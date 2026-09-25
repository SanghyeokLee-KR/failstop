package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.AuthoritativeRun;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.harness.InvariantResult;
import kr.exchange.harness.InvariantVerifier;
import kr.exchange.harness.S7Audit;
import org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7 모드 전환 시험의 공용 절차(ADR 0034 S7, DB 설계 §5.4-e, S7 설계 4-4·4-5·6-2). 정지 창의 드레인, 명령 대역 래치, 운영과 같은
 * 전환 함수, 권위 run 과 구간 경계 증분 표본을 모은다. 노드는 PartitionNode 이고 DB 는 ReservationFixture(앱 로그인, V55 적용)다.
 */
final class S7Cutover {

    static final String ME = "matching-engine";
    static final String P0 = "matching-p0";
    static final String P1 = "matching-p1";

    private S7Cutover() {
    }

    /** 명령 대역 래치가 없으면 건다. 되돌릴 수 없어 폐기용 DB 에서만 한다(ADR 0034 결정 9). */
    static void latch(ReservationFixture f) {
        if (f.count("SELECT count(*) FROM journal_cutover WHERE name = 'command_seq'") == 0) {
            f.owner.sql("SELECT latch_command_band_cutover()::text").query(String.class).single();
        }
    }

    /** 정산되지 않은 주문 생성 명령 수(전환 함수의 unsettled_new 와 같은 술어). */
    static long unsettledNew(ReservationFixture f) {
        return f.count("""
                SELECT count(*) FROM command_journal c
                 WHERE c.command_type = 'NEW'
                   AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq)
                """);
    }

    /** 드레인. 정산 전 NEW 0 표본을 잇달아 두 번 본다(DB 설계 §5.4-e 1, 시험 상한 30초). */
    static void awaitDrained(ReservationFixture f) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> unsettledNew(f) == 0 && unsettledNew(f) == 0);
    }

    /** 선행 조건 사유 목록. */
    static List<String> blockers(ReservationFixture f, int target) {
        return f.owner.sql("SELECT blocker FROM leader_protocol_blockers(?::smallint)").param(target)
                .query(String.class).list();
    }

    /** 운영과 같은 전환 함수로 오르내리고 퇴역 epoch 의 최댓값을 돌려준다. */
    static long switchTo(ReservationFixture f, int target) {
        boolean downgrade = target < f.protocol();
        List<String[]> retired = f.switchProtocol(target, downgrade);
        assertEquals(3, retired.size(), "전환이 리스 행 셋을 퇴역시키지 않았다");
        assertEquals(target, f.protocol());
        return retired.stream().mapToLong(r -> Long.parseLong(r[1])).max().orElseThrow();
    }

    static long lastHistoryId(ReservationFixture f) {
        return f.count("SELECT max(id) FROM leader_protocol_history");
    }

    /** 구간 경계 증분 표본(harness/sql/segment-old-rows.sql). 이력 행마다 옛 구간 epoch 행 수다. */
    static Map<Long, Long> oldRows(ReservationFixture f) throws SQLException {
        try (Connection c = f.ownerPool.getConnection()) {
            return S7Audit.oldRows(c);
        }
    }

    static BigDecimal feeBalance(ReservationFixture f, int asset, int shard) {
        return f.decimal("""
                SELECT b.balance FROM balances b JOIN accounts a ON a.id = b.account_id
                 WHERE a.owner_type = 'SYSTEM' AND a.owner_id = 0 AND a.purpose = 'FEE_INCOME'
                   AND a.asset_id = %d AND a.shard = %d
                """.formatted(asset, shard));
    }

    /** 앱 로그인의 권위 run(운영 검증기와 같은 한 스냅샷). */
    static AuthoritativeRun authoritative(ReservationFixture f, String window) {
        InvariantVerifier verifier = new InvariantVerifier(f.app, f.appTx, new ObjectMapper(), new SimpleMeterRegistry());
        return verifier.runAuthoritative("s7-test", window);
    }

    /** 권위 run 에서 그 체크들이 모두 0 이다. CRITICAL 전체도 깨끗해야 한다. */
    static void assertAudit(AuthoritativeRun run, InvariantCheck... checks) {
        for (InvariantCheck check : checks) {
            InvariantResult result = run.results().stream().filter(r -> r.check() == check).findFirst().orElseThrow();
            assertTrue(result.ok(), check + " 위반 " + result.violatedRows() + ": " + run.results());
        }
        assertTrue(run.criticalClean(), "권위 run 에 CRITICAL 위반이 있다: " + run.results());
    }

    /** S7 관문 권위 run 의 체크(C01·C14'·C22'·C29·C30·C31, S7 설계 6-2). */
    static final InvariantCheck[] GATE = {InvariantCheck.C01_ASSET_CONSERVATION,
            InvariantCheck.C14_LOCKED_RESERVATION, InvariantCheck.C22_FENCING_MONOTONIC,
            InvariantCheck.C29_MARKET_PARTITION_MAP, InvariantCheck.C30_FENCING_RESOURCE_MATCH,
            InvariantCheck.C31_RESERVATION_LINK};

    /** 파티션 노드가 두 파티션 가운데 어느 것을 접수 중인지. */
    static String owned(PartitionNode node) {
        StringBuilder out = new StringBuilder();
        for (String resource : node.plan.partitions()) {
            if (node.accepting(resource)) {
                out.append(out.isEmpty() ? "" : ",").append(resource);
            }
        }
        return out.toString();
    }
}
