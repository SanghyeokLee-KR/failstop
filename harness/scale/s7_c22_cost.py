"""Q14 C22' 비용 실행기(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 5-2 Q14, 하네스 검증설계 §4-1 대조
규칙의 비용 항). 아직 돌린 적이 없다(NOT RUN).

폐기용 postgres:17-alpine 컨테이너 하나(이름 pml-probe-s7q14-<난수>, --rm, CPU 1, 호스트 포트 없음)에 저장소의 V0~V55 를 파일마다 한
트랜잭션으로 올리고, P1 -> P2 -> 명령 대역 래치 -> P3 이력과 두 파티션 획득을 만든 뒤 합성 명령 행 --rows 개(P2 구간 절반, P3 구간
절반, 마켓 12개)를 소유자가 직접 넣는다(S7 설계 3절 P7-7 과 같은 분포). 앱 enum(InvariantCheck.C22_FENCING_MONOTONIC)의 C22' 수 판을
권위 run 과 같은 BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY 안에서 SET LOCAL jit = off 뒤 EXPLAIN (ANALYZE, BUFFERS) 로 세 번
잰다. 판정은 세 번 모두 Execution Time 1,500ms 이하이고, 양성 축은 command_journal 1,000,000행 이상이다(모자라면 NOT RUN). 행 수,
command_journal 잎 수, 호스트 규격(docker info)을 함께 적는다. 문턱과 행 수 하한은 측정 뒤 고치지 않는다.

카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다. 같은 호스트의 다른 작업이 돌면 표본이 흔들린다.

사용:
  python harness/scale/s7_c22_cost.py --rows 1000000 --out <json>
종료코드: 0 PASS, 1 FAIL, 2 수집 실패, 3 NOT RUN(행 수 미달).
"""
import argparse
import json
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402
import s7_axes as s7  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / 'server' / 'app' / 'src' / 'main' / 'resources' / 'db' / 'migration'
INVARIANT_JAVA = ROOT / 'server' / 'app' / 'src' / 'main' / 'java' / 'kr' / 'exchange' / 'harness' / 'InvariantCheck.java'
IMAGE = 'postgres:17-alpine'
THRESHOLD_MS = 1500.0
MIN_ROWS = 1_000_000
RUNS = 3
C22_BLOCK = re.compile(r'C22_FENCING_MONOTONIC\(Severity\.CRITICAL,\s*Scope\.AUTHORITATIVE_ONLY,\s*"""(.*?)"""', re.S)
EXEC_TIME = re.compile(r'Execution Time:\s*([0-9.]+)\s*ms')
VERSION_FILE = re.compile(r'^V(\d+)__.+\.sql$')

SEED_SQL = """
INSERT INTO assets (id, symbol, name, kind, scale) SELECT g, 'A' || g, 'A' || g, CASE WHEN g = 1 THEN 'FIAT' ELSE 'CRYPTO' END, 8
  FROM generate_series(1, 13) g;
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) SELECT 'SYSTEM', 0, 'FEE_INCOME', g, 0 FROM generate_series(1, 13) g;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
SELECT g, 'KRW-A' || (g + 1), g + 1, 1, 1, 0.00000001, 0, 1000000000, 1000000 FROM generate_series(1, 12) g;
SELECT count(*) FROM set_leader_protocol(2::smallint);
SELECT try_acquire_lease('matching-engine', 'N1', interval '1 hour', 2::smallint);
SELECT latch_command_band_cutover();
SELECT count(*) FROM set_leader_protocol(3::smallint);
SELECT try_acquire_lease('matching-p0', 'N1', interval '1 hour', 3::smallint);
SELECT try_acquire_lease('matching-p1', 'N2', interval '1 hour', 3::smallint);
"""

ROWS_SQL = """
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts, tie_break,
                             payload, fencing_token, checksum, created_at)
SELECT m * 1000000000000 + (g / 12) + 1, m, 'CANCEL', 1, NULL, g, 0, 0, '{}'::jsonb,
       CASE WHEN g < %(n)d / 2 THEN (SELECT max(epoch) FROM lease_acquisitions WHERE protocol = 2 AND origin = 'ACQUIRE')
            WHEN m IN (1, 4, 5, 7, 9, 11) THEN (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0')
            ELSE (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1') END,
       0,
       CASE WHEN g < %(n)d / 2
            THEN (SELECT changed_at FROM leader_protocol_history WHERE protocol = 3) - interval '1 hour' + g * interval '1 ms'
            ELSE (SELECT changed_at FROM leader_protocol_history WHERE protocol = 3) + g * interval '1 ms' END
  FROM generate_series(0, %(n)d - 1) g CROSS JOIN LATERAL (SELECT (g %% 12 + 1)::smallint AS m) mm;
ANALYZE command_journal;
"""


# ---------------------------------------------------------------- 순수 함수(test_s7_executors.py)

def c22_sql(java_text):
    """InvariantCheck.java 의 C22 텍스트 블록(앱 enum 의 수 판). 없으면 ValueError 다."""
    m = C22_BLOCK.search(java_text or '')
    if not m:
        raise ValueError('InvariantCheck.java 에서 C22_FENCING_MONOTONIC 블록을 찾지 못했다')
    return m.group(1).strip()


def explain_script(sql):
    """권위 run 과 같은 읽기 전용 반복 읽기 트랜잭션 안의 EXPLAIN (ANALYZE, BUFFERS)."""
    return ('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET LOCAL jit = off;\nEXPLAIN (ANALYZE, BUFFERS)\n'
            + sql.rstrip().rstrip(';') + ';\nCOMMIT;\n')


def execution_ms(text):
    m = EXEC_TIME.search(text or '')
    return float(m.group(1)) if m else None


def migration_files(folder):
    """V<n>__*.sql 을 버전 숫자 순으로."""
    files = [p for p in Path(folder).iterdir() if VERSION_FILE.match(p.name)]
    return sorted(files, key=lambda p: int(VERSION_FILE.match(p.name).group(1)))


def cost_judge(rows, runs_ms):
    axes = {'rows_ge_1m': rows is not None and rows >= MIN_ROWS,
            'runs_measured': len(runs_ms) == RUNS and all(x is not None for x in runs_ms)}
    axes['all_runs_le_1500ms'] = axes['runs_measured'] and all(x <= THRESHOLD_MS for x in runs_ms)
    return axes, ['rows_ge_1m', 'runs_measured']


# ---------------------------------------------------------------- 채집

def sh(args, input_text=None, timeout=600):
    p = subprocess.run(args, input=input_text, capture_output=True, text=True, encoding='utf-8', errors='replace',
                       timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def psql(name, sql, single=False, timeout=1800):
    args = ['docker', 'exec', '-i', name, 'psql', '-X', '-U', 'postgres', '-d', 'probe', '-q', '-At', '-v', 'ON_ERROR_STOP=1']
    if single:
        args.append('-1')
    return sh(args, input_text=sql, timeout=timeout)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--rows', type=int, default=MIN_ROWS)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    name = 'pml-probe-s7q14-%s' % secrets.token_hex(4)
    res = {'started': pgs.now_iso(), 'container': name, 'image': IMAGE, 'rows_requested': a.rows, 'error': None}
    code = 2
    try:
        rc, info, _ = sh(['docker', 'info', '--format', '{{.NCPU}}|{{.MemTotal}}|{{.ServerVersion}}|{{.OperatingSystem}}'])
        res['host'] = info.strip() if rc == 0 else None
        rc, _, err = sh(['docker', 'run', '-d', '--rm', '--name', name, '--cpus', '1', '--shm-size', '256m',
                         '-e', 'POSTGRES_PASSWORD=probe', '-e', 'POSTGRES_DB=probe', IMAGE], timeout=300)
        if rc != 0:
            res['error'] = 'postgres 컨테이너 기동 실패: %s' % err.strip()[-200:]
            return code
        start = time.monotonic()
        while time.monotonic() - start < 180:
            _, logs, logs_err = sh(['docker', 'logs', name], timeout=30)
            if 'PostgreSQL init process complete' in logs + logs_err and sh(
                    ['docker', 'exec', name, 'pg_isready', '-U', 'postgres', '-d', 'probe'], timeout=30)[0] == 0:
                break
            time.sleep(1)
        else:
            res['error'] = 'postgres 준비 시간 초과'
            return code
        time.sleep(1)
        res['version'] = psql(name, 'SELECT version();')[1].strip()
        applied = 0
        for f in migration_files(MIGRATIONS):
            rc, _, err = psql(name, f.read_text(encoding='utf-8'), single=True)
            if rc != 0:
                res['error'] = '마이그레이션 실패 %s: %s' % (f.name, err.strip()[-300:])
                return code
            applied += 1
        res['migrations_applied'] = applied
        rc, out, err = psql(name, SEED_SQL)
        if rc != 0:
            res['error'] = '이력·획득 시드 실패: %s' % err.strip()[-300:]
            return code
        rc, _, err = psql(name, ROWS_SQL % {'n': a.rows})
        if rc != 0:
            res['error'] = '합성 행 적재 실패: %s' % err.strip()[-300:]
            return code
        rows = int(psql(name, 'SELECT count(*) FROM command_journal;')[1].strip() or 0)
        leaves = int(psql(name, "SELECT count(*) FROM pg_inherits WHERE inhparent = 'command_journal'::regclass;")[1].strip() or 0)
        res.update(rows=rows, leaves=leaves)
        sql = c22_sql(INVARIANT_JAVA.read_text(encoding='utf-8'))
        runs, plans = [], []
        for _ in range(RUNS):
            rc, out, err = psql(name, explain_script(sql))
            runs.append(execution_ms(out) if rc == 0 else None)
            plans.append(out[-2000:] if rc == 0 else err[-500:])
        res['runs_ms'] = runs
        res['plan_tail'] = plans[-1]
        axes, positive = cost_judge(rows, runs)
        verdict = s7.verdict_of(axes, positive)
        res.update(axes=axes, positive=positive, verdict=verdict, threshold_ms=THRESHOLD_MS, min_rows=MIN_ROWS,
                   failed=[k for k, v in axes.items() if not v])
        code = s7.exit_code(verdict)
        return code
    except Exception as e:  # noqa: BLE001
        res['error'] = '%s: %s' % (type(e).__name__, e)
        return 2
    finally:
        sh(['docker', 'stop', name], timeout=120)
        res['finished'] = pgs.now_iso()
        Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
        print(json.dumps({k: res.get(k) for k in ('rows', 'runs_ms', 'verdict', 'error')}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    sys.exit(main())
