package kr.exchange.engine;

import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseResources;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * leader_lease 리스 하나의 관리(DB 설계 §3.1·§3.4·§5.4, 파티션 단위는 §5.4-c). 승격은 try_acquire_lease(만료 리스만
 * 탈취, epoch 는 모든 리스 행과 획득 이력의 최대 + 1), 유지는 renew_lease로만 한다. 정본은 V48 의 자원 인자 함수이고
 * 보관소가 이 서비스의 자원으로 부른다. 여기서는 호출만 한다. 파티션마다 인스턴스가 하나다.
 *
 * holder는 노드마다 하나이고 그 노드의 모든 파티션이 같이 쓴다. 프로세스 기동마다 새로 발급하는 UUID다. 호스트명을 쓰면 같은
 * 호스트의 구·신 프로세스를 구분하지 못해 fencing이 무력화된다(P0-1). epoch는 자원을 통틀어 유일하므로 holder를 나눌 필요가 없다.
 *
 * 리스를 얻을 때마다 기간(LeaderTerm)을 하나 만든다. 쓰기 경로는 이 기간을 쥐고 쓰고, 강등 호출은 기간을 받아 지금 기간과
 * 다르면 아무것도 하지 않는다(§5.4-c 6). 같은 노드가 재구성 뒤 새 epoch를 얻어도 강등 전 스레드가 새 epoch를 빌리지 못한다.
 *
 * 상실은 셋이다. 승격 재생 구간의 상실은 회복 가능 반납(standDown)이고 후보에 그대로 남는다. 그 밖의 상실은 후보 표식을
 * 내린다(disarm). 파티션 수명주기가 fresh 재구성을 마친 뒤 rearm으로 다시 세운다. 노드 치명(VM 오류)은 종신 표식을 세우고
 * rearm이 풀지 않는다. 어느 쪽이든 leader_lease에는 아무것도 쓰지 않아 다음 리더는 TTL 만료 뒤 만료 조건 획득으로만 정해지고,
 * 이중 리더 차단 성질이 불변이다.
 *
 * lease_until을 미는 경로는 extendLease 하나뿐이다(§5.4 연장 직전 생존성 판정). 하트비트와 fencing
 * 거부 재확인이 같은 UPDATE를 쓰므로, 판정을 호출자별로 두면 좀비 리더가 리스를 무한 연장한다.
 *
 * 리스가 어디에 보관되는지는 LeaderLeaseStore가 정한다(2026-09-08). 이 클래스는 보관소를 모르고
 * 강등 등급·후보 표식·승격 재생 구간만 소유한다. 보관소가 PostgreSQL 밖이면 트랜잭션 안의 assert_current_leader가 볼
 * 근거가 사라지므로, 승격 시 FencingEpochPublisher가 epoch을 그 행에 먼저 올린다. 게시 실패는 승격 실패다.
 */
public class LeaderLeaseService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaderLeaseService.class);

    // 옛 리더의 쓰기가 리스 행을 이 틱 수만큼 연달아 쥐면 경고한다. 그 트랜잭션은 idle_in_transaction_session_timeout 이
    // 끊어야 하는데 끊기지 않고 있다는 뜻이다.
    private static final int LOCK_TIMEOUT_WARN_STREAK = 30;

    private final String resource;
    private final String holder;
    private final LeaderLeaseStore store;
    // 승격 시 epoch을 쓰기 경로가 보는 자리에 올린다. PostgreSQL 보관소에서는 승격 UPDATE가
    // 이미 같은 행을 올리므로 NONE이다.
    private final FencingEpochPublisher fencingPublisher;
    private final Duration ttl;
    private final Duration tickInterval;
    // 갱신 직전 서비스 생존성 판정(§5.4 자발적 강등). 하트비트가 전용 풀로 격리돼 서비스
    // 생존성과 분리되므로, 요청 경로가 멈춘 노드도 리스만 갱신하는 좀비 리더가 성립한다.
    private final NodeServiceHealth serviceHealth;
    // 비선호 파티션의 획득 유예(§5.4-c 9). P1·P2 는 ALWAYS 다.
    private final PartitionAcquirePolicy acquirePolicy;
    // 파티션 층 좀비 판정의 진행 축. 쓰기 경로가 기간을 거쳐 센다(LeaderTerm.progress).
    private final LeaderWriteProgress partitionProgress;
    // null이면 리더 아님.
    private final AtomicReference<LeaderTerm> term = new AtomicReference<>();
    private final AtomicLong generations = new AtomicLong();
    // 노드 치명 표식(§5.4-c 5 NODE_FATAL). VM 오류 뒤의 프로세스는 불건전하므로 재기동으로만 복귀한다. rearm 이 풀지 않는다.
    private volatile boolean demoted;
    // 후보 표식. 내려가 있으면 획득하지 않는다. 파티션 치명 강등이 내리고 재구성이 끝나면 파티션 수명주기가 다시 세운다.
    // 강등 시점의 인메모리 오더북은 다른 리더의 커밋을 반영하지 못한 stale 이라, 재구성 전에 리스만 다시 얻으면 '접수는
    // ACK되는데 소비자가 없는' 반쪽 리더가 된다.
    private volatile boolean armed = true;
    // 승격 재생 구간 표식. 이 구간의 상실은 강등이 아니라 회복 가능 반납으로 내린다. 근거는
    // 구간의 성질이다. PromotionCoordinator는 재생이 성공한 뒤에야 fresh 엔진을 루프에 설치하고
    // 접수를 열므로, 재생 도중의 루프는 승격 전 상태 그대로이며 게이트가 닫혀 아무것도 소비하지
    // 않는다. 강등이 전제하는 'stale 엔진 + halt된 루프'가 성립하지 않는다.
    //
    // 불리언이 아니라 토큰인 이유: [재생 실패 -> 반납 -> 만료 -> 같은 노드가 재승격]이 정상 경로이고,
    // 앞선 시도의 뒤늦은 해제가 새 시도의 표식을 지울 수 있다. 지워지면 새 시도의 재생 실패가 강등으로
    // 잘못 내려간다. 해제는 자기 토큰에 대해서만 CAS로 성립시킨다. 0은 재생 구간 아님이다.
    private final AtomicLong promotionReplayToken = new AtomicLong();
    private final AtomicLong promotionReplaySequence = new AtomicLong();
    // 상실 확정(표식 + 기간 클리어)과 승격 커밋(기간 세팅)을 상호배제한다. 이 락 없이는
    // markLost가 기간을 비운 직후·표식 세팅 전 창에서 tick의 tryAcquire가 재승격해
    // half-leader가 된다(TOCTOU).
    private final Object leadershipLock = new Object();
    // 연달아 잠금 대기 상한으로 끝난 획득 수. 리스 틱 스레드만 쓴다.
    private int lockTimeoutStreak;
    // 쓰기 가능 상태(DB 설계 §5.4-f 2). null 이면 탐지를 끈 노드라 획득 규칙 둘을 적용하지 않는다.
    private volatile WriteAvailability writeAvailability;
    // 얻었으나 이미 만료돼 버린 획득 수(exchange.lease.acquire.expired).
    private final LongAdder expiredAcquisitions = new LongAdder();
    private final List<Runnable> lossListeners = new CopyOnWriteArrayList<>();
    private final List<TermLossListener> termLossListeners = new CopyOnWriteArrayList<>();
    // 사유 불문 '리더십을 놓았다' 통지. 강등과 회복 가능 반납 모두에서 발화한다. 관측 전용(is_leader 게이지)이다.
    private final List<Runnable> releaseListeners = new CopyOnWriteArrayList<>();
    // 회복 가능 반납 전용 통지. 강등에서는 발화하지 않는다. 관측 전용이다.
    private final List<Runnable> standDownListeners = new CopyOnWriteArrayList<>();
    // 승격 통지. 리스 획득 시점에 발화해 접수 개방 전 인메모리 선복원을 트리거한다(복구 RTO §3-a).
    private final List<Consumer<LeaderIdentity>> gainListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<LeaderTerm>> termGainListeners = new CopyOnWriteArrayList<>();
    // 관측 통지. 승격 시 epoch + 페일오버 다운타임을 함께 넘긴다(하네스 CICD §5-1 단일 계산점).
    private final List<AcquisitionListener> acquisitionListeners = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;

    /** 종전 배선. leader_lease 의 matching-engine 행을 보관소로 쓴다. */
    public LeaderLeaseService(JdbcClient jdbc, Duration ttl, Duration tickInterval) {
        this(jdbc, ttl, tickInterval, NodeServiceHealth.ALWAYS_SERVICEABLE);
    }

    /** 종전 배선. leader_lease 의 matching-engine 행을 보관소로 쓴다. */
    public LeaderLeaseService(JdbcClient jdbc, Duration ttl, Duration tickInterval,
                              NodeServiceHealth serviceHealth) {
        this(new PostgresLeaderLeaseStore(jdbc), FencingEpochPublisher.NONE,
                ttl, tickInterval, serviceHealth);
    }

    /** matching-engine 자원, 자기 holder. 단일 리스 배선과 시험이 쓴다. */
    public LeaderLeaseService(LeaderLeaseStore store, FencingEpochPublisher fencingPublisher,
                              Duration ttl, Duration tickInterval,
                              NodeServiceHealth serviceHealth) {
        this(LeaseResources.MATCHING_ENGINE, UUID.randomUUID().toString(), store, fencingPublisher,
                ttl, tickInterval, serviceHealth, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
    }

    /** 파티션 배선. holder는 노드의 모든 파티션이 같은 값을 넘긴다. */
    public LeaderLeaseService(String resource, String holder, LeaderLeaseStore store,
                              FencingEpochPublisher fencingPublisher, Duration ttl, Duration tickInterval,
                              NodeServiceHealth serviceHealth, PartitionAcquirePolicy acquirePolicy,
                              LeaderWriteProgress partitionProgress) {
        if (serviceHealth == null) {
            throw new IllegalArgumentException("serviceHealth는 필수");
        }
        if (resource == null || holder == null || store == null || fencingPublisher == null
                || ttl == null || tickInterval == null || acquirePolicy == null || partitionProgress == null) {
            throw new IllegalArgumentException(
                    "resource·holder·store·fencingPublisher·ttl·tickInterval·acquirePolicy·partitionProgress는 필수");
        }
        if (ttl.isZero() || ttl.isNegative() || tickInterval.isZero() || tickInterval.isNegative()) {
            throw new IllegalArgumentException("ttl·tickInterval은 양수여야 한다");
        }
        if (tickInterval.compareTo(ttl) >= 0) {
            // 갱신 주기가 TTL 이상이면 정상 리더도 만료를 반복해 탈취/복구 플래핑이 된다.
            throw new IllegalArgumentException("tickInterval은 ttl보다 짧아야 한다");
        }
        this.resource = resource;
        this.holder = holder;
        this.store = store;
        this.fencingPublisher = fencingPublisher;
        this.ttl = ttl;
        this.tickInterval = tickInterval;
        this.serviceHealth = serviceHealth;
        this.acquirePolicy = acquirePolicy;
        this.partitionProgress = partitionProgress;
    }

    public String resource() {
        return resource;
    }

    public String holder() {
        return holder;
    }

    /** 복구 재적재 등 스케줄러 밖 장기 구간이 자기-fencing 전에 renew 주기를 정할 때 쓴다. */
    public Duration ttl() {
        return ttl;
    }

    public boolean isLeader() {
        return term.get() != null;
    }

    /** 이 리스의 보관소. 획득 유예와 관측이 리스 행을 읽을 때 쓴다. */
    public LeaderLeaseStore store() {
        return store;
    }

    LeaderWriteProgress partitionProgress() {
        return partitionProgress;
    }

    /**
     * 쓰기 가능 탐지를 켠 노드의 획득 규칙 둘을 건다(DB 설계 §5.4-f 2). 획득은 ACTIVE 일 때만 시도하고, 얻은 리스가 이미
     * 만료됐으면 승격하지 않는다. 탐지를 끈 노드는 부르지 않아 다른 스택의 관문 조건이 바뀌지 않는다.
     */
    public void gateAcquisition(WriteAvailability availability) {
        this.writeAvailability = availability;
    }

    /** 얻었으나 이미 만료돼 버린 획득 수. */
    public long expiredAcquisitions() {
        return expiredAcquisitions.sum();
    }

    /**
     * 보관소가 보는 현재 리스. 이 노드의 신원이 아니라 클러스터 상태다. 읽기 전용이고 상태를 바꾸지 않는다.
     */
    public Optional<kr.exchange.engine.lease.LeaseSnapshot> leaseSnapshot() {
        return store.current();
    }

    public Optional<LeaderIdentity> current() {
        LeaderTerm t = term.get();
        return t == null ? Optional.empty() : Optional.of(t.identity());
    }

    /** 지금 쥔 기간. 리더가 아니면 비어 있다. */
    public Optional<LeaderTerm> currentTerm() {
        return Optional.ofNullable(term.get());
    }

    /** 이 기간이 지금 쥔 기간인가. 기간의 동일성은 객체 동일성이다(획득마다 하나를 만든다). */
    public boolean isCurrent(LeaderTerm t) {
        return t != null && term.get() == t;
    }

    /** 쓰기 트랜잭션 진입점. 리더가 아니면 DB 왕복 없이 즉시 거부한다. 기간을 쥐지 않은 종전 경로가 쓴다. */
    public LeaderIdentity requireLeader() {
        LeaderTerm t = term.get();
        if (t == null) {
            throw new NotLeaderException("리더 아님: resource=" + resource + ", holder=" + holder);
        }
        return t.identity();
    }

    /** 후보인가. 노드 치명이거나 표식이 내려가 있으면 획득하지 않는다. */
    public boolean isArmed() {
        return armed && !demoted;
    }

    /** 노드 치명으로 종신 강등됐는가. */
    public boolean isNodeFatal() {
        return demoted;
    }

    /** 후보 표식을 내린다. 이후 틱은 획득하지 않는다. 쥔 리스는 놓지 않는다. */
    public void disarm() {
        synchronized (leadershipLock) {
            armed = false;
        }
    }

    /**
     * 후보 표식을 다시 세운다. 재구성을 마친 파티션 수명주기만 부른다. 노드 치명 표식은 풀지 않는다.
     *
     * @return 이 호출 뒤 후보인가
     */
    public boolean rearm() {
        synchronized (leadershipLock) {
            if (demoted) {
                return false;
            }
            armed = true;
            return true;
        }
    }

    /**
     * 강등 통지 등록(종전 배선). 강등과 노드 치명에서 발화하고 회복 가능 반납에서는 발화하지 않는다. 단일 라이터 halt 같은
     * 부작용을 가진 리스너를 여기 건다.
     */
    public void onLeadershipLost(Runnable listener) {
        lossListeners.add(listener);
    }

    /** 강등 통지 등록(파티션 배선). 놓은 기간과 사유를 받는다. 발화 조건은 onLeadershipLost 와 같다. */
    public void onTermLost(TermLossListener listener) {
        termLossListeners.add(listener);
    }

    /** 리더십 반납 통지 등록(강등·회복 가능 공통). 관측 갱신 전용이다. */
    public void onLeadershipReleased(Runnable listener) {
        releaseListeners.add(listener);
    }

    /**
     * 회복 가능 반납 통지 등록(하네스 CICD §5-2 `exchange.leader.recoverable_standdown`).
     * 강등과 구분해 세는 축이라 releaseListeners와 분리한다. 관측 전용이다.
     */
    public void onRecoverableStandDown(Runnable listener) {
        standDownListeners.add(listener);
    }

    /**
     * 승격 통지 등록(markLost 대칭). tryAcquire 성공 시 기간 세팅 직후 발화한다. 리스너는
     * 리스 스케줄러 스레드에서 짧게 반환해야 한다. 대규모 재생은 별도 워커로 넘겨 renew를
     * 막지 않는다(복구 RTO §3-a 승격 중 리스 유지).
     */
    public void onLeadershipGained(Consumer<LeaderIdentity> listener) {
        gainListeners.add(listener);
    }

    /** 승격 통지 등록(파티션 배선). 새 기간을 받는다. 발화 조건과 규칙은 onLeadershipGained 와 같다. */
    public void onTermGained(Consumer<LeaderTerm> listener) {
        termGainListeners.add(listener);
    }

    /**
     * 승격 관측 통지 등록. tryAcquire 성공 시 epoch와 페일오버 다운타임을 함께 넘긴다. 게이지
     * 갱신 전용이라 상태기계(gainListeners)와 분리한다. 리스너는 리스 스케줄러 스레드에서 짧게
     * 반환해야 한다.
     */
    public void onLeadershipAcquired(AcquisitionListener listener) {
        acquisitionListeners.add(listener);
    }

    /** 승격 관측 통지. failoverDowntimeSeconds는 구 리더 최종 심박~승격 간격(실 페일오버 아니면 0). */
    @FunctionalInterface
    public interface AcquisitionListener {
        void onAcquired(LeaderIdentity identity, double failoverDowntimeSeconds);
    }

    /** 강등 통지. 놓은 기간과 사유를 받는다. 리스 틱·라이터·요청 스레드에서 불리므로 짧게 반환해야 한다. */
    @FunctionalInterface
    public interface TermLossListener {
        void lost(LeaderTerm term, DemotionReason reason, String detail);
    }

    /**
     * 외부에서 확정된 상실 통지 경로(DB 설계 §5.4 'stale 리더 즉시 self-shutdown'). 지금 쥔 기간을 놓는다. 기간을 쥐지 않은
     * 종전 경로(단일 라이터 루프의 치명 처리)가 쓴다.
     */
    public void demote(String reason) {
        markLost(null, DemotionReason.WRITER_FATAL, reason);
    }

    /**
     * 기간을 지정한 강등. 지금 기간과 다르면 아무것도 하지 않는다. 늦게 온 옛 기간의 실패가 새 기간을 강등시키지 않는다.
     *
     * @return 이 호출이 기간을 실제로 놓았으면 참
     */
    public boolean demote(LeaderTerm t, DemotionReason reason, String detail) {
        if (t == null) {
            return false;
        }
        return markLost(t, reason, detail);
    }

    /**
     * 노드 치명(§5.4-c 5 NODE_FATAL). 종신 표식을 세우고 지금 기간을 놓는다. 승격 재생 구간이어도 회복 가능으로 내리지
     * 않는다. VM 오류 뒤에는 '다시 하면 같은 결과'라는 회복 가능 등급의 전제가 성립하지 않는다.
     */
    public void markNodeFatal(String reason) {
        LeaderTerm prev;
        synchronized (leadershipLock) {
            demoted = true;
            armed = false;
            prev = term.getAndSet(null);
        }
        log.error("노드 치명으로 리더십 종신 강등(복귀는 재기동): resource={}, holder={}, 기간={}, 사유={}",
                resource, holder, prev, reason);
        if (prev == null) {
            return;
        }
        notifyReleased();
        notifyLost(prev, DemotionReason.NODE_FATAL, reason);
    }

    /**
     * fencing 거부를 만난 쓰기 경로의 권위 재확인. 거부 즉시 강등하면 정당한 현재 리더가 일시
     * 원인(리스 만료 직전의 슬로 윈도, DB 시계 비단조성, 하트비트 한 틱 밀림)만으로 후보에서 빠진다.
     * E2 라이브에서 승격 123ms 뒤의 단 한 번의 거부가 클러스터를 정지시켰다.
     *
     * 판정은 renew_lease 한 번이다. 이 함수는 (holder, epoch)가 여전히 리스 행의 값이고
     * 만료 전일 때만 참이므로, 참이면 이 노드가 정당한 현재 리더임이 DB로 증명된다. 만료 리스를
     * 대상으로 하는 UPDATE가 아니라 탈취가 불가능하고 epoch도 건드리지 않아, 이 경로가 이중
     * 리더를 만들 수 없다.
     *
     * @return true면 리더십이 증명돼 강등하지 않았다(호출자는 재시도 가능). false면 강등했거나
     *         (거짓 반환) 증명에 실패했다. DB 왕복 자체가 실패한 경우는 상실로 단정하지 않는다.
     */
    public boolean demoteUnlessStillLeader(String reason) {
        return demoteUnlessStillLeader(term.get(), reason);
    }

    /** 기간을 지정한 권위 재확인. 그 기간의 epoch로 갱신을 부른다. 지금 기간과 다르면 아무것도 하지 않고 거짓이다. */
    public boolean demoteUnlessStillLeader(LeaderTerm t, String reason) {
        if (t == null || term.get() != t) {
            return false;
        }
        LeaseExtension outcome;
        try {
            outcome = extendLease(t.epoch());
        } catch (RuntimeException ex) {
            // 재확인 왕복 실패는 만료 여부를 알려주지 않는다. renew()와 같은 규칙으로 상실을
            // 단정하지 않는다. 실제 쓰기는 fencing이 계속 막으므로 안전하고, 판정은 다음 틱 몫이다.
            log.warn("fencing 거부 후 리더십 재확인 왕복 실패: resource={}, 사유={}, 오류={}",
                    resource, reason, ex.toString());
            return false;
        }
        switch (outcome) {
            case EXTENDED -> {
                log.warn("fencing 거부를 받았으나 리스 권위는 유지된다(강등하지 않음): resource={}, epoch={}, 사유={}",
                        resource, t.epoch(), reason);
                return true;
            }
            case UNSERVICEABLE -> {
                // 재확인은 곧 리스 연장이다. 생존성 판정을 통과하지 못한 노드까지 연장해 주면
                // §5.4의 '연장 직전 생존성 판정'이 이 경로에서만 무력해진다.
                markLost(t, DemotionReason.STALL, "서비스 불능 지속(fencing 거부 재확인): " + reason);
                return false;
            }
            default -> {
                markLost(t, DemotionReason.LEASE_LOST_WHILE_ACCEPTING, reason);
                return false;
            }
        }
    }

    /**
     * 받은 기간을 쓰지 않고 놓는다(DB 설계 §5.4-c 5). 승격 조율이 후보가 아닌 상태(재구성·차단·참여 끔)와 엇갈려 얻은 기간이다.
     * 후보 표식을 내리고 갱신만 멈춘다. 강등 통지는 부르지 않는다. 표식은 재구성이나 차단기 해제를 마친 수명주기가 다시 세운다.
     *
     * @return 이 호출이 기간을 실제로 놓았으면 참
     */
    public boolean relinquish(LeaderTerm t, String reason) {
        synchronized (leadershipLock) {
            if (t == null || term.get() != t) {
                return false;
            }
            armed = false;
            term.set(null);
        }
        log.warn("받은 기간을 쓰지 않고 놓는다(갱신 없음, TTL 뒤 소멸): resource={}, holder={}, epoch={}, 사유={}",
                resource, holder, t.epoch(), reason);
        notifyReleased();
        return true;
    }

    /**
     * 회복 가능 반납. 승격 재생이 접수 개방 전에 실패했을 때만 쓴다. 리스 행에는 아무것도 쓰지
     * 않고 갱신만 멈추므로, 다음 리더는 TTL 만료 뒤 try_acquire_lease(더 큰 epoch)로만
     * 정해진다. 후보 표식을 내리지 않아 이 노드도 다시 후보가 된다.
     */
    public void standDown(String reason) {
        LeaderTerm prev;
        synchronized (leadershipLock) {
            prev = term.getAndSet(null);
        }
        if (prev == null) {
            return;
        }
        log.warn("리더십 반납(회복 가능, 리스 만료 후 재승격 허용): resource={}, holder={}, epoch={}, 사유={}",
                resource, holder, prev.epoch(), reason);
        // 관측 먼저. 이 등급이 만드는 실패 모드는 [재생 실패 -> 반납 -> 재승격]의 반복이고,
        // epoch 계단만으로는 정상 페일오버와 구분되지 않는다.
        for (Runnable listener : standDownListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("회복 가능 반납 관측 콜백 실패", e);
            }
        }
        notifyReleased();
    }

    /**
     * 승격 재생 진입. 이 구간의 상실은 회복 가능 반납으로 내린다. 반환한 토큰은 짝이 되는
     * endPromotionReplay에 그대로 넘긴다.
     */
    public long beginPromotionReplay() {
        long token = promotionReplaySequence.incrementAndGet();
        long previous = promotionReplayToken.getAndSet(token);
        if (previous != 0) {
            // 승격 재생은 단일 승격 워커에서만 돈다. 겹치면 두 재생이 같은 루프에 엔진을 설치하려
            // 경쟁하고 강등 등급이 해제 순서에 좌우된다. 안전성이 스레드 수 1에 암묵 의존하던
            // 자리라 위반을 무음으로 두지 않는다.
            log.error("승격 재생 구간이 겹쳤다(단일 승격 워커 전제 위반): resource={}, 이전 토큰={}, 새 토큰={}",
                    resource, previous, token);
        }
        return token;
    }

    /**
     * 승격 재생 종료. 접수 개방 전에 반드시 호출해 이후의 상실이 강등으로 돌아오게 한다.
     *
     * @return 이 호출이 표식을 실제로 걷었으면 참. 낡은 시도의 뒤늦은 해제와 짝이 맞는 중복
     *         해제는 거짓이다. 호출자가 '아직 안 걷혀 있었다'를 관측해야 안전망 발동을 무음으로
     *         두지 않는다.
     */
    public boolean endPromotionReplay(long token) {
        return promotionReplayToken.compareAndSet(token, 0);
    }

    /** 관측·테스트용. 현재 승격 재생 구간이면 참이다. */
    public boolean inPromotionReplay() {
        return promotionReplayToken.get() != 0;
    }

    /**
     * 승격 시도. 만료 리스만 탈취 가능하며 성공 시 새 기간을 세운다. 거짓은 유효한 타 리더가 있거나, 후보가 아니거나,
     * 유예 중이거나, 옛 리더의 쓰기가 리스 행을 쥐어 잠금 대기 상한을 넘겼다는 뜻이다.
     */
    public boolean tryAcquire() {
        if (demoted || !armed) {
            return false;
        }
        WriteAvailability writes = writeAvailability;
        if (writes != null && !writes.isActive()) {
            // 커밋이 확인되지 않는 동안 획득하면 커밋 대기에 걸려 리스 스레드가 매달리고, 풀리는 순간 만료된 리스를 받는다.
            return false;
        }
        if (!acquirePolicy.mayAttempt(store)) {
            return false;
        }
        // 승격 전 구 리스 상태를 캡처해 다운타임을 산정한다(관측 전용, 승격 정확성과 무관).
        // 획득이 lease_until을 덮어쓰므로 반드시 취득 시도 전에 읽는다.
        double failoverDowntimeSeconds = store.priorDowntimeSeconds(ttl);
        Long acquired;
        try {
            acquired = store.acquire(holder, ttl).orElse(null);
        } catch (LeaderLeaseStore.AcquireLockTimeoutException e) {
            // 오류가 아니라 '아직 못 얻음'이다. 끊긴 획득은 트랜잭션째 롤백돼 아무것도 남기지 않았다.
            lockTimeoutStreak++;
            log.debug("리스 획득이 잠금 대기 상한을 넘겼다: resource={}, 연속={}", resource, lockTimeoutStreak);
            if (lockTimeoutStreak == LOCK_TIMEOUT_WARN_STREAK) {
                log.warn("리스 획득이 잠금 대기 상한을 {}회 연달아 넘겼다. 옛 리더의 쓰기 트랜잭션이 리스 행을 쥔 채 열려 있다: "
                        + "resource={}", lockTimeoutStreak, resource);
            }
            return false;
        }
        lockTimeoutStreak = 0;
        if (acquired == null) {
            return false;
        }
        if (writes != null && !store.holdsUnexpired(holder, acquired)) {
            // 획득 게이트 직전에 시작한 획득이 커밋 대기를 지나 풀렸다. lease_until 은 커밋 전에 정해져 이미 지났으므로 승격하면
            // 첫 쓰기와 첫 갱신에서 거부돼 곧바로 강등한다. 강등이 아니라 획득 실패로 두고, 다음 획득이 epoch 을 한 번 더 올린다.
            expiredAcquisitions.increment();
            log.warn("얻은 리스가 이미 만료돼 승격하지 않는다(커밋 대기 뒤 풀린 획득): resource={}, holder={}, epoch={}",
                    resource, holder, acquired);
            return false;
        }
        // 쓰기 경로가 볼 자리에 epoch을 먼저 올린다. 게시 전에 리더가 되면 구 리더의 epoch이
        // 남아 있는 동안 두 노드의 쓰기가 모두 통과한다. 게시하지 못한 노드는 어차피 쓸 수
        // 없으므로 승격을 포기하는 것이 안전 측이다. 획득한 리스는 갱신하지 않아 TTL 후 소멸한다.
        if (!fencingPublisher.publish(holder, acquired)) {
            log.error("fencing epoch 게시 실패로 승격을 포기한다(갱신 없음, TTL 후 소멸): "
                    + "resource={}, holder={}, epoch={}", resource, holder, acquired);
            return false;
        }
        LeaderTerm next;
        synchronized (leadershipLock) {
            if (demoted || !armed) {
                // 취득과 강등·후보 해제가 경합했다. 강등이 우선한다. 획득한 DB 리스는 갱신하지
                // 않아 TTL 후 소멸하고 타 노드가 탈취한다(self-shutdown 계약 유지).
                // 로그를 남기는 이유: 이 경로는 epoch만 올리고 아무도 쓰지 않는 리스를 한 TTL
                // 동안 남긴다. 무음이면 사후에 'epoch가 왜 올랐는지'를 설명할 수 없다.
                log.error("승격과 강등이 경합해 획득 리스를 버린다(갱신 없음, TTL 후 소멸): "
                        + "resource={}, holder={}, epoch={}", resource, holder, acquired);
                return false;
            }
            next = new LeaderTerm(this, resource, holder, acquired, generations.incrementAndGet());
            term.set(next);
        }
        // 팔로워 구간의 수요·완료 누적은 이 노드의 리더 처리율이 아니다. 승격 시점에 창을
        // 다시 세우지 않으면 첫 표본이 팔로워 구간 전량을 완료 0과 함께 접어, 갓 승격한 리더가
        // 임계 시간 뒤 무조건 자발적 강등한다(§5.4 판정의 승격 경계 규칙).
        serviceHealth.onLeadershipGained();
        log.info("리더 승격: resource={}, holder={}, epoch={}, 세대={}", resource, holder, acquired,
                next.generation());
        LeaderIdentity identity = next.identity();
        // 관측 통지를 상태기계 통지보다 먼저 부른다. 상태기계가 받은 기간을 곧바로 놓거나(relinquish) 승격 워커가 반납하면
        // 그 반납 통지가 이 뒤에 와야 is_leader 게이지가 1 로 남지 않는다.
        for (AcquisitionListener listener : acquisitionListeners) {
            try {
                listener.onAcquired(identity, failoverDowntimeSeconds);
            } catch (RuntimeException e) {
                log.error("리더십 획득 관측 콜백 실패", e);
            }
        }
        for (Consumer<LeaderIdentity> listener : gainListeners) {
            try {
                listener.accept(identity);
            } catch (RuntimeException e) {
                log.error("리더십 획득 콜백 실패", e);
            }
        }
        for (Consumer<LeaderTerm> listener : termGainListeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                log.error("리더십 획득 콜백 실패", e);
            }
        }
        return true;
    }

    /**
     * 하트비트. epoch를 보존한 채 lease_until만 연장한다. false는 리더십 상실 확정이며
     * 강등 콜백을 발화한다. DB 접속 오류는 상실로 단정하지 않는다. 만료 여부를 알 수 없고,
     * 실제 쓰기는 fencing이 막아주므로 다음 틱 재시도가 안전하다.
     *
     * 갱신 전에 서비스 생존성을 먼저 본다(§5.4 자발적 강등). 하트비트만 살아 있고 요청 경로가
     * 멈춘 노드가 리스를 계속 연장하면, 스탠바이는 만료를 못 봐 승격하지 못하고 클러스터가
     * 무기한 정지한다.
     */
    public boolean renew() {
        LeaderTerm t = term.get();
        if (t == null) {
            return false;
        }
        LeaseExtension outcome = extendLease(t.epoch());
        if (outcome == LeaseExtension.EXTENDED) {
            return true;
        }
        // 자발적 강등. 리스 행에는 쓰지 않고 갱신만 포기해 승격을 만료 구동으로만 남긴다.
        // 즉시 반납은 그 규칙을 우회하는 경로가 되므로 하지 않는다.
        if (outcome == LeaseExtension.UNSERVICEABLE) {
            markLost(t, DemotionReason.STALL, "서비스 불능 지속(요청 풀 고갈 + 리더 쓰기 진행 0)");
        } else {
            markLost(t, DemotionReason.LEASE_LOST_WHILE_ACCEPTING, "renew 거부(만료 또는 탈취됨)");
        }
        return false;
    }

    /** 리스 연장 결과. UNSERVICEABLE은 DB에 묻지도 않은 상태이며 리스 행은 무변경이다. */
    private enum LeaseExtension { EXTENDED, REJECTED, UNSERVICEABLE }

    /**
     * lease_until을 미는 단일 경로(§5.4). renew_lease를 호출하는 코드는 이 메서드뿐이고
     * 생존성 판정이 그 안에 있으므로, '리스를 연장하는 경로는 모두 생존성 판정을 통과한다'가
     * 호출 지점 수와 무관하게 성립한다. 판정을 호출자마다 되풀이하면 하나만 빠져도 좀비 리더가
     * 그 경로로 리스를 무한 연장한다.
     *
     * 판정을 renew 틱보다 자주 부르는 것은 무해하다. 누적 창이라 표본이 잘게 쪼개져도 비율과
     * 초기화 규칙이 같고, 창 초기화는 여전히 누적 비율이 임계를 회복할 때만 일어난다.
     */
    private LeaseExtension extendLease(long currentEpoch) {
        if (!serviceHealth.serviceable()) {
            return LeaseExtension.UNSERVICEABLE;
        }
        return store.renew(holder, currentEpoch, ttl)
                ? LeaseExtension.EXTENDED : LeaseExtension.REJECTED;
    }

    /** 주기 구동: 리더면 갱신, 아니면 승격 시도. 테스트는 이 메서드 대신 개별 호출을 쓴다. */
    public synchronized void start() {
        if (scheduler != null) {
            // 동기화 없이는 두 start()가 null 검사를 함께 통과해 스케줄러가 이중 생성된다.
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-lease-" + resource);
            t.setDaemon(true);
            return t;
        });
        // 어느 보관소가 실제로 걸렸는지 라이브에서 확인할 단일 지점. 보관소는 설정으로 갈리는데
        // 코드에도 설정 파일에도 '지금 무엇이 걸렸는가' 가 한 곳에 없다.
        log.info("리스 보관소: {}, 자원={}, ttl={}, tick={}", store.describe(), resource, ttl, tickInterval);
        s.scheduleWithFixedDelay(this::tick, 0, tickInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler = s;
    }

    public void stop() {
        ScheduledExecutorService s;
        synchronized (this) {
            s = scheduler;
            scheduler = null;
        }
        if (s == null) {
            return;
        }
        s.shutdownNow();
        try {
            // 진행 중 tick의 종료를 기다린다. 대기 없이 반환하면 정지 후에도 renew/tryAcquire가
            // DB에 남아 강등·재기동 판정과 경합한다.
            s.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 틱 스레드가 돌고 있는가. 엔진을 끈 노드는 시작하지 않는다(DB 설계 §5.4-c 2). */
    public boolean started() {
        return scheduler != null;
    }

    @Override
    public void close() {
        stop();
    }

    private void tick() {
        try {
            if (isLeader()) {
                renew();
            } else {
                tryAcquire();
            }
        } catch (RuntimeException e) {
            // 일시 장애로 루프가 죽으면 재승격 경로가 사라진다. 기록만 하고 다음 틱에 재시도.
            log.warn("리스 틱 실패: resource={}, {}", resource, e.getMessage());
        }
    }

    /**
     * 상실 확정. 기간을 비우고 후보 표식을 내린 뒤 강등 리스너를 부른다. expected 가 있으면 지금 기간이 그것일 때만 한다.
     *
     * @return 이 호출이 기간을 실제로 놓았으면 참
     */
    private boolean markLost(LeaderTerm expected, DemotionReason reason, String detail) {
        // 승격 재생 구간의 상실은 강등이 아니다. 그 구간에서는 루프가 승격 전 상태 그대로이고
        // 접수도 열린 적이 없어, self-shutdown이 막으려는 '반쪽 리더'가 성립하지 않는다.
        if (inPromotionReplay()) {
            if (expected != null && term.get() != expected) {
                return false;
            }
            standDown(detail);
            return true;
        }
        LeaderTerm prev;
        synchronized (leadershipLock) {
            LeaderTerm cur = term.get();
            if (cur == null || (expected != null && cur != expected)) {
                return false;
            }
            // 표식을 기간 클리어와 원자 세팅한다. 순서가 갈리면 tryAcquire가 그 창을
            // 비집고 재승격한다. tryAcquire도 같은 락에서 재확인하므로 재승격이 차단된다.
            armed = false;
            term.set(null);
            prev = cur;
        }
        log.warn("리더십 상실(재구성 전 재획득 없음): resource={}, holder={}, epoch={}, 사유={}({})",
                resource, holder, prev.epoch(), reason, detail);
        notifyReleased();
        notifyLost(prev, reason, detail);
        return true;
    }

    private void notifyLost(LeaderTerm prev, DemotionReason reason, String detail) {
        for (Runnable listener : lossListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("리더십 상실 콜백 실패", e);
            }
        }
        for (TermLossListener listener : termLossListeners) {
            try {
                listener.lost(prev, reason, detail);
            } catch (RuntimeException e) {
                log.error("리더십 상실 콜백 실패", e);
            }
        }
    }

    private void notifyReleased() {
        for (Runnable listener : releaseListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("리더십 반납 콜백 실패", e);
            }
        }
    }
}
