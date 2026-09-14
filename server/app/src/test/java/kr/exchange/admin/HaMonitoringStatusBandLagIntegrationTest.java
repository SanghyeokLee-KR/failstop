package kr.exchange.admin;

import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HA 상태의 적용 지연을 마켓별로 잰다(ADR 0022 6-6). 대역 seq 에서 전역 워터마크 둘을 빼면 한 마켓이 쥔 최댓값에
 * 다른 마켓의 정체가 가리고, 레거시 seq 에서 빼면 대역 폭이 지연으로 읽힌다.
 */
@Testcontainers
class HaMonitoringStatusBandLagIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    @Test
    void applyLagIsPerMarketHeadMinusMaterializedBound() throws Exception {
        assertTrue(Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate().success, "Flyway migration failed");
        long span;
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'KRW', 'Korean Won', 'FIAT', 0),
                        (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                    """);
            for (int id = 1; id <= 3; id++) {
                st.execute("""
                        INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                             tick_size, lot_size, min_notional, max_notional, max_qty)
                        VALUES (%d, 'HA-%d', 2, 1, 1000, 0.0001, 5000, 100000000000, 10000)
                        """.formatted(id, id));
            }
            try (ResultSet rs = st.executeQuery("SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'")) {
                assertTrue(rs.next());
                span = rs.getLong(1);
            }
            // 마켓 1. 레거시 둘과 첫 대역 행이 물질화됐고 대역 두 행이 기다린다.
            command(c, 1, 1);
            event(c, 1, 1, 1);
            command(c, 3, 1);
            event(c, 3, 1, 2);
            command(c, span + 1, 1);
            event(c, span + 1, 1, 3);
            command(c, span + 2, 1);
            command(c, span + 3, 1);
            // 마켓 2. 레거시 하나만 물질화됐고 첫 대역 행부터 기다린다.
            command(c, 2, 2);
            event(c, 2, 2, 1);
            command(c, 2 * span + 1, 2);
            command(c, 2 * span + 2, 2);
            // 마켓 3. 전부 물질화됐고 전역 최댓값을 쥔다.
            command(c, 3 * span + 1, 3);
            event(c, 3 * span + 1, 3, 1);
        }
        JdbcClient jdbc = JdbcClient.create(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        HaMonitoringController controller = new HaMonitoringController(jdbc, new CommandJournalReader(jdbc),
                new EventJournalReader(jdbc));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminPrincipal.REQUEST_ATTRIBUTE,
                new AdminPrincipal("op-01", "jti-ha", Set.of(AdminRole.VIEWER)));

        Map<String, Object> status = controller.status(request);

        assertEquals(3 * span + 1, status.get("command_watermark"));
        assertEquals(status.get("command_watermark"), status.get("applied_command_watermark"),
                "전역 워터마크끼리 빼면 지연이 0 으로 읽히는 모양이 아니다");
        assertEquals(List.of(
                        Map.of("market_id", 1, "command_watermark", span + 3, "apply_lag", 2L),
                        Map.of("market_id", 2, "command_watermark", 2 * span + 2, "apply_lag", 2L),
                        Map.of("market_id", 3, "command_watermark", 3 * span + 1, "apply_lag", 0L)),
                status.get("command_watermarks"));
        assertEquals(2L, status.get("apply_lag"));
        assertEquals(4L, status.get("apply_lag_total"));
    }

    private static void command(Connection c, long seq, int marketId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                             assigned_order_id, engine_ts, tie_break, payload,
                                             fencing_token, checksum)
                VALUES (?, ?, 'CANCEL', 1, NULL, ?, 0, 0, '{}', 1, 0)
                """)) {
            ps.setLong(1, seq);
            ps.setInt(2, marketId);
            ps.setLong(3, seq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    /** event_seq 는 명세 파생(market_id * band_span + market_seq)이다(DB 설계 §4.4). */
    private static void event(Connection c, long sourceCmdSeq, int marketId, long marketSeq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                                           fencing_epoch, created_at, market_id, market_seq)
                VALUES (? * (SELECT band_span FROM journal_band_spec WHERE name = 'event_seq') + ?,
                        ?, 0, 'ACCEPTED', '{}', 1, now(), ?, ?)
                """)) {
            ps.setLong(1, marketId);
            ps.setLong(2, marketSeq);
            ps.setLong(3, sourceCmdSeq);
            ps.setInt(4, marketId);
            ps.setLong(5, marketSeq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
