package kr.exchange.api.ws.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * WebSocket 노드 단위 중계 설정(API 설계 §5-1, S6 설계 4-12). 기본값 simple 은 중계 없이 노드 내장 브로커만 쓴다.
 *
 * @param ownerHeartbeat   간격 x 3 + publishMaxAge 가 닿음 판정 창(5초)을 넘을 수 없고, 간격 x 2 가 mirrorMaxSilence 를 넘을 수 없다
 *                         (OwnerBeats 가 거부한다). 기본 200ms 다
 * @param mirrorMaxSilence 비소유 노드가 미러를 쓰는 가장 최근 소유 노드 하트비트의 최대 나이. 기본 400ms 다
 * @param ownerSilentAfter 비어 있으면 리스 TTL + 3초다. 설정값은 그보다 짧을 수 없다(WsGate 가 거부한다)
 */
@ConfigurationProperties(prefix = "exchange.ws.relay")
public record WsRelayProperties(Mode mode, Broker broker, String destination, Subscription subscription,
                                int publishQueue, Duration writeTimeout, Duration publishMaxAge,
                                Duration ownerHeartbeat, Duration degradedAfter, Duration ownerSilentAfter,
                                Reconnect reconnect, Duration mirrorMaxSilence) {

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

    /**
     * 재연결 간격. min 에서 두 배씩 max 까지 늘리고 0.8~1.2배 지터를 준다(BrokerLink). max 는 10초였다. 브로커가 되살아난 뒤에도
     * 마지막 실패 시각에 따라 최대 12초를 더 기다려, 그동안 팔로워의 WS 가 닫혀 있었다(2026-09-25 S6 관문 W2 에서 브로커 시작 뒤 두
     * 연결이 모두 붙기까지 16.5초). 3초로 줄여 기다림을 3.6초 안으로 묶는다. 브로커가 오래 멈춰도 연결 시도는 링크마다 3초에 한 번이다.
     */
    public record Reconnect(Duration min, Duration max) {
        public Reconnect {
            if (min == null) {
                min = Duration.ofSeconds(1);
            }
            if (max == null) {
                max = Duration.ofSeconds(3);
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
        // 1초였다. 발행이 조용히 멈춘 소유 노드의 미러를 500ms 안에 거르려면 가장 최근 하트비트의 나이로 봐야 하고, 그 나이의 상한
        // (mirrorMaxSilence 400ms)이 간격의 두 배를 넘어야 살아 있는 소유 노드의 미러를 쓴다(2026-09-25 S6 관문 W3·W4).
        if (ownerHeartbeat == null) {
            ownerHeartbeat = Duration.ofMillis(200);
        }
        if (mirrorMaxSilence == null) {
            mirrorMaxSilence = Duration.ofMillis(400);
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
