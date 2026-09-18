#!/usr/bin/env python3
"""배포 전 비밀 값을 출력하지 않고 역할 설정과 최악 DB 연결 합계를 검사한다."""
import argparse
import ipaddress
import json
import re
from pathlib import Path


DEFAULTS = {
    "DB_MAX_CONNECTIONS": 200, "DB_RESERVED_CONNECTIONS": 3,
    "DB_OPERATIONS_CONNECTIONS": 10, "ENGINE_NODE_COUNT": 2, "ENGINE_ROLLOUT_SURGE": 1,
    "APP_DB_POOL_MAX": 32, "APP_DB_POOL_IDLE": 8, "APP_APPEND_POOL_MAX": 14,
    "APP_LEASE_POOL_MAX": 2, "READ_MAX_NODES": 2, "READ_DB_POOL_MAX": 8,
    "BOT_NODE_COUNT": 1, "BOT_DB_POOL_MAX": 8, "MAX_WAL_SENDERS": 4,
}
ROLES = ("app", "read", "bot", "data", "standby", "migrate")


def read_env(path):
    values = {}
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        key = key.strip()
        if not sep or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError(f"invalid environment entry at line {number}")
        if key in values:
            raise ValueError(f"duplicate environment key: {key}")
        value = value.strip()
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"unterminated environment value at line {number}")
            value = value[1:-1]
        else:
            value = value.split(" #", 1)[0].rstrip()
        values[key] = value
    return values


def number(values, key):
    raw = values.get(key, str(DEFAULTS[key]))
    if not re.fullmatch(r"[0-9]+", str(raw)):
        raise ValueError(f"{key} must be an integer")
    return int(raw)


def budget(values):
    n = {key: number(values, key) for key in DEFAULTS}
    for key in ("DB_MAX_CONNECTIONS", "ENGINE_NODE_COUNT", "APP_DB_POOL_MAX",
                "APP_APPEND_POOL_MAX", "APP_LEASE_POOL_MAX", "READ_MAX_NODES",
                "READ_DB_POOL_MAX", "BOT_DB_POOL_MAX", "MAX_WAL_SENDERS"):
        if n[key] < 1:
            raise ValueError(f"{key} must be positive")
    if n["APP_DB_POOL_IDLE"] > n["APP_DB_POOL_MAX"]:
        raise ValueError("APP_DB_POOL_IDLE exceeds APP_DB_POOL_MAX")
    if n["READ_DB_POOL_MAX"] < 2 or n["BOT_DB_POOL_MAX"] < 2:
        raise ValueError("read/bot pool maximum must cover minimum-idle=2")
    if n["APP_APPEND_POOL_MAX"] < 2:
        raise ValueError("APP_APPEND_POOL_MAX must cover minimum-idle=2")
    # 조회·봇에도 공통 배선의 append/lease 풀이 각 1개 있다.
    engines = (n["ENGINE_NODE_COUNT"] + n["ENGINE_ROLLOUT_SURGE"]) * (
        n["APP_DB_POOL_MAX"] + n["APP_APPEND_POOL_MAX"] + n["APP_LEASE_POOL_MAX"])
    readers = n["READ_MAX_NODES"] * (n["READ_DB_POOL_MAX"] + 2)
    bots = n["BOT_NODE_COUNT"] * (n["BOT_DB_POOL_MAX"] + 2)
    total = engines + readers + bots + n["DB_OPERATIONS_CONNECTIONS"] + n["MAX_WAL_SENDERS"]
    available = n["DB_MAX_CONNECTIONS"] - n["DB_RESERVED_CONNECTIONS"]
    if total > available:
        raise ValueError(f"DB connection budget exceeded: {total} > {available}")
    return {"engines_including_rollout": engines, "readers_at_max": readers, "bots": bots,
            "operations": n["DB_OPERATIONS_CONNECTIONS"], "replication": n["MAX_WAL_SENDERS"],
            "total": total, "available": available, "headroom": available - total}


def private_bind(value):
    address = ipaddress.ip_address(value)
    ranges = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")
    return address.version == 4 and any(address in ipaddress.ip_network(cidr) for cidr in ranges)


def validate(values, role, local_image=False):
    result = budget(values)
    required = ["DB_HOST"] if role != "data" else []
    if role in ("app", "read", "bot", "migrate"):
        required.append("APP_IMAGE")
    if role in ("app", "read", "bot"):
        required += ["MASTER_KEY_BASE64", "PASSWORD_PEPPER_BASE64", "SESSION_SEED_BASE64"]
    if role == "bot":
        required.append("SIM_BASE_URL")
    if role in ("app", "read"):
        required += ["CORS_ALLOWED_ORIGINS", "WS_ALLOWED_ORIGINS", "EXCHANGE_TRUSTED_PROXIES"]
    if role in ("data", "standby", "migrate"):
        required += ["DB_ADMIN_USER", "DB_ADMIN_PASSWORD"]
    if role in ("data", "standby"):
        required.append("REPL_PASSWORD")
    for prefix in ({"app": ["DB"], "read": ["READ_DB"], "bot": ["BOT_DB"],
                    "migrate": ["DB", "READ_DB", "BOT_DB"]}.get(role, [])):
        required += [prefix + "_USER", prefix + "_PASSWORD"]
    for key in required:
        if not values.get(key):
            raise ValueError(f"required setting is missing: {key}")
    if "APP_IMAGE" in required and not local_image:
        if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", values["APP_IMAGE"]):
            raise ValueError("APP_IMAGE must use a sha256 digest (local lab: --local-image)")
    bindings = ["DB_BIND_IP"] if role in ("data", "standby") else ["APP_BIND_IP", "MGMT_BIND_IP"]
    for key in bindings:
        try:
            valid = private_bind(values.get(key, "127.0.0.1"))
        except ValueError:
            valid = False
        if not valid:
            raise ValueError(f"{key} must be loopback or an explicit RFC1918 IPv4 address")
    if role == "app":
        if values.get("SPRING_PROFILES_ACTIVE", "prod") != "prod":
            raise ValueError("app node requires the prod profile")
        if values.get("EXCHANGE_ENGINE_ENABLED", "true").lower() != "true":
            raise ValueError("use compose.node-read.yml for a read role")
    if role in ("app", "read"):
        trusted = values["EXCHANGE_TRUSTED_PROXIES"]
        if trusted in (".*", ".+", "^.*$", "^.+$"):
            raise ValueError("EXCHANGE_TRUSTED_PROXIES must not trust every source")
        if values.get("APP_BIND_IP", "127.0.0.1") != "127.0.0.1" and trusted == r"127\.0\.0\.1":
            raise ValueError("remote app bind requires an explicit load balancer proxy expression")
    for key in ("DB_USER", "READ_DB_USER", "BOT_DB_USER"):
        name = values.get(key)
        if name and (name == values.get("DB_ADMIN_USER") or name in ("postgres", "app_role", "harness_role")):
            raise ValueError(f"{key} must be a separate runtime login")
    users = [values[key] for key in ("DB_USER", "READ_DB_USER", "BOT_DB_USER") if values.get(key)]
    if len(users) != len(set(users)):
        raise ValueError("app, read and bot logins must be distinct")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--role", required=True, choices=ROLES)
    parser.add_argument("--local-image", action="store_true")
    args = parser.parse_args()
    try:
        result = validate(read_env(args.env), args.role, args.local_image)
    except (ValueError, OSError) as error:
        # 에러에는 키 이름과 숫자만 포함하며 실제 비밀 값은 출력하지 않는다.
        print(f"FAIL: {error}")
        return 1
    print(json.dumps({"role": args.role, "connection_budget": result}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
