/**
 * popBudget.ts. 동시 팝 애니메이션 상한(체결_시각효과 설계 §2-5).
 * 프레임 예산을 넘는 팝을 값 갱신만으로 흡수해 고체결률 CPU 스파이크를 막는다.
 * 스코프별 카운터로 패널 간 예산을 격리한다. 한 패널(예: 마켓 목록)의 다수 셀이 다른 패널
 * (예: 현재가 히어로)의 슬롯을 잠식하지 못하게 한다. 유한 상한을 건 셀만 카운터를 공유한다.
 * 무캡(Infinity) 셀은 예산에 참여하지 않는다. 셀은 팝 시작 시 acquire, 종료 시 같은 scope로 release.
 */

const active = new Map<string, number>();

/**
 * 팝 슬롯 확보. 무캡(Infinity)은 예산 미참여로 항상 허용하되 카운터를 증가시키지 않는다.
 * 유한 상한 초과면 false(호출부는 팝을 생략하고 값만 갱신). scope별로 독립 카운트한다.
 */
export function acquirePop(cap: number, scope = "default"): boolean {
  if (!Number.isFinite(cap)) return true;
  const cur = active.get(scope) ?? 0;
  if (cur >= cap) return false;
  active.set(scope, cur + 1);
  return true;
}

/** 팝 슬롯 반납. 확보 성공분에 대해 acquire와 동일 scope로만 호출한다 */
export function releasePop(scope = "default"): void {
  const cur = active.get(scope) ?? 0;
  active.set(scope, Math.max(0, cur - 1));
}
