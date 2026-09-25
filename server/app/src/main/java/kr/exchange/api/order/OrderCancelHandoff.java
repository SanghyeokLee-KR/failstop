package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.journal.DbOutageErrors;
import kr.exchange.journal.TransientDbErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 파티션 밖 취소의 요청자(DB 설계 §5.4-d 1·2, S5 설계 4-4). 마켓 없는 일괄 취소와 회원 폐쇄는 여러 파티션의 주문을 한 번에
 * 다룬다. 이 노드가 접수 게이트를 연 파티션의 주문은 게이트웨이가 직접 취소하고, 나머지 파티션의 주문은 목록째 그 파티션의
 * 소유 노드에 넘긴다(V54 order_cancel_requests). 소유 노드의 작업자가 보통의 취소 경로(저널 append 와 DB fencing)로 취소하고
 * 결과를 적는다. 인계는 취소를 누가 저널에 넣는지만 바꾼다.
 *
 * 요청자는 행을 넣은 뒤(직접 취소보다 먼저) 직접 취소를 하고, 넣은 뒤 wait(기본 5초)까지 100ms 간격으로 행을 읽는다. 모두
 * DONE 이면 일찍 끝낸다. 끝나면 가져가지 않은 행을 포기로 바꾸고 다시 읽어 합친다. 같은 파티션에서 이 요청의 행을 소유 노드가
 * 하나라도 가져갔으면 그 파티션의 남은 행은 포기하지 않는다. 포기한 행의 주문은 partition_not_started(이 요청은 그 주문에 아무
 * 명령도 넣지 않았다), 가져갔거나 포기하지 않은 행의 주문은 partition_pending(곧 취소될 수 있다)이다.
 *
 * HTTP 로 소유 노드에 다시 보내지 않는 이유는 관리 평면에 회원 키가 없고 사용자 요청을 다시 보내면 nonce 와 허용창을 노드
 * 사이에서 다시 따져야 하기 때문이다. 두 노드가 모두 닿는 권위는 DB 다.
 */
public final class OrderCancelHandoff {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelHandoff.class);

    public static final String ORIGIN_CANCEL_ALL = "cancel_all";
    public static final String ORIGIN_MEMBER_CLOSE = "member_close";

    /** 요청자가 행을 읽는 간격(DB 설계 §5.4-d 2). */
    static final Duration READ_INTERVAL = Duration.ofMillis(100);

    /**
     * 인계 설정(application.yml exchange.order.cancel-handoff.*). waitFor(설정 키 wait)는 요청자가 기다리는 상한, poll 은
     * 작업자가 빈 표를 다시 보는 간격, chunk 는 행 하나의 주문 수(1~500), workers 는 파티션당 작업자 스레드 수,
     * expireInterval 은 작업자가 만료 문장을 도는 간격이다.
     */
    public record Settings(Duration waitFor, Duration poll, int chunk, int workers, Duration expireInterval) {
        public Settings {
            if (waitFor == null || waitFor.isNegative()) {
                throw new IllegalArgumentException("cancel-handoff.wait 는 0 이상이어야 한다: " + waitFor);
            }
            if (poll == null || poll.isZero() || poll.isNegative()) {
                throw new IllegalArgumentException("cancel-handoff.poll 은 0 보다 커야 한다: " + poll);
            }
            if (chunk < 1 || chunk > 500) {
                // V54 chk_ocr_order_ids 가 행 하나의 주문 수를 1~500 으로 묶는다.
                throw new IllegalArgumentException("cancel-handoff.chunk 는 1~500 이어야 한다: " + chunk);
            }
            if (workers < 0) {
                throw new IllegalArgumentException("cancel-handoff.workers 는 0 이상이어야 한다: " + workers);
            }
            if (expireInterval == null || expireInterval.isZero() || expireInterval.isNegative()) {
                throw new IllegalArgumentException("cancel-handoff.expire-interval 은 0 보다 커야 한다: " + expireInterval);
            }
        }

        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(5), Duration.ofMillis(200), 100, 1, Duration.ofSeconds(60));
        }
    }

    /** 넣은 요청. insertFailure 가 있으면 넣기가 DB 오류로 끝났다. 연결이 끊겨 끝났으면 커밋됐을 수 있다. */
    record Submitted(UUID requestId, List<OrderCancelHandoffRepository.NewRow> rows, long insertedAtNanos,
                     RuntimeException insertFailure) {
        int orders() {
            return rows.stream().mapToInt(r -> r.orderIds().size()).sum();
        }
    }

    private final OrderCancelHandoffRepository repo;
    private final PartitionPlan plan;
    private final PartitionLeases leases;
    private final Settings settings;
    private final CancelHandoffCounters counters;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrderCancelHandoff(OrderCancelHandoffRepository repo, PartitionPlan plan, PartitionLeases leases,
                              Settings settings, CancelHandoffCounters counters) {
        this.repo = repo;
        this.plan = plan;
        this.leases = leases;
        this.settings = settings;
        this.counters = counters;
    }

    public Settings settings() {
        return settings;
    }

    public CancelHandoffCounters counters() {
        return counters;
    }

    /** 마켓의 파티션. P3 에서 대응을 모르면 다시 읽고, 그래도 없으면 null 이다. */
    String partitionOf(int marketId) {
        return plan.partitionOf(marketId);
    }

    /** 이 노드가 그 파티션의 접수 게이트를 열었는가. 엔진을 끈 노드는 늘 거짓이다. */
    boolean opened(String resource) {
        return leases.openTermOf(resource) != null;
    }

    /**
     * 파티션별 주문을 chunk 개씩 행으로 만들어 한 트랜잭션으로 넣는다. 넣기가 DB 연결 장애나 일시 오류로 끝나면 실패를 담아
     * 돌려주고 그 밖의 예외는 올린다.
     */
    Submitted submit(long userId, Map<String, List<UUID>> byResource, String origin) {
        UUID requestId = UUID.randomUUID();
        List<OrderCancelHandoffRepository.NewRow> rows = new ArrayList<>();
        byResource.forEach((resource, ids) -> {
            for (int from = 0; from < ids.size(); from += settings.chunk()) {
                rows.add(new OrderCancelHandoffRepository.NewRow(resource,
                        List.copyOf(ids.subList(from, Math.min(ids.size(), from + settings.chunk())))));
            }
        });
        try {
            repo.insert(requestId, userId, origin, rows);
            return new Submitted(requestId, rows, System.nanoTime(), null);
        } catch (RuntimeException e) {
            if (!DbOutageErrors.isOutage(e) && !TransientDbErrors.isRetryable(e)) {
                throw e;
            }
            log.warn("인계 행 넣기가 DB 오류로 끝났다. 넘길 주문을 service_unavailable 로 적는다: request_id={}, 행={}, "
                    + "주문={}, 원인={}", requestId, rows.size(), rows.stream().mapToInt(r -> r.orderIds().size()).sum(),
                    e.toString());
            return new Submitted(requestId, rows, System.nanoTime(), e);
        }
    }

    /**
     * 넣은 행을 기다리고 포기하고 합친다(DB 설계 §5.4-d 2). 직접 취소가 wait 보다 오래 걸렸으면 한 번만 읽는다. 기다림이나 포기가
     * DB 오류로 끝나면 DONE 이 아닌 행의 주문은 모두 partition_pending 이다. 포기하지 못한 행은 유효 시간 안에 처리될 수 있다.
     */
    void settle(Submitted submitted, BulkCancelTally tally) {
        if (submitted.insertFailure() != null) {
            // 커밋됐을 수 있으므로 행이 남아 처리될 수 있다. 확인하지 못한 취소라 미완료로 센다.
            for (OrderCancelHandoffRepository.NewRow row : submitted.rows()) {
                row.orderIds().forEach(id -> tally.failed(id.toString(), BulkCancelTally.SERVICE_UNAVAILABLE));
            }
            tally.handoffInsertFailed();
            counters.request("insert_failed", submitted.rows().size());
            return;
        }
        long deadline = submitted.insertedAtNanos() + settings.waitFor().toNanos();
        List<OrderCancelHandoffRepository.Row> rows = null;
        boolean unknown = false;
        try {
            while (true) {
                rows = repo.byRequest(submitted.requestId());
                long left = deadline - System.nanoTime();
                if (allDone(rows) || left <= 0) {
                    break;
                }
                try {
                    Thread.sleep(Math.max(1, Math.min(READ_INTERVAL.toMillis(), left / 1_000_000)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!allDone(rows)) {
                repo.abandon(submitted.requestId());
                rows = repo.byRequest(submitted.requestId());
            }
        } catch (DataAccessException | TransactionException e) {
            unknown = true;
            log.warn("인계 행의 기다림이나 포기가 DB 오류로 끝났다. 끝나지 않은 행의 주문을 partition_pending 으로 적는다: "
                    + "request_id={}, 원인={}", submitted.requestId(), e.toString());
        }
        merge(submitted, rows, unknown, tally);
    }

    private void merge(Submitted submitted, List<OrderCancelHandoffRepository.Row> rows, boolean unknown,
                       BulkCancelTally tally) {
        if (rows == null) {
            for (OrderCancelHandoffRepository.NewRow row : submitted.rows()) {
                row.orderIds().forEach(id -> tally.failed(id.toString(), BulkCancelTally.PARTITION_PENDING));
            }
            counters.request("pending", submitted.rows().size());
            return;
        }
        for (OrderCancelHandoffRepository.Row row : rows) {
            switch (row.state()) {
                case DONE -> {
                    if (mergeDone(row, tally)) {
                        counters.request("done", 1);
                    } else {
                        pending(row, tally);
                    }
                }
                case ABANDONED -> {
                    if (unknown) {
                        pending(row, tally);
                    } else {
                        // 포기한 행은 뒤에 점유되지 않는다. 이 요청은 그 주문에 아무 명령도 넣지 않았다.
                        row.orderIds().forEach(id -> tally.failed(id.toString(), BulkCancelTally.PARTITION_NOT_STARTED));
                        counters.request("not_started", 1);
                    }
                }
                // 시작했으나 끝내지 않은 몫, 포기하지 않고 남긴 뒷부분, 만료로 닫힌 점유. 그 주문은 아직 열려 있을 수 있다.
                case PENDING, CLAIMED, EXPIRED -> pending(row, tally);
            }
        }
    }

    private void pending(OrderCancelHandoffRepository.Row row, BulkCancelTally tally) {
        row.orderIds().forEach(id -> tally.failed(id.toString(), BulkCancelTally.PARTITION_PENDING));
        counters.request("pending", 1);
    }

    /** DONE 행의 결과를 그대로 옮긴다. 결과를 읽지 못하면(결함) 거짓이고 그 행은 끝나지 않은 것으로 센다. */
    private boolean mergeDone(OrderCancelHandoffRepository.Row row, BulkCancelTally tally) {
        JsonNode result;
        try {
            result = mapper.readTree(row.result());
        } catch (Exception e) {
            log.error("인계 행의 결과를 읽지 못했다: id={}, 원인={}", row.id(), e.toString());
            return false;
        }
        if (result == null || !result.isObject()) {
            log.error("인계 행의 결과가 객체가 아니다: id={}", row.id());
            return false;
        }
        for (JsonNode id : result.path("canceled")) {
            tally.canceled(id.asText());
        }
        for (JsonNode failure : result.path("failed")) {
            tally.failedAsReported(failure.path("id").asText(), failure.path("reason").asText());
        }
        tally.addReportedCounts(result.get("counts"));
        return true;
    }

    private static boolean allDone(List<OrderCancelHandoffRepository.Row> rows) {
        return rows != null && rows.stream().allMatch(r -> r.state() == OrderCancelHandoffRepository.State.DONE);
    }
}
