-- recovery_runs 컬럼 정본을 DB 자체에 남긴다.
--
-- 왜 필요한가. V3의 테이블 위 주석(V3:24-26)이 fp_replay를 "재생 상태 해시", fp_live를
-- "최신 스냅샷 상태 해시"로 서술하고 둘을 대조한 결과가 matched라고 적는다. 구현은 그렇지
-- 않다. 그 파일은 적용된 마이그레이션이라 고치면 Flyway 체크섬이 어긋나 기동이 막힌다.
--
-- V3에는 COMMENT ON이 없어 DB 쪽에는 아예 컬럼 주석이 없었다. 이 파일이 그것을 신설한다.
-- 덮는 것이 아니라 세우는 것이다. 문서만 고치면 psql \d+로 스키마를 읽는 사람에게는
-- 아무 서술도 없고, V3 파일을 여는 사람은 옛 서술을 본다. 후자는 체크섬 때문에 남는다.
-- DB§5.5 v2.37이 본 서술의 원천이다.
--
-- 이 파일 자신이 그 규칙에 한 번 걸렸다(2026-08-30 실측). 적용 후 위 주석을 고쳤더니
-- Migration checksum mismatch로 전 노드 기동이 막혔다. 체크섬은 SQL이 아니라 파일 전체를
-- 본다. 주석 한 줄도 수정이다. 데모 스택은 flyway_schema_history 행을 지우고 재기동해
-- 복구했는데, COMMENT ON이 멱등이라 통한 것이고 DDL이 있으면 그 수단은 실패한다.
COMMENT ON COLUMN recovery_runs.fp_replay IS
    '재생 창 식별자. 성공 시 seq:{from}-{to}:ev:{n}, 불일치 시 리터럴 MISMATCH. '
    '대조 대상이 아니다. 결정론 판정은 ReplayService.rebuild가 재생 이벤트를 event_journal '
    '행과 하나씩 대조해 내고 matched가 그 결과다(DB 설계 v2.37).';

COMMENT ON COLUMN recovery_runs.fp_live IS
    '성공 시 fp_replay와 같은 값, 불일치 시 예외 요약(VARCHAR(64) 절단). '
    '기록자가 성공 경로에서 두 값을 같게 적으므로 fp 비교로는 matched가 항상 참이다. '
    'C17의 실질 판정력은 불일치 기록 경로가 도달하는가에 있다.';

COMMENT ON COLUMN recovery_runs.matched IS
    '재생이 예외 없이 끝났는가. 재생 0건도 true라 그것만으로 결정론 증명을 주장하지 않는다. '
    'verified_events가 fp_replay에 실려 사후에 그 구분이 선다.';

COMMENT ON COLUMN recovery_runs.market_id IS
    '0 = 전 마켓 통합 재생. 단일 라이터가 전 마켓을 한 저널로 재생하므로 마켓별 분해가 '
    '원천에 없다. FK가 아니며 markets.id로 조인하지 않는다.';
