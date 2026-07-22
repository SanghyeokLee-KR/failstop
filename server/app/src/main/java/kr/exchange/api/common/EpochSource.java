package kr.exchange.api.common;

import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 응답 epoch 필드의 원천(§5-2). 이 노드가 리더면 보유 epoch, 아니면 leader_lease 현재값.
 * epoch 변경은 클라이언트 강제 재동기화 신호이므로 0 고정 같은 대체값은 금지다.
 */
public class EpochSource {

    private final LeaderLeaseService lease;
    private final JdbcClient jdbc;

    public EpochSource(LeaderLeaseService lease, JdbcClient jdbc) {
        this.lease = lease;
        this.jdbc = jdbc;
    }

    public long epoch() {
        return lease.current()
                .map(LeaderIdentity::epoch)
                .orElseGet(() -> jdbc.sql(
                                "SELECT COALESCE(max(epoch), 0) FROM leader_lease WHERE resource = 'matching-engine'")
                        .query(Long.class)
                        .single());
    }
}
