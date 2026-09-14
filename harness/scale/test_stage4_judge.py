"""stage4_judge 분기 시험. 합성 입력으로 각 판정이 실제로 나오는지와 경계를 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import unittest

import stage4_judge as sj

MARKETS = ['KRW-BTC', 'KRW-ETH', 'KRW-SOL', 'KRW-XRP', 'KRW-DOGE', 'KRW-ADA',
           'KRW-TRX', 'KRW-LINK', 'KRW-DOT', 'KRW-MATIC', 'KRW-AVAX', 'KRW-ATOM']


def counter_sample(wait, ok=True, alive=True):
    if not ok:
        return {'ok': False, 'error': 'psql 종료코드 1', 'k6_alive': alive, 'prologue_active': None,
                'prologue_lock_wait': None, 'advisory_wait': None, 'counter_row_wait': None, 'waiters': None}
    waiters = [{'pid': 200 + i, 'blockers': [100], 'advisory': False, 'counter_row': True} for i in range(wait)]
    return {'ok': True, 'error': None, 'k6_alive': alive, 'prologue_active': wait + 1,
            'prologue_lock_wait': wait, 'advisory_wait': 0, 'counter_row_wait': wait, 'waiters': waiters}


def conn_sample(n, ok=True):
    if not ok:
        return {'ok': False, 'error': 'psql 빈 출력', 'max_connections': None,
                'superuser_reserved_connections': None, 'reserved_connections': None,
                'client_backends': None, 'groups': None, 'ts': 't'}
    return {'ok': True, 'error': None, 'max_connections': 200, 'superuser_reserved_connections': 3,
            'reserved_connections': 0, 'client_backends': n, 'groups': [], 'ts': 't', 'hikari': None}


def cell(label):
    return {
        'rate': 600, 'label': label,
        '접수/s': 300.0, '수용거절/s': 300.0,
        '락 보유 점유': 1.0, '락 보유 1회(ms)': 2.0, '  저널삽입 1회(ms)': 0.5, '  커밋(ms)': 0.75,
        '커밋 동시성': 0.3, 'k6_rc': 0, 'fsync fdatasync/s': 2000.0, 'fsync 건당(ms)': 0.5,
        'append 풀 대기': 0.0, 'append 풀 대기 최대(1m)': 0.0,
        '락 보유 점유(마켓별)': {'': 1.0},
        '접수(DB, 마켓별)': {'k6 마켓': list(MARKETS), 'error': None,
                         '마켓': [{'id': i + 1, 'symbol': m, 'k6_new': 100, 'all_commands': 150}
                                for i, m in enumerate(MARKETS)]},
        '카운터 행 표본': {'간격(초)': 15, '표본': [counter_sample(0) for _ in range(8)]},
        '카운터 행 양성 대조': {'보유 세션 종료코드': 0, '대기 세션 종료코드': 0, '표본': [counter_sample(1)]},
        '연결': {'표본': [conn_sample(60)], '로그': {'ok': True, 'error': None, '줄 수': 0, '예시': []}},
        '메타': {'시작': '2026-09-15T10:00:00+09:00', 'ENV': {'SCALE_POOL': '32'},
               'compose sha256': {'docker-compose.chaos.yml': 'c1', 'docker-compose.scale.yml': 's1'},
               'k6': {'markets': list(MARKETS), 'duration': 130, 'bots_ops': 20, 'keys': 4},
               '컨테이너 이미지': {'chaos-api-1': {'image_id': 'sha256:%s' % label.lower()}}},
    }


def pair(b4_accept=330.0, wait=1):
    """B0 는 락 보유 1회 2.0, 점유 1.0, B4 는 삽입 0.5 커밋 0.75 라 A 가 정확히 1.6 이다."""
    b0, b4 = cell('B0'), cell('B4')
    b4['락 보유 점유(마켓별)'] = {str(i): 0.2 for i in range(1, 13)}
    b4['접수/s'] = b4_accept
    b4['카운터 행 표본']['표본'] = [counter_sample(wait) for _ in range(8)]
    return b0, b4


def verdict(b0, b4):
    return sj.judge(b0, b4)['최종 판정']


class DecisionTest(unittest.TestCase):
    def test_flat_with_waits(self):
        r = sj.judge(*pair(330.0, wait=1))
        self.assertEqual(r['최종 판정'], '평평')
        self.assertEqual(sj.EXIT[r['최종 판정']], 0)

    def test_ratio_exactly_115_is_flat(self):
        r = sj.judge(*pair(345.0))
        self.assertEqual(r['T1']['비'], 1.15)
        self.assertEqual(r['최종 판정'], '평평')

    def test_ratio_just_over_115_is_proposition(self):
        self.assertEqual(verdict(*pair(345.3)), '명제 성립')

    def test_ratio_equal_to_a_is_proposition(self):
        r = sj.judge(*pair(480.0))
        self.assertEqual(r['A']['값'], 1.6)
        self.assertEqual(r['T1']['비'], 1.6)
        self.assertEqual(r['최종 판정'], '명제 성립')

    def test_ratio_over_a_refutes(self):
        r = sj.judge(*pair(480.3))
        self.assertEqual(r['최종 판정'], '반증')
        self.assertEqual(sj.EXIT['반증'], 1)

    def test_zero_waits_over_115_refutes(self):
        self.assertEqual(verdict(*pair(390.0, wait=0)), '반증')

    def test_flat_with_zero_waits_refutes(self):
        r = sj.judge(*pair(330.0, wait=0))
        self.assertTrue(r['T1']['평평(1.15 이하)'])
        self.assertEqual(r['최종 판정'], '반증')
        self.assertEqual(sj.EXIT[r['최종 판정']], 1)

    def test_flat_over_a_refutes(self):
        b0, b4 = pair(330.0)
        b4['  커밋(ms)'] = 3.0
        r = sj.judge(b0, b4)
        self.assertLess(r['A']['값'], 1.1)
        self.assertTrue(r['T1']['평평(1.15 이하)'])
        self.assertEqual(r['최종 판정'], '반증')

    def test_exit_codes(self):
        self.assertEqual(sorted(set(sj.EXIT.values())), [0, 1, 2, 4])

    def test_a_matches_adr_reference(self):
        b0, b4 = pair()
        b0['락 보유 1회(ms)'], b0['락 보유 점유'] = 2.420, 0.906
        b4['  저널삽입 1회(ms)'], b4['  커밋(ms)'] = 0.494, 1.154
        self.assertAlmostEqual(sj.judge(b0, b4)['A']['값'], 1.62, places=2)


class AxisTest(unittest.TestCase):
    def test_b0_hold_occupancy_boundary(self):
        b0, b4 = pair()
        b0['락 보유 점유'] = 0.9
        self.assertEqual(verdict(b0, b4), '평평')
        b0['락 보유 점유'] = 0.8999
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_b4_hold_sum_must_exceed_one(self):
        b0, b4 = pair()
        b4['락 보유 점유(마켓별)'] = {'1': 0.5, '2': 0.5}
        self.assertEqual(verdict(b0, b4), 'NOT RUN')
        b4['락 보유 점유(마켓별)'] = {'1': 0.5, '2': 0.5001}
        self.assertEqual(verdict(b0, b4), '평평')

    def test_untagged_b4_series_is_not_market_sum(self):
        b0, b4 = pair()
        b4['락 보유 점유(마켓별)'] = {'': 1.9}
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_failed_counter_samples_do_not_count(self):
        b0, b4 = pair()
        b4['카운터 행 표본']['표본'] = [counter_sample(1)] * 5 + [counter_sample(0, ok=False)] * 3
        self.assertEqual(verdict(b0, b4), '평평')
        b4['카운터 행 표본']['표본'] = [counter_sample(1)] * 4 + [counter_sample(0, ok=False)] * 4
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_samples_after_k6_do_not_count(self):
        b0, b4 = pair()
        b4['카운터 행 표본']['표본'] = [counter_sample(1)] * 4 + [counter_sample(1, alive=False)] * 4
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_all_failed_samples_are_not_zero_waits(self):
        b0, b4 = pair(390.0)
        b4['카운터 행 표본']['표본'] = [counter_sample(0, ok=False)] * 8
        r = sj.judge(b0, b4)
        self.assertIsNone(r['카운터 행 대기(B4)']['요약']['대기 합'])
        self.assertEqual(r['최종 판정'], 'NOT RUN')

    def test_counter_interval_must_be_15(self):
        b0, b4 = pair()
        b4['카운터 행 표본']['간격(초)'] = 10
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_positive_control_failure_blocks(self):
        b0, b4 = pair(390.0, wait=0)
        b4['카운터 행 양성 대조']['표본'] = [counter_sample(0)]
        r = sj.judge(b0, b4)
        self.assertEqual(r['카운터 행 대기(B4)']['분류기 양성 대조'], 'FAIL')
        self.assertEqual(r['최종 판정'], 'NOT RUN')

    def test_one_market_without_accepts(self):
        b0, b4 = pair()
        b0['접수(DB, 마켓별)']['마켓'][3]['k6_new'] = 0
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_eleven_markets(self):
        b0, b4 = pair()
        b4['접수(DB, 마켓별)']['k6 마켓'] = MARKETS[:11]
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_k6_rc(self):
        for rc in (1, None):
            b0, b4 = pair()
            b4['k6_rc'] = rc
            self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_fsync_missing(self):
        b0, b4 = pair()
        b0['fsync 건당(ms)'] = None
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_append_pool_pending(self):
        b0, b4 = pair()
        b4['append 풀 대기 최대(1m)'] = 1.0
        self.assertEqual(verdict(b0, b4), 'NOT RUN')
        b4['append 풀 대기 최대(1m)'] = None
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_empty_inputs(self):
        r = sj.judge({}, {})
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertEqual(sj.EXIT[r['최종 판정']], 2)

    def test_missing_a_input(self):
        b0, b4 = pair()
        b4['  커밋(ms)'] = None
        self.assertEqual(verdict(b0, b4), 'NOT RUN')


class PreconditionTest(unittest.TestCase):
    def test_rate_must_be_600(self):
        b0, b4 = pair()
        b4['rate'] = 240
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_same_day(self):
        b0, b4 = pair()
        b4['메타']['시작'] = '2026-09-16T00:10:00+09:00'
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_same_stack(self):
        b0, b4 = pair()
        b4['메타']['ENV'] = {'SCALE_POOL': '64'}
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_b0_must_be_global_lock(self):
        b0, b4 = pair()
        b0['락 보유 점유(마켓별)'] = {'1': 0.95}
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_images_must_differ(self):
        b0, b4 = pair()
        b4['메타']['컨테이너 이미지'] = b0['메타']['컨테이너 이미지']
        self.assertEqual(verdict(b0, b4), 'NOT RUN')


class ConnectionTest(unittest.TestCase):
    def test_peak_at_limit_is_exhaustion(self):
        b0, b4 = pair()
        b4['연결']['표본'] = [conn_sample(60), conn_sample(197)]
        r = sj.judge(b0, b4)
        self.assertEqual(r['최종 판정'], '증거 불완전(연결 고갈)')
        self.assertEqual(sj.EXIT[r['최종 판정']], 4)

    def test_peak_below_limit(self):
        b0, b4 = pair()
        b4['연결']['표본'] = [conn_sample(196)]
        self.assertEqual(verdict(b0, b4), '평평')

    def test_rejection_log_is_exhaustion(self):
        b0, b4 = pair()
        b0['연결']['로그'] = {'ok': True, 'error': None, '줄 수': 1, '예시': ['FATAL:  sorry, too many clients already']}
        self.assertEqual(verdict(b0, b4), '증거 불완전(연결 고갈)')

    def test_unreadable_log_is_not_run(self):
        b0, b4 = pair()
        b4['연결']['로그'] = {'ok': False, 'error': 'x', '줄 수': None, '예시': None}
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_failed_connection_samples_are_not_run(self):
        b0, b4 = pair()
        b4['연결']['표본'] = [conn_sample(0, ok=False)]
        self.assertEqual(verdict(b0, b4), 'NOT RUN')

    def test_exhaustion_is_reported_before_axes(self):
        b0, b4 = pair()
        b4['k6_rc'] = 1
        b4['연결']['표본'] = [conn_sample(199)]
        self.assertEqual(verdict(b0, b4), '증거 불완전(연결 고갈)')


class T2Test(unittest.TestCase):
    def t2(self, conc, accept=300.0, reject=300.0, commit=0.75):
        c = cell('B4')
        c['커밋 동시성'], c['접수/s'], c['수용거절/s'], c['  커밋(ms)'] = conc, accept, reject, commit
        return sj.t2(c)

    def test_na_when_cap_cannot_reach_bound(self):
        r = self.t2(0.5)
        self.assertEqual(r['산술 상한'], 0.45)
        self.assertEqual(r['상태'], 'N/A')

    def test_cap_exactly_bound_is_na(self):
        self.assertEqual(self.t2(0.5, accept=330.0, reject=1270.0)['상태'], 'N/A')

    def test_pass_when_cap_exceeds_bound(self):
        self.assertEqual(self.t2(1.0, reject=1700.0)['상태'], 'PASS')

    def test_exactly_bound_is_not_over(self):
        self.assertEqual(self.t2(1.2, reject=1700.0)['상태'], 'PASS')

    def test_over_bound(self):
        self.assertEqual(self.t2(1.21)['상태'], '초과')

    def test_missing_value(self):
        self.assertEqual(self.t2(None)['상태'], 'NOT RUN')

    def test_over_bound_does_not_change_verdict(self):
        b0, b4 = pair(330.0)
        b4['커밋 동시성'] = 1.5
        r = sj.judge(b0, b4)
        self.assertEqual(r['T2']['B4']['상태'], '초과')
        self.assertEqual(r['최종 판정'], '평평')


if __name__ == '__main__':
    unittest.main()
