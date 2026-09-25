-- 구간 경계 증분의 표본(S7 설계 4-7·5-2, DB 설계 §5.4-e 5). s7_axes.py segment-count 와 protocol_latch.py 가 쓸 초안이다.
-- 전환마다(이력의 둘째 행부터) 그 이력 행의 epoch_floor 이하 epoch(옛 구간)로 기록된 네 표의 행 수를 낸다. 전환 뒤 배정되는
-- epoch 는 모두 epoch_floor 보다 크므로, 전환 직후(노드 시작 전)에 뜬 값과 실험 끝에 뜬 값이 같아야 한다. 커밋 순서와 시계에
-- 기대지 않는다. 표본마다 네 표를 전량 훑으므로 권위 run 이 아니라 하네스(psql)가 전환 직후와 실험 끝에 한 번씩 뜬다.
SELECT h.id, h.protocol, h.epoch_floor,
       (SELECT count(*) FROM command_journal c WHERE c.fencing_token <= h.epoch_floor)
     + (SELECT count(*) FROM event_journal e WHERE e.fencing_epoch <= h.epoch_floor)
     + (SELECT count(*) FROM trades t WHERE t.fencing_epoch <= h.epoch_floor)
     + (SELECT count(*) FROM snapshots n WHERE n.fencing_epoch <= h.epoch_floor) AS old_rows
  FROM leader_protocol_history h
 WHERE h.id > (SELECT min(id) FROM leader_protocol_history)
 ORDER BY h.id;
