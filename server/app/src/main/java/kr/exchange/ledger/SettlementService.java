package kr.exchange.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventPayloads;
import kr.exchange.journal.FencingErrors;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.StaleLeaderException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이벤트 유형별 정산과 event_journal append의 원자 커밋 경로(DB 설계 §5.1~§5.3, §3.1 6.2).
 * 단일 라이터 루프의 sink이자 크래시 창 복구(§5.5 ②)의 재정산 경로다.
 *
 * 한 호출은 한 명령의 방출 배치이며 전부 한 트랜잭션이다. 이벤트만 적재되고 정산이 빠지는
 * 크래시 창, 또는 그 역이 구조적으로 불가능하다. 멱등 판정은 event_emission_registry의
 * (source_cmd_seq, event_ordinal) 존재 검사다(§5.5 ② v2.9 규칙). 이미 적재된 배치는
 * 정산도 이미 커밋된 것이므로 통째로 스킵한다. 레지스트리 23505는 동시 stale 라이터에
 * 대한 최후 안전망으로 남는다(트랜잭션 abort 후 fencing 경로로 수렴).
 *
 * ACCEPTED 예약 실패(잔고 부족 23514)는 배치 전체 롤백 + fail-fast다. 정본(§5.1)은
 * 잔고 부족을 접수 트랜잭션에서 REJECTED로 종결하지만, 그 판정은 게이트웨이 사전 예약
 * 검증(M1-4)의 몫이다. 엔진이 이미 수락·매칭한 뒤의 사후 거절은 오더북과 저널의
 * disposition을 가르므로 여기서는 무결성 위반으로 취급해 루프를 세운다.
 */
public class SettlementService {

    private static final short PAYLOAD_SCHEMA_VERSION = 1;
    /** 수수료 샤드 수 K=1 박제. K 변경은 재생·catch-up의 샤드 배정 동일성을 깬다(DB 설계 §1.2). */
    private static final int FEE_SHARD = 0;
    /** notional(quote 지정) 매수의 quote 소진 scale. 엔진 소진액 규약과 동일해야 이벤트
     * 합산 재구성과 어긋나지 않는다(DB 설계 §5.2). 게이트 가드 아닌 산식 상수로 박제한다. */
    private static final int NOTIONAL_SOAK_SCALE = 18;

    /**
     * 정산 머리말. 종전 세 왕복을 한 왕복으로 묶는다.
     *
     * 무엇을 묶는가. (1) fencing 검증과 리스 행 FOR KEY SHARE(V41), (2) 이 명령의 이벤트가 이미
     * 적재됐는지 세는 멱등 탐침, (3) 정산 대상 명령 읽기다. 셋 다 cmdSeq 하나로 정해지는
     * 조회이고 서로의 결과를 입력으로 쓰지 않는다. 그래서 한 문장에 담을 수 있다.
     *
     * (1) 은 V30 이 이미 두 문장을 하나로 합쳐 둔 것이다. assert_current_leader 호출과
     * FOR SHARE 조회를 따로 보내던 것을 함수 하나가 겸한다. 거부 시 예외 메시지가 종전과
     * 같아 FencingErrors 의 강등 분류가 바뀌지 않는다. 그 성질은 여기서도 그대로다.
     *
     * 왜 하는가. 2026-09-10 실측에서 명령 하나의 직렬 시간 21.36밀리초 중 매칭이 0.02 이고
     * 나머지가 전부 정산이었다. 대기 사유는 행 잠금이 3.8퍼센트뿐이라 경합이 아니었다.
     * 남은 설명이 왕복 횟수였다. V30 이 같은 판단으로 (1) 을 두 문장에서 한 문장으로 줄였고
     * 이번은 그 자리에 (2) 와 (3) 을 마저 붙인다.
     *
     * 왜 이 모양인가. 한 행짜리 원천에 command_journal 을 LEFT JOIN 한다. 안쪽 조인으로 두면
     * 명령 행이 없을 때 결과가 0행이 되고, 그러면 assert_and_lock_leader 가 아예 실행되지
     * 않아 fencing 거부와 명령 부재를 가릴 수 없게 된다. LEFT JOIN 이면 언제나 한 행이고
     * 판정 순서를 종전과 같이 유지할 수 있다.
     *
     * 판정 순서는 바뀌지 않는다. 리스 거부, 멱등 스킵, 부분 적재, 명령 부재 순이다.
     */
    private static final String PROLOGUE_SQL =
            "SELECT assert_and_lock_leader(?, ?) AS epoch,"
            + " (SELECT count(*) FROM event_emission_registry"
            + "   WHERE source_cmd_seq = ?) AS registered,"
            + " " + CommandJournalReader.ENTRY_COLUMNS
            + " FROM (VALUES (1)) AS one(x)"
            + " LEFT JOIN command_journal ON seq = ?";

    /** 머리말 한 왕복의 결과. entry 는 명령 행이 없으면 null 이다. */
    private record Prologue(Long epoch, long registered, JournalEntry entry) {
    }

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO event_journal
              (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
               payload_schema_version, fencing_epoch, created_at, market_id, market_seq)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

    /**
     * 마켓 순서 대역. event_seq 를 marketId * MARKET_SEQ_BAND + marketSeq 로 파생한다.
     *
     * 왜 파생인가. 종전에는 event_seq 를 journal_counters 한 행에서 뽑았고 그 UPDATE
     * 행 잠금이 커밋까지 유지됐다. 채번이 원장 게시 루프보다 앞이라 정산 전 구간이
     * 그 한 행에서 직렬화됐다(2026-09-10 실측: 라이터 12개에서 초당 43.5). 마켓별
     * 카운터로 가르면 514.5 다. 파생으로 두면 채번이 마켓당 한 번이고 전역 행을
     * 아예 건드리지 않는다.
     *
     * 왜 10^7 인가. 마켓 id 가 1 에서 12 이므로 최대 event_seq 가 1.3 x 10^8 이고,
     * event_journal 의 p0 파티션 경계 2 x 10^8 안에 들어간다. 파티션을 건드리지 않으려는
     * 값이다. 대역을 넘으면 next_market_journal_value 가 예외를 던진다. 조용히 넘어가면
     * 다음 마켓 대역을 침범해 event_seq 전역 유일성이 깨진다.
     */
    private static final long MARKET_SEQ_BAND = 10_000_000L;

    private static final String SELECT_ORDER_SQL = """
            SELECT id, user_id, side, ord_type, price, quantity, quote_order_qty,
                   filled_qty, remaining_qty, canceled_qty, executed_quote,
                   locked_account_id, locked_amount, status, version
            FROM orders
            WHERE id IN (?, ?)
            ORDER BY id
            FOR UPDATE
            """;

    private record OrderRow(long id, long userId, String side, String ordType,
                            BigDecimal price, BigDecimal quantity, BigDecimal quoteOrderQty,
                            BigDecimal filledQty, BigDecimal remainingQty, BigDecimal canceledQty,
                            BigDecimal executedQuote, Long lockedAccountId, BigDecimal lockedAmount,
                            String status, long version) {
        boolean notional() {
            return quoteOrderQty != null;
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    // 접수 경로를 깨우는 신호. 없으면(구 생성자) 신호를 보내지 않고 접수는 폴링으로 돈다.
    private ProjectionSignal projectionSignal;
    private final LeaderLeaseService lease;
    private final ObjectMapper objectMapper;
    private final MarketCatalog catalog;
    private final CommandPayloadMapper payloadMapper;
    // 좀비 리더 판정의 진행 축(§5.4). 승격 재생의 크래시 창 재정산도 이 경로를 타므로, 접수가
    // 닫힌 승격 구간에서도 진행성이 관측돼 오탐 강등이 나지 않는다.
    private final LeaderWriteProgress progress;
    // 계정 id는 계정 생성 시점에 확정되고 (owner, purpose, asset, shard)당 불변이라 캐시한다.
    // 체결마다 5~6회 왕복하던 매핑 조회를 제거한다. 성공 조회만 저장하고 미스는 캐시하지 않는다.
    private final Map<Short, Long> feeAccountCache = new ConcurrentHashMap<>();
    private final Map<UserAccountKey, Long> userAccountCache = new ConcurrentHashMap<>();

    private record UserAccountKey(long userId, String purpose, short assetId) {
    }

    public SettlementService(JdbcClient jdbc, TransactionTemplate tx, LeaderLeaseService lease,
                             ObjectMapper objectMapper, MarketCatalog catalog,
                             CommandPayloadMapper payloadMapper) {
        this(jdbc, tx, lease, objectMapper, catalog, payloadMapper,
                new LeaderWriteProgress());
    }

    public SettlementService(JdbcClient jdbc, TransactionTemplate tx, LeaderLeaseService lease,
                             ObjectMapper objectMapper, MarketCatalog catalog,
                             CommandPayloadMapper payloadMapper,
                             LeaderWriteProgress progress) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.lease = lease;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.payloadMapper = payloadMapper;
        this.progress = progress;
    }

    /**
     * 접수 경로 깨우기 신호를 붙인다. 선택 배선이다. 붙이지 않으면 정산은 신호를 보내지 않고
     * 접수는 종전 폴링으로 돈다. 생성자를 늘리지 않는 이유는 기존 호출부와 테스트가 여섯 곳이라
     * 그 전부를 신호와 무관하게 두기 위해서다.
     */
    public void attachProjectionSignal(ProjectionSignal signal) {
        this.projectionSignal = signal;
    }

    /** 단일 명령의 방출 배치를 원자 정산하고 event_seq가 부여된 목록을 반환한다. */
    public List<EngineEvent> appendAndSettle(List<EngineEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        long cmdSeq = events.get(0).sourceCmdSeq();
        for (EngineEvent event : events) {
            if (event.sourceCmdSeq() != cmdSeq) {
                // 트랜잭션 경계가 명령 단위라는 계약. 섞이면 §5.5 ②의 배치 멱등 판정이 무너진다.
                throw new IllegalArgumentException(
                        "정산 배치는 단일 명령의 방출분이어야 한다: " + cmdSeq + " vs " + event.sourceCmdSeq());
            }
        }
        LeaderIdentity leader = lease.requireLeader();
        try {
            List<EngineEvent> settled = tx.execute(status -> settleInTx(leader, cmdSeq, events));
            // 커밋 성공만 진행으로 센다. 예외 경로는 카운터를 건드리지 않는다(§5.4 좀비 리더 판정).
            progress.recordCompletion();
            // 커밋 뒤에만 깨운다. 커밋 전에 깨우면 접수가 조회해도 행이 아직 안 보인다.
            // 신호 실패가 정산 결과를 바꾸면 안 되므로 예외를 삼킨다. 놓친 접수는 폴링으로 간다.
            if (projectionSignal != null) {
                try {
                    for (EngineEvent event : settled) {
                        long id = event.orderId();
                        if (id > 0) {
                            projectionSignal.settled(id);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 신호는 최적화다. 실패해도 정산은 이미 커밋됐고 접수는 폴링으로 회수한다.
                }
            }
            return settled;
        } catch (StaleLeaderException e) {
            // fencing 거부는 리스 권위를 재확인한 뒤에만 강등한다(§5.4 강등 사유 분류). 거부
            // 즉시 강등하면 정당한 현재 리더가 일시 원인으로 영구 후보에서 빠진다.
            lease.demoteUnlessStillLeader(e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            if (FencingErrors.isFencingRejection(e)) {
                lease.demoteUnlessStillLeader(
                        "assert_current_leader 거부(정산): " + FencingErrors.rejectionDetail(e));
            }
            throw e;
        }
    }

    private List<EngineEvent> settleInTx(LeaderIdentity leader, long cmdSeq, List<EngineEvent> events) {
        // 머리말 한 왕복. fencing 검증과 FOR KEY SHARE 잠금(V41)에 멱등 탐침과 명령 읽기를
        // 함께 실어 세 왕복을 하나로 줄인다. 거부 메시지는 종전과 같아 강등 분류가 그대로다.
        // 잠금을 잡은 뒤 판정하므로 판정과 커밋 사이에 epoch 이 바뀌지 않는다.
        Prologue prologue = jdbc.sql(PROLOGUE_SQL)
                .params(leader.holder(), leader.epoch(), cmdSeq, cmdSeq)
                .query((rs, rowNum) -> new Prologue(
                        (Long) rs.getObject("epoch"),
                        rs.getLong("registered"),
                        rs.getObject("seq") == null ? null : CommandJournalReader.mapEntry(rs)))
                .single();

        Long epoch = prologue.epoch();
        if (epoch == null) {
            throw new StaleLeaderException(
                    "stale 리더 정산 거부: holder=" + leader.holder() + ", epoch=" + leader.epoch());
        }

        long registered = prologue.registered();
        if (registered == events.size()) {
            // 이벤트가 적재돼 있으면 정산도 같은 커밋에 포함돼 있었다. 재정산 없이 스킵(§5.5 ②).
            // event_seq는 레지스트리 적재분으로 채워 반환한다. WS 게시 등 호출측이 seq를 쓴다.
            return withRegisteredSeqs(cmdSeq, events);
        }
        if (registered != 0) {
            throw new IllegalStateException("명령 " + cmdSeq + "의 이벤트가 부분 적재 상태다: "
                    + registered + "/" + events.size() + " (원자 커밋 계약 위반)");
        }

        JournalEntry entry = prologue.entry();
        if (entry == null) {
            throw new IllegalStateException("정산 대상 명령이 저널에 없다: seq=" + cmdSeq);
        }
        OrderCommand command = payloadMapper.toCommand(entry);
        CommandPayloadMapper.Extras extras = payloadMapper.extras(entry);

        // 마켓별 채번. 한 정산 배치는 명령 하나의 방출분이고 명령은 마켓 하나에 속하므로
        // 배치 전체가 같은 마켓이다. 그래서 채번이 배치당 한 번이다.
        int marketId = command.marketId();
        long marketStart = jdbc.sql("SELECT next_market_journal_value(?::smallint, 'event_seq', ?)")
                .params(marketId, events.size())
                .query(Long.class)
                .single();

        List<EngineEvent> assigned = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            long marketSeq = marketStart + i;
            EngineEvent event = events.get(i)
                    .withEventSeq(marketId * MARKET_SEQ_BAND + marketSeq);
            Fees.TradeFees fees = event.type() == EngineEvent.EventType.TRADE
                    ? Fees.of(event, extras.makerFeeRate(), extras.takerFeeRate(),
                              catalog.meta(event.marketId()))
                    : null;
            insertEvent(epoch, event, fees, marketId, marketSeq);
            switch (event.type()) {
                case ACCEPTED -> settleAccepted(command, extras);
                case REJECTED -> settleRejected(command, extras, event);
                case TRADE -> settleTrade(event, fees, epoch);
                case CANCELED -> settleCanceled(event);
            }
            assigned.add(event);
        }
        return assigned;
    }

    // ------------------------------------------------------------------
    // ACCEPTED: 예약(§5.1) + orders 생성
    // ------------------------------------------------------------------

    private void settleAccepted(OrderCommand command, CommandPayloadMapper.Extras extras) {
        if (extras.externalId() == null) {
            // external_id는 접수 전 사전 발급 박제 계약(P0-4)이다. 없으면 재생마다 값이 달라진다.
            throw new IllegalStateException("external_id 미박제 명령: seq=" + command.seq());
        }
        MarketCatalog.MarketMeta meta = catalog.meta(command.marketId());
        boolean notional = command.ordType() == OrderCommand.OrdType.MARKET
                && command.side() == OrderCommand.Side.BUY;
        short assetId;
        BigDecimal reserve;
        if (command.side() == OrderCommand.Side.BUY) {
            assetId = meta.quoteAssetId();
            // LIMIT 매수 예약은 UP 양자화. 체결별 차감이 DOWN 절사라 예약이 모자라면 중간
            // 체결이 락 잔여를 초과한다. 초과 예약 잔차는 최종 체결의 실잔여 소비가 환급
            // 분개로 되돌린다(§5.2 (6), API §4-B-5).
            reserve = notional
                    ? command.quoteOrderQty()
                    : Fees.quantize(command.price().multiply(command.quantity()),
                                    meta.quoteScale(), RoundingMode.UP);
        } else {
            assetId = meta.baseAssetId();
            reserve = command.quantity();
        }
        long avail = userAccount(command.userId(), "AVAILABLE", assetId);
        long locked = userAccount(command.userId(), "LOCKED", assetId);
        // 잔고 부족(23514)은 여기서 배치 전체를 롤백시킨다. 클래스 주석의 fail-fast 계약.
        jdbc.sql("SELECT reserve_funds(?, ?, ?, ?, ?)")
                .params(command.assignedOrderId(), avail, locked, assetId, reserve)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        INSERT INTO orders
                          (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                           time_in_force, price, quantity, quote_order_qty, remaining_qty,
                           locked_account_id, locked_amount, status, command_seq)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                        """)
                .params(command.assignedOrderId(), extras.externalId(), command.userId(),
                        command.marketId(), command.clientOrderId(),
                        command.side().name(), command.ordType().name(),
                        (command.timeInForce() == null ? OrderCommand.TimeInForce.GTC
                                : command.timeInForce()).name(),
                        command.price(), command.quantity(), command.quoteOrderQty(),
                        notional ? null : command.quantity(),
                        locked, reserve, command.seq())
                .update();
    }

    // ------------------------------------------------------------------
    // REJECTED: orders 기록만(원장 무변경, §5.1 (a') 거절 경로 준용)
    // ------------------------------------------------------------------

    private void settleRejected(OrderCommand command, CommandPayloadMapper.Extras extras,
                                EngineEvent event) {
        if (command.type() != OrderCommand.CommandType.NEW) {
            // CANCEL 거절(order_not_found)은 대상 주문 상태를 바꾸지 않는다. 저널 disposition으로 충분.
            return;
        }
        if (orderExists(command.assignedOrderId())) {
            // duplicate_order_id 거절. 원 주문 행이 정본이므로 덮지 않는다.
            return;
        }
        if (!insertableShape(command) || extras.externalId() == null) {
            // orders CHECK(NOT NULL side/ord_type, 형태 제약)를 못 채우는 기형 명령은 프로젝션
            // 불가. disposition은 event_journal의 REJECTED가 보존하므로 기록 생략이 정합이다.
            return;
        }
        jdbc.sql("""
                        INSERT INTO orders
                          (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                           time_in_force, price, quantity, quote_order_qty, remaining_qty,
                           locked_amount, status, reject_reason, command_seq)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 'REJECTED', ?, ?)
                        """)
                .params(command.assignedOrderId(), extras.externalId(), command.userId(),
                        command.marketId(), command.clientOrderId(),
                        command.side().name(), command.ordType().name(),
                        (command.timeInForce() == null ? OrderCommand.TimeInForce.GTC
                                : command.timeInForce()).name(),
                        command.price(), command.quantity(), command.quoteOrderQty(),
                        event.reason(), command.seq())
                .update();
    }

    private boolean insertableShape(OrderCommand c) {
        if (c.side() == null || c.ordType() == null) {
            return false;
        }
        if (c.ordType() == OrderCommand.OrdType.MARKET
                && c.timeInForce() == OrderCommand.TimeInForce.POST_ONLY) {
            return false;
        }
        if (c.ordType() == OrderCommand.OrdType.MARKET && c.side() == OrderCommand.Side.BUY) {
            return positive(c.quoteOrderQty()) && c.quantity() == null;
        }
        if (c.ordType() == OrderCommand.OrdType.LIMIT && !positive(c.price())) {
            return false;
        }
        return positive(c.quantity()) && c.quoteOrderQty() == null;
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    // ------------------------------------------------------------------
    // TRADE: §5.2 전문
    // ------------------------------------------------------------------

    private void settleTrade(EngineEvent event, Fees.TradeFees fees, long epoch) {
        long makerOid = event.counterOrderId();
        long takerOid = event.orderId();
        List<OrderRow> rows = lockOrders(makerOid, takerOid);
        OrderRow maker = rowById(rows, makerOid);
        OrderRow taker = rowById(rows, takerOid);
        MarketCatalog.MarketMeta meta = catalog.meta(event.marketId());

        boolean takerBuys = event.takerSide() == OrderCommand.Side.BUY;
        OrderRow buyer = takerBuys ? taker : maker;
        OrderRow seller = takerBuys ? maker : taker;
        BigDecimal buyerFee = takerBuys ? fees.takerFee() : fees.makerFee();   // base 자산
        BigDecimal sellerFee = takerBuys ? fees.makerFee() : fees.takerFee();  // quote 자산

        BigDecimal qty = event.quantity();
        BigDecimal price = event.price();

        // 매도측 base 차감. 예약이 수량 그대로라 잔차가 없고, 최종 체결은 실잔여를 소비한다.
        boolean sellerFilled = seller.remainingQty().compareTo(qty) == 0;
        BigDecimal baseOut = sellerFilled ? seller.lockedAmount() : qty;

        // 매수측 quote 차감. 체결대금은 quote scale DOWN 절사, 최종 체결은 실잔여 전량
        // 소비 + 잔차 환급(§5.2 (6)). notional 주문은 엔진 소진액 규약(scale 18 DOWN)과
        // 동일 산식으로만 차감해 이벤트 합산 재구성과 어긋나지 않게 한다(README M1-3 인계).
        BigDecimal proceeds;
        BigDecimal quoteOut;
        BigDecimal refund;
        boolean buyerFilled;
        if (buyer.notional()) {
            quoteOut = Fees.quantize(qty.multiply(price), NOTIONAL_SOAK_SCALE, RoundingMode.DOWN);
            proceeds = quoteOut;
            refund = BigDecimal.ZERO;
            buyerFilled = buyer.lockedAmount().compareTo(quoteOut) == 0;
        } else {
            proceeds = Fees.quantize(qty.multiply(price), meta.quoteScale(), RoundingMode.DOWN);
            buyerFilled = buyer.remainingQty().compareTo(qty) == 0;
            if (buyerFilled) {
                quoteOut = buyer.lockedAmount();
                refund = quoteOut.subtract(proceeds);
            } else {
                quoteOut = Fees.quantize(qty.multiply(buyer.price()),
                        meta.quoteScale(), RoundingMode.DOWN);
                refund = Fees.quantize(qty.multiply(buyer.price().subtract(price)),
                        meta.quoteScale(), RoundingMode.DOWN);
            }
        }

        // 분개 구성(§5.2). 자산별 마지막 FEE leg는 나머지 분개의 음수 합으로 만들어
        // 양자화 잔차를 FEE_INCOME이 흡수한다. 0금액 leg는 삽입 생략.
        BigDecimal buyerBaseIn = qty.subtract(buyerFee);
        BigDecimal baseFeeLeg = baseOut.subtract(buyerBaseIn);
        BigDecimal sellerQuoteIn = proceeds.subtract(sellerFee);
        BigDecimal quoteFeeLeg = quoteOut.subtract(refund).subtract(sellerQuoteIn);
        if (baseFeeLeg.signum() < 0 || quoteFeeLeg.signum() < 0 || refund.signum() < 0) {
            // 산식 계약 위반. 음수 FEE leg는 수수료 계정을 잠식하므로 무음 통과 금지.
            throw new IllegalStateException("체결 분개 부호 위반: event_seq=" + event.eventSeq());
        }

        ArrayNode legs = objectMapper.createArrayNode();
        addLeg(legs, seller.lockedAccountId(), meta.baseAssetId(), baseOut.negate(), "TRADE");
        addLeg(legs, userAccount(buyer.userId(), "AVAILABLE", meta.baseAssetId()),
                meta.baseAssetId(), buyerBaseIn, "TRADE");
        addLeg(legs, feeAccount(meta.baseAssetId()), meta.baseAssetId(), baseFeeLeg, "FEE");
        addLeg(legs, buyer.lockedAccountId(), meta.quoteAssetId(), quoteOut.negate(), "TRADE");
        addLeg(legs, userAccount(buyer.userId(), "AVAILABLE", meta.quoteAssetId()),
                meta.quoteAssetId(), refund, "TRADE");
        addLeg(legs, userAccount(seller.userId(), "AVAILABLE", meta.quoteAssetId()),
                meta.quoteAssetId(), sellerQuoteIn, "TRADE");
        addLeg(legs, feeAccount(meta.quoteAssetId()), meta.quoteAssetId(), quoteFeeLeg, "FEE");
        jdbc.sql("SELECT post_ledger_transaction('TRADE', ?, ?::jsonb)")
                .params(event.eventSeq(), legs.toString())
                .query(Long.class)
                .single();

        // trade_id = event_seq(P0-4). executed_at/created_at은 engine_ts 파생 명시 삽입.
        OffsetDateTime ts = toUtc(event.engineTs());
        jdbc.sql("""
                        INSERT INTO trades
                          (id, market_id, maker_order_id, taker_order_id, maker_user_id,
                           taker_user_id, taker_side, price, quantity, maker_fee, taker_fee,
                           fencing_epoch, executed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(event.eventSeq(), event.marketId(), makerOid, takerOid,
                        event.makerUserId(), event.takerUserId(), event.takerSide().name(),
                        price, qty, fees.makerFee(), fees.takerFee(), epoch, ts, ts)
                .update();

        applyTradeToOrder(seller, qty, proceeds, baseOut, sellerFilled, sellerFee);
        applyTradeToOrder(buyer, qty, proceeds, quoteOut, buyerFilled, buyerFee);

        // 아웃박스(§5.2 (7)): 동일 트랜잭션이라 dual-write가 없다. 수치는 text 박제.
        ObjectNode outbox = objectMapper.createObjectNode();
        outbox.put("tradeId", event.eventSeq());
        outbox.put("price", price.toPlainString());
        outbox.put("qty", qty.toPlainString());
        outbox.put("seq", event.eventSeq());
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
                        VALUES ('TRADE', ?, 'TradeExecuted', ?::jsonb)
                        """)
                .params(String.valueOf(event.eventSeq()), outbox.toString())
                .update();
    }

    // fee는 이 주문 측 수수료(maker면 maker_fee, taker면 taker_fee). paid_fee·trades_count는
    // 조회 핫패스가 trades를 재집계하지 않도록 filled_qty·executed_quote와 같은 UPDATE에서
    // 누적하는 프로젝션이다(재생 시 정산이 재구성, DB 설계 §5.2).
    private void applyTradeToOrder(OrderRow row, BigDecimal qty, BigDecimal proceeds,
                                   BigDecimal lockedOut, boolean filled, BigDecimal fee) {
        int updated;
        if (row.notional()) {
            updated = jdbc.sql("""
                            UPDATE orders
                            SET filled_qty = filled_qty + ?, executed_quote = executed_quote + ?,
                                locked_amount = locked_amount - ?,
                                paid_fee = paid_fee + ?, trades_count = trades_count + 1,
                                status = ?, version = version + 1
                            WHERE id = ? AND version = ?
                            """)
                    .params(qty, proceeds, lockedOut, fee,
                            filled ? "FILLED" : "PARTIAL", row.id(), row.version())
                    .update();
        } else {
            updated = jdbc.sql("""
                            UPDATE orders
                            SET filled_qty = filled_qty + ?, remaining_qty = remaining_qty - ?,
                                executed_quote = executed_quote + ?,
                                locked_amount = locked_amount - ?,
                                paid_fee = paid_fee + ?, trades_count = trades_count + 1,
                                status = ?, version = version + 1
                            WHERE id = ? AND version = ?
                            """)
                    .params(qty, qty, proceeds, lockedOut, fee,
                            filled ? "FILLED" : "PARTIAL", row.id(), row.version())
                    .update();
        }
        if (updated != 1) {
            // 단일 라이터 경로의 version 불일치는 재시도 대상이 아니라 불변식 위반이다(§5.2 (6)).
            throw new IllegalStateException("주문 갱신 실패(version 불일치): order=" + row.id());
        }
    }

    // ------------------------------------------------------------------
    // CANCELED: RELEASE 정산(§5.3)
    // ------------------------------------------------------------------

    private void settleCanceled(EngineEvent event) {
        BigDecimal qty = event.quantity();
        if (qty.signum() == 0) {
            // 0수량 무시 규약. 엔진은 방출하지 않지만 재정산 방어선으로 둔다.
            return;
        }
        OrderRow row = lockOrders(event.orderId(), event.orderId()).get(0);
        MarketCatalog.MarketMeta meta = catalog.meta(event.marketId());

        if (row.notional()) {
            // 시장가 매수: qty는 quote 금액 단위다(§5.3 (0) v2.8 정정 계약).
            boolean terminal = row.lockedAmount().compareTo(qty) == 0;
            releaseFunds(event.eventSeq(), row, meta.quoteAssetId(), qty);
            int updated = jdbc.sql("""
                            UPDATE orders
                            SET locked_amount = locked_amount - ?, status = ?, version = version + 1
                            WHERE id = ? AND version = ?
                            """)
                    .params(qty, terminal ? "CANCELED" : row.status(), row.id(), row.version())
                    .update();
            assertUpdated(updated, row.id());
            return;
        }

        BigDecimal newRemaining = row.remainingQty().subtract(qty);
        boolean terminal = newRemaining.signum() == 0;
        boolean buySide = "BUY".equals(row.side());
        short assetId = buySide ? meta.quoteAssetId() : meta.baseAssetId();
        // 비종결(STP 부분 감액) 매수는 수량 비례 환급을 DOWN 절사한다. 절사 잔차는 락에 남고
        // 종결 시 실잔여 전량 해제가 되돌리므로 dust 누수가 없다.
        BigDecimal release = terminal
                ? row.lockedAmount()
                : (buySide
                        ? Fees.quantize(qty.multiply(row.price()), meta.quoteScale(), RoundingMode.DOWN)
                        : qty);
        if (release.signum() > 0) {
            releaseFunds(event.eventSeq(), row, assetId, release);
        }
        int updated = jdbc.sql("""
                        UPDATE orders
                        SET canceled_qty = canceled_qty + ?, remaining_qty = ?,
                            locked_amount = ?, status = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """)
                .params(qty, newRemaining,
                        terminal ? BigDecimal.ZERO : row.lockedAmount().subtract(release),
                        terminal ? "CANCELED" : row.status(), row.id(), row.version())
                .update();
        assertUpdated(updated, row.id());
    }

    private void releaseFunds(long eventSeq, OrderRow row, short assetId, BigDecimal amount) {
        long avail = userAccount(row.userId(), "AVAILABLE", assetId);
        // business_event_id는 해제를 유발한 이벤트의 event_seq. 예약(order_id)과 구분한다(§5.3).
        jdbc.sql("SELECT release_funds(?, ?, ?, ?, ?)")
                .params(eventSeq, row.lockedAccountId(), avail, assetId, amount)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------
    // 공용
    // ------------------------------------------------------------------

    private void insertEvent(long epoch, EngineEvent event, Fees.TradeFees fees,
                             int marketId, long marketSeq) {
        String payload = fees == null
                ? EventPayloads.json(event, objectMapper)
                : EventPayloads.json(event, objectMapper,
                        Fees.text(fees.makerFee()), Fees.text(fees.takerFee()));
        jdbc.sql(INSERT_EVENT_SQL)
                .params(event.eventSeq(), event.sourceCmdSeq(), event.eventOrdinal(),
                        event.type().name(), payload, PAYLOAD_SCHEMA_VERSION, epoch,
                        toUtc(event.engineTs()), (short) marketId, marketSeq)
                .update();
    }

    private List<EngineEvent> withRegisteredSeqs(long cmdSeq, List<EngineEvent> events) {
        Map<Integer, Long> byOrdinal = new HashMap<>();
        jdbc.sql("SELECT event_ordinal, event_seq FROM event_emission_registry WHERE source_cmd_seq = ?")
                .params(cmdSeq)
                .query((rs, rowNum) -> {
                    byOrdinal.put(rs.getInt("event_ordinal"), rs.getLong("event_seq"));
                    return 0;
                })
                .list();
        List<EngineEvent> assigned = new ArrayList<>(events.size());
        for (EngineEvent event : events) {
            Long seq = byOrdinal.get(event.eventOrdinal());
            if (seq == null) {
                throw new IllegalStateException("레지스트리에 ordinal 누락: cmd=" + cmdSeq
                        + ", ordinal=" + event.eventOrdinal());
            }
            assigned.add(event.withEventSeq(seq));
        }
        return assigned;
    }

    private List<OrderRow> lockOrders(long oid1, long oid2) {
        // id 오름차순 FOR UPDATE(§5.0, §5.2). balances 잠금은 post_ledger_transaction 내부다.
        List<OrderRow> rows = jdbc.sql(SELECT_ORDER_SQL)
                .params(oid1, oid2)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getLong("id"), rs.getLong("user_id"),
                        rs.getString("side"), rs.getString("ord_type"),
                        rs.getBigDecimal("price"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("quote_order_qty"), rs.getBigDecimal("filled_qty"),
                        rs.getBigDecimal("remaining_qty"), rs.getBigDecimal("canceled_qty"),
                        rs.getBigDecimal("executed_quote"),
                        (Long) rs.getObject("locked_account_id"),
                        rs.getBigDecimal("locked_amount"),
                        rs.getString("status"), rs.getLong("version")))
                .list();
        int expected = oid1 == oid2 ? 1 : 2;
        if (rows.size() != expected) {
            throw new IllegalStateException(
                    "정산 대상 주문 행 누락: " + oid1 + ", " + oid2 + " (조회 " + rows.size() + "건)");
        }
        return rows;
    }

    private static OrderRow rowById(List<OrderRow> rows, long id) {
        for (OrderRow row : rows) {
            if (row.id() == id) {
                return row;
            }
        }
        throw new IllegalStateException("주문 행 없음: " + id);
    }

    private void addLeg(ArrayNode legs, long accountId, short assetId,
                        BigDecimal amount, String entryType) {
        if (amount.signum() == 0) {
            return;
        }
        ObjectNode leg = legs.addObject();
        leg.put("account_id", accountId);
        leg.put("asset_id", assetId);
        leg.put("amount", amount);
        leg.put("entry_type", entryType);
    }

    private long userAccount(long userId, String purpose, short assetId) {
        // computeIfAbsent의 매핑 함수가 던지면 ConcurrentHashMap은 항목을 남기지 않아 미스는 캐시되지 않는다.
        return userAccountCache.computeIfAbsent(new UserAccountKey(userId, purpose, assetId), k ->
                jdbc.sql("""
                                SELECT id FROM accounts
                                WHERE owner_type = 'USER' AND owner_id = ? AND purpose = ?
                                  AND asset_id = ? AND shard = 0
                                """)
                        .params(k.userId(), k.purpose(), k.assetId())
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> new IllegalStateException("사용자 계정 없음: user=" + k.userId()
                                + ", purpose=" + k.purpose() + ", asset=" + k.assetId())));
    }

    private long feeAccount(short assetId) {
        return feeAccountCache.computeIfAbsent(assetId, k ->
                jdbc.sql("""
                                SELECT id FROM accounts
                                WHERE owner_type = 'SYSTEM' AND purpose = 'FEE_INCOME'
                                  AND asset_id = ? AND shard = ?
                                """)
                        .params(k, FEE_SHARD)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> new IllegalStateException("수수료 계정 없음: asset=" + k)));
    }

    private boolean orderExists(long orderId) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE id = ?")
                .params(orderId)
                .query(Long.class)
                .single() > 0;
    }

    private static void assertUpdated(int updated, long orderId) {
        if (updated != 1) {
            throw new IllegalStateException("주문 갱신 실패(version 불일치): order=" + orderId);
        }
    }

    private static OffsetDateTime toUtc(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }
}
