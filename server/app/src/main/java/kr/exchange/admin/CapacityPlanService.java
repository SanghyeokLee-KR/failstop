package kr.exchange.admin;

import kr.exchange.api.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 조회 계층 희망 대수(capacity_plan)의 계약과 관측. 사람의 증설·축소 버튼과 관제센터가 같은 경로를 쓴다.
 * 노드를 직접 띄우거나 내리지 않는다. 실제 대수는 조정기(로컬은 하네스, 클라우드는 ASG)가 맞춘다.
 */
public class CapacityPlanService {

    static final String TIER = "app";

    public record Plan(int min, int desired, int max, String updatedBy, OffsetDateTime updatedAt) {
    }

    public record Change(int from, int to) {
    }

    /**
     * 노드 관측 한 벌. up 은 응답한 노드 수, readAvgCpu 는 조회를 받으면서 사용률을 낸 노드의 평균이다.
     * 사용률을 하나도 읽지 못하면 null 이고 0 으로 채우지 않는다.
     */
    public record Observation(Map<String, NodeProbe.Observed> byNode, int up, Double readAvgCpu, boolean leaderUp) {
    }

    private record Outcome(Plan before, int next, boolean applied) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AdminAuditService audit;
    private final CapacityProperties props;
    private final NodeProbe probe;

    public CapacityPlanService(JdbcClient jdbc, TransactionTemplate tx, AdminAuditService audit,
                               CapacityProperties props, NodeProbe probe) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        this.probe = probe;
    }

    public Plan plan() {
        return jdbc.sql("SELECT min_nodes, desired_nodes, max_nodes, updated_by, updated_at FROM capacity_plan WHERE tier = ?")
                .param(TIER)
                .query((rs, i) -> new Plan(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class)))
                .single();
    }

    /** 호출부의 트랜잭션 안에서 행을 잠그고 읽는다. 되돌리기가 '지금 값이 조치 결과와 같은가'를 볼 때 쓴다. */
    public Plan planForUpdate() {
        return jdbc.sql("SELECT min_nodes, desired_nodes, max_nodes, updated_by, updated_at FROM capacity_plan "
                        + "WHERE tier = ? FOR UPDATE")
                .param(TIER)
                .query((rs, i) -> new Plan(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class)))
                .single();
    }

    /**
     * 희망 대수를 delta 만큼 바꾼다. 읽은 값에 더하기 전에 행을 잠근다. 잠그지 않으면 겹친 두 요청이 같은
     * 값을 읽어 둘 다 같은 전이로 감사에 남고 한 변경을 잃는다. 경계를 넘으면 거부를 감사에 남기고 409 다.
     */
    public Change change(int delta, String operatorId, String action, String reason, String requestId) {
        Outcome outcome = tx.execute(status -> {
            Plan before = jdbc.sql("""
                            SELECT min_nodes, desired_nodes, max_nodes, updated_by, updated_at
                            FROM capacity_plan WHERE tier = ? FOR UPDATE
                            """)
                    .param(TIER)
                    .query((rs, i) -> new Plan(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                            rs.getString(4), rs.getObject(5, OffsetDateTime.class)))
                    .single();
            int next = before.desired() + delta;
            // DB CHECK 가 막기 전에 여기서 막아 화면이 제약 위반 대신 읽을 수 있는 사유를 받게 한다.
            if (next < before.min() || next > before.max()) {
                return new Outcome(before, next, false);
            }
            jdbc.sql("UPDATE capacity_plan SET desired_nodes = ?, updated_by = ?, updated_at = now() WHERE tier = ?")
                    .params(next, operatorId, TIER)
                    .update();
            audit.record(operatorId, action, TIER, requestId, null, "OK",
                    Map.of("from", before.desired(), "to", next, "reason", reason == null ? "" : reason));
            return new Outcome(before, next, true);
        });
        Plan before = outcome.before();
        if (!outcome.applied()) {
            // 거부는 트랜잭션 밖에서 남긴다. 안에서 남기면 예외와 함께 롤백돼 사라진다.
            audit.record(operatorId, action, TIER, requestId, null, "DENIED",
                    Map.of("desired", before.desired(), "requested", outcome.next(),
                            "min", before.min(), "max", before.max()));
            throw new ApiException(409, "CAPACITY_OUT_OF_BOUNDS",
                    outcome.next() < before.min()
                            ? "최소 " + before.min() + "대 아래로 축소 불가"
                            : "최대 " + before.max() + "대 위로 증설 불가");
        }
        return new Change(before.desired(), outcome.next());
    }

    /** 노드를 호출 시점에 잰다. 저장한 값을 쓰지 않는다(NodeProbe 주석). */
    public Observation observe() {
        List<CapacityProperties.Node> nodes = props.getNodes();
        Map<String, NodeProbe.Observed> observed = probe.probeAll(nodes);
        int up = 0;
        int readServing = 0;
        double cpuSum = 0;
        boolean leaderUp = false;
        for (CapacityProperties.Node node : nodes) {
            NodeProbe.Observed o = observed.getOrDefault(node.getId(), NodeProbe.Observed.unreachable());
            if (!o.reachable()) {
                continue;
            }
            up++;
            if (o.leader()) {
                leaderUp = true;
            }
            if (o.servesReads() && o.cpuRatio() != null) {
                readServing++;
                cpuSum += o.cpuRatio();
            }
        }
        return new Observation(observed, up, readServing == 0 ? null : cpuSum / readServing, leaderUp);
    }

    public List<CapacityProperties.Node> nodes() {
        return props.getNodes();
    }
}
