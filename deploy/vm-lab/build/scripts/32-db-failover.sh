#!/usr/bin/env bash
#
# DB02 수동 승격 절차. DB02에서 sudo로 실행한다.
#
# 이 스크립트는 사람이 판단하고 실행하는 절차를 단계로 나눈 것이다. 자동 페일오버가
# 아니다. 승격 여부는 이 스크립트가 정하지 않는다. 판단은 운영자가 한다.
#
# 근거
#   failstop/docs/reference/operations/DB_수동_페일오버.md            순서의 정본
#   failstop/docs/reference/adr/0024-async-two-node-replication.md    비동기 2노드
#   failstop/docs/reference/adr/0025-rejoin-by-basebackup-no-wal-retention.md
#   failstop/deploy/linux/postgresql/prepare-db-recovery.py           격리 확인 게이트
#   failstop/deploy/linux/postgresql/verify-db-recovery.py            승격 뒤 정합성 판정
#   build/00-주소계획.md                                              주소와 허용 출발지
#   아키텍쳐/교수님_보고본/06_VM_운영환경/VM_DB_장애복구_흐름도.svg   절차 설계 원본
#
# 지켜야 하는 원칙
#   구 Primary를 쓰기 불가로 만든 뒤에 새 Primary를 연다. 순서를 바꾸면 두 노드가
#   동시에 쓰기 가능해지고, 복제로 이어져 있지 않은 두 쓰기는 병합할 방법이 없다.
#   "확실히 죽었다"는 판단에 기대지 않는다. 명령 출력으로 판정한다.
#
#   승격은 되돌릴 수 없다. 타임라인이 올라가 DB01을 그대로 Standby로 붙일 수 없고
#   전량 베이스백업이 기본 경로다. 일시적인 네트워크 흔들림에 승격하면 되돌리는
#   비용이 더 크다.
#
#   비동기 복제다. 지연 0이나 receive와 replay LSN 일치를 무손실 근거로 쓰지 않는다.
#
# 각 단계는 앞 단계가 끝났는지 기록에서 확인하고, 그 단계의 조건을 실제 명령 출력으로
# 다시 판정한다. 조건이 확인되지 않으면 다음으로 넘어가지 않는다.
#
# 실행 방법
#   sudo /opt/vm-lab/scripts/32-db-failover.sh 01-detect
#   sudo /opt/vm-lab/scripts/32-db-failover.sh status
#
# 한 번의 장애 대응은 하나의 실행 기록으로 묶인다.
#   /var/log/vm-lab/failover/<실행 ID>/  단계 기록, 증거 파일, 불변식 CSV, 판정 결과
#
# 이 파일은 절차이며 검증 결과가 아니다. 이 절차는 아직 실행하지 않았다.
# 승격 소요 시간, 유실량, RTO와 RPO는 전부 미측정이다.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 고정 값. 00-주소계획.md의 값과 같아야 한다.
# ---------------------------------------------------------------------------
NODE_NAME=db02
LAB_IP=192.168.10.32
MGMT_IP=192.168.56.32
PRIMARY_IP=192.168.10.31        # DB01 lab-lan
PRIMARY_MGMT_IP=192.168.56.31   # DB01 관리 주소
APP01_IP=192.168.10.21
APP02_IP=192.168.10.22
OPS01_IP=192.168.10.41

DB_NAME=exchange
APP_ROLE=exchange_app
REPL_USER=replicator
CONF_DIR=/etc/postgresql
RUN_ROOT=/var/log/vm-lab/failover

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BUILD_ROOT=${BUILD_ROOT:-$(cd -- "$SCRIPT_DIR/.." && pwd)}
FILES_DIR=${FILES_DIR:-$BUILD_ROOT/build-files/db}
SUMMARY_SQL=${SUMMARY_SQL:-$FILES_DIR/summary.sql}

# ---------------------------------------------------------------------------
# 공통 함수. 31 스크립트와 같은 이름, 같은 출력 모양을 쓴다.
# ---------------------------------------------------------------------------
log()  { printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
info() { printf '       %s\n' "$*"; }
warn() { printf '  [확인 필요] %s\n' "$*" >&2; }
die()  { printf '\n  [중단] %s\n' "$*" >&2; exit 1; }
now()  { date -u +%Y-%m-%dT%H:%M:%SZ; }

need_root() { [[ ${EUID} -eq 0 ]] || die '이 스크립트는 sudo로 실행한다.'; }

need_host() {
  local host
  host=$(hostnamectl --static 2>/dev/null || hostname)
  [[ $host == ${NODE_NAME}* ]] || die "호스트 이름이 ${NODE_NAME}로 시작하지 않는다. DB01에서 실행하고 있지 않은지 확인한다."
  ip -brief -4 addr show | grep -qw "$LAB_IP/24" || die "lab-lan 주소 $LAB_IP 가 없다. 대상 VM을 확인한다."
}

PG_UNIT=${PG_UNIT:-}
PGDATA=${PGDATA:-}
PG_BIN=${PG_BIN:-}
detect_pg() {
  if [[ -z $PG_UNIT ]]; then
    PG_UNIT=$(systemctl list-unit-files --type=service --no-legend 'postgresql*.service' 2>/dev/null \
      | awk '{print $1}' | grep -v '@' | head -n1 || true)
  fi
  [[ -n $PG_UNIT ]] || die 'postgresql 서비스 유닛을 찾지 못했다.'
  if [[ -z $PGDATA ]]; then
    PGDATA=$(systemctl show -p Environment --value "$PG_UNIT" 2>/dev/null \
      | tr ' ' '\n' | sed -n 's/^PGDATA=//p' | head -n1 || true)
  fi
  [[ -n $PGDATA ]] || die "유닛 $PG_UNIT 에서 PGDATA를 읽지 못했다."
  if [[ -z $PG_BIN ]]; then
    if [[ -x /usr/pgsql-17/bin/psql ]]; then
      PG_BIN=/usr/pgsql-17/bin
    elif command -v psql >/dev/null 2>&1; then
      PG_BIN=$(dirname -- "$(command -v psql)")
    else
      die 'psql을 찾지 못했다.'
    fi
  fi
}

psql_postgres() { ( cd /tmp && sudo -u postgres "$PG_BIN/psql" -X -v ON_ERROR_STOP=1 -P pager=off "$@" ); }
psql_value()    { psql_postgres -qAt "$@"; }

common_base_present() {
  [[ -f /etc/vm-lab/firewall.nft ]] \
    && systemctl list-unit-files vm-lab-firewall.service >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# 실행 기록
# ---------------------------------------------------------------------------
run_dir() {
  [[ -f $RUN_ROOT/current ]] || die '진행 중인 승격 절차가 없다. 01-detect 부터 시작한다.'
  local id
  id=$(cat "$RUN_ROOT/current")
  [[ -d $RUN_ROOT/$id ]] || die "실행 기록 디렉터리가 없다: $RUN_ROOT/$id"
  printf '%s\n' "$RUN_ROOT/$id"
}

record() {
  local dir
  dir=$(run_dir)
  printf '%s %s\n' "$(now)" "$*" >> "$dir/evidence.log"
  info "$*"
}

mark_step() {
  local dir
  dir=$(run_dir)
  printf 'done:%s at=%s\n' "$1" "$(now)" >> "$dir/state"
  log "단계 완료: $1"
}

require_step() {
  local dir
  dir=$(run_dir)
  grep -q "^done:$1 " "$dir/state" || die "앞 단계가 끝나지 않았다: $1
  status 로 진행 상태를 먼저 본다."
}

already_done() {
  local dir
  dir=$(run_dir)
  if grep -q "^done:$1 " "$dir/state"; then
    die "이미 끝난 단계다: $1
  다시 실행해야 하면 기록을 확인하고 사람이 판단한다."
  fi
}

# 되돌릴 수 없는 단계와 다른 호스트의 상태를 바꾸는 단계는 문구를 정확히 입력해야 진행한다.
confirm() {
  local phrase=$1 message=$2 answer
  [[ -e /dev/tty ]] || die '대화형 확인이 필요하다. 터미널에서 직접 실행한다.'
  printf '\n%s\n' "$message"
  printf "       계속하려면 정확히 '%s' 를 입력한다: " "$phrase"
  IFS= read -r answer < /dev/tty
  [[ $answer == "$phrase" ]] || die '확인 문구가 일치하지 않아 중단한다.'
}

ask_note() {
  local prompt=$1 answer
  [[ -e /dev/tty ]] || die '대화형 입력이 필요하다. 터미널에서 직접 실행한다.'
  printf '\n       %s\n       > ' "$prompt" >&2
  IFS= read -r answer < /dev/tty
  [[ -n $answer ]] || die '내용을 비운 채로 넘어가지 않는다.'
  printf '%s' "$answer"
}

# 대상 포트가 닫혀 있는지 반복해서 본다. 한 번의 실패를 격리 근거로 쓰지 않는다.
# 모두 실패하면 0(닫힘), 한 번이라도 붙으면 1(열림)을 돌려준다.
probe_closed() {
  local host=$1 port=$2 i
  for i in 1 2 3 4 5; do
    if ncat -z -w 3 "$host" "$port" 2>/dev/null; then
      return 1
    fi
    sleep 2
  done
  return 0
}

# ---------------------------------------------------------------------------
# 01-detect: 장애 확인
# ---------------------------------------------------------------------------
step_01_detect() {
  log '01-detect: 장애를 확인하고 기록을 시작한다'
  need_root; need_host; detect_pg

  local id dir
  id=$(date -u +%Y%m%dT%H%M%SZ)
  install -d -o root -g root -m 0750 "$RUN_ROOT" "$RUN_ROOT/$id"
  printf '%s\n' "$id" > "$RUN_ROOT/current"
  chmod 0640 "$RUN_ROOT/current"
  dir=$RUN_ROOT/$id
  : > "$dir/state"
  : > "$dir/evidence.log"
  date -u +%s > "$dir/started_at_epoch"
  log "실행 기록: $dir"

  record "run_id=$id"
  record "pg_unit=$PG_UNIT pgdata=$PGDATA"

  # 앱 장애와 DB 장애를 가른다. pg_isready 는 DB 쪽 응답만 본다.
  local ready_out ready_rc=0
  ready_out=$("$PG_BIN/pg_isready" -h "$PRIMARY_IP" -p 5432 -t 5 2>&1) || ready_rc=$?
  record "db01_pg_isready_rc=$ready_rc"
  record "db01_pg_isready_out=$ready_out"

  # 이 호스트가 아직 Standby인지 본다. 이미 f면 승격 절차를 다시 시작하지 않는다.
  local in_recovery
  in_recovery=$(psql_value -c 'SELECT pg_is_in_recovery();')
  record "db02_in_recovery=$in_recovery"
  [[ $in_recovery == t ]] || die 'DB02가 이미 Standby가 아니다. 현재 상태를 먼저 확인한다.'

  local receiver
  receiver=$(psql_value -c "SELECT coalesce((SELECT status FROM pg_stat_wal_receiver LIMIT 1), 'none');")
  record "db02_wal_receiver=$receiver"

  cat <<'EOF'

       앱 쪽 신호도 함께 본다. 앱의 리스 갱신 실패와 DB 접속 실패는 다른 사건이다.
         APP01/APP02  curl -fsS http://127.0.0.1:8081/actuator/health
                      exchange_leader_lease_renew_total{outcome="error"} 증가 여부
       앱 리더 전환은 DB가 살아 있을 때 앱 사이에서 도는 장치다. 여기는 DB 자체가
       죽었을 때의 절차이므로 둘을 같은 사건으로 적지 않는다.

       장애 시각과 외부 ACK는 장애가 난 DB 밖에 보존한다. 승격한 DB의 명령 저널과
       대조할 원본이기 때문이다.
EOF
  local fail_note ack_note
  fail_note=$(ask_note '장애를 인지한 시각과 근거를 적는다. 예: 02:11Z, APP01 health DOWN, DB01 pg_isready 실패')
  ack_note=$(ask_note '외부 ACK를 어디에 보존했는지 경로나 위치를 적는다. DB01 안이면 지금 밖으로 옮긴다')
  record "failure_note=$fail_note"
  record "ack_preserved_at=$ack_note"

  mark_step 01-detect
  info '다음은 02-hold 다. 신규 접수를 보류하기 전에는 승격하지 않는다.'
}

# ---------------------------------------------------------------------------
# 02-hold: 신규 접수 보류
# ---------------------------------------------------------------------------
step_02_hold() {
  log '02-hold: 신규 접수를 보류한다'
  need_root; need_host
  require_step 01-detect
  already_done 02-hold

  cat <<'EOF'

       APP01과 APP02에서 쓰기 요청과 봇 주문 접수를 멈춘다. 이 단계 전에 승격하지 않는다.
       멈추는 방법은 구성에 따라 다르므로 이 스크립트가 대신 실행하지 않는다.
       어떤 방법을 썼는지와 그 결과를 아래에 남긴다.
EOF
  local how
  how=$(ask_note '어느 호스트에서 어떤 명령으로 접수를 멈췄는지 적는다')
  record "intake_hold=$how"
  confirm 'HOLD confirmed' '쓰기 요청과 봇 주문 접수가 실제로 멈춘 것을 확인했는가.'
  mark_step 02-hold
  info '다음은 03-fence 다. 격리가 확인되기 전에는 승격하지 않는다.'
}

# ---------------------------------------------------------------------------
# 03-fence: DB01 격리 확인. 이 절차에서 가장 중요한 게이트다.
# ---------------------------------------------------------------------------
# prepare-db-recovery.py 의 rejoin-old 분기는 ActiveState=inactive 와
# UnitFileState=masked 를 검사하고 하나라도 어긋나면 즉시 멈춘다. 같은 조건을 여기서 본다.
#
# 확인해야 하는 네 가지
#   C1 DB01의 PostgreSQL이 비활성이다
#   C2 DB01의 PostgreSQL이 재기동되지 않는다
#   C3 DB01에 5432 LISTEN이 없다
#   C4 앱에서 DB01로의 접속이 거부된다
# 하나라도 명령 출력으로 확인되지 않으면 승격을 중단한다. 사람의 보증으로 대신하지 않는다.
# 죽은 줄 알았던 노드가 깨어나 두 노드가 동시에 쓰기 가능해지는 상황을 막기 위한 조건이다.
fence_usage() {
  cat <<EOF

       격리 근거가 없다. 다음 중 하나를 만든 뒤 다시 실행한다.

       가. DB01에 접근할 수 있는 경우. DB01에서 서비스를 내리고 증거 파일을 만든다.
           PG_UNIT=\$(systemctl list-unit-files --type=service --no-legend 'postgresql*.service' \\
                     | awk '{print \$1}' | grep -v '@' | head -n1)
           sudo systemctl stop "\$PG_UNIT"
           sudo systemctl mask "\$PG_UNIT"
           {
             printf 'hostname=%s\\n' "\$(hostname -s)"
             printf 'unit=%s\\n' "\$PG_UNIT"
             printf 'ActiveState=%s\\n' "\$(systemctl show "\$PG_UNIT" -p ActiveState --value)"
             printf 'UnitFileState=%s\\n' "\$(systemctl show "\$PG_UNIT" -p UnitFileState --value)"
             printf 'listen_5432_count=%s\\n' "\$(ss -lnt | grep -c ':5432' || true)"
           } > /tmp/db01-isolation.txt
           scp /tmp/db01-isolation.txt ops@$MGMT_IP:/tmp/

       나. DB01에 접근할 수 없는 경우. 작업 PC에서 전원을 내린다.
           대상 이름이 db01인지 확인하고 실행한다.
           VBoxManage controlvm db01 poweroff

       두 경우 모두 앱 쪽 증거가 필요하다. APP01 또는 APP02에서 만든다.
           { printf 'hostname=%s\\n' "\$(hostname -s)"
             ncat -z -w 3 $PRIMARY_IP 5432; printf 'app_probe_exit=%s\\n' "\$?" ; } > /tmp/app-probe.txt
           scp /tmp/app-probe.txt ops@$MGMT_IP:/tmp/

       그리고 DB02에서 둘 중 하나로 실행한다.
           sudo $0 03-fence --db01-evidence /tmp/db01-isolation.txt --app-evidence /tmp/app-probe.txt
           sudo $0 03-fence --powered-off 'VBoxManage controlvm db01 poweroff' --app-evidence /tmp/app-probe.txt
EOF
}

step_03_fence() {
  log '03-fence: DB01 격리를 확인한다'
  need_root; need_host
  require_step 02-hold
  already_done 03-fence

  local dir db01_evidence='' app_evidence='' powered_off=''
  dir=$(run_dir)
  while (($#)); do
    case "$1" in
      --db01-evidence) db01_evidence=${2:?}; shift 2 ;;
      --app-evidence)  app_evidence=${2:?};  shift 2 ;;
      --powered-off)   powered_off=${2:?};   shift 2 ;;
      *) fence_usage; die "알 수 없는 인수: $1" ;;
    esac
  done

  command -v ncat >/dev/null 2>&1 || die 'ncat 이 없다. nmap-ncat 을 설치한다.'

  # 이 호스트에서 직접 재는 값이다. 전달받은 파일보다 먼저 본다.
  info "DB01 $PRIMARY_IP:5432 를 다섯 번 두드린다"
  local db_closed=1 ssh_closed=1
  if probe_closed "$PRIMARY_IP" 5432; then db_closed=0; fi
  info "DB01 관리 $PRIMARY_MGMT_IP:22 를 다섯 번 두드린다"
  if probe_closed "$PRIMARY_MGMT_IP" 22; then ssh_closed=0; fi
  record "probe_db01_5432_closed=$db_closed"
  record "probe_db01_mgmt22_closed=$ssh_closed"
  [[ $db_closed == 0 ]] || die 'DB01의 5432가 아직 응답한다. 격리되지 않았다. 승격을 중단한다.'

  # --- C1, C2, C3 ---
  if [[ -n $db01_evidence ]]; then
    [[ -f $db01_evidence ]] || die "증거 파일이 없다: $db01_evidence"
    cp -- "$db01_evidence" "$dir/db01-isolation.txt"
    local text
    text=$(cat "$dir/db01-isolation.txt")
    grep -q '^hostname=db01$' <<<"$text" || die '증거 파일이 db01의 출력이 아니다.'
    grep -q '^ActiveState=inactive$' <<<"$text" || die 'C1 미충족. DB01의 PostgreSQL이 비활성이 아니다.'
    grep -q '^UnitFileState=masked$' <<<"$text" || die 'C2 미충족. DB01의 재기동 차단(mask)이 확인되지 않았다.'
    grep -q '^listen_5432_count=0$' <<<"$text" || die 'C3 미충족. DB01에 5432 LISTEN이 남아 있다.'
    record 'fence_path=service-stop-and-mask'
    record 'fence_c1_c2_c3=확인'
  elif [[ -n $powered_off ]]; then
    # DB01에 접근할 수 없는 경우다. 하이퍼바이저에서 전원을 내린 뒤에만 인정한다.
    [[ $ssh_closed == 0 ]] \
      || die 'DB01의 관리 SSH가 아직 응답한다. 전원 차단 경로로 판정할 수 없다.
  DB01에 들어갈 수 있다면 서비스를 내리고 증거 파일을 만드는 경로를 쓴다.'
    record 'fence_path=hypervisor-power-off'
    record "power_off_command=$powered_off"
    confirm 'POWERED OFF db01' \
      "       위 명령으로 DB01 VM의 전원을 내렸고, 이 호스트에서 5432와 관리 22가 모두 닫힌 것을 확인했는가."
    record 'fence_c1_c2_c3=전원 차단과 두 포트 닫힘으로 확인'
  else
    fence_usage
    die '격리 근거가 없어 승격을 중단한다.'
  fi

  # --- C4 ---
  [[ -n $app_evidence && -f $app_evidence ]] \
    || { fence_usage; die 'C4 미충족. APP01 또는 APP02의 접속 거부 증거 파일이 필요하다.'; }
  cp -- "$app_evidence" "$dir/app-probe.txt"
  local app_text app_exit
  app_text=$(cat "$dir/app-probe.txt")
  grep -qE '^hostname=app0[12]$' <<<"$app_text" || die 'C4 미충족. 앱 호스트의 출력이 아니다.'
  app_exit=$(grep -E '^app_probe_exit=' <<<"$app_text" | head -n 1 | cut -d= -f2 || true)
  [[ -n $app_exit ]] || die 'C4 미충족. app_probe_exit 값이 없다.'
  [[ $app_exit != 0 ]] || die 'C4 미충족. 앱에서 DB01의 5432에 아직 붙는다. 승격을 중단한다.'
  record "app_probe_exit=$app_exit"
  record 'fence_c4=확인'

  log 'C1부터 C4까지 모두 명령 출력으로 확인했다'
  mark_step 03-fence
  info '다음은 04-lsn 이다. 유실 범위를 먼저 기록한다.'
}

# ---------------------------------------------------------------------------
# 04-lsn: 유실 범위 판단
# ---------------------------------------------------------------------------
step_04_lsn() {
  log '04-lsn: 수신과 재생 위치를 맞추고 기록한다'
  need_root; need_host; detect_pg
  require_step 03-fence
  already_done 04-lsn

  local i receive='' replay='' same=0
  # 재생이 끝나기를 기다린다. 재생 중 승격하면 그만큼 더 잃는다.
  for i in $(seq 1 60); do
    receive=$(psql_value -c 'SELECT pg_last_wal_receive_lsn();')
    replay=$(psql_value -c 'SELECT pg_last_wal_replay_lsn();')
    if [[ $receive == "$replay" ]]; then
      same=$((same + 1))
      (( same >= 3 )) && break
    else
      same=0
    fi
    sleep 2
  done
  record "last_wal_receive_lsn=$receive"
  record "last_wal_replay_lsn=$replay"
  psql_postgres -c 'SELECT pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn(), pg_last_xact_replay_timestamp();' \
    | sed 's/^/       /'

  if [[ $receive != "$replay" ]]; then
    warn 'receive 와 replay 가 아직 다르다. 재생이 끝나지 않았다.'
    record 'replay_caught_up=no'
    confirm 'PROCEED with lag' '       재생이 끝나지 않은 상태로 진행하면 그만큼 더 잃는다. 그래도 진행하는가.'
  else
    record 'replay_caught_up=yes'
  fi

  # DB01은 격리했으므로 pg_current_wal_lsn 을 조회할 수 없는 것이 정상이다.
  # 조회하지 못한 것을 "유실 없음"으로 적지 않는다.
  record 'primary_current_wal_lsn=미확인 reason=DB01 격리로 조회 불가'

  cat <<'EOF'

       여기서 기록한 값은 이 Standby가 어디까지 받았는지다. DB01이 급사하면 아직
       전송되지 않은 WAL이 유실된다. 지연 0이나 두 LSN의 일치를 무손실 판정으로
       쓰지 않는다. 실제 유실 범위는 08-verify 에서 외부 ACK와 승격한 DB의 명령
       저널을 대조해 판정한다. 확인할 수 없으면 미확인으로 남긴다.
EOF
  mark_step 04-lsn
  warn '다음은 05-promote 다. 되돌릴 수 없는 단계다.'
}

# ---------------------------------------------------------------------------
# 05-promote: 승격
# ---------------------------------------------------------------------------
step_05_promote() {
  log '05-promote: Standby를 승격한다'
  need_root; need_host; detect_pg
  require_step 04-lsn
  already_done 05-promote

  [[ $(psql_value -c 'SELECT pg_is_in_recovery();') == t ]] || die '이미 Standby가 아니다.'

  confirm 'PROMOTE db02' \
    '       승격은 되돌릴 수 없다. 타임라인이 올라가 DB01을 그대로 Standby로 붙일 수 없고
       전량 베이스백업이 기본 경로가 된다. 격리가 확인되지 않았다면 지금 멈춘다.'

  local started ended in_recovery
  started=$(date -u +%s)
  psql_postgres -c 'SELECT pg_promote(true, 60);' | sed 's/^/       /'
  ended=$(date -u +%s)

  in_recovery=$(psql_value -c 'SELECT pg_is_in_recovery();')
  record "promote_seconds=$((ended - started))"
  record "in_recovery_after_promote=$in_recovery"
  [[ $in_recovery == f ]] || die '승격 후에도 pg_is_in_recovery() 가 f 가 아니다. 상태를 확인한다.'

  record "timeline_id=$(psql_value -c 'SELECT timeline_id FROM pg_control_checkpoint();')"
  log '승격했다. 쓰기 가능한 DB는 이제 한 대여야 한다.'
  warn '승격 명령 시간은 전체 복구 시간이 아니다. 09-resume 에서 장애 발생부터 재개까지를 따로 기록한다.'
  mark_step 05-promote
  info '다음은 06-open 이다. HBA와 방화벽을 열기 전에는 앱이 붙지 못한다.'
}

# ---------------------------------------------------------------------------
# 06-open: HBA와 방화벽 전환
# ---------------------------------------------------------------------------
# 승격 전 DB02의 5432를 막는 것은 nftables 한 겹이다. pg_hba.conf 는 pg_basebackup 이
# DB01의 파일을 그대로 복사하므로 앱 접속 규칙을 이미 갖고 있다. "방화벽과 pg_hba 두 겹"은
# DB01에서만 성립한다. 그러니 이 단계의 핵심은 방화벽 교체이고, 아래 HBA 규칙 추가는
# 복사본에 빠진 줄이 있을 때를 대비한 보정이다. 이미 있으면 그대로 둔다.
# 재편입 경로(DB01 -> DB02 복제)의 replication 줄은 복사본에 없으므로 여기서 더한다.
step_06_open() {
  log '06-open: HBA 규칙과 방화벽을 연다'
  need_root; need_host; detect_pg
  require_step 05-promote
  already_done 06-open

  local dir hba backup
  dir=$(run_dir)
  [[ $(psql_value -c 'SELECT pg_is_in_recovery();') == f ]] || die '승격되지 않은 상태다.'

  hba=$(psql_value -c 'SHOW hba_file;')
  backup=$dir/pg_hba.before.conf
  [[ -f $backup ]] || cp -p "$hba" "$backup"
  info "pg_hba.conf: $hba (원본은 $backup 에 보관)"

  local rule
  for rule in \
    "hostssl $DB_NAME $APP_ROLE $APP01_IP/32 scram-sha-256" \
    "hostssl $DB_NAME $APP_ROLE $APP02_IP/32 scram-sha-256" \
    "hostssl replication $REPL_USER $PRIMARY_IP/32 scram-sha-256"; do
    if grep -qF "$rule" "$hba"; then
      info "이미 있는 규칙: $rule"
    else
      printf '%s\n' "$rule" >> "$hba"
      info "추가한 규칙: $rule"
    fi
  done

  # 형식 오류가 있으면 되돌린다. 깨진 HBA로 reload 하지 않는다.
  local errors
  errors=$(psql_value -c 'SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL;')
  if [[ $errors != 0 ]]; then
    cp -p "$backup" "$hba"
    die "HBA 형식 오류가 $errors 건이다. 원본으로 되돌렸다. 파일을 직접 확인한다."
  fi
  psql_value -c 'SELECT pg_reload_conf();' >/dev/null
  errors=$(psql_value -c 'SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL;')
  [[ $errors == 0 ]] || die 'reload 후에도 HBA 오류가 남아 있다.'
  psql_postgres -c 'SELECT line_number,type,database,user_name,address,auth_method FROM pg_hba_file_rules ORDER BY line_number;' \
    | sed 's/^/       /'
  record 'hba_rules_added=app01,app02,replication-db01'

  # 승격용 규칙 파일로 교체한다. 평상시 파일에는 5432 accept 가 없다.
  # 방화벽은 공통 기반 table inet vmlab 한 계통뿐이다. 단독 테이블 경로는 없앴다.
  common_base_present || die '공통 기반(/etc/vm-lab/firewall.nft, vm-lab-firewall.service)이 없다.
  이 호스트의 방화벽이 공통 기반으로 서 있지 않으면 승격용 규칙을 얹을 자리가 없다.
  00-common-setup.sh 와 31-db-standby-setup.sh 의 firewall 단계를 먼저 확인한다.'

  local role_file=/etc/vm-lab/firewall.d/31-db02.nft
  [[ -f $FILES_DIR/db02-firewall-promoted.d.nft ]] \
    || die "승격용 규칙 파일이 없다: $FILES_DIR/db02-firewall-promoted.d.nft"
  # 평상시 파일을 실행 기록에 보관한다. 되돌릴 때 이 사본을 쓴다.
  if [[ -f $role_file ]]; then
    cp -p "$role_file" "$dir/31-db02.standby.nft"
  else
    warn "$role_file 이 없다. 31 스크립트의 firewall 단계를 적용했는지 확인한다."
  fi
  install -o root -g root -m 0600 "$FILES_DIR/db02-firewall-promoted.d.nft" "$role_file"
  if ! nft --check --file /etc/vm-lab/firewall.nft; then
    if [[ -f $dir/31-db02.standby.nft ]]; then
      cp -p "$dir/31-db02.standby.nft" "$role_file"
      die '승격용 규칙 구문이 맞지 않다. 평상시 파일로 되돌렸다.'
    fi
    rm -f "$role_file"
    die '승격용 규칙 구문이 맞지 않다. 역할 파일을 지웠다.'
  fi
  systemctl reload vm-lab-firewall.service || systemctl restart vm-lab-firewall.service
  { nft list chain inet vmlab input; nft list chain inet vmlab roles 2>/dev/null; } | tee "$dir/firewall.after.txt" | sed 's/^/       /'

  local accepts
  accepts=$(grep 'dport 5432' "$dir/firewall.after.txt" | grep -c accept || true)
  [[ $accepts == 3 ]] || die "5432 accept 규칙이 3개가 아니다(현재 $accepts). 규칙 파일을 확인한다."
  ss -lnt | grep -q "$LAB_IP:5432" || die "5432가 $LAB_IP 에서 LISTEN 상태가 아니다."
  record "firewall_5432_accept_rules=$accepts"

  log '앱 두 대와 DB01 재편입 경로에만 5432를 열었다'
  mark_step 06-open
}

# ---------------------------------------------------------------------------
# 07-app-switch: 앱 연결 변경
# ---------------------------------------------------------------------------
step_07_app_switch() {
  log '07-app-switch: 앱의 DB 연결을 이 호스트로 바꾼다'
  need_root; need_host; detect_pg
  require_step 06-open
  already_done 07-app-switch

  local fingerprint
  fingerprint=$(openssl x509 -in "$CONF_DIR/server.crt" -noout -fingerprint -sha256)
  record "db02_cert_fingerprint=$fingerprint"

  cat <<EOF

       APP01과 APP02에 아래 지문을 전달한다. 앱은 이 인증서를 sslmode=verify-full 로 검증한다.
         $fingerprint

       앱 쪽 순서는 switch-db-endpoint.py 의 전제 조건과 같다.
         1. 서비스를 내린 상태에서만 접속 대상을 바꾼다. ActiveState=inactive 를 먼저 확인한다.
         2. 바꾸기 전에 기존 설정과 인증서의 백업 파일을 만든다. 기존 백업이 있으면 중단한다.
         3. 받은 인증서의 지문을 위 값과 대조한다. 전송 자체를 신뢰 근거로 삼지 않는다.
         4. DB 주소를 $LAB_IP:5432 로 바꾸고 재기동한다.
       일반 거래 접수는 계속 보류한다. 이 단계는 연결만 바꾼다.
EOF
  confirm 'SWITCH requested' '       위 내용을 APP01과 APP02에 전달했는가.'

  # 앱이 실제로 붙었는지 이 DB에서 확인한다. 전달했다는 말로 넘어가지 않는다.
  info '앱 접속을 기다린다. 최대 5분.'
  local i connected=''
  for i in $(seq 1 60); do
    connected=$(psql_value -d "$DB_NAME" -c \
      "SELECT coalesce(string_agg(DISTINCT host(client_addr), ','), '') FROM pg_stat_activity
       WHERE client_addr IN ('$APP01_IP','$APP02_IP');" || true)
    [[ -n $connected ]] && break
    sleep 5
  done
  [[ -n $connected ]] || die '앱 접속이 확인되지 않았다.
  확인할 곳: 앱의 DB 주소와 인증서, 이 호스트의 pg_hba.conf, 방화벽 5432 규칙.'
  record "app_clients_connected=$connected"
  psql_postgres -d "$DB_NAME" -c \
    'SELECT client_addr, usename, state, count(*) FROM pg_stat_activity
     WHERE client_addr IS NOT NULL GROUP BY 1, 2, 3;' | sed 's/^/       /'

  log "앱 접속을 확인했다: $connected"
  mark_step 07-app-switch
}

# ---------------------------------------------------------------------------
# 08-verify: ACK 대조와 정합성 판정
# ---------------------------------------------------------------------------
# verify-db-recovery.py 와 같은 방식이다. 한 스냅샷에서 불변식을 판정하고
# CRITICAL 과 HIGH 실패가 남으면 거래를 재개하지 않는다.
step_08_verify() {
  log '08-verify: 한 스냅샷에서 불변식을 판정하고 ACK를 대조한다'
  need_root; need_host; detect_pg
  require_step 07-app-switch
  already_done 08-verify

  local dir csv
  dir=$(run_dir)
  [[ -f $SUMMARY_SQL ]] || die "불변식 질의가 없다: $SUMMARY_SQL
  작업 PC의 build 폴더에서 keys/ 를 뺀 나머지를 /opt/vm-lab 로 복사했는지 확인한다.
  관리 개인키는 VM으로 옮기지 않는다. 예:
    rsync -a --delete --exclude 'keys/' <build 경로>/ ops@192.168.56.32:/home/ops/build/
  원본은 failstop 저장소의 harness/sql/summary.sql 이다."

  csv=$dir/invariants.csv
  # 한 스냅샷 안에서 전부 읽는다. 읽는 동안 값이 바뀌면 판정이 섞인다.
  {
    printf 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
    cat "$SUMMARY_SQL"
    printf '\nCOMMIT;\n'
  } | ( cd /tmp && sudo -u postgres "$PG_BIN/psql" -X -q --csv -v ON_ERROR_STOP=1 -d "$DB_NAME" ) > "$csv"

  python3 - "$csv" "$dir/result.json" <<'PY'
import csv, datetime as dt, json, sys

csv_path, out_path = sys.argv[1:3]
rows = list(csv.DictReader(open(csv_path, encoding='utf-8')))
problems = []
# summary.sql은 C01부터 C28까지를 낸다. 행이 모자라면 일부 판정이 사라진 것이다.
if len(rows) < 28 or any(r.get('verdict') not in ('PASS', 'FAIL', 'SKIP') for r in rows):
    problems.append('정합성 검사 결과 형식 오류')
failed = [r['code'] for r in rows
          if r.get('severity') in ('CRITICAL', 'HIGH') and r.get('verdict') != 'PASS']
if failed:
    problems.append('필수 불변식 실패: ' + ','.join(failed))
result = {
    'invariant_count': len(rows),
    'failed_required': failed,
    'status': 'PASS' if not problems else 'FAIL',
    'problems': problems,
    'completed_at': dt.datetime.now(dt.timezone.utc).isoformat(),
    'scope': '동일 스냅샷의 DB 정합성이다. 실제 장애의 RPO 보장이 아니다.',
}
json.dump(result, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False, indent=2))
if problems:
    raise SystemExit(1)
PY

  cat <<'EOF'

       불변식 판정과 별개로 외부 ACK 대조를 한다. 01-detect 에서 보존한 ACK 원본과
       승격한 DB의 명령 저널을 대조해 어디까지 반영됐는지 본다. 불일치가 남으면
       거래를 재개하지 않는다. 대조할 원자료가 없으면 유실량을 미확인으로 남긴다.
EOF
  local ack_result trade_result
  ack_result=$(ask_note 'ACK 대조 결과를 적는다. 대조한 건수, 불일치 건수, 처리 방침')
  trade_result=$(ask_note '시험 거래 결과를 적는다. 주문, 취소, 체결, 원장과 잔고 일치 여부')
  record "ack_reconciliation=$ack_result"
  record "test_trade=$trade_result"
  confirm 'INTEGRITY ok' '       CRITICAL 과 HIGH 실패가 없고 ACK 불일치가 해소됐는가.'

  log '정합성 판정을 통과했다'
  mark_step 08-verify
}

# ---------------------------------------------------------------------------
# 09-resume: 일반 거래 재개
# ---------------------------------------------------------------------------
step_09_resume() {
  log '09-resume: 일반 거래를 재개한다'
  need_root; need_host; detect_pg
  require_step 08-verify
  already_done 09-resume

  local dir started ended
  dir=$(run_dir)
  confirm 'RESUME trading' '       복구와 정합성 확인이 끝났다. 일반 거래 접수를 여는가.'

  started=$(cat "$dir/started_at_epoch")
  ended=$(date -u +%s)
  record "resumed_at=$(now)"
  record "detect_to_resume_seconds=$((ended - started))"

  psql_postgres -d "$DB_NAME" -c \
    'SELECT state, count(*) FROM pg_stat_activity WHERE client_addr IS NOT NULL GROUP BY 1;' \
    | sed 's/^/       /'

  cat <<'EOF'

       재개 뒤 관찰 항목이다. OPS01의 Grafana와 Prometheus에서 본다.
         오류율, 지연, DB 연결 수, WAL 생성량
       장애 발생부터 재개까지의 시각을 기록했다. 승격 명령 시간을 전체 복구 시간으로
       적지 않는다. 사람이 장애를 알아차린 시간과 판단 시간이 그 안에 들어 있다.
EOF
  mark_step 09-resume
  warn '다음은 10-rejoin 이다. 복제가 다시 붙기 전까지는 DB 한 대로 운영 중이다.'
}

# ---------------------------------------------------------------------------
# 10-rejoin: DB01 재편입
# ---------------------------------------------------------------------------
step_10_rejoin() {
  log '10-rejoin: DB01을 Standby로 다시 붙인다'
  need_root; need_host; detect_pg
  require_step 09-resume

  cat <<EOF

       아래는 DB01에서 실행한다. 이 스크립트는 DB01을 원격으로 바꾸지 않는다.
       prepare-db-recovery.py 의 rejoin-old 분기와 같은 순서다.

       1. 격리 상태를 유지한 채 기존 PGDATA를 rename 으로 보존한다. 지우지 않는다.
            sudo mv /var/lib/pgsql/data /var/lib/pgsql/data-before-recovery-<날짜>
       2. DB02의 공개 인증서를 DB01의 /etc/postgresql/primary.crt 로 받고 지문을 대조한다.
            이 호스트의 지문:
$(openssl x509 -in "$CONF_DIR/server.crt" -noout -fingerprint -sha256 | sed 's/^/              /')
       3. DB02를 원본으로 전량 베이스백업을 받는다. pg_rewind 는 시도해도 되지만
          실패를 기다리지 않는다. 되감기를 사는 WAL 보존 정책은 채택하지 않았다(ADR 0025).
            sudo -u postgres pg_basebackup \\
              -d 'host=$LAB_IP port=5432 user=$REPL_USER application_name=db01_rejoin
                  sslmode=verify-full sslrootcert=/etc/postgresql/primary.crt
                  passfile=/var/lib/pgsql/.pgpass' \\
              -D /var/lib/pgsql/data -Fp -Xs -P -R --no-password
            sudo -u postgres pg_verifybackup /var/lib/pgsql/data
       4. postgresql.auto.conf 에 primary_slot_name = '' 을 넣는다. 슬롯은 쓰지 않는다.
          pg_basebackup 도 -S 없이 받는다. DB02에는 만들어 둔 슬롯이 없다.
       5. unmask 후 기동하고 pg_is_in_recovery()=t 와 pg_stat_wal_receiver=streaming 을 본다.
       6. DB01의 방화벽을 Standby 역할로 되돌린다. 5432 accept 를 없앤다.
          /etc/vm-lab/firewall.d/30-db01.nft 를 Standby용 규칙으로 바꾸고
          nft --check --file /etc/vm-lab/firewall.nft 를 통과시킨 뒤 reload 한다.
       6-1. DB01의 pg_hba.conf 에 exchange_owner 임시 규칙이 남아 있지 않은지 본다.
          남아 있으면 30-db-primary-setup.sh 의 migrate-close 를 실행한다.
       7. OPS01의 Prometheus 대상 라벨에서 DB01과 DB02의 role 을 맞바꾼다.
          finish-db-recovery.py 가 하던 일과 같다. promtool check config 통과 뒤
          Prometheus만 재시작한다.

       복제 재개를 확인하기 전까지는 DB 한 대로 운영 중이다. 이 상태를 이중화 복구로
       적지 않는다.
EOF

  local replication
  replication=$(psql_value -c \
    "SELECT coalesce(string_agg(host(client_addr) || '/' || state || '/' || sync_state, ','), 'none')
     FROM pg_stat_replication;")
  record "pg_stat_replication=$replication"
  psql_postgres -c 'SELECT client_addr, application_name, state, sync_state FROM pg_stat_replication;' \
    | sed 's/^/       /'

  if [[ $replication == *"$PRIMARY_IP/streaming"* ]]; then
    record 'rejoin_confirmed=yes'
    log 'DB01이 Standby로 다시 붙었고 streaming 상태다'
    mark_step 10-rejoin
  else
    warn '아직 DB01의 복제 연결이 streaming 이 아니다. 재편입을 마친 뒤 이 단계를 다시 실행한다.'
    return 1
  fi
}

# ---------------------------------------------------------------------------
# status: 진행 상태
# ---------------------------------------------------------------------------
step_status() {
  local dir
  dir=$(run_dir)
  printf '실행 기록: %s\n\n' "$dir"
  printf '완료한 단계\n'
  sed 's/^/  /' "$dir/state"
  printf '\n기록\n'
  sed 's/^/  /' "$dir/evidence.log"
  printf '\n증거 파일\n'
  ls -la "$dir" | sed 's/^/  /'
}

usage() {
  cat <<EOF
사용법: sudo $0 <단계> [인수]

실제 장애 때 순서대로 실행한다. 각 단계는 앞 단계의 완료와 그 단계의 조건을
명령 출력으로 확인한다. 확인되지 않으면 다음으로 넘어가지 않는다.

  01-detect      장애 확인. 장애 시각과 외부 ACK 보존 위치를 기록한다
  02-hold        신규 접수 보류. 이 단계 전에 승격하지 않는다
  03-fence       DB01 격리 확인. C1부터 C4까지 모두 확인해야 통과한다
                   --db01-evidence <파일>   DB01에서 만든 격리 증거
                   --powered-off '<명령>'   DB01에 접근할 수 없어 전원을 내린 경우
                   --app-evidence <파일>    앱에서의 접속 거부 증거(항상 필요)
  04-lsn         receive 와 replay LSN 을 맞추고 값을 기록한다
  05-promote     pg_promote 실행. 되돌릴 수 없다
  06-open        pg_hba.conf 규칙 추가와 승격용 nft 파일 교체
  07-app-switch  DB02 인증서 지문 전달과 앱 접속 확인
  08-verify      한 스냅샷의 불변식 판정과 ACK 대조
  09-resume      일반 거래 재개와 소요 시간 기록
  10-rejoin      DB01 재편입 안내와 복제 재개 확인
  status         현재 절차의 진행 상태와 기록 출력

승격 여부의 판단 기준은 이 스크립트가 정하지 않는다. 일시적인 네트워크 흔들림에
승격하면 타임라인이 갈려 되돌리는 비용이 더 크다.
EOF
}

main() {
  local command=${1:-help}
  shift || true
  case "$command" in
    help|-h|--help) usage ;;
    01-detect)     step_01_detect ;;
    02-hold)       step_02_hold ;;
    03-fence)      step_03_fence "$@" ;;
    04-lsn)        step_04_lsn ;;
    05-promote)    step_05_promote ;;
    06-open)       step_06_open ;;
    07-app-switch) step_07_app_switch ;;
    08-verify)     step_08_verify ;;
    09-resume)     step_09_resume ;;
    10-rejoin)     step_10_rejoin ;;
    status)        step_status ;;
    *) usage; die "모르는 단계: $command" ;;
  esac
}

main "$@"
