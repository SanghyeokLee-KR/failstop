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
 * 명제는 하나다. **쓰기 수요가 도착했는데 이 노드가 아무것도 완료하지 못하는 상태가 임계 시간
 * 지속되면 리더 자격을 내려놓는다.** 원인은 묻지 않는다.
 *
 * 원인을 묻지 않는 이유가 이 설계의 핵심이다. 직전 판정기는 요청 풀 전량 점유(active >=
 * maximumPoolSize)를 판정의 필수 조건으로 뒀는데, 카오스 라이브에서 실제 좀비는 커넥션 대기가
 * 아니라 CPU 폭주(463%)로 죽어 있었다. 커넥션은 계속 반납되므로 점유 조건이 거짓인 표본이 섞였고,
 * 누적이 매번 초기화돼 강등이 한 번도 발동하지 않았다. 병목이 커넥션이든 CPU든 스레드든, 밖에서
 * 보이는 증상은 '완료가 없다' 하나다. 그 증상만 본다.
 *
 * 두 축.
 *  - 수요(attempts): 주문 쓰기 요청 도착. ApiAuthFilter가 DB 접촉 이전에 센다. 계측 지점이 인증
 *    뒤로 밀리면 좀비 상태에서 한 번도 증가하지 않아 판정이 성립하지 않는다(라이브 실증).
 *  - 완료(completions): 요청 경로가 끝낸 일. api_keys 조회 왕복, 인증 단계의 확정 거절, 계정 조회
 *    성공, 저널 append 커밋, 정산 커밋. 건강한 노드는 수요 1건마다 완료가 최소 1건 잡히도록
 *    계측점을 배치했다. 그래야 [수요 있음 ∧ 완료 0]이 정상 노드에서 성립하지 않는다.
 *
 * 요청 풀 점유는 판정에서 뺐고 로그용 진단 정보로만 읽는다. 그 값 읽기가 실패해도 판정은 영향을
 * 받지 않는다. 판정 입력이 인메모리 카운터 둘뿐이라 판정 자체가 굶거나 실패할 여지가 없다.
 *
 * 누적 규칙. [수요 있음 ∧ 완료 0]이 처음 성립하는 표본이 누적 시작점(arm)이다. 이후에는 수요가
 * 없어도 누적을 유지한다(hold). 이미 정지가 확인된 노드에서 '요청이 더 안 온다'는 건강하다는
 * 증거가 아니다. 실제로 라이브에서 nginx가 실패한 리더를 업스트림에서 빼자 수요가 사라졌고,
 * 수요 없음을 초기화 조건으로 뒀다면 그 순간 판정이 무력화됐을 것이다. 초기화는 완료 관측에서만
 * 일어난다. arm 자체는 수요를 요구하므로 주문이 오지 않는 유휴 노드는 처음부터 누적하지 않는다.
 *
 * 임계 근거(standdownAfter). 기본 45초는 리스 TTL 기본 10초의 4.5배, 카오스 6초의 7.5배로 정상
 * 갱신 주기와 겹치지 않고, GC·체크포인트급 일시 정지보다 훨씬 길며, 실측 좀비 지속시간(30분 이상)
 * 보다는 훨씬 짧다. 총 페일오버 시간은 standdownAfter + TTL이다. arm은 수요가 존재하는 첫 표본에
 * 걸리므로(라이브에서 수요 창은 60초였다) 수요가 짧게 끊겨도 hold가 나머지를 이어받는다.
 *
 * 강등은 이 판정기가 아니라 LeaderLeaseService가 수행한다. 여기서는 상태를 만들지 않는다.
 */
public final class LeaderStallDetector implements NodeServiceHealth {

    private static final Logger log = LoggerFactory.getLogger(LeaderStallDetector.class);

    private final LeaderWriteProgress progress;
    private final long standdownAfterNanos;
    private final LongSupplier nanoClock;
    private final Counter standdowns;
    // 진단 전용. 판정에는 쓰지 않는다(원인 불문 원칙).
    private final HikariDataSource requestPool;
    // 1 정상, 0 강등 판정. 하네스가 좀비 판정 시점을 외부에서 관측하는 단일 지표다.
    private final AtomicInteger serviceable = new AtomicInteger(1);

    // 생성 시점 값으로 초기화한다. 센티널을 쓰면 첫 표본이 항상 '변화 있음'으로 읽혀 누적
    // 시작점이 흐려진다.
    private long lastAttempts;
    private long lastCompletions;
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
        boolean attempted = attempts != lastAttempts;
        boolean completed = completions != lastCompletions;
        lastAttempts = attempts;
        lastCompletions = completions;

        if (completed) {
            // 무엇이든 하나 끝냈다. 느릴 수는 있어도 정지는 아니다.
            stalled = false;
            standdownLogged = false;
            serviceable.set(1);
            return true;
        }
        if (!stalled) {
            if (!attempted) {
                // 쓰기 수요가 없다. 정지의 증거가 아니므로 누적을 시작하지 않는다.
                return true;
            }
            // 첫 정지 표본. 지속 시간 측정 시작점만 잡고 이번 표본은 정상으로 돌려준다.
            stalled = true;
            stalledSinceNanos = nanoClock.getAsLong();
            log.warn("리더 정지 관측 시작: 쓰기 수요는 도착하는데 완료가 없다. {}초 더 지속되면 강등한다",
                    standdownAfterNanos / 1_000_000_000L);
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
            log.error("서비스 불능 지속 판정: 쓰기 수요 {}건 누적, 완료 0, 지속 {}ms, {}",
                    attempts, stalledNanos / 1_000_000, poolDiagnostics());
        }
        return false;
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
