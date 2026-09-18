import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("runtime_files", Path(__file__).resolve().parents[1] / "runtime-files.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeFilesTest(unittest.TestCase):
    def test_copy_omits_secrets_state_and_dump_but_keeps_executable_entrypoint(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "source"
            target = Path(temporary) / "installed"
            secret_paths = ["deploy/.env", "deploy/observability/.state/teams-webhook",
                            "deploy/observability/.state/reporter-token", "deploy/observability/inventory.json",
                            "deploy/reporting/config.json", "deploy/reporting/reports/daily.json",
                            "deploy/terraform/.terraform/state.json", "deploy/lab/backup.dump",
                            "deploy/backup/backup.dump", "deploy/backup/backup.env", "deploy/backup/other.env.example",
                            "deploy/observability/targets/hosts.json"]
            safe_paths = ["deploy/pg-standby-entrypoint.sh", "deploy/compose.node-data.yml",
                          "deploy/observability/prometheus.yml", "deploy/backup/backup.sh",
                          "deploy/backup/backup.env.example", "harness/sql/summary.sql"]
            for name in secret_paths + safe_paths:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("SECRET_MARKER" if name in secret_paths else "source")
            runtime.copy_runtime(root, target)
            self.assertEqual({path.relative_to(target).as_posix() for path in target.rglob("*") if path.is_file()}, set(safe_paths))
            self.assertFalse(any("SECRET_MARKER" in path.read_text() for path in target.rglob("*") if path.is_file()))
            self.assertEqual((target / safe_paths[0]).stat().st_mode & 0o777, 0o755)
            runtime.copy_runtime(target, target)
            self.assertEqual((target / safe_paths[0]).read_text(), "source")


if __name__ == "__main__":
    unittest.main()
