"""Run a disposable PostgreSQL/exporter probe without touching existing databases."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    name = "failstop-exporter-check-" + secrets.token_hex(4)
    db_name, exporter_name = name + "-db", name + "-exporter"
    environment = dict(os.environ, POSTGRES_PASSWORD=secrets.token_urlsafe(32), PG_MONITOR_PASSWORD=secrets.token_urlsafe(32))

    def docker(*arguments, check=True):
        result = subprocess.run([args.docker, *arguments], env=environment, text=True, encoding="utf-8", capture_output=True, timeout=60)
        if check and result.returncode:
            safe_error = result.stderr.replace(environment["POSTGRES_PASSWORD"], "[redacted]").replace(environment["PG_MONITOR_PASSWORD"], "[redacted]")
            raise RuntimeError("isolated_" + arguments[0] + "_failed: " + safe_error[:800])
        return result

    with tempfile.TemporaryDirectory(prefix="failstop-exporter-") as temporary:
        password = Path(temporary) / "monitor-password"
        password.write_text(environment["PG_MONITOR_PASSWORD"], encoding="utf-8")
        try:
            docker("network", "create", name)
            docker("run", "-d", "--name", db_name, "--network", name, "--network-alias", "db",
                   "--memory", "256m", "-e", "POSTGRES_PASSWORD", "-e", "PG_MONITOR_PASSWORD", "-e", "POSTGRES_DB=exchange",
                   "--mount", f"type=bind,source={root / 'deploy/observability/provision-monitor.sql'},target=/provision.sql,readonly",
                   "postgres:17-alpine")
            for _ in range(60):
                if docker("exec", db_name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "exchange", check=False).returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("isolated_db_not_ready")
            docker("exec", db_name, "psql", "-X", "-U", "postgres", "-d", "exchange", "-f", "/provision.sql")
            docker("exec", db_name, "psql", "-X", "-U", "postgres", "-d", "exchange", "-f", "/provision.sql")
            docker("exec", db_name, "psql", "-X", "-U", "postgres", "-d", "exchange", "-c", "CREATE TABLE private_orders(id integer);")
            allowed = docker("exec", db_name, "psql", "-X", "-U", "failstop_monitor", "-d", "exchange", "-Atc",
                             "SELECT count(*) FROM pg_ls_waldir();").stdout.strip()
            if not allowed or int(allowed) < 1:
                raise RuntimeError("monitor_has_no_wal_evidence")
            for sql in ("CREATE TABLE forbidden(id integer);", "SELECT * FROM private_orders;", "SET default_transaction_read_only=off; INSERT INTO private_orders VALUES(1);"):
                denied = docker("exec", db_name, "psql", "-X", "-U", "failstop_monitor", "-d", "exchange", "-c", sql, check=False)
                if denied.returncode == 0:
                    raise RuntimeError("monitor_privilege_boundary_failed")
            docker("run", "-d", "--name", exporter_name, "--network", name, "--network-alias", "exporter", "--memory", "128m",
                   "-e", "DATA_SOURCE_URI=db:5432/exchange?sslmode=disable", "-e", "DATA_SOURCE_USER=failstop_monitor",
                   "-e", "DATA_SOURCE_PASS_FILE=/run/secrets/monitor_password", "-e", "PG_EXPORTER_EXTEND_QUERY_PATH=/queries.yml",
                   "--mount", f"type=bind,source={password},target=/run/secrets/monitor_password,readonly",
                   "--mount", f"type=bind,source={root / 'harness/prometheus/pg-queries.yaml'},target=/queries.yml,readonly",
                   "quay.io/prometheuscommunity/postgres-exporter:v0.17.1")
            for _ in range(30):
                metrics = docker("exec", db_name, "wget", "-qO-", "http://exporter:9187/metrics", check=False)
                if metrics.returncode == 0 and 'pg_up 1' in metrics.stdout:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("exporter_metrics_not_ready")
            if not any(line.startswith("pg_wal_position_dir_bytes{") for line in metrics.stdout.splitlines()):
                raise RuntimeError("wal_metrics_missing")
            if 'pg_exporter_last_scrape_error 1' in metrics.stdout:
                raise RuntimeError("exporter_query_failed")
            print(json.dumps({"status": "PASS", "provision_idempotent": True, "denied_operations": 3,
                              "pg_up": 1, "wal_metrics": True, "custom_query_error": False}))
        finally:
            docker("rm", "-f", exporter_name, db_name, check=False)
            docker("network", "rm", name, check=False)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"status": "FAIL", "error": str(error) if isinstance(error, RuntimeError) else type(error).__name__}))
        raise SystemExit(1) from None
