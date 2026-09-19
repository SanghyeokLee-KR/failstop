"""Cost limits survive worker races, restarts, and failed provider requests."""
import concurrent.futures
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from reporter import Store, analyze, load_config
from secret_inputs import read_secret
from transport import RequestFailure


class BudgetTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / "outbox.db"
        self.store = Store(self.path)

    def test_atomic_limit_survives_restart_and_failed_call(self):
        config = {"ai_daily_calls": 2, "ai_minute_calls": 10}
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda n: self.store.reserve_ai(str(n), config)[0], range(8)))
        self.assertEqual(sum(results), 2)
        self.store.finish_ai("0", error="network_error")
        self.assertFalse(Store(self.path).reserve_ai("next", config)[0])

    def test_monthly_and_minute_limits_and_same_id(self):
        self.assertTrue(self.store.reserve_ai("one", {})[0])
        self.assertFalse(self.store.reserve_ai("one", {})[0])
        self.assertFalse(self.store.reserve_ai("two", {"ai_monthly_calls": 1})[0])
        self.assertFalse(self.store.reserve_ai("two", {"ai_minute_calls": 1})[0])

    def test_exhaustion_does_not_call_provider_or_block_facts_report(self):
        config = {"analyzer": "gemini", "ai_daily_calls": 1}
        self.store.reserve_ai("previous", config)
        with patch("gemini.analyze", side_effect=AssertionError("budget must block provider")):
            result = analyze({"facts": []}, config, self.store, "next")
        self.assertEqual(result["mode"], "facts-only")

    def test_generation_failure_is_recorded_without_secret(self):
        with patch("gemini.analyze", side_effect=RequestFailure("http_error", 429)):
            result = analyze({"facts": []}, {"analyzer": "gemini"}, self.store, "example")
        self.assertEqual(result["mode"], "facts-only")
        with self.store.connect() as db:
            row = db.execute("SELECT status,error FROM ai_calls WHERE id='example'").fetchone()
        self.assertEqual(tuple(row), ("failed", "http_error_429"))

    def test_limits_reject_booleans_negative_and_nonintegers(self):
        path = Path(self.folder.name) / "config.json"
        for name in ("ai_daily_calls", "ai_monthly_calls", "ai_minute_calls", "incident_window_seconds"):
            for value in (True, -1, 1.5):
                path.write_text(json.dumps({name: value}))
                with self.assertRaises(ValueError):
                    load_config(path)

    def test_invalid_analysis_preserves_billed_usage(self):
        response = {"summary": "Example", "observations": [{"text": "Example", "evidence": ["unknown"]}],
                    "hypotheses": [], "next_checks": []}
        usage = {"model": "gemini-3.1-flash-lite", "input_tokens": 1000, "output_tokens": 300, "thought_tokens": 10}
        with patch("gemini.analyze", return_value=(response, usage)):
            result = analyze({"facts": []}, {"analyzer": "gemini"}, self.store, "invalid-response")
        self.assertEqual(result["mode"], "facts-only")
        stats = self.store.stats()["ai_usage"]
        self.assertEqual(stats["completed"], 0)
        self.assertEqual((stats["input_tokens"], stats["output_tokens"], stats["thought_tokens"]), (1000, 300, 10))

    def test_mixed_secret_note_never_evaluates_code(self):
        path = Path(self.folder.name) / "secrets.env"
        sample_key = "AQ." + "example" * 7
        sample_url = "https://unit.api.powerplatform.com:443/path?sig=sample"
        path.write_text("Teams\n" + sample_url + "\nGoogle\n" + sample_key + "\n$(do-not-execute)\n")
        self.assertEqual(read_secret(path, "teams"), sample_url)
        self.assertEqual(read_secret(path, "gemini"), sample_key)
        path.write_text('GEMINI_API_KEY="' + sample_key + '="')
        self.assertEqual(read_secret(path, "gemini"), sample_key + "=")
        path.write_text(sample_key + "\nAQ." + "another" * 7)
        with self.assertRaises(ValueError):
            read_secret(path, "gemini")


if __name__ == "__main__":
    unittest.main()
