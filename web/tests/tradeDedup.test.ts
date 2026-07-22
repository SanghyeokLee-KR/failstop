/**
 * tests/tradeDedup.test.ts. 캔들 합성 체결 중복 판정 검증.
 * 재연결·재동기화 재전달이 같은 체결의 volume을 이중계상하지 않는지 확인한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { newTradeCursor, isDuplicateTrade, advanceTradeCursor } from "../components/chart/tradeDedup";

test("초기 커서는 어떤 체결도 중복 아님", () => {
  const c = newTradeCursor();
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 100 }), false);
});

test("같은 epoch: 되돌이·중복 seq는 중복, 증가 seq는 신규", () => {
  const c = newTradeCursor();
  advanceTradeCursor(c, { epoch: 1, seq: 100 });
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 100 }), true); // 중복
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 99 }), true); // 되돌이
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 101 }), false); // 신규
});

test("epoch 상승은 낮은 seq여도 통과, 이전 epoch는 드롭", () => {
  const c = newTradeCursor();
  advanceTradeCursor(c, { epoch: 1, seq: 500 });
  assert.equal(isDuplicateTrade(c, { epoch: 2, seq: 1 }), false); // 새 리더, seq 리셋
  assert.equal(isDuplicateTrade(c, { epoch: 0, seq: 600 }), true); // 이전 리더 재전달
});

test("재연결 재전달(같은 epoch, 낮은 seq) 무시로 volume 이중계상 방지", () => {
  const c = newTradeCursor();
  // 실시간 100·101 반영
  for (const seq of [100, 101]) {
    assert.equal(isDuplicateTrade(c, { epoch: 1, seq }), false);
    advanceTradeCursor(c, { epoch: 1, seq });
  }
  // 재연결 후 REST 재전달: 100·101이 다시 흘러온다 → 전부 중복으로 걸러야 한다
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 100 }), true);
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 101 }), true);
  // 이어지는 신규 102는 반영
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 102 }), false);
});
