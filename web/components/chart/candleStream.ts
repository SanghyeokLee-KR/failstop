/**
 * 화면 코드는 SimEngine·fetch를 직접 부르지 않고 여기만 통해 캔들에 접근한다(설계 §3.5).
 * 서버 /topic/candle.*는 구독하지 않고 trade 합성이라, candle seq 계약은 도달 불가한 예약 경로다.
 */

import type { Chart, KLineData } from "klinecharts";
import type { CandleUnit } from "@/lib/types";
import { dest } from "@/lib/types";
import { getDataSource } from "@/lib/api/datasource";
import { ApiError } from "@/lib/api/httpDatasource";
import { clampRetrySec } from "@/lib/store";
import { CANDLE_UNIT_MS } from "@/lib/sim/engine";
import { toNum } from "@/lib/format";
import { newTradeCursor, isDuplicateTrade, advanceTradeCursor } from "./tradeDedup";

export interface LiveCandle {
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

/**
 * 초기 로드 결과. 넷을 가른다(차트 설계 5-2, 디자인시스템 A-8).
 *
 * 종전에는 불리언 하나였고 빈 응답과 조회 실패가 같은 값으로 접혔다. 그 합침 때문에
 * 429로 못 받은 화면과 진짜 데이터 없는 마켓이 구분되지 않았고, 실제로 체결 12만 건이
 * 도는 마켓의 백프레셔를 데이터 부재로 오진한 사례가 있다.
 *
 * deferred 는 실패가 아니라 지연이다. 서버가 회복 시각을 함께 주므로 종결로 적지 않는다.
 */
export type CandleLoadResult =
  | { kind: "data" }
  | { kind: "empty" }
  | { kind: "error" }
  | { kind: "deferred"; retryAfterSec: number };

export interface CandleStreamCallbacks {
  /** 초기 로드 결과 */
  onLoaded: (result: CandleLoadResult) => void;
  /** 형성 중 마지막 캔들 갱신(300ms coalesce). 시각 판독(ChartReadout)만 소비하고, aria 낭독은
   *  TradingChart가 봉 롤오버·크로스헤어로 별도 게이팅한다 */
  onLatest: (candle: LiveCandle | null) => void;
}

export interface CandleStreamHandle {
  dispose: () => void;
}

const INITIAL_COUNT = 200;
// 마지막 봉 갱신 coalesce 간격. 셀별 틱 플래시 throttle과 동일(--flash-throttle, 설계 §4.5 C-4)
const FLUSH_THROTTLE_MS = 300;

/** 캔들 스트림 시작. market·unit 조합마다 새로 생성하고 이전 것은 dispose한다 */
export function startCandleStream(
  market: string,
  unit: CandleUnit,
  chart: Chart,
  cb: CandleStreamCallbacks,
): CandleStreamHandle {
  let disposed = false;
  // dispose가 in-flight 초기 로드를 실제 취소한다. 결과 폐기(disposed 플래그)만으로는
  // 빠른 마켓·주기 전환 반복 시 미취소 요청이 커넥션 큐에 적체된다
  const loadAbort = new AbortController();
  const unitMs = CANDLE_UNIT_MS[unit];
  let last: LiveCandle | null = null;
  let flushTimer: ReturnType<typeof setTimeout> | null = null;

  // 형성 봉 갱신을 300ms로 coalesce. 마지막 값만 반영해 재그리기·리렌더·낭독 빈도를 낮춘다(레이아웃 시프트 0 유지)
  function flush(): void {
    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = null;
    }
    if (disposed || !last) return;
    chart.updateData({ ...last });
    cb.onLatest(last);
  }
  // rollover(새 봉 확정)는 즉시, 같은 봉 내 갱신은 trailing throttle
  function schedule(immediate: boolean): void {
    if (immediate) {
      flush();
      return;
    }
    if (flushTimer) return;
    flushTimer = setTimeout(flush, FLUSH_THROTTLE_MS);
  }

  getDataSource()
    .getCandles(market, unit, INITIAL_COUNT, loadAbort.signal)
    .then((rows) => {
      if (disposed) return;
      // 200 + 0행만 빈 상태다. 조회 실패는 아래 catch 가 사유별로 가른다
      if (rows.length === 0) {
        cb.onLoaded({ kind: "empty" });
        return;
      }
      // 계약은 시각 내림차순 → 차트는 오름차순
      const asc = [...rows].reverse();
      // 0·NaN OHLC 캔들은 klinecharts 가격 축 범위를 0까지 오염시킨다. 결측·미형성 버킷은 유입 차단
      const data: KLineData[] = [];
      for (const c of asc) {
        const open = toNum(c.open);
        const high = toNum(c.high);
        const low = toNum(c.low);
        const close = toNum(c.close);
        if (!(open > 0 && high > 0 && low > 0 && close > 0)) continue;
        data.push({ timestamp: Date.parse(c.candle_time), open, high, low, close, volume: Math.max(0, toNum(c.volume)) });
      }
      if (data.length === 0) {
        cb.onLoaded({ kind: "empty" });
        return;
      }
      chart.applyNewData(data);
      const latest = data[data.length - 1];
      last = latest ? { ...latest, volume: latest.volume ?? 0 } : null;
      cb.onLoaded({ kind: "data" });
      cb.onLatest(last);
    })
    .catch((e) => {
      if (disposed) return;
      /* 429 는 지연이다. 상한 해석은 store 와 같은 규칙을 쓴다. 두 곳이 다르면
         같은 헤더가 화면마다 다르게 해석된다(API 설계 2-3) */
      if (e instanceof ApiError && e.status === 429) {
        cb.onLoaded({ kind: "deferred", retryAfterSec: clampRetrySec(e.retryAfter) });
        return;
      }
      console.warn(`[chart] ${market} ${unit} 캔들 로드 실패`, e);
      cb.onLoaded({ kind: "error" });
    });

  // 체결 합성 폴백: 같은 버킷이면 high·low·close·volume 갱신, 새 버킷이면 새 봉(§3.5)
  const tradeCursor = newTradeCursor();
  const unsubscribe = getDataSource().subscribe(dest.trade(market), (msg) => {
    if (disposed || msg.type !== "trade" || !last) return;
    // 재연결·재동기화 재전달은 같은 체결을 다시 흘린다. (epoch, seq) 커서로 걸러 volume 이중계상을 막는다
    if (isDuplicateTrade(tradeCursor, msg)) return;
    advanceTradeCursor(tradeCursor, msg);
    const price = toNum(msg.price);
    // 결측·이상 체결가(0·NaN)는 close=0 캔들을 만들어 축을 오염시킨다. 합성에서 배제
    if (!(price > 0)) return;
    const vol = Math.max(0, toNum(msg.volume));
    const bucket = Math.floor(Date.parse(msg.timestamp) / unitMs) * unitMs;
    // 순서 역전 체결(bucket < 현재 봉)은 과거가로 close를 덮어쓰므로 무시. candle_time 단조성 방어
    if (bucket < last.timestamp) return;
    if (bucket === last.timestamp) {
      last = {
        timestamp: last.timestamp,
        open: last.open,
        high: Math.max(last.high, price),
        low: Math.min(last.low, price),
        close: price,
        volume: last.volume + vol,
      };
      schedule(false);
    } else {
      last = { timestamp: bucket, open: last.close, high: Math.max(last.close, price), low: Math.min(last.close, price), close: price, volume: vol };
      // 새 봉 확정은 즉시 반영해 이전 봉 close·롤오버가 지연 없이 그려지게 한다
      schedule(true);
    }
  });

  return {
    dispose: () => {
      disposed = true;
      loadAbort.abort();
      if (flushTimer) {
        clearTimeout(flushTimer);
        flushTimer = null;
      }
      unsubscribe();
    },
  };
}
