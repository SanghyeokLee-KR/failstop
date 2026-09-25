/**
 * 앱 계층 활동 기록(관리자 설계 4-10-3 용량·부하). app-3 켜기·끄기 한 줄의 낱말과, 활동 기록에서 센 오늘 가동 시간이다.
 * 가동 시간은 켜기부터 끄기의 드레이닝이 끝날 때까지이고(인스턴스 요금이 붙는 구간), 드레이닝 중에 다시 켜면 이어서 켜져 있는
 * 것으로 센다. 관제 판의 app-3 가동 시간(ReadTier.onSecondsToday)과 같은 규칙이라 두 값이 같아야 한다(일치 목록).
 */

import { kstDay } from "./timeText";
import type { TierActivity } from "./types";

export const VIA_WORD: Record<TierActivity["via"], string> = {
  OPERATOR: "운영자",
  AI_APPROVED: "AI 승인",
  AI_AUTO: "AI 자동",
  REVERT: "되돌림",
  AUTO_REVERT: "자동 되돌림",
  SCENARIO: "시나리오 준비",
};

/** 걸린 시간 칸의 글. 기동·드레이닝이 끝났으면 초, 아니면 진행이나 멈춤이다 */
export function tookWord(a: TierActivity): string {
  const verb = a.action === "ON" ? "기동" : "드레이닝";
  if (a.tookSec !== null) return `${verb} ${a.tookSec}초`;
  if (a.interrupted) return a.action === "ON" ? "기동 중 끔" : "드레이닝 중 다시 켬";
  return `${verb} 중`;
}

/** 활동 기록에서 센 오늘(KST) app-3 가동 초 */
export function onSecondsFromActivity(activity: TierActivity[], now: number): number {
  const midnight = Date.parse(`${kstDay(now)}T00:00:00+09:00`);
  const rows = [...activity].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  let start: number | null = null;
  let ms = 0;
  const add = (from: number, to: number) => {
    ms += Math.max(0, Math.min(to, now) - Math.max(from, midnight));
  };
  for (const r of rows) {
    const t = Date.parse(r.at);
    if (r.action === "ON") {
      if (start === null) start = t;
      continue;
    }
    // 드레이닝 중에 다시 켠 끄기는 끝나지 않았다. 켜진 구간이 이어진다
    if (start === null || r.interrupted) continue;
    add(start, r.tookSec === null ? now : t + r.tookSec * 1000);
    start = null;
  }
  if (start !== null) add(start, now);
  return Math.round(ms / 1000);
}
