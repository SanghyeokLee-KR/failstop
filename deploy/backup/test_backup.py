import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("backup", Path(__file__).with_name("backup.py"))
BACKUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)


class BackupTest(unittest.TestCase):
    def test_pgpass_escapes_delimiters_without_shell_interpolation(self):
        text = BACKUP.pgpass({"DB_HOST": "db", "DB_NAME": "exchange", "BACKUP_DB_USER": "reader",
                              "BACKUP_DB_PASSWORD": "a:b\\c'$(no-command)"})
        self.assertEqual("db:5432:exchange:reader:a\\:b\\\\c'$(no-command)\n", text)

    def test_pgpass_rejects_newlines(self):
        with self.assertRaises(ValueError):
            BACKUP.pgpass({"DB_HOST": "db", "DB_NAME": "exchange", "BACKUP_DB_USER": "reader",
                           "BACKUP_DB_PASSWORD": "secret\nsecond-line"})

    def test_failed_backup_preserves_last_success_and_marks_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            BACKUP.metric(directory, "failstop_backup", True)
            previous = (Path(directory) / "failstop_backup.prom").read_text().splitlines()[0]
            BACKUP.metric(directory, "failstop_backup", False)
            current = (Path(directory) / "failstop_backup.prom").read_text()
            self.assertIn(previous, current)
            self.assertIn("failstop_backup_last_run_success 0", current)


if __name__ == "__main__":
    unittest.main()
