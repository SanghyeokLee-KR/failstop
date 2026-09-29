package kr.exchange.admin.msp;

import java.util.Arrays;
import java.util.List;

/**
 * 원인 분석과 부하 판정이 던지는 명명 range 질의(API 설계 §6-F '원인 분석과 부하 판정'). MspQuery·FlowQuery 와 같이 요청의
 * 어떤 값도 PromQL 에 들어가지 않고, 끼우는 값은 MspProperties.validate 가 좁힌 job 선택자뿐이다. 신호(signal)가 있는 질의는
 * 계열 하나를 내는 근거 지표이고, 없는 질의는 노드·경로별 항목과 운영 이벤트를 만드는 묶음 계열이다.
 */
enum CauseQuery {

    APP_CPU("app_cpu", "앱 계층 CPU(1분 평균)", Unit.RATIO, Need.APP),
    HTTP_RATE("http_rate", "업무 요청률", Unit.PER_SECOND, Need.APP),
    HTTP_429("http_429", "요청 제한 거절(429)", Unit.PER_SECOND, Need.APP),
    HTTP_5XX("http_5xx", "5xx 비율", Unit.RATIO, Need.APP),
    HTTP_MEAN("http_mean", "평균 응답 시간", Unit.SECONDS, Need.APP),
    HTTP_P95("http_p95", "응답 p95", Unit.SECONDS, Need.APP),
    ORDER_P99("order_p99", "주문 처리 p99", Unit.SECONDS, Need.APP),
    GC_RATIO("gc_ratio", "GC 정지 시간 비율(가장 높은 노드)", Unit.RATIO, Need.APP),
    HEAP_AFTER_GC("heap_after_gc", "GC 뒤 힙 사용률(가장 높은 노드)", Unit.RATIO, Need.APP),
    DB_POOL_USE("db_pool_use", "DB 연결 풀 사용률(가장 높은 풀)", Unit.RATIO, Need.APP),
    DB_POOL_WAIT("db_pool_wait", "DB 연결 대기 스레드", Unit.COUNT, Need.APP),
    DB_ACQUIRE("db_acquire", "DB 연결 획득 평균 시간(가장 느린 풀)", Unit.SECONDS, Need.APP),
    ERROR_LOGS("error_logs", "ERROR 로그 발생률", Unit.PER_SECOND, Need.APP),
    WARN_LOGS("warn_logs", "WARN 로그 발생률", Unit.PER_SECOND, Need.APP),
    ORDER_RATE("order_rate", "주문·취소 요청률", Unit.PER_SECOND, Need.APP),
    TRADE_RATE("trade_rate", "체결률", Unit.PER_SECOND, Need.APP),
    SEQ_QUEUE("seq_queue", "시퀀서 큐 길이(가장 긴 노드)", Unit.COUNT, Need.APP),
    DB_CONNECTIONS("db_connections", "DB 연결 수", Unit.COUNT, Need.DB),
    DB_COMMITS("db_commits", "DB 커밋률", Unit.PER_SECOND, Need.DB),
    REPL_LAG_BYTES("repl_lag_bytes", "복제 지연(바이트)", Unit.BYTES, Need.DB),
    HOST_CPU("host_cpu", "호스트 CPU(가장 높은 노드)", Unit.RATIO, Need.HOST),
    NODE_CPU(null, "앱 노드 CPU(1분 평균)", Unit.RATIO, Need.APP),
    ROUTE_RATE(null, "경로별 요청률", Unit.PER_SECOND, Need.APP),
    APP_UP(null, "앱 노드 수집", Unit.COUNT, Need.APP),
    LEADER_EPOCH(null, "매칭 리더 세대", Unit.COUNT, Need.APP),
    DB_UP(null, "DB 접속", Unit.COUNT, Need.DB),
    DB_RECOVERY(null, "DB 역할(recovery)", Unit.COUNT, Need.DB);

    /** 응답의 단위 이름과 십진 문자열의 소수 자릿수. */
    enum Unit {
        RATIO("ratio", 4), SECONDS("seconds", 4), PER_SECOND("per_second", 2), BYTES("bytes", 0), COUNT("count", 1);

        final String wire;
        final int scale;

        Unit(String wire, int scale) {
            this.wire = wire;
            this.scale = scale;
        }
    }

    private enum Need { APP, DB, HOST }

    // 업무 요청. 관리·헬스 경로는 부하가 아니다.
    private static final String REQUESTS = "http_server_requests_seconds_count";
    private static final String NOT_ACTUATOR = ",uri!~\"/actuator.*\"";

    final String signal;
    final String label;
    final Unit unit;
    private final Need need;

    CauseQuery(String signal, String label, Unit unit, Need need) {
        this.signal = signal;
        this.label = label;
        this.unit = unit;
        this.need = need;
    }

    /** 근거 항목 id. 신호가 없는 묶음 계열은 null 이다. */
    String id() {
        return signal == null ? null : "signal." + signal;
    }

    static List<CauseQuery> signals() {
        return Arrays.stream(values()).filter(q -> q.signal != null).toList();
    }

    static CauseQuery bySignal(String signal) {
        for (CauseQuery q : values()) {
            if (q.signal != null && q.signal.equals(signal)) {
                return q;
            }
        }
        return null;
    }

    boolean available(MspProperties.Prometheus jobs) {
        return switch (need) {
            case APP -> !jobs.getAppJob().isEmpty();
            case DB -> !jobs.getDbJob().isEmpty();
            case HOST -> !jobs.getHostJobs().isEmpty();
        };
    }

    String promql(MspProperties.Prometheus jobs) {
        String app = "job=~\"" + jobs.getAppJob() + "\"";
        String db = "job=~\"" + jobs.getDbJob() + "\"";
        String host = "job=~\"" + jobs.getHostJobs() + "\"";
        String req = REQUESTS + "{" + app + NOT_ACTUATOR;
        String total = "sum(rate(" + req + "}[1m]))";
        // 요청이 있는 동안 해당 상태의 계열이 없으면 0 이다. 요청마저 없으면 결과가 없어 미수집으로 읽힌다.
        String zeroWhenServing = " or on() (0 * " + total + ")";
        return switch (this) {
            case APP_CPU -> "avg(avg_over_time(process_cpu_usage{" + app + "}[1m]))";
            case HTTP_RATE -> total;
            case HTTP_429 -> "sum(rate(" + req + ",status=\"429\"}[1m]))" + zeroWhenServing;
            case HTTP_5XX -> "(sum(rate(" + req + ",status=~\"5..\"}[1m]))" + zeroWhenServing + ") / (" + total + " > 0)";
            case HTTP_MEAN -> "sum(rate(http_server_requests_seconds_sum{" + app + NOT_ACTUATOR + "}[1m])) / ("
                    + total + " > 0)";
            case HTTP_P95 -> "histogram_quantile(0.95, sum by(le) (rate(http_server_requests_seconds_bucket{" + app
                    + NOT_ACTUATOR + "}[1m])))";
            case ORDER_P99 -> "histogram_quantile(0.99, sum by(le) (rate(exchange_order_latency_seconds_bucket{" + app
                    + "}[1m])))";
            case GC_RATIO -> "max(sum by(instance) (rate(jvm_gc_pause_seconds_sum{" + app + "}[1m])))";
            case HEAP_AFTER_GC -> "max(jvm_memory_usage_after_gc{" + app + ",area=\"heap\"})";
            case DB_POOL_USE -> "max(hikaricp_connections_active{" + app + "} / hikaricp_connections_max{" + app + "})";
            case DB_POOL_WAIT -> "sum(hikaricp_connections_pending{" + app + "})";
            case DB_ACQUIRE -> "max(rate(hikaricp_connections_acquire_seconds_sum{" + app
                    + "}[1m]) / (rate(hikaricp_connections_acquire_seconds_count{" + app + "}[1m]) > 0))";
            case ERROR_LOGS -> "sum(rate(logback_events_total{" + app + ",level=\"error\"}[1m]))";
            case WARN_LOGS -> "sum(rate(logback_events_total{" + app + ",level=\"warn\"}[1m]))";
            case ORDER_RATE -> "sum(rate(" + req + ",uri=~\"" + FlowQuery.ORDER_PATHS + "\",method=~\"POST|DELETE\"}[1m]))"
                    + zeroWhenServing;
            case TRADE_RATE -> "sum(rate(exchange_trades_total{" + app + "}[1m]))";
            case SEQ_QUEUE -> "max(exchange_sequencer_queue_size{" + app + "})";
            case DB_CONNECTIONS -> "sum(pg_stat_database_numbackends{" + db + "})";
            case DB_COMMITS -> "sum(rate(pg_stat_database_xact_commit{" + db + "}[1m]))";
            case REPL_LAG_BYTES -> "max(pg_replication_client_sent_replay_bytes{" + db + "})";
            case HOST_CPU -> "max(1 - avg by(node) (rate(node_cpu_seconds_total{" + host + ",mode=\"idle\"}[1m])))";
            case NODE_CPU -> "avg by(node) (avg_over_time(process_cpu_usage{" + app + "}[1m]))";
            case ROUTE_RATE -> "sum by(method, uri) (rate(" + req + "}[1m]))";
            // 리더 지표는 앱이 붙인 node 라벨(파티션 이름)이 대상 라벨을 가리므로 instance 로 묶는다(FlowQuery 와 같다).
            case APP_UP -> "max by(instance, node) (up{" + app + "})";
            case LEADER_EPOCH -> "max by(instance) (exchange_leader_epoch{" + app + "})";
            case DB_UP -> "max by(node) (pg_up{" + db + "})";
            case DB_RECOVERY -> "max by(node) (pg_wal_position_in_recovery{" + db + "})";
        };
    }
}
