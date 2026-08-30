package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관리 평면이 봇 거버너와 물리 분리된 배치를 재현한다(관리자 설계 §0).
 *
 * SimGovernanceIntegrationTest가 거버너 빈을 @TestConfiguration으로 주입해 동거 배치를
 * 보는 것과 짝이다. 여기서는 그 빈을 두지 않으므로 컨트롤러가 큐 경로를 탄다. 실배치가
 * 이쪽인데 테스트가 동거만 보고 있어서 큐 배선과 복구 판정이 전부 무커버리지였다.
 * 라이브 수동 확인은 CI에서 재생되지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminSplitPlaneIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("exchange.admin.enabled", () -> "true");
        registry.add("exchange.admin.mtls.trusted-forwarding", () -> "true");
        registry.add("exchange.admin.rate-limit.capacity", () -> "1000000");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @Autowired AdminOperatorSeeder operatorSeeder;

    @BeforeAll
    void seed() {
        operatorSeeder.ensureOperator("op-split", "분리 배치 운영자",
                Set.of(AdminRole.BOT_OPS, AdminRole.VIEWER));
    }

    @Test
    void rateQueuesWhenGovernorAbsent() throws Exception {
        String s = session("op-split");
        ResponseEntity<String> r = put("/admin/v1/sim/rate",
                "{\"operator_id\":\"op-split\",\"target_ops\":\"77\"}", s);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());

        assertEquals("queued", body.path("apply_path").asText(), "거버너 부재면 큐 경로다");
        assertFalse(body.path("applied").asBoolean(),
                "applied는 이 응답 시점에 봇에 반영됐는가다. 큐 적재는 아직 아니다");
        long id = body.path("queued_command_id").asLong();
        assertTrue(id > 0, "큐 명령 id가 있어야 추적이 된다");

        // 봇 JVM이 집어 갈 지시가 실제로 표에 남았는가. 응답만 보면 발행 실패를 못 잡는다.
        String row = jdbc.sql("SELECT action || '=' || payload || ':' || state "
                        + "FROM sim_commands WHERE id = ?")
                .param(id).query(String.class).single();
        assertEquals("target_ops=77.0:PENDING", row);
    }

    @Test
    void scenarioQueuedResponseOmitsValuesTheAdminPlaneCannotKnow() throws Exception {
        String s = session("op-split");
        ResponseEntity<String> r = post("/admin/v1/sim/scenarios/spike/activate",
                "{\"operator_id\":\"op-split\"}", s);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());

        assertEquals("queued", body.path("apply_path").asText());
        assertTrue(body.path("queued_command_id").asLong() > 0);
        // 종전에는 baseOps=0.0에서 파생한 target_ops=0.0을 응답과 감사에 남겼다. 봇이 실제로
        // 적용하는 값은 다르다(실측 base 10.0 target 100.0). 없는 값은 비운다.
        assertTrue(body.path("target_ops").isMissingNode(), "모르는 값을 내지 않는다");
        assertTrue(body.path("base_ops").isMissingNode(), "모르는 값을 내지 않는다");
        assertTrue(body.path("markets").isMissingNode(), "빈 배열도 0개 적용이라는 주장이다");
        assertTrue(body.path("clamped").isMissingNode(), "캡 미도달도 주장이다");
        // 프리셋 정의값은 거버너 없이도 정확하다. 요청한 프리셋이 무엇인가의 서술이다.
        assertFalse(body.path("auto_balance").asBoolean());
        assertEquals("0.60", body.path("target_net_bias").asText());
    }

    @Test
    void recoveryVerdictCountsViolationsOutsideTheReturnedWindow() throws Exception {
        String s = session("op-split");
        // 실행 순서가 보장되지 않는다. 다른 테스트가 남긴 행이 판정을 바꾸므로 각자 비운다.
        jdbc.sql("DELETE FROM recovery_runs").update();
        jdbc.sql("DELETE FROM harness.failover_runs").update();

        // 표가 비면 통과가 아니라 미판정이다. 0행 대조로 통과를 내면 공허 통과다.
        assertEquals("UNKNOWN", verdict(s, 20).path("replay_deterministic").asText());

        for (int i = 0; i < 5; i++) {
            insertRun(true, "ok-" + i);
        }
        JsonNode clean = verdict(s, 20);
        assertEquals("PASS", clean.path("replay_deterministic").asText());
        // failover_runs가 비어 있으므로 overall은 UNKNOWN이다. 재생 축만 통과했다고
        // 전체 통과를 내면 페일오버를 한 번도 안 본 스택이 PASS가 된다.
        assertEquals("UNKNOWN", clean.path("failover_ok").asText());
        assertEquals("UNKNOWN", clean.path("overall").asText());

        // 창 밖 위반. limit=1이면 배열에는 이 행이 안 나오지만 판정은 표 전체를 센다.
        // 종전 코드는 응답 배열을 세서 이 상황을 PASS로 냈다.
        insertRun(false, "bad");
        for (int i = 0; i < 3; i++) {
            insertRun(true, "after-" + i);
        }
        JsonNode v = verdict(s, 1);
        assertEquals("all", v.path("scope").asText());
        assertEquals(9, v.path("replay_runs").asInt(), "판정은 창이 아니라 표 전체다");
        assertEquals(1, v.path("replay_mismatched").asInt());
        assertEquals("FAIL", v.path("replay_deterministic").asText());
        assertEquals("FAIL", v.path("overall").asText(), "FAIL이 UNKNOWN을 이긴다");
    }

    @Test
    void sinceWindowFreesTheVerdictFromPastFailures() throws Exception {
        String s = session("op-split");
        // 운영에서 failover_runs는 append-only 누적이고 비우는 훅이 없다. 실패 주행 1건이
        // 남으면 진입 조건 "복구 후 overall=PASS"가 수동 DB 삭제 없이는 달성 불가가 된다.
        // since 창이 그것을 푼다. 테스트는 실행 순서에 기대지 않으려고 두 표를 다 비운다.
        jdbc.sql("DELETE FROM recovery_runs").update();
        jdbc.sql("DELETE FROM harness.failover_runs").update();
        // 시각을 명시한다. 벽시계에 기대면 두 삽입이 같은 밀리초에 들어가 창이 갈리지 않는다.
        insertRunAt(false, "old-bad", "-1 hour");
        insertRunAt(true, "new-ok", "0 second");
        // 포맷 문자열로 만들지 않는다. PG to_char의 OF는 분 단위를 상황에 따라 생략해
        // ISO-8601로 항상 파싱되지 않는다. 값을 그대로 받아 Java가 직렬화한다.
        // UTC로 정규화한다. +09:00의 '+'는 TestRestTemplate이 URI 템플릿을 다시 인코딩하며
        // %252B로 이중 인코딩돼 서버에 도달하지 않는다. Z 표기는 그 문자를 만들지 않는다.
        String cut = jdbc.sql("SELECT now() - interval '30 minutes'")
                .query(java.time.OffsetDateTime.class).single()
                .withOffsetSameInstant(java.time.ZoneOffset.UTC).toString();

        assertEquals("FAIL", verdict(s, 20).path("overall").asText(), "창이 없으면 전체다");

        JsonNode scoped = verdictSince(s, cut);
        assertEquals("since", scoped.path("scope").asText());
        assertEquals(1, scoped.path("replay_runs").asInt());
        assertEquals(0, scoped.path("replay_mismatched").asInt(), "창 이전 실패는 제외된다");

        // 창은 배열에도 걸린다. 집계만 좁히면 판정은 UNKNOWN인데 표에는 창 밖 실패 행이
        // 그대로 떠서 화면과 판정이 갈린다. 이번 라운드가 없앤 결함이 형태만 바꿔 남는다.
        JsonNode rows = bodySince(s, cut).path("recovery_runs");
        assertEquals(1, rows.size(), "배열도 창을 따라야 한다");
        assertEquals("new-ok", rows.path(0).path("fp_replay").asText());

        // 오타 하나가 조용히 창을 넓혀 판정 범위를 바꾸면 안 된다.
        assertEquals(400, get("/admin/v1/ha/recovery?since=yesterday", s).getStatusCode().value());
    }

    @Test
    void failoverAxisSeparatesBreachFromMissingMeasurement() throws Exception {
        String s = session("op-split");
        jdbc.sql("DELETE FROM harness.failover_runs").update();
        jdbc.sql("DELETE FROM recovery_runs").update();
        insertRun(true, "replay-ok");

        // 정상 주행 하나. 재생 축도 통과이므로 전체가 PASS다.
        insertFailover("E4-ok", "3.200", 0L, "PASS");

        // 행 방출도 함께 건다. SELECT하고 매퍼가 버리면 화면이 그 값을 못 본다.
        // epoch 단조는 페일오버 판정의 축이라 행에 있으면 응답에도 있어야 한다.
        JsonNode row = failoverRow(s);
        assertEquals("E4-ok", row.path("experiment_id").asText());
        assertEquals("node-old", row.path("old_leader").asText());
        assertEquals("node-new", row.path("new_leader").asText());
        assertEquals(41, row.path("old_epoch").asInt());
        assertEquals(42, row.path("new_epoch").asInt());
        assertEquals("3.200", row.path("rto_seconds").asText(), "NUMERIC은 문자열 규약이다");

        JsonNode ok = verdict(s, 20);
        assertEquals("PASS", ok.path("failover_ok").asText());
        assertEquals("PASS", ok.path("overall").asText());
        assertEquals("30.000", ok.path("rto_target_seconds").asText(),
                "임계도 실측과 같은 문자열이라야 소비자가 형을 넘나들지 않는다");

        // RTO 경계. 30s "이상"이 위반이다(API§7-4). 경계값을 넣어야 >= 를 > 로 바꾸는
        // 변이가 잡힌다. 31초만 넣으면 그 변이가 살아남는다.
        insertFailover("E4-edge", "30.000", 0L, "PASS");
        assertEquals(1, verdict(s, 20).path("rto_breach").asInt(), "30.000은 경계이자 위반이다");

        insertFailover("E4-slow", "31.000", 0L, "PASS");
        JsonNode breach = verdict(s, 20);
        assertEquals(2, breach.path("rto_breach").asInt());
        assertEquals("FAIL", breach.path("failover_ok").asText());
        assertEquals("FAIL", breach.path("overall").asText());

        // 계측 실패. rpo_records가 NULL인 것은 위반이 아니라 미판정이다. 통과로 세면
        // 계측이 실패한 주행이 성공으로 보인다.
        jdbc.sql("DELETE FROM harness.failover_runs").update();
        insertFailover("E4-nopo", "3.200", null, "PASS");
        JsonNode missing = verdict(s, 20);
        assertEquals(0, missing.path("rpo_breach").asInt(), "NULL은 위반이 아니다");
        assertEquals(1, missing.path("rpo_missing").asInt());
        assertEquals("UNKNOWN", missing.path("failover_ok").asText());

        // 행 자체의 verdict. RTO·RPO가 정상이어도 하네스가 FAIL을 박았으면 실패다.
        // 하네스는 무결성 clean·epoch 단조·체결 재개를 함께 보고 그 결론을 여기 남긴다.
        jdbc.sql("DELETE FROM harness.failover_runs").update();
        insertFailover("E4-dirty", "3.200", 0L, "FAIL");
        JsonNode rowFail = verdict(s, 20);
        assertEquals(0, rowFail.path("rto_breach").asInt());
        assertEquals(0, rowFail.path("rpo_breach").asInt());
        assertEquals(1, rowFail.path("row_verdict_fail").asInt());
        assertEquals("FAIL", rowFail.path("failover_ok").asText(),
                "RTO만 정상인 실패 주행이 통과하면 안 된다");

        jdbc.sql("DELETE FROM harness.failover_runs").update();
    }

    private void insertFailover(String experimentId, String rto, Long rpo, String verdict) {
        jdbc.sql("INSERT INTO harness.failover_runs (experiment_id, old_leader, new_leader, "
                        + "old_epoch, new_epoch, rto_seconds, rpo_records, verdict) "
                        + "VALUES (?, 'node-old', 'node-new', 41, 42, CAST(? AS numeric), ?, ?)")
                .params(experimentId, rto, rpo, verdict).update();
    }

    private JsonNode failoverRow(String bearer) throws Exception {
        ResponseEntity<String> r = get("/admin/v1/ha/recovery?limit=1", bearer);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        return objectMapper.readTree(r.getBody()).path("failover_runs").path(0);
    }

    private void insertRun(boolean matched, String tag) {
        jdbc.sql("INSERT INTO recovery_runs (market_id, snapshot_id, replayed_from_seq, "
                        + "replayed_to_seq, fp_replay, fp_live, matched, duration_ms) "
                        + "VALUES (0, NULL, 1, 2, ?, ?, ?, 1)")
                .params(tag, tag, matched).update();
    }

    /** created_at을 now() 기준 상대 시각으로 박는다. 창 판정을 벽시계에 맡기지 않는다. */
    private void insertRunAt(boolean matched, String tag, String interval) {
        jdbc.sql("INSERT INTO recovery_runs (market_id, snapshot_id, replayed_from_seq, "
                        + "replayed_to_seq, fp_replay, fp_live, matched, duration_ms, created_at) "
                        + "VALUES (0, NULL, 1, 2, ?, ?, ?, 1, now() + CAST(? AS interval))")
                .params(tag, tag, matched, interval).update();
    }

    private JsonNode verdict(String bearer, int limit) throws Exception {
        ResponseEntity<String> r = get("/admin/v1/ha/recovery?limit=" + limit, bearer);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        return objectMapper.readTree(r.getBody()).path("verdict");
    }

    private JsonNode verdictSince(String bearer, String since) throws Exception {
        return bodySince(bearer, since).path("verdict");
    }

    private JsonNode bodySince(String bearer, String since) throws Exception {
        ResponseEntity<String> r = get("/admin/v1/ha/recovery?since=" + since, bearer);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        return objectMapper.readTree(r.getBody());
    }

    private String session(String cn) throws Exception {
        ResponseEntity<String> issued = exchange("/admin/v1/session", HttpMethod.POST, null, cn, null);
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("token").asText();
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return exchange(path, HttpMethod.GET, null, "op-split", bearer);
    }

    private ResponseEntity<String> put(String path, String body, String bearer) {
        return exchange(path, HttpMethod.PUT, body, "op-split", bearer);
    }

    private ResponseEntity<String> post(String path, String body, String bearer) {
        return exchange(path, HttpMethod.POST, body, "op-split", bearer);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body,
                                            String cn, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cn != null) {
            headers.set("X-Operator-Cn", cn);
        }
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }
}
