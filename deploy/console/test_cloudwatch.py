import datetime as dt
import unittest
from unittest.mock import Mock, patch

from cloudwatch import FrameError, METRIC_IDS, collect_aws, parse_frames, period_seconds
from metrics import MAX_POINTS


NOW = 1800000000
CONFIG = {"region": "ap-northeast-2", "load_balancer": "app/exchange/0123456789abcdef",
          "target_group": "targetgroup/readers/0123456789abcdef", "auto_scaling_group": "exchange-readers",
          "volume_id": "vol-0123456789abcdef0"}


def frame(values, times=None, labels=None):
    times = times if times is not None else [(NOW - 120 + 60 * index) * 1000 for index in range(len(values))]
    return {"schema": {"fields": [{"name": "Time", "type": "time"},
                                    {"name": "Value", "type": "number", "labels": labels or {}}]},
            "data": {"values": [times, values]}}


def populated(payload):
    return {"results": {query["refId"]: {"frames": [frame([1, 2])]}
                        for query in payload["queries"]}}


class CloudWatchTests(unittest.TestCase):
    def test_unconfigured_aws_never_issues_request_or_reports_zero(self):
        request = Mock()
        for config in (None, {}, {"region": "ap-northeast-2"}):
            with self.subTest(config=config):
                result = collect_aws(config, 3600, request)
                self.assertEqual(result["sources"][0]["state"], "missing")
                self.assertEqual(set(result["metrics"]), set(METRIC_IDS))
                self.assertTrue(all(item["state"] == "missing" and not item["series"] for item in result["metrics"].values()))
        request.assert_not_called()

    def test_only_configured_dimensions_are_requested(self):
        request = Mock(side_effect=populated)
        with patch("cloudwatch.time.time", return_value=NOW):
            result = collect_aws({"region": "ap-northeast-2", "volume_id": CONFIG["volume_id"]}, 3600, request)
        queries = request.call_args.args[0]["queries"]
        self.assertEqual(len(queries), 1)
        self.assertEqual(queries[0]["namespace"], "AWS/EBS")
        self.assertEqual(queries[0]["dimensions"], {"VolumeId": [CONFIG["volume_id"]]})
        self.assertEqual(result["metrics"]["aws_ebs_queue"]["state"], "ok")
        self.assertEqual(result["metrics"]["aws_asg_desired"]["state"], "missing")

    def test_dimension_wildcards_namespaces_and_query_expressions_are_rejected(self):
        invalid = [{**CONFIG, "load_balancer": "*"}, {**CONFIG, "namespace": "AWS/Other"},
                   {**CONFIG, "expression": "SEARCH('*')"}, {**CONFIG, "region": 'us-east-1"'},
                   {**CONFIG, "volume_id": "https://example.test"}, {**CONFIG, "region": False}]
        request = Mock()
        for config in invalid:
            with self.subTest(config=config):
                result = collect_aws(config, 3600, request)
                self.assertEqual(result["sources"][0]["state"], "error")
        request.assert_not_called()

    def test_request_uses_fixed_datasource_and_bound_query_model(self):
        request = Mock(side_effect=populated)
        with patch("cloudwatch.time.time", return_value=NOW):
            result = collect_aws(CONFIG, 3600, request)
        payload = request.call_args.args[0]
        self.assertEqual(len(payload["queries"]), 9)
        self.assertTrue(all(query["datasource"] == {"type": "cloudwatch", "uid": "cloudwatch"} for query in payload["queries"]))
        self.assertTrue(all(query["matchExact"] and query["metricQueryType"] == 0 for query in payload["queries"]))
        self.assertEqual(result["sources"][0]["state"], "ok")
        self.assertEqual(result["metrics"]["aws_latency_p95"]["series"][0]["value"], 2000)
        self.assertEqual({series["labels"]["source"] for series in result["metrics"]["aws_errors"]["series"]}, {"ALB", "Target"})

    def test_thirty_day_period_matches_retention_and_count_unit_remains_per_minute(self):
        request = Mock(side_effect=populated)
        with patch("cloudwatch.time.time", return_value=NOW):
            result = collect_aws(CONFIG, 30 * 86400, request)
        payload = request.call_args.args[0]
        period = int(payload["queries"][0]["period"])
        self.assertEqual(period % 300, 0)
        self.assertLessEqual((int(payload["to"]) - int(payload["from"])) // (period * 1000) + 1, MAX_POINTS)
        self.assertAlmostEqual(result["metrics"]["aws_requests"]["series"][0]["value"], 2 * 60 / period)

    def test_millisecond_frames_preserve_zero_and_nullable_values(self):
        result = parse_frames({"frames": [frame([3, None, 0])]}, NOW - 3600, NOW, 60, 1, "ALB")
        self.assertEqual(result[0]["points"], [[NOW - 120, 3], [NOW - 60, None], [NOW, 0]])
        self.assertEqual(result[0]["value"], 0)

    def test_seconds_and_iso_timestamps_are_normalized(self):
        iso = dt.datetime.fromtimestamp(NOW - 60, dt.timezone.utc).isoformat()
        result = parse_frames({"frames": [frame([1, 2], [NOW - 120, iso])]}, NOW - 3600, NOW, 60, 1, "ALB")
        self.assertEqual(result[0]["points"], [[NOW - 120, 1], [NOW - 60, 2]])

    def test_wide_frames_and_reordered_fields_keep_series_labels(self):
        data = {"schema": {"fields": [{"name": "a", "type": "number", "labels": {"az": "a"}},
                                         {"name": "time", "type": "time"},
                                         {"name": "b", "type": "number", "labels": {"az": "c"}}]},
                "data": {"values": [[1, 2], [(NOW - 60) * 1000, NOW * 1000], [3, 4]]}}
        result = parse_frames({"frames": [data]}, NOW - 3600, NOW, 60, 1, "ASG")
        self.assertEqual([item["value"] for item in result], [2, 4])
        self.assertEqual([item["labels"]["az"] for item in result], ["a", "c"])

    def test_missing_recent_value_never_reuses_old_healthy_sample(self):
        recent_null = parse_frames({"frames": [frame([1, None])]}, NOW - 3600, NOW, 60, 1, "ALB")
        stale = parse_frames({"frames": [frame([1], [(NOW - 600) * 1000])]}, NOW - 3600, NOW, 60, 1, "ALB")
        self.assertIsNone(recent_null[0]["value"])
        self.assertIsNone(stale[0]["value"])

    def test_sparse_frames_insert_null_gaps(self):
        result = parse_frames({"frames": [frame([1, 2], [(NOW - 180) * 1000, NOW * 1000])]},
                              NOW - 3600, NOW, 60, 1, "ALB")
        self.assertEqual(result[0]["points"], [[NOW - 180, 1], [NOW - 120, None], [NOW - 60, None], [NOW, 2]])

    def test_nonfinite_values_remain_missing_and_empty_frames_are_not_zero(self):
        parsed = parse_frames({"frames": [frame(["NaN", "+Inf", None])]}, NOW - 3600, NOW, 60, 1, "ALB")
        self.assertIsNone(parsed[0]["value"])
        request = lambda payload: {"results": {query["refId"]: {"frames": []} for query in payload["queries"]}}
        result = collect_aws(CONFIG, 3600, request)
        self.assertTrue(all(item["state"] == "missing" for item in result["metrics"].values()))

    def test_malformed_error_and_oversized_frames_fail_explicitly(self):
        wrong_length = frame([1, 2], [NOW * 1000])
        over_limit = frame([1] * (MAX_POINTS + 1), [NOW * 1000] * (MAX_POINTS + 1))
        for entry in ({"error": "permission denied"}, {}, {"frames": [wrong_length]}, {"frames": [over_limit]}):
            with self.subTest(entry=repr(entry)[:50]), self.assertRaises(FrameError):
                parse_frames(entry, NOW - 3600, NOW, 60, 1, "ALB")

    def test_transport_error_is_error_without_provider_details(self):
        result = collect_aws(CONFIG, 3600, Mock(side_effect=RuntimeError("credential detail")))
        self.assertTrue(all(item["state"] == "error" for item in result["metrics"].values()))
        self.assertNotIn("credential detail", str(result))

    def test_partial_query_failure_keeps_other_results_and_marks_affected_metric(self):
        def request(payload):
            result = populated(payload)
            result["results"]["A4"] = {"error": "AccessDenied", "status": 403}
            return result
        with patch("cloudwatch.time.time", return_value=NOW):
            result = collect_aws(CONFIG, 3600, request)
        self.assertEqual(result["metrics"]["aws_errors"]["state"], "error")
        self.assertEqual(result["metrics"]["aws_requests"]["state"], "ok")
        self.assertEqual(result["sources"][0]["state"], "error")
        self.assertNotIn("AccessDenied", str(result))

    def test_missing_query_reference_is_error_not_empty_success(self):
        result = collect_aws(CONFIG, 3600, lambda payload: {"results": {}})
        self.assertTrue(all(item["state"] == "error" for item in result["metrics"].values()))

    def test_invalid_range_is_rejected_before_request(self):
        for value in (0, True, 30 * 86400 + 1):
            with self.subTest(value=value), self.assertRaises(ValueError):
                period_seconds(value)


if __name__ == "__main__":
    unittest.main()
