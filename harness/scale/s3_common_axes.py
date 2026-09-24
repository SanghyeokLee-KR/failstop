"""S3 관문 장애 실험(X2~X4)의 공통 판정 축(마켓분할_리더_S3_설계 7-3). harness/scripts/adr0022-stage5-chaos.ps1 -Protocol 2 가 부른다.

하위 명령
  sample  실험이 도는 동안 잔고 행 대기를 표본으로 뜬다. 행 잠금을 기다리는 백엔드는 balances 의 tuple 잠금을 쥐거나 기다리므로
          ctid 로 계정을 찾아 사용자 행과 시스템 행(수수료 등)을 나누고, 그 백엔드의 현재 문장 경과(query_start 부터)를 대기
          상한으로 적는다. 멈춘 리더가 append 도중이면 잔고 행을 쥔 채 멈추므로 E7 의 기록 항목이다.
  judge   실험 스크립트가 끝난 뒤, 스택을 내리기 전에 부른다.
          1. 봇을 멈추고(docker stop) 정산 전 NEW 가 0 이 될 때까지 기다린다(상한 120초).
          2. 프로메테우스에서 스택 수명 창의 S3 계수 증가를 읽는다(노드가 재기동해도 increase 가 이어 센다). 정산 23514,
             잠금 계획 이탈, 접수 append 40P01, 주문 접수 500(노드별), 정산 일시 오류 재시도(SQLSTATE 별), 정산 전 예약 게이지
             최댓값, ReservationUnsettledStale 경보 표본.
          3. 살아 있는 노드 actuator 를 직접 긁어 같은 계수의 누계를 남긴다. 프로메테우스 첫 스크레이프 전 증가를 보완한다.
          4. pg_stat_database 교착 누계(실험마다 새 스택이라 스택 수명의 값), 예약 행 수, 드레인 뒤 정산 전 예약 수, 승격 뒤
             정산된 예약 명령 수(저널 epoch 와 정산 이벤트 epoch 가 다른 예약 명령. 재적재된 예약 명령이 있었는지의 근거).
          5. 하네스 summary.sql 을 소유자 로그인으로 돌려 CRITICAL·HIGH 행이 모두 PASS 인지 본다.
          6. 앱 컨테이너 로그의 라이터 정지·강등·권한·교착 문구 줄 수. 본문은 남기지 않는다. 강등 문구는 장애를 받은 옛 리더에게
             설계상 나올 수 있으므로(E5·E7) 판정은 실험 끝의 리더 노드만 본다. S4 는 강등 문구를 파티션 강등과 노드 치명으로
             바꿨다(S4 설계 4-16). 문구가 코드와 어긋나면 이 축이 공허하게 통과하므로, --expect-demotion 이면 실험 끝의 리더가
             아닌 노드(옛 리더)에서 강등 문구가 1 이상 잡히는지를 양성 대조로 본다.
판정은 축마다 참·거짓으로 적고, 하나라도 거짓이면 ok=false 다. 폐기용 스택 가드는 protocol_latch 와 같다.

사용:
  python harness/scale/s3_common_axes.py sample --out <jsonl> --stop-file <경로> [--interval 2]
  python harness/scale/s3_common_axes.py judge --window-from <ISO UTC> --fault-from <ISO UTC> --fault-to <ISO UTC>
         [--samples <jsonl>] [--resource matching-engine] [--expect-demotion] --out <json>
종료코드: 0 판정 통과, 1 판정 실패, 2 가드 위반.
"""
import argparse
import datetime as dt
import json
import math
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402
import protocol_latch as pl  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PROM = 'http://localhost:59090'
NODES = (('chaos-api-1', 18080), ('chaos-api-2', 28080))
DRAIN_SQL = ("SELECT count(*) FROM command_journal c WHERE c.command_type = 'NEW' "
             "AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq)")

BALANCE_WAIT_SQL = """
SELECT json_build_object(
  'ts', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
  'lock_waiters', (SELECT count(*) FROM pg_stat_activity WHERE datname = 'exchange' AND wait_event_type = 'Lock'),
  'balance_rows', (SELECT coalesce(json_agg(json_build_object('pid', t.pid, 'kind', t.kind, 'waited_ms', t.waited_ms)),
                                   '[]'::json) FROM (
        SELECT DISTINCT ON (l.pid) l.pid, coalesce(ac.owner_type || ':' || ac.purpose, 'unknown') AS kind,
               floor(EXTRACT(EPOCH FROM clock_timestamp() - a.query_start) * 1000) AS waited_ms
          FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
          LEFT JOIN balances b ON b.ctid = format('(%s,%s)', l.page, l.tuple)::tid
          LEFT JOIN accounts ac ON ac.id = b.account_id
         WHERE l.locktype = 'tuple' AND l.relation = 'balances'::regclass AND a.wait_event_type = 'Lock'
         ORDER BY l.pid) t))
"""

STATE_SQL_TEMPLATE = """
SELECT json_build_object(
  'deadlocks', (SELECT deadlocks FROM pg_stat_database WHERE datname = 'exchange'),
  'reservations', (SELECT count(*) FROM order_reservations),
  'unsettled_new', (%s),
  'unsettled_reservations', (SELECT count(*) FROM order_reservations r
        WHERE NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)),
  'reserved_settled_by_later_epoch', (SELECT count(*) FROM order_reservations r
        JOIN command_journal c ON c.seq = r.command_seq
        JOIN event_journal e ON e.source_cmd_seq = r.command_seq AND e.event_ordinal = 0
        WHERE e.fencing_epoch <> c.fencing_token),
  'protocol', (SELECT protocol FROM leader_protocol),
  'leader', (SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > clock_timestamp())
               FROM leader_lease WHERE resource = '{resource}'))
""".replace('%s', DRAIN_SQL)
LEASE_RESOURCES = ('matching-engine', 'matching-p0', 'matching-p1')


def state_sql(resource='matching-engine'):
    """공통 판정의 DB 상태 질의. 리더는 그 실험이 옮긴 리스 자원의 행이다(S4 설계 4-18)."""
    if resource not in LEASE_RESOURCES:
        raise ValueError('알 수 없는 리스 자원: %s' % resource)
    return STATE_SQL_TEMPLATE.replace('{resource}', resource)


STATE_SQL = state_sql()

# 강등 문구(S4). '리더십 상실'은 리스가 놓은 기간, '파티션 강등'은 파티션 수명주기의 강등, '종신 강등'은 노드 치명이다.
DEMOTION_KEYS = ('terminalDemotion', 'leadershipLost', 'partitionDemotion')
LOG_PATTERNS = {
    'writerFailFast': 'fail-fast', 'terminalDemotion': '종신 강등', 'leadershipLost': '리더십 상실',
    'partitionDemotion': '파티션 강등',
    'leadershipReleased': '리더십 반납', 'promotions': '리더 승격', 'permissionDenied': 'permission denied',
    'sqlstate42501': '42501', 'deadlock40P01': '40P01', 'deadlockDetected': 'deadlock detected',
    'settlementInsufficient': 'chk_balance_nonneg',
}
NODE_COUNTERS = ('exchange_settlement_insufficient_funds_total', 'exchange_settlement_lock_plan_miss_total',
                 'exchange_order_append_transient_total', 'exchange_settlement_transient_retries_total',
                 'exchange_settlement_transient_exhausted_total', 'exchange_order_reserve_rejected_total')
PROM_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+(\S+)')


def parse_iso(s):
    return dt.datetime.fromisoformat(s.replace('Z', '+00:00')).astimezone(dt.timezone.utc)


def prom(path, params):
    url = PROM + path + '?' + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            body = json.load(r)
        return body.get('data', {}).get('result')
    except Exception as e:
        return {'error': type(e).__name__}


def prom_scalar(expr, at):
    res = prom('/api/v1/query', {'query': expr, 'time': '%.3f' % at})
    if isinstance(res, dict):
        return res
    if not res:
        return 0.0
    if len(res) == 1 and not res[0].get('metric'):
        return float(res[0]['value'][1])
    return [{'metric': x['metric'], 'value': float(x['value'][1])} for x in res]


def total(v):
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, list):
        return sum(x['value'] for x in v)
    return None


def scrape(port):
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=10) as r:
            text = r.read().decode('utf-8', 'replace')
    except Exception as e:
        return {'ok': False, 'error': type(e).__name__}
    out = {'ok': True}
    for line in text.splitlines():
        m = PROM_LINE.match(line)
        if not m or m.group(1) not in NODE_COUNTERS:
            continue
        labels = m.group(2) or ''
        tag = re.search(r'(sqlstate|source)="([^"]+)"', labels)
        key = m.group(1) + ('{%s=%s}' % (tag.group(1), tag.group(2)) if tag else '')
        out[key] = out.get(key, 0.0) + float(m.group(3))
    return out


def log_counts(container):
    p = subprocess.run(['docker', 'logs', container], capture_output=True, text=True, encoding='utf-8', errors='replace',
                       timeout=120)
    if p.returncode != 0:
        return {'ok': False}
    lines = (p.stdout + p.stderr).splitlines()
    res = {'ok': True, 'lines': len(lines)}
    for k, pat in LOG_PATTERNS.items():
        res[k] = sum(1 for x in lines if pat in x)
    return res, lines


def run_summary():
    sql = (ROOT / 'harness/sql/summary.sql').read_text(encoding='utf-8')
    p = subprocess.run(['docker', 'exec', '-i', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-X', '-q', '-t', '-A',
                        '-F', '|', '-v', 'ON_ERROR_STOP=1'], input=sql, capture_output=True, text=True, encoding='utf-8',
                       errors='replace', timeout=600)
    rows = []
    for line in p.stdout.splitlines():
        f = line.split('|')
        if len(f) == 6 and re.match(r'^C\d', f[0]):
            rows.append({'code': f[0], 'check': f[1], 'severity': f[2], 'scope': f[3],
                         'violated_rows': None if f[4] == '' else int(f[4]), 'verdict': f[5]})
    gate = [r for r in rows if r['severity'] in ('CRITICAL', 'HIGH')]
    bad = ['%s=%s(%s)' % (r['code'], r['violated_rows'], r['verdict']) for r in gate if r['verdict'] != 'PASS']
    return {'rc': p.returncode, 'rows': len(rows), 'gate_rows': len(gate), 'not_pass': bad,
            'ok': p.returncode == 0 and len(gate) > 0 and not bad, 'table': rows}


def cmd_sample(a):
    g = pl.guard()
    if not g['ok']:
        print('가드 위반: ' + '; '.join(g['problems']), flush=True)
        return 2
    stop = Path(a.stop_file)
    with open(a.out, 'a', encoding='utf-8') as fh:
        while not stop.exists():
            parsed, err = pgs.parse_json_row(*pgs.run_psql(BALANCE_WAIT_SQL, timeout=20))
            fh.write(json.dumps(parsed if parsed is not None else {'error': err, 'ts': pgs.now_iso()},
                                ensure_ascii=False) + '\n')
            fh.flush()
            end = time.monotonic() + a.interval
            while time.monotonic() < end and not stop.exists():
                time.sleep(0.2)
    return 0


def summarize_samples(path):
    rows = []
    if path and Path(path).exists():
        for line in Path(path).read_text(encoding='utf-8').splitlines():
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass
    ok = [r for r in rows if 'balance_rows' in r]
    kinds = {}
    for r in ok:
        for b in r['balance_rows'] or []:
            k = kinds.setdefault(b['kind'], {'samples_with_waiter': 0, 'max_waiters': 0, 'max_waited_ms': 0})
            k['max_waited_ms'] = max(k['max_waited_ms'], b.get('waited_ms') or 0)
        per = {}
        for b in r['balance_rows'] or []:
            per[b['kind']] = per.get(b['kind'], 0) + 1
        for kind, n in per.items():
            kinds[kind]['samples_with_waiter'] += 1
            kinds[kind]['max_waiters'] = max(kinds[kind]['max_waiters'], n)
    return {'samples': len(rows), 'errors': len(rows) - len(ok),
            'max_lock_waiters': max((r.get('lock_waiters') or 0) for r in ok) if ok else None,
            'balance_row_waits': kinds}


def cmd_judge(a):
    g = pl.guard()
    if not g['ok']:
        out = {'ok': False, 'code': 2, 'guard': g, 'error': '가드 위반: ' + '; '.join(g['problems'])}
        Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
        return 2
    res = {'started': pgs.now_iso(), 'window_from': a.window_from, 'fault_from': a.fault_from, 'fault_to': a.fault_to}
    res['bots_stop_rc'] = subprocess.run(['docker', 'stop', 'chaos-bots'], capture_output=True, timeout=120).returncode
    start = time.monotonic()
    streak = 0
    drained = False
    while time.monotonic() - start < 120:
        rc, out, _ = pgs.run_psql(DRAIN_SQL)
        v = out.strip() if rc == 0 else ''
        streak = streak + 1 if v == '0' else 0
        if streak >= 2:
            drained = True
            break
        time.sleep(2)
    res['drain'] = {'ok': drained, 'seconds': round(time.monotonic() - start, 1)}
    # 마지막 증가가 프로메테우스(2초 스크레이프)에 닿고 게이지(15초 주기)가 새로 계산되도록 기다린다.
    time.sleep(20)
    now = time.time()
    w0 = parse_iso(a.window_from).timestamp()
    win = int(math.ceil(now - w0)) + 30
    qs = {
        'settlement_insufficient_funds': 'sum(increase(exchange_settlement_insufficient_funds_total[%ds]))' % win,
        'lock_plan_miss': 'sum(increase(exchange_settlement_lock_plan_miss_total[%ds]))' % win,
        'lock_plan_taken': 'sum(increase(exchange_settlement_lock_plan_taken_total[%ds]))' % win,
        'append_40P01': 'sum(increase(exchange_order_append_transient_total{sqlstate="40P01"}[%ds]))' % win,
        'orders_500_by_instance': ('sum by (instance) (increase(http_server_requests_seconds_count'
                                   '{uri="/api/v1/orders",method="POST",status="500"}[%ds]))' % win),
        'orders_by_status': ('sum by (status) (increase(http_server_requests_seconds_count'
                             '{uri="/api/v1/orders",method="POST"}[%ds]))' % win),
        'settlement_retries_by_state': 'sum by (sqlstate) (increase(exchange_settlement_transient_retries_by_state_total[%ds]))' % win,
        'settlement_transient_exhausted': 'sum(increase(exchange_settlement_transient_exhausted_total[%ds]))' % win,
        'reserve_rejected_by_source': 'sum by (source) (increase(exchange_order_reserve_rejected_total[%ds]))' % win,
        'gauge_oldest_max_s': 'max(max_over_time(exchange_reservation_unsettled_oldest_seconds[%ds]))' % win,
        'gauge_count_max': 'max(max_over_time(exchange_reservation_unsettled_count[%ds]))' % win,
        'protocol_boot': 'max by (instance) (exchange_leader_protocol_boot)',
    }
    res['prom'] = {k: prom_scalar(e, now) for k, e in qs.items()}
    res['prom_window_s'] = win
    alerts = prom('/api/v1/query_range', {'query': 'ALERTS{alertname="ReservationUnsettledStale"}',
                                          'start': '%.0f' % w0, 'end': '%.0f' % now, 'step': '5'})
    fault_from = parse_iso(a.fault_from).timestamp()
    fault_to = parse_iso(a.fault_to).timestamp()
    samples = []
    if isinstance(alerts, list):
        for series in alerts:
            for ts, _ in series.get('values', []):
                samples.append({'state': series['metric'].get('alertstate'), 't': float(ts)})
    outside = [s for s in samples if s['state'] == 'firing' and not (fault_from <= s['t'] <= fault_to)]
    res['stale_alert'] = {'samples': len(samples), 'firing': sum(1 for s in samples if s['state'] == 'firing'),
                          'firing_outside_fault_window': len(outside),
                          'error': alerts.get('error') if isinstance(alerts, dict) else None}
    res['nodes'] = {name: scrape(port) for name, port in NODES}
    parsed, err = pgs.parse_json_row(*pgs.run_psql(state_sql(a.resource), timeout=120))
    res['state'] = parsed if parsed is not None else {'error': err}
    res['summary'] = run_summary()
    logs = {}
    holder = ((parsed or {}).get('leader') or {}).get('holder') or ''
    leader_node = None
    for name, _ in NODES:
        got = log_counts(name)
        if isinstance(got, tuple):
            counts, lines = got
            if holder and any(holder in x for x in lines):
                leader_node = name
            logs[name] = counts
        else:
            logs[name] = got
    got = log_counts('chaos-bots')
    logs['chaos-bots'] = got[0] if isinstance(got, tuple) else got
    res['logs'] = logs
    res['leader_node'] = leader_node
    res['balance_samples'] = summarize_samples(a.samples)

    st = res['state'] if parsed is not None else {}
    node_insufficient = sum(v.get('exchange_settlement_insufficient_funds_total', 0.0)
                            for v in res['nodes'].values() if v.get('ok'))
    node_miss = sum(v.get('exchange_settlement_lock_plan_miss_total', 0.0) for v in res['nodes'].values() if v.get('ok'))
    node_40p01 = sum(v.get('exchange_order_append_transient_total{sqlstate=40P01}', 0.0)
                     for v in res['nodes'].values() if v.get('ok'))
    lead = logs.get(leader_node) or {}
    axes = {
        'invariants_summary': res['summary']['ok'],
        'settlement_insufficient_funds_0': total(res['prom']['settlement_insufficient_funds']) == 0 and node_insufficient == 0,
        'lock_plan_miss_0': total(res['prom']['lock_plan_miss']) == 0 and node_miss == 0,
        'append_40P01_0': total(res['prom']['append_40P01']) == 0 and node_40p01 == 0,
        'orders_500_0': total(res['prom']['orders_500_by_instance']) == 0,
        'pg_deadlocks_0': st.get('deadlocks') == 0,
        'reservations_gt_0': (st.get('reservations') or 0) > 0,
        'unsettled_reservations_after_drain_0': drained and st.get('unsettled_reservations') == 0,
        'leader_node_no_fatal_or_demotion': bool(leader_node) and all(
            lead.get(k, 1) == 0 for k in ('writerFailFast',) + DEMOTION_KEYS),
        'no_permission_errors': all((c or {}).get('permissionDenied', 0) + (c or {}).get('sqlstate42501', 0) == 0
                                    for c in logs.values()),
        'stale_alert_outside_fault_window_0': res['stale_alert']['error'] is None
                                              and res['stale_alert']['firing_outside_fault_window'] == 0,
        'protocol_2': st.get('protocol') == 2,
    }
    if a.expect_demotion:
        # 양성 대조(S4 설계 4-16, 7-2). 장애를 받은 옛 리더가 강등 문구를 남겨야 위 리더 노드 축의 0 이 문구 불일치가 아님을 안다.
        axes['old_leader_demotion_logged'] = demotion_logged(logs, leader_node)
    res['axes'] = axes
    res['ok'] = all(axes.values())
    res['finished'] = pgs.now_iso()
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    print(json.dumps({'ok': res['ok'], 'failed': [k for k, v in axes.items() if not v],
                      'reservations': st.get('reservations'),
                      'reserved_settled_by_later_epoch': st.get('reserved_settled_by_later_epoch'),
                      'deadlocks': st.get('deadlocks'), 'leader_node': leader_node}, ensure_ascii=False), flush=True)
    return 0 if res['ok'] else 1


def demotion_logged(logs, leader_node):
    """실험 끝의 리더가 아닌 api 노드 가운데 하나라도 강등 문구를 남겼는가."""
    others = [c for name, c in logs.items() if name != leader_node and name != 'chaos-bots' and isinstance(c, dict)]
    return any(sum(c.get(k, 0) for k in DEMOTION_KEYS) >= 1 for c in others)


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('sample')
    s.add_argument('--out', required=True)
    s.add_argument('--stop-file', required=True)
    s.add_argument('--interval', type=float, default=2.0)
    j = sub.add_parser('judge')
    j.add_argument('--window-from', required=True)
    j.add_argument('--fault-from', required=True)
    j.add_argument('--fault-to', required=True)
    j.add_argument('--samples', default=None)
    j.add_argument('--resource', default='matching-engine', choices=LEASE_RESOURCES)
    j.add_argument('--expect-demotion', action='store_true')
    j.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    return cmd_sample(a) if a.cmd == 'sample' else cmd_judge(a)


if __name__ == '__main__':
    sys.exit(main())
