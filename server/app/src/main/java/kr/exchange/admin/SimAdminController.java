package kr.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.sim.BotFleet;
import kr.exchange.sim.BotFleetGovernor;
import kr.exchange.sim.BotType;
import kr.exchange.sim.OpsGovernor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 봇 플릿 제어(관리자 설계 §2-1, 정본 §6-A 소비). 전역 OPS·방향·대수·자동균형·효과 강도를
 * 다룬다. 이들은 파괴 액션이 아니므로 approval_token 불요, 감사만 적재한다(§3-6 완결 열거 불변).
 * 봇은 별도 프로파일(bots)에서 기동하므로 governor 부재 배포에서는 제어 의도만 감사에 남긴다.
 * 실행 중 개별 봇 파라미터 변경(§3-6 완결 열거)만 approval_token 필수다.
 */
@RestController
@RequestMapping("/admin/v1/sim")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class SimAdminController {

    private static final String FLASH_KEY = "ui.trade_flash_intensity";
    private static final Set<String> FLASH_VALUES =
            Set.of("off", "subtle", "normal", "strong", "auto");

    public record RateBody(String target_ops) {
    }

    public record ScaleBody(String market, Map<String, Integer> targets) {
    }

    public record BiasBody(String market, String target_net_bias, Boolean auto_balance,
                           String strength) {
    }

    public record EffectsBody(String trade_flash) {
    }

    public record ParamsBody(String approval_token, Map<String, Object> params) {
    }

    public record BotBody(String type, Integer count, String market,
                          Map<String, Object> params, Map<String, Object> seed) {
    }

    private final ObjectProvider<OpsGovernor> opsGovernor;
    private final ObjectProvider<BotFleetGovernor> fleetGovernor;
    private final ObjectProvider<BotFleet> botFleet;
    private final ApprovalService approvals;
    private final AdminAuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public SimAdminController(ObjectProvider<OpsGovernor> opsGovernor,
                             ObjectProvider<BotFleetGovernor> fleetGovernor,
                             ObjectProvider<BotFleet> botFleet,
                             ApprovalService approvals, AdminAuditService audit,
                             JdbcClient jdbc, ObjectMapper objectMapper) {
        this.opsGovernor = opsGovernor;
        this.fleetGovernor = fleetGovernor;
        this.botFleet = botFleet;
        this.approvals = approvals;
        this.audit = audit;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        OpsGovernor governor = opsGovernor.getIfAvailable();
        BotFleetGovernor fleet = fleetGovernor.getIfAvailable();
        BotFleet lifecycle = botFleet.getIfAvailable();
        String flash = readTradeFlash();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", "1.2");
        body.put("fleet_present", governor != null || fleet != null);
        body.put("running", lifecycle != null && lifecycle.isRunning());
        body.put("paused", lifecycle != null && lifecycle.isPaused());
        body.put("target_ops", governor == null ? "0" : String.valueOf(governor.targetOps()));
        body.put("trade_flash", flash);
        body.put("markets", fleet == null ? List.of() : marketRows(fleet, flash));
        return body;
    }

    private List<Map<String, Object>> marketRows(BotFleetGovernor fleet, String flash) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BotFleetGovernor.MarketSnapshot s : fleet.snapshot()) {
            Map<String, Object> scale = new LinkedHashMap<>();
            for (Map.Entry<BotType, int[]> e : s.scale().entrySet()) {
                scale.put(e.getKey().name(), Map.of(
                        "target", e.getValue()[0], "active", e.getValue()[1]));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("market", s.market());
            row.put("target_net_bias", bias(s.targetBias()));
            row.put("measured_net_bias", bias(s.measuredBias()));
            row.put("effective_bias", bias(s.effectiveBias()));
            row.put("auto_balance", Map.of(
                    "enabled", s.autoBalance(), "strength", bias(s.strength())));
            row.put("scale", scale);
            row.put("fill_ratio", ratio(s.fillRatio()));
            row.put("trade_flash", flash);
            rows.add(row);
        }
        return rows;
    }

    @PutMapping("/rate")
    public Map<String, Object> setRate(@RequestBody RateBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        double target = parsePositive(body == null ? null : body.target_ops());
        OpsGovernor governor = opsGovernor.getIfAvailable();
        if (governor != null) {
            governor.setTargetOps(target);
        }
        audit.record(principal.operatorId(), "sim.rate", null,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("target_ops", String.valueOf(target), "applied", governor != null));
        return Map.of("schema_version", "1.0", "target_ops", String.valueOf(target),
                "applied", governor != null);
    }

    @PutMapping("/scale")
    public Map<String, Object> setScale(@RequestBody(required = false) ScaleBody body,
                                        HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        if (body == null || body.market() == null || body.market().isBlank()) {
            throw new ApiException(400, "invalid_parameter", "market이 필요합니다.");
        }
        EnumMap<BotType, Integer> targets = parseTargets(body.targets());
        BotFleetGovernor fleet = fleetGovernor.getIfAvailable();
        BotFleetGovernor.ScaleResult result = fleet == null ? null
                : fleet.setScale(body.market(), targets);
        Map<String, Object> applied = appliedMap(targets, result);
        audit.record(principal.operatorId(), "sim.scale", body.market(),
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("applied", applied, "clamped", result != null && result.clamped(),
                        "fleet_present", fleet != null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.1");
        out.put("market", body.market());
        out.put("applied", applied);
        out.put("clamped", result != null && result.clamped());
        out.put("fleet_present", fleet != null);
        return out;
    }

    @PutMapping("/bias")
    public Map<String, Object> setBias(@RequestBody(required = false) BiasBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        if (body == null || body.market() == null || body.market().isBlank()) {
            throw new ApiException(400, "invalid_parameter", "market이 필요합니다.");
        }
        double targetBias = parseBias(body.target_net_bias());
        boolean auto = Boolean.TRUE.equals(body.auto_balance());
        double strength = parseStrength(body.strength());
        BotFleetGovernor fleet = fleetGovernor.getIfAvailable();
        double effective;
        if (fleet != null) {
            effective = fleet.setBias(body.market(), targetBias, auto, strength).effectiveBias();
        } else {
            effective = auto ? 0.0 : BotFleetGovernor.clampEffective(targetBias);
        }
        Map<String, Object> detail = Map.of(
                "target_net_bias", bias(targetBias), "auto_balance", auto,
                "strength", bias(strength), "effective_bias", bias(effective),
                "fleet_present", fleet != null);
        audit.record(principal.operatorId(), "sim.bias", body.market(),
                AdminRequestContext.requestId(request), null, "OK", detail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.1");
        out.put("market", body.market());
        out.put("target_net_bias", bias(targetBias));
        out.put("auto_balance", auto);
        out.put("strength", bias(strength));
        out.put("effective_bias", bias(effective));
        out.put("fleet_present", fleet != null);
        return out;
    }

    @PutMapping("/effects")
    @Transactional
    public Map<String, Object> setEffects(@RequestBody(required = false) EffectsBody body,
                                          HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        String flash = body == null ? null : body.trade_flash();
        if (flash == null || !FLASH_VALUES.contains(flash)) {
            throw new ApiException(400, "invalid_parameter",
                    "trade_flash는 off|subtle|normal|strong|auto 중 하나여야 합니다.");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(flash);
        } catch (Exception e) {
            throw new ApiException(400, "invalid_parameter", "trade_flash 직렬화 실패");
        }
        jdbc.sql("""
                        INSERT INTO system_config (config_key, value, updated_by)
                        VALUES (?, ?::jsonb, ?)
                        ON CONFLICT (config_key) DO UPDATE
                        SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by,
                            updated_at = now()
                        """)
                .params(FLASH_KEY, json, principal.operatorId())
                .update();
        audit.record(principal.operatorId(), "sim.effects", FLASH_KEY,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("trade_flash", flash));
        return Map.of("schema_version", "1.1", "trade_flash", flash);
    }

    /**
     * 봇 생성(§6-A, 승인 불요). 개별 인스턴스는 별도 bots 프로파일에서 기동하므로 이 경로는
     * 인메모리 governor의 타입별 목표 대수를 count만큼 상향한다(기존+count, clamp 적용). 개별
     * 인스턴스 id를 발급하지 않아 bot_ids는 상시 빈 배열이고, fleet_total은 전 마켓 목표 합계다.
     * governor 부재 배포는 감사만 남기고 count를 그대로 목표·합계로 보고한다.
     */
    @PostMapping("/bots")
    public ResponseEntity<Map<String, Object>> createBot(
            @RequestBody(required = false) BotBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        if (body == null || body.type() == null) {
            throw new ApiException(400, "invalid_parameter", "type이 필요합니다.");
        }
        BotType type;
        try {
            type = BotType.valueOf(body.type());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_parameter", "알 수 없는 봇 유형: " + body.type());
        }
        if (body.count() == null || body.count() < 1) {
            throw new ApiException(400, "invalid_parameter", "count는 1 이상 정수여야 합니다.");
        }
        if (body.market() == null || body.market().isBlank()) {
            throw new ApiException(400, "invalid_parameter", "market이 필요합니다.");
        }
        int count = body.count();
        String market = body.market();
        BotFleetGovernor fleet = fleetGovernor.getIfAvailable();

        int appliedTarget;
        int created;
        int fleetTotal;
        boolean clamped;
        if (fleet != null) {
            BotFleetGovernor.CreateResult result = fleet.increaseTarget(market, type, count);
            appliedTarget = result.appliedTarget();
            created = Math.max(0, result.appliedTarget() - result.existing());
            fleetTotal = result.fleetTotal();
            clamped = result.clamped();
        } else {
            appliedTarget = count;
            created = count;
            fleetTotal = count;
            clamped = false;
        }
        audit.record(principal.operatorId(), "sim.bots.create", market,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("type", type.name(), "count", count, "applied_target", appliedTarget,
                        "fleet_total", fleetTotal, "clamped", clamped, "fleet_present", fleet != null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.0");
        out.put("created", created);
        out.put("bot_ids", List.of());
        out.put("fleet_total", fleetTotal);
        out.put("fleet_present", fleet != null);
        out.put("applied_target", appliedTarget);
        out.put("clamped", clamped);
        return ResponseEntity.status(201).body(out);
    }

    /**
     * 전체 제어(§6-A v2.22). start는 미기동 기동·일시정지 해제, stop은 정지, pause는 발행
     * 게이트 토글(스레드 유지)이다. 플릿 부재 배포(bots 프로파일 꺼짐)는 감사만 남기고
     * applied:false로 미반영을 정직하게 보고한다(fleet_present 패턴).
     */
    @PostMapping("/{command}")
    public Map<String, Object> control(@PathVariable String command, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        if (!command.equals("start") && !command.equals("stop") && !command.equals("pause")) {
            throw new ApiException(404, "not_found", "알 수 없는 제어 명령입니다: " + command);
        }
        BotFleet fleet = botFleet.getIfAvailable();
        boolean applied = false;
        if (fleet != null) {
            switch (command) {
                case "start" -> {
                    fleet.start();
                    applied = true;
                }
                case "stop" -> {
                    fleet.stop();
                    applied = true;
                }
                default -> applied = fleet.togglePause();
            }
        }
        boolean running = fleet != null && fleet.isRunning();
        boolean paused = fleet != null && fleet.isPaused();
        audit.record(principal.operatorId(), "sim." + command, null,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("applied", applied, "running", running, "paused", paused,
                        "fleet_present", fleet != null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.1");
        out.put("command", command);
        out.put("applied", applied);
        out.put("running", running);
        out.put("paused", paused);
        out.put("fleet_present", fleet != null);
        return out;
    }

    @PatchMapping("/bots/{id}/params")
    @Transactional
    public Map<String, Object> updateParams(@PathVariable String id,
                                            @RequestBody(required = false) ParamsBody body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        String token = body == null ? null : body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token,
                "sim.bots.params", id);
        audit.record(principal.operatorId(), "sim.bots.params", id,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("params", body == null || body.params() == null ? Map.of() : body.params()));
        return Map.of("schema_version", "1.0", "bot_id", id, "approval_id", approvalId);
    }

    private EnumMap<BotType, Integer> parseTargets(Map<String, Integer> raw) {
        EnumMap<BotType, Integer> targets = new EnumMap<>(BotType.class);
        if (raw == null) {
            return targets;
        }
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            BotType type;
            try {
                type = BotType.valueOf(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, "invalid_parameter", "알 수 없는 봇 유형: " + e.getKey());
            }
            Integer count = e.getValue();
            if (count == null || count < 0) {
                throw new ApiException(400, "invalid_parameter",
                        "대수는 0 이상 정수여야 합니다: " + e.getKey());
            }
            targets.put(type, count);
        }
        return targets;
    }

    private Map<String, Object> appliedMap(EnumMap<BotType, Integer> requested,
                                           BotFleetGovernor.ScaleResult result) {
        Map<String, Object> applied = new LinkedHashMap<>();
        if (result != null) {
            for (Map.Entry<BotType, Integer> e : result.applied().entrySet()) {
                applied.put(e.getKey().name(), e.getValue());
            }
        } else {
            for (Map.Entry<BotType, Integer> e : requested.entrySet()) {
                applied.put(e.getKey().name(), e.getValue());
            }
        }
        return applied;
    }

    private String readTradeFlash() {
        String json = jdbc.sql("SELECT value::text FROM system_config WHERE config_key = ?")
                .params(FLASH_KEY).query(String.class).optional().orElse(null);
        if (json == null) {
            return "normal";
        }
        try {
            return objectMapper.readTree(json).asText("normal");
        } catch (Exception e) {
            return "normal";
        }
    }

    private static String bias(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String ratio(double v) {
        double clamped = Math.max(0.0, Math.min(1.0, v));
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static double parseBias(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "invalid_parameter", "target_net_bias가 필요합니다.");
        }
        try {
            return BotFleetGovernor.parseBias(raw);
        } catch (RuntimeException e) {
            throw new ApiException(400, "invalid_parameter", "target_net_bias가 올바르지 않습니다.");
        }
    }

    private static double parseStrength(String raw) {
        try {
            return BotFleetGovernor.parseStrength(raw);
        } catch (RuntimeException e) {
            throw new ApiException(400, "invalid_parameter", "strength가 올바르지 않습니다.");
        }
    }

    private static double parsePositive(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "invalid_parameter", "target_ops가 필요합니다.");
        }
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", "target_ops 형식이 올바르지 않습니다.");
        }
        // Infinity·1e400은 POSITIVE_INFINITY로 파싱돼 v<=0을 통과, setTargetOps(Inf)로
        // 토큰버킷 상시 허용(rate limit 해제)을 유발한다. NaN도 여기서 함께 차단한다.
        if (!Double.isFinite(v)) {
            throw new ApiException(400, "invalid_parameter", "target_ops는 유한한 값이어야 합니다.");
        }
        if (v <= 0) {
            throw new ApiException(400, "invalid_parameter", "target_ops는 양수여야 합니다.");
        }
        return v;
    }
}
