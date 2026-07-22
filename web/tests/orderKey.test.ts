/**
 * tests/orderKey.test.ts. 주문 멱등키 수명주기 계약 고정 (API §2-4, §7-6).
 * 회귀 방지 대상: 실패 후 재시도는 동일 키, 입력 변경은 새 키, 확정 성공 후 다음 주문은 새 키.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  initOrderKey,
  onInputChanged,
  onSubmitAccepted,
  onSubmitFailed,
  resolveKeyForSubmit,
} from "../components/trade/orderKey";

/** 결정론 키 생성기. 호출마다 k1, k2, ... */
function counterGen() {
  let n = 0;
  return () => `k${(n += 1)}`;
}

test("실패 후 동일 내용 재시도는 같은 키를 재사용한다", () => {
  const gen = counterGen();
  let s = initOrderKey(gen); // 초기 키 발급, dirty

  s = resolveKeyForSubmit(s, gen); // 첫 제출 키 확정
  const first = s.key;
  assert.equal(s.dirty, false);

  s = onSubmitFailed(s); // 5XX·네트워크 실패
  s = resolveKeyForSubmit(s, gen); // 입력 변경 없이 재시도
  assert.equal(s.key, first, "재시도는 동일 키여야 이중접수를 막는다");
});

test("입력 변경은 새 키를 발급한다", () => {
  const gen = counterGen();
  let s = initOrderKey(gen);

  s = resolveKeyForSubmit(s, gen);
  const first = s.key;

  s = onInputChanged(s); // 값 변경 → 신규 주문
  assert.equal(s.dirty, true);
  s = resolveKeyForSubmit(s, gen);
  assert.notEqual(s.key, first, "내용이 바뀌면 새 키여야 한다");
});

test("확정 성공 후 다음 주문은 새 키를 발급한다", () => {
  const gen = counterGen();
  let s = initOrderKey(gen);

  s = resolveKeyForSubmit(s, gen);
  const first = s.key;

  s = onSubmitAccepted(s); // 접수 확정
  assert.equal(s.dirty, true);
  s = resolveKeyForSubmit(s, gen);
  assert.notEqual(s.key, first, "성공한 주문의 키를 다음 주문이 재사용하면 안 된다");
});

test("초기 상태에서 첫 제출은 신규 키로 발급된다", () => {
  const gen = counterGen();
  const s0 = initOrderKey(gen);
  assert.equal(s0.dirty, true);
  const s1 = resolveKeyForSubmit(s0, gen);
  assert.notEqual(s1.key, s0.key);
  assert.equal(s1.dirty, false);
});

test("실패는 키·dirty를 보존한다(상태 항등)", () => {
  const gen = counterGen();
  let s = resolveKeyForSubmit(initOrderKey(gen), gen);
  const before = s;
  s = onSubmitFailed(s);
  assert.equal(s.key, before.key);
  assert.equal(s.dirty, before.dirty);
});

test("반복 입력 변경은 dirty 상태를 유지한다", () => {
  const gen = counterGen();
  let s = onInputChanged(resolveKeyForSubmit(initOrderKey(gen), gen));
  const afterFirst = s;
  s = onInputChanged(s); // 이미 dirty면 동일 참조 유지
  assert.equal(s, afterFirst, "이미 새 키 대기면 불필요한 상태 교체가 없어야 한다");
});

test("in-flight 입력 변경 동결 후 5XX 재시도는 동일 키를 재사용한다", () => {
  const gen = counterGen();
  let s = initOrderKey(gen);

  s = resolveKeyForSubmit(s, gen); // 제출 확정
  const inflight = s.key;
  assert.equal(s.dirty, false);

  // 제출 중 입력 변경이 들어와도 OrderPanel이 dirty 전이를 동결한다.
  // onInputChanged를 적용하지 않는 것이 그 동결의 계약이다(적용하면 dirty=true → 새 키 = 이중접수).
  s = onSubmitFailed(s); // 5XX. 상태 UNKNOWN, 키 보존
  s = resolveKeyForSubmit(s, gen); // 동일 내용 재전송
  assert.equal(s.key, inflight, "in-flight 편집을 동결했으니 재시도는 확정 키를 그대로 써야 한다");
});
