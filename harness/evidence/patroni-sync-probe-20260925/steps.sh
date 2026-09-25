#!/bin/sh
# 엄격 동기 복제에서 커밋 대기가 어떻게 끝나는지 확인하는 단계. patroni-probe-pg1 안에서 postgres 사용자로 돈다.
# 사용: sh /probe/steps.sh <단계>
# 경과 시간은 /proc/uptime 으로 잰다. busybox date 에는 초 아래 자리가 없다.
set -u
P="psql -X -U postgres -d postgres -v ON_ERROR_STOP=1 -v VERBOSITY=verbose"
up() { cut -d' ' -f1 /proc/uptime; }
since() { awk -v a="$1" -v b="$(up)" 'BEGIN { printf "%.2f", b - a }'; }

case "$1" in
  version)
    $P -Atc "select version()"
    ;;
  status)
    $P -Atc "select 'replication', application_name, state, sync_state from pg_stat_replication"
    $P -Atc "select 'settings', current_setting('synchronous_standby_names'), current_setting('synchronous_commit'), pg_is_in_recovery()"
    ;;
  setup)
    $P -c "create table t (id int primary key, note text)" -c "insert into t values (1, 'baseline async')"
    ;;
  sync-on)
    # ALTER SYSTEM 과 설정 재적재는 psql 호출을 나눈다. 한 호출에 묶으면 트랜잭션 블록 안이라 실패한다.
    $P -c "alter system set synchronous_standby_names = 's1'"
    ;;
  reload)
    $P -Atc "select pg_reload_conf()"
    ;;
  baseline-sync)
    s=$(up)
    $P -c "insert into t values (100, 'baseline sync')"
    echo "baseline-sync rc=$? elapsed=$(since "$s")s"
    ;;
  waiters)
    $P -Atc "select 'waiter', pid, coalesce(wait_event_type, '-'), coalesce(wait_event, '-'), state, left(query, 70) from pg_stat_activity where backend_type = 'client backend' and pid <> pg_backend_pid() order by pid"
    ;;
  rows)
    $P -Atc "select 'row', id, note from t order by id"
    ;;
  A)
    # 암묵 트랜잭션(자동 커밋 INSERT). 세션 statement_timeout 1초가 커밋 대기를 끊는지 본다.
    s=$(up)
    PGOPTIONS='-c statement_timeout=1000' timeout 8 $P -c "insert into t values (2, 'A implicit, statement_timeout 1s')"
    echo "A rc=$? elapsed=$(since "$s")s"
    ;;
  B)
    # 명시 트랜잭션의 COMMIT. 같은 statement_timeout 1초.
    s=$(up)
    PGOPTIONS='-c statement_timeout=1000' timeout 8 $P -c "begin" -c "insert into t values (3, 'B explicit, statement_timeout 1s')" -c "commit"
    echo "B rc=$? elapsed=$(since "$s")s"
    ;;
  C-hold)
    # 트랜잭션 어드바이저리 락을 쥔 채 커밋 대기에 들어가는 쓰기. 백그라운드로 띄운다.
    $P -c "begin" -c "select pg_advisory_xact_lock(42)" -c "insert into t values (4, 'C advisory holder')" -c "commit" > /tmp/C-hold.out 2>&1
    echo "C-hold rc=$?" >> /tmp/C-hold.out
    ;;
  C-wait)
    s=$(up)
    $P -c "set lock_timeout = 1000" -c "select pg_advisory_xact_lock(42)"
    echo "C-wait rc=$? elapsed=$(since "$s")s"
    ;;
  D-hold)
    # 행 잠금을 쥔 채 커밋 대기에 들어가는 자동 커밋 UPDATE. 백그라운드로 띄운다.
    $P -c "update t set note = 'D row holder' where id = 1" > /tmp/D-hold.out 2>&1
    echo "D-hold rc=$?" >> /tmp/D-hold.out
    ;;
  D-wait)
    s=$(up)
    $P -c "set lock_timeout = 1000" -c "update t set note = 'D waiter' where id = 1"
    echo "D-wait rc=$? elapsed=$(since "$s")s"
    ;;
  E)
    # 이 트랜잭션만 synchronous_commit 을 local 로 낮춘 커밋.
    s=$(up)
    $P -c "begin" -c "set local synchronous_commit = local" -c "insert into t values (5, 'E local commit')" -c "commit"
    echo "E rc=$? elapsed=$(since "$s")s"
    $P -Atc "select 'E-visible', id, note from t where id = 5"
    ;;
  F)
    # 쓰기 가능 탐지의 후보 문장. 행을 쓰지 않고 트랜잭션 번호만 받아 커밋한다.
    s=$(up)
    timeout 5 $P -At -c "begin" -c "select txid_current() > 0" -c "commit"
    echo "F probe rc=$? elapsed=$(since "$s")s"
    s=$(up)
    timeout 5 $P -Atc "select 'F-read', 1"
    echo "F read rc=$? elapsed=$(since "$s")s"
    $P -Atc "select 'F-diag', pg_is_in_recovery(), current_setting('synchronous_standby_names')"
    ;;
  G-hold)
    $P -c "insert into t values (6, 'G cancel')" > /tmp/G.out 2>&1
    echo "G-hold rc=$?" >> /tmp/G.out
    ;;
  G-cancel)
    $P -Atc "select 'G-cancel', pid, pg_cancel_backend(pid) from pg_stat_activity where query like 'insert into t values (6%' and wait_event = 'SyncRep'"
    ;;
  G-show)
    cat /tmp/G.out
    $P -Atc "select 'G-visible', id, note from t where id = 6"
    ;;
  H-hold)
    $P -c "insert into t values (7, 'H terminate')" > /tmp/H.out 2>&1
    echo "H-hold rc=$?" >> /tmp/H.out
    ;;
  H-terminate)
    $P -Atc "select 'H-terminate', pid, pg_terminate_backend(pid) from pg_stat_activity where query like 'insert into t values (7%' and wait_event = 'SyncRep'"
    ;;
  H-show)
    cat /tmp/H.out
    $P -Atc "select 'H-visible', id, note from t where id = 7"
    ;;
  I-set)
    $P -c "alter system set client_connection_check_interval = '1s'"
    ;;
  I-show)
    $P -Atc "select 'I-setting', current_setting('client_connection_check_interval')"
    ;;
  I-sync)
    # 클라이언트가 먼저 죽는 커밋 대기. 서버 백엔드가 남는지 본다.
    timeout 3 $P -c "insert into t values (8, 'I sync waiter, client killed')"
    echo "I-sync rc=$?"
    ;;
  I-lock)
    # 클라이언트가 먼저 죽는 행 잠금 대기(D-hold 가 쥔 행). 서버 백엔드가 정리되는지 본다.
    timeout 3 $P -c "update t set note = 'I lock waiter, client killed' where id = 1"
    echo "I-lock rc=$?"
    ;;
  outputs)
    for f in /tmp/C-hold.out /tmp/D-hold.out; do echo "== $f"; cat "$f" 2>/dev/null; done
    ;;
  # 2차 확인. F 가 동기 대기를 타지 않아(WAL 을 쓰지 않은 트랜잭션) WAL 을 쓰는 탐지 후보와 권한을 본다.
  J-role)
    $P -c "create role probe_app login"
    $P -Atc "select 'J-priv', has_function_privilege('probe_app', 'pg_logical_emit_message(boolean,text,text,boolean)', 'execute')"
    ;;
  J-super)
    s=$(up)
    timeout 5 $P -At -c "begin" -c "select pg_logical_emit_message(true, 'exchange-write-probe', '') is not null" -c "commit"
    echo "J-super rc=$? elapsed=$(since "$s")s"
    ;;
  J-app)
    s=$(up)
    timeout 5 psql -X -U probe_app -d postgres -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -At -c "begin" -c "select pg_logical_emit_message(true, 'exchange-write-probe', '') is not null" -c "commit"
    echo "J-app rc=$? elapsed=$(since "$s")s"
    ;;
  K-control)
    # client_connection_check_interval 이 0(기본)일 때 클라이언트가 먼저 죽는 행 잠금 대기. 대조군이다.
    $P -Atc "select 'K-setting', current_setting('client_connection_check_interval')"
    timeout 3 $P -c "update t set note = 'K lock waiter, client killed, check off' where id = 1"
    echo "K-control rc=$?"
    ;;
  # 3차 확인. Patroni 엄격 모드가 동기 후보를 잃었을 때 거는 '*' 가 멤버가 아닌 복제 클라이언트를 동기 대기 서버로
  # 받는지 본다(설계 검토 지적 1). 인자 $2 는 행 id 나 사례 이름, $3 은 추가 인자다.
  star-on)
    $P -c "alter system set synchronous_standby_names = '*'"
    ;;
  repl)
    # 복제 연결별 동기 우선순위·상태와 보고된 flush 위치. 행이 없으면 none 을 찍는다.
    $P -Atc "select 'repl', application_name, state, sync_priority, sync_state, coalesce(flush_lsn::text, '-') from pg_stat_replication order by pid"
    $P -Atc "select 'repl-count', count(*) from pg_stat_replication"
    $P -Atc "select 'lsn', pg_current_wal_lsn(), pg_walfile_name(pg_current_wal_lsn()), current_setting('synchronous_standby_names')"
    ;;
  switch)
    $P -Atc "select 'switch', pg_switch_wal()"
    ;;
  hold)
    # 커밋 대기에 들어갈 자동 커밋 INSERT. 백그라운드로 띄운다. 결과는 /tmp/hold-<id>.out 이다.
    $P -c "insert into t values ($2, 'hold $2')" > /tmp/hold-$2.out 2>&1
    echo "hold $2 rc=$?" >> /tmp/hold-$2.out
    ;;
  holdout)
    echo "== /tmp/hold-$2.out"
    cat /tmp/hold-$2.out 2>/dev/null
    ;;
  visible)
    # 그 id 의 행이 다른 세션에 보이는지(커밋 대기가 풀렸는지)와 그 INSERT 가 아직 SyncRep 에서 기다리는지.
    $P -Atc "select 'visible', $2, count(*) from t where id = $2"
    $P -Atc "select 'waiting', $2, count(*) from pg_stat_activity where wait_event = 'SyncRep' and query like 'insert into t values ($2,%'"
    ;;
  timed)
    # 동기 대기가 풀려 있는지 보는 자동 커밋 INSERT. 5초 안에 끝나지 않으면 timeout 이 클라이언트를 죽인다.
    s=$(up)
    timeout 5 $P -c "insert into t values ($2, 'timed $2')"
    echo "timed $2 rc=$? elapsed=$(since "$s")s"
    ;;
  rw)
    # pg_receivewal 을 이 컨테이너 안에서 127.0.0.1 로 붙여 $3 초 동안 돌린다. 추가 인자는 $4 다.
    mkdir -p /tmp/rw-$2
    timeout $3 pg_receivewal -h 127.0.0.1 -U postgres -D /tmp/rw-$2 --no-password -v ${4:-} > /tmp/rw-$2.log 2>&1
    echo "rw $2 rc=$?" >> /tmp/rw-$2.log
    ;;
  bb)
    # pg_basebackup -X stream 을 속도 제한(32kB/s)으로 느리게 돌려 백업이 진행 중인 상태를 만든다. $3 초 뒤 timeout 이
    # 끝낸다. 추가 인자는 $4 다(예: --no-slot). 기본은 임시 복제 슬롯을 만든다.
    rm -rf /tmp/bb-$2
    timeout $3 pg_basebackup -h 127.0.0.1 -U postgres -D /tmp/bb-$2 -X stream -c fast --max-rate=32k --no-password -v ${4:-} > /tmp/bb-$2.log 2>&1
    echo "bb $2 rc=$?" >> /tmp/bb-$2.log
    ;;
  log)
    echo "== /tmp/$2.log"
    cat /tmp/$2.log 2>/dev/null
    ;;
  bbkill)
    # timeout 은 pg_basebackup 본 프로세스만 끝낸다. 백그라운드 WAL 수신 자식이 두 복제 연결의 소켓을 물려받아 살아 있으면
    # 서버의 walsender 두 개가 남는다(3차 첫 주행). 명령줄이 같은 부모와 자식을 모두 끝낸다.
    # 이 명령줄에는 busybox timeout 의 감시 프로세스도 걸린다. 그 프로세스는 PID 1(postmaster)의 자식이 되어 있어서
    # 신호로 끝나면 postmaster 가 크래시 재시작을 한다(4차에서 확인). 3차 주행은 이 부작용을 모른 채 이 단계를 썼다.
    pkill -TERM -f "pg_basebackup -h 127.0.0.1"
    sleep 2
    pkill -KILL -f "pg_basebackup -h 127.0.0.1"
    echo "bbkill done"
    ;;
  # 4차 확인(설계 검토 지적 4). 커밋 대기 중인 트랜잭션이 postmaster 크래시 재시작 뒤 복제 없이 보이는지, 그리고
  # 컨테이너의 PID 1 이 postmaster 일 때 고아 프로세스의 비정상 종료가 크래시 재시작을 부르는지 본다.
  pid1)
    echo "pid1: $(tr '\0' ' ' < /proc/1/cmdline)"
    ;;
  sleeper)
    # 끝나지 않는 조회 세션 하나. 백엔드를 SIGKILL 로 죽이는 대상이다(메모리 부족으로 커널이 백엔드 하나를 죽인 경우를 흉내).
    PGAPPNAME=sleeper $P -c "select pg_sleep(120)" > /tmp/sleeper.out 2>&1
    echo "sleeper rc=$?" >> /tmp/sleeper.out
    ;;
  kill-sleeper)
    pid=$($P -Atc "select pid from pg_stat_activity where application_name = 'sleeper'")
    echo "kill -9 backend pid=$pid"
    kill -9 "$pid"
    ;;
  orphan)
    # 부모 셸이 곧바로 끝나 sleep 이 고아가 되고 PID 1 의 자식이 된다.
    sh -c "sleep $2 > /dev/null 2>&1 &"
    sleep 1
    echo "orphan: $(ps -o pid,ppid,args | grep "sleep $2" | grep -v grep)"
    ;;
  orphan-kill)
    pkill -TERM -f "sleep $2"
    echo "orphan-kill sleep $2 rc=$?"
    ;;
  ready)
    for i in $(seq 1 60); do
      if pg_isready -U postgres -q; then echo "ready after ${i} tries"; exit 0; fi
      sleep 0.5
    done
    echo "not ready"
    ;;
  *)
    echo "알 수 없는 단계: $1" >&2
    exit 2
    ;;
esac
