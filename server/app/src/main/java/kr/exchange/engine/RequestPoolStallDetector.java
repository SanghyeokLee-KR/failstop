package kr.exchange.engine;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 좀비 리더 판정기(DB 설계 §5.4 자발적 강등). 요청용 HikariCP 풀의 고갈과 리더 쓰기 진행성을
 * 함께 본다.
 *
 * 판정 신호 선택 근거. '느리다'와 '일을 못 한다'를 가르는 것이 전부다.
 *  - 요청 풀에서 커넥션을 직접 얻어 보는 능동 프로브는 쓰지 않는다. HikariCP는 대기자를 FIFO로
 *    처리하므로, 과부하로 대기열이 길기만 해도 프로브가 계속 실패한다. 그 신호로 강등하면
 *    992e59b 이전(요청 풀 포화 = 하트비트 기아 = 헛페일오버)으로 회귀한다.
 *  - 대신 두 축의 논리곱을 쓴다. ① 요청 풀 전량 점유(active >= maximumPoolSize): 대기자 유무와
 *    무관한 신호라 LB가 트래픽을 끊은 뒤에도 성립한다. ② 리더 쓰기 커밋 진행 0: 표본 사이에
 *    command_journal append·정산 커밋이 한 건도 없었다. 지연으로 느려졌을 뿐인 노드는 커밋이
 *    계속 나므로 ②에서 걸러지고, 커넥션이 반납되지 않아 얼어붙은 노드만 두 축을 동시에 만족한다.
 *  - 두 축 모두 인메모리 조회다. 판정이 DB를 타면 판정 자체가 굶어 하트비트를 막는다.
 *
 * 임계 근거(standdownAfter). 두 축이 끊김 없이 이 기간 내내 유지돼야 강등한다. 표본 하나라도
 * 정상이면 누적이 초기화된다. 기본 45초는 리스 TTL 기본 10초의 4.5배, 카오스 6초의 7.5배로
 * 정상 갱신 주기와 겹치지 않고, GC·체크포인트급 일시 정지보다 훨씬 길며, 실측 좀비 지속시간
 * (30분 이상)보다는 훨씬 짧다. 총 페일오버 시간은 standdownAfter + TTL이다.
 *
 * 강등은 이 판정기가 아니라 LeaderLeaseService가 수행한다. 여기서는 상태를 만들지 않는다.
 */
public final class RequestPoolStallDetector implements NodeServiceHealth {

    private static final Logger log = LoggerFactory.getLogger(RequestPoolStallDetector.class);

    private final HikariDataSource requestPool;
    private final LeaderWriteProgress progress;
    private final long standdownAfterNanos;
    private final LongSupplier nanoClock;
    private final Counter standdowns;
    // 1 정상, 0 강등 판정. 하네스가 좀비 판정 시점을 외부에서 관측하는 단일 지표다.
    private final AtomicInteger serviceable = new AtomicInteger(1);

    private long lastCommits = Long.MIN_VALUE;
    private boolean stalled;
    private long stalledSinceNanos;
    private boolean standdownLogged;

    public RequestPoolStallDetector(DataSource requestDataSource, LeaderWriteProgress progress,
                                    Duration standdownAfter, MeterRegistry registry) {
        this(requestDataSource, progress, standdownAfter, registry, System::nanoTime);
    }

    RequestPoolStallDetector(DataSource requestDataSource, LeaderWriteProgress progress,
                             Duration standdownAfter, MeterRegistry registry,
                             LongSupplier nanoClock) {
        if (progress == null || standdownAfter == null || registry == null || nanoClock == null) {
            throw new IllegalArgumentException("progress·standdownAfter·registry·clock은 필수");
        }
        if (standdownAfter.isZero() || standdownAfter.isNegative()) {
            throw new IllegalArgumentException("standdownAfter는 양수여야 한다");
        }
        // 요청 풀이 Hikari가 아니면 점유 상태를 알 수 없다. 판정 불가는 강등 없음으로 흡수한다.
        // 알 수 없는 상태에서 리더십을 내려놓는 쪽이 훨씬 위험하다.
        this.requestPool = requestDataSource instanceof HikariDataSource hikari ? hikari : null;
        if (this.requestPool == null) {
            log.warn("요청 DataSource가 HikariDataSource가 아니라 좀비 리더 판정을 비활성화한다: {}",
                    requestDataSource == null ? "null" : requestDataSource.getClass().getName());
        }
        this.progress = progress;
        this.standdownAfterNanos = standdownAfter.toNanos();
        this.nanoClock = nanoClock;
        this.standdowns = Counter.builder("exchange_leader_standdown")
                .description("서비스 불능 지속으로 리스 갱신을 자발적으로 포기한 횟수")
                .register(registry);
        Gauge.builder("exchange_node_serviceable", serviceable, AtomicInteger::doubleValue)
                .description("리더 일 수행 가능(1)·서비스 불능 지속(0)")
                .register(registry);
    }

    @Override
    public synchronized boolean serviceable() {
        if (requestPool == null) {
            return true;
        }
        HikariPoolMXBean pool = requestPool.getHikariPoolMXBean();
        if (pool == null) {
            // 풀 미초기화 구간. 관측 불가라 판정하지 않는다.
            return true;
        }
        long commits = progress.commits();
        boolean exhausted = pool.getActiveConnections() >= requestPool.getMaximumPoolSize();
        if (!exhausted || commits != lastCommits) {
            lastCommits = commits;
            stalled = false;
            standdownLogged = false;
            serviceable.set(1);
            return true;
        }
        long now = nanoClock.getAsLong();
        if (!stalled) {
            // 첫 정지 표본. 지속 시간 측정 시작점만 잡고 이번 표본은 정상으로 돌려준다.
            stalled = true;
            stalledSinceNanos = now;
            return true;
        }
        long stalledNanos = now - stalledSinceNanos;
        if (stalledNanos < standdownAfterNanos) {
            return true;
        }
        serviceable.set(0);
        if (!standdownLogged) {
            standdownLogged = true;
            standdowns.increment();
            log.error("서비스 불능 지속 판정: 요청 풀 점유 {}/{}, 대기 {}, 리더 쓰기 커밋 진행 0, 지속 {}ms",
                    pool.getActiveConnections(), requestPool.getMaximumPoolSize(),
                    pool.getThreadsAwaitingConnection(), stalledNanos / 1_000_000);
        }
        return false;
    }
}
