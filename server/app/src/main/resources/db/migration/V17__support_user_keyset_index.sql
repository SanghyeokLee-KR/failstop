-- ----------------------------------------------------------------
-- 문의 목록 커서 페이지네이션 정렬 축 교정 (DB 설계 v2.32 §4-G)
--
-- V15는 (user_id, id DESC)로 인덱스를 잡고 목록도 id DESC로 정렬했다. V16이 입출금에서
-- 고친 것과 같은 결함이다. id는 IDENTITY라 유일하지만 created_at 순서와 항상 같지는 않다.
-- 겹친 트랜잭션에서 늦게 시작해 먼저 커밋한 문의가 더 작은 id를 받으므로, 화면이 보여주는
-- 시각 정렬과 페이지 경계가 갈린다. 그 상태에서 커서를 (created_at, external_id)로
-- 발급하면 요청 축과 응답 축이 어긋난다 (API 설계 §2-6).
-- ----------------------------------------------------------------

CREATE INDEX idx_support_user_keyset ON support_inquiries (user_id, created_at DESC, id DESC);

-- 커서 앵커 조회가 (user_id, external_id)로 들어간다. external_id UNIQUE 인덱스만으로도
-- 찾히지만 그 경로는 사용자 스코프를 인덱스가 아니라 필터로 확인한다
CREATE INDEX idx_support_user_external ON support_inquiries (user_id, external_id);

-- 구 정렬 축 전용. 새 인덱스가 (user_id) 접두를 포함하므로 사용자 스코프 조회도 커버한다
DROP INDEX idx_support_user;
