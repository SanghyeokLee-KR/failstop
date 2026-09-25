package kr.exchange.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.WriteAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 봇 JVM이 자기 플릿 상태를 sim_fleet_state에 주기 발행한다(V28).
 *
 * 왜 이 프로세스인가. 거버너와 수명주기는 이 JVM의 인메모리 상태이고 관리 평면은 별도
 * 컨테이너라 그 빈이 없다. V22가 관리에서 봇으로 가는 방향을 이었고 이 클래스가 반대
 * 방향을 잇는다. 없으면 콘솔이 플릿을 상시 정지·0대로 그린다.
 *
 * 서식을 입히지 않고 원값을 싣는다. 관리 평면이 로컬 경로와 같은 헬퍼로 서식을 입혀야
 * 두 경로가 같은 자릿수를 낸다.
 */
public class SimStateReporter {

    private static final Logger log = LoggerFactory.getLogger(SimStateReporter.class);

    private static final String UPSERT = """
            INSERT INTO sim_fleet_state (node_id, running, paused, target_ops, markets, observed_at)
            VALUES (?, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (node_id) DO UPDATE SET
                running = EXCLUDED.running,
                paused = EXCLUDED.paused,
                target_ops = EXCLUDED.target_ops,
                markets = EXCLUDED.markets,
                observed_at = EXCLUDED.observed_at
            """;

    private final JdbcClient jdbc;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final BotFleet lifecycle;
    private final ObjectMapper objectMapper;
    private final String nodeId;
    private final WriteAvailability writeAvailability;

    public SimStateReporter(JdbcClient jdbc, OpsGovernor governor, BotFleetGovernor fleet,
                            BotFleet lifecycle, ObjectMapper objectMapper, String nodeId,
                            WriteAvailability writeAvailability) {
        this.jdbc = jdbc;
        this.governor = governor;
        this.fleet = fleet;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
        this.writeAvailability = writeAvailability;
    }

    /**
     * 명령 폴링과 같은 1초 주기다. 콘솔이 조작 결과를 되읽는 왕복이 두 주기라, 여기만
     * 늘리면 조작이 반영됐는데 화면이 이전 값을 보여주는 창이 길어진다.
     */
    @Scheduled(fixedDelayString = "${exchange.sim.state-publish-ms:1000}")
    public void publish() {
        if (!writeAvailability.isActive()) {
            // 거래 일시 중단 동안 발행이 커밋 대기에 걸리면 이 스케줄러의 명령 폴링까지 재개까지 멈춘다(DB 설계 §5.4-d 6).
            // 관리 평면은 observed_at 이 늙는 것으로 이 구간을 본다.
            return;
        }
        try {
            jdbc.sql(UPSERT)
                    .params(nodeId, lifecycle.isRunning(), lifecycle.isPaused(),
                            BigDecimal.valueOf(governor.targetOps()),
                            marketsJson(fleet, objectMapper))
                    .update();
        } catch (JsonProcessingException | RuntimeException e) {
            // 발행 실패로 스케줄러를 죽이지 않는다. 관리 평면은 observed_at이 늙는 것으로
            // 이 상태를 본다. 조용히 넘기면 화면이 옛 값을 현재로 읽는다.
            log.warn("플릿 상태 발행 실패: node={} {}", nodeId, e.toString());
        }
    }

    /**
     * 발행 페이로드. 관리 평면의 status 폴백이 이 키 이름으로 읽으므로 양쪽이 같이 움직여야
     * 한다. 한쪽만 바뀌면 Jackson MissingNode 가 0 을 내어 화면이 조용히 0.00 을 그린다.
     * public static 인 이유는 그 계약을 컨텍스트 없이 시험에 걸기 위해서다.
     */
    public static String marketsJson(BotFleetGovernor fleet, ObjectMapper objectMapper)
            throws JsonProcessingException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BotFleetGovernor.MarketSnapshot s : fleet.snapshot()) {
            Map<String, Object> scale = new LinkedHashMap<>();
            for (Map.Entry<BotType, int[]> e : s.scale().entrySet()) {
                scale.put(e.getKey().name(),
                        Map.of("target", e.getValue()[0], "active", e.getValue()[1]));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("market", s.market());
            row.put("target_bias", s.targetBias());
            row.put("measured_bias", s.measuredBias());
            row.put("effective_bias", s.effectiveBias());
            row.put("auto_balance", s.autoBalance());
            row.put("strength", s.strength());
            row.put("scale", scale);
            row.put("fill_ratio", s.fillRatio());
            rows.add(row);
        }
        return objectMapper.writeValueAsString(rows);
    }
}
