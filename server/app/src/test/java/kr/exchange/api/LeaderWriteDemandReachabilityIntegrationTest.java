package kr.exchange.api;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.engine.LeaderStallDetector;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 리더 쓰기 수요 계측의 도달 가능성 고정(DB 설계 §5.4 좀비 리더 판정 ② 축).
 *
 * 판정의 전제는 '요청 풀이 고갈된 상태에서도 쓰기 수요가 관측된다'는 것이다. 이 전제가 깨지면
 * 판정이 매 표본 초기화돼 강등이 영원히 발동하지 않는다. 그 함정이 실제로 있었다. 계측을 저널
 * append에 두면, 좀비 상태에서는 그 앞의 인증 필터(api_keys 조회)·게이트웨이(users 조회)가 먼저
 * 커넥션을 얻지 못해 실패하므로 append에 도달조차 하지 못한다.
 *
 * 그래서 수동 recordAttempt()가 아니라 실제 HTTP 주문 요청을 포화된 풀에 흘려 계측 지점이
 * 인증·DB보다 앞서는지를 직접 본다. 대조로 완료 축이 증가하지 않음도 함께 고정한다. 둘이 같이
 * 성립해야 [수요 있음 + 완료 0]이라는 정지 판정이 좀비에서 실제로 성립한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderWriteDemandReachabilityIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;

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
        // 소형 풀 + 짧은 획득 타임아웃으로 고갈을 결정적·즉시로 만든다. 실 카오스의 지연 폭주를
        // 커넥션 직접 점유로 치환한 것이고, 관측 대상(고갈 시 요청 경로 실패)은 동일하다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    DemoKeySeeder keySeeder;
    @Autowired
    LeaderWriteProgress writeProgress;
    @Autowired
    @Qualifier("dataSource")
    HikariDataSource requestPool;

    DemoKeySeeder.IssuedKey tradeKey;

    private final List<Connection> held = new ArrayList<>();

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        long user = bootstrap.user("stall@api.test");
        bootstrap.userAccounts(user, KRW);
        bootstrap.userAccounts(user, BTC);
        bootstrap.deposit(user, KRW, new BigDecimal("1000000000"));
        tradeKey = keySeeder.issue(user, "TRADE", "stall-trade");
        // 접수는 리더 확정 후에만 열린다. 승격 전에 주문을 쏘면 503이라 정상 경로 대조가 무의미하다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
    }

    @AfterEach
    void release() throws Exception {
        for (Connection c : held) {
            c.close();
        }
        held.clear();
    }

    @Test
    void writeDemandIsCountedEvenWhenRequestPoolIsExhausted() throws Exception {
        String path = "/api/v1/orders";
        byte[] body = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\","
                + "\"price\":\"84000000\",\"volume\":\"0.001\","
                + "\"client_order_id\":\"stall-probe-1\"}").getBytes(StandardCharsets.UTF_8);

        // 요청 풀 전량 점유. 이 시점부터 요청 경로의 모든 DB 조회가 커넥션 획득에서 실패한다.
        for (int i = 0; i < requestPool.getMaximumPoolSize(); i++) {
            held.add(requestPool.getConnection());
        }
        assertEquals(requestPool.getMaximumPoolSize(),
                requestPool.getHikariPoolMXBean().getActiveConnections(),
                "요청 풀이 포화되지 않았다");

        long attemptsBefore = writeProgress.attempts();
        long completionsBefore = writeProgress.completions();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", TestSigner.bearerForBody(
                tradeKey.accessKey(), tradeKey.secretKey(), "POST", path, body));
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        // 요청은 실패해야 한다. 성공했다면 풀이 실제로 고갈되지 않은 것이라 전제가 무너진다.
        assertTrue(response.getStatusCode().isError(),
                "포화 상태에서 주문이 성공했다: " + response.getStatusCode());
        assertEquals(attemptsBefore + 1, writeProgress.attempts(),
                "풀 고갈 상태에서 쓰기 수요가 관측되지 않았다. 계측 지점이 인증·DB 뒤에 있다");
        assertEquals(completionsBefore, writeProgress.completions(),
                "요청 경로가 아무것도 끝내지 못했는데 완료가 증가했다");
    }

    /**
     * 라이브 좀비의 실제 모습(커넥션은 남는데 아무것도 끝나지 않음)을 게이트웨이 계층에서 재현해
     * 판정기가 강등을 내는지 본다. 풀 포화를 쓰지 않는 것이 요점이다. 카오스 라이브의 좀비는 CPU
     * 폭주로 죽어 있었고 커넥션은 계속 반납되고 있었다.
     */
    @Test
    void stalledRequestPathStandsDownWithoutPoolExhaustion() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 임계만 줄이고 시계는 실제 단조 시계를 쓴다. 판정기 내부 시계 주입은 같은 패키지 전용이라
        // 여기서는 공개 생성자 그대로, 즉 프로덕션과 동일한 경로로 검증한다.
        Duration standdownAfter = Duration.ofMillis(600);
        LeaderStallDetector detector =
                new LeaderStallDetector(requestPool, writeProgress, standdownAfter, registry);

        // 풀은 멀쩡하다. 판정이 점유율에 걸리면 여기서 강등이 나오지 않는다.
        assertTrue(requestPool.getHikariPoolMXBean().getActiveConnections()
                        < requestPool.getMaximumPoolSize(),
                "풀이 이미 포화라 이 시나리오의 전제가 성립하지 않는다");
        assertTrue(detector.serviceable(), "기준 표본은 정상이어야 한다");

        // 완료가 나오지 않는 쓰기 요청. 인증 앞 계측이라 수요만 오르고 완료는 오르지 않는다.
        // 서명을 만들지 않아 verify가 DB 이전에 거절할 수 있으므로, 완료 상쇄를 확실히 없애기
        // 위해 계측 지점과 동일한 경로(필터 진입)만 타도록 Authorization을 비운다.
        long completionsBefore = writeProgress.completions();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>("{}".getBytes(StandardCharsets.UTF_8), headers), String.class);
        assertEquals(completionsBefore + 1, writeProgress.completions(),
                "인증 단계 확정 거절은 완료로 상쇄돼야 한다(정상 노드 오탐 방지)");

        // 위 요청의 수요·완료 증분을 한 표본으로 흡수한다. 완료율이 건강하니 정상 표본이다.
        assertTrue(detector.serviceable(), "완료가 있었던 표본에서 강등 판정이 나면 안 된다");

        // 이제 처리율이 붕괴한 상태를 만든다. 완료가 0이 아니라 희박하다는 점이 핵심이다.
        for (int i = 0; i < 50; i++) {
            writeProgress.recordAttempt();
        }
        writeProgress.recordCompletion();
        assertTrue(detector.serviceable(), "붕괴 첫 표본은 누적 시작점일 뿐이다");
        Thread.sleep(standdownAfter.toMillis() + 200);

        assertFalse(detector.serviceable(),
                "커넥션이 남아 있고 완료가 간헐적으로 나와도 완료율이 붕괴하면 강등해야 한다");
        assertEquals(1.0, registry.get("exchange.leader.standdown").counter().count());
    }

    @Test
    void writeDemandAndCompletionBothAdvanceOnHealthyPool() {
        String path = "/api/v1/orders";
        byte[] body = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\","
                + "\"price\":\"84000000\",\"volume\":\"0.001\","
                + "\"client_order_id\":\"healthy-probe-1\"}").getBytes(StandardCharsets.UTF_8);

        long attemptsBefore = writeProgress.attempts();
        long completionsBefore = writeProgress.completions();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", TestSigner.bearerForBody(
                tradeKey.accessKey(), tradeKey.secretKey(), "POST", path, body));
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "정상 풀에서 주문이 실패했다: " + response.getStatusCode() + " " + response.getBody());
        assertTrue(writeProgress.attempts() > attemptsBefore, "쓰기 수요가 관측되지 않았다");
        // 완료가 함께 오르는 것이 헛페일오버 방지의 근거다. 정상 노드는 표본마다 누적이 초기화된다.
        assertTrue(writeProgress.completions() > completionsBefore,
                "정상 처리인데 요청 경로 완료가 관측되지 않았다");
    }
}
