"""failover_gaps 시험. 원자료 모양은 patroni_observe.py(kind=write)와 ack_recorder.py(type=attempt)가 적는 그대로다."""
import unittest

import failover_gaps as fg


def w(t, code):
    return {'kind': 'write', 't': t, 'code': code}


def a(t_end, result='acked'):
    return {'type': 'attempt', 't_end': t_end, 'result': result}


class WriteOutageTest(unittest.TestCase):

    def test_outage_spans_first_non_401_to_stable_401(self):
        samples = [w(99, 401), w(100.5, 503), w(101.5, 503), w(120, 423), w(140, 401), w(141, 423), w(142, 401), w(143, 401)]
        out = fg.write_outage(samples, 100)
        self.assertEqual(100.5, out['first_bad'])
        # 140 에서 401 이 한 번 났지만 다시 423 이었다. 끝까지 401 인 첫 표본은 142 다
        self.assertEqual(142, out['back'])
        self.assertEqual(41.5, out['outage_s'])
        self.assertEqual({'503': 2, '423': 2, '401': 1}, out['codes'])

    def test_no_failure_means_zero(self):
        out = fg.write_outage([w(100, 401), w(101, 401)], 100)
        self.assertEqual(0.0, out['outage_s'])

    def test_not_recovered_has_no_back(self):
        out = fg.write_outage([w(100, 401), w(101, 503), w(102, 423)], 100)
        self.assertIsNone(out['back'])
        self.assertIsNone(out['outage_s'])

    def test_samples_before_t0_are_ignored(self):
        out = fg.write_outage([w(90, 503), w(100, 401), w(101, 401)], 100)
        self.assertEqual(0.0, out['outage_s'])


class AckGapTest(unittest.TestCase):

    def test_largest_gap_after_t0(self):
        out = fg.ack_gap([a(98.0), a(99.9), a(100.2, 'unknown'), a(131.4), a(131.5)], 100)
        self.assertEqual(99.9, out['gap_from'])
        self.assertEqual(131.4, out['gap_to'])
        self.assertEqual(31.5, out['gap_s'])
        self.assertEqual(4, out['acked'])

    def test_acks_right_after_t0_do_not_hide_the_outage(self):
        # T0 는 kill 명령 직전이라 그 뒤 0.3초까지 확인이 더 온다. 공백은 그 뒤다.
        out = fg.ack_gap([a(99.5), a(100.003), a(100.3), a(160.0), a(160.1)], 100)
        self.assertEqual(100.3, out['gap_from'])
        self.assertEqual(59.7, out['gap_s'])

    def test_no_ack_after_means_no_gap(self):
        self.assertIsNone(fg.ack_gap([a(99.0)], 100)['gap_s'])


if __name__ == '__main__':
    unittest.main()
