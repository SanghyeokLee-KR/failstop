package kr.exchange.engine.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 외부 보관소에서 얻은 epoch 을 leader_lease 행에 게시한다(V33 publish_leader_epoch).
 *
 * 이 게시가 있어야 트랜잭션 안의 assert_current_leader 가 그대로 동작한다. 그 함수는 바꾸지
 * 않는다. 바꾸면 거부 메시지가 달라지고 FencingErrors 의 분류와 fencing 주입 검증이 함께 깨진다.
 *
 * 게시는 승격 때만 한다. 갱신 때는 하지 않는다. 갱신마다 게시하면 게시가 곧 커밋이므로 리스를
 * 옮긴 의미가 사라진다. 그래서 게시가 미는 lease_until 은 먼 미래이고, 만료 판정은 전적으로
 * 외부 보관소가 한다. 트랜잭션 안에 남는 것은 (holder, epoch) 일치 검사뿐이며 stale 리더의
 * 쓰기는 그 검사가 막는다. 다음 리더가 자기 epoch 을 먼저 게시하기 때문이다.
 */
public final class PostgresFencingEpochPublisher implements FencingEpochPublisher {

    private static final Logger log = LoggerFactory.getLogger(PostgresFencingEpochPublisher.class);

    private final JdbcClient jdbc;

    public PostgresFencingEpochPublisher(JdbcClient jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc는 필수");
        }
        this.jdbc = jdbc;
    }

    @Override
    public boolean publish(String holder, long epoch) {
        try {
            // 3인자 판(V50)은 이 바이너리가 아는 최고 모드를 받는다. 현재 모드가 더 높으면 55000 이고 게시 실패다
            // (DB 설계 §5.4-b 바이너리 능력). 2인자 판은 V50 뒤 P1 전용이다.
            Long published = jdbc.sql("SELECT publish_leader_epoch(?, ?, ?::smallint)")
                    .params(holder, epoch, LeaseResources.MAX_PROTOCOL)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (published == null) {
                // 더 큰 epoch 이 이미 게시돼 있다. 이 노드는 보관소에서는 이겼지만 게시 경쟁에서
                // 졌거나, 게시가 늦어 다음 리더에게 추월당했다. 어느 쪽이든 리더가 되면 안 된다.
                log.error("fencing epoch 게시 거부(더 큰 epoch 선재): holder={}, epoch={}",
                        holder, epoch);
                return false;
            }
            log.info("fencing epoch 게시: holder={}, epoch={}", holder, epoch);
            return true;
        } catch (RuntimeException e) {
            // 게시하지 못한 노드는 쓸 수 없다. 승격을 포기하는 것이 안전 측이다. 이 경로는
            // PostgreSQL 이 커밋할 수 없는 구간에서 발생하며 그 구간에는 어차피 쓸 수 없다.
            log.error("fencing epoch 게시 실패(승격 포기): holder={}, epoch={}, 오류={}",
                    holder, epoch, e.toString());
            return false;
        }
    }
}
