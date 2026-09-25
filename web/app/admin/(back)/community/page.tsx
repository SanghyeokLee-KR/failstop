"use client";

/**
 * 신고 처리는 대상 상태 변경과 신고 종결이 별개 엔드포인트라 원자적이지 않다(§6-D).
 * 앞이 되고 뒤가 실패한 경우를 문구로 구분한다(숨김은 재시도가 안전하다).
 */

import { useCallback, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type {
  CommunityReport,
  ContentStatus,
  ModerationAction,
  ReportReason,
  ReportResolution,
  ReportStatus,
} from "@/lib/admin/types";
import Pager from "@/components/Pager";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import { PageHeader, PermissionNote, Segmented, segmentedTabId, StateChip } from "@/components/admin/ui";
import { useCursorPages } from "@/components/admin/useCursorPages";
import { QUEUE_PAGE_SIZE } from "@/lib/admin/paging";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { useCan } from "@/lib/admin/store";
import { toast } from "@/components/Toast";
import { formatTimeHMS } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const TABS = [
  { value: "PENDING" as const, label: "대기" },
  { value: "ACTIONED" as const, label: "조치" },
  { value: "DISMISSED" as const, label: "기각" },
  { value: "REVIEWED" as const, label: "확인" },
];

const REASON_LABEL: Record<ReportReason, string> = {
  SPAM: "스팸",
  ABUSE: "욕설·비방",
  ILLEGAL: "불법",
  ETC: "기타",
};

/** 대상 상태는 경보가 아니라 사실이다. 무채 상태 칩(§12-14)으로 적고 색을 쓰지 않는다 */
const TARGET_LABEL: Record<ContentStatus, string> = {
  VISIBLE: "노출 중",
  HIDDEN: "숨김",
  DELETED: "삭제됨",
};

const TYPE_LABEL: Record<CommunityReport["targetType"], string> = {
  POST: "게시글",
  COMMENT: "댓글",
};

const EMPTY_LABEL: Record<ReportStatus, string> = {
  PENDING: "처리 대기 중인 신고 없음",
  ACTIONED: "조치한 신고 없음",
  DISMISSED: "기각한 신고 없음",
  REVIEWED: "확인만 한 신고 없음",
};

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

/* 문의 큐와 같은 쪽 크기. 폴링이 같은 쪽을 다시 읽으므로 쪽이 커지면 비용이 계속 나간다 */
const PAGE_SIZE = 50;

/** 판단 하나가 무엇을 부르는지. moderation이 null이면 대상을 건드리지 않고 종결만 한다 */
interface Decision {
  key: string;
  label: string;
  moderation: ModerationAction | null;
  resolution: ReportResolution;
  confirm?: boolean;
}

const DECISIONS: Decision[] = [
  { key: "hide", label: "숨김 처리", moderation: "HIDE", resolution: "ACTIONED" },
  { key: "delete", label: "삭제 처리", moderation: "DELETE", resolution: "ACTIONED", confirm: true },
  { key: "dismiss", label: "문제 없음", moderation: null, resolution: "DISMISSED" },
  { key: "review", label: "확인만", moderation: null, resolution: "REVIEWED" },
];

/** 표시 순서. 대상을 건드리지 않는 종결 묶음이 앞, 대상을 바꾸는 묶음이 뒤다 */
const DECISION_GROUPS: string[][] = [
  ["review", "dismiss"],
  ["hide", "delete"],
];

export default function AdminCommunityPage() {
  const canMod = useCan("MEMBER_MOD");
  const [status, setStatus] = useState<ReportStatus>("PENDING");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [pendingDelete, setPendingDelete] = useState<CommunityReport | null>(null);

  const loader = useCallback(
    (cursor: string | null) =>
      getAdminApi().getCommunityReports(status, { cursor, limit: QUEUE_PAGE_SIZE }),
    [status],
  );
  const { page, rows, hasMore, move, res } = useCursorPages<CommunityReport>(loader, {
    key: status,
    isSessionError,
  });

  const apply = async (row: CommunityReport, d: Decision) => {
    if (busyId !== null) return;
    setBusyId(row.reportId);
    const api = getAdminApi();
    let moderated = false;
    try {
      if (d.moderation) {
        if (!row.targetExternalId) {
          throw new AdminError("not_found", "대상 식별자 없음. 상태 변경 불가");
        }
        await api.moderateCommunityTarget(row.targetType, row.targetExternalId, d.moderation);
        moderated = true;
      }
      await api.resolveCommunityReport(row.reportId, d.resolution);
      toast(`${d.label} 완료`, "success");
      void res.reload();
    } catch (e) {
      const why = e instanceof Error ? e.message : "처리 실패";
      // 앞이 되고 뒤가 실패한 경우를 삼키면 대상은 바뀐 채 큐에 그대로 남아 두 번 처리된다
      toast(moderated ? `대상은 바꿨으나 신고 종결 실패. ${why}` : why, "error");
      if (moderated) void res.reload();
    } finally {
      setBusyId(null);
    }
  };

  const onPick = (row: CommunityReport, d: Decision) => {
    if (d.confirm) {
      setPendingDelete(row);
      return;
    }
    void apply(row, d);
  };

  const deleteDecision = DECISIONS.find((d) => d.key === "delete")!;

  return (
    <div className={styles.page}>
      <PageHeader
        title="커뮤니티 신고"
        desc="회원 신고 큐입니다. 대상 처리와 신고 종결을 함께 기록합니다"
        actions={<ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />}
      />

      {!canMod && <PermissionNote>회원 운영 권한(MEMBER_MOD 이상) 없음. 조회만 가능</PermissionNote>}

      <div className={styles.transferTabs}>
        <Segmented items={TABS} value={status} onChange={setStatus} ariaLabel="신고 상태 필터" panelId="reports-panel" />
      </div>

      <PanelCard title="신고 목록">
        {/* 필터 탭이 고르는 영역이다. 탭만 있고 패널이 없으면 낭독이 무엇을 바꿨는지 잇지 못한다 */}
        <div role="tabpanel" id="reports-panel" aria-labelledby={segmentedTabId("reports-panel", status)} tabIndex={0}>
          {res.error ? (
            <AdminErrorState reason={res.errorReason} onRetry={() => void res.reload()} />
          ) : res.data === null ? (
            <Skeleton variant="card" width="100%" height={240} />
          ) : rows.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">{EMPTY_LABEL[status]}</span>
            </div>
          ) : (
            <ol className={styles.inquiryList}>
              {rows.map((row) => {
                const terminal = row.targetStatus === "DELETED";
                return (
                  <li key={row.reportId} className={styles.inquiryItem}>
                    <div className={styles.inquiryMeta}>
                      <span className={styles.categoryChip}>{REASON_LABEL[row.reason]}</span>
                      <span className={styles.auditNs}>{TYPE_LABEL[row.targetType]}</span>
                      {row.targetStatus && (
                        <StateChip tone="neutral" label={TARGET_LABEL[row.targetStatus]} />
                      )}
                      {row.targetAuthor && <span className={styles.auditNs}>{row.targetAuthor}</span>}
                      <time className={`${styles.inquiryTime} num`} dateTime={new Date(row.createdAt).toISOString()}>
                        {formatTimeHMS(new Date(row.createdAt).toISOString())}
                      </time>
                    </div>

                    {/* 신고당한 원문이 판단의 근거다. 사유만 보여주면 눈 감고 처리하게 된다 */}
                    <h3 className={styles.inquiryTitle}>
                      {row.targetExcerpt ?? "대상 원문을 불러오지 못함"}
                    </h3>
                    {row.detail && <p className={styles.inquiryBody}>신고 사유: {row.detail}</p>}

                    {/* 조치는 오른쪽 끝에 모으고 종결만 하는 판단과 대상을 바꾸는 판단을 두 묶음으로
                        가른다. 되돌릴 수 없는 삭제를 맨 끝에 danger로 둔다(§12-5) */}
                    {status === "PENDING" && (
                      <div className={styles.reportActions}>
                        {terminal && (
                          <span className={styles.formHelp}>이미 삭제된 대상. 종결만 가능</span>
                        )}
                        {DECISION_GROUPS.map((keys) => {
                          const group = keys
                            .map((k) => DECISIONS.find((d) => d.key === k)!)
                            .filter((d) => !(terminal && d.moderation));
                          if (group.length === 0) return null;
                          return (
                            <span key={keys.join()} className={styles.reportGroup}>
                              {group.map((d) => (
                                <button
                                  key={d.key}
                                  type="button"
                                  className={d.key === "delete" ? styles.dangerBtn : styles.neutralBtn}
                                  disabled={!canMod || busyId !== null}
                                  aria-busy={busyId === row.reportId}
                                  onClick={() => onPick(row, d)}
                                >
                                  {d.label}
                                </button>
                              ))}
                            </span>
                          );
                        })}
                      </div>
                    )}
                  </li>
                );
              })}
            </ol>
          )}

          <Pager
            page={page}
            hasMore={hasMore}
            onMove={move}
            ariaLabel="신고 큐 쪽 이동"
            busy={busyId !== null}
          />
        </div>
      </PanelCard>

      {pendingDelete && (
        <ConfirmDialog
          title="게시물 삭제 처리"
          description="삭제는 종료 상태. 숨김 복구로 되돌아오지 않음"
          target={pendingDelete.targetExcerpt ?? `신고 ${pendingDelete.reportId}`}
          blastRadius={`${TYPE_LABEL[pendingDelete.targetType]} 1건 영구 비공개`}
          confirmLabel="삭제 처리"
          tone="danger"
          busy={busyId === pendingDelete.reportId}
          onConfirm={() => {
            const row = pendingDelete;
            setPendingDelete(null);
            void apply(row, deleteDecision);
          }}
          onClose={() => setPendingDelete(null)}
        />
      )}
    </div>
  );
}
