/**
 * tests/storeSeqGuard.test.ts. 스토어 ticker·orderbook 단조 가드 검증.
 * 느린 REST 스냅샷·재전달이 이미 반영된 최신 실시간을 stale로 되돌리지 않는지 확인한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { useExchangeStore } from "../lib/store";
import type { OrderbookMessage, TickerMessage } from "../lib/types";

function tickerMsg(seq: number, epoch: number, tradePrice = "50000000"): TickerMessage {
  return {
    schema_version: "1.0",
    type: "ticker",
    stream: "realtime",
    epoch,
    seq,
    timestamp: "2026-07-21T09:00:00.000Z",
    market: "KRW-BTC",
    trade_price: tradePrice,
    change: "RISE",
    change_rate: "0.01",
    change_price: "1000",
    high_24h: "51000000",
    low_24h: "49000000",
    acc_trade_volume_24h: "1",
    acc_trade_price_24h: "1",
    best_bid: "1",
    best_ask: "1",
  };
}

function obMsg(seq: number, epoch: number, totalAsk = "1"): OrderbookMessage {
  return {
    schema_version: "1.0",
    type: "orderbook",
    market: "KRW-BTC",
    stream: "realtime",
    epoch,
    seq,
    timestamp: "2026-07-21T09:00:00.000Z",
    total_ask_size: totalAsk,
    total_bid_size: "1",
    orderbook_units: [],
  };
}

test("ticker: 낮은 seq 재전달은 stale로 드롭, 최신 유지", () => {
  useExchangeStore.setState({ tickers: {} });
  const { apply } = useExchangeStore.getState();
  apply(tickerMsg(10, 1, "51000000"));
  apply(tickerMsg(9, 1, "49000000")); // 느린 스냅샷·재전달
  const t = useExchangeStore.getState().tickers["KRW-BTC"];
  assert.equal(t.seq, 10);
  assert.equal(t.trade_price, "51000000");
});

test("ticker: 같은 seq 재전달도 드롭", () => {
  useExchangeStore.setState({ tickers: {} });
  const { apply } = useExchangeStore.getState();
  apply(tickerMsg(10, 1, "51000000"));
  apply(tickerMsg(10, 1, "49000000"));
  assert.equal(useExchangeStore.getState().tickers["KRW-BTC"].trade_price, "51000000");
});

test("ticker: 높은 seq는 정상 반영", () => {
  useExchangeStore.setState({ tickers: {} });
  const { apply } = useExchangeStore.getState();
  apply(tickerMsg(10, 1, "51000000"));
  apply(tickerMsg(11, 1, "52000000"));
  assert.equal(useExchangeStore.getState().tickers["KRW-BTC"].seq, 11);
  assert.equal(useExchangeStore.getState().tickers["KRW-BTC"].trade_price, "52000000");
});

test("ticker: epoch 상승은 낮은 seq여도 최신(리더 세대 교체, seq 도메인 리셋)", () => {
  useExchangeStore.setState({ tickers: {} });
  const { apply } = useExchangeStore.getState();
  apply(tickerMsg(100, 1));
  apply(tickerMsg(2, 2, "52000000")); // 새 리더, seq 리셋
  const t = useExchangeStore.getState().tickers["KRW-BTC"];
  assert.equal(t.epoch, 2);
  assert.equal(t.seq, 2);
  assert.equal(t.trade_price, "52000000");
});

test("orderbook: 낮은 seq 재전달 드롭, 높은 seq 반영", () => {
  useExchangeStore.setState({ orderbooks: {} });
  const { apply } = useExchangeStore.getState();
  apply(obMsg(20, 1, "5"));
  apply(obMsg(19, 1, "9")); // 느린 REST 스냅샷
  assert.equal(useExchangeStore.getState().orderbooks["KRW-BTC"].total_ask_size, "5");
  apply(obMsg(21, 1, "7"));
  assert.equal(useExchangeStore.getState().orderbooks["KRW-BTC"].total_ask_size, "7");
});
