-- ----------------------------------------------------------------
-- 커서 페이지네이션 정렬 축 일괄 정렬 (DB 설계 v2.34, API 설계 §2-6)
--
-- 목록 6곳이 고정 LIMIT으로 잘리고 있었다. 커서를 열면서 정렬 축을 (시각, id)로 확정하니
-- 기존 인덱스가 그 축을 덮지 못하는 곳이 남는다. 키셋 술어 (a, b) < (?, ?)는 복합 인덱스가
-- 없으면 필터로 떨어지고, 페이지가 뒤로 갈수록 앞쪽 행을 버리는 비용이 누적된다.
--
-- id 보조 축을 넣는 이유는 성능만이 아니다. 시각 컬럼은 유일하지 않아 단독 축으로는 쪽
-- 경계가 정해지지 않는다. 같은 시각 두 행이 쪽을 넘길 때 하나가 두 번 나오거나 빠진다.
-- ----------------------------------------------------------------

-- 감사 로그. append-only라 영구 누적이고, 필터 없는 전체 훑기가 기본 경로다.
-- 기존 idx_audit_operator·idx_audit_action은 필터가 있을 때만 쓰이고 id 보조 축이 없다
CREATE INDEX idx_audit_keyset ON admin_audit_log (created_at DESC, id DESC);

-- 카오스 실험 이력. action LIKE 'chaos.%' 부분 인덱스라 감사 로그 전체를 키우지 않는다
CREATE INDEX idx_audit_chaos_keyset ON admin_audit_log (created_at DESC, id DESC)
    WHERE action LIKE 'chaos.%';

-- 2인 승인 이력. state 필터가 선택이라 축만으로도 훑을 수 있어야 한다
CREATE INDEX idx_approval_keyset ON admin_approvals (issued_at DESC, id DESC);
CREATE INDEX idx_approval_state_keyset ON admin_approvals (state, issued_at DESC, id DESC);

-- 신고 큐. 오래된 것부터 처리하므로 오름차순 축이다. 기존 idx_reports_pending은
-- PENDING 부분 인덱스라 처리된 상태 조회를 덮지 못하고 id 보조 축도 없다
CREATE INDEX idx_reports_status_keyset ON reports (status, created_at, id);

-- 출금 승인 큐. 오름차순. 기존 idx_transfer_pending은 REQUESTED·PENDING 부분 인덱스라
-- COMPLETED·CANCELED 조회를 덮지 못한다
CREATE INDEX idx_transfer_status_keyset ON transfer_requests (status, created_at, id);

-- 문의 답변 큐. 오름차순. 기존 idx_support_open은 OPEN 부분 인덱스라 ANSWERED를 덮지 못한다
CREATE INDEX idx_support_status_keyset ON support_inquiries (status, created_at, id);

-- 커서 앵커 되짚기. 큐 두 곳이 external_id로 앵커를 찾는다. UNIQUE 인덱스가 이미 있어
-- 추가 인덱스는 두지 않는다(V16·V17의 (user_id, external_id)는 사용자 스코프 전용이다)
