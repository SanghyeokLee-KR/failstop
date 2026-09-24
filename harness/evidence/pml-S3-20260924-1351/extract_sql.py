"""S3 관문의 문장 시간 측정(X1 기록, X7 판정)이 쓰는 SQL 을 코드에서 그대로 뽑는다.

문서 사본이 아니라 실제로 도는 문장을 재려는 것이다.
  c14p.sql      앱 InvariantCheck.C14_LOCKED_RESERVATION(C14' 커서 판, 상시 스케줄)
  c31app.sql    앱 InvariantCheck.C31_RESERVATION_LINK(권위 run 한정, 마켓마다 최근 5,000건 창)
  c05.sql       앱 InvariantCheck.C05_CMD_GAPLESS(기록용)
  gauge.sql     ReservationBacklogMonitor.QUERY(정산 전 예약 게이지)
  c31full.sql   harness/sql/invariants.sql 의 C31 전수 판을 count(*) 로 감싼 것
  precond.sql   V50 set_leader_protocol 의 선행 조건(정산되지 않은 NEW 수, 전수)

사용: python extract_sql.py <출력 폴더>
"""
import hashlib
import json
import re
import sys
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / 'server/app/src/main/java/kr/exchange'


def enum_block(text, name):
    m = re.search(re.escape(name) + r'\(Severity\.\w+, Scope\.\w+, """\n(.*?)"""\)', text, re.S)
    if not m:
        raise SystemExit('InvariantCheck 에서 %s 를 찾지 못했다' % name)
    return textwrap.dedent(m.group(1)).strip() + '\n'


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    inv = (JAVA / 'harness/InvariantCheck.java').read_text(encoding='utf-8')
    mon = (JAVA / 'ledger/ReservationBacklogMonitor.java').read_text(encoding='utf-8')
    harness = (ROOT / 'harness/sql/invariants.sql').read_text(encoding='utf-8')
    files = {
        'c14p.sql': enum_block(inv, 'C14_LOCKED_RESERVATION'),
        'c31app.sql': enum_block(inv, 'C31_RESERVATION_LINK'),
        'c05.sql': enum_block(inv, 'C05_CMD_GAPLESS'),
    }
    m = re.search(r'static final String QUERY = """\n(.*?)""";', mon, re.S)
    if not m:
        raise SystemExit('ReservationBacklogMonitor.QUERY 를 찾지 못했다')
    files['gauge.sql'] = textwrap.dedent(m.group(1)).strip() + '\n'
    start = harness.index("SELECT 'reserve_ledger_mismatch'")
    end = harness.index('\\elif :no_reservations', start)
    body = harness[start:end].strip().rstrip(';')
    files['c31full.sql'] = 'SELECT count(*) FROM (\n' + body + '\n) x\n'
    files['precond.sql'] = ("SELECT count(*) FROM command_journal c\n WHERE c.command_type = 'NEW'\n"
                            "   AND NOT EXISTS (SELECT 1 FROM event_emission_registry e"
                            " WHERE e.source_cmd_seq = c.seq)\n")
    sums = {}
    for name, sql in files.items():
        (out / name).write_text(sql, encoding='utf-8', newline='\n')
        sums[name] = hashlib.sha256(sql.encode('utf-8')).hexdigest()
    (out / 'sql-sha256.json').write_text(json.dumps(sums, indent=2), encoding='utf-8')
    print(json.dumps(sums, indent=2))


if __name__ == '__main__':
    main()
