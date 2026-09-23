package kr.exchange.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 접수 락 대기 상한과 append lock_timeout 은 기본으로 꺼져 있다(ADR 0034 S1, 결정 11).
 *
 * 켜면 초과 요청이 503 을 받고 max_fails=1 인 nginx 쓰기 업스트림이 그 503 하나로 노드를 빼 한 마켓의 정체가 모든 마켓의
 * 쓰기 중단으로 번진다. 값은 인그레스 설정과 함께 S3·S4 에서 정한다. 그 전에 기본값이나 프로파일 값이 조용히 바뀌면
 * 단일 리더 접수가 오늘과 달라지므로 여기서 고정한다.
 */
class AcceptLockDefaultsTest {

    private static final String[] KEYS = {"accept-lock-wait", "accept-lock-timeout", "acceptLockWait",
            "acceptLockTimeout"};

    @Test
    void unsetValuesDefaultToZero() {
        EngineProperties props = new EngineProperties(null, null, null, 0, null, null, null);
        assertEquals(Duration.ZERO, props.acceptLockWait());
        assertEquals(Duration.ZERO, props.acceptLockTimeout());
    }

    @Test
    void negativeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EngineProperties(null, null, null, 0, null, Duration.ofMillis(-1), null));
        assertThrows(IllegalArgumentException.class,
                () -> new EngineProperties(null, null, null, 0, null, null, Duration.ofMillis(-1)));
    }

    /** 어떤 프로파일 문서도 두 값을 켜지 않는다. 켜는 것은 S3·S4 의 결정이다. */
    @Test
    void noProfileTurnsTheBoundsOn() {
        List<Map<String, Object>> docs = documents();
        assertTrue(docs.size() > 1, "application.yml 문서를 읽지 못했다");
        for (int i = 0; i < docs.size(); i++) {
            Object engine = dig(docs.get(i), "exchange", "engine");
            if (!(engine instanceof Map<?, ?> map)) {
                continue;
            }
            for (String key : KEYS) {
                Object value = map.get(key);
                assertTrue(value == null || "0".equals(String.valueOf(value)) || "0s".equals(String.valueOf(value))
                                || "0ms".equals(String.valueOf(value)),
                        "application.yml 문서 " + i + " 가 exchange.engine." + key + " 를 켰다: " + value);
            }
        }
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
