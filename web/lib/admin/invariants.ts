/**
 * 불변식 요약 판정. 두 어댑터가 같은 함수를 쓴다.
 *
 * 분모는 서버가 돌려준 행 수가 아니라 기대 검사 수다. 행 수를 분모로 쓰면 표가 비었을 때
 * 0/0이 되고 `통과 수 < 행 수` 같은 식이 거짓이 되어 미수집이 통과로 칠해진다.
 * 학교 AWS는 하네스를 끈 채 운영하므로(app.env.school-private) 빈 표가 실재하는 환경이고,
 * 그 상태에서 관제 최상단이 자산 보존을 단정하면 장애 대응 중 판단을 직접 오도한다.
 *
 * 기대 검사는 상시 검증기가 주기마다 싣는 정기 검사 전부다. 권위 run 전용 검사는 실험이
 * 있어야만 행이 생기므로 기대에 넣으면 평시가 영구히 부분 수집이 된다. 그래서 응답에 실린
 * 만큼만 분모에 더한다. 서버 응답은 check_name별 최신 1행이라 이름이 겹치지 않는다.
 */

import { INVARIANT_DEFS } from "./catalog";
import type { InvariantCheck, InvariantSummary } from "./types";

/** 상시 검증기가 주기마다 적재하는 검사. 이것이 전부 있어야 통과라고 부를 수 있다 */
export const SCHEDULED_INVARIANTS: string[] = INVARIANT_DEFS.filter((d) => d.scope === "SCHEDULED").map(
  (d) => d.name,
);

export function summarizeInvariants(checks: InvariantCheck[]): InvariantSummary {
  const scheduled = new Set(SCHEDULED_INVARIANTS);
  const scheduledSeen = checks.filter((c) => scheduled.has(c.name)).length;
  // 권위 run 전용이거나 카탈로그에 아직 없는 이름. 실린 만큼은 측정된 사실이라 분모에 넣는다
  const extra = checks.length - scheduledSeen;
  const expected = scheduled.size + extra;
  const violated = checks.filter((c) => !c.ok).length;
  const ok = checks.length - violated;
  // 최신 검사 시각. 행이 없으면 null이고 그것을 현재 시각으로 채우지 않는다
  const checkedAt = checks.reduce(
    (a: string | null, c) => (a === null || c.checkedAt > a ? c.checkedAt : a),
    null as string | null,
  );

  // 판정 순서가 계약이다. 위반이 미수집을 이긴다. 일부만 수집됐더라도 그 안에 위반이 있으면
  // 그것은 측정된 실패이고, 측정된 실패는 표본 부족보다 강한 사실이다.
  const state =
    violated > 0 ? "VIOLATED" : checks.length === 0 ? "NONE" : scheduledSeen < scheduled.size ? "PARTIAL" : "PASS";
  return { state, ok, violated, expected, checkedAt };
}
