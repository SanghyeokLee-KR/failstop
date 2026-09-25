"""S6 관문 실험의 WebSocket 중계 축(마켓분할_리더_S6_설계 7-1·7-2, 하네스 검증설계 S6 패치 노트 (3)·(4)·(7)).

실험 W0~W5 는 아직 돌리지 않았다(NOT RUN). 이 파일은 실행기(adr0022-stage5-chaos.ps1 -Relay·-WsGateAxes, s6-relay-outage.ps1)가
부를 채집기와 판정 규칙의 초안이다. 도커·DB·HTTP 에 닿지 않는 판정 함수는 test_s6_relay_axes.py 가 본다.

하위 명령
  gate       WS 수용 판정의 스택 표본(W0·W5). 두 노드 호스트 포트로 GET /api/v1/orderbook·/ticker 를 N 회 뜨고, 두 노드와 LB 에
             WS 핸드셰이크를 시도하며, 두 노드의 exchange_ws_gate_open·exchange_ws_relay_handshake_refused_total, 리스 행, LB 로그를
             ws_gate.json 에 남긴다. --mode simple(W0): 팔로워의 호가·티커가 모두 seq 0 이고 epoch 가 리스 epoch, 팔로워 직접
             핸드셰이크가 503 과 Retry-After: 5, 리더와 LB 는 101, LB 로그에 /ws/ 의 503 으로 한 노드를 빼는 줄 뒤 101 줄이 있고,
             팔로워 gate.open 0·거절(relay_required) 1 이상, 리더 gate.open 1. --mode broker(W5): 두 노드 모두 gate.open 1 이고
             핸드셰이크 101, LB 101.
  freshness  미러 신선도 표본기. 1초마다 리더 호스트 포트의 호가를 뜨고 --lag-ms 뒤 팔로워 호스트 포트에서 같은 요청을 떠 쌍을
             JSONL 로 남긴다. 판정은 '팔로워 seq 가 0 이거나 팔로워 (epoch, seq) 가 앞선 리더 표본 이상'이다(설계 7-2).
             --stop-file 이 생기면 끝난다. --resource 를 주면(P3, S7 설계 4-13) 그 자원을 ACCEPTING 으로 쥔 노드를 소유 노드(리더
             자리)로, 다른 노드를 비교 노드로 짝짓는다(owner_of). 주지 않으면 모든 파티션이 ACCEPTING 인 노드다.
  watch      k6 관찰자(harness/load/ws_relay_watch.js) 로그에서 노드마다 'WATCH <json>' 줄을 모아 관찰자 JSON 으로 쓴다. 노드마다
             마지막 상태(latest)와 10초 간격 누적 계수 시계열(series)을 남긴다.
  judge      W1~W7 판정. 관찰자 JSON, 신선도 표본, 프로메테우스 계수(창 전후), LB 로그, 웹 관찰을 읽는다. 축마다 참·거짓이고
             하나라도 거짓이면 ok=false 다. 읽지 못한 값은 참으로 세지 않는다. W6(P3 한 파티션 소유자 kill, '그 마켓만 재동기화')과
             W7(P3 한 연결로 두 파티션 마켓 수신)은 S7 관문 판정이다(S7 설계 4-13). 두 판정은 웹 관찰 파일(s6-web-resync.mjs 출력)이
             없으면 web_observed 축이 거짓이고 verdict 가 'NOT RUN(웹 관찰)'이다. 웹 관찰 밖 축이 하나라도 거짓이면 'FAIL' 이다.

사용:
  python harness/scale/s6_relay_axes.py gate --mode simple|broker [--samples 10] [--expect-follower chaos-api-1] [--lb-since <ISO>] --out <json>
  python harness/scale/s6_relay_axes.py freshness --out <jsonl> --stop-file <path> [--interval 1] [--lag-ms 500] [--resource matching-p0]
  python harness/scale/s6_relay_axes.py watch --log <docker logs 파일> --out <json>
  python harness/scale/s6_relay_axes.py judge --experiment W1|W2|W3|W4|W5 --evidence <실험 폴더>
         --window-from <ISO> [--fault-from <ISO> --fault-to <ISO>] [--kill-at <ISO>] --out <json>
  python harness/scale/s6_relay_axes.py judge --experiment W6 --evidence <실험 폴더> --window-from <ISO> --kill-at <ISO> --rto-sec <초>
         --victim chaos-api-2 --survivor chaos-api-1 --affected-markets KRW-ETH --unaffected-markets KRW-BTC,KRW-XRP
         [--web <웹 관찰 JSON>] --out <json>
  python harness/scale/s6_relay_axes.py judge --experiment W7 --evidence <실험 폴더> --window-from <ISO> --window-to <ISO>
         --node chaos-api-1 --p0-markets KRW-BTC,KRW-XRP --p1-markets KRW-ETH [--web <웹 관찰 JSON>] --out <json>
종료코드: 0 판정 통과(수집 명령은 수집 성공), 1 판정 실패, 2 수집 실패, 3 NOT RUN(웹 관찰, W6·W7).
"""
import argparse
import base64
import datetime as dt
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402

PROM = 'http://localhost:59090'
NODES = (('chaos-api-1', 18080), ('chaos-api-2', 28080))
INSTANCES = {'chaos-api-1': 'chaos-api-1:8080', 'chaos-api-2': 'chaos-api-2:8080'}
LB = 'chaos-lb'
LB_PORT = 58080
MARKETS = ('KRW-BTC', 'KRW-ETH', 'KRW-XRP')
PROM_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)')
LEASE_SQL = ("SELECT coalesce(json_agg(json_build_object('resource', resource, 'holder', holder, 'epoch', epoch, "
             "'alive', lease_until > clock_timestamp()) ORDER BY resource), '[]'::json) FROM leader_lease")
WATCH_PREFIX = 'WATCH '

# 설계 7-2 의 판정 값. 2026-09-25 설계를 맡은 AI 가 정했고 사용자 확인 대상이다.
W1_MIN_BOOK_REALTIME = 100
W1_MIN_TRADES = 10
W1_SET_DIFF_MAX = 0.001
W1_LAG_P99_MAX_S = 0.25
W1_FRESHNESS_PAIRS = 300
W1_FOLLOWER_NONZERO_MIN = 0.9
ACCEPT_RATIO_MIN = 0.9
APPEND_P99_FACTOR_MAX = 1.5
RECONNECT_WITHIN_S = {'W2': 15.0, 'W3': 20.0, 'W4': 30.0}
DEGRADED_WITHIN_S = 20.0
RECEIVE_AGAIN_WITHIN_S = 60.0
HEAP_GROWTH_MAX_BYTES = 64 * 1024 * 1024
W4_FALLBACK_AFTER_S = 8.0
W4_LAG_MAX_S = 5.0
W5_FIRST_NEW_EPOCH_MARGIN_S = 5.0
# W6·W7(S7 설계 4-13, S6 설계 7-2). 영향 마켓의 새 epoch 첫 메시지는 RTO + 5초 안이다(W5 와 같은 여유). W7 의 수신 문턱은 W1 과 같다.
W6_FIRST_NEW_EPOCH_MARGIN_S = 5.0
WEB_FILE = 'web_resync.json'
NOT_RUN_WEB = 'NOT RUN(웹 관찰)'


def parse_iso(s):
    return dt.datetime.fromisoformat(s.replace('Z', '+00:00')).astimezone(dt.timezone.utc)


def iso(t):
    return dt.datetime.fromtimestamp(t, dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%fZ')


# ---------------------------------------------------------------- 순수 판정 함수(시험 대상)

def parse_metrics(text, prefixes=('exchange_ws_', 'exchange_partition_demotions_total')):
    """프로메테우스 텍스트에서 prefixes 계열만 {이름{태그}: 값} 으로 모은다."""
    out = {}
    for line in (text or '').splitlines():
        if line.startswith('#'):
            continue
        m = PROM_LINE.match(line)
        if not m or not m.group(1).startswith(prefixes):
            continue
        try:
            out[m.group(1) + (m.group(2) or '')] = float(m.group(3))
        except ValueError:
            continue
    return out


def metric_value(metrics, name, **labels):
    """이름과 태그가 맞는 계열 값의 합. 맞는 계열이 없으면 None 이다(0 으로 읽지 않는다)."""
    total, seen = 0.0, False
    for key, value in (metrics or {}).items():
        base, _, tags = key.partition('{')
        if base != name:
            continue
        if all(re.search(r'(^|,)%s="%s"' % (re.escape(k), re.escape(v)), tags) for k, v in labels.items()):
            total += value
            seen = True
    return total if seen else None


def parse_handshake(raw):
    """HTTP 응답 머리(바이트)에서 상태 코드와 Retry-After 를 읽는다."""
    text = raw.decode('iso-8859-1', 'replace')
    lines = text.split('\r\n')
    m = re.match(r'HTTP/1\.[01] (\d{3})', lines[0] if lines else '')
    headers = {}
    for line in lines[1:]:
        if not line:
            break
        k, sep, v = line.partition(':')
        if sep:
            headers[k.strip().lower()] = v.strip()
    return {'status': int(m.group(1)) if m else None, 'retry_after': headers.get('retry-after')}


def book_sample(status, body):
    """GET /api/v1/orderbook 응답 하나를 {status, epoch, seq} 로 줄인다."""
    if status != 200 or not isinstance(body, dict):
        return {'status': status, 'epoch': None, 'seq': None}
    return {'status': status, 'epoch': body.get('epoch'), 'seq': body.get('seq')}


def ticker_sample(status, body, market):
    """GET /api/v1/ticker 목록 응답에서 그 마켓 원소의 {status, epoch, seq}."""
    if status != 200 or not isinstance(body, dict):
        return {'status': status, 'epoch': None, 'seq': None}
    for row in body.get('data') or []:
        if row.get('market') == market:
            return {'status': status, 'epoch': row.get('epoch'), 'seq': row.get('seq')}
    return {'status': status, 'epoch': None, 'seq': None}


def all_seq_zero_at_epoch(samples, epoch):
    """표본이 있고 모두 200·seq 0·epoch 가 주어진 값이다. 리스 epoch 를 읽지 못했으면 거짓이다."""
    if not samples or epoch is None:
        return False
    return all(s['status'] == 200 and s['seq'] == 0 and s['epoch'] == epoch for s in samples)


def lb_ws_retry_seen(text):
    """LB 로그에 /ws/ 요청의 503 으로 한 업스트림을 뺀 경고 뒤에 /ws/ 101 접근 줄이 있는가.

    기본 스택 nginx.conf 의 접근 로그는 combined 형식이라 업스트림 상태를 싣지 않는다. 그래서 nginx 가 max_fails 에 걸린
    업스트림을 뺄 때 남기는 경고 줄('upstream server temporarily disabled', 요청 줄 포함)을 503 의 증거로 쓴다.
    중계 오버레이의 nginx.relay.conf 는 접근 줄 끝에 ups=<상태 목록>을 싣으므로 'ups=503, 101' 줄도 같은 증거로 센다.
    P3 합본(nginx.partition-relay.conf, S7 설계 4-9)은 파티션 형식이라 ups 가 주소, ust 가 상태 목록이다. 'ust="503, 101"' 줄도 센다.
    """
    lines = (text or '').splitlines()
    for i, line in enumerate(lines):
        if '"GET /ws/' in line and (re.search(r'ups=503, 101\b', line) or re.search(r'ust="503, 101"', line)):
            return True
        if 'upstream server temporarily disabled' in line and 'request: "GET /ws/' in line:
            for later in lines[i + 1:]:
                if '"GET /ws/' in later and '" 101 ' in later:
                    return True
    return False


def leader_from_partitions(partitions_by_node):
    """모든 파티션이 ACCEPTING 인 노드 하나. 없거나 둘 이상이면 None 이다."""
    found = []
    for node, parts in partitions_by_node.items():
        rows = (parts or {}).get('partitions') if isinstance(parts, dict) else None
        if rows and all(p.get('state') == 'ACCEPTING' for p in rows):
            found.append(node)
    return found[0] if len(found) == 1 else None


def owner_of(partitions_by_node, resource):
    """그 자원을 ACCEPTING 으로 쥔 노드 하나(P3, S7 설계 4-13). 없거나 둘 이상이면 None 이다."""
    found = []
    for node, parts in (partitions_by_node or {}).items():
        rows = (parts or {}).get('partitions') if isinstance(parts, dict) else None
        if rows and any(p.get('resource') == resource and p.get('state') == 'ACCEPTING' for p in rows):
            found.append(node)
    return found[0] if len(found) == 1 else None


def pair_nodes(partitions_by_node, resource=None):
    """신선도 표본의 (소유 노드, 비교 노드). resource 가 없으면 모든 파티션을 쥔 노드와 다른 노드다."""
    owner = owner_of(partitions_by_node, resource) if resource else leader_from_partitions(partitions_by_node)
    if owner is None:
        return None, None
    others = [n for n in partitions_by_node if n != owner]
    return owner, (others[0] if len(others) == 1 else None)


def lease_epoch(leases, resource='matching-engine'):
    if not isinstance(leases, list):
        return None
    for row in leases:
        if row.get('resource') == resource:
            return row.get('epoch')
    return None


def gate_axes(mode, nodes, lb_handshake, leader, lease_epoch_value, lb_text, expect_follower=None):
    """gate 판정(설계 7-2 W0·W5). nodes 는 {노드: {book, ticker, handshake, metrics}} 다."""
    axes = {}
    if mode == 'simple':
        follower = None
        if leader in nodes:
            others = [n for n in nodes if n != leader]
            follower = others[0] if len(others) == 1 else None
        axes['leader_found'] = follower is not None
        if expect_follower:
            axes['follower_is_expected'] = follower == expect_follower
        f = nodes.get(follower) or {}
        lead = nodes.get(leader) or {}
        axes['follower_orderbook_seq0_lease_epoch'] = all_seq_zero_at_epoch(f.get('book'), lease_epoch_value)
        axes['follower_ticker_seq0_lease_epoch'] = all_seq_zero_at_epoch(f.get('ticker'), lease_epoch_value)
        hs = f.get('handshake') or {}
        axes['follower_handshake_503_retry_after_5'] = hs.get('status') == 503 and hs.get('retry_after') == '5'
        axes['leader_handshake_101'] = (lead.get('handshake') or {}).get('status') == 101
        axes['lb_handshake_101'] = (lb_handshake or {}).get('status') == 101
        axes['lb_log_503_then_101'] = lb_ws_retry_seen(lb_text)
        axes['follower_gate_closed'] = metric_value(f.get('metrics'), 'exchange_ws_gate_open') == 0
        refused = metric_value(f.get('metrics'), 'exchange_ws_relay_handshake_refused_total', reason='relay_required')
        axes['follower_refused_relay_required_ge_1'] = refused is not None and refused >= 1
        axes['leader_gate_open'] = metric_value(lead.get('metrics'), 'exchange_ws_gate_open') == 1
    else:
        for node, x in nodes.items():
            axes['%s_gate_open' % node] = metric_value(x.get('metrics'), 'exchange_ws_gate_open') == 1
            axes['%s_handshake_101' % node] = (x.get('handshake') or {}).get('status') == 101
        axes['lb_handshake_101'] = (lb_handshake or {}).get('status') == 101
    return axes


def freshness_ok(pair):
    """신선도 표본 쌍 하나의 판정. 팔로워 seq 가 0 이거나 (epoch, seq) 가 앞선 리더 표본 이상이다.
    어느 쪽이든 200 이 아니거나 값이 없으면 판정을 채우지 못한 것으로 본다."""
    lead, fol = pair.get('leader') or {}, pair.get('follower') or {}
    if lead.get('status') != 200 or fol.get('status') != 200:
        return False
    if fol.get('seq') is None or fol.get('epoch') is None:
        return False
    if fol['seq'] == 0:
        return True
    if lead.get('seq') is None or lead.get('epoch') is None:
        return False
    return (fol['epoch'], fol['seq']) >= (lead['epoch'], lead['seq'])


def freshness_summary(pairs, t_from=None, t_to=None):
    """구간 [t_from, t_to] 의 표본 쌍 요약. 쌍이 없으면 all_ok 는 거짓이다."""
    rows = [p for p in pairs if (t_from is None or p['t'] >= t_from) and (t_to is None or p['t'] <= t_to)]
    bad = [p for p in rows if not freshness_ok(p)]
    ok_rows = [p for p in rows if freshness_ok(p)]
    nonzero = [p for p in ok_rows if (p.get('follower') or {}).get('seq') not in (0, None)]
    zero = [p for p in rows if (p.get('follower') or {}).get('status') == 200 and (p.get('follower') or {}).get('seq') == 0]
    return {'pairs': len(rows), 'all_ok': bool(rows) and not bad, 'bad': len(bad), 'bad_first': bad[:5],
            'follower_nonzero_ratio': (len(nonzero) / len(rows)) if rows else None,
            'follower_all_seq0': bool(rows) and len(zero) == len(rows)}


def parse_watch_log(text):
    """k6 관찰자 로그에서 'WATCH <json>' 을 노드별 시계열로 모은다. --log-format json 줄과 원문 줄을 모두 받는다."""
    by_node = {}
    for line in (text or '').splitlines():
        msg = None
        stripped = line.strip()
        if stripped.startswith('{'):
            try:
                obj = json.loads(stripped)
            except ValueError:
                obj = None
            if isinstance(obj, dict) and isinstance(obj.get('msg'), str):
                msg = obj['msg']
        elif WATCH_PREFIX in stripped:
            msg = stripped[stripped.index(WATCH_PREFIX):]
        if not msg or not msg.startswith(WATCH_PREFIX):
            continue
        try:
            state = json.loads(msg[len(WATCH_PREFIX):])
        except ValueError:
            continue
        node = state.get('node')
        if node:
            by_node.setdefault(node, []).append(state)
    out = {}
    for node, states in by_node.items():
        states.sort(key=lambda s: s.get('at') or 0)
        out[node] = {'latest': states[-1], 'series': [series_point(s) for s in states]}
    return out


def series_point(state):
    """누적 상태 하나를 시계열 점으로 줄인다. 마켓마다 [호가 실시간, 체결, 티커 실시간] 수다."""
    markets = {}
    for market, m in (state.get('markets') or {}).items():
        markets[market] = [((m.get('orderbook') or {}).get('realtime') or 0),
                           ((m.get('trade') or {}).get('messages') or 0),
                           ((m.get('ticker') or {}).get('realtime') or 0)]
    return {'at': state.get('at'), 'markets': markets, 'closes1013': (state.get('closeCodes') or {}).get('1013', 0),
            'handshakeErrors': state.get('handshakeErrors', 0), 'opens': state.get('opens', 0)}


def first_increase_after(series, market, t_ms):
    """t_ms 뒤 처음으로 그 마켓의 수신 수가 는 점의 시각(ms). 없으면 None 이다."""
    before = None
    for p in series:
        counts = (p.get('markets') or {}).get(market)
        if counts is None:
            continue
        total = sum(counts)
        if p['at'] <= t_ms:
            before = total
            continue
        if before is None:
            before = 0
        if total > before:
            return p['at']
    return None


def ranges_count(ranges):
    return sum(b - a + 1 for a, b in ranges)


def ranges_intersection_count(a, b):
    i = j = 0
    n = 0
    a = sorted(a)
    b = sorted(b)
    while i < len(a) and j < len(b):
        lo = max(a[i][0], b[j][0])
        hi = min(a[i][1], b[j][1])
        if lo <= hi:
            n += hi - lo + 1
        if a[i][1] < b[j][1]:
            i += 1
        else:
            j += 1
    return n


def book_set_diff_ratio(state_a, state_b, markets=MARKETS):
    """두 관찰자가 받은 호가 (market, epoch, seq) 집합의 대칭차 / 합집합. 한쪽이라도 호가를 받지 못했으면 None 이다."""
    union = diff = 0
    for market in markets:
        ra = ((((state_a.get('markets') or {}).get(market) or {}).get('orderbook') or {}).get('ranges')) or {}
        rb = ((((state_b.get('markets') or {}).get(market) or {}).get('orderbook') or {}).get('ranges')) or {}
        for epoch in set(ra) | set(rb):
            ca, cb = ranges_count(ra.get(epoch, [])), ranges_count(rb.get(epoch, []))
            inter = ranges_intersection_count(ra.get(epoch, []), rb.get(epoch, []))
            union += ca + cb - inter
            diff += ca + cb - 2 * inter
    if union == 0:
        return None
    return diff / union


def market_epochs(state, market):
    m = (state.get('markets') or {}).get(market) or {}
    epochs = set()
    for kind in ('orderbook', 'trade', 'ticker'):
        epochs.update((m.get(kind) or {}).get('epochs') or [])
    return sorted(epochs)


def dense_gaps(state, market, t_from_ms=None, t_to_ms=None):
    """조밀 채널(호가·티커)의 결번 수. 구간이 주어지면 그 안의 결번만 센다."""
    m = (state.get('markets') or {}).get(market) or {}
    n = 0
    for kind in ('orderbook', 'ticker'):
        for g in (m.get(kind) or {}).get('gaps') or []:
            if (t_from_ms is None or g['at'] >= t_from_ms) and (t_to_ms is None or g['at'] <= t_to_ms):
                n += 1
    return n


def closes_with(state, code, t_from_ms=None, t_to_ms=None):
    return sum(1 for c in state.get('closes') or []
               if c.get('code') == code and (t_from_ms is None or c['at'] >= t_from_ms)
               and (t_to_ms is None or c['at'] <= t_to_ms))


def refusals(state, t_from_ms=None, t_to_ms=None):
    """핸드셰이크 실패 수. 관찰자가 뒤따라 뜬 거절 코드(503)가 있으면 그 수를 함께 낸다."""
    errs = [e for e in state.get('errors') or [] if e.get('handshake')
            and (t_from_ms is None or e['at'] >= t_from_ms) and (t_to_ms is None or e['at'] <= t_to_ms)]
    probes = [r for r in state.get('refusals') or [] if r.get('status') == 503
              and (t_from_ms is None or r['at'] >= t_from_ms) and (t_to_ms is None or r['at'] <= t_to_ms)]
    return {'handshake_errors': len(errs), 'status_503': len(probes)}


def epoch_rise_once(state, market, kill_ms, limit_ms):
    """kill 뒤 그 마켓의 epoch 가 정확히 한 번 오르고, 새 epoch 의 첫 메시지가 limit_ms 안에 왔는가.
    반환은 (판정, 새 epoch 첫 수신 시각 ms)."""
    rises = [r for r in state.get('epochRises') or [] if r.get('market') == market and r['at'] >= kill_ms]
    epochs = market_epochs(state, market)
    if len(rises) < 1 or len(epochs) < 2:
        return False, None
    new_epochs = sorted({r['epoch'] for r in rises})
    first = min(r['at'] for r in rises)
    return len(new_epochs) == 1 and first - kill_ms <= limit_ms, first


def accept_ratio(during, before):
    """창 안 접수율 / 창 앞 접수율. 창 앞이 0 이거나 읽지 못했으면 None 이다."""
    if during is None or before is None or before <= 0:
        return None
    return during / before


def fail_closed(v):
    """None(읽지 못함)을 거짓으로 접는다."""
    return bool(v) if v is not None else False


def split_markets(text):
    return [m for m in (text or '').split(',') if m]


def epoch_rises_after(state, market, t_from_ms, t_to_ms=None):
    """그 마켓의 epoch 상승 기록 가운데 구간 안의 것."""
    return [r for r in state.get('epochRises') or [] if r.get('market') == market and r['at'] >= t_from_ms
            and (t_to_ms is None or r['at'] <= t_to_ms)]


def web_market_epochs(web, market):
    """웹 관찰의 그 마켓 WS 채널들(호가·체결·티커)에서 본 epoch 와 처음 본 시각(ms). {epoch: 처음 본 시각}."""
    out = {}
    for key, cur in ((web or {}).get('ws') or {}).get('markets', {}).items():
        name, _, _channel = key.partition('|')
        if name != market:
            continue
        for epoch, at in (cur.get('epochs') or {}).items():
            out[epoch] = min(at, out.get(epoch, at))
    return out


def web_market_gaps(web, market):
    return sum((cur.get('gaps') or 0) for key, cur in (((web or {}).get('ws') or {}).get('markets') or {}).items()
               if key.partition('|')[0] == market)


def web_requests_after(web, t_ms, path=None):
    return [r for r in (web or {}).get('requests') or [] if r.get('at', 0) >= t_ms and (path is None or r.get('path') == path)]


def web_w6_axes(web, kill_ms, affected, unaffected):
    """W6 웹 관찰 판정(S6 설계 7-2 W6, S7 설계 4-13). kill 뒤 티커 합류 요청의 markets 에 영향 마켓만 있고, 계좌·주문(미체결)
    REST 가 한 번씩이며, 비영향 마켓은 호가 REST 재조회가 0 이고 WS epoch 가 그대로이며 결번이 0 이다."""
    axes = {}
    tickers = web_requests_after(web, kill_ms, '/api/v1/ticker')
    asked = set()
    for r in tickers:
        asked.update(split_markets((r.get('query') or {}).get('markets')))
    axes['web_ticker_resync_requested'] = bool(tickers)
    axes['web_ticker_resync_only_affected'] = bool(asked) and asked <= set(affected)
    axes['web_accounts_rest_once'] = len(web_requests_after(web, kill_ms, '/api/v1/accounts')) == 1
    axes['web_open_orders_rest_once'] = len(web_requests_after(web, kill_ms, '/api/v1/orders/open')) == 1
    books = [r for r in web_requests_after(web, kill_ms, '/api/v1/orderbook')
             if (r.get('query') or {}).get('market') in unaffected]
    axes['web_unaffected_orderbook_rest_0'] = not books
    for market in unaffected:
        epochs = web_market_epochs(web, market)
        axes['web_%s_epoch_kept' % market] = bool(epochs) and all(at < kill_ms for at in epochs.values())
        axes['web_%s_gaps_0' % market] = bool(epochs) and web_market_gaps(web, market) == 0
    return axes


def web_w7_axes(web, p0_markets, p1_markets):
    """W7 웹 관찰 판정. 첫 적재 뒤 REST 가 0 이고, 두 파티션 마켓의 WS epoch 가 한 개씩이며 서로 다르다."""
    axes = {'web_rest_after_first_load_0': (web or {}).get('restAfterSettle') == 0}
    p0 = set()
    p1 = set()
    for market in p0_markets:
        p0.update(web_market_epochs(web, market))
    for market in p1_markets:
        p1.update(web_market_epochs(web, market))
    axes['web_partition_epochs_differ'] = len(p0) == 1 and len(p1) == 1 and p0 != p1
    return axes


def w6_watch_axes(survivor_state, kill_ms, rto_s, affected, unaffected):
    """W6 관찰자 축(S7 설계 4-13). 생존 노드에 붙은 연결은 다시 열리지 않고, 비영향 마켓은 kill 뒤 epoch 상승 0·결번 0, 영향 마켓은
    RTO + 5초 안에 epoch 가 정확히 한 번 오르고 그 뒤 결번 0 이다."""
    st = survivor_state or {}
    facts, axes = {'survivor_opens': st.get('opens')}, {}
    axes['survivor_watch_reconnect_0'] = st.get('opens') == 1
    limit_ms = ((rto_s or 0) + W6_FIRST_NEW_EPOCH_MARGIN_S) * 1000
    for market in unaffected:
        rises = epoch_rises_after(st, market, kill_ms)
        facts['%s_rises_after_kill' % market] = len(rises)
        axes['%s_unaffected_epoch_rise_0' % market] = bool(st) and not rises
        axes['%s_unaffected_dense_gaps_0' % market] = bool(st) and dense_gaps(st, market, kill_ms) == 0
    for market in affected:
        ok, first = epoch_rise_once(st, market, kill_ms, limit_ms)
        facts['%s_first_new_epoch_at_ms' % market] = first
        axes['%s_affected_epoch_rise_once_within_rto_plus_5s' % market] = ok
        axes['%s_affected_dense_gaps_after_rise_0' % market] = first is not None and dense_gaps(st, market, first) == 0
    return facts, axes


def w7_watch_axes(state, t_from_ms, t_to_ms, p0_markets, p1_markets):
    """W7 관찰자 축(S6 설계 7-2 W7). 한 연결(opens 1)로 두 파티션 마켓 모두 호가 실시간 100건 이상, 체결 10건 이상을 받고, 두
    파티션의 epoch 가 다르며, 창 안 epoch 상승 0, 조밀 결번 0 이다."""
    st = state or {}
    facts, axes = {'opens': st.get('opens')}, {'one_connection': st.get('opens') == 1}
    epochs = {}
    for market in list(p0_markets) + list(p1_markets):
        m = (st.get('markets') or {}).get(market) or {}
        axes['%s_book_ge_100' % market] = ((m.get('orderbook') or {}).get('realtime') or 0) >= W1_MIN_BOOK_REALTIME
        axes['%s_trades_ge_10' % market] = ((m.get('trade') or {}).get('messages') or 0) >= W1_MIN_TRADES
        axes['%s_epoch_rise_0' % market] = bool(st) and not epoch_rises_after(st, market, t_from_ms, t_to_ms)
        axes['%s_dense_gaps_0' % market] = bool(st) and dense_gaps(st, market, t_from_ms, t_to_ms) == 0
        epochs[market] = market_epochs(st, market)
    facts['epochs'] = epochs
    p0 = {e for m in p0_markets for e in epochs.get(m, [])}
    p1 = {e for m in p1_markets for e in epochs.get(m, [])}
    axes['partition_epochs_differ'] = len(p0) == 1 and len(p1) == 1 and p0 != p1
    return facts, axes


def verdict_with_web(axes, web_present):
    """W6·W7 판정 어휘. 웹 밖 축이 하나라도 거짓이면 FAIL, 모두 참이고 웹 관찰이 없으면 NOT RUN(웹 관찰), 웹 축까지 참이면 PASS."""
    core = {k: v for k, v in axes.items() if not k.startswith('web_')}
    if not core or not all(core.values()):
        return 'FAIL'
    if not web_present:
        return NOT_RUN_WEB
    return 'PASS' if all(axes.values()) else 'FAIL'


# ---------------------------------------------------------------- 채집(도커·HTTP·DB)

def http_json(url, timeout=5):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception as e:  # noqa: BLE001 - 표본기는 실패를 값으로 남긴다
        return None, {'error': type(e).__name__}


def http_text(url, timeout=10):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.read().decode('utf-8', 'replace'), None
    except Exception as e:  # noqa: BLE001
        return None, type(e).__name__


def ws_handshake(port, host='localhost', path='/ws/v1', timeout=5):
    """/ws/v1 업그레이드 요청을 보내고 응답 머리만 읽고 닫는다. Origin 을 싣지 않는다(비브라우저 클라이언트)."""
    key = base64.b64encode(os.urandom(16)).decode('ascii')
    req = ('GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
           'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n' % (path, host, port, key)).encode('ascii')
    try:
        with socket.create_connection((host, port), timeout=timeout) as s:
            s.sendall(req)
            buf = b''
            while b'\r\n\r\n' not in buf and len(buf) < 65536:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
        res = parse_handshake(buf)
        res['at'] = pgs.now_iso()
        return res
    except OSError as e:
        return {'status': None, 'retry_after': None, 'error': type(e).__name__, 'at': pgs.now_iso()}


def docker_logs(container, since=None):
    args = ['docker', 'logs']
    if since:
        args += ['--since', since]
    args.append(container)
    try:
        p = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
    except (subprocess.SubprocessError, OSError) as e:
        return None, type(e).__name__
    if p.returncode != 0:
        return None, 'docker logs 종료코드 %s' % p.returncode
    return p.stdout + p.stderr, None


def node_partitions(port):
    status, body = http_json('http://localhost:%d/actuator/partitions' % port)
    return body if status == 200 else {'error': status if status else (body or {}).get('error')}


def cmd_gate(a):
    started = pgs.now_iso()
    nodes = {}
    partitions = {}
    for name, port in NODES:
        book = [book_sample(*http_json('http://localhost:%d/api/v1/orderbook?market=%s' % (port, a.market)))
                for _ in range(a.samples)]
        ticker = [ticker_sample(*http_json('http://localhost:%d/api/v1/ticker?markets=%s' % (port, a.market)), a.market)
                  for _ in range(a.samples)]
        handshake = ws_handshake(port)
        text, err = http_text('http://localhost:%d/actuator/prometheus' % port)
        partitions[name] = node_partitions(port)
        nodes[name] = {'book': book, 'ticker': ticker, 'handshake': handshake,
                       'metrics': parse_metrics(text) if text is not None else {'error': err}}
    lb = ws_handshake(LB_PORT)
    leases, lease_err = pgs.parse_json_row(*pgs.run_psql(LEASE_SQL, timeout=60))
    # LB 로그는 핸드셰이크 뒤에 읽는다. 위 LB 핸드셰이크 자체가 503 재시도 증거를 남긴다
    time.sleep(1.0)
    lb_text, lb_err = docker_logs(LB, since=a.lb_since)
    leader = leader_from_partitions(partitions)
    epoch = lease_epoch(leases)
    axes = gate_axes(a.mode, nodes, lb, leader, epoch, lb_text or '', a.expect_follower)
    res = {'started': started, 'mode': a.mode, 'market': a.market, 'leader': leader, 'lease_epoch': epoch,
           'leases': leases if leases is not None else {'error': lease_err}, 'partitions': partitions,
           'nodes': nodes, 'lb_handshake': lb, 'lb_log': {'error': lb_err, 'ws_retry_seen': lb_ws_retry_seen(lb_text or '')},
           'axes': axes, 'ok': bool(axes) and all(axes.values()), 'finished': pgs.now_iso()}
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    print(json.dumps({'ok': res['ok'], 'failed': [k for k, v in axes.items() if not v], 'leader': leader},
                     ensure_ascii=False), flush=True)
    return 0 if res['ok'] else 1


def cmd_freshness(a):
    out = Path(a.out)
    stop = Path(a.stop_file)
    leader, follower, refreshed = None, None, 0.0
    with out.open('a', encoding='utf-8') as f:
        while not stop.exists():
            t0 = time.time()
            if t0 - refreshed > 5.0 or leader is None:
                leader, follower = pair_nodes({n: node_partitions(p) for n, p in NODES}, a.resource)
                refreshed = t0
            row = {'t': t0, 'at': iso(t0), 'leader_node': leader, 'follower_node': follower, 'resource': a.resource}
            if leader and follower:
                ports = dict(NODES)
                row['leader'] = book_sample(*http_json('http://localhost:%d/api/v1/orderbook?market=%s'
                                                       % (ports[leader], a.market)))
                time.sleep(max(0.0, a.lag_ms / 1000.0 - (time.time() - t0)))
                row['follower'] = book_sample(*http_json('http://localhost:%d/api/v1/orderbook?market=%s'
                                                         % (ports[follower], a.market)))
            f.write(json.dumps(row, ensure_ascii=False) + '\n')
            f.flush()
            time.sleep(max(0.0, a.interval - (time.time() - t0)))
    return 0


def cmd_watch(a):
    text = Path(a.log).read_text(encoding='utf-8', errors='replace') if Path(a.log).exists() else ''
    merged = parse_watch_log(text)
    Path(a.out).write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'nodes': sorted(merged), 'final': {n: x['latest'].get('final') for n, x in merged.items()}},
                     ensure_ascii=False), flush=True)
    return 0 if merged else 2


# ---------------------------------------------------------------- 판정(프로메테우스)

def prom(path, params):
    url = PROM + path + '?' + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            body = json.load(r)
        return body.get('data', {}).get('result')
    except Exception as e:  # noqa: BLE001
        return {'error': type(e).__name__}


def scalar(expr, at):
    res = prom('/api/v1/query', {'query': expr, 'time': '%.3f' % at})
    if isinstance(res, dict) or res is None:
        return None
    if not res:
        return 0.0
    return float(res[0]['value'][1])


def by_label(expr, label, at):
    res = prom('/api/v1/query', {'query': expr, 'time': '%.3f' % at})
    if isinstance(res, dict) or res is None:
        return None
    return {x['metric'].get(label, '?'): float(x['value'][1]) for x in res}


def series_range(expr, start, end, step=2):
    res = prom('/api/v1/query_range', {'query': expr, 'start': '%.3f' % start, 'end': '%.3f' % end, 'step': str(step)})
    if isinstance(res, dict) or res is None:
        return None
    return [{'metric': x['metric'], 'values': [(float(t), float(v)) for t, v in x['values']]} for x in res]


def first_time_all(series, predicate, after):
    """after 뒤 모든 계열이 동시에 predicate 를 채우는 첫 시각. 계열이 없으면 None 이다."""
    if not series:
        return None
    times = sorted({t for s in series for t, _ in s['values'] if t >= after})
    for t in times:
        vals = []
        for s in series:
            v = [val for tt, val in s['values'] if tt == t]
            if not v:
                break
            vals.append(v[0])
        if len(vals) == len(series) and all(predicate(v) for v in vals):
            return t
    return None


def window_increase(expr, start, end):
    if end <= start:
        return 0.0
    return scalar('sum(increase(%s[%ds]))' % (expr, max(1, round(end - start))), end)


def window_rate(expr, start, end):
    if end <= start:
        return None
    return scalar('sum(rate(%s[%ds]))' % (expr, max(1, round(end - start))), end)


def window_p99(bucket, start, end):
    if end <= start:
        return None
    return scalar('histogram_quantile(0.99, sum by (le) (rate(%s[%ds])))' % (bucket, max(1, round(end - start))), end)


def load_json(path):
    p = Path(path)
    if not p.exists():
        return None
    return json.loads(p.read_text(encoding='utf-8'))


def load_jsonl(path):
    p = Path(path)
    if not p.exists():
        return []
    rows = []
    for line in p.read_text(encoding='utf-8').splitlines():
        try:
            rows.append(json.loads(line))
        except ValueError:
            continue
    return rows


def watch_nodes(watch):
    """관찰자 JSON 의 노드 주소를 api-1·api-2·lb 로 가른다."""
    out = {}
    for url, x in (watch or {}).items():
        key = 'lb' if 'chaos-lb' in url else ('chaos-api-1' if 'chaos-api-1' in url else
                                                ('chaos-api-2' if 'chaos-api-2' in url else url))
        out[key] = x
    return out


def common_rest_axes(win_from, fault_from, fault_to, leader_instance):
    """W2~W4 공통 REST 축. 창 안 접수율, 리더 5xx, 강등, append p99."""
    before_from = fault_from - 60
    accept_during = window_rate('exchange_journal_append_seconds_count', fault_from, fault_to)
    accept_before = window_rate('exchange_journal_append_seconds_count', before_from, fault_from)
    ratio = accept_ratio(accept_during, accept_before)
    five_xx = window_increase('http_server_requests_seconds_count{instance="%s",status=~"5.."}' % leader_instance,
                              win_from, fault_to + 60)
    demotions = window_increase('exchange_partition_demotions_total', win_from, fault_to + 60)
    p99_during = window_p99('exchange_journal_append_seconds_bucket', fault_from, fault_to)
    p99_before = window_p99('exchange_journal_append_seconds_bucket', before_from, fault_from)
    facts = {'accept_rate_during': accept_during, 'accept_rate_before': accept_before, 'accept_ratio': ratio,
             'leader_5xx': five_xx, 'demotions': demotions, 'append_p99_during': p99_during,
             'append_p99_before': p99_before}
    axes = {
        'accept_ratio_ge_90pct': ratio is not None and ratio >= ACCEPT_RATIO_MIN,
        'leader_5xx_0': five_xx == 0,
        'demotions_0': demotions == 0,
        'append_p99_le_1_5x': (p99_during is not None and p99_before is not None and p99_before > 0
                               and p99_during <= APPEND_P99_FACTOR_MAX * p99_before),
    }
    return facts, axes


def judge_w1(ev, a, win_from, win_to):
    watch = watch_nodes(load_json(Path(ev) / 'watch.json'))
    pairs = load_jsonl(Path(ev) / 'freshness.jsonl')
    facts, axes = {}, {}
    for node in ('chaos-api-1', 'chaos-api-2', 'lb'):
        st = (watch.get(node) or {}).get('latest') or {}
        for market in MARKETS:
            m = (st.get('markets') or {}).get(market) or {}
            axes['%s_%s_book_ge_100' % (node, market)] = ((m.get('orderbook') or {}).get('realtime') or 0) >= W1_MIN_BOOK_REALTIME
            axes['%s_%s_trades_ge_10' % (node, market)] = ((m.get('trade') or {}).get('messages') or 0) >= W1_MIN_TRADES
            axes['%s_%s_dense_gaps_0' % (node, market)] = bool(st) and dense_gaps(st, market) == 0
            axes['%s_%s_one_epoch' % (node, market)] = len(market_epochs(st, market)) == 1
    a1 = (watch.get('chaos-api-1') or {}).get('latest') or {}
    a2 = (watch.get('chaos-api-2') or {}).get('latest') or {}
    ratio = book_set_diff_ratio(a1, a2)
    facts['book_set_diff_ratio'] = ratio
    axes['book_set_diff_le_0_1pct'] = ratio is not None and ratio <= W1_SET_DIFF_MAX
    facts['dropped'] = window_increase('exchange_ws_relay_dropped_total', win_from, win_to)
    axes['dropped_0'] = facts['dropped'] == 0
    conn = series_range('exchange_ws_relay_connected', win_from + 30, win_to)
    facts['connected_min'] = min((v for s in conn or [] for _, v in s['values']), default=None)
    axes['connected_always_1'] = facts['connected_min'] == 1
    deg = series_range('max by (instance) (exchange_ws_relay_degraded)', win_from + 30, win_to)
    facts['degraded_max'] = max((v for s in deg or [] for _, v in s['values']), default=None)
    axes['degraded_always_0'] = facts['degraded_max'] == 0
    facts['lag_p99_s'] = window_p99('exchange_ws_relay_lag_seconds_bucket', win_from + 30, win_to)
    axes['lag_p99_le_250ms'] = facts['lag_p99_s'] is not None and facts['lag_p99_s'] <= W1_LAG_P99_MAX_S
    fs = freshness_summary(pairs)
    facts['freshness'] = fs
    axes['freshness_300_pairs_all_ok'] = fs['pairs'] >= W1_FRESHNESS_PAIRS and fs['all_ok']
    axes['freshness_follower_nonzero_ge_90pct'] = (fs['follower_nonzero_ratio'] is not None
                                                   and fs['follower_nonzero_ratio'] >= W1_FOLLOWER_NONZERO_MIN)
    return facts, axes


def judge_outage(exp, ev, a, win_from, fault_from, fault_to, follower_node, leader_node):
    """W2(정지)·W3(멈춤)·W4(메모리 경보)."""
    fi, li = INSTANCES[follower_node], INSTANCES[leader_node]
    facts, axes = common_rest_axes(win_from, fault_from, fault_to, li)
    watch = watch_nodes(load_json(Path(ev) / 'watch.json'))
    pairs = load_jsonl(Path(ev) / 'freshness.jsonl')
    end = fault_to + 90
    fault_ms, recover_ms = fault_from * 1000, fault_to * 1000

    conn = series_range('exchange_ws_relay_connected', fault_from, end)
    reconnect_at = first_time_all(conn, lambda v: v == 1, fault_to)
    facts['reconnected_at'] = iso(reconnect_at) if reconnect_at else None
    axes['connected_back_within_%ds' % int(RECONNECT_WITHIN_S[exp])] = (
        reconnect_at is not None and reconnect_at - fault_to <= RECONNECT_WITHIN_S[exp])
    if exp in ('W2', 'W3'):
        dropped_to_zero = first_time_all(conn, lambda v: v == 0, fault_from)
        facts['connected_zero_at'] = iso(dropped_to_zero) if dropped_to_zero else None
        axes['connected_went_0'] = dropped_to_zero is not None
    else:
        sub = series_range('exchange_ws_relay_connected{link="sub"}', fault_from, fault_to)
        facts['sub_connected_min_during_alarm'] = min((v for s in sub or [] for _, v in s['values']), default=None)
        axes['sub_link_kept_during_alarm'] = facts['sub_connected_min_during_alarm'] == 1
        drops = by_label('sum by (reason) (increase(exchange_ws_relay_dropped_total[%ds]))'
                         % max(1, round(fault_to + 30 - fault_from)), 'reason', fault_to + 30)
        facts['dropped_by_reason'] = drops
        axes['dropped_write_timeout_or_queue_full_ge_1'] = bool(drops) and (
            (drops.get('write_timeout') or 0) + (drops.get('queue_full') or 0) >= 1)

    cause = 'owner_silent' if exp == 'W4' else None
    deg_expr = ('exchange_ws_relay_degraded{instance="%s",cause="owner_silent"}' % fi if cause
                else 'max by (instance) (exchange_ws_relay_degraded{instance="%s"})' % fi)
    fdeg = series_range(deg_expr, fault_from, end)
    fdeg_at = first_time_all(fdeg, lambda v: v == 1, fault_from)
    facts['follower_degraded_at'] = iso(fdeg_at) if fdeg_at else None
    axes['follower_degraded_within_20s'] = fdeg_at is not None and fdeg_at - fault_from <= DEGRADED_WITHIN_S
    fdeg_back = series_range('max by (instance) (exchange_ws_relay_degraded{instance="%s"})' % fi, fault_to, end)
    axes['follower_degraded_back_0'] = first_time_all(fdeg_back, lambda v: v == 0, fault_to) is not None
    ldeg = series_range('max by (instance) (exchange_ws_relay_degraded{instance="%s"})' % li, win_from, end)
    facts['leader_degraded_max'] = max((v for s in ldeg or [] for _, v in s['values']), default=None)
    axes['leader_degraded_always_0'] = facts['leader_degraded_max'] == 0

    fst = (watch.get(follower_node) or {}).get('latest') or {}
    lst = (watch.get(leader_node) or {}).get('latest') or {}
    facts['follower_watch'] = {'closes1013': closes_with(fst, 1013, fault_ms), 'refusals': refusals(fst, fault_ms)}
    facts['leader_watch'] = {'closes': len([c for c in lst.get('closes') or [] if c['at'] >= fault_ms])}
    axes['follower_watch_1013'] = facts['follower_watch']['closes1013'] >= 1
    axes['follower_watch_handshake_refused'] = facts['follower_watch']['refusals']['handshake_errors'] >= 1
    axes['leader_watch_kept_connection'] = bool(lst) and facts['leader_watch']['closes'] == 0
    leader_series = (watch.get(leader_node) or {}).get('series') or []
    axes['leader_watch_kept_receiving'] = all(
        first_increase_after(leader_series, m, fault_ms) is not None for m in MARKETS)
    again = {}
    for node in ('chaos-api-1', 'chaos-api-2', 'lb'):
        series = (watch.get(node) or {}).get('series') or []
        again[node] = {m: first_increase_after(series, m, recover_ms) for m in MARKETS}
    facts['receive_again_at_ms'] = again
    axes['all_watchers_receive_again_within_60s'] = all(
        t is not None and t - recover_ms <= RECEIVE_AGAIN_WITHIN_S * 1000 for x in again.values() for t in x.values())
    fs = freshness_summary(pairs, fault_from, fault_to + 60)
    facts['freshness'] = fs
    axes['freshness_during_and_60s_after_all_ok'] = fs['all_ok']
    if exp == 'W3':
        heap_before = scalar('max(max_over_time(jvm_memory_used_bytes{area="heap"}[60s]))', fault_from)
        heap_during = scalar('max(max_over_time(jvm_memory_used_bytes{area="heap"}[%ds]))'
                             % max(1, round(fault_to + 30 - fault_from)), fault_to + 30)
        facts['heap_before'], facts['heap_during'] = heap_before, heap_during
        axes['heap_growth_le_64MiB'] = (heap_before is not None and heap_during is not None
                                        and heap_during - heap_before <= HEAP_GROWTH_MAX_BYTES)
    if exp == 'W4':
        fs8 = freshness_summary(pairs, fault_from + W4_FALLBACK_AFTER_S, fault_to)
        facts['freshness_alarm_after_8s'] = fs8
        axes['follower_book_seq0_during_alarm'] = fs8['follower_all_seq0']
        fb = window_increase('exchange_ws_mirror_fallback_total{instance="%s",reason="owner_silent"}' % fi,
                             fault_from, fault_to)
        facts['fallback_owner_silent'] = fb
        axes['mirror_fallback_owner_silent_ge_1'] = fb is not None and fb >= 1
        gate = series_range('exchange_ws_gate_open{instance="%s"}' % fi, fault_to, fault_to + 30)
        axes['follower_gate_open_within_30s'] = first_time_all(gate, lambda v: v == 1, fault_to) is not None
        lag = scalar('max(max_over_time(exchange_ws_relay_lag_seconds_max{instance="%s"}[60s]))' % fi, fault_to + 60)
        facts['follower_lag_max_after'] = lag
        axes['follower_lag_max_le_5s_after'] = lag is not None and lag <= W4_LAG_MAX_S
    return facts, axes


def judge_w5(ev, a, win_from, kill_at, rto_s, follower_node='chaos-api-2'):
    """W5 리더 kill(P2, 중계). E4 규칙과 공통 판정은 실행기가 따로 본다. 여기는 중계 축이다."""
    watch = watch_nodes(load_json(Path(ev) / 'watch.json'))
    pairs = load_jsonl(Path(ev) / 'freshness.jsonl')
    kill_ms = kill_at * 1000
    facts, axes = {}, {}
    st = (watch.get(follower_node) or {}).get('latest') or {}
    facts['survivor_opens'] = st.get('opens')
    axes['survivor_watch_reconnect_0'] = st.get('opens') == 1
    deg = series_range('max by (instance) (exchange_ws_relay_degraded{instance="%s"})' % INSTANCES[follower_node],
                       win_from, kill_at + (rto_s or 30))
    facts['survivor_degraded_max_while_follower'] = max((v for s in deg or [] for _, v in s['values']), default=None)
    axes['survivor_never_degraded'] = facts['survivor_degraded_max_while_follower'] == 0
    limit_ms = ((rto_s or 0) + W5_FIRST_NEW_EPOCH_MARGIN_S) * 1000
    rises = {}
    for market in MARKETS:
        ok, first = epoch_rise_once(st, market, kill_ms, limit_ms)
        rises[market] = {'ok': ok, 'first_new_epoch_at_ms': first,
                         'gaps_after': dense_gaps(st, market, first) if first else None}
        axes['%s_epoch_rise_once_within_rto_plus_5s' % market] = ok
        axes['%s_dense_gaps_after_rise_0' % market] = first is not None and dense_gaps(st, market, first) == 0
    facts['epoch_rises'] = rises
    gate = series_range('exchange_ws_gate_open{instance="chaos-api-1:8080"}', kill_at, time.time())
    axes['rejoined_api1_gate_open'] = first_time_all(gate, lambda v: v == 1, kill_at) is not None
    fs = freshness_summary(pairs, kill_at + (rto_s or 30))
    facts['freshness_after_rejoin'] = fs
    axes['freshness_after_rejoin_all_ok'] = fs['all_ok']
    return facts, axes


def load_web(ev, path=None):
    """웹 관찰 파일. 인자로 준 경로가 없으면 실험 폴더의 web_resync.json 이다. 없으면 None 이다."""
    return load_json(path) if path else load_json(Path(ev) / WEB_FILE)


def judge_w6(ev, a, win_from, kill_at, rto_s):
    """W6 한 파티션 소유자 kill(P3, 중계). E4 의 P3 epoch 규칙과 공통 판정은 실행기가 따로 본다. 여기는 중계 축이다."""
    victim, survivor = a.victim, a.survivor
    affected, unaffected = split_markets(a.affected_markets), split_markets(a.unaffected_markets)
    watch = watch_nodes(load_json(Path(ev) / 'watch.json'))
    pairs = load_jsonl(Path(ev) / 'freshness.jsonl')
    kill_ms = kill_at * 1000
    st = (watch.get(survivor) or {}).get('latest') or {}
    facts, axes = w6_watch_axes(st, kill_ms, rto_s, affected, unaffected)
    facts['victim'], facts['survivor'] = victim, survivor
    facts['affected'], facts['unaffected'] = affected, unaffected
    axes['markets_given'] = bool(affected) and bool(unaffected) and not set(affected) & set(unaffected)
    axes['victim_survivor_differ'] = bool(victim) and bool(survivor) and victim != survivor
    deg = series_range('max by (instance) (exchange_ws_relay_degraded{instance="%s"})' % INSTANCES.get(survivor, survivor),
                       win_from, kill_at + (rto_s or 30))
    facts['survivor_degraded_max'] = max((v for s_ in deg or [] for _, v in s_['values']), default=None)
    axes['survivor_never_degraded'] = facts['survivor_degraded_max'] == 0
    gate = series_range('exchange_ws_gate_open{instance="%s"}' % INSTANCES.get(victim, victim), kill_at, time.time())
    axes['rejoined_victim_gate_open'] = first_time_all(gate, lambda v: v == 1, kill_at) is not None
    fs = freshness_summary(pairs, kill_at + (rto_s or 30))
    facts['freshness_after_rejoin'] = fs
    axes['freshness_after_rejoin_all_ok'] = fs['all_ok']
    web = load_web(ev, a.web)
    facts['web_file'] = a.web or str(Path(ev) / WEB_FILE)
    axes['web_observed'] = web is not None
    if web is not None:
        axes.update(web_w6_axes(web, kill_ms, affected, unaffected))
    return facts, axes, web is not None


def judge_w7(ev, a, win_from, win_to):
    """W7 한 연결로 두 파티션 마켓 수신(P3, 봇 부하 10분)."""
    p0, p1 = split_markets(a.p0_markets), split_markets(a.p1_markets)
    watch = watch_nodes(load_json(Path(ev) / 'watch.json'))
    st = (watch.get(a.node) or {}).get('latest') or {}
    facts, axes = w7_watch_axes(st, win_from * 1000, win_to * 1000, p0, p1)
    facts['node'] = a.node
    axes['markets_given'] = bool(p0) and bool(p1) and not set(p0) & set(p1)
    web = load_web(ev, a.web)
    facts['web_file'] = a.web or str(Path(ev) / WEB_FILE)
    axes['web_observed'] = web is not None
    if web is not None:
        axes.update(web_w7_axes(web, p0, p1))
    return facts, axes, web is not None


def cmd_judge(a):
    ev = a.evidence
    win_from = parse_iso(a.window_from).timestamp()
    now = time.time()
    fault_from = parse_iso(a.fault_from).timestamp() if a.fault_from else None
    fault_to = parse_iso(a.fault_to).timestamp() if a.fault_to else None
    res = {'experiment': a.experiment, 'started': pgs.now_iso(), 'window_from': a.window_from,
           'fault_from': a.fault_from, 'fault_to': a.fault_to}
    if a.experiment == 'W1':
        facts, axes = judge_w1(ev, a, win_from, now)
    elif a.experiment in ('W2', 'W3', 'W4'):
        if fault_from is None or fault_to is None:
            facts, axes = {'error': '장애 창이 없다'}, {'fault_window_given': False}
        else:
            facts, axes = judge_outage(a.experiment, ev, a, win_from, fault_from, fault_to, a.follower, a.leader)
    elif a.experiment == 'W7':
        win_to = parse_iso(a.window_to).timestamp() if a.window_to else now
        facts, axes, web_present = judge_w7(ev, a, win_from, win_to)
        res['verdict'] = verdict_with_web(axes, web_present)
    else:
        kill_at = parse_iso(a.kill_at).timestamp() if a.kill_at else fault_from
        if kill_at is None:
            facts, axes = {'error': 'kill 시각이 없다'}, {'kill_at_given': False}
        elif a.experiment == 'W6':
            facts, axes, web_present = judge_w6(ev, a, win_from, kill_at, a.rto_sec)
            res['verdict'] = verdict_with_web(axes, web_present)
        else:
            facts, axes = judge_w5(ev, a, win_from, kill_at, a.rto_sec)
    res['facts'] = facts
    res['axes'] = axes
    res['ok'] = bool(axes) and all(axes.values())
    res.setdefault('verdict', 'PASS' if res['ok'] else 'FAIL')
    res['finished'] = pgs.now_iso()
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    print(json.dumps({'ok': res['ok'], 'verdict': res['verdict'], 'failed': [k for k, v in axes.items() if not v]},
                     ensure_ascii=False), flush=True)
    if res['verdict'] == NOT_RUN_WEB:
        return 3
    return 0 if res['ok'] else 1


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    g = sub.add_parser('gate')
    g.add_argument('--mode', choices=('simple', 'broker'), required=True)
    g.add_argument('--samples', type=int, default=10)
    g.add_argument('--market', default='KRW-BTC')
    g.add_argument('--expect-follower', default=None)
    g.add_argument('--lb-since', default=None)
    g.add_argument('--out', required=True)
    f = sub.add_parser('freshness')
    f.add_argument('--out', required=True)
    f.add_argument('--stop-file', required=True)
    f.add_argument('--interval', type=float, default=1.0)
    f.add_argument('--lag-ms', type=float, default=500.0)
    f.add_argument('--market', default='KRW-BTC')
    f.add_argument('--resource', default=None, choices=('matching-p0', 'matching-p1'),
                   help='P3: 이 자원을 쥔 노드를 소유 노드로 짝짓는다(S7 설계 4-13)')
    w = sub.add_parser('watch')
    w.add_argument('--log', required=True)
    w.add_argument('--out', required=True)
    j = sub.add_parser('judge')
    j.add_argument('--experiment', choices=('W1', 'W2', 'W3', 'W4', 'W5', 'W6', 'W7'), required=True)
    j.add_argument('--evidence', required=True)
    j.add_argument('--window-from', required=True)
    j.add_argument('--fault-from', default=None)
    j.add_argument('--fault-to', default=None)
    j.add_argument('--kill-at', default=None)
    j.add_argument('--rto-sec', type=float, default=None)
    j.add_argument('--leader', default='chaos-api-1')
    j.add_argument('--follower', default='chaos-api-2')
    # W6·W7(S7 설계 4-13). 희생·생존 노드와 영향·비영향 마켓은 실행기가 콘솔의 kill 대상과 market_lease_partition 에서 정해 넘긴다.
    j.add_argument('--victim', default=None)
    j.add_argument('--survivor', default=None)
    j.add_argument('--affected-markets', default='')
    j.add_argument('--unaffected-markets', default='')
    j.add_argument('--window-to', default=None)
    j.add_argument('--node', default='chaos-api-1')
    j.add_argument('--p0-markets', default='')
    j.add_argument('--p1-markets', default='')
    j.add_argument('--web', default=None, help='웹 관찰 JSON(s6-web-resync.mjs 출력). 없으면 실험 폴더의 web_resync.json')
    j.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    return {'gate': cmd_gate, 'freshness': cmd_freshness, 'watch': cmd_watch, 'judge': cmd_judge}[a.cmd](a)


if __name__ == '__main__':
    sys.exit(main())
