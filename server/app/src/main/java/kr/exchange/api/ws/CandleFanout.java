package kr.exchange.api.ws;

import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 중계 모드의 캔들 방출(S6 설계 4-10). 캔들 구독은 그 노드의 SUBSCRIBE 로만 보이므로 소유 노드가 모든 노드의 구독자 몫을 계산할 수
 * 없다. 각 노드가 체결 메시지(자기 게시와 받은 봉투)를 보고 자기 구독자가 있는 단위만 DB 의 최근 버킷을 읽어 자기 내장 브로커로
 * 보낸다. 중계로 다시 보내지 않는다. 캔들 seq 는 버킷의 최대 event_seq 라 어느 노드가 계산해도 같다(API 설계 §5-2).
 *
 * 읽기는 스레드 하나에서 한다. 같은 마켓의 대기 작업은 하나로 모은다. 어차피 최근 버킷 하나를 읽으므로 결과가 같다.
 */
public final class CandleFanout implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CandleFanout.class);

    private final MarketDirectory markets;
    private final MarketDataService marketData;
    private final CandleSubscriptions subscriptions;
    private final WsFanout fanout;
    private final ThreadPoolExecutor executor;
    // 대기 중인 마켓과 그동안 본 가장 큰 epoch.
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private final AtomicLong overflows = new AtomicLong();

    public CandleFanout(MarketDirectory markets, MarketDataService marketData, CandleSubscriptions subscriptions,
                        WsFanout fanout, int queueCapacity) {
        this.markets = markets;
        this.marketData = marketData;
        this.subscriptions = subscriptions;
        this.fanout = fanout;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity), r -> {
                    Thread t = new Thread(r, "ws-relay-candle");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 기다리지 않는다. 큐가 넘치면 버리고 센다. */
    public void onTrade(String market, long epoch) {
        if (subscriptions.subscribedUnits(market).isEmpty()) {
            return;
        }
        boolean[] queued = {false};
        pending.compute(market, (k, cur) -> {
            if (cur == null) {
                queued[0] = true;
                return epoch;
            }
            return Math.max(cur, epoch);
        });
        if (!queued[0]) {
            return;
        }
        try {
            executor.execute(() -> {
                Long latest = pending.remove(market);
                emit(market, latest == null ? epoch : latest);
            });
        } catch (RejectedExecutionException e) {
            pending.remove(market);
            overflows.incrementAndGet();
        }
    }

    private void emit(String symbol, long epoch) {
        MarketDirectory.Market market = markets.bySymbol(symbol);
        for (String unit : subscriptions.subscribedUnits(symbol)) {
            try {
                Map<String, Object> candle = marketData.latestCandle(market, unit);
                if (candle != null) {
                    fanout.localTopic("/topic/candle." + unit + "." + symbol, body(unit, symbol, epoch, candle));
                }
            } catch (RuntimeException e) {
                log.warn("candle 방출 실패(unit={} market={}, 다른 채널·unit 영향 없음): {}", unit, symbol, e.getMessage());
            }
        }
    }

    /** §5-4 candle 메시지. trade_count·stream 은 스키마에 없어 싣지 않는다. */
    static Map<String, Object> body(String unit, String market, long epoch, Map<String, Object> candle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("type", "candle");
        body.put("unit", unit);
        body.put("market", market);
        body.put("epoch", epoch);
        body.put("seq", candle.get("seq"));
        body.put("candle_time", candle.get("candle_time"));
        body.put("open", candle.get("open"));
        body.put("high", candle.get("high"));
        body.put("low", candle.get("low"));
        body.put("close", candle.get("close"));
        body.put("volume", candle.get("volume"));
        return body;
    }

    public long overflows() {
        return overflows.get();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
