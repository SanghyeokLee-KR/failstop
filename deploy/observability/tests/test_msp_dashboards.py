"""Deployment and layout contracts for the native Grafana dashboards."""
import importlib.util
import json
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("msp_dashboards", Path(__file__).with_name("build-msp-dashboards.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class NativeDashboardsTest(unittest.TestCase):
    def dashboards_by_uid(self):
        return {dashboard["uid"]: dashboard for dashboard in builder.all_dashboards().values()}

    def test_six_native_pages_are_reachable_with_customer_scope(self):
        dashboards = self.dashboards_by_uid()
        expected = {"msp-overview", "msp-service", "msp-infra", "msp-db",
                    "msp-db-workload", "msp-db-replication"}
        self.assertEqual(set(dashboards), expected)
        for uid, dashboard in dashboards.items():
            with self.subTest(dashboard=uid):
                destinations = {link["url"].split("?")[0].removeprefix("/d/")
                                for link in dashboard["links"]}
                self.assertEqual(destinations, expected)
                self.assertTrue(all(link["includeVars"] and link["keepTime"]
                                    for link in dashboard["links"]))

    def test_provisioned_files_match_reviewed_generator(self):
        for filename, dashboard in builder.all_dashboards().items():
            with self.subTest(file=filename):
                actual = json.loads((builder.DEST / filename).read_text(encoding="utf-8"))
                self.assertEqual(dashboard, actual)

    def test_native_layout_and_customer_navigation(self):
        for dashboard in builder.all_dashboards().values():
            with self.subTest(dashboard=dashboard["uid"]):
                panels = [panel for panel in dashboard["panels"] if panel["type"] != "row"]
                self.assertEqual(len(panels), len({panel["id"] for panel in panels}))
                for index, panel in enumerate(panels):
                    self.assertIn(panel["type"], {"stat", "timeseries", "canvas", "table", "text", "state-timeline"})
                    position = panel["gridPos"]
                    self.assertLessEqual(position["y"] + position["h"], 19)
                    self.assertLessEqual(position["x"] + position["w"], 24)
                    for other in panels[index + 1:]:
                        b = other["gridPos"]
                        overlap = (position["x"] < b["x"] + b["w"] and b["x"] < position["x"] + position["w"]
                                   and position["y"] < b["y"] + b["h"] and b["y"] < position["y"] + position["h"])
                        self.assertFalse(overlap, (panel["title"], other["title"]))
                for link in dashboard["links"]:
                    self.assertTrue(link["url"].startswith("/d/msp-"))
                    self.assertTrue(link["includeVars"])
                    self.assertTrue(link["keepTime"])

    def test_sensitive_sql_text_is_not_a_dashboard_dimension(self):
        for dashboard in builder.all_dashboards().values():
            for panel in dashboard["panels"]:
                with self.subTest(dashboard=dashboard["uid"], panel=panel["title"]):
                    for target in panel.get("targets", []):
                        self.assertNotRegex(target.get("legendFormat", ""),
                                            r"\{\{\s*(query|queryid|query_id|sql|statement)\s*\}\}")
                    for transform in panel.get("transformations", []):
                        if transform["id"] == "filterFieldsByName":
                            names = transform["options"].get("include", {}).get("names", [])
                            self.assertFalse(set(names) & {"query", "queryid", "query_id", "sql", "statement"})

    def test_database_history_keeps_collection_gaps_visible(self):
        for uid, dashboard in self.dashboards_by_uid().items():
            if not uid.startswith("msp-db"):
                continue
            for panel in dashboard["panels"]:
                with self.subTest(dashboard=uid, panel=panel["title"]):
                    if panel["type"] == "timeseries":
                        custom = panel["fieldConfig"]["defaults"]["custom"]
                        self.assertFalse(custom["spanNulls"])
                        self.assertTrue(custom["insertNulls"])
                    if panel["type"] == "stat":
                        self.assertNotIn("lastNotNull", panel["options"]["reduceOptions"]["calcs"])
                    for target in panel.get("targets", []):
                        self.assertNotIn("vector(0)", target.get("expr", ""))

    def test_workload_page_separates_sql_execution_and_session_waiting(self):
        dashboard = self.dashboards_by_uid()["msp-db-workload"]
        panels = dashboard["panels"]
        for key in ("sql_calls", "sql_mean_ms", "sessions", "session_wait_totals",
                    "blocked_sessions", "longest_transaction_seconds"):
            with self.subTest(query=key):
                self.assertTrue(any(builder.Q[key] in target.get("expr", "")
                                    for panel in panels for target in panel.get("targets", [])),
                                f"Workload diagnosis requires {key}")
        sql_panels = [panel for panel in panels if any(builder.Q["sql_calls"] in target.get("expr", "")
                                                      for target in panel.get("targets", []))]
        for panel in sql_panels:
            for target in panel["targets"]:
                self.assertNotIn("pg_stat_database_tup_", target["expr"],
                                 "Fetched or changed row counts are not SQL execution counts")
        availability = [panel for panel in panels if panel["type"] == "stat"
                        and any(target.get("expr") == builder.Q["sql_collection_status"]
                                for target in panel.get("targets", []))]
        self.assertTrue(availability, "SQL collection status must include every registered database")
        for panel in availability:
            mappings = panel["fieldConfig"]["defaults"]["mappings"]
            zero = [item["options"]["0"]["text"] for item in mappings
                    if item["type"] == "value" and "0" in item["options"]]
            self.assertTrue(zero)
            self.assertTrue(all(value in {"미구성", "미설정", "미수집"} for value in zero))

    def test_transaction_completion_and_wal_generation_have_distinct_meanings(self):
        db_panels = [panel for uid, dashboard in self.dashboards_by_uid().items()
                     if uid.startswith("msp-db") for panel in dashboard["panels"]]
        self.assertTrue(any(all(any(builder.Q[key] in target.get("expr", "")
                                   for target in panel.get("targets", []))
                               for key in ("db_commit_tps", "db_rollback_tps"))
                            for panel in db_panels), "Commit and rollback belong in the same comparison")
        wal_panels = [panel for panel in db_panels if any(builder.Q["wal_generation_rate"] in target.get("expr", "")
                                                         for target in panel.get("targets", []))]
        self.assertTrue(wal_panels)
        for panel in wal_panels:
            self.assertIn("WAL 생성량", panel["title"])
            self.assertEqual(panel["fieldConfig"]["defaults"]["unit"], "Bps")
            self.assertFalse(any("pg_replication_client_" in target["expr"] for target in panel["targets"]),
                             "WAL generation and replication backlog are separate measurements")
        self.assertNotIn("WAL 위치 증가율", json.dumps(db_panels, ensure_ascii=False))

    def test_spring_page_exposes_route_starvation_instead_of_total_rps_only(self):
        panels = self.dashboards_by_uid()["msp-service"]["panels"]
        for key in ("endpoint_success_rps", "endpoint_rejected_rps", "endpoint_success_latency_p95"):
            with self.subTest(query=key):
                self.assertTrue(any(builder.Q[key] in target.get("expr", "")
                                    for panel in panels for target in panel.get("targets", [])))
        limited = [panel for panel in panels if any(builder.Q["endpoint_rejected_rps"] in target.get("expr", "")
                                                  for target in panel.get("targets", []))]
        self.assertTrue(any("429" in panel["title"] for panel in limited),
                        "Request limiting must be identifiable without opening a query editor")

    def test_unconfigured_aws_queries_cannot_cross_customer_selection(self):
        row = builder.infrastructure()["panels"][-1]
        self.assertTrue(row["collapsed"])
        for panel in row["panels"]:
            for target in panel.get("targets", []):
                self.assertTrue(target["hide"], "AWS resource mapping and IAM verification required before activation")


if __name__ == "__main__":
    unittest.main()
