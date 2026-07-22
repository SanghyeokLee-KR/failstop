package kr.exchange.api.ws;

import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * STOMP 인증·구독 상한(§3-6 WS 규칙, §5-1·§5-6 구독 흐름).
 *
 * CONNECT: Authorization JWT가 있으면 canonical "GET\n/ws/v1\n"으로 검증하고 Principal을
 * 부여한다. 검증 실패는 예외로 표면화되어 STOMP 1.2 규약대로 ERROR 프레임 후 절단된다.
 * 무인증 CONNECT는 허용한다(공개 채널 전용 세션).
 *
 * SUBSCRIBE(/user/**): 무인증 세션이면 RECEIPT 없이 subscription_rejected 통지 MESSAGE
 * 1건을 보내고 프레임을 폐기한다(연결·기존 구독 유지, §5-6 v2.2).
 *
 * SUBSCRIBE 상한: §5-1 v2.5 확정 연결당 50채널 상한. 세션별 활성 구독 수를 세고 초과분은
 * §5-6 거절 규약(사유 name=subscription_limit)으로 폐기한다. UNSUBSCRIBE·DISCONNECT에서
 * 카운터를 감소·정리한다.
 */
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);
    private static final String WS_CANONICAL = "GET\n/ws/v1\n";
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 50;

    private final ApiAuthService authService;
    private final Supplier<MessageChannel> clientOutboundChannel;
    private final Map<String, AtomicInteger> sessionSubscriptions = new ConcurrentHashMap<>();

    public StompAuthInterceptor(ApiAuthService authService,
                                Supplier<MessageChannel> clientOutboundChannel) {
        this.authService = authService;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> {
                String authorization = accessor.getFirstNativeHeader("Authorization");
                // 빈 헤더는 무인증 CONNECT로 취급한다. 헤더만 있고 값이 없다고 절단하지 않는다.
                if (authorization != null && !authorization.isBlank()) {
                    AuthPrincipal principal =
                            authService.verify(authorization, WS_CANONICAL, false);
                    accessor.setUser(new StompPrincipal(String.valueOf(principal.userId())));
                }
                return message;
            }
            case SUBSCRIBE -> {
                String destination = accessor.getDestination();
                if (destination != null && destination.startsWith("/user/")
                        && accessor.getUser() == null) {
                    rejectSubscription(accessor, "jwt_verification");
                    return null;
                }
                AtomicInteger count = sessionSubscriptions
                        .computeIfAbsent(accessor.getSessionId(), k -> new AtomicInteger());
                if (count.get() >= MAX_SUBSCRIPTIONS_PER_SESSION) {
                    rejectSubscription(accessor, "subscription_limit");
                    return null;
                }
                count.incrementAndGet();
                return message;
            }
            case UNSUBSCRIBE -> {
                AtomicInteger count = sessionSubscriptions.get(accessor.getSessionId());
                if (count != null) {
                    count.updateAndGet(n -> n > 0 ? n - 1 : 0);
                }
                return message;
            }
            case DISCONNECT -> {
                sessionSubscriptions.remove(accessor.getSessionId());
                return message;
            }
            default -> {
                return message;
            }
        }
    }

    private void rejectSubscription(StompHeaderAccessor inbound, String reason) {
        try {
            SimpMessageHeaderAccessor out = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            out.setSessionId(inbound.getSessionId());
            out.setSubscriptionId(inbound.getSubscriptionId());
            out.setDestination(inbound.getDestination());
            out.setLeaveMutable(true);
            String payload = "{\"schema_version\":\"1.0\",\"type\":\"subscription_rejected\","
                    + "\"error\":{\"name\":\"" + reason + "\"}}";
            clientOutboundChannel.get().send(MessageBuilder.createMessage(
                    payload.getBytes(StandardCharsets.UTF_8), out.getMessageHeaders()));
        } catch (RuntimeException e) {
            log.warn("subscription_rejected 통지 실패: {}", e.getMessage());
        }
    }
}
