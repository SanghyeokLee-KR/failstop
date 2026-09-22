#!/usr/bin/env bash
#
# DB02 Standby 구축 스크립트. Rocky Linux 10.2 minimal에 PostgreSQL 17을 직접 설치한다.
#
# 이 스크립트가 하는 일
#   데이터 디스크를 LVM으로 잘라 PGDATA와 백업 저장소를 나누고, PostgreSQL 17을 설치하고,
#   공통 설정과 노드 설정을 배치하고, TLS 인증서를 만들고, DB01에서 전량 베이스백업을 받아
#   비동기 스트리밍 복제를 붙이고, postgres_exporter와 격리 복원 시험 도구를 올린다.
#
# 이 스크립트가 하지 않는 일
#   initdb. Standby의 PGDATA는 pg_basebackup이 만든다. 초기화하면 안 된다.
#   DB01의 replicator 역할 생성과 pg_hba 한 줄 추가. 그것은 DB01의 몫이다.
#   create-monitor-role.sql 실행. 역할은 DB01에서만 만들고 WAL로 넘어온다.
#   승격. 그것은 32-db-failover.sh 가 사람의 확인을 받아 실행한다.
#   pfSense 구성.
#
# 공통 절차와의 관계
#   여섯 대 공통 설정은 scripts/00-common-setup.sh 가 맡는다. 그 절차를 먼저 적용해야 하며,
#   이 스크립트는 node_exporter와 journald 설정을 덮어쓰지 않는다. 방화벽은 공통 기반
#   table inet vmlab 한 계통만 쓰고 그 위에 /etc/vm-lab/firewall.d/31-db02.nft 로
#   역할 포트만 얹는다. 공통 기반이 없으면 멈춘다. 단독 테이블 경로는 없앴다.
#   DB01의 30-db-primary-setup.sh 와 같은 구조이며, 다른 점은 initdb 대신 베이스백업을
#   받는다는 것과 5432를 열지 않는다는 것이다.
#   복제 슬롯은 쓰지 않는다. pg_basebackup 을 -S 없이 받고 primary_slot_name 을 비운다.
#
# 실행 방법
#   sudo /opt/vm-lab/scripts/31-db-standby-setup.sh all 2>&1 | tee ~/db02-build.log
#   그다음 DB01과 맞추는 단계를 순서대로 실행한다.
#     sudo /opt/vm-lab/scripts/31-db-standby-setup.sh primary-cert /tmp/db01-server.crt
#     sudo /opt/vm-lab/scripts/31-db-standby-setup.sh confirm-primary <DB01 화면의 지문>
#     sudo /opt/vm-lab/scripts/31-db-standby-setup.sh passfile
#     sudo /opt/vm-lab/scripts/31-db-standby-setup.sh basebackup start monitor exporters verify
#
# 되돌리기
#   각 단계는 이미 끝난 일을 다시 하지 않는다. 데이터가 들어 있는 디스크나 내용이 있는
#   PGDATA를 발견하면 덮어쓰지 않고 멈춘다. 지우는 일은 사람이 판단한다.
#
# 주의
#   이 파일은 절차이며 검증 결과가 아니다. 여기 적힌 기대 상태는 직접 확인하기 전까지
#   통과로 적지 않는다. 복제 지연, RTO, RPO는 전부 미측정이다.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 고정 값. 00-주소계획.md의 값과 같아야 한다. 다르면 주소계획을 먼저 고친다.
# ---------------------------------------------------------------------------
NODE_NAME=db02
LAB_IP=192.168.10.32          # lab-lan. DB와 지표를 여는 주소
MGMT_IP=192.168.56.32         # host-only. SSH만 여는 주소
MGMT_CIDR=192.168.56.0/24
PRIMARY_IP=192.168.10.31      # DB01 lab-lan
PRIMARY_MGMT_IP=192.168.56.31 # DB01 관리 주소. 백업 아카이브가 이 경로로 온다
APP01_IP=192.168.10.21
APP02_IP=192.168.10.22
OPS01_IP=192.168.10.41

DB_NAME=exchange
REPL_USER=replicator
REPL_APPNAME=vm_standby       # DB01의 pg_stat_replication에 이 이름으로 보인다

# VirtualBox 의 SATA 포트 순서가 Linux 장치 이름과 일치하지 않고 VM 마다 다르다.
# 2026-09-22 확인: 같은 구성인데 DB01 은 OS 가 sdb, DB02 는 sda 로 잡혔다.
# 포트 번호나 이름으로 데이터 디스크를 추정하지 않는다. 루트가 올라탄 디스크를 찾아
# 제외하고 남은 디스크를 쓴다. lsblk -nso 는 LVM 을 거쳐 실제 디스크까지 내려간다.
# 이미 포맷된 디스크도 대상이다. '빈 디스크' 조건은 재실행에서 OS 디스크를 가리켰다.
detect_data_disk() {
  local rootsrc rootdisk d
  rootsrc=$(findmnt -no SOURCE / 2>/dev/null) || return 0
  rootdisk=$(lsblk -nso NAME -l "$rootsrc" 2>/dev/null | tail -1)
  [[ -n ${rootdisk:-} ]] || return 0
  while read -r d; do
    [[ $d == "$rootdisk" ]] && continue
    echo "/dev/$d"; return 0
  done < <(lsblk -dno NAME,TYPE | awk '$2=="disk"{print $1}')
}
DATA_DISK=${DATA_DISK:-$(detect_data_disk)}
[[ -n $DATA_DISK ]] || die '데이터 디스크를 찾지 못했다. lsblk 로 확인한 뒤 DATA_DISK 로 지정한다.'
VG_NAME=vg_pgdata
LV_DATA=lv_pgdata
LV_BACKUP=lv_pgbackup
LV_DATA_SIZE=12G              # 20GiB 디스크에서 PGDATA 몫
LV_BACKUP_SIZE=6G             # 백업 아카이브와 격리 복원 시험 몫. 나머지는 여유로 둔다
DATA_MOUNT=/var/lib/pgsql
BACKUP_MOUNT=/srv/pgbackup
CONF_DIR=/etc/postgresql
MARK_DIR=/etc/vm-lab

# failstop 저장소 deploy/linux/postgresql/exchange.conf 의 해시.
# 학교 EC2 적용본과 같은 값이며 파일이 바뀌면 여기서 멈춘다.
EXCHANGE_CONF_SHA256=8acbe28b479fa81c73ede1acfd978f1c232d2a85ed478287762a27ad90b603bd

# exporter 배포본. 해시는 docs/reports/AWS_작업.md 268~269행의 학교 기록값이다.
# 이번 VM에서 실제로 대조하는 것이 이 스크립트의 역할이며, 값이 다르면 멈춘다.
NODE_EXPORTER_VERSION=1.12.1
NODE_EXPORTER_SHA256=b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63
PG_EXPORTER_VERSION=0.17.1
PG_EXPORTER_SHA256=6da7d2edafd69ecceb08addec876786fa609849f6d5f903987c0d61c3fc89506

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BUILD_ROOT=${BUILD_ROOT:-$(cd -- "$SCRIPT_DIR/.." && pwd)}
FILES_DIR=${FILES_DIR:-$BUILD_ROOT/build-files/db}
DOWNLOAD_DIR=${DOWNLOAD_DIR:-$BUILD_ROOT/downloads}

# ---------------------------------------------------------------------------
# 공통 함수. DB01 스크립트와 같은 이름과 같은 출력 모양을 쓴다.
# ---------------------------------------------------------------------------
log()  { printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
info() { printf '       %s\n' "$*"; }
warn() { printf '  [확인 필요] %s\n' "$*" >&2; }
die()  { printf '\n  [중단] %s\n' "$*" >&2; exit 1; }

need_root() { [[ ${EUID} -eq 0 ]] || die '이 스크립트는 sudo로 실행한다.'; }

need_file() {
  [[ -f $1 ]] || die "필요한 파일이 없다: $1
  작업 PC의 build 폴더에서 keys/ 를 뺀 나머지를 /opt/vm-lab 로 복사했는지 확인한다.
  관리 개인키는 VM으로 옮기지 않는다. 예:
    rsync -a --delete --exclude 'keys/' <build 경로>/ ops@192.168.56.32:/home/ops/build/
  build-files/db 안의 파일은 failstop 저장소 deploy/linux 의 사본이다."
}

# 파일 하나의 해시를 대조한다. 값이 다르면 멈춘다.
verify_sha256() {
  local path=$1 expected=$2
  printf '%s  %s\n' "$expected" "$path" | sha256sum -c - \
    || die "$path 의 SHA-256이 기대값과 다르다. 그대로 쓰지 않는다."
}

# dnf 패키지가 아닌 배포본을 받아 해시를 대조한다. DB01 스크립트와 같은 이름,
# 같은 다운로드 폴더를 쓰므로 두 노드가 같은 파일을 나눠 쓸 수 있다.
fetch_tarball() {
  local url=$1 file=$2 sha=$3
  install -d -m 0755 "$DOWNLOAD_DIR"
  if [[ -f "$DOWNLOAD_DIR/$file" ]]; then
    info "$file 은 이미 내려받았다."
  else
    info "$file 을 내려받는다. NAT 경로가 필요하다."
    curl -fsSL --retry 3 --connect-timeout 10 -o "$DOWNLOAD_DIR/$file" "$url" \
      || die "내려받기에 실패했다: $url
  NAT 인터페이스와 DNS를 확인한다. 외부 접속이 막혀 있으면 작업 PC에서 받아
  $DOWNLOAD_DIR 에 옮긴 뒤 다시 실행한다."
  fi
  # 해시를 대조한다. 값이 다르면 설치하지 않는다.
  printf '%s  %s\n' "$sha" "$DOWNLOAD_DIR/$file" | sha256sum -c - \
    || die "$file 의 SHA-256이 기대값과 다르다. 설치하지 않는다."
}

# postgres 계정으로 SQL을 실행한다. /root에서 실행할 때 나오는 디렉터리 경고를
# 없애려고 /tmp로 이동한 뒤 실행한다.
psql_postgres() { ( cd /tmp && sudo -u postgres psql -X -v ON_ERROR_STOP=1 -P pager=off "$@" ); }

# 값 하나만 받을 때 쓴다.
psql_value() { psql_postgres -qAt "$@"; }

# 설치한 패키지가 정한 유닛 이름과 PGDATA를 실제로 확인한다.
# 학교 EC2(Amazon Linux 2023)는 postgresql.service + /var/lib/pgsql/data 였고
# PGDG 패키지는 postgresql-17.service + /var/lib/pgsql/17/data 다. 추측하지 않는다.
PG_UNIT=${PG_UNIT:-}
PGDATA=${PGDATA:-}
PG_BIN=${PG_BIN:-}
detect_pg() {
  if [[ -z $PG_UNIT ]]; then
    PG_UNIT=$(systemctl list-unit-files --type=service --no-legend 'postgresql*.service' 2>/dev/null \
      | awk '{print $1}' | grep -v '@' | head -n1 || true)
  fi
  [[ -n $PG_UNIT ]] || die 'postgresql 서비스 유닛을 찾지 못했다. packages 단계를 먼저 실행한다.'

  if [[ -z $PGDATA ]]; then
    PGDATA=$(systemctl show -p Environment --value "$PG_UNIT" 2>/dev/null \
      | tr ' ' '\n' | sed -n 's/^PGDATA=//p' | head -n1 || true)
  fi
  [[ -n $PGDATA ]] || die "유닛 $PG_UNIT 에서 PGDATA를 읽지 못했다. systemctl cat $PG_UNIT 로 직접 확인한 뒤 PGDATA=... 를 앞에 붙여 실행한다."

  if [[ -z $PG_BIN ]]; then
    if [[ -x /usr/pgsql-17/bin/pg_basebackup ]]; then
      PG_BIN=/usr/pgsql-17/bin
    elif command -v pg_basebackup >/dev/null 2>&1; then
      PG_BIN=$(dirname -- "$(command -v pg_basebackup)")
    else
      die 'pg_basebackup을 찾지 못했다. postgresql17-server 설치를 확인한다.'
    fi
  fi
}

show_pg_paths() {
  detect_pg
  info "유닛   : $PG_UNIT"
  info "PGDATA : $PGDATA"
  info "실행파일: $PG_BIN"
}

# 공통 기반(00-common-setup.sh)을 적용한 VM인지 본다. 방화벽과 exporter 단계가 갈린다.
common_base_present() {
  [[ -f /etc/vm-lab/firewall.nft ]] \
    && systemctl list-unit-files vm-lab-firewall.service >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# preflight: 전제 확인. 값이 다르면 여기서 멈추는 편이 낫다.
# ---------------------------------------------------------------------------
step_preflight() {
  log 'preflight: 설치 전제를 확인한다'
  need_root

  if [[ -r /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    info "OS: ${PRETTY_NAME:-unknown}"
    [[ ${ID:-} == rocky ]] || warn "Rocky Linux가 아니다(ID=${ID:-없음}). 패키지 이름과 저장소를 다시 확인한다."
  fi

  local host
  host=$(hostnamectl --static 2>/dev/null || hostname)
  info "호스트 이름: $host"
  # 엉뚱한 VM에서 실행하면 복제 방향이 뒤집힌다. 이름과 주소를 모두 본다.
  [[ $host == ${NODE_NAME}* ]] || die "호스트 이름이 ${NODE_NAME}로 시작하지 않는다. DB01에서 실행하고 있지 않은지 확인한다."

  local addr
  for addr in "$LAB_IP" "$MGMT_IP"; do
    if ip -brief -4 addr show | grep -qw "$addr/24"; then
      info "주소 확인: $addr"
    else
      die "주소 $addr 가 붙어 있지 않다. 이 VM이 DB02가 맞는지 확인한다."
    fi
  done
  info '현재 인터페이스'
  ip -brief -4 addr show | sed 's/^/       /'

  ip link show enp0s3 >/dev/null 2>&1 || warn 'enp0s3이 없다. NAT 인터페이스 이름을 확인하고 nft 규칙의 DHCP 줄을 맞춘다.'

  local mode
  mode=$(getenforce 2>/dev/null || echo unknown)
  info "SELinux: $mode"
  [[ $mode == Enforcing ]] || warn 'SELinux가 Enforcing이 아니다. 끄지 말고 원인을 확인한다.'

  if [[ -b $DATA_DISK ]]; then
    info "데이터 디스크 $DATA_DISK 확인"
  else
    warn "$DATA_DISK 가 없다. VM 설정에서 두 번째 가상 디스크를 붙였는지 확인한다."
  fi

  if locale -a 2>/dev/null | grep -qiE '^C\.utf-?8$'; then
    info '로케일 C.UTF-8 확인'
  else
    warn 'C.UTF-8 로케일을 찾지 못했다. 복원 시험의 initdb 옵션을 바꾸기 전에 원인을 확인한다.'
  fi

  info '시간 동기화 상태'
  timedatectl 2>/dev/null | sed 's/^/       /' || true

  # DB01이 먼저 서 있어야 한다. 여기서 닿지 않으면 베이스백업 단계에서 막힌다.
  if command -v ncat >/dev/null 2>&1; then
    if ncat -z -w 3 "$PRIMARY_IP" 5432 2>/dev/null; then
      info "DB01 $PRIMARY_IP:5432 에 닿는다"
    else
      warn "DB01 $PRIMARY_IP:5432 에 닿지 않는다. DB01 구축과 pg_hba 한 줄 추가를 먼저 끝낸다."
    fi
  fi

  # 방화벽은 공통 기반 한 계통만 쓴다. 기반이 없으면 firewall 단계에서 어차피 멈추므로
  # 여기서 먼저 알린다.
  if common_base_present; then
    info '공통 기반(table inet vmlab)을 찾았다. 역할 규칙만 얹는다.'
  else
    die '공통 기반(/etc/vm-lab/firewall.nft, vm-lab-firewall.service)이 없다.
  먼저 sudo /opt/vm-lab/scripts/00-common-setup.sh 를 적용한다.
  이 스크립트는 별도 테이블을 세우지 않는다.'
  fi

  [[ -d $FILES_DIR ]] || die "설정 원본 폴더가 없다: $FILES_DIR"
  info "설정 원본 폴더: $FILES_DIR"
  local f
  for f in exchange.conf db02-node.conf pg-queries.yaml postgres_exporter.service \
           node_exporter-host.service 60-exchange-lab.conf \
           db02-firewall.d.nft db02-firewall-promoted.d.nft \
           db02-restore-check.sh exchange-backup-verify.service \
           summary.sql; do
    need_file "$FILES_DIR/$f"
  done
  info '필요한 원본 파일을 모두 찾았다.'
}

# ---------------------------------------------------------------------------
# disk: 데이터 디스크를 LVM으로 나눠 PGDATA와 백업 저장소를 따로 마운트한다.
# 파일시스템을 나누는 이유는 백업 아카이브와 복원 시험이 DB 볼륨을 채우지 못하게
# 하기 위해서다. DB01은 파티션 하나만 쓰지만 DB02는 백업 저장소를 함께 맡는다.
# ---------------------------------------------------------------------------
step_disk() {
  log "disk: $DATA_DISK 를 $DATA_MOUNT 와 $BACKUP_MOUNT 로 준비한다"
  need_root
  [[ -b $DATA_DISK ]] || die "$DATA_DISK 가 블록 장치가 아니다."

  if vgs "$VG_NAME" >/dev/null 2>&1; then
    info "볼륨 그룹 $VG_NAME 이 이미 있다. 새로 만들지 않는다."
  else
    # 빈 디스크가 아니면 멈춘다. 다른 자료가 들어 있는 디스크를 지우지 않는다.
    if blkid -p "$DATA_DISK" >/dev/null 2>&1; then
      die "$DATA_DISK 에 이미 서명이 있다. 내용을 확인한 뒤 사람이 판단해서 지운다."
    fi
    if [[ -n $(lsblk -nro NAME "$DATA_DISK" | tail -n +2) ]]; then
      die "$DATA_DISK 에 파티션이 있다. 대상 디스크가 맞는지 확인한다."
    fi
    info '물리 볼륨과 볼륨 그룹을 만든다'
    pvcreate "$DATA_DISK"
    vgcreate "$VG_NAME" "$DATA_DISK"
    udevadm settle
  fi

  local lv
  for lv in "$LV_DATA:$LV_DATA_SIZE" "$LV_BACKUP:$LV_BACKUP_SIZE"; do
    local name=${lv%%:*} size=${lv##*:}
    if lvs "$VG_NAME/$name" >/dev/null 2>&1; then
      info "논리 볼륨 $name 이 이미 있다."
    else
      info "논리 볼륨 $name ($size) 을 만든다"
      lvcreate -L "$size" -n "$name" "$VG_NAME"
    fi
  done
  udevadm settle

  local pair dev mount fstype uuid
  for pair in "/dev/$VG_NAME/$LV_DATA:$DATA_MOUNT" "/dev/$VG_NAME/$LV_BACKUP:$BACKUP_MOUNT"; do
    dev=${pair%%:*}
    mount=${pair##*:}

    if findmnt -n "$mount" >/dev/null 2>&1; then
      info "$mount 는 이미 마운트되어 있다."
      findmnt "$mount" | sed 's/^/       /'
      continue
    fi

    fstype=$(blkid -o value -s TYPE "$dev" 2>/dev/null || true)
    if [[ -z $fstype ]]; then
      info "$dev 를 xfs로 포맷한다"
      mkfs.xfs "$dev"
      udevadm settle
    elif [[ $fstype == xfs ]]; then
      info "$dev 는 이미 xfs다. 포맷하지 않는다."
    else
      die "$dev 의 파일시스템이 $fstype 다. 내용을 확인한 뒤 사람이 판단한다."
    fi

    uuid=$(blkid -o value -s UUID "$dev" 2>/dev/null || true)
    [[ -n $uuid ]] || die "$dev 의 UUID를 읽지 못했다. lsblk -f 로 상태를 확인한다."
    info "UUID=$uuid -> $mount"

    install -d -m 0700 "$mount"
    if grep -q "UUID=$uuid" /etc/fstab; then
      info 'fstab에 이미 등록되어 있다.'
    else
      cp -a /etc/fstab "/etc/fstab.bak-$(date '+%Y%m%d%H%M%S')"
      printf 'UUID=%s  %s  xfs  defaults,noatime,nofail,x-systemd.device-timeout=10s  0 0\n' "$uuid" "$mount" >> /etc/fstab
      info 'fstab에 추가했다. 원본은 /etc/fstab.bak-* 로 보관한다.'
    fi
  done

  systemctl daemon-reload

  # fstab에 nofail을 넣어 마운트가 없어도 부팅은 되게 했다. 대신 PostgreSQL이 빈 경로 위에서
  # 뜨면 안 되므로 유닛이 이 마운트를 요구하게 한다. 마운트가 없으면 서비스만 멈춘다.
  # detect_pg 는 유닛이 없으면 die 한다. 이 단계는 패키지 설치보다 앞이라 없는 것이 정상이다.
  # 그래서 직접 조회하고, 없으면 packages 단계 뒤에 다시 걸도록 안내만 남긴다.
  local pgunit
  # 매칭이 없으면 systemctl 이 exit 1 을 낸다. set -e 에 걸리지 않게 || true 를 붙인다.
  pgunit=$(systemctl list-unit-files --type=service --no-legend 'postgresql*.service' 2>/dev/null | awk '{print $1}' | head -1 || true)
  if [[ -n $pgunit ]]; then
    PG_UNIT=$pgunit
    install -d -m 0755 "/etc/systemd/system/${PG_UNIT}.d"
    {
      printf '[Unit]
'
      printf '# %s 마운트가 없으면 기동하지 않는다. 빈 경로 위에서 PGDATA를 여는 사고를 막는다.
' "$DATA_MOUNT"
      printf 'RequiresMountsFor=%s
' "$DATA_MOUNT"
    } > "/etc/systemd/system/${PG_UNIT}.d/10-requires-mount.conf"
    chmod 0644 "/etc/systemd/system/${PG_UNIT}.d/10-requires-mount.conf"
    systemctl daemon-reload
    info "${PG_UNIT} 에 RequiresMountsFor=${DATA_MOUNT} 를 걸었다."
  else
    info 'PostgreSQL 유닛이 아직 없다. packages 단계 뒤에 mount-guard 로 다시 건다.'
  fi
  mount -a
  findmnt "$DATA_MOUNT" | sed 's/^/       /'
  findmnt "$BACKUP_MOUNT" | sed 's/^/       /'
  lsblk -o NAME,TYPE,SIZE,FSTYPE,MOUNTPOINTS | sed 's/^/       /'
  df -h "$DATA_MOUNT" "$BACKUP_MOUNT" | sed 's/^/       /'

  info 'fstab 옵션에 nofail을 넣었다. 디스크가 없어도 부팅은 되고 PostgreSQL 유닛이 마운트를 요구해 멈춘다.'
  info 'postgres 계정이 아직 없으면 소유권과 문맥은 packages 단계에서 맞춘다.'
}

# ---------------------------------------------------------------------------
# packages: 기본 패키지와 PostgreSQL 17. initdb는 하지 않는다.
# ---------------------------------------------------------------------------
step_packages() {
  log 'packages: 기본 패키지와 PostgreSQL 17을 설치한다'
  need_root

  info '기본 패키지'
  dnf install -y \
    nftables policycoreutils-python-utils chrony openssh-server iproute tar \
    ca-certificates rsync python3 logrotate sysstat \
    lvm2 parted xfsprogs util-linux openssl nmap-ncat bind-utils

  # RHEL 10 계열에는 dnf 모듈 기능이 없다. 있는 경우에만 기본 postgresql 모듈을 끈다.
  if dnf module list postgresql >/dev/null 2>&1; then
    info '기본 postgresql 모듈을 비활성화한다'
    dnf -qy module disable postgresql || warn '모듈 비활성화에 실패했다. 출력을 확인한다.'
  else
    info 'dnf 모듈 기능이 없거나 postgresql 모듈이 없다. 건너뛴다.'
  fi

  if ! rpm -q pgdg-redhat-repo >/dev/null 2>&1; then
    info 'PGDG 저장소를 등록한다 (EL-10 x86_64)'
    dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-10-x86_64/pgdg-redhat-repo-latest.noarch.rpm \
      || die 'PGDG EL-10 저장소 등록에 실패했다.
  Rocky 10용 채널이 아직 없을 수 있다. DB01이 무엇으로 설치했는지 먼저 확인하고
  DB02도 같은 저장소, 같은 메이저 버전을 쓴다. 메이저가 다르면 복제가 성립하지 않는다.'
  else
    info 'PGDG 저장소가 이미 등록되어 있다.'
  fi

  # 저장소를 붙인 직후에는 GPG 키가 없어 조회 자체가 실패한다. 그러면 아래 확인이
  # '저장소 채널을 확인한다'로 멈춰 저장소 문제로 오해하게 된다. 키를 먼저 넣는다.
  if [[ -f /etc/pki/rpm-gpg/PGDG-RPM-GPG-KEY-RHEL ]]; then
    rpm --import /etc/pki/rpm-gpg/PGDG-RPM-GPG-KEY-RHEL 2>/dev/null || true
    dnf -q makecache >/dev/null 2>&1 || true
    info 'PGDG GPG 키를 등록했다.'
  fi
  info '설치 가능 여부를 먼저 확인한다'
  dnf list --available postgresql17-server >/dev/null 2>&1 || rpm -q postgresql17-server >/dev/null 2>&1 \
    || die 'postgresql17-server 를 저장소에서 찾지 못했다. 저장소 채널을 확인한다.'

  dnf install -y postgresql17-server postgresql17-contrib

  detect_pg
  show_pg_paths
  "$PG_BIN/postgres" --version | sed 's/^/       /'
  warn 'DB01에서 SELECT version(); 을 실행해 마이너 버전까지 같은지 눈으로 대조한다.'

  # 패키지가 postgres 계정을 만든 뒤 데이터 디스크의 소유권과 문맥을 맞춘다.
  local mount
  for mount in "$DATA_MOUNT" "$BACKUP_MOUNT"; do
    if findmnt -n "$mount" >/dev/null 2>&1; then
      info "$mount 소유권과 SELinux 문맥을 맞춘다"
      chown postgres:postgres "$mount"
      chmod 0700 "$mount"
    else
      warn "$mount 가 마운트되어 있지 않다. disk 단계를 먼저 실행한다."
    fi
  done

  # /var/lib/pgsql 은 기본 문맥 규칙이 postgresql_db_t 다. 그래서 마운트 지점으로 골랐다.
  # /srv/pgbackup 은 기본값이 다르므로 규칙을 직접 추가한다. SELinux는 끄지 않는다.
  if command -v semanage >/dev/null 2>&1; then
    semanage fcontext -a -t postgresql_db_t "${BACKUP_MOUNT}(/.*)?" 2>/dev/null \
      || semanage fcontext -m -t postgresql_db_t "${BACKUP_MOUNT}(/.*)?"
    info "$BACKUP_MOUNT 에 postgresql_db_t 문맥 규칙을 등록했다"
  fi
  restorecon -R "$DATA_MOUNT" "$BACKUP_MOUNT" 2>/dev/null || true
  ls -ldZ "$DATA_MOUNT" "$BACKUP_MOUNT" | sed 's/^/       /'

  info 'Standby에서는 initdb를 실행하지 않는다. PGDATA는 basebackup 단계가 만든다.'
}

# ---------------------------------------------------------------------------
# config: 공통 설정과 노드 설정을 배치한다.
# ---------------------------------------------------------------------------
step_config() {
  log 'config: exchange.conf 와 node.conf 를 배치한다'
  need_root
  need_file "$FILES_DIR/exchange.conf"
  need_file "$FILES_DIR/db02-node.conf"
  verify_sha256 "$FILES_DIR/exchange.conf" "$EXCHANGE_CONF_SHA256"

  install -d -o root -g postgres -m 0750 "$CONF_DIR"
  install -o root -g postgres -m 0640 "$FILES_DIR/exchange.conf" "$CONF_DIR/exchange.conf"
  install -o root -g postgres -m 0640 "$FILES_DIR/db02-node.conf" "$CONF_DIR/node.conf"
  restorecon -R "$CONF_DIR" 2>/dev/null || true

  sha256sum "$CONF_DIR/exchange.conf" | sed 's/^/       /'
  stat -c '%U %G %a %n' "$CONF_DIR/exchange.conf" "$CONF_DIR/node.conf" | sed 's/^/       /'
  info 'node.conf 를 PGDATA 밖에 두었다. 베이스백업이 DB01의 주소로 덮어쓰지 않는다.'
}

# ---------------------------------------------------------------------------
# tls: 이 노드의 서버 인증서와 키.
# ---------------------------------------------------------------------------
step_tls() {
  log 'tls: DB02 서버 인증서를 만든다'
  need_root
  install -d -o root -g postgres -m 0750 "$CONF_DIR"

  if [[ -f $CONF_DIR/server.key ]]; then
    info '이미 server.key 가 있다. 새로 만들지 않는다.'
  else
    # 승격 뒤 앱이 sslmode=verify-full 로 검증할 인증서다. SAN에 lab-lan 주소를 넣는다.
    # 앱은 이름이 아니라 IP로 붙으므로 IP SAN이 없으면 검증에 실패한다.
    ( umask 0077
      openssl req -x509 -newkey rsa:3072 -nodes -days 365 \
        -subj "/CN=$NODE_NAME" \
        -addext "subjectAltName=IP:$LAB_IP,DNS:$NODE_NAME.lab,DNS:$NODE_NAME" \
        -keyout "$CONF_DIR/server.key" -out "$CONF_DIR/server.crt" )
    chown postgres:postgres "$CONF_DIR/server.key" "$CONF_DIR/server.crt"
    chmod 0600 "$CONF_DIR/server.key"
    chmod 0644 "$CONF_DIR/server.crt"
    restorecon "$CONF_DIR/server.key" "$CONF_DIR/server.crt" 2>/dev/null || true
  fi

  info 'DB02 서버 인증서 지문. 승격할 때 APP01과 APP02에 전달할 값이다.'
  openssl x509 -in "$CONF_DIR/server.crt" -noout -subject -dates -ext subjectAltName | sed 's/^/       /'
  openssl x509 -in "$CONF_DIR/server.crt" -noout -fingerprint -sha256 | sed 's/^/       /'
  info 'server.key 는 postgres:postgres 0600이며 이 호스트 밖으로 복사하지 않는다.'
}

# ---------------------------------------------------------------------------
# primary-cert: DB01의 공개 인증서를 받아 두고 지문을 출력한다.
# ---------------------------------------------------------------------------
step_primary_cert() {
  log 'primary-cert: DB01 공개 인증서를 배치한다'
  need_root
  local source=${1:-}
  [[ -n $source && -f $source ]] || die '사용법: 31-db-standby-setup.sh primary-cert <DB01에서 받은 server.crt 경로>'

  install -d -o root -g postgres -m 0750 "$CONF_DIR"
  install -o root -g postgres -m 0644 "$source" "$CONF_DIR/primary.crt"
  restorecon "$CONF_DIR/primary.crt" 2>/dev/null || true

  info '이 파일의 지문'
  openssl x509 -in "$CONF_DIR/primary.crt" -noout -subject -dates | sed 's/^/       /'
  openssl x509 -in "$CONF_DIR/primary.crt" -noout -fingerprint -sha256 | sed 's/^/       /'
  cat <<'EOF'

       DB01 화면에서 아래를 실행해 나온 값과 눈으로 대조한다.
         openssl x509 -in /etc/postgresql/server.crt -noout -fingerprint -sha256
       일치하면 다음을 실행한다.
         sudo 31-db-standby-setup.sh confirm-primary <DB01 화면에서 읽은 지문>
       전송 자체를 신뢰 근거로 삼지 않는다. 대조가 끝나야 베이스백업 단계가 열린다.
EOF
}

step_confirm_primary() {
  log 'confirm-primary: DB01 인증서 지문을 대조한다'
  need_root
  local given=${1:-}
  [[ -n $given ]] || die '사용법: 31-db-standby-setup.sh confirm-primary <DB01 화면에서 읽은 SHA256 지문>'
  [[ -f $CONF_DIR/primary.crt ]] || die 'primary.crt 가 없다. primary-cert 단계를 먼저 실행한다.'

  local actual normalized
  actual=$(openssl x509 -in "$CONF_DIR/primary.crt" -noout -fingerprint -sha256 | sed 's/.*=//')
  # 입력의 접두사, 콜론, 대소문자 차이를 없애고 비교한다.
  normalized=$(printf '%s' "${given#*=}" | tr -d ':' | tr 'a-f' 'A-F')
  [[ $(printf '%s' "$actual" | tr -d ':') == "$normalized" ]] \
    || die "지문이 일치하지 않는다. 전달 경로를 다시 확인한다.
  이 파일 $actual"

  install -d -o root -g root -m 0755 "$MARK_DIR"
  printf 'verified_at=%s\nfingerprint=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$actual" \
    > "$MARK_DIR/db02-primary-cert-verified"
  chmod 0644 "$MARK_DIR/db02-primary-cert-verified"
  info '대조를 마쳤다. 이제 passfile 단계로 넘어간다.'
}

# ---------------------------------------------------------------------------
# passfile: 복제 비밀번호를 받아 .pgpass 에 넣는다. 화면에 표시하지 않는다.
# failstop 저장소 fix-replication-passfile.py 의 작성 방식을 그대로 쓴다.
# umask 0077, 콜론과 역슬래시 이스케이프, postgres:postgres 0600 이다.
# ---------------------------------------------------------------------------
step_passfile() {
  log 'passfile: 복제 비밀번호를 넣는다'
  need_root
  id postgres >/dev/null 2>&1 || die 'postgres 계정이 없다. packages 단계를 먼저 실행한다.'
  [[ -t 0 || -e /dev/tty ]] || die '대화형 입력이 필요하다. 터미널에서 직접 실행한다.'

  local target=$DATA_MOUNT/.pgpass first second escaped
  if [[ -f $target ]]; then
    warn "$target 이 이미 있다. 덮어쓰면 기존 복제 자격증명이 사라진다."
    printf '       덮어쓰려면 overwrite 를 입력한다: '
    local answer
    IFS= read -r answer < /dev/tty
    [[ $answer == overwrite ]] || die '덮어쓰지 않고 멈춘다.'
  fi

  printf '       DB01에서 만든 %s 역할의 비밀번호를 입력한다. 화면에 표시되지 않는다.\n' "$REPL_USER"
  printf '       비밀번호: '
  IFS= read -r -s first < /dev/tty; printf '\n'
  printf '       다시 입력: '
  IFS= read -r -s second < /dev/tty; printf '\n'
  [[ -n $first ]] || die '빈 비밀번호는 쓰지 않는다.'
  [[ $first == "$second" ]] || die '두 입력이 다르다.'

  # libpq는 필드 구분자 콜론과 이스케이프 문자 역슬래시를 그대로 해석한다.
  escaped=${first//\\/\\\\}
  escaped=${escaped//:/\\:}
  ( umask 0077
    printf '%s:5432:replication:%s:%s\n' "$PRIMARY_IP" "$REPL_USER" "$escaped" > "$target" )
  unset first second escaped
  chown postgres:postgres "$target"
  chmod 0600 "$target"
  restorecon "$target" 2>/dev/null || true

  stat -c '%U %G %a %n' "$target" | sed 's/^/       /'
  info '파일을 만들었다. 내용은 출력하지 않는다. 문서와 캡처에도 남기지 않는다.'
}

# ---------------------------------------------------------------------------
# basebackup: DB01에서 전량 베이스백업을 받는다. 되돌리기 어려운 단계다.
# ---------------------------------------------------------------------------
step_basebackup() {
  log 'basebackup: DB01에서 전량 베이스백업을 받는다'
  need_root
  detect_pg

  [[ -f $MARK_DIR/db02-primary-cert-verified ]] \
    || die 'DB01 인증서 지문 대조가 끝나지 않았다. primary-cert 와 confirm-primary 를 먼저 실행한다.'
  [[ -f $CONF_DIR/primary.crt ]] || die "$CONF_DIR/primary.crt 가 없다."
  [[ -f $DATA_MOUNT/.pgpass ]] || die 'passfile 이 없다. passfile 단계를 먼저 실행한다.'
  [[ -f $CONF_DIR/server.crt && -f $CONF_DIR/server.key ]] || die 'tls 단계를 먼저 실행한다.'
  [[ -f $CONF_DIR/exchange.conf && -f $CONF_DIR/node.conf ]] || die 'config 단계를 먼저 실행한다.'
  findmnt -n "$DATA_MOUNT" >/dev/null 2>&1 || die "$DATA_MOUNT 가 마운트되어 있지 않다."

  local state
  state=$(systemctl show "$PG_UNIT" -p ActiveState --value || true)
  [[ $state == inactive ]] || die "$PG_UNIT 가 $state 다. 베이스백업 전에 중지한다."

  # 기존 PGDATA를 덮어쓰지 않는다. 다시 받으려면 사람이 먼저 옮긴다.
  if [[ -e $PGDATA ]]; then
    [[ ! -L $PGDATA ]] || die "$PGDATA 가 심볼릭 링크다. 구성을 확인한다."
    if [[ -n $(ls -A "$PGDATA" 2>/dev/null) ]]; then
      die "$PGDATA 에 내용이 있다. 기존 데이터를 확인하고 옮긴 뒤 다시 실행한다.
  이 스크립트는 데이터 디렉터리를 지우지 않는다."
    fi
  fi
  install -d -o postgres -g postgres -m 0700 "$PGDATA"

  # 슬롯은 쓰지 않는다. -S 를 붙이지 않는 것이 확정한 구성이며 DB01도 슬롯을 만들지 않는다.
  # 슬롯이 없으면 DB02가 오래 멈췄을 때 DB01이 WAL을 재활용해 복제가 끊길 수 있고
  # 그때는 전량 베이스백업을 다시 받는다. 반대로 DB02 장애가 DB01의 디스크를 채우지 않는다.
  # DB01의 pg_replication_slots 는 비어 있어야 한다. 행이 있으면 어디서 만들었는지 확인한다.
  local conn="host=$PRIMARY_IP port=5432 user=$REPL_USER application_name=$REPL_APPNAME"
  conn+=" sslmode=verify-full sslrootcert=$CONF_DIR/primary.crt passfile=$DATA_MOUNT/.pgpass"
  info '연결 문자열에 비밀번호 본문을 넣지 않는다. passfile 로만 전달한다.'
  info "대상 $PRIMARY_IP:5432, 수신 경로 $PGDATA"

  sudo -u postgres "$PG_BIN/pg_basebackup" -d "$conn" -D "$PGDATA" -Fp -Xs -P -R --no-password
  info '받은 내용을 manifest로 검증한다'
  sudo -u postgres "$PG_BIN/pg_verifybackup" "$PGDATA"

  [[ -f $PGDATA/standby.signal ]] || die 'standby.signal 이 없다. Standby로 받지 못했다.'
  local auto=$PGDATA/postgresql.auto.conf
  grep -q 'passfile=' "$auto" || die 'primary_conninfo 에 passfile 이 없다.'
  if grep -q 'password=' "$auto"; then
    die 'primary_conninfo 에 비밀번호 본문이 들어갔다. 즉시 확인하고 다시 만든다.'
  fi
  # 승격 전 DB01의 슬롯을 참조하지 않게 빈 값을 명시한다.
  if grep -q '^primary_slot_name' "$auto"; then
    info 'postgresql.auto.conf 에 primary_slot_name 이 이미 있다.'
  else
    printf "primary_slot_name = ''\n" >> "$auto"
    info "primary_slot_name = '' 를 명시했다."
  fi
  if grep -q '^listen_addresses' "$auto"; then
    warn 'postgresql.auto.conf 에 listen_addresses 가 있다. node.conf 보다 뒤에 적용되므로 값을 확인한다.'
  fi
  # DB01의 postgresql.conf 를 그대로 받았으므로 include 줄이 이미 있어야 한다.
  if grep -q "include = '$CONF_DIR/exchange.conf'" "$PGDATA/postgresql.conf"; then
    info '공통 설정 include 를 확인했다.'
  else
    warn '공통 설정 include 가 없어 추가한다. DB01의 구성과 다른지 확인한다.'
    printf "\n# 공통 설정. 파일 본문은 failstop 저장소 deploy/linux/postgresql/exchange.conf 와 같다.\ninclude = '%s/exchange.conf'\n" \
      "$CONF_DIR" >> "$PGDATA/postgresql.conf"
  fi
  # pg_basebackup 은 DB01의 pg_hba.conf 도 그대로 가져온다. 따라서 이 호스트의
  # pg_hba.conf 에는 DB01용 exchange_app 규칙이 이미 들어 있다. 승격 전 DB02의
  # 5432를 막는 것은 nftables 한 겹뿐이다. "방화벽과 pg_hba 두 겹"은 DB01에서만
  # 성립한다. 이 사실을 가리지 않는다.
  info '복사된 pg_hba.conf 의 유효 규칙'
  grep -vE '^\s*(#|$)' "$PGDATA/pg_hba.conf" | sed 's/^/       /' || true
  info '위 규칙에 앱 접속 줄이 보이는 것이 정상이다. DB01의 파일을 그대로 받았기 때문이다.'
  info '승격 전 DB02의 5432는 nftables 한 겹으로만 막는다. 그 한 겹을 확인하는 것이 verify 의 5432 항목이다.'

  chown postgres:postgres "$auto" "$PGDATA/postgresql.conf"
  restorecon -R "$PGDATA" 2>/dev/null || true

  stat -c '%U %G %a %n' "$PGDATA" | sed 's/^/       /'
  ls -Z "$PGDATA" | head -n 5 | sed 's/^/       /'
  info '베이스백업과 manifest 검증을 마쳤다.'
}

# ---------------------------------------------------------------------------
# start: 기동하고 복제가 실제로 붙는지 본다.
# ---------------------------------------------------------------------------
step_start() {
  log 'start: PostgreSQL을 기동하고 복제를 확인한다'
  need_root
  detect_pg
  [[ -f $PGDATA/standby.signal ]] || die 'standby.signal 이 없다. 이 호스트는 Standby가 아니다.'

  systemctl enable --now "$PG_UNIT"
  local i
  for i in $(seq 1 15); do
    [[ $(systemctl is-active "$PG_UNIT") == active ]] && break
    sleep 2
  done
  [[ $(systemctl is-active "$PG_UNIT") == active ]] \
    || die "$PG_UNIT 기동에 실패했다. journalctl -u $PG_UNIT -n 50 을 본다."

  local in_recovery
  in_recovery=$(psql_value -c 'SELECT pg_is_in_recovery();')
  info "pg_is_in_recovery = $in_recovery"
  [[ $in_recovery == t ]] || die '이 호스트가 Standby가 아니다. 즉시 멈추고 상태를 확인한다.'

  local status=
  for i in $(seq 1 15); do
    status=$(psql_value -c "SELECT coalesce((SELECT status FROM pg_stat_wal_receiver LIMIT 1), 'none');" || true)
    [[ $status == streaming ]] && break
    sleep 2
  done
  [[ $status == streaming ]] || die "WAL 수신이 streaming 이 아니다(현재 $status).
  확인할 곳: DB01의 pg_hba.conf 한 줄, DB01의 방화벽 5432, .pgpass 비밀번호, primary.crt 지문."

  psql_postgres -c 'SELECT status, sender_host, sender_port, slot_name FROM pg_stat_wal_receiver;' | sed 's/^/       /'
  psql_postgres -c "SELECT name, setting FROM pg_settings WHERE name IN ('primary_slot_name','listen_addresses','port','hot_standby','ssl','wal_level');" | sed 's/^/       /'
  ss -lnt | sed 's/^/       /'
  info '기동과 복제 수신을 확인했다.'
}

# ---------------------------------------------------------------------------
# monitor: 관측 계정. DB 역할은 DB01에서만 만들고 여기서는 확인만 한다.
# ---------------------------------------------------------------------------
step_monitor() {
  log 'monitor: 관측 계정을 확인한다'
  need_root
  detect_pg

  if id failstop_monitor >/dev/null 2>&1; then
    info 'OS 계정 failstop_monitor 가 이미 있다.'
  else
    useradd --system --no-create-home --shell /sbin/nologin failstop_monitor
    info 'OS 계정 failstop_monitor 를 만들었다(로그인 불가).'
  fi

  # DB 역할은 Primary에서만 만든다. WAL로 넘어온 것을 여기서는 조회로만 확인한다.
  # Standby에서 create-monitor-role.sql 을 실행하면 읽기 전용 오류가 난다.
  if [[ $(psql_value -c "SELECT count(*) FROM pg_roles WHERE rolname='failstop_monitor';") == 1 ]]; then
    info 'DB 역할 failstop_monitor 가 복제로 넘어온 것을 확인했다.'
    psql_postgres -c \
      "SELECT r.rolname AS member_of FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid JOIN pg_roles u ON u.oid = m.member WHERE u.rolname = 'failstop_monitor';" \
      | sed 's/^/       /'
  else
    die 'DB 역할 failstop_monitor 가 없다.
  DB01에서 create-monitor-role.sql 을 먼저 적용한다. DB02에서 실행하지 않는다.
  Standby는 읽기 전용이라 역할을 만들 수 없고, 만들어도 복제와 충돌한다.'
  fi

  # peer 인증으로 실제로 붙는지 본다.
  ( cd /tmp && sudo -u failstop_monitor psql -X -P pager=off -d "$DB_NAME" \
      -c 'SELECT current_user, pg_is_in_recovery();' ) | sed 's/^/       /'
}

# ---------------------------------------------------------------------------
# exporters: node_exporter와 postgres_exporter.
# 공통 기반이 node_exporter를 이미 올렸으면 덮어쓰지 않는다.
# ---------------------------------------------------------------------------
step_exporters() {
  log 'exporters: node_exporter와 postgres_exporter를 설치한다'
  need_root
  detect_pg
  need_file "$FILES_DIR/postgres_exporter.service"
  need_file "$FILES_DIR/pg-queries.yaml"
  id failstop_monitor >/dev/null 2>&1 || die 'failstop_monitor 계정이 없다. monitor 단계를 먼저 실행한다.'

  id node_exporter >/dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin node_exporter

  local tmp
  tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf -- '$tmp'" EXIT

  # --- node_exporter ---
  # 호스트 지표는 여섯 대 공통이라 공통 절차(00-common-setup.sh)가 이미 깔았을 수 있다.
  # 이미 있으면 바이너리와 유닛을 덮어쓰지 않는다. 공통 절차가 정한 것을 여기서 되돌리지 않는다.
  if [[ -f /etc/systemd/system/node_exporter.service ]]; then
    info '이미 설치된 node_exporter 유닛을 그대로 쓴다. 덮어쓰지 않는다.'
  else
    need_file "$FILES_DIR/node_exporter-host.service"
    local ne_file="node_exporter-${NODE_EXPORTER_VERSION}.linux-amd64.tar.gz"
    fetch_tarball \
      "https://github.com/prometheus/node_exporter/releases/download/v${NODE_EXPORTER_VERSION}/${ne_file}" \
      "$ne_file" "$NODE_EXPORTER_SHA256"
    tar -xzf "$DOWNLOAD_DIR/$ne_file" -C "$tmp"
    systemctl is-active --quiet node_exporter && systemctl stop node_exporter || true
    install -o root -g root -m 0755 \
      "$tmp/node_exporter-${NODE_EXPORTER_VERSION}.linux-amd64/node_exporter" /usr/local/bin/node_exporter
    restorecon -v /usr/local/bin/node_exporter || true
    install -o root -g root -m 0644 "$FILES_DIR/node_exporter-host.service" /etc/systemd/system/node_exporter.service
  fi

  # 바인딩 주소. 유닛이 listen.env를 읽을 때만 다룬다. OPS01이 lab-lan으로 수집하기 때문이다.
  if grep -q '^EnvironmentFile=/etc/node_exporter/listen.env' /etc/systemd/system/node_exporter.service; then
    install -d -m 0755 /etc/node_exporter
    if grep -qx "NODE_EXPORTER_ADDRESS=${LAB_IP}" /etc/node_exporter/listen.env 2>/dev/null; then
      info "node_exporter 바인딩 주소 확인: $LAB_IP"
    elif [[ -f /etc/node_exporter/listen.env ]]; then
      warn "listen.env의 주소가 $LAB_IP 가 아니다. 현재 값을 확인하고 어느 쪽이 맞는지 정한 뒤 고친다."
      sed 's/^/       /' /etc/node_exporter/listen.env
    else
      printf 'NODE_EXPORTER_ADDRESS=%s\n' "$LAB_IP" > /etc/node_exporter/listen.env
      chmod 0644 /etc/node_exporter/listen.env
      info "listen.env를 만들었다: $LAB_IP"
    fi
  else
    info '유닛이 listen.env를 읽지 않는다. 바인딩 주소는 유닛 본문을 확인한다.'
  fi

  # systemd 수집기의 유닛 이름 필터를 이 VM의 실제 유닛에 맞춘다.
  # 저장소 원본과 공통 절차 모두 정규식에 postgresql 을 쓰는데, PGDG 패키지의
  # 실제 이름이 postgresql-17 이면 DB 서비스 상태가 수집되지 않는다.
  # 유닛 파일은 고치지 않고 드롭인으로 ExecStart만 덮어쓴다. DB01과 같은 방식이다.
  local pg_base exec_line new_exec ne_restart=false
  pg_base=${PG_UNIT%.service}
  exec_line=$(grep -m1 '^ExecStart=' /etc/systemd/system/node_exporter.service || true)
  new_exec=${exec_line//|postgresql|/|${pg_base}|}
  if [[ $new_exec != *postgres_exporter* ]]; then
    new_exec=${new_exec//|node_exporter)/|node_exporter|postgres_exporter)}
  fi
  if [[ -n $exec_line && $new_exec != "$exec_line" ]]; then
    local dropin=/etc/systemd/system/node_exporter.service.d/10-unit-include.conf
    install -d -m 0755 /etc/systemd/system/node_exporter.service.d
    {
      printf '# 유닛 이름 필터를 이 VM의 실제 유닛(%s)에 맞춘다.\n' "$PG_UNIT"
      printf '# 원본 유닛 파일은 고치지 않는다. ExecStart 는 한 번 비운 뒤 다시 적는다.\n'
      printf '[Service]\n'
      printf 'ExecStart=\n'
      printf '%s\n' "$new_exec"
    } > "$tmp/10-unit-include.conf"
    if cmp -s "$tmp/10-unit-include.conf" "$dropin"; then
      info '수집기 필터 드롭인이 이미 같은 내용이다.'
    else
      install -o root -g root -m 0644 "$tmp/10-unit-include.conf" "$dropin"
      ne_restart=true
      info "systemd 수집기 필터를 $pg_base 로 맞춘 드롭인을 놓았다."
    fi
  else
    info '수집기 필터를 바꿀 필요가 없다.'
  fi

  # --- postgres_exporter ---
  local pe_file="postgres_exporter-${PG_EXPORTER_VERSION}.linux-amd64.tar.gz"
  fetch_tarball \
    "https://github.com/prometheus-community/postgres_exporter/releases/download/v${PG_EXPORTER_VERSION}/${pe_file}" \
    "$pe_file" "$PG_EXPORTER_SHA256"
  tar -xzf "$DOWNLOAD_DIR/$pe_file" -C "$tmp"
  systemctl is-active --quiet postgres_exporter && systemctl stop postgres_exporter || true
  install -o root -g root -m 0755 \
    "$tmp/postgres_exporter-${PG_EXPORTER_VERSION}.linux-amd64/postgres_exporter" /usr/local/bin/postgres_exporter
  restorecon -v /usr/local/bin/postgres_exporter || true

  # 0.0.0.0 에 바인딩하지 않는다. lab-lan 주소에만 연다.
  install -d -m 0755 /etc/postgres_exporter
  printf 'PG_EXPORTER_ADDRESS=%s\n' "$LAB_IP" > /etc/postgres_exporter/listen.env
  chmod 0644 /etc/postgres_exporter/listen.env
  # 쿼리 파일은 상류 원본이다. 편집하지 않는다. in_recovery 값으로 Primary와 Standby를
  # 구분하며, 복제 지연을 유실량으로 바꾸어 읽지 않는다.
  # 사용자 쿼리 옵션은 폐기 예정 기능이므로 exporter 버전을 올릴 때 호환성을 다시 확인한다.
  install -o root -g root -m 0644 "$FILES_DIR/pg-queries.yaml" /etc/postgres_exporter/queries.yaml
  install -o root -g root -m 0644 "$FILES_DIR/postgres_exporter.service" /etc/systemd/system/postgres_exporter.service

  systemctl daemon-reload
  $ne_restart && systemctl restart node_exporter || true
  systemctl enable --now node_exporter postgres_exporter
  systemctl restart postgres_exporter
  sleep 3
  systemctl is-active node_exporter postgres_exporter | sed 's/^/       /'

  curl -fsS "http://$LAB_IP:9100/metrics" | head -n 3 | sed 's/^/       /'
  local metrics
  metrics=$(curl -fsS "http://$LAB_IP:9187/metrics" || true)
  if printf '%s\n' "$metrics" | grep -E '^pg_up |^pg_wal_position_in_recovery' | sed 's/^/       /'; then
    info 'Standby에서는 pg_wal_position_in_recovery 가 1 이어야 한다.'
  else
    warn '지표에서 pg_up 또는 in_recovery 를 찾지 못했다. journalctl -u postgres_exporter 를 본다.'
  fi
  warn "OPS01에서 $LAB_IP:9100 과 $LAB_IP:9187 을 수집 대상에 넣고 promtool check config 를 통과시킨 뒤 Prometheus만 재시작한다."
}

# ---------------------------------------------------------------------------
# journald: 로그 보존. 공통 기반이 이미 걸어 두었으면 덮어쓰지 않는다.
# ---------------------------------------------------------------------------
step_journald() {
  log 'journald: 로그 보존 설정을 확인한다'
  need_root
  need_file "$FILES_DIR/60-exchange-lab.conf"

  install -d -o root -g root -m 0755 /etc/systemd/journald.conf.d
  if [[ -f /etc/systemd/journald.conf.d/60-exchange-lab.conf ]]; then
    info '보존 설정이 이미 있다.'
  else
    install -o root -g root -m 0644 "$FILES_DIR/60-exchange-lab.conf" \
      /etc/systemd/journald.conf.d/60-exchange-lab.conf
    systemctl restart systemd-journald
    journalctl --flush
    info '영구 저장과 보존 한도를 적용했다.'
  fi
  journalctl --disk-usage | sed 's/^/       /'
  info '복제 지연과 승격 시각을 판단하려면 로그 시각이 맞아야 한다. chronyd 상태를 함께 본다.'
  chronyc -n tracking 2>/dev/null | sed 's/^/       /' || warn 'chrony 동기화 정보를 아직 얻지 못했다.'
}

# ---------------------------------------------------------------------------
# firewall: 역할 포트. DB02는 5432를 열지 않는다.
# ---------------------------------------------------------------------------
step_firewall() {
  log 'firewall: DB02 역할 규칙을 적용한다'
  need_root
  warn 'input 정책이 drop이다. 규칙이 잘못되면 SSH가 끊긴다. VirtualBox 콘솔 창을 열어 둔 상태에서 실행한다.'

  # 공통 기반 한 계통만 쓴다. 기반이 없으면 규칙을 적용하지 않고 멈춘다.
  common_base_present || die '공통 기반(/etc/vm-lab/firewall.nft, vm-lab-firewall.service)이 없다.
  먼저 sudo /opt/vm-lab/scripts/00-common-setup.sh 를 적용한 뒤 이 단계를 다시 실행한다.
  이 스크립트는 별도 테이블을 세우지 않는다.'

  need_file "$FILES_DIR/db02-firewall.d.nft"
  need_file "$FILES_DIR/db02-firewall-promoted.d.nft"
  info '공통 기반을 찾았다. /etc/vm-lab/firewall.d 에 역할 규칙을 더한다.'
  install -d -o root -g root -m 0755 /etc/vm-lab/firewall.d
  install -o root -g root -m 0600 "$FILES_DIR/db02-firewall.d.nft" /etc/vm-lab/firewall.d/31-db02.nft
  if ! nft --check --file /etc/vm-lab/firewall.nft; then
    rm -f /etc/vm-lab/firewall.d/31-db02.nft
    die '규칙 구문이 맞지 않다. 역할 파일을 지우고 멈춘다.'
  fi
  systemctl reload vm-lab-firewall.service || systemctl restart vm-lab-firewall.service
  { nft list chain inet vmlab input; nft list chain inet vmlab roles 2>/dev/null; } | sed 's/^/       /'

  info '5432는 열지 않았다. 00-주소계획.md의 "DB02 5432 허용 출발지 없음"을 그대로 적용한 값이다.'
  info '승격 전 DB02에서 5432를 막는 것은 이 한 겹이다. pg_hba 는 DB01에서 복사돼 앱 규칙을 이미 갖고 있다.'
  info '승격용 규칙 파일은 build-files/db 에 따로 있고 32-db-failover.sh 의 06-open 단계가 교체한다.'
}

# ---------------------------------------------------------------------------
# backup-store: 별도 백업 저장소와 격리 복원 시험 도구.
# ---------------------------------------------------------------------------
step_backup_store() {
  log 'backup-store: 백업 저장소와 격리 복원 시험 도구를 올린다'
  need_root
  need_file "$FILES_DIR/db02-restore-check.sh"
  need_file "$FILES_DIR/exchange-backup-verify.service"
  need_file "$FILES_DIR/summary.sql"
  findmnt -n "$BACKUP_MOUNT" >/dev/null 2>&1 || die "$BACKUP_MOUNT 가 마운트되어 있지 않다. disk 단계를 먼저 실행한다."

  install -d -o postgres -g postgres -m 0700 "$BACKUP_MOUNT/incoming" "$BACKUP_MOUNT/restore"
  install -d -o root -g root -m 0755 /opt/vm-lab/bin
  install -o root -g root -m 0755 "$FILES_DIR/db02-restore-check.sh" /opt/vm-lab/bin/db02-restore-check.sh
  install -o root -g root -m 0644 "$FILES_DIR/exchange-backup-verify.service" \
    /etc/systemd/system/exchange-backup-verify.service
  systemctl daemon-reload

  # 임시 클러스터의 unix 소켓은 postgresql_var_run_t 문맥이어야 한다.
  # SELinux를 끄지 않고 필요한 문맥만 더한다.
  if command -v semanage >/dev/null 2>&1; then
    semanage fcontext -a -t postgresql_var_run_t "${BACKUP_MOUNT}/restore/[^/]+/socket(/.*)?" 2>/dev/null \
      || semanage fcontext -m -t postgresql_var_run_t "${BACKUP_MOUNT}/restore/[^/]+/socket(/.*)?"
    info '복원 시험용 소켓 문맥 규칙을 등록했다.'
  fi
  restorecon -R "$BACKUP_MOUNT" /opt/vm-lab/bin 2>/dev/null || true
  ls -ldZ "$BACKUP_MOUNT" "$BACKUP_MOUNT/incoming" | sed 's/^/       /'

  cat <<EOF

       아카이브는 관리 대역으로 받는다. DB02의 5432를 열지 않으려고 서비스망이 아니라
       관리망을 데이터 이동에 쓴다. 도면의 통신 정책과 다른 선택이므로 이유를 문서에 남긴다.

       1) DB01에서 아카이브를 만든다. 이것은 DB01의 몫이다.
          failstop 저장소의 deploy/linux/postgresql/backup-restore-check.py 를 DB01에 올려 둔 뒤
          postgres 계정으로 실행하고, --invariants 에 summary.sql 경로를 준다.
          그 스크립트는 pg_is_in_recovery()='f' 를 요구하므로 DB02에서는 실행되지 않는다.
            sudo -u postgres <DB01에 올린 backup-restore-check.py> \\
                 --invariants /opt/vm-lab/build-files/db/summary.sql
       2) DB01에서 관리 대역으로 보낸다.
            scp /var/lib/pgsql/backup-checks/<ID>/exchange.dump \\
                /var/lib/pgsql/backup-checks/<ID>/result.json ops@$MGMT_IP:/tmp/
       3) DB02에서 저장소로 옮긴다.
            sudo install -d -o postgres -g postgres -m 0700 $BACKUP_MOUNT/incoming/<ID>
            sudo install -o postgres -g postgres -m 0600 /tmp/exchange.dump /tmp/result.json \\
                 $BACKUP_MOUNT/incoming/<ID>/
            sudo restorecon -R $BACKUP_MOUNT
       4) 격리 복원 시험을 실행한다.
            sudo systemctl start exchange-backup-verify.service
            sudo journalctl -u exchange-backup-verify -n 80 --no-pager
            sudo cat $BACKUP_MOUNT/restore/<ID>/result.json
EOF
}

# ---------------------------------------------------------------------------
# verify: 상태 확인. 통과 여부를 명령 출력으로 판정한다.
# ---------------------------------------------------------------------------
# 명령을 실행하고 출력과 판정을 함께 남긴다. 파이프의 마지막이 sed라서
# 그냥 두면 종료 코드가 항상 0이 된다. PIPESTATUS로 원래 명령의 값을 읽는다.
VERIFY_FAILED=0
check() {
  local title=$1 status
  shift
  printf '\n  -- %s\n' "$title"
  "$@" 2>&1 | sed 's/^/     /'
  status=${PIPESTATUS[0]}
  if (( status != 0 )); then
    printf '     [확인 필요] 위 명령이 0이 아닌 값(%s)으로 끝났다. 출력을 그대로 기록한다.\n' "$status"
    VERIFY_FAILED=1
  fi
}

# 아래 판정 함수들은 값을 출력한 뒤 기대와 같을 때만 0을 돌려준다.
v_pgdata_perm() {
  stat -c '%U %G %a %n' "$PGDATA"
  [[ $(stat -c '%U %G %a' "$PGDATA") == 'postgres postgres 700' ]]
}
v_pgdata_context() {
  ls -Zd "$PGDATA"
  ls -Zd "$PGDATA" | grep -q postgresql_db_t
}
v_selinux() { getenforce; [[ $(getenforce) == Enforcing ]]; }
v_in_recovery() {
  local value
  value=$(psql_value -c 'SELECT pg_is_in_recovery();')
  printf 'pg_is_in_recovery = %s\n' "$value"
  # f 가 나오면 이 호스트는 Standby가 아니다. 그 상태로 다음을 진행하지 않는다.
  [[ $value == t ]]
}
v_slot_unused() {
  local value
  value=$(psql_value -c "SELECT setting FROM pg_settings WHERE name='primary_slot_name';")
  printf "primary_slot_name = '%s'\n" "$value"
  printf '슬롯은 쓰지 않는다. 빈 값이어야 하고 DB01의 pg_replication_slots 도 비어 있어야 한다.\n'
  [[ -z $value ]]
}
v_read_only() {
  local out
  out=$( ( cd /tmp && sudo -u postgres psql -X -qAt -c 'CREATE TABLE verify_ro(i int);' ) 2>&1 || true )
  printf '%s\n' "$out"
  # 쓰기가 거부되는 것이 정상이다. 성공하면 Standby가 아니다.
  printf '%s' "$out" | grep -qi 'read-only'
}
v_no_wildcard_bind() {
  ss -lnt
  ! ss -lnt | grep -Eq '(0\.0\.0\.0|\[::\]):(22|5432|9100|9187)'
}
v_metric() {
  local name=$1 out
  out=$(curl -fsS "http://$LAB_IP:9187/metrics" || true)
  printf '%s\n' "$out" | grep -E "^$name" || printf '%s 지표를 찾지 못했다\n' "$name"
  printf '%s\n' "$out" | grep -qE "^$name"
}
v_db_port_closed() {
  local rules
  # 역할 규칙은 roles 체인에 들어간다. input 만 읽으면 5432 가 열려 있어도
  # '규칙이 하나도 없다'로 통과해 버린다. 차단 확인은 두 체인을 함께 봐야 한다.
  rules=$( { nft list chain inet vmlab input; nft list chain inet vmlab roles; } 2>/dev/null || true)
  printf '%s\n' "$rules" | grep 'dport 5432' || printf '5432 규칙이 하나도 없다\n'
  # accept 가 하나라도 있으면 승격 전 정책이 깨진 것이다.
  ! printf '%s\n' "$rules" | grep 'dport 5432' | grep -q accept
}

step_verify() {
  log 'verify: 구성 상태를 확인한다'
  need_root
  detect_pg
  show_pg_paths
  VERIFY_FAILED=0

  check '블록 장치와 마운트' lsblk -o NAME,TYPE,SIZE,FSTYPE,MOUNTPOINTS
  check 'PGDATA 마운트' findmnt "$DATA_MOUNT"
  check '백업 저장소 마운트' findmnt "$BACKUP_MOUNT"
  check '용량' df -h "$DATA_MOUNT" "$BACKUP_MOUNT"
  check 'PGDATA 권한(postgres postgres 700)' v_pgdata_perm
  check 'PGDATA SELinux 문맥(postgresql_db_t)' v_pgdata_context
  check 'SELinux 상태(Enforcing)' v_selinux
  check '서비스 상태' systemctl is-active "$PG_UNIT" chronyd sshd
  check 'Standby 여부(t 여야 한다)' v_in_recovery
  check 'WAL 수신(streaming 이어야 한다)' psql_postgres -c \
    'SELECT status, sender_host, sender_port, slot_name FROM pg_stat_wal_receiver;'
  check '복제 슬롯 미사용' v_slot_unused
  check '읽기 전용 강제(쓰기가 거부돼야 한다)' v_read_only
  check '실효 설정' psql_postgres -c 'SHOW data_directory;' -c 'SHOW listen_addresses;' \
    -c 'SHOW ssl;' -c 'SHOW wal_level;' -c 'SHOW hot_standby;' -c 'SHOW primary_slot_name;'
  check '공통 설정 해시' sha256sum "$CONF_DIR/exchange.conf"
  check '인증서' openssl x509 -in "$CONF_DIR/server.crt" -noout -subject -dates -ext subjectAltName
  check '인증서 지문(승격 때 앱에 전달할 값)' openssl x509 -in "$CONF_DIR/server.crt" -noout -fingerprint -sha256
  check '수신 주소(0.0.0.0 바인딩이 없어야 한다)' v_no_wildcard_bind
  check 'exporter 상태' systemctl is-active node_exporter postgres_exporter
  check 'pg_up 지표' v_metric 'pg_up '
  check 'in_recovery 지표(1 이어야 한다)' v_metric 'pg_wal_position_in_recovery'

  check '방화벽 규칙(공통 기반 + DB02 역할 규칙)' nft list chain inet vmlab input
  check '5432 미개방(accept 규칙이 없어야 한다)' v_db_port_closed

  check '최근 기동 로그' journalctl -u "$PG_UNIT" --since '-10min' --no-pager
  # ausearch는 기록이 없으면 0이 아닌 값을 낸다. 없는 것이 정상이므로 메시지로 바꿔 출력한다.
  check 'SELinux 거부 기록(있으면 문맥을 고친다)' \
    sh -c 'ausearch -m AVC -ts recent 2>&1 || echo "AVC 기록이 없거나 ausearch가 없다"'

  if [[ -x /opt/vm-lab/check-host.sh ]]; then
    check '호스트 점검' /opt/vm-lab/check-host.sh --inspect
  fi

  printf '\n'
  if (( VERIFY_FAILED == 0 )); then
    log 'verify: 이 스크립트가 보는 항목은 모두 기대한 값이다'
  else
    log 'verify: 확인이 필요한 항목이 있다. 위 출력을 본다'
  fi

  cat <<'EOF'

  이 스크립트가 확인하지 않는 것은 다음이다. 각 호스트에서 직접 실행하고 화면을 남긴다.

  DB01
    SELECT client_addr, application_name, state, sync_state FROM pg_stat_replication;
      192.168.10.32 / vm_standby / streaming / async 가 나와야 한다.
      sync 가 나오면 exchange.conf 의 synchronous_standby_names 적용을 다시 본다.
    SELECT slot_name, active, restart_lsn FROM pg_replication_slots;
      비어 있어야 한다. 슬롯은 쓰지 않기로 했고 DB01도 만들지 않는다.
      행이 있으면 어디서 만들었는지 확인하고 지운다.
    거래 DB가 아닌 별도 시험 DB에 행을 하나 넣고 DB02에서 같은 행을 읽는다.
      읽히면 복제가 실제로 동작한다.

  APP01
    psql -h 192.168.10.32 -p 5432 -U exchange_app -d exchange
      연결되지 않아야 한다. 시간 초과나 거절이 나온다.
      막는 것은 DB02의 nftables 한 겹이다. pg_hba.conf 는 DB01에서 복사돼 앱 규칙을
      이미 갖고 있으므로 두 겹이 아니다. 성공하면 방화벽 규칙을 먼저 본다.

  작업 PC
    curl --max-time 5 http://192.168.56.32:9187/metrics
      실패해야 한다. 지표 포트를 관리 대역에 열지 않았다.

  OPS01
    promtool check config /etc/prometheus/prometheus.yml 통과 뒤 Prometheus만 재시작하고
    db02 수집 대상이 UP 이며 pg_wal_position_in_recovery 가 1 인지 본다.

  재부팅 후
    systemctl is-active <pg unit> postgres_exporter node_exporter 와 방화벽 유닛,
    그리고 pg_stat_wal_receiver 재조회. 모두 active 이고 streaming 이 다시 붙어야 한다.

  복제 지연, 승격 소요 시간, 유실량, RTO와 RPO는 전부 미측정이다.
  학교 AWS의 2026-09-20 측정값을 이 VM 환경의 값으로 옮겨 적지 않는다.
EOF
  return 0
}

# ---------------------------------------------------------------------------
# 진입점
# ---------------------------------------------------------------------------
# all 에는 DB01과 맞춰야 하는 단계를 넣지 않는다. 인증서 지문 대조와 복제 비밀번호는
# 사람이 화면을 보고 넣어야 하고, 베이스백업은 DB01이 준비된 뒤에만 성립한다.
ALL_STEPS=(preflight disk packages config tls journald firewall backup-store)

usage() {
  cat <<EOF
사용법: sudo $0 <단계> [단계...]

  all        기반 단계를 순서대로 실행한다
             ${ALL_STEPS[*]}

  DB01과 맞추는 단계는 순서대로 따로 실행한다.
  primary-cert <파일>      DB01 공개 인증서를 배치하고 지문을 출력한다
  confirm-primary <지문>   DB01 화면에서 읽은 지문과 대조한다
  passfile                 복제 비밀번호를 넣는다(대화형, 화면에 표시하지 않음)
  basebackup               DB01에서 전량 베이스백업을 받는다
  start                    기동하고 WAL 수신을 확인한다
  monitor                  관측 계정을 확인한다
  exporters                node_exporter와 postgres_exporter를 올린다
  verify                   상태를 확인한다

예)
  sudo $0 all 2>&1 | tee ~/db02-build.log
  sudo $0 primary-cert /tmp/db01-server.crt
  sudo $0 confirm-primary 3A:1B:...:9F
  sudo $0 passfile
  sudo $0 basebackup start monitor exporters verify

DB01에서 먼저 끝내야 하는 일
  replicator 역할과 비밀번호, pg_hba.conf 에 hostssl replication replicator 192.168.10.32/32,
  create-monitor-role.sql 적용, 서버 공개 인증서 전달.
EOF
}

main() {
  local -a steps=()
  if (($# == 0)); then usage; exit 1; fi
  if [[ $1 == all ]]; then
    steps=("${ALL_STEPS[@]}")
  else
    steps=("$@")
  fi

  local step
  while (( ${#steps[@]} )); do
    step=${steps[0]}
    steps=("${steps[@]:1}")
    case "$step" in
      preflight|disk|packages|config|tls|passfile|basebackup|start|monitor|exporters|journald|firewall|verify)
        "step_$step" ;;
      backup-store) step_backup_store ;;
      primary-cert)
        # 뒤에 오는 값 하나를 인수로 받는다.
        step_primary_cert "${steps[0]:-}"
        steps=("${steps[@]:1}") ;;
      confirm-primary)
        step_confirm_primary "${steps[0]:-}"
        steps=("${steps[@]:1}") ;;
      -h|--help|help) usage; exit 0 ;;
      *) usage; die "모르는 단계: $step" ;;
    esac
  done
}

main "$@"
