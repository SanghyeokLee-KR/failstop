"use client";

/**
 * ChartToolbar. 타임프레임 탭(SegmentedTab 재사용) + 지표 토글 + 그리기 도구 + 초기화.
 * 상단 1행. 지표 메뉴는 네이티브 details로 키보드·포커스 접근을 확보한다.
 */

import { memo } from "react";
import SegmentedTab from "@/components/SegmentedTab";
import type { CandleUnit } from "@/lib/types";
import { formatPrice } from "@/lib/format";
import { OVERLAY_INDICATORS, SUB_INDICATORS, type IndicatorKey } from "./indicators";
import { DRAWING_TOOLS } from "./drawingTools";
import type { LiveCandle } from "./candleStream";
import styles from "./chart.module.css";

const TIMEFRAMES: { value: CandleUnit; label: string }[] = [
  { value: "1m", label: "1분" },
  { value: "5m", label: "5분" },
  { value: "15m", label: "15분" },
  { value: "60m", label: "1시간" },
  { value: "240m", label: "4시간" },
  { value: "1d", label: "1일" },
];

interface Props {
  unit: CandleUnit;
  onUnit: (unit: CandleUnit) => void;
  active: Set<IndicatorKey>;
  onToggleIndicator: (key: IndicatorKey) => void;
  onDraw: (overlay: string) => void;
  onClearDrawings: () => void;
  latest: LiveCandle | null;
}

function ChartToolbar({ unit, onUnit, active, onToggleIndicator, onDraw, onClearDrawings, latest }: Props) {
  const dir = latest ? (latest.close > latest.open ? "up" : latest.close < latest.open ? "down" : "flat") : "flat";
  const diff = latest ? latest.close - latest.open : 0;
  const readoutText = dir === "up" ? `▲ +${formatPrice(diff)}` : dir === "down" ? `▼ −${formatPrice(-diff)}` : "0";

  return (
    <div className={styles.toolbar}>
      <div className={styles.tabs}>
        <SegmentedTab items={TIMEFRAMES} value={unit} onChange={onUnit} ariaLabel="차트 주기" />
      </div>

      {latest && (
        <span className={styles.readout}>
          <span className={`${styles.readoutPrice} num`}>{formatPrice(latest.close)}</span>
          <span className="dir-text num" data-dir={dir}>
            {readoutText}
          </span>
        </span>
      )}

      <div className={styles.controls}>
        <details className={styles.menu}>
          <summary aria-label="지표 선택">지표</summary>
          <div className={styles.menuPanel} role="group" aria-label="지표 토글">
            <p className={styles.menuGroupLabel}>가격 오버레이</p>
            {OVERLAY_INDICATORS.map((spec) => (
              <label key={spec.key} className={styles.menuItem}>
                <input type="checkbox" checked={active.has(spec.key)} onChange={() => onToggleIndicator(spec.key)} />
                {spec.label}
              </label>
            ))}
            <p className={styles.menuGroupLabel}>보조 지표</p>
            {SUB_INDICATORS.map((spec) => (
              <label key={spec.key} className={styles.menuItem}>
                <input type="checkbox" checked={active.has(spec.key)} onChange={() => onToggleIndicator(spec.key)} />
                {spec.label}
              </label>
            ))}
          </div>
        </details>

        {DRAWING_TOOLS.map((tool) => (
          <button key={tool.key} type="button" className={styles.toolBtn} onClick={() => onDraw(tool.overlay)} aria-label={`${tool.label} 그리기`}>
            {tool.label}
          </button>
        ))}
        <button type="button" className={styles.toolBtn} onClick={onClearDrawings} aria-label="그리기 전체 지우기">
          지우기
        </button>
      </div>
    </div>
  );
}

export default memo(ChartToolbar);
