/**
 * tests/decimal.test.ts. 문자열 십진 합·차 계약 고정.
 * 회귀 방지 대상: 화면마다 지역 구현을 두거나 float로 합산해 NUMERIC(40,18) 정밀을 잃던 결함.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { addDecimal, subDecimal } from "../lib/decimal";

test("합·차는 자릿수가 다른 입력을 큰 스케일로 맞춘다", () => {
  assert.equal(addDecimal("1.5", "0.25"), "1.75");
  assert.equal(addDecimal("1000000", "0.000000000000000001"), "1000000.000000000000000001");
  assert.equal(subDecimal("1.75", "0.25"), "1.5");
  assert.equal(subDecimal("0.1", "0.3"), "-0.2");
});

test("2^53을 넘는 금액도 정밀을 잃지 않는다", () => {
  // float 합산은 이 구간에서 1원 단위가 뭉개진다
  assert.equal(addDecimal("9007199254740993", "1"), "9007199254740994");
  assert.equal(subDecimal("9007199254740994", "1"), "9007199254740993");
  assert.notEqual(String(9007199254740993 + 1), "9007199254740994");
});

test("잔고 합산은 문자열을 유지한다(가용 + 주문중)", () => {
  assert.equal(addDecimal("1234567.89", "10.11"), "1234578");
  assert.equal(typeof addDecimal("0", "0"), "string");
});
