package kr.exchange.journal;

/**
 * 재생 상한. event_journal 이 완전히 물질화한 지점이다.
 *
 * 왜 필요한가. 재생은 command_journal 을 읽어 엔진에 적용하면서 그 결과를 event_journal 과
 * 대조한다. 상한이 없으면 리더가 계속 쓰는 동안 항상 "명령은 있는데 이벤트가 아직 없는" 구간이
 * 남고, 재생하는 쪽은 그것을 자기 크래시 창으로 읽는다. 그러면 리더 자격을 요구하게 되고,
 * 유효한 타 리더가 있는 노드는 기동을 거부한다. 2026-09-08 두 노드 실측에서 그 경로로
 * 장애 노드가 재합류하지 못했다(`harness/evidence/leader-failover-20260908/`).
 *
 * 두 값을 한 문장으로 함께 읽어야 한다. 따로 읽으면 그 사이의 커밋이 끼어 상한이 어긋나고,
 * 이벤트 커서가 재생이 만들지 않은 행을 만나 재생 불일치로 오판한다.
 *
 * 이벤트가 명령 단위로 원자 커밋되므로 `commandSeq` 이하의 명령은 이벤트가 전부 있다.
 * `eventSeq` 는 그 명령의 마지막 이벤트다. event_seq 가 source_cmd_seq 오름차순으로
 * 결번 없이 배정되기 때문에 둘이 정확히 짝이 된다.
 *
 * @param commandSeq 이벤트가 전부 적재된 마지막 명령의 seq. 빈 저널이면 0
 * @param eventSeq   그 명령의 마지막 event_seq. 빈 저널이면 0
 */
public record JournalCheckpoint(long commandSeq, long eventSeq) {

    public static final JournalCheckpoint EMPTY = new JournalCheckpoint(0, 0);

    public JournalCheckpoint {
        if (commandSeq < 0 || eventSeq < 0) {
            throw new IllegalArgumentException("체크포인트는 음수일 수 없다");
        }
    }
}
