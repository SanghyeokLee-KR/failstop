"use client";

/**
 * ChartToolbar. 타임프레임 탭(SegmentedTab 재사용) + 지표 토글 + 그리기 도구 + 초기화.
 * 상단 1행. 지표 메뉴는 네이티브 details로 키보드·포커스 접근을 확보한다.
 * 그리기 버튼은 아이콘 전용(44px), 라벨은 title 툴팁 + aria-label로 제공한다(1280px 1행 예산).
 */

import { memo, type ReactNode } from "react";
import SegmentedTab from "@/components/SegmentedTab";
import type { CandleUnit } from "@/lib/types";
import { OVERLAY_INDICATORS, SUB_INDICATORS, type IndicatorKey } from "./indicators";
import { DRAWING_TOOLS } from "./drawingTools";
import styles from "./chart.module.css";

/* 서버 UNIT_SECONDS 가 받는 10종 중 UI 가 6종만 내고 있었다. 남은 넷(1s·3m·30m·1w)은
   서버·DB·프론트 타입에 이미 다 들어 있어 노출만 하면 되고 서버 변경이 0이다.
   집합의 근거는 실물 거래소다. 업비트가 초·1·3·5·10·15·30분·1·4시간·일·주·월·년을 내고
   그중 초는 1초 하나뿐이다. 빗썸은 초봉이 없고, 국내 증권사 HTS 는 초 대신 틱을 쓴다.
   우리는 암호화폐 거래소이므로 업비트 쪽 관행을 따르되 서버가 받는 것만 낸다.
   10분·월·년은 서버가 안 받으므로 이번 범위 밖이다. */
const TIMEFRAMES: { value: CandleUnit; label: string }[] = [
  { value: "1s", label: "1초" },
  { value: "1m", label: "1분" },
  { value: "3m", label: "3분" },
  { value: "5m", label: "5분" },
  { value: "15m", label: "15분" },
  { value: "30m", label: "30분" },
  { value: "60m", label: "1시간" },
  { value: "240m", label: "4시간" },
  { value: "1d", label: "1일" },
  { value: "1w", label: "1주" },
];

/* 아이콘은 장식이라 stroke를 currentColor로 물려받는다 */
const TOOL_ICONS: Record<string, ReactNode> = {
  segment: (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M5 19L19 5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <circle cx="5" cy="19" r="2.2" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="19" cy="5" r="2.2" fill="none" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  ),
  priceLine: (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M3 12h18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <circle cx="12" cy="12" r="2.2" fill="none" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  ),
  fibonacciLine: (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <path
        d="M4 5h16M4 10h16M4 15h16M4 20h16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
    </svg>
  ),
};

const CLEAR_ICON = (
  <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
    <path
      d="M9.5 19l-5-5a1.6 1.6 0 010-2.3L12 4.3a1.6 1.6 0 012.3 0l5.4 5.4a1.6 1.6 0 010 2.3L12.6 19M9.5 19h10M9.5 19l-2.8-2.8"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

interface Props {
  unit: CandleUnit;
  onUnit: (unit: CandleUnit) => void;
  active: Set<IndicatorKey>;
  onToggleIndicator: (key: IndicatorKey) => void;
  onDraw: (overlay: string) => void;
  onClearDrawings: () => void;
}

function ChartToolbar({ unit, onUnit, active, onToggleIndicator, onDraw, onClearDrawings }: Props) {
  return (
    <div className={styles.toolbar}>
      <div className={styles.tabs}>
        <SegmentedTab items={TIMEFRAMES} value={unit} onChange={onUnit} ariaLabel="차트 주기" />
      </div>

      <div className={styles.controls}>
        <details className={styles.menu}>
          <summary aria-label={`지표 선택, ${active.size}개 사용 중`}>
            지표
            {active.size > 0 && <span className={`${styles.menuCount} num`}>{active.size}</span>}
          </summary>
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

        <span className={styles.dividerV} aria-hidden="true" />

        {DRAWING_TOOLS.map((tool) => (
          <button
            key={tool.key}
            type="button"
            className={styles.iconBtn}
            onClick={() => onDraw(tool.overlay)}
            title={`${tool.label} 그리기`}
            aria-label={`${tool.label} 그리기`}
          >
            {TOOL_ICONS[tool.key]}
          </button>
        ))}

        <span className={styles.dividerV} aria-hidden="true" />

        <button
          type="button"
          className={styles.iconBtn}
          onClick={onClearDrawings}
          title="그리기 전체 지우기"
          aria-label="그리기 전체 지우기"
        >
          {CLEAR_ICON}
        </button>
      </div>
    </div>
  );
}

export default memo(ChartToolbar);
