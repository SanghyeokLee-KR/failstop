"use client";

/**
 * useChartTheme.ts. tokens.css 해석 + 테마 전환 감지(설계 §4.3).
 * [data-theme] 속성 변경(명시 토글)과 prefers-color-scheme(시스템)을 모두 관찰해
 * 콜백을 호출한다. CandleChart.tsx의 MutationObserver + matchMedia 패턴을 옮긴 것.
 */

import { useEffect } from "react";
import type { ChartTokens } from "./chartTheme";

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** 현재 테마의 토큰 해석값. 차트 init·테마 전환 재주입에서 소비 */
export function readChartTokens(): ChartTokens {
  return {
    up: cssVar("--color-up"),
    down: cssVar("--color-down"),
    flat: cssVar("--color-flat"),
    upText: cssVar("--color-up-text"),
    downText: cssVar("--color-down-text"),
    grid: cssVar("--border-subtle"),
    axisText: cssVar("--text-secondary"),
    axisLine: cssVar("--border-subtle"),
    crosshairLine: cssVar("--text-secondary"),
    surface: cssVar("--surface-default"),
    borderDefault: cssVar("--border-default"),
    borderStrong: cssVar("--border-strong"),
    textDefault: cssVar("--text-default"),
    textSecondary: cssVar("--text-secondary"),
    textInverse: cssVar("--text-inverse"),
    brand400: cssVar("--brand-400"),
    brand500: cssVar("--brand-500"),
    brand600: cssVar("--brand-600"),
    brand700: cssVar("--brand-700"),
    series1: cssVar("--chart-series-1"),
    series2: cssVar("--chart-series-2"),
    series3: cssVar("--chart-series-3"),
    series4: cssVar("--chart-series-4"),
    brandPrimary: cssVar("--brand-primary"),
    font: cssVar("--font-sans"),
  };
}

/** 테마 전환 시 onChange 호출. 명시 토글 + 시스템 스킴 둘 다 관찰 */
export function useChartTheme(onChange: () => void): void {
  useEffect(() => {
    const observer = new MutationObserver(onChange);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    mq.addEventListener("change", onChange);
    return () => {
      observer.disconnect();
      mq.removeEventListener("change", onChange);
    };
  }, [onChange]);
}
