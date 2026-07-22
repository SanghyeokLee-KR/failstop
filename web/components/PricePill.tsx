/**
 * A-6. PriceChange. 등락률 pill/inline. 방향은 색 단독 금지 → ▲▼ + 부호 병기 (§0-2).
 * pill: -strong(700) on -bg 틴트 / inline: -text(600) on surface (§6 대비 계약).
 */

import type { ChangeState } from "@/lib/types";
import { ariaChangeLabel, dirOf, formatDirectional, formatDirectionalAmount } from "@/lib/format";
import styles from "./PricePill.module.css";

interface Props {
  change: ChangeState;
  /** 절대값 등락률 문자열/숫자 ("0.0234" → 2.34%) */
  changeRate: string | number;
  /** withAmount: 등락 금액 병기 (코인 상세 헤더) */
  amount?: string | number;
  variant?: "pill" | "inline";
}

export default function PricePill({ change, changeRate, amount, variant = "pill" }: Props) {
  const dir = dirOf(change);
  return (
    <span
      className={`${styles.root} num`}
      data-variant={variant}
      data-dir={dir}
      aria-label={ariaChangeLabel(changeRate, change)}
    >
      {amount != null && (
        <span className={styles.amount}>{formatDirectionalAmount(amount, change)}</span>
      )}
      {amount != null
        ? formatDirectional(changeRate, change).replace(/^[▲▼]\s/, "")
        : formatDirectional(changeRate, change)}
    </span>
  );
}
