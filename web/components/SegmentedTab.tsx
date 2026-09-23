"use client";

/** A-9. SegmentedTab. 변형 neutral(브랜드) / directional(매수Red·매도Blue) / underline (§7) */

import { useRef } from "react";
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
  /** 제출 in-flight 등 선택 잠금. 버튼 비활성 + onChange 무시 */
  disabled?: boolean;
  /**
   * ARIA 시맨틱. 기본 tab은 tablist/tab(방향키=포커스 이동, 활성화는 Enter·Space·클릭).
   * radio는 radiogroup/radio(방향키=포커스 이동 + 즉시 선택). 매수·매도처럼 진짜 탭이 아닌
   * 상호배타 액션은 소비처가 radio로 지정한다. 기본값은 기존 role 유지(소비처 무회귀).
   */
  semantics?: "tab" | "radio";
  /**
   * 탭이 여닫는 패널의 id. 주면 각 탭에 id를 달고 선택된 탭이 aria-controls로 패널을 가리킨다.
   * 패널은 id={panelId}와 aria-labelledby={segmentedTabId(panelId, value)}로 되짚는다. 패널 하나를
   * 탭이 공유하는 필터형이라 선택된 탭만 패널을 가리킨다. tab 시맨틱에서만 쓴다.
   */
  panelId?: string;
}

/** panelId를 받은 SegmentedTab이 각 탭에 다는 id. 패널의 aria-labelledby가 같은 규칙으로 찾는다 */
export function segmentedTabId(panelId: string, value: string): string {
  return `${panelId}-tab-${value}`;
}

export default function SegmentedTab<T extends string>({
  items,
  value,
  onChange,
  variant = "neutral",
  ariaLabel,
  disabled = false,
  semantics = "tab",
  panelId,
}: Props<T>) {
  const btnRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const isRadio = semantics === "radio";
  const linkPanel = !isRadio && panelId !== undefined;
  // 로빙 tabindex: 선택 항목만 Tab 정지점. 미선택 시 첫 항목
  const activeIndex = Math.max(
    0,
    items.findIndex((it) => it.value === value),
  );

  const onKeyDown = (e: React.KeyboardEvent, idx: number) => {
    if (disabled) return;
    const count = items.length;
    let next = -1;
    if (e.key === "ArrowRight" || e.key === "ArrowDown") next = (idx + 1) % count;
    else if (e.key === "ArrowLeft" || e.key === "ArrowUp") next = (idx - 1 + count) % count;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = count - 1;
    else return;
    e.preventDefault();
    btnRefs.current[next]?.focus();
    // radio 패턴은 이동 즉시 선택. tab 패턴은 포커스만 이동(활성화는 Enter·Space·클릭)
    if (isRadio) onChange(items[next].value);
  };

  return (
    <div
      className={styles.root}
      data-variant={variant}
      role={isRadio ? "radiogroup" : "tablist"}
      aria-label={ariaLabel}
    >
      {items.map((item, idx) => {
        const selected = item.value === value;
        return (
          <button
            key={item.value}
            ref={(el) => {
              btnRefs.current[idx] = el;
            }}
            type="button"
            id={linkPanel ? segmentedTabId(panelId, item.value) : undefined}
            aria-controls={linkPanel && selected ? panelId : undefined}
            role={isRadio ? "radio" : "tab"}
            aria-selected={isRadio ? undefined : selected}
            aria-checked={isRadio ? selected : undefined}
            tabIndex={idx === activeIndex ? 0 : -1}
            className={styles.tab}
            data-selected={selected || undefined}
            data-tone={variant === "directional" ? item.tone : undefined}
            disabled={disabled}
            onKeyDown={(e) => onKeyDown(e, idx)}
            onClick={() => {
              if (disabled) return;
              onChange(item.value);
            }}
          >
            {item.label}
          </button>
        );
      })}
    </div>
  );
}
