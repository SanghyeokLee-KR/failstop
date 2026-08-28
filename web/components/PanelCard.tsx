/**
 * PanelCard: 거래 화면 패널 공통 셸 (§2.5).
 * surface 카드 + 선택적 헤더(타이틀·우측 액션) + 내부 스크롤 슬롯.
 */

import type { ReactNode } from "react";
import styles from "./PanelCard.module.css";

interface Props {
  title?: ReactNode;
  /**
   * 명판 없이 접근성 이름만 필요한 패널의 제목(차트·주문).
   * 가시 헤더를 얹으면 헤더 높이만큼 무스크롤 예산(§2.3)이 깎여 차트나 마켓 목록이 밀린다.
   * sr-only h2로 두면 시각 레이아웃 시프트 0으로 헤딩 목록과 region 이름을 모두 얻는다.
   */
  srTitle?: string;
  /** 헤더 우측 액션 영역 */
  actions?: ReactNode;
  /** 테이블성 패널(호가·체결·마켓 목록)용 12px 패딩 (§2.1) */
  dense?: boolean;
  /** 본문 내부 스크롤 활성 */
  scroll?: boolean;
  /** 지연 상태. 본문만 opacity 감쇠하고 헤더 배지는 또렷이 유지한다(A-5) */
  stale?: boolean;
  className?: string;
  children: ReactNode;
}

export default function PanelCard({ title, srTitle, actions, dense, scroll, stale, className, children }: Props) {
  return (
    <section
      className={`${styles.root} ${dense ? styles.dense : ""} ${className ?? ""}`}
      aria-label={srTitle}
    >
      {srTitle != null && <h2 className="sr-only">{srTitle}</h2>}
      {(title != null || actions != null) && (
        <header className={styles.header}>
          {title != null && <h2 className={styles.title}>{title}</h2>}
          {actions != null && <div className={styles.actions}>{actions}</div>}
        </header>
      )}
      {/* panel-body는 소비처가 본문 슬롯만 지목할 수 있게 두는 전역 훅이다. 모듈 클래스는
          해시가 붙어 밖에서 가리킬 수 없고, .card > *로 잡으면 sr-only h2와 header까지
          걸려 나중에 title을 넘기는 순간 헤더 배치가 바뀐다 */}
      <div
        className={`panel-body ${styles.body} ${scroll ? styles.scroll : ""}`}
        data-stale={stale ? "" : undefined}
      >
        {children}
      </div>
    </section>
  );
}
