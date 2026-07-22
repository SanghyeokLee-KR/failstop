"use client";

/**
 * ChartReadout. 크로스헤어 위치(없으면 최신 봉)의 시·고·저·종·거래량 인라인 판독 1행.
 * 캔버스 내장 툴팁 대신 고정 행으로 노출해 지표 범례와의 겹침·시각 소음을 없앤다.
 * 고정 min-height + tabular-nums로 값 갱신 시 레이아웃 시프트 0을 지킨다.
 * 형성봉 300ms 스트림의 시각 전용 싱크다. 낭독은 TradingChart status 리전이 롤오버·크로스헤어로
 * 별도 게이팅하므로 이 행은 aria-hidden으로 두어 SR 낭독 큐 폭주를 막는다.
 */

import { memo } from "react";
import { formatPrice, formatQty } from "@/lib/format";
import type { LiveCandle } from "./candleStream";
import styles from "./chart.module.css";

function ChartReadout({ candle }: { candle: LiveCandle | null }) {
  const items: { label: string; value: string }[] = [
    { label: "시", value: candle ? formatPrice(candle.open) : "-" },
    { label: "고", value: candle ? formatPrice(candle.high) : "-" },
    { label: "저", value: candle ? formatPrice(candle.low) : "-" },
    { label: "종", value: candle ? formatPrice(candle.close) : "-" },
    { label: "거래량", value: candle ? formatQty(candle.volume, 4) : "-" },
  ];

  const diff = candle ? candle.close - candle.open : 0;
  const dir = diff > 0 ? "up" : diff < 0 ? "down" : "flat";
  const pct = candle && candle.open !== 0 ? ((Math.abs(diff) / candle.open) * 100).toFixed(2) : "0.00";
  const change =
    dir === "up"
      ? `▲ +${formatPrice(diff)} (+${pct}%)`
      : dir === "down"
        ? `▼ −${formatPrice(-diff)} (−${pct}%)`
        : "0 (0.00%)";

  return (
    <div className={styles.readoutRow} aria-hidden="true">
      {items.map((it) => (
        <span key={it.label} className={styles.readoutItem}>
          <span className={styles.readoutLabel}>{it.label}</span>
          <span className={`${styles.readoutValue} num`}>{it.value}</span>
        </span>
      ))}
      <span className={`${styles.readoutChange} num dir-text`} data-dir={dir}>
        {candle ? change : "-"}
      </span>
    </div>
  );
}

export default memo(ChartReadout);
