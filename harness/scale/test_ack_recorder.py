"""ack_recorder 시험. 결과 분류(acked·failed·unknown), 소켓 제한과 소켓만 닫기, 오류 상한 없음, 살아 있음 기록, 대조를 가짜 pq 로 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import json
import os
import socket
import tempfile
import threading
import time
import types
import unittest

import ack_recorder as ar


class FakeResult:
    def __init__(self, status, value=None, severity=None, sqlstate=None, message=None, rows=None):
        self.status = status
        self._value = value
        self._fields = {FakePq.DiagnosticField.SEVERITY_NONLOCALIZED: severity,
                        FakePq.DiagnosticField.SQLSTATE: sqlstate,
                        FakePq.DiagnosticField.MESSAGE_PRIMARY: message}
        self._rows = rows
        self.ntuples = len(rows) if rows is not None else (1 if value is not None else 0)
        self.error_message = (message or '').encode()

    def get_value(self, row, col):
        if self._rows is not None:
            v = self._rows[row]
            if isinstance(v, tuple):
                v = v[col]
            return None if v is None else str(v).encode()
        return None if self._value is None else str(self._value).encode()

    def error_field(self, field):
        v = self._fields.get(field)
        return None if v is None else v.encode()


class FakePq:
    class ConnStatus:
        OK, BAD = 0, 1

    class ExecStatus:
        COMMAND_OK, TUPLES_OK, FATAL_ERROR = 1, 2, 7

    class DiagnosticField:
        SEVERITY_NONLOCALIZED, SQLSTATE, MESSAGE_PRIMARY = 86, 67, 77


def acked_events(n):
    return [('result', FakeResult(FakePq.ExecStatus.TUPLES_OK, value=n)), ('rfq',)]


class FakePgconn:
    """socketpair 로 select 를 깨운다. send 마다 behaviors 에서 하나를 꺼내 응답 사건을 만든다.

    behavior: 'ack', 'hang', 'lost', 'ack_then_lost', 'send_fail', ('error', severity, sqlstate, message),
    ('fatal_then_close', sqlstate, message).
    """

    def __init__(self, behaviors, server='10.159.1.11', status=FakePq.ConnStatus.OK, repl_rows=None):
        self.a, self.b = socket.socketpair()
        self.a.setblocking(False)
        self.behaviors = list(behaviors)
        self.server = server
        self.status = status
        self.error_message = b'could not connect' if status != FakePq.ConnStatus.OK else b''
        self.pending, self.available, self.done = [], [], False
        self.sent = []
        self.finished = False
        self.shut_down = False
        self.repl_rows = repl_rows or []

    @property
    def socket(self):
        return self.a.fileno()

    def _signal(self):
        if self.pending:
            self.b.send(b'x')

    def send_query_params(self, sql, params):
        if self.finished:
            raise RuntimeError('connection finished')
        self.sent.append((sql, params))
        self.available, self.done = [], False
        if sql.startswith(b'SELECT inet_server_addr'):
            self.pending = [('result', FakeResult(FakePq.ExecStatus.TUPLES_OK, value=self.server)), ('rfq',)]
        elif sql == ar.REPL_SQL:
            self.pending = [('result', FakeResult(FakePq.ExecStatus.TUPLES_OK, rows=list(self.repl_rows))), ('rfq',)]
        else:
            behavior = self.behaviors.pop(0) if self.behaviors else 'ack'
            n = int(params[0]) if params else 0
            if behavior == 'send_fail':
                raise RuntimeError('sending query failed: connection reset')
            if behavior == 'ack':
                self.pending = acked_events(n)
            elif behavior == 'hang':
                self.pending = []
            elif behavior == 'lost':
                self.pending = [('lost',)]
            elif behavior == 'ack_then_lost':
                self.pending = [('result', FakeResult(FakePq.ExecStatus.TUPLES_OK, value=n)), ('lost',)]
            elif behavior[0] == 'error':
                _, severity, sqlstate, message = behavior
                self.pending = [('result', FakeResult(FakePq.ExecStatus.FATAL_ERROR, severity=severity,
                                                      sqlstate=sqlstate, message=message)), ('rfq',)]
            elif behavior[0] == 'fatal_then_close':
                _, sqlstate, message = behavior
                self.pending = [('result', FakeResult(FakePq.ExecStatus.FATAL_ERROR, severity='FATAL',
                                                      sqlstate=sqlstate, message=message)), ('lost',)]
        self._signal()

    def consume_input(self):
        try:
            data = self.a.recv(1)
        except BlockingIOError:
            return
        if data == b'':
            raise RuntimeError('consuming input failed: socket shut down')
        event = self.pending.pop(0)
        if event[0] == 'lost':
            raise RuntimeError('consuming input failed: server closed the connection unexpectedly')
        if event[0] == 'result':
            self.available.append(event[1])
        else:
            self.done = True
        self._signal()

    def is_busy(self):
        return not self.available and not self.done

    def get_result(self):
        if self.available:
            return self.available.pop(0)
        self.done = False
        return None

    def finish(self):
        self.finished = True
        self.b.setblocking(False)
        try:
            self.shut_down = self.b.recv(1) == b''
        except BlockingIOError:
            self.shut_down = False
        except OSError:
            self.shut_down = True
        self.a.close()
        self.b.close()


class Connector:
    """연결 시도마다 준비된 가짜 연결을 하나씩 준다. 다 쓰면 계속 acked 하는 연결을 준다."""

    def __init__(self, conns):
        self.conns = list(conns)
        self.infos = []
        self.made = []

    def __call__(self, info):
        self.infos.append(info)
        conn = self.conns.pop(0) if self.conns else FakePgconn([])
        if isinstance(conn, Exception):
            raise conn
        self.made.append(conn)
        return conn


def run_writer(connector, until, timeout_s=ar.SOCKET_TIMEOUT_S, reconnect_delay_s=0.01, limit_s=10):
    """조건이 설 때까지 쓰기 스레드를 돌리고 기록을 돌려준다."""
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, 'ackrec.jsonl')
        journal = ar.Journal(path)
        stop = threading.Event()
        w = ar.Writer(1, 'host=x', journal, stop, FakePq, interval_s=0, connect=connector,
                      timeout_s=timeout_s, reconnect_delay_s=reconnect_delay_s)
        w.start()
        deadline = time.time() + limit_s
        records = []
        while time.time() < deadline:
            records = ar.load(path)
            if until(records):
                break
            time.sleep(0.01)
        stop.set()
        w.join(timeout=5)
        journal.close()
        records = ar.load(path)
        assert not w.is_alive()
        return records


def tried(records):
    return ar.attempts(records)


class OutcomeTest(unittest.TestCase):

    def test_acked_needs_ready_for_query_after_returning(self):
        conn = FakePgconn(['ack', 'ack_then_lost'])
        records = run_writer(Connector([conn]), lambda r: len(tried(r)) >= 2)
        first, second = tried(records)[:2]
        self.assertEqual(ar.ACKED, first['result'])
        self.assertEqual(ar.UNKNOWN, second['result'], 'RETURNING 뒤 ReadyForQuery 전에 끊기면 커밋 확인이 아니다')
        self.assertIn('connection_lost', second['reason'])
        self.assertEqual('10.159.1.11', first['server'])
        self.assertEqual(ar.number(1, 1), first['n'])
        self.assertLessEqual(first['t_start'], first['t_end'])

    def test_error_before_commit_is_failed_and_connection_ending_error_is_unknown(self):
        conns = [FakePgconn([('error', 'ERROR', '25006', 'cannot execute INSERT in a read-only transaction')]),
                 FakePgconn([('fatal_then_close', '57P01', 'terminating connection due to administrator command')]),
                 FakePgconn(['lost'])]
        records = run_writer(Connector(conns), lambda r: len(tried(r)) >= 3)
        results = [(r['result'], r['reason'][:12]) for r in tried(records)[:3]]
        self.assertEqual(ar.FAILED, results[0][0], 'Standby 로 간 INSERT 는 커밋 전 거절이다')
        self.assertEqual(ar.UNKNOWN, results[1][0], 'FATAL 은 로컬 커밋 뒤에도 온다')
        self.assertIn('57P01', tried(records)[1]['reason'])
        self.assertEqual(ar.UNKNOWN, results[2][0], '보낸 뒤 끊기면 결과를 모른다')

    def test_send_failure_is_failed_and_writer_reconnects_without_error_cap(self):
        conns = [FakePgconn(['send_fail']) for _ in range(60)]
        connector = Connector(conns)
        records = run_writer(connector, lambda r: len(tried(r)) >= 61)
        self.assertTrue(all(r['result'] == ar.FAILED for r in tried(records)[:60]))
        self.assertEqual(ar.ACKED, tried(records)[60]['result'], '오류가 50건을 넘어도 멈추지 않는다')
        self.assertGreaterEqual(len(connector.infos), 61, '아닌 결과 뒤에는 새로 붙는다')

    def test_connect_failures_are_recorded_and_retried(self):
        connector = Connector([ConnectionError('could not connect to server'),
                               FakePgconn([], status=FakePq.ConnStatus.BAD), FakePgconn([])])
        records = run_writer(connector, lambda r: len(tried(r)) >= 1)
        connects = [r for r in records if r['type'] == 'connect']
        self.assertEqual([False, False, True], [c['ok'] for c in connects[:3]])
        self.assertEqual(ar.ACKED, tried(records)[0]['result'])

    def test_socket_timeout_closes_only_the_socket(self):
        conn = FakePgconn(['hang'])
        records = run_writer(Connector([conn]), lambda r: len(tried(r)) >= 1, timeout_s=0.3)
        first = tried(records)[0]
        self.assertEqual(ar.UNKNOWN, first['result'])
        self.assertEqual('socket_timeout', first['reason'])
        self.assertGreaterEqual(first['t_end'] - first['t_start'], 0.25)
        self.assertTrue(conn.finished)
        self.assertTrue(conn.shut_down, '소켓을 닫아 서버 쪽이 EOF 를 본다. 취소 요청은 보내지 않는다(FakePgconn 에 없다)')

    def test_stop_while_waiting_marks_the_attempt_unknown(self):
        conn = FakePgconn(['hang'])
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'ackrec.jsonl')
            journal = ar.Journal(path)
            stop = threading.Event()
            w = ar.Writer(1, '', journal, stop, FakePq, interval_s=0, connect=Connector([conn]))
            w.start()
            deadline = time.time() + 5
            while w.snapshot()[0] != 'waiting' and time.time() < deadline:
                time.sleep(0.01)
            stop.set()
            w.join(timeout=5)
            journal.close()
            records = ar.load(path)
        self.assertFalse(w.is_alive())
        self.assertEqual('stopped_in_flight', tried(records)[0]['reason'])
        self.assertEqual(ar.UNKNOWN, tried(records)[0]['result'])


class ConnInfoTest(unittest.TestCase):

    def test_keepalive_and_no_statement_timeout(self):
        info = ar.conninfo('host=patroni-haproxy-b port=5000 dbname=infra_probe', 3)
        for part in ('keepalives=1', 'keepalives_idle=5', 'keepalives_interval=2', 'keepalives_count=3',
                     "options='-c statement_timeout=0'", 'application_name=exchange-ackrec-3'):
            self.assertIn(part, info)
        self.assertNotIn('tcp_user_timeout', info)

    def test_numbers_do_not_collide_between_writers(self):
        self.assertEqual(2, ar.writer_of(ar.number(2, 999)))
        self.assertNotEqual(ar.number(1, 5), ar.number(2, 5))

    def test_iso_is_utc(self):
        self.assertEqual('2026-09-25T00:00:00.500000+00:00', ar.iso(1790294400.5))

    def test_classify_error(self):
        self.assertEqual(ar.FAILED, ar.classify_error('ERROR', '23505'))
        self.assertEqual(ar.UNKNOWN, ar.classify_error('FATAL', '08006'))
        self.assertEqual(ar.UNKNOWN, ar.classify_error('ERROR', '57P03'))


def attempt(writer, seq, result, t_start, t_end, server='10.159.1.11'):
    return {'type': 'attempt', 'writer': writer, 'n': ar.number(writer, seq), 'result': result,
            't_start': t_start, 't_end': t_end, 'server': server, 'reason': '' if result == ar.ACKED else 'x'}


class VerifyTest(unittest.TestCase):

    def test_missing_acked_numbers_fail_and_unknown_does_not(self):
        records = [attempt(1, 1, ar.ACKED, 1, 1.1), attempt(1, 2, ar.ACKED, 2, 2.1),
                   attempt(2, 1, ar.UNKNOWN, 3, 4), attempt(2, 2, ar.FAILED, 5, 5.1)]
        ok = ar.verify_against(records, [ar.number(1, 1), ar.number(1, 2)])
        self.assertTrue(ok['pass'])
        self.assertEqual(0, ok['unknown_present'])
        bad = ar.verify_against(records, [ar.number(1, 1), ar.number(2, 1), ar.number(2, 2)])
        self.assertFalse(bad['pass'])
        self.assertEqual([ar.number(1, 2)], bad['missing'])
        self.assertEqual(1, bad['unknown_present'], 'unknown 은 있어도 위반이 아니다')
        self.assertEqual([ar.number(2, 2)], bad['failed_present'], 'failed 인데 있으면 분류 결함으로 적는다')

    def test_window_counts_alive_and_new_primary_attempts(self):
        records = [attempt(1, 1, ar.ACKED, 10, 10.5, 'old'), attempt(1, 2, ar.UNKNOWN, 11, 40, 'old'),
                   attempt(2, 1, ar.ACKED, 20, 55, 'new'), attempt(2, 2, ar.ACKED, 56, 56.1, 'new'),
                   {'type': 'connect', 'writer': 1, 't': 41, 'ok': True, 'server': 'new'},
                   {'type': 'alive', 'writer': 1, 't': 60, 'thread_alive': True},
                   {'type': 'alive', 'writer': 2, 't': 61, 'thread_alive': True},
                   {'type': 'alive', 'writer': 2, 't': 62, 'thread_alive': False}]
        self.assertEqual(2, ar.acked_between(records, 50, 60))
        self.assertEqual(1, ar.acked_between(records, 0, 50), '끝 시각으로 센다. 20초에 보낸 시도는 55초에 확인됐다')
        self.assertEqual({1: 60, 2: 61}, ar.last_alive(records))
        self.assertEqual({1: False, 2: True}, ar.sent_to_server_between(records, 'new', 15, 50))
        self.assertEqual({'unknown: x': 1}, ar.reasons(records))

    def test_overlap_and_interleaving_detect_two_primaries(self):
        clean = [attempt(1, 1, ar.ACKED, 1, 2, 'a'), attempt(1, 2, ar.ACKED, 3, 4, 'a'),
                 attempt(2, 1, ar.ACKED, 30, 31, 'b')]
        self.assertEqual([], ar.acked_overlap(clean)['overlaps'])
        self.assertEqual([], ar.interleaved_acks(clean)['returned_to'])
        split = clean + [attempt(2, 2, ar.ACKED, 3.5, 3.6, 'b')]
        self.assertEqual([['a', 'b']], [o['servers'] for o in ar.acked_overlap(split)['overlaps']])
        self.assertEqual(['a', 'b'], ar.interleaved_acks(split)['returned_to'])


class RunTest(unittest.TestCase):

    def test_run_writes_start_alive_and_stop_records(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'ackrec.jsonl')
            args = types.SimpleNamespace(out=path, writers=2, interval=0.01, dsn='', duration=1.5, stop_file=None,
                                         repl_sample=0)
            ar.run(args, pq=FakePq, connect=Connector([]))
            records = ar.load(path)
        self.assertEqual('start', records[0]['type'])
        self.assertEqual('stop', records[-1]['type'])
        self.assertEqual([], records[-1]['writers_alive'])
        alive = ar.last_alive(records)
        self.assertEqual({1, 2}, set(alive))
        self.assertTrue(all(records[-1]['t'] - t <= 2.0 for t in alive.values()), '마지막 살아 있음이 끝 2초 안')
        acked = [r for r in ar.attempts(records) if r['result'] == ar.ACKED]
        self.assertGreater(len(acked), 10)
        self.assertEqual(len(acked), len({r['n'] for r in acked}))

    def test_stop_file_ends_the_run(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'ackrec.jsonl')
            stop_file = os.path.join(d, 'stop')
            args = types.SimpleNamespace(out=path, writers=1, interval=0.01, dsn='', duration=30, stop_file=stop_file,
                                         repl_sample=0)
            timer = threading.Timer(0.5, lambda: open(stop_file, 'w').close())
            timer.start()
            started = time.time()
            ar.run(args, pq=FakePq, connect=Connector([]))
            self.assertLess(time.time() - started, 10)
            records = ar.load(path)
        self.assertEqual('stop', records[-1]['type'])


class ReplicationTest(unittest.TestCase):

    def test_sampler_records_walsenders_and_reconnects(self):
        rows = [('patroni-db-2', 'streaming', 'sync', 1790000100.25, 't'), ('pg_receivewal', 'streaming', 'async', 1790000101.0, 't')]
        conns = [ConnectionError('no primary'), FakePgconn([], server='10.159.1.9/32', repl_rows=rows)]
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'ackrec.jsonl')
            journal = ar.Journal(path)
            stop = threading.Event()
            sampler = ar.ReplicationSampler('', journal, stop, FakePq, every_s=0.02, connect=Connector(conns),
                                            reconnect_delay_s=0.01)
            sampler.start()
            deadline = time.time() + 5
            while time.time() < deadline and sum(1 for r in ar.load(path) if r.get('rows')) < 2:
                time.sleep(0.01)
            stop.set()
            sampler.join(timeout=5)
            journal.close()
            records = ar.load(path)
        self.assertIn('no primary', records[0]['error'])
        sample = next(r for r in records if r.get('rows'))
        self.assertEqual('10.159.1.9/32', sample['server'])
        self.assertEqual({'app': 'patroni-db-2', 'state': 'streaming', 'sync_state': 'sync', 'backend_start': 1790000100.25,
                          'flush': True}, sample['rows'][0])
        members = ('patroni-db-1', 'patroni-db-2')
        self.assertEqual(1790000100.25, ar.member_walsender_start(records, '10.159.1.9/32', members, 0))
        self.assertIsNone(ar.member_walsender_start(records, '10.159.1.8/32', members, 0), '다른 서버의 표본은 보지 않는다')
        self.assertEqual({'pg_receivewal'}, {n['app'] for n in ar.nonmember_walsenders(records, members)})


if __name__ == '__main__':
    unittest.main()
