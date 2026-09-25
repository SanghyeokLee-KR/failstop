package kr.exchange.api.ws.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * WebSocket 노드 단위 중계 설정(API 설계 §5-1, S6 설계 4-12). 기본값 simple 은 중계 없이 노드 내장 브로커만 쓴다.
 *
 * @param ownerHeartbeat   간격 x 3 + publishMaxAge 가 닿음 판정 창(5초)을 넘을 수 없다(OwnerBeats 가 거부한다)
 * @param ownerSilentAfter 비어 있으면 리스 TTL + 3초다. 설정값은 그보다 짧을 수 없다(WsGate 가 거부한다)
 */
@ConfigurationProperties(prefix = "exchange.ws.relay")
public record WsRelayProperties(Mode mode, Broker broker, String destination, Subscription subscription,
                                int publishQueue, Duration writeTimeout, Duration publishMaxAge,
                                Duration ownerHeartbeat, Duration degradedAfter, Duration ownerSilentAfter,
                                Reconnect reconnect) {

    public enum Mode { SIMPLE, BROKER }

    /** @param passcode 비밀 값. 환경 변수 EXCHANGE_WS_RELAY_BROKER_PASSCODE 로만 넣는다 */
    public record Broker(String host, int port, String virtualHost, String login, String passcode,
                         Duration heartbeat) {
        public Broker {
            if (port <= 0) {
                port = 61613;
            }
            if (virtualHost == null || virtualHost.isBlank()) {
                virtualHost = "exchange";
            }
            if (login == null || login.isBlank()) {
                login = "exch-relay";
            }
            if (heartbeat == null) {
                heartbeat = Duration.ofSeconds(5);
            }
        }

        @Override
        public String toString() {
            // 비밀 값이 로그와 예외 문구에 실리지 않게 한다.
            return "Broker[host=" + host + ", port=" + port + ", virtualHost=" + virtualHost + ", login=" + login + "]";
        }
    }

    /** 구독 큐 인자(S6 설계 4-2). 멈춘 노드의 큐를 건수·바이트·나이로 브로커에서 묶는다. */
    public record Subscription(int prefetch, int maxLength, DataSize maxLengthBytes, Duration ttl) {
        public Subscription {
            if (prefetch <= 0) {
                prefetch = 1000;
            }
            if (maxLength <= 0) {
                maxLength = 5000;
            }
            if (maxLengthBytes == null) {
                maxLengthBytes = DataSize.ofMegabytes(4);
            }
            if (ttl == null) {
                ttl = Duration.ofSeconds(10);
            }
        }
    }

    public record Reconnect(Duration min, Duration max) {
        public Reconnect {
            if (min == null) {
                min = Duration.ofSeconds(1);
            }
            if (max == null) {
                max = Duration.ofSeconds(10);
            }
        }
    }

    public WsRelayProperties {
        if (mode == null) {
            mode = Mode.SIMPLE;
        }
        if (broker == null) {
            broker = new Broker(null, 0, null, null, null, null);
        }
        if (destination == null || destination.isBlank()) {
            destination = "/topic/exch.bus.v1";
        }
        if (subscription == null) {
            subscription = new Subscription(0, 0, null, null);
        }
        if (publishQueue <= 0) {
            publishQueue = 10_000;
        }
        if (writeTimeout == null) {
            writeTimeout = Duration.ofSeconds(1);
        }
        if (publishMaxAge == null) {
            publishMaxAge = Duration.ofSeconds(2);
        }
        if (ownerHeartbeat == null) {
            ownerHeartbeat = Duration.ofSeconds(1);
        }
        if (degradedAfter == null) {
            degradedAfter = Duration.ofSeconds(5);
        }
        if (reconnect == null) {
            reconnect = new Reconnect(null, null);
        }
    }

    public boolean brokerMode() {
        return mode == Mode.BROKER;
    }

    /** 소유 노드가 닿지 않는 상태의 중계 불능 유예. 설정이 없으면 리스 TTL + 3초다(S6 설계 4-11 (ii)). */
    public Duration ownerSilentAfter(Duration leaseTtl) {
        return ownerSilentAfter != null ? ownerSilentAfter : leaseTtl.plusSeconds(3);
    }

    /** broker 모드에서 주소나 비밀 값이 비면 기동을 멈춘다. */
    public void requireBroker() {
        if (!brokerMode()) {
            return;
        }
        if (broker.host() == null || broker.host().isBlank()) {
            throw new IllegalStateException("exchange.ws.relay.mode=broker 인데 exchange.ws.relay.broker.host 가 비었다");
        }
        if (broker.passcode() == null || broker.passcode().isBlank()) {
            throw new IllegalStateException("exchange.ws.relay.mode=broker 인데 브로커 비밀 값"
                    + "(EXCHANGE_WS_RELAY_BROKER_PASSCODE)이 비었다");
        }
    }
}
