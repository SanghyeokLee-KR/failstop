#!/usr/bin/env bash
#
# 별도 백업 저장소의 격리 복원 시험. DB02에서 root로 실행한다.
# 배치 위치는 /opt/vm-lab/bin/db02-restore-check.sh 이며
# exchange-backup-verify.service 가 --latest 로 호출한다.
#
# 무엇을 하는가
#   DB01이 만든 백업 아카이브를 받아, 외부 접속이 없는 임시 PostgreSQL 클러스터에
#   복원하고, 원본과 건수를 비교하고, 불변식으로 판정한 뒤 클러스터를 내린다.
#
# 무엇을 하지 않는가
#   아카이브를 만들지 않는다. 그것은 DB01의 몫이다.
#   failstop 저장소의 backup-restore-check.py 는 pg_is_in_recovery()='f' 를 요구하므로
#   Standby인 DB02에서는 실행되지 않는다. 이 스크립트는 그 파일의 복원 시험 기법만
#   그대로 옮겼다. initdb로 임시 클러스터 생성, listen_addresses 비움, port 55432,
#   unix 소켓만 사용, pg_restore --exit-on-error, 원본과 복원 건수 비교,
#   불변식 CSV 판정, 끝나면 pg_ctl stop 이다.
#
#   DB02의 복제본 PGDATA는 건드리지 않는다. 임시 클러스터는 TCP를 전혀 열지 않는다.
#
# 복제본은 삭제나 오입력까지 보호하는 백업이 아니다. 이 시험은 그 둘을 나누어
# 확인하려는 것이다. 다만 이 백업 저장소는 같은 작업 PC의 가상 디스크 위에 있어
# PC 고장이나 디스크 손상에는 대응하지 못한다. 실제 오프사이트 백업으로 적지 않는다.
#
# 사용법
#   sudo /opt/vm-lab/bin/db02-restore-check.sh --latest
#   sudo /opt/vm-lab/bin/db02-restore-check.sh --id 20261001T031500123456Z
#
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

PGBACKUP=${PGBACKUP:-/srv/pgbackup}
SUMMARY=${SUMMARY:-/opt/vm-lab/build-files/db/summary.sql}
TEST_PORT=55432

log()  { printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
info() { printf '       %s\n' "$*"; }
die()  { printf '\n  [중단] %s\n' "$*" >&2; exit 1; }

[[ ${EUID} -eq 0 ]] || die '이 스크립트는 sudo로 실행한다.'
[[ -f $SUMMARY ]] || die "불변식 질의가 없다: $SUMMARY
  작업 PC의 build 폴더에서 keys/ 를 뺀 나머지를 /opt/vm-lab 로 복사했는지 확인한다.
  관리 개인키는 VM으로 옮기지 않는다. 예:
    rsync -a --delete --exclude 'keys/' <build 경로>/ ops@192.168.56.32:/home/ops/build/
  원본은 failstop 저장소의 harness/sql/summary.sql 이다."

# PostgreSQL 실행 경로를 추측하지 않는다. PGDG 패키지는 /usr/pgsql-17/bin 을 쓴다.
if [[ -x /usr/pgsql-17/bin/initdb ]]; then
  PG_BIN=/usr/pgsql-17/bin
elif command -v initdb >/dev/null 2>&1; then
  PG_BIN=$(dirname -- "$(command -v initdb)")
else
  die 'initdb를 찾지 못했다. PostgreSQL 설치를 확인한다.'
fi

# --- 대상 아카이브 고르기 ---------------------------------------------------
incoming=
case "${1:-}" in
  --latest)
    incoming=$(ls -1dt "$PGBACKUP"/incoming/*/ 2>/dev/null | head -n 1 || true)
    [[ -n $incoming ]] || die "수신한 아카이브가 없다: $PGBACKUP/incoming"
    incoming=${incoming%/}
    ;;
  --id)
    [[ -n ${2:-} ]] || die '사용법: db02-restore-check.sh --id <백업 ID>'
    incoming=$PGBACKUP/incoming/$2
    ;;
  *)
    die '사용법: db02-restore-check.sh --latest | --id <백업 ID>'
    ;;
esac

backup_id=$(basename -- "$incoming")
archive=$incoming/exchange.dump
source_json=$incoming/result.json
[[ -d $incoming && ! -L $incoming ]] || die "수신 디렉터리가 없다: $incoming"
[[ -f $archive ]] || die "아카이브가 없다: $archive"
[[ -f $source_json ]] || die "DB01의 결과 JSON이 없다: $source_json
  backup-restore-check.py 가 남긴 result.json 을 함께 받아야 건수를 비교할 수 있다."

log "복원 시험 대상: $backup_id"

# --- 무결성 대조 ------------------------------------------------------------
# DB01이 기록한 SHA-256과 대조한다. 전송 중 손상과 잘린 파일을 여기서 거른다.
expected=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["sha256"])' "$source_json")
actual=$(sha256sum "$archive" | awk '{print $1}')
[[ $expected == "$actual" ]] || die "DB01 결과 JSON의 SHA-256과 다르다.
  기대 $expected
  실제 $actual
  아카이브를 다시 받는다."
info "sha256 일치: $actual"

# --- 작업 공간 --------------------------------------------------------------
work=$PGBACKUP/restore/$backup_id
data=$work/data
sock=$work/socket
if [[ -e $data ]]; then
  die "이미 복원 시험 디렉터리가 있다: $data
  기존 결과를 확인하고 옮긴 뒤 다시 실행한다. 자동으로 지우지 않는다."
fi

# 복원본은 원본과 비슷한 크기가 되고 임시 클러스터도 자리를 쓴다.
archive_bytes=$(stat -c %s "$archive")
free_bytes=$(df -P --block-size=1 "$PGBACKUP" | awk 'NR==2 {print $4}')
if (( free_bytes < archive_bytes * 6 )); then
  die "백업 저장소의 여유 공간이 부족하다. 아카이브 $archive_bytes 바이트, 여유 $free_bytes 바이트."
fi

install -d -o postgres -g postgres -m 0700 "$work" "$sock"
restorecon -R "$work" 2>/dev/null || true

running=0
cleanup() {
  if [[ $running == 1 ]]; then
    runuser -u postgres -- "$PG_BIN/pg_ctl" -D "$data" -m fast -w stop || true
  fi
}
trap cleanup EXIT

# --- 임시 클러스터 ----------------------------------------------------------
log '임시 클러스터를 만든다'
runuser -u postgres -- "$PG_BIN/initdb" -D "$data" \
  --auth-local=peer --auth-host=reject --encoding=UTF8 --locale=C.UTF-8

# TCP를 열지 않는다. 소켓 디렉터리는 백업 저장소 하위의 0700 디렉터리다.
# ssl도 끈다. 이 클러스터에는 인증서가 없고 밖으로 열 생각도 없다.
cat >> "$data/postgresql.conf" <<CONF

# 격리 복원 시험 전용. 외부 접속 경로를 만들지 않는다.
listen_addresses=''
port=$TEST_PORT
shared_buffers='32MB'
max_connections=10
autovacuum=off
ssl=off
unix_socket_directories='$sock'
CONF
chown postgres:postgres "$data/postgresql.conf"

runuser -u postgres -- "$PG_BIN/pg_ctl" -D "$data" -l "$work/restore-server.log" -w start
running=1

# 설정이 실제로 먹었는지 본다. 기대한 대로 되었다고 가정하지 않는다.
if ss -lnt | grep -q ":$TEST_PORT"; then
  die "임시 클러스터가 TCP $TEST_PORT 를 열었다. postgresql.conf 를 확인한다."
fi
info "TCP $TEST_PORT 리스너 없음을 확인했다"

# --- 복원 ------------------------------------------------------------------
log '복원한다'
runuser -u postgres -- "$PG_BIN/createdb" -h "$sock" -p "$TEST_PORT" exchange
runuser -u postgres -- "$PG_BIN/pg_restore" --exit-on-error --no-owner --no-acl \
  -h "$sock" -p "$TEST_PORT" -d exchange "$archive"

counts_sql="SELECT json_build_object(
'commands',(SELECT count(*) FROM command_journal),
'events',(SELECT count(*) FROM event_journal),
'trades',(SELECT count(*) FROM trades),
'ledger_entries',(SELECT count(*) FROM ledger_entries));"
restored=$(runuser -u postgres -- "$PG_BIN/psql" -X -qAt -v ON_ERROR_STOP=1 \
  -h "$sock" -p "$TEST_PORT" -d exchange -c "$counts_sql")

# 불변식 판정. summary.sql 은 psql 메타명령(\gset, \if)을 쓰므로 -f 로만 실행한다.
log '불변식을 판정한다'
csv=$work/invariants.csv
runuser -u postgres -- "$PG_BIN/psql" -X -q --csv -v ON_ERROR_STOP=1 \
  -h "$sock" -p "$TEST_PORT" -d exchange -f "$SUMMARY" > "$csv"

runuser -u postgres -- "$PG_BIN/pg_ctl" -D "$data" -m fast -w stop
running=0

# --- 판정 ------------------------------------------------------------------
python3 - "$source_json" "$restored" "$csv" "$work" "$backup_id" "$actual" <<'PY'
import csv, datetime as dt, json, os, sys

source_path, restored_raw, csv_path, work, backup_id, digest = sys.argv[1:7]
source = json.load(open(source_path, encoding='utf-8'))
restored = json.loads(restored_raw)
source_counts = source.get('source_counts')
rows = list(csv.DictReader(open(csv_path, encoding='utf-8')))

problems = []
if not source_counts:
    problems.append('DB01 결과 JSON에 source_counts가 없다')
elif source_counts != restored:
    problems.append('원본과 복원 건수가 다르다')
# summary.sql은 C01부터 C31까지 낸다(C30은 ADR 0034 S7). 행이 모자라면 일부 판정이 사라진 것이다.
# summary.sql 판정 어휘(하네스 검증설계 §4-1, ADR 0034 S3·S7). N/A 는 C29·C30·C31 만 낼 수 있고 통과로도 실패로도 세지 않는다.
VERDICTS = ('PASS', 'FAIL', 'SKIP', 'N/A')
bad_na = [r.get('code') for r in rows if r.get('verdict') == 'N/A' and r.get('code') not in ('C29', 'C30', 'C31')]
if len(rows) < 28 or any(r.get('verdict') not in VERDICTS for r in rows) or bad_na:
    problems.append('정합성 검사 결과가 불완전하다')
failed = [r['code'] for r in rows
          if r.get('severity') in ('CRITICAL', 'HIGH') and r.get('verdict') in ('FAIL', 'SKIP')]
not_applicable = [r['code'] for r in rows if r.get('verdict') == 'N/A']
if failed:
    problems.append('필수 불변식 실패: ' + ','.join(failed))

result = {
    'backup_id': backup_id,
    'archive_sha256': digest,
    'source_counts': source_counts,
    'restored_counts': restored,
    'invariant_count': len(rows),
    'failed_invariants': failed,
    'not_applicable': not_applicable,
    'test_cluster_stopped': not os.path.exists(os.path.join(work, 'data', 'postmaster.pid')),
    'tcp_listener_opened': False,
    'status': 'PASS' if not problems else 'FAIL',
    'problems': problems,
    'completed_at': dt.datetime.now(dt.timezone.utc).isoformat(),
    'scope': '복원본의 읽기와 불변식 판정이다. 실제 장애의 유실량 판정이 아니다.',
}
with open(os.path.join(work, 'result.json'), 'w', encoding='utf-8') as handle:
    json.dump(result, handle, ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False, indent=2))
if problems:
    raise SystemExit(1)
PY

chown postgres:postgres "$work/result.json" "$csv"
log "결과: $work/result.json"
