import datetime as dt
import http.server
import importlib.util
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest import mock

WATCHDOG = Path(__file__).resolve().parents[1] / "watchdog"
spec = importlib.util.spec_from_file_location("failstop_watchdog", WATCHDOG / "failstop_watchdog.py")
watchdog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watchdog)

KST = dt.timezone(dt.timedelta(hours=9))
CONFIG = {"fail_after": 3, "recover_after": 2, "remind_seconds": 3600, "watcher": "app-1",
          "customer": "exchange", "environment": "aws"}
WEBHOOK = "https://prod-00.koreacentral.logic.azure.com/workflows/abc/triggers/manual/paths/invoke?sig=SECRETSIG"


def result(name, ok, error="network_error", target="bot-1"):
    return {"name": name, "target": target, "label": name + " 점검", "ok": ok,
            "error": None if ok else error, "detail": "" if ok else "세부 내용"}


def fresh_state():
    return {"schema_version": 1, "checks": {}, "outbox": []}


def at(text):
    return dt.datetime.fromisoformat(text).replace(tzinfo=KST).timestamp()


def fake_stat(mode, uid=0, size=64):
    return os.stat_result((mode, 0, 0, 1, uid, 0, size, 0, 0, 0))


def env(now=1000.0, fetch=None, systemctl=None, secret=None):
    return SimpleNamespace(now=now, timeout=5, fetch=fetch, systemctl=systemctl,
                           secret=secret or (lambda path, allow_group_read=False: "token-value"))


class TransitionTest(unittest.TestCase):
    def run_rounds(self, state, rounds, start=0.0):
        events = []
        for index, ok in enumerate(rounds):
            events += watchdog.apply_results(state, [result("grafana", ok)], start + index * 60, CONFIG)
        return events

    def test_fires_only_after_three_consecutive_failures(self):
        state = fresh_state()
        self.assertEqual(self.run_rounds(state, [False, False]), [])
        events = watchdog.apply_results(state, [result("grafana", False)], 120, CONFIG)
        self.assertEqual([item["kind"] for item in events], ["fire"])
        self.assertEqual(events[0]["since"], 0)
        self.assertTrue(state["checks"]["grafana"]["alerting"])

    def test_success_between_failures_restarts_the_count(self):
        state = fresh_state()
        self.assertEqual(self.run_rounds(state, [False, False, True, False, False]), [])
        self.assertFalse(state["checks"]["grafana"]["alerting"])

    def test_recovers_after_two_consecutive_successes(self):
        state = fresh_state()
        self.run_rounds(state, [False, False, False])
        self.assertEqual(watchdog.apply_results(state, [result("grafana", True)], 180, CONFIG), [])
        events = watchdog.apply_results(state, [result("grafana", True)], 240, CONFIG)
        self.assertEqual([(item["kind"], item["since"], item["at"]) for item in events], [("recover", 0, 240)])
        self.assertFalse(state["checks"]["grafana"]["alerting"])

    def test_reminds_only_after_the_interval(self):
        state = fresh_state()
        self.run_rounds(state, [False, False, False])
        self.assertEqual(watchdog.apply_results(state, [result("grafana", False)], 120 + 3599, CONFIG), [])
        events = watchdog.apply_results(state, [result("grafana", False)], 120 + 3600, CONFIG)
        self.assertEqual([item["kind"] for item in events], ["remind"])
        self.assertEqual(watchdog.apply_results(state, [result("grafana", False)], 120 + 3660, CONFIG), [])

    def test_state_of_removed_check_is_dropped(self):
        state = fresh_state()
        watchdog.apply_results(state, [result("grafana", False), result("reporting", False)], 0, CONFIG)
        watchdog.apply_results(state, [result("grafana", False)], 60, CONFIG)
        self.assertEqual(set(state["checks"]), {"grafana"})


class OutboxTest(unittest.TestCase):
    def fired(self):
        state = fresh_state()
        for second in (0, 60, 120):
            events = watchdog.apply_results(state, [result("grafana", False), result("reporting", False)], second, CONFIG)
            watchdog.enqueue(state, events, second)
        return state

    def test_simultaneous_failures_share_one_notice_with_unreachable_hint(self):
        state = self.fired()
        self.assertEqual(len(state["outbox"]), 1)
        message = state["outbox"][0]
        self.assertEqual([item["check"] for item in message["events"]], ["grafana", "reporting"])
        self.assertEqual(len(message["hints"]), 1)
        self.assertIn("bot-1", message["hints"][0])

    def test_no_hint_when_the_target_still_answers_some_checks(self):
        state = fresh_state()
        for second in (0, 60, 120):
            events = watchdog.apply_results(state, [result("grafana", False, "http_503"), result("reporting", True)],
                                            second, CONFIG)
            watchdog.enqueue(state, events, second)
        self.assertEqual(state["outbox"][0]["hints"], [])

    def test_reminder_waits_while_an_earlier_notice_is_pending(self):
        state = self.fired()
        events = watchdog.apply_results(state, [result("grafana", False), result("reporting", False)], 3720, CONFIG)
        self.assertEqual(len(events), 2)
        watchdog.enqueue(state, events, 3720)
        self.assertEqual(len(state["outbox"]), 1)

    def test_recovery_replaces_a_fire_notice_that_never_left(self):
        state = self.fired()
        for second in (180, 240):
            events = watchdog.apply_results(state, [result("grafana", True), result("reporting", False)], second, CONFIG)
            watchdog.enqueue(state, events, second)
        self.assertEqual([[item["check"] for item in message["events"]] for message in state["outbox"]],
                         [["reporting"], ["grafana"]])
        recovery = state["outbox"][1]["events"][0]
        self.assertEqual(recovery["kind"], "recover")
        self.assertTrue(recovery["unannounced"])

    def test_recovery_keeps_a_notice_that_may_have_been_delivered(self):
        state = self.fired()
        state["outbox"][0]["uncertain"] = 1
        for second in (180, 240):
            events = watchdog.apply_results(state, [result("grafana", True), result("reporting", True)], second, CONFIG)
            watchdog.enqueue(state, events, second)
        self.assertEqual(len(state["outbox"]), 2)
        self.assertEqual(len(state["outbox"][0]["events"]), 2)
        self.assertFalse(any(item.get("unannounced") for item in state["outbox"][1]["events"]))

    def test_outbox_keeps_only_the_latest_messages(self):
        state = fresh_state()
        for index in range(25):
            watchdog.enqueue(state, [watchdog.event("fire", result("c%d" % index, False), 0, index)], index)
        self.assertEqual(len(state["outbox"]), watchdog.MAX_OUTBOX)
        self.assertEqual(state["outbox"][0]["events"][0]["check"], "c5")


class DeliveryTest(unittest.TestCase):
    def pending(self, count=1):
        state = fresh_state()
        for index in range(count):
            watchdog.enqueue(state, [watchdog.event("fire", result("c%d" % index, False), 0, index)], index)
        return state

    def deliver(self, state, outcomes):
        sent, snapshots = [], []

        def send(payload):
            sent.append(payload)
            return outcomes.pop(0)

        def persist(current):
            snapshots.append(json.loads(json.dumps(current)))

        return watchdog.deliver(state, CONFIG, send, persist, 500), sent, snapshots

    def test_accepted_notice_leaves_the_outbox(self):
        state = self.pending()
        results, sent, snapshots = self.deliver(state, [("sent", None)])
        self.assertEqual(state["outbox"], [])
        self.assertEqual(len(sent), 1)
        self.assertTrue(snapshots[0]["outbox"][0]["inflight"])
        self.assertEqual(results[0]["outcome"], "sent")

    def test_retryable_failure_keeps_the_notice_and_stops_the_round(self):
        state = self.pending(2)
        results, sent, _ = self.deliver(state, [("retry", "http_429")])
        self.assertEqual(len(sent), 1)
        self.assertEqual([message["tries"] for message in state["outbox"]], [1, 0])
        self.assertNotIn("inflight", state["outbox"][0])
        self.assertFalse(results[0]["dropped"])

    def test_uncertain_response_is_resent_once_and_then_abandoned(self):
        state = self.pending()
        self.deliver(state, [("uncertain", "no_response")])
        self.assertEqual(state["outbox"][0]["uncertain"], 1)
        results, sent, _ = self.deliver(state, [("uncertain", "http_504")])
        footer = sent[0]["attachments"][0]["content"]["body"][-1]["text"]
        self.assertIn("한 번 다시 보냄", footer)
        self.assertEqual(state["outbox"], [])
        self.assertTrue(results[0]["dropped"])

    def test_first_send_has_no_resend_marker(self):
        state = self.pending()
        _, sent, _ = self.deliver(state, [("sent", None)])
        self.assertNotIn("다시 보냄", json.dumps(sent[0], ensure_ascii=False))

    def test_rejected_notice_is_dropped(self):
        state = self.pending()
        results, _, _ = self.deliver(state, [("rejected", "http_404")])
        self.assertEqual(state["outbox"], [])
        self.assertTrue(results[0]["dropped"])

    def test_retries_stop_after_the_limit(self):
        state = self.pending()
        state["outbox"][0]["tries"] = watchdog.MAX_TRIES - 1
        self.deliver(state, [("retry", "connect_failed")])
        self.assertEqual(state["outbox"], [])

    def test_interrupted_send_counts_as_uncertain_after_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            state = self.pending()
            state["outbox"][0]["inflight"] = True
            path.write_text(json.dumps(state), encoding="utf-8")
            loaded = watchdog.load_state(path)
        self.assertEqual(loaded["outbox"][0]["uncertain"], 1)
        self.assertNotIn("inflight", loaded["outbox"][0])

    def reload(self, state):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            path.write_text(json.dumps(state), encoding="utf-8")
            return watchdog.load_state(path)

    def test_interrupted_resend_is_abandoned_without_a_third_send(self):
        state = self.pending(2)
        self.deliver(state, [("uncertain", "no_response")])
        self.assertEqual(state["outbox"][0]["uncertain"], 1)
        # 재전송을 시작해 전송 중 표시를 저장한 순간 프로세스가 끊긴 상태를 만든다.
        state["outbox"][0].update(inflight=True, tries=2)
        loaded = self.reload(state)
        self.assertEqual(loaded["outbox"][0]["uncertain"], 2)
        results, sent, _ = self.deliver(loaded, [("sent", None)])
        self.assertEqual(len(sent), 1)
        self.assertIn("c1", json.dumps(sent[0], ensure_ascii=False))
        self.assertEqual([(item["outcome"], item["code"], item["dropped"]) for item in results],
                         [("abandoned", "uncertain_limit", True), ("sent", None, True)])
        self.assertEqual(loaded["outbox"], [])

    def test_interrupted_send_at_the_try_limit_is_not_sent_again(self):
        state = self.pending()
        state["outbox"][0].update(inflight=True, tries=watchdog.MAX_TRIES)
        results, sent, _ = self.deliver(self.reload(state), [])
        self.assertEqual(sent, [])
        self.assertEqual(results[0]["code"], "try_limit")


class FakeResponse:
    def __init__(self, status):
        self.status = status

    def read(self, limit):
        return b"{}"


class FakeConnection:
    def __init__(self, behaviour):
        self.behaviour = behaviour
        self.requests = []

    def __call__(self, host, port, timeout, context):
        self.host, self.port = host, port
        return self

    def connect(self):
        if self.behaviour == "connect":
            raise ConnectionRefusedError()

    def request(self, method, target, body, headers):
        self.requests.append((method, target, body))
        if self.behaviour == "send":
            raise BrokenPipeError()

    def getresponse(self):
        if self.behaviour == "timeout":
            raise socket.timeout()
        return FakeResponse(self.behaviour)

    def close(self):
        pass


class PostCardTest(unittest.TestCase):
    def post(self, behaviour):
        connection = FakeConnection(behaviour)
        return watchdog.post_card(WEBHOOK, {"type": "message"}, 10, connection), connection

    def test_failure_before_the_request_is_retryable(self):
        self.assertEqual(self.post("connect")[0], ("retry", "connect_failed"))

    def test_failure_after_the_request_is_uncertain(self):
        self.assertEqual(self.post("timeout")[0], ("uncertain", "no_response"))
        self.assertEqual(self.post("send")[0], ("uncertain", "no_response"))

    def test_status_classification(self):
        cases = {202: "sent", 200: "sent", 408: "retry", 429: "retry", 503: "retry", 500: "uncertain",
                 502: "uncertain", 504: "uncertain", 302: "rejected", 400: "rejected", 404: "rejected"}
        for status, expected in cases.items():
            self.assertEqual(self.post(status)[0][0], expected, status)

    def test_posts_to_the_workflow_path_with_query(self):
        (outcome, code), connection = self.post(202)
        self.assertEqual((connection.host, connection.port), ("prod-00.koreacentral.logic.azure.com", 443))
        self.assertEqual(connection.requests[0][1], "/workflows/abc/triggers/manual/paths/invoke?sig=SECRETSIG")
        self.assertIsNone(code)


class SecretTest(unittest.TestCase):
    def test_owner_and_mode_rules(self):
        problem = watchdog.secret_problem
        self.assertIsNone(problem(fake_stat(0o100600), 0))
        self.assertIsNone(problem(fake_stat(0o100400, uid=990), 990))
        self.assertEqual(problem(fake_stat(0o100640), 0), "secret_permissions_too_open")
        self.assertIsNone(problem(fake_stat(0o100640), 0, allow_group_read=True))
        self.assertEqual(problem(fake_stat(0o100660), 0, allow_group_read=True), "secret_permissions_too_open")
        self.assertEqual(problem(fake_stat(0o100644), 0, allow_group_read=True), "secret_permissions_too_open")
        self.assertEqual(problem(fake_stat(0o100600, uid=1000), 990), "secret_owner_untrusted")
        self.assertEqual(problem(fake_stat(0o040700), 0), "secret_not_regular_file")
        self.assertEqual(problem(fake_stat(0o100600, size=0), 0), "secret_size_invalid")

    def test_read_rejects_open_permissions_without_revealing_the_value(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "teams-webhook"
            path.write_text(WEBHOOK + "\n", encoding="utf-8")
            with mock.patch.object(watchdog.os, "fstat", return_value=fake_stat(0o100644, size=len(WEBHOOK) + 1)):
                with self.assertRaises(watchdog.WatchdogError) as caught:
                    watchdog.read_secret(str(path))
        self.assertEqual(str(caught.exception), "secret_permissions_too_open:teams-webhook")
        self.assertNotIn("SECRETSIG", str(caught.exception))

    def read_through_credentials(self, copy_mode, original_mode=0o100600, copy_uid=0):
        """systemd 사본과 운영자 원본의 stat을 각각 바꿔 read_secret 결과를 돌려준다."""
        with tempfile.TemporaryDirectory() as folder:
            original = Path(folder) / "etc" / "teams-webhook"
            original.parent.mkdir()
            original.write_text("original-is-not-read\n", encoding="utf-8")
            credentials = Path(folder) / "credentials"
            credentials.mkdir()
            (credentials / "teams-webhook").write_text(WEBHOOK + "\n", encoding="utf-8")
            real_lstat = os.lstat
            size = len(WEBHOOK) + 1

            def lstat(target, *args, **kwargs):
                if str(target) == str(original):
                    return fake_stat(original_mode, size=22)
                return real_lstat(target, *args, **kwargs)

            with mock.patch.dict(os.environ, {"CREDENTIALS_DIRECTORY": str(credentials)}), \
                    mock.patch.object(watchdog.os, "lstat", side_effect=lstat), \
                    mock.patch.object(watchdog.os, "geteuid", create=True, return_value=990), \
                    mock.patch.object(watchdog.os, "fstat", return_value=fake_stat(copy_mode, uid=copy_uid, size=size)):
                try:
                    return watchdog.read_secret(str(original))
                except watchdog.WatchdogError as error:
                    return str(error)

    def test_systemd_credential_copy_is_preferred(self):
        # systemd가 ramfs 사본을 실행 계정에 chown한 경우
        self.assertEqual(self.read_through_credentials(0o100400, copy_uid=990), WEBHOOK)

    def test_credential_copy_with_an_acl_mask_is_accepted(self):
        # tmpfs 사본은 root 0400에 사용자 ACL이 붙어 st_mode가 0440으로 보인다.
        self.assertEqual(self.read_through_credentials(0o100440), WEBHOOK)
        self.assertEqual(self.read_through_credentials(0o100444), "secret_permissions_too_open:teams-webhook")
        self.assertEqual(self.read_through_credentials(0o100460), "secret_permissions_too_open:teams-webhook")
        self.assertEqual(self.read_through_credentials(0o100440, copy_uid=1000), "secret_owner_untrusted:teams-webhook")

    def test_operator_original_stays_strict_when_a_copy_is_used(self):
        self.assertEqual(self.read_through_credentials(0o100400, original_mode=0o100640),
                         "secret_permissions_too_open:teams-webhook")
        self.assertEqual(self.read_through_credentials(0o100400, original_mode=0o120777),
                         "secret_not_regular_file:teams-webhook")

    def test_group_mask_is_rejected_outside_the_credentials_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "teams-webhook"
            path.write_text(WEBHOOK + "\n", encoding="utf-8")
            environment = {key: value for key, value in os.environ.items() if key != "CREDENTIALS_DIRECTORY"}
            with mock.patch.dict(os.environ, environment, clear=True), \
                    mock.patch.object(watchdog.os, "fstat", return_value=fake_stat(0o100440, size=len(WEBHOOK) + 1)):
                with self.assertRaises(watchdog.WatchdogError) as caught:
                    watchdog.read_secret(str(path))
        self.assertEqual(str(caught.exception), "secret_permissions_too_open:teams-webhook")

    @unittest.skipUnless(os.name == "posix", "POSIX 권한과 O_NOFOLLOW가 필요하다")
    def test_real_file_permissions_and_symlink(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "teams-webhook"
            path.write_text(WEBHOOK + "\n", encoding="utf-8")
            path.chmod(0o644)
            with self.assertRaises(watchdog.WatchdogError):
                watchdog.read_secret(str(path))
            path.chmod(0o600)
            self.assertEqual(watchdog.read_secret(str(path)), WEBHOOK)
            link = Path(folder) / "link"
            link.symlink_to(path)
            with self.assertRaises(watchdog.WatchdogError):
                watchdog.read_secret(str(link))

    def test_webhook_must_be_a_teams_workflow_over_https(self):
        read = lambda value: (lambda path, allow_group_read=False: value)
        self.assertEqual(watchdog.webhook_url("/x", read(WEBHOOK)), WEBHOOK)
        for bad in ("http://prod-00.koreacentral.logic.azure.com/w?sig=SECRETSIG",
                    "https://example.com/w?sig=SECRETSIG",
                    "https://user:pw@prod-00.koreacentral.logic.azure.com/w?sig=SECRETSIG",
                    "https://prod-00.koreacentral.logic.azure.com:8443/w?sig=SECRETSIG",
                    "https://prod-00.koreacentral.logic.azure.com:x/w?sig=SECRETSIG"):
            with self.assertRaises(watchdog.WatchdogError) as caught:
                watchdog.webhook_url("/x", read(bad))
            self.assertNotIn("SECRETSIG", str(caught.exception))


def alert(ends_at, **labels):
    return {"labels": dict({"alertname": "Watchdog", "customer": "exchange", "environment": "aws"}, **labels),
            "endsAt": ends_at, "status": {"state": "active"}}


class AlertmanagerWatchdogTest(unittest.TestCase):
    CHECK = {"name": "alert_pipeline", "type": "alertmanager_watchdog", "target": "bot-1", "label": "Watchdog",
             "url": "http://127.0.0.1:9093", "labels": {"customer": "exchange", "environment": "aws"}}
    NOW = dt.datetime(2026, 9, 23, 1, 0, tzinfo=dt.timezone.utc).timestamp()

    def run_with(self, status, body):
        requests = []

        def fetch(url, timeout, headers=None, want_cert=False):
            requests.append(url)
            return status, json.dumps(body).encode(), None

        outcome = watchdog.run_check(self.CHECK, env(self.NOW, fetch))
        return outcome, requests

    def test_active_watchdog_passes(self):
        outcome, requests = self.run_with(200, [alert("2026-09-23T01:03:59.123456789Z")])
        self.assertTrue(outcome["ok"], outcome)
        self.assertIn("filter=alertname%3D%22Watchdog%22", requests[0])

    def test_missing_expired_or_foreign_watchdog_means_the_pipeline_is_broken(self):
        for body in ([], [alert("2026-09-23T00:59:00Z")], [alert("2026-09-23T01:04:00Z", environment="local-lab")]):
            outcome, _ = self.run_with(200, body)
            self.assertEqual(outcome["error"], "watchdog_missing", body)

    def test_alertmanager_errors_are_reported_separately(self):
        self.assertEqual(self.run_with(500, [])[0]["error"], "http_500")

        def down(url, timeout, headers=None, want_cert=False):
            raise watchdog.CheckFailed("network_error")

        self.assertEqual(watchdog.run_check(self.CHECK, env(self.NOW, down))["error"], "network_error")
        self.assertEqual(self.run_with(200, [alert("yesterday")])[0]["error"], "invalid_timestamp")


class HttpCheckTest(unittest.TestCase):
    CHECK = {"name": "grafana", "type": "http", "target": "bot-1", "label": "Grafana",
             "url": "http://172.31.44.141:3000/grafana/api/health", "expect_json": {"database": "ok"}}

    def outcome(self, status, body):
        return watchdog.run_check(self.CHECK, env(fetch=lambda url, timeout, headers=None, want_cert=False:
                                                  (status, body, None)))

    def test_expected_status_and_body(self):
        self.assertTrue(self.outcome(200, b'{"database":"ok","version":"13.1.0"}')["ok"])
        self.assertEqual(self.outcome(200, b'{"database":"failing"}')["error"], "unexpected_response")
        self.assertEqual(self.outcome(200, b"<html>")["error"], "invalid_json")
        self.assertEqual(self.outcome(503, b'{"database":"failing"}')["error"], "http_503")

    def test_fetch_reads_a_local_server_without_following_redirects(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                status, body = {"/health": (200, b'{"status":"ok"}'), "/big": (200, b"x" * (watchdog.RESPONSE_LIMIT + 1))
                                }.get(self.path, (302, b""))
                self.send_response(status)
                if status == 302:
                    self.send_header("Location", "/health")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:%d" % server.server_port
        try:
            self.assertEqual(watchdog.fetch(base + "/health", 5)[:2], (200, b'{"status":"ok"}'))
            self.assertEqual(watchdog.fetch(base + "/moved", 5)[0], 302)
            with self.assertRaises(watchdog.CheckFailed) as caught:
                watchdog.fetch(base + "/big", 5)
            self.assertEqual(caught.exception.code, "response_too_large")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


METRICS = b"""report_jobs{status="pending"} 0
report_jobs{status="dead"} 0
report_last_daily_sent_timestamp_seconds %d
report_oldest_pending_age_seconds %s
report_delivery_enabled %d
"""


class ReportQueueTest(unittest.TestCase):
    CHECK = {"name": "report_queue", "type": "report_queue", "target": "bot-1", "label": "보고 대기열",
             "url": "http://172.31.44.141:9088/metrics", "token_file": "/etc/failstop/reporter-token",
             "max_dead": 0, "max_pending_seconds": 1800}

    def outcome(self, body, check=None, secret=None):
        seen = {}

        def fetch(url, timeout, headers=None, want_cert=False):
            seen["headers"] = headers
            return 200, body, None

        return watchdog.run_check(check or self.CHECK, env(fetch=fetch, secret=secret)), seen

    def test_healthy_queue_uses_the_bearer_token(self):
        outcome, seen = self.outcome(METRICS % (0, b"0", 1))
        self.assertTrue(outcome["ok"], outcome)
        self.assertEqual(seen["headers"], {"Authorization": "Bearer token-value"})

    def test_dead_pending_and_disabled_delivery_fail(self):
        dead = METRICS.replace(b'status="dead"} 0', b'status="dead"} 2')
        self.assertEqual(self.outcome(dead % (0, b"0", 1))[0]["error"], "report_dead")
        self.assertTrue(self.outcome(dead % (0, b"0", 1), dict(self.CHECK, max_dead=2))[0]["ok"])
        stale = self.outcome(METRICS % (0, b"4000.5", 1))[0]
        self.assertEqual(stale["error"], "report_pending_stale")
        self.assertIn("66분", stale["detail"])
        self.assertEqual(self.outcome(METRICS % (0, b"0", 0))[0]["error"], "report_delivery_disabled")
        both = self.outcome(dead % (0, b"4000", 0))[0]
        self.assertEqual(both["error"], "report_dead")
        self.assertEqual(both["detail"].count(" · "), 2)

    def test_missing_metric_or_token_is_a_failure(self):
        self.assertEqual(self.outcome(b"report_jobs{status=\"dead\"} 0\n")[0]["error"], "metric_missing")
        self.assertEqual(self.outcome(b"report_delivery_enabled NaN\n")[0]["error"], "invalid_metrics")

        def broken(path, allow_group_read=False):
            raise watchdog.WatchdogError("secret_unreadable:reporter-token")

        self.assertEqual(self.outcome(METRICS % (0, b"0", 1), secret=broken)[0]["error"], "token_unavailable")


class DailyReportTest(unittest.TestCase):
    def problem(self, now, trigger, sent, timer="active", result="success"):
        return watchdog.daily_problem(at(now), timer, None if trigger is None else at(trigger), result,
                                      0 if sent is None else at(sent))

    def test_today_report_triggered_and_sent(self):
        self.assertIsNone(self.problem("2026-09-23T10:00:00", "2026-09-23T09:00:20", "2026-09-23T09:02:00"))

    def test_grace_windows_judge_against_the_previous_day(self):
        self.assertIsNone(self.problem("2026-09-23T09:05:00", "2026-09-22T09:00:20", "2026-09-22T09:03:00"))
        self.assertIsNone(self.problem("2026-09-23T09:20:00", "2026-09-23T09:00:20", "2026-09-22T09:03:00"))
        self.assertIsNone(self.problem("2026-09-23T08:59:00", "2026-09-22T09:00:20", "2026-09-22T09:03:00"))

    def test_missing_trigger_or_delivery_is_reported(self):
        self.assertEqual(self.problem("2026-09-23T10:00:00", "2026-09-22T09:00:20", "2026-09-22T09:03:00")[0],
                         "daily_not_triggered")
        self.assertEqual(self.problem("2026-09-23T10:00:00", None, None)[0], "daily_not_triggered")
        self.assertEqual(self.problem("2026-09-23T09:40:00", "2026-09-23T09:00:20", "2026-09-22T09:03:00")[0],
                         "daily_not_sent")
        self.assertEqual(self.problem("2026-09-23T10:00:00", "2026-09-23T09:00:20", None)[0], "daily_never_sent")
        self.assertEqual(self.problem("2026-09-23T10:00:00", "2026-09-23T09:00:20", "2026-09-23T09:02:00",
                                      timer="inactive")[0], "daily_timer_inactive")
        self.assertEqual(self.problem("2026-09-23T10:00:00", "2026-09-23T09:00:20", "2026-09-23T09:02:00",
                                      result="exit-code")[0], "daily_enqueue_failed")

    def test_systemd_timestamps_are_read_in_utc(self):
        self.assertEqual(watchdog.parse_systemd_time("Wed 2026-09-23 00:00:20 UTC"), at("2026-09-23T09:00:20"))
        self.assertIsNone(watchdog.parse_systemd_time("n/a"))
        self.assertIsNone(watchdog.parse_systemd_time(""))
        with self.assertRaises(watchdog.CheckFailed):
            watchdog.parse_systemd_time("Wed 2026-09-23 09:00:20 KST")

    def test_check_combines_timer_service_and_metrics(self):
        check = {"name": "daily_report", "type": "daily_report", "target": "bot-1", "label": "일일 보고",
                 "url": "http://172.31.44.141:9088/metrics", "token_file": "/etc/failstop/reporter-token",
                 "timer": "failstop-reporting-daily.timer", "service": "failstop-reporting-daily.service"}
        units = {"failstop-reporting-daily.timer": {"ActiveState": "active",
                                                   "LastTriggerUSec": "Wed 2026-09-23 00:00:20 UTC"},
                 "failstop-reporting-daily.service": {"Result": "success"}}
        systemctl = lambda unit, names, timeout: units[unit]
        body = METRICS % (at("2026-09-23T09:02:00"), b"0", 1)
        fetch = lambda url, timeout, headers=None, want_cert=False: (200, body, None)
        self.assertTrue(watchdog.run_check(check, env(at("2026-09-23T10:00:00"), fetch, systemctl))["ok"])
        failed = watchdog.run_check(check, env(at("2026-09-24T10:00:00"), fetch, systemctl))
        self.assertEqual(failed["error"], "daily_not_triggered")
        self.assertIn("09-24 09:00 KST", failed["detail"])


class SystemdTest(unittest.TestCase):
    def test_show_output_is_parsed_and_missing_units_fail(self):
        output = "LoadState=loaded\nActiveState=failed\nSubState=failed\nResult=exit-code\n"
        with mock.patch.object(watchdog.subprocess, "run",
                               return_value=SimpleNamespace(returncode=0, stdout=output)) as run:
            values = watchdog.systemctl_show("exchange-bot.service", ("ActiveState", "SubState", "Result"), 5)
        self.assertEqual(values["ActiveState"], "failed")
        self.assertEqual(run.call_args.kwargs["env"]["TZ"], "UTC")
        with mock.patch.object(watchdog.subprocess, "run",
                               return_value=SimpleNamespace(returncode=0, stdout="LoadState=not-found\nResult=success\n")):
            with self.assertRaises(watchdog.CheckFailed) as caught:
                watchdog.systemctl_show("nope.service", ("Result",), 5)
        self.assertEqual(caught.exception.code, "unit_not_found")
        with mock.patch.object(watchdog.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout="")):
            with self.assertRaises(watchdog.CheckFailed):
                watchdog.systemctl_show("exchange-bot.service", ("Result",), 5)

    def test_stopped_bot_reports_the_capacity_guard_result(self):
        check = {"name": "market_bot", "type": "systemd_active", "target": "bot-1", "label": "시장 봇",
                 "unit": "exchange-bot.service", "context_unit": "exchange-bot-capacity.service"}
        # 용량 보호는 봇을 멈춰도 정상 종료한다(capacity_guard.py). 그래서 봇을 멈춘 경우에도 용량 보호
        # 유닛의 결과는 success이고, 카드의 결과로는 용량 보호 중지와 사람의 중지를 가르지 못한다.
        units = {"exchange-bot.service": {"ActiveState": "inactive", "SubState": "dead", "Result": "success"},
                 "exchange-bot-capacity.service": {"Result": "success"}}
        outcome = watchdog.run_check(check, env(systemctl=lambda unit, names, timeout: units[unit]))
        self.assertEqual(outcome["error"], "unit_inactive")
        self.assertIn("exchange-bot-capacity.service 결과 success", outcome["detail"])
        # exit-code는 용량 보호 스크립트가 판정 기록이나 봇 중지를 끝내지 못한 경우에만 나온다
        units["exchange-bot-capacity.service"]["Result"] = "exit-code"
        outcome = watchdog.run_check(check, env(systemctl=lambda unit, names, timeout: units[unit]))
        self.assertIn("exchange-bot-capacity.service 결과 exit-code", outcome["detail"])
        units["exchange-bot.service"]["ActiveState"] = "active"
        self.assertTrue(watchdog.run_check(check, env(systemctl=lambda unit, names, timeout: units[unit]))["ok"])


class HttpsSiteTest(unittest.TestCase):
    CHECK = {"name": "public_site", "type": "https_site", "target": "app-1", "label": "공개 HTTPS",
             "url": "https://www.sang.it.kr/api/v1/markets", "min_cert_days": 14}
    NOW = dt.datetime(2026, 9, 23, tzinfo=dt.timezone.utc).timestamp()

    def outcome(self, status, not_after):
        fetch = lambda url, timeout, headers=None, want_cert=False: (status, b"{}", {"notAfter": not_after})
        return watchdog.run_check(self.CHECK, env(self.NOW, fetch))

    def test_status_and_certificate_expiry(self):
        self.assertTrue(self.outcome(200, "Nov 20 12:00:00 2026 GMT")["ok"])
        expiring = self.outcome(200, "Oct  1 00:00:00 2026 GMT")
        self.assertEqual(expiring["error"], "certificate_expiring")
        self.assertIn("8일", expiring["detail"])
        self.assertEqual(self.outcome(502, "Nov 20 12:00:00 2026 GMT")["error"], "http_502")


class RunOnceTest(unittest.TestCase):
    def config(self, folder):
        return dict(CONFIG, timeout_seconds=5, textfile=str(Path(folder) / "textfile" / "failstop_watchdog.prom"),
                    teams_secret_file="/etc/failstop-watchdog/teams-webhook",
                    checks=[{"name": "grafana", "type": "http", "target": "bot-1", "label": "Grafana", "enabled": True,
                             "url": "http://172.31.44.141:3000/grafana/api/health"}])

    def test_three_failed_runs_send_one_notice_and_write_metrics(self):
        sent = []

        def sender():
            return lambda payload: sent.append(payload) or ("sent", None)

        def down(url, timeout, headers=None, want_cert=False):
            raise watchdog.CheckFailed("network_error")

        with tempfile.TemporaryDirectory() as folder, mock.patch.object(watchdog, "log"):
            config = self.config(folder)
            state_path = str(Path(folder) / "state.json")
            codes = [watchdog.run_once(config, state_path, 60.0 * index, env(60.0 * index, down), sender)
                     for index in range(4)]
            state = json.loads(Path(state_path).read_text(encoding="utf-8"))
            metrics = Path(config["textfile"]).read_text(encoding="utf-8")
        self.assertEqual(codes, [0, 0, 0, 0])
        self.assertEqual(len(sent), 1)
        self.assertIn("관측 경로 이상 감지", json.dumps(sent[0], ensure_ascii=False))
        self.assertTrue(state["checks"]["grafana"]["alerting"])
        self.assertIn("failstop_watchdog_last_run_timestamp_seconds 180\n", metrics)
        self.assertIn('failstop_watchdog_check_ok{check="grafana",target="bot-1"} 0\n', metrics)
        self.assertIn('failstop_watchdog_check_alerting{check="grafana",target="bot-1"} 1\n', metrics)

    def test_broken_webhook_secret_keeps_the_notice_and_fails_the_run(self):
        def sender():
            raise watchdog.WatchdogError("secret_unreadable:teams-webhook")

        def down(url, timeout, headers=None, want_cert=False):
            raise watchdog.CheckFailed("timeout")

        with tempfile.TemporaryDirectory() as folder, mock.patch.object(watchdog, "log") as log:
            config = self.config(folder)
            state_path = str(Path(folder) / "state.json")
            codes = [watchdog.run_once(config, state_path, 60.0 * index, env(60.0 * index, down), sender)
                     for index in range(3)]
            state = json.loads(Path(state_path).read_text(encoding="utf-8"))
        self.assertEqual(codes, [1, 1, 1])
        self.assertEqual(len(state["outbox"]), 1)
        self.assertNotIn("inflight", state["outbox"][0])
        self.assertEqual(state["outbox"][0]["uncertain"], 0)
        self.assertEqual(log.call_args_list[0].args[0]["event"], "delivery_unavailable")

    def test_rejected_delivery_marks_the_run_failed(self):
        def down(url, timeout, headers=None, want_cert=False):
            raise watchdog.CheckFailed("timeout")

        with tempfile.TemporaryDirectory() as folder, mock.patch.object(watchdog, "log"):
            config = self.config(folder)
            state_path = str(Path(folder) / "state.json")
            codes = [watchdog.run_once(config, state_path, 60.0 * index, env(60.0 * index, down),
                                       lambda: (lambda payload: ("rejected", "http_404"))) for index in range(3)]
        self.assertEqual(codes, [0, 0, 2])

    def test_abandoned_notice_marks_the_run_failed_without_sending(self):
        sent = []

        def up(url, timeout, headers=None, want_cert=False):
            return 200, b"{}", None

        with tempfile.TemporaryDirectory() as folder, mock.patch.object(watchdog, "log") as log:
            config = self.config(folder)
            state_path = Path(folder) / "state.json"
            state = {"schema_version": 1, "checks": {}, "outbox": [
                {"id": "0123456789abcdef", "created": 0, "events": [watchdog.event("fire", result("grafana", False), 0, 0)],
                 "hints": [], "tries": 2, "uncertain": 1, "inflight": True}]}
            state_path.write_text(json.dumps(state), encoding="utf-8")
            code = watchdog.run_once(config, str(state_path), 600.0, env(600.0, up),
                                     lambda: (lambda payload: sent.append(payload) or ("sent", None)))
            saved = json.loads(state_path.read_text(encoding="utf-8"))
        self.assertEqual((code, sent, saved["outbox"]), (2, [], []))
        self.assertEqual(saved["delivery"]["code"], "uncertain_limit")
        self.assertEqual(log.call_args.args[0]["deliveries"][0]["outcome"], "abandoned")

    def test_unreadable_state_starts_fresh(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(watchdog, "log") as log:
            path = Path(folder) / "state.json"
            path.write_text("{broken", encoding="utf-8")
            state = watchdog.load_state(path)
        self.assertEqual(state, {"schema_version": 1, "checks": {}, "outbox": []})
        self.assertEqual(log.call_args.args[0]["event"], "state_reset")

    def test_atomic_write_replaces_without_leftovers(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            watchdog.write_atomic(path, "first\n", 0o600)
            watchdog.write_atomic(path, "second\n", 0o600)
            self.assertEqual(path.read_text(encoding="utf-8"), "second\n")
            self.assertEqual(sorted(item.name for item in Path(folder).iterdir()), ["state.json"])


class CardTest(unittest.TestCase):
    def test_recovery_card_mentions_undelivered_fire_and_never_the_webhook(self):
        item = dict(watchdog.event("recover", result("grafana", True), at("2026-09-23T10:00:00"),
                                   at("2026-09-23T10:07:00")), unannounced=True)
        card = watchdog.build_card({"id": "0123456789abcdef", "events": [item], "hints": []}, CONFIG)
        text = json.dumps(card, ensure_ascii=False)
        self.assertIn("관측 경로 회복", text)
        self.assertIn("09-23 10:00 KST부터 7분 동안 이상", text)
        self.assertIn("이상 알림은 전달되지 않았음", text)
        self.assertNotIn("logic.azure.com", text)

    def test_test_card_does_not_claim_an_operational_state(self):
        card = watchdog.build_card({"id": "test0123456789", "events": [], "hints": []}, CONFIG)
        self.assertIn("운영 상태를 판정하지 않음", json.dumps(card, ensure_ascii=False))


class ConfigTest(unittest.TestCase):
    def write(self, folder, data):
        path = Path(folder) / "config.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    def test_shipped_profiles_are_valid(self):
        app = watchdog.load_config(WATCHDOG / "config.app-1.json")
        bot = watchdog.load_config(WATCHDOG / "config.bot-1.json")
        self.assertEqual([check["name"] for check in app["checks"] if check["enabled"]], ["grafana", "reporting"])
        self.assertEqual({check["type"] for check in bot["checks"]}, set(watchdog.CHECK_TYPES))
        self.assertTrue(all(check["target"] == "bot-1" for check in app["checks"]))

    def test_invalid_checks_are_rejected(self):
        base = json.loads((WATCHDOG / "config.app-1.json").read_text(encoding="utf-8"))
        cases = {
            "duplicate": base["checks"] + [base["checks"][0]],
            "type": [dict(base["checks"][0], type="shell")],
            "userinfo": [dict(base["checks"][0], url="http://user:pw@172.31.44.141:3000/")],
            "https_only": [{"name": "site", "type": "https_site", "target": "app-1", "label": "site",
                            "url": "http://www.sang.it.kr/"}],
            "unit": [{"name": "bot", "type": "systemd_active", "target": "bot-1", "label": "bot",
                      "unit": "exchange-bot.service; reboot"}],
        }
        with tempfile.TemporaryDirectory() as folder:
            for name, checks in cases.items():
                with self.assertRaises(watchdog.WatchdogError, msg=name):
                    watchdog.load_config(self.write(folder, dict(base, checks=checks)))
            with self.assertRaises(watchdog.WatchdogError):
                watchdog.load_config(self.write(folder, dict(base, fail_after=0)))
            with self.assertRaises(watchdog.WatchdogError):
                watchdog.load_config(self.write(folder, dict(base, teams_secret_file="teams-webhook")))


if __name__ == "__main__":
    unittest.main()
