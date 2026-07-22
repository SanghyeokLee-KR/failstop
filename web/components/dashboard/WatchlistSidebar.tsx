"use client";

/**
 * WatchlistSidebar: 관심 코인 리스트(localStorage favorites).
 * 관심 목록이 비면 거래대금 상위 코인으로 대체하고 안내를 노출한다.
 * 행 클릭 시 /exchange/[market]. 현재가는 TickCell 플래시.
 */

import { useMemo } from "react";
import { useRouter } from "next/navigation";
import SymbolChip from "@/components/SymbolChip";
import TickCell from "@/components/TickCell";
import { useExchangeStore } from "@/lib/store";
import { COIN_META } from "@/lib/sim/engine";
import {
  ariaChangeLabel,
  dirOf,
  formatDirectional,
  formatPrice,
  toNum,
} from "@/lib/format";
import type { CoinMeta } from "@/lib/types";
import styles from "./WatchlistSidebar.module.css";

const FALLBACK_COUNT = 6;

export default function WatchlistSidebar() {
  const router = useRouter();
  const tickers = useExchangeStore((s) => s.tickers);
  const favorites = useExchangeStore((s) => s.favorites);

  const favMetas = useMemo(
    () => favorites.map((m) => COIN_META.find((c) => c.market === m)).filter((c): c is CoinMeta => !!c),
    [favorites],
  );

  const usingFallback = favMetas.length === 0;

  const rows = useMemo(() => {
    if (!usingFallback) return favMetas;
    return [...COIN_META]
      .sort((a, b) => toNum(tickers[b.market]?.acc_trade_price_24h) - toNum(tickers[a.market]?.acc_trade_price_24h))
      .slice(0, FALLBACK_COUNT);
  }, [usingFallback, favMetas, tickers]);

  return (
    <section className={`card ${styles.root}`} aria-label="관심 코인">
      <header className={styles.header}>
        <h2 className={styles.title}>{usingFallback ? "인기 코인" : "관심 코인"}</h2>
        <span className={styles.count}>{rows.length}</span>
      </header>

      {usingFallback && (
        <p className={styles.hint}>관심 코인을 등록하면 여기에 모여요</p>
      )}

      <ul className={styles.list}>
        {rows.map((c) => {
          const t = tickers[c.market];
          const dir = t ? dirOf(t.change) : "flat";
          return (
            <li key={c.market}>
              <button
                type="button"
                className={styles.row}
                aria-label={`${c.koreanName} 거래 화면으로 이동`}
                onClick={() => router.push(`/exchange/${c.market}`)}
              >
                <SymbolChip meta={c} size="sm" />
                <span className={styles.nameText}>
                  <span className={styles.nameKo}>{c.koreanName}</span>
                  <span className={`${styles.nameSym} num`}>{c.symbol}</span>
                </span>
                <span className={styles.figures}>
                  <span className={`${styles.price} num dir-text`} data-dir={dir}>
                    {t ? <TickCell value={toNum(t.trade_price)} format={formatPrice} /> : "-"}
                  </span>
                  <span
                    className={`${styles.change} num dir-text`}
                    data-dir={dir}
                    aria-label={t ? ariaChangeLabel(t.change_rate, t.change) : undefined}
                  >
                    {t ? formatDirectional(t.change_rate, t.change) : ""}
                  </span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
