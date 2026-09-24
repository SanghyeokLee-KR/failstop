package kr.exchange.engine.lease;

import kr.exchange.journal.TransientDbErrors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * leader_lease 행을 보관소로 쓰는 구현. 자원 인자 함수(획득은 V50 의 4인자 try_acquire_lease, 갱신·반납은 V48 의
 * renew_lease·release_lease)를 이 보관소의 자원으로 부른다(DB 설계 §3.4·§3.5·§5.4). 판정 규칙과 epoch 배정은 전부
 * SQL 함수에 있고 여기서는 호출만 한다. 파티션마다 보관소가 하나다(§5.4-c 2).
 *
 * 이것이 기본 보관소다. 옮기기 전과 옮긴 뒤를 같은 조건에서 비교하려면 이 경로가 그대로 남아 있어야 한다.
 *
 * 이 보관소의 알려진 성질을 적어 둔다. 갱신이 곧 커밋이므로 동기 복제 대상 standby 를 전부
 * 잃으면 갱신이 멈춘다. 그것이 이 인터페이스를 만든 이유이며 이 구현의 결함이 아니라 성질이다.
 */
public final class PostgresLeaderLeaseStore implements LeaderLeaseStore {

    private final JdbcClient jdbc;
    // 획득 트랜잭션. null 이면 획득은 종전처럼 자동 커밋 한 문장이다(시험의 단일 리스 배선).
    private final TransactionTemplate tx;
    private final String resource;
    private final short maxProtocol;
    // "1000ms" 꼴. null 이면 획득에 잠금 대기 상한을 걸지 않는다.
    private final String acquireLockTimeout;
    // 이 노드가 승격을 감시하기 시작한 시각(DB 시계). 첫 다운타임 산정에서 잡는다.
    private volatile OffsetDateTime watchingSince;

    /** matching-engine 자원, 잠금 대기 상한 없는 획득. 시험과 단일 리스 배선이 쓴다. */
    public PostgresLeaderLeaseStore(JdbcClient jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc는 필수");
        }
        this.jdbc = jdbc;
        this.tx = null;
        this.resource = LeaseResources.MATCHING_ENGINE;
        this.maxProtocol = LeaseResources.MAX_PROTOCOL;
        this.acquireLockTimeout = null;
    }

    /**
     * 파티션 배선. 획득은 이 풀 위의 트랜잭션 하나에서 잠금 대기 상한을 건 뒤 부른다(DB 설계 §5.4-c 3). 운영은 maxProtocol 에
     * LeaseResources.MAX_PROTOCOL 을 넘기고, P3 흉내 시험만 3을 넘긴다.
     */
    public PostgresLeaderLeaseStore(DataSource leaseDataSource, String resource, short maxProtocol,
                                    Duration acquireLockTimeout) {
        if (leaseDataSource == null || resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("leaseDataSource·resource는 필수");
        }
        if (acquireLockTimeout == null || acquireLockTimeout.isZero() || acquireLockTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireLockTimeout은 양수여야 한다: " + acquireLockTimeout);
        }
        this.jdbc = JdbcClient.create(leaseDataSource);
        this.tx = new TransactionTemplate(new JdbcTransactionManager(leaseDataSource));
        this.resource = resource;
        this.maxProtocol = maxProtocol;
        this.acquireLockTimeout = Math.max(1, acquireLockTimeout.toMillis()) + "ms";
    }

    /** 이 보관소가 다루는 leader_lease 자원. */
    public String resource() {
        return resource;
    }

    @Override
    public Optional<Long> acquire(String holder, Duration ttl) {
        if (tx == null) {
            return tryAcquire(holder, ttl);
        }
        try {
            // 획득은 리스 행을 FOR UPDATE 로 잡으므로 옛 리더의 진행 중 쓰기가 쥔 KEY SHARE 를 기다린다. 기다림이 상한을
            // 넘으면 55P03 으로 트랜잭션째 롤백되고 다음 틱이 다시 시도한다. 설정은 트랜잭션 한정이라 커밋·롤백 뒤 풀
            // 커넥션에 남지 않아 같은 풀의 갱신·반납·재확인은 상한 없이 그대로다.
            return tx.execute(status -> {
                jdbc.sql("SELECT set_config('lock_timeout', ?, true)")
                        .params(acquireLockTimeout)
                        .query(String.class)
                        .single();
                return tryAcquire(holder, ttl);
            });
        } catch (DataAccessException e) {
            if ("55P03".equals(TransientDbErrors.sqlState(e))) {
                throw new AcquireLockTimeoutException(resource, e);
            }
            throw e;
        }
    }

    private Optional<Long> tryAcquire(String holder, Duration ttl) {
        // 실패는 NULL 반환 계약이라 single()이 아니라 optional()로 받는다.
        // 4인자 판(V50)은 이 바이너리가 아는 최고 모드를 받는다. 현재 모드가 그보다 높으면 55000 으로 거부해, 그 모드의
        // 정산을 모르는 바이너리가 리더가 되지 못한다(DB 설계 §5.4-b 바이너리 능력). 3인자 판은 V50 뒤 P1 전용이다.
        return jdbc.sql("SELECT try_acquire_lease(?, ?, ? * interval '1 millisecond', ?::smallint)")
                .params(resource, holder, ttl.toMillis(), maxProtocol)
                .query(Long.class)
                .optional();
    }

    @Override
    public boolean renew(String holder, long epoch, Duration ttl) {
        Boolean renewed = jdbc.sql("SELECT renew_lease(?, ?, ?, ? * interval '1 millisecond')")
                .params(resource, holder, epoch, ttl.toMillis())
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
                .params(resource, holder, epoch)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<LeaseSnapshot> current() {
        return jdbc.sql("SELECT holder, epoch, lease_until FROM leader_lease WHERE resource = ?")
                .param(resource)
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
                    .params(watchingSince, resource)
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
        return "postgres(leader_lease " + resource + (acquireLockTimeout == null ? ""
                : ", 획득 lock_timeout " + acquireLockTimeout) + ")";
    }

    /** 보관소의 시계는 DB 시계다. 만료 판정을 JVM 시계로 하면 스큐가 판정에 들어온다. */
    @Override
    public Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
}
