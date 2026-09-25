"""Bind saved evidence to a fixed two-page MSP form without network access."""
import argparse
import datetime as dt
import html
import json
import math
from pathlib import Path
from presentation import build_brief


def build_bindings(report):
    from charts import build_charts
    brief = build_brief(report)
    decision = brief.get("intervention", {})
    return {"template": {"id": "MSP-OPS-02", "version": 2, "pages": 2},
            "document": {"id": brief["report_id"], "record_line": brief["record_line"], "title": brief["title"], "type": "사건" if report.get("kind") == "incident" else "정기",
                         "service": "페일스톱", "customer": brief["customer"], "environment": brief["environment"],
                         "targets": list(dict.fromkeys(event["target"] for event in brief["events"])),
                         "collected_at": brief["collected_at"], "window": brief["window_label"]},
            "decision": {"code": decision.get("code", "verify"), "label": decision.get("label", "담당자 확인 필요"),
                         "priority": decision.get("priority", "미판정"), "reason": decision.get("reason", brief["impact"]),
                         "scale": "보고 우선순위: P1 즉시 대응 · P2 조치 · P3 확인 · P4 관찰 (SLA와 별도)"},
            "incident": {"state": brief["status"]["label"], "conclusion": brief["lead"], "impact": brief["impact"],
                         "remaining": brief["current_state"], "cause": "확정 원인 미제공", "hypotheses": brief.get("ai_notes", [])},
            "timeline": brief["timeline"], "actions": decision.get("actions", []), "events": brief["events"],
            "handover": {"owner": "역할별 담당자 지정 필요", "reviewer": "미입력", "processing": "처리 결과 확인 전",
                         "approval": "변경 승인 기록 미제공", "executed_actions": brief["automation"]},
            "metrics": brief["metrics"], "charts": build_charts(report), "gaps": brief["gaps"],
            "closure_conditions": decision.get("closure_conditions", []), "limitations": decision.get("limitations", []),
            "evidence_ids": brief["evidence_ids"], "method": brief["analysis_note"]}


def render_pdf(report, output, font, font_bold=None, bindings_output=None):
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.pdfgen.canvas import Canvas
    from reportlab.platypus import Paragraph

    pdfmetrics.registerFont(TTFont("Report", str(font)))
    pdfmetrics.registerFont(TTFont("ReportBold", str(font_bold or font)))
    pdfmetrics.registerFontFamily("Report", normal="Report", bold="ReportBold", italic="Report", boldItalic="ReportBold")
    fields = build_bindings(report)
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas = Canvas(str(destination), pagesize=A4, pageCompression=1)
    # Keep metadata independent of ReportLab's platform-specific local offset handling.
    rendered_at = dt.datetime.now(dt.timezone.utc)
    canvas.setDateFormatter(lambda *parts: rendered_at.strftime("D:%Y%m%d%H%M%SZ"))
    canvas.setTitle(fields["document"]["title"])
    canvas.setAuthor("Failstop Operations")
    width, height = A4
    margin = 12 * mm
    inner = width - 2 * margin
    navy, teal, amber = [colors.HexColor(value) for value in ("#18354B", "#2C7877", "#A66C23")]
    red, ink, muted = [colors.HexColor(value) for value in ("#B54848", "#243746", "#637587")]
    line, pale = colors.HexColor("#DCE3E9"), colors.HexColor("#F3F6F8")
    truncated = []
    styles = {name: ParagraphStyle(name, fontName=face, fontSize=size, leading=leading, textColor=color, wordWrap="CJK")
              for name, face, size, leading, color in (("body", "Report", 8.5, 11.5, ink), ("bold", "ReportBold", 8.5, 11.5, navy),
                  ("small", "Report", 7.2, 9.5, muted), ("white", "ReportBold", 8.5, 11.5, colors.white),
                  ("title", "ReportBold", 16, 20, navy), ("decision", "ReportBold", 14, 18, navy))}

    def box(x, top, w, h, fill=None, stroke=line):
        canvas.setStrokeColor(stroke)
        canvas.setFillColor(fill or colors.white)
        canvas.setLineWidth(0.45)
        canvas.rect(x, top - h, w, h, fill=1, stroke=1)

    def text(value, x, top, w, h, style="body", field=""):
        raw = str(value or "미제공")
        para = Paragraph(html.escape(raw, quote=True).replace("\n", "<br/>"), styles[style])
        _, used = para.wrap(w, h)
        if used > h:
            original = raw
            while used > h and len(raw) > 8:
                raw = raw[:-8]
                para = Paragraph(html.escape(raw + "…"), styles[style])
                _, used = para.wrap(w, h)
            truncated.append({"field": field or style, "characters": len(original)})
        para.drawOn(canvas, x, top - used)

    def section(number, title, top, right=""):
        canvas.setFillColor(teal)
        canvas.rect(margin, top - 13, 3, 13, stroke=0, fill=1)
        text(number + "  " + title, margin + 8, top - 1, inner - 165, 13, "bold")
        if right:
            text(right, margin + inner - 156, top - 2, 156, 11, "small")
        return top - 18

    def table(top, columns, rows, row_heights, headers=None, field="table"):
        if headers:
            offset = margin
            for index, header in enumerate(headers):
                box(offset, top, columns[index], 18, navy, navy)
                text(header, offset + 5, top - 3, columns[index] - 10, 13, "white")
                offset += columns[index]
            top -= 18
        for row_index, row in enumerate(rows):
            row_height = row_heights[row_index] if isinstance(row_heights, list) else row_heights
            offset = margin
            for column, value in enumerate(row):
                box(offset, top, columns[column], row_height, pale if column == 0 and not headers else colors.white)
                padding = 2 if row_height < 20 else 3
                text(value, offset + 5, top - padding, columns[column] - 10, row_height - padding * 2,
                     "bold" if column == 0 else "body", f"{field}.{row_index}.{column}")
                offset += columns[column]
            top -= row_height
        return top

    def header(page, title):
        top = height - margin
        text("FAILSTOP  /  OPERATIONS CENTER", margin, top, 270, 13, "bold")
        text("MSP-OPS-02  ·  v2.0  ·  " + str(page) + " / 2", margin + inner - 155, top, 155, 12, "small")
        canvas.setStrokeColor(navy)
        canvas.setLineWidth(1.2)
        canvas.line(margin, top - 18, width - margin, top - 18)
        text(title, margin, top - 27, inner, 27, "title")
        return top - 55

    def footer(page):
        canvas.setStrokeColor(line)
        canvas.setLineWidth(0.5)
        canvas.line(margin, 24, width - margin, 24)
        text(fields["document"]["record_line"] + " · 보고 ID " + fields["document"]["id"], margin, 19, inner - 55, 10, "small")
        text(f"{page} / 2", width - margin - 35, 19, 35, 10, "small")

    document = fields["document"]
    y = header(1, document["title"])
    y = table(y, [63, inner / 2 - 63, 60, inner / 2 - 60], [
        ["고객 / 환경", document["customer"] + " / " + document["environment"], "보고 기준", document["collected_at"]],
        ["대상 자원", ", ".join(document["targets"]) or "등록 관측 대상", "보고 구분", document["service"] + " " + document["type"] + " 보고"]], [23, 27], field="document") - 10
    y = section("01", "대응 우선순위", y, "수집 시점 기준 · 담당자 개입")
    decision = fields["decision"]
    decision_color = red if decision["priority"] == "P1" else amber if decision["priority"] in ("P2", "P3") else teal
    box(margin, y, inner, 64, colors.HexColor("#F5F7F9"))
    canvas.setFillColor(decision_color)
    canvas.rect(margin, y - 64, 5, 64, fill=1, stroke=0)
    text(decision["priority"] + "  " + decision["label"], margin + 13, y - 8, inner - 26, 20, "decision", "decision.label")
    text(decision["reason"], margin + 13, y - 30, inner - 26, 23, "body", "decision.reason")
    text(decision["scale"], margin + 13, y - 53, inner - 26, 10, "small", "decision.scale")
    y -= 73
    y = section("02", "상황 및 영향 범위", y)
    y = table(y, [64, inner - 64], [["사건 상태", fields["incident"]["conclusion"]], ["서비스 영향", fields["incident"]["impact"]],
        ["남은 위험", fields["incident"]["remaining"]], ["원인 확인", fields["incident"]["cause"] + (" / 검증 전 가설은 상세 보고 참조" if fields["incident"]["hypotheses"] else "")]],
        [30, 30, 30, 20], field="incident") - 8
    y = section("03", "시각별 경과", y, "시간대: Asia/Seoul")
    rows = [[item["time"], item["label"]] for item in fields["timeline"][:4]] or [["미제공", "사건 시각 기록 없음"]]
    y = table(y, [126, inner - 126], rows, 19, headers=["시각", "기록"], field="timeline") - 8
    y = section("04", "조치 및 완료 기준", y, "담당 역할 · 착수 시점은 권고")
    actions = fields["actions"][:5]
    rows = [[item.get("priority", "미판정"), item.get("owner", "지정 필요").removesuffix(" 담당"),
             item.get("timing", "미정").removesuffix(" 권고").replace("다음 판정 전 확인", "다음\n판정 전"),
             item.get("task", "확인 항목 미제공"), item.get("done_when", "완료 조건 미제공")] for item in actions]
    if not rows:
        rows = [["미판정", "운영 담당", "보고 검토 시", "수집 자료와 개입 필요 여부를 검토합니다.", "담당자·처리 범위·완료 조건 기록"]]
    action_height = min(60, (y - 146) / len(rows))
    y = table(y, [27, 62, 58, 186, inner - 333], rows, action_height,
              headers=["순위", "담당 역할", "권고 착수", "조치", "완료 조건"], field="actions") - 9
    y = section("05", "운영 기록 및 종결 관리", y)
    handover = fields["handover"]
    y = table(y, [66, inner / 2 - 66, 58, inner / 2 - 58], [
        ["확인 담당", fields["actions"][0]["owner"] if fields["actions"] else "운영 담당", "처리 기록", "담당자 확인 후 등록"],
        ["자동 조치", handover["executed_actions"], "변경 승인", "조치 실행 전 승인 검토"],
        ["종결 기준", "접속·복제·요청 결과 재확인" if any(event["alertname"].startswith("Database") for event in fields["events"]) else "경보·영향·필수 근거 확인", "판단 근거", "2쪽 지표·수집 범위 참조"]], 22, field="handover")
    if y < 33:
        raise ValueError("page_one_form_overflow")
    footer(1)
    canvas.showPage()

    def number(value):
        return f"{value:,.0f}" if abs(value) >= 100 or value == int(value) else f"{value:.2f}"

    def chart_panel(chart, x, top, w, h):
        box(x, top, w, h)
        text(chart["title"], x + 9, top - 8, w - 18, 14, "bold", "chart.title")
        kind, series = chart.get("kind", "empty"), chart.get("series", [])
        points = [[float(t) if t is not None else 0, float(v)] for item in series for t, v in item.get("points", [])
                  if (t is None or math.isfinite(float(t))) and math.isfinite(float(v))]
        if kind == "empty" or not points:
            text("표본 없음", x + 12, top - 48, w - 24, 20, "decision")
            text(chart.get("note", "수집 자료가 없어 그래프를 작성하지 않았습니다."), x + 12, top - 76, w - 24, 38, "small")
            return
        left, bottom, plot_w, plot_h = x + 36, top - 91, w - 50, 55
        threshold = chart.get("threshold")
        all_values = [point[1] for point in points] + ([threshold["value"]] if threshold else [])
        low, high = chart.get("y_min", min(0, min(all_values))), chart.get("y_max", max(all_values) * 1.12)
        if not math.isfinite(high):
            high = max(all_values)
        if high <= low:
            high = low + 1
        first = chart.get("window_start") if kind == "line" else None
        last = chart.get("window_end") if kind == "line" else None
        first = first if first is not None else min(point[0] for point in points)
        last = last if last is not None else max(point[0] for point in points)
        last = last if last > first else first + 1
        px = lambda moment: left + (moment - first) / (last - first) * plot_w
        py = lambda value: bottom + (value - low) / (high - low) * plot_h
        canvas.setFont("Report", 6.3)
        for value in [low, (low + high) / 2, high]:
            canvas.setStrokeColor(line)
            canvas.setLineWidth(0.35)
            canvas.line(left, py(value), left + plot_w, py(value))
            canvas.setFillColor(muted)
            canvas.drawRightString(left - 4, py(value) - 2, number(value))
        text(chart.get("unit", ""), x + 9, top - 25, 50, 10, "small")
        palette = [teal, navy, colors.HexColor("#5E7893")]
        if threshold:
            canvas.setDash(3, 2)
            canvas.setStrokeColor(red)
            canvas.line(left, py(threshold["value"]), left + plot_w, py(threshold["value"]))
            canvas.setDash()
            canvas.setFillColor(red)
            canvas.setFont("Report", 6.8)
            canvas.drawRightString(x + w - 10, top - 29, str(threshold.get("label", number(threshold["value"]))))
        if kind == "line":
            step = chart.get("step_seconds")
            for index, item in enumerate(series[:3]):
                canvas.setStrokeColor(palette[index % len(palette)])
                canvas.setFillColor(palette[index % len(palette)])
                canvas.setLineWidth(1)
                previous = None
                for moment, value in item.get("points", []):
                    if previous and (not step or moment - previous[0] <= step * 1.5):
                        canvas.line(px(previous[0]), py(previous[1]), px(moment), py(value))
                    canvas.circle(px(moment), py(value), 1.25, stroke=0, fill=1)
                    previous = (moment, value)
            for moment, align in ((first, "left"), (last, "right")):
                label = dt.datetime.fromtimestamp(moment, dt.timezone(dt.timedelta(hours=9))).strftime("%H:%M")
                canvas.setFillColor(muted)
                canvas.setFont("Report", 6.3)
                (canvas.drawString if align == "left" else canvas.drawRightString)(px(moment), bottom - 10, label)
        else:
            bars = [(item.get("label", "대상"), item["points"][-1][1]) for item in series if item.get("points")]
            for index, (label, value) in enumerate(bars[:6]):
                slot = plot_w / max(1, min(6, len(bars)))
                bar_w, center = min(24, slot * 0.56), left + slot * (index + 0.5)
                canvas.setFillColor(palette[index % len(palette)])
                canvas.rect(center - bar_w / 2, py(0), bar_w, max(0.6, py(value) - py(0)), fill=1, stroke=0)
                canvas.setFont("Report", 6.3)
                canvas.drawCentredString(center, min(top - 32, py(value) + 3), number(value))
                canvas.setFillColor(muted)
                canvas.drawCentredString(center, bottom - 10, str(label)[-16:])
        if kind == "line":
            offset = x + 9
            legend_y = top - 110
            for index, item in enumerate(series[:3]):
                label = str(item.get("label", ""))
                if chart["id"] == "db_connections":
                    label = label.replace(":9187 / exchange", "")
                if chart["id"] == "target_up":
                    label = "응답 수"
                maximum_width = (w - 26) / max(1, min(len(series), 3)) - 10
                while pdfmetrics.stringWidth(label, "Report", 6.8) > maximum_width and len(label) > 3:
                    label = label[:-2].rstrip("…") + "…"
                canvas.setFillColor(palette[index % len(palette)])
                canvas.rect(offset, legend_y, 4, 4, fill=1, stroke=0)
                canvas.setFont("Report", 6.8)
                canvas.drawString(offset + 7, legend_y, label)
                offset += (w - 18) / max(1, min(len(series), 3))
        else:
            text("마지막 표본 비교", x + 9, top - 103, w - 18, 10, "small")
        text(chart.get("note", "실측 표본"), x + 9, top - 114, w - 18, h - 118, "small", "chart.note")

    y = header(2, "관측 추이 및 확인 근거")
    text("조회 구간  " + document["window"], margin, y, inner, 14, "small")
    y = section("06", "주요 지표 추이", y - 21, "누락 구간 제외 · 실측 표본")
    plots = list(fields["charts"][:4])
    while len(plots) < 4:
        plots.append({"id": "unavailable", "title": "지표 미제공", "kind": "empty", "series": [], "note": "추가 수집 근거 없음"})
    chart_width, chart_height = (inner - 9) / 2, 137
    for index, chart in enumerate(plots):
        chart_panel(chart, margin + (index % 2) * (chart_width + 9), y - (index // 2) * (chart_height + 8), chart_width, chart_height)
    y -= chart_height * 2 + 17
    y = section("07", "핵심 지표", y, "조회 구간의 마지막 표본")
    rows = [[item["label"], item["value"], item.get("as_of", "시각 미제공")] for item in fields["metrics"][:12]]
    y = table(y, [120, inner - 270, 150], rows, 16.5, headers=["항목", "값 / 상태", "표본 시각 · KST"], field="metrics") - 8
    y = section("08", "수집 범위 및 종결 조건", y)
    closure = fields["closure_conditions"] or ["담당자가 경보·영향·필수 자료를 재확인하고 결과를 기록해야 합니다."]
    left_text = "; ".join(fields["gaps"][:6]) or "미수집으로 표시된 자료 없음"
    if len(fields["gaps"]) > 6:
        left_text += f" · 외 {len(fields['gaps']) - 6}항목: HTML 상세 보고 참조"
    right_text = "\n".join("- " + str(item) for item in closure[:4])
    box(margin, y, inner / 2, 79, pale)
    box(margin + inner / 2, y, inner / 2, 79, pale)
    text("미확인 자료", margin + 7, y - 6, inner / 2 - 14, 13, "bold")
    text("종결 확인", margin + inner / 2 + 7, y - 6, inner / 2 - 14, 13, "bold")
    text(left_text, margin + 7, y - 23, inner / 2 - 14, 51, "small", "gaps")
    text(right_text, margin + inner / 2 + 7, y - 23, inner / 2 - 14, 51, "small", "closure")
    y -= 86
    source = "Prometheus · Alertmanager / " + document["customer"] + " / " + document["environment"]
    text("자료 출처: " + source + " · 후속 지표와 발생 당시 상태 구분", margin, y, inner, 19, "small", "sources")
    text("작성 방식: " + fields["method"], margin, y - 21, inner, 20, "small", "method")
    if y - 41 < 31:
        raise ValueError("page_two_form_overflow")
    footer(2)
    canvas.save()
    if bindings_output:
        fields["rendering"] = {"truncated_fields": truncated, "output_pages": 2}
        Path(bindings_output).write_text(json.dumps(fields, ensure_ascii=False, indent=2), encoding="utf-8")
    return destination


def main():
    parser = argparse.ArgumentParser(description="Render a fixed two-page report from saved evidence")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bindings-output", type=Path)
    parser.add_argument("--font", type=Path, required=True)
    parser.add_argument("--font-bold", type=Path)
    args = parser.parse_args()
    if args.input.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("saved_report_too_large")
    report = json.loads(args.input.read_text(encoding="utf-8-sig"))
    render_pdf(report, args.output, args.font, args.font_bold, args.bindings_output)
    print(json.dumps({"status": "rendered", "format": "pdf", "pages": 2}))


if __name__ == "__main__":
    main()
