package kr.exchange.engine.lease;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * S2 바이너리(작업 브랜치 b04acd5)가 보내던 SQL(ADR 0034 S3 설계 4-1·6-3). S3 이미지에서 S2 이미지로 되돌리는 경로와 P2 에서
 * P1 로 내린 뒤의 경로(결정 8, 시험 R1)가 이 문장들로 돈다. V50 이 바꾼 함수(3인자 획득, 2인자 게시, 6인자 머리말, 저널 행
 * 함수)를 부르므로 P1 에서 V48 과 같은 결과인지 LegacySqlCompatibilityTest 가 보고, 내린 뒤의 DB 에서 도는지 ProtocolRollbackR1Test
 * 가 본다. 문장은 b04acd5 의 PostgresLeaderLeaseStore·PostgresFencingEpochPublisher·CommandJournalAppender·SettlementService·
 * EventJournalAppender 에서 그대로 옮겼다. 바꾸지 않는다.
 */
final class S2BinarySql {

    static final String RESOURCE = "matching-engine";

    // PostgresLeaderLeaseStore
    static final String ACQUIRE = "SELECT try_acquire_lease(?, ?, ? * interval '1 millisecond')";
    static final String RENEW = "SELECT renew_lease(?, ?, ?, ? * interval '1 millisecond')";
    static final String RELEASE = "SELECT release_lease(?, ?, ?)";
    static final String CURRENT = "SELECT holder, epoch, lease_until FROM leader_lease WHERE resource = ?";

    // CommandJournalAppender.PROLOGUE_SQL·INSERT_SQL
    static final String APPEND_PROLOGUE = "SELECT out_seq, out_replayed, out_fencing, out_order_id"
            + " FROM append_command_prologue(?, ?, ?, ?, ?, ?::smallint)";
    static final String APPEND_INSERT =
            "SELECT append_command_journal_row(?, ?::smallint, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)";

    // SettlementService.PROLOGUE_SQL(CommandJournalReader.ENTRY_COLUMNS 를 펼친 문장)
    static final String SETTLE_PROLOGUE =
            "SELECT assert_and_lock_market_leader(COALESCE(market_id, ?::smallint), ?, ?) AS epoch,"
            + " (SELECT count(*) FROM event_emission_registry"
            + "   WHERE source_cmd_seq = ?) AS registered,"
            + " seq, market_id, command_type, user_id, client_order_id,"
            + " assigned_order_id, engine_ts, tie_break, payload::text AS payload,"
            + " fencing_token, checksum, created_at"
            + " FROM (VALUES (1)) AS one(x)"
            + " LEFT JOIN command_journal ON seq = ?";

    // EventJournalAppender.ASSERT_AND_LOCK_SQL
    static final String EVENT_LOCK = "SELECT assert_and_lock_market_leader(?::smallint, ?, ?)";

    // PostgresFencingEpochPublisher
    static final String PUBLISH = "SELECT publish_leader_epoch(?, ?)";

    private S2BinarySql() {
    }

    /** S2 바이너리의 리스 보관소. 획득·갱신·반납·조회를 위 문장으로 한다. 다운타임 게이지는 쓰지 않아 0 이다. */
    static final class LeaseStore implements LeaderLeaseStore {

        private final JdbcClient jdbc;

        LeaseStore(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            return jdbc.sql(ACQUIRE).params(RESOURCE, holder, ttl.toMillis()).query(Long.class).optional();
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            return Boolean.TRUE.equals(jdbc.sql(RENEW).params(RESOURCE, holder, epoch, ttl.toMillis())
                    .query(Boolean.class).single());
        }

        @Override
        public void release(String holder, long epoch) {
            jdbc.sql(RELEASE).params(RESOURCE, holder, epoch).query(Boolean.class).single();
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            return jdbc.sql(CURRENT).param(RESOURCE)
                    .query((rs, n) -> new LeaseSnapshot(rs.getString("holder"), rs.getLong("epoch"),
                            rs.getObject("lease_until", OffsetDateTime.class).toInstant()))
                    .optional();
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public java.time.Instant now() {
            return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
        }

        @Override
        public String describe() {
            return "s2-sql(leader_lease)";
        }
    }
}
