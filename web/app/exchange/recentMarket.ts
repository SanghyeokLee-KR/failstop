// 최근 마켓 기억. /exchange 진입 시 복원 (§1.2)

import { COIN_META } from "@/lib/sim/engine";

const KEY = "exchange.recentMarket";
const DEFAULT_MARKET = "KRW-BTC";

export function readRecentMarket(): string {
  try {
    const v = localStorage.getItem(KEY);
    // 마켓 개편으로 저장값이 무효해질 수 있어 존재 검증 후 사용
    if (v && COIN_META.some((c) => c.market === v)) return v;
  } catch {
    /* storage 접근 실패 시 기본값 */
  }
  return DEFAULT_MARKET;
}

export function writeRecentMarket(market: string): void {
  try {
    localStorage.setItem(KEY, market);
  } catch {
    /* no-op */
  }
}
