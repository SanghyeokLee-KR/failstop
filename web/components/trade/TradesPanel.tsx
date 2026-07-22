"use client";

/**
 * TradesPanel: 최근 체결 4열 테이블 (§2.5).
 * 체결가(방향색) · 체결량 · 등락률(전일종가 역산) · 시각, sticky 헤더 + 내부 스크롤.
 * 데이터: 스토어 trades[market](MAX 60) + REST 백필.
 */

import { useEffect, useMemo, useState } from "react";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import { useExchangeStore } from "@/lib/store";
import { getDataSource } from "@/lib/api/datasource";
import type { Side, Trade } from "@/lib/types";
import { dirOf, formatChangeRate, formatPrice, formatQty, formatTimeHMS, toNum } from "@/lib/format";
import { prevCloseOf, rateVsPrevClose } from "./derive";
import styles from "./TradesPanel.module.css";

const MAX_ROWS = 60;

interface Row {
  id: string;
  price: number;
  volume: string;
  side: Side;
  timestamp: string;
}

export default function TradesPanel({ market }: { market: string }) {
  const liveTrades = useExchangeStore((s) => s.trades[market]);
  const ticker = useExchangeStore((s) => s.tickers[market]);
  const [backfill, setBackfill] = useState<Trade[] | null>(null);

  useEffect(() => {
    let alive = true;
    setBackfill(null);
    getDataSource()
      .getTrades(market, MAX_ROWS)
      .then((t) => {
        if (alive) setBackfill(t);
      });
    return () => {
      alive = false;
    };
  }, [market]);

  const rows: Row[] = useMemo(() => {
    const seen = new Set<string>();
    const out: Row[] = [];
    const push = (id: string, price: string, volume: string, side: Side, timestamp: string) => {
      if (seen.has(id)) return;
      seen.add(id);
      out.push({ id, price: toNum(price), volume, side, timestamp });
    };
    (liveTrades ?? []).forEach((t) => push(t.trade_id, t.price, t.volume, t.taker_side, t.timestamp));
    (backfill ?? []).forEach((t) => push(t.trade_id, t.price, t.volume, t.taker_side, t.timestamp));
    return out.slice(0, MAX_ROWS);
  }, [liveTrades, backfill]);

  const prevClose = prevCloseOf(ticker);

  return (
    <PanelCard dense scroll>
      {backfill === null && rows.length === 0 ? (
        <Skeleton variant="card" width="100%" height="100%" />
      ) : rows.length === 0 ? (
        <div className="empty-state">
          <span className="empty-state__title">아직 체결 내역이 없어요</span>
        </div>
      ) : (
        <table className={`${styles.table} num`}>
          <thead>
            <tr>
              <th scope="col">체결가</th>
              <th scope="col">체결량</th>
              <th scope="col">등락률</th>
              <th scope="col">시각</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => {
              const { rate, state } = rateVsPrevClose(t.price, prevClose);
              const d = dirOf(state);
              const sideDir = t.side === "bid" ? "up" : "down";
              return (
                <tr key={t.id}>
                  <td>
                    <span
                      className="dir-text"
                      data-dir={sideDir}
                      aria-label={`${t.side === "bid" ? "매수" : "매도"} 체결 ${formatPrice(t.price)}원`}
                    >
                      {t.side === "bid" ? "▲" : "▼"} {formatPrice(t.price)}
                    </span>
                  </td>
                  <td className={styles.qty}>{formatQty(t.volume, 4)}</td>
                  <td>
                    <span className="dir-text" data-dir={d}>
                      {formatChangeRate(rate, state)}
                    </span>
                  </td>
                  <td className={styles.time}>{formatTimeHMS(t.timestamp)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </PanelCard>
  );
}
