"use client";

/**
 * 홈 대시보드 본체. 시장 개관 랜딩.
 * 그리드: 요약 스트립(전폭) + 대표 코인/순위(좌) + 관심 사이드바(우 2행 관통).
 * 실시간: 전 마켓 ticker 구독. favorites는 마운트 후 하이드레이션.
 */

import { useEffect, useMemo } from "react";
import MarketSummaryStrip from "@/components/dashboard/MarketSummaryStrip";
import SpotlightPanel from "@/components/dashboard/SpotlightPanel";
import RankingTable from "@/components/dashboard/RankingTable";
import WatchlistSidebar from "@/components/dashboard/WatchlistSidebar";
import { ConnectionBanner } from "@/components/StatusSignals";
import { useExchangeStore, useLiveChannels, useMarketSpecs } from "@/lib/store";
import { dest } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import styles from "./page.module.css";

export default function DashboardClient() {
  const hydrateFavorites = useExchangeStore((s) => s.hydrateFavorites);

  useLiveChannels(useMemo(() => COIN_META.map((c) => dest.ticker(c.market)), []));
  // 가격·수량 표시 자릿수의 근거. 미로드면 폴백 자릿수로 위조된 값을 그린다
  useMarketSpecs();

  useEffect(() => {
    hydrateFavorites();
  }, [hydrateFavorites]);

  return (
    <main className={styles.page}>
      <ConnectionBanner />
      <div className={styles.grid}>
        <div className={styles.summary}>
          <MarketSummaryStrip />
        </div>
        <div className={styles.spotlight}>
          <SpotlightPanel />
        </div>
        <div className={styles.ranking}>
          <RankingTable />
        </div>
        <div className={styles.watch}>
          <WatchlistSidebar />
        </div>
      </div>
    </main>
  );
}
