package kr.exchange.api.system;

import kr.exchange.api.common.ApiException;
import kr.exchange.engine.WriteAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 거래 상태 API 의 모양(API 설계 §4-A-7). 탐지기의 SUSPENDED·UNREACHABLE 이 공개로는 모두 SUSPENDED 이고 reason 으로 갈린다. */
class StatusControllerTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);

    private Map<String, Object> read(StatusController controller) {
        ResponseEntity<Map<String, Object>> response = controller.status(new MockHttpServletRequest());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        return response.getBody();
    }

    @Test
    void disabledNodeIsAlwaysActive() {
        Map<String, Object> body = read(new StatusController(WriteAvailability.disabled()));
        assertEquals("1.0", body.get("schema_version"));
        assertEquals(false, body.get("trading_suspended"));
        assertEquals("ACTIVE", body.get("trading_state"));
        assertNull(body.get("reason"));
        assertNull(body.get("since"));
        assertEquals(5, body.get("poll_after_seconds"));
    }

    @Test
    void unreachableAndSuspendedBothReadAsSuspendedWithTheirReason() {
        WriteAvailability w = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get, wall::get);
        StatusController controller = new StatusController(w);

        Map<String, Object> booting = read(controller);
        assertEquals(true, booting.get("trading_suspended"), "첫 탐지 전에는 쓰기를 받지 않는다");
        assertEquals("SUSPENDED", booting.get("trading_state"));
        assertEquals("database_unavailable", booting.get("reason"));
        assertEquals(wall.get(), booting.get("since"));

        w.settingRead("*", false);
        Map<String, Object> suspended = read(controller);
        assertEquals("SUSPENDED", suspended.get("trading_state"));
        assertEquals("replication_unavailable", suspended.get("reason"));
        assertEquals(wall.get(), suspended.get("checked_at"));
    }

    @Test
    void throttledPerClientIp() {
        StatusController controller = new StatusController(WriteAvailability.disabled());
        for (int i = 0; i < 120; i++) {
            controller.status(new MockHttpServletRequest());
        }
        ApiException rejected = assertThrows(ApiException.class, () -> controller.status(new MockHttpServletRequest()));
        assertEquals(429, rejected.status());
    }
}
