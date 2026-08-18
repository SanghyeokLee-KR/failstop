/**
 * lib/entryGate.ts. 루트(/) 진입 분기 판정.
 * 정적 export라 서버가 세션을 볼 수 없다. 판정은 클라이언트에서 동기로 끝내야 한다.
 * 세션 갱신 같은 네트워크를 기다리면 빈 화면이 길어져 첫인상이 상하고, 판정이 실패하면
 * 데모 진행이 막힌다. 그래서 실패는 전부 /home으로 수렴시킨다.
 */

/** 온보딩을 이미 보여준 브라우저인지 기록하는 키 */
export const ONBOARDED_KEY = "exchange.onboarded";

/**
 * 온보딩을 지난 브라우저인지.
 * storage 접근 실패(사생활 보호 모드·차단)는 "지났다"로 본다. 온보딩을 반복 노출하는 쪽보다
 * 바로 홈으로 보내는 쪽이 데모 진행을 막지 않는다.
 */
export function hasOnboarded(): boolean {
  try {
    return localStorage.getItem(ONBOARDED_KEY) === "1";
  } catch {
    return true;
  }
}

/** 온보딩 노출 기록. 게이트는 브라우저당 1회만 발동한다 */
export function markOnboarded(): void {
  try {
    localStorage.setItem(ONBOARDED_KEY, "1");
  } catch {
    /* 기록 실패는 다음 진입에서 온보딩이 한 번 더 보일 뿐이라 무시한다 */
  }
}

/** 세션 보유자와 재방문자는 홈으로, 처음 온 방문자만 온보딩으로 */
export function entryPathFor(hasSession: boolean, onboarded: boolean): "/home" | "/welcome" {
  return hasSession || onboarded ? "/home" : "/welcome";
}
