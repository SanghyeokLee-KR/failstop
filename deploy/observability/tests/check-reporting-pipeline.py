"""Bounded local pipeline rehearsal; --send explicitly permits Teams test messages."""
import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "deploy/reporting"))
from reporter import daily_key, incident_payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--resume", action="store_true", help="Continue the same recorded alert without another firing notification")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.send:
        print("Plan: one test alert firing/resolved, two mock incident reports, one daily report; retain quiet routing.")
        return
    state = ROOT / "deploy/observability/.state"
    token = (state / "reporter-token").read_text().strip()
    config = state / "alertmanager-quiet.yml"
    quiet = "route:\n  receiver: quiet\nreceivers:\n  - name: quiet\n"

    def request(url, payload=None, auth=False):
        data = None if payload is None else json.dumps(payload).encode()
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + token
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=15) as response:
            body = response.read()
            return json.loads(body) if body else {}

    def wsl(*command, stdin=None):
        result = subprocess.run(["wsl.exe", "-d", "Ubuntu", "-u", "root", "--", *command], input=stdin,
                                text=True, encoding="utf-8", capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("wsl_check_failed")
        return result.stdout.strip()

    def job(key):
        code = ("import sqlite3,json,sys; c=sqlite3.connect('/var/lib/failstop-reporting/outbox.sqlite3');"
                "r=c.execute('SELECT id,status,attempts,report FROM jobs WHERE id=?',(sys.argv[1],)).fetchone();"
                "print(json.dumps(None if r is None else {'id':r[0],'status':r[1],'attempts':r[2],"
                "'mode':json.loads(r[3])['analysis']['mode'] if r[3] else None}))")
        return json.loads(wsl("python3", "-c", code, key))

    def await_sent(key):
        for _ in range(40):
            record = job(key)
            if record and record["status"] == "sent":
                return record
            if record and record["status"] == "dead":
                raise RuntimeError("report_failed")
            time.sleep(1)
        raise RuntimeError("report_delivery_timeout")

    def basic_notifications():
        with urllib.request.urlopen("http://127.0.0.1:59093/metrics", timeout=5) as response:
            lines = response.read().decode().splitlines()
        return {"sent": sum(float(line.rsplit(" ", 1)[1]) for line in lines if line.startswith('alertmanager_notifications_total{') and 'integration="msteamsv2"' in line),
                "failed": sum(float(line.rsplit(" ", 1)[1]) for line in lines if line.startswith('alertmanager_notifications_failed_total{') and 'integration="msteamsv2"' in line)}

    selective = '''route:
  receiver: quiet
  group_by: [alertname, instance]
  group_wait: 1s
  group_interval: 1s
  repeat_interval: 1h
  routes:
    - receiver: teams-test
      matchers: ['alertname="OperationsIntegrationCheck"']
      continue: true
    - receiver: reporter-test
      matchers: ['alertname="OperationsIntegrationCheck"']
receivers:
  - name: quiet
  - name: teams-test
    msteamsv2_configs:
      - webhook_url_file: /run/secrets/teams_webhook
        send_resolved: true
        http_config: {follow_redirects: false}
        title: '[LOCAL TEST {{ .Status }}] OperationsIntegrationCheck'
        text: '로컬 운영 보고 연결 시험이다. 실제 장애가 아니다.'
  - name: reporter-test
    webhook_configs:
      - url: http://host.docker.internal:59188/reporter/alerts
        send_resolved: true
        http_config:
          follow_redirects: false
          authorization:
            type: Bearer
            credentials_file: /run/secrets/reporter_token
'''
    previous = json.loads(args.output.read_text()) if args.resume else None
    started = dt.datetime.fromisoformat(previous["started_at"]) if previous else dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    labels = {"alertname": "OperationsIntegrationCheck", "instance": "local-reporting-test", "severity": "info", "component": "reporting"}
    alert = {"labels": labels, "annotations": {"summary": "로컬 운영 보고 연결 시험. 실제 장애가 아니다."},
             "startsAt": started.isoformat().replace("+00:00", "Z"),
             "endsAt": (started + dt.timedelta(minutes=10)).isoformat().replace("+00:00", "Z")}
    record = previous or {"started_at": started.isoformat(), "scope": "local-test-only", "channel_post_confirmed": False,
                          "basic_before": basic_notifications()}
    before = record["basic_before"]
    record["status"] = "RUNNING"
    try:
        config.write_text(selective, encoding="utf-8")
        request("http://127.0.0.1:59093/-/reload", {})
        firing_key, _ = incident_payload({"status": "firing", "alerts": [{**alert, "status": "firing"}]})
        if args.resume:
            if not job(firing_key) or job(firing_key)["status"] != "sent":
                raise RuntimeError("resume_requires_existing_sent_firing")
        else:
            request("http://127.0.0.1:59093/api/v2/alerts", [alert])
        record["firing"] = await_sent(firing_key)
        duplicate = request("http://127.0.0.1:59188/reporter/alerts", {"status": "firing", "alerts": [{**alert, "status": "firing"}]}, True)
        if not duplicate.get("duplicate"):
            raise RuntimeError("incident_dedup_failed")
        record["duplicate_suppressed"] = True
        alert["endsAt"] = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        request("http://127.0.0.1:59093/api/v2/alerts", [alert])
        resolved_key, _ = incident_payload({"status": "resolved", "alerts": [{**alert, "status": "resolved"}]})
        record["resolved"] = await_sent(resolved_key)
        after = basic_notifications()
        record["basic_notifications"] = {key: after[key] - before[key] for key in before}
        if record["basic_notifications"] != {"sent": 2, "failed": 0}:
            raise RuntimeError("basic_notification_count_unconfirmed")
        command = ["python3", "/opt/failstop/deploy/reporting/reporter.py", "daily", "--config", "/etc/failstop/reporting.json"]
        daily_result = json.loads(wsl(*command))
        record["daily_enqueue"] = daily_result
        record["daily"] = await_sent(daily_result["id"])
        duplicate_daily = json.loads(wsl(*command))
        record["daily_duplicate_suppressed"] = duplicate_daily.get("duplicate") is True
        record["timer_active"] = wsl("systemctl", "is-active", "failstop-reporting-daily.timer") == "active"
        record["status"] = "PASS"
    finally:
        alert["endsAt"] = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        request("http://127.0.0.1:59093/api/v2/alerts", [alert])
        config.write_text(quiet, encoding="utf-8")
        request("http://127.0.0.1:59093/-/reload", {})
        record["notifications_after_test"] = "quiet"
        if record.get("status") != "PASS":
            record["status"] = "FAIL"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(record, ensure_ascii=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired):
        raise SystemExit("reporting_pipeline_check_failed; inspect sanitized evidence") from None
