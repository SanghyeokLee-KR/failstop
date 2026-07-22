"use client";

/**
 * 신고 다이얼로그(게시판_설계 §4 신고, 사용자 측). 사유 선택 + 선택 설명.
 * 관리자 모더레이션 UI는 범위 밖. 여기선 신고 접수만 한다.
 * 제출·롤백은 상위 책임. Escape·배경 클릭으로 닫힌다.
 */

import { useEffect, useId, useRef, useState } from "react";
import type { ReportReason, ReportTargetType } from "@/lib/community/types";
import { REPORT_DETAIL_MAX } from "@/lib/community/types";
import styles from "./ReportDialog.module.css";

const REASONS: Array<{ value: ReportReason; label: string }> = [
  { value: "SPAM", label: "스팸·광고" },
  { value: "ABUSE", label: "욕설·비방" },
  { value: "ILLEGAL", label: "불법·유해 정보" },
  { value: "ETC", label: "기타" },
];

interface Props {
  targetType: ReportTargetType;
  onClose(): void;
  onSubmit(reason: ReportReason, detail: string): Promise<void>;
}

export default function ReportDialog({ targetType, onClose, onSubmit }: Props) {
  const titleId = useId();
  const [reason, setReason] = useState<ReportReason>("SPAM");
  const [detail, setDetail] = useState("");
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    dialogRef.current?.querySelector<HTMLElement>("input, button")?.focus();
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await onSubmit(reason, detail.trim());
    } finally {
      setBusy(false);
    }
  };

  const label = targetType === "POST" ? "글" : "댓글";

  return (
    <div className={styles.overlay} onMouseDown={onClose}>
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 id={titleId} className={styles.title}>
          {label} 신고
        </h2>
        <p className={styles.desc}>신고 사유를 선택해 주세요. 접수 후 운영 검토를 거쳐요.</p>

        <fieldset className={styles.reasons}>
          <legend className="sr-only">신고 사유</legend>
          {REASONS.map((r) => (
            <label key={r.value} className={styles.reason} data-on={reason === r.value || undefined}>
              <input
                type="radio"
                name="report-reason"
                value={r.value}
                checked={reason === r.value}
                onChange={() => setReason(r.value)}
              />
              <span>{r.label}</span>
            </label>
          ))}
        </fieldset>

        <label className={styles.detailLabel} htmlFor={`${titleId}-detail`}>
          상세 설명(선택)
        </label>
        <textarea
          id={`${titleId}-detail`}
          className={styles.detail}
          value={detail}
          maxLength={REPORT_DETAIL_MAX}
          rows={3}
          placeholder="추가로 알려줄 내용이 있으면 적어주세요"
          onChange={(e) => setDetail(e.target.value)}
        />

        <div className={styles.actions}>
          <button type="button" className="btn btn--secondary" onClick={onClose} disabled={busy}>
            취소
          </button>
          <button type="button" className="btn btn--primary" onClick={() => void submit()} disabled={busy} aria-busy={busy}>
            {busy ? "접수 중" : "신고 접수"}
          </button>
        </div>
      </div>
    </div>
  );
}
