package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.PartitionLeases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 파티션 밖 취소의 작업자(DB 설계 §5.4-d 3·5·6, S5 설계 4-4). 엔진을 켠 노드에만 둔다. 계획의 파티션마다 workers 개의 스레드가
 * 그 파티션의 접수 게이트가 연 기간을 보고, 열려 있으면 그 기간의 holder·epoch 로 인계 행 하나를 점유해 보통의 취소 경로로
 * 처리한 뒤 결과와 함께 끝낸다. 점유와 끝내기는 리스 행이 그 holder·epoch 로 유효할 때만 된다.
 *
 * 주문의 결말은 둘로 가른다. 취소됨, 먼저 종결, order_not_found, order_in_progress, market_suspended, 경합의
 * too_many_requests, append 의 DB 연결 장애 service_unavailable 은 결과에 적는다. 취소 전 조회나 재조회가 DB 에 닿지 못하면
 * 그 주문과 남은 주문을 service_unavailable 로 적고, 그 밖의 예외(결함)는 그 주문을 server_error 로 적는다. 리더 자격을
 * 잃었다는 거부(리더 자격 거부, 라이터 정지, 승격 재생 중)는 적지 않는다. 게이트가 같은 기간으로 열려 있으면 poll 뒤 그
 * 주문부터 이어 하고, 같은 주문에서 세 번 이어지면 로컬 일괄 취소가 같은 경우에 적는 사유로 적고 넘어간다. 게이트가
 * 닫혔으면 행을 CLAIMED 로 둔 채 멈춘다. 그 행은 더 큰 epoch 의 작업자가 다시 점유한다. 그래서 리스를 잃은 노드의 실패가
 * 결과로 굳지 않는다.
 *
 * 스레드는 VM 오류 밖의 모든 예외를 잡고 계속 돈다. 행 처리 밖으로 새는 예외(결함)는 그 행을 CLAIMED 로 두고 ERROR 로그와
 * error 계수를 남긴다. 그 행은 기간이 끝나 더 큰 epoch 가 다시 점유하거나 만료로 닫힌다. 스레드는 또 게이트가 열린 동안
 * expire-interval 마다 만료 문장을 돌린다.
 *
 * 작업자 수를 1로 두는 이유는 연결 예산이다. 작업자는 수용 제한 밖이라 요청 풀의 예비분을 정산 루프·퍼블리셔·하네스 상태와
 * 나눠 쓴다(DB 설계 §5.4-d 3). 인계받은 취소는 계정 레이트리밋을 쓰지 않는다(API 설계 §2-5).
 */
public final class CancelHandoffWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CancelHandoffWorker.class);

    /** 같은 주문에서 게이트가 열린 리더 자격 거부를 이어 하는 상한(DB 설계 §5.4-d 3). */
    static final int RESUME_LIMIT = 3;
    // 점유·만료 문장의 DB 오류 로그 간격. DB 가 끊긴 동안 poll 마다 경고를 쌓지 않는다.
    private static final long DB_WARN_INTERVAL_NANOS = 10_000_000_000L;

    /** 주문 하나를 처리하기 전의 시험 이음매. */
    @FunctionalInterface
    interface OrderHook {
        void before(OrderCancelHandoffRepository.Claimed row, int index) throws InterruptedException;
    }

    /** 끝내기 직전의 시험 이음매. 던지면 행 처리 밖으로 새는 예외가 된다. */
    @FunctionalInterface
    interface RowHook {
        void before(OrderCancelHandoffRepository.Claimed row);
    }

    private final OrderCancelHandoffRepository repo;
    private final OrderGateway gateway;
    private final PartitionLeases leases;
    private final OrderCancelHandoff.Settings settings;
    private final CancelHandoffCounters counters;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private volatile long lastDbWarnNanos;
    volatile OrderHook beforeOrder = (row, index) -> { };
    volatile RowHook beforeComplete = row -> { };

    public CancelHandoffWorker(OrderCancelHandoffRepository repo, OrderGateway gateway, PartitionLeases leases,
                               OrderCancelHandoff.Settings settings, CancelHandoffCounters counters) {
        this.repo = repo;
        this.gateway = gateway;
        this.leases = leases;
        this.settings = settings;
        this.counters = counters;
    }

    /** 계획의 파티션마다 스레드를 띄운다. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (String resource : leases.plan().partitions()) {
            for (int i = 0; i < settings.workers(); i++) {
                Thread thread = new Thread(() -> loop(resource), "cancel-handoff-" + resource + "-" + i);
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
        }
        log.info("인계 작업자 시작: 파티션 {}, 파티션당 {}개, poll {}ms, 만료 간격 {}초", leases.plan().partitions(),
                settings.workers(), settings.poll().toMillis(), settings.expireInterval().toSeconds());
    }

    @Override
    public synchronized void close() {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
    }

    /** 살아 있는 작업자 스레드 수. 시험이 스레드가 예외로 죽지 않았는지 본다. */
    int aliveThreads() {
        synchronized (this) {
            return (int) threads.stream().filter(Thread::isAlive).count();
        }
    }

    private void loop(String resource) {
        long nextExpire = System.nanoTime();
        while (running) {
            try {
                LeaderTerm term = leases.openTermOf(resource);
                if (term == null) {
                    pause();
                    continue;
                }
                if (System.nanoTime() - nextExpire >= 0) {
                    nextExpire = System.nanoTime() + settings.expireInterval().toNanos();
                    counters.worker("expired", repo.expire());
                }
                Optional<OrderCancelHandoffRepository.Claimed> claimed =
                        repo.claim(resource, term.holder(), term.epoch());
                if (claimed.isEmpty()) {
                    pause();
                    continue;
                }
                counters.worker(claimed.get().reclaimed() ? "reclaimed" : "claimed");
                process(claimed.get(), term);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (DataAccessException | TransactionException db) {
                // 점유·만료 문장의 DB 오류는 행 처리 전이다. 결함이 아니므로 error 로 세지 않고 다음 poll 에 다시 본다.
                warnDb(resource, db);
                pause();
            } catch (Throwable leaked) {
                if (!running) {
                    break;
                }
                counters.worker("error");
                log.error("인계 작업자의 행 처리 밖으로 예외가 샜다. 그 행은 CLAIMED 로 남고 더 큰 epoch 나 만료가 닫는다: 파티션={}",
                        resource, leaked);
                pause();
            }
        }
    }

    /**
     * 점유한 행을 처리한다. 주문마다 먼저 게이트가 점유 때와 같은 기간으로 열려 있는지 보고, 아니면 멈춘다. 다 하면 기간을 한
     * 번 더 보고 끝낸다.
     */
    private void process(OrderCancelHandoffRepository.Claimed row, LeaderTerm term) throws InterruptedException {
        BulkCancelTally result = new BulkCancelTally();
        List<UUID> ids = row.orderIds();
        int i = 0;
        int resumes = 0;
        while (i < ids.size()) {
            if (!running) {
                return;
            }
            if (!sameTerm(row, term)) {
                counters.worker("stopped");
                return;
            }
            beforeOrder.before(row, i);
            UUID id = ids.get(i);
            OrderGateway.HandoffOutcome outcome = gateway.cancelForHandoff(row.userId(), id);
            switch (outcome.kind()) {
                case CANCELED -> {
                    result.canceled(id.toString());
                    i++;
                    resumes = 0;
                }
                case RECORDED -> {
                    result.failed(id.toString(), outcome.reason());
                    i++;
                    resumes = 0;
                }
                case DEFECT -> {
                    counters.worker("error");
                    log.error("인계 취소가 예기치 않은 예외로 끝났다. 그 주문을 server_error 로 적고 넘어간다: row={}, order={}",
                            row.id(), id, outcome.cause());
                    result.failed(id.toString(), BulkCancelTally.SERVER_ERROR);
                    i++;
                    resumes = 0;
                }
                case UNREACHABLE -> {
                    // DB 에 닿지 못했다. 남은 주문의 취소 전 조회도 같은 장애로 끝나므로 시도하지 않는다.
                    for (; i < ids.size(); i++) {
                        result.failed(ids.get(i).toString(), BulkCancelTally.SERVICE_UNAVAILABLE);
                    }
                }
                case LEADER_LOST -> {
                    if (!sameTerm(row, term)) {
                        // 게이트가 닫혔다. 결과를 적지 않고 CLAIMED 로 둔다. 더 큰 epoch 의 작업자가 다시 점유한다.
                        counters.worker("stopped");
                        return;
                    }
                    if (++resumes >= RESUME_LIMIT) {
                        result.failed(id.toString(), outcome.reason());
                        i++;
                        resumes = 0;
                    } else {
                        // 재확인이 참이었거나 재확인 왕복이 실패했다. 같은 기간이므로 그 주문부터 이어 한다.
                        counters.worker("resumed");
                        pause();
                    }
                }
            }
        }
        if (!sameTerm(row, term)) {
            counters.worker("stopped");
            return;
        }
        beforeComplete.before(row);
        String json = result.toResultJson(mapper);
        while (true) {
            try {
                int done = repo.complete(row.id(), term.holder(), term.epoch(), json);
                counters.worker(done == 1 ? "completed" : "complete_lost");
                return;
            } catch (DataAccessException | TransactionException db) {
                if (!running || !sameTerm(row, term)) {
                    counters.worker("stopped");
                    return;
                }
                warnDb(row.resource(), db);
                pause();
            }
        }
    }

    private boolean sameTerm(OrderCancelHandoffRepository.Claimed row, LeaderTerm term) {
        return leases.openTermOf(row.resource()) == term;
    }

    private void pause() {
        try {
            Thread.sleep(settings.poll().toMillis());
        } catch (InterruptedException e) {
            // close() 가 running 을 먼저 내리고 깨운다. 표식을 다시 세우면 다음 sleep 이 곧바로 끝나 빈 회전이 된다.
        }
    }

    private void warnDb(String resource, RuntimeException e) {
        long now = System.nanoTime();
        if (now - lastDbWarnNanos >= DB_WARN_INTERVAL_NANOS) {
            lastDbWarnNanos = now;
            log.warn("인계 작업자의 DB 문장이 실패했다. 다음 poll 에 다시 본다: 파티션={}, 원인={}", resource, e.toString());
        }
    }
}
