"""Generate report files and grant time-limited access to exactly one stored artifact."""
import hashlib
import hmac
import os
from pathlib import Path
import re
import tempfile
import threading
import time
from urllib.parse import urlsplit

LIMIT = 8 * 1024 * 1024
ROUTE = re.compile(r"/reports/([0-9a-f]{64})/([0-9]{10})/([0-9a-f]{64})/report\.(pdf|html)")
RENDER_LOCK = threading.Lock()


def options(config):
    value = config.get("artifacts", {})
    if not isinstance(value, dict):
        raise ValueError("invalid_artifact_options")
    if not value.get("enabled", False):
        return None
    if value.get("enabled") is not True:
        raise ValueError("invalid_artifact_enabled")
    base = value.get("public_base_url", "")
    parsed = urlsplit(base)
    if (parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"})
            or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment
            or any(char.isspace() for char in base) or not re.fullmatch(r"[/a-zA-Z0-9._-]*", parsed.path)):
        raise ValueError("artifact_base_requires_https_or_localhost")
    if type(value.get("ttl_seconds", 604800)) is not int or not 300 <= value.get("ttl_seconds", 604800) <= 604800:
        raise ValueError("invalid_artifact_ttl")
    if not Path(value.get("directory", "")).is_absolute() or not value.get("font"):
        raise ValueError("artifact_directory_and_font_required")
    return value


def key():
    path = os.environ.get("REPORT_ARTIFACT_KEY_FILE")
    if not path:
        raise ValueError("artifact_key_file_required")
    value = Path(path).read_bytes().strip()
    if len(value) < 32 or len(value) > 256:
        raise ValueError("invalid_artifact_key")
    return value


def signature(identifier, expires, extension, secret):
    return hmac.new(secret, f"report-v1:{identifier}:{expires}:{extension}".encode(), hashlib.sha256).hexdigest()


def render_files(report, settings):
    from presentation import render_html
    from render_pdf import render_pdf
    root = Path(settings["directory"])
    identifier = hashlib.sha256(report["id"].encode()).hexdigest()
    folder = root / identifier
    if folder.is_symlink() or root.is_symlink():
        raise ValueError("artifact_symlink_rejected")
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    # ReportLab shares font registries; serialize PDF rendering across delivery workers.
    with RENDER_LOCK:
        with tempfile.TemporaryDirectory(prefix=".render-", dir=root) as temporary:
            working = Path(temporary)
            html = working / "report.html"
            html.write_text(render_html(report), encoding="utf-8")
            pdf = working / "report.pdf"
            render_pdf(report, pdf, settings["font"], settings.get("font_bold"))
            for file in (pdf, html):
                if not 1 <= file.stat().st_size <= LIMIT:
                    raise ValueError("artifact_size_limit")
                file.chmod(0o600)
                os.replace(file, folder / file.name)
    return identifier


def publish(report, config, now=None):
    settings = options(config)
    if settings is None:
        return []
    secret = key()
    identifier = render_files(report, settings)
    expires = int(time.time() if now is None else now) + settings.get("ttl_seconds", 604800)
    return [{"type": "Action.OpenUrl", "title": title,
             "url": settings["public_base_url"].rstrip("/") + f"/reports/{identifier}/{expires}/"
                    + signature(identifier, expires, extension, secret) + "/report." + extension}
            for extension, title in (("pdf", "PDF 보고서"), ("html", "HTML 보고서"))]


def read_file(path, config, now=None):
    settings = options(config)
    matched = ROUTE.fullmatch(path)
    if settings is None or matched is None:
        raise FileNotFoundError("report_not_found")
    identifier, expires, received, extension = matched.groups()
    clock = int(time.time() if now is None else now)
    if not clock < int(expires) <= clock + 604800 or not hmac.compare_digest(
            received, signature(identifier, expires, extension, key())):
        raise PermissionError("report_link_expired_or_invalid")
    root = Path(settings["directory"]).resolve()
    target = root / identifier / ("report." + extension)
    if target.is_symlink() or target.parent.is_symlink() or not target.resolve().is_relative_to(root):
        raise PermissionError("report_path_rejected")
    if not 1 <= target.stat().st_size <= LIMIT:
        raise ValueError("artifact_size_limit")
    return target.read_bytes(), ("application/pdf" if extension == "pdf" else "text/html; charset=utf-8"), extension
