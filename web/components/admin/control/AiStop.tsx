"use client";

/**
 * 운영자 바의 'AI 즉시 정지'와 'AI 재개'(디자인 시스템 §12-9, ADR 0035). 운영 관제 셸의 모든 화면에서 같은
 * 자리에 늘 보이고 AI 상담 패널이 가리지 않는다. 승인 없이 세션이 있는 운영자면 누구나 멈춘다.
 *
 * 수동 모드는 AI가 조치를 실행하지 않아 평상 톤(neutral 단추, ■만 장애색)이고, 자동 모드 동안에는 AI가 실제로
 * 실행하므로 danger 변형으로 올린다. 정지 중에는 그 자리에 ▲ 'AI 정지 중'과 'AI 재개'가 선다. 재개는 사유를
 * 받아 수동으로만 돌리고 총괄(SUPER)이 한다. http 원천에서는 서버 정책 엔진이 없어 보이되 비활성이다.
 */

import { useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { opsCenterStore, useOpsPickShallow } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import type { ControlCenter } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ConfirmDialog from "../ConfirmDialog";
import adminStyles from "../admin.module.css";
import styles from "./control.module.css";

type StopState = { mode: "MANUAL" | "AUTO" | "STOPPED" | null; connected: boolean };

function stopStateOf(c: ControlCenter | null): StopState {
  const ai = c?.ai.state === "READY" ? c.ai.data : null;
  return { mode: ai?.mode ?? null, connected: ai !== null };
}

function ResumeDialog({ onClose }: { onClose: () => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const ready = reason.trim().length >= 2;
  const submit = async () => {
    setBusy(true);
    try {
      await getAdminApi().resumeAi(reason);
      toast("AI 재개 · 수동 모드", "info");
      void opsCenterStore.getState().refresh(true);
      onClose();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "AI 재개 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmDialog
      title="AI 재개"
      description="수동 모드로만 재개합니다. AI가 다시 판단하고 조치안을 내지만 실행은 운영자 승인입니다. 자동 모드는 다시 승인 티켓(ops.ai.auto)을 거쳐 켭니다."
      target="AI 운영 · 수동으로 재개"
      blastRadius="새 판단과 조치안 · 실행 없음"
      tone="warning"
      confirmLabel="수동으로 재개"
      busy={busy}
      confirmDisabled={!ready}
      onConfirm={() => void submit()}
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <div className={styles.autoForm}>
        <label className={styles.autoFormLabel} htmlFor="resume-reason">
          사유 · 감사 로그와 사건 기록에 남음
        </label>
        <input
          id="resume-reason"
          className={styles.autoReason}
          value={reason}
          maxLength={80}
          placeholder="예: 원인 확인 끝, 판단 다시 받음"
          onChange={(e) => setReason(e.target.value)}
        />
      </div>
    </ConfirmDialog>
  );
}

export default function AiStop() {
  const { mode, connected } = useOpsPickShallow(stopStateOf);
  const canSuper = useCan("SUPER");
  const [busy, setBusy] = useState(false);
  const [resume, setResume] = useState(false);

  const stop = async () => {
    setBusy(true);
    try {
      await getAdminApi().stopAi();
      toast("AI 정지 · 새 판단과 조치안 멈춤", "info");
      void opsCenterStore.getState().refresh(true);
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "AI 정지 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  if (mode === "STOPPED") {
    return (
      <span className={styles.aiStop} data-mode="STOPPED">
        <span className={styles.aiStopWord} role="status">
          <span aria-hidden="true">▲ </span>AI 정지 중
        </span>
        <button
          type="button"
          className={adminStyles.neutralBtn}
          disabled={!canSuper || busy}
          title={canSuper ? "사유를 적어 수동으로 재개합니다" : "총괄 권한 필요"}
          onClick={() => setResume(true)}
        >
          AI 재개
        </button>
        {resume && <ResumeDialog onClose={() => setResume(false)} />}
      </span>
    );
  }
  const auto = mode === "AUTO";
  return (
    <span className={styles.aiStop} data-mode={mode ?? "NONE"}>
      <button
        type="button"
        className={auto ? adminStyles.dangerBtn : `${adminStyles.neutralBtn} ${styles.stopCalm}`}
        disabled={!connected || busy}
        title={connected ? "승인 없이 곧바로 멈춥니다. 자동 모드를 끄고 새 판단과 조치안을 멈춥니다" : "서버 정책 엔진 연결 전"}
        aria-label={connected ? "AI 즉시 정지" : "AI 즉시 정지 · 서버 정책 엔진 연결 전"}
        onClick={() => void stop()}
      >
        <span className={styles.stopShape} aria-hidden="true">
          ■
        </span>
        AI 즉시 정지
      </button>
    </span>
  );
}
