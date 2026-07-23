/**
 * tests/storeDelta.test.ts. myAsset 델타 병합·myOrder 종결 upsert 검증.
 * 서버가 접촉 마켓만 보내는 델타를 전체 교체로 흡수해 미접촉 보유·avg_buy_price가 소실되던 회귀와,
 * 종결 주문이 openOrders에 없을 때 체결 탭에서 유실되던 회귀를 고정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { useExchangeStore } from "../lib/store";
import type { Asset, MyAssetMessage, MyOrderMessage, Order } from "../lib/types";

function asset(currency: string, balance: string, avg = "0"): Asset {
  return { currency, balance, locked: "0", avg_buy_price: avg, unit_currency: "KRW" };
}

function myAssetMsg(assets: Array<{ currency: string; balance: string; locked: string }>): MyAssetMessage {
  return {
    schema_version: "1.0",
    type: "myAsset",
    epoch: 1,
    seq: 1,
    timestamp: "2026-07-21T09:00:00.000Z",
    assets,
  };
}

function myOrderMsg(id: string, status: MyOrderMessage["status"]): MyOrderMessage {
  return {
    schema_version: "1.0",
    type: "myOrder",
    epoch: 1,
    seq: 1,
    timestamp: "2026-07-21T09:00:00.000Z",
    id,
    client_order_id: `c-${id}`,
    market: "KRW-BTC",
    side: "bid",
    status,
    price: "50000000",
    executed_volume: "0",
    remaining_volume: "0",
    reject_reason: null,
    trade: null,
  };
}

function openOrder(id: string): Order {
  return {
    schema_version: "1.0",
    id,
    client_order_id: `c-${id}`,
    market: "KRW-BTC",
    side: "bid",
    ord_type: "limit",
    status: "OPEN",
    price: "50000000",
    volume: "0.1",
    remaining_volume: "0.1",
    executed_volume: "0",
    executed_funds: "0",
    remaining_notional: null,
    paid_fee: "0",
    trades_count: 0,
    created_at: "2026-07-21T09:00:00.000Z",
    epoch: 1,
    seq: 1,
  };
}

test("myAsset: 접촉 마켓 델타가 미접촉 보유·avg_buy_price를 보존한다", () => {
  useExchangeStore.setState({
    assets: [asset("KRW", "1000000"), asset("BTC", "0.5", "48000000"), asset("XRP", "300", "800")],
  });
  // BTC만 접촉한 체결 델타. KRW·XRP는 메시지에 없다
  useExchangeStore.getState().apply(myAssetMsg([{ currency: "BTC", balance: "0.7", locked: "0" }]));
  const assets = useExchangeStore.getState().assets;
  const byCur = Object.fromEntries(assets.map((a) => [a.currency, a]));
  assert.equal(assets.length, 3); // 미접촉 KRW·XRP 유지
  assert.equal(byCur["BTC"].balance, "0.7"); // 접촉분 갱신
  assert.equal(byCur["BTC"].avg_buy_price, "48000000"); // 델타에 없는 평단 보존
  assert.equal(byCur["XRP"].balance, "300"); // 미접촉 보유 소실 방지
  assert.equal(byCur["XRP"].avg_buy_price, "800");
  assert.equal(byCur["KRW"].balance, "1000000");
});

test("myAsset: 신규 보유 통화는 append 된다", () => {
  useExchangeStore.setState({ assets: [asset("KRW", "1000000")] });
  useExchangeStore.getState().apply(myAssetMsg([{ currency: "ETH", balance: "2", locked: "0" }]));
  const assets = useExchangeStore.getState().assets;
  assert.equal(assets.length, 2);
  assert.equal(assets.find((a) => a.currency === "ETH")?.balance, "2");
  assert.equal(assets.find((a) => a.currency === "ETH")?.avg_buy_price, "0");
});

test("myOrder: openOrders에 없는 종결 주문도 closedOrders에 upsert 된다", () => {
  useExchangeStore.setState({ openOrders: [], closedOrders: [] });
  // 재동기화가 재전달한 종결 주문. openOrders에 없다
  useExchangeStore.getState().apply(myOrderMsg("o1", "FILLED"));
  const closed = useExchangeStore.getState().closedOrders;
  assert.equal(closed.length, 1);
  assert.equal(closed[0].id, "o1");
  assert.equal(closed[0].status, "FILLED");
});

test("myOrder: 종결 재전달은 중복 행을 만들지 않고 갱신한다", () => {
  useExchangeStore.setState({ openOrders: [], closedOrders: [] });
  const { apply } = useExchangeStore.getState();
  apply(myOrderMsg("o1", "CANCELED"));
  apply(myOrderMsg("o1", "CANCELED")); // 동일 종결 재전달
  const closed = useExchangeStore.getState().closedOrders;
  assert.equal(closed.length, 1);
  assert.equal(closed[0].id, "o1");
});

test("myOrder: openOrders 보유분 종결은 openOrders에서 빠지고 closedOrders로 이동한다", () => {
  useExchangeStore.setState({ openOrders: [openOrder("o1")], closedOrders: [] });
  useExchangeStore.getState().apply(myOrderMsg("o1", "FILLED"));
  const s = useExchangeStore.getState();
  assert.equal(s.openOrders.length, 0);
  assert.equal(s.closedOrders.length, 1);
  assert.equal(s.closedOrders[0].id, "o1");
  assert.equal(s.closedOrders[0].status, "FILLED");
});
