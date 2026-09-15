package kr.exchange.harness;

/**
 * 불변식 검증 카탈로그의 앱 검증기 배선(하네스 검증설계 §4-1). 각 SQL은 위반 행 수를 단일 값으로
 * 반환하고 ok는 count=0이다. 문서상 pg_cron 분담인 체크도 여기 등록한다. 카오스 스택과
 * Testcontainers에 pg_cron이 없어, 빠지면 고아 체결·미저널 체결이 페일오버 판정을 그대로 통과한다.
 * runScheduled/runAuthoritative는 values()만 순회하므로 여기 없는 체크는 앱 run이 평가하지 않는다
 * (C04만 예외다. DEFERRED CONSTRAINT TRIGGER가 하드 강제한다). 체크별 근거는 §4-1·§4-2와
 * DB 설계 §6.4에 둔다.
 *
 * Scope:
 *   SCHEDULED          상시 스케줄 + 권위 run 모두에서 평가.
 *   AUTHORITATIVE_ONLY 정지 시점·실험 사후 권위 run에서만 평가. 상시 스케줄에서 빼는 이유는
 *                      라이브 프로젝션 지연과 스테일 loadtest_acks가 오탐을 만들기 때문이다.
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
    // 레거시 구간(seq < band_span)은 전역 조밀, 대역 구간은 마켓 안에서만 조밀하다(ADR 0022 6-1).
    // 대역 명세 행이 없으면 두 항이 대상 없이 0 을 내므로 그 부재를 위반 1 로 센다.
    C05_CMD_GAPLESS(Severity.CRITICAL, Scope.SCHEDULED, """
            WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
            SELECT (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM s)
                 + (SELECT COALESCE((max(c.seq) - min(c.seq) + 1) - count(*), 0)
                    FROM command_journal c
                    WHERE c.seq < (SELECT band_span FROM s))
                 + (SELECT COALESCE(sum(b.gap), 0) FROM (
                        SELECT (max(c.seq) - min(c.seq) + 1) - count(*) AS gap
                        FROM command_journal c
                        WHERE c.seq >= (SELECT band_span FROM s)
                        GROUP BY c.market_id
                    ) b)
            """),
    // V36 에서 축이 바뀌었다. 종전에는 event_seq 전역 gapless 였다. 이제 event_seq 는
    // marketId * band_span + marketSeq 파생이라 전역으로 보면 대역 사이가 통째로 비어 있고,
    // 그 빈 자리는 결번이 아니라 대역 경계다. 결번 없음이 실제로 요구되는 축은 마켓
    // 안이다. 재생이 그 마켓의 이벤트를 순서대로 대조하기 때문이다. 그래서 마켓별로
    // 세고 합친다. market_seq 가 비어 있는 V36 이전 행은 이 검사 밖이다.
    C06_EVT_GAPLESS(Severity.CRITICAL, Scope.SCHEDULED, """
            SELECT COALESCE(sum(gap), 0) FROM (
                SELECT (max(market_seq) - min(market_seq) + 1) - count(*) AS gap
                FROM event_journal
                WHERE market_seq IS NOT NULL
                GROUP BY market_id
            ) g
            """),

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
    // 경계 소진 전조다(가용성). WARN: 무결성 위반은 아니다. command_journal_default 는 C27 이 본다.
    C15_DEFAULT_PARTITION(Severity.WARN, Scope.SCHEDULED, """
            SELECT (SELECT count(*) FROM ledger_entries_default)
                 + (SELECT count(*) FROM event_journal_default)
                 + (SELECT count(*) FROM trades_default)
                 + (SELECT count(*) FROM outbox_default)
            """),

    // ── 명령 대역 여유(ADR 0022 6-4) ────────────────────────────────────
    // 소진은 되돌릴 수 없어 잔여가 대역의 10퍼센트 아래인 마켓을 미리 센다. 10^12 대역에서는 사실상 공허하고 실제 방어선은
    // 채번 함수의 대역 상한 예외다. 뷰는 V45 이고 명세 부재는 C05·C23 이 센다.
    C26_CMD_BAND_HEADROOM(Severity.HIGH, Scope.SCHEDULED, """
            SELECT count(*) FROM v_journal_band_headroom
            WHERE name = 'command_seq' AND remaining_ratio < 0.1
            """),

    // ── 명령 저널 DEFAULT 유입 0(ADR 0022 6-4) ──────────────────────────
    // DEFAULT 행은 어느 잎에도 속하지 않는 seq 로 들어간 명령이고 행이 든 구간은 잎을 다시 만들 수 없다(V40).
    // C15 가 여러 표를 합산하는 WARN 이라 명령 축만 떼어 CRITICAL 로 세운다. 행은 부모 표로 읽혀 원천 손상이 아니라 잎 경로 이상이다(검증설계 §4-1 C27, §5-3 R5).
    C27_CMD_DEFAULT_PARTITION(Severity.CRITICAL, Scope.SCHEDULED,
            "SELECT count(*) FROM command_journal_default"),

    // ── 커트오버 뒤 레거시 채번 0(ADR 0022 7절 6단계 섞임 감사) ────────────
    // 마켓 안 첫 대역 행보다 늦게 접수된 레거시 행은 그 마켓 커서 아래로 버려진다. latched_at 과 비교하지 않는다.
    // 래치 커밋 전에 시작한 정당한 전역 행이 latched_at 보다 늦을 수 있다. 명세 부재는 C05·C23 이 센다.
    C28_CMD_LEGACY_AFTER_CUTOVER(Severity.CRITICAL, Scope.SCHEDULED, """
            WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'),
                 b AS (SELECT c.market_id, min(c.created_at) AS first_band_at
                       FROM command_journal c, s
                       WHERE c.seq >= s.band_span
                       GROUP BY c.market_id)
            SELECT count(*)
            FROM command_journal c
            JOIN b ON b.market_id = c.market_id
            CROSS JOIN s
            WHERE c.seq < s.band_span
              AND c.created_at > b.first_band_at
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
    // assert_current_leader 가 라이브 강제이고 이 감사는 백스톱이라 권위 run 에서만 돈다. 시간 축 팔은
    // 마켓 사이까지, 마켓 안 팔은 시계 역행이 시간 축에서 가린 마켓 안 역행을 본다(ADR 0022 6-2, 9-17).
    C22_FENCING_MONOTONIC(Severity.CRITICAL, Scope.AUTHORITATIVE_ONLY, """
            SELECT (
                SELECT count(*) FROM (
                    SELECT fencing_token,
                           lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev
                    FROM command_journal
                ) t
                WHERE t.prev > t.fencing_token
            ) + (
                SELECT count(*) FROM (
                    SELECT fencing_token,
                           lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev
                    FROM command_journal
                ) t
                WHERE t.prev > t.fencing_token
            )
            """),

    // ── 채번 카운터 정합(DB §6.4 ⑰, P0-5) ────────────────────────────────
    // next_value 가 그 축의 저널 최대값 이하이면 다음 채번이 기존 값과 충돌한다. 명령 축은 레거시 팔과
    // 마켓별 대역 팔, 이벤트 축은 마켓별 market_seq 팔이고 명세 부재는 위반 1 이다(ADR 0022 6-3).
    C23_COUNTER_SEQ_CONSISTENCY(Severity.CRITICAL, Scope.AUTHORITATIVE_ONLY, """
            WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
            SELECT (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM s)
                 + (SELECT count(*) FROM journal_counters c
                    WHERE c.name = 'command_seq'
                      AND c.next_value <= (SELECT COALESCE(max(j.seq), 0)
                                           FROM command_journal j
                                           WHERE j.seq < (SELECT band_span FROM s)))
                 + (SELECT count(*) FROM market_journal_counters m
                    WHERE m.name = 'command_seq'
                      AND m.next_value <= (SELECT COALESCE(max(j.seq - j.market_id * (SELECT band_span FROM s)), 0)
                                           FROM command_journal j
                                           WHERE j.market_id = m.market_id
                                             AND j.seq >= (SELECT band_span FROM s)))
                 + (SELECT count(*) FROM market_journal_counters m
                    WHERE m.name = 'event_seq'
                      AND m.next_value <= (SELECT COALESCE(max(e.market_seq), 0)
                                           FROM event_journal e
                                           WHERE e.market_id = m.market_id
                                             AND e.market_seq IS NOT NULL))
            """),

    // ── 원가 프로젝션 정합(DB §6.4 ⑱⑲, V13) ──────────────────────────────
    // 술어는 V13이 만든 뷰가 정본이다. SQL 사본을 두면 라이브 산식과 감사 산식이 두 벌이 되어
    // 갈린다. 여기서는 위반 행 수만 센다.
    //
    // WARN인 이유: position_cost는 계층 ③ 파생 프로젝션이라 위반해도 자금·원장은 무손상이고
    // 조치가 rebuild_position_cost() 재구축이다. CRITICAL로 올리면 §4-3 자동 조치인 거래
    // 정지까지 유발해 카오스 판정이 자금 무결성과 표시 정합을 같은 등급으로 뭉갠다.
    //
    // AUTHORITATIVE_ONLY인 이유: 주입 창에서는 재구축이 아직 안 돈 시점이라 위반이 장애가
    // 아니라 미반영을 뜻한다. R5·실험 사후 권위 run이 유효 평가점이다(하네스 검증설계 §4-2 ④).
    //
    // C24는 항등(cost_qty + unknown_qty = available + locked)이다. 초과는 처분 차감 누락,
    // 미달은 취득 누산 누락이며 어느 방향이든 위반이다. 단측 부등호로 읽으면 한 방향을
    // 정상으로 오해석해 실제 위반을 산식 밖으로 흘린다.
    C24_POSITION_COST_QTY(Severity.WARN, Scope.AUTHORITATIVE_ONLY,
            "SELECT count(*) FROM position_cost_qty_check"),
    // C25는 누산기 부호·쌍·워터마크·원가 통화 라벨 5종을 뷰가 분류한다. 테이블 CHECK로 이미
    // 막히는 항목까지 두는 것은 SECURITY DEFINER 우회·수퍼유저 직접 DML의 회귀 안전망이다.
    C25_POSITION_COST_SANITY(Severity.WARN, Scope.AUTHORITATIVE_ONLY,
            "SELECT count(*) FROM position_cost_sanity_check");

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
