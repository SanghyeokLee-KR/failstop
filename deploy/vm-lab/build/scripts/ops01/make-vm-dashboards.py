"""학교 AWS용 MSP 화면 7개를 OPS01의 VM 라벨에 맞춰 바꾼다.

원본은 failstop/deploy/linux/observability/msp-dashboards/의 JSON 7개다.
패널 쿼리는 customer·environment 변수와 role 라벨로 대상을 고른다. 변수와 라벨 값은
VM 배치에 맞춰 바꾸고 식의 구조(집계 함수, 임계값, 패널 구성)는 건드리지 않는다.
바꾸는 것은 여섯 가지다.

  1. environment 변수의 저장된 선택값 aws -> vm-lab
     (이 값이 aws로 남으면 화면을 열었을 때 대상이 하나도 잡히지 않는다)
  2. 설명 문구의 학교 AWS 표기 -> VM 실습 표기
  3. 태그 school-aws -> vm-lab
  4. 패널 쿼리의 role 정규식에 edge와 observability 추가
     (02-정본결정.md 8절. 빠뜨리면 EDGE01과 OPS01이 어느 패널에도 나오지 않는다)
  5. '서비스 경로' canvas에서 CloudFront와 ALB 계층 제거
     (VM 실습망에 없는 AWS 전용 계층이다. 남겨 두면 영원히 '미수집'으로 보여
      관측이 고장난 것처럼 읽힌다. 게다가 원본 식은 environment="$environment"와
      environment="aws"를 한 선택자에 함께 걸어 두어 blackbox를 배포해도 결과가 빈다)
  6. 패널 링크의 /alerting/list를 Prometheus 경보 화면으로 교체
     (grafana.ini의 [unified_alerting] enabled=false라 그 주소는 빈 화면이다.
      이 배치의 경보는 Prometheus 규칙과 Alertmanager가 담당한다)

CloudWatch 미연결 안내 패널은 그대로 둔다. 학교 AWS와의 차이를 화면에서 읽을 수 있어야 한다.

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

# VM 실습망에 없는 AWS 전용 계층. '서비스 경로 · 계층별 상태' canvas 가 이 둘을 계층 카드로 그린다.
# 원본 식은 한 선택자 안에 environment="$environment" 와 environment="aws" 를 함께 걸어
# 두 값이 모순돼 결과가 항상 빈다. 라벨만 고쳐도 VM 에는 CloudFront 도 ALB 도 없으므로
# 계층 자체를 뺀다. 남기면 영원히 '미수집' 으로 보여 관측이 고장난 것처럼 읽힌다.
AWS_ONLY_TIERS = ("cloudfront", "alb")

# 패널 링크가 가리키는 Grafana 통합 경보 화면. grafana.ini 가 그 기능을 껐으므로 빈 화면이다.
# 이 배치의 경보는 Prometheus 규칙이 평가하고 Alertmanager 가 보낸다.
# Prometheus 의 /alerts 를 고른 이유는 발화 중인 것만이 아니라 규칙 전체와 그 상태를 보여
# '전체 경보' 라는 링크 이름과 맞기 때문이다. 9090 은 OPS01 sshd 의 PermitOpen 목록에 있어
# ssh -L 9090:127.0.0.1:9090 터널을 연 브라우저에서 열린다.
ALERT_LINK_FROM = "/alerting/list"
ALERT_LINK_TO = "http://127.0.0.1:9090/alerts"


def drop_aws_only_tiers(dashboard):
    """서비스 경로 canvas 에서 CloudFront·ALB 계층을 뺀다. 뺀 자리는 남은 계층을 당겨 메운다."""
    removed = 0
    for panel in dashboard.get("panels", []):
        if panel.get("type") != "canvas":
            continue
        targets = panel.get("targets", [])
        tiers = [t.get("legendFormat") for t in targets]
        if not any(tier in tiers for tier in AWS_ONLY_TIERS):
            continue
        root = panel.get("options", {}).get("root", {})
        elements = root.get("elements", [])
        # 카드의 left 좌표가 계층 격자다. 순서를 유지한 채 남은 계층에 앞자리부터 다시 준다.
        slots = sorted(
            round(e["placement"]["left"], 4)
            for e in elements
            if e["name"].endswith("-card")
        )
        keep = [t for t in tiers if t not in AWS_ONLY_TIERS]
        base_of = {}
        for e in elements:
            if e["name"].endswith("-card"):
                base_of[e["name"].rsplit("-", 1)[0]] = round(e["placement"]["left"], 4)
        new_base = {tier: slots[i] for i, tier in enumerate(keep)}

        kept_elements = []
        for e in elements:
            tier = e["name"].rsplit("-", 1)[0]
            if tier in AWS_ONLY_TIERS:
                removed += 1
                continue
            if tier in base_of and tier in new_base:
                offset = round(e["placement"]["left"], 4) - base_of[tier]
                e["placement"]["left"] = round(new_base[tier] + offset, 4)
            kept_elements.append(e)
        root["elements"] = kept_elements
        panel["targets"] = [t for t in targets if t.get("legendFormat") not in AWS_ONLY_TIERS]
    return removed


def retarget_alert_links(dashboard):
    """비활성 Grafana 경보 화면을 가리키는 링크를 Prometheus 경보 화면으로 돌린다."""
    changed = 0
    def fix(links):
        nonlocal changed
        for link in links or []:
            if link.get("url") == ALERT_LINK_FROM:
                link["url"] = ALERT_LINK_TO
                # 다른 탭에서 연다. Grafana 안의 상대 경로가 아니라 다른 서비스이기 때문이다.
                link["targetBlank"] = True
                changed += 1
    fix(dashboard.get("links"))
    for panel in dashboard.get("panels", []):
        fix(panel.get("links"))
        for inner in panel.get("panels", []) or []:
            fix(inner.get("links"))
    return changed



def convert(source, output):
    output.mkdir(parents=True, exist_ok=True)
    total_roles = 0
    total_tiers = 0
    total_links = 0
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

        tiers_removed = drop_aws_only_tiers(dashboard)
        if tiers_removed:
            total_tiers += tiers_removed
            changed.append("AWS 전용 계층 요소 %d개 제거" % tiers_removed)
        links_fixed = retarget_alert_links(dashboard)
        if links_fixed:
            total_links += links_fixed
            changed.append("경보 링크 %d곳 교체" % links_fixed)
        # 줄바꿈은 LF로 고정한다. Windows에서 만들어도 Linux에 설치하는 파일이므로
        # 기본 변환에 맡기지 않는다. newline=""를 주면 텍스트 변환이 일어나지 않는다.
        (output / name).write_text(
            json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8", newline="",
        )
        print(name, dashboard["uid"], ",".join(changed))

    print("role 정규식 치환 합계: %d곳" % total_roles)
    print("AWS 전용 계층 요소 제거 합계: %d개" % total_tiers)
    print("경보 링크 교체 합계: %d곳" % total_links)
    # 원본이 바뀌어 찾을 것을 못 찾으면 조용히 넘어가지 않는다. 기대한 수는
    # 2026-09-22 기준 원본 7개에서 센 값이다. CloudFront·ALB 계층이 세 파일에
    # 네 요소씩 두 계층이라 24개, 경보 링크는 10-msp-overview 와 13-msp-db 에 하나씩이다.
    if total_tiers != 24:
        print("[중단] AWS 전용 계층 요소가 24개가 아니라 %d개다. 원본 구조가 바뀌었다." % total_tiers,
              file=sys.stderr)
        raise SystemExit(1)
    if total_links != 2:
        print("[중단] 경보 링크가 2곳이 아니라 %d곳이다. 원본 구조가 바뀌었다." % total_links,
              file=sys.stderr)
        raise SystemExit(1)
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
