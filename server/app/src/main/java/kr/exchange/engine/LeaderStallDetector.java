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
 * 좀비 리더 판정기(DB 설계 §5.4 자발적 강등).
 *
 * 명제. **쓰기 수요 대비 완료율이 붕괴한 상태가 임계 시간 지속되면 리더 자격을 내려놓는다.**
 * 원인은 묻지 않는다.
 *
 * 신호가 비율인 이유(라이브 2회 실증). 이 장애는 정지가 아니라 처리율 붕괴다.
 *  - 1차 설계는 요청 풀 전량 점유를 필수 조건으로 뒀다. 실제 좀비는 커넥션 대기가 아니라 CPU
 *    폭주(463%)로 죽어 있어 커넥션이 계속 반납됐고, 점유 조건이 거짓인 표본이 섞여 발동하지 않았다.
 *  - 2차 설계는 '완료 0'을 조건으로 뒀다. 실측에서 노드는 완전히 죽지 않고 5분에 체결 1건을
 *    내는 상태였다(정상은 초당 20건). 완료 1건이 누적을 초기화하는 규칙 때문에 역시 발동하지
 *    않았고, 짧게 응답한 순간 게이지는 정상(1.0)을 가리켰다.
 *  - 두 실패의 공통 원인은 이진 조건이다. 정상 구간의 완료/수요는 1에 가깝고 좀비 구간은 0.001
 *    수준이라 세 자릿수가 벌어진다. 그 간극을 비율로 재면 임계 선택이 예민하지 않다.
 *
 * 두 축(둘 다 인메모리 카운터라 판정이 굶거나 실패할 여지가 없다).
 *  - 수요(attempts): 주문 쓰기 요청 도착. ApiAuthFilter가 DB 접촉 이전에 센다. 계측이 인증 뒤로
 *    밀리면 좀비 상태에서 한 번도 증가하지 않아 판정이 성립하지 않는다(라이브 실증).
 *  - 완료(completions): 요청 경로가 끝낸 일. api_keys 조회 왕복, 인증 단계 확정 거절, 계정 조회
 *    성공, 저널 append 커밋, 정산 커밋. 건강한 노드는 수요 1건마다 완료가 최소 1건 잡힌다.
 *
 * 누적 창. 마지막 건강 판정 이후의 수요·완료를 **누적**해 비율을 낸다. 표본별 비율이 아니라
 * 누적 비율인 것이 핵심이다. 좀비가 간헐적으로 요청 하나를 끝내도 분모가 이미 크므로 비율이
 * 회복되지 않는다(2차 설계가 무너진 지점). 창 초기화는 누적 비율이 임계를 회복할 때만 한다.
 *
 * 수요 하한. 누적 수요가 MIN_WINDOW_DEMAND 미만이면 비율의 통계적 의미가 없어 판정을 보류한다.
 * arm도 초기화도 하지 않는다. 주문이 오지 않는 유휴 노드는 이 게이트에서 영원히 멈춘다. 하한은
 * 임계 비율의 역수(1/0.05 = 20)로 잡아, 완료 1건이 곧바로 임계를 넘길 수 있는 최소 분모로 둔다.
 *
 * hold. arm 이후에는 수요가 끊겨도 누적을 유지한다. 라이브에서 nginx가 실패한 리더를 업스트림에서
 * 빼자(max_fails=1·fail_timeout=2s) 수요가 초당 40건에서 0.5건 수준으로 무너졌다. 수요 없음을
 * 건강 증거로 두면 그 순간 판정이 무력화된다. arm은 붕괴 직후 수요가 아직 살아 있는 창에서
 * 걸리고(proxy_read_timeout 10s 동안은 전량이 이 노드로 온다) 나머지는 hold가 이어받는다.
 *
 * 임계 근거(standdownAfter). 기본 45초는 리스 TTL 기본 10초의 4.5배, 카오스 6초의 7.5배로 정상
 * 갱신 주기와 겹치지 않고, GC·체크포인트급 일시 정지보다 훨씬 길며, 실측 좀비 지속시간(30분 이상)
 * 보다는 훨씬 짧다. 총 페일오버 시간은 standdownAfter + TTL이다.
 *
 * 강등은 이 판정기가 아니라 LeaderLeaseService가 수행한다. 여기서는 상태를 만들지 않는다.
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
    private final Counter standdowns;
    // 진단 전용. 판정에는 쓰지 않는다(원인 불문 원칙).
    private final HikariDataSource requestPool;
    // 1 정상, 0 강등 판정. 하네스가 좀비 판정 시점을 외부에서 관측하는 단일 지표다.
    private final AtomicInteger serviceable = new AtomicInteger(1);
    // 마지막으로 평가한 누적 완료율. 라이브에서 붕괴 진행을 눈으로 좇는 축이다.
    private volatile double lastRatio = 1.0;

    private long lastAttempts;
    private long lastCompletions;
    private long windowDemand;
    private long windowCompletions;
    private boolean stalled;
    private long stalledSinceNanos;
    private boolean standdownLogged;

    public LeaderStallDetector(DataSource requestDataSource, LeaderWriteProgress progress,
                               Duration standdownAfter, MeterRegistry registry) {
        this(requestDataSource, progress, standdownAfter, registry, System::nanoTime);
    }

    LeaderStallDetector(DataSource requestDataSource, LeaderWriteProgress progress,
                        Duration standdownAfter, MeterRegistry registry, LongSupplier nanoClock) {
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
        this.standdownAfterNanos = standdownAfter.toNanos();
        this.nanoClock = nanoClock;
        this.standdowns = Counter.builder("exchange.leader.standdown")
                .description("서비스 불능 지속으로 리스 갱신을 자발적으로 포기한 횟수")
                .register(registry);
        Gauge.builder("exchange.node.serviceable", serviceable, AtomicInteger::doubleValue)
                .description("리더 일 수행 가능(1)·서비스 불능 지속(0)")
                .register(registry);
        Gauge.builder("exchange.node.write_completion_ratio", this, d -> d.lastRatio)
                .description("누적 쓰기 완료율(완료/수요). 붕괴 판정 축")
                .register(registry);
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
        long newDemand = attempts - lastAttempts;
        long newCompletions = completions - lastCompletions;
        windowDemand += newDemand;
        windowCompletions += newCompletions;
        lastAttempts = attempts;
        lastCompletions = completions;

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
            log.warn("리더 처리율 붕괴 관측 시작: 완료율 {}({}/{}), {}초 더 지속되면 강등한다",
                    String.format("%.4f", ratio), windowCompletions, windowDemand,
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
            standdowns.increment();
            log.error("서비스 불능 지속 판정: 완료율 {}({}/{}), 지속 {}ms, {}",
                    String.format("%.4f", lastRatio), windowCompletions, windowDemand,
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
