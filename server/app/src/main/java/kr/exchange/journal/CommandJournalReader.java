package kr.exchange.journal;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    /**
     * 상한을 건 조회. 재생이 체크포인트를 넘어가지 않게 한다.
     *
     * 상한이 없으면 리더가 계속 쓰는 동안 재생이 끝나지 않고, 이벤트가 아직 없는 명령까지 읽어
     * 크래시 창으로 오판한다(JournalCheckpoint 참조).
     *
     * upToSeq 이 음수면 상한 없이 읽는다. 0 은 상한 없음이 아니라 '안정 구간이 없다' 이므로
     * 빈 결과여야 한다. 둘을 같은 값으로 두면 저널이 비었을 때 상한이 통째로 사라진다.
     */
    public List<JournalEntry> readRange(long lastAppliedSeq, long upToSeq, int limit) {
        if (upToSeq < 0) {
            return readAfter(lastAppliedSeq, limit);
        }
        return read("WHERE seq > ? AND seq <= ? ORDER BY seq LIMIT ?", lastAppliedSeq, upToSeq, limit);
    }

    /** lastAppliedSeq 초과분을 seq 오름차순으로 반환한다. limit 단위로 반복 호출한다. */
    public List<JournalEntry> readAfter(long lastAppliedSeq, int limit) {
        return read("WHERE seq > ? ORDER BY seq LIMIT ?", lastAppliedSeq, limit);
    }

    /**
     * 한 마켓의 명령만 seq 오름차순으로 읽는다(ADR 0021 4단계).
     *
     * 마켓별 재생의 입력이다. seq 는 전역 채번이라 이 스트림에는 구멍이 뚫려 있다.
     * 그 구멍은 다른 마켓의 명령이지 결번이 아니다. 곧 이 축에서 seq 연속성을
     * 검사하면 안 된다. 마켓 안의 순서만 보장되면 재생이 성립한다.
     */
    public List<JournalEntry> readAfterForMarket(long lastAppliedSeq, int marketId,
                                                 long upToSeq, int limit) {
        if (upToSeq < 0) {
            return read("WHERE seq > ? AND market_id = ? ORDER BY seq LIMIT ?",
                    lastAppliedSeq, marketId, limit);
        }
        return read("WHERE seq > ? AND market_id = ? AND seq <= ? ORDER BY seq LIMIT ?",
                lastAppliedSeq, marketId, upToSeq, limit);
    }

    /**
     * 한 명령의 열 목록. 이 이름들이 곧 아래 mapEntry 의 계약이다.
     *
     * 정산이 이 목록을 자기 질의에 붙여 쓴다. 열 이름과 매핑을 한 곳에 두어야 둘이 갈리지
     * 않는다. 별칭 payload 는 payload::text 의 결과다.
     */
    public static final String ENTRY_COLUMNS = """
            seq, market_id, command_type, user_id, client_order_id,
            assigned_order_id, engine_ts, tie_break, payload::text AS payload,
            fencing_token, checksum, created_at""";

    /** ENTRY_COLUMNS 를 고른 결과 한 행을 명령으로 옮긴다. 호출측이 다른 질의에 붙여 쓴다. */
    public static JournalEntry mapEntry(ResultSet rs) throws SQLException {
        return new JournalEntry(
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
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private List<JournalEntry> read(String tail, Object... params) {
        return jdbc.sql("SELECT " + ENTRY_COLUMNS + " FROM command_journal " + tail)
                .params(params)
                .query((rs, rowNum) -> mapEntry(rs))
                .list();
    }
}
