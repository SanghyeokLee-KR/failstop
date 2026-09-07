package kr.exchange.api.ws;

import org.springframework.beans.factory.annotation.Qualifier;
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

import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionGate;

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
    private final AdmissionGate admissionGate;
    private final AdmissionControl wsAdmission;

    public WebSocketConfig(@Lazy StompAuthInterceptor authInterceptor,
                           @Value("${app.ws.allowed-origins:https://demo.exchange.local,http://localhost:3000}")
                           String[] allowedOrigins,
                           @Value("${exchange.require-secrets:false}") boolean requireSecrets,
                           // @Lazy 를 붙이지 않는다. 두 타입 모두 final 이라 CGLIB 이 프록시를
                           // 만들지 못하고 기동이 깨진다. 순환도 없다. ApiConfig 는 이 설정을
                           // 참조하지 않는다.
                           AdmissionGate admissionGate,
                           @Qualifier("wsAdmissionControl") AdmissionControl wsAdmission) {
        // 운영 기동에서 데모·로컬 오리진이 남아 있으면(미지정 시 @Value 기본값으로 폴백) fail-closed.
        // 운영 CONNECT 인증이 있어도 오리진 화이트리스트는 심층방어의 첫 관문이라 명시를 강제한다.
        if (requireSecrets) {
            for (String origin : allowedOrigins) {
                if (origin == null || origin.isBlank()
                        || origin.contains("localhost") || origin.contains("demo.exchange.local")) {
                    throw new IllegalStateException(
                            "운영 WS 오리진에 데모·로컬 오리진이 있다: " + origin
                                    + ". app.ws.allowed-origins를 운영 오리진으로 명시하라.");
                }
            }
        }
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
        this.admissionGate = admissionGate;
        this.wsAdmission = wsAdmission;
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
        // 동시 연결 수용 제어. 위 세 상한은 세션 하나가 쓰는 양을 묶지만 세션 수는 묶지 않는다.
        // 노드를 죽인 것은 세션 하나의 크기가 아니라 세션 수였다(증거 31회차).
        registration.addDecoratorFactory(
                delegate -> new WsConnectionLimitHandler(delegate, admissionGate, wsAdmission));
    }

    @Bean
    public ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }
}
