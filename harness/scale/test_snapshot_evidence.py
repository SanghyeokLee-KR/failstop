"""snapshot_evidence 해석 시험. 로그 줄 거르기, 승격·채택·재생 적용 줄 해석, 지표 줄 합산, 환경변수 거르기를 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import unittest

import snapshot_evidence as se

PREFIX = '2026-09-15T10:00:00.000+09:00  INFO 1 --- [exchange] [main] '


class LogFilterTest(unittest.TestCase):
    def test_keeps_recovery_lines(self):
        for text in (PREFIX + 'k.e.engine.EngineRecovery : 스냅샷 채택(마켓별): [1@5]',
                     PREFIX + 'k.e.engine.EngineRecovery : 재생 적용(마켓별): {1=5..9 4건}',
                     PREFIX + 'ERROR k.e.engine.EngineRecovery : 스냅샷 적재 실패. 스냅샷 없이 전량 재생으로 폴백한다',
                     PREFIX + 'kr.exchange.engine.PromotionCoordinator : 승격 재생 완료: epoch=2, 마켓 커서={1=9}, '
                              'head={1=9}, 소요=3ms',
                     PREFIX + 'k.e.snapshot.SnapshotStore : 스냅샷 폐기(state_checksum 불일치): market=1, seq=5'):
            self.assertTrue(se.keep_log_line(text), text)

    def test_drops_other_lines(self):
        for text in (PREFIX + 'k.e.api.OrderController : 스냅샷 채택(마켓별): [1@5]',
                     '\tat kr.exchange.engine.EngineRecovery.recoverFromSnapshots(EngineRecovery.java:236)',
                     'java.lang.IllegalStateException: 크래시 창 이벤트 3건 재적재 불가: 리더 승격 실패',
                     PREFIX + 'k.e.security.ApiAuthFilter : 인증 실패 EngineRecovery',
                     PREFIX + 'k.e.engine.EngineRecovery : 스냅샷 채택(마켓별): [' + '1@5, ' * 500 + '1@5]'):
            self.assertFalse(se.keep_log_line(text), text[:80])

    def test_filter_log_splits_docker_timestamp(self):
        raw = '\n'.join([
            '2026-09-15T01:00:00.000000001Z ' + PREFIX + 'k.e.engine.EngineRecovery : 재생 적용(마켓별): {}',
            '2026-09-15T01:00:00.000000002Z ' + PREFIX + 'k.e.auth.LoginService : 로그인 성공',
            '2026-09-15T01:00:00.000000003Z \tat kr.exchange.engine.EngineRecovery.recover(EngineRecovery.java:1)'])
        lines = se.filter_log(raw)
        self.assertEqual([x['ts'] for x in lines], ['2026-09-15T01:00:00.000000001Z'])
        self.assertTrue(lines[0]['text'].startswith(PREFIX))


class ParseTest(unittest.TestCase):
    def test_adopted(self):
        text = PREFIX + '스냅샷 채택(마켓별): [1@1000000000005, 3@3000000000010]'
        self.assertEqual(se.parse_adopted(text), {1: 1000000000005, 3: 3000000000010})
        self.assertIsNone(se.parse_adopted(PREFIX + '재생 적용(마켓별): {}'))

    def test_applied(self):
        self.assertEqual(se.parse_applied('재생 적용(마켓별): {1=5..9 4건, 2=0..12 12건}'),
                         {1: (5, 9, 4), 2: (0, 12, 12)})
        self.assertEqual(se.parse_applied('재생 적용(마켓별): {}'), {})
        self.assertIsNone(se.parse_applied('재생 1단계(마켓별 물질화 경계 상한): 마켓 1개'))

    def test_malformed_lines_raise(self):
        cases = ((se.parse_applied, '재생 적용(마켓별): {1=5-9 4건}'),
                 (se.parse_applied, '재생 적용(마켓별) {1=5..9 4건}'),
                 (se.parse_adopted, '스냅샷 채택(마켓별): [1:5]'),
                 (se.parse_adopted, '스냅샷 채택(마켓별): [1@5, 1@6]'),
                 (se.parse_promotion, '승격 재생 완료: epoch=two, 마켓 커서={}, head={}'))
        for parse, text in cases:
            with self.assertRaises(ValueError, msg=text):
                parse(text)

    def test_promotion(self):
        done = se.parse_promotion('승격 재생 완료: epoch=2, 마켓 커서={1=9, 2=12}, head={1=9, 2=12}, 소요=31ms')
        self.assertEqual(done, {'종류': '완료', 'epoch': 2, '마켓 커서': {1: 9, 2: 12}, 'head': {1: 9, 2: 12}})
        skipped = se.parse_promotion('승격 재생 생략(모든 마켓 head 도달), 라이터에 설치: epoch=3, 마켓 커서={1=9}, head={1=9}')
        self.assertEqual((skipped['종류'], skipped['epoch']), ('생략', 3))
        self.assertIsNone(se.parse_promotion('승격 재생 실패, 재시도한다: epoch=2, 시도=1/3'))

    def test_boundary_and_fallback(self):
        for text in ('승격 재생 실패, 리더십을 반납한다(재승격 허용): epoch=2', '승격 재생 실패, 재시도한다: epoch=2',
                     '승격 재생 생략(모든 마켓 head 도달), 라이터에 설치: epoch=2', '승격 재생 완료: epoch=2'):
            self.assertTrue(se.is_boundary(text), text)
        self.assertFalse(se.is_boundary('승격 재생은 끝났으나 단일 라이터가 정지했다. 종신 강등한다: epoch=2'))
        self.assertTrue(se.is_fallback('스냅샷 시작 재생 실패. 스냅샷 없이 전량 재생으로 폴백한다'))
        self.assertFalse(se.is_fallback('스냅샷 채택(마켓별): [1@5]'))

    def test_applied_ranges_records_bad_lines(self):
        logs = {'chaos-api-2': {'ok': True, '줄': [{'ts': 't1', 'text': '재생 적용(마켓별): {1=5..9 4건}'},
                                                  {'ts': 't2', 'text': '재생 적용(마켓별): {1=bad}'}]},
                'chaos-api-1': {'ok': False, '줄': None}}
        ranges, bad = se.applied_ranges(logs)
        self.assertEqual(ranges, {(1, 5, 9)})
        self.assertEqual([b['ts'] for b in bad], ['t2'])


class MetricTest(unittest.TestCase):
    def test_total_sums_series_of_one_name(self):
        lines = ['exchange_snapshot_captured_total{application="exchange",market="1",} 3.0',
                 'exchange_snapshot_captured_total{application="exchange",market="2",} 4.0',
                 'exchange_snapshot_dropped_total{application="exchange",market="1",} 1.0',
                 'exchange_recovery_snapshot_fallback_total 0.0']
        self.assertEqual(se.metric_total(lines, 'exchange_snapshot_captured_total'), 7.0)
        self.assertEqual(se.metric_total(lines, 'exchange_recovery_snapshot_fallback_total'), 0.0)
        self.assertIsNone(se.metric_total(lines, 'exchange_journal_replay_commands_total'))

    def test_labels_and_non_finite_values(self):
        parsed = se.parse_metrics(['exchange_journal_band_remaining{market="1",name="command_seq"} 999999999999.0',
                                   'exchange_snapshot_last_persisted_seq{market="2"} NaN', '# HELP x y'])
        self.assertEqual(parsed, [('exchange_journal_band_remaining', {'market': '1', 'name': 'command_seq'},
                                   999999999999.0)])


class EnvTest(unittest.TestCase):
    def test_keeps_only_snapshot_prefix(self):
        env = ['EXCHANGE_ENGINE_SNAPSHOT_TIMEINTERVAL=5s', 'EXCHANGE_MASTER_KEY=example-not-kept',
               'SPRING_PROFILES_ACTIVE=dev', 'exchange_engine_snapshot_enabled=false', 'NO_EQUALS_SIGN']
        self.assertEqual(se.snapshot_env_values(env), {'EXCHANGE_ENGINE_SNAPSHOT_TIMEINTERVAL': '5s',
                                                       'exchange_engine_snapshot_enabled': 'false'})

    def test_parse_api(self):
        self.assertEqual(se.parse_api('chaos-api-1=18080, chaos-api-2=28080'),
                         [('chaos-api-1', 18080), ('chaos-api-2', 28080)])
        with self.assertRaises(ValueError):
            se.parse_api('chaos-api-1')


if __name__ == '__main__':
    unittest.main()
