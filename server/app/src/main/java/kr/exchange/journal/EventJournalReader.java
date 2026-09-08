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
