/**
 * candleStream.ts. DataSource 캔들 배선의 단일 통로(설계 §3.5).
 * 화면 코드는 SimEngine·fetch를 직접 부르지 않고 여기만 통해 데이터에 접근한다.
 * 초기 로드는 getCandles, 실시간은 체결(trade) 합성이 현재 유일 경로다.
 *
 * 배선 현황: 서버 /topic/candle.* 채널은 구독하지 않는다. 실시간 캔들은 trade 채널 구독으로
 * 클라이언트 합성한다. 따라서 서버 publishCandles·candle seq 계약과 httpDatasource의 candle
 * 재동기화 분기는 현재 도달 불가한 예약 경로다. 백엔드 캔들 채널 배선 시 이 파일 내부 교체(초기
 * getCandles 유지 + trade 합성을 candle 구독으로 대체)만으로 승격한다. 라이브 전환은 리스크가
 * 커 현 단계에서 배제한다.
 */

import type { Chart, KLineData } from "klinecharts";
import type { CandleUnit } from "@/lib/types";
import { dest } from "@/lib/types";
import { getDataSource } from "@/lib/api/datasource";
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

export interface CandleStreamCallbacks {
  /** 초기 로드 결과. hasData=false면 빈 상태 렌더 */
  onLoaded: (hasData: boolean) => void;
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
    .getCandles(market, unit, INITIAL_COUNT)
    .then((rows) => {
      if (disposed) return;
      // 백엔드 미구현·조회 실패는 어댑터가 []로 흡수(§3.7). 빈 상태로 수렴한다.
      if (rows.length === 0) {
        cb.onLoaded(false);
        return;
      }
      // 계약은 시각 내림차순 → 차트는 오름차순
      const asc = [...rows].reverse();
      const data: KLineData[] = asc.map((c) => ({
        timestamp: Date.parse(c.candle_time),
        open: toNum(c.open),
        high: toNum(c.high),
        low: toNum(c.low),
        close: toNum(c.close),
        volume: toNum(c.volume),
      }));
      chart.applyNewData(data);
      const latest = data[data.length - 1];
      last = latest ? { ...latest, volume: latest.volume ?? 0 } : null;
      cb.onLoaded(true);
      cb.onLatest(last);
    })
    .catch((e) => {
      if (disposed) return;
      // 어댑터가 흡수하지 못한 실패도 빈 상태로 수렴. 흡수 지점에 진단 로그를 남긴다
      console.warn(`[chart] ${market} ${unit} 캔들 로드 실패. 빈 상태 렌더`, e);
      cb.onLoaded(false);
    });

  // 체결 합성 폴백: 같은 버킷이면 high·low·close·volume 갱신, 새 버킷이면 새 봉(§3.5)
  const tradeCursor = newTradeCursor();
  const unsubscribe = getDataSource().subscribe(dest.trade(market), (msg) => {
    if (disposed || msg.type !== "trade" || !last) return;
    // 재연결·재동기화 재전달은 같은 체결을 다시 흘린다. (epoch, seq) 커서로 걸러 volume 이중계상을 막는다
    if (isDuplicateTrade(tradeCursor, msg)) return;
    advanceTradeCursor(tradeCursor, msg);
    const price = toNum(msg.price);
    const vol = toNum(msg.volume);
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
      if (flushTimer) {
        clearTimeout(flushTimer);
        flushTimer = null;
      }
      unsubscribe();
    },
  };
}
