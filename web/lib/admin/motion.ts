/**
 * 관리 콘솔 상태 전이 모션의 계산(디자인 시스템 §12-4). 모양은 CSS가 소유하고, 여기에는 언제·얼마나를
 * 정하는 계산만 둔다. 움직임 줄이기 분기와 FLIP 계산을 웹 단위 시험이 이 경계로 본다.
 */

/** 층이 닫힐 때 거꾸로 도는 길이. 움직임 줄이기면 곧바로 닫는다 */
export const LAYER_EXIT_MS = 120;

export function exitDelay(reduced: boolean): number {
  return reduced ? 0 : LAYER_EXIT_MS;
}

/** 판단 과정 단계가 차례로 켜지는 간격 */
export const STEP_GAP_MS = 120;

/**
 * 단계별 켜짐 지연. 한 판단을 처음 열 때만 차례로 켜고, 다시 열거나 움직임을 줄이면 모두 0이다.
 * 이미 본 판단을 다시 기다리게 하면 읽기를 막는 장식이 된다
 */
export function stepDelays(count: number, firstOpen: boolean, reduced: boolean, gap = STEP_GAP_MS): number[] {
  return Array.from({ length: count }, (_, i) => (firstOpen && !reduced ? i * gap : 0));
}

export interface Point {
  x: number;
  y: number;
}

/**
 * FLIP의 되돌림 폭. 같은 열쇠의 카드가 이전 자리(before)에서 새 자리(after)로 옮겼으면 새 자리에서 이전 자리만큼
 * 되돌려 놓을 거리다. 새로 생긴 카드, 사라진 카드, min 아래로 움직인 카드는 없다. 자리는 목록 틀 기준이라
 * 본문 스크롤은 차이에 들지 않는다
 */
export function flipDeltas(before: ReadonlyMap<string, Point>, after: ReadonlyMap<string, Point>, min = 1): Map<string, Point> {
  const out = new Map<string, Point>();
  for (const [key, next] of after) {
    const prev = before.get(key);
    if (!prev) continue;
    const dx = prev.x - next.x;
    const dy = prev.y - next.y;
    if (Math.abs(dx) < min && Math.abs(dy) < min) continue;
    out.set(key, { x: dx, y: dy });
  }
  return out;
}

/**
 * 많을수록 나쁜 수(경보, 진행 사건, 대기 조치안)의 플래시 색. 바뀐 뒤의 상태로 칠한다. 늘면 주의, 0으로 내려가면
 * 정상이고, 줄었지만 남아 있으면 플래시 없이 숫자만 교차한다. 대수처럼 좋고 나쁨이 없는 수는 호출자가 그 칸의
 * 상태로 색을 정한다
 */
export function flashTone(prev: number, next: number): "success" | "warning" | null {
  if (prev === next) return null;
  if (next === 0) return "success";
  return next > prev ? "warning" : null;
}
