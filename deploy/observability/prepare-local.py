"""Prepare ignored local integration files; do not publish or send notifications."""
import json
import os
from pathlib import Path
import secrets
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def local_targets():
    entries = {
        "hosts": [("host.docker.internal:59188", "operations-lab", "lab", "ubuntu-lab")],
        "reporters": [("host.docker.internal:59188", "operations-lab", "operations", "reporter")],
        "apps": [("host.docker.internal:58180", "exchange", "app", "local-spring")],
        "databases": [("postgres-primary:9187", "exchange", "data", "local-db-primary"),
                      ("postgres-standby:9187", "exchange", "standby", "local-db-standby")],
        "probes": [("http://host.docker.internal:58180/actuator/health/read", "exchange", "probe", "local-spring-probe")],
    }
    return {group: [{"targets": [address], "labels": {"customer": customer, "role": role, "node": node,
                                                    "service": group, "environment": "local-lab"}}
                    for address, customer, role, node in items] for group, items in entries.items()}


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    state = HERE / ".state"
    state.mkdir(mode=0o700, exist_ok=True)

    def save(name, data):
        target = state / name
        target.write_text(data, encoding="utf-8")
        target.chmod(0o600)
        return target.as_posix()

    token = subprocess.run(["wsl.exe", "-d", "Ubuntu", "-u", "root", "--", "cat", "/etc/failstop/reporter-token"],
                           text=True, encoding="utf-8", capture_output=True, check=True).stdout.strip()
    webhook = subprocess.run(["wsl.exe", "-d", "Ubuntu", "-u", "root", "--", "cat", "/etc/failstop/teams-webhook"],
                             text=True, encoding="utf-8", capture_output=True, check=True).stdout.strip()
    reporter_file = save("reporter-token", token + "\n")
    teams_file = save("teams-webhook", webhook + "\n")
    grafana_file = state / "grafana-password"
    if not grafana_file.exists():
        save("grafana-password", secrets.token_urlsafe(32) + "\n")
    monitor_file = state / "monitor-password"
    if not monitor_file.exists():
        save("monitor-password", secrets.token_urlsafe(32) + "\n")
    environment = dict(os.environ, PG_MONITOR_PASSWORD=monitor_file.read_text().strip())
    provision = subprocess.run([args.docker, "exec", "-i", "-e", "PG_MONITOR_PASSWORD", "failstop-ops-lab-standby-1",
                                "psql", "-X", "-U", "ops_admin", "-d", "exchange"],
                               input=(HERE / "provision-monitor.sql").read_text(), env=environment,
                               text=True, encoding="utf-8", capture_output=True)
    if provision.returncode:
        raise ValueError("local_monitor_provision_failed")
    quiet = save("alertmanager-quiet.yml", "route:\n  receiver: quiet\nreceivers:\n  - name: quiet\n")
    prometheus = (HERE / "prometheus.yml").read_text(encoding="utf-8")
    prometheus = prometheus.replace("  external_labels:\n", "  external_labels:\n    environment: local-lab\n")
    prometheus = prometheus.replace("  - job_name: linux-host\n", "  - job_name: linux-host\n    metrics_path: /node/metrics\n")
    prometheus = prometheus.replace("  - job_name: operations-reporter\n", "  - job_name: operations-reporter\n    metrics_path: /reporter/metrics\n")
    prom_file = save("prometheus-local.yml", prometheus)
    target_dir = state / "targets"
    target_dir.mkdir(exist_ok=True)
    for group, entries in local_targets().items():
        (target_dir / (group + ".json")).write_text(json.dumps(entries), encoding="utf-8")
    local_compose = "services:\n"
    for name, host in (("postgres-primary", "standby"), ("postgres-standby", "rejoined")):
        local_compose += f"""  {name}:
    image: quay.io/prometheuscommunity/postgres-exporter:v0.17.1
    environment:
      DATA_SOURCE_URI: {host}:5432/exchange?sslmode=disable
      DATA_SOURCE_USER: failstop_monitor
      DATA_SOURCE_PASS_FILE: /run/secrets/monitor_password
      PG_EXPORTER_EXTEND_QUERY_PATH: /queries.yml
    volumes:
      - {monitor_file.as_posix()}:/run/secrets/monitor_password:ro
      - {(ROOT / 'harness/prometheus/pg-queries.yaml').as_posix()}:/queries.yml:ro
    networks: [default, lab]
    restart: unless-stopped
    mem_limit: 128m
    logging:
      driver: json-file
      options: {{max-size: 10m, max-file: '3'}}
"""
    local_compose += "networks:\n  lab:\n    external: true\n    name: failstop-ops-lab_default\n"
    save("compose.local.yml", local_compose)
    save("compose.env", f"REPORTER_TOKEN_FILE={reporter_file}\nTEAMS_WEBHOOK_FILE={teams_file}\n"
         f"GRAFANA_PASSWORD_FILE={grafana_file.as_posix()}\nOBS_TARGETS_DIR={target_dir.as_posix()}\n"
         f"ALERTMANAGER_CONFIG={quiet}\nPROMETHEUS_CONFIG={prom_file}\nOBS_BIND_IP=127.0.0.1\n")
    print(json.dumps({"status": "prepared", "teams_notifications": "muted", "secret_values_printed": False}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError):
        raise SystemExit("local_observability_preparation_failed") from None
