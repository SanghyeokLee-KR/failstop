"use client";

/**
 * 요청 주체가 운영자가 아니라 2인 승인을 걸지 않고 확인 다이얼로그로 대체한다(§4-E).
 * 승인은 잠긴 금액이 GATEWAY로 빠져나가 비가역이고, 거절은 사유가 필수다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { AdminPage, AdminTransfer, AdminTransferStatus } from "@/lib/admin/types";
import Pager from "@/components/Pager";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import SegmentedTab from "@/components/SegmentedTab";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import { PageHeader } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
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

/* 한 쪽 50건. 폴링이 같은 쪽을 다시 읽으므로 쪽이 커지면 그 비용이 계속 나간다 */
const PAGE_SIZE = 50;

export default function AdminTransfersPage() {
  const [status, setStatus] = useState<AdminTransferStatus>("REQUESTED");
  const [pending, setPending] = useState<{ row: AdminTransfer; action: "approve" | "reject" } | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  /* COMPLETED·CANCELED는 처리 뒤에도 누적된다. status가 바뀌면 쪽 경계가 새로
     생기므로 커서를 버리고 첫 쪽부터 센다 */
  const [page, setPage] = useState(0);
  const cursors = useRef<(string | null)[]>([null]);
  const loader = useCallback(async () => {
    const r = await getAdminApi().getTransfers(status, {
      cursor: cursors.current[page] ?? null,
      limit: PAGE_SIZE,
    });
    cursors.current[page + 1] = r.nextCursor;
    return r;
  }, [status, page]);
  const res = useAdminResource<AdminPage<AdminTransfer>>(loader, { intervalMs: 15000, isSessionError });
  const rows = res.data?.data ?? [];
  const hasMore = res.data?.hasMore ?? false;
  /* 상태와 쪽을 한 효과로 묶는다. 둘로 나누면 쪽이 이미 0일 때 상태만 바뀌는 경우
     setPage(0)이 무동작이라 재조회가 걸리지 않고, 이전 필터의 결과가 새 탭 아래 남는다
     (다음 폴링 틱까지). 상태 변경은 커서를 먼저 버리므로 순서도 맞는다 */
  useEffect(() => {
    void res.reload();
    // reload는 안정 참조다. 상태·쪽 변화에만 반응한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, page]);

  /* 상태가 바뀌면 쪽 경계가 새로 생긴다. 들고 있던 커서는 이전 조건의 것이라 버린다 */
  const changeStatus = (next: AdminTransferStatus) => {
    cursors.current = [null];
    setPage(0);
    setStatus(next);
  };

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
        sortValue: (r) => r.createdAt,
        firstDir: "desc",
        render: (r) => <span className="num">{formatTimeHMS(new Date(r.createdAt).toISOString())}</span>,
      },
      {
        key: "status",
        label: "상태",
        width: "110px",
        render: (r) =>
          r.status === "REQUESTED" ? (
            <span className="status-pill" data-health="warning">
              대기
            </span>
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
        render: (r) =>
          r.status === "REQUESTED" ? (
            <div className={styles.rowActions}>
              <button
                type="button"
                className="btn btn--secondary"
                onClick={() => setPending({ row: r, action: "approve" })}
              >
                승인
              </button>
              <button
                type="button"
                className="btn btn--secondary"
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
        desc="회원 원화 출금 요청. 승인하면 잠긴 금액이 빠져나가고 되돌릴 수 없다"
        actions={res.stale ? <StaleBadge /> : undefined}
      />

      {/* 상태 3종은 배타 선택이라 세그먼트가 맞다. 새 칩 어휘를 만들지 않는다 */}
      <div className={styles.transferTabs}>
        <SegmentedTab items={TABS} value={status} onChange={changeStatus} ariaLabel="상태 필터" />
      </div>

      <PanelCard title="요청 목록">
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
            defaultAlign="center"
          />
        )}

        <Pager page={page} hasMore={hasMore} onMove={setPage} ariaLabel="출금 큐 쪽 이동" />
      </PanelCard>

      {pending && (
        <ConfirmDialog
          title={pending.action === "approve" ? "출금 승인" : "출금 거절"}
          description={
            pending.action === "approve"
              ? "잠긴 금액이 즉시 빠져나간다. 되돌릴 수 없다"
              : "잠금이 풀려 요청자 가용 잔고로 돌아간다"
          }
          target={`${pending.row.userEmail} · ${formatKRW(pending.row.amount)}원`}
          blastRadius="요청자 1명"
          confirmLabel={pending.action === "approve" ? "승인 확정" : "거절 확정"}
          tone={
            pending.action === "approve" && Number(pending.row.amount) >= Number(HIGH_AMOUNT)
              ? "danger"
              : "warning"
          }
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
