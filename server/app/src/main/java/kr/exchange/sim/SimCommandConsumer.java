package kr.exchange.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;

/**
 * 관리 평면이 sim_commands에 남긴 제어 의도를 봇 JVM이 집어 적용한다(V22).
 *
 * 왜 이 프로세스인가. 거버너는 bots 프로파일 JVM의 인메모리 상태이고 관리 평면은 별도
 * 컨테이너라 그 빈이 없다. 이 경로가 없으면 콘솔 조작이 감사 로그만 남기고 봇은 그대로다.
 * 외부 소비기를 두는 chaos_commands와 달리 여기서는 봇 JVM 자신이 대상이라 그 프로세스가
 * 직접 폴링한다. 별도 프로세스는 거버너 인메모리 상태에 닿을 수 없다.
 *
 * at-most-once는 DB가 보장한다. FOR UPDATE SKIP LOCKED로 한 건씩 점유하므로 봇 노드를
 * 둘 띄워도 같은 지시를 두 번 적용하지 않는다. chaos-consumer와 같은 규약이다.
 *
 * 적용 실패를 FAILED로 남긴다. 조용히 넘기면 콘솔은 성공으로 보이고 봇은 그대로인 상태가
 * 되는데, 그것이 이 소비기가 막으려는 상태다.
 */
public class SimCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(SimCommandConsumer.class);

    private final JdbcClient jdbc;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final String nodeId;

    public SimCommandConsumer(JdbcClient jdbc, OpsGovernor governor, BotFleetGovernor fleet,
                              String nodeId) {
        this.jdbc = jdbc;
        this.governor = governor;
        this.fleet = fleet;
        this.nodeId = nodeId;
    }

    /**
     * 1초 주기. 발표 중 콘솔 조작이 화면에 반영되기까지의 지연이 이 값이다. 더 짧게 잡으면
     * 유휴 폴링이 늘고, 더 길게 잡으면 시연에서 눈에 띈다.
     */
    @Scheduled(fixedDelayString = "${exchange.sim.command-poll-ms:1000}")
    public void poll() {
        while (claimAndApply()) {
            // 큐가 밀렸으면 한 주기에 몰아 처리한다. 프리셋 전환이 연달아 들어오면
            // 마지막 것이 최종 상태여야 하므로 남겨두고 다음 주기를 기다리지 않는다.
        }
    }

    private boolean claimAndApply() {
        // 점유와 조회를 한 문장으로 한다. 조회 후 UPDATE로 나누면 그 사이에 다른 노드가
        // 같은 행을 집을 수 있다.
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.sql("""
                            UPDATE sim_commands SET state = 'APPLIED', applied_by = ?, applied_at = now()
                            WHERE id = (
                                SELECT id FROM sim_commands WHERE state = 'PENDING'
                                ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                            )
                            RETURNING id, action, payload
                            """)
                    .param(nodeId).query().listOfRows();
        } catch (RuntimeException e) {
            log.warn("sim_commands 점유 실패: {}", e.toString());
            return false;
        }
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        String action = String.valueOf(row.get("action"));
        String payload = String.valueOf(row.get("payload"));

        try {
            String result = apply(action, payload);
            jdbc.sql("UPDATE sim_commands SET result = ? WHERE id = ?")
                    .params(result, id).update();
            log.info("sim 명령 적용: #{} {}={} -> {}", id, action, payload, result);
        } catch (RuntimeException e) {
            // 이미 APPLIED로 점유했으므로 FAILED로 되돌린다. 점유를 먼저 하는 이유는
            // 적용 중 프로세스가 죽어도 같은 지시가 무한 재시도되지 않게 하기 위해서다.
            jdbc.sql("UPDATE sim_commands SET state = 'FAILED', result = ? WHERE id = ?")
                    .params(truncate(e.toString()), id).update();
            log.warn("sim 명령 실패: #{} {}={} -> {}", id, action, payload, e.toString());
        }
        return true;
    }

    private String apply(String action, String payload) {
        switch (action) {
            case "scenario" -> {
                SimScenario scenario = SimScenario.parse(payload);
                double base = governor.baseTargetOps();
                double target = scenario.scaledOps(base);
                governor.setTargetOps(target);
                List<BotFleetGovernor.ScenarioMarketResult> results = fleet.applyScenario(scenario);
                return "scenario=" + scenario.wireName() + " base=" + base + " target=" + target
                        + " markets=" + results.size();
            }
            case "target_ops" -> {
                double ops = Double.parseDouble(payload);
                if (ops < 0) {
                    throw new IllegalArgumentException("target_ops 음수 불가: " + payload);
                }
                governor.setTargetOps(ops);
                return "target_ops=" + ops;
            }
            default -> throw new IllegalArgumentException("알 수 없는 action: " + action);
        }
    }

    private static String truncate(String s) {
        String v = s == null ? "" : s;
        return v.length() <= 500 ? v : v.substring(0, 500);
    }
}
