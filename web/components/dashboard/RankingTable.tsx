"use client";

/**
 * RankingTable: 실시간 순위 테이블. 탭(거래대금순·급상승·급하락)으로 정렬 기준 전환.
 * 데이터: 전 마켓 tickers. 행 클릭 시 /exchange/[market]. 현재가는 TickCell 플래시.
 */

import { memo, useCallback, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import SegmentedTab from "@/components/SegmentedTab";
import SymbolChip from "@/components/SymbolChip";
import TickCell from "@/components/TickCell";
import { StaleBadge } from "@/components/StatusSignals";
import { useExchangeStore } from "@/lib/store";
import type { ChangeState } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import { compareDecimal } from "@/lib/decimal";
import {
  ariaChangeLabel,
  dirOf,
  formatCompactKRW,
  formatDirectional,
  formatPrice,
  toNum,
} from "@/lib/format";
import styles from "./RankingTable.module.css";

type RankTab = "value" | "gainers" | "losers";

const TABS = [
  { value: "value" as const, label: "거래대금순" },
  { value: "gainers" as const, label: "급상승" },
  { value: "losers" as const, label: "급하락" },
];

function signedRate(t: { change: string; change_rate: string } | undefined): number {
  if (!t) return 0;
  const r = toNum(t.change_rate);
  return t.change === "FALL" ? -r : t.change === "RISE" ? r : 0;
}

/**
 * 순위 행. memo + 원시값 props로 값이 바뀐 행만 리렌더한다.
 *
 * 정렬 기준이 전 마켓 tickers라 부모는 틱마다 재계산할 수밖에 없다. memo 없이는 그때마다 12행
 * 전부가 포맷터 4종(formatPrice·formatDirectional·formatCompactKRW·ariaChangeLabel)과 SymbolChip을
 * 다시 실행한다. 봇이 초당 15건씩 체결을 내는 구간에서는 이 비용이 상시로 깔린다.
 * TradesPanel의 TradeRow와 같은 패턴이다.
 */
interface RankingRowProps {
  rank: number;
  market: string;
  koreanName: string;
  symbol: string;
  meta: (typeof COIN_META)[number];
  tickSpec: string | undefined;
  tradePrice: string | null | undefined;
  change: ChangeState | undefined;
  changeRate: string | undefined;
  accTradePrice24h: string | undefined;
  onOpen: (market: string) => void;
}

const RankingRow = memo(function RankingRow({
  rank, market, koreanName, symbol, meta, tickSpec,
  tradePrice, change, changeRate, accTradePrice24h, sharePct, onOpen,
}: RankingRowProps & { sharePct: number }) {
  const has = change !== undefined && changeRate !== undefined;
  const dir = has ? dirOf(change) : "flat";
  return (
    <div
      role="row"
      className={styles.row}
      tabIndex={0}
      aria-label={`${koreanName} 거래 화면으로 이동`}
      onClick={() => onOpen(market)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onOpen(market);
        }
      }}
    >
      <span role="cell" className={`${styles.rank} num`}>{rank}</span>
      <span role="cell" className={styles.name}>
        <SymbolChip meta={meta} size="sm" />
        <span className={styles.nameText}>
          <span className={styles.nameKo}>{koreanName}</span>
          <span className={`${styles.nameSym} num`}>{symbol}/KRW</span>
        </span>
      </span>
      {/* 이름과 숫자 사이 슬랙을 정보로 채운다. 거래대금 점유는 이미 가진 값이라 신규 조회 0.
          막대는 보조 채널이고 판독의 주 채널은 우측 거래대금 텍스트다 */}
      <span role="cell" className={styles.shareCell} aria-hidden="true">
        <span className={styles.shareTrack}>
          {/* 값 없음(티커 미도착)은 fill 없이 트랙만. 최소 폭을 강제하면 없는 규모를 그린다 */}
          {sharePct > 0 && <span className={styles.shareFill} style={{ width: `${sharePct}%` }} />}
        </span>
      </span>
      <span role="cell" className={`${styles.right} num`}>
        {tradePrice != null ? (
          <TickCell value={toNum(tradePrice)} valueStr={formatPrice(tradePrice, tickSpec)} />
        ) : (
          "-"
        )}
      </span>
      <span
        role="cell"
        className={`${styles.right} num dir-text`}
        data-dir={dir}
        aria-label={has ? ariaChangeLabel(changeRate, change) : undefined}
      >
        {has ? formatDirectional(changeRate, change) : "-"}
      </span>
      <span role="cell" className={`${styles.right} ${styles.vol} num`}>
        {accTradePrice24h !== undefined ? formatCompactKRW(accTradePrice24h) : "-"}
      </span>
    </div>
  );
});

export default function RankingTable() {
  const router = useRouter();
  const tickers = useExchangeStore((s) => s.tickers);
  const markets = useExchangeStore((s) => s.markets);
  const connected = useExchangeStore((s) => s.connected);
  // 행별 markets.find(커밋당 O(행수×마켓수))를 없애는 Map 조회
  const specMap = useMemo(() => new Map(markets.map((m) => [m.market, m.tick_size])), [markets]);
  // 행 memo가 깨지지 않도록 콜백 참조를 고정한다. 인라인 화살표를 넘기면 매 틱 새 함수라 memo가 무효다
  const openMarket = useCallback((market: string) => router.push(`/exchange/${market}`), [router]);
  const [tab, setTab] = useState<RankTab>("value");

  const rows = useMemo(() => {
    const list = [...COIN_META];
    list.sort((a, b) => {
      const ta = tickers[a.market];
      const tb = tickers[b.market];
      if (tab === "value") {
        // 거래대금 내림차순. 2^53 초과 정밀 보존 위해 문자열 십진 비교
        return compareDecimal(tb?.acc_trade_price_24h ?? "0", ta?.acc_trade_price_24h ?? "0");
      }
      const diff = signedRate(tb) - signedRate(ta); // 급상승: 내림차순
      return tab === "gainers" ? diff : -diff;
    });
    return list;
  }, [tab, tickers]);

  // 점유 막대 기준. 1위 대비 상대값이라 절대 규모가 아니라 분포를 보여준다
  const maxAcc = useMemo(
    () => Math.max(0, ...COIN_META.map((c) => toNum(tickers[c.market]?.acc_trade_price_24h ?? "0"))),
    [tickers],
  );

  return (
    <section className={`card ${styles.root}`} aria-label="실시간 순위">
      <header className={styles.header}>
        <div className={styles.titleRow}>
          <h2 className={styles.title}>실시간 순위</h2>
          {/* 낭독은 배너가 화면당 1회. 패널 배지는 시각 표식만 담당한다(A-5) */}
          {!connected && <StaleBadge label="지연" announce={false} />}
        </div>
        <SegmentedTab
          items={TABS}
          value={tab}
          onChange={setTab}
          variant="neutral"
          ariaLabel="순위 정렬 기준"
        />
      </header>

      <div
        className={styles.table}
        role="table"
        aria-label="코인 순위 목록"
        data-stale={!connected ? "" : undefined}
      >
        <div className={`${styles.row} ${styles.head}`} role="row">
          <span role="columnheader" className={styles.rank}>#</span>
          <span role="columnheader" className={styles.name}>코인</span>
          <span role="columnheader" className={styles.shareCell} aria-hidden="true" />
          <span role="columnheader" className={styles.right}>현재가</span>
          <span role="columnheader" className={styles.right}>전일대비</span>
          <span role="columnheader" className={styles.right}>거래대금</span>
        </div>

        <div role="rowgroup">
          {rows.map((c, i) => {
            const t = tickers[c.market];
            return (
              <RankingRow
                key={c.market}
                rank={i + 1}
                market={c.market}
                koreanName={c.koreanName}
                symbol={c.symbol}
                meta={c}
                tickSpec={specMap.get(c.market)}
                tradePrice={t?.trade_price}
                change={t?.change}
                changeRate={t?.change_rate}
                accTradePrice24h={t?.acc_trade_price_24h}
                // 정수 버킷으로 양자화한다. 분모가 전 마켓 최대라 소수 그대로면 1위 체결마다 12행이 리렌더된다
                sharePct={maxAcc > 0 ? Math.round((toNum(t?.acc_trade_price_24h ?? "0") / maxAcc) * 100) : 0}
                onOpen={openMarket}
              />
            );
          })}
        </div>
      </div>
    </section>
  );
}
