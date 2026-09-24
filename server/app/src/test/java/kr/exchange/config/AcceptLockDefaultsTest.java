package kr.exchange.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 접수 락 대기 상한과 append lock_timeout(S4 설계 4-12, API 설계 §2-5-b). 코드 기본값은 0(끔)이라 시험과 다른 배선은 켜지
 * 않고, dev·prod 문서가 2000ms·1000ms 로 켠다. 초과는 429(확정 미접수)나 409 order_in_progress 이고 503 이 아니다. 리스 획득
 * 잠금 대기 상한(DB 설계 §5.4-c 3)은 코드 기본 1000ms 이고 틱보다 짧아야 한다.
 */
class AcceptLockDefaultsTest {

    private static final Set<String> ENABLED = Set.of("dev", "prod");

    @Test
    void unsetValuesDefaultToZero() {
        EngineProperties props = new EngineProperties(null, null, null, 0, null, null, null);
        assertEquals(Duration.ZERO, props.acceptLockWait());
        assertEquals(Duration.ZERO, props.acceptLockTimeout());
        assertEquals(Duration.ofMillis(1000), props.leaseAcquireLockTimeout());
    }

    @Test
    void negativeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EngineProperties(null, null, null, 0, null, Duration.ofMillis(-1), null));
        assertThrows(IllegalArgumentException.class,
                () -> new EngineProperties(null, null, null, 0, null, null, Duration.ofMillis(-1)));
    }

    /** 획득 상한이 0 이면 PostgreSQL 에서 상한 없음이고, 틱 이상이면 막힌 획득이 틱 스레드를 한 틱 넘게 묶는다. */
    @Test
    void acquireLockTimeoutMustBePositiveAndShorterThanTheTick() {
        assertThrows(IllegalArgumentException.class, () -> new EngineProperties(null, Duration.ofSeconds(2), null, 0,
                null, null, null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new EngineProperties(null, Duration.ofSeconds(2), null, 0,
                null, null, null, Duration.ofSeconds(2)));
        assertEquals(Duration.ofMillis(1500), new EngineProperties(null, Duration.ofSeconds(2), null, 0, null, null,
                null, Duration.ofMillis(1500)).leaseAcquireLockTimeout());
    }

    /** dev·prod 문서가 두 값을 켜고, 다른 프로파일 문서는 켜지 않는다. */
    @Test
    void devAndProdTurnTheBoundsOn() {
        List<Map<String, Object>> docs = documents();
        assertTrue(docs.size() > 1, "application.yml 문서를 읽지 못했다");
        int enabled = 0;
        for (int i = 0; i < docs.size(); i++) {
            Object engine = dig(docs.get(i), "exchange", "engine");
            Object profile = dig(docs.get(i), "spring", "config", "activate", "on-profile");
            if (ENABLED.contains(String.valueOf(profile))) {
                assertTrue(engine instanceof Map<?, ?>, profile + " 문서에 exchange.engine 이 없다");
                Map<?, ?> map = (Map<?, ?>) engine;
                assertEquals("2000ms", String.valueOf(map.get("accept-lock-wait")), profile + " accept-lock-wait");
                assertEquals("1000ms", String.valueOf(map.get("accept-lock-timeout")), profile + " accept-lock-timeout");
                enabled++;
                continue;
            }
            if (!(engine instanceof Map<?, ?> map)) {
                continue;
            }
            for (String key : new String[] {"accept-lock-wait", "accept-lock-timeout"}) {
                Object value = map.get(key);
                assertTrue(value == null, "application.yml 문서 " + i + "(" + profile + ") 가 exchange.engine." + key
                        + " 를 따로 정했다: " + value);
            }
        }
        assertEquals(2, enabled, "dev·prod 문서를 모두 찾지 못했다");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents() {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (InputStream in = AcceptLockDefaultsTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml 을 클래스패스에서 찾지 못했다");
            for (Object doc : new Yaml().loadAll(in)) {
                docs.add(doc instanceof Map ? (Map<String, Object>) doc : Map.of());
            }
        } catch (Exception e) {
            throw new IllegalStateException("application.yml 파싱 실패", e);
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    private static Object dig(Map<String, Object> root, String... path) {
        Object cur = root;
        for (String key : path) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(key);
        }
        return cur;
    }
}
