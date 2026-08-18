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
import { useExchangeStore, useLiveChannels, useMarketSpecs } from "@/lib/store";
import { dest, type ChangeState } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import {
  addDecimal,
  compareDecimal,
  decimalsOf,
  fromScaledInt,
  mulDecimal,
  scaledIntTrunc,
  subDecimal,
} from "@/lib/decimal";
import { isCostKnown, unknownCostQty } from "@/lib/orderContract";
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

// KRW 금액 표시 자릿수. formatKRW와 같은 정책이라 평가금액·평가손익이 같은 자릿수로 정렬된다
const KRW_DECIMALS = 0;

// 평가·손익·합산은 lib/decimal의 문자열 십진 연산으로 처리한다.
// float 산술은 금액·수량 NUMERIC(40,18) 정밀을 잃는다

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
  /** 보유 전량(available + locked) */
  qty: string;
  /** 원가 확보분의 평단가. costKnown이 false면 산출 불가라 표시하지 않는다 */
  avgBuyPrice: string;
  /** 보유 전량 평가금액 */
  valuation: string;
  /** 원가 확보 수량 (§4-C-1 cost_basis_qty) */
  costQty: string;
  /** 원가 미상 수량. balance + locked - cost_basis_qty 유도값(별도 필드 없음, K3) */
  unknownQty: string;
  /** 원가 확보분 평가금액. 손익 분자는 이 값이다 */
  costValuation: string;
  /** 원가 미상분 평가금액. 손익 계산에서 제외하고 별도로 보인다 */
  unknownValuation: string;
  /** 총매수. cost_basis_amount 그대로다. 수량×평단가로 재계산하면 반올림만큼 어긋난다 */
  buyAmount: string;
  /** 원가 확보분 한정 평가손익 */
  pnl: string;
  /** 절대값 수익률 문자열 (0.182 = 18.2%) */
  pnlRate: string;
  /** 표시 자리수 유도용 마켓 스펙 */
  qtyScale: number;
  tickSpec?: string;
  change: ChangeState;
  /** 현재가 티커 도착 여부. 미도착 행은 가격 파생 셀을 '-'로 표기 */
  priced: boolean;
  /** 원가 확보 여부. cost_basis_qty = 0이 판별자다(§4-C-1 K4). false면 손익 산출 불가 */
  costKnown: boolean;
}

function changeOf(pnl: string): ChangeState {
  const c = compareDecimal(pnl, "0");
  return c > 0 ? "RISE" : c < 0 ? "FALL" : "EVEN";
}

export default function AssetsPage() {
  const router = useRouter();
  const assets = useExchangeStore((s) => s.assets);
  const tickers = useExchangeStore((s) => s.tickers);
  // 보유수량·매수평균가 표시 자릿수는 마켓 스펙에서 유도한다. 미로드면 폴백 자릿수로 위조된다
  const markets = useMarketSpecs();
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
          const qty = addDecimal(a.balance, a.locked);
          if (!meta || compareDecimal(qty, "0") <= 0) return null;
          const spec = markets.find((m) => m.market === market);
          const price = tickers[market]?.trade_price ?? "0";
          const costQty = a.cost_basis_qty;
          const unknownQty = unknownCostQty(a.balance, a.locked, costQty);
          // 총매수는 서버 원본이다. cost_basis_qty × avg_buy_price로 곱하면 평단가가 quote scale
          // 반올림값이라 원 단위로 어긋난다(§4-C-1)
          const buyAmount = a.cost_basis_amount;
          const costValuation = mulDecimal(costQty, price);
          const pnl = subDecimal(costValuation, buyAmount);
          return {
            currency: a.currency,
            market,
            koreanName: meta.koreanName,
            symbol: meta.symbol,
            brandColor: meta.brandColor,
            brandTextDark: meta.brandTextDark,
            qty,
            avgBuyPrice: a.avg_buy_price,
            valuation: mulDecimal(qty, price),
            costQty,
            unknownQty,
            costValuation,
            unknownValuation: mulDecimal(unknownQty, price),
            buyAmount,
            pnl,
            pnlRate: absRate(pnl, buyAmount),
            qtyScale: spec?.qty_scale ?? 8,
            tickSpec: spec?.tick_size,
            change: changeOf(pnl),
            priced: compareDecimal(price, "0") > 0,
            costKnown: isCostKnown(costQty),
          };
        })
        .filter((h): h is Holding => h !== null)
        .sort((a, b) => compareDecimal(b.valuation, a.valuation)),
    [assets, tickers, markets],
  );

  const cash = useMemo(() => {
    const krw = assets.find((a) => a.currency === "KRW");
    return krw ? addDecimal(krw.balance, krw.locked) : "0";
  }, [assets]);

  // 미가격 보유코인은 해당 행만 '-' 처리한다. 개별 티커 지연이 전체 화면을 고착시키지 않도록 !priced 의존을 뺀다
  const loading = !loaded;

  const totalValuation = holdings.reduce((s, h) => addDecimal(s, h.valuation), "0");
  const totalAsset = addDecimal(cash, totalValuation);
  // 손익은 원가 확보분만으로 낸다. 총평가에서 총매수를 빼면 원가 미상분 평가액이 통째로
  // 수익으로 계상된다. 두 항의 관계는 총평가 = (총매수 + 평가손익) + 원가 미상 평가금액이다.
  // 티커 미도착 행은 네 합계 모두에서 뺀다. 평가액만 0으로 들어가면 그 행의 매수금액이
  // 통째로 손실로 그려진다
  const priced = holdings.filter((h) => h.priced);
  const totalBuy = priced.reduce((s, h) => addDecimal(s, h.buyAmount), "0");
  const totalCostValuation = priced.reduce((s, h) => addDecimal(s, h.costValuation), "0");
  const totalUnknown = priced.reduce((s, h) => addDecimal(s, h.unknownValuation), "0");
  const totalPnl = subDecimal(totalCostValuation, totalBuy);
  const totalChange = changeOf(totalPnl);
  const totalRate = absRate(totalPnl, totalBuy);
  // 원가 확보분이 전혀 없으면 총매수도 손익도 산출 대상이 아니다. 0원으로 그리면 전액 수익으로
  // 읽힌다. 보유가 아예 없는 계정의 정당한 0원은 가리지 않는다
  const costTotalKnown = holdings.length === 0 || compareDecimal(totalBuy, "0") > 0;
  // 표시 조건은 수량 기준이다. 평가액 기준으로 걸면 티커 도착 전후로 스탯이 나타나 시프트가 난다
  const hasUnknownCost = holdings.some((h) => compareDecimal(h.unknownQty, "0") > 0);

  /* ===== 보유 비중 (상위 5 + 기타, 가로 막대) ===== */
  const weights = useMemo(() => {
    // 비중은 화면 막대 폭·aria 값이라 비율 number로 계산한다(금액 값이 아님)
    const totalNum = toNum(totalValuation);
    if (totalNum <= 0) return [];
    const top = holdings.slice(0, 5).map((h) => ({ label: h.symbol, name: h.koreanName, value: h.valuation }));
    const rest = holdings.slice(5).reduce((s, h) => addDecimal(s, h.valuation), "0");
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
        width: "minmax(140px, 1.1fr)",
        align: "right",
        sortValue: (h) => toNum(h.qty),
        render: (h) =>
          hidden ? (
            HIDDEN
          ) : (
            <span className={styles.qtyCell}>
              <span>{`${formatQty(h.qty, h.qtyScale)} ${h.symbol}`}</span>
              {/* 일부만 원가가 잡힌 보유. 손익 분모에서 빠진 수량을 그 자리에서 밝힌다 */}
              {h.costKnown && compareDecimal(h.unknownQty, "0") > 0 && (
                <span className={styles.qtyNote}>원가 미상 {formatQty(h.unknownQty, h.qtyScale)}</span>
              )}
            </span>
          ),
      },
      {
        key: "avg",
        label: "매수평균가",
        width: "minmax(120px, 1fr)",
        align: "right",
        sortValue: (h) => toNum(h.avgBuyPrice),
        // "0"은 평단가 0원이 아니라 산출 불가다. 판별자는 cost_basis_qty = 0 (§4-C-1 K4)
        render: (h) =>
          hidden ? (
            HIDDEN
          ) : h.costKnown ? (
            formatPrice(h.avgBuyPrice, h.tickSpec)
          ) : (
            <span className={styles.unknownMark}>원가 미상</span>
          ),
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
          ) : !h.costKnown ? (
            // 원가가 없으면 손익은 계산 대상이 아니다. 빈칸이 아니라 사유를 적는다
            <span className={styles.unknownMark}>산출 불가 (원가 미상)</span>
          ) : !h.priced ? (
            <span className="num">-</span>
          ) : (
            <span className="dir-text num" data-dir={dirOf(h.change)}>
              {/* 평가손익은 코인 가격이 아니라 KRW 금액이다. 자릿수를 tickSpec에서 유도하면
                  소수틱 마켓 행만 소수부가 붙어 같은 열의 평가금액(formatKRW, 0자리)과 어긋난다.
                  같은 0자리 정책으로 통일해 열 간 자릿수를 맞춘다 */}
              {formatDirectionalAmount(h.pnl, h.change, KRW_DECIMALS)} ({formatChangeRate(h.pnlRate, h.change)})
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
              <b className={`${styles.statValue} num`}>
                {hidden ? HIDDEN : costTotalKnown ? formatKRW(totalBuy) : "산출 불가"}
              </b>
            </div>
            <div className={styles.stat}>
              <span className="gauge-plate">평가손익</span>
              {hidden ? (
                <b className={`${styles.statValue} num`}>{HIDDEN}</b>
              ) : costTotalKnown ? (
                <PricePill
                  change={totalChange}
                  changeRate={totalRate}
                  amount={totalPnl}
                  amountSpec={KRW_DECIMALS}
                  variant="pill"
                />
              ) : (
                <b className={styles.statValue}>산출 불가</b>
              )}
            </div>
            {/* 총평가 = (총매수 + 평가손익) + 원가 미상 평가금액. 이 항이 없으면 세 수가
                맞지 않는 이유를 읽을 수 없다. 시드·입금 취득분은 원가가 0이 아니라 미상이다 */}
            {hasUnknownCost && (
              <div className={styles.stat}>
                <span className="gauge-plate">원가 미상</span>
                <b className={`${styles.statValue} num`}>{hidden ? HIDDEN : formatKRW(totalUnknown)}</b>
                <span className={styles.statNote}>손익 산출 제외</span>
              </div>
            )}
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
