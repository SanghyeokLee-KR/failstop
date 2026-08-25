/**
 * lib/reducedMotion.ts. reduced-motion 선호 단일 판정원.
 *
 * 호출마다 window.matchMedia(...)를 새로 만들면 질의 객체가 매번 할당된다. 체결이 초당 수십 건
 * 흐르는 거래 화면에서는 이 판정이 셀마다·틱마다 실행되므로 그 곱만큼 할당이 쌓인다.
 * 질의 객체를 모듈에 한 번만 만들고 change 이벤트로 갱신한 값을 읽는다. 사용자가 OS 설정을
 * 바꾸면 다음 판정부터 즉시 반영된다.
 *
 * SSR·정적 export 프리렌더에서는 window가 없어 false(모션 허용)로 답한다. 하이드레이션 이후
 * 첫 판정에서 실제 값으로 확정되며, 이 값은 렌더 산출물이 아니라 이펙트 분기에만 쓰인다.
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
