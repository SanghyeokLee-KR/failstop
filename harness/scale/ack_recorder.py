"""DB 수준 ACK 기록기(Patroni 설계 8-1, 검토 R8). 커밋을 확인받은 쓰기가 DB 전환 뒤에도 남는지 거래 스키마 밖에서 잰다.

failover_rpo_check.py 의 Writer 는 쓰지 않는다. 그 Writer 는 subprocess 시간 제한 예외를 잡지 않아 커밋이 10초 넘게 기다리면 스레드가
죽고, 오류 50건에서 기록을 멈춘다. 그래서 DB 가 없는 동안 기록기가 먼저 끝나 'ACK 유실 0' 이 기록 없이 통과했다. 이 기록기의 명세는
설계 8-1 의 일곱이다.

(1) 대상은 같은 클러스터의 실험 전용 DB infra_probe 의 표 ack_probe(n bigint primary key, writer int, t_client timestamptz) 이고
    patroni-haproxy-b 로 붙는다. 거래 스키마와 마이그레이션을 건드리지 않는다.
(2) 쓰기 스레드 넷이 각자 연결 하나로 한 트랜잭션에 한 행을 자동 커밋으로 넣는다. 시도마다 번호, 시작·끝 시각(호스트 벽시계 UTC,
    epoch 초), 결과를 적는다. acked 는 커밋까지 끝난 응답(RETURNING 과 ReadyForQuery)을 받은 것, failed 는 문장을 다 보내기 전의
    연결 실패나 커밋 전에 받은 오류 응답, unknown 은 문장을 보낸 뒤 연결이 끊기거나 소켓 제한에 걸린 것이다.
(3) 취소 요청(CancelRequest), statement_timeout, 드라이버의 질의 시간 제한을 쓰지 않는다. 취소는 복제되지 않은 커밋을 확인으로
    돌려준다(설계 2-6 G). 죽은 연결은 TCP keepalive(5·2·3)와 소켓 제한 90초로만 끊고, 끊을 때는 소켓만 닫는다.
(4) 오류 상한이 없다. 연결에 실패하면 0.5초 뒤 다시 붙고 주행 끝까지 돈다. 스레드마다 1초마다 살아 있음을 적는다.
(5) 판정은 주행 뒤 최종 Primary 에서 읽은 ack_probe 와 acked 기록을 대조해 빠진 번호 0 이다(verify). unknown 은 있든 없든 위반이
    아니다.
(6)·(7) 비공허 조건과 T_promote~T_sync 축은 patroni_axes.py 가 이 기록과 실행기의 시각으로 계산한다(summarize_window 등).

확장 프로토콜에서 INSERT 의 CommandComplete 는 Sync 의 커밋(과 동기 복제 대기)보다 먼저 온다. 그래서 acked 는 첫 결과가 아니라 그
뒤의 ReadyForQuery(get_result 가 None)까지 받은 것으로 센다.

돌리는 법(컨테이너 patroni-ackrec 안, DB 이미지의 /opt/patroni 가상 환경에 psycopg 3.3.6 이 있다):
  python ack_recorder.py init
  python ack_recorder.py run --out /out/ackrec.jsonl --stop-file /out/ackrec.stop
  python ack_recorder.py verify --log /out/ackrec.jsonl --out /out/ackrec-verify.json
연결 대상은 libpq 환경 변수(PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD)나 --dsn 으로 준다.

시험:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import argparse
import datetime
import json
import os
import select
import signal
import socket
import sys
import threading
import time

WRITERS = 4
# 설계 8-1 (3). keepalive 는 5초 쉰 뒤 2초 간격 3번. 소켓 제한은 동기 복제 대기가 풀리기를 기다리는 상한이다.
KEEPALIVE = (5, 2, 3)
SOCKET_TIMEOUT_S = 90.0
CONNECT_TIMEOUT_S = 5
RECONNECT_DELAY_S = 0.5
ALIVE_EVERY_S = 1.0
# 스레드마다 한 행을 넣고 쉬는 시간. 로그가 주행 길이에 비례해 커지지 않게 한다. 네 스레드면 초당 약 200행이다.
DEFAULT_INTERVAL_S = 0.02
# 번호 = writer * WRITER_SPAN + 순번. 스레드끼리 번호가 겹치지 않는다.
WRITER_SPAN = 10 ** 12
SETUP_TIMEOUT_S = 10.0
APPLICATION_NAME = 'exchange-ackrec'
# 복제 표본(repl) 간격. 새 Primary 에서 멤버의 walsender 가 언제 붙었는지(backend_start)를 잰다. '*' 아래에서는 멤버가 붙는
# 순간부터 커밋이 확인되고, Patroni /cluster 의 sync_standby 표시는 HA 루프(loop_wait) 만큼 늦다(2026-09-25 E-P1 첫 회차).
REPL_SAMPLE_S = 0.5
REPL_SQL = (b"SELECT coalesce(application_name, ''), coalesce(state, ''), coalesce(sync_state, ''), "
            b"extract(epoch FROM backend_start)::float8, (flush_lsn IS NOT NULL) FROM pg_stat_replication")

INSERT_SQL = b'INSERT INTO ack_probe (n, writer, t_client) VALUES ($1::bigint, $2::int, $3::timestamptz) RETURNING n'
TABLE_SQL = ('CREATE TABLE IF NOT EXISTS ack_probe ('
             'n bigint PRIMARY KEY, writer int NOT NULL, t_client timestamptz NOT NULL)')

ACKED, FAILED, UNKNOWN = 'acked', 'failed', 'unknown'
# 연결을 끊는 오류(FATAL·PANIC)나 운영자 개입(57P)은 커밋 뒤에도 올 수 있다. 확인받지 못했을 뿐 커밋됐을 수 있으므로 unknown 이다.
FATAL_SEVERITIES = ('FATAL', 'PANIC')
CONNECTION_ENDING_SQLSTATE_PREFIX = '57P'


def number(writer, seq):
    return writer * WRITER_SPAN + seq


def writer_of(n):
    return n // WRITER_SPAN


def iso(t):
    """epoch 초를 timestamptz 가 읽는 UTC 문자열로."""
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat()


def conninfo(dsn, writer):
    """keepalive·연결 제한·statement_timeout 0 을 붙인 libpq 연결 문자열. 드라이버의 질의 시간 제한은 쓰지 않는다."""
    idle, interval, count = KEEPALIVE
    extra = (f"keepalives=1 keepalives_idle={idle} keepalives_interval={interval} keepalives_count={count} "
             f"connect_timeout={CONNECT_TIMEOUT_S} application_name={APPLICATION_NAME}-{writer} "
             f"options='-c statement_timeout=0'")
    return (dsn + ' ' + extra).strip()


def classify_error(severity, sqlstate):
    """결과로 받은 오류 응답의 분류. ERROR 는 커밋 전 거절이라 failed, 연결을 끝내는 오류는 커밋 뒤일 수 있어 unknown 이다."""
    severity = (severity or '').upper()
    sqlstate = sqlstate or ''
    if severity in FATAL_SEVERITIES or sqlstate.startswith(CONNECTION_ENDING_SQLSTATE_PREFIX):
        return UNKNOWN
    return FAILED


class Journal:
    """JSONL 기록. 줄마다 비운다. 스레드가 여럿이라 잠금으로 줄을 섞지 않는다."""

    def __init__(self, path, clock=time.time):
        self._lock = threading.Lock()
        self._fh = open(path, 'a', encoding='utf-8', buffering=1)
        self._clock = clock

    def write(self, record):
        line = json.dumps(record, ensure_ascii=False, sort_keys=True)
        with self._lock:
            self._fh.write(line + '\n')

    def close(self):
        with self._lock:
            self._fh.close()


class SocketTimeout(Exception):
    pass


class Stopped(Exception):
    pass


class ConnectionLost(Exception):
    """결과를 끝까지 받기 전에 연결이 끊겼다. 그 전에 받은 결과(오류 응답 등)를 함께 든다."""

    def __init__(self, message, results=()):
        super().__init__(message)
        self.results = list(results)


def shutdown_socket(fd):
    """소켓만 닫는다. 취소 요청이나 종료 메시지를 보내지 않는다(설계 8-1 (3)). fd 는 libpq 가 계속 들고 있다가 finish 에서 닫는다."""
    try:
        s = socket.socket(fileno=fd)
    except OSError:
        return
    try:
        s.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    finally:
        s.detach()


class Session:
    """연결 하나. psycopg 의 저수준 pq 를 써서 보내기와 받기를 가르고, 받기에 소켓 제한을 건다."""

    def __init__(self, pgconn, pq, clock=time.monotonic, wait=select.select, timeout_s=SOCKET_TIMEOUT_S):
        self.pgconn = pgconn
        self.pq = pq
        self.clock = clock
        self.wait = wait
        self.timeout_s = timeout_s
        self.server = None

    def send(self, sql, params):
        """다 보내면 돌아온다(블로킹 모드). 실패는 문장이 서버에서 끝까지 실행될 수 없었다는 뜻이라 failed 다."""
        self.pgconn.send_query_params(sql, params)

    def receive(self, stop_event, timeout_s=None):
        """ReadyForQuery 까지 받아 결과 목록을 돌려준다. 끊기면 ConnectionLost, 제한을 넘으면 SocketTimeout, 멈추면 Stopped."""
        limit = self.timeout_s if timeout_s is None else timeout_s
        started = self.clock()
        results = []
        fd = self.pgconn.socket
        while True:
            while self.pgconn.is_busy():
                left = limit - (self.clock() - started)
                if left <= 0:
                    shutdown_socket(fd)
                    raise SocketTimeout()
                if stop_event.is_set():
                    shutdown_socket(fd)
                    raise Stopped()
                readable, _, _ = self.wait([fd], [], [], min(1.0, left))
                if readable:
                    try:
                        self.pgconn.consume_input()
                    except Exception as e:  # psycopg.OperationalError: 서버가 연결을 닫았다
                        raise ConnectionLost(str(e), results) from e
            res = self.pgconn.get_result()
            if res is None:
                return results
            results.append(res)

    def close(self):
        try:
            self.pgconn.finish()
        except Exception:
            pass


def open_session(dsn, writer, pq, connect=None, timeout_s=SOCKET_TIMEOUT_S):
    """연결하고 서버 주소를 읽는다. 실패하면 예외."""
    connect = connect or (lambda info: pq.PGconn.connect(info.encode()))
    pgconn = connect(conninfo(dsn, writer))
    if pgconn.status != pq.ConnStatus.OK:
        message = pgconn.error_message.decode(errors='replace').strip()
        try:
            pgconn.finish()
        except Exception:
            pass
        raise ConnectionError(message or 'connection failed')
    session = Session(pgconn, pq, timeout_s=timeout_s)
    session.send(b'SELECT inet_server_addr()::text', [])
    results = session.receive(threading.Event(), timeout_s=SETUP_TIMEOUT_S)
    if not results or results[0].status != pq.ExecStatus.TUPLES_OK:
        session.close()
        raise ConnectionError('server address query failed')
    value = results[0].get_value(0, 0)
    session.server = value.decode() if value is not None else 'local'
    return session


def outcome(results, pq, expected_n):
    """ReadyForQuery 까지 받은 결과 목록의 분류와 이유."""
    for res in results:
        if res.status == pq.ExecStatus.FATAL_ERROR:
            severity = res.error_field(pq.DiagnosticField.SEVERITY_NONLOCALIZED)
            sqlstate = res.error_field(pq.DiagnosticField.SQLSTATE)
            message = res.error_field(pq.DiagnosticField.MESSAGE_PRIMARY)
            sev = severity.decode() if severity else ''
            code = sqlstate.decode() if sqlstate else ''
            text = message.decode(errors='replace') if message else ''
            return classify_error(sev, code), f'{sev} {code} {text}'.strip()
    if len(results) == 1 and results[0].status == pq.ExecStatus.TUPLES_OK:
        value = results[0].get_value(0, 0)
        if value is not None and int(value) == expected_n:
            return ACKED, ''
        return UNKNOWN, 'unexpected returning value'
    return UNKNOWN, 'unexpected result sequence'


class Writer(threading.Thread):
    """쓰기 스레드 하나. 오류 상한 없이 멈출 때까지 돈다."""

    def __init__(self, index, dsn, journal, stop_event, pq, interval_s=DEFAULT_INTERVAL_S,
                 connect=None, wall=time.time, timeout_s=SOCKET_TIMEOUT_S, reconnect_delay_s=RECONNECT_DELAY_S):
        super().__init__(name=f'ackrec-writer-{index}', daemon=True)
        self.index = index
        self.dsn = dsn
        self.journal = journal
        self.stop_event = stop_event
        self.pq = pq
        self.interval_s = interval_s
        self.connect = connect
        self.wall = wall
        self.timeout_s = timeout_s
        self.reconnect_delay_s = reconnect_delay_s
        self.seq = 0
        self.state = 'starting'
        self.in_flight = None
        self.lock = threading.Lock()

    def _set(self, state, in_flight=None):
        with self.lock:
            self.state = state
            self.in_flight = in_flight

    def snapshot(self):
        with self.lock:
            return self.state, self.in_flight

    def run(self):
        while not self.stop_event.is_set():
            self._set('connecting')
            t = self.wall()
            try:
                session = open_session(self.dsn, self.index, self.pq, self.connect, self.timeout_s)
            except Exception as e:
                self.journal.write({'type': 'connect', 'writer': self.index, 't': round(t, 3), 'ok': False,
                                    'error': str(e)[:200]})
                self.stop_event.wait(self.reconnect_delay_s)
                continue
            self.journal.write({'type': 'connect', 'writer': self.index, 't': round(t, 3), 'ok': True,
                                'server': session.server})
            try:
                self._write_until_broken(session)
            finally:
                session.close()
            if not self.stop_event.is_set():
                self.stop_event.wait(self.reconnect_delay_s)
        self._set('stopped')

    def _write_until_broken(self, session):
        while not self.stop_event.is_set():
            self.seq += 1
            n = number(self.index, self.seq)
            t_start = self.wall()
            self._set('sending', {'n': n, 't_start': round(t_start, 3)})
            record = {'type': 'attempt', 'writer': self.index, 'n': n, 't_start': round(t_start, 3),
                      'server': session.server}
            keep = False
            try:
                session.send(INSERT_SQL, [str(n).encode(), str(self.index).encode(), iso(t_start).encode()])
            except Exception as e:
                record.update(result=FAILED, reason=('send: ' + str(e))[:200])
            else:
                self._set('waiting', {'n': n, 't_start': round(t_start, 3)})
                try:
                    results = session.receive(self.stop_event)
                except SocketTimeout:
                    record.update(result=UNKNOWN, reason='socket_timeout')
                except Stopped:
                    record.update(result=UNKNOWN, reason='stopped_in_flight')
                except ConnectionLost as e:
                    # 끊기기 전에 받은 오류 응답이 있으면 그것으로 가른다. ERROR 는 커밋 전 거절, FATAL 은 커밋 뒤일 수 있다.
                    errors = [r for r in e.results if r.status == self.pq.ExecStatus.FATAL_ERROR]
                    if errors:
                        result, reason = outcome(errors, self.pq, n)
                        record.update(result=result, reason=reason)
                    else:
                        record.update(result=UNKNOWN, reason=('connection_lost: ' + str(e))[:200])
                else:
                    result, reason = outcome(results, self.pq, n)
                    record.update(result=result, reason=reason)
                    keep = result == ACKED
            record['t_end'] = round(self.wall(), 3)
            self.journal.write(record)
            self._set('idle')
            if not keep:
                # 아닌 결과 뒤에는 새로 붙는다. 대기열의 옛 Primary 연결이나 Standby 로 간 연결을 그대로 쓰지 않는다.
                return
            if self.interval_s > 0:
                self.stop_event.wait(self.interval_s)


def heartbeat(writers, journal, stop_event, wall=time.time, every_s=ALIVE_EVERY_S):
    """스레드마다 1초마다 살아 있음을 적는다. 쓰기 스레드가 커밋 대기로 막혀 있어도 이 스레드가 그 상태를 적는다."""
    while not stop_event.is_set():
        t = round(wall(), 3)
        for w in writers:
            state, in_flight = w.snapshot()
            journal.write({'type': 'alive', 'writer': w.index, 't': t, 'thread_alive': w.is_alive(),
                           'state': state, 'in_flight': in_flight})
        stop_event.wait(every_s)


class ReplicationSampler(threading.Thread):
    """현재 Primary 의 pg_stat_replication 을 주기마다 적는다(type repl). 연결이 끊기면 0.5초 뒤 다시 붙는다(HAProxy 가 새
    Primary 로 보낸다). 읽기라 동기 복제 대기에 걸리지 않지만 받기에는 짧은 제한을 둔다."""

    def __init__(self, dsn, journal, stop_event, pq, every_s=REPL_SAMPLE_S, connect=None, wall=time.time,
                 reconnect_delay_s=RECONNECT_DELAY_S):
        super().__init__(name='ackrec-repl', daemon=True)
        self.dsn, self.journal, self.stop_event, self.pq = dsn, journal, stop_event, pq
        self.every_s, self.connect, self.wall, self.reconnect_delay_s = every_s, connect, wall, reconnect_delay_s

    def run(self):
        while not self.stop_event.is_set():
            try:
                session = open_session(self.dsn, 99, self.pq, self.connect, timeout_s=SETUP_TIMEOUT_S)
            except Exception as e:
                self.journal.write({'type': 'repl', 't': round(self.wall(), 3), 'server': None, 'error': str(e)[:120]})
                self.stop_event.wait(self.reconnect_delay_s)
                continue
            try:
                while not self.stop_event.is_set():
                    t = round(self.wall(), 3)
                    session.send(REPL_SQL, [])
                    results = session.receive(self.stop_event, timeout_s=SETUP_TIMEOUT_S)
                    if not results or results[0].status != self.pq.ExecStatus.TUPLES_OK:
                        raise ConnectionLost('replication query failed', results)
                    res = results[0]
                    rows = []
                    for i in range(res.ntuples):
                        start = res.get_value(i, 3)
                        rows.append({'app': res.get_value(i, 0).decode(), 'state': res.get_value(i, 1).decode(),
                                     'sync_state': res.get_value(i, 2).decode(),
                                     'backend_start': float(start) if start is not None else None,
                                     'flush': res.get_value(i, 4) == b't'})
                    self.journal.write({'type': 'repl', 't': t, 'server': session.server, 'rows': rows})
                    self.stop_event.wait(self.every_s)
            except (SocketTimeout, Stopped, ConnectionLost, Exception) as e:
                if not self.stop_event.is_set():
                    self.journal.write({'type': 'repl', 't': round(self.wall(), 3), 'server': session.server,
                                        'error': (type(e).__name__ + ': ' + str(e))[:120]})
            finally:
                session.close()
            self.stop_event.wait(self.reconnect_delay_s)


def member_walsender_start(records, server, members, t_from):
    """t_from 뒤 그 서버(새 Primary)에서 멤버의 walsender 가 처음 보인 표본의 backend_start. 없으면 None."""
    starts = []
    for r in records:
        if r.get('type') != 'repl' or r.get('server') != server or r.get('t', 0) < t_from:
            continue
        for row in r.get('rows') or []:
            if row.get('app') in members and row.get('backend_start') is not None:
                starts.append(row['backend_start'])
    return min(starts) if starts else None


def nonmember_walsenders(records, members, t0=None, t1=None):
    """멤버가 아니면서 flush 를 보고한 walsender 표본(설계 3-2). '*' 아래에서는 한 사본만으로 커밋을 확인해 줄 수 있다."""
    seen = []
    for r in records:
        if r.get('type') != 'repl':
            continue
        if t0 is not None and r.get('t', 0) < t0:
            continue
        if t1 is not None and r.get('t', 0) > t1:
            continue
        for row in r.get('rows') or []:
            if row.get('app') not in members and row.get('flush'):
                seen.append({'t': r['t'], 'server': r.get('server'), 'app': row.get('app')})
    return seen


def run(args, pq=None, connect=None):
    pq = pq or _pq()
    journal = Journal(args.out)
    stop_event = threading.Event()

    def on_signal(signum, frame):
        stop_event.set()

    previous = {}
    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            previous[sig] = signal.signal(sig, on_signal)
        except ValueError:
            pass
    started = time.time()
    journal.write({'type': 'start', 't': round(started, 3), 'writers': args.writers,
                   'socket_timeout_s': getattr(args, 'socket_timeout', SOCKET_TIMEOUT_S), 'keepalive': list(KEEPALIVE),
                   'interval_s': args.interval, 'writer_span': WRITER_SPAN})
    timeout_s = getattr(args, 'socket_timeout', SOCKET_TIMEOUT_S)
    writers = [Writer(i, args.dsn, journal, stop_event, pq, args.interval, connect, timeout_s=timeout_s)
               for i in range(1, args.writers + 1)]
    for w in writers:
        w.start()
    beat = threading.Thread(target=heartbeat, args=(writers, journal, stop_event), name='ackrec-alive', daemon=True)
    beat.start()
    repl = None
    repl_every = getattr(args, 'repl_sample', REPL_SAMPLE_S)
    if repl_every and repl_every > 0:
        repl = ReplicationSampler(args.dsn, journal, stop_event, pq, repl_every, connect)
        repl.start()
    deadline = started + args.duration if args.duration else None
    while not stop_event.is_set():
        if args.stop_file and os.path.exists(args.stop_file):
            break
        if deadline and time.time() >= deadline:
            break
        stop_event.wait(0.2)
    stop_event.set()
    for w in writers:
        w.join(timeout=5)
    beat.join(timeout=2)
    if repl:
        repl.join(timeout=5)
    journal.write({'type': 'stop', 't': round(time.time(), 3),
                   'writers_alive': [w.index for w in writers if w.is_alive()]})
    journal.close()
    for sig, handler in previous.items():
        signal.signal(sig, handler)
    return 0


def load(path):
    records = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def attempts(records):
    return [r for r in records if r.get('type') == 'attempt']


def verify_against(records, present):
    """(5) acked 번호가 최종 Primary 의 ack_probe 에 모두 있는가. failed 인데 있는 번호는 분류 결함이라 따로 적는다."""
    present = set(present)
    tried = attempts(records)
    acked = [r['n'] for r in tried if r['result'] == ACKED]
    failed = [r['n'] for r in tried if r['result'] == FAILED]
    unknown = [r['n'] for r in tried if r['result'] == UNKNOWN]
    tried_numbers = {r['n'] for r in tried}
    missing = sorted(n for n in acked if n not in present)
    failed_present = sorted(n for n in failed if n in present)
    return {
        'acked': len(acked),
        'failed': len(failed),
        'unknown': len(unknown),
        'present': len(present),
        'missing_count': len(missing),
        'missing': missing[:100],
        'unknown_present': sum(1 for n in unknown if n in present),
        'failed_present_count': len(failed_present),
        'failed_present': failed_present[:100],
        'never_attempted_present': sorted(n for n in present if n not in tried_numbers)[:100],
        'pass': len(missing) == 0,
    }


def reasons(records, t0=None, t1=None):
    """acked 가 아닌 시도의 결과·이유별 수. 이유 문장의 앞 40자로 묶는다."""
    counts = {}
    for r in attempts(records):
        if r['result'] == ACKED:
            continue
        if t0 is not None and r['t_end'] < t0:
            continue
        if t1 is not None and r['t_start'] > t1:
            continue
        key = f"{r['result']}: {(r.get('reason') or '')[:40]}"
        counts[key] = counts.get(key, 0) + 1
    return counts


def acked_between(records, t0, t1):
    """끝 시각이 [t0, t1] 에 든 acked 시도 수."""
    return sum(1 for r in attempts(records) if r['result'] == ACKED and t0 <= r['t_end'] <= t1)


def last_alive(records):
    """스레드마다 마지막 살아 있음 기록의 시각."""
    last = {}
    for r in records:
        if r.get('type') == 'alive' and r.get('thread_alive'):
            last[r['writer']] = max(last.get(r['writer'], 0), r['t'])
    return last


def sent_to_server_between(records, server, t0, t1):
    """스레드마다 [t0, t1] 에 그 서버로 보낸 시도가 있었는지(설계 8-1 (7))."""
    writers = sorted({r['writer'] for r in records if r.get('type') == 'connect'} |
                     {r['writer'] for r in attempts(records)})
    seen = {w: False for w in writers}
    for r in attempts(records):
        if r.get('server') == server and t0 <= r['t_start'] <= t1:
            seen[r['writer']] = True
    return seen


def acked_overlap(records):
    """서버마다 acked 끝 시각의 구간. 두 서버의 구간이 겹치면 두 노드가 같은 시간대에 커밋을 확인했다(E-P3a·b)."""
    spans = {}
    for r in attempts(records):
        if r['result'] != ACKED:
            continue
        lo, hi = spans.get(r['server'], (r['t_end'], r['t_end']))
        spans[r['server']] = (min(lo, r['t_end']), max(hi, r['t_end']))
    overlaps = []
    servers = sorted(spans)
    for i, a in enumerate(servers):
        for b in servers[i + 1:]:
            lo = max(spans[a][0], spans[b][0])
            hi = min(spans[a][1], spans[b][1])
            if lo <= hi:
                overlaps.append({'servers': [a, b], 'from': lo, 'to': hi})
    return {'spans': {k: list(v) for k, v in spans.items()}, 'overlaps': overlaps}


def interleaved_acks(records):
    """acked 를 끝 시각 순으로 늘어놓았을 때 서버가 A→B→A 로 되돌아간 곳. 겹침보다 엄격한 두 Primary 신호다."""
    seq = sorted((r['t_end'], r['server']) for r in attempts(records) if r['result'] == ACKED)
    order = []
    for _, server in seq:
        if not order or order[-1] != server:
            order.append(server)
    returns = [s for i, s in enumerate(order) if s in order[:i]]
    return {'server_order': order, 'returned_to': returns}


def _pq():
    from psycopg import pq  # 컨테이너 가상 환경에만 있다. 시험은 가짜 pq 를 넘긴다.
    return pq


def init(args, pq=None):
    pq = pq or _pq()
    session = open_session(args.dsn, 0, pq)
    try:
        for sql in (TABLE_SQL.encode(), b'TRUNCATE ack_probe'):
            session.send(sql, [])
            results = session.receive(threading.Event(), timeout_s=SETUP_TIMEOUT_S)
            bad = [r for r in results if r.status == pq.ExecStatus.FATAL_ERROR]
            if bad:
                raise RuntimeError(bad[0].error_message.decode(errors='replace'))
    finally:
        session.close()
    print(json.dumps({'init': 'ok', 'server': session.server}))
    return 0


def verify(args, pq=None):
    pq = pq or _pq()
    records = load(args.log)
    session = open_session(args.dsn, 0, pq)
    try:
        session.send(b'SELECT n FROM ack_probe', [])
        results = session.receive(threading.Event(), timeout_s=120)
    finally:
        session.close()
    if not results or results[0].status != pq.ExecStatus.TUPLES_OK:
        raise RuntimeError('ack_probe read failed')
    res = results[0]
    present = [int(res.get_value(i, 0)) for i in range(res.ntuples)]
    report = verify_against(records, present)
    report['server'] = session.server
    report['overlap'] = acked_overlap(records)
    report['interleaved'] = interleaved_acks(records)
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True)
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as fh:
            fh.write(text + '\n')
    print(text)
    return 0 if report['pass'] else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description='DB 수준 ACK 기록기(Patroni 설계 8-1)')
    sub = parser.add_subparsers(dest='cmd', required=True)
    for name in ('init', 'run', 'verify'):
        p = sub.add_parser(name)
        p.add_argument('--dsn', default='', help='libpq 연결 문자열. 비우면 PG* 환경 변수를 쓴다')
        if name == 'run':
            p.add_argument('--out', required=True)
            p.add_argument('--stop-file')
            p.add_argument('--duration', type=float, default=0, help='초. 0 이면 멈춤 파일이나 신호까지')
            p.add_argument('--writers', type=int, default=WRITERS)
            p.add_argument('--interval', type=float, default=DEFAULT_INTERVAL_S)
            # 실험은 기본값(90초, 설계 8-1 (3))을 쓴다. 짧게 주는 것은 기록기 자체를 확인할 때뿐이다.
            p.add_argument('--socket-timeout', type=float, default=SOCKET_TIMEOUT_S)
            p.add_argument('--repl-sample', type=float, default=REPL_SAMPLE_S, help='복제 표본 간격(초). 0 이면 끈다')
        if name == 'verify':
            p.add_argument('--log', required=True)
            p.add_argument('--out')
    args = parser.parse_args(argv)
    return {'init': init, 'run': run, 'verify': verify}[args.cmd](args)


if __name__ == '__main__':
    sys.exit(main())
