#!/usr/bin/env bash
# 검증된 release와 root-only SSM 환경 파일을 기존 Linux/Compose 설치기에 연결한다.
set -euo pipefail
set +x
umask 077
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin AWS_PAGER=""
[[ $# == 1 && $(id -u) == 0 ]] || { echo 'root and one bootstrap configuration path required' >&2; exit 1; }
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
deployment=$(dirname "$here")
configuration=$(realpath "$1")
for program in aws python3 curl docker nft sshd rsync iostat; do command -v "$program" >/dev/null; done
docker compose version >/dev/null
systemctl is-active --quiet docker.service
[[ $(cat /proc/1/comm) == systemd ]]
temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
python3 - "$configuration" "$temporary" "$deployment" <<'PY'
import importlib.util
import ipaddress
import json
import pathlib
import subprocess
import sys
import urllib.request

config = json.loads(pathlib.Path(sys.argv[1]).read_text())
output = pathlib.Path(sys.argv[2])
role = config["role"]
if role not in {"app", "reader", "data", "standby", "bot"}:
    raise SystemExit("Unknown runtime role")
token_request = urllib.request.Request("http://169.254.169.254/latest/api/token", method="PUT",
                                      headers={"X-aws-ec2-metadata-token-ttl-seconds": "60"})
with urllib.request.urlopen(token_request, timeout=5) as response:
    token = response.read().decode()
ip_request = urllib.request.Request("http://169.254.169.254/latest/meta-data/local-ipv4",
                                   headers={"X-aws-ec2-metadata-token": token})
with urllib.request.urlopen(ip_request, timeout=5) as response:
    address = str(ipaddress.IPv4Address(response.read().decode()))
result = subprocess.run(["aws", "ssm", "get-parameter", "--region", config["region"], "--name", config["parameter_name"],
                         "--with-decryption", "--query", "Parameter.Value", "--output", "text"],
                        check=True, capture_output=True, text=True)
environment = output / "role.env"
environment.write_text(result.stdout)
spec = importlib.util.spec_from_file_location("check_config", pathlib.Path(sys.argv[3]) / "check-config.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
values = checker.read_env(environment)
values.update(config["runtime_overrides"])
if role in {"app", "reader"}:
    proxy_spec = importlib.util.spec_from_file_location("trusted_proxies", pathlib.Path(sys.argv[3]) / "terraform/trusted-proxies.py")
    proxies = importlib.util.module_from_spec(proxy_spec)
    proxy_spec.loader.exec_module(proxies)
    values["EXCHANGE_TRUSTED_PROXIES"] = proxies.compile_ranges(config["alb_subnets"], proxies.fetch_ranges())
values.update({"APP_BIND_IP": address, "MGMT_BIND_IP": address, "BOT_BIND_IP": address, "DB_BIND_IP": address})
# API 8080·관리 8081, PostgreSQL 5432는 SG·ALB·file discovery와 같은 계약이다.
values.update({"APP_PORT": "8080", "MGMT_PORT": "8081", "BOT_MGMT_PORT": "8080"})
if any("'" in value or "\n" in value or "\r" in value for value in values.values()):
    raise SystemExit("Role values must be single-line dotenv values without single quotes")
environment.write_text("".join(f"{key}='{value}'\n" for key, value in sorted(values.items())))
checker.validate(values, "read" if role == "reader" else role)
(output / "operator.pub").write_text(config["operator_public_key"] + "\n")
(output / "role").write_text(role)
(output / "ip").write_text(address)
(output / "monitor").write_text(config["monitor_ip"] + "/32")
(output / "vpc").write_text(config["vpc_cidr"])
(output / "health").write_text("\n".join(config["alb_subnets"]) + "\n")
(output / "start").write_text("yes" if config["start_service"] else "no")
PY
role=$(cat "$temporary/role")
address=$(cat "$temporary/ip")
bash "$deployment/linux/bootstrap-host.sh" --apply --public-key "$temporary/operator.pub" --admin-cidr 127.0.0.1/32
# ALB health 요청은 public subnet에서 관리 포트로 들어온다.
health_arguments=()
while IFS= read -r subnet; do health_arguments+=(--health-cidr "$subnet"); done < "$temporary/health"
bash "$deployment/linux/firewall-host.sh" --apply --role "$role" --admin-cidr 127.0.0.1/32 \
    --monitor-cidr "$(cat "$temporary/monitor")" --client-cidr "$(cat "$temporary/vpc")" --replica-cidr "$(cat "$temporary/vpc")" "${health_arguments[@]}"
bash "$deployment/observability/install-node-exporter.sh" --apply --listen "$address:9100"
arguments=(--apply --role "$role" --env-file "$temporary/role.env")
[[ $(cat "$temporary/start") != yes ]] || arguments+=(--start)
bash "$deployment/linux/install-service.sh" "${arguments[@]}"
if [[ $role == bot ]]; then
    install -d -o root -g root -m 0755 /usr/local/lib/failstop /opt/failstop/deploy/observability/targets /opt/failstop/deploy/observability/rules
    install -m 0755 "$here/discover-targets.py" /usr/local/lib/failstop/discover-targets.py
    install -m 0644 "$here/discovery-alerts.yml" /opt/failstop/deploy/observability/rules/aws-discovery.yml
    python3 - "$configuration" <<'PY'
import json
import pathlib
import sys
config = json.loads(pathlib.Path(sys.argv[1]).read_text())
safe = {key: config[key] for key in ("region", "cluster", "api_origin")}
pathlib.Path("/etc/failstop/discovery.json").write_text(json.dumps(safe))
PY
    install -m 0644 "$here/systemd/"* /etc/systemd/system/
    systemctl daemon-reload
    systemctl enable --now failstop-discovery.timer
fi
printf 'Configured role=%s. Fixed app/bot startup follows manual database migration; inspect cloud-init and systemd results.\n' "$role"
