"use client";

/**
 * 감사 로그(/admin/audit). append-only 조회. 전 관리 액션이 적재 대상(§3-2).
 * admin_audit_log는 forbid_mutation 트리거로 수정·삭제가 차단된다(읽기만 제공).
 */

import { useCallback } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { AuditEntry } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import { PageHeader } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { formatTimeHMS } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const COLUMNS: DataColumn<AuditEntry>[] = [
  { key: "at", label: "시각", width: "90px", render: (e) => formatTimeHMS(e.at) },
  { key: "operator", label: "운영자", width: "minmax(120px, 1fr)", render: (e) => `${e.operatorName} (${e.operatorId})` },
  { key: "action", label: "액션", width: "minmax(160px, 1.2fr)", render: (e) => <span className={styles.auditAction}>{e.action}</span> },
  { key: "target", label: "대상", width: "minmax(120px, 1fr)", render: (e) => e.target },
  {
    key: "result",
    label: "결과",
    width: "90px",
    // 3분 렌더(OK·DENIED·ERROR). 색 단독 금지라 기호·라벨을 병기한다
    render: (e) =>
      e.result === "OK" ? (
        <span className="status-pill" data-health="success">
          ● 성공
        </span>
      ) : e.result === "ERROR" ? (
        <span className="status-pill" data-health="warning">
          ▲ 오류
        </span>
      ) : (
        <span className="status-pill" data-health="danger">
          ■ 거부
        </span>
      ),
  },
  { key: "approval", label: "승인 ID", width: "minmax(120px, 1fr)", render: (e) => e.approvalId ?? "-" },
];

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export default function AuditPage() {
  const load = useCallback(() => getAdminApi().getAudit(), []);
  const { data: rows, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(load, { intervalMs: 3000, isSessionError });

  return (
    <div className={styles.page}>
      <PageHeader
        title="감사 로그"
        desc="append-only. 전 관리 액션(조회 포함)이 적재됩니다."
        actions={stale ? <StaleBadge since={lastUpdatedAt} /> : undefined}
      />
      <PanelCard title="관리 액션 감사">
        {loading ? (
          <Skeleton variant="card" width="100%" height={280} />
        ) : error || !rows ? (
          <AdminErrorState reason={errorReason} onRetry={reload} />
        ) : (
          <div className={styles.staleRegion} data-stale={stale ? "" : undefined}>
            <DataTable
              columns={COLUMNS}
              rows={rows}
              rowKey={(e) => e.id}
              rowHeight={44}
              ariaLabel="관리 감사 로그"
              empty={
                <div className="empty-state">
                  <span className="empty-state__title">기록된 액션이 없습니다</span>
                </div>
              }
            />
          </div>
        )}
      </PanelCard>
    </div>
  );
}
