"use client";

/**
 * 관리 콘솔 층의 공통 동작(디자인 시스템 §12-13). 배경 격리, 포커스 가둠, Esc, 연 자리로 돌아가기, 닫힐 때의
 * 짧은 퇴장을 한곳에 둔다. 확인 대화상자, 모달, 드로어가 같은 규칙을 쓰게 하려는 것이다.
 *
 * 격리와 포커스 복귀를 한 이펙트에 둔다. React는 이펙트 정리를 선언 순서대로 돌리므로 둘을 나누면 inert가 남은
 * 채 복귀가 먼저 돌고, inert 안의 트리거는 focus()를 무시해 포커스가 body로 떨어진다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { exitDelay } from "@/lib/admin/motion";
import { prefersReducedMotion } from "@/lib/reducedMotion";
import { holdToasts } from "@/components/Toast";
import { isolate, isTopLayer, pushLayer, returnTarget, wrapFocus } from "./focusTrap";

/* 입력을 빼면 children으로 받은 사유 입력이 Tab 순환에서 사라진다. 층 안의 누를 것은 모두 잡는다 */
const FOCUSABLE =
  'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';

function focusables(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => !el.closest("[inert]") && el.getClientRects().length > 0);
}

export function useLayer({
  onClose,
  initialFocus,
  closeOnEscape = true,
}: {
  onClose: () => void;
  /** 처음 포커스를 둘 요소의 선택자. 없거나 비활성이면 층 자체에 둔다 */
  initialFocus?: string;
  /** 처리 중(busy)처럼 닫으면 안 되는 동안 false */
  closeOnEscape?: boolean;
}) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [closing, setClosing] = useState(false);
  // 이 층의 깊이. 맨 위 층만 층 안 알림 줄에 오류를 적는다(holdToasts)
  const [depth, setDepth] = useState(0);
  // 최신 onClose를 ref로 잡는다. 호출부가 인라인 화살표를 넘겨 폴링 리렌더마다 참조가 바뀌어도 격리·포커스 이펙트가
  // 다시 돌아 초기 포커스로 포커스를 되돌리는 강탈을 막는다
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const escRef = useRef(closeOnEscape);
  escRef.current = closeOnEscape;
  const closingRef = useRef(false);
  // 복귀 대상은 첫 렌더에서 잡는다. children의 autoFocus(사유 입력 등)는 커밋 중에 포커스를 옮겨 이펙트 시점의
  // activeElement가 트리거가 아니라 층 안의 입력이 된다
  const [opener] = useState(() => (typeof document === "undefined" ? null : (document.activeElement as HTMLElement | null)));

  const requestClose = useCallback(() => {
    if (closingRef.current) return;
    closingRef.current = true;
    const wait = exitDelay(prefersReducedMotion());
    if (wait === 0) {
      onCloseRef.current();
      return;
    }
    setClosing(true);
    setTimeout(() => onCloseRef.current(), wait);
  }, []);
  const requestCloseRef = useRef(requestClose);
  requestCloseRef.current = requestClose;

  useEffect(() => {
    const overlay = overlayRef.current;
    const panel = panelRef.current;
    const token = {};
    const pop = pushLayer(token);
    // 층이 열린 동안 토스트를 붙잡는다. 덮개 아래 오른쪽 아래의 토스트가 층의 가장자리와 겹쳐 읽히지 않게 한다
    const hold = holdToasts();
    setDepth(hold.depth);
    // 토스트 리전은 격리하지 않는다. inert에 걸리면 열린 동안 토스트 낭독과 닫기가 죽는다
    const restore = isolate(Array.from(document.body.children), (el) => el === overlay || (el as Element).hasAttribute("data-toast-region"));
    const onKey = (e: KeyboardEvent) => {
      if (!isTopLayer(token)) return;
      if (e.key === "Escape") {
        if (!escRef.current) return;
        e.preventDefault();
        requestCloseRef.current();
        return;
      }
      if (e.key !== "Tab" || !panel) return;
      const items = focusables(panel);
      // 처리 중에는 단추가 모두 비활성이라 누를 것이 없다. 층 자체에 붙들어 포커스가 밖으로 새지 않게 한다
      if (items.length === 0) {
        e.preventDefault();
        panel.focus();
        return;
      }
      const target = wrapFocus(items, document.activeElement as HTMLElement | null, e.shiftKey);
      if (target) {
        e.preventDefault();
        target.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    const first = initialFocus ? panel?.querySelector<HTMLElement>(initialFocus) : null;
    if (first && !(first as HTMLButtonElement).disabled) first.focus({ preventScroll: true });
    else panel?.focus({ preventScroll: true });
    return () => {
      document.removeEventListener("keydown", onKey);
      pop();
      hold.release();
      restore();
      // inert를 푼 뒤에 돌아간다. 연 단추가 판에서 사라졌으면 그 판의 제목으로 간다
      const back = returnTarget(opener, document.querySelector<HTMLElement>("#admin-main h1"));
      if (back && back !== opener && !back.hasAttribute("tabindex")) back.setAttribute("tabindex", "-1");
      back?.focus({ preventScroll: true });
    };
    // 마운트 1회만. 초기 포커스와 복귀는 열림·닫힘 경계에서만 일어나야 한다(폴링 리렌더와 무관)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { overlayRef, panelRef, closing, requestClose, depth };
}
