"""E-P6(b) 재초기화 축(Patroni 설계 8-3 E-P6 (b) 행, 검토 R1). patroni_axes.py 에 E-P6b 축이 없어 이 회차에서만 쓴다.

읽는 것: 회차 원시 폴더의 timeline.json(reinit_start, basebackup_cmdlines), observe.jsonl, ackrec.jsonl, logs/<재초기화 노드>.log
축:
  active_before_member      재초기화 시작부터 새 멤버 walsender 시작까지 상태 표본 ACTIVE(trading_suspended=false) 수 == 0
  active_before_cluster_sync  같은 수를 설계 문구대로 T_sync(/cluster sync_standby)까지 센 값(기록. /cluster 가 HA 루프만큼 늦다)
  acked_before_member       재초기화 시작부터 새 멤버 walsender 시작까지 끝난 acked 시도 수 == 0
  basebackup_sync_samples   멤버가 아닌 walsender(pg_basebackup 등) 가운데 sync_state 가 sync 인 표본 수 == 0
  no_slot_passed            잡은 pg_basebackup 명령줄에 --no-slot 이 있음
돌리는 법: python ep6b_axes.py <원시 폴더> <출력 json>
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'scale'))
import ack_recorder as ar  # noqa: E402
import patroni_axes as pa  # noqa: E402


def main(raw, out):
    tl = pa.load_json(os.path.join(raw, 'timeline.json'))
    observe = pa.load_jsonl(os.path.join(raw, 'observe.jsonl'))
    records = pa.load_jsonl(os.path.join(raw, 'ackrec.jsonl'))
    node = tl['injected']
    start = tl.get('reinit_start')
    new_primary = (tl.get('post') or {}).get('patroni_leader')
    ip = (tl.get('db_ips') or {}).get(new_primary)
    member_start = ar.member_walsender_start(records, ip, pa.MEMBERS, start) if start else None
    t_sync = pa.t_sync(observe, start, node)
    status = [s for s in pa.samples(observe, 'status') if s.get('code') == 200]

    def active_between(lo, hi):
        if lo is None or hi is None:
            return None
        return sum(1 for s in status if lo <= s['t'] < hi and s.get('trading_suspended') is False)

    acked = None if member_start is None else sum(
        1 for r in ar.attempts(records) if r['result'] == ar.ACKED and start <= r['t_end'] < member_start - pa.MEMBER_START_SLACK_S)
    nonmember = []
    for r in records:
        if r.get('type') != 'repl' or r.get('t', 0) < (start or 0):
            continue
        for row in r.get('rows') or []:
            if row.get('app') not in pa.MEMBERS:
                nonmember.append({'t': r['t'], 'app': row.get('app'), 'sync_state': row.get('sync_state'),
                                  'flush': row.get('flush')})
    sync_nonmember = [x for x in nonmember if x['sync_state'] == 'sync']
    cmdlines = tl.get('basebackup_cmdlines') or []
    log_path = os.path.join(raw, 'logs', node + '.log')
    log_lines = []
    if os.path.exists(log_path):
        with open(log_path, encoding='utf-8', errors='replace') as fh:
            log_lines = [ln.rstrip() for ln in fh if 'basebackup' in ln or 'bootstrap' in ln.lower() or 'replica' in ln]
    axes = [
        pa.equals('active_before_member', active_between(start, member_start), 0,
                  note='재초기화 시작부터 새 멤버 walsender(backend_start %s)까지' % member_start),
        pa.equals('acked_before_member', acked, 0),
        pa.equals('basebackup_sync_samples', len(sync_nonmember), 0,
                  note='멤버가 아닌 walsender 표본 %d(sync %d)' % (len(nonmember), len(sync_nonmember))),
        pa.axis('no_slot_passed', cmdlines, (any('--no-slot' in c for c in cmdlines) if cmdlines else None), 'contains --no-slot',
                note='실행 중 /proc 에서 잡은 pg_basebackup 명령줄'),
    ]
    verdict = 'FAIL' if any(a['pass'] is False for a in axes) else (
        'INCONCLUSIVE' if any(a['pass'] is None for a in axes) else 'PASS')
    result = {
        'scenario': 'E-P6b', 'run_id': tl.get('run_id'), 'verdict': verdict, 'node': node, 'volume': tl.get('reinit_volume'),
        'times': {'T0': tl.get('t0'), 'reinit_start': start, 'member_walsender_start': member_start, 'T_sync': t_sync,
                  'streaming_seen_by_runner': tl.get('reinit_streaming_seen')},
        'derived': {'reinit_to_member_s': pa.diff(member_start, start), 'reinit_to_sync_s': pa.diff(t_sync, start)},
        'axes': axes,
        'record': {'active_before_cluster_sync': active_between(start, t_sync),
                   'nonmember_walsender_samples': nonmember[:30], 'patroni_log_lines': log_lines[:40]},
    }
    with open(out, 'w', encoding='utf-8') as fh:
        fh.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'verdict': verdict, 'failed': [a['id'] for a in axes if a['pass'] is False],
                      'not_judged': [a['id'] for a in axes if a['pass'] is None]}, ensure_ascii=False))
    return 0 if verdict == 'PASS' else (2 if verdict == 'INCONCLUSIVE' else 1)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1], sys.argv[2]))
