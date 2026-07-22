package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 구독 즉시 스냅샷 계약(§5-2 v2.4, orderbook 채널 한정). SUBSCRIBE 성공 직후 해당 세션의
 * 해당 subscription으로만 stream:"snapshot" 1건을 보낸다. 브로커 브로드캐스트가 아니라
 * clientOutboundChannel 직송인 이유: 다른 구독자에게 중복 스냅샷이 가면 안 된다.
 * snapshot의 seq가 이후 realtime 연속성 기준점이다.
 */
public class OrderbookSnapshotOnSubscribe implements ApplicationListener<SessionSubscribeEvent> {

    private static final Logger log = LoggerFactory.getLogger(OrderbookSnapshotOnSubscribe.class);
    private static final String PREFIX = "/topic/orderbook.";

    private final MarketDirectory markets;
    private final MarketDataService marketData;
    private final ChannelSequences sequences;
    private final EpochSource epochSource;
    private final ObjectMapper objectMapper;
    private final Supplier<MessageChannel> clientOutboundChannel;

    public OrderbookSnapshotOnSubscribe(MarketDirectory markets, MarketDataService marketData,
                                        ChannelSequences sequences, EpochSource epochSource,
                                        ObjectMapper objectMapper,
                                        Supplier<MessageChannel> clientOutboundChannel) {
        this.markets = markets;
        this.marketData = marketData;
        this.sequences = sequences;
        this.epochSource = epochSource;
        this.objectMapper = objectMapper;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public void onApplicationEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(PREFIX)) {
            return;
        }
        try {
            String symbol = destination.substring(PREFIX.length());
            MarketDirectory.Market market = markets.bySymbol(symbol);
            MarketDataService.Depth depth = marketData.depth(market.id());
            Map<String, Object> body = marketData.orderbookBody(market.symbol(), depth,
                    epochSource.epoch(), sequences.current("orderbook." + market.symbol()),
                    ApiFormats.time(Instant.now()));
            body.put("type", "orderbook");
            body.put("stream", "snapshot");

            SimpMessageHeaderAccessor out = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            out.setSessionId(accessor.getSessionId());
            out.setSubscriptionId(accessor.getSubscriptionId());
            out.setDestination(destination);
            out.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
            out.setLeaveMutable(true);
            clientOutboundChannel.get().send(MessageBuilder.createMessage(
                    objectMapper.writeValueAsBytes(body), out.getMessageHeaders()));
        } catch (Exception e) {
            // 스냅샷 실패는 구독 자체를 깨지 않는다. 클라이언트는 REST 스냅샷으로 대체 가능하다.
            log.warn("orderbook 구독 스냅샷 전송 실패: {}", e.getMessage());
        }
    }
}
