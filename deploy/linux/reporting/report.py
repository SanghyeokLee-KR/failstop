"""Collect a bounded set of metrics and send an advisory report to Teams."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path


QUERIES = {
    "up": 'up{job="linux"}',
    "sample_age_seconds": 'time() - timestamp(up{job="linux"})',
    "collectors_ok": 'min by(node) (node_scrape_collector_success{job="linux"})',
    "cpu_percent": '100 * (1 - avg by(node) (rate(node_cpu_seconds_total{job="linux",mode="idle"}[2m])))',
    "memory_percent": '100 * (1 - node_memory_MemAvailable_bytes{job="linux"} / node_memory_MemTotal_bytes{job="linux"})',
    "disk_percent": '100 * (1 - node_filesystem_avail_bytes{job="linux",mountpoint="/"} / node_filesystem_size_bytes{job="linux",mountpoint="/"})',
    "inode_percent": '100 * (1 - node_filesystem_files_free{job="linux",mountpoint="/"} / node_filesystem_files{job="linux",mountpoint="/"})',
}


class RequestFailure(Exception):
    """Contains only a safe status; never includes a credential-bearing URL."""


DB_QUERIES = {
    "db_metrics_up": 'up{job="postgres"}',
    "db_sample_age_seconds": 'time() - timestamp(up{job="postgres"})',
    "db_up": 'pg_up{job="postgres"}',
    "db_exporter_error": 'pg_exporter_last_scrape_error{job="postgres"}',
    "db_bytes": 'pg_database_size_bytes{job="postgres",datname="exchange"}',
    "wal_bytes": 'pg_wal_position_dir_bytes{job="postgres"}',
    "in_recovery": 'pg_wal_position_in_recovery{job="postgres"}',
}


def scoped_queries(config):
    for name, query in QUERIES.items():
        yield name, query.replace('job="linux"', 'job=~"linux|linux-host"'), config["nodes"]
    for name, query in DB_QUERIES.items():
        if config.get("db_nodes"):
            yield name, query, list(config["db_nodes"])
    if config.get("app_nodes"):
        yield "app_metrics_up", 'up{job="exchange-api"}', config["app_nodes"]
        yield "app_sample_age_seconds", 'time() - timestamp(up{job="exchange-api"})', config["app_nodes"]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(url, payload=None, headers=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, headers=headers or {})
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=20) as response:
            body = response.read(1_000_001)
            if len(body) > 1_000_000:
                raise RequestFailure("response_too_large")
            return response.status, body
    except urllib.error.HTTPError as exc:
        raise RequestFailure("http_" + str(exc.code)) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RequestFailure("network_error") from None


def collect(config):
    base = config["prometheus_url"].rstrip("/")
    # This job runs on bot-1 next to Prometheus; no arbitrary URL fetch is needed.
    if base != "http://127.0.0.1:9090":
        raise ValueError("prometheus_url must point to the local service")
    nodes = config["nodes"]
    if not nodes or any(not re.fullmatch(r"[a-zA-Z0-9_-]{1,40}", node) for node in nodes):
        raise ValueError("invalid node scope")
    if len(nodes) != len(set(nodes)):
        raise ValueError("duplicate node scope")
    if not set(config.get("db_nodes", {})).union(config.get("app_nodes", [])).issubset(nodes):
        raise ValueError("service scope exceeds node scope")
    if any(role not in ("primary", "standby") for role in config.get("db_nodes", {}).values()):
        raise ValueError("invalid database role")
    snapshot = {"at": datetime.now(timezone.utc).isoformat(), "scope": nodes,
                "metrics": {node: {} for node in nodes}, "collection_errors": [],
                "required_services": config.get("required_services", {}),
                "services": {node: {} for node in nodes},
                "db_nodes": config.get("db_nodes", {}),
                "expected_metrics": {node: [] for node in nodes}}
    for name, query, expected_nodes in scoped_queries(config):
        for node in expected_nodes:
            snapshot["expected_metrics"][node].append(name)
        try:
            _, body = request(base + "/api/v1/query?" + urllib.parse.urlencode({"query": query}))
            result = json.loads(body)
            if result["status"] != "success" or result["data"]["resultType"] != "vector":
                raise ValueError("invalid response")
            seen = set()
            for row in result["data"]["result"]:
                node = row["metric"].get("node")
                if node not in expected_nodes:
                    continue
                if node in seen:
                    raise ValueError("duplicate node label")
                seen.add(node)
                value = float(row["value"][1])
                if not math.isfinite(value):
                    raise ValueError("non-finite metric")
                snapshot["metrics"][node][name] = round(value, 2)
        except (RequestFailure, ValueError, KeyError, TypeError, IndexError):
            for node in expected_nodes:
                snapshot["metrics"][node].pop(name, None)
            snapshot["collection_errors"].append(name)
    if snapshot["required_services"]:
        try:
            query = 'node_systemd_unit_state{job=~"linux|linux-host",state="active"}'
            _, body = request(base + "/api/v1/query?" + urllib.parse.urlencode({"query": query}))
            result = json.loads(body)
            if result["status"] != "success" or result["data"]["resultType"] != "vector":
                raise ValueError("invalid service response")
            for row in result["data"]["result"]:
                node, name = row["metric"].get("node"), row["metric"].get("name")
                if node not in nodes or name not in snapshot["required_services"].get(node, []):
                    continue
                value = float(row["value"][1])
                if name in snapshot["services"][node] or value not in (0, 1):
                    raise ValueError("ambiguous service state")
                snapshot["services"][node][name] = int(value)
        except (RequestFailure, ValueError, KeyError, TypeError, IndexError):
            snapshot["services"] = {node: {} for node in nodes}
            snapshot["collection_errors"].append("services")
    snapshot["issues"] = evaluate(snapshot)
    return snapshot


def evaluate(snapshot):
    issues = []
    for node in snapshot["scope"]:
        values = snapshot["metrics"].get(node, {})
        for service in snapshot.get("required_services", {}).get(node, []):
            active = snapshot.get("services", {}).get(node, {}).get(service)
            if active is None:
                issues.append(node + ":missing_service:" + service)
            elif active != 1:
                issues.append(node + ":service_inactive:" + service)
        for key in snapshot.get("expected_metrics", {}).get(node, QUERIES):
            if key not in values:
                issues.append(node + ":missing:" + key)
        if values.get("up") == 0:
            issues.append(node + ":scrape_failed")
        if values.get("sample_age_seconds", 0) > 60:
            issues.append(node + ":stale_metrics")
        if values.get("collectors_ok") == 0:
            issues.append(node + ":collector_failed")
        for key in ("db_metrics_up", "db_up", "app_metrics_up"):
            if values.get(key) == 0:
                issues.append(node + ":failed:" + key)
        for key in ("db_sample_age_seconds", "app_sample_age_seconds"):
            if values.get(key, 0) > 60:
                issues.append(node + ":stale:" + key)
        if values.get("db_exporter_error", 0) != 0:
            issues.append(node + ":db_collection_failed")
        role = snapshot.get("db_nodes", {}).get(node)
        if role and "in_recovery" in values:
            if values["in_recovery"] != (1 if role == "standby" else 0):
                issues.append(node + ":unexpected_db_role")
        for key, limit in (("cpu_percent", 90), ("memory_percent", 85),
                           ("disk_percent", 85), ("inode_percent", 85)):
            if values.get(key, 0) >= limit:
                issues.append(node + ":high:" + key)
        if 70 <= values.get("disk_percent", 0) < 85:
            issues.append(node + ":warning:disk_percent")
        if values.get("disk_percent", 0) >= 95:
            issues.append(node + ":critical:disk_percent")
    return sorted(set(issues))


def summarize(snapshot, model, api_key):
    if not re.fullmatch(r"gemini-[a-zA-Z0-9.-]+", model):
        raise ValueError("invalid model")
    prompt = ("Linux 운영 담당자가 읽는 짧은 한국어 보고를 작성한다. 제공된 지표 범위만 설명한다. "
              "제목, 소제목, 목록, 굵은 글씨 없이 자연스러운 2~3문장으로 쓴다. 시각은 본문에 반복하지 않는다. "
              "issues가 비어 있으면 정상 범위의 수치만 요약하고 원인이나 이상 징후를 추측하지 않는다. "
              "issues가 있으면 관측 사실과 원인 가설을 구분하고 사람이 확인할 항목 하나를 제안한다. "
              "수집 주기는 15초다. sample_age_seconds는 마지막 수집 후 경과 시간이며 네트워크 지연이 아니다. "
              "이 값은 60초를 넘을 때만 오래된 지표로 판정한다. 15초 이내의 값을 지연이나 문제라고 쓰지 않는다. "
              "누락값은 미수집이며 정상이나 0으로 해석하지 않는다. db_up은 DB 접속 성공, "
              "app_metrics_up은 지표 수집 성공일 뿐 정상 거래를 증명하지 않는다. "
              "in_recovery는 DB 역할이며 복제 연결이나 무손실을 증명하지 않는다. "
              "DB·거래·복구가 검증됐다고 쓰지 않는다. "
              "명령 실행이나 조치를 했다고 쓰지 않으며 향후 작업을 약속하지 않는다. URL, 계정 정보, 명령어는 만들지 않는다. "
              "500자 이내의 자연스러운 문단으로 쓴다.\n" + json.dumps(snapshot, ensure_ascii=False))
    _, body = request(
        "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent",
        {"contents": [{"parts": [{"text": prompt}]}],
         "generationConfig": {"maxOutputTokens": 1000, "temperature": 0.2}},
        {"x-goog-api-key": api_key})
    data = json.loads(body)
    candidate = data["candidates"][0]
    if candidate.get("finishReason") != "STOP":
        raise ValueError("incomplete model output")
    text = "\n".join(part["text"] for part in candidate["content"]["parts"]
                     if "text" in part and not part.get("thought"))
    if not text.strip():
        raise ValueError("empty model output")
    return text[:1800]


def card(snapshot, explanation, report_id):
    status = "확인 필요" if snapshot["issues"] else "수집 범위 내 임계 초과 없음"
    local_time = datetime.fromisoformat(snapshot["at"]).astimezone(timezone(timedelta(hours=9)))
    lines = ["범위: " + ", ".join(snapshot["scope"]), "기준 시각: " + local_time.strftime("%Y-%m-%d %H:%M:%S KST"),
             "판정: " + status, "확인 항목: " + (", ".join(snapshot["issues"]) or "없음"),
             "이 보고는 현재 지표 요약이다. 하루 전체의 가용성이나 DB 정합성을 증명하지 않는다."]
    for node, values in snapshot["metrics"].items():
        displayed = []
        for key, label in (("cpu_percent", "CPU"), ("memory_percent", "메모리"),
                           ("disk_percent", "루트 디스크"), ("inode_percent", "루트 inode")):
            displayed.append(label + " " + (str(values[key]) + "%" if key in values else "미수집"))
        lines.append(node + ": " + " / ".join(displayed))
        if node in snapshot.get("db_nodes", {}):
            sizes = [label + " " + (str(round(values[key] / (1024 ** 2), 1)) + " MiB"
                                    if key in values else "미수집")
                     for key, label in (("db_bytes", "거래 DB"), ("wal_bytes", "보유 WAL"))]
            lines.append(node + ": " + " / ".join(sizes))
    if snapshot.get("services"):
        lines.append("서비스: " + json.dumps(snapshot["services"], ensure_ascii=False, sort_keys=True))
    return {"type": "message", "attachments": [{
        "contentType": "application/vnd.microsoft.card.adaptive", "contentUrl": None,
        "content": {"type": "AdaptiveCard", "version": "1.2", "body": [
            {"type": "TextBlock", "text": "학교 AWS 운영 보고", "weight": "Bolder", "size": "Large"},
            {"type": "TextBlock", "text": "\n\n".join(lines), "wrap": True},
            {"type": "TextBlock", "text": "AI 검토 의견\n" + explanation, "wrap": True},
            {"type": "TextBlock", "text": "보고 ID: " + report_id, "wrap": True}
        ]}}]}


def validate_webhook(url):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ""
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("invalid Teams endpoint")
    if not any(host.endswith(suffix) for suffix in (".logic.azure.com", ".api.powerplatform.com")):
        raise ValueError("expected a Teams Workflows endpoint")


def write_json(path, data):
    temp = path.with_suffix(".tmp")
    with temp.open("w", encoding="utf-8") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temp, path)


def deliver(payload, webhook, state, state_path, report_id, fingerprint):
    validate_webhook(webhook)
    # A lost HTTP response is ambiguous. Record before sending and never blindly resend.
    if state.get("pending"):
        raise RequestFailure("previous_delivery_needs_review")
    state["pending"] = report_id
    write_json(state_path, state)
    request(webhook, payload)
    state.update(pending=None, last_id=report_id, last_fingerprint=fingerprint)
    if report_id.startswith("daily-"):
        state["last_daily_id"] = report_id
    write_json(state_path, state)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--mode", choices=("daily", "incident"), default="daily")
    parser.add_argument("--send", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    directory = Path(args.state_dir)
    directory.mkdir(parents=True, exist_ok=True)
    # The deployed command is Linux-only. Preview does not send or read credentials.
    import fcntl
    with (directory / "report.lock").open("a") as lock:
        try:
            flags = fcntl.LOCK_EX | (fcntl.LOCK_NB if args.mode == "incident" else 0)
            fcntl.flock(lock, flags)
        except BlockingIOError:
            print("report_busy; next incident check will retry")
            return
        snapshot = collect(config)
        write_json(directory / "latest-snapshot.json", snapshot)
        fingerprint = hashlib.sha256(json.dumps(snapshot["issues"]).encode()).hexdigest()
        day = datetime.now(timezone(timedelta(hours=9))).date().isoformat()
        report_id = "daily-" + day if args.mode == "daily" else "incident-" + str(time.time_ns())
        state_path = directory / "delivery-state.json"
        state = json.loads(state_path.read_text()) if state_path.exists() else {}
        if not args.send:
            print(json.dumps(snapshot, ensure_ascii=False))
            return
        if state.get("pending"):
            raise RequestFailure("previous_delivery_needs_review")
        if args.mode == "daily" and state.get("last_daily_id") == report_id:
            print("already_accepted")
            return
        if args.mode == "incident" and (state.get("last_fingerprint") == fingerprint
                                       or (not snapshot["issues"] and "last_fingerprint" not in state)):
            print("unchanged")
            return
        credentials = Path(os.environ["CREDENTIALS_DIRECTORY"])
        webhook = (credentials / "teams_webhook_url").read_text().strip()
        try:
            key = (credentials / "gemini_api_key").read_text().strip()
            explanation = summarize(snapshot, config["model"], key)
        except (RequestFailure, ValueError, KeyError, IndexError, TypeError, OSError) as error:
            reason = str(error) if isinstance(error, RequestFailure) else type(error).__name__
            print("ai_summary_unavailable " + reason, file=sys.stderr)
            explanation = "AI 요약을 받지 못했다. 위의 실측값과 미수집 항목을 확인해야 한다."
        payload = card(snapshot, explanation, report_id)
        write_json(directory / "latest-report.json", payload)
        deliver(payload, webhook, state, state_path, report_id, fingerprint)
        print("workflow_accepted " + report_id)


if __name__ == "__main__":
    try:
        main()
    except RequestFailure as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Exception messages can contain URLs, headers, or file contents.
        print("report_failed; inspect local configuration and service state", file=sys.stderr)
        sys.exit(1)
