/**
 * 탭 보임·숨김을 흉내 내는 최소 document. 박자(ticker.ts)가 불러오는 순간 visibilitychange를 구독하므로
 * 이 파일을 시험 파일에서 가장 먼저 불러온다. 시험 파일은 저마다 다른 프로세스에서 돌아 다른 파일로 새지 않는다.
 */

const listeners = new Set<() => void>();

const fake = {
  visibilityState: "visible" as "visible" | "hidden",
  addEventListener(type: string, fn: () => void): void {
    if (type === "visibilitychange") listeners.add(fn);
  },
  removeEventListener(type: string, fn: () => void): void {
    if (type === "visibilitychange") listeners.delete(fn);
  },
};

(globalThis as unknown as { document: typeof fake }).document = fake;

export function setHidden(hidden: boolean): void {
  fake.visibilityState = hidden ? "hidden" : "visible";
  for (const fn of listeners) fn();
}
