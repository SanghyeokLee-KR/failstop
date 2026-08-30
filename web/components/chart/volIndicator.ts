/**
 * 내장 VOL은 막대를 gapBar-1로 그려 캔들 몸통보다 1px 좁고, 막대 폭은 스타일로 노출되지 않는다.
 * draw 콜백에서 캔들과 같은 기하로 직접 그리되, calc는 내장과 같은 누적합을 유지한다.
 */

import { IndicatorSeries, registerIndicator, type IndicatorFigureStylesCallbackData, type KLineData } from "klinecharts";

interface VolData {
  volume?: number;
  ma1?: number;
  ma2?: number;
  ma3?: number;
  [key: string]: number | undefined;
}

const MA_KEYS = ["ma1", "ma2", "ma3"] as const;

/** 막대 색. 내장과 같은 규칙으로 종가와 시가를 비교한다 */
function barColor(k: KLineData | undefined, styles: Record<string, unknown> | null | undefined,
                  fallback: { upColor: string; downColor: string; noChangeColor: string }): string {
  const bars = (styles?.bars as Array<Partial<typeof fallback>> | undefined)?.[0];
  const up = bars?.upColor ?? fallback.upColor;
  const down = bars?.downColor ?? fallback.downColor;
  const flat = bars?.noChangeColor ?? fallback.noChangeColor;
  if (!k) return flat;
  if (k.close > k.open) return up;
  if (k.close < k.open) return down;
  return flat;
}

export function registerMatchedVolume(): void {
  registerIndicator<VolData>({
    name: "VOL",
    shortName: "VOL",
    series: IndicatorSeries.Volume,
    calcParams: [5, 10, 20],
    shouldFormatBigNumber: true,
    precision: 0,
    minValue: 0,
    figures: [
      { key: "ma1", title: "MA5: ", type: "line" },
      { key: "ma2", title: "MA10: ", type: "line" },
      { key: "ma3", title: "MA20: ", type: "line" },
      { key: "volume", title: "VOLUME: ", type: "bar", baseValue: 0 },
    ],
    regenerateFigures: (params) => {
      const figures = params.map((p: number, i: number) => (
        { key: `ma${i + 1}`, title: `MA${p}: `, type: "line" as const }
      ));
      figures.push({ key: "volume", title: "VOLUME: ", type: "bar" as const, baseValue: 0 } as never);
      return figures as never;
    },
    calc: (dataList, indicator) => {
      const params = indicator.calcParams as number[];
      const figures = indicator.figures;
      const sums: number[] = [];
      return dataList.map((k, i) => {
        const volume = k.volume ?? 0;
        const out: VolData = { volume };
        params.forEach((p, index) => {
          sums[index] = (sums[index] ?? 0) + volume;
          if (i >= p - 1) {
            out[figures[index].key] = sums[index] / p;
            sums[index] -= dataList[i - (p - 1)].volume ?? 0;
          }
        });
        return out;
      });
    },
    draw: ({ ctx, kLineDataList, indicator, visibleRange, barSpace, defaultStyles, xAxis, yAxis }) => {
      const result = indicator.result ?? [];
      const { from, to } = visibleRange;
      const fallbackBar = defaultStyles.bars[0];
      const baseY = yAxis.convertToPixel(0);

      // 막대. 캔들 몸통과 동일한 기하를 쓴다(내장 렌더러의 halfGapBar*2 대신 gapBar).
      for (let i = from; i < to; i++) {
        const value = result[i]?.volume;
        if (typeof value !== "number" || !Number.isFinite(value)) continue;
        const x = xAxis.convertToPixel(i);
        const top = yAxis.convertToPixel(value);
        const height = Math.max(1, Math.abs(baseY - top));
        ctx.fillStyle = barColor(
          kLineDataList[i],
          indicator.styles as Record<string, unknown> | null,
          fallbackBar as { upColor: string; downColor: string; noChangeColor: string },
        );
        ctx.fillRect(x - barSpace.halfGapBar, Math.min(top, baseY), barSpace.gapBar, height);
      }

      // MA 선. 기본 렌더링을 대체했으므로 여기서 함께 그린다.
      MA_KEYS.forEach((key, idx) => {
        const styleLine = (indicator.styles?.lines ?? [])[idx] ?? defaultStyles.lines[idx];
        ctx.beginPath();
        // 스타일 결측 폴백도 토큰 잉크를 따른다. 하드코딩 hex는 테마 전환에서 이물이 된다
        ctx.strokeStyle = String(styleLine?.color ?? defaultStyles.lines[idx]?.color ?? "currentColor");
        ctx.lineWidth = Number(styleLine?.size ?? 1);
        let started = false;
        for (let i = from; i < to; i++) {
          const value = result[i]?.[key];
          if (typeof value !== "number" || !Number.isFinite(value)) { started = false; continue; }
          const x = xAxis.convertToPixel(i);
          const y = yAxis.convertToPixel(value);
          if (started) ctx.lineTo(x, y);
          else { ctx.moveTo(x, y); started = true; }
        }
        ctx.stroke();
      });

      // true를 반환해 기본 도형 렌더링을 대체한다. 범례는 별도 뷰라 영향받지 않는다.
      return true;
    },
  });
}

export type { IndicatorFigureStylesCallbackData };
