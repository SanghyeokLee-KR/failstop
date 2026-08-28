"use client";

/**
 * 발급형 2인 승인 목록(§3-6). 요청->발급->실행을 우회 없이 시연한다.
 * 요청(로컬 의도) -> 발급(다른 운영자가 POST /approvals로 토큰 발급) -> 실행(요청자가 토큰 소각).
 * 반결탁: 발급 버튼은 요청 운영자 본인 세션에는 노출하지 않고, 서버도 인증서 CN으로 이중 강제한다.
 * 로컬 의도와 발급 토큰은 브라우저 보관소(useApprovalVault)가 들고, 원장 상태는 listApprovals가 준다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { ACTION_LABEL } from "@/lib/admin/roles";
import { useAdminSession, useApprovalVault } from "@/lib/admin/store";
import type { ApprovalIntent } from "@/lib/admin/store";
import type { ApprovalRequest, DestructiveAction } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import { toast } from "@/components/Toast";
import ConfirmDialog from "./ConfirmDialog";
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

  const [rows, setRows] = useState<ApprovalRequest[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  // 소각은 되돌릴 수 없어 확인 다이얼로그로 한 번 더 막는다
  const [execTarget, setExecTarget] = useState<ApprovalRequest | null>(null);
  const rowRefs = useRef<Record<string, HTMLLIElement | null>>({});
  // 액션 후 대상 행이 사라질 때(발급 시 intent 소거, 실행 시 상태 전이) 포커스 폴백 목적지.
  // 목록 ul이 언마운트되는 경로(마지막 대기 행 종결)는 빈 상태 컨테이너가 받는다
  const listRef = useRef<HTMLUListElement>(null);
  const emptyRef = useRef<HTMLDivElement>(null);
  // 폴링과 액션 후 refresh가 병행한다. 변이 직전 출발한 폴링 응답이 늦게 도착해 최신 상태를
  // 변이 전 스냅샷으로 되돌리지 않도록, 최신 요청보다 오래된 응답은 폐기한다
  const refreshSeq = useRef(0);

  const inScope = useCallback(
    (action: DestructiveAction) => !actions || actions.includes(action),
    [actions],
  );

  const refresh = useCallback(async () => {
    const seq = ++refreshSeq.current;
    try {
      /* 실행 대기 목록이라 ISSUED만 본다. 상태 없이 부르면 소비·만료된 이력까지 받아
         화면에서 다시 걸러야 하고, 이력이 쌓일수록 첫 쪽이 처리 대상이 아닌 행으로 찬다 */
      const all = await getAdminApi().listApprovals("ISSUED");
      if (seq !== refreshSeq.current) return; // 더 새 요청이 출발했다. 스테일 응답 폐기
      setRows(all.data.filter((a) => inScope(a.action)));
    } catch {
      // 세션 만료 등은 레이아웃 게이트가 처리한다
    }
  }, [inScope]);

  useEffect(() => {
    void refresh();
    // 5초 주기(12건/분). 동거 폴러(대시보드·config)와 합산해도 관리 레이트리밋(60건/분) 내 유지
    const iv = setInterval(() => void refresh(), 5000);
    return () => clearInterval(iv);
  }, [refresh]);

  const act = async (id: string, fn: () => Promise<unknown>, ok: string) => {
    setBusy(id);
    try {
      await fn();
      toast(ok, "info");
      await refresh();
      onChange?.();
      setExecTarget(null);
      // 발급은 intent 행을 소거하고 실행은 행 상태를 바꾼다. 남아 있으면 그 행, 아니면 목록으로.
      // 마지막 대기 행이 종결로 넘어가면 목록 ul 자체가 언마운트되므로 빈 상태 컨테이너까지
      // 폴백해 body 유실을 막는다(§9 키보드 흐름)
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
        await getAdminApi().consumeApproval(held);
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

  // 빈 상태 문법 통일: 좌측 캡션이 아니라 중앙 계기형. 다음 행동(요청 유입 경로)을 desc가 안내한다
  const emptyState = (
    <div className="empty-state empty-state--compact" ref={emptyRef} tabIndex={-1}>
      <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
      <span className="empty-state__title">{emptyLabel}</span>
      {emptyDesc && <span className="empty-state__desc">{emptyDesc}</span>}
    </div>
  );

  if (emptyActive && doneRows.length === 0) {
    return emptyState;
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
              </div>
              <div className={styles.approvalActions}>
                {health && (
                  <span className="status-pill" data-health={health}>
                    {HEALTH_ICON[health]} {PHASE_LABEL.REQUESTED}
                  </span>
                )}
                {mine ? (
                  <span className={styles.waitNote}>다른 운영자 발급 대기</span>
                ) : (
                  <button
                    type="button"
                    className={styles.approveBtn}
                    disabled={busy === id}
                    onClick={() => void issue(intent)}
                  >
                    발급
                  </button>
                )}
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
                {phase === "AWAITING_EXECUTION" && mine && !held && <span className={styles.waitNote}>발급 토큰 수신 대기</span>}
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
