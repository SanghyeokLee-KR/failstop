"""stage8_judge 분기 시험. 합성 칸 여덟으로 칸 식별, 양성 축, 전제, 드리프트, 묶음 무효, 범주가 실제로 갈리는지 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import commit_delay as cd
import pg_samplers as pgs
import stage8_judge as sj

MARKETS = ['KRW-BTC', 'KRW-ETH', 'KRW-SOL', 'KRW-XRP', 'KRW-DOGE', 'KRW-ADA',
           'KRW-TRX', 'KRW-LINK', 'KRW-DOT', 'KRW-MATIC', 'KRW-AVAX', 'KRW-ATOM']
AUTO = '/var/lib/postgresql/data/postgresql.auto.conf'
APPLIED = '설정 적용 확인(SHOW 값, postgresql.auto.conf 출처, 재기동 대기 없음)'
WAL_AXIS = 'WAL 창 50초 이상, wal_sync 증가와 쓰기 트랜잭션 증가 0 초과'


def settings(delay, siblings, sourcefile=AUTO):
    return {'ok': True, 'error': None, 'commit_delay': delay, 'commit_siblings': siblings, 'ts': 't', '시도': 1,
            '출처': [{'name': name, 'setting': str(value), 'source': 'configuration file', 'sourcefile': sourcefile,
                     'pending_restart': False}
                    for name, value in (('commit_delay', delay), ('commit_siblings', siblings))]}


def wal_window(batch, fsync_per_s=100, secs=60.0):
    """batch 는 쓰기 트랜잭션 수 / wal_sync 다. 표본 둘을 pg_samplers 형태로 만들어 창을 그대로 계산한다."""
    syncs = int(fsync_per_s * secs)

    def record(epoch, sync, xid):
        return pgs.wal_counter_record({'epoch': epoch, 'wal_sync': sync, 'wal_bytes': sync * 8192,
                                       'wal_sync_time_ms': 0.0, 'stats_reset': 'r', 'track_wal_io_timing': False,
                                       'xact_commit': xid * 3, 'xid_next': xid}, None)

    return pgs.wal_window(record(1000.0, 0, 0), record(1000.0 + secs, syncs, round(batch * syncs)))


def conn_sample(n):
    return {'ok': True, 'error': None, 'max_connections': 200, 'superuser_reserved_connections': 3,
            'reserved_connections': 0, 'client_backends': n, 'groups': [], 'ts': 't', 'hikari': None}


def cell(label, delay, siblings, accept, batch):
    return {
        'label': label, 'rate': 600,
        '접수/s': accept, '수용거절/s': 400.0, 'p95(ms)': 500.0, '  커밋(ms)': 5.0, '커밋 동시성': 1.3,
        '락 보유 1회(ms)': 10.0, 'DB CPU%': 250.0,
        'k6_rc': 0, 'fsync fdatasync/s': 320.0, 'fsync 건당(ms)': 3.0,
        'append 풀 대기': 0.0, 'append 풀 대기 최대(1m)': 0.0,
        '접수(DB, 마켓별)': {'k6 마켓': list(MARKETS), 'error': None,
                         '마켓': [{'id': i + 1, 'symbol': m, 'k6_new': 100, 'all_commands': 150}
                                for i, m in enumerate(MARKETS)]},
        '대역 행(DB, 마켓별)': {'창': {'from': 'a', 'to': 'b'}, 'band_span': 10 ** 12, 'error': None,
                           '마켓': [{'id': i + 1, 'symbol': m, 'band_rows': 150, 'legacy_rows': 0}
                                  for i, m in enumerate(MARKETS)]},
        'C28': {'ok': True, 'error': None, 'band_span': 10 ** 12, '위반 행': 0, '마켓': []},
        '래치': {'트래픽 전 래치': True, '호출': {'ok': True, 'error': None},
               '상태': {'ok': True, 'error': None, '래치 행': [{'name': 'command_seq', 'latched_at': 't'}]}},
        '커밋 설정': {'적용': {'ok': True, 'error': None, '요청': {'commit_delay': delay, 'commit_siblings': siblings},
                           '명령': [], '확인': settings(delay, siblings)},
                    '유지': settings(delay, siblings), '복구': {'ok': True, 'error': None}},
        'WAL 창': wal_window(batch),
        '연결': {'표본': [conn_sample(60)], '로그': {'ok': True, 'error': None, '줄 수': 0, '예시': []}},
        '메타': {'시작': '2026-09-15T10:00:00+09:00', 'ENV': {'SCALE_POOL': '32'},
               'compose sha256': {'docker-compose.chaos.yml': 'c1', 'docker-compose.scale.yml': 's1'},
               'k6': {'markets': list(MARKETS), 'duration': 130, 'bots_ops': 20, 'keys': 4},
               '컨테이너 이미지': {c: {'image_id': 'sha256:stage8'} for c in ('chaos-api-1', 'chaos-api-2', 'chaos-bots')}},
    }


def grid(overrides=None):
    """칸별 (접수/s, 묶음 비) 덮어쓰기. 기본은 모든 칸 접수 200, 기준 칸 묶음 비 2.0, 지연 칸 2.6 이다."""
    overrides = overrides or {}
    return {label: cell(label, delay, siblings, *overrides.get(label, (200.0, 2.0 if label in sj.BASELINES else 2.6)))
            for label, delay, siblings in sj.GRID}


def verdict(cells):
    return sj.judge(cells)['최종 판정']


def row(result, label):
    return next(r for r in result['칸별'] if r['칸'] == label)


class DecisionTest(unittest.TestCase):
    def test_batching_without_accept_change_is_no_gain(self):
        r = sj.judge(grid())
        self.assertEqual(r['최종 판정'], sj.NO_GAIN)
        self.assertEqual(sj.EXIT[r['최종 판정']], 0)
        self.assertEqual({x['범주'] for x in r['칸별']}, {'평평'})

    def test_gain_needs_accept_and_batch_ratios(self):
        r = sj.judge(grid({'d1000-s1': (240.0, 2.6)}))
        self.assertEqual(r['최종 판정'], sj.GAIN_FOUND)
        self.assertEqual(row(r, 'd1000-s1')['범주'], '이득')
        self.assertEqual(sj.EXIT[r['최종 판정']], 0)

    def test_accept_ratio_exactly_110_is_flat(self):
        r = sj.judge(grid({'d1000-s1': (220.0, 2.6)}))
        self.assertEqual(row(r, 'd1000-s1')['접수 비'], 1.1)
        self.assertEqual(row(r, 'd1000-s1')['범주'], '평평')
        self.assertEqual(r['최종 판정'], sj.NO_GAIN)

    def test_increase_with_batch_ratio_exactly_110_is_not_gain(self):
        r = sj.judge(grid({'d250-s5': (260.0, 2.2)}))
        self.assertEqual(row(r, 'd250-s5')['묶음 비 비'], 1.1)
        self.assertEqual(row(r, 'd250-s5')['범주'], '묶음 없는 증가')
        self.assertEqual(r['최종 판정'], sj.NO_GAIN)

    def test_loss_and_its_boundary(self):
        r = sj.judge(grid({'d3000-s1': (170.0, 3.5), 'd3000-s5': (180.0, 3.5)}))
        self.assertEqual(row(r, 'd3000-s1')['범주'], '손해')
        self.assertEqual(row(r, 'd3000-s5')['범주'], '평평')
        self.assertEqual(r['최종 판정'], sj.NO_GAIN)

    def test_no_batching_anywhere_is_batch_invalid_even_with_accept_gain(self):
        flat = {label: (260.0, 2.2) for label, _, _ in sj.GRID if label not in sj.BASELINES}
        r = sj.judge(grid(flat))
        self.assertEqual(r['최종 판정'], sj.BATCH_INVALID)
        self.assertEqual(sj.EXIT[r['최종 판정']], 1)

    def test_one_batching_cell_lifts_batch_invalid(self):
        flat = {label: (200.0, 2.0) for label, _, _ in sj.GRID if label not in sj.BASELINES}
        flat['d3000-s5'] = (200.0, 2.4)
        self.assertEqual(verdict(grid(flat)), sj.NO_GAIN)

    def test_reference_is_the_baseline_mean(self):
        r = sj.judge(grid({'base-1': (195.0, 1.9), 'base-2': (205.0, 2.1), 'd1000-s5': (221.0, 2.6)}))
        self.assertAlmostEqual(r['기준값']['접수/s'], 200.0)
        self.assertAlmostEqual(r['기준값']['묶음 비'], 2.0)
        self.assertEqual(row(r, 'd1000-s5')['범주'], '이득')

    def test_exit_codes(self):
        self.assertEqual(sorted(set(sj.EXIT.values())), [0, 1, 2, 4])


class DriftTest(unittest.TestCase):
    def test_drift_exactly_005_passes(self):
        r = sj.judge(grid({'base-1': (195.0, 2.0), 'base-2': (205.0, 2.0)}))
        self.assertEqual(r['드리프트']['접수/s']['차 비'], 0.05)
        self.assertEqual(r['최종 판정'], sj.NO_GAIN)

    def test_drift_over_005_is_not_run(self):
        r = sj.judge(grid({'base-1': (194.0, 2.0), 'base-2': (206.0, 2.0)}))
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertTrue(r['사유'][0].startswith('드리프트 초과'))
        self.assertEqual(sj.EXIT[r['최종 판정']], 2)

    def test_batch_drift_is_recorded_only(self):
        r = sj.judge(grid({'base-1': (200.0, 1.5), 'base-2': (200.0, 2.5)}))
        self.assertEqual(r['드리프트']['기록만']['묶음 비']['차 비'], 0.5)
        self.assertEqual(r['최종 판정'], sj.NO_GAIN)


class PremiseTest(unittest.TestCase):
    def test_baseline_concurrency_at_bound_is_not_run(self):
        cells = grid()
        cells['base-2']['커밋 동시성'] = 1.2
        r = sj.judge(cells)
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertTrue(r['사유'][0].startswith('전제 미성립: base-2'))

    def test_unreachable_bound_is_not_run(self):
        cells = grid()
        cells['base-1'].update({'커밋 동시성': 0.5, '수용거절/s': 0.0, '  커밋(ms)': 1.0})
        r = sj.judge(cells)
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertEqual(r['전제(기준 칸 분기 B)']['base-1']['상태'], 'N/A')

    def test_only_baselines_carry_the_premise(self):
        cells = grid()
        cells['d3000-s1']['커밋 동시성'] = 0.4
        self.assertEqual(verdict(cells), sj.NO_GAIN)


class IdentificationTest(unittest.TestCase):
    def assertUnidentified(self, cells, item, label):
        r = sj.judge(cells)
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        rows = [x for x in r['칸 식별'] if x['항목'] == item]
        self.assertEqual(len(rows), 1)
        self.assertIn(label, rows[0]['미충족 칸'])

    def test_missing_cell(self):
        cells = grid()
        cells['d250-s1'] = None
        self.assertUnidentified(cells, '격자 칸 여덟 존재', 'd250-s1')

    def test_rate_day_stack_and_image(self):
        cells = grid()
        cells['base-2']['rate'] = 240
        self.assertUnidentified(cells, '요청 부하 600/s', 'base-2')
        cells = grid()
        cells['base-2']['메타']['시작'] = '2026-09-16T00:05:00+09:00'
        self.assertUnidentified(cells, '같은 날', 'base-2')
        cells = grid()
        cells['d1000-s5']['메타']['ENV'] = {'SCALE_POOL': '64'}
        self.assertUnidentified(cells, '같은 스택과 같은 부하 설정', 'd1000-s5')
        cells = grid()
        cells['d3000-s5']['메타']['컨테이너 이미지']['chaos-bots'] = {'image_id': 'sha256:other'}
        self.assertUnidentified(cells, '같은 이미지(칸마다 세 컨테이너의 서빙 이미지 id 가 하나)', 'd3000-s5')

    def test_file_label_and_request_must_match_grid(self):
        cells = grid()
        cells['d250-s5']['커밋 설정']['적용']['요청'] = {'commit_delay': 250, 'commit_siblings': 1}
        self.assertUnidentified(cells, '칸 이름과 요청 값이 격자와 같음', 'd250-s5')
        cells = grid()
        cells['d250-s5']['label'] = 'd250-s1'
        self.assertUnidentified(cells, '칸 이름과 요청 값이 격자와 같음', 'd250-s5')

    def test_setting_must_be_applied_from_auto_conf(self):
        cells = grid()
        cells['d1000-s1']['커밋 설정']['적용']['확인'] = settings(0, 5)
        self.assertUnidentified(cells, APPLIED, 'd1000-s1')
        cells = grid()
        cells['d1000-s1']['커밋 설정']['적용']['확인'] = settings(1000, 1, '/var/lib/postgresql/data/postgresql.conf')
        self.assertUnidentified(cells, APPLIED, 'd1000-s1')
        cells = grid()
        cells['base-1']['커밋 설정']['적용']['ok'] = False
        self.assertUnidentified(cells, APPLIED, 'base-1')

    def test_setting_must_be_kept_after_k6(self):
        cells = grid()
        cells['d3000-s1']['커밋 설정']['유지'] = settings(0, 5)
        self.assertUnidentified(cells, 'k6 뒤 설정 유지 확인', 'd3000-s1')
        cells = grid()
        del cells['d3000-s1']['커밋 설정']['유지']
        self.assertUnidentified(cells, 'k6 뒤 설정 유지 확인', 'd3000-s1')

    def test_latch_before_traffic(self):
        item = '트래픽 전 래치 호출 성공과 래치 행'
        cells = grid()
        cells['d250-s1']['래치']['트래픽 전 래치'] = False
        self.assertUnidentified(cells, item, 'd250-s1')
        cells = grid()
        cells['d250-s1']['래치']['호출'] = {'ok': False, 'error': '호출 계정 app 가 함수 소유자 exchange 가 아니다'}
        self.assertUnidentified(cells, item, 'd250-s1')
        cells = grid()
        cells['d250-s1']['래치']['상태']['래치 행'] = []
        self.assertUnidentified(cells, item, 'd250-s1')

    def test_band_rows_and_c28(self):
        cells = grid()
        cells['d1000-s5']['대역 행(DB, 마켓별)']['마켓'][4]['band_rows'] = 0
        self.assertUnidentified(cells, 'k6 12마켓 모두 대역 행 > 0', 'd1000-s5')
        cells = grid()
        cells['d1000-s5']['대역 행(DB, 마켓별)'] = {'error': 'psql 종료코드 1', '마켓': None}
        self.assertUnidentified(cells, 'k6 12마켓 모두 대역 행 > 0', 'd1000-s5')
        cells = grid()
        cells['base-2']['C28']['위반 행'] = 1
        self.assertUnidentified(cells, 'C28 0', 'base-2')


class AxisTest(unittest.TestCase):
    def assertAxisMissing(self, cells, label, name):
        r = sj.judge(cells)
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertIn('양성 축 미충족: %s %s' % (label, name), r['사유'])

    def test_failed_wal_samples_are_not_zero(self):
        cells = grid()
        failed = pgs.wal_counter_record(None, 'psql 종료코드 1')
        cells['d1000-s1']['WAL 창'] = pgs.wal_window(failed, failed)
        self.assertAxisMissing(cells, 'd1000-s1', WAL_AXIS)
        cells = grid()
        del cells['base-2']['WAL 창']
        self.assertAxisMissing(cells, 'base-2', WAL_AXIS)

    def test_short_window_or_no_fsync(self):
        cells = grid()
        cells['base-1']['WAL 창'] = wal_window(2.0, secs=40.0)
        self.assertAxisMissing(cells, 'base-1', WAL_AXIS)
        cells = grid()
        cells['d250-s5']['WAL 창'] = wal_window(2.0, fsync_per_s=0)
        self.assertAxisMissing(cells, 'd250-s5', WAL_AXIS)

    def test_run_axes(self):
        cells = grid()
        cells['d3000-s1']['k6_rc'] = 1
        self.assertAxisMissing(cells, 'd3000-s1', 'k6_rc 0')
        cells = grid()
        cells['base-1']['접수/s'] = None
        self.assertAxisMissing(cells, 'base-1', '접수/s 0 초과')
        cells = grid()
        cells['d250-s1']['접수(DB, 마켓별)']['마켓'][7]['k6_new'] = 0
        self.assertAxisMissing(cells, 'd250-s1', '12마켓 각 접수 0 초과')
        cells = grid()
        cells['d1000-s5']['append 풀 대기 최대(1m)'] = 1.0
        self.assertAxisMissing(cells, 'd1000-s5', 'append 풀 대기(순간과 1분 최대) 0')
        cells = grid()
        cells['base-2']['fsync 건당(ms)'] = None
        self.assertAxisMissing(cells, 'base-2', 'fsync 키 존재')

    def test_empty_inputs(self):
        r = sj.judge({})
        self.assertEqual(r['최종 판정'], 'NOT RUN')
        self.assertEqual(sj.EXIT[r['최종 판정']], 2)


class ConnectionTest(unittest.TestCase):
    def test_exhaustion_in_one_cell(self):
        cells = grid()
        cells['d250-s1']['연결']['표본'] = [conn_sample(60), conn_sample(197)]
        r = sj.judge(cells)
        self.assertEqual(r['최종 판정'], sj.EXHAUSTED)
        self.assertEqual(sj.EXIT[r['최종 판정']], 4)

    def test_exhaustion_is_reported_before_axes(self):
        cells = grid()
        cells['base-1']['k6_rc'] = 1
        cells['base-1']['연결']['로그'] = {'ok': True, 'error': None, '줄 수': 1,
                                        '예시': ['FATAL:  sorry, too many clients already']}
        self.assertEqual(verdict(cells), sj.EXHAUSTED)

    def test_unreadable_log_is_not_run(self):
        cells = grid()
        cells['d3000-s5']['연결']['로그'] = {'ok': False, 'error': 'x', '줄 수': None, '예시': None}
        self.assertEqual(verdict(cells), 'NOT RUN')


class GridTest(unittest.TestCase):
    def test_baselines_open_and_close_the_grid(self):
        self.assertEqual((sj.GRID[0], sj.GRID[-1]), (('base-1', 0, 5), ('base-2', 0, 5)))
        self.assertEqual(sj.BASELINES, ('base-1', 'base-2'))

    def test_delay_cells_cover_each_combination_once(self):
        combos = [(d, n) for label, d, n in sj.GRID if label not in sj.BASELINES]
        self.assertEqual(sorted(combos), sorted((d, n) for d in (250, 1000, 3000) for n in (1, 5)))

    def test_each_delay_sits_at_the_baseline_mean_position(self):
        positions = {}
        for i, (_, delay, _) in enumerate(sj.GRID, start=1):
            positions.setdefault(delay, []).append(i)
        self.assertEqual({sum(p) / len(p) for p in positions.values()}, {4.5})

    def test_labels_are_unique_and_values_are_accepted_by_postgres(self):
        labels = [label for label, _, _ in sj.GRID]
        self.assertEqual(len(labels), len(set(labels)))
        for _, d, n in sj.GRID:
            self.assertTrue(cd.valid('commit_delay', d) and cd.valid('commit_siblings', n))


class CliTest(unittest.TestCase):
    def run_main(self, argv):
        buf = io.StringIO()
        with mock.patch('sys.argv', ['stage8_judge.py'] + argv), contextlib.redirect_stdout(buf):
            code = sj.main()
        return code, buf.getvalue()

    def test_print_grid_is_what_the_procedure_reads(self):
        code, out = self.run_main(['--print-grid'])
        self.assertEqual(code, 0)
        self.assertEqual([(g['label'], g['commit_delay'], g['commit_siblings']) for g in json.loads(out)],
                         list(sj.GRID))

    def test_judge_from_directory(self):
        with tempfile.TemporaryDirectory() as d:
            for label, c in grid({'d1000-s1': (240.0, 2.6)}).items():
                Path(d, '%s.json' % label).write_text(json.dumps(c, ensure_ascii=False), encoding='utf-8')
            code, _ = self.run_main(['--dir', d, '--out', str(Path(d, 'verdict.json'))])
            written = json.loads(Path(d, 'verdict.json').read_text(encoding='utf-8'))
        self.assertEqual((code, written['최종 판정']), (0, sj.GAIN_FOUND))

    def test_missing_and_broken_files_are_none(self):
        with tempfile.TemporaryDirectory() as d:
            for label, c in grid().items():
                if label != 'd250-s1':
                    Path(d, '%s.json' % label).write_text(json.dumps(c, ensure_ascii=False), encoding='utf-8')
            Path(d, 'd1000-s5.json').write_text('{broken', encoding='utf-8')
            cells = sj.load_cells(d)
        self.assertIsNone(cells['d250-s1'])
        self.assertIsNone(cells['d1000-s5'])
        self.assertEqual(cells['base-1']['label'], 'base-1')
        self.assertEqual(verdict(cells), 'NOT RUN')


if __name__ == '__main__':
    unittest.main()
