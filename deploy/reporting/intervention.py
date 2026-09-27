"""수집 시점의 개입 권고. 경보 해제나 AI 문장으로 운영 위험을 낮추지 않는다."""
import datetime as dt
import math

# deploy/observability/rules/linux-operations.yml과 test_intervention.py에서 대조한다.
# 마지막 표본의 임계 초과와 Prometheus의 for 조건을 충족한 경보는 구분한다.
DISK_FREE = 0.15
INODE_FREE = 0.10
BACKUP_MAX_AGE_SECONDS = 93600
HOST_MAX_AGE_SECONDS = 180
CRITICAL_ALERTS = {"LinuxHostUnreachable", "LinuxServiceFailed", "DatabaseReplicationUnknown", "DatabaseExporterUnavailable", "DatabaseConnectionFailed"}
REQUIRED_METRICS = {"target_up", "disk_available", "inode_free", "services_failed", "db_connections",
                    "db_replication_lag", "request_rate", "server_errors", "latency_p95", "backup_success"}
LABELS = {"immediate": "즉시 대응 필요", "scheduled": "담당자 조치 필요", "verify": "담당자 확인 필요", "monitor": "관찰 유지"}
RANK = {"immediate": 1, "scheduled": 2, "verify": 3, "monitor": 4}
# 이 권고는 보고 서비스가 실행하지 않는다는 뜻이다. 관제센터의 자동 조치 수는 보고마다 따로 센다(presentation.automation_label).
POLICY_NOTE = "수집 시점의 권고 기준 · 담당 역할·착수 시점은 SLA나 작업 배정이 아님 · 보고 서비스는 조치를 실행하지 않음"
# 경보 이름의 한국어 표기. 카드·보고서 제목(presentation)과 권고 항목의 대상 이름이 같은 표를 쓴다.
# 표에 없는 경보는 '운영 경보'로 적는다
ALERT_NAMES = {
    "DatabaseBackupUnknownOrStale": "최근 DB 백업 확인 경보",
    "LinuxDiskSpaceLow": "디스크 여유 공간 부족",
    "LinuxInodesLow": "파일 생성 여유 부족",
    "LinuxServiceFailed": "Linux 서비스 실패",
    "LinuxHostUnreachable": "Linux 호스트 수집 중단",
    "LinuxCollectorFailed": "Linux 지표 일부 수집 실패",
    "LinuxLogCollectionStale": "호스트 운영 기록 수집 지연",
    "LinuxTargetsMissing": "Linux 수집 대상 미등록",
    "DatabaseReplicationUnknown": "DB 복제 상태 미확인",
    "DatabaseExporterUnavailable": "DB 수집 경로 장애",
    "DatabaseConnectionFailed": "DB 접속 장애",
    "DatabaseMetricsMissing": "DB 지표 일부 누락",
    "SpringMetricsUnavailable": "Spring 서비스 수집 중단",
    "OperationsDailyReportMissing": "일일 보고 전달 지연",
    "OperationsReportDeliveryFailed": "운영 보고 전달 실패",
    "OperationsIntegrationCheck": "운영 보고 연결 시험",
    "TradeSilence": "거래 발생 없음",
    # 관제센터 부하 경보(harness/prometheus/demo-rules/load-alerts.yml). 관리 콘솔 시뮬레이터와 같은 이름이다
    "AppTierCpuHigh": "앱 계층 CPU 높음",
    "ServiceLatencyHigh": "응답 지연 증가",
}


def database_role(labels):
    role = labels.get("role")
    if role in ("standby", "replica"):
        return "Standby DB"
    if role in ("data", "primary"):
        return "Primary DB"
    return "DB"


def database_alert_name(name, labels):
    role = database_role(labels)
    return {"DatabaseConnectionFailed": role + " 접속 장애",
            "DatabaseExporterUnavailable": role + " 수집 경로 장애",
            "DatabaseReplicationUnknown": "DB 복제 상태 미확인",
            "DatabaseMetricsMissing": role + " 지표 누락"}.get(name)


def _date(value):
    try:
        if type(value) in (float, int) and math.isfinite(value):
            return dt.datetime.fromtimestamp(value, dt.timezone.utc)
        parsed = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed if parsed.tzinfo and parsed.year > 1 else None
    except (ValueError, TypeError, OverflowError, OSError):
        return None


def _number(value):
    return value if type(value) in (int, float) and math.isfinite(value) else None


def _text(value, limit=160):
    return str(value or "").replace("\n", " ")[:limit]


def _age_problem(value, reference, maximum):
    moment = _date(value)
    if not moment or not reference:
        return "시각 미제공"
    age = (reference - moment).total_seconds()
    return "미래 시각" if age < -30 else "오래된 자료" if age > maximum else None


def _event_rows(fact):
    records = fact.get("records", [])
    if isinstance(records, dict):
        status = records.get("status")
        records = [{**row, "status": row.get("status", status)} for row in records.get("alerts", []) if isinstance(row, dict)]
    return [row for row in records if isinstance(row, dict)] if isinstance(records, list) else []


def assess(report, now=None):
    evidence = report.get("evidence", {})
    facts = {row["id"]: row for row in evidence.get("facts", []) if isinstance(row, dict) and isinstance(row.get("id"), str)}
    reference = _date(evidence.get("collected_at"))
    findings, limitations = [], []

    def add(code, reason, owner, timing, task, done_when, refs):
        findings.append({"code": code, "reason": reason, "priority": "P" + str(RANK[code]), "owner": owner,
                         "timing": timing, "task": task, "done_when": done_when,
                         "evidence": [ref for ref in refs if ref in facts]})

    if not reference:
        limitations.append("근거 수집 시각 미제공: 현재 상태 판정 불가")
    if now is not None:
        rendered_at = _date(now)
        if rendered_at and reference and abs((rendered_at - reference).total_seconds()) > HOST_MAX_AGE_SECONDS:
            limitations.append("보관 보고 재표시: 개입 권고는 근거 수집 시점 기준이며 현재 상태는 별도 확인")
    fresh_samples, missing = {}, []
    for name in sorted(REQUIRED_METRICS):
        fact = facts.get(name)
        if not fact or fact.get("status") != "ok":
            missing.append(name + (": 수집 실패" if fact and fact.get("status") == "error" else ": 자료 미제공"))
            continue
        samples = fact.get("samples", [])
        if not isinstance(samples, list) or not samples:
            missing.append(name + ": 표본 없음")
            continue
        step = _number(fact.get("source", {}).get("step_seconds")) or 0
        maximum_age = max(HOST_MAX_AGE_SECONDS, min(step * 2, 900))
        good, problems = [], []
        for row in samples:
            if not isinstance(row, dict) or _number(row.get("last")) is None:
                problems.append("수치 해석 불가")
                continue
            problem = _age_problem(row.get("last_observed_at"), reference, maximum_age)
            if (name in {"disk_available", "inode_free"} and not 0 <= row["last"] <= 1
                    or name in {"target_up", "services_failed"} and row["last"] not in (0, 1)
                    or row["last"] < 0
                    or name == "db_connections" and row["last"] != int(row["last"])):
                problem = "수치 범위 오류"
            if problem:
                problems.append(problem)
            else:
                good.append(row)
        if problems:
            missing.append(name + ": " + ", ".join(sorted(set(problems))))
        fresh_samples[name] = good

    if any(row["last"] == 0 for row in fresh_samples.get("target_up", [])):
        add("immediate", "수집 대상 응답 실패", "Linux·서비스 운영 담당", "우선 착수 권고",
            "응답 실패 대상의 프로세스·접속 경로와 실제 요청 성공 여부 확인", "대상 응답 회복과 업무 요청 결과 확인", ["target_up"])
    if any(row["last"] > 0 for row in fresh_samples.get("services_failed", [])):
        add("immediate", "실패 서비스 관측", "Linux 운영 담당", "우선 착수 권고",
            "실패 서비스 상태·최근 로그와 영향 범위 점검", "실패 원인 기록과 서비스·요청 경로 재확인", ["services_failed"])
    if any(row["last"] > 0 for row in fresh_samples.get("server_errors", [])):
        add("verify", "업무 요청 5xx 응답 관측", "서비스 운영 담당", "우선 확인 권고",
            "실패 요청 경로·발생 비율·지속 시간과 영향 범위 확인", "요청 성공 여부와 오류 원인·영향 범위 기록", ["server_errors", "request_rate"])

    storage_findings = set()
    for name, threshold, label in [("disk_available", DISK_FREE, "디스크"), ("inode_free", INODE_FREE, "inode")]:
        low = [row for row in fresh_samples.get(name, []) if row["last"] < threshold]
        if low:
            worst = min(low, key=lambda row: row["last"])
            target = worst.get("labels", {}).get("mountpoint") or "대상 파일시스템"
            add("scheduled", f"{_text(target, 50)} {label} 여유 {worst['last'] * 100:.1f}%", "Linux 운영 담당", "금일 점검 권고",
                f"{_text(target, 50)} 증가 원인·잔여 용량·보존 대상 확인 후 정리 또는 증설 검토",
                f"여유 {threshold * 100:.0f}% 이상과 5분 뒤 경보 상태 확인", [name, "alerts"])
            storage_findings.add("LinuxDiskSpaceLow" if name == "disk_available" else "LinuxInodesLow")
            limitations.append(f"{label} 임계 초과는 마지막 표본 기준: 5분 지속·읽기 전용 여부는 경보 자료로 별도 확인")

    active, seen, backup_transition = [], set(), False
    for fact_id in ("alerts", "trigger"):
        fact = facts.get(fact_id, {})
        if fact.get("status") != "ok":
            if fact_id == "alerts":
                missing.append("alerts: 현재 경보 자료 미제공")
            continue
        for row in _event_rows(fact):
            labels = row.get("labels", {})
            name = _text(labels.get("alertname", "이름 미제공"), 80)
            identity = (name, labels.get("instance"), labels.get("mountpoint"), labels.get("name"))
            status = row.get("status")
            end = _date(row.get("endsAt"))
            start = _date(row.get("startsAt"))
            if isinstance(status, dict):
                status = "resolved" if end and reference and end <= reference else "firing"
            if status == "resolved":
                backup_transition |= name == "DatabaseBackupUnknownOrStale"
                if not end or not reference or end > reference + dt.timedelta(seconds=30):
                    missing.append("trigger: 해제 시각 미제공 또는 수집 시각과 불일치")
                if fact_id == "trigger" and name in {"DatabaseConnectionFailed", "DatabaseExporterUnavailable", "DatabaseReplicationUnknown"}:
                    add("verify", (database_alert_name(name, labels) or "DB 경보") + " 해제 · 종결 확인 필요",
                        "DB 운영 담당", "복구 직후 확인 권고", "DB 접속·복제 streaming·WAL 재생 위치 재확인",
                        "DB 접속과 복제 유지 확인 후 장애 구간·원인·조치 기록", [fact_id, "db_replication_lag", "db_connections"])
                continue
            if status != "firing" or not reference or (start and start > reference + dt.timedelta(seconds=30)) or (end and end < reference):
                missing.append(f"{fact_id}: {name} 전환 시각·상태 대조 필요")
                continue
            if identity in seen:
                continue
            seen.add(identity)
            if name == "TradeSilence" and labels.get("environment") == "local-lab":
                limitations.append("TradeSilence: 유휴 실습에서도 발생 가능한 정책 경보, 주문 수요·실패 증거 미확인")
                continue
            active.append(name)
            if name == "DatabaseConnectionFailed":
                add("immediate", database_role(labels) + " 접속 장애", "DB 운영 담당", "즉시 확인 권고",
                    "대상 DB 프로세스·5432 접속·인증 로그와 Primary/Standby 복제 상태 확인",
                    "DB 접속 회복·복제 streaming·Spring 요청 결과 확인", [fact_id, "db_connections", "db_replication_lag"])
                continue
            if name == "DatabaseExporterUnavailable":
                add("immediate", database_role(labels) + " 수집 경로 장애", "관측·DB 운영 담당", "즉시 확인 권고",
                    "Exporter 프로세스·수집 경로 확인 및 DB 직접 접속 점검",
                    "Exporter 응답·pg_up=1 및 DB 접속 확인", [fact_id, "target_up"])
                continue
            if labels.get("severity") == "critical" or name in CRITICAL_ALERTS:
                subject = database_alert_name(name, labels) or {"LinuxHostUnreachable": "호스트 응답 중단", "LinuxServiceFailed": "서비스 실행 실패"}.get(name, "중대 경보")
                add("immediate", subject, "해당 서비스 운영 담당", "우선 착수 권고",
                    subject + " 대상과 실제 요청 경로 점검", "경보 조건 해소와 대상·요청 경로 재확인", [fact_id])
            elif name not in storage_findings:
                code = "verify" if any(word in name for word in ("Missing", "Unknown", "Stale", "Collector")) else "scheduled"
                subject = database_alert_name(name, labels) or ALERT_NAMES.get(name, "운영 경보")
                add(code, subject + " 발생", "해당 서비스 운영 담당", "금일 점검 권고",
                    "경보 발생 조건·대상·최근 변경 확인", "경보 상태 재확인과 미확인 항목 보완", [fact_id])

    backup_rows = fresh_samples.get("backup_success", [])
    if backup_rows and any(row["last"] <= 0 or _age_problem(row["last"], reference, BACKUP_MAX_AGE_SECONDS) for row in backup_rows):
        missing.append("backup_success: 최근 26시간 백업 성공 미확인 또는 시각 오류")
    backup_checks = []
    schedule = facts.get("backup_schedule", {})
    records = schedule.get("records", {})
    if not (schedule.get("status") == "ok" and isinstance(records, dict) and records.get("enabled") is True
            and not _age_problem(records.get("checked_at"), reference, HOST_MAX_AGE_SECONDS)
            and _date(records.get("next_run_at")) and reference and _date(records["next_run_at"]) > reference):
        backup_checks.append("다음 예약 실행")
    restore = facts.get("backup_restore", {})
    records = restore.get("records", {})
    if not (restore.get("status") == "ok" and isinstance(records, dict) and records.get("verified") is True
            and records.get("backup_id") and not _age_problem(records.get("verified_at"), reference, BACKUP_MAX_AGE_SECONDS)):
        backup_checks.append("격리 복원 결과")
    if backup_checks:
        reason = "백업 경보 해제 후 " if backup_transition else "백업 "
        add("verify", reason + "·".join(backup_checks) + " 미확인", "DB 운영 담당", "금일 확인 권고",
            "백업 파일·다음 예약 실행·격리 복원 결과 대조", "예약 활성·다음 실행 시각과 백업 ID에 연결된 복원 성공 기록 확보",
            ["backup_success", "backup_schedule", "backup_restore", "trigger"])

    host = facts.get("host", {})
    host_problem = _age_problem(host.get("collected_at"), reference, HOST_MAX_AGE_SECONDS)
    if host.get("status") != "ok" or host_problem:
        missing.append("host: " + (host_problem or "수집 실패·자료 미제공"))
    if missing:
        limitations.extend("필수 근거 " + item for item in sorted(set(missing)))
        add("verify", "필수 근거·시각 대조 미완료", "관측·서비스 운영 담당", "다음 판정 전 확인 권고",
            "누락·오래된 항목의 대상·수집 시각·명시된 누락 사유 확인", "필수 근거의 최신 표본과 실제 요청 결과 확보",
            [name for name in sorted(REQUIRED_METRICS | {"alerts", "host"}) if name in facts])
    for name in ("journal", "aws_activity"):
        if facts.get(name, {}).get("status") != "ok":
            limitations.append(("상세 로그" if name == "journal" else "AWS 변경 기록") + " 미제공: 원인·변경 이력 판단 범위 제한")
    if not findings:
        add("monitor", "최신 필수 근거·백업 확인 완료, 추가 개입 조건 없음", "운영 관측 담당", "다음 정기 수집",
            "경보·지표·예약 백업 상태 관찰", "다음 수집에서 새 경보·누락 여부 확인", sorted(REQUIRED_METRICS))
    findings.sort(key=lambda row: RANK[row["code"]])
    code = findings[0]["code"]
    reasons = list(dict.fromkeys(row["reason"] for row in findings if row["code"] == code))
    actions = [{key: value for key, value in row.items() if key not in {"code", "reason"}} for row in findings[:5]]
    limitations.insert(0, "고객 영향 미확인: 개입 우선순위와 서비스 장애 확정은 별개")
    return {"code": code, "label": LABELS[code], "priority": "P" + str(RANK[code]),
            "reason": "; ".join(reasons[:2]), "actions": actions,
            "closure_conditions": list(dict.fromkeys(row["done_when"] for row in findings))[:6],
            "limitations": list(dict.fromkeys(limitations)), "policy_note": POLICY_NOTE,
            "assessed_at": evidence.get("collected_at"), "required_facts": sorted(REQUIRED_METRICS | {"alerts", "host", "backup_schedule", "backup_restore"})}
