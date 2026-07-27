package kr.exchange.engine;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 좀비 리더 판정기의 실패 안전성 단위 검증(DB 설계 §5.4). Testcontainers 불요.
 *
 * 판정 실패는 전부 '강등 없음'으로 흡수돼야 한다. 예외를 호출자로 흘리면 LeaderLeaseService.renew가
 * SQL 실행 전에 중단되고, 리스 틱은 예외를 로그만 남기므로 리스가 TTL 만료로 소멸한다. 즉
 * '판정 못 함'이 '강등 안 함'이 아니라 '비자발적 페일오버'가 된다.
 */
class RequestPoolStallDetectorTest {

    @Test
    void poolReadFailureIsAbsorbedAsServiceable() {
        HikariDataSource pool = mock(HikariDataSource.class);
        HikariPoolMXBean mxBean = mock(HikariPoolMXBean.class);
        when(pool.getHikariPoolMXBean()).thenReturn(mxBean);
        when(pool.getMaximumPoolSize()).thenReturn(2);
        when(mxBean.getActiveConnections())
                .thenThrow(new IllegalStateException("풀이 닫혔다"));

        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        RequestPoolStallDetector detector = new RequestPoolStallDetector(pool, progress,
                Duration.ofSeconds(45), new SimpleMeterRegistry(), clock::get);

        for (int i = 0; i < 5; i++) {
            progress.recordAttempt();
            clock.addAndGet(Duration.ofSeconds(60).toNanos());
            assertTrue(detector.serviceable(), "판정 실패가 강등으로 이어졌다");
        }
    }

    @Test
    void poolReadFailureResetsAccumulatedStall() {
        HikariDataSource pool = mock(HikariDataSource.class);
        HikariPoolMXBean mxBean = mock(HikariPoolMXBean.class);
        when(pool.getHikariPoolMXBean()).thenReturn(mxBean);
        when(pool.getMaximumPoolSize()).thenReturn(2);
        // 정지 2표본으로 누적을 만든 뒤 판정이 실패하고, 이후 다시 정지가 관측되는 흐름.
        when(mxBean.getActiveConnections())
                .thenReturn(2)
                .thenReturn(2)
                .thenThrow(new IllegalStateException("풀이 닫혔다"))
                .thenReturn(2)
                .thenReturn(2);

        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        RequestPoolStallDetector detector = new RequestPoolStallDetector(pool, progress,
                Duration.ofSeconds(45), new SimpleMeterRegistry(), clock::get);

        // 임계 미만으로 누적을 쌓는다(누적 시작점 0s, 두 번째 표본 40s).
        progress.recordAttempt();
        assertTrue(detector.serviceable());
        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "임계 미만 누적에서 강등하면 안 된다");

        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "판정 실패가 강등으로 이어졌다");

        // 누적이 초기화되지 않았다면 시작점이 0s로 남아 다음 표본(120s)에서 곧바로 강등된다.
        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "실패 이후 누적이 초기화되지 않았다");
        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "재누적이 임계 미만인데 강등했다");
    }

    @Test
    void nonHikariDataSourceDisablesDetection() {
        DataSource plain = mock(DataSource.class);
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        RequestPoolStallDetector detector = new RequestPoolStallDetector(plain, progress,
                Duration.ofSeconds(45), new SimpleMeterRegistry(), clock::get);

        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(600).toNanos());
        assertTrue(detector.serviceable(), "관측 불가 상태에서 강등하면 안 된다");
    }

    @Test
    void sustainedStallWithWriteAttemptsStandsDown() {
        HikariDataSource pool = mock(HikariDataSource.class);
        HikariPoolMXBean mxBean = mock(HikariPoolMXBean.class);
        when(pool.getHikariPoolMXBean()).thenReturn(mxBean);
        when(pool.getMaximumPoolSize()).thenReturn(2);
        when(mxBean.getActiveConnections()).thenReturn(2);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        RequestPoolStallDetector detector = new RequestPoolStallDetector(pool, progress,
                Duration.ofSeconds(45), registry, clock::get);

        progress.recordAttempt();
        assertTrue(detector.serviceable());
        progress.recordAttempt();
        clock.addAndGet(Duration.ofSeconds(46).toNanos());

        assertEquals(false, detector.serviceable(), "임계 초과 정지에서 강등 판정이 나야 한다");
        assertEquals(1.0, registry.get("exchange.leader.standdown").counter().count());
        assertEquals(0.0, registry.get("exchange.node.serviceable").gauge().value());
    }
}
