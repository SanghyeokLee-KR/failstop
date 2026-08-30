"use client";

/**
 * 오발주 방지가 목적이라 초기 포커스는 취소에 둔다(§7.6).
 * 색 단독 금지라 확정에 danger 보더와 라벨을 병기한다.
 */

import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import styles from "./ConfirmDialog.module.css";

interface Props {
  title: string;
  /** 액션 설명. 되돌릴 수 없음을 명시할 때 씀 */
  description?: string;
  /** 액션 대상. 무엇에 가해지는지 명확히 표시 */
  target?: string;
  /** 영향 범위. 어디까지 파급되는지 사전 고지 */
  blastRadius?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** 확정 위계. danger=파괴, warning=주의. 기본 danger */
  tone?: "danger" | "warning";
  busy?: boolean;
  onConfirm(): void;
  onClose(): void;
  /** 확정 전에 받아야 할 입력(거절 사유 등). 요약과 액션 사이에 놓인다 */
  children?: ReactNode;
}

export default function ConfirmDialog({
  title,
  description,
  target,
  blastRadius,
  confirmLabel = "실행",
  cancelLabel = "취소",
  tone = "danger",
  busy = false,
  onConfirm,
  onClose,
  children,
}: Props) {
  const titleId = useId();
  const descId = useId();
  const summaryId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  // onClose 최신 참조를 ref로 잡는다. 호출부가 인라인 화살표를 넘겨 폴링 리렌더마다 참조가 바뀌어도
  // 포커스 셋업 이펙트가 재실행돼 초기 포커스(취소)로 포커스를 되돌리는 강탈을 막는다
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  // 실제 렌더된 조각만 참조한다. description 없이 target/blastRadius만 있을 때 빈 id 참조를 막는다
  const describedBy =
    [description ? descId : null, target || blastRadius ? summaryId : null].filter(Boolean).join(" ") || undefined;

  useEffect(() => {
    const prevFocused = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onCloseRef.current();
        return;
      }
      // 포커스 트랩: Tab이 다이얼로그 밖으로 새지 않게 처음·끝을 순환한다
      if (e.key === "Tab" && dialog) {
        /* 입력을 빼면 children으로 받은 사유 입력이 Tab 순환에서 사라진다. 트랩 안에
           있는데 키보드로 못 닿는 컨트롤이 생기므로 폼 요소를 함께 잡는다 */
        const focusables = dialog.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        );
        // busy 구간엔 취소·확정 모두 disabled라 focusable이 0이 된다. 루트에 고정해 Tab 유출을 막는다
        if (focusables.length === 0) {
          e.preventDefault();
          dialog.focus();
          return;
        }
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
    // 초기 포커스는 취소. 취소가 disabled(busy)면 루트로 폴백해 포커스가 다이얼로그 밖에 머물지 않게 한다
    const cancel = dialog?.querySelector<HTMLButtonElement>(`.${styles.cancel}`);
    if (cancel && !cancel.disabled) cancel.focus();
    else dialog?.focus();
    return () => {
      document.removeEventListener("keydown", onKey);
      // 닫힐 때 트리거로 포커스 복귀(키보드 흐름 유지)
      prevFocused?.focus?.();
    };
    // 마운트 1회만. 초기 포커스·트리거 복귀는 열림·닫힘 경계에서만 일어나야 한다(폴링 리렌더 무관)
  }, []);

  // 배경 격리는 포커스 효과 뒤에 선언한다. 정리 역순이라 inert 해제가 포커스 복원보다 먼저 돈다
  useEffect(() => {
    const overlay = overlayRef.current;
    if (!overlay) return;
    // 토스트 리전은 격리 대상에서 제외한다. inert에 걸리면 열림 중 토스트 낭독·닫기가 죽는다
    const bg = Array.from(document.body.children).filter(
      (el) => el !== overlay && !el.hasAttribute("data-toast-region"),
    ) as HTMLElement[];
    const prev = bg.map((n) => ({ n, inert: n.hasAttribute("inert") }));
    bg.forEach((n) => n.setAttribute("inert", ""));
    return () => {
      prev.forEach(({ n, inert }) => {
        if (!inert) n.removeAttribute("inert");
      });
    };
  }, []);

  return createPortal(
    <div ref={overlayRef} className={styles.overlay} onMouseDown={onClose}>
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="alertdialog"
        tabIndex={-1}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={describedBy}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        {description && (
          <p id={descId} className={styles.desc}>
            {description}
          </p>
        )}

        {(target || blastRadius) && (
          <dl id={summaryId} className={styles.summary}>
            {target && (
              <div className={styles.row}>
                <dt>대상</dt>
                <dd className="num">{target}</dd>
              </div>
            )}
            {blastRadius && (
              <div className={styles.row}>
                <dt>영향 범위</dt>
                <dd>{blastRadius}</dd>
              </div>
            )}
          </dl>
        )}

        {children}

        <div className={styles.actions}>
          <button
            type="button"
            className={`btn btn--secondary ${styles.cancel}`}
            onClick={onClose}
            disabled={busy}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            className={styles.confirm}
            data-tone={tone}
            onClick={onConfirm}
            disabled={busy}
            aria-busy={busy}
          >
            {/* §7.6 부호·라벨 병기를 중앙에서 강제한다. 호출부가 부호를 잊을 수 없다 */}
            {busy ? (
              "처리 중…"
            ) : (
              <>
                {tone === "danger" && <span aria-hidden="true">■ </span>}
                {confirmLabel}
              </>
            )}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
