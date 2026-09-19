import importlib.util
import json
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


profiles = load("profiles", HERE / "configure-environment.py")
drill = load("monitored_standby", HERE / "tests/check-monitored-standby.py")


class EnvironmentTests(unittest.TestCase):
    def test_aws_uses_direct_linux_endpoints_and_scopes_every_query(self):
        files = profiles.configuration("aws", "exchange", "http://10.42.1.10:9088",
                                       "http://127.0.0.1:59090", "http://127.0.0.1:59093")
        config = json.loads(files["reporting.json"])
        self.assertEqual(config["analyzer"], "facts-only")
        self.assertIn('environment="aws"', config["alert_matchers"])
        for query in config["queries"]:
            self.assertEqual(query["expr"].count('customer="exchange"'), query["expr"].count('environment="aws"'))
            self.assertIn('role=~"app|reader|data|standby|probe"', query["expr"])
        self.assertIn("http://10.42.1.10:9088/alerts", files["alertmanager.yml"])
        self.assertNotIn("/reporter/", files["alertmanager.yml"])
        self.assertNotIn("local-quiet", files["alertmanager.yml"])
        self.assertNotIn("/node/metrics", files["prometheus.yml"])
        self.assertIn("environment: aws", files["prometheus.yml"])

    def test_local_bridge_and_quiet_scope_preserve_customer_db_alerts(self):
        files = profiles.configuration("local-lab", "exchange", "http://host.docker.internal:59188",
                                       "http://172.22.16.1:59090", "http://172.22.16.1:59093")
        self.assertIn("/reporter/alerts", files["alertmanager.yml"])
        self.assertIn("/node/metrics", files["prometheus.yml"])
        self.assertIn("local-quiet", files["alertmanager.yml"])
        self.assertIn("customer=\"operations-lab\"", files["alertmanager.yml"])
        self.assertIn("receiver: evidence-reporter", files["alertmanager.yml"])
        self.assertEqual(json.loads(files["reporting.json"])["environment"], "local-lab")

    def test_reject_secret_urls_and_scope_injection(self):
        for url in ("http://user:pass@host", "https://host?key=secret", "http://host/alerts", "ftp://host"):
            with self.assertRaises(ValueError):
                profiles.configuration("aws", "exchange", url, "http://localhost:59090", "http://localhost:59093")
        with self.assertRaises(ValueError):
            profiles.configuration("aws", 'exchange",role="bot', "http://10.0.0.1:9088", "http://localhost:59090", "http://localhost:59093")

    def test_drill_refuses_other_container_or_nonrunning_replica(self):
        candidate = {"Name": "/" + drill.REPLICA, "State": {"Running": True},
                     "Config": {"Labels": {"com.docker.compose.project": "failstop-ops-lab", "com.docker.compose.service": "rejoined"}}}
        self.assertTrue(drill.replica_identity(candidate))
        candidate["Name"] = "/" + drill.PRIMARY
        self.assertFalse(drill.replica_identity(candidate))
        candidate["Name"] = "/" + drill.REPLICA
        candidate["State"]["Running"] = False
        self.assertFalse(drill.replica_identity(candidate))


if __name__ == "__main__":
    unittest.main()
