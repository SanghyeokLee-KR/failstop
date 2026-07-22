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

/* ===== 격자(틱) 반올림 · 총액 역산 (십진 연산은 lib/decimal 공유) ===== */

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
 * 소수 자릿수 내림. 주문 가능 수량 초과 방지.
 * 부동소수 표현 오차를 절사 직전 흡수하고, scale 상한을 둬 스케일 폭주를 막는다(qty_scale>=15 오버플로 가드).
 */
export function floorTo(value: number, scale: number): number {
  if (!Number.isFinite(value)) return 0;
  const s = Math.min(Math.max(scale, 0), 15);
  const p = 10 ** s;
  const scaled = value * p;
  const eps = Math.abs(scaled) * Number.EPSILON * 4;
  return Math.floor(scaled + eps) / p;
}

/**
 * 비지수 문자열화. String()은 1e-6 미만에서 지수표기("1e-8")를 만들어 수량 입력에 노출된다.
 * toFixed(scale)로 고정 소수를 만든 뒤 의미 없는 후행 0을 제거한다.
 */
export function toPlainString(value: number, scale: number): string {
  if (!Number.isFinite(value)) return "0";
  const fixed = value.toFixed(Math.min(Math.max(scale, 0), 100));
  if (fixed.indexOf(".") < 0) return fixed;
  return fixed.replace(/0+$/, "").replace(/\.$/, "");
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
  // qty = floor(total/price, s)
  //     = floor( (totalInt/10^st) / (priceInt/10^sp) * 10^s ) / 10^s
  //     = floor( totalInt * 10^(sp+s) / (priceInt * 10^st) ) / 10^s
  const num = totalInt * 10n ** BigInt(sp + s);
  const den = priceInt * 10n ** BigInt(st);
  const qtyScaled = num / den; // 양수 BigInt 나눗셈은 내림
  return fromScaledInt(qtyScaled, s);
}

/**
 * 가용 잔고 대비 주문 비중. bid는 (총액+수수료)/현금, ask는 수량/보유코인.
 * 분모가 0 이하면 0. 미확정 잔고에서 임계가 오작동하지 않게 막는다.
 */
export function committedRatioOf(
  side: Side,
  total: number,
  fee: number,
  availKRW: number,
  availCoin: number,
  qty: number,
): number {
  if (side === "bid") return availKRW > 0 ? (total + fee) / availKRW : 0;
  return availCoin > 0 ? qty / availCoin : 0;
}

/** 대형 주문 여부. 제출 가능(ready)하고 비중이 임계 이상일 때만 다이얼로그로 넘긴다 */
export function isLargeOrder(ready: boolean, ratio: number, threshold: number = LARGE_ORDER_RATIO): boolean {
  return ready && ratio >= threshold;
}
