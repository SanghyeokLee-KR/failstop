import json
import math
import os
from concurrent.futures import ThreadPoolExecutor
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from metrics import MAX_POINTS, MAX_RANGE, MAX_SERIES, MetricsClient, MetricsUnavailable, merge_series, queries


def target(customer="exchange", environment="local-lab", role="app", job="exchange-api", service="apps", instance="app:8081"):
    return {"labels": {"customer": customer, "environment": environment, "role": role,
                       "job": job, "service": service, "instance": instance, "node": "app-1"},
            "health": "up", "lastScrape": "2026-09-18T08:00:00Z", "lastError": ""}


class PrometheusFixture:
    def __init__(self, response):
        self.response = response

    def __enter__(self):
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                status, payload = fixture.response
                data = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return MetricsClient("http://127.0.0.1:" + str(self.server.server_port))

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class MetricsTests(unittest.TestCase):
    def test_instant_missing_does_not_reuse_last_healthy_value(self):
        past = [{"metric": {"node": "app-1"}, "values": [[100, "1"], [115, "1"]]}]
        result = merge_series([], past)
        self.assertEqual(result["state"], "missing")
        self.assertIsNone(result["series"][0]["value"])
        self.assertEqual(result["series"][0]["points"], [[100, 1], [115, 1]])

    def test_zero_is_preserved_and_nonfinite_values_remain_gaps(self):
        labels = {"node": "app-1"}
        history = [{"metric": labels, "values": [[100, "NaN"], [115, "+Inf"], [130, "0"]]}]
        current = [{"metric": labels, "value": [130, "0"]}]
        result = merge_series(current, history)
        self.assertEqual(result["state"], "ok")
        self.assertEqual(result["series"][0]["value"], 0)
        self.assertEqual(result["series"][0]["points"], [[100, None], [115, None], [130, 0]])

    def test_historical_series_cannot_replace_another_instance(self):
        result = merge_series([{"metric": {"instance": "new"}, "value": [130, "0.5"]}],
                              [{"metric": {"instance": "old"}, "values": [[100, "0.1"]]}])
        by_instance = {item["labels"]["instance"]: item for item in result["series"]}
        self.assertIsNone(by_instance["old"]["value"])
        self.assertEqual(by_instance["new"]["value"], 0.5)

    def test_missing_scrape_intervals_are_explicit_chart_gaps(self):
        result = merge_series([], [{"metric": {"node": "app-1"}, "values": [[100, "1"], [145, "1"]]}],
                              start=100, end=145, step=15)
        self.assertEqual(result["series"][0]["points"], [[100, 1], [115, None], [130, None], [145, 1]])

    def test_series_and_point_limits_fail_instead_of_truncating_success(self):
        current = [{"metric": {"instance": str(index)}, "value": [130, "1"]} for index in range(MAX_SERIES + 1)]
        with self.assertRaisesRegex(MetricsUnavailable, "series_limit"):
            merge_series(current, [])
        with self.assertRaisesRegex(MetricsUnavailable, "points_limit"):
            merge_series([], [{"metric": {}, "values": [[index, "1"] for index in range(MAX_POINTS + 1)]}])

    def test_customer_inventory_excludes_observer_and_unlabelled_targets(self):
        client = MetricsClient("http://127.0.0.1:9090")
        entries = [target(), target(role="lab", customer="operations-lab", job="linux-host"),
                   target(role="", job="prometheus"), target(role="bot"), target(customer='bad"scope')]
        with patch.object(client, "_get", return_value={"activeTargets": entries}):
            self.assertEqual(client.scopes(), [{"customer": "exchange", "environment": "local-lab",
                                               "services": ["apps"], "instances": ["app:8081"]}])

    def test_range_and_scope_are_rejected_before_upstream_access(self):
        client = MetricsClient("http://127.0.0.1:9090")
        with patch.object(client, "_get") as get:
            for seconds in (0, -1, MAX_RANGE + 1, True, 3600.0):
                with self.assertRaises(ValueError):
                    client.snapshot("exchange", "local-lab", seconds)
            for name in ('exchange"} or up{job="', "", "exchange\n"):
                with self.assertRaises(ValueError):
                    client.snapshot(name, "local-lab")
            get.assert_not_called()

    def test_optional_label_values_are_encoded_as_promql_strings(self):
        expressions = queries("exchange", "local-lab", 3600, 'apps"', "http://a:8080/x")
        for expression in expressions.values():
            self.assertIn('customer="exchange",environment="local-lab"', expression)
            self.assertIn('service="apps\\\""', expression)
            self.assertNotIn('service="apps""', expression)

    def test_business_queries_exclude_observation_requests(self):
        expressions = queries("exchange", "local-lab", 3600)
        for key in ("rps", "error_rate", "latency_mean", "latency_p95", "latency_p99", "requests_by_uri", "http_codes"):
            self.assertIn('uri!~"/actuator(/.*)?"', expressions[key])
        self.assertNotIn("vector(0)", " ".join(expressions.values()))

    @unittest.skipUnless(os.getenv("PROMETHEUS_TEST_URL"), "requires a read-only Prometheus query endpoint")
    def test_http_code_groups_preserve_labels_and_aggregate_statuses(self):
        # Evaluate the generated aggregation in Prometheus without writing test samples.
        expressions = queries("exchange", "local-lab", 3600)
        source = expressions["rps"][len("sum("):-1]
        statuses = [(100, 1), (200, 2), (201, 3), (302, 7), (404, 11), (503, 13)]
        fixture = "(" + " or ".join(
            f'label_replace(vector({value}),"status","{status}","__name__",".*")'
            for status, value in statuses) + ")"
        expression = expressions["http_codes"].replace(source, fixture)
        client = MetricsClient(os.environ["PROMETHEUS_TEST_URL"])
        rows = client._get("/api/v1/query", {"query": expression})["result"]
        actual = {row["metric"].get("code"): float(row["value"][1]) for row in rows}
        self.assertEqual(actual, {"1xx": 1, "2xx": 5, "3xx": 7, "4xx": 11, "5xx": 13})

    def test_failed_http_and_query_errors_are_not_successful_empty_metrics(self):
        for response in ((503, {}), (200, {"status": "error", "error": "private details"}),
                         (200, []), (200, {"status": "success", "data": []})):
            with self.subTest(status=response[0]), PrometheusFixture(response) as client:
                with self.assertRaisesRegex(MetricsUnavailable, "prometheus_") as raised:
                    client.scopes()
                self.assertNotIn("private details", str(raised.exception))

    def test_snapshot_filters_targets_alerts_and_preserves_partial_failures(self):
        client = MetricsClient("http://127.0.0.1:9090")
        selected = target()
        other_customer = target(customer="other")
        other_environment = target(environment="aws")
        other_role = target(role="operations", job="operations-reporter")
        warning = {"labels": {**selected["labels"], "alertname": "SpringMetricsUnavailable", "severity": "critical"},
                   "annotations": {"summary": "Spring collection failure"}, "state": "firing", "activeAt": "2026-09-18T08:00:00Z"}

        def get(path, params=None):
            if path.endswith("targets"):
                return {"activeTargets": [selected, other_customer, other_environment, other_role]}
            if path.endswith("alerts"):
                return {"alerts": [warning, {**warning, "labels": other_customer["labels"]}]}
            if params["query"] == "broken":
                raise MetricsUnavailable("unavailable")
            if path.endswith("query_range"):
                self.assertLessEqual(math.floor((params["end"] - params["start"]) / params["step"]) + 1, MAX_POINTS)
                return {"result": [{"metric": {"node": "app-1"}, "values": [[100, "1"]]}]}
            return {"result": [{"metric": {"node": "app-1"}, "value": [130, "1"]}]}

        with patch("metrics.queries", return_value={"app_up": "up", "rps": "broken"}), patch.object(client, "_get", side_effect=get):
            snapshot = client.snapshot("exchange", "local-lab", MAX_RANGE)
        self.assertEqual(len(snapshot["targets"]), 1)
        self.assertEqual(len(snapshot["alerts"]), 1)
        self.assertEqual(snapshot["metrics"]["app_up"]["state"], "ok")
        self.assertEqual(snapshot["metrics"]["rps"]["state"], "error")
        self.assertEqual(snapshot["sources"][0]["state"], "error")

    def test_recurrent_alert_has_a_new_id_without_changing_label_identity(self):
        client = MetricsClient("http://127.0.0.1:9090")
        labels = {**target()["labels"], "alertname": "SpringMetricsUnavailable"}
        alerts = [{"labels": labels, "activeAt": active, "state": "firing"}
                  for active in ("2026-09-18T08:00:00Z", "2026-09-18T09:00:00Z")]

        def get(path, params=None):
            return {"alerts": alerts} if path.endswith("alerts") else {"activeTargets": []}

        with patch("metrics.queries", return_value={}), patch.object(client, "_get", side_effect=get):
            snapshot = client.snapshot("exchange", "local-lab")
        self.assertEqual(len(snapshot["alerts"]), 2)
        self.assertNotEqual(snapshot["alerts"][0]["id"], snapshot["alerts"][1]["id"])

    def test_cache_is_shared_between_views_but_returned_data_cannot_mutate_it(self):
        client = MetricsClient("http://127.0.0.1:9090")
        snapshot = {"metrics": {"app_up": {"state": "ok"}}}
        with patch.object(client, "_snapshot", return_value=snapshot) as loader:
            first = client.snapshot("exchange", "local-lab")
            first["metrics"]["app_up"]["state"] = "error"
            second = client.snapshot("exchange", "local-lab", view="spring")
            self.assertEqual(second["metrics"]["app_up"]["state"], "ok")
            loader.assert_called_once()

    def test_concurrent_cache_misses_use_one_upstream_refresh(self):
        client = MetricsClient("http://127.0.0.1:9090")
        count = 0

        def load():
            nonlocal count
            count += 1
            time.sleep(0.03)
            return {"state": "ok"}

        with ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda _: client._cached(("same",), load), range(6)))
        self.assertEqual(count, 1)
        self.assertEqual(results, [{"state": "ok"}] * 6)

    def test_cache_evicts_by_size_and_expiry_and_skips_oversized_entries(self):
        client = MetricsClient("http://127.0.0.1:9090")
        with patch("metrics.MAX_CACHE_BYTES", 100), patch("metrics.MAX_CACHE_ENTRY_BYTES", 75):
            for index in range(3):
                client._cached((index,), lambda: {"value": "x" * 45})
            self.assertLessEqual(sum(len(entry[1]) for entry in client._cache.values()), 100)
            client._cached(("oversized",), lambda: {"value": "x" * 100})
            self.assertNotIn(("oversized",), client._cache)
        for key, (_, payload) in list(client._cache.items()):
            client._cache[key] = (time.monotonic() - 11, payload)
        self.assertIsNone(client._cached_bytes((2,)))
        self.assertEqual(client._cache, {})

    def test_prometheus_outage_does_not_fan_out_dozens_of_timeout_requests(self):
        client = MetricsClient("http://127.0.0.1:9090")
        with patch.object(client, "_get", side_effect=MetricsUnavailable("unavailable")) as get:
            snapshot = client.snapshot("exchange", "local-lab")
        get.assert_called_once_with("/api/v1/targets")
        self.assertTrue(snapshot["metrics"])
        self.assertTrue(all(item["state"] == "error" for item in snapshot["metrics"].values()))

    def test_snapshot_point_budget_produces_explicit_error(self):
        client = MetricsClient("http://127.0.0.1:9090")

        def get(path, params=None):
            if path.endswith("targets"):
                return {"activeTargets": [target()]}
            if path.endswith("alerts"):
                return {"alerts": []}
            if path.endswith("query_range"):
                return {"result": [{"metric": {}, "values": [[params["start"], "1"]]}]}
            return {"result": [{"metric": {}, "value": [130, "1"]}]}

        with patch("metrics.MAX_TOTAL_POINTS", 2), patch("metrics.queries", return_value={"app_up": "up"}), patch.object(client, "_get", side_effect=get):
            snapshot = client.snapshot("exchange", "local-lab")
        self.assertEqual(snapshot["metrics"]["app_up"]["state"], "error")
        self.assertTrue(snapshot["metrics"]["app_up"]["message"])


if __name__ == "__main__":
    unittest.main()
