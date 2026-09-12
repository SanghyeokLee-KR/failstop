package kr.exchange.journal;

/**
 * 재생 상한. 연속 물질화 경계다.
 *
 * 왜 필요한가. 재생은 command_journal 을 읽어 엔진에 적용하면서 그 결과를 event_journal 과
 * 대조한다. 상한이 없으면 리더가 계속 쓰는 동안 항상 "명령은 있는데 이벤트가 아직 없는" 구간이
 * 남고, 재생하는 쪽은 그것을 자기 크래시 창으로 읽는다. 그러면 리더 자격을 요구하게 되고,
 * 유효한 타 리더가 있는 노드는 기동을 거부한다. 2026-09-08 두 노드 실측에서 그 경로로
 * 장애 노드가 재합류하지 못했다(`harness/evidence/leader-failover-20260908/`).
 *
 * 왜 max(source_cmd_seq) 가 아닌가. 명령 저널은 접수 시점에 커밋되고 이벤트는 마켓별 라이터가
 * 각자 정산 트랜잭션에서 적재한다. 곧 마켓 A 의 seq N 이 아직 정산 전인데 마켓 B 의 N+1 이
 * 먼저 커밋될 수 있다. max 를 상한으로 쓰면 창 안에 그 구멍이 남고 대조가 짝을 잘못 잡는다.
 * 실측은 `harness/evidence/materialization-holes-20260912/` 다.
 *
 * 이벤트 축 값을 들지 않는다. 재생 커서의 축이 (source_cmd_seq, event_ordinal) 이므로 상한도
 * 명령 축이어야 한다. 종전에는 event_seq 를 함께 들었고 그 값이 커서 시작점 자리에 들어가
 * 대조가 판정 대상 0 건으로 돌았다.
 *
 * @param commandSeq 이 seq 이하 모든 명령의 이벤트가 저널에 전부 있다. 빈 저널이면 0
 */
public record JournalCheckpoint(long commandSeq) {

    public static final JournalCheckpoint EMPTY = new JournalCheckpoint(0);

    public JournalCheckpoint {
        if (commandSeq < 0) {
            throw new IllegalArgumentException("체크포인트는 음수일 수 없다");
        }
    }
}
