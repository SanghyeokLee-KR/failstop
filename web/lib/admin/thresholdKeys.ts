/**
 * 편집 칸의 건반(디자인 시스템 §12-9 v1.14). 행마다 초점 자리는 숫자 칸 하나이고, 슬라이더는 마우스로만 끈다. 위·아래
 * 화살표는 브라우저가 한 눈금씩 움직이고, PageUp·PageDown은 열 눈금씩 허용 범위 안에서 움직인다. Esc는 고치던 값을
 * 버린다. 행마다 초점이 두 번 서던 것(슬라이더와 숫자 칸)을 한 번으로 줄여 표를 건너는 탭 수가 절반이 됐다
 */
export function stepKey(key: string, value: number, t: { min: number; max: number; step: number }): number | null {
  const big = t.step * 10;
  const clamp = (v: number) => Math.min(t.max, Math.max(t.min, v));
  // 소수 눈금(0.5 등)을 더할 때 생기는 꼬리를 눈금 자리수로 자른다
  const digits = (String(t.step).split(".")[1] ?? "").length;
  const fix = (v: number) => Number(clamp(v).toFixed(digits));
  // 칸을 비운 채 누르면 허용 범위의 아래 끝에서 센다
  const base = Number.isFinite(value) ? value : t.min;
  if (key === "PageUp") return fix(base + big);
  if (key === "PageDown") return fix(base - big);
  return null;
}
