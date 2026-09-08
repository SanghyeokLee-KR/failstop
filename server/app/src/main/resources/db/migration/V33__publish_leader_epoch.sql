-- 리스 보관소를 PostgreSQL 밖으로 옮길 때 쓰는 fencing epoch 게시 경로.
--
-- 왜 필요한가. 2026-09-08 실측에서 리스 갱신이 곧 커밋이라는 성질이 사고를 만들었다. 동기 복제
-- 대상 standby 를 전부 잃으면 커밋이 멈추고, 갱신도 함께 멈추고, 그 사이 리스가 만료되고,
-- standby 가 돌아와 커밋이 풀리는 순간 assert_current_leader 가 리더를 끌어내렸다. 복제가
-- streaming 으로 돌아와도 readiness 는 503 에 머물렀고 복구에 앱 재기동이 필요했다.
-- 근거는 harness/evidence/replication-fault-20260908/ 이다.
--
-- 그래서 리스 갱신을 외부 보관소로 옮긴다. 그러면 트랜잭션 안의 fencing 검증이 볼 근거가
-- 사라지므로, 새 리더가 자기 epoch 을 이 행에 먼저 올린다. 이 함수가 그 게시다.
--
-- assert_current_leader 와 assert_and_lock_leader 는 건드리지 않는다. 그 함수들의 거부 메시지에
-- 자바의 강등 분류(FencingErrors.rejectionDetail)와 fencing 주입 검증
-- (PromotionStandDownIntegrationTest.installFencingFault)이 걸려 있다.
--
-- lease_until 을 먼 미래로 미는 이유. 게시는 승격 때 한 번뿐이고 갱신 때는 하지 않는다. 갱신마다
-- 게시하면 게시가 곧 커밋이라 옮긴 의미가 사라진다. 그러면 이 행의 만료 시각은 갱신되지 않으므로
-- 짧은 값을 두면 한 TTL 뒤 모든 쓰기가 '리스 만료' 로 거부된다. 먼 미래는 '만료 판정은 이 행이
-- 하지 않는다' 를 뜻한다. 남는 검사는 (holder, epoch) 일치이고 stale 리더 차단은 그것으로 성립한다.
-- 다음 리더가 쓰기를 열기 전에 반드시 자기 epoch 을 여기 올리기 때문이다.
--
-- 'infinity' 가 아니라 유한한 먼 미래인 이유가 따로 있다. 이 컬럼을 읽는 계약이 여럿이고 전부
-- 유한한 타임스탬프를 전제한다.
--   admin/HaMonitoringController 의 lease_remaining_ms 는
--   EXTRACT(EPOCH FROM (lease_until - clock_timestamp())) * 1000 을 bigint 로 캐스트한다.
--   harness/HarnessController 의 leader_alive 는 lease_until > clock_timestamp() 다.
--   관리 평면 검증은 이 값이 타임스탬프 패턴을 만족한다고 단언한다.
-- 무한대는 캐스트를 깨고 패턴을 깬다. 100 년은 밀리초로 약 3.15e12 라 bigint 안에 넉넉히 들고
-- 패턴도 그대로 만족한다. 이 선택은 계약을 하나도 깨지 않으면서 같은 뜻을 낸다.
--
-- 남는 결과 하나를 적어 둔다. leader_alive 가 이 보관소에서는 항상 참이다. 리더 생존을 이 행으로
-- 판정하던 오라클은 dynamodb 보관소에서 권위를 잃고, 권위는 DynamoDB 항목의 leaseUntil 로 옮긴다.
--
-- 단조 조건. epoch 이 더 크거나, 같은 epoch 을 같은 보유자가 다시 올리는 경우에만 성립한다.
-- 같은 보유자의 재게시를 허용하는 이유는 게시 재시도가 정상 경로이기 때문이다. 허용하지 않으면
-- 재시도가 게시 거부로 읽혀 정당한 리더가 승격을 포기한다.
CREATE OR REPLACE FUNCTION publish_leader_epoch(p_holder TEXT, p_epoch BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    UPDATE leader_lease
    SET holder = p_holder, epoch = p_epoch,
        lease_until = clock_timestamp() + interval '100 years'
    WHERE resource = 'matching-engine'
      AND (epoch < p_epoch OR (epoch = p_epoch AND holder = p_holder))
    RETURNING epoch INTO v_epoch;
    RETURN v_epoch;
END $$;

COMMENT ON FUNCTION publish_leader_epoch(TEXT, BIGINT) IS
'외부 리스 보관소에서 얻은 epoch 을 leader_lease 에 단조 게시하고 만료 판정을 외부로 넘긴다(V33).';

-- 보관소를 PostgreSQL 로 되돌릴 때 쓴다. 게시가 lease_until 을 먼 미래로 밀어 두므로 그대로 두면
-- try_acquire_leader 의 만료 조건이 영원히 거짓이고 최초 선출이 불가능해진다. 되돌리기 전에
-- 이 함수를 한 번 부른다. 앱이 전부 내려간 상태에서만 부른다. 리더가 살아 있는데 부르면 그
-- 리더의 리스가 즉시 만료로 보여 다른 노드가 탈취한다.
CREATE OR REPLACE FUNCTION expire_leader_lease_now()
RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE leader_lease
    SET lease_until = clock_timestamp() - interval '1 second'
    WHERE resource = 'matching-engine';
END $$;

COMMENT ON FUNCTION expire_leader_lease_now() IS
'leader_lease 만료 시각을 과거로 되돌린다. 외부 보관소에서 PostgreSQL 보관소로 되돌릴 때 쓴다(V33).';

-- 권한. 앱 롤은 리스 행에 SELECT·UPDATE 만 갖고 함수는 EXECUTE 로만 부른다(V1 권한 경계).
-- 새 함수를 부여하지 않으면 운영 롤에서 승격이 권한 오류로 실패하고, 그 실패는 게시 실패이므로
-- 승격 실패가 된다. dev 는 소유자로 접속해 드러나지 않으니 여기서 닫는다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION
            publish_leader_epoch(TEXT, BIGINT),
            expire_leader_lease_now()
        TO app_role;
    END IF;
END $$;
