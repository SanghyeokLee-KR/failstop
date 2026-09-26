"""X1 권위 run 500 의 원인 찾기: 앱 역할 로그인으로 InvariantCheck 의 모든 문장을 권위 run 과 같은 트랜잭션에서 돌린다.

폐기용 postgres:17-alpine 컨테이너 하나(--rm, 127.0.0.1 무작위 포트, 이름 pml-probe-s3priv-*)에 저장소 V0~V50 을 올리고
배포와 같은 방식으로 app_role 에 속한 로그인을 만든 뒤, 그 로그인으로 체크마다 BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL jit = off; <문장>; COMMIT 을 돌린다. 권위 run 의 적재(INSERT invariant_checks)와 게이지 두 문장도 돌린다.
카오스 스택과 보존 볼륨은 건드리지 않는다.

사용: python approle_check_probe.py <출력 json>
"""
import json
import random
import re
import subprocess
import sys
import textwrap
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MIG = ROOT / 'server/app/src/main/resources/db/migration'
INV = ROOT / 'server/app/src/main/java/kr/exchange/harness/InvariantCheck.java'
NAME = 'pml-probe-s3priv-%d' % random.randint(10 ** 6, 10 ** 7)


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def psql(user, sql, db='probe'):
    return sh(['docker', 'exec', '-i', NAME, 'psql', '-X', '-U', user, '-d', db, '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
              input=sql, timeout=300)


def checks():
    text = INV.read_text(encoding='utf-8')
    out = []
    for m in re.finditer(r'\n    (C\w+)\(Severity\.(\w+), Scope\.(\w+),\s*(?:"""\n(.*?)"""|((?:"[^"\n]*"\s*\+?\s*)+))\)', text, re.S):
        if m.group(4) is not None:
            sql = textwrap.dedent(m.group(4)).strip()
        else:
            sql = ''.join(re.findall(r'"([^"\n]*)"', m.group(5)))
        out.append((m.group(1), m.group(2), m.group(3), sql))
    return out


def main():
    out_path = Path(sys.argv[1])
    res = {'container': NAME, 'checks': {}}
    sh(['docker', 'run', '-d', '--rm', '--name', NAME, '-e', 'POSTGRES_PASSWORD=probe', '-e', 'POSTGRES_DB=probe',
        '-p', '127.0.0.1::5432', 'postgres:17-alpine'])
    try:
        for _ in range(60):
            if 'PostgreSQL init process complete' in sh(['docker', 'logs', NAME]).stderr + sh(['docker', 'logs', NAME]).stdout:
                break
            time.sleep(1)
        for _ in range(60):
            if sh(['docker', 'exec', NAME, 'pg_isready', '-U', 'postgres', '-d', 'probe']).returncode == 0:
                break
            time.sleep(1)
        time.sleep(1)
        files = sorted(MIG.glob('V*.sql'), key=lambda p: int(re.match(r'V(\d+)__', p.name).group(1)))
        for f in files:
            r = psql('postgres', 'BEGIN;\n' + f.read_text(encoding='utf-8') + '\nCOMMIT;\n')
            if r.returncode != 0:
                res['migration_error'] = {'file': f.name, 'stderr': r.stderr[-400:]}
                raise SystemExit('migration failed: %s' % f.name)
        res['migrations'] = len(files)
        r = psql('postgres', 'CREATE ROLE probe_app LOGIN; GRANT app_role TO probe_app;')
        res['login'] = {'rc': r.returncode, 'stderr': r.stderr.strip()}
        for name, sev, scope, sql in checks():
            script = ('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET LOCAL jit = off;\n%s;\nCOMMIT;\n' % sql.rstrip(';'))
            r = psql('probe_app', script)
            res['checks'][name] = {'severity': sev, 'scope': scope, 'rc': r.returncode,
                                  'error': r.stderr.strip()[-300:] if r.returncode != 0 else None}
        extra = {
            'insert_invariant_checks': "INSERT INTO invariant_checks (check_name, ok, violated_rows, details) "
                                       "VALUES ('probe', true, 0, '{}'::jsonb);",
            'ledger_gauge': 'SELECT asset_id, SUM(amount) AS net FROM ledger_entries GROUP BY asset_id;',
            'band_gauge': 'SELECT name, market_id, remaining FROM v_journal_band_headroom;',
            'watermarks': 'SELECT COALESCE(max(seq), 0) FROM command_journal; '
                          'SELECT COALESCE(max(event_seq), 0) FROM event_journal;',
        }
        for k, sql in extra.items():
            r = psql('probe_app', sql)
            res['checks'][k] = {'rc': r.returncode, 'error': r.stderr.strip()[-300:] if r.returncode != 0 else None}
    finally:
        sh(['docker', 'stop', NAME])
    failed = {k: v for k, v in res['checks'].items() if v['rc'] != 0}
    res['failed'] = failed
    out_path.write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    print('checks', len(res['checks']), 'failed', json.dumps(failed, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
