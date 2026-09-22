"use client";

/**
 * 백오피스 진입점. 이 화면이 답하는 질문은 하나다. 지금 처리를 기다리는 것이 얼마나 쌓였나.
 *
 * 큐 셋(출금·문의·신고)만 실제 건수를 읽는다. 마켓·회원·운영자·감사는 쌓이는 대기가
 * 아니라 조회하러 가는 곳이라 건수를 세지 않는다. 일곱 개를 전부 세면 이 화면 하나가
 * 일곱 번을 왕복하는데, 그건 종합 화면에서 걷어낸 팬아웃을 여기서 되살리는 것이다.
 *
 * 갱신은 진입 1회와 새로고침뿐이다. 큐는 사람이 처리하는 속도로 변하므로 주기 폴링이
 * 값을 더 정확하게 만들지 않는다.
 *
 * 건수에 톤을 붙이지 않는다. 큐에 일이 있는 것은 장애가 아니라 정상 업무다. 존재만으로
 * 발화하면 세 큐가 늘 주황이 되고, 그러면 색이 상태를 말하지 못한다. 적체를 구분하려면
 * 건수가 아니라 가장 오래 기다린 건의 경과가 원천이어야 하는데 지금 그 축이 없다.
 */

import { useCallback } from "react";
import Link from "next/link";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import { PageHeader, StatTile } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { AdminIcon, type AdminIconName } from "@/components/admin/icons";
import { QUEUE_COUNT_CAP, countLabel } from "@/lib/admin/paging";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

/** 세어 본 만큼의 건수. 상한을 채웠으면 그 이상이라는 사실을 함께 든다 */
interface QueueCount {
  loaded: number;
  hasMore: boolean;
}

interface QueueCounts {
  transfers: QueueCount;
  inquiries: QueueCount;
  reports: QueueCount;
}

/**
 * 건수를 세지 않는 곳. 대기가 쌓이는 큐가 아니라 조회·설정하러 가는 화면이다.
 * 라벨은 내비와 한 글자까지 같게 둔다. 같은 화면을 두 이름으로 부르면 어느 쪽이
 * 무엇을 가리키는지 사람이 매번 맞춰 봐야 한다.
 */
const DESKS: { href: string; label: string; desc: string; icon: AdminIconName }[] = [
  { href: "/admin/members", label: "회원 운영", desc: "정지·복구·폐쇄", icon: "members" },
  { href: "/admin/config", label: "마켓·공지", desc: "거래 중지·재개, 수수료, 공지", icon: "config" },
  { href: "/admin/operators", label: "운영자·역할", desc: "신원·역할", icon: "operators" },
  { href: "/admin/audit", label: "감사 로그", desc: "수정·삭제 불가 기록", icon: "audit" },
];

export default function BackOfficeHome() {
  const load = useCallback(async (): Promise<QueueCounts> => {
    const api = getAdminApi();
    /* 서버에 개수 API가 없어 첫 쪽 길이로 센다. 상한을 채우면 그 이상이라고 적는다.
       종전에는 상한이 그대로 건수가 되어, 대기가 그 수를 넘은 순간부터 카드가 조용히
       같은 수를 계속 띄웠다. 세어 본 범위 밖을 없는 것으로 적지 않는다 */
    const [t, i, r] = await Promise.all([
      api.getTransfers("REQUESTED", { limit: QUEUE_COUNT_CAP }),
      api.getInquiries("OPEN", { limit: QUEUE_COUNT_CAP }),
      api.getCommunityReports("PENDING", { limit: QUEUE_COUNT_CAP }),
    ]);
    const count = (p: { data: unknown[]; hasMore: boolean }): QueueCount => ({
      loaded: p.data.length,
      hasMore: p.hasMore,
    });
    return { transfers: count(t), inquiries: count(i), reports: count(r) };
  }, []);

  const { data, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(load, {
    isSessionError,
  });

  return (
    <div className={styles.page}>
      <PageHeader
        title="처리 대기"
        desc="고객 응대 큐의 대기 건수"
        actions={<ResourceStamp lastUpdatedAt={lastUpdatedAt} stale={stale} onReload={reload} />}
      />

      {loading ? (
        <div className={styles.pageStack}>
          <div className={styles.kpiGrid} data-cols="3">
            {Array.from({ length: 3 }, (_, i) => (
              <Skeleton key={i} variant="card" width="100%" height={96} />
            ))}
          </div>
          <Skeleton variant="card" width="100%" height={200} />
        </div>
      ) : error || !data ? (
        <AdminErrorState reason={errorReason} onRetry={reload} />
      ) : (
        <div className={`${styles.pageStack} ${styles.staleRegion}`} data-stale={stale ? "" : undefined}>
          <div className={styles.kpiGrid} data-cols="3">
            <Link href="/admin/transfers" className={styles.railLink}>
              <StatTile
                compact
                label="출금 승인"
                value={countLabel(data.transfers.loaded, data.transfers.hasMore)}
                unit="건"
                sub="승인 시 잠긴 금액 출금"
              />
            </Link>
            <Link href="/admin/support" className={styles.railLink}>
              <StatTile
                compact
                label="문의 답변"
                value={countLabel(data.inquiries.loaded, data.inquiries.hasMore)}
                unit="건"
                sub="답변은 회원 화면에 그대로 표시"
              />
            </Link>
            <Link href="/admin/community" className={styles.railLink}>
              <StatTile
                compact
                label="커뮤니티 신고"
                value={countLabel(data.reports.loaded, data.reports.hasMore)}
                unit="건"
                sub="대상 처리와 신고 종결"
              />
            </Link>
          </div>

          {/* 큐가 없는 자리를 모은다. 내비의 두 묶음에 걸쳐 있어 그중 한 묶음의 이름을 쓰지 않는다 */}
          <PanelCard title="큐 없는 자리">
            <div className={styles.deskList}>
              {DESKS.map((d) => (
                <Link key={d.href} href={d.href} className={styles.deskRow}>
                  <span className={styles.deskIcon} aria-hidden="true">
                    <AdminIcon name={d.icon} />
                  </span>
                  <span className={styles.deskLabel}>{d.label}</span>
                  <span className={styles.deskDesc}>{d.desc}</span>
                </Link>
              ))}
            </div>
          </PanelCard>
        </div>
      )}
    </div>
  );
}
