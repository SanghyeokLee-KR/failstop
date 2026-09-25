"use client";

/**
 * 관리 콘솔의 모달과 드로어(디자인 시스템 §12-13). 머리(제목·보조 줄·닫기), 스크롤하는 본문, 선택적 단추 줄의
 * 한 뼈대다. 포커스·격리·Esc 규칙은 useLayer가, 등장과 퇴장 움직임은 CSS가 맡는다.
 */

import { useId, type CSSProperties, type ReactNode } from "react";
import { createPortal } from "react-dom";
import adminStyles from "../admin.module.css";
import { AdminIcon } from "../icons";
import { useLayer } from "./useLayer";
import styles from "./layer.module.css";

interface LayerProps {
  title: ReactNode;
  /** 제목 아래 보조 줄(대상 id, 시각, 출처 표식) */
  sub?: ReactNode;
  /** 제목 앞 형태 기호나 표지 */
  lead?: ReactNode;
  /** 제목 옆 상태 칩 */
  badge?: ReactNode;
  /** 닫기 단추 앞 도구(이웃 노드 단추 등) */
  tools?: ReactNode;
  /** 머리 아래에 붙는 줄(탭 목록). 본문과 함께 스크롤하지 않는다 */
  bar?: ReactNode;
  /** 단추 줄. 함수면 닫기(퇴장 움직임을 거치는)를 받아 '취소'에 건다 */
  footer?: ReactNode | ((close: () => void) => ReactNode);
  children: ReactNode;
  onClose: () => void;
  /** 처음 포커스를 둘 요소의 선택자. 없으면 층 자체에 둔다 */
  initialFocus?: string;
  /** 덮개를 눌러 닫는가. 결정 직전의 확인 층은 false다 */
  closeOnBackdrop?: boolean;
  role?: "dialog" | "alertdialog";
  /** 본문 안에서 설명으로 읽을 요소의 id */
  describedBy?: string;
  /** 시험과 캡처가 층을 가려내는 이름 */
  name: string;
  /** 처리 중이면 Esc·덮개·닫기로 닫지 않는다. 요청이 떠난 뒤 층이 사라지면 결과가 무엇의 결과인지 읽히지 않는다 */
  busy?: boolean;
}

function LayerFrame({ kind, width, height, props }: { kind: "modal" | "drawer"; width: number; height?: number; props: LayerProps }) {
  const { title, sub, lead, badge, tools, bar, footer, children, onClose, initialFocus, closeOnBackdrop = true, role = "dialog", describedBy, name, busy = false } = props;
  const titleId = useId();
  const { overlayRef, panelRef, closing, requestClose } = useLayer({ onClose, initialFocus, closeOnEscape: !busy });
  const size: CSSProperties = kind === "modal"
    ? { width: `min(${width}px, calc(100vw - 48px))`, height: height ? `min(${height}px, calc(100vh - 48px))` : undefined }
    : { width: `min(${width}px, calc(100vw - 48px))` };
  return createPortal(
    <div
      ref={overlayRef}
      className={`${styles.overlay} ${adminStyles.typeScale}`}
      data-kind={kind}
      data-closing={closing ? "" : undefined}
      onMouseDown={(e) => {
        if (closeOnBackdrop && !busy && e.target === e.currentTarget) requestClose();
      }}
    >
      <div
        ref={panelRef}
        className={styles.panel}
        style={size}
        role={role}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={describedBy}
        tabIndex={-1}
        data-layer={name}
      >
        <header className={styles.head}>
          <div className={styles.headMain}>
            <div className={styles.titleRow}>
              {lead}
              <h2 id={titleId} className={styles.title}>
                {title}
              </h2>
              {badge}
            </div>
            {sub != null && <div className={styles.sub}>{sub}</div>}
          </div>
          <div className={styles.tools}>
            {tools}
            <button type="button" className={styles.close} aria-label="닫기" disabled={busy} onClick={requestClose}>
              <AdminIcon name="close" size={16} />
            </button>
          </div>
        </header>
        {bar != null && <div className={styles.bar}>{bar}</div>}
        <div className={styles.body}>{children}</div>
        {footer != null && <footer className={styles.foot}>{typeof footer === "function" ? footer(requestClose) : footer}</footer>}
      </div>
    </div>,
    document.body,
  );
}

/** 창 가운데에 서는 모달. 높이를 주면 그 높이에 고정하고 본문만 스크롤한다 */
export function Modal({ width, height, ...props }: LayerProps & { width: number; height?: number }) {
  return <LayerFrame kind="modal" width={width} height={height} props={props} />;
}

/** 창 오른쪽 끝에 붙어 위아래 끝까지 서는 드로어 */
export function Drawer({ width, ...props }: LayerProps & { width: number }) {
  return <LayerFrame kind="drawer" width={width} props={props} />;
}
