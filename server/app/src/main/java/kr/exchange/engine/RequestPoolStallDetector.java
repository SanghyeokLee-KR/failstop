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
 * 좀비 리더 판정기(DB 설계 §5.4 자발적 강등). 요청용 HikariCP 풀의 고갈과 요청 경로 진행성을
 * 함께 본다.
 *
 * 판정 신호 선택 근거. '느리다'와 '할 일이 없다'와 '일을 못 한다'를 가르는 것이 전부다.
 *  - 요청 풀에서 커넥션을 직접 얻어 보는 능동 프로브는 쓰지 않는다. HikariCP는 대기자를 FIFO로
 *    처리하므로, 과부하로 대기열이 길기만 해도 프로브가 계속 실패한다. 그 신호로 강등하면
 *    992e59b 이전(요청 풀 포화 = 하트비트 기아 = 헛페일오버)으로 회귀한다.
 *  - 대신 세 축을 쓴다. ① 요청 풀 전량 점유(active >= maximumPoolSize). ② 쓰기 요청 도착
 *    (ApiAuthFilter가 DB 접촉 전에 계측). ③ 요청 경로 DB 작업 완료 0(계정 조회 성공·저널 append
 *    커밋·정산 커밋이 표본 사이에 하나도 없음). ①만으로는 조회 부하가 만든 포화와 정지를 구분하지
 *    못하고(가상 스레드라 조회만으로도 풀은 포화된다), ②가 없으면 주문이 없는 정상 리더가 정지로
 *    오판된다. ③이 없으면 지연으로 느려졌을 뿐인 노드가 걸린다.
 *  - ②의 계측 지점이 도달 가능해야 한다. 대상 장애에서는 인증 필터의 api_keys 조회부터 실패해
 *    컨트롤러·게이트웨이·저널 append에 도달하지 못하므로, 그 뒤에서 세면 정작 좀비에서 한 번도
 *    증가하지 않아 판정이 영원히 성립하지 않는다(LeaderWriteProgress 주석).
 *  - 세 축 모두 인메모리 조회다. 판정이 DB를 타면 판정 자체가 굶어 하트비트를 막는다.
 *
 * 누적 규칙. ①∧②∧③이 처음 성립하는 표본이 누적 시작점(arm)이다. 이후에는 ②가 없어도 누적을
 * 유지한다(hold). 이미 정지가 확인된 노드에서 '요청이 더 안 온다'는 건강하다는 증거가 아니다.
 * LB가 트래픽을 끊으면 수요가 사라지므로, 리셋 조건으로 두면 그 순간 판정이 무력화된다. 누적은
 * 진행(③ 성립 실패)이나 포화 해소(① 성립 실패)에서만 초기화한다. arm 자체는 ②를 요구하므로
 * 주문이 오지 않는 유휴 노드는 처음부터 누적을 시작하지 않는다.
 *
 * 임계 근거(standdownAfter). 기본 45초는 리스 TTL 기본 10초의 4.5배, 카오스 6초의 7.5배로 정상
 * 갱신 주기와 겹치지 않고, GC·체크포인트급 일시 정지보다 훨씬 길며, 실측 좀비 지속시간(30분 이상)
 * 보다는 훨씬 짧다. 총 페일오버 시간은 standdownAfter + TTL이다.
 *
 * 판정 실패는 전부 '강등 없음'으로 흡수한다. 알 수 없는 상태에서 리더십을 내려놓는 쪽이 훨씬
 * 위험하고, 여기서 예외를 흘리면 renew SQL 자체가 실행되지 않아 오히려 비자발적 페일오버가 난다.
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

    // 생성 시점 값으로 초기화한다. 센티널을 쓰면 첫 표본의 판정이 '풀이 초기화됐는지'에 따라
    // 갈려(미초기화면 조기 반환이라 스냅샷을 건너뜀) 누적 시작점이 비결정적이 된다.
    private long lastAttempts;
    private long lastCompletions;
    private boolean stalled;
    private long stalledSinceNanos;
    private boolean standdownLogged;
    private boolean probeFailureLogged;

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
        this.requestPool = requestDataSource instanceof HikariDataSource hikari ? hikari : null;
        if (this.requestPool == null) {
            log.warn("요청 DataSource가 HikariDataSource가 아니라 좀비 리더 판정을 비활성화한다: {}",
                    requestDataSource == null ? "null" : requestDataSource.getClass().getName());
        }
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
            boolean ok = evaluate();
            // 판정이 한 번이라도 정상 완료하면 다음 실패는 다시 남긴다. 한 번 실패했다고 이후
            // 전부 무음이면 판정 비활성이 관측되지 않는다(standdownLogged와 대칭).
            probeFailureLogged = false;
            return ok;
        } catch (RuntimeException e) {
            // 판정 자체의 실패로 리더십을 잃으면 안 된다. 호출자에게 예외를 흘리면 renew SQL이
            // 실행되지 않아 리스가 TTL 만료로 소멸한다(비자발적 페일오버).
            if (!probeFailureLogged) {
                probeFailureLogged = true;
                log.warn("좀비 리더 판정 실패, 이번 표본은 정상으로 흡수한다", e);
            }
            resetStreak();
            return true;
        }
    }

    private boolean evaluate() {
        if (requestPool == null) {
            return true;
        }
        HikariPoolMXBean pool = requestPool.getHikariPoolMXBean();
        if (pool == null) {
            // 풀 미초기화 구간. 관측 불가라 판정하지 않는다.
            return true;
        }
        long attempts = progress.attempts();
        long completions = progress.completions();
        boolean exhausted = pool.getActiveConnections() >= requestPool.getMaximumPoolSize();
        boolean attempted = attempts != lastAttempts;
        boolean completed = completions != lastCompletions;
        lastAttempts = attempts;
        lastCompletions = completions;
        if (!exhausted || completed) {
            resetStreak();
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
            log.error("서비스 불능 지속 판정: 요청 풀 점유 {}/{}, 대기 {}, 요청 경로 완료 0, 지속 {}ms",
                    pool.getActiveConnections(), requestPool.getMaximumPoolSize(),
                    pool.getThreadsAwaitingConnection(), stalledNanos / 1_000_000);
        }
        return false;
    }

    private void resetStreak() {
        stalled = false;
        standdownLogged = false;
        serviceable.set(1);
    }
}
