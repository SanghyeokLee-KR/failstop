"use client";

/**
 * MarketListPanel: 검색·탭(원화/관심/보유)·정렬 헤더·코인 행 리스트 (§2.5).
 * 고정형 정책으로 드로어 모드 없음, 상주 단일 모드. 행 클릭 시 /exchange/[market] 이동.
 * 데이터: markets + tickers + favorites (+assets, 보유 탭).
 */

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import PanelCard from "@/components/PanelCard";
import TickCell from "@/components/TickCell";
import { useExchangeStore } from "@/lib/store";
import { useEffectIntensity } from "@/lib/useEffectIntensity";
import { COIN_META } from "@/lib/sim/engine";
import { dirOf, formatChangeRate, formatCompactKRW, formatPrice, toNum } from "@/lib/format";
import styles from "./MarketListPanel.module.css";

type ListTab = "krw" | "fav" | "held";
type SortKey = "name" | "price" | "change" | "volume";
type SortDir = "asc" | "desc";

// 목록은 셀 수가 많아 팝 예산을 걸지 않으면 동시 팝이 무제한 누적된다. 자체 scope로
// 격리해 현재가 히어로·호가 mid 셀 예산을 잠식하지 않게 한다(§2-5 패널 간 격리)
const LIST_POP_CAP = 8;

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

export default function MarketListPanel({ market }: { market: string }) {
  const router = useRouter();
  const tickers = useExchangeStore((s) => s.tickers);
  const favorites = useExchangeStore((s) => s.favorites);
  const assets = useExchangeStore((s) => s.assets);
  // 목록은 다마켓이라 마켓별 거버너를 걸지 않고 설정 강도를 그대로 소비한다(셀당 체결률 낮음)
  const effectConfig = useEffectIntensity();

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
          cmp = toNum(tickers[a.market]?.trade_price) - toNum(tickers[b.market]?.trade_price);
          break;
        case "change":
          cmp = signedRate(a.market) - signedRate(b.market);
          break;
        case "volume":
          cmp =
            toNum(tickers[a.market]?.acc_trade_price_24h) -
            toNum(tickers[b.market]?.acc_trade_price_24h);
          break;
      }
      return sortDir === "asc" ? cmp : -cmp;
    });
    return sorted;
  }, [query, tab, sortKey, sortDir, tickers, favorites, assets]);

  const emptyText =
    query.trim() !== ""
      ? "검색 결과가 없어요"
      : tab === "fav"
        ? "관심 코인이 없어요"
        : tab === "held"
          ? "보유 코인이 없어요"
          : "마켓 정보가 없어요";

  return (
    <PanelCard dense title="마켓">
      <div className={styles.wrap}>
        <div className={styles.search}>
          <span aria-hidden="true">⌕</span>
          <input
            type="text"
            value={query}
            placeholder="코인명·심볼 검색"
            aria-label="코인명 또는 심볼 검색"
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

        <div className={styles.tabs} role="tablist" aria-label="마켓 목록 구분">
          {TABS.map((t) => (
            <button
              key={t.value}
              type="button"
              role="tab"
              aria-selected={tab === t.value}
              className={styles.tabBtn}
              data-on={tab === t.value || undefined}
              onClick={() => setTab(t.value)}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className={styles.head}>
          {HEADERS.map((h) => {
            const on = sortKey === h.key;
            return (
              <button
                key={h.key}
                type="button"
                className={styles.headBtn}
                data-on={on || undefined}
                aria-label={`${h.label} 기준 정렬${on ? (sortDir === "desc" ? " (내림차순)" : " (오름차순)") : ""}`}
                onClick={() => onSort(h.key)}
              >
                {h.label}
                {on && <span aria-hidden="true">{sortDir === "desc" ? "▼" : "▲"}</span>}
              </button>
            );
          })}
        </div>

        <div className={styles.list}>
          {rows.length === 0 ? (
            <div className="empty-state">
              <span className="empty-state__title">{emptyText}</span>
            </div>
          ) : (
            rows.map((c) => {
              const t = tickers[c.market];
              const d = t ? dirOf(t.change) : "flat";
              const current = c.market === market;
              return (
                <button
                  key={c.market}
                  type="button"
                  className={styles.row}
                  data-current={current || undefined}
                  aria-current={current ? "page" : undefined}
                  aria-label={`${c.koreanName} 거래 화면으로 이동`}
                  onClick={() => {
                    if (!current) router.push(`/exchange/${c.market}`);
                  }}
                >
                  <span className={styles.nameCell}>
                    <span className={styles.nameText}>
                      <span className={styles.name}>{c.koreanName}</span>
                      <span className={`${styles.sym} num`}>{c.symbol}/KRW</span>
                    </span>
                  </span>
                  <span className="dir-text num" data-dir={d}>
                    {t ? <TickCell value={toNum(t.trade_price)} format={formatPrice} level={effectConfig.level} popCap={LIST_POP_CAP} popScope="market-list" /> : "-"}
                  </span>
                  <span className="dir-text num" data-dir={d}>
                    {t ? formatChangeRate(t.change_rate, t.change) : "-"}
                  </span>
                  <span className={`${styles.vol} num`}>
                    {t ? formatCompactKRW(t.acc_trade_price_24h) : "-"}
                  </span>
                </button>
              );
            })
          )}
        </div>
      </div>
    </PanelCard>
  );
}
