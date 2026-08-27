/**
 * tests/chartLineStyles.test.ts. 지표 선 스타일 오버라이드 완전성 검증.
 *
 * klinecharts는 지표 인스턴스의 styles.lines를 기본값과 병합하지 않고 통째로 대체한다
 * (eachFigures -> formatValue(styles, 'lines', defaultStyles.lines)).
 * color만 담은 오버라이드를 넘기면 dashedValue가 사라지고, 선분 병합 비교가
 * styles.dashedValue[0]을 읽다가 TypeError로 렌더 루프를 죽인다. 그 시점부터 차트가
 * 스크롤·드래그에 반응하지 않는다(볼린저를 켜면 차트가 멈추던 결함).
 * 새 지표를 추가할 때 색만 지정하고 끝내는 실수를 막는다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { bollStyles, kdjStyles, macdStyles, rsiStyles, volStyles, buildChartStyles } from "../components/chart/chartTheme";
import type { ChartTokens } from "../components/chart/chartTheme";

/** 선 병합 비교가 읽는 속성. 하나라도 빠지면 렌더 중 예외가 난다 */
const REQUIRED_LINE_KEYS = ["color", "size", "style", "smooth", "dashedValue"] as const;

const TOKENS: ChartTokens = {
  up: "#FF5D63", down: "#35A6FF", flat: "#8C95A1",
  upText: "#FF8086", downText: "#5CB6FF",
  grid: "#262B35", axisText: "#A8B2BE", axisLine: "#333A46", crosshairLine: "#454E5C",
  surface: "#171A21", borderDefault: "#333A46", borderStrong: "#454E5C",
  textDefault: "#E4E8ED", textSecondary: "#A8B2BE", textInverse: "#16191F",
  brand400: "#4E7DEE", brand500: "#2E6BE6", brand600: "#1F58CC", brand700: "#1848AC",
  series1: "#EBF1FE", series2: "#A9C2FA", series3: "#7B9FF5", series4: "#4E7DEE",
  brandPrimary: "#6B9DF7", font: "Pretendard",
  priceMarkRadius: 8, priceMarkPadY: 4, priceMarkPadX: 8,
};

const OVERRIDES: Array<[string, (t: ChartTokens) => { lines?: unknown }]> = [
  ["BOLL", bollStyles],
  ["MACD", macdStyles],
  ["RSI", rsiStyles],
  ["KDJ", kdjStyles],
  ["VOL", volStyles],
];

function assertCompleteLines(label: string, lines: unknown): void {
  assert.ok(Array.isArray(lines), `${label}: lines가 배열이 아니다`);
  (lines as Record<string, unknown>[]).forEach((ln, i) => {
    for (const key of REQUIRED_LINE_KEYS) {
      assert.ok(key in ln, `${label} lines[${i}]에 ${key}가 없다`);
      assert.notEqual(ln[key], undefined, `${label} lines[${i}].${key}가 undefined다`);
    }
    const dv = ln.dashedValue;
    assert.ok(Array.isArray(dv) && dv.length === 2, `${label} lines[${i}].dashedValue는 길이 2 배열이어야 한다`);
    assert.ok(typeof ln.size === "number" && (ln.size as number) > 0, `${label} lines[${i}].size가 양수가 아니다`);
    assert.equal(typeof ln.smooth, "boolean", `${label} lines[${i}].smooth가 불리언이 아니다`);
  });
}

test("지표별 선 스타일 오버라이드는 병합 비교에 필요한 속성을 전부 채운다", () => {
  for (const [label, fn] of OVERRIDES) {
    const styles = fn(TOKENS);
    assertCompleteLines(label, styles.lines);
  }
});

test("전역 지표 램프도 같은 규격을 지킨다", () => {
  const styles = buildChartStyles(TOKENS);
  assertCompleteLines("global", styles.indicator?.lines);
});

test("오버라이드 색은 토큰 해석값을 그대로 싣는다", () => {
  const boll = bollStyles(TOKENS).lines as Array<{ color: string }>;
  assert.deepEqual(
    boll.map((l) => l.color),
    [TOKENS.borderStrong, TOKENS.brandPrimary, TOKENS.borderStrong],
    "볼린저 상·중·하 밴드 색이 설계(§4.2)와 다르다",
  );
  const rsi = rsiStyles(TOKENS).lines as Array<{ color: string }>;
  assert.equal(rsi.length, 1);
  assert.equal(rsi[0].color, TOKENS.brandPrimary);
});
