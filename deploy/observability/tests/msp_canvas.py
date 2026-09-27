"""Native Grafana Canvas topology panels with explicit missing-data states."""

from copy import deepcopy


# Grafana v13.1.0: panel/canvas/panelcfg.gen.ts, canvas/elements/text.tsx,
# dimensions/text.ts, dimensions/color.ts, themes/createVisualizationColors.ts.
DATASOURCE = {"type": "prometheus", "uid": "prometheus"}
TEAL, RED, GRAY = "green", "red", "#788396"
STATUS_FIELDS = {"edge", "cloudfront", "alb", "spring", "primary", "standby", "replication"}
STATUS_MAPPING = [
    {"type": "value", "options": {
        "0": {"text": "▲ 장애", "color": RED},
        "1": {"text": "● 정상", "color": TEAL},
        "-1": {"text": "미수집", "color": GRAY},
    }},
    {"type": "special", "options": {
        "match": "null+nan", "result": {"text": "미수집", "color": GRAY},
    }},
]
# msp_queries.Q["replication_mode"] 의 값. 이 프로젝트의 동기 구성은 Patroni 엄격 동기(ADR 0036,
# synchronous_mode_strict)뿐이라 동기를 '엄격 동기 복제'로 적는다. 엄격 여부 자체는 지표가 아니라 설정으로 확인한다
REPLICATION_MODE_MAPPING = {"type": "value", "options": {
    "2": {"text": "엄격 동기 복제"},
    "1": {"text": "비동기 복제"},
}}


def _placement(left, top, width, height):
    # Percentage constraints preserve readable text sizes on both monitor sizes.
    return {"constraint": {"horizontal": "scale", "vertical": "scale"},
            "placement": {"left": left, "right": 100 - left - width,
                          "top": top, "bottom": 100 - top - height,
                          "rotation": 0}}


def _text(name, value, left, top, width, height, *, field=None, size=14,
          color="text", align="center"):
    text = {"mode": "fixed", "fixed": value}
    tone = {"fixed": "text" if color == GRAY else color}
    if field:
        # Unlike metric-value, native text honors this fallback when the field
        # is absent. Empty query results therefore remain visibly unknown.
        text.update({"mode": "field", "field": field})
        # Status words and numbers use theme text contrast. Icons and borders
        # carry the semantic color without weakening small-text readability.
        tone = {"fixed": "text"}
    return {"name": name, "type": "text", **_placement(left, top, width, height),
            "config": {"text": text, "color": tone, "size": size,
                       "align": align, "valign": "middle"},
            "background": {"color": {"fixed": "transparent"}},
            "border": {"width": 0}}


def _card(name, field, left, top, width, height):
    return {"name": name, "type": "rectangle", **_placement(left, top, width, height),
            "config": {"text": {"mode": "fixed", "fixed": ""}},
            "background": {"color": {"fixed": "panel-bg"}},
            "border": {"width": 1, "radius": 7,
                       "color": {"field": field, "fixed": GRAY}}}


def _icon(name, icon, field, left, top, width, height):
    return {"name": name, "type": "icon", **_placement(left, top, width, height),
            "config": {"path": {"mode": "fixed", "fixed": f"img/icons/unicons/{icon}.svg"},
                       "fill": {"field": field, "fixed": GRAY}},
            "background": {"color": {"fixed": "transparent"}},
            "border": {"width": 0}}


def _connection(target, *, field=None):
    return {"targetName": target, "path": "straight",
            "source": {"x": 1, "y": 0}, "target": {"x": -1, "y": 0},
            "color": {"field": field, "fixed": GRAY} if field else {"fixed": GRAY},
            "size": {"fixed": 2, "min": 1, "max": 3},
            "direction": {"mode": "fixed", "fixed": "forward"}}


def _panel(title, expressions, elements, x, y, w, h, panel_id, description):
    result = {
        "type": "canvas", "title": title, "description": description,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "datasource": dict(DATASOURCE), "transparent": False,
        "targets": [{"refId": chr(65 + index), "expr": expression,
                     "legendFormat": key, "instant": True, "range": False,
                     "format": "time_series", "editorMode": "code"}
                    for index, (key, expression) in enumerate(expressions.items()) if expression],
        "fieldConfig": {"defaults": {
            "unit": "short", "noValue": "미수집", "decimals": 0,
            "color": {"mode": "fixed", "fixedColor": GRAY},
            "mappings": [deepcopy(STATUS_MAPPING[1])]}, "overrides": []},
        "options": {"inlineEditing": False, "panZoom": False, "zoomToContent": False,
                    "showAdvancedTypes": False,
                    "tooltip": {"mode": "single", "disableForOneClick": False},
                    "root": {"name": "관측 경로", "type": "frame", "elements": elements}},
    }
    # Grafana 13.1 merges mapping arrays when applying overrides. Keep status
    # values out of defaults so a measured zero remains numeric on every frame.
    result["fieldConfig"]["overrides"] = [
        {"matcher": {"id": "byFrameRefID", "options": target["refId"]},
         "properties": [{"id": "mappings", "value": [deepcopy(STATUS_MAPPING[0])]}]}
        for target in result["targets"] if target["legendFormat"] in STATUS_FIELDS
    ]
    if panel_id is not None:
        result["id"] = panel_id
    return result


def service_path_panel(expressions, *, x=0, y=0, w=24, h=5, id=None):
    """Accept single-series status queries: 1 healthy, 0 failed, no series unknown."""
    nodes = [("edge", "외부 접속", "globe"), ("cloudfront", "CloudFront", "cloud"),
             ("alb", "ALB", "exchange"), ("spring", "Spring Boot", "server"),
             ("primary", "Primary DB", "database"), ("standby", "Standby DB", "database")]
    elements = []
    compact = h <= 4
    for index, (key, label, icon) in enumerate(nodes):
        left = 0.6 + index * 16.6
        card = _card(key + "-card", key, left, 4, 15, 92 if compact else 77)
        if index + 1 < len(nodes):
            # Arrows denote the architecture. Their neutral color does not
            # imply an independently verified network or replication link.
            card["connections"] = [_connection(nodes[index + 1][0] + "-card")]
        elements.append(card)
        if compact:
            elements.extend([
                _icon(key + "-icon", icon, key, left + 1, 16, 3.1, 30),
                _text(key + "-title", label, left + 4.7, 16, 9.8, 30, size=14),
                _text(key + "-state", "미수집", left + 0.5, 59, 14, 25, field=key, size=13),
            ])
        else:
            elements.extend([
                _icon(key + "-icon", icon, key, left + 5.2, 12, 4.6, 25),
                _text(key + "-title", label, left + 0.5, 40, 14, 16, size=15),
                _text(key + "-state", "미수집", left + 0.5, 62, 14, 14, field=key, size=13),
            ])
    if not compact:
        elements.append(_text("scope-note", "고객 서비스 경로 · 회색: 미수집 · DB 역할: 실제 복구 상태 기준",
                              0.6, 86, 98, 11, size=11, color=GRAY))
    queries = {key: expressions[key] for key, _, _ in nodes if expressions.get(key)}
    return _panel("서비스 경로 · 계층별 상태", queries, elements, x, y, w, h, id,
                  "선택 고객·환경의 실측 상태. 회색은 미수집 또는 미연결. 화살표는 설계 경로이며 "
                  "구간별 네트워크 성공 판정이 아님. DB 역할은 호출 쿼리의 in_recovery 기준.")


def db_replication_panel(expressions, *, x=0, y=0, w=12, h=6, id=None):
    """Bind current DB roles; optional connections/utilization keys add detail."""
    elements = []
    compact = h <= 4
    for key, label, left in (("primary", "Primary", 1), ("standby", "Standby", 68)):
        card = _card(key + "-card", key, left, 3, 31, 93)
        if key == "primary":
            card["connections"] = [_connection("standby-card", field="replication")]
            if compact:
                card["connections"][0]["source"]["y"] = 0.35
                card["connections"][0]["target"]["y"] = 0.35
        elements.append(card)
        if compact:
            elements.extend([
                _icon(key + "-icon", "database", key, left + 3, 6, 6, 27),
                _text(key + "-title", label, left + 10, 6, 20, 27, size=14, align="left"),
                _text(key + "-state", "미수집", left + 1, 39, 29, 25, field=key, size=12),
            ])
        else:
            elements.extend([
                _icon(key + "-icon", "database", key, left + 11, 7, 9, 18),
                _text(key + "-title", label, left + 1, 27, 29, 14, size=17),
                _text(key + "-state", "미수집", left + 1, 45, 29, 13, field=key, size=14),
            ])
        detail_keys = [(key + "_connections", "연결 수"), (key + "_utilization", "사용률")]
        present = [(field, title) for field, title in detail_keys if expressions.get(field)]
        width = 29 / max(1, len(present))
        for index, (field, title) in enumerate(present):
            detail_left = left + 1 + index * width
            if compact:
                elements.append(_text(field + "-value", "미수집", detail_left, 72, width, 24,
                                      field=field, size=11))
            else:
                elements.extend([
                    _text(field + "-label", title, detail_left, 65, width, 12, size=12, color=GRAY),
                    _text(field + "-value", "미수집", detail_left, 80, width, 13, field=field, size=14),
                ])
    elements.extend([
        # 복제 방식은 sync_state 실측이다. 식이 없거나 계열이 없으면 고정 문구(미수집)가 남는다
        _text("replication-title", "복제 방식 미수집", 34, 3 if compact else 9, 32,
              25 if compact else 13, size=12 if compact else 13,
              field="replication_mode" if expressions.get("replication_mode") else None),
        _text("replication-state", "미수집", 34, 32 if compact else 25, 32,
              25 if compact else 13, field="replication", size=12 if compact else 13),
    ])
    if compact:
        elements.extend([
            _text("wal-gap-label", "WAL", 34, 74, 12, 24, size=11, color=GRAY),
            _text("wal-gap-value", "미수집", 46, 74, 20, 24, field="wal_gap", size=11),
        ])
    else:
        elements.extend([
            _text("wal-gap-label", "재생 지연량", 34, 64, 32, 12, size=12, color=GRAY),
            _text("wal-gap-value", "미수집", 34, 80, 32, 13, field="wal_gap", size=15),
        ])
    keys = ["primary", "standby", "replication", "wal_gap", "primary_connections",
            "standby_connections", "primary_utilization", "standby_utilization", "replication_mode"]
    queries = {key: expressions[key] for key in keys if expressions.get(key)}
    panel = _panel("DB 복제 구성 · 현재 역할", queries, elements, x, y, w, h, id,
                   "Primary/Standby는 in_recovery 실측으로 선택. 복제 방식(동기·비동기)은 sync_state 실측이고, "
                   "동기는 Patroni 엄격 동기(ADR 0036)로 표기. 복제 상태와 WAL 거리는 별도 지표. "
                   "자료 부재는 미수집이며 복제 정상 또는 무손실 판정 제외.")
    query_refs = {target["legendFormat"]: target["refId"] for target in panel["targets"]}
    if "replication_mode" in query_refs:
        panel["fieldConfig"]["overrides"].append({
            "matcher": {"id": "byFrameRefID", "options": query_refs["replication_mode"]}, "properties": [
                {"id": "mappings", "value": [deepcopy(REPLICATION_MODE_MAPPING)]},
                {"id": "color", "value": {"mode": "fixed", "fixedColor": "text"}},
            ]})
    for key in keys[3:-1]:
        if not expressions.get(key):
            continue
        unit = "bytes" if key == "wal_gap" else "percent" if key.endswith("_utilization") else (
            "suffix:연결" if compact else "short")
        panel["fieldConfig"]["overrides"].append({
            # Query refs also distinguish fields with the same raw name Value.
            "matcher": {"id": "byFrameRefID", "options": query_refs[key]}, "properties": [
                {"id": "unit", "value": unit},
                {"id": "decimals", "value": 1 if unit == "percent" else 0},
                {"id": "color", "value": {"mode": "fixed", "fixedColor": "text"}},
            ]})
    return panel
