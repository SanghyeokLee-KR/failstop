import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), DIRECTORY / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


discovery = load("discover-targets")
packager = load("package-release")
proxies = load("trusted-proxies")


def instance(role, address, state="running"):
    return {"InstanceId": f"i-{role}", "PrivateIpAddress": address,
            "State": {"Name": state}, "Tags": [{"Key": "Role", "Value": role}]}


class DiscoveryTest(unittest.TestCase):
    def test_roles_and_scale_in_replace_target_list(self):
        inventory = {"Reservations": [{"Instances": [instance("app", "10.0.0.1"), instance("reader", "10.0.0.2"), instance("data", "10.0.0.3"), instance("bot", "10.0.0.4")]}]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            discovery.collect({"api_origin": "https://origin.example.invalid"}, root / "targets", root / "metrics", lambda _: inventory)
            self.assertIn("10.0.0.4:9088", (root / "targets/reporters.json").read_text())
            self.assertIn("https://origin.example.invalid/api/v1/markets", (root / "targets/probes.json").read_text())
            self.assertEqual(len(json.loads((root / "targets/hosts.json").read_text())), 4)
            inventory["Reservations"][0]["Instances"][1]["State"]["Name"] = "terminated"
            discovery.collect({}, root / "targets", root / "metrics", lambda _: inventory)
            hosts = json.loads((root / "targets/hosts.json").read_text())
            self.assertEqual(len(hosts), 3)
            self.assertNotIn("10.0.0.2", json.dumps(hosts))
            self.assertNotIn("10.0.0.4:8080", (root / "targets/apps.json").read_text())
            self.assertIn("10.0.0.3:9187", (root / "targets/databases.json").read_text())

    def test_failed_discovery_retains_targets_and_marks_failure(self):
        inventory = {"Reservations": [{"Instances": [instance("app", "10.0.0.1")]}]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            discovery.collect({}, root / "targets", root / "metrics", lambda _: inventory)
            before = (root / "targets/hosts.json").read_text()
            with self.assertRaises(ValueError):
                discovery.collect({}, root / "targets", root / "metrics", lambda _: {"Reservations": []})
            self.assertEqual((root / "targets/hosts.json").read_text(), before)
            self.assertIn("failstop_target_discovery_success 0", (root / "metrics/failstop-discovery.prom").read_text())

    def test_invalid_instance_address_cannot_become_target(self):
        with self.assertRaises(ValueError):
            discovery.targets({"Reservations": [{"Instances": [instance("app", "attacker.example/path")]}]})

    def test_only_selected_customer_cluster_is_discovered(self):
        selected = instance("app", "10.0.0.1")
        selected["Tags"].append({"Key": "FailstopCluster", "Value": "customer-one"})
        unrelated = instance("reader", "10.0.0.2")
        unrelated["Tags"].append({"Key": "FailstopCluster", "Value": "customer-two"})
        found = discovery.targets({"Reservations": [{"Instances": [selected, unrelated]}]}, cluster="customer-one")
        self.assertEqual(len(found["hosts.json"]), 1)
        self.assertEqual(found["hosts.json"][0]["labels"]["environment"], "aws")
        self.assertNotIn("10.0.0.2", json.dumps(found))


class PackageTest(unittest.TestCase):
    def test_real_release_contains_required_runtime_but_no_local_configuration(self):
        files = [path.as_posix() for path in packager.release_files(DIRECTORY.parents[1])]
        self.assertIn("deploy/terraform/bootstrap-node.sh", files)
        self.assertIn("deploy/compose.node-read.yml", files)
        self.assertTrue(any(path.startswith("deploy/backup/") for path in files))
        self.assertIn("harness/sql/summary.sql", files)
        self.assertIn("harness/prometheus/pg-queries.yaml", files)
        self.assertIn("deploy/backup/backup.env.example", files)
        self.assertTrue(any(path.startswith("harness/grafana/dashboards/") for path in files))
        self.assertFalse(any((".env" in Path(path).name and path != "deploy/backup/backup.env.example") or "/.terraform/" in path or "/.tools/" in path or "/tests/" in path for path in files))

    def test_read_route_has_exact_boundary(self):
        text = (DIRECTORY / "locals.tf").read_text()
        pattern = re.search(r'read_path_regex\s*=\s*"([^"]+)"', text).group(1)
        for path in ["/api/v1/markets", "/api/v1/trades", "/api/v1/candles/1m"]:
            self.assertIsNotNone(re.fullmatch(pattern, path))
        for path in ["/api/v1/orders", "/api/v1/ticker", "/api/v1/orderbook", "/ws/v1", "/api/v1/candles/1m/extra", "/api/v1/markets/", "/api/v1/candles/%2e"]:
            self.assertIsNone(re.fullmatch(pattern, path))


class ProxyTest(unittest.TestCase):
    def setUp(self):
        self.expression = re.compile(proxies.compile_ranges(["10.42.0.0/24", "10.42.1.0/24"],
            {"prefixes": [{"ip_prefix": "192.0.2.128/25", "service": "CLOUDFRONT_ORIGIN_FACING"},
                          {"ip_prefix": "198.51.100.0/24", "service": "EC2"}]}))

    def test_network_boundaries_and_unrelated_ec2_exclusion(self):
        for address in ["10.42.0.0", "10.42.1.255", "192.0.2.128", "192.0.2.255"]:
            self.assertIsNotNone(self.expression.fullmatch(address))
        for address in ["10.42.2.0", "192.0.2.127", "192.0.2.256", "198.51.100.10"]:
            self.assertIsNone(self.expression.fullmatch(address))

    def test_right_to_left_proxy_chain_stops_at_real_viewer(self):
        def remote_address(chain):
            for address in reversed(chain):
                if not self.expression.fullmatch(address):
                    return address
            raise AssertionError("No client address")
        self.assertEqual(remote_address(["1.1.1.1", "203.0.113.10", "192.0.2.150", "10.42.0.10"]), "203.0.113.10")
        self.assertEqual(remote_address(["1.1.1.1", "203.0.113.10", "10.42.1.10"]), "203.0.113.10")

    def test_empty_or_broad_ranges_fail_closed(self):
        with self.assertRaises(ValueError):
            proxies.compile_ranges(["10.42.0.0/24"], {"prefixes": []})
        with self.assertRaises(ValueError):
            proxies.network_pattern("0.0.0.0/0")


if __name__ == "__main__":
    unittest.main()
