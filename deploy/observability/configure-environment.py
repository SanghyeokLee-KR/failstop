"""Prepare matching Prometheus, Alertmanager and report configuration without deployment."""
import argparse
import importlib.util
import ipaddress
import json
from pathlib import Path
import sys
import re
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "reporting"))
from scope import scoped_config


def endpoint(url):
    if not isinstance(url, str) or len(url) > 512 or any(char.isspace() for char in url):
        raise ValueError("invalid_monitoring_url")
    parsed = urlsplit(url)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise ValueError("monitoring_url_must_be_an_origin_without_credentials")
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError("invalid_monitoring_port")
    try:
        address = ipaddress.ip_address(parsed.hostname)
    except ValueError:
        if not re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?", parsed.hostname):
            raise ValueError("invalid_monitoring_host") from None
    else:
        if address.is_link_local or address.is_unspecified or address.is_multicast:
            raise ValueError("unsafe_monitoring_host")
    return url.rstrip("/")


def configuration(environment, customer, reporter_url, prometheus_url, alertmanager_url):
    if environment not in {"local-lab", "aws"}:
        raise ValueError("unsupported_environment")
    reporter_url, prometheus_url, alertmanager_url = map(endpoint, (reporter_url, prometheus_url, alertmanager_url))
    report = scoped_config(json.loads((HERE.parent / "reporting/config.example.json").read_text(encoding="utf-8")),
                           customer, environment)
    report.update(analyzer="facts-only", prometheus_url=prometheus_url, alertmanager_url=alertmanager_url)
    prometheus = (HERE / "prometheus.yml").read_text(encoding="utf-8")
    prometheus = prometheus.replace("    customer: exchange\n", f"    customer: {customer}\n    environment: {environment}\n")
    prometheus = prometheus.replace("labels: {customer: exchange, service: observability}",
                                    f"labels: {{customer: {customer}, environment: {environment}, service: observability}}")
    if environment == "local-lab":
        if customer != "exchange":
            raise ValueError("local_lab_inventory_uses_exchange")
        spec = importlib.util.spec_from_file_location("local_alerts", HERE / "activate-local-alerts.py")
        local = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(local)
        alerts = local.local_config().replace("http://host.docker.internal:59188/reporter/alerts", reporter_url + "/reporter/alerts")
        for job, path in (("linux-host", "/node/metrics"), ("operations-reporter", "/reporter/metrics")):
            prometheus = prometheus.replace(f"  - job_name: {job}\n", f"  - job_name: {job}\n    metrics_path: {path}\n")
    else:
        alerts = (HERE / "alertmanager.yml").read_text(encoding="utf-8")
        alerts = alerts.replace("http://host.docker.internal:9088/alerts", reporter_url + "/alerts")
    return {"prometheus.yml": prometheus, "alertmanager.yml": alerts,
            "reporting.json": json.dumps(report, ensure_ascii=False, indent=2) + "\n"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", choices=("local-lab", "aws"), required=True)
    parser.add_argument("--customer", default="exchange")
    parser.add_argument("--reporter-url", required=True, help="Origin reachable from monitoring containers")
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:59090", help="Origin reachable from the Linux report service")
    parser.add_argument("--alertmanager-url", default="http://127.0.0.1:59093")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    files = configuration(args.environment, args.customer, args.reporter_url, args.prometheus_url, args.alertmanager_url)
    if args.write:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        for name, contents in files.items():
            (args.output_dir / name).write_text(contents, encoding="utf-8", newline="\n")
    print(json.dumps({"environment": args.environment, "customer": args.customer,
                      "files": list(files), "written": args.write, "deployed": False}))


if __name__ == "__main__":
    main()
