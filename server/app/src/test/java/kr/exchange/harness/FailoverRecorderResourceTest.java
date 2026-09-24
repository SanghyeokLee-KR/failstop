package kr.exchange.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.lease.PartitionLeaseDb;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 페일오버 측정 이력의 리스 자원(V53, DB 설계 §3.8). 배포와 같은 앱 로그인이 새 열로 적재한다. 표 수준 권한(V4)이 새 열에도
 * 걸리므로 V53 은 GRANT 를 다시 하지 않는다. 자원을 적지 않은 옛 호출부의 행은 NULL 이고, 모르는 자원은 CHECK 가 막는다.
 */
class FailoverRecorderResourceTest {

    @Test
    void appLoginRecordsTheResource() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_failover_resource").withRuntimeRoles();
        try (HikariDataSource app = db.appPool()) {
            FailoverRecorder recorder = new FailoverRecorder(JdbcClient.create(app), new ObjectMapper());
            OffsetDateTime now = OffsetDateTime.now();

            long p1 = recorder.record(new FailoverRecorder.FailoverRecord("E7", "node-a", "node-b", 3L, 4L,
                    now.minusSeconds(12), now.minusSeconds(2), now, new BigDecimal("12.000"), new BigDecimal("10.000"),
                    0L, "PASS", Map.of("partition", "p1"), "matching-p1"));
            long legacy = recorder.record(new FailoverRecorder.FailoverRecord("E1", "node-a", "node-b", 1L, 2L,
                    now.minusSeconds(9), now.minusSeconds(1), now, new BigDecimal("9.000"), new BigDecimal("8.000"),
                    0L, "PASS", Map.of()));
            assertTrue(p1 > 0 && legacy > 0);
            assertEquals("matching-p1", db.ownerString("SELECT resource FROM harness.failover_runs WHERE id = " + p1));
            assertNull(db.ownerString("SELECT resource FROM harness.failover_runs WHERE id = " + legacy),
                    "자원 없는 적재가 NULL 이 아니다");

            DataIntegrityViolationException bad = assertThrows(DataIntegrityViolationException.class,
                    () -> recorder.record(new FailoverRecorder.FailoverRecord("E7", "a", "b", 5L, 6L, null, null, null,
                            null, null, 0L, "FAIL", Map.of(), "matching-p9")));
            assertTrue(bad.getMostSpecificCause().getMessage().contains("chk_failover_runs_resource"),
                    bad.getMostSpecificCause().getMessage());
        }
    }
}
