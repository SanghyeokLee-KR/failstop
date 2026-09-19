"""Private operator console; Grafana authenticates operators, fixed adapters read evidence."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from metrics import MetricsClient, MetricsUnavailable

ROOT = Path(__file__).resolve().parent
REPORT_PATH = re.compile(r"/reports/[0-9a-f]{64}/[0-9]{10}/[0-9a-f]{64}/report\.(pdf|html)")


class UpstreamError(Exception):
    def __init__(self, status=502):
        self.status = status


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def fetch(url, authorization=None, binary=False, payload=None):
    headers = {"Accept": "application/json"}
    if authorization:
        headers["Authorization"] = authorization
    try:
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, headers=headers, data=json.dumps(payload).encode() if payload is not None else None)
        with urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({})).open(request, timeout=8) as response:
            data = response.read(8 * 1024 * 1024 + 1)
            if len(data) > 8 * 1024 * 1024:
                raise UpstreamError()
            return data if binary else json.loads(data)
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        raise UpstreamError(status) from None
    except (OSError, ValueError, urllib.error.URLError):
        raise UpstreamError() from None


def config_from_env():
    return {
        "public_url": os.getenv("CONSOLE_PUBLIC_URL", "http://127.0.0.1:53002"),
        "grafana_url": os.getenv("CONSOLE_GRAFANA_URL", "http://127.0.0.1:53001"),
        "prometheus_url": os.getenv("CONSOLE_PROMETHEUS_URL", "http://127.0.0.1:59090"),
        "reporter_url": os.getenv("CONSOLE_REPORTER_URL", "http://127.0.0.1:59188/reporter"),
        "reporter_token_file": os.getenv("CONSOLE_REPORTER_TOKEN_FILE", ""),
        "org_id": int(os.getenv("CONSOLE_GRAFANA_ORG_ID", "1")),
        "state": os.getenv("CONSOLE_STATE", str(ROOT / ".state/console.sqlite3")),
        "aws": {
            "customer": os.getenv("CONSOLE_AWS_CUSTOMER", "exchange"), "environment": "aws",
            "region": os.getenv("CONSOLE_AWS_REGION", "ap-northeast-2"),
            "load_balancer": os.getenv("CONSOLE_AWS_LOAD_BALANCER", ""),
            "target_group": os.getenv("CONSOLE_AWS_TARGET_GROUP", ""),
            "auto_scaling_group": os.getenv("CONSOLE_AWS_ASG", ""),
            "volume_id": os.getenv("CONSOLE_AWS_VOLUME_ID", ""),
        },
        "links": {
            "grafana": os.getenv("CONSOLE_GRAFANA_PUBLIC_URL", "http://127.0.0.1:53001"),
            "prometheus": os.getenv("CONSOLE_PROMETHEUS_PUBLIC_URL", "http://127.0.0.1:59090"),
            "alertmanager": os.getenv("CONSOLE_ALERTMANAGER_PUBLIC_URL", "http://127.0.0.1:59093"),
        },
    }


def validate_config(config):
    public = urllib.parse.urlsplit(config["public_url"])
    if (public.scheme not in {"http", "https"} or not public.hostname or public.path not in {"", "/"}
            or public.username or public.password or public.query or public.fragment
            or (public.scheme == "http" and public.hostname not in {"127.0.0.1", "localhost", "::1"})):
        raise ValueError("public_url_requires_https_or_loopback")
    for value in [config[key] for key in ("grafana_url", "prometheus_url", "reporter_url")] + list(config["links"].values()):
        parsed = urllib.parse.urlsplit(value)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment):
            raise ValueError("invalid_upstream_url")
    if config["org_id"] < 1:
        raise ValueError("invalid_grafana_organization")
    return config


class ConsoleState:
    def __init__(self, path):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(path)) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS acknowledgements (id TEXT, customer TEXT, environment TEXT, actor TEXT, at REAL, note TEXT, PRIMARY KEY(id,customer,environment))")

    def acknowledgements(self, customer, environment):
        with closing(sqlite3.connect(self.path)) as db, db:
            rows = db.execute("SELECT id,actor,at,note FROM acknowledgements WHERE customer=? AND environment=? ORDER BY at DESC LIMIT 1000", (customer, environment)).fetchall()
        return {key: {"by": actor, "at": at, "note": note} for key, actor, at, note in rows}

    def acknowledge(self, identifier, customer, environment, actor, note):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("INSERT INTO acknowledgements VALUES(?,?,?,?,?,?) ON CONFLICT(id,customer,environment) DO UPDATE SET actor=excluded.actor,at=excluded.at,note=excluded.note",
                       (identifier, customer, environment, actor, time.time(), note))


class ConsoleServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, config):
        self.config = validate_config(config)
        self.metrics = MetricsClient(config["prometheus_url"])
        self.state = ConsoleState(config["state"])
        self.sessions = {}
        self.login_attempts = {}
        self.cloudwatch_cache = {}
        self.cloudwatch_lock = threading.Lock()
        self.lock = threading.Lock()
        self.slots = threading.BoundedSemaphore(8)
        public = urllib.parse.urlsplit(config["public_url"])
        self.origins = {public.scheme + "://" + public.netloc}
        if public.hostname in {"localhost", "127.0.0.1"}:
            self.origins.update(public.scheme + "://" + host + (":" + str(public.port) if public.port else "") for host in ("localhost", "127.0.0.1"))
        self.hosts = {urllib.parse.urlsplit(origin).netloc for origin in self.origins}
        self.secure = public.scheme == "https"
        super().__init__(address, Handler)

    def process_request(self, request_socket, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request_socket)
            return
        super().process_request(request_socket, client_address)

    def process_request_thread(self, request_socket, client_address):
        try:
            super().process_request_thread(request_socket, client_address)
        finally:
            self.slots.release()

    def grafana(self, path, auth, payload=None):
        return fetch(self.config["grafana_url"].rstrip("/") + path, auth, payload=payload)

    def aws_metrics(self, customer, environment, duration, session):
        from cloudwatch import collect_aws
        config = self.config.get("aws", {})
        selected = {key: value for key, value in config.items() if key not in {"customer", "environment"}} if customer == config.get("customer") and environment == config.get("environment") else {}
        cache_key = (session["user"]["id"], customer, environment, duration)
        with self.cloudwatch_lock:
            self.cloudwatch_cache = {key: value for key, value in self.cloudwatch_cache.items() if value[0] > time.time() - 60}
            if cache_key not in self.cloudwatch_cache:
                result = collect_aws(selected, duration, lambda body: self.grafana("/api/ds/query", session["auth"], body))
                encoded = json.dumps(result, allow_nan=False).encode()
                if len(encoded) > 4 * 1024 * 1024:
                    return result
                while self.cloudwatch_cache and (len(self.cloudwatch_cache) >= 8 or sum(len(value[1]) for value in self.cloudwatch_cache.values()) + len(encoded) > 8 * 1024 * 1024):
                    self.cloudwatch_cache.pop(next(iter(self.cloudwatch_cache)))
                self.cloudwatch_cache[cache_key] = (time.time(), encoded)
            return json.loads(self.cloudwatch_cache[cache_key][1])

    def identity(self, auth):
        result = self.grafana("/api/user", auth)
        if result.get("orgId") != self.config["org_id"]:
            raise UpstreamError(403)
        orgs = self.grafana("/api/user/orgs", auth)
        role = next((org["role"] for org in orgs if org["orgId"] == self.config["org_id"]), None)
        if role not in {"Viewer", "Editor", "Admin"}:
            raise UpstreamError(403)
        return {"id": result["id"], "login": result["login"], "name": result.get("name") or result["login"],
                "role": role, "isAdmin": role == "Admin", "isGrafanaAdmin": bool(result.get("isGrafanaAdmin"))}

    def report_request(self, path, binary=False):
        token_path = self.config.get("reporter_token_file")
        if not token_path:
            raise UpstreamError(503)
        try:
            token = Path(token_path).read_text(encoding="utf-8").strip()
        except OSError:
            raise UpstreamError(503) from None
        if not 24 <= len(token) <= 512:
            raise UpstreamError(503)
        return fetch(self.config["reporter_url"].rstrip("/") + path, "Bearer " + token, binary)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        # Report paths contain grants. Neither paths, credentials nor bodies enter access logs.
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(12)

    def reply(self, status, value, mime="application/json; charset=utf-8", cookie=None, download=None):
        data = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        if download:
            self.send_header("Content-Disposition", 'attachment; filename="operations-report.' + download + '"')
        self.end_headers()
        self.wfile.write(data)

    def error(self, status, code):
        self.reply(status, {"error": code})

    def host_allowed(self):
        return self.headers.get("Host") in self.server.hosts

    def cookie(self, identifier, age=28800):
        return "msp_session=" + identifier + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + str(age) + ("; Secure" if self.server.secure else "")

    def session_id(self):
        cookies = SimpleCookie()
        try:
            cookies.load(self.headers.get("Cookie", ""))
            return cookies["msp_session"].value if "msp_session" in cookies else ""
        except CookieError:
            return ""

    def session(self):
        identifier = self.session_id()
        with self.server.lock:
            value = self.server.sessions.get(identifier)
            if value and value["expires"] < time.time():
                self.server.sessions.pop(identifier, None)
                value = None
        if value and value["checked"] < time.time() - 60:
            try:
                value["user"] = self.server.identity(value["auth"])
                value["checked"] = time.time()
            except UpstreamError:
                with self.server.lock:
                    self.server.sessions.pop(identifier, None)
                return None
        return value

    def query(self, allowed):
        parsed = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query, keep_blank_values=True, max_num_fields=12)
        if set(parsed) - set(allowed) or any(len(values) != 1 for values in parsed.values()):
            raise ValueError("invalid_query")
        return {key: values[0] for key, values in parsed.items()}

    def scope(self, values):
        customer = values.get("customer", "")
        environment = values.get("environment", "")
        if not all(re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", value) for value in (customer, environment)):
            raise ValueError("invalid_scope")
        if not any(row["customer"] == customer and row["environment"] == environment for row in self.server.metrics.scopes()):
            raise ValueError("unknown_scope")
        return customer, environment

    def reports(self, customer, environment):
        try:
            result = self.server.report_request("/console/reports?" + urllib.parse.urlencode({"customer": customer, "environment": environment}))
            for report in result.get("reports", []):
                report["links"] = {key: "/api/report-file?" + urllib.parse.urlencode({"path": path})
                                   for key, path in report.get("links", {}).items() if isinstance(path, str) and REPORT_PATH.fullmatch(path)}
            return result
        except UpstreamError:
            return {"state": "error", "reports": [], "message": "보고 서비스 연결 확인 필요"}

    def admin(self, session):
        auth = session["auth"]
        result = {"state": "ok", "users": [], "datasources": [], "dashboards": [], "links": self.server.config["links"], "errors": []}
        paths = {"dashboards": "/api/search?type=dash-db", "datasources": "/api/datasources"}
        if session["user"]["isAdmin"]:
            paths["users"] = "/api/org/users"
        else:
            user = session["user"]
            result["users"] = [{"userId": user["id"], "login": user["login"], "name": user["name"], "role": user["role"]}]
        with ThreadPoolExecutor(max_workers=3) as pool:
            futures = {name: pool.submit(self.server.grafana, path, auth) for name, path in paths.items()}
            for name, future in futures.items():
                try:
                    rows = future.result()
                    if name == "datasources":
                        for row in rows:
                            parsed = urllib.parse.urlsplit(row.get("url", ""))
                            # Connection addresses are useful; embedded credentials and query arguments are not.
                            safe_url = parsed.scheme + "://" + (parsed.hostname or "") + (":" + str(parsed.port) if parsed.port else "") + parsed.path if parsed.hostname else ""
                            result[name].append({key: row.get(key) for key in ("uid", "name", "type", "isDefault")}
                                               | {"url": safe_url, "state": "configured"})
                    elif name == "users":
                        result[name] = [{key: row.get(key) for key in ("userId", "login", "name", "role")} for row in rows]
                    else:
                        result[name] = [{key: row.get(key) for key in ("uid", "title", "url")} for row in rows]
                except UpstreamError:
                    result["errors"].append(name)
                    result["state"] = "partial"
        return result

    def do_GET(self):
        if not self.host_allowed():
            self.error(403, "invalid_host")
            return
        path = urllib.parse.urlsplit(self.path).path
        if path == "/health":
            self.reply(200, {"status": "ok"})
            return
        if not path.startswith("/api/"):
            relative = "index.html" if path == "/" else path.removeprefix("/static/").lstrip("/")
            file = (ROOT / "static" / relative).resolve()
            static = (ROOT / "static").resolve()
            if not file.is_relative_to(static) or not file.is_file() or file.is_symlink():
                self.error(404, "not_found")
                return
            mime = {".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".html": "text/html; charset=utf-8"}.get(file.suffix, mimetypes.guess_type(str(file))[0] or "application/octet-stream")
            self.reply(200, file.read_bytes(), mime)
            return
        session = self.session()
        if not session:
            self.error(401, "login_required")
            return
        try:
            if path == "/api/bootstrap":
                try:
                    scopes = self.server.metrics.scopes()
                    source_state = "ok"
                except (OSError, ValueError, MetricsUnavailable):
                    scopes, source_state = [], "error"
                self.reply(200, {"user": session["user"], "scopes": scopes, "links": self.server.config["links"],
                                 "features": {"reporting": bool(self.server.config.get("reporter_token_file"))}, "sourceState": source_state})
            elif path == "/api/metrics":
                values = self.query({"customer", "environment", "view", "range", "service", "instance"})
                customer, environment = self.scope(values)
                duration = int(values.get("range", "3600"))
                if duration not in {3600, 21600, 86400, 604800, 2592000} or values.get("view", "overview") not in {"overview", "spring", "aws", "db"}:
                    raise ValueError("invalid_view_or_range")
                result = self.server.metrics.snapshot(customer, environment, duration, values.get("view", "overview"), values.get("service", ""), values.get("instance", ""))
                if values.get("view") == "aws":
                    aws = self.server.aws_metrics(customer, environment, duration, session)
                    result["metrics"].update(aws["metrics"])
                    result.setdefault("sources", []).extend(aws["sources"])
                acknowledged = self.server.state.acknowledgements(customer, environment)
                for alert in result.get("alerts", []):
                    alert["acknowledgement"] = acknowledged.get(alert["id"])
                self.reply(200, result)
            elif path in {"/api/reports", "/api/events"}:
                values = self.query({"customer", "environment"})
                customer, environment = self.scope(values)
                result = self.reports(customer, environment)
                if path == "/api/events":
                    events = [{"id": row["id"], "time": row["created"], "type": row.get("kind", "report"), "severity": row.get("severity", "info"),
                               "target": ", ".join(row.get("targets", [])), "title": row.get("title", "운영 보고"),
                               "summary": row.get("summary", ""), "status": row.get("alertState") or row["status"], "links": row.get("links", {})}
                              for row in result.get("reports", [])]
                    for key, ack in self.server.state.acknowledgements(customer, environment).items():
                        events.append({"id": "ack:" + key, "time": ack["at"], "type": "acknowledgement", "severity": "info", "target": ack["by"],
                                       "title": "알림 확인", "summary": ack["note"], "status": "acknowledged", "links": {}})
                    result = {"state": result["state"], "events": sorted(events, key=lambda event: event["time"], reverse=True)[:100]}
                self.reply(200, result)
            elif path == "/api/admin":
                self.reply(200, self.admin(session))
            elif path == "/api/report-file":
                values = self.query({"path"})
                artifact = values.get("path", "")
                matched = REPORT_PATH.fullmatch(artifact)
                if not matched:
                    raise ValueError("invalid_report_path")
                data = self.server.report_request(artifact, binary=True)
                extension = matched[1]
                self.reply(200, data, "application/pdf" if extension == "pdf" else "text/html; charset=utf-8", download=extension)
            else:
                self.error(404, "not_found")
        except (ValueError, KeyError):
            self.error(400, "invalid_request")
        except (UpstreamError, OSError, sqlite3.Error, MetricsUnavailable):
            self.error(502, "source_unavailable")

    def do_POST(self):
        if not self.host_allowed() or self.headers.get("Origin") not in self.server.origins:
            self.error(403, "same_origin_required")
            return
        if (self.headers.get_content_type() != "application/json" or self.headers.get("Transfer-Encoding")
                or len(self.headers.get_all("Content-Length", [])) != 1):
            self.error(415, "json_required")
            return
        try:
            length = int(self.headers["Content-Length"])
            if not 2 <= length <= 16384:
                raise ValueError("body_limit")
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict):
                raise ValueError("invalid_body")
        except (ValueError, TimeoutError):
            self.error(400, "invalid_body")
            return
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/login":
            username, password = data.get("username"), data.get("password")
            if not isinstance(username, str) or not isinstance(password, str) or not 1 <= len(username) <= 128 or not 1 <= len(password) <= 4096 or ":" in username:
                self.error(400, "invalid_credentials")
                return
            now = time.time()
            with self.server.lock:
                self.server.login_attempts = {key: [value for value in values if value > now - 300]
                                              for key, values in self.server.login_attempts.items() if any(value > now - 300 for value in values)}
                if len(self.server.login_attempts) >= 1024 and self.client_address[0] not in self.server.login_attempts:
                    self.error(429, "login_rate_limit")
                    return
                attempts = [value for value in self.server.login_attempts.get(self.client_address[0], []) if value > now - 300]
                self.server.login_attempts[self.client_address[0]] = attempts
                if len(attempts) >= 10:
                    self.error(429, "login_rate_limit")
                    return
                attempts.append(now)
            auth = "Basic " + base64.b64encode((username + ":" + password).encode()).decode()
            try:
                user = self.server.identity(auth)
            except UpstreamError as error:
                self.error(401 if error.status in {401, 403} else 503, "login_failed")
                return
            identifier = secrets.token_urlsafe(32)
            with self.server.lock:
                self.server.sessions = {key: value for key, value in self.server.sessions.items() if value["expires"] > now}
                if len(self.server.sessions) >= 256:
                    self.error(503, "session_capacity")
                    return
                self.server.sessions.pop(self.session_id(), None)
                self.server.sessions[identifier] = {"auth": auth, "user": user, "expires": now + 28800, "checked": now}
                self.server.login_attempts.pop(self.client_address[0], None)
            self.reply(200, {"user": user}, cookie=self.cookie(identifier))
            return
        session = self.session()
        if not session:
            self.error(401, "login_required")
            return
        if path == "/api/logout":
            with self.server.lock:
                self.server.sessions.pop(self.session_id(), None)
            self.reply(200, {"status": "ok"}, cookie=self.cookie("", 0))
        elif path == "/api/ack":
            if session["user"]["role"] not in {"Admin", "Editor"}:
                self.error(403, "editor_required")
                return
            try:
                customer, environment = self.scope(data)
                identifier, note = data.get("id", ""), data.get("note", "")
                if not isinstance(identifier, str) or not isinstance(note, str) or len(note) > 500:
                    raise ValueError("invalid_acknowledgement")
                snapshot = self.server.metrics.snapshot(customer, environment, 3600, "overview")
                if not any(alert["id"] == identifier for alert in snapshot.get("alerts", [])):
                    raise ValueError("active_alert_required")
                self.server.state.acknowledge(identifier, customer, environment, session["user"]["login"], note)
                self.reply(200, {"status": "acknowledged"})
            except (ValueError, KeyError):
                self.error(400, "invalid_acknowledgement")
            except (OSError, sqlite3.Error, MetricsUnavailable):
                self.error(502, "source_unavailable")
        else:
            self.error(404, "not_found")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=53002)
    parser.add_argument("--config")
    args = parser.parse_args()
    config = config_from_env()
    if args.config:
        config.update(json.loads(Path(args.config).read_text(encoding="utf-8")))
    server = ConsoleServer((args.bind, args.port), config)
    print("MSP console listening", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
