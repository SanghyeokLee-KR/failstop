"use client";

/**
 * 조치안 카드 자리 이동(디자인 시스템 §12-4 v1.14). 틀 안의 [data-flip] 요소가 그림과 그림 사이에 자리를 옮기면
 * 새 자리에서 이전 자리만큼 되돌려 놓은 뒤 제자리로 옮긴다(FLIP). transform만 쓰고 움직임 줄이기면 곧바로 선다.
 * 자리는 틀 기준이라 틀 안 스크롤과 판 전체의 이동은 차이에 들지 않는다.
 */

import { useLayoutEffect, useRef, type RefObject } from "react";
import { flipDeltas, type Point } from "@/lib/admin/motion";
import { prefersReducedMotion } from "@/lib/reducedMotion";

export function useFlip(rootRef: RefObject<HTMLElement | null>, durationMs = 200): void {
  const last = useRef<Map<string, Point>>(new Map());
  // 그릴 때마다 잰다. 이 틀을 싣는 패널은 칸이 바뀔 때만 다시 그리므로(memo) 재는 횟수는 판단 횟수를 넘지 않는다
  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const origin = root.getBoundingClientRect();
    const els = Array.from(root.querySelectorAll<HTMLElement>("[data-flip]"));
    const now = new Map<string, Point>();
    for (const el of els) {
      const r = el.getBoundingClientRect();
      now.set(el.dataset.flip as string, { x: r.left - origin.left + root.scrollLeft, y: r.top - origin.top + root.scrollTop });
    }
    const deltas = flipDeltas(last.current, now);
    last.current = now;
    if (deltas.size === 0 || prefersReducedMotion()) return;
    for (const el of els) {
      const d = deltas.get(el.dataset.flip as string);
      if (!d || typeof el.animate !== "function") continue;
      el.animate([{ transform: `translate(${d.x}px, ${d.y}px)` }, { transform: "translate(0, 0)" }], {
        duration: durationMs,
        easing: "cubic-bezier(0, 0, .2, 1)",
      });
    }
  });
}
