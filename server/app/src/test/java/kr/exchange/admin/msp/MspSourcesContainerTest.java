package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하네스와 같은 핀의 실제 Prometheus·Alertmanager 로 클라이언트를 확인한다. 명명 질의가 구문 오류 없이
 * 평가되는지, 사일런스 생성·조회·종료와 상태 응답의 시작 시각이 스텁과 같은 모양인지 본다.
 */
@Testcontainers
class MspSourcesContainerTest {

    @Container
    static final GenericContainer<?> PROMETHEUS = new GenericContainer<>("prom/prometheus:v3.13.0")
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200));

    @Container
    static final GenericContainer<?> ALERTMANAGER = new GenericContainer<>("prom/alertmanager:v0.34.0")
            .withExposedPorts(9093)
            .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200));

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 명명_질의는_실제_Prometheus_에서_구문_오류_없이_평가된다() {
        ExecutorService pool = Executors.newFixedThreadPool(MspWorkers.QUERY_THREADS);
        try {
            MspProperties.Prometheus jobs = new MspProperties().getPrometheus();
            PrometheusClient client = new PrometheusClient(base(PROMETHEUS, 9090), jobs, Duration.ofSeconds(5),
                    Duration.ofSeconds(10), mapper, pool, new SourceCache(Clock.systemUTC(), new SimpleMeterRegistry()));
            for (MspQuery query : MspQuery.values()) {
                client.query(query.promql(jobs));
            }
            PrometheusClient.Snapshot snapshot = client.snapshot();
            assertEquals(Map.of(), snapshot.failures(), "모든 질의가 성공해야 한다");
            assertEquals(MspQuery.values().length, snapshot.results().size());
            for (MspQuery query : MspQuery.values()) {
                if (query.trend()) {
                    for (MspTrends.Range range : MspTrends.Range.values()) {
                        assertNull(client.range(query, range, range.end(java.time.Instant.now())).value().failure(),
                                "추세 range 질의도 실제 Prometheus 에서 구문 오류 없이 평가된다");
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 사일런스를_만들고_읽고_끝낸다() throws Exception {
        URI base = base(ALERTMANAGER, 9093);
        Instant now = Instant.now();
        String alert = "[{\"labels\":{\"alertname\":\"MspProbe\",\"severity\":\"warning\",\"node\":\"test\"},"
                + "\"annotations\":{\"summary\":\"확인용\"},\"startsAt\":\"" + now + "\"}]";
        HttpResponse<String> posted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/v2/alerts"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(alert)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, posted.statusCode(), posted.body());
        AlertmanagerClient client = new AlertmanagerClient(base, Duration.ofSeconds(5), mapper);

        AlertmanagerClient.Alert probe = Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> client.alerts().stream().filter(a -> "MspProbe".equals(a.labels().get("alertname")))
                        .findFirst().orElse(null), a -> a != null);
        assertTrue(IncidentStore.FINGERPRINT.matcher(probe.fingerprint()).matches(), probe.fingerprint());
        assertTrue(probe.silencedBy().isEmpty());
        assertTrue(client.startedAt().isBefore(Instant.now().plusSeconds(1)));

        String id = client.createSilence(probe.labels(), now, now.plus(Duration.ofMinutes(5)), "msp-test",
                "확인 · msp-incident:1");
        assertEquals("active", client.silence(id).orElseThrow().state());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> client.alerts().stream()
                .anyMatch(a -> a.fingerprint().equals(probe.fingerprint()) && a.silencedBy().contains(id)));

        client.expireSilence(id);
        assertEquals("expired", client.silence(id).orElseThrow().state());
        // 0.34.0 은 끝난 사일런스의 종료도 200 으로 받는다. 보류 해제가 먼저 조회하는 이유는 거부가 아니라 이미 끝났는지 남기기 위해서다.
        client.expireSilence(id);
        assertEquals("expired", client.silence(id).orElseThrow().state());
        assertTrue(client.silence("8e0a2c56-0000-4000-8000-00000000ffff").isEmpty());
    }

    private static URI base(GenericContainer<?> container, int port) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(port));
    }
}
