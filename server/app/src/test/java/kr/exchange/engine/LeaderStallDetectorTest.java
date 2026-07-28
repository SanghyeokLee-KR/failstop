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
 * 이 장애는 정지가 아니라 처리율 붕괴다. 라이브에서 좀비 노드는 완전히 죽지 않고 5분에 체결 1건을
 * 냈고(정상은 초당 20건), 그 한 건이 누적을 초기화해 이진 조건 판정이 무력화됐다. 여기서 고정하는
 * 것은 누적 완료율 판정이다. 희박한 완료로는 회복되지 않을 것, 무주문 유휴는 판정하지 않을 것,
 * 정상 완료율과 일시 하락은 강등되지 않을 것.
 */
class LeaderStallDetectorTest {

    private static final Duration STANDDOWN_AFTER = Duration.ofSeconds(45);

    /**
     * 라이브 실패 재현. 수요는 계속 도착하는데 완료가 표본당 1건씩만 나온다(비율 약 0.02).
     * 완료 1건이 누적을 초기화하던 규칙에서는 영원히 잡히지 않던 상태다.
     */
    @Test
    void sparseCompletionsStillStandDown() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, registry, clock::get);

        boolean verdict = true;
        // 표본마다 수요 50건·완료 1건(2%). 임계 45초를 넘길 때까지 흘린다.
        for (int i = 0; i < 30 && verdict; i++) {
            for (int r = 0; r < 50; r++) {
                progress.recordAttempt();
            }
            progress.recordCompletion();
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            verdict = detector.serviceable();
        }

        assertFalse(verdict, "희박한 완료가 계속 누적을 초기화하면 라이브 좀비를 영원히 놓친다");
        assertEquals(1.0, registry.get("exchange.leader.standdown").counter().count());
        assertEquals(0.0, registry.get("exchange.node.serviceable").gauge().value());
        assertTrue(registry.get("exchange.node.write_completion_ratio").gauge().value() < 0.05,
                "완료율 게이지가 붕괴를 반영해야 한다");
        assertTrue(progress.completions() > 0, "완료가 0이 아닌 상태여야 이번 회귀를 재현한다");
    }

    /**
     * 라이브 실측 수치 고정. 좀비 구간에서 nginx 재시도가 약 0.5건/s이므로 5분이면 수요 약 150건,
     * 그 사이 저널 append 커밋은 실측 6건이다. 비율 0.04로 임계 0.05 아래다. 이 여백이 좁으므로
     * 임계나 완료 축을 건드리면 여기서 먼저 깨진다.
     */
    @Test
    void liveZombieRatioCrossesThreshold() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, registry, clock::get);

        boolean verdict = true;
        // 5분을 2초 표본으로 나눈다. 표본당 수요 1건, 50표본마다 커밋 1건(총 150:6).
        for (int i = 0; i < 150 && verdict; i++) {
            progress.recordAttempt();
            if (i % 25 == 0) {
                progress.recordCompletion();
            }
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            verdict = detector.serviceable();
        }

        assertFalse(verdict, "라이브 실측 비율(약 0.04)에서 강등하지 않았다");
        assertTrue(registry.get("exchange.node.write_completion_ratio").gauge().value() < 0.05);
    }

    /** 완료가 아예 없는 상태도 당연히 잡는다. */
    @Test
    void zeroCompletionsStandDown() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        demand(progress, 50);
        assertTrue(detector.serviceable(), "붕괴 첫 표본은 누적 시작점일 뿐이다");
        clock.addAndGet(Duration.ofSeconds(46).toNanos());

        assertFalse(detector.serviceable(), "완료 0이 임계를 넘겼는데 강등하지 않았다");
    }

    /** 커넥션 풀이 여유로워도 강등한다. 병목이 CPU·스레드일 때가 실제 장애다. */
    @Test
    void standsDownEvenWithIdleConnectionPool() {
        HikariDataSource pool = healthyPool();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(pool, progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        demand(progress, 50);
        assertTrue(detector.serviceable());
        clock.addAndGet(Duration.ofSeconds(46).toNanos());

        assertFalse(detector.serviceable(), "풀 점유가 판정 조건으로 남아 있으면 안 된다");
    }

    /** 수요가 끊겨도 누적은 유지한다. LB가 실패한 리더를 업스트림에서 빼면 실제로 이렇게 된다. */
    @Test
    void stallAccumulationHoldsWhenDemandStops() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        demand(progress, 50);
        assertTrue(detector.serviceable(), "붕괴 첫 표본은 누적 시작점일 뿐이다");
        // 이후 수요 0. 붕괴 직후 nginx가 트래픽을 넘긴 뒤의 상황이다.
        clock.addAndGet(Duration.ofSeconds(46).toNanos());

        assertFalse(detector.serviceable(), "수요가 끊겼다고 누적이 초기화되면 안 된다");
    }

    /** 주문이 오지 않는 노드는 판정 자체를 보류한다(수요 하한). */
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

    /** 수요가 하한 미만이면 완료가 0이라도 판정하지 않는다. 비율의 통계적 의미가 없다. */
    @Test
    void demandBelowFloorDoesNotArm() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        // 총 수요 10건(하한 20 미만), 완료 0. 단발 실패로 정상 노드가 강등되면 안 된다.
        for (int i = 0; i < 10; i++) {
            progress.recordAttempt();
            clock.addAndGet(Duration.ofSeconds(45).toNanos());
            assertTrue(detector.serviceable(), "수요 하한 미만에서 강등했다");
        }
    }

    /** 느리지만 완료율이 유지되는 노드(E1 전제)는 임계를 아무리 넘겨도 유지된다. */
    @Test
    void healthyCompletionRatioNeverStandsDown() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        for (int i = 0; i < 10; i++) {
            // 수요 50건 중 45건 완료(90%). 나머지는 타임아웃·거절로 유실된 상황을 모사한다.
            demand(progress, 50);
            for (int c = 0; c < 45; c++) {
                progress.recordCompletion();
            }
            clock.addAndGet(Duration.ofSeconds(45).toNanos());
            assertTrue(detector.serviceable(), "완료율이 건강한 노드에서 강등했다");
        }
    }

    /** 완료율이 임계를 회복하면 누적이 초기화된다. 일시 하락으로는 강등되지 않는다. */
    @Test
    void ratioRecoveryResetsAccumulation() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        demand(progress, 50);
        assertTrue(detector.serviceable(), "붕괴 첫 표본은 누적 시작점일 뿐이다");
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "임계 미만 붕괴에서 강등했다");

        // 누적 분모(50)를 임계 위로 끌어올릴 만큼 완료가 재개된다.
        for (int c = 0; c < 20; c++) {
            progress.recordCompletion();
        }
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "완료율 회복 표본에서 강등했다");

        // 초기화되지 않았다면 시작점이 0s로 남아 이 표본(120s)에서 곧바로 강등된다.
        demand(progress, 50);
        clock.addAndGet(Duration.ofSeconds(40).toNanos());
        assertTrue(detector.serviceable(), "회복 후 누적이 초기화되지 않았다");
    }

    /**
     * 승격 경계 회귀. 팔로워는 renew를 하지 않아 serviceable()이 한 번도 불리지 않고, 그동안
     * 도착한 쓰기 수요는 카운터에만 쌓인다(팔로워는 리더 아님으로 거절하므로 완료는 0이다).
     * 승격 시 창을 다시 세우지 않으면 첫 표본이 그 전량을 접어, 정상 처리율의 새 리더가 임계
     * 시간 뒤 확정적으로 자발적 강등한다. E2에서 살아남은 유일한 후보가 그렇게 죽으면 클러스터가
     * 리더 없이 정지한다.
     */
    @Test
    void promotionResetsWindowSoFollowerBacklogDoesNotStandDownNewLeader() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        // 팔로워 구간: 수요 5000건 도착, 리더 기원 쓰기 커밋 0건. 판정은 한 번도 돌지 않는다.
        demand(progress, 5000);
        clock.addAndGet(Duration.ofMinutes(10).toNanos());

        detector.onLeadershipGained();

        // 승격 후 정상 리더. 표본마다 수요 50 대비 완료 45(90%)로 건강하다.
        for (int i = 0; i < 10; i++) {
            demand(progress, 50);
            for (int c = 0; c < 45; c++) {
                progress.recordCompletion();
            }
            clock.addAndGet(Duration.ofSeconds(20).toNanos());
            assertTrue(detector.serviceable(),
                    "팔로워 구간 수요가 승격 후 판정에 계상돼 정상 리더가 강등했다");
        }
    }

    /** 음성 대조. 승격 경계 초기화가 없으면 같은 입력이 강등으로 간다. */
    @Test
    void withoutPromotionResetFollowerBacklogStandsDownNewLeader() {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderStallDetector detector = new LeaderStallDetector(healthyPool(), progress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);

        demand(progress, 5000);
        clock.addAndGet(Duration.ofMinutes(10).toNanos());

        boolean verdict = true;
        for (int i = 0; i < 10 && verdict; i++) {
            demand(progress, 50);
            for (int c = 0; c < 45; c++) {
                progress.recordCompletion();
            }
            clock.addAndGet(Duration.ofSeconds(20).toNanos());
            verdict = detector.serviceable();
        }

        assertFalse(verdict, "초기화 없이는 팔로워 백로그가 새 리더를 강등시킨다(회귀 대조)");
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

        demand(progress, 50);
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

        demand(progress, 50);
        assertTrue(detector.serviceable());
        clock.addAndGet(Duration.ofSeconds(46).toNanos());
        assertFalse(detector.serviceable(), "진단 불가가 판정을 막았다");
    }

    private static void demand(LeaderWriteProgress progress, int count) {
        for (int i = 0; i < count; i++) {
            progress.recordAttempt();
        }
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
