package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final String TIER = "app";

    /** 권고 임계. ADR 0015 의 운영값이고 클라우드 착지 뒤 관측으로 조인다. */
    private static final double SCALE_OUT_CPU = 0.60;
    private static final double SCALE_IN_CPU = 0.30;

    public record ScaleBody(String reason) {
    }

    private final CapacityProperties props;
    private final NodeProbe probe;
    private final AdminAuditService audit;
    private final JdbcClient jdbc;

    public CapacityAdminController(CapacityProperties props, NodeProbe probe,
                                   AdminAuditService audit, JdbcClient jdbc) {
        this.props = props;
        this.probe = probe;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> state(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        return snapshot();
    }

    @PostMapping("/scale-out")
    @Transactional
    public Map<String, Object> scaleOut(@RequestBody(required = false) ScaleBody body,
                                        HttpServletRequest request) {
        return change(+1, "capacity.scale_out", body, request);
    }

    @PostMapping("/scale-in")
    @Transactional
    public Map<String, Object> scaleIn(@RequestBody(required = false) ScaleBody body,
                                       HttpServletRequest request) {
        return change(-1, "capacity.scale_in", body, request);
    }

    /* ===== 조치 ===== */

    private Map<String, Object> change(int delta, String action, ScaleBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);

        Plan before = plan();
        int next = before.desired() + delta;
        if (next < before.min() || next > before.max()) {
            // 경계를 넘는 요청은 DB CHECK 가 막기 전에 여기서 막는다. 그래야 화면이
            // 제약 위반 메시지 대신 사람이 읽을 수 있는 사유를 받는다.
            audit.record(principal.operatorId(), action, TIER,
                    AdminRequestContext.requestId(request), null, "DENIED",
                    Map.of("desired", before.desired(), "requested", next,
                            "min", before.min(), "max", before.max()));
            throw new ApiException(409, "CAPACITY_OUT_OF_BOUNDS",
                    next < before.min()
                            ? "최소 " + before.min() + "대 아래로 축소 불가"
                            : "최대 " + before.max() + "대 위로 증설 불가");
        }

        jdbc.sql("UPDATE capacity_plan SET desired_nodes = ?, updated_by = ?, updated_at = now() WHERE tier = ?")
                .params(next, principal.operatorId(), TIER)
                .update();

        audit.record(principal.operatorId(), action, TIER,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("from", before.desired(), "to", next,
                        "reason", body == null || body.reason() == null ? "" : body.reason()));

        Map<String, Object> out = new LinkedHashMap<>(snapshot());
        out.put("changed_from", before.desired());
        out.put("changed_to", next);
        return out;
    }

    /* ===== 상태 ===== */

    private record Plan(int min, int desired, int max, String updatedBy, OffsetDateTime updatedAt) {
    }

    private Plan plan() {
        return jdbc.sql("SELECT min_nodes, desired_nodes, max_nodes, updated_by, updated_at FROM capacity_plan WHERE tier = ?")
                .param(TIER)
                .query((rs, i) -> new Plan(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class)))
                .single();
    }

    private Map<String, Object> snapshot() {
        Plan plan = plan();
        List<CapacityProperties.Node> configured = props.getNodes();
        Map<String, NodeProbe.Observed> observed = probe.probeAll(configured);

        List<Map<String, Object>> nodes = new ArrayList<>();
        int up = 0;
        int readServing = 0;
        double cpuSum = 0;
        boolean leaderUp = false;
        for (int i = 0; i < configured.size(); i++) {
            CapacityProperties.Node c = configured.get(i);
            NodeProbe.Observed o = observed.getOrDefault(c.getId(), NodeProbe.Observed.unreachable());
            // 계약상 떠 있어야 하는 노드는 설정 순서로 앞에서부터 희망 대수만큼이다.
            boolean expected = i < plan.desired();
            if (o.reachable()) {
                up++;
                if (o.leader()) {
                    leaderUp = true;
                }
                if (o.servesReads() && o.cpuRatio() != null) {
                    readServing++;
                    cpuSum += o.cpuRatio();
                }
            }
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
        Double avgCpu = readServing == 0 ? null : cpuSum / readServing;

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
        out.put("advice", advice(plan, up, avgCpu, leaderUp));
        return out;
    }

    /**
     * 권고. 표시까지이고 조치로 이어지지 않는다(정본 7.12 자동화 격자 3층).
     *
     * 순서가 뜻을 갖는다. 계약과 관측이 어긋난 상태가 부하보다 먼저다. 3 대여야 하는데 2 대인
     * 상황에서 "부하가 높으니 늘리세요" 를 먼저 보이면 이미 누른 버튼을 또 누르게 된다.
     */
    private List<Map<String, Object>> advice(Plan plan, int up, Double avgCpu, boolean leaderUp) {
        List<Map<String, Object>> out = new ArrayList<>();

        if (!leaderUp) {
            // 이 문구가 정확해야 한다. 노드를 늘려도 쓰기는 돌아오지 않는다. 늘어나는 노드는
            // 매칭엔진이 없는 조회 전용이다(ADR 0015 영향 절).
            out.add(card("leader_down", "danger", "주문 접수 중단",
                    "주문을 처리하는 서버가 응답하지 않는다. 조회는 남은 서버가 계속 받는다. "
                            + "서버를 추가해도 조회 용량만 늘고 주문은 재개되지 않는다. "
                            + "주문 재개는 그 서버가 다시 서야 가능하다.", null));
        }
        if (up < plan.desired()) {
            out.add(card("under_provisioned", "warn", "서버 부족",
                    "있어야 할 서버가 " + plan.desired() + "대인데 " + up + "대만 응답한다. "
                            + "추가한 서버가 준비 중이거나 한 대가 멈춘 상태다.", null));
        }
        if (up > plan.desired()) {
            out.add(card("over_provisioned", "warn", "서버 초과",
                    "있어야 할 서버가 " + plan.desired() + "대인데 " + up + "대가 응답한다. "
                            + "줄이기가 끝나지 않았고 그동안 비용이 더 나간다.", null));
        }
        if (avgCpu == null) {
            out.add(card("no_metrics", "warn", "상태 읽기 실패",
                    "조회를 받는 서버에서 사용률을 가져오지 못했다. 판단 근거가 없어 "
                            + "권고를 내지 않는다.", null));
            return out;
        }
        int pct = (int) Math.round(avgCpu * 100);
        if (avgCpu >= SCALE_OUT_CPU && plan.desired() < plan.max()) {
            out.add(card("scale_out", "warn", "조회 부하 높음",
                    "조회를 받는 서버의 평균 사용률 " + pct + "%. 서버를 한 대 늘리면 부하가 "
                            + "나뉜다. 추가에 약 17초가 걸리고 서비스는 멈추지 않는다.",
                    "SCALE_OUT"));
        } else if (avgCpu >= SCALE_OUT_CPU) {
            out.add(card("at_max", "warn", "조회 부하 높음. 증설 불가",
                    "평균 사용률 " + pct + "%인데 이미 최대 " + plan.max() + "대다. "
                            + "최대 대수를 올리려면 비용 판정이 먼저다.", null));
        } else if (avgCpu <= SCALE_IN_CPU && plan.desired() > plan.min()) {
            out.add(card("scale_in", "info", "조회 부하 낮음",
                    "조회를 받는 서버의 평균 사용률 " + pct + "%. 서버를 한 대 줄이면 비용이 "
                            + "준다. 줄이기는 진행 중인 요청을 먼저 흘려보낸 뒤 내린다.",
                    "SCALE_IN"));
        } else {
            out.add(card("steady", "ok", "안정",
                    "조회를 받는 서버의 평균 사용률 " + pct + "%. 조치 불요.", null));
        }
        return out;
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
