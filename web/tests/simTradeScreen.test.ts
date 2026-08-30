/**
 * sim 데이터소스가 거래 화면 핵심 데이터를 채우는지 고정. 정적 export의 기본 데이터소스가 sim이라,
 * 여기가 비면 백엔드 없이 out/을 여는 사람에게 거래 화면이 통째로 비어 보인다.
 * 화면이 구독하는 destination(§5-3)과 REST 스냅샷(§4)을 같은 이름으로 검증한다.
 */

import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { SimDataSource } from "../lib/api/datasource";
import { SimEngine, COIN_META } from "../lib/sim/engine";
import { dest, type WsMessage } from "../lib/types";

const MARKET = "KRW-BTC";

function isPositiveDecimal(v: string | null): boolean {
  return v !== null && /^\d+(\.\d+)?$/.test(v) && Number(v) > 0;
}

/** queueMicrotask로 나가는 구독 스냅샷을 흘려보낸다 */
async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

test("REST 스냅샷: 마켓·티커·호가·체결·캔들이 전부 채워진다", async () => {
  const ds = new SimDataSource(new SimEngine());

  const markets = await ds.getMarkets();
  assert.equal(markets.length, COIN_META.length, "마켓 목록이 카탈로그와 같은 수여야 한다");

  const tickers = await ds.getTicker(COIN_META.map((c) => c.market));
  assert.equal(tickers.length, COIN_META.length);
  for (const t of tickers) {
    assert.ok(isPositiveDecimal(t.trade_price), `${t.market} 현재가가 비었다`);
    assert.ok(isPositiveDecimal(t.high_24h), `${t.market} 고가가 비었다`);
    assert.ok(isPositiveDecimal(t.acc_trade_price_24h), `${t.market} 거래대금이 비었다`);
  }

  const book = await ds.getOrderbook(MARKET);
  assert.equal(book.orderbook_units.length, 10, "호가는 10단계다");
  for (const u of book.orderbook_units) {
    assert.ok(isPositiveDecimal(u.ask_price) && isPositiveDecimal(u.ask_size));
    assert.ok(isPositiveDecimal(u.bid_price) && isPositiveDecimal(u.bid_size));
  }

  const trades = await ds.getTrades(MARKET, 30);
  assert.equal(trades.length, 30, "체결 테이프 초기화는 REST가 담당한다");
  assert.ok(trades.every((t) => isPositiveDecimal(t.price) && isPositiveDecimal(t.volume)));

  const candles = await ds.getCandles(MARKET, "15m", 120);
  assert.equal(candles.length, 120);
  assert.ok(candles.every((c) => isPositiveDecimal(c.close) && isPositiveDecimal(c.high)));
});

test("구독 스냅샷: ticker·orderbook은 구독 즉시 1건 배달된다(§5-2)", async () => {
  const ds = new SimDataSource(new SimEngine());
  const got: WsMessage[] = [];
  const offTicker = ds.subscribe(dest.ticker(MARKET), (m) => got.push(m));
  const offBook = ds.subscribe(dest.orderbook(MARKET), (m) => got.push(m));
  await flush();
  offTicker();
  offBook();

  const ticker = got.find((m) => m.type === "ticker");
  const book = got.find((m) => m.type === "orderbook");
  assert.ok(ticker && ticker.type === "ticker", "ticker 스냅샷 미배달");
  assert.equal(ticker.stream, "snapshot");
  assert.ok(isPositiveDecimal(ticker.trade_price));
  assert.ok(book && book.type === "orderbook", "orderbook 스냅샷 미배달");
  assert.equal(book.orderbook_units.length, 10);
});

test("틱 루프: 구독 채널로 체결·티커·호가가 계속 흐른다", async () => {
  mock.timers.enable({ apis: ["setInterval", "setTimeout"] });
  try {
    const ds = new SimDataSource(new SimEngine());
    const kinds = new Set<string>();
    const offs = COIN_META.flatMap((c) => [
      ds.subscribe(dest.trade(c.market), (m) => kinds.add(m.type)),
      ds.subscribe(dest.ticker(c.market), (m) => kinds.add(m.type)),
      ds.subscribe(dest.orderbook(c.market), (m) => kinds.add(m.type)),
    ]);
    ds.connect();
    // 틱 간격 700ms. 여러 틱을 돌려 어느 마켓이 뽑히든 세 채널이 모두 발행되게 한다
    mock.timers.tick(7_000);
    ds.disconnect();
    for (const off of offs) off();

    assert.ok(kinds.has("trade"), "체결 스트림이 흐르지 않는다");
    assert.ok(kinds.has("ticker"), "티커 갱신이 흐르지 않는다");
    assert.ok(kinds.has("orderbook"), "호가 갱신이 흐르지 않는다");
  } finally {
    mock.timers.reset();
  }
});

test("마켓 카탈로그: 전 마켓이 호가·체결 채널을 채운다(딥링크 진입 보장)", async () => {
  const ds = new SimDataSource(new SimEngine());
  for (const c of COIN_META) {
    const book = await ds.getOrderbook(c.market);
    assert.equal(book.orderbook_units.length, 10, `${c.market} 호가 미생성`);
    const trades = await ds.getTrades(c.market, 5);
    assert.equal(trades.length, 5, `${c.market} 체결 미생성`);
  }
});
