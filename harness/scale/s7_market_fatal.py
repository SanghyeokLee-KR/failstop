"""Q10 한 마켓 치명 실행기(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 5-2 Q10). 폐기용 스택(exchange-pml)
에서만 돈다. 첫 관문 주행 기록은 harness/evidence/pml-S7-20260926-0300/ 이다. 판정은 s7_axes.py market-fatal 이 이 파일의 출력으로 한다.

소유자 로그인으로 폐기용 DB 에 trades INSERT 트리거를 건다. 트리거는 NEW.market_id 가 --market(기본 KRW-ETH, p1)이고 비트랜잭션
시퀀스의 nextval 이 1 일 때만 사용자 정의 SQLSTATE S7F01 로 실패한다. S7F01 은 정산의 재시도 대상(55P03·57014·40P01·40001), 연결
오류(08xxx), 무결성 위반(23xxx), fencing 거부(P0001) 어디에도 들지 않으므로 정산이 재시도하지 않고 그 마켓 라이터를 치명으로 올린다
(서버 시험 InjectedMarketFaultClassificationTest 가 분류를 고정한다). 시퀀스는 롤백되지 않으므로 재구성 뒤의 재정산은 통과한다.
시퀀스가 불린 것을 보면 곧바로 트리거·함수·시퀀스를 지운다(finally 에서도 지운다).

적는 것: 두 노드의 파티션 강등 계수 증분(자원·사유별), 치명 파티션의 차단기 상태, 주입부터 그 파티션이 다시 ACCEPTING 이 되기까지의
초, p0 ACK 간격(s7_axes.ack_interval_judge, 주입 시각 기준), 봇을 멈추고 비운 뒤 사후 권위 run 의 게이팅 판정(lib.ps1 과 같은 면제:
C13A·C16·C19 와 WARN). 봇은 멈춘 채 둔다(실행기가 스택을 내린다).

사용:
  python harness/scale/s7_market_fatal.py --market KRW-ETH --out <json>
종료코드: 0 수집 완료(판정은 s7_axes.py), 2 가드 위반이나 전제 불성립, 1 그 밖의 실패.
"""
import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402
import protocol_latch as pl  # noqa: E402
import s7_axes as s7  # noqa: E402
import s7_double_spend as ds  # noqa: E402

PORTS = {'chaos-api-1': 18080, 'chaos-api-2': 28080}
FIRE_WAIT_S = 180
REACQUIRE_WAIT_S = 90
# lib.ps1 CHAOS_GATING_EXEMPT_CHECKS 와 같은 면제(드레인 전제·스테일 ACK). WARN 도 게이팅하지 않는다.
GATING_EXEMPT = ('C13A_ORDER_MATERIALIZED', 'C16_ACK_JOURNALED', 'C19_ORDERBOOK_NONCROSS')
NAME_RE = re.compile(r'^[A-Z0-9]+-[A-Z0-9]+$')


# ---------------------------------------------------------------- 순수 함수(test_s7_executors.py)

def install_sql(market_id):
    """한 번만 실패하는 trades 트리거. 시퀀스는 그 마켓 행에서만 부른다(IF 를 겹쳐 다른 마켓 행이 nextval 을 쓰지 않게 한다)."""
    return """
CREATE SEQUENCE s7_fault_once;
CREATE FUNCTION s7_fault_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.market_id = %d THEN
        IF nextval('s7_fault_once') = 1 THEN
            RAISE EXCEPTION 'S7 Q10 한 번 치명 주입(market %%)', NEW.market_id USING ERRCODE = 'S7F01';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_s7_fault_once BEFORE INSERT ON trades FOR EACH ROW EXECUTE FUNCTION s7_fault_once();
""" % int(market_id)


REMOVE_SQL = """
DROP TRIGGER IF EXISTS trg_s7_fault_once ON trades;
DROP FUNCTION IF EXISTS s7_fault_once();
DROP SEQUENCE IF EXISTS s7_fault_once;
"""


def demotion_deltas(before, after):
    """{노드: {'자원|사유': 값}} 두 표본의 차를 {자원: {사유: 증분}} 으로 모은다(두 노드 합). 표본이 없으면 None 이다."""
    if before is None or after is None:
        return None
    out = {}
    for node, rows in after.items():
        base = before.get(node) or {}
        for key, v in (rows or {}).items():
            d = v - base.get(key, 0.0)
            if d:
                res, reason = key.split('|', 1)
                out.setdefault(res, {})[reason] = out.setdefault(res, {}).get(reason, 0) + int(round(d))
    return out


def gated_clean(checks):
    """사후 권위 run 의 게이팅 판정(lib.ps1 Test-ExperimentIntegrity 와 같은 술어). 체크가 없으면 거짓이다."""
    if not checks:
        return False
    for c in checks:
        if not c.get('checkName'):
            return False
        if c.get('checkName') in GATING_EXEMPT or c.get('severity') == 'WARN':
            continue
        if int(c.get('violatedRows') or 0) != 0:
            return False
    return True


# ---------------------------------------------------------------- 채집

def psql_script(sql):
    p = subprocess.run(['docker', 'exec', '-i', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-X', '-q',
                        '-v', 'ON_ERROR_STOP=1'], input=sql, capture_output=True, text=True, encoding='utf-8',
                       errors='replace', timeout=120)
    return p.returncode, p.stderr.strip()[-300:]


def scalar(sql):
    rc, out, _ = pgs.run_psql(sql, timeout=60)
    return out.strip() if rc == 0 else None


def scrape_demotions():
    out = {}
    for node, port in PORTS.items():
        try:
            with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=10) as r:
                out[node] = ds.parse_node_counters(r.read().decode('utf-8', 'replace'))['demotions']
        except Exception:  # noqa: BLE001
            return None
    return out


def partition_status(resource):
    """그 자원을 ACCEPTING 으로 쥔 노드와 노드마다 그 자원의 차단기·상태."""
    parts = s7.all_partitions()
    view = {}
    for node, body in parts.items():
        for p in (body or {}).get('partitions') or []:
            if p.get('resource') == resource:
                view[node] = {'state': p.get('state'), 'epoch': p.get('epoch'), 'breaker': p.get('breaker')}
    return s7.owner_of(parts, resource), view


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--market', default='KRW-ETH')
    ap.add_argument('--stop-before-post', default=None,
                    help='사후 드레인 전에 멈출 부하 컨테이너(pml-s7-k6-*). 돌고 있으면 새 NEW 가 들어와 드레인이 끝나지 않는다')
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    res = {'started': pgs.now_iso(), 'market': a.market, 'error': None, 'injected_failures': 0}
    installed = False
    code = 1
    try:
        if not NAME_RE.match(a.market):
            res['error'] = '마켓 이름 형식 위반'
            return 2
        g = pl.guard()
        if not g['ok']:
            res['error'] = '가드 위반: ' + '; '.join(g['problems'])
            return 2
        if s7.assignment(s7.all_partitions())[0] != 'ok':
            res['error'] = '선호 배정이 아니다. Q0 준비를 먼저 한다'
            return 2
        market_id = scalar("SELECT id FROM markets WHERE symbol = '%s'" % a.market)
        resource = scalar("SELECT p.resource FROM market_lease_partition p JOIN markets m ON m.id = p.market_id "
                          "WHERE m.symbol = '%s'" % a.market)
        if not market_id or resource not in s7.RESOURCES:
            res['error'] = '마켓이나 그 파티션을 찾지 못했다'
            return 2
        other = [r for r in s7.RESOURCES if r != resource][0]
        split = s7.partition_markets() or s7.DEFAULT_PARTITION_MARKETS
        res.update(resource=resource, survivor_resource=other)
        owner_before, view_before = partition_status(resource)
        old_epoch = (view_before.get(owner_before) or {}).get('epoch') if owner_before else None
        res.update(owner_before=owner_before, epoch_before=old_epoch)
        if old_epoch is None:
            res['error'] = '%s 의 소유 노드와 epoch 를 읽지 못했다' % resource
            return 2
        before = scrape_demotions()
        rc, err = psql_script(install_sql(market_id))
        installed = rc == 0
        res['install'] = {'rc': rc, 'stderr': err}
        if not installed:
            res['error'] = '트리거 설치 실패'
            return 1
        start = time.time()
        fired_at = None
        while time.time() - start < FIRE_WAIT_S:
            v = scalar("SELECT is_called FROM s7_fault_once")
            if v == 't':
                fired_at = time.time()
                break
            time.sleep(0.2)
        rc, err = psql_script(REMOVE_SQL)
        installed = rc != 0
        res['remove'] = {'rc': rc, 'stderr': err}
        if fired_at is None:
            res['error'] = '%d초 안에 그 마켓 체결이 트리거를 부르지 않았다(봇 부하 확인)' % FIRE_WAIT_S
            return 1
        res['injected_failures'] = 1
        res['fired_at'] = dt.datetime.fromtimestamp(fired_at, dt.timezone.utc).isoformat()

        def reacquired():
            # 치명 강등 뒤 재구성과 새 획득을 거친 것만 센다. 강등 전의 옛 epoch ACCEPTING 을 재획득으로 읽지 않는다.
            owner, view = partition_status(resource)
            epoch = (view.get(owner) or {}).get('epoch') if owner else None
            return owner if epoch is not None and int(epoch) > int(old_epoch) else None

        got, waited = s7.wait_for(reacquired, REACQUIRE_WAIT_S, 0.5)
        res['p1_reacquired_s'] = round(time.time() - fired_at, 2) if got else None
        res['owner_after'] = got
        time.sleep(15)
        after = scrape_demotions()
        res['demotions_before'], res['demotions_after'] = before, after
        res['demotions'] = demotion_deltas(before, after)
        _, view = partition_status(resource)
        res['partition_view'] = view
        res['breaker_open'] = any(((x or {}).get('breaker') or {}).get('open') for x in view.values()) if view else None
        rows, _ = s7.ack_rows(list(split.get(other) or []), fired_at - s7.ACK_BASELINE_S,
                              fired_at + (res['p1_reacquired_s'] or s7.RTO_LIMIT_S) + s7.ACK_WINDOW_AFTER_FIRST_TRADE_S)
        if rows is not None:
            facts, axes, positive = s7.ack_interval_judge(rows, fired_at, fired_at + (res['p1_reacquired_s'] or s7.RTO_LIMIT_S)
                                                          + s7.ACK_WINDOW_AFTER_FIRST_TRADE_S)
            res['p0_ack'] = {'facts': facts, 'axes': axes, 'verdict': s7.verdict_of(axes, positive)}
            res['p0_ack_ok'] = res['p0_ack']['verdict'] == 'PASS'
        else:
            res['p0_ack_ok'] = None
        if a.stop_before_post and re.match(r'^pml-s7-k6-[A-Za-z0-9_-]+$', a.stop_before_post):
            res['stop_before_post_rc'] = subprocess.run(['docker', 'stop', '-t', '10', a.stop_before_post], capture_output=True,
                                                        timeout=60).returncode
        post = ds.post_run('Q10-%s' % dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d-%H%M%S'))
        res['post'] = {k: v for k, v in post.items() if k != 'all_checks'}
        res['post_invariants_pass'] = post['run_status'] == 200 and gated_clean(post.get('all_checks'))
        code = 0
        return code
    except Exception as e:  # noqa: BLE001
        res['error'] = '%s: %s' % (type(e).__name__, e)
        return 1
    finally:
        if installed:
            res['remove_finally'] = psql_script(REMOVE_SQL)[0]
        res['finished'] = pgs.now_iso()
        Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
        print(json.dumps({k: res.get(k) for k in ('injected_failures', 'p1_reacquired_s', 'breaker_open', 'p0_ack_ok',
                                                  'post_invariants_pass', 'error')}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    sys.exit(main())
