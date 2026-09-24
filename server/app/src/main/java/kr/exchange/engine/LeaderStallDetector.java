package kr.exchange.engine;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 좀비 리더 판정기(DB 설계 §5.4 자발적 강등, §5.4-c 8). 쓰기 수요 대비 누적 완료율이 붕괴한 상태가 임계
 * 시간 지속되면 불능으로 본다. 원인은 묻지 않는다. 비율 신호·누적 창·수요 하한·hold·알려진
 * 사각의 근거는 그 절에 있다. 강등 자체는 LeaderLeaseService가 하고 여기서는 상태를 만들지 않는다.
 *
 * 노드 층과 파티션 층이 같은 판정기를 쓴다. 노드 층은 인증 이전의 주문 쓰기 수요를, 파티션 층은 게이트가 열린 그 파티션의
 * 접수를 센다. 두 층 모두 마켓 접수 경합으로 끝난 요청(excluded)은 수요에서 뺀다.
 */
public final class LeaderStallDetector implements NodeServiceHealth {

    private static final Logger log = LoggerFactory.getLogger(LeaderStallDetector.class);

    // 완료/수요가 이 값 미만이면 처리율 붕괴로 본다. 정상은 1에 가깝고 실측 좀비는 0.001 수준이라
    // 두 상태가 세 자릿수 벌어진다. 5%는 그 사이 어디에 둬도 무방한 지점의 보수적 선택이다.
    private static final double MIN_COMPLETION_RATIO = 0.05;
    // 비율 판정에 필요한 최소 누적 수요. 임계 비율의 역수라, 완료 1건이면 임계를 넘는 최소 분모다.
    private static final long MIN_WINDOW_DEMAND = 20;

    private final LeaderWriteProgress progress;
    private final long standdownAfterNanos;
    private final LongSupplier nanoClock;
    // 판정기 자신의 강등 판정 수. 운영 배선은 파티션 강등(STALL)을 exchange.leader.standdown{partition} 으로 세므로 비어 있다.
    private final Counter standdowns;
    // 로그에서 층을 가른다.
    private final String scope;
    // 진단 전용. 판정에는 쓰지 않는다(원인 불문 원칙).
    private final HikariDataSource requestPool;
    // 1 정상, 0 강등 판정. 하네스가 좀비 판정 시점을 외부에서 관측하는 단일 지표다.
    private final AtomicInteger serviceable = new AtomicInteger(1);
    // 마지막으로 평가한 누적 완료율. 라이브에서 붕괴 진행을 눈으로 좇는 축이다.
    private volatile double lastRatio = 1.0;

    private long lastAttempts;
    private long lastCompletions;
    private long lastExcluded;
    private long windowDemand;
    private long windowCompletions;
    private boolean stalled;
    private long stalledSinceNanos;
    private boolean standdownLogged;

    public LeaderStallDetector(DataSource requestDataSource, LeaderWriteProgress progress,
                               Duration standdownAfter, MeterRegistry registry) {
        this(requestDataSource, progress, standdownAfter, registry, System::nanoTime);
    }

    /** 단독 배선. exchange.leader.standdown 을 판정기가 스스로 센다. */
    LeaderStallDetector(DataSource requestDataSource, LeaderWriteProgress progress,
                        Duration standdownAfter, MeterRegistry registry, LongSupplier nanoClock) {
        this(requestDataSource, progress, standdownAfter, registry, nanoClock, "exchange.node", Tags.empty(),
                "노드", true);
    }

    private LeaderStallDetector(DataSource requestDataSource, LeaderWriteProgress progress,
                                Duration standdownAfter, MeterRegistry registry, LongSupplier nanoClock,
                                String prefix, Tags tags, String scope, boolean countStanddowns) {
        if (progress == null || standdownAfter == null || registry == null || nanoClock == null) {
            throw new IllegalArgumentException("progress·standdownAfter·registry·clock은 필수");
        }
        if (standdownAfter.isZero() || standdownAfter.isNegative()) {
            throw new IllegalArgumentException("standdownAfter는 양수여야 한다");
        }
        this.requestPool = requestDataSource instanceof HikariDataSource hikari ? hikari : null;
        this.progress = progress;
        this.lastAttempts = progress.attempts();
        this.lastCompletions = progress.completions();
        this.lastExcluded = progress.excluded();
        this.standdownAfterNanos = standdownAfter.toNanos();
        this.nanoClock = nanoClock;
        this.scope = scope;
        this.standdowns = countStanddowns
                ? Counter.builder("exchange.leader.standdown")
                        .description("서비스 불능 지속으로 리스 갱신을 자발적으로 포기한 횟수")
                        .register(registry)
                : null;
        Gauge.builder(prefix + ".serviceable", serviceable, AtomicInteger::doubleValue)
                .description("리더 일 수행 가능(1)·서비스 불능 지속(0)")
                .tags(tags)
                .register(registry);
        Gauge.builder(prefix + ".write_completion_ratio", this, d -> d.lastRatio)
                .description("누적 쓰기 완료율(완료/수요). 붕괴 판정 축")
                .tags(tags)
                .register(registry);
    }

    /** 운영 노드 층. 강등 수는 파티션 강등(STALL)이 센다. */
    public static LeaderStallDetector node(DataSource requestDataSource, LeaderWriteProgress progress,
                                           Duration standdownAfter, MeterRegistry registry) {
        return node(requestDataSource, progress, standdownAfter, registry, System::nanoTime);
    }

    static LeaderStallDetector node(DataSource requestDataSource, LeaderWriteProgress progress,
                                    Duration standdownAfter, MeterRegistry registry, LongSupplier nanoClock) {
        return new LeaderStallDetector(requestDataSource, progress, standdownAfter, registry, nanoClock,
                "exchange.node", Tags.empty(), "노드", false);
    }

    /** 운영 파티션 층. 지표는 exchange.partition.serviceable{partition}·write_completion_ratio{partition} 이다. */
    public static LeaderStallDetector partition(String resource, LeaderWriteProgress progress,
                                                Duration standdownAfter, MeterRegistry registry) {
        return partition(resource, progress, standdownAfter, registry, System::nanoTime);
    }

    static LeaderStallDetector partition(String resource, LeaderWriteProgress progress,
                                         Duration standdownAfter, MeterRegistry registry, LongSupplier nanoClock) {
        return new LeaderStallDetector(null, progress, standdownAfter, registry, nanoClock,
                "exchange.partition", Tags.of("partition", resource), "파티션 " + resource, false);
    }

    /**
     * 승격 경계에서 창을 다시 세운다(라이브 4차 실패 방지선). 팔로워는 renew를 하지 않아
     * evaluate가 한 번도 돌지 않으므로 lastAttempts가 기동 시점에 고정된다. 승격 후 첫 표본이
     * 팔로워 구간 수요 전량을 완료 0과 함께 창에 접고, 비율이 임계를 회복하려면 그 분모의 5%를
     * 새로 채워야 해서 정상 처리율로도 임계 시간 안에 회복이 불가능하다. 결과는 페일오버
     * standdownAfter 초 뒤의 확정적 재강등이며, 2노드에서는 곧 클러스터 정지다.
     */
    @Override
    public synchronized void onLeadershipGained() {
        lastAttempts = progress.attempts();
        lastCompletions = progress.completions();
        lastExcluded = progress.excluded();
        lastRatio = 1.0;
        resetWindow();
    }

    @Override
    public synchronized boolean serviceable() {
        try {
            return evaluate();
        } catch (RuntimeException e) {
            // 판정 입력이 인메모리 카운터뿐이라 여기 오지 않아야 정상이다. 그래도 예외를 흘리면
            // renew SQL이 실행되지 않아 리스가 TTL 만료로 소멸하므로(비자발적 페일오버) 흡수한다.
            log.warn("좀비 리더 판정 실패, 이번 표본은 정상으로 흡수한다", e);
            return true;
        }
    }

    private boolean evaluate() {
        long attempts = progress.attempts();
        long completions = progress.completions();
        long excluded = progress.excluded();
        // 경합으로 끝난 요청을 수요에서 뺀다. 수요와 제외를 다른 시점에 세므로 표본 경계에서 음수가 나올 수 있어 0으로 자른다.
        long newDemand = Math.max(0, (attempts - lastAttempts) - (excluded - lastExcluded));
        long newCompletions = completions - lastCompletions;
        windowDemand += newDemand;
        windowCompletions += newCompletions;
        lastAttempts = attempts;
        lastCompletions = completions;
        lastExcluded = excluded;

        if (windowDemand < MIN_WINDOW_DEMAND) {
            // 증거 부족. arm도 초기화도 하지 않는다. 무주문 유휴 노드는 여기서 영원히 멈춘다.
            return verdict();
        }
        double ratio = (double) windowCompletions / windowDemand;
        lastRatio = ratio;
        if (ratio >= MIN_COMPLETION_RATIO) {
            resetWindow();
            return true;
        }
        if (!stalled) {
            stalled = true;
            stalledSinceNanos = nanoClock.getAsLong();
            log.warn("리더 처리율 붕괴 관측 시작({}): 완료율 {}({}/{}), {}초 더 지속되면 강등한다",
                    scope, String.format("%.4f", ratio), windowCompletions, windowDemand,
                    standdownAfterNanos / 1_000_000_000L);
        }
        return verdict();
    }

    private boolean verdict() {
        if (!stalled) {
            return true;
        }
        long stalledNanos = nanoClock.getAsLong() - stalledSinceNanos;
        if (stalledNanos < standdownAfterNanos) {
            return true;
        }
        serviceable.set(0);
        if (!standdownLogged) {
            standdownLogged = true;
            if (standdowns != null) {
                standdowns.increment();
            }
            log.error("서비스 불능 지속 판정({}): 완료율 {}({}/{}), 지속 {}ms, {}",
                    scope, String.format("%.4f", lastRatio), windowCompletions, windowDemand,
                    stalledNanos / 1_000_000, poolDiagnostics());
        }
        return false;
    }

    private void resetWindow() {
        windowDemand = 0;
        windowCompletions = 0;
        stalled = false;
        standdownLogged = false;
        serviceable.set(1);
    }

    /** 진단 문자열. 원인 분류(커넥션 고갈인지 CPU·스레드 기아인지)를 사후에 가르는 데만 쓴다. */
    private String poolDiagnostics() {
        if (requestPool == null) {
            return "요청 풀 관측 불가";
        }
        try {
            HikariPoolMXBean pool = requestPool.getHikariPoolMXBean();
            if (pool == null) {
                return "요청 풀 미초기화";
            }
            return "요청 풀 점유 " + pool.getActiveConnections() + "/"
                    + requestPool.getMaximumPoolSize()
                    + ", 대기 " + pool.getThreadsAwaitingConnection();
        } catch (RuntimeException e) {
            return "요청 풀 조회 실패: " + e.getClass().getSimpleName();
        }
    }
}
