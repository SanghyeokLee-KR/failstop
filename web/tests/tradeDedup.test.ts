/**
 * tests/tradeDedup.test.ts. 캔들 합성 체결 중복 판정 검증.
 * 재연결·재동기화 재전달이 같은 체결의 volume을 이중계상하지 않는지 확인한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { newTradeCursor, isDuplicateTrade, advanceTradeCursor } from "../components/chart/tradeDedup";
import { TRADE_TAPE_LIMIT } from "../lib/types";

/** 커서(2차 방어) 단독 검증용. id를 매번 새로 만들어 trade_id 1차 방어를 우회한다.
 *  Set 상한을 넘어 id가 밀려난 상태와 같은 조건이다 */
let idSeq = 0;
const freshId = () => `fresh-${++idSeq}`;

test("초기 커서는 어떤 체결도 중복 아님", () => {
  const c = newTradeCursor();
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 100, trade_id: freshId() }), false);
});

test("같은 epoch: 되돌이·중복 seq는 중복, 증가 seq는 신규", () => {
  const c = newTradeCursor();
  advanceTradeCursor(c, { epoch: 1, seq: 100, trade_id: freshId() });
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 100, trade_id: freshId() }), true); // 중복
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 99, trade_id: freshId() }), true); // 되돌이
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 101, trade_id: freshId() }), false); // 신규
});

test("epoch 상승은 낮은 seq여도 통과, 이전 epoch는 드롭", () => {
  const c = newTradeCursor();
  advanceTradeCursor(c, { epoch: 1, seq: 500, trade_id: freshId() });
  assert.equal(isDuplicateTrade(c, { epoch: 2, seq: 1, trade_id: freshId() }), false); // 새 리더, seq 리셋
  assert.equal(isDuplicateTrade(c, { epoch: 0, seq: 600, trade_id: freshId() }), true); // 이전 리더 재전달
});

test("새 epoch로 재전달된 기수신 체결은 trade_id로 걸러진다", () => {
  // 어댑터는 재전달 메시지에 채널 전역 lastEpoch를 찍는다. ticker가 먼저 새 세대를 알린 뒤
  // trade 기준점이 비워지면 기수신 체결이 새 epoch를 달고 돌아와 epoch 커서를 통과한다.
  // 커서만 있던 시절에는 이 재전달이 현재 봉 volume을 이중계상시켰다
  const c = newTradeCursor();
  for (const seq of [100, 101]) {
    const msg = { epoch: 1, seq, trade_id: `t-${seq}` };
    assert.equal(isDuplicateTrade(c, msg), false);
    advanceTradeCursor(c, msg);
  }
  assert.equal(isDuplicateTrade(c, { epoch: 2, seq: 100, trade_id: "t-100" }), true);
  assert.equal(isDuplicateTrade(c, { epoch: 2, seq: 101, trade_id: "t-101" }), true);
  // 같은 세대의 진짜 신규는 통과한다
  assert.equal(isDuplicateTrade(c, { epoch: 2, seq: 102, trade_id: "t-102" }), false);
});

test("trade_id 기억은 상한을 넘으면 오래된 것부터 버린다", () => {
  const c = newTradeCursor();
  for (let i = 0; i < TRADE_TAPE_LIMIT * 2 + 5; i++) {
    advanceTradeCursor(c, { epoch: 1, seq: i + 1, trade_id: `t-${i}` });
  }
  assert.equal(c.seen.size, TRADE_TAPE_LIMIT * 2, "무한 증가하면 장시간 세션에서 누수가 된다");
  assert.equal(c.seen.has("t-0"), false);
  assert.equal(c.seen.has(`t-${TRADE_TAPE_LIMIT * 2 + 4}`), true);
});

test("재연결 재전달(같은 epoch, 낮은 seq)은 id가 밀려나도 커서가 막는다", () => {
  const c = newTradeCursor();
  // 실시간 100·101 반영
  for (const seq of [100, 101]) {
    const msg = { epoch: 1, seq, trade_id: freshId() };
    assert.equal(isDuplicateTrade(c, msg), false);
    advanceTradeCursor(c, msg);
  }
  // 재연결 후 REST 재전달. id가 Set에서 밀려난 상태를 새 id로 흉내낸다
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 100, trade_id: freshId() }), true);
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 101, trade_id: freshId() }), true);
  // 이어지는 신규 102는 반영
  assert.equal(isDuplicateTrade(c, { epoch: 1, seq: 102, trade_id: freshId() }), false);
});
