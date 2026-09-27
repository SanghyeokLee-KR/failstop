"""로컬 MSP 화면 7개의 모든 패널 질의를 Grafana 에 실제로 보내 값이 있는지 표로 보인다. 브라우저가 필요 없다.

    python harness/grafana/check-local-msp.py [--grafana http://localhost:53001] [--environment local-lab]
                                             [--from now-15m] [--json 결과.json]

Grafana 가 지금 서빙하는 대시보드(/api/dashboards/uid/...)를 읽고, 화면이 하는 것과 같이 변수만 바꿔 /api/ds/query 로
보낸다. $__rate_interval 같은 매크로는 Grafana 가 서버에서 푼다. 판정은 다음 넷이다.
  값 있음    숫자 값이 하나 이상 왔다. 순간 질의는 마지막 값을 적는다(특수값 -1·-2 는 화면의 '요청 없음' 표시다).
  빈 결과    질의는 성공했으나 계열이 없다. 화면에는 '미수집'으로 보인다.
  오류       Grafana 나 Prometheus 가 오류를 돌려줬다.
  글         질의가 없는 글 패널이다.
종료코드는 '빈 결과'나 '오류'가 하나라도 있으면 1 이다. 빈 결과를 정상으로 세지 않는다.
"""
import argparse
import json
import sys
import urllib.request

UIDS = ("msp-overview", "msp-service", "msp-infra", "msp-db", "msp-db-workload", "msp-db-replication", "msp-storage")


def get(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.load(response)


def post(url, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def panels(items):
    for panel in items:
        yield panel
        yield from panels(panel.get("panels", []))


def substitute(expr, variables):
    # 긴 이름부터 바꿔 $customer 가 다른 변수 이름의 앞부분을 먹지 않게 한다.
    for name in sorted(variables, key=len, reverse=True):
        expr = expr.replace("${" + name + "}", variables[name]).replace("$" + name, variables[name])
    return expr


def summarize(result, instant):
    if result.get("error"):
        return "오류", result["error"][:120]
    series, numbers, last = 0, 0, None
    for frame in result.get("frames", []):
        values = frame.get("data", {}).get("values", [])
        if len(values) < 2:
            continue
        for column in values[1:]:
            points = [v for v in column if isinstance(v, (int, float))]
            if points:
                series += 1
                numbers += len(points)
                last = points[-1]
    if not numbers:
        return "빈 결과", ""
    if instant:
        return "값 있음", f"계열 {series} · 값 {last:g}"
    return "값 있음", f"계열 {series} · 점 {numbers}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--grafana", default="http://localhost:53001")
    parser.add_argument("--customer", default="exchange")
    parser.add_argument("--environment", default="local-lab")
    parser.add_argument("--from", dest="start", default="now-15m")
    parser.add_argument("--json", dest="json_path")
    args = parser.parse_args()
    rows = []
    for uid in UIDS:
        dashboard = get(f"{args.grafana}/api/dashboards/uid/{uid}")["dashboard"]
        variables = {"customer": args.customer, "environment": args.environment}
        for variable in dashboard["templating"]["list"]:
            if variable["name"] not in variables:
                # 모두(All) 선택과 같은 값. 숨은 텍스트 변수는 저장된 값을 쓴다.
                if variable.get("includeAll"):
                    variables[variable["name"]] = variable.get("allValue") or ".*"
                else:
                    variables[variable["name"]] = str((variable.get("current") or {}).get("value", ""))
        for panel in panels(dashboard["panels"]):
            if panel.get("type") == "row":
                continue
            title = panel.get("title") or "(제목 없음)"
            targets = [t for t in panel.get("targets", []) if t.get("expr") and not t.get("hide")]
            if not targets:
                rows.append((dashboard["title"], panel.get("id"), title, "-", "글", ""))
                continue
            for target in targets:
                instant = bool(target.get("instant")) and not target.get("range")
                query = {
                    "refId": target["refId"], "datasource": {"type": "prometheus", "uid": "prometheus"},
                    "expr": substitute(target["expr"], variables), "instant": instant, "range": not instant,
                    "format": target.get("format", "time_series"), "interval": target.get("interval", ""),
                    "intervalMs": 5000, "maxDataPoints": 400,
                }
                try:
                    response = post(f"{args.grafana}/api/ds/query", {"queries": [query], "from": args.start, "to": "now"})
                    verdict, detail = summarize(response["results"][target["refId"]], instant)
                except Exception as error:  # 요청 자체의 실패도 오류로 센다
                    verdict, detail = "오류", str(error)[:120]
                legend = target.get("legendFormat") or ""
                rows.append((dashboard["title"], panel.get("id"), title, f"{target['refId']} {legend}".strip(), verdict, detail))
    width = max(len(r[2]) for r in rows)
    counts = {}
    for board, pid, title, ref, verdict, detail in rows:
        counts[verdict] = counts.get(verdict, 0) + 1
        print(f"{board:<16} {str(pid):>4}  {title:<{width}}  {ref:<24} {verdict:<5} {detail}")
    print("합계: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as handle:
            json.dump([dict(zip(("board", "panel_id", "title", "query", "verdict", "detail"), r)) for r in rows],
                      handle, ensure_ascii=False, indent=2)
    return 1 if counts.get("빈 결과") or counts.get("오류") else 0


if __name__ == "__main__":
    sys.exit(main())
