"use client";

/**
 * ChartPanel: chart 영역 래퍼 (§2.5).
 * 주기 탭(1분~1주, SegmentedTab) + CandleChart(height 100%로 1fr 행 채움).
 */

import { useState } from "react";
import dynamic from "next/dynamic";
import PanelCard from "@/components/PanelCard";
import SegmentedTab from "@/components/SegmentedTab";
import Skeleton from "@/components/Skeleton";
import type { CandleUnit } from "@/lib/types";
import styles from "./ChartPanel.module.css";

const CandleChart = dynamic(() => import("@/components/CandleChart"), {
  ssr: false,
  loading: () => <Skeleton variant="chart" width="100%" height="100%" />,
});

const TIMEFRAMES = [
  { value: "1m" as CandleUnit, label: "1분" },
  { value: "5m" as CandleUnit, label: "5분" },
  { value: "15m" as CandleUnit, label: "15분" },
  { value: "60m" as CandleUnit, label: "1시간" },
  { value: "1d" as CandleUnit, label: "1일" },
  { value: "1w" as CandleUnit, label: "1주" },
];

export default function ChartPanel({ market }: { market: string }) {
  const [unit, setUnit] = useState<CandleUnit>("15m");

  return (
    <PanelCard>
      <div className={styles.body}>
        <div className={styles.toolbar}>
          <SegmentedTab items={TIMEFRAMES} value={unit} onChange={setUnit} ariaLabel="차트 주기" />
        </div>
        <div className={styles.chartArea}>
          <CandleChart market={market} unit={unit} height="100%" />
        </div>
      </div>
    </PanelCard>
  );
}
