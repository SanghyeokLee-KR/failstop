"""주행 중 잠금 표본. 계획 5절 도구 4.

프로메테우스 표본은 15초 간격이다(submit-lock-20260911 lock_samples.csv 방식).
카운터 행 대기 표본은 1초 간격이다. e1-pg-latency.ps1 의 pg_stat_activity 와 pg_blocking_pids
패턴을 append_command_prologue 호출에 맞췄다. 15초 간격이면 130초 주행에 표본이 8개뿐이라 순간 상태를 못 잡는다.

wait_event 로 둘을 가른다. advisory 는 NEW 멱등 어드바이저리 락, transactionid 와 tuple 은
journal_counters('command_seq') 행 UPDATE 대기다.

돌리는 법:
  python lock_sampler.py --label B0-240 --outdir <dir>
멈추기: <dir>/stop-<label> 파일을 만든다. --max-secs 가 상한이다.
"""
import argparse
import csv
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scale'))
from load_ceiling import q  # noqa: E402

PG_SQL = (
    "SELECT "
    "(SELECT count(*) FROM pg_stat_activity WHERE query ILIKE '%append_command_prologue%' "
    " AND state = 'active' AND pid <> pg_backend_pid()) || '|' || "
    "(SELECT count(*) FROM pg_stat_activity WHERE query ILIKE '%append_command_prologue%' "
    " AND state = 'active' AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()) || '|' || "
    "(SELECT count(*) FROM pg_stat_activity WHERE query ILIKE '%append_command_prologue%' "
    " AND state = 'active' AND wait_event_type = 'Lock' AND wait_event = 'advisory' AND pid <> pg_backend_pid()) || '|' || "
    "(SELECT count(*) FROM pg_stat_activity WHERE query ILIKE '%append_command_prologue%' "
    " AND state = 'active' AND wait_event_type = 'Lock' AND wait_event IN ('transactionid','tuple') AND pid <> pg_backend_pid()) || '|' || "
    "(SELECT count(*) FROM pg_stat_activity WHERE datname = 'exchange' AND state = 'active' AND pid <> pg_backend_pid()) || '|' || "
    "coalesce((SELECT string_agg(pid || ':' || coalesce(wait_event, '') || ':' || pg_blocking_pids(pid)::text, ';') "
    " FROM pg_stat_activity WHERE query ILIKE '%append_command_prologue%' AND state = 'active' "
    " AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()), '')"
)

PROM = [
    ('submit_s', 'sum(rate(exchange_submit_count_total[1m]))'),
    ('hold_occ', 'sum(rate(exchange_submit_lock_hold_nanos_total[1m]))/1e9'),
    ('wait_occ', 'sum(rate(exchange_submit_lock_wait_nanos_total[1m]))/1e9'),
    ('commit_conc', 'sum(rate(exchange_journal_commit_nanos_total[1m]))/1e9'),
    ('inflight_hold_occ', 'sum(rate(exchange_inflight_lock_hold_nanos_total[1m]))/1e9'),
    ('inflight_wait_occ', 'sum(rate(exchange_inflight_lock_wait_nanos_total[1m]))/1e9'),
    ('catchup_s', 'sum(rate(exchange_writer_catchup_calls_total[1m]))'),
    ('apply_s', 'sum(rate(exchange_writer_apply_count_total[1m]))'),
    ('sink_occ', 'sum(rate(exchange_writer_sink_nanos_total[1m]))/1e9'),
    ('append_pending', 'sum(hikaricp_connections_pending{pool="exchange-append"})'),
    ('accepts_s', 'sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))'),
    ('reject_s', 'sum(rate(exchange_admission_rejected_total{scope="order"}[1m]))'),
]


def k6_running():
    p = subprocess.run(['docker', 'ps', '-q', '--filter', 'ancestor=grafana/k6:1.4.0'],
                       capture_output=True, text=True, timeout=30)
    return 1 if p.stdout.strip() else 0


def pg_sample():
    p = subprocess.run(['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange',
                        '-t', '-A', '-c', PG_SQL], capture_output=True, text=True, timeout=30)
    if p.returncode != 0:
        return None
    parts = p.stdout.strip().split('|', 5)
    return parts if len(parts) == 6 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--label', required=True)
    ap.add_argument('--outdir', required=True)
    ap.add_argument('--max-secs', type=int, default=3600)
    a = ap.parse_args()
    out = Path(a.outdir)
    stop = out / ('stop-' + a.label)
    pg_f = open(out / ('pg_lock_samples_%s.csv' % a.label), 'w', newline='', encoding='utf-8')
    pr_f = open(out / ('lock_samples_%s.csv' % a.label), 'w', newline='', encoding='utf-8')
    pg_w = csv.writer(pg_f)
    pr_w = csv.writer(pr_f)
    pg_w.writerow(['ts', 'k6', 'ok', 'prologue_active', 'prologue_lock_wait', 'advisory_wait',
                   'counter_row_wait', 'db_active', 'waiters'])
    pr_w.writerow(['ts', 'k6'] + [k for k, _ in PROM])
    end = time.time() + a.max_secs
    next_prom = 0.0
    while time.time() < end and not stop.exists():
        t = time.time()
        ts = time.strftime('%H:%M:%S', time.localtime(t))
        k6 = k6_running()
        s = pg_sample()
        if s is None:
            pg_w.writerow([ts, k6, 0, '', '', '', '', '', ''])
        else:
            pg_w.writerow([ts, k6, 1] + s)
        pg_f.flush()
        if t >= next_prom:
            vals = []
            for _, expr in PROM:
                try:
                    v = q(expr)
                except Exception:
                    v = None
                vals.append('' if v is None else round(v, 4))
            pr_w.writerow([ts, k6] + vals)
            pr_f.flush()
            next_prom = t + 15
        time.sleep(max(0.0, 1.0 - (time.time() - t)))
    pg_f.close()
    pr_f.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
