"use client";

/**
 * OrderbookPanel: 업비트식 세로 호가 (§2.5).
 * 매도 10호가(Blue 틴트) 위 / 현재가 경계 / 매수 10호가(Red 틴트) 아래.
 * 열: 잔량 막대 · 가격 · 등락률(ticker 전일종가 역산).
 * 데스크톱 프리필 규약: 클릭 시 가격만 주입, side 유지 (§2.5 규약 분기).
 */

import { useEffect, useRef } from "react";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import TickCell from "@/components/TickCell";
import { useExchangeStore } from "@/lib/store";
import { useEffectIntensity } from "@/lib/useEffectIntensity";
import { dirOf, formatChangeRate, formatPrice, formatQty, toNum } from "@/lib/format";
import { isPresentLevel, prevCloseOf, rateVsPrevClose } from "./derive";
import { useTradeRateGovernor } from "./useTradeRateGovernor";
import { effectiveLevel } from "./effectGovernor";
import styles from "./OrderbookPanel.module.css";

const DEPTH = 10;

interface Props {
  market: string;
  /** 데스크톱 규약: 가격만 주입, side 전환 없음 */
  onPricePrefill: (price: string) => void;
}

export default function OrderbookPanel({ market, onPricePrefill }: Props) {
  const orderbook = useExchangeStore((s) => s.orderbooks[market]);
  const ticker = useExchangeStore((s) => s.tickers[market]);
  const spec = useExchangeStore((s) => s.markets.find((m) => m.market === market));
  // 표시 자리수는 마켓 스펙에서 유도한다(호출부 상수 금지). 미로딩 시 폴백
  const tickSpec = spec?.tick_size;
  const qtyScale = spec?.qty_scale ?? 8;

  const scrollRef = useRef<HTMLDivElement>(null);
  const midRef = useRef<HTMLDivElement>(null);
  const centered = useRef(false);

  // 마켓 진입 시 1회 현재가 경계를 스크롤 중앙에 위치
  useEffect(() => {
    centered.current = false;
  }, [market]);
  useEffect(() => {
    if (centered.current || !orderbook) return;
    const box = scrollRef.current;
    const mid = midRef.current;
    if (!box || !mid) return;
    box.scrollTop = mid.offsetTop - box.clientHeight / 2 + mid.clientHeight / 2;
    centered.current = true;
  }, [orderbook]);

  const units = orderbook?.orderbook_units ?? [];
  // 표시 슬롯을 DEPTH로 고정한다. 서버가 얇은 호가를 잘라 보내든 0으로 채우든
  // 행 수가 10+10으로 유지돼 호가 증감 시 레이아웃 시프트가 0이다.
  const slots = Array.from({ length: DEPTH }, (_, i) => units[i]);
  const maxSize = slots.reduce(
    (mx, u) => Math.max(mx, toNum(u?.ask_size ?? "0"), toNum(u?.bid_size ?? "0")),
    0,
  );
  const prevClose = prevCloseOf(ticker);
  const dir = ticker ? dirOf(ticker.change) : "flat";

  const config = useEffectIntensity();
  const gov = useTradeRateGovernor(market);
  const midLevel = config.mode === "auto" ? effectiveLevel(config.level, gov) : config.level;

  const renderRow = (side: "ask" | "bid", price: string, size: string, index: number) => {
    // 얇은 호가의 빈 슬롯(가격·잔량 0)은 값 없는 자리행으로 그린다.
    // 행 수를 10+10으로 고정해 호가 도착·소멸 시 레이아웃 시프트를 0으로 유지한다.
    if (!isPresentLevel(price, size)) {
      return (
        <div
          key={`${side}-${index}`}
          className={`${styles.row} ${styles.rowEmpty}`}
          aria-hidden="true"
        />
      );
    }
    const { rate, state } = rateVsPrevClose(price, prevClose);
    const d = dirOf(state);
    const sideLabel = side === "ask" ? "매도" : "매수";
    return (
      <button
        key={`${side}-${index}`}
        type="button"
        className={`${styles.row} ${side === "ask" ? styles.rowAsk : styles.rowBid} num`}
        onClick={() => onPricePrefill(price)}
        aria-label={`${sideLabel}호가 ${formatPrice(price, tickSpec)}원, 잔량 ${formatQty(size, qtyScale)}, 주문 가격으로 입력`}
      >
        <span
          className={styles.depth}
          style={{ width: maxSize > 0 ? `${Math.min(100, (toNum(size) / maxSize) * 100)}%` : 0 }}
          aria-hidden="true"
        />
        <span className={`${styles.cell} ${styles.qty}`}>{formatQty(size, qtyScale)}</span>
        <span className={`${styles.cell} ${styles.price} dir-text`} data-dir={d}>
          {formatPrice(price, tickSpec)}
        </span>
        <span className={`${styles.cell} ${styles.rate} dir-text`} data-dir={d}>
          {formatChangeRate(rate, state)}
        </span>
      </button>
    );
  };

  return (
    <PanelCard dense title="호가">
      <div className={styles.wrap}>
        {orderbook ? (
          <>
            <div className={styles.scroll} ref={scrollRef}>
              {slots
                .map((u, i) => renderRow("ask", u?.ask_price ?? "0", u?.ask_size ?? "0", i))
                .reverse()}
              <div className={`${styles.mid} num dir-text`} data-dir={dir} ref={midRef}>
                {ticker ? (
                  <TickCell value={toNum(ticker.trade_price)} valueStr={formatPrice(ticker.trade_price, tickSpec)} format={(v) => formatPrice(v, tickSpec)} level={midLevel} popCap={gov.popCap} popScope="orderbook" throttleBonus={gov.throttleBonusMs} />
                ) : (
                  "-"
                )}
                {dir !== "flat" && <span aria-hidden="true">{dir === "up" ? "▲" : "▼"}</span>}
                <span className={styles.midCaption}>현재가</span>
              </div>
              {slots.map((u, i) => renderRow("bid", u?.bid_price ?? "0", u?.bid_size ?? "0", i))}
            </div>
            <div className={`${styles.totals} num`}>
              <span className={styles.totalItem}>
                <span className={styles.totalLabel}>매도 잔량</span>
                <span className="dir-text" data-dir="down">
                  {formatQty(orderbook.total_ask_size, qtyScale)}
                </span>
              </span>
              <span className={styles.totalItem}>
                <span className={styles.totalLabel}>매수 잔량</span>
                <span className="dir-text" data-dir="up">
                  {formatQty(orderbook.total_bid_size, qtyScale)}
                </span>
              </span>
            </div>
          </>
        ) : (
          <Skeleton variant="card" width="100%" height="100%" />
        )}
      </div>
    </PanelCard>
  );
}
