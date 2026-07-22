/**
 * 주문 패널 수치 로직. 총액→수량 역산, 가용 대비 비중, 대형 주문 임계 판정.
 * OrderPanel에서 분리해 단위테스트로 회귀를 고정한다. 표시·상태는 여기서 다루지 않는다.
 */

import type { Side } from "../../lib/types";

/** 가용 잔고 대비 이 비율 이상이면 인라인 확인 대신 확인 다이얼로그로 오발주를 막는다 */
export const LARGE_ORDER_RATIO = 0.7;

/** 소수 자릿수 내림. 주문 가능 수량 초과 방지 */
export function floorTo(value: number, scale: number): number {
  const p = 10 ** scale;
  return Math.floor(value * p) / p;
}

/**
 * 비지수 문자열화. String()은 1e-6 미만에서 지수표기("1e-8")를 만들어 수량 입력에 노출된다.
 * toFixed(scale)로 고정 소수를 만든 뒤 의미 없는 후행 0을 제거한다.
 */
export function toPlainString(value: number, scale: number): string {
  if (!Number.isFinite(value)) return "0";
  const fixed = value.toFixed(scale);
  if (fixed.indexOf(".") < 0) return fixed;
  return fixed.replace(/0+$/, "").replace(/\.$/, "");
}

/**
 * 주문총액→수량 역산. 가격이 양수일 때만 역산하고, 그 외에는 null로 미변경을 알린다.
 * 수량이 원본, 총액은 파생이라 총액 입력은 수량으로 되쓴다.
 */
export function qtyFromTotal(total: number, price: number, scale: number): string | null {
  if (price <= 0) return null;
  return toPlainString(floorTo(total / price, scale), scale);
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
