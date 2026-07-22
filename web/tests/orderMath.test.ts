/**
 * tests/orderMath.test.ts. 주문 패널 수치 로직 회귀 고정.
 * 대상: 총액→수량 역산, 비지수 문자열화, 가용 대비 비중, 대형 주문 임계 판정.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  LARGE_ORDER_RATIO,
  floorTo,
  formatPriceField,
  roundToTick,
  stepToTick,
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

/* ===== 격자(틱) 반올림: 저가·고가 마켓 양단 회귀 ===== */

test("틱 반올림: sub-1 KRW 마켓이 0으로 붕괴하지 않는다", () => {
  // 정수 강제(Math.round)면 0.404 → 0. 격자 기준이면 값 보존
  assert.equal(roundToTick("0.404", "0.001"), "0.404");
  assert.equal(roundToTick("0.000398", "0.000001"), "0.000398");
});

test("틱 반올림: 최저가 마켓도 최소 한 틱을 유지", () => {
  // 0.0003 는 0.0001 격자에서 0이 아니라 0.0003
  assert.equal(roundToTick("0.0003", "0.0001"), "0.0003");
});

test("틱 반올림: 소수틱 half-up", () => {
  assert.equal(roundToTick("0.4044", "0.001"), "0.404");
  assert.equal(roundToTick("0.4046", "0.001"), "0.405");
  assert.equal(roundToTick("0.4045", "0.001"), "0.405");
});

test("틱 반올림: 고가 마켓 정수 격자", () => {
  assert.equal(roundToTick("84320123", "1000"), "84320000");
  assert.equal(roundToTick("84320678", "1000"), "84321000");
});

test("틱 반올림: 0.1 격자에서 정수 강제되지 않는다", () => {
  assert.equal(roundToTick("182.34", "0.1"), "182.3");
  assert.equal(roundToTick("182.36", "0.1"), "182.4");
});

test("틱 반올림: 틱 0 이하는 격자 없이 스케일 문자열화", () => {
  assert.equal(roundToTick("1.23", "0"), "1");
});

test("틱 스텝: 한 틱 이동", () => {
  assert.equal(stepToTick("182.3", "0.1", 1), "182.4");
  assert.equal(stepToTick("182.3", "0.1", -1), "182.2");
  assert.equal(stepToTick("84320000", "1000", 1), "84321000");
});

test("틱 스텝: 최소 한 틱으로 클램프(음수 방지)", () => {
  assert.equal(stepToTick("0.001", "0.001", -1), "0.001");
  assert.equal(stepToTick("0.0005", "0.001", -1), "0.001");
});

test("총액 역산: 저가 마켓(0.404)도 정밀 역산", () => {
  // 5000 / 0.404 = 12376.2376..., scale 8 내림
  assert.equal(qtyFromTotal(5000, "0.404", 8), "12376.23762376");
});

test("총액 역산: 고가 마켓(BTC) 정밀 역산", () => {
  // 100000 / 84320000 = 0.0011859582..., scale 8 내림
  assert.equal(qtyFromTotal(100000, "84320000", 8), "0.00118595");
});

test("총액 역산: 문자열 총액도 받는다", () => {
  assert.equal(qtyFromTotal("1000000", "50000000", 8), "0.02");
});

test("가격 필드 표기: 정수부 자리구분 + 소수부 보존", () => {
  assert.equal(formatPriceField("84320000"), "84,320,000");
  assert.equal(formatPriceField("0.404"), "0.404");
  assert.equal(formatPriceField("1000.5"), "1,000.5");
});

test("가격 필드 표기: 입력 중 후행 소수점·0 유지", () => {
  assert.equal(formatPriceField("0."), "0.");
  assert.equal(formatPriceField("0.40"), "0.40");
  assert.equal(formatPriceField(""), "");
});
