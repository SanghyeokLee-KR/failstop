/**
 * tests/simCancel.test.ts. 주문 취소 시 잔고 락 해제(자산 보존) 검증.
 * 회귀 방지 대상: 시장가 매수 취소가 remaining_volume=0 때문에 KRW 락을 영구 잔존시키던 결함.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimEngine } from "../lib/sim/engine";

function krwOf(engine: SimEngine) {
  const krw = engine.getAccounts().find((a) => a.currency === "KRW")!;
  return { balance: krw.balance, locked: krw.locked };
}

test("시장가 매수 취소는 잔여 대금 락을 전액 해제한다(락 0 수렴)", () => {
  const engine = new SimEngine();
  const before = krwOf(engine);

  engine.placeOrder({
    market: "KRW-BTC",
    side: "bid",
    ord_type: "market",
    price: "1000000", // 시장가 매수 금액(대금)
    client_order_id: "mkt-buy-1",
  });
  // 취소는 즉시 체결 타이머보다 먼저. 타이머가 정리되고 OPEN 상태에서 취소된다.
  engine.cancelOrder("mkt-buy-1");

  const after = krwOf(engine);
  assert.equal(after.locked, "0.00", "취소 후 KRW 락이 남으면 자산 보존 위반");
  assert.equal(after.balance, before.balance, "취소 후 KRW 잔고가 원복돼야 한다");
});

test("지정가 매수 취소는 기존 환급 경로를 유지한다", () => {
  const engine = new SimEngine();
  const before = krwOf(engine);

  // 현재가보다 낮은 지정가 매수는 교차하지 않아 OPEN 유지(타이머 없음)
  engine.placeOrder({
    market: "KRW-BTC",
    side: "bid",
    ord_type: "limit",
    price: "80000000",
    volume: "0.01",
    client_order_id: "lim-buy-1",
  });
  engine.cancelOrder("lim-buy-1");

  const after = krwOf(engine);
  assert.equal(after.locked, "0.00", "지정가 취소도 락이 0으로 수렴해야 한다");
  assert.equal(after.balance, before.balance, "지정가 취소 후 KRW 잔고 원복");
});
