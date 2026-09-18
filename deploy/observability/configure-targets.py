"""Validate an explicit inventory and atomically update Prometheus file discovery."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit

GROUPS = {"hosts", "apps", "databases", "reporters", "probes"}


def endpoint(value, probe=False):
    if not isinstance(value, str) or len(value) > 512:
        raise ValueError("invalid_target")
    parsed = urlsplit(value if probe else "//" + value)
    if parsed.username or parsed.password or parsed.query or parsed.fragment or not parsed.hostname:
        raise ValueError("target_must_not_contain_credentials_or_query")
    if probe and parsed.scheme not in {"http", "https"}:
        raise ValueError("invalid_probe_scheme")
    if not probe and (parsed.path or not parsed.port or not 1 <= parsed.port <= 65535):
        raise ValueError("target_requires_host_and_port")
    host = parsed.hostname
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if not re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?", host):
            raise ValueError("invalid_target_hostname") from None
    else:
        if address.is_unspecified or address.is_multicast or address.is_link_local:
            raise ValueError("unsafe_target_address")
    return value


def generate(inventory, allow_empty=False):
    if not isinstance(inventory, dict) or set(inventory) != GROUPS:
        raise ValueError("inventory_requires_all_five_groups")
    result = {}
    for group in sorted(GROUPS):
        items = inventory[group]
        if not isinstance(items, list) or len(items) > 200 or (not items and not allow_empty):
            raise ValueError("empty_or_excessive_target_group")
        targets, seen = [], set()
        for item in items:
            if not isinstance(item, dict) or set(item) != {"address", "labels"}:
                raise ValueError("invalid_target_fields")
            address = endpoint(item["address"], group == "probes")
            if address in seen:
                raise ValueError("duplicate_target")
            seen.add(address)
            labels = item["labels"]
            if not isinstance(labels, dict) or not {"customer", "service"} <= set(labels) or set(labels) - {"customer", "service", "role", "node", "environment"}:
                raise ValueError("invalid_target_labels")
            if any(not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9_.-]{1,80}", value) for value in labels.values()):
                raise ValueError("invalid_target_label_value")
            targets.append({"targets": [address], "labels": labels})
        result[group] = targets
    return result


def write_targets(output, targets):
    output.mkdir(parents=True, exist_ok=True)
    for group, entries in targets.items():
        descriptor, temporary = tempfile.mkstemp(prefix=".targets-", dir=output)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as target:
                json.dump(entries, target, indent=2, ensure_ascii=False)
                target.write("\n")
            os.chmod(temporary, 0o644)
            os.replace(temporary, output / (group + ".json"))
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--allow-empty", action="store_true")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    if args.inventory.stat().st_size > 131072:
        raise ValueError("inventory_too_large")
    targets = generate(json.loads(args.inventory.read_text(encoding="utf-8-sig")), args.allow_empty)
    if args.write:
        write_targets(args.output_dir, targets)
    print(json.dumps({"written": args.write, "counts": {group: len(items) for group, items in targets.items()}}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TypeError, KeyError):
        print('{"error":"target_inventory_rejected"}')
        raise SystemExit(1) from None
