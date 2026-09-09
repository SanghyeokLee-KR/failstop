"""단일 라이터 직렬 구간의 지배분을 찾는다. 정본 13.5 한계 29.

문제. 수용 정원을 네 배 벌려도 통과량이 같고 시퀀서 큐가 같은 깊이로 쌓인다
(admission-matrix-20260909). 상한이 게이트 뒤에 있고 단일 라이터가 유력한데, 그 직렬
구간 안에서 무엇이 시간을 쓰는지 모른다.

구간의 구성. `EngineBridge.submit` 이 ReentrantLock 을 잡고 그 안에서 저널 append 와
커밋을 한 다음 루프에 넘긴다. 곧 직렬 구간 = 저널 왕복 + 매칭 apply + 부대 비용이다.

재는 법. 저널 append 는 이미 타이머가 있다(`exchange_journal_append_seconds`). 평균
소요와 초당 호출을 곱하면 직렬 구간에서 저널이 차지하는 비율이 나온다. 그 비율이
낮은데 처리량이 낮으면 저널이 아니라 다른 것이 지배한다.

교차 확인. `synchronous_commit` 을 off 로 두면 커밋이 fsync 를 기다리지 않는다. 처리량이
오르면 상한은 커밋 내구성이고 안 오르면 매칭이나 그 밖이다.

경고. off 는 내구성을 끄는 설정이라 RPO=0 계약을 어긴다. 측정 전용이고 이 스크립트가
끝나면서 원복한다. 중단되면 손으로 되돌려야 한다.

돌리는 법:
  python harness/scale/writer_serial.py --rate 150 --duration 120
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import q, admin_call  # noqa: E402
from k6_ceiling import run_k6, duration_fill  # noqa: E402

PG = 'chaos-pg'


def psql(sql):
    p = subprocess.run(['docker', 'exec', PG, 'psql', '-U', 'exchange', '-d', 'postgres',
                        '-t', '-A', '-c', sql], capture_output=True, text=True, timeout=60)
    return p.stdout.strip()


def set_sync(value):
    psql("ALTER SYSTEM SET synchronous_commit = '%s';" % value)
    psql('SELECT pg_reload_conf();')
    time.sleep(3)
    return psql('SHOW synchronous_commit;')


def sample():
    n = q('sum(rate(exchange_journal_append_seconds_count[1m]))')
    avg = q('rate(exchange_journal_append_seconds_sum[1m])/rate(exchange_journal_append_seconds_count[1m])')
    # 직렬 점유율. 평균 소요 x 초당 호출이 1.0 이면 그 구간만으로 꽉 찬다.
    share = (avg * n * 100) if (avg and n) else None
    return {
        '저널 평균(ms)': (avg * 1000) if avg else None,
        '저널 호출/s': n,
        '저널 직렬 점유율(%)': share,
        '저널 이론상한/s': (1.0 / avg) if avg else None,
        '접수/s': q('sum(rate(exchange_order_latency_seconds_count[1m]))'),
        '체결/s': q('max(exchange:trades_per_second:rate1m)'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total[1m]))'),
        'p99(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p99_1m)')),
        '시퀀서 큐': q('max(exchange_sequencer_queue_size)'),
        'api1 CPU%': q('max(container_cpu_percent{name="chaos-api-1"})'),
        'DB CPU%': q('max(container_cpu_percent{name="chaos-pg"})'),
    }


def phase(label, rate, duration):
    print('\n[%s] k6 %d/s, %ds' % (label, rate, duration), flush=True)
    proc = run_k6(rate, duration, 'ws-%s-%d' % (label, rate))
    time.sleep(min(duration_fill(duration), duration - 5))
    s = sample()
    s['_phase'] = label
    try:
        proc.communicate(timeout=duration + 120)
    except subprocess.TimeoutExpired:
        proc.kill()
    for k in ('접수/s', '저널 평균(ms)', '저널 직렬 점유율(%)', '저널 이론상한/s', '시퀀서 큐'):
        v = s.get(k)
        print('  %-20s %s' % (k, '-' if v is None else round(v, 2)), flush=True)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rate', type=int, default=150)
    ap.add_argument('--duration', type=int, default=120)
    ap.add_argument('--bots-ops', type=int, default=20)
    ap.add_argument('--out', default=str(ROOT / 'harness/evidence/writer-serial.json'))
    a = ap.parse_args()

    print('시작 시점 synchronous_commit =', psql('SHOW synchronous_commit;'), flush=True)
    admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': a.bots_ops})
    time.sleep(30)

    rows = []
    try:
        rows.append(phase('sync-on', a.rate, a.duration))
        time.sleep(20)
        print('\nsynchronous_commit -> off (측정 전용, 내구성 해제)', flush=True)
        print('  적용 확인:', set_sync('off'), flush=True)
        rows.append(phase('sync-off', a.rate, a.duration))
    finally:
        print('\nsynchronous_commit 원복:', set_sync('on'), flush=True)

    Path(a.out).write_text(json.dumps({'rate': a.rate, 'duration': a.duration, 'rows': rows},
                                      ensure_ascii=False, indent=1), encoding='utf-8')

    keys = ['접수/s', '체결/s', 'p99(ms)', '저널 평균(ms)', '저널 호출/s',
            '저널 직렬 점유율(%)', '저널 이론상한/s', '시퀀서 큐', 'api1 CPU%', 'DB CPU%']
    print('\n' + '=' * 100)
    print('%-10s' % '구간' + ''.join('%13s' % k[:12] for k in keys))
    for r in rows:
        line = '%-10s' % r['_phase']
        for k in keys:
            v = r.get(k)
            line += '%13s' % ('-' if v is None else round(v, 1))
        print(line)
    print('\n판정. 저널 직렬 점유율이 100 에 가까우면 저널이 상한이다.')
    print('낮은데 접수가 낮으면 저널이 아니다. sync-off 에서 접수가 오르면 커밋 내구성이 원인이다.')
    print('결과 파일: ' + a.out)


if __name__ == '__main__':
    sys.exit(main())
