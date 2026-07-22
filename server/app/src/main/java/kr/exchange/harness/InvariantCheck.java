package kr.exchange.harness;

/**
 * 불변식 검증 카탈로그 중 앱 검증기 담당 부분집합(하네스 검증설계 §4-1 C01~C19 전체가
 * 아니다). 각 SQL은 위반 행 수(BIGINT)를 단일 값으로 반환하고, ok는 count=0으로 판정한다.
 * 컬럼명은 DB 설계 정본 실제 DDL 기준.
 *
 * 미포함 체크의 담당: C03·C09~C12·C15는 pg_cron 분담(§4-2), C04는 DEFERRED CONSTRAINT
 * TRIGGER(V1:350~370)가 하드 강제한다. runScheduled/runAuthoritative는 이 enum의 values()만
 * 순회하므로 여기 없는 체크는 앱 run에서 평가하지 않는다.
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
    C08_DUP_FILL(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT (
                SELECT count(*) FROM (
                    SELECT id FROM trades GROUP BY id HAVING count(*) > 1
                ) dup
            ) + (
                SELECT count(*) FROM trades t
                LEFT JOIN trade_seq_registry r ON r.seq = t.id
                WHERE r.seq IS NULL
            )
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

    // ── 재생 결정론 ─────────────────────────────────────────────────────
    C17_REPLAY_DETERMINISM(Severity.CRITICAL, Scope.SCHEDULED,
            "SELECT count(*) FROM recovery_runs WHERE matched = false"),

    // ── 체결가 = maker 지정가 ───────────────────────────────────────────
    C18_FILL_PRICE_MAKER(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT count(*) FROM trades t
            JOIN orders m ON m.id = t.maker_order_id
            WHERE m.price IS NOT NULL AND t.price <> m.price
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
