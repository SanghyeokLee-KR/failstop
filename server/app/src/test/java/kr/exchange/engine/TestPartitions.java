package kr.exchange.engine;

import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;

import java.time.Duration;
import java.util.Optional;

/** DB 없는 시험용 파티션 묶음. 리스 틱을 시작하지 않는 배선(엔진을 끈 노드와 같다)을 흉내 낸다. */
public final class TestPartitions {

    private TestPartitions() {
    }

    /** 보관소에 닿으면 실패하는 리스 서비스들. 계획만 쓰는 시험이 쓴다. */
    public static PartitionLeases unstarted(PartitionPlan plan) {
        String holder = "test-holder";
        return new PartitionLeases(plan, holder, resource -> new LeaderLeaseService(resource, holder,
                new UnreachableStore(), FencingEpochPublisher.NONE, Duration.ofSeconds(10), Duration.ofSeconds(3),
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress()));
    }

    /** 시험용 기간. 그 리스 서비스는 보관소에 닿지 않고 이 기간을 쥐지 않는다. epoch 만 쓰는 시험이 쓴다. */
    public static LeaderTerm term(String resource, long epoch) {
        LeaderLeaseService lease = unstarted(PartitionPlan.single()).first();
        return new LeaderTerm(lease, resource, "test-holder", epoch, 1);
    }

    /** 부르면 실패하는 보관소. 시험이 필요한 조회만 덮어 쓴다. */
    public static class UnreachableStore implements LeaderLeaseStore {
        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            throw new UnsupportedOperationException("시험 보관소");
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            throw new UnsupportedOperationException("시험 보관소");
        }

        @Override
        public void release(String holder, long epoch) {
            throw new UnsupportedOperationException("시험 보관소");
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            throw new UnsupportedOperationException("시험 보관소");
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public String describe() {
            return "시험 보관소";
        }
    }
}
