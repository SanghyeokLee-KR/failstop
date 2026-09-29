"""Render operational evidence for people without turning collection success into health."""
import datetime as dt
import html
import json
import math
import re

from charts import build_charts, render_svg
from intervention import ALERT_NAMES, DISK_FREE, INODE_FREE, assess, database_alert_name, database_role

KST = dt.timezone(dt.timedelta(hours=9))
FACT_NAMES = {
    "target_up": "지표 수집 응답", "disk_available": "디스크 여유", "inode_free": "inode 여유",
    "io_time": "디스크 I/O 사용", "network_errors": "수신 네트워크 오류", "services_failed": "실패 서비스",
    "db_connections": "DB 연결", "db_replication_lag": "WAL 재생 거리", "request_rate": "서비스 요청",
    "server_errors": "5xx 응답", "latency_p95": "응답 시간 P95", "backup_success": "최근 백업 성공",
    "alerts": "현재 경보", "host": "호스트 운영 기록", "journal": "상세 로그", "aws_activity": "AWS 변경 기록",
    "trigger": "이번 알림 전환",
    "backup_schedule": "백업 예약 확인", "backup_restore": "백업 복원 확인",
}
# 보고 id 는 '종류:식별' 형식의 보관함 키다(reporter.daily_key, incident_payload, msp_events.event_payload).
REPORT_KINDS = {"daily": "일일 보고", "incident": "경보 보고", "msp": "관제 사건", "preview": "미리보기", "test": "연결 시험"}
AUTOMATION_UNREADABLE = "자동 조치 기록 확인 불가"
# AI 문장 속 시각. 날짜(2026-09-28, 9월 28일)와 초, 시간대(KST·UTC·Z·+09:00)는 붙지 않을 수 있다.
TIME_MENTION = re.compile(
    r"(?<![\d:.])(?:(?P<date>\d{4}-\d{2}-\d{2})[T ]?|(?:(?P<year>\d{4})년\s*)?(?P<month>\d{1,2})월\s*(?P<day>\d{1,2})일\s*)?"
    r"(?:(?P<hour>[01]?\d|2[0-3]):(?P<minute>[0-5]\d)(?::[0-5]\d(?:\.\d+)?)?(?![\d:])"
    r"|(?:(?P<half>오전|오후)\s*)?(?P<ko_hour>[01]?\d|2[0-3])시\s*(?P<ko_minute>[0-5]?\d)분(?:\s*[0-5]?\d초)?)"
    r"(?P<zone>Z|\+\d{2}:\d{2}|\s?(?:\((?:KST|UTC)\)|KST|UTC))?")


def _text(value, limit=160):
    return re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", str(value or ""))[:limit]


def _date(value):
    try:
        if isinstance(value, (float, int)) and math.isfinite(value):
            return dt.datetime.fromtimestamp(value, dt.timezone.utc)
        parsed = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed if parsed.tzinfo and parsed.year > 1 else None
    except (ValueError, TypeError, OverflowError, OSError):
        return None


def _kst(value):
    parsed = _date(value)
    return parsed.astimezone(KST).strftime("%Y-%m-%d %H:%M KST") if parsed else "시각 확인 불가"


def _number(value):
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) else None


def _status(row, observed, trigger):
    status = row.get("status")
    if isinstance(status, str) and status in ("firing", "resolved"):
        return status
    # Alertmanager's active/suppressed state is not a firing/resolved transition.
    ended = _date(row.get("endsAt"))
    return "unknown" if trigger else "resolved" if ended and observed and ended <= observed else "firing"


def _events(fact, observed, trigger=False):
    result = []
    for row in fact.get("records", []) if isinstance(fact.get("records"), list) else []:
        if not isinstance(row, dict):
            continue
        labels = row.get("labels", {})
        status = _status(row, observed, trigger)
        name = _text(labels.get("alertname", "이름 미확인"), 70)
        target_keys = ("mountpoint", "name", "node" if labels.get("node") else "instance")
        target = " · ".join(_text(labels[key], 60) for key in target_keys if labels.get(key))
        result.append({"name": database_alert_name(name, labels) or ALERT_NAMES.get(name, "운영 경보"), "alertname": name,
                       "status": status, "status_label": {"firing": "진행", "resolved": "해제", "unknown": "확인 필요"}[status],
                       "role": database_role(labels) if name.startswith("Database") else _text(labels.get("role"), 40),
                       "node": _text(labels.get("node") or labels.get("instance"), 80),
                       "customer": _text(labels.get("customer"), 60), "environment": _text(labels.get("environment"), 40),
                       "local_lab": labels.get("environment") == "local-lab",
                       "target": target or "대상 정보 미제공", "started_at": _kst(row.get("startsAt")),
                       "ended_at": _kst(row.get("endsAt")) if status == "resolved" else None})
    return result


def _report_times(facts, observed, start, end):
    """경보 발생·해제와 근거 수집, 조회 구간의 시각. AI 문장의 시각은 이 가운데 하나여야 한다."""
    times = [observed, start, end]
    for identifier in ("trigger", "alerts"):
        records = facts.get(identifier, {}).get("records")
        for row in records if isinstance(records, list) else []:
            if isinstance(row, dict):
                times.append(_date(row.get("startsAt")))
                if _status(row, observed, identifier == "trigger") == "resolved":
                    times.append(_date(row.get("endsAt")))
    return [moment for moment in times if moment]


def _time_label(match, times, today):
    """AI 문장의 시각 하나에 맞는 보고 시각을 찾아 KST 표기로 돌려준다. 없거나 둘 이상이면 None이다."""
    part = match.groupdict()
    if part["hour"] is not None:
        hour, minute = int(part["hour"]), int(part["minute"])
    else:
        hour, minute = int(part["ko_hour"]), int(part["ko_minute"])
        if part["half"] == "오후" and hour < 12:
            hour += 12
        elif part["half"] == "오전" and hour == 12:
            hour = 0
    if part["date"]:
        date = tuple(int(value) for value in part["date"].split("-"))
    elif part["month"]:
        date = (int(part["year"]) if part["year"] else None, int(part["month"]), int(part["day"]))
    else:
        date = None
    utc = dt.timezone.utc
    # 모델이 UTC 시계에 KST를 붙이거나 KST 시계에 UTC 날짜를 붙인 표기도 같은 보고 시각으로 찾아 고친다.
    readings = [(KST, KST), (KST, utc), (utc, utc)]
    if (part["zone"] or "").strip(" ()") in ("UTC", "Z", "+00:00"):
        readings = readings[2:] + readings[:2]
    for clock_zone, date_zone in readings:
        found = set()
        for moment in times:
            clock, day = moment.astimezone(clock_zone), moment.astimezone(date_zone)
            if (clock.hour, clock.minute) == (hour, minute) and (
                    date is None or (date[1:] == (day.month, day.day) and date[0] in (None, day.year))):
                found.add(moment.astimezone(KST).replace(second=0, microsecond=0))
        if date is None and len(found) > 1:
            found = {moment for moment in found if moment.date() == today} or found
        if len(found) == 1:
            moment = found.pop()
            return moment.strftime("%Y-%m-%d %H:%M KST" if date or moment.date() != today else "%H:%M KST")
        if found:
            return None
    return None


def _align_times(value, times, today):
    """AI 문장의 시각을 보고 시각의 KST 표기로 고쳐 쓰고, 보고 시각과 맞지 않는 시각이 든 문장은 뺀다."""
    parts = re.split(r"((?<=[.!?])\s+|\s*\n\s*)", _text(value, 800))
    kept = []
    for sentence, gap in zip(parts[::2], parts[1::2] + [""]):
        labels = [_time_label(match, times, today) for match in TIME_MENTION.finditer(sentence)]
        if None not in labels:
            replacement = iter(labels)
            kept.append(TIME_MENTION.sub(lambda match: next(replacement), sentence) + gap)
    return "".join(kept).strip()


def report_label(identifier):
    """보고 id 를 사람이 읽는 이름으로 바꾼다.

    예전 카드는 id 의 뒤 12자를 잘라 'daily:2026-09-24'가 'y:2026-09-24'로 보였다. 앞의 종류를 이름으로 바꾸고
    일일 보고는 날짜 전체를, 해시·uuid 는 앞부분만 싣는다. 전체 id 는 보고 목록과 HTML·PDF 꼬리에 남는다.
    """
    text = _text(identifier, 120)
    prefix, _, rest = text.partition(":")
    name = REPORT_KINDS.get(prefix)
    if not name or not rest:
        return text or "보고 ID 미제공"
    if prefix == "daily":
        return name + " " + rest
    return name + " " + (rest.split("-", 1)[0] if prefix == "msp" else rest[:12])


def automation_label(automation):
    """보고 구간에 관제센터가 자동으로 한 조치 수. 셀 수 없었으면 '없음'이 아니라 확인 불가로 적는다."""
    if not isinstance(automation, dict) or automation.get("state") != "ok":
        return AUTOMATION_UNREADABLE
    counts = [automation.get(field) for field in ("executed", "reverted", "unknown")]
    if any(type(count) is not int or count < 0 for count in counts):
        return AUTOMATION_UNREADABLE
    executed, reverted, unknown = counts
    if not (executed or reverted or unknown):
        return "자동 조치 없음"
    parts = [f"자동 실행 {executed}건", f"자동 되돌림 {reverted}건"]
    if unknown:
        parts.append(f"조치 결과 확인 필요 {unknown}건")
    return " · ".join(parts)


def _metric(fact, observed):
    identifier = fact["id"]
    label = FACT_NAMES.get(identifier, _text(fact.get("description", identifier), 60))
    base = {"label": label, "evidence": identifier}
    samples = [sample for sample in fact.get("samples", []) if _number(sample.get("last")) is not None]
    if fact.get("status") != "ok" or not samples:
        failed = fact.get("status") == "error"
        detail = _text(fact.get("missing_reason"), 160) or "상태 판단 근거 미제공"
        return {**base, "value": "수집 실패" if failed else "자료 없음", "detail": detail, "status": "unknown", "as_of": "표본 없음"}
    values = [sample["last"] for sample in samples]
    if identifier == "db_connections":
        identities = [json.dumps(sample.get("labels", {}), sort_keys=True) for sample in samples]
        if len(identities) != len(set(identities)):
            return {**base, "value": "대상 구분 불충분", "detail": "동일 대상 표본 중복 · 연결 수 합산 보류 · DB명·관측 시각 대조 필요", "status": "unknown", "as_of": "표본 시각 미제공"}
    if identifier == "target_up":
        grouped = {}
        for sample in samples:
            identity = json.dumps(sample.get("labels", {}), sort_keys=True)
            grouped.setdefault(identity, []).append(sample["last"])
        if any(len(set(items)) > 1 for items in grouped.values()):
            return {**base, "value": "대상 상태 대조 필요", "detail": "동일 대상의 상태 표본 불일치 · 현재 응답 수 확정 보류", "status": "unknown", "as_of": "표본 시각 미제공"}
        values = [items[0] for items in grouped.values()]
    if identifier in ("disk_available", "inode_free") and any(not 0 <= value <= 1 for value in values):
        return {**base, "value": "일부 값 해석 불가", "detail": "파일시스템 표본의 0~100% 범위 초과 · 가상 파일시스템 지원 범위 확인 필요", "status": "unknown", "as_of": "표본 시각 미제공"}
    invalid_range = False
    if identifier in ("disk_available", "inode_free"):
        for sample in samples:
            history = [sample[key] for key in ("min", "max") if key in sample]
            points = sample.get("points", [])
            if not isinstance(points, list) or any(not isinstance(point, (list, tuple)) or len(point) != 2 for point in points):
                invalid_range = True
            else:
                history.extend(point[1] for point in points)
            invalid_range |= any(_number(value) is None or not 0 <= value <= 1 for value in history)
    detail, state = "조회 구간의 마지막 표본 기준", "observed"
    if identifier in ("disk_available", "inode_free"):
        sample = min(samples, key=lambda item: item["last"])
        value = f"{sample['last'] * 100:.2f}% 여유"
        target = sample.get("labels", {}).get("mountpoint")
        minimums = [v for item in samples if (v := _number(item.get("min"))) is not None]
        detail = (_text(target, 70) + " · " if target else "") + f"구간 마지막 값 중 최소 / {len(samples)}개 수집 표본"
        if minimums and not invalid_range:
            detail += f" · 수집 구간 최저 {min(minimums) * 100:.2f}%"
        state = "attention" if sample["last"] < (DISK_FREE if identifier == "disk_available" else INODE_FREE) else "observed"
    elif identifier == "backup_success":
        valid = [v for v in values if v > 0 and _date(v)]
        value = _kst(min(valid)) if valid else "성공 시각 미확인"
        detail = "백업 성공 기록 · 복원 검증은 별도"
        if valid and observed:
            age = (observed - _date(min(valid))).total_seconds()
            detail = (f"{age / 3600:.1f}시간 전 · " if age >= 0 else "미래 시각 표본 · 시계 확인 필요 · ") + detail
        state = "unknown" if not valid else "observed"
    elif identifier == "target_up":
        responding = sum(value == 1 for value in values)
        value = f"{responding}/{len(values)}개 응답"
        detail = "수집 연결 응답 · 업무 요청 성공 여부는 별도"
        state = "attention" if responding != len(values) else "observed"
    elif identifier == "services_failed":
        failed = sum(value > 0 for value in values)
        value = f"{failed}개 실패"
        detail = "관측 대상 systemd 서비스 기준"
        state = "attention" if failed else "observed"
    elif identifier == "db_replication_lag":
        value = f"{max(values):,.0f} B"
        detail = "전송·재생 위치의 최대 차이 · 지연 시간·유실량 의미 아님"
        state = "attention" if max(values) > 0 else "observed"
    elif identifier == "db_connections":
        value = f"{sum(values):,.0f}개"
        detail = "수집 DB 연결 합계 · 허용 한도 대비 여유는 별도 확인"
        parts = []
        for sample in samples[:4]:
            labels = sample.get("labels", {})
            target = labels.get("role") or labels.get("instance") or "대상 미제공"
            database = " / " + _text(labels["datname"], 30) if labels.get("datname") else ""
            parts.append(f"{_text(target, 55)}{database} {sample['last']:,.0f}개")
        detail += " · " + "; ".join(parts)
    elif identifier == "latency_p95":
        value = f"{max(values) * 1000:,.0f} ms"
        detail = "수집 P95의 최대값 · 표본·구간에 따른 변동"
    elif identifier == "io_time":
        value = f"{max(values) * 100:.1f}%"
        detail = "수집 디스크의 구간 마지막 표본 중 최대 I/O 사용 비율"
    elif identifier in ("request_rate", "server_errors", "network_errors"):
        value = f"{sum(values):,.2f}건/초"
        detail = "조회 구간의 마지막 표본 합계"
        state = "attention" if identifier != "request_rate" and sum(values) > 0 else "observed"
    else:
        value = f"{values[-1]:,.2f}"
        detail = "단위 미지정 · 마지막 표본 값"
    source = fact.get("source", {})
    sample_times = [_date(sample.get("last_observed_at")) for sample in samples]
    sample_times = [value for value in sample_times if value]
    as_of = _kst(min(sample_times).isoformat()) if sample_times else "표본 시각 미제공"
    end = observed
    step = _number(source.get("step_seconds")) or 0
    if sample_times and end and (end - min(sample_times)).total_seconds() > max(180, min(step * 2, 900)):
        state = "unknown"
        detail = "오래된 표본 · 현재 상태 확인 필요. " + detail
        value += " (과거 표본)"
    if invalid_range:
        state = "unknown"
        detail += " · 구간 통계 해석 불가: 범위 오류 표본 포함, 최저·최대값 제외"
    return {**base, "value": value, "detail": detail, "status": state, "as_of": as_of,
            "scope": _text(source.get("scope"), 240)}


def build_brief(report):
    """Produce a shared, deterministic Korean brief for Teams, HTML and PDF."""
    evidence, analysis = report.get("evidence", {}), report.get("analysis", {})
    intervention = assess(report)
    facts = {fact["id"]: fact for fact in evidence.get("facts", []) if isinstance(fact, dict) and "id" in fact}
    observed = _date(evidence.get("collected_at")) or _date(evidence.get("window_end"))
    trigger = _events(facts.get("trigger", {}), observed, True)
    ambient = _events(facts.get("alerts", {}), observed)
    incident = report.get("kind") == "incident" or bool(trigger)
    events = trigger if incident else ambient
    firing = sum(event["status"] == "firing" for event in events)
    resolved = sum(event["status"] == "resolved" for event in events)
    unknown = sum(event["status"] == "unknown" for event in events)
    names = list(dict.fromkeys(event["name"] for event in events))
    names_title = names[0] if len(names) == 1 else f"운영 경보 {len(events)}건 묶음"
    if incident and events and resolved == len(events):
        code, label, tone = "resolved", "알림 해제", "good"
        title = names_title + " 해제"
        if len(events) == 1:
            ended = events[0]["ended_at"]
            subject = events[0]["name"]
            if not subject.endswith("경보"):
                subject += " 경보"
            if ended and ended.endswith(" KST"):
                moment = ended.split(" ", 1)[1]
                if observed and ended[:10] != observed.astimezone(KST).strftime("%Y-%m-%d"):
                    moment = ended
                lead = f"{moment} 경보 해제 · 복구 후속 점검 대상"
            else:
                lead = "경보 해제 · 해제 시각 미확인"
        else:
            lead = f"경보 {resolved}건 해제 · 대상별 전환 시각 기록 참조"
    elif firing and resolved:
        code, label, tone = "mixed", "진행·해제 혼합", "warning"
        title = names_title if incident else "일일 운영 보고"
        lead = f"진행 {firing}건 / 해제 {resolved}건 · 대상별 전환 시각 기록 참조"
    elif firing:
        code, label, tone = "firing", "진행 중", "attention"
        title = names_title + " 발생" if incident else "일일 운영 보고"
        lead = f"진행 경보 {firing}건 · 원인 확인 및 서비스 영향 점검 필요"
    else:
        code, label, tone = "unknown", "확인 필요", "warning"
        title = "사건 상태 확인 필요" if incident else "일일 운영 보고"
        lead = "사건 전환 정보 미확인" if incident else "일일 관측 현황 · 수집 시점 기준 개입 판정"
    if unknown:
        lead += f" · 상태 미확인 {unknown}건"
    metrics = [_metric(fact, observed) for fact in facts.values() if fact.get("kind") == "metric" or "samples" in fact]
    if code == "resolved" and any("Backup" in event["alertname"] for event in events):
        backup = next((item for item in metrics if item["evidence"] == "backup_success"), None)
        if backup and backup["value"].endswith(" KST"):
            lead += " · 마지막 백업 성공 " + backup["value"].split(" ", 1)[1]
    priority = {"backup_success": 0} if any("Backup" in event["alertname"] for event in events) else {"disk_available": 0} if any("Disk" in event["alertname"] for event in events) else {}
    primary = (["db_connections", "db_replication_lag", "target_up", "request_rate", "latency_p95", "server_errors"]
               if any(event["alertname"].startswith("Database") for event in events)
               else ["target_up", "request_rate", "latency_p95", "server_errors", "disk_available", "backup_success"])
    metrics.sort(key=lambda item: (priority.get(item["evidence"], 1), item["status"] != "attention",
                                   primary.index(item["evidence"]) if item["evidence"] in primary else len(primary)))
    gaps = [f"{FACT_NAMES.get(identifier, identifier)}: {'수집 실패' if fact.get('status') == 'error' else '자료 없음'}"
            for identifier, fact in facts.items() if fact.get("status") != "ok"]
    gaps += [f"{item['label']}: {item['value']}" for item in metrics
             if item["status"] == "unknown" and facts[item["evidence"]].get("status") == "ok"]
    if not facts:
        gaps = ["운영 근거 미수신"]
    checks = [item["task"] for item in intervention["actions"]]
    end, seconds = _date(evidence.get("window_end")), _number(evidence.get("window_seconds"))
    start = end - dt.timedelta(seconds=seconds) if end and seconds else None
    times = _report_times(facts, observed, start, end)
    today = observed.astimezone(KST).date() if observed else None
    generated = analysis.get("mode") in ("remote", "gemini", "ollama")
    ai_checks = []
    if generated:
        for item in analysis.get("next_checks", [])[:3]:
            refs, text = item.get("evidence", []), _align_times(item.get("text"), times, today)
            if refs and all(ref in facts for ref in refs) and text:
                ai_checks.append(_text(text, 320))
    remaining = {}
    for event in ambient:
        if event["status"] != "firing":
            continue
        name = event["name"]
        if event["alertname"] == "TradeSilence" and event["local_lab"]:
            name += " (유휴 실습 참고)"
        remaining[name] = remaining.get(name, 0) + 1
    if remaining:
        current = "진행 경보: " + "; ".join(f"{name} {count}건" for name, count in remaining.items())
    elif facts.get("alerts", {}).get("status") == "ok":
        current = "수집 시점 진행 경보 0건"
    else:
        current = "수집 시점 경보 목록 미확인"
    impact = "고객 영향 미확인 · 요청 실패 범위·영향 사용자 수 자료 없음"
    if any(event["alertname"] == "DatabaseConnectionFailed" and event["role"] == "Standby DB" for event in events):
        impact = "Standby 접속 장애 · Primary 요청 영향 및 복제 지속 여부 확인 필요"
        if code == "resolved":
            impact = "Standby 접속 경보 해제 · 장애 구간의 고객 영향 및 복제 정상화 확인 필요"
    elif any(event["alertname"] == "DatabaseExporterUnavailable" for event in events):
        impact = "DB 관측 공백 · DB 직접 접속 및 서비스 영향 미확인"
    errors = next((item for item in metrics if item["evidence"] == "server_errors" and item["status"] == "attention"), None)
    if errors:
        impact = f"5xx 응답 {errors['value']} 관측 · 영향 사용자 수·지속 시간 미확인"
    timeline = []
    for event in events[:6]:
        timeline.append({"label": event["name"] + " 발생", "time": event["started_at"]})
        if event["ended_at"]:
            timeline.append({"label": event["name"] + " 해제", "time": event["ended_at"]})
    timeline.append({"label": "근거 수집", "time": _kst(evidence.get("collected_at"))})
    window = f"{_kst(start.isoformat())} ~ {_kst(end.isoformat())}" if start else "수집 구간 미제공"
    mode = analysis.get("mode", "facts-only")
    note = {"mock": "모의 분석 · 개입 등급은 수집 근거와 운영 기준으로 판정",
            "remote": "AI 참고 분석 · 개입 등급은 수집 근거와 운영 기준으로 판정",
            "gemini": "Gemini 참고 분석 · 개입 등급은 수집 근거와 운영 기준으로 판정",
            "ollama": "온프레미스 AI 참고 분석 · 개입 등급은 수집 근거와 운영 기준으로 판정",
            "facts-only": "수집 근거로 판정 · AI 분석 없음"}.get(mode, "분석 방식 확인 필요")
    if mode == "facts-only":
        reason = str(analysis.get("reason", ""))
        if "한도 기록이 없어" in reason:
            note = "수집 근거로 판정 · AI 호출 한도 기록 미확인"
        elif "기존 호출 기록" in reason:
            note = "수집 근거로 판정 · AI 호출 한도·이전 호출 기록에 따른 추가 호출 생략"
        elif "한도" in reason:
            note = "수집 근거로 판정 · AI 호출 한도 도달"
        elif "응답을 확보하지 못" in reason or any(word in reason for word in ("시간 제한", "연결", "검증 실패")):
            note = "AI 응답 미확보 · 수집 근거로 판정"
    ai_notes = []
    if generated:
        for item in analysis.get("hypotheses", [])[:3]:
            refs, text = item.get("evidence", []), _align_times(item.get("text"), times, today)
            if refs and all(ref in facts for ref in refs) and text:
                ai_notes.append("미확인 가설: " + _text(text, 260))
    scope = evidence.get("scope", {})
    customer = scope.get("customer") or next((event["customer"] for event in events if event["customer"]), "고객 미지정")
    environment = scope.get("environment") or next((event["environment"] for event in events if event["environment"]), "환경 미지정")
    environment = {"local-lab": "로컬 실습", "aws": "AWS"}.get(environment, environment)
    label, automation = report_label(report.get("id")), automation_label(report.get("automation"))
    return {"report_label": label, "automation": automation, "record_line": label + " · " + automation,
            "automation_basis": "보고 구간에 보고 서비스가 받은 관제센터 사건 기준"
            if automation != AUTOMATION_UNREADABLE else "관제센터 사건 기록을 읽지 못함",
            "schema_version": 2, "title": title, "status": {"code": code, "label": label, "tone": tone},
            "customer": _text(customer, 80), "environment": _text(environment, 60),
            "lead": lead, "impact": impact, "current_state": current, "metrics": metrics[:12], "events": events[:12],
            "event_count": len(events), "timeline": timeline, "next_checks": checks[:5], "gaps": gaps[:16],
            "intervention": intervention, "ai_checks": ai_checks,
            "analysis_note": note,
            "analysis_summary": _text(_align_times(analysis.get("summary"), times, today), 400) if generated else "",
            "ai_notes": ai_notes, "report_id": _text(report.get("id"), 120),
            "collected_at": _kst(evidence.get("collected_at")), "window_label": window,
            "evidence_ids": list(facts)[:20], "action_note": intervention["policy_note"]}


def _markdown(value):
    return re.sub(r"([\\`*_{}\[\]<>()#!|])", r"\\\1", _text(value, 800))


def card(report):
    brief = build_brief(report)
    decision = brief["intervention"]
    heading = "페일스톱 운영 관제 · " + brief["customer"] + " / " + brief["environment"]

    def text(value, **options):
        return {"type": "TextBlock", "text": _markdown(value), "wrap": True, **options}

    resolved = brief["status"]["code"] == "resolved"
    icon = "\U0001f7e2" if resolved else "\U0001f534" if decision["code"] == "immediate" else "\U0001f7e0" if decision["code"] in ("scheduled", "verify") else "\U0001f4cb"
    color = "Good" if resolved else {"immediate": "Attention", "scheduled": "Warning", "verify": "Warning", "monitor": "Good"}[decision["code"]]
    details = []
    if brief["events"]:
        event = brief["events"][0]
        details = [{"title": "대상", "value": _markdown(event["target"])},
                   {"title": "발생", "value": _markdown(event["started_at"])}]
        if event["ended_at"]:
            details.append({"title": "해제", "value": _markdown(event["ended_at"])})
    else:
        details = [{"title": "보고 기준", "value": _markdown(brief["collected_at"])}]
    # 영향은 대상·발생과 같은 이름-값 줄이다. 예전처럼 한 줄에 '영향  |  '를 붙이면 세로줄이 Markdown 이스케이프를 거쳐
    # 빈 칸 뒤 '|'만 남은 표 조각처럼 보였다.
    details.append({"title": "영향", "value": _markdown(brief["impact"])})
    body = [text(heading, size="Small", isSubtle=True),
            text(icon + " " + brief["title"], size="Large", weight="Bolder", color=color),
            text(decision["priority"] + " · " + decision["label"], weight="Bolder"),
            {"type": "FactSet", "facts": details},
            text("담당자 조치", weight="Bolder", separator=True)]
    actions = decision["actions"]
    if brief["events"]:
        relevant = [action for action in actions if "trigger" in action["evidence"]]
        if relevant:
            actions = [action for action in actions if action in relevant or action["priority"] == decision["priority"]]
    for action in actions[:2]:
        body += [text(f"{action['priority']} · {action['owner']} · {action['timing']}", weight="Bolder", size="Small"),
                 text(action["task"], size="Small"), text("완료: " + action["done_when"], size="Small", isSubtle=True)]
    metrics = [item for item in brief["metrics"] if item["status"] != "unknown"][:4]
    if metrics:
        body += [text("후속 관측 · " + brief["collected_at"], weight="Bolder", size="Small", separator=True),
                 {"type": "FactSet", "facts": [{"title": _markdown(item["label"]), "value": _markdown(item["value"])} for item in metrics]}]
    if brief["analysis_summary"]:
        body += [text("참고 분석", weight="Bolder", separator=True), text(brief["analysis_summary"], size="Small")]
    if brief["gaps"]:
        body.append(text("미확인 자료 " + str(len(brief["gaps"])) + "항목 · 상세 보고의 수집 범위 참조", size="Small", isSubtle=True))
    body += [text(brief["analysis_note"], size="Small", isSubtle=True, separator=True),
             text(brief["record_line"], size="Small", isSubtle=True)]
    result = {"type": "message", "attachments": [{"contentType": "application/vnd.microsoft.card.adaptive", "contentUrl": None,
              "content": {"$schema": "http://adaptivecards.io/schemas/adaptive-card.json", "type": "AdaptiveCard", "version": "1.2", "body": body}}]}
    if len(json.dumps(result, ensure_ascii=False).encode()) > 26000:
        raise ValueError("card_too_large")
    return result


def render_html(report):
    """Two bounded report pages; full evidence remains in the saved report JSON."""
    brief = build_brief(report)
    decision = brief["intervention"]
    esc = lambda value: html.escape(str(value), quote=True)
    clip = lambda value, limit: esc(_text(value, limit) + ("…" if len(str(value or "")) > limit else ""))
    metrics = "".join(
        f'<article class="metric {esc(item["status"])}"><h3>{esc(item["label"])}</h3>'
        f'<strong>{esc(item["value"])}</strong><small>{clip(item["detail"], 90)}</small></article>'
        for item in brief["metrics"][:6])
    chart_cards = "".join(
        f'<figure class="chart"><figcaption>{esc(chart["title"])}</figcaption>{render_svg(chart)}'
        f'<small>{clip(chart["note"], 130)}</small></figure>' for chart in build_charts(report)[:4])
    actions = "".join(
        f'<tr><td class="priority">{esc(item["priority"])}</td><td>{esc(item["owner"])}<small>{esc(item["timing"])}</small></td>'
        f'<td>{clip(item["task"], 110)}<small>근거 {esc(", ".join(item["evidence"][:4]))}</small></td>'
        f'<td>{clip(item["done_when"], 110)}</td></tr>' for item in decision["actions"][:5])
    events = "".join(
        f'<tr><td>{esc(item["status_label"])}</td><td>{clip(item["name"], 45)}<small>{clip(item["target"], 70)}</small></td>'
        f'<td>{esc(item["started_at"])}<small>{"해제 " + esc(item["ended_at"]) if item["ended_at"] else "해제 시각 없음"}</small></td></tr>'
        for item in brief["events"][:3])
    if not events:
        events = '<tr><td colspan="3">사건 전환 자료 없음</td></tr>'
    if brief["event_count"] > 3:
        events += f'<tr><td colspan="3">추가 {brief["event_count"] - 3}건: 원자료 참조</td></tr>'
    closures = "".join(f'<li>{clip(item, 110)}</li>' for item in decision["closure_conditions"][:5])
    limitations = decision["limitations"]
    # Keep explicit missing facts visible while avoiding a repeated disclaimer paragraph.
    required = [item for item in limitations if item.startswith("필수 근거 ")]
    other = [item for item in limitations if not item.startswith("필수 근거 ") and not item.startswith("고객 영향")]
    missing = "필수 자료: " + "; ".join(item.removeprefix("필수 근거 ") for item in required) if required else "필수 자료 누락 없음"
    limits = [missing, *other[:3]]
    limit_html = "".join(f'<li>{clip(item, 420 if index == 0 else 150)}</li>' for index, item in enumerate(limits))
    analysis = f'<section class="analysis"><h2>참고 분석</h2><p>{clip(brief["analysis_summary"], 300)}</p></section>' if brief["analysis_summary"] else ""
    footer = (f'<footer><span>{esc(brief["record_line"])} · 보고 ID {clip(brief["report_id"], 90)}</span>'
              '<span>수집 시점 판정</span></footer>')
    return f'''<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(brief["title"])} · 페일스톱 운영 보고</title><style>
@page{{size:A4;margin:10mm}}*{{box-sizing:border-box}}body{{margin:0;background:#e9edf3;color:#19283b;font:11px/1.5 "Noto Sans KR","Malgun Gothic",sans-serif}}
.page{{width:190mm;min-height:277mm;margin:24px auto;padding:25px 28px;background:white;box-shadow:0 8px 30px #10203515;display:flex;flex-direction:column}}
header{{display:flex;justify-content:space-between;gap:20px;border-bottom:2px solid #1b334d;padding-bottom:12px;margin-bottom:14px}}.eyebrow{{font-size:9px;letter-spacing:.1em;color:#617690;margin:0 0 5px}}h1{{font-size:21px;line-height:1.35;margin:0;letter-spacing:-.035em}}.page-number{{color:#667b91;font-size:10px;white-space:nowrap}}h2{{font-size:12px;margin:0 0 8px}}h3{{font-size:10px;font-weight:500;margin:0 0 3px;color:#60738a}}p{{margin:0}}section{{margin:12px 0}}.decision{{border-left:4px solid #ba7a25;background:#fff7e8;padding:12px 15px;margin:0 0 12px}}.decision.immediate{{border-color:#b43b35;background:#fff0ed}}.decision.monitor{{border-color:#237b6a;background:#edf8f3}}.decision strong{{display:block;font-size:18px;letter-spacing:-.02em;margin-bottom:3px}}.lead{{white-space:pre-line;font-size:12px;font-weight:600}}.meta{{font-size:9px;color:#60738a;margin:6px 0 10px}}.summary{{display:grid;grid-template-columns:1fr 1fr;gap:16px;background:#f3f6f9;padding:12px}}.summary h2{{margin-bottom:4px}}.metrics{{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px}}.metric{{border:1px solid #dce4ec;padding:8px 10px;break-inside:avoid}}.metric.attention{{border-top:3px solid #b3712b}}.metric.unknown{{border-top:3px solid #9eacba}}.metric strong{{display:block;font-size:15px;letter-spacing:-.025em;overflow-wrap:anywhere}}small{{display:block;font-size:9px;color:#65788e;overflow-wrap:anywhere;margin-top:3px}}.chart-grid{{display:grid;grid-template-columns:1fr 1fr;gap:10px}}.chart{{margin:0;border:1px solid #dce4ec;padding:9px 10px;break-inside:avoid}}figcaption{{font-size:11px;font-weight:700}}.chart svg{{width:100%;display:block}}.chart small{{font-size:8px;line-height:1.4}}
table{{width:100%;border-collapse:collapse;table-layout:fixed;font-size:10px}}th{{font-size:9px;text-align:left;color:#596f87;background:#edf2f7;padding:8px}}td{{padding:9px 8px;border-bottom:1px solid #dce4ec;vertical-align:top;overflow-wrap:anywhere}}tr{{break-inside:avoid}}.priority{{font-weight:700;color:#925b1d}}.actions th:nth-child(1){{width:7%}}.actions th:nth-child(2){{width:22%}}.actions th:nth-child(3){{width:37%}}.events th:nth-child(1){{width:10%}}.events th:nth-child(2){{width:42%}}.footnote{{font-size:9px;color:#667a90;margin-top:6px}}ul{{padding-left:17px;margin:0}}li{{margin:4px 0;overflow-wrap:anywhere}}.closing{{display:grid;grid-template-columns:1fr 1fr;gap:20px}}.limitations{{background:#f4f6f9;padding:13px}}.limitations li{{font-size:10px}}.analysis p{{font-size:11px}}.method{{font-size:9px;color:#65788e;border-top:1px solid #dce4ec;padding-top:9px}}footer{{margin-top:auto;padding-top:14px;display:flex;justify-content:space-between;gap:10px;color:#6d8198;font-size:8px;border-bottom:1px solid #dce4ec;padding-bottom:5px}}
@media(max-width:680px){{.page{{width:100%;min-height:0;margin:0 0 12px;padding:22px}}.metrics{{grid-template-columns:repeat(2,minmax(0,1fr))}}.chart-grid,.closing,.summary{{grid-template-columns:1fr}}h1{{font-size:19px}}table{{font-size:9px}}td,th{{padding:6px 4px}}}}
@media print{{body{{background:white}}.chart svg{{height:140px}}.metric small{{font-size:8px}}.metric strong{{font-size:14px}}section{{margin:9px 0}}.page{{width:190mm;height:277mm;min-height:0;margin:0;padding:5mm;box-shadow:none;break-after:page;page-break-after:always}}.page:last-child{{break-after:auto;page-break-after:auto}}.decision,.summary,th,.limitations{{print-color-adjust:exact;-webkit-print-color-adjust:exact}}h2{{break-after:avoid}}}}
</style></head><body>
<article class="page" data-page="1"><header><div><p class="eyebrow">FAILSTOP / OPERATIONS CENTER · {esc(brief["customer"])} / {esc(brief["environment"])}</p><h1>{esc(brief["title"])}</h1></div><span class="page-number">상황 및 영향 · 1 / 2</span></header>
<div class="decision {esc(decision["code"])}"><strong>{esc(decision["priority"])} · {esc(decision["label"])}</strong><p>{esc(decision["reason"])}</p></div>
<p class="lead">{esc(brief["lead"])}</p><p class="meta">근거 수집 {esc(brief["collected_at"])}<br>후속 지표 구간 {esc(brief["window_label"])}</p>
<div class="summary"><div><h2>서비스 영향</h2><p>{esc(brief["impact"])}</p></div><div><h2>수집 시점 경보</h2><p>{clip(brief["current_state"], 180)}</p></div></div>
<section><h2>핵심 운영 지표</h2><div class="metrics">{metrics or '<p>사용 가능한 수치 자료 없음</p>'}</div></section>
<section><h2>후속 점검 지표</h2><div class="chart-grid">{chart_cards}</div></section>{footer}</article>
<article class="page" data-page="2"><header><div><p class="eyebrow">FOLLOW-UP / RECOMMENDED ACTIONS</p><h1>조치와 완료 조건</h1></div><span class="page-number">점검 계획 · 2 / 2</span></header>
<section><h2>우선 확인할 사항</h2><table class="actions"><thead><tr><th>순위</th><th>권고 담당·착수</th><th>확인·조치</th><th>완료 조건</th></tr></thead><tbody>{actions}</tbody></table><p class="footnote">{esc(brief["action_note"])}<br>관제센터 자동 조치: {esc(brief["automation"])} · {esc(brief["automation_basis"])}</p></section>
<section><h2>발생 및 해제 이력</h2><table class="events"><thead><tr><th>전환</th><th>사건 / 대상</th><th>발생 / 해제 · KST</th></tr></thead><tbody>{events}</tbody></table><p class="footnote">표시 구간의 후속 관측 자료 · 경보 발생 당시의 상태와 구분</p></section>
{analysis}<section class="closing"><div><h2>종결 확인</h2><ul>{closures}</ul></div><div><h2>기록할 결과</h2><p>확인 대상·작업 시각·변경 내용·재확인 결과.<br>백업은 파일과 복원 기록의 ID 대조.<br>고객 영향은 요청 결과로 별도 확인.</p></div></section>
<section class="limitations"><h2>판단 제한</h2><ul>{limit_html}</ul></section><p class="method">{esc(brief["analysis_note"])}</p>{footer}</article>
</body></html>'''
