package kr.exchange.journal;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntPredicate;

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
     * 마켓별 연속 물질화 경계. 정의와 레거시 경계 아래를 훑지 않는 근거는 DB 설계 §5.5-a 다.
     * 결과에는 cursors 의 마켓과 명령이 있는 마켓이 모두 담긴다. cursors 에 없는 마켓의 커서는 0 이다.
     *
     * 마켓마다 한 문장이다. 전 마켓을 한 문장에 두면 마켓 집합의 행 수를 과대 추정해 JIT 가 켜지고,
     * 커서가 head 근처인 정상 상태가 수백 ms 로 늘어난다(harness/evidence/replay-reader-plans-20260913/).
     */
    public JournalCheckpoints materializedThroughByMarket(Map<Integer, Long> cursors) {
        return materializedThroughByMarket(cursors, marketId -> true);
    }

    /**
     * include 가 받는 마켓만 경계를 읽는다. 결과에 없는 마켓의 경계는 판독하지 않은 것이다. 팔로워가 이 노드가 쥔 파티션의 마켓을
     * 훑지 않게 한다. 인계한 마켓의 커서는 인계 시점에 멈춰 있어 그 경계 질의가 그 뒤 쌓인 명령을 매번 훑는다.
     */
    public JournalCheckpoints materializedThroughByMarket(Map<Integer, Long> cursors, IntPredicate include) {
        long legacyBound = legacyBound();
        TreeSet<Integer> markets = new TreeSet<>(cursors.keySet());
        markets.addAll(jdbc.sql(CommandJournalReader.MARKET_IDS_CTE
                        + "SELECT id FROM market_ids WHERE id IS NOT NULL")
                .query(Integer.class)
                .list());
        markets.removeIf(marketId -> !include.test(marketId));
        Map<Integer, Long> bounds = new HashMap<>();
        for (int marketId : markets) {
            long floor = Math.max(cursors.getOrDefault(marketId, 0L), legacyBound);
            // 구멍 탐색과 head 대체값을 한 문장에 둔다. 둘을 따로 읽으면 사이에 접수된 미물질화 명령이
            // head 로 잡혀 상한이 그 명령 위로 올라간다.
            bounds.put(marketId, jdbc.sql(String.format(Locale.ROOT, """
                            SELECT COALESCE(
                              (SELECT c.seq - 1 FROM command_journal c
                                WHERE c.market_id = %d AND c.seq > %d
                                  AND NOT EXISTS (SELECT 1 FROM event_journal e
                                                   WHERE e.source_cmd_seq = c.seq)
                                ORDER BY c.seq
                                LIMIT 1),
                              GREATEST((SELECT max(c2.seq) FROM command_journal c2
                                         WHERE c2.market_id = %d), %d))
                            """, marketId, floor, marketId, floor))
                    .query(Long.class)
                    .single());
        }
        return new JournalCheckpoints(bounds, legacyBound);
    }

    /** journal_axis_boundary('pre_v36_event'). 이 이하 명령의 이벤트는 market_id 가 비어 있을 수 있다. */
    public long legacyBound() {
        return jdbc.sql("SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")
                .query(Long.class)
                .single();
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
     * 레거시 패스가 없어 market_id 가 빈 V36 전 행은 이 읽기에 들어오지 않는다.
     */
    public List<EventJournalRow> readAfterForMarket(long cmdSeq, int ordinal,
                                                    int marketId, int limit) {
        return readAfterForMarket(cmdSeq, ordinal, marketId, 0, limit);
    }

    /**
     * 레거시 패스를 더한 마켓 판독. legacyBound 는 JournalCheckpoints.legacyBound() 다.
     *
     * source_cmd_seq 가 legacyBound 이하인 V36 전 이벤트는 market_id 가 비어 있다. 명령의
     * market_id 는 NOT NULL 이므로 command_journal 조인으로 마켓을 짝짓는다. 두 패스를 합쳐 같은
     * 축으로 정렬하므로 커서가 경계를 넘는 배치도 한 번에 읽는다.
     *
     * 커서 seq 가 경계와 같으면 레거시 패스를 건너뛰지 않는다. 경계 명령의 남은 ordinal 이 있다.
     *
     * 마켓 id 는 리터럴로 넣는다. psql 준비문 실측에서 인자로 둔 문장은 generic 계획으로 굳어 작은 마켓에서
     * idx_evt_market_cmd 를 버리고 배치마다 다른 마켓 행을 수만 건 걸렀다(harness/evidence/replay-reader-plans-20260913/).
     */
    public List<EventJournalRow> readAfterForMarket(long cmdSeq, int ordinal, int marketId,
                                                    long legacyBound, int limit) {
        String sql;
        Object[] params;
        if (cmdSeq > legacyBound) {
            sql = String.format(Locale.ROOT, """
                    SELECT event_seq, source_cmd_seq, event_ordinal, event_type,
                           payload::text AS payload, payload_schema_version,
                           fencing_epoch, created_at
                    FROM event_journal
                    WHERE market_id = %d AND (source_cmd_seq, event_ordinal) > (?, ?)
                    ORDER BY source_cmd_seq, event_ordinal
                    LIMIT ?
                    """, marketId);
            params = new Object[] {cmdSeq, ordinal, limit};
        } else {
            // c.seq 범위는 e 쪽 술어와 같은 구간이다. 부등식은 조인 등가로 전파되지 않아, 이것이 없으면
            // command_journal 에서 출발하는 계획이 idx_cmd_market 으로 그 마켓 명령 전부를 읽는다.
            sql = String.format(Locale.ROOT, """
                    SELECT event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                           payload_schema_version, fencing_epoch, created_at
                    FROM (
                        (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
                                e.payload::text AS payload, e.payload_schema_version,
                                e.fencing_epoch, e.created_at
                         FROM event_journal e
                         JOIN command_journal c ON c.seq = e.source_cmd_seq
                         WHERE e.market_id IS NULL AND c.market_id = %d
                           AND e.source_cmd_seq <= ? AND c.seq BETWEEN ? AND ?
                           AND (e.source_cmd_seq, e.event_ordinal) > (?, ?)
                         ORDER BY e.source_cmd_seq, e.event_ordinal
                         LIMIT ?)
                        UNION ALL
                        (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
                                e.payload::text AS payload, e.payload_schema_version,
                                e.fencing_epoch, e.created_at
                         FROM event_journal e
                         WHERE e.market_id = %d AND (e.source_cmd_seq, e.event_ordinal) > (?, ?)
                         ORDER BY e.source_cmd_seq, e.event_ordinal
                         LIMIT ?)
                    ) u
                    ORDER BY source_cmd_seq, event_ordinal
                    LIMIT ?
                    """, marketId, marketId);
            params = new Object[] {legacyBound, cmdSeq, legacyBound, cmdSeq, ordinal, limit,
                    cmdSeq, ordinal, limit, limit};
        }
        return jdbc.sql(sql)
                .params(params)
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
