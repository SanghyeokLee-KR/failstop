"""Patroni 실험 판정(Patroni 설계 8-2 시각, 8-3 PASS 조건, 8-1 회차 무효 조건).

실행기(ep-lib.ps1)가 모은 네 원천을 읽어 시각을 뽑고 축마다 PASS·FAIL·판정 불가를 낸다.
  timeline.json      실행기가 적은 주입·복귀 시각, 주입 전후의 Patroni 리더·타임라인·앱 리더·epoch, DB 주소
  observe.jsonl      patroni_observe.py 표본(cluster, status, read, write, haproxy, pool, withdraw)
  ackrec.jsonl       ack_recorder.py 기록. ackrec-verify.json 은 최종 Primary 대조 결과
  facts.json         실행기가 DB·앱·Prometheus 에서 읽은 값(불변식 run, T5, 구 epoch 쓰기 증분, 계수 증분, 중복 행 수, 무효 조건 원천)

판정 규칙. 시간 축(T_ 구간 상한, 재개 뒤 첫 체결, 조회 표본 비율)은 회차 무효 조건에 걸리면 판정에서 빼고 판정 불가로 남긴다.
불변식, ACK 유실, 구 epoch 쓰기, 확인 커밋 구간, epoch 증가, 주문 500 은 무효 조건으로 면제하지 않는다(설계 8-1). 축 하나라도
FAIL 이면 FAIL, 판정 불가가 하나라도 있으면 INCONCLUSIVE, 모두 PASS 면 PASS 다. E-P4c·E-P8b·E-P9 처럼 기록만 하는 실험은 RECORD 다.

돌리는 법:
  python harness/scale/patroni_axes.py --scenario E-P2a --dir <회차 원시 폴더> --out <증거 폴더>/verdict.json
시험:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import argparse
import json
import os
import sys

import ack_recorder as ar

PASS, FAIL, NOT_JUDGED = True, False, None
# 비공허 조건(설계 8-1 (6)).
ACK_WINDOW_S = 30.0
ACK_MIN = 100
ALIVE_SLACK_S = 2.0
# 회차 무효 조건(설계 8-1, 검토 R13).
FSYNC_P99_MAX_S = 0.1
PEER_RTT_P99_MAX_S = 0.1
CPU_AVG_MAX = 80.0
# 불변식 가운데 E-P 축이 이름으로 요구하는 것(설계 8-3 E-P1: C01·C02·C05·C07'·C13A·C14'·C17·C31). 이름 앞머리로 가른다.
# C07' 는 C07P_DUP_ORDER_JOURNAL, C14' 는 C14_* 셋이다.
NAMED_CHECK_PREFIXES = ('C01_', 'C02_', 'C05_', 'C07P_', 'C13A_', 'C14_', 'C17_', 'C31_')
RECORD_ONLY = ('E-P4c', 'E-P8b', 'E-P9')
# 실행기가 지킨 유지 시간(주입~복귀)의 허용 오차. 주입 명령 자체(docker stop 의 정상 종료 등)가 몇 초 걸린다.
HOLD_SLACK_S = 10.0


def load_jsonl(path):
    if not path or not os.path.exists(path):
        return []
    out = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def load_json(path, default=None):
    if not path or not os.path.exists(path):
        return default
    with open(path, encoding='utf-8') as fh:
        return json.load(fh)


def samples(observe, kind, **match):
    out = [s for s in observe if s.get('kind') == kind and all(s.get(k) == v for k, v in match.items())]
    return sorted(out, key=lambda s: s['t'])


# ---------------------------------------------------------------------------
# 시각(설계 8-2)
# ---------------------------------------------------------------------------

def t_suspend(observe, t0):
    """주입 뒤 공개 상태가 trading_suspended=true 가 된 첫 표본(어느 노드든)."""
    hits = [s['t'] for s in samples(observe, 'status') if s['t'] >= t0 and s.get('trading_suspended') is True]
    return min(hits) if hits else None


def t_resume(observe, t_from):
    """t_from 뒤 모든 노드가 끝까지 ACTIVE 로 남는 첫 시각. 노드마다 마지막 중단 표본 다음의 첫 ACTIVE 표본을 잡고 그 최댓값이다.
    도중에 다시 중단되면 그 뒤의 재개를 잡는다. 어느 노드든 끝까지 중단이면 None."""
    if t_from is None:
        return None
    nodes = sorted({s['node'] for s in samples(observe, 'status') if s.get('node') != 'lb'})
    per_node = []
    for node in nodes:
        seq = [s for s in samples(observe, 'status', node=node) if s['t'] >= t_from and s.get('code') == 200]
        if not seq:
            return None
        last_suspended = max((s['t'] for s in seq if s.get('trading_suspended') is True), default=None)
        after = [s['t'] for s in seq if s.get('trading_suspended') is False
                 and (last_suspended is None or s['t'] > last_suspended)]
        if not after:
            return None
        per_node.append(min(after))
    return max(per_node) if per_node else None


def t_promote(observe, t0, old_leader, old_timeline):
    """주입 뒤 Patroni 리더가 옛 리더가 아닌 노드가 되거나 타임라인이 오른 첫 표본."""
    for s in samples(observe, 'cluster', code=200):
        if s['t'] < t0:
            continue
        leader, tl = s.get('leader'), s.get('timeline')
        if leader and leader != old_leader:
            return s['t']
        if tl is not None and old_timeline is not None and tl > old_timeline:
            return s['t']
    return None


def t_sync(observe, t_back, member):
    """복귀 뒤 그 멤버가 동기 Standby 로 적힌 첫 표본."""
    if t_back is None or not member:
        return None
    hits = [s['t'] for s in samples(observe, 'cluster', code=200) if s['t'] >= t_back and member in (s.get('sync') or [])]
    return min(hits) if hits else None


def t_down(observe, t0, server):
    """앱 노드 HAProxy 가운데 하나라도 옛 Primary 서버를 UP 에서 뺀 첫 표본."""
    if not server:
        return None
    hits = [s['t'] for s in samples(observe, 'haproxy', code=200) if s['t'] >= t0 and server not in (s.get('up') or [])]
    return min(hits) if hits else None


def t_reads(observe, t0):
    """주입 뒤 조회 대표 경로가 다시 모두 200 인 첫 시각. 주입 뒤 실패가 없었으면 주입 뒤 첫 전부 200 시각이다."""
    reads = [s for s in samples(observe, 'read') if s['t'] >= t0]
    if not reads:
        return None
    paths = sorted({s['path'] for s in reads})
    first_fail = min((s['t'] for s in reads if s.get('code') != 200), default=None)
    latest = {}
    for s in reads:
        latest[s['path']] = s.get('code')
        if first_fail is not None and s['t'] < first_fail:
            continue
        if len(latest) == len(paths) and all(code == 200 for code in latest.values()):
            return s['t']
    return None


def first_sync_loss(observe, t0):
    """주입 뒤 /cluster 에 동기 Standby 가 없어진 첫 표본(E-P2b 에서 '*' 전환 전에 중단을 알아챘는지 견준다)."""
    hits = [s['t'] for s in samples(observe, 'cluster', code=200) if s['t'] >= t0 and not s.get('sync')]
    return min(hits) if hits else None


def derive_times(timeline, observe, facts):
    t0 = timeline['t0']
    pre = timeline.get('pre', {})
    injected = timeline.get('injected')
    times = {'T0': t0, 'T_back': timeline.get('t_back')}
    times['T_suspend'] = t_suspend(observe, t0)
    times['T_resume'] = t_resume(observe, times['T_suspend']) if times['T_suspend'] else None
    times['T_promote'] = t_promote(observe, t0, pre.get('patroni_leader'), pre.get('timeline'))
    sync_member = timeline.get('sync_member') or injected
    times['T_sync'] = t_sync(observe, times['T_back'], sync_member)
    server = (timeline.get('haproxy_server') or {}).get(pre.get('patroni_leader'))
    times['T_down'] = t_down(observe, t0, server) if timeline.get('role_injected') == 'primary' else None
    times['T_reads'] = t_reads(observe, t0)
    times['T5'] = facts.get('t5')
    times['T_first_trade_after_resume'] = facts.get('first_trade_after_resume')
    times['T_sync_lost'] = first_sync_loss(observe, t0)
    return times


def diff(a, b):
    return None if a is None or b is None else round(a - b, 3)


# ---------------------------------------------------------------------------
# 축
# ---------------------------------------------------------------------------

def axis(axis_id, value, ok, limit=None, time_axis=False, note=None):
    return {'id': axis_id, 'value': value, 'limit': limit, 'pass': ok, 'time_axis': time_axis, 'note': note}


def upper(axis_id, value, limit, time_axis=True, note=None):
    ok = NOT_JUDGED if value is None else value <= limit
    return axis(axis_id, value, ok, '<= %s' % limit, time_axis, note)


def equals(axis_id, value, expected, time_axis=False, note=None):
    ok = NOT_JUDGED if value is None else value == expected
    return axis(axis_id, value, ok, '== %s' % expected, time_axis, note)


def invariant_axes(facts):
    post = (facts.get('invariants') or {}).get('post')
    if not post or not post.get('checks'):
        return [axis('post_invariants', None, NOT_JUDGED, note='사후 권위 run 없음')]
    checks = post['checks']
    gated_violations = 0
    named = {}
    for c in checks:
        name = c.get('checkName') or ''
        rows = max(0, int(c.get('violatedRows') or 0))
        if name.startswith(NAMED_CHECK_PREFIXES):
            named[name] = rows
        if name == 'C16_ACK_JOURNALED':
            continue
        if c.get('severity') == 'WARN' or name in ('C13A_ORDER_MATERIALIZED', 'C19_ORDERBOOK_NONCROSS'):
            continue
        gated_violations += rows
    c16 = next((max(0, int(c.get('violatedRows') or 0)) for c in checks if c.get('checkName') == 'C16_ACK_JOURNALED'), None)
    out = [equals('post_invariants_gated', gated_violations, 0),
           equals('post_invariants_named', sum(named.values()), 0, note=json.dumps(named, ensure_ascii=False))]
    out.append(equals('c16_rpo_records', c16, 0, note='C16(loadtest_acks 대조)'))
    return out


def ackrec_axes(timeline, times, records, verify):
    """DB 수준 기록기의 RPO 축과 비공허 조건(설계 8-1 (5)~(7))."""
    out = []
    if not records or not verify:
        return [axis('ackrec_rpo', None, NOT_JUDGED, note='기록기 기록 또는 대조 결과 없음')], {}
    t0 = timeline['t0']
    t_end = next((r['t'] for r in records if r.get('type') == 'stop'), timeline.get('t_end'))
    pre_acked = ar.acked_between(records, t0 - ACK_WINDOW_S, t0)
    resume = times.get('T_resume') or timeline.get('t_resume_hint')
    post_acked = ar.acked_between(records, resume, resume + ACK_WINDOW_S) if resume else None
    alive = ar.last_alive(records)
    writers = sorted({r['writer'] for r in records if r.get('type') == 'attempt'})
    stale = [w for w in writers if t_end is None or alive.get(w, 0) < t_end - ALIVE_SLACK_S]
    reasons = ar.reasons(records)
    vacuous = []
    if pre_acked < ACK_MIN:
        vacuous.append('주입 전 30초 acked %d < %d' % (pre_acked, ACK_MIN))
    if post_acked is None or post_acked < ACK_MIN:
        vacuous.append('재개 뒤 30초 acked %s < %d' % (post_acked, ACK_MIN))
    if stale:
        vacuous.append('마지막 살아 있음이 끝 2초 밖인 스레드 %s' % stale)
    summary = {'pre_acked_30s': pre_acked, 'post_resume_acked_30s': post_acked, 'last_alive': alive,
               'not_acked_reasons': reasons, 'verify': verify, 'vacuous': vacuous}
    if vacuous:
        out.append(axis('ackrec_rpo', verify.get('missing_count'), NOT_JUDGED, '== 0', note='; '.join(vacuous)))
    else:
        out.append(equals('ackrec_rpo', verify.get('missing_count'), 0, note='acked 인데 최종 Primary 에 없는 번호'))
    out.append(equals('ackrec_failed_present', verify.get('failed_present_count'), 0,
                      note='failed 로 분류했는데 있는 번호(분류 결함)'))
    return out, summary


MEMBERS = ('patroni-db-1', 'patroni-db-2')
# 멤버 walsender 의 backend_start(DB 시계)와 기록기 끝 시각(호스트 시계)을 견줄 때 두는 여유. 시계 가드가 잰 차이보다 넉넉하다.
MEMBER_START_SLACK_S = 0.25


def confirmed_without_member_axis(records, times, new_primary_ip, members=MEMBERS):
    """설계 8-1 (7)의 뜻: 승격 뒤 멤버 사본 없이 확인된 쓰기 0. 끝을 /cluster 의 sync_standby(T_sync)가 아니라 새 Primary 에서 멤버의
    walsender 가 붙은 시각(pg_stat_replication.backend_start)으로 둔다. '*' 아래에서는 멤버가 붙는 순간부터 커밋이 확인되고,
    /cluster 표시는 Patroni HA 루프만큼 늦어 그 사이의 정상 확인을 위반으로 센다(2026-09-25 E-P1 첫 회차, 설계 13절 확인 필요).
    스레드마다 그 구간에 새 Primary 로 보낸 시도가 있어야 비공허다."""
    axis_id = 'confirmed_writes_before_member'
    lo = times.get('T_promote')
    if lo is None or not records:
        return axis(axis_id, None, NOT_JUDGED, '== 0', note='승격 시각 또는 기록 없음')
    start = ar.member_walsender_start(records, new_primary_ip, members, lo)
    if start is None:
        return axis(axis_id, None, NOT_JUDGED, '== 0', note='새 Primary 의 멤버 walsender 표본(repl) 없음')
    count = sum(1 for r in ar.attempts(records)
                if r['result'] == ar.ACKED and lo <= r['t_end'] < start - MEMBER_START_SLACK_S)
    sent = ar.sent_to_server_between(records, new_primary_ip, lo, start)
    note = '멤버 walsender 시작 %.3f(승격 뒤 %.2f초), 스레드별 새 Primary 시도 %s' % (start, start - lo, sent)
    if not sent or not all(sent.values()):
        return axis(axis_id, count, NOT_JUDGED, '== 0', note='비공허 조건 미충족. ' + note)
    return equals(axis_id, count, 0, note=note)


def confirmed_between_record(records, times):
    """설계 문구 그대로의 수(T_promote~T_sync 사이 끝난 acked). 판정에 쓰지 않고 기록만 한다."""
    lo, hi = times.get('T_promote'), times.get('T_sync')
    if lo is None or hi is None or not records:
        return None
    return ar.acked_between(records, lo, hi)


def window(observe, kind, lo, hi):
    return [s for s in samples(observe, kind) if lo is not None and hi is not None and lo <= s['t'] <= hi]


def suspension_window_axes(observe, times):
    """E-P2a·E-P2b 중단 구간(T_suspend + 2초 ~ T_back): 조회 99% 이상 200, 쓰기는 모두 423 이고 429 0."""
    lo = None if times.get('T_suspend') is None else times['T_suspend'] + 2
    hi = times.get('T_back')
    reads = window(observe, 'read', lo, hi)
    writes = window(observe, 'write', lo, hi)
    out = []
    if not reads:
        out.append(axis('reads_ok_ratio_suspended', None, NOT_JUDGED, '>= 0.99', True, '구간 조회 표본 없음'))
    else:
        ratio = round(sum(1 for s in reads if s.get('code') == 200) / len(reads), 4)
        out.append(axis('reads_ok_ratio_suspended', ratio, ratio >= 0.99, '>= 0.99', True, 'n=%d' % len(reads)))
    if not writes:
        out.append(axis('writes_all_423_suspended', None, NOT_JUDGED, 'all 423', note='구간 쓰기 표본 없음'))
    else:
        codes = {}
        for s in writes:
            codes[s.get('code')] = codes.get(s.get('code'), 0) + 1
        out.append(axis('writes_all_423_suspended', codes, set(codes) == {423}, 'all 423', note='n=%d' % len(writes)))
        out.append(equals('writes_429_suspended', codes.get(429, 0), 0, note='게이트가 수용 제한 앞인지(검토 R6)'))
    return out


def status_true_axis(observe, times):
    lo, hi = times.get('T0'), times.get('T_resume')
    seen = any(s.get('trading_suspended') is True for s in window(observe, 'status', lo, hi))
    return axis('status_reports_suspension', seen, seen if hi is not None else NOT_JUDGED, 'true')


def never_suspended_axis(observe, lo, hi, node=None, axis_id='no_suspension', reason=None):
    """그 구간 상태 표본 가운데 중단(trading_suspended)인 수. reason 을 주면 그 이유의 중단만 센다. 상태 API 는 SUSPENDED 와
    UNREACHABLE 을 모두 trading_suspended=true 로 내고 reason 으로 가른다(replication_unavailable, database_unavailable)."""
    seq = [s for s in window(observe, 'status', lo, hi) if node is None or s.get('node') == node]
    if not seq:
        return axis(axis_id, None, NOT_JUDGED, '0', note='표본 없음')
    count = sum(1 for s in seq if s.get('trading_suspended') is True and (reason is None or s.get('reason') == reason))
    other = sum(1 for s in seq if s.get('trading_suspended') is True) - count
    note = 'n=%d' % len(seq) + ('' if reason is None else ', 다른 이유의 중단 %d' % other)
    return equals(axis_id, count, 0, note=note)


def suspension_samples(observe, lo, hi, node, reason):
    """그 구간 그 노드의 상태 표본 수와 그 가운데 그 이유로 중단을 낸 수(기록용)."""
    seq = [s for s in window(observe, 'status', lo, hi) if s.get('node') == node]
    return {'n': len(seq),
            'count': sum(1 for s in seq if s.get('trading_suspended') is True and s.get('reason') == reason)}


def withdraw_axes(observe, facts):
    recs = samples(observe, 'withdraw')
    if not recs:
        return [], {}
    uncertain = {}
    for r in recs:
        code = r.get('code')
        if code == 423 or code == 0 or (code or 0) >= 500:
            uncertain.setdefault(r['key'], r['t'])
    resent = {r['key'] for r in recs if r.get('resend')}
    end = next((s for s in observe if s.get('kind') == 'withdraw_end'), {})
    unresolved = [k for k in uncertain if k not in resent]
    summary = {'requests': len(recs), 'uncertain_keys': len(uncertain), 'resent_same_key': len(resent & set(uncertain)),
               'pending_at_end': end.get('pending'), 'unresolved': unresolved[:20]}
    return [equals('withdraw_rows_per_request', facts.get('withdraw_duplicates'), 0,
                   note='요청 하나(Idempotency-Key)에 출금 행 둘 이상인 수')], summary


def pool_maxima(observe):
    """노드별 기본 풀 활성 연결 수와 수용 in-flight 의 최댓값(E-P2a·E-P5 기록, 설계 8-3)."""
    out = {}
    for s in samples(observe, 'pool', code=200):
        node = out.setdefault(s['node'], {'active': {}, 'inflight': {}})
        for kind in ('active', 'inflight'):
            for key, value in (s.get(kind) or {}).items():
                node[kind][key] = max(node[kind].get(key, 0), value)
    return out or None


def metric_delta(facts, name):
    m = facts.get('metrics') or {}
    before, after = m.get('before') or {}, m.get('after') or {}
    if name not in after:
        return None
    return round(sum(after[name].values()) - sum((before.get(name) or {}).values()), 6) \
        if isinstance(after[name], dict) else round(after[name] - before.get(name, 0), 6)


def invalidity(facts, scenario):
    """회차 무효 조건(설계 8-1). 맞으면 시간 축 판정을 뺀다."""
    inv = facts.get('invalid') or {}
    reasons = []
    # etcd 를 주입한 실험(E-P3a·b 는 Primary 와 같은 역할의 etcd 멤버를 함께 떼고, E-P4·E-P8 은 etcd 를 멈추거나 바꾼다)에서는
    # 리더 교체가 주입의 결과라 무효 조건 (a)를 적용하지 않는다.
    etcd_injected = scenario.startswith(('E-P3', 'E-P4', 'E-P8'))
    changes = inv.get('etcd_leader_changes')
    if not etcd_injected and changes not in (None, 0):
        reasons.append('etcd 리더 교체 %s' % changes)
    if inv.get('fsync_p99_s') is not None and inv['fsync_p99_s'] > FSYNC_P99_MAX_S:
        reasons.append('etcd fsync p99 %.3fs' % inv['fsync_p99_s'])
    if inv.get('peer_rtt_p99_s') is not None and inv['peer_rtt_p99_s'] > PEER_RTT_P99_MAX_S:
        reasons.append('etcd 피어 왕복 p99 %.3fs' % inv['peer_rtt_p99_s'])
    cpu = inv.get('cpu_samples') or []
    if cpu and sum(cpu) / len(cpu) >= CPU_AVG_MAX:
        reasons.append('호스트 CPU 평균 %.1f%%' % (sum(cpu) / len(cpu)))
    foreign = inv.get('foreign_processes') or []
    if foreign:
        reasons.append('실험 밖 부하 도구 %s' % sorted(set(foreign)))
    unknown = [k for k in ('etcd_leader_changes', 'fsync_p99_s', 'cpu_samples') if inv.get(k) is None]
    return reasons, unknown


# ---------------------------------------------------------------------------
# 실험별 축(설계 8-3)
# ---------------------------------------------------------------------------

def common_integrity(facts):
    out = invariant_axes(facts)
    out.append(equals('stale_epoch_writes', facts.get('stale_write_delta'), 0, note='구 epoch 쓰기 증분'))
    out.append(equals('order_500', facts.get('orders_500'), 0, note='주문 500(앱 지표·관측 쓰기·k6)'))
    return out


def failover_axes(timeline, times, observe, records, facts, new_primary_ip):
    pre, post = timeline.get('pre', {}), timeline.get('post', {})
    out = [
        upper('promote_s', diff(times['T_promote'], times['T0']), 30),
        upper('reads_after_promote_s', diff(times['T_reads'], times['T_promote']), 10),
        upper('resume_after_sync_s', diff(times['T_resume'], times['T_sync']), 5),
        upper('t5_after_sync_s', diff(times['T5'], times['T_sync']), 30),
        equals('app_epoch_increase', None if post.get('epoch') is None or pre.get('epoch') is None
               else post['epoch'] - pre['epoch'], 1),
        equals('post_promotion_fencing_rejections', (facts.get('post_promotion') or {}).get('fencing_rejections'), 0),
        equals('post_promotion_demotions', (facts.get('post_promotion') or {}).get('demotions'), 0),
        equals('lease_acquire_expired', metric_delta(facts, 'lease_acquire_expired'), 0, note='검토 R5'),
        confirmed_without_member_axis(records, times, new_primary_ip),
        status_true_axis(observe, times),
        equals('rejoin_nodes_consistent', (facts.get('rejoin') or {}).get('equal'), True,
               note='E-P6a: 옛 Primary 재합류 뒤 두 노드의 주요 표 건수·합(방법 %s)' % (facts.get('rejoin') or {}).get('method')),
    ]
    return out + common_integrity(facts)


def judge(scenario, timeline, observe, records, verify, facts):
    times = derive_times(timeline, observe, facts)
    new_primary = (timeline.get('post') or {}).get('patroni_leader')
    new_primary_ip = (timeline.get('db_ips') or {}).get(new_primary)
    axes = []
    ack_axes, ack_summary = ackrec_axes(timeline, times, records, verify)
    extra = {}
    if scenario in ('E-P1', 'E-P3a', 'E-P3b', 'E-P5'):
        axes += failover_axes(timeline, times, observe, records, facts, new_primary_ip)
        axes += ack_axes
        extra['acked_between_promote_and_cluster_sync'] = confirmed_between_record(records, times)
        extra['nonmember_walsenders'] = ar.nonmember_walsenders(records, MEMBERS, times.get('T0'), times.get('T_sync'))[:20]
        if scenario in ('E-P3a', 'E-P3b'):
            overlap = ar.acked_overlap(records) if records else None
            axes.append(equals('two_primaries_confirmed_overlap', None if overlap is None else len(overlap['overlaps']), 0,
                               note='두 노드가 같은 시간대에 확인한 커밋'))
            extra['acked_overlap'] = overlap
        if scenario == 'E-P5':
            axes.append(equals('duplicate_coid_orders', facts.get('duplicate_coid_orders'), 0))
            wd_axes, extra['withdraw'] = withdraw_axes(observe, facts)
            axes += wd_axes
            extra['k6'] = facts.get('k6')
            extra['pool_max'] = pool_maxima(observe)
            after = (facts.get('metrics') or {}).get('after') or {}
            extra['v47'] = {k: metric_delta(facts, k) for k in ('commit_recovered', 'unresolved', 'unavailable')
                            if k in after}
            extra['definitive_deferred'] = metric_delta(facts, 'definitive_deferred')
    elif scenario == 'E-P1b':
        # 타임라인은 판정 축이 아니라 기록 항목이다(사용자 확정 2026-09-25, 설계 13절 26). Patroni 4.1.5 는 리더 키를 쥔 채
        # 재기동한 노드를 읽기 전용으로 띄운 뒤 스스로 다시 승격하므로 같은 노드여도 타임라인이 하나 오른다.
        pre, post = timeline.get('pre', {}), timeline.get('post', {})
        extra['timeline_change'] = {'pre': pre.get('timeline'), 'post': post.get('timeline'),
                                    'delta': diff(post.get('timeline'), pre.get('timeline'))}
        extra['patroni_leader_changed'] = None if not post.get('patroni_leader') \
            else pre.get('patroni_leader') != post.get('patroni_leader')
        axes += ack_axes
        axes += common_integrity(facts)
        extra['definitive_deferred'] = metric_delta(facts, 'definitive_deferred')
        extra['app_leader_changed'] = pre.get('app_leader') != post.get('app_leader')
    elif scenario in ('E-P2a', 'E-P2b'):
        pre, post = timeline.get('pre', {}), timeline.get('post', {})
        axes += [
            upper('suspend_after_inject_s', diff(times['T_suspend'], times['T0']), 5),
            upper('resume_after_sync_s', diff(times['T_resume'], times['T_sync']), 5),
            upper('first_trade_after_resume_s', diff(times['T_first_trade_after_resume'], times['T_resume']), 10),
            equals('app_epoch_unchanged', None if post.get('epoch') is None else post['epoch'] - pre.get('epoch', 0), 0),
            equals('demotions', metric_delta(facts, 'demotions'), 0, note='강등·자발적 강등·회복 가능 반납 합'),
            equals('syncrep_waiters_after_resume', facts.get('syncrep_waiters_after_resume'), 0),
            equals('duplicate_coid_orders', facts.get('duplicate_coid_orders'), 0),
        ]
        axes += suspension_window_axes(observe, times)
        axes += ack_axes
        axes += common_integrity(facts)
        wd_axes, extra['withdraw'] = withdraw_axes(observe, facts)
        axes += wd_axes
        extra['k6'] = facts.get('k6')
        extra['pool_max'] = pool_maxima(observe)
        if scenario == 'E-P2b':
            extra['suspend_before_sync_loss_s'] = diff(times['T_sync_lost'], times['T_suspend'])
    elif scenario == 'E-P4a':
        axes += [never_suspended_axis(observe, timeline['t0'], timeline.get('t_back')),
                 equals('etcd_member_down_fired', facts.get('alerts_fired', {}).get('EtcdMemberDown'), True)]
        axes += common_integrity(facts)
    elif scenario == 'E-P4b':
        axes += [never_suspended_axis(observe, timeline['t0'], timeline.get('t_back')),
                 equals('failsafe_active_seen', facts.get('failsafe_max'), 1),
                 equals('etcd_quorum_lost_fired', facts.get('alerts_fired', {}).get('EtcdQuorumLost'), True)]
        axes += common_integrity(facts)
    elif scenario == 'E-P7':
        t0 = timeline['t0']
        axes += [
            # 느린 경로의 노드는 탐지 연결이 끊겨 UNREACHABLE(503)일 수 있다. 판정은 클러스터 중단(SUSPENDED)만 본다(검토 R3).
            never_suspended_axis(observe, t0, timeline.get('t_back'), node='api-1', axis_id='api1_suspended_samples',
                                 reason='replication_unavailable'),
            equals('gate_423_delta', metric_delta(facts, 'gate_423'), 0),
            upper('t5_after_inject_s', diff(times['T5'], t0), 60, note='api-2 승격과 체결 재개'),
            equals('app_epoch_increase', None if (timeline.get('post') or {}).get('epoch') is None
                   else timeline['post']['epoch'] - timeline['pre']['epoch'], 1),
            axis('probe_unconfirmed_increase', metric_delta(facts, 'probe_unconfirmed'),
                 None if metric_delta(facts, 'probe_unconfirmed') is None else metric_delta(facts, 'probe_unconfirmed') > 0,
                 '> 0', note='비공허'),
        ]
        axes += common_integrity(facts)
        # 확인 연결의 소켓 제한(2초)을 넘는 지연은 UNREACHABLE(503, 공개 상태 database_unavailable)이다(설계 4-4). 기록으로 남긴다.
        extra['api1_unreachable'] = suspension_samples(observe, t0, timeline.get('t_back'), 'api-1', 'database_unavailable')
        extra['gate_503_delta'] = metric_delta(facts, 'gate_503')
    else:
        axes += common_integrity(facts)
    reasons, unknown = invalidity(facts, scenario)
    hold = timeline.get('hold_s')
    if hold is not None and timeline.get('t_back') is not None:
        actual = timeline['t_back'] - timeline['t0']
        if abs(actual - hold) > HOLD_SLACK_S:
            # 실행 규약을 벗어난 회차다(유지 시간). 시간 축을 빼고 규약 축을 판정 불가로 둔다. 안전 축의 FAIL 은 그대로다.
            reasons.append('유지 시간 %.1f초가 계획 %s초와 다르다' % (actual, hold))
            axes.append(axis('protocol_hold', round(actual, 1), NOT_JUDGED, '%s±%s' % (hold, HOLD_SLACK_S),
                             note='실행기가 주입 뒤 유지 시간을 지키지 못했다'))
    for a in axes:
        if a['time_axis'] and reasons and a['pass'] is not None:
            a['note'] = ((a['note'] + '; ') if a['note'] else '') + '회차 무효 조건으로 판정에서 뺌(값 %s)' % a['pass']
            a['pass'] = NOT_JUDGED
    if scenario in RECORD_ONLY:
        verdict = 'RECORD'
    elif any(a['pass'] is FAIL for a in axes):
        verdict = 'FAIL'
    elif any(a['pass'] is NOT_JUDGED for a in axes):
        verdict = 'INCONCLUSIVE'
    else:
        verdict = 'PASS'
    return {
        'scenario': scenario,
        'run_id': timeline.get('run_id'),
        'verdict': verdict,
        'times': times,
        'derived': {
            'db_failover_s': diff(times['T_promote'], times['T0']),
            'read_recovery_s': diff(times['T_reads'], times['T0']),
            'resume_delay_s': diff(times['T_resume'], times['T_sync']),
            'trade_resume_delay_s': diff(times['T5'], times['T_sync']),
            'suspend_detect_s': diff(times['T_suspend'], times['T0']),
            'haproxy_down_s': diff(times['T_down'], times['T0']),
        },
        'axes': axes,
        'ackrec': ack_summary,
        'invalid': {'time_axes_invalid': bool(reasons), 'reasons': reasons, 'unknown_sources': unknown},
        'extra': extra,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description='Patroni 실험 판정')
    parser.add_argument('--scenario', required=True)
    parser.add_argument('--dir', required=True, help='회차 원시 폴더(timeline.json, observe.jsonl, ackrec.jsonl, ...)')
    parser.add_argument('--out', required=True)
    args = parser.parse_args(argv)
    d = args.dir
    timeline = load_json(os.path.join(d, 'timeline.json'))
    if not timeline:
        raise SystemExit('timeline.json 없음: ' + d)
    observe = load_jsonl(os.path.join(d, 'observe.jsonl'))
    records = load_jsonl(os.path.join(d, 'ackrec.jsonl'))
    verify = load_json(os.path.join(d, 'ackrec-verify.json'))
    facts = load_json(os.path.join(d, 'facts.json'), {})
    result = judge(args.scenario, timeline, observe, records, verify, facts)
    text = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=False)
    with open(args.out, 'w', encoding='utf-8') as fh:
        fh.write(text + '\n')
    print(json.dumps({'scenario': result['scenario'], 'verdict': result['verdict'],
                      'failed': [a['id'] for a in result['axes'] if a['pass'] is FAIL],
                      'not_judged': [a['id'] for a in result['axes'] if a['pass'] is NOT_JUDGED]},
                     ensure_ascii=False))
    return {'PASS': 0, 'RECORD': 0, 'INCONCLUSIVE': 2}.get(result['verdict'], 1)


if __name__ == '__main__':
    sys.exit(main())
