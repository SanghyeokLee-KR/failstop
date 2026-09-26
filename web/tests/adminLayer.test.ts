/**
 * 관리 콘솔 층(모달·드로어·확인 대화상자)의 포커스 규칙과 상태 전이 모션 계산(디자인 시스템 §12-4·§12-13).
 * 층 쌓임과 Esc·Tab의 주인, Tab 순환, 배경 격리와 되돌림, 닫힌 뒤 포커스 복귀 자리, 움직임 줄이기 분기,
 * FLIP 되돌림 폭, 숫자 플래시 색을 DOM 없이 가짜 요소로 본다.
 */

import { afterEach, test } from "node:test";
import assert from "node:assert/strict";
import { isTopLayer, isolate, pushLayer, resetLayers, returnTarget, wrapFocus, type InertTarget } from "../components/admin/layer/focusTrap";
import { LAYER_EXIT_MS, STEP_GAP_MS, exitDelay, flashTone, flipDeltas, stepDelays, type Point } from "../lib/admin/motion";
import { stepKey } from "../lib/admin/thresholdKeys";

afterEach(() => resetLayers());

test("층은 쌓인 순서대로 맨 위 층만 Esc와 Tab을 받고, 가운데 층이 먼저 닫혀도 제 자리만 걷는다", () => {
  const modal = {};
  const confirm = {};
  const popModal = pushLayer(modal);
  assert.equal(isTopLayer(modal), true);
  const popConfirm = pushLayer(confirm);
  assert.equal(isTopLayer(modal), false, "확인 대화상자가 뜨면 아래 모달은 Esc를 받지 않는다");
  assert.equal(isTopLayer(confirm), true);
  popModal();
  assert.equal(isTopLayer(confirm), true, "아래 층이 먼저 닫혀도 위 층은 그대로 맨 위다");
  popConfirm();
  assert.equal(isTopLayer(confirm), false);
  assert.equal(isTopLayer(modal), false);
});

test("Tab 순환은 끝에서 처음으로, Shift+Tab은 처음에서 끝으로 돌고 안쪽에서는 브라우저에 맡긴다", () => {
  const items = ["취소", "승인하고 실행", "닫기"];
  assert.equal(wrapFocus(items, "닫기", false), "취소");
  assert.equal(wrapFocus(items, "취소", true), "닫기");
  assert.equal(wrapFocus(items, "취소", false), null);
  assert.equal(wrapFocus(items, "승인하고 실행", true), null);
  assert.equal(wrapFocus(items, null, false), "취소", "층 밖에 있던 포커스는 방향에 맞는 끝으로 들인다");
  assert.equal(wrapFocus(items, "바깥 단추", true), "닫기");
  assert.equal(wrapFocus([], "취소", false), null, "누를 것이 없으면 호출자가 층 자체에 둔다");
});

class FakeEl implements InertTarget {
  attrs = new Map<string, string>();
  constructor(readonly name: string, inert = false) {
    if (inert) this.attrs.set("inert", "");
  }
  hasAttribute(n: string) {
    return this.attrs.has(n);
  }
  setAttribute(n: string, v: string) {
    this.attrs.set(n, v);
  }
  removeAttribute(n: string) {
    this.attrs.delete(n);
  }
}

test("배경 격리는 층 밖 형제만 inert로 만들고, 아래 층이 걸어 둔 격리는 위 층이 닫혀도 풀지 않는다", () => {
  const shell = new FakeEl("shell");
  const chat = new FakeEl("chat");
  const lower = new FakeEl("lower-layer");
  const upper = new FakeEl("upper-layer");
  // 아래 층(모달)이 먼저 셸과 상담 패널을 격리한다
  const releaseLower = isolate([shell, chat, lower], (el) => el === lower);
  assert.equal(shell.hasAttribute("inert"), true);
  assert.equal(chat.hasAttribute("inert"), true);
  assert.equal(lower.hasAttribute("inert"), false);
  // 위 층(확인 대화상자)은 아래 층까지 격리한다
  const releaseUpper = isolate([shell, chat, lower, upper], (el) => el === upper);
  assert.equal(lower.hasAttribute("inert"), true);
  releaseUpper();
  assert.equal(lower.hasAttribute("inert"), false, "위 층이 닫히면 아래 층이 다시 조작된다");
  assert.equal(shell.hasAttribute("inert"), true, "아래 층의 격리는 그대로다");
  releaseLower();
  assert.equal(shell.hasAttribute("inert"), false);
  assert.equal(chat.hasAttribute("inert"), false);
});

test("닫힌 뒤 포커스는 연 단추로 돌아가고, 판이 바뀌어 단추가 사라졌으면 판의 제목으로 간다", () => {
  const focused: string[] = [];
  const el = (name: string, isConnected = true) => ({ name, isConnected, focus: () => focused.push(name) });
  const opener = el("app-1 카드");
  const heading = el("관제센터 제목");
  assert.equal(returnTarget(opener, heading), opener);
  assert.equal(returnTarget(el("사라진 카드", false), heading), heading);
  assert.equal(returnTarget(null, heading), heading);
  assert.equal(returnTarget(null, null), null);
});

test("움직임 줄이기에서는 층이 곧바로 닫히고 판단 과정 단계가 한 번에 켜진다", () => {
  assert.equal(exitDelay(false), LAYER_EXIT_MS);
  assert.equal(exitDelay(true), 0);
  assert.deepEqual(stepDelays(5, true, false), [0, STEP_GAP_MS, STEP_GAP_MS * 2, STEP_GAP_MS * 3, STEP_GAP_MS * 4]);
  assert.deepEqual(stepDelays(5, true, true), [0, 0, 0, 0, 0], "움직임 줄이기");
  assert.deepEqual(stepDelays(5, false, false), [0, 0, 0, 0, 0], "이미 본 판단을 다시 열면 기다리지 않는다");
});

test("FLIP 되돌림 폭은 자리를 옮긴 카드에만 있고 새 카드·사라진 카드·1px 미만 흔들림은 없다", () => {
  const before = new Map<string, Point>([
    ["alert-a", { x: 0, y: 0 }],
    ["alert-b", { x: 0, y: 48 }],
    ["gone", { x: 0, y: 96 }],
    ["still", { x: 0, y: 144.4 }],
  ]);
  const after = new Map<string, Point>([
    ["new", { x: 0, y: 0 }],
    ["alert-a", { x: 0, y: 48 }],
    ["alert-b", { x: 0, y: 96 }],
    ["still", { x: 0, y: 144 }],
  ]);
  const d = flipDeltas(before, after);
  assert.deepEqual([...d.keys()].sort(), ["alert-a", "alert-b"]);
  assert.deepEqual(d.get("alert-a"), { x: 0, y: -48 }, "새 자리에서 이전 자리만큼 되돌려 놓는다");
  assert.equal(d.has("new"), false);
  assert.equal(d.has("gone"), false);
  assert.equal(d.has("still"), false);
});

test("많을수록 나쁜 수는 늘면 주의, 0이 되면 정상으로 번쩍이고 줄었지만 남으면 번쩍이지 않는다", () => {
  assert.equal(flashTone(0, 1), "warning");
  assert.equal(flashTone(2, 0), "success");
  assert.equal(flashTone(3, 2), null);
  assert.equal(flashTone(2, 2), null);
});

test("임계값 편집 칸의 PageUp·PageDown은 열 눈금씩 허용 범위 안에서 움직이고 다른 키는 브라우저에 맡긴다", () => {
  const cpu = { min: 40, max: 90, step: 1 };
  assert.equal(stepKey("PageUp", 60, cpu), 70);
  assert.equal(stepKey("PageDown", 45, cpu), 40, "아래 끝에서 멈춘다");
  assert.equal(stepKey("PageUp", 85, cpu), 90, "위 끝에서 멈춘다");
  assert.equal(stepKey("ArrowUp", 60, cpu), null, "한 눈금은 숫자 칸이 스스로 움직인다");
  const lag = { min: 0.5, max: 30, step: 0.5 };
  assert.equal(stepKey("PageUp", 1.5, lag), 6.5, "소수 눈금의 꼬리를 남기지 않는다");
  assert.equal(stepKey("PageDown", Number.NaN, lag), 0.5, "비운 칸은 아래 끝에서 센다");
});
