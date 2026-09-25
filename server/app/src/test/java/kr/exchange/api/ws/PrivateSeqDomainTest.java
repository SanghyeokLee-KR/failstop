package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.engine.core.OrderCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
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
 * 비공개 채널 seq 도메인(API 설계 §5-2, S6 설계 4-8). 중계 모드는 user × market 이고 seq_domain 을 싣는다. 한 마켓은 한 순간 그
 * 파티션 리더 하나만 게시하므로 노드 하나의 카운터로 조밀하다. simple 모드는 종전 user × type 이고 필드가 없다.
 */
class PrivateSeqDomainTest {

    private static final OrderCommand.Side BUY = OrderCommand.Side.BUY;

    @Test
    void brokerModeCountsPerUserAndMarketAndMarksTheDomain() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_seq_broker", true)) {
            long alice = placeAlternating(rig);
            for (String queue : List.of("myOrder", "myAsset")) {
                List<JsonNode> msgs = rig.messages("/user/" + alice + "/queue/" + queue);
                assertEquals(4, msgs.size(), queue);
                Map<String, List<Long>> byMarket = new HashMap<>();
                for (JsonNode m : msgs) {
                    assertEquals("user_market", m.path("seq_domain").asText(), queue + " 에 seq_domain 이 없다");
                    byMarket.computeIfAbsent(m.path("market").asText(), k -> new ArrayList<>()).add(m.path("seq").asLong());
                }
                assertEquals(List.of(1L, 2L), byMarket.get("KRW-BTC"), queue + " KRW-BTC 가 마켓별로 1부터 조밀하지 않다");
                assertEquals(List.of(1L, 2L), byMarket.get("KRW-ETH"), queue + " KRW-ETH 가 마켓별로 1부터 조밀하지 않다");
            }
            rig.drainRelay();
            assertTrue(rig.transport.sent.stream().anyMatch(e -> "/queue/myOrder".equals(e.dest())
                    && e.userId() == alice), "중계 봉투에 사용자 메시지가 없다");
        }
    }

    @Test
    void simpleModeKeepsOneCounterPerUserAndTypeWithoutTheField() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_seq_simple", false)) {
            long alice = placeAlternating(rig);
            for (String queue : List.of("myOrder", "myAsset")) {
                List<JsonNode> msgs = rig.messages("/user/" + alice + "/queue/" + queue);
                List<Long> seqs = new ArrayList<>();
                for (JsonNode m : msgs) {
                    assertFalse(m.has("seq_domain"), queue + " simple 모드에 seq_domain 이 실렸다");
                    seqs.add(m.path("seq").asLong());
                }
                assertEquals(List.of(1L, 2L, 3L, 4L), seqs, queue + " 사용자 하나의 카운터가 아니다");
            }
            JsonNode asset = rig.messages("/user/" + alice + "/queue/myAsset").get(0);
            assertTrue(asset.has("market") && asset.path("assets").get(0).has("version"),
                    "simple 모드에도 myAsset market·version 을 싣는다");
        }
    }

    /** 한 사용자가 KRW-BTC, KRW-ETH 에 번갈아 쉬는 주문을 넷 낸다. */
    private static long placeAlternating(PublisherRig rig) {
        long alice = rig.f.user("alice", new BigDecimal("100000000"));
        rig.f.deposit(alice, BTC, new BigDecimal("1"));
        rig.f.deposit(alice, ETH, new BigDecimal("1"));
        rig.submit(rig.f.limit(KRW_BTC, alice, BUY, "40000", "0.1"));
        rig.submit(rig.f.limit(KRW_ETH, alice, BUY, "2000", "0.1"));
        rig.submit(rig.f.limit(KRW_BTC, alice, BUY, "41000", "0.1"));
        rig.submit(rig.f.limit(KRW_ETH, alice, BUY, "2100", "0.1"));
        return alice;
    }
}
