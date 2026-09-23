package kr.exchange.engine.lease;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모드 래치 leader_protocol 과 그 이력(S2 설계 4-6). 바뀔 때마다 이력이 한 행 남고, 값이 같은 UPDATE 는 남지 않으며, 래치와 이력은
 * 지우거나 비울 수 없다. epoch_floor 는 변경 시점의 리스 행·획득 이력 epoch 최대다.
 */
class ProtocolHistoryTest {

    @Test
    void recordsEachChangeWithFloorAndSkipsSameValueUpdate() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("protocol_history");
        assertEquals("1||0", history(db));

        db.seedLease("matching-engine", "old-node", 41, 3600);
        db.exec("INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin) "
                + "VALUES (43, 'matching-engine', 'old-node', 1, 'ACQUIRE')");
        db.setProtocol(2);
        db.setProtocol(2);
        assertEquals("1||0,2|1|43", history(db), "값이 같은 UPDATE 가 이력을 남겼거나 epoch_floor 가 틀렸다");

        db.setProtocol(3);
        assertEquals("1||0,2|1|43,3|2|43", history(db));
        assertEquals("3|" + PartitionLeaseDb.OWNER, db.ownerString(
                "SELECT protocol || '|' || changed_by FROM leader_protocol"));
        assertEquals("true", db.ownerString("""
                SELECT (max(changed_at) = (SELECT changed_at FROM leader_protocol))::text FROM leader_protocol_history
                """), "래치 행의 변경 시각과 이력 시각이 어긋난다");
    }

    @Test
    void latchAndHistoryCannotBeDeletedOrRewritten() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("protocol_guard");
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            for (String sql : List.of(
                    "DELETE FROM leader_protocol",
                    "TRUNCATE leader_protocol",
                    "UPDATE leader_protocol_history SET protocol = 2",
                    "DELETE FROM leader_protocol_history",
                    "TRUNCATE leader_protocol_history")) {
                SQLException refused = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                assertTrue(refused.getMessage().contains("append-only 위반"), sql + ": " + refused.getMessage());
            }
            SQLException second = assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO leader_protocol (protocol) VALUES (1)"));
            assertEquals("23505", second.getSQLState(), second.getMessage());
            SQLException unknown = assertThrows(SQLException.class,
                    () -> st.execute("UPDATE leader_protocol SET protocol = 4"));
            assertEquals("23514", unknown.getSQLState(), unknown.getMessage());
            assertEquals("1", queryString(c, "SELECT count(*) FROM leader_protocol"));
        }
    }

    @Test
    void appRoleReadsButCannotChangeTheLatch() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("protocol_role").withRuntimeRoles();
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            assertEquals("1", queryString(app, "SELECT protocol FROM leader_protocol"));
            for (String sql : List.of(
                    "UPDATE leader_protocol SET protocol = 3",
                    "INSERT INTO leader_protocol_history (protocol, epoch_floor, changed_at, changed_by) "
                            + "VALUES (3, 0, now(), 'app')",
                    "INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin) "
                            + "VALUES (999, 'matching-engine', 'app', 1, 'ACQUIRE')")) {
                SQLException denied = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                assertEquals("42501", denied.getSQLState(), sql + ": " + denied.getMessage());
            }
        }
        assertEquals("1||0", history(db));
    }

    private static String history(PartitionLeaseDb db) throws SQLException {
        return db.ownerString("""
                SELECT string_agg(protocol || '|' || COALESCE(prev_protocol::text, '') || '|' || epoch_floor, ','
                                  ORDER BY id)
                FROM leader_protocol_history
                """);
    }
}
