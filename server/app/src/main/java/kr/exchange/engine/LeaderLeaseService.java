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
 *
 * 강등은 두 종류다(§5.4 강등 사유 분류). 가르는 축은 사유 문구가 아니라 **단일 라이터 루프가
 * 그 강등으로 정지했는가**다.
 *  - 종신(markLost): 루프를 halt시키는 강등. 인메모리 오더북이 타 리더의 커밋을 반영하지 못한
 *    stale이고 소비자가 사라졌으므로 리스만 다시 얻으면 반쪽 리더가 된다. 복귀는 재기동뿐이다.
 *  - 회복 가능(standDown): 접수를 연 적이 없고 루프를 건드리지도 않은 구간(승격 재생)의 반납.
 *    이 구간의 엔진은 승격 전 상태 그대로이고 다음 승격은 fresh 엔진 전량 재생으로 다시 시작한다.
 *    한 번의 일시 실패로 후보를 영구히 잃으면 2노드에서 클러스터가 리더 없이 정지한다
 *    (E2 라이브 실측: 양 노드 종신 강등 → 체결 영구 정지).
 * 어느 쪽이든 leader_lease에는 아무것도 쓰지 않는다. 다음 리더는 종전대로 TTL 만료 뒤
 * try_acquire_leader(epoch +1) 단독 UPDATE로만 정해지므로 이중 리더 차단 성질이 불변이다.
 *
 * lease_until을 미는 경로는 extendLease 하나뿐이다(§5.4 '연장 직전 생존성 판정'). 하트비트와
 * fencing 거부 재확인이 같은 UPDATE를 쓰므로, 판정을 호출자별로 두면 하나만 빠져도 좀비 리더가
 * 그 경로로 리스를 무한 연장한다.
 */
public class LeaderLeaseService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaderLeaseService.class);

    private final JdbcClient jdbc;
    private final Duration ttl;
    private final Duration tickInterval;
    // 갱신 직전 서비스 생존성 판정(§5.4 자발적 강등). 하트비트 전용 풀 격리(992e59b) 이후
    // 하트비트 생존성이 서비스 생존성과 분리돼, 요청 경로가 멈춘 노드도 리스만 갱신하는
    // 좀비 리더가 성립한다. 이 판정이 그 창을 닫는다.
    private final NodeServiceHealth serviceHealth;
    private final String holder = UUID.randomUUID().toString();
    // null이면 리더 아님. 값은 획득 시점의 epoch.
    private final AtomicReference<Long> epoch = new AtomicReference<>();
    // 상실 확정 후 재승격 금지(§5.4 self-shutdown). 강등 시점의 엔진 인메모리 상태는 타 리더의
    // 커밋을 반영하지 않은 stale이고 단일 라이터 루프는 halt로 영구 정지라, 리스만 다시 얻으면
    // '접수는 ACK되는데 소비자가 없는' 반쪽 리더가 된다. 복귀는 재생을 포함한 재기동뿐이다.
    // 자발적 강등(서비스 불능 지속)도 같은 종착 상태다. 강등 사유가 무엇이든 루프가 halt된 뒤의
    // 재승격은 동일하게 불건전하고, 서비스 불능이 확인된 노드가 리더십을 되찾으려 경쟁하면
    // 손상 노드 사이의 플래핑이 된다.
    private volatile boolean demoted;
    // 승격 재생 구간 표식. 이 구간의 상실은 종신이 아니라 회복 가능 반납으로 내린다. 근거는
    // 구간의 성질이다. PromotionCoordinator는 재생이 성공한 뒤에야 fresh 엔진을 루프에 설치하고
    // 접수를 열므로, 재생 도중의 루프는 승격 전 상태 그대로이며 게이트가 닫혀 아무것도 소비하지
    // 않는다. 종신 강등이 전제하는 'stale 엔진 + halt된 루프'가 성립하지 않는다.
    //
    // 불리언이 아니라 토큰인 이유: 회복 가능 반납 도입으로 [재생 실패 -> 반납 -> 만료 -> 같은
    // 노드가 재승격]이 정상 경로가 됐고, 앞선 시도의 뒤늦은 해제가 새 시도의 표식을 지울 수 있다.
    // 지워지면 새 시도의 재생 실패가 종신 강등으로 잘못 내려가 후보가 소진된다. 해제는 자기
    // 토큰에 대해서만 CAS로 성립시킨다. 0은 재생 구간 아님이다.
    private final java.util.concurrent.atomic.AtomicLong promotionReplayToken =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong promotionReplaySequence =
            new java.util.concurrent.atomic.AtomicLong();
    // 강등 확정(demoted+epoch 클리어)과 승격 커밋(epoch 세팅)을 상호배제한다. 이 락 없이는
    // markLost가 epoch를 비운 직후·demoted 세팅 전 창에서 tick의 tryAcquire가 재승격해
    // half-leader가 된다(TOCTOU).
    private final Object leadershipLock = new Object();
    private final List<Runnable> lossListeners = new CopyOnWriteArrayList<>();
    // 사유 불문 '리더십을 놓았다' 통지. 종신 강등과 회복 가능 반납 모두에서 발화한다. 관측
    // 전용(is_leader 게이지)이라 self-shutdown 부작용을 가진 리스너를 여기 걸면 안 된다.
    private final List<Runnable> releaseListeners = new CopyOnWriteArrayList<>();
    // 회복 가능 반납 전용 통지. 종신 강등에서는 발화하지 않는다. 관측 전용이다.
    private final List<Runnable> standDownListeners = new CopyOnWriteArrayList<>();
    // 승격 통지. 리스 획득 시점에 발화해 접수 개방 전 인메모리 선복원을 트리거한다(복구 RTO §3-a).
    private final List<Consumer<LeaderIdentity>> gainListeners = new CopyOnWriteArrayList<>();
    // 관측 통지. 승격 시 epoch + 페일오버 다운타임을 함께 넘긴다(하네스 CICD §5-1 단일 계산점).
    private final List<AcquisitionListener> acquisitionListeners = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;

    public LeaderLeaseService(JdbcClient jdbc, Duration ttl, Duration tickInterval) {
        this(jdbc, ttl, tickInterval, NodeServiceHealth.ALWAYS_SERVICEABLE);
    }

    public LeaderLeaseService(JdbcClient jdbc, Duration ttl, Duration tickInterval,
                              NodeServiceHealth serviceHealth) {
        if (serviceHealth == null) {
            throw new IllegalArgumentException("serviceHealth는 필수");
        }
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
        this.serviceHealth = serviceHealth;
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

    /**
     * 종신 강등 통지 등록. self-shutdown 부작용(단일 라이터 halt, 승격 상태기계 DEMOTED)은
     * 여기에만 건다. 회복 가능 반납에서는 발화하지 않는다.
     */
    public void onLeadershipLost(Runnable listener) {
        lossListeners.add(listener);
    }

    /** 리더십 반납 통지 등록(종신·회복 가능 공통). 관측 갱신 전용이다. */
    public void onLeadershipReleased(Runnable listener) {
        releaseListeners.add(listener);
    }

    /**
     * 회복 가능 반납 통지 등록(하네스 CICD §5-2 `exchange.leader.recoverable_standdown`).
     * 종신 강등과 구분해 세는 축이라 releaseListeners와 분리한다. 관측 전용이다.
     */
    public void onRecoverableStandDown(Runnable listener) {
        standDownListeners.add(listener);
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

    /**
     * 외부에서 확정된 상실 통지 경로(DB 설계 §5.4 'stale 리더 즉시 self-shutdown').
     * fencing 거부(INSERT 0행, assert_current_leader 예외)는 renew 틱보다 먼저
     * 상실을 확정하므로 다음 틱을 기다리지 않고 즉시 강등한다.
     */
    public void demote(String reason) {
        markLost(reason);
    }

    /**
     * fencing 거부를 만난 쓰기 경로의 권위 재확인. 거부 즉시 강등하면 정당한 현재 리더가 일시
     * 원인(리스 만료 직전의 슬로 윈도, DB 시계 비단조성, 하트비트 한 틱 밀림)만으로 영구
     * 후보에서 빠진다. E2 라이브에서 승격 123ms 뒤의 단 한 번의 거부가 클러스터를 정지시켰다.
     *
     * 판정은 renew_leader_lease 한 번이다. 이 함수는 (holder, epoch)가 여전히 리스 행의 값이고
     * 만료 전일 때만 참이므로, 참이면 이 노드가 정당한 현재 리더임이 DB로 증명된다. 만료 리스를
     * 대상으로 하는 UPDATE가 아니라 탈취가 불가능하고 epoch도 건드리지 않아, 이 경로가 이중
     * 리더를 만들 수 없다.
     *
     * @return true면 리더십이 증명돼 강등하지 않았다(호출자는 재시도 가능). false면 강등했거나
     *         (거짓 반환) 증명에 실패했다. DB 왕복 자체가 실패한 경우는 상실로 단정하지 않는다.
     */
    public boolean demoteUnlessStillLeader(String reason) {
        Long e = epoch.get();
        if (e == null) {
            return false;
        }
        LeaseExtension outcome;
        try {
            outcome = extendLease(e);
        } catch (RuntimeException ex) {
            // 재확인 왕복 실패는 만료 여부를 알려주지 않는다. renew()와 같은 규칙으로 상실을
            // 단정하지 않는다. 실제 쓰기는 fencing이 계속 막으므로 안전하고, 판정은 다음 틱 몫이다.
            log.warn("fencing 거부 후 리더십 재확인 왕복 실패: 사유={}, 오류={}", reason, ex.toString());
            return false;
        }
        switch (outcome) {
            case EXTENDED -> {
                log.warn("fencing 거부를 받았으나 리스 권위는 유지된다(강등하지 않음): epoch={}, 사유={}",
                        e, reason);
                return true;
            }
            case UNSERVICEABLE -> {
                // 재확인은 곧 리스 연장이다. 생존성 판정을 통과하지 못한 노드까지 연장해 주면
                // §5.4의 '연장 직전 생존성 판정'이 이 경로에서만 무력해진다.
                markLost("서비스 불능 지속(fencing 거부 재확인): " + reason);
                return false;
            }
            default -> {
                markLost(reason);
                return false;
            }
        }
    }

    /**
     * 회복 가능 반납. 승격 재생이 접수 개방 전에 실패했을 때만 쓴다. 리스 행에는 아무것도 쓰지
     * 않고 갱신만 멈추므로, 다음 리더는 종전대로 TTL 만료 뒤 try_acquire_leader(epoch +1)로만
     * 정해진다. demoted를 세우지 않아 이 노드도 다시 후보가 된다.
     */
    public void standDown(String reason) {
        Long prev;
        synchronized (leadershipLock) {
            prev = epoch.getAndSet(null);
        }
        if (prev == null) {
            return;
        }
        log.warn("리더십 반납(회복 가능, 리스 만료 후 재승격 허용): holder={}, epoch={}, 사유={}",
                holder, prev, reason);
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
            log.error("승격 재생 구간이 겹쳤다(단일 승격 워커 전제 위반): 이전 토큰={}, 새 토큰={}",
                    previous, token);
        }
        return token;
    }

    /** 승격 재생 종료. 접수 개방 전에 반드시 호출해 이후의 상실이 종신으로 돌아오게 한다. */
    public void endPromotionReplay(long token) {
        promotionReplayToken.compareAndSet(token, 0);
    }

    /** 관측·테스트용. 현재 승격 재생 구간이면 참이다. */
    public boolean inPromotionReplay() {
        return promotionReplayToken.get() != 0;
    }

    /**
     * 승격 시도. 만료 리스만 탈취 가능하며 성공 시 epoch가 +1 된 값으로 반환된다.
     * 실패(NULL)는 유효한 타 리더 존재를 뜻한다.
     */
    public boolean tryAcquire() {
        if (demoted) {
            return false;
        }
        // 승격 전 구 리스 상태를 캡처해 다운타임을 산정한다(관측 전용, 승격 정확성과 무관).
        // try_acquire_leader가 lease_until을 덮어쓰므로 반드시 취득 시도 전에 읽는다.
        double failoverDowntimeSeconds = capturePriorDowntimeSeconds();
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
                // 로그를 남기는 이유: 이 경로는 epoch만 올리고 아무도 쓰지 않는 리스를 한 TTL
                // 동안 남긴다. 무음이면 사후에 'epoch가 왜 올랐는지'를 설명할 수 없다.
                log.error("승격과 종신 강등이 경합해 획득 리스를 버린다(갱신 없음, TTL 후 소멸): "
                        + "holder={}, epoch={}", holder, acquired);
                return false;
            }
            epoch.set(acquired);
        }
        // 팔로워 구간의 수요·완료 누적은 이 노드의 리더 처리율이 아니다. 승격 시점에 창을
        // 다시 세우지 않으면 첫 표본이 팔로워 구간 전량을 완료 0과 함께 접어, 갓 승격한 리더가
        // 임계 시간 뒤 무조건 자발적 강등한다(§5.4 판정의 승격 경계 규칙).
        serviceHealth.onLeadershipGained();
        log.info("리더 승격: holder={}, epoch={}", holder, acquired);
        LeaderIdentity identity = new LeaderIdentity(holder, acquired);
        for (Consumer<LeaderIdentity> listener : gainListeners) {
            try {
                listener.accept(identity);
            } catch (RuntimeException e) {
                log.error("리더십 획득 콜백 실패", e);
            }
        }
        for (AcquisitionListener listener : acquisitionListeners) {
            try {
                listener.onAcquired(identity, failoverDowntimeSeconds);
            } catch (RuntimeException e) {
                log.error("리더십 획득 관측 콜백 실패", e);
            }
        }
        return true;
    }

    /**
     * 페일오버 다운타임 = 승격 시각 − 구 리더 최종 심박. 구 리더 최종 심박 = old_lease_until − ttl
     * (같은 ttl로 renew하므로, 하네스 CICD §5-1 T1). 시드(holder='')나 실 페일오버가 아닌
     * 최초 기동 승격은 0을 돌려 게이지 오염을 막는다. 읽기 실패는 0으로 흡수해 승격을 막지 않는다.
     */
    private double capturePriorDowntimeSeconds() {
        try {
            Double expiredSeconds = jdbc.sql("""
                    SELECT CASE WHEN holder <> ''
                                THEN EXTRACT(EPOCH FROM (clock_timestamp() - lease_until))
                                ELSE NULL END
                    FROM leader_lease WHERE resource = 'matching-engine'
                    """)
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

    /**
     * 하트비트. epoch를 보존한 채 lease_until만 연장한다. false는 리더십 상실 확정이며
     * 상실 콜백을 발화한다. DB 접속 오류는 상실로 단정하지 않는다. 만료 여부를 알 수 없고,
     * 실제 쓰기는 fencing이 막아주므로 다음 틱 재시도가 안전하다.
     *
     * 갱신 전에 서비스 생존성을 먼저 본다(§5.4 자발적 강등). 하트비트만 살아 있고 요청 경로가
     * 멈춘 노드가 리스를 계속 연장하면, 스탠바이는 만료를 못 봐 승격하지 못하고 클러스터가
     * 무기한 정지한다.
     */
    public boolean renew() {
        Long e = epoch.get();
        if (e == null) {
            return false;
        }
        LeaseExtension outcome = extendLease(e);
        if (outcome == LeaseExtension.EXTENDED) {
            return true;
        }
        // 자발적 강등. 리스 행에는 아무것도 쓰지 않고 갱신만 포기한다. 승격은 종전대로
        // TTL 만료 후 스탠바이의 try_acquire_leader(epoch +1) 단독 UPDATE로만 일어나므로
        // 승격 원자성·fencing 단조성·이중 리더 차단 성질이 그대로다. 리스를 즉시 반납하지
        // 않는 것도 같은 이유다. 만료 구동 승격 규칙을 우회하는 경로를 만들지 않는다.
        markLost(outcome == LeaseExtension.UNSERVICEABLE
                ? "서비스 불능 지속(요청 풀 고갈 + 리더 쓰기 진행 0)"
                : "renew 거부(만료 또는 탈취됨)");
        return false;
    }

    /** 리스 연장 결과. UNSERVICEABLE은 DB에 묻지도 않은 상태이며 리스 행은 무변경이다. */
    private enum LeaseExtension { EXTENDED, REJECTED, UNSERVICEABLE }

    /**
     * lease_until을 미는 단일 경로(§5.4). renew_leader_lease를 호출하는 코드는 이 메서드뿐이고
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
        Boolean renewed = jdbc.sql("SELECT renew_leader_lease(?, ?, ? * interval '1 millisecond')")
                .params(holder, currentEpoch, ttl.toMillis())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(renewed) ? LeaseExtension.EXTENDED : LeaseExtension.REJECTED;
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
        // 승격 재생 구간의 상실은 종신이 아니다. 그 구간에서는 루프가 승격 전 상태 그대로이고
        // 접수도 열린 적이 없어, self-shutdown이 막으려는 '반쪽 리더'가 성립하지 않는다.
        if (inPromotionReplay()) {
            standDown(reason);
            return;
        }
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
        notifyReleased();
        for (Runnable listener : lossListeners) {
            try {
                listener.run();
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
