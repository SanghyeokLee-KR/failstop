package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.order.dto.BatchResponse;
import kr.exchange.api.order.dto.LedgerEntryResponse;
import kr.exchange.api.order.dto.OrderResponse;
import kr.exchange.api.order.dto.PageResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 직렬화가 §4-B·§4-C의 키 이름·순서·null 표기 계약을 지키는지 회귀 검증.
 * 키 순서 계약은 record 컴포넌트 선언 순서가 진다.
 */
class OrderDtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void orderResponseKeepsContractKeyOrder() throws Exception {
        OrderResponse view = new OrderResponse("1.0", "e1", null, "KRW-BTC", "bid", "limit",
                "OPEN", "100", "1", "1", "0", "0", null, "0", 0L, null,
                "2026-01-01T00:00:00.000Z", null, 3L, 7L);
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(view));
        assertEquals(List.of("schema_version", "id", "client_order_id", "market", "side",
                "ord_type", "status", "price", "volume", "remaining_volume", "executed_volume",
                "executed_funds", "remaining_notional", "paid_fee", "trades_count",
                "reject_reason", "created_at", "epoch", "seq"), keys(json));
        // 부재 키가 아니라 명시적 null이어야 한다(§2-1).
        assertTrue(json.get("client_order_id").isNull());
        assertTrue(json.get("remaining_notional").isNull());
    }

    @Test
    void canceledAtAppearsOnlyForCanceledOrders() throws Exception {
        OrderResponse view = new OrderResponse("1.0", "e1", "c1", "KRW-BTC", "ask", "limit",
                "CANCELED", "100", "1", "1", "0", "0", null, "0", 0L, null,
                "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:01.000Z", 3L, 7L);
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(view));
        assertEquals(List.of("schema_version", "id", "client_order_id", "market", "side",
                "ord_type", "status", "price", "volume", "remaining_volume", "executed_volume",
                "executed_funds", "remaining_notional", "paid_fee", "trades_count",
                "reject_reason", "created_at", "canceled_at", "epoch", "seq"), keys(json));
    }

    @Test
    void batchItemCarriesOnlyBranchKeys() throws Exception {
        BatchResponse.Item rejected = new BatchResponse.Item(0, null, "rejected", null, null,
                new BatchResponse.RejectedError("invalid_parameter", "잘못된 파라미터", null));
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(rejected));
        assertEquals(List.of("index", "client_order_id", "status", "error"), keys(json));
        assertEquals(List.of("name", "message"), keys(json.get("error")));

        BatchResponse.Item inProgress = new BatchResponse.Item(1, "c1", "in_progress", null, null,
                new BatchResponse.InProgressError("order_in_progress", 1));
        json = mapper.readTree(mapper.writeValueAsBytes(inProgress));
        assertEquals(List.of("index", "client_order_id", "status", "error"), keys(json));
        assertEquals(List.of("name", "retry_after"), keys(json.get("error")));
    }

    @Test
    void batchSummaryEmitsInProgressOnlyWhenPresent() throws Exception {
        JsonNode json = mapper.readTree(
                mapper.writeValueAsBytes(new BatchResponse.Summary(1, 2, 0, null, 3)));
        assertEquals(List.of("accepted", "rejected", "replayed", "total"), keys(json));
        json = mapper.readTree(
                mapper.writeValueAsBytes(new BatchResponse.Summary(1, 2, 0, 1, 4)));
        assertEquals(List.of("accepted", "rejected", "replayed", "in_progress", "total"),
                keys(json));
    }

    @Test
    void ledgerRefSecondKeyFollowsBusinessEventType() throws Exception {
        assertEquals(List.of("type", "trade_id"),
                keys(mapper.readTree(mapper.writeValueAsBytes(
                        LedgerEntryResponse.ref("TRADE", 5, null)))));
        // RESERVE는 주문 부재 시에도 order_id 키가 null로 존재해야 한다.
        JsonNode reserve = mapper.readTree(mapper.writeValueAsBytes(
                LedgerEntryResponse.ref("RESERVE", 5, null)));
        assertEquals(List.of("type", "order_id"), keys(reserve));
        assertTrue(reserve.get("order_id").isNull());
        assertEquals(List.of("type", "id"),
                keys(mapper.readTree(mapper.writeValueAsBytes(
                        LedgerEntryResponse.ref("GENESIS", 5, null)))));
    }

    @Test
    void pageResponseAlwaysCarriesPaginationLiterals() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(
                new PageResponse<>("1.0", List.of(), new PageResponse.Pagination(null, false))));
        assertEquals(List.of("schema_version", "data", "pagination"), keys(json));
        assertEquals(List.of("next_cursor", "has_more"), keys(json.get("pagination")));
        assertTrue(json.get("pagination").get("next_cursor").isNull());
    }
}
