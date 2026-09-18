"""Loopback-only development bridge for Docker Desktop to WSL observability."""
import argparse
import base64
import json
import ipaddress
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROUTES = {"/node/metrics": ("GET", "node", "/metrics"),
          "/reporter/metrics": ("GET", "reporter", "/metrics"),
          "/reporter/health": ("GET", "reporter", "/health"),
          "/reporter/alerts": ("POST", "reporter", "/alerts")}


def route_for(path):
    parsed = urllib.parse.urlsplit(path)
    if parsed.path == "/reporter/console/reports" and not (parsed.scheme or parsed.netloc or parsed.fragment):
        try:
            pairs = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=3)
            query = dict(pairs)
            if (len(query) != len(pairs) or set(query) - {"customer", "environment", "limit"}
                    or any(not re.fullmatch(r"[a-zA-Z0-9_.-]{1,80}", query.get(name, ""))
                           for name in ("customer", "environment"))
                    or not re.fullmatch(r"[1-9][0-9]{0,2}", query.get("limit", "50"))
                    or int(query.get("limit", "50")) > 100):
                return None
            return "GET", "reporter", path.removeprefix("/reporter")
        except ValueError:
            return None
    if re.fullmatch(r"/reporter/reports/[0-9a-f]{64}/[0-9]{10}/[0-9a-f]{64}/report\.(pdf|html)", path):
        return "GET", "reporter", path.removeprefix("/reporter")
    return ROUTES.get(path)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_config(config):
    if set(config) != {"node", "reporter"}:
        raise ValueError("invalid_bridge_config")
    for endpoint in config.values():
        parsed = urllib.parse.urlsplit(endpoint)
        address = ipaddress.IPv4Address(parsed.hostname)
        if parsed.scheme != "http" or not address.is_private or address.is_unspecified or address.is_link_local:
            raise ValueError("private_http_target_required")
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path or not parsed.port:
            raise ValueError("invalid_bridge_endpoint")
    return config


def inside_wsl(config):
    incoming = json.loads(sys.stdin.buffer.read(100000))
    method, service, path = route_for(incoming["path"])
    headers = {"Content-Type": "application/json"}
    if incoming.get("authorization"):
        headers["Authorization"] = incoming["authorization"]
    body = base64.b64decode(incoming["body"]) if method == "POST" else None
    req = urllib.request.Request(config[service] + path, data=body, headers=headers, method=method)
    try:
        response = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({})).open(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        maximum = 8388608 if path.startswith("/reports/") else 1048576
        data = response.read(maximum + 1)
        if len(data) > maximum:
            raise ValueError("upstream_limit")
        print(json.dumps({"status": response.status, "type": response.headers.get("Content-Type", "text/plain"),
                          "disposition": response.headers.get("Content-Disposition"),
                          "body": base64.b64encode(data).decode()}))


class Bridge(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def forward(self):
        try:
            route = route_for(self.path)
            if not route or route[0] != self.command:
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if self.headers.get("Transfer-Encoding") or not 0 <= length <= 65536:
                self.send_error(413)
                return
            self.connection.settimeout(5)
            data = {"path": self.path, "authorization": self.headers.get("Authorization", ""),
                    "body": base64.b64encode(self.rfile.read(length)).decode()}
            result = subprocess.run(["wsl.exe", "-d", "Ubuntu", "--", "python3", self.server.script,
                                     "--inside-wsl", "--config", self.server.config], input=json.dumps(data),
                                    text=True, encoding="utf-8", capture_output=True, timeout=9)
            if result.returncode:
                raise ValueError("upstream_failed")
            response = json.loads(result.stdout)
            body = base64.b64decode(response["body"])
            self.send_response(response["status"])
            self.send_header("Content-Type", response["type"])
            self.send_header("Content-Length", str(len(body)))
            if self.path.startswith("/reporter/console/"):
                self.send_header("Cache-Control", "private, no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
            if self.path.startswith("/reporter/reports/"):
                self.send_header("Cache-Control", "private, no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Referrer-Policy", "no-referrer")
                self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:; frame-ancestors 'none'")
                if response.get("disposition"):
                    self.send_header("Content-Disposition", response["disposition"])
            self.end_headers()
            self.wfile.write(body)
        except (OSError, ValueError, KeyError, subprocess.TimeoutExpired):
            self.send_error(502, "WSL observability unavailable")

    do_GET = forward
    do_POST = forward


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inside-wsl", action="store_true")
    parser.add_argument("--config", required=True)
    parser.add_argument("--wsl-script")
    parser.add_argument("--wsl-config")
    parser.add_argument("--port", type=int, default=59188)
    args = parser.parse_args()
    config = validate_config(json.loads(Path(args.config).read_text(encoding="utf-8")))
    if args.inside_wsl:
        inside_wsl(config)
    else:
        if not args.wsl_script or not args.wsl_config:
            raise ValueError("wsl_paths_required")
        server = ThreadingHTTPServer(("127.0.0.1", args.port), Bridge)
        server.script, server.config = args.wsl_script, args.wsl_config
        server.serve_forever()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError):
        raise SystemExit("local_observability_bridge_failed") from None
