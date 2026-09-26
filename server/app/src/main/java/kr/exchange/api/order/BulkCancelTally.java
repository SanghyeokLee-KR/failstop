package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.api.order.dto.CancelAllResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 일괄 취소 결과를 모으는 곳(API 설계 §4-B-3, DB 설계 §5.4-d 2·3). 이 노드가 직접 취소한 주문, 소유 노드가 인계받아 처리한 행의
 * 결과, 인계 작업자의 행 결과가 같은 사유 이름과 사유별 수를 쓴다.
 *
 * 사유별 수는 사유 이름으로 센다. service_unavailable 과 trading_suspended 는 unsubmitted, too_many_requests 는 contended, market_suspended 는
 * suspended, server_error 는 errored, partition_not_started 와 partition_pending 은 그 이름의 수다. 모두 '그 주문이 아직 열려
 * 있을 수 있다'는 뜻이고 회원 폐쇄가 미완료로 센다(§6-E). 먼저 종결된 주문의 상태, order_not_found, 취소 저널 뒤 반영 대기의
 * order_in_progress 는 세지 않는다.
 */
final class BulkCancelTally {

    static final String SERVICE_UNAVAILABLE = "service_unavailable";
    static final String TRADING_SUSPENDED = "trading_suspended";
    static final String TOO_MANY_REQUESTS = "too_many_requests";
    static final String MARKET_SUSPENDED = "market_suspended";
    static final String SERVER_ERROR = "server_error";
    static final String PARTITION_NOT_STARTED = "partition_not_started";
    static final String PARTITION_PENDING = "partition_pending";

    private final List<String> canceled = new ArrayList<>();
    private final List<CancelAllResponse.Failure> failed = new ArrayList<>();
    private int unsubmitted;
    private int suspended;
    private int contended;
    private int partitionNotStarted;
    private int partitionPending;
    private int errored;
    // 이 노드가 submit 까지 간 로컬 취소 수. 0 이면 이 노드의 쓰기 능력을 시험하지 않은 요청이다(DB 설계 §5.4-c 8 v2.57).
    private int localSubmits;
    private boolean handoffInsertFailed;

    void canceled(String id) {
        canceled.add(id);
    }

    /** 실패 한 건을 적고 사유 이름으로 센다. */
    void failed(String id, String reason) {
        failed.add(new CancelAllResponse.Failure(id, reason));
        count(reason, 1);
    }

    /** 소유 노드가 적은 실패를 그대로 옮긴다. 수는 그 행의 사유별 수로 따로 더한다(addReportedCounts). */
    void failedAsReported(String id, String reason) {
        failed.add(new CancelAllResponse.Failure(id, reason));
    }

    /** 소유 노드가 처리한 행 결과의 사유별 수를 그대로 더한다(DB 설계 §5.4-d 2). */
    void addReportedCounts(JsonNode counts) {
        if (counts == null) {
            return;
        }
        unsubmitted += counts.path("unsubmitted").asInt(0);
        contended += counts.path("contended").asInt(0);
        suspended += counts.path("suspended").asInt(0);
        errored += counts.path("errored").asInt(0);
    }

    void submitted() {
        localSubmits++;
    }

    void handoffInsertFailed() {
        handoffInsertFailed = true;
    }

    private void count(String reason, int n) {
        switch (reason) {
            // 423 은 거래 일시 중단으로 저널에 닿지 않은 취소다. 주문이 열려 있어 재개 뒤 다시 불러야 한다.
            case SERVICE_UNAVAILABLE, TRADING_SUSPENDED -> unsubmitted += n;
            case TOO_MANY_REQUESTS -> contended += n;
            case MARKET_SUSPENDED -> suspended += n;
            case SERVER_ERROR -> errored += n;
            case PARTITION_NOT_STARTED -> partitionNotStarted += n;
            case PARTITION_PENDING -> partitionPending += n;
            default -> {
                // 먼저 종결된 주문의 상태, order_not_found, order_in_progress 는 다시 부를 필요가 없어 세지 않는다.
            }
        }
    }

    OrderGateway.BulkCancelResult result() {
        return new OrderGateway.BulkCancelResult(List.copyOf(canceled), List.copyOf(failed), unsubmitted, suspended,
                contended, partitionNotStarted, partitionPending, errored, localSubmits, handoffInsertFailed);
    }

    /**
     * 인계 작업자가 끝내기 문장에 싣는 결과(DB 설계 §5.4-d 3). canceled, failed[{id, reason}], counts{unsubmitted, contended,
     * suspended, errored}다.
     */
    String toResultJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode ids = root.putArray("canceled");
        canceled.forEach(ids::add);
        ArrayNode failures = root.putArray("failed");
        for (CancelAllResponse.Failure f : failed) {
            failures.addObject().put("id", f.id()).put("reason", f.reason());
        }
        ObjectNode counts = root.putObject("counts");
        counts.put("unsubmitted", unsubmitted);
        counts.put("contended", contended);
        counts.put("suspended", suspended);
        counts.put("errored", errored);
        return root.toString();
    }
}
