/**
 * tests/transferQuickAmount.test.ts. 입출금 빠른 금액 버튼의 누적 회귀 고정.
 * 합이 요청 금액이 되므로 number를 거친 합으로 되돌아가면 이 시험이 실패해야 한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { addQuickAmount } from "../app/transfers/quickAmount";

test("빠른 금액: 빈 입력칸은 0에서 더한다", () => {
  assert.equal(addQuickAmount("", "100000"), "100000");
  assert.equal(addQuickAmount("0", "10000000"), "10000000");
  assert.equal(addQuickAmount("250000", "1000000"), "1250000");
});

test("빠른 금액: 2^53을 넘는 금액도 끝자리를 바꾸지 않는다", () => {
  // number 합은 9007199254740993을 ...992로 읽어 9007199254840992를 만든다
  assert.equal(addQuickAmount("9007199254740993", "100000"), "9007199254840993");
});

test("빠른 금액: 큰 합도 숫자만 있는 문자열로 남아 금액 검증을 그대로 받는다", () => {
  // number 합은 이 구간에서 지수 표기로 문자열화되어 '숫자만' 검증에 먼저 걸린다
  const sum = addQuickAmount("999999999999999000000", "1000000");
  assert.equal(sum, "1000000000000000000000");
  assert.match(sum, /^\d+$/);
});
