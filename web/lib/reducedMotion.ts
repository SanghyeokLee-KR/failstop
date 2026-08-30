/**
 * reduced-motion 선호 단일 판정원. 호출마다 matchMedia를 새로 만들면 체결이 초당 수십 건
 * 흐르는 거래 화면에서 셀마다·틱마다 질의 객체가 할당된다. 모듈에 하나만 두고 change로 갱신한다.
 * SSR·프리렌더에는 window가 없어 false(모션 허용)로 답하고, 하이드레이션 이후 실제 값으로 확정된다.
 */

const QUERY = "(prefers-reduced-motion: reduce)";

let mql: MediaQueryList | null = null;
let current = false;

function ensure(): void {
  if (mql !== null) return;
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
  mql = window.matchMedia(QUERY);
  current = mql.matches;
  // 리스너는 앱 수명 내내 유지한다. 판정원이 모듈 단일이라 해제 지점이 없고, 해제하면
  // 설정 변경이 반영되지 않는다.
  mql.addEventListener("change", (e) => {
    current = e.matches;
  });
}

/** 모션을 줄여야 하는지. 질의 객체는 최초 1회만 만든다 */
export function prefersReducedMotion(): boolean {
  ensure();
  return current;
}
