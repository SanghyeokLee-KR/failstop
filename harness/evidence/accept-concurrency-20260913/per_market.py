"""양성 축. 주행 식별자별로 12마켓 각각의 접수(command_journal NEW 행)와 주문 행 수를 센다.

k6 client_order_id 는 k6-<RUN_ID>-<vu>-<iter> 다(order_flow.js). 접수 201 은 저널 커밋 뒤에
나가므로 저널 NEW 행이 접수의 데이터베이스 쪽 근거다.

돌리는 법:
  python per_market.py --run-ids rtB0240 --out <file>
"""
import argparse
import json
import subprocess
import sys

MARKETS = ('KRW-BTC', 'KRW-ETH', 'KRW-SOL', 'KRW-XRP', 'KRW-DOGE', 'KRW-ADA',
           'KRW-TRX', 'KRW-LINK', 'KRW-DOT', 'KRW-MATIC', 'KRW-AVAX', 'KRW-ATOM')


def psql(sql):
    p = subprocess.run(['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange',
                        '-t', '-A', '-F', '|', '-c', sql], capture_output=True, text=True, timeout=300)
    if p.returncode != 0:
        raise RuntimeError((p.stderr or p.stdout)[-300:])
    return p.stdout.strip()


def count(table, run_id):
    raw = psql(
        "SELECT m.symbol, count(*) FROM %s t JOIN markets m ON m.id = t.market_id "
        "WHERE t.client_order_id LIKE 'k6-%s-%%' %s GROUP BY m.symbol"
        % (table, run_id, "AND t.command_type = 'NEW'" if table == 'command_journal' else ''))
    got = {}
    for line in raw.splitlines():
        sym, n = line.split('|')
        got[sym] = int(n)
    return {mk: got.get(mk, 0) for mk in MARKETS}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--run-ids', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    res = {}
    for rid in a.run_ids.split(','):
        cj = count('command_journal', rid)
        od = count('orders', rid)
        res[rid] = {'journal_new': cj, 'orders': od,
                    'journal_new_total': sum(cj.values()),
                    'markets_with_accept': sum(1 for v in cj.values() if v > 0),
                    'all_12_positive': all(v > 0 for v in cj.values())}
        print('%s 저널 NEW 합 %d, 접수>0 마켓 %d/12' % (rid, res[rid]['journal_new_total'],
                                                   res[rid]['markets_with_accept']), flush=True)
    with open(a.out, 'w', encoding='utf-8') as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    return 0


if __name__ == '__main__':
    sys.exit(main())
