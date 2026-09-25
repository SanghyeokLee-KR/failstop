import copy
import datetime as dt
from pathlib import Path
import re
import unittest

from intervention import (assess, DISK_FREE, INODE_FREE, BACKUP_MAX_AGE_SECONDS,
                          HOST_MAX_AGE_SECONDS, REQUIRED_METRICS)

NOW = dt.datetime(2026, 9, 18, 2, 0, tzinfo=dt.timezone.utc)


def complete_report():
    values = {"target_up": 1, "disk_available": .6, "inode_free": .8, "services_failed": 0,
              "db_connections": 9, "db_replication_lag": 0, "request_rate": 2,
              "server_errors": 0, "latency_p95": .08, "backup_success": NOW.timestamp() - 600}
    facts = [{"id": name, "kind": "metric", "status": "ok", "source": {"step_seconds": 60},
              "samples": [{"last": value, "min": value, "max": value, "labels": {"node": "lab-a", "mountpoint": "/mnt/c"},
                           "last_observed_at": NOW.timestamp()}]} for name, value in values.items()]
    facts += [{"id": "alerts", "status": "ok", "records": []},
              {"id": "host", "status": "ok", "collected_at": NOW.isoformat(), "records": {}},
              {"id": "backup_schedule", "status": "ok", "records": {"enabled": True,
                  "checked_at": NOW.isoformat(), "next_run_at": (NOW + dt.timedelta(hours=1)).isoformat()}},
              {"id": "backup_restore", "status": "ok", "records": {"verified": True, "backup_id": "backup-1",
                  "verified_at": (NOW - dt.timedelta(hours=1)).isoformat()}}]
    return {"kind": "daily", "analysis": {"summary": "전부 정상", "intervention": {"code": "monitor"}},
            "evidence": {"collected_at": NOW.isoformat(), "window_end": NOW.timestamp(), "window_seconds": 900, "facts": facts}}


def fact(report, name):
    return next(row for row in report["evidence"]["facts"] if row["id"] == name)


class InterventionTest(unittest.TestCase):
    def test_policy_thresholds_match_operational_rules(self):
        path = Path(__file__).resolve().parents[1] / "observability/rules/linux-operations.yml"
        rules = path.read_text(encoding="utf-8")
        def expression(name):
            return re.search(r"- alert: " + name + r"\n\s+expr: ([^\n]+)", rules).group(1)
        self.assertIn(f"< {DISK_FREE}", expression("LinuxDiskSpaceLow"))
        self.assertIn("< 0.10", expression("LinuxInodesLow"))
        self.assertEqual(INODE_FREE, .10)
        self.assertIn(f"> {BACKUP_MAX_AGE_SECONDS}", expression("DatabaseBackupUnknownOrStale"))
        self.assertIn(f"> {HOST_MAX_AGE_SECONDS}", expression("LinuxLogCollectionStale"))

    def test_monitor_requires_every_mandatory_source_and_backup_proof(self):
        value = complete_report()
        self.assertEqual(assess(value)["code"], "monitor")
        for identifier in REQUIRED_METRICS | {"alerts", "host", "backup_schedule", "backup_restore"}:
            with self.subTest(identifier=identifier):
                incomplete = copy.deepcopy(value)
                incomplete["evidence"]["facts"] = [row for row in incomplete["evidence"]["facts"] if row["id"] != identifier]
                self.assertEqual(assess(incomplete)["code"], "verify")

    def test_critical_fresh_evidence_needs_immediate_action_despite_ai(self):
        for identifier, value in [("target_up", 0), ("services_failed", 1)]:
            report = complete_report()
            fact(report, identifier)["samples"][0]["last"] = value
            result = assess(report)
            self.assertEqual(result["code"], "immediate")
            self.assertIn("고객 영향 미확인", result["limitations"][0])
            self.assertEqual(result["actions"][0]["priority"], "P1")

    def test_firing_critical_alert_can_escalate_when_metrics_are_missing(self):
        report = complete_report()
        report["evidence"]["facts"] = [fact(report, "alerts")]
        fact(report, "alerts")["records"] = [{"labels": {"alertname": "DatabaseReplicationUnknown", "severity": "critical"},
                                                "status": "firing", "startsAt": (NOW - dt.timedelta(minutes=3)).isoformat()}]
        self.assertEqual(assess(report)["code"], "immediate")

    def test_resolved_backup_with_low_disk_and_http_gaps_still_needs_action(self):
        report = complete_report()
        fact(report, "disk_available")["samples"][0]["last"] = .147
        fact(report, "request_rate").update(status="missing", samples=[])
        report["evidence"]["facts"] = [row for row in report["evidence"]["facts"] if row["id"] not in {"backup_schedule", "backup_restore"}]
        report["evidence"]["facts"].append({"id": "trigger", "status": "ok", "records": [{
            "labels": {"alertname": "DatabaseBackupUnknownOrStale"}, "status": "resolved",
            "endsAt": (NOW - dt.timedelta(minutes=1)).isoformat()}]})
        result = assess(report)
        self.assertEqual(result["code"], "scheduled")
        self.assertEqual(result["label"], "담당자 조치 필요")
        self.assertIn("14.7%", result["reason"])
        self.assertIn("금일 점검", result["actions"][0]["timing"])
        self.assertTrue(any("예약" in row["task"] for row in result["actions"]))
        self.assertTrue(any("request_rate" in row for row in result["limitations"]))

    def test_local_idle_trade_silence_never_escalates_on_its_own(self):
        report = complete_report()
        fact(report, "alerts")["records"] = [{"labels": {"alertname": "TradeSilence", "severity": "critical", "environment": "local-lab"}, "status": "firing"}]
        result = assess(report)
        self.assertEqual(result["code"], "monitor")
        self.assertTrue(any("유휴 실습" in row for row in result["limitations"]))

    def test_stale_future_or_timestamp_missing_samples_cannot_clear_intervention(self):
        for timestamp in (NOW.timestamp() - 18000, NOW.timestamp() + 3600, None):
            report = complete_report()
            fact(report, "target_up")["samples"][0]["last_observed_at"] = timestamp
            self.assertEqual(assess(report)["code"], "verify")
        report = complete_report()
        report["evidence"]["window_end"] -= 18000
        fact(report, "target_up")["source"]["window_end"] = report["evidence"]["window_end"]
        fact(report, "target_up")["samples"][0]["last_observed_at"] = report["evidence"]["window_end"]
        self.assertEqual(assess(report)["code"], "verify")

    def test_old_report_rendering_keeps_historical_decision_and_marks_its_scope(self):
        result = assess(complete_report(), now=NOW + dt.timedelta(days=1))
        self.assertEqual(result["code"], "monitor")
        self.assertTrue(any("보관 보고" in row for row in result["limitations"]))

    def test_backup_age_and_host_age_are_independent_of_alert_resolution(self):
        for identifier, field, value in [("backup_success", "last", NOW.timestamp() - 93601),
                                         ("backup_success", "last", NOW.timestamp() + 3600)]:
            report = complete_report()
            fact(report, identifier)["samples"][0][field] = value
            self.assertEqual(assess(report)["code"], "verify")
        report = complete_report()
        fact(report, "host")["collected_at"] = (NOW - dt.timedelta(seconds=181)).isoformat()
        self.assertEqual(assess(report)["code"], "verify")

    def test_expired_critical_transition_and_optional_sources_do_not_imply_current_failure(self):
        report = complete_report()
        fact(report, "alerts")["records"] = [{"labels": {"alertname": "LinuxHostUnreachable", "severity": "critical"},
                                                "status": {"state": "active"}, "endsAt": (NOW - dt.timedelta(minutes=1)).isoformat()}]
        self.assertEqual(assess(report)["code"], "monitor")
        self.assertEqual(assess(complete_report())["code"], "monitor")

    def test_input_is_not_mutated_and_actions_are_recommendations(self):
        report = complete_report()
        before = copy.deepcopy(report)
        result = assess(report)
        self.assertEqual(report, before)
        self.assertIn("SLA", result["policy_note"])
        self.assertIn("보고 서비스는 조치를 실행하지 않음", result["policy_note"])
        self.assertNotIn("자동 조치 없음", result["policy_note"], "관제센터 자동 조치 수는 보고마다 센다")
        self.assertTrue(all(set(row) == {"priority", "owner", "timing", "task", "done_when", "evidence"} for row in result["actions"]))

    def test_http_errors_require_verification_even_without_a_firing_alert(self):
        report = complete_report()
        fact(report, "server_errors")["samples"][0]["last"] = 2
        result = assess(report)
        self.assertEqual(result["code"], "verify")
        self.assertIn("5xx", result["reason"])

    def test_invalid_metric_domains_cannot_clear_intervention(self):
        for identifier, value in [("target_up", .5), ("services_failed", -1), ("db_connections", -2),
                                  ("db_connections", 1.2), ("latency_p95", -1), ("request_rate", -1)]:
            with self.subTest(identifier=identifier, value=value):
                report = complete_report()
                fact(report, identifier)["samples"][0]["last"] = value
                self.assertEqual(assess(report)["code"], "verify")


if __name__ == "__main__":
    unittest.main()
