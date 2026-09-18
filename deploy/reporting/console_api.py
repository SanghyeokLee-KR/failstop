"""Read customer-scoped report metadata without collecting or sending evidence."""
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.parse import parse_qsl, urlsplit

import artifacts
from presentation import build_brief
from transport import redact

SCOPE = re.compile(r"[a-zA-Z0-9_.-]{1,80}")
LINK_TTL_SECONDS = 3600


def parse_query(path):
    parsed = urlsplit(path)
    if parsed.path != "/console/reports" or parsed.scheme or parsed.netloc or parsed.fragment:
        raise ValueError("invalid_console_path")
    pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=3)
    query = dict(pairs)
    if len(query) != len(pairs) or set(query) - {"customer", "environment", "limit"}:
        raise ValueError("invalid_console_query")
    if any(not SCOPE.fullmatch(query.get(field, "")) for field in ("customer", "environment")):
        raise ValueError("console_scope_required")
    limit = query.get("limit", "50")
    if not re.fullmatch(r"[1-9][0-9]{0,2}", limit) or int(limit) > 100:
        raise ValueError("invalid_console_limit")
    return query["customer"], query["environment"], int(limit)


def report_scope(report, payload):
    evidence = report.get("evidence", {}) if report else {}
    scope = evidence.get("scope", {})
    candidates = []
    if scope:
        candidates.append(scope)
    trigger = []
    for fact in evidence.get("facts", []):
        if fact.get("id") == "trigger":
            trigger.extend(fact.get("records", []))
    if not report:
        trigger = payload.get("incident", [])
        if payload.get("scope"):
            candidates.append(payload["scope"])
    candidates.extend(row.get("labels", {}) for row in trigger)
    if not candidates:
        return None
    scopes = set()
    for candidate in candidates:
        customer, environment = candidate.get("customer"), candidate.get("environment")
        if any(not isinstance(value, str) or not SCOPE.fullmatch(value) for value in (customer, environment)):
            return None
        scopes.add((customer, environment))
    if len(scopes) != 1:
        return None
    matched = next(iter(scopes))
    # A report may combine current alerts and its trigger; cross-customer evidence stays private.
    for fact in evidence.get("facts", []):
        for record in fact.get("records", []) if fact.get("id") in {"alerts", "trigger"} else []:
            labels = record.get("labels", {})
            if (labels.get("customer"), labels.get("environment")) != matched:
                return None
        for sample in fact.get("samples", []):
            labels = sample.get("labels", {})
            if any(field in labels and labels[field] != value
                   for field, value in zip(("customer", "environment"), matched)):
                return None
    return matched


def stored_links(report_id, artifact_status, settings, secret, now):
    links = {"pdf": None, "html": None}
    if artifact_status != "ready" or settings is None or secret is None:
        return links
    identifier = hashlib.sha256(report_id.encode()).hexdigest()
    root = Path(settings["directory"])
    folder = root / identifier
    expires = int(now) + LINK_TTL_SECONDS
    try:
        if root.is_symlink() or folder.is_symlink():
            return links
        resolved_root = root.resolve()
        for extension in links:
            target = folder / ("report." + extension)
            if (target.is_symlink() or not target.is_file()
                    or not target.resolve().is_relative_to(resolved_root)
                    or not 1 <= target.stat().st_size <= artifacts.LIMIT):
                continue
            signature = artifacts.signature(identifier, expires, extension, secret)
            links[extension] = f"/reports/{identifier}/{expires}/{signature}/report.{extension}"
    except OSError:
        return links
    return links


def list_reports(store, config, path, now=None):
    customer, environment, limit = parse_query(path)
    now = time.time() if now is None else now
    settings = artifacts.options(config)
    secret = None
    if settings:
        try:
            secret = artifacts.key()
        except (OSError, ValueError):
            secret = None
    result = []
    with store.connect() as db:
        rows = db.execute("SELECT j.id,j.kind,j.payload,j.status,j.created,j.sent,j.report,"
                          "a.status AS artifact_status FROM jobs j LEFT JOIN report_artifacts a ON j.id=a.id "
                          "ORDER BY j.created DESC,j.id DESC")
        for row in rows:
            try:
                report = json.loads(row["report"]) if row["report"] else None
                payload = json.loads(row["payload"])
                if report_scope(report, payload) != (customer, environment):
                    continue
                if report is None:
                    report = {"id": row["id"], "kind": row["kind"], "evidence": {
                        "scope": {"customer": customer, "environment": environment},
                        "collected_at": payload.get("window_end"), "facts": [
                            {"id": "trigger", "status": "ok", "records": payload.get("incident", [])}]}}
                brief = build_brief(report)
                entry = {"id": row["id"], "kind": row["kind"], "created": row["created"],
                         "sent": row["sent"], "status": row["status"], "title": redact(brief["title"]),
                         "severity": brief["intervention"]["priority"],
                         "summary": redact(brief["lead"] + " · " + brief["impact"]),
                         "alertState": brief["status"]["code"] if brief["status"]["code"] in {"firing", "resolved"} else None,
                         "targets": list(dict.fromkeys(redact(event["target"]) for event in brief["events"])),
                         "customer": customer, "environment": environment,
                         "links": stored_links(row["id"], row["artifact_status"], settings, secret, now)}
            except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
                # Corrupt or unscoped historical records cannot establish a customer boundary.
                continue
            result.append(entry)
            if len(result) == limit:
                break
    return {"state": "ok", "reports": result}
