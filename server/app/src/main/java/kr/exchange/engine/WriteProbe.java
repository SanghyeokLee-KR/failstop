package kr.exchange.engine;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 쓰기 가능 탐지의 두 연결(DB 설계 §5.4-f 4, Patroni 설계 4-3). 풀을 쓰지 않는 전용 연결로 로컬 HAProxy 에 붙는다. 풀 연결을
 * 쓰면 커밋 대기에 걸린 탐지가 풀을 한 칸 막고, 풀이 막혔을 때 탐지도 함께 굶는다.
 *
 * 탐지 연결은 WAL 을 쓰는 트랜잭션 하나(pg_logical_emit_message)를 1초마다, 앞 탐지가 끝났을 때만 보낸다. 그래서 커밋 대기에
 * 걸린 탐지 백엔드는 노드마다 하나다. 확인 연결은 설정을 읽고, 탐지가 늦으면 탐지 백엔드의 대기 사건을 읽는다. 둘 다 풀과 같은
 * 로그인 역할이라 추가 권한 없이 pg_stat_activity 에서 자기 백엔드를 보고 끝낼 수 있다.
 */
public final class WriteProbe implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WriteProbe.class);

    /** 탐지 연결의 application_name. 수집기의 커밋 대기 수(pg_syncrep_waiters)가 이 이름을 뺀다. */
    public static final String PROBE_APPLICATION = "exchange-write-probe";
    static final String CHECK_APPLICATION = "exchange-write-check";

    /** 새 연결을 연다. 운영은 JDBC 주소로 여는 driverManager 이고 시험이 바꿀 수 있다. */
    @FunctionalInterface
    public interface Connector {
        Connection open(String applicationName, Duration socketTimeout) throws SQLException;
    }

    /** 탐지 백엔드. PID 는 재사용되므로 시작 시각과 짝으로 알아본다. */
    record Backend(int pid, OffsetDateTime start) {
    }

    private final WriteAvailability availability;
    private final Connector connector;
    private final Duration interval;
    private final Duration confirmInterval;
    private final Duration probeSocketTimeout;
    private final Duration checkSocketTimeout;
    private final Timer probeTimer;
    private final AtomicLong probeSequence = new AtomicLong();
    private volatile boolean running;
    private volatile Backend probeBackend;
    private volatile Connection probeConnection;
    private volatile Connection checkConnection;
    private Thread probeThread;
    private Thread checkThread;

    public WriteProbe(WriteAvailability availability, Connector connector, Duration interval,
                      Duration confirmInterval, Duration probeSocketTimeout, Duration checkSocketTimeout,
                      Timer probeTimer) {
        if (availability == null || connector == null || interval == null || confirmInterval == null
                || probeSocketTimeout == null || checkSocketTimeout == null) {
            throw new IllegalArgumentException("availability·connector·주기·소켓 제한은 필수");
        }
        this.availability = availability;
        this.connector = connector;
        this.interval = interval;
        this.confirmInterval = confirmInterval;
        this.probeSocketTimeout = probeSocketTimeout;
        this.checkSocketTimeout = checkSocketTimeout;
        this.probeTimer = probeTimer;
    }

    /**
     * JDBC 주소로 연결을 여는 기본 연결기. 소켓 제한은 초 단위(pgjdbc socketTimeout)라 1초보다 짧게 줄 수 없다. 주소의 TLS 인자
     * 등은 풀과 같게 따른다.
     */
    public static Connector driverManager(String url, String user, String password, Duration connectTimeout) {
        return (applicationName, socketTimeout) -> {
            Properties props = new Properties();
            if (user != null) {
                props.setProperty("user", user);
            }
            if (password != null) {
                props.setProperty("password", password);
            }
            props.setProperty("ApplicationName", applicationName);
            props.setProperty("socketTimeout", String.valueOf(Math.max(1, socketTimeout.toSeconds())));
            props.setProperty("connectTimeout", String.valueOf(Math.max(1, connectTimeout.toSeconds())));
            props.setProperty("tcpKeepAlive", "true");
            return DriverManager.getConnection(url, props);
        };
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        probeThread = daemon(this::probeLoop, "write-probe");
        checkThread = daemon(this::checkLoop, "write-probe-check");
        log.info("쓰기 가능 탐지 시작: 주기 {}, 확인 간격 {}, 소켓 제한 탐지 {}·확인 {}, 임계 {}",
                interval, confirmInterval, probeSocketTimeout, checkSocketTimeout, availability.settings());
    }

    private static Thread daemon(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void probeLoop() {
        Connection conn = null;
        // 소켓 제한으로 버린 연결의 백엔드. 서버에서는 아직 커밋을 기다릴 수 있어 다시 붙을 때 먼저 끝낸다.
        Backend abandoned = null;
        // 연달아 실패한 수. DB 가 없는 동안 1초마다 같은 경고를 남기지 않게 첫 실패만 WARN 으로 적는다.
        int failures = 0;
        long next = System.nanoTime();
        while (running) {
            if (!sleepUntil(next)) {
                break;
            }
            next = Math.max(next + interval.toNanos(), System.nanoTime());
            long id = probeSequence.incrementAndGet();
            try {
                if (conn == null) {
                    conn = openProbe(abandoned);
                    abandoned = null;
                }
                availability.probeSent(id);
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT pg_logical_emit_message(true, '" + PROBE_APPLICATION + "', '')");
                }
                // 커밋이 동기 대기를 탄다. 동기 standby 가 없으면 여기서 기다린다. 취소 요청은 쓰지 않는다(DB 설계 §5.4-f 3).
                conn.commit();
                Duration took = availability.probeCompleted(id);
                if (probeTimer != null) {
                    probeTimer.record(took);
                }
                failures = 0;
            } catch (SQLException e) {
                if (!running) {
                    break;
                }
                availability.probeFailed(id);
                if (conn != null && socketTimedOut(e)) {
                    abandoned = probeBackend;
                }
                if (failures++ == 0) {
                    log.warn("쓰기 가능 탐지 실패(probe={}): {}", id, e.toString());
                } else {
                    log.debug("쓰기 가능 탐지 실패 {}회째(probe={}): {}", failures, id, e.toString());
                }
                closeQuietly(conn);
                conn = null;
                probeConnection = null;
                probeBackend = null;
            }
        }
        closeQuietly(conn);
    }

    private Connection openProbe(Backend abandoned) throws SQLException {
        Connection conn = connector.open(PROBE_APPLICATION, probeSocketTimeout);
        try {
            if (abandoned != null) {
                terminate(conn, abandoned);
            }
            try (Statement st = conn.createStatement()) {
                st.execute("SET synchronous_commit = on");
            }
            Backend backend = currentBackend(conn);
            conn.setAutoCommit(false);
            probeConnection = conn;
            probeBackend = backend;
            availability.probeConnected();
            return conn;
        } catch (SQLException e) {
            closeQuietly(conn);
            throw e;
        }
    }

    private void checkLoop() {
        Connection conn = null;
        int failures = 0;
        while (running) {
            if (!sleepUntil(System.nanoTime() + interval.toNanos())) {
                break;
            }
            try {
                if (conn == null) {
                    conn = connector.open(CHECK_APPLICATION, checkSocketTimeout);
                    checkConnection = conn;
                }
                readSetting(conn);
                long overdue = availability.overdueProbe();
                Backend backend = probeBackend;
                if (overdue >= 0 && backend != null) {
                    String first = waitEvent(conn, backend);
                    if (!sleepUntil(System.nanoTime() + confirmInterval.toNanos())) {
                        break;
                    }
                    String second = waitEvent(conn, backend);
                    availability.waitEventsRead(overdue, first, second);
                }
                failures = 0;
            } catch (SQLException e) {
                if (!running) {
                    break;
                }
                availability.checkFailed();
                if (failures++ == 0) {
                    log.warn("쓰기 가능 확인 연결 실패: {}", e.toString());
                } else {
                    log.debug("쓰기 가능 확인 연결 실패 {}회째: {}", failures, e.toString());
                }
                closeQuietly(conn);
                conn = null;
                checkConnection = null;
            }
        }
        closeQuietly(conn);
    }

    private void readSetting(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT current_setting('synchronous_standby_names'), pg_is_in_recovery()")) {
            rs.next();
            availability.settingRead(rs.getString(1), rs.getBoolean(2));
        }
    }

    private static String waitEvent(Connection conn, Backend backend) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT wait_event FROM pg_stat_activity WHERE pid = ? AND backend_start = ?")) {
            ps.setInt(1, backend.pid());
            ps.setObject(2, backend.start());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Backend currentBackend(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT pid, backend_start FROM pg_stat_activity WHERE pid = pg_backend_pid()")) {
            rs.next();
            return new Backend(rs.getInt(1), rs.getObject(2, OffsetDateTime.class));
        }
    }

    /**
     * 버린 탐지 백엔드가 아직 커밋을 기다리면 끝낸다. 업무 데이터가 없는 트랜잭션이라 커밋 대기를 끊지 않는 규칙(DB 설계 §5.4-f 3)의
     * 유일한 예외다. 같은 로그인 역할의 백엔드라 추가 권한이 필요 없다.
     */
    private static void terminate(Connection conn, Backend backend) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                WHERE pid = ? AND backend_start = ? AND application_name = ? AND wait_event = 'SyncRep'
                """)) {
            ps.setInt(1, backend.pid());
            ps.setObject(2, backend.start());
            ps.setString(3, PROBE_APPLICATION);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    log.warn("커밋을 기다리던 이전 탐지 백엔드를 끝냈다: pid={}", backend.pid());
                }
            }
        }
    }

    private static boolean socketTimedOut(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private boolean sleepUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return running;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(remaining);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 멈춘다. 커밋을 기다리는 탐지는 소켓을 닫아 풀고(abort 는 다른 스레드에서 불러도 된다), 서버에 남는 그 백엔드는 짧은 연결로
     * 끝낸다. 남겨 두면 재기동마다 커밋을 기다리는 백엔드가 하나씩 쌓인다.
     */
    @Override
    public void close() {
        Thread probe;
        Thread check;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            probe = probeThread;
            check = checkThread;
        }
        Backend outstanding = availability.outstandingSeconds() > 0 ? probeBackend : null;
        abortQuietly(probeConnection);
        abortQuietly(checkConnection);
        probe.interrupt();
        check.interrupt();
        joinQuietly(probe);
        joinQuietly(check);
        if (outstanding != null) {
            try (Connection conn = connector.open(CHECK_APPLICATION, checkSocketTimeout)) {
                terminate(conn, outstanding);
            } catch (SQLException e) {
                log.warn("종료 중 탐지 백엔드를 정리하지 못했다: pid={}, {}", outstanding.pid(), e.toString());
            }
        }
    }

    private static void abortQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.abort(Runnable::run);
        } catch (SQLException | RuntimeException ignored) {
            // 이미 닫혔다. 종료 경로라 더 할 일이 없다.
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 끊긴 연결을 버리는 중이다. 다음 주기가 새로 연다.
        }
    }
}
