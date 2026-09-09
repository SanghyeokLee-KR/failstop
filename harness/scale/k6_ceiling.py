"""k6 로 접수 천장을 잰다. 정본 13.5 한계 19 의 후속.

왜 봇이 아니라 k6 인가. 2026-09-09 실측에서 봇이 상한이었다. 봇은 앱 안에서 도는
스레드라 앱이 느려지면 봇도 같이 느려진다. 닫힌 루프라 시스템이 밀리면 주입도 밀린다.
자기 자신을 자로 잴 수 없다.

k6 는 시스템 완전 외부에서 개방 루프(constant-arrival-rate)로 주입한다. 시스템이
느려져도 요청은 계획대로 나간다. 그래야 천장이 보인다.

방법. 요청률을 사다리로 올리며 각 구간에서 실제 접수율과 거절률과 지연을 잰다.
접수율이 요청률을 따라오기를 멈추는 지점이 천장이다.

봇은 켜 둔다. 상시면이 봇 기저 트래픽 위에서 도므로 그 조건에서 재야 실제 천장이다.
기저를 빼고 싶으면 --bots-ops 0 을 준다.

돌리는 법:
  python harness/scale/k6_ceiling.py --rungs 20,40,60,80,120,160 --duration 90
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

IMAGE = 'grafana/k6:1.4.0'
NET = 'exchange-chaos_chaos-net'


def run_k6(rate, duration, run_id):
    """calibrate.js 를 한 구간 돌린다. 컨테이너는 카오스 망 안에 붙여 로드밸런서를 친다."""
    args = [
        'docker', 'run', '--rm', '--network', NET,
        '-v', str(ROOT / 'harness/load') + ':/load:ro',
        '-e', 'BASE_OPS=%d' % rate,
        '-e', 'CAL_DURATION=%ds' % duration,
        '-e', 'BASE_URL=http://chaos-lb:8080',
        '-e', 'K6_KEYS=/load/secrets/k6_keys.json',
        # 요약 파일은 쓰기 가능한 곳으로 뺀다. /load 는 읽기 전용 마운트다.
        '-e', 'CAL_OUT=/tmp/calibration.json',
        '-e', 'RUN_ID=' + run_id,
        IMAGE, 'run', '--quiet', '--no-color', '/load/calibrate.js',
    ]
    # Popen 이다. run 이 아니다. 주행이 끝난 뒤 재면 1분 rate 창에 조용한 구간이 섞여
    # 기저보다 낮은 값이 나온다(2026-09-09 실측: 기저 20.8/s 인데 주행 후 12/s).
    return subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def duration_fill(duration):
    """rate 창(60초)이 주행으로 가득 차는 시점. 구간이 짧으면 가능한 만큼만 기다린다."""
    return min(65, max(20, duration - 10))


def measure():
    """서버가 본 값을 쓴다. k6 가 본 값은 클라이언트 쪽이라 거절도 성공으로 셀 수 있다."""
    return {
        '접수/s': q('sum(rate(exchange_order_latency_seconds_count[1m]))'),
        '체결/s': q('max(exchange:trades_per_second:rate1m)'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total[1m]))'),
        '주문거절/s': q('sum(rate(exchange_orders_rejected_total[1m]))'),
        'p95(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p95_1m)')),
        'p99(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p99_1m)')),
        'api1 CPU%': q('max(container_cpu_percent{name="chaos-api-1"})'),
        'api2 CPU%': q('max(container_cpu_percent{name="chaos-api-2"})'),
        'DB CPU%': q('max(container_cpu_percent{name="chaos-pg"})'),
        'k6 CPU%': q('max(container_cpu_percent{name=~"k6.*"})'),
        '풀 활성': q('sum(hikaricp_connections_active)'),
        '풀 대기': q('sum(hikaricp_connections_pending)'),
        '시퀀서 큐': q('max(exchange_sequencer_queue_size)'),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rungs', default='20,40,60,80,120,160')
    ap.add_argument('--duration', type=int, default=90, help='구간당 초. 1분 rate 창보다 커야 한다')
    ap.add_argument('--bots-ops', type=int, default=20, help='봇 기저 트래픽. 0 이면 끈다')
    ap.add_argument('--out', default=str(ROOT / 'harness/evidence/k6-ceiling.json'))
    a = ap.parse_args()

    rungs = [int(x) for x in a.rungs.split(',')]
    print('사다리 %s, 구간당 %ds, 봇 기저 %d OPS' % (rungs, a.duration, a.bots_ops), flush=True)
    print('기저 설정:', admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': a.bots_ops})[0], flush=True)
    time.sleep(30)
    base = measure()
    print('기저만: 접수 %s/s' % round(base['접수/s'] or 0, 2), flush=True)

    rows = []
    for r in rungs:
        run_id = 'k6ceil-%d' % r
        print('\n[요청 %d/s] k6 %ds' % (r, a.duration), flush=True)
        proc = run_k6(r, a.duration, run_id)
        # 1분 rate 창이 주행으로 가득 찬 뒤에 잰다. 그전에 재면 이전 구간이 섞인다.
        time.sleep(min(duration_fill(a.duration), a.duration - 5))
        s = measure()
        out = ''
        try:
            out = proc.communicate(timeout=a.duration + 120)[0] or ''
        except subprocess.TimeoutExpired:
            proc.kill()
            out = 'timeout'
        code = proc.returncode
        s['_요청'] = r
        s['_k6_exit'] = code
        rows.append(s)
        for k in ('접수/s', '수용거절/s', 'p99(ms)', 'api1 CPU%', 'DB CPU%'):
            print('  %-12s %s' % (k, '-' if s[k] is None else round(s[k], 2)), flush=True)
        if code != 0:
            tail = [ln for ln in out.splitlines() if ln.strip()][-3:]
            print('  k6 종료코드 %d: %s' % (code, ' | '.join(tail)[:220]), flush=True)
        time.sleep(20)

    Path(a.out).write_text(json.dumps(
        {'rungs': rungs, 'duration': a.duration, 'bots_ops': a.bots_ops,
         'base': base, 'rows': rows}, ensure_ascii=False, indent=1), encoding='utf-8')

    keys = ['접수/s', '체결/s', '수용거절/s', '주문거절/s', 'p95(ms)', 'p99(ms)',
            'api1 CPU%', 'DB CPU%', '풀 활성', '시퀀서 큐']
    print('\n' + '=' * 108)
    print('%-8s' % '요청/s' + ''.join('%11s' % k for k in keys))
    for r in rows:
        line = '%-8d' % r['_요청']
        for k in keys:
            v = r[k]
            line += '%11s' % ('-' if v is None else round(v, 1))
        print(line)
    print('\n판정. 접수/s 가 요청/s 를 따라오기를 멈추는 구간이 천장이다.')
    print('그 구간에서 거절이 오르면 수용 게이트가, CPU 가 100 에 붙으면 그 계층이 원인이다.')
    print('결과 파일: ' + a.out)


if __name__ == '__main__':
    sys.exit(main())
