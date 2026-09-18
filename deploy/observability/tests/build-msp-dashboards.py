"""Build the four provisioned MSP dashboards from one visual and query contract."""

import argparse
import json
import math
from pathlib import Path
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[3]
DEST = ROOT / "harness/grafana/dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}
SCOPE = 'customer="$customer",environment="$environment",role=~"app|reader|data|standby|probe"'
APP = SCOPE + ',job="exchange-api"'
DB = SCOPE + ',job="postgres"'
HOST = SCOPE + ',job="linux-host"'
HTTP = APP + ',uri!~"/actuator(/.*)?"'
BLUE, TEAL, AMBER, RED, SLATE = "#6AA7EF", "#57BFA3", "#E5B35F", "#E36C78", "#94A3B8"


def metric(name, labels=SCOPE):
    return name + "{" + labels + "}"


def rate(name, labels=SCOPE, window="$__rate_interval"):
    return "rate(" + metric(name, labels) + "[" + window + "])"


RPS = "sum(" + rate("http_server_requests_seconds_count", HTTP) + ")"
HTTP_ERRORS = "sum(" + rate("http_server_requests_seconds_count", HTTP + ',status=~"5.."') + ")"
ERROR_RATE = "100 * (" + HTTP_ERRORS + " or (0 * " + RPS + ")) / (" + RPS + " > 0)"
MEAN_LATENCY = "1000 * sum(" + rate("http_server_requests_seconds_sum", HTTP) + ") / (" + RPS + " > 0)"
P95 = "1000 * histogram_quantile(0.95, sum by(le)(" + rate("http_server_requests_seconds_bucket", HTTP) + "))"
UP = metric("up", SCOPE + ',job=~"exchange-api|linux-host|postgres|blackbox-http"')
ALERTS = metric("ALERTS", SCOPE + ',component!="reporting",alertstate="firing"')
ALERT_COUNT = "sum(" + ALERTS + ") or (0 * count(" + UP + "))"
DB_UTIL = "100 * sum by(node,instance)(" + metric("pg_stat_activity_count", DB) + ") / on(node,instance) max by(node,instance)(" + metric("pg_settings_max_connections", DB) + ")"
CPU = "100 * (1 - avg by(node)(" + rate("node_cpu_seconds_total", HOST + ',mode="idle"') + "))"
MEMORY = "100 * (1 - " + metric("node_memory_MemAvailable_bytes", HOST) + " / " + metric("node_memory_MemTotal_bytes", HOST) + ")"
FS = HOST + ',fstype!~"tmpfs|overlay|squashfs|9p",mountpoint!~"/run.*|/var/lib/docker.*"'
DISK = "100 * (1 - " + metric("node_filesystem_avail_bytes", FS) + " / " + metric("node_filesystem_size_bytes", FS) + ")"
INODES = "100 * (1 - " + metric("node_filesystem_files_free", FS) + " / " + metric("node_filesystem_files", FS) + ")"
WAL_GAP = "max(" + metric("pg_replication_client_sent_replay_bytes", DB) + ")"
APP_HEAP = "100 * sum by(node)(" + metric("jvm_memory_used_bytes", APP + ',area="heap"') + ") / sum by(node)(" + metric("jvm_memory_max_bytes", APP + ',area="heap"') + ")"


def link(uid, title):
    return {"title": title, "url": "/d/" + uid + "?${__url_time_range}&var-customer=${customer}&var-environment=${environment}", "targetBlank": False}


def base(title, kind, x, y, w, h, description=""):
    return {"type": kind, "title": title, "description": description,
            "gridPos": {"x": x, "y": y, "w": w, "h": h}, "datasource": DS,
            "fieldConfig": {"defaults": {"noValue": "자료 없음", "mappings": [{"type": "special", "options": {"match": "null+nan", "result": {"text": "자료 없음", "color": SLATE}}}],
                                          "color": {"mode": "palette-classic"}, "unit": "short"}, "overrides": []}}


def targets(expressions, instant=False, table=False):
    if isinstance(expressions, str):
        expressions = [(expressions, "")]
    return [{"refId": chr(65 + i), "expr": expr, "legendFormat": legend, "instant": instant,
             "range": not instant, "format": "table" if table else "time_series", "editorMode": "code"}
            for i, (expr, legend) in enumerate(expressions)]


def thresholds(values):
    return {"mode": "absolute", "steps": [{"color": color, "value": value} for value, color in values]}


def stat(title, expression, x, y=2, w=4, h=4, unit="short", description="", colors=None, mapping=None, to=None, no_value="자료 없음", decimals=0):
    panel = base(title, "stat", x, y, w, h, description)
    panel["targets"] = targets(expression, True)
    defaults = panel["fieldConfig"]["defaults"]
    defaults.update({"unit": unit, "decimals": decimals, "noValue": no_value, "color": {"mode": "fixed", "fixedColor": BLUE}})
    defaults["mappings"][0]["options"]["result"]["text"] = no_value
    if colors:
        defaults.update({"color": {"mode": "thresholds"}, "thresholds": thresholds(colors)})
    if mapping:
        defaults["mappings"].insert(0, {"type": "value", "options": {str(v): {"text": text, "color": color, "index": i} for i, (v, text, color) in enumerate(mapping)}})
    if to:
        defaults["links"] = [link(*to)]
    panel["options"] = {"colorMode": "value", "graphMode": "none", "textMode": "value", "justifyMode": "center",
                        "orientation": "horizontal", "wideLayout": True, "showPercentChange": False,
                        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "text": {"valueSize": 30}}
    return panel


def status(title, expression, x, y=2, w=4, description="", to=None, good="정상", bad="장애"):
    return stat(title, expression, x, y, w, description=description,
                mapping=[(0, bad, RED), (1, good, TEAL)], to=to)


def series(title, expressions, x, y, w=12, h=7, unit="short", description="", colors=None, max_value=None):
    panel = base(title, "timeseries", x, y, w, h, description)
    panel["targets"] = targets(expressions)
    defaults = panel["fieldConfig"]["defaults"]
    defaults.update({"unit": unit, "min": 0, "custom": {"drawStyle": "line", "lineInterpolation": "linear", "lineWidth": 2,
        "fillOpacity": 8, "showPoints": "never", "spanNulls": False, "insertNulls": False,
        "axisPlacement": "auto", "axisLabel": "", "axisColorMode": "text", "axisBorderShow": False,
        "scaleDistribution": {"type": "linear"}, "hideFrom": {"tooltip": False, "viz": False, "legend": False},
        "thresholdsStyle": {"mode": "off"}, "stacking": {"mode": "none", "group": "A"}}})
    if max_value:
        defaults["max"] = max_value
    for name, color in (colors or {}).items():
        panel["fieldConfig"]["overrides"].append({"matcher": {"id": "byName", "options": name}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]})
    panel["options"] = {"tooltip": {"mode": "multi", "sort": "desc"},
                        "legend": {"showLegend": True, "displayMode": "list", "placement": "bottom", "calcs": ["lastNotNull"]}}
    return panel


def note(title, content, x, y, w=24, h=2):
    return {"type": "text", "title": title, "transparent": True, "gridPos": {"x": x, "y": y, "w": w, "h": h},
            "options": {"mode": "markdown", "content": content}}


def header(section, subtitle):
    paths = {"서비스 관제": "외부 접속 → Spring → Primary → Standby",
             "트래픽 · SPRING": "요청 · 지연 · 오류 · 조회 노드 증감",
             "AWS · 서버": "서울 리전 · 2 AZ 운영 구성",
             "DB · 복구": "비동기 복제 · 수동 승격"}
    panel = note("", "**FAILSTOP / " + section + "**　 ·　**${customer}**　 ·　**${environment}**　 |　" + paths[section], 0, 0)
    panel["description"] = subtitle
    return panel


def table(title, expression, fields, x, y, w=12, h=6, description=""):
    panel = base(title, "table", x, y, w, h, description)
    panel["targets"] = targets(expression, True, True)
    panel["options"] = {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}, "sortBy": []}
    panel["fieldConfig"]["defaults"]["custom"] = {"align": "left", "cellOptions": {"type": "auto"}, "inspect": False}
    panel["transformations"] = [
        {"id": "filterFieldsByName", "options": {"include": {"names": list(fields)}}},
        {"id": "organize", "options": {"indexByName": {name: i for i, name in enumerate(fields)}, "renameByName": fields}},
    ]
    return panel


def alerts_table(x, y, w=12, h=6):
    panel = table("진행 경보", "max by(severity,alertname,role,node)(" + ALERTS + ")",
                  {"severity": "등급", "role": "역할", "alertname": "경보", "node": "대상"}, x, y, w, h,
                  "선택 고객·환경의 firing 경보. 수집 대상이 없거나 경보 조회에 실패한 경우 정상 판정 제외.")
    panel["fieldConfig"]["overrides"] = [
        {"matcher": {"id": "byName", "options": "등급"}, "properties": [
            {"id": "custom.width", "value": 85}, {"id": "custom.cellOptions", "value": {"type": "color-text"}},
            {"id": "mappings", "value": [{"type": "value", "options": {"critical": {"text": "긴급", "color": RED}, "warning": {"text": "주의", "color": AMBER}}}]}]},
        {"matcher": {"id": "byName", "options": "역할"}, "properties": [{"id": "custom.width", "value": 100},
            {"id": "mappings", "value": [{"type": "value", "options": {"data": {"text": "Primary"}, "standby": {"text": "Standby"},
                "app": {"text": "Spring"}, "reader": {"text": "조회"}, "probe": {"text": "접속"}}}]}]},
        {"matcher": {"id": "byName", "options": "경보"}, "properties": [{"id": "mappings", "value": [{"type": "value", "options": {
            "DatabaseConnectionFailed": {"text": "DB 연결 장애"},
            "DatabaseExporterUnavailable": {"text": "DB 관측 경로 장애"}, "DatabaseMetricsMissing": {"text": "DB 지표 누락"},
            "DatabaseReplicationUnknown": {"text": "복제 연결 미확인"}, "LinuxHostUnreachable": {"text": "서버 관측 중단"},
            "LinuxDiskSpaceLow": {"text": "디스크 여유 부족"}, "LinuxServiceFailed": {"text": "서비스 기동 실패"},
            "SpringMetricsUnavailable": {"text": "Spring 관측 중단"}, "EdgeProbeDown": {"text": "외부 접속 장애"},
            "DatabaseBackupUnknownOrStale": {"text": "백업 지연·미확인"}}}]}]},
    ]
    return panel


def timeline(title, expressions, x, y, w=12, h=6):
    panel = base(title, "state-timeline", x, y, w, h, "연속 수집 기준. 공백은 미수집 구간이며 정상값으로 보간하지 않음.")
    panel["targets"] = targets(expressions)
    panel["fieldConfig"]["defaults"].update({"color": {"mode": "thresholds"}, "thresholds": thresholds([(None, RED), (1, TEAL)]),
        "mappings": [{"type": "value", "options": {"0": {"text": "장애", "color": RED}, "1": {"text": "정상", "color": TEAL}}}],
        "custom": {"lineWidth": 0, "fillOpacity": 80, "spanNulls": False, "hideFrom": {"tooltip": False, "viz": False, "legend": False}}})
    panel["options"] = {"mergeValues": True, "showValue": "never", "alignValue": "left", "rowHeight": 0.65,
                        "legend": {"showLegend": False}, "tooltip": {"mode": "single", "sort": "none"}}
    return panel


def cloudwatch_panel(title, namespace, metrics, dimensions, x, y, w=8, h=7, unit="short", statistic="Average"):
    panel = series(title, [], x, y, w, h, unit, "AWS 전용 CloudWatch 데이터 소스와 Terraform 자원 식별자 연결 후 조회.")
    panel["datasource"] = {"type": "cloudwatch", "uid": "cloudwatch"}
    panel["targets"] = [
        {"refId": chr(65 + i), "datasource": panel["datasource"], "queryMode": "Metrics", "metricQueryType": 0,
         "region": "$aws_region", "namespace": namespace, "metricName": name, "dimensions": dimensions,
         "statistic": statistic, "period": "60", "matchExact": True, "label": label}
        for i, (name, label) in enumerate(metrics)
    ]
    return panel


def cloudwatch_row():
    alb = {"LoadBalancer": ["$aws_alb"]}
    readers = {**alb, "TargetGroup": ["$aws_reader_target_group"]}
    asg = {"AutoScalingGroupName": ["$aws_asg"]}
    panels = [
        note("AWS 자원 범위", "**리전**　${aws_region}　 |　 **조회 ASG**　${aws_asg}\n\n**ALB**　${aws_alb}　 |　 **조회 대상 그룹**　${aws_reader_target_group}\n\nAWS 전용 데이터 소스와 Terraform 출력 연결 필요. 미설정 자원 조회 결과의 정상 판정 제외.", 0, 34, 24, 3),
        cloudwatch_panel("조회 ASG · 목표 / 가동", "AWS/AutoScaling", [("GroupDesiredCapacity", "목표"), ("GroupInServiceInstances", "가동")], asg, 0, 37, unit="suffix:대"),
        cloudwatch_panel("조회 ASG · 기동 / 종료", "AWS/AutoScaling", [("GroupPendingInstances", "기동 중"), ("GroupTerminatingInstances", "종료 중")], asg, 8, 37, unit="suffix:대"),
        cloudwatch_panel("조회 대상 · 정상 / 비정상", "AWS/ApplicationELB", [("HealthyHostCount", "정상"), ("UnHealthyHostCount", "비정상")], readers, 16, 37, unit="suffix:대", statistic="Minimum"),
        cloudwatch_panel("ALB 요청 · 분당", "AWS/ApplicationELB", [("RequestCount", "요청")], alb, 0, 44, unit="suffix:건/분", statistic="Sum"),
        cloudwatch_panel("ALB 대상 응답 시간", "AWS/ApplicationELB", [("TargetResponseTime", "평균 응답")], alb, 8, 44, unit="s"),
        cloudwatch_panel("ALB 오류 · 분당", "AWS/ApplicationELB", [("HTTPCode_ELB_5XX_Count", "ALB 5xx"), ("HTTPCode_Target_5XX_Count", "대상 5xx")], alb, 16, 44, unit="suffix:건/분", statistic="Sum"),
        cloudwatch_panel("EBS 대기열", "AWS/EBS", [("VolumeQueueLength", "I/O 대기")], {"VolumeId": ["$aws_ebs_volume"]}, 0, 51, unit="short"),
        cloudwatch_panel("EBS 읽기 / 쓰기 · 분당", "AWS/EBS", [("VolumeReadBytes", "읽기"), ("VolumeWriteBytes", "쓰기")], {"VolumeId": ["$aws_ebs_volume"]}, 8, 51, unit="bytes", statistic="Sum"),
        cloudwatch_panel("조회 대상별 요청 · 분당", "AWS/ApplicationELB", [("RequestCountPerTarget", "대상별 요청")], readers, 16, 51, unit="suffix:건/분", statistic="Sum"),
    ]
    for i, panel in enumerate(panels, 1001):
        panel["id"] = i
    return {"id": 1000, "type": "row", "title": "CloudWatch · AWS 연결 후 조회", "collapsed": True,
            "gridPos": {"x": 0, "y": 33, "w": 24, "h": 1}, "panels": panels}


def dashboard(uid, title, panels):
    for i, panel in enumerate(panels, 1):
        panel["id"] = i
    return {"uid": uid, "title": title, "tags": ["msp", "operations"], "timezone": "Asia/Seoul", "schemaVersion": 39,
            "editable": True, "graphTooltip": 1, "refresh": "15s", "time": {"from": "now-30m", "to": "now"},
            "timepicker": {"refresh_intervals": ["15s", "30s", "1m", "5m"]},
            "templating": {"list": [
                {"name": "customer", "label": "고객사", "type": "query", "datasource": DS,
                 "query": {"query": 'label_values(up{job=~"exchange-api|linux-host|postgres|blackbox-http",role=~"app|reader|data|standby|probe"},customer)', "refId": "customer"},
                 "refresh": 1, "current": {"text": "exchange", "value": "exchange"}, "multi": False, "includeAll": False},
                {"name": "environment", "label": "환경", "type": "query", "datasource": DS,
                 "query": {"query": 'label_values(up{customer="$customer",job=~"exchange-api|linux-host|postgres|blackbox-http",role=~"app|reader|data|standby|probe"},environment)', "refId": "environment"},
                 "refresh": 1, "current": {}, "multi": False, "includeAll": False},
            ]}, "links": [{"type": "link", "icon": "dashboard", "includeVars": True, "keepTime": True, **link(*item)} for item in [
                ("msp-overview", "01 서비스 관제"), ("msp-service", "02 트래픽·Spring"), ("msp-infra", "03 AWS·서버"), ("msp-db", "04 DB·복구")]],
            "annotations": {"list": [{"name": "고객 경보", "enable": True, "hide": False, "datasource": DS,
                "expr": ALERTS, "iconColor": AMBER, "titleFormat": "{{alertname}}", "textFormat": "{{node}} · {{severity}}", "tagKeys": "severity"}]},
            "panels": panels}


def overview():
    panels = [header("서비스 관제", "서비스 경로  ·  외부 접속 → Spring → PostgreSQL Primary → Standby　 |　AWS 상세: 03 AWS·서버")]
    panels += [
        status("외부 접속", "min(" + metric("probe_success", SCOPE + ',job="blackbox-http"') + ")", 0, to=("msp-service", "접속·지연 상세")),
        status("Spring 서비스", "max(" + metric("exchange_node_serviceable", APP) + ")", 4, description="요청 수용 가능 앱 존재 여부. 리더 수·노드별 상태는 서비스 상세에서 확인.", to=("msp-service", "Spring 상세")),
        status("Primary DB", "min(" + metric("pg_up", DB + ',role="data"') + ")", 8, to=("msp-db", "DB 장애·복구")),
        status("Standby DB", "min(" + metric("pg_up", DB + ',role="standby"') + ")", 12, to=("msp-db", "복제 상세")),
        stat("진행 경보", ALERT_COUNT, 16, colors=[(None, TEAL), (1, AMBER), (3, RED)], description="관측 대상이 존재할 때만 경보 0건 표시. 관측 대상 누락은 자료 없음.", unit="suffix:건"),
        stat("수집 응답률", "100 * sum(" + UP + ") / count(" + UP + ")", 20, unit="percent", colors=[(None, RED), (100, TEAL)], description="등록 고객 대상의 Prometheus 응답률. 미등록 AWS 자원 포함 아님.", to=("msp-infra", "관측 대상 상세")),
        stat("API 요청량", RPS, 0, 6, h=3, unit="reqps", decimals=2),
        stat("API 오류율 · 5xx", ERROR_RATE, 4, 6, h=3, unit="percent", decimals=2, colors=[(None, TEAL), (1, AMBER), (5, RED)], description="업무 API 요청만 집계. 요청 0건·지표 미수집은 자료 없음."),
        stat("API 평균 응답", MEAN_LATENCY, 8, 6, h=3, unit="ms", decimals=1),
        stat("Primary 연결 사용률", "max(" + DB_UTIL + " and on(node,instance) " + metric("pg_up", DB + ',role="data"') + ")", 12, 6, h=3, unit="percent", decimals=1, colors=[(None, BLUE), (70, AMBER), (85, RED)]),
        stat("WAL 재생 거리", WAL_GAP, 16, 6, h=3, unit="bytes", description="Primary 전송 위치와 Standby 재생 위치의 차이. 손실량 확정 지표 아님."),
        stat("조회 노드 · 수집 응답", "sum(" + metric("up", APP + ',role="reader"') + ")", 20, 6, h=3, unit="suffix:대", no_value="미연결", to=("msp-infra", "ASG·AWS 상세")),
        series("요청 추이 · Spring", [("sum by(role)(" + rate("http_server_requests_seconds_count", HTTP) + ")", "{{role}}")], 0, 9, 8, 7, "reqps", "Actuator 관리 요청 제외. app는 엔진 계층, reader는 조회 계층.", {"app": BLUE, "reader": TEAL}),
        series("응답 지연 · 평균 / P95", [(MEAN_LATENCY, "평균"), (P95, "P95")], 8, 9, 8, 7, "ms", "P95는 HTTP histogram 수집 시 표시. 무요청 구간은 공백.", {"평균": BLUE, "P95": AMBER}),
        series("DB 복제 · WAL 재생 거리", [(metric("pg_replication_client_sent_replay_bytes", DB), "{{node}} → {{application_name}}")], 16, 9, 8, 7, "bytes", "거리 증가: 복제 지연 점검. 시계열 소실: 복제 상태·관측 경로 점검."),
        timeline("서비스 가용성 · 장애 구간", [(metric("probe_success", SCOPE + ',job="blackbox-http"'), "외부 접속"),
                 (metric("exchange_node_serviceable", APP), "Spring · {{node}}"), (metric("pg_up", DB), "DB · {{role}}")], 0, 16, 12, 6),
        alerts_table(12, 16, 12, 6),
        note("", "**DB 장애**　격리 → 수동 승격 → 연결 전환 → 정합성　 [DB·복구](/d/msp-db?var-customer=${customer}&var-environment=${environment})　 |　 **트래픽 급증**　요청·지연 → ASG·ALB → DB 여유　 [트래픽·Spring](/d/msp-service?var-customer=${customer}&var-environment=${environment})", 0, 22, 24, 3),
    ]
    for panel in panels[1:]:
        position = panel["gridPos"]
        if position["y"] == 2:
            position["h"] = 3
        elif position["y"] == 6:
            position["y"] = 5
        elif position["y"] == 9:
            position.update({"y": 8, "h": 6})
        elif position["y"] == 16:
            position["y"] = 14
        elif position["y"] == 22:
            position["y"] = 20
        if panel["type"] == "stat":
            panel["options"]["text"]["valueSize"] = 26
    return dashboard("msp-overview", "01 · 서비스 관제", panels)


def service():
    panels = [header("트래픽 · SPRING", "트래픽 급증 경로  ·  요청 증가 → 응답 지연·오류 → 조회 노드 증감 → DB 연결 여유")]
    panels += [
        stat("API 요청량", RPS, 0, unit="reqps", decimals=2),
        stat("API 오류율 · 5xx", ERROR_RATE, 4, unit="percent", decimals=2, colors=[(None, TEAL), (1, AMBER), (5, RED)]),
        stat("API 응답 · P95", P95, 8, unit="ms", decimals=1),
        stat("활성 앱 리더", "sum(" + metric("exchange_leader_is_leader", APP + ',role="app"') + ")", 12, unit="suffix:대", colors=[(None, RED), (1, TEAL), (2, RED)], description="매칭 리더는 1대. 조회 ASG 증감과 별도 판단."),
        stat("조회 노드 · 수집 응답", "sum(" + metric("up", APP + ',role="reader"') + ")", 16, unit="suffix:대", no_value="미연결"),
        stat("DB 풀 대기", "sum(" + metric("hikaricp_connections_pending", APP) + ")", 20, unit="suffix:건", colors=[(None, TEAL), (1, AMBER), (10, RED)]),
        series("요청량 · 역할별", [("sum by(role)(" + rate("http_server_requests_seconds_count", HTTP) + ")", "{{role}}")], 0, 6, 8, 7, "reqps", colors={"app": BLUE, "reader": TEAL}),
        series("응답 지연 · 평균 / P95 / P99", [(MEAN_LATENCY, "평균"), (P95, "P95"), (P95.replace("0.95", "0.99"), "P99")], 8, 6, 8, 7, "ms", colors={"평균": BLUE, "P95": AMBER, "P99": RED}),
        series("응답 코드 · HTTP", [("sum by(status)(" + rate("http_server_requests_seconds_count", HTTP) + ")", "HTTP {{status}}")], 16, 6, 8, 7, "reqps"),
        series("노드별 요청량", [("sum by(node)(" + rate("http_server_requests_seconds_count", HTTP) + ")", "{{node}}")], 0, 13, 8, 7, "reqps", "ASG 노드 등록 후 요청 분산 확인."),
        series("Spring CPU · 프로세스", [("100 * " + metric("process_cpu_usage", APP), "{{node}}")], 8, 13, 8, 7, "percent", "EC2 호스트 CPU와 구분한 JVM 프로세스 CPU.", max_value=100),
        series("JVM 힙 · 사용률", [(APP_HEAP, "{{node}}")], 16, 13, 8, 7, "percent", max_value=100),
        series("처리 대기 · 마켓별", [(metric("exchange_sequencer_queue_size", APP), "{{node}} · 마켓 {{market}}")], 0, 20, 8, 6, "short"),
        series("DB 연결 풀 · 사용 / 대기", [("sum by(node)(" + metric("hikaricp_connections_active", APP) + ")", "{{node}} 사용"),
                 ("sum by(node)(" + metric("hikaricp_connections_pending", APP) + ")", "{{node}} 대기")], 8, 20, 8, 6, "short"),
        series("외부 접속 · 응답 시간", [("1000 * " + metric("probe_duration_seconds", SCOPE + ',job="blackbox-http"'), "{{node}}")], 16, 20, 8, 6, "ms"),
        timeline("요청 수용 · 노드별", [(metric("exchange_node_serviceable", APP), "{{node}}")], 0, 26, 12, 6),
        table("경로별 요청량", "sum by(method,uri)(" + rate("http_server_requests_seconds_count", HTTP) + ")", {"method": "메서드", "uri": "API 경로", "Value": "요청/s"}, 12, 26, 12, 6),
        note("확장 판단", "**조회 계층**　ALB 대상별 요청량·ASG 목표/가동·노드별 지연　 |　 **공유 병목**　Primary 연결·풀 대기·DB 지연\n\n**엔진 계층**　단일 리더·순차 처리 대기　 |　 **데이터 계층**　수동 승격·비동기 복제　 |　 **ASG 대상**　DB 완결 조회 API", 0, 32, 24, 3),
    ]
    return dashboard("msp-service", "02 · 트래픽·Spring", panels)


def infrastructure():
    panels = [header("AWS · 서버", "AWS 운영 구성  ·  서울 리전 / 2 AZ / ALB / 고정 엔진 / 조회 ASG / PostgreSQL 2노드　 |　실측 범위: 선택 환경")]
    panels += [
        stat("AWS 서버 · 등록", "count(" + metric("up", HOST + ',environment="aws"') + ")", 0, unit="suffix:대", no_value="미연결", description="AWS customer host inventory 기준. 로컬 Ubuntu·관측 서버 제외."),
        stat("고객 서버 · 수집 응답", "sum(" + metric("up", HOST) + ")", 4, unit="suffix:대", no_value="미연결"),
        stat("CPU · 최대", "max(" + CPU + ")", 8, unit="percent", decimals=1, colors=[(None, BLUE), (75, AMBER), (90, RED)]),
        stat("메모리 · 최대", "max(" + MEMORY + ")", 12, unit="percent", decimals=1, colors=[(None, BLUE), (80, AMBER), (90, RED)]),
        stat("디스크 · 최대", "max(" + DISK + ")", 16, unit="percent", decimals=1, colors=[(None, BLUE), (85, AMBER), (95, RED)]),
        stat("실패 서비스", "sum(" + metric("node_systemd_unit_state", HOST + ',state="failed"') + ")", 20, unit="suffix:건", colors=[(None, TEAL), (1, RED)]),
        table("관측 대상 · 서비스 역할", UP, {"node": "대상", "role": "역할", "job": "수집 경로", "Value": "응답"}, 0, 6, 12, 7, "대상 등록 및 수집 응답. 서비스 정상 판정은 Spring·DB·접속 지표와 대조."),
        note("AWS 관측 계층", "| 계층 | 관측 경로 | 주요 판단 |\n|---|---|---|\n| ALB · 조회 ASG | CloudWatch | 대상 상태 · 목표/가동 대수 |\n| Spring · 조회 노드 | Actuator | 요청 · 오류 · 지연 |\n| EC2 · EBS 파일시스템 | node_exporter | CPU · 메모리 · 디스크 |\n| Primary · Standby | postgres_exporter | 연결 · 역할 · 복제 |\n\n**환경 구분**　local-lab: 로컬 / aws: AWS　 ·　 **자료 없음**　미등록·미수집", 12, 6, 12, 7),
        series("CPU 사용률 · 서버별", [(CPU, "{{node}}")], 0, 13, 8, 7, "percent", max_value=100),
        series("메모리 사용률 · 서버별", [(MEMORY, "{{node}}")], 8, 13, 8, 7, "percent", max_value=100),
        series("파일시스템 사용률", [(DISK, "{{node}} · {{mountpoint}}")], 16, 13, 8, 7, "percent", max_value=100),
        series("디스크 I/O · 가동률", [("100 * " + rate("node_disk_io_time_seconds_total", HOST), "{{node}} · {{device}}")], 0, 20, 8, 7, "percent", max_value=100),
        series("네트워크 · 수신 / 송신", [("sum by(node)(" + rate("node_network_receive_bytes_total", HOST + ',device!~"lo|veth.*|docker.*|br-.*"') + ")", "{{node}} 수신"),
                 ("sum by(node)(" + rate("node_network_transmit_bytes_total", HOST + ',device!~"lo|veth.*|docker.*|br-.*"') + ")", "{{node}} 송신")], 8, 20, 8, 7, "Bps"),
        series("inode 사용률", [(INODES, "{{node}} · {{mountpoint}}")], 16, 20, 8, 7, "percent", max_value=100),
        timeline("호스트 관측 · 가용성", [(metric("up", HOST), "{{node}}")], 0, 27, 12, 6),
        table("서비스 장애 · systemd", metric("node_systemd_unit_state", HOST + ',state="failed"') + " == 1", {"node": "대상", "name": "서비스", "Value": "실패"}, 12, 27, 12, 6),
    ]
    result = dashboard("msp-infra", "03 · AWS·서버", panels)
    result["panels"].append(cloudwatch_row())
    for name, label, default in [("aws_region", "AWS 리전", "ap-northeast-2"), ("aws_alb", "ALB", "UNCONFIGURED"),
                                 ("aws_asg", "조회 ASG", "UNCONFIGURED"), ("aws_reader_target_group", "조회 대상 그룹", "UNCONFIGURED"),
                                 ("aws_ebs_volume", "EBS 볼륨", "UNCONFIGURED")]:
        result["templating"]["list"].append({"name": name, "label": label, "type": "textbox", "hide": 2, "query": default,
                                             "current": {"text": default, "value": default}, "skipUrlSync": False})
    status_table = panels[7]
    status_table["fieldConfig"]["overrides"] = [{"matcher": {"id": "byName", "options": "응답"}, "properties": [
        {"id": "mappings", "value": [{"type": "value", "options": {"0": {"text": "중단", "color": RED}, "1": {"text": "수집 중", "color": TEAL}}}]},
        {"id": "custom.cellOptions", "value": {"type": "color-text"}}]}]
    return result


def databases():
    panels = [header("DB · 복구", "PostgreSQL  ·  Primary / Standby 비동기 복제　 |　수동 승격　 |　앱 리더 전환·조회 ASG와 별도 운영")]
    panels += [
        status("Primary DB · 연결", "min(" + metric("pg_up", DB + ',role="data"') + ")", 0, description="Exporter의 실제 DB 연결 결과. 수집 장애와 별도 판단."),
        status("Standby DB · 연결", "min(" + metric("pg_up", DB + ',role="standby"') + ")", 4),
        stat("Primary · DB 역할", "max(" + metric("pg_wal_position_in_recovery", DB + ',role="data"') + ")", 8, mapping=[(0, "Primary", TEAL), (1, "복구 모드", AMBER)]),
        stat("Standby · DB 역할", "min(" + metric("pg_wal_position_in_recovery", DB + ',role="standby"') + ")", 12, mapping=[(0, "승격 상태", AMBER), (1, "Standby", TEAL)]),
        stat("WAL 재생 거리", WAL_GAP, 16, unit="bytes", description="Primary 전송 위치와 Standby 재생 위치 차이. 실제 유실량은 ACK·저널 대조로 판단."),
        stat("연결 사용률 · 최대", "max(" + DB_UTIL + ")", 20, unit="percent", decimals=1, colors=[(None, BLUE), (70, AMBER), (85, RED)], description="DB 노드별 접속 수 / 해당 노드 max_connections의 최대값. 노드 합계와 단일 한도 혼합 금지."),
        timeline("DB 연결 · 장애 구간", [(metric("pg_up", DB), "{{node}}")], 0, 6, 8, 7),
        series("WAL 복제 · 전송 / 재생 거리", [(metric("pg_replication_client_sent_replay_bytes", DB), "Primary → Standby"),
                 (metric("pg_replica_replay_receive_replay_bytes", DB + ',role="standby"'), "Standby 수신 → 재생")], 8, 6, 8, 7, "bytes", colors={"Primary → Standby": BLUE, "Standby 수신 → 재생": AMBER}),
        series("DB 연결 사용률 · 노드별", [(DB_UTIL, "{{node}}")], 16, 6, 8, 7, "percent", max_value=100),
        series("트랜잭션 · 커밋 / 롤백", [("sum by(node)(" + rate("pg_stat_database_xact_commit", DB + ',datname="exchange"') + ")", "{{node}} 커밋"),
                 ("sum by(node)(" + rate("pg_stat_database_xact_rollback", DB + ',datname="exchange"') + ")", "{{node}} 롤백")], 0, 13, 8, 7, "ops"),
        series("앱 DB 풀 · 사용 / 대기", [("sum by(node)(" + metric("hikaricp_connections_active", APP) + ")", "{{node}} 사용"),
                 ("sum by(node)(" + metric("hikaricp_connections_pending", APP) + ")", "{{node}} 대기")], 8, 13, 8, 7),
        series("DB 연결 확보 · 평균", [("1000 * sum by(node)(" + rate("hikaricp_connections_acquire_seconds_sum", APP) + ") / (sum by(node)(" + rate("hikaricp_connections_acquire_seconds_count", APP) + ") > 0)", "{{node}}")], 16, 13, 8, 7, "ms"),
        series("잠금 보유 · 유형별", [("sum by(mode)(" + metric("pg_locks_count", DB) + ")", "{{mode}}")], 0, 20, 8, 6, description="보유 잠금 수. 차단 세션 수나 교착 발생 건수와 구분."),
        series("WAL 생성 · Primary", [(rate("pg_wal_position_bytes", DB + ',role="data"'), "{{node}}")], 8, 20, 8, 6, "Bps"),
        series("복제 마지막 재생 경과", [(metric("pg_replica_replay_delay_seconds", DB + ',role="standby"'), "{{node}}")], 16, 20, 8, 6, "s", "유휴 시간 포함. 이 값만으로 복제 장애·RPO·유실량 판단 금지."),
        alerts_table(0, 26, 12, 6),
        note("DB 장애 · 운영 절차", "| 순서 | 확인·조치 | 완료 기준 |\n|---|---|---|\n| 01 | 고객 영향·Primary 상태 확인 | API·DB 연결 근거 |\n| 02 | 기존 Primary 격리 | 이전 쓰기 경로 차단 |\n| 03 | Standby 수동 승격 | 복구 모드 해제 |\n| 04 | 앱 DB 연결 전환 | readiness·요청 회복 |\n| 05 | 재편입·정합성 확인 | 복제 재개·ACK 대조 |\n\n**사람 개입 필수** · 승격 승인, 기존 Primary 격리, 접속점 전환\n\n**RTO·RPO** · 실제 실험 기록 기준 · 화면 지표만의 확정 금지", 12, 26, 12, 6),
    ]
    return dashboard("msp-db", "04 · DB·복구", panels)


def all_dashboards():
    return {"10-msp-overview.json": overview(), "11-msp-service.json": service(),
            "12-msp-infra.json": infrastructure(), "13-msp-db.json": databases()}


def validate_live(dashboards, url):
    outcomes = []
    for name, data in dashboards.items():
        for panel in data["panels"]:
            for target in panel.get("targets", []):
                query = target["expr"].replace("$customer", "exchange").replace("$environment", "local-lab").replace("$__rate_interval", "1m")
                endpoint = url.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": query})
                with urllib.request.urlopen(endpoint, timeout=15) as response:
                    result = json.load(response)
                if result.get("status") != "success":
                    raise RuntimeError(name + ": " + panel["title"] + ": " + result.get("error", "query failed"))
                series_data = result["data"]["result"]
                finite = sum(math.isfinite(float(item["value"][1])) for item in series_data)
                outcomes.append({"dashboard": name, "panel": panel["title"], "refId": target["refId"],
                                 "series": len(series_data), "finite_series": finite})
    print(json.dumps({"validated_queries": len(outcomes), "with_data": sum(item["finite_series"] > 0 for item in outcomes), "results": outcomes}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--prometheus", help="Read-only query validation URL")
    args = parser.parse_args()
    dashboards = all_dashboards()
    if args.write:
        for filename, data in dashboards.items():
            (DEST / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Updated four MSP dashboards.")
    if args.prometheus:
        validate_live(dashboards, args.prometheus)
    if not args.write and not args.prometheus:
        parser.error("choose --write or --prometheus")
