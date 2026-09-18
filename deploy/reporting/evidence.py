import datetime as dt
import json
import math
import urllib.parse
from pathlib import Path

from transport import RequestFailure, json_request, redact


def timestamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def file_evidence(root, relative, kind):
    if not root or not relative:
        return {"status": "missing", "records": []}
    base = Path(root).resolve()
    target = (base / relative).resolve()
    if not target.is_relative_to(base) or target == base:
        return {"status": "error", "error": "evidence_outside_root", "records": []}
    try:
        if target.stat().st_size > 65536:
            raise ValueError("evidence_too_large")
        if kind in {"backup_schedule", "backup_restore"}:
            document = json.loads(target.read_text(encoding="utf-8"))
            if document.get("schema_version") != 1 or not isinstance(document.get("records"), dict):
                raise ValueError("backup_review_schema")
            allowed = ({"enabled", "checked_at", "next_run_at"} if kind == "backup_schedule"
                       else {"verified", "backup_id", "verified_at"})
            records = {key: value for key, value in document["records"].items() if key in allowed}
            flag = "enabled" if kind == "backup_schedule" else "verified"
            if type(records.get(flag)) is not bool:
                raise ValueError("backup_review_flag")
            for name, value in records.items():
                if name != flag and (not isinstance(value, str) or not 1 <= len(value) <= 160):
                    raise ValueError("backup_review_field")
            return {"status": "ok", "records": redact(records)}
        if kind == "host":
            document = json.loads(target.read_text(encoding="utf-8"))
            if document.get("schema_version") != 1 or not isinstance(document.get("sources"), dict):
                raise ValueError("host_evidence_schema")
            allowed = {"filesystems", "inodes", "failed_services", "service_states", "disk_io", "network", "journal_usage"}
            sources = {key: {field: value for field, value in source.items() if field in {"ok", "output", "error"}}
                       for key, source in document["sources"].items() if key in allowed and isinstance(source, dict)}
            state = "missing" if not sources else "ok" if all(source.get("ok") is True for source in sources.values()) else "error"
            return {"status": state, "collected_at": document.get("collected_at"),
                    "source_status": document.get("status"), "records": redact(sources)}
        fields = ({"__REALTIME_TIMESTAMP", "_SYSTEMD_UNIT", "PRIORITY", "MESSAGE"} if kind == "journal"
                  else {"ActivityId", "StartTime", "EndTime", "StatusCode", "Description", "Cause", "Progress"})
        records = []
        for line in target.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if not isinstance(row, dict) or len(records) >= 50:
                raise ValueError("evidence_shape_or_count")
            records.append(redact({key: value for key, value in row.items() if key in fields}))
        return {"status": "ok" if records else "missing", "records": records}
    except FileNotFoundError:
        return {"status": "missing", "records": []}
    except (OSError, ValueError, UnicodeError, AttributeError):
        return {"status": "error", "error": "evidence_unreadable", "records": []}


def metric_fact(base, query, end, window, timeout):
    params = {"query": query["expr"], "time": end}
    endpoint = "/api/v1/query"
    step = None
    if query.get("range"):
        endpoint = "/api/v1/query_range"
        step = max(60, window // 120)
        params = {"query": query["expr"], "start": end - window, "end": end, "step": step}
    source = {"system": "prometheus", "query_type": "range" if step is not None else "instant",
              "window_start": end - window if step is not None else end, "window_end": end,
              "step_seconds": step, "aggregation": query.get("aggregation", "per_series"),
              "unit": query.get("unit"), "scope": query.get("scope", query["description"])}
    try:
        response = json_request(base.rstrip("/") + endpoint + "?" + urllib.parse.urlencode(params), timeout=timeout)
        if response.get("status") != "success":
            raise ValueError("query_failed")
        result = response["data"]["result"]
        if not isinstance(result, list) or len(result) > 50:
            raise ValueError("series_limit")
        samples = []
        for series in result:
            values = series.get("values", [series.get("value")])
            if not values or len(values) > 200:
                raise ValueError("samples_limit")
            numeric = [float(value[1]) for value in values]
            times = [float(value[0]) for value in values]
            if not all(math.isfinite(value) for value in numeric + times):
                raise ValueError("nonfinite_sample")
            if any(right <= left for left, right in zip(times, times[1:])):
                raise ValueError("unordered_samples")
            labels = {key: value for key, value in series.get("metric", {}).items()
                      if key in {"job", "instance", "mountpoint", "device", "name", "state", "application_name",
                                 "datname", "node", "role", "service", "environment"}}
            samples.append({"labels": redact(labels), "last": numeric[-1], "min": min(numeric),
                            "max": max(numeric), "count": len(numeric),
                            "first_observed_at": times[0], "last_observed_at": times[-1],
                            "points": [[moment, value] for moment, value in zip(times, numeric)]})
        result = {"status": "ok" if samples else "missing", "samples": samples, "source": source}
        if not samples:
            result["missing_reason"] = query.get("missing_reason", "조회 조건과 시간 범위에 해당하는 지표 표본이 없습니다.")
        return result
    except RequestFailure as error:
        return {"status": "error", "error": error.code, "samples": [], "source": source}
    except (KeyError, TypeError, ValueError, IndexError, AttributeError):
        return {"status": "error", "error": "invalid_metrics", "samples": [], "source": source}


def collect(config, end, incident=None):
    window = config.get("incident_window_seconds", 900) if incident is not None else config.get("window_seconds", 86400)
    if isinstance(window, bool) or not isinstance(window, int) or not 60 <= window <= 86400:
        raise ValueError("invalid_collection_window")
    facts = []
    for query in config.get("queries", []):
        facts.append({"id": query["id"], "kind": "metric", "description": query["description"],
                      **metric_fact(config["prometheus_url"], query, end, window,
                                    config.get("http_timeout", 5))})
    try:
        alert_url = config["alertmanager_url"].rstrip("/") + "/api/v2/alerts"
        if config.get("alert_matchers"):
            alert_url += "?" + urllib.parse.urlencode({"filter": config["alert_matchers"]}, doseq=True)
        alerts = json_request(alert_url, timeout=config.get("http_timeout", 5))
        if not isinstance(alerts, list) or len(alerts) > 100:
            raise ValueError("alerts_limit")
        rows = [{key: row[key] for key in ("labels", "annotations", "startsAt", "endsAt", "status", "fingerprint") if key in row}
                for row in alerts]
        facts.append({"id": "alerts", "kind": "alerts", "status": "ok", "records": redact(rows)})
    except (RequestFailure, ValueError, TypeError):
        facts.append({"id": "alerts", "kind": "alerts", "status": "error", "error": "alerts_unavailable", "records": []})
    for kind in ("host", "journal", "aws_activity", "backup_schedule", "backup_restore"):
        facts.append({"id": kind, "kind": kind, **file_evidence(config.get("evidence_root"), config.get(kind + "_file"), kind)})
    if incident:
        facts.append({"id": "trigger", "kind": "alert_trigger", "status": "ok", "records": redact(incident)})
    return {"schema_version": 1, "collected_at": timestamp(), "window_end": end,
            "scope": {key: config[key] for key in ("customer", "environment") if key in config},
            "window_seconds": window, "facts": facts}
