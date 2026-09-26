"""S7 관문 실험의 판정 축(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 4-13·5-2).

폐기용 스택 실험 Q0~Q16 의 첫 관문 주행 기록은 harness/evidence/pml-S7-20260926-0300/ 이다. 이 파일은 실행기
(harness/scripts/s7-p3-gate.ps1, adr0022-stage5-chaos.ps1 -Protocol 3, protocol_latch.py)가 부를 채집기와 판정 규칙이다. 도커·DB 에 닿지 않는 판정 함수는 test_s7_axes.py 가 본다. 모든 판정은
축마다 참·거짓을 적고, 읽지 못한 값은 참으로 세지 않는다. 양성 축(판정력의 전제)이 모자라면 verdict 가 NOT RUN 이다.

하위 명령
  ack-interval   생존 파티션 ACK 간격(설계 5-2 공통 판정, 결정 10). 생존 파티션 마켓마다 command_journal NEW 행 created_at 의 이웃
                 간격을 본다. 기준선은 주입 전 120초의 최대, 판정 창은 주입부터 영향 파티션 첫 체결 + 10초다. 창 안 NEW 가 마켓마다
                 10건 이상이어야 판정한다. 창 최대가 기준선 + 1초 이하이면 참이다.
  p3-epoch       P3 E4 epoch 규칙(하네스 검증설계 §5-1). 옛 epoch 뒤 그 자원의 ACQUIRE 이력이 정확히 하나이고 모드 3 이며, 그 epoch 가
                 생존 노드가 /actuator/partitions 로 알린 그 자원의 epoch 다.
  segment-count  구간 경계 증분 표본(설계 4-7). harness/sql/segment-old-rows.sql 을 psql 로 돌려 이력 행마다 옛 구간 epoch 행 수를
                 적는다. 전환 도구가 전환 함수 직후·노드 시작 전에, 실행기가 실험 끝에 뜬다.
  segment-judge  --pre 표본의 이력 행마다 --post 값이 같은지 본다. 뒤 표본에 앞 이력 행이 없거나 값이 다르면 거짓이다. --pre 는
                 segment-count 출력이나 전환 도구 출력(protocol.json 의 segment_after_cutover)이고, 전환이 여럿이면(Q4) 여러 번 준다.
  epoch-sampler  희생 epoch 집합 표본기(하네스 검증설계 §3 P3 공통 판정). 1초마다 파티션 리스 행과, 지금까지 본 epoch 마다 네 표의
                 행 수를 적는다. 실행기가 실험 스크립트 전에 띄우고 --stop-file 로 멈춘다.
  victim-writes  희생 epoch 집합(주입 직전 표본에서 --resource 행의 holder 가 쥔 파티션 epoch 전부)의 구 쓰기 증분. 주입 뒤 첫 표본과
                 마지막 표본의 행 수가 epoch 마다 같아야 한다. 희생 노드가 두 파티션을 쥐었으면(Q3b) 두 epoch 를 모두 센다. 희생
                 파티션마다 다른 holder 가 처음 쥔 표본의 주입 뒤 초도 적는다(takeover_after_inject_s).
  partition-rto  한 파티션의 RTO. 옛 epoch 뒤 그 파티션 마켓의 첫 체결까지(T5, 하네스 검증설계 §5-1). 실행기가 희생 노드의 다른
                 파티션(Q3b)에 쓴다. -Resource 파티션의 RTO 는 실험 스크립트가 잰다.
  lb-hintless    Q15 기록(판정 아님). 힌트 없는 12마켓 부하 창의 LB 로그에서 주문 쓰기 502 수, 502 창의 수와 길이, ups 에
                 exchange_write 가 든 줄 수를 적는다.
  double-spend   Q1(E12) 판정. s7_double_spend.py 출력 JSON 을 읽는다.
  fee-coupling   Q8 판정. s7_fee_coupling.py 출력 JSON 을 읽는다.
  relocate       Q9 부하 중 수동 인계. 참여 끄기·켜기로 p1 을 두 번 옮기고 두 인계의 RTO, 생존 파티션 ACK 간격, 옛 epoch 쓰기 증분,
                 끝의 배정을 본다(수집과 판정을 함께 한다).
  market-fatal   Q10 판정. s7_market_fatal.py 출력 JSON 을 읽는다.
  cold-assign    Q11 두 노드 콜드 재시작 뒤 선호 배정. 두 앱을 멈췄다 함께 시작하고 배정, 두 파티션 첫 체결, 기동 뒤 2×TTL 동안
                 비선호 획득 0 을 본다(수집과 판정을 함께 한다).
  handoff        Q16 두 파티션 인계 판정. 요청 시점에 p1 을 유효하게 쥔 노드가 api-2 밖이면 그 회차는 NOT RUN 이다.

사용:
  python harness/scale/s7_axes.py segment-count --out <json>
  python harness/scale/s7_axes.py segment-judge --pre <json> --post <json> --out <json>
  python harness/scale/s7_axes.py ack-interval --survivor-markets KRW-BTC,KRW-XRP --affected-markets KRW-ETH
         --inject-at <ISO> [--end-at <ISO>] [--baseline-from <ISO>] --out <json>
  python harness/scale/s7_axes.py p3-epoch --resource matching-p1 --old-epoch <n> --survivor chaos-api-1 [--until <T5 ISO>]
         --out <json>
  python harness/scale/s7_axes.py epoch-sampler --out <jsonl> --stop-file <경로> [--interval 1]
  python harness/scale/s7_axes.py victim-writes --samples <jsonl> --inject-at <ISO> --resource matching-p1 --out <json>
  python harness/scale/s7_axes.py partition-rto --resource matching-p0 --old-epoch <n> --inject-at <ISO> --out <json>
  python harness/scale/s7_axes.py lb-hintless --log <LB 로그> --window-from <ISO> --window-to <ISO> --out <json>
  python harness/scale/s7_axes.py double-spend|fee-coupling|market-fatal|handoff --in <json> --out <json>
  python harness/scale/s7_axes.py relocate --out <json>
  python harness/scale/s7_axes.py cold-assign --out <json>
종료코드: 0 PASS, 1 FAIL, 2 수집 실패, 3 NOT RUN(양성 축 미달 또는 전제 불성립).
"""
import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402

HARNESS = Path(__file__).resolve().parents[1]
SEGMENT_SQL_FILE = HARNESS / 'sql' / 'segment-old-rows.sql'
NODES = (('chaos-api-1', 18080), ('chaos-api-2', 28080))
PORTS = dict(NODES)
PREFERRED = {'chaos-api-1': 'matching-p0', 'chaos-api-2': 'matching-p1'}
RESOURCES = ('matching-p0', 'matching-p1')
# V48 대응 시드(ADR 0034 결정한 값 3). 스택의 표를 읽을 수 없을 때만 쓰는 기본값이고 실행기는 market_lease_partition 을 읽는다.
DEFAULT_PARTITION_MARKETS = {'matching-p0': ('KRW-BTC', 'KRW-XRP'), 'matching-p1': ('KRW-ETH',)}

# 설계 5-2 의 값. 2026-09-25 설계를 맡은 AI 가 정했고 사용자 확인 대상이다.
ACK_BASELINE_S = 120.0
ACK_MARGIN_S = 1.0
ACK_MIN_NEW_PER_MARKET = 10
ACK_WINDOW_AFTER_FIRST_TRADE_S = 10.0
RTO_LIMIT_S = 30.0
COLD_FIRST_TRADE_S = 30.0
COLD_TRADE_WAIT_S = 60.0
TTL_S = 6.0
EXIT_PASS, EXIT_FAIL, EXIT_COLLECT, EXIT_NOT_RUN = 0, 1, 2, 3
NOT_RUN = 'NOT RUN'


def parse_iso(s):
    return dt.datetime.fromisoformat(s.replace('Z', '+00:00')).astimezone(dt.timezone.utc)


def ts(s):
    return parse_iso(s).timestamp()


def verdict_of(axes, positive=None):
    """축 묶음의 판정. positive(양성 축 이름 목록)가 하나라도 거짓이면 NOT RUN, 아니면 모든 축이 참일 때 PASS 다."""
    if not axes:
        return 'FAIL'
    if positive and not all(axes.get(k) for k in positive):
        return NOT_RUN
    return 'PASS' if all(axes.values()) else 'FAIL'


def exit_code(verdict):
    return {'PASS': EXIT_PASS, NOT_RUN: EXIT_NOT_RUN}.get(verdict, EXIT_FAIL)


# ---------------------------------------------------------------- 순수 판정 함수(시험 대상)

def owner_of(partitions_by_node, resource):
    """그 자원을 ACCEPTING 으로 쥔 노드 하나. 없거나 둘 이상이면 None 이다(s6_relay_axes.owner_of 와 같은 규칙)."""
    found = []
    for node, parts in (partitions_by_node or {}).items():
        rows = (parts or {}).get('partitions') if isinstance(parts, dict) else None
        if rows and any(p.get('resource') == resource and p.get('state') == 'ACCEPTING' for p in rows):
            found.append(node)
    return found[0] if len(found) == 1 else None


def reported_epoch(partitions, resource):
    """노드의 /actuator/partitions 응답에서 그 자원의 epoch(ACCEPTING 일 때만)."""
    for p in (partitions or {}).get('partitions') or []:
        if p.get('resource') == resource and p.get('state') == 'ACCEPTING':
            return p.get('epoch')
    return None


def assignment(partitions_by_node, preferred=None):
    """선호 배정 상태. ('ok', []) 는 모든 자원을 선호 노드가 쥔 것, ('wait', []) 는 아직 소유 노드가 없는 자원이 있는 것,
    ('relocate', [(끌 노드, 자원)]) 는 비선호 노드가 쥔 자원이 있어 그 노드의 참여를 꺼야 하는 것이다(DB 설계 §5.4-e 1)."""
    preferred = preferred or PREFERRED
    owners = {res: owner_of(partitions_by_node, res) for res in preferred.values()}
    if all(owners[res] == node for node, res in preferred.items()):
        return 'ok', []
    if any(v is None for v in owners.values()):
        return 'wait', []
    return 'relocate', [(owners[res], res) for node, res in sorted(preferred.items()) if owners[res] != node]


def max_gap(times):
    """정렬된 시각 목록의 이웃 간격 최대. 둘 미만이면 None 이다."""
    ordered = sorted(times)
    if len(ordered) < 2:
        return None
    return max(b - a for a, b in zip(ordered, ordered[1:]))


def ack_interval_judge(rows_by_market, inject_at, end_at, baseline_s=ACK_BASELINE_S, margin_s=ACK_MARGIN_S,
                       min_rows=ACK_MIN_NEW_PER_MARKET, baseline_from=None):
    """생존 파티션 ACK 간격(설계 5-2, 결정 10). rows_by_market 은 {마켓: [NEW 행 created_at(초)]} 다.
    마켓마다 기준선(주입 전 baseline_s 초의 이웃 간격 최대)과 창(주입~end_at)의 최대를 비교한다. 창 경계를 넘는 간격은 창에 넣는다
    (주입 직전 마지막 행과 창 첫 행 사이). 양성 축은 마켓마다 창 안 NEW 가 min_rows 건 이상이다.
    baseline_from(초)을 주면 기준선을 그 시각 뒤로 자른다. 모드 전환의 정지 창(두 노드 재시작)이 주입 전 120초에 들어가면 그 간격이
    기준선 최대가 되어 판정이 헐거워진다(2026-09-26 관문 첫 Q2: 기준선 최대 21.8초). 실행기는 Q0 준비 시각을 넘긴다."""
    facts, axes = {}, {}
    if not rows_by_market:
        return {'error': '생존 파티션 마켓이 없다'}, {'markets_given': False}, ['markets_given']
    positive = []
    for market, times in sorted(rows_by_market.items()):
        lo = inject_at - baseline_s if baseline_from is None else max(inject_at - baseline_s, baseline_from)
        base = [t for t in times if lo <= t < inject_at]
        window = [t for t in times if inject_at <= t <= end_at]
        last_before = max(base) if base else None
        window_span = ([last_before] if last_before is not None else []) + window
        b, w = max_gap(base), max_gap(window_span)
        facts[market] = {'baseline_from_s': lo, 'baseline_rows': len(base), 'window_rows': len(window), 'baseline_max_gap_s': b,
                         'window_max_gap_s': w}
        axes['%s_window_new_ge_%d' % (market, min_rows)] = len(window) >= min_rows
        axes['%s_baseline_measured' % market] = b is not None
        positive += ['%s_window_new_ge_%d' % (market, min_rows), '%s_baseline_measured' % market]
        axes['%s_ack_gap_le_baseline_plus_%gs' % (market, margin_s)] = (b is not None and w is not None
                                                                         and w <= b + margin_s)
    return facts, axes, positive


def p3_epoch_judge(acquisitions, resource, old_epoch, survivor_epoch):
    """P3 의 E4 epoch 규칙. acquisitions 는 lease_acquisitions 행 목록(epoch, resource, origin, protocol)이다."""
    after = [a for a in acquisitions or [] if a.get('resource') == resource and int(a.get('epoch')) > int(old_epoch)
             and a.get('origin') == 'ACQUIRE']
    axes = {
        'exactly_one_acquire_after_old_epoch': len(after) == 1,
        'acquired_in_p3': len(after) == 1 and int(after[0].get('protocol')) == 3,
        'holder_is_survivor': len(after) == 1 and survivor_epoch is not None
                              and int(after[0]['epoch']) == int(survivor_epoch),
    }
    return {'acquires_after_old': after, 'survivor_epoch': survivor_epoch}, axes


def segment_judge(pre, post):
    """구간 경계 증분(설계 4-7). pre·post 는 segment-count 출력의 rows([{id, protocol, epoch_floor, old_rows}])다."""
    pre_rows = {int(r['id']): int(r['old_rows']) for r in pre or []}
    post_rows = {int(r['id']): int(r['old_rows']) for r in post or []}
    axes, facts = {'pre_has_rows': bool(pre_rows)}, {}
    for hid, before in sorted(pre_rows.items()):
        after = post_rows.get(hid)
        facts[str(hid)] = {'pre': before, 'post': after}
        axes['history_%d_old_rows_unchanged' % hid] = after is not None and after == before
    return facts, axes


def segment_rows(doc):
    """segment-count 출력(rows)이나 전환 도구 출력(segment_after_cutover.rows)에서 이력 행 표본을 꺼낸다. 없으면 None 이다."""
    if not isinstance(doc, dict):
        return None
    if 'rows' in doc:
        return doc.get('rows')
    seg = doc.get('segment_after_cutover')
    return seg.get('rows') if isinstance(seg, dict) else None


def merge_pre(samples):
    """여러 앞 표본(전환마다 뜬 표본)을 이력 행 id 로 합친다. 같은 id 의 값이 표본끼리 다르면 consistent 가 거짓이다(그 사이에 옛
    구간 epoch 쓰기가 있었다). 합친 행은 id 순이고 처음 본 값을 쓴다."""
    merged, consistent = {}, True
    for rows in samples:
        for r in rows or []:
            hid = int(r['id'])
            if hid in merged:
                consistent = consistent and int(merged[hid]['old_rows']) == int(r['old_rows'])
            else:
                merged[hid] = dict(r)
    return [merged[k] for k in sorted(merged)], consistent


def victim_writes_judge(samples, inject_at, resource):
    """희생 epoch 집합의 구 쓰기 증분(하네스 검증설계 §3 P3 실험 공통 판정). samples 는 epoch-sampler 표본
    [{t(호스트 초), leases: [{resource, holder, epoch, alive}], counts: {epoch: 네 표 행 수}}] 이다.
    희생 노드는 주입 직전 표본에서 resource 행을 유효하게 쥔 holder 이고, 희생 epoch 집합은 그 표본에서 같은 holder 가 유효하게 쥔
    파티션 행의 epoch 전부다(한 노드는 파티션마다 같은 holder 를 쓴다). 기준은 주입 뒤 첫 표본, 끝은 마지막 표본이고 epoch 마다 두
    값이 같아야 한다. 주입 전·뒤 표본이 없으면 판정하지 않는다(양성 축)."""
    ok_samples = [s for s in samples or [] if isinstance(s, dict) and s.get('leases') is not None
                  and s.get('counts') is not None]
    pre = [s for s in ok_samples if s['t'] < inject_at]
    after = [s for s in ok_samples if s['t'] >= inject_at]
    positive = ['pre_sample_before_inject', 'victim_holder_known', 'base_sample_after_inject', 'post_after_base']
    axes = {k: False for k in positive}
    facts = {'samples': len(ok_samples), 'victim_resources': [], 'deltas': {}}
    if not pre:
        return facts, axes, positive
    axes['pre_sample_before_inject'] = True
    last = pre[-1]
    rows = [r for r in last['leases'] if r.get('alive')]
    holder = next((r.get('holder') for r in rows if r.get('resource') == resource), None)
    axes['victim_holder_known'] = bool(holder)
    if not holder:
        return facts, axes, positive
    victim = {str(r['epoch']): r['resource'] for r in rows if r.get('holder') == holder and r.get('resource') in RESOURCES}
    facts['victim_holder'] = holder
    facts['victim_resources'] = sorted(victim.values())
    facts['victim_epochs'] = sorted(int(e) for e in victim)
    facts['victim_map'] = dict(victim)
    # 희생 파티션마다 다른 holder 가 유효하게 쥔 첫 표본의 주입 뒤 초(Q3b 의 '선호 p1 을 먼저, p0 를 유예 뒤' 기록).
    takeovers = {}
    for s in after:
        for r in s['leases']:
            name = r.get('resource')
            if name in victim.values() and name not in takeovers and r.get('alive') and r.get('holder') != holder:
                takeovers[name] = round(s['t'] - inject_at, 3)
    facts['takeover_after_inject_s'] = takeovers
    covered = [s for s in after if all(e in s['counts'] for e in victim)]
    if not covered:
        return facts, axes, positive
    axes['base_sample_after_inject'] = True
    base, post = covered[0], covered[-1]
    facts['base_t'], facts['post_t'] = base['t'], post['t']
    axes['post_after_base'] = post['t'] > base['t']
    for e in sorted(victim, key=int):
        delta = int(post['counts'][e]) - int(base['counts'][e])
        facts['deltas'][e] = delta
        axes['victim_epoch_%s_writes_0' % e] = delta == 0
    return facts, axes, positive


def rto_judge(first_trade_s, inject_s):
    """한 파티션의 RTO(하네스 검증설계 §5-1 T5 를 그 파티션 마켓으로 한정). 첫 체결이 없으면 RTO 를 잴 수 없어 거짓이다(실험 스크립트와
    같이 실패 쪽으로 닫는다)."""
    rto = None if first_trade_s is None else round(float(first_trade_s) - float(inject_s), 3)
    return {'rto_s': rto}, {'rto_lt_30s': rto is not None and rto < RTO_LIMIT_S}


LB_LINE = re.compile(r'\[(\d{2})/(\w{3})/(\d{4}):(\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})\] "(\S+) (\S+)[^"]*" (\d{3}) '
                     r'.*?ups="([^"]*)"')
MONTHS = {m: i for i, m in enumerate(('Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'), 1)}
HINTLESS_GAP_S = 1.0


def lb_order_line(line):
    """LB 파티션 형식 줄(harness/nginx/nginx.partition-relay.conf)에서 주문 쓰기의 (초, 상태, ups). 다른 줄은 None 이다."""
    m = LB_LINE.search(line or '')
    if not m or m.group(10) not in ('POST', 'DELETE') or not m.group(11).startswith('/api/v1/orders'):
        return None
    sign = 1 if m.group(7) == '+' else -1
    offset = sign * (int(m.group(8)) * 3600 + int(m.group(9)) * 60)
    t = dt.datetime(int(m.group(3)), MONTHS[m.group(2)], int(m.group(1)), int(m.group(4)), int(m.group(5)), int(m.group(6)),
                    tzinfo=dt.timezone.utc).timestamp() - offset
    return t, int(m.group(12)), m.group(13)


def hintless_summary(lines, t_from, t_to):
    """Q15 기록(판정 아님). 창 안 주문 쓰기 줄 가운데 502 수, 502 창(이웃 간격 1초 이하를 한 창으로 묶음)의 수와 길이, ups 에
    업스트림 이름 exchange_write 가 든 줄 수(nginx 가 살아 있는 업스트림을 못 찾으면 주소 대신 이름을 적는다)."""
    rows = [x for x in (lb_order_line(line) for line in lines or []) if x and t_from <= x[0] <= t_to]
    bad = sorted(t for t, status, _ in rows if status == 502)
    windows = []
    for t in bad:
        if windows and t - windows[-1][1] <= HINTLESS_GAP_S:
            windows[-1][1] = t
        else:
            windows.append([t, t])
    return {'order_lines': len(rows), 'status_502': len(bad), 'windows': len(windows),
            'window_lengths_s': [round(b - a, 3) for a, b in windows],
            'ups_exchange_write_lines': sum(1 for _, _, ups in rows if 'exchange_write' in ups)}


def double_spend_judge(res):
    """Q1(E12) 판정(설계 5-2 Q1). res 는 s7_double_spend.py 의 출력이다."""
    r = res or {}
    rounds = r.get('rounds') or []
    requested = r.get('requested_rounds')
    facts = {'requested': requested, 'ran': len(rounds)}
    axes = {
        'all_rounds_ran': requested is not None and len(rounds) == requested,
        'exactly_one_success_every_round': bool(rounds) and all(x.get('successes') == 1 for x in rounds),
        'insufficient_rejections_gt_0': (r.get('order_insufficient', 0) + r.get('withdraw_insufficient', 0)) > 0,
        'order_insufficient_gt_0': r.get('order_insufficient', 0) > 0,
        'settlement_23514_delta_0': r.get('settlement_insufficient_delta') == 0,
        'fatal_demotions_0': r.get('fatal_demotions_delta') == 0,
        'server_errors_0': r.get('server_errors') == 0,
        'balance_restore_failures_0': r.get('restore_failures') == 0,
    }
    # 인증 거절(401·403)이 한 건이라도 있으면 그 회의 한쪽은 잔고 경합 전에 끝난 것이라 '성공 하나'가 경합의 결과가 아니다
    # (2026-09-26 첫 주행: 세션 서명 시드가 노드마다 달라 api-2 행위가 모두 401). 판정력의 전제이므로 양성 축이다.
    counts = r.get('status_counts')
    axes['auth_rejections_0'] = isinstance(counts, dict) and bool(counts) and not any(
        k.endswith(':401') or k.endswith(':403') for k in counts)
    for code in ('C01', 'C14', 'C31'):
        axes['post_%s_0' % code] = (r.get('post_invariants') or {}).get(code) == 0
    return facts, axes, ['all_rounds_ran', 'order_insufficient_gt_0', 'auth_rejections_0']


def fee_coupling_judge(res):
    """Q8 판정(설계 5-2 Q8). 멈춘 세션이 KRW 샤드 0 수수료 행을 쥔 동안 p1 정산이 기준선 + 1초 안이고 인계가 없다."""
    r = res or {}
    base = r.get('p1_settle_baseline_max_s')
    during = r.get('p1_settle_during_max_s')
    axes = {
        'fee_row_held_by_paused_session': r.get('lock_seen_after_pause') is True,
        'p1_trades_during_pause_ge_1': (r.get('p1_trades_during') or 0) >= 1,
        'krw_shard1_increased': r.get('krw_shard1_increased') is True,
        'api2_transient_exhausted_delta_0': r.get('api2_transient_exhausted_delta') == 0,
        'api2_fatal_demotions_0': r.get('api2_fatal_demotions_delta') == 0,
        'p1_settle_max_le_baseline_plus_1s': base is not None and during is not None and during <= base + ACK_MARGIN_S,
        'no_lease_handover': r.get('lease_handover') is False,
    }
    return {'attempts': r.get('attempts'), 'baseline_s': base, 'during_s': during}, axes, \
        ['fee_row_held_by_paused_session', 'p1_trades_during_pause_ge_1']


def market_fatal_judge(res):
    """Q10 판정(설계 5-2 Q10). p1 만 WRITER_FATAL 로 한 번 강등·재구성되고 30초 안에 다시 리더가 되며 p0 는 흔들리지 않는다."""
    r = res or {}
    demotions = r.get('demotions') or {}
    axes = {
        'injected_once': r.get('injected_failures') == 1,
        'p1_writer_fatal_once': demotions.get('matching-p1', {}).get('WRITER_FATAL') == 1,
        'p0_no_demotion': not any((demotions.get('matching-p0') or {}).values()),
        'breaker_not_open': r.get('breaker_open') is False,
        'p1_leader_again_within_30s': r.get('p1_reacquired_s') is not None and r['p1_reacquired_s'] <= RTO_LIMIT_S,
        'p0_ack_interval_ok': r.get('p0_ack_ok') is True,
        'post_invariants_pass': r.get('post_invariants_pass') is True,
    }
    # 생존 파티션 ACK 는 판정력의 전제다. 창 안 NEW 가 모자라 판정하지 못했으면(NOT RUN) FAIL 이 아니라 NOT RUN 이다(Q9 relocate
    # 의 p0_ack_measured 와 같은 규칙). 판정 파일에 ack 판정이 없던 입력(p0_ack_ok 만 있는 입력)은 종전처럼 그 값을 따른다.
    ack = (r.get('p0_ack') or {}).get('verdict') if isinstance(r.get('p0_ack'), dict) else None
    axes['p0_ack_measured'] = ack in ('PASS', 'FAIL') if 'p0_ack' in r else r.get('p0_ack_ok') is not None
    return {'reacquired_s': r.get('p1_reacquired_s')}, axes, ['injected_once', 'p0_ack_measured']


def cold_assign_judge(res):
    """Q11 판정(설계 5-2 Q11). 재배치 없이 선호 배정, 두 파티션 첫 체결 30초 이하, 기동 뒤 2×TTL 동안 비선호 획득 0."""
    r = res or {}
    firsts = r.get('first_trade_s') or {}
    axes = {
        'preferred_assignment_without_relocation': r.get('assignment') == 'ok' and not r.get('relocated'),
        'non_preferred_acquire_in_2ttl_0': r.get('non_preferred_acquires_in_2ttl') == 0,
    }
    for res_name in RESOURCES:
        v = firsts.get(res_name)
        axes['%s_first_trade_le_30s' % res_name] = v is not None and v <= COLD_FIRST_TRADE_S
    return {'first_trade_s': firsts}, axes


def relocate_judge(res):
    """Q9 판정(설계 5-2 Q9). 두 인계 모두 p1 RTO < 30초, 생존 파티션 ACK 간격, 옛 epoch 쓰기 증분 0, 재배치 뒤 배정이 Q0 과 같다."""
    r = res or {}
    moves = r.get('moves') or []
    axes = {'two_moves': len(moves) == 2}
    for i, m in enumerate(moves):
        axes['move%d_rto_lt_30s' % i] = m.get('rto_s') is not None and m['rto_s'] < RTO_LIMIT_S
        axes['move%d_old_epoch_writes_0' % i] = m.get('old_epoch_writes_delta') == 0
        axes['move%d_to_expected_node' % i] = m.get('new_owner') == m.get('expected_owner')
    axes['p0_ack_interval_ok'] = r.get('p0_ack_ok') is True
    axes['final_assignment_ok'] = r.get('final_assignment') == 'ok'
    return {'moves': moves}, axes


HANDOFF_OPEN_REASONS = ('partition_pending', 'partition_not_started')


def failed_reasons(failed):
    """일괄 취소 응답 failed[] 의 사유 목록(API 설계 §4-B-3)."""
    out = []
    for f in failed or []:
        if not isinstance(f, dict):
            continue
        reason = f.get('reason')
        if reason is None and isinstance(f.get('error'), dict):
            reason = f['error'].get('name')
        out.append(reason)
    return out


def handoff_judge(res):
    """Q16 두 파티션 인계(하네스 검증설계 §3 P3 실험 Q16, S5 Z4r 을 P3 에 옮김). res 는 s5_route_probe.py q16 의 출력이다.
    판정: 두 노드가 살아 있을 때 첫 일괄 취소가 기다림(5초) 안에 두 파티션 주문을 모두 취소, api-2 를 멈춘 동안 일괄 취소의 p1 주문은
    partition_pending·partition_not_started 이고 회원 폐쇄는 409 orders_cancel_incomplete, 재개 뒤 끝나지 않은 인계 행 0, 참여 복구.
    요청 시점에 p1 을 유효하게 쥔 노드가 api-2 밖에 있으면(api-1 이 넘겨받음) 그 회차는 NOT RUN 이다(설계 11절 지적 4)."""
    r = res or {}
    live = r.get('live') or {}
    resp = live.get('response') or {}
    paused = r.get('paused_requests') or []
    by_kind = {q.get('kind'): q for q in paused}
    cancel = by_kind.get('cancel_all') or {}
    close = by_kind.get('member_close') or {}
    rows = live.get('handoff_rows') or []
    states = live.get('order_states') or {}
    reasons = failed_reasons(cancel.get('failed'))
    pending = close.get('pending_reasons') or {}
    facts = {'paused_holders': [q.get('p1_holder_node') for q in paused], 'paused_cancel_reasons': reasons,
             'paused_close_pending_reasons': pending}
    positive = ['api1_p1_participation_off', 'paused_requests_sent', 'p1_not_validly_held_outside_api2']
    axes = {
        'api1_p1_participation_off': r.get('api1_p1_participate_off') == '200',
        'paused_requests_sent': 'cancel_all' in by_kind and 'member_close' in by_kind,
        'p1_not_validly_held_outside_api2': bool(paused) and all(
            q.get('p1_holder_node') in ('chaos-api-2', None) for q in paused),
        'live_cancel_all_200_canceled_4': resp.get('status') == 200 and resp.get('body_canceled_count') == 4
        and not resp.get('body_failed'),
        'live_orders_canceled': len(states) == 4 and all(v == 'CANCELED' for v in states.values()),
        'live_p1_handoff_row_done': any(x.get('resource') == 'matching-p1' and x.get('state') == 'DONE'
                                        and x.get('origin') == 'cancel_all' for x in rows),
        'paused_cancel_all_200_p1_open_reasons': cancel.get('status') == 200 and len(reasons) == 2
        and all(x in HANDOFF_OPEN_REASONS for x in reasons),
        'paused_member_close_409_incomplete': close.get('status') == 409
        and close.get('error') == 'orders_cancel_incomplete' and bool(pending)
        and all(k in HANDOFF_OPEN_REASONS for k in pending),
        'handoff_rows_closed_after_resume': (r.get('after_resume') or {}).get('open_rows') == 0,
        'api1_p1_participation_restored': r.get('api1_p1_participate_on') == '200',
    }
    return facts, axes, positive


# ---------------------------------------------------------------- 채집(도커·HTTP·DB)

def psql_json(sql, timeout=120):
    parsed, err = pgs.parse_json_row(*pgs.run_psql(sql, timeout=timeout))
    return parsed, err


def segment_sql():
    text = SEGMENT_SQL_FILE.read_text(encoding='utf-8')
    body = '\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('--')).strip().rstrip(';')
    return "SELECT coalesce(json_agg(row_to_json(x) ORDER BY x.id), '[]'::json) FROM (%s) x" % body


def segment_count():
    rows, err = psql_json(segment_sql())
    return {'ok': rows is not None, 'error': err, 'at': pgs.now_iso(), 'rows': rows}


def http_json(url, timeout=5):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode('utf-8', 'replace'))
    except Exception as e:  # noqa: BLE001 - 표본기는 실패를 값으로 남긴다
        return None, {'error': type(e).__name__}


def node_partitions(port):
    status, body = http_json('http://localhost:%d/actuator/partitions' % port)
    return body if status == 200 else {'error': status if status else (body or {}).get('error')}


def all_partitions():
    return {name: node_partitions(port) for name, port in NODES}


def set_participate(container, resource, value):
    """POST /actuator/partitions 를 그 컨테이너 안에서 부른다(loopback 전용, S5 설계 4-9)."""
    body = json.dumps({'resource': resource, 'participate': value})
    try:
        p = subprocess.run(['docker', 'exec', container, 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', '-X',
                            'POST', '-H', 'Content-Type: application/json', '-d', body,
                            'http://localhost:8080/actuator/partitions'],
                           capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
        return p.stdout.strip()
    except (subprocess.SubprocessError, OSError) as e:
        return type(e).__name__


def ack_rows(markets, since_s, until_s):
    """마켓마다 NEW 행 created_at(초). 창은 DB 시계다."""
    names = ','.join("'%s'" % m for m in markets)
    sql = ("SELECT coalesce(json_object_agg(symbol, ts), '{}'::json) FROM ("
           "SELECT m.symbol, json_agg(EXTRACT(EPOCH FROM c.created_at) ORDER BY c.created_at) AS ts "
           "FROM command_journal c JOIN markets m ON m.id = c.market_id "
           "WHERE c.command_type = 'NEW' AND m.symbol IN (%s) "
           "AND c.created_at >= to_timestamp(%.3f) AND c.created_at <= to_timestamp(%.3f) GROUP BY m.symbol) x"
           % (names, since_s, until_s))
    rows, err = psql_json(sql)
    return ({m: [float(t) for t in (rows or {}).get(m, [])] for m in markets} if rows is not None else None), err


def first_trade_after(markets, after_s):
    names = ','.join("'%s'" % m for m in markets)
    sql = ("SELECT json_build_object('t', EXTRACT(EPOCH FROM min(e.created_at))) FROM event_journal e "
           "JOIN markets m ON m.id = e.market_id WHERE e.event_type = 'TRADE' AND m.symbol IN (%s) "
           "AND e.created_at > to_timestamp(%.3f)" % (names, after_s))
    row, _ = psql_json(sql)
    return (row or {}).get('t')


def acquisitions_sql(resource, old_epoch, until_s=None):
    """옛 epoch 뒤 그 자원의 이력 행(하네스 검증설계 §5-1). until_s(T5, 초)를 주면 그 시각까지 획득한 행만 본다."""
    if resource not in RESOURCES:
        raise ValueError('P3 자원이 아니다: %s' % resource)
    sql = ("SELECT coalesce(json_agg(json_build_object('epoch', epoch, 'resource', resource, 'origin', origin, "
           "'protocol', protocol, 'acquired_at', acquired_at) ORDER BY epoch), '[]'::json) FROM lease_acquisitions "
           "WHERE resource = '%s' AND epoch > %d" % (resource, int(old_epoch)))
    if until_s is not None:
        sql += " AND acquired_at <= to_timestamp(%.3f)" % float(until_s)
    return sql


def acquisitions_after(resource, old_epoch, until_s=None):
    rows, err = psql_json(acquisitions_sql(resource, old_epoch, until_s))
    return rows, err


EPOCH_LEASES_SQL = ("SELECT coalesce(json_agg(json_build_object('resource', resource, 'holder', holder, 'epoch', epoch, "
                    "'alive', lease_until > clock_timestamp()) ORDER BY resource), '[]'::json) FROM leader_lease "
                    "WHERE resource IN ('matching-p0', 'matching-p1')")


def epoch_counts_sql(epochs):
    """epoch 마다 네 표(명령·이벤트·체결·스냅샷)의 행 수. json 객체 {epoch: 행 수}."""
    ids = ','.join(str(int(e)) for e in sorted(set(epochs)))
    return ("SELECT coalesce(json_object_agg(e, n), '{}'::json) FROM (SELECT e, "
            "(SELECT count(*) FROM command_journal WHERE fencing_token = e) "
            "+ (SELECT count(*) FROM event_journal WHERE fencing_epoch = e) "
            "+ (SELECT count(*) FROM trades WHERE fencing_epoch = e) "
            "+ (SELECT count(*) FROM snapshots WHERE fencing_epoch = e) AS n "
            "FROM unnest(ARRAY[%s]::bigint[]) AS e) x" % ids)


def epoch_sample(seen):
    """파티션 리스 행과, 지금까지 본 epoch 마다의 행 수 한 표본. seen 은 호출 사이에 이어지는 epoch 집합이다."""
    t = time.time()
    leases, err = psql_json(EPOCH_LEASES_SQL, timeout=20)
    if leases is None:
        return {'t': t, 'error': err}
    seen.update(int(r['epoch']) for r in leases if r.get('epoch') is not None)
    counts, err = psql_json(epoch_counts_sql(seen), timeout=20) if seen else ({}, None)
    if counts is None:
        return {'t': t, 'leases': leases, 'error': err}
    return {'t': t, 'leases': leases, 'counts': counts}


def partition_markets():
    """{자원: [마켓 심볼]} (market_lease_partition). 읽지 못하면 None."""
    sql = ("SELECT coalesce(json_object_agg(resource, symbols), '{}'::json) FROM (SELECT p.resource, "
           "json_agg(m.symbol ORDER BY m.id) AS symbols FROM market_lease_partition p JOIN markets m ON m.id = p.market_id "
           "GROUP BY p.resource) x")
    rows, _ = psql_json(sql)
    return rows


def write(path, res):
    Path(path).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    print(json.dumps({k: res.get(k) for k in ('verdict', 'failed', 'error') if k in res}, ensure_ascii=False),
          flush=True)
    return exit_code(res.get('verdict'))


def finish(path, name, facts, axes, positive=None, extra=None):
    verdict = verdict_of(axes, positive)
    res = {'judge': name, 'at': pgs.now_iso(), 'facts': facts, 'axes': axes, 'positive': positive or [],
           'verdict': verdict, 'failed': [k for k, v in axes.items() if not v]}
    if extra:
        res.update(extra)
    return write(path, res)


def cmd_segment_count(a):
    res = segment_count()
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    print(json.dumps({'ok': res['ok'], 'rows': res['rows']}, ensure_ascii=False, default=str), flush=True)
    return EXIT_PASS if res['ok'] else EXIT_COLLECT


def load(path):
    p = Path(path)
    return json.loads(p.read_text(encoding='utf-8')) if p.exists() else None


def cmd_segment_judge(a):
    pres = [segment_rows(load(p)) for p in a.pre]
    merged, consistent = merge_pre(pres)
    facts, axes = segment_judge(merged, segment_rows(load(a.post)))
    axes['pre_samples_read'] = all(r is not None for r in pres)
    axes['pre_samples_consistent'] = consistent
    return finish(a.out, 'segment-judge', facts, axes, ['pre_has_rows', 'pre_samples_read'], {'pre': a.pre, 'post': a.post})


def cmd_epoch_sampler(a):
    """희생 epoch 집합 표본기. 실행기가 실험 스크립트 전에 띄우고 스크립트가 끝나면 --stop-file 로 멈춘다."""
    seen = set()
    stop = Path(a.stop_file)
    with open(a.out, 'a', encoding='utf-8') as fh:
        while not stop.exists():
            fh.write(json.dumps(epoch_sample(seen), ensure_ascii=False, default=str) + '\n')
            fh.flush()
            end = time.monotonic() + a.interval
            while time.monotonic() < end and not stop.exists():
                time.sleep(0.1)
    return EXIT_PASS


def load_jsonl(path):
    rows = []
    p = Path(path)
    if p.exists():
        for line in p.read_text(encoding='utf-8').splitlines():
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass
    return rows


def cmd_victim_writes(a):
    samples = load_jsonl(a.samples)
    facts, axes, positive = victim_writes_judge(samples, ts(a.inject_at), a.resource)
    facts['inject_at'] = a.inject_at
    return finish(a.out, 'victim-writes', facts, axes, positive, {'samples_file': a.samples})


def cmd_ack_interval(a):
    inject = ts(a.inject_at)
    survivors = [m for m in a.survivor_markets.split(',') if m]
    affected = [m for m in a.affected_markets.split(',') if m]
    if a.end_at:
        end = ts(a.end_at)
    else:
        first = first_trade_after(affected, inject) if affected else None
        end = (first + ACK_WINDOW_AFTER_FIRST_TRADE_S) if first else None
    if end is None:
        return finish(a.out, 'ack-interval', {'error': '영향 파티션 첫 체결을 찾지 못했다'}, {'window_end_found': False},
                      ['window_end_found'])
    rows, err = ack_rows(survivors, inject - ACK_BASELINE_S, end)
    if rows is None:
        return finish(a.out, 'ack-interval', {'error': err}, {'rows_read': False}, ['rows_read'])
    facts, axes, positive = ack_interval_judge(rows, inject, end,
                                               baseline_from=ts(a.baseline_from) if a.baseline_from else None)
    facts['window'] = {'inject_at': a.inject_at, 'end_at_epoch_s': end, 'baseline_from': a.baseline_from}
    return finish(a.out, 'ack-interval', facts, axes, positive)


def first_partition_trade_sql(resource, old_epoch, after_s):
    """그 파티션 마켓의 옛 epoch 뒤 첫 체결(lib.ps1 Get-FirstTradeSql 의 파티션 판과 같은 술어, command_journal.fencing_token 축)."""
    if resource not in RESOURCES:
        raise ValueError('P3 자원이 아니다: %s' % resource)
    return ("SELECT json_build_object('t', EXTRACT(EPOCH FROM min(e.created_at))) FROM event_journal e "
            "JOIN command_journal c ON c.seq = e.source_cmd_seq WHERE e.event_type = 'TRADE' AND c.fencing_token > %d "
            "AND e.created_at > to_timestamp(%.3f) AND e.market_id IN "
            "(SELECT market_id FROM market_lease_partition WHERE resource = '%s')" % (int(old_epoch), float(after_s), resource))


def cmd_partition_rto(a):
    inject = ts(a.inject_at)
    row, err = psql_json(first_partition_trade_sql(a.resource, a.old_epoch, inject))
    if row is None:
        return finish(a.out, 'partition-rto', {'error': err}, {'first_trade_read': False}, ['first_trade_read'])
    facts, axes = rto_judge(row.get('t'), inject)
    facts.update(resource=a.resource, old_epoch=a.old_epoch, inject_at=a.inject_at)
    return finish(a.out, 'partition-rto', facts, axes)


def cmd_lb_hintless(a):
    lines = Path(a.log).read_text(encoding='utf-8', errors='replace').splitlines() if Path(a.log).exists() else None
    if lines is None:
        return finish(a.out, 'lb-hintless', {'error': 'LB 로그가 없다'}, {'lb_log_read': False}, ['lb_log_read'])
    facts = hintless_summary(lines, ts(a.window_from), ts(a.window_to))
    res = {'judge': 'lb-hintless', 'at': pgs.now_iso(), 'facts': facts, 'verdict': 'RECORD',
           'window': {'from': a.window_from, 'to': a.window_to}}
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(facts, ensure_ascii=False), flush=True)
    return EXIT_PASS if facts['order_lines'] > 0 else EXIT_NOT_RUN


def cmd_p3_epoch(a):
    rows, err = acquisitions_after(a.resource, a.old_epoch, ts(a.until) if a.until else None)
    survivor_epoch = reported_epoch(node_partitions(PORTS[a.survivor]), a.resource)
    if rows is None:
        return finish(a.out, 'p3-epoch', {'error': err}, {'acquisitions_read': False}, ['acquisitions_read'])
    facts, axes = p3_epoch_judge(rows, a.resource, a.old_epoch, survivor_epoch)
    return finish(a.out, 'p3-epoch', facts, axes)


def cmd_from_file(judge, name):
    def run(a):
        facts, axes, *rest = judge(load(a.inp))
        return finish(a.out, name, facts, axes, rest[0] if rest else None, {'input': a.inp})
    return run


def wait_for(check, limit_s, step_s=1.0):
    start = time.monotonic()
    while time.monotonic() - start < limit_s:
        v = check()
        if v:
            return v, round(time.monotonic() - start, 2)
        time.sleep(step_s)
    return None, round(time.monotonic() - start, 2)


def old_epoch_writes(epoch):
    sql = ("SELECT json_build_object('n', (SELECT count(*) FROM command_journal WHERE fencing_token = %d) "
           "+ (SELECT count(*) FROM event_journal WHERE fencing_epoch = %d))" % (epoch, epoch))
    row, _ = psql_json(sql)
    return (row or {}).get('n')


def move_partition(resource, from_node, expected_owner):
    """from_node 의 그 자원 참여를 끄고 expected_owner 가 얻을 때까지 잰다. 끈 참여는 다시 켠다(백업으로 남김)."""
    before = reported_epoch(node_partitions(PORTS[from_node]), resource)
    t0 = time.time()
    off = set_participate(from_node, resource, False)
    got, waited = wait_for(lambda: owner_of(all_partitions(), resource) == expected_owner, 90)
    rto = round(time.time() - t0, 2) if got else None
    time.sleep(2.0)
    delta_before = old_epoch_writes(before) if before is not None else None
    on = set_participate(from_node, resource, True)
    time.sleep(TTL_S)
    delta_after = old_epoch_writes(before) if before is not None else None
    return {'resource': resource, 'from': from_node, 'expected_owner': expected_owner,
            'new_owner': owner_of(all_partitions(), resource), 'participate_off': off, 'participate_on': on,
            'old_epoch': before, 'rto_s': rto, 'waited_s': waited,
            'old_epoch_writes_delta': (delta_after - delta_before) if None not in (delta_before, delta_after) else None,
            'at': dt.datetime.fromtimestamp(t0, dt.timezone.utc).isoformat()}


def combine_ack(verdicts):
    """인계마다 잰 생존 파티션 ACK 판정을 모은다. 반환은 (모두 PASS, 모두 판정됨)이다. 하나라도 NOT RUN 이면 판정됨이 거짓이다."""
    measured = bool(verdicts) and all(v in ('PASS', 'FAIL') for v in verdicts)
    return measured and all(v == 'PASS' for v in verdicts), measured


def cmd_relocate(a):
    """Q9. api-2 의 p1 참여를 꺼 api-1 이 유예 뒤 얻게 하고, 다시 켜도 돌아오지 않음을 본 뒤, api-1 의 p1 참여를 꺼 api-2 가 되찾게
    한다. 봇 부하는 실행기가 켜 둔다. 생존 파티션(p0) ACK 간격은 인계마다 참여를 끈 시각을 주입 시각으로, 새 소유 노드가 얻은 시각
    + 10초를 창 끝으로 잰다(ack-interval 과 같은 판정). 하나라도 양성 축이 모자라면 NOT RUN 이다."""
    if assignment(all_partitions())[0] != 'ok':
        return finish(a.out, 'relocate', {'error': '시작 배정이 선호가 아니다'}, {'start_assignment_ok': False},
                      ['start_assignment_ok'])
    moves = [move_partition('matching-p1', 'chaos-api-2', 'chaos-api-1')]
    time.sleep(TTL_S * 2)
    kept = owner_of(all_partitions(), 'matching-p1') == 'chaos-api-1'
    moves.append(move_partition('matching-p1', 'chaos-api-1', 'chaos-api-2'))
    time.sleep(TTL_S)
    survivors = list((partition_markets() or DEFAULT_PARTITION_MARKETS).get('matching-p0') or [])
    acks = []
    for m in moves:
        inject = ts(m['at'])
        end = inject + (m.get('rto_s') or RTO_LIMIT_S) + ACK_WINDOW_AFTER_FIRST_TRADE_S
        rows, err = ack_rows(survivors, inject - ACK_BASELINE_S, end)
        if rows is None:
            acks.append({'at': m['at'], 'verdict': NOT_RUN, 'error': err})
            continue
        f, ax, pos = ack_interval_judge(rows, inject, end)
        acks.append({'at': m['at'], 'facts': f, 'axes': ax, 'verdict': verdict_of(ax, pos)})
    p0_ok, measured = combine_ack([x['verdict'] for x in acks])
    res = {'moves': moves, 'no_automatic_failback': kept, 'final_assignment': assignment(all_partitions())[0],
           'p0_ack_ok': p0_ok, 'p0_ack': acks}
    facts, axes = relocate_judge(res)
    axes['no_automatic_failback'] = kept
    axes['p0_ack_measured'] = measured
    return finish(a.out, 'relocate', facts, axes, ['two_moves', 'p0_ack_measured'], {'raw': res})


def cmd_cold_assign(a):
    """Q11. 두 앱을 멈췄다가 함께 시작한다. 봇 부하는 실행기가 켠다."""
    for name, _ in NODES:
        subprocess.run(['docker', 'stop', name], capture_output=True, timeout=180)
    start = time.time()
    for name, _ in NODES:
        subprocess.run(['docker', 'start', name], capture_output=True, timeout=180)
    state, waited = wait_for(lambda: assignment(all_partitions())[0] == 'ok' or None, 120, 1.0)
    status = assignment(all_partitions())
    markets = partition_markets() or DEFAULT_PARTITION_MARKETS
    firsts = {}
    # 배정만 보고 곧바로 첫 체결을 읽으면 아직 체결이 없어 늘 None 이다(2026-09-26 첫 Q11: 배정 10.7초 뒤 읽음). 파티션마다 첫 체결을
    # 기동 뒤 60초까지 기다린다. 30초를 넘겨 온 값도 그대로 적어 판정이 늦음을 FAIL 로 본다.
    for res_name in RESOURCES:
        mk = list(markets.get(res_name) or DEFAULT_PARTITION_MARKETS[res_name])
        t, _ = wait_for(lambda: first_trade_after(mk, start), max(0.0, start + COLD_TRADE_WAIT_S - time.time()) or 0.1, 1.0)
        firsts[res_name] = round(t - start, 2) if t else None
    sql = ("SELECT json_build_object('n', count(*)) FROM lease_acquisitions a WHERE a.origin = 'ACQUIRE' "
           "AND a.acquired_at <= to_timestamp(%.3f) AND a.acquired_at >= to_timestamp(%.3f) "
           "AND NOT ((a.resource = 'matching-p0' AND a.epoch = (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0')) "
           "OR (a.resource = 'matching-p1' AND a.epoch = (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1')))"
           % (start + 2 * TTL_S, start))
    row, _ = psql_json(sql)
    res = {'assignment': status[0], 'relocated': False, 'waited_s': waited, 'first_trade_s': firsts,
           'non_preferred_acquires_in_2ttl': (row or {}).get('n')}
    facts, axes = cold_assign_judge(res)
    return finish(a.out, 'cold-assign', facts, axes, None, {'raw': res})


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('segment-count')
    s.add_argument('--out', required=True)
    s = sub.add_parser('segment-judge')
    s.add_argument('--pre', required=True, action='append',
                   help='segment-count 출력이나 전환 도구 출력(protocol.json). 전환마다 한 번씩 여러 번 준다')
    s.add_argument('--post', required=True)
    s.add_argument('--out', required=True)
    s = sub.add_parser('epoch-sampler')
    s.add_argument('--out', required=True)
    s.add_argument('--stop-file', required=True)
    s.add_argument('--interval', type=float, default=1.0)
    s = sub.add_parser('lb-hintless')
    s.add_argument('--log', required=True)
    s.add_argument('--window-from', required=True)
    s.add_argument('--window-to', required=True)
    s.add_argument('--out', required=True)
    s = sub.add_parser('partition-rto')
    s.add_argument('--resource', required=True, choices=RESOURCES)
    s.add_argument('--old-epoch', type=int, required=True)
    s.add_argument('--inject-at', required=True)
    s.add_argument('--out', required=True)
    s = sub.add_parser('victim-writes')
    s.add_argument('--samples', required=True)
    s.add_argument('--inject-at', required=True)
    s.add_argument('--resource', required=True, choices=RESOURCES)
    s.add_argument('--out', required=True)
    s = sub.add_parser('ack-interval')
    s.add_argument('--survivor-markets', required=True)
    s.add_argument('--affected-markets', default='')
    s.add_argument('--inject-at', required=True)
    s.add_argument('--end-at', default=None)
    s.add_argument('--baseline-from', default=None, help='기준선을 이 시각(ISO) 뒤로 자른다. 모드 전환 정지 창을 빼려고 Q0 준비 시각을 준다')
    s.add_argument('--out', required=True)
    s = sub.add_parser('p3-epoch')
    s.add_argument('--resource', required=True, choices=RESOURCES)
    s.add_argument('--old-epoch', type=int, required=True)
    s.add_argument('--survivor', required=True, choices=tuple(PORTS))
    s.add_argument('--until', default=None, help='T5(ISO). 주면 그 시각까지 획득한 행만 센다(하네스 검증설계 §5-1)')
    s.add_argument('--out', required=True)
    for name in ('double-spend', 'fee-coupling', 'market-fatal', 'handoff'):
        s = sub.add_parser(name)
        s.add_argument('--in', dest='inp', required=True)
        s.add_argument('--out', required=True)
    s = sub.add_parser('relocate')
    s.add_argument('--out', required=True)
    s = sub.add_parser('cold-assign')
    s.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    handlers = {
        'segment-count': cmd_segment_count, 'segment-judge': cmd_segment_judge, 'ack-interval': cmd_ack_interval,
        'epoch-sampler': cmd_epoch_sampler, 'victim-writes': cmd_victim_writes, 'partition-rto': cmd_partition_rto,
        'lb-hintless': cmd_lb_hintless,
        'p3-epoch': cmd_p3_epoch, 'double-spend': cmd_from_file(double_spend_judge, 'double-spend'),
        'fee-coupling': cmd_from_file(fee_coupling_judge, 'fee-coupling'),
        'market-fatal': cmd_from_file(market_fatal_judge, 'market-fatal'),
        'handoff': cmd_from_file(handoff_judge, 'handoff'), 'relocate': cmd_relocate, 'cold-assign': cmd_cold_assign,
    }
    return handlers[a.cmd](a)


if __name__ == '__main__':
    sys.exit(main())
