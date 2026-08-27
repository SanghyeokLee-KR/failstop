"use client";

/**
 * 종합 상태 요약(/admin). 모니터링 소스를 합성한 헤드라인 지표 + 전역 승인 대기.
 * 읽기 전용 요약이며 파괴 액션은 각 화면과 승인 목록에서만 실행된다(§6 안전).
 * 1층 헬스 히어로가 화면 단일 대표 지표. 2층은 좌(이동 타일 2x2 + 노드 요약) 우(승인 대기) 2열로,
 * 세로 적층이 만들던 하단 절반 공백을 우열 stretch가 흡수한다.
 */

import { useCallback } from "react";
import Link from "next/link";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { AdminOverview, HaStatus } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import ApprovalList from "@/components/admin/ApprovalList";
import HealthHero from "@/components/admin/HealthHero";
import { HealthPill, PageHeader, StatTile, type Health } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { formatKRW } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

interface OverviewData {
  ov: AdminOverview;
  ha: HaStatus;
}

const NODE_HEALTH: Record<string, Health> = { healthy: "success", degraded: "warning", down: "danger" };
const NODE_HEALTH_LABEL: Record<string, string> = { healthy: "정상", degraded: "성능 저하", down: "중단" };

export default function AdminOverviewPage() {
  const load = useCallback(async (): Promise<OverviewData> => {
    const api = getAdminApi();
    // 노드 요약은 모니터링과 같은 소스를 재사용한다(신규 계약 없음)
    const [ov, ha] = await Promise.all([api.getOverview(), api.getHaStatus()]);
    return { ov, ha };
  }, []);
  // getOverview 7건 + ha 1건 합성. 10초 주기(48건/분)는 승인 목록 폴링(12건/분)과 합쳐
  // 관리 레이트리밋(60건/60초)에 정확히 닿아 상호작용 여유가 0이 된다. 15초로 완화(44건/분)
  const { data, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(load, {
    intervalMs: 15000,
    isSessionError,
  });
  const ov = data?.ov;

  return (
    <div className={styles.page}>
      <PageHeader title="종합 상태" actions={stale ? <StaleBadge since={lastUpdatedAt} /> : undefined} />

      {loading ? (
        <div className={styles.pageStack}>
          {/* 히어로 min-height 128, 컴팩트 타일 96(admin.module.css data-compact 실측)과 동치로 로딩 후 시프트 0 */}
          <Skeleton variant="card" width="100%" height={128} />
          <div className={styles.overviewCols}>
            <div className={styles.overviewLeft}>
              <div className={styles.kpiGrid} data-cols="2">
                {Array.from({ length: 4 }, (_, i) => (
                  <Skeleton key={i} variant="card" width="100%" height={96} />
                ))}
              </div>
              <Skeleton variant="card" width="100%" height={180} />
            </div>
            <Skeleton variant="card" width="100%" height={300} />
          </div>
        </div>
      ) : error || !ov || !data ? (
        <AdminErrorState reason={errorReason} onRetry={reload} />
      ) : (
        <div className={`${styles.pageStack} ${styles.staleRegion}`} data-stale={stale ? "" : undefined}>
          <HealthHero ov={ov} />
          <div className={styles.overviewCols}>
            <div className={styles.overviewLeft}>
              <div className={styles.kpiGrid} data-cols="2">
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
              <PanelCard title="노드 상태" className={styles.stretchCard}>
                <div className={styles.nodeSummaryList}>
                  {data.ha.nodes.map((n) => (
                    <Link
                      key={n.id}
                      href="/admin/monitoring"
                      className={`${styles.nodeSummaryRow} num`}
                      data-leader={n.role === "leader" || undefined}
                    >
                      <span className={styles.nodeSummaryId}>{n.id}</span>
                      <span className={styles.nodeRole}>{n.role === "leader" ? "리더" : "팔로워"}</span>
                      <HealthPill health={NODE_HEALTH[n.health]} label={NODE_HEALTH_LABEL[n.health]} />
                      <span className={styles.nodeSummaryMeta}>
                        {n.role === "leader"
                          ? `처리 순번 ${formatKRW(n.seq)}`
                          : n.health === "down"
                            ? "복제 측정 두절"
                            : `복제 지연 ${n.followerLagMs}ms`}
                      </span>
                    </Link>
                  ))}
                </div>
              </PanelCard>
            </div>
            <PanelCard title="승인 대기" className={styles.stretchCard}>
              <ApprovalList
                onChange={reload}
                emptyLabel="대기 중이거나 실행 가능한 승인 없음"
                emptyDesc="카오스·마켓·운영자 화면에서 파괴 액션을 요청하면 여기에 적재"
              />
            </PanelCard>
          </div>
        </div>
      )}
    </div>
  );
}
