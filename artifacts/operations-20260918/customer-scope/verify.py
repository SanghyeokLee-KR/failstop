"""Check customer dashboard and report queries against the local Prometheus."""
import base64
import datetime as dt
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
config = json.loads((ROOT / "deploy/reporting/config.example.json").read_text())
queries = [("report:" + item["id"], item["expr"]) for item in config["queries"]]
for path in sorted((ROOT / "harness/grafana/dashboards").glob("1*-msp-*.json")):
    for panel in json.loads(path.read_text())["panels"]:
        for target in panel.get("targets", []):
            expression = target["expr"].replace("$customer", "exchange").replace("$environment", "local-lab")
            queries.append((panel["title"], expression))
results = []
for name, expression in queries:
    url = "http://127.0.0.1:59090/api/v1/query?" + urllib.parse.urlencode({"query": expression})
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        result = json.load(error)
    results.append({"name": name, "expr": expression, "response": result})
(HERE / "query-validation.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
assert all(item["response"]["status"] == "success" for item in results), "promql_query_failed"
targets = [item["metric"] for item in results[0]["response"]["data"]["result"]]
assert len(targets) == 4, "local_customer_inventory_mismatch"
assert {item["job"] for item in targets} == {"exchange-api", "postgres", "blackbox-http"}
assert all(item["customer"] == "exchange" and item.get("role") in {"app", "data", "standby", "probe"} for item in targets)

password = (ROOT / "deploy/observability/.state/grafana-password").read_text().strip()
authorization = "Basic " + base64.b64encode(("operator:" + password).encode()).decode()
grafana = []
for uid in ("msp-overview", "msp-service", "msp-infra", "msp-db"):
    request = urllib.request.Request("http://127.0.0.1:53001/api/dashboards/uid/" + uid,
                                     headers={"Authorization": authorization})
    with urllib.request.urlopen(request, timeout=10) as response:
        dashboard = json.load(response)["dashboard"]
    exprs = [target["expr"] for panel in dashboard["panels"] for target in panel.get("targets", [])]
    assert all("chaos-" not in expr and "container_" not in expr for expr in exprs)
    assert all('customer="$customer"' in expr and 'environment=~"$environment"' in expr for expr in exprs)
    grafana.append({"uid": uid, "title": dashboard["title"], "queries": len(exprs), "customer_scope_loaded": True})
alert_url = "http://127.0.0.1:59093/api/v2/alerts?" + urllib.parse.urlencode({"filter": config["alert_matchers"]}, doseq=True)
with urllib.request.urlopen(alert_url, timeout=10) as response:
    alerts = json.load(response)
assert all(item["labels"].get("customer") == "exchange" and item["labels"].get("component") != "reporting"
           and item["labels"].get("role") != "bot" for item in alerts)
summary = {
    "checked_at": dt.datetime.now(dt.timezone.utc).isoformat(), "scope": "local-customer-workload",
    "queries_passed": len(results), "customer_targets": targets, "grafana": grafana,
    "no_data": sorted(set(item["name"] for item in results if not item["response"]["data"]["result"])),
    "aws_verified": False,
}
(HERE / "runtime-check.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print(json.dumps(summary, ensure_ascii=False))
