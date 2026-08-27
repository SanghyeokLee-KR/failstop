/**
 * tests/brandChip.test.ts. 코인 브랜드칩 라벨·배경 대비 검증.
 * 수기 플래그(brandTextDark)가 12종 중 1종만 채워져 BTC 2.30:1·DOGE 2.39:1·ETH 3.69:1·
 * AVAX 3.99:1을 남기던 결함의 회귀 방지. 시드 전종이 4.5:1을 넘어야 하고,
 * 대비가 이미 충분한 색은 브랜드색이 보존돼야 한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { resolveChipStyle, contrastRatio } from "../lib/brandChip";
import { COIN_META } from "../lib/sim/engine";

const LABEL_LIGHT: [number, number, number] = [255, 255, 255];
const LABEL_DARK: [number, number, number] = [22, 25, 31];
const MIN_RATIO = 4.5;

function rgb(hex: string): [number, number, number] {
  return [
    parseInt(hex.slice(1, 3), 16),
    parseInt(hex.slice(3, 5), 16),
    parseInt(hex.slice(5, 7), 16),
  ];
}

function ratioOf(style: { background: string; dark: boolean }): number {
  return contrastRatio(rgb(style.background), style.dark ? LABEL_DARK : LABEL_LIGHT);
}

test("시드 코인 전종의 칩 라벨 대비가 4.5:1 이상이다", () => {
  assert.ok(COIN_META.length > 0, "시드 코인이 비어 있다");
  for (const meta of COIN_META) {
    const style = resolveChipStyle(meta.brandColor);
    const cr = ratioOf(style);
    assert.ok(
      cr >= MIN_RATIO,
      `${meta.symbol} ${meta.brandColor} -> ${style.background} ${style.dark ? "dark" : "light"} ${cr.toFixed(2)}:1`,
    );
  }
});

test("라벨 선택만으로 4.5:1을 넘는 색은 브랜드색을 보존한다", () => {
  for (const meta of COIN_META) {
    const original = rgb(meta.brandColor);
    const best = Math.max(
      contrastRatio(original, LABEL_LIGHT),
      contrastRatio(original, LABEL_DARK),
    );
    if (best < MIN_RATIO) continue;
    const style = resolveChipStyle(meta.brandColor);
    assert.equal(
      style.background.toUpperCase(),
      meta.brandColor.toUpperCase(),
      `${meta.symbol}는 보정이 불필요한데 배경이 바뀌었다`,
    );
  }
});

test("양쪽 라벨 모두 미달인 색만 배경을 보정한다", () => {
  // AVAX. 흰 3.99 / 검 4.41로 라벨 선택만으로는 어느 쪽도 통과하지 못한다
  const style = resolveChipStyle("#E84142");
  assert.notEqual(style.background.toUpperCase(), "#E84142", "보정이 걸리지 않았다");
  assert.ok(ratioOf(style) >= MIN_RATIO);
  // 보정은 최소 이동이라 원 브랜드색과 채널당 차이가 작아야 한다
  const before = rgb("#E84142");
  const after = rgb(style.background);
  for (let i = 0; i < 3; i++) {
    assert.ok(Math.abs(after[i] - before[i]) <= 16, `채널 ${i} 이동폭 과다: ${before[i]} -> ${after[i]}`);
  }
});

test("대비가 높은 쪽 라벨을 고른다", () => {
  assert.equal(resolveChipStyle("#F7931A").dark, true, "BTC 주황은 어두운 라벨");
  assert.equal(resolveChipStyle("#23292F").dark, false, "XRP 먹색은 밝은 라벨");
  assert.equal(resolveChipStyle("#66F9A1").dark, true, "SOL 연두는 어두운 라벨");
});

test("hex가 아닌 브랜드색은 입력을 보존하고 밝은 라벨로 떨어진다", () => {
  const style = resolveChipStyle("var(--brand-500)");
  assert.equal(style.background, "var(--brand-500)");
  assert.equal(style.dark, false);
});
