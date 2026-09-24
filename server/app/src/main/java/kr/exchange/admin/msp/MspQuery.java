package kr.exchange.admin.msp;

import java.util.ArrayList;
import java.util.List;

/**
 * 관제센터가 Prometheus 에 던지는 질의 전부(설계 4-1). API 요청의 어떤 값도 PromQL 에 들어가지 않는다.
 * 끼우는 값은 설정의 job 선택자뿐이고, 그 모양은 MspProperties.validate 가 좁힌다. 여러 시계열을 합치는 요약 값은
 * Prometheus 기록 규칙이 미리 계산하고 여기서는 그 이름만 질의한다(설계 4-6). 규칙은 학교 school-alerts.yml 과
 * 하네스 exchange-recording.yml 에 같은 이름으로 있다.
 */
enum MspQuery {

    SCRAPE_AGE(Need.ANY),
    TARGET_UP(Need.ANY),
    HOST_CPU_RATIO(Need.HOST),
    HOST_MEMORY_RATIO(Need.HOST),
    HOST_ROOT_FREE_RATIO(Need.HOST),
    HTTP_REQUEST_RATE(Need.APP),
    HTTP_5XX_RATIO(Need.APP),
    HTTP_P95_SECONDS(Need.APP),
    PROBE_SUCCESS_RATIO(Need.PROBE),
    DB_IN_RECOVERY(Need.DB),
    DB_UP(Need.DB),
    REPLICATION_LAG_SECONDS(Need.DB),
    REPLICATION_LAG_BYTES(Need.DB),
    BACKUP_LAST_SUCCESS(Need.HOST),
    BACKUP_VERIFY_OK(Need.HOST),
    DAILY_REPORT_LAST_TRIGGER(Need.HOST),
    THRESHOLD_GAUGE_COUNT(Need.NONE),
    THRESHOLD_RULE_VALUE(Need.NONE);

    private enum Need { NONE, ANY, HOST, APP, DB, PROBE }

    static final String RULE_HTTP_REQUESTS = "exchange:msp_http_requests:rate5m";
    static final String RULE_HTTP_5XX = "exchange:msp_http_5xx:ratio5m";
    static final String RULE_HTTP_P95 = "exchange:msp_http_p95:seconds5m";
    static final String RULE_PROBE_SUCCESS = "exchange:msp_probe_success:ratio5m";

    private final Need need;

    MspQuery(Need need) {
        this.need = need;
    }

    /** 요약 추세(range 질의)에 쓸 수 있는 계열. 기록 규칙 이름 하나라 range 로 읽어도 원천 부하가 작다. */
    boolean trend() {
        return this == HTTP_5XX_RATIO || this == HTTP_P95_SECONDS || this == PROBE_SUCCESS_RATIO;
    }

    /** 필요한 job 선택자가 비어 있으면 질의하지 않는다. 그 칸은 연결 전이다. */
    boolean available(MspProperties.Prometheus jobs) {
        return switch (need) {
            case NONE -> true;
            case ANY -> !allJobs(jobs).isEmpty();
            case HOST -> !jobs.getHostJobs().isEmpty();
            case APP -> !jobs.getAppJob().isEmpty();
            case DB -> !jobs.getDbJob().isEmpty();
            case PROBE -> !jobs.getProbeJob().isEmpty();
        };
    }

    String promql(MspProperties.Prometheus jobs) {
        String host = "job=~\"" + jobs.getHostJobs() + "\"";
        String db = "job=\"" + jobs.getDbJob() + "\"";
        String rootFs = host + ",mountpoint=\"/\",fstype!~\"tmpfs|overlay|squashfs\"";
        // 대상이 내려가 있으면 5분 창의 옛 표본으로 지금 값을 만들지 않는다.
        String hostUp = " and on(node) (max by(node) (up{" + host + "}) == 1)";
        return switch (this) {
            case SCRAPE_AGE -> "time() - timestamp(up{job=~\"" + allJobs(jobs) + "\"})";
            case TARGET_UP -> "up{job=~\"" + allJobs(jobs) + "\"}";
            case HOST_CPU_RATIO -> "(1 - avg by(node) (rate(node_cpu_seconds_total{" + host
                    + ",mode=\"idle\"}[5m])))" + hostUp;
            case HOST_MEMORY_RATIO -> "max by(node) (1 - node_memory_MemAvailable_bytes{" + host
                    + "} / node_memory_MemTotal_bytes{" + host + "})" + hostUp;
            case HOST_ROOT_FREE_RATIO -> "min by(node) (node_filesystem_avail_bytes{" + rootFs
                    + "} / node_filesystem_size_bytes{" + rootFs + "})" + hostUp;
            case HTTP_REQUEST_RATE -> RULE_HTTP_REQUESTS;
            case HTTP_5XX_RATIO -> RULE_HTTP_5XX;
            case HTTP_P95_SECONDS -> RULE_HTTP_P95;
            case PROBE_SUCCESS_RATIO -> RULE_PROBE_SUCCESS;
            case DB_IN_RECOVERY -> "max by(node, role) (pg_wal_position_in_recovery{" + db + "})";
            case DB_UP -> "max by(node) (pg_up{" + db + "})";
            case REPLICATION_LAG_SECONDS -> "max by(node, application_name, state) "
                    + "(pg_replication_client_replay_lag_seconds{" + db + "})";
            case REPLICATION_LAG_BYTES -> "max by(node, application_name, state) "
                    + "(pg_replication_client_sent_replay_bytes{" + db + "})";
            case BACKUP_LAST_SUCCESS -> "max(failstop_db_backup_last_success_timestamp_seconds{" + host + "})";
            case BACKUP_VERIFY_OK -> "min(failstop_db_backup_last_verify_success{" + host + "})";
            case DAILY_REPORT_LAST_TRIGGER -> "max(node_systemd_timer_last_trigger_seconds{" + host
                    + ",name=\"failstop-reporting-daily.timer\"})";
            case THRESHOLD_GAUGE_COUNT -> "count(" + OpsThresholdService.GAUGE + ")";
            // 경보 규칙이 지금 쓰는 값이다. 규칙과 같은 식이라 게이지가 끊긴 뒤에도 마지막 값을 7일 본다.
            case THRESHOLD_RULE_VALUE -> "max by(key) (last_over_time(" + OpsThresholdService.GAUGE + "[7d]))";
        };
    }

    static String allJobs(MspProperties.Prometheus jobs) {
        List<String> parts = new ArrayList<>();
        for (String job : List.of(jobs.getHostJobs(), jobs.getAppJob(), jobs.getDbJob(), jobs.getProbeJob())) {
            if (!job.isEmpty()) {
                parts.add(job);
            }
        }
        return String.join("|", parts);
    }
}
