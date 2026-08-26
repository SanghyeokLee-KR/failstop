"use client";

/**
 * 거래 탭 라이브 제목. 멀티 탭으로 여러 마켓을 감시하는 트레이더 관례:
 * 탭 제목만으로 심볼·현재가·등락이 읽혀야 한다. 초당 1회로 스로틀해 제목 깜빡임을 막는다.
 */

import { useEffect, useRef } from "react";
import { useExchangeStore } from "@/lib/store";
import { formatPrice, toNum } from "@/lib/format";

const UPDATE_MS = 1000;

export default function LiveTitle({ market }: { market: string }) {
  const ticker = useExchangeStore((s) => s.tickers[market]);
  const spec = useExchangeStore((s) => s.markets.find((m) => m.market === market));
  const lastAt = useRef(0);

  useEffect(() => {
    if (!ticker?.trade_price) return;
    const now = Date.now();
    if (now - lastAt.current < UPDATE_MS) return;
    lastAt.current = now;
    const symbol = market.split("-")[1] ?? market;
    const arrow = ticker.change === "RISE" ? "▲" : ticker.change === "FALL" ? "▼" : "";
    const sign = ticker.change === "RISE" ? "+" : ticker.change === "FALL" ? "-" : "";
    const rate = (toNum(ticker.change_rate ?? "0") * 100).toFixed(2);
    document.title = `${symbol} ${formatPrice(ticker.trade_price, spec?.tick_size)} ${arrow}${sign}${rate}% · 페일스톱`;
  }, [ticker, market, spec?.tick_size]);

  // 이탈 시 제목 복원을 두지 않는다. 패시브 cleanup이 라우터의 metadata 교체보다 늦게 돌아
  // 목적지 제목(홈·토론 등)을 기본값으로 덮어쓴다. 복원은 라우터와 usePageTitle이 이미 한다

  return null;
}
