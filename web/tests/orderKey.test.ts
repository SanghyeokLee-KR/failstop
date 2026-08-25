/**
 * tests/orderKey.test.ts. 주문 멱등키 수명주기 계약 고정 (API §2-4, §7-6).
 * 회귀 방지 대상: 실패 후 재시도는 동일 키, 입력 변경은 새 키, 확정 성공 후 다음 주문은 새 키.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  initOrderKey,
  isFinalRejectStatus,
  onInputChanged,
  onSubmitAccepted,
  onSubmitFailed,
  resolveKeyForSubmit,
  shouldApplyInputChange,
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

test("종결성 거부(422) 후 동일 내용 재제출은 새 키를 쓴다(REJECTED replay 오인 방지)", () => {
  const gen = counterGen();
  let s = resolveKeyForSubmit(initOrderKey(gen), gen);
  const first = s.key;

  // OrderPanel catch: 4xx 종결 거부는 onInputChanged로 dirty를 세운다.
  // 같은 키 재시도는 서버가 기존 REJECTED 주문의 멱등 replay(200 + X-Idempotent-Replay)로
  // 받아 거짓 성공이 되므로, 새 키 발급이 계약이다(§2-4).
  assert.equal(isFinalRejectStatus(422, "order_rejected"), true);
  s = onInputChanged(s);
  s = resolveKeyForSubmit(s, gen);
  assert.notEqual(s.key, first, "종결 거부 후 재제출은 새 키여야 한다");
});

test("isFinalRejectStatus: 4xx 종결만 참, UNKNOWN·레이트리밋은 거짓(키 보존)", () => {
  assert.equal(isFinalRejectStatus(400, "invalid_parameter"), true);
  assert.equal(isFinalRejectStatus(401, "jwt_verification"), true);
  assert.equal(isFinalRejectStatus(422, "insufficient_balance"), true);
  assert.equal(isFinalRejectStatus(429, "too_many_requests"), false, "레이트리밋은 키 보존");
  assert.equal(isFinalRejectStatus(500, "server_error"), false);
  assert.equal(isFinalRejectStatus(503, "server_error"), false);
  assert.equal(isFinalRejectStatus(0, "network_error"), false, "네트워크 실패(status 0)는 UNKNOWN, 키 보존");
});

test("409는 코드로 갈린다: 중복키는 종결, 진행 중·판정 보류는 키 보존", () => {
  // 같은 키가 다른 내용으로 소모된 확정 응답. 같은 키 재시도는 영구 409라 새 키가 필요하다
  assert.equal(isFinalRejectStatus(409, "duplicate_client_order_id"), true);
  // 결과 미확정. 새 키를 발급하면 원본이 뒤늦게 접수될 때 이중접수가 된다
  assert.equal(isFinalRejectStatus(409, "order_in_progress"), false);
  assert.equal(isFinalRejectStatus(409, "requery_deferred"), false);
  assert.equal(isFinalRejectStatus(409), false, "코드 미상 409는 보존 쪽으로 판단");
});

test("중복키 409 후 재제출은 새 키를 쓴다(영구 409 고착 방지)", () => {
  const gen = counterGen();
  let s = resolveKeyForSubmit(initOrderKey(gen), gen);
  const first = s.key;
  // OrderPanel catch가 종결로 판정하면 onInputChanged로 dirty를 세운다
  assert.equal(isFinalRejectStatus(409, "duplicate_client_order_id"), true);
  s = onInputChanged(s);
  s = resolveKeyForSubmit(s, gen);
  assert.notEqual(s.key, first);
});

test("진행 중 409 후 재제출은 같은 키를 재사용한다(이중접수 방지)", () => {
  const gen = counterGen();
  let s = resolveKeyForSubmit(initOrderKey(gen), gen);
  const first = s.key;
  assert.equal(isFinalRejectStatus(409, "order_in_progress"), false);
  s = onSubmitFailed(s);
  s = resolveKeyForSubmit(s, gen);
  assert.equal(s.key, first);
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

/* 아래 4건은 in-flight 입력 변경 게이트(shouldApplyInputChange) 계약이다.
   동결만 하고 끝내면 변경이 소실되고, 제출 종료만으로 반영하면 5XX 재시도가 새 키를 얻는다.
   두 실패 모드를 동시에 막는 것이 이 게이트의 존재 이유다. */

test("제출 중에는 입력 변경을 dirty에 반영하지 않는다", () => {
  assert.equal(shouldApplyInputChange("bid|limit|100|1||KRW-BTC", "bid|limit|90|1||KRW-BTC", true), false);
});

test("제출 종료로 재평가돼도 서명이 같으면 반영하지 않는다", () => {
  const sig = "bid|limit|100|1||KRW-BTC";
  assert.equal(
    shouldApplyInputChange(sig, sig, false),
    false,
    "입력이 그대로인 5XX 재시도에 새 키를 발급하면 이중접수다",
  );
});

test("서명이 달라지면 반영한다", () => {
  assert.equal(shouldApplyInputChange("bid|limit|200|1||KRW-BTC", "bid|limit|100|1||KRW-BTC", false), true);
});

test("in-flight 중 바뀐 입력은 제출 종료 후 정확히 한 번 반영된다", () => {
  const before = "bid|limit|100|1||KRW-BTC";
  const after = "bid|limit|200|1||KRW-BTC";
  let lastSig: string | null = before;

  // 제출 중 편집: 보류하고 서명도 갱신하지 않는다
  assert.equal(shouldApplyInputChange(after, lastSig, true), false);
  assert.equal(lastSig, before, "동결 구간에서 서명을 갱신하면 변경이 소실된다");

  // 제출 종료: 서명이 달라 반영된다
  assert.equal(shouldApplyInputChange(after, lastSig, false), true);
  lastSig = after;

  // 반영 뒤 재평가는 항등
  assert.equal(shouldApplyInputChange(after, lastSig, false), false);
});
