package kr.exchange.admin.msp;

/**
 * 서비스 흐름·지표 관제가 그리는 최근 150초의 명명 range 질의(API 설계 §6-F '흐름'). MspQuery 와 같이 요청의 어떤 값도
 * PromQL 에 들어가지 않고, 끼우는 값은 MspProperties.validate 가 좁힌 job 선택자뿐이다.
 */
enum FlowQuery {

    APP_UP(Need.APP),
    APP_CPU(Need.APP),
    APP_READS(Need.APP),
    APP_READS_REJECTED(Need.APP),
    APP_WRITES(Need.APP),
    APP_LEADER(Need.APP),
    APP_EPOCH(Need.APP),
    APP_WRITE_STATE(Need.APP),
    APP_LEASE_AGE(Need.APP),
    TRADES(Need.APP),
    ORDERS(Need.APP),
    DB_TARGET(Need.DB),
    DB_UP(Need.DB),
    DB_RECOVERY(Need.DB),
    DB_CONNECTIONS(Need.DB),
    DB_XACT(Need.DB),
    REPL_LAG_BYTES(Need.DB),
    REPL_MODE(Need.DB),
    PROMOTION(Need.PATRONI),
    PROBE(Need.PROBE);

    private enum Need { APP, DB, PROBE, PATRONI }

    // 로드밸런서가 조회 업스트림으로 보내는 경로(harness/nginx/nginx.conf). 그 밖의 업무 요청은 쓰기 업스트림으로 간다.
    static final String READ_PATHS = "/api/v1/(markets|trades|candles).*";
    // 봇 발행률(OpsGovernor)이 세는 호출은 주문과 취소다.
    static final String ORDER_PATHS = "/api/v1/orders.*";

    private final Need need;

    FlowQuery(Need need) {
        this.need = need;
    }

    boolean available(MspProperties.Prometheus jobs) {
        return switch (need) {
            case APP -> !jobs.getAppJob().isEmpty();
            case DB -> !jobs.getDbJob().isEmpty();
            case PROBE -> !jobs.getProbeJob().isEmpty();
            case PATRONI -> !jobs.getPatroniJob().isEmpty();
        };
    }

    String promql(MspProperties.Prometheus jobs) {
        String app = "job=~\"" + jobs.getAppJob() + "\"";
        String db = "job=~\"" + jobs.getDbJob() + "\"";
        String requests = "http_server_requests_seconds_count{" + app;
        // 떠 있는 앱 노드에 요청이 없으면 계열이 없어 미수집으로 읽힌다. 떠 있는 동안의 무요청은 0 이다.
        String upZero = "0 * max by(instance) (up{" + app + "} == 1)";
        String anyUpZero = "0 * max(up{" + app + "} == 1)";
        return switch (this) {
            case APP_UP -> "up{" + app + "}";
            case APP_CPU -> "max by(instance) (process_cpu_usage{" + app + "})";
            // 조회 경로 요청을 수용(429 아닌 응답)과 거절(앱의 요청 제한이 낸 429)로 나눈다. 창은 2초 수집 간격의 다섯 배인
            // 10초라 CPU(2초마다 읽는 순간값)와 같은 박자로 움직이고, 부하가 멈추면 10초 안에 0 으로 내려간다.
            case APP_READS -> "sum by(instance) (rate(" + requests + ",uri=~\"" + READ_PATHS + "\",status!=\"429\"}[10s]))"
                    + " or on(instance) (" + upZero + ")";
            case APP_READS_REJECTED -> "sum by(instance) (rate(" + requests + ",uri=~\"" + READ_PATHS
                    + "\",status=\"429\"}[10s])) or on(instance) (" + upZero + ")";
            case APP_WRITES -> "sum by(instance) (rate(" + requests + ",uri!~\"/actuator.*|" + READ_PATHS + "\"}[10s]))"
                    + " or on(instance) (" + upZero + ")";
            // 리더 지표는 앱이 붙인 node 라벨(파티션 이름)이 대상 라벨을 가리므로 instance 로 묶는다.
            case APP_LEADER -> "max by(instance) (exchange_leader_is_leader{" + app + "})";
            case APP_EPOCH -> "max by(instance) (exchange_leader_epoch{" + app + "})";
            case APP_WRITE_STATE -> "max by(instance, state) (exchange_write_availability_state{" + app + "} == 1)";
            // 리스를 쥐지 않은 노드는 -1 을 낸다.
            case APP_LEASE_AGE -> "max by(instance) (exchange_leader_lease_last_renew_age_seconds{" + app + "} >= 0)";
            case TRADES -> "sum(rate(exchange_trades_total{" + app + "}[10s])) or on() (" + anyUpZero + ")";
            case ORDERS -> "sum(rate(" + requests + ",uri=~\"" + ORDER_PATHS + "\",method=~\"POST|DELETE\"}[10s]))"
                    + " or on() (" + anyUpZero + ")";
            case DB_TARGET -> "max by(node) (up{" + db + "})";
            case DB_UP -> "max by(node) (pg_up{" + db + "})";
            case DB_RECOVERY -> "max by(node) (pg_wal_position_in_recovery{" + db + "})";
            case DB_CONNECTIONS -> "sum by(node) (pg_stat_database_numbackends{" + db + "})";
            case DB_XACT -> "sum by(node) (rate(pg_stat_database_xact_commit{" + db + "}[15s]))";
            case REPL_LAG_BYTES -> "max(pg_replication_client_sent_replay_bytes{" + db + "})";
            // 복제 방식과 승격 방식은 관제 판과 같은 식이다(MspQuery.replicationMode·promotion)
            case REPL_MODE -> MspQuery.replicationMode(db);
            case PROMOTION -> MspQuery.promotion("job=~\"" + jobs.getPatroniJob() + "\"");
            case PROBE -> "min by(service) (probe_success{job=~\"" + jobs.getProbeJob() + "\"})";
        };
    }
}
