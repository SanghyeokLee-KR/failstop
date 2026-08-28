-- ----------------------------------------------------------------
-- 회원 운영 목록 정렬 축 (DB 설계 v2.35, API 설계 §6-E)
--
-- users에는 PK와 external_id·email 유니크뿐이라 관리 목록이 쓰는 축을 덮는 인덱스가 없다.
-- 목록은 가입 역순 (created_at DESC, id DESC)이고 상태 필터가 얹힌다. 키셋 술어
-- (created_at, id) < (?, ?)는 복합 인덱스가 없으면 필터로 떨어져 쪽이 뒤로 갈수록
-- 앞쪽 행을 버리는 비용이 누적된다.
--
-- id 보조 축은 성능만의 문제가 아니다. created_at은 유일하지 않아 단독 축으로는 쪽 경계가
-- 정해지지 않는다. 같은 시각 두 행이 쪽을 넘길 때 하나가 두 번 나오거나 빠진다(V19와 같은 규율).
--
-- kind = 'HUMAN' 부분 인덱스인 이유는 조회 술어와 일치시키기 위해서다. 봇 시더가 만든
-- kind='BOT' 행은 운영 대상이 아니라 목록에서 제외되므로, 인덱스도 같은 술어로 좁혀
-- 봇 계정이 인덱스를 부풀리지 않게 한다.
-- ----------------------------------------------------------------

-- 상태 필터 있는 조회. 상태를 선두에 둬 등가 술어로 먼저 좁힌 뒤 정렬 축을 잇는다.
CREATE INDEX idx_users_admin_status_keyset
    ON users (status, created_at DESC, id DESC)
    WHERE kind = 'HUMAN';

-- 필터 없는 전체 훑기. 상태 선두 인덱스는 필터가 없으면 전 상태를 훑어야 해 축이 맞지 않는다.
CREATE INDEX idx_users_admin_keyset
    ON users (created_at DESC, id DESC)
    WHERE kind = 'HUMAN';

-- 상세·전이가 external_id로 행을 집는다. UNIQUE (external_id)가 이미 있어 추가 인덱스는 없다.
