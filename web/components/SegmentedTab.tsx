"use client";

/** A-9. SegmentedTab. 변형 neutral(브랜드) / directional(매수Red·매도Blue) / underline (§7) */

import styles from "./SegmentedTab.module.css";

export interface SegmentedItem<T extends string = string> {
  value: T;
  label: string;
  /** directional 변형에서 선택 시 색: buy=Red / sell=Blue */
  tone?: "buy" | "sell";
}

interface Props<T extends string> {
  items: SegmentedItem<T>[];
  value: T;
  onChange: (value: T) => void;
  variant?: "neutral" | "directional" | "underline";
  ariaLabel: string;
}

export default function SegmentedTab<T extends string>({
  items,
  value,
  onChange,
  variant = "neutral",
  ariaLabel,
}: Props<T>) {
  return (
    <div className={styles.root} data-variant={variant} role="tablist" aria-label={ariaLabel}>
      {items.map((item) => {
        const selected = item.value === value;
        return (
          <button
            key={item.value}
            type="button"
            role="tab"
            aria-selected={selected}
            className={styles.tab}
            data-selected={selected || undefined}
            data-tone={variant === "directional" ? item.tone : undefined}
            onClick={() => onChange(item.value)}
          >
            {item.label}
          </button>
        );
      })}
    </div>
  );
}
