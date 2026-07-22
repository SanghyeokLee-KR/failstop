/**
 * TradeLayout: /exchange/[market] 그리드 컨테이너 (§2.2, §5.2).
 * grid-template과 §2.3 높이 브레이크포인트만 담당하고 패널은 슬롯으로 받는다.
 */

import type { ReactNode } from "react";
import styles from "./TradeLayout.module.css";

interface Props {
  summary: ReactNode;
  chart: ReactNode;
  book: ReactNode;
  trades: ReactNode;
  order: ReactNode;
  marketList: ReactNode;
}

export default function TradeLayout({ summary, chart, book, trades, order, marketList }: Props) {
  return (
    <div className={styles.tradeGrid}>
      <div className={styles.summary}>{summary}</div>
      <div className={styles.chart}>{chart}</div>
      <div className={styles.book}>{book}</div>
      <div className={styles.trades}>{trades}</div>
      <div className={styles.side}>
        <div className={styles.order}>{order}</div>
        <div className={styles.marketList}>{marketList}</div>
      </div>
    </div>
  );
}
