package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.api.ws.relay.RelaySubscriber;
import kr.exchange.engine.PartitionPlan;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 구독 즉시 스냅샷과 실시간 호가 사이의 결번 경합(API 설계 §5-2 '스냅샷을 읽는 시점', S6 설계 14절).
 *
 * 2026-09-25 S6 관문에서 다시 붙은 관찰자가 스냅샷 seq 831 다음 실시간으로 833 을 받았다. 스냅샷을 구독 사건에서 읽었는데 그
 * 사건은 SUBSCRIBE 를 수신 채널의 스레드 풀에 넘긴 직후 나오고, 내장 브로커의 구독 등록은 풀의 다른 스레드에서 끝난다. 이 시험은
 * Spring 의 실제 부품(스레드 풀 수신 채널, SimpleBrokerMessageHandler, SimpMessagingTemplate)으로 노드 하나의 STOMP 배선을 세우고,
 * 등록을 늦춰 그 창에 게시를 넣는다. 대조군(구독 사건 시점의 스냅샷, 중계 수신의 '전달 뒤 미러 갱신')은 결번 1 이상이고, 고친
 * 경로(등록 뒤 스냅샷, '미러 갱신 뒤 전달')는 0 이다. 결번은 계약 §5-2 의 클라이언트 규칙(스냅샷보다 먼저 온 실시간은 임시
 * 기준점, 기준점 이하는 폐기, 기준점 + 1 이 아니면 결번)으로 센다.
 */
class OrderbookSnapshotRaceTest {

    private static final String MARKET = "KRW-BTC";
    private static final String DEST = "/topic/orderbook." + MARKET;
    private static final long EPOCH = 7;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final ObjectMapper json = new ObjectMapper();

    // ---------------------------------------------------------------- 소유 노드(자기 카운터로 스냅샷)

    @Test
    void ownerSnapshotReadBeforeRegistrationMissesTheNextPublish() throws Exception {
        try (Node n = new Node()) {
            ChannelSequences seqs = new ChannelSequences();
            OrderbookSnapshotOnSubscribe snap = new OrderbookSnapshotOnSubscribe(ownerView(seqs), json, () -> n.outbound);
            publish(n, seqs);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            n.registry.gate(entered, release);

            Message<byte[]> sub = n.subscribe("s1");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            // 대조군: 종전 구독 사건 시점. SUBSCRIBE 를 수신 채널에 넘긴 직후라 등록 전이다.
            snap.sendSnapshot(sub);
            publish(n, seqs);
            release.countDown();
            n.awaitRegistered(1);
            publish(n, seqs);

            Client c = n.client("s1");
            assertEquals(List.of("snapshot:1", "realtime:3"), c.log(), "대조군이 경합을 재현하지 못했다");
            assertEquals(1, c.gaps(), "등록 전에 읽은 스냅샷 뒤의 결번");
        }
    }

    @Test
    void ownerSnapshotAfterRegistrationLeavesNoGap() throws Exception {
        try (Node n = new Node()) {
            ChannelSequences seqs = new ChannelSequences();
            n.inbound.addInterceptor(new OrderbookSnapshotOnSubscribe(ownerView(seqs), json, () -> n.outbound));
            publish(n, seqs);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            n.registry.gate(entered, release);

            n.subscribe("s1");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            // 대조군과 같은 창의 게시다. 스냅샷은 아직 나가지 않았다.
            publish(n, seqs);
            assertTrue(n.client("s1").log().isEmpty(), "등록 전에 스냅샷을 보냈다");
            release.countDown();
            Awaitility.await().atMost(WAIT).until(() -> n.client("s1").snapshots() == 1);
            publish(n, seqs);

            Client c = n.client("s1");
            assertEquals(List.of("snapshot:2", "realtime:3"), c.log());
            assertEquals(0, c.gaps());
        }
    }

    /**
     * 게시 스레드가 쉬지 않고 게시하는 동안 구독 200개를 차례로 연다. 등록마다 1ms 를 늦춰 바쁜 호스트의 창을 흉내 낸다(S5 첫
     * 주행 직전에는 다른 빌드와 Testcontainers 60여 개가 돌았다). 구독 사건 시점의 스냅샷은 결번을 내고, 등록 뒤 스냅샷은 모든
     * 구독에서 0 이다.
     */
    @Test
    void concurrentPublishAndSubscribeGapsOnlyWithEventTiming() throws Exception {
        assertTrue(stressGaps(false) > 0, "대조군(구독 사건 시점)이 결번을 내지 못했다");
        assertEquals(0, stressGaps(true), "등록 뒤 스냅샷에서 결번이 났다");
    }

    private long stressGaps(boolean afterRegistration) throws Exception {
        final int sessions = 200;
        try (Node n = new Node()) {
            ChannelSequences seqs = new ChannelSequences();
            OrderbookSnapshotOnSubscribe snap = new OrderbookSnapshotOnSubscribe(ownerView(seqs), json, () -> n.outbound);
            if (afterRegistration) {
                n.inbound.addInterceptor(snap);
            }
            n.registry.delay(1);
            AtomicBoolean stop = new AtomicBoolean();
            Thread publisher = new Thread(() -> {
                while (!stop.get()) {
                    publish(n, seqs);
                    Thread.yield();
                }
            }, "race-publisher");
            publisher.start();
            try {
                for (int i = 0; i < sessions; i++) {
                    Message<byte[]> sub = n.subscribe("s" + i);
                    if (!afterRegistration) {
                        snap.sendSnapshot(sub);
                    }
                }
                n.awaitRegistered(sessions);
                // 모든 구독이 스냅샷을 받고 그 뒤 실시간을 몇 건 받을 때까지 게시를 둔다. 결번은 다음 실시간에서 드러난다.
                Awaitility.await().atMost(WAIT).until(() -> n.clients.size() == sessions
                        && n.clients.values().stream().allMatch(c -> c.snapshots() == 1 && c.realtimeAfterSnapshot() >= 5));
            } finally {
                stop.set(true);
                publisher.join(10_000);
            }
            long gaps = n.clients.values().stream().mapToLong(Client::gaps).sum();
            long withGap = n.clients.values().stream().filter(c -> c.gaps() > 0).count();
            System.out.printf("race stress: afterRegistration=%s sessions=%d gaps=%d sessionsWithGap=%d published=%d%n",
                    afterRegistration, sessions, gaps, withGap, seqs.current("orderbook." + MARKET));
            return gaps;
        }
    }

    // ---------------------------------------------------------------- 비소유 노드(중계 미러로 스냅샷)

    /**
     * 중계 수신이 받은 호가를 내장 브로커로 전달한 뒤 미러를 갱신하면, 그 사이에 등록된 구독의 스냅샷은 미러의 옛 seq 를 읽고
     * 방금 전달된 번호를 받지 못한다. 대조군은 그 순서를 손으로 밟고, 고친 RelaySubscriber 는 미러를 먼저 갱신한다.
     */
    @Test
    void relayDeliverBeforeMirrorUpdateMissesTheDeliveredSeq() throws Exception {
        try (Node n = new Node()) {
            MarketMirror mirror = new MarketMirror(System::currentTimeMillis);
            n.inbound.addInterceptor(new OrderbookSnapshotOnSubscribe(directory(), nonOwnerViews(mirror), json,
                    () -> n.outbound));
            byte[] first = realtime(1);
            n.fanout.deliverTopic(DEST, first);
            mirror.update(MarketMirror.Channel.ORDERBOOK, MARKET, EPOCH, 1, first);

            // 대조군: 전달 -> (구독 등록, 스냅샷) -> 미러 갱신.
            byte[] second = realtime(2);
            n.fanout.deliverTopic(DEST, second);
            n.subscribe("s1");
            Awaitility.await().atMost(WAIT).until(() -> n.client("s1").snapshots() == 1);
            mirror.update(MarketMirror.Channel.ORDERBOOK, MARKET, EPOCH, 2, second);
            n.fanout.deliverTopic(DEST, realtime(3));

            Client c = n.client("s1");
            assertEquals(List.of("snapshot:1", "realtime:3"), c.log(), "대조군이 경합을 재현하지 못했다");
            assertEquals(1, c.gaps());
        }
    }

    @Test
    void relaySubscriberUpdatesMirrorBeforeDeliveringSoNoGap() throws Exception {
        try (Node n = new Node()) {
            MarketMirror mirror = new MarketMirror(System::currentTimeMillis);
            n.inbound.addInterceptor(new OrderbookSnapshotOnSubscribe(directory(), nonOwnerViews(mirror), json,
                    () -> n.outbound));
            RelaySubscriber relay = new RelaySubscriber("node-b", PartitionPlan.single(), directory(), n.fanout, mirror,
                    beats, null, json, new SimpleMeterRegistry().timer("relay.lag"));
            relay(relay, 1);
            // seq 2 가 내장 브로커를 지난 직후(RelaySubscriber 가 전달을 마치고 돌아오기 전)에 구독을 열고 스냅샷을 기다린다.
            AtomicBoolean opened = new AtomicBoolean();
            n.brokerChannel.addInterceptor(new ChannelInterceptor() {
                @Override
                public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
                    if (seqOf(message) == 2 && opened.compareAndSet(false, true)) {
                        n.subscribe("s1");
                        Awaitility.await().atMost(WAIT).until(() -> n.client("s1").snapshots() == 1);
                    }
                }
            });
            relay(relay, 2);
            relay(relay, 3);

            assertTrue(opened.get(), "구독을 여는 지점을 지나지 않았다");
            Client c = n.client("s1");
            assertEquals(List.of("snapshot:2", "realtime:3"), c.log());
            assertEquals(0, c.gaps());
        }
    }

    // ---------------------------------------------------------------- 배선

    private final OwnerBeats beats = new OwnerBeats(System::currentTimeMillis);

    /** 비소유 노드의 호가 선택. 소유 노드 하트비트 셋이 닿고 미러가 알린 게시(seq 0) 이상이라 미러를 쓴다(§4-A-2). */
    private MarketViews nonOwnerViews(MarketMirror mirror) {
        beats.onSubscribed();
        for (int i = 0; i < 3; i++) {
            beats.onHeartbeat(PartitionPlan.single().partitionOf(1), EPOCH, new RelayEnvelope.Heartbeat(
                    Map.of(MARKET, new long[] {EPOCH, 0}), Map.of()));
        }
        EpochSource epochs = new EpochSource(null, null) {
            @Override
            public long epoch(int marketId) {
                return EPOCH;
            }
        };
        // MarketDataService 는 미러를 쓰지 못할 때(DB 집계)만 부른다. 이 시험은 그 경로에 닿지 않는다.
        return new MarketViews(null, new ChannelSequences(), epochs, PartitionPlan.single(), id -> null,
                new MarketViews.Relay(mirror, beats, 60_000), json);
    }

    /**
     * 소유 노드의 스냅샷 본문. MarketViews 의 소유 노드 분기처럼 채널 카운터의 현재값을 seq 로 쓴다(호가 사본은 DB 가 필요해
     * 싣지 않는다. 결번 판정에는 seq 만 쓴다).
     */
    private static Function<String, Map<String, Object>> ownerView(ChannelSequences seqs) {
        return symbol -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("market", symbol);
            body.put("epoch", EPOCH);
            body.put("seq", seqs.current("orderbook." + symbol));
            return body;
        };
    }

    /** MarketDataPublisher.publishOrderbook 처럼 카운터를 올린 뒤 내장 브로커로 보낸다. */
    private void publish(Node n, ChannelSequences seqs) {
        long seq = seqs.next("orderbook." + MARKET);
        n.fanout.topic(DEST, MARKET, EPOCH, seq, realtimeBody(seq));
    }

    private void relay(RelaySubscriber relay, long seq) {
        byte[] body = realtime(seq);
        relay.onMessage(RelayEnvelope.topic("node-a", DEST, MARKET, EPOCH, seq, body)
                .headers(System.currentTimeMillis()), body, "ack-" + seq);
    }

    private static Map<String, Object> realtimeBody(long seq) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "orderbook");
        body.put("market", MARKET);
        body.put("epoch", EPOCH);
        body.put("seq", seq);
        body.put("stream", "realtime");
        body.put("orderbook_units", List.of());
        return body;
    }

    private byte[] realtime(long seq) {
        try {
            return json.writeValueAsBytes(realtimeBody(seq));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long seqOf(Message<?> message) {
        try {
            return json.readTree((byte[]) message.getPayload()).path("seq").asLong();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MarketDirectory directory() {
        return MarketDirectory.ofSnapshot(null, List.of(new MarketDirectory.Market(1, MARKET, "ACTIVE",
                (short) 2, "BTC", "Bitcoin", 8, (short) 1, "KRW", 0, BigDecimal.ONE, new BigDecimal("0.0001"),
                BigDecimal.ONE, null, null, BigDecimal.ZERO, BigDecimal.ZERO)));
    }

    /** 노드 하나의 STOMP 배선. 수신 채널은 Spring 기본처럼 스레드 풀이고, 게시 채널과 발신 채널은 호출 스레드에서 돈다. */
    private final class Node implements AutoCloseable {

        final ExecutorService inboundPool = Executors.newFixedThreadPool(4);
        final ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(inboundPool);
        final ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel();
        final ExecutorSubscribableChannel brokerChannel = new ExecutorSubscribableChannel();
        final GatedRegistry registry = new GatedRegistry();
        final SimpleBrokerMessageHandler broker;
        final WsFanout fanout;
        final Map<String, Client> clients = new ConcurrentHashMap<>();

        Node() {
            broker = new SimpleBrokerMessageHandler(inbound, outbound, brokerChannel, List.of("/topic"));
            broker.setSubscriptionRegistry(registry);
            broker.start();
            // 운영 수신 채널에는 주석 처리기와 사용자 목적지 처리기도 붙는다. 그 처리기가 먼저 끝나도 스냅샷을 보내지 않아야 한다.
            inbound.subscribe(message -> { });
            fanout = new WsFanout(new SimpMessagingTemplate(brokerChannel), json, null, "node-b");
            outbound.subscribe(m -> {
                Client c = client(SimpMessageHeaderAccessor.getSessionId(m.getHeaders()));
                SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(m.getHeaders());
                if (type == SimpMessageType.CONNECT_ACK) {
                    c.connected = true;
                } else if (type == SimpMessageType.MESSAGE) {
                    c.receive(m);
                }
            });
        }

        Client client(String session) {
            return clients.computeIfAbsent(session, k -> new Client());
        }

        /**
         * StompSubProtocolHandler 처럼 SUBSCRIBE 를 수신 채널에 넘긴다. 처리(등록)는 풀 스레드에서 한다. 내장 브로커는 CONNECT 로
         * 세션을 안 뒤에만 그 세션에 게시를 보내므로 먼저 CONNECT 를 마친다.
         */
        Message<byte[]> subscribe(String session) {
            SimpMessageHeaderAccessor connect = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT);
            connect.setSessionId(session);
            inbound.send(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()));
            Awaitility.await().atMost(WAIT).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
                    .until(() -> client(session).connected);
            StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            a.setSessionId(session);
            a.setSubscriptionId("sub-0");
            a.setDestination(DEST);
            Message<byte[]> message = MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
            inbound.send(message);
            return message;
        }

        void awaitRegistered(int count) {
            Awaitility.await().atMost(WAIT).until(() -> registry.registered.get() >= count);
        }

        @Override
        public void close() {
            broker.stop();
            inboundPool.shutdownNow();
        }
    }

    /** 등록을 늦추거나 멈출 수 있는 구독 표. 나머지는 Spring 기본 표에 맡긴다. */
    private static final class GatedRegistry implements SubscriptionRegistry {

        final DefaultSubscriptionRegistry delegate = new DefaultSubscriptionRegistry();
        final AtomicInteger registered = new AtomicInteger();
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private volatile long delayMillis;

        void gate(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        void delay(long millis) {
            this.delayMillis = millis;
        }

        @Override
        public void registerSubscription(Message<?> message) {
            try {
                if (entered != null) {
                    entered.countDown();
                    release.await();
                }
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delegate.registerSubscription(message);
            registered.incrementAndGet();
        }

        @Override
        public void unregisterSubscription(Message<?> message) {
            delegate.unregisterSubscription(message);
        }

        @Override
        public void unregisterAllSubscriptions(String sessionId) {
            delegate.unregisterAllSubscriptions(sessionId);
        }

        @Override
        public MultiValueMap<String, String> findSubscriptions(Message<?> message) {
            return delegate.findSubscriptions(message);
        }
    }

    /** 계약 §5-2 의 orderbook 클라이언트 규칙으로 받은 순서를 따라 결번을 센다. */
    private final class Client {

        private final List<String> log = new ArrayList<>();
        volatile boolean connected;
        private Long baseline;
        private int snapshots;
        private int realtimeAfterSnapshot;
        private long gaps;

        synchronized void receive(Message<?> message) {
            JsonNode body;
            try {
                body = json.readTree((byte[]) message.getPayload());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            long seq = body.path("seq").asLong();
            String stream = body.path("stream").asText();
            if (log.size() < 20) {
                log.add(stream + ":" + seq);
            }
            if ("snapshot".equals(stream)) {
                snapshots++;
                // 스냅샷보다 먼저 온 실시간이 있으면 그보다 큰 스냅샷만 기준점이 된다(기준점 전진 규칙).
                if (seq != 0 && (baseline == null || seq > baseline)) {
                    baseline = seq;
                }
                return;
            }
            if (snapshots > 0) {
                realtimeAfterSnapshot++;
            }
            if (baseline == null) {
                baseline = seq;
                return;
            }
            if (seq <= baseline) {
                return;
            }
            if (seq != baseline + 1) {
                gaps++;
            }
            baseline = seq;
        }

        synchronized List<String> log() {
            return List.copyOf(log);
        }

        synchronized int snapshots() {
            return snapshots;
        }

        synchronized int realtimeAfterSnapshot() {
            return realtimeAfterSnapshot;
        }

        synchronized long gaps() {
            return gaps;
        }
    }
}
