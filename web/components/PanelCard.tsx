/**
 * PanelCard: 거래 화면 패널 공통 셸 (§2.5).
 * surface 카드 + 선택적 헤더(타이틀·우측 액션) + 내부 스크롤 슬롯.
 */

import type { ReactNode } from "react";
import styles from "./PanelCard.module.css";

interface Props {
  title?: ReactNode;
  /** 헤더 우측 액션 영역 */
  actions?: ReactNode;
  /** 테이블성 패널(호가·체결·마켓 목록)용 12px 패딩 (§2.1) */
  dense?: boolean;
  /** 본문 내부 스크롤 활성 */
  scroll?: boolean;
  className?: string;
  children: ReactNode;
}

export default function PanelCard({ title, actions, dense, scroll, className, children }: Props) {
  return (
    <section className={`${styles.root} ${dense ? styles.dense : ""} ${className ?? ""}`}>
      {(title != null || actions != null) && (
        <header className={styles.header}>
          {title != null && <h2 className={styles.title}>{title}</h2>}
          {actions != null && <div className={styles.actions}>{actions}</div>}
        </header>
      )}
      <div className={`${styles.body} ${scroll ? styles.scroll : ""}`}>{children}</div>
    </section>
  );
}
