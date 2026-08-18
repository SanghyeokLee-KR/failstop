/**
 * tests/entryGate.test.ts. 루트 진입 분기 판정 검증.
 * 온보딩 화면이 도달 불가였던 회귀와, 판정 실패가 데모 진입을 막던 위험을 고정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { ONBOARDED_KEY, entryPathFor, hasOnboarded, markOnboarded } from "../lib/entryGate";

/** localStorage 대역. throws=true면 접근 차단(사생활 보호 모드) */
function installStorage(initial: Record<string, string> = {}, throws = false): void {
  const store = new Map(Object.entries(initial));
  (globalThis as { localStorage?: unknown }).localStorage = {
    getItem(k: string): string | null {
      if (throws) throw new Error("storage blocked");
      return store.get(k) ?? null;
    },
    setItem(k: string, v: string): void {
      if (throws) throw new Error("storage blocked");
      store.set(k, v);
    },
  };
}

test("첫 방문은 온보딩으로 간다", () => {
  assert.equal(entryPathFor(false, false), "/welcome");
});

test("재방문은 홈으로 간다", () => {
  assert.equal(entryPathFor(false, true), "/home");
});

test("세션 보유자는 온보딩을 건너뛴다", () => {
  assert.equal(entryPathFor(true, false), "/home");
  assert.equal(entryPathFor(true, true), "/home");
});

test("표식이 없으면 첫 방문, 남기면 재방문", () => {
  installStorage();
  assert.equal(hasOnboarded(), false);
  markOnboarded();
  assert.equal(hasOnboarded(), true);
});

test("표식이 약속한 값이 아니면 첫 방문으로 본다", () => {
  // 다른 키와 충돌하거나 옛 형식이 남았을 때 온보딩을 건너뛰지 않는다
  installStorage({ [ONBOARDED_KEY]: "true" });
  assert.equal(hasOnboarded(), false);
});

test("storage 차단은 홈으로 수렴한다(데모 진입 우선)", () => {
  installStorage({}, true);
  assert.equal(hasOnboarded(), true);
  assert.equal(entryPathFor(false, hasOnboarded()), "/home");
  markOnboarded(); // 기록 실패가 예외로 새지 않는다
});
