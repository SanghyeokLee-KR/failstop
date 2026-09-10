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
 * 한 트랜잭션 안에서 [assert_and_lock_leader(검증+FOR SHARE 잠금 한 왕복) →
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

    /**
     * fencing 검증·행 잠금·epoch 반환을 한 왕복으로 한다(V30 assert_and_lock_leader).
     *
     * 종전에는 assert_current_leader 호출과 FOR SHARE 조회를 따로 보냈다. 앞이 거부
     * 사유를, 뒤가 커밋까지의 보장을 담당해 둘 다 필요했다. 함수가 둘을 겸하므로 왕복이
     * 하나 준다. 거부 시 예외 메시지가 종전과 같아 FencingErrors 분류가 바뀌지 않는다.
     */
    private static final String ASSERT_AND_LOCK_SQL =
            "SELECT assert_and_lock_leader(?, ?)";

    private static final String INSERT_SQL = """
            INSERT INTO event_journal
              (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
               payload_schema_version, fencing_epoch, created_at, market_id, market_seq)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

    /** SettlementService 와 같은 대역이어야 한다. 두 경로가 같은 저널에 쓴다. */
    private static final long MARKET_SEQ_BAND = 10_000_000L;

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
            // fencing 거부는 리스 권위를 재확인한 뒤에만 강등한다(§5.4 강등 사유 분류).
            lease.demoteUnlessStillLeader(e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            if (FencingErrors.isFencingRejection(e)) {
                lease.demoteUnlessStillLeader("assert_current_leader 거부(event append): "
                        + FencingErrors.rejectionDetail(e));
            }
            throw e;
        }
    }

    private List<EngineEvent> appendInTx(LeaderIdentity leader, List<EngineEvent> events) {
        // fencing 검증과 FOR SHARE 잠금을 한 왕복으로 한다(V30). 종전에는 두 문장을 연달아
        // 보냈다. 앞 문장이 거부 사유를 주고 뒤 문장이 커밋까지의 보장을 주는 구조였는데,
        // 함수가 둘 다 하므로 왕복 하나가 준다. 거부 메시지는 종전과 같아 강등 분류가 그대로다.
        // 잠금을 잡은 뒤 판정하므로 판정과 커밋 사이에 epoch 이 바뀌지 않는다.
        Long epoch = jdbc.sql(ASSERT_AND_LOCK_SQL)
                .params(leader.holder(), leader.epoch())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (epoch == null) {
            throw new StaleLeaderException(
                    "stale 리더 event append 거부: holder=" + leader.holder() + ", epoch=" + leader.epoch());
        }

        // V36. 채번이 마켓별이다. 배치는 명령 하나의 방출분이라 전부 같은 마켓이다.
        // SettlementService 와 같은 규약을 쓴다. 두 경로가 같은 저널에 쓰므로 어긋나면
        // 한쪽이 만든 행에 market_seq 가 비고 마켓별 gapless 검사가 그 행을 놓친다.
        int marketId = events.get(0).marketId();
        for (EngineEvent e : events) {
            if (e.marketId() != marketId) {
                throw new IllegalArgumentException(
                        "이벤트 배치가 마켓을 넘는다: " + marketId + " vs " + e.marketId());
            }
        }
        long marketStart = jdbc.sql("SELECT next_market_journal_value(?::smallint, 'event_seq', ?)")
                .params(marketId, events.size())
                .query(Long.class)
                .single();

        List<EngineEvent> assigned = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            long marketSeq = marketStart + i;
            EngineEvent event = events.get(i)
                    .withEventSeq(marketId * MARKET_SEQ_BAND + marketSeq);
            jdbc.sql(INSERT_SQL)
                    .params(event.eventSeq(), event.sourceCmdSeq(), event.eventOrdinal(),
                            event.type().name(), EventPayloads.json(event, objectMapper),
                            PAYLOAD_SCHEMA_VERSION, epoch, toUtc(event.engineTs()),
                            (short) marketId, marketSeq)
                    .update();
            assigned.add(event);
        }
        return assigned;
    }

    private static OffsetDateTime toUtc(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }
}
