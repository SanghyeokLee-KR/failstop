"use client";

/**
 * 클라이언트 전용이라 ChartPanel이 next/dynamic ssr:false로 로드한다(설계 §3.1).
 * 데이터 접근은 candleStream 단일 통로만 쓰고 DataSource를 직접 부르지 않는다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { ActionType, dispose, init, type Chart, type IndicatorCreate, type KLineData } from "klinecharts";
import Skeleton from "@/components/Skeleton";
import type { CandleUnit } from "@/lib/types";
import { formatPrice, formatQty } from "@/lib/format";
import ChartToolbar from "./ChartToolbar";
import ChartReadout from "./ChartReadout";
import { readChartTokens, useChartTheme } from "./useChartTheme";
import { buildChartStyles, precisionOf, volStyles } from "./chartTheme";
import { DEFAULT_ACTIVE, specOf, type IndicatorKey, type IndicatorSpec } from "./indicators";
import { startCandleStream, type CandleStreamHandle, type LiveCandle } from "./candleStream";
import { registerMatchedVolume } from "./volIndicator";
import { registerOrderLine, syncOrderLines, toOrderLines, type OrderLineSpec } from "./orderLine";
import { useExchangeStore } from "@/lib/store";
import { prefersReducedMotion } from "@/lib/reducedMotion";
import styles from "./chart.module.css";

// 내장 VOL을 막대 폭이 캔들과 같은 구현으로 교체한다. 모듈 로드 시 1회(등록은 이름 기준 덮어쓰기).
registerMatchedVolume();
registerOrderLine();

const CANDLE_PANE_ID = "candle_pane";
// 거래량 전용 pane. 캔들 pane에 스택하지 않는다. klinecharts YAxis는 pane에 붙은 모든 지표 figure
// 값을 축 범위에 포함시켜, 볼륨(≈0~수십)이 캔들 pane에 있으면 가격 축 하한을 0으로 끌어내려 캔들이
// 상단에 눌린다. 분리 pane는 자체 축을 가져 가격 축이 실제 OHLC 범위에 맞춰 피팅된다.
const VOLUME_PANE_ID = "volume_pane";
const VOLUME_PANE_HEIGHT = 78;

// 데이터 로드 후 뷰포트 API를 호출하지 않는다. 호출 조합·주기마다 캔들 pane과 거래량 pane이
// 서로 다른 봉 간격·렌더 범위로 갈라진다(로드 후 보정으로는 해결되지 않는다).

// 모션 킬스위치 판정(§4.5)은 lib/reducedMotion의 단일 질의 객체를 쓴다. init·테마 재주입 시
// klinecharts 애니메이션 비활성에 소비한다.

const UNIT_LABEL: Record<string, string> = {
  "1m": "1분", "5m": "5분", "15m": "15분", "60m": "1시간", "240m": "4시간", "1d": "1일",
};

// status 리전 낭독 디바운스. 크로스헤어 스윕·연속 이벤트를 최종 1건으로 합쳐 SR 낭독 큐 폭주를 막는다.
// 시각 채널(플래시 throttle 300ms, 소음 0)의 청각 대칭.
const ANNOUNCE_DEBOUNCE_MS = 2500;

/** 게이팅된 낭독 문구. 형성봉 300ms 스트림이 아니라 롤오버·크로스헤어·명시 갱신에서만 호출된다 */
function candleSummary(market: string, unitLabel: string, c: LiveCandle, prefix: string): string {
  const dir = c.close > c.open ? "상승" : c.close < c.open ? "하락" : "보합";
  return `${market} ${unitLabel} ${prefix} 시가 ${formatPrice(c.open)}, 고가 ${formatPrice(c.high)}, 저가 ${formatPrice(c.low)}, 종가 ${formatPrice(c.close)}, 거래량 ${formatQty(c.volume, 4)}, ${dir}.`;
}

/** 지표 생성. main=캔들 pane에 스택, sub=새 보조 pane. 생성 paneId 반환 */
function createIndicatorFor(chart: Chart, spec: IndicatorSpec, tokens: ReturnType<typeof readChartTokens>): string | null {
  const create: IndicatorCreate = { name: spec.name };
  if (spec.calcParams) create.calcParams = spec.calcParams;
  // DeepPartial 오버라이드를 라이브러리의 Partial 스타일 파라미터 경계에서 좁힌다
  if (spec.styles) create.styles = spec.styles(tokens) as IndicatorCreate["styles"];
  if (spec.placement === "main") {
    chart.createIndicator(create, true, { id: CANDLE_PANE_ID });
    return CANDLE_PANE_ID;
  }
  const id = chart.createIndicator(create, false);
  return typeof id === "string" ? id : null;
}

export default function TradingChart({ market }: { market: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<Chart | null>(null);
  const streamRef = useRef<CandleStreamHandle | null>(null);
  const subPaneRef = useRef<Map<IndicatorKey, string>>(new Map());
  const appliedRef = useRef<Set<IndicatorKey>>(new Set());
  const precisionRef = useRef<number>(0);

  const [unit, setUnit] = useState<CandleUnit>("15m");
  const [active, setActive] = useState<Set<IndicatorKey>>(() => new Set(DEFAULT_ACTIVE));
  const [loading, setLoading] = useState(true);
  const [empty, setEmpty] = useState(false);
  const [latest, setLatest] = useState<LiveCandle | null>(null);
  // 크로스헤어가 가리키는 봉. null이면 판독은 최신 봉으로 폴백
  const [hover, setHover] = useState<LiveCandle | null>(null);
  const hoverTsRef = useRef<number | null>(null);
  // 재시도 카운터. 빈 상태의 다시 불러오기가 스트림 effect를 재실행시킨다
  const [retrySeq, setRetrySeq] = useState(0);
  // status 리전 낭독 문구. 형성봉 300ms 스트림과 분리해 게이팅된 이벤트에서만 갱신한다
  const [ariaMessage, setAriaMessage] = useState("");
  // 미체결 주문선. 스토어를 그대로 구독해 별도 폴러를 만들지 않는다
  const openOrders = useExchangeStore((s) => s.openOrders);
  const orderLinesRef = useRef<Map<string, OrderLineSpec>>(new Map());

  const unitRef = useRef(unit);
  unitRef.current = unit;
  const activeRef = useRef(active);
  activeRef.current = active;
  const marketRef = useRef(market);
  marketRef.current = market;
  // 마지막으로 관측한 형성봉 timestamp. 봉 롤오버(단위당 1회) 판정에 쓴다
  const lastBarTsRef = useRef<number | null>(null);
  const announceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingAriaRef = useRef<string>("");

  // 낭독 게이트. immediate는 명시 갱신(최초 로드·마켓/유닛 전환)용 즉시 낭독, 그 외는 수초 디바운스
  const announce = useCallback((message: string, immediate = false) => {
    if (announceTimerRef.current) {
      clearTimeout(announceTimerRef.current);
      announceTimerRef.current = null;
    }
    if (immediate) {
      setAriaMessage(message);
      return;
    }
    pendingAriaRef.current = message;
    announceTimerRef.current = setTimeout(() => {
      announceTimerRef.current = null;
      setAriaMessage(pendingAriaRef.current);
    }, ANNOUNCE_DEBOUNCE_MS);
  }, []);
  const announceRef = useRef(announce);
  announceRef.current = announce;

  const handleLatest = useCallback((candle: LiveCandle | null) => {
    const chart = chartRef.current;
    if (chart && candle) {
      // 종가 크기 기반 축 정밀도. 변할 때만 재설정해 불필요한 재그리기 방지
      const p = precisionOf(String(candle.close));
      if (p !== precisionRef.current) {
        precisionRef.current = p;
        chart.setPriceVolumePrecision(p, 4);
      }
    }
    setLatest(candle);
    // 형성봉 300ms 갱신은 시각(ChartReadout) 전용. 낭독은 봉 롤오버에서만 게이팅해 큐 폭주를 막는다
    if (!candle) {
      lastBarTsRef.current = null;
      return;
    }
    const prevTs = lastBarTsRef.current;
    lastBarTsRef.current = candle.timestamp;
    const unitLabel = UNIT_LABEL[unitRef.current] ?? unitRef.current;
    if (prevTs === null) {
      // 최초 로드·마켓/유닛 전환 후 첫 봉: 명시 갱신으로 즉시 낭독
      announceRef.current(candleSummary(marketRef.current, unitLabel, candle, "현재 봉."), true);
    } else if (candle.timestamp !== prevTs) {
      // 봉 롤오버: 단위당 1회만 낭독
      announceRef.current(candleSummary(marketRef.current, unitLabel, candle, "새 봉 시작."), false);
    }
  }, []);

  // 테마 전환 → 전역 스타일 + 지표별 색 재주입(§4.3)
  const reapplyTheme = useCallback(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const tokens = readChartTokens();
    chart.setStyles(buildChartStyles(tokens, prefersReducedMotion()));
    chart.overrideIndicator({ name: "VOL", styles: volStyles(tokens) as IndicatorCreate["styles"] }, VOLUME_PANE_ID);
    activeRef.current.forEach((key) => {
      const spec = specOf(key);
      if (!spec || !spec.styles) return;
      const paneId = spec.placement === "main" ? CANDLE_PANE_ID : subPaneRef.current.get(key);
      if (paneId) chart.overrideIndicator({ name: spec.name, styles: spec.styles(tokens) as IndicatorCreate["styles"] }, paneId);
    });
  }, []);
  useChartTheme(reapplyTheme);

  // 초기화: 차트 생성 + 거래량(상시) + 활성 지표 + resize 관찰. [market]마다 재생성
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const tokens = readChartTokens();
    const chart = init(el, { styles: buildChartStyles(tokens, prefersReducedMotion()) });
    if (!chart) return;
    chartRef.current = chart;

    /* klinecharts가 캔버스 래퍼에 tabIndex=1을 심어 문서 탭 순서를 통째로 앞지른다.
       role="img"이고 조작은 전부 툴바 버튼이라 -1로 내려 이름 없는 정지점을 없앤다 */
    for (const positive of el.querySelectorAll('[tabindex="1"]')) {
      positive.setAttribute("tabindex", "-1");
    }
    chart.setPriceVolumePrecision(precisionRef.current, 4);
    chart.createIndicator({ name: "VOL", styles: volStyles(tokens) as IndicatorCreate["styles"] }, false, { id: VOLUME_PANE_ID, height: VOLUME_PANE_HEIGHT });

    subPaneRef.current.clear();
    appliedRef.current = new Set();
    activeRef.current.forEach((key) => {
      const spec = specOf(key);
      if (!spec) return;
      const id = createIndicatorFor(chart, spec, tokens);
      if (spec.placement === "sub" && id) subPaneRef.current.set(key, id);
      appliedRef.current.add(key);
    });

    // 크로스헤어 판독. 같은 봉 안의 이동은 무시해 setState 빈도를 봉 단위로 낮춘다
    const onCrosshair = (data?: { kLineData?: KLineData }) => {
      const k = data?.kLineData;
      const ts = k ? k.timestamp : null;
      if (ts === hoverTsRef.current) return;
      hoverTsRef.current = ts;
      const c = k
        ? { timestamp: k.timestamp, open: k.open, high: k.high, low: k.low, close: k.close, volume: k.volume ?? 0 }
        : null;
      setHover(c);
      // 크로스헤어 조작은 사용자 능동 행위. 가리킨 봉을 디바운스 낭독 큐에 올려 스윕 소음을 흡수한다
      if (c) {
        const unitLabel = UNIT_LABEL[unitRef.current] ?? unitRef.current;
        announceRef.current(candleSummary(marketRef.current, unitLabel, c, "선택 봉."), false);
      }
    };
    chart.subscribeAction(ActionType.OnCrosshairChange, onCrosshair);

    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(el);

    // 보조 pane은 레이아웃 확정 전 폭으로 캔버스가 잡혀 봉이 가로로 압축될 수 있다.
    // ResizeObserver는 컨테이너 크기가 변할 때만 깨어나므로 rAF 2회 뒤 한 번 강제 재계산한다.
    let raf2 = 0;
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => chart.resize());
    });

    return () => {
      cancelAnimationFrame(raf1);
      if (raf2) cancelAnimationFrame(raf2);
      ro.disconnect();
      chart.unsubscribeAction(ActionType.OnCrosshairChange, onCrosshair);
      streamRef.current?.dispose();
      streamRef.current = null;
      dispose(el);
      chartRef.current = null;
    };
    // unit을 의존에 넣어 주기 전환마다 차트를 새로 만든다. 인스턴스를 유지하면 거래량 pane이
    // 직전 주기의 봉·간격을 그대로 들고 있다(지표 재계산·resize·뷰포트 API로 해소되지 않는다).
  }, [market, unit]);

  // 캔들 스트림: market·unit 조합마다 재배선. 초기 로드 + 체결 합성 폴백
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    setLoading(true);
    setEmpty(false);
    setLatest(null);
    hoverTsRef.current = null;
    setHover(null);
    // 재배선 시 첫 봉을 명시 갱신으로 낭독하도록 롤오버 기준을 리셋
    lastBarTsRef.current = null;
    streamRef.current?.dispose();
    streamRef.current = startCandleStream(market, unit, chart, {
      onLoaded: (hasData) => {
        setLoading(false);
        setEmpty(!hasData);
      },
      onLatest: handleLatest,
    });
    return () => {
      streamRef.current?.dispose();
      streamRef.current = null;
    };
  }, [market, unit, retrySeq, handleLatest]);

  /* 미체결 주문선. 스토어가 바뀔 때만 돌고 캔들 스트림과 독립이다.
     차트가 [market]마다 재생성되므로 마켓이 바뀌면 이전 선은 차트와 함께 사라진다.
     그때 ref 를 비우지 않으면 다음 마켓에서 지울 대상을 잘못 계산한다 */
  useEffect(() => {
    orderLinesRef.current = new Map();
  }, [market]);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const tokens = readChartTokens();
    const next = toOrderLines(openOrders, market, tokens, (v) => formatQty(v, 4));
    orderLinesRef.current = syncOrderLines(chart, orderLinesRef.current, next);
  }, [openOrders, market]);

  // 지표 토글: 활성 집합 diff만 반영(전체 재생성 없이)
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const applied = appliedRef.current;
    const tokens = readChartTokens();
    active.forEach((key) => {
      if (applied.has(key)) return;
      const spec = specOf(key);
      if (!spec) return;
      const id = createIndicatorFor(chart, spec, tokens);
      if (spec.placement === "sub" && id) subPaneRef.current.set(key, id);
      applied.add(key);
    });
    [...applied].forEach((key) => {
      if (active.has(key)) return;
      const spec = specOf(key);
      if (spec) {
        const paneId = spec.placement === "main" ? CANDLE_PANE_ID : subPaneRef.current.get(key);
        if (paneId) chart.removeIndicator(paneId, spec.name);
        subPaneRef.current.delete(key);
      }
      applied.delete(key);
    });
  }, [active]);

  // 언마운트 시 대기 중 낭독 디바운스 타이머 정리
  useEffect(() => () => {
    if (announceTimerRef.current) clearTimeout(announceTimerRef.current);
  }, []);

  const onToggleIndicator = useCallback((key: IndicatorKey) => {
    setActive((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const onDraw = useCallback((overlay: string) => {
    chartRef.current?.createOverlay(overlay);
  }, []);

  const onClearDrawings = useCallback(() => {
    chartRef.current?.removeOverlay();
  }, []);

  // 크로스헤어가 형성 중 마지막 봉 위에 있으면 스냅샷 대신 실시간 값을 판독한다
  const readoutCandle = hover ? (latest && hover.timestamp === latest.timestamp ? latest : hover) : latest;

  const unitLabel = UNIT_LABEL[unit] ?? unit;
  const ariaLabel = `${market} ${unitLabel} 캔들 차트. 양봉은 빨간색 상승, 음봉은 파란색 하락, 하단은 거래량입니다.`;

  return (
    <div className={styles.body}>
      <ChartToolbar
        unit={unit}
        onUnit={setUnit}
        active={active}
        onToggleIndicator={onToggleIndicator}
        onDraw={onDraw}
        onClearDrawings={onClearDrawings}
      />
      <ChartReadout candle={readoutCandle} />
      <div className={styles.chartArea}>
        <div ref={containerRef} className={styles.canvas} role="img" aria-label={ariaLabel} />
        {loading && (
          <div className={styles.overlayState} aria-hidden="true">
            <Skeleton variant="chart" width="100%" height="100%" />
          </div>
        )}
        {empty && !loading && (
          <div className={styles.emptyState} role="status">
            <span>표시할 차트 데이터 없음</span>
            <button type="button" className={styles.retryBtn} onClick={() => setRetrySeq((n) => n + 1)}>
              다시 불러오기
            </button>
          </div>
        )}
        <p className={styles.srOnly} role="status" aria-live="polite">
          {ariaMessage}
        </p>
      </div>
    </div>
  );
}
