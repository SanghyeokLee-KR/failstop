package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.order.AccountRepository;
import kr.exchange.api.transfer.TransferService;
import kr.exchange.engine.core.OrderCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * myAsset 항목과 계좌 조회의 잔고 판 version(API 설계 §4-C-1·§5-5, DB 설계 §4.1, S6 설계 4-9). 한 사용자가 두 마켓에서 번갈아
 * 체결하고 출금 요청·취소를 섞는다. 게시 항목의 version 은 AVAILABLE·LOCKED 두 잔고 행 version 의 합이고 줄지 않으며, 같은
 * version 이면 잔고와 잠금이 같다. 계좌 조회의 version 과 같은 시점 게시의 version 이 같다.
 */
class MyAssetVersionIntegrationTest {

    private static final OrderCommand.Side BUY = OrderCommand.Side.BUY;
    private static final OrderCommand.Side SELL = OrderCommand.Side.SELL;

    @Test
    void myAssetVersionIsTheSumOfBothBalanceRowVersionsAndNeverDecreases() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_myasset_version", false)) {
            long alice = rig.f.user("alice", new BigDecimal("100000000"));
            long bob = rig.f.user("bob", new BigDecimal("100000000"));
            rig.f.deposit(alice, BTC, new BigDecimal("10"));
            rig.f.deposit(bob, BTC, new BigDecimal("10"));
            rig.f.deposit(alice, ETH, new BigDecimal("100"));
            rig.f.deposit(bob, ETH, new BigDecimal("100"));
            TransferService transfers = new TransferService(rig.f.app, rig.f.appTx);
            AccountRepository accounts = new AccountRepository(rig.f.app);
            String aliceAssets = "/user/" + alice + "/queue/myAsset";

            rig.submit(rig.f.limit(KRW_BTC, alice, BUY, "50000", "0.3"));
            assertPublishedMatchesDb(rig, alice, aliceAssets);
            rig.submit(rig.f.limit(KRW_BTC, bob, SELL, "50000", "0.1"));
            assertPublishedMatchesDb(rig, alice, aliceAssets);
            long krwBeforeWithdraw = lastVersion(rig.messages(aliceAssets), "KRW");

            // 출금 요청과 취소는 게시가 없지만 잔고 행을 바꾼다. 다음 게시가 그만큼 오른 판을 싣는다.
            TransferService.Result withdraw = transfers.request(alice, "WITHDRAW", "KRW", "1000", "s6-w-1");
            transfers.cancel(String.valueOf(withdraw.view().get("external_id")), alice);

            rig.submit(rig.f.limit(KRW_ETH, alice, SELL, "3000", "2"));
            assertPublishedMatchesDb(rig, alice, aliceAssets);
            rig.submit(rig.f.limit(KRW_ETH, bob, BUY, "3000", "1"));
            assertPublishedMatchesDb(rig, alice, aliceAssets);
            rig.submit(rig.f.limit(KRW_BTC, bob, SELL, "50000", "0.1"));
            assertPublishedMatchesDb(rig, alice, aliceAssets);

            List<JsonNode> published = rig.messages(aliceAssets);
            assertTrue(published.size() >= 5, "myAsset 게시가 모자라다: " + published.size());
            assertTrue(lastVersion(published, "KRW") > krwBeforeWithdraw + 2,
                    "출금 요청·취소가 잔고 판을 올리지 않았다");
            assertMonotonicAndSameVersionSameBalance(published);

            // 같은 시점의 계좌 조회와 마지막 게시가 같은 판이다.
            Map<String, Long> last = new HashMap<>();
            for (JsonNode m : published) {
                for (JsonNode a : m.path("assets")) {
                    last.put(a.path("currency").asText(), a.path("version").asLong());
                }
            }
            for (AccountRepository.BalanceRow row : accounts.balances(alice)) {
                if (last.containsKey(row.currency())) {
                    assertEquals(last.get(row.currency()), row.version(), "계좌 조회와 게시의 판이 다르다: " + row.currency());
                }
            }
            assertEquals(3, last.size(), "게시가 건드린 자산이 셋(KRW·BTC·ETH)이 아니다: " + last.keySet());
            assertEquals("KRW-BTC", published.get(published.size() - 1).path("market").asText(),
                    "myAsset 이 게시를 만든 명령의 마켓을 싣지 않았다");
        }
    }

    /** 마지막 게시의 각 항목 version 이 그 순간 DB 의 두 잔고 행 version 합과 같다. */
    private static void assertPublishedMatchesDb(PublisherRig rig, long user, String destination) {
        List<JsonNode> published = rig.messages(destination);
        assertFalse(published.isEmpty(), "myAsset 이 게시되지 않았다");
        JsonNode last = published.get(published.size() - 1);
        for (JsonNode asset : last.path("assets")) {
            long db = rig.f.count("SELECT COALESCE(SUM(b.version), 0) FROM accounts a JOIN balances b"
                    + " ON b.account_id = a.id JOIN assets s ON s.id = a.asset_id WHERE a.owner_type = 'USER'"
                    + " AND a.owner_id = " + user + " AND a.purpose IN ('AVAILABLE', 'LOCKED') AND s.symbol = '"
                    + asset.path("currency").asText() + "'");
            assertEquals(db, asset.path("version").asLong(), "게시 version 이 두 잔고 행 합과 다르다: " + asset);
            assertTrue(asset.path("version").isIntegralNumber(), "version 이 JSON 숫자가 아니다");
        }
    }

    private static void assertMonotonicAndSameVersionSameBalance(List<JsonNode> published) {
        Map<String, JsonNode> seen = new HashMap<>();
        for (JsonNode m : published) {
            for (JsonNode a : m.path("assets")) {
                String currency = a.path("currency").asText();
                JsonNode prev = seen.get(currency);
                if (prev != null) {
                    long pv = prev.path("version").asLong();
                    long v = a.path("version").asLong();
                    assertTrue(v >= pv, currency + " 판이 줄었다: " + pv + " -> " + v);
                    if (v == pv) {
                        assertEquals(prev.path("balance").asText(), a.path("balance").asText(), "같은 판 다른 잔고");
                        assertEquals(prev.path("locked").asText(), a.path("locked").asText(), "같은 판 다른 잠금");
                    }
                }
                seen.put(currency, a);
            }
        }
    }

    private static long lastVersion(List<JsonNode> published, String currency) {
        long v = -1;
        for (JsonNode m : published) {
            for (JsonNode a : m.path("assets")) {
                if (currency.equals(a.path("currency").asText())) {
                    v = a.path("version").asLong();
                }
            }
        }
        return v;
    }
}
