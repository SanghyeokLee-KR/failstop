/**
 * TradeLayout: /exchange/[market] 그리드 컨테이너 (§2.2, §5.2).
 * grid-template과 §2.3 높이 브레이크포인트만 담당하고 패널은 슬롯으로 받는다.
 */

import type { ReactNode } from "react";
import styles from "./TradeLayout.module.css";

/** 주문 슬롯 앵커. 스킵 링크가 같은 값을 참조한다 */
export const ORDER_SLOT_ID = "trade-order";

interface Props {
  /** 그리드 위 전폭 배너 슬롯(연결 단절 등). 없으면 그리드가 가용 높이를 전부 차지한다 */
  banner?: ReactNode;
  summary: ReactNode;
  chart: ReactNode;
  book: ReactNode;
  trades: ReactNode;
  order: ReactNode;
  marketList: ReactNode;
}

export default function TradeLayout({ banner, summary, chart, book, trades, order, marketList }: Props) {
  return (
    <div className={styles.shell}>
      {banner}
      <div className={styles.tradeGrid}>
        <div className={styles.summary}>{summary}</div>
        <div className={styles.chart}>{chart}</div>
        <div className={styles.book}>{book}</div>
        <div className={styles.trades}>{trades}</div>
        <div className={styles.side}>
          {/* 주문 스킵 링크 대상. 읽기 순서상 주문 폼 앞에 호가 20행이 있어
              키보드로 닿으려면 48탭이 걸린다(§조작 효율) */}
          <div className={styles.order} id={ORDER_SLOT_ID} tabIndex={-1}>
            {order}
          </div>
          <div className={styles.marketList}>{marketList}</div>
        </div>
      </div>
    </div>
  );
}
