"""patroni_axes 시험. 합성 표본으로 시각 추출(8-2)과 실험별 축(8-3), 회차 무효 조건(8-1)을 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import unittest

import ack_recorder as ar
import patroni_axes as pa

T0 = 1000.0
DB1, DB2 = 'patroni-db-1', 'patroni-db-2'
IP1, IP2 = '10.159.1.5/32', '10.159.1.6/32'


def status_series(start, end, suspended_from=None, suspended_to=None, nodes=('api-1', 'api-2')):
    out = []
    t = start
    while t <= end:
        for node in nodes:
            s = suspended_from is not None and suspended_from <= t < suspended_to
            out.append({'kind': 'status', 't': t, 'node': node, 'code': 200, 'trading_suspended': s,
                        'state': 'SUSPENDED' if s else 'ACTIVE'})
        t += 1.0
    return out


def cluster_series(start, end, leader_at, sync_at, timeline_at=None):
    """leader_at(t)->리더, sync_at(t)->동기 목록, timeline_at(t)->타임라인."""
    out = []
    t = start
    while t <= end:
        leader = leader_at(t)
        tl = timeline_at(t) if timeline_at else 1
        members = [{'name': leader, 'role': 'leader', 'state': 'running', 'timeline': tl}]
        out.append({'kind': 'cluster', 't': t, 'code': 200, 'leader': leader, 'sync': sync_at(t), 'timeline': tl,
                    'members': members})
        t += 0.5
    return out


def reads_series(start, end, fail_at=()):
    out = []
    t = start
    while t <= end:
        for path in ('markets', 'orderbook', 'trades', 'accounts', 'open_orders'):
            code = 503 if any(lo <= t < hi for lo, hi in fail_at) else 200
            out.append({'kind': 'read', 't': t, 'path': path, 'code': code})
        t += 1.0
    return out


def writes_series(start, end, code_at):
    out = []
    t = start
    while t <= end:
        out.append({'kind': 'write', 't': t, 'code': code_at(t)})
        t += 1.0
    return out


def ack_records(end, server_at, windows_acked=(), unknown_windows=()):
    """스레드 넷이 0.1초마다 한 번씩 시도한 기록. unknown_windows 안에 시작한 시도는 unknown, 그 밖은 acked 다."""
    recs = [{'type': 'start', 't': T0 - 60}]
    seq = {w: 0 for w in range(1, 5)}
    t = T0 - 60
    while t <= end:
        for w in range(1, 5):
            seq[w] += 1
            unknown = any(lo <= t < hi for lo, hi in unknown_windows)
            result = ar.UNKNOWN if unknown else ar.ACKED
            recs.append({'type': 'attempt', 'writer': w, 'n': ar.number(w, seq[w]), 't_start': round(t, 3),
                         't_end': round(t + (5 if unknown else 0.01), 3), 'result': result, 'server': server_at(t),
                         'reason': '' if result == ar.ACKED else 'socket_timeout'})
        t += 0.1
    for w in range(1, 5):
        recs.append({'type': 'alive', 'writer': w, 't': end, 'thread_alive': True})
    recs.append({'type': 'stop', 't': end + 0.5})
    return recs


def clean_facts(**over):
    facts = {
        'invariants': {'post': {'checks': [
            {'checkName': 'C01_ASSET_CONSERVATION', 'severity': 'CRITICAL', 'violatedRows': 0},
            {'checkName': 'C07P_DUP_ORDER_JOURNAL', 'severity': 'CRITICAL', 'violatedRows': 0},
            {'checkName': 'C13A_ORDER_MATERIALIZED', 'severity': 'HIGH', 'violatedRows': 0},
            {'checkName': 'C16_ACK_JOURNALED', 'severity': 'CRITICAL', 'violatedRows': 0},
            {'checkName': 'C17_REPLAY_DETERMINISM', 'severity': 'CRITICAL', 'violatedRows': 0}]}},
        'stale_write_delta': 0, 'orders_500': 0, 'syncrep_waiters_after_resume': 0, 'duplicate_coid_orders': 0,
        'withdraw_duplicates': 0,
        'metrics': {'before': {'demotions': 0, 'lease_acquire_expired': 0},
                    'after': {'demotions': 0, 'lease_acquire_expired': 0}},
        'post_promotion': {'fencing_rejections': 0, 'demotions': 0},
        'invalid': {'etcd_leader_changes': 0, 'fsync_p99_s': 0.004, 'peer_rtt_p99_s': 0.001,
                    'cpu_samples': [30, 35, 40, 32, 31], 'foreign_processes': []},
    }
    facts.update(over)
    return facts


def by_id(result, axis_id):
    return next(a for a in result['axes'] if a['id'] == axis_id)


class StandbyStopTest(unittest.TestCase):
    """E-P2a: T0 에 Standby 정지, 3초 뒤 중단, T0+90 에 복귀, 4초 뒤 동기, 6초 뒤 재개."""

    def setUp(self):
        self.t_back = T0 + 90
        self.timeline = {'scenario': 'E-P2a', 'run_id': 'EP2a-test', 't0': T0, 't_back': self.t_back,
                         'injected': DB2, 'role_injected': 'standby',
                         'pre': {'patroni_leader': DB1, 'timeline': 1, 'epoch': 1, 'app_leader': 'patroni-api-1'},
                         'post': {'patroni_leader': DB1, 'timeline': 1, 'epoch': 1},
                         'db_ips': {DB1: IP1, DB2: IP2}}
        end = self.t_back + 60
        self.observe = (
            status_series(T0 - 40, end, T0 + 3, self.t_back + 6)
            + cluster_series(T0 - 40, end, lambda t: DB1,
                             lambda t: [DB2] if t < T0 + 2 or t >= self.t_back + 4 else [])
            + reads_series(T0 - 40, end)
            + writes_series(T0 - 40, end, lambda t: 423 if T0 + 3 <= t < self.t_back + 6 else 401))
        self.records = ack_records(end, lambda t: IP1, unknown_windows=[(T0 + 1, self.t_back + 5)])
        self.verify = ar.verify_against(self.records, [r['n'] for r in ar.attempts(self.records)
                                                       if r['result'] == ar.ACKED])
        self.facts = clean_facts(first_trade_after_resume=self.t_back + 8)

    def run_judge(self, **kw):
        return pa.judge('E-P2a', kw.get('timeline', self.timeline), kw.get('observe', self.observe),
                        kw.get('records', self.records), kw.get('verify', self.verify), kw.get('facts', self.facts))

    def test_times_are_derived_from_samples(self):
        r = self.run_judge()
        self.assertEqual(T0 + 3, r['times']['T_suspend'])
        self.assertEqual(self.t_back + 4, r['times']['T_sync'])
        self.assertEqual(self.t_back + 6, r['times']['T_resume'])
        self.assertIsNone(r['times']['T_promote'])
        self.assertEqual(T0 + 2, r['times']['T_sync_lost'])

    def test_clean_run_passes(self):
        r = self.run_judge()
        failed = [a['id'] for a in r['axes'] if a['pass'] is not True]
        self.assertEqual([], failed, r['axes'])
        self.assertEqual('PASS', r['verdict'])
        self.assertEqual({423: 86}, by_id(r, 'writes_all_423_suspended')['value'], 'T_suspend+2 ~ T_back 양끝 포함')

    def test_write_429_during_suspension_fails(self):
        observe = [dict(s, code=429) if s['kind'] == 'write' and s['t'] == T0 + 30 else s for s in self.observe]
        r = self.run_judge(observe=observe)
        self.assertEqual('FAIL', r['verdict'])
        self.assertFalse(by_id(r, 'writes_429_suspended')['pass'])
        self.assertFalse(by_id(r, 'writes_all_423_suspended')['pass'])

    def test_missing_acked_row_fails_rpo(self):
        present = [r['n'] for r in ar.attempts(self.records) if r['result'] == ar.ACKED][1:]
        r = self.run_judge(verify=ar.verify_against(self.records, present))
        self.assertEqual('FAIL', r['verdict'])
        self.assertEqual(1, by_id(r, 'ackrec_rpo')['value'])

    def test_vacuous_recorder_is_not_judged(self):
        records = [x for x in self.records if not (x.get('type') == 'attempt' and x['t_start'] < T0)]
        r = self.run_judge(records=records)
        self.assertIsNone(by_id(r, 'ackrec_rpo')['pass'])
        self.assertEqual('INCONCLUSIVE', r['verdict'])

    def test_invalid_run_drops_time_axes_but_keeps_integrity(self):
        facts = clean_facts(first_trade_after_resume=self.t_back + 8,
                            invalid={'etcd_leader_changes': 2, 'fsync_p99_s': 0.2, 'cpu_samples': [95, 90, 85, 88, 92],
                                     'foreign_processes': ['java']})
        r = self.run_judge(facts=facts)
        self.assertTrue(r['invalid']['time_axes_invalid'])
        self.assertIsNone(by_id(r, 'suspend_after_inject_s')['pass'])
        self.assertTrue(by_id(r, 'post_invariants_gated')['pass'], '불변식은 무효 조건으로 면제하지 않는다')
        self.assertEqual('INCONCLUSIVE', r['verdict'])
        facts['stale_write_delta'] = 3
        self.assertEqual('FAIL', self.run_judge(facts=facts)['verdict'], '구 epoch 쓰기는 무효 회차에서도 FAIL 이다')

    def test_invariant_violation_fails(self):
        facts = clean_facts(first_trade_after_resume=self.t_back + 8)
        facts['invariants']['post']['checks'][1]['violatedRows'] = 1
        r = self.run_judge(facts=facts)
        self.assertFalse(by_id(r, 'post_invariants_gated')['pass'])
        self.assertFalse(by_id(r, 'post_invariants_named')['pass'])


class PrimaryKillTest(unittest.TestCase):
    """E-P1: T0 에 Primary kill, T0+20 에 db-2 승격(타임라인 2), T0+60 에 옛 노드 복귀, +4 동기, +6 재개, +10 첫 체결."""

    def setUp(self):
        self.t_back = T0 + 60
        self.t_promote = T0 + 20
        self.timeline = {'scenario': 'E-P1', 'run_id': 'EP1-test', 't0': T0, 't_back': self.t_back,
                         'injected': DB1, 'role_injected': 'primary',
                         'pre': {'patroni_leader': DB1, 'timeline': 1, 'epoch': 1, 'app_leader': 'patroni-api-1'},
                         'post': {'patroni_leader': DB2, 'timeline': 2, 'epoch': 2, 'app_leader': 'patroni-api-2'},
                         'db_ips': {DB1: IP1, DB2: IP2},
                         'haproxy_server': {DB1: 'db-1', DB2: 'db-2'}}
        end = self.t_back + 60
        self.observe = (
            status_series(T0 - 40, end, T0 + 6, self.t_back + 6)
            + cluster_series(T0 - 40, end, lambda t: DB1 if t < self.t_promote else DB2,
                             lambda t: [DB2] if t < T0 else ([DB1] if t >= self.t_back + 4 else []),
                             lambda t: 1 if t < self.t_promote else 2)
            + reads_series(T0 - 40, end, fail_at=[(T0 + 1, T0 + 22)])
            + [{'kind': 'haproxy', 't': T0 + x, 'node': 'haproxy-1', 'code': 200,
                'up': ['db-1'] if x < 3 else []} for x in range(0, 10)])

        def server_at(t):
            return IP1 if t < T0 else IP2
        # 새 Primary 로 보낸 시도는 승격부터 옛 노드(멤버)가 붙을 때까지 커밋 대기(unknown)다.
        self.records = ack_records(end, server_at, unknown_windows=[(T0, self.t_back + 4)]) + self.repl()
        self.verify = ar.verify_against(self.records, [r['n'] for r in ar.attempts(self.records)
                                                       if r['result'] == ar.ACKED])
        self.facts = clean_facts(t5=self.t_back + 10, rejoin={'equal': True, 'method': 'rewind'})

    def repl(self, member_start=None):
        start = self.t_back + 3.5 if member_start is None else member_start
        return [{'type': 'repl', 't': start + 0.3, 'server': IP2,
                 'rows': [{'app': DB1, 'state': 'streaming', 'sync_state': 'sync', 'backend_start': start, 'flush': True}]}]

    def run_judge(self, **kw):
        return pa.judge('E-P1', kw.get('timeline', self.timeline), kw.get('observe', self.observe),
                        kw.get('records', self.records), kw.get('verify', self.verify), kw.get('facts', self.facts))

    def test_failover_times_and_pass(self):
        r = self.run_judge()
        self.assertEqual(self.t_promote, r['times']['T_promote'])
        self.assertEqual(T0 + 22, r['times']['T_reads'])
        self.assertEqual(T0 + 3, r['times']['T_down'])
        self.assertEqual(self.t_back + 4, r['times']['T_sync'])
        failed = [a['id'] for a in r['axes'] if a['pass'] is not True]
        self.assertEqual([], failed, [a for a in r['axes'] if a['pass'] is not True])
        self.assertEqual('PASS', r['verdict'])

    def test_confirmed_write_between_promote_and_sync_fails(self):
        records = list(self.records)
        records.append({'type': 'attempt', 'writer': 1, 'n': ar.number(1, 10 ** 6), 't_start': self.t_promote + 5,
                        't_end': self.t_promote + 5.2, 'result': ar.ACKED, 'server': IP2, 'reason': ''})
        verify = ar.verify_against(records, [r['n'] for r in ar.attempts(records) if r['result'] == ar.ACKED])
        r = self.run_judge(records=records, verify=verify)
        self.assertFalse(by_id(r, 'confirmed_writes_before_member')['pass'], '멤버 walsender 가 붙기 전에 확인됐다')
        self.assertEqual('FAIL', r['verdict'])

    def test_writes_confirmed_after_member_attaches_are_not_violations(self):
        # 2026-09-25 E-P1 첫 회차: '*' 아래 옛 노드가 붙자 커밋이 확인됐고 /cluster 의 sync_standby 는 3.9초 뒤였다.
        records = ack_records(self.t_back + 60, lambda t: IP1 if t < T0 else IP2,
                              unknown_windows=[(T0, self.t_back + 1)]) + self.repl(member_start=self.t_back + 0.5)
        verify = ar.verify_against(records, [r['n'] for r in ar.attempts(records) if r['result'] == ar.ACKED])
        r = self.run_judge(records=records, verify=verify)
        self.assertTrue(by_id(r, 'confirmed_writes_before_member')['pass'])
        self.assertGreater(r['extra']['acked_between_promote_and_cluster_sync'], 0, '설계 문구의 수는 기록만 한다')

    def test_missing_replication_samples_are_not_judged(self):
        records = [x for x in self.records if x.get('type') != 'repl']
        self.assertIsNone(by_id(self.run_judge(records=records), 'confirmed_writes_before_member')['pass'])

    def test_hold_deviation_makes_the_run_inconclusive(self):
        timeline = dict(self.timeline, hold_s=60, t_back=T0 + 675)
        r = self.run_judge(timeline=timeline)
        self.assertIsNone(by_id(r, 'protocol_hold')['pass'])
        self.assertIsNone(by_id(r, 'promote_s')['pass'], '시간 축은 판정에서 뺀다')
        self.assertIn(r['verdict'], ('INCONCLUSIVE', 'FAIL'))

    def test_confirmed_axis_needs_attempts_to_new_primary(self):
        def old_only(t):
            return IP1
        records = ack_records(self.t_back + 60, old_only, unknown_windows=[(T0, self.t_back + 4)]) + self.repl()
        verify = ar.verify_against(records, [r['n'] for r in ar.attempts(records) if r['result'] == ar.ACKED])
        r = self.run_judge(records=records, verify=verify)
        self.assertIsNone(by_id(r, 'confirmed_writes_before_member')['pass'], '새 Primary 로 보낸 시도가 없으면 공허하다')

    def test_rejoin_mismatch_fails(self):
        facts = clean_facts(t5=self.t_back + 10, rejoin={'equal': False, 'method': 'rewind'})
        self.assertFalse(by_id(self.run_judge(facts=facts), 'rejoin_nodes_consistent')['pass'])

    def test_epoch_must_increase_by_exactly_one(self):
        self.timeline['post']['epoch'] = 3
        self.assertFalse(by_id(self.run_judge(), 'app_epoch_increase')['pass'])


class SlowPathTest(unittest.TestCase):

    def test_ep7_requires_no_suspension_on_slow_node_and_nonvacuous_probe(self):
        timeline = {'scenario': 'E-P7', 'run_id': 'EP7-test', 't0': T0, 't_back': T0 + 60,
                    'pre': {'epoch': 1}, 'post': {'epoch': 2}}
        observe = status_series(T0 - 10, T0 + 90)
        facts = clean_facts(t5=T0 + 25)
        facts['metrics'] = {'before': {'gate_423': 0, 'probe_unconfirmed': 0, 'lease_acquire_expired': 0},
                            'after': {'gate_423': 0, 'probe_unconfirmed': 4, 'lease_acquire_expired': 0}}
        r = pa.judge('E-P7', timeline, observe, [], None, facts)
        self.assertTrue(by_id(r, 'api1_suspended_samples')['pass'])
        self.assertTrue(by_id(r, 'probe_unconfirmed_increase')['pass'])
        self.assertTrue(by_id(r, 't5_after_inject_s')['pass'])
        facts['metrics']['after']['probe_unconfirmed'] = 0
        self.assertFalse(by_id(pa.judge('E-P7', timeline, observe, [], None, facts), 'probe_unconfirmed_increase')['pass'])

    def test_ep7_counts_only_replication_suspension_on_the_slow_node(self):
        timeline = {'scenario': 'E-P7', 'run_id': 'EP7-test', 't0': T0, 't_back': T0 + 60,
                    'pre': {'epoch': 1}, 'post': {'epoch': 2}}
        observe = [{'kind': 'status', 't': T0 + i, 'node': 'api-1', 'code': 200, 'trading_suspended': True,
                    'reason': 'database_unavailable'} for i in range(1, 30)]
        facts = clean_facts(t5=T0 + 25)
        r = pa.judge('E-P7', timeline, observe, [], None, facts)
        self.assertTrue(by_id(r, 'api1_suspended_samples')['pass'], '느린 경로의 UNREACHABLE 은 클러스터 중단이 아니다')
        self.assertEqual({'n': 29, 'count': 29}, r['extra']['api1_unreachable'], 'UNREACHABLE 표본은 기록으로 남긴다')
        observe.append({'kind': 'status', 't': T0 + 31, 'node': 'api-1', 'code': 200, 'trading_suspended': True,
                        'reason': 'replication_unavailable'})
        r = pa.judge('E-P7', timeline, observe, [], None, facts)
        self.assertFalse(by_id(r, 'api1_suspended_samples')['pass'])

    def test_record_only_scenarios(self):
        timeline = {'scenario': 'E-P4c', 'run_id': 'x', 't0': T0}
        self.assertEqual('RECORD', pa.judge('E-P4c', timeline, [], [], None, clean_facts())['verdict'])


class PoolTest(unittest.TestCase):

    def test_pool_maxima_per_node(self):
        observe = [{'kind': 'pool', 't': 1, 'node': 'api-1', 'code': 200, 'active': {'HikariPool-1': 3.0},
                    'inflight': {'query': 1.0}},
                   {'kind': 'pool', 't': 2, 'node': 'api-1', 'code': 200, 'active': {'HikariPool-1': 9.0},
                    'inflight': {'query': 0.0}},
                   {'kind': 'pool', 't': 2, 'node': 'api-2', 'code': 0}]
        self.assertEqual({'api-1': {'active': {'HikariPool-1': 9.0}, 'inflight': {'query': 1.0}}},
                         pa.pool_maxima(observe))
        self.assertIsNone(pa.pool_maxima([]))


class WithdrawTest(unittest.TestCase):

    def test_uncertain_keys_are_resent_with_the_same_key(self):
        observe = [{'kind': 'withdraw', 't': 1, 'key': 'a', 'resend': False, 'code': 201},
                   {'kind': 'withdraw', 't': 2, 'key': 'b', 'resend': False, 'code': 423},
                   {'kind': 'withdraw', 't': 3, 'key': 'c', 'resend': False, 'code': 423},
                   {'kind': 'withdraw', 't': 4, 'key': 'd', 'resend': False, 'code': 201},
                   {'kind': 'withdraw', 't': 5, 'key': 'b', 'resend': True, 'code': 201},
                   {'kind': 'withdraw_end', 't': 6, 'pending': ['c']}]
        axes, summary = pa.withdraw_axes(observe, {'withdraw_duplicates': 0})
        self.assertTrue(axes[0]['pass'])
        self.assertEqual(2, summary['uncertain_keys'])
        self.assertEqual(1, summary['resent_same_key'])
        self.assertEqual(['c'], summary['unresolved'])


if __name__ == '__main__':
    unittest.main()
