package kr.exchange.engine.lease;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.ReserveCheck;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.Fees;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0034 S3 시험의 공통 배선(S3 설계 6-1). 시험마다 새 데이터베이스를 만들고(PartitionLeaseDb), 배포와 같은 앱 로그인으로
 * 리스·append·정산을 돈다. 모드는 소유자가 전환 함수(set_leader_protocol)로 바꾼다. 소유자 UPDATE 로 바꾸지 않는다.
 *
 * 마켓 셋(KRW-BTC 1, KRW-ETH 2, KRW-XRP 3)이 KRW 를 quote 로 함께 쓴다. 같은 사용자의 KRW 를 두 마켓이 다투는 장면을
 * 만들려는 것이다. 수수료율은 0.0005 다.
 */
public final class ReservationFixture implements AutoCloseable {

    public static final int KRW = 1;
    public static final int BTC = 2;
    public static final int ETH = 3;
    public static final int XRP = 4;
    public static final int KRW_BTC = 1;
    public static final int KRW_ETH = 2;
    public static final int KRW_XRP = 3;
    public static final BigDecimal RATE = new BigDecimal("0.0005");
    private static final long BASE_TS = 1_790_000_000_000L;

    public final PartitionLeaseDb db;
    public final HikariDataSource ownerPool;
    public final HikariDataSource appPool;
    public final JdbcClient owner;
    public final JdbcClient app;
    public final TransactionTemplate appTx;
    public final CommandPayloadMapper mapper = new CommandPayloadMapper(new ObjectMapper());
    public final AccountBootstrap bootstrap;
    private final AtomicLong orderIds = new AtomicLong(700_000);
    private final List<LeaderLeaseService> leases = new ArrayList<>();

    private ReservationFixture(PartitionLeaseDb db) {
        this.db = db;
        this.ownerPool = db.ownerPool();
        this.appPool = db.appPool();
        this.owner = JdbcClient.create(ownerPool);
        this.app = JdbcClient.create(appPool);
        this.appTx = new TransactionTemplate(new JdbcTransactionManager(appPool));
        this.bootstrap = new AccountBootstrap(owner);
    }

    /** 마켓 셋을 시드한 새 데이터베이스. protocol 이 2 면 전환 함수로 올린다. */
    public static ReservationFixture create(String prefix, int protocol) throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create(prefix).withRuntimeRoles();
        ReservationFixture f = new ReservationFixture(db);
        try {
            f.bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
            f.bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
            f.bootstrap.asset(ETH, "ETH", "Ether", "CRYPTO", 8);
            f.bootstrap.asset(XRP, "XRP", "Ripple", "CRYPTO", 8);
            f.bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW, RATE, RATE);
            f.bootstrap.market(KRW_ETH, "KRW-ETH", ETH, KRW, RATE, RATE);
            f.bootstrap.market(KRW_XRP, "KRW-XRP", XRP, KRW, RATE, RATE);
            if (protocol == 2) {
                f.switchProtocol(2, false);
            } else if (protocol != 1) {
                throw new IllegalArgumentException("protocol " + protocol);
            }
            return f;
        } catch (RuntimeException e) {
            // 시험마다 새 DB 라 컨테이너 연결 한도(100)를 나눠 쓴다. 준비가 실패해도 풀을 닫아 다음 시험의 연결을 남긴다.
            f.close();
            throw e;
        }
    }

    /** 소유자가 전환 함수를 부른다. 퇴역 행(자원, epoch)을 돌려준다. */
    public List<String[]> switchProtocol(int target, boolean allowDowngrade) {
        return owner.sql("SELECT out_resource, out_retired_epoch FROM set_leader_protocol(?::smallint, ?)")
                .params(target, allowDowngrade)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
                .list();
    }

    public int protocol() {
        return owner.sql("SELECT protocol FROM leader_protocol").query(Integer.class).single();
    }

    /** 네 자산 계정을 가진 사용자. krw 만큼 KRW 를 입금한다. */
    public long user(String name, BigDecimal krw) {
        long id = bootstrap.user(name + "@s3.test");
        for (int asset : new int[] {KRW, BTC, ETH, XRP}) {
            bootstrap.userAccounts(id, asset);
        }
        if (krw.signum() > 0) {
            bootstrap.deposit(id, KRW, krw);
        }
        return id;
    }

    public void deposit(long userId, int assetId, BigDecimal amount) {
        bootstrap.deposit(userId, assetId, amount);
    }

    /** 앱 로그인 리스. 만료된 리스를 얻는다. close 가 닫는다. */
    public LeaderLeaseService lease() {
        LeaderLeaseService lease = new LeaderLeaseService(app, Duration.ofMinutes(10), Duration.ofSeconds(1));
        assertTrue(lease.tryAcquire(), "앱 로그인 승격 실패");
        leases.add(lease);
        return lease;
    }

    public CommandJournalAppender appender(LeaderLeaseService lease, boolean requireReservation) {
        CommandJournalAppender appender = new CommandJournalAppender(app, appTx, lease);
        appender.setRequireReservation(requireReservation);
        return appender;
    }

    public SettlementService settlement(LeaderLeaseService lease) {
        return new SettlementService(app, appTx, lease, new ObjectMapper(), new MarketCatalog(app), mapper);
    }

    public long nextOrderId() {
        return orderIds.incrementAndGet();
    }

    public OrderIntake limit(int market, long user, OrderCommand.Side side, String price, String qty) {
        return limit(market, user, side, price, qty, null);
    }

    public OrderIntake limit(int market, long user, OrderCommand.Side side, String price, String qty,
                             OrderCommand.TimeInForce tif) {
        long id = nextOrderId();
        return intake(market, user, "s3-" + id, id, side, OrderCommand.OrdType.LIMIT, tif,
                new BigDecimal(price), new BigDecimal(qty), null);
    }

    public OrderIntake marketBuy(int market, long user, String notional) {
        long id = nextOrderId();
        return intake(market, user, "s3-" + id, id, OrderCommand.Side.BUY, OrderCommand.OrdType.MARKET,
                OrderCommand.TimeInForce.IOC, null, null, new BigDecimal(notional));
    }

    public OrderIntake marketSell(int market, long user, String qty) {
        long id = nextOrderId();
        return intake(market, user, "s3-" + id, id, OrderCommand.Side.SELL, OrderCommand.OrdType.MARKET,
                OrderCommand.TimeInForce.IOC, null, new BigDecimal(qty), null);
    }

    public OrderIntake intake(int market, long user, String coid, long orderId, OrderCommand.Side side,
                              OrderCommand.OrdType type, OrderCommand.TimeInForce tif, BigDecimal price,
                              BigDecimal qty, BigDecimal notional) {
        OrderIntake bare = new OrderIntake(market, CommandDraft.CommandType.NEW, user, coid, orderId,
                BASE_TS + orderId, orderId, side, type, tif, price, qty, notional,
                UUID.nameUUIDFromBytes(coid.getBytes()), RATE, RATE);
        return withCheck(bare, check(bare));
    }

    public OrderIntake cancel(int market, long user, long targetOrderId) {
        long tie = nextOrderId();
        return new OrderIntake(market, CommandDraft.CommandType.CANCEL, user, null, targetOrderId,
                BASE_TS + tie, tie, null, null, null, null, null, null, null, null, null);
    }

    /** 대조값을 바꾼 같은 입력. */
    public static OrderIntake withCheck(OrderIntake i, ReserveCheck check) {
        return new OrderIntake(i.marketId(), i.type(), i.userId(), i.clientOrderId(), i.assignedOrderId(),
                i.engineTs(), i.tieBreak(), i.side(), i.ordType(), i.timeInForce(), i.price(), i.quantity(),
                i.quoteOrderQty(), i.externalId(), i.makerFeeRate(), i.takerFeeRate(), check);
    }

    /** 게이트웨이 가드와 같은 산식의 대조값(OrderGateway.ParsedOrder.reserve). 이 시험의 마켓은 모두 KRW scale 0 이다. */
    public static ReserveCheck check(OrderIntake i) {
        if (i.side() == OrderCommand.Side.BUY) {
            BigDecimal amount = i.ordType() == OrderCommand.OrdType.MARKET
                    ? i.quoteOrderQty()
                    : Fees.quantize(i.price().multiply(i.quantity()), 0, RoundingMode.UP);
            return new ReserveCheck((short) KRW, amount);
        }
        return new ReserveCheck((short) baseAsset(i.marketId()), i.quantity());
    }

    public static int baseAsset(int market) {
        return switch (market) {
            case KRW_BTC -> BTC;
            case KRW_ETH -> ETH;
            case KRW_XRP -> XRP;
            default -> throw new IllegalArgumentException("market " + market);
        };
    }

    public CommandDraft draft(OrderIntake i) {
        return new CommandDraft(i.marketId(), i.type(), i.userId(), i.clientOrderId(), i.assignedOrderId(),
                i.engineTs(), i.tieBreak(), mapper.toPayloadJson(i));
    }

    public AppendResult append(CommandJournalAppender appender, OrderIntake i) {
        return appender.append(draft(i), i.reserveCheck());
    }

    /** 저널에 적고 엔진에 적용해 정산할 배치를 만든다. */
    public List<EngineEvent> journalAndApply(CommandJournalAppender appender, MatchingEngineImpl engine,
                                             OrderIntake i) {
        AppendResult ack = append(appender, i);
        assertTrue(!ack.replayed(), "재전송이 아니어야 한다: " + i.clientOrderId());
        return apply(engine, ack.seq(), i.marketId());
    }

    /** 저널 행 seq 를 엔진에 적용한다. */
    public List<EngineEvent> apply(MatchingEngineImpl engine, long seq, int market) {
        JournalEntry entry = new CommandJournalReader(owner).readAfterForMarket(seq - 1, market, seq, 1).get(0);
        assertEquals(seq, entry.seq());
        return engine.apply(mapper.toCommand(entry));
    }

    /** 접수·적용·정산을 한 번에 한다. */
    public List<EngineEvent> submit(CommandJournalAppender appender, MatchingEngineImpl engine,
                                    SettlementService settlement, OrderIntake i) {
        return settlement.appendAndSettle(journalAndApply(appender, engine, i));
    }

    public long count(String sql) {
        return owner.sql(sql).query(Long.class).single();
    }

    public BigDecimal decimal(String sql) {
        return owner.sql(sql).query(BigDecimal.class).single();
    }

    public BigDecimal available(long userId, int assetId) {
        return decimal("SELECT available FROM user_balances WHERE user_id = " + userId + " AND asset_id = " + assetId);
    }

    public BigDecimal locked(long userId, int assetId) {
        return decimal("SELECT locked FROM user_balances WHERE user_id = " + userId + " AND asset_id = " + assetId);
    }

    public long account(long userId, String purpose, int assetId) {
        return count("SELECT id FROM accounts WHERE owner_type = 'USER' AND owner_id = " + userId
                + " AND purpose = '" + purpose + "' AND asset_id = " + assetId + " AND shard = 0");
    }

    /** 소유자로 앱 enum 불변식 하나를 센다. */
    public long invariant(kr.exchange.harness.InvariantCheck check) {
        return owner.sql(check.sql()).query(Long.class).single();
    }

    @Override
    public void close() {
        for (LeaderLeaseService lease : leases) {
            lease.close();
        }
        appPool.close();
        ownerPool.close();
    }
}
