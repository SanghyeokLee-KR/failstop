"""학교 AWS MSP 화면 7개로 로컬 Docker 판을 만든다.

원본은 deploy/linux/observability/msp-dashboards/ 의 JSON 7개(10~16)이고, 결과는 harness/grafana/dashboards-msp/ 다.
결과 JSON 을 손으로 고치지 않는다. 원본이 바뀌면 이 스크립트를 다시 돌린다.

    python harness/grafana/build-local-msp.py            결과를 쓴다
    python harness/grafana/build-local-msp.py --check    결과가 원본과 이 스크립트로 만든 것과 같은지만 본다

uid·제목·패널 배치·상단 보드 링크 줄(01~07, 다크·라이트)은 원본 그대로다. 관리 콘솔 딥링크가 uid 를 쓴다.
로컬 수집 규약은 harness/prometheus/prometheus.demo.yml 에 있다. 라벨 이름은 AWS 와 같고 값만 로컬 값이다.

로컬 판 변환 규칙. 이 목록 밖의 식·임계값·단위는 바꾸지 않는다.
  1. environment 변수의 저장된 선택값을 aws 에서 local-lab 으로 바꾼다. aws 로 남으면 화면을 열 때 대상이 하나도
     잡히지 않는다. local-lab 은 보고 서비스(deploy/reporting)가 로컬 환경에 쓰는 값과 같다.
  2. 역할 정규식 role=~"app|reader|data|standby|probe" 에 host 를 더한다. 로컬 노드는 컨테이너라 노드마다
     node_exporter 가 없고, Docker 호스트 한 대의 node_exporter 가 role=host, node=docker-host 로 들어온다.
     host 를 빼면 파일시스템·inode·디스크 I/O·백업 경과가 어느 패널에도 나오지 않는다. AWS 판에는 host 가 없다.
  3. 서비스 경로 Canvas 의 CloudFront·ALB 질의(B·C)에 박힌 environment="aws" 를 뺀다. 같은 선택자에
     environment="$environment" 가 이미 있어 선택한 환경 기준이 된다. 로컬은 그 두 칸을 실제로 검사한다
     (CloudFront 역할 = 웹 입구의 정적 자산 /icon.svg, ALB 역할 = chaos-lb 의 /actuator/health/read).
  4. 03 · AWS·서버의 CPU·메모리·네트워크 패널(통계 둘, 시계열 넷)을 cstats 의 컨테이너별 실측으로 바꾼다.
     prometheus.demo.yml 이 고객 역할 컨테이너에만 node·role 을 붙이므로 범례는 AWS 와 같은 노드 이름이다.
     CPU 는 docker stats 값(100%=1코어)을 컨테이너에 준 코어 수(cstats 의 container_cpu_limit_cores)로 나눈다.
     AWS 호스트 CPU 처럼 받은 코어 전체가 100% 라 원본의 상한 100 을 그대로 둔다(app 1코어, DB 2코어).
     cstats 는 약 5초마다 값을 바꾼다(2026-09-27 전에는 컨테이너마다 두 번째 표본을 기다려 약 60초였다). 두 네트워크
     질의의 최소 간격 15s 는 그때 rate 창이 수집 주기보다 짧아 0 과 뾰족한 값이 번갈아 나오던 것을 막으려 둔 값이다.
     파일시스템·inode·디스크 I/O 는 원본 식 그대로 Docker 호스트 node_exporter 를 읽는다.
     '수집 범위' 글 패널에는 공유 디스크라는 사실과 수집 원천을 적는다.
  5. 07 · 디스크·용량의 파일시스템 선택자 mountpoint="/" 를 mountpoint="/var/lib" 로 바꾼다. Docker Desktop VM 의
     / 는 메모리 위 overlay 이고, 컨테이너·볼륨·백업이 모두 있는 영속 디스크는 /var/lib(ext4)다. 설명과 안내 글도
     로컬 배치에 맞춘다.
  6. 모든 Prometheus 질의의 최소 간격(Min step)을 5s 로 둔다. 로컬 스크레이프 간격은 대부분 5초인데 데이터 소스의
     scrape interval 이 2s(exchange-api 기준)라, 짧은 시간 범위에서 $__rate_interval 이 8초까지 줄어 표본이 모자란
     빈 구간이 생긴다. Min step 5s 면 $__rate_interval 이 20초 이상이다. AWS 판은 데이터 소스 기본 15s 를 쓴다.
  7. 대시보드 설명과 태그(school-aws → local-docker)를 로컬 배치로 바꾼다.
  8. 01 · 서비스 관제 '서비스 가용성 · 장애 구간'의 외부 접속 범례에 {{service}} 를 붙인다. 로컬 검사 대상이 셋이라
     원본의 고정 범례 '외부 접속'으로는 같은 이름 세 줄이 된다. 식은 그대로다.
  9. 04·06의 'DB 복제 구성 · 현재 역할' 캔버스에서 고정 문구 '비동기 복제'를 복제 방식 실측(pg_stat_replication
     sync_state)으로 바꾼다. 식과 문구는 생성기(deploy/observability/tests의 msp_queries·msp_canvas)와 같다. 고정 판
     (build-msp-dashboards.py 의 SOURCE_COMMIT)이 새 생성기를 담으면 이 규칙은 원본에서 이미 된 상태가 된다.
 10. 로컬 시연 스택은 Patroni 엄격 동기(ADR 0036)다. 학교 AWS 기준의 수동 승격 안내(04 진단 글, 06 '사람 개입' 절차와
     복제 설명 글, 03 수집 범위의 DB 승격)를 Patroni 자동 전환 절차로 바꾼다.
 11. 시연 영상 전용 판 90-msp-demo-app-cpu.json(uid msp-demo-app-cpu)을 따로 만든다. 학교 판에는 없고 보드 링크 줄에도
     싣지 않는다. 위 규칙을 거친 03 · AWS·서버의 CPU 시계열(패널 6)을 그대로 복사하고 역할만 앱(app·reader)으로 좁힌다.
     2026-09-27 사용자 요청이다. 앱 계층 증설 장면의 CPU 확대 화면에 주 DB 선이 섞여 app-3 합류가 가려졌다.
     원본 7개 판은 바꾸지 않는다.
 12. 화면에 보이는 DB 역할 이름 Primary·Standby 를 '주 DB'·'예비 DB'로 바꾼다(패널 제목, Canvas 고정 글, 값 매핑 글의
     글 전체가 같을 때만). 2026-09-27 사용자 요청이다. DB 교수가 용어를 정리하라고 했고 영상 자막도 '주 DB'·'예비 DB'를 쓴다.
     질의식과 마우스를 올릴 때만 보이는 설명은 원본 그대로다. 학교 AWS 판(원본)은 이 생성기 밖이라 아직 영문이다.
원본의 구조가 바뀌어 위 치환이 예상한 수만큼 일어나지 않으면 멈춘다.
"""
import argparse
import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy" / "observability" / "tests"))
from msp_canvas import REPLICATION_MODE_MAPPING  # noqa: E402
from msp_queries import Q  # noqa: E402
SOURCE = ROOT / "deploy" / "linux" / "observability" / "msp-dashboards"
DEST = Path(__file__).resolve().parent / "dashboards-msp"
FILES = (
    "10-msp-overview.json", "11-msp-service.json", "12-msp-infra.json",
    "13-msp-db.json", "14-msp-db-workload.json", "15-msp-db-replication.json",
    "16-msp-storage.json",
)
ENVIRONMENT = "local-lab"
ROLE_FROM = 'role=~"app|reader|data|standby|probe"'
ROLE_TO = 'role=~"app|reader|data|standby|probe|host"'
AWS_PIN = ',environment="aws"'
MOUNT_FROM = 'mountpoint="/",'
MOUNT_TO = 'mountpoint="/var/lib",'
MIN_STEP = "5s"
COUNTER_MIN_STEP = "15s"

# 원본(2026-09-27 기준)에서 센 값이다. 원본이 바뀌면 이 수도 같이 확인한다.
EXPECTED_ROLE = {
    "10-msp-overview.json": 191, "11-msp-service.json": 202, "12-msp-infra.json": 147,
    "13-msp-db.json": 385, "14-msp-db-workload.json": 275, "15-msp-db-replication.json": 205,
    "16-msp-storage.json": 4,
}
EXPECTED_AWS_PIN = {"10-msp-overview.json": 2, "11-msp-service.json": 2, "12-msp-infra.json": 2}
EXPECTED_MOUNT = {"16-msp-storage.json": 12}

DESCRIPTION = (
    "로컬 Docker 판. 학교 AWS 화면(deploy/linux/observability/msp-dashboards)을 harness/grafana/build-local-msp.py 로 "
    "environment=local-lab 라벨에 맞춘 것. 앱·DB 노드는 컨테이너이고 호스트 디스크는 Docker 호스트 한 대의 실측이다. "
    "미수집은 정상 판정이 아니다."
)

# 03 · AWS·서버의 컨테이너 실측 패널. cstats 대상(up)에는 role 이 없으므로 신선도 조건은 고객·환경·job 으로 건다.
# 수집 실패(failstop_container_collection_success=0) 동안에는 cstats 가 값을 내지 않으므로 빈 구간이 된다.
CSTATS = 'customer="$customer",environment="$environment",' + ROLE_TO + ',job="cstats"'
CSTATS_UP = 'customer="$customer",environment="$environment",job="cstats"'
CSTATS_FRESH = (
    f'((up{{{CSTATS_UP}}} and (time() - timestamp(up{{{CSTATS_UP}}}) < 90)) == 1)'
    f' and on(instance,job) (failstop_container_collection_success{{{CSTATS_UP}}} == 1)'
)


def fresh(selector_expr):
    return f"(({selector_expr}) and on(instance,job) ({CSTATS_FRESH}))"


CPU = fresh(f"container_cpu_percent{{{CSTATS}}} / (container_cpu_limit_cores{{{CSTATS}}} > 0)")
MEM = fresh(f"100 * container_mem_bytes{{{CSTATS}}} / (container_mem_limit_bytes{{{CSTATS}}} > 0)")
RX = fresh(f"rate(container_net_rx_bytes{{{CSTATS}}}[$__rate_interval])")
TX = fresh(f"rate(container_net_tx_bytes{{{CSTATS}}}[$__rate_interval])")
CONTAINER_NOTE = ("로컬: 노드 컨테이너 경계 실측(cstats, Docker API). 값은 약 5초마다 바뀐다. "
                  "AWS 판은 노드별 node_exporter 의 호스트 값이다.")
CONTAINER_PANELS = {
    # id: (식, 범례, 설명, 최소 간격, 상한 제거)
    2: (f"max({CPU})", "", "로컬: 노드 컨테이너 중 최대. 컨테이너에 준 코어 전체가 100%다(app 1코어, DB 2코어). " + CONTAINER_NOTE,
        MIN_STEP, False),
    3: (f"max({MEM})", "", "로컬: 컨테이너 메모리(비활성 파일 캐시 제외) / 컨테이너 상한. 상한이 없는 DB 는 Docker 호스트 전체 메모리가 분모다. "
        + CONTAINER_NOTE, MIN_STEP, False),
    6: (f"max by(node) ({CPU})", "{{node}}", "컨테이너에 준 코어 전체가 100%다(app 1코어, DB 2코어). " + CONTAINER_NOTE,
        MIN_STEP, False),
    7: (f"max by(node) ({MEM})", "{{node}}", "컨테이너 메모리(비활성 파일 캐시 제외) / 컨테이너 상한. " + CONTAINER_NOTE,
        MIN_STEP, False),
    10: (f"sum by(node) ({RX})", "{{node}}", "컨테이너 네트워크 수신 누적값의 증가율. " + CONTAINER_NOTE,
         COUNTER_MIN_STEP, False),
    11: (f"sum by(node) ({TX})", "{{node}}", "컨테이너 네트워크 송신 누적값의 증가율. " + CONTAINER_NOTE,
         COUNTER_MIN_STEP, False),
}
CONTAINER_TITLES = {
    2: "CPU 사용률 · 최대", 3: "메모리 사용률 · 최대", 6: "CPU 사용률 · 고객 호스트",
    7: "메모리 사용률", 10: "네트워크 수신", 11: "네트워크 송신",
}
SCOPE_PANEL_ID = 15
SCOPE_TITLE = "AWS 운영 · 수집 범위"
SCOPE_TEXT = (
    "**현재 환경** · `$environment`　|　**로컬 배치** · Docker Desktop 한 대, 앱·DB 노드는 컨테이너  \n"
    "**CPU·메모리·네트워크** · 노드 컨테이너 경계 실측(cstats, 받은 코어 전체=100%)　|　**DB 승격** · 자동(Patroni 엄격 동기)  \n"
    "**파일시스템·inode·디스크 I/O** · Docker 호스트 `docker-host` 의 `/var/lib` **한 디스크를 모든 노드가 공유**"
)

STORAGE_DESCRIPTION = ("로컬 Docker 판. Docker 호스트(Docker Desktop VM) 한 대의 영속 디스크(/var/lib, ext4) 여유와 "
                       "DB·WAL 크기. 모든 노드 컨테이너·두 DB·백업이 이 디스크를 함께 쓴다. 자동 삭제 기능은 없다.")
STORAGE_GUIDE_TITLE = "용량이 늘 때 확인할 순서"
# 원본 글은 줄바꿈 없는 한 문단이다(storage_dashboard.py 의 문자열 이어 쓰기). 같은 모양과 비슷한 길이로 둔다.
STORAGE_GUIDE = (
    "**70% 주의 · 85% 조치 · 95% 긴급**은 화면 색상 기준이며 자동 삭제는 없습니다. 로컬 경보 규칙에는 디스크 여유 경보가 없습니다.  "
    "**로그·캐시·오래된 백업 확인 → 보존 기준과 복원 가능 여부 확인 → 승인된 파일만 정리.** DB 데이터와 `pg_wal`은 직접 지우지 않습니다.  "
    "로컬에서는 두 DB·모든 노드 컨테이너·백업이 Docker 호스트의 한 디스크(`/var/lib`)를 함께 쓰고, 대기 DB는 DB 크기만큼 한 번 더 차지합니다."
)
# 01 · 서비스 관제의 '서비스 가용성 · 장애 구간'은 외부 접속 검사마다 한 줄을 그리는데 원본 범례가 '외부 접속' 고정이다.
# 로컬은 검사 대상이 셋(service=edge·cloudfront·alb)이라 같은 이름 세 줄이 되므로 범례에만 service 를 붙인다.
AVAILABILITY_TITLE = "서비스 가용성 · 장애 구간"
AVAILABILITY_LEGEND_FROM = "외부 접속"
AVAILABILITY_LEGEND_TO = "외부 접속 · {{service}}"

# 규칙 9. 'DB 복제 구성 · 현재 역할' 캔버스의 복제 방식 칸
REPLICATION_TITLE = "DB 복제 구성 · 현재 역할"
REPLICATION_DESCRIPTION = ("Primary/Standby는 in_recovery 실측으로 선택. 복제 방식(동기·비동기)은 sync_state 실측이고, "
                           "동기는 Patroni 엄격 동기(ADR 0036)로 표기. 복제 상태와 WAL 거리는 별도 지표. "
                           "자료 부재는 미수집이며 복제 정상 또는 무손실 판정 제외. 로컬 시연 스택은 Patroni 자동 승격.")
# 규칙 10. (파일, 패널 id, 원문 일부, 바꿀 글). 원문 일부가 없으면 원본 구조가 바뀐 것이라 멈춘다
PATRONI_TEXTS = [
    ("13-msp-db.json", 15, "DB 격리·승격·접속 전환", "승격·접속 전환은 Patroni 자동"),
    ("15-msp-db-replication.json", 15, "**비동기 복제** · 미복제 데이터 유실 가능성",
     "**엄격 동기 복제** · 예비 DB 기록 뒤 커밋 확정, 예비가 없으면 쓰기 정지"),
    ("15-msp-db-replication.json", 15, "자동 DB 승격 미적용", "자동 DB 승격(Patroni)"),
]
RECOVERY_PANEL = ("15-msp-db-replication.json", 14, "DB 복구 · 사람 개입")
RECOVERY_TITLE = "DB 복구 · 자동 전환(Patroni)"
RECOVERY_TEXT = ("**① 장애 감지** · 주 DB의 리더 키가 etcd(3대)에서 만료  \n**② 자동 승격** · 동기 예비 DB만 후보  \n"
                 "**③ 접속 전환** · HAProxy가 새 주 DB로  \n**④ 재합류** · 옛 주 DB를 예비로 · RTO/RPO 대조")


def replication_mode_canvas(name, panel):
    """규칙 9. 복제 방식 식을 캔버스 질의 끝에 더하고, 고정 문구 칸을 그 값(값 매핑)으로 바꾼다."""
    targets = panel["targets"]
    if any(target.get("legendFormat") == "replication_mode" for target in targets):
        fail(f"{name} 복제 캔버스에 이미 replication_mode 질의가 있다. 고정 판이 새 생성기를 담았으면 규칙 9를 뺀다.")
    ref = chr(65 + len(targets))
    added = copy.deepcopy(targets[-1])
    added.update(refId=ref, expr=Q["replication_mode"].replace(ROLE_FROM, ROLE_TO), legendFormat="replication_mode")
    targets.append(added)
    titles = [element for element in panel["options"]["root"]["elements"] if element.get("name") == "replication-title"]
    if len(titles) != 1:
        fail(f"{name} 복제 캔버스에 replication-title 칸이 하나가 아니다.")
    titles[0]["config"]["text"] = {"mode": "field", "field": "replication_mode", "fixed": "복제 방식 미수집"}
    titles[0]["config"]["color"] = {"fixed": "text"}
    panel["fieldConfig"]["overrides"].append({
        "matcher": {"id": "byFrameRefID", "options": ref}, "properties": [
            {"id": "mappings", "value": [copy.deepcopy(REPLICATION_MODE_MAPPING)]},
            {"id": "color", "value": {"mode": "fixed", "fixedColor": "text"}},
        ]})
    panel["description"] = REPLICATION_DESCRIPTION


def walk_strings(node, fn):
    """JSON 트리의 모든 문자열에 fn 을 적용한 새 트리를 돌려준다."""
    if isinstance(node, str):
        return fn(node)
    if isinstance(node, list):
        return [walk_strings(item, fn) for item in node]
    if isinstance(node, dict):
        return {key: walk_strings(value, fn) for key, value in node.items()}
    return node


def count_strings(node, pattern):
    if isinstance(node, str):
        return node.count(pattern)
    if isinstance(node, list):
        return sum(count_strings(item, pattern) for item in node)
    if isinstance(node, dict):
        return sum(count_strings(value, pattern) for value in node.values())
    return 0


def panels(items):
    for panel in items:
        yield panel
        yield from panels(panel.get("panels", []))


def fail(message):
    print("[중단] " + message, file=sys.stderr)
    raise SystemExit(1)


def convert(name, dashboard):
    changes = []
    roles = count_strings(dashboard, ROLE_FROM)
    if roles != EXPECTED_ROLE[name]:
        fail(f"{name} 역할 정규식이 {EXPECTED_ROLE[name]}곳이 아니라 {roles}곳이다. 원본 구조가 바뀌었다.")
    pins = count_strings(dashboard, AWS_PIN)
    if pins != EXPECTED_AWS_PIN.get(name, 0):
        fail(f"{name} environment=\"aws\" 고정이 {EXPECTED_AWS_PIN.get(name, 0)}곳이 아니라 {pins}곳이다.")
    mounts = count_strings(dashboard, MOUNT_FROM)
    if mounts != EXPECTED_MOUNT.get(name, 0):
        fail(f"{name} mountpoint=\"/\" 선택자가 {EXPECTED_MOUNT.get(name, 0)}곳이 아니라 {mounts}곳이다.")

    def rewrite(text):
        text = text.replace(ROLE_FROM, ROLE_TO).replace(AWS_PIN, "")
        return text.replace(MOUNT_FROM, MOUNT_TO)

    dashboard = walk_strings(dashboard, rewrite)
    changes.append(f"역할 정규식 {roles}곳")
    if pins:
        changes.append(f"environment=\"aws\" 고정 {pins}곳 제거")
    if mounts:
        changes.append(f"mountpoint / → /var/lib {mounts}곳")

    found_environment = False
    for variable in dashboard["templating"]["list"]:
        if variable["name"] == "environment":
            variable["current"] = {"text": ENVIRONMENT, "value": ENVIRONMENT}
            variable["options"] = []
            found_environment = True
    if not found_environment:
        fail(f"{name} 에 environment 변수가 없다.")

    dashboard["id"] = None
    dashboard["description"] = DESCRIPTION
    tags = [tag for tag in dashboard.get("tags", []) if tag != "school-aws"]
    dashboard["tags"] = tags + ([] if "local-docker" in tags else ["local-docker"])

    by_id = {panel.get("id"): panel for panel in panels(dashboard["panels"])}
    if name == "12-msp-infra.json":
        for panel_id, (expr, legend, description, step, drop_max) in CONTAINER_PANELS.items():
            panel = by_id.get(panel_id)
            if panel is None or panel.get("title") != CONTAINER_TITLES[panel_id] or len(panel.get("targets", [])) != 1:
                fail(f"{name} 패널 {panel_id} 가 '{CONTAINER_TITLES[panel_id]}' 단일 질의가 아니다.")
            target = panel["targets"][0]
            target["expr"] = expr
            target["legendFormat"] = legend
            target["interval"] = step
            panel["description"] = description
            if drop_max:
                panel["fieldConfig"]["defaults"].pop("max", None)
        changes.append(f"컨테이너 실측 패널 {len(CONTAINER_PANELS)}개")
        scope = by_id.get(SCOPE_PANEL_ID)
        if scope is None or scope.get("title") != SCOPE_TITLE or scope.get("type") != "text":
            fail(f"{name} 에 '{SCOPE_TITLE}' 글 패널이 없다.")
        scope["options"]["content"] = SCOPE_TEXT
        changes.append("수집 범위 글")
    if name == "10-msp-overview.json":
        timeline = [panel for panel in panels(dashboard["panels"]) if panel.get("title") == AVAILABILITY_TITLE]
        legends = [t for p in timeline for t in p.get("targets", []) if t.get("legendFormat") == AVAILABILITY_LEGEND_FROM]
        if len(legends) != 1:
            fail(f"{name} '{AVAILABILITY_TITLE}'의 '{AVAILABILITY_LEGEND_FROM}' 범례가 하나가 아니다.")
        legends[0]["legendFormat"] = AVAILABILITY_LEGEND_TO
        changes.append("외부 접속 범례에 service")
    if name == "16-msp-storage.json":
        dashboard["description"] = STORAGE_DESCRIPTION
        guide = [panel for panel in panels(dashboard["panels"]) if panel.get("title") == STORAGE_GUIDE_TITLE]
        if len(guide) != 1:
            fail(f"{name} 에 '{STORAGE_GUIDE_TITLE}' 글 패널이 하나가 아니다.")
        guide[0]["options"]["content"] = STORAGE_GUIDE
        changes.append("디스크 안내 글")
    if name in ("13-msp-db.json", "15-msp-db-replication.json"):
        canvases = [panel for panel in panels(dashboard["panels"])
                    if panel.get("title") == REPLICATION_TITLE and panel.get("type") == "canvas"]
        if len(canvases) != 1:
            fail(f"{name} 에 '{REPLICATION_TITLE}' 캔버스가 하나가 아니다.")
        replication_mode_canvas(name, canvases[0])
        changes.append("복제 방식 실측 칸")
    for file, panel_id, before, after in PATRONI_TEXTS:
        if file != name:
            continue
        panel = by_id.get(panel_id)
        content = (panel or {}).get("options", {}).get("content", "")
        if before not in content:
            fail(f"{name} 패널 {panel_id} 에 '{before}' 가 없다.")
        panel["options"]["content"] = content.replace(before, after)
        changes.append(f"Patroni 문구(패널 {panel_id})")
    if name == RECOVERY_PANEL[0]:
        panel = by_id.get(RECOVERY_PANEL[1])
        if panel is None or panel.get("title") != RECOVERY_PANEL[2] or panel.get("type") != "text":
            fail(f"{name} 에 '{RECOVERY_PANEL[2]}' 글 패널이 없다.")
        panel["title"] = RECOVERY_TITLE
        panel["options"]["content"] = RECOVERY_TEXT
        changes.append("DB 복구 절차 글")

    renamed = rename_db_roles(dashboard)
    if renamed:
        changes.append(f"DB 역할 한글 이름 {renamed}곳")

    steps = 0
    for panel in panels(dashboard["panels"]):
        if (panel.get("datasource") or {}).get("type") != "prometheus":
            continue
        for target in panel.get("targets", []):
            if "expr" in target and "interval" not in target:
                target["interval"] = MIN_STEP
                steps += 1
    changes.append(f"최소 간격 {MIN_STEP} {steps}곳")
    return dashboard, changes


# 규칙 12. 화면에 보이는 DB 역할 이름(패널 제목, Canvas 고정 글, 값 매핑 글)을 한글로 바꾼다. 글 전체가 같을 때만
# 바꾸고 질의식·설명(마우스를 올릴 때만 보이는 글)은 두어 원본과의 대조가 쉽게 한다.
DB_ROLE_WORDS = {
    "Primary DB": "주 DB", "Standby DB": "예비 DB", "Primary": "주 DB", "Standby": "예비 DB",
    "Primary · 쓰기": "주 DB · 쓰기", "Standby · 복제": "예비 DB · 복제",
    "Primary · 단일 구성": "주 DB · 단일 구성", "WAL 생성량 · Primary": "WAL 생성량 · 주 DB",
}


def rename_db_roles(node):
    """규칙 12. 제목(title)·Canvas 고정 글(fixed)·값 매핑 글(text) 가운데 DB_ROLE_WORDS 와 똑같은 글만 바꾸고 수를 돌려준다."""
    count = 0
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ("title", "fixed", "text") and isinstance(value, str) and value in DB_ROLE_WORDS:
                node[key] = DB_ROLE_WORDS[value]
                count += 1
            else:
                count += rename_db_roles(value)
    elif isinstance(node, list):
        for value in node:
            count += rename_db_roles(value)
    return count


DEMO_FILE = "90-msp-demo-app-cpu.json"
APP_ROLE = 'role=~"app|reader"'


def demo_app_cpu(infra):
    """규칙 11. 변환을 마친 03 · AWS·서버에서 CPU 시계열(패널 6)만 떼어 앱 역할로 좁힌 시연 전용 판을 만든다."""
    source = next((p for p in panels(infra["panels"]) if p.get("id") == 6), None)
    if source is None or source.get("type") != "timeseries" or not source.get("title", "").startswith("CPU"):
        fail("12-msp-infra.json 에 CPU 시계열 패널 6 이 없다. 규칙 11 을 원본에 맞춰 고친다.")
    panel = copy.deepcopy(source)
    replaced = 0
    for target in panel["targets"]:
        replaced += target["expr"].count(ROLE_TO)
        target["expr"] = target["expr"].replace(ROLE_TO, APP_ROLE)
    if replaced == 0:
        fail("03 CPU 시계열 식에 역할 선택자가 없다. 규칙 11 을 원본에 맞춰 고친다.")
    # 범례는 노드 라벨(app-1 등) 대신 한글 역할 이름으로 보인다(규칙 12 와 같은 이유). 식과 라벨 값은 그대로다
    overrides = panel.setdefault("fieldConfig", {}).setdefault("overrides", [])
    for n in (1, 2, 3):
        overrides.append({"matcher": {"id": "byName", "options": f"app-{n}"},
                          "properties": [{"id": "displayName", "value": f"앱 서버 {n}"}]})
    panel.update(id=1, title="CPU 사용률 · 앱 서버", gridPos={"h": 24, "w": 24, "x": 0, "y": 0},
                 description="로컬 시연 전용. 03 · AWS·서버의 'CPU 사용률 · 고객 호스트'와 같은 식에서 역할만 앱(app·reader)으로 "
                             "좁혔다. 받은 코어 전체가 100%다. 학교 AWS 판에는 없다.")
    keep = [v for v in infra["templating"]["list"] if v.get("name") in ("customer", "environment")]
    if len(keep) != 2:
        fail("03 판에 customer·environment 변수가 없다. 규칙 11 을 원본에 맞춰 고친다.")
    return {
        "uid": "msp-demo-app-cpu",
        "title": "시연 · 앱 서버 CPU",
        "description": "로컬 시연 영상 전용(harness/grafana/build-local-msp.py 규칙 11). 앱 계층 증설 장면의 CPU 확대 화면이다.",
        "tags": ["msp", "local-demo"],
        "editable": False,
        "schemaVersion": infra.get("schemaVersion"),
        "timezone": infra.get("timezone"),
        "refresh": infra.get("refresh"),
        "time": copy.deepcopy(infra.get("time")),
        "templating": {"list": copy.deepcopy(keep)},
        "panels": [panel],
    }


def render(dashboard):
    return json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="쓰지 않고 결과가 최신인지만 확인한다")
    args = parser.parse_args()
    stale = []
    DEST.mkdir(exist_ok=True)
    built = {}
    for name in FILES:
        original = json.loads((SOURCE / name).read_text(encoding="utf-8"))
        dashboard, changes = convert(name, copy.deepcopy(original))
        built[name] = (dashboard, changes)
    demo = demo_app_cpu(built["12-msp-infra.json"][0])
    built[DEMO_FILE] = (demo, ["시연 전용 · 앱 역할 CPU(규칙 11)"])
    for name, (dashboard, changes) in built.items():
        text = render(dashboard)
        target = DEST / name
        current = target.read_text(encoding="utf-8") if target.exists() else None
        if args.check:
            if current != text:
                stale.append(name)
            continue
        if current != text:
            target.write_text(text, encoding="utf-8", newline="\n")
        print(name, dashboard["uid"], ", ".join(changes))
    extra = sorted(path.name for path in DEST.glob("*.json") if path.name not in built)
    if extra:
        fail("dashboards-msp 에 생성기가 만들지 않은 파일이 있다: " + ", ".join(extra))
    if args.check:
        if stale:
            fail("로컬 MSP 화면이 최신이 아니다: " + ", ".join(stale))
        print("로컬 MSP 화면 7개와 시연 전용 판 1개가 최신이다")


if __name__ == "__main__":
    main()
