"""명령 하나가 데이터베이스를 몇 번 왕복하는지 재고 직렬 구간을 함께 가른다.

배경. 정본 5.7.5 가 다음 상한을 왕복 횟수로 특정했다. 명령 하나에 트랜잭션 19.34개이고
직렬 시간 21.36밀리초였다. 왕복을 줄이는 판을 한 뒤 그 둘이 실제로 줄었는지 본다.

무엇을 재는가.
  직렬 점유    exchange_writer_{apply,sink,hook}_nanos_total 의 초당 증가분
  명령당 시간  같은 값을 적용 호출 수로 나눈 것
  명령당 트랜잭션  pg_stat_database_xact_commit 의 초당 증가분을 접수로 나눈 것
  표별 쓰기    pg_stat_user_tables 의 삽입과 갱신을 접수로 나눈 것

한 칸만 돈다. 규격은 4코어로 고정한다. 정본 5.7.6 이 그 지점을 포화로 판정했다.

돌리는 법:
  python harness/scale/roundtrip_probe.py --label after --rate 240
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
from load_ceiling import q  # noqa: E402
from k6_ceiling import run_k6, duration_fill  # noqa: E402
import clean_suite as cs  # noqa: E402

ENV = {'SCALE_POOL': '32', 'SCALE_ORDER_INFLIGHT': '0', 'SCALE_QUERY_INFLIGHT': '0',
       'SCALE_REQUERY_INFLIGHT': '0', 'SCALE_PG_CPUS': '4.0', 'SCALE_PG_MEM': '4g'}

TABLES = ('ledger_entries', 'balances', 'ledger_transactions', 'orders',
          'event_journal', 'command_journal', 'event_emission_registry',
          'trades', 'position_cost', 'market_journal_counters', 'journal_counters')


def sample():
    def ns(name):
        v = q('sum(rate(exchange_writer_%s_nanos_total[1m]))' % name)
        return (v / 1e9) if v is not None else None

    ap, sk, hk = ns('apply'), ns('sink'), ns('hook')
    n = q('sum(rate(exchange_writer_apply_count_total[1m]))')
    # 접수 임계구역. 마켓별 샤딩이 건드리지 않은 전역 직렬 구간이다. 모든 마켓의 모든
    # 접수가 EngineBridge 의 ReentrantLock 하나를 지나고 그 안에서 저널 append 와 커밋을
    # 한다. 보유 점유가 1.0 에 가까우면 그 락이 상한이다. 라이터 점유와 달리 마켓 수로
    # 나뉘지 않으므로 물리 상한이 12.0 이 아니라 1.0 이다.
    lw = q('sum(rate(exchange_submit_lock_wait_nanos_total[1m]))')
    lh = q('sum(rate(exchange_submit_lock_hold_nanos_total[1m]))')
    sc = q('sum(rate(exchange_submit_count_total[1m]))')
    lw = (lw / 1e9) if lw is not None else None
    lh = (lh / 1e9) if lh is not None else None

    # 임계구역 안 분해(ADR 0022 0단계 (b)). 락 보유의 3분의 2가 데이터베이스 밖이라는
    # 것까지만 알고 그 안을 몰랐다(정본 5.7.10). append 를 문장별로 가른다.
    # appendTimer - inTx 가 커넥션 획득과 트랜잭션 경계와 커밋의 합이다.
    def jn(name):
        v = q('sum(rate(exchange_journal_%s_nanos_total[1m]))' % name)
        return (v / 1e9) if v is not None else None

    ap_sum = q('sum(rate(exchange_journal_append_seconds_sum[1m]))')
    ap_cnt = q('sum(rate(exchange_journal_append_seconds_count[1m]))')
    intx, pro, ins = jn('intx'), jn('prologue'), jn('insert')
    pre, cmt, post = jn('pre'), jn('commit'), jn('post')
    jc = q('sum(rate(exchange_journal_intx_count_total[1m]))')
    acc = q('sum(rate(http_server_requests_seconds_count'
            '{uri="/api/v1/orders",method="POST",status="201"}[1m]))')
    xact = q('sum(rate(pg_stat_database_xact_commit{datname="exchange"}[1m]))')
    out = {
        '접수/s': acc,
        '체결/s': q('sum(rate(exchange_trades_total[1m]))'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total{scope="order"}[1m]))'),
        'p95(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p95_1m)')),
        'p99(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p99_1m)')),
        '적용 점유': ap, 'sink 점유': sk, '훅 점유': hk,
        '직렬 합계': None if None in (ap, sk, hk) else ap + sk + hk,
        '적용 호출/s': n,
        '적용 1회(ms)': (ap / n * 1e3) if (ap and n) else None,
        'sink 1회(ms)': (sk / n * 1e3) if (sk and n) else None,
        '훅 1회(ms)': (hk / n * 1e3) if (hk is not None and n) else None,
        '1건당 직렬(ms)': (((ap or 0) + (sk or 0) + (hk or 0)) / n * 1e3) if n else None,
        '락 보유 점유': lh,
        '락 대기 점유': lw,
        '접수 시도/s': sc,
        '락 보유 1회(ms)': (lh / sc * 1e3) if (lh and sc) else None,
        '락 대기 1회(ms)': (lw / sc * 1e3) if (lw and sc) else None,
        'append 1회(ms)': (ap_sum / ap_cnt * 1e3) if (ap_sum and ap_cnt) else None,
        'inTx 1회(ms)': (intx / jc * 1e3) if (intx and jc) else None,
        '  머리말 1회(ms)': (pro / jc * 1e3) if (pro and jc) else None,
        '  저널삽입 1회(ms)': (ins / jc * 1e3) if (ins and jc) else None,
        '커넥션·경계·커밋 1회(ms)': ((ap_sum / ap_cnt - intx / jc) * 1e3)
                                   if (ap_sum and ap_cnt and intx and jc) else None,
        '  커넥션획득·경계시작(ms)': (pre / jc * 1e3) if (pre and jc) else None,
        '  커밋(ms)': (cmt / jc * 1e3) if (cmt and jc) else None,
        '  정리·반납(ms)': (post / jc * 1e3) if (post and jc) else None,
        'append 호출/s': ap_cnt,
        '트랜잭션/s': xact,
        '명령당 트랜잭션': (xact / n) if (xact and n) else None,
        '활성 커넥션': q('avg_over_time(pg_stat_activity_count{state="active",datname="exchange"}[1m])'),
        'DB CPU%': q('max(container_cpu_percent{name="chaos-pg"})'),
        'api1 CPU%': q('max(container_cpu_percent{name="chaos-api-1"})'),
        '풀 대기': q('sum(hikaricp_connections_pending)'),
    }
    per = {}
    for t in TABLES:
        ins = q('sum(rate(pg_stat_user_tables_n_tup_ins{relname="%s"}[1m]))' % t)
        upd = q('sum(rate(pg_stat_user_tables_n_tup_upd{relname="%s"}[1m]))' % t)
        if n and (ins is not None or upd is not None):
            per[t] = {'삽입/명령': round((ins or 0) / n, 3), '갱신/명령': round((upd or 0) / n, 3)}
    out['표별 쓰기'] = per
    return out


def main():
    a_ = argparse.ArgumentParser()
    a_.add_argument('--label', required=True)
    a_.add_argument('--rate', type=int, default=240)
    a_.add_argument('--duration', type=int, default=130)
    a_.add_argument('--bots-ops', type=int, default=20)
    a_.add_argument('--warmup', type=int, default=240)
    a_.add_argument('--out', required=True)
    a = a_.parse_args()

    print('[%s] 부하 %d/s' % (a.label, a.rate), flush=True)
    if not cs.reset_stack(ENV):
        print('  초기화 실패: %s' % cs.LAST_ERR[0][-300:], flush=True)
        return 1
    if cs.reissue_keys() == 0:
        print('  키 재발급 실패', flush=True)
        return 1
    hit, want = cs.keys_match_db()
    if hit < want:
        print('  키 불일치 %d/%d' % (hit, want), flush=True)
        return 1
    cs.set_bot_ops(a.bots_ops)
    if not cs.wait_bots_steady(a.warmup):
        print('  봇 안정화 실패', flush=True)
        return 1

    # 부하 전에 잰다. 부하 중이면 같은 장치를 k6 주행과 다투게 되고 측정값이 그 다툼을 담는다.
    fsync = cs.fsync_state()

    k6log = str(ROOT / ('harness/evidence/k6-rt-%s.log' % a.label))
    proc = run_k6(a.rate, a.duration, 'rt%s%d' % (a.label[:3], a.rate), log_path=k6log)
    time.sleep(min(duration_fill(a.duration), a.duration - 5))
    s = sample()
    s.update(fsync)
    s['label'] = a.label
    s['rate'] = a.rate
    try:
        proc.communicate(timeout=a.duration + 120)
    except subprocess.TimeoutExpired:
        proc.kill()
    s['k6_rc'] = proc.returncode
    io.open(a.out, 'w', encoding='utf-8').write(json.dumps(s, ensure_ascii=False, indent=2))
    for k, v in s.items():
        if k != '표별 쓰기':
            print('  %-16s %s' % (k, round(v, 3) if isinstance(v, float) else v), flush=True)
    for t, d in (s.get('표별 쓰기') or {}).items():
        print('  %-24s 삽입 %6.3f 갱신 %6.3f' % (t, d['삽입/명령'], d['갱신/명령']), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
