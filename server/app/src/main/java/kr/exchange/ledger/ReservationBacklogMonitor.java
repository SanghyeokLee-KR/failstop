package kr.exchange.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 정산 전 예약 감시(ADR 0034 S3, S3 설계 4-16). 가장 오래된 정산 전 예약의 나이와 그 수를 게이지로 낸다.
 *
 * P2 에서는 주문 생성 명령의 자금이 접수 트랜잭션에서 예약되고 정산이 그 예약을 주문 행으로 넘기거나(ACCEPTED) 푼다
 * (REJECTED). 정산이 멈춘 마켓이나 재적재가 막힌 경우 예약이 LOCKED 에 머문다. 정상 대기는 밀리초이고 리더 장애 뒤 RTO
 * 목표 30초 안의 대기는 경보 문턱(60초) 아래다. 경보 ReservationUnsettledStale 은 노드 값의 최댓값이 60초를 넘은 상태가
 * 2분 이어질 때 울린다(harness/prometheus/rules/exchange-alerts.yml).
 *
 * 왜 전용 빈인가. 첫 설계는 불변식 검증기의 5분 상시 실행에서 계산했다. 값이 5분에 한 번만 바뀌면 2분 지속 조건이 뜻을
 * 잃고, 그 검증기는 리더와 하네스를 켠 배포에서만 돈다(S3 설계 11절 R8). 그래서 모든 노드에서 15초마다 따로 잰다. 조회 역할
 * 노드는 뺀다. 그 로그인은 저널과 예약 표를 읽지 못해 질의가 매번 실패한다(EngineConfig.reservationBacklogMonitor).
 * 스케줄링을 켜는 설정이 하네스·시뮬레이션 설정에만 있으므로 @Scheduled 를 쓰지 않고 전용 단일 스레드 실행기를 쓴다.
 *
 * 질의는 C14' 의 정산 전 예약 항과 같은 모양이다(마켓별 정산 커서 위의 NEW 만 LIMIT 를 단 LATERAL 로 읽는다). 누적 행
 * 수가 아니라 정산 대기 명령 수에 비례하고, 합성 100만·300만 행에서 JIT 을 끄면 3ms 였다(S3 설계 3절 P8). 계획 비용
 * 추정이 커서 JIT 이 붙으면 컴파일(약 0.2초)이 실행보다 길어 트랜잭션 안에서 끈다. 질의가 실패하면 두 게이지를 NaN 으로
 * 두고 실패 계수를 올린다. NaN 은 경보 조건을 채우지 않는다. P1 에서는 예약 행이 없어 두 값이 0 이다.
 */
public class ReservationBacklogMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReservationBacklogMonitor.class);

    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);

    // LIMIT 는 seq 가 작은 쪽부터 남긴다. 마켓 안의 seq 순서는 created_at 순서와 같으므로(한 마켓의 append 는 접수 락 안에서
    // 한 줄로 서고 created_at 은 그 트랜잭션의 now() 다) 대기가 10만 건을 넘어도 가장 오래된 예약은 잘리지 않는다.
    static final String QUERY = """
            SELECT count(*) AS pending,
                   COALESCE(floor(EXTRACT(EPOCH FROM clock_timestamp() - min(r.created_at))), 0) AS oldest_s
            FROM markets m
            CROSS JOIN LATERAL (SELECT max(e.source_cmd_seq) AS settled FROM event_journal e WHERE e.market_id = m.id) cur
            CROSS JOIN LATERAL (SELECT c.seq FROM command_journal c
                                 WHERE c.market_id = m.id AND c.command_type = 'NEW' AND c.seq > COALESCE(cur.settled, -1)
                                 ORDER BY c.seq LIMIT 100000) c
            CROSS JOIN LATERAL (SELECT r.created_at FROM order_reservations r
                                 WHERE r.command_seq = c.seq
                                   AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)
                                 LIMIT 1) r
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;
    private final Counter queryFailed;
    private volatile double pending = Double.NaN;
    private volatile double oldestSeconds = Double.NaN;
    private ScheduledExecutorService executor;

    public ReservationBacklogMonitor(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry registry) {
        this(jdbc, tx, registry, DEFAULT_INTERVAL);
    }

    public ReservationBacklogMonitor(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry registry,
                                     Duration interval) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.interval = interval;
        Gauge.builder("exchange.reservation.unsettled.oldest.seconds", this, m -> m.oldestSeconds)
                .description("정산 전 예약 가운데 가장 오래된 것의 나이(초). 질의 실패면 NaN")
                .register(registry);
        Gauge.builder("exchange.reservation.unsettled.count", this, m -> m.pending)
                .description("정산 전 예약 수(마켓당 10만에서 멈춘다). 질의 실패면 NaN")
                .register(registry);
        this.queryFailed = Counter.builder("exchange.reservation.backlog.query.failed")
                .description("정산 전 예약 감시 질의가 실패한 횟수")
                .register(registry);
    }

    /** 게이지를 한 번 계산한다. 실패는 삼키고 NaN 과 실패 계수로 남긴다. */
    public void refresh() {
        try {
            double[] values = tx.execute(status -> {
                // 결과를 바꾸지 않는 성능 설정이다. 트랜잭션 한정이라 풀 커넥션에 남지 않는다.
                jdbc.sql("SET LOCAL jit = off").update();
                return jdbc.sql(QUERY)
                        .query((rs, n) -> new double[] {rs.getLong("pending"), rs.getLong("oldest_s")})
                        .single();
            });
            pending = values[0];
            oldestSeconds = values[1];
        } catch (RuntimeException e) {
            pending = Double.NaN;
            oldestSeconds = Double.NaN;
            queryFailed.increment();
            log.warn("정산 전 예약 감시 질의 실패. 게이지를 NaN 으로 둔다: {}", e.toString());
        }
    }

    public double pending() {
        return pending;
    }

    public double oldestSeconds() {
        return oldestSeconds;
    }

    public double queryFailures() {
        return queryFailed.count();
    }

    /** 전용 실행기로 주기 계산을 시작한다. 두 번 불러도 하나만 돈다. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reservation-backlog-monitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
