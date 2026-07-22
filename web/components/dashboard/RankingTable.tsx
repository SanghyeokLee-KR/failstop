"use client";

/**
 * RankingTable: 실시간 순위 테이블. 탭(거래대금순·급상승·급하락)으로 정렬 기준 전환.
 * 데이터: 전 마켓 tickers. 행 클릭 시 /exchange/[market]. 현재가는 TickCell 플래시.
 */

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import SegmentedTab from "@/components/SegmentedTab";
import SymbolChip from "@/components/SymbolChip";
import TickCell from "@/components/TickCell";
import { useExchangeStore } from "@/lib/store";
import { COIN_META } from "@/lib/sim/engine";
import { compareDecimal } from "@/lib/decimal";
import {
  ariaChangeLabel,
  dirOf,
  formatCompactKRW,
  formatDirectional,
  formatPrice,
  toNum,
} from "@/lib/format";
import styles from "./RankingTable.module.css";

type RankTab = "value" | "gainers" | "losers";

const TABS = [
  { value: "value" as const, label: "거래대금순" },
  { value: "gainers" as const, label: "급상승" },
  { value: "losers" as const, label: "급하락" },
];

function signedRate(t: { change: string; change_rate: string } | undefined): number {
  if (!t) return 0;
  const r = toNum(t.change_rate);
  return t.change === "FALL" ? -r : t.change === "RISE" ? r : 0;
}

export default function RankingTable() {
  const router = useRouter();
  const tickers = useExchangeStore((s) => s.tickers);
  const markets = useExchangeStore((s) => s.markets);
  const specOf = (m: string) => markets.find((x) => x.market === m)?.tick_size;
  const [tab, setTab] = useState<RankTab>("value");

  const rows = useMemo(() => {
    const list = [...COIN_META];
    list.sort((a, b) => {
      const ta = tickers[a.market];
      const tb = tickers[b.market];
      if (tab === "value") {
        // 거래대금 내림차순. 2^53 초과 정밀 보존 위해 문자열 십진 비교
        return compareDecimal(tb?.acc_trade_price_24h ?? "0", ta?.acc_trade_price_24h ?? "0");
      }
      const diff = signedRate(tb) - signedRate(ta); // 급상승: 내림차순
      return tab === "gainers" ? diff : -diff;
    });
    return list;
  }, [tab, tickers]);

  return (
    <section className={`card ${styles.root}`} aria-label="실시간 순위">
      <header className={styles.header}>
        <h2 className={styles.title}>실시간 순위</h2>
        <SegmentedTab
          items={TABS}
          value={tab}
          onChange={setTab}
          variant="neutral"
          ariaLabel="순위 정렬 기준"
        />
      </header>

      <div className={styles.table} role="table" aria-label="코인 순위 목록">
        <div className={`${styles.row} ${styles.head}`} role="row">
          <span role="columnheader" className={styles.rank}>#</span>
          <span role="columnheader" className={styles.name}>코인</span>
          <span role="columnheader" className={styles.right}>현재가</span>
          <span role="columnheader" className={styles.right}>전일대비</span>
          <span role="columnheader" className={styles.right}>거래대금</span>
        </div>

        <div role="rowgroup">
          {rows.map((c, i) => {
            const t = tickers[c.market];
            const dir = t ? dirOf(t.change) : "flat";
            return (
              <div
                key={c.market}
                role="row"
                className={styles.row}
                tabIndex={0}
                aria-label={`${c.koreanName} 거래 화면으로 이동`}
                onClick={() => router.push(`/exchange/${c.market}`)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    router.push(`/exchange/${c.market}`);
                  }
                }}
              >
                <span role="cell" className={`${styles.rank} num`}>{i + 1}</span>
                <span role="cell" className={styles.name}>
                  <SymbolChip meta={c} size="sm" />
                  <span className={styles.nameText}>
                    <span className={styles.nameKo}>{c.koreanName}</span>
                    <span className={`${styles.nameSym} num`}>{c.symbol}/KRW</span>
                  </span>
                </span>
                <span role="cell" className={`${styles.right} num`}>
                  {t ? <TickCell value={toNum(t.trade_price)} format={(v) => formatPrice(v, specOf(c.market))} /> : "-"}
                </span>
                <span
                  role="cell"
                  className={`${styles.right} num dir-text`}
                  data-dir={dir}
                  aria-label={t ? ariaChangeLabel(t.change_rate, t.change) : undefined}
                >
                  {t ? formatDirectional(t.change_rate, t.change) : "-"}
                </span>
                <span role="cell" className={`${styles.right} ${styles.vol} num`}>
                  {t ? formatCompactKRW(t.acc_trade_price_24h) : "-"}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
