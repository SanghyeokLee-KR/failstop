"""보고 카드의 기록 줄(보고 이름 · 자동 조치 수)과 영향 줄 표기."""
import json
import sqlite3
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import msp_events
from presentation import build_brief, card, render_html, report_label
from render_pdf import build_bindings
from reporter import Store, automation_summary, drain_one
from test_msp_events import auto_executed
from test_presentation import report

# 2026-09-24 09:00 KST 에 끝나는 24시간(daily_key 와 같은 창).
WINDOW_END = 1790208000.0
DAY = 86400


def daily_facts(scope=None):
    facts = report()["evidence"]
    facts["facts"] = [row for row in facts["facts"] if row["id"] != "trigger"]
    facts.update(window_end=WINDOW_END, window_seconds=DAY,
                 scope=scope or {"customer": "exchange", "environment": "aws"})
    return facts


def event(kind, occurred_at, environment="aws"):
    body = auto_executed(event_id=str(uuid.uuid4()), kind=kind, occurred_at=occurred_at,
                         scope={"customer": "exchange", "environment": environment})
    if kind in ("AUTO_REVERTED", "EXECUTION_UNKNOWN"):
        body["action"]["status"] = {"AUTO_REVERTED": "AUTO_REVERTED", "EXECUTION_UNKNOWN": "UNKNOWN"}[kind]
    if kind == "STOPPED":
        body.update(action=None, model=None, metrics=[], unconfirmed=0, counts={"expired_actions": 0},
                    mode={"mode": "MANUAL", "cause": "stopped", "actor": "op-02", "valid_until": None})
    return msp_events.event_payload(body)


def last_line(message):
    return message["attachments"][0]["content"]["body"][-1]["text"]


class RecordLineTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.store = Store(Path(temporary.name) / "outbox.sqlite3")

    def daily(self, automation):
        value = report()
        value["id"], value["kind"] = "daily:2026-09-24", "daily"
        value["evidence"] = daily_facts()
        if automation is not None:
            value["automation"] = automation
        return value

    def test_report_label_keeps_the_whole_date_and_names_the_kind(self):
        # 예전 카드는 id 의 뒤 12자를 잘라 'daily:2026-09-24'를 'y:2026-09-24'로 보였다.
        self.assertEqual(report_label("daily:2026-09-24"), "일일 보고 2026-09-24")
        self.assertEqual(report_label("incident:" + "ab" * 32), "경보 보고 abababababab")
        self.assertEqual(report_label("msp:6f1c2a9e-0b1d-4c77-9d7a-2b0c3f4e5a61"), "관제 사건 6f1c2a9e")
        self.assertEqual(report_label("preview:0123456789abcdef"), "미리보기 0123456789ab")
        self.assertEqual(report_label("test:0123456789abcdef"), "연결 시험 0123456789ab")
        self.assertEqual(report_label("incident:example"), "경보 보고 example")
        self.assertEqual(report_label("custom-id"), "custom-id")
        self.assertEqual(report_label(None), "보고 ID 미제공")

    def test_daily_card_html_and_pdf_share_the_record_line(self):
        value = self.daily({"state": "ok", "executed": 0, "reverted": 0, "unknown": 0})
        self.assertEqual(last_line(card(value)), "일일 보고 2026-09-24 · 자동 조치 없음")
        rendered = render_html(value)
        self.assertEqual(rendered.count("<footer><span>일일 보고 2026-09-24 · 자동 조치 없음 · 보고 ID daily:2026-09-24</span>"), 2)
        self.assertIn("관제센터 자동 조치: 자동 조치 없음 · 보고 구간에 보고 서비스가 받은 관제센터 사건 기준", rendered)
        self.assertNotIn("y:2026-09-24 ·", rendered)
        bindings = build_bindings(value)
        self.assertEqual(bindings["document"]["record_line"], "일일 보고 2026-09-24 · 자동 조치 없음")
        self.assertEqual(bindings["document"]["id"], "daily:2026-09-24")
        self.assertEqual(bindings["handover"]["executed_actions"], "자동 조치 없음")

    def test_counts_and_unreadable_records_have_distinct_wording(self):
        cases = [({"state": "ok", "executed": 2, "reverted": 1, "unknown": 0}, "자동 실행 2건 · 자동 되돌림 1건"),
                 ({"state": "ok", "executed": 0, "reverted": 0, "unknown": 1},
                  "자동 실행 0건 · 자동 되돌림 0건 · 조치 결과 확인 필요 1건"),
                 ({"state": "unavailable"}, "자동 조치 기록 확인 불가"),
                 (None, "자동 조치 기록 확인 불가"),
                 ({"state": "ok", "executed": "2", "reverted": 0, "unknown": 0}, "자동 조치 기록 확인 불가"),
                 ({"state": "ok", "executed": -1, "reverted": 0, "unknown": 0}, "자동 조치 기록 확인 불가"),
                 ({"state": "ok", "executed": 1, "reverted": 0}, "자동 조치 기록 확인 불가")]
        for automation, expected in cases:
            with self.subTest(automation=automation):
                value = self.daily(automation)
                self.assertEqual(last_line(card(value)), "일일 보고 2026-09-24 · " + expected)
                self.assertEqual(build_bindings(value)["handover"]["executed_actions"], expected)
                if expected.endswith("확인 불가"):
                    self.assertIn("관제센터 사건 기록을 읽지 못함", render_html(value))

    def test_impact_is_a_fact_row_not_a_pipe_separated_text(self):
        for value in (self.daily(None), report()):
            body = card(value)["attachments"][0]["content"]["body"]
            facts = [fact for block in body if block["type"] == "FactSet" for fact in block["facts"]]
            self.assertIn({"title": "영향", "value": "고객 영향 미확인 · 요청 실패 범위·영향 사용자 수 자료 없음"}, facts)
            self.assertFalse(any("|" in block.get("text", "") for block in body))
            self.assertEqual(body[4]["text"], "담당자 조치")

    def test_store_counts_automatic_events_inside_the_window_and_scope(self):
        for kind, moment, environment in (
                ("AUTO_EXECUTED", "2026-09-23T00:00:00Z", "aws"),      # 창 시작 시각 포함
                ("AUTO_EXECUTED", "2026-09-23T15:30:00.5+09:00", "aws"),
                ("AUTO_REVERTED", "2026-09-23T23:59:59Z", "aws"),
                ("EXECUTION_UNKNOWN", "2026-09-23T12:00:00Z", "aws"),
                ("STOPPED", "2026-09-23T12:00:00Z", "aws"),             # 조치가 아닌 사건
                ("AUTO_EXECUTED", "2026-09-24T00:00:00Z", "aws"),      # 창 끝 시각은 다음 보고
                ("AUTO_EXECUTED", "2026-09-22T23:59:59Z", "aws"),      # 전날 보고
                ("AUTO_EXECUTED", "2026-09-23T12:00:00Z", "local-lab")):  # 다른 환경
            key, payload = event(kind, moment, environment)
            self.store.enqueue(key, "msp", payload)
        self.assertEqual(automation_summary(self.store, daily_facts()),
                         {"state": "ok", "executed": 2, "reverted": 1, "unknown": 1})
        self.assertEqual(automation_summary(self.store, daily_facts({"customer": "exchange", "environment": "local-lab"})),
                         {"state": "ok", "executed": 1, "reverted": 0, "unknown": 0})
        self.assertEqual(automation_summary(self.store, dict(daily_facts(), window_seconds=None)), {"state": "unavailable"})
        self.assertEqual(automation_summary(self.store, dict(daily_facts(), window_end=float("nan"))), {"state": "unavailable"})
        self.assertEqual(automation_summary(None, daily_facts()), {"state": "unavailable"})

    def test_daily_job_saves_the_count_and_sends_it(self):
        key, payload = event("AUTO_EXECUTED", "2026-09-23T03:00:00Z")
        self.store.enqueue(key, "msp", payload)
        self.store.enqueue("daily:2026-09-24", "daily", {"window_end": WINDOW_END})
        sent = []
        with patch("reporter.collect", return_value=daily_facts()):
            self.assertTrue(drain_one(self.store, {"analyzer": "mock"}, sent.append, "daily:2026-09-24"))
        self.assertEqual(last_line(sent[0]), "일일 보고 2026-09-24 · 자동 실행 1건 · 자동 되돌림 0건")
        with self.store.connect() as db:
            saved = json.loads(db.execute("SELECT report FROM jobs WHERE id='daily:2026-09-24'").fetchone()[0])
        self.assertEqual(saved["automation"], {"state": "ok", "executed": 1, "reverted": 0, "unknown": 0})
        self.assertEqual(build_brief(saved)["record_line"], "일일 보고 2026-09-24 · 자동 실행 1건 · 자동 되돌림 0건")

    def test_unreadable_event_records_are_not_reported_as_none(self):
        key, payload = event("AUTO_EXECUTED", "2026-09-23T03:00:00Z")
        self.store.enqueue(key, "msp", payload)
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET payload='{\"event\":' WHERE id=?", (key,))
        self.assertEqual(automation_summary(self.store, daily_facts()), {"state": "unavailable"})
        with self.store.connect() as db:
            db.execute("DELETE FROM jobs WHERE id=?", (key,))
        with patch.object(Store, "automation", side_effect=sqlite3.OperationalError("disk I/O error")):
            self.assertEqual(automation_summary(self.store, daily_facts()), {"state": "unavailable"})
            self.store.enqueue("daily:2026-09-24", "daily", {"window_end": WINDOW_END})
            sent = []
            with patch("reporter.collect", return_value=daily_facts()):
                drain_one(self.store, {"analyzer": "mock"}, sent.append, "daily:2026-09-24")
        self.assertEqual(last_line(sent[0]), "일일 보고 2026-09-24 · 자동 조치 기록 확인 불가")

    def test_event_card_states_what_the_event_itself_did(self):
        expected = {"AUTO_EXECUTED": "자동 실행 1건", "AUTO_REVERTED": "자동 되돌림 1건",
                    "EXECUTION_UNKNOWN": "조치 결과 확인 필요", "STOPPED": "자동 조치 없음"}
        for kind, text in expected.items():
            with self.subTest(kind=kind):
                key, payload = event(kind, "2026-09-25T00:00:00Z")
                line = last_line(msp_events.card(msp_events.report(key, payload), {}))
                self.assertEqual(line, report_label(key) + " · " + text)
                self.assertTrue(line.startswith("관제 사건 " + key[4:12]))


if __name__ == "__main__":
    unittest.main()
