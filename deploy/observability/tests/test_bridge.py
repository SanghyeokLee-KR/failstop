import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / "windows-wsl-bridge.py"
spec = importlib.util.spec_from_file_location("windows_wsl_bridge", path)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class BridgeRouteTests(unittest.TestCase):
    def test_report_list_allows_exact_readonly_scope_and_bounded_limit(self):
        path = "/reporter/console/reports?customer=exchange&environment=local-lab"
        self.assertEqual(bridge.route_for(path), ("GET", "reporter", path.removeprefix("/reporter")))
        self.assertEqual(bridge.route_for(path + "&limit=100")[0], "GET")
        self.assertEqual(bridge.route_for("/reporter/metrics"), ("GET", "reporter", "/metrics"))

    def test_console_query_cannot_expand_proxy_destinations_or_endpoint_permissions(self):
        base = "/reporter/console/reports?customer=exchange&environment=local-lab"
        invalid = [base + "&url=http://169.254.169.254", base + "&limit=101", base + "&limit=0",
                   base + "&customer=another", base + "&cursor=x", base + "&", base + "#fragment",
                   base.replace("exchange", "*"), base.replace("exchange", "%0d%0aInjected"),
                   base.replace("exchange", ""), base.replace("local-lab", "local-lab/other"),
                   base.replace("/reports?", "/reports/../alerts?"), "/reporter/console/reports",
                   "http://external" + base, "//external" + base, "/reporter/alerts?customer=exchange"]
        for path in invalid:
            with self.subTest(path=path):
                self.assertIsNone(bridge.route_for(path))


if __name__ == "__main__":
    unittest.main()
