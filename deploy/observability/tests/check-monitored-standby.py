"""Stop only the registered local replica and verify real Prometheus-to-Teams delivery."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
REPLICA = "failstop-ops-lab-rejoined-1"
PRIMARY = "failstop-ops-lab-standby-1"
INSTANCE = "postgres-standby:9187"
ALERT = "DatabaseConnectionFailed"
DOCKER = os.environ.get("DOCKER_BIN", "docker")


def command(arguments, timeout=30):
    result = subprocess.run(arguments, capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"command_failed_exit_{result.returncode}")
    return result.stdout.strip()


def query(expression):
    url = "http://127.0.0.1:59090/api/v1/query?" + urllib.parse.urlencode({"query": expression})
    with urllib.request.urlopen(url, timeout=10) as response:
        result = json.load(response)
    if result["status"] != "success":
        raise RuntimeError("prometheus_query_failed")
    return result["data"]["result"]


def sql(container, expression):
    return command([DOCKER, "exec", container, "psql", "-X", "-qAt", "-U", "ops_admin", "-d", "exchange", "-c", expression])


def wait(probe, description, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value:
            print("PASS: " + description, flush=True)
            return value
        time.sleep(5)
    raise RuntimeError("timeout_" + description.replace(" ", "_"))


def deliveries(since):
    return json.loads(command(["wsl.exe", "-d", "Ubuntu", "-u", "root", "--", "python3",
                               "/mnt/d/dev/failstop/deploy/reporting/delivery-audit.py",
                               "--since", str(since), "--instance", INSTANCE, "--alertname", ALERT]))


def delivered(since, transition):
    rows = deliveries(since)
    if any(row["status"] == "dead" for row in rows):
        raise RuntimeError("incident_delivery_failed")
    return next((row for row in rows if row["status"] == "sent" and transition in row["transitions"]), None)


def replica_identity(container):
    labels = container["Config"]["Labels"]
    return (container["Name"] == "/" + REPLICA and container["State"]["Running"]
            and labels.get("com.docker.compose.project") == "failstop-ops-lab"
            and labels.get("com.docker.compose.service") == "rejoined")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Interrupt the local replica and use the already enabled Teams routes")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.run:
        print(json.dumps({"replica": REPLICA, "primary": PRIMARY, "stop_wait_budget_seconds": 170,
                          "command_timeout_seconds": 30,
                          "path": "postgres_exporter -> Prometheus -> Alertmanager -> reporter -> Teams",
                          "sends_via_existing_routes": True, "executed": False}))
        return
    # Fixed lab identity and recovery mode prevent accidentally stopping the writable DB.
    inspected = json.loads(command([DOCKER, "inspect", "--format", '{{json .}}', REPLICA]))
    if not replica_identity(inspected) or sql(REPLICA, "SELECT pg_is_in_recovery();") != "t":
        raise RuntimeError("registered_local_replica_identity_required")
    if sql(PRIMARY, "SELECT pg_is_in_recovery();") != "f":
        raise RuntimeError("expected_primary_missing")
    selector = f'{{job="postgres",instance="{INSTANCE}",customer="exchange",environment="local-lab",role="standby"}}'
    if len(query("pg_up" + selector + " == 1")) != 1:
        raise RuntimeError("standby_must_be_registered_and_scraped")
    if query(f'ALERTS{{alertname="{ALERT}",instance="{INSTANCE}"}}'):
        raise RuntimeError("standby_already_alerting")
    status = json.loads(command(["wsl.exe", "-d", "Ubuntu", "-u", "root", "--", "python3",
                                 "/mnt/d/dev/failstop/deploy/reporting/run-installed.py", "status"]))
    if not status.get("delivery_enabled") or any(status["counts"].get(key, 0) for key in ("dead", "queued", "pending", "sending")):
        raise RuntimeError("reporter_must_be_ready_without_backlog")
    started = time.time()
    result = {"status": "FAIL", "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
              "customer": "exchange", "environment": "local-lab", "replica": REPLICA,
              "instance": INSTANCE, "alertname": ALERT, "primary_interrupted": False,
              "aws_verified": False, "teams_channel_visible": "not_verified"}
    stopped = False
    try:
        try:
            stopped = True
            command([DOCKER, "stop", "--time", "10", REPLICA])
            stop_time = time.monotonic()
            print("Replica stopped; waiting for the configured alert duration and notification grouping", flush=True)
            result["failure_samples"] = wait(lambda: query("pg_up" + selector + " == 0"), "Prometheus observed DB failure", 40)
            result["firing_alert"] = wait(lambda: query(f'ALERTS{{alertname="{ALERT}",instance="{INSTANCE}",alertstate="firing"}}'),
                                           "Prometheus firing alert", max(1, 130 - (time.monotonic() - stop_time)))
            result["firing_report"] = wait(lambda: delivered(started, "firing"), "incident webhook accepted by Teams",
                                            max(1, 170 - (time.monotonic() - stop_time)))
        finally:
            if stopped:
                command([DOCKER, "start", REPLICA])
                result["replica_restart_requested"] = True
                print("Replica restarted; checking streaming and resolved delivery", flush=True)
        result["replication_streaming"] = wait(lambda: sql(PRIMARY, "SELECT count(*) FROM pg_stat_replication WHERE state='streaming';") == "1",
                                                "replica streaming", 90)
        result["recovery_samples"] = wait(lambda: query("pg_up" + selector + " == 1"), "Prometheus observed DB recovery", 60)
        result["resolved_report"] = wait(lambda: delivered(started, "resolved"), "resolved webhook accepted by Teams", 420)
        result["report_rows"] = deliveries(started)
        if len(result["report_rows"]) != 2 or any(row["scope"] != {"customer": "exchange", "environment": "local-lab"} for row in result["report_rows"]):
            raise RuntimeError("incident_scope_or_deduplication_failed")
        with urllib.request.urlopen("http://127.0.0.1:58180/actuator/health/readiness", timeout=10) as response:
            result["spring_ready"] = json.load(response)["status"] == "UP"
        if not result["spring_ready"]:
            raise RuntimeError("spring_not_ready")
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = str(error)[:200]
        raise
    finally:
        result["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("PASS: real metric failure, firing and resolved reports; primary and Spring remain available", flush=True)


if __name__ == "__main__":
    main()
