import json
import http.client
import os
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from secret_inputs import read_secret

SECRET_ASSIGNMENT = re.compile(
    r"""(?ix)(?<![\w])["']?(?:password|passwd|secret|token|api[_-]?key|access[_-]?key|signature|credential)
    ["']?\s*[=:]\s*(?:"(?:\\[\s\S]|[^"\\])*(?:"|(?:\\)?$)|'(?:\\[\s\S]|[^'\\])*(?:'|(?:\\)?$)|[^\s,;]+)"""
)


class RequestFailure(Exception):
    def __init__(self, code, status=0, retry_after=None):
        super().__init__(code)
        self.code, self.status, self.retry_after = code, status, retry_after


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(url, payload=None, headers=None, timeout=5, limit=262144):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or parsed.password:
        raise RequestFailure("invalid_endpoint")
    body = None if payload is None else json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()
    if body is not None and len(body) > limit:
        raise RequestFailure("request_too_large")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json", **(headers or {})})
    deadline = time.monotonic() + timeout
    try:
        with urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({})).open(req, timeout=timeout) as res:
            chunks, size = [], 0
            while True:
                if time.monotonic() >= deadline:
                    raise RequestFailure("timeout")
                chunk = res.read1(min(8192, limit + 1 - size))
                if not chunk:
                    return b"".join(chunks)
                chunks.append(chunk)
                size += len(chunk)
                if size > limit:
                    raise RequestFailure("response_too_large")
    except urllib.error.HTTPError as error:
        failure = RequestFailure("http_error", error.code, error.headers.get("Retry-After"))
        error.close()
        raise failure from None
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError, http.client.HTTPException):
        raise RequestFailure("network_error") from None


def json_request(url, **kwargs):
    try:
        return json.loads(request(url, **kwargs))
    except (ValueError, UnicodeError):
        raise RequestFailure("invalid_json") from None


def restricted_https(url, allowed_hosts, teams=False):
    parsed = urllib.parse.urlsplit(url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or parsed.port not in (None, 443) or parsed.username or parsed.password or parsed.fragment:
        raise ValueError("endpoint_requires_https")
    if host not in {value.strip().lower() for value in allowed_hosts.split(",") if value.strip()}:
        raise ValueError("endpoint_host_not_allowed")
    if teams and not (host.endswith(".logic.azure.com") or host.endswith(".api.powerplatform.com")):
        raise ValueError("endpoint_is_not_teams_workflows")
    return url


def teams_secret(secret_file=None):
    value = os.environ.get("TEAMS_WEBHOOK_URL", "").strip()
    if not value and secret_file:
        value = read_secret(secret_file, "teams")
    if not value:
        raise ValueError("teams_secret_missing")
    return restricted_https(value, os.environ.get("REPORT_TEAMS_ALLOWED_HOSTS", ""), teams=True)


def redact(value):
    if isinstance(value, dict):
        return {str(key)[:80]: redact(item) for key, item in value.items()
                if not re.search(r"secret|password|token|authorization|cookie|email|user_?id|access.?key|signature|credential", str(key), re.I)}
    if isinstance(value, list):
        return [redact(item) for item in value[:100]]
    if not isinstance(value, str):
        return value
    text = value[:2000]
    text = re.sub(r"https?://\S+", "[URL REDACTED]", text, flags=re.I)
    text = re.sub(r"(?i)\b(?:bearer|basic)\s+\S+", "[AUTH REDACTED]", text)
    # Quoted JSON/log values may contain spaces and escaped quotes. An unfinished
    # quoted value at the size boundary stays private through the end of the text.
    text = SECRET_ASSIGNMENT.sub("[SECRET REDACTED]", text)
    text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[EMAIL REDACTED]", text)
    text = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "[IP REDACTED]", text)
    text = re.sub(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b", "[KEY REDACTED]", text)
    text = re.sub(r"\b(?:AIza[A-Za-z0-9_-]{35}|AQ\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)", "[KEY REDACTED]", text)
    text = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b", "[JWT REDACTED]", text)
    text = re.sub(r"[\x00-\x08\x0b-\x1f]", "", text)
    return text
