import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


host = module("host", ROOT / "deploy/observability/collect-host.py")
containers = module("containers", ROOT / "harness/cstats/exporter.py")
targets = module("targets", ROOT / "deploy/observability/configure-targets.py")
local = module("local", ROOT / "deploy/observability/prepare-local.py")


class CollectorTests(unittest.TestCase):
    def test_local_customer_inventory_excludes_ubuntu_and_reporter(self):
        inventory = local.local_targets()
        customer = [entry for entries in inventory.values() for entry in entries
                    if entry["labels"]["customer"] == "exchange"]
        self.assertEqual(len(customer), 4)
        self.assertEqual({entry["labels"]["role"] for entry in customer}, {"app", "data", "standby", "probe"})
        self.assertTrue(all(entry["labels"]["customer"] == "operations-lab"
                            for group in ("hosts", "reporters") for entry in inventory[group]))

    def test_failure_drops_old_container_values(self):
        with patch.object(containers, "collect", return_value=[{"name": "app", "container_mem_bytes": 400}]):
            good = containers.sample()
        self.assertIn('container_mem_bytes{name="app"} 400', good)
        with patch.object(containers, "collect", side_effect=OSError()):
            failed = containers.sample()
        self.assertIn("failstop_container_collection_success 0", failed)
        self.assertNotIn("container_mem_bytes", failed)
        self.assertGreater(containers._last_success, 0)

    def test_partial_collection_is_failure(self):
        with patch.object(containers, "_api", side_effect=[[{"Id": "one"}], OSError()]):
            self.assertIn("failstop_container_collection_success 0", containers.sample())

    def test_missing_container_network_is_not_zero(self):
        # 목록 → one-shot 통계 → 컨테이너 설정(코어 상한) 순서로 부른다
        with patch.object(containers, "_api", side_effect=[[{"Id": "one", "Names": ["/app"]}], {}, {}]):
            result = containers.sample()
        self.assertNotIn('container_net_rx_bytes{name="app"}', result)
        self.assertNotIn('container_net_tx_bytes{name="app"}', result)

    def test_real_zero_container_network_is_preserved(self):
        with patch.object(containers, "_api", side_effect=[[{"Id": "one", "Names": ["/app"]}],
                                                         {"networks": {"eth0": {"rx_bytes": 0, "tx_bytes": 0}}}, {}]):
            result = containers.sample()
        self.assertIn('container_net_rx_bytes{name="app"} 0', result)

    def test_cpu_is_the_delta_between_collection_cycles(self):
        # one-shot 은 기다리지 않는 대신 precpu 가 비어 있다. 처음 본 주기에는 CPU 를 내지 않고
        # 다음 주기부터 직전 누적값과의 차로 낸다(2코어 상한 컨테이너가 코어 하나를 쓰면 100, 상한 2)
        def stats(total, system):
            return {"cpu_stats": {"cpu_usage": {"total_usage": total}, "system_cpu_usage": system, "online_cpus": 8}}
        info = {"HostConfig": {"NanoCpus": 2_000_000_000}}
        listing = [{"Id": "one", "Names": ["/app"]}]
        containers._prev_cpu.clear()
        calls = []

        def api(path, answers=iter([listing, stats(1_000, 80_000), info, listing, stats(6_000, 120_000), info])):
            calls.append(path)
            return next(answers)

        with patch.object(containers, "_api", side_effect=api):
            first = containers.sample()
            second = containers.sample()
        self.assertNotIn('container_cpu_percent{name="app"}', first)
        self.assertIn('container_cpu_limit_cores{name="app"} 2', first)
        # (6000-1000)/(120000-80000) × 8코어 × 100 = 100
        self.assertIn('container_cpu_percent{name="app"} 100', second)
        self.assertTrue(all("one-shot=true" in c for c in calls if "/stats" in c))
        containers._prev_cpu.clear()

    def test_empty_inventory_requires_explicit_option(self):
        inventory = {name: [] for name in targets.GROUPS}
        with self.assertRaises(ValueError):
            targets.generate(inventory)
        self.assertEqual(targets.generate(inventory, True), inventory)

    def test_inventory_rejects_credentials_and_metadata(self):
        for address in ["user:secret@example.org:9100", "169.254.169.254:80", "0.0.0.0:9100", "host:bad"]:
            with self.assertRaises(ValueError):
                targets.endpoint(address)
        with self.assertRaises(ValueError):
            targets.endpoint("https://example.org/?token=secret", True)

    def test_inventory_write_is_complete_and_atomic(self):
        import json
        inventory = json.loads((ROOT / "deploy/observability/inventory.example.json").read_text())
        generated = targets.generate(inventory)
        with tempfile.TemporaryDirectory() as path:
            targets.write_targets(Path(path), generated)
            self.assertEqual(len(list(Path(path).iterdir())), 5)
            for group in targets.GROUPS:
                self.assertEqual(json.loads((Path(path) / (group + ".json")).read_text()), generated[group])

    def test_inventory_environment_label_is_validated(self):
        import json
        inventory = json.loads((ROOT / "deploy/observability/inventory.example.json").read_text())
        inventory["apps"][0]["labels"]["environment"] = "local-lab"
        self.assertEqual(targets.generate(inventory)["apps"][0]["labels"]["environment"], "local-lab")
        inventory["apps"][0]["labels"]["environment"] = "local lab\n"
        with self.assertRaises(ValueError):
            targets.generate(inventory)

    def test_host_secrets_are_removed(self):
        output = host.redact("token=hidden password: secret Authorization: Bearer abc https://example.com?sig=secret")
        for value in ["hidden", "secret", "abc", "https://"]:
            self.assertNotIn(value, output)

    def test_command_failure_is_not_empty_success(self):
        with patch.object(host.subprocess, "run", side_effect=FileNotFoundError()):
            self.assertFalse(host.command(["missing"])["ok"])

    def test_atomic_metrics_write(self):
        with tempfile.TemporaryDirectory() as path:
            output = Path(path) / "state.prom"
            host.atomic_write(output, "success 1\n", 0o644)
            host.atomic_write(output, "success 0\n", 0o644)
            self.assertEqual(output.read_text(), "success 0\n")
            self.assertEqual([p.name for p in Path(path).iterdir()], ["state.prom"])


if __name__ == "__main__":
    unittest.main()
