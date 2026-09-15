"""stage7_judge 분기 시험. 합성 채집 결과로 모드 식별, 전제, 승격 식별, 재생 생략, 부팅 식별, 폴백, 재기동 기록, 증거 완결성,
경계 재생 일치, 양성 축, 칸과 최종 판정 집계가 실제로 갈리는지 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path

import stage7_judge as sj

API1, API2 = 'chaos-api-1', 'chaos-api-2'
BAND = 10 ** 12
MARKETS = (1, 2, 3)
RECOVERY = 'k.e.engine.EngineRecovery'
PROMOTION = 'k.e.engine.PromotionCoordinator'
CONFIG = 'k.e.config.EngineConfig'


def line(ts, text, logger=RECOVERY):
    return {'ts': '2026-09-15T01:%s.000000000Z' % ts,
            'text': '2026-09-15T10:00:00.000+09:00  INFO 1 --- [exchange] [engine-promotion] %s : %s' % (logger, text)}


def boot_line(ts, mode='global'):
    axis, latch = ('마켓 대역', '있음') if mode == 'band' else ('전역', '없음')
    return line(ts, '명령 채번 축: %s (journal_cutover command_seq 래치 %s)' % (axis, latch), CONFIG)


def metrics(fallback=0, replay=0):
    return {'port': 28080, 'ok': True, 'error': None, '줄': [
        'exchange_recovery_snapshot_fallback_total{application="exchange"} %s' % float(fallback),
        'exchange_journal_replay_commands_total{application="exchange"} %s' % float(replay),
        'exchange_snapshot_last_persisted_seq{application="exchange",market="1"} 100.0']}


def unreadable():
    return {'port': 28080, 'ok': False, 'error': 'URLError', '줄': None}


def adopted_text(snap):
    return '스냅샷 채택(마켓별): [%s]' % ', '.join('%d@%d' % (mk, snap[mk]) for mk in sorted(snap))


def applied_text(items):
    return '재생 적용(마켓별): {%s}' % ', '.join('%d=%d..%d %d건' % item for item in items)


def fmt_map(d):
    return ', '.join('%d=%d' % kv for kv in sorted(d.items()))


def done_text(cursors, heads, epoch=2):
    return '승격 재생 완료: epoch=%d, 마켓 커서={%s}, head={%s}, 소요=812ms' % (epoch, fmt_map(cursors), fmt_map(heads))


def skipped_text(cursors, heads, epoch=2):
    return '승격 재생 생략(모든 마켓 head 도달), 라이터에 설치: epoch=%d, 마켓 커서={%s}, head={%s}' % (
        epoch, fmt_map(cursors), fmt_map(heads))


def mode_fixture(mode, experiment):
    """모드 식별 증거. 요약, 칸 행의 공통 부분, band_state."""
    summary = {'imageId': 'sha256:stage7', 'latchBeforeTraffic': mode == 'band', 'snapshotEvidence': True}
    row = {'experiment': experiment, '실험 판정': 'PASS', '연결 판정': '고갈 없음', '분리 판정': 'PASS'}
    if mode == 'band':
        row['cutover'] = {'대역 모드 성립': True}
    band_state = {
        '래치': {'ok': True, 'error': None, '래치 행': [{'name': 'command_seq'}] if mode == 'band' else []},
        '대역 행': {'error': None, '마켓': [{'id': i, 'symbol': 'M%d' % i,
                                         'band_rows': 40 if mode == 'band' and i in MARKETS else 0}
                                        for i in range(1, 13)]},
        'C28': {'ok': True, '위반 행': 0}}
    return summary, row, band_state


class Cell:
    """장애 한 칸(기본 E4). chaos-api-1 이 옛 리더이고 chaos-api-2 가 마켓별 스냅샷 경계에서 epoch 2 로 승격했다."""

    def __init__(self, mode='global', tails=(50, 60, 10), experiment='E4'):
        self.mode = mode
        self.experiment = experiment
        self.snap = {mk: (BAND * mk if mode == 'band' else 0) + 100 * mk for mk in MARKETS}
        self.tails = dict(zip(MARKETS, tails))
        # 전역 모드는 마켓 안 seq 가 성겨 종료 커서가 적용 건수보다 멀다.
        gap = 1 if mode == 'band' else 3
        self.end = {mk: self.snap[mk] + gap * self.tails[mk] for mk in MARKETS}
        self.summary, self.row, self.band_state = mode_fixture(mode, experiment)
        self.row['criteria'] = {'2 계약된 반응': {'oldEpoch': 1, 'newEpoch': 2}, '3 복구 시간': {'rtoSeconds': 6.2}}

        self.adopted_line = line('05:01', adopted_text(self.snap))
        self.applied_line = line('05:03', applied_text(
            (mk, self.snap[mk], self.end[mk], self.tails[mk]) for mk in MARKETS))
        self.done_line = line('05:04', done_text(self.end, self.end), PROMOTION)
        self.boot = {API1: [boot_line('00:00', mode), line('00:01', '재생 적용(마켓별): {}')],
                     API2: [boot_line('00:01', mode), line('00:02', '재생 적용(마켓별): {}')]}
        promotion = [self.adopted_line, line('05:02', '재생 1단계(마켓별 물질화 경계 상한): 마켓 3개, 적용 120건, 대조 300건'),
                     self.applied_line, self.done_line]
        snapshot_rows = [{'snapshot_id': i + 1, 'market_id': mk, 'last_applied_seq': self.snap[mk], 'fencing_epoch': 1}
                         for i, mk in enumerate(MARKETS)]
        boot_runs = [{'id': i, 'market_id': 0, 'replayed_from_seq': 0, 'replayed_to_seq': 0, 'matched': True}
                     for i in (1, 2)]
        env = {c: {'ok': True, 'error': None, '값': {}} for c in (API1, API2)}

        self.pre = copy.deepcopy({
            '단계': 'pre',
            '대기': {'충족': True, '경과(초)': 41.0},
            '활성 마켓': {'ok': True, 'error': None,
                        '마켓': [{'market_id': mk, 'rows': 40, 'head': self.snap[mk] + 5} for mk in MARKETS]},
            '스냅샷': {'ok': True, 'error': None, '행': snapshot_rows},
            '복구 실행': {'ok': True, 'error': None, '행': boot_runs},
            '지표': {API1: metrics(), API2: metrics()},
            '복구 로그': {c: {'ok': True, 'error': None, '줄': self.boot[c]} for c in (API1, API2)},
            '스냅샷 설정 환경변수': env,
        })
        self.post = copy.deepcopy({
            '단계': 'post',
            '활성 마켓': self.pre['활성 마켓'],
            '스냅샷': {'ok': True, 'error': None, '행': snapshot_rows + [
                {'snapshot_id': 9, 'market_id': 1, 'last_applied_seq': self.end[1] + 7, 'fencing_epoch': 2}]},
            '복구 실행': {'ok': True, 'error': None, '행': boot_runs + [
                {'id': 3, 'market_id': 0, 'replayed_from_seq': max(self.snap.values()),
                 'replayed_to_seq': max(self.end.values()), 'matched': True}]},
            '지표': {API1: metrics(), API2: metrics(replay=sum(tails))},
            '복구 로그': {API1: {'ok': True, 'error': None, '줄': self.boot[API1]},
                        API2: {'ok': True, 'error': None, '줄': self.boot[API2] + promotion}},
            '스냅샷 설정 환경변수': env,
            '저널 구간 수': {'ok': True, 'error': None, '해석 실패 줄': [],
                          '구간': [{'market_id': mk, 'from': self.snap[mk], 'to': self.end[mk],
                                  'rows': self.tails[mk]} for mk in MARKETS]},
        })

    def lines(self, container=API2):
        return self.post['복구 로그'][container]['줄']

    def replace(self, old, new):
        lines = self.lines()
        lines[lines.index(old)] = new

    def skip(self, cursors=None, heads=None):
        """팔로워가 모든 마켓 head 에 닿아 승격이 재생을 생략한 칸으로 바꾼다."""
        cursors = dict(self.end) if cursors is None else cursors
        heads = dict(self.end) if heads is None else heads
        self.post['복구 로그'][API2]['줄'] = copy.deepcopy(self.boot[API2]) + [
            line('05:04', skipped_text(cursors, heads), PROMOTION)]
        self.post['복구 실행']['행'] = self.post['복구 실행']['행'][:2]
        return self

    def verdict(self):
        return sj.snapshot_verdict(self.mode, self.summary, self.row, self.band_state, self.pre, self.post)

    def cell(self):
        return {'row': self.row, 'band_state': self.band_state, 'pre': self.pre, 'post': self.post}


class ColdCell:
    """콜드 재기동 한 칸. 팔로워 chaos-api-2, 리더 chaos-api-1 순으로 멈추고 역순으로 올렸다. chaos-api-1 은 스냅샷 경계에서
    부팅 복구한 뒤 승격 재생을 생략했고, chaos-api-2 는 그 사이 새로 찍힌 스냅샷에서 부팅 복구해 팔로워로 떴다."""

    experiment = sj.COLD

    def __init__(self, mode='global', tails1=(50, 60, 10), tails2=(7, 8, 9)):
        self.mode = mode
        base = {mk: BAND * mk if mode == 'band' else 0 for mk in MARKETS}
        gap = 1 if mode == 'band' else 3
        self.tails1, self.tails2 = dict(zip(MARKETS, tails1)), dict(zip(MARKETS, tails2))
        self.snap1 = {mk: base[mk] + 100 * mk for mk in MARKETS}
        self.end1 = {mk: self.snap1[mk] + gap * self.tails1[mk] for mk in MARKETS}
        self.snap2 = {mk: self.end1[mk] + 5 for mk in MARKETS}
        self.end2 = {mk: self.snap2[mk] + gap * self.tails2[mk] for mk in MARKETS}
        self.summary, self.row, self.band_state = mode_fixture(mode, sj.COLD)
        self.row['criteria'] = {'2 계약된 반응': {'oldEpoch': 1, 'newEpoch': 2}, '3 복구 시간': {'rtoSeconds': 48.3},
                                '4 무결성': {'사후 권위 run CRITICAL': {'checks': 15, 'violations': 0}},
                                '5 자력 복구': {'acceptResumedAt': '2026-09-15T01:06:10.000Z'}}

        old = {c: [boot_line('00:00', mode), line('00:01', '재생 적용(마켓별): {}')] for c in (API1, API2)}
        self.boot1 = [boot_line('06:00', mode), line('06:02', adopted_text(self.snap1)),
                      line('06:03', '재생 1단계(마켓별 물질화 경계 상한): 마켓 3개, 적용 120건, 대조 300건'),
                      line('06:04', applied_text((mk, self.snap1[mk], self.end1[mk], self.tails1[mk]) for mk in MARKETS))]
        self.boot2 = [boot_line('06:20', mode), line('06:22', adopted_text(self.snap2)),
                      line('06:32', applied_text((mk, self.snap2[mk], self.end2[mk], self.tails2[mk]) for mk in MARKETS))]
        after1 = [line('06:08', skipped_text(self.end1, self.end1), PROMOTION)]
        snaps1 = [{'snapshot_id': i + 1, 'market_id': mk, 'last_applied_seq': self.snap1[mk], 'fencing_epoch': 1}
                  for i, mk in enumerate(MARKETS)]
        snaps2 = [{'snapshot_id': i + 4, 'market_id': mk, 'last_applied_seq': self.snap2[mk], 'fencing_epoch': 2}
                  for i, mk in enumerate(MARKETS)]
        old_runs = [{'id': i, 'market_id': 0, 'replayed_from_seq': 0, 'replayed_to_seq': 0, 'matched': True}
                    for i in (1, 2)]
        env = {c: {'ok': True, 'error': None, '값': {}} for c in (API1, API2)}

        self.pre = copy.deepcopy({
            '단계': 'pre',
            '대기': {'충족': True, '경과(초)': 95.0},
            '활성 마켓': {'ok': True, 'error': None,
                        '마켓': [{'market_id': mk, 'rows': 40, 'head': self.snap1[mk] + 5} for mk in MARKETS]},
            '스냅샷': {'ok': True, 'error': None, '행': snaps1},
            '복구 실행': {'ok': True, 'error': None, '행': old_runs},
            # 멈추기 전 JVM 의 계수다. 재기동 뒤 값이 이보다 작아도 판정은 사후 값만 본다.
            '지표': {API1: metrics(replay=500), API2: metrics(replay=500)},
            '복구 로그': {c: {'ok': True, 'error': None, '줄': old[c]} for c in (API1, API2)},
            '스냅샷 설정 환경변수': env,
        })
        self.post = copy.deepcopy({
            '단계': 'post',
            '활성 마켓': self.pre['활성 마켓'],
            '스냅샷': {'ok': True, 'error': None, '행': snaps1 + snaps2},
            '복구 실행': {'ok': True, 'error': None, '행': old_runs + [
                {'id': 3, 'market_id': 0, 'replayed_from_seq': max(self.snap1.values()),
                 'replayed_to_seq': max(self.end1.values()), 'matched': True},
                {'id': 4, 'market_id': 0, 'replayed_from_seq': max(self.snap2.values()),
                 'replayed_to_seq': max(self.end2.values()), 'matched': True}]},
            '지표': {API1: metrics(replay=sum(tails1)), API2: metrics(replay=sum(tails2))},
            '복구 로그': {API1: {'ok': True, 'error': None, '줄': old[API1] + self.boot1 + after1},
                        API2: {'ok': True, 'error': None, '줄': old[API2] + self.boot2}},
            '스냅샷 설정 환경변수': env,
            '저널 구간 수': {'ok': True, 'error': None, '해석 실패 줄': [], '구간': [
                {'market_id': mk, 'from': snap[mk], 'to': end[mk], 'rows': tails[mk]}
                for snap, end, tails in ((self.snap1, self.end1, self.tails1), (self.snap2, self.end2, self.tails2))
                for mk in MARKETS]},
        })

    def lines(self, container):
        return self.post['복구 로그'][container]['줄']

    def verdict(self):
        return sj.cold_verdict(self.mode, self.summary, self.row, self.band_state, self.pre, self.post)

    def cell(self):
        return {'row': self.row, 'band_state': self.band_state, 'pre': self.pre, 'post': self.post}


def mode_inputs(*cells):
    return {'summary': cells[0].summary, 'cells': {c.experiment: c.cell() for c in cells}}


class VerdictCase(unittest.TestCase):
    def assertVerdict(self, c, category, fragment=None):
        got, reasons, measures = c.verdict()
        self.assertEqual(got, category, reasons)
        if fragment is not None:
            self.assertTrue(any(fragment in r for r in reasons), reasons)
        return measures


class EstablishedTest(VerdictCase):
    def test_global_mode(self):
        m = self.assertVerdict(Cell('global'), sj.ESTABLISHED)
        self.assertEqual(m['꼬리 재생 합'], 120)
        self.assertEqual(m['재생 지표 비교'], '비교함')
        self.assertEqual(m['승격']['컨테이너'], API2)

    def test_band_mode(self):
        m = self.assertVerdict(Cell('band'), sj.ESTABLISHED)
        self.assertEqual(m['채택'][3], 3 * BAND + 300)

    def test_killed_old_leader_scrape_does_not_matter(self):
        c = Cell()
        c.post['지표'][API1] = unreadable()
        self.assertVerdict(c, sj.ESTABLISHED)

    def test_snapshot_env_override_is_noted_not_judged(self):
        c = Cell()
        c.pre['스냅샷 설정 환경변수'][API1]['값'] = {'EXCHANGE_ENGINE_SNAPSHOT_TIMEINTERVAL': '5s'}
        m = self.assertVerdict(c, sj.ESTABLISHED)
        self.assertIn('EXCHANGE_ENGINE_SNAPSHOT_TIMEINTERVAL', m['주'])

    def test_exit_codes(self):
        self.assertEqual(sorted(set(sj.EXIT.values())), [0, 1, 2, 4])
        self.assertNotIn(sj.SKIPPED, sj.EXIT)
        self.assertEqual(sj.EXPERIMENTS, ('E2', 'E4', 'E5', 'E6', 'E7', sj.COLD))


class NotRunTest(VerdictCase):
    def test_experiment_not_run_or_blocked(self):
        for split in ('NOT RUN(시계 스큐 SKIP)', 'NOT RUN(C28 미확인)', 'BLOCKED', None):
            c = Cell()
            c.row['분리 판정'] = split
            self.assertVerdict(c, sj.NOT_RUN, '실험 미성립')

    def test_connection_unknown(self):
        c = Cell()
        c.row['연결 판정'] = 'NOT RUN'
        self.assertVerdict(c, sj.NOT_RUN, '연결 고갈 여부 미확인')

    def test_global_mode_with_latch_row(self):
        c = Cell()
        c.band_state['래치']['래치 행'] = [{'name': 'command_seq'}]
        self.assertVerdict(c, sj.NOT_RUN, '래치 행')

    def test_global_mode_without_latch_state(self):
        c = Cell()
        c.band_state = None
        self.assertVerdict(c, sj.NOT_RUN, '모드 미성립')

    def test_global_mode_with_band_rows(self):
        c = Cell()
        c.band_state['대역 행']['마켓'][4]['band_rows'] = 1
        self.assertVerdict(c, sj.NOT_RUN, '대역 행이 0 이 아닌 마켓')

    def test_global_mode_empty_band_rows_are_not_zero(self):
        c = Cell()
        c.band_state['대역 행']['마켓'] = []
        self.assertVerdict(c, sj.NOT_RUN, '대역 행 조회 실패')

    def test_global_summary_from_latched_run(self):
        c = Cell()
        c.summary['latchBeforeTraffic'] = True
        self.assertVerdict(c, sj.NOT_RUN, '트래픽 전 래치 주행이다')

    def test_band_mode_not_established(self):
        c = Cell('band')
        c.row['cutover']['대역 모드 성립'] = False
        self.assertVerdict(c, sj.NOT_RUN, '대역 모드 성립이 아니다')

    def test_summary_without_snapshot_evidence(self):
        c = Cell()
        c.summary['snapshotEvidence'] = False
        self.assertVerdict(c, sj.NOT_RUN, '스냅샷 채집 주행이 아니다')

    def test_active_market_without_snapshot(self):
        c = Cell()
        c.pre['스냅샷']['행'] = [r for r in c.pre['스냅샷']['행'] if r['market_id'] != 3]
        self.assertVerdict(c, sj.NOT_RUN, '스냅샷 없는 활성 마켓 [3]')

    def test_zero_seq_snapshot_does_not_satisfy(self):
        c = Cell()
        c.pre['스냅샷']['행'][0]['last_applied_seq'] = 0
        self.assertVerdict(c, sj.NOT_RUN, '스냅샷 없는 활성 마켓 [1]')

    def test_no_active_market(self):
        c = Cell()
        c.pre['활성 마켓']['마켓'] = []
        self.assertVerdict(c, sj.NOT_RUN, '활성 마켓이 없다')

    def test_pre_capture_missing_or_failed(self):
        c = Cell()
        c.pre = None
        self.assertVerdict(c, sj.NOT_RUN, 'snapshot_pre.json 이 없다')
        c = Cell()
        c.pre['스냅샷'] = {'ok': False, 'error': 'psql 종료코드 2', '행': None}
        self.assertVerdict(c, sj.NOT_RUN, '주입 전 snapshots 조회 실패')

    def test_no_new_epoch(self):
        c = Cell()
        c.row['criteria']['2 계약된 반응']['newEpoch'] = 1
        self.assertVerdict(c, sj.NOT_RUN, '새 epoch 없음')

    def test_zero_tail(self):
        self.assertVerdict(Cell(tails=(0, 0, 0)), sj.NOT_RUN, '꼬리 재생 없음')


class SkipTest(VerdictCase):
    def test_promotion_replay_skipped_is_not_not_run(self):
        m = self.assertVerdict(Cell().skip(), sj.SKIPPED, '승격 재생 생략(epoch 2, chaos-api-2)')
        self.assertEqual(m['승격']['종류'], '생략')

    def test_skip_does_not_need_promoted_node_metrics(self):
        c = Cell().skip()
        c.post['지표'][API2] = unreadable()
        self.assertVerdict(c, sj.SKIPPED)

    def test_skip_with_fallback_log_is_fallback(self):
        c = Cell().skip()
        c.lines(API1).append(line('05:30', '스냅샷 시작 재생 실패. 스냅샷 없이 전량 재생으로 폴백한다'))
        self.assertVerdict(c, sj.FALLBACK, '주행 창 폴백 로그 1줄(chaos-api-1)')

    def test_skip_with_fallback_counter_is_fallback(self):
        c = Cell().skip()
        c.post['지표'][API2] = metrics(fallback=1)
        self.assertVerdict(c, sj.FALLBACK, '폴백 계수 증분 1')

    def test_skip_line_cursor_below_head(self):
        c = Cell()
        heads = dict(c.end)
        heads[2] += 1
        self.assertVerdict(c.skip(heads=heads), sj.INCOMPLETE, '마켓 커서가 head 아래인 마켓 [2]')

    def test_skip_after_unmet_precondition_is_not_run(self):
        c = Cell().skip()
        c.pre['스냅샷']['행'] = []
        self.assertVerdict(c, sj.NOT_RUN, '스냅샷 전제 미충족')


class IncompleteTest(VerdictCase):
    def test_connection_exhausted(self):
        c = Cell()
        c.row['분리 판정'] = c.row['연결 판정'] = '증거 불완전(연결 고갈)'
        self.assertVerdict(c, sj.INCOMPLETE, '연결 고갈')

    def test_log_capture_failed(self):
        c = Cell()
        c.post['복구 로그'][API2] = {'ok': False, 'error': 'docker logs 종료코드 1', '줄': None}
        self.assertVerdict(c, sj.INCOMPLETE, 'chaos-api-2 사후 복구 로그 채집 실패')
        c = Cell()
        c.pre['복구 로그'][API1] = {'ok': False, 'error': 'TimeoutExpired', '줄': None}
        self.assertVerdict(c, sj.INCOMPLETE, 'chaos-api-1 주입 전 복구 로그 채집 실패')

    def test_no_completion_line_for_new_epoch(self):
        c = Cell()
        c.row['criteria']['2 계약된 반응']['newEpoch'] = 3
        self.assertVerdict(c, sj.INCOMPLETE, 'epoch 3')

    def test_lines_already_present_before_injection_are_not_the_window(self):
        c = Cell()
        c.pre['복구 로그'][API2]['줄'] = copy.deepcopy(c.lines())
        self.assertVerdict(c, sj.INCOMPLETE, 'epoch 2')

    def test_malformed_applied_line(self):
        c = Cell()
        c.replace(c.applied_line, line('05:03', '재생 적용(마켓별): {1=100-150 50건}'))
        self.assertVerdict(c, sj.INCOMPLETE, '로그 형식 해석 실패')

    def test_malformed_promotion_line(self):
        c = Cell()
        c.replace(c.done_line, line('05:04', '승격 재생 완료: epoch=2, 마켓 커서=[1=150], head={1=150}, 소요=1ms', PROMOTION))
        self.assertVerdict(c, sj.INCOMPLETE, '로그 형식 해석 실패')

    def test_no_applied_line(self):
        c = Cell()
        c.lines().remove(c.applied_line)
        self.assertVerdict(c, sj.INCOMPLETE, '재생 적용 줄이 없다')

    def test_promoted_node_metrics_unreadable(self):
        c = Cell()
        c.post['지표'][API2] = unreadable()
        self.assertVerdict(c, sj.INCOMPLETE, '폴백 계수 값을 읽지 못했다')

    def test_absent_series_is_not_zero(self):
        c = Cell()
        node = c.pre['지표'][API2]
        node['줄'] = [x for x in node['줄'] if 'fallback' not in x]
        self.assertVerdict(c, sj.INCOMPLETE, '폴백 계수 값을 읽지 못했다')

    def test_counter_decrease(self):
        c = Cell()
        c.pre['지표'][API2] = metrics(replay=500)
        self.assertVerdict(c, sj.INCOMPLETE, '재생 명령 지표 값이 줄었다')

    def test_promotion_recovery_row_missing(self):
        c = Cell()
        del c.post['복구 실행']['행'][2]
        self.assertVerdict(c, sj.INCOMPLETE, '새 recovery_runs 행이 없다')

    def test_queries_failed(self):
        for key, value, fragment in (
                ('복구 실행', {'ok': False, 'error': 'x', '행': None}, 'recovery_runs 조회 실패'),
                ('스냅샷', {'ok': False, 'error': 'x', '행': None}, '사후 snapshots 조회 실패'),
                ('저널 구간 수', {'ok': False, 'error': 'x', '구간': None}, '저널 구간 행 수 조회 실패')):
            c = Cell()
            c.post[key] = value
            self.assertVerdict(c, sj.INCOMPLETE, fragment)

    def test_range_count_missing(self):
        c = Cell()
        del c.post['저널 구간 수']['구간'][1]
        self.assertVerdict(c, sj.INCOMPLETE, '저널 구간 행 수가 없는 구간')


class FallbackTest(VerdictCase):
    def test_fallback_log_line(self):
        c = Cell()
        lines = c.lines()
        lines.insert(lines.index(c.done_line), line('05:03', '스냅샷 적재 실패. 스냅샷 없이 전량 재생으로 폴백한다'))
        self.assertVerdict(c, sj.FALLBACK, '주행 창 폴백 로그 1줄(chaos-api-2)')

    def test_fallback_log_on_other_container(self):
        c = Cell()
        c.lines(API1).append(line('05:30', '스냅샷 시작 재생 실패. 스냅샷 없이 전량 재생으로 폴백한다'))
        self.assertVerdict(c, sj.FALLBACK, 'chaos-api-1')

    def test_fallback_in_earlier_attempt_still_counts(self):
        c = Cell()
        lines = c.lines()
        i = lines.index(c.adopted_line)
        lines[i:i] = [line('04:50', '스냅샷 시작 재생 실패. 스냅샷 없이 전량 재생으로 폴백한다'),
                      line('04:51', '승격 재생 실패, 재시도한다: epoch=2, 시도=1/3, 경과=10ms, 재시도경과=0ms', PROMOTION)]
        self.assertVerdict(c, sj.FALLBACK, '주행 창 폴백 로그')

    def test_fallback_counter(self):
        c = Cell()
        c.post['지표'][API2] = metrics(fallback=1, replay=120)
        self.assertVerdict(c, sj.FALLBACK, '폴백 계수 증분 1')

    def test_no_adoption_line(self):
        c = Cell()
        c.lines().remove(c.adopted_line)
        self.assertVerdict(c, sj.FALLBACK, '스냅샷 채택 줄이 없다')

    def test_adoption_before_retry_boundary_is_not_this_attempt(self):
        c = Cell()
        lines = c.lines()
        i = lines.index(c.adopted_line)
        lines.insert(i + 1, line('05:01', '승격 재생 실패, 재시도한다: epoch=2, 시도=1/3, 경과=10ms, 재시도경과=0ms', PROMOTION))
        self.assertVerdict(c, sj.FALLBACK, '스냅샷 채택 줄이 없다')

    def test_adoption_on_old_leader_is_not_the_promotion(self):
        c = Cell()
        c.lines().remove(c.adopted_line)
        c.lines(API1).append(copy.deepcopy(c.adopted_line))
        self.assertVerdict(c, sj.FALLBACK, '스냅샷 채택 줄이 없다')

    def test_active_market_not_adopted(self):
        c = Cell()
        c.replace(c.adopted_line, line('05:01', '스냅샷 채택(마켓별): [1@%d, 2@%d]' % (c.snap[1], c.snap[2])))
        self.assertVerdict(c, sj.FALLBACK, '채택되지 않은 활성 마켓 [3]')

    def test_active_market_replayed_from_zero(self):
        c = Cell()
        items = [(1, 0, c.end[1], 50)] + [(mk, c.snap[mk], c.end[mk], c.tails[mk]) for mk in (2, 3)]
        c.replace(c.applied_line, line('05:03', applied_text(items)))
        self.assertVerdict(c, sj.FALLBACK, '시작 커서 0 에서 재생한 활성 마켓 [1]')

    def test_new_full_replay_row(self):
        c = Cell()
        c.post['복구 실행']['행'].append({'id': 4, 'market_id': 0, 'replayed_from_seq': 0,
                                      'replayed_to_seq': max(c.end.values()), 'matched': True})
        self.assertVerdict(c, sj.FALLBACK, 'replayed_from_seq 0')

    def test_fallback_comes_before_incomplete(self):
        c = Cell()
        c.post['지표'][API2] = unreadable()
        c.lines().insert(1, line('05:00', '스냅샷 적재 실패. 스냅샷 없이 전량 재생으로 폴백한다'))
        self.assertVerdict(c, sj.FALLBACK, '주행 창 폴백 로그')


class MismatchTest(VerdictCase):
    def test_applied_count_differs_from_journal(self):
        c = Cell()
        c.post['저널 구간 수']['구간'][0]['rows'] = 49
        self.assertVerdict(c, sj.MISMATCH, '적용 50건, 저널 49행')

    def test_start_cursor_differs_from_adopted_seq(self):
        c = Cell()
        start = c.snap[2] + 5
        items = [(mk, start if mk == 2 else c.snap[mk], c.end[mk], c.tails[mk]) for mk in MARKETS]
        c.replace(c.applied_line, line('05:03', applied_text(items)))
        c.post['저널 구간 수']['구간'][1]['from'] = start
        self.assertVerdict(c, sj.MISMATCH, '마켓 2 시작 커서')

    def test_end_cursor_below_promotion_head(self):
        c = Cell()
        heads = dict(c.end)
        heads[3] = c.end[3] + 1
        c.replace(c.done_line, line('05:04', done_text(c.end, heads), PROMOTION))
        self.assertVerdict(c, sj.MISMATCH, '승격 head')

    def test_adopted_seq_without_snapshot_row(self):
        c = Cell()
        c.post['스냅샷']['행'] = [r for r in c.post['스냅샷']['행'] if r['market_id'] != 2]
        self.assertVerdict(c, sj.MISMATCH, '같은 snapshots 행이 없다')

    def test_unmatched_recovery_row(self):
        c = Cell()
        c.post['복구 실행']['행'].append({'id': 5, 'market_id': 0, 'replayed_from_seq': 300, 'replayed_to_seq': 301,
                                      'matched': False})
        self.assertVerdict(c, sj.MISMATCH, 'C17')

    def test_replay_metric_differs(self):
        c = Cell()
        c.post['지표'][API2] = metrics(replay=119)
        self.assertVerdict(c, sj.MISMATCH, '재생 명령 지표 증분 119')

    def test_replay_metric_not_compared_with_two_applied_lines(self):
        c = Cell()
        lines = c.lines()
        i = lines.index(c.adopted_line)
        lines[i:i] = [line('04:58', applied_text([(1, 100, 101, 1)])),
                      line('04:59', '승격 재생 실패, 재시도한다: epoch=2, 시도=1/3, 경과=5ms, 재시도경과=0ms', PROMOTION)]
        c.post['지표'][API2] = metrics(replay=121)
        c.post['저널 구간 수']['구간'].append({'market_id': 1, 'from': 100, 'to': 101, 'rows': 1})
        m = self.assertVerdict(c, sj.ESTABLISHED)
        self.assertTrue(m['재생 지표 비교'].startswith('생략'), m['재생 지표 비교'])


class ColdEstablishedTest(VerdictCase):
    def test_global_mode(self):
        m = self.assertVerdict(ColdCell('global'), sj.ESTABLISHED)
        self.assertEqual(m['꼬리 재생 합'], {API1: 120, API2: 24})
        self.assertEqual({c: n['재생 지표 비교'] for c, n in m['노드'].items()}, {API1: '비교함', API2: '비교함'})

    def test_band_mode(self):
        c = ColdCell('band')
        m = self.assertVerdict(c, sj.ESTABLISHED)
        self.assertEqual(m['노드'][API2]['채택'][3], c.snap2[3])

    def test_old_process_counters_are_not_deltas(self):
        c = ColdCell()
        c.pre['지표'] = {API1: unreadable(), API2: metrics(fallback=3, replay=9000)}
        self.assertVerdict(c, sj.ESTABLISHED)

    def test_same_boot_range_needs_a_row_per_node(self):
        c = ColdCell()
        c.post['복구 로그'][API2]['줄'] = copy.deepcopy(c.pre['복구 로그'][API2]['줄']) + [
            boot_line('06:20'), line('06:22', adopted_text(c.snap1)),
            line('06:32', applied_text((mk, c.snap1[mk], c.end1[mk], c.tails1[mk]) for mk in MARKETS))]
        c.post['지표'][API2] = metrics(replay=120)
        rows = c.post['복구 실행']['행']
        rows[3] = dict(rows[2], id=4)
        self.assertVerdict(c, sj.ESTABLISHED)
        del rows[3]
        self.assertVerdict(c, sj.INCOMPLETE, '부팅 2 번보다 적다')

    def test_replay_metric_not_compared_after_promotion_replay(self):
        c = ColdCell()
        c.lines(API1)[-1:] = [line('06:08', adopted_text(c.end1)),
                              line('06:09', applied_text((mk, c.end1[mk], c.end1[mk], 0) for mk in MARKETS)),
                              line('06:10', done_text(c.end1, c.end1), PROMOTION)]
        c.post['지표'][API1] = metrics(replay=999)
        m = self.assertVerdict(c, sj.ESTABLISHED)
        self.assertTrue(m['노드'][API1]['재생 지표 비교'].startswith('생략'), m['노드'][API1])


class ColdNotRunTest(VerdictCase):
    def test_experiment_skipped_by_clock_guard(self):
        c = ColdCell()
        c.row['분리 판정'] = 'NOT RUN(시계 스큐 SKIP)'
        self.assertVerdict(c, sj.NOT_RUN, '실험 미성립')

    def test_precondition_not_met(self):
        c = ColdCell()
        c.pre['스냅샷']['행'] = c.pre['스냅샷']['행'][:2]
        self.assertVerdict(c, sj.NOT_RUN, '스냅샷 없는 활성 마켓 [3]')

    def test_band_mode_not_established(self):
        c = ColdCell('band')
        c.row['cutover']['대역 모드 성립'] = False
        self.assertVerdict(c, sj.NOT_RUN, '대역 모드 성립이 아니다')

    def test_zero_tail_on_one_node(self):
        self.assertVerdict(ColdCell(tails2=(0, 0, 0)), sj.NOT_RUN, "['chaos-api-2']")


class ColdIncompleteTest(VerdictCase):
    def test_boot_marker_missing(self):
        c = ColdCell()
        c.lines(API2).remove(c.boot2[0])
        self.assertVerdict(c, sj.INCOMPLETE, 'chaos-api-2 주행 창 기동 표지 0줄')

    def test_second_boot_in_window(self):
        c = ColdCell()
        c.lines(API1).extend([boot_line('07:00'), line('07:02', adopted_text(c.snap1))])
        self.assertVerdict(c, sj.INCOMPLETE, 'chaos-api-1 주행 창 기동 표지 2줄')

    def test_boot_recovery_not_finished_is_not_fallback(self):
        c = ColdCell()
        c.lines(API2).remove(c.boot2[1])
        c.lines(API2).remove(c.boot2[2])
        self.assertVerdict(c, sj.INCOMPLETE, 'chaos-api-2 부팅 복구 구간에 재생 적용 줄이 없다')

    def test_restart_record_missing(self):
        c = ColdCell()
        del c.row['criteria']['5 자력 복구']
        self.assertVerdict(c, sj.INCOMPLETE, '접수 재개 기록이 없다')
        c = ColdCell()
        c.row['criteria']['4 무결성']['사후 권위 run CRITICAL'] = None
        self.assertVerdict(c, sj.INCOMPLETE, 'CRITICAL 기록이 없다')
        c = ColdCell()
        c.row['criteria']['4 무결성']['사후 권위 run CRITICAL']['checks'] = 0
        self.assertVerdict(c, sj.INCOMPLETE, 'CRITICAL 체크가 0 이다')

    def test_restarted_node_metrics_unreadable(self):
        c = ColdCell()
        c.post['지표'][API1] = unreadable()
        self.assertVerdict(c, sj.INCOMPLETE, 'chaos-api-1 에서 폴백 계수 값을 읽지 못했다')

    def test_boot_recovery_row_missing(self):
        c = ColdCell()
        del c.post['복구 실행']['행'][3]
        self.assertVerdict(c, sj.INCOMPLETE, '새 recovery_runs 행이 0 개로 부팅 1 번보다 적다')

    def test_range_count_missing(self):
        c = ColdCell()
        del c.post['저널 구간 수']['구간'][5]
        self.assertVerdict(c, sj.INCOMPLETE, '저널 구간 행 수가 없는 구간')


class ColdFailTest(VerdictCase):
    def test_fallback_log(self):
        c = ColdCell()
        lines = c.lines(API1)
        lines.insert(lines.index(c.boot1[3]), line('06:03', '스냅샷 적재 실패. 스냅샷 없이 전량 재생으로 폴백한다'))
        self.assertVerdict(c, sj.FALLBACK, '주행 창 폴백 로그 1줄(chaos-api-1)')

    def test_fallback_counter_after_restart(self):
        c = ColdCell()
        c.post['지표'][API2] = metrics(fallback=1, replay=24)
        self.assertVerdict(c, sj.FALLBACK, 'chaos-api-2 사후 폴백 계수 1')

    def test_no_adoption_line(self):
        c = ColdCell()
        c.lines(API2).remove(c.boot2[1])
        self.assertVerdict(c, sj.FALLBACK, 'chaos-api-2 부팅 복구 구간에 스냅샷 채택 줄이 없다')

    def test_adoption_after_boot_section_is_not_boot_adoption(self):
        c = ColdCell()
        c.lines(API1).remove(c.boot1[1])
        c.lines(API1).append(line('06:09', adopted_text(c.snap1)))
        self.assertVerdict(c, sj.FALLBACK, 'chaos-api-1 부팅 복구 구간에 스냅샷 채택 줄이 없다')

    def test_active_market_replayed_from_zero(self):
        c = ColdCell()
        items = [(1, 0, c.end2[1], 7)] + [(mk, c.snap2[mk], c.end2[mk], c.tails2[mk]) for mk in (2, 3)]
        lines = c.lines(API2)
        lines[lines.index(c.boot2[2])] = line('06:32', applied_text(items))
        self.assertVerdict(c, sj.FALLBACK, 'chaos-api-2 시작 커서 0 에서 재생한 활성 마켓 [1]')

    def test_full_replay_row(self):
        c = ColdCell()
        c.post['복구 실행']['행'].append({'id': 5, 'market_id': 0, 'replayed_from_seq': 0, 'replayed_to_seq': 900,
                                      'matched': True})
        self.assertVerdict(c, sj.FALLBACK, 'replayed_from_seq 0')

    def test_accept_not_resumed(self):
        c = ColdCell()
        c.row['criteria']['5 자력 복구']['acceptResumedAt'] = None
        self.assertVerdict(c, sj.RESTART_FAILED, '재기동 뒤 접수 재개가 없다')

    def test_post_run_critical_violation(self):
        c = ColdCell()
        c.row['criteria']['4 무결성']['사후 권위 run CRITICAL']['violations'] = 2
        self.assertVerdict(c, sj.RESTART_FAILED, 'CRITICAL 위반 행 2')

    def test_applied_count_differs_from_journal(self):
        c = ColdCell()
        c.post['저널 구간 수']['구간'][4]['rows'] = 7
        self.assertVerdict(c, sj.MISMATCH, 'chaos-api-2 마켓 2 구간')

    def test_start_cursor_differs_from_adopted_seq(self):
        c = ColdCell()
        start = c.snap1[1] + 2
        items = [(mk, start if mk == 1 else c.snap1[mk], c.end1[mk], c.tails1[mk]) for mk in MARKETS]
        lines = c.lines(API1)
        lines[lines.index(c.boot1[3])] = line('06:04', applied_text(items))
        c.post['저널 구간 수']['구간'][0]['from'] = start
        self.assertVerdict(c, sj.MISMATCH, 'chaos-api-1 마켓 1 시작 커서')

    def test_adopted_seq_without_snapshot_row(self):
        c = ColdCell()
        c.post['스냅샷']['행'] = [r for r in c.post['스냅샷']['행'] if r['snapshot_id'] != 5]
        self.assertVerdict(c, sj.MISMATCH, 'chaos-api-2 채택')

    def test_unmatched_recovery_row(self):
        c = ColdCell()
        c.post['복구 실행']['행'].append({'id': 6, 'market_id': 0, 'replayed_from_seq': 300, 'replayed_to_seq': 310,
                                      'matched': False})
        self.assertVerdict(c, sj.MISMATCH, 'C17')

    def test_replay_metric_differs(self):
        c = ColdCell()
        c.post['지표'][API1] = metrics(replay=119)
        self.assertVerdict(c, sj.MISMATCH, 'chaos-api-1 사후 재생 명령 지표 119')


class AggregationTest(unittest.TestCase):
    def test_all_established(self):
        r = sj.judge({'global': mode_inputs(Cell('global'), ColdCell('global')),
                      'band': mode_inputs(Cell('band'), ColdCell('band'))}, sj.MODES, ('E4', sj.COLD))
        self.assertEqual(r['최종 판정'], sj.ESTABLISHED)
        self.assertEqual(sj.EXIT[r['최종 판정']], 0)
        self.assertEqual(r['사유'], [])
        self.assertEqual(r['칸'][0]['RTO(초)'], 6.2)
        self.assertEqual(r['최종 조건']['band'],
                         {'콜드 재기동 칸': sj.ESTABLISHED, '장애 칸 성립': 1, '장애 칸 재생 생략': 0, '장애 칸': 1})

    def test_skipped_fault_cells_do_not_block(self):
        exps = ('E2', 'E4', 'E5', sj.COLD)
        g = mode_inputs(Cell('global', experiment='E2').skip(), Cell('global', experiment='E4'),
                        Cell('global', experiment='E5').skip(), ColdCell('global'))
        b = mode_inputs(Cell('band', experiment='E2'), Cell('band', experiment='E4').skip(),
                        Cell('band', experiment='E5').skip(), ColdCell('band'))
        r = sj.judge({'global': g, 'band': b}, sj.MODES, exps)
        self.assertEqual([c['칸 판정'] for c in r['칸']],
                         [sj.SKIPPED, sj.ESTABLISHED, sj.SKIPPED, sj.ESTABLISHED,
                          sj.ESTABLISHED, sj.SKIPPED, sj.SKIPPED, sj.ESTABLISHED])
        self.assertEqual(r['최종 판정'], sj.ESTABLISHED)
        self.assertEqual(sj.EXIT[r['최종 판정']], 0)
        self.assertEqual(r['최종 조건']['band']['장애 칸 재생 생략'], 2)

    def test_all_fault_cells_skipped_in_one_mode_is_not_run(self):
        exps = ('E2', 'E4', sj.COLD)
        g = mode_inputs(Cell('global', experiment='E2'), Cell('global', experiment='E4'), ColdCell('global'))
        b = mode_inputs(Cell('band', experiment='E2').skip(), Cell('band', experiment='E4').skip(), ColdCell('band'))
        r = sj.judge({'global': g, 'band': b}, sj.MODES, exps)
        self.assertEqual(r['최종 판정'], sj.NOT_RUN)
        self.assertEqual(sj.EXIT[r['최종 판정']], 2)
        self.assertIn('band: 장애 칸 성립 0(장애 칸 2, 재생 생략 2)', r['사유'])

    def test_cold_cell_fail_is_fail(self):
        cold = ColdCell('band')
        cold.post['지표'][API2] = metrics(fallback=1, replay=24)
        r = sj.judge({'global': mode_inputs(Cell('global'), ColdCell('global')),
                      'band': mode_inputs(Cell('band'), cold)}, sj.MODES, ('E4', sj.COLD))
        self.assertEqual((r['칸'][3]['스냅샷 판정'], r['칸'][3]['칸 판정']), (sj.FALLBACK, sj.FAIL))
        self.assertEqual(r['최종 판정'], sj.FAIL)
        self.assertEqual(sj.EXIT[r['최종 판정']], 1)

    def test_cold_experiment_fail_is_fail(self):
        cold = ColdCell('global')
        cold.row['분리 판정'] = cold.row['실험 판정'] = 'FAIL'
        r = sj.judge({'global': mode_inputs(Cell('global'), cold),
                      'band': mode_inputs(Cell('band'), ColdCell('band'))}, sj.MODES, ('E4', sj.COLD))
        self.assertEqual(r['칸'][1]['칸 판정'], sj.FAIL)
        self.assertEqual(r['최종 판정'], sj.FAIL)

    def test_cold_precondition_not_met_is_not_run(self):
        cold = ColdCell('band')
        cold.pre['스냅샷']['행'] = []
        r = sj.judge({'global': mode_inputs(Cell('global'), ColdCell('global')),
                      'band': mode_inputs(Cell('band'), cold)}, sj.MODES, ('E4', sj.COLD))
        self.assertEqual(r['최종 판정'], sj.NOT_RUN)
        self.assertIn('band: 콜드 재기동 칸이 성립이 아니다(NOT RUN)', r['사유'])

    def test_skipped_cell_with_failed_experiment_is_fail(self):
        skipped = Cell('global').skip()
        skipped.row['분리 판정'] = 'FAIL'
        r = sj.judge({'global': mode_inputs(skipped, Cell('global', experiment='E2'), ColdCell('global')),
                      'band': mode_inputs(Cell('band', experiment='E2'), ColdCell('band'))}, sj.MODES,
                     ('E2', 'E4', sj.COLD))
        e4 = [c for c in r['칸'] if c['모드'] == 'global' and c['실험'] == 'E4'][0]
        self.assertEqual((e4['스냅샷 판정'], e4['칸 판정']), (sj.SKIPPED, sj.FAIL))
        self.assertEqual(r['최종 판정'], sj.FAIL)

    def test_split_fail_with_established_snapshot_is_fail(self):
        c = Cell()
        c.row['분리 판정'] = 'FAIL(C28 섞임)'
        r = sj.judge({'global': mode_inputs(c, ColdCell())}, ('global',), ('E4', sj.COLD))
        self.assertEqual(r['칸'][0]['스냅샷 판정'], sj.ESTABLISHED)
        self.assertEqual(r['최종 판정'], sj.FAIL)
        self.assertEqual(sj.EXIT[r['최종 판정']], 1)

    def test_order_fail_incomplete_not_run(self):
        ok = Cell(experiment='E2')
        fail = Cell(experiment='E4')
        fail.post['지표'][API2] = metrics(fallback=1, replay=120)
        incomplete = Cell(experiment='E5')
        incomplete.post['스냅샷'] = {'ok': False, 'error': 'x', '행': None}
        not_run = Cell(experiment='E6')
        not_run.row['분리 판정'] = 'NOT RUN'

        def final(*faults):
            exps = tuple(c.experiment for c in faults) + (sj.COLD,)
            inputs = {'global': mode_inputs(*faults, ColdCell('global')),
                      'band': mode_inputs(*[Cell('band', experiment=c.experiment) for c in faults], ColdCell('band'))}
            return sj.judge(inputs, sj.MODES, exps)['최종 판정']

        self.assertEqual(final(ok, not_run, incomplete, fail), sj.FAIL)
        self.assertEqual(final(ok, not_run, incomplete), sj.INCOMPLETE)
        self.assertEqual(final(ok, not_run), sj.ESTABLISHED)
        self.assertEqual(final(not_run), sj.NOT_RUN)

    def test_missing_cold_cell_is_not_run(self):
        r = sj.judge({'global': mode_inputs(Cell('global')), 'band': mode_inputs(Cell('band'))}, sj.MODES,
                     ('E4', sj.COLD))
        self.assertEqual([c['칸 판정'] for c in r['칸']], [sj.ESTABLISHED, sj.NOT_RUN, sj.ESTABLISHED, sj.NOT_RUN])
        self.assertEqual(r['최종 판정'], sj.NOT_RUN)

    def test_single_mode_request_is_not_run(self):
        r = sj.judge({'global': mode_inputs(Cell('global'), ColdCell('global'))}, ('global',), ('E4', sj.COLD))
        self.assertEqual({c['칸 판정'] for c in r['칸']}, {sj.ESTABLISHED})
        self.assertEqual(r['최종 판정'], sj.NOT_RUN)

    def test_image_mismatch_makes_every_cell_not_run(self):
        g, b = Cell('global'), Cell('band')
        b.summary['imageId'] = 'sha256:other'
        g.post['지표'][API2] = metrics(fallback=1, replay=120)
        r = sj.judge({'global': mode_inputs(g, ColdCell('global')), 'band': mode_inputs(b, ColdCell('band'))},
                     sj.MODES, ('E4', sj.COLD))
        self.assertEqual({c['칸 판정'] for c in r['칸']}, {sj.NOT_RUN})
        self.assertEqual(r['최종 판정'], sj.NOT_RUN)
        self.assertTrue(r['식별']['미충족'])


class LoaderTest(unittest.TestCase):
    def test_main_reads_mode_folders(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for mode in sj.MODES:
                cells = (Cell(mode), ColdCell(mode))
                (root / mode).mkdir()
                summary = dict(cells[0].summary, experiments=[c.row for c in cells])
                (root / mode / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False), encoding='utf-8')
                for c in cells:
                    folder = root / mode / c.experiment
                    folder.mkdir()
                    for name, obj in (('band_state.json', c.band_state), ('snapshot_pre.json', c.pre),
                                      ('snapshot_post.json', c.post)):
                        (folder / name).write_text(json.dumps(obj, ensure_ascii=False), encoding='utf-8')
            out = root / 'verdict.json'
            with contextlib.redirect_stdout(io.StringIO()):
                rc = sj.main(['--global', str(root / 'global'), '--band', str(root / 'band'), '--out', str(out)])
            self.assertEqual(rc, 0)
            verdict = json.loads(out.read_text(encoding='utf-8'))
            self.assertEqual(verdict['최종 판정'], sj.ESTABLISHED)
            self.assertEqual(len(verdict['칸']), 2 * len(sj.EXPERIMENTS))

    def test_missing_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(sj.load_mode(tmp), {'summary': None, 'cells': {}})


if __name__ == '__main__':
    unittest.main()
