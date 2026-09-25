-- ============================================================================
-- segment-old-rows.sql
-- 구간 경계 증분의 표본(ADR 0034 S7, S7 설계 4-7·5-2, DB 설계 §5.4-e 5, 하네스 검증설계 §3 P3 실험 공통 판정).
-- 전환마다(이력의 둘째 행부터) 그 이력 행의 epoch_floor 이하 epoch(옛 구간)로 기록된 네 표의 행 수를 낸다. 전환 뒤 배정되는
-- epoch 는 모두 epoch_floor 보다 크므로, 전환 직후(노드 시작 전)에 뜬 값과 실험 끝에 뜬 값이 같아야 한다. 커밋 순서와 시계에
-- 기대지 않으므로 사후 감사(C22'·C30)가 세지 못하는 두 경우, 곧 전환을 가로지른 트랜잭션과 옛 구간 epoch 의 event_journal·
-- trades 쓰기도 센다. 표본마다 네 표를 전량 훑으므로 권위 run 이 아니라 하네스(psql, s7_axes.py segment-count, protocol_latch.py)가
-- 전환 직후와 실험 끝에 한 번씩 뜬다. 서버 시험(HarnessSqlVerdictTest, CutoverStaleWriteTest)도 이 파일을 그대로 돌린다.
-- 열: id(이력 행), protocol(그 행이 연 모드), epoch_floor, old_rows(옛 구간 epoch 행 수). 초안은
-- harness/evidence/pml-s7-probe-20260925/segment-old-rows.sql 이고 술어가 같다.
-- ============================================================================
SELECT h.id, h.protocol, h.epoch_floor,
       (SELECT count(*) FROM command_journal c WHERE c.fencing_token <= h.epoch_floor)
     + (SELECT count(*) FROM event_journal e WHERE e.fencing_epoch <= h.epoch_floor)
     + (SELECT count(*) FROM trades t WHERE t.fencing_epoch <= h.epoch_floor)
     + (SELECT count(*) FROM snapshots n WHERE n.fencing_epoch <= h.epoch_floor) AS old_rows
  FROM leader_protocol_history h
 WHERE h.id > (SELECT min(id) FROM leader_protocol_history)
 ORDER BY h.id;
