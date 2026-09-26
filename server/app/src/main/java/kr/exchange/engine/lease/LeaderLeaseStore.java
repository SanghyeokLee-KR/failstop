package kr.exchange.engine.lease;

import java.time.Duration;
import java.util.Optional;

/**
 * 리더 리스의 보관소. 획득·갱신·반납·조회만 한다.
 *
 * 이 인터페이스가 생긴 이유는 2026-09-08 실측이다. 리스가 PostgreSQL 행이었을 때 리스 갱신은
 * 곧 커밋이었고, `synchronous_standby_names` 가 지목한 standby 를 전부 잃으면 그 커밋이 멈췄다.
 * 갱신이 멈춘 사이 리스가 만료되고, standby 가 돌아와 커밋이 풀리는 순간 fencing 이 리더를
 * 끌어내렸다. 곧 거래 데이터의 내구성 설정이 리더 선출의 생존성을 결정했다. 두 축은 서로
 * 독립이어야 하므로 보관소를 갈랐다. 근거는 `harness/evidence/replication-fault-20260908/`.
 *
 * 이 인터페이스는 리스 상태만 다룬다. 강등 등급, 재승격 금지, 승격 재생 구간 같은 판단은
 * 여전히 LeaderLeaseService 가 소유한다. 보관소를 바꿔도 그 규칙이 바뀌지 않아야 하기 때문이다.
 */
public interface LeaderLeaseStore {

    /**
     * 승격 시도. 만료된 리스만 탈취 가능하고 성공하면 직전보다 큰 epoch 을 돌려준다.
     * 비었으면 유효한 타 리더가 있다는 뜻이다.
     *
     * 구현은 반드시 조건부 쓰기 한 번으로 성립시킨다. 읽고 나서 쓰면 두 노드가 같은 만료를
     * 보고 함께 승격한다.
     *
     * 잠금 대기 상한을 건 구현은 초과를 AcquireLockTimeoutException 으로 알린다. 오류가 아니라 '아직 못 얻음'이다.
     */
    Optional<Long> acquire(String holder, Duration ttl);

    /**
     * 획득이 트랜잭션 한정 잠금 대기 상한(lock_timeout)을 넘겼다(DB 설계 §5.4-c 3). 트랜잭션째 롤백돼 리스 행과 획득 이력에
     * 아무것도 남지 않았으므로 다음 틱이 다시 시도하면 된다. 옛 리더의 쓰기 트랜잭션이 리스 행의 KEY SHARE 를 쥐고 있다는 뜻이다.
     */
    final class AcquireLockTimeoutException extends RuntimeException {
        public AcquireLockTimeoutException(String resource, Throwable cause) {
            super("리스 획득 잠금 대기 상한 초과: resource=" + resource, cause);
        }
    }

    /**
     * 하트비트. (holder, epoch) 가 여전히 현재 리스의 값이고 아직 만료 전일 때만 참이다.
     * 만료 뒤 갱신을 허용하면 만료 구동 승격이라는 성질이 깨진다.
     *
     * 왕복 자체가 실패하면 예외를 던진다. 거짓과 예외를 섞으면 호출자가 '갱신이 거부됐다' 와
     * '갱신 여부를 모른다' 를 구분하지 못하고, 접속 오류만으로 리더가 내려간다.
     */
    boolean renew(String holder, long epoch, Duration ttl);

    /**
     * 반납. 엔진의 강등 경로는 이것을 부르지 않는다. 강등은 리스에 아무것도 쓰지 않고 갱신만
     * 멈춰 다음 리더가 만료 구동으로만 정해지게 한다(LeaderLeaseService 클래스 주석).
     * 운영과 검증에서 만료를 기다리지 않고 리스를 비울 때 쓴다.
     */
    void release(String holder, long epoch);

    /** 현재 리스. 없거나 아직 아무도 잡은 적이 없으면 비어 있다. */
    Optional<LeaseSnapshot> current();

    /**
     * 구 리더 최종 심박부터 지금까지의 초. 승격 관측 게이지 전용이고 승격 정확성과 무관하다.
     * 반드시 acquire 전에 불러야 한다. acquire 가 리스 만료 시각을 덮어쓰기 때문이다.
     * 페일오버가 아닌 구간을 세지 않도록 구현은 이 노드가 감시를 시작한 시각으로 하한을 건다.
     */
    double priorDowntimeSeconds(Duration ttl);

    /**
     * 보관소의 현재 시각. 만료 판정은 반드시 이 시계로 한다.
     *
     * 리스가 PostgreSQL 행이던 시절에는 만료를 clock_timestamp() 로 봤고 모든 판정이 같은 시계를
     * 썼다. 보관소가 갈리면 그 시계도 갈리므로, 만료를 묻는 쪽이 자기 시계를 쓰면 스큐가 판정에
     * 들어온다. 기본값은 JVM 시계이고 PostgreSQL 보관소가 DB 시계로 덮는다.
     */
    default java.time.Instant now() {
        return java.time.Instant.now();
    }

    /** 기동 로그용 한 줄. 어느 보관소가 실제로 걸렸는지 라이브에서 확인할 단일 지점이다. */
    String describe();
}
