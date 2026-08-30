-- recovery_runs 나머지 컬럼 주석. V24가 절반만 세웠다.
--
-- V24는 fp_replay·fp_live·matched·market_id 넷에만 COMMENT ON을 걸었다. 실측(psql
-- col_description 전수)에서 duration_ms·snapshot_id·replayed_from_seq·replayed_to_seq가
-- 비어 있다. V24는 적용분이라 고칠 수 없으므로 새 파일로 잇는다.
--
-- duration_ms가 특히 중요하다. 이 값은 재생 소요이고 페일오버 RTO가 아닌데, 프론트 타입이
-- 한때 `durationMs: number; // RTO`로, 정본 문서가 "RTO duration_ms"로 적고 있었다. 축이
-- 갈리면 화면이 재생 소요를 30초 SLO 축에 그린다(실측 결함). DB가 스스로 축을 말해야
-- 다음 소비자가 같은 혼동을 하지 않는다.
COMMENT ON COLUMN recovery_runs.duration_ms IS
    '재생 소요 밀리초. 페일오버 RTO가 아니다. RTO 원천은 harness.failover_runs.rto_seconds이고 '
    '두 축은 다른 것을 잰다. 실측 대역(2026-08-30, n=34): min 40 / p50 73 / max 159ms.';

COMMENT ON COLUMN recovery_runs.snapshot_id IS
    '재생 시작 스냅샷 id. 현행 기록자(RecoveryRunRecorder)는 항상 NULL을 넣는다. '
    '전 마켓 통합 재생이라 스냅샷 단위 분해가 원천에 없다. NULL을 "스냅샷 없음"으로 읽지 않는다.';

COMMENT ON COLUMN recovery_runs.replayed_from_seq IS
    '재생 시작 seq. 스냅샷 로드 후 tail 재생이면 그 스냅샷의 last_applied_seq, '
    'fresh 엔진 전량 재생이면 0이다. fp_replay의 from 성분과 같다.';

COMMENT ON COLUMN recovery_runs.replayed_to_seq IS
    '재생 종점 seq(저널 head). fp_replay의 to 성분과 같다. '
    'from과 같으면 재생할 것이 없었다는 뜻이고 그것은 결정론 증명이 아니다.';
