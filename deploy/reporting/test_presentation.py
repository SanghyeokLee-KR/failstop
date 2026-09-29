import copy
import datetime as dt
from html.parser import HTMLParser
import json
import unittest

from presentation import build_brief, card, render_html


def metric(identifier, value, labels=None, status="ok"):
    return {"id": identifier, "kind": "metric", "status": status,
            "samples": [] if value is None else [{"last": value, "min": value, "max": value,
                                                 "count": 4, "last_observed_at": "2026-09-18T01:20:00Z", "labels": labels or {}}]}


def event(name="DatabaseBackupUnknownOrStale", status="resolved", target="lab-db"):
    return {"labels": {"alertname": name, "instance": target}, "status": status,
            "startsAt": "2026-09-18T01:00:00Z", "endsAt": "2026-09-18T01:15:00Z" if status == "resolved" else "0001-01-01T00:00:00Z"}


def report(events=None):
    return {"id": "incident:example", "kind": "incident", "analysis": {"mode": "mock", "reason": "모의 응답",
            "summary": "원시 수집 상태", "observations": [], "hypotheses": [], "next_checks": []},
            "evidence": {"collected_at": "2026-09-18T01:20:00Z", "window_end": 1789694400, "window_seconds": 86400,
                         "facts": [metric("disk_available", 0.1496, {"mountpoint": "/mnt/c"}),
                                   metric("backup_success", dt.datetime(2026, 9, 18, 1, 10, tzinfo=dt.timezone.utc).timestamp()),
                                   metric("services_failed", 0), metric("server_errors", None, status="missing"),
                                   {"id": "trigger", "kind": "alert_trigger", "status": "ok", "records": events or [event()]},
                                   {"id": "alerts", "kind": "alerts", "status": "ok", "records": []}]}}


def midnight_report(status, summary, **analysis):
    """UTC 15시대에 울린 경보다. 한국 시간으로는 날짜가 하루 넘어간 0시대다(시연 13회차 보고와 같은 시각)."""
    alert = {"labels": {"alertname": "AppTierCpuHigh"}, "status": status, "startsAt": "2026-09-27T15:03:46.132Z",
             "endsAt": "2026-09-27T15:05:06.132Z" if status == "resolved" else "0001-01-01T00:00:00Z"}
    # 현재 경보 목록의 진행 중 경보는 Prometheus가 붙인 유효 기한(발생 4분 뒤)을 endsAt으로 갖는다.
    active = {**alert, "status": {"state": "active"}, "endsAt": "2026-09-27T15:07:46.132Z"}
    collected = dt.datetime(2026, 9, 27, 15, 6, 1, tzinfo=dt.timezone.utc)
    return {"id": "incident:midnight", "kind": "incident",
            "analysis": {"mode": "ollama", "model": "gpt-oss:20b", "summary": summary, "observations": [],
                         "hypotheses": [], "next_checks": [], **analysis},
            "evidence": {"collected_at": collected.isoformat(), "window_end": collected.timestamp(), "window_seconds": 900,
                         "facts": [{"id": "trigger", "kind": "alert_trigger", "status": "ok", "records": [alert]},
                                   {"id": "alerts", "kind": "alerts", "status": "ok",
                                    "records": [active] if status == "firing" else []}]}}


class PresentationTests(unittest.TestCase):
    def test_card_names_customer_and_environment_for_daily_or_incident(self):
        item = report()
        item["evidence"]["scope"] = {"customer": "exchange", "environment": "local-lab"}
        self.assertIn("exchange / 로컬 실습", card(item)["attachments"][0]["content"]["body"][0]["text"])
        item["kind"] = "daily"
        item["evidence"]["scope"]["environment"] = "aws"
        self.assertIn("exchange / AWS", card(item)["attachments"][0]["content"]["body"][0]["text"])

    def test_backup_resolved_is_not_reported_as_an_ongoing_incident(self):
        brief = build_brief(report())
        self.assertEqual(brief["status"]["code"], "resolved")
        self.assertIn("백업", brief["title"])
        self.assertIn("해제", brief["title"])
        self.assertNotIn("진행 중인 경보", brief["lead"])
        self.assertIn("복원", " ".join(brief["next_checks"]))
        self.assertTrue(any("예약 활성" in row for row in brief["intervention"]["closure_conditions"]))
        self.assertEqual(brief["intervention"]["code"], "scheduled")
        self.assertIn("10:15 KST 경보 해제", brief["lead"])
        self.assertIn("마지막 백업 성공 10:10 KST", brief["lead"])
        self.assertNotIn("해제 전환을 수신", brief["lead"])

    def test_low_disk_firing_has_value_target_and_safe_next_step(self):
        brief = build_brief(report([event("LinuxDiskSpaceLow", "firing")]))
        self.assertEqual(brief["status"]["code"], "firing")
        self.assertEqual(brief["title"], "디스크 여유 공간 부족 발생")
        disk = brief["metrics"][0]
        self.assertEqual(disk["status"], "attention")
        self.assertEqual(disk["value"], "14.96% 여유")
        self.assertIn("/mnt/c", disk["detail"])
        self.assertIn("보존 대상 확인 후", " ".join(brief["next_checks"]))

    def test_multiple_transitions_are_a_mixed_group(self):
        brief = build_brief(report([event(), event("LinuxDiskSpaceLow", "firing")]))
        self.assertEqual(brief["status"]["code"], "mixed")
        self.assertIn("2건 묶음", brief["title"])
        self.assertIn("진행 1건 / 해제 1건", brief["lead"])
        self.assertEqual([item["status"] for item in brief["events"]], ["resolved", "firing"])

    def test_collection_ok_is_not_equated_with_service_health(self):
        value = report([event("LinuxHostUnreachable", "firing")])
        value["evidence"]["facts"].append(metric("target_up", 0))
        brief = build_brief(value)
        target = next(item for item in brief["metrics"] if item["evidence"] == "target_up")
        self.assertEqual(target["value"], "0/1개 응답")
        self.assertEqual(target["status"], "attention")
        self.assertIn("업무 요청 성공 여부는 별도", target["detail"])
        self.assertIn("미확인", brief["impact"])

    def test_missing_and_error_are_not_zero(self):
        value = report()
        value["evidence"]["facts"].append(metric("request_rate", None, status="error"))
        brief = build_brief(value)
        metrics = {item["evidence"]: item for item in brief["metrics"]}
        self.assertEqual(metrics["server_errors"]["value"], "자료 없음")
        self.assertEqual(metrics["request_rate"]["value"], "수집 실패")
        self.assertTrue(all(item["status"] == "unknown" for key, item in metrics.items() if key in ("server_errors", "request_rate")))

    def test_real_zero_and_measurement_units_are_preserved(self):
        value = report()
        value["evidence"]["facts"] += [metric("db_replication_lag", 0), metric("latency_p95", 0.125), metric("request_rate", 2.5)]
        metrics = {item["evidence"]: item for item in build_brief(value)["metrics"]}
        self.assertEqual(metrics["db_replication_lag"]["value"], "0 B")
        self.assertEqual(metrics["latency_p95"]["value"], "125 ms")
        self.assertEqual(metrics["request_rate"]["value"], "2.50건/초")
        self.assertEqual(metrics["services_failed"]["value"], "0개 실패")

    def test_timeline_is_kst_and_missing_end_is_not_an_actual_end(self):
        brief = build_brief(report([event("LinuxDiskSpaceLow", "firing")]))
        self.assertEqual(brief["events"][0]["started_at"], "2026-09-18 10:00 KST")
        self.assertIsNone(brief["events"][0]["ended_at"])
        self.assertEqual(brief["collected_at"], "2026-09-18 10:20 KST")

    def test_ambient_firing_does_not_override_resolved_trigger(self):
        value = report()
        value["evidence"]["facts"][-1]["records"] = [event("LinuxDiskSpaceLow", "firing")]
        brief = build_brief(value)
        self.assertEqual(brief["status"]["code"], "resolved")
        self.assertIn("디스크 여유 공간 부족 1건", brief["current_state"])

    def test_daily_empty_alerts_are_not_a_full_health_claim(self):
        value = report()
        value["kind"] = "daily"
        value["evidence"]["facts"] = [metric("target_up", 1), {"id": "alerts", "status": "ok", "records": []}]
        brief = build_brief(value)
        self.assertEqual(brief["title"], "일일 운영 보고")
        self.assertNotEqual(brief["status"]["code"], "resolved")
        self.assertIn("수집 시점 기준", brief["lead"])
        self.assertEqual(brief["intervention"]["code"], "verify")

    def test_unknown_trigger_status_is_not_inferred_as_firing(self):
        value = report([event(status="unexpected")])
        brief = build_brief(value)
        self.assertEqual(brief["status"]["code"], "unknown")
        self.assertEqual(brief["events"][0]["status"], "unknown")

    def test_remote_ai_does_not_override_evidence_and_hypotheses_are_labeled(self):
        value = report()
        value["analysis"].update(mode="remote", summary="모든 서비스가 완전히 복구됐습니다.",
                                 hypotheses=[{"text": "예약 실행 지연 가능성", "evidence": ["backup_success"]}])
        brief = build_brief(value)
        self.assertNotIn("완전히", brief["lead"])
        self.assertIn("미확인 가설", brief["ai_notes"][0])

    def test_gemini_summary_and_grounded_checks_are_visible_as_reviewable_advice(self):
        value = report()
        value["analysis"].update(mode="gemini", summary="백업 알림은 해제됐지만 용량 위험은 남아 있습니다.",
                                 next_checks=[{"text": "백업 파일과 성공 시각을 대조합니다.", "evidence": ["backup_success"]},
                                              {"text": "근거 없는 조치", "evidence": ["invented"]}],
                                 hypotheses=[{"text": "수집 지연 가능성", "evidence": ["backup_success"]}])
        brief = build_brief(value)
        self.assertEqual(brief["status"]["code"], "resolved")
        self.assertIn("보존 대상 확인 후", brief["next_checks"][0])
        self.assertIn("백업 파일과 성공 시각을 대조합니다.", brief["ai_checks"])
        self.assertNotIn("근거 없는 조치", brief["next_checks"])
        self.assertIn("Gemini", brief["analysis_note"])
        self.assertIn(value["analysis"]["summary"], json.dumps(card(value), ensure_ascii=False))
        self.assertIn(value["analysis"]["summary"], render_html(value))

    def test_facts_only_discloses_ai_failure_without_raw_errors(self):
        value = report()
        value["analysis"].update(mode="facts-only", reason="AI 호출 한도 소진: http_error_429")
        brief = build_brief(value)
        self.assertIn("호출 한도", brief["analysis_note"])
        self.assertNotIn("http_error", brief["analysis_note"])
        value["analysis"]["reason"] = "AI 응답을 확보하지 못해 사실만 보고합니다."
        self.assertIn("AI 응답 미확보", build_brief(value)["analysis_note"])

    def test_mock_mode_has_readable_facts_without_raw_analysis_dump(self):
        payload = json.dumps(card(report()), ensure_ascii=False)
        self.assertIn("모의 분석", payload)
        self.assertIn("최근 백업 성공", payload)
        self.assertNotIn("원시 수집 상태", payload)
        self.assertNotIn('\\"samples\\"', payload)
        self.assertNotIn('\\"records\\"', payload)

    def test_card_has_no_actions_and_fits_delivery_limit(self):
        result = card(report())
        content = result["attachments"][0]["content"]
        self.assertEqual(content["type"], "AdaptiveCard")
        self.assertEqual(content["version"], "1.2")
        self.assertNotIn("actions", content)
        self.assertLess(len(json.dumps(result, ensure_ascii=False).encode()), 26000)

    def test_html_escapes_all_external_text_and_has_no_external_assets(self):
        malicious = '<script>alert("x")</script>'
        value = report([event("Unknown" + malicious, "firing", malicious)])
        value["id"] = malicious
        value["evidence"]["facts"][0]["samples"][0]["labels"]["mountpoint"] = malicious
        rendered = render_html(value)
        self.assertNotIn("<script>", rendered)
        self.assertIn("&lt;script&gt;", rendered)
        self.assertNotIn("<iframe", rendered)
        self.assertNotIn("<link", rendered)
        HTMLParser().feed(rendered)

    def test_html_has_responsive_and_a4_print_layout(self):
        rendered = render_html(report())
        self.assertIn("size:A4", rendered)
        self.assertIn("@media print", rendered)
        self.assertIn("max-width:680px", rendered)
        self.assertIn("서비스 영향", rendered)
        self.assertIn("우선 확인할 사항", rendered)
        self.assertEqual(rendered.count('class="page"'), 2)
        self.assertEqual(rendered.count('class="chart"'), 4)
        self.assertIn("권고 담당·착수", rendered)
        self.assertIn("완료 조건", rendered)
        self.assertIn("SLA나 작업 배정이 아님", rendered)
        self.assertIn("경보 발생 당시의 상태와 구분", rendered)

    def test_intervention_is_first_in_teams_and_cannot_be_downgraded_by_ai(self):
        value = report()
        value["analysis"].update(mode="gemini", summary="추가 조치 불필요", intervention={"code": "monitor"},
                                 next_checks=[{"text": "점검 중단", "evidence": ["disk_available"]}])
        brief = build_brief(value)
        self.assertEqual(brief["intervention"]["code"], "scheduled")
        self.assertIn("보존 대상", brief["next_checks"][0])
        blocks = card(value)["attachments"][0]["content"]["body"]
        self.assertIn("담당자 조치 필요", blocks[2]["text"])
        self.assertIn("14.96%", next(row for row in brief["metrics"] if row["evidence"] == "disk_available")["value"])
        encoded = json.dumps(blocks, ensure_ascii=False)
        self.assertIn("금일 점검 권고", encoded)
        self.assertIn("완료:", encoded)
        self.assertLess(encoded.index("담당자 조치 필요"), encoded.index("추가 조치 불필요"))

    def test_standby_connection_failure_names_target_and_does_not_claim_ec2_death(self):
        incident = event("DatabaseConnectionFailed", "firing", "db-standby:9187")
        incident["labels"].update(role="standby", node="db-standby-1", customer="exchange", environment="aws")
        value = report([incident])
        brief = build_brief(value)
        self.assertEqual(brief["title"], "Standby DB 접속 장애 발생")
        content = card(value)["attachments"][0]["content"]
        encoded = json.dumps(content, ensure_ascii=False)
        self.assertIn("\U0001f534 Standby DB 접속 장애 발생", encoded)
        self.assertIn("db-standby-1", encoded)
        self.assertIn("DB 운영 담당", encoded)
        self.assertNotIn("DatabaseConnectionFailed", encoded)
        self.assertNotIn("서버 중단", encoded)
        self.assertNotIn("자료 없음;", encoded)
        self.assertIn("Primary 요청 영향", brief["impact"])

    def test_exporter_failure_never_masquerades_as_database_outage(self):
        incident = event("DatabaseExporterUnavailable", "firing")
        incident["labels"]["role"] = "standby"
        brief = build_brief(report([incident]))
        self.assertEqual(brief["title"], "Standby DB 수집 경로 장애 발생")
        self.assertIn("DB 직접 접속", brief["impact"])
        self.assertNotIn("접속 장애 발생", brief["title"])

    def test_resolved_database_card_has_both_times_and_followup(self):
        incident = event("DatabaseConnectionFailed", "resolved")
        incident["labels"]["role"] = "standby"
        value = report([incident])
        value["evidence"]["facts"] = [row for row in value["evidence"]["facts"] if row["id"] != "disk_available"]
        content = card(value)["attachments"][0]["content"]
        encoded = json.dumps(content, ensure_ascii=False)
        self.assertIn("\U0001f7e2 Standby DB 접속 장애 해제", encoded)
        self.assertIn("10:00 KST", encoded)
        self.assertIn("10:15 KST", encoded)
        self.assertIn("복구 직후 확인", encoded)
        self.assertNotIn("추가 개입 조건 없음", encoded)

    def test_current_collection_time_exposes_stale_daily_window(self):
        value = report()
        value["kind"] = "daily"
        value["evidence"]["collected_at"] = "2026-09-18T06:20:00Z"
        brief = build_brief(value)
        disk = next(row for row in brief["metrics"] if row["evidence"] == "disk_available")
        self.assertEqual(disk["status"], "unknown")
        self.assertIn("과거 표본", disk["value"])
        self.assertEqual(brief["intervention"]["code"], "verify")

    def test_input_report_and_analysis_are_unchanged(self):
        original = report()
        expected = copy.deepcopy(original)
        build_brief(original)
        card(original)
        render_html(original)
        self.assertEqual(original, expected)

    def test_invalid_numeric_values_are_not_healthy_measurements(self):
        value = report()
        value["evidence"]["facts"] = [metric("disk_available", float("nan")), metric("backup_success", 1e300)]
        metrics = build_brief(value)["metrics"]
        self.assertEqual(metrics[0]["status"], "unknown")
        self.assertEqual(metrics[1]["value"], "성공 시각 미확인")

    def test_old_range_sample_is_labeled_as_stale(self):
        value = report()
        disk = value["evidence"]["facts"][0]
        disk["samples"][0]["last_observed_at"] = 1789690800
        disk["source"] = {"window_end": 1789694400, "step_seconds": 60}
        item = next(item for item in build_brief(value)["metrics"] if item["evidence"] == "disk_available")
        self.assertEqual(item["status"], "unknown")
        self.assertIn("오래된 표본", item["detail"])
        self.assertIn("KST", item["as_of"])

    def test_db_connections_show_both_instances_and_database_scope(self):
        value = report()
        fact = metric("db_connections", 9, {"role": "primary", "datname": "exchange"})
        fact["samples"].append({"last": 2, "labels": {"role": "standby", "datname": "exchange"}})
        value["evidence"]["facts"].append(fact)
        item = next(item for item in build_brief(value)["metrics"] if item["evidence"] == "db_connections")
        self.assertEqual(item["value"], "11개")
        self.assertIn("primary / exchange 9개", item["detail"])
        self.assertIn("standby / exchange 2개", item["detail"])

    def test_legacy_duplicate_db_labels_are_not_summed_as_new_connections(self):
        value = report()
        fact = metric("db_connections", 0, {"instance": "primary"})
        fact["samples"].append({"last": 9, "labels": {"instance": "primary"}})
        value["evidence"]["facts"].append(fact)
        item = next(item for item in build_brief(value)["metrics"] if item["evidence"] == "db_connections")
        self.assertEqual(item["status"], "unknown")
        self.assertEqual(item["value"], "대상 구분 불충분")

    def test_impossible_inode_ratio_is_not_presented_as_healthy(self):
        value = report()
        value["evidence"]["facts"].append(metric("inode_free", 1001.0))
        item = next(item for item in build_brief(value)["metrics"] if item["evidence"] == "inode_free")
        self.assertEqual(item["status"], "unknown")
        self.assertEqual(item["value"], "일부 값 해석 불가")

    def test_bad_historical_ratio_is_not_printed_as_a_valid_minimum(self):
        for identifier in ("disk_available", "inode_free"):
            value = report()
            fact = metric(identifier, .5)
            fact["samples"][0].update(min=-10, max=.5, points=[[1789694340, -10], [1789694400, .5]])
            value["evidence"]["facts"] = [fact]
            item = build_brief(value)["metrics"][0]
            self.assertEqual(item["status"], "unknown")
            self.assertEqual(item["value"], "50.00% 여유")
            self.assertNotIn("-1000", item["detail"])
            self.assertIn("구간 통계 해석 불가", item["detail"])

    def test_ai_utc_clock_labeled_kst_is_rewritten_to_the_next_kst_day(self):
        value = midnight_report("resolved", "앱 계층 CPU 높음 경보가 2026-09-27 15:03:46(KST)에서 15:05:06(KST)까지 "
                                            "발생했으며, 15:05:06(KST)에 해제되었습니다.")
        expected = "앱 계층 CPU 높음 경보가 2026-09-28 00:03 KST에서 00:05 KST까지 발생했으며, 00:05 KST에 해제되었습니다."
        self.assertEqual(build_brief(value)["analysis_summary"], expected)
        for rendered in (json.dumps(card(value), ensure_ascii=False), render_html(value)):
            self.assertIn(expected, rendered)
            self.assertNotIn("15:03", rendered)
            self.assertNotIn("15:05", rendered)

    def test_ai_kst_clock_with_utc_date_gets_the_kst_date(self):
        value = midnight_report("firing", "앱 계층 CPU 높음 경보가 2026-09-27 00:03:46(KST)에 발생해 활성 상태입니다.")
        self.assertEqual(build_brief(value)["analysis_summary"],
                         "앱 계층 CPU 높음 경보가 2026-09-28 00:03 KST에 발생해 활성 상태입니다.")

    def test_ai_sentence_with_a_time_the_report_does_not_show_is_dropped(self):
        # 모델이 현재 경보 목록의 유효 기한을 해제 시각처럼 옮겨 쓴 시연 13회차 발생 보고의 문장이다.
        value = midnight_report("firing", "앱 계층 CPU 높음 경보가 2026-09-27 00:03:46(KST) 발생하여 2026-09-27 00:07:46(KST)까지 "
                                          "활성 상태를 유지하고 있습니다. 요청률과 지연을 함께 확인해야 합니다.",
                                hypotheses=[{"text": "00:07 KST에 배포가 있었을 가능성", "evidence": ["trigger"]},
                                            {"text": "트래픽 증가 가능성", "evidence": ["trigger"]}],
                                next_checks=[{"text": "15:03 UTC 전후 로그를 확인합니다.", "evidence": ["trigger"]},
                                             {"text": "23:10 KST 백업 작업을 확인합니다.", "evidence": ["trigger"]}])
        brief = build_brief(value)
        self.assertEqual(brief["analysis_summary"], "요청률과 지연을 함께 확인해야 합니다.")
        self.assertEqual(brief["ai_notes"], ["미확인 가설: 트래픽 증가 가능성"])
        self.assertEqual(brief["ai_checks"], ["00:03 KST 전후 로그를 확인합니다."])
        rendered = render_html(value) + json.dumps(card(value), ensure_ascii=False)
        self.assertNotIn("00:07", rendered)
        self.assertNotIn("2026-09-27 00:03", rendered)

    def test_ai_offset_time_and_korean_clock_use_the_report_time_format(self):
        value = midnight_report("resolved", "2026-09-28T00:03:46+09:00에 발생한 경보가 오전 12시 5분에 해제됐습니다.")
        self.assertEqual(build_brief(value)["analysis_summary"],
                         "2026-09-28 00:03 KST에 발생한 경보가 00:05 KST에 해제됐습니다.")

    def test_ai_time_on_the_kst_day_before_the_report_keeps_that_date(self):
        value = midnight_report("resolved", "경보가 14:55(KST)에 발생했습니다.")
        value["evidence"]["facts"][0]["records"][0]["startsAt"] = "2026-09-27T14:55:00Z"
        self.assertEqual(build_brief(value)["analysis_summary"], "경보가 2026-09-27 23:55 KST에 발생했습니다.")


if __name__ == "__main__":
    unittest.main()
