-- ----------------------------------------------------------------
-- 공지 목록 커서 페이지네이션 정렬 축 (DB 설계 v2.33 §7)
--
-- V5는 (active, published_at DESC)로 잡았다. published_at은 유일하지 않으므로 그것만으로는
-- 커서 경계가 정해지지 않는다. 같은 시각에 발행된 두 공지가 있으면 쪽을 넘길 때 하나가
-- 두 번 나오거나 아예 빠진다. 정렬을 (published_at DESC, id DESC)로 확정하고 인덱스를
-- 같은 축으로 맞춘다 (API 설계 §2-6).
--
-- 공개 경로(active = true 전용)와 관리 경로(철회분 포함)가 축은 같고 조건만 다르므로
-- 인덱스를 둘로 나눈다. 공개용은 부분 인덱스라 철회분이 인덱스를 키우지 않는다.
-- ----------------------------------------------------------------

CREATE INDEX idx_notice_active_keyset ON notices (published_at DESC, id DESC)
    WHERE active = true;

CREATE INDEX idx_notice_keyset ON notices (published_at DESC, id DESC);

-- 구 인덱스. 새 부분 인덱스가 active = true 조회를 덮고, 전체 조회는 idx_notice_keyset이
-- 받는다. active = false만 따로 훑는 경로는 없다
DROP INDEX idx_notice_active;
