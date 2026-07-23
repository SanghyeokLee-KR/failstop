/**
 * tests/derive.test.ts. 거래 화면 파생 계산 회귀 고정.
 * 대상: 전일종가 역산(RISE 차감·FALL 가산·EVEN), 첫 체결 전 마켓 null 필드 무크래시 강등,
 * 전일종가 대비 등락률(0 나눗셈 보호·방향), 호가 레벨 실재 판정.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import type { TickerMessage } from "../lib/types";
import { prevCloseOf, rateVsPrevClose, isPresentLevel } from "../components/trade/derive";

// 런타임 null은 타입(non-null string)을 위반하지만 실백엔드가 첫 체결 전 마켓에 보낸다. 캐스팅으로 재현한다.
function tk(p: Partial<Record<string, unknown>>): TickerMessage {
  return { type: "ticker", ...p } as unknown as TickerMessage;
}

test("prevCloseOf: ticker 부재는 0", () => {
  assert.equal(prevCloseOf(undefined), "0");
});

test("prevCloseOf: 첫 체결 전 null 필드는 크래시 없이 0", () => {
  assert.equal(prevCloseOf(tk({ trade_price: null, change_price: null, change: "EVEN" })), "0");
  // change가 RISE/FALL이어도 null 강등으로 0-0
  assert.equal(prevCloseOf(tk({ trade_price: null, change_price: null, change: "RISE" })), "0");
});

test("prevCloseOf: RISE는 현재가에서 등락액 차감, FALL은 가산", () => {
  assert.equal(prevCloseOf(tk({ trade_price: "84300000", change_price: "20000", change: "RISE" })), "84280000");
  assert.equal(prevCloseOf(tk({ trade_price: "84300000", change_price: "20000", change: "FALL" })), "84320000");
  assert.equal(prevCloseOf(tk({ trade_price: "84300000", change_price: "0", change: "EVEN" })), "84300000");
});

test("prevCloseOf: sub-1 KRW 소수도 문자열 십진으로 보존", () => {
  assert.equal(prevCloseOf(tk({ trade_price: "0.000398", change_price: "0.000002", change: "RISE" })), "0.000396");
});

test("rateVsPrevClose: 전일종가 0 이하는 EVEN·0 (0 나눗셈·-100% 오표시 방지)", () => {
  assert.deepEqual(rateVsPrevClose("84300000", "0"), { rate: "0", state: "EVEN" });
  assert.deepEqual(rateVsPrevClose("100", "-5"), { rate: "0", state: "EVEN" });
});

test("rateVsPrevClose: 동일가는 EVEN, 상승은 RISE, 하락은 FALL", () => {
  assert.equal(rateVsPrevClose("100", "100").state, "EVEN");
  // rate는 절대 비율 문자열(후행 0 절사). formatChangeRate가 100을 곱해 "2.00%"로 표시한다.
  const up = rateVsPrevClose("102", "100");
  assert.equal(up.state, "RISE");
  assert.equal(up.rate, "0.02");
  const down = rateVsPrevClose("99", "100");
  assert.equal(down.state, "FALL");
  assert.equal(down.rate, "0.01");
});

test("isPresentLevel: 가격·잔량 둘 다 양수여야 실재 레벨", () => {
  assert.equal(isPresentLevel("84300000", "0.5"), true);
  assert.equal(isPresentLevel("0", "0.5"), false);
  assert.equal(isPresentLevel("84300000", "0"), false);
});
