-- 게이트웨이 주문 id 채번을 journal_counters 행 잠금에서 시퀀스로 옮긴다.
--
-- 왜 옮기는가.
--
-- `next_journal_value` 는 카운터 한 행을 UPDATE 로 잠근다. 그 잠금은 트랜잭션이 끝날 때까지
-- 유지되므로 같은 카운터를 쓰는 호출자들이 직렬화된다. command_seq·event_seq 는 단일 라이터
-- 스레드가 쓰므로 어차피 직렬이라 문제가 되지 않는다.
--
-- order_id 는 다르다. 접수 경로(OrderGateway.nextOrderId)가 API 스레드에서 부르고, 수용 정원이
-- 허용하는 만큼 동시에 들어온다. 게다가 OrderGateway 에는 트랜잭션이 없어 이 한 문장이 곧
-- 하나의 트랜잭션이다. 즉 **주문마다 id 채번만을 위한 커밋과 WAL 플러시가 한 번 더 일어난다.**
--
-- 2026-09-07 실측(pg_stat_statements): `next_journal_value('order_id')` 가 명령당 2.73 회에
-- 총 3202ms 로, 같은 창의 `assert_current_leader`(482ms) 의 12 배다. 같은 창에서
-- `synchronous_commit=off` 로 커밋 플러시를 없애면 주문 처리량이 77.1/s 에서 128.8/s 로 올랐다
-- (증거 `scenario-20260907` 5 단계, `fencing-20260907`). 플러시가 이 경로의 지배 비용이라는 뜻이다.
--
-- 왜 시퀀스가 되는가.
--
-- V2__gateway_counters.sql 이 이미 적었다. "gapless는 요구되지 않으나 채번 경로는
-- journal_counters 단일 방식으로 통일한다." order_id 는 통일성 때문에 그 경로에 있었고
-- 정확성 때문이 아니다. 실제 사용처를 확인하면 전부 조인 키와 존재 검사다
-- (`harness/sql/invariants.sql:79,91`, `orders.id = command_journal.assigned_order_id`).
-- 연속성을 가정하는 곳이 없다.
--
-- 시퀀스의 nextval 은 행 잠금을 잡지 않고 롤백에도 되돌아가지 않는다. 그래서 직렬화가 사라지고
-- 캐시 구간 안에서는 WAL 플러시도 없다. 대가는 구멍인데, 그 대가는 정본이 이미 허용했다.
--
-- command_seq 와 event_seq 는 옮기지 않는다. 그 둘은 재생 결정론의 축이고 gapless 가 계약이다
-- (V1__init.sql:567 "PK=seq(전역유일 gapless)").
--
-- CACHE 를 20 으로 둔 이유. 세션마다 20 개를 미리 잡아 공유 시퀀스 접근을 1/20 로 줄인다.
-- 대가는 세션 종료·재기동 때 잡아 둔 나머지가 버려지는 것이고, 커넥션 풀 16 기준 최대 320 의
-- 구멍이다. 구멍이 허용되는 축이라 이 교환은 성립한다. 값을 더 키우면 구멍이 커지고 줄이면
-- 플러시가 늘어난다.

-- 기존 카운터가 어디까지 나갔는지에서 이어 받는다. 둘 중 큰 값을 쓴다. 카운터만 보면 과거에
-- 직접 삽입된 행을 놓치고, orders 만 보면 채번했으나 접수에 실패해 행이 없는 구간을 놓친다.
DO $$
DECLARE v_start BIGINT;
BEGIN
    SELECT GREATEST(
               COALESCE((SELECT next_value FROM journal_counters WHERE name = 'order_id'), 1),
               COALESCE((SELECT max(id) + 1 FROM orders), 1))
    INTO v_start;
    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS order_id_seq AS BIGINT START WITH %s CACHE 20', v_start);
    -- IF NOT EXISTS 로 이미 있으면 START 가 무시되므로 명시적으로 맞춘다. 재적용해도 뒤로
    -- 돌아가지 않도록 현재 값보다 클 때만 올린다.
    IF (SELECT last_value FROM order_id_seq) < v_start THEN
        PERFORM setval('order_id_seq', v_start, false);
    END IF;
END $$;

COMMENT ON SEQUENCE order_id_seq IS
'게이트웨이 주문 id 채번(V31). gapless 미요구(V2 주석). 행 잠금과 채번 전용 커밋을 없애려고 journal_counters 에서 옮겼다.';

-- journal_counters 의 order_id 행은 남긴다. 지우면 롤백이 불가능해지고, 남겨도 아무도 읽지
-- 않으므로 비용이 없다. 되돌릴 때는 이 행의 값을 시퀀스 현재값으로 올린 뒤 코드를 되돌린다.
COMMENT ON TABLE journal_counters IS
'저널 seq 채번(gapless 계약). order_id 행은 V31 에서 order_id_seq 로 옮겨 더 이상 쓰이지 않는다.';

GRANT USAGE, SELECT ON SEQUENCE order_id_seq TO app_role;
