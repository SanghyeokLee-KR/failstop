"use client";

/**
 * 고정 min-height + tabular-nums로 값 갱신 시 레이아웃 시프트 0을 지킨다.
 * 낭독은 TradingChart status 리전이 전담하므로 이 행은 aria-hidden으로 둬 SR 낭독 큐 폭주를 막는다.
 */

import { memo } from "react";
import { formatPrice } from "@/lib/format";
import type { LiveCandle } from "./candleStream";
import styles from "./chart.module.css";

function ChartReadout({ candle }: { candle: LiveCandle | null }) {
  // 거래량은 바로 아래 볼륨 pane 범례가 이미 같은 값을 표시한다. 1280에서 이 행이
  // 등락 숫자를 말줄임 없이 자르던 원인이라 중복 항목을 걷어 약 110px를 회수한다
  const items: { label: string; value: string }[] = [
    { label: "시", value: candle ? formatPrice(candle.open) : "-" },
    { label: "고", value: candle ? formatPrice(candle.high) : "-" },
    { label: "저", value: candle ? formatPrice(candle.low) : "-" },
    { label: "종", value: candle ? formatPrice(candle.close) : "-" },
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
