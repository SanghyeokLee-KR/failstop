/**
 * tests/effectGovernor.test.ts. 체결 시각효과 거버너 순수 로직 검증(설계 §2-3).
 * 강도 강등, 밀도 밴드 히스테리시스, 유효 강도, 대형 체결 중앙값 판정을 확인한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  BAND_DOWN,
  BAND_UP,
  createRateState,
  currentRate,
  downgradeLevel,
  effectiveLevel,
  governorFor,
  isLargeVolume,
  levelFromIndex,
  levelIndex,
  medianOf,
  nextBand,
  sampleRate,
} from "../components/trade/effectGovernor";

test("강도 강등은 off로 하한 고정", () => {
  assert.equal(downgradeLevel("strong", 1), "normal");
  assert.equal(downgradeLevel("normal", 1), "subtle");
  assert.equal(downgradeLevel("subtle", 1), "off");
  assert.equal(downgradeLevel("off", 1), "off");
  assert.equal(downgradeLevel("strong", 3), "off");
  assert.equal(downgradeLevel("normal", 0), "normal");
});

test("levelIndex·levelFromIndex 왕복과 클램프", () => {
  assert.equal(levelIndex("off"), 0);
  assert.equal(levelIndex("strong"), 3);
  assert.equal(levelFromIndex(-5), "off");
  assert.equal(levelFromIndex(99), "strong");
});

test("밴드는 상향 임계 초과 시 상승", () => {
  assert.equal(nextBand(0, 2), 0);
  assert.equal(nextBand(0, BAND_UP[0] + 0.1), 1);
  assert.equal(nextBand(1, BAND_UP[1] + 1), 2);
  assert.equal(nextBand(2, BAND_UP[2] + 1), 3);
});

test("히스테리시스: 복귀는 하향 임계 미만에서만", () => {
  // 밴드1에서 상향 임계(20)와 복귀 임계(16) 사이면 유지, 미만이면 하강
  assert.equal(nextBand(2, 18), 2); // 16 < 18 < 20 → 유지
  assert.equal(nextBand(2, BAND_DOWN[1] - 0.1), 1); // 16 미만 → 하강
  assert.equal(nextBand(1, BAND_DOWN[0] - 0.1), 0); // 4 미만 → 하강
});

test("거버너 밴드별 동작", () => {
  assert.equal(governorFor(0).downgradeSteps, 0);
  assert.equal(governorFor(0).flood, false);
  assert.equal(governorFor(0).throttleBonusMs, 0);
  assert.equal(governorFor(0).popCap, Infinity);
  assert.equal(governorFor(2).downgradeSteps, 1);
  assert.equal(governorFor(2).throttleBonusMs, 100);
  assert.equal(governorFor(2).popCap, 8);
  assert.equal(governorFor(3).flood, true);
  assert.equal(governorFor(3).popCap, 0);
});

test("유효 강도: 홍수는 off, 강등은 단계 하향", () => {
  assert.equal(effectiveLevel("strong", governorFor(0)), "strong");
  assert.equal(effectiveLevel("strong", governorFor(2)), "normal");
  assert.equal(effectiveLevel("normal", governorFor(3)), "off");
});

test("EWMA 체결률: 100ms 간격이면 약 10건/s", () => {
  const st = createRateState();
  let now = 0;
  sampleRate(st, now);
  for (let i = 0; i < 20; i++) {
    now += 100;
    sampleRate(st, now);
  }
  const r = currentRate(st, now);
  assert.ok(r > 8 && r < 12, `rate=${r}`);
});

test("유휴 시 경과가 커지면 체결률 하락", () => {
  const st = createRateState();
  sampleRate(st, 0);
  sampleRate(st, 100); // 10건/s 수준
  const idle = currentRate(st, 100 + 5000); // 5초 유휴
  assert.ok(idle < 1, `idle rate=${idle}`);
});

test("중앙값: 홀짝 길이", () => {
  assert.equal(medianOf([]), 0);
  assert.equal(medianOf([5]), 5);
  assert.equal(medianOf([1, 3]), 2);
  assert.equal(medianOf([1, 2, 3]), 2);
});

test("대형 체결: 중앙값 K배 이상", () => {
  assert.equal(isLargeVolume(80, 10, 8), true);
  assert.equal(isLargeVolume(79, 10, 8), false);
  assert.equal(isLargeVolume(1000, 0, 8), false); // 표본 없음
});

/**
 * 고레이트 도착 시퀀스가 소비 경로(sampleRate→currentRate→nextBand→governorFor)를 통과하며
 * 밴드가 실제로 상승·홍수 진입하는지 검증한다. sim 틱(약 1/s)은 밴드1 임계에 미달하므로
 * 거버너 대량 틱 동작은 이 시퀀스 테스트가 유일한 증명 경로다(설계 §2-3).
 */
function driveRate(intervalMs: number, count: number) {
  const st = createRateState();
  let band = 0;
  let now = 0;
  sampleRate(st, now);
  for (let i = 0; i < count; i++) {
    now += intervalMs;
    sampleRate(st, now);
    band = nextBand(band, currentRate(st, now));
  }
  return { st, band, now };
}

test("고레이트 200/s: 홍수 밴드 진입, 팝 off", () => {
  const { band } = driveRate(5, 60); // 5ms 간격 ≈ 200건/s
  assert.equal(band, 3);
  const gov = governorFor(band);
  assert.equal(gov.flood, true);
  assert.equal(gov.popCap, 0);
  assert.equal(effectiveLevel("strong", gov), "off");
});

test("중레이트 40/s: 밴드2 강등·팝 캡 유지", () => {
  const { band } = driveRate(25, 60); // 25ms 간격 ≈ 40건/s → 20~60 구간
  assert.equal(band, 2);
  const gov = governorFor(band);
  assert.equal(gov.downgradeSteps, 1);
  assert.equal(gov.popCap, 8);
  assert.equal(effectiveLevel("strong", gov), "normal");
});

test("고레이트 후 유휴: 밴드 자연 복귀", () => {
  const { st, band, now } = driveRate(5, 60);
  assert.equal(band, 3);
  // 체결이 멎으면 경과가 커져 체결률이 하향, 밴드가 0으로 복귀한다
  let b = band;
  b = nextBand(b, currentRate(st, now + 5000));
  assert.equal(b, 0);
});
