package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.market.MarketDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 구독 즉시 스냅샷 계약(§5-2 v2.4, orderbook 채널 한정). SUBSCRIBE 성공 직후 해당 세션의
 * 해당 subscription으로만 stream:"snapshot" 1건을 보낸다. 브로커 브로드캐스트가 아니라
 * clientOutboundChannel 직송인 이유: 다른 구독자에게 중복 스냅샷이 가면 안 된다.
 * snapshot의 seq가 이후 realtime 연속성 기준점이고, seq 가 0 이면 기준점이 아니다(§4-A-2, MarketViews).
 *
 * 스냅샷은 노드 내장 브로커(SimpleBrokerMessageHandler)가 그 SUBSCRIBE 를 처리해 구독을 등록한 뒤에 읽는다(§5-2 '스냅샷을 읽는
 * 시점'). 그래서 수신 채널의 실행기 가로채기(afterMessageHandled)로 붙는다. 전에는 구독 사건(SessionSubscribeEvent)에서 읽었는데,
 * 그 사건은 SUBSCRIBE 를 수신 채널의 스레드 풀에 넘긴 직후 나오고 등록은 풀의 다른 스레드에서 끝난다. 그 사이에 게시된 realtime 은
 * 이 구독에 가지 않아, 스냅샷 seq 다음 번호가 빠졌다(2026-09-25 S6 관문, 스냅샷 831 다음 realtime 833). 등록 뒤에 읽으면 seq 가
 * 스냅샷보다 큰 게시는 모두 등록 뒤에 브로커를 지나므로 이 구독에 간다. 소유 노드는 카운터를 올린 뒤 브로커로 보내고
 * (MarketDataPublisher), 비소유 노드는 미러를 갱신한 뒤 세션에 전달한다(RelaySubscriber).
 */
public class OrderbookSnapshotOnSubscribe implements ExecutorChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OrderbookSnapshotOnSubscribe.class);
    private static final String PREFIX = "/topic/orderbook.";

    private final Function<String, Map<String, Object>> snapshots;
    private final ObjectMapper objectMapper;
    private final Supplier<MessageChannel> clientOutboundChannel;

    public OrderbookSnapshotOnSubscribe(MarketDirectory markets, MarketViews views, ObjectMapper objectMapper,
                                        Supplier<MessageChannel> clientOutboundChannel) {
        this(symbol -> views.orderbook(markets.bySymbol(symbol)), objectMapper, clientOutboundChannel);
    }

    /** @param snapshots 마켓 심볼로 REST 모양의 호가 본문을 낸다(MarketViews.orderbook 과 같은 선택) */
    public OrderbookSnapshotOnSubscribe(Function<String, Map<String, Object>> snapshots, ObjectMapper objectMapper,
                                        Supplier<MessageChannel> clientOutboundChannel) {
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    /**
     * 수신 채널이 한 처리기에 메시지를 넘기고 돌아온 뒤 부른다. 내장 브로커가 SUBSCRIBE 를 예외 없이 처리했을 때만 스냅샷을 보낸다.
     * 다른 처리기(주석 처리기, 사용자 목적지 처리기)의 완료는 구독 등록과 무관하다. 인증 가로채기가 거절한 SUBSCRIBE 는 어느
     * 처리기에도 가지 않으므로 스냅샷도 없다.
     */
    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        if (ex != null || !(handler instanceof SimpleBrokerMessageHandler)) {
            return;
        }
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.SUBSCRIBE) {
            return;
        }
        sendSnapshot(message);
    }

    /** 그 SUBSCRIBE 의 세션·구독으로 스냅샷 한 건을 보낸다. 구독 등록 뒤에 불러야 한다. */
    void sendSnapshot(Message<?> subscribe) {
        String destination = SimpMessageHeaderAccessor.getDestination(subscribe.getHeaders());
        if (destination == null || !destination.startsWith(PREFIX)) {
            return;
        }
        try {
            Map<String, Object> body = snapshots.apply(destination.substring(PREFIX.length()));
            body.put("type", "orderbook");
            body.put("stream", "snapshot");

            SimpMessageHeaderAccessor out = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            out.setSessionId(SimpMessageHeaderAccessor.getSessionId(subscribe.getHeaders()));
            out.setSubscriptionId(SimpMessageHeaderAccessor.getSubscriptionId(subscribe.getHeaders()));
            out.setDestination(destination);
            out.setContentType(MimeTypeUtils.APPLICATION_JSON);
            out.setLeaveMutable(true);
            clientOutboundChannel.get().send(MessageBuilder.createMessage(
                    objectMapper.writeValueAsBytes(body), out.getMessageHeaders()));
        } catch (Exception e) {
            // 스냅샷 실패는 구독 자체를 깨지 않는다. 클라이언트는 REST 스냅샷으로 대체 가능하다.
            log.warn("orderbook 구독 스냅샷 전송 실패: {}", e.getMessage());
        }
    }
}
