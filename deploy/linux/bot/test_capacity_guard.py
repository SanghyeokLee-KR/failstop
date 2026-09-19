import copy
import unittest
from capacity_guard import METRICS, NODES, decision


class CapacityGuardTest(unittest.TestCase):
    def setUp(self):
        self.rows = []
        for node in NODES:
            for metric, value in zip(METRICS, [40*1024**3, 50*1024**3, 9000, 10000]):
                self.rows.append({'metric': {'node': node, '__name__': metric}, 'value': [0, str(value)]})
        self.ages = [{'metric': {'node': node}, 'value': [0, '15']} for node in NODES]

    def test_healthy_hosts_continue(self):
        result = decision(self.rows, self.ages, 2)
        self.assertFalse(result['stop'])
        self.assertEqual(result['failures'], 0)
        self.assertEqual(set(result['nodes']), NODES)

    def test_each_host_can_stop_bots(self):
        for node in NODES:
            rows = copy.deepcopy(self.rows)
            next(r for r in rows if r['metric'] == {'node': node, '__name__': METRICS[0]})['value'][1] = str(10*1024**3)
            self.assertTrue(decision(rows, self.ages)['stop'])

    def test_inode_exhaustion_stops(self):
        next(r for r in self.rows if r['metric']['__name__'] == METRICS[2])['value'][1] = '1000'
        self.assertTrue(decision(self.rows, self.ages)['stop'])

    def test_missing_stale_nan_duplicate_fail_closed_after_three_checks(self):
        variants = [(self.rows[:-1], self.ages), (self.rows + [self.rows[0]], self.ages)]
        stale = copy.deepcopy(self.ages); stale[0]['value'][1] = '91'
        variants.append((self.rows, stale))
        invalid = copy.deepcopy(self.rows); invalid[0]['value'][1] = 'NaN'
        variants.append((invalid, self.ages))
        for rows, ages in variants:
            self.assertFalse(decision(rows, ages, 0)['stop'])
            self.assertTrue(decision(rows, ages, 2)['stop'])


if __name__ == '__main__':
    unittest.main()
