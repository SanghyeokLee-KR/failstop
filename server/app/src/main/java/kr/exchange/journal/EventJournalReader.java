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

    /** eventSeq 초과분을 event_seq 오름차순으로 반환한다. limit 단위로 반복 호출한다. */
    public List<EventJournalRow> readAfter(long eventSeq, int limit) {
        return jdbc.sql("""
                        SELECT event_seq, source_cmd_seq, event_ordinal, event_type,
                               payload::text AS payload, payload_schema_version,
                               fencing_epoch, created_at
                        FROM event_journal
                        WHERE event_seq > ?
                        ORDER BY event_seq
                        LIMIT ?
                        """)
                .params(eventSeq, limit)
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
