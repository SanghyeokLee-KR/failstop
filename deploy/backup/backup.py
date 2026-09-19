#!/usr/bin/env python3
"""PostgreSQL custom-format backup and isolated restore verification."""
import argparse
import csv
import io
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import tempfile
import time
import uuid


def load_secrets(path):
    path = Path(path)
    if path.is_symlink():
        raise ValueError("backup environment must not be a symlink")
    info = path.stat()
    if os.name != "posix" or os.geteuid() != 0 or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError("backup requires a root-owned mode 0600 environment file on Linux")
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in result:
            raise ValueError("invalid or duplicate backup environment entry")
        value = value.strip()
        if len(value) > 1 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        result[key.strip()] = value
    for key in ("DB_HOST", "DB_NAME", "BACKUP_DB_USER", "BACKUP_DB_PASSWORD"):
        if not result.get(key):
            raise ValueError(f"missing backup setting: {key}")
    return result


def pgpass(values):
    fields = [values["DB_HOST"], values.get("DB_PORT", "5432"), values["DB_NAME"],
              values["BACKUP_DB_USER"], values["BACKUP_DB_PASSWORD"]]
    if any("\n" in field or "\r" in field for field in fields):
        raise ValueError("pgpass fields must be single-line values")
    return ":".join(field.replace("\\", "\\\\").replace(":", "\\:") for field in fields) + "\n"


def docker(binary, *args, input_text=None, timeout=1800):
    result = subprocess.run([binary, *map(str, args)], input=input_text, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        # stderr can contain connection details. Journal records the operation, never credentials.
        raise RuntimeError(f"Docker {args[0]} failed with exit {result.returncode}")
    return result.stdout.strip()


def metric(directory, name, success):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"{name}.prom"
    previous = target.read_text(encoding="utf-8") if target.exists() else ""
    stamp = f"{name}_last_success_timestamp_seconds {time.time():.0f}\n" if success else "".join(
        line + "\n" for line in previous.splitlines() if line.startswith(name + "_last_success_timestamp_seconds "))
    temporary = target.with_suffix(".prom.tmp")
    temporary.write_text(stamp + f"{name}_last_run_success {int(success)}\n", encoding="utf-8")
    os.chmod(temporary, 0o644)
    temporary.replace(target)


def backup(args):
    values = load_secrets(args.env)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    os.chmod(output, 0o700)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8]
    archive = output / (stamp + ".dump")
    # systemd serializes timer invocations; flock also covers a simultaneous manual run.
    import fcntl
    with (output / ".backup.lock").open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            with tempfile.TemporaryDirectory(prefix=".backup-", dir=output) as temporary:
                staging = Path(temporary)
                password_file = staging / "pgpass"
                password_file.write_text(pgpass(values), encoding="utf-8")
                password_file.chmod(0o600)
                docker(args.docker, "run", "--rm", "--network", "host", "--user", "0:0",
                       "--mount", f"type=bind,source={staging},target=/backup",
                       "-e", "PGPASSFILE=/backup/pgpass", args.image,
                       "pg_dump", "--no-password", "--format=custom", "--file=/backup/database.dump",
                       "--host", values["DB_HOST"], "--port", values.get("DB_PORT", "5432"),
                       "--username", values["BACKUP_DB_USER"], "--dbname", values["DB_NAME"])
                dump = staging / "database.dump"
                if dump.stat().st_size < 1000:
                    raise RuntimeError("backup archive is unexpectedly empty")
                dump.chmod(0o600)
                dump.replace(archive)
            metric(args.metrics_dir, "failstop_backup", True)
        except Exception:
            metric(args.metrics_dir, "failstop_backup", False)
            raise
    print(json.dumps({"status": "PASS", "archive": str(archive), "bytes": archive.stat().st_size,
                      "restored": False}))


def restore_test(args):
    if args.archive.is_symlink():
        raise ValueError("restore-test requires a regular backup archive")
    archive = args.archive.resolve(strict=True)
    if not archive.is_file():
        raise ValueError("restore-test requires a regular backup archive")
    # No source host, database endpoint or caller-selected container is accepted here.
    # Every restore goes into a freshly created disposable container without published ports.
    name = "failstop-backup-check-" + uuid.uuid4().hex[:12]
    created = False
    try:
        with tempfile.TemporaryDirectory(prefix="failstop-restore-") as temporary:
            env_file = Path(temporary) / "database.env"
            env_file.write_text("POSTGRES_USER=restore_owner\nPOSTGRES_DB=exchange\nPOSTGRES_PASSWORD="
                                + secrets.token_hex(32) + "\n", encoding="utf-8")
            env_file.chmod(0o600)
            docker(args.docker, "run", "-d", "--name", name, "--network", "none",
                   "--env-file", env_file, "--label", "io.failstop.backup-check=true", args.image)
            created = True
            deadline = time.monotonic() + 90
            while True:
                try:
                    docker(args.docker, "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "restore_owner", "-d", "exchange")
                    break
                except RuntimeError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError("isolated restore database did not start") from None
                    time.sleep(1)
            docker(args.docker, "cp", archive, name + ":/tmp/restore.dump")
            docker(args.docker, "exec", name, "pg_restore", "--exit-on-error", "--no-owner", "--no-acl",
                   "-U", "restore_owner", "-d", "exchange", "/tmp/restore.dump")
            counts_sql = "SELECT json_build_object('commands',(SELECT count(*) FROM command_journal),'trades'," \
                         "(SELECT count(*) FROM trades),'events',(SELECT count(*) FROM event_journal)," \
                         "'ledger_entries',(SELECT count(*) FROM ledger_entries));"
            counts = json.loads(docker(args.docker, "exec", name, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                                      "-U", "restore_owner", "-d", "exchange", "-c", counts_sql))
            if not all(value > 0 for value in counts.values()):
                raise RuntimeError("empty trading data cannot prove backup recovery")
            catalogue = args.invariants.read_text(encoding="utf-8")
            output = docker(args.docker, "exec", "-i", name, "psql", "-X", "-q", "--csv", "-v", "ON_ERROR_STOP=1",
                            "-U", "restore_owner", "-d", "exchange", input_text=catalogue)
            rows = list(csv.DictReader(io.StringIO(output)))
            if len(rows) < 28 or any(row.get("verdict") not in ("PASS", "FAIL", "SKIP") for row in rows):
                raise RuntimeError("restore invariant catalogue is incomplete")
            if any(row["verdict"] != "PASS" for row in rows if row["severity"] in ("CRITICAL", "HIGH")):
                raise RuntimeError("restored backup violates trading invariants")
            result = {"status": "PASS", "isolated_database": True, "counts": counts,
                      "invariants": rows, "application_boot_checked": False}
            args.result.parent.mkdir(parents=True, exist_ok=True)
            args.result.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            if args.metrics_dir:
                metric(args.metrics_dir, "failstop_backup_restore", True)
            print(json.dumps({"status": "PASS", "result": str(args.result), "application_boot_checked": False}))
    except Exception as error:
        args.result.parent.mkdir(parents=True, exist_ok=True)
        args.result.write_text(json.dumps({"status": "FAIL", "error": str(error),
                                          "application_boot_checked": False}) + "\n", encoding="utf-8")
        if args.metrics_dir:
            metric(args.metrics_dir, "failstop_backup_restore", False)
        raise
    finally:
        if created:
            docker(args.docker, "rm", "-fv", name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", default="postgres:17-alpine")
    commands = parser.add_subparsers(dest="command", required=True)
    dump = commands.add_parser("backup")
    dump.add_argument("--env", type=Path, required=True)
    dump.add_argument("--output", type=Path, default=Path("/var/backups/failstop"))
    dump.add_argument("--metrics-dir", type=Path, default=Path("/var/lib/failstop/metrics"))
    restore = commands.add_parser("restore-test")
    restore.add_argument("--archive", type=Path, required=True)
    restore.add_argument("--invariants", type=Path, default=Path(__file__).resolve().parents[2] / "harness/sql/summary.sql")
    restore.add_argument("--result", type=Path, required=True)
    restore.add_argument("--metrics-dir", type=Path)
    args = parser.parse_args()
    try:
        backup(args) if args.command == "backup" else restore_test(args)
    except Exception as error:
        if args.command == "backup":
            metric(args.metrics_dir, "failstop_backup", False)
        print(f"FAIL: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
