"use client";

/**
 * 신고 다이얼로그(게시판_설계 §4 신고, 사용자 측). 사유 선택 + 선택 설명.
 * 관리자 모더레이션 UI는 범위 밖. 여기선 신고 접수만 한다.
 * 제출·롤백은 상위 책임. Escape·배경 클릭으로 닫힌다.
 */

import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
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
  const descId = useId();
  const [reason, setReason] = useState<ReportReason>("SPAM");
  const [detail, setDetail] = useState("");
  const [busy, setBusy] = useState(false);
  // 제출 in-flight 중 Escape·오버레이 클릭 닫힘을 막는다. 버튼 disabled와 취소 수단이 모순되지
  // 않게 하고, 닫힌 뒤 접수 완료·실패가 사용자 인지와 어긋나는 것을 방지한다. 이펙트 deps
  // 변경 없이 최신 값을 읽도록 ref로 판정한다
  const busyRef = useRef(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  // 호출부가 인라인 onClose를 넘긴다. deps에 두면 부모 리렌더마다 격리·포커스 이펙트가 다시 돌아
  // 입력 중인 포커스를 첫 사유로 옮기므로 최신 참조만 ref로 읽는다
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  // 배경 격리와 포커스 복귀를 한 이펙트에 둔다. React는 이펙트 정리를 선언 순서대로 돌리므로 둘을 나누면
  // inert가 남은 채 복귀가 먼저 실행되고, inert 안의 트리거는 focus()를 무시해 포커스가 body로 떨어진다
  useEffect(() => {
    const prevFocused = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    const overlay = overlayRef.current;
    // 토스트 리전은 격리 대상에서 제외한다. inert에 걸리면 열림 중 토스트 낭독·닫기가 죽는다
    const bg = Array.from(document.body.children).filter(
      (el) => el !== overlay && !el.hasAttribute("data-toast-region"),
    ) as HTMLElement[];
    // inert가 접근성 트리에서 이미 제거하므로 aria-hidden은 중복이라 두지 않는다
    const prevInert = bg.map((n) => ({ n, inert: n.hasAttribute("inert") }));
    bg.forEach((n) => n.setAttribute("inert", ""));
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (!busyRef.current) onCloseRef.current();
        return;
      }
      // 모달 포커스 트랩: Tab이 다이얼로그 밖으로 새지 않게 처음·끝을 순환한다
      if (e.key === "Tab" && dialog) {
        const focusables = dialog.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        );
        if (focusables.length === 0) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    dialog?.querySelector<HTMLElement>("input, button")?.focus();
    return () => {
      document.removeEventListener("keydown", onKey);
      prevInert.forEach(({ n, inert }) => {
        if (!inert) n.removeAttribute("inert");
      });
      // inert를 푼 뒤에 호출 지점으로 포커스를 복귀시킨다(키보드 흐름 유지)
      prevFocused?.focus?.();
    };
    // 마운트 1회. 초기 포커스·복귀는 열림·닫힘 경계에서만 일어나야 한다(onClose는 ref 경유)
  }, []);

  const submit = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      await onSubmit(reason, detail.trim());
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const label = targetType === "POST" ? "글" : "댓글";

  return createPortal(
    <div
      ref={overlayRef}
      className={styles.overlay}
      onMouseDown={() => {
        if (!busyRef.current) onClose();
      }}
    >
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descId}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 id={titleId} className={styles.title}>
          {label} 신고
        </h2>
        <p id={descId} className={styles.desc}>신고 사유 선택. 접수 후 운영 검토 진행.</p>

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
          placeholder="추가로 알릴 내용 입력"
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
    </div>,
    document.body,
  );
}
