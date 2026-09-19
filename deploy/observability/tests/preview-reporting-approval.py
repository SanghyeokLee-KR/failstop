"""Render the proposed five-message payload without sending it anywhere."""
import datetime as dt
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "deploy/reporting"))
from evidence import collect
from reporter import analyze, card, daily_key, load_config


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    config = load_config(args.config)
    config["analyzer"] = "mock"
    started = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    messages = []
    for status in ("firing", "resolved"):
        messages.append({"kind": "alertmanager_basic_" + status,
                         "title": "[LOCAL TEST " + status + "] OperationsIntegrationCheck",
                         "text": "로컬 운영 보고 연결 시험이다. 실제 장애가 아니다."})
        incident = [{"labels": {"alertname": "OperationsIntegrationCheck", "instance": "local-reporting-test", "severity": "info"},
                     "annotations": {"summary": "로컬 운영 보고 연결 시험. 실제 장애가 아니다."}, "startsAt": started, "status": status}]
        facts = collect(config, time.time(), incident)
        report = {"id": "approval-preview-incident-" + status, "kind": "incident", "evidence": facts, "analysis": analyze(facts, config)}
        messages.append({"kind": "mock_incident_" + status, "report": report, "card": card(report)})
    key, end = daily_key()
    facts = collect(config, end)
    report = {"id": key, "kind": "daily", "evidence": facts, "analysis": analyze(facts, config)}
    messages.append({"kind": "mock_daily", "report": report, "card": card(report)})
    result = {"created_at": started, "destination": "사용자가 .env에 준비한 Teams Workflows 웹훅의 채널. 비밀 URL은 포함하지 않는다.",
              "scope": "기본 시험 알림 firing/resolved 2건, 모의 사건 보고 2건, 모의 일일 보고 1건", "message_count": 5,
              "sent": False, "channel_post_confirmed": False,
              "note": "승인 후 실행 시 보고 ID와 수집 시각·지표 값은 최신 값으로 갱신된다. 계정정보·웹훅URL·원문 앱로그는 포함하지 않는다.",
              "messages": messages}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "preview_saved", "message_count": 5, "sent": False}))


if __name__ == "__main__":
    main()
