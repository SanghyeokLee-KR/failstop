"""Read sanitized incident delivery metadata from the local outbox."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3


def audit(state, since, instance, alertname):
    with closing(sqlite3.connect(Path(state).resolve().as_uri() + "?mode=ro", uri=True)) as database:
        database.row_factory = sqlite3.Row
        rows = database.execute("SELECT id,status,attempts,created,sent,error,payload,report FROM jobs "
                                "WHERE kind='incident' AND created>=? ORDER BY created", (since,)).fetchall()
    result = []
    for row in rows:
        triggers = json.loads(row["payload"]).get("incident", [])
        matching = [item for item in triggers if item.get("labels", {}).get("instance") == instance
                    and item.get("labels", {}).get("alertname") == alertname]
        if matching:
            report = json.loads(row["report"]) if row["report"] else {}
            result.append({**{key: row[key] for key in ("id", "status", "attempts", "created", "sent", "error")},
                           "transitions": sorted({item["status"] for item in matching}),
                           "scope": report.get("evidence", {}).get("scope", {}),
                           "analysis": report.get("analysis", {}).get("mode"),
                           "facts": [{"id": fact["id"], "status": fact["status"]}
                                     for fact in report.get("evidence", {}).get("facts", [])]})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=Path("/var/lib/failstop-reporting/outbox.sqlite3"))
    parser.add_argument("--since", type=float, required=True)
    parser.add_argument("--instance", required=True)
    parser.add_argument("--alertname", required=True)
    args = parser.parse_args()
    print(json.dumps(audit(args.state, args.since, args.instance, args.alertname), ensure_ascii=False))


if __name__ == "__main__":
    main()
