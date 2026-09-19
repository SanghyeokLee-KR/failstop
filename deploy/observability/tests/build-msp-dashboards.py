"""Build compact, native Grafana dashboards for customer operations."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
from pathlib import Path
import urllib.parse
import urllib.request

from msp_canvas import db_replication_panel, service_path_panel
from msp_queries import Q, SERVICE_PATH, DB_REPLICATION


ROOT = Path(__file__).resolve().parents[3]
DEST = ROOT / "harness/grafana/dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}
BLUE, TEAL, AMBER, RED = "blue", "green", "orange", "red"
PAGES = [("msp-overview", "01 · 서비스 관제"), ("msp-service", "02 · 트래픽·Spring"),
         ("msp-infra", "03 · AWS·서버"), ("msp-db", "04 · DB 관제"),
         ("msp-db-workload", "05 · SQL·세션"), ("msp-db-replication", "06 · 복제·복구")]
MISSING = {"type": "special", "options": {"match": "null+nan",
           "result": {"text": "미수집", "color": "text"}}}
STATUS = {"type": "value", "options": {
    "0": {"text": "▲ 장애", "color": RED}, "1": {"text": "● 정상", "color": TEAL}}}


def link(uid, title, suffix=""):
    return {"type": "link", "title": title, "url": "/d/" + uid + suffix,
            "targetBlank": False, "includeVars": True, "keepTime": True, "icon": "dashboard"}


def base(title, kind, x, y, w, h, description=""):
    return {"type": kind, "title": title, "description": description,
            "gridPos": {"x": x, "y": y, "w": w, "h": h}, "datasource": DS,
            "fieldConfig": {"defaults": {"noValue": "미수집", "mappings": [MISSING],
                "color": {"mode": "palette-classic"}, "unit": "short"}, "overrides": []}}


def targets(expressions, instant=False, table=False):
    if isinstance(expressions, str):
        expressions = [(expressions, "")]
    return [{"refId": chr(65 + i), "expr": expression, "legendFormat": legend,
             "instant": instant, "range": not instant, "format": "table" if table else "time_series",
             "editorMode": "code"} for i, (expression, legend) in enumerate(expressions)]


def thresholds(warning, critical=None):
    steps = [{"value": None, "color": TEAL}, {"value": warning, "color": AMBER}]
    if critical is not None:
        steps.append({"value": critical, "color": RED})
    return {"mode": "absolute", "steps": steps}


def stat(title, expression, x, y=0, w=6, h=3, unit="short", decimals=0, status=False,
         limits=None, description=""):
    panel = base(title, "stat", x, y, w, h, description)
    panel["targets"] = targets(expression, True)
    defaults = panel["fieldConfig"]["defaults"]
    defaults.update({"unit": unit, "decimals": decimals, "color": {"mode": "fixed", "fixedColor": BLUE}})
    if status:
        defaults["mappings"] = [STATUS, MISSING]
    if limits:
        defaults.update({"color": {"mode": "thresholds"}, "thresholds": thresholds(*limits)})
    panel["options"] = {"colorMode": "value", "graphMode": "none", "textMode": "value",
                        "justifyMode": "left", "orientation": "horizontal", "wideLayout": True,
                        "showPercentChange": False, "text": {"valueSize": 22 if h <= 2 else 30},
                        "reduceOptions": {"calcs": ["last"], "fields": "", "values": False}}
    return panel


def traffic_stat(title, key, x, unit, decimals=1, limits=None, description="", w=6):
    # No requests is a distinct display state; missing counters stay missing.
    expression = "(" + Q[key] + ") or (-1 + 0 * (" + Q["rps"] + " == 0))"
    if key == "success_latency_p95":
        expression += " or ((-2 + 0 * (" + Q["success_rps"] + " == 0)) and on() (" + Q["rps"] + " > 0))"
    panel = stat(title, expression, x, w=w, unit=unit, decimals=decimals, limits=limits,
                 description=description + " 요청이 없는 구간은 요청 없음. 수집 중단은 미수집. 실제 측정값 0과 구분.")
    panel["fieldConfig"]["defaults"]["mappings"].insert(0, {"type": "value", "options": {
        "-1": {"text": "요청 없음", "color": "text"}}})
    if key == "success_latency_p95":
        panel["fieldConfig"]["defaults"]["mappings"][0]["options"]["-2"] = {"text": "성공 응답 없음", "color": AMBER}
    return panel


def series(title, expressions, x, y, w=6, h=4, unit="short", limit=None, maximum=None, description=""):
    panel = base(title, "timeseries", x, y, w, h, description)
    panel["targets"] = targets(expressions)
    defaults = panel["fieldConfig"]["defaults"]
    defaults.update({"unit": unit, "min": 0, "custom": {
        "drawStyle": "line", "lineInterpolation": "linear", "lineWidth": 1.5,
        "fillOpacity": 8, "showPoints": "never", "spanNulls": False, "insertNulls": True,
        "axisPlacement": "auto", "axisLabel": "", "scaleDistribution": {"type": "linear"},
        "stacking": {"mode": "none", "group": "A"}, "thresholdsStyle": {"mode": "off"}}})
    if maximum is not None:
        defaults["max"] = maximum
    if limit is not None:
        defaults["thresholds"] = thresholds(limit)
        defaults["custom"]["thresholdsStyle"] = {"mode": "dashed"}
    panel["options"] = {"legend": {"displayMode": "list", "placement": "bottom", "calcs": []},
                        "tooltip": {"mode": "multi", "sort": "desc"}}
    palette = ["blue", "yellow", "purple", "green", "orange"]
    panel["fieldConfig"]["overrides"] = [{"matcher": {"id": "byFrameRefID", "options": target["refId"]},
        "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": palette[index % len(palette)]}}]}
        for index, target in enumerate(panel["targets"])] if len(panel["targets"]) > 1 else []
    return panel


def note(title, text, x, y, w, h):
    panel = base(title, "text", x, y, w, h)
    panel["options"] = {"mode": "markdown", "content": text}
    return panel


def stacked(panel):
    panel["fieldConfig"]["defaults"]["custom"].update({
        "stacking": {"mode": "normal", "group": "A"}, "fillOpacity": 22})
    return panel


def endpoint_series(title, expression, x, unit="reqps"):
    panel = series(title, [(expression, "{{method}} {{uri}}")], x, 7, unit=unit,
                   description="경로별 원래 집계를 유지한 표시명. 전체 메서드·URI는 아래 요청 경로 표와 쿼리 검사에서 확인.")
    names = [(r"GET /api/v1/orderbook", "호가"), (r"GET /api/v1/trades", "체결"),
             (r"GET /api/v1/candles/\{unit\}", "캔들"), (r"GET /api/v1/markets", "마켓"),
             (r"GET /api/v1/ticker", "현재가"), (r"GET /api/v1/accounts", "계정"),
             (r"GET /api/v1/orders", "주문 조회"), (r"POST /api/v1/orders", "주문 접수"),
             (r"DELETE /api/v1/orders/\{orderId\}", "주문 취소"), (r"GET UNKNOWN", "경로 미확인")]
    for pattern, title in names:
        panel["fieldConfig"]["overrides"].append({"matcher": {"id": "byRegexp", "options": "^" + pattern + "$"},
            "properties": [{"id": "displayName", "value": title}]})
    if unit == "ms":
        panel["description"] += " HTTP 2xx 히스토그램의 P95만 사용하며 성공 응답이 없는 경로는 미산정."
    return panel


def session_series(x, y, w=8, h=5):
    panel = stacked(series("세션 상태 · 전체 노드", [(Q["session_totals"], "{{session_state}}")],
                           x, y, w, h, description="exchange DB의 client backend 집계. 수집 계정만 제외하며 관리 세션 포함. active는 쿼리 실행 중이며 대기 포함. "
                           "active_no_wait는 관측 시 대기 이벤트 없음이며 CPU 사용량 또는 AAS가 아님."))
    states = [("active_no_wait", "실행", "blue"), ("active_waiting", "실행 대기", "orange"),
              ("idle", "유휴", "green"), ("idle_in_transaction", "트랜잭션 유휴", "yellow"),
              ("idle_in_transaction_aborted", "오류 유휴", "red"), ("other", "기타", "purple")]
    for state, title, color in states:
        panel["fieldConfig"]["overrides"].append({"matcher": {"id": "byName", "options": state},
            "properties": [{"id": "displayName", "value": title},
                           {"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]})
    return panel


def transactions(x, y, w=8, h=5):
    panel = series("TPS · 전체 노드", [("sum(" + Q["db_commit_tps"] + ")", "Commit"),
                   ("sum(" + Q["db_rollback_tps"] + ")", "Rollback")], x, y, w, h, unit="suffix:tx/s",
                   description="exchange DB 전체 노드 합계의 Commit / Rollback 처리율. 조회·앱·관측·관리 트랜잭션 포함. SQL 실행 횟수·주문 처리량과 구분.")
    panel["fieldConfig"]["overrides"][1]["properties"][0]["value"]["fixedColor"] = RED
    return panel


def db_roles(x, y, w=8, h=5):
    roles = {"type": "value", "options": {"0": {"text": "Primary · 쓰기", "color": TEAL},
              "1": {"text": "Standby · 복제", "color": BLUE}}}
    panel = table("DB 대상 · 현재 역할", Q["db_recovery"],
                  {"node": "등록 대상", "Value": "실제 역할"}, x, y, w, h, mappings=roles)
    panel["description"] = "등록 대상 라벨의 전체 이름. 실제 역할은 pg_is_in_recovery()로 판정. 수집 접속점은 쿼리 검사에서 확인. " \
                           "앱의 SQL 경로는 Primary이며 Standby 조회 분산 미적용. 호스트 IP·AZ를 임의 추정하지 않음."
    panel["fieldConfig"]["overrides"][-2]["properties"][0]["value"] = 122
    return panel


def table(title, expression, columns, x, y, w, h, unit="short", mappings=None):
    panel = base(title, "table", x, y, w, h)
    panel["targets"] = targets(expression, True, True)
    panel["transformations"] = [
        {"id": "filterFieldsByName", "options": {"include": {"names": list(columns)}}},
        {"id": "organize", "options": {"indexByName": {name: i for i, name in enumerate(columns)}, "renameByName": columns}},
    ]
    panel["fieldConfig"]["defaults"].update({"unit": unit, "custom": {
        "align": "auto", "minWidth": 50, "cellOptions": {"type": "auto"}, "filterable": False}})
    widths = {"method": 64, "role": 75, "severity": 65, "Value": 85}
    panel["fieldConfig"]["overrides"] = [{"matcher": {"id": "byName", "options": columns[key]},
        "properties": [{"id": "custom.width", "value": width}]}
        for key, width in widths.items() if key in columns]
    if mappings:
        panel["fieldConfig"]["overrides"].append({"matcher": {"id": "byName", "options": columns["Value"]},
            "properties": [{"id": "mappings", "value": [mappings, MISSING]},
                           {"id": "custom.cellOptions", "value": {"type": "color-text"}}]})
    panel["options"] = {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}}
    return panel


def alerts(x, y, w=12, h=4):
    expression = Q["alerts"] + ' or label_replace((' + Q["alert_count"] + ') == 0,"alertname","진행 경보 없음","",".*")'
    panel = table("진행 경보 · 고객 라벨", expression,
                  {"severity": "등급", "node": "대상", "alertname": "경보", "instance": "접속점"}, x, y, w, h)
    panel["description"] = "선택 고객·환경·역할 라벨이 있는 경보. 미분류 경보는 기본 경고 메뉴에서 별도 확인."
    panel["links"] = [{"title": "전체 경보 · 기본 메뉴", "url": "/alerting/list", "targetBlank": False}]
    if h <= 3:
        panel["options"]["showHeader"] = False
    return panel


def timeline(title, expressions, x, y, w=12, h=4):
    panel = base(title, "state-timeline", x, y, w, h, "회색·빈 구간은 미수집. 수집 응답과 실제 서비스 상태의 구분.")
    panel["targets"] = targets(expressions)
    panel["fieldConfig"]["defaults"].update({"mappings": [STATUS, MISSING],
        "custom": {"lineWidth": 0, "fillOpacity": 75, "spanNulls": False, "insertNulls": True}})
    panel["options"] = {"mergeValues": True, "showValue": "never", "alignValue": "center", "rowHeight": 0.72,
                        "legend": {"showLegend": False}, "tooltip": {"mode": "single", "sort": "none"}}
    return panel


def path():
    return service_path_panel(SERVICE_PATH, y=3, h=4)


def dashboard(uid, title, panels):
    for index, panel in enumerate(panels, 1):
        panel["id"] = index
    return {"uid": uid, "title": title, "description": "고객 서비스 관제 · 실측 자료 · 미수집 분리 · 운영자 판단",
            "tags": ["MSP", "customer-operations"], "schemaVersion": 39, "version": 1,
            "editable": False, "timezone": "Asia/Seoul", "refresh": "15s",
            "time": {"from": "now-1h", "to": "now"}, "timepicker": {"refresh_intervals": ["15s", "30s", "1m", "5m"]},
            "links": [link(*page) for page in PAGES] + [
                {**link(uid, "다크", "?theme=dark"), "targetBlank": True},
                {**link(uid, "라이트", "?theme=light"), "targetBlank": True}],
            "templating": {"list": [
                {"name": "customer", "label": "고객사", "type": "query", "datasource": DS, "refresh": 1,
                 "query": 'label_values(up{role=~"app|reader|data|standby|probe"}, customer)',
                 "current": {"text": "exchange", "value": "exchange"}},
                {"name": "environment", "label": "환경", "type": "query", "datasource": DS, "refresh": 1,
                 "query": 'label_values(up{customer="$customer",role=~"app|reader|data|standby|probe"}, environment)',
                 "current": {"text": "local-lab", "value": "local-lab"}},
            ]}, "annotations": {"list": [{"name": "고객 경보", "datasource": DS, "enable": True, "hide": True,
                "iconColor": "red", "expr": Q["alerts"], "titleFormat": "{{alertname}}", "textFormat": "{{node}} · {{severity}}"}]},
            "panels": panels}


def overview():
    panels = [
        stat("성공 응답 · 2xx", Q["success_rps"], 0, w=4, unit="reqps", decimals=2),
        stat("요청 제한 · 429", Q["rate_limit_rps"], 4, w=4, unit="reqps", decimals=2, limits=(0.01, 5)),
        traffic_stat("5xx 오류율", "error_rate", 8, "percent", 2, (1, 5), w=5),
        traffic_stat("성공 응답 시간 · P95", "success_latency_p95", 13, "ms", 1, (500, 1000),
                     description="HTTP 2xx만 산정. 요청 제한 응답을 성공 지연에서 제외."),
        stat("진행 경보", Q["alert_count"], 19, w=5, limits=(1, 3), description="고객·환경·역할 라벨이 있는 경보. 미분류 경보는 기본 경고 메뉴 확인."), path(),
        series("전체 응답 · 인스턴스", [(Q["instance_rps"], "{{node}}")], 0, 7, h=5, unit="reqps",
               description="전체 HTTP 응답. 성공·요청 제한·오류 포함. 상단 성공 응답 및 429와 함께 비교."),
        series("응답 시간 · P95", [(Q["success_latency_p95"], "2xx P95"), (Q["latency_p95"], "전체 P95")], 6, 7, h=5, unit="ms",
               description="동일 구간의 성공 응답 P95와 전체 응답 P95. 빠른 429가 전체 지연을 낮추는 구간의 식별."),
        series("DB 복제 · 재생 지연량", [(Q["wal_gap"], "{{node}} → {{application_name}}")], 12, 7, h=5, unit="bytes"),
        series("CPU / JVM · 최대", [("max(" + Q["app_cpu"] + ")", "CPU"), ("max(" + Q["app_memory"] + ")", "JVM")], 18, 7, h=5, unit="percent", maximum=100,
               description="선택 고객·환경의 수집 성공 노드 중 지표별 최대값. CPU와 JVM의 최대 대상은 다를 수 있음. JVM은 사용량 / committed이며 호스트 전체 메모리와 구분. 노드별 추이는 Spring 화면에서 확인."),
        timeline("서비스 가용성 · 장애 구간", [(Q["probe_up"], "외부 접속"), (Q["app_serviceable"], "{{node}} 서비스"), (Q["db_up"], "{{node}} DB")], 0, 12, h=5),
        alerts(12, 12, h=5),
        stat("등록 대상", Q["target_count"], 0, 17, 4, 2),
        stat("수집 응답률", Q["collection_ratio"], 4, 17, 4, 2, unit="percent", decimals=0),
        note("", "**관제 기준** · 고객 자원 / 미수집·장애 구분  \n**사람 개입** · DB 격리·승격·접속 전환", 8, 17, 16, 2),
    ]
    return dashboard(*PAGES[0], panels)


def service():
    codes = stacked(series("HTTP 응답 코드 · 전체 응답", [(Q["http_codes"], "{{code}}")], 18, 7, unit="reqps"))
    for code, color in [("2xx", TEAL), ("3xx", BLUE), ("4xx", AMBER), ("5xx", RED)]:
        codes["fieldConfig"]["overrides"].append({"matcher": {"id": "byName", "options": code},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]})
    panels = [
        stat("성공 응답 · 2xx", Q["success_rps"], 0, unit="reqps", decimals=2,
             description="실측 HTTP 2xx 응답. 리다이렉트·요청 제한·서버 오류를 성공 처리량에서 제외."),
        stat("요청 제한 · HTTP 429", Q["rate_limit_rps"], 6, unit="reqps", decimals=2, limits=(0.01, 5),
             description="요청 제한에 의한 거절. 서버 오류율 및 성공 응답과 별도 관측."),
        traffic_stat("서버 오류율 · 5xx", "error_rate", 12, "percent", 2, (1, 5)),
        traffic_stat("성공 응답 시간 · P95", "success_latency_p95", 18, "ms", description="2xx 응답만 산정. 빠른 429 응답에 의한 지연 과소평가 방지."), path(),
        endpoint_series("성공 응답 · 경로별", Q["endpoint_success_rps"], 0),
        endpoint_series("HTTP 429 · 경로별", Q["endpoint_rejected_rps"], 6),
        endpoint_series("성공 P95 · 경로별", Q["endpoint_success_latency_p95"], 12, "ms"), codes,
        series("CPU / Heap 사용률", [(Q["app_cpu"], "{{node}} CPU"), (Q["jvm_heap"], "{{node}} Heap")], 0, 11, unit="percent", maximum=100,
               description="CPU는 Spring 프로세스. Heap은 사용량 / committed이며 최대 힙 한도 대비 비율과 구분."),
        series("JVM GC 시간 비율", [(Q["gc_time"], "{{node}} {{gc}}")], 6, 11, unit="percent"),
        series("활성 스레드", [(Q["threads"], "{{node}}")], 12, 11),
        series("DB 연결 풀 · 사용 / 한도", [(Q["hikari_active"], "{{node}} 사용"), (Q["hikari_max"], "{{node}} 한도")], 18, 11),
        table("인스턴스 · 요청 수용 상태", Q["app_serviceable"], {"node": "인스턴스", "role": "등록 역할", "Value": "상태"}, 0, 15, 8, 4, mappings=STATUS),
        table("전체 응답 · 경로별 누적 요청", "topk(5," + Q["requests_by_uri"] + ")", {"method": "메서드", "uri": "경로", "Value": "요청 수"}, 8, 15, 8, 4),
        timeline("요청 수용 · 인스턴스 이력", [(Q["app_serviceable"], "{{node}}")], 16, 15, 8, 4),
    ]
    return dashboard(*PAGES[1], panels)


def databases():
    panels = [
        stat("Primary · 단일 구성", Q["primary_topology_status"], 0, status=True),
        stat("연결 사용률 · 최대", "max(" + Q["db_utilization"] + ")", 6, unit="percent", decimals=1, limits=(70, 85)),
        stat("차단 세션", "sum(" + Q["blocked_sessions"] + ")", 12, limits=(1, 5),
             description="다른 세션의 잠금 해제를 기다리는 세션. 수집 계정 제외·관리 세션 포함. 보유 잠금의 총개수와 구분."),
        stat("복제 지연량 · WAL", "max(" + Q["wal_gap"] + ")", 18, unit="bytes", limits=(104857600, 268435456)),
        db_replication_panel(DB_REPLICATION, y=3, w=8, h=5), db_roles(8, 3), session_series(16, 3),
        transactions(0, 8, 6),
        series("SQL 실행량", [(Q["sql_calls_overview"], "{{sql_group}}")], 6, 8, 6, 5, unit="suffix:calls/s",
               description="exchange DB 전체 노드의 수집 계정 제외 완료 호출. SELECT / 변경(INSERT·UPDATE·DELETE·MERGE) / 기타(WITH·OTHER). 관리 SQL 포함. SELECT의 부수효과 가능성은 별도 확인."),
        series("SQL 평균 시간", [(Q["sql_mean_overall"], "유효 표본")], 12, 8, 6, 5, unit="ms",
               description="exchange DB의 유효한 노드·구간 실행 시간 합 / 완료 호출 수. 실행 수로 가중한 평균이며 초기화·통계 퇴출·수집 오류 표본은 제외. 일부 노드 제외 시 전체 노드 평균을 보장하지 않음. API 응답 시간·P95와 구분하며 최근 호출이 없는 구간은 미산정."),
        series("DB 연결 사용률", [(Q["db_utilization"], "{{node}}")], 18, 8, 6, 5, unit="percent", limit=80, maximum=100),
        series("복제 지연량 · 전송 / 재생", [(Q["wal_gap"], "{{node}} → {{application_name}}")], 0, 13, 8, 4, unit="bytes", limit=104857600),
        timeline("DB 접속 · 장애 구간", [(Q["db_up"], "{{node}}")], 8, 13, 8, 4), alerts(16, 13, 8, 4),
        note("", "**진단 순서** · SQL 부하 → 세션 대기·차단 → 복제 지연  \n**사람 개입** · 차단 원인·장기 트랜잭션 확인 / DB 격리·승격·접속 전환", 0, 17, 24, 2),
    ]
    for panel in panels:
        if panel["title"].startswith(("SQL", "세션", "차단", "트랜잭션", "DB 연결")):
            panel["links"] = [link(*PAGES[4])]
        elif panel["title"].startswith(("복제", "Primary", "DB 복제")):
            panel["links"] = [link(*PAGES[5])]
    return dashboard(*PAGES[3], panels)


def database_workload():
    available = stat("SQL 통계 수집", Q["sql_collection_status"], 0, 17, 4, 2)
    available["options"]["text"]["valueSize"] = 22
    available["options"]["justifyMode"] = "center"
    available["fieldConfig"]["defaults"]["mappings"].insert(0, {"type": "value", "options": {
        "0": {"text": "미설정", "color": AMBER}, "1": {"text": "수집", "color": TEAL}}})
    panels = [
        stat("SQL 실행량 · 수집 계정 제외", "sum(" + Q["sql_calls"] + ")", 0, unit="suffix:calls/s", decimals=2),
        stat("차단 세션", "sum(" + Q["blocked_sessions"] + ")", 6, limits=(1, 5)),
        stat("최장 트랜잭션", "max(" + Q["longest_transaction_seconds"] + ")", 12, unit="s", limits=(60, 300),
             description="수집 계정을 제외한 client backend의 최장 트랜잭션 경과 시간. 색상 60초·300초는 검토 기준이며 SLA·장애 알림 정책이 아님."),
        stat("트랜잭션 유휴 · 최장", "max(" + Q["oldest_idle_transaction_seconds"] + ")", 18, unit="s", limits=(30, 120),
             description="트랜잭션을 닫지 않은 유휴 상태의 최장 경과 시간. 색상 30초·120초는 검토 기준이며 SLA·장애 알림 정책이 아님."),
        stacked(series("SQL 실행량 · 명령별", [(Q["sql_calls_by_command"], "{{command}}")], 0, 3, 12, 5, unit="suffix:calls/s",
                       description="exchange DB 전체 노드 합계의 pg_stat_statements 완료 호출. 수집 계정 제외·관리 SQL 포함. SQL 원문·인자·queryid 미노출. SELECT의 부수효과 가능성 및 WITH 별도 분류.")),
        series("SQL 평균 시간", [(Q["sql_mean_by_command"], "{{command}}")], 12, 3, 12, 5, unit="ms",
               description="exchange DB의 유효한 노드·구간에서 명령별 실행 수로 가중한 평균. 초기화·통계 퇴출·수집 오류 표본은 제외되므로 일부 노드 제외 시 전체 노드 평균을 보장하지 않음. P95·진행 중 쿼리 시간과 구분하며 최근 실행이 없는 명령은 미산정."),
        session_series(0, 8),
        stacked(series("실행 대기 · 전체 노드", [(Q["session_wait_totals"], "{{wait_event_type}}")], 8, 8, 8, 5,
                       description="exchange DB 전체 노드에서 active이며 wait_event_type이 있는 세션. Lock·IO·기타 유형별 합계. 수집 계정 제외·관리 세션 포함. 유휴 ClientRead 제외.")),
        series("차단 세션", [("sum(" + Q["blocked_sessions"] + ")", "전체 노드")], 16, 8, 4, 5),
        series("최장 잠금 대기", [("max(" + Q["longest_lock_wait_seconds"] + ")", "최장")], 20, 8, 4, 5, unit="s"),
        series("버퍼 캐시 적중률", [(Q["db_cache_hit"], "{{node}}")], 0, 13, unit="percent", maximum=100,
               description="PostgreSQL 버퍼 캐시 hit / (hit + read). OS 캐시·디스크 지연과 구분. 읽기 없는 구간은 미산정."),
        series("임시 파일 생성량", [(Q["db_temp_bytes"], "{{node}}")], 6, 13, unit="Bps",
               description="정렬·해시 작업 등이 기록한 DB 임시 파일의 바이트 증가량."),
        series("교착 발생률", [(Q["db_deadlocks"], "{{node}}")], 12, 13, unit="suffix:건/s",
               description="해결된 deadlock 누적 건수의 초당 증가량. 현재 차단 세션과 구분."),
        series("처리 행", [("sum(" + Q["db_read_rows"] + ")", "스캔"), ("sum(" + Q["db_changed_rows"] + ")", "변경")], 18, 13, unit="suffix:rows/s",
               description="exchange DB 전체 노드 합계. 스캔은 순차 스캔 행과 인덱스 스캔 항목(pg_stat_database.tup_returned). 변경은 INSERT+UPDATE+DELETE 행. SQL 결과 행 수·호출 횟수와 구분."),
        available,
        note("", "**SQL·세션** · 수집 계정 제외 / 관리 SQL 포함　|　**TPS·I/O** · exchange DB 전체  \n**우선 확인** · 차단 세션 → 대기 유형 → 장기·유휴 트랜잭션 → 임시 파일 증가", 4, 17, 20, 2),
    ]
    waits = next(panel for panel in panels if panel["title"] == "실행 대기 · 전체 노드")
    for name, title, color in [("Lock", "잠금", AMBER), ("IO", "I/O", BLUE), ("Other", "기타", "purple")]:
        waits["fieldConfig"]["overrides"].append({"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "displayName", "value": title},
                           {"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]})
    return dashboard(*PAGES[4], panels)


def database_replication():
    panels = [
        stat("복제 연결 · Streaming", Q["replication_status"], 0, status=True,
             description="Primary의 pg_stat_replication 상태. 연결 정상만으로 무손실 또는 복구 완료 판정 불가."),
        stat("WAL 생성량", "sum(" + Q["wal_generation_rate"] + ")", 6, unit="Bps", decimals=1,
             description="Primary pg_stat_wal.wal_bytes의 초당 증가량. LSN 위치 차분·복제 지연량과 구분."),
        stat("복제 지연량 · 재생", "max(" + Q["wal_gap"] + ")", 12, unit="bytes", limits=(104857600, 268435456)),
        stat("백업 성공 · 경과 시간", "time() - max(" + Q["backup_timestamp"] + ")", 18, unit="s", limits=(93600, 172800),
             description="선택 고객 DB에 연결된 백업 수집만 사용. 백업 성공이 실제 복원 검증을 뜻하지 않음."),
        db_replication_panel(DB_REPLICATION, y=3, w=8, h=5), db_roles(8, 3),
        timeline("DB 접속 / 복제 연결", [(Q["db_up"], "{{node}} 접속"), (Q["replication_status"], "Streaming")], 16, 3, 8, 5),
        series("WAL 생성량 · Primary", [(Q["wal_generation_rate"], "{{node}}")], 0, 8, 6, 5, unit="Bps",
               description="pg_stat_wal.wal_bytes 증가율. 업무 변경뿐 아니라 유지보수 등 모든 WAL 포함."),
        series("복제 지연량 · 전송 대기", [(Q["wal_send_gap"], "{{node}} → {{application_name}}")], 6, 8, 6, 5, unit="bytes",
               description="Primary 현재 WAL 위치 − Standby로 전송한 위치. 네트워크 지연 시간과 구분."),
        series("복제 지연량 · 기록 대기", [(Q["wal_flush_gap"], "{{node}} → {{application_name}}")], 12, 8, 6, 5, unit="bytes",
               description="Standby가 write한 WAL 위치 − 디스크에 flush한 위치. 내구성 확보 전 대기량이며 네트워크 지연과 구분."),
        series("복제 지연량 · 적용 대기", [(Q["wal_replay_gap"], "{{node}} → {{application_name}}")], 18, 8, 6, 5, unit="bytes",
               description="Standby flush 위치 − replay 위치. 기록된 WAL의 재생 대기량이며 실제 데이터 유실량이 아님."),
        series("복제 지연 시간 · Replay", [(Q["replication_lag"], "{{node}} → {{application_name}}")], 0, 13, 8, 4, unit="s", limit=5,
               description="pg_stat_replication.replay_lag. 유휴 시 NULL은 미산정. 마지막 트랜잭션의 경과 시간 또는 RPO와 구분."),
        series("백업 성공 · 경과 시간", [("time() - (" + Q["backup_timestamp"] + ")", "{{node}}")], 8, 13, 8, 4, unit="s", limit=93600,
               description="고객 백업 지표 부재는 미수집. 예약 실행·실제 복원·불변식 검증은 별도 확인."),
        note("DB 복구 · 사람 개입", "**① Primary 격리** · 쓰기 차단  \n**② 수동 승격** · WAL 유실 위험 확인  \n**③ 접속 전환** · 앱·원장 확인  \n**④ 재편입·복원** · RTO/RPO 대조", 16, 13, 8, 4),
        note("", "**복제 경로** · 생성 → 전송 → 디스크 기록 → 재생　|　**비동기 복제** · 미복제 데이터 유실 가능성  \n**복구 완료 조건** · 앱 요청 회복 + 원장·ACK 대조 + 새 Standby 복제　|　자동 DB 승격 미적용", 0, 17, 24, 2),
    ]
    return dashboard(*PAGES[5], panels)


def cloudwatch_row():
    panels = [note("AWS 연결 · 배포 설정", "**질의 비활성** · AWS 계정·권한 및 고객별 자원 매핑 적용 전  \n인스턴스 역할·CloudWatch 데이터 소스·ALB/조회 ASG/EBS 등록 및 선택 고객·환경과의 일치 확인 후 활성화  \n리전 `$aws_region` · ALB `$aws_alb` · 조회 ASG `$aws_asg` · EBS `$aws_ebs_volume`", 0, 20, 24, 3)]
    specifications = [
        ("ALB 요청 · 분당", "AWS/ApplicationELB", "RequestCount", "Sum", "LoadBalancer", "$aws_alb", "short"),
        ("ALB 대상 응답 시간 · P95", "AWS/ApplicationELB", "TargetResponseTime", "p95", "LoadBalancer", "$aws_alb", "s"),
        ("ALB 비정상 대상 · 최대", "AWS/ApplicationELB", "UnHealthyHostCount", "Maximum", "LoadBalancer", "$aws_alb", "short"),
        ("조회 ASG · 가동 인스턴스", "AWS/AutoScaling", "GroupInServiceInstances", "Average", "AutoScalingGroupName", "$aws_asg", "short"),
        ("대상 5xx · 분당", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "Sum", "LoadBalancer", "$aws_alb", "short"),
        ("EBS 대기열 · 평균", "AWS/EBS", "VolumeQueueLength", "Average", "VolumeId", "$aws_ebs_volume", "short"),
    ]
    for index, (title, namespace, metric, statistic, dimension, resource, unit) in enumerate(specifications):
        panel = series(title, [], index % 3 * 8, 23 + index // 3 * 5, 8, 5, unit)
        panel["datasource"] = {"type": "cloudwatch", "uid": "cloudwatch"}
        dimensions = {dimension: [resource]}
        if metric == "UnHealthyHostCount":
            dimensions["TargetGroup"] = ["$aws_reader_target_group"]
        panel["targets"] = [{"refId": "A", "hide": True, "queryMode": "Metrics", "metricQueryType": 0, "metricEditorMode": 0,
            "namespace": namespace, "metricName": metric, "statistic": statistic, "period": "60", "region": "$aws_region",
            "dimensions": dimensions, "matchExact": True, "id": "", "expression": ""}]
        panels.append(panel)
    for index, panel in enumerate(panels, 1001):
        panel["id"] = index
    return {"id": 1000, "type": "row", "title": "CloudWatch · AWS 수집 설정 · 연결 전", "collapsed": True,
            "gridPos": {"x": 0, "y": 19, "w": 24, "h": 1}, "panels": panels}


def infrastructure():
    host_up = Q["availability"].replace('job=~"exchange-api|linux-host|postgres|blackbox-http"', 'job="linux-host"')
    panels = [
        stat("고객 호스트 · 등록 수", Q.get("host_count", "count(" + host_up + ")"), 0),
        stat("CPU 사용률 · 최대", "max(" + Q["host_cpu"] + ")", 6, unit="percent", decimals=1, limits=(70, 90)),
        stat("메모리 사용률 · 최대", "max(" + Q["host_memory"] + ")", 12, unit="percent", decimals=1, limits=(80, 90)),
        stat("디스크 사용률 · 최대", "max(" + Q["host_disk"] + ")", 18, unit="percent", decimals=1, limits=(80, 90)), path(),
        series("CPU 사용률 · 고객 호스트", [(Q["host_cpu"], "{{node}}")], 0, 7, unit="percent", maximum=100),
        series("메모리 사용률", [(Q["host_memory"], "{{node}}")], 6, 7, unit="percent", maximum=100),
        series("파일시스템 사용률", [(Q["host_disk"], "{{node}} {{mountpoint}}")], 12, 7, unit="percent", limit=80, maximum=100),
        series("inode 사용률", [(Q["host_inode"], "{{node}} {{mountpoint}}")], 18, 7, unit="percent", limit=80, maximum=100),
        series("네트워크 수신", [(Q["host_network_rx"], "{{node}} {{device}}")], 0, 11, unit="Bps"),
        series("네트워크 송신", [(Q["host_network_tx"], "{{node}} {{device}}")], 6, 11, unit="Bps"),
        series("디스크 I/O 활성 시간", [(Q["host_io"], "{{node}} {{device}}")], 12, 11, unit="percent"),
        timeline("호스트 수집 · 응답 이력", [(host_up, "{{node}}")], 18, 11, 6, 4),
        table("수집 대상 · 고객 자원", Q["availability"], {"node": "대상", "role": "등록 역할", "job": "수집 경로", "Value": "응답"}, 0, 15, 12, 4, mappings=STATUS),
        note("AWS 운영 · 수집 범위", "**현재 환경** · `$environment`　|　**AWS 배포 대상** · 서울 리전 / 2 AZ  \n**고객 자원** · Spring·조회 API·PostgreSQL·등록 Linux 서버·접속 검사  \n**미등록 자원** · 미수집　|　**확장 범위** · 조회 API ASG　|　**DB 승격** · 수동", 12, 15, 12, 4),
    ]
    result = dashboard(*PAGES[2], panels)
    result["panels"].append(cloudwatch_row())
    for name, label, default in [("aws_region", "AWS 리전", "ap-northeast-2"), ("aws_alb", "ALB", "UNCONFIGURED"),
                                 ("aws_asg", "조회 ASG", "UNCONFIGURED"), ("aws_reader_target_group", "조회 대상 그룹", "UNCONFIGURED"),
                                 ("aws_ebs_volume", "EBS 볼륨", "UNCONFIGURED")]:
        result["templating"]["list"].append({"name": name, "label": label, "type": "textbox", "hide": 2, "query": default,
            "current": {"text": default, "value": default}, "skipUrlSync": False})
    return result


def all_dashboards():
    return {"10-msp-overview.json": overview(), "11-msp-service.json": service(),
            "12-msp-infra.json": infrastructure(), "13-msp-db.json": databases(),
            "14-msp-db-workload.json": database_workload(), "15-msp-db-replication.json": database_replication()}


def validate_live(dashboards, url):
    def inspect(item):
        name, panel, target = item
        query = target["expr"].replace("$customer", "exchange").replace("$environment", "local-lab")
        query = query.replace("$__rate_interval", "5m").replace("$__range", "1h")
        endpoint = url.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": query})
        with urllib.request.urlopen(endpoint, timeout=20) as response:
            result = json.load(response)
        if result.get("status") != "success":
            raise RuntimeError(name + ": " + panel["title"] + ": query failed")
        values = result["data"]["result"]
        return {"dashboard": name, "panel": panel["title"], "refId": target["refId"], "series": len(values),
                "finite_series": sum(math.isfinite(float(item["value"][1])) for item in values)}
    work = [(name, panel, target) for name, data in dashboards.items() for panel in data["panels"]
            for target in panel.get("targets", []) if "expr" in target]
    with ThreadPoolExecutor(max_workers=4) as pool:
        outcomes = list(pool.map(inspect, work))
    return {"validated_queries": len(outcomes), "with_data": sum(item["finite_series"] > 0 for item in outcomes), "results": outcomes}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--prometheus", help="Read-only query validation URL")
    parser.add_argument("--output", type=Path, help="Sanitized query evidence JSON")
    args = parser.parse_args()
    dashboards = all_dashboards()
    if args.write:
        for filename, data in dashboards.items():
            (DEST / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Updated six native MSP dashboards.")
    if args.prometheus:
        evidence = validate_live(dashboards, args.prometheus)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: value for key, value in evidence.items() if key != "results"}))
    if not args.write and not args.prometheus:
        parser.error("choose --write or --prometheus")
