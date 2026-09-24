"""Run an isolated service/DB recovery drill with actual API acknowledgements.

The normal lab uses failstop-ops-lab. --repeat creates a new, uniquely named lab
with its own volumes, ports and credentials instead of resetting the existing DB.
"""
import argparse
import base64
import csv
import datetime as dt
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
STATE = HERE / ".state"
PROJECT = "failstop-ops-lab"
API = "http://127.0.0.1:58180"
RESTORED_API = "http://127.0.0.1:58182"
EVIDENCE_FILE = "db-recovery-drill.json"
DOCKER = os.environ.get("DOCKER_BIN", "docker")



# summary.sql 판정 어휘(하네스 검증설계 §4-1 '판정 어휘', ADR 0034 S3). 이 파일을 쓰는 복원·복구 점검 여섯 곳이 같은 뜻으로
# 둔다. N/A 는 그 판정이 읽는 마이그레이션 객체가 모두 없는 행이라 통과로도 실패로도 세지 않고 따로 적는다. N/A 를 낼 수 있는
# 코드는 C29(V48 객체)와 C31(V50 객체)뿐이다. 그 밖의 N/A 는 형식 오류다. 미평가 SKIP 은 종전대로 실패다.
VERDICTS = ("PASS", "FAIL", "SKIP", "N/A")
NOT_APPLICABLE_CODES = ("C29", "C31")


def judge_invariant_rows(rows):
    """summary.sql 판정 행을 가른다. 형식 오류는 ValueError 다.

    failed 는 CRITICAL·HIGH 행 가운데 FAIL·SKIP 인 코드, not_applicable 은 N/A 코드다. N/A 는 통과 목록에 넣지 않는다.
    """
    if len(rows) < 28:
        raise ValueError("invariant catalogue is incomplete: %d rows" % len(rows))
    for row in rows:
        verdict = row.get("verdict")
        if verdict not in VERDICTS:
            raise ValueError("unknown invariant verdict: %r" % (verdict,))
        if verdict == "N/A" and row.get("code") not in NOT_APPLICABLE_CODES:
            raise ValueError("N/A verdict outside C29/C31: %r" % (row.get("code"),))
    failed = [row["code"] for row in rows
              if row.get("severity") in ("CRITICAL", "HIGH") and row["verdict"] in ("FAIL", "SKIP")]
    not_applicable = [row["code"] for row in rows if row["verdict"] == "N/A"]
    return {"failed": failed, "not_applicable": not_applicable}

def run(args, *, input=None, binary=False, timeout=240):
    result = subprocess.run([DOCKER, *args], input=input, capture_output=True,
                            text=not binary, encoding=None if binary else "utf-8",
                            timeout=timeout, cwd=ROOT)
    if result.returncode:
        # Docker errors can contain interpolated environment values. Keep details local.
        raise RuntimeError(f"Docker {args[0]} failed (exit {result.returncode}); inspect the labelled lab containers")
    return result.stdout


def compose(*args, **kwargs):
    return run(["compose", "--project-name", PROJECT, "--env-file", str(STATE / "runtime.env"),
                "-f", str(HERE / "compose.yml"), *args], **kwargs)


def env_read():
    return dict(line.split("=", 1) for line in (STATE / "runtime.env").read_text().splitlines() if "=" in line)


def env_write(values):
    path = STATE / "runtime.env"
    path.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n", encoding="utf-8")
    path.chmod(0o600)


def initialize():
    STATE.mkdir(exist_ok=True)
    if not (STATE / "runtime.env").exists():
        values = {"LAB_DB_PASSWORD": secrets.token_hex(24), "LAB_REPL_PASSWORD": secrets.token_hex(24)}
        for key in ["LAB_MASTER_KEY", "LAB_PEPPER", "LAB_SESSION_SEED"]:
            values[key] = base64.b64encode(secrets.token_bytes(32)).decode()
        values.update(LAB_ACTIVE_DB="primary", LAB_FLYWAY_ENABLED="true")
        env_write(values)


def configure_repeat():
    global PROJECT, STATE, API, RESTORED_API, EVIDENCE_FILE
    PROJECT = "failstop-ops-repeat-" + uuid.uuid4().hex[:12]
    STATE = HERE / ".state" / PROJECT
    if STATE.exists() or run(["ps", "-aq", "--filter", "label=com.docker.compose.project=" + PROJECT]).strip() or run(
            ["volume", "ls", "-q", "--filter", "label=com.docker.compose.project=" + PROJECT]).strip():
        raise RuntimeError("Repeat lab must not reuse existing state or resources")
    STATE.parent.mkdir(exist_ok=True)
    initialize()
    sockets, ports = [], []
    try:
        for _ in range(4):
            connection = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sockets.append(connection)
            connection.bind(("127.0.0.1", 0))
            ports.append(connection.getsockname()[1])
    finally:
        for connection in sockets:
            connection.close()
    values = env_read()
    values.update(zip(("LAB_APP_PORT", "LAB_RESTORED_APP_PORT", "LAB_DB_PRIMARY_PORT", "LAB_DB_STANDBY_PORT"), map(str, ports)))
    env_write(values)
    API, RESTORED_API = (f"http://127.0.0.1:{port}" for port in ports[:2])
    EVIDENCE_FILE = PROJECT + ".json"
    print(f"Repeat lab: {PROJECT}; application: {API}; original lab unchanged", flush=True)


def cleanup_repeat():
    suffix = PROJECT.removeprefix("failstop-ops-repeat-")
    if not PROJECT.startswith("failstop-ops-repeat-") or len(suffix) != 12 or any(char not in "0123456789abcdef" for char in suffix) or STATE.resolve() != (HERE / ".state" / PROJECT).resolve():
        raise RuntimeError("Cleanup only accepts the newly created repeat lab")
    compose("--profile", "restore", "--profile", "rejoin", "down", "--volumes")
    if run(["ps", "-aq", "--filter", "label=com.docker.compose.project=" + PROJECT]).strip() or run(
            ["volume", "ls", "-q", "--filter", "label=com.docker.compose.project=" + PROJECT]).strip():
        raise RuntimeError("Repeat lab resources remain after cleanup")


def build():
    jars = list((ROOT / "server/app/build/libs").glob("app-*.jar"))
    jars = [jar for jar in jars if "-plain" not in jar.name]
    if len(jars) != 1:
        raise RuntimeError("Build exactly one :app:bootJar before building the lab image")
    context = STATE / "image"
    context.mkdir(exist_ok=True)
    shutil.copy2(jars[0], context / "app.jar")
    shutil.copy2(ROOT / "harness/Dockerfile.app", context / "Dockerfile")
    run(["build", "--tag", "failstop-ops:local", str(context)], timeout=600)
    print("PASS: lab image built from current bootJar", flush=True)


def sql(service, query):
    return compose("exec", "-T", service, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                   "-U", "ops_admin", "-d", "exchange", input=query).strip()


def request(path, data=None, token=None, base=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request((base or API) + path, body, headers)
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        value = json.load(error)
        # Tokens/passwords are never included in errors or evidence.
        raise RuntimeError(f"API {path.split('?')[0]} returned {error.code}: {value.get('error', {}).get('name', 'request_failed')}") from None


def wait_for(probe, description, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = probe()
            if result:
                return result
        except (OSError, RuntimeError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError(f"Timeout waiting for {description}")


def ready(base=None):
    return wait_for(lambda: request("/actuator/health/readiness", base=base)[1].get("status") == "UP", "app readiness")


def up():
    if env_read()["LAB_ACTIVE_DB"] != "primary":
        raise RuntimeError("This lab already ran failover. Preserve evidence, then explicitly destroy before a fresh drill")
    compose("up", "-d", "--wait", "primary")
    compose("up", "-d", "app")
    ready()
    compose("up", "-d", "--wait", "standby")
    wait_for(lambda: sql("primary", "SELECT count(*) FROM pg_stat_replication WHERE state='streaming';") == "1", "streaming standby")
    status, markets = request("/api/v1/markets")
    count = len(markets.get("data", [])) if isinstance(markets, dict) else len(markets)
    if count < 1:
        raise RuntimeError("No market rows: startup is not meaningful")
    return {"markets": count, "http_status": status}


def write_evidence(name, value):
    target = ROOT / "artifacts/operations-20260918" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return str(target)


def seed_trades():
    suffix = uuid.uuid4().hex[:10]
    sessions = []
    for role in ("seller", "buyer"):
        _, session = request("/api/v1/auth/signup", {"email": f"{role}-{suffix}@example.test",
                            "password": "Drill-" + secrets.token_hex(12), "display_name": f"{role}{suffix}"})
        sessions.append(session)
    seller, buyer = sessions
    # A lab-only BTC genesis fixture goes through the same balanced ledger function.
    # Demo sessions deliberately cannot submit member orders.
    seller_id = int(sql("primary", f"SELECT id FROM users WHERE email='seller-{suffix}@example.test';"))
    sql("primary", f"""SELECT post_ledger_transaction('GENESIS', {2_000_000_000_000 + seller_id},
        jsonb_build_array(
          jsonb_build_object('account_id', (SELECT id FROM accounts WHERE owner_type='SYSTEM' AND owner_id=0 AND purpose='GENESIS' AND asset_id=(SELECT id FROM assets WHERE symbol='BTC')), 'asset_id',(SELECT id FROM assets WHERE symbol='BTC'), 'amount','-1', 'entry_type','GENESIS'),
          jsonb_build_object('account_id', (SELECT id FROM accounts WHERE owner_type='USER' AND owner_id={seller_id} AND purpose='AVAILABLE' AND asset_id=(SELECT id FROM assets WHERE symbol='BTC')), 'asset_id',(SELECT id FROM assets WHERE symbol='BTC'), 'amount','1', 'entry_type','GENESIS')));""")
    acknowledgements = []
    for side, token in [("ask", seller["access_token"]), ("bid", buyer["access_token"])]:
        key = str(uuid.uuid4())
        order = {"market": "KRW-BTC", "side": side, "ord_type": "limit", "price": "100000000",
                 "volume": "0.001", "time_in_force": "GTC", "client_order_id": key}
        status, result = request("/api/v1/orders", order, token)
        if status not in (200, 201) or result.get("status") == "REJECTED":
            raise RuntimeError("No actual accepted order")
        acknowledgements.append({"client_order_id": key, "id": result["id"], "http_status": status})
        time.sleep(1)
    wait_for(lambda: int(sql("primary", "SELECT count(*) FROM trades;")) > 0, "actual matching trade")
    return acknowledgements


def invariant_summary(service):
    query = (ROOT / "harness/sql/summary.sql").read_text(encoding="utf-8")
    # psql quiet mode suppresses meta-command notices, leaving exactly the CSV catalogue.
    output = compose("exec", "-T", service, "psql", "-X", "-q", "--csv", "-v", "ON_ERROR_STOP=1",
                     "-U", "ops_admin", "-d", "exchange", input=query)
    rows = list(csv.DictReader(io.StringIO(output)))
    try:
        verdicts = judge_invariant_rows(rows)
    except ValueError as error:
        raise RuntimeError("Invariant catalogue is incomplete: " + str(error)) from None
    if verdicts["failed"]:
        raise RuntimeError("Invariant violations: " + ",".join(verdicts["failed"]))
    return rows


def counts(service):
    return json.loads(sql(service, "SELECT json_build_object('commands',(SELECT count(*) FROM command_journal),"
        "'events',(SELECT count(*) FROM event_journal),'trades',(SELECT count(*) FROM trades),"
        "'ledger_entries',(SELECT count(*) FROM ledger_entries),'orders',(SELECT count(*) FROM orders));"))


def assert_positive(value):
    if not all(value[key] > 0 for key in ["commands", "events", "trades", "ledger_entries", "orders"]):
        raise RuntimeError("Empty transactions cannot prove recovery")


def drill():
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    result = {"schema_version": 1, "status": "FAIL", "environment": "isolated local Docker, PostgreSQL asynchronous replication",
              "started_at": started, "project": PROJECT, "phases": {}}
    try:
        result["phases"]["startup"] = up()
        print("PASS: app ready and standby streaming", flush=True)
        acks = seed_trades()
        result["acknowledgements"] = acks
        compose("stop", "app")
        expected = counts("primary")
        assert_positive(expected)
        result["before"] = expected
        result["invariants_before"] = invariant_summary("primary")
        print("PASS: accepted orders, actual trades and pre-failover invariants", flush=True)
        # This is a caught-up controlled drill. It does not claim zero RPO for an abrupt DB loss.
        lsn = sql("primary", "SELECT pg_current_wal_lsn();")
        if not all(c in "0123456789ABCDEF/" for c in lsn):
            raise RuntimeError("Unexpected WAL position")
        wait_for(lambda: sql("standby", f"SELECT pg_last_wal_replay_lsn() >= '{lsn}'::pg_lsn;") == "t", "standby caught up")
        if sql("standby", "SELECT pg_is_in_recovery();") != "t":
            raise RuntimeError("Promotion target is not a standby")
        cutover = time.monotonic()
        compose("stop", "primary")
        old_id = compose("ps", "-aq", "primary").strip()
        if not old_id or run(["inspect", "--format", "{{.State.Running}}", old_id]).strip() != "false":
            raise RuntimeError("Old primary is not fenced")
        if sql("standby", "SELECT pg_promote(true, 30);") != "t":
            raise RuntimeError("Manual promotion failed")
        if sql("standby", "SELECT pg_is_in_recovery();") != "f":
            raise RuntimeError("Promotion target is still in recovery")
        values = env_read()
        values.update(LAB_ACTIVE_DB="standby", LAB_FLYWAY_ENABLED="false")
        env_write(values)
        compose("up", "-d", "--no-deps", "--force-recreate", "app")
        ready()
        result["phases"]["manual_failover"] = {"old_primary_stopped": True, "standby_recovery_before": True,
            "promoted_recovery_after": False, "caught_up_wal_lsn": lsn,
            "app_ready_seconds": round(time.monotonic() - cutover, 3), "human_decision_time_included": False}
        print("PASS: old primary fenced, standby promoted and app connected", flush=True)
        compose("stop", "app")
        after = counts("standby")
        assert_positive(after)
        if expected != after:
            raise RuntimeError("Database counts changed across controlled failover")
        preserved = []
        for ack in acks:
            key = str(uuid.UUID(ack["client_order_id"]))
            matches = int(sql("standby", f"SELECT count(*) FROM command_journal WHERE client_order_id='{key}';"))
            if matches != 1:
                raise RuntimeError("Acknowledged order lost or duplicated")
            preserved.append(key)
        result["after_failover"] = after
        result["ack_preserved"] = len(preserved)
        result["invariants_after_failover"] = invariant_summary("standby")
        print("PASS: acknowledged orders and invariants preserved after failover", flush=True)
        sql("standby", "SELECT pg_create_physical_replication_slot('ops_rejoined') WHERE NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name='ops_rejoined');")
        compose("--profile", "rejoin", "up", "-d", "--wait", "rejoined")
        wait_for(lambda: sql("standby", "SELECT count(*) FROM pg_stat_replication WHERE state='streaming';") == "1", "basebackup rejoin")
        result["phases"]["rejoin"] = {"new_volume": True, "streaming": True, "old_primary_volume_preserved": True}
        print("PASS: new standby rejoined and streaming", flush=True)
        dump = compose("exec", "-T", "standby", "pg_dump", "-U", "ops_admin", "-d", "exchange", "-Fc", binary=True)
        backup = STATE / "exchange.dump"
        backup.write_bytes(dump)
        backup.chmod(0o600)
        if len(dump) < 1000:
            raise RuntimeError("Backup is unexpectedly empty")
        compose("--profile", "restore", "up", "-d", "--wait", "restored")
        sql("restored", (ROOT / "server/app/src/main/resources/db/migration/V0__roles.sql").read_text(encoding="utf-8"))
        compose("exec", "-T", "restored", "pg_restore", "--exit-on-error", "--no-owner", "-U", "ops_admin", "-d", "exchange", input=dump, binary=True)
        restored = counts("restored")
        assert_positive(restored)
        if restored != after:
            raise RuntimeError("Restored transaction counts differ")
        result["restored_counts"] = restored
        result["invariants_restored"] = invariant_summary("restored")
        compose("--profile", "restore", "up", "-d", "restored-app")
        ready(RESTORED_API)
        status, _ = request("/api/v1/markets", base=RESTORED_API)
        result["phases"]["backup_restore"] = {"bytes": len(dump), "restored_http": status, "restored_app_ready": True}
        (STATE / "backup.prom").write_text(f"failstop_backup_last_success_timestamp_seconds {time.time():.0f}\n", encoding="utf-8", newline="\n")
        compose("up", "-d", "--no-deps", "app")
        ready()
        result["status"] = "PASS"
        result["limits"] = ["Single Docker host, not AWS or multi-host validation", "Writes quiesced and standby caught up before fencing",
                            "Asynchronous DB replication can still lose acknowledged writes in an abrupt failure", "Lab application uses isolated superuser credentials"]
    except Exception as error:
        result["error"] = str(error)[:500]
        raise
    finally:
        result["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_evidence(EVIDENCE_FILE, result)
    print("PASS: actual API trade, fenced manual promotion, rejoin, backup restore and invariant checks", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["build", "up", "drill", "status", "stop", "destroy"])
    parser.add_argument("--confirm-project", default="")
    parser.add_argument("--repeat", action="store_true", help="Use a fresh isolated lab and remove only its containers and volumes after success")
    args = parser.parse_args()
    if args.repeat:
        if args.command != "drill":
            raise RuntimeError("--repeat is only supported for drill")
        configure_repeat()
    else:
        initialize()
    if args.command == "build":
        build()
    elif args.command == "up":
        print(json.dumps(up()))
    elif args.command == "drill":
        result = drill()
        if args.repeat:
            cleanup_repeat()
            result["cleanup"] = {"repeat_containers_removed": True, "repeat_volumes_removed": True,
                                 "backup_preserved": True, "original_lab_reset": False}
            path = write_evidence(EVIDENCE_FILE, result)
            print(f"PASS: repeat lab cleaned; evidence: {path}", flush=True)
    elif args.command == "status":
        print(compose("ps"))
    elif args.command == "stop":
        compose("--profile", "restore", "--profile", "rejoin", "stop")
    else:
        if args.confirm_project != PROJECT:
            raise RuntimeError("Destroy requires --confirm-project failstop-ops-lab")
        compose("--profile", "restore", "--profile", "rejoin", "down", "--volumes")
        values = env_read()
        values.update(LAB_ACTIVE_DB="primary", LAB_FLYWAY_ENABLED="true")
        env_write(values)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
