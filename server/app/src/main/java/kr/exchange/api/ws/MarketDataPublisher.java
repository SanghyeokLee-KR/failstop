package kr.exchange.api.ws;

import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderQueryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.OrderCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 정산 완료 이벤트 → WS 게시 파이프라인(§5).
 *
 * 호출 시점은 SettlementService.appendAndSettle 트랜잭션 COMMIT 이후다(단일 라이터 스레드).
 * 커밋 전 게시는 롤백 시 유령 이벤트가 되므로 금지이고, 커밋 후 게시는 크래시 시 유실될 수
 * 있으나 WS는 최선 노력 채널이다. 상태 권위는 REST 스냅샷 + seq/epoch 재동기화 계약(§5-2)이
 * 지므로 게시 실패는 엔진을 세우지 않고 삼킨다(로그만).
 *
 * 단일 노드 SimpleBroker 전제. 크로스노드 팬아웃(/topic Redis 릴레이, /user 외부 STOMP
 * 브로커 릴레이, §5-1)은 다중 노드 승격 시점의 몫이다.
 */
public class MarketDataPublisher {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPublisher.class);

    // §4-C-1과 동일한 원가 산출식. 델타에 평단가를 싣지 않으면 클라이언트가 직전 값을 보존해
    // 매수마다 낡은 평단가가 남는다(§5-5 v2.21). 조인은 기존 쿼리에 붙여 왕복을 늘리지 않는다.
    // publishMyAsset은 매칭 단일 라이터 스레드에서 동기 실행되므로 별도 쿼리 추가는 금지다.
    private static final String MY_ASSET_SQL = """
            SELECT a.symbol, ub.available, ub.locked,
                   COALESCE(pc.cost_qty, 0)    AS cost_basis_qty,
                   COALESCE(pc.cost_amount, 0) AS cost_basis_amount,
                   COALESCE(CASE WHEN pc.cost_qty > 0
                                 THEN round(pc.cost_amount / pc.cost_qty, qa.scale::int)
                            END, 0) AS avg_buy_price
            FROM user_balances ub
            JOIN assets a ON a.id = ub.asset_id
            LEFT JOIN position_cost pc
                 ON pc.user_id = ub.user_id AND pc.asset_id = ub.asset_id
            LEFT JOIN assets qa ON qa.id = pc.quote_asset_id
            WHERE ub.user_id = ? AND ub.asset_id = ?
            """;

    private final SimpMessagingTemplate messaging;
    private final JdbcClient jdbc;
    private final MarketDirectory markets;
    private final MarketDataService marketData;
    private final OrderQueryService orders;
    private final ChannelSequences sequences;
    private final EpochSource epochSource;
    private final CandleSubscriptions candleSubscriptions;
    private final MeterRegistry meterRegistry;
    // 미터는 market당 1개라 캐시로 태그 리스트 재구성·레지스트리 조회를 라이터 스레드에서 없앤다.
    private final Map<String, Counter> tradeCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> fillTimers = new ConcurrentHashMap<>();

    public MarketDataPublisher(SimpMessagingTemplate messaging, JdbcClient jdbc,
                               MarketDirectory markets, MarketDataService marketData,
                               OrderQueryService orders, ChannelSequences sequences,
                               EpochSource epochSource, CandleSubscriptions candleSubscriptions,
                               MeterRegistry meterRegistry) {
        this.messaging = messaging;
        this.jdbc = jdbc;
        this.markets = markets;
        this.marketData = marketData;
        this.orders = orders;
        this.sequences = sequences;
        this.epochSource = epochSource;
        this.candleSubscriptions = candleSubscriptions;
        this.meterRegistry = meterRegistry;
    }

    /** 정산 커밋이 끝난 단일 명령 방출 배치를 게시한다. 실패는 삼키고 기록만 한다. */
    public void publishSettled(List<EngineEvent> events) {
        try {
            doPublish(events);
        } catch (RuntimeException e) {
            log.warn("WS 게시 실패(정합성 영향 없음, 클라이언트는 REST 재동기화): {}", e.getMessage());
        }
    }

    private void doPublish(List<EngineEvent> events) {
        long epoch = epochSource.epoch();
        Set<Integer> touchedMarkets = new LinkedHashSet<>();
        Set<Long> touchedUsers = new LinkedHashSet<>();
        boolean traded = false;

        for (EngineEvent event : events) {
            MarketDirectory.Market market = markets.byId(event.marketId());
            touchedMarkets.add(event.marketId());
            switch (event.type()) {
                case TRADE -> {
                    traded = true;
                    publishTrade(market, event, epoch);
                    publishMyOrder(market, event, event.orderId(), epoch);
                    publishMyOrder(market, event, event.counterOrderId(), epoch);
                    touchedUsers.add(event.makerUserId());
                    touchedUsers.add(event.takerUserId());
                    recordTradeMetrics(market, event);
                }
                case ACCEPTED, CANCELED -> {
                    Long userId = publishMyOrder(market, event, event.orderId(), epoch);
                    if (userId != null) {
                        touchedUsers.add(userId);
                    }
                }
                case REJECTED -> publishMyOrder(market, event, event.orderId(), epoch);
            }
        }

        for (int marketId : touchedMarkets) {
            MarketDirectory.Market market = markets.byId(marketId);
            // depth를 마켓당 한 번만 집계해 orderbook·ticker 두 경로에 공유한다(배치당 중복 제거).
            MarketDataService.Depth depth = marketData.depth(market.id());
            publishOrderbook(market, depth, epoch, events.get(0).engineTs());
            if (traded) {
                publishTicker(market, depth, epoch);
                publishCandles(market, epoch);
            }
        }
        for (long userId : touchedUsers) {
            publishMyAsset(userId, touchedMarkets, epoch, events.get(0).engineTs());
        }
    }

    private void publishTrade(MarketDirectory.Market market, EngineEvent event, long epoch) {
        String channel = "trade." + market.symbol();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("type", "trade");
        body.put("market", market.symbol());
        body.put("epoch", epoch);
        // seq는 event_seq 도메인(= REST §4-A-3 seq = trade_id). REST 스냅샷과 WS 라이브가 한
        // 도메인이라 재연결 재동기화 후 라이브가 드롭되지 않는다(계약 §5-2, §5-4). 채널 인메모리
        // 카운터를 쓰면 REST(event_seq 수백만대)와 도메인이 갈려 재동기화가 피드를 영구 정지시킨다.
        body.put("seq", event.eventSeq());
        body.put("trade_id", String.valueOf(event.eventSeq()));
        body.put("price", ApiFormats.amount(event.price()));
        body.put("volume", ApiFormats.amount(event.quantity()));
        body.put("taker_side", event.takerSide() == OrderCommand.Side.BUY ? "bid" : "ask");
        body.put("timestamp", ApiFormats.timeMs(event.engineTs()));
        messaging.convertAndSend("/topic/" + channel, body);
    }

    private void publishOrderbook(MarketDirectory.Market market, MarketDataService.Depth depth,
                                  long epoch, long engineTs) {
        String channel = "orderbook." + market.symbol();
        Map<String, Object> body = marketData.orderbookBody(market.symbol(), depth,
                epoch, sequences.next(channel), ApiFormats.timeMs(engineTs));
        body.put("type", "orderbook");
        body.put("stream", "realtime");
        messaging.convertAndSend("/topic/" + channel, body);
    }

    /**
     * 체결이 발생한 마켓의 활성 candle 구독 unit에 형성 중 캔들을 방출한다(§5-3, §5-4). 값은
     * REST와 같은 trades 집계(§4-A-5)의 최근 버킷 1건이나, 라이브 경로는 현재 버킷 창만 읽는
     * latestCandle을 써 이력 무제한 스캔이 라이터 루프를 잠식하지 않게 한다.
     * "현재 캔들 갱신"·"새 캔들 확정"은 별도 필드 없이 candle_time 단조성으로 구분한다(§5-3).
     * seq는 버킷 최대 event_seq(= REST §4-A-5 seq)라 REST 스냅샷과 WS 라이브가 한 도메인이다
     * (§5-2, 재연결 재동기화 연속성). 같은 배치 방출이라 이 버킷 최대 id == 방금 체결의 event_seq다.
     * trade_count·stream은 §5-4 candle 스키마에 없어 싣지 않는다. unit별로 격리해 한 unit 조회
     * 실패가 나머지 unit·채널을 취소하지 않게 한다(candle은 이 배치에서 가장 무거운 경로).
     */
    private void publishCandles(MarketDirectory.Market market, long epoch) {
        for (String unit : candleSubscriptions.subscribedUnits(market.symbol())) {
            try {
                Map<String, Object> candle = marketData.latestCandle(market, unit);
                if (candle == null) {
                    continue;
                }
                String channel = "candle." + unit + "." + market.symbol();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("schema_version", ApiFormats.SCHEMA_VERSION);
                body.put("type", "candle");
                body.put("unit", unit);
                body.put("market", market.symbol());
                body.put("epoch", epoch);
                body.put("seq", candle.get("seq"));
                body.put("candle_time", candle.get("candle_time"));
                body.put("open", candle.get("open"));
                body.put("high", candle.get("high"));
                body.put("low", candle.get("low"));
                body.put("close", candle.get("close"));
                body.put("volume", candle.get("volume"));
                messaging.convertAndSend("/topic/" + channel, body);
            } catch (RuntimeException e) {
                log.warn("candle 방출 실패(unit={} market={}, 다른 채널·unit 영향 없음): {}",
                        unit, market.symbol(), e.getMessage());
            }
        }
    }

    private void publishTicker(MarketDirectory.Market market, MarketDataService.Depth depth,
                               long epoch) {
        String channel = "ticker." + market.symbol();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("type", "ticker");
        body.putAll(marketData.ticker(market, epoch, sequences.next(channel), depth));
        messaging.convertAndSend("/topic/" + channel, body);
    }

    /** 게시한 주문의 소유 user_id를 반환한다. 대상 행이 없으면 null(중복 조회 방지용 재사용). */
    private Long publishMyOrder(MarketDirectory.Market market, EngineEvent event,
                                Long orderId, long epoch) {
        if (orderId == null) {
            return null;
        }
        OrderQueryService.OrderRow row = orders.byInternalId(orderId).orElse(null);
        if (row == null) {
            // REJECTED 중 프로젝션 불가 형태(기형 명령)는 행이 없다. 통지 대상 사용자도 특정 불가.
            return null;
        }
        String channel = "myOrder:" + row.userId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("type", "myOrder");
        body.put("epoch", epoch);
        body.put("seq", sequences.next(channel));
        body.put("id", row.externalId().toString());
        body.put("client_order_id", row.clientOrderId());
        body.put("market", market.symbol());
        body.put("side", "BUY".equals(row.side()) ? "bid" : "ask");
        body.put("status", row.status());
        body.put("price", row.price() == null ? null : ApiFormats.amount(row.price()));
        body.put("executed_volume", ApiFormats.amount(row.filledQty()));
        body.put("remaining_volume", ApiFormats.amount(row.remainingQty()));
        body.put("reject_reason", row.rejectReason());
        if (event.type() == EngineEvent.EventType.TRADE) {
            boolean maker = orderId == event.counterOrderId().longValue();
            BigDecimal fee = tradeFee(event.eventSeq(), maker, event.engineTs());
            Map<String, Object> trade = new LinkedHashMap<>();
            trade.put("trade_id", String.valueOf(event.eventSeq()));
            trade.put("price", ApiFormats.amount(event.price()));
            trade.put("volume", ApiFormats.amount(event.quantity()));
            trade.put("liquidity_flag", maker ? "MAKER" : "TAKER");
            trade.put("fee", fee == null ? "0" : ApiFormats.amount(fee));
            body.put("trade", trade);
        }
        body.put("timestamp", ApiFormats.timeMs(event.engineTs()));
        messaging.convertAndSendToUser(String.valueOf(row.userId()), "/queue/myOrder", body);
        return row.userId();
    }

    private BigDecimal tradeFee(long tradeId, boolean maker, long engineTs) {
        // trades PK가 (id, created_at)라 id 단독은 파티션 프루닝이 안 붙어 전 파티션 프로브였다.
        // created_at = engine_ts 파생 불변식이라 같은 값을 동반해 대상 파티션·PK로 직접 프로브한다.
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(engineTs), ZoneOffset.UTC);
        return jdbc.sql("SELECT " + (maker ? "maker_fee" : "taker_fee")
                        + " FROM trades WHERE id = ? AND created_at = ?")
                .params(tradeId, createdAt)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    /** 체결 곡선 Counter + 접수→체결확정 지연 Timer(하네스 CICD §5-2). 라이터 스레드 비블로킹. */
    private void recordTradeMetrics(MarketDirectory.Market market, EngineEvent event) {
        tradeCounter(market.symbol()).increment();
        long latencyMs = Math.max(0, System.currentTimeMillis() - event.engineTs());
        fillTimer(market.symbol()).record(Duration.ofMillis(latencyMs));
    }

    private Counter tradeCounter(String market) {
        return tradeCounters.computeIfAbsent(market, m ->
                Counter.builder("exchange.trades")
                        .description("체결 건수(체결/초 곡선)")
                        .tag("market", m)
                        .register(meterRegistry));
    }

    private Timer fillTimer(String market) {
        // classic 히스토그램 + 500ms SLO 경계 버킷. 서버측 histogram_quantile p95/p99와
        // slo_500ms 직독을 지원한다(CICD §5-2, §5-3).
        return fillTimers.computeIfAbsent(market, m ->
                Timer.builder("exchange.order.latency")
                        .description("접수→체결확정 지연(SLI)")
                        .tag("market", m)
                        .tag("outcome", "filled")
                        .publishPercentileHistogram()
                        .serviceLevelObjectives(Duration.ofMillis(500))
                        .register(meterRegistry));
    }

    private void publishMyAsset(long userId, Set<Integer> marketIds, long epoch, long engineTs) {
        Set<Short> assetIds = new LinkedHashSet<>();
        for (int marketId : marketIds) {
            MarketDirectory.Market market = markets.byId(marketId);
            assetIds.add(market.baseAssetId());
            assetIds.add(market.quoteAssetId());
        }
        java.util.List<Map<String, Object>> assets = new java.util.ArrayList<>();
        for (short assetId : assetIds) {
            jdbc.sql(MY_ASSET_SQL)
                    .params(userId, assetId)
                    .query((rs, rowNum) -> {
                        Map<String, Object> asset = new LinkedHashMap<String, Object>();
                        asset.put("currency", rs.getString("symbol"));
                        asset.put("balance", ApiFormats.amount(rs.getBigDecimal("available")));
                        asset.put("locked", ApiFormats.amount(rs.getBigDecimal("locked")));
                        asset.put("avg_buy_price",
                                ApiFormats.amount(rs.getBigDecimal("avg_buy_price")));
                        // v1 quote 단일(KRW) 전제. §4-C-1 과 같은 이유로 상수다.
                        asset.put("unit_currency", "KRW");
                        asset.put("cost_basis_qty",
                                ApiFormats.amount(rs.getBigDecimal("cost_basis_qty")));
                        asset.put("cost_basis_amount",
                                ApiFormats.amount(rs.getBigDecimal("cost_basis_amount")));
                        return asset;
                    })
                    .optional()
                    .ifPresent(assets::add);
        }
        String channel = "myAsset:" + userId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("type", "myAsset");
        body.put("epoch", epoch);
        body.put("seq", sequences.next(channel));
        body.put("assets", assets);
        body.put("timestamp", ApiFormats.timeMs(engineTs));
        messaging.convertAndSendToUser(String.valueOf(userId), "/queue/myAsset", body);
    }
}
