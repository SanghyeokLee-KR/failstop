package kr.exchange.engine;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * leader_lease 단일 라이터 리스 관리(DB 설계 v2.9 §3.1 6.6, §5.4).
 *
 * holder는 프로세스 기동마다 새로 발급하는 UUID다. 호스트명을 쓰면 같은 호스트의
 * 구/신 프로세스를 구분하지 못해 fencing이 무력화된다(P0-1).
 * 승격은 try_acquire_leader(만료 리스만 탈취, epoch +1), 유지는 renew_leader_lease
 * (epoch 보존)로만 한다. 정본은 V1 함수 3종이고 여기서는 호출만 한다.
 */
public class LeaderLeaseService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaderLeaseService.class);

    private final JdbcClient jdbc;
    private final Duration ttl;
    private final Duration tickInterval;
    private final String holder = UUID.randomUUID().toString();
    // null이면 리더 아님. 값은 획득 시점의 epoch.
    private final AtomicReference<Long> epoch = new AtomicReference<>();
    // 상실 확정 후 재승격 금지(§5.4 self-shutdown). 강등 시점의 엔진 인메모리 상태는 타 리더의
    // 커밋을 반영하지 않은 stale이고 단일 라이터 루프는 halt로 영구 정지라, 리스만 다시 얻으면
    // '접수는 ACK되는데 소비자가 없는' 반쪽 리더가 된다. 복귀는 재생을 포함한 재기동뿐이다.
    private volatile boolean demoted;
    // 강등 확정(demoted+epoch 클리어)과 승격 커밋(epoch 세팅)을 상호배제한다. 이 락 없이는
    // markLost가 epoch를 비운 직후·demoted 세팅 전 창에서 tick의 tryAcquire가 재승격해
    // half-leader가 된다(TOCTOU).
    private final Object leadershipLock = new Object();
    private final List<Runnable> lossListeners = new CopyOnWriteArrayList<>();
    // 승격 통지. 리스 획득 시점에 발화해 접수 개방 전 인메모리 선복원을 트리거한다(복구 RTO §3-a).
    private final List<Consumer<LeaderIdentity>> gainListeners = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;

    public LeaderLeaseService(JdbcClient jdbc, Duration ttl, Duration tickInterval) {
        if (jdbc == null || ttl == null || tickInterval == null) {
            throw new IllegalArgumentException("jdbc·ttl·tickInterval은 필수");
        }
        if (ttl.isZero() || ttl.isNegative() || tickInterval.isZero() || tickInterval.isNegative()) {
            throw new IllegalArgumentException("ttl·tickInterval은 양수여야 한다");
        }
        if (tickInterval.compareTo(ttl) >= 0) {
            // 갱신 주기가 TTL 이상이면 정상 리더도 만료를 반복해 탈취/복구 플래핑이 된다.
            throw new IllegalArgumentException("tickInterval은 ttl보다 짧아야 한다");
        }
        this.jdbc = jdbc;
        this.ttl = ttl;
        this.tickInterval = tickInterval;
    }

    public String holder() {
        return holder;
    }

    /** 복구 재적재 등 스케줄러 밖 장기 구간이 자기-fencing 전에 renew 주기를 정할 때 쓴다. */
    public Duration ttl() {
        return ttl;
    }

    public boolean isLeader() {
        return epoch.get() != null;
    }

    public Optional<LeaderIdentity> current() {
        Long e = epoch.get();
        return e == null ? Optional.empty() : Optional.of(new LeaderIdentity(holder, e));
    }

    /** 쓰기 트랜잭션 진입점. 리더가 아니면 DB 왕복 없이 즉시 거부한다. */
    public LeaderIdentity requireLeader() {
        Long e = epoch.get();
        if (e == null) {
            throw new NotLeaderException("리더 아님: holder=" + holder);
        }
        return new LeaderIdentity(holder, e);
    }

    public void onLeadershipLost(Runnable listener) {
        lossListeners.add(listener);
    }

    /**
     * 승격 통지 등록(markLost 대칭). tryAcquire 성공 시 epoch 세팅 직후 발화한다. 리스너는
     * 리스 스케줄러 스레드에서 짧게 반환해야 한다. 대규모 재생은 별도 워커로 넘겨 renew를
     * 막지 않는다(복구 RTO §3-a 승격 중 리스 유지).
     */
    public void onLeadershipGained(Consumer<LeaderIdentity> listener) {
        gainListeners.add(listener);
    }

    /**
     * 외부에서 확정된 상실 통지 경로(DB 설계 §5.4 'stale 리더 즉시 self-shutdown').
     * fencing 거부(INSERT 0행, assert_current_leader 예외)는 renew 틱보다 먼저
     * 상실을 확정하므로 다음 틱을 기다리지 않고 즉시 강등한다.
     */
    public void demote(String reason) {
        markLost(reason);
    }

    /**
     * 승격 시도. 만료 리스만 탈취 가능하며 성공 시 epoch가 +1 된 값으로 반환된다.
     * 실패(NULL)는 유효한 타 리더 존재를 뜻한다.
     */
    public boolean tryAcquire() {
        if (demoted) {
            return false;
        }
        // 실패는 NULL 반환 계약이라 single()이 아니라 optional()로 받는다.
        Long acquired = jdbc.sql("SELECT try_acquire_leader(?, ? * interval '1 millisecond')")
                .params(holder, ttl.toMillis())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (acquired == null) {
            return false;
        }
        synchronized (leadershipLock) {
            if (demoted) {
                // 취득과 강등이 경합했다. 재승격 금지가 우선한다. 획득한 DB 리스는 갱신하지
                // 않아 TTL 후 소멸하고 타 노드가 탈취한다(self-shutdown 계약 유지).
                return false;
            }
            epoch.set(acquired);
        }
        log.info("리더 승격: holder={}, epoch={}", holder, acquired);
        LeaderIdentity identity = new LeaderIdentity(holder, acquired);
        for (Consumer<LeaderIdentity> listener : gainListeners) {
            try {
                listener.accept(identity);
            } catch (RuntimeException e) {
                log.error("리더십 획득 콜백 실패", e);
            }
        }
        return true;
    }

    /**
     * 하트비트. epoch를 보존한 채 lease_until만 연장한다. false는 리더십 상실 확정이며
     * 상실 콜백을 발화한다. DB 접속 오류는 상실로 단정하지 않는다. 만료 여부를 알 수 없고,
     * 실제 쓰기는 fencing이 막아주므로 다음 틱 재시도가 안전하다.
     */
    public boolean renew() {
        Long e = epoch.get();
        if (e == null) {
            return false;
        }
        Boolean renewed = jdbc.sql("SELECT renew_leader_lease(?, ?, ? * interval '1 millisecond')")
                .params(holder, e, ttl.toMillis())
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(renewed)) {
            return true;
        }
        markLost("renew 거부(만료 또는 탈취됨)");
        return false;
    }

    /** 주기 구동: 리더면 갱신, 아니면 승격 시도. 테스트는 이 메서드 대신 개별 호출을 쓴다. */
    public synchronized void start() {
        if (scheduler != null) {
            // 동기화 없이는 두 start()가 null 검사를 함께 통과해 스케줄러가 이중 생성된다.
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-lease");
            t.setDaemon(true);
            return t;
        });
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
            log.warn("리스 틱 실패: {}", e.getMessage());
        }
    }

    private void markLost(String reason) {
        Long prev;
        synchronized (leadershipLock) {
            // demoted를 epoch 클리어와 원자 세팅한다. 순서가 갈리면 tryAcquire가 그 창을
            // 비집고 재승격한다. tryAcquire도 같은 락에서 재확인하므로 재승격이 차단된다.
            demoted = true;
            prev = epoch.getAndSet(null);
        }
        if (prev == null) {
            return;
        }
        log.warn("리더십 상실(재승격 금지, 복귀는 재기동): holder={}, epoch={}, 사유={}",
                holder, prev, reason);
        for (Runnable listener : lossListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("리더십 상실 콜백 실패", e);
            }
        }
    }
}
