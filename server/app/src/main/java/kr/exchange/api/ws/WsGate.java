package kr.exchange.api.ws;

import kr.exchange.engine.LeaderTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.Lifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * WS 수용 판정(API 설계 §5-6, S6 설계 4-11). 노드는 모든 마켓의 게시를 세션에 줄 수 있을 때만 /ws/v1 을 받는다. 조건은 두 모드가 같다.
 *
 * 모든 파티션의 접수 게이트를 연 노드는 늘 받는다. simple 모드에서 쥐지 않은 파티션을 다른 holder 가 유효하게 쥐었으면 받지 않는다
 * (relay_required). 그 게시는 다른 노드의 내장 브로커로만 가기 때문이다. broker 모드에서 중계 불능이면 받지 않는다
 * (relay_unavailable). 엔진을 끈 노드는 파티션을 쥐지 못하므로 늘 받지 않는다.
 *
 * 받지 않는 노드는 핸드셰이크를 업그레이드 전에 503 과 Retry-After 로 거절한다. 업그레이드 뒤에 닫으면 인그레스에는 성공으로 보여
 * 다른 노드로 넘기지 않는다. 받지 않게 되는 순간 열린 세션을 1013 으로 닫는다. 조용히 멈춘 화면보다 끊김이 보이는 편이 안전하다.
 */
public final class WsGate implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WsGate.class);
    /** 소유 노드가 닿지 않는 상태의 유예는 리스 TTL 에 이만큼을 더한 값 이상이어야 한다. 죽은 소유 노드의 리스는 TTL 안에 만료된다. */
    static final Duration OWNER_SILENT_MARGIN = Duration.ofSeconds(3);
    static final String RETRY_AFTER_SECONDS = "5";

    public enum Reason {
        RELAY_REQUIRED("relay_required"), RELAY_UNAVAILABLE("relay_unavailable");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public enum Cause {
        LINK_DOWN("link_down"), OWNER_SILENT("owner_silent");

        private final String wire;

        Cause(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** leader_lease 행 하나. valid 는 DB 시각으로 판정한 lease_until > clock_timestamp() 다. */
    public record LeaseRow(String holder, long epoch, boolean valid) {
    }

    public record Decision(boolean open, Reason reason) {
        static final Decision OPEN = new Decision(true, null);
    }

    /** broker 모드 노드의 중계 상태. */
    public interface Relay {
        boolean subscribed();

        long disconnectedSince();

        long generationStartedAt();

        boolean reachable(String partition, long epoch);

        long lastReachableAt(String partition, long epoch);
    }

    private record FirstSeen(long epoch, long at) {
    }

    private final boolean engineEnabled;
    private final String holder;
    private final List<String> partitions;
    private final Function<String, LeaderTerm> openTerm;
    private final Supplier<Optional<Map<String, LeaseRow>>> leases;
    // simple 모드면 null 이다.
    private final Relay relay;
    private final long degradedAfterMs;
    private final long ownerSilentAfterMs;
    private final LongSupplier clockMillis;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong[] refused = {new AtomicLong(), new AtomicLong()};
    // 게이트 판정 스레드만 쓴다.
    private final Map<String, FirstSeen> firstSeen = new HashMap<>();
    private volatile Decision current;
    private volatile boolean linkDown;
    private volatile boolean ownerSilent;
    private ScheduledExecutorService scheduler;

    public WsGate(boolean engineEnabled, String holder, List<String> partitions, Function<String, LeaderTerm> openTerm,
                  Supplier<Optional<Map<String, LeaseRow>>> leases, Relay relay, Duration degradedAfter,
                  Duration ownerSilentAfter, Duration leaseTtl, LongSupplier clockMillis) {
        if (ownerSilentAfter.compareTo(leaseTtl.plus(OWNER_SILENT_MARGIN)) < 0) {
            throw new IllegalArgumentException("exchange.ws.relay.owner-silent-after(" + ownerSilentAfter
                    + ")는 리스 TTL + 3초(" + leaseTtl.plus(OWNER_SILENT_MARGIN) + ") 이상이어야 한다. 짧으면 리더 사망을"
                    + " 중계 불능으로 오판해 곧 승격할 노드가 세션을 닫는다");
        }
        this.engineEnabled = engineEnabled;
        this.holder = holder;
        this.partitions = List.copyOf(partitions);
        this.openTerm = openTerm;
        this.leases = leases;
        this.relay = relay;
        this.degradedAfterMs = degradedAfter.toMillis();
        this.ownerSilentAfterMs = ownerSilentAfter.toMillis();
        this.clockMillis = clockMillis;
        this.current = engineEnabled ? Decision.OPEN : new Decision(false, Reason.RELAY_REQUIRED);
    }

    /** 1초마다 판정한다. 엔진을 끈 노드는 늘 닫혀 있어 판정하지 않는다. */
    public synchronized void start() {
        if (!engineEnabled || scheduler != null) {
            return;
        }
        evaluate();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-gate");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                evaluate();
            } catch (RuntimeException e) {
                log.warn("WS 게이트 판정 실패(직전 판정 유지): {}", e.getMessage());
            }
        }, 1_000, 1_000, TimeUnit.MILLISECONDS);
    }

    /** 판정을 다시 하고, 받다가 받지 않게 된 순간에만 열린 세션을 닫는다. */
    public synchronized Decision evaluate() {
        Decision previous = current;
        Decision next = decide();
        current = next;
        if (previous.open() && !next.open()) {
            log.warn("WS 수용을 닫는다: reason={}, linkDown={}, ownerSilent={}, 열린 세션 {}",
                    next.reason().wire(), linkDown, ownerSilent, sessions.size());
            closeAll(next.reason());
        } else if (!previous.open() && next.open()) {
            log.info("WS 수용을 다시 연다");
        }
        return next;
    }

    private Decision decide() {
        if (!engineEnabled) {
            return new Decision(false, Reason.RELAY_REQUIRED);
        }
        boolean allOwned = true;
        for (String p : partitions) {
            if (openTerm.apply(p) == null) {
                allOwned = false;
                break;
            }
        }
        if (allOwned) {
            linkDown = false;
            ownerSilent = false;
            firstSeen.clear();
            return Decision.OPEN;
        }
        long now = clockMillis.getAsLong();
        Optional<Map<String, LeaseRow>> view = leases.get();
        if (relay == null) {
            if (view.isPresent()) {
                for (String p : partitions) {
                    if (openTerm.apply(p) == null && heldByOther(view.get().get(p))) {
                        return new Decision(false, Reason.RELAY_REQUIRED);
                    }
                }
            }
            return Decision.OPEN;
        }
        boolean down = !relay.subscribed() && now - relay.disconnectedSince() > degradedAfterMs;
        boolean silent = false;
        if (view.isPresent()) {
            for (String p : partitions) {
                LeaseRow row = view.get().get(p);
                if (openTerm.apply(p) != null || !heldByOther(row)) {
                    firstSeen.remove(p);
                    continue;
                }
                FirstSeen seen = firstSeen.get(p);
                if (seen == null || seen.epoch() != row.epoch()) {
                    seen = new FirstSeen(row.epoch(), now);
                    firstSeen.put(p, seen);
                }
                if (relay.reachable(p, row.epoch())) {
                    continue;
                }
                long since = Math.max(Math.max(relay.lastReachableAt(p, row.epoch()), relay.generationStartedAt()),
                        seen.at());
                if (now - since > ownerSilentAfterMs) {
                    silent = true;
                }
            }
        } else {
            firstSeen.clear();
        }
        linkDown = down;
        ownerSilent = silent;
        return down || silent ? new Decision(false, Reason.RELAY_UNAVAILABLE) : Decision.OPEN;
    }

    private boolean heldByOther(LeaseRow row) {
        return row != null && row.valid() && !holder.equals(row.holder());
    }

    private void closeAll(Reason reason) {
        for (WebSocketSession session : sessions.values()) {
            close(session, reason);
        }
    }

    private static void close(WebSocketSession session, Reason reason) {
        try {
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason(reason.wire()));
        } catch (IOException | RuntimeException e) {
            log.debug("WS 세션 닫기 실패: session={}, {}", session.getId(), e.getMessage());
        }
    }

    public Decision current() {
        return current;
    }

    public boolean open() {
        return current.open();
    }

    public boolean degraded(Cause cause) {
        return cause == Cause.LINK_DOWN ? linkDown : ownerSilent;
    }

    public long handshakeRefused(Reason reason) {
        return refused[reason.ordinal()].get();
    }

    int trackedSessions() {
        return sessions.size();
    }

    /** 게이트가 닫혀 있으면 업그레이드 전에 503 으로 거절하는 핸드셰이크 처리기. 오리진 검사(인터셉터) 뒤에 돈다. */
    public HandshakeHandler handshakeHandler() {
        return new GateHandshake(new DefaultHandshakeHandler());
    }

    private final class GateHandshake implements HandshakeHandler, Lifecycle {

        private final DefaultHandshakeHandler delegate;

        GateHandshake(DefaultHandshakeHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean doHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws HandshakeFailureException {
            Decision d = current;
            if (!d.open()) {
                refused[d.reason().ordinal()].incrementAndGet();
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                response.getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
                return false;
            }
            return delegate.doHandshake(request, response, wsHandler, attributes);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }
    }

    /**
     * 세션 기록 데코레이터. 닫혀 있는 동안 막 맺힌 세션도 바로 닫는다. 핸드셰이크와 판정 사이의 창에서 들어온 세션을 남기지 않는다.
     */
    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
                Decision d = current;
                if (!d.open()) {
                    close(session, d.reason());
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
