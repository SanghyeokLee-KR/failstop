package kr.exchange.api.ws.relay;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 중계 설정의 기본값(S6 설계 4-12). 2026-09-25 S6 관문 주행 뒤 세 값을 바꿨다. 하트비트 간격 1초에서 200ms, 미러를 쓰는 가장 최근
 * 하트비트의 최대 나이 400ms(새 키), 재연결 간격 상한 10초에서 3초다. 기본값끼리 기동 검사를 통과해야 한다.
 */
class WsRelayPropertiesTest {

    private static WsRelayProperties defaults() {
        return new WsRelayProperties(null, null, null, null, 0, null, null, null, null, null, null, null);
    }

    @Test
    void defaultsAfterTheGateRun() {
        WsRelayProperties p = defaults();
        assertEquals(Duration.ofMillis(200), p.ownerHeartbeat());
        assertEquals(Duration.ofMillis(400), p.mirrorMaxSilence());
        assertEquals(Duration.ofSeconds(1), p.reconnect().min());
        assertEquals(Duration.ofSeconds(3), p.reconnect().max());
        assertEquals(Duration.ofSeconds(2), p.publishMaxAge());
    }

    @Test
    void defaultsPassTheStartupChecks() {
        WsRelayProperties p = defaults();
        OwnerBeats.requireReachableInterval(p.ownerHeartbeat(), p.publishMaxAge());
        OwnerBeats.requireFreshInterval(p.ownerHeartbeat(), p.mirrorMaxSilence());
        // 지터 상한(1.2배)을 넣어도 브로커가 되살아난 뒤 다음 시도까지 4초를 넘지 않는다(W2 의 15초 판정에 브로커 기동 약 8초를 남긴다).
        assertTrue(p.reconnect().max().toMillis() * 12 / 10 <= 4_000, "재연결 상한이 너무 길다: " + p.reconnect().max());
    }
}
