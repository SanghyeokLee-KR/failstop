"use client";

/**
 * 자산 화면, §3.1 와이드 레이아웃.
 * 총 보유자산 요약 카드(전폭) + 보유 자산 테이블(1fr) + 보유 비중 카드(320px).
 * 실시간: ticker 채널로 평가금액·손익·수익률 재계산. 평가손익=(현재가-매수평균가)x수량.
 * 고정형 정책(§4): 가로 미디어 쿼리 없음, 열 접힘 없음.
 */

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import DataTable, { type DataColumn } from "@/components/DataTable";
import Skeleton from "@/components/Skeleton";
import PricePill from "@/components/PricePill";
import SymbolChip from "@/components/SymbolChip";
import { ConnectionBanner, StaleBadge } from "@/components/StatusSignals";
import { useExchangeStore, useLiveChannels } from "@/lib/store";
import { dest, type ChangeState } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import { compareDecimal, decimalsOf, fromScaledInt, mulDecimal, scaledIntTrunc } from "@/lib/decimal";
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

// 평가·손익·합산은 문자열 십진 연산으로 처리한다. float 산술은 금액·수량 NUMERIC(40,18) 정밀을 잃는다
function addDec(a: string, b: string): string {
  const s = Math.max(decimalsOf(a), decimalsOf(b));
  return fromScaledInt(scaledIntTrunc(a, s) + scaledIntTrunc(b, s), s);
}
function subDec(a: string, b: string): string {
  const s = Math.max(decimalsOf(a), decimalsOf(b));
  return fromScaledInt(scaledIntTrunc(a, s) - scaledIntTrunc(b, s), s);
}
// 수익률 소수부 자리수. 백분율 2자리 표시에 충분한 여유
const RATE_SCALE = 8;
// |a| / b 를 고정 자리수 비율 문자열로. b<=0이면 "0"(0 나눗셈·매수금액 0 보호)
function absRate(a: string, b: string): string {
  const da = decimalsOf(a);
  const db = decimalsOf(b);
  let n = scaledIntTrunc(a, da);
  if (n < 0n) n = -n;
  const d = scaledIntTrunc(b, db);
  if (d <= 0n) return "0";
  const scaled = (n * 10n ** BigInt(db + RATE_SCALE)) / (d * 10n ** BigInt(da));
  return fromScaledInt(scaled, RATE_SCALE);
}

interface Holding {
  currency: string;
  market: string;
  koreanName: string;
  symbol: string;
  brandColor: string;
  brandTextDark?: boolean;
  qty: string;
  avgBuyPrice: string;
  valuation: string;
  buyAmount: string;
  pnl: string;
  /** 절대값 수익률 문자열 (0.182 = 18.2%) */
  pnlRate: string;
  /** 표시 자리수 유도용 마켓 스펙 */
  qtyScale: number;
  tickSpec?: string;
  change: ChangeState;
  /** 현재가 티커 도착 여부. 미도착 행은 가격 파생 셀을 '-'로 표기 */
  priced: boolean;
}

function changeOf(pnl: string): ChangeState {
  const c = compareDecimal(pnl, "0");
  return c > 0 ? "RISE" : c < 0 ? "FALL" : "EVEN";
}

export default function AssetsPage() {
  const router = useRouter();
  const assets = useExchangeStore((s) => s.assets);
  const tickers = useExchangeStore((s) => s.tickers);
  const markets = useExchangeStore((s) => s.markets);
  const connected = useExchangeStore((s) => s.connected);
  const loadAccounts = useExchangeStore((s) => s.loadAccounts);
  const [hidden, setHidden] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  // loaded는 성공·실패 모두 확정한다. 계좌 로드 실패가 스켈레톤을 영구 고착시키지 않도록 분리한다
  const load = useCallback(() => {
    setFailed(false);
    loadAccounts()
      .then(() => setFailed(false))
      .catch(() => setFailed(true))
      .finally(() => setLoaded(true));
  }, [loadAccounts]);

  useEffect(() => {
    load();
  }, [load]);

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
          const qty = addDec(a.balance, a.locked);
          if (!meta || compareDecimal(qty, "0") <= 0) return null;
          const spec = markets.find((m) => m.market === market);
          const price = tickers[market]?.trade_price ?? "0";
          const avgBuyPrice = a.avg_buy_price;
          const valuation = mulDecimal(qty, price);
          const buyAmount = mulDecimal(qty, avgBuyPrice);
          const pnl = subDec(valuation, buyAmount);
          return {
            currency: a.currency,
            market,
            koreanName: meta.koreanName,
            symbol: meta.symbol,
            brandColor: meta.brandColor,
            brandTextDark: meta.brandTextDark,
            qty,
            avgBuyPrice,
            valuation,
            buyAmount,
            pnl,
            pnlRate: absRate(pnl, buyAmount),
            qtyScale: spec?.qty_scale ?? 8,
            tickSpec: spec?.tick_size,
            change: changeOf(pnl),
            priced: compareDecimal(price, "0") > 0,
          };
        })
        .filter((h): h is Holding => h !== null)
        .sort((a, b) => compareDecimal(b.valuation, a.valuation)),
    [assets, tickers, markets],
  );

  const cash = useMemo(() => {
    const krw = assets.find((a) => a.currency === "KRW");
    return krw ? addDec(krw.balance, krw.locked) : "0";
  }, [assets]);

  // 미가격 보유코인은 해당 행만 '-' 처리한다. 개별 티커 지연이 전체 화면을 고착시키지 않도록 !priced 의존을 뺀다
  const loading = !loaded;

  const totalValuation = holdings.reduce((s, h) => addDec(s, h.valuation), "0");
  const totalBuy = holdings.reduce((s, h) => addDec(s, h.buyAmount), "0");
  const totalAsset = addDec(cash, totalValuation);
  const totalPnl = subDec(totalValuation, totalBuy);
  const totalChange = changeOf(totalPnl);
  const totalRate = absRate(totalPnl, totalBuy);

  /* ===== 보유 비중 (상위 5 + 기타, 가로 막대) ===== */
  const weights = useMemo(() => {
    // 비중은 화면 막대 폭·aria 값이라 비율 number로 계산한다(금액 값이 아님)
    const totalNum = toNum(totalValuation);
    if (totalNum <= 0) return [];
    const top = holdings.slice(0, 5).map((h) => ({ label: h.symbol, name: h.koreanName, value: h.valuation }));
    const rest = holdings.slice(5).reduce((s, h) => addDec(s, h.valuation), "0");
    if (toNum(rest) > 0) top.push({ label: "기타", name: "", value: rest });
    return top.map((seg) => ({ ...seg, pct: (toNum(seg.value) / totalNum) * 100 }));
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
        sortValue: (h) => toNum(h.qty),
        render: (h) => (hidden ? HIDDEN : `${formatQty(h.qty, h.qtyScale)} ${h.symbol}`),
      },
      {
        key: "avg",
        label: "매수평균가",
        width: "minmax(110px, 1fr)",
        align: "right",
        sortValue: (h) => toNum(h.avgBuyPrice),
        render: (h) => (hidden ? HIDDEN : formatPrice(h.avgBuyPrice, h.tickSpec)),
      },
      {
        key: "valuation",
        label: "평가금액",
        width: "minmax(120px, 1fr)",
        align: "right",
        sortValue: (h) => toNum(h.valuation),
        render: (h) => (
          <b className={styles.valuation}>{hidden ? HIDDEN : h.priced ? formatKRW(h.valuation) : "-"}</b>
        ),
      },
      {
        key: "pnl",
        label: "평가손익",
        width: "minmax(160px, 1fr)",
        align: "right",
        sortValue: (h) => toNum(h.pnl),
        render: (h) =>
          hidden ? (
            HIDDEN
          ) : !h.priced ? (
            <span className="num">-</span>
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
      <ConnectionBanner />
      {loading ? (
        <div className={styles.loading}>
          <Skeleton variant="card" width="100%" height={120} />
          <Skeleton variant="card" width="100%" height={320} />
        </div>
      ) : failed ? (
        <section className="card" aria-label="자산 로드 실패">
          <div className="empty-state" role="alert">
            <span className="empty-state__mark" aria-hidden="true" />
            <span className="empty-state__title">자산을 불러오지 못했어요</span>
            <span className="empty-state__desc">서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요</span>
            <button type="button" className="btn btn--secondary" onClick={load}>
              다시 시도
            </button>
          </div>
        </section>
      ) : (
        <>
          {/* ===== 총 보유자산 요약 카드 (전폭) ===== */}
          <section className={`card ${styles.summary}`} aria-label="총 보유자산 요약">
            <div className={styles.statHero}>
              <span className={styles.heroPlate}>
                <span className="gauge-plate">총 평가금액</span>
                {!connected && <StaleBadge label="지연" announce={false} />}
              </span>
              <b className={`${styles.heroValue} num`} data-stale={!connected ? "" : undefined}>
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
              <b className={`${styles.statValueEmph} num`}>{hidden ? HIDDEN : formatKRW(totalAsset)}</b>
            </div>
            <button
              type="button"
              className={styles.hideBtn}
              aria-pressed={hidden}
              aria-label={hidden ? "자산 금액 보기" : "자산 금액 숨기기"}
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
                    <span className="empty-state__mark" aria-hidden="true" />
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
                  <span className="empty-state__mark" aria-hidden="true" />
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
