"use client";

/**
 * 목록에 새로 선 행(디자인 시스템 §12-4 목록 행 등장). 처음 그린 목록은 새 행이 아니다. 그 뒤에 나타난 열쇠만
 * holdMs 동안 돌려주어 행이 들어오는 움직임과 플래시를 건다. 시각 규칙은 CSS가 소유한다.
 */

import { useEffect, useRef, useState } from "react";

export function useFreshKeys(keys: readonly string[], holdMs = 600): ReadonlySet<string> {
  // 열쇠마다 처음 본 시각. 첫 그림의 열쇠는 0이라 새 행으로 치지 않는다
  const seen = useRef<Map<string, number> | null>(null);
  const [, redraw] = useState(0);
  const now = Date.now();
  const fresh = new Set<string>();
  if (seen.current === null) {
    seen.current = new Map(keys.map((k) => [k, 0]));
  } else {
    for (const k of keys) {
      if (!seen.current.has(k)) seen.current.set(k, now);
      const at = seen.current.get(k) ?? 0;
      if (at > 0 && now - at < holdMs) fresh.add(k);
    }
  }
  const pending = fresh.size;
  // 표지를 거둘 때 다시 그린다. 목록이 그 사이에 다시 그려지지 않아도 플래시 면이 남지 않게 한다
  useEffect(() => {
    if (pending === 0) return;
    const t = setTimeout(() => redraw((n) => n + 1), holdMs);
    return () => clearTimeout(t);
  }, [pending, holdMs]);
  return fresh;
}
