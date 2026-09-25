package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.auth.CanonicalRequests;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.dto.CancelAllResponse;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.NotLeaderException;
import kr.exchange.engine.NotReadyException;
import kr.exchange.journal.DbReadRetry;
import kr.exchange.ledger.ProjectionSignal;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.LeaseResources;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.DbOutageErrors;
import kr.exchange.journal.FencingErrors;
import kr.exchange.journal.ReserveCheck;
import kr.exchange.journal.SqlErrors;
import kr.exchange.journal.StaleLeaderException;
import kr.exchange.ledger.Fees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 주문 접수 게이트웨이. 검증·사전 가드·박제(external_id, 수수료율, engine_ts)를 끝낸 뒤
 * EngineBridge에 넘긴다(DB 설계 §4.4 비결정 입력 박제).
 *
 * 잔고 판정은 리더 모드에 따라 다르다(ReserveMode, ADR 0034 S3). P1 에서는 잔고 사전 가드가 필수다. 가드 없이 잔고 부족
 * 명령이 저널을 통과하면 정산 예약(23514)이 루프 정지 + demote로 이어진다(DB 설계 §5.1 v2.9). 가드와 정산 사이의 짧은
 * 경합 창은 인메모리 in-flight 예약 누계로 좁힌다. 누계가 노드 메모리에 있으므로 저널 append 를 리더 한 노드만 한다는
 * 전제에 기댄다. P2 에서는 저널 append 트랜잭션 안의 DB 예약이 권위다(DB 설계 §3.5). 가드는 가용 잔고만 보는 빠른 거부이고
 * in-flight 누계를 쓰지 않으며, 잔고가 모자란 append 는 23514 로 롤백돼 400 insufficient_funds 가 된다.
 *
 * submit 이 예외로 끝나면 저널에 닿았는지를 이 요청의 주문 id 로 확인한다(V47). 커밋 응답만 잃은 것이면 키를 남기고
 * 라이터에 그 명령의 회수를 요청한 뒤 정상 접수처럼 응답한다. 닿지 않았으면 넣은 키를 정리하되, 같은 내용의 다른 요청이
 * 그 키로 대조했으면(공유 표시) 남긴다. 확인마저 실패하면 키와 예약을 남긴다. 응답은 원래 예외가 마켓 접수 경합인지, 리더
 * 자격 거부나 DB 불통인지와 확인이 DB 에 닿았는지로 가른다(API 설계 §2-5-b·§2-5-c).
 */
public class OrderGateway {

    private static final Logger log = LoggerFactory.getLogger(OrderGateway.class);

    public record Placement(OrderQueryService.OrderRow row, boolean replayed, long epoch) {
    }

    private static final long PROJECTION_WAIT_MS = 5_000;
    private static final long POLL_INTERVAL_MS = 20;
    // 신호 대기 상한. 전체 대기 예산(5초)보다 짧게 두어 신호가 오지 않는 경로에 폴링 몫을 남긴다.
    private static final long SIGNAL_WAIT_MS = 3_000;
    private static final long RESERVE_DEFER_TTL_MS = 30_000;

    /**
     * 잔고 판정의 권위가 어디 있는가(ADR 0034 S3, S3 설계 4-9). 기동 때 읽은 리더 모드로 정한다(LeaderProtocolBoot).
     *
     * IN_FLIGHT(P1): 사전 가드가 가용 잔고에서 이 노드의 in-flight 누계를 빼고 판정하고, 예약은 정산에서 한다.
     * DB_RESERVATION(P2 이상): 저널 append 트랜잭션 안의 DB 예약이 권위다. 가드는 가용 잔고만 보는 빠른 거부이고 in-flight
     * 누계를 쓰지 않는다. 잔고가 모자라면 append 가 23514 로 롤백되고 400 insufficient_funds 로 끝난다.
     */
    public enum ReserveMode { IN_FLIGHT, DB_RESERVATION }

    /** 예약 자산의 가용 잔고 조회. 계정이 없으면 400 account_not_found 를 던진다. 시험이 호출을 세거나 끼어들 때 바꾼다. */
    @FunctionalInterface
    interface BalanceLookup {
        BigDecimal available(long userId, short assetId);
    }

    private final JdbcClient jdbc;
    private final EngineBridge bridge;
    private final MarketDirectory markets;
    private final OrderQueryService orders;
    private final InflightReserves inflight;
    private final ReserveMode mode;
    private final LongAdder commitsRecovered = new LongAdder();
    private final LongAdder keysKept = new LongAdder();
    private final LongAdder unresolvedFailures = new LongAdder();
    // 잔고 부족 거부의 출처별 수(ADR 0034 S3). precheck 는 저널 전 빠른 거부, db 는 append 트랜잭션의 23514 다.
    private final Map<String, LongAdder> reserveRejections = new java.util.concurrent.ConcurrentHashMap<>();
    // 접수 append 가 일시 DB 오류로 끝난 수(SQLSTATE 별). 잠금 계획 뒤에는 40P01 이 결함 신호다(S3 설계 4-14).
    private final Map<String, LongAdder> appendTransient = new java.util.concurrent.ConcurrentHashMap<>();
    // 마켓 접수 경합 응답 수(API 설계 §2-5-b). 키는 원인과 응답 코드다.
    private final Map<String, LongAdder> intakeContention = new java.util.concurrent.ConcurrentHashMap<>();
    // DB 연결 장애와 리더 자격 거부로 끝난 주문 생성·취소의 응답 수(API 설계 §2-5-c). 키는 원인과 응답 코드다.
    private final Map<String, LongAdder> intakeUnavailable = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile BalanceLookup balances = this::availableBalance;
    private volatile IntakeResolver resolver = this::resolveInDb;
    // 응답 epoch 를 읽을 리스 행을 마켓에서 정하는 계획. null 이면 matching-engine 이다.
    private volatile kr.exchange.engine.PartitionPlan partitionPlan;
    // 프로젝션 반영을 알리는 신호. 없으면 종전대로 폴링만 한다.
    private ProjectionSignal signal;
    // 마켓 라우팅 계수(API 설계 §2-5-d). 운영 배선이 부착하고, 부착하지 않은 시험 배선은 내보내지 않는 판을 쓴다.
    private volatile RoutingCounters routing = RoutingCounters.untracked();
    // 접수 전 읽기의 한 번 더 읽기(API 설계 §2-5-d 'DB가 흔들린 직후').
    private volatile DbReadRetry readRetry = DbReadRetry.untracked();
    // 파티션 밖 취소의 요청자(DB 설계 §5.4-d). 없으면 일괄 취소가 모든 주문을 직접 취소한다(시험 배선).
    private volatile OrderCancelHandoff handoff;
    // 주문 id 채번 호출 수. 선검사와 힌트 거절이 채번 전에 끝났는지 시험이 본다.
    private final LongAdder orderIdAssignments = new LongAdder();

    public OrderGateway(JdbcClient jdbc, EngineBridge bridge, MarketDirectory markets,
                        OrderQueryService orders) {
        this(jdbc, bridge, markets, orders, ReserveMode.IN_FLIGHT);
    }

    public OrderGateway(JdbcClient jdbc, EngineBridge bridge, MarketDirectory markets,
                        OrderQueryService orders, ReserveMode mode) {
        this.jdbc = jdbc;
        this.bridge = bridge;
        this.markets = markets;
        this.orders = orders;
        this.mode = mode == null ? ReserveMode.IN_FLIGHT : mode;
        this.inflight = new InflightReserves(
                orderId -> orders.byInternalId(orderId).isPresent(),
                System::currentTimeMillis, RESERVE_DEFER_TTL_MS);
    }

    /** 이 게이트웨이의 잔고 판정 모드. */
    public ReserveMode reserveMode() {
        return mode;
    }

    /** in-flight 예약 누계를 쓰는가. P2(DB_RESERVATION)로 뜬 노드는 쓰지 않는다. 게이지 exchange.order.inflight.enabled. */
    public boolean inflightEnabled() {
        return mode == ReserveMode.IN_FLIGHT;
    }

    /** 잔고 부족으로 거부한 수. source 는 precheck(저널 전 빠른 거부) 또는 db(append 의 23514)다. */
    public long reserveRejections(String source) {
        LongAdder n = reserveRejections.get(source);
        return n == null ? 0 : n.sum();
    }

    /** 접수 append 가 그 SQLSTATE 의 일시 DB 오류로 끝난 수. */
    public long appendTransient(String sqlState) {
        LongAdder n = appendTransient.get(sqlState);
        return n == null ? 0 : n.sum();
    }

    /**
     * 마켓 접수 경합 응답 수(API 설계 §2-5-b). cause 는 lock_wait·lock_wait_stuck·55P03·57014·40P01·40001, response 는
     * 429·409 다.
     */
    public long intakeContention(String cause, String response) {
        LongAdder n = intakeContention.get(cause + "|" + response);
        return n == null ? 0 : n.sum();
    }

    /**
     * DB 연결 장애와 리더 자격 거부로 끝난 주문 생성·취소의 응답 수(API 설계 §2-5-c). cause 는 connection·fencing, response 는
     * 503·429·409 다.
     */
    public long intakeUnavailable(String cause, String response) {
        LongAdder n = intakeUnavailable.get(cause + "|" + response);
        return n == null ? 0 : n.sum();
    }

    /** 가용 잔고 조회를 바꾼다. 시험 전용이다. */
    void setBalanceLookup(BalanceLookup lookup) {
        this.balances = lookup;
    }

    /** V47 판정 호출. 시험이 실패를 주입하려고 감싼다. */
    @FunctionalInterface
    interface IntakeResolver {
        IntakeResolution resolve(long userId, String clientOrderId, String requestHash, long assignedId,
                                 boolean discardKey);
    }

    /** V47 판정 호출을 감싼다. 시험 전용이다. */
    void interceptResolver(java.util.function.UnaryOperator<IntakeResolver> wrap) {
        this.resolver = wrap.apply(resolver);
    }

    /**
     * 프로젝션 신호를 붙인다. 선택 배선이라 붙이지 않으면 폴링만으로 동작한다.
     * 생성자를 늘리지 않는 이유는 기존 호출부와 테스트를 신호와 무관하게 두기 위해서다.
     */
    public void attachProjectionSignal(ProjectionSignal projectionSignal) {
        this.signal = projectionSignal;
    }

    /** 응답 epoch 를 그 마켓 파티션의 리스 행에서 읽게 한다(API 설계 §5-2). */
    public void setPartitionPlan(kr.exchange.engine.PartitionPlan plan) {
        this.partitionPlan = plan;
    }

    /**
     * 라우팅 계수와 한 번 더 읽기를 붙인다(S5 설계 4-2·4-6). 생성자를 늘리지 않는 이유는 attachProjectionSignal 과 같다. 운영
     * 배선(ApiConfig)은 기동 때 붙이고, 붙이지 않은 시험 배선은 계수를 내보내지 않는 판으로 같은 동작을 한다.
     */
    public void attachRouting(RoutingCounters counters, DbReadRetry retry) {
        this.routing = counters;
        this.readRetry = retry;
    }

    /** 파티션 밖 취소의 요청자를 붙인다(DB 설계 §5.4-d). 붙이지 않으면 일괄 취소가 모든 주문을 직접 취소한다. */
    public void attachCancelHandoff(OrderCancelHandoff cancelHandoff) {
        this.handoff = cancelHandoff;
    }

    public RoutingCounters routing() {
        return routing;
    }

    public DbReadRetry readRetry() {
        return readRetry;
    }

    /** 주문 id 채번 호출 수. 시험 이음매다(OrderGatewayTestAccess). */
    long orderIdAssignments() {
        return orderIdAssignments.sum();
    }

    /** 접수 전 읽기. 연결 끊김이면 다른 연결로 한 번 더 읽는다(API 설계 §2-5-d). */
    private <T> T read(Supplier<T> supplier) {
        return readRetry.read(DbReadRetry.ORDER, supplier);
    }

    /** in-flight 예약 락 획득 대기 누적 나노초. */
    public long inflightLockWaitNanos() {
        return inflight.lockWaitNanos();
    }

    /** in-flight 예약 락 보유 누적 나노초. */
    public long inflightLockHoldNanos() {
        return inflight.lockHoldNanos();
    }

    public Placement place(long userId, JsonNode body, byte[] rawBody) {
        return place(userId, body, rawBody, null);
    }

    /**
     * 주문 생성. hint 는 요청의 마켓 힌트 헤더(API 설계 §2-5-d)이고 없으면 null 이다. 힌트는 client_order_id 형식 검사 다음,
     * 계정 상태 조회 전에 바디의 market 과 대조한다. 다르면 400 market_hint_mismatch 이고 DB 에 가지 않는다.
     */
    public Placement place(long userId, JsonNode body, byte[] rawBody, String hint) {
        String clientOrderId = text(body, "client_order_id");
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new ApiException(400, "client_order_id_required", "client_order_id는 필수입니다.");
        }
        if (!clientOrderId.matches("[A-Za-z0-9_-]{1,36}")) {
            throw new ApiException(400, "invalid_parameter",
                    "client_order_id는 1~36자의 [A-Za-z0-9_-]여야 합니다.");
        }
        if (hint != null && !hint.equals(text(body, "market"))) {
            routing.hintRejected(RoutingCounters.HINT_MISMATCH);
            throw MarketHint.mismatch();
        }

        // 정지·해지 계정 차단(§3-8 account_suspended). 스테이트리스 액세스 토큰(15분) 무효화 지연을
        // 주문 생성 경로에서 좁힌다. 라이터 스레드 밖의 가벼운 조회다.
        requireActiveUser(userId);

        ParsedOrder parsed = validate(body);
        MarketDirectory.Market market = parsed.market();

        // 멱등 내용 비교(§2-4): 원본 요청 바디의 JCS 바이트 해시를 저장해 재전송과 대조한다.
        // 기존 키가 있어도 저널 미기록(고아 키)이면 잔고 가드가 적용된 적 없다. 저널 실재를 확인해
        // 가드 재적용 여부를 가른다(고아 키가 잔고 사전 가드를 무력화하는 경로 차단, §데이터 무결성).
        // 저널에 실재하면 원 주문이 예약을 소비했으므로 재적용하지 않는다(정당한 replay 오거부 방지).
        String requestHash = CanonicalRequests.sha256Hex(CanonicalRequests.jcs(rawBody));
        Optional<String> stored = storedRequestHash(userId, clientOrderId);
        short reserveAsset = parsed.buy() ? market.quoteAssetId() : market.baseAssetId();
        String reserveKey = userId + ":" + reserveAsset;
        BigDecimal required = parsed.reserve();
        // 원본 명령이 저널에 없어 이 요청이 키를 잡고 새 접수로 가는가. 참이면 키 선점(claimKey)과 append 실패 뒤 V47 판정을
        // 받는다. P1 에서는 사전 가드 적용 여부와 같고, P2 에서는 빠른 거부 여부와 따로 정한다(S3 설계 4-9).
        boolean guardApplies;
        // 이 요청의 내용이 기존 키의 내용과 같음을 실제로 대조했는지. 저널 멱등 replay를 200으로
        // 돌려줘도 되는지 가르는 유일한 근거다(아래 replay 판정 참조).
        boolean contentVerified = false;
        // 저널에 같은 키의 NEW 가 없음을 이미 봤는가. 공유 표시의 전제다(claimKey).
        boolean journalObservedEmpty = false;
        if (stored.isPresent()) {
            requireSameContent(stored.get(), requestHash);
            contentVerified = true;
            guardApplies = !read(() -> orders.commandJournaled(userId, clientOrderId));
            journalObservedEmpty = guardApplies;
        } else {
            guardApplies = true;
        }
        // P1 의 in-flight 예약을 잡았는가. 잡았으면 finally 가 풀거나 지연 회수한다.
        boolean guarded = false;
        if (guardApplies) {
            if (mode == ReserveMode.IN_FLIGHT) {
                // 잔고 사전 가드(§5.1 v2.9). in-flight 예약 누계를 합산해 가드-정산 경합 창을 좁힌다.
                // 가드 실패는 멱등 키를 소비하기 전이라 같은 키의 후속 시도를 오염시키지 않는다.
                if (!inflight.tryReserve(reserveKey, required,
                        () -> read(() -> balances.available(userId, reserveAsset)))) {
                    reserveRejected("precheck");
                    throw insufficientFunds();
                }
                guarded = true;
            } else if (stored.isEmpty()) {
                // P2 빠른 거부(ADR 0034 S3, S3 설계 4-9, API 설계 v2.54 §2-4). 판정 권위는 append 트랜잭션의 DB 예약이고
                // 이것은 저널 전에 명백한 잔고 부족과 계정 없음을 끝내는 조회일 뿐이다. in-flight 누계를 쓰지 않는다.
                // 처음 읽을 때 저장된 해시가 없던 요청에만 건다. 저장된 해시와 같은 내용의 재전송에 걸면, 원본이 재전송의
                // 저널 확인과 잔고 조회 사이에 커밋할 때 원본의 예약이 빠진 잔고로 400 을 낼 수 있다(검토 R1).
                if (read(() -> balances.available(userId, reserveAsset)).compareTo(required) < 0) {
                    // 거부 직전에 해시를 다시 읽는다. 그 사이 원본이 같은 내용의 키를 넣고 커밋했으면 재생 길로 보낸다.
                    // 재조회는 거부 경로에서만 한 번 더 도는 조회라 정상 접수의 왕복은 늘지 않는다.
                    Optional<String> again = storedRequestHash(userId, clientOrderId);
                    if (again.isEmpty()) {
                        reserveRejected("precheck");
                        throw insufficientFunds();
                    }
                    requireSameContent(again.get(), requestHash);
                    contentVerified = true;
                }
            }
        }
        // claimKey 가 키를 넣거나 공유 표시를 채웠다. append 가 예외로 끝나면 V47 판정으로 이 키를 정리한다.
        boolean keyed = false;
        boolean insertedKey = false;
        boolean journaled = false;
        boolean replayed = false;
        // 저널 기록 여부와 무관하게 이번에 넣은 키 행을 폐기해야 하는 경우(대조 불가 재사용 거절)
        boolean discardKey = false;
        // append 예외 뒤 V47 판정을 이미 불렀다. finally 가 키를 다시 정리하지 않는다.
        boolean keyResolved = false;
        // append 예외 뒤 커밋 여부를 끝내 확인하지 못했다. 예약을 바로 풀지 않는다.
        boolean outcomeUnknown = false;
        OrderQueryService.OrderRow row = null;
        long orderId = 0;
        try {
            if (guardApplies) {
                // 키를 넣거나 같은 내용의 기존 키에 공유 표시를 채운다(V47). 예약을 잡은 뒤라 여기서 예외가 나도
                // finally 가 예약을 푼다. 종전에는 이 단계가 try 밖이라 경합 409 나 DB 오류가 예약을 영영 남겼다.
                // 기존 키로 대조한 요청은 위에서 저널에 같은 키의 NEW 가 없음을 이미 봤다. P2 에서 빠른 거부를 건너뛴
                // 요청도 여기를 지난다. 빼면 원본이 실패해 V47 이 원본의 키를 지운 뒤 이 요청이 새 접수로 커밋할 때 키 행이
                // 없어 다음 재시도가 대조 불가 409 로 끝난다(S3 설계 4-9).
                KeyClaim claim = claimKey(userId, clientOrderId, requestHash, journalObservedEmpty);
                switch (claim) {
                    case INSERTED -> {
                        insertedKey = true;
                        keyed = true;
                    }
                    case SHARED -> {
                        contentVerified = true;
                        keyed = true;
                    }
                    case JOURNALED -> {
                        // 처음 읽을 때 키가 없어 가드를 잡았으나 경합에서 진 뒤 보니 같은 키의 NEW 가 이미 저널에 있다.
                        // 기존 키로 대조한 요청이 저널 실재를 본 경우와 같이 가드 없는 재생으로 보낸다. 재생은 새 명령을
                        // 커밋하지 않으므로 잡은 예약을 여기서 푼다. guarded 를 먼저 내려 finally 가 한 번 더 풀지 않게 한다.
                        // 두 번 풀면 같은 잔고의 다른 요청이 잡은 예약까지 빠져 가드가 빈다.
                        contentVerified = true;
                        if (guarded) {
                            guarded = false;
                            inflight.release(reserveKey, required);
                        }
                    }
                }
            }
            final long assignedId = nextOrderId();
            orderId = assignedId;
            // 제출 전에 자리를 잡는다. 제출 뒤에 잡으면 정산이 먼저 끝나 신호를 놓친다.
            // 자리 회수는 이 메서드의 finally 가 한다.
            if (signal != null) {
                signal.register(assignedId);
            }
            // 예약 자산과 금액은 P2 이상에서 머리말이 DB 계산값과 대조한다(ADR 0034 결정한 값 7). 저널 payload 에는 없다.
            OrderIntake intake = new OrderIntake(
                    market.id(), CommandDraft.CommandType.NEW, userId, clientOrderId, assignedId,
                    System.currentTimeMillis(), assignedId,
                    parsed.side(), parsed.ordType(), parsed.timeInForce(),
                    parsed.price(), parsed.quantity(), parsed.quoteOrderQty(),
                    UUID.randomUUID(), market.makerFeeRate(), market.takerFeeRate(),
                    new ReserveCheck(reserveAsset, required));
            SubmitResult result;
            try {
                result = bridge.submit(intake);
                journaled = true;
            } catch (RuntimeException e) {
                countTransient(e);
                if (!keyed) {
                    // 키를 잡지 않은 재전송이다. 같은 키의 NEW 가 이미 저널에 있어 append 는 재생만 하므로
                    // 새로 커밋됐을 명령이 없다.
                    throw submitFailure(e, IntakeOutcome.REPLAY, null, market.id());
                }
                // append 가 커밋된 뒤 응답만 잃었을 수 있다. 이 요청의 주문 id 로 저널을 보고, 커밋되지 않았으면
                // 넣은 키를 정리한다(V47). 판정은 같은 키의 append 가 끝나기를 기다린 뒤에 한다.
                keyResolved = true;
                Verdict verdict = resolveFailedSubmit(userId, clientOrderId, requestHash, assignedId, insertedKey);
                IntakeResolution resolution = verdict.resolution();
                if (resolution == null) {
                    outcomeUnknown = true;
                    throw submitFailure(e, IntakeOutcome.UNRESOLVED, verdict, market.id());
                }
                if (!resolution.journaled()) {
                    throw submitFailure(e, IntakeOutcome.ofResolution(resolution.outcome()), verdict, market.id());
                }
                // 커밋은 됐고 명령은 라이터 큐에 들어가지 않았다. 그대로 두면 같은 마켓의 다음 명령이 갭을 드러낼
                // 때까지 정산되지 않아, 한가한 마켓에서는 in-flight 예약 TTL 이 먼저 끝나 가드가 빈다. 라이터에 회수를
                // 요청하고 정상 접수와 같은 경로로 응답한다. 키는 남아 있으므로 같은 키 재시도는 멱등 재생이 된다.
                // journaled 를 먼저 세운다. 회수 요청이 예외로 끝나도 finally 가 예약을 바로 풀지 않게 하기 위해서다.
                result = new SubmitResult(resolution.seq(), resolution.fencing(), false, false);
                journaled = true;
                commitsRecovered.increment();
                log.warn("append 예외 뒤 이 요청의 NEW 가 저널에 커밋돼 있었다. 라이터에 회수를 요청하고 접수로 처리한다: "
                                + "user_id={}, client_order_id={}, seq={}, 원인={}",
                        userId, clientOrderId, resolution.seq(), e.toString());
                bridge.requestCatchUp(resolution.marketId(), resolution.seq());
            }
            if (guarded && result.reserved()) {
                // P1 로 뜬 노드가 P2 DB 에서 돈다(전환 뒤 재기동하지 않음). 자금은 DB 가 저널과 함께 예약했으므로 in-flight 몫을
                // 바로 푼다. 겹쳐 잡은 것은 append 동안뿐이고 거부 쪽으로만 틀렸다(S3 설계 4-9).
                guarded = false;
                inflight.release(reserveKey, required);
            }
            replayed = result.replayed();

            // 저널이 같은 client_order_id를 이미 갖고 있는데(replay) 내용 대조를 한 번도 못 했다면
            // 200으로 원 주문을 돌려줄 근거가 없다. idempotency_keys가 비어 있었다는 뜻이다. 규약이 드는
            // 원인은 7일 보존 기간 삭제다(API 설계 §2-4. 삭제 주체는 아직 없다, DB 설계 §3.2-a-5).
            // 커밋 응답 유실 뒤에도 키를 남기므로(V47) 퍼지가 배선되기 전에는 도달하지 않는 방어선이다.
            // 이 상태에서 내용이 다른 재사용을 replay로 흡수하면 요청과 무관한 주문을 200으로
            // 돌려주게 되어 멱등 계약이 깨진다. 대조 불가일 때는 재사용으로 보고 거절한다.
            //
            // 예외가 하나 있다. 이번 요청이 넣은 키에 공유 표시가 있으면, 그 키로 내용을 대조한 요청이 이 NEW 를 커밋한
            // 것이다. 공유 표시는 저널에 같은 키의 NEW 가 없음을 실제로 관측한 요청만 채운다(claimKey). 경합에서 져 처음
            // 읽을 때 키를 보지 못한 요청도 표시 전에 저널을 다시 본다. 그래서 그 NEW 는 관측 뒤에 커밋된 것이라 보존 기한
            // 삭제 전의 옛 주문이 아니고, 내용은 이 키의 해시와 같다. 같은 내용의 요청 셋이 섞여 앞 요청이 지운 키를 이번
            // 요청이 다시 넣은 경우다. 공유 표시를 읽지 못하면 대조하지 못한 것으로 보고 거절한다(keyShared).
            if (replayed && !contentVerified) {
                if (keyShared(userId, clientOrderId, requestHash)) {
                    contentVerified = true;
                } else {
                    // 이번 요청 해시로 넣은 키 행은 원 주문 내용과 무관하다. 남겨두면 다음 재시도가
                    // 그 해시로 대조에 성공해 같은 결함이 그대로 재현된다. 정리 대상으로 표시한다.
                    discardKey = true;
                    throw new ApiException(409, "duplicate_client_order_id",
                            "이미 사용한 client_order_id. 새 키 필요");
                }
            }

            row = result.replayed()
                    ? awaitRow(() -> orders.byClientOrderId(userId, clientOrderId), assignedId)
                    : awaitRow(() -> orders.byInternalId(assignedId), assignedId);
            if (row == null) {
                throw new ApiException(409, "order_in_progress",
                        "주문 처리 중. 재조회 필요", null, 1);
            }
            if (!result.replayed() && "REJECTED".equals(row.status())) {
                throw new ApiException(422, "domain_rejected", "도메인 검증에서 거부됨",
                        Map.of("reject_reason", row.rejectReason() == null ? "rejected"
                                : row.rejectReason()), null);
            }
            return new Placement(row, result.replayed(), result.epoch());
        } finally {
            // 신호 자리 회수. 성공·실패·예외 어느 경로로 나가도 반드시 지운다.
            if (signal != null && orderId > 0) {
                signal.forget(orderId);
            }
            if (insertedKey && discardKey) {
                // 저널에는 있으나 내용 대조가 불가해 재사용으로 거절한 경우다. 이번 요청 해시가 남으면 다음
                // 재시도가 그 해시로 대조에 성공해 거절이 무력화된다.
                deleteOrphanKey(userId, clientOrderId, requestHash);
            } else if (insertedKey && !journaled && !keyResolved) {
                // submit 에 닿기 전에 끝났다(채번 실패 등). 저널에 닿지 않았으므로 넣은 키를 V47 판정으로 정리해
                // 다음 재시도가 잔고 가드를 다시 받게 한다. 정리 실패해도 재시도 경로의 command_journal 실재
                // 확인이 backstop이라 가드 무력화로 이어지지 않는다.
                resolveFailedSubmit(userId, clientOrderId, requestHash, orderId, true);
            }
            if (guarded) {
                if (!outcomeUnknown && releaseImmediately(row != null, journaled, replayed)) {
                    inflight.release(reserveKey, required);
                } else {
                    // 저널 이후 정산 미관측(프로젝션 타임아웃 등). 즉시 해제는 가드-정산 경합 창을
                    // 다시 열므로 주문 row 관측 또는 TTL로 지연 회수한다(M1-4 재판정 #4).
                    // append 예외 뒤 커밋 여부를 확인하지 못한 경우도 같다. 커밋됐다면 그 명령이 정산될 때까지
                    // 예약이 필요하다. 커밋되지 않았다면 TTL 동안 거부 쪽으로만 틀린다.
                    inflight.defer(reserveKey, required, orderId);
                }
            }
        }
    }

    /**
     * finally 예약 회수 결정. 정산 관측 완료(available 반영)·submit 예외(저널 미기록으로 본다)·멱등 replay 중 하나면
     * 즉시 해제한다. replay 접수는 자기 명령이 엔진·정산에 도달하지 않아(권위 예약은 원본이 보유)
     * assignedId로 어떤 orders row도 생기지 않으므로, defer하면 sweep의 주문 row 관측 회수 경로가
     * 죽어 유령 예약이 TTL(30s)로만 회수되고 후속 주문을 insufficient_funds로 오거부한다.
     */
    static boolean releaseImmediately(boolean rowObserved, boolean journaled, boolean replayed) {
        return rowObserved || !journaled || replayed;
    }

    private void requireActiveUser(long userId) {
        String status = read(() -> jdbc.sql("SELECT status FROM users WHERE id = ?")
                .params(userId)
                .query(String.class)
                .optional())
                .orElse("CLOSED");
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(403, "account_suspended", "주문할 수 없는 계정 상태입니다.");
        }
    }

    /**
     * 대조 불가로 거절한 요청이 넣은 키를 지운다. 공유 표시가 있는 키는 남긴다. 공유 표시를 읽지 못해 거절한 경우
     * (keyShared) 실제로는 같은 내용의 요청이 대조해 커밋한 주문의 키일 수 있기 때문이다. 표시가 없다고 읽은 정상 거절
     * 경로에서는 조건이 삭제를 막지 않는다. 저널에 같은 키의 NEW 가 있음을 본 요청은 표시하지 않기 때문이다(claimKey).
     */
    private void deleteOrphanKey(long userId, String clientOrderId, String requestHash) {
        try {
            jdbc.sql("""
                            DELETE FROM idempotency_keys
                            WHERE user_id = ? AND idem_key = ? AND request_hash = ? AND shared_at IS NULL
                            """)
                    .params(userId, clientOrderId, requestHash)
                    .update();
        } catch (RuntimeException ignored) {
            // 정리 실패는 재시도 경로의 commandJournaled 확인이 흡수한다.
        }
    }

    /** claimKey 결과. */
    private enum KeyClaim {
        /** 키를 새로 넣었다. 내용 대조는 아직 하지 않았다. */
        INSERTED,
        /** 같은 내용의 기존 키에 공유 표시를 채웠다. */
        SHARED,
        /** 같은 내용의 기존 키가 있고 저널에 같은 키의 NEW 가 이미 있다. 표시하지 않았다. 가드 없이 재생한다. */
        JOURNALED
    }

    /**
     * 이번 요청의 멱등 키를 잡는다. 기존 키의 내용이 다르면 409 duplicate_client_order_id 다.
     *
     * 공유 표시가 필요한 이유(V47). 기존 키로 내용을 대조한 요청은 그 키를 넣은 요청이 저널 전에 실패해 키를 지우면
     * 자기 주문의 키를 잃고, 그 주문의 정당한 재시도가 대조 불가 409 나 잔고 부족 400 을 받는다. 표시가 채워진 키는
     * 넣은 요청이 실패해도 지우지 않는다.
     *
     * 표시는 저널에 같은 키의 NEW 가 없음을 실제로 관측한 요청만 채운다. 대조 불가 재생 판정(keyShared)이 이 전제에
     * 기댄다. 표시가 있으면 재생된 NEW 는 그 관측 뒤에 커밋된 것이라 보존 기한 삭제 전의 옛 주문이 아니다.
     * journalObservedEmpty 는 호출자가 기존 키로 대조한 뒤 저널에 NEW 가 없음을 이미 봤는지다. 처음 읽을 때 키가 없어
     * 저널을 보지 않은 요청이 INSERT 경합에서 지면 표시 전에 저널을 본다. 이미 있으면 표시하지 않고 JOURNALED 를
     * 돌려준다. 이 확인이 없으면 보존 기한 삭제로 키를 잃은 옛 주문 O 가 있을 때, 내용이 다른 요청 X 가 키를 넣고 X 와
     * 같은 내용의 요청 B 가 경합에서 져 표시를 채운다. 그러면 X 의 append 가 O 를 재생해도 표시 때문에 200 O 를 받고
     * 대조하지 못한 키가 남는다.
     *
     * 대조와 표시 사이에 넣은 요청이 키를 지울 수 있다. 그러면 다시 넣는다. 두 번 모두 놓치면 같은 키의 요청들이
     * 동시에 넣고 지우는 중이라 재조회로 돌려보낸다. 키를 새로 넣는 정상 경로는 INSERT 한 번 그대로다.
     */
    private KeyClaim claimKey(long userId, String clientOrderId, String requestHash, boolean journalObservedEmpty) {
        boolean observedEmpty = journalObservedEmpty;
        for (int attempt = 0; attempt < 2; attempt++) {
            int inserted = jdbc.sql("""
                            INSERT INTO idempotency_keys (user_id, idem_key, request_hash)
                            VALUES (?, ?, ?) ON CONFLICT (user_id, idem_key) DO NOTHING
                            """)
                    .params(userId, clientOrderId, requestHash)
                    .update();
            if (inserted > 0) {
                return KeyClaim.INSERTED;
            }
            // 동시 도착 경합에서 졌거나 기존 키로 대조한 요청이다. 내용 비교 후 저널 멱등 판정으로 수렴한다(§2-4).
            Optional<String> existing = storedRequestHash(userId, clientOrderId);
            if (existing.isEmpty()) {
                continue;
            }
            requireSameContent(existing.get(), requestHash);
            if (!observedEmpty) {
                if (read(() -> orders.commandJournaled(userId, clientOrderId))) {
                    return KeyClaim.JOURNALED;
                }
                observedEmpty = true;
            }
            int marked = jdbc.sql("""
                            UPDATE idempotency_keys SET shared_at = COALESCE(shared_at, now())
                            WHERE user_id = ? AND idem_key = ? AND request_hash = ?
                            """)
                    .params(userId, clientOrderId, requestHash)
                    .update();
            if (marked > 0) {
                return KeyClaim.SHARED;
            }
        }
        throw new ApiException(409, "order_in_progress", "주문 처리 중. 재조회 필요", null, 1);
    }

    /**
     * 이번 요청이 넣은 키에 다른 요청이 공유 표시를 채웠는가(claimKey). 키가 없으면 거짓이다.
     *
     * 조회가 실패해도 거짓이다. 참이어야 대조를 마친 것으로 보고 재생을 200 으로 돌려준다. 확인하지 못한 채 참으로 보면
     * 내용이 다른 요청에 옛 주문을 돌려줄 수 있고, 예외를 그대로 올리면 거절 경로의 키 정리가 빠져 대조하지 못한 해시가
     * 남는다. 그러면 다음 재시도가 그 해시로 대조에 성공해 옛 주문을 받는다.
     */
    private boolean keyShared(long userId, String clientOrderId, String requestHash) {
        try {
            return jdbc.sql("""
                            SELECT shared_at IS NOT NULL FROM idempotency_keys
                            WHERE user_id = ? AND idem_key = ? AND request_hash = ?
                            """)
                    .params(userId, clientOrderId, requestHash)
                    .query(Boolean.class)
                    .optional()
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("공유 표시 조회가 실패했다. 대조하지 못한 재생으로 보고 거절한다: user_id={}, client_order_id={}, 원인={}",
                    userId, clientOrderId, e.toString());
            return false;
        }
    }

    /** 이 게이트웨이가 사용자·자산에 잡아 둔 in-flight 예약 누계. 시험이 예약 해제를 확인하는 데 쓴다. */
    BigDecimal inflightPending(long userId, short assetId) {
        return inflight.pending(userId + ":" + assetId);
    }

    /** V47 판정 결과. journaled 면 이 요청의 주문 id 로 커밋된 NEW 가 있고 seq·marketId·fencing 이 그 행의 값이다. */
    record IntakeResolution(String outcome, long seq, int marketId, long fencing) {
        boolean journaled() {
            return "JOURNALED".equals(outcome);
        }
    }

    /**
     * V47 판정의 끝(API 설계 §2-5-c). resolution 이 있으면 판정됐고, 없으면 failure 가 마지막 실패다. 그 실패가 DB 불통이면
     * 판정이 DB 에 닿지 못한 것이고, 아니면(55P03·57014 등) DB 에 닿았으나 판정하지 못한 것이다.
     */
    private record Verdict(IntakeResolution resolution, RuntimeException failure, int calls) {
        boolean dbUnreachable() {
            return resolution == null && DbOutageErrors.isOutage(failure);
        }
    }

    private IntakeResolution resolveInDb(long userId, String clientOrderId, String requestHash, long assignedId,
                                         boolean discardKey) {
        return jdbc.sql("SELECT * FROM resolve_failed_order_intake(?, ?, ?, ?, ?)")
                .params(userId, clientOrderId, requestHash, assignedId, discardKey)
                .query((rs, rowNum) -> new IntakeResolution(rs.getString("out_outcome"),
                        rs.getLong("out_seq"), rs.getInt("out_market_id"), rs.getLong("out_fencing")))
                .single();
    }

    /**
     * append 예외 뒤 저널을 보고 키를 정리한다(V47 resolve_failed_order_intake).
     *
     * 판정이 실패하면 커밋 여부를 모른다. 키를 지우지 않고 예약도 바로 풀지 않는다. 커밋됐다면 그 주문의 재시도가 키로 대조해
     * 멱등 재생으로 수렴하고, 커밋되지 않았다면 재시도가 대조 뒤 command_journal 실재로 잔고 가드를 다시 받는다.
     *
     * 연결이 끊긴 판정은 한 번 더 부르되 키를 지우지 않는다(API 설계 §2-4). 저널에 없다는 답은 NOT_JOURNALED 라 409 가 된다.
     */
    private Verdict resolveFailedSubmit(long userId, String clientOrderId, String requestHash,
                                        long assignedId, boolean discardKey) {
        RuntimeException failure;
        int calls = 0;
        do {
            // 첫 판정이 이 키를 지우고 응답만 잃었다면, V47 은 그 사이 같은 해시로 새로 넣은 다른 요청의 키를 이 키와 가르지 못한다.
            boolean discard = discardKey && calls == 0;
            calls++;
            try {
                IntakeResolution resolution = resolver.resolve(userId, clientOrderId, requestHash, assignedId, discard);
                if ("KEPT".equals(resolution.outcome())) {
                    keysKept.increment();
                }
                return new Verdict(resolution, null, calls);
            } catch (RuntimeException e) {
                failure = e;
            }
            // 끊긴 연결은 풀이 버리므로 다시 부르면 다른 연결로 DB 도달 여부를 다시 본다. 획득 실패는 연결 대기만 한 번 더 쓰므로
            // 다시 부르지 않는다.
        } while (calls < 2 && DbOutageErrors.kind(failure) == DbOutageErrors.Kind.CONNECTION_LOST);
        unresolvedFailures.increment();
        log.warn("append 예외 뒤 저널 판정이 실패했다. 멱등 키를 남기고 in-flight 예약은 지연 회수한다: "
                        + "user_id={}, client_order_id={}, 판정 호출={}, 원인={}, 메시지={}",
                userId, clientOrderId, calls, SqlErrors.describe(failure), failure.getMessage());
        return new Verdict(null, failure, calls);
    }

    /**
     * 저널에 닿지 못한 submit 예외를 응답으로 바꾼다. 루프 정지(리더십 상실 창)는 재조회 계약의 503 이다.
     * 검증이 ACTIVE 를 본 뒤 마켓 정지가 들어온 창은 검증의 정지 거부와 같은 422 를 낸다.
     *
     * 마켓 접수 경합(접수 락 대기 상한 초과와 append 트랜잭션의 55P03·57014·40P01·40001)은 503 으로 내지 않는다(API 설계
     * §2-5-b). 쓰기 업스트림의 max_fails=1 이 리더의 503 하나로 리더를 빼 모든 마켓의 쓰기가 502 가 되기 때문이다. 이 요청이
     * 접수되지 않았음이 확정(outcome DISCARDED)이면 429(bucket market)이고, 같은 키의 다른 요청이 관여했거나 판정을 못 했거나
     * 이미 접수된 주문의 재전송이면 409 order_in_progress 다. API 예외로 바꿔야 배치 주문이 이 원소만 in_progress 로 적고
     * 다음 원소로 넘어간다(§4-B-6).
     *
     * P2 의 잔고 부족(append 트랜잭션의 23514 chk_balance_nonneg)은 400 insufficient_funds 다(API 설계 v2.54 §2-3). 트랜잭션
     * 전체가 롤백돼 저널·채번·예약이 남지 않았고, 이 요청의 키는 V47 판정이 이미 정리했다. 다른 제약의 23514 는 잔고 부족이
     * 아니라 결함이라 원래 예외(500)로 둔다.
     *
     * 경합 다음은 리더 자격 거부(fencing 거부, 옛 epoch 삽입 0행, 기간 종료)와 DB 불통이다(API 설계 §2-5-c). 둘 다 경합이
     * 아니므로 좀비 판정 수요에서 빼지 않는다. DB 에 닿지 못하는 리더는 두 층 판정으로 강등돼야 한다.
     */
    private RuntimeException submitFailure(RuntimeException e, IntakeOutcome outcome, Verdict verdict, int marketId) {
        String cause = contentionCause(e);
        if (cause != null) {
            boolean confirmed = outcome.confirmedNotAccepted();
            countContention(cause, confirmed ? "429" : "409");
            // 멈춘 보유자 때문에 난 대기 초과는 좀비 판정의 수요로 남긴다(DB 설계 §5.4-c 8).
            boolean excluded = !"lock_wait_stuck".equals(cause);
            return confirmed ? marketContention(excluded)
                    : new ApiException(409, "order_in_progress", "주문 처리 중. 재조회 필요", null, 1, excluded);
        }
        if (leaderRejection(e)) {
            if (!bridge.accepting(marketId)) {
                // 재확인이 거짓이라 강등이 게이트를 먼저 닫았다. 이 노드는 그 마켓의 리더가 아니므로 페일오버 503 이고 쓰기
                // 업스트림이 같은 요청을 새 리더로 넘긴다. V47 은 키 정리를 위해 이미 불렀다.
                ApiException failover = failoverUnavailable();
                recordUnavailable("fencing", failover, "주문 생성", verdict, e);
                return failover;
            }
            return undecidedIntake("fencing", outcome, verdict, e);
        }
        if (DbOutageErrors.isOutage(e)) {
            return undecidedIntake("connection", outcome, verdict, e);
        }
        if (e instanceof IllegalStateException) {
            return new ApiException(503, "service_unavailable",
                    "페일오버 진행 중. 재조회 후 판단 필요", null, 1);
        }
        if (e instanceof EngineBridge.MarketHaltedException) {
            return marketSuspended();
        }
        if (SqlErrors.isInsufficientFunds(e)) {
            reserveRejected("db");
            return insufficientFunds();
        }
        return e;
    }

    /**
     * DB 불통이나 게이트를 연 노드의 리더 자격 거부로 끝난 주문 생성의 응답(API 설계 §2-5-c). 쓰기 업스트림에서 이 노드를 빼는
     * 503 은 V47 판정마저 DB 에 닿지 못했을 때뿐이다. 판정이 DB 에 닿았으면 마켓 접수 경합과 같은 규칙이다. V47 이 이 요청의
     * 키를 지웠으면(DISCARDED) 같은 키의 append 와 같은 어드바이저리 락 아래에서 확인한 확정 미접수라 429 이고, 그 밖과
     * 판정 실패와 재전송은 409 다.
     */
    private ApiException undecidedIntake(String cause, IntakeOutcome outcome, Verdict verdict, RuntimeException e) {
        ApiException response;
        String counted = cause;
        if (verdict != null && verdict.dbUnreachable()) {
            counted = "connection";
            response = dbUnreachable();
        } else if (outcome.confirmedNotAccepted()) {
            response = marketContention(false);
        } else {
            response = new ApiException(409, "order_in_progress", "주문 처리 중. 재조회 필요", null, 1);
        }
        recordUnavailable(counted, response, "주문 생성", verdict, e);
        return response;
    }

    /** fencing 거부, 옛 epoch 의 삽입 0행, append 전 기간 검사의 기간 종료. 그 append 는 롤백됐거나 DB 에 가지 않았다. */
    private static boolean leaderRejection(RuntimeException e) {
        return e instanceof NotLeaderException || e instanceof StaleLeaderException
                || (e instanceof DataAccessException dae && FencingErrors.isFencingRejection(dae));
    }

    /** 판정이나 재조회마저 DB 에 닿지 못했다. 배치 주문과 일괄 취소가 남은 원소를 시도하지 않게 표식을 단다. */
    private static ApiException dbUnreachable() {
        return new ApiException(503, "service_unavailable", "DB 연결 장애. 재조회 후 판단 필요", null, 1, false,
                ApiException.DbOutage.UNREACHABLE);
    }

    private static ApiException failoverUnavailable() {
        return new ApiException(503, "service_unavailable", "페일오버 진행 중. 재조회 후 판단 필요", null, 1);
    }

    private void recordUnavailable(String cause, ApiException response, String path, Verdict verdict,
                                   RuntimeException e) {
        recordUnavailable(cause, response, path, verdict == null ? 0 : verdict.calls(), e);
    }

    /** 응답마다 계수와 WARN 한 줄(경로, 응답, 확인 호출 수, 원인 사슬)을 남긴다. 확인은 생성이면 V47, 취소면 재조회다. */
    private void recordUnavailable(String cause, ApiException response, String path, int checks, RuntimeException e) {
        intakeUnavailable.computeIfAbsent(cause + "|" + response.status(), k -> new LongAdder()).increment();
        log.warn("DB 연결 장애·리더 자격 거부 응답: 경로={}, 원인={}, 응답={} {}, 확인 호출={}, 예외={}", path, cause,
                response.status(), response.name(), checks, SqlErrors.describe(e));
    }

    /** 잔고 부족의 저널 전 거부. P1 은 사전 가드, P2 는 빠른 거부와 append 트랜잭션의 23514 가 낸다. */
    private static ApiException insufficientFunds() {
        return new ApiException(400, "insufficient_funds", "주문 가능 잔고가 부족합니다.");
    }

    private void reserveRejected(String source) {
        reserveRejections.computeIfAbsent(source, k -> new LongAdder()).increment();
    }

    /**
     * append 가 일시 DB 오류 넷(55P03·57014·40P01·40001)으로 끝난 수를 센다. 정산 잠금 계획 뒤 접수 경로의 교착은 결함
     * 신호라 오류 로그를 남기고 경보(OrderIntakeDeadlock)가 이 계수를 본다(S3 설계 4-14).
     */
    private void countTransient(RuntimeException e) {
        String state = SqlErrors.sqlState(e);
        if (state != null && kr.exchange.journal.TransientDbErrors.isRetryable(e)) {
            appendTransient.computeIfAbsent(state, k -> new LongAdder()).increment();
            if ("40P01".equals(state)) {
                log.error("접수 append 가 교착 희생으로 끝났다. 잠금 계획 뒤에는 도달하지 않아야 한다: {}", e.toString());
            }
        }
    }

    /** 마켓 접수 경합이면 원인 이름, 아니면 null 이다(API 설계 §2-5-b). */
    private static String contentionCause(RuntimeException e) {
        if (e instanceof EngineBridge.AcceptBusyException busy) {
            return busy.stuckHolder() ? "lock_wait_stuck" : "lock_wait";
        }
        if (e instanceof org.springframework.dao.DataAccessException
                && kr.exchange.journal.TransientDbErrors.isRetryable(e)) {
            return kr.exchange.journal.TransientDbErrors.sqlState(e);
        }
        return null;
    }

    private void countContention(String cause, String response) {
        intakeContention.computeIfAbsent(cause + "|" + response, k -> new LongAdder()).increment();
    }

    /** append 예외 뒤 이 요청의 NEW 가 커밋돼 있어 접수로 처리한 횟수. 늘면 커밋 응답 유실을 의심한다. */
    public long commitsRecovered() {
        return commitsRecovered.sum();
    }

    /** 저널 전 실패로 끝난 요청의 키를 공유 표시 때문에 남긴 횟수. 같은 키의 동시 요청이 있었다는 뜻이다. */
    public long keysKept() {
        return keysKept.sum();
    }

    /** append 예외 뒤 저널 판정마저 실패해 커밋 여부를 모른 채 끝난 횟수. */
    public long unresolvedFailures() {
        return unresolvedFailures.sum();
    }

    private Optional<String> storedRequestHash(long userId, String clientOrderId) {
        return read(() -> jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(userId, clientOrderId)
                .query(String.class)
                .optional());
    }

    private static void requireSameContent(String stored, String incoming) {
        if (!stored.equals(incoming)) {
            throw new ApiException(409, "duplicate_client_order_id",
                    "동일 client_order_id로 다른 내용의 주문이 존재합니다.");
        }
    }

    public Placement cancel(long userId, UUID externalId) {
        return cancel(userId, externalId, null);
    }

    /**
     * 단건 취소(§4-B-2). hint 는 요청의 마켓 힌트 헤더(API 설계 §2-5-d)이고 없으면 null 이다.
     *
     * 순서가 계약이다. 대상 주문을 회원으로 걸러 읽고(없으면 404), 힌트가 있으면 주문의 마켓과 대조하고(다르면 400), 종결 주문이면
     * 200 + 현재 상태를 돌려준다. 그 마켓의 접수 게이트가 이 노드에서 닫혀 있으면 채번 전에 소유 아님 503 이다. 쓰지 않으므로
     * 종결 주문은 소유하지 않는 노드도 200 으로 답한다.
     */
    public Placement cancel(long userId, UUID externalId, String hint) {
        return cancelOne(userId, externalId, hint, null);
    }

    /**
     * 취소 한 건. tally 가 있으면 일괄 취소의 로컬 몫이라 submit 까지 간 수를 세고 소유 아님 계수는 세지 않는다. 그 계수는 힌트
     * 없는 단건 취소의 게이트웨이 503 만 센다(하네스 CI/CD §5-2).
     */
    private Placement cancelOne(long userId, UUID externalId, String hint, BulkCancelTally tally) {
        OrderQueryService.OrderRow row = read(() -> orders.byExternalId(externalId))
                .filter(r -> r.userId() == userId)
                .orElseThrow(() -> new ApiException(404, "order_not_found", "주문 없음"));
        if (hint != null && !hint.equals(row.marketSymbol())) {
            // 채번 전이라 멱등 키·예약·채번이 남지 않는다. 종결 주문도 힌트가 틀리면 400 이다(API 설계 §4-B-2).
            routing.hintRejected(RoutingCounters.HINT_MISMATCH);
            throw MarketHint.mismatch();
        }
        if (row.terminal()) {
            // 재취소 멱등(§2-4): 이미 종결이면 현재 상태를 그대로 반환한다.
            return new Placement(row, true, currentEpoch(row.marketId()));
        }
        if (!bridge.accepting(row.marketId())) {
            // 이 노드가 그 마켓을 접수하지 않는다. 채번 전에 돌려보내 인그레스가 소유 노드로 다시 보낸다(API 설계 §2-5-d).
            // 예외 처리기가 좀비 판정 수요 되돌림 표식과 X-Exchange-Route 헤더를 단다.
            if (tally == null) {
                routing.cancelNotOwner();
            }
            throw ApiException.notOwner();
        }
        MarketDirectory.Market market = markets.byId(row.marketId());
        OrderIntake intake = new OrderIntake(
                market.id(), CommandDraft.CommandType.CANCEL, userId, null, row.id(),
                System.currentTimeMillis(), nextOrderId(),
                null, null, null, null, null, null, null, null, null);
        if (tally != null) {
            tally.submitted();
        }
        SubmitResult result = submit(intake);

        OrderQueryService.OrderRow settled = awaitRow(() ->
                orders.byInternalId(row.id()).filter(OrderQueryService.OrderRow::terminal));
        if (settled == null) {
            throw new ApiException(409, "order_in_progress",
                    "취소 처리 중. 재조회 필요", null, 1);
        }
        return new Placement(settled, false, result.epoch());
    }

    public Placement cancelByClient(long userId, String clientOrderId) {
        return cancelByClient(userId, clientOrderId, null);
    }

    /**
     * by-client 취소(§4-B-2). 프로젝션 미반영은 저널 권위로 404·409를 가른다(§4-B-4-a). 행이 있으면 단건 취소와 같고, 저널에만
     * 있는 주문은 마켓을 모르므로 힌트를 대조하지 않는다.
     */
    public Placement cancelByClient(long userId, String clientOrderId, String hint) {
        Optional<OrderQueryService.OrderRow> row = read(() -> orders.byClientOrderId(userId, clientOrderId));
        if (row.isPresent()) {
            return cancel(userId, row.get().externalId(), hint);
        }
        if (!read(() -> orders.commandJournaled(userId, clientOrderId))) {
            throw new ApiException(404, "order_not_found", "주문 없음");
        }
        throw new ApiException(409, "order_in_progress",
                "주문 처리 중. 재조회 필요", null, 1);
    }

    /**
     * 일괄 취소 결과(API 설계 §4-B-3·§6-E). 아래 수는 모두 failed 가운데 그 주문이 아직 열려 있을 수 있는 것이다.
     *
     * unsubmitted 는 취소 명령이 저널에 닿지 못하고 503 으로 끝났거나(라이터 정지, 게이트가 닫힌 리더 자격 거부) DB 연결 장애로
     * 저널 반영을 확인하지 못한 수다(API 설계 §2-5-c). contended 는 마켓 접수 경합의 429 로 끝난 수다(API 설계 §2-5-b).
     * suspended 는 정지 마켓의 422 market_suspended 로 남은 수다. 마켓 재개가 먼저라 곧바로 다시 불러도 닫히지 않는다.
     * partitionNotStarted 는 소유 노드가 기다림 안에 시작하지 않아 포기한 인계 몫, partitionPending 은 시작했으나 끝내지 않았거나
     * 포기하지 않고 남긴 몫, errored 는 소유 노드의 예기치 않은 오류로 끝난 주문 수다(DB 설계 §5.4-d 2). 회원 폐쇄는 여섯의 합
     * (incomplete)을 미완료로 센다. 체결로 먼저 종결된 주문과 취소 저널 뒤 반영 대기 409 는 다시 부를 필요가 없어 어느 쪽에도
     * 세지 않는다.
     *
     * localSubmits 는 이 노드가 submit 까지 간 로컬 취소 수이고, handoffInsertFailed 는 인계 행 넣기가 DB 오류로 끝났는지다. 둘은
     * 좀비 판정 수요 되돌림에 쓴다(DB 설계 §5.4-c 8 v2.57).
     */
    public record BulkCancelResult(List<String> canceledIds,
                                   List<CancelAllResponse.Failure> failed,
                                   int unsubmitted,
                                   int suspended,
                                   int contended,
                                   int partitionNotStarted,
                                   int partitionPending,
                                   int errored,
                                   int localSubmits,
                                   boolean handoffInsertFailed) {

        public BulkCancelResult(List<String> canceledIds, List<CancelAllResponse.Failure> failed, int unsubmitted) {
            this(canceledIds, failed, unsubmitted, 0, 0);
        }

        public BulkCancelResult(List<String> canceledIds, List<CancelAllResponse.Failure> failed, int unsubmitted,
                                int suspended) {
            this(canceledIds, failed, unsubmitted, suspended, 0);
        }

        public BulkCancelResult(List<String> canceledIds, List<CancelAllResponse.Failure> failed, int unsubmitted,
                                int suspended, int contended) {
            this(canceledIds, failed, unsubmitted, suspended, contended, 0, 0, 0, 0, false);
        }

        /**
         * 회원 폐쇄가 미완료로 세는 수(API 설계 v2.57 §6-E). 저널에 닿지 못한 503·429, 정지 마켓의 422 market_suspended, 인계의
         * 세 사유(partition_not_started, partition_pending, server_error)의 합이다. 정지 마켓 주문은 곧바로 다시 불러도 닫히지
         * 않지만 남은 주문이 폐쇄 계정에서 체결될 수 있으므로 완료로 덮지 않는다.
         */
        public int incomplete() {
            return unsubmitted + suspended + contended + partitionNotStarted + partitionPending + errored;
        }
    }

    /** 일괄 취소(§4-B-3). */
    public BulkCancelResult cancelAll(long userId, String market) {
        return cancelAll(userId, market, OrderCancelHandoff.ORIGIN_CANCEL_ALL);
    }

    /**
     * 일괄 취소와 회원 폐쇄(API 설계 §4-B-3·§6-E, DB 설계 §5.4-d). 요청 시점의 미체결을 파티션별로 나눈다. 이 노드가 접수 게이트를
     * 연 파티션의 주문은 하나씩 직접 취소하고, 나머지 파티션의 주문은 그 소유 노드에 넘긴다(인계 행). 넣기를 직접 취소보다
     * 먼저 해 두 파티션의 취소가 함께 진행되게 한다. 인계 요청자가 붙어 있지 않으면(시험 배선) 모두 직접 취소한다. origin 은
     * 인계 행에 적는 출처(cancel_all, member_close)다.
     */
    public BulkCancelResult cancelAll(long userId, String market, String origin) {
        List<OrderQueryService.OpenOrder> open = read(() -> orders.openOrders(userId, market));
        OrderCancelHandoff cancelHandoff = handoff;
        List<UUID> local = new ArrayList<>();
        Map<String, List<UUID>> remote = new LinkedHashMap<>();
        Map<String, Boolean> opened = new HashMap<>();
        for (OrderQueryService.OpenOrder order : open) {
            String resource = cancelHandoff == null ? null : cancelHandoff.partitionOf(order.marketId());
            // 파티션 대응이 없는 마켓(P3 에서 대응을 만들지 않은 마켓)은 넘길 곳이 없다. 직접 취소를 시도하고 게이트 검사가 끝낸다.
            if (resource == null || opened.computeIfAbsent(resource, cancelHandoff::opened)) {
                local.add(order.externalId());
            } else {
                remote.computeIfAbsent(resource, r -> new ArrayList<>()).add(order.externalId());
            }
        }
        OrderCancelHandoff.Submitted submitted = remote.isEmpty() ? null
                : cancelHandoff.submit(userId, remote, origin);
        BulkCancelTally tally = new BulkCancelTally();
        cancelLocally(userId, local, tally);
        if (submitted != null) {
            cancelHandoff.settle(submitted, tally);
        }
        return tally.result();
    }

    /** 이 노드가 게이트를 연 파티션의 주문을 하나씩 취소하고 성패를 원소별로 적는다(§4-B-3). */
    private void cancelLocally(long userId, List<UUID> open, BulkCancelTally tally) {
        for (int i = 0; i < open.size(); i++) {
            UUID externalId = open.get(i);
            try {
                Placement placement = cancelOne(userId, externalId, null, tally);
                if ("CANCELED".equals(placement.row().status())) {
                    tally.canceled(externalId.toString());
                } else {
                    // 나열과 취소 사이에 체결·종결된 경합 분. 취소 대상이 아니라 실패로 집계한다.
                    tally.failed(externalId.toString(), placement.row().status().toLowerCase(Locale.ROOT));
                }
            } catch (ApiException e) {
                if (e.dbOutage() != ApiException.DbOutage.NONE) {
                    // 저널에 들어갔을 수 있으나 확인하지 못한 취소다. 단건의 409 를 '저널 뒤 반영 대기'로 보지 않고 미완료로 센다
                    // (API 설계 §2-5-c). 재조회마저 DB 에 닿지 못했으면 남은 주문의 취소 전 조회가 같은 장애를 원시 예외로 올려
                    // 앞 결과를 잃으므로 시도하지 않는다.
                    int until = e.dbOutage() == ApiException.DbOutage.UNREACHABLE ? open.size() : i + 1;
                    for (UUID skipped : open.subList(i, until)) {
                        tally.failed(skipped.toString(), BulkCancelTally.SERVICE_UNAVAILABLE);
                    }
                    if (until == open.size()) {
                        break;
                    }
                    continue;
                }
                tally.failed(externalId.toString(), failureReason(e));
            }
        }
    }

    /**
     * 인계받은 취소 한 건의 결말(DB 설계 §5.4-d 3). kind 가 RECORDED 면 reason 을 결과에 적고, LEADER_LOST 면 적지 않는다.
     * LEADER_LOST 의 reason 은 같은 주문에서 세 번 이어졌을 때 적을 로컬 일괄 취소의 사유다.
     */
    public record HandoffOutcome(Kind kind, String reason, RuntimeException cause) {

        public enum Kind {
            /** 취소됨. 먼저 취소로 종결된 주문도 여기다. */
            CANCELED,
            /** 결과에 적는 실패(먼저 종결된 상태, order_not_found, order_in_progress, market_suspended, 경합, 연결 장애). */
            RECORDED,
            /** 취소 전 조회나 재조회가 DB 에 닿지 못했다. 그 주문과 남은 주문을 service_unavailable 로 적는다. */
            UNREACHABLE,
            /** 리더 자격을 잃었다는 거부(리더 자격 거부, 라이터 정지, 승격 재생 중). 결과에 적지 않는다. */
            LEADER_LOST,
            /** 예기치 않은 예외(결함). 그 주문을 server_error 로 적는다. */
            DEFECT
        }

        static HandoffOutcome canceled() {
            return new HandoffOutcome(Kind.CANCELED, null, null);
        }

        static HandoffOutcome recorded(String reason) {
            return new HandoffOutcome(Kind.RECORDED, reason, null);
        }

        static HandoffOutcome unreachable(RuntimeException cause) {
            return new HandoffOutcome(Kind.UNREACHABLE, BulkCancelTally.SERVICE_UNAVAILABLE, cause);
        }

        static HandoffOutcome leaderLost(String fallbackReason, RuntimeException cause) {
            return new HandoffOutcome(Kind.LEADER_LOST, fallbackReason, cause);
        }

        static HandoffOutcome defect(RuntimeException cause) {
            return new HandoffOutcome(Kind.DEFECT, BulkCancelTally.SERVER_ERROR, cause);
        }
    }

    /**
     * 인계받은 취소 한 건(DB 설계 §5.4-d 3). 단건 취소와 같은 길(회원으로 거른 대상 주문 읽기, 종결 확인, 채번, submit, 반영
     * 대기)을 가되 계정 레이트리밋과 힌트가 없고, HTTP 응답 대신 결말을 돌려준다. 예외를 던지지 않는다.
     */
    public HandoffOutcome cancelForHandoff(long userId, UUID externalId) {
        OrderQueryService.OrderRow row;
        try {
            row = read(() -> orders.byExternalId(externalId)).filter(r -> r.userId() == userId).orElse(null);
        } catch (RuntimeException e) {
            return DbOutageErrors.isOutage(e) ? HandoffOutcome.unreachable(e) : HandoffOutcome.defect(e);
        }
        if (row == null) {
            // 행의 user_id 로 걸러 읽으므로 다른 회원의 주문 id 는 여기서 끝난다.
            return HandoffOutcome.recorded("order_not_found");
        }
        if (row.terminal()) {
            return "CANCELED".equals(row.status()) ? HandoffOutcome.canceled()
                    : HandoffOutcome.recorded(row.status().toLowerCase(Locale.ROOT));
        }
        if (!bridge.accepting(row.marketId())) {
            // 그 마켓의 게이트가 이 노드에서 닫혔다. 작업자가 기간을 다시 보고 멈추거나 이어 한다.
            return HandoffOutcome.leaderLost(BulkCancelTally.SERVICE_UNAVAILABLE,
                    new NotReadyException("인계 취소: 마켓 게이트 닫힘(market=" + row.marketId() + ")"));
        }
        OrderIntake intake;
        try {
            MarketDirectory.Market market = markets.byId(row.marketId());
            intake = new OrderIntake(
                    market.id(), CommandDraft.CommandType.CANCEL, userId, null, row.id(),
                    System.currentTimeMillis(), nextOrderId(),
                    null, null, null, null, null, null, null, null, null);
        } catch (RuntimeException e) {
            return DbOutageErrors.isOutage(e) ? HandoffOutcome.unreachable(e) : HandoffOutcome.defect(e);
        }
        try {
            bridge.submit(intake);
        } catch (RuntimeException e) {
            return handoffSubmitFailure(e, intake);
        }
        OrderQueryService.OrderRow settled;
        try {
            settled = awaitRow(() -> orders.byInternalId(row.id()).filter(OrderQueryService.OrderRow::terminal));
        } catch (RuntimeException e) {
            return DbOutageErrors.isOutage(e) ? HandoffOutcome.unreachable(e) : HandoffOutcome.defect(e);
        }
        if (settled == null) {
            // 취소가 저널에 들어간 뒤 반영을 기다리다 끝났다. 곧 반영된다(API 설계 §4-B-3 order_in_progress).
            return HandoffOutcome.recorded("order_in_progress");
        }
        return "CANCELED".equals(settled.status()) ? HandoffOutcome.canceled()
                : HandoffOutcome.recorded(settled.status().toLowerCase(Locale.ROOT));
    }

    /** 인계 취소의 submit 예외를 결말로 가른다. 분류는 단건 취소(submit)와 같다. */
    private HandoffOutcome handoffSubmitFailure(RuntimeException e, OrderIntake intake) {
        String cause = contentionCause(e);
        if (cause != null) {
            // 커밋 전에 롤백됐거나 저널 전에 끝나 이 취소 명령이 저널에 없다는 것이 확정이다(API 설계 §2-5-b).
            countTransient(e);
            countContention(cause, "429");
            return HandoffOutcome.recorded(BulkCancelTally.TOO_MANY_REQUESTS);
        }
        if (e instanceof EngineBridge.MarketHaltedException) {
            return HandoffOutcome.recorded(BulkCancelTally.MARKET_SUSPENDED);
        }
        if (e instanceof IllegalStateException || e instanceof NotReadyException) {
            // 라이터 정지와 승격 재생 중. 저널 전 거부다.
            return HandoffOutcome.leaderLost(BulkCancelTally.SERVICE_UNAVAILABLE, e);
        }
        if (leaderRejection(e)) {
            // fencing 거부, 옛 epoch 의 삽입 0행, 기간 종료. 롤백됐거나 DB 에 가지 않았다. 게이트를 연 노드의 이 거부를 세 번
            // 이어 받으면 로컬 일괄 취소처럼 too_many_requests 로 적는다.
            log.warn("인계 취소가 리더 자격 거부로 끝났다: market={}, order_id={}, 예외={}", intake.marketId(),
                    intake.assignedOrderId(), SqlErrors.describe(e));
            return HandoffOutcome.leaderLost(BulkCancelTally.TOO_MANY_REQUESTS, e);
        }
        if (DbOutageErrors.isOutage(e)) {
            // 취소 명령이 저널에 들어갔는지 모른다. 대상 주문을 다시 읽어 DB 에 닿으면 이 주문만, 닿지 못하면 남은 주문까지
            // service_unavailable 로 적는다.
            int[] reads = new int[1];
            RuntimeException reread = rereadFailure(intake.assignedOrderId(), reads);
            log.warn("인계 취소의 append 가 DB 연결 장애로 끝났다: order_id={}, 재조회={}, 예외={}", intake.assignedOrderId(),
                    reread == null ? "닿음" : SqlErrors.describe(reread), SqlErrors.describe(e));
            return reread != null && DbOutageErrors.isOutage(reread) ? HandoffOutcome.unreachable(e)
                    : HandoffOutcome.recorded(BulkCancelTally.SERVICE_UNAVAILABLE);
        }
        return HandoffOutcome.defect(e);
    }

    /** 일괄 취소의 원소별 실패 사유. 도메인 거부는 오류 이름이 아니라 거부 사유를 싣는다(§4-B-3). */
    private static String failureReason(ApiException e) {
        Object reason = e.detail() == null ? null : e.detail().get("reject_reason");
        return reason instanceof String s ? s : e.name();
    }

    private long currentEpoch(int marketId) {
        kr.exchange.engine.PartitionPlan plan = partitionPlan;
        String resource = plan == null ? LeaseResources.MATCHING_ENGINE : plan.partitionOf(marketId);
        return jdbc.sql("SELECT COALESCE(max(epoch), 0) FROM leader_lease WHERE resource = ?")
                .param(resource == null ? LeaseResources.MATCHING_ENGINE : resource)
                .query(Long.class)
                .single();
    }

    private SubmitResult submit(OrderIntake intake) {
        try {
            return bridge.submit(intake);
        } catch (IllegalStateException e) {
            // 루프 정지(리더십 상실 창). 저널 커밋 전 거부이므로 재조회 계약으로 수렴시킨다.
            throw new ApiException(503, "service_unavailable",
                    "페일오버 진행 중. 재조회 후 판단 필요", null, 1);
        } catch (EngineBridge.MarketHaltedException e) {
            // 취소 경로는 마켓 상태를 미리 보지 않아 정지 중이면 늘 여기서 거부된다. 저널 커밋 전 거부라 주문
            // 생성과 같은 도메인 거부로 돌려야 일괄 취소가 원소별 실패로 집계하고 나머지를 계속 취소한다.
            throw marketSuspended();
        } catch (RuntimeException e) {
            String cause = contentionCause(e);
            if (cause != null) {
                // 취소는 네 오류가 모두 커밋 전에 롤백되고 대기 초과는 저널 전에 끝나므로 이 취소 명령이 저널에 없다는 것이
                // 확정이다. 429 는 그것만 뜻하고 주문 상태는 그 사이 바뀌었을 수 있다(API 설계 §2-5-b).
                countTransient(e);
                countContention(cause, "429");
                throw marketContention(!"lock_wait_stuck".equals(cause));
            }
            if (leaderRejection(e)) {
                // 롤백됐거나 DB 에 가지 않은 취소라 저널에 없다는 것이 확정이다. 게이트를 연 노드는 리더이므로 빼지 않는다.
                ApiException response = bridge.accepting(intake.marketId())
                        ? marketContention(false) : failoverUnavailable();
                recordUnavailable("fencing", response, "취소", 0, e);
                throw response;
            }
            if (DbOutageErrors.isOutage(e)) {
                throw cancelOutage(intake.assignedOrderId(), e);
            }
            throw e;
        }
    }

    /**
     * 취소 append 가 DB 불통으로 끝났다(API 설계 §2-5-c). 취소에는 V47 같은 저널 판정이 없어 대상 주문을 다시 읽어 DB 도달
     * 여부만 본다. 취소가 저널에 들어갔는지는 모르므로 읽혔으면 상태와 무관하게 409 이고, 읽기마저 DB 에 닿지 못했을 때만
     * 쓰기 업스트림에서 이 노드를 빼는 503 이다. 끊긴 연결은 풀이 버리므로 연결 끊김이면 한 번 더 읽는다.
     */
    private ApiException cancelOutage(long orderId, RuntimeException e) {
        int[] reads = new int[1];
        RuntimeException failure = rereadFailure(orderId, reads);
        ApiException response = failure != null && DbOutageErrors.isOutage(failure) ? dbUnreachable()
                : new ApiException(409, "order_in_progress", "취소 처리 중. 재조회 필요", null, 1, false,
                        ApiException.DbOutage.REACHED);
        recordUnavailable("connection", response, "취소", reads[0], e);
        return response;
    }

    /** 대상 주문을 다시 읽는다. 연결 끊김이면 한 번 더 읽는다. 마지막 읽기의 실패를 돌려주고 읽혔으면 null 이다. */
    private RuntimeException rereadFailure(long orderId, int[] reads) {
        RuntimeException failure;
        do {
            reads[0]++;
            try {
                orders.byInternalId(orderId);
                failure = null;
            } catch (RuntimeException readFailure) {
                failure = readFailure;
            }
        } while (failure != null && reads[0] < 2
                && DbOutageErrors.kind(failure) == DbOutageErrors.Kind.CONNECTION_LOST);
        return failure;
    }

    private OrderQueryService.OrderRow awaitRow(
            java.util.function.Supplier<Optional<OrderQueryService.OrderRow>> query) {
        return awaitRow(query, 0L);
    }

    /**
     * 프로젝션에 행이 생길 때까지 기다린다.
     *
     * orderId가 양수이고 신호가 붙어 있으면 먼저 신호를 기다린다. 정산이 같은 JVM에서 커밋
     * 직후 깨우므로 그 뒤 조회 한 번이면 끝난다. 2026-09-04 실측에서 이 조회가 주문 하나당
     * 약 100회였고(20ms 폴링 x 대기 시간), 그것이 전체 DB 호출의 절반을 넘었다.
     *
     * 신호가 오지 않으면 종전 폴링으로 넘어간다. 다른 노드의 엔진이 처리한 경우, 신호 등록이
     * 상한에 걸린 경우, 신호 배선이 없는 경우가 그에 해당한다. 곧 신호는 빠른 길일 뿐이고
     * 정확성은 폴링이 그대로 보장한다.
     */
    private OrderQueryService.OrderRow awaitRow(
            java.util.function.Supplier<Optional<OrderQueryService.OrderRow>> query, long orderId) {
        long deadline = System.currentTimeMillis() + PROJECTION_WAIT_MS;
        // 먼저 한 번 조회한다. 멱등 재생처럼 이미 반영된 경우가 여기서 끝난다. 그 경우 신호는
        // 오지 않는다(원본 주문이 다른 id로 이미 정산됐다). 조회를 신호 뒤로 미루면 그 경로가
        // 신호를 기다리다 예산을 소진하고 409로 끝난다. 실측으로 재현했다(2026-09-04).
        Optional<OrderQueryService.OrderRow> first = query.get();
        if (first.isPresent()) {
            return first.get();
        }
        // 신호를 기다리되 예산의 일부만 쓴다. 신호가 오지 않는 경로가 폴링 기회를 잃으면
        // 종전보다 나빠진다. 남는 예산이 폴링 몫이다.
        if (orderId > 0 && signal != null) {
            long slice = Math.min(SIGNAL_WAIT_MS, deadline - System.currentTimeMillis());
            if (slice > 0 && signal.await(orderId, slice)) {
                Optional<OrderQueryService.OrderRow> row = query.get();
                if (row.isPresent()) {
                    return row.get();
                }
            }
        }
        while (System.currentTimeMillis() < deadline) {
            Optional<OrderQueryService.OrderRow> row = query.get();
            if (row.isPresent()) {
                return row.get();
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 검증(§4-B-1 조건부 규칙 + §1.4 앱 가드)
    // ------------------------------------------------------------------

    private record ParsedOrder(MarketDirectory.Market market, OrderCommand.Side side,
                               OrderCommand.OrdType ordType, OrderCommand.TimeInForce timeInForce,
                               BigDecimal price, BigDecimal quantity, BigDecimal quoteOrderQty) {
        boolean buy() {
            return side == OrderCommand.Side.BUY;
        }

        /** 정산 예약 산식과 동일해야 가드가 유효하다(예약 UP 양자화, §1.4 v2.9 레짐). */
        BigDecimal reserve() {
            if (ordType == OrderCommand.OrdType.MARKET && side == OrderCommand.Side.BUY) {
                return quoteOrderQty;
            }
            if (side == OrderCommand.Side.BUY) {
                return Fees.quantize(price.multiply(quantity), market.quoteScale(), RoundingMode.UP);
            }
            return quantity;
        }
    }

    private ParsedOrder validate(JsonNode body) {
        MarketDirectory.Market market = markets.bySymbol(requireText(body, "market"));
        if (!"ACTIVE".equals(market.status())) {
            throw marketSuspended();
        }
        OrderCommand.Side side = switch (requireText(body, "side")) {
            case "bid" -> OrderCommand.Side.BUY;
            case "ask" -> OrderCommand.Side.SELL;
            default -> throw invalid("side_enum");
        };
        OrderCommand.OrdType ordType = switch (requireText(body, "ord_type")) {
            case "limit" -> OrderCommand.OrdType.LIMIT;
            case "market" -> OrderCommand.OrdType.MARKET;
            case "best" -> OrderCommand.OrdType.BEST;
            default -> throw invalid("ord_type_enum");
        };
        OrderCommand.TimeInForce tif = null;
        if (body.hasNonNull("time_in_force")) {
            tif = switch (body.get("time_in_force").asText()) {
                case "GTC" -> OrderCommand.TimeInForce.GTC;
                case "IOC" -> OrderCommand.TimeInForce.IOC;
                case "FOK" -> OrderCommand.TimeInForce.FOK;
                case "POST_ONLY" -> OrderCommand.TimeInForce.POST_ONLY;
                default -> throw invalid("time_in_force_enum");
            };
        }
        BigDecimal price = decimal(body, "price");
        BigDecimal volume = decimal(body, "volume");

        // §4-B-1 상호배타 규칙(v2.6)
        if (tif == OrderCommand.TimeInForce.POST_ONLY && ordType != OrderCommand.OrdType.LIMIT) {
            throw invalid("post_only_limit_only");
        }
        BigDecimal quantity = null;
        BigDecimal quoteOrderQty = null;
        switch (ordType) {
            case LIMIT -> {
                if (price == null || volume == null) {
                    throw invalid("limit_requires_price_volume");
                }
                quantity = volume;
            }
            case MARKET -> {
                if (side == OrderCommand.Side.BUY) {
                    if (price == null || volume != null) {
                        throw invalid("market_bid_notional_only");
                    }
                    quoteOrderQty = price;
                    price = null;
                } else {
                    if (volume == null || price != null) {
                        throw invalid("market_ask_volume_only");
                    }
                    quantity = volume;
                }
            }
            case BEST -> {
                if (volume == null || price != null) {
                    throw invalid("best_requires_volume");
                }
                if (side == OrderCommand.Side.BUY) {
                    // best 매수는 volume만 지정하고 체결가가 오더북 의존이라 정적 예약 산식이 없다.
                    // 접수 시 reserve()가 price null로 NPE를 내 결정론적 500이 무한 반복되므로,
                    // idempotency insert 이전인 이 지점에서 명시 거부한다(§4-B-1 side 제약 연동).
                    throw invalid("best_bid_unsupported");
                }
                if (tif != OrderCommand.TimeInForce.IOC && tif != OrderCommand.TimeInForce.FOK) {
                    throw invalid("best_requires_ioc_or_fok");
                }
                quantity = volume;
            }
        }

        // 수치·마켓 규칙 가드
        if (price != null) {
            if (price.signum() <= 0 || price.remainder(market.tickSize()).signum() != 0) {
                throw new ApiException(400, "invalid_price", "가격 단위(tick) 오류",
                        Map.of("tick", market.tickSize().stripTrailingZeros().toPlainString()), null);
            }
        }
        if (quantity != null) {
            if (quantity.signum() <= 0 || quantity.remainder(market.lotSize()).signum() != 0) {
                throw new ApiException(400, "invalid_volume", "수량 단위(lot) 오류",
                        Map.of("lot", market.lotSize().stripTrailingZeros().toPlainString()), null);
            }
            if (quantity.compareTo(market.maxQty()) > 0) {
                throw new ApiException(400, "over_max_order", "최대 주문 수량 초과");
            }
        }
        if (quoteOrderQty != null) {
            if (quoteOrderQty.signum() <= 0) {
                throw invalid("notional_positive");
            }
            if (quoteOrderQty.stripTrailingZeros().scale() > 18) {
                // 예약은 notional 그대로(양자화 없음), 소진은 scale 18 DOWN이라
                // 초과 자리는 어느 경로로도 표현 불가다(§1.4 v2.10 앱 가드).
                throw invalid("notional_scale_exceeded");
            }
            if (market.quoteScale() != 18) {
                // MARKET BUY(notional) 허용 마켓은 quote scale 18 필수(§1.4 v2.9 앱 가드).
                throw invalid("market_buy_quote_scale_18");
            }
        }
        BigDecimal notional = quoteOrderQty != null ? quoteOrderQty
                : price != null ? price.multiply(quantity) : null;
        if (notional != null) {
            if (notional.compareTo(market.minNotional()) < 0) {
                throw new ApiException(400, "under_min_order", "최소 주문 금액에 미달합니다.",
                        Map.of("min", market.minNotional().stripTrailingZeros().toPlainString()), null);
            }
            if (notional.compareTo(market.maxNotional()) > 0) {
                throw new ApiException(400, "over_max_order", "최대 주문 금액 초과");
            }
        }
        return new ParsedOrder(market, side, ordType, tif, price, quantity, quoteOrderQty);
    }

    /**
     * 예약 자산의 가용 잔고. 계정이 없으면 접수 단계에서 거부한다(정본 13.5 한계 40).
     *
     * 종전에는 계정이 없어도 잔고 0 으로 보고 그대로 진행했다. 그러면 정산이 그 계정을
     * 찾다가 예외를 던지고, 그 예외가 단일 라이터를 fail-fast 로 멈추며 리더십까지 함께
     * 내려놓는다. 2026-09-10 실측에서 그 순서를 그대로 봤다. 계정 없음 예외, 라이터 정지,
     * 리더십 상실이다. 곧 한 사용자의 잘못된 주문 하나가 노드를 통째로 내렸다.
     *
     * 잔고 0 과 계정 부재는 다른 사실이다. 매수는 예약액이 금액이라 잔고 0 이면 가드에
     * 걸리지만, 매도는 예약액이 수량이라 그 자산 잔고를 보지 않고 통과할 수 있다.
     * 그래서 잔고로 대신 막을 수 없고 계정 실재를 따로 확인해야 한다.
     *
     * 400 으로 돌려보낸다. 클라이언트가 고칠 수 있는 요청 문제이고, 서버 용량 문제가
     * 아니므로 503 이 아니다.
     *
     * user_balances 조회로 계정 실재를 판정해도 되는 근거는 스키마에 있다. 그 뷰는
     * accounts 와 balances 의 조인이고(V1__init.sql:135-141), balances 행은 계정이
     * 생길 때 트리거가 반드시 만든다(trg_account_balance, V1__init.sql:131). 곧 계정이
     * 있는데 뷰에 안 나오는 상태가 물리적으로 생기지 않는다. 실측으로도 확인했다.
     * 사용자 계정 1,570개 전부가 잔고 행을 가지고 있고 없는 것이 0개다.
     *
     * 그 트리거가 사라지면 이 판정이 정상 계정을 오거부한다. 트리거를 건드릴 때
     * 이 자리를 함께 봐야 한다.
     */
    private BigDecimal availableBalance(long userId, short assetId) {
        return jdbc.sql("SELECT available FROM user_balances WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new ApiException(400, "account_not_found",
                        "해당 자산 계좌 없음",
                        Map.of("asset_id", String.valueOf(assetId)), null));
    }

    /**
     * 주문 id 사전 채번(V31 order_id_seq).
     *
     * 종전에는 `next_journal_value('order_id')` 를 썼다. 그것은 카운터 한 행을 UPDATE 로 잠그고
     * 트랜잭션이 끝날 때까지 쥔다. 이 메서드는 트랜잭션 밖에서 도므로 한 문장이 곧 한
     * 트랜잭션이고, **주문마다 id 채번만을 위한 커밋과 WAL 플러시가 한 번 더 일어났다.**
     * 접수는 수용 정원만큼 동시에 들어오므로 그 잠금에서 서로 줄을 섰다.
     *
     * 2026-09-07 실측에서 그 문장이 명령당 2.73 회에 총 3202ms 로 fencing 의 12 배였다.
     * nextval 은 행 잠금을 잡지 않고 캐시 구간 안에서는 플러시도 없다.
     *
     * 구멍이 생기는 것은 계약상 허용된다. V2__gateway_counters.sql 이 "gapless는 요구되지
     * 않으나 채번 경로는 journal_counters 단일 방식으로 통일한다" 고 적었다. 통일성이 이유였고
     * 정확성이 아니었다. 실제 사용처는 전부 조인 키와 존재 검사다.
     */
    private long nextOrderId() {
        orderIdAssignments.increment();
        return jdbc.sql("SELECT nextval('order_id_seq')")
                .query(Long.class)
                .single();
    }

    /** 접수 락 대기 상한 초과의 저널 전 거부(ADR 0034 S1). 같은 client_order_id 로 다시 보내면 된다. */
    /**
     * 마켓 접수 경합의 확정 미접수(API 설계 §2-5-b). 재조회 대상이 아니고 같은 client_order_id 로 다시 보낸다. excluded 면
     * 좀비 판정의 수요에서 뺀다.
     */
    private static ApiException marketContention(boolean excluded) {
        return new ApiException(429, "too_many_requests", "마켓 접수 혼잡. 잠시 후 재시도 가능",
                Map.of("bucket", "market", "scope", "order"), 1, excluded);
    }

    /** 정지 마켓의 도메인 거부(API 설계 §4-B-1·§4-B-2). 주문 생성 검증과 생성·취소의 엔진 접수가 같은 응답을 낸다. */
    private static ApiException marketSuspended() {
        return new ApiException(422, "domain_rejected", "마켓이 정지 상태입니다.",
                Map.of("reject_reason", "market_suspended"), null);
    }

    private static ApiException invalid(String constraint) {
        return new ApiException(400, "invalid_parameter", "파라미터 조합 오류",
                Map.of("constraint", constraint), null);
    }

    private static String requireText(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "invalid_parameter", "필수 필드 누락: " + field);
        }
        return value;
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            return null;
        }
        try {
            return new BigDecimal(body.get(field).asText());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", field + " 수치 형식 오류");
        }
    }
}
