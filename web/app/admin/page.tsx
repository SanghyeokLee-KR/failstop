"use client";

/**
 * 종합 상태 요약(/admin). 모니터링 소스를 합성한 헤드라인 지표 + 전역 승인 대기.
 * 읽기 전용 요약이며 파괴 액션은 각 화면과 승인 목록에서만 실행된다(§6 안전).
 * 1층 헬스 히어로가 화면 단일 대표 지표, 2층 컴팩트 레일은 각 화면으로 가는 이동 타일이다.
 */

import { useCallback } from "react";
import Link from "next/link";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import ApprovalList from "@/components/admin/ApprovalList";
import HealthHero from "@/components/admin/HealthHero";
import { PageHeader, StatTile } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { formatKRW } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export default function AdminOverviewPage() {
  const loadOverview = useCallback(() => getAdminApi().getOverview(), []);
  // getOverview는 1회당 GET 7건 합성이다. 2초 주기는 승인 목록 폴링과 합쳐 분당 240건으로
  // 서버 관리 레이트리밋(운영자당 60건/60초)을 상시 초과한다. 10초로 완화(42건/분)
  const { data: ov, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(loadOverview, {
    intervalMs: 10000,
    isSessionError,
  });

  return (
    <div className={styles.page}>
      <PageHeader title="종합 상태" actions={stale ? <StaleBadge since={lastUpdatedAt} /> : undefined} />

      {loading ? (
        <div className={styles.pageStack}>
          {/* 히어로 min-height 128, 컴팩트 타일 96(admin.module.css data-compact 실측)과 동치로 로딩 후 시프트 0 */}
          <Skeleton variant="card" width="100%" height={128} />
          <div className={styles.kpiGrid}>
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} variant="card" width="100%" height={96} />
            ))}
          </div>
        </div>
      ) : error || !ov ? (
        <AdminErrorState reason={errorReason} onRetry={reload} />
      ) : (
        <div className={`${styles.pageStack} ${styles.staleRegion}`} data-stale={stale ? "" : undefined}>
          <HealthHero ov={ov} />
          <div className={styles.kpiGrid}>
            <Link href="/admin/monitoring" className={styles.railLink}>
              <StatTile
                compact
                label="매칭 리더"
                value={ov.leaderNodeId}
                sub={<span title="epoch">선출 세대 {ov.epoch}</span>}
              />
            </Link>
            <Link href="/admin/bots" className={styles.railLink}>
              <StatTile compact label="활성 봇" value={ov.botsActive} sub={`초당 ${formatKRW(ov.currentOps)}건 처리`} />
            </Link>
            <Link href="/admin/chaos" className={styles.railLink}>
              <StatTile
                compact
                label="진행 카오스"
                value={ov.chaosRunning}
                tone={ov.chaosRunning > 0 ? "warning" : undefined}
                alarm={ov.chaosRunning > 0}
              />
            </Link>
            <Link href="/admin/config" className={styles.railLink}>
              <StatTile
                compact
                label="중지 마켓"
                value={ov.suspendedMarkets}
                tone={ov.suspendedMarkets > 0 ? "warning" : undefined}
                alarm={ov.suspendedMarkets > 0}
              />
            </Link>
          </div>
        </div>
      )}

      <PanelCard title="승인 대기">
        <ApprovalList onChange={reload} emptyLabel="대기 중이거나 실행 가능한 승인이 없습니다" />
      </PanelCard>
    </div>
  );
}
