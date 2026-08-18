"use client";

/**
 * SpotlightPanel: 대표 코인 미니 캔들 + 요약.
 * 상단 코인 칩으로 선택 전환(대시보드 로컬 상태), 주기 탭, CandleChart 재사용.
 * 하단 요약(현재가·등락·고저·거래대금)과 거래 화면 진입 CTA.
 */

import { useState } from "react";
import Link from "next/link";
import CandleChart from "@/components/CandleChart";
import SegmentedTab from "@/components/SegmentedTab";
import SymbolChip from "@/components/SymbolChip";
import PricePill from "@/components/PricePill";
import { StaleBadge } from "@/components/StatusSignals";
import { useExchangeStore } from "@/lib/store";
import { COIN_META } from "@/lib/sim/engine";
import { dirOf, formatCompactQty, formatPrice } from "@/lib/format";
import type { CandleUnit } from "@/lib/types";
import styles from "./SpotlightPanel.module.css";

const CHOICES = COIN_META.slice(0, 6);

const UNITS: { value: CandleUnit; label: string }[] = [
  { value: "15m", label: "15분" },
  { value: "60m", label: "1시간" },
  { value: "1d", label: "1일" },
];

export default function SpotlightPanel() {
  const [market, setMarket] = useState(CHOICES[0].market);
  const [unit, setUnit] = useState<CandleUnit>("60m");
  const meta = COIN_META.find((c) => c.market === market)!;
  const ticker = useExchangeStore((s) => s.tickers[market]);
  const connected = useExchangeStore((s) => s.connected);
  const spec = useExchangeStore((s) => s.markets.find((m) => m.market === market));
  const tickSpec = spec?.tick_size;
  const qtyScale = spec?.qty_scale ?? 8;
  const dir = ticker ? dirOf(ticker.change) : "flat";

  return (
    <section className={`card ${styles.root}`} aria-label="대표 코인">
      <span className={styles.plate}>
        <span className="gauge-plate">대표 코인</span>
        {!connected && <StaleBadge label="지연" announce={false} />}
      </span>
      <div className={styles.chips} role="tablist" aria-label="대표 코인 선택">
        {CHOICES.map((c) => (
          <button
            key={c.market}
            type="button"
            role="tab"
            aria-selected={c.market === market}
            className={styles.chip}
            data-on={c.market === market || undefined}
            onClick={() => setMarket(c.market)}
          >
            <SymbolChip meta={c} size="sm" />
            <span>{c.symbol}</span>
          </button>
        ))}
      </div>

      <div className={styles.head}>
        <div className={styles.ident}>
          <span className={styles.name}>{meta.koreanName}</span>
          <span className={`${styles.sym} num`}>{market}</span>
        </div>
        {/* 단절 구간은 마지막 시세임을 감쇠로 알린다. 배지·명판은 또렷이 유지(A-5) */}
        <div className={styles.figures} data-stale={!connected ? "" : undefined}>
          <span className={`${styles.price} num dir-hero`} data-dir={dir}>
            {ticker?.trade_price != null ? formatPrice(ticker.trade_price, tickSpec) : "-"}
          </span>
          {ticker && (
            <PricePill
              change={ticker.change}
              changeRate={ticker.change_rate}
              amount={ticker.change_price}
              amountSpec={tickSpec}
              variant="inline"
            />
          )}
        </div>
      </div>

      <div className={styles.tf}>
        <SegmentedTab
          items={UNITS}
          value={unit}
          onChange={setUnit}
          variant="underline"
          ariaLabel="차트 주기"
        />
      </div>

      <div className={styles.chart}>
        <CandleChart market={market} unit={unit} height={240} />
      </div>

      <div className={styles.summary}>
        <div className={styles.stat}>
          <span className={styles.statLabel}>고가(24H)</span>
          <span className={`${styles.statValue} num`}>{ticker?.high_24h != null ? formatPrice(ticker.high_24h, tickSpec) : "-"}</span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statLabel}>저가(24H)</span>
          <span className={`${styles.statValue} num`}>{ticker?.low_24h != null ? formatPrice(ticker.low_24h, tickSpec) : "-"}</span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statLabel}>거래량(24H)</span>
          <span className={`${styles.statValue} num`}>
            {ticker ? `${formatCompactQty(ticker.acc_trade_volume_24h, qtyScale)} ${meta.symbol}` : "-"}
          </span>
        </div>
        <Link href={`/exchange/${market}`} className={`btn btn--primary ${styles.cta}`}>
          {meta.koreanName} 거래하기
        </Link>
      </div>
    </section>
  );
}
