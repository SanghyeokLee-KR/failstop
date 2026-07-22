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
import java.util.function.Supplier;

/**
 * STOMP 인증(§3-6 WS 규칙, §5-6 구독 흐름).
 *
 * CONNECT: Authorization JWT가 있으면 canonical "GET\n/ws/v1\n"으로 검증하고 Principal을
 * 부여한다. 검증 실패는 예외로 표면화되어 STOMP 1.2 규약대로 ERROR 프레임 후 절단된다.
 * 무인증 CONNECT는 허용한다(공개 채널 전용 세션).
 *
 * SUBSCRIBE(/user/**): 무인증 세션이면 RECEIPT 없이 subscription_rejected 통지 MESSAGE
 * 1건을 보내고 프레임을 폐기한다(연결·기존 구독 유지, §5-6 v2.2).
 */
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);
    private static final String WS_CANONICAL = "GET\n/ws/v1\n";

    private final ApiAuthService authService;
    private final Supplier<MessageChannel> clientOutboundChannel;

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
                if (authorization != null) {
                    AuthPrincipal principal =
                            authService.verify(authorization, WS_CANONICAL, false);
                    accessor.setUser(new StompPrincipal(String.valueOf(principal.userId())));
                    // setUser만으로는 부족하다. 핸드셰이크 세션에 저장돼야 이후 프레임에 승계된다.
                    if (accessor.getSessionAttributes() != null) {
                        accessor.getSessionAttributes().put("exchange.ws.user",
                                String.valueOf(principal.userId()));
                    }
                }
                return message;
            }
            case SUBSCRIBE -> {
                String destination = accessor.getDestination();
                if (destination != null && destination.startsWith("/user/")
                        && accessor.getUser() == null) {
                    rejectSubscription(accessor);
                    return null;
                }
                return message;
            }
            default -> {
                return message;
            }
        }
    }

    private void rejectSubscription(StompHeaderAccessor inbound) {
        try {
            SimpMessageHeaderAccessor out = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            out.setSessionId(inbound.getSessionId());
            out.setSubscriptionId(inbound.getSubscriptionId());
            out.setDestination(inbound.getDestination());
            out.setLeaveMutable(true);
            String payload = "{\"schema_version\":\"1.0\",\"type\":\"subscription_rejected\","
                    + "\"error\":{\"name\":\"jwt_verification\"}}";
            clientOutboundChannel.get().send(MessageBuilder.createMessage(
                    payload.getBytes(StandardCharsets.UTF_8), out.getMessageHeaders()));
        } catch (RuntimeException e) {
            log.warn("subscription_rejected 통지 실패: {}", e.getMessage());
        }
    }
}
