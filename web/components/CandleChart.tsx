"use client";

/**
 * A-8. ChartPanel(candle): lightweight-charts v5.
 * 규약(§7 A-8): 라이브러리 기본값(up=green) 금지 → getComputedStyle로 토큰(--color-up/--color-down) 주입,
 * 테마 변경 시 applyOptions 재적용. 양봉=Red / 음봉=Blue (한국 관례, §0-1).
 * 실시간: 시뮬레이터 trade 채널을 구독해 마지막 캔들을 series.update로 갱신.
 */

import { useEffect, useRef, useState } from "react";
import { newTradeCursor, isDuplicateTrade, advanceTradeCursor } from "./chart/tradeDedup";
import {
  CandlestickSeries,
  ColorType,
  HistogramSeries,
  createChart,
  type CandlestickData,
  type HistogramData,
  type UTCTimestamp,
} from "lightweight-charts";
import type { CandleUnit } from "@/lib/types";
import { dest } from "@/lib/types";
import { getDataSource } from "@/lib/api/datasource";
import { CANDLE_UNIT_MS } from "@/lib/sim/engine";
import { formatPrice, toNum } from "@/lib/format";
import Skeleton from "./Skeleton";

interface Props {
  market: string;
  unit: CandleUnit;
  /** number=px, string은 그대로 적용. 래퍼가 높이를 갖는 경우 "100%" 사용 (§5.1) */
  height?: number | string;
}

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export default function CandleChart({ market, unit, height = 264 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [loading, setLoading] = useState(true);
  const [empty, setEmpty] = useState(false);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    setLoading(true);
    setEmpty(false);
    let disposed = false;
    // cleanup이 in-flight 초기 로드를 실제 취소한다(결과 폐기만으로는 요청이 큐에 적체)
    const loadAbort = new AbortController();

    const readTokens = () => ({
      up: cssVar("--color-up"),
      down: cssVar("--color-down"),
      text: cssVar("--text-secondary"),
      grid: cssVar("--border-subtle"),
      font: cssVar("--font-sans"),
    });
    let t = readTokens();
    /* 거래량 막대 = 방향 토큰 파생(35% 알파). 데이터 내장 색이라 테마 전이 시 재세팅 */
    const volColor = (open: number, close: number) => `${close >= open ? t.up : t.down}59`;

    const chart = createChart(el, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: t.text,
        fontFamily: t.font,
        attributionLogo: false,
      },
      grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
      rightPriceScale: { borderColor: t.grid, scaleMargins: { top: 0.08, bottom: 0.24 } },
      timeScale: { borderColor: t.grid, timeVisible: true, secondsVisible: false },
      // 저가 코인(1,000원 미만) 축·라벨이 정수 반올림으로 "0"이 되지 않도록 구간별 소수 규칙 사용
      localization: { locale: "ko-KR", priceFormatter: (p: number) => formatPrice(p) },
    });

    const candleSeries = chart.addSeries(CandlestickSeries, {
      upColor: t.up,
      downColor: t.down,
      wickUpColor: t.up,
      wickDownColor: t.down,
      borderVisible: false,
    });
    const volumeSeries = chart.addSeries(HistogramSeries, {
      priceScaleId: "vol",
      priceFormat: { type: "volume" },
      lastValueVisible: false,
      priceLineVisible: false,
    });
    chart.priceScale("vol").applyOptions({ scaleMargins: { top: 0.84, bottom: 0 } });

    /* UTCTimestamp는 UTC 기준 표시 → 로컬 시각 표시를 위한 관례적 시프트 */
    const tzOffsetSec = -new Date().getTimezoneOffset() * 60;
    const toChartTime = (ms: number) => (Math.floor(ms / 1000) + tzOffsetSec) as UTCTimestamp;

    const unitMs = CANDLE_UNIT_MS[unit];
    interface LiveCandle {
      bucketMs: number;
      open: number;
      high: number;
      low: number;
      close: number;
      volume: number;
    }
    let last: LiveCandle | null = null;
    let candleData: CandlestickData<UTCTimestamp>[] = [];
    // 거래량 원본(시각·값·방향 판정용 open/close). 데이터 내장 색은 setData 시점 토큰 값으로
    // 굳어 applyOptions로는 안 바뀌므로, 테마 전이 시 색을 재계산해 setData로 재주입한다
    interface VolPoint {
      time: UTCTimestamp;
      value: number;
      open: number;
      close: number;
    }
    let volPoints: VolPoint[] = [];
    const volBar = (p: VolPoint): HistogramData<UTCTimestamp> => ({
      time: p.time,
      value: p.value,
      color: volColor(p.open, p.close),
    });

    getDataSource()
      .getCandles(market, unit, 150, loadAbort.signal)
      .then((rows) => {
        if (disposed) return;
        // 백엔드 미구현·일시 오류(어댑터가 []로 흡수)·빈 응답이면 빈 상태를 그린다.
        // 캔들이 도착하면 정상 렌더로 전환된다.
        if (rows.length === 0) {
          setEmpty(true);
          setLoading(false);
          return;
        }
        // 계약은 시각 내림차순 → 차트는 오름차순. 0·NaN OHLC(결측·미형성 버킷)는 축·색 오염을 막게 배제
        const asc = [...rows].reverse().filter((c) => {
          const o = toNum(c.open);
          const h = toNum(c.high);
          const l = toNum(c.low);
          const cl = toNum(c.close);
          return o > 0 && h > 0 && l > 0 && cl > 0;
        });
        if (asc.length === 0) {
          setEmpty(true);
          setLoading(false);
          return;
        }
        candleData = asc.map((c) => ({
          time: toChartTime(Date.parse(c.candle_time)),
          open: toNum(c.open),
          high: toNum(c.high),
          low: toNum(c.low),
          close: toNum(c.close),
        }));
        volPoints = asc.map((c) => ({
          time: toChartTime(Date.parse(c.candle_time)),
          value: toNum(c.volume),
          open: toNum(c.open),
          close: toNum(c.close),
        }));
        candleSeries.setData(candleData);
        volumeSeries.setData(volPoints.map(volBar));
        const latest = asc[asc.length - 1];
        if (latest) {
          last = {
            bucketMs: Date.parse(latest.candle_time),
            open: toNum(latest.open),
            high: toNum(latest.high),
            low: toNum(latest.low),
            close: toNum(latest.close),
            volume: toNum(latest.volume),
          };
        }
        chart.timeScale().setVisibleLogicalRange({ from: candleData.length - 56, to: candleData.length + 3 });
        setLoading(false);
      })
      .catch((e) => {
        // 어댑터가 흡수하지 못한 실패(네트워크 등)도 에러 표면화 없이 빈 상태로 수렴한다.
        // 조용한 수렴이 진단을 가리지 않도록 흡수 지점에 경고를 남긴다(UX는 빈 상태 유지).
        if (disposed) return;
        console.warn(`[chart] ${market} ${unit} 캔들 로드 실패. 빈 상태 렌더`, e);
        setEmpty(true);
        setLoading(false);
      });

    /* 실시간 체결 → 마지막 캔들 갱신 / 버킷 전환 시 신규 캔들.
       재연결·재동기화 재전달을 (epoch,seq) 커서로 걸러 volume 이중계상 차단(candleStream과 동일 경로) */
    const tradeCursor = newTradeCursor();
    const unsubscribe = getDataSource().subscribe(dest.trade(market), (msg) => {
      if (disposed || msg.type !== "trade" || !last) return;
      if (isDuplicateTrade(tradeCursor, msg)) return;
      advanceTradeCursor(tradeCursor, msg);
      const price = toNum(msg.price);
      // 결측·이상 체결가(0·NaN)는 close=0 캔들을 만들어 축을 오염시킨다. 합성에서 배제
      if (!(price > 0)) return;
      const vol = Math.max(0, toNum(msg.volume));
      const bucketMs = Math.floor(Date.parse(msg.timestamp) / unitMs) * unitMs;
      if (bucketMs <= last.bucketMs) {
        last.high = Math.max(last.high, price);
        last.low = Math.min(last.low, price);
        last.close = price;
        last.volume += vol;
      } else {
        last = {
          bucketMs,
          open: last.close,
          high: Math.max(last.close, price),
          low: Math.min(last.close, price),
          close: price,
          volume: vol,
        };
      }
      const time = toChartTime(last.bucketMs);
      candleSeries.update({ time, open: last.open, high: last.high, low: last.low, close: last.close });
      // 원본 배열도 함께 갱신해 테마 전이 재주입이 최신 막대까지 새 색으로 다시 그린다
      const point: VolPoint = { time, value: last.volume, open: last.open, close: last.close };
      const tail = volPoints[volPoints.length - 1];
      if (tail && tail.time === time) volPoints[volPoints.length - 1] = point;
      else volPoints.push(point);
      volumeSeries.update(volBar(point));
    });

    /* 테마 전환 감지 → 토큰 재주입 (명시 토글 [data-theme] + 시스템 prefers-color-scheme 모두) */
    const applyTheme = () => {
      t = readTokens();
      chart.applyOptions({
        layout: { background: { type: ColorType.Solid, color: "transparent" }, textColor: t.text },
        grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
        rightPriceScale: { borderColor: t.grid },
        timeScale: { borderColor: t.grid },
      });
      candleSeries.applyOptions({ upColor: t.up, downColor: t.down, wickUpColor: t.up, wickDownColor: t.down });
      // 거래량 막대는 데이터 내장 색이라 applyOptions로 안 바뀐다. 새 토큰으로 재계산해 재주입하지
      // 않으면 기존 막대가 이전 테마 색으로 잔존해 한 차트에 두 테마 색이 섞인다
      if (volPoints.length > 0) volumeSeries.setData(volPoints.map(volBar));
    };
    const observer = new MutationObserver(applyTheme);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    mq.addEventListener("change", applyTheme);

    return () => {
      disposed = true;
      loadAbort.abort();
      unsubscribe();
      observer.disconnect();
      mq.removeEventListener("change", applyTheme);
      chart.remove();
    };
  }, [market, unit]);

  return (
    <div style={{ position: "relative", height }}>
      <div
        ref={containerRef}
        style={{ position: "absolute", inset: 0 }}
        role="img"
        aria-label={`${market} ${unit} 캔들 차트. 양봉은 빨간색(상승), 음봉은 파란색(하락), 하단은 거래량입니다.`}
      />
      {loading && (
        <div style={{ position: "absolute", inset: 0 }} aria-hidden="true">
          <Skeleton variant="chart" width="100%" height="100%" />
        </div>
      )}
      {empty && (
        <div
          role="status"
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            // 빈 상태 문구는 정보 텍스트라 tertiary(라이트 3.03:1) 금지, secondary 4.75:1
            color: "var(--text-secondary)",
            fontSize: "var(--font-body-sm-size)",
          }}
        >
          표시할 차트 데이터가 없어요
        </div>
      )}
    </div>
  );
}
