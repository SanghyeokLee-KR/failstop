"use client";

/**
 * MSP AI 관제센터(/admin, 관리자 설계 4-4). 종합 관제를 대신한다.
 *
 * Grafana와 할 일을 가른다. Grafana는 추이를 탐색하는 곳이고, 여기는 지금 값과 임계를 보고 판단하고
 * 조치를 승인하고 기록을 남기는 곳이다. 추세는 상태 요약 칸의 작은 선 하나씩만 두고(범위 1·6·24시간)
 * 탐색은 Grafana로 보낸다. 파괴 액션은 여기서 실행하지 않는다. AI 조치는 ADR 0035의 허용 목록뿐이다.
 *
 * 관제 판은 운영 관제 셸 전체가 나눠 쓰는 폴러(opsCenter)에서 읽는다. 거래 요약은 판의 거래 칸과 기존 합성 조회
 * (getOverview, 30초 리듬)를 함께 읽는다. 판은 매초 새로 오지만 기둥마다 자기 칸만 받고(memo) 시계
 * 글자는 그 글자를 그리는 조각만 탄다. 이 화면 머리에서 시계를 받아 아래로 내리지 않는다.
 */

import { useCallback, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import type { TrendRange } from "@/lib/admin/types";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import AiPanel from "@/components/admin/control/AiPanel";
import ActionsColumn from "@/components/admin/control/Actions";
import InfraColumn from "@/components/admin/control/Infra";
import ModeBar from "@/components/admin/control/ModeBar";
import OpsSkeleton from "@/components/admin/control/OpsSkeleton";
import OpsHead, { CollectState, RangeSwitch } from "@/components/admin/control/OpsHead";
import ScenarioControl from "@/components/admin/control/ScenarioControl";
import SummaryStrip from "@/components/admin/control/SummaryStrip";
import ThresholdPanel from "@/components/admin/control/Thresholds";
import TradingStrip from "@/components/admin/control/TradingStrip";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export default function ControlCenterPage() {
  const { data, loading, failed, refresh } = useOpsCenter();
  // 관제센터는 지금을 보는 화면이라 기본 범위는 1시간이다. 긴 범위는 앞 구간 평균에 묻혀 증감이 늦게 움직인다
  const [range, setRange] = useState<TrendRange>(1);
  // http 원천은 1시간 추세만 늘 읽으므로 다른 범위를 고르면 그 범위를 읽게 알린다(sim은 할 일이 없다)
  const pickRange = useCallback(
    (r: TrendRange) => {
      setRange(r);
      getAdminApi().wantTrends(r);
      void refresh(true);
    },
    [refresh],
  );
  const loadOverview = useCallback(() => getAdminApi().getOverview(), []);
  const ov = useAdminResource(loadOverview, { cadence: "idle", isSessionError });
  const reloadOverview = ov.reload;
  // 조작 뒤에는 떠 있던 조회가 조작 전 판일 수 있어 한 번 더 읽는다(force)
  const onChanged = useCallback(() => {
    void refresh(true);
    reloadOverview();
  }, [refresh, reloadOverview]);

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="관제센터"
        center={data}
        aside={
          <>
            <RangeSwitch range={range} onRange={pickRange} />
            <CollectState center={data} failed={failed} />
          </>
        }
        actions={<ScenarioControl scenario={data?.scenario ?? null} onChanged={onChanged} />}
      />
      {data === null && loading ? (
        <OpsSkeleton />
      ) : data === null ? (
        <AdminErrorState onRetry={() => void refresh(true)} />
      ) : (
        <div className={`${adminStyles.pageStack} ${adminStyles.staleRegion}`} data-stale={failed ? "" : undefined}>
          <ModeBar section={data.ai} onChanged={onChanged} />
          <SummaryStrip summary={data.summary} alerts={data.alerts} incidents={data.incidents} trends={data.trends} range={range} />
          <div className={styles.columns}>
            <InfraColumn center={data} />
            <AiPanel section={data.ai} logs={data.logs} onChanged={onChanged} />
            <ActionsColumn alerts={data.alerts} incidents={data.incidents} jobs={data.jobs} oncall={data.oncall} onChanged={onChanged} />
          </div>
          <ThresholdPanel section={data.thresholds} onChanged={onChanged} />
          <TradingStrip ov={ov.data} at={ov.lastUpdatedAt} trading={data.trading} />
        </div>
      )}
    </div>
  );
}
