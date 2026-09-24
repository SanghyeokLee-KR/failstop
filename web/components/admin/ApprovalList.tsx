"use client";

/**
 * 요청은 로컬 의도, 발급은 승인 티켓 생성, 실행은 요청자의 토큰 소각이다(§3-6).
 * 1인 승인이라 요청 운영자 본인이 발급해 실행한다(ADR 0019). 토큰은 발급한 탭의 메모리에만
 * 있어(store.ts) 다른 운영자가 다른 브라우저에서 발급하면 요청자에게 넘어가는 경로가 없다.
 */

import { useCallback, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { CLOSE_INCOMPLETE, describeCloseIncomplete } from "@/lib/admin/memberClose";
import { ACTION_LABEL } from "@/lib/admin/roles";
import { useAdminSession, useApprovalVault } from "@/lib/admin/store";
import { useApprovalLedger } from "@/lib/admin/approvals";
import type { ApprovalIntent } from "@/lib/admin/store";
import type { ApprovalRequest, DestructiveAction } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import { toast } from "@/components/Toast";
import ConfirmDialog from "./ConfirmDialog";
import { AdminErrorState } from "./ResourceState";
import { HEALTH_ICON } from "./ui";
import styles from "./admin.module.css";

/**
 * 표시 단계. 원장 state는 ISSUED|CONSUMED|EXPIRED|REJECTED뿐이고 발급형이라 ISSUED는 곧 실행 대기다
 * (발급=승인, approverId 항상 존재). REQUESTED는 서버 상태가 아니라 발급 전 로컬 의도다.
 */
type ApprovalPhase = "REQUESTED" | "AWAITING_EXECUTION" | "CONSUMED" | "EXPIRED" | "REJECTED";

const PHASE_LABEL: Record<ApprovalPhase, string> = {
  REQUESTED: "발급 대기",
  AWAITING_EXECUTION: "실행 대기",
  CONSUMED: "실행 완료",
  EXPIRED: "만료",
  REJECTED: "거부됨",
};

const PHASE_HEALTH: Record<ApprovalPhase, "success" | "warning" | "danger" | null> = {
  REQUESTED: "warning",
  AWAITING_EXECUTION: "warning",
  CONSUMED: "success",
  EXPIRED: null,
  REJECTED: "danger",
};

function ledgerPhase(a: ApprovalRequest): ApprovalPhase {
  return a.state === "ISSUED" ? "AWAITING_EXECUTION" : a.state;
}

interface Props {
  /** 이 액션 집합만 표시. 생략 시 전체 */
  actions?: DestructiveAction[];
  /** 실행·상태 변화 후 부모가 관련 데이터를 다시 읽도록 통지 */
  onChange?: () => void;
  emptyLabel?: string;
  /** 빈 상태 보조 안내(다음 행동·유입 경로). 생략 시 제목만 */
  emptyDesc?: string;
}

export default function ApprovalList({
  actions,
  onChange,
  emptyLabel = "대기 중이거나 실행 가능한 승인 없음",
  emptyDesc,
}: Props) {
  const session = useAdminSession();
  const intents = useApprovalVault((s) => s.intents);
  const tokens = useApprovalVault((s) => s.tokens);
  const cancelIntent = useApprovalVault((s) => s.cancelIntent);
  const stashToken = useApprovalVault((s) => s.stashToken);
  const dropToken = useApprovalVault((s) => s.dropToken);

  const {
    rows: ledgerRows,
    hasMore: ledgerHasMore,
    loading: ledgerLoading,
    failed: ledgerFailed,
    lastAt: ledgerLastAt,
    invalidate: refresh,
  } = useApprovalLedger();
  const [busy, setBusy] = useState<string | null>(null);
  // 소각은 되돌릴 수 없어 확인 다이얼로그로 한 번 더 막는다
  const [execTarget, setExecTarget] = useState<ApprovalRequest | null>(null);
  const rowRefs = useRef<Record<string, HTMLLIElement | null>>({});
  // 액션 후 대상 행이 사라질 때(발급 시 intent 소거, 실행 시 상태 전이) 포커스 폴백 목적지.
  // 목록 ul이 언마운트되는 경로(마지막 대기 행 종결)는 빈 상태 컨테이너가 받는다
  const listRef = useRef<HTMLUListElement>(null);
  const emptyRef = useRef<HTMLDivElement>(null);
  const inScope = useCallback(
    (action: DestructiveAction) => !actions || actions.includes(action),
    [actions],
  );

  // 원장은 공용 폴러가 한 번 받아 오고 화면은 자기 범위만 거른다. 다섯 화면이 같은 원장을
  // 각자 읽던 다섯 폴러가 하나로 모였다(lib/admin/approvals)
  const rows = useMemo(() => ledgerRows.filter((a) => inScope(a.action)), [ledgerRows, inScope]);

  // fn 이 문자열을 돌려주면 요청은 반영됐지만 남은 조치가 있다는 경고다. 성공 경로(목록 갱신·통지)를 그대로 타고 문구만 바꾼다
  const act = async (id: string, fn: () => Promise<unknown>, ok: string) => {
    setBusy(id);
    try {
      const outcome = await fn();
      if (typeof outcome === "string") toast(outcome, "warning");
      else toast(ok, "info");
      // 다이얼로그를 먼저 닫는다. 열린 채면 배경이 inert라 아래 폴백 focus()가 무시된다
      setExecTarget(null);
      await refresh();
      onChange?.();
      // 발급은 intent 행을 소거하고 실행은 행 상태를 바꾼다. 새 원장이 반영된 뒤라 남은 행이면
      // 그 행, 사라졌으면 목록, 목록째 언마운트됐으면 빈 상태로 옮겨 body 유실을 막는다(§9 키보드 흐름)
      requestAnimationFrame(() => (rowRefs.current[id] ?? listRef.current ?? emptyRef.current)?.focus());
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리 실패", "error");
    } finally {
      setBusy(null);
    }
  };

  const issue = (intent: ApprovalIntent) =>
    act(
      `intent:${intent.key}`,
      async () => {
        const issued = await getAdminApi().issueApproval(intent.requesterId, intent.action, intent.payload);
        stashToken(issued);
        cancelIntent(intent.key);
      },
      "승인 발급 완료. 요청자가 실행 가능",
    );

  const consume = (row: ApprovalRequest) => {
    const held = tokens[row.id];
    if (!held) {
      toast("발급 토큰이 이 세션에 없음. 발급자에게 요청 필요", "error");
      return;
    }
    return act(
      row.id,
      async () => {
        try {
          await getAdminApi().consumeApproval(held);
        } catch (e) {
          // 회원 폐쇄의 409 orders_cancel_incomplete 는 실패가 아니다(API 설계 v2.54 §6-E). 폐쇄는 확정됐고 토큰도 서버에서
          // 소각됐다. 토큰을 치우고 목록·상세를 다시 읽으며, 남은 수와 다음 조치를 경고로 알린다. 정지 마켓 몫이 있으면
          // 재개가 먼저다
          if (e instanceof AdminError && e.code === CLOSE_INCOMPLETE) {
            dropToken(row.id);
            return describeCloseIncomplete(e.detail);
          }
          throw e;
        }
        dropToken(row.id);
      },
      "실행 완료",
    );
  };

  const scopedIntents = intents.filter((i) => inScope(i.action));
  // 대기(발급 대기·실행 대기)와 종결(실행 완료·만료·거부)을 가른다. 종결 카드가 "대기" 아래
  // 섞이면 대기 0건 화면이 일하는 화면처럼 읽힌다
  const activeRows = rows.filter((a) => a.state === "ISSUED");
  const doneRows = rows.filter((a) => a.state !== "ISSUED").slice(0, 6);

  const emptyActive = scopedIntents.length === 0 && activeRows.length === 0;

  // 원장을 한 번도 받지 못했으면 대기 승인이 없다고 적을 근거가 없다. 첫 로드 중과 실패를 가른다
  if (emptyActive && ledgerLastAt === null) {
    return ledgerLoading ? (
      <div className="empty-state empty-state--compact" ref={emptyRef} tabIndex={-1} aria-busy="true">
        <span className="empty-state__title">승인 목록 확인 중</span>
      </div>
    ) : (
      <AdminErrorState
        title="승인 목록을 불러오지 못함"
        desc="대기 승인 유무 미확인. 다시 시도 필요"
        onRetry={() => void refresh()}
      />
    );
  }

  // 빈 상태 문법 통일: 좌측 캡션이 아니라 중앙 계기형. 다음 행동(요청 유입 경로)을 desc가 안내한다
  const emptyState = (
    <div className="empty-state empty-state--compact" ref={emptyRef} tabIndex={-1}>
      <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
      <span className="empty-state__title">{emptyLabel}</span>
      {emptyDesc && <span className="empty-state__desc">{emptyDesc}</span>}
    </div>
  );
  // 마지막 갱신이 실패했으면 보이는 목록이 언제 것인지, 원장을 아예 못 받았는지를 함께 적는다
  const staleNote = ledgerFailed && (
    <p className={styles.approvalCapNote} role="status">
      {ledgerLastAt === null
        ? "승인 원장을 불러오지 못함. 발급된 승인은 표시되지 않음"
        : `갱신 실패. ${formatTimeHMS(new Date(ledgerLastAt).toISOString())} 기준 목록`}
    </p>
  );

  if (emptyActive && doneRows.length === 0) {
    return (
      <>
        {emptyState}
        {staleNote}
      </>
    );
  }

  return (
    <>
      {emptyActive && emptyState}
      {!emptyActive && (
      <ul ref={listRef} className={styles.approvalList} tabIndex={-1} aria-label="승인 목록">

        {scopedIntents.map((intent) => {
          const mine = session?.operatorId === intent.requesterId;
          const health = PHASE_HEALTH.REQUESTED;
          const id = `intent:${intent.key}`;
          return (
            <li
              key={id}
              className={styles.approvalItem}
              ref={(el) => {
                rowRefs.current[id] = el;
              }}
              tabIndex={-1}
            >
              <div className={styles.approvalMain}>
                <span className={styles.approvalAction}>{ACTION_LABEL[intent.action]}</span>
                <span className={styles.approvalTarget}>{intent.targetLabel}</span>
              </div>
              <div className={styles.approvalMeta}>
                <span>
                  요청 {intent.requesterName} · {formatTimeHMS(intent.proposedAt)}
                </span>
                {mine && <span>본인 발급 시 발급·실행이 같은 신원으로 감사 적재</span>}
              </div>
              <div className={styles.approvalActions}>
                {health && (
                  <span className="status-pill" data-health={health}>
                    {HEALTH_ICON[health]} {PHASE_LABEL.REQUESTED}
                  </span>
                )}
                {mine && <span className={styles.waitNote}>본인 발급</span>}
                <button
                  type="button"
                  className={styles.approveBtn}
                  disabled={busy === id}
                  onClick={() => void issue(intent)}
                >
                  발급
                </button>
                <button
                  type="button"
                  className={styles.neutralBtn}
                  disabled={busy === id}
                  onClick={() => {
                    cancelIntent(intent.key);
                    // 취소로 이 행이 언마운트되므로 목록으로 포커스를 옮겨 body 유실을 막는다
                    requestAnimationFrame(() => listRef.current?.focus());
                  }}
                >
                  취소
                </button>
              </div>
            </li>
          );
        })}

        {activeRows.map((a) => {
          const mine = session?.operatorId === a.requesterId;
          const phase = ledgerPhase(a);
          const health = PHASE_HEALTH[phase];
          const owner = session?.operatorId === a.requesterId || session?.operatorId === a.approverId;
          const held = Boolean(tokens[a.id]);
          return (
            <li
              key={a.id}
              className={styles.approvalItem}
              ref={(el) => {
                rowRefs.current[a.id] = el;
              }}
              tabIndex={-1}
            >
              <div className={styles.approvalMain}>
                <span className={styles.approvalAction}>{ACTION_LABEL[a.action]}</span>
                <span className={styles.approvalTarget}>{a.targetLabel}</span>
              </div>
              <div className={styles.approvalMeta}>
                <span>
                  요청 {a.requesterName} · {formatTimeHMS(a.issuedAt)}
                </span>
                {a.approverName && <span>발급 {a.approverName}</span>}
              </div>
              <div className={styles.approvalActions}>
                {health ? (
                  <span className="status-pill" data-health={health}>
                    {HEALTH_ICON[health]} {PHASE_LABEL[phase]}
                  </span>
                ) : (
                  <span className={styles.neutralPill}>{PHASE_LABEL[phase]}</span>
                )}

                {phase === "AWAITING_EXECUTION" && mine && held && (
                  <button type="button" className={styles.dangerBtn} disabled={busy === a.id} onClick={() => setExecTarget(a)}>
                    실행
                  </button>
                )}
                {phase === "AWAITING_EXECUTION" && mine && !held && <span className={styles.waitNote}>다른 세션 발급. 이 세션에 토큰 없음</span>}
                {phase === "AWAITING_EXECUTION" && !mine && <span className={styles.waitNote}>요청자 실행 대기</span>}
                {phase === "AWAITING_EXECUTION" && owner && (
                  <button type="button" className={styles.neutralBtn} disabled={busy === a.id} onClick={() => void act(a.id, () => getAdminApi().rejectApproval(a.id), "거부 완료")}>
                    거부
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
      )}
      {/* 원장의 첫 쪽만 그린다. 받은 범위 밖을 없는 것으로 적지 않는다 */}
      {ledgerHasMore && (
        <p className={styles.approvalCapNote}>첫 쪽 표시. 대기 승인이 더 있음</p>
      )}
      {staleNote}
      {doneRows.length > 0 && (
        // 종결분은 대기와 분리해 이력으로만 남긴다. 버튼 없는 판독 전용 행
        <div className={styles.approvalDoneGroup}>
          <span className={styles.kpiGroupLabel}>최근 처리</span>
          <ul className={styles.approvalList} aria-label="최근 처리된 승인">
            {doneRows.map((a) => {
              const phase = ledgerPhase(a);
              const health = PHASE_HEALTH[phase];
              return (
                <li key={a.id} className={styles.approvalItem} data-done="">
                  <div className={styles.approvalMain}>
                    <span className={styles.approvalAction}>{ACTION_LABEL[a.action]}</span>
                    <span className={styles.approvalTarget}>{a.targetLabel}</span>
                  </div>
                  <div className={styles.approvalMeta}>
                    <span>
                      요청 {a.requesterName} · {formatTimeHMS(a.issuedAt)}
                    </span>
                    {a.approverName && <span>발급 {a.approverName}</span>}
                  </div>
                  <div className={styles.approvalActions}>
                    {health ? (
                      <span className="status-pill" data-health={health}>
                        {HEALTH_ICON[health]} {PHASE_LABEL[phase]}
                      </span>
                    ) : (
                      <span className={styles.neutralPill}>{PHASE_LABEL[phase]}</span>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}
      {execTarget && (
        <ConfirmDialog
          title="승인된 액션 실행"
          description="실행하면 되돌릴 수 없음. 발급 토큰을 소각하고 즉시 반영"
          target={ACTION_LABEL[execTarget.action]}
          blastRadius={execTarget.targetLabel}
          tone="danger"
          confirmLabel="실행"
          busy={busy === execTarget.id}
          onConfirm={() => void consume(execTarget)}
          onClose={() => {
            if (busy !== execTarget.id) setExecTarget(null);
          }}
        />
      )}
    </>
  );
}
