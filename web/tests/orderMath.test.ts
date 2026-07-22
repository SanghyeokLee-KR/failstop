/**
 * tests/orderMath.test.ts. 주문 패널 수치 로직 회귀 고정.
 * 대상: 총액→수량 역산, 비지수 문자열화, 가용 대비 비중, 대형 주문 임계 판정.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  LARGE_ORDER_RATIO,
  floorTo,
  toPlainString,
  qtyFromTotal,
  committedRatioOf,
  isLargeOrder,
} from "../components/trade/orderMath";

test("총액 역산: 총액/가격을 scale 자리로 내림", () => {
  // 1,000,000 / 50,000,000 = 0.02
  assert.equal(qtyFromTotal(1_000_000, 50_000_000, 8), "0.02");
});

test("총액 역산: 내림으로 주문 가능 수량을 넘지 않는다", () => {
  // 100 / 3 = 33.333..., scale 4 내림 → 33.3333
  assert.equal(qtyFromTotal(100, 3, 4), "33.3333");
});

test("총액 역산: 가격 0이면 null(수량 미변경)", () => {
  assert.equal(qtyFromTotal(1_000_000, 0, 8), null);
});

test("총액 역산: 가격 음수도 null", () => {
  assert.equal(qtyFromTotal(1_000_000, -1, 8), null);
});

test("비지수 문자열: 1e-6 미만도 지수표기 없이 고정 소수", () => {
  // String(1e-8) === "1e-8" 회귀 방지
  assert.equal(toPlainString(1e-8, 8), "0.00000001");
});

test("비지수 문자열: 후행 0 제거", () => {
  assert.equal(toPlainString(0.00412, 8), "0.00412");
});

test("비지수 문자열: 정수는 소수점을 붙이지 않는다", () => {
  assert.equal(toPlainString(100, 8), "100");
});

test("비지수 문자열: 0은 0", () => {
  assert.equal(toPlainString(0, 8), "0");
});

test("비지수 문자열: 비유한 입력은 0으로 방어", () => {
  assert.equal(toPlainString(Number.NaN, 8), "0");
  assert.equal(toPlainString(Number.POSITIVE_INFINITY, 8), "0");
});

test("floorTo: 소수 자릿수 내림", () => {
  assert.equal(floorTo(0.123456789, 8), 0.12345678);
  assert.equal(floorTo(33.33339, 4), 33.3333);
});

test("bid 비중: (총액+수수료)/현금", () => {
  // (700,000 + 350) / 1,000,000
  assert.equal(committedRatioOf("bid", 700_000, 350, 1_000_000, 0, 0), 0.70035);
});

test("bid 비중: 현금 0이면 0(분모 가드)", () => {
  assert.equal(committedRatioOf("bid", 700_000, 350, 0, 0, 0), 0);
});

test("ask 비중: 수량/보유코인", () => {
  assert.equal(committedRatioOf("ask", 0, 0, 0, 2, 1), 0.5);
});

test("ask 비중: 보유코인 0이면 0(분모 가드)", () => {
  assert.equal(committedRatioOf("ask", 0, 0, 0, 0, 1), 0);
});

test("임계 판정: 0.7 직전은 대형 아님", () => {
  assert.equal(isLargeOrder(true, 0.6999, LARGE_ORDER_RATIO), false);
});

test("임계 판정: 정확히 0.7은 대형(경계 포함)", () => {
  assert.equal(isLargeOrder(true, 0.7, LARGE_ORDER_RATIO), true);
});

test("임계 판정: 0.7 직후는 대형", () => {
  assert.equal(isLargeOrder(true, 0.7001, LARGE_ORDER_RATIO), true);
});

test("임계 판정: ready 아니면 임계 넘어도 대형 아님", () => {
  assert.equal(isLargeOrder(false, 0.9, LARGE_ORDER_RATIO), false);
});
