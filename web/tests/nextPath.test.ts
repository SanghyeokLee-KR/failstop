/**
 * 로그인 복귀 경로(?next=) 해석 검증.
 * 외부 입력을 router.push에 넘기는 흐름이라, 여기가 뚫리면 거래소 링크가 임의 사이트로 사람을 보낸다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { safeNextPath, loginHref, DEFAULT_NEXT } from "../lib/auth/nextPath";

/** 제어문자는 코드포인트로 만든다. 소스에 리터럴로 넣으면 편집기·diff마다 다르게 보인다 */
const ctrl = (code: number) => String.fromCharCode(code);

test("같은 출처 절대 경로는 통과한다", () => {
  assert.equal(safeNextPath("/exchange/KRW-BTC"), "/exchange/KRW-BTC");
  assert.equal(
    safeNextPath("/order/KRW-ETH?side=bid&price=84000000"),
    "/order/KRW-ETH?side=bid&price=84000000",
  );
  assert.equal(safeNextPath("/assets"), "/assets");
});

test("외부로 나가는 형태는 전부 기본 목적지로 떨어진다", () => {
  const hostile = [
    "//evil.example",
    "///evil.example",
    "https://evil.example",
    "http://evil.example",
    "//evil.example/exchange",
    "/" + ctrl(92) + "evil.example",
    "javascript:alert(1)",
    "exchange",
    "../admin",
  ];
  for (const value of hostile) {
    assert.equal(safeNextPath(value), DEFAULT_NEXT, `통과하면 안 됨: ${value}`);
  }
});

test("이중 인코딩으로 판정을 우회하지 못한다", () => {
  // %2F는 디코드하면 /가 된다. 디코드 전에는 "/%2F..."라 첫 글자 검사를 지나므로
  // 디코드 후 다시 보는 순서가 아니면 //evil로 나가는 값이 통과한다
  assert.equal(safeNextPath("/%2Fevil.example"), DEFAULT_NEXT);
  assert.equal(safeNextPath("%2F%2Fevil.example"), DEFAULT_NEXT);
});

test("깨진 퍼센트 인코딩은 예외 대신 기본 목적지다", () => {
  assert.equal(safeNextPath("/%E0%A4%A"), DEFAULT_NEXT);
  assert.equal(safeNextPath("%"), DEFAULT_NEXT);
});

test("제어문자가 섞이면 거부한다", () => {
  assert.equal(safeNextPath("/exchange" + ctrl(0) + "/evil"), DEFAULT_NEXT);
  assert.equal(
    safeNextPath("/exchange" + ctrl(13) + ctrl(10) + "Location: https://evil.example"),
    DEFAULT_NEXT,
  );
  assert.equal(safeNextPath("/exchange" + ctrl(127)), DEFAULT_NEXT);
  assert.equal(safeNextPath("/exchange" + ctrl(9) + "foo"), DEFAULT_NEXT);
});

test("공백은 제어문자가 아니라 통과한다", () => {
  // 0x20은 판정 밖이다. 여기서 막으면 공백이 든 정상 쿼리까지 기본 목적지로 떨어진다
  assert.equal(safeNextPath("/search?q=비트 코인"), "/search?q=비트 코인");
});

test("빈 값은 기본 목적지다", () => {
  assert.equal(safeNextPath(null), DEFAULT_NEXT);
  assert.equal(safeNextPath(undefined), DEFAULT_NEXT);
  assert.equal(safeNextPath(""), DEFAULT_NEXT);
});

test("loginHref는 기본 목적지일 때 쿼리를 붙이지 않는다", () => {
  // 어차피 기본값으로 갈 경로를 next로 실으면 URL만 길어진다
  assert.equal(loginHref(DEFAULT_NEXT), "/auth/login");
  assert.equal(loginHref("//evil.example"), "/auth/login");
});

test("loginHref는 복귀 경로를 인코딩해 싣고, 되돌리면 원래 경로다", () => {
  assert.equal(
    loginHref("/exchange/KRW-BTC?price=84000000"),
    "/auth/login?next=" + encodeURIComponent("/exchange/KRW-BTC?price=84000000"),
  );
  const href = loginHref("/order/KRW-ETH?side=ask");
  const back = new URLSearchParams(href.slice(href.indexOf("?") + 1)).get("next");
  assert.equal(safeNextPath(back), "/order/KRW-ETH?side=ask");
});
