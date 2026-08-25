/**
 * A-6. PriceChange. 등락률 pill/inline. 방향은 색 단독 금지 → ▲▼ + 부호 병기 (§0-2).
 * pill: -strong(700) on -bg 틴트 / inline: -text(600) on surface (§6 대비 계약).
 */

import type { ChangeState } from "@/lib/types";
import { ariaChangeLabel, dirOf, formatChangeRate, formatDirectional, formatDirectionalAmount, type PriceSpec } from "@/lib/format";
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
      {/* 금액을 병기할 때는 화살표가 금액 앞에 이미 붙어 중복된다. formatDirectional 출력에서
          정규식으로 벗기면 포맷터의 접두 형식(기호·공백)이 바뀔 때 조용히 깨지므로, 화살표 없는
          본문 포맷터를 직접 쓴다. */}
      {amount != null
        ? formatChangeRate(changeRate, change)
        : formatDirectional(changeRate, change)}
    </span>
  );
}
