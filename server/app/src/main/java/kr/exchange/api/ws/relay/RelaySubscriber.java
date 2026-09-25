package kr.exchange.api.ws.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.ws.CandleFanout;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.WsFanout;
import kr.exchange.engine.PartitionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 중계 수신(S6 설계 4-5). 구독 연결로 받은 봉투의 모양을 보고, 자기가 보낸 봉투는 확인만 하고 건너뛴다. 하트비트는 소유자 표만
 * 갱신하고, 나머지는 내장 브로커로 보낸 뒤 호가·티커면 미러를, 체결이면 캔들 팬아웃을 갱신한다.
 *
 * 확인은 200건이나 200ms 마다 마지막으로 받은 메시지로 누적해 보낸다. 처리하다 실패해도 확인한다(최대 한 번 전달).
 */
public final class RelaySubscriber implements BrokerLink.Listener {

    private static final Logger log = LoggerFactory.getLogger(RelaySubscriber.class);
    static final int ACK_EVERY = 200;
    static final long ACK_INTERVAL_MS = 200;

    private final String holder;
    private final PartitionPlan plan;
    private final MarketDirectory markets;
    private final WsFanout fanout;
    private final MarketMirror mirror;
    private final OwnerBeats beats;
    private final CandleFanout candles;
    private final ObjectMapper json;
    private final Timer lag;

    private final AtomicLong[] received = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private final AtomicLong malformed = new AtomicLong();

    private volatile BrokerLink link;

    private final Object ackLock = new Object();
    private String pendingAck;
    private int sinceAck;

    public RelaySubscriber(String holder, PartitionPlan plan, MarketDirectory markets, WsFanout fanout,
                           MarketMirror mirror, OwnerBeats beats, CandleFanout candles, ObjectMapper json,
                           Timer lag) {
        this.holder = holder;
        this.plan = plan;
        this.markets = markets;
        this.fanout = fanout;
        this.mirror = mirror;
        this.beats = beats;
        this.candles = candles;
        this.json = json;
        this.lag = lag;
    }

    /** 구독 연결을 붙이고 누적 확인 주기를 건다. */
    public void attach(BrokerLink subscriptionLink) {
        this.link = subscriptionLink;
        subscriptionLink.scheduler().scheduleWithFixedDelay(this::flushAck, ACK_INTERVAL_MS, ACK_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void onConnected() {
        synchronized (ackLock) {
            pendingAck = null;
            sinceAck = 0;
        }
        log.info("중계 구독 세대 {} 시작", beats.onSubscribed());
    }

    @Override
    public void onDisconnected() {
        beats.onUnsubscribed();
    }

    @Override
    public void onMessage(Map<String, String> headers, byte[] body, String ackId) {
        try {
            dispatch(headers, body);
        } catch (RelayEnvelope.Malformed e) {
            malformed.incrementAndGet();
            log.debug("중계 봉투 모양 위반: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("중계 봉투 처리 실패(최대 한 번 전달, 클라이언트는 결번으로 재동기화): {}", e.getMessage());
        } finally {
            acknowledge(ackId);
        }
    }

    private void dispatch(Map<String, String> headers, byte[] body) throws RelayEnvelope.Malformed {
        RelayEnvelope e = RelayEnvelope.decode(headers, body);
        if (e.fromSelf(holder)) {
            return;
        }
        String sentAt = headers.get(RelayEnvelope.H_SENT_AT);
        lag.record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - Long.parseLong(sentAt))));
        received[e.kind().ordinal()].incrementAndGet();
        switch (e.kind()) {
            case HB -> onHeartbeat(e);
            case TOPIC -> {
                fanout.deliverTopic(e.dest(), e.body());
                MarketMirror.Channel channel = e.mirrorChannel();
                if (channel != null) {
                    mirror.update(channel, e.market(), e.epoch(), e.seq(), e.body());
                } else if (e.trade()) {
                    candles.onTrade(e.market(), e.epoch());
                }
            }
            case USER -> fanout.deliverUser(e.userId(), e.dest(), e.body());
        }
    }

    private void onHeartbeat(RelayEnvelope e) throws RelayEnvelope.Malformed {
        if (!plan.contains(e.partition())) {
            throw new RelayEnvelope.Malformed("계획에 없는 파티션 " + e.partition());
        }
        RelayEnvelope.Heartbeat hb = RelayEnvelope.Heartbeat.decode(json, e.body());
        for (Map<String, long[]> channel : java.util.List.of(hb.orderbook(), hb.ticker())) {
            for (String symbol : channel.keySet()) {
                MarketDirectory.Market market;
                try {
                    market = markets.bySymbol(symbol);
                } catch (RuntimeException unknown) {
                    throw new RelayEnvelope.Malformed("hb 모르는 마켓 " + symbol);
                }
                if (!e.partition().equals(plan.partitionOf(market.id()))) {
                    throw new RelayEnvelope.Malformed("hb 마켓 " + symbol + " 이 파티션 " + e.partition() + " 밖이다");
                }
            }
        }
        beats.onHeartbeat(e.partition(), e.epoch(), hb);
    }

    private void acknowledge(String ackId) {
        String flush = null;
        synchronized (ackLock) {
            pendingAck = ackId;
            if (++sinceAck >= ACK_EVERY) {
                flush = pendingAck;
                pendingAck = null;
                sinceAck = 0;
            }
        }
        send(flush);
    }

    private void flushAck() {
        String flush;
        synchronized (ackLock) {
            flush = pendingAck;
            pendingAck = null;
            sinceAck = 0;
        }
        send(flush);
    }

    private void send(String ackId) {
        BrokerLink l = link;
        if (ackId == null || l == null) {
            return;
        }
        try {
            l.ack(ackId);
        } catch (RuntimeException e) {
            // 예약 작업이 예외로 끝나면 그 뒤 주기가 멈춘다. 확인을 못 보낸 메시지는 연결이 끊기면 브로커가 다시 보낸다.
            log.debug("중계 누적 확인 실패: {}", e.getMessage());
        }
    }

    public long received(RelayEnvelope.Kind kind) {
        return received[kind.ordinal()].get();
    }

    public long malformed() {
        return malformed.get();
    }
}
