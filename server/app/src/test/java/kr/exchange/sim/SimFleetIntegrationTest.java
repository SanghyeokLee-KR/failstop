package kr.exchange.sim;

import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-5 봇 플릿 통합 검증. MM+TAKER 플릿을 §3 서명 HTTP(외부 관점)로 수 초 구동해
 * 체결을 만들고, 정지 후 DB 불변식을 검증한다:
 * 자산별 원장 합 0, balance_ledger_check 0행, 저널 gapless, FILLED 락 잔량 0.
 * 종료 후 봇 스레드 정지·저널 증가 중단(정지 정상성)까지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimFleetIntegrationTest {

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
        // 봇 플릿은 서버와 같은 호스트라 공개 조회가 전부 127.0.0.1 IP 버킷 하나에 계상된다
        // (§2-5). 계약 기본값 300토큰/10s는 MM 재호가·taker 도착이 합쳐지면 수 초 만에 마르고,
        // 그러면 이 테스트가 검증하려는 체결·불변식이 아니라 레이트리밋을 재는 테스트가 된다.
        // bots 프로파일이 라이브 데모에서 하는 상향을 여기서 동일하게 적용한다.
        registry.add("exchange.public.rate-limit.capacity", () -> "100000");
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    DemoKeySeeder keySeeder;
    @LocalServerPort
    int port;

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        // 현실 규모 호가 단위. AccountBootstrap 기본(1e-18)은 틱 산술에 부적합하다.
        jdbc.sql("UPDATE markets SET tick_size = 1000, lot_size = 0.0001, min_notional = 5000 "
                        + "WHERE id = ?")
                .params(KRW_BTC)
                .update();
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
    }

    @Test
    void fleetProducesTradesAndInvariantsHold() {
        SimProperties props = new SimProperties(
                "http://localhost:" + port, 42, 60, "KRW-BTC", "50000000",
                new SimProperties.Funding("1000000000", "100"),
                new SimProperties.MarketMaker(2, 10, 3, "0.01", 300),
                new SimProperties.Taker(2, 4.0, "0.001", 5, 0.2), null);
        BotSeeder seeder = new BotSeeder(jdbc, bootstrap, keySeeder);
        OpsGovernor governor = new OpsGovernor(props.targetOps());
        // 파킹 게이트 검증 겸 시드 비용 억제를 위해 타입당 풀을 작게 잡는다.
        BotFleetGovernor fleetGovernor = new BotFleetGovernor(30, 80, 300, 3);
        BotFleet fleet = new BotFleet(props, seeder, governor, fleetGovernor,
                new DbRunOrdinalSource(jdbc));

        fleet.start();
        try {
            // 재호가·취소·부분체결 경로가 섞이도록 체결 다건까지 구동한다.
            Awaitility.await().atMost(Duration.ofSeconds(40)).until(() ->
                    count("SELECT count(*) FROM trades") >= 8);
        } finally {
            fleet.stop();
        }
        assertFalse(fleet.isRunning(), "정지 후 running 플래그 해제");

        // 기동 배선 고정: start()가 기동마다 새 ordinal을 뽑아 coid 접두에 반영한다. 접두가
        // 상수·캐시로 퇴행하는 회귀를 조립된 경로에서 잡는다. 여기서 덮는 것은 같은 프로세스 내
        // 재기동까지다(인메모리 카운터 구현도 이 구간은 통과한다). JVM 경계 단조는 채번원의 성질이라
        // RunOrdinalSourceIntegrationTest가 맡는다.
        // 대기 조건이 목표와 같아야 한다. "주문이 하나라도 늘었나"로 기다리면 1차 실행에서
        // in-flight이던 주문이 그 조건을 만족시켜, 2차 접두가 생기기 전에 정지할 수 있다.
        // stop()이 발행 중 주문을 드레인하지 않으므로 실측으로 재현된다(3회 중 1회 실패).
        String distinctPrefixes =
                "SELECT count(DISTINCT split_part(client_order_id, '-', 1)) FROM orders";
        fleet.start();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(40)).until(() ->
                    count(distinctPrefixes) >= 2);
        } finally {
            fleet.stop();
        }
        long noncePrefixes = count(distinctPrefixes);
        assertTrue(noncePrefixes >= 2, "재기동마다 coid 접두가 갈린다: " + noncePrefixes);

        // 정지 정상성: 서버측 in-flight 처리 소진 후 저널 증가가 멈춘다(연속 2회 동수 관측).
        long[] previous = {-1};
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    long now = count("SELECT count(*) FROM command_journal");
                    boolean stable = now == previous[0];
                    previous[0] = now;
                    return stable;
                });

        long trades = count("SELECT count(*) FROM trades");
        assertTrue(trades >= 8, "체결 발생: " + trades);

        // 불변식 1: 자산별 복식부기 합 = 0
        assertEquals(0, count("""
                SELECT count(*) FROM (
                    SELECT asset_id FROM ledger_entries GROUP BY asset_id HAVING sum(amount) <> 0
                ) broken
                """), "자산별 원장 합 0");

        // 불변식 2: balances와 원장 합 대조 뷰 mismatch 0행
        assertEquals(0, count("SELECT count(*) FROM balance_ledger_check WHERE mismatch"),
                "balance_ledger_check 0행");

        // 불변식 3: 저널 gapless(§6-C ①)
        assertEquals(0, count("""
                SELECT CASE WHEN max(seq) - min(seq) + 1 = count(*) THEN 0 ELSE 1 END
                FROM command_journal
                """), "command_journal gapless");
        // V36 에서 결번 없음의 축이 마켓 안으로 갔다. event_seq 는 marketId 대역 파생이라
        // 전역으로 세면 대역 사이가 비어 보인다. 그 빈 자리는 결번이 아니라 경계다.
        assertEquals(0, count("""
                SELECT CASE WHEN (SELECT COALESCE(sum(span), 0) FROM (
                    SELECT max(market_seq) - min(market_seq) + 1 AS span
                    FROM event_journal WHERE market_seq IS NOT NULL GROUP BY market_id
                ) g) = (SELECT count(*) FROM event_journal WHERE market_seq IS NOT NULL)
                            THEN 0 ELSE 1 END
                """), "event_journal 마켓별 gapless");

        // 불변식 4: FILLED 주문의 락 잔량 0(§4-B-5)
        assertEquals(0, count(
                        "SELECT count(*) FROM orders WHERE status = 'FILLED' AND locked_amount <> 0"),
                "FILLED locked=0");

        // 봇 접수는 전부 §3 서명 HTTP 경로였음을 봇 사용자 저널 존재로 확인(외부 관점 계약)
        assertTrue(count("""
                SELECT count(*) FROM command_journal cj
                JOIN users u ON u.id = cj.user_id
                WHERE u.kind = 'BOT'
                """) > 0, "봇 기원 명령 존재");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
