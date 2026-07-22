"use client";

/**
 * 승인 대기·이력 목록(§1-4). 2인 승인 라이프사이클 UI.
 * 요청(다른 운영자 승인 대기) → 승인(요청자와 다른 운영자) → 실행(요청자 본인 소각).
 * 반결탁: 요청자와 같은 운영자에게는 승인 버튼을 노출하지 않고, 서버도 이중 강제한다.
 */

import { useCallback, useEffect, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { ACTION_LABEL } from "@/lib/admin/roles";
import { useAdminSession } from "@/lib/admin/store";
import type { ApprovalRequest, DestructiveAction } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import { toast } from "@/components/Toast";
import styles from "./admin.module.css";

/**
 * 표시 단계. 정본 state는 ISSUED|CONSUMED|EXPIRED|REJECTED뿐이라(§7-7),
 * "승인 대기"와 "실행 대기"는 ISSUED + approverId 유무로 파생한다.
 */
type ApprovalPhase = "AWAITING_APPROVAL" | "AWAITING_EXECUTION" | "CONSUMED" | "EXPIRED" | "REJECTED";

function phaseOf(a: ApprovalRequest): ApprovalPhase {
  if (a.state === "ISSUED") return a.approverId ? "AWAITING_EXECUTION" : "AWAITING_APPROVAL";
  return a.state;
}

const PHASE_LABEL: Record<ApprovalPhase, string> = {
  AWAITING_APPROVAL: "승인 대기",
  AWAITING_EXECUTION: "실행 대기",
  CONSUMED: "실행 완료",
  EXPIRED: "만료",
  REJECTED: "거부됨",
};

const PHASE_HEALTH: Record<ApprovalPhase, "success" | "warning" | "danger" | null> = {
  AWAITING_APPROVAL: "warning",
  AWAITING_EXECUTION: "warning",
  CONSUMED: "success",
  EXPIRED: null,
  REJECTED: "danger",
};

interface Props {
  /** 이 액션 집합만 표시. 생략 시 전체 */
  actions?: DestructiveAction[];
  /** 실행·상태 변화 후 부모가 관련 데이터를 다시 읽도록 통지 */
  onChange?: () => void;
  emptyLabel?: string;
}

export default function ApprovalList({ actions, onChange, emptyLabel = "대기 중인 승인이 없어요" }: Props) {
  const session = useAdminSession();
  const [items, setItems] = useState<ApprovalRequest[]>([]);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const all = await getAdminApi().listApprovals();
      setItems(actions ? all.filter((a) => actions.includes(a.action)) : all);
    } catch {
      // 세션 만료 등은 레이아웃 게이트가 처리한다
    }
    // actions 배열은 부모가 useMemo로 안정화한다
  }, [actions]);

  useEffect(() => {
    void refresh();
    const iv = setInterval(() => void refresh(), 2000);
    return () => clearInterval(iv);
  }, [refresh]);

  const act = async (id: string, fn: () => Promise<unknown>, ok: string) => {
    setBusy(id);
    try {
      await fn();
      toast(ok, "info");
      await refresh();
      onChange?.();
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리에 실패했어요", "error");
    } finally {
      setBusy(null);
    }
  };

  const api = getAdminApi();

  if (items.length === 0) {
    return <p className={styles.approvalEmpty}>{emptyLabel}</p>;
  }

  return (
    <ul className={styles.approvalList}>
      {items.map((a) => {
        const mine = session?.operatorId === a.requesterId;
        const phase = phaseOf(a);
        const health = PHASE_HEALTH[phase];
        return (
          <li key={a.id} className={styles.approvalItem}>
            <div className={styles.approvalMain}>
              <span className={styles.approvalAction}>{ACTION_LABEL[a.action]}</span>
              <span className={styles.approvalTarget}>{a.targetLabel}</span>
            </div>
            <div className={styles.approvalMeta}>
              <span>
                요청 {a.requesterName} · {formatTimeHMS(a.issuedAt)}
              </span>
              {a.approverName && <span>승인 {a.approverName}</span>}
            </div>
            <div className={styles.approvalActions}>
              {health ? (
                <span className="status-pill" data-health={health}>
                  {phase === "CONSUMED" ? "●" : phase === "REJECTED" ? "▲" : "◐"} {PHASE_LABEL[phase]}
                </span>
              ) : (
                <span className={styles.neutralPill}>{PHASE_LABEL[phase]}</span>
              )}

              {phase === "AWAITING_APPROVAL" && !mine && (
                <>
                  <button
                    type="button"
                    className={styles.approveBtn}
                    disabled={busy === a.id}
                    onClick={() => void act(a.id, () => api.approve(a.id), "승인했어요")}
                  >
                    승인
                  </button>
                  <button
                    type="button"
                    className={styles.neutralBtn}
                    disabled={busy === a.id}
                    onClick={() => void act(a.id, () => api.reject(a.id), "거부했어요")}
                  >
                    거부
                  </button>
                </>
              )}
              {phase === "AWAITING_APPROVAL" && mine && <span className={styles.waitNote}>다른 운영자 승인 대기</span>}
              {phase === "AWAITING_EXECUTION" && mine && (
                <button
                  type="button"
                  className={styles.dangerBtn}
                  disabled={busy === a.id}
                  onClick={() => void act(a.id, () => api.execute(a.id), "실행했어요")}
                >
                  실행
                </button>
              )}
              {phase === "AWAITING_EXECUTION" && !mine && <span className={styles.waitNote}>요청자 실행 대기</span>}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
