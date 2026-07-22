"use client";

/**
 * 파괴 액션 요청 버튼(§2-2, §3-2). 명시 클릭으로만 승인 요청을 생성한다.
 * 요구 권한 미충족 시 비활성(관측 데이터가 아무리 임계를 넘어도 자동 실행 없음, §6 안전).
 * 요청은 즉시 실행이 아니라 다른 운영자의 2인 승인 대기로 진입한다.
 * confirm이 주어지면 요청 생성 전에 확인 다이얼로그로 오요청을 막는다(주입·해제·마켓 중지).
 */

import { useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { ACTION_CAP } from "@/lib/admin/roles";
import { useCan } from "@/lib/admin/store";
import type { ApprovalPayload, DestructiveAction } from "@/lib/admin/types";
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
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);

  const request = async () => {
    setBusy(true);
    try {
      await getAdminApi().requestApproval(action, payload);
      toast("승인 요청을 등록했어요. 다른 운영자의 승인이 필요합니다", "info");
      setOpen(false);
      onRequested?.();
    } catch (e) {
      toast(e instanceof Error ? e.message : "요청에 실패했어요", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className={styles.dangerBtn}
        onClick={() => (confirm ? setOpen(true) : void request())}
        disabled={!allowed || busy}
        aria-busy={busy}
        title={allowed ? undefined : "권한이 부족합니다"}
      >
        {busy ? "요청 중" : label}
      </button>
      {open && confirm && (
        <ConfirmDialog
          title={confirm.title}
          description={confirm.description}
          target={confirm.target}
          blastRadius={confirm.blastRadius}
          tone={confirm.tone ?? "warning"}
          confirmLabel={confirm.confirmLabel ?? label}
          busy={busy}
          onConfirm={() => void request()}
          onClose={() => {
            if (!busy) setOpen(false);
          }}
        />
      )}
    </>
  );
}
