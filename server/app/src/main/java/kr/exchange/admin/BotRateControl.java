package kr.exchange.admin;

import kr.exchange.sim.OpsGovernor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 봇 목표 발행률의 단일 쓰기 경로. 사람의 PUT /admin/v1/sim/rate 와 관제센터의 봇 부하 줄임 조치가 같은 코드와 같은
 * JVM 잠금을 쓰고, 목표 OPS 를 함께 바꾸는 시나리오 활성화도 이 잠금 안에서 적용한다(설계 7-7). 같은 JVM 에 거버너가
 * 있으면 그 자리에서 바꾸고, 없으면 sim_commands 큐에 의도를 남긴다.
 */
public class BotRateControl {

    // 발행 주기 1초 기준 10배. 이보다 늙은 발행 상태는 현재 목표로 읽지 않는다(SimAdminController 와 같다).
    static final int STATE_STALE_SECONDS = 10;

    /** 현재 목표 발행률과 그 원천. 모르면 targetOps 가 null 이고 source 가 unknown 이다. */
    public record Current(Double targetOps, String source, Instant sampledAt) {
    }

    public record Applied(double target, String applyPath, long queuedCommandId) {
    }

    private final ObjectProvider<OpsGovernor> governor;
    private final JdbcClient jdbc;
    private final AdminAuditService audit;
    private final Clock clock;
    private final Object lock = new Object();

    public BotRateControl(ObjectProvider<OpsGovernor> governor, JdbcClient jdbc, AdminAuditService audit, Clock clock) {
        this.governor = governor;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public Current current() {
        OpsGovernor g = governor.getIfAvailable();
        if (g != null) {
            return new Current(g.targetOps(), "local", clock.instant());
        }
        // 신선도는 DB 시계 하나로 본다. observed_at 도 now() 로 찍히므로 관리 JVM 시계를 섞지 않는다.
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT target_ops::float8 AS target_ops, observed_at,
                               now() - observed_at <= make_interval(secs => ?) AS fresh
                        FROM sim_fleet_state ORDER BY observed_at DESC LIMIT 1
                        """)
                .param(STATE_STALE_SECONDS).query().listOfRows();
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("fresh"))) {
            return new Current(null, "unknown", null);
        }
        Map<String, Object> row = rows.get(0);
        return new Current((Double) row.get("target_ops"), "published", ((Timestamp) row.get("observed_at")).toInstant());
    }

    public boolean local() {
        return governor.getIfAvailable() != null;
    }

    /**
     * 목표를 바꾸고 감사(sim.rate)를 남긴다. 큐 경로의 발행은 호출부의 트랜잭션에 합류한다. 호출부가 이미 이 잠금을
     * 잡았어도 같은 스레드라 다시 잡을 수 있다.
     */
    public Applied set(double target, String actor, String requestId, Map<String, Object> extra) {
        synchronized (lock) {
            OpsGovernor g = governor.getIfAvailable();
            long queuedId = 0;
            if (g != null) {
                g.setTargetOps(target);
            } else {
                // 거버너는 봇 JVM 인메모리라 관리 평면에 없을 수 있다. 큐 발행을 빠뜨리면 조작이 조용히 무시된다.
                queuedId = jdbc.sql("""
                                INSERT INTO sim_commands (action, payload, requested_by)
                                VALUES ('target_ops', ?, ?)
                                RETURNING id
                                """)
                        .params(String.valueOf(target), actor)
                        .query(Long.class).single();
            }
            String applyPath = g != null ? "local" : "queued";
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("target_ops", String.valueOf(target));
            detail.put("apply_path", applyPath);
            detail.put("queued_command_id", queuedId);
            detail.putAll(extra);
            audit.record(actor, "sim.rate", null, requestId, null, "OK", detail);
            return new Applied(target, applyPath, queuedId);
        }
    }

    /** 검사, 기록, 호출, 결과 기록을 한 줄로 세운다. 관리 JVM 이 배포마다 한 대라는 전제다(설계 7-7). */
    public <T> T withLock(Supplier<T> body) {
        synchronized (lock) {
            return body.get();
        }
    }
}
