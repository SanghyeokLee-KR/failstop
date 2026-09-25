"""이 세션 회차들의 판정 파일에서 판정표 한 줄씩을 뽑는다. 사용: python summarize.py <verdict.json> ..."""
import json
import sys

KEYS = ('db_failover_s', 'read_recovery_s', 'suspend_detect_s', 'resume_delay_s', 'trade_resume_delay_s', 'haproxy_down_s')


def fmt(v):
    return '-' if v is None else (('%.2f' % v) if isinstance(v, float) else str(v))


for path in sys.argv[1:]:
    with open(path, encoding='utf-8') as fh:
        v = json.load(fh)
    fails = [a['id'] for a in v['axes'] if a['pass'] is False]
    nj = [a['id'] for a in v['axes'] if a['pass'] is None]
    ack = (v.get('ackrec') or {}).get('verify') or {}
    wd = (v.get('extra') or {}).get('withdraw')
    if wd:
        v['extra']['withdraw'] = {'requests': wd.get('requests'), 'uncertain_keys': wd.get('uncertain_keys'),
                                  'resent_same_key': wd.get('resent_same_key'),
                                  'pending_at_end': len(wd.get('pending_at_end') or [])}
    print(json.dumps({
        'run': v['run_id'], 'scenario': v['scenario'], 'verdict': v['verdict'],
        'derived': {k: fmt((v.get('derived') or {}).get(k)) for k in KEYS},
        'axes_pass': sum(1 for a in v['axes'] if a['pass'] is True), 'axes_total': len(v['axes']),
        'fail': fails, 'not_judged': nj,
        'invalid': (v.get('invalid') or {}).get('reasons'),
        'ack': {k: ack.get(k) for k in ('acked', 'unknown', 'missing_count')},
        'not_acked': (v.get('ackrec') or {}).get('not_acked_reasons'),
        'extra': {k: v['extra'][k] for k in v.get('extra', {}) if k in (
            'acked_between_promote_and_cluster_sync', 'suspend_before_sync_loss_s', 'v47', 'definitive_deferred',
            'app_leader_changed', 'withdraw')},
    }, ensure_ascii=False))
