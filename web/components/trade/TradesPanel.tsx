"use client";

/**
 * TradesPanel: 최근 체결 4열 테이블 (§2.5).
 * 체결가(직전 체결 대비 틱 방향) · 체결량 · 전일대비(전일종가 역산) · 시각, sticky 헤더 + 내부 스크롤.
 * 데이터: 스토어 trades[market](MAX 60) + REST 백필.
 *
 * 체결 시각효과(설계 §2): 신규 체결 도착 시 첫 행 방향색 플래시 1회 + 대형 체결 정적 배지 오버레이.
 * 강도·거버너는 useEffectIntensity·useTradeRateGovernor가 공급한다. 배지는 절대 배치라 시프트 0.
 */

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import { StaleBadge } from "@/components/StatusSignals";
import { useExchangeStore } from "@/lib/store";
import { useEffectIntensity } from "@/lib/useEffectIntensity";
import { getDataSource } from "@/lib/api/datasource";
import { mulDecimal } from "@/lib/decimal";
import type { Side, Trade } from "@/lib/types";
import {
  dirOf,
  formatChangeRate,
  formatCompactKRW,
  formatPrice,
  formatQty,
  formatTimeHMS,
  toNum,
} from "@/lib/format";
import { prevCloseOf, rateVsPrevClose } from "./derive";
import { useTradeRateGovernor } from "./useTradeRateGovernor";
import { useLargeTradeThreshold } from "./useLargeTradeThreshold";
import { effectiveLevel } from "./effectGovernor";
import { prefersReducedMotion } from "@/lib/reducedMotion";
import styles from "./TradesPanel.module.css";

const MAX_ROWS = 60;
const FLASH_MS = 260; // --dur-flash(220ms) + 여유
const HIT_WINDOW = 20; // 홍수 히트 틴트 우세 방향 판정 창(최근 체결 수)

interface Row {
  id: string;
  /** 방향·등락 판정용 수치 값. 표시·거래대금 곱은 priceStr(원본 문자열)로 정밀 보존 */
  price: number;
  priceStr: string;
  volume: string;
  side: Side;
  timestamp: string;
}

// reduced-motion 판정은 lib/reducedMotion의 단일 질의 객체를 쓴다(틱마다 재할당 방지)

/**
 * 체결 행. memo + 원시값 props로 신규 체결·플래시 커밋이 바뀐 행만 리렌더하게 한다.
 * memo 없이는 체결 1건당 60행 전부가 rateVsPrevClose(BigInt 경로)·포맷터 4종을 재실행하고,
 * 플래시 커밋이 그 비용을 한 번 더 반복한다(초당 20+ 체결 구간 상시 부하).
 */
const TradeRow = memo(function TradeRow({
  price,
  priceStr,
  volume,
  side,
  timestamp,
  prevClose,
  tickSpec,
  qtyScale,
  isLarge,
  flashDir,
  tickDir,
}: {
  price: number;
  priceStr: string;
  volume: string;
  side: Side;
  timestamp: string;
  prevClose: string;
  tickSpec: string | undefined;
  qtyScale: number;
  isLarge: boolean;
  flashDir: "up" | "down" | undefined;
  /** 직전 체결 대비 틱 방향. 화살표·색의 유일한 의미다(테이커 방향 인코딩 금지) */
  tickDir: "up" | "down" | "flat";
}) {
  const { rate, state } = rateVsPrevClose(price, prevClose);
  const d = dirOf(state);
  // 거래대금은 원본 문자열 정밀 곱(float 곱 금지). sub-1 KRW 대량 체결 정밀 보존
  const largeAmount = isLarge ? formatCompactKRW(mulDecimal(priceStr, volume)) : "";
  return (
    <tr className={flashDir ? "trade-flash" : undefined} data-flash={flashDir}>
      <td className={styles.priceCell}>
        {isLarge && (
          // 대형 태그는 규모 신호다. 방향색을 섞으면 화살표 문법이 다시 이중 의미가 된다
          <span className={styles.largeTag} title={`대형 체결 ${largeAmount}`} aria-hidden="true">
            대형
          </span>
        )}
        {/* 체결가 화살표는 직전 체결 대비 틱 방향, 우측 전일대비 열은 전일 종가 기준이다.
            둘은 기준이 다르므로 같은 행에서 부호가 갈릴 수 있다. 보합은 무채·무화살표 */}
        <span
          className="dir-text"
          data-dir={tickDir}
          aria-label={`${side === "bid" ? "매수" : "매도"} 체결 ${formatPrice(priceStr, tickSpec)}원${isLarge ? `, 대형 체결 ${largeAmount}` : ""}`}
        >
          {tickDir === "up" ? "▲ " : tickDir === "down" ? "▼ " : ""}
          {formatPrice(priceStr, tickSpec)}
        </span>
      </td>
      <td className={styles.qty}>{formatQty(volume, qtyScale)}</td>
      <td>
        <span className="dir-text" data-dir={d}>
          {formatChangeRate(rate, state)}
        </span>
      </td>
      <td className={styles.time}>{formatTimeHMS(timestamp)}</td>
    </tr>
  );
});

export default function TradesPanel({ market }: { market: string }) {
  const liveTrades = useExchangeStore((s) => s.trades[market]);
  const ticker = useExchangeStore((s) => s.tickers[market]);
  const connected = useExchangeStore((s) => s.connected);
  const spec = useExchangeStore((s) => s.markets.find((m) => m.market === market));
  const tickSpec = spec?.tick_size;
  const qtyScale = spec?.qty_scale ?? 8;
  const [backfill, setBackfill] = useState<Trade[] | null>(null);
  // 백필 실패를 표면화한다. catch 없으면 미처리 거부 + 라이브 체결 전까지 영구 스켈레톤
  const [backfillFailed, setBackfillFailed] = useState(false);
  const [backfillRetry, setBackfillRetry] = useState(0);

  const config = useEffectIntensity();
  const gov = useTradeRateGovernor(market);
  const large = useLargeTradeThreshold(market);
  const level = config.mode === "auto" ? effectiveLevel(config.level, gov) : config.level;

  useEffect(() => {
    let alive = true;
    // 마켓 전환 cleanup이 in-flight 백필을 실제 취소한다(결과 폐기만으로는 요청이 큐에 적체)
    const controller = new AbortController();
    setBackfill(null);
    setBackfillFailed(false);
    getDataSource()
      .getTrades(market, MAX_ROWS, controller.signal)
      .then((t) => {
        if (alive) setBackfill(t);
      })
      .catch(() => {
        if (alive) setBackfillFailed(true);
      });
    return () => {
      alive = false;
      controller.abort();
    };
  }, [market, backfillRetry]);

  const retryBackfill = useCallback(() => setBackfillRetry((n) => n + 1), []);

  const rows: Row[] = useMemo(() => {
    const seen = new Set<string>();
    const out: Row[] = [];
    const push = (id: string, price: string, volume: string, side: Side, timestamp: string) => {
      if (seen.has(id)) return;
      seen.add(id);
      out.push({ id, price: toNum(price), priceStr: price, volume, side, timestamp });
    };
    (liveTrades ?? []).forEach((t) => push(t.trade_id, t.price, t.volume, t.taker_side, t.timestamp));
    (backfill ?? []).forEach((t) => push(t.trade_id, t.price, t.volume, t.taker_side, t.timestamp));
    return out.slice(0, MAX_ROWS);
  }, [liveTrades, backfill]);

  // 첫 행 플래시: 선두 체결 갱신 시 방향색 1회 점멸. off·reduced-motion·홍수 모드는 생략(정적 수렴)
  const [flash, setFlash] = useState<{ id: string; dir: "up" | "down" } | null>(null);
  const lastTopId = useRef<string | null>(null);
  const flashTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    const top = rows[0];
    if (!top || lastTopId.current === top.id) return;
    const isInitial = lastTopId.current === null; // 백필 첫 렌더는 플래시 생략
    lastTopId.current = top.id;
    if (isInitial || level === "off" || gov.flood || prefersReducedMotion()) return;
    // 플래시도 가격 틱 방향. 보합·비교 불가는 생략한다. 행이 무채인데 배경만 방향색으로
    // 점멸하면 행 문법(보합 무채·무화살표)과 자기모순이 된다
    const prev = rows[1];
    if (!prev || top.price === prev.price) return;
    setFlash({ id: top.id, dir: top.price < prev.price ? "down" : "up" });
    if (flashTimer.current) clearTimeout(flashTimer.current);
    flashTimer.current = setTimeout(() => setFlash(null), FLASH_MS);
  }, [rows, level, gov.flood]);
  useEffect(
    () => () => {
      if (flashTimer.current != null) clearTimeout(flashTimer.current);
    },
    [],
  );

  const prevClose = prevCloseOf(ticker);
  // 홍수 모드: 개별 팝·플래시 대신 패널 방향 히트 틴트(최근 창의 가격 흐름 방향).
  // reduced-motion이면 배경 틴트도 끈다(정적 수렴). rows[0] 단건 구동은 red↔blue 점멸을 유발하므로
  // 창 양끝 가격 비교로 판정한다. 테이커 우세로 칠하면 행 화살표(가격 방향)와 의미가 갈린다.
  const hitDir = useMemo<"up" | "down" | undefined>(() => {
    if (!gov.flood || prefersReducedMotion()) return undefined;
    const window = rows.slice(0, HIT_WINDOW);
    if (window.length < 2) return undefined;
    const newest = window[0].price;
    const oldest = window[window.length - 1].price;
    if (newest === oldest) return undefined;
    return newest > oldest ? "up" : "down";
  }, [gov.flood, rows]);

  return (
    <PanelCard
      dense
      scroll
      title="체결"
      stale={!connected}
      actions={!connected ? <StaleBadge label="지연" announce={false} /> : undefined}
    >
      {backfillFailed && rows.length === 0 ? (
        <div className="empty-state" role="alert">
          <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
          <span className="empty-state__title">체결 내역 불러오기 실패</span>
          <button type="button" className="btn btn--secondary" onClick={retryBackfill}>
            다시 시도
          </button>
        </div>
      ) : backfill === null && rows.length === 0 ? (
        <Skeleton variant="card" width="100%" height="100%" />
      ) : rows.length === 0 ? (
        <div className="empty-state">
          <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
          <span className="empty-state__title">체결 내역 없음</span>
        </div>
      ) : (
        <table className={`${styles.table} num ${hitDir ? "hit-tint" : ""}`} data-hit={hitDir}>
          <thead>
            <tr>
              <th scope="col">체결가</th>
              <th scope="col">체결량</th>
              <th scope="col">전일대비</th>
              <th scope="col">시각</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t, i) => {
              // 직전 체결 = 시각상 바로 앞 행(목록은 내림차순). 마지막 행은 비교 대상이 없어 보합 처리
              const prev = rows[i + 1];
              const tickDir: "up" | "down" | "flat" =
                !prev || t.price === prev.price ? "flat" : t.price > prev.price ? "up" : "down";
              return (
                <TradeRow
                  key={t.id}
                  price={t.price}
                  priceStr={t.priceStr}
                  volume={t.volume}
                  side={t.side}
                  timestamp={t.timestamp}
                  prevClose={prevClose}
                  tickSpec={tickSpec}
                  qtyScale={qtyScale}
                  isLarge={large.isLarge(t.volume)}
                  flashDir={flash?.id === t.id ? flash.dir : undefined}
                  tickDir={tickDir}
                />
              );
            })}
          </tbody>
        </table>
      )}
    </PanelCard>
  );
}
