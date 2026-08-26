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
import { StaleBadge } from "@/components/StatusSignals";
import { useExchangeStore } from "@/lib/store";
import { COIN_META } from "@/lib/sim/engine";
import { compareDecimal } from "@/lib/decimal";
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
/* 레일 최소 행수. 관심 목록이 짧으면 홈 첫 화면 우측에 큰 빈 배경이 남는다.
   모자란 만큼 거래대금 상위로 채워 레일이 항상 정보로 서 있게 한다 */
const RAIL_MIN_ROWS = 6;

export default function WatchlistSidebar() {
  const router = useRouter();
  const tickers = useExchangeStore((s) => s.tickers);
  const markets = useExchangeStore((s) => s.markets);
  const connected = useExchangeStore((s) => s.connected);
  // 행별 markets.find(커밋당 O(행수×마켓수))를 없애는 Map 조회
  const specMap = useMemo(() => new Map(markets.map((m) => [m.market, m.tick_size])), [markets]);
  const favorites = useExchangeStore((s) => s.favorites);

  const favMetas = useMemo(
    () => favorites.map((m) => COIN_META.find((c) => c.market === m)).filter((c): c is CoinMeta => !!c),
    [favorites],
  );

  const usingFallback = favMetas.length === 0;

  const byVolume = useMemo(
    () =>
      [...COIN_META].sort((a, b) =>
        compareDecimal(
          tickers[b.market]?.acc_trade_price_24h ?? "0",
          tickers[a.market]?.acc_trade_price_24h ?? "0",
        ),
      ),
    [tickers],
  );

  const rows = useMemo(
    () => (usingFallback ? byVolume.slice(0, FALLBACK_COUNT) : favMetas),
    [usingFallback, favMetas, byVolume],
  );

  // 관심 목록이 최소 행수에 못 미치면 비관심 상위 거래대금으로 잔여를 채운다
  const filler = useMemo(() => {
    if (usingFallback || rows.length >= RAIL_MIN_ROWS) return [];
    const owned = new Set(rows.map((c) => c.market));
    return byVolume.filter((c) => !owned.has(c.market)).slice(0, RAIL_MIN_ROWS - rows.length);
  }, [usingFallback, rows, byVolume]);

  return (
    <section className={`card ${styles.root}`} aria-label="관심 코인">
      <header className={styles.header}>
        <h2 className={styles.title}>{usingFallback ? "인기 코인" : "관심 코인"}</h2>
        <span className={styles.count}>{rows.length}</span>
        {/* 낭독은 배너가 화면당 1회. 패널 배지는 시각 표식만 담당한다(A-5) */}
        {!connected && <StaleBadge label="지연" announce={false} />}
      </header>

      {usingFallback && (
        <p className={styles.hint}>관심 코인을 등록하면 여기에 모여요</p>
      )}

      <ul className={styles.list} data-stale={!connected ? "" : undefined}>
        {rows.concat(filler).map((c, i) => {
          const t = tickers[c.market];
          const fillerStart = filler.length > 0 && i === rows.length;
          const dir = t ? dirOf(t.change) : "flat";
          return (
            /* data-filler는 채움 구간 스타일 훅이다. 배경·패딩을 걸 때는 명판이 같은 li 안에
               있다는 점에 주의한다(첫 채움 행만 명판까지 그 면을 먹는다) */
            <li key={c.market} data-filler={i >= rows.length ? "" : undefined}>
              {/* 관심과 채움을 명판으로 가른다. 섞이면 관심 목록이 부풀어 보인다 */}
              {fillerStart && <span className={styles.groupLabel}>거래대금 상위</span>}
              <button
                type="button"
                className={styles.row}
                onClick={() => router.push(`/exchange/${c.market}`)}
              >
                <SymbolChip meta={c} size="sm" />
                <span className={styles.nameText}>
                  <span className={styles.nameKo}>{c.koreanName}</span>
                  <span className={`${styles.nameSym} num`}>{c.symbol}</span>
                </span>
                <span className={styles.figures}>
                  <span className={`${styles.price} num`}>
                    {t?.trade_price != null ? (
                      <TickCell value={toNum(t.trade_price)} valueStr={formatPrice(t.trade_price, specMap.get(c.market))} />
                    ) : (
                      "-"
                    )}
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
