#!/usr/bin/env bash
#
# DB01 Primary 구축 스크립트. Rocky Linux 10.2 minimal에 PostgreSQL 17을 직접 설치한다.
#
# 이 스크립트가 하는 일
#   데이터 디스크 파티션과 마운트, PostgreSQL 17 설치, initdb, 공통 설정 배치,
#   TLS 자체 서명 인증서, pg_hba, 역할과 데이터베이스, 계정 비밀번호, 관측 계정,
#   마이그레이션 창 개폐, node_exporter와 postgres_exporter, journald 보존,
#   nftables 역할 규칙, 상태 확인.
#
# 이 스크립트가 하지 않는 일
#   Flyway 마이그레이션 실행(APP01의 21-app-migrate.sh 가 한다. 이 스크립트는 창만 연다),
#   DB02 베이스백업, pfSense 구성. 복제 슬롯도 만들지 않는다.
#
# 공통 절차와의 관계
#   여섯 대 공통 설정은 scripts/00-common-setup.sh 가 맡는다. 방화벽은 공통 기반
#   table inet vmlab 한 계통만 쓰고, 이 스크립트는 그 위에
#   /etc/vm-lab/firewall.d/30-db01.nft 로 역할 포트만 얹는다. 공통 기반이 없으면
#   firewall 단계는 규칙을 적용하지 않고 멈춘다. 단독 테이블로 물러서는 경로는 없앴다.
#   node_exporter와 journald 설정도 공통 절차가 놓은 것을 덮어쓰지 않는다.
#
# 실행 방법
#   sudo /opt/vm-lab/scripts/30-db-primary-setup.sh all 2>&1 | tee ~/db01-build.log
#   sudo /opt/vm-lab/scripts/30-db-primary-setup.sh passwords
#   sudo /opt/vm-lab/scripts/30-db-primary-setup.sh migrate-open      <- APP01에서 Flyway 실행 전
#   sudo /opt/vm-lab/scripts/30-db-primary-setup.sh migrate-close     <- Flyway 실행 뒤
#   sudo /opt/vm-lab/scripts/30-db-primary-setup.sh grant-app-role   <- 그다음. 빠뜨리면 앱이
#       테이블을 하나도 보지 못한다. app_role 은 마이그레이션이 만들므로 이 순서여야 한다.
#   sudo /opt/vm-lab/scripts/30-db-primary-setup.sh verify
#   단계 이름을 여러 개 이어서 줄 수도 있다. 예: ... setup.sh config tls hba start
#   all 에는 passwords, migrate-open, migrate-close, grant-app-role, verify 가 들어 있지 않다.
#   비밀번호가 없으면 마이그레이션이 되지 않으므로 all 다음에 passwords 를 반드시 실행한다.
#
# 되돌리기
#   각 단계는 이미 끝난 일을 다시 하지 않는다. 데이터가 들어 있는 디스크나
#   초기화된 PGDATA를 발견하면 덮어쓰지 않고 멈춘다. 지우는 일은 사람이 판단한다.
#
# 주의
#   firewall 단계는 input 정책을 drop으로 바꾼다. 규칙이 잘못되면 SSH가 끊긴다.
#   VirtualBox 콘솔 창을 열어 둔 상태에서 실행한다.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 고정 값. 00-주소계획.md의 값과 같아야 한다. 다르면 주소계획을 먼저 고친다.
# ---------------------------------------------------------------------------
NODE_NAME=db01
LAB_IP=192.168.10.31          # lab-lan. DB와 지표를 여는 주소
MGMT_IP=192.168.56.31         # host-only. SSH만 여는 주소
MGMT_CIDR=192.168.56.0/24
APP01_IP=192.168.10.21
APP02_IP=192.168.10.22
DB02_IP=192.168.10.32
OPS01_IP=192.168.10.41

DB_NAME=exchange
# 복제 슬롯은 쓰지 않는다. DB02는 pg_basebackup 을 -S 없이 받고
# postgresql.auto.conf 에 primary_slot_name = '' 을 명시한다.
# 단절이 길어져 필요한 WAL이 사라지면 전량 베이스백업을 다시 받는다.
OWNER_ROLE=exchange_owner     # Flyway 마이그레이션 계정. 평상시에는 NOLOGIN 으로 잠근다
APP_ROLE=exchange_app
REPL_ROLE=replicator
MIGRATE_MARK=/etc/vm-lab/migrate-window   # 마이그레이션 창을 연 기록
MIGRATE_TAG='vm-lab-migrate-window'       # pg_hba.conf 안에서 임시 규칙을 찾는 표식
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
DATA_PART="${DATA_DISK}1"
DATA_MOUNT=/var/lib/pgsql
CONF_DIR=/etc/postgresql

# failstop 저장소 deploy/linux/postgresql/exchange.conf 의 해시.
# 학교 EC2 적용본과 같은 값이며 파일이 바뀌면 여기서 멈춘다.
EXCHANGE_CONF_SHA256=a03ed6236983133a24cac6610628072205e258e7fdeb21a1db7598c68db45958

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
# 공통 함수
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
    rsync -a --delete --exclude 'keys/' <build 경로>/ ops@192.168.56.31:/home/ops/build/
  build-files/db 안의 파일은 failstop 저장소 deploy/linux 의 사본이다."
}

# postgres 계정으로 SQL을 실행한다. /root에서 실행할 때 나오는 디렉터리 경고를
# 없애려고 /tmp로 이동한 뒤 실행한다.
psql_postgres() { ( cd /tmp && sudo -u postgres psql -X -v ON_ERROR_STOP=1 -P pager=off "$@" ); }

# 관측 계정으로 붙는다. peer 인증이 되는지 보는 용도다.
psql_monitor() { ( cd /tmp && sudo -u failstop_monitor psql -X -P pager=off -d "$DB_NAME" "$@" ); }

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
    if [[ -x /usr/pgsql-17/bin/initdb ]]; then
      PG_BIN=/usr/pgsql-17/bin
    elif command -v initdb >/dev/null 2>&1; then
      PG_BIN=$(dirname -- "$(command -v initdb)")
    else
      die 'initdb를 찾지 못했다. postgresql17-server 설치를 확인한다.'
    fi
  fi
}

show_pg_paths() {
  detect_pg
  info "유닛   : $PG_UNIT"
  info "PGDATA : $PGDATA"
  info "실행파일: $PG_BIN"
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
  [[ $host == ${NODE_NAME}* ]] || warn "호스트 이름이 ${NODE_NAME}로 시작하지 않는다. 다른 VM에서 실행하고 있지 않은지 확인한다."

  # 주소 확인. 도면과 주소계획의 값이 실제로 붙어 있어야 한다.
  local addr
  for addr in "$LAB_IP" "$MGMT_IP"; do
    if ip -brief -4 addr show | grep -qw "$addr/24"; then
      info "주소 확인: $addr"
    else
      warn "주소 $addr 가 붙어 있지 않다. nmcli로 고정 주소를 먼저 설정한다."
    fi
  done
  info '현재 인터페이스'
  ip -brief -4 addr show | sed 's/^/       /'

  # 인터페이스 이름 전제. 다르면 주소계획과 nft 파일의 enp0s3 줄을 함께 고친다.
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

  # initdb에 쓸 로케일이 실제로 있는지 본다.
  if locale -a 2>/dev/null | grep -qiE '^C\.utf-?8$'; then
    info '로케일 C.UTF-8 확인'
  else
    warn 'C.UTF-8 로케일을 찾지 못했다. initdb 옵션을 바꾸기 전에 원인을 확인한다.'
  fi

  info '시간 동기화 상태'
  timedatectl 2>/dev/null | sed 's/^/       /' || true

  [[ -d $FILES_DIR ]] || die "설정 원본 폴더가 없다: $FILES_DIR"
  info "설정 원본 폴더: $FILES_DIR"
}

# ---------------------------------------------------------------------------
# disk: 데이터 디스크를 나누고 /var/lib/pgsql 로 마운트한다.
# PGDATA를 OS 디스크와 분리해야 용량과 권한을 따로 다룰 수 있다.
# ---------------------------------------------------------------------------
step_disk() {
  log "disk: $DATA_DISK 를 $DATA_MOUNT 로 준비한다"
  need_root
  [[ -b $DATA_DISK ]] || die "$DATA_DISK 가 블록 장치가 아니다."

  if findmnt -n "$DATA_MOUNT" >/dev/null 2>&1; then
    info "$DATA_MOUNT 는 이미 마운트되어 있다. 파티션 작업을 건너뛴다."
    findmnt "$DATA_MOUNT" | sed 's/^/       /'
    return 0
  fi

  if [[ -b $DATA_PART ]]; then
    info "$DATA_PART 가 이미 있다. 새로 만들지 않는다."
  else
    # 빈 디스크가 아니면 멈춘다. 다른 자료가 들어 있는 디스크를 지우지 않는다.
    if blkid -p "$DATA_DISK" >/dev/null 2>&1; then
      die "$DATA_DISK 에 이미 서명이 있다. 내용을 확인한 뒤 사람이 판단해서 지운다."
    fi
    info "GPT 레이블과 파티션 하나를 만든다"
    parted "$DATA_DISK" --script mklabel gpt mkpart pgdata xfs 1MiB 100%
    udevadm settle
  fi

  local fstype
  fstype=$(blkid -o value -s TYPE "$DATA_PART" 2>/dev/null || true)
  if [[ -z $fstype ]]; then
    info "$DATA_PART 를 xfs로 포맷한다"
    mkfs.xfs -L pgdata "$DATA_PART"
    udevadm settle
  elif [[ $fstype == xfs ]]; then
    info "$DATA_PART 는 이미 xfs다. 포맷하지 않는다."
  else
    die "$DATA_PART 의 파일시스템이 $fstype 다. 내용을 확인한 뒤 사람이 판단한다."
  fi

  local uuid
  uuid=$(blkid -o value -s UUID "$DATA_PART" 2>/dev/null || true)
  [[ -n $uuid ]] || die "$DATA_PART 의 UUID를 읽지 못했다. lsblk -f 로 상태를 확인한다."
  info "UUID=$uuid"

  install -d -m 0755 "$DATA_MOUNT"

  # fstab은 장치 이름 대신 UUID로 적는다. 디스크 순서가 바뀌어도 같은 곳이 붙는다.
  if grep -q "UUID=$uuid" /etc/fstab; then
    info 'fstab에 이미 등록되어 있다.'
  else
    cp -a /etc/fstab "/etc/fstab.bak-$(date '+%Y%m%d%H%M%S')"
    printf 'UUID=%s  %s  xfs  defaults,noatime,nofail,x-systemd.device-timeout=10s  0 0\n' "$uuid" "$DATA_MOUNT" >> /etc/fstab
    info 'fstab에 추가했다. 원본은 /etc/fstab.bak-* 로 보관한다.'
  fi

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
  df -h "$DATA_MOUNT" | sed 's/^/       /'

  # nofail로 부팅은 막지 않는다. 대신 PostgreSQL 유닛이 RequiresMountsFor로 마운트를 요구한다.
  # PGDATA 없이 서비스가 뜨는 것보다 멈추는 편이 낫다고 판단한 값이다.
  info 'fstab 옵션에 nofail을 넣었다. 디스크가 없어도 부팅은 되고 PostgreSQL 유닛이 마운트를 요구해 멈춘다.'
}

# ---------------------------------------------------------------------------
# packages: 기본 패키지와 PostgreSQL 17을 설치한다.
# ---------------------------------------------------------------------------
step_packages() {
  log 'packages: 기본 패키지와 PostgreSQL 17을 설치한다'
  need_root

  # bootstrap-host.sh 78행의 Rocky 계열 목록과 같은 구성에 디스크, TLS, 연결 확인 도구를 더했다.
  # minimal ISO에 없어서 설치 때 건너뛴 패키지가 여기서 채워진다.
  info '기본 패키지'
  dnf install -y \
    nftables policycoreutils-python-utils chrony openssh-server iproute tar \
    ca-certificates rsync python3 logrotate sysstat \
    parted xfsprogs util-linux openssl nmap-ncat bind-utils

  # RHEL 10 계열에는 dnf 모듈 기능이 없다. 있는 경우에만 기본 postgresql 모듈을 끈다.
  # 모듈이 켜져 있으면 PGDG 패키지와 충돌한다.
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
  Rocky 10용 채널이 아직 없을 수 있다. dnf info postgresql-server 로 AppStream 버전을 확인하고
  17이 아니면 어떤 버전을 쓸지 먼저 정한다. 확인하지 않은 채로 다음 단계를 진행하지 않는다.'
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

  # 패키지가 postgres 계정을 만든 뒤 데이터 디스크의 소유권과 문맥을 맞춘다.
  # 디스크를 먼저 마운트했기 때문에 이 순서가 필요하다.
  if findmnt -n "$DATA_MOUNT" >/dev/null 2>&1; then
    info "$DATA_MOUNT 소유권과 SELinux 문맥을 맞춘다"
    chown postgres:postgres "$DATA_MOUNT"
    chmod 0700 "$DATA_MOUNT"
    restorecon -Rv "$DATA_MOUNT" | tail -n 5 | sed 's/^/       /' || true
    ls -ldZ "$DATA_MOUNT" | sed 's/^/       /'
  else
    warn "$DATA_MOUNT 가 마운트되어 있지 않다. disk 단계를 먼저 실행한다."
  fi
}

# ---------------------------------------------------------------------------
# initdb: 데이터 디렉터리를 초기화한다. 학교 Primary와 같은 옵션을 쓴다.
# ---------------------------------------------------------------------------
step_initdb() {
  log 'initdb: 데이터 디렉터리를 초기화한다'
  need_root
  detect_pg
  show_pg_paths

  if [[ -s $PGDATA/PG_VERSION ]]; then
    info "$PGDATA 는 이미 초기화되어 있다(PG_VERSION=$(cat "$PGDATA/PG_VERSION")). 다시 실행하지 않는다."
    return 0
  fi

  findmnt -n "$DATA_MOUNT" >/dev/null 2>&1 \
    || die "$DATA_MOUNT 가 마운트되어 있지 않다. OS 디스크 위에 PGDATA를 만들지 않는다."

  install -d -o postgres -g postgres -m 0700 "$PGDATA"

  # --data-checksums 는 디스크 손상을 읽는 시점에 잡아낸다. 나중에 켜기 어려우므로 지금 켠다.
  # --auth-local=peer 는 OS 계정 이름으로 로컬 인증한다. 비밀번호 없는 관리 경로다.
  # --auth-host=scram-sha-256 은 원격 접속의 기본 인증 방식이다.
  sudo -u postgres "$PG_BIN/initdb" -D "$PGDATA" \
    --encoding=UTF8 --locale=C.UTF-8 \
    --auth-local=peer --auth-host=scram-sha-256 \
    --data-checksums

  ls -ldZ "$PGDATA" | sed 's/^/       /'
}

# ---------------------------------------------------------------------------
# config: 공통 설정과 노드 설정을 배치하고 postgresql.conf에서 include 한다.
# ---------------------------------------------------------------------------
step_config() {
  log 'config: 설정 파일을 배치한다'
  need_root
  detect_pg
  need_file "$FILES_DIR/exchange.conf"
  need_file "$FILES_DIR/postgresql.vm.conf"

  # 공통 설정은 저장소 원본을 고치지 않고 그대로 쓴다. 해시로 같은 파일인지 확인한다.
  local actual
  actual=$(sha256sum "$FILES_DIR/exchange.conf" | awk '{print $1}')
  if [[ $actual != "$EXCHANGE_CONF_SHA256" ]]; then
    if [[ ${ALLOW_CONF_HASH_MISMATCH:-0} == 1 ]]; then
      warn "exchange.conf 해시가 다르다($actual). ALLOW_CONF_HASH_MISMATCH=1 이라 계속 진행한다."
    else
      die "exchange.conf 해시가 다르다.
  기대: $EXCHANGE_CONF_SHA256
  실제: $actual
  저장소 원본이 바뀌었는지 확인한다. 바뀐 내용을 검토하고 의도한 변경이면
  ALLOW_CONF_HASH_MISMATCH=1 을 붙여 다시 실행한다."
    fi
  else
    info "exchange.conf 해시 확인: $actual"
  fi

  # /etc/postgresql 는 RHEL 계열의 기본 경로가 아니다. postgres가 읽을 수 있도록
  # 그룹을 postgres로 두고 0750을 준다.
  install -d -o root -g postgres -m 0750 "$CONF_DIR"
  install -o root -g postgres -m 0640 "$FILES_DIR/exchange.conf" "$CONF_DIR/exchange.conf"
  install -o root -g postgres -m 0640 "$FILES_DIR/postgresql.vm.conf" "$CONF_DIR/node.conf"

  # 노드 설정에 이 VM의 주소가 들어 있는지 확인한다. 다른 노드 파일을 복사한 사고를 막는다.
  grep -q "listen_addresses *= *'localhost,${LAB_IP}'" "$CONF_DIR/node.conf" \
    || die "node.conf의 listen_addresses가 localhost,${LAB_IP} 가 아니다. 파일을 확인한다."

  # postgresql.conf 맨 끝에서 공통 설정을 읽는다. 마지막에 읽은 값이 이긴다.
  if grep -q "^include = '$CONF_DIR/exchange.conf'" "$PGDATA/postgresql.conf"; then
    info 'postgresql.conf에 include가 이미 있다.'
  else
    cp -a "$PGDATA/postgresql.conf" "$PGDATA/postgresql.conf.initdb-$(date '+%Y%m%d%H%M%S')"
    printf "\n# 공통 설정. 파일 본문은 failstop 저장소 deploy/linux/postgresql/exchange.conf 와 같다.\ninclude = '%s/exchange.conf'\n" "$CONF_DIR" >> "$PGDATA/postgresql.conf"
    info 'postgresql.conf에 include를 추가했다. 원본은 postgresql.conf.initdb-* 로 보관한다.'
  fi

  restorecon -Rv "$CONF_DIR" | sed 's/^/       /' || true
  ls -lZ "$CONF_DIR" | sed 's/^/       /'
  # 문맥이 etc_t로 남아 postgres가 읽지 못하면 semanage fcontext로 타입을 지정하고
  # restorecon 한다. setenforce 0 이나 SELinux 비활성화로 우회하지 않는다.
}

# ---------------------------------------------------------------------------
# tls: 자체 서명 인증서를 만든다. 개인 키는 이 VM 밖으로 내보내지 않는다.
# ---------------------------------------------------------------------------
step_tls() {
  log 'tls: 서버 인증서를 만든다'
  need_root
  install -d -o root -g postgres -m 0750 "$CONF_DIR"

  if [[ -f $CONF_DIR/server.crt && -f $CONF_DIR/server.key ]]; then
    info '인증서가 이미 있다. 새로 만들지 않는다.'
  else
    # CN과 SAN의 IP가 앱·Standby의 sslmode=verify-full 검증 대상이다.
    openssl req -new -x509 -days 365 -nodes -newkey rsa:2048 \
      -keyout "$CONF_DIR/server.key" -out "$CONF_DIR/server.crt" \
      -subj "/CN=${NODE_NAME}.lab" \
      -addext "subjectAltName=IP:${LAB_IP},DNS:${NODE_NAME}.lab"
  fi

  chown postgres:postgres "$CONF_DIR/server.key"
  chmod 0600 "$CONF_DIR/server.key"
  chown root:root "$CONF_DIR/server.crt"
  chmod 0644 "$CONF_DIR/server.crt"
  restorecon -v "$CONF_DIR/server.crt" "$CONF_DIR/server.key" || true

  info '인증서 내용'
  openssl x509 -in "$CONF_DIR/server.crt" -noout -subject -dates -ext subjectAltName | sed 's/^/       /'
  info '지문(APP01·APP02·DB02로 옮긴 뒤 같은 값인지 비교한다)'
  openssl x509 -in "$CONF_DIR/server.crt" -noout -fingerprint -sha256 | sed 's/^/       /'
  info '만료일을 운영 기록에 적는다. 자체 서명이라 갱신도 배포도 수동이다.'
  info "공개 인증서만 옮긴다: $CONF_DIR/server.crt. 개인 키 server.key 는 옮기지 않는다."
}

# ---------------------------------------------------------------------------
# hba: 접속 인증 규칙을 배치한다.
# ---------------------------------------------------------------------------
step_hba() {
  log 'hba: pg_hba.conf 를 배치한다'
  need_root
  detect_pg
  need_file "$FILES_DIR/pg_hba.vm.conf"
  [[ -f $PGDATA/pg_hba.conf ]] || die "$PGDATA/pg_hba.conf 가 없다. initdb 단계를 먼저 실행한다."

  if [[ ! -f $PGDATA/pg_hba.conf.initdb ]]; then
    cp -a "$PGDATA/pg_hba.conf" "$PGDATA/pg_hba.conf.initdb"
    info "initdb가 만든 원본을 $PGDATA/pg_hba.conf.initdb 로 보관했다."
  fi

  # 이 단계는 파일을 통째로 덮어쓴다. 마이그레이션 창을 열어 둔 상태에서 다시 실행하면
  # 임시 규칙이 사라지고 APP01의 Flyway가 막힌다. 창이 열려 있으면 먼저 알린다.
  if [[ -f $PGDATA/pg_hba.conf ]] && grep -q "$MIGRATE_TAG" "$PGDATA/pg_hba.conf"; then
    warn "마이그레이션 창이 열려 있다. 덮어쓰면 임시 규칙이 사라진다.
  덮어쓴 뒤에는 migrate-open 을 다시 실행하고, 이미 끝났으면 migrate-close 로 역할을 잠근다."
  fi
  install -o postgres -g postgres -m 0600 "$FILES_DIR/pg_hba.vm.conf" "$PGDATA/pg_hba.conf"
  restorecon -v "$PGDATA/pg_hba.conf" || true
  grep -vE '^\s*(#|$)' "$PGDATA/pg_hba.conf" | sed 's/^/       /' || true

  if systemctl is-active --quiet "$PG_UNIT"; then
    info '서비스가 떠 있으므로 설정을 다시 읽는다'
    systemctl reload "$PG_UNIT"
  fi
}

# ---------------------------------------------------------------------------
# start: 서비스를 부팅 등록하고 기동한다.
# ---------------------------------------------------------------------------
step_start() {
  log 'start: PostgreSQL을 기동한다'
  need_root
  detect_pg
  systemctl enable --now "$PG_UNIT"

  # 기동을 기다린다. 소켓이 열릴 때까지 최대 30초 본다.
  local i
  for i in $(seq 1 30); do
    if sudo -u postgres "$PG_BIN/pg_isready" -q; then break; fi
    sleep 1
  done
  sudo -u postgres "$PG_BIN/pg_isready" || die "기동하지 못했다. journalctl -u $PG_UNIT -n 50 을 확인한다."

  systemctl is-active "$PG_UNIT" | sed 's/^/       /' || true
  info '실효 설정'
  psql_postgres -c 'SHOW data_directory;' -c 'SHOW listen_addresses;' -c 'SHOW ssl;' \
    -c 'SHOW wal_level;' -c 'SHOW password_encryption;' -c 'SHOW synchronous_standby_names;' \
    | sed 's/^/       /'
}

# ---------------------------------------------------------------------------
# roles: 데이터베이스와 역할을 만든다. 비밀번호는 여기서 넣지 않는다.
# ---------------------------------------------------------------------------
step_roles() {
  log 'roles: 데이터베이스와 역할을 만든다'
  need_root
  detect_pg

  # 이름을 SQL 안에 그대로 적는다. 셸 확장을 막으려고 따옴표 heredoc을 쓰기 때문이다.
  # app_role 과 harness_role 은 Flyway V0가 만드는 NOLOGIN 그룹 롤이라 여기서 만들지 않는다.
  psql_postgres -d postgres -f - <<'SQL'
\echo '-- 이미 있으면 만들지 않는다. 앞의 SELECT가 행을 낼 때만 뒤의 명령이 실행된다.'

SELECT 'CREATE ROLE exchange_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'exchange_owner')\gexec

SELECT 'CREATE ROLE exchange_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'exchange_app')\gexec

SELECT 'CREATE ROLE replicator LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE'
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'replicator')\gexec

SELECT 'CREATE DATABASE exchange OWNER exchange_owner ENCODING ''UTF8'' LOCALE ''C.UTF-8'' TEMPLATE template0'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'exchange')\gexec

-- 누구나 붙을 수 있는 기본 권한을 회수하고 필요한 역할에만 다시 준다.
-- 회수만 하면 exchange_app이 접속하지 못하므로 CONNECT를 명시해서 준다.
REVOKE CONNECT ON DATABASE exchange FROM PUBLIC;
GRANT CONNECT ON DATABASE exchange TO exchange_owner;
GRANT CONNECT ON DATABASE exchange TO exchange_app;

\echo '-- 역할 권한 확인'
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication
  FROM pg_roles
 WHERE rolname IN ('exchange_owner','exchange_app','replicator')
 ORDER BY rolname;
SQL

  info '비밀번호는 아직 없다. passwords 단계에서 사람이 직접 넣는다.'
  info "$OWNER_ROLE 도 비밀번호를 받는다. 그 값이 없으면 APP01의 Flyway가 scram 인증에 실패한다."
}

# ---------------------------------------------------------------------------
# passwords: 대화형으로 비밀번호를 넣는다. 값은 화면에도 파일에도 남기지 않는다.
# ---------------------------------------------------------------------------
step_passwords() {
  log 'passwords: DB 계정 비밀번호를 넣는다'
  need_root
  detect_pg
  [[ -t 0 ]] || die '대화형 입력이 필요하다. 터미널에서 직접 실행한다.'

  info 'psql의 \password 는 입력을 화면에 표시하지 않고 암호화한 값만 서버로 보낸다.'
  info '명령 인자나 파일에 비밀번호를 적지 않는다. 값은 앱·DB02 설정에 옮길 때만 다시 쓴다.'
  info "대상은 세 역할이다: $OWNER_ROLE(마이그레이션), $APP_ROLE(거래), $REPL_ROLE(복제)."
  info "$OWNER_ROLE 의 값은 APP01의 Flyway가 쓴다. 세 값을 서로 다르게 정한다."

  local role
  for role in "$OWNER_ROLE" "$APP_ROLE" "$REPL_ROLE"; do
    printf '\n  -- %s 의 비밀번호를 두 번 입력한다 --\n' "$role"
    ( cd /tmp && sudo -u postgres psql -X -c "\\password $role" )
  done

  info '설정 여부만 확인한다(값은 보지 않는다)'
  psql_postgres -c "SELECT rolname, (rolpassword IS NOT NULL) AS has_password FROM pg_authid WHERE rolname IN ('$OWNER_ROLE','$APP_ROLE','$REPL_ROLE') ORDER BY rolname;" \
    | sed 's/^/       /'

  # 세 역할 중 하나라도 비어 있으면 여기서 멈춘다. 빈 상태로 다음 단계를 진행하면
  # 마이그레이션이나 복제가 인증 실패로만 드러난다.
  local missing
  missing=$(psql_postgres -qAt -c "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_authid WHERE rolname IN ('$OWNER_ROLE','$APP_ROLE','$REPL_ROLE') AND rolpassword IS NULL;")
  [[ -z $missing ]] || die "비밀번호가 없는 역할이 남았다: $missing
  이 단계를 다시 실행해 해당 역할의 값을 넣는다."

  info "다음은 migrate-open 이다. 그 단계가 $OWNER_ROLE 의 접속 경로를 열고, migrate-close 가 다시 잠근다."
}

# ---------------------------------------------------------------------------
# migrate-open / migrate-close: Flyway 마이그레이션 창을 여닫는다.
#
# APP01의 21-app-migrate.sh 는 exchange_owner 로 TCP 접속해 Flyway를 돌린다.
# 평상시 pg_hba.conf 에는 그 역할의 hostssl 줄이 없고, 끝난 뒤에는 역할 자체를
# NOLOGIN 으로 잠근다. 두 단계가 그 창을 열고 닫는다. 창을 열지 않은 상태에서
# 마이그레이션이 성공한 것처럼 적지 않는다.
# ---------------------------------------------------------------------------
hba_path() { psql_postgres -qAt -c 'SHOW hba_file;'; }

# pg_hba.conf 의 오류 개수를 센다. 0이 아니면 규칙이 적용되지 않는다.
hba_error_count() { psql_postgres -qAt -c 'SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL;'; }

step_migrate_open() {
  log 'migrate-open: 마이그레이션 창을 연다'
  need_root
  detect_pg
  systemctl is-active --quiet "$PG_UNIT" || die "$PG_UNIT 가 떠 있지 않다. start 단계를 먼저 실행한다."

  # 비밀번호가 없으면 규칙을 넣어도 scram 인증이 되지 않는다. 먼저 막는다.
  local has_pw
  has_pw=$(psql_postgres -qAt -c "SELECT (rolpassword IS NOT NULL) FROM pg_authid WHERE rolname = '$OWNER_ROLE';")
  [[ $has_pw == t ]] || die "$OWNER_ROLE 의 비밀번호를 확인하지 못했다(조회 결과: '${has_pw:-없음}').
  값이 f 면 passwords 단계를 실행하고, 비어 있으면 roles 단계부터 다시 확인한다.
  비밀번호 없이 규칙만 넣으면 Flyway가 scram 인증에서 막힌다."

  local hba backup
  hba=$(hba_path)
  [[ -n $hba && -f $hba ]] || die "pg_hba.conf 경로를 읽지 못했다: $hba"

  if grep -q "$MIGRATE_TAG" "$hba"; then
    info '마이그레이션 창이 이미 열려 있다. 규칙을 다시 넣지 않는다.'
  else
    backup="${hba}.before-migrate-$(date '+%Y%m%d%H%M%S')"
    cp -p "$hba" "$backup"
    info "원본을 $backup 에 보관했다."
    # 표식 줄 사이에 규칙을 넣는다. 닫을 때 이 구간만 지우면 되므로 되돌리기가 분명해진다.
    {
      printf '# %s BEGIN %s\n' "$MIGRATE_TAG" "$(date '+%Y-%m-%dT%H:%M:%S%z')"
      printf 'hostssl   %s        %s  %s/32        scram-sha-256\n' "$DB_NAME" "$OWNER_ROLE" "$APP01_IP"
      printf '# %s END\n' "$MIGRATE_TAG"
    } >> "$hba"
    chown postgres:postgres "$hba"
    chmod 0600 "$hba"

    # 형식 오류가 있으면 되돌린다. 깨진 HBA로 reload 하지 않는다.
    local errors
    errors=$(hba_error_count)
    if [[ $errors != 0 ]]; then
      cp -p "$backup" "$hba"
      die "HBA 형식 오류가 $errors 건이다. 원본으로 되돌렸다. 파일을 직접 확인한다."
    fi
    psql_postgres -c 'SELECT pg_reload_conf();' >/dev/null
    errors=$(hba_error_count)
    [[ $errors == 0 ]] || die 'reload 뒤에도 HBA 오류가 남아 있다. 파일을 직접 확인한다.'
  fi

  # 역할이 잠겨 있으면 푼다. migrate-close 가 다시 잠근다.
  psql_postgres -c "ALTER ROLE $OWNER_ROLE LOGIN;" | sed 's/^/       /'

  # 연 사실과 위치를 남긴다. 닫을 때 이 기록과 실제 파일을 함께 본다.
  local line_no
  line_no=$(grep -n "^hostssl.*$OWNER_ROLE" "$hba" | tail -n1 | cut -d: -f1 || true)
  install -d -o root -g root -m 0755 "$(dirname -- "$MIGRATE_MARK")"
  {
    printf 'opened_at=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    printf 'hba_file=%s\n' "$hba"
    printf 'rule_line=%s\n' "${line_no:-unknown}"
    printf 'rule=hostssl %s %s %s/32 scram-sha-256\n' "$DB_NAME" "$OWNER_ROLE" "$APP01_IP"
  } > "$MIGRATE_MARK"
  chmod 0644 "$MIGRATE_MARK"

  psql_postgres -c "SELECT line_number,type,database,user_name,address,auth_method FROM pg_hba_file_rules WHERE user_name::text LIKE '%${OWNER_ROLE}%';" \
    | sed 's/^/       /'
  cat <<EOF

       창을 열었다. 이제 APP01에서 마이그레이션을 실행한다.
         sudo /opt/vm-lab/scripts/21-app-migrate.sh
       끝나면 이 호스트에서 반드시 창을 닫는다.
         sudo $0 migrate-close
       닫기 전까지 APP01의 ${APP01_IP} 에서 ${OWNER_ROLE} 로 접속할 수 있다.
EOF
}

# ---------------------------------------------------------------------------
# grant-app-role: 마이그레이션이 만든 app_role 을 실행 계정에 붙인다.
# V0__roles.sql 주석이 "실제 LOGIN 사용자는 배포 환경 프로비저닝에서 별도 생성 후
# GRANT app_role 한다" 고 적었지만 그 단계가 어느 스크립트에도 없었다.
# app_role 은 마이그레이션이 만들므로 roles 단계에서는 붙일 수 없고 여기가 제자리다.
# 2026-09-22 VM 실습에서 이 누락 때문에 exchange_app 이 테이블을 0 개만 보았다.
# ---------------------------------------------------------------------------
step_grant_app_role() {
  log 'grant-app-role: 실행 계정에 app_role 을 부여한다'
  need_root
  detect_pg

  if ! psql_postgres -qAt -d "$DB_NAME" -c "select 1 from pg_roles where rolname='app_role';" | grep -q 1; then
    die 'app_role 이 없다. 마이그레이션을 먼저 적용한다.'
  fi

  psql_postgres -q -d "$DB_NAME" -c "GRANT app_role TO $APP_ROLE;"
  info "$APP_ROLE 에 app_role 을 부여했다."

  info '소속 확인'
  psql_postgres -d "$DB_NAME" -c "
    select r.rolname as 계정, string_agg(m.rolname, ', ') as 소속
    from pg_roles r
    join pg_auth_members am on am.member = r.oid
    join pg_roles m on m.oid = am.roleid
    where r.rolname = '$APP_ROLE' group by r.rolname;" | sed 's/^/       /'

  info '원장과 잔고 직접 DML 이 여전히 막히는지는 APP01 에서 확인한다.'
  info '  psql ... -c "insert into ledger_transactions default values;"  -> permission denied 여야 한다'
}

step_migrate_close() {
  log 'migrate-close: 마이그레이션 창을 닫는다'
  need_root
  detect_pg
  systemctl is-active --quiet "$PG_UNIT" || die "$PG_UNIT 가 떠 있지 않다. 창을 닫으려면 서비스가 떠 있어야 한다."

  local hba
  hba=$(hba_path)
  [[ -n $hba && -f $hba ]] || die "pg_hba.conf 경로를 읽지 못했다: $hba"

  if grep -q "$MIGRATE_TAG" "$hba"; then
    cp -p "$hba" "${hba}.before-close-$(date '+%Y%m%d%H%M%S')"
    # 표식 BEGIN 줄부터 END 줄까지를 지운다. 그 사이에 있는 임시 규칙이 같이 없어진다.
    sed -i "/# ${MIGRATE_TAG} BEGIN/,/# ${MIGRATE_TAG} END/d" "$hba"
    chown postgres:postgres "$hba"
    chmod 0600 "$hba"
    info '임시 규칙 구간을 지웠다.'
  else
    info "pg_hba.conf 에 $MIGRATE_TAG 구간이 없다. 지울 것이 없다."
  fi

  # 표식 없이 손으로 넣은 줄이 남아 있을 수 있다. 남으면 사람이 직접 확인한다.
  if grep -qE "^[^#]*$OWNER_ROLE" "$hba"; then
    warn "$hba 에 $OWNER_ROLE 규칙이 아직 남아 있다. 아래 줄을 확인하고 직접 지운다."
    grep -nE "^[^#]*$OWNER_ROLE" "$hba" | sed 's/^/       /'
  fi

  psql_postgres -c 'SELECT pg_reload_conf();' >/dev/null
  local errors
  errors=$(hba_error_count)
  [[ $errors == 0 ]] || die "reload 뒤 HBA 오류가 $errors 건이다. 파일을 직접 확인한다."

  # 규칙을 지운 뒤에 역할을 잠근다. 순서를 바꾸면 잠깐 열린 구간이 남는다.
  psql_postgres -c "ALTER ROLE $OWNER_ROLE NOLOGIN;" | sed 's/^/       /'

  if [[ -f $MIGRATE_MARK ]]; then
    printf 'closed_at=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$MIGRATE_MARK"
    mv "$MIGRATE_MARK" "${MIGRATE_MARK}.closed"
    info "$MIGRATE_MARK 를 ${MIGRATE_MARK}.closed 로 옮겼다. 기록은 남긴다."
  fi

  info '닫은 뒤 상태'
  psql_postgres -c "SELECT rolname, rolcanlogin FROM pg_roles WHERE rolname = '$OWNER_ROLE';" | sed 's/^/       /'
  psql_postgres -c 'SELECT line_number,type,database,user_name,address,auth_method FROM pg_hba_file_rules ORDER BY line_number;' \
    | sed 's/^/       /'
  info "APP01에서 $OWNER_ROLE 로 접속해 거부되는 것을 확인하고 그 화면을 증거로 남긴다."
}

# 창이 열린 채 남아 있는지 본다. verify 에서 쓴다.
v_migrate_window_closed() {
  local hba canlogin
  hba=$(hba_path)
  canlogin=$(psql_postgres -qAt -c "SELECT rolcanlogin FROM pg_roles WHERE rolname = '$OWNER_ROLE';")
  printf '%s 규칙: %s\n' "$OWNER_ROLE" "$(grep -cE "^[^#]*$OWNER_ROLE" "$hba" || true)"
  printf '%s rolcanlogin = %s\n' "$OWNER_ROLE" "$canlogin"
  printf '마이그레이션이 끝난 상태라면 규칙 0개, rolcanlogin = f 여야 한다.\n'
  [[ $canlogin == f ]] && ! grep -qE "^[^#]*$OWNER_ROLE" "$hba"
}

# ---------------------------------------------------------------------------
# monitor: 관측용 OS 계정과 DB 역할을 만든다.
# ---------------------------------------------------------------------------
step_monitor() {
  log 'monitor: 관측 계정을 만든다'
  need_root
  detect_pg
  need_file "$FILES_DIR/create-monitor-role.sql"

  if id failstop_monitor >/dev/null 2>&1; then
    info 'OS 계정 failstop_monitor 가 이미 있다.'
  else
    useradd --system --no-create-home --shell /sbin/nologin failstop_monitor
    info 'OS 계정 failstop_monitor 를 만들었다(로그인 불가).'
  fi

  # SQL 파일은 최초 설치용이라 역할이 이미 있으면 실패한다. 재실행하지 않고 권한을 본다.
  if psql_postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname = 'failstop_monitor'" | grep -q 1; then
    info 'DB 역할 failstop_monitor 가 이미 있다. SQL을 다시 실행하지 않고 권한만 확인한다.'
    psql_postgres -d "$DB_NAME" -c \
      "SELECT r.rolname AS member_of FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid JOIN pg_roles u ON u.oid = m.member WHERE u.rolname = 'failstop_monitor';" \
      | sed 's/^/       /'
  else
    psql_postgres -d "$DB_NAME" -f "$FILES_DIR/create-monitor-role.sql"
    info 'create-monitor-role.sql 을 적용했다. 역할은 WAL로 DB02에도 복제된다.'
  fi

  info 'peer 인증으로 붙는지 확인한다'
  psql_monitor -c 'SELECT current_user, pg_is_in_recovery();' | sed 's/^/       /'
  info '이 계정은 다른 세션의 질의까지 보는 통계 권한을 가진다. 일반 사용자 계정으로 쓰지 않는다.'
}

# ---------------------------------------------------------------------------
# exporters: node_exporter와 postgres_exporter를 설치한다.
# ---------------------------------------------------------------------------
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
  # 스크립트가 끝날 때 임시 폴더를 지운다. 중간에 멈춰도 남지 않는다.
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
  # 유닛 파일은 고치지 않고 드롭인으로 ExecStart만 덮어쓴다.
  local pg_base exec_line new_exec
  pg_base=${PG_UNIT%.service}
  exec_line=$(grep -m1 '^ExecStart=' /etc/systemd/system/node_exporter.service || true)
  new_exec=${exec_line//|postgresql|/|${pg_base}|}
  # postgres_exporter 자신의 상태도 같이 본다.
  if [[ $new_exec != *postgres_exporter* ]]; then
    new_exec=${new_exec//|node_exporter)/|node_exporter|postgres_exporter)}
  fi
  local ne_restart=false
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
    # 내용이 같으면 그대로 둔다. 매번 exporter를 재시작하지 않기 위해서다.
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

  install -d -m 0755 /etc/postgres_exporter
  printf 'PG_EXPORTER_ADDRESS=%s\n' "$LAB_IP" > /etc/postgres_exporter/listen.env
  chmod 0644 /etc/postgres_exporter/listen.env
  # 쿼리 파일은 상류 원본이다. 편집하지 않는다.
  install -o root -g root -m 0644 "$FILES_DIR/pg-queries.yaml" /etc/postgres_exporter/queries.yaml
  install -o root -g root -m 0644 "$FILES_DIR/postgres_exporter.service" /etc/systemd/system/postgres_exporter.service

  # 원본의 After=postgresql.service 는 이 VM의 유닛 이름과 다를 수 있다.
  # After= 를 빈 값으로 한 번 지우고 실제 이름으로 다시 적는다.
  install -d -m 0755 /etc/systemd/system/postgres_exporter.service.d
  {
    printf '# 원본의 After=postgresql.service 를 이 VM의 실제 유닛 이름으로 맞춘다.\n'
    printf '[Unit]\n'
    printf 'After=\n'
    printf 'After=network-online.target %s\n' "$PG_UNIT"
  } > /etc/systemd/system/postgres_exporter.service.d/10-unit-name.conf
  chmod 0644 /etc/systemd/system/postgres_exporter.service.d/10-unit-name.conf

  systemctl daemon-reload
  # 하나가 뜨지 못해도 여기서 멈추지 않는다. 상태는 아래와 verify 단계에서 그대로 보여 준다.
  systemctl enable --now node_exporter postgres_exporter \
    || warn 'exporter 기동에 실패했다. journalctl -u node_exporter -u postgres_exporter 를 확인한다.'
  # 이미 돌고 있던 node_exporter는 enable --now로 다시 뜨지 않는다. 드롭인을 바꿨을 때만 재시작한다.
  if [[ $ne_restart == true ]]; then
    info '드롭인이 바뀌었으므로 node_exporter를 다시 시작한다'
    systemctl restart node_exporter || warn 'node_exporter 재시작에 실패했다.'
  fi
  sleep 2
  systemctl is-active node_exporter postgres_exporter | sed 's/^/       /' || true

  info '로컬에서 지표 응답을 확인한다'
  curl -fsS "http://${LAB_IP}:9100/metrics" | head -n 1 | sed 's/^/       /' || warn 'node_exporter 응답이 없다.'
  curl -fsS "http://${LAB_IP}:9187/metrics" | grep -m1 '^pg_up' | sed 's/^/       /' || warn 'postgres_exporter 응답이 없다.'
  info 'postgres_exporter의 사용자 쿼리 옵션은 폐기 예정 기능이다. 버전을 올릴 때 호환성을 확인한다.'
}

# ---------------------------------------------------------------------------
# journald: 로그 보존 설정을 앱과 같게 맞춘다.
# ---------------------------------------------------------------------------
step_journald() {
  log 'journald: 로그 보존 설정을 배치한다'
  need_root
  need_file "$FILES_DIR/60-exchange-lab.conf"
  install -d -m 0755 /etc/systemd/journald.conf.d
  # 공통 절차가 같은 파일을 이미 놓았을 수 있다. 내용이 같으면 journald를 다시 흔들지 않는다.
  if cmp -s "$FILES_DIR/60-exchange-lab.conf" /etc/systemd/journald.conf.d/60-exchange-lab.conf; then
    info '같은 보존 설정이 이미 적용되어 있다. 재시작하지 않는다.'
  else
    install -o root -g root -m 0644 "$FILES_DIR/60-exchange-lab.conf" /etc/systemd/journald.conf.d/60-exchange-lab.conf
    systemctl restart systemd-journald
  fi
  journalctl --disk-usage | sed 's/^/       /'
  info '재부팅 뒤에도 보존되는지는 reboot 후에 따로 확인한다.'
}

# ---------------------------------------------------------------------------
# firewall: nftables 규칙을 적용한다. input 정책이 drop으로 바뀐다.
# ---------------------------------------------------------------------------
step_firewall() {
  log 'firewall: nftables 규칙을 적용한다'
  need_root
  command -v nft >/dev/null || die 'nft 명령이 없다. packages 단계를 먼저 실행한다.'

  warn '허용하지 않은 인바운드가 끊긴다. SSH가 막힐 경우를 대비해 VirtualBox 콘솔을 열어 둔다.'

  # firewalld가 살아 있으면 같은 후크를 두 도구가 건드린다. 규칙을 한 곳에서만 관리한다.
  if systemctl is-enabled firewalld >/dev/null 2>&1 || systemctl is-active --quiet firewalld; then
    info 'firewalld를 내리고 mask 한다'
    systemctl disable --now firewalld || true
    systemctl mask firewalld || true
  fi

  # 방화벽은 공통 기반 한 계통만 쓴다. 기반은 table inet vmlab 이고 input 정책이 drop이며
  # 22와 9100을 이미 열어 두었다. 이 단계는 그 위에 역할 포트만 얹는다.
  # 같은 후크에 테이블을 하나 더 세우는 단독 경로는 없앴다. 두 계통이 함께 서면
  # 어느 규칙이 걸렀는지 알기 어렵고 한쪽을 내릴 때 다른 쪽까지 사라진다.
  [[ -f /etc/vm-lab/firewall.nft ]] && systemctl list-unit-files vm-lab-firewall.service >/dev/null 2>&1 \
    || die '공통 기반(/etc/vm-lab/firewall.nft, vm-lab-firewall.service)이 없다.
  먼저 sudo /opt/vm-lab/scripts/00-common-setup.sh 를 적용한 뒤 이 단계를 다시 실행한다.
  이 스크립트는 별도 테이블을 세우지 않는다.'

  need_file "$FILES_DIR/db01-firewall.d.nft"
  info '공통 기반을 찾았다. /etc/vm-lab/firewall.d 에 역할 규칙을 더한다.'
  install -d -o root -g root -m 0755 /etc/vm-lab/firewall.d
  install -o root -g root -m 0600 "$FILES_DIR/db01-firewall.d.nft" /etc/vm-lab/firewall.d/30-db01.nft

  # 조각만 따로 검사할 수 없다. 기반 파일이 이 디렉터리를 include 하므로 기반을 검사한다.
  # 구문이 틀리면 방금 놓은 파일을 도로 치우고 멈춘다.
  if ! nft --check --file /etc/vm-lab/firewall.nft; then
    rm -f /etc/vm-lab/firewall.d/30-db01.nft
    die '규칙 구문이 맞지 않다. 역할 규칙 파일을 도로 치웠다. 기반 규칙은 그대로다.'
  fi
  info '구문 확인 통과'
  systemctl reload vm-lab-firewall.service || systemctl restart vm-lab-firewall.service
  { nft list chain inet vmlab input; nft list chain inet vmlab roles 2>/dev/null; } | sed 's/^/       /'

  info '허용한 출발지에서 접속되는지, 아닌 곳에서 막히는지 각각 확인한다(verify 단계 안내 참고).'
}

# ---------------------------------------------------------------------------
# verify: 상태를 확인한다. 실행 결과를 그대로 출력하며 통과 여부를 꾸미지 않는다.
# ---------------------------------------------------------------------------
check() {
  local title=$1; shift
  printf '\n  -- %s\n' "$title"
  if "$@" 2>&1 | sed 's/^/     /'; then
    :
  else
    printf '     [실패] 위 명령이 0이 아닌 값으로 끝났다. 출력을 그대로 기록한다.\n'
  fi
}

# 세 역할의 비밀번호 유무를 출력하고, 하나라도 비어 있으면 0이 아닌 값을 낸다.
v_role_passwords() {
  psql_postgres -c "SELECT rolname,(rolpassword IS NOT NULL) AS has_password FROM pg_authid WHERE rolname IN ('$OWNER_ROLE','$APP_ROLE','$REPL_ROLE') ORDER BY rolname;"
  local missing
  missing=$(psql_postgres -qAt -c "SELECT count(*) FROM pg_authid WHERE rolname IN ('$OWNER_ROLE','$APP_ROLE','$REPL_ROLE') AND rolpassword IS NULL;")
  local created
  created=$(psql_postgres -qAt -c "SELECT count(*) FROM pg_authid WHERE rolname IN ('$OWNER_ROLE','$APP_ROLE','$REPL_ROLE');")
  printf '역할 %s개 중 비밀번호 없는 역할 %s개\n' "$created" "$missing"
  [[ $created == 3 && $missing == 0 ]]
}

step_verify() {
  log 'verify: 상태를 확인한다'
  need_root
  detect_pg
  show_pg_paths

  check '디스크와 마운트' lsblk -f
  check '마운트 지점' findmnt "$DATA_MOUNT"
  check '용량' df -h "$DATA_MOUNT"
  check '데이터 경로 권한과 SELinux 문맥' ls -ldZ "$DATA_MOUNT" "$PGDATA"
  check 'SELinux 상태' getenforce
  # 방화벽은 공통 기반 한 계통이다. 유닛 이름도 하나뿐이다.
  check '서비스 상태' systemctl is-active "$PG_UNIT" node_exporter postgres_exporter vm-lab-firewall chronyd sshd
  check '실효 설정' psql_postgres -c 'SHOW data_directory;' -c 'SHOW listen_addresses;' -c 'SHOW ssl;' -c 'SHOW wal_level;' -c 'SHOW password_encryption;' -c 'SHOW synchronous_standby_names;'
  check '공통 설정 해시' sha256sum "$CONF_DIR/exchange.conf"
  check 'HBA 오류 개수(0이어야 한다)' psql_postgres -c 'SELECT count(*) AS hba_errors FROM pg_hba_file_rules WHERE error IS NOT NULL;'
  check 'HBA 규칙 목록' psql_postgres -c 'SELECT line_number,type,database,user_name,address,auth_method FROM pg_hba_file_rules ORDER BY line_number;'
  check '역할 권한' psql_postgres -c "SELECT rolname,rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,rolreplication FROM pg_roles WHERE rolname IN ('$OWNER_ROLE','$APP_ROLE','$REPL_ROLE','failstop_monitor') ORDER BY rolname;"
  # 세 역할 모두 has_password 가 t 여야 한다. 하나라도 f 면 마이그레이션이나 복제가
  # scram 인증에서 막힌다. rolcanlogin 만 보면 이 구멍이 드러나지 않는다.
  check '비밀번호 설정 여부(셋 다 t 여야 한다)' v_role_passwords
  check '마이그레이션 창(닫혀 있어야 한다)' v_migrate_window_closed
  # 슬롯은 쓰지 않기로 했다. 목록이 비어 있는 것이 정상이다.
  check '복제 슬롯(비어 있어야 한다)' psql_postgres -c 'SELECT slot_name,slot_type,active,wal_status FROM pg_replication_slots;'
  check '복제 상태(DB02 연결 전에는 0행)' psql_postgres -c 'SELECT client_addr,state,sync_state FROM pg_stat_replication;'
  check '관측 계정 peer 접속' psql_monitor -c 'SELECT current_user, pg_is_in_recovery();'
  check '수신 주소(0.0.0.0 바인딩이 없어야 한다)' ss -lntp
  check '인증서' openssl x509 -in "$CONF_DIR/server.crt" -noout -subject -dates -ext subjectAltName
  check '방화벽 규칙(공통 기반 + DB01 역할 규칙)' nft list chain inet vmlab input
  check '최근 기동 로그' journalctl -u "$PG_UNIT" --since '-10min' --no-pager
  # ausearch는 기록이 없으면 0이 아닌 값을 낸다. 없는 것이 정상이므로 메시지로 바꿔 출력한다.
  check 'SELinux 거부 기록(있으면 문맥을 고친다)' \
    sh -c 'ausearch -m AVC -ts recent 2>&1 || echo "AVC 기록이 없거나 ausearch가 없다"'

  cat <<EOF

  여기까지는 DB01 안에서 볼 수 있는 것만 확인했다. 아래는 다른 노드에서 해야 한다.
  결과를 증거로 남기고, 실행하지 않은 항목은 실행하지 않았다고 적는다.

  APP01에서 (앱 계정 접속과 TLS 검증)
    psql "host=${LAB_IP} port=5432 dbname=${DB_NAME} user=${APP_ROLE} sslmode=verify-full sslrootcert=/etc/exchange/db01.crt" -c 'SELECT 1;'
  접속 중에 DB01에서
    sudo -u postgres psql -X -P pager=off -c "SELECT a.usename,a.client_addr,s.ssl,s.version,s.cipher FROM pg_stat_ssl s JOIN pg_stat_activity a USING (pid) WHERE a.client_addr IS NOT NULL;"

  APP01에서 (마이그레이션 계정은 창을 닫은 뒤 막혀야 한다)
    psql "host=${LAB_IP} port=5432 dbname=${DB_NAME} user=${OWNER_ROLE} sslmode=verify-full sslrootcert=/etc/exchange/db01.crt" -c 'SELECT 1;'
      <- 거부돼야 한다. 성공하면 migrate-close 를 실행하지 않은 것이다.

  OPS01에서 (지표는 되고 DB 포트는 막혀야 한다)
    curl -s http://${LAB_IP}:9100/metrics | head -1
    curl -s http://${LAB_IP}:9187/metrics | grep -m1 '^pg_up'
    nc -vz ${LAB_IP} 5432        <- 실패해야 한다

  EDGE01 등 허용하지 않은 노드에서
    nc -vz ${LAB_IP} 9187        <- 실패해야 한다

  차단 기록 확인(DB01에서)
    공통 기반 table inet vmlab 에는 log prefix "vmlab-drop: " 규칙이 있다.
    차단된 연결은 journalctl -k --grep 'vmlab-drop' 으로 본다. 분당 5건으로 제한하므로
    로그 건수를 차단 총량으로 읽지 않는다. 총량은 '기본 차단 도달' 카운터로 본다.
    역할 규칙은 jump roles 로 로그보다 먼저 판정되므로 허용된 연결은 이 로그에 남지 않는다.

  재부팅 확인
    sudo systemctl reboot 뒤 다시 접속해서 이 verify 단계를 한 번 더 실행한다.

EOF
}

# ---------------------------------------------------------------------------
# 단계 선택
# ---------------------------------------------------------------------------
# verify 는 all 에 넣지 않는다. 비밀번호가 없는 상태에서 먼저 판정을 내리면
# 그 출력이 통과한 증거처럼 남는다. 순서는 all -> passwords -> migrate-open ->
# (APP01에서 마이그레이션) -> migrate-close -> verify 다.
ALL_STEPS=(preflight disk packages initdb config tls hba start roles monitor exporters journald firewall)

usage() {
  cat <<EOF
사용법: sudo $0 <단계> [단계...]

  all            아래 단계를 순서대로 실행한다
                 ${ALL_STEPS[*]}

  passwords      대화형으로 DB 비밀번호를 넣는다. 대상은 세 역할이다.
                 exchange_owner, exchange_app, replicator. all 에는 들어 있지 않다.
  migrate-open   pg_hba.conf 에 exchange_owner 임시 규칙을 넣고 역할을 LOGIN 으로 바꾼다.
  migrate-close  그 규칙을 지우고 exchange_owner 를 NOLOGIN 으로 잠근다.
  verify         상태를 확인한다. all 에는 들어 있지 않다.

실행 순서
  1) sudo $0 all 2>&1 | tee ~/db01-build.log
  2) sudo $0 passwords
  3) sudo $0 migrate-open
  4) APP01에서 sudo /opt/vm-lab/scripts/21-app-migrate.sh
  5) sudo $0 migrate-close
  6) sudo $0 verify

  3)을 건너뛰면 APP01의 Flyway가 pg_hba 에서 막힌다. 5)를 건너뛰면
  마이그레이션 계정의 접속 경로가 열린 채로 남는다.
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
  for step in "${steps[@]}"; do
    case "$step" in
      preflight|disk|packages|initdb|config|tls|hba|start|roles|passwords|monitor|exporters|journald|firewall|verify)
        "step_$step" ;;
      migrate-open)  step_migrate_open ;;
      migrate-close) step_migrate_close ;;
      grant-app-role) step_grant_app_role ;;
      -h|--help|help) usage; exit 0 ;;
      *) usage; die "모르는 단계: $step" ;;
    esac
  done

  log '끝났다. 실행하지 않은 확인 항목을 통과했다고 적지 않는다.'
}

main "$@"
