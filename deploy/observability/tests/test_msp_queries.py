import json
import os
import re
import subprocess
import unittest
import urllib.parse
import urllib.request

from msp_queries import DB_REPLICATION, Q, SERVICE_PATH


def bind(expression, customer="exchange", environment="local-lab"):
    return (expression.replace("$customer", customer).replace("$environment", environment)
            .replace("$__rate_interval", "5m").replace("$__range", "10m"))


def sample(name, values, job="exchange-api", role="app", instance="app:8081", node="app-1", **extra):
    labels = {"customer": "exchange", "environment": "local-lab", "job": job, "role": role,
              "instance": instance, "node": node, **extra}
    return {"series": name + "{" + ",".join(key + "=" + json.dumps(value) for key, value in labels.items()) + "}",
            "values": values}


def semantic_cases():
    count = "http_server_requests_seconds_count"
    bucket = "http_server_requests_seconds_bucket"
    labels = {"uri": "/api/markets", "status": "200", "method": "GET"}
    up = sample("up", "1+0x10")
    business = sample(count, "0+60x10", **labels)
    histogram = [sample(bucket, "0+60x10", **labels, le=bound) for bound in ("1", "+Inf")]
    tests = []

    def case(name, inputs, expected, exact=None):
        expressions = [{"expr": "sum(" + bind(Q[key]) + ")", "eval_time": "10m",
                        "exp_samples": [] if result is None else [{"labels": "{}", "value": result}]}
                       for key, result in expected.items()]
        expressions.extend(exact or [])
        tests.append({"name": name, "interval": "1m", "input_series": inputs, "promql_expr_test": expressions})

    case("business observations remain scoped and absent error series can be zero", [up, business, *histogram],
         {"rps": 1, "error_rate": 0, "latency_p95": 950})
    case("missing counters never become healthy zero", [up], {"rps": None, "error_rate": None, "latency_p95": None})
    case("idle requests retain zero throughput without inventing latency", [up, sample(count, "300+0x10", **labels)],
         {"rps": 0, "error_rate": None, "latency_p95": None})
    case("management traffic is excluded", [up, sample(count, "0+60x10", **{**labels, "uri": "/actuator/prometheus"})],
         {"rps": None, "error_rate": None})
    case("another customer is excluded", [up, sample(count, "0+60x10", **labels, customer="other")], {"rps": None})
    case("AWS samples cannot replace the local environment", [up, sample(count, "0+60x10", **labels, environment="aws")], {"rps": None})
    case("fresh exporter failure invalidates old counters", [sample("up", "1+0x9 0"), business, *histogram],
         {"app_up": 0, "rps": None, "latency_p95": None})
    case("stale scrape success is not current success", [sample("up", "1+0x8 _ _"), business],
         {"app_up": None, "rps": None})
    case("unobserved registered application cannot hide behind healthy peer", [up, sample("exchange_node_serviceable", "1+0x10"),
         sample("up", "0+0x10", instance="app-2:8081", node="app-2")], {"app_status": None})
    case("stale registered application cannot disappear from completeness", [up, sample("exchange_node_serviceable", "1+0x10"),
         sample("up", "1+0x8 _ _", instance="app-2:8081", node="app-2"),
         sample("exchange_node_serviceable", "1+0x8 _ _", instance="app-2:8081", node="app-2")], {"app_status": None})
    case("stale peer remains in inventory and lowers collection ratio", [up,
         sample("up", "1+0x8 _ _", instance="app-2:8081", node="app-2")], {"target_count": 2, "collection_ratio": 50})
    case("all stale targets retain inventory without inventing collection response", [sample("up", "1+0x8 _ _")],
         {"target_count": 1, "collection_ratio": None})
    case("customer host registration and fresh responses are separate", [
         sample("up", "1+0x10", job="linux-host", instance="host-1:9100"),
         sample("up", "1+0x8 _ _", job="linux-host", instance="host-2:9100"),
         sample("up", "0+0x10", job="linux-host", instance="host-3:9100"),
         sample("up", "1+0x10", job="linux-host", role="observer", instance="observer:9100")],
         {"host_count": 3, "host_fresh_count": 2, "host_up_count": 1, "target_count": 3, "collection_ratio": 100 / 3})
    case("measured service refusal survives a missing peer", [up, sample("exchange_node_serviceable", "0+0x10"),
         sample("up", "0+0x10", instance="app-2:8081", node="app-2")], {"app_status": 0})

    db = {"job": "postgres", "role": "standby", "instance": "old-standby:9187", "node": "db-2"}
    replica = {"job": "postgres", "role": "data", "instance": "old-primary:9187", "node": "db-1"}
    primary_inputs = [sample("up", "1+0x10", **db), sample("pg_up", "1+0x10", **db),
                      sample("pg_exporter_last_scrape_error", "0+0x10", **db),
                      sample("pg_wal_position_in_recovery", "0+0x10", **db)]
    replica_inputs = [sample("up", "1+0x10", **replica), sample("pg_up", "1+0x10", **replica),
                      sample("pg_exporter_last_scrape_error", "0+0x10", **replica),
                      sample("pg_wal_position_in_recovery", "1+0x10", **replica)]
    case("promoted standby is classified by runtime recovery mode", [*primary_inputs, *replica_inputs],
         {"primary_status": 1, "standby_status": 1, "primary_count": 1, "standby_count": 1,
          "primary_topology_status": 1, "standby_topology_status": 1}, exact=[
             {"expr": bind(Q["primary_status"]), "eval_time": "10m", "exp_samples": [{"labels": primary_inputs[1]["series"], "value": 1}]},
             {"expr": bind(Q["standby_status"]), "eval_time": "10m", "exp_samples": [{"labels": replica_inputs[1]["series"], "value": 1}]},
         ])
    dual_primary = [*primary_inputs, *replica_inputs[:3], sample("pg_wal_position_in_recovery", "0+0x10", **replica)]
    case("two connected primaries are a topology failure", dual_primary,
         {"primary_count": 2, "standby_count": 0, "primary_topology_status": 0, "standby_topology_status": 0})
    case("two measured primaries remain a failure with an unknown third peer", [*dual_primary,
         sample("up", "0+0x10", job="postgres", role="data", instance="db-3:9187", node="db-3")],
         {"primary_count": 2, "primary_topology_status": 0, "standby_topology_status": None})
    case("all observed roles are standby so primary topology is absent", replica_inputs,
         {"primary_count": 0, "standby_count": 1, "primary_topology_status": 0, "standby_topology_status": 1})
    case("complete primary without standby is a redundancy failure", primary_inputs,
         {"primary_count": 1, "standby_count": 0, "primary_topology_status": 1, "standby_topology_status": 0})
    case("unobserved runtime role prevents healthy primary topology", [*primary_inputs, *replica_inputs[:3]],
         {"primary_count": 1, "primary_topology_status": None, "standby_topology_status": None})
    case("stale registered role prevents healthy DB topology", [*primary_inputs,
         sample("up", "1+0x8 _ _", **replica), sample("pg_up", "1+0x8 _ _", **replica),
         sample("pg_wal_position_in_recovery", "1+0x8 _ _", **replica)],
         {"primary_topology_status": None, "standby_topology_status": None})
    case("empty DB inventory cannot produce a role count or healthy topology", [],
         {"primary_count": None, "standby_count": None, "primary_topology_status": None, "standby_topology_status": None})
    case("unknown recovery values never satisfy complete role evidence", [*primary_inputs, *replica_inputs[:3],
         sample("pg_wal_position_in_recovery", "2+0x10", **replica)],
         {"primary_topology_status": None, "standby_topology_status": None})
    case("DB connection failure is different from exporter success", [sample("up", "1+0x10", **db), sample("pg_up", "0+0x10", **db),
         sample("pg_stat_database_numbackends", "11+0x10", **db, datname="exchange")], {"db_up": 0, "db_connections": None, "primary_status": None})
    case("missing pg_up never becomes a healthy database", [sample("up", "1+0x10", **db),
         sample("pg_stat_database_numbackends", "11+0x10", **db, datname="exchange")], {"db_up": None, "db_connections": None})
    case("missing replica cannot hide behind a connected primary", [*primary_inputs, sample("up", "0+0x10", **replica)], {"db_status": None})
    case("streaming replication is measured on the actual primary", [*primary_inputs,
         sample("pg_replication_client_sent_replay_bytes", "0+0x10", **db, state="streaming")], {"replication_status": 1, "wal_gap": 0})
    case("present non-streaming replication is a failure", [*primary_inputs,
         sample("pg_replication_client_sent_replay_bytes", "2+0x10", **db, state="catchup")], {"replication_status": 0})
    case("absent replication is not zero gap", primary_inputs, {"replication_status": None, "wal_gap": None})
    case("empty monitoring inventory cannot produce zero alerts", [], {"alert_count": None, "target_count": None})
    case("known inventory with no firing alert has zero alerts", [up], {"alert_count": 0, "target_count": 1})
    case("observer host data is excluded", [sample("up", "1+0x10", job="linux-host", role="observer", instance="observer:9100"),
         sample("node_memory_MemAvailable_bytes", "100+0x10", job="linux-host", role="observer", instance="observer:9100"),
         sample("node_memory_MemTotal_bytes", "100+0x10", job="linux-host", role="observer", instance="observer:9100")], {"host_memory": None})

    statuses = [(100, 60), (200, 120), (201, 180), (302, 240), (404, 300), (503, 360)]
    case("literal HTTP code labels survive Grafana variable interpolation", [up, *[
        sample(count, f"0+{increment}x10", **{**labels, "status": str(status)}) for status, increment in statuses]], {}, exact=[
        {"expr": bind(Q["http_codes"]), "eval_time": "10m", "exp_samples": [
            {"labels": '{code="' + code + '"}', "value": result}
            for code, result in (("1xx", 1), ("2xx", 5), ("3xx", 4), ("4xx", 5), ("5xx", 6))]}])

    case("rejected traffic remains visible while successful latency has no samples", [up,
         sample(count, "0+120x10", **{**labels, "status": "429"}),
         *[sample(bucket, "0+120x10", **{**labels, "status": "429"}, le=bound) for bound in (".01", "+Inf")]],
         {"success_rps": 0, "rate_limit_rps": 2, "success_latency_p95": None,
          "endpoint_success_latency_p95": None})
    case("fast rejections never lower successful latency", [up, business, *histogram,
         sample(count, "0+600x10", **{**labels, "status": "429"}),
         *[sample(bucket, "0+600x10", **{**labels, "status": "429"}, le=bound) for bound in (".01", "+Inf")]],
         {"success_rps": 1, "rate_limit_rps": 10, "success_latency_p95": 950,
          "endpoint_success_latency_p95": 950})
    case("missing HTTP collection cannot invent successful or rejected traffic", [up],
         {"success_rps": None, "rate_limit_rps": None})

    sql_scope = {**db, "datname": "exchange"}
    availability = sample("pg_ops_sql_collection_available", "1+0x10", **sql_scope)
    stable_dealloc = sample("pg_ops_sql_tracking_dealloc_total", "0+0x10", **sql_scope)
    stable_reset = sample("pg_ops_sql_tracking_stats_reset_timestamp_seconds", "100+0x10", **sql_scope)
    calls = sample("pg_ops_sql_calls_total", "0+60x10", **sql_scope, command="SELECT")
    duration = sample("pg_ops_sql_execution_seconds_total", "0+60x10", **sql_scope, command="SELECT")
    case("SQL call rate and average duration use independent execution counters",
         [*primary_inputs, availability, stable_dealloc, stable_reset, calls, duration],
         {"sql_calls": 1, "sql_mean_ms": 1000})
    peer_scope = {**replica, "datname": "exchange"}
    peer_sql = [*replica_inputs,
                sample("pg_ops_sql_collection_available", "1+0x10", **peer_scope),
                sample("pg_ops_sql_tracking_dealloc_total", "0+0x10", **peer_scope),
                sample("pg_ops_sql_tracking_stats_reset_timestamp_seconds", "100+0x10", **peer_scope),
                sample("pg_ops_sql_calls_total", "0+180x10", **peer_scope, command="SELECT")]
    case("fleet SQL mean is weighted by valid call counts", [*primary_inputs, availability,
         stable_dealloc, stable_reset, calls, duration, *peer_sql,
         sample("pg_ops_sql_execution_seconds_total", "0+360x10", **peer_scope, command="SELECT")],
         {"sql_calls_by_command": 4, "sql_calls_overview": 4, "sql_mean_by_command": 1750,
          "sql_mean_overall": 1750, "sql_collection_status": 1})
    case("invalid duration on one node cannot dilute the fleet SQL mean", [*primary_inputs, availability,
         stable_dealloc, stable_reset, calls, duration, *peer_sql,
         sample("pg_ops_sql_execution_seconds_total", "600+0x8 20 80", **peer_scope, command="SELECT")],
         {"sql_calls_by_command": 4, "sql_mean_by_command": 1000, "sql_mean_overall": 1000})
    case("missing peer SQL capability prevents complete collection status", [*primary_inputs,
         availability, *replica_inputs], {"sql_collection_status": None})
    case("SQL extension absence cannot look like zero calls", [*primary_inputs,
         sample("pg_ops_sql_collection_available", "0+0x10", **sql_scope), stable_dealloc, stable_reset, calls, duration],
         {"sql_available": 0, "sql_calls": None, "sql_mean_ms": None})
    case("SQL entry eviction invalidates rates rather than manufacturing a reset spike", [*primary_inputs,
         availability, sample("pg_ops_sql_tracking_dealloc_total", "0+1x10", **sql_scope), stable_reset, calls, duration],
         {"sql_calls": None, "sql_mean_ms": None})
    case("SQL reset invalidates the affected measurement window", [*primary_inputs,
         availability, stable_dealloc, sample("pg_ops_sql_tracking_stats_reset_timestamp_seconds", "100+0x8 200 200", **sql_scope), calls, duration],
         {"sql_calls": None, "sql_mean_ms": None})
    case("SQL collection without activity has zero calls but no execution duration", [*primary_inputs,
         availability, stable_dealloc, stable_reset,
         sample("pg_ops_sql_calls_total", "0+0x10", **sql_scope, command="SELECT"),
         sample("pg_ops_sql_execution_seconds_total", "0+0x10", **sql_scope, command="SELECT")],
         {"sql_calls": 0, "sql_mean_ms": None})
    case("partial SQL counter reset cannot manufacture a command rate", [*primary_inputs,
         availability, stable_dealloc, stable_reset,
         sample("pg_ops_sql_calls_total", "600+0x8 20 80", **sql_scope, command="SELECT"), duration],
         {"sql_calls": None, "sql_mean_ms": None})
    case("execution time reset cannot manufacture average duration", [*primary_inputs,
         availability, stable_dealloc, stable_reset, calls,
         sample("pg_ops_sql_execution_seconds_total", "600+0x8 20 80", **sql_scope, command="SELECT")],
         {"sql_calls": 1, "sql_mean_ms": None})
    case("active session count sums waiting and no-wait groups without idle", [*primary_inputs,
         sample("pg_ops_sessions_count", "2+0x10", **sql_scope, session_state="active_no_wait"),
         sample("pg_ops_sessions_count", "3+0x10", **sql_scope, session_state="active_waiting"),
         sample("pg_ops_sessions_count", "8+0x10", **sql_scope, session_state="idle")], {"db_active": 5})
    case("wait overview retains lock and IO while grouping other wait types", [*primary_inputs,
         *[sample("pg_ops_session_waits_count", f"{count}+0x10", **sql_scope, wait_event_type=event)
           for event, count in (("Lock", 2), ("IO", 3), ("LWLock", 4), ("Client", 5))]], {}, exact=[
             {"expr": bind(Q["session_wait_totals"]), "eval_time": "10m", "exp_samples": [
                 {"labels": '{wait_event_type="' + event + '"}', "value": count}
                 for event, count in (("Lock", 2), ("IO", 3), ("Other", 9))]}])
    case("exporter query failure invalidates historic DB counters while connection remains visible", [
         *[item for item in primary_inputs if not item['series'].startswith('pg_exporter_last_scrape_error')],
         sample("pg_exporter_last_scrape_error", "0+0x9 1", **db), availability, stable_dealloc, stable_reset, calls,
         sample("pg_stat_database_xact_commit", "0+60x10", **sql_scope)],
         {"db_up": 1, "db_commit_tps": None, "sql_calls": None, "sql_available": None})
    for path in ("cloudfront", "alb"):
        case(path + " cannot be inferred from a local edge probe", [up, sample("up", "1+0x10", job="blackbox-http", role="probe"),
             sample("probe_success", "1+0x10", job="blackbox-http", role="probe", service=path)], {}, exact=[
            {"expr": bind(SERVICE_PATH[path]), "eval_time": "10m", "exp_samples": []}])
    return {"rule_files": [], "evaluation_interval": "1m", "fuzzy_compare": True, "tests": tests}


class MspQueryTests(unittest.TestCase):
    def test_every_selector_retains_customer_environment_and_customer_roles(self):
        expressions = {**Q, **{"path." + key: value for key, value in SERVICE_PATH.items()},
                       **{"replication." + key: value for key, value in DB_REPLICATION.items()}}
        for name, expression in expressions.items():
            for labels in re.findall(r"\{([^{}]*)\}", expression):
                with self.subTest(query=name, labels=labels):
                    self.assertIn('customer="$customer"', labels)
                    self.assertIn('environment="$environment"', labels)
                    self.assertIn('role=~"app|reader|data|standby|probe"', labels)
            self.assertNotIn("vector(0)", expression)

    def test_grafana_macros_are_limited_and_code_labels_are_literal(self):
        allowed = {"$customer", "$environment", "$__range", "$__rate_interval"}
        for name, expression in Q.items():
            with self.subTest(query=name):
                self.assertLessEqual(set(re.findall(r"\$[A-Za-z_][A-Za-z0-9_]*", expression)), allowed)
                self.assertNotRegex(expression, r"\$\{?\d")
        for code in range(1, 6):
            self.assertIn(f'"code","{code}xx"', Q["http_codes"])
        self.assertIn("[$__range]", Q["requests_by_uri"])
        self.assertIn("[$__rate_interval]", Q["rps"])

    def test_jvm_basis_roles_and_missing_sources_remain_explicit(self):
        self.assertIn("jvm_memory_committed_bytes", Q["jvm_heap"])
        self.assertNotIn("jvm_memory_max_bytes", Q["jvm_heap"])
        self.assertIn("pg_wal_position_in_recovery", Q["primary_status"])
        self.assertNotIn('role="data"', Q["primary_status"])
        self.assertNotIn('role="standby"', Q["standby_status"])
        self.assertIn("timestamp(up", Q["db_up"])
        self.assertIn("pg_up", Q["db_connections"])
        self.assertIn('environment="aws"', SERVICE_PATH["cloudfront"])
        self.assertIn('environment="aws"', SERVICE_PATH["alb"])

    @unittest.skipUnless(os.getenv("MSP_PROMTOOL_CONTAINER"), "set MSP_PROMTOOL_CONTAINER for semantic PromQL tests")
    def test_semantic_promql_cases(self):
        result = subprocess.run([os.getenv("DOCKER_BIN", "docker"), "exec", "-i", os.environ["MSP_PROMTOOL_CONTAINER"],
                                 "promtool", "test", "rules", "/dev/stdin"], input=json.dumps(semantic_cases()),
                                text=True, capture_output=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipUnless(os.getenv("MSP_PROMETHEUS_URL"), "set MSP_PROMETHEUS_URL for read-only live queries")
    def test_live_queries_parse_and_return_only_finite_values(self):
        base = os.environ["MSP_PROMETHEUS_URL"].rstrip("/")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        expressions = {**Q, **{"path." + key: value for key, value in SERVICE_PATH.items()},
                       **{"replication." + key: value for key, value in DB_REPLICATION.items()}}
        for name, expression in expressions.items():
            with self.subTest(query=name):
                url = base + "/api/v1/query?" + urllib.parse.urlencode({"query": bind(expression)})
                with opener.open(url, timeout=10) as response:
                    payload = json.load(response)
                self.assertEqual(payload["status"], "success")
                for row in payload["data"]["result"]:
                    self.assertNotIn(row["value"][1], {"NaN", "+Inf", "-Inf"})


if __name__ == "__main__":
    unittest.main()
