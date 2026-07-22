"use client";

/**
 * SummaryBar: 종목 식별·현재가·등락·24H 스탯·관심 토글·마켓 전환 드롭다운 (§2.5).
 * 1280px 고정 기준 구성: 고가·저가·거래대금·거래량 전부 노출, 1행 유지.
 */

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import SymbolChip from "@/components/SymbolChip";
import TickCell from "@/components/TickCell";
import PricePill from "@/components/PricePill";
import Skeleton from "@/components/Skeleton";
import { useExchangeStore } from "@/lib/store";
import { useEffectIntensity } from "@/lib/useEffectIntensity";
import { COIN_META } from "@/lib/sim/engine";
import { dirOf, formatCompactKRW, formatPrice, toNum } from "@/lib/format";
import type { CoinMeta } from "@/lib/types";
import { useTradeRateGovernor } from "./useTradeRateGovernor";
import { effectiveLevel } from "./effectGovernor";
import styles from "./SummaryBar.module.css";

interface Props {
  market: string;
  meta: CoinMeta;
}

export default function SummaryBar({ market, meta }: Props) {
  const router = useRouter();
  const ticker = useExchangeStore((s) => s.tickers[market]);
  const favorites = useExchangeStore((s) => s.favorites);
  const toggleFavorite = useExchangeStore((s) => s.toggleFavorite);

  const [menuOpen, setMenuOpen] = useState(false);
  const identRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onDown = (e: MouseEvent) => {
      if (identRef.current && !identRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setMenuOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [menuOpen]);

  const config = useEffectIntensity();
  const gov = useTradeRateGovernor(market);
  // 자동 모드면 거버너가 선택 단계를 상한으로 하향한다(§3-5)
  const heroLevel = config.mode === "auto" ? effectiveLevel(config.level, gov) : config.level;

  const favorite = favorites.includes(market);
  const dir = ticker ? dirOf(ticker.change) : "flat";

  return (
    <div className={styles.root}>
      <div className={styles.ident} ref={identRef}>
        <button
          type="button"
          className={styles.identBtn}
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          aria-label={`마켓 전환, 현재 ${meta.koreanName}`}
          onClick={() => setMenuOpen((v) => !v)}
        >
          <SymbolChip meta={meta} size="sm" />
          <span className={styles.name}>{meta.koreanName}</span>
          <span className={`${styles.symbol} num`}>{market}</span>
          <span className={styles.caret} aria-hidden="true">
            ▾
          </span>
        </button>
        {menuOpen && (
          <div className={styles.menu} role="menu" aria-label="마켓 목록">
            {COIN_META.map((c) => (
              <button
                key={c.market}
                type="button"
                role="menuitem"
                className={styles.menuItem}
                data-on={c.market === market || undefined}
                onClick={() => {
                  setMenuOpen(false);
                  if (c.market !== market) router.push(`/exchange/${c.market}`);
                }}
              >
                <SymbolChip meta={c} size="sm" />
                <span className={styles.menuItemName}>{c.koreanName}</span>
                <span className={`${styles.menuItemSym} num`}>{c.symbol}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <Link
        href={`/community/${market}`}
        className={styles.discuss}
        aria-label={`${meta.koreanName} 토론 게시판으로 이동`}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M4 5h16v11H9l-4 3v-3H4z"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinejoin="round"
          />
        </svg>
        토론
      </Link>

      {ticker ? (
        <div className={styles.price}>
          <span className={`${styles.cur} num dir-hero`} data-dir={dir}>
            <TickCell value={toNum(ticker.trade_price)} format={formatPrice} level={heroLevel} popCap={gov.popCap} popScope="summary" throttleBonus={gov.throttleBonusMs} />
          </span>
          <PricePill change={ticker.change} changeRate={ticker.change_rate} amount={ticker.change_price} variant="inline" />
        </div>
      ) : (
        <Skeleton variant="text" width={240} height={28} />
      )}

      <div className={styles.stats}>
        <div className={styles.stat}>
          <span className={styles.statLabel}>고가(24H)</span>
          <span className={`${styles.statValue} num`}>{ticker ? formatPrice(ticker.high_24h) : "-"}</span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statLabel}>저가(24H)</span>
          <span className={`${styles.statValue} num`}>{ticker ? formatPrice(ticker.low_24h) : "-"}</span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statLabel}>거래대금(24H)</span>
          <span className={`${styles.statValue} num`}>
            {ticker ? formatCompactKRW(ticker.acc_trade_price_24h) : "-"}
          </span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statLabel}>거래량(24H)</span>
          <span className={`${styles.statValue} num`}>
            {ticker ? `${formatCompactKRW(ticker.acc_trade_volume_24h)} ${meta.symbol}` : "-"}
          </span>
        </div>
        <button
          type="button"
          className={styles.star}
          data-on={favorite || undefined}
          aria-pressed={favorite}
          aria-label={`관심 ${favorite ? "해제" : "등록"}`}
          onClick={() => toggleFavorite(market)}
        >
          {/* 별 글리프(U+2605)는 스타일 게이트 이모지 범위라 SVG로 대체 */}
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M12 3.6l2.5 5.4 5.9.7-4.4 4 1.2 5.8L12 16.6l-5.2 2.9 1.2-5.8-4.4-4 5.9-.7z"
              fill={favorite ? "currentColor" : "none"}
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      </div>
    </div>
  );
}
