package kr.exchange.ledger;

import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.JournalChecksum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 정산 분기(ADR 0034 S3, S3 설계 4-8·6-2, DB 설계 §5.1 P2 정산 규칙). 예약 행이 있는 주문은 정산이 reserve_funds 를
 * 부르지 않고 예약 행을 권위로 쓰며, 거부된 주문은 조기 반환보다 먼저 예약을 푼다. 앱 로그인으로 실제 append·엔진·정산을 돈다.
 */
class SettlementReservedTest {

    private static final OrderCommand.Side BUY = OrderCommand.Side.BUY;
    private static final OrderCommand.Side SELL = OrderCommand.Side.SELL;

    @Test
    void reservedOrdersSettleWithoutReserveFundsAndRejectionsReleaseBeforeEarlyReturns() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("settle_reserved", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            long bob = f.user("bob", new BigDecimal("1000000"));
            f.deposit(alice, BTC, new BigDecimal("10"));
            f.deposit(bob, BTC, new BigDecimal("10"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            SettlementService settlement = f.settlement(lease);
            MatchingEngineImpl engine = new MatchingEngineImpl();

            // (1) ACCEPTED: 주문 행이 예약 행의 금액·계정을 넘겨받고, 정산이 RESERVE 거래도 LOCKED 도 더 움직이지 않는다.
            OrderIntake rest = f.limit(KRW_BTC, alice, BUY, "50000", "0.3");
            List<EngineEvent> accepted = f.journalAndApply(appender, engine, rest);
            long reservesBefore = reserves(f);
            BigDecimal lockedBefore = f.locked(alice, KRW);
            settlement.appendAndSettle(accepted);
            assertEquals(reservesBefore, reserves(f), "정산이 예약된 주문을 다시 예약했다");
            assertEquals(0, lockedBefore.compareTo(f.locked(alice, KRW)));
            assertEquals("15000|" + f.account(alice, "LOCKED", KRW) + "|OPEN", f.owner.sql("""
                            SELECT o.locked_amount::numeric(40,0) || '|' || o.locked_account_id || '|' || o.status
                            FROM orders o WHERE o.id = ?
                            """).param(rest.assignedOrderId()).query(String.class).single());

            // (2) FOK 미충족과 POST_ONLY 즉시 체결은 거부되고, 해제가 REJECTED 이벤트 event_seq 축으로 들어간다.
            OrderIntake fok = f.limit(KRW_BTC, alice, BUY, "60000", "5", OrderCommand.TimeInForce.FOK);
            assertRejectedAndReleased(f, appender, engine, settlement, fok, alice, KRW, "300000", "fok_not_filled");
            // 매도 호가 55000 은 alice 의 매수 50000 과 겹치지 않아 쉰다. 56000 POST_ONLY 매수는 그 호가를 친다.
            f.submit(appender, engine, settlement, f.limit(KRW_BTC, bob, SELL, "55000", "0.1"));
            OrderIntake postOnly = f.limit(KRW_BTC, alice, BUY, "56000", "0.2", OrderCommand.TimeInForce.POST_ONLY);
            assertRejectedAndReleased(f, appender, engine, settlement, postOnly, alice, KRW, "11200",
                    "post_only_would_take");

            // (3) 넣을 수 없는 모양(매도 시장가 POST_ONLY). 게이트웨이는 막지만 저널은 받는다. 예약은 되고 엔진이 거부하며
            //     주문 행 없이 해제된다.
            OrderIntake malformed = f.intake(KRW_BTC, alice, "s3-malformed", f.nextOrderId(), SELL,
                    OrderCommand.OrdType.MARKET, OrderCommand.TimeInForce.POST_ONLY, null, new BigDecimal("0.5"), null);
            BigDecimal btcBefore = f.available(alice, BTC);
            List<EngineEvent> invalid = f.journalAndApply(appender, engine, malformed);
            assertEquals(0, btcBefore.subtract(new BigDecimal("0.5")).compareTo(f.available(alice, BTC)),
                    "넣을 수 없는 모양이 접수에서 예약되지 않았다");
            settlement.appendAndSettle(invalid);
            assertEquals(0, btcBefore.compareTo(f.available(alice, BTC)), "모양 조기 반환이 해제를 건너뛰었다");
            assertEquals(0, f.count("SELECT count(*) FROM orders WHERE id = " + malformed.assignedOrderId()));

            // (4) 중복 주문 id 조기 반환. 예약된 명령의 주문 id 로 REJECTED 행을 미리 두고 REJECTED(duplicate_order_id)
            //     배치를 넘긴다. 해제가 되고 기존 행은 그대로다.
            OrderIntake dup = f.limit(KRW_BTC, alice, BUY, "30000", "1");
            AppendResult dupAck = f.append(appender, dup);
            f.owner.sql("""
                            INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                                price, quantity, remaining_qty, locked_amount, status, reject_reason,
                                                command_seq)
                            VALUES (?, gen_random_uuid(), ?, 1, 'pre-existing', 'BUY', 'LIMIT', 1, 1, 0, 0, 'REJECTED',
                                    'pre', ?)
                            """)
                    .params(dup.assignedOrderId(), alice, dupAck.seq()).update();
            BigDecimal availDup = f.available(alice, KRW);
            List<EngineEvent> duplicate = List.of(new EngineEvent(0, dupAck.seq(), 0, KRW_BTC,
                    EngineEvent.EventType.REJECTED, dup.engineTs(), dup.assignedOrderId(), null, null, null, null,
                    null, null, "duplicate_order_id"));
            settlement.appendAndSettle(duplicate);
            assertEquals(0, availDup.add(new BigDecimal("30000")).compareTo(f.available(alice, KRW)),
                    "중복 주문 id 조기 반환이 해제를 건너뛰었다");
            assertEquals("pre-existing|pre", f.owner.sql("SELECT client_order_id || '|' || reject_reason FROM orders"
                    + " WHERE id = ?").param(dup.assignedOrderId()).query(String.class).single());

            // (5) 같은 배치 재정산은 레지스트리가 통째로 건너뛰어 해제가 한 번이다.
            settlement.appendAndSettle(duplicate);
            assertEquals(1, f.count("SELECT count(*) FROM ledger_transactions t JOIN event_journal e"
                    + " ON t.business_event_id = e.event_seq WHERE t.business_event_type = 'RELEASE'"
                    + " AND e.source_cmd_seq = " + dupAck.seq()));
            assertEquals(0, availDup.add(new BigDecimal("30000")).compareTo(f.available(alice, KRW)));

            // (7) P1 에서 접수한 명령(예약 행 없음)은 P2 DB 에서도 정산 시점에 예약한다. 소유자가 저널에 직접 넣어 만든다.
            //     fencing_token 은 P1 epoch 라 C31 (b)의 대상이 아니다.
            OrderIntake legacy = f.limit(KRW_BTC, bob, BUY, "20000", "1");
            long legacySeq = insertUnreserved(f, legacy, p1Epoch(f));
            BigDecimal bobLocked = f.locked(bob, KRW);
            settlement.appendAndSettle(f.apply(engine, legacySeq, KRW_BTC));
            assertEquals(0, bobLocked.add(new BigDecimal("20000")).compareTo(f.locked(bob, KRW)),
                    "예약 행 없는 명령을 정산이 예약하지 않았다");
            assertEquals(1, f.count("SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE'"
                    + " AND business_event_id = " + legacy.assignedOrderId()));

            assertEquals(0, settlement.insufficientFunds());
            assertEquals(0, f.invariant(InvariantCheck.C14_LOCKED_RESERVATION), "C14' 위반");
            assertEquals(0, f.invariant(InvariantCheck.valueOf("C31_RESERVATION_LINK")), "C31 위반");
            assertEquals(0, f.invariant(InvariantCheck.C01_ASSET_CONSERVATION));
            assertEquals(0, f.invariant(InvariantCheck.C02_BAL_VS_LEDGER));
        }
    }

    @Test
    void reservationRowThatDoesNotMatchTheCommandStopsTheWriter() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("settle_mismatch", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            LeaderLeaseService lease = f.lease();
            SettlementService settlement = f.settlement(lease);
            // (6) 소유자가 명령과 주문 id 가 다른 예약 행을 붙인다(연결 손상). 저널 행 함수를 거치지 않는 소유자 경로다.
            OrderIntake buy = f.limit(KRW_BTC, alice, BUY, "50000", "0.3");
            long seq = insertUnreserved(f, buy, p1Epoch(f));
            f.owner.sql("""
                            INSERT INTO order_reservations (command_seq, order_id, user_id, market_id, asset_id,
                                                            available_account_id, locked_account_id, amount, ledger_txn_id,
                                                            payload_sha256)
                            VALUES (?, ?, ?, 1, 1, ?, ?, 15000, 1, '\\x00')
                            """)
                    .params(seq, buy.assignedOrderId() + 999, alice, f.account(alice, "AVAILABLE", KRW),
                            f.account(alice, "LOCKED", KRW))
                    .update();
            List<EngineEvent> events = f.apply(new MatchingEngineImpl(), seq, KRW_BTC);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> settlement.appendAndSettle(events));

            assertTrue(e.getMessage().contains("예약 행"), e.getMessage());
            assertEquals(0, f.count("SELECT count(*) FROM event_journal WHERE source_cmd_seq = " + seq),
                    "연결 손상 배치가 일부라도 커밋됐다");
            assertEquals(0, f.count("SELECT count(*) FROM orders"));
        }
    }

    /**
     * 정산 시점 잔고 부족 계수의 양성 축(S3 구현 검토). P1 에서는 정산이 ACCEPTED 에서 예약한다. 사전 가드를 거치지 않고 잔고보다
     * 큰 주문을 저널에 적으면 정산의 reserve_funds 가 23514(chk_balance_nonneg)로 끝나고 exchange.settlement.insufficient_funds 가
     * 오른다. 이 사례가 없으면 이 시험 (1)~(7), 이중 지출 시험, 스택 관문의 '정산 23514 0' 판정은 계수가 깨져도 통과한다.
     */
    @Test
    void settlementInsufficientFundsCounterRisesOnA23514() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("settle_short", 1)) {
            long bob = f.user("bob", new BigDecimal("10000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, false);
            SettlementService settlement = f.settlement(lease);
            List<EngineEvent> accepted = f.journalAndApply(appender, new MatchingEngineImpl(),
                    f.limit(KRW_BTC, bob, BUY, "50000", "0.3"));
            long ledgerBefore = f.count("SELECT count(*) FROM ledger_transactions");

            org.springframework.dao.DataAccessException e = assertThrows(
                    org.springframework.dao.DataAccessException.class, () -> settlement.appendAndSettle(accepted));

            assertTrue(kr.exchange.journal.SqlErrors.isInsufficientFunds(e), "잔고 부족이 아닌 이유로 끝났다: " + e);
            assertEquals(1, settlement.insufficientFunds(), "정산 23514 를 세지 않았다");
            assertEquals(ledgerBefore, f.count("SELECT count(*) FROM ledger_transactions"), "실패한 배치가 원장을 남겼다");
            assertEquals(0, f.count("SELECT count(*) FROM event_journal"), "실패한 배치가 이벤트를 남겼다");
            assertEquals(0, new BigDecimal("10000").compareTo(f.available(bob, KRW)));
        }
    }

    private static void assertRejectedAndReleased(ReservationFixture f, CommandJournalAppender appender,
                                                  MatchingEngineImpl engine, SettlementService settlement,
                                                  OrderIntake intake, long user, int asset, String reserved,
                                                  String reason) {
        BigDecimal availBefore = f.available(user, asset);
        List<EngineEvent> events = f.journalAndApply(appender, engine, intake);
        assertEquals(0, availBefore.subtract(new BigDecimal(reserved)).compareTo(f.available(user, asset)),
                "접수가 예약하지 않았다: " + reason);
        assertEquals(EngineEvent.EventType.REJECTED, events.get(0).type());
        assertEquals(reason, events.get(0).reason());
        List<EngineEvent> settled = settlement.appendAndSettle(events);
        assertEquals(0, availBefore.compareTo(f.available(user, asset)), "거부된 주문의 예약이 풀리지 않았다: " + reason);
        long rejectedSeq = settled.get(0).eventSeq();
        BigDecimal released = f.owner.sql("""
                        SELECT e.amount FROM ledger_transactions t JOIN ledger_entries e ON e.transaction_id = t.id
                        WHERE t.business_event_type = 'RELEASE' AND t.business_event_id = ? AND e.amount > 0
                        """).param(rejectedSeq).query(BigDecimal.class).single();
        assertEquals(0, new BigDecimal(reserved).compareTo(released), "해제액이 예약액과 다르다: " + reason);
        assertEquals("REJECTED|" + reason, f.owner.sql("SELECT status || '|' || reject_reason FROM orders WHERE id = ?")
                .param(intake.assignedOrderId()).query(String.class).single());
    }

    private static long reserves(ReservationFixture f) {
        return f.count("SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE'");
    }

    /** P1 에서 쓰인 epoch. 이 DB 는 만들자마자 P2 로 올려 P1 획득이 없으므로 0 이다(획득 이력이 없어 C31 (b) 밖이다). */
    private static long p1Epoch(ReservationFixture f) {
        return f.count("SELECT COALESCE(max(epoch), 0) FROM lease_acquisitions WHERE protocol = 1");
    }

    /** 소유자가 예약 없이 저널 행을 넣는다. P1 에서 접수된 명령과 같은 모양이다. */
    private static long insertUnreserved(ReservationFixture f, OrderIntake intake, long fencing) {
        long seq = f.owner.sql("SELECT next_journal_value('command_seq')").query(Long.class).single();
        CommandDraft draft = f.draft(intake);
        f.owner.sql("""
                        INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                     assigned_order_id, engine_ts, tie_break, payload, fencing_token,
                                                     checksum)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """)
                .params(seq, draft.marketId(), draft.type().name(), draft.userId(), draft.clientOrderId(),
                        draft.assignedOrderId(), draft.engineTs(), draft.tieBreak(), draft.payloadJson(), fencing,
                        JournalChecksum.of(seq, draft))
                .update();
        return seq;
    }
}
