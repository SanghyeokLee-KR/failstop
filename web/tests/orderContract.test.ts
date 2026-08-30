/**
 * 락·수수료·원가 계약 산식 고정. 기대값은 API 설계 §4-B-5·§4-C-1과 §5-5 예시 수치를 그대로 쓴다.
 * 락 모델·수수료 수취 자산·평단가 "0" 해석이 계약에서 벗어나는 것을 막는다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  applyCostDelta,
  avgBuyPriceOf,
  bidLockAmount,
  buyFeeBase,
  isCostKnown,
  marketBidLockAmount,
  sellFeeQuote,
  unknownCostQty,
  EMPTY_COST_BASIS,
} from "../lib/orderContract";
import { ceilToScale, divDecimal, roundToScale, truncToScale } from "../lib/decimal";

/* ===== 십진 헬퍼 ===== */

test("UP 양자화는 잔여 소수가 있으면 무조건 한 칸 올린다", () => {
  assert.equal(ceilToScale("100.0000001", 0), "101");
  assert.equal(ceilToScale("100", 0), "100");
  assert.equal(ceilToScale("0.00000001", 0), "1");
});

test("HALF_UP·절사는 경계에서 갈린다", () => {
  assert.equal(roundToScale("0.5", 0), "1");
  assert.equal(truncToScale("0.9999", 0), "0");
});

test("십진 나눗셈: HALF_UP과 절사, 0 제수 가드", () => {
  assert.equal(divDecimal("200000", "0.003998", 0), "50025013"); // §5-5 예시
  assert.equal(divDecimal("1", "3", 4, "trunc"), "0.3333");
  assert.equal(divDecimal("1", "0", 4), null);
});

/* ===== 락 (§4-B-5 v2.7) ===== */

test("지정가 매수 락은 대금의 UP 양자화다(수수료 미포함)", () => {
  // §5-5: bid 0.01 @ 50,000,000 -> 500,000. 수수료를 더하면 500,250이 되어 계약과 어긋난다
  assert.equal(bidLockAmount("50000000", "0.01", 0), "500000");
});

test("지정가 매수 락은 소수 대금을 올림해 잡는다(모자란 락 금지)", () => {
  assert.equal(bidLockAmount("1234.5", "0.001", 0), "2"); // 1.2345 -> 2
});

test("시장가 매수 락은 주문 대금 그대로다", () => {
  assert.equal(marketBidLockAmount("1000000", 0), "1000000");
});

/* ===== 수수료 (수취 자산 기준) ===== */

test("매수 수수료는 base로 나가고 qty_scale로 선양자화된다", () => {
  // §5-5: 수취 0.004 × taker 0.0005 = 0.000002 BTC
  assert.equal(buyFeeBase("0.004", "0.0005", 8), "0.000002");
});

test("매도 수수료는 quote로 나가고 price_scale로 선양자화된다", () => {
  assert.equal(sellFeeQuote("200000", "0.0005", 0), "100");
  assert.equal(sellFeeQuote("1000.4", "0.0005", 0), "1"); // 0.5002 -> HALF_UP 1
});

/* ===== 원가 산출 (§4-C-1 v2.21) ===== */

test("평단가는 누산기 쌍에서 산출하고 quote scale로 HALF_UP 반올림한다", () => {
  assert.equal(avgBuyPriceOf("200000", "0.003998", 0), "50025013"); // §5-5 예시
  assert.equal(avgBuyPriceOf("19800000", "0.4", 0), "49500000"); // §4-C-1 예시
});

test('원가 확보분이 없으면 평단가는 "0"이고 그것은 산출 불가다', () => {
  assert.equal(avgBuyPriceOf("0", "0", 0), "0");
  assert.equal(isCostKnown("0"), false);
  assert.equal(isCostKnown("0.003998"), true);
});

test("원가 미상 수량은 balance + locked - cost_basis_qty로 유도한다", () => {
  // §5-5: 0.493998 + 0.01 - 0.003998 = 0.5
  assert.equal(unknownCostQty("0.493998", "0.01", "0.003998"), "0.5");
});

test("유도식이 음수면 0으로 클램프한다(C24 위반 응답 방어)", () => {
  assert.equal(unknownCostQty("0.1", "0", "0.5"), "0");
});

/* ===== 원가 프로젝션 전이 (DB 설계 §6.4 ⑱⑲ 미러) ===== */

test("대가를 지불한 취득만 원가로 잡는다", () => {
  const c = applyCostDelta(EMPTY_COST_BASIS, "0.003998", "-200000");
  assert.equal(c.costQty, "0.003998");
  assert.equal(c.costAmount, "200000");
  assert.equal(c.unknownQty, "0");
});

test("시드·입금 취득은 원가 0이 아니라 미상이다", () => {
  const c = applyCostDelta(EMPTY_COST_BASIS, "0.49", "0");
  assert.equal(c.costQty, "0");
  assert.equal(c.costAmount, "0");
  assert.equal(c.unknownQty, "0.49");
  assert.equal(avgBuyPriceOf(c.costAmount, c.costQty, 0), "0");
});

test("처분은 원가 확보분을 먼저 소진한다(known-first)", () => {
  const acquired = applyCostDelta({ costQty: "0", costAmount: "0", unknownQty: "1" }, "1", "-100000");
  // 보유 2(원가 1 + 미상 1)에서 1.5 처분 -> 원가분 1 전량 + 미상 0.5
  const sold = applyCostDelta(acquired, "-1.5", "160000");
  assert.equal(sold.costQty, "0");
  assert.equal(sold.costAmount, "0", "전량 스윕은 잔여 원가를 남기지 않는다");
  assert.equal(sold.unknownQty, "0.5");
});

test("부분 처분은 원가를 안분 절사해 덜어낸다", () => {
  const acquired = applyCostDelta(EMPTY_COST_BASIS, "2", "-100000");
  const sold = applyCostDelta(acquired, "-0.5", "30000");
  assert.equal(sold.costQty, "1.5");
  assert.equal(sold.costAmount, "75000");
  assert.equal(avgBuyPriceOf(sold.costAmount, sold.costQty, 0), "50000");
});

test("RESERVE·RELEASE 같은 순델타 0은 무동작이다", () => {
  const base = applyCostDelta(EMPTY_COST_BASIS, "1", "-50000");
  assert.deepEqual(applyCostDelta(base, "0", "0"), base);
});
