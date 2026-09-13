package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 마켓별 단일 라이터 묶음(ADR 0021 5단계).
 *
 * 왜 이것이 필요한가. 종전에는 SingleWriterLoop 하나가 전 마켓 명령을 한 스레드로
 * 처리했고, 그 스레드가 정산까지 했으므로 커넥션을 한 번에 하나만 썼다. 2026-09-10
 * 실측에서 풀 32에 평균 활성 연결이 0.40개였고 표본의 73.8퍼센트가 0개였다. 같은 창에서
 * 수용 거절이 초당 100건이었다. 초당 100건을 거절하면서 데이터베이스가 놀고 있었다.
 *
 * 직렬 구간 1건당 21.87밀리초 중 21.84가 정산과 발행이다. 매칭은 0.017이다. 그 정산을
 * 마켓마다 따로 돌리면 마켓 수만큼 커넥션이 동시에 붙는다. 인스턴스는 늘리지 않는다.
 * 라이터가 스레드이기 때문이다.
 *
 * 왜 마켓이 축인가. 단일 라이터를 둔 사유는 재생 결정론이고, 그것이 요구하는 것은
 * 마켓 안의 단일 순서이지 거래소 전체의 단일 순서가 아니다. 마켓마다 라이터를 하나씩
 * 두면 순서 보장을 유지하면서 마켓 수만큼 수평 확장된다. 4단계 shadow 비교가 그 전제를
 * 실증했다. 한 마켓만 골라 재생한 북이 전 마켓을 재생한 북 중 그 마켓 것과 바이트가 같다.
 *
 * 이 클래스는 이음매다. 호출측이 보던 표면(offer, isRunning)을 그대로 두고 안에서만
 * 마켓으로 가른다. 그래야 EngineBridge 와 수명주기 배선이 통째로 바뀌지 않는다.
 */
public final class WriterRouter {

    /** 마켓 하나의 라이터를 만든다. 엔진과 루프를 짝지어 돌려준다. */
    @FunctionalInterface
    public interface LoopFactory {
        Shard create(int marketId);
    }

    /** 마켓 하나의 라이터 묶음. */
    public record Shard(MatchingEngineImpl engine, SingleWriterLoop loop) {
    }

    private final LoopFactory factory;
    private final Map<Integer, Shard> shards = new ConcurrentHashMap<>();
    // 지연 생성을 직렬화한다. 같은 마켓의 첫 주문 둘이 동시에 오면 루프가 둘 생긴다.
    private final Object createLock = new Object();
    private volatile boolean started;
    // 마지막 설치가 심은 마켓별 커서와, 커서 없는 마켓의 바닥(복구본의 스냅샷 seq). 그 뒤에 생기는 샤드는
    // 자기 마켓 커서에서 출발한다. 0 에서 출발하면 갭 회수가 그 마켓 전 이력을 다시 읽고, 다른 마켓의
    // 더 큰 seq 에서 출발하면 대역 채번에서 제 마켓의 새 명령을 커서 아래로 보고 버린다.
    private volatile Map<Integer, Long> installedCursors = Map.of();
    private volatile long installedFloor;

    public WriterRouter(LoopFactory factory) {
        this.factory = factory;
    }

    /**
     * 마켓의 라이터를 얻는다. 없으면 만든다.
     *
     * 왜 지연 생성인가. 기동 시점에 마켓 목록을 알 수 없다. markets 표는 런타임에
     * 채워지고 저널도 처음에는 비어 있다. 통합 시험 여럿은 markets 를 아예 비운 채
     * 주문을 넣는다. 목록을 미리 요구하면 그 경로가 전부 막힌다.
     *
     * 생성은 잠금 안에서만 한다. 같은 마켓의 첫 주문 둘이 동시에 오면 루프가 둘 생기고
     * 그러면 그 마켓에 라이터가 둘이라 단일 라이터 계약이 깨진다.
     */
    public Shard shard(int marketId) {
        Shard existing = shards.get(marketId);
        if (existing != null) {
            return existing;
        }
        synchronized (createLock) {
            return shards.computeIfAbsent(marketId, id -> {
                Shard created = factory.create(id);
                long start = installedCursors.getOrDefault(id, installedFloor);
                if (start > 0) {
                    created.engine().loadSnapshot(start, List.of());
                }
                if (started) {
                    created.loop().start();
                }
                return created;
            });
        }
    }

    /** 이미 만들어진 마켓만 돌려준다. 만들지 않는다. */
    public List<Integer> markets() {
        return shards.keySet().stream().sorted().toList();
    }

    public SingleWriterLoop loop(int marketId) {
        return shard(marketId).loop();
    }

    public MatchingEngineImpl engine(int marketId) {
        return shard(marketId).engine();
    }

    /** 명령을 그 마켓의 라이터에 넣는다. 그 마켓의 라이터가 없으면 만든다. */
    public boolean offer(OrderCommand command) {
        return shard(command.marketId()).loop().offer(command);
    }

    /**
     * 복구한 엔진의 북을 마켓별 엔진으로 나눠 담는다.
     *
     * 복구는 엔진 하나 위에서 마켓 벡터로 하고(EngineRecovery), 여기서는 그 안의 북을 마켓마다 하나씩
     * 옮겨 담기만 한다. MatchingEngineImpl 은 마켓별 OrderBookImpl 을 들고 라우팅할 뿐이고 북 자체는
     * 이미 마켓 단위다.
     *
     * 커서는 마켓마다 복구본의 그 마켓 커서를 심는다. 전역 최댓값을 물려주면 대역 채번에서 낮은 대역
     * 마켓의 새 명령이 커서 아래로 보여 버려진다(ADR 0022 1-3(3)).
     *
     * 복구 결과에 없는 마켓은 명령이 아직 없었던 마켓이다. 복구본의 바닥(스냅샷 seq, 없으면 0)에서
     * 출발한다.
     *
     * 북은 복사해서 담는다. 참조를 그대로 넘기면 복구본과 샤드 엔진이 같은 북을 공유하면서
     * 커서만 따로 들어, 한쪽이 전진해도 다른 쪽 커서가 멈춘 채로 남는다.
     */
    public void installFrom(MatchingEngineImpl recovered) {
        synchronized (createLock) {
            for (int marketId : recovered.markets()) {
                Shard s = shard(marketId);
                MatchingEngineImpl fresh = new MatchingEngineImpl();
                fresh.loadMarketSnapshot(marketId, recovered.appliedSeq(marketId),
                        recovered.orderBook(marketId).copy());
                s.loop().installEngine(fresh);
                shards.put(marketId, new Shard(fresh, s.loop()));
            }
            Map<Integer, Long> cursors = recovered.appliedSeqs();
            long floor = recovered.floorSeq();
            for (Map.Entry<Integer, Shard> e : shards.entrySet()) {
                if (recovered.markets().contains(e.getKey())) {
                    continue;
                }
                MatchingEngineImpl fresh = new MatchingEngineImpl();
                long start = cursors.getOrDefault(e.getKey(), floor);
                if (start > 0) {
                    fresh.loadSnapshot(start, List.of());
                }
                e.getValue().loop().installEngine(fresh);
                shards.put(e.getKey(), new Shard(fresh, e.getValue().loop()));
            }
            installedCursors = cursors;
            installedFloor = floor;
        }
    }

    /** 리더십 상실 시 전부 즉시 정지. 잔여 큐를 버린다. */
    public void haltAll() {
        for (Shard s : shards.values()) {
            s.loop().halt();
        }
    }

    /**
     * 전부 살아 있어야 running 이다.
     *
     * 하나라도 죽었으면 그 마켓의 접수가 갈 곳이 없다. 부분 생존을 running 으로 보면
     * 죽은 마켓의 주문이 등록되지 않은 마켓 예외로 떨어지거나 큐에 쌓이기만 한다.
     * 접수 게이트는 전체 기준으로 닫는다. 마켓별 게이트는 이 판의 범위가 아니다.
     */
    public boolean isRunning() {
        if (!started) {
            return false;
        }
        return shards.values().stream().allMatch(s -> s.loop().isRunning());
    }

    /** 큐 깊이 합. 지표는 마켓별로도 따로 등록한다. */
    public int queueSize() {
        return shards.values().stream().mapToInt(s -> s.loop().queueSize()).sum();
    }

    /**
     * 이미 만들어진 라이터를 전부 띄우고, 이후 생기는 것도 만들자마자 뜨게 한다.
     *
     * started 를 잠금 안에서 세운다. 밖에서 세우면 그 사이에 생긴 루프가 여기서도
     * 안 뜨고 생성 시점에도 안 떠서 영영 멈춘 채로 남는다.
     */
    public void startAll() {
        synchronized (createLock) {
            started = true;
            for (Shard s : shards.values()) {
                s.loop().start();
            }
        }
    }

    /**
     * 전부 멈춘다. 하나가 실패해도 나머지를 계속 멈춘다.
     *
     * 중간에 던지면 남은 라이터가 계속 돌아 셧다운이 반쪽이 된다. 그러면 큐에 남은
     * 명령이 정산되면서 리스가 이미 넘어간 뒤에 쓰기가 일어날 수 있다.
     */
    public void stopAll() throws InterruptedException {
        InterruptedException interrupted = null;
        RuntimeException failure = null;
        synchronized (createLock) {
            started = false;
        }
        for (SingleWriterLoop loop : shards.values().stream().map(Shard::loop).toList()) {
            try {
                loop.stop();
            } catch (InterruptedException ex) {
                interrupted = ex;
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                }
            }
        }
        if (interrupted != null) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (failure != null) {
            throw failure;
        }
    }
}
