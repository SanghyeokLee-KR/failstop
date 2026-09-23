#!/usr/bin/env python3
"""관측 경로를 점검하고 상태가 바뀔 때만 Teams Workflows 웹훅으로 직접 알린다.

Amazon Linux 2023의 기본 python3(3.9)와 표준 라이브러리만 사용한다. 설계와 한계는 같은 폴더의 README.md를 따른다.
"""
import argparse
import datetime as dt
import hashlib
import http.client
import json
import math
import os
import re
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlencode, urlsplit

KST = dt.timezone(dt.timedelta(hours=9), "Asia/Seoul")
CHECK_TYPES = {
    "http": ("url",),
    "alertmanager_watchdog": ("url",),
    "report_queue": ("url", "token_file"),
    "daily_report": ("url", "token_file", "timer", "service"),
    "systemd_active": ("unit",),
    "https_site": ("url",),
}
NAME = re.compile(r"[a-z][a-z0-9_]{0,39}")
HOST = re.compile(r"[a-z0-9][a-z0-9-]{0,39}")
UNIT = re.compile(r"[A-Za-z0-9@_.-]{1,120}\.(service|timer)")
TEAMS_HOSTS = (".logic.azure.com", ".api.powerplatform.com")
# failstop-reporting-daily.timer의 OnCalendar(매일 09:00 Asia/Seoul)와 같아야 한다.
DAILY_HOUR = 9
# timer는 AccuracySec=1min 안에 실행되고, 보고 생성은 AI 분석과 Teams 재시도를 포함한다.
DAILY_TRIGGER_GRACE = 600
DAILY_SEND_GRACE = 1800
# 1분 timer 기준 약 30분 동안 전송하지 못한 알림은 버린다. 이상이 계속되면 재알림이 새로 만든다.
MAX_TRIES = 30
MAX_OUTBOX = 20
RESPONSE_LIMIT = 262144
NO_RESPONSE = ("network_error", "timeout", "tls_error")


class WatchdogError(Exception):
    """설정·비밀 파일 오류. 점검 대상의 이상과 달리 워치독 서비스 자체를 실패로 끝낸다."""


class CheckFailed(Exception):
    def __init__(self, code, detail=""):
        super().__init__(code)
        self.code, self.detail = code, detail


def load_config(path):
    try:
        config = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise WatchdogError("config_unreadable") from None
    if not isinstance(config, dict) or config.get("schema_version") != 1:
        raise WatchdogError("invalid_config:schema_version")
    for name, default, low, high in (("fail_after", 3, 1, 10), ("recover_after", 2, 1, 10),
                                     ("remind_seconds", 3600, 300, 86400), ("timeout_seconds", 5, 1, 10)):
        value = config.setdefault(name, default)
        if type(value) is not int or not low <= value <= high:
            raise WatchdogError("invalid_config:" + name)
    for name in ("watcher", "customer", "environment"):
        if not isinstance(config.get(name), str) or not HOST.fullmatch(config[name]):
            raise WatchdogError("invalid_config:" + name)
    config.setdefault("textfile", None)
    for name in ("teams_secret_file", "textfile"):
        value = config.get(name)
        if not (isinstance(value, str) and value.startswith("/")) and not (name == "textfile" and value is None):
            raise WatchdogError("invalid_config:" + name)
    checks = config.get("checks")
    if not isinstance(checks, list) or not 1 <= len(checks) <= 20:
        raise WatchdogError("invalid_config:checks")
    names = set()
    for check in checks:
        validate_check(check, names)
    return config


def validate_check(check, names):
    if not isinstance(check, dict) or check.get("type") not in CHECK_TYPES:
        raise WatchdogError("invalid_config:check_type")
    name = check.get("name")
    if not isinstance(name, str) or not NAME.fullmatch(name) or name in names:
        raise WatchdogError("invalid_config:check_name")
    names.add(name)
    if not isinstance(check.get("target"), str) or not HOST.fullmatch(check["target"]):
        raise WatchdogError("invalid_config:target:" + name)
    if not isinstance(check.get("label"), str) or not 1 <= len(check["label"]) <= 60:
        raise WatchdogError("invalid_config:label:" + name)
    if not isinstance(check.setdefault("enabled", True), bool):
        raise WatchdogError("invalid_config:enabled:" + name)
    for field in CHECK_TYPES[check["type"]]:
        if not isinstance(check.get(field), str):
            raise WatchdogError("invalid_config:" + field + ":" + name)
    if "url" in check:
        parts = urlsplit(check["url"])
        allowed = ("https",) if check["type"] == "https_site" else ("http", "https")
        if parts.scheme not in allowed or not parts.hostname or parts.username or parts.password or parts.fragment:
            raise WatchdogError("invalid_config:url:" + name)
    for field in ("unit", "context_unit", "timer", "service"):
        if field in check and not (isinstance(check[field], str) and UNIT.fullmatch(check[field])):
            raise WatchdogError("invalid_config:" + field + ":" + name)
    for field, low, high in (("expect_status", 100, 599), ("max_dead", 0, 100000),
                             ("max_pending_seconds", 60, 86400), ("min_cert_days", 1, 90)):
        if field in check and (type(check[field]) is not int or not low <= check[field] <= high):
            raise WatchdogError("invalid_config:" + field + ":" + name)
    for field in ("expect_json", "labels"):
        if field in check and not (isinstance(check[field], dict)
                                   and all(isinstance(value, str) for value in check[field].values())):
            raise WatchdogError("invalid_config:" + field + ":" + name)
    if not isinstance(check.get("require_delivery", True), bool):
        raise WatchdogError("invalid_config:require_delivery:" + name)


def credential_path(path):
    # systemd LoadCredential= 복사본이 있으면 원본 대신 서비스 전용 사본을 읽는다.
    directory = os.environ.get("CREDENTIALS_DIRECTORY")
    if directory:
        candidate = Path(directory) / Path(path).name
        if candidate.exists():
            return candidate
    return Path(path)


def secret_problem(info, euid, allow_group_read=False):
    if not stat.S_ISREG(info.st_mode):
        return "secret_not_regular_file"
    if info.st_uid not in (0, euid):
        return "secret_owner_untrusted"
    # 쓰기 권한과 기타 사용자 접근은 항상 거부한다. 그룹 읽기는 root:failstop-report 0640 보고 토큰에만 허용한다.
    if info.st_mode & (0o027 if allow_group_read else 0o077):
        return "secret_permissions_too_open"
    if not 0 < info.st_size <= 4096:
        return "secret_size_invalid"
    return None


def read_secret(path, allow_group_read=False):
    name = Path(path).name
    source = credential_path(path)
    copied = source != Path(path)
    euid = os.geteuid() if hasattr(os, "geteuid") else None
    if copied:
        # systemd 사본의 권한은 원본과 무관하게 systemd가 정하므로, 운영자가 관리하는 원본의 권한을 따로 확인한다.
        try:
            problem = secret_problem(os.lstat(path), euid, allow_group_read)
        except OSError:
            raise WatchdogError("secret_unreadable:" + name) from None
        if problem:
            raise WatchdogError(problem + ":" + name)
    try:
        descriptor = os.open(str(source), os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError:
        raise WatchdogError("secret_unreadable:" + name) from None
    try:
        # tmpfs에 만든 사본은 root 0400에 실행 계정 ACL이 붙고, st_mode의 그룹 비트에는 ACL mask(r)가 보인다.
        # 그래서 사본에는 그룹 읽기를 허용하고 쓰기와 기타 사용자 권한만 거부한다.
        problem = secret_problem(os.fstat(descriptor), euid, allow_group_read or copied)
        if problem:
            raise WatchdogError(problem + ":" + name)
        value = os.read(descriptor, 4097).decode("utf-8").strip()
    except UnicodeDecodeError:
        raise WatchdogError("secret_format:" + name) from None
    finally:
        os.close(descriptor)
    if not value or any(character.isspace() for character in value):
        raise WatchdogError("secret_format:" + name)
    return value


def webhook_url(secret_file, reader=read_secret):
    url = reader(secret_file)
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise WatchdogError("webhook_not_teams_workflows") from None
    host = (parts.hostname or "").lower()
    if (parts.scheme != "https" or port not in (None, 443) or parts.username or parts.password
            or parts.fragment or not host.endswith(TEAMS_HOSTS)):
        raise WatchdogError("webhook_not_teams_workflows")
    return url


def fetch(url, timeout, headers=None, want_cert=False):
    parts = urlsplit(url)
    target = (parts.path or "/") + ("?" + parts.query if parts.query else "")
    if parts.scheme == "https":
        conn = http.client.HTTPSConnection(parts.hostname, parts.port or 443, timeout=timeout,
                                           context=ssl.create_default_context())
    else:
        conn = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    try:
        conn.connect()
        cert = conn.sock.getpeercert() if want_cert else None
        conn.request("GET", target, headers=headers or {})
        response = conn.getresponse()
        body = response.read(RESPONSE_LIMIT + 1)
    except ssl.SSLCertVerificationError:
        raise CheckFailed("tls_verify_failed") from None
    except ssl.SSLError:
        raise CheckFailed("tls_error") from None
    except socket.timeout:
        raise CheckFailed("timeout") from None
    except (OSError, http.client.HTTPException):
        raise CheckFailed("network_error") from None
    finally:
        conn.close()
    if len(body) > RESPONSE_LIMIT:
        raise CheckFailed("response_too_large")
    return response.status, body, cert


def systemctl_show(unit, names, timeout):
    properties = ("LoadState",) + tuple(names)
    try:
        # TZ=UTC로 고정해야 LastTriggerUSec 같은 시각을 호스트 시간대와 무관하게 해석할 수 있다.
        done = subprocess.run(["systemctl", "show", "--property=" + ",".join(properties), "--", unit],
                              capture_output=True, text=True, timeout=timeout, check=False,
                              env={"LC_ALL": "C", "TZ": "UTC", "PATH": "/usr/bin:/bin"})
    except (OSError, subprocess.TimeoutExpired):
        raise CheckFailed("systemctl_unavailable") from None
    if done.returncode != 0:
        raise CheckFailed("systemctl_failed", unit)
    values = dict(line.split("=", 1) for line in done.stdout.splitlines() if "=" in line)
    if any(name not in values for name in properties):
        raise CheckFailed("systemctl_property_missing", unit)
    if values["LoadState"] != "loaded":
        raise CheckFailed("unit_not_found", unit)
    return values


def parse_systemd_time(text):
    if text in ("", "n/a", "0"):
        return None
    match = re.search(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) UTC$", text)
    if not match:
        raise CheckFailed("systemd_time_format")
    moment = dt.datetime.strptime(match.group(1), "%Y-%m-%d %H:%M:%S")
    return moment.replace(tzinfo=dt.timezone.utc).timestamp()


def parse_time(text):
    # Go의 RFC3339 시각은 나노초까지 올 수 있어 3.9의 fromisoformat이 읽는 6자리로 줄인다.
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})", text or "")
    if not match:
        raise CheckFailed("invalid_timestamp")
    fraction = (match.group(2) or "0")[:6].ljust(6, "0")
    zone = "+00:00" if match.group(3) == "Z" else match.group(3)
    return dt.datetime.fromisoformat(match.group(1) + "." + fraction + zone).timestamp()


def json_body(body):
    try:
        return json.loads(body)
    except ValueError:
        raise CheckFailed("invalid_json") from None


def check_http(check, env):
    status, body, _ = env.fetch(check["url"], env.timeout)
    if status != check.get("expect_status", 200):
        raise CheckFailed("http_" + str(status))
    if check.get("expect_json"):
        data = json_body(body)
        for key, value in check["expect_json"].items():
            if not isinstance(data, dict) or data.get(key) != value:
                raise CheckFailed("unexpected_response", key)


def check_alertmanager_watchdog(check, env):
    query = urlencode({"filter": 'alertname="Watchdog"'})
    status, body, _ = env.fetch(check["url"].rstrip("/") + "/api/v2/alerts?" + query, env.timeout)
    if status != 200:
        raise CheckFailed("http_" + str(status))
    alerts = json_body(body)
    if not isinstance(alerts, list):
        raise CheckFailed("invalid_json")
    wanted = dict(check.get("labels", {}), alertname="Watchdog")
    for alert in alerts:
        labels = alert.get("labels", {}) if isinstance(alert, dict) else {}
        # Prometheus가 재전송을 멈추면 endsAt이 지나 Alertmanager에서 해제된다.
        if all(labels.get(key) == value for key, value in wanted.items()) and parse_time(alert.get("endsAt")) > env.now:
            return
    raise CheckFailed("watchdog_missing", "Prometheus 규칙 평가 또는 Alertmanager 수신 중단")


def report_metrics(check, env):
    try:
        token = env.secret(check["token_file"], allow_group_read=True)
    except WatchdogError as error:
        raise CheckFailed("token_unavailable", str(error)) from None
    status, body, _ = env.fetch(check["url"], env.timeout, {"Authorization": "Bearer " + token})
    if status != 200:
        raise CheckFailed("http_" + str(status))
    values = {}
    for line in body.decode("utf-8", "replace").splitlines():
        if not line or line.startswith("#"):
            continue
        name, _, value = line.rpartition(" ")
        try:
            number = float(value)
        except ValueError:
            raise CheckFailed("invalid_metrics") from None
        if not math.isfinite(number):
            raise CheckFailed("invalid_metrics")
        values[name] = number
    return values


def metric(values, name):
    if name not in values:
        raise CheckFailed("metric_missing", name)
    return values[name]


def queue_problems(values, max_dead, max_pending, require_delivery):
    problems = []
    dead = metric(values, 'report_jobs{status="dead"}')
    if dead > max_dead:
        problems.append(("report_dead", "전송 포기 %d건(확인한 기준 %d건)" % (dead, max_dead)))
    pending = metric(values, "report_oldest_pending_age_seconds")
    if pending > max_pending:
        problems.append(("report_pending_stale", "가장 오래된 미전송 보고 %d분 경과" % (pending // 60)))
    if require_delivery and metric(values, "report_delivery_enabled") != 1:
        problems.append(("report_delivery_disabled", "보고 서비스의 Teams 전송이 꺼져 있음"))
    return problems


def check_report_queue(check, env):
    problems = queue_problems(report_metrics(check, env), check.get("max_dead", 0),
                              check.get("max_pending_seconds", 1800), check.get("require_delivery", True))
    if problems:
        raise CheckFailed(problems[0][0], " · ".join(detail for _, detail in problems))


def last_schedule(now):
    local = dt.datetime.fromtimestamp(now, KST)
    scheduled = local.replace(hour=DAILY_HOUR, minute=0, second=0, microsecond=0)
    if local < scheduled:
        scheduled -= dt.timedelta(days=1)
    return scheduled.timestamp()


def daily_problem(now, timer_state, last_trigger, service_result, last_sent):
    scheduled = last_schedule(now)
    if timer_state != "active":
        return "daily_timer_inactive", "일일 보고 timer 상태 " + timer_state
    # 예약 직후 유예 시간 안에는 전날 예약을 기준으로 판정해 09:00 정각의 오탐을 막는다.
    due = scheduled if now - scheduled >= DAILY_TRIGGER_GRACE else scheduled - 86400
    if last_trigger is None or last_trigger < due:
        return "daily_not_triggered", kst(due) + " 예약 실행 기록 없음"
    if service_result != "success":
        return "daily_enqueue_failed", "일일 보고 등록 서비스 결과 " + service_result
    due = scheduled if now - scheduled >= DAILY_SEND_GRACE else scheduled - 86400
    if last_sent <= 0:
        return "daily_never_sent", "보고 서비스에 전송 완료된 일일 보고 없음"
    if last_sent < due:
        return "daily_not_sent", kst(due) + " 이후 일일 보고 전송 기록 없음"
    return None


def check_daily_report(check, env):
    timer = env.systemctl(check["timer"], ("ActiveState", "LastTriggerUSec"), env.timeout)
    service = env.systemctl(check["service"], ("Result",), env.timeout)
    last_sent = metric(report_metrics(check, env), "report_last_daily_sent_timestamp_seconds")
    problem = daily_problem(env.now, timer["ActiveState"], parse_systemd_time(timer["LastTriggerUSec"]),
                            service["Result"], last_sent)
    if problem:
        raise CheckFailed(*problem)


def check_systemd_active(check, env):
    unit = env.systemctl(check["unit"], ("ActiveState", "SubState", "Result"), env.timeout)
    if unit["ActiveState"] == "active":
        return
    detail = "%s %s/%s · 결과 %s" % (check["unit"], unit["ActiveState"], unit["SubState"], unit["Result"])
    if check.get("context_unit"):
        context = env.systemctl(check["context_unit"], ("Result",), env.timeout)
        detail += " · %s 결과 %s" % (check["context_unit"], context["Result"])
    raise CheckFailed("unit_" + unit["ActiveState"], detail)


def check_https_site(check, env):
    status, _, cert = env.fetch(check["url"], env.timeout, None, True)
    if status != check.get("expect_status", 200):
        raise CheckFailed("http_" + str(status))
    days = (ssl.cert_time_to_seconds(cert["notAfter"]) - env.now) / 86400
    if days < check.get("min_cert_days", 14):
        raise CheckFailed("certificate_expiring", "인증서 만료까지 %d일" % days)


CHECKS = {"http": check_http, "alertmanager_watchdog": check_alertmanager_watchdog,
          "report_queue": check_report_queue, "daily_report": check_daily_report,
          "systemd_active": check_systemd_active, "https_site": check_https_site}


def run_check(check, env):
    result = {"name": check["name"], "target": check["target"], "label": check["label"],
              "ok": True, "error": None, "detail": ""}
    try:
        CHECKS[check["type"]](check, env)
    except CheckFailed as failure:
        result.update(ok=False, error=failure.code, detail=failure.detail)
    return result


def apply_results(state, results, now, config):
    events = []
    checks = state["checks"]
    for result in results:
        entry = checks.setdefault(result["name"], {"alerting": False, "failures": 0, "successes": 0})
        entry.update(target=result["target"], label=result["label"])
        if result["ok"]:
            entry.update(failures=0, successes=entry["successes"] + 1, error=None)
            if entry["alerting"] and entry["successes"] >= config["recover_after"]:
                events.append(event("recover", result, entry["since"], now))
                entry.update(alerting=False, since=None, reminded=None)
            continue
        if entry["failures"] == 0:
            entry["first_failed"] = now
        entry.update(failures=entry["failures"] + 1, successes=0, error=result["error"], detail=result["detail"])
        if not entry["alerting"] and entry["failures"] >= config["fail_after"]:
            entry.update(alerting=True, since=entry["first_failed"], reminded=now)
            events.append(event("fire", result, entry["since"], now))
        elif entry["alerting"] and now - entry["reminded"] >= config["remind_seconds"]:
            entry["reminded"] = now
            events.append(event("remind", result, entry["since"], now))
    for name in set(checks) - {result["name"] for result in results}:
        del checks[name]
    return events


def event(kind, result, since, now):
    return {"kind": kind, "check": result["name"], "target": result["target"], "label": result["label"],
            "error": result["error"], "detail": result["detail"], "since": since, "at": now}


def target_hints(state, events):
    hints = []
    for target in sorted({item["target"] for item in events if item["kind"] != "recover"}):
        entries = [entry for entry in state["checks"].values() if entry["target"] == target]
        if entries and all(entry["alerting"] and entry["error"] in NO_RESPONSE for entry in entries):
            hints.append(target + "의 모든 점검이 응답하지 않음 · EC2 상태 검사·SG·네트워크 경로 확인")
    return hints


def enqueue(state, events, now):
    outbox = state["outbox"]
    kept = []
    for item in events:
        pending = [message for message in outbox if any(entry["check"] == item["check"] for entry in message["events"])]
        if item["kind"] == "remind" and pending:
            continue
        if item["kind"] == "recover":
            # 전달되지 않은 이상 알림은 회복 알림 하나로 합친다. 전달됐을 수 있는 메시지는 그대로 둔다.
            for message in pending:
                if message["uncertain"] == 0:
                    removed = [entry for entry in message["events"] if entry["check"] == item["check"]]
                    message["events"] = [entry for entry in message["events"] if entry["check"] != item["check"]]
                    if any(entry["kind"] != "recover" for entry in removed):
                        item["unannounced"] = True
        kept.append(item)
    outbox[:] = [message for message in outbox if message["events"]]
    if kept:
        digest = hashlib.sha256(json.dumps([state.get("watcher"), now, kept], sort_keys=True).encode()).hexdigest()
        outbox.append({"id": digest[:16], "created": now, "events": kept, "hints": target_hints(state, kept),
                       "tries": 0, "uncertain": 0})
    del outbox[:-MAX_OUTBOX]


def classify(status):
    if 200 <= status < 300:
        return "sent", None
    if status in (408, 429, 503):
        return "retry", "http_" + str(status)
    if status >= 500:
        return "uncertain", "http_" + str(status)
    return "rejected", "http_" + str(status)


def post_card(url, payload, timeout, connection=http.client.HTTPSConnection):
    parts = urlsplit(url)
    data = json.dumps(payload, ensure_ascii=False).encode()
    conn = connection(parts.hostname, 443, timeout=timeout, context=ssl.create_default_context())
    try:
        try:
            conn.connect()
        except (OSError, http.client.HTTPException):
            return "retry", "connect_failed"
        # 요청을 보낸 뒤의 실패는 Teams가 이미 접수했을 수 있어 재전송 한도를 적용한다.
        try:
            conn.request("POST", parts.path + ("?" + parts.query if parts.query else ""), body=data,
                         headers={"Content-Type": "application/json"})
            response = conn.getresponse()
            response.read(4096)
        except (OSError, http.client.HTTPException):
            return "uncertain", "no_response"
    finally:
        conn.close()
    return classify(response.status)


def deliver(state, config, send, persist, now):
    outcomes = []
    outbox = state["outbox"]
    for message in list(outbox):
        # 재전송 중 종료되면 load_state가 불확실 횟수를 올린다. 이미 두 번 불확실했거나 시도 한도에 이른
        # 알림은 세 번째로 보내지 않고 버린다. 전송하지 않았으므로 다음 알림은 이어서 보낸다.
        if message["uncertain"] >= 2 or message["tries"] >= MAX_TRIES:
            limit = "uncertain_limit" if message["uncertain"] >= 2 else "try_limit"
            outbox.remove(message)
            outcomes.append({"id": message["id"], "outcome": "abandoned", "code": limit, "dropped": True})
            state["delivery"] = {"at": now, "outcome": "abandoned", "code": limit}
            continue
        resend = message["uncertain"] > 0
        message["tries"] += 1
        # 전송 중 종료되면 다음 실행이 이 표시를 불확실 전송 한 번으로 센다.
        message["inflight"] = True
        persist(state)
        outcome, code = send(build_card(message, config, resend))
        del message["inflight"]
        if outcome == "uncertain":
            message["uncertain"] += 1
        dropped = outcome in ("sent", "rejected") or message["uncertain"] >= 2 or message["tries"] >= MAX_TRIES
        if dropped:
            outbox.remove(message)
        outcomes.append({"id": message["id"], "outcome": outcome, "code": code, "dropped": dropped})
        state["delivery"] = {"at": now, "outcome": outcome, "code": code}
        if outcome != "sent":
            break
    return outcomes


def kst(timestamp):
    return dt.datetime.fromtimestamp(timestamp, KST).strftime("%m-%d %H:%M KST")


def duration(seconds):
    minutes = int(seconds // 60)
    return "%d분" % minutes if minutes < 120 else "%d시간 %d분" % (minutes // 60, minutes % 60)


def build_card(message, config, resend=False):
    kinds = {item["kind"] for item in message["events"]}
    if not kinds:
        title, color = "관측 감시 연결 시험 · 운영 상태를 판정하지 않음", "Accent"
    elif kinds == {"recover"}:
        title, color = "관측 경로 회복", "Good"
    elif "recover" in kinds:
        title, color = "관측 경로 상태 변경", "Warning"
    elif "fire" in kinds:
        title, color = "관측 경로 이상 감지", "Attention"
    else:
        title, color = "관측 경로 이상 지속", "Attention"

    def text(value, **options):
        return {"type": "TextBlock", "text": value, "wrap": True, **options}

    body = [text("페일스톱 관측 감시 · %s / %s · 감시 위치 %s" % (config["customer"], config["environment"].upper(),
                                                          config["watcher"]), size="Small", isSubtle=True),
            text(title, size="Large", weight="Bolder", color=color)]
    for item in message["events"]:
        name = "%s (%s)" % (item["label"], item["target"])
        if item["kind"] == "recover":
            line = "회복 · %s · %s부터 %s 동안 이상" % (name, kst(item["since"]), duration(item["at"] - item["since"]))
            if item.get("unannounced"):
                line += " · 이상 알림은 전달되지 않았음"
            body.append(text(line, color="Good", weight="Bolder"))
            continue
        state = "이상" if item["kind"] == "fire" else "지속"
        line = "%s · %s · %s부터 · %s" % (state, name, kst(item["since"]), item["error"])
        if item["kind"] == "remind":
            line += " · %s 경과" % duration(item["at"] - item["since"])
        body.append(text(line, color="Attention", weight="Bolder"))
        if item["detail"]:
            body.append(text(item["detail"], size="Small"))
    for hint in message["hints"]:
        body.append(text(hint, size="Small", weight="Bolder"))
    footer = "알림 %s · Alertmanager와 별도 경로 · 자동 조치 없음" % message["id"][:12]
    if resend:
        footer += " · 응답이 불확실해 한 번 다시 보냄, 같은 알림 번호는 같은 사건"
    body.append(text(footer, size="Small", isSubtle=True, separator=True))
    return {"type": "message", "attachments": [{
        "contentType": "application/vnd.microsoft.card.adaptive", "contentUrl": None,
        "content": {"$schema": "http://adaptivecards.io/schemas/adaptive-card.json", "type": "AdaptiveCard",
                    "version": "1.2", "body": body}}]}


def write_atomic(path, text, mode):
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(dir=str(path.parent), prefix="." + path.name + ".")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, str(path))
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise
    # 교체한 이름까지 디스크에 남겨야 전원 장애 뒤에도 이전 또는 새 상태 중 하나가 온전하다.
    if os.name == "posix":
        directory = os.open(str(path.parent), os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)


def load_state(path):
    try:
        state = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(state, dict) or state.get("schema_version") != 1:
            raise ValueError("schema")
    except FileNotFoundError:
        state = {"schema_version": 1}
    except ValueError:
        log({"event": "state_reset", "reason": "unreadable_state"})
        state = {"schema_version": 1}
    state.setdefault("checks", {})
    state.setdefault("outbox", [])
    for message in state["outbox"]:
        if message.pop("inflight", False):
            message["uncertain"] += 1
    return state


def render_textfile(state, results, now):
    lines = ["# HELP failstop_watchdog_last_run_timestamp_seconds Unix time of the last completed watchdog run.",
             "# TYPE failstop_watchdog_last_run_timestamp_seconds gauge",
             "failstop_watchdog_last_run_timestamp_seconds %d" % now,
             "# HELP failstop_watchdog_check_ok Whether the latest check result succeeded.",
             "# TYPE failstop_watchdog_check_ok gauge"]
    lines += ['failstop_watchdog_check_ok{check="%s",target="%s"} %d' % (item["name"], item["target"], item["ok"])
              for item in results]
    lines += ["# HELP failstop_watchdog_check_alerting Whether the check passed the failure threshold.",
              "# TYPE failstop_watchdog_check_alerting gauge"]
    lines += ['failstop_watchdog_check_alerting{check="%s",target="%s"} %d'
              % (item["name"], item["target"], state["checks"][item["name"]]["alerting"]) for item in results]
    lines += ["# HELP failstop_watchdog_outbox_messages Notifications waiting for Teams delivery.",
              "# TYPE failstop_watchdog_outbox_messages gauge",
              "failstop_watchdog_outbox_messages %d" % len(state["outbox"])]
    return "\n".join(lines) + "\n"


def log(record):
    print(json.dumps(record, ensure_ascii=False, sort_keys=True), flush=True)


def probe_env(config, now):
    return SimpleNamespace(now=now, timeout=config["timeout_seconds"], fetch=fetch, systemctl=systemctl_show,
                           secret=read_secret)


def run_once(config, state_path, now, env, sender):
    """점검·상태 전이·알림 전송을 한 번 수행한다. 반환값은 systemd에 넘길 종료 코드다.

    sender()는 전송 함수를 돌려주며 웹훅 비밀에 문제가 있으면 WatchdogError를 낸다.
    """
    enabled = [check for check in config["checks"] if check["enabled"]]
    results = [run_check(check, env) for check in enabled]
    state = load_state(state_path)
    state["watcher"] = config["watcher"]
    events = apply_results(state, results, now, config)
    enqueue(state, events, now)
    state["last_run"] = now

    def persist(current):
        write_atomic(state_path, json.dumps(current, ensure_ascii=False, sort_keys=True) + "\n", 0o600)

    persist(state)
    if config["textfile"]:
        folder = Path(config["textfile"]).parent
        folder.mkdir(parents=True, exist_ok=True)
        # node_exporter 계정이 읽을 수 있어야 하므로 서비스의 UMask와 무관하게 권한을 정한다.
        folder.chmod(0o755)
        write_atomic(config["textfile"], render_textfile(state, results, now), 0o644)
    outcomes = []
    code = 0
    # 알릴 일이 없어도 매번 비밀을 확인해 사고 순간에야 알림 경로 고장을 발견하는 일을 막는다.
    try:
        send = sender()
    except WatchdogError as error:
        log({"event": "delivery_unavailable", "error": str(error), "pending": len(state["outbox"])})
        send, code = None, 1
    if send and state["outbox"]:
        outcomes = deliver(state, config, send, persist, now)
        persist(state)
    if events or outcomes:
        log({"event": "run", "changes": [[item["kind"], item["check"], item["error"]] for item in events],
             "deliveries": outcomes, "pending": len(state["outbox"])})
    if any(item["outcome"] == "rejected" or (item["dropped"] and item["outcome"] != "sent") for item in outcomes):
        code = 2
    return code


def main(argv=None):
    parser = argparse.ArgumentParser(description="관측 경로 워치독")
    parser.add_argument("command", choices=("run", "check", "status", "test-send"))
    parser.add_argument("--config", default="/etc/failstop-watchdog/config.json")
    parser.add_argument("--state", default="/var/lib/failstop-watchdog/state.json")
    args = parser.parse_args(argv)
    try:
        config = load_config(args.config)
        now = time.time()
        if args.command == "status":
            state = load_state(args.state)
            print(json.dumps({"last_run": state.get("last_run"), "checks": state["checks"],
                              "outbox": len(state["outbox"]), "delivery": state.get("delivery")},
                             ensure_ascii=False, indent=2, sort_keys=True))
            return 0
        if args.command == "check":
            results = [run_check(check, probe_env(config, now)) for check in config["checks"] if check["enabled"]]
            for item in results:
                log(item)
            return 0 if all(item["ok"] for item in results) else 3

        def sender():
            url = webhook_url(config["teams_secret_file"])
            return lambda payload: post_card(url, payload, 10)

        if args.command == "test-send":
            message = {"id": "test" + hashlib.sha256(str(now).encode()).hexdigest()[:12], "hints": [], "events": []}
            outcome, code = sender()(build_card(message, config))
            log({"event": "test_send", "outcome": outcome, "code": code})
            return 0 if outcome == "sent" else 1
        return run_once(config, args.state, now, probe_env(config, now), sender)
    except WatchdogError as error:
        log({"event": "configuration_error", "error": str(error)})
        return 1
    except OSError as error:
        # 상태·textfile 경로의 권한이나 디스크 문제는 경로와 errno만 남긴다.
        log({"event": "runtime_error", "error": type(error).__name__, "errno": error.errno,
             "path": getattr(error, "filename", None)})
        return 1


if __name__ == "__main__":
    sys.exit(main())
