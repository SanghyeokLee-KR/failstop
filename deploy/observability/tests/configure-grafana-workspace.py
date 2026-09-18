"""Configure and verify the native Grafana operations workspace."""
import argparse
import base64
import ipaddress
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


HERE = Path(__file__).resolve().parents[1]
DASHBOARDS = ("msp-overview", "msp-service", "msp-infra", "msp-db",
              "msp-db-workload", "msp-db-replication")
PREFERENCES = {"homeDashboardUID": "msp-overview", "timezone": "Asia/Seoul",
               "language": "ko-KR", "theme": "dark"}
BOOKMARKS = ("/alerting/list", "/connections/datasources", "/admin/users")


class ConfigurationError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # An unexpected redirect must not carry the administrator credential elsewhere.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_url(value):
    try:
        parsed = urllib.parse.urlsplit(value)
        parsed.port
    except ValueError:
        raise ConfigurationError("grafana_url_invalid") from None
    try:
        loopback = parsed.hostname == "localhost" or ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        loopback = False
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or any(char.isspace() for char in value)
            or parsed.scheme == "http" and not loopback):
        raise ConfigurationError("grafana_url_requires_https_or_loopback_without_credentials")
    return value.rstrip("/")


class Grafana:
    def __init__(self, url, user, password_file):
        self.url = validate_url(url)
        try:
            password = Path(password_file).read_text(encoding="utf-8-sig").strip()
        except (OSError, UnicodeError):
            raise ConfigurationError("grafana_password_file_unreadable") from None
        if (not password or any(char in password for char in "\r\n\x00") or not user
                or any(char in user for char in ":\r\n\x00")):
            raise ConfigurationError("grafana_credentials_invalid")
        self.authorization = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, payload=None):
        body = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(self.url + path, data=body, method=method,
                                         headers={"Authorization": self.authorization,
                                                  "Accept": "application/json",
                                                  "Content-Type": "application/json"})
        try:
            with self.opener.open(request, timeout=15) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ConfigurationError("grafana_response_too_large")
            return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            # Error bodies may contain request headers or deployment details.
            raise ConfigurationError(f"grafana_http_{error.code}: {method} {path}") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ConfigurationError(f"grafana_connection_failed: {method} {path}") from None
        except (ValueError, UnicodeError):
            raise ConfigurationError(f"grafana_response_invalid: {method} {path}") from None


def preferences_patch(current, include_bookmarks=False):
    if not isinstance(current, dict):
        raise ConfigurationError("grafana_preferences_invalid")
    patch = {key: value for key, value in PREFERENCES.items() if current.get(key) != value}
    if include_bookmarks:
        navbar = current.get("navbar") or {}
        if not isinstance(navbar, dict):
            raise ConfigurationError("grafana_navbar_invalid")
        existing = navbar.get("bookmarkUrls") or []
        if not isinstance(existing, list) or any(not isinstance(item, str) for item in existing):
            raise ConfigurationError("grafana_bookmarks_invalid")
        merged = existing + [item for item in BOOKMARKS if item not in existing]
        if merged != existing:
            patch["navbar"] = {**navbar, "bookmarkUrls": merged}
    return patch


def dashboard_state(api, uid):
    result = api.request("GET", f"/api/search?type=dash-db&dashboardUIDs={uid}")
    if (not isinstance(result, list) or len(result) != 1 or not isinstance(result[0], dict)
            or result[0].get("uid") != uid or not isinstance(result[0].get("isStarred"), bool)):
        raise ConfigurationError(f"grafana_dashboard_invalid: {uid}")
    return result[0]["isStarred"]


def configure(api):
    health = api.request("GET", "/api/health")
    if not isinstance(health, dict) or health.get("database") != "ok":
        raise ConfigurationError("grafana_not_ready")
    org = api.request("GET", "/api/org/preferences")
    user = api.request("GET", "/api/user/preferences")
    org_patch = preferences_patch(org)
    user_patch = preferences_patch(user, include_bookmarks=True)
    # Resolve every provisioned dashboard before changing the operator's home.
    starred = {uid: dashboard_state(api, uid) for uid in DASHBOARDS}
    changed = []
    for path, patch in (("/api/org/preferences", org_patch), ("/api/user/preferences", user_patch)):
        if patch:
            api.request("PATCH", path, patch)
            changed.append(path)
    for uid, present in starred.items():
        if not present:
            api.request("POST", f"/api/user/stars/dashboard/uid/{uid}")
            changed.append(uid)
    if preferences_patch(api.request("GET", "/api/org/preferences")):
        raise ConfigurationError("grafana_org_preferences_verification_failed")
    final_user = api.request("GET", "/api/user/preferences")
    if preferences_patch(final_user, include_bookmarks=True):
        raise ConfigurationError("grafana_user_preferences_verification_failed")
    expected_navbar = user_patch.get("navbar", user.get("navbar", {}))
    if any(final_user.get("navbar", {}).get(key) != value for key, value in expected_navbar.items()):
        raise ConfigurationError("grafana_existing_navbar_verification_failed")
    if not all(dashboard_state(api, uid) for uid in DASHBOARDS):
        raise ConfigurationError("grafana_stars_verification_failed")
    return {"status": "PASS", "changed": changed, "preferences": dict(PREFERENCES),
            "starred_dashboards": len(DASHBOARDS),
            "bookmark_count": len(final_user["navbar"]["bookmarkUrls"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:53001")
    parser.add_argument("--user", default="operator")
    parser.add_argument("--password-file", type=Path, default=HERE / ".state/grafana-password")
    args = parser.parse_args()
    try:
        result = configure(Grafana(args.url, args.user, args.password_file))
    except ConfigurationError as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
