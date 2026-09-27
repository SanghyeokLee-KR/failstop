"""Customer-scoped PromQL for the provisioned Grafana MSP dashboards."""

SCOPE = 'customer="$customer",environment="$environment",role=~"app|reader|data|standby|probe"'
RATE_INTERVAL = "$__rate_interval"
RANGE = "$__range"
FRESH_SECONDS = 90
JOBS = 'exchange-api|linux-host|postgres|blackbox-http'


def selector(job, extra=""):
    return SCOPE + ',job="' + job + '"' + ("," + extra if extra else "")


def raw(name, job, extra=""):
    return name + "{" + selector(job, extra) + "}"


def fresh_up(job):
    up = raw("up", job)
    return f"({up} and (time() - timestamp({up}) < {FRESH_SECONDS}))"


def healthy(expression, job):
    gate = fresh_up(job) + " == 1"
    if job == "postgres":
        gate = "(" + gate + ") and on(instance,job) (" + raw("pg_up", job) + " == 1)"
        gate += " and on(instance,job) (" + raw("pg_exporter_last_scrape_error", job) + " == 0)"
    return f"(({expression}) and on(instance,job) ({gate}))"


def value(name, job, extra=""):
    return healthy(raw(name, job, extra), job)


def rate(name, job, extra=""):
    return healthy("rate(" + raw(name, job, extra) + "[" + RATE_INTERVAL + "])", job)


APP, DB, HOST = "exchange-api", "postgres", "linux-host"
BUSINESS = 'uri!~"/actuator(/.*)?"'
HTTP = rate("http_server_requests_seconds_count", APP, BUSINESS)
RPS = "sum(" + HTTP + ")"
ERRORS = "sum(" + rate("http_server_requests_seconds_count", APP, BUSINESS + ',status=~"5.."') + ")"


def latency(quantile, per_instance=False):
    grouping = "le,node,instance" if per_instance else "le"
    buckets = rate("http_server_requests_seconds_bucket", APP, BUSINESS)
    expression = f"1000 * histogram_quantile({quantile}, sum by({grouping})({buckets}))"
    requests = "sum by(node,instance)(" + HTTP + ")" if per_instance else RPS
    matching = "on(node,instance)" if per_instance else "on()"
    return f"(({expression}) < +Inf) and {matching} ({requests} > 0)"


def jvm(extra=""):
    used = "sum by(node,instance)(" + value("jvm_memory_used_bytes", APP, extra) + ")"
    committed = "sum by(node,instance)(" + value("jvm_memory_committed_bytes", APP, extra) + ")"
    return f"100 * {used} / ({committed} > 0)"


def http_codes():
    # Literal replacements avoid Grafana interpreting Prometheus capture variables.
    groups = [
        'label_replace(' + rate("http_server_requests_seconds_count", APP, BUSINESS + f',status=~"{code}.."')
        + f',"code","{code}xx","status",".*")'
        for code in range(1, 6)
    ]
    return "sum by(code)(" + " or ".join(groups) + ")"


PG_SCOPE = 'datname="exchange"'
PG_CONNECTIONS = "sum by(node,instance)(" + value("pg_stat_activity_count", DB) + ")"
PG_MAXIMUM = "max by(node,instance)(" + value("pg_settings_max_connections", DB) + ")"
FILESYSTEMS = 'fstype!~"tmpfs|overlay|squashfs|9p|drvfs",mountpoint!~"/run.*|/var/lib/docker.*"'
ALL_UP = "up{" + SCOPE + ',job=~"' + JOBS + '"}'
FRESH_ALL_UP = f"({ALL_UP} and (time() - timestamp({ALL_UP}) < {FRESH_SECONDS}))"
ALERTS = "ALERTS{" + SCOPE + ',component!="reporting",alertstate="firing"}'
FRESH_ALERTS = f"({ALERTS} and (time() - timestamp({ALERTS}) < {FRESH_SECONDS}))"

Q = {
    "rps": RPS,
    "error_rate": f"100 * ({ERRORS} or (0 * {RPS})) / ({RPS} > 0)",
    "latency_mean": "1000 * sum(" + rate("http_server_requests_seconds_sum", APP, BUSINESS) + ") / (" + RPS + " > 0)",
    "latency_p95": latency(0.95),
    "latency_p99": latency(0.99),
    "app_up": fresh_up(APP),
    "app_serviceable": value("exchange_node_serviceable", APP),
    "probe_up": value("probe_success", "blackbox-http"),
    "db_up": "(" + raw("pg_up", DB) + ") and on(instance,job) (" + fresh_up(DB) + " == 1)",
    "db_recovery": value("pg_wal_position_in_recovery", DB),
    "db_connections": "sum by(node,instance)(" + value("pg_stat_database_numbackends", DB, PG_SCOPE) + ")",
    "db_utilization": f"100 * {PG_CONNECTIONS} / ({PG_MAXIMUM} > 0)",
    "wal_gap": value("pg_replication_client_sent_replay_bytes", DB),
    "replication_lag": value("pg_replication_client_replay_lag_seconds", DB),
    "app_cpu": "100 * " + value("process_cpu_usage", APP),
    "app_memory": jvm(),
    "jvm_heap": jvm('area="heap"'),
    "jvm_nonheap": jvm('area="nonheap"'),
    "gc_time": "100 * sum by(node,instance,gc)(" + rate("jvm_gc_pause_seconds_sum", APP) + ")",
    "threads": value("jvm_threads_live_threads", APP),
    "hikari_active": value("hikaricp_connections_active", APP),
    "hikari_max": value("hikaricp_connections_max", APP),
    "host_cpu": "100 * (1 - avg by(node,instance)(" + rate("node_cpu_seconds_total", HOST, 'mode="idle"') + "))",
    "host_memory": "100 * (1 - " + value("node_memory_MemAvailable_bytes", HOST) + " / " + value("node_memory_MemTotal_bytes", HOST) + ")",
    "host_disk": "100 * (1 - " + value("node_filesystem_avail_bytes", HOST, FILESYSTEMS) + " / " + value("node_filesystem_size_bytes", HOST, FILESYSTEMS) + ")",
    "host_inode": "100 * (1 - " + value("node_filesystem_files_free", HOST, FILESYSTEMS) + " / (" + value("node_filesystem_files", HOST, FILESYSTEMS) + " > 0))",
    "host_network_rx": rate("node_network_receive_bytes_total", HOST, 'device!="lo"'),
    "host_network_tx": rate("node_network_transmit_bytes_total", HOST, 'device!="lo"'),
    "host_io": "100 * " + rate("node_disk_io_time_seconds_total", HOST),
    "db_tps": rate("pg_stat_database_xact_commit", DB, PG_SCOPE) + " + " + rate("pg_stat_database_xact_rollback", DB, PG_SCOPE),
    "db_active": value("pg_stat_activity_count", DB, PG_SCOPE + ',state="active"'),
    "db_locks": "sum by(node,instance)(" + value("pg_locks_count", DB, PG_SCOPE) + ")",
    "db_tables": "topk(5,(" + value("pg_stat_user_tables_size_bytes", DB, PG_SCOPE) + ") and on(instance,job) (" + value("pg_wal_position_in_recovery", DB) + " == 0))",
    "wal_rate": "((" + rate("pg_wal_position_bytes", DB) + ") < +Inf) > -Inf",
    "backup_timestamp": value("failstop_backup_last_success_timestamp_seconds", HOST),
    "requests_by_uri": "topk(10,sum by(uri,method)(" + healthy("increase(" + raw("http_server_requests_seconds_count", APP, BUSINESS) + "[" + RANGE + "])", APP) + "))",
    "http_codes": http_codes(),
    "instance_rps": "sum by(node,instance)(" + HTTP + ")",
    "instance_latency": latency(0.95, True),
    "availability": FRESH_ALL_UP,
    "alerts": FRESH_ALERTS,
    "alert_count": "sum(" + FRESH_ALERTS + ") or (0 * count(" + FRESH_ALL_UP + "))",
    "target_count": "count(" + ALL_UP + ")",
    "collection_ratio": "100 * sum(" + FRESH_ALL_UP + ") / count(" + ALL_UP + ")",
    "host_count": "count(" + raw("up", HOST) + ")",
    "host_fresh_count": "count(" + fresh_up(HOST) + ")",
    "host_up_count": "sum(" + fresh_up(HOST) + ")",
}


def role_status(recovery):
    return "(" + Q["db_up"] + ") and on(instance,job) (" + Q["db_recovery"] + f" == {recovery})"


Q["primary_status"] = role_status(0)
Q["standby_status"] = role_status(1)
valid_recovery = "((" + Q["db_recovery"] + " == 0) or (" + Q["db_recovery"] + " == 1))"
complete_roles = "count(" + valid_recovery + ") == count(" + raw("up", DB) + ")"
for name, recovery in (("primary", 0), ("standby", 1)):
    Q[name + "_count"] = "(count(" + Q["db_recovery"] + f" == {recovery}) or (0 * count(" + valid_recovery + ")))"
# Two measured primaries are a failure even if another registered role is unknown.
Q["primary_topology_status"] = ("(0 * (" + Q["primary_count"] + " > 1)) or (("
                                + Q["primary_count"] + " == bool 1) and on() (" + complete_roles + "))")
Q["standby_topology_status"] = "(" + Q["standby_count"] + " >= bool 1) and on() (" + complete_roles + ")"
Q["primary_connections"] = "(" + Q["db_connections"] + ") and on(instance) (" + Q["db_recovery"] + " == 0)"
Q["standby_connections"] = "(" + Q["db_connections"] + ") and on(instance) (" + Q["db_recovery"] + " == 1)"
Q["primary_utilization"] = "(" + Q["db_utilization"] + ") and on(instance) (" + Q["db_recovery"] + " == 0)"
Q["standby_utilization"] = "(" + Q["db_utilization"] + ") and on(instance) (" + Q["db_recovery"] + " == 1)"
streaming = value("pg_replication_client_sent_replay_bytes", DB, 'state="streaming"')
Q["replication_status"] = "min((" + streaming + " >= bool 0) or (0 * " + Q["wal_gap"] + "))"
# 복제 방식. Primary 가 보는 pg_stat_replication.sync_state 실측이다. 동기(sync·quorum) 상대가 하나라도 있으면 2,
# 비동기 상대만 있으면 1, 복제 상대가 없으면 계열이 없다(미수집). 화면 문구는 msp_canvas.REPLICATION_MODE_MAPPING 이다
sync_clients = value("pg_replication_client_sent_replay_bytes", DB, 'sync_state=~"sync|quorum"')
async_clients = value("pg_replication_client_sent_replay_bytes", DB, 'sync_state="async"')
Q["replication_mode"] = "max((0 * " + sync_clients + " + 2) or (0 * " + async_clients + " + 1))"
Q["serviceable"] = Q["app_serviceable"]
Q["alerts_count"] = Q["alert_count"]
Q["instance_p95"] = Q["instance_latency"]


def complete_status(expression, job):
    observed = "min(" + expression + ")"
    complete = "count(" + expression + ") == count(" + raw("up", job) + ")"
    # Preserve a measured failure; an incomplete set of healthy samples is unknown.
    return f"({observed} == 0) or ({observed} and on() ({complete}))"


Q["app_status"] = complete_status(Q["app_serviceable"], APP)
Q["db_status"] = complete_status(Q["db_up"], DB)
Q["probe_status"] = complete_status(Q["probe_up"], "blackbox-http")


def endpoint_rate(status):
    selected = "sum by(uri,method)(" + rate("http_server_requests_seconds_count", APP,
        BUSINESS + ',status=~"' + status + '"') + ")"
    observed = "sum by(uri,method)(" + HTTP + ")"
    return "(" + selected + ") or (0 * " + observed + ")"


def successful_latency(per_endpoint=False):
    grouping = "le,uri,method" if per_endpoint else "le"
    by = " by(uri,method)" if per_endpoint else ""
    match = "on(uri,method)" if per_endpoint else "on()"
    successful = BUSINESS + ',status=~"2.."'
    buckets = rate("http_server_requests_seconds_bucket", APP, successful)
    count = "sum" + by + "(" + rate("http_server_requests_seconds_count", APP, successful) + ")"
    expression = "1000 * histogram_quantile(0.95,sum by(" + grouping + ")(" + buckets + "))"
    return "((" + expression + ") < +Inf) and " + match + " (" + count + " > 0)"


Q.update({
    "endpoint_success_rps": endpoint_rate("2.."),
    "endpoint_rejected_rps": endpoint_rate("429"),
    "success_rps": "sum(" + endpoint_rate("2..") + ")",
    "rate_limit_rps": "sum(" + endpoint_rate("429") + ")",
    "success_latency_p95": successful_latency(),
    "endpoint_success_latency_p95": successful_latency(True),
    "db_commit_tps": rate("pg_stat_database_xact_commit", DB, PG_SCOPE),
    "db_rollback_tps": rate("pg_stat_database_xact_rollback", DB, PG_SCOPE),
    "db_read_rows": rate("pg_stat_database_tup_returned", DB, PG_SCOPE),
    "db_changed_rows": " + ".join(rate("pg_stat_database_tup_" + kind, DB, PG_SCOPE)
                                   for kind in ("inserted", "updated", "deleted")),
    "db_temp_bytes": rate("pg_stat_database_temp_bytes", DB, PG_SCOPE),
    "db_deadlocks": rate("pg_stat_database_deadlocks", DB, PG_SCOPE),
    "sessions": value("pg_ops_sessions_count", DB, PG_SCOPE),
    "session_waits": value("pg_ops_session_waits_count", DB, PG_SCOPE),
    "blocked_sessions": value("pg_ops_activity_blocked_sessions", DB, PG_SCOPE),
    "longest_transaction_seconds": value("pg_ops_activity_longest_transaction_seconds", DB, PG_SCOPE),
    "longest_lock_wait_seconds": value("pg_ops_activity_longest_lock_wait_seconds", DB, PG_SCOPE),
    "oldest_idle_transaction_seconds": value("pg_ops_activity_oldest_idle_transaction_seconds", DB, PG_SCOPE),
    "sql_available": value("pg_ops_sql_collection_available", DB, PG_SCOPE),
    "wal_generation_rate": rate("pg_ops_wal_generated_bytes_total", DB),
    "wal_send_gap": value("pg_replication_client_current_sent_bytes", DB),
    "wal_flush_gap": value("pg_replication_client_write_flush_bytes", DB),
    "wal_replay_gap": value("pg_replication_client_flush_replay_bytes", DB),
})
hits = rate("pg_stat_database_blks_hit", DB, PG_SCOPE)
reads = rate("pg_stat_database_blks_read", DB, PG_SCOPE)
Q["db_cache_hit"] = "100 * (" + hits + ") / ((" + hits + " + " + reads + ") > 0)"
# Aggregate pg_stat_statements counters can decrease on entry eviction. Such windows
# cannot be interpreted as counter resets and therefore do not produce a call rate.
stable_sql = "(" + Q["sql_available"] + " == 1) and on(instance,job,datname) (" + healthy(
    "increase(" + raw("pg_ops_sql_tracking_dealloc_total", DB, PG_SCOPE) + "[" + RATE_INTERVAL + "]) == 0", DB
    ) + ") and on(instance,job,datname) (" + healthy(
    "changes(" + raw("pg_ops_sql_tracking_stats_reset_timestamp_seconds", DB, PG_SCOPE)
    + "[" + RATE_INTERVAL + "]) == 0", DB) + ")"
stable_command = " and on(instance,job,datname,command) (resets(" + raw(
    "pg_ops_sql_calls_total", DB, PG_SCOPE) + "[" + RATE_INTERVAL + "]) == 0)"
Q["sql_calls"] = "(" + rate("pg_ops_sql_calls_total", DB, PG_SCOPE) + ") and on(instance,job,datname) (" + stable_sql + ")" + stable_command
Q["sql_mean_ms"] = "1000 * (" + rate("pg_ops_sql_execution_seconds_total", DB, PG_SCOPE) + ") / ((" + Q["sql_calls"] + ") > 0)"
Q["sql_mean_ms"] = "(" + Q["sql_mean_ms"] + ") and on(instance,job,datname,command) (resets(" + raw(
    "pg_ops_sql_execution_seconds_total", DB, PG_SCOPE) + "[" + RATE_INTERVAL + "]) == 0)"
Q["sql_collection_status"] = complete_status(Q["sql_available"], DB)
Q["sql_calls_by_command"] = "sum by(command)(" + Q["sql_calls"] + ")"
# Weight only intervals with valid calls AND execution time on the same collector.
# A reset execution counter must not dilute the mean with calls from that node.
valid_sql_calls = "(" + Q["sql_calls"] + ") and on(instance,job,datname,command) (" + Q["sql_mean_ms"] + ")"
weighted_sql_time = "(" + Q["sql_mean_ms"] + ") * on(instance,job,datname,command) (" + valid_sql_calls + ")"
Q["sql_mean_by_command"] = "sum by(command)(" + weighted_sql_time + ") / (sum by(command)(" + valid_sql_calls + ") > 0)"
Q["sql_mean_overall"] = "sum(" + weighted_sql_time + ") / (sum(" + valid_sql_calls + ") > 0)"
sql_groups = 'label_replace(' + Q["sql_calls_by_command"] + ',"sql_group","기타","command",".*")'
sql_groups = 'label_replace(' + sql_groups + ',"sql_group","SELECT","command","SELECT")'
sql_groups = 'label_replace(' + sql_groups + ',"sql_group","변경","command","INSERT|UPDATE|DELETE|MERGE")'
Q["sql_calls_overview"] = "sum by(sql_group)(" + sql_groups + ")"
Q["session_totals"] = "sum by(session_state)(" + Q["sessions"] + ")"
# Aggregate before assigning group labels so distinct event types cannot collide.
Q["session_wait_totals"] = " or ".join(
    'label_replace(sum(' + value("pg_ops_session_waits_count", DB, PG_SCOPE + ',' + selector)
    + '),"wait_event_type","' + label + '","","")'
    for label, selector in (("Lock", 'wait_event_type="Lock"'),
                            ("IO", 'wait_event_type="IO"'),
                            ("Other", 'wait_event_type!~"Lock|IO"')))
Q["db_active"] = "sum by(node,instance)(" + value("pg_ops_sessions_count", DB,
    PG_SCOPE + ',session_state=~"active_no_wait|active_waiting"') + ")"
# Retain the old dictionary key for callers, with the corrected source definition.
Q["wal_rate"] = Q["wal_generation_rate"]

# These optional probes require explicit AWS registration. Missing probes remain empty.
SERVICE_PATH = {
    "edge": Q["probe_status"],
    "cloudfront": "min(" + value("probe_success", "blackbox-http", 'service="cloudfront",environment="aws"') + ")",
    "alb": "min(" + value("probe_success", "blackbox-http", 'service="alb",environment="aws"') + ")",
    "spring": Q["app_status"],
    "primary": Q["primary_topology_status"],
    "standby": Q["standby_topology_status"],
}
DB_REPLICATION = {
    "primary": SERVICE_PATH["primary"],
    "standby": SERVICE_PATH["standby"],
    "replication": Q["replication_status"],
    "replication_mode": Q["replication_mode"],
    "wal_gap": "max(" + Q["wal_gap"] + ")",
    "primary_connections": "sum(" + Q["primary_connections"] + ")",
    "standby_connections": "sum(" + Q["standby_connections"] + ")",
    "primary_utilization": "max(" + Q["primary_utilization"] + ")",
    "standby_utilization": "max(" + Q["standby_utilization"] + ")",
}
