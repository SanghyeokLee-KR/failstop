import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("lab", Path(__file__).with_name("run.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


class RepeatIsolationTest(unittest.TestCase):
    def test_repeat_uses_separate_credentials_state_project_and_ports(self):
        with tempfile.TemporaryDirectory() as directory, patch.multiple(
                lab, HERE=Path(directory), PROJECT=lab.PROJECT, STATE=lab.STATE,
                API=lab.API, RESTORED_API=lab.RESTORED_API, EVIDENCE_FILE=lab.EVIDENCE_FILE), patch.object(lab, "run", return_value=""):
            lab.configure_repeat()
            self.assertNotEqual(lab.PROJECT, "failstop-ops-lab")
            self.assertEqual(lab.STATE, Path(directory) / ".state" / lab.PROJECT)
            values = lab.env_read()
            self.assertEqual(values["LAB_ACTIVE_DB"], "primary")
            ports = [values[key] for key in ("LAB_APP_PORT", "LAB_RESTORED_APP_PORT", "LAB_DB_PRIMARY_PORT", "LAB_DB_STANDBY_PORT")]
            self.assertEqual(len(set(ports)), 4)
            self.assertEqual(lab.API, "http://127.0.0.1:" + ports[0])
            self.assertEqual(lab.RESTORED_API, "http://127.0.0.1:" + ports[1])
            self.assertNotEqual(lab.EVIDENCE_FILE, "db-recovery-drill.json")

    def test_existing_project_resources_are_never_reused(self):
        with patch.multiple(lab, PROJECT=lab.PROJECT, STATE=lab.STATE), patch.object(lab, "run", return_value="existing"), patch.object(lab, "initialize") as initialize:
            with self.assertRaises(RuntimeError):
                lab.configure_repeat()
            initialize.assert_not_called()

    def test_cleanup_rejects_the_original_project_or_unrelated_state(self):
        for project, state in (("failstop-ops-lab", lab.HERE / ".state"),
                               ("failstop-ops-repeat-012345abcdef", lab.HERE / ".state"),
                               ("012345abcdef", lab.HERE / ".state" / "012345abcdef")):
            with self.subTest(project=project), patch.multiple(lab, PROJECT=project, STATE=state), patch.object(lab, "compose") as compose:
                with self.assertRaises(RuntimeError):
                    lab.cleanup_repeat()
                compose.assert_not_called()


if __name__ == "__main__":
    unittest.main()
