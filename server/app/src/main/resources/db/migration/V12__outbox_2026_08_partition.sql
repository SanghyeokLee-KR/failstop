-- ============================================================================
-- V12__outbox_2026_08_partition.sql
-- 원천: docs/백엔드_DB_설계.md v2.25 §3.1(outbox 초기 파티션), §3.2(수동 선생성 베이스라인).
-- 목적: outbox 시간 파티션의 2026-08 결손 교정.
--   V1은 outbox를 2026-07 한 달만 만들고(상한 2026-08-01), V8은 2026-09부터 12개월을
--   만든다. 그 사이 2026-08 한 달이 어느 정규 파티션에도 속하지 않는다. trades·
--   ledger_entries는 V1이 2026-07·2026-08 두 달을 만들어 경계가 이어지고, outbox만
--   어긋났다(V1 작성 시 누락).
--   결과: 2026-08 구간의 신규 outbox 행이 전부 outbox_default로 유입되어
--   C15_DEFAULT_PARTITION(파티션 지평 소진 전조)이 상시 위반된다. 프루닝도 무력화된다.
-- 절차: DEFAULT에 결손 구간 행이 있으면 CREATE ... PARTITION OF가 DEFAULT 제약 재계산에서
--   중단되므로, V8과 같은 순서(DETACH → 파티션 생성 → 행 재배치 → 재ATTACH)를 쓴다.
--   outbox에는 사용자 트리거가 없어 V8의 DISABLE TRIGGER USER 우회가 불필요하다.
--   id가 GENERATED ALWAYS AS IDENTITY라 재배치 INSERT에 OVERRIDING SYSTEM VALUE가 필요하다.
-- ============================================================================

-- 데이터 재배치가 데이터량에 비례한다. prod Hikari 풀 connection-init-sql의
-- statement_timeout=3s/lock_timeout=2s를 Flyway가 상속하면 적재 DB에서 57014로 취소돼
-- 부팅이 막힌다(V8·V10과 동일 근거). SET LOCAL이라 트랜잭션 종료 시 원복되고 풀에 무영향.
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

-- 아래 경계 리터럴은 명시 오프셋(+00)을 박아 세션 TZ에 의존하지 않는다. V1도 파일 상단
-- SET timezone='UTC' 아래에서 경계를 만들었으므로 2026-08-01T00:00Z가 outbox_p2026_07의
-- 상한이자 outbox_p2026_09의 하한 정렬점이다.
SET LOCAL timezone = 'UTC';

DO $$
DECLARE
    lo   CONSTANT TIMESTAMPTZ := TIMESTAMPTZ '2026-08-01 00:00:00+00';
    hi   CONSTANT TIMESTAMPTZ := TIMESTAMPTZ '2026-09-01 00:00:00+00';
    default_attached BOOLEAN;
    total_before BIGINT;
    total_after  BIGINT;
    moved   BIGINT := 0;
    removed BIGINT := 0;
BEGIN
    -- 재실행 안전: 파티션이 이미 있으면 전체를 건너뛴다.
    IF to_regclass('public.outbox_p2026_08') IS NULL THEN
        -- 총행 수 가드가 잠금 획득 경계를 가로지르면 안 된다. count 단독으로는 ACCESS SHARE만
        -- 잡으므로 READ COMMITTED에서 그 사이 커밋된 타 노드의 INSERT가 total_after를 늘려
        -- 유실이 없는데도 전체가 롤백되고 기동이 막힌다. 롤링 배포에서 살아 있는 노드가
        -- 정산을 계속하는 상황이 정확히 그 조건이다. 어차피 DETACH가 잡을 잠금을 앞당겨
        -- 잡아 두 count를 같은 경계 안에 넣는다.
        LOCK TABLE outbox IN ACCESS EXCLUSIVE MODE;

        SELECT count(*) INTO total_before FROM outbox;

        SELECT EXISTS (
            SELECT 1 FROM pg_inherits
            WHERE inhparent = 'public.outbox'::regclass
              AND inhrelid  = 'public.outbox_default'::regclass
        ) INTO default_attached;

        IF default_attached THEN
            ALTER TABLE outbox DETACH PARTITION outbox_default;
        END IF;

        CREATE TABLE outbox_p2026_08 PARTITION OF outbox
            FOR VALUES FROM (TIMESTAMPTZ '2026-08-01 00:00:00+00')
                         TO (TIMESTAMPTZ '2026-09-01 00:00:00+00');

        IF default_attached THEN
            -- 부모 경유 INSERT라 라우팅이 신규 파티션을 고른다(DEFAULT는 이 시점 분리 상태).
            INSERT INTO outbox (id, event_id, aggregate_type, aggregate_id, event_type,
                                payload, published_at, created_at) OVERRIDING SYSTEM VALUE
                SELECT id, event_id, aggregate_type, aggregate_id, event_type,
                       payload, published_at, created_at
                FROM outbox_default
                WHERE created_at >= lo AND created_at < hi;
            GET DIAGNOSTICS moved = ROW_COUNT;

            DELETE FROM outbox_default WHERE created_at >= lo AND created_at < hi;
            GET DIAGNOSTICS removed = ROW_COUNT;

            -- 이동·삭제 대칭이 깨지면 유실이므로 즉시 롤백한다(감춘 채 진행 금지).
            IF moved <> removed THEN
                RAISE EXCEPTION 'outbox 2026-08 재배치 비대칭: 이동 %, 삭제 %', moved, removed;
            END IF;

            ALTER TABLE outbox ATTACH PARTITION outbox_default DEFAULT;
        END IF;

        SELECT count(*) INTO total_after FROM outbox;
        IF total_after <> total_before THEN
            RAISE EXCEPTION 'outbox 행 수 변동: 이전 %, 이후 %', total_before, total_after;
        END IF;
    END IF;
END $$;
