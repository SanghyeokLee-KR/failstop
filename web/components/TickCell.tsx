"use client";

/**
 * 강도별 최소 간격 미만은 스킵하고 진행 중 플래시는 취소 후 재시작한다(§6 훅 계약).
 * reduced-motion이면 토글을 건너뛰고 값만 갱신한다. 강도·팝 상한은 상위 거버너가 넘긴다.
 */

import { memo, useEffect, useRef } from "react";
import type { EffectLevel } from "@/lib/types";
import { acquirePop, releasePop } from "@/components/trade/popBudget";
import { prefersReducedMotion } from "@/lib/reducedMotion";

const FLASH_MS = 260; // --dur-flash(220ms)·--dur-pop(220ms) + 여유
// 강도별 셀 스로틀(설계 §2-2). off는 팝·플래시 없음
const THROTTLE_FOR: Record<Exclude<EffectLevel, "off">, number> = {
  subtle: 500,
  normal: 300, // == --flash-throttle
  strong: 200,
};

/** 진행 중 플래시·팝 속성 제거 + 점유 예산 반납. 모듈 스코프라 이펙트 의존성에 잡히지 않는다 */
function clearEffectState(el: HTMLElement, budgeted: { current: boolean }, scope: string): void {
  el.removeAttribute("data-tick");
  el.removeAttribute("data-pop");
  el.removeAttribute("data-intensity");
  el.removeAttribute("data-strong");
  if (budgeted.current) {
    releasePop(scope);
    budgeted.current = false;
  }
}

interface Props {
  /** 틱 방향 판정용 수치 값 (delta 비교 전용, 표시에는 쓰지 않는다) */
  value: number;
  /**
   * 표시용 원본 십진 문자열. 렌더는 이 문자열로만 하고 value는 델타 비교에만 쓴다.
   * value(number) 왕복이 NUMERIC(40,18) 정밀을 잃는 근접·대형 가격에서 표시 오차를 없앤다.
   * 포맷 함수를 prop으로 받지 않는다. 호출부가 매 렌더 새 클로저를 만들어 memo가 무의미해진다.
   */
  valueStr: string;
  className?: string;
  /**
   * 효과 강도(거버너 적용 후 유효 단계). 기본 subtle(배경 플래시만, 팝 없음).
   * 팝은 opt-in이다. normal·strong을 명시한 트레이딩 화면 지정 셀만 팝한다(설계 §2-1).
   */
  level?: EffectLevel;
  /** 대형 체결 팝 승격 */
  strong?: boolean;
  /** 동시 팝 상한(거버너). 초과 시 값만 갱신. Infinity면 예산 미참여 */
  popCap?: number;
  /** 팝 예산 스코프. 패널 간 예산 격리용(§2-5). 같은 scope 셀끼리만 상한을 공유한다 */
  popScope?: string;
  /** 거버너 스로틀 가산(ms). 밴드 상승 시 팝·플래시 빈도를 낮춘다 */
  throttleBonus?: number;
}

function TickCell({ value, valueStr, className, level = "subtle", strong = false, popCap = Infinity, popScope = "default", throttleBonus = 0 }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const prev = useRef<number | null>(null);
  const lastFlash = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 전역 팝 예산을 실제 점유한 경우에만 true(무캡 팝은 예산 미참여, 반납 불요)
  const budgeted = useRef(false);
  // 확보 시점 scope를 고정해 반납이 항상 같은 카운터를 감소시키게 한다(중간에 prop 변해도 정합)
  const acquiredScope = useRef("default");

  // 최신 강도·대형·상한을 이펙트에서 참조(value 변동만 트리거로 유지)
  const levelRef = useRef(level);
  levelRef.current = level;
  const strongRef = useRef(strong);
  strongRef.current = strong;
  const popCapRef = useRef(popCap);
  popCapRef.current = popCap;
  const popScopeRef = useRef(popScope);
  popScopeRef.current = popScope;
  const throttleBonusRef = useRef(throttleBonus);
  throttleBonusRef.current = throttleBonus;

  useEffect(() => {
    const el = ref.current;
    const before = prev.current;
    prev.current = value;
    if (el == null || before == null) return;

    const delta = value - before;
    if (delta === 0) return; // flat 틱은 플래시 생략

    // reduced-motion: 값 갱신만, 토글 스킵
    if (prefersReducedMotion()) return;

    const lvl = levelRef.current;
    if (lvl === "off") return; // 표식만 모드: 색·부호·값은 유지, 모션·플래시 끔

    const now = performance.now();
    // 강도별 throttle + 거버너 가산(밴드 상승 시 빈도 억제)
    if (now - lastFlash.current < THROTTLE_FOR[lvl] + throttleBonusRef.current) return;
    lastFlash.current = now;

    if (timer.current) clearTimeout(timer.current);
    clearEffectState(el, budgeted, acquiredScope.current); // 진행 중 플래시·팝 취소 후 재시작(중첩 금지)

    const dir = delta > 0 ? "up" : "down";
    el.setAttribute("data-tick", dir);
    // subtle은 배경 플래시만. normal·strong은 팝 승격(상한 내에서)
    const cap = popCapRef.current;
    const scope = popScopeRef.current;
    if (lvl !== "subtle" && acquirePop(cap, scope)) {
      // 무캡(Infinity)은 전역 예산을 점유하지 않으므로 반납 대상에서 제외
      if (Number.isFinite(cap)) {
        budgeted.current = true;
        acquiredScope.current = scope;
      }
      el.setAttribute("data-pop", dir);
      el.setAttribute("data-intensity", lvl);
      if (strongRef.current) el.setAttribute("data-strong", "true");
    }

    timer.current = setTimeout(() => {
      clearEffectState(el, budgeted, acquiredScope.current);
      timer.current = null;
    }, FLASH_MS);
  }, [value]);

  // 언마운트 시 점유 예산 반납·타이머 정리
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
      if (budgeted.current) {
        releasePop(acquiredScope.current);
        budgeted.current = false;
      }
    },
    [],
  );

  return (
    <span ref={ref} className={`price-cell num ${className ?? ""}`}>
      {valueStr}
    </span>
  );
}

export default memo(TickCell);
