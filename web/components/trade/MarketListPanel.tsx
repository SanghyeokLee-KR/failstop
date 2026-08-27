"use client";

/**
 * MarketListPanel: 검색·탭(원화/관심/보유)·정렬 헤더·코인 행 리스트 (§2.5).
 * 고정형 정책으로 드로어 모드 없음, 상주 단일 모드. 행 클릭 시 /exchange/[market] 이동.
 * 데이터: markets + tickers + favorites (+assets, 보유 탭).
 */

import { memo, useCallback, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import PanelCard from "@/components/PanelCard";
import SegmentedTab from "@/components/SegmentedTab";
import { StaleBadge } from "@/components/StatusSignals";
import { useExchangeStore } from "@/lib/store";
import { COIN_META } from "@/lib/sim/engine";
import { compareDecimal } from "@/lib/decimal";
import type { CoinMeta, TickerMessage } from "@/lib/types";
import { dirOf, formatChangeRate, formatCompactKRW, formatPrice, toNum } from "@/lib/format";
import styles from "./MarketListPanel.module.css";

type ListTab = "krw" | "fav" | "held";
type SortKey = "name" | "price" | "change" | "volume";
type SortDir = "asc" | "desc";

const TABS: { value: ListTab; label: string }[] = [
  { value: "krw", label: "원화" },
  { value: "fav", label: "관심" },
  { value: "held", label: "보유" },
];

const HEADERS: { key: SortKey; label: string }[] = [
  { key: "name", label: "한글명" },
  { key: "price", label: "현재가" },
  { key: "change", label: "전일대비" },
  { key: "volume", label: "거래대금" },
];

/**
 * 마켓 행. memo로 해당 마켓 ticker 참조가 바뀐 행만 리렌더한다.
 * 패널은 전 마켓 ticker를 구독하므로 memo 없이는 임의 마켓 티커 1건마다 전 행이
 * 포맷터(BigInt 경로 포함)를 재실행한다. props는 전부 원시값·안정 참조로 유지한다.
 */
const MarketRow = memo(function MarketRow({
  meta,
  ticker,
  tickSpec,
  current,
  onNavigate,
}: {
  meta: CoinMeta;
  ticker: TickerMessage | undefined;
  tickSpec: string | undefined;
  current: boolean;
  onNavigate: (market: string) => void;
}) {
  const d = ticker ? dirOf(ticker.change) : "flat";
  const navigate = () => {
    if (!current) onNavigate(meta.market);
  };
  return (
    // 표 시맨틱(WCAG 1.3.1): role=row + cell. 네이티브 button role을 덮지 않도록 div+onKeyDown으로
    // 두고 Enter·Space 활성을 직접 처리한다(RankingTable 패턴)
    <div
      role="row"
      className={styles.row}
      data-current={current || undefined}
      aria-current={current ? "page" : undefined}
      tabIndex={0}
      aria-label={`${meta.koreanName} 거래 화면으로 이동`}
      onClick={navigate}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          navigate();
        }
      }}
    >
      <span role="cell" className={styles.nameCell}>
        <span className={styles.nameText}>
          <span className={styles.name}>{meta.koreanName}</span>
          <span className={`${styles.sym} num`}>{meta.symbol}/KRW</span>
        </span>
      </span>
      <span role="cell" className="dir-text num" data-dir={d}>
        {/* 셀별 틱 구독·플래시 비용을 없앤다. 방향은 행 data-dir 색과
            전일대비 부호·화살표가 전달하고, 현재가 히어로가 상세 플래시를 담당한다(§16) */}
        {ticker?.trade_price != null ? formatPrice(ticker.trade_price, tickSpec) : "-"}
      </span>
      <span role="cell" className="dir-text num" data-dir={d}>
        {ticker ? formatChangeRate(ticker.change_rate, ticker.change) : "-"}
      </span>
      <span role="cell" className={`${styles.vol} num`}>
        {ticker ? formatCompactKRW(ticker.acc_trade_price_24h) : "-"}
      </span>
    </div>
  );
});

export default function MarketListPanel({ market }: { market: string }) {
  const router = useRouter();
  const tickers = useExchangeStore((s) => s.tickers);
  const markets = useExchangeStore((s) => s.markets);
  const favorites = useExchangeStore((s) => s.favorites);
  const assets = useExchangeStore((s) => s.assets);
  const connected = useExchangeStore((s) => s.connected);
  // 행별 markets.find(커밋당 O(행수×마켓수))를 없애는 Map 조회
  const specMap = useMemo(() => new Map(markets.map((m) => [m.market, m.tick_size])), [markets]);
  const onNavigate = useCallback(
    (m: string) => {
      router.push(`/exchange/${m}`);
    },
    [router],
  );

  const [query, setQuery] = useState("");
  const [tab, setTab] = useState<ListTab>("krw");
  const [sortKey, setSortKey] = useState<SortKey>("volume");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  const onSort = (key: SortKey) => {
    if (key === sortKey) {
      setSortDir((d) => (d === "desc" ? "asc" : "desc"));
      return;
    }
    setSortKey(key);
    setSortDir(key === "name" ? "asc" : "desc");
  };

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    const filtered = COIN_META.filter((c) => {
      if (tab === "fav" && !favorites.includes(c.market)) return false;
      if (tab === "held") {
        const held = assets.some(
          (a) => a.currency === c.symbol && toNum(a.balance) + toNum(a.locked) > 0,
        );
        if (!held) return false;
      }
      if (!q) return true;
      return (
        c.koreanName.toLowerCase().includes(q) ||
        c.symbol.toLowerCase().includes(q) ||
        c.market.toLowerCase().includes(q)
      );
    });

    // 정렬 값: 전일대비는 부호 있는 등락률, 거래대금은 24H 누적
    const signedRate = (m: string) => {
      const t = tickers[m];
      if (!t) return 0;
      const r = toNum(t.change_rate);
      return t.change === "FALL" ? -r : t.change === "RISE" ? r : 0;
    };
    const sorted = [...filtered].sort((a, b) => {
      let cmp = 0;
      switch (sortKey) {
        case "name":
          cmp = a.koreanName.localeCompare(b.koreanName, "ko");
          break;
        case "price":
          // 근접 두 가격의 float 왕복 순서 역전을 막는다(문자열 십진 비교)
          cmp = compareDecimal(
            tickers[a.market]?.trade_price ?? "0",
            tickers[b.market]?.trade_price ?? "0",
          );
          break;
        case "change":
          cmp = signedRate(a.market) - signedRate(b.market);
          break;
        case "volume":
          // 거래대금은 2^53 초과 가능. 문자열 십진 비교로 정렬 키 정밀 보존
          cmp = compareDecimal(
            tickers[a.market]?.acc_trade_price_24h ?? "0",
            tickers[b.market]?.acc_trade_price_24h ?? "0",
          );
          break;
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
    return sorted;
  }, [query, tab, sortKey, sortDir, tickers, favorites, assets]);

  const emptyText =
    query.trim() !== ""
      ? "검색 결과 없음"
      : tab === "fav"
        ? "관심 코인 없음"
        : tab === "held"
          ? "보유 코인 없음"
          : "마켓 정보 없음";

  return (
    <PanelCard
      dense
      title="마켓"
      stale={!connected}
      actions={!connected ? <StaleBadge label="지연" announce={false} /> : undefined}
    >
      <div className={styles.wrap}>
        <div className={`focus-ring-owner ${styles.search}`}>
          <span aria-hidden="true">⌕</span>
          <input
            type="text"
            value={query}
            placeholder="코인명·심볼 검색"
            aria-label="코인명 또는 심볼 검색"
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

        <div className={styles.tabs}>
          <SegmentedTab items={TABS} value={tab} onChange={setTab} ariaLabel="마켓 목록 구분" />
        </div>

        <div className={styles.grid} role="table" aria-label="마켓 시세 목록">
          {/* 정렬 상태는 aria-sort로 전달한다. 정렬 컨트롤은 네이티브 button을 유지하고 columnheader
              셀이 그 위를 감싸 button role을 덮지 않는다(WCAG 4.1.2) */}
          <div className={styles.head} role="row">
            {HEADERS.map((h) => {
              const on = sortKey === h.key;
              return (
                <div
                  key={h.key}
                  role="columnheader"
                  className={styles.headCell}
                  aria-sort={on ? (sortDir === "desc" ? "descending" : "ascending") : "none"}
                >
                  <button
                    type="button"
                    className={styles.headBtn}
                    data-on={on || undefined}
                    aria-label={`${h.label} 기준 정렬${on ? (sortDir === "desc" ? " (내림차순)" : " (오름차순)") : ""}`}
                    onClick={() => onSort(h.key)}
                  >
                    {h.label}
                    {on && <span aria-hidden="true">{sortDir === "desc" ? "▼" : "▲"}</span>}
                  </button>
                </div>
              );
            })}
          </div>

          {/* rowgroup은 row 자식이 필수라 빈 상태에선 role을 내려 ARIA 구조 위반을 피한다 */}
          <div className={styles.list} role={rows.length > 0 ? "rowgroup" : undefined}>
            {rows.length === 0 ? (
              <div className="empty-state">
                <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
                <span className="empty-state__title">{emptyText}</span>
              </div>
            ) : (
              rows.map((c) => (
                <MarketRow
                  key={c.market}
                  meta={c}
                  ticker={tickers[c.market]}
                  tickSpec={specMap.get(c.market)}
                  current={c.market === market}
                  onNavigate={onNavigate}
                />
              ))
            )}
          </div>
        </div>
      </div>
    </PanelCard>
  );
}
