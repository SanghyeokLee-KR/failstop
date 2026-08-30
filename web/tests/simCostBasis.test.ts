/**
 * sim이 라이브 계약(락·원가)의 소비자인지 고정. sim이 자체 산식을 들면 라이브 결함이 은폐된다.
 * 시드 물량이 원가 미상으로 남는지, 체결이 원가를 계약대로 적재하는지 본다.
 * 체결은 교차 지정가 + 모의 타이머로 강제해 테스트용 API를 엔진에 만들지 않는다.
 */

import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { SimEngine } from "../lib/sim/engine";
import { unknownCostQty } from "../lib/orderContract";
import { compareDecimal } from "../lib/decimal";
import type { Asset } from "../lib/types";

/** 시드 BTC 현재가 84,320,000. 매수는 그 위, 매도는 그 아래 지정가라야 즉시 교차한다 */
const CROSS_BID_PRICE = "85000000";
const CROSS_ASK_PRICE = "80000000";
/** 체결 지연 상한 1,200ms를 넘기는 모의 시간 */
const FILL_WINDOW_MS = 2_000;

function accountOf(engine: SimEngine, currency: string): Asset {
  return engine.getAccounts().find((a) => a.currency === currency)!;
}

test("시드 물량은 원가 미상이다(가짜 평단가 금지)", () => {
  const engine = new SimEngine();
  const btc = accountOf(engine, "BTC");
  assert.equal(btc.cost_basis_qty, "0", "시드 취득은 원가 확보 수량이 아니다");
  assert.equal(btc.cost_basis_amount, "0");
  assert.equal(btc.avg_buy_price, "0", '"0"은 평단가 0원이 아니라 산출 불가다');
  assert.equal(unknownCostQty(btc.balance, btc.locked, btc.cost_basis_qty), "0.0918");
});

test("quote 자산(KRW)에는 원가 행이 생기지 않는다(C25)", () => {
  const engine = new SimEngine();
  const krw = accountOf(engine, "KRW");
  assert.equal(krw.cost_basis_qty, "0");
  assert.equal(krw.cost_basis_amount, "0");
  assert.equal(krw.avg_buy_price, "0");
});

test("지정가 매수 락은 대금만 잡는다(수수료 예약 없음, §4-B-5 v2.7)", () => {
  const engine = new SimEngine();
  // 현재가보다 낮은 지정가는 교차하지 않아 OPEN 유지. 락만 확인한다
  engine.placeOrder({
    market: "KRW-BTC",
    side: "bid",
    ord_type: "limit",
    price: CROSS_ASK_PRICE,
    volume: "0.01",
    client_order_id: "lock-1",
  });
  const krw = accountOf(engine, "KRW");
  assert.equal(krw.locked, "800000.00", "락은 대금 800,000뿐이다. 구 모델이면 800,400이다");
  assert.equal(krw.balance, "9200000.00", "가용에서 락만 빠진다");
});

test("매수 체결은 수취 base에서 수수료를 떼고 원가를 계약대로 적재한다", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const engine = new SimEngine();
    engine.placeOrder({
      market: "KRW-BTC",
      side: "bid",
      ord_type: "limit",
      price: CROSS_BID_PRICE,
      volume: "0.01",
      client_order_id: "buy-1",
    });
    mock.timers.tick(FILL_WINDOW_MS);

    const btc = accountOf(engine, "BTC");
    const krw = accountOf(engine, "KRW");
    // 수수료는 수취 자산 기준: 0.01 × 0.0005 = 0.000005 BTC. 순수취 0.009995
    assert.equal(btc.cost_basis_qty, "0.009995");
    assert.equal(btc.cost_basis_amount, "850000");
    assert.equal(btc.avg_buy_price, "85042521");
    assert.equal(btc.balance, "0.10179500", "시드 0.0918 + 순수취 0.009995");
    assert.equal(krw.locked, "0.00", "전량 체결은 잔여 락을 0으로 소비한다");
    assert.equal(krw.balance, "9150000.00", "락 850,000만 나가고 수수료 원화 차감은 없다");
    // 시드분은 원가 미상으로 남는다. 매수분만 원가가 잡힌다
    assert.equal(unknownCostQty(btc.balance, btc.locked, btc.cost_basis_qty), "0.0918");
  } finally {
    mock.timers.reset();
  }
});

test("매도 체결은 quote에서 수수료를 떼고 원가 확보분을 먼저 소진한다", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const engine = new SimEngine();
    engine.placeOrder({
      market: "KRW-BTC",
      side: "bid",
      ord_type: "limit",
      price: CROSS_BID_PRICE,
      volume: "0.01",
      client_order_id: "buy-2",
    });
    mock.timers.tick(FILL_WINDOW_MS);

    const krwBefore = accountOf(engine, "KRW").balance;
    engine.placeOrder({
      market: "KRW-BTC",
      side: "ask",
      ord_type: "limit",
      price: CROSS_ASK_PRICE,
      volume: "0.005",
      client_order_id: "sell-1",
    });
    mock.timers.tick(FILL_WINDOW_MS);

    const btc = accountOf(engine, "BTC");
    const krw = accountOf(engine, "KRW");
    // 체결대금 400,000 - 수수료 200 = 순수취 399,800
    assert.equal(krw.balance, "9549800.00");
    assert.ok(compareDecimal(krw.balance, krwBefore) > 0);
    assert.equal(btc.locked, "0.00000000", "매도 락은 전량 소비된다");
    // 원가 확보 0.009995에서 0.005 소진
    assert.equal(btc.cost_basis_qty, "0.004995");
    assert.ok(compareDecimal(btc.cost_basis_amount, "850000") < 0, "처분분만큼 원가가 덜어진다");
    assert.ok(compareDecimal(btc.cost_basis_amount, "0") > 0, "잔여 수량이 있으면 원가도 남는다");
  } finally {
    mock.timers.reset();
  }
});

test("myAsset 델타는 원가 필드를 싣는다(§5-5 K5)", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const engine = new SimEngine();
    let payload: Array<Record<string, string>> = [];
    const off = engine.subscribe("/user/queue/myAsset", (m) => {
      if (m.type === "myAsset") payload = m.assets as unknown as Array<Record<string, string>>;
    });
    engine.placeOrder({
      market: "KRW-BTC",
      side: "bid",
      ord_type: "limit",
      price: CROSS_BID_PRICE,
      volume: "0.01",
      client_order_id: "delta-1",
    });
    mock.timers.tick(FILL_WINDOW_MS);
    off();

    const btc = payload.find((a) => a.currency === "BTC")!;
    assert.ok(btc, "델타에 접촉 자산이 실려야 한다");
    assert.equal(btc.cost_basis_qty, "0.009995");
    assert.equal(btc.cost_basis_amount, "850000");
    assert.equal(btc.avg_buy_price, "85042521");
  } finally {
    mock.timers.reset();
  }
});
