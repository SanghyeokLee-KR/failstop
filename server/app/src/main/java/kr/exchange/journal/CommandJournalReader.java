package kr.exchange.journal;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

/** command_journal 순서 보장 조회. catch-up과 재생 복구가 같은 경로를 쓴다. */
public class CommandJournalReader {

    private final JdbcClient jdbc;

    public CommandJournalReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 현재 저널 head. 승격 시 인메모리 엔진이 head에 도달했는지 판정에 쓴다(빈 저널이면 0). */
    public long maxSeq() {
        Long v = jdbc.sql("SELECT COALESCE(max(seq), 0) FROM command_journal")
                .query(Long.class).single();
        return v == null ? 0 : v;
    }

    /** lastAppliedSeq 초과분을 seq 오름차순으로 반환한다. limit 단위로 반복 호출한다. */
    public List<JournalEntry> readAfter(long lastAppliedSeq, int limit) {
        return jdbc.sql("""
                        SELECT seq, market_id, command_type, user_id, client_order_id,
                               assigned_order_id, engine_ts, tie_break, payload::text AS payload,
                               fencing_token, checksum, created_at
                        FROM command_journal
                        WHERE seq > ?
                        ORDER BY seq
                        LIMIT ?
                        """)
                .params(lastAppliedSeq, limit)
                .query((rs, rowNum) -> new JournalEntry(
                        rs.getLong("seq"),
                        rs.getInt("market_id"),
                        CommandDraft.CommandType.valueOf(rs.getString("command_type")),
                        rs.getLong("user_id"),
                        rs.getString("client_order_id"),
                        rs.getLong("assigned_order_id"),
                        rs.getLong("engine_ts"),
                        rs.getLong("tie_break"),
                        rs.getString("payload"),
                        rs.getLong("fencing_token"),
                        rs.getLong("checksum"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
