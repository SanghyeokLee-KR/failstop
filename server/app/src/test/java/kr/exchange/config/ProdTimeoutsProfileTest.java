package kr.exchange.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 카오스용 prod-timeouts 프로파일이 운영 기본 풀의 세션 상한을 그대로 옮긴다(ADR 0034 S1).
 *
 * 값이 갈리면 카오스가 운영과 다른 잠금 대기 조건에서 돌아 정산 재시도·강등 판정이 운영을 대변하지 못한다. dev 문서보다
 * 뒤에 있어야 dev,chaos-monkey,prod-timeouts 조합에서 dev 의 같은 키를 덮는다.
 */
class ProdTimeoutsProfileTest {

    private static final String INIT_SQL = "spring.datasource.hikari.connection-init-sql";

    @Test
    void prodTimeoutsMirrorsProdSessionLimitsAndOverridesDev() {
        List<Map<String, Object>> docs = documents();
        int dev = indexOf(docs, "dev");
        int prod = indexOf(docs, "prod");
        int prodTimeouts = indexOf(docs, "prod-timeouts");

        Object prodInit = dig(docs.get(prod), INIT_SQL.split("\\."));
        assertNotNull(prodInit, "prod 문서에 " + INIT_SQL + " 가 없다");
        assertTrue(String.valueOf(prodInit).contains("lock_timeout"), "prod 세션 상한에 lock_timeout 이 없다");
        assertEquals(prodInit, dig(docs.get(prodTimeouts), INIT_SQL.split("\\.")),
                "prod-timeouts 의 세션 상한이 prod 와 다르다");
        assertTrue(prodTimeouts > dev, "prod-timeouts 문서가 dev 문서보다 앞에 있어 dev 값에 덮인다");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents() {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (InputStream in = ProdTimeoutsProfileTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml 을 클래스패스에서 찾지 못했다");
            for (Object doc : new Yaml().loadAll(in)) {
                docs.add(doc instanceof Map ? (Map<String, Object>) doc : Map.of());
            }
        } catch (Exception e) {
            throw new IllegalStateException("application.yml 파싱 실패", e);
        }
        return docs;
    }

    private static int indexOf(List<Map<String, Object>> docs, String profile) {
        for (int i = 0; i < docs.size(); i++) {
            if (profile.equals(dig(docs.get(i), "spring", "config", "activate", "on-profile"))) {
                return i;
            }
        }
        throw new AssertionError(profile + " 프로파일 문서가 없다");
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
