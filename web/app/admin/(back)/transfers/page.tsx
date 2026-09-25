"use client";

/**
 * 요청 주체가 운영자가 아니라 승인 티켓을 걸지 않고 확인 다이얼로그로 대체한다(§4-E).
 * 승인은 잠긴 금액이 GATEWAY로 빠져나가 비가역이고, 거절은 사유가 필수다.
 */

import { useCallback, useMemo, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { AdminTransfer, AdminTransferStatus } from "@/lib/admin/types";
import Pager from "@/components/Pager";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import { PageHeader, Segmented, segmentedTabId } from "@/components/admin/ui";
import { useCursorPages } from "@/components/admin/useCursorPages";
import { QUEUE_PAGE_SIZE } from "@/lib/admin/paging";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { toast } from "@/components/Toast";
import { formatKRW, formatTimeHMS } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const TABS = [
  { value: "REQUESTED" as AdminTransferStatus, label: "대기" },
  { value: "COMPLETED" as AdminTransferStatus, label: "완료" },
  { value: "CANCELED" as AdminTransferStatus, label: "거절·취소" },
];

/** 이 금액을 넘으면 확인 다이얼로그가 주의 톤으로 바뀐다. 한 건의 손해 상한을 눈에 띄게 한다 */
const HIGH_AMOUNT = "10000000";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export default function AdminTransfersPage() {
  const [status, setStatus] = useState<AdminTransferStatus>("REQUESTED");
  const [pending, setPending] = useState<{ row: AdminTransfer; action: "approve" | "reject" } | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  /* COMPLETED·CANCELED는 처리 뒤에도 누적된다. 상태가 바뀌면 쪽 경계가 새로
     생기므로 훅이 커서를 버리고 첫 쪽부터 센다 */
  const loader = useCallback(
    (cursor: string | null) => getAdminApi().getTransfers(status, { cursor, limit: QUEUE_PAGE_SIZE }),
    [status],
  );
  const { page, rows, hasMore, move, res } = useCursorPages<AdminTransfer>(loader, {
    key: status,
    isSessionError,
  });

  const close = () => {
    if (busy) return;
    setPending(null);
    setReason("");
  };

  const confirm = async () => {
    if (!pending || busy) return;
    if (pending.action === "reject" && !reason.trim()) return;
    setBusy(true);
    try {
      if (pending.action === "approve") {
        await getAdminApi().approveTransfer(pending.row.externalId);
        toast("출금 승인 완료", "success");
      } else {
        await getAdminApi().rejectTransfer(pending.row.externalId, reason.trim());
        toast("출금 거절 처리", "info");
      }
      setPending(null);
      setReason("");
      void res.reload();
    } catch (e) {
      toast(transferAdminErrorCopy(e), "error");
    } finally {
      setBusy(false);
    }
  };

  const columns = useMemo<DataColumn<AdminTransfer>[]>(
    () => [
      {
        key: "user",
        label: "요청자",
        width: "minmax(0, 1.2fr)",
        sortValue: (r) => r.userEmail,
        render: (r) => <span className={styles.auditNs}>{r.userEmail || "-"}</span>,
      },
      {
        key: "amount",
        label: "금액",
        width: "160px",
        align: "right",
        sortValue: (r) => Number(r.amount),
        firstDir: "desc",
        render: (r) => (
          <span className="num">
            <b>{formatKRW(r.amount)}</b> <span className="unit-suffix">원</span>
          </span>
        ),
      },
      {
        key: "created",
        label: "요청 시각",
        width: "120px",
        align: "right",
        sortValue: (r) => r.createdAt,
        firstDir: "desc",
        render: (r) => <span className="num">{formatTimeHMS(new Date(r.createdAt).toISOString())}</span>,
      },
      {
        key: "status",
        label: "상태",
        width: "110px",
        align: "center",
        render: (r) =>
          r.status === "REQUESTED" ? (
            /* 대기는 처리할 차례라는 뜻이지 이상 상태가 아니다. 상태색 대신 굵은 잉크로 둔다(§12-10) */
            <span className={styles.queueWait}>대기</span>
          ) : r.status === "COMPLETED" ? (
            /* 완료는 조치가 끝난 행이다. 운영자가 볼 곳은 대기뿐이라 완료까지 색을 쓰면
               큐에서 처리 대상을 골라내는 일이 오히려 어려워진다 */
            <span className={styles.stateText}>완료</span>
          ) : (
            /* 거절·취소는 실패가 아니라 종결이다. danger를 쓰면 사고로 읽힌다 */
            <span className={styles.stateText}>{r.reason ?? "취소"}</span>
          ),
      },
      {
        key: "action",
        label: "처리",
        width: "168px",
        align: "right",
        render: (r) =>
          r.status === "REQUESTED" ? (
            <div className={styles.rowActions}>
              {/* 긍정 확정 하나만 브랜드 면, 되돌릴 수 없는 거절은 danger 테두리다(§12-5) */}
              <button
                type="button"
                className={styles.approveBtn}
                onClick={() => setPending({ row: r, action: "approve" })}
              >
                승인
              </button>
              <button
                type="button"
                className={styles.dangerBtn}
                onClick={() => setPending({ row: r, action: "reject" })}
              >
                거절
              </button>
            </div>
          ) : (
            <span className={styles.stateTextDim}>처리 완료</span>
          ),
      },
    ],
    [],
  );

  return (
    <div className={styles.page}>
      <PageHeader
        title="출금 승인"
        actions={<ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />}
      />

      {/* 상태 3종은 배타 선택이라 세그먼트가 맞다. 새 칩 어휘를 만들지 않는다 */}
      <div className={styles.transferTabs}>
        <Segmented items={TABS} value={status} onChange={setStatus} ariaLabel="상태 필터" panelId="transfers-panel" />
      </div>

      <PanelCard title="요청 목록" actions={<span className={styles.panelMeta}>승인하면 잠긴 금액을 출금하고 되돌릴 수 없습니다</span>}>
        {/* 필터 탭이 고르는 영역이다. 탭만 있고 패널이 없으면 낭독이 무엇을 바꿨는지 잇지 못한다 */}
        <div role="tabpanel" id="transfers-panel" aria-labelledby={segmentedTabId("transfers-panel", status)} tabIndex={0}>
          {res.error ? (
            <AdminErrorState reason={res.errorReason} onRetry={() => void res.reload()} />
          ) : res.data === null ? (
            <Skeleton variant="card" width="100%" height={240} />
          ) : rows.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">
                {status === "REQUESTED" ? "대기 중인 출금 없음" : "해당 상태의 요청 없음"}
              </span>
            </div>
          ) : (
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(r) => r.externalId}
              ariaLabel="출금 요청 목록"
            />
          )}

          <Pager page={page} hasMore={hasMore} onMove={move} ariaLabel="출금 큐 쪽 이동" />
        </div>
      </PanelCard>

      {pending && (
        <ConfirmDialog
          title={pending.action === "approve" ? "출금 승인" : "출금 거절"}
          description={
            pending.action === "approve"
              ? "잠긴 금액을 곧바로 출금합니다. 되돌릴 수 없습니다"
              : "잠금을 풀어 요청자의 가용 잔고로 돌려놓습니다"
          }
          target={`${pending.row.userEmail} · ${formatKRW(pending.row.amount)}원`}
          blastRadius="요청자 1명"
          confirmLabel={pending.action === "approve" ? "승인 확정" : "거절 확정"}
          // 확정은 채운 단추 하나다(§12-5). 거절과 고액 출금 승인은 되돌릴 수 없는 쪽이라 danger다
          tone={pending.action === "reject" || Number(pending.row.amount) >= Number(HIGH_AMOUNT) ? "danger" : "approve"}
          busy={busy}
          onConfirm={confirm}
          onClose={close}
        >
          {pending.action === "reject" && (
            <label className={styles.reasonField}>
              <span className={styles.reasonLabel}>거절 사유</span>
              <input
                className={styles.reasonInput}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                maxLength={200}
                placeholder="요청자에게 그대로 표시됨"
                autoFocus
              />
            </label>
          )}
        </ConfirmDialog>
      )}
    </div>
  );
}

/** 서버 문장을 그대로 띄우지 않는다. 코드를 화면 어휘로 옮긴다 */
function transferAdminErrorCopy(e: unknown): string {
  const code = e instanceof AdminError ? e.code : "";
  switch (code) {
    case "transfer_not_pending":
      return "이미 처리된 요청. 목록 새로고침 필요";
    case "admin_forbidden":
      return "권한 부족. MEMBER_MOD 필요";
    case "invalid_parameter":
      return "거절 사유 필요";
    case "not_found":
      return "대상 요청 없음";
    default:
      return "처리 실패. 잠시 후 다시 시도 필요";
  }
}
