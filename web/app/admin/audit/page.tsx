"use client";

/**
 * 감사 로그(/admin/audit). append-only 조회. 전 관리 액션이 적재 대상(§3-2).
 * admin_audit_log는 forbid_mutation 트리거로 수정·삭제가 차단된다(읽기만 제공).
 */

import { useCallback, useEffect, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import type { AuditEntry } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import { PageHeader } from "@/components/admin/ui";
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
    render: (e) =>
      e.result === "OK" ? (
        <span className="status-pill" data-health="success">
          ● 성공
        </span>
      ) : (
        <span className="status-pill" data-health="danger">
          ▲ 거부
        </span>
      ),
  },
  { key: "approval", label: "승인 ID", width: "minmax(120px, 1fr)", render: (e) => e.approvalId ?? "-" },
];

export default function AuditPage() {
  const [rows, setRows] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState(false);

  const load = useCallback(async () => {
    try {
      setRows(await getAdminApi().getAudit());
      setError(false);
    } catch {
      // 폴링 중 실패(기존 rows 존재)는 조용히 유지하고 초기 로드 실패만 에러 상태로 노출
      setError(true);
    }
  }, []);

  useEffect(() => {
    void load();
    const iv = setInterval(() => void load(), 3000);
    return () => clearInterval(iv);
  }, [load]);

  return (
    <div className={styles.page}>
      <PageHeader title="감사 로그" desc="append-only. 전 관리 액션(조회 포함)이 적재됩니다." />
      <PanelCard title="관리 액션 감사">
        {error && rows && (
          <p className={styles.auditStale} role="status">
            갱신 실패. 마지막 조회 결과를 표시 중이에요.
          </p>
        )}
        {error && !rows ? (
          <div className="empty-state" role="alert">
            <span className="empty-state__title">감사 로그를 불러오지 못했어요</span>
            <span className="empty-state__desc">세션이 만료됐거나 서버에 연결하지 못했어요.</span>
            <button type="button" className="btn btn--secondary" onClick={() => void load()}>
              다시 시도
            </button>
          </div>
        ) : !rows ? (
          <Skeleton variant="card" width="100%" height={280} />
        ) : (
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey={(e) => e.id}
            rowHeight={44}
            ariaLabel="관리 감사 로그"
            empty={
              <div className="empty-state">
                <span className="empty-state__title">기록된 액션이 없어요</span>
              </div>
            }
          />
        )}
      </PanelCard>
    </div>
  );
}
