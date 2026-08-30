/**
 * effectGovernor.ts. 체결 시각효과 자동 스로틀의 순수 로직(체결_시각효과 설계 §2-3).
 * React 비의존. 체결 도착률 EWMA 측정, 밀도 밴드(히스테리시스), 강도 강등,
 * 대형 체결 중앙값 판정을 담당한다. 훅(useTradeRateGovernor·useLargeTradeThreshold)이 감싼다.
 */

import type { EffectLevel } from "../../lib/types";

export const LEVEL_ORDER: EffectLevel[] = ["off", "subtle", "normal", "strong"];

export function levelIndex(level: EffectLevel): number {
  const i = LEVEL_ORDER.indexOf(level);
  return i < 0 ? LEVEL_ORDER.indexOf("normal") : i;
}

export function levelFromIndex(index: number): EffectLevel {
  const clamped = Math.max(0, Math.min(LEVEL_ORDER.length - 1, index));
  return LEVEL_ORDER[clamped];
}

/** 강도를 steps 단계 강등한다(0단계 미만은 off로 고정) */
export function downgradeLevel(level: EffectLevel, steps: number): EffectLevel {
  return levelFromIndex(levelIndex(level) - Math.max(0, steps));
}

export interface RateState {
  /** 체결 간격 EWMA(ms). null이면 미측정 */
  ewmaInterval: number | null;
  /** 마지막 체결 시각(performance.now 기준 ms) */
  lastTs: number | null;
}

export function createRateState(): RateState {
  return { ewmaInterval: null, lastTs: null };
}

/** 체결 1건 도착을 표본에 반영한다. alpha가 클수록 최근값에 민감 */
export function sampleRate(state: RateState, now: number, alpha = 0.25): void {
  if (state.lastTs === null) {
    state.lastTs = now;
    return;
  }
  const interval = Math.max(1, now - state.lastTs); // 0 나눗셈·역행 방어
  state.lastTs = now;
  state.ewmaInterval =
    state.ewmaInterval === null ? interval : state.ewmaInterval + alpha * (interval - state.ewmaInterval);
}

/**
 * 현재 추정 체결률(초당 건수). 마지막 체결 이후 경과가 EWMA 간격보다 크면
 * 그 경과로 낮춰 유휴 시 밴드가 자연 복귀하게 한다.
 */
export function currentRate(state: RateState, now: number): number {
  if (state.ewmaInterval === null || state.lastTs === null) return 0;
  const effectiveInterval = Math.max(state.ewmaInterval, now - state.lastTs);
  return effectiveInterval > 0 ? 1000 / effectiveInterval : 0;
}

// 상향 진입 임계(초당 건수). 밴드 k에서 이 값을 넘으면 k+1로 진입
export const BAND_UP = [5, 20, 60];
// 하향 복귀 임계(상향의 80%). 경계에서 깜빡임을 막는 20% 폭(§2-3)
export const BAND_DOWN = [4, 16, 48];

/** 현재 밴드와 체결률로 다음 밴드를 정한다. 0=전량 표시, 3=홍수 모드 */
export function nextBand(current: number, r: number): number {
  let b = Math.max(0, Math.min(3, current));
  while (b < 3 && r > BAND_UP[b]) b++;
  while (b > 0 && r < BAND_DOWN[b - 1]) b--;
  return b;
}

export interface GovernorResult {
  band: number;
  /** 강도 강등 단계 수 */
  downgradeSteps: number;
  /** 홍수 모드. 개별 팝 off, 패널 방향 히트 틴트 */
  flood: boolean;
  /** 셀 스로틀 가산(ms). 밴드가 오를수록 팝·플래시 빈도를 눌러 소음을 줄인다 */
  throttleBonusMs: number;
  /** 동시 팝 상한(밴드2 이상은 8, 홍수는 0) */
  popCap: number;
}

/** 밴드 → 거버너 동작(§2-3). 구현 소비: throttleBonusMs(TickCell 스로틀), popCap(팝 예산), flood(패널 히트 틴트) */
export function governorFor(band: number): GovernorResult {
  switch (band) {
    case 0:
      return { band: 0, downgradeSteps: 0, flood: false, throttleBonusMs: 0, popCap: Infinity };
    case 1:
      return { band: 1, downgradeSteps: 0, flood: false, throttleBonusMs: 100, popCap: 8 };
    case 2:
      return { band: 2, downgradeSteps: 1, flood: false, throttleBonusMs: 100, popCap: 8 };
    default:
      return { band: 3, downgradeSteps: 3, flood: true, throttleBonusMs: 100, popCap: 0 };
  }
}

/** 설정 강도에 거버너를 적용한 유효 강도. 홍수 모드는 표식만(off) */
export function effectiveLevel(configLevel: EffectLevel, gov: GovernorResult): EffectLevel {
  if (gov.flood) return "off";
  return downgradeLevel(configLevel, gov.downgradeSteps);
}

/** 오름차순 정렬 배열의 중앙값. 빈 배열은 0 */
export function medianOf(sortedAsc: number[]): number {
  const n = sortedAsc.length;
  if (n === 0) return 0;
  const mid = Math.floor(n / 2);
  return n % 2 === 1 ? sortedAsc[mid] : (sortedAsc[mid - 1] + sortedAsc[mid]) / 2;
}

/** 체결량이 중앙값의 K배 이상인지(§2-1 대형 체결). median<=0이면 판정 불가 → false */
export function isLargeVolume(volume: number, median: number, k = 8): boolean {
  return median > 0 && volume >= median * k;
}
