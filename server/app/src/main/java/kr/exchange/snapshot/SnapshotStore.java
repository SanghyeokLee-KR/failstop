package kr.exchange.snapshot;

import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.SnapshotCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * snapshots 테이블 읽기·쓰기(복구 RTO 설계 §3-b). append-only(V1 트리거)라 UPDATE·DELETE가
 * 없고, 손상·구식 스냅샷은 삭제가 아니라 채택 게이트에서 무시한다.
 *
 * 세트 원자성: 한 스냅샷 세트(동일 last_applied_seq의 마켓 N행)를 단일 트랜잭션으로 INSERT한다.
 * 부분 세트가 관측되지 않으므로 로더는 완전 세트만 읽는다. 세트 키 last_applied_seq는 단일
 * 라이터 경계라 단조 증가한다.
 *
 * 채택 게이트: format_version·engine_version·config_hash가 현재 런타임 값과 일치하고, 디코딩한
 * 각 마켓 북의 state_checksum이 기록값과 일치해야 한다. 하나라도 어긋나면 그 세트 전체를 폐기하고
 * 다음(더 낮은) 세트를, 없으면 empty를 반환해 seq 0 전량 재생으로 폴백한다(§3-b 단일 정본 규율).
 */
public class SnapshotStore implements SnapshotSource {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private static final String INSERT_SQL = """
            INSERT INTO snapshots
              (market_id, last_applied_seq, state, state_checksum,
               engine_version, config_hash, format_version, fencing_epoch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String engineVersion;
    private final String configHash;

    public SnapshotStore(JdbcClient jdbc, TransactionTemplate tx,
                         String engineVersion, String configHash) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.engineVersion = engineVersion;
        this.configHash = configHash;
    }

    /** 한 세트를 단일 트랜잭션으로 적재한다. 워커 스레드(라이터 밖)에서만 호출한다. */
    public void persist(long lastAppliedSeq, long fencingEpoch, List<MarketState> markets) {
        tx.executeWithoutResult(status -> {
            for (MarketState m : markets) {
                jdbc.sql(INSERT_SQL)
                        .params((short) m.marketId(), lastAppliedSeq, m.state(), m.checksum(),
                                engineVersion, configHash, SnapshotCodec.formatVersion(), fencingEpoch)
                        .update();
            }
        });
    }

    @Override
    public Optional<Loaded> loadLatestAdoptable() {
        List<Long> candidates = jdbc.sql(
                        "SELECT DISTINCT last_applied_seq FROM snapshots ORDER BY last_applied_seq DESC")
                .query(Long.class)
                .list();
        for (long seq : candidates) {
            Optional<Loaded> adopted = tryAdopt(seq);
            if (adopted.isPresent()) {
                return adopted;
            }
        }
        return Optional.empty();
    }

    private Optional<Loaded> tryAdopt(long seq) {
        List<Row> rows = jdbc.sql("""
                        SELECT market_id, state, state_checksum,
                               engine_version, config_hash, format_version
                        FROM snapshots WHERE last_applied_seq = ? ORDER BY market_id
                        """)
                .params(seq)
                .query((rs, rowNum) -> new Row(
                        rs.getInt("market_id"), rs.getBytes("state"), rs.getLong("state_checksum"),
                        rs.getString("engine_version"), rs.getString("config_hash"),
                        rs.getInt("format_version")))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        // 중복 market_id(구 리더 중복 기록)는 결정론상 바이트 동일이라 뒤 항목으로 덮어도 무방하다.
        Map<Integer, OrderBookImpl> books = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!engineVersion.equals(row.engineVersion())
                    || !configHash.equals(row.configHash())
                    || row.formatVersion() != SnapshotCodec.formatVersion()) {
                log.warn("스냅샷 세트 폐기(버전·설정 불일치): seq={}, market={}, engine={}/{}, config={}/{}, format={}/{}",
                        seq, row.marketId(), row.engineVersion(), engineVersion,
                        row.configHash(), configHash, row.formatVersion(), SnapshotCodec.formatVersion());
                return Optional.empty();
            }
            OrderBookImpl book;
            try {
                book = SnapshotCodec.decode(row.state());
            } catch (RuntimeException e) {
                log.warn("스냅샷 세트 폐기(디코딩 실패): seq={}, market={}: {}",
                        seq, row.marketId(), e.getMessage());
                return Optional.empty();
            }
            if (book.stateChecksum() != row.checksum()) {
                log.warn("스냅샷 세트 폐기(state_checksum 불일치): seq={}, market={}, 기록={}, 계산={}",
                        seq, row.marketId(), row.checksum(), book.stateChecksum());
                return Optional.empty();
            }
            books.put(row.marketId(), book);
        }
        return Optional.of(new Loaded(seq, new ArrayList<>(books.values())));
    }

    /** 마켓 하나의 직렬화 스냅샷. 라이터 스레드가 인코딩해 워커로 넘긴다. */
    public record MarketState(int marketId, byte[] state, long checksum) {
    }

    private record Row(int marketId, byte[] state, long checksum,
                       String engineVersion, String configHash, int formatVersion) {
    }
}
