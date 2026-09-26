package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.admin.msp.OpsThresholdService;
import kr.exchange.api.common.ApiFormats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 용량 콘솔(ADR 0015 수동 스케일링). 판정은 자동이고 실행은 사람이다.
 *
 * 이 컨트롤러가 하는 일은 셋이다. 계약과 관측을 나란히 내주고, 임계를 넘으면 권고를 붙이고,
 * 관리자가 누른 버튼을 희망 용량에 반영한다. 노드를 직접 띄우거나 내리지 않는다.
 *
 * 왜 직접 하지 않는가. 카오스 콘솔과 같은 경계다. 그쪽도 "실제 주입은 격리 스택의 카오스
 * 주입기가 승인된 명령을 소비하는 경계" 이고 컨트롤러는 승인·감사 지점이다. 여기도 같다.
 * 희망 용량이 계약이고 그것을 실제로 맞추는 것은 조정기(로컬은 하네스, 클라우드는 Auto
 * Scaling group)다. 그래서 버튼을 누른 직후에는 계약 3 대에 관측 2 대인 창이 실재하고,
 * 화면이 그 창을 그대로 보여준다. 감추지 않는다.
 *
 * 확장 대상은 조회를 받는 앱 계층뿐이다. 쓰기는 매칭엔진이 단일 라이터라 노드를 늘려도
 * 천장이 오르지 않는다(2026-09-07 실측 56.8/s -> 54.3/s). DB 는 primary 가 하나여야 한다.
 *
 * 역할은 잠정이다. 용량 변경은 비용을 쓰는 인프라 조치라 SUPER 로 둔다. ADR 0015 가
 * 승인 정책을 결손으로 등재했고 그 판정이 서면 여기를 함께 고친다.
 */
@RestController
@RequestMapping("/admin/v1/capacity")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class CapacityAdminController {

    private static final String TIER = CapacityPlanService.TIER;


    public record ScaleBody(String reason) {
    }

    private final CapacityProperties props;
    private final CapacityPlanService plans;
    private final Optional<OpsThresholdService> registry;

    /**
     * registry 는 관제센터(exchange.msp.enabled)를 켠 배포에만 있다. 끈 배포는 지금처럼 설정의 권고 임계를
     * 읽고 임계값 표(V70)를 건드리지 않는다. 앱이 Flyway 를 끄는 곳에서 새 JAR 가 표보다 먼저 떠도 500 을 내지 않는다.
     */
    public CapacityAdminController(CapacityProperties props, CapacityPlanService plans,
                                   Optional<OpsThresholdService> registry) {
        this.props = props;
        this.plans = plans;
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> state(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        return snapshot();
    }

    @PostMapping("/scale-out")
    public Map<String, Object> scaleOut(@RequestBody(required = false) ScaleBody body,
                                        HttpServletRequest request) {
        return change(+1, "capacity.scale_out", body, request);
    }

    @PostMapping("/scale-in")
    public Map<String, Object> scaleIn(@RequestBody(required = false) ScaleBody body,
                                       HttpServletRequest request) {
        return change(-1, "capacity.scale_in", body, request);
    }

    /* ===== 조치 ===== */

    private Map<String, Object> change(int delta, String action, ScaleBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        CapacityPlanService.Change change = plans.change(delta, principal.operatorId(), action,
                body == null ? null : body.reason(), AdminRequestContext.requestId(request));

        Map<String, Object> out = new LinkedHashMap<>(snapshot());
        out.put("changed_from", change.from());
        out.put("changed_to", change.to());
        return out;
    }

    /* ===== 상태 ===== */

    private Map<String, Object> snapshot() {
        CapacityPlanService.Plan plan = plans.plan();
        List<CapacityProperties.Node> configured = props.getNodes();
        CapacityPlanService.Observation observation = plans.observe();
        Map<String, NodeProbe.Observed> observed = observation.byNode();

        List<Map<String, Object>> nodes = new ArrayList<>();
        int up = observation.up();
        Double avgCpu = observation.readAvgCpu();
        boolean leaderUp = observation.leaderUp();
        for (int i = 0; i < configured.size(); i++) {
            CapacityProperties.Node c = configured.get(i);
            NodeProbe.Observed o = observed.getOrDefault(c.getId(), NodeProbe.Observed.unreachable());
            // 계약상 떠 있어야 하는 노드는 설정 순서로 앞에서부터 희망 대수만큼이다.
            boolean expected = i < plan.desired();
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", c.getId());
            n.put("az", c.getAz());
            n.put("instance_type", c.getInstanceType());
            n.put("engine", c.isEngine());
            n.put("scalable", c.isScalable());
            n.put("expected", expected);
            n.put("reachable", o.reachable());
            n.put("leader", o.leader());
            n.put("serves_reads", o.servesReads());
            n.put("cpu_ratio", o.cpuRatio());
            n.put("cpu_count", o.cpuCount());
            n.put("heap_used_bytes", o.heapUsedBytes());
            n.put("heap_max_bytes", o.heapMaxBytes());
            n.put("http_requests", o.httpRequests());
            n.put("ws_clients", o.wsClients());
            nodes.add(n);
        }
        Limits limits = limits();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.0");
        out.put("tier", TIER);
        // Map.of 를 쓰지 않는다. null 에 NPE 가 나고 순회 순서가 기동마다 바뀐다.
        // 시각은 ApiFormats.time 으로만 낸다. toString 은 밀리초 자릿수가 값마다 달라
        // 응답 형식 회귀 시험의 패턴을 깬다.
        Map<String, Object> planOut = new LinkedHashMap<>();
        planOut.put("min", plan.min());
        planOut.put("desired", plan.desired());
        planOut.put("max", plan.max());
        planOut.put("updated_by", plan.updatedBy() == null ? "" : plan.updatedBy());
        planOut.put("updated_at", plan.updatedAt() == null ? "" : ApiFormats.time(plan.updatedAt()));
        out.put("plan", planOut);
        out.put("observed_total", up);
        out.put("reconciled", up == plan.desired());
        out.put("read_avg_cpu_ratio", avgCpu);
        out.put("nodes", nodes);
        // 임계를 응답에 싣는다. 화면이 0.60 을 복제하고 있어 서버가 값을 조이면 두 곳이
        // 갈렸다. 게이지 눈금도 판정과 같은 값을 써야 한다
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("scale_out_cpu_ratio", limits.scaleOut());
        thresholds.put("scale_in_cpu_ratio", limits.scaleIn());
        out.put("thresholds", thresholds);
        out.put("advice", advice(plan, up, avgCpu, leaderUp, limits, configured));
        return out;
    }

    private record Limits(double scaleOut, double scaleIn) {
    }

    /** 증설·축소 권고 임계. 관제센터를 켜면 임계값 레지스트리, 끄면 설정값이다. 응답 모양은 같다. */
    private Limits limits() {
        if (registry.isEmpty()) {
            return new Limits(props.getScaleOutCpu(), props.getScaleInCpu());
        }
        OpsThresholdService.ReadThresholds read = registry.get().readThresholds();
        return new Limits(read.scaleOut().doubleValue(), read.scaleIn().doubleValue());
    }

    /**
     * 권고. 표시까지이고 조치로 이어지지 않는다(정본 7.5 스케일링 실행 주체).
     *
     * 순서가 뜻을 갖는다. 계약과 관측이 어긋난 상태가 부하보다 먼저다. 3 대여야 하는데 2 대인
     * 상황에서 "부하가 높으니 늘리세요" 를 먼저 보이면 이미 누른 버튼을 또 누르게 된다.
     */
    private List<Map<String, Object>> advice(CapacityPlanService.Plan plan, int up, Double avgCpu, boolean leaderUp,
                                             Limits limits, List<CapacityProperties.Node> configured) {
        List<Map<String, Object>> out = new ArrayList<>();

        if (!leaderUp) {
            // 이 문구가 정확해야 한다. 노드를 늘려도 쓰기는 돌아오지 않는다. 늘어나는 노드는
            // 매칭엔진이 없는 조회 전용이다(ADR 0015 영향 절).
            out.add(card("leader_down", "danger", "주문 접수 중단",
                    "주문을 처리하는 앱 노드가 응답하지 않습니다. 조회는 남은 앱 노드가 계속 받습니다. "
                            + "조회 전용 노드를 켜도 조회 용량만 늘고 주문은 재개되지 않습니다. "
                            + "주문은 그 노드가 다시 서야 재개됩니다.", null));
        }
        if (up < plan.desired()) {
            out.add(card("under_provisioned", "warn", "노드 부족",
                    "있어야 할 앱 노드가 " + plan.desired() + "대인데 " + up + "대만 응답합니다. "
                            + "켠 노드가 준비 중이거나 한 대가 멈춘 상태입니다.", null));
        }
        if (up > plan.desired()) {
            out.add(card("over_provisioned", "warn", "노드 초과",
                    "있어야 할 앱 노드가 " + plan.desired() + "대인데 " + up + "대가 응답합니다. "
                            + "끄기가 끝나지 않았고 그동안 비용이 더 나갑니다.", null));
        }
        if (avgCpu == null) {
            out.add(card("no_metrics", "warn", "상태 읽기 실패",
                    "조회를 받는 앱 노드에서 사용률을 가져오지 못했습니다. 판단 근거가 없어 "
                            + "권고를 내지 않습니다.", null));
            return out;
        }
        int pct = (int) Math.round(avgCpu * 100);
        if (avgCpu >= limits.scaleOut() && plan.desired() < plan.max()) {
            out.add(card("scale_out", "warn", "조회 부하 높음",
                    "조회를 받는 앱 노드의 평균 사용률이 " + pct + "%입니다. " + nodeName(configured, plan.desired())
                            + "를 켜면 앱 계층이 " + (plan.desired() + 1) + "대가 되어 조회가 나뉩니다. "
                            + "켜는 데 약 17초가 걸리고 서비스는 멈추지 않습니다.",
                    "SCALE_OUT"));
        } else if (avgCpu >= limits.scaleOut()) {
            out.add(card("at_max", "warn", "조회 부하 높음 · 증설 불가",
                    "평균 사용률이 " + pct + "%인데 이미 앱 계층 최대 " + plan.max() + "대입니다. "
                            + "최대 대수를 올리려면 비용 판단이 먼저입니다.", null));
        } else if (avgCpu <= limits.scaleIn() && plan.desired() > plan.min()) {
            out.add(card("scale_in", "info", "조회 부하 낮음",
                    "조회를 받는 앱 노드의 평균 사용률이 " + pct + "%입니다. " + nodeName(configured, plan.desired() - 1)
                            + "를 끄면 앱 계층이 " + (plan.desired() - 1) + "대가 되어 비용이 줄고, "
                            + "끄기는 진행 중인 요청을 먼저 흘려보낸 뒤 내립니다.",
                    "SCALE_IN"));
        } else {
            out.add(card("steady", "ok", "안정",
                    "조회를 받는 앱 노드의 평균 사용률이 " + pct + "%입니다. 할 조치가 없습니다.", null));
        }
        return out;
    }

    /** 켜고 끄는 노드는 설정 순서로 희망 대수 경계에 있는 노드다(AWS 구성에서는 app-3). */
    private static String nodeName(List<CapacityProperties.Node> configured, int index) {
        return index >= 0 && index < configured.size() ? configured.get(index).getId() : "조회 전용 노드";
    }

    private static Map<String, Object> card(String id, String level, String headline,
                                            String detail, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("level", level);
        m.put("headline", headline);
        m.put("detail", detail);
        m.put("action", action);
        return m;
    }
}
