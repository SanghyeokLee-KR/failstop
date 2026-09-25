"""S7 실행기의 순수 함수 시험(ADR 0034 S7, S7 설계 5-2·6-3). 도커·DB·HTTP 없이 돈다.

Q1 s7_double_spend.py(회 계획, 금액, 응답 분류, 계수 해석), Q8 s7_fee_coupling.py(잠금 표본 줄, 표본 문장, 정산 지연), Q10
s7_market_fatal.py(한 번만 실패하는 트리거, 강등 증분, 게이팅), Q14 s7_c22_cost.py(앱 enum 문장 추출, 측정 트랜잭션, 판정)를 본다.

돌리는 법: python -B -m unittest harness/scale/test_s7_executors.py
"""
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import s7_axes as s7  # noqa: E402
import s7_c22_cost as cost  # noqa: E402
import s7_double_spend as ds  # noqa: E402
import s7_fee_coupling as fee  # noqa: E402
import s7_market_fatal as fatal  # noqa: E402


class DoubleSpendPlanTest(unittest.TestCase):

    def test_even_rounds_buy_on_both_partition_owners(self):
        self.assertEqual([('order', 'KRW-BTC', 'chaos-api-1'), ('order', 'KRW-ETH', 'chaos-api-2')], ds.plan_round(0))
        self.assertEqual(ds.plan_round(0), ds.plan_round(2))

    def test_odd_rounds_pair_a_buy_with_a_withdraw_on_the_other_node(self):
        self.assertEqual([('order', 'KRW-BTC', 'chaos-api-1'), ('withdraw', None, 'chaos-api-2')], ds.plan_round(1))
        self.assertEqual([('order', 'KRW-ETH', 'chaos-api-2'), ('withdraw', None, 'chaos-api-1')], ds.plan_round(3),
                         '홀수 회의 마켓은 번갈아 고른다')
        for i in range(1, 40, 2):
            (_, _, order_node), (_, _, withdraw_node) = ds.plan_round(i)
            self.assertNotEqual(order_node, withdraw_node, '출금은 다른 노드로 보낸다')

    def test_amounts_are_decimal_strings(self):
        spend = ds.spend_of('100000001')
        self.assertIsInstance(spend, Decimal)
        self.assertEqual(Decimal('80000000'), spend, 'KRW 정수로 내린다')
        price, volume = ds.order_terms(spend, '1000', 8)
        self.assertEqual(('1000000', '80'), (price, volume))
        price, volume = ds.order_terms(Decimal('80000001'), '100', 8)
        self.assertEqual('100000', price)
        self.assertEqual('800.00001', volume, '수량은 qty_scale 로 내린다(부동소수점 없음)')
        self.assertLessEqual(Decimal(price) * Decimal(volume), Decimal('80000001'))
        with self.assertRaises(ValueError):
            ds.order_terms(Decimal('10'), '1000', 0)

    def test_two_actions_cannot_both_fit(self):
        x = Decimal('100000000')
        self.assertGreater(ds.spend_of(x) * 2, x, '두 행위 모두 X 의 80% 라 동시에 성공할 수 없다')
        self.assertLessEqual(ds.spend_of(x), x)

    def test_classify(self):
        self.assertEqual('success', ds.classify('order', 201, {'id': 'o1', 'status': 'OPEN'}))
        self.assertEqual('insufficient', ds.classify('order', 400, {'error': {'name': 'insufficient_funds'}}))
        self.assertEqual('other', ds.classify('order', 400, {'error': {'name': 'invalid_price'}}))
        self.assertEqual('success', ds.classify('withdraw', 201, {'external_id': 't1', 'status': 'REQUESTED'}))
        self.assertEqual('insufficient', ds.classify('withdraw', 422, {'error': {'name': 'insufficient_funds'}}))
        self.assertEqual('other', ds.classify('withdraw', 500, {'error': {'name': 'server_error'}}))
        self.assertEqual('other', ds.classify('order', 0, {'transport_error': 'TimeoutError'}))

    def test_counters_and_fatal_delta(self):
        text = '\n'.join([
            'exchange_settlement_insufficient_funds_total{application="exchange",} 2.0',
            'exchange_partition_demotions_total{application="exchange",partition="matching-p1",reason="WRITER_FATAL",} 1.0',
            'exchange_partition_demotions_total{application="exchange",partition="matching-p1",reason="PLANNED",} 3.0',
        ])
        got = ds.parse_node_counters(text)
        self.assertEqual(2.0, got['insufficient'])
        self.assertEqual({'matching-p1|WRITER_FATAL': 1.0, 'matching-p1|PLANNED': 3.0}, got['demotions'])
        before = {'matching-p1|WRITER_FATAL': 1.0, 'matching-p1|PLANNED': 1.0}
        self.assertEqual(0.0, ds.fatal_delta(before, got['demotions']), '계획 강등(PLANNED)은 치명이 아니다')
        self.assertEqual(1.0, ds.fatal_delta({}, got['demotions']))
        self.assertIsNone(ds.fatal_delta(None, got['demotions']))

    def test_summarize_feeds_the_judge(self):
        rounds = [
            {'i': 0, 'successes': 1, 'restored': True, 'results': [
                {'kind': 'order', 'status': 201, 'class': 'success'},
                {'kind': 'order', 'status': 400, 'class': 'insufficient'}]},
            {'i': 1, 'successes': 1, 'restored': False, 'results': [
                {'kind': 'order', 'status': 500, 'class': 'other'},
                {'kind': 'withdraw', 'status': 201, 'class': 'success'}]},
        ]
        got = ds.summarize(rounds)
        self.assertEqual(1, got['order_insufficient'])
        self.assertEqual(0, got['withdraw_insufficient'])
        self.assertEqual(1, got['server_errors'])
        self.assertEqual(1, got['restore_failures'])
        res = dict(got, rounds=rounds, requested_rounds=2, settlement_insufficient_delta=0, fatal_demotions_delta=0,
                   post_invariants={'C01': 0, 'C14': 0, 'C31': 0})
        _, axes, positive = s7.double_spend_judge(res)
        self.assertEqual('FAIL', s7.verdict_of(axes, positive))
        self.assertFalse(axes['server_errors_0'])
        self.assertFalse(axes['balance_restore_failures_0'])


class FeeCouplingTest(unittest.TestCase):

    def test_lock_lines(self):
        self.assertEqual(('chaos-api-1', 4242), fee.parse_lock_line('LOCK|chaos-api-1|4242'))
        self.assertEqual(('', 7), fee.parse_lock_line('LOCK||7'))
        self.assertIsNone(fee.parse_lock_line('TICK|12:00:00.020'))
        self.assertIsNone(fee.parse_lock_line('Thu Sep 25 12:00:00 2026 (every 0.02s)'))

    def test_lock_query_joins_the_fee_row_and_ticks(self):
        sql = fee.lock_query(42)
        self.assertIn("pgrowlocks('balances')", sql)
        self.assertIn('b.ctid = l.locked_row', sql)
        self.assertIn('b.account_id = 42', sql)
        self.assertIn("'TICK|'", sql, '표본기가 살아 있음을 표본마다 알린다')
        with self.assertRaises(ValueError):
            fee.lock_query('1; DROP TABLE balances')

    def test_settle_max(self):
        self.assertEqual(1.25, fee.settle_max([{'lag_s': 0.2}, {'lag_s': '1.25'}, {'lag_s': None}]))
        self.assertIsNone(fee.settle_max([]))


class MarketFatalTest(unittest.TestCase):

    def test_trigger_fails_only_the_first_row_of_the_market(self):
        sql = fatal.install_sql(3)
        self.assertIn('IF NEW.market_id = 3 THEN', sql)
        self.assertIn("IF nextval('s7_fault_once') = 1 THEN", sql, '다른 마켓 행은 시퀀스를 쓰지 않는다(IF 를 겹친다)')
        self.assertIn("ERRCODE = 'S7F01'", sql)
        self.assertIn('BEFORE INSERT ON trades', sql)
        self.assertIn('(market %)', sql, 'RAISE 형식 문자열의 % 자리')
        for name in ('DROP TRIGGER IF EXISTS trg_s7_fault_once', 'DROP FUNCTION IF EXISTS s7_fault_once()',
                     'DROP SEQUENCE IF EXISTS s7_fault_once'):
            self.assertIn(name, fatal.REMOVE_SQL)

    def test_demotion_deltas(self):
        before = {'chaos-api-1': {'matching-p0|PLANNED': 1.0}, 'chaos-api-2': {'matching-p1|WRITER_FATAL': 0.0}}
        after = {'chaos-api-1': {'matching-p0|PLANNED': 1.0}, 'chaos-api-2': {'matching-p1|WRITER_FATAL': 1.0}}
        self.assertEqual({'matching-p1': {'WRITER_FATAL': 1}}, fatal.demotion_deltas(before, after))
        self.assertIsNone(fatal.demotion_deltas(None, after))
        res = {'injected_failures': 1, 'demotions': fatal.demotion_deltas(before, after), 'breaker_open': False,
               'p1_reacquired_s': 14.2, 'p0_ack_ok': True, 'post_invariants_pass': True}
        _, axes, positive = s7.market_fatal_judge(res)
        self.assertEqual('PASS', s7.verdict_of(axes, positive), axes)

    def test_gated_clean_matches_lib_ps1(self):
        ok = [{'checkName': 'C01_ASSET_CONSERVATION', 'severity': 'CRITICAL', 'violatedRows': 0},
              {'checkName': 'C13A_ORDER_MATERIALIZED', 'severity': 'CRITICAL', 'violatedRows': 3},
              {'checkName': 'C24_POSITION_COST_QTY', 'severity': 'WARN', 'violatedRows': 9}]
        self.assertTrue(fatal.gated_clean(ok))
        self.assertFalse(fatal.gated_clean(ok + [{'checkName': 'C30_FENCING_RESOURCE_MATCH', 'severity': 'CRITICAL',
                                                  'violatedRows': 1}]))
        self.assertFalse(fatal.gated_clean([]), '체크가 없으면 통과가 아니다')
        self.assertFalse(fatal.gated_clean([{'severity': 'CRITICAL', 'violatedRows': 0}]), '이름 없는 체크는 판정 불능')


class C22CostTest(unittest.TestCase):

    def test_extracts_the_app_enum_count_statement(self):
        sql = cost.c22_sql(cost.INVARIANT_JAVA.read_text(encoding='utf-8'))
        self.assertTrue(sql.startswith('WITH seg AS ('))
        self.assertIn('width_bucket(c.fencing_token, s.lb)', sql, "C22' 수 판")
        self.assertIn('FROM snapshots n CROSS JOIN seg s', sql, '구간 경계 팔의 스냅샷 항')
        self.assertNotIn('"""', sql)
        with self.assertRaises(ValueError):
            cost.c22_sql('enum X {}')

    def test_explain_runs_like_the_authoritative_run(self):
        script = cost.explain_script('SELECT 1;')
        self.assertTrue(script.startswith('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;'))
        self.assertIn('SET LOCAL jit = off;', script)
        self.assertIn('EXPLAIN (ANALYZE, BUFFERS)\nSELECT 1;', script)
        self.assertTrue(script.rstrip().endswith('COMMIT;'))
        self.assertEqual(1296.302, cost.execution_ms('Planning Time: 0.1 ms\n Execution Time: 1296.302 ms'))
        self.assertIsNone(cost.execution_ms('ERROR: canceling statement'))

    def test_migrations_sorted_by_version_number(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ('V10__b.sql', 'V2__a.sql', 'V0__roles.sql', 'README.md', 'V55__x.sql'):
                (Path(d) / name).write_text('', encoding='utf-8')
            self.assertEqual(['V0__roles.sql', 'V2__a.sql', 'V10__b.sql', 'V55__x.sql'],
                             [p.name for p in cost.migration_files(d)])
        names = [p.name for p in cost.migration_files(cost.MIGRATIONS)]
        self.assertEqual('V55__partition_protocol_cutover.sql', names[-1])

    def test_cost_judge(self):
        axes, positive = cost.cost_judge(1_000_000, [1200.0, 1499.0, 1500.0])
        self.assertEqual('PASS', s7.verdict_of(axes, positive))
        axes, positive = cost.cost_judge(1_000_000, [1200.0, 1500.1, 1300.0])
        self.assertEqual('FAIL', s7.verdict_of(axes, positive), '세 번 가운데 하나라도 문턱을 넘으면 FAIL')
        axes, positive = cost.cost_judge(999_999, [1.0, 1.0, 1.0])
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(axes, positive), '100만 행 미만은 판정력이 없다')
        axes, positive = cost.cost_judge(1_000_000, [1.0, None, 1.0])
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(axes, positive))


if __name__ == '__main__':
    unittest.main()
