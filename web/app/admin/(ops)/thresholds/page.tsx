"use client";

/**
 * 임계값 관리(/admin/thresholds, 관리자 설계 4-4). 관제센터 아래 임계값 패널의 전체판이다.
 * Grafana는 보기만 한다. 값을 바꾸고 승인하고 되돌리는 일은 여기서 한다.
 */

import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import { AdminErrorState } from "@/components/admin/ResourceState";
import OpsHead from "@/components/admin/control/OpsHead";
import ThresholdPanel from "@/components/admin/control/Thresholds";
import { useClock } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";

export default function ThresholdsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const now = useClock();
  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="임계값 관리"
        desc="값을 바꾸면 게이지 눈금과 경보 판정이 함께 움직입니다. 변경은 1인 승인을 거치고 이력에서 되돌립니다"
        center={data}
        now={now}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={520} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <ThresholdPanel center={data} onChanged={() => void refresh()} full />
      )}
    </div>
  );
}
