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
     * 재생 상한. 이벤트가 전부 적재된 마지막 명령과 그 명령의 마지막 이벤트를 한 문장으로 읽는다.
     *
     * 한 문장이어야 하는 이유가 있다. 두 값을 따로 읽으면 그 사이에 리더의 커밋이 끼어 명령 상한과
     * 이벤트 상한이 어긋나고, 이벤트 커서가 재생이 만들지 않은 행을 만나 재생 불일치로 오판한다.
     */
    public JournalCheckpoint checkpoint() {
        return jdbc.sql("""
                        SELECT COALESCE(max(source_cmd_seq), 0) AS cmd_seq,
                               COALESCE(max(event_seq), 0) AS evt_seq
                        FROM event_journal
                        """)
                .query((rs, rowNum) -> new JournalCheckpoint(
                        rs.getLong("cmd_seq"), rs.getLong("evt_seq")))
                .single();
    }

    /**
     * 주어진 명령까지의 이벤트 워터마크. 이어서 재생할 때 이벤트 커서를 어디에 둘지 정한다.
     * 상태를 들고 다니지 않고 매번 유도해야 재기동·건너뜀에 흔들리지 않는다.
     */
    public long watermark(long commandSeq) {
        Long v = jdbc.sql("SELECT COALESCE(max(event_seq), 0) FROM event_journal "
                        + "WHERE source_cmd_seq <= ?")
                .param(commandSeq)
                .query(Long.class)
                .single();
        return v == null ? 0 : v;
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
