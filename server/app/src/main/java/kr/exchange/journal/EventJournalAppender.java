package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.core.EngineEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * event_journal 저널 전용 append 경로(DB 설계 v2.11 §3.1 6.2).
 *
 * 한 트랜잭션 안에서 [assert_current_leader → leader_lease FOR SHARE 재검증 →
 * next_journal_value('event_seq') 블록 채번 → 이벤트별 INSERT 반복]을 원자 수행한다.
 * JDBC 배치가 아니라 왕복 N회다. batchUpdate 전환은 실측 병목 확인 후에 한다.
 * created_at은 이벤트의 engine_ts 파생값을 명시 삽입한다(DB now() 금지, P0-4).
 * event_ordinal 중복은 event_emission_registry 트리거가 23505로 물리 차단한다.
 *
 * 운영 sink는 M1-3부터 SettlementService다(이벤트 적재와 정산의 원자 커밋, §5.2).
 * 이 클래스는 정산 없이 저널 계층만 검증하는 테스트 경로로 남는다. TRADE payload의
 * makerFee/takerFee가 자리표시 "0"으로 남는 것도 그 용도 한정이다.
 */
public class EventJournalAppender {

    private static final short PAYLOAD_SCHEMA_VERSION = 1;

    private static final String SELECT_EPOCH_SQL = """
            SELECT epoch FROM leader_lease
            WHERE resource = 'matching-engine'
              AND holder = ? AND epoch = ? AND lease_until > clock_timestamp()
            FOR SHARE
            """;

    private static final String INSERT_SQL = """
            INSERT INTO event_journal
              (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
               payload_schema_version, fencing_epoch, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final LeaderLeaseService lease;
    private final ObjectMapper objectMapper;

    public EventJournalAppender(JdbcClient jdbc, TransactionTemplate tx,
                                LeaderLeaseService lease, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.lease = lease;
        this.objectMapper = objectMapper;
    }

    /** 배치를 원자 커밋하고 event_seq가 부여된 이벤트 목록을 반환한다. */
    public List<EngineEvent> append(List<EngineEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        LeaderIdentity leader = lease.requireLeader();
        try {
            return tx.execute(status -> appendInTx(leader, events));
        } catch (StaleLeaderException e) {
            // fencing 확정 거부는 renew 틱을 기다리지 않고 즉시 강등한다(§5.4).
            lease.demote(e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            if (FencingErrors.isFencingRejection(e)) {
                lease.demote("assert_current_leader 거부(event append)");
            }
            throw e;
        }
    }

    private List<EngineEvent> appendInTx(LeaderIdentity leader, List<EngineEvent> events) {
        jdbc.sql("SELECT assert_current_leader(?, ?)")
                .params(leader.holder(), leader.epoch())
                .query((rs, rowNum) -> 0)
                .list();

        // FOR SHARE 행 잠금으로 커밋까지 epoch 불변을 강제한다. 0행이면 stale 확정.
        Long epoch = jdbc.sql(SELECT_EPOCH_SQL)
                .params(leader.holder(), leader.epoch())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (epoch == null) {
            throw new StaleLeaderException(
                    "stale 리더 event append 거부: holder=" + leader.holder() + ", epoch=" + leader.epoch());
        }

        long start = jdbc.sql("SELECT next_journal_value('event_seq', ?)")
                .params(events.size())
                .query(Long.class)
                .single();

        List<EngineEvent> assigned = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            EngineEvent event = events.get(i).withEventSeq(start + i);
            jdbc.sql(INSERT_SQL)
                    .params(event.eventSeq(), event.sourceCmdSeq(), event.eventOrdinal(),
                            event.type().name(), EventPayloads.json(event, objectMapper),
                            PAYLOAD_SCHEMA_VERSION, epoch, toUtc(event.engineTs()))
                    .update();
            assigned.add(event);
        }
        return assigned;
    }

    private static OffsetDateTime toUtc(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }
}
