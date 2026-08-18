/**
 * A-6. PriceChange. 등락률 pill/inline. 방향은 색 단독 금지 → ▲▼ + 부호 병기 (§0-2).
 * pill: -strong(700) on -bg 틴트 / inline: -text(600) on surface (§6 대비 계약).
 */

import type { ChangeState } from "@/lib/types";
import { ariaChangeLabel, dirOf, formatDirectional, formatDirectionalAmount, type PriceSpec } from "@/lib/format";
import styles from "./PricePill.module.css";

interface Props {
  change: ChangeState;
  /** 절대값 등락률 문자열/숫자 ("0.0234" → 2.34%) */
  changeRate: string | number;
  /** withAmount: 등락 금액 병기 (코인 상세 헤더) */
  amount?: string | number;
  /** amount 표시 자릿수 스펙. 가격 델타는 tick_size, KRW 손익 금액은 0을 넘긴다 */
  amountSpec?: PriceSpec;
  variant?: "pill" | "inline";
}

export default function PricePill({ change, changeRate, amount, amountSpec, variant = "pill" }: Props) {
  const dir = dirOf(change);
  return (
    <span
      className={`${styles.root} num`}
      data-variant={variant}
      data-dir={dir}
      aria-label={ariaChangeLabel(changeRate, change)}
    >
      {amount != null && (
        <span className={styles.amount}>{formatDirectionalAmount(amount, change, amountSpec)}</span>
      )}
      {amount != null
        ? formatDirectional(changeRate, change).replace(/^[▲▼]\s/, "")
        : formatDirectional(changeRate, change)}
    </span>
  );
}
