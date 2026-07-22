package kr.exchange.api.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * /ws/v1 STOMP 엔드포인트(§5-1). 단일 노드 SimpleBroker 구성이다.
 * 다중 노드의 /topic Redis 릴레이·/user 외부 STOMP 브로커 릴레이(§5-1 (A))는 M2 몫이며,
 * 그 전까지 크로스노드 user-destination 라우팅은 성립하지 않는다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(@Lazy StompAuthInterceptor authInterceptor,
                           @Value("${app.ws.allowed-origins:https://demo.exchange.local,http://localhost:3000}")
                           String[] allowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 백엔드_API_설계.md §2-7: 데모 오리진 화이트리스트, * 금지. 정확한 오리진만 허용한다.
        registry.addEndpoint("/ws/v1").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(wsHeartbeatScheduler());
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    /**
     * 슬로우 컨슈머 백프레셔 한도. 미설정 시 버퍼가 무한 적재되어 힙을 잠식한다.
     * 전송 버퍼·전송 시간 초과 세션은 종료되고, 클라이언트는 §5-2 재구독·REST 재동기화로 복구한다.
     * 메시지 상한 64KB는 §2-7 요청 바디 상한과 정렬한다.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit(15_000);
        registration.setSendBufferSizeLimit(512 * 1024);
        registration.setMessageSizeLimit(64 * 1024);
    }

    @Bean
    public ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }
}
