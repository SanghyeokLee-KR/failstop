/**
 * 표시 포맷터 결측 방어·정상 포맷 회귀 고정.
 * displayValue의 null·undefined·"" 강등은 어댑터 이상이 렌더 크래시로 번지는 것을 막는 최후 방어다.
 * 유효 입력의 정상 포맷도 함께 고정해 전부 0으로 통과하는 토톨로지를 막는다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  formatKRW,
  formatCount,
  formatPrice,
  formatQty,
  formatChangeRate,
  formatDirectional,
  formatCompactKRW,
  formatCompactQty,
  formatDirectionalAmount,
} from "../lib/format";

// 음수·하락 표기는 U+2212 MINUS SIGN(하이픈 아님). 방향 화살표는 U+25B2/U+25BC. 코드포인트로 고정한다.
const MINUS = "−";
const UP = "▲";
const DOWN = "▼";

// displayValue(format.ts:44) 방어 대상은 string|number 타입 계약 밖 런타임 결측이다.
// 어댑터 이상으로 null·undefined가 들어오는 상황을 재현하려 호출부에서 타입을 지운다.
const nul = null as unknown as string;
const undef = undefined as unknown as string;

// formatFixed(d=0) 경로: KRW 정수·카운트
test("formatKRW: null·undefined·\"\"는 크래시 없이 \"0\"", () => {
  assert.equal(formatKRW(nul), "0");
  assert.equal(formatKRW(undef), "0");
  assert.equal(formatKRW(""), "0");
});

test("formatKRW: 유효 정수 문자열은 자리구분 포맷", () => {
  assert.equal(formatKRW("84320000"), "84,320,000");
});

test("formatCount: null·undefined·\"\"는 \"0\", 유효 숫자는 자리구분", () => {
  assert.equal(formatCount(nul), "0");
  assert.equal(formatCount(undef), "0");
  assert.equal(formatCount(""), "0");
  assert.equal(formatCount(1200), "1,200");
});

// formatFixed(스펙·스케일) 경로: 가격·수량
test("formatPrice: null·undefined·\"\"는 \"0\"", () => {
  assert.equal(formatPrice(nul), "0");
  assert.equal(formatPrice(undef), "0");
  assert.equal(formatPrice(""), "0");
});

test("formatPrice: 폴백 자릿수는 가격대별, sub-1 KRW는 붕괴하지 않는다", () => {
  assert.equal(formatPrice("84300000"), "84,300,000"); // >=1000 -> 정수
  assert.equal(formatPrice("0.000398"), "0.000398"); // sub-1 -> 6자리 폴백
});

test("formatPrice: tick_size 문자열 스펙에서 자릿수 유도", () => {
  assert.equal(formatPrice("64000000", "1000"), "64,000,000"); // tick 1000 -> 0자리
});

test("formatQty: null·undefined·\"\"는 하한 자릿수 0으로 강등", () => {
  assert.equal(formatQty(nul), "0.00");
  assert.equal(formatQty(undef), "0.00");
  assert.equal(formatQty(""), "0.00");
});

test("formatQty: 후행 0은 하한 2자리까지만 남는다", () => {
  assert.equal(formatQty("0.00412"), "0.00412");
  assert.equal(formatQty("1.5", 4), "1.50");
  assert.equal(formatQty("2", 8), "2.00");
});

/* 정산 scale 과 표시 scale 을 가른다. ETH 는 wei 단위라 정산 18 이 맞지만 그대로 그리면
   "13.750000000000000000" 이 되어 열 하나가 화면을 먹는다 */
test("formatQty: 정산 scale 18은 표시 상한 8로 자른다", () => {
  assert.equal(formatQty("13.750000000000000000", 18), "13.75");
  assert.equal(formatQty("0.010000000000000000", 18), "0.01");
});

/* 상한으로 자른 결과가 0이면 표시가 값을 지운 것이다. 잔량이 있는데 없다고 그리지 않는다 */
test("formatQty: 상한 아래 값은 첫 유효 숫자까지 자릿수를 되돌린다", () => {
  assert.equal(formatQty("0.000000000000000001", 18), "0.000000000000000001");
  // 되돌림은 첫 유효 자리에서 멈추므로 반올림 표기다. 0이 아니라는 사실이 남는 것이 목적이다
  assert.equal(formatQty("0.0000000005", 18), "0.000000001");
  // scale 이 허용하지 않으면 되돌리지 않는다. 없는 정밀도를 만들지 않는다
  assert.equal(formatQty("0.000000000000000001", 8), "0.00");
});

// formatPct 경로: 등락률
test("formatChangeRate: null·undefined·\"\"는 부호만 남고 0.00%로 강등", () => {
  assert.equal(formatChangeRate(nul, "RISE"), "+0.00%");
  assert.equal(formatChangeRate(undef, "FALL"), `${MINUS}0.00%`);
  assert.equal(formatChangeRate("", "EVEN"), "0.00%");
});

test("formatChangeRate: 유효 비율은 백분율 2자리", () => {
  assert.equal(formatChangeRate("0.0234", "RISE"), "+2.34%");
});

test("formatDirectional: null 강등도 화살표+부호 병기 유지", () => {
  assert.equal(formatDirectional(nul, "RISE"), `${UP} +0.00%`);
  assert.equal(formatDirectional(undef, "EVEN"), "0.00%");
  assert.equal(formatDirectional("0.0108", "FALL"), `${DOWN} ${MINUS}1.08%`);
});

// formatCompactKRW: 억·만 축약(displayValue 직접 소비)
test("formatCompactKRW: null·undefined·\"\"는 \"0\"", () => {
  assert.equal(formatCompactKRW(nul), "0");
  assert.equal(formatCompactKRW(undef), "0");
  assert.equal(formatCompactKRW(""), "0");
});

test("formatCompactKRW: 억·만 반올림 축약", () => {
  assert.equal(formatCompactKRW("345000000000"), "3,450억");
  assert.equal(formatCompactKRW("52000000"), "5,200만");
});

// formatCompactQty: 수량 축약(displayValue 직접 소비)
test("formatCompactQty: null·undefined·\"\"는 \"0.00000000\"", () => {
  assert.equal(formatCompactQty(nul), "0.00000000");
  assert.equal(formatCompactQty(undef), "0.00000000");
  assert.equal(formatCompactQty(""), "0.00000000");
});

test("formatCompactQty: 1 이상은 2자리 압축, 1 미만은 스케일 보존", () => {
  assert.equal(formatCompactQty("3.75"), "3.75");
  assert.equal(formatCompactQty("0.00412"), "0.00412000");
});

// formatDirectionalAmount: 등락 금액 병기(displayValue 경유)
test("formatDirectionalAmount: null·undefined·\"\" 강등도 화살표+부호 유지", () => {
  assert.equal(formatDirectionalAmount(nul, "RISE"), `${UP} +0`);
  assert.equal(formatDirectionalAmount(undef, "FALL"), `${DOWN} ${MINUS}0`);
  assert.equal(formatDirectionalAmount("", "EVEN"), "0");
});

test("formatDirectionalAmount: 유효 금액은 스펙 자릿수로 병기", () => {
  assert.equal(formatDirectionalAmount("1930000", "RISE", "1000"), `${UP} +1,930,000`);
  assert.equal(formatDirectionalAmount("0.000398", "FALL"), `${DOWN} ${MINUS}0.000398`);
});

test("formatDirectionalAmount: price_scale 0은 formatKRW와 같은 자릿수", () => {
  // 자산 평가손익은 KRW 금액이라 평가금액(formatKRW)과 자릿수를 맞춘다.
  // 스펙을 안 넘기면 소액 구간에서 폴백 자릿수가 붙어 같은 행의 평가금액과 어긋난다
  assert.equal(formatDirectionalAmount("12.34", "FALL", 0), `${DOWN} ${MINUS}${formatKRW("12.34")}`);
  assert.equal(formatDirectionalAmount("1234.5678", "RISE", 0), `${UP} +${formatKRW("1234.5678")}`);
  assert.notEqual(formatDirectionalAmount("12.34", "FALL"), `${DOWN} ${MINUS}${formatKRW("12.34")}`);
});
