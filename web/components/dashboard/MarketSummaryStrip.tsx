"use client";

/**
 * MarketSummaryStrip: 시장 개관 스트립.
 * 좌: 전체 집계(총 거래대금, 상승·하락·보합 코인 수). 우: 대표 코인 미니 스파크라인 카드.
 * 데이터: 전 마켓 tickers(집계) + getCandles(대표 코인 추세). 방향은 색+부호+화살표 병기.
 */

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Sparkline from "./Sparkline";
import { StaleBadge } from "@/components/StatusSignals";
import { useExchangeStore } from "@/lib/store";
import { getDataSource } from "@/lib/api/datasource";
import { COIN_META } from "@/lib/sim/engine";
import { fromScaledInt, scaledIntTrunc } from "@/lib/decimal";
import {
  dirOf,
  formatChangeRate,
  formatCompactKRW,
  formatDirectional,
  formatPrice,
  toNum,
} from "@/lib/format";
import type { CoinMeta } from "@/lib/types";
import styles from "./MarketSummaryStrip.module.css";

// 대표 코인은 로스터 상위 4종 고정. 스파크라인 재요청을 막기 위해 집계 순위와 분리한다.
const REPRESENTATIVES = COIN_META.slice(0, 4);

export default function MarketSummaryStrip() {
  const tickers = useExchangeStore((s) => s.tickers);
  const connected = useExchangeStore((s) => s.connected);

  // 총 거래대금은 won 정수 BigInt로 합산한다. float 합은 2^53 초과·오차 누적으로 금액 정밀을 잃는다
  let totalValueWon = 0n;
  let up = 0;
  let down = 0;
  let flat = 0;
  let priced = 0;
  for (const c of COIN_META) {
    const t = tickers[c.market];
    if (!t) continue;
    priced += 1;
    // acc_trade_price_24h는 무체결이어도 "0"으로 오는 non-null 계약(§4-A-4)이라 별도 강등 불요
    totalValueWon += scaledIntTrunc(t.acc_trade_price_24h, 0);
    if (t.change === "RISE") up += 1;
    else if (t.change === "FALL") down += 1;
    else flat += 1;
  }
  const ready = priced > 0;

  return (
    <section className={`card ${styles.root}`} aria-label="시장 개관">
      <div className={styles.aggregate}>
        <div className={styles.aggMain}>
          {/* 배지는 또렷이 유지하고 집계값만 감쇠한다(A-5). 낭독은 배너가 화면당 1회 맡는다 */}
          <span className={styles.aggPlate}>
            <span className={styles.aggLabel}>24시간 총 거래대금</span>
            {!connected && <StaleBadge label="지연" announce={false} />}
          </span>
          <b
            className={`${styles.aggValue} num ${ready ? "" : styles.pending}`}
            data-stale={!connected ? "" : undefined}
          >
            {ready ? formatCompactKRW(fromScaledInt(totalValueWon, 0)) : "-"}
          </b>
        </div>
        <div className={styles.counts}>
          <span className={styles.count}>
            <span className={styles.countLabel}>상승</span>
            <b className="dir-text num" data-dir="up">
              {"▲"} {ready ? up : "-"}
            </b>
          </span>
          <span className={styles.count}>
            <span className={styles.countLabel}>하락</span>
            <b className="dir-text num" data-dir="down">
              {"▼"} {ready ? down : "-"}
            </b>
          </span>
          <span className={styles.count}>
            <span className={styles.countLabel}>보합</span>
            <b className="dir-text num" data-dir="flat">
              {ready ? flat : "-"}
            </b>
          </span>
        </div>
      </div>

      <ul className={styles.reps}>
        {REPRESENTATIVES.map((c) => (
          <RepCard key={c.market} meta={c} />
        ))}
      </ul>
    </section>
  );
}

function RepCard({ meta }: { meta: CoinMeta }) {
  const router = useRouter();
  const ticker = useExchangeStore((s) => s.tickers[meta.market]);
  const tickSpec = useExchangeStore((s) => s.markets.find((m) => m.market === meta.market)?.tick_size);
  const [points, setPoints] = useState<number[]>([]);

  useEffect(() => {
    let alive = true;
    getDataSource()
      .getCandles(meta.market, "15m", 24)
      .then((rows) => {
        if (!alive) return;
        // 계약은 시각 내림차순, 추세선은 오름차순
        setPoints([...rows].reverse().map((r) => toNum(r.close)));
      })
      .catch(() => {
        /* 빈 추세선은 Sparkline이 자체 처리 */
      });
    return () => {
      alive = false;
    };
  }, [meta.market]);

  const dir = ticker ? dirOf(ticker.change) : "flat";

  return (
    <li>
      <button
        type="button"
        className={styles.rep}
        aria-label={`${meta.koreanName} 거래 화면으로 이동`}
        onClick={() => router.push(`/exchange/${meta.market}`)}
      >
        <div className={styles.repHead}>
          <span className={styles.repName}>{meta.koreanName}</span>
          <span className={`${styles.repSym} num`}>{meta.symbol}</span>
        </div>
        <div className={styles.repBody}>
          <div className={styles.repFigures}>
            <span className={`${styles.repPrice} num`}>
              {ticker?.trade_price != null ? formatPrice(ticker.trade_price, tickSpec) : "-"}
            </span>
            <span
              className={`${styles.repChange} num dir-text`}
              data-dir={dir}
              aria-label={ticker ? `${meta.koreanName} ${formatChangeRate(ticker.change_rate, ticker.change)}` : undefined}
            >
              {ticker ? formatDirectional(ticker.change_rate, ticker.change) : ""}
            </span>
          </div>
          <Sparkline points={points} dir={dir} />
        </div>
      </button>
    </li>
  );
}
