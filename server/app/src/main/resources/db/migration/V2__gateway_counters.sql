-- ============================================================================
-- V2__gateway_counters.sql
-- 게이트웨이 주문 id 사전 채번 카운터(DB 설계 v2.9 §5.1 (e), API 설계 §4-B-1 v2.6).
-- orders.id = command_journal.assigned_order_id는 접수 전 게이트웨이가 발급한다.
-- gapless는 요구되지 않으나 채번 경로는 journal_counters 단일 방식으로 통일한다.
-- ============================================================================
INSERT INTO journal_counters (name, next_value) VALUES ('order_id', 1)
ON CONFLICT (name) DO NOTHING;
