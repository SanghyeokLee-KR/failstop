/**
 * tests/reducedMotion.test.ts. 전역 실패 통지 보조 함수 계약.
 * 통지 문구 선택과 억제 판정만 고정한다. 이벤트 배선은 DOM 의존이라 여기서 다루지 않는다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { isBackpressure, messageOf, shouldNotify } from "../components/globalErrorNotice";
import { ApiError } from "../lib/api/httpDatasource";

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

/* 백프레셔는 전역 오류 통지 대상이 아니다.
   429는 서버가 설계대로 수용 상한을 건 결과이고 회복 시각까지 함께 준다.
   정본이 "조회 429를 종결 실패로 적지 않는다"로 못박는다(API 설계 2-3).
   이 판정이 없으면 자체 catch 를 빠져나간 429 거부가 붉은 오류 토스트가 되어,
   설계된 백프레셔가 사용자에게 고장으로 보인다. 그 오역이 이 저장소가 없애려는 것이다. */
test("429는 전역 통지 대상이 아니다", () => {
  assert.equal(isBackpressure({ status: 429, message: "노드 처리 용량 초과" }), true);
  assert.equal(isBackpressure(new ApiError(429, "too_many_requests", "요청 몰림", 3)), true);
});

test("429가 아닌 실패는 통지 대상이다", () => {
  assert.equal(isBackpressure(new ApiError(500, "internal", "서버 오류")), false);
  assert.equal(isBackpressure(new ApiError(401, "unauthorized", "인증 필요")), false);
  assert.equal(isBackpressure(new Error("네트워크 끊김")), false);
});

/* 비정형 사유에서 터지면 보고기 자체가 죽어 어떤 실패도 통지되지 않는다 */
test("비정형 사유에도 판정이 터지지 않는다", () => {
  for (const r of [null, undefined, "", 0, "429", { status: "429" }, []]) {
    assert.equal(isBackpressure(r), false, String(r));
  }
});
