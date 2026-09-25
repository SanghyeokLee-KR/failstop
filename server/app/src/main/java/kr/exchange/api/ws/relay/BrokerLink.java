package kr.exchange.api.ws.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompReactorNettyCodec;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.tcp.TcpConnection;
import org.springframework.messaging.tcp.TcpConnectionHandler;
import org.springframework.messaging.tcp.reactor.ReactorNettyTcpClient;
import org.springframework.util.MimeTypeUtils;
import reactor.netty.tcp.TcpClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 외부 STOMP 브로커와의 연결 하나(S6 설계 4-2, 4-4, 4-5). 노드마다 발행 연결과 구독 연결을 따로 둔다. 메모리 경보는 발행하는
 * 연결의 읽기를 막으므로, 한 연결로 발행과 확인을 함께 하면 경보 동안 구독까지 멈춘다.
 *
 * 끊기면 1초에서 두 배씩 10초까지 지터를 주어 다시 붙는다. 5초 넘게 정상이던 연결이 끊기면 다시 1초부터 센다.
 */
public final class BrokerLink implements RelayPublisher.Transport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrokerLink.class);

    public enum Role { PUBLISH, SUBSCRIBE }

    /** 연결 사건. 호출은 Reactor 스레드에서 오므로 기다리지 않고 돌아와야 한다. */
    public interface Listener {
        default void onConnected() {
        }

        /** @param ackId 누적 확인에 넘길 값(STOMP 1.2 ack 머리글) */
        default void onMessage(Map<String, String> headers, byte[] body, String ackId) {
        }

        default void onDisconnected() {
        }
    }

    private static final String SUBSCRIPTION_ID = "relay-sub";
    // CONNECT 를 보낸 뒤 CONNECTED 를 기다리는 상한. 멈춘(docker pause) 브로커는 TCP 연결은 받고 답하지 않는다.
    private static final long CONNECTED_TIMEOUT_MS = 10_000;
    // 이만큼 정상이던 연결이 끊기면 재연결 간격을 처음부터 센다.
    private static final long STABLE_MS = 5_000;
    private static final long WARN_INTERVAL_MS = 10_000;

    private final String name;
    private final Role role;
    private final WsRelayProperties props;
    private final Listener listener;
    private final ReactorNettyTcpClient<byte[]> client;
    private final ScheduledExecutorService scheduler;
    private final Handler handler = new Handler();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    private volatile boolean closed;
    // CONNECTED 를 받은 연결만 둔다. 그 전에는 connected() 가 거짓이다.
    private volatile TcpConnection<byte[]> connection;
    private volatile Channel channel;
    private volatile long connectedAt;
    private long backoffMs;
    private volatile long lastWarnAt;

    public BrokerLink(String name, Role role, WsRelayProperties props, Listener listener) {
        this.name = name;
        this.role = role;
        this.props = props;
        this.listener = listener;
        this.backoffMs = props.reconnect().min().toMillis();
        WsRelayProperties.Broker broker = props.broker();
        this.client = new ReactorNettyTcpClient<>(tcp -> {
            TcpClient c = tcp.host(broker.host()).port(broker.port())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                    .doOnConnected(conn -> channel = conn.channel());
            // 닫을 때 송신 버퍼를 버리고 RST 를 보낸다. 보통 닫기면 브로커가 경보가 풀린 뒤 옛 연결의 메시지를 늦게 전달한다.
            return role == Role.PUBLISH ? c.option(ChannelOption.SO_LINGER, 0) : c;
        }, new StompReactorNettyCodec());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-relay-" + name + "-link");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.execute(this::connect);
    }

    @Override
    public boolean connected() {
        return connection != null;
    }

    @Override
    public CompletableFuture<Void> send(RelayEnvelope envelope) {
        TcpConnection<byte[]> conn = connection;
        if (conn == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("발행 연결 없음"));
        }
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.SEND);
        a.setDestination(props.destination());
        a.setContentType(MimeTypeUtils.APPLICATION_JSON);
        envelope.headers(System.currentTimeMillis()).forEach(a::setNativeHeader);
        return conn.sendAsync(MessageBuilder.createMessage(envelope.body(), a.getMessageHeaders()));
    }

    @Override
    public void abort() {
        TcpConnection<byte[]> conn = connection;
        connection = null;
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        } else if (conn != null) {
            conn.close();
        }
    }

    /** 구독 연결의 누적 확인. 이 id 까지 받은 메시지를 모두 확인한다. */
    public void ack(String ackId) {
        TcpConnection<byte[]> conn = connection;
        if (conn == null || ackId == null) {
            return;
        }
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.ACK);
        a.setNativeHeader("id", ackId);
        conn.sendAsync(MessageBuilder.createMessage(new byte[0], a.getMessageHeaders()));
    }

    /** 이 연결의 예약 스레드. 구독 쪽 누적 확인 주기가 같이 쓴다. */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        closed = true;
        abort();
        scheduler.shutdownNow();
        try {
            client.shutdownAsync().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("중계 연결 종료 대기 실패: link={}", name, e);
        }
    }

    private void connect() {
        if (closed) {
            return;
        }
        reconnectScheduled.set(false);
        client.connectAsync(handler);
    }

    private void scheduleReconnect() {
        if (closed || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (connectedAt > 0 && now - connectedAt > STABLE_MS) {
            backoffMs = props.reconnect().min().toMillis();
        }
        connectedAt = 0;
        long delay = Math.round(backoffMs * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
        backoffMs = Math.min(backoffMs * 2, props.reconnect().max().toMillis());
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 닫는 중이다.
        }
    }

    private void warn(String message, Object... args) {
        long now = System.nanoTime() / 1_000_000;
        if (now - lastWarnAt >= WARN_INTERVAL_MS) {
            lastWarnAt = now;
            log.warn(message, args);
        }
    }

    private Message<byte[]> connectFrame() {
        WsRelayProperties.Broker broker = props.broker();
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.CONNECT);
        a.setAcceptVersion("1.2");
        a.setHost(broker.virtualHost());
        a.setLogin(broker.login());
        a.setPasscode(broker.passcode());
        long hb = broker.heartbeat().toMillis();
        a.setHeartbeat(hb, hb);
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private Message<byte[]> subscribeFrame() {
        WsRelayProperties.Subscription s = props.subscription();
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        a.setDestination(props.destination());
        a.setSubscriptionId(SUBSCRIPTION_ID);
        // 누적 확인과 선반입, 큐 인자를 함께 써야 멈춘 노드의 큐가 브로커에서 묶인다(증거 pml-s6-probe-20260925 R6b·R10b).
        a.setNativeHeader("ack", "client");
        a.setNativeHeader("prefetch-count", String.valueOf(s.prefetch()));
        a.setNativeHeader("x-max-length", String.valueOf(s.maxLength()));
        a.setNativeHeader("x-max-length-bytes", String.valueOf(s.maxLengthBytes().toBytes()));
        a.setNativeHeader("x-message-ttl", String.valueOf(s.ttl().toMillis()));
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private static Message<byte[]> heartbeatFrame() {
        return MessageBuilder.createMessage(new byte[0], StompHeaderAccessor.createForHeartbeat().getMessageHeaders());
    }

    private final class Handler implements TcpConnectionHandler<byte[]> {

        private volatile TcpConnection<byte[]> pending;

        @Override
        public void afterConnected(TcpConnection<byte[]> conn) {
            pending = conn;
            conn.sendAsync(connectFrame());
            try {
                scheduler.schedule(() -> {
                    if (pending == conn && connection != conn) {
                        warn("중계 브로커가 CONNECT 에 답하지 않는다. 다시 붙는다: link={}", name);
                        conn.close();
                    }
                }, CONNECTED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                conn.close();
            }
        }

        @Override
        public void afterConnectFailure(Throwable ex) {
            warn("중계 브로커 연결 실패: link={}, {}", name, ex.toString());
            scheduleReconnect();
        }

        @Override
        public void handleMessage(Message<byte[]> message) {
            StompHeaderAccessor a = StompHeaderAccessor.wrap(message);
            if (a.isHeartbeat()) {
                return;
            }
            StompCommand command = a.getCommand();
            if (command == StompCommand.CONNECTED) {
                onConnectedFrame(a);
            } else if (command == StompCommand.MESSAGE) {
                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : a.toNativeHeaderMap().entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        headers.put(e.getKey(), e.getValue().get(0));
                    }
                }
                String ackId = headers.containsKey("ack") ? headers.get("ack") : headers.get("message-id");
                listener.onMessage(headers, message.getPayload(), ackId);
            } else if (command == StompCommand.ERROR) {
                warn("중계 브로커 ERROR: link={}, message={}", name, a.getMessage());
                TcpConnection<byte[]> conn = pending;
                if (conn != null) {
                    conn.close();
                }
            }
        }

        private void onConnectedFrame(StompHeaderAccessor a) {
            TcpConnection<byte[]> conn = pending;
            if (conn == null) {
                return;
            }
            long[] server = a.getHeartbeat();
            long hb = props.broker().heartbeat().toMillis();
            if (hb > 0 && server[1] > 0) {
                long sendEvery = Math.max(hb, server[1]);
                conn.onWriteInactivity(() -> conn.sendAsync(heartbeatFrame()), sendEvery);
            }
            if (hb > 0 && server[0] > 0) {
                long readEvery = Math.max(hb, server[0]);
                // STOMP 의 관례대로 간격의 3배 동안 아무것도 오지 않으면 연결이 죽은 것으로 본다(브로커 쪽도 3배, R7).
                conn.onReadInactivity(() -> {
                    warn("중계 브로커 하트비트가 끊겼다. 다시 붙는다: link={}", name);
                    abort();
                }, readEvery * 3);
            }
            if (role == Role.SUBSCRIBE) {
                conn.sendAsync(subscribeFrame());
            }
            connectedAt = System.nanoTime() / 1_000_000;
            connection = conn;
            log.info("중계 브로커 연결: link={}, broker={}", name, props.broker());
            listener.onConnected();
        }

        /** 수신 흐름이 오류로 끝났다. RST 로 끊긴 연결은 닫힘 대신 이 경로로만 온다. */
        @Override
        public void handleFailure(Throwable ex) {
            warn("중계 연결 오류: link={}, {}", name, ex.toString());
            Channel ch = channel;
            if (ch != null) {
                ch.close();
            }
            linkClosed();
        }

        @Override
        public void afterConnectionClosed() {
            linkClosed();
        }

        /**
         * 연결이 닫혔다. 끊김을 알리는 것은 CONNECTED 까지 받은 연결(connection)이 닫혔을 때뿐이다. TCP 만 붙고 CONNECTED 전에 닫힌
         * 시도(pending)는 알리지 않는다. 알리면 구독 쪽이 끊긴 시각을 시도마다 새로 적어, 브로커 앞에서 TCP 를 받아 주는 장치(Docker
         * 포트 전달, TCP 로드밸런서)나 기동 중인 브로커를 두고 재연결 간격이 5초보다 짧을 때 link_down 이 끝내 켜지지 않는다(2026-09-25
         * S6 관문 주행 뒤 재연결 상한을 3초로 줄이자 RelayDegradedGateTest 가 드러냈다).
         */
        private void linkClosed() {
            boolean wasConnected;
            synchronized (this) {
                wasConnected = connection != null;
                connection = null;
                pending = null;
                channel = null;
            }
            if (wasConnected) {
                listener.onDisconnected();
            }
            scheduleReconnect();
        }
    }
}
