"""S4 관문 실험의 파티션 관측 축(마켓분할_리더_S4_설계 4-18, 7-2). harness/scripts/adr0022-stage5-chaos.ps1 -PartitionAxes 가 부른다.

하위 명령
  snapshot  노드마다 /actuator/partitions 와 파티션·리스·접수 경합 계수를 긁고, leader_lease 행과 LB 로그의 no live upstreams·502
            줄 수를 함께 남긴다. 실험 전(pre)과 후(post)에 한 번씩 뜬다.
  judge     실험이 끝난 뒤, 스택을 내리기 전에 부른다. 판정 축은 넷이다(7-2).
            1. 접수 경합 상한. 장애 창 밖(스택을 세운 뒤부터 주입 전까지, 스크립트가 끝난 시각 + settle 초부터 판정 시작까지)의
               exchange_order_intake_contention_total 증가 합이 같은 구간 exchange_journal_append_seconds_count 증가의 0.1% 이하다.
               장애 창이 없으면(X1) 주행 전체가 창 밖이다. 창 안의 값은 cause·response 별로 기록만 한다.
            2. 창 밖 PartitionBreakerOpen·PartitionRebuildFailing 발화 0.
            3. 실험 끝 리더 노드의 파티션 강등 계수 0. 노드·사유별 계수를 함께 남긴다.
            4. LB 로그의 no live upstreams·502 줄 수를 창 안팎으로 나눠 남긴다. --lb-gate 이면(X1·X6) 모두 0 이어야 한다.
판정은 축마다 참·거짓으로 적고, 하나라도 거짓이면 ok=false 다. 도커·DB 에 닿지 않는 집계 함수는 test_s4_partition_axes.py 가 본다.

사용:
  python harness/scale/s4_partition_axes.py snapshot --out <json>
  python harness/scale/s4_partition_axes.py judge --window-from <ISO UTC> [--fault-from <ISO UTC> --fault-to <ISO UTC>]
         --pre <json> --post <json> [--settle-sec 30] [--lb-gate] --out <json>
종료코드: 0 판정 통과, 1 판정 실패.
"""
import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402

PROM = 'http://localhost:59090'
NODES = (('chaos-api-1', 18080), ('chaos-api-2', 28080))
LB = 'chaos-lb'
# 긁어 남길 계수. 접두어가 맞는 계열을 태그째 남긴다.
METRIC_PREFIXES = ('exchange_partition_', 'exchange_leader_lease_acquire_lock_timeouts_total',
                   'exchange_order_intake_contention_total', 'exchange_node_serviceable',
                   'exchange_node_write_completion_ratio', 'exchange_leader_standdown_total',
                   'exchange_leader_recoverable_standdown_total', 'exchange_journal_append_seconds_count',
                   'exchange_leader_lease_acquire_total', 'exchange_leader_lease_renew_total')
CONTENTION_CAP = 0.001
PROM_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)')
LEASE_SQL = ("SELECT coalesce(json_agg(json_build_object('resource', resource, 'holder', holder, 'epoch', epoch, "
             "'alive', lease_until > clock_timestamp()) ORDER BY resource), '[]'::json) FROM leader_lease")
# LB 는 nginx 기본 combined 형식이다("$request" $status $body_bytes_sent ...). 상태 필드만 세려고 요청 줄의 닫는 따옴표를
# 붙인다. ' 502 ' 만 보면 응답 바이트 수가 502 인 정상 줄도 센다.
LB_PATTERNS = {'no_live_upstreams': 'no live upstreams', 'status_502': '" 502 '}


def parse_iso(s):
    return dt.datetime.fromisoformat(s.replace('Z', '+00:00')).astimezone(dt.timezone.utc)


def iso(t):
    return dt.datetime.fromtimestamp(t, dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%fZ')


def parse_metrics(text):
    """프로메테우스 텍스트에서 METRIC_PREFIXES 계열만 {이름{태그}: 값} 으로 모은다."""
    out = {}
    for line in text.splitlines():
        if line.startswith('#'):
            continue
        m = PROM_LINE.match(line)
        if not m or not m.group(1).startswith(METRIC_PREFIXES):
            continue
        try:
            out[m.group(1) + (m.group(2) or '')] = float(m.group(3))
        except ValueError:
            continue
    return out


def http_json(url, timeout=10):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode('utf-8', 'replace'))
    except Exception as e:
        return {'error': type(e).__name__}


def http_text(url, timeout=10):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.read().decode('utf-8', 'replace'), None
    except Exception as e:
        return None, type(e).__name__


def count_lb_lines(text):
    return {k: sum(1 for x in text.splitlines() if pat in x) for k, pat in LB_PATTERNS.items()}


def lb_counts(since=None, until=None):
    args = ['docker', 'logs']
    if since:
        args += ['--since', since]
    if until:
        args += ['--until', until]
    args.append(LB)
    try:
        p = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
    except (subprocess.SubprocessError, OSError) as e:
        return {'ok': False, 'error': type(e).__name__}
    if p.returncode != 0:
        return {'ok': False, 'error': 'docker logs 종료코드 %s' % p.returncode}
    res = {'ok': True}
    res.update(count_lb_lines(p.stdout + p.stderr))
    return res


def node_snapshot(port):
    text, err = http_text('http://localhost:%d/actuator/prometheus' % port)
    return {'partitions': http_json('http://localhost:%d/actuator/partitions' % port),
            'metrics': parse_metrics(text) if text is not None else {'error': err}}


def cmd_snapshot(a):
    lease, err = pgs.parse_json_row(*pgs.run_psql(LEASE_SQL, timeout=60))
    res = {'at': pgs.now_iso(), 'nodes': {name: node_snapshot(port) for name, port in NODES},
           'leader_lease': lease if lease is not None else {'error': err}, 'lb': lb_counts()}
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'at': res['at'], 'leader_lease': res['leader_lease']}, ensure_ascii=False), flush=True)
    return 0


def prom(path, params):
    url = PROM + path + '?' + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            body = json.load(r)
        return body.get('data', {}).get('result')
    except Exception as e:
        return {'error': type(e).__name__}


def increase(expr, start, end):
    """[start, end] 의 증가 합. 구간이 없으면 0, 질의 실패는 None 이다(판정은 None 을 0 으로 읽지 않는다)."""
    if end <= start:
        return 0.0
    res = prom('/api/v1/query', {'query': 'sum(increase(%s[%ds]))' % (expr, max(1, round(end - start))),
                                 'time': '%.3f' % end})
    if isinstance(res, dict):
        return None
    if not res:
        return 0.0
    return float(res[0]['value'][1])


def increase_by(expr, labels, start, end):
    if end <= start:
        return []
    res = prom('/api/v1/query', {'query': 'sum by (%s) (increase(%s[%ds]))' % (','.join(labels), expr,
                                                                                max(1, round(end - start))),
                                 'time': '%.3f' % end})
    if isinstance(res, dict):
        return res
    return [{'labels': {k: x['metric'].get(k) for k in labels}, 'value': float(x['value'][1])} for x in res]


def windows(window_from, fault_from, fault_to, now, settle):
    """창 밖 구간 목록과 창 안 구간. 장애 창이 없으면 주행 전체가 창 밖이다."""
    if fault_from is None or fault_to is None:
        return [(window_from, now)], None
    inside_end = min(now, fault_to + settle)
    outside = [(window_from, fault_from)]
    if inside_end < now:
        outside.append((inside_end, now))
    return outside, (fault_from, inside_end)


def contention_cap(contention, appends, cap=CONTENTION_CAP):
    """창 밖 경합 증가가 같은 구간 append 증가의 cap 이하인가. 어느 하나라도 읽지 못했으면 거짓이다."""
    if contention is None or appends is None:
        return False
    return contention <= cap * appends


def leader_node(post):
    """실험 끝에 접수 중인 파티션이 있는 노드. 없거나 둘 이상이면 None 이다(P2 는 한 노드만 ACCEPTING)."""
    found = []
    for name, node in (post.get('nodes') or {}).items():
        parts = (node.get('partitions') or {}).get('partitions') or []
        if any(p.get('state') == 'ACCEPTING' for p in parts):
            found.append(name)
    return found[0] if len(found) == 1 else None


def demotions(node):
    """노드의 파티션 강등 계수(partition·reason 별)와 합. 긁지 못했으면 None 이다."""
    metrics = node.get('metrics') or {}
    if 'error' in metrics:
        return None
    by = {}
    for key, value in metrics.items():
        if not key.startswith('exchange_partition_demotions_total'):
            continue
        partition = re.search(r'partition="([^"]+)"', key)
        reason = re.search(r'reason="([^"]+)"', key)
        by['%s/%s' % (partition.group(1) if partition else '?', reason.group(1) if reason else '?')] = value
    return {'by': by, 'total': sum(by.values())}


def alerts_outside(samples, fault_from, fault_to):
    """발화 표본 가운데 창 밖에 있는 수. fault 창이 없으면 모든 발화가 창 밖이다."""
    n = 0
    for s in samples:
        if s['state'] != 'firing':
            continue
        if fault_from is not None and fault_to is not None and fault_from <= s['t'] <= fault_to:
            continue
        n += 1
    return n


def cmd_judge(a):
    now = dt.datetime.now(dt.timezone.utc).timestamp()
    window_from = parse_iso(a.window_from).timestamp()
    fault_from = parse_iso(a.fault_from).timestamp() if a.fault_from else None
    fault_to = parse_iso(a.fault_to).timestamp() if a.fault_to else None
    outside, inside = windows(window_from, fault_from, fault_to, now, a.settle_sec)
    pre = json.loads(Path(a.pre).read_text(encoding='utf-8')) if a.pre and Path(a.pre).exists() else {}
    post = json.loads(Path(a.post).read_text(encoding='utf-8')) if a.post and Path(a.post).exists() else {}
    res = {'started': pgs.now_iso(), 'windows': {'outside': [[iso(s), iso(e)] for s, e in outside],
                                                'inside': [iso(inside[0]), iso(inside[1])] if inside else None}}

    contention_out = [increase('exchange_order_intake_contention_total', s, e) for s, e in outside]
    appends_out = [increase('exchange_journal_append_seconds_count', s, e) for s, e in outside]
    c_sum = None if any(v is None for v in contention_out) else sum(contention_out)
    a_sum = None if any(v is None for v in appends_out) else sum(appends_out)
    res['contention'] = {'outside': c_sum, 'appends_outside': a_sum, 'cap': CONTENTION_CAP,
                         'inside_by_cause_response': increase_by('exchange_order_intake_contention_total',
                                                                  ('cause', 'response'), *inside) if inside else None,
                         'acquire_lock_timeouts': increase('exchange_leader_lease_acquire_lock_timeouts_total',
                                                           window_from, now)}

    alerts = prom('/api/v1/query_range', {
        'query': 'ALERTS{alertname=~"PartitionBreakerOpen|PartitionRebuildFailing"}',
        'start': '%.3f' % window_from, 'end': '%.3f' % now, 'step': '15'})
    samples = []
    if isinstance(alerts, list):
        for series in alerts:
            for ts, _ in series.get('values', []):
                samples.append({'state': series['metric'].get('alertstate'), 'alert': series['metric'].get('alertname'),
                                't': float(ts)})
    res['partition_alerts'] = {'samples': len(samples), 'firing_outside': alerts_outside(samples, fault_from,
                                                                                         (fault_to + a.settle_sec)
                                                                                         if fault_to else None),
                               'error': alerts.get('error') if isinstance(alerts, dict) else None}

    lead = leader_node(post)
    res['leader_node'] = lead
    res['demotions'] = {name: demotions(node) for name, node in (post.get('nodes') or {}).items()}
    res['partitions'] = {'pre': {n: (x.get('partitions') or {}).get('partitions') for n, x in (pre.get('nodes') or {}).items()},
                         'post': {n: (x.get('partitions') or {}).get('partitions') for n, x in (post.get('nodes') or {}).items()}}
    res['leader_lease'] = {'pre': pre.get('leader_lease'), 'post': post.get('leader_lease')}

    lb = {'outside': [lb_counts(iso(s), iso(e)) for s, e in outside],
          'inside': lb_counts(iso(inside[0]), iso(inside[1])) if inside else None}
    res['lb'] = lb
    lb_rows = lb['outside'] + ([lb['inside']] if lb['inside'] else [])
    lb_zero = all(x.get('ok') and x['no_live_upstreams'] == 0 and x['status_502'] == 0 for x in lb_rows)

    lead_demotions = res['demotions'].get(lead) if lead else None
    axes = {
        'contention_cap_outside_window': contention_cap(c_sum, a_sum),
        'partition_alerts_outside_window_0': res['partition_alerts']['error'] is None
                                             and res['partition_alerts']['firing_outside'] == 0,
        'leader_node_demotions_0': lead_demotions is not None and lead_demotions['total'] == 0,
    }
    if a.lb_gate:
        axes['lb_no_live_upstreams_and_502_0'] = lb_zero
    res['axes'] = axes
    res['ok'] = all(axes.values())
    res['finished'] = pgs.now_iso()
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    print(json.dumps({'ok': res['ok'], 'failed': [k for k, v in axes.items() if not v], 'leader_node': lead,
                      'contention_outside': c_sum, 'appends_outside': a_sum}, ensure_ascii=False), flush=True)
    return 0 if res['ok'] else 1


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('snapshot')
    s.add_argument('--out', required=True)
    j = sub.add_parser('judge')
    j.add_argument('--window-from', required=True)
    j.add_argument('--fault-from', default=None)
    j.add_argument('--fault-to', default=None)
    j.add_argument('--pre', default=None)
    j.add_argument('--post', default=None)
    j.add_argument('--settle-sec', type=float, default=30.0)
    j.add_argument('--lb-gate', action='store_true')
    j.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    return cmd_snapshot(a) if a.cmd == 'snapshot' else cmd_judge(a)


if __name__ == '__main__':
    sys.exit(main())
