/**
 * 거래 화면 파생 계산 (§2.5).
 * orderbook·trade 메시지에는 전일종가가 없어 ticker에서 역산해 조인한다.
 */

import type { ChangeState, TickerMessage } from "../../lib/types";
import { toNum } from "../../lib/format";

/** 전일종가 = 현재가 ∓ 등락액 (RISE는 차감, FALL은 가산) */
export function prevCloseOf(ticker: TickerMessage | undefined): number {
  if (!ticker) return 0;
  const cur = toNum(ticker.trade_price);
  const chg = toNum(ticker.change_price);
  if (ticker.change === "RISE") return cur - chg;
  if (ticker.change === "FALL") return cur + chg;
  return cur;
}

export interface DerivedRate {
  /** 절대값 등락률 (formatChangeRate 입력 규격) */
  rate: number;
  state: ChangeState;
}

/**
 * 호가 레벨 실재 여부. 얇은 호가에서 서버가 0으로 채운 빈 슬롯을 걸러낸다.
 * 가격 0을 기준으로 등락률을 역산하면 -100%가 나오는 오표시를 막는다.
 */
export function isPresentLevel(price: string, size: string): boolean {
  return toNum(price) > 0 && toNum(size) > 0;
}

/** 임의 가격의 전일종가 대비 등락률 */
export function rateVsPrevClose(price: number, prevClose: number): DerivedRate {
  if (prevClose <= 0) return { rate: 0, state: "EVEN" };
  const rel = (price - prevClose) / prevClose;
  if (rel > 0) return { rate: rel, state: "RISE" };
  if (rel < 0) return { rate: -rel, state: "FALL" };
  return { rate: 0, state: "EVEN" };
}
