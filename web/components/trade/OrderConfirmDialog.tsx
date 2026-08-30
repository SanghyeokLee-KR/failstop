"use client";

/**
 * 임계 이상 주문에서만 뜨고, 임계 미만은 OrderPanel의 인라인 2단계 흐름을 쓴다(§2.5).
 * 포커스 트랩·Escape·배경 inert·복귀는 ReportDialog 규약을 재사용하고, 방향은 라벨과 톤을 병기한다.
 */

import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { formatKRW, formatPrice, formatQty, type PriceSpec } from "@/lib/format";
import type { Side } from "@/lib/types";
import styles from "./OrderConfirmDialog.module.css";

interface Props {
  side: Side;
  market: string;
  base: string;
  ordType: "limit" | "market";
  /** 지정가 가격 문자열. 시장가면 미표시 */
  priceStr?: string;
  /** 가격 표시 자릿수 스펙(tick_size 또는 price_scale). 저가 마켓 가격이 정수 반올림으로 0원이 되지 않게 한다 */
  priceSpec?: PriceSpec;
  qtyStr: string;
  qtyScale: number;
  totalStr: string;
  /** 수수료 금액. 수취 자산 단위다(매수 base, 매도 quote, §4-B-5 v2.7) */
  feeStr: string;
  /** 수수료 단위 라벨. 금액만 보이면 매수 수수료를 원으로 오독한다 */
  feeUnit: string;
  /** 수수료율 백분율 표기(0.0005 -> 0.05) */
  feePctStr: string;
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
  priceSpec,
  qtyStr,
  qtyScale,
  totalStr,
  feeStr,
  feeUnit,
  feePctStr,
  ratioPct,
  busy,
  onConfirm,
  onClose,
}: Props) {
  const titleId = useId();
  const descId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  // onClose는 ref로 읽는다. 부모(OrderPanel)가 티커 구독으로 매 렌더 새 인라인 핸들러를 넘기므로
  // deps에 두면 갱신마다 포커스 이펙트가 재실행돼 사용자가 확정에 둔 포커스를 취소로 강탈한다
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

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
    // 마운트 1회. 초기 포커스·트랩 등록은 열림 시점에만 수행한다(onClose는 ref 경유)
  }, []);

  // 배경 격리는 포커스 효과 뒤에 선언한다. 정리 역순이라 inert 해제가 포커스 복원보다 먼저 돈다
  useEffect(() => {
    const overlay = overlayRef.current;
    if (!overlay) return;
    // 토스트 리전은 격리 대상에서 제외한다. inert로 접근성 트리에서 빠지면 열림 중 체결·에러
    // 토스트가 낭독되지 않고 닫기 버튼도 조작 불가가 된다(z-toast로 시각은 다이얼로그 위)
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
          가용 잔고의 약 {ratioPct}% 규모. 내용 확인 후 진행 필요.
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
                {formatPrice(priceStr, priceSpec)} <span className="unit-suffix">원</span>
              </dd>
            </div>
          )}
          <div className={styles.row}>
            <dt>수량</dt>
            <dd className="num">
              {formatQty(qtyStr, qtyScale)} <span className="unit-suffix">{base}</span>
            </dd>
          </div>
          <div className={styles.row}>
            <dt>예상 수수료 ({feePctStr}%)</dt>
            <dd className="num">
              {feeUnit === "원" ? formatKRW(feeStr) : formatQty(feeStr, qtyScale)}{" "}
              <span className="unit-suffix">{feeUnit}</span>
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
