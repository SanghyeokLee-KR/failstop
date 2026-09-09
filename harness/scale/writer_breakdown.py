"""직렬 구간을 셋으로 갈라 재고 데이터베이스 규격을 함께 올린다. 정본 13.5 한계 29.

배경. 상한 후보에서 수용 정원과 예비 슬롯과 커넥션 풀과 저널 기록과 커밋 내구성이
전부 빠졌다. 남은 것이 매칭 적용 구간인데 타이머가 없어 잴 수 없었다. 2026-09-09 에
`SingleWriterLoop` 에 누적기를 넣어 적용과 sink 와 훅을 갈라 세게 했다.

같이 답할 것. 데이터베이스를 키우면 처리량이 오르는가. 종전 격자는 부하가 발생기에
묶여 있어 판정하지 못했다. 이번에는 개방 루프로 밀면서 규격을 바꾼다.

직렬 점유율의 뜻. 누적 나노초의 초당 증가분이 곧 그 구간이 1초 중 몇 초를 쓰는지다.
1.0 이면 그 구간만으로 꽉 찬다. 셋의 합이 1.0 에 못 미치면 직렬 구간 밖에 상한이 있다.

돌리는 법:
  python harness/scale/writer_breakdown.py --rate 150 --duration 120
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import q, admin_call  # noqa: E402
from k6_ceiling import run_k6, duration_fill  # noqa: E402

CHAOS = str(ROOT / 'harness/docker-compose.chaos.yml')
SCALE = str(ROOT / 'harness/docker-compose.scale.yml')

# 규격 칸. DB 의 CPU 와 메모리를 함께 올린다. 사용자 요청이 "cpu ram 을 늘린 상태" 다.
CELLS = [
    ('작음', '1.0', '1g'),
    ('기준', '2.0', '2g'),
    ('큼', '4.0', '4g'),
    ('아주큼', '8.0', '8g'),
]


def compose_up(cpus, mem):
    env = dict(os.environ)
    env.update({'SCALE_PG_CPUS': cpus, 'SCALE_PG_MEM': mem, 'SCALE_POOL': '32'})
    # 한 번에 다 세우면 depends_on 대기 중 재생성된 컨테이너를 compose 가 실패로 읽는다
    # (2026-09-09 실측: exited 137 로 보고하나 OOMKilled=false 이고 실제로는 정상 기동).
    # DB 를 먼저 세워 healthy 를 확인한 뒤 앱을 올리면 그 경합이 없다.
    base = ['docker', 'compose', '-f', CHAOS, '-f', SCALE,
            '--env-file', str(ROOT / 'harness/.env.chaos')]
    kw = dict(cwd=str(ROOT / 'harness'), env=env, capture_output=True,
              text=True, timeout=900)
    code = subprocess.run(base + ['up', '-d', '--force-recreate', 'chaos-pg'], **kw).returncode
    if code != 0:
        return code
    time.sleep(20)
    for attempt in range(3):
        code = subprocess.run(
            base + ['up', '-d', '--force-recreate', 'chaos-api-1', 'chaos-api-2'], **kw).returncode
        if code == 0:
            return 0
        print('  앱 기동 재시도 %d/3' % (attempt + 1), flush=True)
        time.sleep(20)
    return code


def wait_ready(timeout=240):
    import urllib.request
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(
                    'http://localhost:18080/actuator/health/readiness', timeout=5) as r:
                if b'UP' in r.read():
                    return True
        except Exception:
            pass
        time.sleep(5)
    return False


def sample():
    # 누적 나노초의 초당 증가분을 초 단위로 바꾼다. 그것이 직렬 점유율이다.
    def share(name):
        v = q('sum(rate(exchange_writer_%s_nanos_total[1m]))' % name)
        return (v / 1e9) if v is not None else None

    ap, sk, hk = share('apply'), share('sink'), share('hook')
    total = None
    if None not in (ap, sk, hk):
        total = ap + sk + hk
    n = q('sum(rate(exchange_writer_apply_count_total[1m]))')
    # 접수 임계구역. 대기가 크면 경합이, 보유가 크면 그 안의 작업이 상한이다.
    lw = q('sum(rate(exchange_submit_lock_wait_nanos_total[1m]))')
    lh = q('sum(rate(exchange_submit_lock_hold_nanos_total[1m]))')
    sc = q('sum(rate(exchange_submit_count_total[1m]))')
    lw = (lw / 1e9) if lw is not None else None
    lh = (lh / 1e9) if lh is not None else None
    return {
        '적용 점유': ap,
        'sink 점유': sk,
        '훅 점유': hk,
        '직렬 합계': total,
        '적용 호출/s': n,
        '락 대기 점유': lw,
        '락 보유 점유': lh,
        '접수 시도/s': sc,
        '락 대기 1회(us)': (lw / sc * 1e6) if (lw and sc) else None,
        '락 보유 1회(us)': (lh / sc * 1e6) if (lh and sc) else None,
        '적용 1회(us)': (ap / n * 1e6) if (ap and n) else None,
        'sink 1회(us)': (sk / n * 1e6) if (sk and n) else None,
        '접수/s': q('sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))'),
        '체결/s': q('sum(rate(exchange_trades_total[1m]))'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total[1m]))'),
        'p99(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p99_1m)')),
        '시퀀서 큐': q('max(exchange_sequencer_queue_size)'),
        'DB CPU%': q('max(container_cpu_percent{name="chaos-pg"})'),
        'api1 CPU%': q('max(container_cpu_percent{name="chaos-api-1"})'),
    }


def main():
    ap_ = argparse.ArgumentParser()
    ap_.add_argument('--rate', type=int, default=150)
    ap_.add_argument('--duration', type=int, default=120)
    ap_.add_argument('--bots-ops', type=int, default=20)
    ap_.add_argument('--cells', default=None)
    ap_.add_argument('--out', default=str(ROOT / 'harness/evidence/writer-breakdown.json'))
    a = ap_.parse_args()

    cells = [c for c in CELLS if not a.cells or c[0] in a.cells.split(',')]
    out = {'rate': a.rate, 'duration': a.duration, 'cells': []}

    for name, cpus, mem in cells:
        print('\n' + '#' * 66, flush=True)
        print('[%s] DB cpus %s / mem %s' % (name, cpus, mem), flush=True)
        if compose_up(cpus, mem) != 0:
            print('  기동 실패', flush=True)
            out['cells'].append({'cell': name, 'error': 'compose up 실패'})
            continue
        if not wait_ready():
            print('  readiness 미도달', flush=True)
            out['cells'].append({'cell': name, 'error': 'readiness 미도달'})
            continue
        admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': a.bots_ops})
        time.sleep(40)

        proc = run_k6(a.rate, a.duration, 'wb-%s' % name)
        time.sleep(min(duration_fill(a.duration), a.duration - 5))
        s = sample()
        s['_cell'] = name
        s['_cpus'] = cpus
        s['_mem'] = mem
        try:
            proc.communicate(timeout=a.duration + 120)
        except subprocess.TimeoutExpired:
            proc.kill()
        out['cells'].append(s)
        for k in ('접수/s', '체결/s', '락 대기 점유', '락 보유 점유', '락 보유 1회(us)', 'sink 1회(us)'):
            v = s.get(k)
            print('  %-14s %s' % (k, '-' if v is None else round(v, 3)), flush=True)
        time.sleep(20)

    Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')

    keys = ['접수/s', '체결/s', 'p99(ms)', '락 대기 점유', '락 보유 점유', '직렬 합계',
            '락 대기 1회(us)', '락 보유 1회(us)', 'sink 1회(us)', '시퀀서 큐', 'DB CPU%']
    print('\n' + '=' * 118)
    print('%-9s%-7s' % ('규격', 'cpus') + ''.join('%11s' % k[:10] for k in keys))
    for c in out['cells']:
        if '_cell' not in c:
            continue
        line = '%-9s%-7s' % (c['_cell'], c['_cpus'])
        for k in keys:
            v = c.get(k)
            line += '%11s' % ('-' if v is None else round(v, 2))
        print(line)
    print('\n판정. 직렬 합계가 1.0 에 가까우면 직렬 구간이 상한이다.')
    print('낮은데 처리량이 낮으면 상한은 그 밖이다. 규격을 올려 처리량이 오르면 DB 가 답이다.')
    print('결과 파일: ' + a.out)


if __name__ == '__main__':
    sys.exit(main())
