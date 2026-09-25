"use client";

/**
 * 오발주 방지가 목적이라 초기 포커스는 취소에 둔다(§7.6, 디자인 시스템 §12-5). 색 단독 금지라 파괴 확정은
 * danger 면과 ■ 라벨을 병기한다. 포커스·격리·Esc 규칙은 다른 층과 같은 useLayer가 맡는다(§12-13).
 *
 * 머리(제목·설명)와 단추 줄은 제자리이고 가운데 본문만 스크롤한다. 높이는 창 높이에서 48px을 뺀 값을 넘지 않는다.
 * 본문까지 함께 늘어나면 1280x800 창에서 자동 모드 켜기의 확정 단추가 창 밖으로 밀린다.
 */

import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { clearLayerError } from "@/components/Toast";
import adminStyles from "./admin.module.css";
import LayerAlert from "./layer/LayerAlert";
import { useLayer } from "./layer/useLayer";
import styles from "./ConfirmDialog.module.css";
import StatusShape from "./StatusShape";

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
  /**
   * 확정의 위계. 확정 단추는 늘 채운 단추 하나다. approve는 긍정·비파괴 확정(승인하고 켜기, 재개, 진행), danger는 되돌릴
   * 수 없는 중단·삭제·거절이다. 기본 danger
   */
  tone?: "danger" | "approve";
  busy?: boolean;
  onConfirm(): void;
  onClose(): void;
  /** 확정 전에 받아야 할 입력(거절 사유 등). 요약과 액션 사이에 놓인다 */
  children?: ReactNode;
  /** 입력과 규칙 목록을 함께 싣는 대화상자(AI 자동 모드 켜기)는 폭을 넓힌다 */
  wide?: boolean;
  /** 입력이 모자라 확정할 수 없을 때. busy와 달리 취소는 그대로 된다 */
  confirmDisabled?: boolean;
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
  wide = false,
  confirmDisabled = false,
}: Props) {
  const titleId = useId();
  const descId = useId();
  const summaryId = useId();
  // 처리 중에는 닫지 않는다. 요청이 떠난 뒤 층이 사라지면 결과 토스트가 무엇의 결과인지 읽히지 않는다
  const { overlayRef, panelRef, closing, requestClose, depth } = useLayer({ onClose, initialFocus: `.${styles.cancel}`, closeOnEscape: !busy });
  const bodyRef = useRef<HTMLDivElement>(null);
  const [scrolls, setScrolls] = useState(false);
  // 본문이 넘칠 때만 머리 아래와 단추 줄 위에 헤어라인을 둔다. 스크롤할 글이 더 있다는 표시다
  useEffect(() => {
    const el = bodyRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const check = () => setScrolls(el.scrollHeight > el.clientHeight + 1);
    const ro = new ResizeObserver(check);
    ro.observe(el);
    for (const child of Array.from(el.children)) ro.observe(child);
    check();
    return () => ro.disconnect();
  }, []);
  // 실제 렌더된 조각만 참조한다. description 없이 target/blastRadius만 있을 때 빈 id 참조를 막는다
  const describedBy =
    [description ? descId : null, target || blastRadius ? summaryId : null].filter(Boolean).join(" ") || undefined;
  const confirmClass = tone === "approve" ? adminStyles.approveBtn : adminStyles.dangerFillBtn;
  const hasBody = Boolean(target || blastRadius || children);

  return createPortal(
    <div
      ref={overlayRef}
      className={`${styles.overlay} ${adminStyles.typeScale}`}
      data-closing={closing ? "" : undefined}
      onMouseDown={(e) => {
        if (!busy && e.target === e.currentTarget) requestClose();
      }}
    >
      <div
        ref={panelRef}
        className={styles.dialog}
        data-layer="confirm"
        data-wide={wide ? "" : undefined}
        data-scrolls={scrolls ? "" : undefined}
        role="alertdialog"
        tabIndex={-1}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={describedBy}
      >
        <div className={styles.head}>
          <h2 id={titleId} className={styles.title}>
            {title}
          </h2>
          {description && (
            <p id={descId} className={styles.desc}>
              {description}
            </p>
          )}
        </div>

        {hasBody && (
          <div ref={bodyRef} className={styles.body}>
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
          </div>
        )}

        <LayerAlert depth={depth} />

        <div className={styles.actions}>
          <button type="button" className={`${adminStyles.neutralBtn} ${styles.cancel}`} onClick={requestClose} disabled={busy}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={confirmClass}
            onClick={() => {
              clearLayerError();
              onConfirm();
            }}
            disabled={busy || confirmDisabled}
            aria-busy={busy}
          >
            {/* §7.6 부호·라벨 병기를 중앙에서 강제한다. 호출부가 부호를 잊을 수 없다 */}
            {busy ? (
              "처리 중…"
            ) : (
              <>
                {tone === "danger" && <span aria-hidden="true"><StatusShape kind="square" /> </span>}
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
