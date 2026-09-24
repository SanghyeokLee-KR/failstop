"use client";

/**
 * 읽기 전용 요약이라 파괴 액션은 각 화면과 승인 목록에서만 실행한다(§6 안전).
 *
 * 노드 목록은 이 화면이 그리지 않는다. 종전에는 getHaStatus의 노드 배열을 링크 행으로
 * 폈는데, 그 배열이 http 어댑터에서는 리스 하나로 합성한 단일 노드이고 sim에서는 서버가
 * 실재하지 않는다고 선언한 토폴로지를 지어낸 것이었다. 실제 관측이 있는 쪽은 NodeProbe를
 * 지나는 용량 화면뿐이므로 노드는 거기서 본다. 그 호출이 빠져 이 화면의 왕복도 하나 준다.
 */

import { useCallback } from "react";
import Link from "next/link";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { AdminOverview } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import ApprovalList from "@/components/admin/ApprovalList";
import HealthHero from "@/components/admin/HealthHero";
import DecisionFooter from "@/components/admin/DecisionFooter";
import GrafanaLink, { grafanaConfigured } from "@/components/admin/GrafanaLink";
import { PageHeader, StatTile } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { formatKRW } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export default function AdminOverviewPage() {
  const load = useCallback(() => getAdminApi().getOverview(), []);
  // getOverview가 6건을 합성한다. 이 화면은 "지금 손댈 것이 있나"에 답하는 자리라
  // 초 단위 신선도가 필요 없다. idle 리듬으로 두고 조작 직후와 새로고침에 즉시 갱신한다
  const { data, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(load, {
    cadence: "idle",
    isSessionError,
  });
  const ov = data;

  return (
    <div className={styles.page}>
      <PageHeader title="종합 관제" actions={<ResourceStamp lastUpdatedAt={lastUpdatedAt} stale={stale} onReload={reload} />} />

      {loading ? (
        <div className={styles.pageStack}>
          {/* 히어로 min-height 128, 컴팩트 타일 96(admin.module.css data-compact 실측)과 동치로 로딩 후 시프트 0 */}
          <Skeleton variant="card" width="100%" height={128} />
          <div className={styles.kpiGrid} data-cols="3">
            {Array.from({ length: 3 }, (_, i) => (
              <Skeleton key={i} variant="card" width="100%" height={96} />
            ))}
          </div>
          <Skeleton variant="card" width="100%" height={240} />
        </div>
      ) : error || !ov ? (
        <AdminErrorState reason={errorReason} onRetry={reload} />
      ) : (
        <div className={`${styles.pageStack} ${styles.staleRegion}`} data-stale={stale ? "" : undefined}>
          <HealthHero ov={ov} />
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
          <PanelCard title="승인 대기">
            <ApprovalList
              onChange={reload}
              emptyLabel="대기 중이거나 실행 가능한 승인 없음"
              emptyDesc="마켓·운영자·회원 화면에서 파괴 액션을 요청하면 여기에 적재"
            />
          </PanelCard>
          {/* 관측 주소가 없으면 링크가 null을 그린다. 빈 단계를 배열에 넣지 않아 화살표가 겹치지 않는다 */}
          <DecisionFooter
            steps={[
              "판정 확인",
              ...(grafanaConfigured()
                ? [<GrafanaLink key="ov" board="msp-overview" label="서비스 관제에서 추이" />]
                : []),
              <Link key="cap" href="/admin/capacity" className={styles.boardLink}>
                용량·부하에서 조치
              </Link>,
            ]}
          />
        </div>
      )}
    </div>
  );
}
