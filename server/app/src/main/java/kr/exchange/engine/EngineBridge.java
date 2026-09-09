package kr.exchange.engine;

import java.util.concurrent.locks.ReentrantLock;
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
 * submit은 append와 offer를 한 임계구역에 묶는다. 병렬 호출을 풀어두면
 * append 커밋(=seq 확정)과 offer 사이에 스레드가 밀려 큐 투입 순서가 seq 순서와
 * 역전될 수 있고, 소비측의 seq 단조 증가 계약이 뒤진 seq를 무음 폐기한다.
 * 직렬화 완화는 소비측 seq 연속성 검증(fail-fast + catch-up)이 먼저다(server/README.md M1-2 인계).
 *
 * 그 임계구역을 synchronized가 아니라 ReentrantLock으로 잡는다. 이 구역 안에서 저널 append가
 * DB를 왕복하는데, JDK 21에서 synchronized 안의 블로킹은 가상 스레드를 캐리어에 고정한다.
 * 캐리어 수(=코어 수)만큼 고정되면 스케줄링이 통째로 멈춘다. 2 vCPU 실측에서 주문 16/s에
 * 노드가 영구 정지했다(harness/evidence/gateoff-20260903-2vcpu/). ReentrantLock은 고정하지
 * 않는다. 직렬화 계약은 그대로이고 고정만 사라진다.
 */
public class EngineBridge {

    private final CommandJournalAppender appender;
    private final SingleWriterLoop loop;
    private final CommandPayloadMapper payloadMapper;
    private final AcceptGate acceptGate;
    private final MarketStatusSource marketStatus;
    private final ReentrantLock submitLock = new ReentrantLock();

    // 접수 임계구역 계측(정본 13.5 한계 29). 직렬 구간과 매칭이 상한에서 빠진 뒤
    // 남은 후보가 이 락이다. 모든 접수가 여기를 지나고 안에서 저널 append 와 커밋을 한다.
    //
    // 대기와 보유를 나눈다. 대기가 크면 경합이 상한이고 보유가 크면 임계구역 안의
    // 작업이 상한이다. 둘을 합치지 않으면 어느 쪽인지 알 수 없다.
    //
    // 생성자가 셋이라 MeterRegistry 를 파라미터로 받으면 호출부가 전부 깨진다.
    // 누적기만 노출하고 EngineConfig 가 FunctionCounter 로 읽는다. 엔진 쪽과 같은 방식이다.
    private final java.util.concurrent.atomic.LongAdder lockWaitNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder lockHoldNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder submitCount =
            new java.util.concurrent.atomic.LongAdder();

    /** 락 획득 대기 누적 나노초. 경합의 크기다. */
    public long lockWaitNanos() {
        return lockWaitNanos.sum();
    }

    /** 락 보유 누적 나노초. 임계구역 안 작업의 크기다. */
    public long lockHoldNanos() {
        return lockHoldNanos.sum();
    }

    /** 접수 시도 수. 위 둘의 분모다. 예외 경로도 센다. */
    public long submitCount() {
        return submitCount.sum();
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
        this.appender = appender;
        this.loop = loop;
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

    /** 저널 커밋 전 거부. 게이트웨이 통과와 정지가 겹친 창에서만 난다. */
    public static class MarketHaltedException extends RuntimeException {
        public MarketHaltedException(int marketId) {
            super("마켓 정지 상태: 접수 거부(market=" + marketId + ")");
        }
    }

    public SubmitResult submit(OrderIntake intake) {
        long t0 = System.nanoTime();
        submitLock.lock();
        long t1 = System.nanoTime();
        lockWaitNanos.add(t1 - t0);
        try {
            if (!loop.isRunning()) {
                // 루프 정지 상태의 ACK는 '접수됐지만 영원히 미체결'인 주문을 만든다. 저널 커밋 전에
                // 거부한다. 정지와 경합하는 창은 fatal 처리의 demote가 requireLeader로 마저 닫는다.
                throw new IllegalStateException("단일 라이터 루프 정지 상태: 접수 거부");
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
            boolean queued = loop.offer(new OrderCommand(
                    ack.seq(), intake.marketId(), CommandPayloadMapper.commandType(intake.type()),
                    intake.userId(), intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(),
                    intake.tieBreak(), intake.side(), intake.ordType(), intake.timeInForce(),
                    intake.price(), intake.quantity(), intake.quoteOrderQty(), ack.fencingToken()));
            return new SubmitResult(ack.seq(), ack.fencingToken(), false, queued);
        } finally {
            // 보유 시간은 예외 경로도 센다. 거부로 끝난 접수도 락을 잡고 있었다.
            lockHoldNanos.add(System.nanoTime() - t1);
            submitCount.increment();
            submitLock.unlock();
        }
    }
}
