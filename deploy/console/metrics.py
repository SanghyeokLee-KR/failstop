"""Read a bounded, customer-scoped set of operational Prometheus metrics."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import math
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


ROLES = {"app", "reader", "data", "standby", "probe"}
JOBS = {"exchange-api", "linux-host", "postgres", "blackbox-http"}
MAX_RANGE = 30 * 86400
MAX_POINTS = 600
MAX_SERIES = 24
MAX_TOTAL_POINTS = 60000
MAX_RESPONSE = 1024 * 1024
MAX_CACHE_BYTES = 12 * 1024 * 1024
MAX_CACHE_ENTRY_BYTES = 4 * 1024 * 1024
MAX_CACHE_ENTRIES = 8
IDENTIFIER = re.compile(r"[a-zA-Z0-9_.-]{1,80}")


class MetricsUnavailable(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def scope_labels(customer, environment, service="", instance=""):
    if not all(isinstance(value, str) and IDENTIFIER.fullmatch(value) for value in (customer, environment)):
        raise ValueError("invalid_customer_scope")
    for value in (service, instance):
        if not isinstance(value, str) or len(value) > 256 or any(ord(char) < 32 for char in value):
            raise ValueError("invalid_target_filter")
    labels = [f"customer={json.dumps(customer)}", f"environment={json.dumps(environment)}",
              'role=~"app|reader|data|standby|probe"']
    if service:
        labels.append("service=" + json.dumps(service))
    if instance:
        labels.append("instance=" + json.dumps(instance))
    return ",".join(labels)


def queries(customer, environment, range_seconds, service="", instance=""):
    scope = scope_labels(customer, environment, service, instance)

    def selector(job, extra=""):
        return scope + ',job="' + job + '"' + ("," + extra if extra else "")

    def raw(name, job, extra=""):
        return name + "{" + selector(job, extra) + "}"

    def fresh_up(job):
        up = raw("up", job)
        return f"({up} and (time() - timestamp({up}) < 90))"

    def healthy(expression, job):
        gate = fresh_up(job) + " == 1"
        if job == "postgres":
            gate = "(" + gate + ") and on(instance,job) (" + raw("pg_up", job) + " == 1)"
        return f"(({expression}) and on(instance,job) ({gate}))"

    def value(name, job, extra=""):
        return healthy(raw(name, job, extra), job)

    def rate(name, job, extra=""):
        return healthy("rate(" + raw(name, job, extra) + "[5m])", job)

    app, db, host = "exchange-api", "postgres", "linux-host"
    business = 'uri!~"/actuator(/.*)?"'
    http = rate("http_server_requests_seconds_count", app, business)
    rps = "sum(" + http + ")"
    errors = "sum(" + rate("http_server_requests_seconds_count", app, business + ',status=~"5.."') + ")"
    # An absent 5xx series can mean zero only when a business-request counter exists.
    error_rate = f"100 * ({errors} or (0 * {rps})) / ({rps} > 0)"

    def latency(quantile, per_instance=False):
        grouping = "le,node,instance" if per_instance else "le"
        buckets = rate("http_server_requests_seconds_bucket", app, business)
        expression = f"1000 * histogram_quantile({quantile}, sum by({grouping})({buckets}))"
        requests = "sum by(node,instance)(" + http + ")" if per_instance else rps
        matching = "on(node,instance)" if per_instance else "on()"
        return f"(({expression}) < +Inf) and {matching} ({requests} > 0)"

    def jvm(extra=""):
        used = "sum by(node,instance)(" + value("jvm_memory_used_bytes", app, extra) + ")"
        committed = "sum by(node,instance)(" + value("jvm_memory_committed_bytes", app, extra) + ")"
        return f"100 * {used} / ({committed} > 0)"

    pg_scope = 'datname="exchange"'
    pg_connections = "sum by(node,instance)(" + value("pg_stat_activity_count", db) + ")"
    pg_maximum = "max by(node,instance)(" + value("pg_settings_max_connections", db) + ")"
    fs = 'fstype!~"tmpfs|overlay|squashfs|9p|drvfs",mountpoint!~"/run.*|/var/lib/docker.*"'
    all_up = "up{" + scope + ',job=~"exchange-api|linux-host|postgres|blackbox-http"}'
    return {
        "rps": rps,
        "error_rate": error_rate,
        "latency_mean": "1000 * sum(" + rate("http_server_requests_seconds_sum", app, business) + ") / (" + rps + " > 0)",
        "latency_p95": latency(0.95),
        "latency_p99": latency(0.99),
        "app_up": fresh_up(app),
        "app_serviceable": value("exchange_node_serviceable", app),
        "probe_up": value("probe_success", "blackbox-http"),
        "db_up": "(" + raw("pg_up", db) + ") and on(instance,job) (" + fresh_up(db) + " == 1)",
        "db_recovery": value("pg_wal_position_in_recovery", db),
        "db_connections": "sum by(node,instance)(" + value("pg_stat_database_numbackends", db, pg_scope) + ")",
        "db_utilization": f"100 * {pg_connections} / ({pg_maximum} > 0)",
        "wal_gap": value("pg_replication_client_sent_replay_bytes", db),
        "replication_lag": value("pg_replication_client_replay_lag_seconds", db),
        "app_cpu": "100 * " + value("process_cpu_usage", app),
        "app_memory": jvm(),
        "jvm_heap": jvm('area="heap"'),
        "jvm_nonheap": jvm('area="nonheap"'),
        "gc_time": "100 * sum by(node,instance,gc)(" + rate("jvm_gc_pause_seconds_sum", app) + ")",
        "threads": value("jvm_threads_live_threads", app),
        "hikari_active": value("hikaricp_connections_active", app),
        "hikari_max": value("hikaricp_connections_max", app),
        "host_cpu": "100 * (1 - avg by(node,instance)(" + rate("node_cpu_seconds_total", host, 'mode="idle"') + "))",
        "host_memory": "100 * (1 - " + value("node_memory_MemAvailable_bytes", host) + " / " + value("node_memory_MemTotal_bytes", host) + ")",
        "host_disk": "100 * (1 - " + value("node_filesystem_avail_bytes", host, fs) + " / " + value("node_filesystem_size_bytes", host, fs) + ")",
        "host_inode": "100 * (1 - " + value("node_filesystem_files_free", host, fs) + " / (" + value("node_filesystem_files", host, fs) + " > 0))",
        "host_network_rx": rate("node_network_receive_bytes_total", host, 'device!="lo"'),
        "host_network_tx": rate("node_network_transmit_bytes_total", host, 'device!="lo"'),
        "host_io": "100 * " + rate("node_disk_io_time_seconds_total", host),
        "db_tps": rate("pg_stat_database_xact_commit", db, pg_scope) + " + " + rate("pg_stat_database_xact_rollback", db, pg_scope),
        "db_active": value("pg_stat_activity_count", db, pg_scope + ',state="active"'),
        "db_locks": "sum by(node,instance)(" + value("pg_locks_count", db, pg_scope) + ")",
        "db_tables": "topk(5,(" + value("pg_stat_user_tables_size_bytes", db, pg_scope) + ") and on(instance,job) (" + value("pg_wal_position_in_recovery", db) + " == 0))",
        "wal_rate": rate("pg_wal_position_bytes", db),
        "backup_timestamp": value("failstop_backup_last_success_timestamp_seconds", host),
        "requests_by_uri": "topk(10,sum by(uri,method)(" + healthy("increase(" + raw("http_server_requests_seconds_count", app, business) + f"[{range_seconds}s])", app) + "))",
        "http_codes": 'sum by(code)(label_replace(' + http + ',"code","${1}xx","status","([1-5])[0-9][0-9]"))',
        "instance_rps": "sum by(node,instance)(" + http + ")",
        "instance_latency": latency(0.95, True),
        "availability": f"({all_up} and (time() - timestamp({all_up}) < 90))",
    }


def finite(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def safe_labels(value):
    if not isinstance(value, dict):
        raise MetricsUnavailable("invalid_prometheus_labels")
    return {str(key): str(text)[:512] for key, text in value.items() if key != "__name__"}


def merge_series(instant, history, start=None, end=None, step=None):
    if len(instant) > MAX_SERIES or len(history) > MAX_SERIES:
        raise MetricsUnavailable("series_limit")
    merged = {}
    for item in history:
        labels = safe_labels(item.get("metric", {}))
        key = tuple(sorted(labels.items()))
        if len(item.get("values", [])) > MAX_POINTS:
            raise MetricsUnavailable("points_limit")
        points = [[float(point[0]), finite(point[1])] for point in item.get("values", [])]
        if start is not None and end is not None and step is not None:
            sampled = {round((point[0] - start) / step): point[1] for point in points}
            count = min(MAX_POINTS, math.floor((end - start) / step) + 1)
            points = [[start + index * step, sampled.get(index)] for index in range(count)]
        merged[key] = {"labels": labels, "points": points, "value": None}
    for item in instant:
        labels = safe_labels(item.get("metric", {}))
        key = tuple(sorted(labels.items()))
        sample = item.get("value", [None, None])
        if key not in merged:
            if len(merged) >= MAX_SERIES:
                raise MetricsUnavailable("series_limit")
            merged[key] = {"labels": labels, "points": [], "value": None}
        merged[key]["value"] = finite(sample[1])
    result = list(merged.values())
    return {"state": "ok" if any(item["value"] is not None for item in result) else "missing", "series": result}


class MetricsClient:
    def __init__(self, base_url, timeout=5):
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("invalid_prometheus_url")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._cache = {}
        self._lock = threading.Lock()
        self._load_lock = threading.Lock()

    def _get(self, path, params=None):
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(url, headers={"Accept": "application/json"})
        try:
            opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
            with opener.open(request, timeout=self.timeout) as response:
                data = response.read(MAX_RESPONSE + 1)
            if len(data) > MAX_RESPONSE:
                raise MetricsUnavailable("prometheus_response_limit")
            payload = json.loads(data)
            if not isinstance(payload, dict) or payload.get("status") != "success" or not isinstance(payload.get("data"), dict):
                raise MetricsUnavailable("prometheus_query_failed")
            return payload["data"]
        except (OSError, ValueError, urllib.error.URLError) as error:
            raise MetricsUnavailable("prometheus_unavailable") from error

    def _cached_bytes(self, key):
        with self._lock:
            now = time.monotonic()
            for expired in [name for name, entry in self._cache.items() if now - entry[0] >= 10]:
                self._cache.pop(expired)
            entry = self._cache.get(key)
            return entry[1] if entry else None

    def _cached(self, key, loader):
        cached = self._cached_bytes(key)
        if cached is not None:
            return json.loads(cached)
        # One refresh at a time prevents parallel browser tabs multiplying query workers.
        with self._load_lock:
            cached = self._cached_bytes(key)
            if cached is not None:
                return json.loads(cached)
            value = loader()
            encoded = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
            if len(encoded) <= MAX_CACHE_ENTRY_BYTES:
                with self._lock:
                    while self._cache and (len(self._cache) >= MAX_CACHE_ENTRIES or
                                           sum(len(entry[1]) for entry in self._cache.values()) + len(encoded) > MAX_CACHE_BYTES):
                        self._cache.pop(next(iter(self._cache)))
                    self._cache[key] = (time.monotonic(), encoded)
            return value

    def scopes(self):
        def load():
            grouped = {}
            for target in self._get("/api/v1/targets").get("activeTargets", []):
                labels = target.get("labels", {})
                if labels.get("role") not in ROLES or labels.get("job") not in JOBS:
                    continue
                customer, environment = labels.get("customer", ""), labels.get("environment", "")
                if not IDENTIFIER.fullmatch(customer) or not IDENTIFIER.fullmatch(environment):
                    continue
                entry = grouped.setdefault((customer, environment), {"customer": customer, "environment": environment, "services": set(), "instances": set()})
                if labels.get("service"):
                    entry["services"].add(labels["service"])
                if labels.get("instance"):
                    entry["instances"].add(labels["instance"])
            return [{**entry, "services": sorted(entry["services"]), "instances": sorted(entry["instances"])}
                    for _, entry in sorted(grouped.items())]
        return self._cached(("scopes",), load)

    def snapshot(self, customer, environment, range_seconds=3600, view="overview", service="", instance=""):
        scope_labels(customer, environment, service, instance)
        if type(range_seconds) is not int or not 300 <= range_seconds <= MAX_RANGE:
            raise ValueError("invalid_metrics_range")
        if not isinstance(view, str) or not re.fullmatch(r"[a-z-]{1,40}", view):
            raise ValueError("invalid_metrics_view")
        key = (customer, environment, range_seconds, service, instance)
        return self._cached(key, lambda: self._snapshot(customer, environment, range_seconds, service, instance))

    def _snapshot(self, customer, environment, range_seconds, service, instance):
        end = int(time.time())
        start = end - range_seconds
        step = max(15, math.ceil(range_seconds / (MAX_POINTS - 1)))
        metric_queries = queries(customer, environment, range_seconds, service, instance)
        result = {"generatedAt": end, "start": start, "end": end, "range": range_seconds,
                  "scope": {"customer": customer, "environment": environment, "service": service, "instance": instance},
                  "metrics": {}, "targets": [], "alerts": [], "sources": []}
        try:
            target_data = self._get("/api/v1/targets")
        except MetricsUnavailable:
            result["metrics"] = {name: {"state": "error", "series": []} for name in metric_queries}
            result["sources"] = [{"name": name, "state": "error", "message": "Prometheus 연결 확인 필요"}
                                 for name in ("prometheus", "targets", "alerts")]
            return result

        def matches(labels):
            return (labels.get("customer") == customer and labels.get("environment") == environment
                    and labels.get("role") in ROLES
                    and (not service or labels.get("service") == service)
                    and (not instance or labels.get("instance") == instance))

        def collect_metric(expression):
            try:
                instant = self._get("/api/v1/query", {"query": expression, "time": end})
                history = self._get("/api/v1/query_range", {"query": expression, "start": start, "end": end, "step": step})
                return merge_series(instant.get("result", []), history.get("result", []), start, end, step)
            except MetricsUnavailable as error:
                limit = str(error) in {"series_limit", "points_limit", "prometheus_response_limit"}
                return {"state": "error", "series": [], "message": "표시 한도 초과 · 인스턴스 범위 축소 필요" if limit else "지표 조회 실패"}
            except (TypeError, ValueError, KeyError, IndexError):
                return {"state": "error", "series": []}

        with ThreadPoolExecutor(max_workers=8) as pool:
            pending = {pool.submit(collect_metric, expression): name for name, expression in metric_queries.items()}
            alert_job = pool.submit(self._get, "/api/v1/alerts")
            total_points = 0
            for task in as_completed(pending):
                name = pending.pop(task)
                metric = task.result()
                count = sum(len(series["points"]) for series in metric["series"])
                if total_points + count > MAX_TOTAL_POINTS:
                    metric = {"state": "error", "series": [], "message": "표시 한도 초과 · 인스턴스 범위 축소 필요"}
                else:
                    total_points += count
                result["metrics"][name] = metric
            for name, task in (("targets", None), ("alerts", alert_job)):
                try:
                    data = target_data if task is None else task.result()
                    if name == "targets":
                        result["targets"] = [{"labels": safe_labels(item.get("labels", {})),
                                              "health": item.get("health") if item.get("health") in {"up", "down"} else "unknown",
                                              "lastScrape": item.get("lastScrape"),
                                              "lastError": "수집 실패" if item.get("lastError") else ""}
                                             for item in data.get("activeTargets", [])
                                             if matches(item.get("labels", {})) and item.get("labels", {}).get("job") in JOBS]
                    else:
                        for item in data.get("alerts", []):
                            labels = item.get("labels", {})
                            if not matches(labels) or labels.get("component") == "reporting":
                                continue
                            annotations = item.get("annotations", {})
                            identity = {"labels": labels, "activeAt": item.get("activeAt")}
                            identifier = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:16]
                            result["alerts"].append({"id": identifier, "name": labels.get("alertname", "경보"),
                                                     "severity": labels.get("severity", "warning"), "state": item.get("state", "unknown"),
                                                     "activeAt": item.get("activeAt"), "summary": str(annotations.get("summary", ""))[:1000],
                                                     "description": str(annotations.get("description", ""))[:2000], "labels": safe_labels(labels)})
                    result["sources"].append({"name": name, "state": "ok", "message": "조회 완료"})
                except (MetricsUnavailable, TypeError, ValueError, KeyError):
                    result["sources"].append({"name": name, "state": "error", "message": "Prometheus 연결 확인 필요"})
        failed = sum(metric["state"] == "error" for metric in result["metrics"].values())
        result["sources"].insert(0, {"name": "prometheus", "state": "error" if failed else "ok",
                                     "message": f"지표 조회 실패 {failed}건" if failed else "고객 범위 지표 조회 완료"})
        result["targets"].sort(key=lambda item: (item["labels"].get("role", ""), item["labels"].get("node", "")))
        result["alerts"].sort(key=lambda item: (item["severity"] != "critical", item["activeAt"] or ""))
        return result
