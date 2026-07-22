"use client";

/**
 * 대형 주문 확인 다이얼로그 (§2.5 오발주 방지).
 * 임계 이상 주문에서만 뜬다. 임계 미만은 OrderPanel의 인라인 2단계 흐름을 그대로 쓴다.
 * 포커스 관리는 ReportDialog 규약 재사용: 포커스 트랩·Escape·배경 inert·복귀.
 * 방향은 색 단독 금지. 라벨 텍스트(매수/매도)와 버튼 톤을 병기한다.
 */

import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { formatKRW, formatQty } from "@/lib/format";
import type { Side } from "@/lib/types";
import styles from "./OrderConfirmDialog.module.css";

interface Props {
  side: Side;
  market: string;
  base: string;
  ordType: "limit" | "market";
  /** 지정가 가격 문자열. 시장가면 미표시 */
  priceStr?: string;
  qtyStr: string;
  qtyScale: number;
  totalStr: string;
  /** 가용 잔고 대비 주문 비율(퍼센트, 정수 반올림). 큰 비중임을 사용자에게 알린다 */
  ratioPct: number;
  busy: boolean;
  onConfirm(): void;
  onClose(): void;
}

export default function OrderConfirmDialog({
  side,
  market,
  base,
  ordType,
  priceStr,
  qtyStr,
  qtyScale,
  totalStr,
  ratioPct,
  busy,
  onConfirm,
  onClose,
}: Props) {
  const titleId = useId();
  const descId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const prevFocused = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      // 포커스 트랩: Tab이 다이얼로그 밖으로 새지 않게 처음·끝을 순환한다
      if (e.key === "Tab" && dialog) {
        const focusables = dialog.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [tabindex]:not([tabindex="-1"])',
        );
        // busy 구간엔 취소·확정 모두 disabled라 focusable이 0이 된다. 이때 루트에 포커스를 고정해 Tab 유출을 막는다
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
    // 확정 버튼이 아니라 취소에 초기 포커스를 둔다. 오발주 방지가 목적이라 확정은 의도적 이동을 요구한다.
    // 취소가 disabled(busy)면 루트로 폴백해 포커스가 다이얼로그 밖에 머물지 않게 한다
    const cancel = dialog?.querySelector<HTMLButtonElement>(`.${styles.cancel}`);
    if (cancel && !cancel.disabled) cancel.focus();
    else dialog?.focus();
    return () => {
      document.removeEventListener("keydown", onKey);
      prevFocused?.focus?.();
    };
  }, [onClose]);

  // 배경 격리는 포커스 효과 뒤에 선언한다. 정리 역순이라 inert 해제가 포커스 복원보다 먼저 돈다
  useEffect(() => {
    const overlay = overlayRef.current;
    if (!overlay) return;
    const bg = Array.from(document.body.children).filter((el) => el !== overlay) as HTMLElement[];
    const prev = bg.map((n) => ({ n, inert: n.hasAttribute("inert") }));
    bg.forEach((n) => n.setAttribute("inert", ""));
    return () => {
      prev.forEach(({ n, inert }) => {
        if (!inert) n.removeAttribute("inert");
      });
    };
  }, []);

  const sideLabel = side === "bid" ? "매수" : "매도";
  const btnTone = side === "bid" ? "btn--buy" : "btn--sell";

  return createPortal(
    <div ref={overlayRef} className={styles.overlay} onMouseDown={onClose}>
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        tabIndex={-1}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descId}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 id={titleId} className={styles.title}>
          {sideLabel} 주문 확인
        </h2>
        <p id={descId} className={styles.desc}>
          가용 잔고의 약 {ratioPct}% 규모예요. 내용을 확인하고 진행해 주세요.
        </p>

        <dl className={styles.summary}>
          <div className={styles.row}>
            <dt>마켓</dt>
            <dd className="num">{market}</dd>
          </div>
          <div className={styles.row}>
            <dt>유형</dt>
            <dd>{ordType === "limit" ? "지정가" : "시장가"}</dd>
          </div>
          {ordType === "limit" && priceStr != null && (
            <div className={styles.row}>
              <dt>가격</dt>
              <dd className="num">
                {formatKRW(priceStr)} <span className="unit-suffix">원</span>
              </dd>
            </div>
          )}
          <div className={styles.row}>
            <dt>수량</dt>
            <dd className="num">
              {formatQty(qtyStr, qtyScale)} <span className="unit-suffix">{base}</span>
            </dd>
          </div>
          <div className={`${styles.row} ${styles.rowTotal}`}>
            <dt>주문총액</dt>
            <dd className="num">
              {formatKRW(totalStr)} <span className="unit-suffix">원</span>
            </dd>
          </div>
        </dl>

        <div className={styles.actions}>
          <button
            type="button"
            className={`btn btn--secondary ${styles.cancel}`}
            onClick={onClose}
            disabled={busy}
          >
            취소
          </button>
          <button
            type="button"
            className={`btn ${btnTone}`}
            onClick={onConfirm}
            disabled={busy}
            aria-busy={busy}
          >
            {busy ? "접수 중…" : `${sideLabel} 확정`}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
