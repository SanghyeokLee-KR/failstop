package kr.exchange.api.ws;

import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.OrderCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final SimpMessagingTemplate messaging;
    private final JdbcClient jdbc;
    private final MarketDirectory markets;
    private final MarketDataService marketData;
    private final OrderQueryService orders;
    private final ChannelSequences sequences;
    private final EpochSource epochSource;

    public MarketDataPublisher(SimpMessagingTemplate messaging, JdbcClient jdbc,
                               MarketDirectory markets, MarketDataService marketData,
                               OrderQueryService orders, ChannelSequences sequences,
                               EpochSource epochSource) {
        this.messaging = messaging;
        this.jdbc = jdbc;
        this.markets = markets;
        this.marketData = marketData;
        this.orders = orders;
        this.sequences = sequences;
        this.epochSource = epochSource;
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
            publishOrderbook(market, epoch, events.get(0).engineTs());
            if (traded) {
                publishTicker(market, epoch);
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
        body.put("seq", sequences.next(channel));
        body.put("trade_id", String.valueOf(event.eventSeq()));
        body.put("price", ApiFormats.amount(event.price()));
        body.put("volume", ApiFormats.amount(event.quantity()));
        body.put("taker_side", event.takerSide() == OrderCommand.Side.BUY ? "bid" : "ask");
        body.put("timestamp", ApiFormats.timeMs(event.engineTs()));
        messaging.convertAndSend("/topic/" + channel, body);
    }

    private void publishOrderbook(MarketDirectory.Market market, long epoch, long engineTs) {
        String channel = "orderbook." + market.symbol();
        MarketDataService.Depth depth = marketData.depth(market.id());
        Map<String, Object> body = marketData.orderbookBody(market.symbol(), depth,
                epoch, sequences.next(channel), ApiFormats.timeMs(engineTs));
        body.put("type", "orderbook");
        body.put("stream", "realtime");
        messaging.convertAndSend("/topic/" + channel, body);
    }

    private void publishTicker(MarketDirectory.Market market, long epoch) {
        String channel = "ticker." + market.symbol();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("type", "ticker");
        body.putAll(marketData.ticker(market, epoch, sequences.next(channel)));
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
            BigDecimal fee = tradeFee(event.eventSeq(), maker);
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

    private BigDecimal tradeFee(long tradeId, boolean maker) {
        return jdbc.sql("SELECT " + (maker ? "maker_fee" : "taker_fee")
                        + " FROM trades WHERE id = ?")
                .params(tradeId)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
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
            jdbc.sql("""
                            SELECT a.symbol, ub.available, ub.locked
                            FROM user_balances ub JOIN assets a ON a.id = ub.asset_id
                            WHERE ub.user_id = ? AND ub.asset_id = ?
                            """)
                    .params(userId, assetId)
                    .query((rs, rowNum) -> {
                        Map<String, Object> asset = new LinkedHashMap<String, Object>();
                        asset.put("currency", rs.getString("symbol"));
                        asset.put("balance", ApiFormats.amount(rs.getBigDecimal("available")));
                        asset.put("locked", ApiFormats.amount(rs.getBigDecimal("locked")));
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
