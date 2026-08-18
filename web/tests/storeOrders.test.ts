/**
 * tests/storeOrders.test.ts. REST 주문 스냅샷 병합 계약 고정.
 * 회귀 방지 대상: 지연 REST 응답의 전량 교체가 WS로 확정된 종결 전이를 미체결로 되돌리던 결함,
 * 왕복 중 접수된 주문이 응답 부재로 사라지던 결함, 취소 응답이 목록에 반영되지 않던 결함.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { mergeOrderSnapshot, useExchangeStore } from "../lib/store";
import type { MyOrderMessage, Order, OrderStatus } from "../lib/types";

function order(id: string, status: OrderStatus, over: Partial<Order> = {}): Order {
  return {
    schema_version: "1.0",
    id,
    client_order_id: `c-${id}`,
    market: "KRW-BTC",
    side: "bid",
    ord_type: "limit",
    status,
    price: "50000000",
    volume: "0.1",
    remaining_volume: status === "OPEN" ? "0.1" : "0",
    executed_volume: status === "FILLED" ? "0.1" : "0",
    executed_funds: "0",
    remaining_notional: null,
    paid_fee: "0",
    trades_count: 0,
    created_at: "2026-07-21T09:00:00.000Z",
    epoch: 1,
    seq: 1,
    ...over,
  };
}

function myOrderMsg(id: string, status: OrderStatus): MyOrderMessage {
  return {
    schema_version: "1.0",
    type: "myOrder",
    epoch: 1,
    seq: 5,
    timestamp: "2026-07-21T09:00:01.000Z",
    id,
    client_order_id: `c-${id}`,
    market: "KRW-BTC",
    side: "bid",
    status,
    price: "50000000",
    executed_volume: "0.1",
    remaining_volume: "0",
    reject_reason: null,
    trade: null,
  };
}

const EMPTY = { openOrders: [] as Order[], closedOrders: [] as Order[] };

test("지연 REST OPEN은 로컬 종결 주문을 미체결로 되돌리지 않는다", () => {
  // WS로 FILLED가 확정된 뒤, 체결 전에 발사된 REST 조회가 OPEN을 싣고 늦게 도착한다
  const local = { openOrders: [], closedOrders: [order("o1", "FILLED")] };
  const next = mergeOrderSnapshot(local, [order("o1", "OPEN")], { known: new Set(["o1"]) });
  assert.equal(next.openOrders.length, 0, "종결 주문이 미체결 탭에 부활하면 안 된다");
  assert.equal(next.closedOrders.length, 1);
  assert.equal(next.closedOrders[0].status, "FILLED");
});

test("지연 REST OPEN은 로컬 PARTIAL의 체결 수량을 되돌리지 않는다", () => {
  const local = { openOrders: [order("o1", "PARTIAL", { executed_volume: "0.05" })], closedOrders: [] };
  const next = mergeOrderSnapshot(local, [order("o1", "OPEN", { executed_volume: "0" })], {
    known: new Set(["o1"]),
  });
  assert.equal(next.openOrders[0].status, "PARTIAL");
  assert.equal(next.openOrders[0].executed_volume, "0.05");
});

test("동급 스냅샷은 채택해 합성 주문의 권위 필드를 채운다", () => {
  // 합성 주문은 ord_type·수수료·체결금액을 안전 기본값으로 둔다. REST가 확정한다
  const local = { openOrders: [order("o1", "OPEN", { ord_type: "limit", paid_fee: "0" })], closedOrders: [] };
  const authoritative = order("o1", "OPEN", { ord_type: "market", paid_fee: "250" });
  const next = mergeOrderSnapshot(local, [authoritative], { known: new Set(["o1"]) });
  assert.equal(next.openOrders[0].ord_type, "market");
  assert.equal(next.openOrders[0].paid_fee, "250");
});

test("스냅샷의 종결 전이는 미체결에서 체결로 이동시킨다", () => {
  const local = { openOrders: [order("o1", "OPEN")], closedOrders: [] };
  const next = mergeOrderSnapshot(local, [order("o1", "CANCELED")], { known: new Set(["o1"]) });
  assert.equal(next.openOrders.length, 0);
  assert.equal(next.closedOrders[0].status, "CANCELED");
});

test("조회 왕복 중 접수된 주문은 응답 부재로 제거되지 않는다", () => {
  // known은 발사 시점 집합이다. o2는 그 이후 201 응답으로 들어와 스냅샷에 있을 수 없다
  const local = { openOrders: [order("o1", "OPEN"), order("o2", "OPEN")], closedOrders: [] };
  const next = mergeOrderSnapshot(local, [order("o1", "OPEN")], { known: new Set(["o1"]) });
  assert.deepEqual(
    next.openOrders.map((o) => o.id),
    ["o1", "o2"],
  );
});

test("발사 시점에 알던 주문이 응답에 없으면 제거한다(유령 행 방지)", () => {
  const local = { openOrders: [order("o1", "OPEN")], closedOrders: [] };
  const next = mergeOrderSnapshot(local, [], { known: new Set(["o1"]) });
  assert.equal(next.openOrders.length, 0);
});

test("마켓 한정 조회는 타 마켓 주문을 제거하지 않는다", () => {
  const local = {
    openOrders: [order("o1", "OPEN"), order("x1", "OPEN", { market: "KRW-XRP" })],
    closedOrders: [],
  };
  const next = mergeOrderSnapshot(local, [order("o1", "OPEN")], {
    known: new Set(["o1", "x1"]),
    market: "KRW-BTC",
  });
  assert.deepEqual(
    next.openOrders.map((o) => o.id),
    ["o1", "x1"],
  );
});

test("known 없는 병합(단건 upsert)은 아무 주문도 제거하지 않는다", () => {
  const local = { openOrders: [order("o1", "OPEN")], closedOrders: [] };
  const next = mergeOrderSnapshot(local, [order("o2", "OPEN")]);
  assert.deepEqual(
    next.openOrders.map((o) => o.id),
    ["o1", "o2"],
  );
});

test("빈 스토어에 스냅샷을 병합하면 상태로 두 목록에 분류된다", () => {
  const next = mergeOrderSnapshot(EMPTY, [order("o1", "OPEN"), order("o2", "FILLED"), order("o3", "REJECTED")]);
  assert.deepEqual(
    next.openOrders.map((o) => o.id),
    ["o1"],
  );
  assert.deepEqual(
    next.closedOrders.map((o) => o.id),
    ["o2", "o3"],
  );
});

test("upsertOrder: 취소 200 응답이 즉시 체결·취소 목록으로 이동시킨다", () => {
  useExchangeStore.setState({ openOrders: [order("o1", "OPEN")], closedOrders: [] });
  useExchangeStore.getState().upsertOrder(order("o1", "CANCELED", { canceled_at: "2026-07-21T09:00:02.000Z" }));
  const s = useExchangeStore.getState();
  assert.equal(s.openOrders.length, 0);
  assert.equal(s.closedOrders[0].status, "CANCELED");
});

test("upsertOrder: 역행 응답은 드롭하고 배열 참조를 유지한다", () => {
  const closed = [order("o1", "FILLED")];
  useExchangeStore.setState({ openOrders: [], closedOrders: closed });
  useExchangeStore.getState().upsertOrder(order("o1", "OPEN"));
  const s = useExchangeStore.getState();
  assert.equal(s.openOrders.length, 0);
  assert.equal(s.closedOrders, closed, "변화 없는 병합은 리렌더를 만들지 않는다");
});

test("apply(myOrder): 재전달된 OPEN은 종결 주문을 미체결로 되살리지 않는다", () => {
  useExchangeStore.setState({ openOrders: [], closedOrders: [order("o1", "FILLED")] });
  // 재연결 재동기화가 REST open 목록을 myOrder로 재전달하는 경로
  useExchangeStore.getState().apply(myOrderMsg("o1", "OPEN"));
  const s = useExchangeStore.getState();
  assert.equal(s.openOrders.length, 0);
  assert.equal(s.closedOrders[0].status, "FILLED");
});
