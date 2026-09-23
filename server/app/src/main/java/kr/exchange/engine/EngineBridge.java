package kr.exchange.engine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;

/**
 * 접수 배선: 저널 append(COMMIT=ACK) 후에만 명령을 단일 라이터 큐에 공급한다.
 * Log-first 순서가 뒤집히면 크래시 창에서 '저널에 없는 적용된 명령'이 생겨
 * RPO 0 주장이 깨진다(DB 설계 §5.1, API 설계 §0-2 (1)).
 *
 * submit은 append와 offer를 그 마켓의 임계구역에 묶는다. 풀어두면 append 커밋(=seq 확정)과
 * offer 사이에 스레드가 밀려 큐 투입 순서가 seq 순서와 역전될 수 있고, 소비측의 seq 단조 증가
 * 계약이 뒤진 seq를 무음 폐기한다. 소비측이 마켓별 라이터라(WriterRouter) 그 계약이 요구하는 것은
 * 마켓 안의 투입 순서다. 마켓 m 의 채번과 커밋과 offer 가 m 의 락 안에서만 일어나므로 m 의 명령은
 * 락 획득 순서대로 seq 를 받고 같은 순서로 큐에 들어간다. 마켓 사이에는 순서 계약이 없다(ADR 0022 4-1).
 *
 * 그 임계구역을 synchronized가 아니라 ReentrantLock으로 잡는다. 이 구역 안에서 저널 append가
 * DB를 왕복하는데, JDK 21에서 synchronized 안의 블로킹은 가상 스레드를 캐리어에 고정한다.
 * 캐리어 수(=코어 수)만큼 고정되면 스케줄링이 통째로 멈춘다. 2 vCPU 실측에서 주문 16/s에
 * 노드가 영구 정지했다(harness/evidence/gateoff-20260903-2vcpu/). ReentrantLock은 고정하지
 * 않는다. 직렬화 계약은 그대로이고 고정만 사라진다.
 */
public class EngineBridge {

    private final CommandJournalAppender appender;
    private final CommandSink sink;
    private final CommandPayloadMapper payloadMapper;
    private final AcceptGate acceptGate;
    private final MarketStatusSource marketStatus;
    private final ConcurrentHashMap<Integer, MarketLock> submitLocks = new ConcurrentHashMap<>();
    private volatile IntConsumer marketAdded = marketId -> { };

    /**
     * 마켓 하나의 접수 임계구역과 그 계측(정본 13.5 한계 29).
     *
     * 대기와 보유를 나눈다. 대기가 크면 경합이 상한이고 보유가 크면 임계구역 안의 작업이 상한이다.
     * 생성자가 여럿이라 MeterRegistry 를 받지 않고 누적기만 노출한다. EngineConfig 가 마켓 태그를
     * 붙여 FunctionCounter 로 읽는다.
     */
    private static final class MarketLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final LongAdder waitNanos = new LongAdder();
        private final LongAdder holdNanos = new LongAdder();
        private final LongAdder submits = new LongAdder();
    }

    /** 그 마켓의 락 획득 대기 누적 나노초. 경합의 크기다. 접수가 없던 마켓은 0이다. */
    public long lockWaitNanos(int marketId) {
        MarketLock market = submitLocks.get(marketId);
        return market == null ? 0 : market.waitNanos.sum();
    }

    /** 그 마켓의 락 보유 누적 나노초. 임계구역 안 작업의 크기다. */
    public long lockHoldNanos(int marketId) {
        MarketLock market = submitLocks.get(marketId);
        return market == null ? 0 : market.holdNanos.sum();
    }

    /** 그 마켓의 접수 시도 수. 위 둘의 분모다. 예외 경로도 센다. */
    public long submitCount(int marketId) {
        MarketLock market = submitLocks.get(marketId);
        return market == null ? 0 : market.submits.sum();
    }

    /**
     * 마켓이 처음 접수될 때 한 번 부를 통지를 건다. 마켓 목록을 기동 시점에 알 수 없어 마켓별 지표를
     * 이때 등록한다. 첫 접수 전에 건다. 그 마켓의 락을 만드는 자리에서 돌므로 짧게 끝나야 한다.
     */
    public void onMarketAdded(IntConsumer listener) {
        this.marketAdded = listener;
    }

    public EngineBridge(CommandJournalAppender appender, SingleWriterLoop loop,
                        CommandPayloadMapper payloadMapper) {
        this(appender, loop, payloadMapper, AcceptGate.ALWAYS_OPEN, NEVER_HALTED);
    }

    public EngineBridge(CommandJournalAppender appender, SingleWriterLoop loop,
                        CommandPayloadMapper payloadMapper, AcceptGate acceptGate) {
        this(appender, loop, payloadMapper, acceptGate, NEVER_HALTED);
    }

    public EngineBridge(CommandJournalAppender appender, SingleWriterLoop loop,
                        CommandPayloadMapper payloadMapper, AcceptGate acceptGate,
                        MarketStatusSource marketStatus) {
        this(appender, CommandSink.of(loop), payloadMapper, acceptGate, marketStatus);
    }

    /** 마켓별 라이터 배선(ADR 0021 5단계). 운영이 쓴다. */
    public EngineBridge(CommandJournalAppender appender, WriterRouter router,
                        CommandPayloadMapper payloadMapper, AcceptGate acceptGate,
                        MarketStatusSource marketStatus) {
        this(appender, CommandSink.of(router), payloadMapper, acceptGate, marketStatus);
    }

    /** 명령을 받을 곳을 직접 꽂는다. 운영은 위 배선을 거치고, 시험이 투입 순서를 기록할 때 쓴다. */
    EngineBridge(CommandJournalAppender appender, CommandSink sink,
                 CommandPayloadMapper payloadMapper, AcceptGate acceptGate,
                 MarketStatusSource marketStatus) {
        this.appender = appender;
        this.sink = sink;
        this.payloadMapper = payloadMapper;
        this.acceptGate = acceptGate;
        this.marketStatus = marketStatus;
    }

    /** 엔진이 마켓 카탈로그 전체를 알 필요는 없다. 정지 여부 하나만 본다. */
    @FunctionalInterface
    public interface MarketStatusSource {
        boolean isHalted(int marketId);
    }

    /** 카탈로그 없이 엔진만 세우는 경로용. 정지 판정은 그 경우 호출자 책임이다. */
    public static final MarketStatusSource NEVER_HALTED = marketId -> false;

    /**
     * 저널 커밋 전 거부. 주문 생성은 게이트웨이 검증과 정지가 겹친 창에서만 나고, 취소는 게이트웨이가
     * 마켓 상태를 보지 않아 정지 중이면 늘 난다.
     */
    public static class MarketHaltedException extends RuntimeException {
        public MarketHaltedException(int marketId) {
            super("마켓 정지 상태: 접수 거부(market=" + marketId + ")");
        }
    }

    /**
     * 저널에 커밋된 것이 확인됐지만 큐에 넣지 못한 명령을 그 마켓 라이터가 seq 까지 적용하게 한다.
     * 접수가 append 예외를 받은 뒤 그 명령의 커밋을 저널에서 확인한 경우에 쓴다(OrderGateway).
     */
    public void requestCatchUp(int marketId, long seq) {
        sink.requestCatchUp(marketId, seq);
    }

    public SubmitResult submit(OrderIntake intake) {
        MarketLock market = submitLocks.computeIfAbsent(intake.marketId(), marketId -> {
            marketAdded.accept(marketId);
            return new MarketLock();
        });
        long t0 = System.nanoTime();
        market.lock.lock();
        long t1 = System.nanoTime();
        market.waitNanos.add(t1 - t0);
        try {
            if (!sink.isRunning(intake.marketId())) {
                // 루프 정지 상태의 ACK는 '접수됐지만 영원히 미체결'인 주문을 만든다. 저널 커밋 전에
                // 거부한다. 정지와 경합하는 창은 fatal 처리의 demote가 requireLeader로 마저 닫는다.
                throw new IllegalStateException(
                        "단일 라이터 루프 정지 상태: 접수 거부(market=" + intake.marketId() + ")");
            }
            if (!acceptGate.isAccepting()) {
                // 리더지만 승격 재생(PROMOTING) 중이다. head 선복원 전 접수를 열면 catch-up이
                // 대규모 delta를 정산 sink로 태우는 결함 경로가 재발한다. 저널 커밋 전에 거부한다.
                throw new NotReadyException("승격 재생 진행 중: 접수 보류");
            }
            // 게이트웨이 검사와 여기 사이에 정지가 들어오면 통과한 주문이 저널에 남는다.
            // 로컬 스냅샷을 다시 보고 저널 커밋 전에 끊는다. DB 를 보지 않는다.
            if (marketStatus.isHalted(intake.marketId())) {
                throw new MarketHaltedException(intake.marketId());
            }
            CommandDraft draft = new CommandDraft(
                    intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                    intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                    payloadMapper.toPayloadJson(intake));
            AppendResult ack = appender.append(draft);
            if (ack.replayed()) {
                // 원본 명령이 이미 큐 또는 저널 catch-up 경로에 있다. 재투입하면 이중 적용 후보만 늘린다.
                return new SubmitResult(ack.seq(), ack.fencingToken(), true, false);
            }
            boolean queued = sink.offer(new OrderCommand(
                    ack.seq(), intake.marketId(), CommandPayloadMapper.commandType(intake.type()),
                    intake.userId(), intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(),
                    intake.tieBreak(), intake.side(), intake.ordType(), intake.timeInForce(),
                    intake.price(), intake.quantity(), intake.quoteOrderQty(), ack.fencingToken()));
            return new SubmitResult(ack.seq(), ack.fencingToken(), false, queued);
        } finally {
            // 보유 시간은 예외 경로도 센다. 거부로 끝난 접수도 락을 잡고 있었다.
            market.holdNanos.add(System.nanoTime() - t1);
            market.submits.increment();
            market.lock.unlock();
        }
    }
}
