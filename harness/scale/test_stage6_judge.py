"""stage6_judge 분기 시험. 합성 입력으로 칸 식별, 양성 축, B4 기준선, 판정, 분기 기록이 실제로 갈리는지 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import unittest

import stage6_judge as sj

MARKETS = ['KRW-BTC', 'KRW-ETH', 'KRW-SOL', 'KRW-XRP', 'KRW-DOGE', 'KRW-ADA',
           'KRW-TRX', 'KRW-LINK', 'KRW-DOT', 'KRW-MATIC', 'KRW-AVAX', 'KRW-ATOM']


def split_sample(glob=0, market=0, ok=True, alive=True):
    if not ok:
        return {'ok': False, 'error': 'psql 종료코드 1', 'k6_alive': alive, 'prologue_active': None,
                'global_row_wait': None, 'market_row_wait': None, 'waiters': None}
    waiters = ([{'pid': 200 + i, 'blockers': [100], 'global_row': True, 'market_row': False} for i in range(glob)]
               + [{'pid': 300 + i, 'blockers': [100], 'global_row': False, 'market_row': True}
                  for i in range(market)])
    return {'ok': True, 'error': None, 'k6_alive': alive, 'prologue_active': glob + market + 1,
            'global_row_wait': glob, 'market_row_wait': market, 'waiters': waiters}


def control(sample, holder_rc=0):
    return {'보유 세션 종료코드': holder_rc, '대기 세션 종료코드': 0, '표본': [sample]}


def conn_sample(n):
    return {'ok': True, 'error': None, 'max_connections': 200, 'superuser_reserved_connections': 3,
            'reserved_connections': 0, 'client_backends': n, 'groups': [], 'ts': 't', 'hikari': None}


def cell(latched):
    return {
        'rate': 600,
        '접수/s': 300.0, '수용거절/s': 300.0, '  커밋(ms)': 1.0, '커밋 동시성': 0.5,
        'k6_rc': 0, 'fsync fdatasync/s': 2000.0, 'fsync 건당(ms)': 0.5,
        'append 풀 대기': 0.0, 'append 풀 대기 최대(1m)': 0.0,
        '접수(DB, 마켓별)': {'k6 마켓': list(MARKETS), 'error': None,
                         '마켓': [{'id': i + 1, 'symbol': m, 'k6_new': 100, 'all_commands': 150}
                                for i, m in enumerate(MARKETS)]},
        '대역 행(DB, 마켓별)': {'창': {'from': 'a', 'to': 'b'}, 'band_span': 10 ** 12, 'error': None,
                           '마켓': [{'id': i + 1, 'symbol': m, 'band_rows': 150 if latched else 0,
                                   'legacy_rows': 0 if latched else 150} for i, m in enumerate(MARKETS)]},
        'C28': {'ok': True, 'error': None, 'band_span': 10 ** 12, '위반 행': 0, '마켓': []},
        '래치': {'트래픽 전 래치': latched, '호출': None,
               '상태': {'ok': True, 'error': None,
                      '래치 행': [{'name': 'command_seq', 'latched_at': 't'}] if latched else []}},
        '카운터 행 표본(전역·마켓)': {'간격(초)': 15, '표본': [split_sample(market=1) for _ in range(8)]},
        '카운터 행 양성 대조(전역)': control(split_sample(glob=1)),
        '카운터 행 양성 대조(마켓)': control(split_sample(market=1)),
        '연결': {'표본': [conn_sample(60)], '로그': {'ok': True, 'error': None, '줄 수': 0, '예시': []}},
        '메타': {'시작': '2026-09-15T10:00:00+09:00', 'ENV': {'SCALE_POOL': '32'},
               'compose sha256': {'docker-compose.chaos.yml': 'c1', 'docker-compose.scale.yml': 's1'},
               'k6': {'markets': list(MARKETS), 'duration': 130, 'bots_ops': 20, 'keys': 4},
               '컨테이너 이미지': {c: {'image_id': 'sha256:stage6'} for c in sj.CONTAINERS}},
    }


def pair(b6_accept=330.0, b6_global=0, b4_global=2):
    """B4 는 접수 300/s 에 전역 카운터 행 대기가 표본마다 b4_global, B6 는 마켓 대기 1 에 전역 대기 b6_global 이다."""
    b4, b6 = cell(False), cell(True)
    b4['카운터 행 표본(전역·마켓)']['표본'] = [split_sample(glob=b4_global) for _ in range(8)]
    b6['카운터 행 표본(전역·마켓)']['표본'] = [split_sample(glob=b6_global, market=1) for _ in range(8)]
    b6['접수/s'] = b6_accept
    return b4, b6


def verdict(b4, b6):
    return sj.judge(b4, b6)['최종 판정']


class DecisionTest(unittest.TestCase):
    def test_flat(self):
        r = sj.judge(*pair(330.0))
        self.assertEqual(r['최종 판정'], '평평')
        self.assertEqual(sj.EXIT[r['최종 판정']], 0)

    def test_ratio_exactly_115_is_flat(self):
        r = sj.judge(*pair(345.0))
        self.assertEqual(r['접수 비']['비'], 1.15)
        self.assertEqual(r['최종 판정'], '평평')

    def test_ratio_over_115_is_split_effect(self):
        r = sj.judge(*pair(345.3))
        self.assertEqual(r['최종 판정'], '채번 분할 효과')
        self.assertEqual(sj.EXIT[r['최종 판정']], 0)

    def test_one_b6_global_wait_invalidates_latch(self):
        b4, b6 = pair(600.0)
        b6['카운터 행 표본(전역·마켓)']['표본'][3] = split_sample(glob=1, market=1)
        r = sj.judge(b4, b6)
        self.assertEqual(r['최종 판정'], '래치 무효')
        self.assertEqual(sj.EXIT[r['최종 판정']], 1)

    def test_latch_invalid_comes_before_flat(self):
        self.assertEqual(verdict(*pair(300.0, b6_global=1)), '래치 무효')

    def test_b6_market_waits_are_not_global(self):
        b4, b6 = pair(330.0)
        b6['카운터 행 표본(전역·마켓)']['표본'] = [split_sample(market=5) for _ in range(8)]
        self.assertEqual(verdict(b4, b6), '평평')

    def test_b6_global_wait_after_k6_does_not_count(self):
        b4, b6 = pair(330.0)
        b6['카운터 행 표본(전역·마켓)']['표본'].append(split_sample(glob=3, alive=False))
        self.assertEqual(verdict(b4, b6), '평평')

    def test_b4_without_global_wait_has_no_baseline(self):
        r = sj.judge(*pair(400.0, b4_global=0))
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertTrue(r['사유'][0].startswith('재직렬화 기준선 없음'))
        self.assertEqual(sj.EXIT[r['최종 판정']], 2)

    def test_baseline_comes_before_latch_invalid(self):
        self.assertEqual(verdict(*pair(400.0, b6_global=1, b4_global=0)), 'NOT RUN')

    def test_missing_accept_rate(self):
        b4, b6 = pair()
        b4['접수/s'] = None
        self.assertEqual(verdict(b4, b6), 'NOT RUN')

    def test_exit_codes(self):
        self.assertEqual(sorted(set(sj.EXIT.values())), [0, 1, 2, 4])


class IdentificationTest(unittest.TestCase):
    def assertUnidentified(self, b4, b6, item):
        r = sj.judge(b4, b6)
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertIn('칸 식별 미충족: ' + item, r['사유'])

    def test_rate(self):
        b4, b6 = pair()
        b6['rate'] = 240
        self.assertUnidentified(b4, b6, '요청 부하 600/s')

    def test_same_day(self):
        b4, b6 = pair()
        b6['메타']['시작'] = '2026-09-16T00:10:00+09:00'
        self.assertUnidentified(b4, b6, '같은 날')

    def test_same_stack(self):
        b4, b6 = pair()
        b6['메타']['ENV'] = {'SCALE_POOL': '64'}
        self.assertUnidentified(b4, b6, '같은 스택과 같은 부하 설정')

    def test_same_image(self):
        item = '같은 이미지(두 칸 세 컨테이너의 서빙 이미지 id 가 하나)'
        b4, b6 = pair()
        b6['메타']['컨테이너 이미지']['chaos-api-2'] = {'image_id': 'sha256:other'}
        self.assertUnidentified(b4, b6, item)
        b4, b6 = pair()
        b4['메타']['컨테이너 이미지']['chaos-bots'] = None
        self.assertUnidentified(b4, b6, item)

    def test_b4_latch_row(self):
        b4, b6 = pair()
        b4['래치']['상태']['래치 행'] = [{'name': 'command_seq'}]
        self.assertUnidentified(b4, b6, 'B4 래치 행 없음')

    def test_b4_unreadable_latch_state_is_not_empty(self):
        b4, b6 = pair()
        b4['래치']['상태'] = {'ok': False, 'error': 'relation "journal_cutover" does not exist', '래치 행': None}
        self.assertUnidentified(b4, b6, 'B4 래치 행 없음')

    def test_b4_band_rows(self):
        b4, b6 = pair()
        b4['대역 행(DB, 마켓별)']['마켓'][5]['band_rows'] = 1
        self.assertUnidentified(b4, b6, 'B4 대역 행 0')

    def test_b4_band_query_error(self):
        b4, b6 = pair()
        b4['대역 행(DB, 마켓별)'] = {'error': 'psql 종료코드 1', '마켓': None}
        self.assertUnidentified(b4, b6, 'B4 대역 행 0')

    def test_b4_empty_band_rows_are_not_zero(self):
        b4, b6 = pair()
        b4['대역 행(DB, 마켓별)']['마켓'] = []
        self.assertUnidentified(b4, b6, 'B4 대역 행 0')

    def test_b6_latch_row(self):
        b4, b6 = pair()
        b6['래치']['상태']['래치 행'] = []
        self.assertUnidentified(b4, b6, 'B6 래치 행 있음')

    def test_b6_one_market_without_band_rows(self):
        b4, b6 = pair()
        b6['대역 행(DB, 마켓별)']['마켓'][11]['band_rows'] = 0
        self.assertUnidentified(b4, b6, 'B6 12마켓 모두 대역 행 > 0')

    def test_b6_market_missing_from_band_rows(self):
        b4, b6 = pair()
        del b6['대역 행(DB, 마켓별)']['마켓'][0]
        self.assertUnidentified(b4, b6, 'B6 12마켓 모두 대역 행 > 0')

    def test_b6_c28(self):
        b4, b6 = pair()
        b6['C28']['위반 행'] = 1
        self.assertUnidentified(b4, b6, 'B6 C28 0')
        b4, b6 = pair()
        b6['C28'] = {'ok': False, 'error': 'journal_band_spec 에 command_seq 행이 없다', '위반 행': None}
        self.assertUnidentified(b4, b6, 'B6 C28 0')


class AxisTest(unittest.TestCase):
    def assertAxisMissing(self, b4, b6, label, name):
        r = sj.judge(b4, b6)
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertIn('양성 축 미충족: %s %s' % (label, name), r['사유'])

    def test_one_market_without_accepts(self):
        b4, b6 = pair()
        b6['접수(DB, 마켓별)']['마켓'][3]['k6_new'] = 0
        self.assertAxisMissing(b4, b6, 'B6', '12마켓 각 접수 0 초과')

    def test_k6_rc(self):
        for rc in (1, None):
            b4, b6 = pair()
            b4['k6_rc'] = rc
            self.assertAxisMissing(b4, b6, 'B4', 'k6_rc 0')

    def test_append_pool_pending(self):
        name = 'append 풀 대기(순간과 1분 최대) 0'
        b4, b6 = pair()
        b6['append 풀 대기'] = 1.0
        self.assertAxisMissing(b4, b6, 'B6', name)
        b4, b6 = pair()
        b6['append 풀 대기 최대(1m)'] = None
        self.assertAxisMissing(b4, b6, 'B6', name)

    def test_fsync_missing(self):
        b4, b6 = pair()
        b4['fsync fdatasync/s'] = None
        self.assertAxisMissing(b4, b6, 'B4', 'fsync 키 존재')

    def test_sample_success_boundary(self):
        name = '카운터 행 표본 15초 간격 성공 5회 이상'
        b4, b6 = pair()
        b6['카운터 행 표본(전역·마켓)']['표본'] = [split_sample(market=1)] * 5 + [split_sample(ok=False)] * 3
        self.assertEqual(verdict(b4, b6), '평평')
        b6['카운터 행 표본(전역·마켓)']['표본'] = [split_sample(market=1)] * 4 + [split_sample(ok=False)] * 4
        self.assertAxisMissing(b4, b6, 'B6', name)

    def test_sample_interval(self):
        b4, b6 = pair()
        b4['카운터 행 표본(전역·마켓)']['간격(초)'] = 10
        self.assertAxisMissing(b4, b6, 'B4', '카운터 행 표본 15초 간격 성공 5회 이상')

    def test_all_failed_samples_are_not_zero_waits(self):
        b4, b6 = pair(600.0)
        b6['카운터 행 표본(전역·마켓)']['표본'] = [split_sample(ok=False)] * 8
        r = sj.judge(b4, b6)
        self.assertIsNone(r['카운터 행 대기']['B6']['전역']['대기 합'])
        self.assertEqual(r['최종 판정'], 'NOT RUN')

    def test_global_control_without_wait(self):
        b4, b6 = pair()
        b6['카운터 행 양성 대조(전역)'] = control(split_sample())
        self.assertAxisMissing(b4, b6, 'B6', '양성 대조 PASS(전역)')

    def test_market_control_counts_only_market_waiter(self):
        b4, b6 = pair()
        b4['카운터 행 양성 대조(마켓)'] = control(split_sample(glob=1))
        self.assertAxisMissing(b4, b6, 'B4', '양성 대조 PASS(마켓)')

    def test_market_control_not_run(self):
        b4, b6 = pair()
        b6['카운터 행 양성 대조(마켓)'] = control(split_sample(), holder_rc=1)
        self.assertAxisMissing(b4, b6, 'B6', '양성 대조 PASS(마켓)')
        del b6['카운터 행 양성 대조(마켓)']
        self.assertAxisMissing(b4, b6, 'B6', '양성 대조 PASS(마켓)')

    def test_empty_inputs(self):
        r = sj.judge({}, {})
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertEqual(sj.EXIT[r['최종 판정']], 2)


class ConnectionTest(unittest.TestCase):
    def test_exhaustion(self):
        b4, b6 = pair()
        b6['연결']['표본'] = [conn_sample(60), conn_sample(197)]
        r = sj.judge(b4, b6)
        self.assertEqual(r['최종 판정'], '증거 불완전(연결 고갈)')
        self.assertEqual(sj.EXIT[r['최종 판정']], 4)

    def test_exhaustion_is_reported_before_axes(self):
        b4, b6 = pair()
        b4['k6_rc'] = 1
        b4['연결']['로그'] = {'ok': True, 'error': None, '줄 수': 1, '예시': ['FATAL:  sorry, too many clients already']}
        self.assertEqual(verdict(b4, b6), '증거 불완전(연결 고갈)')

    def test_identification_is_reported_before_exhaustion(self):
        b4, b6 = pair()
        b6['rate'] = 240
        b6['연결']['표본'] = [conn_sample(199)]
        self.assertEqual(verdict(b4, b6), 'NOT RUN')

    def test_unreadable_log_is_not_run(self):
        b4, b6 = pair()
        b6['연결']['로그'] = {'ok': False, 'error': 'x', '줄 수': None, '예시': None}
        self.assertEqual(verdict(b4, b6), 'NOT RUN')


class BranchTest(unittest.TestCase):
    def branch(self, conc, accept=300.0, reject=300.0, commit=1.0):
        c = cell(True)
        c['커밋 동시성'], c['접수/s'], c['수용거절/s'], c['  커밋(ms)'] = conc, accept, reject, commit
        return sj.branch(c)['분기']

    def test_over_bound_is_branch_b(self):
        self.assertEqual(self.branch(1.21), '분기 B(동시 커밋 확장)')

    def test_at_or_below_bound_with_reach_is_branch_a(self):
        self.assertEqual(self.branch(1.0, reject=1000.0), '분기 A(저장소 직렬)')
        self.assertEqual(self.branch(1.2, reject=1000.0), '분기 A(저장소 직렬)')

    def test_bound_unreachable_is_na(self):
        self.assertEqual(self.branch(0.5), 'N/A')

    def test_missing_value(self):
        self.assertEqual(self.branch(None), 'NOT RUN')

    def test_branch_does_not_change_verdict(self):
        b4, b6 = pair(330.0)
        b6['커밋 동시성'] = 1.5
        r = sj.judge(b4, b6)
        self.assertEqual(r['분기(B6)']['분기'], '분기 B(동시 커밋 확장)')
        self.assertEqual(r['최종 판정'], '평평')


if __name__ == '__main__':
    unittest.main()
