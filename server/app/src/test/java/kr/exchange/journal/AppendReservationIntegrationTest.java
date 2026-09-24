package kr.exchange.journal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.ledger.Fees;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 접수 시점 예약의 append 계약(ADR 0034 S3, S3 설계 6-2, DB 설계 §3.5). 전환 함수로 P2 에 올린 DB 에서 배포와 같은 앱
 * 로그인으로 실제 CommandJournalAppender 를 돈다.
 */
class AppendReservationIntegrationTest {

    private static final OrderCommand.Side BUY = OrderCommand.Side.BUY;

    @Test
    void reservationHappensInsideTheAppendTransaction() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_append", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            CommandJournalAppender appender = f.appender(f.lease(), true);
            OrderIntake buy = f.limit(KRW_BTC, alice, BUY, "50000", "0.3");

            AppendResult ack = f.append(appender, buy);

            assertTrue(ack.reserved(), "P2 NEW 가 예약되지 않았다");
            assertFalse(ack.replayed());
            // 0.3 x 50000 = 15000 KRW. 예약 행은 이 seq·주문·사용자·마켓이고 payload 해시가 저널 행과 같다.
            assertEquals(buy.assignedOrderId() + "|" + alice + "|1|1|15000|true|true", f.owner.sql("""
                            SELECT r.order_id || '|' || r.user_id || '|' || r.market_id || '|' || r.asset_id || '|'
                                   || r.amount::numeric(40,0) || '|'
                                   || (r.payload_sha256 = sha256(convert_to(c.payload::text, 'UTF8')))::text || '|'
                                   || (r.locked_account_id = ? AND r.available_account_id = ?)::text
                            FROM order_reservations r JOIN command_journal c ON c.seq = r.command_seq
                            WHERE r.command_seq = ?
                            """)
                    .params(f.account(alice, "LOCKED", KRW), f.account(alice, "AVAILABLE", KRW), ack.seq())
                    .query(String.class).single());
            // 원장 RESERVE 거래는 봉인됐고 business_event_id 가 주문 id 다.
            assertEquals("SEALED|" + buy.assignedOrderId(), f.owner.sql("""
                            SELECT t.status || '|' || t.business_event_id FROM ledger_transactions t
                            JOIN order_reservations r ON r.ledger_txn_id = t.id
                            WHERE r.command_seq = ? AND t.business_event_type = 'RESERVE'
                            """).param(ack.seq()).query(String.class).single());
            assertEquals(0, new BigDecimal("985000").compareTo(f.available(alice, KRW)));
            assertEquals(0, new BigDecimal("15000").compareTo(f.locked(alice, KRW)));
        }
    }

    @Test
    void insufficientFundsRollsBackJournalCounterReservationAndLedger() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_short", 2)) {
            long bob = f.user("bob", new BigDecimal("10000"));
            LeaderLeaseService lease = f.lease();
            LeaderWriteProgress progress = new LeaderWriteProgress();
            CommandJournalAppender appender = new CommandJournalAppender(f.app, f.appTx, lease,
                    new SimpleMeterRegistry(), progress);
            appender.setRequireReservation(true);
            // 양성 축: 잔고 안의 주문은 예약되고 진행 카운터가 오른다.
            assertTrue(f.append(appender, f.limit(KRW_BTC, bob, BUY, "1000", "1")).reserved());
            assertEquals(1, progress.completions());
            String before = snapshot(f);

            OrderIntake tooBig = f.limit(KRW_BTC, bob, BUY, "50000", "0.3");
            DataAccessException e = assertThrows(DataAccessException.class, () -> f.append(appender, tooBig));

            assertEquals("23514", SqlErrors.sqlState(e));
            assertEquals("chk_balance_nonneg", SqlErrors.constraintName(e));
            assertTrue(SqlErrors.isInsufficientFunds(e));
            assertEquals(before, snapshot(f), "잔고 부족 롤백이 저널·카운터·예약·원장에 흔적을 남겼다");
            assertEquals(1, progress.completions(), "실패한 append 가 진행으로 셌다");
            assertTrue(lease.isLeader(), "잔고 부족이 강등을 불렀다");
            assertEquals(0, new BigDecimal("9000").compareTo(f.available(bob, KRW)));
        }
    }

    @Test
    void replayedRetransmissionDoesNotReserveTwice() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_replay", 2)) {
            long carol = f.user("carol", new BigDecimal("100000"));
            CommandJournalAppender appender = f.appender(f.lease(), true);
            OrderIntake first = f.limit(KRW_BTC, carol, BUY, "50000", "0.3");
            AppendResult original = f.append(appender, first);
            // 재전송은 게이트웨이가 새 주문 id 를 채번해 보낸다. 머리말은 원본을 돌려준다.
            OrderIntake again = f.intake(KRW_BTC, carol, first.clientOrderId(), f.nextOrderId(), BUY,
                    OrderCommand.OrdType.LIMIT, null, new BigDecimal("50000"), new BigDecimal("0.3"), null);

            AppendResult replay = f.append(appender, again);

            assertTrue(replay.replayed());
            assertFalse(replay.reserved(), "재전송이 예약했다");
            assertEquals(original.seq(), replay.seq());
            assertEquals(first.assignedOrderId(), replay.assignedOrderId());
            assertEquals(1, f.count("SELECT count(*) FROM order_reservations"));
            assertEquals(1, f.count("SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE'"));
            assertEquals(0, new BigDecimal("15000").compareTo(f.locked(carol, KRW)));
        }
    }

    @Test
    void staleJournalInsertRollsBackReservationRowLedgerAndSequence() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_stale", 2)) {
            long dave = f.user("dave", new BigDecimal("100000"));
            LeaderLeaseService lease = f.lease();
            OrderIntake buy = f.limit(KRW_BTC, dave, BUY, "50000", "0.3");
            CommandDraft draft = f.draft(buy);
            String before = snapshot(f);
            try (Connection c = f.db.app()) {
                c.setAutoCommit(false);
                long seq;
                try (PreparedStatement ps = c.prepareStatement(CommandJournalAppender.PROLOGUE_SQL)) {
                    bindPrologue(ps, lease, draft, buy.reserveCheck());
                    ResultSet rs = ps.executeQuery();
                    assertTrue(rs.next());
                    assertTrue(rs.getBoolean("out_reserved"), "머리말이 예약하지 않았다");
                    seq = rs.getLong("out_seq");
                }
                // 머리말이 쥔 KEY SHARE 는 lease_until 갱신(FOR NO KEY UPDATE)을 막지 않는다. 그 사이 리스가 만료된다.
                assertEquals(1, f.owner.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second'"
                        + " WHERE resource = 'matching-engine'").update());
                try (PreparedStatement ps = c.prepareStatement(CommandJournalAppender.INSERT_SQL)) {
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
                    ps.setString(11, lease.requireLeader().holder());
                    ps.setLong(12, lease.requireLeader().epoch());
                    ResultSet rs = ps.executeQuery();
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "만료된 리스로 저널 행이 들어갔다");
                }
                c.rollback();
            }
            assertEquals(before, snapshot(f), "0행 뒤 롤백이 예약 행·원장·채번을 남겼다");
        }
    }

    /**
     * 저널 행과 예약의 결속(S3 설계 4-5·4-6, 3절 C10). P2 에서 저널 행 함수는 넣은 NEW 에 같은 seq·주문·사용자·마켓의 예약 행이
     * 있고 payload 해시가 같을 때만 커밋을 허용한다. 앱은 머리말과 저널 행에 같은 payload 를 넘기므로 이 확인은 앱 결함에 대한 DB
     * 쪽 방어선이다. (a) 머리말과 다른 가격의 payload 로 저널 행을 넣거나 (b) 예약하지 않은 머리말(is_new 거짓) 뒤에 NEW 행을
     * 넣거나 (c) 저널 행의 주문 id 가 예약과 다르면 거부되고 저널·예약·원장·채번이 남지 않는다.
     *
     * S3 구현 검토에서 이 확인을 IF false 로 바꿔도 종전 시험이 모두 통과한다는 지적을 받아 더했다. 음성 대조로 확인을 뺀 함수
     * 판에서는 같은 장면이 커밋되고 C31 이 그 행을 센다. 시험이 확인 자체를 잡는다는 것과 감사가 두 번째 방어선이라는 것을 함께
     * 보인다.
     */
    @Test
    void journalRowRefusesP2NewThatDoesNotMatchItsReservation() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_bind", 2)) {
            long hank = f.user("hank", new BigDecimal("1000000"));
            LeaderLeaseService lease = f.lease();
            OrderIntake buy = f.limit(KRW_BTC, hank, BUY, "50000", "0.3");
            OrderIntake otherPrice = sameOrderAtPrice(f, buy, "40000");
            String before = snapshot(f);

            String payload = refusedJournalRow(f, lease, buy, true, f.draft(otherPrice), buy.assignedOrderId());
            String unreserved = refusedJournalRow(f, lease, buy, false, f.draft(buy), buy.assignedOrderId());
            String orderId = refusedJournalRow(f, lease, buy, true, f.draft(buy), buy.assignedOrderId() + 1);

            for (String message : new String[] {payload, unreserved, orderId}) {
                assertTrue(message.contains("P2 주문 생성 명령에 맞는 예약 행이 없다"), message);
            }
            assertEquals(before, snapshot(f), "거부된 저널 행이 저널·예약·원장·채번에 흔적을 남겼다");
            assertTrue(lease.isLeader(), "결속 거부가 강등을 불렀다");

            // 양성 축: 같은 연결 경로에서 payload 와 주문 id 가 맞으면 들어간다. 시험 장치가 행을 넣을 수 있다는 뜻이다.
            try (Connection c = f.db.app()) {
                c.setAutoCommit(false);
                long seq = prologue(c, lease, f.draft(buy), true, buy.reserveCheck());
                assertEquals(1, journalRow(c, lease, seq, f.draft(buy), buy.assignedOrderId()));
                c.commit();
            }
            assertEquals(1, f.count("SELECT count(*) FROM command_journal"));
            assertEquals(1, f.count("SELECT count(*) FROM order_reservations"));
            assertEquals(0, f.invariant(InvariantCheck.valueOf("C31_RESERVATION_LINK")));

            // 음성 대조: 이 일회용 DB 에서 소유자가 결속 확인만 뺀 함수 판으로 바꾸면 (a)·(b) 장면이 커밋되고 C31 이 둘을 센다.
            String signature = "append_command_journal_row(bigint, smallint, varchar, bigint, varchar, bigint, bigint, bigint,"
                    + " jsonb, bigint, text, bigint)";
            String definition = f.owner.sql("SELECT pg_get_functiondef(?::regprocedure)").param(signature)
                    .query(String.class).single();
            String guard = "IF v_rows = 1 AND p_command_type = 'NEW'";
            assertTrue(definition.contains(guard), "결속 확인 문장을 찾지 못했다: " + definition);
            f.db.exec(definition.replace(guard, "IF false AND p_command_type = 'NEW'"));
            OrderIntake second = f.limit(KRW_BTC, hank, BUY, "50000", "0.1");
            OrderIntake third = f.limit(KRW_BTC, hank, BUY, "50000", "0.2");
            try (Connection c = f.db.app()) {
                c.setAutoCommit(false);
                long seq = prologue(c, lease, f.draft(second), true, second.reserveCheck());
                assertEquals(1, journalRow(c, lease, seq, f.draft(sameOrderAtPrice(f, second, "40000")),
                        second.assignedOrderId()), "확인을 뺀 판이 다른 payload 행을 넣지 않았다");
                long unreservedSeq = prologue(c, lease, f.draft(third), false, null);
                assertEquals(1, journalRow(c, lease, unreservedSeq, f.draft(third), third.assignedOrderId()));
                c.commit();
            }
            assertEquals(2, f.invariant(InvariantCheck.valueOf("C31_RESERVATION_LINK")),
                    "감사가 확인 없이 들어간 두 행을 세지 않았다");
        }
    }

    @Test
    void appAmountOrAssetMismatchIsRefusedWithoutJournal() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_check", 2)) {
            long erin = f.user("erin", new BigDecimal("100000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            OrderIntake buy = f.limit(KRW_BTC, erin, BUY, "50000", "0.3");
            String before = snapshot(f);
            for (ReserveCheck wrong : new ReserveCheck[] {
                    new ReserveCheck((short) KRW, new BigDecimal("14999")),
                    new ReserveCheck((short) BTC, new BigDecimal("15000")),
                    null}) {
                OrderIntake mismatched = ReservationFixture.withCheck(buy, wrong);
                DataAccessException e = assertThrows(DataAccessException.class, () -> f.append(appender, mismatched));
                assertTrue(e.getMostSpecificCause().getMessage().contains("예약액 대조 불일치"),
                        "대조 불일치가 아닌 이유로 거부됐다: " + e.getMostSpecificCause().getMessage());
            }
            assertEquals(before, snapshot(f));
            assertTrue(lease.isLeader(), "대조 불일치가 강등을 불렀다");
            // 양성 축: 맞는 대조값으로는 같은 주문이 들어간다.
            assertTrue(f.append(appender, buy).reserved());
        }
    }

    /**
     * 예약액 NUMERIC 경계(S3 설계 3절 P1, 6-2 (6)). DB 의 order_reserve_amount 가 정산·가드가 쓰는 Java 산식
     * Fees.quantize(price x qty, scale, UP)와 값과 글자 모두 같다. quote scale 0·2·8·18, 시장가 매수, 매도를 섞는다.
     */
    @Test
    void reserveAmountEqualsJavaQuantizationAtNumericBoundaries() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_numeric", 1)) {
            f.bootstrap.asset(10, "USD", "US Dollar", "FIAT", 2);
            f.bootstrap.asset(11, "Q18", "Scale Eighteen", "CRYPTO", 18);
            f.bootstrap.market(10, "USD-ETH", ReservationFixture.ETH, 10, ReservationFixture.RATE, ReservationFixture.RATE);
            f.bootstrap.market(11, "BTC-ETH", ReservationFixture.ETH, BTC, ReservationFixture.RATE, ReservationFixture.RATE);
            f.bootstrap.market(12, "Q18-ETH", ReservationFixture.ETH, 11, ReservationFixture.RATE, ReservationFixture.RATE);
            int[][] markets = {{KRW_BTC, 0}, {10, 2}, {11, 8}, {12, 18}};
            String[][] fixed = {
                    {"1", "1"}, {"0.1", "0.3"}, {"50000", "0.00000001"}, {"99999999.99999999", "0.00000001"},
                    {"0.000000000000000001", "1"}, {"123456789.123456789", "987654.321"}, {"2.5", "4"},
                    {"50000.00", "0.30000000"}, {"0.999999999999999999", "0.999999999999999999"},
                    {"1000000000", "0.000000000000000001"}, {"3", "0.333333333333333333"},
                    {"7.77", "1.1"}, {"0.01", "0.01"}, {"19.99", "3"}, {"100", "0.005"},
                    {"0.000001", "0.000001"}, {"1.000000000000000001", "1"}, {"12345.6789", "0.0001"},
                    {"0.5", "0.5"}, {"999999999999", "1"}};
            List<String[]> cases = new ArrayList<>();
            for (String[] c : fixed) {
                cases.add(c);
            }
            Random random = new Random(20260924L);
            for (int i = 0; i < 200; i++) {
                BigDecimal price = BigDecimal.valueOf(1 + random.nextInt(1_000_000_000))
                        .movePointLeft(random.nextInt(19));
                BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(1_000_000_000)).movePointLeft(random.nextInt(19));
                cases.add(new String[] {price.toPlainString(), qty.toPlainString()});
            }
            int compared = 0;
            int roundedUp = 0;
            long user = f.user("numeric", BigDecimal.ZERO);
            for (int i = 0; i < cases.size(); i++) {
                int[] market = markets[i % markets.length];
                BigDecimal price = new BigDecimal(cases.get(i)[0]);
                BigDecimal qty = new BigDecimal(cases.get(i)[1]);
                OrderIntake buy = f.intake(market[0], user, "n" + i, 90_000 + i, BUY, OrderCommand.OrdType.LIMIT,
                        null, price, qty, null);
                BigDecimal java = Fees.quantize(price.multiply(qty), market[1], RoundingMode.UP);
                String[] db = reserveAmount(f, market[0], f.mapper.toPayloadJson(buy));
                assertEquals(String.valueOf(quoteAsset(market[0])), db[0], "예약 자산");
                assertEquals(java.toPlainString(), db[1], "예약액 글자: price=" + price + " qty=" + qty
                        + " scale=" + market[1]);
                assertEquals(java.toPlainString(), db[2], "NUMERIC(40,18) 캐스트 뒤 값: " + price + " x " + qty);
                if (java.compareTo(price.multiply(qty)) != 0) {
                    roundedUp++;
                }
                compared++;
            }
            // 시장가 매수는 quote_order_qty 그대로, 매도는 qty 그대로다.
            OrderIntake notional = f.intake(KRW_BTC, user, "n-mkt", 99_001, BUY, OrderCommand.OrdType.MARKET,
                    OrderCommand.TimeInForce.IOC, null, null, new BigDecimal("123.456000"));
            assertEquals("1|123.456000", String.join("|",
                    List.of(reserveAmount(f, KRW_BTC, f.mapper.toPayloadJson(notional))).subList(0, 2)));
            OrderIntake sell = f.intake(KRW_BTC, user, "n-sell", 99_002, OrderCommand.Side.SELL,
                    OrderCommand.OrdType.LIMIT, null, new BigDecimal("50000"), new BigDecimal("0.12345678"), null);
            assertEquals(BTC + "|0.12345678", String.join("|",
                    List.of(reserveAmount(f, KRW_BTC, f.mapper.toPayloadJson(sell))).subList(0, 2)));
            assertEquals(220, compared);
            assertTrue(roundedUp > 50, "올림이 실제로 일어난 사례가 적다: " + roundedUp);
        }
    }

    @Test
    void p1DatabaseDoesNotReserve() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_p1", 1)) {
            long frank = f.user("frank", new BigDecimal("100000"));
            CommandJournalAppender appender = f.appender(f.lease(), false);

            AppendResult ack = f.append(appender, f.limit(KRW_BTC, frank, BUY, "50000", "0.3"));

            assertFalse(ack.reserved());
            assertEquals(0, f.count("SELECT count(*) FROM order_reservations"));
            assertEquals(0, f.count("SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE'"));
            assertEquals(0, BigDecimal.ZERO.compareTo(f.locked(frank, KRW)));
        }
    }

    @Test
    void p2ModeAppenderRefusesUnreservedNewOnP1Database() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("res_p2_on_p1", 1)) {
            long gina = f.user("gina", new BigDecimal("100000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            String before = snapshot(f);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> f.append(appender, f.limit(KRW_BTC, gina, BUY, "50000", "0.3")));

            assertTrue(e.getMessage().contains("재기동"), e.getMessage());
            assertEquals(before, snapshot(f), "예약 없는 NEW 가 커밋됐다");
            assertTrue(lease.isLeader());
        }
    }

    /** 저널 행 수, 명령 카운터, 예약 행 수, 원장 거래 수, 잔고 합. 롤백이 흔적을 남기지 않았는지 보는 축이다. */
    private static String snapshot(ReservationFixture f) {
        return f.owner.sql("""
                SELECT (SELECT count(*) FROM command_journal) || '|'
                    || (SELECT COALESCE(string_agg(name || '=' || next_value, ',' ORDER BY name), '')
                          FROM journal_counters WHERE name = 'command_seq') || '|'
                    || (SELECT count(*) FROM order_reservations) || '|'
                    || (SELECT count(*) FROM ledger_transactions) || '|'
                    || (SELECT COALESCE(sum(b.balance) FILTER (WHERE a.purpose = 'LOCKED'), 0) FROM balances b
                          JOIN accounts a ON a.id = b.account_id)
                """).query(String.class).single();
    }

    /** 같은 키·주문 id 에 가격만 다른 입력. payload 가 달라 해시가 다르다. */
    private static OrderIntake sameOrderAtPrice(ReservationFixture f, OrderIntake i, String price) {
        return f.intake(i.marketId(), i.userId(), i.clientOrderId(), i.assignedOrderId(), i.side(), i.ordType(),
                i.timeInForce(), new BigDecimal(price), i.quantity(), i.quoteOrderQty());
    }

    /**
     * 앱 연결 한 트랜잭션에서 10인자 머리말(intake 로, isNew 면 예약)과 저널 행(rowDraft, rowOrderId)을 부른다. 저널 행이 거부돼야
     * 하고 그 문구를 돌려준다. 끝에 롤백한다.
     */
    private static String refusedJournalRow(ReservationFixture f, LeaderLeaseService lease, OrderIntake intake,
                                            boolean isNew, CommandDraft rowDraft, long rowOrderId) throws Exception {
        try (Connection c = f.db.app()) {
            c.setAutoCommit(false);
            long seq = prologue(c, lease, f.draft(intake), isNew, isNew ? intake.reserveCheck() : null);
            SQLException e = assertThrows(SQLException.class, () -> journalRow(c, lease, seq, rowDraft, rowOrderId));
            c.rollback();
            return e.getMessage();
        }
    }

    /** 10인자 머리말. isNew 가 거짓이면 멱등 판정과 예약을 건너뛰고 채번만 한다. 새 seq 를 돌려준다. */
    private static long prologue(Connection c, LeaderLeaseService lease, CommandDraft draft, boolean isNew,
                                 ReserveCheck check) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(CommandJournalAppender.PROLOGUE_SQL)) {
            ps.setString(1, lease.requireLeader().holder());
            ps.setLong(2, lease.requireLeader().epoch());
            ps.setBoolean(3, isNew);
            ps.setLong(4, draft.userId());
            ps.setString(5, draft.clientOrderId());
            ps.setInt(6, draft.marketId());
            ps.setLong(7, draft.assignedOrderId());
            ps.setString(8, draft.payloadJson());
            if (check == null) {
                ps.setNull(9, java.sql.Types.SMALLINT);
                ps.setNull(10, java.sql.Types.NUMERIC);
            } else {
                ps.setShort(9, check.assetId());
                ps.setBigDecimal(10, check.amount());
            }
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(isNew, rs.getBoolean("out_reserved"), "머리말의 예약 여부가 기대와 다르다");
            return rs.getLong("out_seq");
        }
    }

    /** 저널 행 함수. 명령 종류는 NEW 이고 주문 id 만 따로 받는다. 넣은 행 수를 돌려준다. */
    private static int journalRow(Connection c, LeaderLeaseService lease, long seq, CommandDraft draft,
                                  long orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CommandJournalAppender.INSERT_SQL)) {
            ps.setLong(1, seq);
            ps.setInt(2, draft.marketId());
            ps.setString(3, CommandDraft.CommandType.NEW.name());
            ps.setLong(4, draft.userId());
            ps.setString(5, draft.clientOrderId());
            ps.setLong(6, orderId);
            ps.setLong(7, draft.engineTs());
            ps.setLong(8, draft.tieBreak());
            ps.setString(9, draft.payloadJson());
            ps.setLong(10, JournalChecksum.of(seq, draft));
            ps.setString(11, lease.requireLeader().holder());
            ps.setLong(12, lease.requireLeader().epoch());
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static void bindPrologue(PreparedStatement ps, LeaderLeaseService lease, CommandDraft draft,
                                     ReserveCheck check) throws Exception {
        ps.setString(1, lease.requireLeader().holder());
        ps.setLong(2, lease.requireLeader().epoch());
        ps.setBoolean(3, true);
        ps.setLong(4, draft.userId());
        ps.setString(5, draft.clientOrderId());
        ps.setInt(6, draft.marketId());
        ps.setLong(7, draft.assignedOrderId());
        ps.setString(8, draft.payloadJson());
        ps.setShort(9, check.assetId());
        ps.setBigDecimal(10, check.amount());
    }

    /** DB 계산값: 자산, 예약액 글자, NUMERIC(40,18) 캐스트 뒤 값을 Java 표기로. 앱 로그인으로 부른다. */
    private static String[] reserveAmount(ReservationFixture f, int market, String payload) {
        return f.app.sql("""
                        SELECT asset_id::text, amount::text, amount::numeric(40,18)::text
                        FROM order_reserve_amount(?::smallint, ?::jsonb)
                        """)
                .params(market, payload)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2),
                        new BigDecimal(rs.getString(3)).setScale(new BigDecimal(rs.getString(2)).scale())
                                .toPlainString()})
                .single();
    }

    private static int quoteAsset(int market) {
        return switch (market) {
            case KRW_BTC -> KRW;
            case 10 -> 10;
            case 11 -> BTC;
            case 12 -> 11;
            default -> throw new IllegalArgumentException("market " + market);
        };
    }
}
