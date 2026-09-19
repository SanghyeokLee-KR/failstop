import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

from scope import scoped_config


def reporting_queries():
    config = json.loads((Path(__file__).parent / "config.example.json").read_text(encoding="utf-8"))
    return {query["id"]: query for query in scoped_config(config, "exchange", "local-lab")["queries"]}


def http_query_cases():
    queries = reporting_queries()

    def samples(name, values, suffix="", customer="exchange", environment="local-lab", uri="/api/markets"):
        labels = f'customer="{customer}",environment="{environment}",role="app",job="exchange-api",uri="{uri}",status="200"'
        return {"series": name + "{" + labels + suffix + "}", "values": values}

    def histogram(values, **labels):
        return [samples("http_server_requests_seconds_bucket", values, ',le="' + bound + '"', **labels)
                for bound in ("1", "+Inf")]

    def case(name, series, errors, latency):
        return {"name": name, "interval": "1m", "input_series": series, "promql_expr_test": [
            {"expr": queries[query]["expr"], "eval_time": "10m", "exp_samples": [] if expected is None else [{"labels": "{}", "value": expected}]}
            for query, expected in (("server_errors", errors), ("latency_p95", latency))
        ]}

    count = "http_server_requests_seconds_count"
    active = [samples(count, "0+60x10"), *histogram("0+60x10")]
    errors = {"series": active[0]["series"].replace('status="200"', 'status="500"'), "values": "0+6x10"}
    return {"rule_files": [], "evaluation_interval": "1m", "fuzzy_compare": True, "tests": [
        case("business traffic without a lazily-created 5xx series", active, 0, 0.95),
        case("existing 5xx errors retain their measured rate", [*active, errors], 0.1, 0.95),
        case("idle histogram has no percentile and measured zero errors", [samples(count, "300+0x10"), *histogram("300+0x10")], 0, None),
        case("missing business metrics are not zero", [], None, None),
        case("management traffic is excluded", [samples(count, "0+60x10", uri="/actuator/prometheus"), *histogram("0+60x10", uri="/actuator/prometheus")], None, None),
        case("missing histogram is not inferred from request rate", [samples(count, "0+60x10")], 0, None),
        case("histogram without request counter is not sufficient", histogram("0+60x10"), None, None),
        case("other customer traffic is excluded", [samples(count, "0+60x10", customer="other"), *histogram("0+60x10", customer="other")], None, None),
        case("other environment traffic is excluded", [samples(count, "0+60x10", environment="aws"), *histogram("0+60x10", environment="aws")], None, None),
    ]}


class ScopeTests(unittest.TestCase):
    def test_switch_environment_does_not_leave_previous_matchers(self):
        original = json.loads((Path(__file__).parent / "config.example.json").read_text(encoding="utf-8"))
        local = scoped_config(original, "exchange", "local-lab")
        aws = scoped_config(local, "exchange", "aws")
        self.assertNotIn("local-lab", json.dumps(aws))
        self.assertNotIn("environment", original)
        self.assertEqual(sum(value.startswith("environment=") for value in aws["alert_matchers"]), 1)

    def test_http_query_operands_keep_customer_and_environment_scope(self):
        import re
        for query_id in ("server_errors", "latency_p95"):
            expression = reporting_queries()[query_id]["expr"]
            self.assertNotIn("vector(0)", expression)
            for labels in re.findall(r"\{([^{}]+)\}", expression):
                self.assertIn('customer="exchange"', labels)
                self.assertIn('environment="local-lab"', labels)
                self.assertIn('role=~"app|reader|data|standby|probe"', labels)
                self.assertIn('uri!~"/actuator(/.*)?"', labels)

    def test_unscoped_query_is_rejected(self):
        with self.assertRaises(ValueError):
            scoped_config({"queries": [{"expr": "up"}]}, "exchange", "aws")

    def test_delivery_audit_filters_incidents_and_does_not_expose_payload(self):
        import importlib.util
        from reporter import Store
        spec = importlib.util.spec_from_file_location("audit", Path(__file__).parent / "delivery-audit.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "outbox.sqlite3"
            store = Store(state)
            store.enqueue("incident:one", "incident", {"incident": [{"labels": {"instance": "db:9187", "alertname": "DBDown"},
                                                                     "status": "firing", "annotations": {"secret": "do_not_output"}}]})
            self.assertEqual(module.audit(state, 0, "other:9187", "DBDown"), [])
            rows = module.audit(state, 0, "db:9187", "DBDown")
            self.assertEqual(rows[0]["transitions"], ["firing"])
            self.assertNotIn("do_not_output", json.dumps(rows))

    @unittest.skipUnless(os.environ.get("REPORTING_PROMTOOL_CONTAINER"), "set REPORTING_PROMTOOL_CONTAINER for actual PromQL evaluation")
    def test_http_queries_with_promtool(self):
        result = subprocess.run([os.environ.get("DOCKER_BIN", "docker"), "exec", "-i", os.environ["REPORTING_PROMTOOL_CONTAINER"],
                                 "promtool", "test", "rules", "/dev/stdin"],
                                input=json.dumps(http_query_cases()), text=True, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipUnless(os.environ.get("REPORTING_PROMETHEUS_URL"), "set REPORTING_PROMETHEUS_URL for live range evidence")
    def test_http_live_ranges_keep_strict_evidence_validation(self):
        from evidence import metric_fact
        for window in (900, 86400):
            for query_id in ("server_errors", "latency_p95"):
                with self.subTest(window=window, query=query_id):
                    result = metric_fact(os.environ["REPORTING_PROMETHEUS_URL"], reporting_queries()[query_id], time.time(), window, 10)
                    self.assertIn(result["status"], {"ok", "missing"}, result)


if __name__ == "__main__":
    unittest.main()
