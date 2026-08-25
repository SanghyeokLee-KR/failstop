/**
 * tests/reducedMotion.test.ts. 전역 실패 통지 보조 함수 계약.
 * 통지 문구 선택과 억제 판정만 고정한다. 이벤트 배선은 DOM 의존이라 여기서 다루지 않는다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { messageOf, shouldNotify } from "../components/globalErrorNotice";

test("Error 사유는 메시지를 그대로 쓴다", () => {
  assert.equal(messageOf(new Error("노드 처리 용량을 초과했습니다")), "노드 처리 용량을 초과했습니다");
});

test("빈 메시지·비정형 사유는 기본 문구로 수렴한다", () => {
  const fallback = messageOf(null);
  assert.equal(messageOf(new Error("   ")), fallback);
  assert.equal(messageOf({ code: 500 }), fallback);
  assert.equal(messageOf(""), fallback);
});

test("문자열 사유는 그대로 쓴다", () => {
  assert.equal(messageOf("네트워크 연결이 끊겼어요"), "네트워크 연결이 끊겼어요");
});

test("같은 메시지는 억제 창 안에서 한 번만 통지한다", () => {
  assert.equal(shouldNotify("동일", "동일", 100), false, "재시도 루프가 토스트를 도배하면 안 된다");
  assert.equal(shouldNotify("동일", "동일", 6000), true, "창을 넘기면 다시 통지한다");
  assert.equal(shouldNotify("다름", "동일", 100), true, "다른 실패는 즉시 통지한다");
  assert.equal(shouldNotify("최초", null, 0), true);
});
