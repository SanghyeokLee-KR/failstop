"""Exercise Alertmanager and reporting against a loopback-only Teams substitute."""
import datetime as dt
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "deploy/reporting"))
from reporter import ReportServer, Store, daily_key, drain_one, incident_payload, load_config
from transport import request


def main():
    state = ROOT / "deploy/observability/.state"
    output = ROOT / "artifacts/operations-20260918/reporting-local-pipeline.json"
    token = (state / "reporter-token").read_text().strip()
    config_file = state / "alertmanager-quiet.yml"
    received = []

    class Sink(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            received.append(json.loads(body))
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")

    def post(url, payload, auth=False):
        headers = {"Authorization": "Bearer " + token} if auth else {}
        return json.loads(request(url, payload=payload, headers=headers) or b"{}")

    selective = '''route:
  receiver: quiet
  group_by: [alertname, instance]
  group_wait: 1s
  group_interval: 1s
  repeat_interval: 1h
  routes:
    - receiver: teams-local
      matchers: ['alertname="LocalMockOperationsCheck"']
      continue: true
    - receiver: reporter-local
      matchers: ['alertname="LocalMockOperationsCheck"']
receivers:
  - name: quiet
  - name: teams-local
    msteamsv2_configs:
      - webhook_url: http://host.docker.internal:59202/card
        send_resolved: true
        http_config: {follow_redirects: false}
        title: '[LOCAL MOCK {{ .Status }}] LocalMockOperationsCheck'
        text: 'Local-only test. No external Teams request.'
  - name: reporter-local
    webhook_configs:
      - url: http://host.docker.internal:59201/alerts
        send_resolved: true
        http_config:
          follow_redirects: false
          authorization:
            type: Bearer
            credentials_file: /run/secrets/reporter_token
'''
    started = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    alert = {"labels": {"alertname": "LocalMockOperationsCheck", "instance": "local-loopback-only", "severity": "info"},
             "annotations": {"summary": "Local mock integration, not an incident."},
             "startsAt": started.isoformat().replace("+00:00", "Z"),
             "endsAt": (started + dt.timedelta(minutes=5)).isoformat().replace("+00:00", "Z")}
    record = {"started_at": started.isoformat(), "destination": "loopback-only mock sink", "external_messages": 0}
    with tempfile.TemporaryDirectory(prefix="failstop-local-report-") as temporary:
        store = Store(Path(temporary) / "outbox.sqlite3")
        inbox = ReportServer(("127.0.0.1", 59201), store, token)
        sink = ThreadingHTTPServer(("127.0.0.1", 59202), Sink)
        stop = threading.Event()
        config = load_config(ROOT / "deploy/reporting/config.example.json")
        config.update(prometheus_url="http://127.0.0.1:59090", alertmanager_url="http://127.0.0.1:59093")

        def worker():
            while not stop.wait(0.1):
                drain_one(store, config, lambda card: request("http://127.0.0.1:59202/card", payload=card))

        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (inbox, sink)]
        threads.append(threading.Thread(target=worker, daemon=True))
        for thread in threads:
            thread.start()

        def await_report(key):
            for _ in range(40):
                with store.connect() as db:
                    row = db.execute("SELECT status,attempts,report FROM jobs WHERE id=?", (key,)).fetchone()
                if row and row["status"] == "sent":
                    return {"id": key, "status": row["status"], "attempts": row["attempts"], "mode": json.loads(row["report"])["analysis"]["mode"]}
                time.sleep(0.5)
            raise RuntimeError("local_report_timeout")

        try:
            config_file.write_text(selective, encoding="utf-8")
            post("http://127.0.0.1:59093/-/reload", {})
            post("http://127.0.0.1:59093/api/v2/alerts", [alert])
            key, _ = incident_payload({"status": "firing", "alerts": [{**alert, "status": "firing"}]})
            record["firing"] = await_report(key)
            record["duplicate_suppressed"] = post("http://127.0.0.1:59201/alerts", {"status": "firing", "alerts": [{**alert, "status": "firing"}]}, True)["duplicate"]
            alert["endsAt"] = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
            post("http://127.0.0.1:59093/api/v2/alerts", [alert])
            key, _ = incident_payload({"status": "resolved", "alerts": [{**alert, "status": "resolved"}]})
            record["resolved"] = await_report(key)
            key, end = daily_key()
            store.enqueue(key, "daily", {"window_end": end})
            record["daily"] = await_report(key)
            record["daily_duplicate_suppressed"] = not store.enqueue(key, "daily", {"window_end": end})
            for _ in range(20):
                if len(received) == 5:
                    break
                time.sleep(0.5)
            record["cards_received"] = len(received)
            record["adaptive_card_envelopes"] = all(item.get("type") == "message" and item.get("attachments") for item in received)
            record["status"] = "PASS" if len(received) == 5 and record["duplicate_suppressed"] and record["daily_duplicate_suppressed"] else "FAIL"
        finally:
            alert["endsAt"] = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
            post("http://127.0.0.1:59093/api/v2/alerts", [alert])
            config_file.write_text("route:\n  receiver: quiet\nreceivers:\n  - name: quiet\n", encoding="utf-8")
            post("http://127.0.0.1:59093/-/reload", {})
            stop.set()
            for server in (inbox, sink):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=3)
            record["notifications_after_test"] = "quiet"
            record.setdefault("status", "FAIL")
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(record, indent=2), encoding="utf-8")
    print(json.dumps(record))
    if record["status"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError):
        raise SystemExit("local_mock_pipeline_failed; inspect sanitized evidence") from None
