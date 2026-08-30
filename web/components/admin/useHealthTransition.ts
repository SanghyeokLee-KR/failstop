"use client";

/**
 * 시각 규칙은 CSS가 소유하고, 훅은 전이 순간의 헬스값만 holdMs 동안 돌려준다.
 * 정지가 기본값(디자인_방향 §5)이라 최초 마운트와 null 경계는 전이로 치지 않는다.
 */

import { useEffect, useRef, useState } from "react";
import type { Health } from "./ui";

export function useHealthTransition(health: Health | null, holdMs = 600): Health | null {
  const [flash, setFlash] = useState<Health | null>(null);
  // undefined는 미관측 표지. null은 유효 상태(데이터 없음)라 별도 초기값이 필요하다
  const prevRef = useRef<Health | null | undefined>(undefined);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (prevRef.current === undefined) {
      // 최초 관측은 전이가 아니다. StrictMode 이중 실행에도 ref가 유지돼 재발화하지 않는다
      prevRef.current = health;
      return;
    }
    if (prevRef.current === health) return;
    const prev = prevRef.current;
    prevRef.current = health;
    // null 경계(데이터 도착·소실)는 발화 생략. 헬스 간 이동만 플래시
    if (prev === null || health === null) return;
    if (timerRef.current !== null) clearTimeout(timerRef.current);
    setFlash(health);
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setFlash(null);
    }, holdMs);
  }, [health, holdMs]);

  useEffect(() => {
    // 언마운트 후 setState 누수 방지
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    };
  }, []);

  return flash;
}
