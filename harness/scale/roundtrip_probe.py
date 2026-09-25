"""명령 하나가 데이터베이스를 몇 번 왕복하는지 재고 직렬 구간을 함께 가른다.

배경. 정본 5.7.5 가 다음 상한을 왕복 횟수로 특정했다. 명령 하나에 트랜잭션 19.34개이고
직렬 시간 21.36밀리초였다. 왕복을 줄이는 판을 한 뒤 그 둘이 실제로 줄었는지 본다.

무엇을 재는가.
  직렬 점유    exchange_writer_{apply,sink,hook}_nanos_total 의 초당 증가분
  명령당 시간  같은 값을 적용 호출 수로 나눈 것
  명령당 트랜잭션  pg_stat_database_xact_commit 의 초당 증가분을 접수로 나눈 것
  표별 쓰기    pg_stat_user_tables 의 삽입과 갱신을 접수로 나눈 것
  갭 회수 비   exchange_writer_catchup_calls_total 을 적용 호출 수로 나눈 것
  예약 락      exchange_inflight_lock_{wait,hold}_nanos_total 의 초당 증가분
  교착과 커밋  pg_stat_database 의 deadlocks 와 xact_commit 주행 전후 차

ADR 0022 4단계 판정 입력(stage4_judge.py 가 읽는다).
  마켓별 락 보유 점유와 접수 시도  market 태그 시계열. 태그가 없는 판은 '' 한 키다
  12마켓 접수      k6 주행 창의 command_journal NEW 행을 market_id 로 센다
  카운터 행 표본   15초마다 pg_samplers.COUNTER_ROW_RULE 로 센다. 부하 뒤 분류기 양성 대조를 한다
  연결 표본        같은 틱의 pg_stat_activity 연결 분해와 chaos-pg 로그의 거절 문구

ADR 0022 6단계 판정 입력(stage6_judge.py 가 읽는다). 위 넷에 더한다.
  래치             --latch-before-traffic 이면 기동 직후, 키 발급과 봇 워밍업 전에 건다. 칸마다 래치 행을 남긴다
  대역 행과 C28    k6 주행 창의 command_journal 행을 마켓별 대역과 레거시로 센다. C28 은 주행 뒤 전체 저널 값이다
  카운터 행 분리 표본  같은 틱에 pg_samplers.COUNTER_SPLIT_RULE 로 전역과 마켓을 가른다. 양성 대조도 각각 한다

ADR 0022 8단계 판정 입력(stage8_judge.py 가 읽는다). 위에 더한다.
  커밋 설정   --commit-delay 와 --commit-siblings 를 주면 래치 뒤, 키 발급 전에 commit_delay.apply 로 건다.
              k6 뒤 유지 확인과 칸 끝 복구를 남기고, 실패 경로의 복구는 __main__ 이 맡는다
  WAL 창      프로메테우스 1분 창과 같은 구간의 pg_stat_wal.wal_sync 와 배정 xid 차. 묶음 비가 여기서 나온다

ADR 0034 S3 판정 입력(마켓분할_리더_S3_설계 7-3 X5). 위에 더한다.
  모드 전환   --protocol-before-traffic 2 이면 스택을 세운 직후, 래치·키 발급·봇 예열 전에 protocol_latch.transition 으로
              P2 로 올린다. 칸마다 전환 결과와 주행 뒤 모드 상태(예약 행 수, 정산 전 예약)를 남긴다
  단일 마켓   SCALE_MARKETS 가 마켓 하나면 주행 창의 그 마켓 k6 NEW 수를 주행 초로 나눈 접수율과 과포화 여부(요청률의 80퍼센트
              이하), 그 마켓의 명령당 접수 락 보유를 남긴다. 봇 주문과 다른 마켓은 세지 않는다

ADR 0034 S4b 판정 입력(마켓분할_리더_S4b_설계 4-7 X7). 위에 더한다.
  접수/s(k6 저널 행)  주행 창의 모든 마켓 k6 NEW 행 합을 주행 초로 나눈 칸 값. 1분 창 rate 는 기록으로 남긴다
  이 러너는 칸마다 down -v 를 하므로 보존 스택(CHAOS_PROJECT 미설정·exchange-chaos)에서는 시작하지 않는다. 모드 전환 칸은
  CHAOS_PROJECT=exchange-pml, CHAOS_APP_IMAGE=exchange-chaos-app:pml 일 때만 시작한다(7-0, 하네스 검증설계 §2-4)

한 칸만 돈다. 규격은 4코어로 고정한다. 정본 5.7.6 이 그 지점을 포화로 판정했다.

돌리는 법:
  python harness/scale/roundtrip_probe.py --label after --rate 240
"""
import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import q, qv  # noqa: E402
from k6_ceiling import run_k6, duration_fill, markets, IMAGE as K6_IMAGE  # noqa: E402
import clean_suite as cs  # noqa: E402
import pg_samplers as pgs  # noqa: E402
import band_cutover as bc  # noqa: E402
import commit_delay as cd  # noqa: E402
import protocol_latch as pl  # noqa: E402

ENV = {'SCALE_POOL': '32', 'SCALE_ORDER_INFLIGHT': '0', 'SCALE_QUERY_INFLIGHT': '0',
       'SCALE_REQUERY_INFLIGHT': '0', 'SCALE_PG_CPUS': '4.0', 'SCALE_PG_MEM': '4g'}

TABLES = ('ledger_entries', 'balances', 'ledger_transactions', 'orders',
          'event_journal', 'command_journal', 'event_emission_registry',
          'trades', 'position_cost', 'market_journal_counters', 'journal_counters')

# pgstat.c PGSTAT_IDLE_INTERVAL(10초)보다 길게 잡는다.
PGSTAT_FLUSH_WAIT_S = 11

# ADR 0022 7절 4단계가 카운터 행 표본 간격을 15초로 정한다.
SAMPLE_INTERVAL_S = 15

# WAL 계수기 차는 프로메테우스 rate 창과 같은 1분에서 잰다. 백엔드 통계는 1초 안팎으로 모아 쓰여 창 양 끝이 같은 폭으로 늦는다.
WAL_WINDOW_S = 60

# 칸 값을 건 뒤 칸 끝 복구를 확인하기 전에 끝난 실행은 __main__ 이 되돌린다.
COMMIT_APPLIED = [False]

PROM_SERIES = (
    ('접수/s', 'sum(rate(http_server_requests_seconds_count'
             '{uri="/api/v1/orders",method="POST",status="201"}[1m]))'),
    ('수용거절/s', 'sum(rate(exchange_admission_rejected_total{scope="order"}[1m]))'),
    ('락 보유 점유', 'sum(rate(exchange_submit_lock_hold_nanos_total[1m]))/1e9'),
    ('커밋 동시성', 'sum(rate(exchange_journal_commit_nanos_total[1m]))/1e9'),
    ('append 풀 대기', 'sum(hikaricp_connections_pending{pool="exchange-append"})'),
)

# 잔고 행 대기 표본(S3 설계 7-3 X5 기록). 행 잠금을 기다리는 백엔드는 balances 의 tuple 잠금을 쥐거나 기다린다. ctid 로
# 계정을 찾아 사용자 행(USER)과 시스템 행(수수료 FEE_INCOME 등)을 나눈다. balances 는 파티션 표가 아니다(V1).
BALANCE_WAIT_SQL = """
SELECT json_build_object(
  'ts', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
  'lock_waiters', (SELECT count(*) FROM pg_stat_activity WHERE datname = 'exchange' AND wait_event_type = 'Lock'),
  'balance_row_waiters', (SELECT coalesce(json_object_agg(k, n), '{}'::json) FROM (
        SELECT coalesce(ac.owner_type || ':' || ac.purpose, 'unknown') AS k, count(DISTINCT l.pid) AS n
          FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
          LEFT JOIN balances b ON b.ctid = format('(%s,%s)', l.page, l.tuple)::tid
          LEFT JOIN accounts ac ON ac.id = b.account_id
         WHERE l.locktype = 'tuple' AND l.relation = 'balances'::regclass AND a.wait_event_type = 'Lock'
         GROUP BY 1) t))
"""

NODE_PORTS = (18080, 28080)
SCRAPE_NAMES = ('exchange_submit_count_total', 'exchange_submit_lock_hold_nanos_total',
                'exchange_writer_catchup_calls_total', 'exchange_writer_apply_count_total')
PROM_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)')
PROM_LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')
DB_TS = re.compile(r'[0-9: .+-]+')


def pg_counters():
    """커밋과 교착의 누계를 데이터베이스에서 직접 읽는다. 주행 전후 차로 쓴다.

    rate 창 1분만 보면 창 밖에서 난 교착을 놓치고, 프로메테우스를 거치면 스크레이프 지연으로
    주행 끝의 증가분이 빠질 수 있다. 데이터베이스 쪽에도 같은 성질의 지연이 있다. PG15 이후
    백엔드는 통계를 모았다가 쓰고 마지막 기록 뒤 1초 안이면 유휴 10초 뒤로 미룬다. 그래서
    주행 뒤 값은 PGSTAT_FLUSH_WAIT_S 를 기다린 다음 읽는다."""
    try:
        p = subprocess.run(
            ['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-t', '-A',
             '-F', '|', '-c',
             "select xact_commit, deadlocks from pg_stat_database where datname = 'exchange';"],
            capture_output=True, text=True, timeout=60)
        c, d = p.stdout.strip().split('|')
        return int(c), int(d)
    except (ValueError, subprocess.SubprocessError, OSError):
        return None


def balance_waits():
    parsed, err = pgs.parse_json_row(*pgs.run_psql(BALANCE_WAIT_SQL))
    return parsed if parsed is not None else {'error': err}


def summarize_balance_waits(rows):
    """표본마다 잔고 행 대기 백엔드 수(계정 종류별)의 최댓값과 평균. 표본이 없으면 빈 값이다."""
    keys = sorted({k for r in rows for k in (r.get('balance_row_waiters') or {})})
    ok = [r for r in rows if 'balance_row_waiters' in r]
    out = {'표본 수': len(ok), '오류 표본': len(rows) - len(ok)}
    for k in keys:
        vals = [(r.get('balance_row_waiters') or {}).get(k, 0) for r in ok]
        out[k] = {'최대': max(vals) if vals else None, '평균': (sum(vals) / len(vals)) if vals else None}
    lw = [r.get('lock_waiters') or 0 for r in ok]
    out['Lock 대기 전체'] = {'최대': max(lw) if lw else None, '평균': (sum(lw) / len(lw)) if lw else None}
    return out


def by_market(expr):
    """market 라벨별 값. 질의 실패는 None 이고 라벨 없는 시계열은 '' 키에 담는다."""
    rows = qv(expr)
    if rows is None:
        return None
    return {m.get('market', ''): v for m, v in rows}


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

    # 갭 회수 저널 왕복(ADR 0022 0단계 (c)). 전역 seq 를 마켓 12개가 나눠 쓰면 한 마켓
    # 스트림의 명령 대부분이 갭으로 도착한다. 적용 대비 비의 계산값 1 - 1/12 를 이것으로 잰다.
    cu_n = q('sum(rate(exchange_writer_catchup_calls_total[1m]))')
    cu_t = ns('catchup')
    # in-flight 예약 락. 저널 replay 를 뺀 접수가 지나는 락이고(4단계 전에는 전역 하나, 4단계부터 스트라이프 1024개) 안에서 잔고 조회가
    # 데이터베이스를 왕복한다.
    iw = q('sum(rate(exchange_inflight_lock_wait_nanos_total[1m]))')
    ih = q('sum(rate(exchange_inflight_lock_hold_nanos_total[1m]))')
    iw = (iw / 1e9) if iw is not None else None
    ih = (ih / 1e9) if ih is not None else None
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
        # 4단계 뒤에는 접수 락이 마켓마다 있어 보유 점유 합이 1.0 을 넘을 수 있다.
        '락 보유 점유(마켓별)': by_market(
            'sum by (market) (rate(exchange_submit_lock_hold_nanos_total[1m]))/1e9'),
        '접수 시도/s(마켓별)': by_market('sum by (market) (rate(exchange_submit_count_total[1m]))'),
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
        'append 풀 대기': q('sum(hikaricp_connections_pending{pool="exchange-append"})'),
        # 순간값 하나는 스크레이프 한 번이다. rate 창과 같은 1분의 최대를 함께 둔다.
        'append 풀 대기 최대(1m)': q(
            'max(max_over_time(hikaricp_connections_pending{pool="exchange-append"}[1m]))'),
        # 커밋 누적 나노초의 초당 증가분. 1.0 을 넘으면 커밋이 동시에 겹친 것이다.
        '커밋 동시성': cmt,
        'catchup 왕복/s': cu_n,
        'catchup 비': (cu_n / n) if (cu_n is not None and n) else None,
        'catchup 점유': cu_t,
        'catchup 1회(ms)': (cu_t / cu_n * 1e3) if (cu_t and cu_n) else None,
        'inflight 락 보유 점유': ih,
        'inflight 락 대기 점유': iw,
    }
    per = {}
    for t in TABLES:
        ins = q('sum(rate(pg_stat_user_tables_n_tup_ins{relname="%s"}[1m]))' % t)
        upd = q('sum(rate(pg_stat_user_tables_n_tup_upd{relname="%s"}[1m]))' % t)
        if n and (ins is not None or upd is not None):
            per[t] = {'삽입/명령': round((ins or 0) / n, 3), '갱신/명령': round((upd or 0) / n, 3)}
    out['표별 쓰기'] = per
    return out


class RunSampler(threading.Thread):
    """k6 가 도는 동안 15초마다 카운터 행, 연결, 프로메테우스 표본을 뜬다. k6 가 끝난 뒤 한 번 더 뜨고 멈춘다."""

    def __init__(self, proc):
        super().__init__(daemon=True)
        self.proc = proc
        self.rows = []

    def run(self):
        next_t = time.monotonic()
        while True:
            alive = self.proc.poll() is None
            counter, split = pgs.sample_counter_rows()
            counter['k6_alive'] = split['k6_alive'] = alive
            conn = pgs.sample_connections()
            conn['k6_alive'] = alive
            prom = {k: q(e) for k, e in PROM_SERIES}
            bal = balance_waits()
            bal['k6_alive'] = alive
            self.rows.append({'ts': counter['ts'], 'k6_alive': alive, 'counter': counter, 'split': split,
                              'conn': conn, 'prom': prom, 'balance': bal})
            if not alive:
                return
            next_t += SAMPLE_INTERVAL_S
            while self.proc.poll() is None and time.monotonic() < next_t:
                time.sleep(0.5)


def db_clock():
    rc, out, _ = pgs.run_psql('SELECT clock_timestamp()')
    t = (out or '').strip() if rc == 0 else ''
    return t if DB_TS.fullmatch(t) else None


def accepts_by_market(run_id, t0, t1):
    """k6 주행 창의 저널 행을 마켓별로 센다. 접수 201 은 저널 커밋 뒤에 나가므로 NEW 행이 접수의
    데이터베이스 쪽 근거다. k6 몫은 주행 식별자 접두로, 전체 몫은 봇과 취소를 포함한 모든 명령이다."""
    out = {'run_id': run_id, '창': {'from': t0, 'to': t1}, 'k6 마켓': markets().split(','),
           '마켓': None, 'error': None}
    if not (t0 and t1 and re.fullmatch(r'[A-Za-z0-9_-]{1,16}', run_id)):
        out['error'] = '주행 창 시각이나 주행 식별자가 없다'
        return out
    sql = (
        "SELECT coalesce(json_agg(json_build_object('id', m.id, 'symbol', m.symbol, "
        "'k6_new', coalesce(k.n, 0), 'all_commands', coalesce(a.n, 0)) ORDER BY m.id), '[]'::json) "
        "FROM markets m "
        "LEFT JOIN (SELECT market_id, count(*) AS n FROM command_journal "
        "WHERE command_type = 'NEW' AND client_order_id LIKE 'k6-%s-%%' "
        "AND created_at >= '%s' AND created_at < '%s' GROUP BY market_id) k ON k.market_id = m.id "
        "LEFT JOIN (SELECT market_id, count(*) AS n FROM command_journal "
        "WHERE created_at >= '%s' AND created_at < '%s' GROUP BY market_id) a ON a.market_id = m.id"
        % (run_id, t0, t1, t0, t1))
    parsed, err = pgs.parse_json_row(*pgs.run_psql(sql, timeout=120))
    out['마켓'] = parsed
    out['error'] = err
    return out


def parse_prom_text(text, names):
    rows = []
    for line in (text or '').splitlines():
        if not line or line.startswith('#'):
            continue
        m = PROM_LINE.match(line)
        if not m or m.group(1) not in names:
            continue
        try:
            value = float(m.group(3))
        except ValueError:
            continue
        rows.append({'name': m.group(1), 'labels': dict(PROM_LABEL.findall(m.group(2) or '')),
                     'value': value})
    return rows


def scrape_nodes():
    """두 노드 actuator 를 직접 긁는다. 프로메테우스를 거치면 스크레이프 간격만큼 창 끝이 흐려진다."""
    out = {}
    for port in NODE_PORTS:
        try:
            with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=10) as r:
                text = r.read().decode('utf-8', 'replace')
            out[str(port)] = {'ok': True, 'series': parse_prom_text(text, SCRAPE_NAMES)}
        except Exception as e:
            out[str(port)] = {'ok': False, 'error': type(e).__name__, 'series': None}
    return out


def counter_delta(scrape0, scrape1, name):
    """두 긁기 사이 증가를 market 라벨별로 노드 합산한다. 한 노드라도 긁기에 실패했거나 카운터가 줄었으면 None 이다."""
    total = {}
    for port in map(str, NODE_PORTS):
        a, b = (scrape0 or {}).get(port) or {}, (scrape1 or {}).get(port) or {}
        if not (a.get('ok') and b.get('ok')):
            return None
        before = {}
        for row in a['series']:
            if row['name'] == name:
                key = row['labels'].get('market', '')
                before[key] = before.get(key, 0.0) + row['value']
        after = {}
        for row in b['series']:
            if row['name'] == name:
                key = row['labels'].get('market', '')
                after[key] = after.get(key, 0.0) + row['value']
        for key, v in after.items():
            d = v - before.get(key, 0.0)
            if d < 0:
                return None
            total[key] = total.get(key, 0.0) + d
    return total


def submit_crosscheck(scrape0, scrape1, accepts):
    """접수 시도 계측 증가와 같은 창의 저널 행 수를 나란히 둔다. 판정 입력이 아니고 대조다.
    계측은 거부로 끝난 시도도 세고, 저널은 커밋된 명령만 세므로 같을 필요는 없다."""
    delta = counter_delta(scrape0, scrape1, 'exchange_submit_count_total')
    rows = accepts.get('마켓') or []
    db = {}
    for r in rows:
        db[str(r.get('id'))] = r.get('all_commands')
        db[r.get('symbol')] = r.get('all_commands')
    per = None
    if delta is not None:
        per = {k: {'계측 증가': v, '저널 행': db.get(k) if k else sum((r.get('all_commands') or 0) for r in rows)}
               for k, v in sorted(delta.items())}
    return {'마켓별': per, '저널 행 합': sum((r.get('all_commands') or 0) for r in rows) if rows else None}


def journal_accept_rate(accepts, duration):
    """접수/s(k6 저널 행)(S4b 설계 4-7). k6 주행 창의 k6 NEW 행 합을 주행 초로 나눈다. 봇과 취소 명령(all_commands 에만
    든다)은 세지 않는다. 창 끝 시각은 k6 종료 뒤 표본 스레드가 멈출 때까지 늦게 찍히므로 창 폭이 아니라 주행 초로 나눈다.
    창이 없거나 조회가 실패하면 값이 없다."""
    rows = accepts.get('마켓')
    if accepts.get('error') or rows is None or not duration:
        return None
    return sum((r.get('k6_new') or 0) for r in rows) / float(duration)


def single_market(s, market, duration, rate):
    """단일 마켓 과포화 주행의 판정값(S3 설계 7-3 X5 (ii)). 주행 창의 그 마켓 k6 NEW 수 / 주행 초다.
    명령당 접수 락 보유는 그 마켓 라벨의 보유 점유를 접수 시도율로 나눈 값이고 설명에만 쓴다."""
    rows = (s.get('접수(DB, 마켓별)') or {}).get('마켓') or []
    hit = next((r for r in rows if r.get('symbol') == market), None)
    k6_new = hit.get('k6_new') if hit else None
    accept = (k6_new / float(duration)) if (k6_new is not None and duration) else None
    hold = s.get('락 보유 점유(마켓별)') or {}
    tries = s.get('접수 시도/s(마켓별)') or {}
    keys = [k for k in (market, str(hit.get('id')) if hit else None) if k is not None]
    h = next((hold[k] for k in keys if hold.get(k) is not None), None)
    t = next((tries[k] for k in keys if tries.get(k) is not None), None)
    return {
        '마켓': market, 'k6 NEW': k6_new, '주행 초': duration, '요청률': rate,
        '접수율(/s)': accept,
        '과포화(요청률의 80% 이하)': (accept <= 0.8 * rate) if accept is not None else None,
        '명령당 락 보유(ms)': (h / t * 1e3) if (h and t) else None,
        '락 보유 점유': h, '접수 시도/s': t,
    }


def sha256_of(path):
    try:
        return hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError:
        return None


def container_images():
    out = {}
    for c in ('chaos-api-1', 'chaos-api-2', 'chaos-bots'):
        p = subprocess.run(['docker', 'inspect', '-f', '{{.Image}}|{{.Config.Image}}', c],
                           capture_output=True, text=True, timeout=30)
        parts = p.stdout.strip().split('|')
        out[c] = ({'image_id': parts[0], 'config_image': parts[1]}
                  if p.returncode == 0 and len(parts) == 2 else None)
    return out


def git_head():
    p = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], capture_output=True, text=True, timeout=30)
    return p.stdout.strip() if p.returncode == 0 else None


def main():
    a_ = argparse.ArgumentParser()
    a_.add_argument('--label', required=True)
    a_.add_argument('--rate', type=int, default=240)
    a_.add_argument('--duration', type=int, default=130)
    a_.add_argument('--bots-ops', type=int, default=20)
    a_.add_argument('--warmup', type=int, default=240)
    a_.add_argument('--out', required=True)
    a_.add_argument('--latch-before-traffic', action='store_true',
                    help='ADR 0022 6단계 B6. 되돌릴 수 없는 래치를 새로 세운 카오스 DB 에 건다')
    a_.add_argument('--commit-delay', type=int, default=None, help='ADR 0022 8단계 칸 값. 마이크로초')
    a_.add_argument('--commit-siblings', type=int, default=None, help='ADR 0022 8단계 칸 값')
    a_.add_argument('--protocol-before-traffic', type=int, choices=(2,), default=None,
                    help='ADR 0034 S3. 스택을 세운 직후 키 발급과 봇 예열 전에 이 모드로 전환한다(protocol_latch)')
    a = a_.parse_args()
    if (a.commit_delay is None) != (a.commit_siblings is None):
        a_.error('--commit-delay 와 --commit-siblings 는 함께 준다')
    # 칸마다 down -v 를 한다. 보존 스택이면 시작하지 않는다(하네스 검증설계 §2-4). 모드 전환 칸은 S3 폐기용 스택
    # (exchange-pml, exchange-chaos-app:pml)에서만 돈다(S3 설계 7-0). ADR 0022 단계 절차는 자기 태그로 이 러너를 부른다.
    problems = cs.disposable_problems(protocol=a.protocol_before_traffic is not None)
    if problems:
        print('보존 스택 가드로 시작하지 않는다: %s' % '; '.join(problems), flush=True)
        return 2

    started = pgs.now_iso()
    print('[%s] 부하 %d/s' % (a.label, a.rate), flush=True)
    if not cs.reset_stack(ENV):
        print('  초기화 실패: %s' % cs.LAST_ERR[0][-300:], flush=True)
        return 1
    protocol = None
    if a.protocol_before_traffic is not None:
        # 스택 기동 직후, 래치·키 발급·봇 예열보다 먼저 한다. 봇은 스택과 함께 떠 있으므로 전환 도구가 멈추고 드레인한 뒤 되살린다.
        protocol = pl.transition(a.protocol_before_traffic)
        if not protocol['ok']:
            print('  모드 전환 실패(code=%s): %s' % (protocol['code'], protocol['error']), flush=True)
            if a.out:
                io.open(a.out + '.protocol-failed.json', 'w', encoding='utf-8').write(
                    json.dumps(protocol, ensure_ascii=False, indent=2, default=str))
            return 1
        print('  모드 전환 P%d, 새 리더 epoch %s, 호출 %sms' % (
            a.protocol_before_traffic, protocol.get('new_leader_epoch'),
            (protocol.get('call') or {}).get('duration_ms')), flush=True)
    latch = None
    if a.latch_before_traffic:
        # 키 발급, 봇 워밍업, k6 보다 먼저 건다. 봇은 스택 기동과 함께 돌므로 호출 전 저널 행 수를 함께 남긴다.
        latch = bc.latch()
        if not latch['ok']:
            print('  래치 실패: %s' % json.dumps(latch, ensure_ascii=False), flush=True)
            return 1
        print('  래치 %s' % latch['상태']['래치 행'], flush=True)
    commit = None
    if a.commit_delay is not None:
        # 래치 뒤, 키 발급과 봇 워밍업 전에 건다. 명령 하나만 먹혀도 값이 남으므로 표시를 호출보다 먼저 세운다.
        COMMIT_APPLIED[0] = True
        commit = {'적용': cd.apply(a.commit_delay, a.commit_siblings), '유지': None, '복구': None}
        if not commit['적용']['ok']:
            print('  커밋 설정 실패: %s' % commit['적용']['error'], flush=True)
            return 1
        print('  commit_delay %d, commit_siblings %d' % (a.commit_delay, a.commit_siblings), flush=True)
    n_keys = cs.reissue_keys()
    if n_keys == 0:
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
    pools = pgs.live_pool_max()

    run_id = 'rt%s%d' % (a.label[:3], a.rate)
    k6log = str(ROOT / ('harness/evidence/k6-rt-%s.log' % a.label))
    pg0 = pg_counters()
    scrape0 = scrape_nodes()
    t0 = db_clock()
    proc = run_k6(a.rate, a.duration, run_id, log_path=k6log)
    sampler = RunSampler(proc)
    sampler.start()
    k6_started = time.monotonic()
    fill = min(duration_fill(a.duration), a.duration - 5)
    time.sleep(max(0, fill - WAL_WINDOW_S))
    wal0 = pgs.sample_wal_counters()
    time.sleep(max(0.0, k6_started + fill - time.monotonic()))
    wal1 = pgs.sample_wal_counters()
    s = sample()
    s.update(fsync)
    s['label'] = a.label
    s['rate'] = a.rate
    try:
        proc.communicate(timeout=a.duration + 120)
    except subprocess.TimeoutExpired:
        proc.kill()
    s['k6_rc'] = proc.returncode
    sampler.join(timeout=SAMPLE_INTERVAL_S * 4)
    t1 = db_clock()
    scrape1 = scrape_nodes()
    if commit is not None:
        commit['유지'] = cd.settings()
    time.sleep(PGSTAT_FLUSH_WAIT_S)
    pg1 = pg_counters()
    # 읽기 실패는 None 으로 남긴다. 0 으로 채우면 교착 0건으로 읽힌다.
    s['xact_commit 증가'] = (pg1[0] - pg0[0]) if (pg0 and pg1) else None
    s['deadlocks 증가'] = (pg1[1] - pg0[1]) if (pg0 and pg1) else None

    accepts = accepts_by_market(run_id, t0, t1)
    s['접수(DB, 마켓별)'] = accepts
    # 칸 값. 1분 창 rate(접수/s)는 k6 주행 안에서 정점에 닿지 않을 수 있어 기록으로만 남긴다.
    s['접수/s(k6 저널 행)'] = journal_accept_rate(accepts, a.duration)
    s['접수 시도 계측 대조'] = submit_crosscheck(scrape0, scrape1, accepts)
    s['노드 계측 증가'] = {name: counter_delta(scrape0, scrape1, name) for name in SCRAPE_NAMES}
    counter_rows = [r['counter'] for r in sampler.rows]
    s['카운터 행 표본'] = {'간격(초)': SAMPLE_INTERVAL_S, '규칙': pgs.COUNTER_ROW_RULE,
                        '요약': pgs.summarize_counter_samples(counter_rows), '표본': counter_rows}
    split_rows = [r['split'] for r in sampler.rows]
    s['카운터 행 표본(전역·마켓)'] = {
        '간격(초)': SAMPLE_INTERVAL_S, '규칙': pgs.COUNTER_SPLIT_RULE,
        '요약': {'전역': pgs.summarize_counter_samples(split_rows, 'global_row_wait'),
                '마켓': pgs.summarize_counter_samples(split_rows, 'market_row_wait')},
        '표본': split_rows}
    controls = pgs.counter_row_controls()
    s['카운터 행 양성 대조'] = controls['카운터 행']
    s['카운터 행 양성 대조(전역)'] = controls['전역']
    s['카운터 행 양성 대조(마켓)'] = controls['마켓']
    s['래치'] = {'트래픽 전 래치': a.latch_before_traffic, '호출': latch, '상태': bc.latch_state()}
    s['모드'] = {'트래픽 전 전환': a.protocol_before_traffic, '전환': protocol, '주행 뒤 상태': pl.state()}
    mk = markets().split(',')
    if len(mk) == 1:
        s['단일 마켓'] = single_market(s, mk[0], a.duration, a.rate)
    s['대역 행(DB, 마켓별)'] = bc.band_rows((t0, t1))
    s['C28'] = bc.c28()
    conn_rows = [r['conn'] for r in sampler.rows]
    conn_summary = pgs.summarize_connection_samples(conn_rows)
    conn_log = pgs.pg_log_exhaustion()
    s['연결'] = {'요약': conn_summary, '로그': conn_log, '판정': pgs.connection_verdict(conn_summary, conn_log),
                '풀 상한(부하 전)': pools, '표본': conn_rows}
    s['표본 시계열'] = [dict(r['prom'], ts=r['ts'], k6_alive=r['k6_alive']) for r in sampler.rows]
    bal_rows = [r['balance'] for r in sampler.rows if r['k6_alive']]
    s['잔고 행 대기 표본'] = {'간격(초)': SAMPLE_INTERVAL_S, '요약(k6 주행 중)': summarize_balance_waits(bal_rows),
                        '표본': [r['balance'] for r in sampler.rows]}
    s['WAL 창'] = pgs.wal_window(wal0, wal1)
    if commit is not None:
        commit['복구'] = cd.reset()
        COMMIT_APPLIED[0] = not commit['복구']['ok']
        s['커밋 설정'] = commit
    s['메타'] = {
        '시작': started,
        '끝': pgs.now_iso(),
        'run_id': run_id,
        'ENV': ENV,
        'CHAOS_APP_IMAGE': os.environ.get('CHAOS_APP_IMAGE'),
        'CHAOS_PROJECT': os.environ.get('CHAOS_PROJECT'),
        'SCALE_LOAD_KEYS': cs.load_keys(),
        'SCALE_MARKETS': os.environ.get('SCALE_MARKETS'),
        'CHAOS_EXTRA_COMPOSE': os.environ.get('CHAOS_EXTRA_COMPOSE'),
        'CHAOS_SPRING_PROFILES': os.environ.get('CHAOS_SPRING_PROFILES'),
        '컨테이너 이미지': container_images(),
        '하네스 커밋': git_head(),
        'compose sha256': {Path(f).name: sha256_of(f) for f in (cs.CHAOS, cs.SCALE)},
        'k6': {'image': K6_IMAGE, 'script': 'calibrate.js', 'markets': markets().split(','),
               'duration': a.duration, 'bots_ops': a.bots_ops, 'keys': n_keys,
               'base_funding': cs.LOAD_BASE_FUNDING, 'quote_funding': cs.LOAD_QUOTE_FUNDING,
               'calibrate.js sha256': sha256_of(ROOT / 'harness/load/calibrate.js'),
               'order_flow.js sha256': sha256_of(ROOT / 'harness/load/order_flow.js')},
        'k6 로그': {'path': os.path.relpath(k6log, ROOT), 'sha256': sha256_of(k6log)},
    }
    io.open(a.out, 'w', encoding='utf-8').write(json.dumps(s, ensure_ascii=False, indent=2))
    for k, v in s.items():
        if not isinstance(v, (dict, list)):
            print('  %-16s %s' % (k, round(v, 3) if isinstance(v, float) else v), flush=True)
    for t, d in (s.get('표별 쓰기') or {}).items():
        print('  %-24s 삽입 %6.3f 갱신 %6.3f' % (t, d['삽입/명령'], d['갱신/명령']), flush=True)
    print('  락 보유 점유(마켓별) %s' % s['락 보유 점유(마켓별)'], flush=True)
    print('  카운터 행 표본 %s' % s['카운터 행 표본']['요약'], flush=True)
    print('  분류기 양성 대조 %s, 전역 %s, 마켓 %s' % (controls['카운터 행']['상태'], controls['전역']['상태'],
                                             controls['마켓']['상태']), flush=True)
    print('  카운터 행 분리 표본 %s' % s['카운터 행 표본(전역·마켓)']['요약'], flush=True)
    print('  래치 행 %s, C28 %s' % (s['래치']['상태']['래치 행'], s['C28']['위반 행']), flush=True)
    st = s['모드']['주행 뒤 상태']
    print('  모드 P%s, 예약 행 %s, 정산 전 예약 %s' % (st.get('protocol'), st.get('reservations'),
                                               st.get('unsettled_reservations')), flush=True)
    if s.get('단일 마켓'):
        sm = s['단일 마켓']
        print('  단일 마켓 %s 접수율 %s/s, 과포화 %s, 명령당 락 보유 %sms' % (
            sm['마켓'], None if sm['접수율(/s)'] is None else round(sm['접수율(/s)'], 2),
            sm['과포화(요청률의 80% 이하)'],
            None if sm['명령당 락 보유(ms)'] is None else round(sm['명령당 락 보유(ms)'], 3)), flush=True)
    print('  연결 %s' % s['연결']['판정'], flush=True)
    w = s['WAL 창']
    print('  WAL 창 %s초, fsync/s %s, 묶음 비 %s %s' % (w['초'], w['WAL fsync/s'], w['묶음 비'], w['error'] or ''),
          flush=True)
    if commit is not None:
        print('  커밋 설정 적용 %s, 유지 %s, 복구 %s' % (
            commit['적용']['ok'], cd.in_effect(commit['유지'], a.commit_delay, a.commit_siblings),
            commit['복구']['ok']), flush=True)
    return 0


if __name__ == '__main__':
    try:
        code = main()
    finally:
        # 실패 경로에서도 칸 값을 서버에 남기지 않는다.
        if COMMIT_APPLIED[0]:
            print('  커밋 설정 복구 %s' % cd.reset()['ok'], flush=True)
    sys.exit(code)
