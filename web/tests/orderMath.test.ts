/**
 * tests/orderMath.test.ts. 주문 패널 수치 로직 회귀 고정.
 * 대상: 총액→수량 역산, 비지수 문자열화, 가용 대비 비중, 대형 주문 임계 판정.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { priceStep } from "../lib/sim/engine";
import {
  LARGE_ORDER_RATIO,
  autoFillPrice,
  formatPriceField,
  pctOfDecimal,
  roundToTick,
  stepToTick,
  qtyFromTotal,
  resnapAutoPrice,
  stepAutoPrice,
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

/* ===== 퍼센트 프리셋 (문자열 십진) ===== */

test("퍼센트 프리셋: scale 자리 내림", () => {
  assert.equal(pctOfDecimal("0.123456789", 100, 8), "0.12345678");
  assert.equal(pctOfDecimal("33.33339", 100, 4), "33.3333");
});

test("퍼센트 프리셋: 10·25·50%는 절사 없이 떨어진다", () => {
  assert.equal(pctOfDecimal("1000", 10, 8), "100");
  assert.equal(pctOfDecimal("1000", 25, 8), "250");
  assert.equal(pctOfDecimal("1000", 50, 8), "500");
});

test("퍼센트 프리셋: 스케일드 2^53 경계에서도 정확", () => {
  // 9007199254740993 = 2^53 + 1. double이 표현하지 못해 float 경로는 ...992로 뭉갠다
  const avail = "90071992.54740993";
  assert.equal(pctOfDecimal(avail, 100, 8), avail);
  const floatPath = (Math.floor(Number(avail) * 1e8) / 1e8).toFixed(8);
  assert.notEqual(floatPath, avail, "float 경로였으면 깨지는 값이어야 판별력이 있다");
});

test("퍼센트 프리셋: 2^53 초과 잔고의 절반도 정확", () => {
  assert.equal(pctOfDecimal("180143985.09481986", 50, 8), "90071992.54740993");
});

test("퍼센트 프리셋: 잔고 0 이하는 0", () => {
  assert.equal(pctOfDecimal("0", 100, 8), "0");
  assert.equal(pctOfDecimal("-5", 100, 8), "0");
});

test("퍼센트 프리셋: 지수표기 잔고도 십진으로 받는다", () => {
  assert.equal(pctOfDecimal(1e-8, 100, 8), "0.00000001");
});

test("퍼센트 프리셋: 비유한 pct는 0으로 흡수(BigInt 변환 예외 차단)", () => {
  assert.equal(pctOfDecimal("1000", Number.NaN, 8), "0");
  assert.equal(pctOfDecimal("1000", Number.POSITIVE_INFINITY, 8), "0");
});

test("bid 비중: 락/현금(수수료 미포함, v2.7 예약 모델)", () => {
  assert.equal(committedRatioOf("bid", 700_000, 1_000_000, 0, 0), 0.7);
});

test("bid 비중: 현금 0이면 0(분모 가드)", () => {
  assert.equal(committedRatioOf("bid", 700_000, 0, 0, 0), 0);
});

test("ask 비중: 수량/보유코인", () => {
  assert.equal(committedRatioOf("ask", 0, 0, 2, 1), 0.5);
});

test("ask 비중: 보유코인 0이면 0(분모 가드)", () => {
  assert.equal(committedRatioOf("ask", 0, 0, 0, 1), 0);
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

/* ===== 마켓 스펙 지연 도착: 자동 채움 격자와 재스냅 ===== */

test("자동 채움: 스펙 틱이 있으면 권위 격자로 스냅", () => {
  assert.equal(autoFillPrice("84320450", "1000", priceStep), "84320000");
  assert.equal(autoFillPrice("0.40449", "0.001", priceStep), "0.404");
});

test("자동 채움: 스펙이 없으면 폴백 틱을 스냅 대상 값에서 유도", () => {
  // 직전 렌더 파생 틱(가격 미상 구간의 priceStep(1)=0.01)을 쓰면 84320450이 그대로 남아
  // 다음 렌더의 폴백 격자(1000) 기준가 84320000과 갈린다
  assert.equal(autoFillPrice("84320450", null, priceStep), "84320000");
});

// 폴백 밴드 경계 근방 표본. 경계를 넘는 스냅·스텝이 도착 밴드 격자 밖에 떨어지는지 본다
const BAND_SAMPLES = ["0.009995", "0.01001", "0.0999", "0.1", "0.999", "1.001", "9.97", "9.99", "10.04",
  "99.7", "100.1", "999.6", "9999.6", "10001", "99990", "100010", "499950", "500100",
  "999999.6", "1000100", "1999999.7", "2000500", "84320450"];

/** 값이 자기 폴백 밴드 격자 위에 있는지. 표시가와 제출 기준가가 갈리지 않을 조건 */
function isOnRenderGrid(v: string): boolean {
  return roundToTick(v, String(priceStep(Number(v)))) === v;
}

test("자동 채움: 스펙 없는 결과는 렌더 폴백 격자의 고정점이다", () => {
  for (const v of BAND_SAMPLES) {
    const filled = autoFillPrice(v, null, priceStep);
    assert.ok(isOnRenderGrid(filled), `${v} -> ${filled}`);
  }
});

test("한 틱 이동: 밴드 경계를 넘어도 결과가 도착 밴드 격자 위에 있다", () => {
  // 회귀: stepToTick에 렌더 시점 틱을 넘기면 999,999.6 한 틱 위가 1,000,100이 되고
  // 제출 기준가는 1,000,000이 된다(밴드 500 격자 밖)
  for (const v of BAND_SAMPLES) {
    for (const sign of [1, -1] as const) {
      const next = stepAutoPrice(v, null, priceStep, sign);
      assert.ok(isOnRenderGrid(next), `${v} ${sign} -> ${next}`);
    }
  }
});

test("한 틱 이동: 정렬된 시작점과 반드시 달라진다(단조성)", () => {
  for (const v of BAND_SAMPLES) {
    for (const sign of [1, -1] as const) {
      const start = autoFillPrice(v, null, priceStep);
      assert.notEqual(stepAutoPrice(v, null, priceStep, sign), start, `${v} ${sign}`);
    }
  }
});

test("한 틱 이동: 밴드 경계 직하 값은 도착 밴드 틱만큼 움직인다", () => {
  assert.equal(stepAutoPrice("999999.6", null, priceStep, 1), "1000500");
  assert.equal(stepAutoPrice("1999999.7", null, priceStep, 1), "2001000");
  assert.equal(stepAutoPrice("9999.6", null, priceStep, 1), "10010");
});

test("한 틱 이동: 스펙 틱이 있으면 권위 격자만 쓴다", () => {
  assert.equal(stepAutoPrice("84320123", "1000", priceStep, 1), "84321000");
  assert.equal(stepAutoPrice("84320123", "1000", priceStep, -1), "84319000");
});

test("한 틱 이동: 정렬이 스텝을 삼키면 도착 밴드 틱으로 다시 민다", () => {
  // 폴백 밴드 격자가 시작점을 포함해 재정렬이 제자리로 되돌리는 배치
  const banded = (p: number) => (p >= 100 ? 33 : 1);
  assert.equal(autoFillPrice("100", null, banded), "99"); // 재정렬이 시작점으로 되돌린다
  assert.equal(stepAutoPrice("99", null, banded, 1), "132");
});

test("재스냅: 폴백 격자로 채운 값을 권위 틱으로 다시 스냅", () => {
  // 폴백 틱 0.1로 182.4가 된 뒤 스펙 틱 1이 도착한 상황
  assert.equal(resnapAutoPrice("182.4", "182.4", "1"), "182");
});

test("재스냅: 사용자가 타이핑 중인 값은 덮어쓰지 않는다", () => {
  assert.equal(resnapAutoPrice("182.37", "182.4", "1"), null);
});

test("재스냅: 스펙 미도착이면 대상 아님", () => {
  assert.equal(resnapAutoPrice("182.4", "182.4", null), null);
});

test("재스냅: 빈 입력은 대상 아님", () => {
  assert.equal(resnapAutoPrice("", "", "1000"), null);
});

test("재스냅: 이미 격자 위면 상태 갱신 없음", () => {
  assert.equal(resnapAutoPrice("84320000", "84320000", "1000"), null);
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
