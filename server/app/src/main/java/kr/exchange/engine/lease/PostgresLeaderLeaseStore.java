package kr.exchange.engine.lease;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * leader_lease 행을 보관소로 쓰는 구현. V48 의 자원 인자 함수(try_acquire_lease, renew_lease, release_lease)를
 * matching-engine 자원으로 부른다(DB 설계 §3.4·§5.4). 판정 규칙과 epoch 배정은 전부 SQL 함수에 있고 여기서는 호출만 한다.
 *
 * 이것이 기본 보관소다. 옮기기 전과 옮긴 뒤를 같은 조건에서 비교하려면 이 경로가 그대로 남아 있어야 한다.
 *
 * 이 보관소의 알려진 성질을 적어 둔다. 갱신이 곧 커밋이므로 동기 복제 대상 standby 를 전부
 * 잃으면 갱신이 멈춘다. 그것이 이 인터페이스를 만든 이유이며 이 구현의 결함이 아니라 성질이다.
 */
public final class PostgresLeaderLeaseStore implements LeaderLeaseStore {

    private static final String RESOURCE = LeaseResources.MATCHING_ENGINE;

    private final JdbcClient jdbc;
    // 이 노드가 승격을 감시하기 시작한 시각(DB 시계). 첫 다운타임 산정에서 잡는다.
    private volatile OffsetDateTime watchingSince;

    public PostgresLeaderLeaseStore(JdbcClient jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc는 필수");
        }
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> acquire(String holder, Duration ttl) {
        // 실패는 NULL 반환 계약이라 single()이 아니라 optional()로 받는다.
        return jdbc.sql("SELECT try_acquire_lease(?, ?, ? * interval '1 millisecond')")
                .params(RESOURCE, holder, ttl.toMillis())
                .query(Long.class)
                .optional();
    }

    @Override
    public boolean renew(String holder, long epoch, Duration ttl) {
        Boolean renewed = jdbc.sql("SELECT renew_lease(?, ?, ?, ? * interval '1 millisecond')")
                .params(RESOURCE, holder, epoch, ttl.toMillis())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(renewed);
    }

    @Override
    public void release(String holder, long epoch) {
        // 만료 시각을 과거로 미는 것으로 반납한다. epoch 은 건드리지 않는다. 여기서 올리면
        // 승격이 아닌 경로가 fencing 토큰을 소비해 계단이 실제 페일오버 수와 어긋난다.
        // 소유자 권한 함수로 한다. 앱 역할의 leader_lease UPDATE 는 V49 가 회수한다.
        jdbc.sql("SELECT release_lease(?, ?, ?)")
                .params(RESOURCE, holder, epoch)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<LeaseSnapshot> current() {
        return jdbc.sql("SELECT holder, epoch, lease_until FROM leader_lease WHERE resource = ?")
                .param(RESOURCE)
                .query((rs, rowNum) -> new LeaseSnapshot(
                        rs.getString("holder"),
                        rs.getLong("epoch"),
                        rs.getObject("lease_until", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * 페일오버 다운타임 = 승격 시각 - 구 리더 최종 심박. 구 리더 최종 심박 = old_lease_until - ttl
     * (같은 ttl 로 갱신하므로). 시드(holder='')나 실 페일오버가 아닌 최초 기동 승격은 0 을 돌려
     * 게이지 오염을 막는다. 읽기 실패는 0 으로 흡수해 승격을 막지 않는다.
     *
     * 감시 시작 시각으로 하한을 거는 이유가 있다. 리스 행은 스택 종료를 넘어 볼륨에 남으므로
     * 하한이 없으면 스택을 껐다 켠 시간이 통째로 다운타임이 되어 RTO 알람이 기동마다 오발화한다.
     * 두 시각을 모두 DB 시계로 읽어 JVM 과의 스큐가 판정에 들어오지 않게 한다.
     */
    @Override
    public double priorDowntimeSeconds(Duration ttl) {
        try {
            if (watchingSince == null) {
                watchingSince = jdbc.sql("SELECT clock_timestamp()")
                        .query(OffsetDateTime.class)
                        .single();
            }
            Double expiredSeconds = jdbc.sql("""
                    SELECT CASE WHEN holder <> ''
                                THEN EXTRACT(EPOCH FROM (clock_timestamp() - GREATEST(lease_until, ?)))
                                ELSE NULL END
                    FROM leader_lease WHERE resource = ?
                    """)
                    .params(watchingSince, RESOURCE)
                    .query(Double.class)
                    .optional()
                    .orElse(null);
            if (expiredSeconds == null || expiredSeconds < 0) {
                return 0;
            }
            return expiredSeconds + ttl.toMillis() / 1000.0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public String describe() {
        return "postgres(leader_lease)";
    }

    /** 보관소의 시계는 DB 시계다. 만료 판정을 JVM 시계로 하면 스큐가 판정에 들어온다. */
    @Override
    public Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
}
