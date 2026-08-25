/**
 * chartTheme.ts. tokens.css 해석값 → klinecharts 스타일 객체 매핑(설계 §4).
 * 하드코딩 hex 없음. 색은 전부 ChartTokens(런타임 getComputedStyle 해석값)에서 온다.
 * 상승 Red·하락 Blue, 지표선은 브랜드 명도 램프, 방향색·green 미사용(§4.2).
 */

import { CandleType, LineType, TooltipShowRule } from "klinecharts";
import type { DeepPartial, IndicatorStyle, Styles } from "klinecharts";
import { toNum } from "@/lib/format";

/** 지표별 색 오버라이드. 배열 원소까지 부분 지정하려면 DeepPartial이 필요하다 */
export type IndicatorStyleOverride = DeepPartial<IndicatorStyle>;

/** tokens.css에서 읽어오는 색·폰트 해석값. 값은 라이트·다크에서 토큰이 전환한다 */
export interface ChartTokens {
  up: string;
  down: string;
  flat: string;
  upText: string;
  downText: string;
  grid: string;
  axisText: string;
  axisLine: string;
  crosshairLine: string;
  surface: string;
  borderDefault: string;
  borderStrong: string;
  textDefault: string;
  textSecondary: string;
  textInverse: string;
  brand400: string;
  brand500: string;
  brand600: string;
  brand700: string;
  /* 지표선 계열 램프(--chart-series-1..4). 다크에서 밝은 단계로 미러돼 3:1을 지킨다(§4.2) */
  series1: string;
  series2: string;
  series3: string;
  series4: string;
  brandPrimary: string;
  font: string;
}

/** 거래량 막대 알파(35%). 방향 토큰 파생, 데이터 내장 색이라 테마 전이 시 재세팅 */
const VOL_ALPHA_HEX = "59";

/**
 * 방향색에 거래량 막대 알파를 입힌다.
 *
 * 토큰은 getComputedStyle 해석값이라 현재는 #RRGGBB로 오지만, 형식이 rgb()·oklch() 등으로 바뀌면
 * 접미 결합이 깨진 색 문자열을 만든다(무음 실패). 6자리 hex일 때만 접미하고, 아니면 color-mix로
 * 알파를 얹는다. color-mix도 못 쓰는 환경이면 불투명 원색으로 떨어뜨려 최소한 막대는 보이게 한다.
 */
function volumeBarColor(color: string): string {
  if (/^#[0-9a-fA-F]{6}$/.test(color)) return `${color}${VOL_ALPHA_HEX}`;
  return `color-mix(in srgb, ${color} 35%, transparent)`;
}

/**
 * klinecharts 전역 스타일 객체. setStyles·init options.styles로 주입.
 * reduced-motion: 마지막 가격 포인트 리플 애니메이션을 끈다(디자인시스템 §5 킬스위치 동형, §4.5).
 */
export function buildChartStyles(t: ChartTokens, reducedMotion = false): DeepPartial<Styles> {
  const dashed = { style: LineType.Dashed };
  return {
    grid: {
      show: true,
      horizontal: { color: t.grid },
      vertical: { color: t.grid },
    },
    candle: {
      type: CandleType.CandleSolid,
      // 마지막 가격 포인트 리플은 area 타입에서만 렌더되나 reduced-motion 킬스위치를 선언적으로 건다(§4.5)
      area: { point: { animation: !reducedMotion } },
      bar: {
        upColor: t.up,
        downColor: t.down,
        noChangeColor: t.flat,
        upBorderColor: t.up,
        downBorderColor: t.down,
        noChangeBorderColor: t.flat,
        upWickColor: t.up,
        downWickColor: t.down,
        noChangeWickColor: t.flat,
      },
      priceMark: {
        high: { color: t.axisText },
        low: { color: t.axisText },
        last: {
          show: true,
          upColor: t.up,
          downColor: t.down,
          noChangeColor: t.flat,
          line: { show: true, ...dashed },
          text: { color: t.textInverse },
        },
      },
      // 캔들 OHLCV 판독은 캔버스 툴팁 대신 ChartReadout 고정 행이 담당한다(겹침·소음 제거)
      tooltip: { showRule: TooltipShowRule.None },
    },
    indicator: {
      // 지표선 계열 구분은 테마 인지 램프(§4.2). primitive 직접 소비는 다크에서 600·700이
      // 3:1 미달이라 --chart-series-* 토큰(다크 미러 램프)으로 소비한다
      lines: [{ color: t.series1 }, { color: t.series2 }, { color: t.series3 }, { color: t.series4 }],
      // 히스토그램 up/down은 부호가 막대 위치에 내재해 색 단독이 아님(§4.2)
      bars: [{ upColor: volumeBarColor(t.up), downColor: volumeBarColor(t.down), noChangeColor: t.flat }],
      lastValueMark: { text: { color: t.textInverse } },
      tooltip: { text: { color: t.textSecondary, family: t.font }, showName: true, showParams: true },
    },
    xAxis: {
      axisLine: { color: t.axisLine },
      tickLine: { color: t.axisLine },
      tickText: { color: t.axisText, family: t.font },
    },
    yAxis: {
      axisLine: { color: t.axisLine },
      tickLine: { color: t.axisLine },
      tickText: { color: t.axisText, family: t.font },
    },
    crosshair: {
      horizontal: {
        line: { color: t.crosshairLine, ...dashed },
        text: { color: t.textDefault, backgroundColor: t.surface, borderColor: t.borderDefault, family: t.font },
      },
      vertical: {
        line: { color: t.crosshairLine, ...dashed },
        text: { color: t.textDefault, backgroundColor: t.surface, borderColor: t.borderDefault, family: t.font },
      },
    },
    separator: { color: t.grid },
    overlay: {
      // 그리기 객체는 방향색·green을 피한 브랜드 중립색(§3.4)
      point: { color: t.brandPrimary, borderColor: t.surface, activeColor: t.brandPrimary, activeBorderColor: t.surface },
      line: { color: t.brandPrimary },
      rect: { borderColor: t.brandPrimary, color: "transparent" },
      polygon: { color: t.brandPrimary },
      circle: { borderColor: t.brandPrimary, color: "transparent" },
      arc: { color: t.brandPrimary },
      text: { color: t.textDefault, family: t.font },
    },
  };
}

/** 볼린저: 상·하 밴드 border-strong, 중앙 brand-primary(§4.2) */
export function bollStyles(t: ChartTokens): IndicatorStyleOverride {
  return { lines: [{ color: t.borderStrong }, { color: t.brandPrimary }, { color: t.borderStrong }] };
}

/** MACD: DIF brand-primary, DEA text-secondary, 히스토그램 up/down(§4.2) */
export function macdStyles(t: ChartTokens): IndicatorStyleOverride {
  return {
    lines: [{ color: t.brandPrimary }, { color: t.textSecondary }],
    bars: [{ upColor: t.up, downColor: t.down, noChangeColor: t.flat }],
  };
}

/** 스토캐스틱(KDJ) %K brand-primary, %D text-secondary, J 보조 brand-400(§4.2) */
export function kdjStyles(t: ChartTokens): IndicatorStyleOverride {
  return { lines: [{ color: t.brandPrimary }, { color: t.textSecondary }, { color: t.brand400 }] };
}

/** RSI 단일선 brand-primary(§4.2) */
export function rsiStyles(t: ChartTokens): IndicatorStyleOverride {
  return { lines: [{ color: t.brandPrimary }] };
}

/** 거래량 막대는 방향 알파색, 이동평균선은 테마 인지 램프(다크 3:1 확보) */
export function volStyles(t: ChartTokens): IndicatorStyleOverride {
  return {
    bars: [{ upColor: volumeBarColor(t.up), downColor: volumeBarColor(t.down), noChangeColor: t.flat }],
    lines: [{ color: t.series1 }, { color: t.series2 }, { color: t.series3 }],
  };
}

/** 종가·시가 문자열 → 축 정밀도. 픽셀 스케일 계산 전용(§3.6, toNum 경유) */
export function precisionOf(closeStr: string): number {
  const n = Math.abs(toNum(closeStr));
  if (n >= 1000) return 0;
  if (n >= 100) return 1;
  return 2;
}
