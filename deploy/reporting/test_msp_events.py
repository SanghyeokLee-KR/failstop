import json
import re
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import msp_events
from console_api import delivery_status, list_reports
from reporter import ReportServer, Store, drain_one, load_config
from transport import RequestFailure, json_request, request

EVENT_ID = "6f1c2a9e-0b1d-4c77-9d7a-2b0c3f4e5a61"


def auto_executed(**changes):
    """관리 JVM(TeamsNotifier)이 자동 실행 뒤 넘기는 본문과 같은 모양이다."""
    body = {"schema_version": 1, "event_id": EVENT_ID, "kind": "AUTO_EXECUTED",
            "occurred_at": "2026-09-25T00:00:00.000Z", "scope": {"customer": "exchange", "environment": "aws"},
            "action": {"id": 77, "kind": "READ_NODES", "verb": "READ_NODE_ADD_ONE", "target": "app", "from": "2",
                       "to": "3", "status": "AUTO_EXECUTED", "confidence": "0.86",
                       "observe_until": "2026-09-25T00:05:00.000Z", "observe_outcome": None},
            "mode": None, "model": "gpt-oss:20b",
            "metrics": [{"name": "read_avg_cpu", "value": "0.82", "threshold": "0.6"}],
            "unconfirmed": 1, "counts": {}}
    body.update(changes)
    return body


def stopped():
    return auto_executed(kind="STOPPED", action=None, model=None, metrics=[], unconfirmed=0,
                         mode={"mode": "MANUAL", "cause": "stopped", "actor": "op-02", "valid_until": None},
                         counts={"expired_actions": 2})


class MspEventTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.store = Store(self.root / "outbox.sqlite3")
        self.token = "m" * 40

    def test_accepts_only_the_server_made_shape(self):
        key, payload = msp_events.event_payload(auto_executed())
        self.assertEqual(key, "msp:" + EVENT_ID)
        self.assertEqual(payload["scope"], {"customer": "exchange", "environment": "aws"})
        self.assertEqual(payload["event"]["action"]["verb"], "READ_NODE_ADD_ONE")
        rejected = [auto_executed(reason_ko="이유와 상황 문장은 받지 않습니다"), auto_executed(kind="DB_PROMOTE"),
                    auto_executed(occurred_at="2026-09-25T00:00:00"), auto_executed(schema_version=2),
                    auto_executed(event_id="not-a-uuid"), auto_executed(model="[x](https://evil.test)"),
                    auto_executed(scope={"customer": "exchange", "environment": "aws x"}),
                    auto_executed(metrics=[{"name": "read_avg_cpu", "value": "0.8", "threshold": None}] * 7),
                    auto_executed(unconfirmed=-1), auto_executed(counts={"other": 1})]
        for key_name, value in (("verb", "DB_PROMOTE"), ("target", "x" * 65), ("target", "a b"), ("from", "2; rm"),
                                ("status", "DONE"), ("extra", 1)):
            body = auto_executed()
            body["action"] = dict(body["action"], **{key_name: value})
            rejected.append(body)
        for body in rejected:
            with self.subTest(body=json.dumps(body, ensure_ascii=False)[:120]), self.assertRaises(ValueError):
                msp_events.event_payload(body)

    def test_card_uses_report_language_and_records_the_automatic_action(self):
        key, payload = msp_events.event_payload(auto_executed())
        card = msp_events.card(msp_events.report(key, payload), {})
        encoded = json.dumps(card, ensure_ascii=False)
        body = card["attachments"][0]["content"]["body"]
        texts = [block.get("text", "") for block in body]
        self.assertEqual(texts[0], "페일스톱 운영 관제 · exchange / AWS")
        self.assertIn("AI 자동 조치 실행 · 조회 노드 1대 증설", texts[1])
        self.assertEqual(texts[2], "P3 · 담당자 확인 필요")
        self.assertEqual(body[3]["facts"], [
            {"title": "대상", "value": "조회 계층"}, {"title": "발생", "value": "2026-09-25 09:00 KST"},
            {"title": "영향", "value": "조회 노드 1대 증설\\(2→3\\) · 관찰 창 09:05 KST까지 · 악화하면 되돌리거나 수동 전환"}])
        self.assertFalse(any("|" in text for text in texts), "영향은 세로줄 구분자 없이 이름-값 줄로 싣는다")
        self.assertEqual(texts[4], "담당자 조치")
        self.assertIn("후속 관측 · 2026-09-25 09:00 KST", texts)
        self.assertIn({"title": "조회 평균 CPU", "value": "82.0% \\(임계 60.0%\\)"},
                      [fact for block in body if block["type"] == "FactSet" for fact in block["facts"]])
        self.assertIn("참고 분석 · AI", texts)
        self.assertIn("AI 판단 · 모델 gpt-oss:20b · 확신도 86%", texts)
        self.assertTrue(any(text.startswith("미확인 자료 1항목") for text in texts))
        self.assertEqual(texts[-1], "관제 사건 6f1c2a9e · 자동 실행 1건")
        self.assertNotIn("actions", card["attachments"][0]["content"], "관제센터 주소를 설정하지 않았다")
        self.assertLess(len(encoded.encode()), 26000)

    def test_urgency_follows_the_event_kind_and_mode_events_have_no_automatic_action(self):
        key, payload = msp_events.event_payload(stopped())
        brief = msp_events.brief(msp_events.report(key, payload))
        self.assertEqual((brief["title"], brief["priority"]), ("AI 정지", "P3"))
        self.assertIn("대기 조치안 2건 만료", brief["impact"])
        self.assertFalse(brief["automatic"])
        self.assertEqual(brief["automation"], "자동 조치 없음")
        unknown = auto_executed(kind="EXECUTION_UNKNOWN")
        unknown["action"]["status"] = "UNKNOWN"
        self.assertEqual(msp_events.brief(msp_events.report(*msp_events.event_payload(unknown)))["priority"], "P1")
        manual = auto_executed(kind="MODE_CHANGED", action=None, model=None, metrics=[],
                               mode={"mode": "MANUAL", "cause": "operator", "actor": "op-02", "valid_until": None})
        self.assertEqual(msp_events.brief(msp_events.report(*msp_events.event_payload(manual)))["priority"], "P4")
        degraded = dict(manual, mode=dict(manual["mode"], cause="degraded", actor="system:msp"))
        self.assertEqual(msp_events.brief(msp_events.report(*msp_events.event_payload(degraded)))["title"],
                         "AI 수동 전환 · 관찰 창 악화")

    def test_console_button_comes_only_from_the_reporting_config(self):
        key, payload = msp_events.event_payload(auto_executed())
        card = msp_events.card(msp_events.report(key, payload), {"msp_console_url": "https://ops.example.test/admin"})
        self.assertEqual(card["attachments"][0]["content"]["actions"],
                         [{"type": "Action.OpenUrl", "title": "관제센터에서 확인", "url": "https://ops.example.test/admin"}])
        for bad in ("http://ops.example.test", "https://user:pw@ops.example.test", "https://ops.example.test/?t=1",
                    "javascript:alert(1)"):
            with self.subTest(url=bad), self.assertRaises(ValueError):
                msp_events.console_url({"msp_console_url": bad})
        config = json.loads(Path(__file__).with_name("config.example.json").read_text(encoding="utf-8"))
        path = self.root / "config.json"
        config["msp_console_url"] = "https://ops.example.test/admin"
        path.write_text(json.dumps(config), encoding="utf-8")
        self.assertEqual(load_config(path)["msp_console_url"], "https://ops.example.test/admin")
        config["msp_console_url"] = "http://ops.example.test"
        path.write_text(json.dumps(config), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_config(path)

    def test_server_accepts_events_once_and_worker_sends_the_card(self):
        server = ReportServer(("127.0.0.1", 0), self.store, self.token, {})
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        headers = {"Authorization": "Bearer " + self.token}
        try:
            with self.assertRaises(RequestFailure) as unauthorized:
                request(base + "/msp/events", payload=auto_executed())
            self.assertEqual(unauthorized.exception.status, 401)
            first = json_request(base + "/msp/events", payload=auto_executed(), headers=headers)
            self.assertEqual((first["id"], first["duplicate"]), ("msp:" + EVENT_ID, False))
            self.assertTrue(json_request(base + "/msp/events", payload=auto_executed(), headers=headers)["duplicate"],
                            "같은 사건 id 는 한 번만 받는다")
            with self.assertRaises(RequestFailure) as invalid:
                request(base + "/msp/events", payload=auto_executed(kind="DB_PROMOTE"), headers=headers)
            self.assertEqual(invalid.exception.status, 400)
            with self.assertRaises(RequestFailure) as private:
                request(base + "/console/status")
            self.assertEqual(private.exception.status, 401)
            status = json_request(base + "/console/status", headers=headers)
            self.assertEqual(status["counts"]["pending"], 1)
            self.assertIsNone(status["last_sent_at"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        sent = []
        with patch("reporter.collect") as collect, patch("reporter.analyze") as analyze:
            self.assertTrue(drain_one(self.store, {}, sent.append))
        collect.assert_not_called()
        analyze.assert_not_called()
        self.assertEqual(len(sent), 1)
        self.assertIn("AI 자동 조치 실행", json.dumps(sent[0], ensure_ascii=False))
        with self.store.connect() as db:
            row = db.execute("SELECT status,report FROM jobs WHERE id=?", ("msp:" + EVENT_ID,)).fetchone()
        self.assertEqual(row["status"], "sent")
        self.assertEqual(json.loads(row["report"])["kind"], "msp")
        status = delivery_status(self.store, True)
        self.assertEqual(status["counts"]["sent"], 1)
        self.assertEqual(status["last_attempt"]["status"], "sent")
        self.assertIsNotNone(status["last_sent_at"])
        self.assertTrue(status["delivery_enabled"])

    def test_console_list_shows_events_within_the_customer_scope(self):
        key, payload = msp_events.event_payload(auto_executed())
        self.store.enqueue(key, "msp", payload)
        other_key, other_payload = msp_events.event_payload(auto_executed(
            event_id="7f1c2a9e-0b1d-4c77-9d7a-2b0c3f4e5a62", scope={"customer": "exchange", "environment": "local-lab"}))
        self.store.enqueue(other_key, "msp", other_payload)
        result = list_reports(self.store, {}, "/console/reports?customer=exchange&environment=aws")
        self.assertEqual([row["id"] for row in result["reports"]], ["msp:" + EVENT_ID])
        entry = result["reports"][0]
        self.assertEqual((entry["kind"], entry["severity"], entry["alertState"]), ("msp", "P3", None))
        self.assertEqual(entry["title"], "AI 자동 조치 실행 · 조회 노드 1대 증설")
        self.assertEqual(entry["targets"], ["조회 계층"])
        self.assertEqual(entry["links"], {"pdf": None, "html": None})

    def test_installer_copies_every_local_module_the_service_imports(self):
        # 설치기는 모듈을 이름 목록으로 복사한다. 목록에서 빠진 모듈은 설치된 서비스의 import 에서야 드러난다.
        here = Path(__file__).parent
        local = {path.stem for path in here.glob("*.py") if not path.name.startswith("test_")}
        installer = (here / "install.py").read_text(encoding="utf-8")
        listed = set(re.findall(r'"([a-z_]+)\.py"', installer.split("for name in (", 1)[1].split(")", 1)[0]))
        pending, seen = ["reporter"], set()
        while pending:
            module = pending.pop()
            if module not in seen:
                seen.add(module)
                source = (here / (module + ".py")).read_text(encoding="utf-8")
                pending += [name for name in re.findall(r"^\s*(?:from|import)\s+([a-z_]+)", source, re.M) if name in local]
        self.assertIn("msp_events", seen)
        self.assertEqual(sorted(seen - listed), [])


if __name__ == "__main__":
    unittest.main()
