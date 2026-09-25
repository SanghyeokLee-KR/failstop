-- V55__partition_protocol_cutover.sql (ADR 0034 S7, DB 설계 §3.10·§5.4-e). 쓰기를 멈춘 창에서 적용한다.
-- 파티션 리더 모드(P3)로 오르내리는 전환과 그 선행 조건, P3 가 쓰는 수수료 샤드 계정을 더한다. 모드는 바꾸지 않는다.
-- 운영 풀의 statement_timeout·lock_timeout 을 Flyway 가 물려받으면 트리거 생성과 함수 교체가 막힌다(V41·V48 과 같은 근거).
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

-- ── 수수료 샤드 계정(ADR 0034 결정 10, DB 설계 §5.4-c 11) ─────────────
-- P3 에서 체결 수수료는 그 마켓 파티션의 번호(matching-p0 0, matching-p1 1)를 샤드로 쓰는 FEE_INCOME 계정에 게시한다.
-- 모드와 무관하게 자산마다 샤드 0·1 두 계정을 둔다. P1·P2 는 샤드 0 만 쓰므로 샤드 1 은 잔고 0 으로 남는다.
-- 자산은 앱·시드 도구·배포 SQL 이 넣고 그때 샤드 0 계정을 만든다. 트리거가 같은 트랜잭션에 샤드 1 을 더한다.
-- 발화는 EXECUTE 를 보지 않지만 CREATE TRIGGER 는 본다. 아래 권한 절이 PUBLIC EXECUTE 를 회수한다(V48 과 같은 이유).
CREATE FUNCTION seed_fee_income_shard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
    VALUES ('SYSTEM', 0, 'FEE_INCOME', NEW.asset_id, 1)
    ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_accounts_fee_income_shard AFTER INSERT ON accounts
    FOR EACH ROW WHEN (NEW.owner_type = 'SYSTEM' AND NEW.purpose = 'FEE_INCOME' AND NEW.shard = 0)
    EXECUTE FUNCTION seed_fee_income_shard();
-- 이미 있는 자산. 잔고 행은 trg_account_balance 가 만든다.
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'SYSTEM', 0, 'FEE_INCOME', a.asset_id, 1
  FROM accounts a
 WHERE a.owner_type = 'SYSTEM' AND a.purpose = 'FEE_INCOME' AND a.shard = 0
ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING;

-- ── 전환 선행 조건(읽기 전용) ─────────────────────────────────────────
-- 목표 모드로 전환하지 못하게 막는 것을 한 줄씩 낸다. 행이 없으면 막는 것이 없다. 전환 함수가 잠금 뒤에 부르고,
-- 전환 도구는 정지 창에 들어가기 전에 같은 함수로 미리 본다(드레인 전이라 unsettled_new 는 그때 참일 수 있다).
-- 모든 목표: 정산되지 않은 주문 생성 명령 0건(ADR 0034 결정 8, S3 설계 4-7).
-- 3 으로 올릴 때(단계 계획 S7 선행 조건): 명령 대역 래치, 모든 마켓의 대응·카운터 행, 파티션 리스 행, 모든 마켓의 기준·호가
-- 자산에 FEE_INCOME 샤드 0·1, app_role 에 leader_lease UPDATE 와 command_journal INSERT 가 없음(V49 상태, AI 방침).
-- 두 노드가 파티션 가능 바이너리인지는 DB 가 알 수 없어 절차가 확인한다(DB 설계 §5.4-e).
CREATE FUNCTION leader_protocol_blockers(p_target SMALLINT)
RETURNS TABLE (blocker TEXT, detail TEXT)
LANGUAGE plpgsql STABLE SET search_path = public, pg_temp AS $$
DECLARE
    v_n    BIGINT;
    v_list TEXT;
BEGIN
    SELECT count(*) INTO v_n
      FROM command_journal c
     WHERE c.command_type = 'NEW'
       AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq);
    IF v_n > 0 THEN
        SELECT string_agg(s.seq::text, ',') INTO v_list
          FROM (SELECT c.seq FROM command_journal c
                 WHERE c.command_type = 'NEW'
                   AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq)
                 ORDER BY c.seq LIMIT 10) s;
        blocker := 'unsettled_new'; detail := v_n || '건(앞 seq: ' || v_list || ')'; RETURN NEXT;
    END IF;
    IF p_target <> 3 THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM journal_cutover WHERE name = 'command_seq') THEN
        blocker := 'band_latch_missing'; detail := 'journal_cutover 에 command_seq 행이 없다(ADR 0022 6-b)'; RETURN NEXT;
    END IF;
    -- 래치 뒤에 생긴 마켓도 대역 잎이 있어야 한다. 없으면 그 마켓 명령이 DEFAULT 잎으로 간다(V44 래치 전 확인 1 과 같은 술어).
    SELECT string_agg(m.id::text, ',' ORDER BY m.id) INTO v_list
      FROM markets m
     WHERE NOT EXISTS (SELECT 1 FROM pg_inherits i
                        WHERE i.inhparent = 'public.command_journal'::regclass
                          AND i.inhrelid = to_regclass('public.command_journal_b' || m.id));
    IF v_list IS NOT NULL THEN
        blocker := 'band_leaf_missing'; detail := '대역 잎이 없는 마켓 ' || v_list; RETURN NEXT;
    END IF;
    SELECT string_agg(m.id::text, ',' ORDER BY m.id) INTO v_list
      FROM markets m WHERE NOT EXISTS (SELECT 1 FROM market_lease_partition p WHERE p.market_id = m.id);
    IF v_list IS NOT NULL THEN
        blocker := 'market_partition_missing'; detail := '마켓 ' || v_list; RETURN NEXT;
    END IF;
    SELECT string_agg(m.id::text || ':' || n.name, ',' ORDER BY m.id, n.name) INTO v_list
      FROM markets m CROSS JOIN (VALUES ('command_seq'), ('event_seq')) AS n(name)
     WHERE NOT EXISTS (SELECT 1 FROM market_journal_counters c WHERE c.market_id = m.id AND c.name = n.name);
    IF v_list IS NOT NULL THEN
        blocker := 'market_counter_missing'; detail := '마켓:카운터 ' || v_list; RETURN NEXT;
    END IF;
    SELECT string_agg(r.resource, ',' ORDER BY r.resource) INTO v_list
      FROM (VALUES ('matching-p0'), ('matching-p1')) AS r(resource)
     WHERE NOT EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = r.resource);
    IF v_list IS NOT NULL THEN
        blocker := 'lease_row_missing'; detail := v_list; RETURN NEXT;
    END IF;
    SELECT string_agg(x.asset_id::text, ',' ORDER BY x.asset_id) INTO v_list
      FROM (SELECT base_asset_id AS asset_id FROM markets UNION SELECT quote_asset_id FROM markets) x
     WHERE (SELECT count(DISTINCT a.shard) FROM accounts a
             WHERE a.owner_type = 'SYSTEM' AND a.owner_id = 0 AND a.purpose = 'FEE_INCOME'
               AND a.asset_id = x.asset_id AND a.shard IN (0, 1)) < 2;
    IF v_list IS NOT NULL THEN
        blocker := 'fee_shard_missing'; detail := 'FEE_INCOME 샤드 0·1 이 모두 있지 않은 자산 ' || v_list; RETURN NEXT;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        IF has_any_column_privilege('app_role', 'leader_lease', 'UPDATE') THEN
            blocker := 'app_role_lease_update'; detail := 'app_role 이 leader_lease 를 UPDATE 할 수 있다(V49 회수가 되돌려졌다)'; RETURN NEXT;
        END IF;
        IF has_any_column_privilege('app_role', 'command_journal', 'INSERT') THEN
            blocker := 'app_role_journal_insert'; detail := 'app_role 이 command_journal 에 직접 INSERT 할 수 있다(V49 회수가 되돌려졌다)'; RETURN NEXT;
        END IF;
    END IF;
END $$;

-- ── 모드 전환(소유자 전용, 정지 창) ─────────────────────────────────
-- V50 판에 2->3 과 3->2 를 더한다. 잠금 순서(모든 리스 행 resource 순 FOR UPDATE 다음 모드 행), 5초 잠금 대기, 퇴역 순서
-- (matching-engine 을 마지막에)는 V50 과 같다. 내리는 전이(3->2, 2->1)는 모두 p_allow_downgrade 를 요구한다. 선행 조건은 잠금
-- 뒤에 leader_protocol_blockers 로 본다. 잠금 전에 보면 그 사이 커밋되는 쓰기가 새어 들어온다(단계 계획 2절 검토 1 1b).
CREATE OR REPLACE FUNCTION set_leader_protocol(p_target SMALLINT, p_allow_downgrade BOOLEAN DEFAULT false)
RETURNS TABLE (out_resource TEXT, out_retired_epoch BIGINT, out_protocol SMALLINT)
LANGUAGE plpgsql SET lock_timeout = '5s' AS $$
DECLARE
    v_current  SMALLINT;
    v_blockers TEXT;
    v_res      TEXT;
    v_epoch    BIGINT;
BEGIN
    PERFORM 1 FROM leader_lease l ORDER BY l.resource FOR UPDATE;
    SELECT p.protocol INTO v_current FROM leader_protocol p FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_protocol 행 없음: 부트스트랩 오류';
    END IF;
    IF NOT ((v_current = 1 AND p_target = 2) OR (v_current = 2 AND p_target = 1)
            OR (v_current = 2 AND p_target = 3) OR (v_current = 3 AND p_target = 2)) THEN
        RAISE EXCEPTION '프로토콜 전이 P% -> P%는 받지 않는다(1->2, 2->1, 2->3, 3->2 만)', v_current, p_target
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF p_target < v_current AND NOT p_allow_downgrade THEN
        RAISE EXCEPTION 'P% -> P% 는 되돌리기다. 2->1 은 ADR 0034 결정 8 과 시험 R1, 3->2 는 DB 설계 §5.4-e 되돌리기 절차를 확인한 뒤 p_allow_downgrade => true 로만 한다',
            v_current, p_target
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    SELECT string_agg(b.blocker || ': ' || b.detail, '; ') INTO v_blockers FROM leader_protocol_blockers(p_target) b;
    IF v_blockers IS NOT NULL THEN
        RAISE EXCEPTION 'P% -> P% 전환의 선행 조건이 어긋난다. %', v_current, p_target, v_blockers
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    UPDATE leader_protocol SET protocol = p_target;
    -- 잠금은 위에서 resource 순으로 이미 쥐었다. 퇴역 순서만 matching-engine 을 끝에 둔다(false 가 true 보다 앞선다).
    FOR v_res IN SELECT l.resource FROM leader_lease l
                 ORDER BY (l.resource = 'matching-engine'), l.resource LOOP
        v_epoch := allocate_lease_epoch();
        UPDATE leader_lease l
           SET holder = '', epoch = v_epoch, lease_until = clock_timestamp() - interval '1 second'
         WHERE l.resource = v_res;
        INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
        VALUES (v_epoch, v_res, '', p_target, 'RETIRE');
        out_resource := v_res; out_retired_epoch := v_epoch; out_protocol := p_target;
        RETURN NEXT;
    END LOOP;
END $$;

-- ── 권한 ────────────────────────────────────────────────────────────
-- 트리거 함수와 선행 조건 함수에서 PUBLIC EXECUTE 를 회수한다. 전환 함수의 권한은 V50 그대로다(CREATE OR REPLACE 는 권한을
-- 바꾸지 않는다. PUBLIC 과 app_role 에 EXECUTE 가 없다). 선행 조건 함수는 읽기만 하므로 하네스 조회 역할에 준다.
REVOKE ALL ON FUNCTION seed_fee_income_shard(), leader_protocol_blockers(SMALLINT) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT EXECUTE ON FUNCTION leader_protocol_blockers(SMALLINT) TO harness_role;
    END IF;
END $$;
