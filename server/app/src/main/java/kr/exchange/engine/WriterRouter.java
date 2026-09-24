package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * 마켓별 단일 라이터 묶음(ADR 0021 5단계, 파티션 단위는 DB 설계 §5.4-c 7).
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
 * 두면 순서 보장을 유지하면서 마켓 수만큼 수평 확장된다.
 *
 * 배선은 둘이다. 기간 배선(운영)은 승격이 기간과 함께 설치할 때 그 파티션 마켓의 루프를 새로 만든다. 새 루프의 sink·치명
 * 처리기·스냅샷 훅이 그 기간을 쥐고, 다른 파티션 마켓의 루프·엔진·큐는 건드리지 않는다. 기간 전에는 루프가 없다. 종전 배선
 * (시험)은 마켓마다 루프 하나를 두고 설치 때 엔진만 바꾼다.
 */
public final class WriterRouter {

    /** 마켓 하나의 라이터를 만든다. 엔진과 루프를 짝지어 돌려준다(종전 배선). */
    @FunctionalInterface
    public interface LoopFactory {
        Shard create(int marketId);
    }

    /** 기간에 묶인 라이터를 만든다. 루프는 받은 엔진을 쓰고 sink·치명 처리기·스냅샷 훅이 그 기간을 쥔다. */
    @FunctionalInterface
    public interface TermLoopFactory {
        Shard create(int marketId, MatchingEngineImpl engine, LeaderTerm term, ShardSlot slot);
    }

    /** 마켓 하나의 라이터 묶음. */
    public record Shard(MatchingEngineImpl engine, SingleWriterLoop loop) {
    }

    private final LoopFactory legacyFactory;
    private final TermLoopFactory termFactory;
    private final Map<Integer, ShardSlot> slots = new ConcurrentHashMap<>();
    // 생성과 설치를 직렬화한다. 같은 마켓의 첫 주문 둘이 동시에 오면 루프가 둘 생긴다.
    private final Object createLock = new Object();
    private volatile boolean started;
    // 기간 배선의 지연 생성용. 그 마켓 파티션의 게이트가 열린 기간이다. 없으면 루프를 띄우지 않는다.
    private volatile IntFunction<LeaderTerm> openTermOf = marketId -> null;
    private volatile Consumer<ShardSlot> slotAdded = slot -> { };

    public WriterRouter(LoopFactory factory) {
        this.legacyFactory = factory;
        this.termFactory = null;
    }

    public WriterRouter(TermLoopFactory factory) {
        this.legacyFactory = null;
        this.termFactory = factory;
    }

    /** 기간 배선의 지연 생성이 볼 게이트. 설치 전에 건다. */
    public void setOpenTermLookup(IntFunction<LeaderTerm> lookup) {
        this.openTermOf = lookup;
    }

    /** 자리가 처음 생길 때 한 번 부를 통지. 마켓 목록을 기동 시점에 알 수 없어 마켓별 지표를 이때 등록한다. */
    public void onSlotAdded(Consumer<ShardSlot> listener) {
        this.slotAdded = listener;
    }

    private boolean termBound() {
        return termFactory != null;
    }

    private ShardSlot slot(int marketId) {
        return slots.computeIfAbsent(marketId, id -> {
            ShardSlot created = new ShardSlot(id);
            slotAdded.accept(created);
            return created;
        });
    }

    /**
     * 마켓의 라이터를 얻는다. 없으면 만든다. 기간 배선에서 그 마켓 파티션의 게이트가 닫혀 있으면 만들지 않고 null 이다.
     *
     * 왜 지연 생성인가. 기동 시점에 마켓 목록을 알 수 없다. markets 표는 런타임에
     * 채워지고 저널도 처음에는 비어 있다. 통합 시험 여럿은 markets 를 아예 비운 채
     * 주문을 넣는다. 목록을 미리 요구하면 그 경로가 전부 막힌다.
     *
     * 새 샤드의 엔진은 커서 0 에서 출발한다. 샤드가 없던 마켓은 이 노드가 설치한 상태에 그 마켓 명령이 없으므로 그 마켓
     * 명령을 전부 적용해야 한다. 다른 마켓 커서나 스냅샷 seq 를 바닥으로 두면 대역 채번에서 그 아래 명령을 버린다.
     *
     * 생성은 잠금 안에서만 한다. 같은 마켓의 첫 주문 둘이 동시에 오면 루프가 둘 생기고
     * 그러면 그 마켓에 라이터가 둘이라 단일 라이터 계약이 깨진다.
     */
    public Shard shard(int marketId) {
        ShardSlot existing = slots.get(marketId);
        if (existing != null && existing.shard() != null) {
            return existing.shard();
        }
        synchronized (createLock) {
            ShardSlot slot = slot(marketId);
            if (slot.shard() != null) {
                return slot.shard();
            }
            if (!termBound()) {
                Shard created = legacyFactory.create(marketId);
                slot.replace(created, null);
                if (started) {
                    created.loop().start();
                }
                return created;
            }
            LeaderTerm term = openTermOf.apply(marketId);
            if (term == null) {
                return null;
            }
            Shard created = termFactory.create(marketId, new MatchingEngineImpl(), term, slot);
            slot.replace(created, term);
            created.loop().start();
            return created;
        }
    }

    /** 루프가 있는 마켓만 돌려준다. 만들지 않는다. */
    public List<Integer> markets() {
        return slots.values().stream().filter(s -> s.shard() != null).map(ShardSlot::marketId).sorted().toList();
    }

    /** 자리가 있는 마켓의 자리. 지표와 시험이 읽는다. */
    public ShardSlot slotOf(int marketId) {
        return slots.get(marketId);
    }

    public SingleWriterLoop loop(int marketId) {
        Shard s = shard(marketId);
        return s == null ? null : s.loop();
    }

    public MatchingEngineImpl engine(int marketId) {
        Shard s = shard(marketId);
        return s == null ? null : s.engine();
    }

    /** 명령을 그 마켓의 라이터에 넣는다. 그 마켓의 라이터가 없으면 만든다. 만들 수 없으면 거짓이고 저널 catch-up 이 회수한다. */
    public boolean offer(OrderCommand command) {
        Shard s = shard(command.marketId());
        return s != null && s.loop().offer(command);
    }

    /** 저널에만 커밋된 명령의 회수를 그 마켓 라이터에 요청한다. 라이터가 없으면 다음 승격 재생이 회수한다. */
    public void requestCatchUp(int marketId, long seq) {
        SingleWriterLoop loop = loop(marketId);
        if (loop != null) {
            loop.requestCatchUp(marketId, seq);
        }
    }

    /**
     * 복구한 엔진의 북을 마켓별 엔진으로 나눠 담는다(종전 배선, 모든 마켓).
     *
     * 복구는 엔진 하나 위에서 마켓 벡터로 하고(EngineRecovery), 여기서는 그 안의 북을 마켓마다 하나씩
     * 옮겨 담기만 한다. 커서는 마켓마다 복구본의 그 마켓 커서를 심는다. 전역 최댓값을 물려주면 대역 채번에서 낮은 대역
     * 마켓의 새 명령이 커서 아래로 보여 버려진다(ADR 0022 1-3(3)). 복구 결과에 없고 샤드가 있는 마켓은 복구본이 명령을
     * 적용한 적이 없는 마켓이라 커서 0 엔진을 설치한다.
     *
     * 북은 복사해서 담는다. 참조를 그대로 넘기면 복구본과 샤드 엔진이 같은 북을 공유하면서
     * 커서만 따로 들어, 한쪽이 전진해도 다른 쪽 커서가 멈춘 채로 남는다.
     */
    public void installFrom(MatchingEngineImpl recovered) {
        if (termBound()) {
            throw new IllegalStateException("기간 배선은 기간과 대상 마켓을 받아 설치한다(install)");
        }
        installEngines(recovered, marketId -> true);
    }

    /**
     * 기간과 대상 마켓을 받아 설치한다(DB 설계 §5.4-c 7). scope 의 마켓만 바꾸고 그 밖의 자리는 루프·엔진·큐·커서를 그대로 둔다.
     * 기간 배선은 scope 의 기존 루프를 멈추고 그 기간에 묶인 새 루프에 엔진 복사본을 심어 띄운다. scope 안인데 복구 결과에
     * 없는 마켓은 그 파티션에서 명령을 적용한 적이 없는 마켓이라 커서 0 엔진이다. 종전 배선은 scope 의 루프에 엔진만 바꾼다.
     */
    public void install(LeaderTerm term, IntPredicate scope, MatchingEngineImpl recovered) {
        if (!termBound()) {
            installEngines(recovered, scope);
            return;
        }
        if (term == null) {
            throw new IllegalArgumentException("기간 배선의 설치는 기간이 필수다");
        }
        synchronized (createLock) {
            for (int marketId : targets(recovered, scope)) {
                ShardSlot slot = slot(marketId);
                Shard old = slot.shard();
                if (old != null) {
                    // 옛 루프가 쥔 기간은 끝났다. 늦게 깨어나는 스레드의 쓰기는 기간 결속과 DB 가 거부한다.
                    old.loop().halt();
                }
                Shard created = termFactory.create(marketId, copyOf(recovered, marketId), term, slot);
                slot.replace(created, term);
                created.loop().start();
            }
        }
    }

    private void installEngines(MatchingEngineImpl recovered, IntPredicate scope) {
        synchronized (createLock) {
            for (int marketId : targets(recovered, scope)) {
                ShardSlot slot = slot(marketId);
                Shard s = slot.shard() != null ? slot.shard() : shard(marketId);
                MatchingEngineImpl fresh = copyOf(recovered, marketId);
                s.loop().installEngine(fresh);
                slot.replace(new Shard(fresh, s.loop()), null);
            }
        }
    }

    private Set<Integer> targets(MatchingEngineImpl recovered, IntPredicate scope) {
        Set<Integer> targets = new TreeSet<>();
        for (int marketId : recovered.markets()) {
            if (scope.test(marketId)) {
                targets.add(marketId);
            }
        }
        for (ShardSlot slot : slots.values()) {
            if (slot.shard() != null && scope.test(slot.marketId())) {
                targets.add(slot.marketId());
            }
        }
        return targets;
    }

    private static MatchingEngineImpl copyOf(MatchingEngineImpl recovered, int marketId) {
        MatchingEngineImpl fresh = new MatchingEngineImpl();
        OrderBookImpl book = recovered.orderBook(marketId);
        if (book != null) {
            fresh.loadMarketSnapshot(marketId, recovered.appliedSeq(marketId), book.copy());
        }
        return fresh;
    }

    /** 리더십 상실 시 전부 즉시 정지. 잔여 큐를 버린다. */
    public void haltAll() {
        halt(marketId -> true);
    }

    /** scope 의 루프만 즉시 정지하고 큐를 버린다. 다른 파티션 마켓의 루프는 그대로 돈다. */
    public void halt(IntPredicate scope) {
        for (ShardSlot slot : slots.values()) {
            Shard s = slot.shard();
            if (s != null && scope.test(slot.marketId())) {
                s.loop().halt();
            }
        }
    }

    /**
     * 전부 살아 있어야 running 이다(종전 배선). 기간 배선은 isRunning(scope) 를 쓴다.
     *
     * 접수 한 건의 게이트는 이것이 아니라 isRunning(int) 다. 한 마켓 라이터의 정지로 다른 마켓 접수를
     * 닫지 않는다.
     */
    public boolean isRunning() {
        if (!started) {
            return false;
        }
        return slots.values().stream().allMatch(s -> s.shard() == null || s.shard().loop().isRunning());
    }

    /** scope 의 루프가 모두 돌 때 참이다. 승격이 그 파티션 게이트를 열기 전에 본다. 루프가 아직 없는 마켓은 막지 않는다. */
    public boolean isRunning(IntPredicate scope) {
        if (!termBound()) {
            return isRunning();
        }
        for (ShardSlot slot : slots.values()) {
            Shard s = slot.shard();
            if (s != null && scope.test(slot.marketId()) && !s.loop().isRunning()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 그 마켓의 접수를 받을 수 있는가(ADR 0022 4-2). 한 샤드의 정지는 그 마켓의 접수만 닫는다.
     *
     * 샤드가 아직 없는 마켓은 참이다. 첫 offer 가 샤드를 만들고 띄우므로 닫을 사유가 없다. 기간 배선에서는 그 마켓 파티션의
     * 게이트가 열려 있어야 만들 수 있다. 판정이 샤드를 만들지 않는다. 만들면 뒤 게이트에서 거부될 접수도 라이터 스레드를 띄운다.
     */
    public boolean isRunning(int marketId) {
        ShardSlot slot = slots.get(marketId);
        Shard shard = slot == null ? null : slot.shard();
        if (termBound()) {
            return shard != null ? shard.loop().isRunning() : openTermOf.apply(marketId) != null;
        }
        if (!started) {
            return false;
        }
        return shard == null || shard.loop().isRunning();
    }

    /** 큐 깊이 합. 지표는 마켓별로도 따로 등록한다. */
    public int queueSize() {
        return slots.values().stream().mapToInt(ShardSlot::queueSize).sum();
    }

    /**
     * 이미 만들어진 라이터를 전부 띄우고, 이후 생기는 것도 만들자마자 뜨게 한다(종전 배선). 기간 배선은 설치가 루프를 띄운다.
     *
     * started 를 잠금 안에서 세운다. 밖에서 세우면 그 사이에 생긴 루프가 여기서도
     * 안 뜨고 생성 시점에도 안 떠서 영영 멈춘 채로 남는다.
     */
    public void startAll() {
        synchronized (createLock) {
            started = true;
            for (ShardSlot slot : slots.values()) {
                Shard s = slot.shard();
                if (s != null) {
                    s.loop().start();
                }
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
        for (ShardSlot slot : slots.values()) {
            Shard s = slot.shard();
            if (s == null) {
                continue;
            }
            try {
                s.loop().stop();
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
