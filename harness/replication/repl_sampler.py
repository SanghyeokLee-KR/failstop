#!/usr/bin/env python3
"""repl_sampler.py. 복제 장애 실험의 시계열 표본기.

부하 도구는 주행이 끝난 뒤 요약만 준다. 그 요약으로는 "standby 를 죽인 순간부터 무슨 일이
일어났는가" 를 알 수 없다. 이 프로그램이 초 단위로 앱과 DB 를 함께 찍는다.

무엇을 찍는가.

  앱  주문 상태코드별 초당 건수, 요청 지연 평균과 최대, 수용 정원과 거절 누계,
      커넥션 풀 활성과 대기, 저널 적재 지연, readiness 상태코드
      --app-url 을 여러 번 주면 노드별로 찍고 클러스터 합계를 함께 낸다. 리더 페일오버에서는
      카운터가 노드 사이를 옮겨가므로 한 노드만 보면 승격 순간에 처리량이 0 으로 보인다.
  리스 갱신 성공·거부·왕복실패 누계, 마지막 갱신 이후 경과, fencing 토큰, 리더 여부, epoch
  DB  pg_stat_replication 의 상태와 sync_state 와 세 가지 지연
  자원 컨테이너 CPU 와 메모리(비싸므로 몇 틱마다 한 번)

왜 앱 지표를 원천으로 쓰는가. 부하 도구가 보는 값은 도구가 포기한 몫이 섞인다. 앱의
카운터는 앱이 실제로 처리한 것과 거절한 것을 그대로 센다. 누계이므로 두 표본의 차로
초당 값을 낸다.

지연은 평균과 최대만 낸다. 백분위는 내지 않는다. Micrometer 의 히스토그램을 구간마다
다시 접으면 값이 미묘하게 어긋나고, 그 어긋남을 이 실험이 검증하지 못한다. 백분위가
필요한 구간은 부하 도구의 주행 요약을 쓴다.

사용:
  python harness/replication/repl_sampler.py --out run.jsonl --duration 300
  python harness/replication/repl_sampler.py --out run.jsonl --duration 300 --standbys ex-pg2,ex-pg3

산출: --out 경로에 JSON Lines. 한 줄이 한 표본이다. 시각은 UTC 이고 kill 시각과 같은 시계다.
"""

import argparse
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

ORDER_URI = '/api/v1/orders'


def sh(args, timeout=10):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return p.stdout.strip()
    except Exception:
        return ''


def probe(url, timeout=1.5):
    """상태코드만 본다. readiness 는 본문이 아니라 코드가 계약이다."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except (urllib.error.URLError, OSError):
        return 0


def fetch(url, timeout=1.5):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.read().decode('utf-8', 'replace')
    except (urllib.error.URLError, OSError):
        return None


def parse_metrics(text):
    """필요한 값만 뽑는다. 클라이언트 라이브러리를 붙이지 않는 이유는 값 몇 개뿐이기 때문이다."""
    out = {
        'order': {},          # 상태코드 -> 누계
        'http_sum': 0.0, 'http_count': 0.0, 'http_max': 0.0,
        'hikari_active': None, 'hikari_pending': None, 'hikari_idle': None,
        'adm_inflight': None, 'adm_limit': None, 'adm_rejected': None,
        'journal_count': 0.0, 'journal_sum': 0.0,
        'cpu': None,
        # 리스 보관소 축(2026-09-08). 리스가 PostgreSQL 밖으로 나가면 leader_lease 행 조회로는
        # 갱신이 살아 있는지 알 수 없다. 앱 계측이 유일한 원천이다.
        'lease_renew_ok': None, 'lease_renew_rejected': None, 'lease_renew_error': None,
        'lease_renew_age': None, 'lease_token': None,
        'is_leader': None, 'leader_epoch': None,
        # 팔로워 따라잡기 축(2026-09-08). 재합류가 성립했는지는 이 넷으로만 밖에서 보인다.
        # verified 는 대조가 실제로 돌았는지다. 적용만 보면 결함 B 같은 무음 실패를 놓친다.
        'catchup_lag_max': None, 'catchup_lag_sum': None, 'catchup_state': None, 'catchup_applied': None,
        'catchup_verified': None,
        'catchup_tick': None, 'heap_used': None, 'heap_max': None,
    }
    if not text:
        return None
    for line in text.split('\n'):
        if line.startswith('#') or not line:
            continue
        # 주문 경로의 상태코드별 누계. POST 만 센다. 취소는 DELETE 라 섞이지 않는다.
        if line.startswith('http_server_requests_seconds_count{'):
            if 'method="POST"' in line and ('uri="%s"' % ORDER_URI) in line:
                m = re.search(r'status="(\d+)"', line)
                v = tail(line)
                if m and v is not None:
                    out['order'][m.group(1)] = out['order'].get(m.group(1), 0.0) + v
            if ('uri="%s"' % ORDER_URI) in line and 'method="POST"' in line:
                v = tail(line)
                if v is not None:
                    out['http_count'] += v
        elif line.startswith('http_server_requests_seconds_sum{'):
            if 'method="POST"' in line and ('uri="%s"' % ORDER_URI) in line:
                v = tail(line)
                if v is not None:
                    out['http_sum'] += v
        elif line.startswith('http_server_requests_seconds_max{'):
            if 'method="POST"' in line and ('uri="%s"' % ORDER_URI) in line:
                v = tail(line)
                if v is not None:
                    out['http_max'] = max(out['http_max'], v)
        elif line.startswith('hikaricp_connections_active{pool="HikariPool-1"}'):
            out['hikari_active'] = tail(line)
        elif line.startswith('hikaricp_connections_pending{pool="HikariPool-1"}'):
            out['hikari_pending'] = tail(line)
        elif line.startswith('hikaricp_connections_idle{pool="HikariPool-1"}'):
            out['hikari_idle'] = tail(line)
        elif line.startswith('exchange_admission_in_flight{scope="order"}'):
            out['adm_inflight'] = tail(line)
        elif line.startswith('exchange_admission_limit{scope="order"}'):
            out['adm_limit'] = tail(line)
        elif line.startswith('exchange_admission_rejected_total{scope="order"}'):
            out['adm_rejected'] = tail(line)
        elif line.startswith('exchange_journal_append_seconds_count'):
            out['journal_count'] = tail(line) or 0.0
        elif line.startswith('exchange_journal_append_seconds_sum'):
            out['journal_sum'] = tail(line) or 0.0
        elif line.startswith('exchange_leader_lease_renew_total{'):
            if 'outcome="ok"' in line:
                out['lease_renew_ok'] = tail(line)
            elif 'outcome="rejected"' in line:
                out['lease_renew_rejected'] = tail(line)
            elif 'outcome="error"' in line:
                out['lease_renew_error'] = tail(line)
        elif line.startswith('exchange_leader_lease_last_renew_age_seconds'):
            out['lease_renew_age'] = tail(line)
        elif line.startswith('exchange_leader_lease_fencing_token'):
            out['lease_token'] = tail(line)
        elif line.startswith('exchange_leader_is_leader{'):
            out['is_leader'] = tail(line)
        elif line.startswith('exchange_leader_epoch{'):
            out['leader_epoch'] = tail(line)
        # 이름을 통째로 맞춘다. 두 격차 지표가 같은 접두를 공유해 startswith 로는 서로를 삼킨다.
        elif line.split('{')[0].split(' ')[0] == 'exchange_follower_catchup_lag_commands_max':
            out['catchup_lag_max'] = tail(line)
        elif line.split('{')[0].split(' ')[0] == 'exchange_follower_catchup_lag_commands_sum_of_markets':
            out['catchup_lag_sum'] = tail(line)
        elif line.startswith('exchange_follower_catchup_state'):
            out['catchup_state'] = tail(line)
        elif line.startswith('exchange_follower_catchup_applied_total'):
            out['catchup_applied'] = tail(line)
        # 적용 건수만 보면 대조가 도는지 알 수 없다. 결함 B 는 적용이 늘어도 대조가 0 이던 상태였다.
        elif line.startswith('exchange_follower_catchup_verified_events_total'):
            out['catchup_verified'] = tail(line)
        elif line.startswith('exchange_follower_catchup_last_tick_seconds'):
            out['catchup_tick'] = tail(line)
        elif line.startswith('jvm_memory_used_bytes{') and 'area="heap"' in line:
            out['heap_used'] = (out['heap_used'] or 0) + (tail(line) or 0)
        elif line.startswith('jvm_memory_max_bytes{') and 'area="heap"' in line:
            v = tail(line)
            if v and v > 0:
                out['heap_max'] = (out['heap_max'] or 0) + v
        elif line.startswith('process_cpu_usage'):
            out['cpu'] = tail(line)
    return out


def poll_nodes(urls, timeout):
    """노드들을 병렬로 찍는다. 죽은 노드가 산 노드의 표본을 늦추지 않게 한다.

    직렬로 돌면 죽은 노드 하나가 틱을 통째로 늘린다. 도커 포트 매핑은 컨테이너가 죽어도
    호스트 쪽 리스너가 남아 접속이 즉시 거절되지 않고 타임아웃까지 매달리기 때문이다.
    2026-09-08 리더 페일오버 회차에서 1 초 틱이 승격 구간에서만 9 초로 벌어졌고, 하필 그
    구간이 이 실험의 측정 대상이었다. 병렬로 돌리고 타임아웃을 짧게 잡아 닫는다.
    """
    with ThreadPoolExecutor(max_workers=max(2, len(urls) * 2)) as pool:
        metric_futures = [pool.submit(fetch, u + '/actuator/prometheus', timeout) for u in urls]
        ready_futures = [pool.submit(probe, u + '/actuator/health/readiness', timeout)
                         for u in urls]
        out = []
        for url, mf, rf in zip(urls, metric_futures, ready_futures):
            try:
                text = mf.result()
            except Exception:
                text = None
            try:
                code = rf.result()
            except Exception:
                code = 0
            out.append((url, parse_metrics(text), code))
    return out


def rates_by_node(per_node, prev_by_node, t0, prev_t):
    """노드별로 누계 증분을 내고 더한다. 사라진 노드는 0 이고 갓 붙은 노드는 건너뛴다."""
    orders = {}
    dc = ds = dj = djs = 0.0
    adm_rej = None
    lease = {'ok': None, 'rejected': None, 'error': None}
    for url, cur, _ in per_node:
        prev = prev_by_node.get(url)
        if not cur or not prev:
            continue
        dt = t0 - prev_t.get(url, 0)
        if dt <= 0.2:
            continue
        for code in set(list(cur['order'].keys()) + list(prev['order'].keys())):
            d = cur['order'].get(code, 0.0) - prev['order'].get(code, 0.0)
            if d > 0:
                orders[code] = round(orders.get(code, 0.0) + d / dt, 2)
        dc += max(0.0, cur['http_count'] - prev['http_count'])
        ds += max(0.0, cur['http_sum'] - prev['http_sum'])
        dj += max(0.0, cur['journal_count'] - prev['journal_count'])
        djs += max(0.0, cur['journal_sum'] - prev['journal_sum'])
        if cur['adm_rejected'] is not None and prev['adm_rejected'] is not None:
            adm_rej = (adm_rej or 0.0) + max(0.0, cur['adm_rejected'] - prev['adm_rejected']) / dt
        for key, field in (('ok', 'lease_renew_ok'), ('rejected', 'lease_renew_rejected'),
                           ('error', 'lease_renew_error')):
            if cur[field] is not None and prev[field] is not None:
                lease[key] = (lease[key] or 0.0) + max(0.0, cur[field] - prev[field]) / dt
    return orders, dc, ds, dj, djs, adm_rej, lease


def merge_metrics(metrics):
    """노드별 표본에서 게이지 성격 값만 클러스터 하나로 접는다.

    누계는 여기서 더하지 않는다. 더했다가 노드가 사라지면 합계가 줄고 그 감소가 음수 비율이 된다.
    2026-09-08 리더 페일오버 1 회차에서 kill 직후 거절이 초당 -1,864 로 찍혔다. 누계는
    rates_by_node 가 노드별로 증분을 낸 뒤 그 증분을 더한다. 사라진 노드는 증분에 기여하지 않고
    갓 붙은 노드는 첫 틱을 건너뛴다.

    리스 축은 리더인 노드의 값을 쓴다. 팔로워의 토큰과 리스 나이는 클러스터 상태가 아니다.
    """
    live = [m for m in metrics if m]
    if not live:
        return None
    if len(live) == 1:
        return live[0]
    out = dict(live[0])
    out['http_max'] = max(m['http_max'] for m in live)
    for key in ('hikari_active', 'hikari_pending', 'hikari_idle',
                'adm_inflight', 'adm_limit'):
        vals = [m[key] for m in live if m[key] is not None]
        out[key] = sum(vals) if vals else None
    leader = next((m for m in live if m.get('is_leader') == 1.0), live[0])
    for key in ('lease_renew_age', 'lease_token', 'is_leader', 'leader_epoch', 'cpu'):
        out[key] = leader.get(key)
    return out


def tail(line):
    i = line.rfind(' ')
    if i < 0:
        return None
    try:
        v = float(line[i + 1:])
        return v if v == v and abs(v) != float('inf') else None
    except ValueError:
        return None


def replication(pg, user, db):
    """primary 가 보는 복제 세션. 없으면 빈 목록이고 그것이 곧 sync 대상 부재다."""
    sql = ("SELECT coalesce(application_name,'-')||'|'||coalesce(client_addr::text,'-')||'|'"
           "||state||'|'||sync_state||'|'"
           "||coalesce(extract(epoch from write_lag)::text,'')||'|'"
           "||coalesce(extract(epoch from flush_lag)::text,'')||'|'"
           "||coalesce(extract(epoch from replay_lag)::text,'') FROM pg_stat_replication")
    out = sh(['docker', 'exec', pg, 'psql', '-U', user, '-d', db, '-Atc', sql])
    rows = []
    for line in out.splitlines():
        p = line.split('|')
        if len(p) != 7:
            continue
        rows.append({
            'app': p[0], 'client': p[1], 'state': p[2], 'sync': p[3],
            'write_lag': fnum(p[4]), 'flush_lag': fnum(p[5]), 'replay_lag': fnum(p[6]),
        })
    return rows


def fnum(s):
    try:
        return round(float(s), 6)
    except ValueError:
        return None


def cpu_mem(names):
    if not names:
        return {}
    out = sh(['docker', 'stats', '--no-stream', '--format',
              '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'] + names, timeout=20)
    res = {}
    for line in out.splitlines():
        parts = line.split(None, 2)
        if len(parts) < 2 or not parts[1].endswith('%'):
            continue
        try:
            res[parts[0]] = {'cpu_pct': float(parts[1][:-1]),
                             'mem': parts[2] if len(parts) > 2 else ''}
        except ValueError:
            continue
    return res


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--app-url', action='append', default=None,
                   help='여러 번 줄 수 있다. 노드별로 찍고 합계를 낸다')
    p.add_argument('--pg-container', default='ex-pg')
    p.add_argument('--user', default='exchange')
    p.add_argument('--database', default='exchange')
    p.add_argument('--standbys', default='', help='자원 표본 대상. 쉼표로 나눈 컨테이너 이름')
    p.add_argument('--out', required=True)
    p.add_argument('--duration', type=float, default=300)
    p.add_argument('--interval', type=float, default=1.0)
    p.add_argument('--node-timeout', type=float, default=0.8,
                   help='노드 한 대당 왕복 상한. 죽은 노드를 짧게 포기한다')
    p.add_argument('--pg-every', type=int, default=1,
                   help='복제 상태를 몇 틱마다 찍나. docker exec 왕복이 0.4초라 리더 페일오버처럼 '
                        '초 단위 해상도가 필요한 회차에서는 늘린다')
    p.add_argument('--stat-every', type=int, default=5, help='docker stats 를 몇 틱마다 찍나')
    a = p.parse_args()
    if not a.app_url:
        a.app_url = ['http://localhost:8080']

    watched = [a.pg_container] + [s for s in a.standbys.split(',') if s]
    f = open(a.out, 'a', encoding='utf-8')
    prev_by_node = {}
    prev_t_by_node = {}
    last_rep = []
    tick = 0
    end = time.time() + a.duration
    print('표본 시작. 대상 %s. %.0f초' % (', '.join(watched), a.duration), flush=True)
    while time.time() < end:
        t0 = time.time()
        now = datetime.now(timezone.utc)
        per_node = poll_nodes(a.app_url, a.node_timeout)
        cur = merge_metrics([m for _, m, _ in per_node])
        # 클러스터가 쓰기를 받을 수 있으면 200 이다. 페일오버 창은 어느 노드도 200 이 아닌 구간이고
        # 그 길이가 곧 이 실험의 다운타임이다.
        readiness = 200 if any(r == 200 for _, _, r in per_node) else             max((r for _, _, r in per_node), default=0)
        rep = replication(a.pg_container, a.user, a.database)             if tick % a.pg_every == 0 else last_rep
        last_rep = rep
        stats = cpu_mem(watched) if tick % a.stat_every == 0 else None

        rec = {
            'ts': now.strftime('%H:%M:%S'),
            'epoch': round(t0, 3),
            'app_up': cur is not None,
            'repl_sessions': len(rep),
            'repl': rep,
            # readiness 를 표본마다 찍는다. 이 실험의 성공 판정이 '앱 재기동 없이 200 으로
            # 돌아오는가' 이므로 사후에 로그로 재구성할 수 없다.
            'readiness': readiness,
            'nodes': [{'url': u, 'readiness': r,
                       'is_leader': (m or {}).get('is_leader'),
                       'epoch': (m or {}).get('leader_epoch'),
                       'token': (m or {}).get('lease_token'),
                       'lease_age': (m or {}).get('lease_renew_age'),
                       'catchup_lag_max': (m or {}).get('catchup_lag_max'),
                       'catchup_lag_sum': (m or {}).get('catchup_lag_sum'),
                       'catchup_state': (m or {}).get('catchup_state'),
                       'catchup_applied': (m or {}).get('catchup_applied'),
                       'catchup_verified': (m or {}).get('catchup_verified'),
                       'catchup_tick': (m or {}).get('catchup_tick'),
                       'cpu': (m or {}).get('cpu'),
                       'heap_used': (m or {}).get('heap_used'),
                       'heap_max': (m or {}).get('heap_max')}
                      for u, m, r in per_node],
        }
        if cur:
            rec['adm'] = {'in_flight': cur['adm_inflight'], 'limit': cur['adm_limit']}
            rec['hikari'] = {'active': cur['hikari_active'], 'pending': cur['hikari_pending'],
                             'idle': cur['hikari_idle']}
            rec['cpu_app'] = cur['cpu']
            rec['lease'] = {'age': cur['lease_renew_age'], 'token': cur['lease_token'],
                            'is_leader': cur['is_leader'], 'epoch': cur['leader_epoch']}
            orders, dc, ds, dj, djs, adm_rej, lease = rates_by_node(
                per_node, prev_by_node, t0, prev_t_by_node)
            if orders or dc or adm_rej is not None:
                rec['dt'] = round(a.interval, 3)
                rec['orders_per_s'] = orders
                rec['order_mean_ms'] = round(ds / dc * 1000, 1) if dc > 0 else None
                rec['order_max_ms'] = round(cur['http_max'] * 1000, 1)
                rec['journal_mean_ms'] = round(djs / dj * 1000, 1) if dj > 0 else None
                if adm_rej is not None:
                    rec['adm_reject_per_s'] = round(adm_rej, 2)
                for key, out_key in (('ok', 'lease_ok_per_s'),
                                     ('rejected', 'lease_rejected_per_s'),
                                     ('error', 'lease_error_per_s')):
                    if lease[key] is not None:
                        rec[out_key] = round(lease[key], 2)
        for url, m, _ in per_node:
            if m:
                prev_by_node[url] = m
                prev_t_by_node[url] = t0
            else:
                # 노드가 사라졌다. 다음에 돌아오면 첫 틱을 건너뛰어 누계 전량이 스파이크로
                # 찍히는 것을 막는다.
                prev_by_node.pop(url, None)
                prev_t_by_node.pop(url, None)
        if stats:
            rec['stats'] = stats
        f.write(json.dumps(rec, ensure_ascii=False) + '\n')
        f.flush()
        tick += 1
        sleep = a.interval - (time.time() - t0)
        if sleep > 0:
            time.sleep(sleep)
    f.close()
    print('표본 종료. %d틱' % tick, flush=True)


if __name__ == '__main__':
    main()
