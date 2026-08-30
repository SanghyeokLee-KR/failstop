/**
 * 락·수수료·원가 계약 산식의 단일 출처. 정본은 API 설계 §4-B-5·§4-C-1이고,
 * 원가 전이는 DB 설계 §6.4 ⑱⑲(position_cost) 함수와 같은 분기다.
 * 산식 사본을 두면 sim이 라이브와 갈리고, 그 갈림이 라이브 결함을 은폐한다.
 */

import {
  addDecimal,
  ceilToScale,
  compareDecimal,
  divDecimal,
  mulDecimal,
  roundToScale,
  subDecimal,
  truncToScale,
} from "./decimal";

/** 원가 누산 최대 자릿수. NUMERIC(40,18)의 소수부와 같다(서버 trunc(...,18)과 동일 격자) */
const COST_SCALE = 18;

/* ===== 예약(락) 산식 (§4-B-5 v2.7) ===== */

/**
 * 지정가 매수 락. `qty × price`를 quote scale로 UP 양자화한다. 수수료는 예약하지 않는다.
 * 반올림·내림으로 잡으면 락이 체결 대금보다 작아져 마지막 체결에서 모자란다.
 */
export function bidLockAmount(price: string, qty: string, priceScale: number): string {
  return ceilToScale(mulDecimal(price, qty), priceScale);
}

/** 시장가 매수 락. 주문 대금이 곧 락이다(수수료 미포함) */
export function marketBidLockAmount(notional: string, priceScale: number): string {
  return ceilToScale(notional, priceScale);
}

/** 매도 락. base 수량 그대로 잡는다(수수료는 수취 quote에서 나가 base 예약에 없다) */
export function askLockQty(qty: string): string {
  return qty;
}

/* ===== 수수료 산식 (§4-B-5 v2.7 수취 자산 기준) ===== */

/**
 * 매수 수수료. 수취 자산이 base라 수수료도 base로 나간다.
 * `수취 수량 × 요율`을 qty_scale로 HALF_UP 선양자화한다.
 */
export function buyFeeBase(recvQty: string, feeRate: string, qtyScale: number): string {
  return roundToScale(mulDecimal(recvQty, feeRate), qtyScale);
}

/** 매도 수수료. 수취 자산이 quote라 `체결대금 × 요율`을 price_scale로 HALF_UP 선양자화한다 */
export function sellFeeQuote(funds: string, feeRate: string, priceScale: number): string {
  return roundToScale(mulDecimal(funds, feeRate), priceScale);
}

/* ===== 원가 산출 (§4-C-1 v2.21) ===== */

/** 원가 확보 여부. `"0"`인 avg_buy_price가 평단가 0원인지 산출 불가인지의 유일한 판별자다 */
export function isCostKnown(costBasisQty: string): boolean {
  return compareDecimal(costBasisQty, "0") > 0;
}

/**
 * 평단가 산출. `cost_basis_amount / cost_basis_qty`를 quote 자산 scale로 HALF_UP 반올림한다.
 * 원가 확보분이 없으면 `"0"`이며 이는 평단가 0원이 아니라 산출 불가다.
 */
export function avgBuyPriceOf(costAmount: string, costQty: string, quoteScale: number): string {
  if (!isCostKnown(costQty)) return "0";
  return divDecimal(costAmount, costQty, quoteScale, "half-up") ?? "0";
}

/**
 * 원가 미상 수량 유도(§4-C-1 K3). 서버는 이 값을 별도 필드로 내지 않는다.
 * `balance + locked - cost_basis_qty`가 음수면 그 응답 자체가 C24 위반이라 0으로 클램프한다.
 */
export function unknownCostQty(balance: string, locked: string, costQty: string): string {
  const diff = subDecimal(addDecimal(balance, locked), costQty);
  return compareDecimal(diff, "0") > 0 ? diff : "0";
}

/* ===== 원가 프로젝션 전이 (DB 설계 §6.4 ⑱⑲ apply_position_cost 미러) ===== */

export interface CostBasis {
  /** 원가 확보 수량 */
  costQty: string;
  /** 원가 확보 금액(unit_currency) */
  costAmount: string;
  /** 원가 미상 수량. 서버는 저장만 하고 API로 내지 않는다(클라이언트는 유도식을 쓴다) */
  unknownQty: string;
}

export const EMPTY_COST_BASIS: CostBasis = { costQty: "0", costAmount: "0", unknownQty: "0" };

/**
 * 사용자별 자산 순델타 한 벌을 원가 누산기에 반영한다. 서버 apply_position_cost와 같은 분기다.
 * dBase는 실수취·실지출 반영 후의 순델타이고, RESERVE·RELEASE는 순델타 0이라 무동작으로 흡수된다.
 */
export function applyCostDelta(prev: CostBasis, dBase: string, dQuote: string): CostBasis {
  const dir = compareDecimal(dBase, "0");
  if (dir === 0) return prev;
  if (dir > 0) {
    // 대가를 지불한 취득만 원가로 잡는다. 시드 발행·입금은 원가 0이 아니라 미상이다
    if (compareDecimal(dQuote, "0") < 0) {
      return {
        costQty: addDecimal(prev.costQty, dBase),
        costAmount: addDecimal(prev.costAmount, subDecimal("0", dQuote)),
        unknownQty: prev.unknownQty,
      };
    }
    return { ...prev, unknownQty: addDecimal(prev.unknownQty, dBase) };
  }

  // 처분은 원가 확보분 우선 소진(known-first)
  const out = subDecimal("0", dBase);
  const known = compareDecimal(out, prev.costQty) < 0 ? out : prev.costQty;
  const rest = subDecimal(out, known);
  let relief: string;
  if (compareDecimal(known, "0") <= 0) relief = "0";
  // 전량 스윕은 나눗셈 없이 잔여 원가 전액을 덜어낸다. 절사 안분을 쓰면 수량 0인데 원가가
  // 남는 유령 손실이 생기고 (costQty=0)=(costAmount=0) 쌍 불변식이 깨진다
  else if (compareDecimal(known, prev.costQty) === 0) relief = prev.costAmount;
  else {
    const ratio = divDecimal(mulDecimal(prev.costAmount, known), prev.costQty, COST_SCALE, "trunc") ?? "0";
    relief = compareDecimal(ratio, prev.costAmount) < 0 ? ratio : prev.costAmount;
  }

  const nextQty = subDecimal(prev.costQty, known);
  const nextAmount = subDecimal(prev.costAmount, relief);
  const nextUnknownRaw = subDecimal(prev.unknownQty, rest);
  const nextUnknown = compareDecimal(nextUnknownRaw, "0") > 0 ? nextUnknownRaw : "0";
  // 한쪽만 0이 되면 잔여 수량을 원가 미상으로 강등한다(서버 CHECK 방어와 같은 처리)
  if (compareDecimal(nextQty, "0") <= 0 || compareDecimal(nextAmount, "0") <= 0) {
    return {
      costQty: "0",
      costAmount: "0",
      unknownQty: addDecimal(nextUnknown, compareDecimal(nextQty, "0") > 0 ? nextQty : "0"),
    };
  }
  return { costQty: nextQty, costAmount: nextAmount, unknownQty: nextUnknown };
}

/** 누산기를 계약 표기 자릿수로 고정한다. 문자열 비교가 스케일 차이로 흔들리지 않게 한다 */
export function normalizeCostBasis(c: CostBasis): CostBasis {
  return {
    costQty: truncToScale(c.costQty, COST_SCALE),
    costAmount: truncToScale(c.costAmount, COST_SCALE),
    unknownQty: truncToScale(c.unknownQty, COST_SCALE),
  };
}
