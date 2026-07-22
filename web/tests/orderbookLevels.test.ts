/**
 * tests/orderbookLevels.test.ts. 호가 레벨 실재 판정(isPresentLevel) 검증.
 * 얇은 호가에서 서버가 0으로 채운 빈 슬롯을 걸러 -100% 오등락 표시를 막는지 확인한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { isPresentLevel } from "../components/trade/derive";

test("가격·잔량이 양수면 실재 레벨", () => {
  assert.equal(isPresentLevel("50000000", "0.5"), true);
});

test("가격 0(빈 슬롯)은 비실재 → -100% 역산 차단", () => {
  assert.equal(isPresentLevel("0", "0"), false);
});

test("가격만 있고 잔량 0이면 비실재", () => {
  assert.equal(isPresentLevel("50000000", "0"), false);
});

test("잔량만 있고 가격 0이면 비실재", () => {
  assert.equal(isPresentLevel("0", "1.2"), false);
});

test("빈 문자열은 비실재(방어)", () => {
  assert.equal(isPresentLevel("", ""), false);
});
