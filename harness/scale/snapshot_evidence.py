"""ADR 0022 7단계 스냅샷 경계 재생 주행의 채집. 실험 스크립트 전(pre)과 뒤(post)에 스냅샷·복구 증거를 JSON 으로 뜬다.

근거는 docs/contracts/하네스_검증설계.md §2-4 의 「ADR 0022 7단계 스냅샷 경계 재생 판정 규약」이다. 판정은 하지 않고
stage7_judge.py 가 한다. docker exec chaos-pg, docker logs, docker inspect, 노드 actuator 로만 붙으므로 카오스 스택에만 닿는다.

컨테이너 로그는 정해진 로거 이름과 문구가 한 줄에 함께 있을 때만 남긴다. 요청 식별자, 계정, 비밀값이 든 줄을 증거에 싣지
않으려는 것이다. 환경변수도 EXCHANGE_ENGINE_SNAPSHOT_ 접두만 남긴다.

돌리는 법:
  python harness/scale/snapshot_evidence.py pre --dir <실험 증거 폴더> --api chaos-api-1=18080,chaos-api-2=28080 [--wait-sec 180]
  python harness/scale/snapshot_evidence.py post --dir <실험 증거 폴더> --api chaos-api-1=18080,chaos-api-2=28080

산출물은 <폴더>/snapshot_pre.json, <폴더>/snapshot_post.json 이다.
종료코드: 0 채집이 모두 성공(pre 는 전제 충족까지), 1 그 밖. 실험을 막지 않으므로 절차는 이 값을 기록만 한다.
"""
import argparse
import http.client
import json
import math
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402

# 로거 단순 이름과 문구가 한 줄에 함께 있어야 남긴다. 예외 스택 줄은 로거 이름만 담고 문구가 없어 빠진다.
LOG_ALLOW = (
    ('EngineRecovery', ('스냅샷 채택(마켓별)', '재생 적용(마켓별)', '재생 1단계', '재생 2단계',
                        '스냅샷 적재 실패', '스냅샷 시작 재생 실패', '크래시 창 이벤트 재적재')),
    ('PromotionCoordinator', ('승격 재생',)),
    ('SnapshotStore', ('스냅샷 폐기',)),
    ('EngineConfig', ('명령 채번 축',)),
)
LOG_LINE_MAX = 2000
METRIC_PREFIXES = ('exchange_snapshot_', 'exchange_recovery_', 'exchange_journal_replay_commands',
                   'exchange_journal_band_remaining')
ENV_PREFIX = 'EXCHANGE_ENGINE_SNAPSHOT_'

ADOPTED = '스냅샷 채택(마켓별)'
APPLIED = '재생 적용(마켓별)'
# 엔진 기동 lifecycle 이 부팅 복구 직전에 한 번 남기는 줄이다. 콜드 재기동 칸의 부팅 복구 구간 시작점이다.
BOOT = '명령 채번 축:'
FALLBACK_PHRASES = ('스냅샷 적재 실패', '스냅샷 시작 재생 실패')

# EngineRecovery·PromotionCoordinator 의 로그 문구다. 대괄호와 중괄호 안은 Java List·Map 의 toString 형식이다.
ADOPTED_RE = re.compile(r'스냅샷 채택\(마켓별\): \[([^\]]*)\]')
APPLIED_RE = re.compile(r'재생 적용\(마켓별\): \{([^}]*)\}')
PROMOTION_RE = re.compile(r'승격 재생 (완료|생략\(모든 마켓 head 도달\), 라이터에 설치): epoch=(\d+), '
                          r'마켓 커서=\{([^}]*)\}, head=\{([^}]*)\}')
BOUNDARY_RE = re.compile(r'승격 재생 (완료|생략|실패, 재시도한다|실패, 리더십을 반납한다)')
ADOPTED_ITEM = re.compile(r'(\d+)@(\d+)')
APPLIED_ITEM = re.compile(r'(\d+)=(\d+)\.\.(\d+) (\d+)건')
MAP_ITEM = re.compile(r'(\d+)=(\d+)')
METRIC_LINE = re.compile(r'([A-Za-z_:][A-Za-z0-9_:]*)(\{[^}]*\})?\s+(\S+)')
METRIC_LABEL = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)="((?:[^"\\]|\\.)*)"')

PRECONDITION_SQL = """
SELECT json_build_object(
  'active', (SELECT coalesce(json_agg(a.market_id ORDER BY a.market_id), '[]'::json)
             FROM (SELECT DISTINCT market_id FROM command_journal) a),
  'missing', (SELECT coalesce(json_agg(a.market_id ORDER BY a.market_id), '[]'::json)
              FROM (SELECT DISTINCT market_id FROM command_journal) a
              WHERE NOT EXISTS (SELECT 1 FROM snapshots s
                                WHERE s.market_id = a.market_id AND s.last_applied_seq > 0)))
"""

ACTIVE_SQL = """
SELECT coalesce(json_agg(json_build_object('market_id', market_id, 'rows', n, 'head', head) ORDER BY market_id),
                '[]'::json)
FROM (SELECT market_id, count(*) AS n, max(seq) AS head FROM command_journal GROUP BY market_id) j
"""

# 상태 바이트는 싣지 않고 길이만 남긴다.
SNAPSHOTS_SQL = """
SELECT coalesce(json_agg(json_build_object('snapshot_id', snapshot_id, 'market_id', market_id,
  'last_applied_seq', last_applied_seq, 'fencing_epoch', fencing_epoch, 'engine_version', engine_version,
  'config_hash', config_hash, 'format_version', format_version, 'state_bytes', octet_length(state),
  'created_at', created_at) ORDER BY snapshot_id), '[]'::json)
FROM snapshots
"""

RECOVERY_RUNS_SQL = """
SELECT coalesce(json_agg(json_build_object('id', id, 'market_id', market_id,
  'replayed_from_seq', replayed_from_seq, 'replayed_to_seq', replayed_to_seq, 'fp_replay', fp_replay,
  'fp_live', fp_live, 'matched', matched, 'duration_ms', duration_ms, 'created_at', created_at) ORDER BY id),
  '[]'::json)
FROM recovery_runs
"""


def parse_api(text):
    """'chaos-api-1=18080,chaos-api-2=28080' 을 [(컨테이너, 포트)] 로."""
    out = []
    for part in text.split(','):
        name, _, port = part.strip().partition('=')
        if not name or not port.isdigit():
            raise ValueError('--api 는 컨테이너=포트 목록이다: %s' % text)
        out.append((name, int(port)))
    return out


def _items(body, pattern, what):
    body = body.strip()
    if not body:
        return []
    out = []
    for part in body.split(', '):
        m = pattern.fullmatch(part.strip())
        if not m:
            raise ValueError('%s 항목을 읽지 못했다: %s' % (what, part[:80]))
        out.append(tuple(int(g) for g in m.groups()))
    return out


def _by_market(items, what):
    out = {}
    for key, *value in items:
        if key in out:
            raise ValueError('%s 에 마켓 %d 가 두 번 있다' % (what, key))
        out[key] = value[0] if len(value) == 1 else tuple(value)
    return out


def parse_adopted(text):
    """스냅샷 채택 줄이면 {마켓: 채택 seq}, 아니면 None."""
    if ADOPTED not in text:
        return None
    m = ADOPTED_RE.search(text)
    if not m:
        raise ValueError('스냅샷 채택 줄 형식이 다르다: %s' % text[-120:])
    return _by_market(_items(m.group(1), ADOPTED_ITEM, '스냅샷 채택'), '스냅샷 채택')


def parse_applied(text):
    """재생 적용 줄이면 {마켓: (시작 커서, 종료 커서, 적용 건수)}, 아니면 None."""
    if APPLIED not in text:
        return None
    m = APPLIED_RE.search(text)
    if not m:
        raise ValueError('재생 적용 줄 형식이 다르다: %s' % text[-120:])
    return _by_market(_items(m.group(1), APPLIED_ITEM, '재생 적용'), '재생 적용')


def parse_promotion(text):
    """승격 재생 완료·생략 줄이면 {'종류', 'epoch', '마켓 커서', 'head'}, 아니면 None."""
    if '승격 재생 완료:' not in text and '승격 재생 생략(' not in text:
        return None
    m = PROMOTION_RE.search(text)
    if not m:
        raise ValueError('승격 재생 줄 형식이 다르다: %s' % text[-120:])
    return {'종류': '완료' if m.group(1) == '완료' else '생략', 'epoch': int(m.group(2)),
            '마켓 커서': _by_market(_items(m.group(3), MAP_ITEM, '마켓 커서'), '마켓 커서'),
            'head': _by_market(_items(m.group(4), MAP_ITEM, 'head'), 'head')}


def is_boundary(text):
    """승격 재생 한 번이 끝난 줄(완료, 생략, 재시도, 반납)."""
    return BOUNDARY_RE.search(text) is not None


def is_fallback(text):
    return any(p in text for p in FALLBACK_PHRASES)


def is_boot(text):
    return BOOT in text


def keep_log_line(text):
    if len(text) > LOG_LINE_MAX:
        return False
    return any(logger in text and any(p in text for p in phrases) for logger, phrases in LOG_ALLOW)


def filter_log(raw):
    """docker logs --timestamps 출력에서 남길 줄만 출력 순서대로 [{'ts', 'text'}] 로."""
    lines = []
    for row in raw.splitlines():
        ts, _, text = row.partition(' ')
        if text and keep_log_line(text):
            lines.append({'ts': ts, 'text': text})
    return lines


def parse_metrics(lines):
    """노출 형식 줄을 [(이름, {라벨}, 값)] 로. 수가 아니거나 유한하지 않은 값은 뺀다."""
    out = []
    for line in lines:
        m = METRIC_LINE.match(line)
        if not m:
            continue
        try:
            value = float(m.group(3))
        except ValueError:
            continue
        if math.isfinite(value):
            out.append((m.group(1), dict(METRIC_LABEL.findall(m.group(2) or '')), value))
    return out


def metric_total(lines, name):
    """이름이 같은 계열 값의 합. 계열이 없으면 None 이다. 없는 계열을 0 으로 접지 않는다."""
    values = [v for n, _, v in parse_metrics(lines) if n == name]
    return sum(values) if values else None


def snapshot_env_values(env):
    """컨테이너 환경변수 목록에서 스냅샷 설정 접두만 남긴다. 다른 값은 싣지 않는다."""
    kept = {}
    for item in env or []:
        key, sep, value = str(item).partition('=')
        if sep and key.upper().startswith(ENV_PREFIX):
            kept[key] = value
    return kept


def applied_ranges(logs):
    """모든 컨테이너의 재생 적용 줄에서 (마켓, 시작, 종료) 구간과 해석 실패 줄을 뽑는다."""
    ranges, bad = set(), []
    for container, node in sorted((logs or {}).items()):
        for line in (node or {}).get('줄') or []:
            try:
                applied = parse_applied(line['text'])
            except ValueError as e:
                bad.append({'container': container, 'ts': line['ts'], 'error': str(e)})
                continue
            for market, (start, end, _) in (applied or {}).items():
                ranges.add((market, start, end))
    return ranges, bad


def container_log(container):
    try:
        p = subprocess.run(['docker', 'logs', '--timestamps', container], stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', timeout=120)
    except (subprocess.SubprocessError, OSError) as e:
        return {'ok': False, 'error': type(e).__name__, '줄': None}
    if p.returncode != 0:
        return {'ok': False, 'error': 'docker logs 종료코드 %s' % p.returncode, '줄': None}
    return {'ok': True, 'error': None, '줄': filter_log(p.stdout)}


def scrape(port):
    url = 'http://localhost:%d/actuator/prometheus' % port
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(url, timeout=10) as r:
            body = r.read().decode('utf-8', errors='replace')
    except (OSError, http.client.HTTPException) as e:
        return {'port': port, 'ok': False, 'error': type(e).__name__, '줄': None}
    return {'port': port, 'ok': True, 'error': None,
            '줄': [line for line in body.splitlines() if line.startswith(METRIC_PREFIXES)]}


def snapshot_env(container):
    try:
        p = subprocess.run(['docker', 'inspect', '-f', '{{json .Config.Env}}', container], capture_output=True,
                           text=True, encoding='utf-8', errors='replace', timeout=30)
    except (subprocess.SubprocessError, OSError) as e:
        return {'ok': False, 'error': type(e).__name__, '값': None}
    if p.returncode != 0:
        return {'ok': False, 'error': 'docker inspect 종료코드 %s' % p.returncode, '값': None}
    try:
        env = json.loads(p.stdout)
    except ValueError:
        return {'ok': False, 'error': 'docker inspect 출력 해석 실패', '값': None}
    return {'ok': True, 'error': None, '값': snapshot_env_values(env)}


def query_rows(sql, key):
    parsed, err = pgs.parse_json_row(*pgs.run_psql(sql, timeout=60))
    if not isinstance(parsed, list):
        return {'ok': False, 'error': err or 'JSON 배열이 아니다', key: None}
    return {'ok': True, 'error': None, key: parsed}


def range_counts(ranges):
    """구간마다 command_journal 행 수. 구간 값은 로그에서 숫자로만 뽑은 정수다."""
    if not ranges:
        return {'ok': True, 'error': None, '구간': []}
    values = ', '.join('(%d::smallint, %d::bigint, %d::bigint)' % r for r in sorted(ranges))
    sql = ("SELECT coalesce(json_agg(json_build_object('market_id', v.m, 'from', v.f, 'to', v.t, 'rows', "
           "(SELECT count(*) FROM command_journal c WHERE c.market_id = v.m AND c.seq > v.f AND c.seq <= v.t)) "
           "ORDER BY v.m, v.f, v.t), '[]'::json) FROM (VALUES %s) AS v(m, f, t)" % values)
    return query_rows(sql, '구간')


def wait_precondition(wait_sec, interval):
    """활성 마켓마다 last_applied_seq > 0 스냅샷이 생길 때까지 기다린다. 조회 실패 표본은 충족으로 세지 않는다."""
    start = time.monotonic()
    samples = 0
    while True:
        samples += 1
        parsed, err = pgs.parse_json_row(*pgs.run_psql(PRECONDITION_SQL))
        active = parsed.get('active') if isinstance(parsed, dict) else None
        missing = parsed.get('missing') if isinstance(parsed, dict) else None
        met = bool(active) and missing == []
        elapsed = time.monotonic() - start
        if met or elapsed >= wait_sec:
            return {'제한(초)': wait_sec, '경과(초)': round(elapsed, 1), '표본': samples, '충족': met,
                    '활성 마켓': active, '스냅샷 없는 활성 마켓': missing, 'error': err}
        time.sleep(interval)


def capture(phase, api, wait_sec, interval):
    out = {'단계': phase, 'ts': pgs.now_iso()}
    if phase == 'pre':
        out['대기'] = wait_precondition(wait_sec, interval)
    out['활성 마켓'] = query_rows(ACTIVE_SQL, '마켓')
    out['스냅샷'] = query_rows(SNAPSHOTS_SQL, '행')
    out['복구 실행'] = query_rows(RECOVERY_RUNS_SQL, '행')
    out['지표'] = {name: scrape(port) for name, port in api}
    out['복구 로그'] = {name: container_log(name) for name, _ in api}
    out['스냅샷 설정 환경변수'] = {name: snapshot_env(name) for name, _ in api}
    if phase == 'post':
        ranges, bad = applied_ranges(out['복구 로그'])
        out['저널 구간 수'] = dict(range_counts(ranges), **{'해석 실패 줄': bad})
    return out


def all_ok(result):
    parts = [result['활성 마켓'], result['스냅샷'], result['복구 실행']]
    for key in ('지표', '복구 로그', '스냅샷 설정 환경변수'):
        parts.extend(result[key].values())
    if '저널 구간 수' in result:
        parts.append(result['저널 구간 수'])
    ok = all(p.get('ok') for p in parts)
    return ok and result['대기']['충족'] if '대기' in result else ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('phase', choices=('pre', 'post'))
    ap.add_argument('--dir', required=True)
    ap.add_argument('--api', required=True)
    ap.add_argument('--wait-sec', type=int, default=180)
    ap.add_argument('--interval', type=int, default=5)
    a = ap.parse_args()
    try:
        api = parse_api(a.api)
    except ValueError as e:
        ap.error(str(e))
    result = capture(a.phase, api, a.wait_sec, a.interval)
    folder = Path(a.dir)
    folder.mkdir(parents=True, exist_ok=True)
    pgs.write_json(folder / ('snapshot_%s.json' % a.phase), result)
    ok = all_ok(result)
    wait = result.get('대기')
    note = '' if wait is None else ', 전제 충족 %s(%s초)' % (wait['충족'], wait['경과(초)'])
    print('%s 채집 %s%s' % (a.phase, '성공' if ok else '일부 실패', note), flush=True)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
