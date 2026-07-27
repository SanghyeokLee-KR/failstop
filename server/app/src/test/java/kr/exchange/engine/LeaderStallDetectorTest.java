package kr.exchange.engine;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 좀비 리더 판정 규칙 단위 검증(DB 설계 §5.4). Testcontainers 불요.
 *
 * 판정은 원인을 묻지 않는다. 커넥션 고갈이든 CPU 폭주든 스레드 기아든, 밖에서 보이는 증상은
 * '쓰기 수요는 도착하는데 완료가 없다' 하나다. 요청 풀 점유는 판정 입력이 아니라 진단 정보다.
 * 이 클래스는 그 규칙(원인 불문 발동, 유휴 무발동, 완료 시 초기화, 수요 중단 시 누적 유지)을 고정한다.
 */
class LeaderStallDetectorTest {

    private static final Duration STANDDOWN_AFTER = Duration.ofSeconds(45);

    /** 풀이 여유로운데도(점유 0/32) 완료가 없으면 강등해야 한다. 라이브 좀비의 실제 모습이다. */
    @Test
    void standsDownWhenNothingCompletesEvenWithIdleConnectionPool() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderStallDetector detector =
                new LeaderStallDetector(healthyPool(), progress, STANDDOWN_AFTER, registry,
                        clock::get);

        progress.recordAttempt();
        assertTrue(detector.serviceable(), "정지 첫 표본은 누적 시작점일 뿐이다");
        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(46).toNanos());

        assertFalse(detector.serviceable(),
                "풀이 고갈되지 않았다는 이유로 강등이 막히면 CPU·스레드 기아형 좀비를 놓친다");
        assertEquals(1.0, registry.get("exchange.leader.standdown").counter().count());
        assertEquals(0.0, registry.get("exchange.node.serviceable").gauge().value());
        assertEquals(0, progress.completions(), "완료가 없어야 성립하는 시나리오다");
    }

    /** 수요가 끊겨도 누적은 유지한다. LB가 실패한 리더를 업스트림에서 빼면 실제로 이렇게 된다. */
    @Test
    void stallAccumulationHoldsWhenDemandStops() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        progress.recordAttempt();
        assertTrue(detector.serviceable());
        clock.addAndGet(Duration.ofSeconds(46).toNanos());

        assertFalse(detector.serviceable(), "수요가 끊겼다고 누적이 초기화되면 안 된다");
    }

    /** 주문이 오지 않는 노드는 누적을 시작조차 하지 않는다. */
    @Test
    void idleWriteDemandNeverArmsAccumulation() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        for (int i = 0; i < 10; i++) {
            clock.addAndGet(Duration.ofSeconds(45).toNanos());
            assertTrue(detector.serviceable(), "쓰기 수요 없는 유휴 노드에서 강등했다");
        }
    }

    /** 느리지만 일은 끝내는 노드(E1 전제)는 임계를 아무리 넘겨도 유지된다. */
    @Test
    void completingNodeNeverStandsDownHoweverSlow() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        for (int i = 0; i < 10; i++) {
            progress.recordAttempt();
            progress.recordCompletion();
            clock.addAndGet(Duration.ofSeconds(45).toNanos());
            assertTrue(detector.serviceable(), "완료가 있는 노드에서 강등했다");
        }
    }

    /** 완료가 재개되면 누적이 초기화된다. 지속이 아닌 간헐 정지로는 강등되지 않는다. */
    @Test
    void completionResetsAccumulation() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        progress.recordAttempt();
        assertTrue(detector.serviceable());
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "임계 미만 정지에서 강등했다");

        progress.recordCompletion();
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "완료 표본에서 강등했다");

        // 초기화되지 않았다면 시작점이 0s로 남아 이 표본(120s)에서 곧바로 강등된다.
        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "완료 후 누적이 초기화되지 않았다");
    }

    /** 진단용 풀 조회가 실패해도 판정은 영향을 받지 않는다. 판정 입력은 인메모리 카운터뿐이다. */
    @Test
    void poolDiagnosticsFailureDoesNotAffectVerdict() {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getHikariPoolMXBean()).thenThrow(new IllegalStateException("풀이 닫혔다"));

        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(pool, progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        progress.recordAttempt();
        assertTrue(detector.serviceable());
        clock.addAndGet(Duration.ofSeconds(46).toNanos());
        assertFalse(detector.serviceable(), "진단 실패가 강등 판정을 막았다");
    }

    /** 요청 DataSource가 Hikari가 아니어도 판정은 그대로 동작한다. */
    @Test
    void nonHikariDataSourceStillAllowsVerdict() {
        DataSource plain = mock(DataSource.class);
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(plain, progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        progress.recordAttempt();
        assertTrue(detector.serviceable());
        clock.addAndGet(Duration.ofSeconds(46).toNanos());
        assertFalse(detector.serviceable(), "진단 불가가 판정을 막았다");
    }

    // 커넥션이 넉넉히 남아 있는 정상 풀. 판정이 점유율과 무관함을 드러내기 위한 배경이다.
    private static HikariDataSource healthyPool() {
        HikariDataSource pool = mock(HikariDataSource.class);
        HikariPoolMXBean mxBean = mock(HikariPoolMXBean.class);
        when(pool.getHikariPoolMXBean()).thenReturn(mxBean);
        when(pool.getMaximumPoolSize()).thenReturn(32);
        when(mxBean.getActiveConnections()).thenReturn(0);
        when(mxBean.getThreadsAwaitingConnection()).thenReturn(0);
        return pool;
    }
}
