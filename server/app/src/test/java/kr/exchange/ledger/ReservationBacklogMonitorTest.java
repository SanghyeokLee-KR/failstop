package kr.exchange.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandJournalAppender;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산 전 예약 감시 게이지(ADR 0034 S3, S3 설계 4-16, 11절 R8). 전용 실행기로 모든 노드에서 돌고 하네스 설정이 없어도 돈다.
 */
class ReservationBacklogMonitorTest {

    @Test
    void gaugesFollowUnsettledReservationsAndFailToNaN() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("backlog_gauge", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            SettlementService settlement = f.settlement(lease);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ReservationBacklogMonitor monitor = new ReservationBacklogMonitor(f.app, f.appTx, registry,
                    Duration.ofMillis(200));

            monitor.refresh();
            assertEquals(0.0, monitor.pending());
            assertEquals(0.0, monitor.oldestSeconds());

            List<EngineEvent> pending = f.journalAndApply(appender, new MatchingEngineImpl(),
                    f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.3"));
            monitor.refresh();
            assertEquals(1.0, monitor.pending(), "정산 전 예약을 세지 않았다");
            Thread.sleep(2_100);
            monitor.refresh();
            assertTrue(monitor.oldestSeconds() >= 2.0, "가장 오래된 예약의 나이가 늘지 않았다: " + monitor.oldestSeconds());
            assertEquals(1.0, registry.get("exchange.reservation.unsettled.count").gauge().value());

            settlement.appendAndSettle(pending);
            monitor.refresh();
            assertEquals(0.0, monitor.pending());
            assertEquals(0.0, monitor.oldestSeconds());

            // 하네스 설정 없이 전용 실행기로 돈다. 주기 계산이 게이지를 다시 채우는지 본다.
            f.journalAndApply(appender, new MatchingEngineImpl(),
                    f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1"));
            monitor.start();
            try {
                for (int i = 0; i < 50 && monitor.pending() != 1.0; i++) {
                    Thread.sleep(100);
                }
                assertEquals(1.0, monitor.pending(), "전용 실행기가 게이지를 계산하지 않았다");
            } finally {
                monitor.close();
            }

            // 질의 실패(저널을 읽을 권한이 없는 조회 로그인, provision-runtime-roles.sql)는 NaN 과 실패 계수다.
            try (var readerPool = new com.zaxxer.hikari.HikariDataSource()) {
                readerPool.setJdbcUrl(f.db.url());
                readerPool.setUsername(kr.exchange.engine.lease.PartitionLeaseDb.READER);
                readerPool.setPassword("s2-read");
                ReservationBacklogMonitor failing = new ReservationBacklogMonitor(JdbcClient.create(readerPool),
                        new TransactionTemplate(new JdbcTransactionManager(readerPool)), new SimpleMeterRegistry());
                failing.refresh();
                assertTrue(Double.isNaN(failing.pending()));
                assertTrue(Double.isNaN(failing.oldestSeconds()));
                assertEquals(1.0, failing.queryFailures());
            }
        }
    }
}
