"""학교에서 푸시한 MSP 화면을 지정 커밋에서 읽어 AWS 배포본을 만든다."""
import json
import subprocess
from pathlib import Path
from storage_dashboard import build_storage

ROOT = Path(__file__).resolve().parents[3]
SOURCE_COMMIT = "6ee11791c79564b37c47ea30659373a856eb91c8"
DESTINATION = Path(__file__).resolve().parent / "msp-dashboards"
FILES = (
    "10-msp-overview.json", "11-msp-service.json", "12-msp-infra.json",
    "13-msp-db.json", "14-msp-db-workload.json", "15-msp-db-replication.json",
)


def panels(items):
    for panel in items:
        yield panel
        yield from panels(panel.get("panels", []))


def unavailable(panel, message):
    # 연결 전인 경보를 0건으로, CloudWatch를 쿼리 오류로 보이지 않게 한다.
    panel["type"] = "text"
    panel.pop("datasource", None)
    panel.pop("transformations", None)
    panel["targets"] = []
    panel["options"] = {"mode": "markdown", "content": message}


def build():
    DESTINATION.mkdir(exist_ok=True)
    for filename in FILES:
        raw = subprocess.check_output(
            ["git", "show", f"{SOURCE_COMMIT}:harness/grafana/dashboards/{filename}"],
            cwd=ROOT,
        )
        dashboard = json.loads(raw)
        dashboard["id"] = None
        dashboard["description"] = (
            f"학교 AWS 배포. 원본 Git 커밋 {SOURCE_COMMIT[:7]}. "
            "앱·Linux·DB 지표 수집 대상. 외부 프로브·CloudWatch·경보 규칙은 연결 전. "
            "미수집은 정상 판정이 아니다."
        )
        dashboard["tags"] = list(dict.fromkeys(dashboard.get("tags", []) + ["school-aws"]))
        for variable in dashboard["templating"]["list"]:
            if variable["name"] == "environment":
                variable["current"] = {"text": "aws", "value": "aws"}
                variable["options"] = []
        dashboard["links"].insert(6, {"type": "link", "title": "07 · 디스크·용량", "url": "/d/msp-storage", "includeVars": True, "keepTime": True, "targetBlank": False})
        for panel in panels(dashboard["panels"]):
            if any("ALERTS{" in target.get("expr", "") for target in panel.get("targets", [])):
                unavailable(panel, "경보 규칙 **미연결** · 현재 화면에서 정상 여부를 판정하지 않습니다.")
            elif panel.get("datasource", {}).get("type") == "cloudwatch":
                unavailable(panel, "CloudWatch **미연결** · AWS 권한과 대상 자원을 확인한 뒤 연결합니다.")
        (DESTINATION / filename).write_text(
            json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(filename, dashboard["uid"])
    reference = json.loads((DESTINATION / FILES[0]).read_text(encoding="utf-8"))
    storage = build_storage(reference)
    for link in storage["links"]:
        if link.get("title") in ("다크", "라이트"):
            link["url"] = link["url"].replace("msp-overview", "msp-storage")
    (DESTINATION / "16-msp-storage.json").write_text(json.dumps(storage, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    build()
