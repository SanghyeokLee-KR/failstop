"""ADR 0034 S3 모드 전환 도구(마켓분할_리더_S3_설계 7-2). 폐기용 스택(compose 프로젝트 exchange-pml)에서만 돈다.

하위 명령
  state                   모드, 이력, 퇴역 행, 예약 행 수, 정산 전 NEW 수, 정산 전 예약 수와 가장 오래된 나이를 낸다
  drain                   봇을 멈추고 정산 전 NEW 가 0 이 될 때까지 기다린 뒤(상한 120초) 봇을 다시 켠다
  transition --target 2   봇 정지 -> 드레인 -> api-2·api-1 정지 -> set_leader_protocol(2) -> api-1 기동(새 리더 대기)
                          -> api-2 기동 -> 두 노드의 exchange_leader_protocol_boot == 2, exchange_order_inflight_enabled == 0
                          확인 -> 봇 재개. 이미 목표 모드면 아무것도 하지 않고 already=true 와 현재 이력을 남긴 채 0 으로 끝난다.
  transition --target 1 --allow-downgrade
                          2->1. ADR 0034 결정 8 과 시험 R1 을 확인한 뒤에만 쓴다.

허용된 출발 모드(1->2 의 1, 2->1 의 2)가 아니면 전환 함수를 부르지 않고 3 으로 끝난다. 같은 스택에서 전환을 두 번 부르는
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

종료코드: 0 성공(already 포함), 1 실패, 2 가드 위반(시작하지 않음), 3 허용되지 않은 출발 모드, 4 BLOCKED(드레인 상한 초과).
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
  'db_now', clock_timestamp())
""" % UNSETTLED_NEW_SQL

LEADER_SQL = ("SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > clock_timestamp()) "
              "FROM leader_lease WHERE resource = 'matching-engine'")

GAUGES = ('exchange_leader_protocol_boot', 'exchange_order_inflight_enabled')
GAUGE_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{[^}]*\})?\s+(\S+)')


def plan_transition(current, target, allow_downgrade):
    """'already', 'call', 'refuse' 가운데 하나. 전환 함수를 부르기 전에 도구가 스스로 가른다."""
    if current == target:
        return 'already'
    if current == 1 and target == 2:
        return 'call'
    if current == 2 and target == 1 and allow_downgrade:
        return 'call'
    return 'refuse'


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


def leader():
    parsed, _ = pgs.parse_json_row(*pgs.run_psql(LEADER_SQL))
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
    t.add_argument('--target', type=int, required=True, choices=(1, 2))
    t.add_argument('--allow-downgrade', action='store_true')
    t.add_argument('--out')
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
