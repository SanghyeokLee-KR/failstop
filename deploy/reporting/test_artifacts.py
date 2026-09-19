import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import urlopen

import artifacts
from reporter import ReportServer, Store, drain_one


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.secret = b"a" * 64
        key = self.root / "key"
        key.write_bytes(self.secret)
        env = patch.dict(os.environ, {"REPORT_ARTIFACT_KEY_FILE": str(key)})
        env.start()
        self.addCleanup(env.stop)
        self.config = {"artifacts": {"enabled": True, "directory": str(self.root / "reports"),
                        "public_base_url": "https://reports.example.org", "font": "unused.ttf"}}
        self.identifier = hashlib.sha256(b"incident:test").hexdigest()
        folder = Path(self.config["artifacts"]["directory"]) / self.identifier
        folder.mkdir(parents=True)
        (folder / "report.pdf").write_bytes(b"%PDF-test")
        (folder / "report.html").write_bytes(b"<!doctype html><title>Test</title>")

    def path(self, extension="pdf", expiry=None):
        expiry = int(time.time()) + 300 if expiry is None else expiry
        sig = artifacts.signature(self.identifier, expiry, extension, self.secret)
        return f"/reports/{self.identifier}/{expiry}/{sig}/report.{extension}"

    def test_signature_binds_expiry_identifier_and_extension(self):
        path = self.path()
        self.assertEqual(artifacts.read_file(path, self.config)[0], b"%PDF-test")
        for invalid in (path.replace(".pdf", ".html"), path.replace(self.identifier, "b" * 64),
                        self.path(expiry=int(time.time()) - 1), self.path(expiry=int(time.time()) + 700000)):
            with self.subTest(invalid=invalid[-10:]), self.assertRaises(PermissionError):
                artifacts.read_file(invalid, self.config)

    def test_rejects_arbitrary_paths_and_non_public_urls(self):
        for path in ("/reports/../key", self.path() + "?download=1", self.path().replace("report.pdf", "key")):
            with self.assertRaises(FileNotFoundError):
                artifacts.read_file(path, self.config)
        for url in ("http://example.org", "https://user:password@example.org", "https://example.org/?token=x"):
            with self.assertRaises(ValueError):
                artifacts.options({"artifacts": {**self.config["artifacts"], "public_base_url": url}})

    def test_download_headers_and_bad_signature(self):
        server = ReportServer(("127.0.0.1", 0), Store(self.root / "outbox.sqlite3"), "a" * 64, self.config)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        base = f"http://127.0.0.1:{server.server_port}"
        with urlopen(base + self.path()) as response:
            self.assertEqual(response.read(), b"%PDF-test")
            self.assertIn("attachment", response.headers["Content-Disposition"])
            self.assertEqual(response.headers["Cache-Control"], "private, no-store")
            self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
        with self.assertRaises(HTTPError) as rejected:
            urlopen(base + self.path().replace(".pdf", ".html"))
        self.assertEqual(rejected.exception.code, 403)
        rejected.exception.close()

    def test_pdf_library_error_preserves_delivery_and_next_job(self):
        from reportlab.pdfbase.ttfonts import TTFError
        store = Store(self.root / "outbox.sqlite3")
        for identity in ("incident:first", "incident:next"):
            store.enqueue(identity, "incident", {})
            with store.connect() as db:
                db.execute("UPDATE jobs SET report=? WHERE id=?", (json.dumps({"id": identity}), identity))
        messages = []
        def message(report):
            return {"attachments": [{"content": {"body": []}}]}
        with patch("reporter.card", side_effect=message), patch("reporter.publish_artifacts", side_effect=TTFError("private font path")):
            self.assertTrue(drain_one(store, self.config, messages.append))
        with patch("reporter.card", side_effect=message), patch("reporter.publish_artifacts", return_value=[{"type": "Action.OpenUrl", "url": "https://reports.example.org/signed"}]):
            self.assertTrue(drain_one(store, self.config, messages.append))
        self.assertEqual(store.stats()["counts"], {"sent": 2})
        self.assertEqual(store.stats()["artifacts"], {"failed": 1, "ready": 1})
        self.assertIn("PDF 생성 실패", str(messages[0]))
        self.assertNotIn("private font path", str(messages))
        self.assertTrue(messages[1]["attachments"][0]["content"]["actions"])


if __name__ == "__main__":
    unittest.main()
