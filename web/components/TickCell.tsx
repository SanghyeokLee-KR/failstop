"use client";

/**
 * A-5. NumberDisplay / 틱 플래시 셀.
 * 계약(§6 훅 계약): 셀별 마지막 플래시 시각 기록 → 최소 간격 300ms(--flash-throttle) 미만 스킵,
 * 진행 중 플래시는 취소 후 재시작(중첩 금지), reduced-motion이면 data-tick 토글 자체를 건너뜀(값 갱신만).
 * tabular-nums + 컨테이너 폭 유지 → 레이아웃 시프트 0.
 */

import { memo, useEffect, useRef } from "react";

const FLASH_THROTTLE_MS = 300; // == --flash-throttle
const FLASH_MS = 260;          // --dur-flash(220ms) + 여유

interface Props {
  /** 비교 가능한 수치 값 (표시 문자열은 format으로 생성) */
  value: number;
  format: (value: number) => string;
  className?: string;
}

function TickCell({ value, format, className }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const prev = useRef<number | null>(null);
  const lastFlash = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const el = ref.current;
    const before = prev.current;
    prev.current = value;
    if (el == null || before == null) return;

    const delta = value - before;
    if (delta === 0) return; // flat 틱은 플래시 생략

    // reduced-motion: 값 갱신만, 플래시 토글 스킵
    if (typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    const now = performance.now();
    if (now - lastFlash.current < FLASH_THROTTLE_MS) return; // throttle
    lastFlash.current = now;

    if (timer.current) clearTimeout(timer.current); // 진행 중 플래시 취소 후 재시작
    el.setAttribute("data-tick", delta > 0 ? "up" : "down");
    timer.current = setTimeout(() => {
      el.removeAttribute("data-tick");
      timer.current = null;
    }, FLASH_MS);
  }, [value]);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  return (
    <span ref={ref} className={`price-cell num ${className ?? ""}`}>
      {format(value)}
    </span>
  );
}

export default memo(TickCell);
