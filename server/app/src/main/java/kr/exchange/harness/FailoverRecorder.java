package kr.exchange.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 페일오버 측정 기록기(하네스 검증설계 §4-3, §5). RTO/RPO/verdict를 harness.failover_runs에
 * 적재한다. recovery_runs(재생 결정론 지문)와 원천을 분리한 3중 산출물 구조다.
 *
 * verdict PASS = matched ∧ CRITICAL 0행 ∧ rpo_records=0 ∧ rto<30s(§5-3 R7). 판정 자체는
 * 오케스트레이터(권위 run 결과 + RTO 측정)가 계산해 넘기고, 여기서는 원장이 아닌 관측
 * 산출물을 append 한다.
 */
public class FailoverRecorder {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public FailoverRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long record(FailoverRecord r) {
        return jdbc.sql("""
                INSERT INTO harness.failover_runs
                    (experiment_id, old_leader, new_leader, old_epoch, new_epoch,
                     last_heartbeat_ts, takeover_ts, first_trade_ts,
                     rto_seconds, downtime_seconds, rpo_records, verdict, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING id
                """)
                .params(r.experimentId(), r.oldLeader(), r.newLeader(), r.oldEpoch(), r.newEpoch(),
                        r.lastHeartbeatTs(), r.takeoverTs(), r.firstTradeTs(),
                        r.rtoSeconds(), r.downtimeSeconds(), r.rpoRecords(), r.verdict(),
                        toJson(r.details()))
                .query(Long.class)
                .single();
    }

    private String toJson(Map<String, Object> details) {
        if (details == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public record FailoverRecord(
            String experimentId,
            String oldLeader,
            String newLeader,
            Long oldEpoch,
            Long newEpoch,
            OffsetDateTime lastHeartbeatTs,
            OffsetDateTime takeoverTs,
            OffsetDateTime firstTradeTs,
            BigDecimal rtoSeconds,
            BigDecimal downtimeSeconds,
            Long rpoRecords,
            String verdict,
            Map<String, Object> details) {
    }
}
