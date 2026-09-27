#!/bin/sh
# 로컬 MSP 시연용 DB 백업 작업. harness/docker-compose.demo-obs.yml 의 chaos-db-backup 컨테이너가 실행한다.
#
# 한 번의 백업은 다음을 모두 통과해야 완료다.
#   1. PGHOST(대기 DB chaos-pg-standby)의 exchange DB 를 pg_dump -Fc 로 받는다. 재생 충돌로 끊기면 세 번까지 다시 한다.
#   2. pg_restore --list 로 목차를 읽고 TABLE DATA 항목이 하나 이상인지 본다(학교 db-backup.py 의 논리 덤프 검사와 같은 기준).
#   3. 완료 기록(COMPLETE)을 쓴 뒤 작업 폴더를 백업 ID 이름으로 옮긴다. 기록이 없는 폴더는 완료로 세지 않는다.
# 성공 시각은 완료한 백업의 기록에서 읽어 node_exporter textfile 로 낸다. 실행하지 않은 백업의 시각을 만들지 않는다.
# 컨테이너를 다시 띄워도 이미 있는 완료 백업의 시각을 그대로 다시 낸다.
#
# 학교 AWS 의 deploy/linux/postgresql/backup/db-backup.py 와 다른 점. 그쪽은 primary 의 물리 베이스백업을 받고
# pg_verifybackup 으로 매니페스트·WAL 까지 검증해 failstop_db_backup_last_verify_success 를 낸다. 여기는 논리 덤프라
# 그 지표를 내지 않는다. 목차 확인은 복원 검증이 아니다. 실제 복원·불변식 검사는 별도 절차다.
# 지표 이름은 db-backup.py 와 같게 두고, 옛 이름(failstop_backup_*)도 같은 값으로 낸다. MSP 복제·복구 화면이 옛 이름을 읽는다.
#
# 주기는 BACKUP_INTERVAL_SECONDS(기본 6시간)다. 기동할 때 가장 최근 완료 백업이 주기보다 오래됐으면 바로 한 번 받고,
# 아니면 남은 시간만큼 기다린다. 컨테이너를 재생성할 때마다 1GB 넘는 덤프를 다시 받지 않게 하려는 것이다.
#
# 원천 고르기(BACKUP_HOSTS). Patroni 판(harness/docker-compose.demo-patroni.yml)은 어느 노드가 Standby 인지 전환마다 바뀐다.
# BACKUP_HOSTS 에 공백으로 노드를 적으면 백업마다 pg_is_in_recovery() 가 참인 첫 노드(Standby)에서 받고, Standby 가 없으면
# 응답하는 첫 노드(Primary)에서 받는다. 비우면 종전처럼 PGHOST 하나다. pg_dump 는 일반 연결이라 복제 연결이 아니다.
# 원천 클러스터가 바뀌면(pg_control_system() 의 system_identifier 가 가장 최근 완료 백업과 다르면) 주기를 기다리지 않고 바로
# 받는다. 다른 클러스터의 백업을 이 클러스터의 백업으로 세지 않게 하려는 것이다(2026-09-27 chaos-pg 에서 Patroni 로 옮김).
set -u
umask 022

BACKUP_DIR=/backups
TEXTFILE_DIR=/textfile
INTERVAL=${BACKUP_INTERVAL_SECONDS:-21600}
KEEP=${BACKUP_KEEP:-2}
METRIC=failstop_db_backup
LEGACY=failstop_backup

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

trap 'log "중지 신호를 받았다"; exit 0' TERM INT

case "$INTERVAL" in ''|*[!0-9]*) log "BACKUP_INTERVAL_SECONDS 가 정수가 아니다"; exit 1;; esac
case "$KEEP" in ''|0|*[!0-9]*) log "BACKUP_KEEP 은 1 이상 정수여야 한다"; exit 1;; esac

complete_backups() {
    # 이름이 백업 ID 모양이고 완료 기록이 있는 폴더만, 최신부터.
    for d in $(ls -1 "$BACKUP_DIR" 2>/dev/null | grep -E '^[0-9]{8}T[0-9]{6}Z$' | sort -r); do
        [ -f "$BACKUP_DIR/$d/COMPLETE" ] && echo "$d"
    done
}

field() { sed -n "s/^$2=//p" "$BACKUP_DIR/$1/COMPLETE" | head -n 1; }

# 원천 노드를 골라 PGHOST 에 넣는다. 성공하면 0, 어느 노드도 답하지 않으면 1.
pick_source() {
    [ -n "${BACKUP_HOSTS:-}" ] || return 0
    fallback=""
    for h in $BACKUP_HOSTS; do
        r=$(PGHOST="$h" PGCONNECT_TIMEOUT=5 psql -X -At --no-password -c 'SELECT pg_is_in_recovery()' 2>/dev/null) || continue
        if [ "$r" = "t" ]; then
            PGHOST=$h; export PGHOST
            return 0
        fi
        [ -n "$fallback" ] || fallback=$h
    done
    if [ -n "$fallback" ]; then
        PGHOST=$fallback; export PGHOST
        return 0
    fi
    return 1
}

system_id() {
    PGCONNECT_TIMEOUT=5 psql -X -At --no-password -c 'SELECT system_identifier FROM pg_control_system()' 2>/dev/null
}

# $1 = 마지막 실행 성공(1/0), 비우면 이번 기동에서 아직 실행하지 않았다는 뜻이라 실행 지표를 내지 않는다.
# $2 = 마지막 실행이 끝난 시각
write_metrics() {
    run_success=$1
    run_ts=$2
    names=$(complete_backups)
    count=$(printf '%s\n' "$names" | grep -c . || true)
    newest=$(printf '%s\n' "$names" | head -n 1)
    tmp=$(mktemp "$TEXTFILE_DIR/.${METRIC}.XXXXXX") || return 1
    {
        echo "# HELP ${METRIC}_offsite_configured 외부 보관 설정 여부. 로컬 시연 백업은 외부 보관이 없다"
        echo "# TYPE ${METRIC}_offsite_configured gauge"
        echo "${METRIC}_offsite_configured 0"
        echo "# HELP ${METRIC}_local_backups 이 호스트에 남은 완료 백업 수(논리 덤프, 목차 확인 통과)"
        echo "# TYPE ${METRIC}_local_backups gauge"
        echo "${METRIC}_local_backups ${count}"
        if [ -n "$run_success" ]; then
            echo "# HELP ${METRIC}_last_run_timestamp_seconds 마지막 실행이 끝난 시각"
            echo "# TYPE ${METRIC}_last_run_timestamp_seconds gauge"
            echo "${METRIC}_last_run_timestamp_seconds ${run_ts}"
            echo "# HELP ${METRIC}_last_run_success 마지막 실행이 덤프·목차 확인·완료 기록까지 끝났으면 1"
            echo "# TYPE ${METRIC}_last_run_success gauge"
            echo "${METRIC}_last_run_success ${run_success}"
            echo "# HELP ${LEGACY}_last_run_success ${METRIC}_last_run_success 와 같은 값(옛 이름)"
            echo "# TYPE ${LEGACY}_last_run_success gauge"
            echo "${LEGACY}_last_run_success ${run_success}"
        fi
        if [ -n "$newest" ]; then
            completed=$(field "$newest" completed_epoch)
            bytes=$(field "$newest" bytes)
            echo "# HELP ${METRIC}_last_success_timestamp_seconds 가장 최근 완료 백업의 완료 시각"
            echo "# TYPE ${METRIC}_last_success_timestamp_seconds gauge"
            echo "${METRIC}_last_success_timestamp_seconds ${completed}"
            echo "# HELP ${METRIC}_last_success_bytes 가장 최근 완료 백업의 덤프 파일 크기"
            echo "# TYPE ${METRIC}_last_success_bytes gauge"
            echo "${METRIC}_last_success_bytes ${bytes}"
            echo "# HELP ${LEGACY}_last_success_timestamp_seconds ${METRIC}_last_success_timestamp_seconds 와 같은 값(옛 이름)"
            echo "# TYPE ${LEGACY}_last_success_timestamp_seconds gauge"
            echo "${LEGACY}_last_success_timestamp_seconds ${completed}"
        fi
    } > "$tmp"
    chmod 0644 "$tmp"
    # node_exporter 가 쓰는 중인 파일을 읽지 않게 같은 디렉터리 안에서 이름만 바꾼다(.prom 이 아닌 임시 이름).
    mv -f "$tmp" "$TEXTFILE_DIR/${METRIC}.prom"
}

prune() {
    n=0
    for d in $(complete_backups); do
        n=$((n + 1))
        if [ "$n" -gt "$KEEP" ]; then
            rm -rf "${BACKUP_DIR:?}/$d"
            log "보존 개수 ${KEEP}개를 넘어 옛 백업 $d 를 지웠다"
        fi
    done
}

run_backup() {
    if ! pick_source; then
        log "BACKUP_HOSTS(${BACKUP_HOSTS:-}) 가운데 응답하는 DB 가 없다"
        return 1
    fi
    id=$(date -u +%Y%m%dT%H%M%SZ)
    work="$BACKUP_DIR/.partial-$id"
    rm -rf "$BACKUP_DIR"/.partial-*
    mkdir -p "$work" || return 1
    started=$(date -u +%s)
    sysid=$(system_id)
    log "백업 $id 시작: ${PGHOST}:${PGPORT:-5432}/${PGDATABASE}"
    attempt=1
    while :; do
        if pg_dump --no-password -Fc -Z 1 -f "$work/exchange.dump" 2> "$work/pg_dump.err"; then
            break
        fi
        log "pg_dump 실패($attempt 회): $(tail -n 3 "$work/pg_dump.err" | tr '\n' ' ')"
        if [ "$attempt" -ge 3 ]; then
            return 1
        fi
        attempt=$((attempt + 1))
        sleep 10 & wait $!
    done
    if ! pg_restore --list "$work/exchange.dump" > "$work/toc.txt" 2> "$work/pg_restore.err"; then
        log "pg_restore --list 실패: $(tail -n 3 "$work/pg_restore.err" | tr '\n' ' ')"
        return 1
    fi
    entries=$(grep -c ' TABLE DATA ' "$work/toc.txt" || true)
    if [ "${entries:-0}" -lt 1 ]; then
        log "덤프 목차에 테이블 데이터가 없다"
        return 1
    fi
    in_recovery=$(psql -X -At --no-password -c 'SELECT pg_is_in_recovery()' 2>/dev/null || echo unknown)
    bytes=$(stat -c %s "$work/exchange.dump")
    completed=$(date -u +%s)
    {
        echo "id=$id"
        echo "started_epoch=$started"
        echo "completed_epoch=$completed"
        echo "bytes=$bytes"
        echo "table_data_entries=$entries"
        echo "attempts=$attempt"
        echo "source=${PGHOST}:${PGPORT:-5432}/${PGDATABASE}"
        echo "source_in_recovery=$in_recovery"
        echo "system_identifier=${sysid:-unknown}"
        echo "format=pg_dump custom (-Fc -Z 1)"
    } > "$work/COMPLETE.tmp"
    mv -f "$work/COMPLETE.tmp" "$work/COMPLETE"
    rm -f "$work/pg_dump.err" "$work/pg_restore.err"
    mv "$work" "$BACKUP_DIR/$id" || return 1
    log "백업 $id 완료: ${bytes} bytes, TABLE DATA ${entries}개, ${attempt}회째, 원천 in_recovery=${in_recovery}, $((completed - started))초"
    prune
    return 0
}

mkdir -p "$BACKUP_DIR" "$TEXTFILE_DIR"
write_metrics "" ""

while :; do
    newest=$(complete_backups | head -n 1)
    now=$(date -u +%s)
    wait_for=0
    if [ -n "$newest" ]; then
        age=$((now - $(field "$newest" completed_epoch)))
        if [ "$age" -lt "$INTERVAL" ]; then
            wait_for=$((INTERVAL - age))
        fi
        # 원천 클러스터가 바뀌었으면 기다리지 않는다. 식별자를 읽지 못하면(DB 가 아직 없음) 종전 규칙대로 기다린다.
        # BACKUP_HOSTS 를 준 배치(Patroni 판)에서만 본다. 비동기 판의 옛 백업에는 식별자 기록이 없어 매번 새로 받게 된다.
        if [ "$wait_for" -gt 0 ] && [ -n "${BACKUP_HOSTS:-}" ] && pick_source; then
            current=$(system_id)
            recorded=$(field "$newest" system_identifier)
            if [ -n "$current" ] && [ "$current" != "$recorded" ]; then
                log "원천 클러스터가 바뀌었다(최근 백업 ${recorded:-기록 없음}, 지금 $current). 바로 받는다"
                wait_for=0
            fi
        fi
    fi
    if [ "$wait_for" -gt 0 ]; then
        log "가장 최근 완료 백업 $newest, 다음 백업까지 ${wait_for}초"
        sleep "$wait_for" & wait $!
        continue
    fi
    if run_backup; then
        write_metrics 1 "$(date -u +%s)"
    else
        log "백업 실패. 완료 백업 기록은 바꾸지 않는다"
        rm -rf "$BACKUP_DIR"/.partial-*
        write_metrics 0 "$(date -u +%s)"
        # 실패하면 주기 전체를 기다리지 않고 10분 뒤 다시 한다.
        sleep 600 & wait $!
    fi
done
