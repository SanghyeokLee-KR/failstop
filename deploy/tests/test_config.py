import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("deploy_config", Path(__file__).parents[1] / "check-config.py")
CONFIG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONFIG)


class DeploymentConfigTest(unittest.TestCase):
    def test_default_budget_covers_rolling_engine_and_max_readers(self):
        result = CONFIG.budget({})
        self.assertEqual(144, result["engines_including_rollout"])
        self.assertEqual(20, result["readers_at_max"])
        self.assertEqual(188, result["total"])
        self.assertEqual(9, result["headroom"])

    def test_scaling_cannot_exceed_database_budget(self):
        for values in ({"READ_MAX_NODES": "4"}, {"DB_MAX_CONNECTIONS": "100"},
                       {"ENGINE_ROLLOUT_SURGE": "2"}):
            with self.assertRaisesRegex(ValueError, "budget exceeded"):
                CONFIG.budget(values)

    def test_bind_rejects_wildcard_public_and_non_rfc1918(self):
        for address in ("0.0.0.0", "8.8.8.8", "169.254.1.1", "::", "100.64.0.1"):
            self.assertFalse(CONFIG.private_bind(address), address)
        for address in ("127.0.0.1", "10.0.1.3", "172.16.2.3", "192.168.20.1"):
            self.assertTrue(CONFIG.private_bind(address), address)

    def test_environment_errors_never_echo_secret_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "env"
            path.write_text("DB_PASSWORD=secret-value\nDB_PASSWORD=other-secret\n", encoding="utf-8")
            with self.assertRaises(ValueError) as caught:
                CONFIG.read_env(path)
            self.assertNotIn("secret", str(caught.exception))

    def test_invalid_pool_settings_fail_before_deployment(self):
        for values in ({"READ_DB_POOL_MAX": "1"}, {"APP_DB_POOL_MAX": "4"},
                       {"APP_LEASE_POOL_MAX": "0"}, {"APP_APPEND_POOL_MAX": "1"}, {"READ_MAX_NODES": "oops"}):
            with self.assertRaises(ValueError):
                CONFIG.budget(values)


if __name__ == "__main__":
    unittest.main()
