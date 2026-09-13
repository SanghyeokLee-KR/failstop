package kr.exchange.snapshot;

import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.SnapshotCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * snapshots 테이블 읽기·쓰기(복구 RTO 설계 §3-b). append-only(V1 트리거)라 UPDATE·DELETE가
 * 없고, 손상·구식 스냅샷은 삭제가 아니라 채택 게이트에서 무시한다.
 *
 * 쓰기는 한 번에 여러 마켓 행을 단일 트랜잭션으로 INSERT 한다. 읽기는 세트로 묶지 않는다. 마켓마다
 * 최신 채택본을 따로 고르므로 한 마켓의 손상이 다른 마켓의 채택을 끌어내리지 않는다(ADR 0022 5-4).
 * 마켓 커서가 마켓마다 따로라 서로 다른 seq 의 마켓 스냅샷을 한 엔진에 실어도 재생이 성립한다.
 *
 * 채택 게이트: format_version·engine_version·config_hash가 현재 런타임 값과 일치하고, 디코딩한
 * 북의 마켓과 state_checksum이 행과 일치해야 한다. 어긋나면 그 마켓의 그 seq 를 폐기하고 그 마켓의
 * 다음(더 낮은) seq 를, 없으면 그 마켓을 결과에서 빼 0 에서 재생하게 한다(§3-b 단일 정본 규율).
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

    /** 여러 마켓 행을 단일 트랜잭션으로 적재한다. 워커 스레드(라이터 밖)에서만 호출한다. */
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
    public Map<Integer, MarketLoaded> loadLatestAdoptablePerMarket() {
        List<Integer> markets = jdbc.sql("SELECT DISTINCT market_id FROM snapshots ORDER BY market_id")
                .query(Integer.class)
                .list();
        Map<Integer, MarketLoaded> adopted = new TreeMap<>();
        for (int marketId : markets) {
            List<Long> candidates = jdbc.sql("""
                            SELECT DISTINCT last_applied_seq FROM snapshots
                            WHERE market_id = ? ORDER BY last_applied_seq DESC
                            """)
                    .params((short) marketId)
                    .query(Long.class)
                    .list();
            for (long seq : candidates) {
                Optional<OrderBookImpl> book = tryAdopt(marketId, seq);
                if (book.isPresent()) {
                    adopted.put(marketId, new MarketLoaded(seq, book.get()));
                    break;
                }
            }
        }
        return adopted;
    }

    private Optional<OrderBookImpl> tryAdopt(int marketId, long seq) {
        List<Row> rows = jdbc.sql("""
                        SELECT state, state_checksum, engine_version, config_hash, format_version
                        FROM snapshots WHERE market_id = ? AND last_applied_seq = ?
                        """)
                .params((short) marketId, seq)
                .query((rs, rowNum) -> new Row(
                        rs.getBytes("state"), rs.getLong("state_checksum"),
                        rs.getString("engine_version"), rs.getString("config_hash"),
                        rs.getInt("format_version")))
                .list();
        // 같은 마켓·seq 의 중복 행(구 리더 중복 기록)은 결정론상 바이트가 같아 마지막 행을 쓴다. 하나라도 게이트를
        // 넘지 못하면 그 seq 전체를 버린다.
        OrderBookImpl adopted = null;
        for (Row row : rows) {
            if (!engineVersion.equals(row.engineVersion())
                    || !configHash.equals(row.configHash())
                    || row.formatVersion() != SnapshotCodec.formatVersion()) {
                log.warn("스냅샷 폐기(버전·설정 불일치): market={}, seq={}, engine={}/{}, config={}/{}, format={}/{}",
                        marketId, seq, row.engineVersion(), engineVersion,
                        row.configHash(), configHash, row.formatVersion(), SnapshotCodec.formatVersion());
                return Optional.empty();
            }
            OrderBookImpl book;
            try {
                book = SnapshotCodec.decode(row.state());
            } catch (RuntimeException e) {
                log.warn("스냅샷 폐기(디코딩 실패): market={}, seq={}: {}", marketId, seq, e.getMessage());
                return Optional.empty();
            }
            if (book.marketId() != marketId) {
                log.warn("스냅샷 폐기(북의 마켓 불일치): market={}, seq={}, book={}", marketId, seq, book.marketId());
                return Optional.empty();
            }
            if (book.stateChecksum() != row.checksum()) {
                log.warn("스냅샷 폐기(state_checksum 불일치): market={}, seq={}, 기록={}, 계산={}",
                        marketId, seq, row.checksum(), book.stateChecksum());
                return Optional.empty();
            }
            adopted = book;
        }
        return Optional.ofNullable(adopted);
    }

    /** 마켓 하나의 직렬화 스냅샷. 라이터 스레드가 인코딩해 워커로 넘긴다. */
    public record MarketState(int marketId, byte[] state, long checksum) {
    }

    private record Row(byte[] state, long checksum,
                       String engineVersion, String configHash, int formatVersion) {
    }
}
