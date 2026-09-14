-- 마켓별 이벤트 판독 인덱스. 재생과 팔로워 따라잡기는 마켓 하나의 이벤트를 (source_cmd_seq, event_ordinal)
-- 순서로 읽는다(EventJournalReader.readAfterForMarket).
--
-- 왜 하는가. 이 인덱스가 없으면 그 판독이 idx_evt_src 를 source_cmd_seq 순으로 훑으며 다른 마켓 행을 거른다.
-- 배치마다 버리는 행이 대략 배치 크기를 그 마켓 점유율로 나눈 만큼이라 작은 마켓일수록 비싸다. 합성 저널
-- (이벤트 74만 행)에서 점유율 1퍼센트 마켓의 한 배치가 5만여 행을 걸러 4ms 였고, 이 인덱스와 마켓 id 리터럴로
-- 0.3ms 였다(harness/evidence/replay-reader-plans-20260913/). idx_evt_market_seq 는 이 판독에 쓰이지 않는다.
-- 정렬이 명령 축이고 부분 인덱스 술어가 판독 조건에 없다.
--
-- 쓰기 비용. 같은 합성 저널의 대량 삽입 대리 측정에서 인덱스 유무 차이가 회차 사이 편차 안이었다.
-- 정산 경로에서 잰 값은 아니다.
--
-- 잠금. 분할 부모에는 CONCURRENTLY 를 쓸 수 없어 잎마다 SHARE 잠금을 쥔다. 라이터가 멈춘 배포 창에서 적용한다.
-- 운영 중에 적용하면 정산이 기본 풀 lock_timeout(2000ms)을 넘기는 순간 55P03 으로 실패하고, 이 파일의 lock_timeout 0 은
-- 긴 트랜잭션 뒤에 줄을 서 그 뒤 적재까지 막는다. 합성 저널 74만 행에서 생성은 347.7ms 였고 저널 크기에 비례한다.

SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

CREATE INDEX idx_evt_market_cmd ON event_journal (market_id, source_cmd_seq, event_ordinal);
