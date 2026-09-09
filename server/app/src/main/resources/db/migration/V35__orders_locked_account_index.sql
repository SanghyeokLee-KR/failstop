-- C14 불변식이 orders 를 순차 스캔하던 것을 인덱스로 바꾼다.
--
-- 증상. 2026-09-09 실측에서 accounts 순차 스캔이 108만 회, 읽은 튜플 16.6억이었다.
-- 데이터베이스 초당 읽은 튜플이 42.8만인데 쓴 튜플은 544 로 787 대 1 이었다. 그 비대칭이
-- 조회 부하 때문인 줄 알았으나 아니었다. 거래 경로가 아니라 관측 스캐너 하나였다.
--
-- 범인. C14_LOCKED_RESERVATION 이다. LOCKED 잔고가 열린 주문의 예약 합과 맞는지 보는
-- 검사이고 스케줄러가 주기로 돈다. 그 안의 LEFT JOIN 이 orders.locked_account_id 를 조건으로
-- 거는데 그 컬럼에 인덱스가 없어 orders 전량을 훑는다. 실행 계획이 Seq Scan on orders 로
-- 19.9만 행을 읽고 136 밀리초를 쓴다. 데이터가 쌓일수록 선형으로 나빠진다.
--
-- 왜 accounts 가 아니라 orders 인가. accounts 는 1547 행짜리 작은 표라 그 자체 스캔은 싸다.
-- pg_stat_user_tables 의 accounts.seq_tup_read 가 큰 것은 이 조인이 반복되며 매번 전량을
-- 해시에 올리기 때문이고, 원인은 반대편 orders 다. 통계만 보고 accounts 를 고치면 헛짚는다.
--
-- 조치. 부분 인덱스 하나다. 조건절이 status 를 세 값으로 좁히므로 그 술어를 인덱스에
-- 박는다. 전체 인덱스보다 작고 쓰기 비용도 적다. 주문 대부분은 체결이나 취소로 끝나
-- 이 세 상태에 머무는 행은 소수다.
--
-- 거래 경로에 영향이 없다. 스키마도 제약도 트리거도 건드리지 않고 인덱스만 더한다.
-- 발주와 체결과 정산의 SQL 은 한 글자도 바뀌지 않는다. 인덱스 추가는 쓰기에 소폭 비용을
-- 더하나, 부분 인덱스라 대상 행이 적고 이 표는 초당 수백 행 규모다.
--
-- CONCURRENTLY 를 쓰지 않는다. Flyway 는 마이그레이션을 트랜잭션 안에서 돌리는데
-- CREATE INDEX CONCURRENTLY 는 트랜잭션 밖에서만 된다. 이 표의 크기에서 잠금 시간은
-- 짧고, 기동 시점이라 트래픽이 없다.

CREATE INDEX IF NOT EXISTS idx_orders_locked_account_open
    ON orders (locked_account_id)
    WHERE status IN ('PENDING', 'OPEN', 'PARTIAL');

COMMENT ON INDEX idx_orders_locked_account_open IS
    'C14 불변식의 LOCKED 예약 합 조인용. 열린 주문만 담는 부분 인덱스다(V35, 2026-09-09 실측)';
