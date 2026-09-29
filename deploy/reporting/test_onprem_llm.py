import json
import socket
import unittest
from unittest.mock import patch

import onprem_llm
import reporter
from presentation import card
from transport import RequestFailure


def facts():
    return {"schema_version": 1, "collected_at": "2026-09-27T10:00:00Z", "window_seconds": 900,
            "facts": [{"id": "target_up", "kind": "metric", "status": "ok", "samples": [
                {"labels": {"job": "exchange-api", "instance": "app-1"}, "last": 1, "min": 1, "max": 1, "count": 15}]},
                {"id": "trigger", "kind": "alert_trigger", "status": "ok",
                 "records": {"status": "firing", "alerts": [{"status": "firing", "labels": {"alertname": "ReadTierCpuHigh"},
                                                              "startsAt": "2026-09-27T09:58:00Z"}]}}]}


def analysis():
    return {"summary": "조회 계층 CPU 경보가 활성 상태입니다.",
            "observations": [{"text": "app-1 수집 응답은 유지되었습니다.", "evidence": ["target_up", "trigger"]}],
            "hypotheses": [], "next_checks": []}


def ollama_response(content=None, **extra):
    body = {"model": "qwen3.6:35b-a3b", "done": True, "done_reason": "stop",
            "message": {"role": "assistant", "content": json.dumps(content or analysis(), ensure_ascii=False)},
            "prompt_eval_count": 1500, "eval_count": 180, "total_duration": 4_200_000_000}
    body.update(extra)
    return body


def resolve(address):
    return lambda host, port: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 0))]


class OnpremEndpointTest(unittest.TestCase):
    def test_loopback_http_is_accepted(self):
        with patch("onprem_llm.socket.getaddrinfo", resolve("127.0.0.1")):
            self.assertEqual(onprem_llm.chat_url({"ai_base_url": "http://127.0.0.1:11434"}), "http://127.0.0.1:11434/api/chat")
            self.assertEqual(onprem_llm.chat_url({}), "http://127.0.0.1:11434/api/chat")

    def test_public_address_is_refused_even_over_https(self):
        with patch("onprem_llm.socket.getaddrinfo", resolve("8.8.8.8")):
            with self.assertRaises(RequestFailure) as caught:
                onprem_llm.chat_url({"ai_base_url": "https://llm.example.test"})
        self.assertEqual(caught.exception.code, "ollama_remote_address")

    def test_private_plaintext_needs_explicit_opt_in(self):
        with patch("onprem_llm.socket.getaddrinfo", resolve("192.168.0.20")):
            with self.assertRaises(RequestFailure) as caught:
                onprem_llm.chat_url({"ai_base_url": "http://dgx-spark.lab:11434"})
            self.assertEqual(caught.exception.code, "ollama_plaintext_not_allowed")
            self.assertEqual(onprem_llm.chat_url({"ai_base_url": "http://dgx-spark.lab:11434", "ai_allow_insecure_http": True}),
                             "http://dgx-spark.lab:11434/api/chat")
            self.assertEqual(onprem_llm.chat_url({"ai_base_url": "https://dgx-spark.lab"}), "https://dgx-spark.lab/api/chat")

    def test_paths_queries_and_credentials_in_the_base_url_are_refused(self):
        with patch("onprem_llm.socket.getaddrinfo", resolve("127.0.0.1")):
            for base in ("http://127.0.0.1:11434/v1", "http://127.0.0.1:11434?x=1", "http://user:pw@127.0.0.1:11434",
                         "ftp://127.0.0.1", "", None):
                with self.subTest(base=base), self.assertRaises(RequestFailure):
                    onprem_llm.chat_url({"ai_base_url": base})


class OnpremAnalyzeTest(unittest.TestCase):
    def setUp(self):
        patcher = patch("onprem_llm.socket.getaddrinfo", resolve("127.0.0.1"))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_request_uses_shared_instruction_schema_and_no_thinking(self):
        with patch("onprem_llm.json_request", return_value=ollama_response()) as call:
            result, usage = onprem_llm.analyze(facts(), {"ai_model": "qwen3.6:35b-a3b"})
        url, kwargs = call.call_args[0][0], call.call_args[1]
        payload = kwargs["payload"]
        self.assertEqual(url, "http://127.0.0.1:11434/api/chat")
        self.assertEqual(payload["model"], "qwen3.6:35b-a3b")
        self.assertIs(payload["stream"], False)
        self.assertIs(payload["think"], False)
        text = json.dumps(payload["format"])
        for limit in ("maxLength", "minLength", "maxItems", "minItems"):
            self.assertNotIn(limit, text)  # llama.cpp 문법에서 반복 규칙으로 펼쳐져 생성을 늦춘다
        self.assertEqual(payload["format"]["properties"]["observations"]["items"]["properties"]["evidence"]["items"],
                         {"type": "string", "enum": ["target_up", "trigger"]})
        self.assertEqual(set(payload["format"]["required"]), {"summary", "observations", "hypotheses", "next_checks"})
        self.assertEqual(payload["messages"][0]["role"], "system")
        self.assertTrue(payload["messages"][0]["content"].startswith(onprem_llm.SYSTEM_INSTRUCTION))
        self.assertIn("예시 문장을 쓰지 말고", payload["messages"][0]["content"])
        self.assertIn("untrusted_evidence", payload["messages"][1]["content"])
        self.assertEqual(result, analysis())
        self.assertEqual(usage, {"model": "qwen3.6:35b-a3b", "input_tokens": 1500, "output_tokens": 180, "total_ms": 4200})

    def test_cloud_models_and_bad_settings_are_refused_before_any_call(self):
        with patch("onprem_llm.json_request") as call:
            for config in ({"ai_model": "gpt-oss:120b-cloud"}, {"ai_model": "glm-4.6:cloud"}, {"ai_model": "a b"},
                           {"ai_think": "max"}, {"ai_timeout": 60}, {"ai_max_output_tokens": 10}):
                with self.subTest(config=config), self.assertRaises(RequestFailure):
                    onprem_llm.analyze(facts(), config)
        call.assert_not_called()

    def test_incomplete_or_malformed_answers_fail(self):
        broken = [ollama_response(done=False), ollama_response(done_reason="length"),
                  ollama_response(content={"summary": "x"}), {"done": True, "message": {"content": "not json"}},
                  ollama_response(eval_count=-1)]
        for body in broken:
            with self.subTest(body=str(body)[:60]), patch("onprem_llm.json_request", return_value=body):
                with self.assertRaises(RequestFailure) as caught:
                    onprem_llm.analyze(facts(), {})
                self.assertEqual(caught.exception.code, "ollama_response_invalid")

    def test_transport_failures_are_renamed_without_detail(self):
        with patch("onprem_llm.json_request", side_effect=RequestFailure("timeout")):
            with self.assertRaises(RequestFailure) as caught:
                onprem_llm.analyze(facts(), {})
        self.assertEqual(caught.exception.code, "ollama_timeout")


class ReporterOllamaModeTest(unittest.TestCase):
    def setUp(self):
        patcher = patch("onprem_llm.socket.getaddrinfo", resolve("127.0.0.1"))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_validated_analysis_is_marked_as_onprem(self):
        with patch("onprem_llm.json_request", return_value=ollama_response()):
            result = reporter.analyze(facts(), {"analyzer": "ollama"})
        self.assertEqual(result["mode"], "ollama")
        self.assertEqual(result["model"], "qwen3.6:35b-a3b")
        self.assertEqual(result["summary"], analysis()["summary"])

    def test_prompt_times_are_kst_and_the_card_rewrites_a_utc_clock_labeled_kst(self):
        evidence = {"schema_version": 1, "collected_at": "2026-09-27T15:06:01+00:00", "window_end": 1790521561,
                    "window_seconds": 900, "facts": [{"id": "trigger", "kind": "alert_trigger", "status": "ok", "records": [
                        {"status": "resolved", "labels": {"alertname": "AppTierCpuHigh"},
                         "startsAt": "2026-09-27T15:03:46.132Z", "endsAt": "2026-09-27T15:05:06.132Z"}]}]}
        answer = {"summary": "앱 계층 CPU 높음 경보가 2026-09-27 15:03:46(KST)에서 15:05:06(KST)까지 발생했습니다.",
                  "observations": [], "hypotheses": [], "next_checks": []}
        with patch("onprem_llm.json_request", return_value=ollama_response(answer)) as call:
            result = reporter.analyze(evidence, {"analyzer": "ollama"})
        system, user = (message["content"] for message in call.call_args[1]["payload"]["messages"])
        self.assertIn('"startsAt":"2026-09-28T00:03:46+09:00","endsAt":"2026-09-28T00:05:06+09:00"', user)
        self.assertNotIn("15:03", user)
        self.assertIn("시간대를 다시 계산하지 않는다", system)
        self.assertEqual(result["summary"], answer["summary"])
        body = card({"id": "incident:midnight", "kind": "incident", "evidence": evidence, "analysis": result})
        texts = [block.get("text") for block in body["attachments"][0]["content"]["body"]]
        self.assertIn("앱 계층 CPU 높음 경보가 2026-09-28 00:03 KST에서 00:05 KST까지 발생했습니다.", texts)

    def test_model_failure_falls_back_to_facts_with_error_code(self):
        with patch("onprem_llm.json_request", side_effect=RequestFailure("network_error")):
            result = reporter.analyze(facts(), {"analyzer": "ollama"})
        self.assertEqual(result["mode"], "facts-only")
        self.assertEqual(result["error"], "ollama_network_error")


if __name__ == "__main__":
    unittest.main()
