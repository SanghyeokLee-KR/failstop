package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.api.ws.relay.RelayPublisher;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.ledger.SettlementService;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 게시기 단위 시험의 배선. 시험마다 새 DB(ReservationFixture, P2)에 앱 로그인으로 접수·엔진·정산을 돌리고, 정산이 끝난 배치를
 * 실제 게시기로 게시한다. 내장 브로커 대신 브로커 채널을 붙잡아 목적지와 본문을 본다. 중계 모드는 가짜 발행 연결로 흉내 낸다.
 */
final class PublisherRig implements AutoCloseable {

    /** 중계 모드의 가짜 발행 연결. 보낸 봉투를 모은다. */
    static final class CapturingTransport implements RelayPublisher.Transport {
        final List<RelayEnvelope> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean connected() {
            return true;
        }

        @Override
        public CompletableFuture<Void> send(RelayEnvelope envelope) {
            sent.add(envelope);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void abort() {
        }
    }

    final ReservationFixture f;
    final ObjectMapper json = new ObjectMapper();
    final MarketDirectory markets;
    final MarketDataService data;
    final ChannelSequences sequences = new ChannelSequences();
    final CandleSubscriptions candleSubscriptions = new CandleSubscriptions();
    final List<Message<?>> local = new CopyOnWriteArrayList<>();
    final CapturingTransport transport = new CapturingTransport();
    final RelayPublisher relay;
    final WsFanout fanout;
    final CandleFanout candles;
    final MarketMirror mirror;
    final MarketDataPublisher publisher;
    final LeaderLeaseService lease;
    final LeaderTerm term;
    final CommandJournalAppender appender;
    final SettlementService settlement;
    final MatchingEngineImpl engine = new MatchingEngineImpl();

    PublisherRig(String prefix, boolean brokerMode) throws Exception {
        this.f = ReservationFixture.create(prefix, 2);
        this.markets = new MarketDirectory(f.app);
        markets.start();
        this.data = new MarketDataService(f.app);
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        channel.subscribe(local::add);
        this.relay = brokerMode ? new RelayPublisher(transport, 10_000, Duration.ofSeconds(1), Duration.ofSeconds(2),
                () -> System.nanoTime() / 1_000_000) : null;
        this.fanout = new WsFanout(new SimpMessagingTemplate(channel), json, relay, "rig-node");
        this.candles = brokerMode ? new CandleFanout(markets, data, candleSubscriptions, fanout, 1_000) : null;
        this.mirror = brokerMode ? new MarketMirror(() -> System.nanoTime() / 1_000_000) : null;
        this.publisher = new MarketDataPublisher(f.app, markets, data, new OrderQueryService(f.app), sequences,
                candleSubscriptions, fanout, candles, mirror, new SimpleMeterRegistry());
        if (relay != null) {
            relay.start();
        }
        this.lease = f.lease();
        this.term = lease.currentTerm().orElseThrow();
        this.appender = f.appender(lease, true);
        this.settlement = f.settlement(lease);
    }

    /** 접수·적용·정산하고, 운영 sink 처럼 엔진 호가 사본을 이 기간으로 갈아 끼운 뒤 그 배치를 게시한다. */
    List<EngineEvent> submit(OrderIntake intake) {
        List<EngineEvent> settled = f.submit(appender, engine, settlement, intake);
        kr.exchange.engine.core.OrderBookImpl book = engine.orderBook(intake.marketId());
        if (book != null) {
            data.publishEngineDepth(intake.marketId(), term.epoch(),
                    MarketDataService.fromEngine(book.askDepth(15), book.bidDepth(15)));
        }
        publisher.publishSettled(term, settled);
        return settled;
    }

    /** 다른 노드가 이끈 것처럼 접수·정산만 한다. 이 노드의 엔진 사본도 게시도 바뀌지 않는다. */
    void submitElsewhere(OrderIntake intake) {
        f.submit(appender, new MatchingEngineImpl(), settlement, intake);
    }

    /** 이 노드 내장 브로커로 간 본문. 사용자 목적지는 /user/{id}/queue/... 다. */
    List<JsonNode> messages(String destination) {
        List<JsonNode> out = new ArrayList<>();
        for (Message<?> m : local) {
            if (destination.equals(SimpMessageHeaderAccessor.getDestination(m.getHeaders()))) {
                try {
                    out.add(json.readTree((byte[]) m.getPayload()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return out;
    }

    /** 발행 스레드가 큐를 비울 때까지 기다린다. */
    void drainRelay() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> relay.queueSize() == 0
                && relay.pendingWrites() == 0);
    }

    @Override
    public void close() {
        if (candles != null) {
            candles.close();
        }
        if (relay != null) {
            relay.close();
        }
        markets.close();
        f.close();
    }
}
