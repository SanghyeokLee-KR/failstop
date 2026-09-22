#!/usr/bin/env bash
# OPS01 일일·장애 운영 보고 구축 스크립트
#
# 무엇을 하는가
#   OPS01(192.168.10.41 / 192.168.56.41)에 Alertmanager와 failstop-reporting을 설치하고
#   Prometheus에 경보 규칙을 붙인다. 설치가 끝나면 경보는 Teams로 나가고, 같은 경보가
#   보고 서비스로도 들어와 지표와 증거를 모은 보고가 대기열에 쌓인다.
#
# 왜 두 갈래인가
#   Alertmanager의 teams 수신자가 기본 장애 알림을 바로 보내고, evidence-reporter 수신자가
#   같은 경보를 127.0.0.1:9088로 한 번 더 넘긴다. 두 경로는 서로 의존하지 않으므로
#   보고 서비스나 AI가 멈춰도 기본 장애 알림은 계속 전달된다. 이 분리를 한 수신자로 합치지 않는다.
#
# 무엇을 하지 않는가
#   - 외부 전송을 켜지 않는다. 설치 직후 REPORT_DELIVERY_ENABLED=false이며 수집과 대기열 적재만 한다.
#     사람이 수신 대상과 보고 내용을 확인한 뒤 손으로 켠다. 절차는 문서/OPS01_보고_구축절차.md에 있다.
#   - 비밀 값을 만들지 않고 출력하지 않는다. Teams 웹훅과 AI 키 파일은 실행 전에 운영자가 만든다.
#   - Prometheus, Grafana, node_exporter를 설치하지 않는다. 관측 역할이 먼저 끝나 있어야 한다.
#   - nftables 규칙을 바꾸지 않는다. 방화벽 계통은 02-정본결정.md 4절대로 table inet vmlab 하나이며
#     공통 절차 00-common-setup.sh가 넣고 필요하면 40-ops01-observability.sh가 같은 자리에 넣는다.
#     9088은 기본 정책 drop과 127.0.0.1 바인딩으로 막히므로 포트별 drop 규칙을 두지 않는다.
#     여기서는 그 두 가지가 실제로 그런지 확인만 하고 어긋나면 경고한다.
#   - AI가 운영 조치를 실행하는 경로를 만들지 않는다. AI는 요약문만 만든다.
#
# 이 스크립트는 실행 절차이지 검증 결과가 아니다. 실행해야 확인되는 값을 미리 통과로 적지 않는다.
#
# 실행 예
#   sudo ./41-ops01-reporting.sh --repo /home/ops/failstop
#
# 되돌리기
#   systemctl disable --now alertmanager failstop-reporting failstop-reporting-daily.timer
#   prometheus.yml은 같은 폴더의 prometheus.yml.before-reporting-* 백업으로 되돌린다.

set -euo pipefail

umask 0077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 산출물 기본 경로. 주소·포트는 ${BUILD_DIR}/00-주소계획.md가 유일한 기준이다.
OPS_FILES="${BUILD_DIR}/build-files/ops"
OBS_FILES="${SCRIPT_DIR}/ops01"

REPO=""
TEAMS_SECRET="/etc/failstop/teams-webhook"
AI_SECRET="/etc/failstop/gemini-key"
ANALYZER="gemini"
AI_MODEL="gemini-3.1-flash-lite"
AM_VERSION="0.31.1"
AM_ARCHIVE=""
AM_SUMS=""
ALERTMANAGER_CONFIG=""
RULES_FILE=""
REPORT_CONFIG=""
SKIP_PROMETHEUS=0

BIND_ADDRESS="127.0.0.1"
PROMETHEUS_URL="http://127.0.0.1:9090"
ALERTMANAGER_URL="http://127.0.0.1:9093"
CUSTOMER="exchange"
# install.py의 --environment 선택지는 local-lab과 aws 둘뿐이다. 실제 VM 라벨 vm-lab은
# 아래 reporting.config.vm.json이 담고 있으며 설치 직후 그 파일로 덮어쓴다.
INSTALL_ENVIRONMENT="local-lab"
TARGET_ENVIRONMENT="vm-lab"

TMP_DIR=""

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
step() { printf '\n===== %s =====\n' "$*"; }
warn() { printf '[경고] %s\n' "$*" >&2; }
die() { printf '[중단] %s\n' "$*" >&2; exit 1; }

cleanup() {
  if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup EXIT

usage() {
  cat <<'USAGE'
사용법: sudo ./41-ops01-reporting.sh --repo <failstop 저장소 경로> [옵션]

  --repo DIR                 OPS01에 내려받은 failstop 저장소 경로 (필수)
  --ops-files DIR            reporting.config.vm.json과 alerts.vm.yml이 있는 폴더
                             (기본값 <build>/build-files/ops)
  --obs-files DIR            alertmanager.vm.yml이 있는 관측 역할 산출물 폴더
                             (기본값 <build>/scripts/ops01)
  --alertmanager-config FILE Alertmanager 설정 파일을 직접 지정
  --rules-file FILE          경보 규칙 파일을 직접 지정
  --report-config FILE       보고 설정 JSON을 직접 지정
  --teams-secret-file FILE   Teams Workflows 웹훅 파일 (기본값 /etc/failstop/teams-webhook)
  --ai-secret-file FILE      AI API 키 파일 (기본값 /etc/failstop/gemini-key)
  --analyzer NAME            gemini | facts-only | mock (기본값 gemini)
  --ai-model NAME            gemini-3.1-flash-lite 등 (기본값 gemini-3.1-flash-lite)
  --alertmanager-version V   설치할 Alertmanager 버전 (기본값 0.31.1)
  --alertmanager-archive F   내려받지 않고 쓸 tar.gz 경로. --alertmanager-sums와 같이 쓴다
  --alertmanager-sums F      위 아카이브에 대응하는 sha256sums.txt 경로
  --skip-prometheus          prometheus.yml 편집과 규칙 설치를 건너뛴다
  -h, --help                 이 도움말

외부 전송은 이 스크립트에서 켜지 않는다. 설치 후 사람이 확인하고 직접 켠다.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="${2:?--repo 값이 필요하다}"; shift 2 ;;
    --ops-files) OPS_FILES="${2:?}"; shift 2 ;;
    --obs-files) OBS_FILES="${2:?}"; shift 2 ;;
    --alertmanager-config) ALERTMANAGER_CONFIG="${2:?}"; shift 2 ;;
    --rules-file) RULES_FILE="${2:?}"; shift 2 ;;
    --report-config) REPORT_CONFIG="${2:?}"; shift 2 ;;
    --teams-secret-file) TEAMS_SECRET="${2:?}"; shift 2 ;;
    --ai-secret-file) AI_SECRET="${2:?}"; shift 2 ;;
    --analyzer) ANALYZER="${2:?}"; shift 2 ;;
    --ai-model) AI_MODEL="${2:?}"; shift 2 ;;
    --alertmanager-version) AM_VERSION="${2:?}"; shift 2 ;;
    --alertmanager-archive) AM_ARCHIVE="${2:?}"; shift 2 ;;
    --alertmanager-sums) AM_SUMS="${2:?}"; shift 2 ;;
    --skip-prometheus) SKIP_PROMETHEUS=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "알 수 없는 인수: $1" ;;
  esac
done

# 관측 역할의 산출물은 build-files/ops가 정본이고 scripts/ops01은 먼저 만든 사본이다.
# 40-ops01-observability.sh와 같은 순서로 찾는다.
find_source() {
  local name="$1" dir
  for dir in "${OPS_FILES}" "${OBS_FILES}"; do
    if [[ -f "${dir}/${name}" ]]; then
      printf '%s\n' "${dir}/${name}"
      return 0
    fi
  done
  return 1
}

[[ -n "${ALERTMANAGER_CONFIG}" ]] || ALERTMANAGER_CONFIG="$(find_source alertmanager.vm.yml || true)"
[[ -n "${RULES_FILE}" ]] || RULES_FILE="${OPS_FILES}/alerts.vm.yml"
[[ -n "${REPORT_CONFIG}" ]] || REPORT_CONFIG="${OPS_FILES}/reporting.config.vm.json"

REPORTING_SRC="${REPO}/deploy/reporting"
LINUX_REPORTING_SRC="${REPO}/deploy/linux/reporting"

# ---------------------------------------------------------------------------
# 1단계. 선행 조건 확인
# 여기서 막히는 항목은 뒤 단계에서 조용히 잘못된 상태로 끝나는 것보다 낫다.
# ---------------------------------------------------------------------------
stage_preflight() {
  step "1단계. 선행 조건 확인"

  [[ ${EUID} -eq 0 ]] || die "root로 실행한다. sudo ./41-ops01-reporting.sh ..."
  [[ -n "${REPO}" ]] || { usage >&2; die "--repo로 failstop 저장소 경로를 지정한다"; }

  local missing=()
  local command_name
  for command_name in curl tar sha256sum systemctl ss python3 install awk grep sed getent runuser; do
    command -v "${command_name}" >/dev/null 2>&1 || missing+=("${command_name}")
  done
  (( ${#missing[@]} == 0 )) || die "필수 명령이 없다: ${missing[*]}"

  # 보고 본체는 Python 3.10 이상을 요구한다. 낮으면 유닛의 /usr/bin/python3를 별도 런타임으로
  # 바꿔야 하므로 여기서 멈춘다. 학교 AL2023에서는 이 이유로 전용 가상환경을 따로 만들었다.
  local python_version
  python_version="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
  python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' \
    || die "python3 ${python_version}은 3.10 미만이다. 별도 런타임을 먼저 준비한다"
  log "python3 ${python_version} 확인"

  local path
  for path in "${REPORTING_SRC}/install.py" "${REPORTING_SRC}/config.example.json" \
              "${REPORTING_SRC}/run-installed.py" "${REPORTING_SRC}/systemd/failstop-reporting.service" \
              "${LINUX_REPORTING_SRC}/alertmanager.service"; do
    [[ -f "${path}" ]] || die "저장소 파일이 없다: ${path} (--repo 경로를 확인한다)"
  done
  [[ -n "${ALERTMANAGER_CONFIG}" ]] \
    || die "alertmanager.vm.yml을 찾지 못했다. ${OPS_FILES} 또는 ${OBS_FILES}에 두거나 --alertmanager-config로 지정한다"
  for path in "${ALERTMANAGER_CONFIG}" "${RULES_FILE}" "${REPORT_CONFIG}"; do
    [[ -f "${path}" ]] || die "산출물 파일이 없다: ${path}"
  done

  # 비밀 파일은 이 스크립트가 만들지 않는다. 내용을 읽지도 출력하지도 않고 존재와 크기만 본다.
  check_secret_file "${TEAMS_SECRET}" "Teams Workflows 웹훅"
  if [[ "${ANALYZER}" == "gemini" ]]; then
    check_secret_file "${AI_SECRET}" "AI API 키"
  fi

  install -d -o root -g root -m 0755 /etc/failstop

  # 관측 역할이 만든 경보 규칙 사본이 남아 있으면 둘 중 어느 쪽이 설치됐는지 헷갈린다.
  if [[ -f "${OBS_FILES}/vm-alerts.yml" && "${RULES_FILE}" != "${OBS_FILES}/vm-alerts.yml" ]]; then
    warn "관측 역할의 ${OBS_FILES}/vm-alerts.yml이 남아 있다. 이번 설치는 ${RULES_FILE}을 쓴다. 규칙을 고칠 때는 두 파일을 같이 고치거나 사본을 지운다"
  fi
}

# 비밀 값 파일을 확인한다. 내용은 읽지 않는다.
# 00-주소계획.md 계정 절이 비밀 값 권한을 0600으로 정했으므로 소유자와 권한도 같이 본다.
# 판정 방식은 scripts/00-common-setup.sh의 require_protected_file과 같게 맞췄다.
check_secret_file() {
  local path="$1" label="$2" owner mode
  [[ -e "${path}" ]] || die "${label} 파일이 없다: ${path}. 문서/OPS01_보고_구축절차.md의 비밀 값 준비 절차를 먼저 수행한다"
  [[ ! -L "${path}" ]] || die "${label} 파일이 심볼릭 링크다: ${path}"
  [[ -f "${path}" ]] || die "${label} 경로가 일반 파일이 아니다: ${path}"
  [[ -s "${path}" ]] || die "${label} 파일이 비어 있다: ${path}"
  owner="$(stat -c %u "${path}")"
  [[ "${owner}" == 0 ]] || die "${label} 파일의 소유자가 root가 아니다: ${path} (uid ${owner}). sudo chown root:root ${path} 로 고친다"
  mode="$(stat -c %a "${path}")"
  # group과 other에 어떤 권한도 없어야 한다. 0177 비트가 하나라도 서면 실패로 본다.
  if (( (8#${mode} & 8#0177) != 0 )); then
    die "${label} 파일 권한이 넓다: ${path} (${mode}). sudo chmod 0600 ${path} 로 좁힌다"
  fi
  log "${label} 파일 확인: ${path} (소유자 root, 권한 ${mode}. 내용은 읽지 않는다)"
}

# ---------------------------------------------------------------------------
# 2단계. 패키지 설치
# 보고가 실제로 쓰는 것만 넣는다. PDF·HTML 제공을 끄므로 reportlab과 한글 글꼴은 설치하지 않는다.
# ---------------------------------------------------------------------------
stage_packages() {
  step "2단계. 패키지 설치"

  # sysstat: collect-host.py가 iostat으로 disk_io 증거를 모은다. 없으면 host 증거가 error로 남는다.
  # tar: Alertmanager 릴리스에서 실행 파일 두 개만 꺼낸다.
  # chrony: 보고 기준 시각과 09:00 Asia/Seoul 실행의 근거다. 시계가 어긋나면 감지 시각도 틀어진다.
  dnf -y install sysstat tar chrony curl python3

  systemctl enable --now chronyd
  log "chronyd 기동 상태: $(systemctl is-active chronyd)"

  # SELinux는 끄지 않는다. 필요하면 문맥·포트를 다루는 도구만 더 넣는다.
  if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce)" != "Disabled" ]]; then
    if ! command -v semanage >/dev/null 2>&1; then
      log "semanage가 없어 policycoreutils-python-utils를 설치한다"
      dnf -y install policycoreutils-python-utils
    fi
    log "SELinux 상태: $(getenforce)"
  else
    warn "SELinux가 비활성이다. 운영 기준은 enforcing이며 이 상태를 정상으로 기록하지 않는다"
  fi

  # 시간대는 바꾸지 않고 확인만 한다. 다른 역할의 로그 시각 기준까지 함께 움직이기 때문이다.
  if command -v timedatectl >/dev/null 2>&1; then
    local zone
    zone="$(timedatectl show -p Timezone --value 2>/dev/null || echo unknown)"
    if [[ "${zone}" != "Asia/Seoul" ]]; then
      warn "시스템 시간대가 ${zone}이다. 일일 보고는 OnCalendar의 Asia/Seoul 지정으로 09:00 KST에 돌지만 로그 시각 판독을 맞추려면 timedatectl set-timezone Asia/Seoul을 별도로 검토한다"
    else
      log "시간대 Asia/Seoul 확인"
    fi
  fi
}

# ---------------------------------------------------------------------------
# 3단계. 관측 선행 조건 확인
# 보고의 모든 수치는 Prometheus 라벨에서 온다. 라벨이 어긋나면 보고서가 통째로 미수집이 된다.
# ---------------------------------------------------------------------------
stage_upstream() {
  step "3단계. 관측 선행 조건 확인"

  curl -fsS --max-time 5 "${PROMETHEUS_URL}/-/ready" >/dev/null \
    || die "Prometheus가 ${PROMETHEUS_URL}에서 응답하지 않는다. 관측 역할의 설치를 먼저 끝낸다"
  log "Prometheus 응답 확인"

  # up 시계열의 라벨 규약을 실제로 대조한다. 여기서 틀리면 보고서의 사실이 전부 비어 나온다.
  PROM_URL="${PROMETHEUS_URL}" WANT_CUSTOMER="${CUSTOMER}" WANT_ENVIRONMENT="${TARGET_ENVIRONMENT}" \
  python3 - <<'PY' || die "Prometheus 라벨 규약이 보고 설정과 맞지 않는다. 관측 역할과 라벨을 맞춘 뒤 다시 실행한다"
import json
import os
import sys
import urllib.parse
import urllib.request

base = os.environ["PROM_URL"]
customer = os.environ["WANT_CUSTOMER"]
environment = os.environ["WANT_ENVIRONMENT"]
url = base + "/api/v1/query?" + urllib.parse.urlencode({"query": "up"})
with urllib.request.urlopen(url, timeout=10) as response:
    data = json.load(response)

results = data.get("data", {}).get("result", [])
seen = {}
for item in results:
    metric = item.get("metric", {})
    node = metric.get("node")
    if not node:
        continue
    seen.setdefault(node, set()).add((metric.get("customer"), metric.get("environment"), metric.get("role")))

expected = {"edge01", "app01", "app02", "db01", "db02", "ops01"}
missing = sorted(expected - set(seen))
wrong = sorted(node for node, labels in seen.items()
               if any(item[0] != customer or item[1] != environment for item in labels))

for node in sorted(seen):
    roles = sorted({item[2] or "-" for item in seen[node]})
    print("  node=%-8s role=%s" % (node, ",".join(roles)))

if missing:
    print("  누락 node: " + ", ".join(missing))
if wrong:
    print("  customer/environment 라벨 불일치 node: " + ", ".join(wrong))
    print("  기대값 customer=%s environment=%s" % (customer, environment))
sys.exit(1 if (missing or wrong) else 0)
PY
  log "Prometheus 라벨 규약 확인 (customer=${CUSTOMER}, environment=${TARGET_ENVIRONMENT})"

  # host 증거 수집기는 관측 역할이 node_exporter와 함께 설치한다. 유닛 파일이 있어야
  # install.py가 --group failstop-report drop-in을 붙여 보고 계정이 증거 파일을 읽게 만든다.
  if [[ -f /etc/systemd/system/failstop-host-evidence.service ]]; then
    log "failstop-host-evidence.timer 상태: $(systemctl is-active failstop-host-evidence.timer || true)"
  else
    warn "failstop-host-evidence.service가 없다. OPS01 자신의 host 증거가 보고서에서 미수집으로 남는다. 관측 역할의 install-node-exporter.sh를 먼저 실행한다"
  fi
}

# ---------------------------------------------------------------------------
# 4단계. Alertmanager 실행 파일 설치
# dnf로 받지 않는다. Rocky 10 기본 저장소의 제공 여부가 확인되지 않았고
# 설정의 msteamsv2_configs는 0.28 이상을 요구한다. 릴리스를 받아 해시를 대조한다.
# ---------------------------------------------------------------------------
stage_alertmanager_binaries() {
  step "4단계. Alertmanager ${AM_VERSION} 실행 파일 설치"

  if [[ -x /usr/local/bin/alertmanager ]] \
     && /usr/local/bin/alertmanager --version 2>&1 | grep -q "version ${AM_VERSION}"; then
    log "/usr/local/bin/alertmanager가 이미 ${AM_VERSION}이다. 내려받기를 건너뛴다"
    return
  fi

  TMP_DIR="$(mktemp -d /tmp/ops01-alertmanager.XXXXXX)"
  local filename="alertmanager-${AM_VERSION}.linux-amd64.tar.gz"
  local archive="${TMP_DIR}/${filename}"
  local sums="${TMP_DIR}/sha256sums.txt"

  if [[ -n "${AM_ARCHIVE}" ]]; then
    [[ -n "${AM_SUMS}" ]] || die "--alertmanager-archive를 쓰면 --alertmanager-sums도 지정한다. 해시 대조 없이 설치하지 않는다"
    cp -- "${AM_ARCHIVE}" "${archive}"
    cp -- "${AM_SUMS}" "${sums}"
    log "미리 받아 둔 아카이브를 사용한다: ${AM_ARCHIVE}"
  else
    # pfSense로 기본 경로를 옮기면 승인한 저장소만 열리므로 이 내려받기는 그 전에 끝낸다.
    local base="https://github.com/prometheus/alertmanager/releases/download/v${AM_VERSION}"
    log "릴리스를 내려받는다: ${base}/${filename}"
    curl -fsSL --proto '=https' --tlsv1.2 --max-time 300 -o "${archive}" "${base}/${filename}" \
      || die "Alertmanager 아카이브를 받지 못했다. NAT 경로가 살아 있는지 확인하거나 --alertmanager-archive로 넘긴다"
    curl -fsSL --proto '=https' --tlsv1.2 --max-time 60 -o "${sums}" "${base}/sha256sums.txt" \
      || die "sha256sums.txt를 받지 못했다"
  fi

  # 해시가 다르면 여기서 멈춘다. 확인하지 않은 실행 파일을 설치하지 않는다.
  local expected actual
  expected="$(awk -v name="${filename}" '{ file=$2; sub(/^\*/, "", file); if (file == name) print $1 }' "${sums}")"
  [[ -n "${expected}" ]] || die "sha256sums.txt에 ${filename} 항목이 없다"
  actual="$(sha256sum "${archive}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || die "Alertmanager SHA-256 불일치. 설치를 중단한다"
  log "SHA-256 대조 통과"

  # 아카이브 안의 경로를 그대로 풀지 않고 필요한 두 개만 표준출력으로 꺼낸다.
  local name
  for name in alertmanager amtool; do
    tar -xzf "${archive}" -O "alertmanager-${AM_VERSION}.linux-amd64/${name}" > "${TMP_DIR}/${name}" \
      || die "${name} 추출 실패"
    [[ -s "${TMP_DIR}/${name}" ]] || die "${name} 추출 결과가 비어 있다"
    install -o root -g root -m 0755 "${TMP_DIR}/${name}" "/usr/local/bin/${name}"
  done

  if command -v restorecon >/dev/null 2>&1; then
    restorecon -F /usr/local/bin/alertmanager /usr/local/bin/amtool
  fi
  log "설치 완료: $(/usr/local/bin/alertmanager --version 2>&1 | head -n 1)"
}

# ---------------------------------------------------------------------------
# 5단계. 보고 서비스 설치
# install.py가 전용 계정 failstop-report, 코드, 비밀 파일, 수집 토큰, systemd 유닛을 만든다.
# --enable-delivery는 넣지 않는다. 설치 직후에는 밖으로 아무것도 보내지 않는다.
# ---------------------------------------------------------------------------
stage_reporting_install() {
  step "5단계. 보고 서비스 설치"

  local args=(
    --apply
    --environment "${INSTALL_ENVIRONMENT}"
    --customer "${CUSTOMER}"
    --bind "${BIND_ADDRESS}"
    --prometheus-url "${PROMETHEUS_URL}"
    --alertmanager-url "${ALERTMANAGER_URL}"
    --analyzer "${ANALYZER}"
    --ai-model "${AI_MODEL}"
    --secret-file "${TEAMS_SECRET}"
  )
  if [[ "${ANALYZER}" == "gemini" ]]; then
    args+=(--ai-secret-file "${AI_SECRET}")
  fi
  # 학교에서 쓰던 --artifact-base-url, --pdf-font, --pdf-font-bold는 넣지 않는다.
  # VM에는 수신자가 닿을 HTTPS 주소가 없어 PDF·HTML 파일 제공을 끄기 때문이다.

  log "install.py 실행. 인수에 비밀 값은 없고 파일 경로만 들어간다"
  ( cd "${REPORTING_SRC}" && python3 ./install.py "${args[@]}" )

  getent passwd failstop-report >/dev/null || die "failstop-report 계정이 만들어지지 않았다"

  # 설치 직후 전송이 꺼져 있는지 확인한다. 이 파일에는 수집 토큰이 있으므로 내용을 출력하지 않는다.
  if ! grep -q '^REPORT_DELIVERY_ENABLED=false$' /etc/failstop/reporting.env; then
    die "REPORT_DELIVERY_ENABLED가 false가 아니다. 승인 전 전송을 막기 위해 중단한다"
  fi
  log "전송 비활성 상태 확인 (REPORT_DELIVERY_ENABLED=false)"
}

# ---------------------------------------------------------------------------
# 6단계. VM 보고 설정 적용
# install.py는 --environment로 local-lab과 aws만 받는다. 실제 VM 라벨은 vm-lab이므로
# 미리 vm-lab으로 묶어 둔 설정 파일로 덮어쓴다. 이 값이 prometheus.yml의 라벨과 같아야 한다.
# ---------------------------------------------------------------------------
stage_reporting_config() {
  step "6단계. VM 보고 설정 적용"

  # 설치된 코드의 검증 함수로 먼저 문법과 한도를 확인한다. 통과하지 못하면 덮어쓰지 않는다.
  CONFIG_PATH="${REPORT_CONFIG}" WANT_ENVIRONMENT="${TARGET_ENVIRONMENT}" python3 - <<'PY' \
    || die "보고 설정 파일이 검증을 통과하지 못했다: ${REPORT_CONFIG}"
import os
import sys

sys.path.insert(0, "/opt/failstop/deploy/reporting")
from reporter import load_config

config = load_config(os.environ["CONFIG_PATH"])
want = os.environ["WANT_ENVIRONMENT"]
if config.get("environment") != want:
    print("environment가 %s가 아니다: %r" % (want, config.get("environment")))
    sys.exit(1)
if config.get("artifacts", {}).get("enabled"):
    print("VM에서는 PDF·HTML 파일 제공을 켜지 않는다")
    sys.exit(1)
print("  queries=%d matchers=%s" % (len(config["queries"]), config["alert_matchers"]))
PY

  install -o root -g failstop-report -m 0640 "${REPORT_CONFIG}" /etc/failstop/reporting.json
  log "/etc/failstop/reporting.json 적용 (root:failstop-report 0640)"

  systemctl restart failstop-reporting.service

  # 서비스 계정이 실제로 읽을 수 있는지 그 계정으로 한 번 더 확인한다.
  runuser -u failstop-report -- python3 -c \
    "import sys; sys.path.insert(0, '/opt/failstop/deploy/reporting'); from reporter import load_config; c = load_config('/etc/failstop/reporting.json'); print('  보고 범위 customer=%s environment=%s' % (c['customer'], c['environment']))"
}

# ---------------------------------------------------------------------------
# 7단계. Alertmanager 설정과 서비스
#
# 계정 문제를 여기서 정리한다. install.py는 /etc/failstop/teams-webhook과
# /etc/failstop/reporter-token을 실행할 때마다 root:failstop-report 0640으로 다시 쓴다.
# 그런데 관측 역할(40-ops01-observability.sh)은 Alertmanager를 전용 계정 alertmanager로
# 돌리고 같은 파일을 root:alertmanager로 맞춘다. 두 계정이 같은 파일을 읽어야 한다.
#
# failstop-report 쪽에 보조 그룹을 붙이는 방법은 쓸 수 없다. install.py가 그 계정에
# 전용 기본 그룹 외의 그룹이 붙어 있으면 설치를 거부하기 때문이다. 그래서 반대로
# alertmanager 계정을 failstop-report 그룹에 넣는다. 이렇게 하면 어느 스크립트를 나중에
# 돌려도 두 서비스가 모두 파일을 읽는다.
#
# 관측 역할이 유닛을 먼저 올렸으면 그 유닛을 그대로 두고 그룹만 맞춘다. 아직 없으면
# 저장소의 alertmanager.service(User=failstop-report)를 설치한다.
# ---------------------------------------------------------------------------
stage_alertmanager_service() {
  step "7단계. Alertmanager 설정과 서비스"

  local unit=/etc/systemd/system/alertmanager.service
  local service_user=""
  if [[ -f "${unit}" ]]; then
    service_user="$(sed -n 's/^User=\(.*\)$/\1/p' "${unit}" | tail -n 1)"
  fi

  if [[ -n "${service_user}" && "${service_user}" != "failstop-report" ]] \
     && getent passwd "${service_user}" >/dev/null; then
    # 관측 역할이 올린 유닛을 그대로 쓴다. 실행 계정만 비밀 파일을 읽게 만든다.
    log "기존 alertmanager.service의 실행 계정 ${service_user}를 그대로 둔다"
    if id -nG "${service_user}" | tr ' ' '\n' | grep -qx failstop-report; then
      log "${service_user}가 이미 failstop-report 그룹에 있다"
    else
      usermod -aG failstop-report "${service_user}"
      log "${service_user}를 failstop-report 그룹에 넣었다. 비밀 파일은 root:failstop-report 0640이다"
    fi
  else
    # 보고 역할이 먼저 온 경우다. 저장소 유닛을 그대로 설치한다. 이 유닛은 failstop-report로 돈다.
    service_user="failstop-report"
    install -o root -g root -m 0644 "${LINUX_REPORTING_SRC}/alertmanager.service" "${unit}"
    # 원본 유닛의 Description은 학교 AWS 문구다. 파일을 고치지 않고 drop-in으로만 덮어쓴다.
    install -d -o root -g root -m 0755 /etc/systemd/system/alertmanager.service.d
    cat > /etc/systemd/system/alertmanager.service.d/vm-lab.conf <<'EOF'
# 원본 /etc/systemd/system/alertmanager.service는 failstop/deploy/linux/reporting/alertmanager.service
# 그대로이며 학교 AWS용 Description을 갖고 있다. 실행 조건은 바꾸지 않고 표시 이름만 VM 것으로 덮어쓴다.
[Unit]
Description=VM lab operational alerts
EOF
    chmod 0644 /etc/systemd/system/alertmanager.service.d/vm-lab.conf
    log "저장소의 alertmanager.service를 설치했다. 실행 계정은 failstop-report다"
  fi

  if systemctl is-active --quiet alertmanager.service; then
    systemctl stop alertmanager.service
    log "설정을 바꾸기 전에 alertmanager.service를 멈췄다"
  fi

  # 설정에는 비밀 값이 없고 비밀 파일의 경로만 있다. 그래도 실행 계정 밖으로 열지 않는다.
  local config_group
  config_group="$(id -gn "${service_user}" || true)"
  install -o root -g "${config_group}" -m 0640 "${ALERTMANAGER_CONFIG}" /etc/failstop/alertmanager.yml
  log "/etc/failstop/alertmanager.yml 적용 (root:${config_group} 0640, 원본 ${ALERTMANAGER_CONFIG})"

  # 설정이 가리키는 보고 수신 주소가 이 호스트의 루프백인지 확인한다. 학교 값이 남아 있으면 막는다.
  grep -q 'url: http://127.0.0.1:9088/alerts' /etc/failstop/alertmanager.yml \
    || die "evidence-reporter의 url이 http://127.0.0.1:9088/alerts가 아니다. 학교 AWS 주소가 남아 있는지 확인한다"
  grep -q 'webhook_url_file: /etc/failstop/teams-webhook' /etc/failstop/alertmanager.yml \
    || die "teams 수신자가 webhook_url_file을 쓰지 않는다. 웹훅 값을 설정 파일에 직접 넣지 않는다"

  # StateDirectory는 systemd가 만들지만 계정이 바뀌었을 수 있어 소유를 맞춘다.
  if [[ -d /var/lib/exchange-alertmanager ]]; then
    chown -R "${service_user}:${config_group}" /var/lib/exchange-alertmanager
  fi

  if command -v restorecon >/dev/null 2>&1; then
    restorecon -F "${unit}" /etc/failstop/alertmanager.yml
    [[ -f /etc/systemd/system/alertmanager.service.d/vm-lab.conf ]] \
      && restorecon -F /etc/systemd/system/alertmanager.service.d/vm-lab.conf
  fi

  /usr/local/bin/amtool check-config /etc/failstop/alertmanager.yml \
    || die "amtool check-config 실패. msteamsv2_configs를 모르는 버전이면 여기서 막힌다"
  # 실행 계정이 설정과 비밀 파일 두 개를 실제로 읽는지 그 계정으로 한 번 더 확인한다.
  runuser -u "${service_user}" -- /usr/local/bin/amtool check-config /etc/failstop/alertmanager.yml \
    || die "${service_user} 계정이 설정 또는 비밀 파일을 읽지 못한다. /etc/failstop의 소유와 권한을 확인한다"
  log "amtool check-config 통과 (root와 ${service_user} 양쪽)"

  systemctl daemon-reload
  systemctl enable --now alertmanager.service
  systemctl restart alertmanager.service
}

# ---------------------------------------------------------------------------
# 8단계. Prometheus 경보 규칙 연결
# 규칙 파일을 넣고 prometheus.yml에 rule_files와 alertmanagers를 붙인다.
# 검사에 실패하면 원문으로 되돌린다. 이미 붙어 있으면 손대지 않는다.
# ---------------------------------------------------------------------------
stage_prometheus_rules() {
  step "8단계. Prometheus 경보 규칙 연결"

  if (( SKIP_PROMETHEUS )); then
    warn "--skip-prometheus가 지정돼 규칙 설치와 prometheus.yml 편집을 건너뛴다"
    return
  fi

  local promtool=""
  local candidate
  for candidate in /usr/local/bin/promtool /usr/bin/promtool; do
    [[ -x "${candidate}" ]] && { promtool="${candidate}"; break; }
  done
  [[ -n "${promtool}" ]] || die "promtool을 찾지 못했다. 관측 역할의 Prometheus 설치를 확인한다"

  local rules_group="prometheus" rules_mode="0640"
  if ! getent group prometheus >/dev/null; then
    warn "prometheus 그룹이 없다. 규칙 파일을 root:root 0644로 둔다"
    rules_group="root"; rules_mode="0644"
  fi
  install -o root -g "${rules_group}" -m "${rules_mode}" "${RULES_FILE}" /etc/prometheus/vm-alerts.yml
  "${promtool}" check rules /etc/prometheus/vm-alerts.yml || die "promtool check rules 실패"
  log "/etc/prometheus/vm-alerts.yml 적용과 검사 통과"

  local config=/etc/prometheus/prometheus.yml
  [[ -f "${config}" ]] || die "${config}이 없다"

  # 이미 같은 항목이 있으면 손대지 않는다. 관측 역할의 prometheus.yml에는 처음부터 들어 있다.
  local need_rules=0 need_alerting=0
  grep -q 'vm-alerts.yml' "${config}" || need_rules=1
  if grep -q '^alerting:' "${config}"; then
    grep -q '127.0.0.1:9093' "${config}" \
      || warn "prometheus.yml에 alerting 블록이 있지만 127.0.0.1:9093이 없다. 경보가 Alertmanager로 가지 않을 수 있으니 손으로 확인한다"
  else
    need_alerting=1
  fi

  if (( need_rules || need_alerting )); then
    local backup="${config}.before-reporting-$(date +%Y%m%d)"
    [[ -e "${backup}" ]] || cp -p "${config}" "${backup}"
    log "prometheus.yml을 한 번 백업했다: ${backup}"

    # 되돌릴 원문은 변수가 아니라 파일에 둔다. 명령 치환은 끝의 줄바꿈을 잘라내기 때문이다.
    local original_file
    original_file="$(mktemp /tmp/ops01-prometheus.XXXXXX)"
    cat "${config}" > "${original_file}"
    {
      if (( need_rules )); then
        printf '\n# 보고 역할이 추가. 경보 규칙은 41-ops01-reporting.sh가 설치한다.\n'
        printf 'rule_files:\n  - /etc/prometheus/vm-alerts.yml\n'
      fi
      if (( need_alerting )); then
        printf '\n# 보고 역할이 추가. Alertmanager는 같은 호스트의 루프백에만 떠 있다.\n'
        printf 'alerting:\n  alertmanagers:\n    - static_configs:\n        - targets: ["127.0.0.1:9093"]\n'
      fi
    } >> "${config}"

    if ! "${promtool}" check config "${config}"; then
      # 리다이렉션으로 내용만 되돌린다. cp는 임시 파일의 소유·권한까지 옮겨 붙이기 때문이다.
      cat "${original_file}" > "${config}"
      rm -f "${original_file}"
      die "promtool check config 실패. prometheus.yml을 원문으로 되돌렸다. rule_files나 alerting이 이미 다른 형태로 있으면 손으로 병합한다"
    fi
    rm -f "${original_file}"
    log "prometheus.yml 편집과 검사 통과"
  else
    log "prometheus.yml에 rule_files와 alertmanagers가 이미 있다. 편집하지 않는다"
  fi

  systemctl restart prometheus.service
  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if curl -fsS --max-time 3 "${PROMETHEUS_URL}/-/ready" >/dev/null 2>&1; then
      log "Prometheus 재기동 확인"
      return
    fi
    sleep 2
  done
  die "Prometheus가 재기동 후 준비 상태가 되지 않았다"
}

# ---------------------------------------------------------------------------
# 9단계. 설치 확인
# 여기서 출력되는 값이 증거본에 남길 실제 확인 결과다. 실행하지 않은 항목은 아래 안내에만 적는다.
# ---------------------------------------------------------------------------
stage_verify() {
  step "9단계. 설치 확인"

  systemctl is-active alertmanager.service failstop-reporting.service \
    || die "alertmanager 또는 failstop-reporting이 active가 아니다"
  log "서비스 상태 active 확인"

  systemctl list-timers failstop-reporting-daily.timer --no-pager || true

  # 비밀 파일의 소유와 권한만 찍는다. 내용은 읽지 않으므로 이 출력은 그대로 캡처해도 된다.
  printf '\n-- /etc/failstop 권한 --\n'
  stat -c '  %n %U:%G %a' /etc/failstop/teams-webhook /etc/failstop/reporter-token \
    /etc/failstop/reporting.json /etc/failstop/reporting.env /etc/failstop/alertmanager.yml 2>/dev/null || true
  if [[ -f /etc/failstop/gemini-key ]]; then
    stat -c '  %n %U:%G %a' /etc/failstop/gemini-key
  fi

  # 관제·보고 포트가 VM 밖으로 열리면 주소계획의 루프백 전용 조건을 어긴다. 열려 있으면 멈춘다.
  printf '\n-- 수신 포트 --\n'
  ss -lntp | grep -E ':(3000|9088|9090|9093)\b' || true
  if ss -lnt | awk '{print $4}' | grep -Eq '^(0\.0\.0\.0|\[::\]|\*|192\.168\.[0-9]+\.[0-9]+):(3000|9088|9090|9093)$'; then
    die "관제·보고 포트가 루프백 밖에 열려 있다. 바인딩을 고친 뒤 다시 확인한다"
  fi
  log "3000·9088·9090·9093이 루프백에만 열린 것을 확인"

  # /health는 SQLite를 읽을 수 있다는 뜻이며 Teams 전달 성공의 증거가 아니다.
  printf '\n-- /health --\n'
  curl -fsS --max-time 5 http://127.0.0.1:9088/health || die "9088 /health 응답 실패"
  printf '\n'

  printf '\n-- 보고 대기열 상태 --\n'
  runuser -u failstop-report -- python3 "${REPORTING_SRC}/run-installed.py" status || true

  # 로그에 비밀 값이 섞여 나오지 않는지 개수만 센다. 일치한 줄 자체는 출력하지 않는다.
  local leaked
  leaked="$(journalctl -u failstop-reporting -u alertmanager --since today --no-pager 2>/dev/null \
    | grep -c -E 'logic\.azure\.com|api\.powerplatform\.com|AIza|Bearer ' || true)"
  if [[ "${leaked}" != "0" ]]; then
    warn "저널에서 비밀 값처럼 보이는 문자열이 ${leaked}건 잡혔다. 증거본에 로그를 붙이기 전에 직접 확인한다"
  else
    log "저널에 웹훅·키·Bearer 문자열 없음 (0건)"
  fi

  # 방화벽은 방화벽 역할의 파일이 담당한다. 여기서는 9088이 밖에서 닿지 않는지만 본다.
  #
  # 02-정본결정.md 4절대로 계통은 table inet vmlab 하나이고 input 기본 정책은 drop이다.
  # 기본 차단이 9088을 막으므로 포트마다 drop 규칙을 두지 않는다. 확인할 것은 두 가지다.
  # 위의 ss 확인이 9088 바인딩이 127.0.0.1인 것을 이미 보았고, 여기서 기본 정책이 drop인지 본다.
  #
  # 예전 확인은 table inet failstop에서 'tcp dport 9088' 규칙을 찾았다. 그 테이블은
  # 40-ops01-observability.sh가 공통 기반이 있으면 만들지 않으므로 정상 경로에서 항상 경고가 났고,
  # 경고문이 안내한 '40의 firewall 단계를 다시 실행'을 따라도 같은 이유로 바뀌지 않았다.
  # 해결되지 않는 안내가 증거본에 남지 않도록 확인 대상과 안내문을 함께 바꿨다.
  if command -v nft >/dev/null 2>&1; then
    local vmlab_input=""
    # policy drop 은 input 에, 역할별 허용은 roles 에 있다. 9088 허용 여부를 보려면 둘 다 읽는다.
    vmlab_input="$( { nft list chain inet vmlab input; nft list chain inet vmlab roles; } 2>/dev/null || true)"
    if [[ -n "${vmlab_input}" ]]; then
      if grep -q 'policy drop' <<<"${vmlab_input}"; then
        log "방화벽 기본 차단 확인: table inet vmlab의 input이 policy drop이다. 9088은 별도 규칙 없이 막힌다"
      else
        warn "table inet vmlab의 input 기본 정책이 drop이 아니다. 9088이 밖에서 닿을 수 있다. 공통 절차 00-common-setup.sh의 방화벽 단계를 확인한다"
      fi
      if grep -q 'tcp dport 9088' <<<"${vmlab_input}"; then
        warn "inet vmlab에 9088을 다루는 규칙이 있다. 00-주소계획.md 포트 표는 9088을 루프백만으로 정했다. /etc/vm-lab/firewall.d/의 역할 규칙을 확인한다"
      fi
    elif nft list table inet failstop >/dev/null 2>&1; then
      warn "예전 계통 table inet failstop이 남아 있다. 02-정본결정.md 4절대로 inet vmlab 한 계통만 쓴다. 규칙을 확인한 뒤 sudo nft delete table inet failstop으로 지우고 공통 절차를 적용한다"
    else
      warn "방화벽 테이블 inet vmlab이 없다. 공통 절차 00-common-setup.sh를 먼저 적용한다. 지금은 9088을 막는 것이 루프백 바인딩 한 겹뿐이다"
    fi
  fi

  if command -v ausearch >/dev/null 2>&1; then
    printf '\n-- 최근 SELinux 거부 --\n'
    ausearch -m AVC -ts today 2>/dev/null | tail -n 20 || echo "  최근 AVC 기록 없음"
  fi
}

print_next_steps() {
  cat <<'NEXT'

===== 여기까지가 자동 설치다. 다음은 사람이 확인하고 직접 한다 =====

1. 다른 VM에서 OPS01의 보고·관제 포트가 막힌 것을 확인한다.
     APP01에서:
       curl --connect-timeout 3 http://192.168.10.41:9088/health
       curl --connect-timeout 3 http://192.168.10.41:9093/
     둘 다 연결 거부나 타임아웃으로 끝나야 한다.

2. 경보가 Alertmanager를 지나 보고 서비스까지 오는지 확인한다.
     APP02에서 node_exporter를 1분 이상 멈추면 LinuxHostUnreachable이 발화한다.
       journalctl -u alertmanager --since -10m    teams 수신자 전달 시도
       journalctl -u failstop-reporting --since -10m    202 접수
       python3 <repo>/deploy/linux/reporting/check-school-alerts.py    PROM/AM/REPORT 세 줄 대조

3. 수신 대상과 보고 내용을 사람이 확인한 뒤에만 전송을 켠다.
     sed -i 's/^REPORT_DELIVERY_ENABLED=false$/REPORT_DELIVERY_ENABLED=true/' /etc/failstop/reporting.env
     systemctl restart failstop-reporting
     runuser -u failstop-report -- python3 <repo>/deploy/reporting/run-installed.py test-send
   HTTP 2xx는 접수 증거일 뿐이다. Teams 채널에서 카드가 실제로 보이는지 눈으로 확인하고 캡처한다.

4. 일일 보고 예정을 확인한다.
     systemctl list-timers failstop-reporting-daily.timer
   NEXT가 다음 09:00 KST여야 한다.

5. 장애 경로를 한 번 실제로 태운다.
     python3 <repo>/deploy/linux/reporting/disk-alert-exercise.py
   128MiB 시험 파일시스템만 채우고 7분 뒤 자동 반환한다. 기본 알림과 보고가 따로 도착하는지 본다.

6. 실패 견디기를 확인한다.
     AI 키를 잘못된 값으로 두고 경보를 태우면 보고가 facts-only로 그대로 도착한다.
     failstop-reporting을 멈추고 경보를 태우면 기본 장애 알림만 도착한다.

모든 단계의 명령과 출력을 증거본에 남긴다. 비밀 값을 입력하는 구간은 캡처하지 않고 누락 구간으로 표시한다.
NEXT
}

main() {
  stage_preflight
  stage_packages
  stage_upstream
  stage_alertmanager_binaries
  stage_reporting_install
  stage_reporting_config
  stage_alertmanager_service
  stage_prometheus_rules
  stage_verify
  print_next_steps
  printf '\n설치 절차를 끝냈다. 외부 전송은 아직 꺼져 있다.\n'
}

main "$@"
