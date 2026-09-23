package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.auth.CanonicalRequests;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.dto.CancelAllResponse;
import kr.exchange.engine.EngineBridge;
import kr.exchange.ledger.ProjectionSignal;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.LeaseResources;
import kr.exchange.journal.CommandDraft;
import kr.exchange.ledger.Fees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * 주문 접수 게이트웨이. 검증·사전 가드·박제(external_id, 수수료율, engine_ts)를 끝낸 뒤
 * EngineBridge에 넘긴다(DB 설계 §4.4 비결정 입력 박제).
 *
 * 잔고 사전 가드는 필수다. 가드 없이 잔고 부족 명령이 저널을 통과하면 정산 예약(23514)이
 * 루프 정지 + demote로 이어진다(DB 설계 §5.1 v2.9). 가드와 정산 사이의 짧은 경합 창은
 * 인메모리 in-flight 예약 누계로 좁힌다. 누계가 노드 메모리에 있으므로 저널 append 를 리더
 * 한 노드만 한다는 전제에 기댄다.
 *
 * submit 이 예외로 끝나면 저널에 닿았는지를 이 요청의 주문 id 로 확인한다(V47). 커밋 응답만 잃은 것이면 키를 남기고
 * 라이터에 그 명령의 회수를 요청한 뒤 정상 접수처럼 응답한다. 닿지 않았으면 넣은 키를 정리하되, 같은 내용의 다른 요청이
 * 그 키로 대조했으면(공유 표시) 남긴다. 확인마저 실패하면 키와 예약을 남긴 채 원래 예외를 돌려준다.
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

    private final JdbcClient jdbc;
    private final EngineBridge bridge;
    private final MarketDirectory markets;
    private final OrderQueryService orders;
    private final InflightReserves inflight;
    private final LongAdder commitsRecovered = new LongAdder();
    private final LongAdder keysKept = new LongAdder();
    private final LongAdder unresolvedFailures = new LongAdder();
    // 프로젝션 반영을 알리는 신호. 없으면 종전대로 폴링만 한다.
    private ProjectionSignal signal;

    public OrderGateway(JdbcClient jdbc, EngineBridge bridge, MarketDirectory markets,
                        OrderQueryService orders) {
        this.jdbc = jdbc;
        this.bridge = bridge;
        this.markets = markets;
        this.orders = orders;
        this.inflight = new InflightReserves(
                orderId -> orders.byInternalId(orderId).isPresent(),
                System::currentTimeMillis, RESERVE_DEFER_TTL_MS);
    }

    /**
     * 프로젝션 신호를 붙인다. 선택 배선이라 붙이지 않으면 폴링만으로 동작한다.
     * 생성자를 늘리지 않는 이유는 기존 호출부와 테스트를 신호와 무관하게 두기 위해서다.
     */
    public void attachProjectionSignal(ProjectionSignal projectionSignal) {
        this.signal = projectionSignal;
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
        String clientOrderId = text(body, "client_order_id");
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new ApiException(400, "client_order_id_required", "client_order_id는 필수입니다.");
        }
        if (!clientOrderId.matches("[A-Za-z0-9_-]{1,36}")) {
            throw new ApiException(400, "invalid_parameter",
                    "client_order_id는 1~36자의 [A-Za-z0-9_-]여야 합니다.");
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
        boolean guardApplies;
        // 이 요청의 내용이 기존 키의 내용과 같음을 실제로 대조했는지. 저널 멱등 replay를 200으로
        // 돌려줘도 되는지 가르는 유일한 근거다(아래 replay 판정 참조).
        boolean contentVerified = false;
        if (stored.isPresent()) {
            requireSameContent(stored.get(), requestHash);
            contentVerified = true;
            guardApplies = !orders.commandJournaled(userId, clientOrderId);
        } else {
            guardApplies = true;
        }
        boolean guarded = false;
        if (guardApplies) {
            // 잔고 사전 가드(§5.1 v2.9). in-flight 예약 누계를 합산해 가드-정산 경합 창을 좁힌다.
            // 가드 실패는 멱등 키를 소비하기 전이라 같은 키의 후속 시도를 오염시키지 않는다.
            if (!inflight.tryReserve(reserveKey, required,
                    () -> availableBalance(userId, reserveAsset))) {
                throw new ApiException(400, "insufficient_funds", "주문 가능 잔고가 부족합니다.");
            }
            guarded = true;
        }
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
            if (guarded) {
                // 키를 넣거나 같은 내용의 기존 키에 공유 표시를 채운다(V47). 예약을 잡은 뒤라 여기서 예외가 나도
                // finally 가 예약을 푼다. 종전에는 이 단계가 try 밖이라 경합 409 나 DB 오류가 예약을 영영 남겼다.
                // 기존 키로 대조한 요청은 위에서 저널에 같은 키의 NEW 가 없음을 이미 봤다.
                KeyClaim claim = claimKey(userId, clientOrderId, requestHash, stored.isPresent());
                switch (claim) {
                    case INSERTED -> insertedKey = true;
                    case SHARED -> contentVerified = true;
                    case JOURNALED -> {
                        // 처음 읽을 때 키가 없어 가드를 잡았으나 경합에서 진 뒤 보니 같은 키의 NEW 가 이미 저널에 있다.
                        // 기존 키로 대조한 요청이 저널 실재를 본 경우와 같이 가드 없는 재생으로 보낸다. 재생은 새 명령을
                        // 커밋하지 않으므로 잡은 예약을 여기서 푼다. guarded 를 먼저 내려 finally 가 한 번 더 풀지 않게 한다.
                        // 두 번 풀면 같은 잔고의 다른 요청이 잡은 예약까지 빠져 가드가 빈다.
                        contentVerified = true;
                        guarded = false;
                        inflight.release(reserveKey, required);
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
            OrderIntake intake = new OrderIntake(
                    market.id(), CommandDraft.CommandType.NEW, userId, clientOrderId, assignedId,
                    System.currentTimeMillis(), assignedId,
                    parsed.side(), parsed.ordType(), parsed.timeInForce(),
                    parsed.price(), parsed.quantity(), parsed.quoteOrderQty(),
                    UUID.randomUUID(), market.makerFeeRate(), market.takerFeeRate());
            SubmitResult result;
            try {
                result = bridge.submit(intake);
                journaled = true;
            } catch (RuntimeException e) {
                if (!guarded) {
                    // 키도 예약도 잡지 않은 재전송이다. 같은 키의 NEW 가 이미 저널에 있어 append 는 재생만 하므로
                    // 새로 커밋됐을 명령이 없다.
                    throw submitFailure(e);
                }
                // append 가 커밋된 뒤 응답만 잃었을 수 있다. 이 요청의 주문 id 로 저널을 보고, 커밋되지 않았으면
                // 넣은 키를 정리한다(V47). 판정은 같은 키의 append 가 끝나기를 기다린 뒤에 한다.
                keyResolved = true;
                IntakeResolution resolution =
                        resolveFailedSubmit(userId, clientOrderId, requestHash, assignedId, insertedKey);
                if (resolution == null) {
                    outcomeUnknown = true;
                    throw submitFailure(e);
                }
                if (!resolution.journaled()) {
                    throw submitFailure(e);
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
        String status = jdbc.sql("SELECT status FROM users WHERE id = ?")
                .params(userId)
                .query(String.class)
                .optional()
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
                if (orders.commandJournaled(userId, clientOrderId)) {
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
    private record IntakeResolution(String outcome, long seq, int marketId, long fencing) {
        boolean journaled() {
            return "JOURNALED".equals(outcome);
        }
    }

    /**
     * append 예외 뒤 저널을 보고 키를 정리한다(V47 resolve_failed_order_intake). 판정 자체가 실패하면 null 이다.
     *
     * null 이면 커밋 여부를 모른다. 키를 지우지 않고 예약도 바로 풀지 않는다. 커밋됐다면 그 주문의 재시도가 키로 대조해
     * 멱등 재생으로 수렴하고, 커밋되지 않았다면 재시도가 대조 뒤 command_journal 실재로 잔고 가드를 다시 받는다.
     */
    private IntakeResolution resolveFailedSubmit(long userId, String clientOrderId, String requestHash,
                                                 long assignedId, boolean discardKey) {
        try {
            IntakeResolution resolution = jdbc.sql("SELECT * FROM resolve_failed_order_intake(?, ?, ?, ?, ?)")
                    .params(userId, clientOrderId, requestHash, assignedId, discardKey)
                    .query((rs, rowNum) -> new IntakeResolution(rs.getString("out_outcome"),
                            rs.getLong("out_seq"), rs.getInt("out_market_id"), rs.getLong("out_fencing")))
                    .single();
            if ("KEPT".equals(resolution.outcome())) {
                keysKept.increment();
            }
            return resolution;
        } catch (RuntimeException e) {
            unresolvedFailures.increment();
            log.warn("append 예외 뒤 저널 판정이 실패했다. 멱등 키를 남기고 in-flight 예약은 지연 회수한다: "
                    + "user_id={}, client_order_id={}, 원인={}", userId, clientOrderId, e.toString());
            return null;
        }
    }

    /**
     * 저널에 닿지 못한 submit 예외를 응답으로 바꾼다. 루프 정지(리더십 상실 창)는 재조회 계약의 503 이다.
     * 검증이 ACTIVE 를 본 뒤 마켓 정지가 들어온 창은 검증의 정지 거부와 같은 422 를 낸다.
     * 접수 락 대기 상한 초과(ADR 0034 S1, 기본 꺼짐)도 락 밖에서 끝난 저널 전 거부라 503 이다. API 예외로 바꿔야
     * 배치 주문이 이 원소만 in_progress 로 적고 다음 원소로 넘어간다(§4-B-6).
     *
     * 잠금 대기 초과(55P03) 같은 일시 DB 오류는 여기서 503 으로 바꾸지 않는다. 쓰기 업스트림의 max_fails=1 이 503
     * 하나로 리더를 빼므로 인그레스 설정과 함께 정한다(ADR 0034 결정 11, API 설계 v2.52).
     */
    private static RuntimeException submitFailure(RuntimeException e) {
        if (e instanceof IllegalStateException) {
            return new ApiException(503, "service_unavailable",
                    "페일오버 진행 중. 재조회 후 판단 필요", null, 1);
        }
        if (e instanceof EngineBridge.MarketHaltedException) {
            return marketSuspended();
        }
        if (e instanceof EngineBridge.AcceptBusyException) {
            return acceptBusy();
        }
        return e;
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
        return jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(userId, clientOrderId)
                .query(String.class)
                .optional();
    }

    private static void requireSameContent(String stored, String incoming) {
        if (!stored.equals(incoming)) {
            throw new ApiException(409, "duplicate_client_order_id",
                    "동일 client_order_id로 다른 내용의 주문이 존재합니다.");
        }
    }

    public Placement cancel(long userId, UUID externalId) {
        OrderQueryService.OrderRow row = orders.byExternalId(externalId)
                .filter(r -> r.userId() == userId)
                .orElseThrow(() -> new ApiException(404, "order_not_found", "주문 없음"));
        if (row.terminal()) {
            // 재취소 멱등(§2-4): 이미 종결이면 현재 상태를 그대로 반환한다.
            return new Placement(row, true, currentEpoch());
        }
        MarketDirectory.Market market = markets.byId(row.marketId());
        OrderIntake intake = new OrderIntake(
                market.id(), CommandDraft.CommandType.CANCEL, userId, null, row.id(),
                System.currentTimeMillis(), nextOrderId(),
                null, null, null, null, null, null, null, null, null);
        SubmitResult result = submit(intake);

        OrderQueryService.OrderRow settled = awaitRow(() ->
                orders.byInternalId(row.id()).filter(OrderQueryService.OrderRow::terminal));
        if (settled == null) {
            throw new ApiException(409, "order_in_progress",
                    "취소 처리 중. 재조회 필요", null, 1);
        }
        return new Placement(settled, false, result.epoch());
    }

    /** by-client 취소(§4-B-2). 프로젝션 미반영은 저널 권위로 404·409를 가른다(§4-B-4-a). */
    public Placement cancelByClient(long userId, String clientOrderId) {
        Optional<OrderQueryService.OrderRow> row = orders.byClientOrderId(userId, clientOrderId);
        if (row.isPresent()) {
            return cancel(userId, row.get().externalId());
        }
        if (!orders.commandJournaled(userId, clientOrderId)) {
            throw new ApiException(404, "order_not_found", "주문 없음");
        }
        throw new ApiException(409, "order_in_progress",
                "주문 처리 중. 재조회 필요", null, 1);
    }

    /**
     * 일괄 취소 결과. unsubmitted 는 failed 가운데 취소 명령이 저널에 닿지 못하고 503 으로 끝난 수다(라이터 정지, 켜 둔
     * 경우의 접수 락 대기 상한 초과). 그 주문은 아직 열려 있고 곧바로 다시 취소하면 닫힐 수 있다. 정지 마켓의 422 는
     * 여기에 넣지 않는다. 마켓 재개가 먼저라 곧바로 다시 불러도 닫히지 않는다(API 설계 §6-E). 체결로 먼저 종결된 주문과
     * 취소 저널 뒤 반영 대기 409 도 다시 부를 필요가 없어 세지 않는다.
     */
    public record BulkCancelResult(List<String> canceledIds,
                                   List<CancelAllResponse.Failure> failed,
                                   int unsubmitted) {
    }

    /** 일괄 취소(§4-B-3). 미체결(OPEN·PARTIAL) 전량을 개별 취소하고 성패를 per-item 집계한다. */
    public BulkCancelResult cancelAll(long userId, String market) {
        List<String> canceled = new ArrayList<>();
        List<CancelAllResponse.Failure> failed = new ArrayList<>();
        int unsubmitted = 0;
        for (UUID externalId : orders.openOrderIds(userId, market)) {
            try {
                Placement placement = cancel(userId, externalId);
                if ("CANCELED".equals(placement.row().status())) {
                    canceled.add(externalId.toString());
                } else {
                    // 나열과 취소 사이에 체결·종결된 경합 분. 취소 대상이 아니라 실패로 집계한다.
                    failed.add(new CancelAllResponse.Failure(externalId.toString(),
                            placement.row().status().toLowerCase(Locale.ROOT)));
                }
            } catch (ApiException e) {
                failed.add(new CancelAllResponse.Failure(externalId.toString(), failureReason(e)));
                if (e.status() == 503) {
                    unsubmitted++;
                }
            }
        }
        return new BulkCancelResult(canceled, failed, unsubmitted);
    }

    /** 일괄 취소의 원소별 실패 사유. 도메인 거부는 오류 이름이 아니라 거부 사유를 싣는다(§4-B-3). */
    private static String failureReason(ApiException e) {
        Object reason = e.detail() == null ? null : e.detail().get("reject_reason");
        return reason instanceof String s ? s : e.name();
    }

    private long currentEpoch() {
        return jdbc.sql("SELECT COALESCE(max(epoch), 0) FROM leader_lease WHERE resource = ?")
                .param(LeaseResources.MATCHING_ENGINE)
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
        } catch (EngineBridge.AcceptBusyException e) {
            // 접수 락 대기 상한 초과(기본 꺼짐). 저널 전 거부라 같은 이유로 API 예외로 바꿔 일괄 취소가 이 건만 실패로
            // 집계하고 다음 주문으로 넘어가게 한다.
            throw acceptBusy();
        }
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
        return jdbc.sql("SELECT nextval('order_id_seq')")
                .query(Long.class)
                .single();
    }

    /** 접수 락 대기 상한 초과의 저널 전 거부(ADR 0034 S1). 같은 client_order_id 로 다시 보내면 된다. */
    private static ApiException acceptBusy() {
        return new ApiException(503, "service_unavailable",
                "접수 대기 상한 초과. 잠시 후 재시도 필요", null, 1);
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
