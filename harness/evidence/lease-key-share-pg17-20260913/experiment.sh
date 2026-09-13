### Host (Git Bash)
docker run -d --name leasekeyshare-exp -e POSTGRES_PASSWORD=exp -e POSTGRES_DB=exp postgres:17-alpine
# wait for pg_isready, then:
MSYS_NO_PATHCONV=1 docker cp exp leasekeyshare-exp:/exp      # dir with the 4 files below
for s in init s1 s2 s3 s3b s4 s5 s6a "s6bc b 4" "s6bc c 8" s6d s6e s7 s8 s9 s9b s10 s11; do MSYS_NO_PATHCONV=1 docker exec leasekeyshare-exp sh /exp/run.sh $s; done
docker rm -f leasekeyshare-exp

### exp/schema.sql
DROP TABLE IF EXISTS leader_lease CASCADE;
CREATE TABLE leader_lease (
    resource    TEXT        PRIMARY KEY,
    holder      TEXT        NOT NULL,
    epoch       BIGINT      NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ NOT NULL
);
INSERT INTO leader_lease (resource, holder, epoch, lease_until)
VALUES ('matching-engine', '', 0, now() - interval '1 s');

CREATE OR REPLACE FUNCTION assert_current_leader(p_holder TEXT, p_epoch BIGINT)
RETURNS void
LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
    SELECT holder, epoch, lease_until INTO r
    FROM leader_lease WHERE resource = 'matching-engine';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;
    IF r.holder <> p_holder OR r.epoch <> p_epoch THEN
        RAISE EXCEPTION 'fencing 위반: 현재 리더(holder=%, epoch=%)가 아님(요청 holder=%, epoch=%)',
            r.holder, r.epoch, p_holder, p_epoch;
    END IF;
    IF r.lease_until < clock_timestamp() THEN
        RAISE EXCEPTION 'fencing 위반: 리스 만료(lease_until=%, now=%)',
            r.lease_until, clock_timestamp();
    END IF;
END $$;

CREATE OR REPLACE FUNCTION try_acquire_leader(p_holder TEXT, p_ttl INTERVAL)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    UPDATE leader_lease
    SET holder = p_holder, epoch = epoch + 1,
        lease_until = clock_timestamp() + p_ttl
    WHERE resource = 'matching-engine'
      AND lease_until < clock_timestamp()
    RETURNING epoch INTO v_epoch;
    RETURN v_epoch;
END $$;

CREATE OR REPLACE FUNCTION renew_leader_lease(p_holder TEXT, p_epoch BIGINT, p_ttl INTERVAL)
RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE leader_lease
    SET lease_until = clock_timestamp() + p_ttl
    WHERE resource = 'matching-engine'
      AND holder = p_holder AND epoch = p_epoch
      AND lease_until >= clock_timestamp();
    RETURN FOUND;
END $$;

CREATE OR REPLACE FUNCTION assert_and_lock_leader(p_holder TEXT, p_epoch BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    SELECT epoch INTO v_epoch
    FROM leader_lease WHERE resource = 'matching-engine'
    FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;

    PERFORM assert_current_leader(p_holder, p_epoch);

    RETURN v_epoch;
END $$;

CREATE OR REPLACE FUNCTION publish_leader_epoch(p_holder TEXT, p_epoch BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    UPDATE leader_lease
    SET holder = p_holder, epoch = p_epoch,
        lease_until = clock_timestamp() + interval '100 years'
    WHERE resource = 'matching-engine'
      AND (epoch < p_epoch OR (epoch = p_epoch AND holder = p_holder))
    RETURNING epoch INTO v_epoch;
    RETURN v_epoch;
END $$;

CREATE OR REPLACE FUNCTION expire_leader_lease_now()
RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE leader_lease
    SET lease_until = clock_timestamp() - interval '1 second'
    WHERE resource = 'matching-engine';
END $$;

### exp/ks_func.sql  (V30 body, FOR SHARE -> FOR KEY SHARE only)
CREATE OR REPLACE FUNCTION assert_and_lock_leader(p_holder TEXT, p_epoch BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    SELECT epoch INTO v_epoch
    FROM leader_lease WHERE resource = 'matching-engine'
    FOR KEY SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;

    PERFORM assert_current_leader(p_holder, p_epoch);

    RETURN v_epoch;
END $$;

### exp/ks_index.sql
CREATE UNIQUE INDEX leader_lease_fencing_key ON leader_lease (resource, holder, epoch);

### exp/run.sh
#!/bin/sh
P="psql -X -U postgres -d exp -At"
OUT=/exp/out
mkdir -p $OUT

q() { $P -c "$1" 2>&1; }
base() { $P -q -f /exp/schema.sql >/dev/null 2>&1 || echo "schema load failed"; }
ksf() { $P -q -f /exp/ks_func.sql >/dev/null 2>&1 || echo "ks_func load failed"; }
ksi() { $P -q -f /exp/ks_index.sql 2>&1; }
lease_valid() { q "UPDATE leader_lease SET holder='nodeA', epoch=5, lease_until=clock_timestamp()+interval '1 hour'" >/dev/null; }
row() { q "SELECT 'row', holder, epoch, to_char(lease_until,'HH24:MI:SS.MS') AS lease_until, to_char(clock_timestamp(),'HH24:MI:SS.MS') AS now FROM leader_lease"; }
rl() { q "SELECT 'rowlocks', locker, multi, xids, modes, pids FROM pgrowlocks('leader_lease')"; }
waiters() { q "SELECT 'waiter', to_char(clock_timestamp(),'HH24:MI:SS.MS'), pid, wait_event_type, wait_event, pg_blocking_pids(pid), left(regexp_replace(query,'\s+',' ','g'),60) FROM pg_stat_activity WHERE datname='exp' AND wait_event_type='Lock'"; }

holdA() {
  if [ "$3" = raw ]; then
    lk="SELECT 'A locked raw KEY SHARE', epoch, to_char(clock_timestamp(),'HH24:MI:SS.MS') FROM leader_lease WHERE resource='matching-engine' FOR KEY SHARE"
  else
    lk="SELECT 'A locked via assert_and_lock_leader', assert_and_lock_leader('nodeA',5), to_char(clock_timestamp(),'HH24:MI:SS.MS')"
  fi
  $P > $1 2>&1 <<EOF
BEGIN;
$lk;
SELECT pg_sleep($2);
SELECT 'A before commit sees', holder, epoch, to_char(clock_timestamp(),'HH24:MI:SS.MS') FROM leader_lease;
COMMIT;
SELECT 'A committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
}

tq() {
  $P 2>&1 <<EOF
SET lock_timeout='$2';
SELECT '$1 start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
\timing on
$3;
\timing off
SELECT '$1 end', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
}

worker() {
  i=0
  { while [ $i -lt $2 ]; do echo "BEGIN; SELECT assert_and_lock_leader('nodeA',5); SELECT pg_sleep(0.3); COMMIT;"; i=$((i+1)); done; } | $P -q > $OUT/w$1.log 2>&1
}
loopers() {
  rm -f $OUT/w*.log
  n=1; while [ $n -le $1 ]; do worker $n $2 & sleep 0.07; n=$((n+1)); done
}
werrs() { echo "worker error summary:"; cat $OUT/w*.log | grep -i -E 'error|fencing' | sed -E 's/holder=[^)]*//' | sort | uniq -c; }

RENEW="SELECT 'renew(nodeA,5,6s) ->', renew_leader_lease('nodeA',5,'6 seconds')"

s1() {
  base; lease_valid
  holdA $OUT/s1a.log 4 &
  sleep 0.5
  rl
  tq B 1s "$RENEW"
  wait; cat $OUT/s1a.log; row
}

s2() {
  base; lease_valid
  loopers 5 50
  sleep 1
  tq R 0 "SET statement_timeout='14s'; SELECT 'R stmt_start', to_char(statement_timestamp(),'HH24:MI:SS.MS'), renew_leader_lease('nodeA',5,'6 seconds'), to_char(clock_timestamp(),'HH24:MI:SS.MS')" > $OUT/s2r.log &
  k=1; while [ $k -le 6 ]; do sleep 1.5; waiters; k=$((k+1)); done
  wait
  cat $OUT/s2r.log; row; werrs
}

s3() {
  base; ksf; ksi; lease_valid
  holdA $OUT/s3a.log 10 &
  sleep 0.5
  rl
  tq B 1s "$RENEW"
  rl
  tq D 1s "SELECT 'D publish(nodeB,6) ->', publish_leader_epoch('nodeB',6)"
  tq E 1s "SELECT 'E publish(nodeA,5) ->', publish_leader_epoch('nodeA',5)"
  row
  tq F 1s "SELECT 'F expire_leader_lease_now ->', expire_leader_lease_now()"
  row; rl
  tq D2 1s "SELECT 'D2 publish(nodeB,6) ->', publish_leader_epoch('nodeB',6)"
  tq C 1s "SELECT 'C try_acquire(nodeC) ->', try_acquire_leader('nodeC','6 seconds')"
  tq C2 0 "SELECT 'C2 try_acquire(nodeC) ->', try_acquire_leader('nodeC','6 seconds')" > $OUT/s3c2.log &
  sleep 1; waiters
  wait
  cat $OUT/s3c2.log; cat $OUT/s3a.log; row
}

s3b() {
  base; ksf; ksi; lease_valid
  q "SELECT expire_leader_lease_now()" >/dev/null; row
  holdA $OUT/s3b1.log 3 &
  sleep 0.5
  tq C 1s "SELECT 'C try_acquire(nodeC) ->', try_acquire_leader('nodeC','6 seconds')"
  wait; cat $OUT/s3b1.log; row
  base; ksf; ksi; lease_valid
  q "SELECT expire_leader_lease_now()" >/dev/null
  holdA $OUT/s3b2.log 4 raw &
  sleep 0.5
  tq C 1s "SELECT 'C try_acquire(nodeC) ->', try_acquire_leader('nodeC','6 seconds')"
  tq C2 0 "SELECT 'C2 try_acquire(nodeC) ->', try_acquire_leader('nodeC','6 seconds')"
  wait; cat $OUT/s3b2.log; row
}

s4() {
  base; ksf; lease_valid
  holdA $OUT/s4a.log 5 &
  sleep 0.5
  rl
  tq B 1s "$RENEW"
  tq D 1s "SELECT 'D publish(nodeB,6) ->', publish_leader_epoch('nodeB',6)"
  row
  tq F 1s "SELECT 'F expire_leader_lease_now ->', expire_leader_lease_now()"
  tq C 1s "SELECT 'C try_acquire(nodeC) ->', try_acquire_leader('nodeC','6 seconds')"
  row
  wait; cat $OUT/s4a.log; row
}

s5() {
  base; ksf; ksi; lease_valid
  loopers 5 80
  sleep 1
  k=1
  while [ $k -le 10 ]; do
    tq R$k 0 "SET statement_timeout='14s'; SELECT 'R$k', renew_leader_lease('nodeA',5,'6 seconds')" | grep -E "R$k|Time|ERROR"
    sleep 0.7; k=$((k+1))
  done
  tq D 1s "SELECT 'D publish(nodeB,6) ->', publish_leader_epoch('nodeB',6)"
  tq Dwait 0 "SET statement_timeout='8s'; SELECT 'Dwait publish(nodeB,6) ->', publish_leader_epoch('nodeB',6)" > $OUT/s5d.log &
  k=1; while [ $k -le 3 ]; do sleep 2; waiters; k=$((k+1)); done
  wait
  cat $OUT/s5d.log; row; werrs
}

s6a() {
  base; lease_valid
  holdA $OUT/s6a.log 2 &
  sleep 0.3
  tq R 0 "SELECT 'R stmt_start', to_char(statement_timestamp(),'HH24:MI:SS.MS'), renew_leader_lease('nodeA',5,'6 seconds'), to_char(clock_timestamp(),'HH24:MI:SS.MS')"
  q "SELECT 'lease_until - 6s =', to_char(lease_until - interval '6 seconds','HH24:MI:SS.MS') FROM leader_lease"
  wait; cat $OUT/s6a.log
}

s6bc() {
  base
  q "UPDATE leader_lease SET holder='nodeA', epoch=5, lease_until=clock_timestamp()+interval '1.5 seconds'" >/dev/null
  holdA $OUT/s6$1a.log $2 &
  sleep 0.5
  tq R 0 "SELECT 'R stmt_start', to_char(statement_timestamp(),'HH24:MI:SS.MS'), renew_leader_lease('nodeA',5,'6 seconds'), to_char(clock_timestamp(),'HH24:MI:SS.MS')" > $OUT/s6$1r.log &
  sleep 1.5
  tq C 0 "SELECT 'C stmt_start', to_char(statement_timestamp(),'HH24:MI:SS.MS'), try_acquire_leader('nodeB','6 seconds'), to_char(clock_timestamp(),'HH24:MI:SS.MS')" > $OUT/s6$1c.log &
  sleep 0.5; waiters
  wait
  cat $OUT/s6$1a.log $OUT/s6$1r.log $OUT/s6$1c.log; row
}

s6d() {
  base; lease_valid
  holdA $OUT/s6da.log 3 &
  sleep 0.5
  tq D 0 "SELECT 'D publish(nodeB,6) ->', publish_leader_epoch('nodeB',6)" > $OUT/s6dd.log &
  sleep 0.5
  tq R 0 "$RENEW" > $OUT/s6dr.log &
  sleep 0.5; waiters
  wait
  cat $OUT/s6da.log $OUT/s6dd.log $OUT/s6dr.log; row
}

s6e() {
  base; lease_valid
  holdA $OUT/s6ea.log 3 &
  sleep 0.5
  tq R1 0 "SELECT 'R1 stmt_start', to_char(statement_timestamp(),'HH24:MI:SS.MS'), renew_leader_lease('nodeA',5,'6 seconds'), to_char(clock_timestamp(),'HH24:MI:SS.MS')" > $OUT/s6e1.log &
  sleep 0.5
  tq R2 0 "SELECT 'R2 stmt_start', to_char(statement_timestamp(),'HH24:MI:SS.MS'), renew_leader_lease('nodeA',5,'6 seconds'), to_char(clock_timestamp(),'HH24:MI:SS.MS')" > $OUT/s6e2.log &
  sleep 0.5; waiters
  wait
  cat $OUT/s6ea.log $OUT/s6e1.log $OUT/s6e2.log
  q "SELECT 'final lease_until - 6s =', to_char(lease_until - interval '6 seconds','HH24:MI:SS.MS') FROM leader_lease"
}

s7v() {
  base; lease_valid
  [ -n "$2" ] && q "$2"
  q "SELECT 'index', indexrelid::regclass, indisunique, indimmediate, indpred IS NOT NULL AS partial, indexprs IS NOT NULL AS expr FROM pg_index WHERE indrelid='leader_lease'::regclass ORDER BY 2"
  holdA $OUT/s7_$1.log 4.5 raw &
  sleep 0.4
  tq U_holder 1s "UPDATE leader_lease SET holder='x' WHERE resource='matching-engine'" | grep -E 'Time|ERROR|UPDATE'
  tq U_epoch 1s "UPDATE leader_lease SET epoch=epoch+1 WHERE resource='matching-engine'" | grep -E 'Time|ERROR|UPDATE'
  tq U_lease 1s "UPDATE leader_lease SET lease_until=clock_timestamp()+interval '6 seconds' WHERE resource='matching-engine'" | grep -E 'Time|ERROR|UPDATE'
  wait
  row
}
s7() {
  s7v pk_only ""
  s7v uq_resource_epoch "CREATE UNIQUE INDEX ON leader_lease (resource, epoch)"
  s7v uq_resource_holder_epoch "CREATE UNIQUE INDEX ON leader_lease (resource, holder, epoch)"
  s7v uq_deferrable "ALTER TABLE leader_lease ADD CONSTRAINT lk UNIQUE (resource, holder, epoch) DEFERRABLE INITIALLY IMMEDIATE"
  s7v uq_partial "CREATE UNIQUE INDEX ON leader_lease (resource, holder, epoch) WHERE epoch >= 0"
  s7v uq_expression "CREATE UNIQUE INDEX ON leader_lease (resource, (holder || ''), epoch)"
  s7v nonunique "CREATE INDEX ON leader_lease (resource, holder, epoch)"
}

s8v() {
  base; lease_valid
  [ -n "$2" ] && q "$2" >/dev/null
  q "SELECT pg_stat_reset_single_table_counters('leader_lease'::regclass)" >/dev/null
  { i=0; while [ $i -lt 200 ]; do echo "SELECT renew_leader_lease('nodeA',5,'6 seconds');"; i=$((i+1)); done; } | $P -q | sort | uniq -c
  sleep 1.5
  q "SELECT '$1 renew x200', n_tup_upd, n_tup_hot_upd, pg_relation_size('leader_lease') FROM pg_stat_user_tables WHERE relname='leader_lease'"
  q "SELECT pg_stat_reset_single_table_counters('leader_lease'::regclass)" >/dev/null
  { i=6; while [ $i -lt 56 ]; do echo "SELECT publish_leader_epoch('nodeA',$i);"; i=$((i+1)); done; } | $P -q > /dev/null
  sleep 1.5
  q "SELECT '$1 publish x50 (epoch changes)', n_tup_upd, n_tup_hot_upd FROM pg_stat_user_tables WHERE relname='leader_lease'"
}
s8() {
  s8v pk_only ""
  s8v with_fencing_key "CREATE UNIQUE INDEX leader_lease_fencing_key ON leader_lease (resource, holder, epoch)"
}

s9v() {
  for stmt in "SELECT assert_and_lock_leader('nodeA',5)" "SELECT renew_leader_lease('nodeA',5,'6 seconds')" "SELECT publish_leader_epoch('nodeA',5)" "SELECT publish_leader_epoch('nodeA',99)" "SELECT expire_leader_lease_now(), try_acquire_leader('nodeC','6 seconds')"; do
    $P > $OUT/s9.log 2>&1 <<EOF &
BEGIN;
$stmt;
SELECT pg_sleep(1.2);
ROLLBACK;
EOF
    sleep 0.5; echo "  open tx: $stmt"; rl; wait
  done
}
s9() {
  base; lease_valid; s9v
  base; ksf; ksi; lease_valid; s9v
}

s9b() {
  for cfg in base ks; do
    base; [ $cfg = ks ] && { ksf; ksi; }
    lease_valid; q "SELECT expire_leader_lease_now()" >/dev/null
    $P > $OUT/s9b.log 2>&1 <<EOF &
BEGIN;
SELECT 'try_acquire ->', try_acquire_leader('nodeC','6 seconds');
SELECT pg_sleep(1.2);
ROLLBACK;
EOF
    sleep 0.5; echo "  $cfg open tx: try_acquire_leader"; rl; wait; cat $OUT/s9b.log
  done
}

s10v() {
  base; [ "$1" = ks ] && { ksf; ksi; }
  lease_valid; q "SELECT publish_leader_epoch('nodeA',5)" >/dev/null
  loopers 5 40
  sleep 1
  tq P 1s "SET statement_timeout='9s'; SELECT 'publish(nodeB,6) ->', publish_leader_epoch('nodeB',6)"
  wait; row; werrs
}
s10() { s10v base; s10v ks; }

s11v() {
  base; [ "$1" = ks ] && { ksf; ksi; }
  lease_valid
  q "CHECKPOINT" >/dev/null
  m0=$($P -c "SELECT next_multixact_id FROM pg_control_checkpoint()")
  loopers 5 20
  k=0; while [ $k -lt 10 ]; do sleep 0.5; tq r 1s "$RENEW" > /dev/null 2>&1; k=$((k+1)); done
  wait
  q "CHECKPOINT" >/dev/null
  m1=$($P -c "SELECT next_multixact_id FROM pg_control_checkpoint()")
  echo "S11 $1: 5 writers x20 + 10 renews; next_multixact_id $m0 -> $m1"
}
s11() { s11v base; s11v ks; }

init() { q "CREATE EXTENSION IF NOT EXISTS pgrowlocks"; q "SELECT version()"; }

"$@"

### Local copies (scratchpad, outside repo)
C:\Users\DEV-PC\AppData\Local\Temp\claude\C--Users-DEV-PC-Desktop-----\8fcd986d-ebfe-41ed-ba89-0d73c29bd570\scratchpad\exp\{schema.sql,ks_func.sql,ks_index.sql,run.sh}