import copy
import unittest

from charts import build_charts, render_svg, segments
from gemini import compact_evidence


def report(identifier="disk_available", rows=None):
    rows = rows or [{"labels": {"mountpoint": "/data"}, "last": .14, "min": .14, "max": .2, "count": 2,
                     "last_observed_at": 1060, "points": [[1000, .2], [1060, .14]]}]
    return {"evidence": {"collected_at": "1970-01-01T00:18:40Z", "window_end": 1120,
                         "window_seconds": 120, "facts": [{"id": identifier, "kind": "metric", "status": "ok",
                          "source": {"window_start": 1000, "window_end": 1120, "step_seconds": 60}, "samples": rows}]}}


class ChartsTest(unittest.TestCase):
    def test_real_points_scale_to_percent_and_keep_window(self):
        chart = build_charts(report())[0]
        self.assertEqual(chart["kind"], "line")
        self.assertEqual([point[0] for point in chart["series"][0]["points"]], [1000, 1060])
        self.assertAlmostEqual(chart["series"][0]["points"][-1][1], 14)
        self.assertEqual((chart["window_start"], chart["window_end"]), (1000, 1120))
        self.assertEqual(chart["threshold"]["value"], 15)

    def test_old_records_are_snapshots_not_invented_trends(self):
        saved = report()
        row = saved["evidence"]["facts"][0]["samples"][0]
        del row["points"]
        del row["last_observed_at"]
        chart = build_charts(saved)[0]
        self.assertEqual(chart["kind"], "bar")
        self.assertIsNone(chart["series"][0]["points"][0][0])
        self.assertAlmostEqual(chart["series"][0]["points"][0][1], 14)
        self.assertEqual(chart["status"], "stale")
        self.assertIn("시계열 미보존", chart["note"])

    def test_invalid_values_and_time_order_are_never_plotted(self):
        for points in ([[1000, .2], [1000, .14]], [[1060, .14], [1000, .2]],
                       [[999, .2]], [[1200, .2]], [[1000, float("nan")]], [[1000, 1.2]]):
            saved = report()
            saved["evidence"]["facts"][0]["samples"][0]["points"] = points
            self.assertEqual(build_charts(saved)[0]["kind"], "empty")

    def test_missing_facts_and_gaps_do_not_become_zero(self):
        self.assertTrue(all(chart["kind"] == "empty" for chart in build_charts({})))
        points = [[1000, 1], [1060, 1], [1240, 1]]
        self.assertEqual(segments(points, 60), [points[:2], points[2:]])

    def test_response_totals_require_same_timestamp_for_every_target(self):
        rows = [{"labels": {"job": "a"}, "points": [[1000, 1], [1060, 0]], "last": 0},
                {"labels": {"job": "b"}, "points": [[1000, 1], [1120, 1]], "last": 1}]
        chart = build_charts(report("target_up", rows))[3]
        self.assertEqual(chart["series"][0]["points"], [[1000, 2]])
        self.assertEqual(chart["y_max"], 2)
        self.assertIn("서비스 정상 판정 아님", chart["note"])

    def test_svg_escapes_labels_and_has_no_external_content(self):
        saved = report()
        saved["evidence"]["facts"][0]["samples"][0]["labels"]["mountpoint"] = '<script>alert("x")</script>'
        svg = render_svg(build_charts(saved)[0])
        self.assertNotIn("<script>", svg)
        self.assertIn("&lt;script&gt;", svg)
        self.assertNotIn("href=", svg)

    def test_stored_points_do_not_expand_ai_input(self):
        document = report()["evidence"]
        without = copy.deepcopy(document)
        del without["facts"][0]["samples"][0]["points"]
        self.assertEqual(compact_evidence(document), compact_evidence(without))

    def test_redacted_hosts_stay_distinguishable_by_node(self):
        rows = [{"labels": {"node": name, "instance": "[IP REDACTED]:9187", "datname": "exchange", "mountpoint": "/"},
                 "points": [[1000, .2]], "last": .2} for name in ("node-a", "node-b")]
        for identifier, index in (("disk_available", 0), ("db_connections", 1)):
            labels = [item["label"] for item in build_charts(report(identifier, rows))[index]["series"]]
            self.assertEqual(len(set(labels)), 2)

    def test_future_legacy_time_and_calendar_overflow_are_not_plotted(self):
        saved = report()
        row = saved["evidence"]["facts"][0]["samples"][0]
        del row["points"]
        row["last_observed_at"] = 5000
        self.assertEqual(build_charts(saved)[0]["kind"], "empty")
        saved["evidence"]["facts"][0]["source"]["window_end"] = 1e12
        self.assertEqual(build_charts(saved)[0]["kind"], "empty")

    def test_large_finite_values_do_not_overflow_axis(self):
        rows = [{"labels": {}, "points": [[1000, 1.7e308]], "last": 1.7e308}]
        svg = render_svg(build_charts(report("db_connections", rows))[1])
        self.assertNotIn("nan", svg.lower())
        self.assertNotIn("inf", svg.lower())

    def test_database_incident_graphs_focus_on_service_and_database(self):
        saved = report("request_rate", [{"labels": {}, "points": [[1000, 12], [1060, 15]], "last": 15}])
        saved["evidence"]["facts"].append({"id": "trigger", "records": [{"labels": {"alertname": "DatabaseConnectionFailed"}}]})
        charts = build_charts(saved)
        self.assertEqual([item["id"] for item in charts], ["request_rate", "db_connections", "db_replication_lag", "target_up"])
        self.assertEqual(charts[0]["series"][0]["points"][-1][1], 15)
        self.assertEqual(charts[1]["kind"], "empty")

    def test_service_p95_seconds_are_rendered_in_milliseconds(self):
        saved = report("latency_p95", [{"labels": {}, "points": [[1000, .1], [1060, .2]], "last": .2}])
        saved["evidence"]["facts"].append({"id": "trigger", "records": [{"labels": {"alertname": "SpringMetricsUnavailable"}}]})
        chart = build_charts(saved)[1]
        self.assertEqual(chart["unit"], "ms")
        self.assertEqual(chart["series"][0]["points"][-1][1], 200)


if __name__ == "__main__":
    unittest.main()
