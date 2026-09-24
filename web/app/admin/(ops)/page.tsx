"use client";

/**
 * MSP AI 관제센터(/admin, 관리자 설계 4-4). 종합 관제를 대신한다.
 *
 * Grafana와 할 일을 가른다. Grafana는 추이를 보고 탐색하는 곳이고, 여기는 지금 값과 임계를 보고
 * 판단하고 조치를 승인하고 기록을 남기는 곳이다. 그래서 추이 그래프를 그리지 않고 현재값, 임계 눈금,
 * 상태, 조치, 기록만 둔다. 파괴 액션은 여기서 실행하지 않는다. AI 조치는 ADR 0035의 허용 목록뿐이다.
 *
 * 관제 판은 운영 관제 셸 전체가 나눠 쓰는 폴러(opsCenter)에서 읽고, 거래 요약은 기존 합성 조회
 * (getOverview)를 30초 리듬으로 읽는다.
 */

import { useCallback } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import AiPanel from "@/components/admin/control/AiPanel";
import ActionsColumn from "@/components/admin/control/Actions";
import InfraColumn from "@/components/admin/control/Infra";
import ModeBar from "@/components/admin/control/ModeBar";
import OpsHead from "@/components/admin/control/OpsHead";
import ScenarioControl from "@/components/admin/control/ScenarioControl";
import SummaryStrip from "@/components/admin/control/SummaryStrip";
import ThresholdPanel from "@/components/admin/control/Thresholds";
import TradingStrip from "@/components/admin/control/TradingStrip";
import { useClock } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export default function ControlCenterPage() {
  const { data, loading, failed, refresh } = useOpsCenter();
  const now = useClock();
  const loadOverview = useCallback(() => getAdminApi().getOverview(), []);
  const ov = useAdminResource(loadOverview, { cadence: "idle", isSessionError });
  const reloadOverview = ov.reload;
  const onChanged = useCallback(() => {
    void refresh();
    reloadOverview();
  }, [refresh, reloadOverview]);

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="관제센터"
        desc={
          <>
            관찰과 추이는 <b>Grafana</b>, 판단·조치·기록은 <b>관제센터</b>
          </>
        }
        center={data}
        now={now}
        actions={<ScenarioControl scenario={data?.scenario ?? null} onChanged={onChanged} />}
      />
      {data === null && loading ? (
        <>
          <Skeleton variant="card" width="100%" height={52} />
          <div className={styles.summary}>
            {Array.from({ length: 5 }, (_, i) => (
              <Skeleton key={i} variant="card" width="100%" height={96} />
            ))}
          </div>
          <div className={styles.columns}>
            {Array.from({ length: 3 }, (_, i) => (
              <Skeleton key={i} variant="card" width="100%" height="100%" />
            ))}
          </div>
        </>
      ) : data === null ? (
        <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <div className={`${adminStyles.pageStack} ${adminStyles.staleRegion}`} data-stale={failed ? "" : undefined}>
          <ModeBar center={data} onChanged={onChanged} />
          <SummaryStrip center={data} now={now} />
          <div className={styles.columns}>
            <InfraColumn center={data} now={now} />
            <AiPanel center={data} now={now} onChanged={onChanged} />
            <ActionsColumn center={data} now={now} onChanged={onChanged} />
          </div>
          <ThresholdPanel center={data} onChanged={onChanged} />
          <TradingStrip ov={ov.data} at={ov.lastUpdatedAt} />
        </div>
      )}
    </div>
  );
}
