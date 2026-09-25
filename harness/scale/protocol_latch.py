"""ADR 0034 S3·S7 모드 전환 도구(마켓분할_리더_S3_설계 7-2, 마켓분할_리더_S7_설계 4-4·4-5). 폐기용 스택(compose 프로젝트
exchange-pml)에서만 돈다.

하위 명령
  state                   모드, 이력, 퇴역 행, 예약 행 수, 정산 전 NEW 수, 정산 전 예약 수와 가장 오래된 나이를 낸다
  drain                   봇을 멈추고 정산 전 NEW 가 0 이 될 때까지 기다린 뒤(상한 120초) 봇을 다시 켠다
  transition --target 2   봇 정지 -> 드레인 -> api-2·api-1 정지 -> set_leader_protocol(2) -> api-1 기동(새 리더 대기)
                          -> api-2 기동 -> 두 노드의 exchange_leader_protocol_boot == 2, exchange_order_inflight_enabled == 0
                          확인 -> 봇 재개. 이미 목표 모드면 아무것도 하지 않고 already=true 와 현재 이력을 남긴 채 0 으로 끝난다.
  transition --target 1 --allow-downgrade
                          2->1. ADR 0034 결정 8 과 시험 R1 을 확인한 뒤에만 쓴다.
  transition --target 3 [--latch-band]
                          P3(파티션 리더 모드)로 올린다(S7 설계 4-4, DB 설계 §5.4-e 1). 사전 확인(두 노드의 게이지
                          exchange_leader_protocol_max 3, leader_protocol_blockers(3)이 unsettled_new·band_latch_missing 밖의
                          줄을 내지 않음, 두 컨테이너의 선호 설정 api-1 matching-p0·api-2 matching-p1) -> 봇 정지 -> 드레인 ->
                          api-2·api-1 정지 -> 필요하면 1->2 -> 명령 대역 래치(없고 --latch-band 를 준 경우만. 래치가 없고 인자도
                          없으면 BLOCKED) -> 2->3 -> 구간 경계 표본(노드 시작 전) -> 두 노드를 함께 시작 -> 90초 안 선호 배정,
                          어긋나면 비선호 노드의 그 파티션 참여를 껐다가 선호 노드가 얻은 뒤 켠다 -> 두 노드 게이지(boot 3,
                          inflight 0), C29 0, 두 파티션 epoch 가 마지막 퇴역 epoch 보다 큼 -> 봇 재개.
  transition --target 2 --allow-downgrade
                          P3 에서 3->2 되돌리기(DB 설계 §5.4-e 2). 두 노드를 멈춘 채 전환하고 구간 경계 표본을 뜬 뒤 api-1 을 먼저
                          시작해 matching-engine 을 마지막 퇴역 epoch 뒤로 얻는 것을 보고 api-2 를 시작한다.
  blockers --target N     leader_protocol_blockers(N) 의 사유를 읽기만 한다(정지 창 전의 미리 보기).

허용된 출발 모드(1->2 의 1, 2->1 의 2, 2->3 과 1->3 의 1·2, 3->2 의 3)가 아니면 전환 함수를 부르지 않고 3 으로 끝난다. 3->1 은
받지 않는다(먼저 3->2). 같은 스택에서 전환을 두 번 부르는
절차 실수가 전환 함수의 55000 으로 칸을 망치지 않게 하려는 것이다(S3 설계 11절 R3).

가드(S3 설계 7-0). CHAOS_PROJECT 가 exchange-pml 이 아니거나 CHAOS_APP_IMAGE 가 exchange-chaos-app:pml 이 아니면 시작하지
않는다. 떠 있는 chaos-* 컨테이너의 compose 프로젝트 라벨도 exchange-pml 이어야 하고, 앱 컨테이너 이미지는 그 태그의 id 와 같아야
한다. 컨테이너 이름(chaos-*)이 보존 스택과 같아서 환경 변수만으로는 어느 스택에 붙었는지 보장하지 못하기 때문이다.

봇 부하는 docker stop 으로 끊고 docker start 로 되살린다(harness/scripts/lib.ps1 의 Stop-BotLoad·Restore-BotLoad 와 같은 규약).
목표 OPS 를 낮추는 것으로는 부족하다. 1 OPS 도 새 NEW 를 넣어 드레인이 끝나지 않는다. 노드 정지·기동도 docker CLI 로 한다.
docker compose stop·start 와 효과가 같고, 라벨을 먼저 확인하므로 프로젝트도 같다. compose 로 부르려면 이 스택을 세운 파일
조합(스케일·오버레이)을 도구가 알아야 해서 그렇게 했다.

돌리는 법:
  python harness/scale/protocol_latch.py state --out <json>
  python harness/scale/protocol_latch.py transition --target 2 --out <json>

명령 대역 래치(latch_command_band_cutover)는 되돌릴 수 없다. 이 도구는 폐기용 스택 가드를 통과하고 --latch-band 를 준 경우에만
부른다(ADR 0034 결정한 값 9).

종료코드: 0 성공(already 포함), 1 실패, 2 가드 위반(시작하지 않음), 3 허용되지 않은 출발 모드, 4 BLOCKED(드레인 상한 초과,
P3 사전 확인 실패, 래치 없음).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402
import s7_axes as s7  # noqa: E402

PROJECT = 'exchange-pml'
IMAGE = 'exchange-chaos-app:pml'
PG = 'chaos-pg'
API = (('chaos-api-1', 18080), ('chaos-api-2', 28080))
BOTS = 'chaos-bots'
APP_CONTAINERS = tuple(n for n, _ in API) + (BOTS,)

DRAIN_LIMIT_S = 120
DRAIN_POLL_S = 2
DRAIN_STREAK = 2
LEADER_WAIT_S = 240
NODE_WAIT_S = 180
# P3 배정(S7 설계 4-4). 두 노드를 함께 시작한 뒤 이 시간 안에 선호 배정이어야 하고, 재배치는 한 번에 이 시간까지 기다린다.
ASSIGN_WAIT_S = 90
RELOCATE_WAIT_S = 90
PREFERRED = {'chaos-api-1': 'matching-p0', 'chaos-api-2': 'matching-p1'}
PREFERRED_ENV = 'EXCHANGE_LEADER_PARTITION_PREFERRED'
# 정지 창에 들어가기 전에는 드레인과 래치가 아직 남아 있을 수 있다. 그 밖의 사유는 정지 창에서 풀 수 없다.
PRECHECK_ALLOWED_BLOCKERS = ('unsettled_new', 'band_latch_missing')

EXIT_OK, EXIT_FAIL, EXIT_GUARD, EXIT_MODE, EXIT_BLOCKED = 0, 1, 2, 3, 4

# 전환 함수의 선행 조건과 같은 술어다(V50 set_leader_protocol). 전수 반조인이라 폐기용 스택 크기에서만 쓴다.
UNSETTLED_NEW_SQL = ("SELECT count(*) FROM command_journal c WHERE c.command_type = 'NEW' "
                     "AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq)")

STATE_SQL = """
SELECT json_build_object(
  'protocol', (SELECT protocol FROM leader_protocol),
  'changed_at', (SELECT changed_at FROM leader_protocol),
  'history', (SELECT coalesce(json_agg(json_build_object('id', h.id, 'protocol', h.protocol,
                'prev_protocol', h.prev_protocol, 'epoch_floor', h.epoch_floor, 'changed_at', h.changed_at,
                'changed_by', h.changed_by) ORDER BY h.id), '[]'::json) FROM leader_protocol_history h),
  'lease', (SELECT coalesce(json_agg(json_build_object('resource', l.resource, 'holder', l.holder, 'epoch', l.epoch,
                'alive', l.lease_until > clock_timestamp()) ORDER BY l.resource), '[]'::json) FROM leader_lease l),
  'retired', (SELECT coalesce(json_agg(json_build_object('epoch', a.epoch, 'resource', a.resource,
                'protocol', a.protocol, 'acquired_at', a.acquired_at) ORDER BY a.epoch), '[]'::json)
              FROM lease_acquisitions a WHERE a.origin = 'RETIRE'),
  'acquisitions_by_protocol', (SELECT coalesce(json_object_agg(t.protocol, t.n), '{}'::json)
              FROM (SELECT protocol, count(*) AS n FROM lease_acquisitions GROUP BY protocol) t),
  'reservations', (SELECT count(*) FROM order_reservations),
  'unsettled_new', (%s),
  'unsettled_reservations', (SELECT count(*) FROM order_reservations r
              WHERE NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)),
  'oldest_unsettled_reservation_s', (SELECT floor(EXTRACT(EPOCH FROM clock_timestamp() - min(r.created_at)))
              FROM order_reservations r
              WHERE NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)),
  'latched', EXISTS (SELECT 1 FROM journal_cutover WHERE name = 'command_seq'),
  'db_now', clock_timestamp())
""" % UNSETTLED_NEW_SQL

# 리스 자원은 인자로 받는다(S4 설계 4-18). 이 도구는 P1 과 P2 사이만 전환하고 두 모드 모두 matching-engine 이 리더다.
LEADER_SQL = ("SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > clock_timestamp()) "
              "FROM leader_lease WHERE resource = '%s'")
LEASE_RESOURCES = ('matching-engine', 'matching-p0', 'matching-p1')

GAUGES = ('exchange_leader_protocol_boot', 'exchange_order_inflight_enabled', 'exchange_leader_protocol_max')
BLOCKERS_SQL = ("SELECT coalesce(json_agg(json_build_object('blocker', blocker, 'detail', detail)), '[]'::json) "
                "FROM leader_protocol_blockers(%d::smallint)")
C29_SQL = ("SELECT json_build_object('n', (SELECT count(*) FROM markets m WHERE NOT EXISTS (SELECT 1 FROM market_lease_partition p "
           "WHERE p.market_id = m.id)) + (SELECT count(*) FROM (VALUES ('matching-engine'), ('matching-p0'), ('matching-p1')) "
           "AS r(resource) WHERE NOT EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = r.resource)) "
           "+ (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM leader_protocol))")
GAUGE_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{[^}]*\})?\s+(\S+)')


# 오르는 전이와 그 단계, 내리는 전이(확인 인자 필요). 1->3 은 1->2 를 먼저 부르는 두 단계다. 3->1 은 받지 않는다.
UP_STEPS = {(1, 2): [2], (2, 3): [3], (1, 3): [2, 3]}
DOWN_STEPS = {(2, 1): [1], (3, 2): [2]}


def plan_steps(current, target, allow_downgrade):
    """부를 전환 함수 목표의 목록. [] 는 이미 목표 모드, None 은 이 도구가 부르지 않는 전이다(S7 설계 4-4)."""
    if current is None or target is None:
        return None
    if current == target:
        return []
    if (current, target) in UP_STEPS:
        return list(UP_STEPS[(current, target)])
    if (current, target) in DOWN_STEPS and allow_downgrade:
        return list(DOWN_STEPS[(current, target)])
    return None


def plan_transition(current, target, allow_downgrade):
    """'already', 'call', 'refuse' 가운데 하나. 전환 함수를 부르기 전에 도구가 스스로 가른다."""
    steps = plan_steps(current, target, allow_downgrade)
    if steps is None:
        return 'refuse'
    return 'already' if not steps else 'call'


def latch_decision(latched, latch_band):
    """명령 대역 래치. 'present'(이미 있음), 'latch'(이 창에서 건다), 'blocked'(없고 --latch-band 도 없음)."""
    if latched:
        return 'present'
    return 'latch' if latch_band else 'blocked'


def precheck_problems(gauges, blockers, preferred_env):
    """2->3 정지 창 전 확인(DB 설계 §5.4-e 1). gauges 는 {노드: node_gauges 결과}, blockers 는 [{blocker, detail}] 이나 None,
    preferred_env 는 {노드: 선호 설정 값}이다. 빈 목록이면 정지 창에 들어가도 된다."""
    problems = []
    for node, resource in sorted(PREFERRED.items()):
        g = gauges.get(node) or {}
        value = (g.get('values') or {}).get('exchange_leader_protocol_max') if g.get('ok') else None
        if value != 3.0:
            problems.append('%s 의 exchange_leader_protocol_max 가 3 이 아니다(%r). S7 바이너리가 아니다' % (node, value))
        if preferred_env.get(node) != resource:
            problems.append('%s 의 %s 가 %r 이다. %s 이어야 한다(P3 오버레이)' % (node, PREFERRED_ENV,
                                                                         preferred_env.get(node), resource))
    if blockers is None:
        problems.append('leader_protocol_blockers(3)를 읽지 못했다')
    else:
        extra = [b for b in blockers if b.get('blocker') not in PRECHECK_ALLOWED_BLOCKERS]
        if extra:
            problems.append('정지 창에서 풀 수 없는 선행 조건: ' + '; '.join(
                '%s: %s' % (b.get('blocker'), b.get('detail')) for b in extra))
    return problems


def guard_problems(env, labels, image_ids, pml_image_id):
    """가드 위반 목록. 비었으면 시작해도 된다. labels·image_ids 의 None 은 그 컨테이너가 없다는 뜻이다."""
    problems = []
    if env.get('CHAOS_PROJECT') != PROJECT:
        problems.append('CHAOS_PROJECT=%r 이다. %s 이어야 한다' % (env.get('CHAOS_PROJECT'), PROJECT))
    if env.get('CHAOS_APP_IMAGE') != IMAGE:
        problems.append('CHAOS_APP_IMAGE=%r 이다. %s 이어야 한다' % (env.get('CHAOS_APP_IMAGE'), IMAGE))
    if labels.get(PG) is None:
        problems.append('%s 컨테이너가 없다' % PG)
    for name, project in sorted(labels.items()):
        if project is not None and project != PROJECT:
            problems.append('%s 의 compose 프로젝트가 %r 이다' % (name, project))
    if not pml_image_id:
        problems.append('%s 이미지가 없다' % IMAGE)
    for name, image_id in sorted(image_ids.items()):
        if image_id is not None and image_id != pml_image_id:
            problems.append('%s 의 이미지 %s 가 %s 의 id 와 다르다' % (name, image_id[:19], IMAGE))
    return problems


def docker(args, timeout=180):
    try:
        p = subprocess.run(['docker'] + args, capture_output=True, text=True, encoding='utf-8', errors='replace',
                           timeout=timeout)
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except (subprocess.SubprocessError, OSError) as e:
        return None, '', type(e).__name__


def inspect(name, fmt):
    rc, out, _ = docker(['inspect', '-f', fmt, name], timeout=30)
    return out if rc == 0 else None


def guard():
    labels = {c: inspect(c, '{{index .Config.Labels "com.docker.compose.project"}}') for c in (PG,) + APP_CONTAINERS}
    image_ids = {c: inspect(c, '{{.Image}}') for c in APP_CONTAINERS}
    rc, pml_id, _ = docker(['image', 'inspect', IMAGE, '--format', '{{.Id}}'], timeout=30)
    pml_id = pml_id if rc == 0 else None
    problems = guard_problems(os.environ, labels, image_ids, pml_id)
    return {'ok': not problems, 'problems': problems, 'labels': labels, 'image_ids': image_ids, 'pml_image_id': pml_id}


def running(name):
    return inspect(name, '{{.State.Running}}') == 'true'


def state():
    parsed, err = pgs.parse_json_row(*pgs.run_psql(STATE_SQL, timeout=120))
    out = {'ok': parsed is not None, 'error': err, 'ts': pgs.now_iso()}
    if parsed is not None:
        out.update(parsed)
    return out


def scalar(sql, timeout=60):
    rc, stdout, _ = pgs.run_psql(sql, timeout=timeout)
    try:
        return int((stdout or '').strip()) if rc == 0 else None
    except ValueError:
        return None


def drain_wait(limit_s=DRAIN_LIMIT_S):
    """정산 전 NEW 가 DRAIN_STREAK 번 잇달아 0 이면 끝난다. 무응답 표본은 0 으로 세지 않는다(lib.ps1 Wait-DrainZero)."""
    start = time.monotonic()
    samples, streak = [], 0
    while time.monotonic() - start < limit_s:
        v = scalar(UNSETTLED_NEW_SQL)
        samples.append({'t_s': round(time.monotonic() - start, 1), 'unsettled_new': v})
        streak = streak + 1 if v == 0 else 0
        if streak >= DRAIN_STREAK:
            return {'ok': True, 'seconds': round(time.monotonic() - start, 1), 'samples': samples[-20:]}
        time.sleep(DRAIN_POLL_S)
    return {'ok': False, 'seconds': round(time.monotonic() - start, 1), 'samples': samples[-20:]}


def leader(resource='matching-engine'):
    if resource not in LEASE_RESOURCES:
        raise ValueError('알 수 없는 리스 자원: %s' % resource)
    parsed, _ = pgs.parse_json_row(*pgs.run_psql(LEADER_SQL % resource))
    return parsed


def http_ok(port, path):
    try:
        with urllib.request.urlopen('http://localhost:%d%s' % (port, path), timeout=5) as r:
            return r.status == 200 and b'UP' in r.read()
    except Exception:
        return False


def wait_until(check, limit_s):
    start = time.monotonic()
    while time.monotonic() - start < limit_s:
        v = check()
        if v:
            return v, round(time.monotonic() - start, 1)
        time.sleep(2)
    return None, round(time.monotonic() - start, 1)


def node_gauges(port):
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=10) as r:
            text = r.read().decode('utf-8', 'replace')
    except Exception as e:
        return {'ok': False, 'error': type(e).__name__}
    got = {}
    for line in text.splitlines():
        m = GAUGE_LINE.match(line)
        if m and m.group(1) in GAUGES:
            try:
                got[m.group(1)] = float(m.group(2))
            except ValueError:
                pass
    return {'ok': True, 'values': got}


def call_transition(target, allow_downgrade):
    """전환 함수를 소유자 로그인으로 부른다. 앞뒤 clock_timestamp 로 호출 시간을 DB 시계에서 잰다."""
    args = '%d::smallint' % target + (', true' if allow_downgrade else '')
    script = ("\\pset format unaligned\n\\pset tuples_only on\n"
              "SELECT 'T0|' || clock_timestamp();\n"
              "SELECT 'ROW|' || out_resource || '|' || out_retired_epoch || '|' || out_protocol "
              "FROM set_leader_protocol(%s);\n"
              "SELECT 'T1|' || clock_timestamp();\n" % args)
    try:
        p = subprocess.run(['docker', 'exec', '-i', PG, 'psql', '-U', 'exchange', '-d', 'exchange', '-X', '-q',
                            '-v', 'ON_ERROR_STOP=1', '-f', '-'], input=script, capture_output=True, text=True,
                           encoding='utf-8', errors='replace', timeout=120)
        rc, stdout, stderr = p.returncode, p.stdout, p.stderr
    except (subprocess.SubprocessError, OSError) as e:
        rc, stdout, stderr = None, '', type(e).__name__
    rows, t0, t1 = [], None, None
    for line in (stdout or '').splitlines():
        f = line.strip().split('|')
        if f[0] == 'ROW' and len(f) == 4:
            rows.append({'resource': f[1], 'retired_epoch': int(f[2]), 'protocol': int(f[3])})
        elif f[0] == 'T0' and len(f) == 2:
            t0 = f[1]
        elif f[0] == 'T1' and len(f) == 2:
            t1 = f[1]
    duration_ms = None
    if t0 and t1:
        q = "SELECT round(EXTRACT(EPOCH FROM ('%s'::timestamptz - '%s'::timestamptz)) * 1000, 1)" % (t1, t0)
        rc2, out2, _ = pgs.run_psql(q)
        try:
            duration_ms = float(out2.strip()) if rc2 == 0 else None
        except ValueError:
            duration_ms = None
    return {'rc': rc, 'sql': 'SELECT * FROM set_leader_protocol(%s)' % args, 'rows': rows, 't0': t0, 't1': t1,
            'duration_ms': duration_ms, 'stderr': (stderr or '').strip()[-400:]}


def transition(target, allow_downgrade=False):
    out = {'ok': False, 'already': False, 'target': target, 'allow_downgrade': allow_downgrade, 'code': EXIT_FAIL,
           'error': None, 'started': pgs.now_iso()}
    g = guard()
    out['guard'] = g
    if not g['ok']:
        out['error'] = '가드 위반: ' + '; '.join(g['problems'])
        out['code'] = EXIT_GUARD
        return out
    before = state()
    out['before'] = before
    if not before['ok']:
        out['error'] = '모드를 읽지 못했다: %s' % before['error']
        return out
    decision = plan_transition(before.get('protocol'), target, allow_downgrade)
    out['decision'] = decision
    if decision == 'already':
        out.update(ok=True, already=True, code=EXIT_OK, history=before.get('history'))
        return out
    if decision == 'refuse':
        out['error'] = '출발 모드 P%s 에서 P%s 로는 이 도구가 전환 함수를 부르지 않는다' % (before.get('protocol'), target)
        out['code'] = EXIT_MODE
        return out

    bots_were_running = running(BOTS)
    out['bots'] = {'running_before': bots_were_running}
    stopped_nodes = []
    try:
        if bots_were_running:
            out['bots']['stop_rc'] = docker(['stop', BOTS])[0]
        d = drain_wait()
        out['drain'] = d
        if not d['ok']:
            out['error'] = 'BLOCKED: %d초 안에 정산 전 NEW 가 0 이 되지 않았다' % DRAIN_LIMIT_S
            out['code'] = EXIT_BLOCKED
            return out
        for name in ('chaos-api-2', 'chaos-api-1'):
            rc = docker(['stop', name])[0]
            out.setdefault('stop', {})[name] = rc
            stopped_nodes.append(name)
            if rc != 0:
                out['error'] = '%s 정지 실패(rc=%s)' % (name, rc)
                return out
        call = call_transition(target, allow_downgrade)
        out['call'] = call
        if call['rc'] != 0 or len(call['rows']) != 3:
            out['error'] = '전환 함수 실패(rc=%s): %s' % (call['rc'], call['stderr'])
            return out
        last_retired = max(r['retired_epoch'] for r in call['rows'])
        starts = {}
        rc = docker(['start', 'chaos-api-1'])[0]
        stopped_nodes.remove('chaos-api-1')

        def new_leader():
            lead = leader()
            return lead if (lead and lead.get('alive') and int(lead.get('epoch') or 0) > last_retired
                            and http_ok(18080, '/actuator/health/readiness')) else None

        lead, waited = wait_until(new_leader, LEADER_WAIT_S)
        starts['chaos-api-1'] = {'rc': rc, 'leader': lead, 'waited_s': waited}
        rc = docker(['start', 'chaos-api-2'])[0]
        stopped_nodes.remove('chaos-api-2')
        live, waited = wait_until(lambda: http_ok(28080, '/actuator/health/liveness'), NODE_WAIT_S)
        starts['chaos-api-2'] = {'rc': rc, 'liveness': bool(live), 'waited_s': waited}
        out['start'] = starts
        nodes = {}
        for name, port in API:
            nodes[name] = node_gauges(port)
        out['nodes'] = nodes
        gauges_ok = all(n.get('ok') and n['values'].get('exchange_leader_protocol_boot') == float(target)
                        and n['values'].get('exchange_order_inflight_enabled') == (0.0 if target >= 2 else 1.0)
                        for n in nodes.values())
        after = state()
        out['after'] = after
        out['history'] = after.get('history')
        out['new_leader_epoch'] = lead.get('epoch') if lead else None
        out['expected_leader_epoch'] = last_retired + 1
        checks = {
            'leader': lead is not None,
            'leader_epoch_is_last_retired_plus_1': bool(lead) and int(lead['epoch']) == last_retired + 1,
            'api2_liveness': bool(live),
            'gauges': gauges_ok,
            'mode_after': after.get('protocol') == target,
            'retire_order': [r['resource'] for r in call['rows']] == ['matching-p0', 'matching-p1', 'matching-engine'],
        }
        out['checks'] = checks
        # 새 리더 epoch 는 기록만 한다. 두 번째 노드가 먼저 획득 시도를 하면 +1 이 아닐 수 있지만 api-2 는 뒤에 켠다.
        failed = [k for k, v in checks.items() if not v and k != 'leader_epoch_is_last_retired_plus_1']
        if failed:
            out['error'] = '전환 뒤 확인 실패: %s' % ', '.join(failed)
            return out
        out.update(ok=True, code=EXIT_OK)
        return out
    finally:
        for name in reversed(stopped_nodes):
            out.setdefault('recovery_start', {})[name] = docker(['start', name])[0]
        if bots_were_running:
            out['bots']['start_rc'] = docker(['start', BOTS])[0]
        out['finished'] = pgs.now_iso()


def blockers(target):
    rows, _ = pgs.parse_json_row(*pgs.run_psql(BLOCKERS_SQL % target, timeout=120))
    return rows


def preferred_env(name):
    """컨테이너 환경에서 선호 설정 한 줄만 고른다. 다른 환경 변수(비밀 값 포함)는 읽지 않는다."""
    out = inspect(name, '{{range .Config.Env}}{{if eq (index (split . "=") 0) "%s"}}{{.}}{{end}}{{end}}' % PREFERRED_ENV)
    if not out or '=' not in out:
        return None
    return out.split('=', 1)[1]


def call_latch():
    """명령 대역 래치(되돌릴 수 없다, ADR 0022 6-b). 폐기용 스택 가드와 --latch-band 뒤에만 부른다."""
    rc, out, err = pgs.run_psql("SELECT 'LATCHED|' || latch_command_band_cutover()", timeout=120)
    ok = rc == 0 and 'LATCHED|' in (out or '')
    return {'ok': ok, 'rc': rc, 'latched_at': (out or '').strip().split('|', 1)[-1] if ok else None,
            'stderr': (err or '').strip()[-400:]}


def c29():
    row, _ = pgs.parse_json_row(*pgs.run_psql(C29_SQL, timeout=60))
    return (row or {}).get('n')


class Ops:
    """정지 창의 부작용을 모은 것. 시험은 같은 이름의 가짜로 순서를 본다(test_protocol_latch)."""

    def guard(self):
        return guard()

    def state(self):
        return state()

    def blockers(self, target):
        return blockers(target)

    def gauges(self, port):
        return node_gauges(port)

    def preferred_env(self, name):
        return preferred_env(name)

    def running(self, name):
        return running(name)

    def docker(self, args):
        return docker(args)[0]

    def drain(self):
        return drain_wait()

    def latch(self):
        return call_latch()

    def call(self, target, allow_downgrade):
        return call_transition(target, allow_downgrade)

    def segment(self):
        return s7.segment_count()

    def partitions(self):
        return s7.all_partitions()

    def set_participate(self, node, resource, value):
        return s7.set_participate(node, resource, value)

    def leader(self, resource):
        return leader(resource)

    def http_ok(self, port, path):
        return http_ok(port, path)

    def c29(self):
        return c29()

    def sleep(self, seconds):
        time.sleep(seconds)


def wait_ops(ops, check, limit_s, step_s=2.0):
    waited = 0.0
    while waited < limit_s:
        v = check()
        if v:
            return v, waited
        ops.sleep(step_s)
        waited += step_s
    return None, waited


def settle_assignment(ops, out):
    """두 노드를 함께 시작한 뒤 선호 배정을 기다리고, 한 노드가 비선호 파티션을 쥐었으면 옮긴다(DB 설계 §5.4-e 1, 결정 4)."""
    status, waited = wait_ops(ops, lambda: s7.assignment(ops.partitions())[0] != 'wait' or None, ASSIGN_WAIT_S)
    state_, moves = s7.assignment(ops.partitions())
    out['assignment_first'] = {'state': state_, 'moves': moves, 'waited_s': waited}
    relocations = []
    for node, resource in moves:
        preferred_node = next(n for n, r in PREFERRED.items() if r == resource)
        off = ops.set_participate(node, resource, False)
        got, w = wait_ops(ops, lambda: s7.owner_of(ops.partitions(), resource) == preferred_node, RELOCATE_WAIT_S)
        on = ops.set_participate(node, resource, True)
        relocations.append({'from': node, 'to': preferred_node, 'resource': resource, 'participate_off': off,
                            'participate_on': on, 'moved': bool(got), 'waited_s': w})
    out['relocations'] = relocations
    final, _ = s7.assignment(ops.partitions())
    out['assignment'] = final
    return final == 'ok'


def transition_partition(target, allow_downgrade=False, latch_band=False, ops=None):
    """P3 로 오르거나(1·2 -> 3) P3 에서 내린다(3 -> 2). S7 설계 4-4·4-5."""
    ops = ops or Ops()
    out = {'ok': False, 'already': False, 'target': target, 'allow_downgrade': allow_downgrade,
           'latch_band': latch_band, 'code': EXIT_FAIL, 'error': None, 'started': pgs.now_iso(), 'order': []}
    g = ops.guard()
    out['guard'] = g
    if not g['ok']:
        out.update(error='가드 위반: ' + '; '.join(g['problems']), code=EXIT_GUARD)
        return out
    before = ops.state()
    out['before'] = before
    if not before.get('ok'):
        out['error'] = '모드를 읽지 못했다: %s' % before.get('error')
        return out
    current = before.get('protocol')
    steps = plan_steps(current, target, allow_downgrade)
    out['steps'] = steps
    if steps is None:
        out.update(error='출발 모드 P%s 에서 P%s 로는 이 도구가 전환 함수를 부르지 않는다' % (current, target), code=EXIT_MODE)
        return out
    if not steps:
        out.update(ok=True, already=True, code=EXIT_OK, history=before.get('history'))
        return out
    up = target == 3
    latch = 'present'
    if up:
        gauges = {name: ops.gauges(port) for name, port in API}
        envs = {name: ops.preferred_env(name) for name, _ in API}
        pre_blockers = ops.blockers(3)
        out['precheck'] = {'gauges': gauges, 'preferred_env': envs, 'blockers': pre_blockers}
        problems = precheck_problems(gauges, pre_blockers, envs)
        latch = latch_decision(before.get('latched'), latch_band)
        out['latch_decision'] = latch
        if latch == 'blocked':
            problems.append('명령 대역 래치가 없다. 폐기용 DB 에서만 --latch-band 로 건다(ADR 0034 결정한 값 9)')
        if problems:
            out.update(error='BLOCKED: ' + '; '.join(problems), code=EXIT_BLOCKED)
            return out
    bots_were_running = ops.running(BOTS)
    out['bots'] = {'running_before': bots_were_running}
    stopped = []
    try:
        if bots_were_running:
            out['bots']['stop_rc'] = ops.docker(['stop', BOTS])
            out['order'].append('stop:' + BOTS)
        d = ops.drain()
        out['drain'] = d
        out['order'].append('drain')
        if not d['ok']:
            out.update(error='BLOCKED: %d초 안에 정산 전 NEW 가 0 이 되지 않았다' % DRAIN_LIMIT_S, code=EXIT_BLOCKED)
            return out
        for name in ('chaos-api-2', 'chaos-api-1'):
            rc = ops.docker(['stop', name])
            out.setdefault('stop', {})[name] = rc
            out['order'].append('stop:' + name)
            stopped.append(name)
            if rc != 0:
                out['error'] = '%s 정지 실패(rc=%s)' % (name, rc)
                return out
        calls = []
        for step in steps:
            if step == 3 and latch == 'latch':
                lr = ops.latch()
                out['latch'] = lr
                out['order'].append('latch')
                if not lr.get('ok'):
                    out['error'] = '명령 대역 래치 실패: %s' % lr.get('stderr')
                    return out
            call = ops.call(step, step < (current if not calls else calls[-1]['target']))
            call['target'] = step
            calls.append(call)
            out['order'].append('call:%d' % step)
            if call.get('rc') != 0 or len(call.get('rows') or []) != 3:
                out['calls'] = calls
                out['error'] = '전환 함수 실패(P%d, rc=%s): %s' % (step, call.get('rc'), call.get('stderr'))
                return out
        out['calls'] = calls
        last_retired = max(r['retired_epoch'] for r in calls[-1]['rows'])
        out['last_retired_epoch'] = last_retired
        # 구간 경계 표본은 전환 함수 직후, 노드를 시작하기 전에 뜬다(설계 4-7). 뒤 표본과 이력 행마다 같아야 한다.
        out['segment_after_cutover'] = ops.segment()
        out['order'].append('segment')
        if up:
            for name in ('chaos-api-1', 'chaos-api-2'):
                out.setdefault('start', {})[name] = ops.docker(['start', name])
                out['order'].append('start:' + name)
                stopped.remove(name)
            assigned = settle_assignment(ops, out)
        else:
            out.setdefault('start', {})['chaos-api-1'] = ops.docker(['start', 'chaos-api-1'])
            out['order'].append('start:chaos-api-1')
            stopped.remove('chaos-api-1')

            def new_leader():
                lead = ops.leader('matching-engine')
                return lead if (lead and lead.get('alive') and int(lead.get('epoch') or 0) > last_retired
                                and ops.http_ok(18080, '/actuator/health/readiness')) else None

            lead, waited = wait_ops(ops, new_leader, LEADER_WAIT_S)
            out['leader'] = {'lease': lead, 'waited_s': waited}
            out.setdefault('start', {})['chaos-api-2'] = ops.docker(['start', 'chaos-api-2'])
            out['order'].append('start:chaos-api-2')
            stopped.remove('chaos-api-2')
            live, _ = wait_ops(ops, lambda: ops.http_ok(28080, '/actuator/health/liveness'), NODE_WAIT_S)
            out['api2_liveness'] = bool(live)
            assigned = lead is not None and bool(live)
        nodes = {name: ops.gauges(port) for name, port in API}
        out['nodes'] = nodes
        after = ops.state()
        out['after'] = after
        out['history'] = after.get('history')
        leases = {row.get('resource'): row for row in after.get('lease') or []}
        checks = {
            'gauges_boot': all(n.get('ok') and n['values'].get('exchange_leader_protocol_boot') == float(target)
                               for n in nodes.values()),
            'gauges_inflight_0': all(n.get('ok') and n['values'].get('exchange_order_inflight_enabled') == 0.0
                                     for n in nodes.values()),
            'mode_after': after.get('protocol') == target,
            'history_last': bool(after.get('history')) and after['history'][-1].get('protocol') == target,
            'retire_order': [r['resource'] for r in calls[-1]['rows']] == ['matching-p0', 'matching-p1',
                                                                            'matching-engine'],
            'segment_sampled_before_start': bool((out.get('segment_after_cutover') or {}).get('ok')),
        }
        if up:
            checks['preferred_assignment'] = assigned
            checks['c29_0'] = ops.c29() == 0
            checks['partition_epochs_after_last_retired'] = all(
                int((leases.get(r) or {}).get('epoch') or 0) > last_retired for r in ('matching-p0', 'matching-p1'))
            checks['gauges_max_3'] = all(n.get('ok') and n['values'].get('exchange_leader_protocol_max') == 3.0
                                         for n in nodes.values())
        else:
            checks['engine_leader_after_last_retired'] = assigned
        out['checks'] = checks
        failed = [k for k, v in checks.items() if not v]
        if failed:
            out['error'] = '전환 뒤 확인 실패: %s' % ', '.join(failed)
            return out
        out.update(ok=True, code=EXIT_OK)
        return out
    finally:
        for name in list(stopped):
            out.setdefault('recovery_start', {})[name] = ops.docker(['start', name])
        if bots_were_running:
            out['bots']['start_rc'] = ops.docker(['start', BOTS])
            out['order'].append('start:' + BOTS)
        out['finished'] = pgs.now_iso()


def drain_only():
    out = {'ok': False, 'code': EXIT_FAIL, 'error': None}
    g = guard()
    out['guard'] = g
    if not g['ok']:
        out['error'] = '가드 위반: ' + '; '.join(g['problems'])
        out['code'] = EXIT_GUARD
        return out
    was = running(BOTS)
    out['bots'] = {'running_before': was}
    try:
        if was:
            out['bots']['stop_rc'] = docker(['stop', BOTS])[0]
        d = drain_wait()
        out['drain'] = d
        out['state'] = state()
        if d['ok']:
            out.update(ok=True, code=EXIT_OK)
        else:
            out.update(code=EXIT_BLOCKED, error='BLOCKED: 드레인 상한 초과')
        return out
    finally:
        if was:
            out['bots']['start_rc'] = docker(['start', BOTS])[0]


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('state')
    s.add_argument('--out')
    d = sub.add_parser('drain')
    d.add_argument('--out')
    t = sub.add_parser('transition')
    t.add_argument('--target', type=int, required=True, choices=(1, 2, 3))
    t.add_argument('--allow-downgrade', action='store_true')
    t.add_argument('--latch-band', action='store_true',
                   help='명령 대역 래치가 없으면 이 창에서 건다(되돌릴 수 없다, 폐기용 DB 만)')
    t.add_argument('--out')
    b = sub.add_parser('blockers')
    b.add_argument('--target', type=int, required=True, choices=(1, 2, 3))
    b.add_argument('--out')
    a = ap.parse_args(argv)
    if a.cmd == 'state':
        g = guard()
        if not g['ok']:
            res = {'ok': False, 'code': EXIT_GUARD, 'guard': g, 'error': '가드 위반: ' + '; '.join(g['problems'])}
        else:
            res = state()
            res['code'] = EXIT_OK if res['ok'] else EXIT_FAIL
    elif a.cmd == 'drain':
        res = drain_only()
    elif a.cmd == 'blockers':
        g = guard()
        rows = blockers(a.target) if g['ok'] else None
        res = {'ok': rows is not None, 'code': EXIT_OK if rows is not None else (EXIT_GUARD if not g['ok'] else EXIT_FAIL),
               'target': a.target, 'blockers': rows, 'guard': g}
    else:
        current = state().get('protocol') if guard()['ok'] else None
        if a.target == 3 or current == 3:
            res = transition_partition(a.target, a.allow_downgrade, a.latch_band)
        else:
            res = transition(a.target, a.allow_downgrade)
    text = json.dumps(res, ensure_ascii=False, indent=2, default=str)
    if a.out:
        Path(a.out).write_text(text, encoding='utf-8')
    summary = {k: res.get(k) for k in ('ok', 'already', 'code', 'error', 'new_leader_epoch', 'protocol')
               if k in res}
    if res.get('call'):
        summary['retired'] = [(r['resource'], r['retired_epoch']) for r in res['call']['rows']]
        summary['duration_ms'] = res['call']['duration_ms']
    print(json.dumps(summary, ensure_ascii=False, default=str), flush=True)
    return res.get('code', EXIT_FAIL)


if __name__ == '__main__':
    sys.exit(main())
