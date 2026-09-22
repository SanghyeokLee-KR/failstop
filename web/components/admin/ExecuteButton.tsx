"use client";

/**
 * 승인 없이 즉시 실행하는 파괴 액션 버튼(§3-6 v2.34 카오스 승인 완화).
 *
 * RequestButton과 무엇이 다른가. RequestButton은 이 브라우저 보관소에 요청 의도를 적재하고,
 * 승인 티켓을 발급한 뒤에야 실행된다. 이 버튼은 그 두 단계를 건너뛰고
 * 확인 다이얼로그 하나만 거쳐 바로 실행한다.
 *
 * 왜 별도 컴포넌트인가. RequestButton을 고치면 승인이 그대로 남아 있는 마켓 정지·수수료 변경·
 * 운영자 관리·회원 폐쇄까지 같이 열린다. 완화 대상은 카오스 4종뿐이라 경로를 갈라 놓는다.
 *
 * 없앤 것은 "다른 사람의 승인" 한 겹이다. 역할 게이트(CHAOS_OPS)와 확인 다이얼로그, 전량 감사
 * 적재는 그대로다. 파괴성 자체가 줄지 않았으므로 확인 다이얼로그를 선택 사항으로 두지 않는다.
 */

import { useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { ACTION_CAP } from "@/lib/admin/roles";
import { useAdminSession, useCan } from "@/lib/admin/store";
import type { ApprovalPayload } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ConfirmDialog from "./ConfirmDialog";
import type { RequestConfirm } from "./RequestButton";
import styles from "./admin.module.css";

interface Props {
  payload: ApprovalPayload;
  label: string;
  /** 실행 후 재조회. 진행 중 목록이 바뀌어야 버튼이 "주입 중"으로 넘어간다 */
  onDone?: () => void;
  /** 파괴 강도. destructive는 체결이 실제로 끊기는 등급에만 준다 */
  tone?: "neutral" | "destructive";
  disabled?: boolean;
  confirm: RequestConfirm;
}

export default function ExecuteButton({ payload, label, onDone, tone = "neutral", disabled = false, confirm }: Props) {
  const allowed = useCan(ACTION_CAP[payload.action]);
  const session = useAdminSession();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    if (!session) {
      toast("관리자 세션 없음", "error");
      return;
    }
    setBusy(true);
    try {
      await getAdminApi().executeDirect(payload);
      // 다이얼로그는 성공 뒤에 닫는다. 실패하면 열어 둔 채로 두어 재시도 자리를 지킨다
      setOpen(false);
      toast(`${confirm.target ?? label} 주입`, "info");
      onDone?.();
    } catch (e) {
      toast(e instanceof Error ? e.message : "주입 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className={tone === "destructive" ? styles.dangerBtn : styles.controlBtn}
        disabled={!allowed || disabled || busy}
        aria-busy={busy}
        onClick={() => setOpen(true)}
      >
        {busy ? "주입 중" : label}
      </button>
      {open && (
        <ConfirmDialog
          title={confirm.title}
          description={confirm.description}
          target={confirm.target}
          blastRadius={confirm.blastRadius}
          tone={confirm.tone ?? "danger"}
          confirmLabel={confirm.confirmLabel ?? label}
          busy={busy}
          onConfirm={() => void run()}
          onClose={() => {
            if (!busy) setOpen(false);
          }}
        />
      )}
    </>
  );
}
