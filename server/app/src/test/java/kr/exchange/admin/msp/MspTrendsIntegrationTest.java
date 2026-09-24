package kr.exchange.admin.msp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 요약 추세(설계 4-7)를 실제 사건 표와 스텁 Prometheus 로 계산한다. 활성 경보는 각 시각에 열린 사건 수, 사건은 칸별
 * 시작 수이고, Prometheus 계열은 기록 규칙 이름 하나를 두 구간에 걸쳐 한 번 읽는다.
 */
@Testcontainers
class MspTrendsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private AiRig.Sources src;
    private AiRig rig;

    @BeforeAll
    static void migrate() {
        AiRig.migrate(POSTGRES);
    }

    @BeforeEach
    void start() throws IOException {
        src = new AiRig.Sources();
        rig = new AiRig(POSTGRES, src, MspProperties.Llm.Provider.OPENAI_COMPATIBLE, p -> { });
    }

    @AfterEach
    void stop() {
        rig.close();
        src.close();
    }

    private void incident(String fingerprint, Instant started, Instant resolved) {
        rig.jdbc.sql("""
                        INSERT INTO ops_incidents (fingerprint, alertname, severity, labels, summary, started_at,
                                                   first_seen_at, last_seen_at, resolved_at, status)
                        VALUES (?, 'X', 'warning', '{}', 's', ?, ?, ?, ?, ?)
                        """)
                .params(fingerprint, AiControl.at(started), AiControl.at(started), AiControl.at(started),
                        AiControl.at(resolved), resolved == null ? "OPEN" : "RESOLVED")
                .update();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 활성_경보와_사건은_사건_표에서_세고_이전_구간과_비교한다() {
        MspTrends trends = new MspTrends(rig.props, rig.prometheus, rig.incidents, rig.cache);
        Instant end = MspTrends.Range.H24.end(rig.clock.instant());
        // 이전 구간에 두 건(하나는 지금 구간까지 열림), 지금 구간에 한 건
        incident("d000000000000001", end.minus(Duration.ofHours(30)), end.minus(Duration.ofHours(29)));
        incident("d000000000000002", end.minus(Duration.ofHours(26)), null);
        incident("d000000000000003", end.minus(Duration.ofHours(3)).plusSeconds(1), end.minus(Duration.ofHours(1)));

        Map<String, Object> section = trends.section(MspTrends.Range.H24);

        assertEquals(720L, section.get("step_seconds"));
        Map<String, Object> series = (Map<String, Object>) section.get("series");
        Map<String, Object> incidents = (Map<String, Object>) ((Map<String, Object>) series.get("incidents")).get("data");
        assertEquals(1L, incidents.get("current"));
        assertEquals(2L, incidents.get("previous"));
        assertEquals(-1L, incidents.get("delta"));
        List<List<Object>> buckets = (List<List<Object>>) incidents.get("points");
        assertEquals(24, buckets.size());
        assertEquals(1L, buckets.get(21).get(1), "끝 3시간 전 직후에 시작한 사건은 22번째 칸이다");

        Map<String, Object> alerts = (Map<String, Object>) ((Map<String, Object>) series.get("active_alerts")).get("data");
        List<List<Object>> points = (List<List<Object>>) alerts.get("points");
        assertEquals(120, points.size());
        assertEquals(1L, points.get(0).get(1), "지금 구간 처음에는 열린 사건 하나가 이어진다");
        assertEquals(2L, points.get(109).get(1), "끝 2시간 전에는 두 건이 열려 있다");
        assertEquals("1.1", alerts.get("current"), "점 120개 가운데 9개가 두 건이다");
        assertTrue(Double.parseDouble((String) alerts.get("previous")) > 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void Prometheus_계열은_같은_격자_안에서_한_번만_읽고_실패는_칸에_남긴다() {
        MspTrends trends = new MspTrends(rig.props, rig.prometheus, rig.incidents, rig.cache);
        src.prometheus.handler = r -> r.path().equals("/api/v1/query_range")
                ? StubSource.Response.status(503, "{\"status\":\"error\",\"error\":\"busy\"}")
                : StubSource.Response.json("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");

        Map<String, Object> first = (Map<String, Object>) trends.section(MspTrends.Range.H6).get("series");
        trends.section(MspTrends.Range.H6);

        assertEquals(Map.of("state", "UNAVAILABLE", "reason", "query_failed"), first.get("p95_seconds"));
        assertEquals(3, src.prometheus.requests("/api/v1/query_range").size(), "계열 셋을 한 번씩만 읽었다");
        assertTrue(src.prometheus.requests("/api/v1/query_range").stream()
                .allMatch(r -> r.param("query").startsWith("exchange:msp_")), "기록 규칙 이름만 질의한다");

        MspTrends none = new MspTrends(rig.props, null, rig.incidents, rig.cache);
        Map<String, Object> disconnected = (Map<String, Object>) none.section(MspTrends.Range.H1).get("series");
        assertEquals(Map.of("state", "NOT_CONNECTED", "source", "prometheus"), disconnected.get("availability"));
        assertEquals("READY", ((Map<String, Object>) disconnected.get("incidents")).get("state"),
                "사건 추세는 Prometheus 와 무관하다");
    }
}
