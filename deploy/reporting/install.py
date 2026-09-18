"""Install the local reporting units without evaluating secret files as code."""
import argparse
import grp
import ipaddress
import json
import os
from pathlib import Path
import pwd
import secrets
import subprocess
import tempfile
import urllib.parse
from secret_inputs import read_secret
from scope import scoped_config

HERE = Path(__file__).resolve().parent


def run(*args):
    subprocess.run(args, check=True)


def write(path, value, group, mode=0o640):
    target = Path(path)
    if target.is_symlink():
        raise ValueError("Installation target must not be a symlink")
    descriptor, temporary = tempfile.mkstemp(dir=target.parent, prefix=".report-install-")
    try:
        os.fchmod(descriptor, mode)
        os.fchown(descriptor, 0, group)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(value)
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--enable-delivery", action="store_true", help="Enable approved Teams delivery explicitly")
    parser.add_argument("--secret-file", required=True)
    parser.add_argument("--analyzer", choices=("mock", "facts-only", "gemini"), default="mock")
    parser.add_argument("--ai-secret-file")
    parser.add_argument("--ai-model", default="gemini-3.1-flash-lite")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--prometheus-url", default="http://127.0.0.1:59090")
    parser.add_argument("--alertmanager-url", default="http://127.0.0.1:59093")
    parser.add_argument("--customer", default="exchange")
    parser.add_argument("--environment", choices=("local-lab", "aws"), default="local-lab")
    parser.add_argument("--artifact-base-url", help="Recipient-accessible HTTPS origin/path; localhost HTTP allowed for local testing")
    parser.add_argument("--pdf-font", default="/usr/share/fonts/truetype/nanum/NanumGothic.ttf")
    parser.add_argument("--pdf-font-bold", default="/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf")
    args = parser.parse_args()
    config = scoped_config(json.loads((HERE / "config.example.json").read_text(encoding="utf-8")),
                           args.customer, args.environment)
    config.update(prometheus_url=args.prometheus_url, alertmanager_url=args.alertmanager_url,
                  analyzer=args.analyzer, ai_model=args.ai_model)
    if args.artifact_base_url:
        from artifacts import options
        config["artifacts"] = {"enabled": True, "public_base_url": args.artifact_base_url,
                               "directory": "/var/lib/failstop-reporting/artifacts", "ttl_seconds": 604800,
                               "font": args.pdf_font, "font_bold": args.pdf_font_bold}
        options(config)
    address = ipaddress.IPv4Address(args.bind)
    if not address.is_private or address.is_unspecified:
        raise ValueError("Bind must be an explicit loopback or private IPv4 address")
    for value in (args.prometheus_url, args.alertmanager_url):
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("Invalid internal monitoring URL")
    if not args.apply:
        print("Plan: dedicated service account; protected secret/config; persistent outbox; daily 09:00 Asia/Seoul")
        return
    if os.geteuid() != 0:
        raise ValueError("Run installation as root")
    if args.artifact_base_url:
        import reportlab
        if not Path(args.pdf_font).is_file() or not Path(args.pdf_font_bold).is_file():
            raise ValueError("PDF fonts missing")
    os.umask(0o077)
    # Parse the same raw-URL or dotenv format as runtime. Never source this file.
    teams_url = read_secret(args.secret_file, "teams")
    ai_key = read_secret(args.ai_secret_file or args.secret_file, "gemini") if args.analyzer == "gemini" else None
    from transport import restricted_https
    host = urllib.parse.urlsplit(teams_url).hostname
    restricted_https(teams_url, host or "", teams=True)
    if args.analyzer == "gemini":
        from gemini import MODELS
        if args.ai_model not in MODELS:
            raise ValueError("Unsupported model")
    try:
        account = pwd.getpwnam("failstop-report")
    except KeyError:
        run("useradd", "--system", "--user-group", "--no-create-home", "--shell", "/usr/sbin/nologin", "failstop-report")
        account = pwd.getpwnam("failstop-report")
    if account.pw_uid == 0 or account.pw_shell not in ("/usr/sbin/nologin", "/sbin/nologin"):
        raise ValueError("Existing report account is unsafe")
    if set(os.getgrouplist(account.pw_name, account.pw_gid)) != {account.pw_gid}:
        raise ValueError("Report account must not have additional groups")
    group = grp.getgrnam("failstop-report").gr_gid
    if account.pw_gid != group:
        raise ValueError("Report account must have its dedicated primary group")
    # /etc/failstop also holds public SSH keys; its parent must remain traversable.
    # Individual credentials retain their dedicated 0640/0600 permissions.
    for folder, mode, directory_group in (("/etc/failstop", 0o755, 0),
                         ("/opt/failstop", 0o755, 0), ("/opt/failstop/deploy", 0o755, 0),
                         ("/var/lib/failstop", 0o755, 0),
                         ("/opt/failstop/deploy/reporting", 0o755, 0),
                         ("/var/lib/failstop/evidence", 0o750, group)):
        target = Path(folder)
        if target.is_symlink():
            raise ValueError("Installation directory must not be a symlink")
        target.mkdir(parents=True, exist_ok=True)
        os.chown(target, 0, directory_group)
        target.chmod(mode)
    for name in ("reporter.py", "evidence.py", "transport.py", "secret_inputs.py", "gemini.py", "presentation.py",
                 "intervention.py", "charts.py", "render_pdf.py", "scope.py", "artifacts.py"):
        write(f"/opt/failstop/deploy/reporting/{name}", (HERE / name).read_text(encoding="utf-8"), 0, 0o644)
    write("/etc/failstop/teams-webhook", teams_url + "\n", group)
    if ai_key:
        write("/etc/failstop/gemini-key", ai_key + "\n", group)
    token_path = Path("/etc/failstop/reporter-token")
    token = token_path.read_text().strip() if token_path.is_file() and not token_path.is_symlink() else secrets.token_hex(32)
    if len(token) < 32 or not token.isalnum():
        raise ValueError("Invalid existing ingest token")
    write(token_path, token + "\n", group)
    artifact_key_path = Path("/etc/failstop/report-artifact-key")
    if args.artifact_base_url:
        artifact_key = artifact_key_path.read_text().strip() if artifact_key_path.is_file() and not artifact_key_path.is_symlink() else secrets.token_hex(32)
        write(artifact_key_path, artifact_key + "\n", group)
    enabled = str(args.enable_delivery).lower()
    write("/etc/failstop/reporting.env", f"REPORT_BIND={args.bind}\nREPORT_DELIVERY_ENABLED={enabled}\nREPORT_INGEST_TOKEN={token}\n"
          f"REPORT_TEAMS_SECRET_FILE=/etc/failstop/teams-webhook\nREPORT_TEAMS_ALLOWED_HOSTS={host}\n"
          + ("REPORT_AI_KEY_FILE=/etc/failstop/gemini-key\n" if ai_key else "")
          + ("REPORT_ARTIFACT_KEY_FILE=/etc/failstop/report-artifact-key\n" if args.artifact_base_url else ""), group)
    write("/etc/failstop/reporting.json", json.dumps(config, ensure_ascii=False, indent=2) + "\n", group)
    evidence = Path("/var/lib/failstop/evidence/host-latest.json")
    if evidence.is_file() and not evidence.is_symlink():
        os.chown(evidence, 0, group)
        evidence.chmod(0o640)
    for unit in (HERE / "systemd").glob("failstop-reporting*"):
        write(f"/etc/systemd/system/{unit.name}", unit.read_text(), 0, 0o644)
    collector_unit = Path("/etc/systemd/system/failstop-host-evidence.service")
    if collector_unit.is_file():
        collector_source = HERE.parent / "observability/collect-host.py"
        write("/usr/local/lib/failstop/collect-host.py", collector_source.read_text(encoding="utf-8"), 0, 0o755)
        dropin = Path("/etc/systemd/system/failstop-host-evidence.service.d")
        if dropin.is_symlink():
            raise ValueError("Collector drop-in directory must not be a symlink")
        dropin.mkdir(exist_ok=True, mode=0o755)
        write(dropin / "reporting.conf", "[Service]\nExecStart=\n"
              "ExecStart=/usr/bin/python3 /usr/local/lib/failstop/collect-host.py "
              "--output /var/lib/failstop/metrics --evidence /var/lib/failstop/evidence --group failstop-report\n", 0, 0o644)
    run("systemctl", "daemon-reload")
    if collector_unit.is_file():
        run("systemctl", "start", "failstop-host-evidence.service")
    run("systemctl", "enable", "--now", "failstop-reporting.service", "failstop-reporting-daily.timer")
    run("systemctl", "restart", "failstop-reporting.service")
    run("systemctl", "is-active", "failstop-reporting.service", "failstop-reporting-daily.timer")
    print("Installed. Configure monitor-only firewall access before using a private bind; verify webhook acceptance separately.")


if __name__ == "__main__":
    try:
        main()
    except (ImportError, ValueError, OSError, subprocess.CalledProcessError):
        raise SystemExit("Reporting installation failed; secret values were not logged") from None
