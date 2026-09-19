"""새 Primary의 현재 데이터를 같은 스냅샷으로 검사하고 결과만 남긴다."""
import csv
import datetime as dt
import io
import json
from pathlib import Path
import subprocess

sql = Path('/tmp/summary.sql').read_text()
command = ['runuser', '-u', 'postgres', '--', 'psql', '-X', '-q', '--csv',
           '-v', 'ON_ERROR_STOP=1', '-d', 'exchange']
output = subprocess.check_output(command, input='BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' + sql + '\nCOMMIT;\n', text=True)
rows = list(csv.DictReader(io.StringIO(output)))
if len(rows) < 28 or any(r.get('verdict') not in ('PASS', 'FAIL', 'SKIP') for r in rows):
    raise SystemExit('정합성 검사 결과 형식 오류')
failed = [r['code'] for r in rows if r['severity'] in ('CRITICAL', 'HIGH') and r['verdict'] != 'PASS']
root = Path('/var/lib/pgsql/recovery-check-20260920')
root.mkdir(mode=0o700, exist_ok=True)
(root / 'invariants.csv').write_text(output)
result = {'completed_at': dt.datetime.now(dt.timezone.utc).isoformat(),
          'invariant_count': len(rows), 'failed_required': failed,
          'status': 'PASS' if not failed else 'FAIL',
          'scope': '동일 스냅샷의 DB 정합성. 실제 장애의 RPO 보장은 아님.'}
(root / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result, ensure_ascii=False, indent=2))
if failed:
    raise SystemExit(1)
