/**
 * tests/fillNotice.test.ts. 체결 토스트 발화 계약 고정.
 * 회귀 방지 대상: 재연결 재동기화가 재전달한 종결 주문이 신규 체결 토스트로 오발하던 결함.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { isResyncReplay, shouldAnnounceFill } from "../components/trade/fillNotice";
import type { MyOrderMessage, OrderStatus } from "../lib/types";

function msg(id: string, status: OrderStatus, seq: number): Pick<MyOrderMessage, "id" | "status" | "seq"> {
  return { id, status, seq };
}

test("실시간 신규 체결은 토스트를 발화한다", () => {
  assert.equal(shouldAnnounceFill(msg("o1", "FILLED", 12), []), true);
});

test("재동기화 재전달(seq 0)은 발화하지 않는다", () => {
  // orderToMyOrderMessage 합성은 seq 0. 재연결마다 종결 주문 전량이 재전달된다
  assert.equal(shouldAnnounceFill(msg("o1", "FILLED", 0), []), false);
  assert.equal(isResyncReplay(msg("o1", "FILLED", 0)), true);
  assert.equal(isResyncReplay(msg("o1", "FILLED", 1)), false);
});

test("이미 종결로 알던 주문의 재통지는 발화하지 않는다", () => {
  assert.equal(shouldAnnounceFill(msg("o1", "FILLED", 12), [{ id: "o1" }]), false);
});

test("미체결·부분체결·취소는 발화하지 않는다", () => {
  for (const status of ["OPEN", "PARTIAL", "CANCELED", "REJECTED"] as OrderStatus[]) {
    assert.equal(shouldAnnounceFill(msg("o1", status, 12), []), false, status);
  }
});
