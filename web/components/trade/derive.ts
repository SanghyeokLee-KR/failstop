/**
 * 거래 화면 파생 계산 (§2.5).
 * orderbook·trade 메시지에는 전일종가가 없어 ticker에서 역산해 조인한다.
 * 역산·등락률은 문자열 십진 연산으로 처리한다. float 왕복은 근접 가격의 방향·비율 오차를 만든다.
 */

import type { ChangeState, TickerMessage } from "../../lib/types";
import { toNum } from "../../lib/format";
import {
  absDecimal,
  compareDecimal,
  decimalsOf,
  fromScaledInt,
  normalizeDecimal,
  scaledIntTrunc,
} from "../../lib/decimal";

// 문자열 십진 덧셈·뺄셈. decimal.ts는 곱·비교만 제공한다. 전일종가 역산에서 float 왕복을 없애려 여기서 조인한다.
function addDec(a: string, b: string): string {
  const s = Math.max(decimalsOf(a), decimalsOf(b));
  return fromScaledInt(scaledIntTrunc(a, s) + scaledIntTrunc(b, s), s);
}
function subDec(a: string, b: string): string {
  const s = Math.max(decimalsOf(a), decimalsOf(b));
  return fromScaledInt(scaledIntTrunc(a, s) - scaledIntTrunc(b, s), s);
}

// 등락률 소수부 자리수. 백분율 2자리 표시에 충분한 여유
const RATE_SCALE = 8;
// |a| / b 를 고정 자리수 비율 문자열로. b<=0이면 "0"(0 나눗셈 보호)
function ratioStr(a: string, b: string): string {
  const da = decimalsOf(a);
  const db = decimalsOf(b);
  let n = scaledIntTrunc(a, da);
  if (n < 0n) n = -n;
  const d = scaledIntTrunc(b, db);
  if (d <= 0n) return "0";
  const scaled = (n * 10n ** BigInt(db + RATE_SCALE)) / (d * 10n ** BigInt(da));
  return fromScaledInt(scaled, RATE_SCALE);
}

/** 전일종가 = 현재가 ∓ 등락액 (RISE는 차감, FALL은 가산). 문자열 십진으로 정밀 보존 */
export function prevCloseOf(ticker: TickerMessage | undefined): string {
  // 첫 체결 전 마켓은 trade_price가 null이다(§4-A-4). 기준가가 없으므로 역산 없이 0으로 조기 반환
  if (!ticker || ticker.trade_price == null) return "0";
  const cur = normalizeDecimal(ticker.trade_price);
  const chg = normalizeDecimal(ticker.change_price);
  if (ticker.change === "RISE") return subDec(cur, chg);
  if (ticker.change === "FALL") return addDec(cur, chg);
  return cur;
}

export interface DerivedRate {
  /** 절대값 등락률 문자열 (formatChangeRate 입력 규격) */
  rate: string;
  state: ChangeState;
}

/**
 * 호가 레벨 실재 여부. 얇은 호가에서 서버가 0으로 채운 빈 슬롯을 걸러낸다.
 * 가격 0을 기준으로 등락률을 역산하면 -100%가 나오는 오표시를 막는다.
 */
export function isPresentLevel(price: string, size: string): boolean {
  return toNum(price) > 0 && toNum(size) > 0;
}

/** 임의 가격의 전일종가 대비 등락률. float 왕복 없이 문자열 비율로 산출한다 */
export function rateVsPrevClose(price: string | number, prevClose: string): DerivedRate {
  if (compareDecimal(prevClose, "0") <= 0) return { rate: "0", state: "EVEN" };
  const p = normalizeDecimal(price);
  const cmp = compareDecimal(p, prevClose);
  if (cmp === 0) return { rate: "0", state: "EVEN" };
  const rate = ratioStr(absDecimal(subDec(p, prevClose)), prevClose);
  return { rate, state: cmp > 0 ? "RISE" : "FALL" };
}
