package kr.exchange.api.common;

import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.PartitionLeases;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 응답 epoch 필드의 원천(API 설계 §5-2). 마켓을 아는 응답은 그 마켓 파티션의 epoch 다. 이 노드가 그 파티션을 쥐면 보유
 * 기간의 epoch, 아니면 그 자원의 leader_lease 현재값이다. 인자 없는 epoch 는 계획의 첫 자원 값이고 P1·P2 에서는 둘이 같다.
 * epoch 변경은 클라이언트 강제 재동기화 신호이므로 0 고정 같은 대체값은 금지다.
 */
public class EpochSource {

    private final PartitionLeases leases;
    private final JdbcClient jdbc;

    public EpochSource(PartitionLeases leases, JdbcClient jdbc) {
        this.leases = leases;
        this.jdbc = jdbc;
    }

    public long epoch() {
        return epoch(leases.first());
    }

    /** 그 마켓 파티션의 epoch. P3 에서 대응이 없는 마켓은 첫 자원 값이다. */
    public long epoch(int marketId) {
        return epoch(leases.forMarket(marketId).orElseGet(leases::first));
    }

    private long epoch(LeaderLeaseService lease) {
        // 비리더면 leader_lease 실측 epoch를 읽는다. resource가 PK라 단일 행이므로 max() 불필요.
        // COALESCE(...,0) 대체값은 클래스 계약(0 고정 금지) 위반이라 쓰지 않는다. 행은 V1·V48
        // 부트스트랩 시드로 항상 선재하므로 미조회는 정상 상태가 아니다(single이 그대로 노출).
        return lease.currentTerm()
                .map(LeaderTerm::epoch)
                .orElseGet(() -> jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = ?")
                        .param(lease.resource())
                        .query(Long.class)
                        .single());
    }
}
