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
import { StaleBadge, StaleRegion } from "@/components/StatusSignals";
import { useExchangeStore } from "@/lib/store";
import { useEffectIntensity } from "@/lib/useEffectIntensity";
import { COIN_META } from "@/lib/sim/engine";
import { dirOf, formatCompactKRW, formatCompactQty, formatPrice, toNum } from "@/lib/format";
import type { CoinMeta } from "@/lib/types";
import { useTradeRateGovernor } from "./useTradeRateGovernor";
import { effectiveLevel } from "./effectGovernor";
import styles from "./SummaryBar.module.css";

interface Props {
  market: string;
  meta: CoinMeta;
}

/** 트리거와 메뉴 사이 간격. absolute 시절 top: calc(100% + 6px)의 6px */
const MENU_GAP = 6;
/** 메뉴 하단과 뷰포트 사이 여백 */
const MENU_EDGE = 12;
/** 아무리 좁아도 항목 몇 개는 보여야 스크롤 손잡이가 생긴다 */
const MENU_MIN_HEIGHT = 160;

interface MenuPos {
  top: number;
  left: number;
  maxHeight: number;
}

export default function SummaryBar({ market, meta }: Props) {
  const router = useRouter();
  const ticker = useExchangeStore((s) => s.tickers[market]);
  const connected = useExchangeStore((s) => s.connected);
  const spec = useExchangeStore((s) => s.markets.find((m) => m.market === market));
  const tickSpec = spec?.tick_size;
  const qtyScale = spec?.qty_scale ?? 8;
  const favorites = useExchangeStore((s) => s.favorites);
  const toggleFavorite = useExchangeStore((s) => s.toggleFavorite);

  const [menuOpen, setMenuOpen] = useState(false);
  const [menuPos, setMenuPos] = useState<MenuPos | null>(null);
  const identRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuItemRefs = useRef<Array<HTMLButtonElement | null>>([]);

  // 메뉴는 fixed라 좌표를 직접 잡는다. absolute 시절 기준(ident 좌하단 + 6px)을 그대로 재현한다
  useEffect(() => {
    if (!menuOpen) {
      setMenuPos(null);
      return;
    }
    const place = () => {
      const r = identRef.current?.getBoundingClientRect();
      if (!r) return;
      const top = r.bottom + MENU_GAP;
      // 12종이 뷰포트를 넘기면 잘리는 대신 메뉴 안에서 스크롤한다
      setMenuPos({ top, left: r.left, maxHeight: Math.max(MENU_MIN_HEIGHT, window.innerHeight - top - MENU_EDGE) });
    };
    place();
    window.addEventListener("resize", place);
    return () => window.removeEventListener("resize", place);
  }, [menuOpen]);

  useEffect(() => {
    if (!menuOpen) return;
    const onDown = (e: MouseEvent) => {
      if (identRef.current && !identRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      // Escape는 닫고 트리거로 포커스 복귀. 항목이 많아 role=menu 대신 명시 포커스 관리
      if (e.key === "Escape") {
        setMenuOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [menuOpen]);

  // 열림 시 현재 마켓 항목(없으면 첫 항목)으로 포커스 진입
  useEffect(() => {
    if (!menuOpen) return;
    const idx = Math.max(
      0,
      COIN_META.findIndex((c) => c.market === market),
    );
    menuItemRefs.current[idx]?.focus();
  }, [menuOpen, market]);

  const onMenuKeyDown = (e: React.KeyboardEvent) => {
    const count = COIN_META.length;
    const idx = menuItemRefs.current.findIndex((el) => el === document.activeElement);
    let next = -1;
    if (e.key === "ArrowDown") next = idx < 0 ? 0 : (idx + 1) % count;
    else if (e.key === "ArrowUp") next = idx < 0 ? count - 1 : (idx - 1 + count) % count;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = count - 1;
    else return;
    e.preventDefault();
    menuItemRefs.current[next]?.focus();
  };

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
          ref={triggerRef}
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
        {menuOpen && menuPos && (
          <div
            className={styles.menu}
            role="menu"
            aria-label="마켓 목록"
            onKeyDown={onMenuKeyDown}
            style={{ top: menuPos.top, left: menuPos.left, maxHeight: menuPos.maxHeight }}
          >
            {COIN_META.map((c, i) => (
              <button
                key={c.market}
                ref={(el) => {
                  menuItemRefs.current[i] = el;
                }}
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

      {!connected && <StaleBadge label="지연" announce={false} />}

      {ticker ? (
        <StaleRegion stale={!connected} className={styles.price}>
          <span className={`${styles.cur} num dir-hero`} data-dir={dir}>
            {ticker.trade_price != null ? (
              <TickCell value={toNum(ticker.trade_price)} valueStr={formatPrice(ticker.trade_price, tickSpec)} level={heroLevel} popCap={gov.popCap} popScope="summary" throttleBonus={gov.throttleBonusMs} />
            ) : (
              "-"
            )}
          </span>
          {/* change_price는 가격 델타라 자릿수 스펙이 tick_size다. 미전달이면 sub-1 KRW 마켓 델타가 뭉개진다 */}
          <PricePill change={ticker.change} changeRate={ticker.change_rate} amount={ticker.change_price} amountSpec={tickSpec} variant="inline" />
        </StaleRegion>
      ) : (
        <Skeleton variant="text" width={240} height={28} />
      )}

      <div className={styles.stats}>
        {/* 고저는 통계값이지 방향이 아니다. 방향색을 입히면 상승·하락 문법이 희석된다(무채 유지) */}
        <div className={styles.stat}>
          <span className={styles.statLabel}>고가(24H)</span>
          <span className={`${styles.statValue} num`}>
            {ticker?.high_24h != null ? formatPrice(ticker.high_24h, tickSpec) : "-"}
          </span>
        </div>
        <div className={styles.stat}>
          <span className={styles.statLabel}>저가(24H)</span>
          <span className={`${styles.statValue} num`}>
            {ticker?.low_24h != null ? formatPrice(ticker.low_24h, tickSpec) : "-"}
          </span>
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
            {ticker ? (
              <>
                {formatCompactQty(ticker.acc_trade_volume_24h, qtyScale)} <span className="unit-suffix">{meta.symbol}</span>
              </>
            ) : (
              "-"
            )}
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
