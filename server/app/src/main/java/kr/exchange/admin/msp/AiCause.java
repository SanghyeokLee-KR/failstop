package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiFormats;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 원인 분석 근거 묶음(설계 7-19, API 설계 §6-F '원인 분석과 부하 판정'). 경보나 넘은 임계가 있을 때 같은 시간 창의 지표와 운영
 * 이벤트를 결정적으로 모아 전후 평균, 변화, 대상 지표와의 상관을 계산한다. 모델은 이 묶음만 보고 가설을 쓰고, 서버는 인용 id
 * 로 이 묶음의 값을 다시 붙인다. 수집되지 않은 지표는 항목을 만들지 않고 missing 에 적는다. 로그 문장과 사람이 쓴 문장은
 * 싣지 않는다.
 */
public class AiCause {

    static final String SCHEMA = "msp-cause/1";
    static final Duration BASELINE = Duration.ofMinutes(15);
    static final Duration MAX_LOOKBACK = Duration.ofMinutes(60);
    static final Duration MIN_INCIDENT = Duration.ofMinutes(2);
    static final Duration DEFAULT_ANCHOR = Duration.ofMinutes(5);
    static final long MIN_STEP = 15;
    static final int MAX_POINTS = 200;
    static final int TOP_ROUTES = 5;
    static final int MAX_EVENTS = 12;
    static final int MAX_LOGS = 5;
    static final int MAX_ALERTS = 8;
    static final int MIN_PAIRS = 8;
    // 감사 기록에서 읽는 운영 이벤트. 행위자는 사람·AI·시스템 구분만, 값은 아래 허용 필드만 싣는다.
    static final List<String> AUDIT_ACTIONS = List.of("capacity.scale_out", "capacity.scale_in", "sim.rate",
            "sim.scenario", "ops.threshold.set", "ops.threshold.revert");

    /** 감사 기록의 운영 이벤트 하나. detail 에는 허용한 필드만 남긴다. */
    record AuditEvent(Instant at, String action, String actor, String target, Map<String, Object> detail) {
    }

    /** 묶음 한 벌. items 는 인용 id 로 찾는다. json 은 모델에 보낸 원문이고 sha256 은 그 해시다. */
    record Bundle(Instant collectedAt, Instant start, Instant anchor, Instant end, Duration step, String targetId,
                  String targetLabel, List<String> trigger, LoadPattern load, Map<String, Map<String, Object>> items,
                  List<Map<String, Object>> missing, String json, String sha256) {
    }

    /** 한 계열의 전후 통계. before 는 기준 시각 앞, value 는 기준 시각부터의 평균이다. 값이 없으면 null 이다. */
    private record Stats(Double before, Double value, Double now, Instant nowAt, Double peak, Instant peakAt) {
    }

    private final MspProperties props;
    private final PrometheusClient prometheus;
    private final Function<Instant, List<AuditEvent>> audit;
    private final Supplier<ErrorLogPoller.Digest> logs;
    private final AlertMeanings meanings;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** prometheus 와 logs 는 원천이 없으면 null 이다. audit 은 창 시작 시각 뒤의 운영 이벤트를 읽는다. */
    public AiCause(MspProperties props, PrometheusClient prometheus, Function<Instant, List<AuditEvent>> audit,
                   Supplier<ErrorLogPoller.Digest> logs, AlertMeanings meanings, ObjectMapper mapper, Clock clock) {
        this.props = props;
        this.prometheus = prometheus;
        this.audit = audit;
        this.logs = logs;
        this.meanings = meanings;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 감사 기록에서 운영 이벤트를 읽는다. 최근 20건이고 사유 같은 자유 문장은 읽지 않는다. */
    static Function<Instant, List<AuditEvent>> auditEvents(JdbcClient jdbc, ObjectMapper mapper) {
        return since -> jdbc.sql("""
                        SELECT created_at, action, operator_id, target, detail::text AS detail
                        FROM admin_audit_log
                        WHERE action IN ('capacity.scale_out', 'capacity.scale_in', 'sim.rate', 'sim.scenario',
                                         'ops.threshold.set', 'ops.threshold.revert')
                          AND result = 'OK' AND created_at >= ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 20
                        """)
                .param(since.atOffset(ZoneOffset.UTC))
                .query((rs, i) -> new AuditEvent(rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getString("action"), actor(rs.getString("operator_id")), rs.getString("target"),
                        allowed(mapper, rs.getString("detail"))))
                .list();
    }

    static String actor(String operatorId) {
        if (AiControl.AI_OPERATOR.equals(operatorId)) {
            return "AI";
        }
        return operatorId != null && operatorId.startsWith("system:") ? "SYSTEM" : "OPERATOR";
    }

    private static Map<String, Object> allowed(ObjectMapper mapper, String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        try {
            Map<String, Object> detail = mapper.readValue(json, new TypeReference<>() {
            });
            for (String key : List.of("from", "to", "target_ops", "scenario")) {
                Object v = detail.get(key);
                if (v instanceof Number || (v instanceof String s && s.length() <= 32)) {
                    out.put(key, v);
                }
            }
        } catch (JsonProcessingException e) {
            return out;
        }
        return out;
    }

    Bundle build(AiEvidence ev) {
        Instant now = clock.instant();
        List<AiEvidence.Alert> judged = ev.judgedAlerts();
        LoadPattern load = ev.load();
        Instant anchor = anchor(judged, load, now);
        long step = step(anchor, now);
        long end = Math.floorDiv(now.getEpochSecond(), step) * step;
        long start = Math.floorDiv(anchor.minus(BASELINE).getEpochSecond(), step) * step;
        long anchorAt = Math.floorDiv(anchor.getEpochSecond(), step) * step;

        List<Map<String, Object>> missing = new ArrayList<>();
        PrometheusClient.Ranges<CauseQuery> ranges = ranges(start, end, step, missing);
        Map<CauseQuery, Map<Long, Double>> single = new EnumMap<>(CauseQuery.class);
        for (CauseQuery q : CauseQuery.signals()) {
            List<PrometheusClient.RangeSeries> series = ranges == null ? null : ranges.results().get(q);
            if (series != null && !series.isEmpty() && !series.get(0).values().isEmpty()) {
                single.put(q, series.get(0).values());
            } else if (ranges != null && !missing.stream().anyMatch(m -> q.id().equals(m.get("id")))) {
                missing.add(missing(q.id(), q.label, ranges.failures().containsKey(q) ? "query_failed" : "no_series"));
            }
        }
        CauseQuery target = target(judged, ev.crossings(), single);

        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        alerts(ev, items);
        if (load != null) {
            Map<String, Object> item = item("load", "load", "부하 판정(규칙)");
            item.putAll(load.toJson());
            items.put("load", item);
        }
        events(ranges, start, end, step, items);
        signals(single, target, start, end, step, anchorAt, items);
        nodes(ranges, target == null ? null : single.get(target), start, end, step, anchorAt, items);
        routes(ranges, start, end, step, anchorAt, items);
        logs(items);
        bot(ev, items);

        List<String> trigger = new ArrayList<>();
        judged.forEach(a -> trigger.add(a.displayName()));
        trigger.addAll(ev.crossings());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("environment", ev.environment().name());
        body.put("collected_at", ApiFormats.time(now));
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", ApiFormats.time(Instant.ofEpochSecond(start)));
        window.put("anchor", ApiFormats.time(Instant.ofEpochSecond(anchorAt)));
        window.put("end", ApiFormats.time(Instant.ofEpochSecond(end)));
        window.put("step_seconds", step);
        body.put("window", window);
        body.put("trigger", trigger);
        body.put("target", target == null ? null : target.id());
        body.put("items", new ArrayList<>(items.values()));
        body.put("missing", missing);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("원인 분석 근거 직렬화 실패", e);
        }
        return new Bundle(now, Instant.ofEpochSecond(start), Instant.ofEpochSecond(anchorAt), Instant.ofEpochSecond(end),
                Duration.ofSeconds(step), target == null ? null : target.id(), target == null ? null : target.label,
                List.copyOf(trigger), load, items, List.copyOf(missing), json, AiStore.sha256(json));
    }

    /**
     * 기준 시각. 판단 대상 경보 가운데 가장 이른 시작 시각이고, 경보 없이 임계만 넘었으면 부하 판정의 초과 시작, 그마저
     * 없으면 5분 전이다. 앞쪽 평균을 낼 자리와 뒤쪽 점이 남게 60분 전과 2분 전 사이로 자른다.
     */
    static Instant anchor(List<AiEvidence.Alert> judged, LoadPattern load, Instant now) {
        Instant anchor = judged.stream().map(AiEvidence.Alert::startsAt).min(Comparator.naturalOrder())
                .orElse(load != null && load.aboveSince() != null ? load.aboveSince() : now.minus(DEFAULT_ANCHOR));
        Instant earliest = now.minus(MAX_LOOKBACK);
        Instant latest = now.minus(MIN_INCIDENT);
        return anchor.isBefore(earliest) ? earliest : anchor.isAfter(latest) ? latest : anchor;
    }

    /** 한 계열의 점이 MAX_POINTS 를 넘지 않는 15초의 배수. */
    static long step(Instant anchor, Instant now) {
        long span = now.getEpochSecond() - anchor.minus(BASELINE).getEpochSecond();
        long raw = (span + MAX_POINTS - 1) / MAX_POINTS;
        return Math.max(MIN_STEP, (raw + MIN_STEP - 1) / MIN_STEP * MIN_STEP);
    }

    private PrometheusClient.Ranges<CauseQuery> ranges(long start, long end, long step,
                                                       List<Map<String, Object>> missing) {
        MspProperties.Prometheus jobs = props.getPrometheus();
        Map<CauseQuery, String> queries = new EnumMap<>(CauseQuery.class);
        for (CauseQuery q : CauseQuery.values()) {
            if (prometheus != null && q.available(jobs)) {
                queries.put(q, q.promql(jobs));
            } else if (q.id() != null) {
                missing.add(missing(q.id(), q.label, prometheus == null ? "unavailable" : "not_configured"));
            }
        }
        if (prometheus == null) {
            return null;
        }
        return prometheus.ranges(CauseQuery.class, queries, Instant.ofEpochSecond(start), Instant.ofEpochSecond(end),
                Duration.ofSeconds(step));
    }

    /**
     * 대상 지표. 판단 대상 경보(critical 먼저)의 의미표가 정한 지표 가운데 값이 있는 첫 지표이고, 없으면 넘은 임계가 가리키는
     * 지표, 그마저 없으면 앱 계층 CPU 다. 값이 없으면 null 이고 그때는 상관을 내지 않는다.
     */
    private CauseQuery target(List<AiEvidence.Alert> judged, List<String> crossings,
                              Map<CauseQuery, Map<Long, Double>> single) {
        List<AiEvidence.Alert> ordered = new ArrayList<>(judged);
        ordered.sort(Comparator.comparing(a -> "critical".equals(a.severity()) ? 0 : 1));
        for (AiEvidence.Alert a : ordered) {
            CauseQuery q = CauseQuery.bySignal(meanings.of(a.alertname()).signal());
            if (q != null && single.containsKey(q)) {
                return q;
            }
        }
        for (String c : crossings) {
            CauseQuery q = switch (c) {
                case "host.cpu_alert" -> CauseQuery.HOST_CPU;
                case "db.replication_lag_alert" -> CauseQuery.REPL_LAG_BYTES;
                default -> CauseQuery.APP_CPU;
            };
            if (single.containsKey(q)) {
                return q;
            }
        }
        return single.containsKey(CauseQuery.APP_CPU) ? CauseQuery.APP_CPU : null;
    }

    private void alerts(AiEvidence ev, Map<String, Map<String, Object>> items) {
        int unknown = 0;
        int n = 0;
        for (AiEvidence.Alert a : ev.alerts()) {
            if (n++ >= MAX_ALERTS) {
                break;
            }
            String id = a.rejected().contains("fingerprint") ? "alert.unknown-" + (++unknown) : "alert." + a.fingerprint();
            Map<String, Object> item = item(id, "alert", "경보 " + a.displayName());
            item.put("alertname", a.alertname());
            item.put("severity", a.severity());
            meanings.describe(item, a.alertname());
            a.labels().forEach(item::put);
            if (!a.rejected().isEmpty()) {
                item.put("rejected", a.rejected());
            }
            item.put("starts_at", ApiFormats.time(a.startsAt()));
            item.put("silenced", a.silenced());
            items.put(id, item);
        }
    }

    private void signals(Map<CauseQuery, Map<Long, Double>> single, CauseQuery target, long start, long end, long step,
                         long anchorAt, Map<String, Map<String, Object>> items) {
        Map<Long, Double> targetSeries = target == null ? null : single.get(target);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<CauseQuery, Map<Long, Double>> e : single.entrySet()) {
            CauseQuery q = e.getKey();
            Map<String, Object> item = item(q.id(), "signal", q.label);
            item.put("unit", q.unit.wire);
            put(item, stats(e.getValue(), start, end, step, anchorAt), q.unit);
            item.put("corr", q == target || targetSeries == null ? null
                    : LoadPattern.decimal(LoadPattern.pearson(e.getValue(), targetSeries, start, end, step, MIN_PAIRS), 2));
            rows.add(item);
        }
        // 대상 지표를 먼저, 그다음 상관이 큰 순으로 싣는다. 모델이 먼저 읽는 순서다.
        rows.sort(Comparator.comparing((Map<String, Object> m) -> !m.get("id").equals(target == null ? null : target.id()))
                .thenComparing(m -> m.get("corr") == null ? 2 : -Math.abs(Double.parseDouble((String) m.get("corr")))));
        rows.forEach(r -> items.put((String) r.get("id"), r));
    }

    private void nodes(PrometheusClient.Ranges<CauseQuery> ranges, Map<Long, Double> targetSeries, long start,
                       long end, long step, long anchorAt, Map<String, Map<String, Object>> items) {
        List<PrometheusClient.RangeSeries> series = ranges == null ? null : ranges.results().get(CauseQuery.NODE_CPU);
        if (series == null) {
            return;
        }
        series.stream().filter(s -> s.labels().get("node") != null)
                .sorted(Comparator.comparing(s -> s.labels().get("node")))
                .forEach(s -> {
                    String node = s.labels().get("node");
                    Map<String, Object> item = item("node." + node, "node", node + " CPU(1분 평균)");
                    item.put("unit", CauseQuery.Unit.RATIO.wire);
                    put(item, stats(s.values(), start, end, step, anchorAt), CauseQuery.Unit.RATIO);
                    item.put("corr", targetSeries == null ? null : LoadPattern.decimal(
                            LoadPattern.pearson(s.values(), targetSeries, start, end, step, MIN_PAIRS), 2));
                    items.put((String) item.get("id"), item);
                });
    }

    /** 요청이 많은 경로 다섯. 앞뒤 구간의 요청률과 전체에서의 비중을 함께 싣는다(분포 변화). */
    private void routes(PrometheusClient.Ranges<CauseQuery> ranges, long start, long end, long step, long anchorAt,
                        Map<String, Map<String, Object>> items) {
        List<PrometheusClient.RangeSeries> series = ranges == null ? null : ranges.results().get(CauseQuery.ROUTE_RATE);
        if (series == null || series.isEmpty()) {
            return;
        }
        record Route(String method, String uri, Stats stats) {
        }
        List<Route> all = new ArrayList<>();
        double totalBefore = 0;
        double totalValue = 0;
        for (PrometheusClient.RangeSeries s : series) {
            Stats st = stats(s.values(), start, end, step, anchorAt);
            all.add(new Route(s.labels().getOrDefault("method", "?"), s.labels().getOrDefault("uri", "?"), st));
            totalBefore += st.before() == null ? 0 : st.before();
            totalValue += st.value() == null ? 0 : st.value();
        }
        all.sort(Comparator.comparing((Route r) -> -Math.max(nz(r.stats().value()), nz(r.stats().before()))));
        int rank = 0;
        for (Route r : all.subList(0, Math.min(TOP_ROUTES, all.size()))) {
            String id = "route." + (++rank);
            Map<String, Object> item = item(id, "route", r.method() + " " + r.uri() + " 요청률");
            item.put("method", r.method());
            item.put("uri", r.uri());
            item.put("unit", CauseQuery.Unit.PER_SECOND.wire);
            put(item, r.stats(), CauseQuery.Unit.PER_SECOND);
            item.put("share_before", totalBefore > 0 && r.stats().before() != null
                    ? LoadPattern.decimal(r.stats().before() / totalBefore, 2) : null);
            item.put("share_value", totalValue > 0 && r.stats().value() != null
                    ? LoadPattern.decimal(r.stats().value() / totalValue, 2) : null);
            items.put(id, item);
        }
    }

    /**
     * 운영 이벤트. 감사 기록의 대수·봇·임계값 변경과, Prometheus 계열에서 읽은 앱 노드 수집 끊김·복귀, 매칭 리더 세대 변경,
     * DB 접속 끊김·복귀와 역할 바뀜이다. 창 안의 가장 최근 12건을 시각 순으로 싣는다.
     */
    private void events(PrometheusClient.Ranges<CauseQuery> ranges, long start, long end, long step,
                        Map<String, Map<String, Object>> items) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (ranges != null) {
            // instance 라벨은 수집 주소(사설 주소와 포트)라 노드 이름으로 쓰지 않는다(판단 근거와 같은 규칙, 설계 7-2). node 라벨로
            // 잇지 못하면 노드를 모르는 것으로 적는다. instance 는 이 방법 안에서 두 계열을 잇는 열쇠로만 쓴다.
            Map<String, String> nodeOf = new LinkedHashMap<>();
            for (PrometheusClient.RangeSeries s : ranges.results().getOrDefault(CauseQuery.APP_UP, List.of())) {
                String node = s.labels().get("node");
                if (node != null && s.labels().get("instance") != null) {
                    nodeOf.put(s.labels().get("instance"), node);
                }
                flips(s.values(), start, end, step, node, "APP_DOWN", "APP_UP", "수집 끊김", "수집 복귀", true, events);
            }
            for (PrometheusClient.RangeSeries s : ranges.results().getOrDefault(CauseQuery.LEADER_EPOCH, List.of())) {
                String instance = s.labels().get("instance");
                String node = instance == null ? null : nodeOf.get(instance);
                Double prev = null;
                for (Map.Entry<Long, Double> p : new java.util.TreeMap<>(s.values()).entrySet()) {
                    if (prev != null && p.getValue() > prev) {
                        events.add(event(p.getKey(), "LEADER_EPOCH", node, fmt(prev), fmt(p.getValue()),
                                "매칭 리더 세대 " + fmt(prev) + "→" + fmt(p.getValue()) + "(" + who(node) + ")"));
                    }
                    prev = p.getValue();
                }
            }
            for (PrometheusClient.RangeSeries s : ranges.results().getOrDefault(CauseQuery.DB_UP, List.of())) {
                flips(s.values(), start, end, step, s.labels().get("node"), "DB_DOWN", "DB_UP", "DB 접속 끊김",
                        "DB 접속 복귀", false, events);
            }
            for (PrometheusClient.RangeSeries s : ranges.results().getOrDefault(CauseQuery.DB_RECOVERY, List.of())) {
                // recovery 1 은 Standby, 0 은 Primary 다(ADR 0032).
                flips(s.values(), start, end, step, s.labels().get("node"), "DB_PROMOTED", "DB_STANDBY",
                        "Standby→Primary 승격", "Primary→Standby", false, events);
            }
        }
        for (AuditEvent a : audit.apply(Instant.ofEpochSecond(start))) {
            events.add(auditEvent(a));
        }
        events.sort(Comparator.comparing(m -> (String) m.get("at")));
        List<Map<String, Object>> kept = events.subList(Math.max(0, events.size() - MAX_EVENTS), events.size());
        int n = 0;
        for (Map<String, Object> e : kept) {
            String id = "event." + (++n);
            Map<String, Object> item = item(id, "event", (String) e.remove("label"));
            item.putAll(e);
            items.put(id, item);
        }
    }

    /**
     * 0·1 계열의 바뀜. 1 에서 0 은 down, 0 에서 1 은 up 이다. appear 면 창이 열린 뒤 처음 나타난 계열(app-3 켜짐)과 창이
     * 닫히기 전에 사라진 계열도 적는다.
     */
    private static void flips(Map<Long, Double> values, long start, long end, long step, String node, String down,
                              String up, String downWord, String upWord, boolean appear,
                              List<Map<String, Object>> events) {
        java.util.TreeMap<Long, Double> sorted = new java.util.TreeMap<>(values);
        if (sorted.isEmpty()) {
            return;
        }
        String who = who(node) + " ";
        if (appear && sorted.firstKey() > start + 2 * step && sorted.firstEntry().getValue() > 0) {
            events.add(event(sorted.firstKey(), "APP_APPEARED", node, null, null, who + "수집 시작"));
        }
        Double prev = null;
        for (Map.Entry<Long, Double> p : sorted.entrySet()) {
            boolean on = p.getValue() > 0;
            if (prev != null && (prev > 0) != on) {
                events.add(event(p.getKey(), on ? up : down, node, null, null, who + (on ? upWord : downWord)));
            }
            prev = p.getValue();
        }
        if (appear && sorted.lastKey() < end - 2 * step) {
            events.add(event(sorted.lastKey(), "APP_GONE", node, null, null, who + "수집 대상에서 빠짐"));
        }
    }

    /** 이벤트 문구의 노드 이름. 모르면 주소 대신 '노드 미확인'이다. */
    private static String who(String node) {
        return node == null ? "노드 미확인" : node;
    }

    private static Map<String, Object> event(long at, String code, String node, String from, String to, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("at", ApiFormats.time(Instant.ofEpochSecond(at)));
        m.put("code", code);
        m.put("node", node);
        m.put("from", from);
        m.put("to", to);
        return m;
    }

    private static Map<String, Object> auditEvent(AuditEvent a) {
        String actorWord = switch (a.actor()) {
            case "AI" -> "AI";
            case "SYSTEM" -> "시스템";
            default -> "운영자";
        };
        Object from = a.detail().get("from");
        Object to = a.detail().get("to");
        String code;
        String label;
        switch (a.action()) {
            case "capacity.scale_out", "capacity.scale_in" -> {
                code = a.action().endsWith("out") ? "SCALE_OUT" : "SCALE_IN";
                label = "앱 계층 희망 대수 " + from + "→" + to + "(" + actorWord + ")";
            }
            case "sim.rate" -> {
                code = "BOT_RATE";
                to = a.detail().get("target_ops");
                label = "봇 목표 발행률 → " + to + "(" + actorWord + ")";
            }
            case "sim.scenario" -> {
                code = "BOT_SCENARIO";
                to = a.detail().get("target_ops");
                label = "봇 시나리오 " + a.detail().getOrDefault("scenario", "?") + (to == null ? "" : " · 목표 " + to)
                        + "(" + actorWord + ")";
            }
            default -> {
                code = a.action().endsWith("revert") ? "THRESHOLD_REVERT" : "THRESHOLD_SET";
                label = "임계값 " + a.target() + " " + from + "→" + to + "(" + actorWord + ")";
            }
        }
        Map<String, Object> m = event(a.at().getEpochSecond(), code, null, from == null ? null : String.valueOf(from),
                to == null ? null : String.valueOf(to), label);
        m.put("at", ApiFormats.time(a.at()));
        m.put("actor", a.actor());
        if (code.startsWith("THRESHOLD")) {
            m.put("key", a.target());
        }
        return m;
    }

    /** 오류 로그 묶음. 로그 문장은 싣지 않고 등급·클래스 이름·건수·시각·노드만 싣는다(신뢰 경계). */
    private void logs(Map<String, Map<String, Object>> items) {
        ErrorLogPoller.Digest d = logs == null ? null : logs.get();
        if (d == null || d.polledAt() == null) {
            return;
        }
        int n = 0;
        for (ErrorLogPoller.Group g : d.groups()) {
            if (n++ >= MAX_LOGS) {
                break;
            }
            String name = g.exception() != null ? g.exception() : g.logger() != null ? g.logger() : "이름 없는 묶음";
            Map<String, Object> item = item("log." + g.id(), "log", g.level() + " " + name
                    + (g.exception() != null && g.logger() != null ? " · " + g.logger() : ""));
            item.put("level", g.level());
            item.put("logger", g.logger());
            item.put("exception", g.exception());
            item.put("frame", g.frame());
            item.put("count", g.count());
            item.put("first_seen", ApiFormats.time(g.firstSeen()));
            item.put("last_seen", ApiFormats.time(g.lastSeen()));
            item.put("nodes", g.nodes());
            items.put((String) item.get("id"), item);
        }
    }

    private static void bot(AiEvidence ev, Map<String, Map<String, Object>> items) {
        if (ev.bot().targetOps() == null) {
            return;
        }
        Map<String, Object> item = item("bot", "bot", "봇 목표 발행률");
        item.put("unit", CauseQuery.Unit.PER_SECOND.wire);
        item.put("value", LoadPattern.decimal(ev.bot().targetOps(), 2));
        item.put("source", ev.bot().source());
        item.put("sampled_at", ev.bot().sampledAt() == null ? null : ApiFormats.time(ev.bot().sampledAt()));
        items.put("bot", item);
    }

    /* ===== 계산 ===== */

    private static Stats stats(Map<Long, Double> values, long start, long end, long step, long anchorAt) {
        double sb = 0;
        int nb = 0;
        double sv = 0;
        int nv = 0;
        Double now = null;
        Long nowAt = null;
        Double peak = null;
        Long peakAt = null;
        for (long t = start; t <= end; t += step) {
            Double v = values.get(t);
            if (v == null || !Double.isFinite(v)) {
                continue;
            }
            if (t < anchorAt) {
                sb += v;
                nb++;
            } else {
                sv += v;
                nv++;
                if (peak == null || v > peak) {
                    peak = v;
                    peakAt = t;
                }
            }
            now = v;
            nowAt = t;
        }
        return new Stats(nb == 0 ? null : sb / nb, nv == 0 ? null : sv / nv, now,
                nowAt == null ? null : Instant.ofEpochSecond(nowAt), peak, peakAt == null ? null : Instant.ofEpochSecond(peakAt));
    }

    private static void put(Map<String, Object> item, Stats s, CauseQuery.Unit unit) {
        item.put("before", number(s.before(), unit));
        item.put("value", number(s.value(), unit));
        item.put("now", number(s.now(), unit));
        item.put("at", s.nowAt() == null ? null : ApiFormats.time(s.nowAt()));
        Double change = s.before() == null || s.value() == null ? null : s.value() - s.before();
        item.put("change", number(change, unit));
        item.put("change_ratio", change == null || s.before() == 0 ? null
                : LoadPattern.decimal(change / Math.abs(s.before()), 2));
        item.put("peak", number(s.peak(), unit));
        item.put("peak_at", s.peakAt() == null ? null : ApiFormats.time(s.peakAt()));
    }

    static String number(Double value, CauseQuery.Unit unit) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return ApiFormats.amount(BigDecimal.valueOf(value).setScale(unit.scale, RoundingMode.HALF_UP));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static Map<String, Object> item(String id, String kind, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("label", label);
        return m;
    }

    private static Map<String, Object> missing(String id, String label, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("reason", reason);
        return m;
    }

    /**
     * 인용 하나(API 설계 §6-F). 모델이 인용한 id 에 서버가 묶음의 이름, 단위, 값, 이전 값, 변화, 시각을 붙인다. 화면은 모델
     * 문장 속 숫자가 아니라 이 값을 근거로 보인다.
     */
    static Map<String, Object> citation(Map<String, Object> item) {
        Map<String, Object> c = new LinkedHashMap<>();
        String kind = (String) item.get("kind");
        c.put("id", item.get("id"));
        c.put("kind", kind);
        c.put("label", item.get("label"));
        c.put("unit", item.get("unit"));
        switch (kind) {
            case "alert" -> {
                c.put("value", item.get("severity"));
                c.put("at", item.get("starts_at"));
            }
            case "event" -> {
                Object from = item.get("from");
                Object to = item.get("to");
                c.put("value", from == null && to == null ? null : (from == null ? "" : from) + "→" + (to == null ? "" : to));
                c.put("at", item.get("at"));
            }
            case "log" -> {
                c.put("unit", "count");
                c.put("value", String.valueOf(item.get("count")));
                c.put("at", item.get("last_seen"));
            }
            case "load" -> {
                c.put("value", item.get("pattern"));
                c.put("at", item.get("sampled_at"));
            }
            case "bot" -> {
                c.put("value", item.get("value"));
                c.put("at", item.get("sampled_at"));
            }
            default -> {
                c.put("value", item.get("value"));
                c.put("before", item.get("before"));
                c.put("change", item.get("change"));
                c.put("now", item.get("now"));
                c.put("at", item.get("at"));
            }
        }
        return c;
    }
}
