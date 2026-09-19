"""Guard the missing-data and role-query contracts of the native topology."""

import unittest

from msp_canvas import db_replication_panel, service_path_panel


class CanvasContractTests(unittest.TestCase):
    def test_missing_cloud_nodes_have_no_fabricated_targets(self):
        query = 'min(up{customer="$customer",environment="$environment"})'
        panel = service_path_panel({"spring": query}, id=20)
        self.assertEqual(panel["targets"], [{
            "refId": "A", "expr": query, "legendFormat": "spring", "instant": True,
            "range": False, "format": "time_series", "editorMode": "code"}])
        elements = {item["name"]: item for item in panel["options"]["root"]["elements"]}
        for key in ("edge", "cloudfront", "alb", "spring", "primary", "standby"):
            text = elements[key + "-state"]["config"]["text"]
            self.assertEqual(text, {"mode": "field", "field": key, "fixed": "미수집"})
        self.assertEqual(panel["id"], 20)

    def test_current_role_expressions_are_preserved_without_static_role_override(self):
        expressions = {
            "primary": 'min(pg_up and on(instance) (pg_replication_is_replica == 0))',
            "standby": 'min(pg_up and on(instance) (pg_replication_is_replica == 1))',
            "replication": 'min(pg_stat_replication_state{state="streaming"})',
            "wal_gap": "max(pg_replication_client_sent_replay_bytes)",
            "primary_connections": "sum(pg_stat_activity_count)",
            "standby_utilization": "max(pg_connection_utilization)",
        }
        panel = db_replication_panel(expressions)
        self.assertEqual({target["legendFormat"]: target["expr"] for target in panel["targets"]}, expressions)
        overrides = {item["matcher"]["options"]: item["properties"] for item in panel["fieldConfig"]["overrides"]}
        query_refs = {target["legendFormat"]: target["refId"] for target in panel["targets"]}
        self.assertEqual(panel["fieldConfig"]["defaults"]["mappings"][0]["type"], "special")
        self.assertEqual(len(panel["fieldConfig"]["defaults"]["mappings"]), 1)
        for field in ("wal_gap", "primary_connections", "standby_utilization"):
            self.assertFalse(any(prop["id"] == "mappings" for prop in overrides[query_refs[field]]))
        for field in ("primary", "standby", "replication"):
            mappings = next(prop["value"] for prop in overrides[query_refs[field]] if prop["id"] == "mappings")
            self.assertEqual(mappings[0]["options"]["0"]["text"], "▲ 장애")
        self.assertTrue(all(item["matcher"]["id"] == "byFrameRefID"
                            for item in panel["fieldConfig"]["overrides"]))

    def test_native_elements_respect_theme_and_canvas_bounds(self):
        for panel in (service_path_panel({}), db_replication_panel({}),
                      service_path_panel({}, h=4), db_replication_panel({}, h=4)):
            self.assertEqual(panel["type"], "canvas")
            self.assertFalse(panel["options"]["inlineEditing"])
            names = []
            for element in panel["options"]["root"]["elements"]:
                names.append(element["name"])
                placement = element["placement"]
                self.assertGreaterEqual(placement["left"], 0)
                self.assertGreaterEqual(placement["right"], 0)
                self.assertLess(placement["left"] + placement["right"], 100)
                self.assertGreaterEqual(placement["top"], 0)
                self.assertGreaterEqual(placement["bottom"], 0)
                self.assertLess(placement["top"] + placement["bottom"], 100)
                if element["type"] == "rectangle":
                    self.assertEqual(element["background"]["color"]["fixed"], "panel-bg")
                if element["type"] == "text":
                    self.assertEqual(element["config"]["color"], {"fixed": "text"},
                                     "Small status and metric text must follow the selected theme")
            self.assertEqual(len(names), len(set(names)))
            for element in panel["options"]["root"]["elements"]:
                for connection in element.get("connections", []):
                    self.assertIn(connection["targetName"], names)


if __name__ == "__main__":
    unittest.main()
