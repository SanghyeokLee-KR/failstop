"""주행 중 PostgreSQL 표본. ADR 0022 4단계 카운터 행 대기와 카오스 스택 연결 예산.

카운터 행 대기는 4단계 반증 팔이다(ADR 0022 7절 4단계). 연결 표본은 append 풀을 키운 판이
max_connections 에 닿는지를 가른다. 닿으면 장애 판정과 섞지 않고 증거 불완전으로 뗀다.

표본마다 ok 가 있다. 거짓이면 수치 칸이 None 이고 error 에 사유가 있다. 채집 실패를 0 으로 접지 않는다.

돌리는 법:
  python harness/scale/pg_samplers.py connections --out <jsonl> --stop-file <path>
  python harness/scale/pg_samplers.py summarize --samples <jsonl> --out <json>
  python harness/scale/pg_samplers.py budget --append-pool 14 --max-connections 200 --out <json>
"""
import argparse
import json
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import qv  # noqa: E402

PSQL = ['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-X', '-q', '-t', '-A']

COUNTER_ROW_RULE = (
    '머리말 질의(query 에 append_command_prologue)가 활성이고, 미획득 잠금이 transactionid 또는 tuple 이며, '
    '같은 백엔드가 journal_counters 표 잠금을 쥐면 카운터 행 대기 1 로 센다. ok 거짓 표본은 채집 실패이고 '
    '대기 수를 채우지 않는다.')

# pg_stat_activity.query 는 최상위 문장이라 plpgsql 안의 채번 UPDATE 대기도 머리말 호출로 보인다.
# 머리말에서 journal_counters 를 잠그는 문장은 끝의 채번 하나라 표 잠금이 곧 그 행 대기의 표지다(V39).
COUNTER_ROW_SQL = """
WITH p AS (
  SELECT a.pid, pg_blocking_pids(a.pid) AS blockers
  FROM pg_stat_activity a
  WHERE a.datname = current_database() AND a.backend_type = 'client backend'
    AND a.state = 'active' AND a.pid <> pg_backend_pid()
    AND a.query ILIKE '%append_command_prologue%'
), w AS (
  SELECT p.pid, p.blockers,
    EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = p.pid AND NOT l.granted) AS waiting,
    EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = p.pid AND NOT l.granted
            AND l.locktype = 'advisory') AS advisory,
    EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = p.pid AND NOT l.granted
            AND l.locktype IN ('transactionid', 'tuple'))
    AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = p.pid AND l.locktype = 'relation'
                AND l.relation = to_regclass('journal_counters')) AS counter_row
  FROM p
)
SELECT json_build_object(
  'counter_rel', to_regclass('journal_counters') IS NOT NULL,
  'prologue_active', (SELECT count(*) FROM w),
  'prologue_lock_wait', (SELECT count(*) FROM w WHERE waiting),
  'advisory_wait', (SELECT count(*) FROM w WHERE advisory),
  'counter_row_wait', (SELECT count(*) FROM w WHERE counter_row),
  'waiters', (SELECT coalesce(json_agg(json_build_object('pid', pid, 'blockers', blockers,
                'advisory', advisory, 'counter_row', counter_row)), '[]'::json)
              FROM w WHERE waiting))
"""
COUNTER_KEYS = ('prologue_active', 'prologue_lock_wait', 'advisory_wait', 'counter_row_wait', 'waiters')

# 분류기 양성 대조. 한 세션이 카운터 행을 쥔 채 머리말 표식을 단 두 번째 세션이 같은 행을 기다리게 한다.
# 둘 다 ROLLBACK 이라 값이 바뀌지 않는다. 쥐는 동안 실제 append 도 멈추므로 부하가 끝난 뒤에만 부른다.
CONTROL_HOLD_S = 8
CONTROL_HOLDER_SQL = ("BEGIN; SELECT next_value FROM journal_counters WHERE name = 'command_seq' "
                      "FOR NO KEY UPDATE; SELECT pg_sleep(%d); ROLLBACK;" % CONTROL_HOLD_S)
CONTROL_WAITER_SQL = ("BEGIN; SELECT 'append_command_prologue' AS control FROM journal_counters "
                      "WHERE name = 'command_seq' FOR NO KEY UPDATE; ROLLBACK;")

CONNECTIONS_SQL = """
SELECT json_build_object(
  'max_connections', current_setting('max_connections')::int,
  'superuser_reserved_connections', current_setting('superuser_reserved_connections')::int,
  'reserved_connections', coalesce(current_setting('reserved_connections', true), '0')::int,
  'client_backends', (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'),
  'groups', (SELECT coalesce(json_agg(g ORDER BY g.n DESC), '[]'::json) FROM (
      SELECT a.application_name, a.usename, host(a.client_addr) AS client_addr, count(*) AS n,
             count(*) FILTER (WHERE a.state = 'active') AS active,
             count(*) FILTER (WHERE a.state = 'idle') AS idle,
             count(*) FILTER (WHERE a.state LIKE 'idle in transaction%') AS idle_in_tx,
             bool_or(r.rolsuper) AS superuser
      FROM pg_stat_activity a LEFT JOIN pg_roles r ON r.rolname = a.usename
      WHERE a.backend_type = 'client backend'
      GROUP BY a.application_name, a.usename, host(a.client_addr)) g))
"""
CONNECTION_KEYS = ('max_connections', 'superuser_reserved_connections', 'reserved_connections',
                   'client_backends', 'groups')

# 서버가 연결을 거절할 때 남기는 FATAL 문구 둘.
EXHAUSTION_PATTERNS = ('too many clients', 'remaining connection slots are reserved')


def now_iso():
    return datetime.now().astimezone().isoformat(timespec='seconds')


def run_psql(sql, timeout=30):
    try:
        p = subprocess.run(PSQL + ['-c', sql], capture_output=True, text=True,
                           encoding='utf-8', errors='replace', timeout=timeout)
        return p.returncode, p.stdout, p.stderr
    except (subprocess.SubprocessError, OSError) as e:
        return None, '', type(e).__name__


def parse_json_row(rc, stdout, stderr):
    if rc != 0:
        return None, 'psql 종료코드 %s: %s' % (rc, (stderr or '').strip()[-200:])
    text = (stdout or '').strip()
    if not text:
        return None, 'psql 빈 출력'
    try:
        return json.loads(text), None
    except ValueError:
        return None, 'JSON 해석 실패: %s' % text[:120]


def counter_row_record(parsed, error):
    rec = {'ok': False, 'error': error}
    rec.update({k: None for k in COUNTER_KEYS})
    if parsed is None:
        return rec
    if not parsed.get('counter_rel'):
        rec['error'] = 'journal_counters 를 해석하지 못해 카운터 행 대기를 가를 수 없다'
        return rec
    rec.update({k: parsed.get(k) for k in COUNTER_KEYS})
    rec['ok'] = True
    return rec


def connection_record(parsed, error):
    rec = {'ok': False, 'error': error}
    rec.update({k: None for k in CONNECTION_KEYS})
    if parsed is None:
        return rec
    rec.update({k: parsed.get(k) for k in CONNECTION_KEYS})
    rec['ok'] = True
    return rec


def sample_counter_row():
    rec = counter_row_record(*parse_json_row(*run_psql(COUNTER_ROW_SQL)))
    rec['ts'] = now_iso()
    return rec


def sample_connections():
    rec = connection_record(*parse_json_row(*run_psql(CONNECTIONS_SQL)))
    rec['ts'] = now_iso()
    # api-1 과 api-2 는 둘 다 toxiproxy 를 지나 client_addr 로 갈리지 않는다. 풀 쪽 계측으로 가른다.
    rows = qv('sum by (job, instance, pool) (hikaricp_connections)')
    rec['hikari'] = None if rows is None else [dict(m, connections=v) for m, v in rows]
    return rec


def summarize_counter_samples(samples):
    """k6 가 도는 동안의 표본만 센다. 실패 표본은 성공 수에 넣지 않는다."""
    run = [s for s in samples if s.get('k6_alive')]
    ok = [s for s in run if s.get('ok')]
    waits = [s['counter_row_wait'] for s in ok]
    return {
        '주행 중 표본': len(run),
        '채집 성공': len(ok),
        '채집 실패': len(run) - len(ok),
        '대기 > 0 표본': sum(1 for w in waits if w > 0) if ok else None,
        '대기 합': sum(waits) if ok else None,
        '대기 최대': max(waits) if ok else None,
        '머리말 활성 > 0 표본': sum(1 for s in ok if s['prologue_active'] > 0) if ok else None,
    }


def control_verdict(samples, holder_rc, waiter_rc):
    ok = [s for s in samples if s.get('ok')]
    hit = [s for s in ok if (s.get('counter_row_wait') or 0) >= 1
           and any(w.get('counter_row') and w.get('blockers') for w in (s.get('waiters') or []))]
    if hit:
        state = 'PASS'
    elif ok and holder_rc == 0 and waiter_rc == 0:
        state = 'FAIL'
    else:
        state = 'NOT RUN'
    return {'상태': state, '보유 세션 종료코드': holder_rc, '대기 세션 종료코드': waiter_rc, '표본': samples}


def counter_row_positive_control():
    kw = dict(stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='replace')
    try:
        holder = subprocess.Popen(PSQL + ['-c', CONTROL_HOLDER_SQL], **kw)
    except OSError as e:
        return control_verdict([], type(e).__name__, None)
    time.sleep(2.5)
    try:
        waiter = subprocess.Popen(PSQL + ['-c', CONTROL_WAITER_SQL], **kw)
    except OSError as e:
        waiter = None
        waiter_err = type(e).__name__
    samples = []
    if waiter is not None:
        time.sleep(2.5)
        for _ in range(3):
            s = sample_counter_row()
            samples.append(s)
            if s['ok'] and s['counter_row_wait'] >= 1:
                break
            time.sleep(1)

    def finish(proc):
        try:
            proc.communicate(timeout=CONTROL_HOLD_S + 30)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate()
        return proc.returncode

    holder_rc = finish(holder)
    waiter_rc = finish(waiter) if waiter is not None else waiter_err
    return control_verdict(samples, holder_rc, waiter_rc)


def summarize_connection_samples(samples):
    ok = [s for s in samples if s.get('ok')]
    out = {'표본': len(samples), '성공': len(ok), '실패': len(samples) - len(ok)}
    if not ok:
        out.update({'max_connections': None, 'superuser_reserved_connections': None, '한도': None,
                    '최대 연결': None, '최대 시각': None, '최대 시 분해': None, '최대 시 풀': None,
                    '한도 도달': None})
        return out
    # 판정 한도는 max_connections - superuser_reserved_connections 다(연결 고갈 분리 규칙).
    limit = min(s['max_connections'] - s['superuser_reserved_connections'] for s in ok)
    peak = max(ok, key=lambda s: s['client_backends'])
    out.update({
        'max_connections': peak['max_connections'],
        'superuser_reserved_connections': peak['superuser_reserved_connections'],
        '한도': limit,
        '최대 연결': peak['client_backends'],
        '최대 시각': peak.get('ts'),
        '최대 시 분해': peak.get('groups'),
        '최대 시 풀': peak.get('hikari'),
        '한도 도달': peak['client_backends'] >= limit,
    })
    return out


def count_exhaustion_lines(text):
    lines = [ln for ln in (text or '').splitlines() if any(p in ln for p in EXHAUSTION_PATTERNS)]
    return {'줄 수': len(lines), '예시': lines[:3]}


def pg_log_exhaustion():
    """chaos-pg 컨테이너 수명 전체의 로그를 본다. down 이 컨테이너를 지우므로 그 전에 부른다."""
    try:
        p = subprocess.run(['docker', 'logs', 'chaos-pg'], capture_output=True, text=True,
                           encoding='utf-8', errors='replace', timeout=120)
    except (subprocess.SubprocessError, OSError) as e:
        return {'ok': False, 'error': type(e).__name__, '줄 수': None, '예시': None}
    if p.returncode != 0:
        return {'ok': False, 'error': (p.stderr or '').strip()[-200:], '줄 수': None, '예시': None}
    out = count_exhaustion_lines((p.stdout or '') + '\n' + (p.stderr or ''))
    out.update({'ok': True, 'error': None})
    return out


def connection_verdict(summary, log):
    """고갈이 하나라도 보이면 증거 불완전, 표본과 로그가 둘 다 확인됐고 안 보이면 고갈 없음이다."""
    by_sample = (summary or {}).get('한도 도달')
    log = log or {}
    by_log = ((log.get('줄 수') or 0) > 0) if log.get('ok') else None
    if by_sample or by_log:
        state = '증거 불완전(연결 고갈)'
    elif by_sample is False and by_log is False:
        state = '고갈 없음'
    else:
        state = 'NOT RUN'
    return {'상태': state, '표본 한도 도달': by_sample, '로그 고갈 줄': log.get('줄 수'), '로그 확인': log.get('ok')}


def connection_budget(api_nodes, request_pool, lease_pool, append_pool, bots_request_pool, admin_request_pool,
                      exporter, tooling, max_connections, superuser_reserved):
    """노드마다 요청 풀, 리스 풀, append 풀을 전부 쥔 이론 최대다. 풀 셋은 프로파일과 무관하게 생긴다
    (LeaseDataSourceConfig). 봇과 관리 노드도 셋을 다 갖는다."""
    api = api_nodes * (request_pool + lease_pool + append_pool)
    bots = (bots_request_pool + lease_pool + append_pool) if bots_request_pool else 0
    admin = (admin_request_pool + lease_pool + append_pool) if admin_request_pool else 0
    total = api + bots + admin + exporter + tooling
    limit = max_connections - superuser_reserved
    return {
        '입력': {'api_nodes': api_nodes, 'request_pool': request_pool, 'lease_pool': lease_pool,
                'append_pool': append_pool, 'bots_request_pool': bots_request_pool,
                'admin_request_pool': admin_request_pool, 'exporter': exporter, 'tooling': tooling,
                'max_connections': max_connections, 'superuser_reserved_connections': superuser_reserved},
        'API 노드': api, '봇': bots, '관리': admin, '합': total, '한도': limit, '여유': limit - total,
        '판정': '충분' if total < limit else '부족',
    }


def live_pool_max():
    rows = qv('sum by (job, instance, pool) (hikaricp_connections_max)')
    if rows is None:
        return None
    return {'풀': [dict(m, max=v) for m, v in rows], '합': sum(v for _, v in rows)}


def write_json(path, obj):
    Path(path).write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding='utf-8')


def cmd_connections(a):
    stop = Path(a.stop_file)
    end = time.monotonic() + a.max_secs
    next_t = time.monotonic()
    with open(a.out, 'a', encoding='utf-8') as f:
        while not stop.exists() and time.monotonic() < end:
            f.write(json.dumps(sample_connections(), ensure_ascii=False) + '\n')
            f.flush()
            next_t += a.interval
            while not stop.exists() and time.monotonic() < next_t:
                time.sleep(0.5)
    return 0


def cmd_summarize(a):
    samples = []
    try:
        for line in Path(a.samples).read_text(encoding='utf-8').splitlines():
            if line.strip():
                samples.append(json.loads(line))
    except (OSError, ValueError) as e:
        print('표본 파일을 읽지 못했다: %s' % e, flush=True)
    summary = summarize_connection_samples(samples)
    log = pg_log_exhaustion()
    verdict = connection_verdict(summary, log)
    write_json(a.out, {'요약': summary, '로그': log, '판정': verdict})
    print('연결 판정 %s. 최대 %s / 한도 %s, 로그 고갈 줄 %s' % (
        verdict['상태'], summary['최대 연결'], summary['한도'], log.get('줄 수')), flush=True)
    return {'고갈 없음': 0, '증거 불완전(연결 고갈)': 1}.get(verdict['상태'], 2)


def cmd_budget(a):
    max_conn, reserved = a.max_connections, a.superuser_reserved
    live = None
    if a.live:
        s = sample_connections()
        live = {'설정 표본': {k: s.get(k) for k in ('ok', 'error', 'max_connections',
                                                   'superuser_reserved_connections', 'client_backends')},
                '풀 상한': live_pool_max()}
        if not s['ok']:
            write_json(a.out, {'실측': live, '판정': 'NOT RUN'})
            print('연결 설정을 읽지 못했다: %s' % s['error'], flush=True)
            return 2
        max_conn, reserved = s['max_connections'], s['superuser_reserved_connections']
    if max_conn is None:
        print('--max-connections 또는 --live 가 필요하다', flush=True)
        return 2
    out = connection_budget(a.api_nodes, a.request_pool, a.lease_pool, a.append_pool, a.bots_request_pool,
                            a.admin_request_pool, a.exporter, a.tooling, max_conn, reserved)
    out['실측'] = live
    write_json(a.out, out)
    print('연결 예산 %s. 합 %d / 한도 %d' % (out['판정'], out['합'], out['한도']), flush=True)
    return 0 if out['판정'] == '충분' else 1


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    c = sub.add_parser('connections')
    c.add_argument('--out', required=True)
    c.add_argument('--stop-file', required=True)
    c.add_argument('--interval', type=float, default=5.0)
    c.add_argument('--max-secs', type=int, default=3600)
    s = sub.add_parser('summarize')
    s.add_argument('--samples', required=True)
    s.add_argument('--out', required=True)
    b = sub.add_parser('budget')
    b.add_argument('--api-nodes', type=int, default=2)
    b.add_argument('--request-pool', type=int, default=32)
    b.add_argument('--lease-pool', type=int, default=2)
    b.add_argument('--append-pool', type=int, required=True)
    b.add_argument('--bots-request-pool', type=int, default=10, help='bots 프로파일은 요청 풀을 정하지 않아 Hikari 기본 10')
    b.add_argument('--admin-request-pool', type=int, default=0, help='chaos-admin 을 붙일 때만. dev 기본 16')
    b.add_argument('--exporter', type=int, default=2)
    b.add_argument('--tooling', type=int, default=4, help='표본기와 하네스 psql 세션')
    b.add_argument('--max-connections', type=int, default=None)
    b.add_argument('--superuser-reserved', type=int, default=3)
    b.add_argument('--live', action='store_true', help='max_connections 를 떠 있는 chaos-pg 에서 읽는다')
    b.add_argument('--out', required=True)
    a = ap.parse_args()
    return {'connections': cmd_connections, 'summarize': cmd_summarize, 'budget': cmd_budget}[a.cmd](a)


if __name__ == '__main__':
    sys.exit(main())
