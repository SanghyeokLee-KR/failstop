-- ============================================================================
-- V13__position_cost.sql
-- 원천: 원가추적 실행계획 A안(원장 트랜잭션 순델타 파생 원가 프로젝션).
-- 목적: AccountController 의 avg_buy_price 고정 "0" 스텁을 실측 원가로 대체할 계층3 파생
--       프로젝션. 평단가를 저장하지 않고 (cost_qty, cost_amount) 누산기 쌍만 적재해
--       취득 경로를 덧셈만으로 만든다(반올림 0). 평단가는 조회 시 산출한다.
-- 범위: position_cost, apply_position_cost, rebuild_position_cost,
--       post_ledger_transaction 재정의(말미 1회 호출), 검증 뷰(C24·C25), 권한, 백필.
--
-- 계층 논증: position_cost 는 엔진 상태가 아니다. SnapshotCodec.encode·stateChecksum 입력에
--   없고 event_journal payload 바이트를 바꾸지 않아 재생 결정론(C17)과 무관하다. 게시 함수
--   내부 호출이라 원장·잔고와 항상 같은 트랜잭션이며 크래시 창이 성립하지 않는다.
--
-- 산식 단일화: 라이브 게시와 재구축이 같은 apply_position_cost 를 호출한다. 산식을 두 벌
--   만들지 않으므로 드리프트가 구조적으로 불가능하다. 재구축은 ledger_transactions.id
--   오름차순으로 적용하고, 라이브는 커밋 순서로 적용한다. 두 순서가 같아야 결과가 같다.
--   원장 게시자는 엔진(SettlementService) 하나가 아니다. MemberAuthService·DemoUserProvisioner
--   ·AccountBootstrap·BotSeeder 도 post_ledger_transaction 을 직접 호출하며, id 는 IDENTITY
--   채번 시점 값이라 커밋 순서와 일치할 의무가 없다. 두 순서가 갈라지려면 비엔진 게시자와
--   정산이 같은 (user, 비quote 자산)을 동시에 건드려야 한다. 현재 그런 경로가 없다: 가입은
--   KRW 단독(무동작)이고 시드·프로비저닝은 해당 사용자의 첫 자금 발급이라 정산과 겹치지
--   않는다. 비quote 자산을 움직이는 비엔진 게시 경로를 새로 만들면 정산과 직렬화해야 한다.
--
-- 운영 규모 caveat: 말미 백필은 ledger_transactions 전량을 1패스 순회한다. 데모 규모(수천 행)
--   에서는 수 초지만, 원장 이력이 큰 인스턴스는 이 마이그레이션에서 백필 구문만 제거해
--   테이블·함수만 만들고 백필을 별도 1회 실행으로 분리해야 부팅 지연을 피한다. 백필 전까지
--   평단가가 비는 것은 무해한 열화다(원가 미상 표기).
--   분리 실행의 조건: rebuild_position_cost 는 정산이 멈춘 창에서 함수 소유자 롤이 psql 로
--   직접 돌린다. app_role 에는 EXECUTE 를 주지 않는다. 리셋 UPDATE 가 대상 행을 재생이
--   끝날 때까지 잠그므로, 정산이 살아 있으면 라이브 apply 가 lock_timeout 을 맞고 그 게시가
--   롤백된다(무음 유실이 아니라 큰 소리로 실패한다. 아래 no-RAISE 계약의 범위 참조).
-- ============================================================================
-- 백필이 원장 전량을 순회해 데이터량에 비례한다. prod Hikari 풀의 connection-init-sql 이 심은
-- statement_timeout=3s 를 Flyway 가 상속하면 적재 DB 에서 57014 로 취소돼 부팅이 막힌다.
-- SET LOCAL 로 이 트랜잭션에 한해 상한을 해제한다(트랜잭션 종료 시 원복, 풀 무영향).
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;
SET LOCAL timezone = 'UTC';

-- ----------------------------------------------------------------
-- 1. 원가 통화 집합
-- ----------------------------------------------------------------
-- 원가를 표시하는 통화(quote) 판정의 단일 정의. 함수와 검증 뷰가 같은 정의를 본다.
-- markets 에 등재된 quote 자산이 정본이고, FIAT 를 합집합으로 더한다. 마켓 등재보다
-- 먼저 게시되는 시드 GENESIS 에서도 KRW 가 quote 로 분류돼야 원가 통화 자신에 대한
-- 무의미한 원가 행이 생기지 않는다.
-- 이 집합은 마켓 등재로 늘어난다. 기존 보유 자산을 새 마켓의 quote 로 등재하면 그 자산의
-- 원가 행이 갱신 대상에서 빠지고 C24·C25 가 상시 위반으로 바뀐다. 파괴적 변경이라
-- 마이그레이션과 원가 행 정리를 같이 해야 한다. v1 은 quote 가 KRW 단일이라 무영향.
CREATE VIEW cost_quote_assets AS
SELECT quote_asset_id AS asset_id FROM markets
UNION
SELECT id FROM assets WHERE kind = 'FIAT';

-- ----------------------------------------------------------------
-- 2. 프로젝션 테이블
-- ----------------------------------------------------------------
-- 평단가 컬럼이 없는 것이 설계의 핵심이다. 평단가를 저장하면 취득마다 나눗셈 반올림이
-- 누적되고 부분 처분이 그 오차를 확대한다. 누산기 쌍만 두면 취득은 덧셈뿐이다.
-- unknown_qty 는 원가 미상 버킷이다. 시드·에어드롭·입금 취득분은 원가 0 이 아니라
-- 원가 미상이며, 이 둘을 섞으면 전액 수익으로 오표시된다.
CREATE TABLE position_cost (
    user_id          BIGINT   NOT NULL REFERENCES users(id),
    asset_id         SMALLINT NOT NULL REFERENCES assets(id),
    -- cost_amount 의 표시 통화 라벨. v1 은 quote 가 KRW 단일이라는 전제 위에 있다.
    -- 누산기는 통화 구분 없이 더해지고 이 컬럼은 최근 관측값으로 덮이므로, 한 자산이
    -- 두 통화로 거래되면 합계는 혼합이고 라벨은 최신값이다. 다중 quote 확장은 이 컬럼을
    -- PK 에 넣어 통화별 행을 분리해야 하며 라벨 추가만으로는 성립하지 않는다.
    quote_asset_id   SMALLINT NOT NULL REFERENCES assets(id),
    cost_qty         NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (cost_qty >= 0),
    cost_amount      NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (cost_amount >= 0),
    unknown_qty      NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (unknown_qty >= 0),
    -- 실현손익은 부호를 허용한다(손실 계상).
    realized_pnl     NUMERIC(40,18) NOT NULL DEFAULT 0,
    unknown_proceeds NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (unknown_proceeds >= 0),
    -- 행별 멱등 워터마크. 이미 반영한 트랜잭션의 재적용을 무동작으로 만든다.
    last_txn_id      BIGINT   NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, asset_id),
    -- 한쪽만 0 이면 평단가가 0 또는 무한이 된다. 조회 산식의 전제를 DB 가 강제한다.
    CONSTRAINT chk_cost_pair CHECK ((cost_qty = 0) = (cost_amount = 0))
);
COMMENT ON TABLE position_cost IS
    '원장 순델타 파생 원가 프로젝션(계층3). 평단가 = cost_amount / cost_qty (cost_qty > 0 일 때만)';

-- ----------------------------------------------------------------
-- 3. 적용 함수
-- ----------------------------------------------------------------
-- post_ledger_transaction 말미에서 잔고 갱신·봉인 직후 1회 호출된다.
--
-- no-RAISE 계약(절대): 이 함수는 어떤 입력에도 예외를 던지지 않는다. 예외가 나면 게시
--   트랜잭션이 통째로 롤백되고 SettlementService 계약대로 엔진이 fail-fast 로 선다. 재기동
--   catch-up 이 같은 체결을 재정산하면 같은 예외가 재발해 크래시 루프가 된다. 파생 프로젝션
--   하나 때문에 원장을 세우는 것은 정확성 우선순위의 역전이다. 따라서
--     * 형태 이탈(비quote 자산이 여럿, quote 부호가 규칙과 다름)은 원가 미상으로 강등한다.
--     * 음수·CHECK 위반 위험은 전부 클램프한다.
--     * 그럼에도 남는 데이터 계열 실패는 행 단위 예외 블록이 삼키고 미갱신으로 남긴다.
--   누락·왜곡은 사후에 불변식 C24·C25 가 적발하고 rebuild_position_cost 가 교정한다.
--
--   계약의 범위는 "입력"이다. 삼키는 대상은 data_exception(22)·integrity_constraint_violation(23)
--   뿐이고, 잠금·취소·데드락(55P03·57014·40P01·40001)은 그대로 올린다. 이유가 셋이다.
--     * 이들은 입력 함수가 아니라 환경 함수다. 재시도하면 결과가 달라지므로 크래시 루프의
--       전제(같은 입력 = 같은 예외)가 성립하지 않는다.
--     * 삼키면 rebuild 와 라이브가 겹칠 때 그 델타가 무음으로 영구 유실된다. 원장은 커밋되고
--       프로젝션만 빠지는데, 재구축 중이라 재구축 목록에도 없다. 큰 소리로 실패해야 한다.
--     * 57014 를 삼키면 statement_timeout 이 그 문장에서 재무장하지 않아 게시의 잔여 구간이
--       상한 없이 진행된다. 게이트웨이의 3s 상한·503 변환 계약(application.yml)이 깨진다.
--   드라이빙 SELECT 는 블록 밖이다. 입력으로는 실패하지 않고, 환경 실패는 어차피 올려야 한다.
--
-- 판정은 사용자별 자산 순델타 벡터 하나로 한다. 경로별 분기를 만들지 않는다.
--   1) 비base 0 개              -> 무동작. KRW 단독 GENESIS(가입·데모 프로비저닝)가 여기다.
--   2) dBase > 0, dQuote < 0    -> 원가 확보 취득. cost_qty += dBase, cost_amount += -dQuote.
--   3) dBase > 0, dQuote >= 0   -> 원가 미상 취득. unknown_qty += dBase.
--   4) dBase < 0                -> 처분. 원가 확보분 우선 소진(known-first).
--   5) dBase = 0                -> 순델타 집계의 HAVING 이 배제한다.
-- RESERVE·RELEASE 는 동일 사용자·동일 자산의 AVAILABLE<->LOCKED 페어라 순델타가 0 이 되어
-- 자동으로 걸러진다. 매수 수수료도 순델타에 이미 반영돼 있다(실수취 base = qty - fee,
-- 실지출 quote = quoteOut - refund). 별도 항을 만들지 않는다.
--
-- 잠금 순서: post_ledger_transaction 이 balances 를 account_id 오름차순으로 잠근 뒤 이 함수가
-- position_cost 를 (user_id, asset_id) 오름차순으로 잠근다. 전역 순서가 balances -> position_cost
-- 로 고정돼 데드락이 성립하지 않는다.
CREATE OR REPLACE FUNCTION apply_position_cost(p_txn_id BIGINT) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    r                RECORD;
    v_cost_qty       NUMERIC;
    v_cost_amount    NUMERIC;
    v_unknown_qty    NUMERIC;
    v_realized       NUMERIC;
    v_unknown_proc   NUMERIC;
    v_last           BIGINT;
    v_row_quote      SMALLINT;
    v_quote_asset    SMALLINT;
    v_out            NUMERIC;
    v_known          NUMERIC;
    v_rest           NUMERIC;
    v_relief         NUMERIC;
    v_proceeds       NUMERIC;
    v_known_proceeds NUMERIC;
BEGIN
    -- 핫패스 조기 종료: 이 드라이빙 쿼리가 0 행이면 루프 본문이 아예 실행되지 않는다.
    -- RESERVE·RELEASE·KRW 단독 GENESIS 가 전부 여기서 끝난다(행 잠금·서브트랜잭션 없음).
    FOR r IN
        WITH d AS (
            SELECT a.owner_id AS user_id, le.asset_id, SUM(le.amount) AS delta
            FROM ledger_entries le
            JOIN accounts a ON a.id = le.account_id
            WHERE le.transaction_id = p_txn_id
              AND a.owner_type = 'USER'
            GROUP BY a.owner_id, le.asset_id
            HAVING SUM(le.amount) <> 0
        ),
        s AS (
            SELECT d.user_id,
                   count(*) FILTER (WHERE q.asset_id IS NULL)     AS base_cnt,
                   count(*) FILTER (WHERE q.asset_id IS NOT NULL) AS quote_cnt,
                   -- quote_cnt = 1 일 때만 소비하므로 min 은 그 유일 값이다.
                   min(d.delta)    FILTER (WHERE q.asset_id IS NOT NULL) AS quote_delta,
                   min(d.asset_id) FILTER (WHERE q.asset_id IS NOT NULL) AS quote_asset
            FROM d LEFT JOIN cost_quote_assets q ON q.asset_id = d.asset_id
            GROUP BY d.user_id
        )
        SELECT d.user_id, d.asset_id, d.delta,
               -- base 와 quote 가 정확히 하나씩일 때만 quote 를 원가로 귀속한다. 그 밖의
               -- 형태는 귀속 불가로 보고 0 을 넘겨 원가 미상 경로로 강등한다.
               CASE WHEN s.base_cnt = 1 AND s.quote_cnt = 1
                    THEN s.quote_delta ELSE 0 END AS quote_delta,
               CASE WHEN s.base_cnt = 1 AND s.quote_cnt = 1
                    THEN s.quote_asset ELSE NULL END AS quote_asset
        FROM d
        JOIN s ON s.user_id = d.user_id
        LEFT JOIN cost_quote_assets q ON q.asset_id = d.asset_id
        WHERE q.asset_id IS NULL
        ORDER BY d.user_id, d.asset_id
    LOOP
        BEGIN
            SELECT pc.cost_qty, pc.cost_amount, pc.unknown_qty, pc.realized_pnl,
                   pc.unknown_proceeds, pc.last_txn_id, pc.quote_asset_id
            INTO v_cost_qty, v_cost_amount, v_unknown_qty, v_realized,
                 v_unknown_proc, v_last, v_row_quote
            FROM position_cost pc
            WHERE pc.user_id = r.user_id AND pc.asset_id = r.asset_id
            FOR UPDATE;

            IF NOT FOUND THEN
                v_cost_qty := 0; v_cost_amount := 0; v_unknown_qty := 0;
                v_realized := 0; v_unknown_proc := 0; v_last := 0; v_row_quote := NULL;
            END IF;

            IF p_txn_id > v_last THEN
                v_quote_asset := COALESCE(
                    r.quote_asset,
                    v_row_quote,
                    (SELECT m.quote_asset_id FROM markets m
                      WHERE m.base_asset_id = r.asset_id ORDER BY m.id LIMIT 1),
                    (SELECT a2.id FROM assets a2 WHERE a2.kind = 'FIAT' ORDER BY a2.id LIMIT 1),
                    r.asset_id);

                IF r.delta > 0 THEN
                    IF r.quote_delta < 0 THEN
                        v_cost_qty    := v_cost_qty + r.delta;
                        v_cost_amount := v_cost_amount + (-r.quote_delta);
                    ELSE
                        v_unknown_qty := v_unknown_qty + r.delta;
                    END IF;
                ELSE
                    v_out   := -r.delta;
                    v_known := least(v_out, v_cost_qty);
                    v_rest  := v_out - v_known;
                    IF v_known = 0 THEN
                        v_relief := 0;
                    ELSIF v_known = v_cost_qty THEN
                        -- 전량 스윕: 나눗셈 없이 잔여 원가 전액을 덜어낸다. cost_qty 와
                        -- cost_amount 가 동시에 0 이 되어 chk_cost_pair 가 유지된다.
                        v_relief := v_cost_amount;
                    ELSE
                        -- DOWN 절사 + 클램프. round 를 쓰면 known < cost_qty 인데도 relief 가
                        -- cost_amount 와 같아져 cost_amount=0·cost_qty>0 이 되고 chk_cost_pair
                        -- 위반이 원장 트랜잭션을 롤백시킨다.
                        v_relief := least(trunc(v_cost_amount * v_known / v_cost_qty, 18),
                                          v_cost_amount);
                    END IF;

                    v_proceeds := CASE WHEN r.quote_delta > 0 THEN r.quote_delta ELSE 0 END;
                    IF v_rest = 0 THEN
                        v_known_proceeds := v_proceeds;
                    ELSIF v_known = 0 THEN
                        v_known_proceeds := 0;
                    ELSE
                        v_known_proceeds := least(trunc(v_proceeds * v_known / v_out, 18),
                                                  v_proceeds);
                    END IF;

                    v_cost_qty     := v_cost_qty - v_known;
                    v_cost_amount  := v_cost_amount - v_relief;
                    v_unknown_qty  := greatest(v_unknown_qty - v_rest, 0);
                    v_realized     := v_realized + v_known_proceeds - v_relief;
                    v_unknown_proc := v_unknown_proc + (v_proceeds - v_known_proceeds);
                END IF;

                -- CHECK 방어. 정상 경로에서는 전부 항등이다. 형태 이탈로 한쪽만 0 이 되면
                -- 잔여 수량을 원가 미상으로 강등한다(예외를 던지는 것보다 정직하다).
                v_cost_qty     := greatest(v_cost_qty, 0);
                v_cost_amount  := greatest(v_cost_amount, 0);
                v_unknown_qty  := greatest(v_unknown_qty, 0);
                v_unknown_proc := greatest(v_unknown_proc, 0);
                IF v_cost_qty = 0 OR v_cost_amount = 0 THEN
                    v_unknown_qty := v_unknown_qty + v_cost_qty;
                    v_cost_qty    := 0;
                    v_cost_amount := 0;
                END IF;

                INSERT INTO position_cost (user_id, asset_id, quote_asset_id, cost_qty,
                                           cost_amount, unknown_qty, realized_pnl,
                                           unknown_proceeds, last_txn_id, updated_at)
                VALUES (r.user_id, r.asset_id, v_quote_asset, v_cost_qty, v_cost_amount,
                        v_unknown_qty, v_realized, v_unknown_proc, p_txn_id, now())
                ON CONFLICT (user_id, asset_id) DO UPDATE
                SET quote_asset_id   = EXCLUDED.quote_asset_id,
                    cost_qty         = EXCLUDED.cost_qty,
                    cost_amount      = EXCLUDED.cost_amount,
                    unknown_qty      = EXCLUDED.unknown_qty,
                    realized_pnl     = EXCLUDED.realized_pnl,
                    unknown_proceeds = EXCLUDED.unknown_proceeds,
                    last_txn_id      = EXCLUDED.last_txn_id,
                    updated_at       = EXCLUDED.updated_at;
            END IF;
        EXCEPTION WHEN data_exception OR integrity_constraint_violation THEN
            -- no-RAISE 계약의 최종 방어선(입력 계열 한정). 이 행은 미갱신으로 남고
            -- C24·C25 가 사후 적발한다. 원장·잔고는 상위 트랜잭션에 그대로 커밋된다.
            -- 잠금·취소·데드락은 여기서 잡지 않는다. 함수 주석의 범위 논증 참조.
            NULL;
        END;
    END LOOP;
END $$;

-- ----------------------------------------------------------------
-- 4. 재구축 함수
-- ----------------------------------------------------------------
-- 대상 행을 0 으로 리셋한 뒤 attach 된 ledger_entries 를 ledger_transactions.id 오름차순으로
-- 순회하며 라이브와 같은 apply_position_cost 를 호출한다. 산식이 한 벌이므로 재구축 결과와
-- 라이브 결과가 비트동일해야 하고, 그 동일성이 통합테스트의 병합 조건이다.
--
-- p_user_id 지정 시 다른 사용자 행은 워터마크가 막는다. 그 사용자를 건드린 트랜잭션은 모두
-- id <= last_txn_id 이므로 재적용이 전부 스킵된다.
--
-- 실행 조건: 정산이 멈춘 창에서 소유자 롤이 돌린다. 아래 리셋 UPDATE 가 대상 행을 재생이
-- 끝날 때까지 잠그므로 라이브 게시와 겹치면 그 게시가 lock_timeout 으로 롤백된다.
-- 교정 범위는 누산기·워터마크·원가 통화 라벨이다. 보유 수량이 0 이 된 행은 남는다(해가 없다).
CREATE OR REPLACE FUNCTION rebuild_position_cost(p_user_id BIGINT DEFAULT NULL) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_txn BIGINT;
    v_n   BIGINT := 0;
BEGIN
    -- quote_asset_id 도 폴백값으로 되돌린다. 남겨두면 apply 의 COALESCE 2순위가 손상된 값을
    -- 그대로 물려받아 재구축으로 복구되지 않는다. 실취득이 있는 자산은 재생이 관측값으로 덮는다.
    UPDATE position_cost pc
    SET cost_qty = 0, cost_amount = 0, unknown_qty = 0, realized_pnl = 0,
        unknown_proceeds = 0, last_txn_id = 0, updated_at = now(),
        quote_asset_id = COALESCE(
            (SELECT m.quote_asset_id FROM markets m
              WHERE m.base_asset_id = pc.asset_id ORDER BY m.id LIMIT 1),
            (SELECT a2.id FROM assets a2 WHERE a2.kind = 'FIAT' ORDER BY a2.id LIMIT 1),
            pc.asset_id)
    WHERE p_user_id IS NULL OR pc.user_id = p_user_id;

    FOR v_txn IN
        SELECT t.id FROM ledger_transactions t
        WHERE p_user_id IS NULL
           OR EXISTS (SELECT 1
                      FROM ledger_entries le
                      JOIN accounts a ON a.id = le.account_id
                      WHERE le.transaction_id = t.id
                        AND a.owner_type = 'USER' AND a.owner_id = p_user_id)
        ORDER BY t.id
    LOOP
        PERFORM apply_position_cost(v_txn);
        v_n := v_n + 1;
    END LOOP;

    -- 말미 정합화. 파티션 detach 로 취득 이력이 잘린 구간은 재생만으로 보유 수량을 채우지
    -- 못한다. 남는 수량을 원가 미상으로 강등한다. 원가 0 으로 계상하면 전액 수익으로
    -- 오표시되므로 미상 강등이 정직하다. 이력이 온전하면 이 두 구문은 항등이다.
    UPDATE position_cost pc
    SET unknown_qty = greatest(COALESCE(ub.available + ub.locked, 0) - pc.cost_qty, 0),
        updated_at = now()
    FROM (SELECT user_id, asset_id, available, locked FROM user_balances) ub
    WHERE ub.user_id = pc.user_id AND ub.asset_id = pc.asset_id
      AND (p_user_id IS NULL OR pc.user_id = p_user_id)
      AND pc.unknown_qty <> greatest(COALESCE(ub.available + ub.locked, 0) - pc.cost_qty, 0);

    INSERT INTO position_cost (user_id, asset_id, quote_asset_id, unknown_qty, last_txn_id)
    SELECT ub.user_id, ub.asset_id,
           COALESCE((SELECT m.quote_asset_id FROM markets m
                      WHERE m.base_asset_id = ub.asset_id ORDER BY m.id LIMIT 1),
                    (SELECT a2.id FROM assets a2 WHERE a2.kind = 'FIAT' ORDER BY a2.id LIMIT 1),
                    ub.asset_id),
           ub.available + ub.locked,
           -- 이 행은 재생으로 만들어지지 않았다. 워터마크를 0 으로 두면 과거 txn 재적용이
           -- 이중 계상이 된다. 현재 워터마크를 박아 재적용을 무동작으로 만든다.
           COALESCE((SELECT max(id) FROM ledger_transactions), 0)
    FROM user_balances ub
    WHERE (p_user_id IS NULL OR ub.user_id = p_user_id)
      AND ub.available + ub.locked > 0
      AND NOT EXISTS (SELECT 1 FROM cost_quote_assets q WHERE q.asset_id = ub.asset_id)
    ON CONFLICT (user_id, asset_id) DO NOTHING;

    RETURN v_n;
END $$;

-- ----------------------------------------------------------------
-- 5. 게시 함수 재정의 (V1 4b 의 본문 + 말미 1회 호출)
-- ----------------------------------------------------------------
-- 적용된 마이그레이션 파일은 checksum 불변이라 V1 을 고치지 않고 여기서 재정의한다.
-- 본문은 V1 과 동일하고 RETURN 직전 apply_position_cost 호출 한 줄만 늘었다.
CREATE OR REPLACE FUNCTION post_ledger_transaction(
    p_business_event_type VARCHAR,
    p_business_event_id   BIGINT,
    p_entries             JSONB
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_txn      BIGINT;
    v_expected INT;
    v_inserted INT;
    r          RECORD;
BEGIN
    IF p_entries IS NULL OR jsonb_typeof(p_entries) <> 'array'
       OR jsonb_array_length(p_entries) = 0 THEN
        RAISE EXCEPTION '빈 거래 게시 금지: entries가 비어 있음(event=% %)',
            p_business_event_type, p_business_event_id;
    END IF;
    v_expected := jsonb_array_length(p_entries);

    -- 멱등 키 UNIQUE가 이중 게시를 23505로 차단한다.
    INSERT INTO ledger_transactions (business_event_type, business_event_id)
    VALUES (p_business_event_type, p_business_event_id)
    RETURNING id INTO v_txn;

    -- 헤더 잠금. 자체 생성 행이라 이미 배타적이지만 게시 경로의 잠금 패턴을 고정한다.
    PERFORM 1 FROM ledger_transactions WHERE id = v_txn FOR UPDATE;

    INSERT INTO ledger_entries (transaction_id, account_id, asset_id, amount, entry_type)
    SELECT v_txn, e.account_id, e.asset_id, e.amount, e.entry_type
    FROM jsonb_to_recordset(p_entries)
         AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR);
    GET DIAGNOSTICS v_inserted = ROW_COUNT;
    IF v_inserted <> v_expected THEN
        RAISE EXCEPTION 'entry 수 불일치: 기대 % 실제 % (txn=%)', v_expected, v_inserted, v_txn;
    END IF;

    -- 양자화 검증(P0-3): 자산 scale을 넘는 소수는 게시 자체를 거부한다.
    IF EXISTS (
        SELECT 1 FROM ledger_entries le JOIN assets a ON a.id = le.asset_id
        WHERE le.transaction_id = v_txn
          AND le.amount <> round(le.amount, a.scale::int)
    ) THEN
        RAISE EXCEPTION '양자화 위반: 자산 scale 초과 소수 존재(txn=%)', v_txn;
    END IF;

    -- 잔고 갱신. account_id 오름차순 UPDATE로 행 배타 잠금을 항상 같은 순서로 획득한다.
    -- balances CHECK(>=0)가 이중지출/과다락을 커밋 전에 차단한다.
    FOR r IN
        SELECT le.account_id, SUM(le.amount) AS delta
        FROM ledger_entries le
        WHERE le.transaction_id = v_txn
        GROUP BY le.account_id
        ORDER BY le.account_id
    LOOP
        UPDATE balances
        SET balance = balance + r.delta, version = version + 1
        WHERE account_id = r.account_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'balances 행 없음: account=% (txn=%)', r.account_id, v_txn;
        END IF;
    END LOOP;

    -- 자산별 합=0 최종 검증(자산 보존)
    IF EXISTS (
        SELECT 1 FROM ledger_entries
        WHERE transaction_id = v_txn
        GROUP BY asset_id HAVING SUM(amount) <> 0
    ) THEN
        RAISE EXCEPTION '원장 불균형: txn=% 자산별 합이 0이 아님(자산 보존 위반)', v_txn;
    END IF;

    -- clock_timestamp(): 장기 트랜잭션에서도 실제 봉인 시각을 기록한다.
    UPDATE ledger_transactions
    SET status = 'SEALED', sealed_at = clock_timestamp()
    WHERE id = v_txn AND status = 'OPEN';
    IF NOT FOUND THEN
        RAISE EXCEPTION '봉인 실패: txn=% OPEN 상태 아님', v_txn;
    END IF;

    -- 원가 프로젝션(V13). 봉인 뒤라야 분개 집합이 확정된다. 입력 계열 실패는 게시 결과를
    -- 바꾸지 않는다. 잠금·취소·데드락은 올라와 이 게시를 롤백시킨다(no-RAISE 계약의 범위).
    PERFORM apply_position_cost(v_txn);

    RETURN v_txn;
END $$;

-- ----------------------------------------------------------------
-- 6. 검증 뷰 (하네스 C24·C25. 앱 검증기 등재는 S7 소관)
-- ----------------------------------------------------------------
-- C24 원가 수량 보존: 사용자·자산별 cost_qty + unknown_qty = available + locked.
-- 취득·처분이 순델타 그대로 누산되므로 이력이 온전하면 항등이다. 어긋난 행은
-- apply 의 클램프·강등이 실제로 발동했다는 뜻이고 rebuild_position_cost 가 교정 수단이다.
CREATE VIEW position_cost_qty_check AS
SELECT pc.user_id, pc.asset_id,
       pc.cost_qty, pc.unknown_qty,
       COALESCE(ub.available, 0) + COALESCE(ub.locked, 0) AS held_qty
FROM position_cost pc
LEFT JOIN user_balances ub ON ub.user_id = pc.user_id AND ub.asset_id = pc.asset_id
WHERE pc.cost_qty + pc.unknown_qty
      <> COALESCE(ub.available, 0) + COALESCE(ub.locked, 0);

-- C25 원가 행 정합: 누산기 부호·쌍·워터마크·원가 통화 표기. CHECK 로 이미 막히는 항목도
-- 두는 이유는 SECURITY DEFINER 우회나 수퍼유저 직접 DML 을 회귀 안전망으로 잡기 위함이다.
CREATE VIEW position_cost_sanity_check AS
SELECT pc.user_id, pc.asset_id,
       CASE
           WHEN pc.cost_qty < 0 OR pc.cost_amount < 0
                OR pc.unknown_qty < 0 OR pc.unknown_proceeds < 0 THEN 'negative_accumulator'
           WHEN (pc.cost_qty = 0) <> (pc.cost_amount = 0)         THEN 'cost_pair_broken'
           WHEN pc.last_txn_id
                > COALESCE((SELECT max(id) FROM ledger_transactions), 0) THEN 'watermark_ahead'
           WHEN EXISTS (SELECT 1 FROM cost_quote_assets q WHERE q.asset_id = pc.asset_id)
                                                                  THEN 'quote_asset_position'
           WHEN NOT EXISTS (SELECT 1 FROM cost_quote_assets q
                            WHERE q.asset_id = pc.quote_asset_id)
                AND pc.cost_amount > 0                            THEN 'cost_currency_invalid'
       END AS violation
FROM position_cost pc
WHERE pc.cost_qty < 0 OR pc.cost_amount < 0
   OR pc.unknown_qty < 0 OR pc.unknown_proceeds < 0
   OR (pc.cost_qty = 0) <> (pc.cost_amount = 0)
   OR pc.last_txn_id > COALESCE((SELECT max(id) FROM ledger_transactions), 0)
   OR EXISTS (SELECT 1 FROM cost_quote_assets q WHERE q.asset_id = pc.asset_id)
   OR (NOT EXISTS (SELECT 1 FROM cost_quote_assets q WHERE q.asset_id = pc.quote_asset_id)
       AND pc.cost_amount > 0);

-- ----------------------------------------------------------------
-- 7. 권한
-- ----------------------------------------------------------------
-- app_role 은 position_cost 를 읽기만 한다. 쓰기는 SECURITY DEFINER 함수 전용이라
-- 원장·잔고와 같은 게시 일원화 규율이 원가 프로젝션에도 그대로 적용된다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT ON position_cost, cost_quote_assets,
                        position_cost_qty_check, position_cost_sanity_check TO app_role;
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON position_cost FROM app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON position_cost, cost_quote_assets,
                        position_cost_qty_check, position_cost_sanity_check TO harness_role;
    END IF;
END $$;

REVOKE ALL ON FUNCTION apply_position_cost(BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION rebuild_position_cost(BIGINT) FROM PUBLIC;

-- ----------------------------------------------------------------
-- 8. 백필
-- ----------------------------------------------------------------
-- 헤더의 운영 규모 caveat 참조. 신선 적용(빈 원장)에서는 0 행 no-op 이다.
SELECT rebuild_position_cost(NULL);
