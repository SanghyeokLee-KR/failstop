"use client";

/**
 * 발급형 계약에 서버측 요청 상태가 없어 요청 의도는 이 브라우저 보관소에 둔다(§3-6).
 * 권한 미충족 시 비활성이며, 관측값이 임계를 넘어도 자동 실행은 없다(§6 안전).
 */

import { useState } from "react";
import { ACTION_CAP, approvalConsoleTarget } from "@/lib/admin/roles";
import { useAdminSession, useApprovalVault, useCan } from "@/lib/admin/store";
import type { ApprovalPayload, DestructiveAction } from "@/lib/admin/types";
import { isoNow } from "@/lib/format";
import { toast } from "@/components/Toast";
import ConfirmDialog from "./ConfirmDialog";
import styles from "./admin.module.css";

/** 요청 생성 전 확인 다이얼로그 내용. target·blastRadius로 대상·파급을 사전 고지한다 */
export interface RequestConfirm {
  title: string;
  description?: string;
  target?: string;
  blastRadius?: string;
  tone?: "danger" | "warning";
  confirmLabel?: string;
}

interface Props {
  action: DestructiveAction;
  payload: ApprovalPayload;
  label: string;
  onRequested?: () => void;
  confirm?: RequestConfirm;
  /**
   * 파괴 강도. destructive는 디자인_방향 §7.6 등급 예외를 만족하는 호출측만 넘긴다.
   * 등급이 균일한 목록(대상만 다른 반복 액션)은 예외 대상이 아니다.
   */
  tone?: "neutral" | "destructive";
}

export default function RequestButton({ action, payload, label, onRequested, confirm, tone = "neutral" }: Props) {
  const allowed = useCan(ACTION_CAP[action]);
  const session = useAdminSession();
  const propose = useApprovalVault((s) => s.propose);
  const [open, setOpen] = useState(false);

  const request = () => {
    if (!session) {
      toast("관리자 세션 없음", "error");
      return;
    }
    const { key, label: targetLabel } = approvalConsoleTarget(payload);
    propose({
      key,
      requesterId: session.operatorId,
      requesterName: session.displayName,
      action,
      payload,
      targetLabel,
      proposedAt: isoNow(),
    });
    toast("승인 요청 등록 완료. 다른 운영자의 발급 필요", "info");
    setOpen(false);
    onRequested?.();
  };

  return (
    <>
      <button
        type="button"
        // 무채가 기본이다. 파괴 의미는 동사 라벨과 확인 단계가 전달하고, 형태 차이는 등급 예외에서만 쓴다
        className={tone === "destructive" ? styles.dangerBtn : styles.neutralBtn}
        onClick={() => (confirm ? setOpen(true) : request())}
        disabled={!allowed}
        title={allowed ? undefined : "권한 부족"}
      >
        {label}
      </button>
      {open && confirm && (
        <ConfirmDialog
          title={confirm.title}
          description={confirm.description}
          target={confirm.target}
          blastRadius={confirm.blastRadius}
          tone={confirm.tone ?? "warning"}
          confirmLabel={confirm.confirmLabel ?? label}
          busy={false}
          onConfirm={request}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}
