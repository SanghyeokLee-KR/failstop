"use client";

/**
 * 다이얼로그를 두지 않는 이유는 긴 답변을 쓰는 동안 원문이 가려지기 때문이다(§4-G).
 * 질문과 답을 같은 화면에 나란히 둔다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { AdminPage, AdminInquiry } from "@/lib/admin/types";
import Pager from "@/components/Pager";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import SegmentedTab from "@/components/SegmentedTab";
import { PageHeader } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { toast } from "@/components/Toast";
import { formatTimeHMS } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const TABS = [
  { value: "OPEN" as const, label: "대기" },
  { value: "ANSWERED" as const, label: "답변 완료" },
];

const CATEGORY_LABEL: Record<AdminInquiry["category"], string> = {
  TRADE: "거래",
  TRANSFER: "입출금",
  ACCOUNT: "계정",
  ETC: "기타",
};

const MAX_ANSWER = 2000;

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

/* 한 쪽 50건. 폴링이 같은 쪽을 다시 읽으므로 쪽이 커지면 그 비용이 계속 나간다 */
const PAGE_SIZE = 50;

export default function AdminSupportPage() {
  const [status, setStatus] = useState<"OPEN" | "ANSWERED">("OPEN");
  /* 답변 초안은 문의별로 따로 든다. 하나의 상태로 두면 다른 문의를 열었을 때 앞서 쓰던
     글이 그대로 남아 엉뚱한 문의에 붙는다 */
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);

  /* ANSWERED는 답변 뒤에도 누적된다. status가 바뀌면 쪽 경계가 새로 생기므로
     커서를 버리고 첫 쪽부터 다시 센다 */
  const [page, setPage] = useState(0);
  const cursors = useRef<(string | null)[]>([null]);
  const loader = useCallback(async () => {
    const r = await getAdminApi().getInquiries(status, {
      cursor: cursors.current[page] ?? null,
      limit: PAGE_SIZE,
    });
    cursors.current[page + 1] = r.nextCursor;
    return r;
  }, [status, page]);
  const res = useAdminResource<AdminPage<AdminInquiry>>(loader, { isSessionError });
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
  const changeStatus = (next: "OPEN" | "ANSWERED") => {
    cursors.current = [null];
    setPage(0);
    setStatus(next);
  };

  const submit = async (row: AdminInquiry) => {
    const text = (drafts[row.externalId] ?? "").trim();
    if (!text || busyId) return;
    setBusyId(row.externalId);
    try {
      await getAdminApi().answerInquiry(row.externalId, text);
      setDrafts((d) => {
        const next = { ...d };
        delete next[row.externalId];
        return next;
      });
      toast("답변 등록", "success");
      void res.reload();
    } catch (e) {
      toast(supportAdminErrorCopy(e), "error");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className={styles.page}>
      <PageHeader
        title="문의 답변"
        desc="회원 문의. 답변은 사용자 문의 화면에 그대로 표시된다"
        actions={<ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />}
      />

      <div className={styles.transferTabs}>
        <SegmentedTab items={TABS} value={status} onChange={changeStatus} ariaLabel="상태 필터" />
      </div>

      <PanelCard title="문의 목록">
        {res.error ? (
          <AdminErrorState reason={res.errorReason} onRetry={() => void res.reload()} />
        ) : res.data === null ? (
          <Skeleton variant="card" width="100%" height={240} />
        ) : rows.length === 0 ? (
          <div className="empty-state empty-state--compact">
            <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
            <span className="empty-state__title">
              {status === "OPEN" ? "대기 중인 문의 없음" : "답변한 문의 없음"}
            </span>
          </div>
        ) : (
          <ol className={styles.inquiryList}>
            {rows.map((row) => (
              <li key={row.externalId} className={styles.inquiryItem}>
                <div className={styles.inquiryMeta}>
                  <span className={styles.categoryChip}>{CATEGORY_LABEL[row.category]}</span>
                  <span className={styles.auditNs}>{row.userEmail}</span>
                  <time className={`${styles.inquiryTime} num`} dateTime={new Date(row.createdAt).toISOString()}>
                    {formatTimeHMS(new Date(row.createdAt).toISOString())}
                  </time>
                </div>
                <h3 className={styles.inquiryTitle}>{row.title}</h3>
                <p className={styles.inquiryBody}>{row.body}</p>

                {row.status === "ANSWERED" ? (
                  <div className={styles.inquiryAnswer}>
                    <span className={styles.inquiryAnswerLabel}>등록된 답변</span>
                    <p className={styles.inquiryAnswerBody}>{row.answer}</p>
                  </div>
                ) : (
                  <div className={styles.answerForm}>
                    <label className={styles.answerField}>
                      <span className="sr-only">답변 내용</span>
                      <textarea
                        className={`focus-ring-owner ${styles.answerInput}`}
                        value={drafts[row.externalId] ?? ""}
                        onChange={(e) =>
                          setDrafts((d) => ({ ...d, [row.externalId]: e.target.value }))
                        }
                        maxLength={MAX_ANSWER}
                        rows={4}
                        placeholder="사용자에게 그대로 표시됨"
                        disabled={busyId === row.externalId}
                      />
                    </label>
                    <div className={styles.answerActions}>
                      <span className={`${styles.answerCount} num`}>
                        {(drafts[row.externalId] ?? "").trim().length} / {MAX_ANSWER}
                      </span>
                      <button
                        type="button"
                        className="btn btn--primary"
                        onClick={() => void submit(row)}
                        disabled={!(drafts[row.externalId] ?? "").trim() || busyId === row.externalId}
                        aria-busy={busyId === row.externalId}
                      >
                        {busyId === row.externalId ? "등록 중" : "답변 등록"}
                      </button>
                    </div>
                  </div>
                )}
              </li>
            ))}
          </ol>
        )}

        <Pager page={page} hasMore={hasMore} onMove={setPage} ariaLabel="문의 큐 쪽 이동" />
      </PanelCard>
    </div>
  );
}

/** 서버 문장을 그대로 띄우지 않는다. 코드를 화면 어휘로 옮긴다 */
function supportAdminErrorCopy(e: unknown): string {
  const code = e instanceof AdminError ? e.code : "";
  switch (code) {
    case "support_already_answered":
      return "이미 답변된 문의. 목록 새로고침 필요";
    case "invalid_support_input":
      return "답변 내용 확인 필요";
    case "admin_forbidden":
      return "권한 부족. MEMBER_MOD 필요";
    case "not_found":
      return "대상 문의 없음";
    default:
      return "등록 실패. 잠시 후 다시 시도 필요";
  }
}
