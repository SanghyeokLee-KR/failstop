"""Read bounded host state; failures remain explicit and never become healthy zeroes."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


def redact(text):
    text = re.sub(r"https?://[^\s\"<>]+", "[url redacted]", text)
    text = re.sub(r"(?i)Bearer\s+\S+", "Bearer [redacted]", text)
    text = re.sub(r"(?i)(password|passwd|token|secret|authorization|api[_-]?key)(\s*[:=]\s*|\s+)[^\s,;]+", r"\1=[redacted]", text)
    text = re.sub(r"\b[A-Za-z0-9_-]{40,}\b", "[credential redacted]", text)
    return text[:32000]


def command(argv):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=8, check=False)
        return {"ok": result.returncode == 0, "exit_code": result.returncode,
                "output": redact(result.stdout), "error": redact(result.stderr)}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"ok": False, "error": type(error).__name__}


def atomic_write(path, text, mode, group_id=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=".collect-")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            target.write(text)
        os.chmod(temporary, mode)
        if group_id is not None:
            os.chown(temporary, -1, group_id)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def collect():
    # Only this unit's own diagnostic logs are retained; arbitrary application logs may contain PII.
    return {
        "filesystems": command(["df", "-P", "-T"]),
        "inodes": command(["df", "-P", "-i"]),
        "failed_services": command(["systemctl", "--failed", "--no-legend", "--no-pager"]),
        "service_states": command(["systemctl", "list-units", "failstop*", "--no-pager", "--plain"]),
        "disk_io": command(["iostat", "-dx", "1", "2"]),
        "network": command(["ip", "-s", "link"]),
        "journal_usage": command(["journalctl", "--disk-usage"]),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--group", default="root")
    args = parser.parse_args()
    import grp
    group_id = grp.getgrnam(args.group).gr_gid
    timestamp = time.time()
    readings = collect()
    healthy = all(item["ok"] for item in readings.values())
    document = {"schema_version": 1, "collected_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "status": "PASS" if healthy else "FAIL", "sources": readings}
    atomic_write(args.evidence / "host-latest.json", json.dumps(document, ensure_ascii=False, indent=2), 0o640, group_id)
    metrics = (f"failstop_host_collection_timestamp_seconds {timestamp:.0f}\n"
               f"failstop_host_collection_success {int(healthy)}\n"
               f"failstop_host_collection_failed_sources {sum(not item['ok'] for item in readings.values())}\n")
    atomic_write(args.output / "failstop-host.prom", metrics, 0o644)
    return 0 if healthy else 1


if __name__ == "__main__":
    raise SystemExit(main())
