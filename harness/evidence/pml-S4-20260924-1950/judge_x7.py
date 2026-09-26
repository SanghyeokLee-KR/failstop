"""X7 처리량 기록(마켓분할_리더_S4_설계 7-2 X7, 판정 아님). run-s4-gate.ps1 -Phase X7 이 남긴 칸 파일을 읽어 손실과 잡음을 낸다.

칸 값은 roundtrip_probe 의 '접수/s'(k6 주행 중 1분 창의 201/s)다. S3 관문 X5 (i)과 같은 값이다.
규칙: 400 비율이 1퍼센트를 넘는 칸은 NOT RUN. 잡음 n = |S3a - S3b| / 평균(S3)(세 칸 이상이면 범위/중앙값)이 0.10 을 넘으면 칸을
더 돌아야 한다. 손실 L = 1 - 중심(S4)/중심(S3)이다(두 칸이면 평균, 세 칸 이상이면 중앙값). L 이 0.10 을 넘으면 원인을 구현 결과
절에 적는다. 거부는 노드별 상태 계수(리더·팔로워), 접수 경합 계수(bucket market 의 429 와 409), 수용 거절(bucket node),
LB 로그의 5xx 를 칸마다 남긴다. 노드 계수는 칸마다 새로 세운 스택의 누계라 예열 구간을 포함한다.

사용: python judge_x7.py X7 --out X7/judge.json
"""
import argparse
import json
import re
import statistics
from pathlib import Path


def node_statuses(cell):
    """노드별 POST /api/v1/orders 상태 누계와 경합·수용 거절 누계."""
    out = {}
    for port, m in (cell.get('nodeMetrics') or {}).items():
        if not isinstance(m, dict) or not m.get('ok'):
            out[port] = {'error': (m or {}).get('error') if isinstance(m, dict) else 'none'}
            continue
        statuses = {k[len('orders_post_'):]: v for k, v in m.items() if k.startswith('orders_post_')}
        contention = {k.split('{', 1)[1].rstrip('}'): v for k, v in m.items()
                      if k.startswith('exchange_order_intake_contention_total{')}
        admission = {k: v for k, v in m.items() if k.startswith('exchange_admission_rejected_total')}
        out[port] = {'statuses': statuses, 'contention': contention, 'admission': admission}
    return out


def ratio400(nodes):
    tot = bad = 0.0
    for n in nodes.values():
        for st, v in (n.get('statuses') or {}).items():
            tot += v
            if st == '400':
                bad += v
    return (bad / tot) if tot else None


def load(dirpath, rate):
    want = re.compile(r'^(S[34][a-z])-%d$' % rate)
    out = {}
    for f in sorted(Path(dirpath).glob('S*-%d.json' % rate)):
        m = want.match(f.stem)
        if not m:
            continue
        cell = m.group(1)
        probe = json.loads(f.read_text(encoding='utf-8'))
        meta = Path(dirpath) / ('cell-%s.json' % f.stem)
        info = json.loads(meta.read_text(encoding='utf-8-sig')) if meta.exists() else {}
        nodes = node_statuses(info)
        out[cell] = {
            'file': f.name, 'rate': probe.get('rate'), 'value': probe.get('접수/s'), 'k6_rc': probe.get('k6_rc'),
            'probe_rc': info.get('probeRc'), 'image': info.get('imageId'),
            'status400_ratio': ratio400(nodes),
            'serial_ms_per_cmd': probe.get('1건당 직렬(ms)'),
            'lock_hold_ms': probe.get('락 보유 1회(ms)'),
            'lock_wait_ms': probe.get('락 대기 1회(ms)'),
            'append_ms': probe.get('append 1회(ms)'),
            'active_connections': probe.get('활성 커넥션'),
            'admission_reject_per_s': probe.get('수용거절/s'),
            'deadlocks': probe.get('deadlocks 증가'),
            'api1_cpu': probe.get('api1 CPU%'), 'db_cpu': probe.get('DB CPU%'),
            'nodes': nodes, 'lb': info.get('lb'), 'host': info.get('host'),
            'summary_ok': (info.get('summary') or {}).get('ok'),
            'summary_notPass': (info.get('summary') or {}).get('notPass'),
            'drained': info.get('drained'),
        }
    return out


def center(values):
    return statistics.mean(values) if len(values) == 2 else statistics.median(values)


def judge(cells):
    notrun = [k for k, v in cells.items() if v['value'] is None or (v['status400_ratio'] or 0) > 0.01]
    res = {'cells': cells, 'not_run_cells': notrun}
    s3 = [v['value'] for k, v in sorted(cells.items()) if k.startswith('S3') and k not in notrun]
    s4 = [v['value'] for k, v in sorted(cells.items()) if k.startswith('S4') and k not in notrun]
    if len(s3) < 2 or len(s4) < 2:
        res['record'] = 'NOT RUN'
        res['reason'] = '유효 칸이 이미지마다 둘 이상이어야 한다'
        return res
    c3, c4 = center(s3), center(s4)
    noise = (abs(s3[0] - s3[1]) / c3) if len(s3) == 2 else ((max(s3) - min(s3)) / c3)
    loss = 1 - c4 / c3
    res.update({'s3_values': s3, 's4_values': s4, 's3_center': c3, 's4_center': c4, 'noise': noise, 'loss': loss,
                'noise_over_0_10': noise > 0.10, 'loss_over_0_10': loss > 0.10,
                's4_range_over_center': (max(s4) - min(s4)) / c4})
    res['record'] = '칸 추가 필요(잡음 > 0.10)' if noise > 0.10 else ('손실 > 0.10' if loss > 0.10 else '기록')
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir')
    ap.add_argument('--rate', type=int, default=600)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    res = judge(load(a.dir, a.rate))
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    brief = {k: res.get(k) for k in ('record', 'not_run_cells', 's3_values', 's4_values', 'noise', 'loss')}
    print(json.dumps(brief, ensure_ascii=False))


if __name__ == '__main__':
    main()
