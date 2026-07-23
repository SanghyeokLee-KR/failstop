-- ============================================================================
-- V11__sim_run_ordinal.sql
-- 봇 플릿 run ordinal 채번 시퀀스. run 시작마다 nextval로 1건 발번해 client_order_id nonce
-- 접두의 유일성 성분으로 쓴다. 종전 인메모리 카운터는 JVM 재시작마다 리셋돼 DB 보존 재기동에서
-- 직전 run의 coid 열을 재생성했고 idempotency_keys와 충돌했다. 채번원을 멱등키와 같은 DB에 두면
-- 백업 복원에서도 둘이 함께 되감겨 정합이 유지된다.
-- 엔진 기원 쓰기·리더 리스·원장 게시와 무관한 봇 클라이언트측 채번이라 journal_counters와
-- 분리한다(엔진 임계경로 미접촉).
-- ============================================================================
CREATE SEQUENCE IF NOT EXISTS sim_run_ordinal AS BIGINT START 1;

-- 신규 시퀀스는 V1의 일괄 GRANT(적용 시점 스냅샷) 이후 생성되므로 명시 부여한다.
-- 롤 존재 검사로 빈 PG17 단독 적용성을 지킨다(정상 번들에서는 V0가 선행 생성).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT USAGE ON SEQUENCE sim_run_ordinal TO app_role;
    END IF;
END $$;
