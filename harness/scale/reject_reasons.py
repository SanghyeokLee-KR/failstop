"""주문 거부 사유 분포를 잰다. 정본 13.3 한계 42.

배경. 12마켓 부하에서 주문 요청의 34퍼센트가 201도 429도 아닌 응답이었다. 사유를 가르지
못해 가설로만 남겼다. exchange.orders.rejected 가 reason 태그로 이미 세고 있으므로
그 분포를 읽으면 된다.

함께 본다. 부하 계정의 자산별 잔고다. 기준자산 시드 고갈이 가설이므로 주행 전후의 잔고를
비교하면 바로 갈린다.

돌리는 법:
  python harness/scale/reject_reasons.py --rate 240 --out out.json
"""
import argparse
import io
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import q, PROM  # noqa: E402
from k6_ceiling import run_k6, duration_fill  # noqa: E402
import clean_suite as cs  # noqa: E402

ENV = {'SCALE_POOL': '32', 'SCALE_ORDER_INFLIGHT': '0', 'SCALE_QUERY_INFLIGHT': '0',
       'SCALE_REQUERY_INFLIGHT': '0', 'SCALE_PG_CPUS': '4.0', 'SCALE_PG_MEM': '4g'}


def psql(sql):
    p = subprocess.run(['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange',
                        '-t', '-A', '-F', '|', '-c', sql],
                       capture_output=True, text=True, timeout=60)
    return p.stdout.strip()


def load_balances():
    """부하 계정의 자산별 가용 잔고. 이메일은 LoadKeySeeder 가 k6-load-N@demo.local 로 만든다."""
    rows = psql("""
        SELECT a.symbol, count(*) AS accounts,
               round(min(b.available)::numeric, 4), round(avg(b.available)::numeric, 4)
        FROM user_balances b
        JOIN assets a ON a.id = b.asset_id
        JOIN users u ON u.id = b.user_id
        WHERE u.email LIKE 'k6-load-%'
        GROUP BY a.symbol ORDER BY a.symbol;
    """)
    out = {}
    for line in rows.splitlines():
        parts = line.split('|')
        if len(parts) == 4:
            out[parts[0]] = {'계정': int(parts[1]), '최소': parts[2], '평균': parts[3]}
    return out


def reasons():
    """사유별 초당 거부 수. load_ceiling.q 는 스칼라만 돌려주므로 여기서 직접 읽는다."""
    import urllib.parse
    import urllib.request
    url = PROM + '/api/v1/query?' + urllib.parse.urlencode(
        {'query': 'sum by (reason) (rate(exchange_orders_rejected_total[1m]))'})
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            res = json.load(r)['data']['result']
    except Exception:
        return {}
    return {m['metric'].get('reason', '(무)'): round(float(m['value'][1]), 3) for m in res}


def main():
    a_ = argparse.ArgumentParser()
    a_.add_argument('--rate', type=int, default=240)
    a_.add_argument('--duration', type=int, default=130)
    a_.add_argument('--bots-ops', type=int, default=20)
    a_.add_argument('--warmup', type=int, default=240)
    a_.add_argument('--out', required=True)
    a = a_.parse_args()

    if not cs.reset_stack(ENV):
        print('초기화 실패: %s' % cs.LAST_ERR[0][-300:], flush=True)
        return 1
    if cs.reissue_keys() == 0:
        print('키 재발급 실패', flush=True)
        return 1
    hit, want = cs.keys_match_db()
    if hit < want:
        print('키 불일치 %d/%d' % (hit, want), flush=True)
        return 1
    before = load_balances()
    print('주행 전 부하 계정 잔고', flush=True)
    for k, v in before.items():
        print('  %-6s 계정 %d 최소 %s 평균 %s' % (k, v['계정'], v['최소'], v['평균']), flush=True)

    cs.set_bot_ops(a.bots_ops)
    if not cs.wait_bots_steady(a.warmup):
        print('봇 안정화 실패', flush=True)
        return 1

    proc = run_k6(a.rate, a.duration, 'rej%d' % a.rate,
                  log_path=str(ROOT / 'harness/evidence/k6-reject.log'))
    time.sleep(min(duration_fill(a.duration), a.duration - 5))
    s = cs.measure()
    s['거부 사유'] = reasons()
    try:
        proc.communicate(timeout=a.duration + 120)
    except subprocess.TimeoutExpired:
        proc.kill()
    s['k6_rc'] = proc.returncode
    s['잔고 전'] = before
    s['잔고 후'] = load_balances()
    s['rate'] = a.rate
    io.open(a.out, 'w', encoding='utf-8').write(json.dumps(s, ensure_ascii=False, indent=2))

    print('\n거부 사유', flush=True)
    print('  %s' % s['거부 사유'], flush=True)
    print('\n주행 후 부하 계정 잔고', flush=True)
    for k, v in s['잔고 후'].items():
        print('  %-6s 계정 %d 최소 %s 평균 %s' % (k, v['계정'], v['최소'], v['평균']), flush=True)
    for k in ('접수/s', '비201/s', '수용거절/s', '전체요청/s'):
        print('  %-10s %s' % (k, s.get(k)), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
