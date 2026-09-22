"use client";

/**
 * 읽기 전용 요약이라 파괴 액션은 각 화면과 승인 목록에서만 실행한다(§6 안전).
 * 2층을 좌우 2열 stretch로 두어 세로 적층에서 생기는 하단 공백을 없앤다.
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
    // 노드 요약은 용량 화면과 같은 소스를 재사용한다(신규 계약 없음)
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
              <div className={styles.kpiGrid} data-cols="3">
                {Array.from({ length: 3 }, (_, i) => (
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
              <div className={styles.kpiGrid} data-cols="3">
                <Link href="/admin/capacity" className={styles.railLink}>
                  <StatTile
                    compact
                    label="매칭 리더"
                    value={ov.leaderNodeId}
                    sub={<span title="epoch">선출 세대 {ov.epoch}</span>}
                  />
                </Link>
                <Link href="/admin/bots" className={styles.railLink}>
                  {/* 봇 노드 미보고를 0으로 채우면 홈이 플릿 정지를 단정한다(status 1.3 source) */}
                  <StatTile
                    compact
                    label="활성 봇"
                    value={ov.botsActive == null ? "미상" : ov.botsActive}
                    sub={ov.currentOps == null ? "상태 미보고" : `초당 ${formatKRW(ov.currentOps)}건 처리`}
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
                      href="/admin/capacity"
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
                emptyDesc="마켓·운영자·회원 화면에서 파괴 액션을 요청하면 여기에 적재"
              />
            </PanelCard>
          </div>
        </div>
      )}
    </div>
  );
}
