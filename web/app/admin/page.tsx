"use client";

/**
 * 종합 상태 요약(/admin). 모니터링 소스를 합성한 헤드라인 지표 + 전역 승인 대기.
 * 읽기 전용 요약이며 파괴 액션은 각 화면과 승인 목록에서만 실행된다(§6 안전).
 */

import { useCallback } from "react";
import { getAdminApi } from "@/lib/admin/api";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import ApprovalList from "@/components/admin/ApprovalList";
import { HealthPill, PageHeader, StatTile } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { formatKRW } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const HEALTH_LABEL = { success: "전체 정상", warning: "주의", danger: "장애" } as const;

export default function AdminOverviewPage() {
  const loadOverview = useCallback(() => getAdminApi().getOverview(), []);
  const { data: ov, loading, error, stale, lastUpdatedAt, reload } = useAdminResource(loadOverview, {
    intervalMs: 2000,
  });

  return (
    <div className={styles.page}>
      <PageHeader
        title="종합 상태"
        desc="고가용성·봇·카오스·승인 현황 요약. 파괴 액션은 각 화면에서 2인 승인으로만 실행됩니다."
        actions={stale ? <StaleBadge since={lastUpdatedAt} /> : undefined}
      />

      {loading ? (
        <div className={styles.kpiGrid}>
          {Array.from({ length: 8 }, (_, i) => (
            <Skeleton key={i} variant="card" width="100%" height={96} />
          ))}
        </div>
      ) : error || !ov ? (
        <AdminErrorState onRetry={reload} />
      ) : (
        <div className={styles.staleRegion} data-stale={stale ? "" : undefined}>
          <div className={styles.kpiGroup}>
            <span className={styles.kpiGroupLabel}>헬스</span>
            <div className={styles.kpiGrid}>
              <div className={styles.statTile}>
                <span className={styles.statLabel}>종합 헬스</span>
                <span className={styles.statPillWrap}>
                  <HealthPill health={ov.overallHealth} label={HEALTH_LABEL[ov.overallHealth]} />
                </span>
              </div>
              <StatTile
                label="불변식"
                value={`${ov.invariantsOk} / ${ov.invariantsTotal}`}
                sub="정상 / 전체"
                tone={ov.invariantsOk === ov.invariantsTotal ? "success" : "danger"}
                alarm={ov.invariantsOk !== ov.invariantsTotal}
                hero
              />
              <StatTile
                label="최근 복구 RTO"
                value={ov.lastRtoMs == null ? "-" : `${(ov.lastRtoMs / 1000).toFixed(1)}초`}
                sub={ov.lastVerdict ? `판정 ${ov.lastVerdict}` : undefined}
                tone={ov.lastVerdict === "PASS" ? "success" : ov.lastVerdict === "FAIL" ? "danger" : undefined}
                alarm={ov.lastVerdict === "FAIL"}
              />
            </div>
          </div>

          <div className={styles.kpiGroup}>
            <span className={styles.kpiGroupLabel}>운영</span>
            <div className={styles.kpiGrid}>
              <StatTile label="리더 · epoch" value={ov.leaderNodeId} sub={`epoch ${ov.epoch}`} />
              <StatTile label="활성 봇 · OPS" value={ov.botsActive} sub={`${formatKRW(ov.currentOps)} OPS`} />
              <StatTile
                label="진행 카오스"
                value={ov.chaosRunning}
                sub="주입 중 실험"
                tone={ov.chaosRunning > 0 ? "warning" : undefined}
                alarm={ov.chaosRunning > 0}
              />
            </div>
          </div>

          <div className={styles.kpiGroup}>
            <span className={styles.kpiGroupLabel}>승인 · 마켓</span>
            <div className={styles.kpiGrid}>
              <StatTile
                label="대기 승인"
                value={ov.pendingApprovals}
                sub="2인 승인 대기"
                tone={ov.pendingApprovals > 0 ? "warning" : undefined}
                alarm={ov.pendingApprovals > 0}
              />
              <StatTile
                label="중지 마켓"
                value={ov.suspendedMarkets}
                sub="거래 중지"
                tone={ov.suspendedMarkets > 0 ? "warning" : undefined}
                alarm={ov.suspendedMarkets > 0}
              />
            </div>
          </div>
        </div>
      )}

      <PanelCard title="승인 대기 · 실행">
        <ApprovalList onChange={reload} emptyLabel="대기 중이거나 실행 가능한 승인이 없어요" />
      </PanelCard>
    </div>
  );
}
