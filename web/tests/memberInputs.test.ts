/**
 * 가입 입력 검증 미러 검증. 두 구현이 갈리면 "폼은 통과하는데 서버가 거절"이 된다.
 * 입력 표는 서버 MemberInputsTest.java와 같다. 통과·거절만 맞추고 문구는 맞추지 않는다
 * (화면 어휘는 명사형, 서버 메시지는 평서문으로 대상이 다르다).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  displayNameError,
  emailError,
  normalizeDisplayName,
  passwordError,
  MAX_DISPLAY_NAME,
} from "../lib/auth/memberInputs";

/** 제어문자는 코드포인트로 만든다. 소스에 리터럴로 넣으면 편집기·diff마다 다르게 보인다 */
const ch = (code: number) => String.fromCharCode(code);

test("정상 이메일은 통과한다", () => {
  for (const ok of [
    "trader@demo.local",
    "  trader@demo.local  ",
    "a.b+tag_1%x-y@sub.demo.co.kr",
    "a".repeat(64) + "@demo.local",
    "a@" + "b".repeat(63) + ".local",
  ]) {
    assert.equal(emailError(ok), null, `통과해야 함: ${ok}`);
  }
});

test("이전 서버 구현이 통과시키던 형태를 프론트도 막는다", () => {
  // 서버 MemberInputsTest.regressionHolesOfPreviousCheckAreClosed와 같은 입력
  for (const bad of ["a..b@demo.local", ".a@demo.local", "a@-demo.local", "a@demo..local"]) {
    assert.notEqual(emailError(bad), null, `막아야 함: ${bad}`);
  }
});

test("구조가 어긋난 이메일을 막는다", () => {
  // 서버 MemberInputsTest.malformedEmailsRejected와 같은 목록
  for (const bad of [
    "", "   ", "trader", "trader@", "@demo.local", "a@b@demo.local",
    "a.@demo.local", "a@demo.local.", "a@.demo.local", "a@demo-.local",
    "a@localhost", "a@demo.l", "a@demo.123", "a@demo.local-",
    "a b@demo.local", "a@demo local", "a<b>@demo.local", 'a"b@demo.local',
    "a".repeat(65) + "@demo.local",
    "a@" + "b".repeat(64) + ".local",
    "a".repeat(300) + "@demo.local",
  ]) {
    assert.notEqual(emailError(bad), null, `막아야 함: [${bad}]`);
  }
});

test("안쪽 공백류와 제어문자를 막는다", () => {
  assert.notEqual(emailError("a" + ch(0x00a0) + "b@demo.local"), null);
  assert.notEqual(emailError("a" + ch(0x0000) + "b@demo.local"), null);
  assert.notEqual(emailError("a@demo" + ch(0x3000) + ".local"), null);
  assert.notEqual(emailError("a@demo.local" + ch(0x3000)), null);
});

test("끝에 붙은 제어문자는 잘라내고 통과시킨다", () => {
  // 서버 trim()과 같은 범위(U+0020 이하). 붙여넣기 찌꺼기라 거절보다 제거가 맞다
  assert.equal(emailError("a@demo.local" + ch(0x0000)), null);
  assert.equal(emailError("a@demo.local" + ch(0x000d)), null);
  assert.equal(emailError(ch(0x0009) + "a@demo.local"), null);
});

test("표시명 공백을 접고 양끝을 자른다", () => {
  assert.equal(normalizeDisplayName("  데모   트레이더  "), "데모 트레이더");
  assert.equal(normalizeDisplayName("A" + ch(0x00a0) + "B"), "A B");
  // U+00A0·U+3000만으로 이뤄진 이름은 빈 이름이다
  assert.notEqual(displayNameError(ch(0x00a0) + ch(0x3000)), null);
});

test("표시명 개행은 공백으로 접는다", () => {
  assert.equal(normalizeDisplayName("데모\n트레이더"), "데모 트레이더");
  assert.equal(displayNameError("데모\n트레이더"), null);
});

test("표시명의 보이지 않는 문자를 막는다", () => {
  // 제로폭이면 두 계정이 화면상 같은 이름을 갖고, 양방향 제어면 이름이 거꾸로 읽힌다
  assert.notEqual(displayNameError("관리자" + ch(0x200b)), null);
  assert.notEqual(displayNameError("관" + ch(0xfeff) + "리자"), null);
  assert.notEqual(displayNameError("admin" + ch(0x202e)), null);
  assert.notEqual(displayNameError(ch(0x2066) + "user"), null);
});

test("U+FEFF는 공백이 아니라 거절 대상이다", () => {
  // ECMAScript의 WhiteSpace에는 U+FEFF가 들어 있어 `\s`로 접으면 공백이 된다. Java에는
  // 없어서 서버는 보이지 않는 문자로 거절한다. `\s`를 그대로 썼을 때 프론트만 통과하고
  // 서버가 막는 조합이 실제로 나왔다. 이 케이스가 그 회귀를 잡는다
  assert.notEqual(displayNameError("관" + ch(0xfeff) + "리자"), null);
  assert.equal(normalizeDisplayName("관" + ch(0xfeff) + "리자").length, 4, "공백으로 접히면 안 된다");
});

test("일반 문자를 공백으로 오판하지 않는다", () => {
  // 유니코드 속성 이스케이프의 백슬래시가 빠지면 문자클래스가 p·Z·s·l 같은 글자를
  // 공백으로 친다. 그러면 표시명에서 그 글자만 사라진다
  assert.equal(normalizeDisplayName("pZsl"), "pZsl");
  assert.equal(normalizeDisplayName("Zeppelin"), "Zeppelin");
  assert.equal(displayNameError("pZsl"), null);
});

test("표시명 길이는 코드포인트로 센다", () => {
  assert.equal(displayNameError("가".repeat(MAX_DISPLAY_NAME)), null);
  assert.notEqual(displayNameError("가".repeat(MAX_DISPLAY_NAME + 1)), null);
  // 보조평면 1자는 UTF-16 2단위다. length로 세면 32자가 64로 잡혀 거절된다
  const emoji = String.fromCodePoint(0x1f600).repeat(MAX_DISPLAY_NAME);
  assert.equal(emoji.length, MAX_DISPLAY_NAME * 2);
  assert.equal(displayNameError(emoji), null);
});

test("표시명 NFC로 합성한다", () => {
  // 조합형(초성 U+1100 + 중성 U+1161)이 완성형과 다른 문자열로 남으면 화면에서만 같아 보인다
  const decomposed = ch(0x1100) + ch(0x1161);
  assert.equal(normalizeDisplayName(decomposed), "가");
});

test("비밀번호는 길이만 본다", () => {
  assert.notEqual(passwordError(""), null);
  assert.notEqual(passwordError("short7c"), null);
  assert.equal(passwordError("demopass1"), null);
  // 복잡도 규칙은 정본이 배제했다(회원_설계 §1-1). 소문자만 8자도 통과해야 한다
  assert.equal(passwordError("abcdefgh"), null);
  assert.equal(passwordError("a".repeat(128)), null);
  assert.notEqual(passwordError("a".repeat(129)), null);
});
