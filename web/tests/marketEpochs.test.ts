/**
 * tests/marketEpochs.test.ts. 마켓별 epoch 표(ADR 0034 S6, 설계 4-13) 순수 규칙 검증.
 *
 * epoch 는 DB 타임라인 하나 안에서 배정 순서대로 커지므로 같은 마켓에서는 큰 epoch 가 뒤의 리더 기간이다.
 * 다른 마켓끼리는 파티션이 다를 수 있어 비교하지 않는다. 시계를 주입해 10초 감시를 고정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { MarketEpochs, STALE_EPOCH_RESET_MS } from "../lib/api/marketEpochs";

function clock(start = 0) {
  const c = { now: start };
  return { c, read: () => c.now };
}

test("처음 본 마켓은 기록만 하고 같은 epoch 는 통과한다", () => {
  const e = new MarketEpochs(() => 0);
  assert.equal(e.current("KRW-BTC"), undefined);
  assert.equal(e.observe("KRW-BTC", 5), "first");
  assert.equal(e.observe("KRW-BTC", 5), "same");
  assert.equal(e.current("KRW-BTC"), 5);
});

test("두 마켓의 서로 다른 epoch 가 번갈아 와도 재동기화 신호가 없다", () => {
  const e = new MarketEpochs(() => 0);
  const verdicts: string[] = [];
  for (let i = 0; i < 3; i++) {
    verdicts.push(e.observe("KRW-BTC", 5), e.observe("KRW-ETH", 9));
  }
  assert.deepEqual(verdicts, ["first", "first", "same", "same", "same", "same"]);
});

test("작은 epoch 는 버림 신호, 큰 epoch 는 그 마켓만 재동기화 신호다", () => {
  const e = new MarketEpochs(() => 0);
  e.observe("KRW-BTC", 5);
  e.observe("KRW-ETH", 9);
  assert.equal(e.observe("KRW-BTC", 4), "stale");
  assert.equal(e.current("KRW-BTC"), 5, "작은 epoch 는 기준을 바꾸지 않는다");
  assert.equal(e.observe("KRW-BTC", 6), "rise");
  assert.equal(e.current("KRW-BTC"), 6);
  assert.equal(e.current("KRW-ETH"), 9, "다른 마켓의 기준은 그대로다");
  assert.equal(e.observe("KRW-ETH", 9), "same");
});

test("작은 epoch 만 10초 넘게 이어지면 그 마켓 기준을 지우고 재동기화한다", () => {
  const { c, read } = clock();
  const e = new MarketEpochs(read);
  e.observe("KRW-BTC", 5);
  e.observe("KRW-ETH", 9);
  assert.equal(e.observe("KRW-BTC", 4), "stale");
  c.now = 5_000;
  assert.equal(e.observe("KRW-BTC", 4), "stale");
  c.now = STALE_EPOCH_RESET_MS;
  assert.equal(e.observe("KRW-BTC", 4), "stale", "10초 째는 아직 넘지 않았다");
  c.now = STALE_EPOCH_RESET_MS + 1;
  assert.equal(e.observe("KRW-BTC", 4), "reset", "DB 타임라인이 바뀐 것으로 보고 기준을 다시 잡는다");
  assert.equal(e.current("KRW-BTC"), 4);
  assert.equal(e.observe("KRW-BTC", 4), "same");
  assert.equal(e.current("KRW-ETH"), 9, "감시는 마켓마다다");
});

test("그 사이 그 마켓의 메시지를 받았으면 10초 감시를 다시 센다", () => {
  const { c, read } = clock();
  const e = new MarketEpochs(read);
  e.observe("KRW-BTC", 5);
  assert.equal(e.observe("KRW-BTC", 4), "stale");
  c.now = 5_000;
  assert.equal(e.observe("KRW-BTC", 5), "same", "현재 기간 메시지가 왔다. 옛 메시지는 늦게 온 것일 뿐이다");
  c.now = 12_000;
  assert.equal(e.observe("KRW-BTC", 4), "stale", "감시는 이 시점부터 다시 센다");
  c.now = 12_000 + STALE_EPOCH_RESET_MS;
  assert.equal(e.observe("KRW-BTC", 4), "stale");
  c.now = 12_000 + STALE_EPOCH_RESET_MS + 1;
  assert.equal(e.observe("KRW-BTC", 4), "reset");
});

test("REST 스냅샷은 작으면 버리고 크면 기준을 올린다. 감시를 시작하지 않는다", () => {
  const { c, read } = clock();
  const e = new MarketEpochs(read);
  assert.equal(e.snapshot("KRW-BTC", 5), "first", "처음 본 마켓의 스냅샷도 기준이 된다");
  assert.equal(e.snapshot("KRW-BTC", 5), "same");
  assert.equal(e.snapshot("KRW-BTC", 4), "stale");
  assert.equal(e.current("KRW-BTC"), 5);
  c.now = 60_000;
  assert.equal(e.snapshot("KRW-BTC", 4), "stale", "스냅샷은 감시로 기준을 지우지 않는다");
  assert.equal(e.observe("KRW-BTC", 4), "stale", "실시간 감시는 첫 실시간부터 센다");
  assert.equal(e.snapshot("KRW-BTC", 7), "rise");
  assert.equal(e.current("KRW-BTC"), 7);
  assert.equal(e.observe("KRW-BTC", 7), "same");
});

test("같은 epoch 의 스냅샷은 진행 중인 감시를 끊는다", () => {
  const { c, read } = clock();
  const e = new MarketEpochs(read);
  e.observe("KRW-BTC", 5);
  e.observe("KRW-BTC", 4);
  c.now = 8_000;
  assert.equal(e.snapshot("KRW-BTC", 5), "same", "서버가 아직 기준 기간을 낸다");
  c.now = 11_000;
  assert.equal(e.observe("KRW-BTC", 4), "stale", "감시가 다시 시작됐다");
});
