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
import kr.exchange.journal.EventSeqBlock;
import kr.exchange.journal.FencingErrors;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.SqlErrors;
import kr.exchange.journal.StaleLeaderException;
import kr.exchange.journal.TransientDbErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

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
 * 예약은 리더 모드에 따라 두 갈래다(DB 설계 §5.1 v2.54, ADR 0034 S3). P2 이상에서 접수한 주문 생성 명령은 접수
 * 트랜잭션이 이미 예약했고 order_reservations 에 한 행을 남겼다. 정산은 그 행을 권위로 써 reserve_funds 를 다시 부르지
 * 않고, 거부된 주문은 조기 반환보다 먼저 그 예약을 푼다. 예약 행이 없는 명령(P1 에서 접수)은 종전처럼 정산 시점에
 * 예약한다. 두 갈래는 영구히 함께 남는다.
 *
 * 정산 시점 예약 실패(잔고 부족 23514)는 배치 전체 롤백 + fail-fast다. P1 에서 그 판정은 게이트웨이 사전 가드의
 * 몫이고, P2 에서는 접수 예약이 먼저 걸러 버그가 없는 한 도달하지 않는다. 어느 쪽이든 엔진이 이미 수락·매칭한 뒤의
 * 사후 거절은 오더북과 저널의 disposition을 가르므로 무결성 위반으로 취급해 루프를 세우고 지표로 경보한다.
 *
 * 잔고 행 잠금(DB 설계 §5.0 v2.54 트랜잭션 단위 규칙). 배치는 원장 게시를 여러 번 한다(P1 예약, TRADE 마다 한 번, 해제).
 * 게시 하나 안의 account_id 오름차순만으로는 배치 전체의 순서가 어긋나 P2 접수 예약과 순환이 생긴다(S3 설계 11절 R6).
 * 그래서 첫 게시 직전에 배치가 게시할 수 있는 잔고 행 전체(잠금 계획)를 lock_balance_rows 로 오름차순 한 번에 잡는다.
 *
 * 일시 DB 오류(잠금 대기 초과, 문장 취소, 교착, 직렬화 실패)는 배치 전체를 제한 안에서 다시 돌린다. 실패한
 * 시도는 커밋 전에 롤백되고 다음 시도는 위 레지스트리 멱등 판정부터 다시 하므로 이중 정산이 생기지 않는다.
 * 한도를 넘긴 일시 오류와 재시도 대상이 아닌 오류(fencing 거부, 무결성 위반, 연결 오류, 계약 위반
 * IllegalStateException 등)는 예외를 그대로 올려 라이터가 fail-fast 한다.
 */
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private static final short PAYLOAD_SCHEMA_VERSION = 1;
    // 일시 DB 오류의 재시도 제한. 시도 수(첫 시도 포함)와 경과 시간 중 먼저 닿는 쪽에서 멈춘다.
    //
    // 대기 상한은 시도마다 두 배(100, 200, 400밀리초)이고 실제 대기는 그 절반에서 상한 사이의 무작위 값이다. 여러 마켓
    // 라이터가 같은 DB 정체에서 한꺼번에 실패해도 같은 순간에 다시 몰리지 않게 흩는다.
    //
    // 시도 수만으로는 시간이 묶이지 않는다. 한 시도는 여러 문장을 보내고 문장마다 prod 기본 풀의 lock_timeout 2초나
    // statement_timeout 3초까지 기다릴 수 있다. 57014 도 재시도하므로 네 시도가 모두 3초 문장 취소로 끝나면 대기까지
    // 약 12.7초다. 그래서 첫 시도 시작부터 RETRY_DEADLINE 이 지나면 새 시도를 열지 않는다. 최악은 대략 이 기한에 마지막
    // 한 시도의 길이를 더한 값이다. 잠금 대기 초과는 그 문장에서 시도를 끝내므로 흔한 경우는 기한 + 2초 안쪽이고,
    // 문장마다 상한 직전까지 기다리는 병적인 경우는 더 길 수 있다. 이 시간만큼 라이터 한 마켓이 멈추고, 승격 재생의
    // 크래시 창 재정산도 같은 경로라 그만큼 길어진다(좀비 판정 창 45초, in-flight 예약 TTL 30초와 비교할 값이다).
    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_BACKOFF_MS = 100;
    private static final long DEFAULT_RETRY_DEADLINE_MS = 5_000;
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
     * (1) 은 assert_and_lock_market_leader(V48) 한 함수가 판정과 잠금을 겸한다. 리스 행은 정산할 명령 행의 마켓에서
     * 찾고, 명령 행이 없을 때만 배치 마켓을 쓴다(DB 설계 §5.4-b). P1 에서는 matching-engine 행이라 판정·잠금·거부
     * 문구가 V41 의 assert_and_lock_leader 와 같고 FencingErrors 의 강등 분류가 바뀌지 않는다.
     *
     * 왜 하는가. 2026-09-10 실측에서 명령 하나의 직렬 시간 21.36밀리초 중 매칭이 0.02 이고
     * 나머지가 전부 정산이었다. 대기 사유는 행 잠금이 3.8퍼센트뿐이라 경합이 아니었다.
     * 남은 설명이 왕복 횟수였다. V30 이 같은 판단으로 (1) 을 두 문장에서 한 문장으로 줄였고
     * 이번은 그 자리에 (2) 와 (3) 을 마저 붙인다.
     *
     * 왜 이 모양인가. 한 행짜리 원천에 command_journal 을 LEFT JOIN 한다. 안쪽 조인으로 두면
     * 명령 행이 없을 때 결과가 0행이 되고, 그러면 판정 함수가 아예 실행되지
     * 않아 fencing 거부와 명령 부재를 가릴 수 없게 된다. LEFT JOIN 이면 언제나 한 행이다.
     *
     * 판정 순서는 리스 거부, 마켓 불일치, 멱등 스킵, 부분 적재, 명령 부재다.
     *
     * (4) 예약 행(V50 order_reservations)도 같은 왕복에 싣는다(ADR 0034 S3, S3 설계 4-8). 열 이름이 명령 행과 겹치므로
     * (market_id, user_id) 별칭을 단 파생 표로 붙인다. 왕복 수는 그대로다.
     */
    private static final String PROLOGUE_SQL =
            "SELECT assert_and_lock_market_leader(COALESCE(market_id, ?::smallint), ?, ?) AS epoch,"
            + " (SELECT count(*) FROM event_emission_registry"
            + "   WHERE source_cmd_seq = ?) AS registered,"
            + " " + CommandJournalReader.ENTRY_COLUMNS + ","
            + " res.res_order_id, res.res_user_id, res.res_market_id, res.res_asset_id,"
            + " res.res_available_account_id, res.res_locked_account_id, res.res_amount"
            + " FROM (VALUES (1)) AS one(x)"
            + " LEFT JOIN command_journal ON seq = ?"
            + " LEFT JOIN (SELECT r.order_id AS res_order_id, r.user_id AS res_user_id, r.market_id AS res_market_id,"
            + "                   r.asset_id AS res_asset_id, r.available_account_id AS res_available_account_id,"
            + "                   r.locked_account_id AS res_locked_account_id, r.amount AS res_amount"
            + "              FROM order_reservations r WHERE r.command_seq = ?) res ON true";

    /** 머리말 한 왕복의 결과. entry 는 명령 행이 없으면 null 이고 reservation 은 예약 행이 없으면 null 이다. */
    private record Prologue(Long epoch, long registered, JournalEntry entry, Reservation reservation) {
    }

    /** 접수 트랜잭션이 남긴 예약 행(V50). 정산은 이 금액과 계정을 권위로 쓴다. */
    private record Reservation(long orderId, long userId, int marketId, short assetId, long availableAccountId,
                               long lockedAccountId, BigDecimal amount) {
    }

    /**
     * 배치 하나의 정산 상태. 잠금 계획은 배치가 게시할 수 있는 잔고 행 계정 id 의 오름차순 목록이고 첫 게시 직전에 한 번
     * 잡는다. 계획이 꺼져 있으면(시험 주입점) plan 이 null 이다.
     */
    private static final class Batch {
        final long cmdSeq;
        final Reservation reservation;
        final long[] plan;
        final java.util.Set<Long> planned;
        boolean locked;

        Batch(long cmdSeq, Reservation reservation, java.util.SortedSet<Long> plan) {
            this.cmdSeq = cmdSeq;
            this.reservation = reservation;
            this.plan = plan == null ? null : plan.stream().mapToLong(Long::longValue).toArray();
            this.planned = plan;
        }
    }

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO event_journal
              (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
               payload_schema_version, fencing_epoch, created_at, market_id, market_seq)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

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
    private final LongAdder transientRetries = new LongAdder();
    private final Map<String, LongAdder> transientRetriesByState = new ConcurrentHashMap<>();
    private final LongAdder transientExhausted = new LongAdder();
    // 정산이 잔고 부족(23514 chk_balance_nonneg)으로 끝난 수. P2 에서는 버그 신호다(S3 설계 4-8, 4-16).
    private final LongAdder insufficientFunds = new LongAdder();
    // 정산 게시가 잠금 계획 밖의 계정을 쓴 수. 0 이어야 한다(S3 설계 4-13).
    private final LongAdder lockPlanMisses = new LongAdder();
    // 잠금 계획을 잡은 배치 수. 시험이 계획이 실제로 돌았음을 보이는 양성 축이다.
    private final LongAdder lockPlansTaken = new LongAdder();
    private volatile long retryDeadlineNanos = TimeUnit.MILLISECONDS.toNanos(DEFAULT_RETRY_DEADLINE_MS);
    // 시험 주입점 셋. 운영 배선은 계획을 늘 켜고 훅과 계획 줄이기를 두지 않는다.
    private volatile boolean lockPlanEnabled = true;
    private volatile PostHook postHook;
    private volatile java.util.function.LongPredicate lockPlanTrim;

    /** 원장 게시 하나가 끝난 직후 부르는 시험 훅. kind 는 RESERVE·RELEASE·TRADE 다. 트랜잭션 안에서 돈다. */
    @FunctionalInterface
    interface PostHook {
        void afterPost(String kind, long cmdSeq);
    }

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

    /** 일시 DB 오류로 배치를 다시 돌린 횟수. 흡수한 잠금 경합의 크기다. */
    public long transientRetries() {
        return transientRetries.sum();
    }

    /** 일시 DB 오류를 재시도 제한 안에서 흡수하지 못해 라이터로 올린 횟수. 1 이상이면 그 마켓 라이터가 멈추고 강등됐다. */
    public long transientExhausted() {
        return transientExhausted.sum();
    }

    /** 그 SQLSTATE 의 일시 DB 오류로 배치를 다시 돌린 횟수. 40P01 몫은 잠금 계획 뒤 결함 신호다(S3 설계 4-14). */
    public long transientRetries(String sqlState) {
        LongAdder n = transientRetriesByState.get(sqlState);
        return n == null ? 0 : n.sum();
    }

    /** 정산이 잔고 부족(23514 chk_balance_nonneg)으로 끝난 수. 1 이상이면 경보 대상이다(SettlementInsufficientFunds). */
    public long insufficientFunds() {
        return insufficientFunds.sum();
    }

    /** 정산 게시가 잠금 계획 밖 계정을 쓴 수. 교착 가능성 신호이고 0 이어야 한다. */
    public long lockPlanMisses() {
        return lockPlanMisses.sum();
    }

    /** 잠금 계획을 잡은 배치 수. */
    public long lockPlansTaken() {
        return lockPlansTaken.sum();
    }

    /** 잠금 계획을 끈다. 교착 재현 시험의 양성 축 전용이다(SettlementLockPlanTest). */
    void setLockPlanEnabled(boolean enabled) {
        this.lockPlanEnabled = enabled;
    }

    /** 게시 직후 훅을 건다. 시험 전용이다. */
    void setPostHook(PostHook hook) {
        this.postHook = hook;
    }

    /**
     * 계획을 만든 뒤 조건에 맞는 계정을 뺀다. 시험 전용이다. 계획 밖 게시를 세는 계수(lock_plan.miss)가 실제로 오르는지 보이는
     * 양성 축에 쓴다(SettlementLockPlanTest). 운영 코드에는 계획이 게시 계정을 빠뜨리는 경로가 없어 이것 없이는 그 계수를 올릴
     * 수 없다.
     */
    void setLockPlanTrim(java.util.function.LongPredicate trim) {
        this.lockPlanTrim = trim;
    }

    /**
     * 재시도 경과 기한을 바꾼다. 첫 시도 시작부터 이 시간이 지나면 새 시도를 열지 않는다. 기본 5초다.
     * 운영 배선은 기본값을 쓴다. 시험이 시도 수보다 기한이 먼저 닿는 경로를 만들 때 쓴다.
     */
    public void setRetryDeadline(java.time.Duration deadline) {
        this.retryDeadlineNanos = deadline.toNanos();
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
        long startedAt = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            try {
                return settleOnce(cmdSeq, events);
            } catch (DataAccessException e) {
                if (!TransientDbErrors.isRetryable(e)) {
                    throw e;
                }
                long backoffMs = backoffMs(attempt);
                long elapsedNanos = System.nanoTime() - startedAt;
                if (attempt >= MAX_ATTEMPTS
                        || elapsedNanos + TimeUnit.MILLISECONDS.toNanos(backoffMs) > retryDeadlineNanos) {
                    transientExhausted.increment();
                    log.error("정산 일시 DB 오류를 재시도 제한 안에서 흡수하지 못했다. 라이터가 멈추고 강등한다: cmd_seq={}, "
                                    + "sqlstate={}, 시도={}/{}, 경과_ms={}, 원인={}",
                            cmdSeq, TransientDbErrors.sqlState(e), attempt, MAX_ATTEMPTS,
                            TimeUnit.NANOSECONDS.toMillis(elapsedNanos), e.getMostSpecificCause().getMessage());
                    throw e;
                }
                transientRetries.increment();
                transientRetriesByState.computeIfAbsent(String.valueOf(TransientDbErrors.sqlState(e)),
                        k -> new LongAdder()).increment();
                log.warn("정산 일시 DB 오류, 배치를 다시 돌린다: cmd_seq={}, sqlstate={}, 시도={}/{}, 대기_ms={}, 원인={}",
                        cmdSeq, TransientDbErrors.sqlState(e), attempt, MAX_ATTEMPTS, backoffMs,
                        e.getMostSpecificCause().getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    // 인터럽트는 정지 요청(라이터 halt 등)이다. 재시도를 멈추고 원래 오류를 올린다.
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** attempt 번째 실패 뒤의 대기. 상한의 절반에서 상한 사이 무작위 값이다(50~100, 100~200, 200~400밀리초). */
    private static long backoffMs(int attempt) {
        long cap = RETRY_BACKOFF_MS << (attempt - 1);
        return cap / 2 + ThreadLocalRandom.current().nextLong(cap / 2 + 1);
    }

    private List<EngineEvent> settleOnce(long cmdSeq, List<EngineEvent> events) {
        // 시도마다 로컬 리더십을 다시 본다. 대기 중에 강등됐으면 DB 왕복 없이 멈춘다.
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
            if (SqlErrors.isInsufficientFunds(e)) {
                // 정산 시점 잔고 부족. P1 에서는 사전 가드를 통과한 명령의 무결성 위반이고 P2 에서는 버그 신호다. 분류는
                // 바꾸지 않는다(재시도 대상이 아니고 라이터가 멈춘다). 경보 SettlementInsufficientFunds 가 이 계수를 본다.
                insufficientFunds.increment();
                log.error("정산이 잔고 부족(23514 chk_balance_nonneg)으로 끝났다. 라이터가 멈춘다: cmd_seq={}, 원인={}",
                        cmdSeq, e.getMostSpecificCause().getMessage());
            }
            throw e;
        }
    }

    private List<EngineEvent> settleInTx(LeaderIdentity leader, long cmdSeq, List<EngineEvent> events) {
        // 머리말 한 왕복. fencing 검증과 FOR KEY SHARE 잠금에 멱등 탐침과 명령 읽기를
        // 함께 실어 세 왕복을 하나로 줄인다. 잠금을 잡은 뒤 판정하므로 판정과 커밋 사이에 epoch 이 바뀌지 않는다.
        int batchMarket = events.get(0).marketId();
        Prologue prologue = jdbc.sql(PROLOGUE_SQL)
                .params(batchMarket, leader.holder(), leader.epoch(), cmdSeq, cmdSeq, cmdSeq)
                .query((rs, rowNum) -> new Prologue(
                        (Long) rs.getObject("epoch"),
                        rs.getLong("registered"),
                        rs.getObject("seq") == null ? null : CommandJournalReader.mapEntry(rs),
                        rs.getObject("res_order_id") == null ? null : new Reservation(
                                rs.getLong("res_order_id"), rs.getLong("res_user_id"), rs.getInt("res_market_id"),
                                rs.getShort("res_asset_id"), rs.getLong("res_available_account_id"),
                                rs.getLong("res_locked_account_id"), rs.getBigDecimal("res_amount"))))
                .single();

        Long epoch = prologue.epoch();
        if (epoch == null) {
            throw new StaleLeaderException(
                    "stale 리더 정산 거부: holder=" + leader.holder() + ", epoch=" + leader.epoch());
        }

        // 잠근 리스는 명령 행의 마켓에서 찾았다. 기록할 행이 다른 마켓이면 그 마켓의 리스 판정 없이 쓰게 된다.
        for (EngineEvent event : events) {
            if (event.marketId() != batchMarket) {
                throw new IllegalStateException("정산 배치가 한 마켓이 아니다: cmd_seq=" + cmdSeq
                        + ", 마켓 " + batchMarket + " vs " + event.marketId());
            }
        }
        if (prologue.entry() != null && prologue.entry().marketId() != batchMarket) {
            throw new IllegalStateException("정산 배치 마켓이 명령 행 마켓과 다르다: cmd_seq=" + cmdSeq
                    + ", 명령 마켓=" + prologue.entry().marketId() + ", 배치 마켓=" + batchMarket);
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
        Reservation reservation = prologue.reservation();
        if (reservation != null && command.type() != OrderCommand.CommandType.NEW) {
            // 머리말은 주문 생성 명령에만 예약한다(DB 설계 §3.5). 연결 손상이라 재시도로 풀리지 않는다.
            throw new IllegalStateException("예약 행이 주문 생성이 아닌 명령에 붙어 있다: cmd_seq=" + cmdSeq);
        }

        // 마켓별 채번(ADR 0021). 한 정산 배치는 명령 하나의 방출분이고 명령은 마켓 하나에 속하므로
        // 배치 전체가 같은 마켓이다. 그래서 채번이 배치당 한 번이다. event_seq 파생은 DB 가 한다(V46).
        int marketId = command.marketId();
        Batch batch = new Batch(cmdSeq, reservation,
                lockPlanEnabled ? lockPlan(command, catalog.meta(marketId), events) : null);
        EventSeqBlock start = EventSeqBlock.allocate(jdbc, marketId, events.size());

        List<EngineEvent> assigned = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            long marketSeq = start.marketSeq() + i;
            EngineEvent event = events.get(i).withEventSeq(start.eventSeq() + i);
            Fees.TradeFees fees = event.type() == EngineEvent.EventType.TRADE
                    ? Fees.of(event, extras.makerFeeRate(), extras.takerFeeRate(),
                              catalog.meta(event.marketId()))
                    : null;
            insertEvent(epoch, event, fees, marketId, marketSeq);
            switch (event.type()) {
                case ACCEPTED -> settleAccepted(batch, command, extras);
                case REJECTED -> settleRejected(batch, command, extras, event);
                case TRADE -> settleTrade(batch, event, fees, epoch);
                case CANCELED -> settleCanceled(batch, event);
            }
            assigned.add(event);
        }
        return assigned;
    }

    // ------------------------------------------------------------------
    // 잔고 행 잠금 계획(DB 설계 §5.0 v2.54, ADR 0034 S3, S3 설계 4-13)
    // ------------------------------------------------------------------

    /**
     * 배치가 게시할 수 있는 잔고 행의 계정 id. 명령 사용자(taker 이자 취소되는 주문의 주인)의 그 마켓 base·quote 자산
     * AVAILABLE·LOCKED 네 행, 배치의 TRADE 마다 maker 사용자의 같은 네 행, TRADE 가 있으면 두 자산의 FEE_INCOME 행이다.
     *
     * 이 합집합이 정산 게시를 모두 덮는 근거는 코드다. ACCEPTED 예약(P1)과 REJECTED 해제(P2)는 명령 사용자의 예약 자산
     * AVAILABLE·LOCKED 만 쓴다. TRADE 분개는 매수자·매도자의 AVAILABLE·LOCKED 와 두 FEE_INCOME 만 쓰고 매수자·매도자는
     * taker 와 maker 뿐이다. CANCELED 해제는 취소되는 주문 주인의 두 행만 쓰는데, 엔진은 CANCEL 을 소유자 대조로만 처리하고
     * STP 도 같은 사용자 주문만 건드린다(OrderBookImpl). 계정은 캐시된 조회로 풀고 없는 계정은 뺀다. 그 계정을 실제로 쓰는
     * 게시는 종전처럼 게시 단계에서 멈춘다.
     *
     * 계획은 조금 넓다. 배치가 쓰지 않을 자산의 두 행과 maker 의 쓰지 않을 두 행도 잡으므로 같은 사용자의 다른 접수·출금이
     * 이 배치 커밋까지 기다리는 일이 는다. 교착과 달리 유계다. 수수료 행을 빼면 시스템 계정(id 가 작다)을 사용자 행 뒤에
     * 잡게 되어 순서가 다시 깨지므로 빼지 않는다.
     */
    private java.util.SortedSet<Long> lockPlan(OrderCommand command, MarketCatalog.MarketMeta meta,
                                               List<EngineEvent> events) {
        java.util.SortedSet<Long> plan = new java.util.TreeSet<>();
        addUserRows(plan, command.userId(), meta);
        boolean trades = false;
        for (EngineEvent event : events) {
            if (event.type() == EngineEvent.EventType.TRADE) {
                trades = true;
                if (event.makerUserId() != null) {
                    addUserRows(plan, event.makerUserId(), meta);
                }
            }
        }
        if (trades) {
            addIfPresent(plan, optionalFeeAccount(meta.baseAssetId()));
            addIfPresent(plan, optionalFeeAccount(meta.quoteAssetId()));
        }
        java.util.function.LongPredicate trim = lockPlanTrim;
        if (trim != null) {
            plan.removeIf(trim::test);
        }
        return plan;
    }

    private void addUserRows(java.util.SortedSet<Long> plan, long userId, MarketCatalog.MarketMeta meta) {
        for (short asset : new short[] {meta.baseAssetId(), meta.quoteAssetId()}) {
            addIfPresent(plan, optionalUserAccount(userId, "AVAILABLE", asset));
            addIfPresent(plan, optionalUserAccount(userId, "LOCKED", asset));
        }
    }

    private static void addIfPresent(java.util.SortedSet<Long> plan, Long accountId) {
        if (accountId != null) {
            plan.add(accountId);
        }
    }

    /**
     * 원장 게시 하나 직전. 배치의 첫 게시면 잠금 계획 전체를 오름차순 한 번에 잡고, 게시할 계정이 계획 안인지 본다. 벗어나면
     * 교착 가능성일 뿐 자금 안전과는 무관하므로 라이터를 멈추지 않고 지표와 경고로 드러낸다.
     */
    private void beforePost(Batch batch, String kind, long... accounts) {
        if (batch.plan == null) {
            return;
        }
        if (!batch.locked) {
            // 소유자 권한 함수다. app_role 에는 balances UPDATE 권한이 없어 행 잠금 절을 직접 쓸 수 없다(V50).
            jdbc.sql("SELECT lock_balance_rows(?::bigint[])")
                    .param(batch.plan)
                    .query(Integer.class)
                    .single();
            batch.locked = true;
            lockPlansTaken.increment();
        }
        for (long account : accounts) {
            if (!batch.planned.contains(account)) {
                lockPlanMisses.increment();
                log.warn("정산 게시가 잠금 계획 밖 계정을 쓴다(교착 가능성): cmd_seq={}, 게시={}, account_id={}, 계획={}",
                        batch.cmdSeq, kind, account, batch.planned);
            }
        }
    }

    /** 원장 게시 하나 직후. 시험 훅이 있으면 부른다. */
    private void afterPost(Batch batch, String kind) {
        PostHook hook = postHook;
        if (hook != null) {
            hook.afterPost(kind, batch.cmdSeq);
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED: 예약(§5.1) + orders 생성
    // ------------------------------------------------------------------

    private void settleAccepted(Batch batch, OrderCommand command, CommandPayloadMapper.Extras extras) {
        if (extras.externalId() == null) {
            // external_id는 접수 전 사전 발급 박제 계약(P0-4)이다. 없으면 재생마다 값이 달라진다.
            throw new IllegalStateException("external_id 미박제 명령: seq=" + command.seq());
        }
        MarketCatalog.MarketMeta meta = catalog.meta(command.marketId());
        boolean notional = command.ordType() == OrderCommand.OrdType.MARKET
                && command.side() == OrderCommand.Side.BUY;
        short assetId = command.side() == OrderCommand.Side.BUY ? meta.quoteAssetId() : meta.baseAssetId();
        long locked;
        BigDecimal reserve;
        Reservation reservation = batch.reservation;
        if (reservation != null) {
            // P2 접수 예약(ADR 0034 S3). 접수 트랜잭션이 이미 reserve_funds 로 잠갔다. 다시 부르면 원장 멱등 키(주문 id)
            // 23505 로 라이터가 멈춘다. 예약 행의 금액과 계정이 권위다. 예약액의 산식 동일성은 접수 때 머리말이 대조했다.
            requireLinked(reservation, command, assetId);
            locked = reservation.lockedAccountId();
            reserve = reservation.amount();
        } else {
            if (command.side() == OrderCommand.Side.BUY) {
                // LIMIT 매수 예약은 UP 양자화. 체결별 차감이 DOWN 절사라 예약이 모자라면 중간
                // 체결이 락 잔여를 초과한다. 초과 예약 잔차는 최종 체결의 실잔여 소비가 환급
                // 분개로 되돌린다(§5.2 (6), API §4-B-5).
                reserve = notional
                        ? command.quoteOrderQty()
                        : Fees.quantize(command.price().multiply(command.quantity()),
                                        meta.quoteScale(), RoundingMode.UP);
            } else {
                reserve = command.quantity();
            }
            long avail = userAccount(command.userId(), "AVAILABLE", assetId);
            locked = userAccount(command.userId(), "LOCKED", assetId);
            // 예약 행이 없는 명령(P1 에서 접수)은 정산 시점에 예약한다. 잔고 부족(23514)은 여기서 배치 전체를 롤백시킨다.
            // 클래스 주석의 fail-fast 계약.
            beforePost(batch, "RESERVE", avail, locked);
            jdbc.sql("SELECT reserve_funds(?, ?, ?, ?, ?)")
                    .params(command.assignedOrderId(), avail, locked, assetId, reserve)
                    .query(Long.class)
                    .single();
            afterPost(batch, "RESERVE");
        }
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

    private void settleRejected(Batch batch, OrderCommand command, CommandPayloadMapper.Extras extras,
                                EngineEvent event) {
        if (command.type() != OrderCommand.CommandType.NEW) {
            // CANCEL 거절(order_not_found)은 대상 주문 상태를 바꾸지 않는다. 저널 disposition으로 충분.
            return;
        }
        Reservation reservation = batch.reservation;
        if (reservation != null) {
            // P2 접수 예약의 해제(ADR 0034 S3, DB 설계 §5.1 P2 정산 규칙). 아래 조기 반환(중복 주문 id, 넣을 수 없는 모양)보다
            // 먼저 한다. 뒤에 두면 그 반환이 해제를 건너뛰어 예약이 LOCKED 에 영영 남는다. 예약 행은 이 명령의 것이라 다른
            // 주문의 자금을 풀 수 없다(같은 주문 id 의 RESERVE 거래는 원장 멱등 키가 하나만 허용한다). business_event_id 는
            // 이 REJECTED 이벤트의 event_seq 로 CANCELED 해제와 같은 축이다. 재정산은 레지스트리가 배치째 건너뛰어 한 번뿐이다.
            requireLinked(reservation, command, null);
            beforePost(batch, "RELEASE", reservation.lockedAccountId(), reservation.availableAccountId());
            jdbc.sql("SELECT release_funds(?, ?, ?, ?, ?)")
                    .params(event.eventSeq(), reservation.lockedAccountId(), reservation.availableAccountId(),
                            reservation.assetId(), reservation.amount())
                    .query(Long.class)
                    .single();
            afterPost(batch, "RELEASE");
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

    private void settleTrade(Batch batch, EngineEvent event, Fees.TradeFees fees, long epoch) {
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
        long[] legAccounts = new long[legs.size()];
        for (int i = 0; i < legs.size(); i++) {
            legAccounts[i] = legs.get(i).get("account_id").asLong();
        }
        beforePost(batch, "TRADE", legAccounts);
        jdbc.sql("SELECT post_ledger_transaction('TRADE', ?, ?::jsonb)")
                .params(event.eventSeq(), legs.toString())
                .query(Long.class)
                .single();
        afterPost(batch, "TRADE");

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

    private void settleCanceled(Batch batch, EngineEvent event) {
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
            releaseFunds(batch, event.eventSeq(), row, meta.quoteAssetId(), qty);
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
            releaseFunds(batch, event.eventSeq(), row, assetId, release);
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

    private void releaseFunds(Batch batch, long eventSeq, OrderRow row, short assetId, BigDecimal amount) {
        long avail = userAccount(row.userId(), "AVAILABLE", assetId);
        // business_event_id는 해제를 유발한 이벤트의 event_seq. 예약(order_id)과 구분한다(§5.3).
        beforePost(batch, "RELEASE", row.lockedAccountId(), avail);
        jdbc.sql("SELECT release_funds(?, ?, ?, ?, ?)")
                .params(eventSeq, row.lockedAccountId(), avail, assetId, amount)
                .query(Long.class)
                .single();
        afterPost(batch, "RELEASE");
    }

    /**
     * 예약 행이 이 명령의 것인지 본다(S3 설계 4-8). 주문 id·사용자·마켓이 명령과 같고, assetId 가 주어지면 side 에서 나온
     * 자산과 같아야 한다. 어긋나면 연결 손상이라 재시도로 풀리지 않으므로 라이터를 멈춘다.
     */
    private static void requireLinked(Reservation r, OrderCommand command, Short assetId) {
        if (r.orderId() != command.assignedOrderId() || r.userId() != command.userId()
                || r.marketId() != command.marketId() || (assetId != null && r.assetId() != assetId)) {
            throw new IllegalStateException("예약 행이 명령과 맞지 않는다(연결 손상): cmd_seq=" + command.seq()
                    + ", 예약 행(order=" + r.orderId() + ", user=" + r.userId() + ", market=" + r.marketId()
                    + ", asset=" + r.assetId() + "), 명령(order=" + command.assignedOrderId() + ", user="
                    + command.userId() + ", market=" + command.marketId() + ", asset=" + assetId + ")");
        }
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

    /** 잠금 계획용 조회. 계정이 없으면 null 이다. 성공 조회만 캐시에 남는다(userAccount 와 같은 캐시). */
    private Long optionalUserAccount(long userId, String purpose, short assetId) {
        UserAccountKey key = new UserAccountKey(userId, purpose, assetId);
        Long cached = userAccountCache.get(key);
        if (cached != null) {
            return cached;
        }
        Long id = jdbc.sql("""
                        SELECT id FROM accounts
                        WHERE owner_type = 'USER' AND owner_id = ? AND purpose = ?
                          AND asset_id = ? AND shard = 0
                        """)
                .params(userId, purpose, assetId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (id != null) {
            userAccountCache.putIfAbsent(key, id);
        }
        return id;
    }

    private Long optionalFeeAccount(short assetId) {
        Long cached = feeAccountCache.get(assetId);
        if (cached != null) {
            return cached;
        }
        Long id = jdbc.sql("""
                        SELECT id FROM accounts
                        WHERE owner_type = 'SYSTEM' AND purpose = 'FEE_INCOME'
                          AND asset_id = ? AND shard = ?
                        """)
                .params(assetId, FEE_SHARD)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (id != null) {
            feeAccountCache.putIfAbsent(assetId, id);
        }
        return id;
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
