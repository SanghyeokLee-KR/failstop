"use client";

/**
 * ChartPanel: chart 영역 래퍼 (§2.5).
 * 메인 TA 차트(klinecharts)를 next/dynamic ssr:false로 로드해 대시보드 미니 차트(lightweight-charts)와
 * 번들을 코드 스플릿한다(설계 §2.4). 타임프레임·지표·그리기는 TradingChart가 툴바에서 관리한다.
 */

import dynamic from "next/dynamic";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";

const TradingChart = dynamic(() => import("@/components/chart/TradingChart"), {
  ssr: false,
  loading: () => <Skeleton variant="chart" width="100%" height="100%" />,
});

export default function ChartPanel({ market }: { market: string }) {
  return (
    <PanelCard>
      <TradingChart market={market} />
    </PanelCard>
  );
}
