"""Validate DB metric semantics in an isolated PostgreSQL 17 instance.

Pass --docker PATH to also execute the SQL and scrape the actual exporter. Existing
lab containers, volumes and database statistics are never modified.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[3]
QUERIES = ROOT / "harness/prometheus/pg-queries.yaml"
DOCKER = None


def sql_queries():
    text = QUERIES.read_text(encoding="utf-8")
    return {name: "\n".join(line[4:] for line in sql.rstrip().splitlines())
            for name, sql in re.findall(r"^([a-z_]+):\n  query: \|\n((?:    .*\n|\n)+?)  metrics:", text, re.M)}


class QueryContractTests(unittest.TestCase):
    def test_monitoring_queries_are_read_only_and_bounded(self):
        queries = sql_queries()
        self.assertEqual(len(queries), 10)
        for name, sql in queries.items():
            self.assertRegex(sql.lstrip(), r"^(SELECT|WITH)\b", name)
            self.assertNotRegex(sql, r"(?im)^\s*(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|TRUNCATE)\s", name)
        # Raw SQL and identity must never become Prometheus labels.
        labels = re.findall(r'- (\w+):\n\s+usage: "LABEL"', QUERIES.read_text(encoding="utf-8"))
        self.assertTrue(set(labels) <= {"datname", "session_state", "wait_event_type", "command",
                                        "application_name", "state", "sync_state"})

    def test_wal_generation_is_not_lsn_derivative(self):
        query = sql_queries()["pg_ops_wal"]
        self.assertIn("wal_bytes AS generated_bytes_total", query)
        self.assertIn("FROM pg_stat_wal", query)
        self.assertIn("WHERE NOT pg_is_in_recovery()", query)


@unittest.skipUnless(DOCKER, "pass --docker PATH for isolated PostgreSQL/exporter validation")
class PostgresIntegrationTests(unittest.TestCase):
    def test_postgresql17_and_exporter(self):
        name = "failstop-pg-ops-test-" + secrets.token_hex(5)
        db_name, exporter_name = name + "-db", name + "-exporter"
        environment = dict(os.environ, POSTGRES_PASSWORD=secrets.token_urlsafe(32),
                           PG_MONITOR_PASSWORD=secrets.token_urlsafe(32))
        subprocesses = []
        passed = []

        def docker(*args, input=None, check=True):
            result = subprocess.run([DOCKER, *args], input=input, env=environment,
                                    text=True, encoding="utf-8", capture_output=True, timeout=60)
            if check and result.returncode:
                error = result.stderr
                for value in (environment["POSTGRES_PASSWORD"], environment["PG_MONITOR_PASSWORD"]):
                    error = error.replace(value, "[redacted]")
                raise AssertionError(error[:1600])
            return result

        def sql(statement, user="postgres"):
            return docker("exec", "-i", db_name, "psql", "-X", "-v", "ON_ERROR_STOP=1",
                          "-U", user, "-d", "exchange", "-At", input=statement).stdout.strip()

        def rows(name, user="failstop_monitor"):
            query = sql_queries()[name]
            output = sql("SELECT coalesce(json_agg(row_to_json(result)), '[]'::json) FROM (" + query + ") result;", user)
            return json.loads(output)

        def connection(statement):
            process = subprocess.Popen([DOCKER, "exec", "-i", db_name, "psql", "-X", "-v", "ON_ERROR_STOP=1",
                                        "-U", "postgres", "-d", "exchange", "-At"],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       env=environment, text=True, encoding="utf-8")
            process.stdin.write(statement)
            process.stdin.flush()
            subprocesses.append(process)
            return process

        def wait_for(predicate, failure):
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if predicate():
                    return
                time.sleep(0.2)
            logs = docker("logs", "--tail", "12", db_name, check=False)
            self.fail(failure + ": " + logs.stderr[-2400:])

        with tempfile.TemporaryDirectory(prefix="failstop-pg-ops-") as temporary:
            password = Path(temporary) / "monitor-password"
            password.write_text(environment["PG_MONITOR_PASSWORD"], encoding="utf-8")
            try:
                docker("network", "create", name)
                docker("run", "-d", "--name", db_name, "--network", name, "--network-alias", "db",
                       "--memory", "384m", "-e", "POSTGRES_PASSWORD", "-e", "PG_MONITOR_PASSWORD",
                       "-e", "POSTGRES_DB=exchange", "--mount",
                       f"type=bind,source={ROOT / 'deploy/observability/provision-monitor.sql'},target=/provision.sql,readonly",
                       "postgres:17-alpine")
                wait_for(lambda: docker("exec", db_name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "exchange", check=False).returncode == 0,
                         "isolated PostgreSQL did not become ready")
                docker("exec", db_name, "psql", "-X", "-U", "postgres", "-d", "exchange", "-f", "/provision.sql")

                self.assertEqual(rows("pg_ops_sql_collection")[0]["available"], 0)
                self.assertEqual(rows("pg_ops_sql"), [])
                self.assertEqual(rows("pg_ops_sql_tracking"), [])
                passed.append("missing_extension_unavailable_without_query_error")

                sql("ALTER SYSTEM SET shared_preload_libraries='pg_stat_statements';")
                docker("restart", db_name)
                wait_for(lambda: docker("exec", db_name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "exchange", check=False).returncode == 0,
                         "isolated PostgreSQL did not restart with preload")

                sql("CREATE EXTENSION pg_stat_statements; CREATE TABLE fixture(id integer PRIMARY KEY, value integer); "
                    "INSERT INTO fixture VALUES (1, 0); SELECT * FROM fixture; UPDATE fixture SET value=1 WHERE id=1; "
                    "WITH changed AS (UPDATE fixture SET value=value+1 RETURNING *) SELECT * FROM changed;")
                self.assertEqual(rows("pg_ops_sql_collection")[0]["available"], 1)
                totals = {row["command"]: row for row in rows("pg_ops_sql")}
                self.assertEqual(set(totals), {"SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "OTHER"})
                for command in ("SELECT", "INSERT", "UPDATE", "WITH"):
                    self.assertGreaterEqual(totals[command]["calls_total"], 1, command)
                self.assertEqual(totals["WITH"]["calls_total"], 1)
                self.assertEqual(totals["DELETE"]["calls_total"], 0)
                self.assertEqual(len(rows("pg_ops_sql_tracking")), 1)
                passed.append("successful_command_totals_and_mutating_cte_separation")

                before = {row["command"]: row["calls_total"] for row in rows("pg_ops_sql")}
                for _ in range(4):
                    sql("SELECT count(*) FROM pg_stat_activity;", "failstop_monitor")
                after = {row["command"]: row["calls_total"] for row in rows("pg_ops_sql")}
                self.assertEqual(before, after)
                passed.append("exporter_sql_exclusion")

                sql("CREATE ROLE restricted LOGIN;")
                self.assertEqual(rows("pg_ops_sql_collection", "restricted")[0]["available"], 0)
                self.assertEqual(rows("pg_ops_sql", "restricted"), [])
                self.assertEqual(rows("pg_ops_sessions", "restricted"), [])
                restricted_activity = rows("pg_ops_activity", "restricted")[0]
                self.assertEqual(restricted_activity["available"], 0)
                self.assertIsNone(restricted_activity["blocked_sessions"])
                passed.append("insufficient_privilege_never_becomes_healthy_zero")

                sql("REVOKE SELECT ON pg_stat_statements FROM PUBLIC;")
                self.assertEqual(rows("pg_ops_sql_collection")[0]["available"], 0)
                self.assertEqual(rows("pg_ops_sql"), [])
                sql("GRANT SELECT ON pg_stat_statements TO PUBLIC;")
                self.assertEqual(rows("pg_ops_sql_collection")[0]["available"], 1)
                passed.append("extension_view_permission_denied_is_unavailable")

                sql("CREATE SCHEMA private_stats; ALTER EXTENSION pg_stat_statements SET SCHEMA private_stats;")
                self.assertEqual(rows("pg_ops_sql_collection")[0]["available"], 0)
                self.assertEqual(rows("pg_ops_sql"), [])
                sql("ALTER EXTENSION pg_stat_statements SET SCHEMA public;")
                self.assertEqual(rows("pg_ops_sql_collection")[0]["available"], 1)
                passed.append("extension_schema_permission_denied_is_unavailable")

                blocker = connection("SET application_name='ops-test-blocker'; BEGIN; UPDATE fixture SET value=2 WHERE id=1;\n")
                wait_for(lambda: int(sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='ops-test-blocker' AND state='idle in transaction';")) == 1,
                         "blocking fixture did not begin its transaction")
                waiter = connection("SET application_name='ops-test-waiter'; UPDATE fixture SET value=3 WHERE id=1;\n")
                wait_for(lambda: rows("pg_ops_activity")[0]["blocked_sessions"] == 1, "lock wait not detected")
                states = {row["session_state"]: row["count"] for row in rows("pg_ops_sessions")}
                waits = {row["wait_event_type"]: row["count"] for row in rows("pg_ops_session_waits")}
                activity = rows("pg_ops_activity")[0]
                self.assertEqual(states["idle_in_transaction"], 1)
                self.assertEqual(states["active_waiting"], 1)
                self.assertEqual(states["active_no_wait"], 0)
                self.assertEqual(waits["Lock"], 1)
                self.assertGreater(activity["longest_transaction_seconds"], 0)
                self.assertGreater(activity["longest_lock_wait_seconds"], 0)
                self.assertGreater(activity["oldest_idle_transaction_seconds"], 0)
                passed.append("blocked_active_is_waiting_not_cpu_running")
                blocker.stdin.write("ROLLBACK;\n\\q\n")
                blocker.stdin.flush()
                blocker.wait(timeout=10)
                waiter.stdin.write("\\q\n")
                waiter.stdin.flush()
                waiter.wait(timeout=10)
                wait_for(lambda: rows("pg_ops_activity")[0]["blocked_sessions"] == 0, "lock wait did not clear")
                self.assertEqual(rows("pg_ops_activity")[0]["longest_lock_wait_seconds"], 0)
                self.assertTrue(all(row["count"] == 0 for row in rows("pg_ops_sessions")))
                passed.append("released_wait_returns_observed_zero_and_excludes_collector")

                wal = rows("pg_ops_wal")[0]
                self.assertGreater(wal["generated_bytes_total"], 0)
                self.assertGreater(wal["stats_reset_timestamp_seconds"], 0)
                passed.append("standard_wal_bytes_source")

                docker("run", "-d", "--name", exporter_name, "--network", name, "--network-alias", "exporter",
                       "--memory", "128m", "-e", "DATA_SOURCE_URI=db:5432/exchange?sslmode=disable",
                       "-e", "DATA_SOURCE_USER=failstop_monitor", "-e", "DATA_SOURCE_PASS_FILE=/run/secrets/monitor_password",
                       "-e", "PG_EXPORTER_EXTEND_QUERY_PATH=/queries.yml", "--mount",
                       f"type=bind,source={password},target=/run/secrets/monitor_password,readonly", "--mount",
                       f"type=bind,source={QUERIES},target=/queries.yml,readonly",
                       "quay.io/prometheuscommunity/postgres-exporter:v0.17.1")
                metrics = ""

                def scrape():
                    nonlocal metrics
                    result = docker("exec", db_name, "wget", "-qO-", "http://exporter:9187/metrics", check=False)
                    metrics = result.stdout
                    return result.returncode == 0 and "pg_ops_sql_calls_total{" in metrics

                wait_for(scrape, "custom SQL exporter metrics missing")
                self.assertIn("pg_exporter_last_scrape_error 0", metrics)
                for metric in ("pg_ops_sessions_count{", "pg_ops_activity_blocked_sessions{", "pg_ops_wal_generated_bytes_total{",
                               "pg_ops_sql_execution_seconds_total{", "pg_ops_sql_tracking_dealloc_total{"):
                    self.assertIn(metric, metrics)
                self.assertNotRegex(metrics, r'pg_ops_.*(?:queryid|userid|query)=')
                passed.append("postgres_exporter17_custom_metrics_without_raw_sql_labels")

                sql("DROP EXTENSION pg_stat_statements;")
                def reconnected():
                    nonlocal metrics
                    metrics = docker("exec", db_name, "wget", "-qO-", "http://exporter:9187/metrics").stdout
                    return re.search(r'^pg_up 1$', metrics, re.M) is not None

                wait_for(reconnected, "exporter did not reconnect after isolated DB restart")
                self.assertTrue("pg_exporter_last_scrape_error 0" in metrics, "custom query failed without extension")
                self.assertRegex(metrics, r'pg_ops_sql_collection_available\{[^\n]+\} 0')
                self.assertNotRegex(metrics, re.compile(r'^pg_ops_sql_calls_total\{', re.M))
                passed.append("missing_extension_scrape_preserves_other_metrics")

                sql("CREATE EXTENSION pg_stat_statements;")
                sql("ALTER SYSTEM RESET shared_preload_libraries;")
                docker("restart", db_name)
                wait_for(lambda: docker("exec", db_name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "exchange", check=False).returncode == 0,
                         "isolated PostgreSQL did not restart without preload")
                self.assertEqual(rows("pg_ops_sql_collection")[0]["available"], 0)
                self.assertEqual(rows("pg_ops_sql"), [])
                self.assertEqual(rows("pg_ops_sql_tracking"), [])
                wait_for(reconnected, "exporter did not reconnect after isolated DB restart")
                self.assertTrue("pg_exporter_last_scrape_error 0" in metrics, "custom query failed without preload")
                self.assertRegex(metrics, r'pg_ops_sql_collection_available\{[^\n]+\} 0')
                self.assertNotRegex(metrics, re.compile(r'^pg_ops_sql_calls_total\{', re.M))
                passed.append("installed_without_preload_never_executes_unavailable_extension")
                print(json.dumps({"status": "PASS", "scenarios": passed}))
            finally:
                for process in subprocesses:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=5)
                    for stream in (process.stdin, process.stdout, process.stderr):
                        if stream:
                            stream.close()
                docker("rm", "-f", "-v", exporter_name, db_name, check=False)
                docker("network", "rm", name, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--docker")
    arguments, remaining = parser.parse_known_args()
    DOCKER = arguments.docker
    if DOCKER:
        PostgresIntegrationTests.__unittest_skip__ = False
    unittest.main(argv=[__file__, *remaining])
