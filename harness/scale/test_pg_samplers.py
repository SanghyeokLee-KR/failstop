"""pg_samplers 와 왕복 프로브 계측 파서 시험. 채집 실패가 0 으로 접히지 않는지를 본다."""
import unittest

import pg_samplers as pgs
import roundtrip_probe as rp


def counter(wait, ok=True, alive=True, blockers=(100,)):
    if not ok:
        return {'ok': False, 'k6_alive': alive, 'counter_row_wait': None, 'prologue_active': None, 'waiters': None}
    return {'ok': True, 'k6_alive': alive, 'counter_row_wait': wait, 'prologue_active': wait,
            'waiters': [{'pid': 1, 'blockers': list(blockers), 'counter_row': True}] if wait else []}


def conn(n, max_conn=100, reserved=3):
    return {'ok': True, 'max_connections': max_conn, 'superuser_reserved_connections': reserved,
            'client_backends': n, 'groups': [], 'ts': 't'}


class ParseTest(unittest.TestCase):
    def test_failures_are_not_rows(self):
        self.assertIsNone(pgs.parse_json_row(1, '', 'boom')[0])
        self.assertIsNone(pgs.parse_json_row(None, '', 'TimeoutExpired')[0])
        self.assertIsNone(pgs.parse_json_row(0, '  ', '')[0])
        self.assertIsNone(pgs.parse_json_row(0, 'UPDATE 0', '')[0])
        self.assertEqual(pgs.parse_json_row(0, '{"a": 1}\n', ''), ({'a': 1}, None))

    def test_counter_record_needs_counter_table(self):
        rec = pgs.counter_row_record({'counter_rel': False, 'counter_row_wait': 0}, None)
        self.assertFalse(rec['ok'])
        self.assertIsNone(rec['counter_row_wait'])
        rec = pgs.counter_row_record(None, 'psql 빈 출력')
        self.assertFalse(rec['ok'])
        self.assertEqual(rec['error'], 'psql 빈 출력')


class CounterSummaryTest(unittest.TestCase):
    def test_failed_samples_are_not_zero(self):
        s = pgs.summarize_counter_samples([counter(0), counter(0, ok=False)])
        self.assertEqual((s['채집 성공'], s['채집 실패'], s['대기 합']), (1, 1, 0))
        s = pgs.summarize_counter_samples([counter(0, ok=False)] * 3)
        self.assertEqual(s['채집 성공'], 0)
        self.assertIsNone(s['대기 합'])

    def test_only_samples_while_k6_runs(self):
        s = pgs.summarize_counter_samples([counter(2), counter(5, alive=False)])
        self.assertEqual((s['주행 중 표본'], s['대기 합'], s['대기 > 0 표본']), (1, 2, 1))


class ControlTest(unittest.TestCase):
    def test_states(self):
        self.assertEqual(pgs.control_verdict([counter(1)], 0, 0)['상태'], 'PASS')
        self.assertEqual(pgs.control_verdict([counter(1, blockers=())], 0, 0)['상태'], 'FAIL')
        self.assertEqual(pgs.control_verdict([counter(0)], 0, 0)['상태'], 'FAIL')
        self.assertEqual(pgs.control_verdict([counter(0, ok=False)], 0, 0)['상태'], 'NOT RUN')
        self.assertEqual(pgs.control_verdict([counter(0)], 0, 1)['상태'], 'NOT RUN')
        self.assertEqual(pgs.control_verdict([], 'OSError', None)['상태'], 'NOT RUN')


class SplitTest(unittest.TestCase):
    PARSED = {'counter_rel': True, 'market_rel': True, 'prologue_active': 3, 'prologue_lock_wait': 2,
              'advisory_wait': 0, 'counter_row_wait': 1, 'market_row_wait': 1, 'both_row_wait': 0,
              'waiters': [{'pid': 1, 'blockers': [9], 'advisory': False, 'counter_row': True, 'market_row': False},
                          {'pid': 2, 'blockers': [8], 'advisory': False, 'counter_row': False, 'market_row': True}]}

    def test_split_follows_table_lock(self):
        rec = pgs.counter_split_record(self.PARSED, None)
        self.assertTrue(rec['ok'])
        self.assertEqual((rec['global_row_wait'], rec['market_row_wait']), (1, 1))
        self.assertEqual([(w['global_row'], w['market_row']) for w in rec['waiters']], [(True, False), (False, True)])
        self.assertEqual(pgs.counter_row_record(self.PARSED, None)['counter_row_wait'], 1)

    def test_waiter_on_both_tables_fails_split_sample_only(self):
        both = dict(self.PARSED, both_row_wait=1)
        rec = pgs.counter_split_record(both, None)
        self.assertFalse(rec['ok'])
        self.assertIsNone(rec['global_row_wait'])
        self.assertTrue(pgs.counter_row_record(both, None)['ok'])

    def test_unresolved_table_fails_sample(self):
        self.assertFalse(pgs.counter_split_record(dict(self.PARSED, market_rel=False), None)['ok'])
        rec = pgs.counter_split_record(None, 'psql 빈 출력')
        self.assertEqual((rec['ok'], rec['error'], rec['market_row_wait']), (False, 'psql 빈 출력', None))

    def test_summary_and_control_by_class(self):
        market_only = dict(self.PARSED, counter_row_wait=0, waiters=[self.PARSED['waiters'][1]])
        rec = dict(pgs.counter_split_record(market_only, None), k6_alive=True)
        self.assertEqual(pgs.summarize_counter_samples([rec, rec], 'market_row_wait')['대기 합'], 2)
        self.assertEqual(pgs.summarize_counter_samples([rec, rec], 'global_row_wait')['대기 합'], 0)
        self.assertEqual(pgs.control_verdict([rec], 0, 0, 'market_row_wait', 'market_row')['상태'], 'PASS')
        self.assertEqual(pgs.control_verdict([rec], 0, 0, 'global_row_wait', 'global_row')['상태'], 'FAIL')


class ConnectionTest(unittest.TestCase):
    def test_limit_is_max_minus_superuser_reserved(self):
        s = pgs.summarize_connection_samples([conn(40), conn(97)])
        self.assertEqual((s['한도'], s['최대 연결'], s['한도 도달']), (97, 97, True))
        self.assertFalse(pgs.summarize_connection_samples([conn(96)])['한도 도달'])
        self.assertIsNone(pgs.summarize_connection_samples([{'ok': False}])['한도 도달'])

    def test_verdict_states(self):
        ok_log = {'ok': True, '줄 수': 0}
        reached = pgs.summarize_connection_samples([conn(97)])
        clear = pgs.summarize_connection_samples([conn(10)])
        empty = pgs.summarize_connection_samples([])
        self.assertEqual(pgs.connection_verdict(reached, ok_log)['상태'], '증거 불완전(연결 고갈)')
        self.assertEqual(pgs.connection_verdict(clear, {'ok': True, '줄 수': 2})['상태'], '증거 불완전(연결 고갈)')
        self.assertEqual(pgs.connection_verdict(clear, ok_log)['상태'], '고갈 없음')
        self.assertEqual(pgs.connection_verdict(clear, {'ok': False})['상태'], 'NOT RUN')
        self.assertEqual(pgs.connection_verdict(empty, ok_log)['상태'], 'NOT RUN')
        self.assertEqual(pgs.connection_verdict(reached, {'ok': False})['상태'], '증거 불완전(연결 고갈)')

    def test_rejection_lines(self):
        text = ('LOG:  checkpoint starting\n'
                'FATAL:  sorry, too many clients already\n'
                'FATAL:  remaining connection slots are reserved for roles with the SUPERUSER attribute\n')
        self.assertEqual(pgs.count_exhaustion_lines(text)['줄 수'], 2)
        self.assertEqual(pgs.count_exhaustion_lines('')['줄 수'], 0)


class BudgetTest(unittest.TestCase):
    def budget(self, append_pool, max_conn, admin=0, tooling=0):
        return pgs.connection_budget(2, 32, 2, append_pool, 10, admin, 2, tooling, max_conn, 3)

    def test_stage4_stack_exceeds_default(self):
        b = self.budget(14, 100)
        self.assertEqual((b['API 노드'], b['봇'], b['합'], b['한도'], b['판정']), (96, 26, 124, 97, '부족'))
        self.assertEqual(self.budget(14, 200, admin=16, tooling=4)['합'], 160)
        self.assertEqual(self.budget(14, 200, admin=16, tooling=4)['판정'], '충분')

    def test_baseline_fits_default(self):
        self.assertEqual(self.budget(2, 100)['합'], 88)
        self.assertEqual(self.budget(2, 100)['판정'], '충분')

    def test_total_equal_to_limit_is_short(self):
        self.assertEqual(self.budget(2, 91)['판정'], '부족')


class PromTextTest(unittest.TestCase):
    TEXT = '\n'.join([
        '# HELP exchange_submit_count_total 접수 시도 수',
        '# TYPE exchange_submit_count_total counter',
        'exchange_submit_count_total{application="exchange",market="3"} 120.0',
        'exchange_submit_count_total{market="4",note="a\\"b"} 7 1726000000000',
        'exchange_submit_lock_hold_nanos_total 5.5E9',
        'hikaricp_connections_pending{pool="exchange-append"} 0.0',
    ])

    def test_parse(self):
        rows = rp.parse_prom_text(self.TEXT, rp.SCRAPE_NAMES)
        self.assertEqual(len(rows), 3)
        self.assertEqual(rows[0]['labels']['market'], '3')
        self.assertEqual(rows[1]['value'], 7.0)
        self.assertEqual(rows[2]['labels'], {})
        self.assertEqual(rows[2]['value'], 5.5e9)

    def test_counter_delta(self):
        def scrape(v3, v4):
            series = [{'name': 'exchange_submit_count_total', 'labels': {'market': '3'}, 'value': v3},
                      {'name': 'exchange_submit_count_total', 'labels': {'market': '4'}, 'value': v4}]
            return {'18080': {'ok': True, 'series': series},
                    '28080': {'ok': True, 'series': []}}
        delta = rp.counter_delta(scrape(10, 5), scrape(40, 6), 'exchange_submit_count_total')
        self.assertEqual(delta, {'3': 30.0, '4': 1.0})
        self.assertIsNone(rp.counter_delta(scrape(10, 5), scrape(9, 6), 'exchange_submit_count_total'))
        broken = scrape(40, 6)
        broken['28080'] = {'ok': False, 'series': None}
        self.assertIsNone(rp.counter_delta(scrape(10, 5), broken, 'exchange_submit_count_total'))


if __name__ == '__main__':
    unittest.main()
