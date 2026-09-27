package kr.exchange.admin.msp;

import kr.exchange.admin.BotRateControl;
import kr.exchange.admin.CapacityPlanService;
import kr.exchange.api.common.ApiFormats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 서비스 흐름·지표 관제의 실측 한 판(API 설계 §6-F '흐름', 관리자 설계 4-2). 최근 150초를 2초 격자로 읽어 앱 노드, DB 노드,
 * 거래와 주문, 진입 검사, 앱 계층 희망 대수, 봇 목표 발행률을 낸다. 조회 요청은 노드별과 전체 모두 수용과 요청 제한 거절(429)을
 * 나눠 싣는다. 배열의 i 번째 값은 격자 start + i × step 의 값이고, 값이 없는 점은 null 이다. 대상이 내려간 점에서는 그 대상의
 * 다른 지표도 싣지 않는다.
 */
public class MspFlow {

    static final Duration STEP = Duration.ofSeconds(2);
    static final Duration WINDOW = Duration.ofSeconds(150);
    /** DB 읽기가 실패한 뒤 DB 칸(희망 대수, 봇 목표)을 곧바로 unavailable 로 내는 시간 */
    static final Duration DB_COOLDOWN = Duration.ofSeconds(5);

    /** 수집 대상 하나. key 는 다른 질의의 계열과 잇는 라벨 값(앱은 instance, DB 는 node)이다 */
    private record Target(String node, String role, String key, Map<Long, Double> up) {
    }

    private record PlanHistory(CapacityPlanService.Plan plan, List<CapacityPlanService.Shift> shifts) {
    }

    private final MspProperties props;
    private final PrometheusClient prometheus;
    private final CapacityPlanService capacity;
    private final BotRateControl bots;
    private final SourceCache cache;
    private final Clock clock;
    private final DbGuard db;

    /** prometheus 는 원천 주소가 없으면 null 이다. 봇 칸만 보지 않는 시험은 bots 를 넘기지 않는다. 그 칸은 연결 전으로 낸다. */
    public MspFlow(MspProperties props, PrometheusClient prometheus, CapacityPlanService capacity, BotRateControl bots,
                   SourceCache cache) {
        this.props = props;
        this.prometheus = prometheus;
        this.capacity = capacity;
        this.bots = bots;
        this.cache = cache;
        this.clock = cache.clock();
        this.db = new DbGuard(clock, DB_COOLDOWN);
    }

    public Map<String, Object> view() {
        Instant now = clock.instant();
        long step = STEP.toSeconds();
        Instant end = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), step) * step);
        Instant start = end.minus(WINDOW);
        List<Instant> grid = new ArrayList<>();
        for (Instant t = start; !t.isAfter(end); t = t.plus(STEP)) {
            grid.add(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("environment", props.getEnvironment().name());
        out.put("generated_at", ApiFormats.time(now));
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", ApiFormats.time(start));
        window.put("end", ApiFormats.time(end));
        window.put("step_seconds", step);
        out.put("window", window);
        out.put("flow", flow(start, end, grid));
        // 희망 대수와 봇 목표는 DB 에서 읽는다. 주 DB 전환 동안 그 읽기가 실패해도 Prometheus 원천의 흐름 칸은 그대로 낸다
        out.put("read_tier", db.section(() -> readTier(start, end, grid)));
        out.put("bots", db.section(() -> bots(end)));
        return out;
    }

    /* ===== Prometheus ===== */

    private Map<String, Object> flow(Instant start, Instant end, List<Instant> grid) {
        if (prometheus == null || !FlowQuery.APP_UP.available(props.getPrometheus())) {
            return MspSections.notConnected("prometheus");
        }
        PrometheusClient.Frames f = prometheus.frames(start, end, STEP).value();
        if (f.failure() != null) {
            return MspSections.unavailable(f.failure());
        }
        // 앱 노드 목록을 up 에서 얻으므로 이 질의가 없으면 칸을 그릴 수 없다.
        String upFailed = f.failures().get(FlowQuery.APP_UP);
        if (upFailed != null) {
            return MspSections.unavailable(upFailed);
        }
        List<Target> apps = targets(f.results().get(FlowQuery.APP_UP), "instance");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apps", apps(f, apps, grid));
        data.put("dbs", dbs(f, grid));
        data.put("trades_per_second", numbers(only(f, FlowQuery.TRADES), grid, MspSections.Unit.TENTHS));
        data.put("order_rate", numbers(only(f, FlowQuery.ORDERS), grid, MspSections.Unit.TENTHS));
        data.put("read_rate", total(f, FlowQuery.APP_READS, apps, grid));
        data.put("read_rejected_rate", total(f, FlowQuery.APP_READS_REJECTED, apps, grid));
        data.put("replication_lag_bytes", numbers(only(f, FlowQuery.REPL_LAG_BYTES), grid, MspSections.Unit.INTEGER));
        data.put("replication_mode", codes(only(f, FlowQuery.REPL_MODE), grid, MspBoard::replicationMode));
        data.put("promotion", codes(only(f, FlowQuery.PROMOTION), grid, MspBoard::promotion));
        data.put("probes", probes(f, grid));
        data.put("failed", f.failures().keySet().stream().map(q -> q.name().toLowerCase(Locale.ROOT)).toList());
        return MspSections.ready(data);
    }

    private List<Map<String, Object>> apps(PrometheusClient.Frames f, List<Target> targets, List<Instant> grid) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Target t : targets) {
            List<Boolean> up = flags(t.up(), grid, null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", t.node());
            row.put("role", t.role());
            row.put("up", up);
            row.put("cpu_ratio", numbers(by(f, FlowQuery.APP_CPU, "instance", t.key()), grid, up,
                    MspSections.Unit.RATIO));
            row.put("read_rate", numbers(by(f, FlowQuery.APP_READS, "instance", t.key()), grid, up,
                    MspSections.Unit.TENTHS));
            row.put("read_rejected_rate", numbers(by(f, FlowQuery.APP_READS_REJECTED, "instance", t.key()), grid, up,
                    MspSections.Unit.TENTHS));
            row.put("write_rate", numbers(by(f, FlowQuery.APP_WRITES, "instance", t.key()), grid, up,
                    MspSections.Unit.TENTHS));
            row.put("leader", flags(by(f, FlowQuery.APP_LEADER, "instance", t.key()), grid, up));
            row.put("epoch", integers(by(f, FlowQuery.APP_EPOCH, "instance", t.key()), grid, up));
            row.put("write_state", writeStates(f, t.key(), grid, up));
            row.put("lease_age_seconds", numbers(by(f, FlowQuery.APP_LEASE_AGE, "instance", t.key()), grid, up,
                    MspSections.Unit.SECONDS));
            rows.add(row);
        }
        return rows;
    }

    /** DB 노드의 역할은 설정이나 노드 이름이 아니라 recovery 지표로 정한다(ADR 0032). 설정이 없으면 null 이다. */
    private List<Map<String, Object>> dbs(PrometheusClient.Frames f, List<Instant> grid) {
        if (!f.results().containsKey(FlowQuery.DB_TARGET)) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Target t : targets(f.results().get(FlowQuery.DB_TARGET), "node")) {
            List<Boolean> reachable = flags(t.up(), grid, null);
            List<Boolean> up = flags(by(f, FlowQuery.DB_UP, "node", t.node()), grid, reachable);
            // pg_up 을 읽지 못했으면 수집 대상이 떠 있는 점까지만 싣는다.
            List<Boolean> alive = up != null ? up : reachable;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", t.node());
            row.put("reachable", reachable);
            row.put("up", up);
            row.put("in_recovery", flags(by(f, FlowQuery.DB_RECOVERY, "node", t.node()), grid, alive));
            row.put("connections", integers(by(f, FlowQuery.DB_CONNECTIONS, "node", t.node()), grid, alive));
            row.put("xact_rate", numbers(by(f, FlowQuery.DB_XACT, "node", t.node()), grid, alive,
                    MspSections.Unit.TENTHS));
            rows.add(row);
        }
        return rows;
    }

    /** 진입 검사. 로컬 Docker 는 AWS 의 CloudFront·ALB 자리를 같은 일을 하는 웹 입구·로드밸런서 검사로 잰다. */
    private static List<Map<String, Object>> probes(PrometheusClient.Frames f, List<Instant> grid) {
        List<PrometheusClient.RangeSeries> series = f.results().get(FlowQuery.PROBE);
        if (series == null) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        series.stream().filter(s -> s.labels().get("service") != null)
                .sorted(Comparator.comparing(s -> s.labels().get("service")))
                .forEach(s -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("service", s.labels().get("service"));
                    row.put("success", flags(s.values(), grid, null));
                    rows.add(row);
                });
        return rows;
    }

    /** up 계열에서 대상 목록을 얻는다. 이름을 붙일 수 없는(node 라벨이 없는) 대상은 뺀다. 같은 node 는 첫 대상만 쓴다. */
    private static List<Target> targets(List<PrometheusClient.RangeSeries> ups, String key) {
        Map<String, Target> byNode = new LinkedHashMap<>();
        ups.stream()
                .filter(s -> s.labels().get("node") != null && s.labels().get(key) != null)
                .sorted(Comparator.comparing((PrometheusClient.RangeSeries s) -> s.labels().get("node"))
                        .thenComparing(s -> s.labels().get(key)))
                .forEach(s -> byNode.putIfAbsent(s.labels().get("node"), new Target(s.labels().get("node"),
                        s.labels().get("role"), s.labels().get(key), s.values())));
        return List.copyOf(byNode.values());
    }

    private static Map<Long, Double> by(PrometheusClient.Frames f, FlowQuery query, String label, String value) {
        List<PrometheusClient.RangeSeries> series = f.results().get(query);
        if (series == null) {
            return null;
        }
        return series.stream().filter(s -> value.equals(s.labels().get(label))).findFirst()
                .map(PrometheusClient.RangeSeries::values).orElse(Map.of());
    }

    /**
     * 앱 계층 전체의 초당 건수. 격자마다 떠 있는 노드 값의 합이라 노드별 배열을 더한 것과 같다. 질의가 실패했으면 배열 전체가
     * null 이고, 떠 있는 노드가 없거나 값을 낸 노드가 없는 점은 null 이다.
     */
    private static List<String> total(PrometheusClient.Frames f, FlowQuery query, List<Target> apps, List<Instant> grid) {
        if (f.results().get(query) == null) {
            return null;
        }
        List<Map<Long, Double>> values = apps.stream().map(t -> by(f, query, "instance", t.key())).toList();
        List<String> out = new ArrayList<>(grid.size());
        for (Instant t : grid) {
            long at = t.getEpochSecond();
            Double sum = null;
            for (int k = 0; k < apps.size(); k++) {
                Double up = apps.get(k).up().get(at);
                Double v = values.get(k).get(at);
                if (up != null && up > 0 && v != null) {
                    sum = (sum == null ? 0.0 : sum) + v;
                }
            }
            out.add(MspSections.number(sum, MspSections.Unit.TENTHS));
        }
        return out;
    }

    private static Map<Long, Double> only(PrometheusClient.Frames f, FlowQuery query) {
        List<PrometheusClient.RangeSeries> series = f.results().get(query);
        if (series == null) {
            return null;
        }
        return series.isEmpty() ? Map.of() : series.get(0).values();
    }

    private static List<String> writeStates(PrometheusClient.Frames f, String instance, List<Instant> grid,
                                            List<Boolean> up) {
        List<PrometheusClient.RangeSeries> series = f.results().get(FlowQuery.APP_WRITE_STATE);
        List<String> out = new ArrayList<>(grid.size());
        for (int i = 0; i < grid.size(); i++) {
            long at = grid.get(i).getEpochSecond();
            String state = null;
            if (series != null && Boolean.TRUE.equals(up.get(i))) {
                state = series.stream()
                        .filter(s -> instance.equals(s.labels().get("instance")) && s.values().containsKey(at))
                        .map(s -> s.labels().get("state")).filter(Objects::nonNull).findFirst().orElse(null);
            }
            out.add(state);
        }
        return out;
    }

    /* ===== 격자 값 =====
     * values 가 null 이면 질의가 돌지 않았거나 실패한 것이고 배열 전체가 null 이다. alive 가 있으면 그 점이 true 일 때만 싣는다 */

    private static List<Boolean> flags(Map<Long, Double> values, List<Instant> grid, List<Boolean> alive) {
        if (values == null) {
            return null;
        }
        List<Boolean> out = new ArrayList<>(grid.size());
        for (int i = 0; i < grid.size(); i++) {
            Double v = values.get(grid.get(i).getEpochSecond());
            out.add(v == null || (alive != null && !Boolean.TRUE.equals(alive.get(i))) ? null : v > 0);
        }
        return out;
    }

    private static List<String> numbers(Map<Long, Double> values, List<Instant> grid, MspSections.Unit unit) {
        return numbers(values, grid, null, unit);
    }

    private static List<String> numbers(Map<Long, Double> values, List<Instant> grid, List<Boolean> alive,
                                        MspSections.Unit unit) {
        if (values == null) {
            return null;
        }
        List<String> out = new ArrayList<>(grid.size());
        for (int i = 0; i < grid.size(); i++) {
            boolean shown = alive == null || Boolean.TRUE.equals(alive.get(i));
            out.add(shown ? MspSections.number(values.get(grid.get(i).getEpochSecond()), unit) : null);
        }
        return out;
    }

    /** 격자마다의 코드 값을 계약 이름으로. 질의가 돌지 않았거나 실패했으면 배열 전체가 null 이다 */
    private static List<String> codes(Map<Long, Double> values, List<Instant> grid,
                                      java.util.function.Function<Double, String> name) {
        if (values == null) {
            return null;
        }
        List<String> out = new ArrayList<>(grid.size());
        for (Instant t : grid) {
            out.add(name.apply(values.get(t.getEpochSecond())));
        }
        return out;
    }

    private static List<Long> integers(Map<Long, Double> values, List<Instant> grid, List<Boolean> alive) {
        if (values == null) {
            return null;
        }
        List<Long> out = new ArrayList<>(grid.size());
        for (int i = 0; i < grid.size(); i++) {
            Double v = values.get(grid.get(i).getEpochSecond());
            out.add(v == null || (alive != null && !Boolean.TRUE.equals(alive.get(i))) ? null : Math.round(v));
        }
        return out;
    }

    /* ===== 앱 계층과 봇 ===== */

    private Map<String, Object> readTier(Instant start, Instant end, List<Instant> grid) {
        MspProperties.ReadCapacityMode mode = props.getReadCapacity().getMode();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode.name());
        switch (mode) {
            case NONE -> {
                return MspSections.notConnected("read_capacity");
            }
            // AWS 는 ASG 가 대상당 요청 수로 대수를 정한다. 대수는 AWS API 를 읽지 않으므로 싣지 않는다.
            case ASG_POLICY -> data.put("scaling_owner", "ASG");
            case CAPACITY_PLAN -> {
                PlanHistory h = cache.get("capacity:flow:" + end.getEpochSecond(), STEP,
                        () -> new PlanHistory(capacity.plan(), capacity.shiftsSince(start))).value();
                data.put("min", h.plan().min());
                data.put("max", h.plan().max());
                data.put("desired", desiredGrid(h.shifts(), h.plan().desired(), grid));
            }
        }
        return MspSections.ready(data);
    }

    /**
     * 격자마다의 희망 대수. 그 시각까지의 마지막 변경 뒤 대수이고, 창 안의 첫 변경보다 앞이면 그 변경 전 대수, 창 안에 변경이
     * 없으면 지금 대수다.
     */
    static List<Integer> desiredGrid(List<CapacityPlanService.Shift> shifts, int current, List<Instant> grid) {
        List<Integer> out = new ArrayList<>(grid.size());
        int k = 0;
        for (Instant t : grid) {
            while (k < shifts.size() && !shifts.get(k).at().isAfter(t)) {
                k++;
            }
            out.add(k > 0 ? shifts.get(k - 1).to() : shifts.isEmpty() ? current : shifts.get(0).from());
        }
        return out;
    }

    private Map<String, Object> bots(Instant end) {
        if (bots == null) {
            return MspSections.notConnected("bot_fleet");
        }
        BotRateControl.Current c = cache.get("bots:current:" + end.getEpochSecond(), STEP, bots::current).value();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", c.source());
        data.put("target_ops", MspSections.number(c.targetOps(), MspSections.Unit.TENTHS));
        data.put("sampled_at", MspSections.time(c.sampledAt()));
        return MspSections.ready(data);
    }
}
