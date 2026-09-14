"""ADR 0022 0단계 계측. 착수 게이트다.

무엇을 왜 재는가.

(a) 동시 커밋 확장성. 이 판의 처리량 이득 전부가 여기에 걸려 있다. wal-fsync-20260907 이
    이 호스트의 fdatasync 를 초당 311.9회로 쟀는데 그것은 겹치지 않는 직렬 플러시의 값이고
    곧 지연 3.206밀리초의 다른 표현이다. PostgreSQL 의 그룹 커밋이 한 번의 플러시로 동시
    커밋 여럿을 함께 내구화하면 초당 커밋이 초당 플러시를 넘는다. 4단계 전에는 전역 접수 락이
    저널 커밋을 통째로 직렬화해 동시 커밋자가 구조적으로 하나다. 락을 가르는 것이 정확히
    그 조건을 여는 변경이므로, 저장소가 동시 플러시를 흡수하는지가 1.18배냐 1.6배냐를 가른다.

    판정: N=12 의 초당 커밋이 N=1 의 3배 미만이면 저장소가 직렬 장치다.

(e) 파티션 잎당 팬아웃. findExistingNew 의 술어가 (command_type, user_id, client_order_id)
    뿐이라 파티션 프루닝이 0 이고 잎 전부의 인덱스를 훑는다. 그 조회가 어드바이저리 락 안,
    append 트랜잭션 안, 곧 상한 그 자체인 6.879밀리초 안이다. V39 가 잎을 67개로 만드는데
    락 분할은 뒤 단계라 그 사이 내내 이 비용만 먼저 문다. 잎 3, 15, 67 로 재서 그 거래를 정한다.

돌리는 법:
  python harness/scale/stage0_probe.py --out out.json
"""
import argparse
import io
import json
import re
import subprocess
import sys

PG = 'chaos-pg'
DB = 'exchange'
USER = 'exchange'


def psql(sql, db=DB):
    p = subprocess.run(['docker', 'exec', PG, 'psql', '-U', USER, '-d', db,
                        '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-F', '|', '-c', sql],
                       capture_output=True, text=True, timeout=300)
    if p.returncode != 0:
        raise RuntimeError((p.stderr or p.stdout)[-400:])
    return p.stdout.strip()


def pgbench(args, timeout=300):
    p = subprocess.run(['docker', 'exec', PG, 'pgbench', '-U', USER, '-d', DB] + args,
                       capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


def commit_ladder(secs, levels):
    """(a) 동시 커밋 확장성. pgbench 가 스크립트 1회 실행을 트랜잭션 하나로 감싼다."""
    psql("DROP TABLE IF EXISTS commit_probe; "
         "CREATE TABLE commit_probe (id BIGSERIAL PRIMARY KEY, v INT NOT NULL);")
    # 스크립트를 컨테이너 안에 둔다. 호스트 마운트를 쓰지 않는다.
    subprocess.run(['docker', 'exec', PG, 'sh', '-c',
                    "printf 'INSERT INTO commit_probe (v) VALUES (1);\n' > /tmp/commit.sql"],
                   capture_output=True, text=True, timeout=60)
    rows = []
    for n in levels:
        out = pgbench(['-n', '-f', '/tmp/commit.sql', '-c', str(n), '-j', str(min(n, 8)),
                       '-T', str(secs), '-r'], timeout=secs + 180)
        tps = re.search(r'tps = ([0-9.]+)', out)
        lat = re.search(r'latency average = ([0-9.]+) ms', out)
        rows.append({'동시': n,
                     '커밋/s': round(float(tps.group(1)), 1) if tps else None,
                     '평균 지연(ms)': round(float(lat.group(1)), 3) if lat else None,
                     'raw': out[-300:] if not tps else ''})
        print('  동시 %-3d 커밋/s %s  평균 %s ms' % (n, rows[-1]['커밋/s'], rows[-1]['평균 지연(ms)']),
              flush=True)
    psql("DROP TABLE IF EXISTS commit_probe;")
    return rows


def leaf_fanout(leaves, rows_per_leaf, reps):
    """(e) 잎 수에 따른 findExistingNew 형태 조회 비용."""
    out = []
    for k in leaves:
        psql("DROP TABLE IF EXISTS fan_probe CASCADE;")
        psql("CREATE TABLE fan_probe (seq BIGINT NOT NULL, command_type VARCHAR(16) NOT NULL,"
             " user_id BIGINT NOT NULL, client_order_id VARCHAR(36),"
             " PRIMARY KEY (seq)) PARTITION BY RANGE (seq);")
        span = 10 ** 12
        for i in range(k):
            psql("CREATE TABLE fan_probe_p%d PARTITION OF fan_probe "
                 "FOR VALUES FROM (%d) TO (%d);" % (i, i * span, (i + 1) * span))
        # 실제 인덱스와 같은 모양. V1 의 idx_cmd_client_new 다.
        psql("CREATE INDEX ON fan_probe (user_id, client_order_id) "
             "WHERE command_type = 'NEW' AND client_order_id IS NOT NULL;")
        psql("INSERT INTO fan_probe SELECT i * %d + g, 'NEW', (g %% 500) + 1, 'coid-' || g "
             "FROM generate_series(0, %d) i, generate_series(1, %d) g;"
             % (span, k - 1, rows_per_leaf))
        psql("ANALYZE fan_probe;")
        # 매치 0건 조회가 최악이다. 신규 키마다 잎 전부를 훑는다.
        q = ("SELECT seq FROM fan_probe WHERE command_type = 'NEW' "
             "AND user_id = 12345 AND client_order_id = 'no-such-key' ORDER BY seq LIMIT 1")
        ms = []
        for _ in range(reps):
            t = psql("EXPLAIN (ANALYZE, TIMING ON, FORMAT JSON) " + q)
            m = re.search(r'"Execution Time":\s*([0-9.]+)', t)
            if m:
                ms.append(float(m.group(1)))
        ms.sort()
        plan = psql("EXPLAIN (COSTS OFF) " + q)
        out.append({'잎': k, '행/잎': rows_per_leaf,
                    '중앙값(ms)': round(ms[len(ms) // 2], 4) if ms else None,
                    '최대(ms)': round(ms[-1], 4) if ms else None,
                    'Append 자식': plan.count('Index Scan') + plan.count('Seq Scan')})
        print('  잎 %-3d 중앙 %s ms  최대 %s ms  스캔 노드 %d'
              % (k, out[-1]['중앙값(ms)'], out[-1]['최대(ms)'], out[-1]['Append 자식']), flush=True)
        psql("DROP TABLE IF EXISTS fan_probe CASCADE;")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--secs', type=int, default=30)
    ap.add_argument('--levels', default='1,2,4,8,12,24')
    ap.add_argument('--leaves', default='3,15,67')
    ap.add_argument('--rows-per-leaf', type=int, default=15000)
    ap.add_argument('--reps', type=int, default=9)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    res = {'settings': {}}
    for k in ('synchronous_commit', 'wal_sync_method', 'commit_delay', 'commit_siblings',
              'max_wal_size', 'shared_buffers'):
        res['settings'][k] = psql("SHOW %s;" % k)
    print('설정 %s' % res['settings'], flush=True)

    print('\n(a) 동시 커밋 확장성', flush=True)
    res['commit_ladder'] = commit_ladder(a.secs, [int(x) for x in a.levels.split(',')])
    base = next((r['커밋/s'] for r in res['commit_ladder'] if r['동시'] == 1), None)
    twelve = next((r['커밋/s'] for r in res['commit_ladder'] if r['동시'] == 12), None)
    if base and twelve:
        res['배수_12대1'] = round(twelve / base, 2)
        res['게이트'] = '분기 B (동시 커밋 확장)' if twelve / base >= 3 else '분기 A (직렬 장치)'
        print('  12/1 배수 %.2f -> %s' % (res['배수_12대1'], res['게이트']), flush=True)

    print('\n(e) 파티션 잎당 팬아웃', flush=True)
    res['leaf_fanout'] = leaf_fanout([int(x) for x in a.leaves.split(',')],
                                     a.rows_per_leaf, a.reps)
    io.open(a.out, 'w', encoding='utf-8').write(json.dumps(res, ensure_ascii=False, indent=2))
    print('\n저장 %s' % a.out, flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
