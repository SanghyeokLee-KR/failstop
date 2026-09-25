package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.ws.CandleFanout;
import kr.exchange.api.ws.CandleSubscriptions;
import kr.exchange.api.ws.ChannelSequences;
import kr.exchange.api.ws.LeaseWatch;
import kr.exchange.api.ws.MarketDataPublisher;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.WsFanout;
import kr.exchange.api.ws.WsGate;
import kr.exchange.api.ws.relay.BrokerLink;
import kr.exchange.api.ws.relay.BusClient;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayBroker;
import kr.exchange.api.ws.relay.RelayHeartbeat;
import kr.exchange.api.ws.relay.RelayPublisher;
import kr.exchange.api.ws.relay.RelaySubscriber;
import kr.exchange.api.ws.relay.WsRelayProperties;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파티션 리더 모드(P3 흉내)의 중계 epoch(S6 설계 4-7, 6-2). 두 PartitionNode(A 는 p0, B 는 p1)에 중계 구성요소를 붙이고 실제
 * RabbitMQ 로 게시를 나눈다. 버스 관측자가 마켓 1(p0)은 A 의 epoch, 마켓 2(p1)는 B 의 epoch 를 본다. A 를 닫으면 B 가 TTL 뒤 p0 을
 * 얻고 마켓 1 의 epoch 만 한 번 오른다. 이것이 '그 마켓만 재동기화'의 서버 쪽 근거다.
 */
class PartitionRelayEpochTest {

    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";
    private static final LongSupplier CLOCK = () -> System.nanoTime() / 1_000_000;
    static final RelayBroker BROKER = RelayBroker.start();

    @AfterAll
    static void stop() {
        BROKER.close();
    }

    /** 노드 하나에 붙인 중계 구성요소. 운영 배선(WsRelayConfig)과 같은 조립이다. */
    static final class Relay implements AutoCloseable {
        final PartitionNode node;
        final MarketDirectory markets;
        final BrokerLink pub;
        final BrokerLink sub;
        final RelayPublisher publisher;
        final MarketMirror mirror = new MarketMirror(CLOCK);
        final OwnerBeats beats = new OwnerBeats(CLOCK);
        final RelayHeartbeat heartbeat;
        final CandleFanout candles;
        final LeaseWatch leaseWatch;

        Relay(PartitionNode node) {
            this.node = node;
            ObjectMapper json = new ObjectMapper();
            WsRelayProperties props = BROKER.clientProperties();
            this.markets = new MarketDirectory(node.f.app);
            markets.start();
            MarketDataService data = new MarketDataService(node.f.app);
            this.pub = new BrokerLink(node.name + "-pub", BrokerLink.Role.PUBLISH, props, new BrokerLink.Listener() { });
            this.publisher = new RelayPublisher(pub, 10_000, Duration.ofSeconds(1), Duration.ofSeconds(2), CLOCK);
            ExecutorSubscribableChannel local = new ExecutorSubscribableChannel();
            local.subscribe(m -> { });
            WsFanout fanout = new WsFanout(new SimpMessagingTemplate(local), json, publisher, node.leases.holder());
            CandleSubscriptions candleSubscriptions = new CandleSubscriptions();
            this.candles = new CandleFanout(markets, data, candleSubscriptions, fanout, 1_000);
            MarketDataPublisher marketData = new MarketDataPublisher(node.f.app, markets, data,
                    new OrderQueryService(node.f.app), new ChannelSequences(), candleSubscriptions, fanout, candles, mirror,
                    new SimpleMeterRegistry());
            RelaySubscriber subscriber = new RelaySubscriber(node.leases.holder(), node.plan, markets, fanout, mirror, beats,
                    candles, json, new SimpleMeterRegistry().timer("lag"));
            this.sub = new BrokerLink(node.name + "-sub", BrokerLink.Role.SUBSCRIBE, props, subscriber);
            subscriber.attach(sub);
            this.heartbeat = new RelayHeartbeat(node.leases.holder(), node.leases, markets, marketData::lastPublished,
                    publisher, json, Duration.ofSeconds(1));
            node.afterSettle = marketData::publishSettled;
            this.leaseWatch = LeaseWatch.of(node.f.app, node.plan.partitions(), Duration.ofMillis(200), CLOCK);
            leaseWatch.start();
            publisher.start();
            pub.start();
            sub.start();
            heartbeat.start();
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> pub.connected() && sub.connected());
        }

        WsGate gate(boolean broker) {
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> leaseWatch.view().isPresent());
            return new WsGate(true, node.leases.holder(), node.plan.partitions(),
                    p -> node.leases.runtime(p).map(r -> r.coordinator().openTerm()).orElse(null), leaseWatch::view,
                    broker ? beats : null, Duration.ofSeconds(5), node.options.ttl.plusSeconds(3), node.options.ttl, CLOCK);
        }

        @Override
        public void close() {
            leaseWatch.close();
            heartbeat.close();
            sub.close();
            pub.close();
            publisher.close();
            candles.close();
            markets.close();
        }
    }

    @Test
    void eachMarketCarriesItsPartitionEpochAndOnlyTheLostPartitionMarketsRise() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_s6_relay_epoch", 2)) {
            f.db.setProtocol(3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", new BigDecimal("100000000"));
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("100"));
            PartitionNode a = new PartitionNode("node-a", f, new PartitionNode.Options().preferred(P0));
            PartitionNode b = new PartitionNode("node-b", f, new PartitionNode.Options().preferred(P1));
            try (BusClient bus = BusClient.observer(BROKER); Relay ra = new Relay(a); Relay rb = new Relay(b)) {
                a.start();
                b.start();
                awaitAccepting(a, P0);
                awaitAccepting(b, P1);
                long a0 = a.lease(P0).currentTerm().orElseThrow().epoch();
                long b1 = b.lease(P1).currentTerm().orElseThrow().epoch();
                assertTrue(a0 != b1);

                // P3 에서 한 파티션만 쥔 simple 노드는 거절하고, 중계가 온전한 broker 노드는 받는다.
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> ra.beats.reachable(P1, b1));
                assertEquals(WsGate.Reason.RELAY_REQUIRED, ra.gate(false).evaluate().reason());
                // 새 게이트는 (P, E)를 처음 본 때부터 유예를 세므로 첫 판정은 하트비트와 무관하게 열려 있다. 유예보다 길게 본다.
                WsGate brokerGate = ra.gate(true);
                long until = System.nanoTime() + a.options.ttl.plusSeconds(5).toNanos();
                while (System.nanoTime() < until) {
                    assertTrue(brokerGate.evaluate().open(), "중계가 온전한데 broker 노드가 거절했다");
                    Thread.sleep(200);
                }
                // 대조군: B 의 하트비트를 멈추면 같은 게이트가 유예 뒤 소유 노드 침묵으로 닫는다.
                rb.heartbeat.close();
                Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                        .until(() -> brokerGate.evaluate().reason() == WsGate.Reason.RELAY_UNAVAILABLE);
                assertTrue(brokerGate.degraded(WsGate.Cause.OWNER_SILENT));

                submit(a, f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1"));
                submit(b, f.limit(KRW_ETH, alice, OrderCommand.Side.BUY, "2000", "0.1"));
                submit(b, f.limit(KRW_ETH, bob, OrderCommand.Side.SELL, "2000", "0.05"));
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> orderbook(bus, "KRW-BTC").size() >= 1
                        && orderbook(bus, "KRW-ETH").size() >= 2);
                assertEquals(List.of(a0), epochs(bus, "KRW-BTC"), "마켓 1 이 p0 소유자(A)의 epoch 가 아니다");
                assertEquals(List.of(b1), epochs(bus, "KRW-ETH"), "마켓 2 가 p1 소유자(B)의 epoch 가 아니다");
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                        rb.mirror.get(MarketMirror.Channel.ORDERBOOK, "KRW-BTC") != null);
                assertEquals(a0, rb.mirror.get(MarketMirror.Channel.ORDERBOOK, "KRW-BTC").epoch());

                // A 가 죽는다. B 가 TTL 뒤 p0 을 얻고 마켓 1 의 epoch 만 오른다.
                a.close();
                awaitAccepting(b, P0);
                long b0 = b.lease(P0).currentTerm().orElseThrow().epoch();
                assertTrue(b0 > a0);
                submit(b, f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50100", "0.1"));
                submit(b, f.limit(KRW_ETH, alice, OrderCommand.Side.BUY, "2010", "0.1"));
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> epochs(bus, "KRW-BTC").contains(b0)
                        && orderbook(bus, "KRW-ETH").size() >= 3);
                assertEquals(List.of(a0, b0), epochs(bus, "KRW-BTC"), "마켓 1 의 epoch 가 정확히 한 번 오르지 않았다");
                assertEquals(List.of(b1), epochs(bus, "KRW-ETH"), "p0 인계가 마켓 2 의 epoch 를 바꿨다");
                List<Long> ethSeqs = orderbook(bus, "KRW-ETH").stream().map(r -> Long.parseLong(r.h("x-seq"))).toList();
                for (int i = 1; i < ethSeqs.size(); i++) {
                    assertEquals(ethSeqs.get(i - 1) + 1, ethSeqs.get(i), "마켓 2 호가 seq 에 결번이 있다: " + ethSeqs);
                }
                assertEquals(b0, rb.mirror.get(MarketMirror.Channel.ORDERBOOK, "KRW-BTC").epoch(),
                        "B 의 미러가 마켓 1 의 새 epoch 로 바뀌지 않았다");
            } finally {
                a.close();
                b.close();
            }
        }
    }

    private static void submit(PartitionNode node, OrderIntake intake) {
        SubmitResult result = node.submit(intake);
        assertTrue(result.queued() && !result.replayed(), "접수 실패: " + result);
    }

    private static List<BusClient.Received> orderbook(BusClient bus, String market) {
        return bus.matching(r -> ("/topic/orderbook." + market).equals(r.h("x-dest")));
    }

    private static List<Long> epochs(BusClient bus, String market) {
        return orderbook(bus, market).stream().map(r -> Long.parseLong(r.h("x-epoch"))).distinct().toList();
    }
}
