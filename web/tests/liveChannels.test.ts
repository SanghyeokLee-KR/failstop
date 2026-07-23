/**
 * tests/liveChannels.test.ts. 구독 diff 계산과 transient 스토어 관찰 경로 검증.
 * 회귀 방지 대상:
 * - useLiveChannels가 destinations 전이에서 유지 채널을 철거·재구독하지 않는다(diffDestinations).
 * - useTradeRateGovernor 표본 수집이 셀렉터 리렌더 없이 zustand subscribe로 동작한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { diffDestinations, useExchangeStore } from "../lib/store";
import { dest } from "../lib/types";
import type { TradeMessage } from "../lib/types";

function tradeMsg(id: string, market = "KRW-BTC", seq = 0): TradeMessage {
  return {
    schema_version: "1.0",
    type: "trade",
    market,
    epoch: 1,
    seq,
    timestamp: "2026-07-21T09:00:01.000Z",
    trade_id: id,
    price: "50000000",
    volume: "0.1",
    taker_side: "bid",
  };
}

test("diffDestinations: 유지 채널은 계산에서 제외, 추가·제거만 산출", () => {
  const r = diffDestinations(["a", "b", "c"], ["b", "c", "d"]);
  assert.deepEqual(r.added, ["d"]);
  assert.deepEqual(r.removed, ["a"]);
});

test("diffDestinations: 동일 집합은 no-op(전량 재구독 회귀 방지)", () => {
  const r = diffDestinations(["a", "b"], ["a", "b"]);
  assert.deepEqual(r, { added: [], removed: [] });
});

test("diffDestinations: 마켓 전환 시 orderbook·trade만 교체되고 ticker 집합은 유지", () => {
  const tickers = ["KRW-BTC", "KRW-ETH", "KRW-XRP"].map((m) => dest.ticker(m));
  // 분리 훅 계약: ticker 훅은 고정 배열이라 diff가 비어야 한다
  assert.deepEqual(diffDestinations(tickers, tickers), { added: [], removed: [] });
  // 마켓 훅은 이전 마켓 2채널 제거 + 새 마켓 2채널 추가만 나와야 한다
  const prev = [dest.orderbook("KRW-BTC"), dest.trade("KRW-BTC")];
  const next = [dest.orderbook("KRW-ETH"), dest.trade("KRW-ETH")];
  const r = diffDestinations(prev, next);
  assert.deepEqual(r.added, next);
  assert.deepEqual(r.removed, prev);
});

test("trade apply는 transient 구독자에게 선두 trade_id 변화를 통지한다(거버너 표본 경로)", () => {
  useExchangeStore.setState({ trades: {} });
  const seen: string[] = [];
  let lastTop: string | undefined;
  // useTradeRateGovernor의 수집 메커니즘과 동형: 셀렉터·리렌더 없이 스토어 변경만 관찰
  const unsub = useExchangeStore.subscribe((s) => {
    const top = s.trades["KRW-BTC"]?.[0]?.trade_id;
    if (!top || top === lastTop) return;
    lastTop = top;
    seen.push(top);
  });
  const { apply } = useExchangeStore.getState();
  apply(tradeMsg("t-1", "KRW-BTC", 1));
  apply(tradeMsg("t-2", "KRW-BTC", 2));
  apply(tradeMsg("x-1", "KRW-ETH", 3)); // 무관 마켓은 표본을 만들지 않는다
  unsub();
  apply(tradeMsg("t-3", "KRW-BTC", 4)); // 해제 후 통지 없음
  assert.deepEqual(seen, ["t-1", "t-2"]);
});
