"""ADR 0022 6단계 대역 채번 커트오버와 9단계 이벤트 대역 확대의 DB 쪽 조작과 조회. 래치, 래치 상태, 마켓별 대역 행,
C28 값, 이벤트 축 확대 식별.

래치는 되돌릴 수 없다(ADR 0022 7절 6-b). docker exec chaos-pg 로만 붙으므로 볼륨을 파기하고 다시 세우는 카오스
스택 DB 에만 닿는다. C28 은 같은 절 섞임 감사 술어를 옮긴 측정용 질의다. band_span 은 journal_band_spec 에서
읽고, 명세 행이 없으면 레거시와 대역을 가를 수 없어 값을 내지 않는다. state 의 이벤트 대역 절은 하네스 검증설계
§2-4 의 9단계 확대 모드 분리 판정이 읽는다.

돌리는 법:
  python harness/scale/band_cutover.py latch --out <json>
  python harness/scale/band_cutover.py state --out <json>

종료코드: 0 성공, 1 래치 거부·실패 또는 조회 실패.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402

LATCH_SQL = 'SELECT latch_command_band_cutover()'

PRECHECK_SQL = """
SELECT json_build_object(
  'function', to_regprocedure('latch_command_band_cutover()')::text,
  'owner', (SELECT pg_get_userbyid(p.proowner) FROM pg_proc p
            WHERE p.oid = to_regprocedure('latch_command_band_cutover()')),
  'current_user', current_user,
  'app_role_execute', CASE WHEN to_regprocedure('latch_command_band_cutover()') IS NOT NULL
                            AND to_regrole('app_role') IS NOT NULL
                       THEN has_function_privilege('app_role',
                              to_regprocedure('latch_command_band_cutover()')::oid, 'EXECUTE') END,
  'journal_rows', (SELECT count(*) FROM command_journal))
"""

STATE_SQL = "SELECT json_build_object('rows', (SELECT coalesce(json_agg(j), '[]'::json) FROM journal_cutover j))"

BAND_SPEC_MISSING = 'journal_band_spec 에 command_seq 행이 없어 레거시와 대역을 가를 수 없다'
EVENT_BAND_SPEC_MISSING = 'journal_band_spec 에 event_seq 행이 없어 레거시와 대역을 가를 수 없다'

# V46 이 올린 이벤트 축 명세(band_span, max_band_index). 확대 식별은 명세가 이 값이고 이벤트 행이 있는 마켓마다 대역 행이
# 있을 때 성립한다.
WIDENED_EVENT_SPEC = (10 ** 12, 63)

# 창을 주면 created_at 이 그 창인 행만 센다. 창 시각은 호출자가 DB 시계 형식으로 검증한 값이다.
BAND_ROWS_SQL = """
WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'),
c AS (SELECT market_id,
             count(*) FILTER (WHERE seq >= (SELECT band_span FROM s)) AS band,
             count(*) FILTER (WHERE seq < (SELECT band_span FROM s)) AS legacy
      FROM command_journal %s GROUP BY market_id)
SELECT json_build_object(
  'band_span', (SELECT band_span FROM s),
  'markets', (SELECT coalesce(json_agg(json_build_object('id', m.id, 'symbol', m.symbol,
                'band_rows', coalesce(c.band, 0), 'legacy_rows', coalesce(c.legacy, 0)) ORDER BY m.id), '[]'::json)
              FROM markets m LEFT JOIN c ON c.market_id = m.id))
"""

# legacy_after_band 가 0 보다 크면 그 마켓의 첫 대역 행보다 늦은 레거시 행이 있다. 마켓 커서 아래로 무음
# 폐기될 명령이다. 대역 행이 없는 마켓은 대상이 아니다.
C28_SQL = """
WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'),
b AS (SELECT market_id, min(created_at) AS first_band FROM command_journal
      WHERE seq >= (SELECT band_span FROM s) GROUP BY market_id)
SELECT json_build_object(
  'band_span', (SELECT band_span FROM s),
  'markets', (SELECT coalesce(json_agg(json_build_object('market_id', b.market_id,
                'first_band_created_at', b.first_band,
                'legacy_after_band', (SELECT count(*) FROM command_journal c
                                      WHERE c.market_id = b.market_id AND c.seq < (SELECT band_span FROM s)
                                        AND c.created_at > b.first_band)) ORDER BY b.market_id), '[]'::json)
              FROM b))
"""

# 이벤트 축. legacy_after_band 는 그 마켓 첫 대역 행보다 큰 market_seq 를 가진 레거시 행(event_seq < band_span) 수다.
# 확대 뒤 앱 파생 판이 쓴 행이고 마켓 안 event_seq 가 되돌아간 흔적이다. market_seq 로 가르므로 시계를 읽지 않는다.
# derivation_mismatch 는 대역 행 가운데 market_id * band_span + market_seq 가 아닌 행 수다. V36 이전 행은 market_id 가 비어 빠진다.
EVENT_BAND_SQL = """
WITH s AS (SELECT band_span, max_band_index FROM journal_band_spec WHERE name = 'event_seq'),
e AS (SELECT market_id,
             count(*) FILTER (WHERE event_seq >= (SELECT band_span FROM s)) AS band,
             count(*) FILTER (WHERE event_seq < (SELECT band_span FROM s)) AS legacy,
             min(market_seq) FILTER (WHERE event_seq >= (SELECT band_span FROM s)) AS first_band,
             count(*) FILTER (WHERE event_seq >= (SELECT band_span FROM s)
                              AND event_seq <> market_id * (SELECT band_span FROM s) + market_seq) AS mismatch
      FROM event_journal WHERE market_id IS NOT NULL GROUP BY market_id),
l AS (SELECT e.market_id, count(*) AS legacy_after_band
      FROM e JOIN event_journal j ON j.market_id = e.market_id
      WHERE j.event_seq < (SELECT band_span FROM s) AND j.market_seq > e.first_band
      GROUP BY e.market_id)
SELECT json_build_object(
  'band_span', (SELECT band_span FROM s),
  'max_band_index', (SELECT max_band_index FROM s),
  'markets', (SELECT coalesce(json_agg(json_build_object('market_id', e.market_id,
                'band_rows', e.band, 'legacy_rows', e.legacy, 'first_band_market_seq', e.first_band,
                'legacy_after_band', coalesce(l.legacy_after_band, 0),
                'derivation_mismatch', e.mismatch) ORDER BY e.market_id), '[]'::json)
              FROM e LEFT JOIN l ON l.market_id = e.market_id))
"""


def latch_state():
    parsed, err = pgs.parse_json_row(*pgs.run_psql(STATE_SQL))
    rows = parsed.get('rows') if parsed is not None else None
    return {'ok': isinstance(rows, list), 'error': err, '래치 행': rows, 'ts': pgs.now_iso()}


def latch():
    """함수 소유자 계정일 때만 래치를 건다. 호출 전 저널 행 수로 트래픽 전이었는지를 남긴다."""
    pre, err = pgs.parse_json_row(*pgs.run_psql(PRECHECK_SQL))
    out = {'ok': False, 'error': err, '사전': pre, '호출': None, '상태': None}
    if pre is None:
        return out
    if not pre.get('function'):
        out['error'] = 'latch_command_band_cutover() 가 없다'
        return out
    if pre.get('owner') != pre.get('current_user'):
        out['error'] = '호출 계정 %s 가 함수 소유자 %s 가 아니다' % (pre.get('current_user'), pre.get('owner'))
        return out
    rc, stdout, stderr = pgs.run_psql(LATCH_SQL, timeout=60)
    out['호출'] = {'sql': LATCH_SQL, '종료코드': rc, '출력': (stdout or '').strip(),
                  '오류': (stderr or '').strip()[-500:], 'ts': pgs.now_iso()}
    out['상태'] = latch_state()
    if rc != 0:
        out['error'] = 'psql 종료코드 %s' % rc
    elif not out['상태']['래치 행']:
        out['error'] = '래치 호출 뒤 journal_cutover 행이 없다'
    else:
        out['ok'], out['error'] = True, None
    return out


def band_rows(window=None):
    """마켓별 대역 행(seq >= band_span)과 레거시 행 수. window 가 (from, to) 면 그 창만 센다."""
    out = {'창': None, 'band_span': None, '마켓': None, 'error': None}
    where = ''
    if window is not None:
        t0, t1 = window
        out['창'] = {'from': t0, 'to': t1}
        if not (t0 and t1):
            out['error'] = '주행 창 시각이 없다'
            return out
        where = "WHERE created_at >= '%s' AND created_at < '%s'" % (t0, t1)
    parsed, err = pgs.parse_json_row(*pgs.run_psql(BAND_ROWS_SQL % where, timeout=120))
    if parsed is None:
        out['error'] = err
    elif parsed.get('band_span') is None:
        out['error'] = BAND_SPEC_MISSING
    else:
        out.update({'band_span': parsed['band_span'], '마켓': parsed.get('markets')})
    return out


def c28():
    parsed, err = pgs.parse_json_row(*pgs.run_psql(C28_SQL, timeout=120))
    out = {'ok': False, 'error': err, 'band_span': None, '위반 행': None, '마켓': None}
    if parsed is None:
        return out
    if parsed.get('band_span') is None:
        out['error'] = BAND_SPEC_MISSING
        return out
    markets = parsed.get('markets')
    out.update({'ok': True, 'band_span': parsed['band_span'], '마켓': markets,
                '위반 행': sum(m['legacy_after_band'] for m in markets)})
    return out


def event_band():
    """이벤트 축 명세, 마켓별 대역·레거시 행, 섞임과 파생 불일치, 확대 식별. 명세 행이 없으면 값을 내지 않는다."""
    parsed, err = pgs.parse_json_row(*pgs.run_psql(EVENT_BAND_SQL, timeout=120))
    out = {'ok': False, 'error': err, 'band_span': None, 'max_band_index': None, '마켓': None,
           '섞임 행': None, '파생 불일치 행': None, '확대 식별': None}
    if parsed is None:
        return out
    if parsed.get('band_span') is None:
        out['error'] = EVENT_BAND_SPEC_MISSING
        return out
    markets = parsed.get('markets')
    spec = (parsed['band_span'], parsed.get('max_band_index'))
    without_band = [m['market_id'] for m in markets if m['band_rows'] == 0]
    out.update({'ok': True, 'band_span': spec[0], 'max_band_index': spec[1], '마켓': markets,
                '섞임 행': sum(m['legacy_after_band'] for m in markets),
                '파생 불일치 행': sum(m['derivation_mismatch'] for m in markets),
                '확대 식별': {'명세 확대': spec == WIDENED_EVENT_SPEC, '이벤트 행 마켓 수': len(markets),
                            '대역 행 없는 마켓': without_band,
                            '성립': spec == WIDENED_EVENT_SPEC and bool(markets) and not without_band}})
    return out


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    sub.add_parser('latch').add_argument('--out', required=True)
    sub.add_parser('state').add_argument('--out', required=True)
    a = ap.parse_args()
    if a.cmd == 'latch':
        result = latch()
        ok = result['ok']
        print('래치 %s' % ('성공' if ok else '실패: %s' % result['error']), flush=True)
    else:
        result = {'래치': latch_state(), '대역 행': band_rows(), 'C28': c28(), '이벤트 대역': event_band()}
        event = result['이벤트 대역']
        ok = (result['래치']['ok'] and result['대역 행']['error'] is None and result['C28']['ok'] and event['ok'])
        print('래치 행 %s, C28 %s, 이벤트 band_span %s 섞임 %s 파생 불일치 %s' % (
            result['래치']['래치 행'], result['C28']['위반 행'], event['band_span'], event['섞임 행'],
            event['파생 불일치 행']), flush=True)
    pgs.write_json(a.out, result)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
