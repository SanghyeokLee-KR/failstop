package kr.exchange.journal;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

/** event_journal 순서 보장 조회. 재생 prefix 대조와 검증 테스트가 같은 경로를 쓴다. */
public class EventJournalReader {

    private final JdbcClient jdbc;

    public EventJournalReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 연속 물질화 경계. 이 seq 이하 모든 명령의 이벤트가 저널에 전부 있다.
     *
     * max(source_cmd_seq) 가 아닌 이유가 있다. 명령 저널은 접수 시점에 커밋되고 이벤트는
     * 마켓별 라이터가 각자 정산 트랜잭션에서 적재하므로, 마켓 A 의 seq N 이 아직 정산 전인데
     * 마켓 B 의 N+1 이 먼저 커밋될 수 있다. max 를 상한으로 쓰면 창 안에 그 구멍이 남고
     * 대조가 짝을 잘못 잡아 거짓 불일치를 낸다. 실측은
     * harness/evidence/materialization-holes-20260912/ 다.
     *
     * 경계는 첫 미물질화 명령 바로 앞이다. 구멍이 없으면 명령 head 가 경계다.
     * fromCmdSeq 는 성능 하한이다. 이미 재생한 구간을 다시 훑지 않는다.
     */
    public JournalCheckpoint materializedThrough(long fromCmdSeq) {
        Long v = jdbc.sql("""
                        SELECT COALESCE(
                          (SELECT min(c.seq) - 1 FROM command_journal c
                            WHERE c.seq > ?
                              AND NOT EXISTS (SELECT 1 FROM event_journal e
                                               WHERE e.source_cmd_seq = c.seq)),
                          (SELECT COALESCE(max(c2.seq), ?) FROM command_journal c2
                            WHERE c2.seq > ?),
                          ?)
                        """)
                .param(fromCmdSeq).param(fromCmdSeq).param(fromCmdSeq).param(fromCmdSeq)
                .query(Long.class)
                .single();
        return new JournalCheckpoint(v == null ? fromCmdSeq : v);
    }

    /**
     * (source_cmd_seq, event_ordinal) 초과분을 그 순서로 반환한다. limit 단위로 반복 호출한다.
     *
     * V36 이전에는 event_seq 오름차순이었다. 그 축이 명령 순서와 같다는 전제 위에 있었고,
     * 재생이 명령을 seq 순서로 재적용하며 이벤트를 위치로 1 대 1 대조하기 때문에 그 전제가
     * 필요했다. V36 에서 event_seq 가 marketId 대역 파생이 되면서 그 전제가 깨졌다.
     * 마켓이 섞이면 event_seq 오름차순과 명령 순서가 갈린다.
     *
     * 그래서 축을 (source_cmd_seq, event_ordinal) 로 옮긴다. 이 축은 채번 방식과 무관하게
     * 명령 재적용 순서와 정확히 같다. 대조의 짝이 원래 이것이었고 event_seq 는 대리였다.
     */
    /**
     * 한 마켓의 이벤트만 (source_cmd_seq, event_ordinal) 오름차순으로 읽는다(ADR 0021 4단계).
     *
     * market_seq 오름차순과 같은 순서다. 마켓 안에는 라이터가 하나라 두 축이 함께 증가한다.
     * 대조 짝은 명령 축이므로 정렬은 명령 축으로 둔다.
     * V36 이전 행은 market_id 가 비어 있어 이 읽기에 들어오지 않는다.
     */
    public List<EventJournalRow> readAfterForMarket(long cmdSeq, int ordinal,
                                                    int marketId, int limit) {
        return jdbc.sql("""
                        SELECT event_seq, source_cmd_seq, event_ordinal, event_type,
                               payload::text AS payload, payload_schema_version,
                               fencing_epoch, created_at
                        FROM event_journal
                        WHERE market_id = ? AND (source_cmd_seq, event_ordinal) > (?, ?)
                        ORDER BY source_cmd_seq, event_ordinal
                        LIMIT ?
                        """)
                .params(marketId, cmdSeq, ordinal, limit)
                .query((rs, rowNum) -> new EventJournalRow(
                        rs.getLong("event_seq"),
                        rs.getLong("source_cmd_seq"),
                        rs.getInt("event_ordinal"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getShort("payload_schema_version"),
                        rs.getLong("fencing_epoch"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public List<EventJournalRow> readAfter(long cmdSeq, int ordinal, int limit) {
        return jdbc.sql("""
                        SELECT event_seq, source_cmd_seq, event_ordinal, event_type,
                               payload::text AS payload, payload_schema_version,
                               fencing_epoch, created_at
                        FROM event_journal
                        WHERE (source_cmd_seq, event_ordinal) > (?, ?)
                        ORDER BY source_cmd_seq, event_ordinal
                        LIMIT ?
                        """)
                .params(cmdSeq, ordinal, limit)
                .query((rs, rowNum) -> new EventJournalRow(
                        rs.getLong("event_seq"),
                        rs.getLong("source_cmd_seq"),
                        rs.getInt("event_ordinal"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getShort("payload_schema_version"),
                        rs.getLong("fencing_epoch"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
