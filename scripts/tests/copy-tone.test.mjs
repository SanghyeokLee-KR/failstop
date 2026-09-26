/**
 * copy-tone.mjs 시험. node --test scripts/tests/copy-tone.test.mjs 로 돈다(외부 의존 없음).
 * 관리 콘솔은 합니다체라 반말 평서문을 잡고, 그 밖의 화면은 명사형이라 존댓말 종결을 잡는지 본다.
 */

import test from "node:test";
import assert from "node:assert/strict";
import { checkCopyTone, hasHonorificNida, isAdminConsole, koreanLiterals, plainEnding } from "../copy-tone.mjs";

test("관리 콘솔 경로를 가른다", () => {
  assert.equal(isAdminConsole("app/admin/(ops)/page.tsx"), true);
  assert.equal(isAdminConsole("components/admin/control/AiPanel.tsx"), true);
  assert.equal(isAdminConsole("lib/admin/opsSim.ts"), true);
  assert.equal(isAdminConsole("components/trade/OrderPanel.tsx"), false);
  assert.equal(isAdminConsole("app/administer/page.tsx"), false);
});

test("ㅂ니다는 종성으로 가른다", () => {
  assert.equal(hasHonorificNida("곧바로 실행합니다"), true);
  assert.equal(hasHonorificNida("값이 적용됩니다"), true);
  assert.equal(hasHonorificNida("연결 전입니다"), true);
  assert.equal(hasHonorificNida("정상이 아니다"), false);
});

test("반말 평서문 종결을 찾는다", () => {
  assert.equal(plainEnding("승인하면 곧바로 실행한다"), "실행한다");
  assert.equal(plainEnding("조치안이 없다. 다시 판단한다"), "없다");
  assert.equal(plainEnding("정상이 아니다"), "아니다");
  assert.equal(plainEnding("판단을 멈췄다 · 재개 대기"), "멈췄다");
});

test("합니다체와 명사형, 조사는 반말로 보지 않는다", () => {
  assert.equal(plainEnding("승인하면 곧바로 실행합니다"), null);
  assert.equal(plainEnding("연결 전입니다"), null);
  assert.equal(plainEnding("근거가 60초보다 오래됨"), null);
  assert.equal(plainEnding("켤 때마다 0부터 셉니다"), null);
  assert.equal(plainEnding("자동 실행 가능"), null);
  assert.equal(plainEnding("다음 판단"), null);
});

test("식으로 끊긴 JSX 조각도 모은다", () => {
  const code = "const a = <p>자동 조치 {n}회를 실행했다</p>;";
  assert.ok(koreanLiterals(code, { jsxSplit: true }).includes("회를 실행했다"));
  assert.ok(!koreanLiterals(code).includes("회를 실행했다"));
});

test("관리 콘솔은 반말을 잡고 합니다체를 통과시킨다", () => {
  const bad = checkCopyTone("components/admin/control/AiPanel.tsx", 'const t = "AI 정지 중이다. 재개하면 다시 판단한다";');
  assert.equal(bad.length, 1);
  assert.equal(bad[0].rule, "admin-plain");
  assert.equal(bad[0].found, "중이다");
  const ok = checkCopyTone("components/admin/control/AiPanel.tsx", 'const t = "AI 정지 중입니다. 재개하면 다시 판단합니다";');
  assert.deepEqual(ok, []);
  const label = checkCopyTone("app/admin/(ops)/page.tsx", "const x = <span>승인 필요</span>;");
  assert.deepEqual(label, []);
});

test("관리 콘솔 밖은 종전처럼 존댓말을 잡고 명사형을 통과시킨다", () => {
  const bad = checkCopyTone("components/trade/OrderPanel.tsx", "toast(`주문 가능 잔고가 부족합니다`);");
  assert.equal(bad.length, 1);
  assert.equal(bad[0].rule, "honorific");
  const polite = checkCopyTone("app/page.tsx", "const x = <p>다시 시도해 주세요</p>;");
  assert.equal(polite.length, 1);
  const ok = checkCopyTone("components/trade/OrderPanel.tsx", "toast(`주문 접수 완료`);");
  assert.deepEqual(ok, []);
  /* 거래 화면의 반말은 이 규칙의 대상이 아니다. 해설 문단은 한다체를 쓴다(§10-1) */
  const plain = checkCopyTone("components/trade/OrderPanel.tsx", 'const x = "시장가는 즉시 체결된다";');
  assert.deepEqual(plain, []);
});
