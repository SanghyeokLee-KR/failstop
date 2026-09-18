"""Bind saved Prometheus evaluation points to report charts without filling gaps."""
import datetime as dt
import html
import math

KST = dt.timezone(dt.timedelta(hours=9))
COLORS = ("#177D8D", "#355D93", "#AC6334")
SPECS = (("disk_available", "파일시스템 여유", "%"),
         ("db_connections", "DB별 연결", "개"),
         ("db_replication_lag", "WAL 전송·재생 위치 차이", "B"),
         ("target_up", "수집 대상 응답", "개"))
SERVICE_SPECS = (("request_rate", "Spring 요청 처리량", "건/초"),
                 ("latency_p95", "Spring 응답 시간 P95", "ms"),
                 ("server_errors", "Spring 5xx 응답", "건/초"))


def _number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _time(value):
    try:
        if _number(value):
            return dt.datetime.fromtimestamp(value, dt.timezone.utc).timestamp()
        parsed = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed.timestamp() if parsed.tzinfo else None
    except (ValueError, TypeError, OverflowError, OSError):
        return None


def _label(row, identifier):
    labels = row.get("labels", {})
    if identifier in {"request_rate", "server_errors", "latency_p95"} and not labels:
        return "Spring 합산"
    instance = str(labels.get("instance", ""))
    node = labels.get("node") or instance or labels.get("job") or "대상 미제공"
    if identifier == "disk_available":
        return f"{node} / {labels.get('mountpoint', '경로 미제공')}"[:160]
    if identifier == "db_connections":
        identity = str(node)
        return f"{identity} / {labels.get('datname', 'DB 미제공')}"[:160]
    return (str(node) + (" / " + str(labels["application_name"]) if labels.get("application_name") else ""))[:160]


def _points(row, start, end, identifier, scale):
    if "points" not in row:
        return None
    points = row["points"]
    if not isinstance(points, list) or not 1 <= len(points) <= 200:
        return []
    previous, result = None, []
    for item in points:
        if not isinstance(item, (list, tuple)) or len(item) != 2 or not all(_number(v) for v in item):
            return []
        moment, value = item
        if previous is not None and moment <= previous:
            return []
        if start is not None and moment < start - 0.001 or end is not None and moment > end + 0.001:
            return []
        if value < 0 or identifier == "disk_available" and value > 1 or identifier == "target_up" and value not in (0, 1):
            return []
        result.append([moment, value * scale])
        previous = moment
    return result


def build_charts(report):
    evidence = report.get("evidence", {})
    facts = {fact.get("id"): fact for fact in evidence.get("facts", []) if isinstance(fact, dict)}
    collected = _time(evidence.get("collected_at"))
    charts = []
    events = facts.get("trigger", {}).get("records", [])
    names = [str(row.get("labels", {}).get("alertname", "")) for row in events if isinstance(row, dict)] if isinstance(events, list) else []
    specs = SPECS
    if any(name.startswith("Database") for name in names):
        specs = (SERVICE_SPECS[0], SPECS[1], SPECS[2], SPECS[3])
    elif any(name.startswith(("Spring", "High", "Read", "Request")) for name in names):
        specs = (*SERVICE_SPECS, SPECS[3])
    for identifier, title, unit in specs:
        fact = facts.get(identifier, {})
        source = fact.get("source", {})
        end = _time(source.get("window_end", evidence.get("window_end")))
        window = evidence.get("window_seconds")
        start = _time(source.get("window_start"))
        if start is None and end is not None and _number(window):
            start = end - window
        step = source.get("step_seconds")
        step = step if _number(step) and step > 0 else None
        chart = {"id": identifier, "title": title, "kind": "empty", "unit": unit,
                 "series": [], "source": identifier, "window_start": start, "window_end": end,
                 "step_seconds": step, "note": fact.get("missing_reason") or "수집 표본 없음 · 상태 판정 제외",
                 "y_min": 0, "status": "unknown"}
        charts.append(chart)
        rows = fact.get("samples", [])
        if fact.get("status") != "ok" or not isinstance(rows, list) or not rows:
            continue
        scale = 100 if identifier == "disk_available" else 1000 if identifier == "latency_p95" else 1
        if identifier == "disk_available":
            chart.update(y_max=100, threshold={"value": 15, "label": "주의 기준 15%"})
        series, invalid, legacy = [], 0, False
        for row in rows:
            if not isinstance(row, dict):
                invalid += 1
                continue
            points = _points(row, start, end, identifier, scale)
            if points is None:
                legacy = True
                value = row.get("last")
                if not _number(value) or value < 0 or identifier == "disk_available" and value > 1 or identifier == "target_up" and value not in (0, 1):
                    invalid += 1
                    continue
                # This timestamp is not invented from the collection window.
                moment = _time(row.get("last_observed_at"))
                if moment is not None and ((end is not None and moment > end + .001)
                                          or (start is not None and moment < start - .001)
                                          or (collected is not None and moment > collected + 30)):
                    invalid += 1
                    continue
                points = [[moment, value * scale]]
            if not points:
                invalid += 1
                continue
            series.append({"label": _label(row, identifier), "points": points})
        if invalid:
            chart["note"] = f"표본 {invalid}개의 시각·범위·수치 오류 · 그래프 보류"
            continue
        if not series:
            continue
        if source.get("window_end") is not None and end is None or source.get("window_start") is not None and start is None:
            chart["note"] = "조회 시각의 지원 범위 초과 · 그래프 보류"
            continue
        # Mixed old/new storage can only support a snapshot comparison.
        if legacy:
            for item in series:
                item["points"] = [item["points"][-1]]
        if identifier == "target_up":
            chart["y_max"] = len(series)
            if legacy:
                moments = {item["points"][-1][0] for item in series}
                if len(moments) != 1 or None in moments:
                    chart["note"] = "대상별 표본 시각 불일치 · 응답 수 합산 보류"
                    continue
                points = [[moments.pop(), sum(item["points"][-1][1] for item in series)]]
            else:
                sets = [{point[0] for point in item["points"]} for item in series]
                moments = sorted(set.intersection(*sets))
                maps = [dict(item["points"]) for item in series]
                points = [[moment, sum(values[moment] for values in maps)] for moment in moments]
            if not points:
                chart["note"] = "전체 대상의 공통 표본 시각 부재 · 응답 수 합산 보류"
                continue
            series = [{"label": f"응답 대상 / 등록 {len(rows)}개", "points": points}]
        elif identifier == "disk_available":
            series.sort(key=lambda item: item["points"][-1][1])
        count = len(series)
        series = series[:3]
        stale = end is not None and any(item["points"][-1][0] is None or end - item["points"][-1][0] > max(180, (step or 0) * 2) for item in series)
        future = collected is not None and end is not None and end > collected + 60
        if future:
            chart["note"] = "수집 이후의 조회 종료 시각 · 시간 설정 확인 필요"
            continue
        chart.update(kind="bar" if legacy or start is None or end is None or end <= start else "line", series=series,
                     status="stale" if stale else "observed")
        if "y_max" not in chart:
            maximum = max(point[1] for item in series for point in item["points"])
            padded = maximum * 1.2
            chart["y_max"] = max(padded if math.isfinite(padded) else maximum, 1)
        note = "시계열 미보존 · 마지막 표본 비교" if chart["kind"] == "bar" else f"{int(step)}초 간격의 조회 표본" if step else "저장된 조회 시각의 표본"
        if count > 3:
            note += f" · {count}개 중 3개 표시"
        if stale:
            note += " · 마지막 표본 오래됨/시각 미확인"
        if identifier == "target_up":
            note += " · 모든 대상의 표본이 있는 시각만 합산; 서비스 정상 판정 아님"
        elif identifier == "db_connections":
            note += " · exchange DB별 연결; 서버 한도와 비교하지 않음"
        elif identifier == "db_replication_lag":
            note += " · 유실량이나 지연 시간으로 해석하지 않음"
        chart["note"] = note
    return charts


def segments(points, step_seconds):
    """No interpolation across an absent evaluation point."""
    result = []
    for point in points:
        if not result or step_seconds and point[0] - result[-1][-1][0] > step_seconds * 1.5:
            result.append([])
        result[-1].append(point)
    return result


def render_svg(chart, width=520, height=250):
    esc = lambda value: html.escape(str(value), quote=True)
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" role="img" aria-label="{esc(chart["title"])}">',
             '<rect width="100%" height="100%" fill="#fff"/>']
    if chart["kind"] == "empty":
        parts += [f'<text x="20" y="75" fill="#66788e" font-size="15">수집 자료 없음 / 확인 필요</text>', '</svg>']
        return "".join(parts)
    left, right, top, bottom = 52, width - 20, 20, height - 72
    series = chart["series"]
    values = [point[1] for item in series for point in item["points"]]
    lower = chart.get("y_min", 0)
    upper = chart.get("y_max", max(max(values) * 1.2, 1))
    upper = max(upper, lower + 1)
    y = lambda value: bottom - (value - lower) / (upper - lower) * (bottom - top)
    for index in range(3):
        value = lower + (upper - lower) * (index / 2)
        value_label = f"{value:g}"
        parts += [f'<line x1="{left}" x2="{right}" y1="{y(value):.2f}" y2="{y(value):.2f}" stroke="#e2e8ef"/>',
                  f'<text x="{left - 7}" y="{y(value) + 4:.2f}" text-anchor="end" font-size="11" fill="#62748a">{esc(value_label)}</text>']
    parts.append(f'<text x="8" y="14" font-size="11" fill="#62748a">{esc(chart["unit"])}</text>')
    threshold = chart.get("threshold")
    if threshold:
        axis = y(threshold["value"])
        parts += [f'<line x1="{left}" x2="{right}" y1="{axis:.2f}" y2="{axis:.2f}" stroke="#b06820" stroke-dasharray="4 3"/>',
                  f'<text x="{right}" y="{axis - 5:.2f}" text-anchor="end" font-size="10" fill="#91521b">{esc(threshold["label"])}</text>']
    if chart["kind"] == "line":
        start, end = chart["window_start"], chart["window_end"]
        x = lambda moment: left + (moment - start) / max(end - start, 1) * (right - left)
        for index, item in enumerate(series):
            color = COLORS[index % len(COLORS)]
            for segment in segments(item["points"], chart.get("step_seconds")):
                coords = " ".join(f"{x(moment):.2f},{y(value):.2f}" for moment, value in segment)
                parts.append(f'<polyline points="{coords}" fill="none" stroke="{color}" stroke-width="2"/>')
                if len(segment) == 1:
                    parts.append(f'<circle cx="{x(segment[0][0]):.2f}" cy="{y(segment[0][1]):.2f}" r="2.5" fill="{color}"/>')
        for moment in (start, (start + end) / 2, end):
            label = dt.datetime.fromtimestamp(moment, KST).strftime("%H:%M")
            parts.append(f'<text x="{x(moment):.2f}" y="{bottom + 17}" text-anchor="middle" font-size="10" fill="#62748a">{label}</text>')
    else:
        slot = (right - left) / max(len(series), 1)
        for index, item in enumerate(series):
            value = item["points"][-1][1]
            x = left + slot * (index + 0.2)
            parts += [f'<rect x="{x:.2f}" y="{y(value):.2f}" width="{slot * .6:.2f}" height="{bottom - y(value):.2f}" fill="{COLORS[index % 3]}"/>',
                      f'<text x="{x + slot * .3:.2f}" y="{y(value) - 4:.2f}" text-anchor="middle" font-size="11">{value:g}</text>']
    for index, item in enumerate(series):
        label = item["label"]
        label = label[:62] + "…" if len(label) > 62 else label
        parts.append(f'<text x="{left}" y="{height - 36 + index * 13}" font-size="10" fill="{COLORS[index % 3]}">{esc(label)}</text>')
    parts.append('</svg>')
    return "".join(parts)
