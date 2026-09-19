"""Run a reporting CLI command with the protected installation environment."""
import os
from pathlib import Path
import subprocess
import sys

if sys.argv[1:] not in (["test-send"], ["daily"], ["status"], ["drain"]):
    raise SystemExit("Expected test-send, daily, status or drain")
environment = dict(os.environ)
for line in Path("/etc/failstop/reporting.env").read_text().splitlines():
    if line and not line.startswith("#"):
        key, value = line.split("=", 1)
        environment[key] = value
raise SystemExit(subprocess.run([
    sys.executable, "/opt/failstop/deploy/reporting/reporter.py", sys.argv[1],
    "--config", "/etc/failstop/reporting.json", "--state", "/var/lib/failstop-reporting/outbox.sqlite3",
], env=environment).returncode)
