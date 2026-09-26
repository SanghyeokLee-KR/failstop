package kr.exchange.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.lease.PartitionLeaseDb;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 앱 역할 로그인으로 불변식 검증기의 상시 run 과 권위 run 을 끝까지 돈다(ADR 0034 S3 관문 X1 에서 드러난 결함). 배포의 런타임
 * 로그인은 app_role 에 속하고(deploy/provision-runtime-roles.sql) 검증기도 그 로그인의 풀로 돈다. C15·C27 은 DEFAULT 파티션 잎을
 * 직접 세는데 잎은 부모의 권한을 물려받지 않는다. 그 권한이 없으면 상시 run 은 C15 에서 멈춰 어떤 체크도 적재하지 못하고, 권위
 * run 은 한 트랜잭션이라 통째로 실패한다(X1 에서 HTTP 500). 다른 검증기 시험과 로컬 카오스는 소유자 로그인이라 이 누락을 가렸다.
 * 잎에 준 권한은 읽기뿐이다. 잎에 직접 쓰는 길은 그대로 닫혀 있다(V49 의 전제).
 */
class AppRoleInvariantRunTest {

    private static final List<String> DEFAULT_LEAVES = List.of("ledger_entries_default", "event_journal_default",
            "trades_default", "outbox_default", "command_journal_default");

    @Test
    void verifierEvaluatesEveryCheckAsAppLoginAndDefaultLeavesStayReadOnly() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("app_role_verifier").withRuntimeRoles();
        long scheduledChecks = Arrays.stream(InvariantCheck.values())
                .filter(c -> c.scope() == InvariantCheck.Scope.SCHEDULED)
                .count();
        try (HikariDataSource appPool = db.appPool()) {
            JdbcClient app = JdbcClient.create(appPool);
            InvariantVerifier verifier = new InvariantVerifier(app,
                    new TransactionTemplate(new JdbcTransactionManager(appPool)), new ObjectMapper(),
                    new SimpleMeterRegistry());

            List<InvariantResult> scheduled = verifier.runScheduled();
            assertEquals(scheduledChecks, scheduled.size(), "앱 로그인 상시 run 이 SCHEDULED 체크를 모두 평가하지 못했다");

            AuthoritativeRun run = verifier.runAuthoritative("app-role", "post");
            assertEquals(InvariantCheck.values().length, run.results().size(), "앱 로그인 권위 run 이 체크를 모두 평가하지 못했다");
            assertTrue(run.pass(), "빈 DB 의 권위 run 에 위반이 있다: " + run.results());
            assertEquals("off", verifier.lastAuthoritativeJit());
        }
        assertEquals(scheduledChecks + InvariantCheck.values().length,
                db.ownerLong("SELECT count(*) FROM invariant_checks"), "앱 로그인 검증기가 결과를 모두 적재하지 못했다");

        try (Connection c = db.app(); Statement st = c.createStatement()) {
            for (String leaf : DEFAULT_LEAVES) {
                st.execute("SELECT count(*) FROM " + leaf);
            }
            // 쓰기는 부모를 거치고 명령 저널은 append_command_journal_row 로만 쓴다(V49). 잎 직접 INSERT 는 거부된다.
            SQLException denied = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO command_journal_default (seq, market_id, command_type, user_id, client_order_id,
                        assigned_order_id, engine_ts, tie_break, payload, fencing_token, checksum)
                    VALUES (-1, 1, 'NEW', 1, 'leaf', 1, 0, 0, '{}'::jsonb, 0, 0)
                    """));
            assertEquals("42501", denied.getSQLState(), denied.getMessage());
        }
    }
}
