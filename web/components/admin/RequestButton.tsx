"use client";

/**
 * 파괴 액션 요청 버튼(§3-6 발급형). 명시 클릭으로만 로컬 요청 의도를 만든다.
 * 발급형 계약에는 서버측 "요청" 상태가 없어(승인자 발급이 원장 최초 상태) 요청은 이 브라우저
 * 보관소에 둔다. 다른 운영자가 발급하면 요청자가 토큰을 소각한다(요청->발급->실행).
 * 요구 권한 미충족 시 비활성(관측 데이터가 임계를 넘어도 자동 실행 없음, §6 안전).
 * confirm이 주어지면 요청 생성 전에 확인 다이얼로그로 오요청을 막는다.
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
}

export default function RequestButton({ action, payload, label, onRequested, confirm }: Props) {
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
        // 반복 트리거는 무채(디자인_방향 §7.6). danger는 확정 지점 전용이고 파괴 의미는 동사 라벨이 전달한다
        className={styles.neutralBtn}
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
