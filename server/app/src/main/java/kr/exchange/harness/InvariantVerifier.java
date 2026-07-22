package kr.exchange.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 불변식 상시 검증기(하네스 검증설계 §4). 결과를 invariant_checks에 append 하고
 * Micrometer 게이지로 노출한다. 판정은 전부 SQL 위반 행 수 0으로 기계화한다.
 *
 * 두 실행 모드:
 *   runScheduled()     SCHEDULED 스코프 경량 검증. 각 체크는 단일 SELECT라 문장 단위
 *                      스냅샷으로 내부 정합이 성립한다(READ COMMITTED 무해).
 *   runAuthoritative() 실험 판정 프로토콜의 사전·사후 권위 run. 전 체크(C16·C19 포함)를
 *                      REPEATABLE READ READ ONLY 단일 스냅샷에서 읽어 교차 정합을 보장하고,
 *                      결과를 details(run_id·window·watermark)와 함께 적재한다.
 *
 * 원장·저널을 절대 건드리지 않는다(읽기 + invariant_checks append만).
 */
public class InvariantVerifier {

    private static final Logger log = LoggerFactory.getLogger(InvariantVerifier.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnlyRepeatableRead;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Map<InvariantCheck, AtomicLong> violationGauges = new ConcurrentHashMap<>();
    private final MultiGauge ledgerImbalance;

    public InvariantVerifier(JdbcClient jdbc,
                             TransactionTemplate transactionTemplate,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.readOnlyRepeatableRead = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readOnlyRepeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readOnlyRepeatableRead.setReadOnly(true);
        this.ledgerImbalance = MultiGauge.builder("exchange_ledger_imbalance")
                .description("자산별 원장 총합. 0이 아니면 자산 보존 위반")
                .register(meterRegistry);
    }

    /** SCHEDULED 스코프 경량 검증. 상시 스케줄러가 호출한다. */
    public List<InvariantResult> runScheduled() {
        List<InvariantResult> results = new ArrayList<>();
        for (InvariantCheck check : InvariantCheck.values()) {
            if (check.scope() != InvariantCheck.Scope.SCHEDULED) {
                continue;
            }
            InvariantResult result = InvariantResult.of(check, count(check.sql()));
            results.add(result);
            record(result, null, null);
        }
        refreshLedgerImbalanceGauge();
        logViolations("scheduled", results);
        return results;
    }

    /**
     * 실험 판정 프로토콜의 권위 run. 전 체크를 단일 REPEATABLE READ 스냅샷에서 읽고,
     * 워터마크와 함께 적재한다. window는 'pre'|'post'|'stopped' 등 실험 단계 라벨이다.
     */
    public AuthoritativeRun runAuthoritative(String runId, String window) {
        // 읽기 단계: 단일 스냅샷에서 워터마크 + 전 체크 위반 수를 확정한다.
        AuthoritativeRun snapshot = readOnlyRepeatableRead.execute(status -> {
            long cmdWm = scalarOrZero("SELECT COALESCE(max(seq), 0) FROM command_journal");
            long evtWm = scalarOrZero("SELECT COALESCE(max(event_seq), 0) FROM event_journal");
            List<InvariantResult> results = new ArrayList<>();
            for (InvariantCheck check : InvariantCheck.values()) {
                results.add(InvariantResult.of(check, count(check.sql())));
            }
            return new AuthoritativeRun(runId, window, cmdWm, evtWm, results);
        });

        // 쓰기 단계: read-only 트랜잭션 밖에서 적재한다.
        for (InvariantResult result : snapshot.results()) {
            record(result, runId, window, snapshot.commandWatermark(), snapshot.eventWatermark());
        }
        refreshLedgerImbalanceGauge();
        logViolations("authoritative[" + runId + "/" + window + "]", snapshot.results());
        log.info("권위 run 완료: run_id={}, window={}, verdict={}, 위반합={}",
                runId, window, snapshot.pass() ? "PASS" : "FAIL", snapshot.totalViolations());
        return snapshot;
    }

    // ── 내부 ────────────────────────────────────────────────────────────

    private void record(InvariantResult result, String runId, String window) {
        record(result, runId, window, null, null);
    }

    private void record(InvariantResult result, String runId, String window,
                        Long cmdWm, Long evtWm) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("severity", result.check().severity().name());
        if (runId != null) {
            details.put("run_id", runId);
        }
        if (window != null) {
            details.put("window", window);
        }
        if (cmdWm != null) {
            details.put("command_watermark", cmdWm);
        }
        if (evtWm != null) {
            details.put("event_watermark", evtWm);
        }
        jdbc.sql("""
                INSERT INTO invariant_checks (check_name, ok, violated_rows, details)
                VALUES (?, ?, ?, ?::jsonb)
                """)
                .params(result.check().name(), result.ok(), result.violatedRows(), toJson(details))
                .update();
        violationGauge(result.check()).set(result.violatedRows());
    }

    private AtomicLong violationGauge(InvariantCheck check) {
        return violationGauges.computeIfAbsent(check, c -> {
            AtomicLong holder = new AtomicLong();
            meterRegistry.gauge("exchange_invariant_violations",
                    Tags.of("check_name", c.name(), "severity", c.severity().name()),
                    holder, AtomicLong::doubleValue);
            return holder;
        });
    }

    private void refreshLedgerImbalanceGauge() {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        jdbc.sql("SELECT asset_id, SUM(amount) AS net FROM ledger_entries GROUP BY asset_id")
                .query((rs, n) -> {
                    rows.add(MultiGauge.Row.of(
                            Tags.of("asset", String.valueOf(rs.getInt("asset_id"))),
                            rs.getBigDecimal("net")));
                    return null;
                })
                .list();
        ledgerImbalance.register(rows, true);
    }

    private long count(String sql) {
        Long v = jdbc.sql(sql).query(Long.class).single();
        return v == null ? 0 : v;
    }

    private long scalarOrZero(String sql) {
        Long v = jdbc.sql(sql).query(Long.class).single();
        return v == null ? 0 : v;
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private void logViolations(String phase, List<InvariantResult> results) {
        for (InvariantResult r : results) {
            if (!r.ok()) {
                log.error("불변식 위반[{}]: {} severity={} 위반행={}",
                        phase, r.check().name(), r.check().severity(), r.violatedRows());
            }
        }
    }
}
