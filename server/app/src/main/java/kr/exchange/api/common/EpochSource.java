package kr.exchange.api.common;

import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.lease.LeaseResources;
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
        // 비리더면 leader_lease 실측 epoch를 읽는다. resource가 PK라 단일 행이므로 max() 불필요.
        // COALESCE(...,0) 대체값은 클래스 계약(0 고정 금지) 위반이라 쓰지 않는다. 행은 V1
        // 부트스트랩 시드로 항상 선재하므로 미조회는 정상 상태가 아니다(single이 그대로 노출).
        return lease.current()
                .map(LeaderIdentity::epoch)
                .orElseGet(() -> jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = ?")
                        .param(LeaseResources.MATCHING_ENGINE)
                        .query(Long.class)
                        .single());
    }
}
