/**
 * 추세선 점 줄이기. 상태 요약 칸의 폭(120px 안팎)에서는 60점보다 촘촘한 점이 보이지 않고 경로 문자열과 비교만
 * 길어진다. 끝을 기준으로 묶어 마지막 점(지금 값)이 늘 제자리에 남는다.
 */

export const SPARK_MAX_POINTS = 60;

/**
 * 묶는 방법.
 * - extreme: 묶음마다 전체 평균에서 가장 먼 값을 남긴다. 지표 선에서 순간 치솟음을 지우지 않는다
 * - sum: 묶음의 합을 남긴다. 건수 막대는 묶은 동안 생긴 수를 보여야 한다
 */
export type DownsampleMode = "extreme" | "sum";

/** 점 수를 max 이하로 줄인다. 묶음이 모두 비었으면 빈 점이다. 몇 칸을 한 점으로 묶었는지(factor)도 돌려준다 */
export function downsample(
  points: (number | null)[],
  max = SPARK_MAX_POINTS,
  mode: DownsampleMode = "extreme",
): { points: (number | null)[]; factor: number } {
  if (points.length <= max) return { points, factor: 1 };
  const factor = Math.ceil(points.length / max);
  const vals = points.filter((v): v is number => v !== null);
  const mean = vals.length === 0 ? 0 : vals.reduce((a, v) => a + v, 0) / vals.length;
  const out: (number | null)[] = [];
  for (let end = points.length; end > 0; end -= factor) {
    let pick: number | null = null;
    for (let i = Math.max(0, end - factor); i < end; i += 1) {
      const v = points[i];
      if (v === null) continue;
      if (mode === "sum") pick = (pick ?? 0) + v;
      else if (pick === null || Math.abs(v - mean) > Math.abs(pick - mean)) pick = v;
    }
    out.push(pick);
  }
  return { points: out.reverse(), factor };
}
