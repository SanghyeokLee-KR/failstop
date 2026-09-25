"""관제센터 AI 사건을 같은 보고 카드로 옮긴다. 관리 JVM 이 서버가 만든 값만 넘기고 모델의 자유 문장은 받지 않는다."""
import datetime as dt
import re
import urllib.parse
import uuid

from intervention import LABELS, RANK
from presentation import _kst, _markdown, report_label
from transport import redact

KINDS = {"AUTO_EXECUTED", "AUTO_REVERTED", "MODE_CHANGED", "CAP_REACHED", "OBSERVE_FAILED", "EXECUTION_UNKNOWN",
         "MODEL_FAILING", "STOPPED", "RESUMED"}
TOP_KEYS = {"schema_version", "event_id", "kind", "occurred_at", "scope", "action", "mode", "model", "metrics",
            "unconfirmed", "counts"}
ACTION_KEYS = {"id", "kind", "verb", "target", "from", "to", "status", "confidence", "observe_until", "observe_outcome"}
MODE_KEYS = {"mode", "cause", "actor", "valid_until"}
ACTION_KINDS = {"READ_NODES", "THRESHOLD", "SILENCE", "BOT_LOAD", "BACKUP_NOW"}
STATUSES = {"PROPOSED", "DEFERRED", "EXECUTING", "APPROVED", "AUTO_EXECUTED", "FAILED", "UNKNOWN", "REJECTED",
            "DENIED", "WITHDRAWN", "EXPIRED", "REVERTED", "AUTO_REVERTED"}
VERBS = {"READ_NODE_ADD_ONE": "조회 노드 1대 증설", "READ_NODE_REMOVE_ONE": "조회 노드 1대 축소",
         "THRESHOLD_RAISE": "임계값 올림", "THRESHOLD_LOWER": "임계값 내림", "ALERT_SILENCE": "경보 보류",
         "BOT_LOAD_REDUCE": "시장 봇 부하 줄임", "BACKUP_RUN_NOW": "DB 백업 즉시 실행"}
OUTCOMES = {"KEPT": "유지", "REVERTED": "되돌림", "UNOBSERVABLE": "관찰 불가", "REVERT_BLOCKED": "되돌림 불가",
            "NOT_REVERTIBLE": "자동 되돌림 대상 아님"}
CAUSES = {"operator": "운영자 전환", "expired": "자동 모드 유효 시간 만료", "auto_cap": "자동 조치 상한 도달",
          "auto_cap_lowered": "자동 조치 상한 하향", "degraded": "관찰 창 악화", "unobservable": "관찰 불가",
          "revert_blocked": "되돌림 불가", "not_revertible": "자동 되돌림 대상 아님", "startup_llm": "기동 정리",
          "startup_executing": "기동 때 끝나지 않은 실행",
          "stopped": "AI 정지", "resumed": "AI 재개", "observe_failed": "관찰 실패"}
METRICS = {"read_avg_cpu": "조회 평균 CPU", "error_ratio": "5xx 비율", "p95_seconds": "응답 p95",
           "availability": "외부 가용성"}
COUNTS = {"expired_actions", "consecutive_failures"}
SCOPE = re.compile(r"[a-zA-Z0-9_.-]{1,80}")
TARGET = re.compile(r"[A-Za-z0-9_.:-]{0,64}")
ACTOR = re.compile(r"[A-Za-z0-9_.:@-]{1,64}")
MODEL = re.compile(r"[\w.:/-]{1,128}")
DECIMAL = re.compile(r"-?\d{1,15}(\.\d{1,15})?")
TIME = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})")
AUTOMATIC = {"AUTO_EXECUTED", "AUTO_REVERTED"}
# 이 사건 자체가 한 자동 조치. 관찰 실패는 새 조치 없이 수동으로 바꾼 사건이지만 앞선 자동 조치의 상태를 모른다.
AUTOMATION = {"AUTO_EXECUTED": "자동 실행 1건", "AUTO_REVERTED": "자동 되돌림 1건",
              "EXECUTION_UNKNOWN": "조치 결과 확인 필요", "OBSERVE_FAILED": "새 자동 조치 없음 · 앞선 조치 상태 확인 필요"}


def _check(condition, code):
    if not condition:
        raise ValueError(code)


def _time(value, required=True):
    if value is None and not required:
        return None
    _check(isinstance(value, str) and TIME.fullmatch(value) is not None, "invalid_event_time")
    dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    return value


def _choice(value, allowed, code, required=True):
    if value is None and not required:
        return None
    _check(isinstance(value, str) and value in allowed, code)
    return value


def _pattern(value, pattern, code, required=True):
    if value is None and not required:
        return None
    _check(isinstance(value, str) and pattern.fullmatch(value) is not None, code)
    return value


def _action(action):
    if action is None:
        return None
    _check(isinstance(action, dict) and set(action) == ACTION_KEYS, "invalid_event_action")
    _check(type(action["id"]) is int and action["id"] > 0, "invalid_event_action")
    return {"id": action["id"], "kind": _choice(action["kind"], ACTION_KINDS, "invalid_event_action"),
            "verb": _choice(action["verb"], VERBS, "invalid_event_action"),
            "target": _pattern(action["target"], TARGET, "invalid_event_action"),
            "from": _pattern(action["from"], DECIMAL, "invalid_event_action", False),
            "to": _pattern(action["to"], DECIMAL, "invalid_event_action", False),
            "status": _choice(action["status"], STATUSES, "invalid_event_action"),
            "confidence": _pattern(action["confidence"], DECIMAL, "invalid_event_action", False),
            "observe_until": _time(action["observe_until"], False),
            "observe_outcome": _choice(action["observe_outcome"], OUTCOMES, "invalid_event_action", False)}


def _mode(mode):
    if mode is None:
        return None
    _check(isinstance(mode, dict) and set(mode) == MODE_KEYS, "invalid_event_mode")
    return {"mode": _choice(mode["mode"], {"AUTO", "MANUAL"}, "invalid_event_mode"),
            "cause": _choice(mode["cause"], CAUSES, "invalid_event_mode", False),
            "actor": _pattern(mode["actor"], ACTOR, "invalid_event_mode", False),
            "valid_until": _time(mode["valid_until"], False)}


def _metrics(metrics):
    _check(isinstance(metrics, list) and len(metrics) <= 6, "invalid_event_metrics")
    result = []
    for metric in metrics:
        _check(isinstance(metric, dict) and set(metric) == {"name", "value", "threshold"}, "invalid_event_metrics")
        result.append({"name": _choice(metric["name"], METRICS, "invalid_event_metrics"),
                       "value": _pattern(metric["value"], DECIMAL, "invalid_event_metrics"),
                       "threshold": _pattern(metric["threshold"], DECIMAL, "invalid_event_metrics", False)})
    return result


def event_payload(body):
    """관리 JVM 이 넘긴 사건 하나를 검사한다. 모르는 필드나 값이 있으면 거부한다. 같은 사건 id 는 같은 작업이 된다."""
    _check(isinstance(body, dict) and set(body) == TOP_KEYS and body["schema_version"] == 1, "invalid_event")
    event_id = str(uuid.UUID(_pattern(body["event_id"], re.compile(r"[0-9a-f-]{36}"), "invalid_event")))
    scope = body["scope"]
    _check(isinstance(scope, dict) and set(scope) == {"customer", "environment"}, "invalid_event_scope")
    scope = {key: _pattern(scope[key], SCOPE, "invalid_event_scope") for key in ("customer", "environment")}
    counts = body["counts"]
    _check(isinstance(counts, dict) and set(counts) <= COUNTS
           and all(type(v) is int and 0 <= v <= 100000 for v in counts.values()), "invalid_event_counts")
    _check(type(body["unconfirmed"]) is int and 0 <= body["unconfirmed"] <= 1000, "invalid_event")
    event = {"event_id": event_id, "kind": _choice(body["kind"], KINDS, "invalid_event_kind"),
             "occurred_at": _time(body["occurred_at"]), "scope": scope, "action": _action(body["action"]),
             "mode": _mode(body["mode"]), "model": _pattern(body["model"], MODEL, "invalid_event", False),
             "metrics": _metrics(body["metrics"]), "unconfirmed": body["unconfirmed"], "counts": dict(counts)}
    return "msp:" + event_id, {"event": redact(event), "scope": scope}


def report(identity, payload):
    """보관할 보고 한 벌. 근거 수집이나 AI 분석 없이 넘겨받은 사건만 담는다. 범위는 보고 목록의 고객 경계다."""
    event = payload["event"]
    return {"id": identity, "kind": "msp", "event": event,
            "evidence": {"schema_version": 1, "collected_at": event["occurred_at"], "scope": event["scope"], "facts": []}}


def _code(event):
    kind, mode = event["kind"], event["mode"] or {}
    if kind in ("OBSERVE_FAILED", "EXECUTION_UNKNOWN"):
        return "immediate"
    if kind == "AUTO_REVERTED":
        return "scheduled"
    if kind == "RESUMED" or (kind == "MODE_CHANGED" and (mode.get("mode") == "AUTO"
                                                        or mode.get("cause") in ("operator", "expired"))):
        return "monitor"
    return "verify"


def _percent(value):
    return f"{float(value) * 100:.1f}%"


def _metric_value(name, value):
    return f"{float(value) * 1000:,.0f} ms" if name == "p95_seconds" else _percent(value)


def _target(action):
    target = action["target"]
    return {"app": "조회 계층", "bot-fleet": "시장 봇", "databasus": "DB 백업"}.get(
        target, ("경보 " + target) if action["kind"] == "SILENCE" else target)


def _clock(value):
    stamp = _kst(value)
    return stamp.split(" ", 1)[1] if stamp.endswith(" KST") else stamp


def brief(saved):
    """카드와 보고 목록이 함께 쓰는 표시 모델."""
    event = saved["event"]
    kind, action, mode = event["kind"], event["action"], event["mode"] or {}
    verb = VERBS.get(action["verb"], "AI 조치") if action else None
    change = f"({action['from']}→{action['to']})" if action and action["from"] and action["to"] else ""
    counts = event["counts"]
    code = _code(event)
    title = {"AUTO_EXECUTED": f"AI 자동 조치 실행 · {verb}",
             "AUTO_REVERTED": f"AI 자동 조치 되돌림 · {verb}",
             "OBSERVE_FAILED": "AI 조치 관찰 실패 · " + OUTCOMES.get((action or {}).get("observe_outcome"),
                                                                 CAUSES.get(mode.get("cause"), "관찰 실패")),
             "EXECUTION_UNKNOWN": f"AI 조치 결과 확인 필요 · {verb}",
             "CAP_REACHED": "AI 자동 조치 상한 도달 · 수동 전환",
             "MODE_CHANGED": "AI 자동 모드 켜짐" if mode.get("mode") == "AUTO"
             else "AI 수동 전환 · " + CAUSES.get(mode.get("cause"), "시스템 전환"),
             "MODEL_FAILING": "AI 판단 모델 응답 실패 연속",
             "STOPPED": "AI 정지",
             "RESUMED": "AI 재개 · 수동 모드"}[kind]
    observe = f" · 관찰 창 {_clock(action['observe_until'])}까지" if action and action["observe_until"] else ""
    impact = {"AUTO_EXECUTED": f"{verb}{change}{observe} · 악화하면 되돌리거나 수동 전환",
              "AUTO_REVERTED": f"{verb}{change} 자동 되돌림 · 수동 모드로 전환",
              "OBSERVE_FAILED": "자동 조치의 관찰 창을 끝까지 보지 못해 수동 모드로 전환 · 조치 상태 직접 확인 필요",
              "EXECUTION_UNKNOWN": "실행 결과를 원천에서 대조하지 못함 · 확인 전까지 자동 켜기 차단",
              "CAP_REACHED": "이번 자동 모드 활성의 자동 조치 상한에 닿아 수동 모드로 전환",
              "MODE_CHANGED": ("허용 목록 조치를 자동 실행 · " + _kst(mode.get("valid_until")) + "까지")
              if mode.get("mode") == "AUTO" else "AI 자동 실행 중지 · 조치안은 운영자 승인으로만 실행",
              "MODEL_FAILING": f"모델 응답 실패 {counts.get('consecutive_failures', 0)}회 연속 · 조치 없음, "
                               "기존 경보와 ASG 정책은 계속",
              "STOPPED": f"AI 판단·조치 정지 · 대기 조치안 {counts.get('expired_actions', 0)}건 만료",
              "RESUMED": "AI 판단 재개 · 수동 모드(자동은 다시 승인 필요)"}[kind]
    task = {"AUTO_EXECUTED": ("관찰 창 안에 확인 권고", "관제센터에서 조치 근거와 관찰 결과를 확인하고 필요하면 되돌리기",
                              "관찰 창 결과(유지·되돌림) 확인"),
            "AUTO_REVERTED": ("금일 점검 권고", "악화 원인과 되돌림 결과를 확인하고 자동 모드를 다시 켤지 판단",
                              "원인 기록과 모드 결정"),
            "OBSERVE_FAILED": ("즉시 확인 권고", "조치 대상의 현재 상태를 직접 확인하고 필요하면 관제센터에서 되돌리기",
                               "대상 상태 확인과 되돌림 여부 기록"),
            "EXECUTION_UNKNOWN": ("즉시 확인 권고", "원천(Alertmanager 사일런스, 용량 계획)에서 조치 결과 확인",
                                  "조치 요청이 결과 모름에서 닫힘"),
            "CAP_REACHED": ("금일 확인 권고", "이번 활성의 자동 조치 기록 검토", "자동 모드 재개 여부 결정"),
            "MODEL_FAILING": ("금일 확인 권고", "AI 서버 상태(적재 모델·응답 지연)와 연결 확인", "모델 응답 회복"),
            "STOPPED": ("금일 확인 권고", "정지 사유를 확인하고 재개 여부 판단(재개는 SUPER)", "재개 또는 정지 유지 결정"),
            "RESUMED": ("다음 정기 확인", "재개 뒤 첫 판단과 조치안 확인", "다음 판단 확인")}.get(kind)
    if kind == "MODE_CHANGED":
        task = (("다음 정기 확인", "모드 전환 사유와 유효 시간 확인", "전환 기록 확인") if code == "monitor"
                else ("금일 확인 권고", "전환 원인과 최근 AI 조치 확인", "원인 기록과 모드 결정"))
    metrics = [{"label": METRICS[m["name"]], "value": _metric_value(m["name"], m["value"])
                + (f" (임계 {_metric_value(m['name'], m['threshold'])})" if m["threshold"] else "")}
               for m in event["metrics"]]
    confidence = f" · 확신도 {float(action['confidence']) * 100:.0f}%" if action and action["confidence"] else ""
    ai_note = f"AI 판단 · 모델 {event['model']}{confidence}" if event["model"] else ""
    return {"title": title, "code": code, "priority": "P" + str(RANK[code]), "label": LABELS[code],
            "target": _target(action) if action else "AI 모드", "occurred_at": _kst(event["occurred_at"]),
            "impact": impact, "action": {"owner": "관제 담당", "timing": task[0], "task": task[1], "done_when": task[2]},
            "metrics": metrics, "ai_note": ai_note, "unconfirmed": event["unconfirmed"],
            "automatic": kind in AUTOMATIC, "automation": AUTOMATION.get(kind, "자동 조치 없음"),
            "report_id": saved["id"], "report_label": report_label(saved["id"]),
            "customer": event["scope"]["customer"],
            "environment": {"local-lab": "로컬 실습", "aws": "AWS"}.get(event["scope"]["environment"],
                                                                     event["scope"]["environment"])}


def console_url(config):
    """카드의 관제센터 버튼 주소. 관리 JVM 이 보낸 값이 아니라 이 서비스 설정에서만 만든다."""
    url = config.get("msp_console_url")
    if url is None:
        return None
    parsed = urllib.parse.urlsplit(url) if isinstance(url, str) else None
    if (parsed is None or parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or len(url) > 300):
        raise ValueError("invalid_msp_console_url")
    return url


def card(saved, config):
    """일일·사건 보고와 같은 카드 언어. 서버가 만든 값만 싣고 모델의 문장은 없다."""
    b = brief(saved)

    def text(value, **options):
        return {"type": "TextBlock", "text": _markdown(value), "wrap": True, **options}

    icon = {"immediate": "\U0001f534", "scheduled": "\U0001f7e0", "verify": "\U0001f7e0"}.get(b["code"], "\U0001f4cb")
    color = {"immediate": "Attention", "scheduled": "Warning", "verify": "Warning", "monitor": "Good"}[b["code"]]
    action = b["action"]
    body = [text("페일스톱 운영 관제 · " + b["customer"] + " / " + b["environment"], size="Small", isSubtle=True),
            text(icon + " " + b["title"], size="Large", weight="Bolder", color=color),
            text(b["priority"] + " · " + b["label"], weight="Bolder"),
            {"type": "FactSet", "facts": [{"title": "대상", "value": _markdown(b["target"])},
                                          {"title": "발생", "value": _markdown(b["occurred_at"])},
                                          {"title": "영향", "value": _markdown(b["impact"])}]},
            text("담당자 조치", weight="Bolder", separator=True),
            text(f"{b['priority']} · {action['owner']} · {action['timing']}", weight="Bolder", size="Small"),
            text(action["task"], size="Small"),
            text("완료: " + action["done_when"], size="Small", isSubtle=True)]
    if b["metrics"]:
        body += [text("후속 관측 · " + b["occurred_at"], weight="Bolder", size="Small", separator=True),
                 {"type": "FactSet", "facts": [{"title": _markdown(m["label"]), "value": _markdown(m["value"])}
                                               for m in b["metrics"]]}]
    if b["ai_note"]:
        body += [text("참고 분석 · AI", weight="Bolder", separator=True), text(b["ai_note"], size="Small")]
    if b["unconfirmed"]:
        body.append(text(f"미확인 자료 {b['unconfirmed']}항목 · 판단 근거 가운데 미수집이거나 60초보다 오래된 항목",
                         size="Small", isSubtle=True))
    body += [text("서버 정책 엔진이 허용 목록·한도를 다시 검사한 기록 · AI 문장은 싣지 않음", size="Small",
                  isSubtle=True, separator=True),
             text(b["report_label"] + " · " + b["automation"], size="Small", isSubtle=True)]
    content = {"$schema": "http://adaptivecards.io/schemas/adaptive-card.json", "type": "AdaptiveCard",
               "version": "1.2", "body": body}
    url = console_url(config)
    if url:
        content["actions"] = [{"type": "Action.OpenUrl", "title": "관제센터에서 확인", "url": url}]
    return {"type": "message", "attachments": [{"contentType": "application/vnd.microsoft.card.adaptive",
                                                "contentUrl": None, "content": content}]}
