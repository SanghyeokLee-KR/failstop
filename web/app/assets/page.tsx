"use client";

/**
 * 자산 화면, §3.1 와이드 레이아웃.
 * 총 보유자산 요약 카드(전폭) + 보유 자산 테이블(1fr) + 보유 비중 카드(320px).
 * 실시간: ticker 채널로 평가금액·손익·수익률 재계산. 평가손익=(현재가-매수평균가)x수량.
 * 고정형 정책(§4): 가로 미디어 쿼리 없음, 열 접힘 없음.
 */

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import DataTable, { type DataColumn } from "@/components/DataTable";
import Skeleton from "@/components/Skeleton";
import PricePill from "@/components/PricePill";
import SymbolChip from "@/components/SymbolChip";
import { useExchangeStore, useLiveChannels } from "@/lib/store";
import { dest, type ChangeState } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import {
  dirOf,
  formatChangeRate,
  formatDirectionalAmount,
  formatKRW,
  formatPrice,
  formatQty,
  toNum,
} from "@/lib/format";
import styles from "./page.module.css";

const HIDDEN = "••••••";

interface Holding {
  currency: string;
  market: string;
  koreanName: string;
  symbol: string;
  brandColor: string;
  brandTextDark?: boolean;
  qty: number;
  avgBuyPrice: number;
  price: number;
  valuation: number;
  buyAmount: number;
  pnl: number;
  /** 절대값 수익률 (0.182 = 18.2%) */
  pnlRate: number;
  change: ChangeState;
}

function changeOf(pnl: number): ChangeState {
  return pnl > 0 ? "RISE" : pnl < 0 ? "FALL" : "EVEN";
}

export default function AssetsPage() {
  const router = useRouter();
  const assets = useExchangeStore((s) => s.assets);
  const tickers = useExchangeStore((s) => s.tickers);
  const loadAccounts = useExchangeStore((s) => s.loadAccounts);
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    void loadAccounts();
  }, [loadAccounts]);

  /* 보유코인 마켓 티커 + 내 자산 실시간 구독 */
  const heldMarkets = useMemo(
    () =>
      assets
        .filter((a) => a.currency !== "KRW" && toNum(a.balance) + toNum(a.locked) > 0)
        .map((a) => `KRW-${a.currency}`)
        .filter((m) => COIN_META.some((c) => c.market === m)),
    [assets],
  );
  useLiveChannels(
    useMemo(() => [...heldMarkets.map((m) => dest.ticker(m)), dest.myAsset], [heldMarkets]),
  );

  const holdings: Holding[] = useMemo(
    () =>
      assets
        .filter((a) => a.currency !== "KRW")
        .map((a): Holding | null => {
          const market = `KRW-${a.currency}`;
          const meta = COIN_META.find((c) => c.market === market);
          const qty = toNum(a.balance) + toNum(a.locked);
          if (!meta || qty <= 0) return null;
          const price = toNum(tickers[market]?.trade_price);
          const avgBuyPrice = toNum(a.avg_buy_price);
          const valuation = qty * price;
          const buyAmount = qty * avgBuyPrice;
          const pnl = valuation - buyAmount;
          return {
            currency: a.currency,
            market,
            koreanName: meta.koreanName,
            symbol: meta.symbol,
            brandColor: meta.brandColor,
            brandTextDark: meta.brandTextDark,
            qty,
            avgBuyPrice,
            price,
            valuation,
            buyAmount,
            pnl,
            pnlRate: buyAmount > 0 ? Math.abs(pnl / buyAmount) : 0,
            change: changeOf(pnl),
          };
        })
        .filter((h): h is Holding => h !== null)
        .sort((a, b) => b.valuation - a.valuation),
    [assets, tickers],
  );

  const cash = useMemo(() => {
    const krw = assets.find((a) => a.currency === "KRW");
    return krw ? toNum(krw.balance) + toNum(krw.locked) : 0;
  }, [assets]);

  const priced = holdings.every((h) => h.price > 0);
  const loading = assets.length === 0 || (holdings.length > 0 && !priced);

  const totalValuation = holdings.reduce((s, h) => s + h.valuation, 0);
  const totalBuy = holdings.reduce((s, h) => s + h.buyAmount, 0);
  const totalAsset = cash + totalValuation;
  const totalPnl = totalValuation - totalBuy;
  const totalChange = changeOf(totalPnl);
  const totalRate = totalBuy > 0 ? Math.abs(totalPnl / totalBuy) : 0;

  /* ===== 보유 비중 (상위 5 + 기타, 가로 막대) ===== */
  const weights = useMemo(() => {
    if (totalValuation <= 0) return [];
    const top = holdings.slice(0, 5).map((h) => ({ label: h.symbol, name: h.koreanName, value: h.valuation }));
    const rest = holdings.slice(5).reduce((s, h) => s + h.valuation, 0);
    if (rest > 0) top.push({ label: "기타", name: "", value: rest });
    return top.map((seg) => ({ ...seg, pct: (seg.value / totalValuation) * 100 }));
  }, [holdings, totalValuation]);

  const columns: DataColumn<Holding>[] = useMemo(
    () => [
      {
        key: "name",
        label: "코인",
        width: "minmax(150px, 1.1fr)",
        sortValue: (h) => h.koreanName,
        firstDir: "asc",
        render: (h) => (
          <span className={styles.coinCell}>
            <SymbolChip meta={h} size="sm" />
            <span className={styles.coinText}>
              <span className={styles.coinName}>{h.koreanName}</span>
              <span className={`${styles.coinSym} num`}>{h.symbol}</span>
            </span>
          </span>
        ),
      },
      {
        key: "qty",
        label: "보유수량",
        width: "minmax(120px, 1fr)",
        align: "right",
        sortValue: (h) => h.qty,
        render: (h) => (hidden ? HIDDEN : `${formatQty(h.qty, 8)} ${h.symbol}`),
      },
      {
        key: "avg",
        label: "매수평균가",
        width: "minmax(110px, 1fr)",
        align: "right",
        sortValue: (h) => h.avgBuyPrice,
        render: (h) => (hidden ? HIDDEN : formatPrice(h.avgBuyPrice)),
      },
      {
        key: "valuation",
        label: "평가금액",
        width: "minmax(120px, 1fr)",
        align: "right",
        sortValue: (h) => h.valuation,
        render: (h) => (
          <b className={styles.valuation}>{hidden ? HIDDEN : formatKRW(h.valuation)}</b>
        ),
      },
      {
        key: "pnl",
        label: "평가손익",
        width: "minmax(160px, 1fr)",
        align: "right",
        sortValue: (h) => h.pnl,
        render: (h) =>
          hidden ? (
            HIDDEN
          ) : (
            <span className="dir-text num" data-dir={dirOf(h.change)}>
              {formatDirectionalAmount(h.pnl, h.change)} ({formatChangeRate(h.pnlRate, h.change)})
            </span>
          ),
      },
    ],
    [hidden],
  );

  return (
    <main className={styles.page} aria-busy={loading}>
      {loading ? (
        <div className={styles.loading}>
          <Skeleton variant="card" width="100%" height={120} />
          <Skeleton variant="card" width="100%" height={320} />
        </div>
      ) : (
        <>
          {/* ===== 총 보유자산 요약 카드 (전폭) ===== */}
          <section className={`card ${styles.summary}`} aria-label="총 보유자산 요약">
            <div className={styles.statHero}>
              <span className="gauge-plate">총 평가금액</span>
              <b className={`${styles.heroValue} num`}>
                {hidden ? HIDDEN : formatKRW(totalValuation)}
                <small>원</small>
              </b>
            </div>
            <div className={styles.stat}>
              <span className="gauge-plate">총 매수금액</span>
              <b className={`${styles.statValue} num`}>{hidden ? HIDDEN : formatKRW(totalBuy)}</b>
            </div>
            <div className={styles.stat}>
              <span className="gauge-plate">평가손익</span>
              {hidden ? (
                <b className={`${styles.statValue} num`}>{HIDDEN}</b>
              ) : (
                <PricePill change={totalChange} changeRate={totalRate} amount={totalPnl} variant="pill" />
              )}
            </div>
            <div className={styles.stat}>
              <span className="gauge-plate">보유현금</span>
              <b className={`${styles.statValue} num`}>{hidden ? HIDDEN : formatKRW(cash)}</b>
            </div>
            <div className={styles.stat}>
              <span className="gauge-plate">총 보유자산</span>
              <b className={`${styles.statValue} num`}>{hidden ? HIDDEN : formatKRW(totalAsset)}</b>
            </div>
            <button
              type="button"
              className={styles.hideBtn}
              aria-pressed={hidden}
              onClick={() => setHidden((h) => !h)}
            >
              {hidden ? "보기" : "숨김"}
            </button>
          </section>

          {/* ===== 보유 자산 테이블 + 보유 비중 카드 (1fr 320px 고정 2열) ===== */}
          <div className={styles.grid}>
            <section className={`card ${styles.tableCard}`} aria-label="보유 자산 목록">
              <DataTable
                columns={columns}
                rows={holdings}
                rowKey={(h) => h.currency}
                defaultSort={{ key: "valuation", dir: "desc" }}
                rowHeight={48}
                onRowClick={(h) => router.push(`/exchange/${h.market}`)}
                rowAriaLabel={(h) => `${h.koreanName} 거래 화면으로 이동`}
                ariaLabel="보유 자산"
                empty={
                  <div className="empty-state">
                    <span className={styles.emptyMark} aria-hidden="true" />
                    <span className="empty-state__title">아직 보유한 코인이 없어요</span>
                    <span className="empty-state__desc">첫 코인을 담아 포트폴리오를 시작해 보세요</span>
                    <Link href="/exchange" className="btn btn--secondary">
                      거래소 가기
                    </Link>
                  </div>
                }
              />
            </section>

            <section className={`card ${styles.weightCard}`} aria-label="보유 비중">
              <h2 className={`${styles.weightTitle} gauge-plate`}>보유 비중</h2>
              {weights.length === 0 ? (
                <div className="empty-state">
                  <span className={styles.emptyMark} aria-hidden="true" />
                  <span className="empty-state__title">보유 자산이 없어요</span>
                </div>
              ) : (
                <ul className={styles.weightList}>
                  {weights.map((w) => (
                    <li key={w.label} className={styles.weightRow}>
                      <span className={styles.weightLabel}>
                        <b>{w.label}</b>
                        {w.name !== "" && <span className={styles.weightName}>{w.name}</span>}
                      </span>
                      <span
                        className={styles.weightTrack}
                        role="meter"
                        aria-label={`${w.label} 비중`}
                        aria-valuenow={Math.round(w.pct)}
                        aria-valuemin={0}
                        aria-valuemax={100}
                      >
                        <span className={styles.weightFill} style={{ width: `${w.pct.toFixed(1)}%` }} />
                        <span className={`${styles.weightTicks} rule-rail`} aria-hidden="true" />
                      </span>
                      <b className={`${styles.weightPct} num`}>{w.pct.toFixed(1)}%</b>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </>
      )}
    </main>
  );
}
