package kr.exchange.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 노드의 파티션 묶음(DB 설계 §5.4-c 1·2). 계획의 파티션마다 리스 서비스를 들고, 엔진을 켠 노드에서는 파티션 수명주기도 든다.
 * holder 는 노드에 하나이고 모든 파티션이 같이 쓴다.
 *
 * 쓰는 곳은 셋이다. 파티션을 아는 곳(쓰기 경로, 라이터, 승격 조율, 팔로워)은 파티션의 리스 서비스를 받는다. 노드를 보는
 * 곳(readiness, 불변식 스캔)은 이 묶음의 집계를 쓴다. 자원을 인자로 받는 곳(하네스·관리 API, epoch)은 자원으로 찾는다.
 *
 * 접수 게이트이기도 하다. 그 마켓 파티션의 승격 조율이 연 기간을 돌려준다. 엔진을 끈 노드는 수명주기가 없어 늘 닫혀 있다.
 */
public final class PartitionLeases implements AcceptGate {

    private final PartitionPlan plan;
    private final String holder;
    private final Map<String, LeaderLeaseService> leases = new LinkedHashMap<>();
    private final Map<String, PartitionRuntime> runtimes = new ConcurrentHashMap<>();

    public PartitionLeases(PartitionPlan plan, String holder, Function<String, LeaderLeaseService> factory) {
        this.plan = plan;
        this.holder = holder;
        for (String resource : plan.partitions()) {
            LeaderLeaseService lease = factory.apply(resource);
            if (!resource.equals(lease.resource()) || !holder.equals(lease.holder())) {
                throw new IllegalArgumentException("파티션 리스의 자원·holder 가 계획과 다르다: " + resource);
            }
            leases.put(resource, lease);
        }
    }

    public PartitionPlan plan() {
        return plan;
    }

    public String holder() {
        return holder;
    }

    public LeaderLeaseService get(String resource) {
        LeaderLeaseService lease = leases.get(resource);
        if (lease == null) {
            throw new IllegalArgumentException("계획에 없는 자원: " + resource + " (계획 " + plan.partitions() + ")");
        }
        return lease;
    }

    /** 계획의 첫 파티션 리스. P1·P2 에서는 matching-engine 하나다. */
    public LeaderLeaseService first() {
        return leases.get(plan.first());
    }

    /** 그 마켓 파티션의 리스. P3 에서 대응이 없는 마켓이면 비어 있다. */
    public Optional<LeaderLeaseService> forMarket(int marketId) {
        String resource = plan.partitionOf(marketId);
        return resource == null ? Optional.empty() : Optional.ofNullable(leases.get(resource));
    }

    public Collection<LeaderLeaseService> all() {
        return leases.values();
    }

    /** 이 노드가 쥔 파티션이 하나라도 있는가. */
    public boolean anyLeader() {
        return leases.values().stream().anyMatch(LeaderLeaseService::isLeader);
    }

    void attach(PartitionRuntime runtime) {
        runtimes.put(runtime.resource(), runtime);
    }

    public Optional<PartitionRuntime> runtime(String resource) {
        return Optional.ofNullable(runtimes.get(resource));
    }

    /** 계획 순서의 수명주기. 엔진을 끈 노드는 비어 있다. */
    public List<PartitionRuntime> runtimes() {
        return plan.partitions().stream()
                .map(runtimes::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 노드 치명(§5.4-c 5 NODE_FATAL). 모든 파티션을 종신 강등한다. */
    void nodeFatal(String detail) {
        for (PartitionRuntime runtime : runtimes()) {
            runtime.nodeFatalLocal(detail);
        }
    }

    /** 접수 중인 파티션이 하나라도 있는가. */
    @Override
    public boolean isAccepting() {
        return runtimes.values().stream().anyMatch(r -> r.coordinator().openTerm() != null);
    }

    @Override
    public boolean termBound() {
        return true;
    }

    @Override
    public LeaderTerm openTerm(int marketId) {
        String resource = plan.partitionOf(marketId);
        PartitionRuntime runtime = resource == null ? null : runtimes.get(resource);
        return runtime == null ? null : runtime.coordinator().openTerm();
    }
}
