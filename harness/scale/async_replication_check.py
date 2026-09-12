#!/usr/bin/env python3
"""async_replication_check.py. 2노드 비동기 복제의 계약을 판정한다(ADR 0024).

대상은 카오스 스택이 아니라 deploy/compose.node-data.yml 로 세운 검증 스택이다. 배포 배선을
그대로 쓰는 것이 요점이다. 카오스 스택의 3노드 동기 구성과 섞으면 무엇을 잰 것인지 알 수 없다.

판정하는 것은 다섯이다.

  A 정상    primary 가 recovery 아님, standby 가 recovery, state=streaming, sync_state=async
  B 복제    primary 쓰기가 standby 에 도달
  C 격리    standby 를 죽여도 primary 커밋이 계속된다. 이 정책의 목적 그 자체다
  D 복귀    standby 재기동 후 밀린 구간을 따라잡는다
  E 지연    복제 지연을 수치로 낸다

D 는 자동 승격을 가정하지 않는다. 이 저장소에 DB 자동 페일오버가 없다(ADR 0024 미충족 절).
primary 장애 시나리오는 수동 절차 문서가 소유한다.

사용:
  python harness/scale/async_replication_check.py --project pgha --out out.json
"""

import argparse
import io
import json
import subprocess
import sys
import time


def psql(container, sql, user, db):
    """단일 값 또는 행을 텍스트로 받는다. 빈 결과와 실패를 가른다."""
    p = subprocess.run(
        ['docker', 'exec', container, 'psql', '-U', user, '-d', db, '-t', '-A', '-F', '|', '-c', sql],
        capture_output=True, text=True, timeout=60)
    if p.returncode != 0:
        return None
    return p.stdout.strip()


def alive(container):
    p = subprocess.run(['docker', 'inspect', '-f', '{{.State.Running}}', container],
                       capture_output=True, text=True, timeout=30)
    return p.returncode == 0 and p.stdout.strip() == 'true'


def wait(fn, timeout, label):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if fn():
            return True
        time.sleep(1)
    print('  대기 실패: %s' % label, flush=True)
    return False


def main():
    a_ = argparse.ArgumentParser()
    a_.add_argument('--project', default='pgha', help='docker compose 프로젝트명')
    a_.add_argument('--user', default='exchange')
    a_.add_argument('--db', default='exchange')
    a_.add_argument('--out', required=True)
    a = a_.parse_args()

    primary = '%s-db-1' % a.project
    standby = '%s-db-standby-1' % a.project
    r = {'primary': primary, 'standby': standby, 'checks': {}}

    def fail(key, msg):
        r['checks'][key] = {'ok': False, 'detail': msg}
        print('  %s FAIL %s' % (key, msg), flush=True)

    def ok(key, detail):
        r['checks'][key] = {'ok': True, 'detail': detail}
        print('  %s PASS %s' % (key, detail), flush=True)

    print('[A] 정상 상태', flush=True)
    pr = psql(primary, 'SELECT pg_is_in_recovery()', a.user, a.db)
    sr = psql(standby, 'SELECT pg_is_in_recovery()', a.user, a.db)
    rep = psql(primary,
               'SELECT application_name, state, sync_state FROM pg_stat_replication', a.user, a.db)
    r['pg_stat_replication'] = rep
    if pr != 'f':
        fail('A_primary_not_recovery', 'pg_is_in_recovery=%s' % pr)
    elif sr != 't':
        fail('A_standby_in_recovery', 'pg_is_in_recovery=%s' % sr)
    elif not rep:
        fail('A_streaming', 'pg_stat_replication 이 비었다. standby 가 붙지 않았다')
    else:
        f = rep.split('|')
        if len(f) < 3 or f[1] != 'streaming':
            fail('A_streaming', 'state=%s' % (f[1] if len(f) > 1 else '?'))
        elif f[2] != 'async':
            # 동기로 뜨면 ADR 0024 위반이다. 정책이 코드와 갈렸다는 뜻이라 즉시 실패다.
            fail('A_async', 'sync_state=%s. 비동기여야 한다' % f[2])
        else:
            ok('A_streaming', 'state=streaming sync_state=async')

    print('[B] 복제 도달', flush=True)
    psql(primary, 'CREATE TABLE IF NOT EXISTS repl_probe(id serial primary key, at timestamptz default now())',
         a.user, a.db)
    before = psql(standby, 'SELECT count(*) FROM repl_probe', a.user, a.db)
    psql(primary, 'INSERT INTO repl_probe DEFAULT VALUES', a.user, a.db)
    reached = wait(lambda: psql(standby, 'SELECT count(*) FROM repl_probe', a.user, a.db) != before,
                   15, 'standby 도달')
    if reached:
        ok('B_replicated', 'standby 행 수 %s -> %s' %
           (before, psql(standby, 'SELECT count(*) FROM repl_probe', a.user, a.db)))
    else:
        fail('B_replicated', '15초 안에 도달하지 않았다')

    print('[C] standby 격리. 이 정책의 목적이다', flush=True)
    subprocess.run(['docker', 'kill', standby], capture_output=True, timeout=60)
    wait(lambda: not alive(standby), 20, 'standby 정지')
    t0 = time.monotonic()
    wrote = psql(primary, "INSERT INTO repl_probe DEFAULT VALUES; SELECT count(*) FROM repl_probe",
                 a.user, a.db)
    elapsed = (time.monotonic() - t0) * 1000
    r['write_without_standby_ms'] = round(elapsed, 1)
    if wrote is None:
        fail('C_write_without_standby', 'standby 부재에서 primary 커밋이 실패했다')
    else:
        ok('C_write_without_standby', '커밋 성공, %.1fms, 행 %s' % (elapsed, wrote))

    print('[D] standby 복귀와 catch-up', flush=True)
    # 밀린 구간을 만든다. 이것이 없으면 catch-up 이 판정 대상 0건으로 통과한다.
    for _ in range(20):
        psql(primary, 'INSERT INTO repl_probe DEFAULT VALUES', a.user, a.db)
    lag_rows = psql(primary, 'SELECT count(*) FROM repl_probe', a.user, a.db)
    subprocess.run(['docker', 'start', standby], capture_output=True, timeout=120)
    caught = wait(lambda: psql(standby, 'SELECT count(*) FROM repl_probe', a.user, a.db) == lag_rows,
                  90, 'catch-up')
    if caught:
        ok('D_catchup', 'standby 가 %s 행까지 따라잡았다' % lag_rows)
    else:
        fail('D_catchup', '90초 안에 따라잡지 못했다. standby=%s primary=%s' %
             (psql(standby, 'SELECT count(*) FROM repl_probe', a.user, a.db), lag_rows))

    print('[E] 복제 지연', flush=True)
    lag = psql(standby,
               "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())), -1)",
               a.user, a.db)
    lsn = psql(primary,
               "SELECT sent_lsn||'|'||replay_lsn||'|'||"
               "pg_wal_lsn_diff(sent_lsn, replay_lsn) FROM pg_stat_replication", a.user, a.db)
    r['replay_delay_seconds'] = lag
    r['lsn_sent_replay_diff'] = lsn
    if lag is None or lsn is None:
        fail('E_lag', '지연을 재지 못했다')
    else:
        ok('E_lag', 'replay_delay=%ss, sent/replay/diff=%s' % (lag, lsn))

    r['verdict'] = 'PASS' if all(c['ok'] for c in r['checks'].values()) else 'FAIL'
    io.open(a.out, 'w', encoding='utf-8').write(json.dumps(r, ensure_ascii=False, indent=2))
    print('\nverdict=%s -> %s' % (r['verdict'], a.out), flush=True)
    return 0 if r['verdict'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
