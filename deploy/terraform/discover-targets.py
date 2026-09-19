#!/usr/bin/env python3
"""EC2 태그를 읽어 Prometheus 파일 대상을 갱신한다. AWS 상태를 변경하지 않는다."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def targets(inventory, api_origin=None, cluster=None):
    hosts, apps, databases, reporters = [], [], [], []
    roles = {"app", "reader", "data", "standby", "bot"}
    for reservation in inventory.get("Reservations", []):
        for instance in reservation.get("Instances", []):
            tags = {item["Key"]: item["Value"] for item in instance.get("Tags", [])}
            if cluster is not None and tags.get("FailstopCluster") != cluster:
                continue
            role = tags.get("Role")
            if role not in roles or instance.get("State", {}).get("Name") != "running":
                continue
            address = str(ipaddress.IPv4Address(instance["PrivateIpAddress"]))
            labels = {"customer": "exchange", "node": instance["InstanceId"], "role": role, "environment": "aws"}
            hosts.append({"targets": [f"{address}:9100"], "labels": dict(labels, service="host")})
            if role in {"app", "reader"}:
                apps.append({"targets": [f"{address}:8081"], "labels": dict(labels, service="api")})
            if role in {"data", "standby"}:
                databases.append({"targets": [f"{address}:9187"], "labels": dict(labels, service="database")})
            if role == "bot":
                reporters.append({"targets": [f"{address}:9088"], "labels": dict(labels, service="reporter")})
    if not hosts:
        raise ValueError("Discovery returned no running hosts for the configured cluster")
    probes = [] if not api_origin else [{"targets": [api_origin.rstrip("/") + "/api/v1/markets"],
                                         "labels": {"customer": "exchange", "environment": "aws", "service": "api", "node": "public-api", "role": "probe"}}]
    return {"hosts.json": hosts, "apps.json": apps, "databases.json": databases, "reporters.json": reporters, "probes.json": probes}


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(content)
        os.chmod(name, 0o644)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def collect(config, directory, metrics, fetch):
    last_success_file = metrics / ".discovery-last-success"
    last_success = last_success_file.read_text().strip() if last_success_file.exists() else "0"
    try:
        found = targets(fetch(config), config.get("api_origin"), config.get("cluster"))
        for name, entries in found.items():
            atomic_write(directory / name, json.dumps(entries, indent=2) + "\n")
        last_success = str(int(time.time()))
        atomic_write(last_success_file, last_success)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        atomic_write(metrics / "failstop-discovery.prom", f"failstop_target_discovery_success 0\nfailstop_target_discovery_last_success_seconds {last_success}\n")
        raise
    atomic_write(metrics / "failstop-discovery.prom", f"failstop_target_discovery_success 1\nfailstop_target_discovery_last_success_seconds {last_success}\n")
    return len(found["hosts.json"])


def fetch_aws(config):
    result = subprocess.run(["aws", "ec2", "describe-instances", "--region", config["region"],
                             "--filters", f"Name=tag:FailstopCluster,Values={config['cluster']}",
                             "Name=instance-state-name,Values=running", "--output", "json"],
                            check=True, capture_output=True, text=True, timeout=25)
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("/etc/failstop/discovery.json"))
    parser.add_argument("--output", type=Path, default=Path("/opt/failstop/deploy/observability/targets"))
    parser.add_argument("--metrics", type=Path, default=Path("/var/lib/failstop/metrics"))
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    try:
        count = collect(config, args.output, args.metrics, fetch_aws)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Target discovery failed ({type(error).__name__}); inspect freshness and last successful target files") from None
    print(f"Updated {count} running host targets")


if __name__ == "__main__":
    main()
