"""Exercise authentication, tenant scope, report grants and operator acknowledgement."""
import base64
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from server import ConsoleServer, MetricsUnavailable, UpstreamError, config_from_env, validate_config


class FakeMetrics:
    def scopes(self):
        return [{"customer": "exchange", "environment": "local-lab", "services": ["exchange"], "instances": []}]

    def snapshot(self, *args):
        return {"metrics": {}, "targets": [], "alerts": [{"id": "incident-1", "name": "DatabaseConnectionFailed"}]}


class ConsoleTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        config = config_from_env()
        config.update(state=str(Path(self.folder.name) / "console.sqlite3"), public_url="http://127.0.0.1:53002")
        self.server = ConsoleServer(("127.0.0.1", 0), config)
        self.server.metrics = FakeMetrics()
        self.server.origins = {"http://127.0.0.1:" + str(self.server.server_port)}
        self.server.hosts = {"127.0.0.1:" + str(self.server.server_port)}
        self.viewer = False

        def grafana(path, auth):
            if auth != "Basic " + base64.b64encode(b"operator:test-password").decode():
                raise UpstreamError(401)
            if path == "/api/user":
                return {"id": 1, "orgId": 1, "login": "operator", "name": "Operator", "isGrafanaAdmin": True}
            if path == "/api/user/orgs":
                return [{"orgId": 1, "role": "Viewer" if self.viewer else "Admin"}]
            if path == "/api/org/users":
                return [{"userId": 1, "login": "operator", "role": "Admin", "email": "not-returned@example.test"}]
            if path == "/api/datasources":
                return [{"uid": "prometheus", "name": "Prometheus", "type": "prometheus", "url": "http://user:secret@prometheus:9090/?secret=hidden", "secureJsonData": {"secret": "hidden"}}]
            return [{"uid": "msp-overview", "title": "Overview", "url": "/d/msp-overview"}]

        self.server.grafana = grafana
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.cookie = None

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.folder.cleanup()

    def request(self, path, body=None, origin=True, cookie=True, host=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        headers = {}
        if origin:
            headers["Origin"] = next(iter(self.server.origins))
        if cookie and self.cookie:
            headers["Cookie"] = self.cookie
        if host:
            headers["Host"] = host
        if body is not None:
            headers["Content-Type"] = "application/json"
        connection.request("POST" if body is not None else "GET", path, json.dumps(body) if body is not None else None, headers)
        response = connection.getresponse()
        data, status, received = response.read(), response.status, dict(response.getheaders())
        connection.close()
        return status, json.loads(data), received

    def login(self):
        status, body, headers = self.request("/api/login", {"username": "operator", "password": "test-password"})
        self.assertEqual(status, 200)
        self.cookie = headers["Set-Cookie"].split(";")[0]
        return headers

    def test_api_requires_identity_and_failed_login_leaks_no_credentials(self):
        self.assertEqual(self.request("/api/bootstrap")[0], 401)
        status, body, _ = self.request("/api/login", {"username": "operator", "password": "incorrect"})
        self.assertEqual(status, 401)
        self.assertNotIn("incorrect", json.dumps(body))

    def test_cookie_protection_logout_and_session_invalidation(self):
        headers = self.login()
        self.assertIn("HttpOnly", headers["Set-Cookie"])
        self.assertIn("SameSite=Strict", headers["Set-Cookie"])
        self.assertEqual(self.request("/api/bootstrap")[0], 200)
        self.assertEqual(self.request("/api/logout", {})[0], 200)
        self.assertEqual(self.request("/api/bootstrap")[0], 401)

    def test_origin_and_dns_rebinding_rejection(self):
        self.assertEqual(self.request("/api/login", {"username": "operator", "password": "test-password"}, origin=False)[0], 403)
        self.assertEqual(self.request("/health", host="attacker.example")[0], 403)

    def test_unknown_scope_arbitrary_query_and_range_rejection(self):
        self.login()
        for query in ("customer=other&environment=local-lab", "customer=exchange&environment=local-lab&query=up", "customer=exchange&environment=local-lab&range=999999999", "customer=exchange&customer=other&environment=local-lab"):
            self.assertEqual(self.request("/api/metrics?" + query)[0], 400)

    def test_acknowledgement_persistence_and_active_alert_boundary(self):
        self.login()
        body = {"id": "incident-1", "customer": "exchange", "environment": "local-lab", "note": "DB 담당 확인"}
        self.assertEqual(self.request("/api/ack", body)[0], 200)
        state = self.server.state.acknowledgements("exchange", "local-lab")
        self.assertEqual(state["incident-1"]["by"], "operator")
        self.assertEqual(self.server.state.acknowledgements("other", "local-lab"), {})
        body["id"] = "not-active"
        self.assertEqual(self.request("/api/ack", body)[0], 400)

    def test_viewer_cannot_acknowledge(self):
        self.viewer = True
        self.login()
        self.assertEqual(self.request("/api/ack", {"id": "incident-1", "customer": "exchange", "environment": "local-lab"})[0], 403)

    def test_permission_revocation_rechecked(self):
        self.login()
        session = next(iter(self.server.sessions.values()))
        session["checked"] = time.time() - 61
        self.server.identity = lambda auth: (_ for _ in ()).throw(UpstreamError(403))
        self.assertEqual(self.request("/api/bootstrap")[0], 401)
        self.assertEqual(self.server.sessions, {})

    def test_organization_mismatch_rejected(self):
        self.server.config["org_id"] = 2
        self.assertEqual(self.request("/api/login", {"username": "operator", "password": "test-password"})[0], 401)

    def test_report_path_cannot_become_open_proxy(self):
        self.login()
        for path in ("http://example.test/", "/console/reports", "/reports/../../.env"):
            self.assertEqual(self.request("/api/report-file?path=" + path)[0], 400)

    def test_reporting_outage_is_not_empty_success(self):
        self.login()
        self.server.report_request = lambda *args, **kwargs: (_ for _ in ()).throw(UpstreamError())
        status, body, _ = self.request("/api/reports?customer=exchange&environment=local-lab")
        self.assertEqual(status, 200)
        self.assertEqual(body["state"], "error")

    def test_admin_response_removes_credentials_and_personal_email(self):
        self.login()
        status, body, _ = self.request("/api/admin")
        self.assertEqual(status, 200)
        self.assertEqual(body["datasources"][0]["url"], "http://prometheus:9090/")
        self.assertNotIn("secret", json.dumps(body))
        self.assertNotIn("email", json.dumps(body))

    def test_login_attempts_bounded(self):
        for _ in range(10):
            self.assertEqual(self.request("/api/login", {"username": "operator", "password": "wrong"})[0], 401)
        self.assertEqual(self.request("/api/login", {"username": "operator", "password": "wrong"})[0], 429)

    def test_public_console_requires_https(self):
        config = config_from_env()
        config["public_url"] = "http://console.example.test"
        with self.assertRaises(ValueError):
            validate_config(config)

    def test_prometheus_outage_returns_explicit_status(self):
        self.login()
        self.server.metrics.scopes = lambda: (_ for _ in ()).throw(MetricsUnavailable())
        status, body, _ = self.request("/api/bootstrap")
        self.assertEqual((status, body["sourceState"]), (200, "error"))
        self.assertEqual(self.request("/api/metrics?customer=exchange&environment=local-lab")[0], 502)

    def test_aws_scope_metadata_not_forwarded_to_query_and_cache_isolated(self):
        self.server.config["aws"] = {"customer": "exchange", "environment": "aws", "region": "ap-northeast-2", "auto_scaling_group": "reader"}
        session = {"user": {"id": 1}, "auth": "Basic unused"}
        with patch("cloudwatch.collect_aws", return_value={"metrics": {}, "sources": []}) as collect:
            self.server.aws_metrics("exchange", "aws", 3600, session)
            self.assertEqual(collect.call_args.args[0], {"region": "ap-northeast-2", "auto_scaling_group": "reader"})
            self.server.aws_metrics("exchange", "aws", 3600, session)
            self.assertEqual(collect.call_count, 1)
            self.server.aws_metrics("other", "aws", 3600, session)
            self.assertEqual(collect.call_args.args[0], {})


if __name__ == "__main__":
    unittest.main()
