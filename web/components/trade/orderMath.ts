/**
 * 주문 패널 수치 로직. 가격 격자(틱) 반올림, 총액→수량 역산, 가용 대비 비중, 대형 주문 임계 판정.
 * OrderPanel에서 분리해 단위테스트로 회귀를 고정한다.
 * 격자·역산은 부동소수 대신 BigInt 십진 연산으로 처리한다. 저가·소수틱 마켓에서 0으로 붕괴하지 않게 한다.
 */

import type { Side } from "../../lib/types";
import { toNum } from "../../lib/format";
import {
  decimalsOf,
  fromScaledInt,
  normalizeDecimal,
  scaledIntRound,
  scaledIntTrunc,
} from "../../lib/decimal";

/** 가용 잔고 대비 이 비율 이상이면 인라인 확인 대신 확인 다이얼로그로 오발주를 막는다 */
export const LARGE_ORDER_RATIO = 0.7;

/**
 * 가격을 마켓 틱 격자에 반올림한 십진 문자열. 저가·소수틱(0.1, 0.001, 0.000398 등)에서 0으로 강제되지 않게 한다.
 * 틱이 0 이하면 격자 없이 틱 스케일로 문자열화만 한다.
 */
export function roundToTick(value: string | number, tick: string | number): string {
  const tickStr = normalizeDecimal(tick);
  const tScale = decimalsOf(tickStr);
  const tickInt = scaledIntTrunc(tickStr, tScale);
  if (tickInt <= 0n) return fromScaledInt(scaledIntRound(normalizeDecimal(value), tScale), tScale);
  const v = scaledIntRound(normalizeDecimal(value), tScale);
  const half = tickInt / 2n;
  const steps = v >= 0n ? (v + half) / tickInt : -((-v + half) / tickInt);
  return fromScaledInt(steps * tickInt, tScale);
}

/**
 * 현재값을 틱 격자에 스냅한 뒤 sign 방향으로 한 틱 이동한 십진 문자열. 최소 한 틱으로 클램프한다.
 */
export function stepToTick(value: string | number, tick: string | number, sign: 1 | -1): string {
  const tickStr = normalizeDecimal(tick);
  const tScale = decimalsOf(tickStr);
  const tickInt = scaledIntTrunc(tickStr, tScale);
  if (tickInt <= 0n) return fromScaledInt(scaledIntRound(normalizeDecimal(value), tScale), tScale);
  const snapped = scaledIntRound(roundToTick(value, tickStr), tScale);
  let next = snapped + BigInt(sign) * tickInt;
  if (next < tickInt) next = tickInt;
  return fromScaledInt(next, tScale);
}

/** 값이 속한 밴드의 격자. 마켓 스펙 틱이 있으면 그것이 권위다 */
function autoTick(
  value: string | number,
  specTick: string | null,
  fallbackTick: (price: number) => number,
): string {
  if (specTick !== null) return specTick;
  const n = toNum(value);
  return String(fallbackTick(n > 0 ? n : 1));
}

/**
 * 자동 채움(프리필·현재가) 가격을 격자에 맞춘다. 폴백 틱은 스냅 대상 값 자체에서 유도한다.
 * 직전 렌더의 파생 틱을 쓰면 가격 미상 상태(콜드 마운트)의 틱으로 스냅해, 값이 들어간 다음
 * 렌더의 폴백 구간과 어긋난다. 그 순간부터 표시가와 제출 기준가가 갈린다.
 */
export function autoFillPrice(
  value: string | number,
  specTick: string | null,
  fallbackTick: (price: number) => number,
): string {
  return roundToTick(value, autoTick(value, specTick, fallbackTick));
}

/**
 * 자동 채움 격자 위에서 한 틱 이동. 시작값을 먼저 자기 밴드 격자로 정렬한 뒤 그 밴드 틱으로 민다.
 * 렌더 시점 틱을 그대로 넘기면 밴드 경계에서 결과가 도착 밴드 격자 밖에 떨어진다.
 */
export function stepAutoPrice(
  value: string | number,
  specTick: string | null,
  fallbackTick: (price: number) => number,
  sign: 1 | -1,
): string {
  const start = autoFillPrice(value, specTick, fallbackTick);
  const raw = stepToTick(start, autoTick(start, specTick, fallbackTick), sign);
  const aligned = autoFillPrice(raw, specTick, fallbackTick);
  if (aligned !== start) return aligned;
  // 재정렬을 거치지 않는 유일한 반환. 현 밴드표에서는 하한 클램프(최소 한 틱)에서만 도달하고
  // 그때 값은 start 그대로라 고정점이 유지된다. 밴드표를 바꾸면 이 경로부터 확인할 것
  return stepToTick(start, autoTick(raw, specTick, fallbackTick), sign);
}

/**
 * 스펙이 늦게 오면 폴백 격자로 채운 값이 권위 기준가와 갈리므로 권위 틱으로 다시 스냅한다.
 * 사용자가 타이핑 중이면 null을 돌려 입력을 덮어쓰지 않는다.
 */
export function resnapAutoPrice(current: string, auto: string, tick: string | null): string | null {
  if (tick === null || current === "" || current !== auto) return null;
  const snapped = roundToTick(current, tick);
  return snapped === current ? null : snapped;
}

/**
 * 가격 입력 필드 표기. 정수부는 자리 구분, 소수부는 입력 그대로 유지(입력 중 후행 0·소수점 보존).
 * formatKRW는 Math.round로 소수를 버려 저가 마켓 입력을 0으로 만든다. 그 자리를 대체한다.
 */
const nfGroup = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
export function formatPriceField(raw: string): string {
  if (!raw) return "";
  const neg = raw.startsWith("-");
  const body = neg ? raw.slice(1) : raw;
  const dot = body.indexOf(".");
  const intPart = dot < 0 ? body : body.slice(0, dot);
  const fracPart = dot < 0 ? "" : body.slice(dot + 1);
  const intNum = intPart === "" ? 0 : Number(intPart);
  const grouped = Number.isFinite(intNum) ? nfGroup.format(intNum) : intPart || "0";
  return (neg ? "-" : "") + grouped + (dot < 0 ? "" : "." + fracPart);
}

/**
 * 잔고 초과를 막으려 반올림하지 않고 내림한다. 2^53 초과 잔고에서도 정확하도록 BigInt로 계산한다.
 * pct는 0~100 정수로 좁히고, 소수 pct는 내림으로 삼켜진다(33.3 -> 33).
 */
export function pctOfDecimal(value: string | number, pct: number, scale: number): string {
  const s = Math.min(Math.max(scale, 0), 30);
  const v = scaledIntTrunc(normalizeDecimal(value), s);
  if (v <= 0n || !Number.isFinite(pct)) return "0";
  const p = BigInt(Math.trunc(Math.min(Math.max(pct, 0), 100)));
  return fromScaledInt((v * p) / 100n, s);
}

/**
 * 주문총액→수량 역산. 가격이 양수일 때만 역산하고, 그 외에는 null로 미변경을 알린다.
 * 나눗셈·절사를 BigInt 십진 연산으로 처리해 저가 마켓 부동소수 오차를 제거한다.
 * 수량이 원본, 총액은 파생이라 총액 입력은 수량으로 되쓴다.
 */
export function qtyFromTotal(total: string | number, price: string | number, scale: number): string | null {
  if (toNum(price) <= 0) return null;
  const s = Math.min(Math.max(scale, 0), 30);
  const totalStr = normalizeDecimal(total);
  const priceStr = normalizeDecimal(price);
  const sp = decimalsOf(priceStr);
  const st = decimalsOf(totalStr);
  const totalInt = scaledIntTrunc(totalStr, st);
  const priceInt = scaledIntTrunc(priceStr, sp);
  if (priceInt <= 0n) return null;
  // qty = floor(totalInt * 10^(sp+s) / (priceInt * 10^st)) / 10^s
  const num = totalInt * 10n ** BigInt(sp + s);
  const den = priceInt * 10n ** BigInt(st);
  const qtyScaled = num / den; // 양수 BigInt 나눗셈은 내림
  return fromScaledInt(qtyScaled, s);
}

/**
 * 가용 잔고 대비 주문 비중. bid는 락/현금, ask는 수량/보유코인.
 * 예약은 수수료를 포함하지 않으므로(§4-B-5 v2.7) bid 분자에 수수료 항이 없다.
 * 분모가 0 이하면 0. 미확정 잔고에서 임계가 오작동하지 않게 막는다.
 */
export function committedRatioOf(
  side: Side,
  lock: number,
  availKRW: number,
  availCoin: number,
  qty: number,
): number {
  if (side === "bid") return availKRW > 0 ? lock / availKRW : 0;
  return availCoin > 0 ? qty / availCoin : 0;
}

/** 대형 주문 여부. 제출 가능(ready)하고 비중이 임계 이상일 때만 다이얼로그로 넘긴다 */
export function isLargeOrder(ready: boolean, ratio: number, threshold: number = LARGE_ORDER_RATIO): boolean {
  return ready && ratio >= threshold;
}
