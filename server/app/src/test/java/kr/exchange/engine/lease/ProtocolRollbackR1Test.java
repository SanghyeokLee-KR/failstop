package kr.exchange.engine.lease;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.JournalChecksum;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시험 R1(ADR 0034 결정 8, S3 설계 6-2). P2 에서 예약 주문을 만들고 정산을 끝낸 뒤 P1 로 내린다. P1 경로(정산 시점 예약)로
 * 예약 주문과의 체결, 취소, 새 주문을 돌리고 저널 전체를 새 엔진으로 재생해 대조가 맞고 불변식이 0 이다. 정산이 끝난 예약
 * 주문은 주문 행의 locked_amount 로 보통 주문과 똑같이 체결·취소된다는 되돌리기의 근거다.
 *
 * 이어서 S2 바이너리의 SQL(S2BinarySql)로 리더가 되어 P2 예약 주문과 체결·취소하는 단계를 돈다(S3 설계 6-2 의 S2 SQL 판, 구현
 * 검토 반영). 정산 본체는 이 바이너리의 P1 정산이라 S2 이미지를 띄운 판(X6)을 대신하지는 않는다.
 */
class ProtocolRollbackR1Test {

    private static final OrderCommand.Side BUY = OrderCommand.Side.BUY;
    private static final OrderCommand.Side SELL = OrderCommand.Side.SELL;

    @Test
    void reservedOrdersKeepWorkingAfterDowngradeAndReplayMatches() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("r1_rollback", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            long carol = f.user("carol", new BigDecimal("1000000"));
            f.deposit(bob, BTC, new BigDecimal("10"));
            MatchingEngineImpl live = new MatchingEngineImpl();

            // P2: 쉬는 주문(alice 매수 0.5), 부분 체결(bob 매도 0.2 가 alice 를 부분 체결), 쉬는 매도(bob 0.1 @51000).
            LeaderLeaseService p2Lease = f.lease();
            CommandJournalAppender p2Appender = f.appender(p2Lease, true);
            SettlementService p2Settlement = f.settlement(p2Lease);
            long aliceOrder = f.submit(p2Appender, live, p2Settlement, f.limit(KRW_BTC, alice, BUY, "50000", "0.5"))
                    .get(0).orderId();
            f.submit(p2Appender, live, p2Settlement, f.limit(KRW_BTC, bob, SELL, "50000", "0.2"));
            long bobAsk = f.submit(p2Appender, live, p2Settlement, f.limit(KRW_BTC, bob, SELL, "51000", "0.1"))
                    .get(0).orderId();
            // S2 SQL 단계가 체결·취소할 쉬는 예약 매수(carol 0.2 @45000). P1 단계의 주문과는 가격이 겹치지 않는다.
            long carolBid = f.submit(p2Appender, live, p2Settlement, f.limit(KRW_BTC, carol, BUY, "45000", "0.2"))
                    .get(0).orderId();
            long reservations = f.count("SELECT count(*) FROM order_reservations");
            assertEquals(4, reservations, "P2 주문이 예약되지 않았다");
            assertEquals("PARTIAL", status(f, aliceOrder));

            // 정지 창에서 2->1. 정산되지 않은 NEW 가 없어 확인 인자로 내린다.
            p2Lease.close();
            f.switchProtocol(1, true);
            assertEquals(1, f.protocol());

            // P1: 예약 주문과의 체결 둘, 취소 하나, 새 주문 하나. 정산이 P1 명령을 정산 시점에 예약한다.
            LeaderLeaseService p1Lease = f.lease();
            CommandJournalAppender p1Appender = f.appender(p1Lease, false);
            SettlementService p1Settlement = f.settlement(p1Lease);
            f.submit(p1Appender, live, p1Settlement, f.limit(KRW_BTC, carol, BUY, "51000", "0.1"));
            assertEquals("FILLED", status(f, bobAsk), "P2 예약 매도가 P1 매수와 체결되지 않았다");
            f.submit(p1Appender, live, p1Settlement, f.limit(KRW_BTC, bob, SELL, "50000", "0.1"));
            f.submit(p1Appender, live, p1Settlement, f.cancel(KRW_BTC, alice, aliceOrder));
            assertEquals("CANCELED", status(f, aliceOrder), "P2 예약 주문의 취소가 되지 않았다");
            f.submit(p1Appender, live, p1Settlement, f.limit(KRW_BTC, carol, BUY, "40000", "0.05"));
            assertEquals(reservations, f.count("SELECT count(*) FROM order_reservations"), "P1 이 예약 행을 남겼다");
            assertEquals(0, p1Settlement.insufficientFunds());
            // alice 의 KRW 는 체결 0.3 x 50000 만큼만 줄었다. 예약 잔여가 LOCKED 에 남지 않았다.
            assertEquals(0, new BigDecimal("985000").compareTo(f.available(alice, KRW)));
            assertEquals(0, BigDecimal.ZERO.compareTo(f.locked(alice, KRW)));

            // S2 바이너리 단계(S3 구현 검토). 내린 뒤의 DB 에서 S2 바이너리의 SQL(3인자 획득, 6인자 머리말, 저널 행 함수, 정산
            // 머리말, 2인자 게시)로 리더가 되어, P2 에서 예약된 쉬는 매수와 체결하고 그 주문을 취소하고 새 주문을 낸다. 정산 본체는
            // 이 바이너리(S3)의 P1 정산이다. S2 와 S3 의 P1 정산 차이는 정산 머리말(예약 행 결합)과 잠금 계획(잠그기만 한다)이고,
            // 정산 머리말은 S2 문장을 같은 명령에 돌려 대조한다. S2 이미지를 띄운 판(X6)을 대신하지는 않는다.
            p1Lease.close();
            f.db.expire("matching-engine");
            LeaderLeaseService s2Lease = new LeaderLeaseService(new S2BinarySql.LeaseStore(f.app),
                    FencingEpochPublisher.NONE, Duration.ofMinutes(10), Duration.ofSeconds(1),
                    NodeServiceHealth.ALWAYS_SERVICEABLE);
            assertTrue(s2Lease.tryAcquire(), "내린 뒤 S2 획득(3인자)이 되지 않았다");
            SettlementService s2Settlement = f.settlement(s2Lease);
            s2Submit(f, s2Lease, live, s2Settlement, f.limit(KRW_BTC, bob, SELL, "45000", "0.1"));
            assertEquals("PARTIAL", status(f, carolBid), "S2 SQL 로 적은 매도가 P2 예약 매수와 체결되지 않았다");
            s2Submit(f, s2Lease, live, s2Settlement, f.cancel(KRW_BTC, carol, carolBid));
            assertEquals("CANCELED", status(f, carolBid), "S2 SQL 로 적은 취소가 P2 예약 주문을 풀지 않았다");
            s2Submit(f, s2Lease, live, s2Settlement, f.limit(KRW_BTC, carol, BUY, "30000", "0.1"));
            assertEquals(reservations, f.count("SELECT count(*) FROM order_reservations"), "S2 단계가 예약 행을 남겼다");
            assertEquals(0, s2Settlement.insufficientFunds());
            // carol 의 KRW LOCKED 는 쉬는 P1 주문 둘(0.05 @40000, 0.1 @30000)의 예약뿐이다. P2 예약 9,000 은 체결 4,500 과
            // 취소 해제 4,500 으로 모두 빠졌다.
            assertEquals(0, new BigDecimal("5000").compareTo(f.locked(carol, KRW)), "carol 의 LOCKED: " + f.locked(carol, KRW));
            // 외부 보관소 노드의 S2 게시(2인자)도 P1 이면 된다. 게시는 리스 행을 옮기므로 정산을 끝낸 뒤에 한다.
            long s2Epoch = s2Lease.requireLeader().epoch();
            s2Lease.close();
            try (Connection c = f.db.app(); PreparedStatement ps = c.prepareStatement(S2BinarySql.PUBLISH)) {
                ps.setString(1, "s2-dynamo");
                ps.setLong(2, s2Epoch + 10);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(s2Epoch + 10, rs.getLong(1), "내린 뒤 S2 게시(2인자)가 되지 않았다");
            }

            // 새 엔진으로 저널 전체를 재생해 기존 이벤트와 하나씩 대조한다. 어긋나면 예외다.
            ReplayService replay = new ReplayService(new CommandJournalReader(f.app), new EventJournalReader(f.app),
                    f.mapper, new ObjectMapper(), new MarketCatalog(f.app));
            ReplayResult result = replay.verifyToMarket(new MatchingEngineImpl(), KRW_BTC,
                    replay.materializedThroughByMarket(Map.of()));
            assertTrue(result.verifiedEvents() >= 10, "재생 대조가 비었다: " + result.verifiedEvents());

            for (InvariantCheck check : new InvariantCheck[] {InvariantCheck.C01_ASSET_CONSERVATION,
                    InvariantCheck.C02_BAL_VS_LEDGER, InvariantCheck.C14_LOCKED_RESERVATION,
                    InvariantCheck.valueOf("C31_RESERVATION_LINK"), InvariantCheck.C17_REPLAY_DETERMINISM,
                    InvariantCheck.C13A_ORDER_MATERIALIZED}) {
                assertEquals(0, f.invariant(check), check.name());
            }
        }
    }

    private static String status(ReservationFixture f, long orderId) {
        return f.owner.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }

    /**
     * S2 바이너리의 SQL 로 명령 하나를 저널에 적고(6인자 머리말과 저널 행 함수, 한 트랜잭션), S2 정산 머리말 문장이 그 명령을
     * 판정 epoch·미정산으로 읽는지 본 뒤 엔진에 적용해 정산한다.
     */
    private static void s2Submit(ReservationFixture f, LeaderLeaseService s2Lease, MatchingEngineImpl engine,
                                 SettlementService settlement, OrderIntake intake) throws Exception {
        CommandDraft draft = f.draft(intake);
        LeaderIdentity leader = s2Lease.requireLeader();
        long seq;
        try (Connection c = f.db.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(S2BinarySql.APPEND_PROLOGUE)) {
                ps.setString(1, leader.holder());
                ps.setLong(2, leader.epoch());
                ps.setBoolean(3, draft.type() == CommandDraft.CommandType.NEW);
                ps.setLong(4, draft.userId());
                ps.setString(5, draft.clientOrderId());
                ps.setInt(6, draft.marketId());
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertFalse(rs.getBoolean("out_replayed"));
                seq = rs.getLong("out_seq");
            }
            try (PreparedStatement ps = c.prepareStatement(S2BinarySql.APPEND_INSERT)) {
                ps.setLong(1, seq);
                ps.setInt(2, draft.marketId());
                ps.setString(3, draft.type().name());
                ps.setLong(4, draft.userId());
                ps.setString(5, draft.clientOrderId());
                ps.setLong(6, draft.assignedOrderId());
                ps.setLong(7, draft.engineTs());
                ps.setLong(8, draft.tieBreak());
                ps.setString(9, draft.payloadJson());
                ps.setLong(10, JournalChecksum.of(seq, draft));
                ps.setString(11, leader.holder());
                ps.setLong(12, leader.epoch());
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "S2 저널 행이 들어가지 않았다: " + draft.type());
            }
            c.commit();
            try (PreparedStatement ps = c.prepareStatement(S2BinarySql.SETTLE_PROLOGUE)) {
                ps.setInt(1, draft.marketId());
                ps.setString(2, leader.holder());
                ps.setLong(3, leader.epoch());
                ps.setLong(4, seq);
                ps.setLong(5, seq);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(leader.epoch(), rs.getLong("epoch"));
                assertEquals(0, rs.getLong("registered"));
                assertEquals(seq, rs.getLong("seq"));
                assertEquals(draft.type().name(), rs.getString("command_type"));
            }
            c.rollback();
        }
        settlement.appendAndSettle(f.apply(engine, seq, intake.marketId()));
    }
}
