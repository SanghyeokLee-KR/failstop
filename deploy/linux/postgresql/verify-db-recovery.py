"""새 Primary의 현재 데이터를 같은 스냅샷으로 검사하고 결과만 남긴다."""
import csv
import datetime as dt
import io
import json
from pathlib import Path
import subprocess


# summary.sql 판정 어휘(하네스 검증설계 §4-1 '판정 어휘', ADR 0034 S3). 이 파일을 쓰는 복원·복구 점검 여섯 곳이 같은 뜻으로
# 둔다. N/A 는 그 판정이 읽는 마이그레이션 객체가 모두 없는 행이라 통과로도 실패로도 세지 않고 따로 적는다. N/A 를 낼 수 있는
# 코드는 C29·C30(V48 객체)과 C31(V50 객체)뿐이다(C30 은 ADR 0034 S7). 그 밖의 N/A 는 형식 오류다. 미평가 SKIP 은 종전대로 실패다.
VERDICTS = ("PASS", "FAIL", "SKIP", "N/A")
NOT_APPLICABLE_CODES = ("C29", "C30", "C31")


def judge_invariant_rows(rows):
    """summary.sql 판정 행을 가른다. 형식 오류는 ValueError 다.

    failed 는 CRITICAL·HIGH 행 가운데 FAIL·SKIP 인 코드, not_applicable 은 N/A 코드다. N/A 는 통과 목록에 넣지 않는다.
    """
    if len(rows) < 28:
        raise ValueError("invariant catalogue is incomplete: %d rows" % len(rows))
    for row in rows:
        verdict = row.get("verdict")
        if verdict not in VERDICTS:
            raise ValueError("unknown invariant verdict: %r" % (verdict,))
        if verdict == "N/A" and row.get("code") not in NOT_APPLICABLE_CODES:
            raise ValueError("N/A verdict outside C29/C30/C31: %r" % (row.get("code"),))
    failed = [row["code"] for row in rows
              if row.get("severity") in ("CRITICAL", "HIGH") and row["verdict"] in ("FAIL", "SKIP")]
    not_applicable = [row["code"] for row in rows if row["verdict"] == "N/A"]
    return {"failed": failed, "not_applicable": not_applicable}


def main():
    sql = Path('/tmp/summary.sql').read_text()
    command = ['runuser', '-u', 'postgres', '--', 'psql', '-X', '-q', '--csv',
               '-v', 'ON_ERROR_STOP=1', '-d', 'exchange']
    output = subprocess.check_output(command, input='BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' + sql
                                     + '\nCOMMIT;\n', text=True)
    rows = list(csv.DictReader(io.StringIO(output)))
    try:
        verdicts = judge_invariant_rows(rows)
    except ValueError as error:
        raise SystemExit('정합성 검사 결과 형식 오류: ' + str(error))
    failed = verdicts['failed']
    root = Path('/var/lib/pgsql/recovery-check-20260920')
    root.mkdir(mode=0o700, exist_ok=True)
    (root / 'invariants.csv').write_text(output)
    result = {'completed_at': dt.datetime.now(dt.timezone.utc).isoformat(),
              'invariant_count': len(rows), 'failed_required': failed,
              'not_applicable': verdicts['not_applicable'],
              'status': 'PASS' if not failed else 'FAIL',
              'scope': '동일 스냅샷의 DB 정합성. 실제 장애의 RPO 보장은 아님.'}
    (root / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
