"""ADR 0022 8단계 commit_delay 격자 칸 설정. ALTER SYSTEM SET 과 pg_reload_conf 로 걸고 SHOW 로 확인하며
ALTER SYSTEM RESET 으로 되돌린다.

docker exec chaos-pg 로만 붙어 카오스 스택 DB 에만 닿는다. 두 설정은 재적재로 바뀌어 재기동하지 않는다. 기존 세션은
다음 명령을 읽기 전에 재적재를 반영하므로 적용 확인은 새 세션의 SHOW 와 pg_settings 출처로 한다.
규약은 docs/contracts/하네스_검증설계.md §2-4 의 「ADR 0022 8단계 commit_delay 격자 판정 규약」 문단이다.
칸 값을 거는 쪽은 roundtrip_probe.py --commit-delay --commit-siblings 다.

돌리는 법:
  python harness/scale/commit_delay.py reset --out <json>

종료코드: 0 복구 확인, 1 명령 실패 또는 확인 실패.
"""
import argparse
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402

NAMES = ('commit_delay', 'commit_siblings')
# PostgreSQL 이 받는 범위. commit_delay 단위는 마이크로초다.
LIMITS = {'commit_delay': (0, 100000), 'commit_siblings': (0, 1000)}
AUTO_CONF = 'postgresql.auto.conf'
# pg_reload_conf 는 신호만 보내고 돌아와 새 세션이 재적재 전 값을 볼 수 있다.
CONFIRM_ATTEMPTS = 5
CONFIRM_WAIT_S = 1.0

SETTINGS_SQL = (
    "SELECT coalesce(json_agg(json_build_object('name', name, 'setting', setting, 'source', source, "
    "'sourcefile', sourcefile, 'pending_restart', pending_restart) ORDER BY name), '[]'::json) "
    "FROM pg_settings WHERE name IN ('commit_delay', 'commit_siblings')")
RELOAD_SQL = 'SELECT pg_reload_conf()'
RESET_SQLS = ('ALTER SYSTEM RESET commit_delay', 'ALTER SYSTEM RESET commit_siblings', RELOAD_SQL)


def valid(name, value):
    lo, hi = LIMITS[name]
    return isinstance(value, int) and not isinstance(value, bool) and lo <= value <= hi


def settings():
    """새 세션의 SHOW 두 값과 pg_settings 출처. 하나라도 못 읽으면 ok 가 거짓이다."""
    out = {'ok': False, 'error': None, 'commit_delay': None, 'commit_siblings': None, '출처': None,
           'ts': pgs.now_iso()}
    for name in NAMES:
        rc, stdout, stderr = pgs.run_psql('SHOW %s' % name)
        text = (stdout or '').strip()
        if rc != 0 or not re.fullmatch(r'[0-9]+', text):
            out['error'] = 'SHOW %s 실패(종료코드 %s): %s' % (name, rc, ((stderr or '') + text).strip()[-200:])
            return out
        out[name] = int(text)
    rows, err = pgs.parse_json_row(*pgs.run_psql(SETTINGS_SQL))
    if not isinstance(rows, list):
        out['error'] = err or 'pg_settings 결과가 목록이 아니다'
        return out
    out['출처'] = rows
    out['ok'] = True
    return out


def source_rows(state):
    return {r.get('name'): r for r in (state or {}).get('출처') or [] if isinstance(r, dict)}


def from_auto_conf(row):
    return isinstance(row, dict) and str(row.get('sourcefile') or '').endswith(AUTO_CONF)


def in_effect(state, delay, siblings):
    """두 값이 칸 값이고 ALTER SYSTEM 이 쓴 파일에서 왔으며 재기동을 기다리지 않을 때만 참이다."""
    state = state or {}
    if not state.get('ok') or state.get('commit_delay') != delay or state.get('commit_siblings') != siblings:
        return False
    rows = source_rows(state)
    return all(from_auto_conf(rows.get(n)) and rows[n].get('pending_restart') is False for n in NAMES)


def cleared(state):
    """두 설정 모두 postgresql.auto.conf 출처가 아니다. 곧 ALTER SYSTEM 이 남긴 값이 없다."""
    rows = source_rows(state)
    return bool((state or {}).get('ok')) and all(n in rows and not from_auto_conf(rows[n]) for n in NAMES)


def run_sql(sql):
    rc, _, stderr = pgs.run_psql(sql)
    return {'sql': sql, '종료코드': rc, '오류': (stderr or '').strip()[-300:], 'ts': pgs.now_iso()}


def confirm(check):
    """확인이 설 때까지 새 세션으로 다시 본다. 마지막 상태에 시도 수를 붙여 돌려준다."""
    for attempt in range(1, CONFIRM_ATTEMPTS + 1):
        state = settings()
        if check(state) or attempt == CONFIRM_ATTEMPTS:
            state['시도'] = attempt
            return state
        time.sleep(CONFIRM_WAIT_S)


def apply(delay, siblings):
    """칸 값을 건다. 실패해도 앞 명령의 값이 파일에 남을 수 있어 호출자는 결과와 무관하게 reset 을 부른다."""
    out = {'ok': False, 'error': None, '요청': {'commit_delay': delay, 'commit_siblings': siblings},
           '명령': [], '확인': None}
    bad = [n for n, v in zip(NAMES, (delay, siblings)) if not valid(n, v)]
    if bad:
        out['error'] = '범위 밖 값: %s' % ', '.join(bad)
        return out
    for sql in ('ALTER SYSTEM SET commit_delay = %d' % delay,
                'ALTER SYSTEM SET commit_siblings = %d' % siblings, RELOAD_SQL):
        rec = run_sql(sql)
        out['명령'].append(rec)
        if rec['종료코드'] != 0:
            out['error'] = '%s 실패(종료코드 %s)' % (sql, rec['종료코드'])
            return out
    out['확인'] = confirm(lambda s: in_effect(s, delay, siblings))
    if in_effect(out['확인'], delay, siblings):
        out['ok'] = True
    else:
        out['error'] = 'SHOW 가 칸 값이 아니거나 출처가 %s 가 아니다' % AUTO_CONF
    return out


def reset():
    """칸 값을 지운다. 한 명령이 실패해도 나머지와 재적재를 끝까지 부른다."""
    out = {'ok': False, 'error': None, '명령': [run_sql(sql) for sql in RESET_SQLS], '확인': None}
    out['확인'] = confirm(cleared)
    failed = [c['sql'] for c in out['명령'] if c['종료코드'] != 0]
    if failed:
        out['error'] = '실패한 명령: %s' % ', '.join(failed)
    elif not cleared(out['확인']):
        out['error'] = '재적재 뒤에도 %s 출처가 남았거나 설정을 읽지 못했다' % AUTO_CONF
    else:
        out['ok'] = True
    return out


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    sub.add_parser('reset').add_argument('--out', required=True)
    a = ap.parse_args()
    result = reset()
    pgs.write_json(a.out, result)
    print('커밋 설정 복구 %s' % ('성공' if result['ok'] else '실패: %s' % result['error']), flush=True)
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
