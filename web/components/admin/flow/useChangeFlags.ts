"use client";

/**
 * 값이 바뀐 열쇠만 holdMs 동안 켜 둔다. 구성도의 링 펄스와 대수 플래시가 쓴다. 시각 규칙은 CSS 가
 * 소유하고 이 훅은 켤 열쇠만 돌려준다(useHealthTransition 과 같은 역할 분담).
 *
 * 처음 관측한 값은 전이가 아니다. 화면에 들어오는 순간 모든 카드가 한꺼번에 퍼지면 무엇이 바뀌었는지
 * 가 오히려 가려진다. 이전 값은 ref 에 두어 StrictMode 의 effect 이중 실행에도 다시 켜지지 않는다.
 * 켜 있는 동안 같은 열쇠가 또 바뀌면 그 순간부터 다시 센다.
 */

import { useEffect, useRef, useState } from "react";

type Values = Record<string, string | number>;
type Flags = Record<string, true>;

const NONE: Flags = {};

export function useChangeFlags(values: Values, holdMs: number): Flags {
  const [flags, setFlags] = useState<Flags>(NONE);
  const prevRef = useRef<Values | null>(null);
  const timersRef = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  // 호출부가 매번 새 객체를 넘기므로 내용으로 비교한다. 열쇠 순서는 호출부가 고정이라 문자열로 접는다
  const sig = Object.keys(values)
    .map((k) => `${k}=${values[k]}`)
    .join("|");

  useEffect(() => {
    const prev = prevRef.current;
    prevRef.current = { ...values };
    if (prev === null) return;
    const changed = Object.keys(values).filter((k) => k in prev && prev[k] !== values[k]);
    if (changed.length === 0) return;
    const timers = timersRef.current;
    setFlags((cur) => {
      const next = { ...cur };
      for (const k of changed) next[k] = true;
      return next;
    });
    for (const k of changed) {
      const old = timers.get(k);
      if (old !== undefined) clearTimeout(old);
      timers.set(
        k,
        setTimeout(() => {
          timers.delete(k);
          setFlags((cur) => {
            if (!cur[k]) return cur;
            const next = { ...cur };
            delete next[k];
            return Object.keys(next).length === 0 ? NONE : next;
          });
        }, holdMs),
      );
    }
    // values 는 sig 가 대신한다. 내용이 같은 새 객체로는 다시 돌지 않는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig, holdMs]);

  useEffect(() => {
    const timers = timersRef.current;
    // 언마운트 뒤 setState 가 남지 않게 걸린 타이머를 모두 거둔다
    return () => {
      for (const t of timers.values()) clearTimeout(t);
      timers.clear();
    };
  }, []);

  return flags;
}
