package kr.exchange.engine;

import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.LeaseResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * 파티션 계획(DB 설계 §5.4-c 1). 노드는 기동 때 읽은 리더 모드로 계획을 한 번 만들고 기동 뒤 바꾸지 않는다. P1·P2 는
 * matching-engine 하나가 모든 마켓을 덮는다. P3 은 matching-p0·matching-p1 이고 마켓의 파티션은 market_lease_partition 에서
 * 읽는다. 조회 역할 노드는 DB 를 읽지 않고 matching-engine 하나의 계획을 쓴다.
 *
 * 앱의 대응은 라우팅과 수명주기에만 쓴다. 쓰기 판정은 DB 가 기록할 행의 마켓에서 자원을 도출하므로 앱이 대응을 잘못 알아도
 * 다른 리스로 쓰기가 통과하지 않는다. 대응 표는 추가 전용이라 앱이 읽은 대응과 DB 가 달라지는 경우는 아직 읽지 않은 새 마켓뿐이고,
 * 계획은 모르는 마켓을 만나면 그 자리에서 다시 읽는다.
 */
public final class PartitionPlan {

    private static final Logger log = LoggerFactory.getLogger(PartitionPlan.class);

    private final List<String> partitions;
    private final boolean partitioned;
    // P3 전용. 대응을 다시 읽는 곳.
    private final JdbcClient jdbc;
    private final Map<Integer, String> marketPartition = new ConcurrentHashMap<>();

    private PartitionPlan(List<String> partitions, boolean partitioned, JdbcClient jdbc) {
        this.partitions = List.copyOf(partitions);
        this.partitioned = partitioned;
        this.jdbc = jdbc;
    }

    /** matching-engine 하나가 모든 마켓을 덮는 계획. P1·P2 와 조회 역할 노드가 쓴다. */
    public static PartitionPlan single() {
        return new PartitionPlan(List.of(LeaseResources.MATCHING_ENGINE), false, null);
    }

    /** 파티션 리더 모드 계획. 대응은 지연 읽기다. */
    public static PartitionPlan partitioned(JdbcClient jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc는 필수");
        }
        return new PartitionPlan(List.of(LeaseResources.PARTITION_P0, LeaseResources.PARTITION_P1), true, jdbc);
    }

    /** 기동 모드로 만든다. 모드를 읽지 않은 노드(조회 역할)는 DB 를 읽지 않는 한 파티션 계획이다. */
    public static PartitionPlan forBoot(LeaderProtocolBoot boot, JdbcClient jdbc) {
        PartitionPlan plan = boot.protocol() >= LeaseResources.PARTITION_PROTOCOL
                ? partitioned(jdbc) : single();
        log.info("파티션 계획: {} (기동 모드 P{})", plan.partitions, boot.protocol());
        return plan;
    }

    /** 계획의 자원 목록. 첫 자원이 인자 없는 epoch 와 불변식 스캔의 기준이다. */
    public List<String> partitions() {
        return partitions;
    }

    public String first() {
        return partitions.get(0);
    }

    /** 파티션 리더 모드(P3)인가. */
    public boolean partitioned() {
        return partitioned;
    }

    public int index(String resource) {
        int i = partitions.indexOf(resource);
        if (i < 0) {
            throw new IllegalArgumentException("계획에 없는 자원: " + resource + " (계획 " + partitions + ")");
        }
        return i;
    }

    public boolean contains(String resource) {
        return partitions.contains(resource);
    }

    /**
     * 마켓의 파티션. P1·P2 는 DB 를 읽지 않고 matching-engine 이다. P3 에서 대응이 없으면 다시 읽고, 그래도 없으면 null 이다.
     * 그 마켓의 쓰기는 DB 가 55000 으로 거부한다.
     */
    public String partitionOf(int marketId) {
        if (!partitioned) {
            return partitions.get(0);
        }
        String known = marketPartition.get(marketId);
        if (known != null) {
            return known;
        }
        reload();
        return marketPartition.get(marketId);
    }

    /**
     * 메모리 대응만 본 마켓의 파티션(API 설계 §2-5-d, S5 설계 4-1). P1·P2 는 matching-engine 이다. P3 에서 아직 읽지 않은 마켓이면
     * null 이고 다시 읽지 않는다. 인증 전의 소유권 선검사가 쓴다. 미인증 요청이 대응 표 조회를 일으키지 않게 하려는 것이다.
     */
    public String knownPartitionOf(int marketId) {
        return partitioned ? marketPartition.get(marketId) : partitions.get(0);
    }

    /** 그 자원이 덮는 마켓인가를 답하는 판정. P1·P2 의 matching-engine 은 모든 마켓이다. */
    public IntPredicate scope(String resource) {
        if (!contains(resource)) {
            throw new IllegalArgumentException("계획에 없는 자원: " + resource);
        }
        if (!partitioned) {
            return marketId -> true;
        }
        return marketId -> resource.equals(partitionOf(marketId));
    }

    private void reload() {
        jdbc.sql("SELECT market_id, resource FROM market_lease_partition")
                .query((rs, rowNum) -> marketPartition.put(rs.getInt("market_id"), rs.getString("resource")))
                .list();
    }
}
