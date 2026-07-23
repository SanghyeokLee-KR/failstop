package kr.exchange.harness;

/**
 * 불변식 검증 카탈로그의 앱 검증기 배선(하네스 검증설계 §4-1). 각 SQL은 위반 행 수(BIGINT)를
 * 단일 값으로 반환하고, ok는 count=0으로 판정한다. 컬럼명은 DB 설계 정본 실제 DDL 기준.
 *
 * C21~C23은 DB 설계 §6.4의 감사 카탈로그 ⑮(이벤트 방출 레지스트리 1:1, P0-4)·⑯(fencing 단조,
 * P0-1)·⑰(채번 카운터 정합, P0-5)이다. §6.5 pg_cron이 실환경(Testcontainers·카오스)에 없어 어떤
 * 러너도 평가하지 않던 공백을, 고아 체크(C09~C12) 이관과 동일하게 앱 스케줄러로 배선했다.
 *
 * C03·C09~C12·C15는 원래 pg_cron 분담이었으나, 카오스 스택·Testcontainers에 pg_cron이 없어
 * 실환경에서 아무 러너도 평가하지 않았다(고아 체결·미저널 체결 회귀가 페일오버 판정을 그대로
 * 통과). 이를 이 enum에 등록해 runScheduled/runAuthoritative가 실제로 평가하고 위반을
 * exchange_invariant_violations 게이지·invariant_checks에 노출하게 한다(하네스 검증설계 §4-2
 * 실행 분담 개정과 정합). C08~C12·C18·C20은 90일 retention 윈도로 스캔 비용에 상한을 건다
 * (§4-2 ③, detach된 파티션 배제).
 * C04는 DEFERRED CONSTRAINT TRIGGER(V1:350~370)가 하드 강제하므로 여기 없다.
 * runScheduled/runAuthoritative는 이 enum의 values()만 순회하므로 여기 없는 체크는 앱 run에서
 * 평가하지 않는다.
 *
 * trades의 체결 seq는 컬럼 id다(trade_id = event_seq, DB 설계 §3.1). 설계 문서 §4-1의
 * trades.seq 표기는 실제 컬럼 id로 옮긴다.
 *
 * Scope:
 *   SCHEDULED          상시 스케줄 + 권위 run 모두에서 평가.
 *   AUTHORITATIVE_ONLY 정지 시점·실험 사후 권위 run에서만 평가(C16 실험 한정, C13A·C19 정지 한정).
 *                      상시 스케줄에서 제외(라이브 프로젝션 지연 오탐·스테일 loadtest_acks 오탐 차단).
 */
public enum InvariantCheck {

    // ── 자산 보존·잔고 정합 ─────────────────────────────────────────────
    C01_ASSET_CONSERVATION(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT count(*) FROM (
                SELECT asset_id FROM ledger_entries
                GROUP BY asset_id HAVING SUM(amount) <> 0
            ) x
            """),
    C02_BAL_VS_LEDGER(Severity.CRITICAL, Scope.SCHEDULED,
            "SELECT count(*) FROM balance_ledger_check WHERE mismatch"),

    // ── 미봉인 잔존 거래(1분 초과 OPEN) ─────────────────────────────────
    // post_ledger_transaction은 개설·봉인을 원자 수행하므로 커밋된 OPEN이 1분 넘게 남으면
    // 정본 게시 경로 우회 신호다(하네스/수퍼유저 우회 삽입 백스톱). 심각도는 §4-1 C03 [HIGH]
    // 정본을 따른다. in-flight 트랜잭션의 OPEN 행은 커밋 전이라 이 스캔에 보이지 않는다.
    C03_TXN_UNSEALED(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM ledger_transactions
            WHERE status = 'OPEN' AND created_at < now() - interval '1 min'
            """),

    // ── 저널 gapless(유실 0 · RPO=0) ────────────────────────────────────
    C05_CMD_GAPLESS(Severity.CRITICAL, Scope.SCHEDULED,
            "SELECT COALESCE((max(seq) - min(seq) + 1) - count(*), 0) FROM command_journal"),
    C06_EVT_GAPLESS(Severity.CRITICAL, Scope.SCHEDULED,
            "SELECT COALESCE((max(event_seq) - min(event_seq) + 1) - count(*), 0) FROM event_journal"),

    // ── 중복 접수(프로젝션 축 · 저널 축) ─────────────────────────────────
    C07_DUP_ORDER(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT count(*) FROM (
                SELECT user_id, client_order_id FROM orders
                WHERE client_order_id IS NOT NULL
                GROUP BY user_id, client_order_id HAVING count(*) > 1
            ) x
            """),
    C07P_DUP_ORDER_JOURNAL(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT count(*) FROM (
                SELECT user_id, client_order_id FROM command_journal
                WHERE command_type = 'NEW' AND client_order_id IS NOT NULL
                GROUP BY user_id, client_order_id HAVING count(*) > 1
            ) x
            """),

    // ── 중복 체결(id 중복 0 · 레지스트리 1:1) ────────────────────────────
    // 90일 윈도는 §4-2 ③ 규약(retention 지평 한정). C09~C12와 동일 패턴으로 detach된
    // 파티션의 오탐·스캔 비용을 배제한다.
    C08_DUP_FILL(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT (
                SELECT count(*) FROM (
                    SELECT id FROM trades
                    WHERE created_at >= now() - interval '90 days'
                    GROUP BY id HAVING count(*) > 1
                ) dup
            ) + (
                SELECT count(*) FROM trades t
                LEFT JOIN trade_seq_registry r ON r.seq = t.id
                WHERE t.created_at >= now() - interval '90 days' AND r.seq IS NULL
            )
            """),

    // ── 체결 ⊆ event_journal(TRADE)(유실 체결 이벤트) ────────────────────
    // trade와 event_journal(TRADE)은 동일 정산 트랜잭션에 기록되고 trades.id=event_seq라
    // 정상 DB에서 0이다(SCHEDULED 안전). 90일 윈도로 detach된 파티션을 배제한다.
    C09_TRADE_SEQ_EVENT_MATCH(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM trades t
            LEFT JOIN event_journal e ON e.event_seq = t.id AND e.event_type = 'TRADE'
            WHERE t.created_at >= now() - interval '90 days' AND e.event_seq IS NULL
            """),

    // ── 체결 합 = filled_qty(수량 기반, 90일 지평) ──────────────────────
    // filled_qty는 정산이 trade INSERT와 같은 UPDATE에서 누적하는 프로젝션이라 async 창이 없다
    // (SCHEDULED). market BUY(quantity NULL) 노셔널 주문은 제외한다.
    C10_ORDER_FILL_AGG(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM (
                SELECT o.id
                FROM orders o
                LEFT JOIN trades t ON t.maker_order_id = o.id OR t.taker_order_id = o.id
                WHERE o.quantity IS NOT NULL AND o.created_at >= now() - interval '90 days'
                GROUP BY o.id, o.filled_qty
                HAVING o.filled_qty <> COALESCE(SUM(t.quantity), 0)
            ) x
            """),

    // ── 고아 체결(maker/taker 주문 부재) ─────────────────────────────────
    // trades.maker_order_id/taker_order_id는 FK 생략(파티션/핫패스)이라 이 탐지 쿼리가 유일한
    // 참조 무결성 보증이다(V1:220). botched replay의 고아 체결을 물리 FK가 막지 못한다.
    C11_TRADE_ORPHAN(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM trades t
            WHERE t.created_at >= now() - interval '90 days'
              AND (NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = t.maker_order_id)
                OR NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = t.taker_order_id))
            """),

    // ── 고아 이벤트(source_cmd_seq 부재) ────────────────────────────────
    // event_journal.source_cmd_seq는 FK 없는 논리 참조(V1:605)라 이 쿼리가 유일한 보증이다.
    // 명령은 이벤트보다 먼저 append되므로 정상 DB에서 0이다(SCHEDULED 안전). 전량 anti-join의
    // 무한 성장 상한으로 C09~C11과 동일한 90일 retention 윈도를 건다(§4-2 ③).
    C12_EVENT_ORPHAN(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM event_journal e
            WHERE e.created_at >= now() - interval '90 days'
              AND NOT EXISTS (SELECT 1 FROM command_journal c WHERE c.seq = e.source_cmd_seq)
            """),

    // ── NEW 명령 ↔ orders 물질화(유실·유령 주문) ─────────────────────────
    // AUTHORITATIVE_ONLY: orders 행은 append 이후 정산 sink가 물질화한다. append와 정산 커밋
    // 사이의 비동기 창을 상시 스케줄이 위반으로 오탐한다(라이브 프로젝션 지연). 큐 드레인된
    // 권위 run에서만 평가한다(복구 RTO 설계 §3-c). 역방향 C13B는 정산 이후 orders만 보므로
    // 프로젝션 지연 오탐이 없어 SCHEDULED를 유지한다.
    C13A_ORDER_MATERIALIZED(Severity.CRITICAL, Scope.AUTHORITATIVE_ONLY, """
            SELECT count(*) FROM command_journal c
            WHERE c.command_type = 'NEW'
              AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = c.assigned_order_id)
            """),
    C13B_ORDER_JOURNALED(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT count(*) FROM orders o
            WHERE o.command_seq IS NULL
               OR NOT EXISTS (SELECT 1 FROM command_journal c WHERE c.seq = o.command_seq)
            """),

    // ── LOCKED 잔고 = 열린 주문 잔여 예약 합(과다락·환급 누락) ───────────
    C14_LOCKED_RESERVATION(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM (
                SELECT b.account_id
                FROM accounts a
                JOIN balances b ON b.account_id = a.id
                LEFT JOIN orders o ON o.locked_account_id = a.id
                     AND o.status IN ('PENDING','OPEN','PARTIAL')
                WHERE a.purpose = 'LOCKED'
                GROUP BY b.account_id, b.balance
                HAVING b.balance <> COALESCE(SUM(o.locked_amount), 0)
            ) x
            """),

    // ── orders 수수료 물질화 = trades 자기측 fee leg 합(V10 프로젝션 드리프트) ──
    // paid_fee·trades_count는 정산이 filled_qty·executed_quote와 같은 UPDATE에서 누적하는
    // 프로젝션(V10). trade INSERT와 동일 정산 트랜잭션이라 async 물질화 창이 없어 SCHEDULED
    // (append 이후 물질화되는 C13A와 다르다). 자기측 fee: maker면 maker_fee, taker면 taker_fee.
    // 90일 하한은 detach된 파티션을 제외한다(C10 규약·V10 백필과 동일). 주문 생성이 체결보다
    // 앞서므로 90일 내 주문의 leg는 전부 attached 파티션에 있어 합계가 온전하다.
    C20_ORDER_FEE_MATERIALIZED(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM (
                SELECT o.id
                FROM orders o
                LEFT JOIN (
                    SELECT maker_order_id AS oid, maker_fee AS fee FROM trades
                    WHERE created_at >= now() - interval '90 days'
                    UNION ALL
                    SELECT taker_order_id AS oid, taker_fee AS fee FROM trades
                    WHERE created_at >= now() - interval '90 days'
                ) legs ON legs.oid = o.id
                WHERE o.created_at >= now() - interval '90 days'
                GROUP BY o.id, o.paid_fee, o.trades_count
                HAVING o.paid_fee <> COALESCE(SUM(legs.fee), 0)
                    OR o.trades_count <> COUNT(legs.oid)
            ) x
            """),

    // ── 재생 결정론 ─────────────────────────────────────────────────────
    C17_REPLAY_DETERMINISM(Severity.CRITICAL, Scope.SCHEDULED,
            "SELECT count(*) FROM recovery_runs WHERE matched = false"),

    // ── 체결가 = maker 지정가 ───────────────────────────────────────────
    // 90일 윈도는 §4-2 ③ 규약(C08과 동일). maker 주문 행은 조인으로만 읽어 별도 윈도 불요.
    C18_FILL_PRICE_MAKER(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT count(*) FROM trades t
            JOIN orders m ON m.id = t.maker_order_id
            WHERE t.created_at >= now() - interval '90 days'
              AND m.price IS NOT NULL AND t.price <> m.price
            """),

    // ── DEFAULT 파티션 유입 0(파티션 지평 소진 전조) ─────────────────────
    // 파생 파티션(_pYYYY_MM/_p<start>)이 정상 흡수하면 DEFAULT는 항상 비어야 한다. 유입 행은
    // 경계 소진 전조다(가용성). WARN: 무결성 위반은 아니다. 위반 행 수 = DEFAULT 유입 행 총합.
    C15_DEFAULT_PARTITION(Severity.WARN, Scope.SCHEDULED, """
            SELECT (SELECT count(*) FROM ledger_entries_default)
                 + (SELECT count(*) FROM command_journal_default)
                 + (SELECT count(*) FROM event_journal_default)
                 + (SELECT count(*) FROM trades_default)
                 + (SELECT count(*) FROM outbox_default)
            """),

    // ── RPO=0 외부 증거(실험 한정): ACK 원장 ⊆ command_journal ───────────
    C16_ACK_JOURNALED(Severity.CRITICAL, Scope.AUTHORITATIVE_ONLY, """
            SELECT count(*) FROM harness.loadtest_acks a
            LEFT JOIN command_journal c
                 ON c.user_id = a.user_id AND c.client_order_id = a.client_order_id
            WHERE c.seq IS NULL
            """),

    // ── 오더북 비교차 bestBid < bestAsk(정지 시점 한정) ──────────────────
    C19_ORDERBOOK_NONCROSS(Severity.HIGH, Scope.AUTHORITATIVE_ONLY, """
            SELECT count(*) FROM (
                SELECT market_id
                FROM orders
                WHERE status IN ('OPEN','PARTIAL') AND price IS NOT NULL
                GROUP BY market_id
                HAVING max(price) FILTER (WHERE side = 'BUY')
                       >= min(price) FILTER (WHERE side = 'SELL')
            ) x
            """),

    // ── 이벤트 방출 레지스트리 1:1(DB §6.4 ⑮, P0-4) ──────────────────────
    // event_journal ↔ event_emission_registry 양방향 anti-join. AFTER INSERT 트리거가 방출 시점
    // 정방향만 강제하므로, 트리거 실패·저널 우회 삽입으로 생기는 드리프트(유실·유령 이벤트)를
    // event_seq gapless(C06)로는 못 잡는다. 윈도는 event_journal.created_at(engine_ts 파생) 한쪽에만
    // 걸고 존재 검사측(조인 상대)은 무윈도라, registry.created_at(DEFAULT now())이 달라도 경계
    // 오탐이 구조적으로 불가능하다. 90일 윈도는 §4-2 ③(C08~C12·C18·C20과 동일 retention 규약).
    C21_EVENT_EMISSION_REGISTRY(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT (
                SELECT count(*) FROM event_journal e
                LEFT JOIN event_emission_registry r ON r.event_seq = e.event_seq
                WHERE e.created_at >= now() - interval '90 days' AND r.event_seq IS NULL
            ) + (
                SELECT count(*) FROM event_emission_registry r
                LEFT JOIN event_journal e ON e.event_seq = r.event_seq
                WHERE r.created_at >= now() - interval '90 days' AND e.event_seq IS NULL
            )
            """),

    // ── fencing_token 단조성(DB §6.4 ⑯, P0-1) ────────────────────────────
    // 리더 epoch는 seq 오름차순으로 비감소여야 한다(split-brain 부재). assert_current_leader가
    // 라이브 강제이고 이 감사는 그 백스톱이라 정지 시점 권위 run에서만 평가한다(AUTHORITATIVE_ONLY).
    C22_FENCING_MONOTONIC(Severity.CRITICAL, Scope.AUTHORITATIVE_ONLY, """
            SELECT count(*) FROM (
                SELECT seq, fencing_token,
                       lag(fencing_token) OVER (ORDER BY seq) AS prev
                FROM command_journal
            ) t
            WHERE t.prev > t.fencing_token
            """),

    // ── 채번 카운터 정합(DB §6.4 ⑰, P0-5) ────────────────────────────────
    // next_value가 저널 최대 seq 이하이면 다음 채번이 기존 seq와 충돌한다(재사용). 정상 채번은
    // 원자적이라 라이브에선 항상 max+1 이상이고, 백업 복원·수동 리시드가 카운터를 저널 아래로
    // 되돌리는 경우만 위반이다. 정지 시점 권위 run에서 평가한다(AUTHORITATIVE_ONLY).
    C23_COUNTER_SEQ_CONSISTENCY(Severity.CRITICAL, Scope.AUTHORITATIVE_ONLY, """
            SELECT count(*) FROM journal_counters c
            WHERE (c.name = 'command_seq'
                     AND c.next_value <= (SELECT COALESCE(max(seq), 0) FROM command_journal))
               OR (c.name = 'event_seq'
                     AND c.next_value <= (SELECT COALESCE(max(event_seq), 0) FROM event_journal))
            """);

    public enum Severity { CRITICAL, HIGH, WARN }

    public enum Scope { SCHEDULED, AUTHORITATIVE_ONLY }

    private final Severity severity;
    private final Scope scope;
    private final String sql;

    InvariantCheck(Severity severity, Scope scope, String sql) {
        this.severity = severity;
        this.scope = scope;
        this.sql = sql;
    }

    public Severity severity() {
        return severity;
    }

    public Scope scope() {
        return scope;
    }

    public String sql() {
        return sql;
    }
}
