"""감사 문장의 실행 시간(S3 설계 7-3 X1 기록, X7 판정).

권위 run 과 같은 BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY 안에서 SET LOCAL jit 를 켬·끔으로 두고
EXPLAIN (ANALYZE, BUFFERS) 를 두 번 돌려 둘째 Execution Time 을 쓴다. 결과값도 같은 트랜잭션 밖에서 한 번 읽어 남긴다
(판정 값이 아니라 문장이 무엇을 셌는지 보이려는 것이다). 컨테이너 안 psql 로만 붙는다.

사용: python stmt_timing.py --container <이름> --user <롤> --db <DB> --sql-dir <폴더> --out <json> [--plans <폴더>]
"""
import argparse
import json
import re
import subprocess
from pathlib import Path

ORDER = ('c14p', 'c31app', 'gauge', 'c31full', 'precond', 'c05')
EXEC = re.compile(r'Execution Time: ([0-9.]+) ms')


def psql(container, user, db, script, timeout):
    p = subprocess.run(['docker', 'exec', '-i', container, 'psql', '-X', '-U', user, '-d', db, '-At',
                        '-v', 'ON_ERROR_STOP=1', '-f', '-'], input=script, capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--container', required=True)
    ap.add_argument('--user', required=True)
    ap.add_argument('--db', required=True)
    ap.add_argument('--sql-dir', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--plans', default=None)
    ap.add_argument('--timeout', type=int, default=3600)
    a = ap.parse_args()
    sql_dir = Path(a.sql_dir)
    plans = Path(a.plans) if a.plans else None
    if plans:
        plans.mkdir(parents=True, exist_ok=True)
    res = {}
    for name in ORDER:
        f = sql_dir / (name + '.sql')
        if not f.exists():
            continue
        stmt = f.read_text(encoding='utf-8').strip().rstrip(';')
        row = {}
        rc, out, err = psql(a.container, a.user, a.db, stmt + ';\n', a.timeout)
        row['value'] = out.strip() if rc == 0 else None
        row['value_error'] = None if rc == 0 else err.strip()[-300:]
        for jit in ('on', 'off'):
            script = ('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET LOCAL jit = %s;\n'
                      'EXPLAIN (ANALYZE, BUFFERS) %s;\nEXPLAIN (ANALYZE, BUFFERS) %s;\nCOMMIT;\n' % (jit, stmt, stmt))
            rc, out, err = psql(a.container, a.user, a.db, script, a.timeout)
            times = [float(x) for x in EXEC.findall(out)] if rc == 0 else []
            row['jit_' + jit] = {'rc': rc, 'first_ms': times[0] if len(times) > 0 else None,
                                 'second_ms': times[1] if len(times) > 1 else None,
                                 'error': None if rc == 0 else err.strip()[-300:]}
            if plans and rc == 0:
                (plans / ('%s-jit-%s.txt' % (name, jit))).write_text(out, encoding='utf-8')
        res[name] = row
        print('%-8s value=%s jit_on=%s jit_off=%s' % (name, row['value'], row['jit_on']['second_ms'],
                                                     row['jit_off']['second_ms']), flush=True)
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
