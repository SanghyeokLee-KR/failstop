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
import { getAdminApi } from "@/lib/admin/api";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import { PageHeader, StatStrip, StatTile } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { AdminIcon, type AdminIconName } from "@/components/admin/icons";
import { countLabel } from "@/lib/admin/paging";
import { isSessionError, loadQueueCounts, type QueueCount, type QueueCounts, type QueuePreview } from "@/lib/admin/queueCounts";
import { sourceWord } from "@/components/admin/SourceMark";
import styles from "@/components/admin/admin.module.css";

/** 타일 값. 권한 거절과 읽기 실패를 0건과 다른 말로 적는다 */
function queueTile(q: QueueCount, sub: string): { value: string; unit?: string; sub: string; tone?: "warning" } {
  if (q.kind === "ok") return { value: countLabel(q.loaded, q.hasMore), unit: "건", sub };
  if (q.kind === "forbidden") return { value: "권한 없음", sub: "이 운영자 역할로는 볼 수 없습니다" };
  return { value: sourceWord("MISSING"), sub: "새로고침으로 다시 읽습니다", tone: "warning" };
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
  { href: "/admin/audit", label: "감사 로그", desc: "고치거나 지울 수 없는 기록", icon: "audit" },
];

/** 큐마다 가장 오래 기다린 셋의 미리보기. 건수만 두면 무엇이 기다리는지 보려고 큐마다 들어가 봐야 한다 */
function QueuePanel({ title, href, count, rows, now }: { title: string; href: string; count: QueueCount; rows: QueuePreview[]; now: number }) {
  return (
    <PanelCard
      title={title}
      actions={
        <Link href={href} className={styles.boardLink}>
          모두 보기
        </Link>
      }
    >
      {count.kind === "forbidden" ? (
        <p className={styles.previewEmpty}>이 운영자 역할로는 이 큐를 볼 수 없습니다</p>
      ) : count.kind === "failed" ? (
        <p className={styles.previewEmpty}>큐를 읽지 못했습니다. 새로고침으로 다시 읽습니다</p>
      ) : rows.length === 0 ? (
        <p className={styles.previewEmpty}>기다리는 항목이 없습니다</p>
      ) : (
        <ol className={styles.previewList}>
          {rows.map((r) => (
            <li key={r.id}>
              <Link href={href} className={styles.previewRow}>
                <span className={styles.previewTitle} title={r.title}>
                  {r.title}
                </span>
                <span className={styles.previewMeta}>{r.meta}</span>
                <span className={`${styles.previewAge} num`}>{waitWord(now - r.at)}</span>
              </Link>
            </li>
          ))}
        </ol>
      )}
    </PanelCard>
  );
}

/** 기다린 시간. 가장 오래 기다린 항목이 먼저 서므로 그 경과가 적체를 말한다 */
function waitWord(ms: number): string {
  const m = Math.max(0, Math.floor(ms / 60_000));
  if (m < 60) return `${m}분째`;
  const h = Math.floor(m / 60);
  return h < 24 ? `${h}시간째` : `${Math.floor(h / 24)}일째`;
}

export default function BackOfficeHome() {
  /* 서버에 개수 API가 없어 첫 쪽 길이로 센다. 상한을 채우면 그 이상이라고 적는다.
     종전에는 상한이 그대로 건수가 되어, 대기가 그 수를 넘은 순간부터 카드가 조용히
     같은 수를 계속 띄웠다. 세어 본 범위 밖을 없는 것으로 적지 않는다.
     큐별 권한 거절과 세션 만료의 판정은 loadQueueCounts에 두고 시험한다 */
  const load = useCallback((): Promise<QueueCounts> => loadQueueCounts(getAdminApi()), []);

  const { data, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(load, {
    isSessionError,
  });

  return (
    <div className={styles.page}>
      <PageHeader title="처리 대기" actions={<ResourceStamp lastUpdatedAt={lastUpdatedAt} stale={stale} onReload={reload} />} />

      {loading ? (
        <div className={styles.pageStack}>
          <Skeleton variant="card" width="100%" height={90} />
          <Skeleton variant="card" width="100%" height={200} />
        </div>
      ) : error || !data ? (
        <AdminErrorState reason={errorReason} onRetry={reload} />
      ) : (
        <div className={`${styles.pageStack} ${styles.staleRegion}`} data-stale={stale ? "" : undefined}>
          {/* 판독 묶음 한 상자(§12-14). 칸마다 그 큐의 화면으로 간다 */}
          <StatStrip label="처리 대기 큐" count={3}>
            <Link href="/admin/transfers" className={styles.statLink}>
              <StatTile label="출금 승인" {...queueTile(data.transfers, "승인하면 잠긴 금액을 출금합니다")} />
            </Link>
            <Link href="/admin/support" className={styles.statLink}>
              <StatTile label="문의 답변" {...queueTile(data.inquiries, "답변은 회원 화면에 그대로 보입니다")} />
            </Link>
            <Link href="/admin/community" className={styles.statLink}>
              <StatTile label="커뮤니티 신고" {...queueTile(data.reports, "대상 처리와 신고 종결을 함께 기록합니다")} />
            </Link>
          </StatStrip>

          <div className={styles.previewGrid}>
            <QueuePanel title="출금 승인 대기" href="/admin/transfers" count={data.transfers} rows={data.preview.transfers} now={lastUpdatedAt ?? Date.now()} />
            <QueuePanel title="문의 답변 대기" href="/admin/support" count={data.inquiries} rows={data.preview.inquiries} now={lastUpdatedAt ?? Date.now()} />
            <QueuePanel title="신고 처리 대기" href="/admin/community" count={data.reports} rows={data.preview.reports} now={lastUpdatedAt ?? Date.now()} />
          </div>

          {/* 대기가 쌓이지 않는 조회·설정 화면을 모은다. 내비의 두 묶음에 걸쳐 있어 그중 한 묶음의 이름을 쓰지 않는다 */}
          <PanelCard title="조회·설정 화면">
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
