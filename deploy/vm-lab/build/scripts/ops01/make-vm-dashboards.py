"""학교 AWS용 MSP 화면 7개를 OPS01의 VM 라벨에 맞춰 바꾼다.

원본은 failstop/deploy/linux/observability/msp-dashboards/의 JSON 7개다.
패널 쿼리는 customer·environment 변수와 role 라벨로 대상을 고른다. 변수와 라벨 값은
VM 배치에 맞춰 바꾸고 식의 구조(집계 함수, 임계값, 패널 구성)는 건드리지 않는다.
바꾸는 것은 네 가지다.

  1. environment 변수의 저장된 선택값 aws -> vm-lab
     (이 값이 aws로 남으면 화면을 열었을 때 대상이 하나도 잡히지 않는다)
  2. 설명 문구의 학교 AWS 표기 -> VM 실습 표기
  3. 태그 school-aws -> vm-lab
  4. 패널 쿼리의 role 정규식에 edge와 observability 추가
     (02-정본결정.md 8절. 빠뜨리면 EDGE01과 OPS01이 어느 패널에도 나오지 않는다)

경보·CloudWatch 미연결 안내 패널은 그대로 둔다. VM에서도 blackbox 프로브와
CloudWatch는 없고, 경보 규칙은 Alertmanager 연결 뒤에 따로 확인한다.

사용법:
  python3 make-vm-dashboards.py --source <failstop>/deploy/linux/observability/msp-dashboards \
      --output ./msp-dashboards-vm
"""
import argparse
import json
import sys
from pathlib import Path

FILES = (
    "10-msp-overview.json", "11-msp-service.json", "12-msp-infra.json",
    "13-msp-db.json", "14-msp-db-workload.json", "15-msp-db-replication.json",
    "16-msp-storage.json",
)
NOTE = "VM 실습 환경 배포. 학교 AWS 화면을 lab-lan 주소와 vm-lab 라벨로 옮긴 것이다. 미수집은 정상 판정이 아니다."

# role 라벨 정규식. 원본 7개는 app|reader|data|standby|probe 다섯 값만 고른다.
# build-files/ops/prometheus.vm.yml은 EDGE01을 role: edge로, OPS01을 role: observability로
# linux-host 작업에 넣어 수집하므로 원본 정규식으로는 그 두 대가 어느 패널에도 나오지 않는다.
# 바꿀 값은 build-files/ops/reporting.config.vm.json이 이미 쓰는 것과 같은 문자열이다.
# JSON 본문에서는 큰따옴표가 역슬래시로 이스케이프돼 있으므로 파싱 전 원문에서 치환한다.
ROLE_FROM = 'role=~\\"app|reader|data|standby|probe\\"'
ROLE_TO = 'role=~\\"edge|app|reader|data|standby|probe|observability\\"'


def convert(source, output):
    output.mkdir(parents=True, exist_ok=True)
    total_roles = 0
    missing = []
    for name in FILES:
        raw = (source / name).read_text(encoding="utf-8")
        role_hits = raw.count(ROLE_FROM)
        raw = raw.replace(ROLE_FROM, ROLE_TO)
        total_roles += role_hits
        if role_hits == 0:
            missing.append(name)
        dashboard = json.loads(raw)
        changed = []
        for variable in dashboard["templating"]["list"]:
            if variable["name"] == "environment":
                variable["current"] = {"text": "vm-lab", "value": "vm-lab"}
                variable["options"] = []
                changed.append("environment=vm-lab")
        tags = [tag for tag in dashboard.get("tags", []) if tag != "school-aws"]
        if "vm-lab" not in tags:
            tags.append("vm-lab")
        dashboard["tags"] = tags
        dashboard["description"] = NOTE
        dashboard["id"] = None
        changed.append("role 정규식 %d곳" % role_hits)
        # 줄바꿈은 LF로 고정한다. Windows에서 만들어도 Linux에 설치하는 파일이므로
        # 기본 변환에 맡기지 않는다. newline=""를 주면 텍스트 변환이 일어나지 않는다.
        (output / name).write_text(
            json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8", newline="",
        )
        print(name, dashboard["uid"], ",".join(changed))

    print("role 정규식 치환 합계: %d곳" % total_roles)
    # 치환이 한 건도 없는 파일이 있으면 원본 표기가 바뀐 것이다. 조용히 넘기면
    # EDGE01과 OPS01이 빠진 화면이 그대로 설치되므로 여기서 멈춘다.
    if missing:
        print("[중단] role 정규식을 찾지 못한 파일: %s" % ", ".join(missing), file=sys.stderr)
        print("[중단] 원본에서 찾는 문자열: %s" % ROLE_FROM, file=sys.stderr)
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    convert(arguments.source, arguments.output)


if __name__ == "__main__":
    main()
