#!/usr/bin/env python3
"""새 Docker 프로젝트에서 배포 이미지·단발 마이그레이션·조회 역할을 검증한다."""
import argparse
import base64
import json
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request


PROJECT = "failstop-deploy-check"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--image", default="failstop-deploy-check:local")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    checks = {}

    def run(*command, timeout=180, input_text=None):
        result = subprocess.run([args.docker, *command], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout, input=input_text)
        if result.returncode:
            # 테스트용 비밀번호도 명령·환경·전체 로그와 함께 출력하지 않는다.
            raise RuntimeError(f"Docker command failed: {command[0]} (exit {result.returncode})")
        return result.stdout.strip()

    if run("ps", "-aq", "--filter", f"label=com.docker.compose.project={PROJECT}"):
        raise RuntimeError(f"existing {PROJECT} containers found; refusing to reuse their data")

    def free_port():
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]

    api_port, management_port = free_port(), free_port()
    secret = base64.b64encode(bytes(32)).decode()
    values = {
        "APP_IMAGE": args.image, "DB_HOST": "db", "DB_NAME": "exchange", "DB_PORT": "5432",
        "DB_ADMIN_USER": "smoke_owner", "DB_ADMIN_PASSWORD": "test-only-owner",
        "DB_USER": "smoke_app", "DB_PASSWORD": "test-only-app",
        "READ_DB_USER": "smoke_read", "READ_DB_PASSWORD": "test-only-read",
        "BOT_DB_USER": "smoke_bot", "BOT_DB_PASSWORD": "test-only-bot",
        "REPL_PASSWORD": "test-only'quoted", "REPL_CIDR": "172.16.0.0/12",
        "DB_LISTEN_PORT": str(free_port()), "STANDBY_LISTEN_PORT": str(free_port()),
        "APP_PORT": str(api_port), "MGMT_PORT": str(management_port),
        "MASTER_KEY_BASE64": secret, "PASSWORD_PEPPER_BASE64": secret, "SESSION_SEED_BASE64": secret,
        "CORS_ALLOWED_ORIGINS": "https://exchange.example", "WS_ALLOWED_ORIGINS": "https://exchange.example",
        "EXCHANGE_TRUSTED_PROXIES": r"127\.0\.0\.1", "SIM_BASE_URL": "http://read:8080",
    }
    env = output / "smoke.env"
    env.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n", encoding="utf-8")
    compose = ["compose", "-p", PROJECT, "--env-file", str(env),
               "-f", str(root / "deploy/compose.node-data.yml"),
               "-f", str(root / "deploy/compose.migrate.yml"),
               "-f", str(root / "deploy/compose.node-app.yml"),
               "-f", str(root / "deploy/compose.node-read.yml")]

    def sql(query):
        return run(*compose, "exec", "-T", "db", "psql", "-X", "-v", "ON_ERROR_STOP=1",
                   "-U", values["DB_ADMIN_USER"], "-d", "exchange", "-Atq", "-c", query)

    def http(port, path, method="GET", data=None):
        body = None if data is None else json.dumps(data).encode()
        request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method,
                                         data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=8) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def wait(check, seconds=150):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                if check():
                    return
            except (RuntimeError, OSError, urllib.error.URLError):
                pass
            time.sleep(1)
        raise RuntimeError("timed out waiting for deployment state")

    try:
        context = output / "image"
        context.mkdir(exist_ok=True)
        jars = [jar for jar in (root / "server/app/build/libs").glob("*.jar") if "-plain" not in jar.name]
        if len(jars) != 1:
            raise RuntimeError("build exactly one application bootJar before this test")
        shutil.copyfile(jars[0], context / "app.jar")
        run("build", "-t", args.image, "-f", str(root / "harness/Dockerfile.app"), str(context), timeout=600)
        checks["image_build"] = True
        for filename in ("compose.node-app.yml", "compose.node-bot.yml", "compose.node-data.yml",
                         "compose.node-read.yml", "compose.migrate.yml"):
            run("compose", "--env-file", str(env), "-f", str(root / "deploy" / filename), "config", "--quiet")
        checks["compose_config"] = True
        run(*compose, "up", "-d", "db")
        wait(lambda: sql("SELECT 1") == "1")
        # initdb 임시 서버가 아니라 최종 TCP 서버가 수신하기 시작한 시점까지 확인한다.
        wait(lambda: run(*compose, "exec", "-T", "db", "pg_isready", "-h", "127.0.0.1",
                         "-U", values["DB_ADMIN_USER"], "-d", "exchange").endswith("accepting connections"))
        run(*compose, "run", "--rm", "--no-deps", "migrate")
        # Flyway는 파일명 V47_1__의 밑줄을 점으로 바꿔 버전 47.1로 기록한다.
        expected = sorted(path.name[1:].split("__")[0].replace("_", ".")
                          for path in (root / "server/app/src/main/resources/db/migration").glob("V*__*.sql"))
        applied = sorted(sql("SELECT version FROM flyway_schema_history WHERE success").splitlines())
        checks["migrations_match_repository"] = applied == expected
        run(*compose, "run", "--rm", "--no-deps", "catalog-seed")
        run(*compose, "run", "--rm", "--no-deps", "catalog-seed")
        checks["catalog_only"] = sql("SELECT (SELECT count(*) FROM assets), (SELECT count(*) FROM markets),"
                                     " (SELECT count(*) FROM users), (SELECT count(*) FROM ledger_transactions)") == "13|12|0|0"
        checks["empty_system_accounts"] = sql("SELECT (SELECT count(*) FROM accounts WHERE owner_type='SYSTEM'),"
                                              " (SELECT count(*) FROM accounts WHERE owner_type='USER'),"
                                              " (SELECT count(*) FROM balances WHERE balance<>0)") == "39|0|0"
        run(*compose, "run", "--rm", "--no-deps", "provision")
        checks["provision"] = sql("SELECT count(*) FROM pg_roles WHERE rolname IN "
                                   "('smoke_app','smoke_read','smoke_bot') AND NOT rolsuper AND NOT rolcreaterole") == "3"
        checks["max_connections"] = sql("SHOW max_connections") == "200"
        run(*compose, "--profile", "standby", "up", "-d", "db-standby")
        wait(lambda: sql("SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state='async'") == "1")
        checks["standby_with_quoted_password"] = True
        run(*compose, "up", "-d", "read")
        wait(lambda: http(management_port, "/actuator/health/read")[0] == 200)
        checks["market_reads"] = all(http(api_port, path)[0] == 200 for path in (
            "/api/v1/markets", "/api/v1/trades?market=KRW-BTC", "/api/v1/candles/1m?market=KRW-BTC"))
        checks["market_count"] = len(json.loads(http(api_port, "/api/v1/markets")[1])["data"]) == 12
        checks["forbidden_routes"] = all(http(api_port, path, method)[0] == 404
            for path in ("/api/v1/orders", "/api/v1/ticker", "/api/v1/orderbook", "/ws/v1", "/admin/v1/sim")
            for method in ("GET", "POST"))
        checks["no_leader"] = sql("SELECT count(*) FROM leader_lease WHERE holder = '' AND epoch = 0") == "1"
        run(*compose, "stop", "db")
        wait(lambda: http(management_port, "/actuator/health/read")[0] == 503, seconds=45)
        checks["db_outage_read_503_liveness_200"] = http(management_port, "/actuator/health/liveness")[0] == 200
        run(*compose, "up", "-d", "db")
        wait(lambda: http(management_port, "/actuator/health/read")[0] == 200)
        checks["db_recovery"] = True
        run(*compose, "stop", "read")
        run(*compose, "up", "-d", "app")
        wait(lambda: http(management_port, "/actuator/health/readiness")[0] == 200)
        checks["runtime_app_role_leader"] = sql("SELECT count(*) FROM leader_lease WHERE holder <> '' AND epoch > 0") == "1"
        signup_status, _ = http(api_port, "/api/v1/auth/signup", "POST", {
            "email": "deploy-smoke@example.test", "password": "Deploy-smoke-test-2026!",
            "display_name": "deploysmoke"})
        checks["runtime_app_role_signup"] = signup_status in (200, 201)
        if not all(checks.values()):
            raise RuntimeError("one or more deployment checks failed")
    finally:
        (output / "checks.json").write_text(json.dumps(checks, indent=2) + "\n", encoding="utf-8")
        run(*compose, "--profile", "standby", "down", "--volumes", "--remove-orphans")
    print(json.dumps(checks, indent=2))


if __name__ == "__main__":
    main()
