package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.ReservationFixture;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 같은 키 동시 요청의 경합 응답(S4 설계 4-12·6-2, API 설계 §2-5-b, 검토 R1). P2 에서 외부 연결이 한 계정의 KRW 잔고 행을
 * FOR UPDATE 로 쥔 동안 같은 키·같은 내용의 주문 B(키를 넣음)와 A(공유 표시)를 보내고, 따로 키를 넣은 단독 요청 C 를 같은
 * 잠금에 보낸다(다른 마켓이라 접수 락은 다르다).
 *
 * A·B 는 모두 409 order_in_progress 이고 429 가 아니다. B 가 넣은 키는 A 가 공유 표시를 채워 V47 이 지우지 않으므로 그 키의
 * 주문이 뒤에 접수될 수 있기 때문이다. C 는 429(bucket market)이고 저널 행이 없다. 잠금을 푼 뒤 그 키로 다시 보내면 201
 * 하나이고 NEW 행이 하나다.
 *
 * 대조군(첫 판의 판정: 저널에 없음이면 429)은 따로 배선하지 않는다. 그 판정은 V47 결과만의 함수이므로, 그 입력이 이 장면에서
 * 성립함(경합 끝 시점에 그 키의 NEW 없음, V47 판정 실패 0, 커밋 회수 0, 공유 표시로 남은 키 1)을 단언해 그 판정이 A·B 를 '확정
 * 미접수'로 오판했을 것임을 보인다. 그 키는 재시도로 접수된다.
 */
class SameKeyContentionIntegrationTest {

    private static final String RESOURCE = "matching-engine";

    @Test
    void sameKeyRequestsGetOrderInProgressAndOnlyTheLoneKeyGets429() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_same_key", 2);
             PartitionNode node = new PartitionNode("node", f, new PartitionNode.Options().protocol(2))) {
            long alice = f.user("alice", new BigDecimal("100000000"));
            // dev·prod 설정과 같은 상한(S4 설계 4-12).
            node.appender.setLockTimeout(Duration.ofMillis(1000));
            node.bridge.setAcceptLockWait(Duration.ofMillis(2000));
            node.start();
            awaitAccepting(node, RESOURCE);

            MarketDirectory markets = new MarketDirectory(f.app);
            markets.start();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            OrderGateway gateway = new ApiConfig().orderGateway(f.app, node.bridge, markets,
                    new OrderQueryService(f.app), registry, new LeaderProtocolBoot(2), node.plan);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                String key = "same-key-1";
                String lone = "lone-key-1";
                Future<Object> b;
                Future<Object> a;
                Future<Object> c;
                try (Connection holder = f.db.owner()) {
                    holder.setAutoCommit(false);
                    lockKrwBalance(holder, alice);
                    b = pool.submit(() -> place(gateway, alice, "KRW-BTC", key));
                    Thread.sleep(100);
                    c = pool.submit(() -> place(gateway, alice, "KRW-ETH", lone));
                    Thread.sleep(200);
                    a = pool.submit(() -> place(gateway, alice, "KRW-BTC", key));
                    assertContention(b.get(20, TimeUnit.SECONDS), 409, "order_in_progress", "B(키를 넣음)");
                    assertContention(a.get(20, TimeUnit.SECONDS), 409, "order_in_progress", "A(공유 표시)");
                    ApiException cFailure = assertContention(c.get(20, TimeUnit.SECONDS), 429, "too_many_requests", "C(단독)");
                    assertEquals(Map.of("bucket", "market", "scope", "order"), cFailure.detail());
                    assertEquals(1, cFailure.retryAfter());
                    // 대조군의 입력: 경합이 끝난 시점에 그 키의 NEW 는 저널에 없다.
                    assertEquals(0, newRows(f, alice, key), "경합 중 그 키의 NEW 가 저널에 들어갔다");
                    assertEquals(0, newRows(f, alice, lone), "단독 요청 C 의 NEW 가 저널에 들어갔다");
                    holder.rollback();
                }
                // 대조군의 입력: 세 요청 모두 V47 이 답했고(판정 실패 0) 커밋된 것이 없으며(커밋 회수 0), B 가 넣은 키만 공유 표시로
                // 남았다(KEPT 1). 첫 판 판정은 V47 결과만의 함수라 이 입력에서 A(NOT_JOURNALED)와 B(KEPT)에 429 를 준다.
                assertEquals(0, gateway.unresolvedFailures(), "V47 판정이 실패해 대조군의 입력이 성립하지 않는다");
                assertEquals(0, gateway.commitsRecovered(), "경합 중 커밋된 요청이 있다");
                assertEquals(1, gateway.keysKept(), "공유 표시로 남은 키가 B 의 것 하나가 아니다");
                // B 가 넣은 키는 공유 표시 때문에 남아 있고(KEPT), C 의 키는 지워졌다(DISCARDED).
                assertEquals(1, f.count("SELECT count(*) FROM idempotency_keys WHERE user_id = " + alice
                        + " AND idem_key = '" + key + "'"), "공유 표시된 키가 지워졌다");
                assertEquals(0, f.count("SELECT count(*) FROM idempotency_keys WHERE user_id = " + alice
                        + " AND idem_key = '" + lone + "'"), "확정 미접수의 키가 남았다");
                assertEquals(2.0, contention(registry, "409"), "409 경합 계수");
                assertEquals(1.0, contention(registry, "429"), "429 경합 계수");

                // 잠금을 푼 뒤 같은 키로 다시 보내면 201 하나와 멱등 재생 하나다.
                Object first = place(gateway, alice, "KRW-BTC", key);
                Object second = place(gateway, alice, "KRW-BTC", key);
                if (!(first instanceof OrderGateway.Placement p1) || !(second instanceof OrderGateway.Placement p2)) {
                    fail("재시도가 접수되지 않았다: " + first + ", " + second);
                    return;
                }
                assertFalse(p1.replayed(), "첫 재시도가 새 접수(201)가 아니다");
                assertTrue(p2.replayed(), "두 번째 재시도가 멱등 재생(200)이 아니다");
                assertEquals(p1.row().id(), p2.row().id());
                assertEquals(1, newRows(f, alice, key), "그 키의 NEW 가 하나가 아니다");
            } finally {
                pool.shutdownNow();
                markets.close();
            }
        }
    }

    private static Object place(OrderGateway gateway, long user, String market, String key) {
        byte[] raw = ("{\"market\":\"" + market + "\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"0.1\",\"client_order_id\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
        try {
            return gateway.place(user, new ObjectMapper().readTree(raw), raw);
        } catch (RuntimeException e) {
            return e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException assertContention(Object outcome, int status, String name, String who) {
        if (!(outcome instanceof ApiException e)) {
            fail(who + " 가 경합 응답이 아니다: " + outcome);
            return null;
        }
        assertEquals(status, e.status(), who + " 상태: " + e.name());
        assertEquals(name, e.name(), who);
        return e;
    }

    private static void lockKrwBalance(Connection c, long user) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT b.account_id FROM balances b JOIN accounts a ON a.id = b.account_id
                WHERE a.owner_type = 'USER' AND a.owner_id = ? AND a.asset_id = 1
                FOR UPDATE OF b
                """)) {
            ps.setLong(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "잠글 잔고 행이 없다");
            }
        }
    }

    private static long newRows(ReservationFixture f, long user, String key) {
        return f.count("SELECT count(*) FROM command_journal WHERE command_type = 'NEW' AND user_id = " + user
                + " AND client_order_id = '" + key + "'");
    }

    private static double contention(SimpleMeterRegistry registry, String response) {
        return registry.find("exchange.order.intake.contention").tag("response", response).functionCounters().stream()
                .mapToDouble(io.micrometer.core.instrument.FunctionCounter::count).sum();
    }
}
