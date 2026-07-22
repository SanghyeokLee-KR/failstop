/**
 * indicators.ts. 지표 레지스트리(설계 §3.3, §4.2).
 * 오버레이(메인 pane): MA·EMA·볼린저. 보조 pane: MACD·RSI·스토캐스틱.
 * 계산은 klinecharts 내장을 쓰고 우리가 재구현하지 않는다. 색만 토큰으로 바인딩한다.
 */

import type { ChartTokens, IndicatorStyleOverride } from "./chartTheme";
import { bollStyles, kdjStyles, macdStyles, rsiStyles } from "./chartTheme";

export type IndicatorKey = "MA" | "EMA" | "BOLL" | "MACD" | "RSI" | "KDJ";

export interface IndicatorSpec {
  key: IndicatorKey;
  /** klinecharts 내장 지표 이름 */
  name: string;
  label: string;
  /** main=가격 오버레이(캔들 pane에 겹침), sub=보조 pane 별도 적재 */
  placement: "main" | "sub";
  /** 기본 계산 파라미터 재정의. 없으면 라이브러리 기본값 */
  calcParams?: number[];
  /** 토큰 기반 색 오버라이드. 없으면 전역 브랜드 램프 사용 */
  styles?: (t: ChartTokens) => IndicatorStyleOverride;
}

export const OVERLAY_INDICATORS: IndicatorSpec[] = [
  { key: "MA", name: "MA", label: "이동평균", placement: "main" },
  { key: "EMA", name: "EMA", label: "지수이평", placement: "main" },
  { key: "BOLL", name: "BOLL", label: "볼린저", placement: "main", styles: bollStyles },
];

export const SUB_INDICATORS: IndicatorSpec[] = [
  { key: "MACD", name: "MACD", label: "MACD", placement: "sub", styles: macdStyles },
  { key: "RSI", name: "RSI", label: "RSI", placement: "sub", calcParams: [14], styles: rsiStyles },
  { key: "KDJ", name: "KDJ", label: "스토캐스틱", placement: "sub", styles: kdjStyles },
];

export const ALL_INDICATORS: IndicatorSpec[] = [...OVERLAY_INDICATORS, ...SUB_INDICATORS];

/** 기본 활성 지표. 빈 차트가 아닌 최소 구성으로 MA만 켠다 */
export const DEFAULT_ACTIVE: IndicatorKey[] = ["MA"];

export function specOf(key: IndicatorKey): IndicatorSpec | undefined {
  return ALL_INDICATORS.find((s) => s.key === key);
}
