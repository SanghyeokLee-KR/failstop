/**
 * tests/httpDatasource.test.ts. HttpStompDataSource 단위 검증.
 * 실제 네트워크 없이 fetch·STOMP를 주입해 계약 동작만 확인한다.
 * 검증 대상: 엔벌로프 파싱, 에러코드 매핑, 세션 만료 재발급, seq gap·epoch 재동기화.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ApiError,
  HttpStompDataSource,
  type StompFactory,
  type StompFactoryArgs,
} from "../lib/api/httpDatasource";
import { dest } from "../lib/types";
import type { OrderbookMessage, Ticker, TickerMessage, WsMessage } from "../lib/types";

/* ===== 테스트 하네스 ===== */

interface RecordedCall {
  url: string;
  method: string;
  auth: string | null;
}

function jsonRes(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

type Route = (call: RecordedCall, hits: number) => Response;

function makeFetch(routes: Record<string, Route>) {
  const calls: RecordedCall[] = [];
  const hits = new Map<string, number>();
  const fetchFn: typeof fetch = async (input, init) => {
    const url = String(input);
    const path = url.replace(/^https?:\/\/[^/]+/, "").split("?")[0];
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const call: RecordedCall = {
      url,
      method: init?.method ?? "GET",
      auth: headers["Authorization"] ?? null,
    };
    calls.push(call);
    const route = routes[path];
    if (!route) return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
    const n = (hits.get(path) ?? 0) + 1;
    hits.set(path, n);
    return route(call, n);
  };
  const count = (path: string) => calls.filter((c) => c.url.includes(path)).length;
  return { fetchFn, calls, count };
}

function makeStomp() {
  const state = {
    args: null as StompFactoryArgs | null,
    active: false,
    deactivations: 0,
    subs: new Map<string, (body: string) => void>(),
  };
  const factory: StompFactory = (args) => {
    state.args = args;
    return {
      activate: () => {
        state.active = true;
      },
      deactivate: async () => {
        state.deactivations += 1;
        state.active = false;
      },
      subscribe: (destination, cb) => {
        state.subs.set(destination, cb);
        return () => {
          state.subs.delete(destination);
        };
      },
    };
  };
  const push = (destination: string, msg: unknown) => {
    const cb = state.subs.get(destination);
    assert.ok(cb, `구독 없음: ${destination}`);
    cb(JSON.stringify(msg));
  };
  return { state, factory, push };
}

async function settle(): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise((r) => setImmediate(r));
}

function b64url(obj: unknown): string {
  return Buffer.from(JSON.stringify(obj)).toString("base64url");
}

let jwtNonce = 0;

function makeJwt(ttlMs: number): string {
  // nonce로 토큰 문자열 유일성 보장 (동일 ms 발급 시 동일 문자열 방지)
  const payload = { sub: "demo", scope: "TRADE", nonce: ++jwtNonce, exp: Math.floor((Date.now() + ttlMs) / 1000) };
  return `${b64url({ alg: "HS256", typ: "JWT" })}.${b64url(payload)}.sig`;
}

function restTicker(seq: number): Ticker {
  return {
    market: "KRW-BTC",
    trade_price: "50005000",
    change: "RISE",
    change_rate: "0.0123",
    change_price: "605000",
    high_24h: "51000000",
    low_24h: "49000000",
    acc_trade_volume_24h: "1234.56",
    acc_trade_price_24h: "61728000000",
    best_bid: "50000000",
    best_ask: "50010000",
    seq,
    timestamp: "2026-07-21T09:00:01.000Z",
  };
}

function wsTicker(seq: number, epoch = 1): TickerMessage {
  const { seq: _s, timestamp, ...rest } = restTicker(seq);
  return { schema_version: "1.0", type: "ticker", epoch, seq, timestamp, ...rest };
}

function wsTrade(seq: number, epoch = 1): WsMessage {
  return {
    schema_version: "1.0",
    type: "trade",
    market: "KRW-BTC",
    epoch,
    seq,
    timestamp: "2026-07-21T09:00:01.000Z",
    trade_id: `t-${seq}`,
    price: "50005000",
    volume: "0.1",
    taker_side: "bid",
  } as WsMessage;
}

function wsCandle(seq: number, epoch = 1): WsMessage {
  return {
    schema_version: "1.0",
    type: "candle",
    unit: "1m",
    market: "KRW-BTC",
    epoch,
    seq,
    timestamp: "2026-07-21T09:00:00.000Z",
    candle_time: "2026-07-21T09:00:00.000Z",
    open: "50000000",
    high: "50100000",
    low: "49900000",
    close: "50050000",
    volume: "12.34",
  } as WsMessage;
}

const emptyList = { schema_version: "1.0", data: [], pagination: { next_cursor: null, has_more: false } };

function newDs(
  routes: Record<string, Route>,
  stompFactory?: StompFactory,
): { ds: HttpStompDataSource; count: (p: string) => number; calls: RecordedCall[] } {
  const { fetchFn, calls, count } = makeFetch(routes);
  const ds = new HttpStompDataSource({
    apiBase: "http://api.test",
    wsBase: "ws://api.test",
    fetchFn,
    stompFactory: stompFactory ?? (() => ({
      activate: () => {},
      deactivate: async () => {},
      subscribe: () => () => {},
    })),
  });
  return { ds, count, calls };
}

/* ===== REST: 엔벌로프·에러 ===== */

test("리스트 엔벌로프 파싱: data 추출, 금액은 문자열 유지", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () =>
      jsonRes(200, {
        schema_version: "1.0",
        data: [
          {
            market: "KRW-BTC",
            korean_name: "비트코인",
            base: "BTC",
            quote: "KRW",
            state: "ACTIVE",
            tick_size: "1000",
            min_order_amount: "5000",
            max_order_amount: "1000000000",
            qty_scale: 8,
            price_scale: 0,
            maker_fee: "0.0005",
            taker_fee: "0.0005",
          },
        ],
        pagination: { next_cursor: null, has_more: false },
      }),
  });
  const markets = await ds.getMarkets();
  assert.equal(markets.length, 1);
  assert.equal(markets[0].tick_size, "1000");
  assert.equal(typeof markets[0].min_order_amount, "string");
});

test("엔벌로프 아님(단건 객체를 리스트로 오인) 시 invalid_envelope", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () => jsonRes(200, { schema_version: "1.0", market: "KRW-BTC" }),
  });
  await assert.rejects(ds.getMarkets(), (e: ApiError) => e.code === "invalid_envelope");
});

test("캔들 500은 빈 배열로 흡수(표시 전용 피드, 화면은 빈 상태)", async () => {
  const { ds } = newDs({
    "/api/v1/candles/15m": () => jsonRes(500, { error: { name: "server_error", message: "일시 오류" } }),
  });
  assert.deepEqual(await ds.getCandles("KRW-BTC", "15m"), []);
});

test("캔들 미구현(404)도 빈 배열로 흡수", async () => {
  const { ds } = newDs({
    "/api/v1/candles/15m": () => jsonRes(404, { error: { name: "not_found", message: "없음" } }),
  });
  assert.deepEqual(await ds.getCandles("KRW-BTC", "15m"), []);
});

test("캔들 조회 실패 흡수 시 진단 경고를 남긴다(무진단 흡수 방지)", async () => {
  const { ds } = newDs({
    "/api/v1/candles/15m": () => jsonRes(500, { error: { name: "server_error", message: "일시 오류" } }),
  });
  const warnings: unknown[][] = [];
  const orig = console.warn;
  console.warn = (...a: unknown[]) => {
    warnings.push(a);
  };
  try {
    assert.deepEqual(await ds.getCandles("KRW-BTC", "15m"), []);
  } finally {
    console.warn = orig;
  }
  assert.equal(warnings.length, 1);
  assert.match(String(warnings[0][0]), /server_error/);
});

test("캔들 엔벌로프 스키마 불일치는 error로 분리해 진단", async () => {
  const { ds } = newDs({
    "/api/v1/candles/15m": () => jsonRes(200, { schema_version: "1.0", market: "KRW-BTC" }),
  });
  const errors: unknown[][] = [];
  const warnings: unknown[][] = [];
  const origErr = console.error;
  const origWarn = console.warn;
  console.error = (...a: unknown[]) => {
    errors.push(a);
  };
  console.warn = (...a: unknown[]) => {
    warnings.push(a);
  };
  try {
    assert.deepEqual(await ds.getCandles("KRW-BTC", "15m"), []);
  } finally {
    console.error = origErr;
    console.warn = origWarn;
  }
  assert.equal(errors.length, 1, "스키마 불일치는 error로 승격");
  assert.equal(warnings.length, 0, "예상 범위 조회 실패와 분리");
  assert.match(String(errors[0][0]), /엔벌로프/);
});

test("캔들 정상 응답은 그대로 통과(금액 문자열 유지)", async () => {
  const { ds } = newDs({
    "/api/v1/candles/15m": () =>
      jsonRes(200, {
        schema_version: "1.0",
        data: [
          {
            market: "KRW-BTC",
            candle_time: "2026-07-21T09:00:00.000Z",
            open: "50000000",
            high: "50100000",
            low: "49900000",
            close: "50050000",
            volume: "12.34",
            seq: 7,
          },
        ],
      }),
  });
  const candles = await ds.getCandles("KRW-BTC", "15m");
  assert.equal(candles.length, 1);
  assert.equal(candles[0].close, "50050000");
  assert.equal(typeof candles[0].volume, "string");
});

test("표준 에러 매핑: name·message·status·retry_after", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () =>
      jsonRes(429, {
        error: {
          name: "too_many_requests",
          message: "요청이 너무 많아요",
          detail: { bucket: "ip" },
          retry_after: 3,
        },
      }),
  });
  await assert.rejects(ds.getMarkets(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.name, "too_many_requests");
    assert.equal(e.code, "too_many_requests");
    assert.equal(e.message, "요청이 너무 많아요");
    assert.equal(e.status, 429);
    assert.equal(e.retryAfter, 3);
    return true;
  });
});

test("에러 본문이 JSON이 아니면 상태코드 기반 기본 에러", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () => new Response("bad gateway", { status: 502 }),
  });
  await assert.rejects(ds.getMarkets(), (e: ApiError) => e.code === "server_error" && e.status === 502);
});

/* ===== 데모 세션 ===== */

test("데모 세션: 최초 인증 요청 전 발급, Bearer 첨부", async () => {
  const tokenA = makeJwt(15 * 60_000);
  const { ds, calls, count } = newDs({
    "/api/v1/demo/session": () => jsonRes(200, { access_token: tokenA, user: { external_id: "u1", email: "demo@t" } }),
    "/api/v1/accounts": () =>
      jsonRes(200, { schema_version: "1.0", data: [], pagination: { next_cursor: null, has_more: false } }),
  });
  await ds.getAccounts();
  assert.equal(count("/demo/session"), 1);
  const acc = calls.find((c) => c.url.includes("/accounts"));
  assert.equal(acc?.auth, `Bearer ${tokenA}`);
});

test("세션 만료(401) 시 재발급 후 1회 재시도", async () => {
  const tokenA = makeJwt(15 * 60_000);
  const tokenB = makeJwt(15 * 60_000);
  const { ds, calls, count } = newDs({
    "/api/v1/demo/session": (_c, hits) => jsonRes(200, { access_token: hits === 1 ? tokenA : tokenB }),
    "/api/v1/accounts": (call) =>
      call.auth === `Bearer ${tokenB}`
        ? jsonRes(200, { schema_version: "1.0", data: [], pagination: { next_cursor: null, has_more: false } })
        : jsonRes(401, { error: { name: "jwt_verification", message: "서명 검증 실패" } }),
  });
  const assets = await ds.getAccounts();
  assert.deepEqual(assets, []);
  assert.equal(count("/demo/session"), 2);
  const accCalls = calls.filter((c) => c.url.includes("/accounts"));
  assert.equal(accCalls.length, 2);
  assert.equal(accCalls[0].auth, `Bearer ${tokenA}`);
  assert.equal(accCalls[1].auth, `Bearer ${tokenB}`);
});

test("exp 경과 토큰은 요청 전에 선제 재발급", async () => {
  const expired = makeJwt(-1000);
  const fresh = makeJwt(15 * 60_000);
  const { ds, calls, count } = newDs({
    "/api/v1/demo/session": (_c, hits) => jsonRes(200, { access_token: hits === 1 ? expired : fresh }),
    "/api/v1/accounts": () =>
      jsonRes(200, { schema_version: "1.0", data: [], pagination: { next_cursor: null, has_more: false } }),
  });
  await ds.getAccounts(); // 만료 토큰 수령 후 그대로 사용(서버가 200이면 통과)
  await ds.getAccounts(); // 두 번째 요청 시점에는 만료 판정 → 재발급
  assert.equal(count("/demo/session"), 2);
  const accCalls = calls.filter((c) => c.url.includes("/accounts"));
  assert.equal(accCalls[1].auth, `Bearer ${fresh}`);
});

/* ===== 주문 라우트 ===== */

test("cancelOrder: UUID(order.id)는 정규 라우트, client_order_id는 by-client 라우트", async () => {
  const uuid = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";
  const order = { id: uuid, status: "CANCELED" };
  const { ds, calls } = newDs({
    "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(15 * 60_000) }),
    [`/api/v1/orders/${uuid}`]: () => jsonRes(200, order),
    "/api/v1/orders/by-client/web-abc-123": () => jsonRes(200, order),
  });
  await ds.cancelOrder(uuid); // 화면 실경로: order.id(UUID)
  await ds.cancelOrder("web-abc-123"); // client_order_id 명시 호출
  const deletes = calls.filter((c) => c.method === "DELETE");
  assert.ok(deletes[0].url.endsWith(`/api/v1/orders/${uuid}`));
  assert.ok(deletes[1].url.endsWith("/api/v1/orders/by-client/web-abc-123"));
});

/* ===== STOMP: seq/epoch ===== */

test("seq 연속 수신은 전달, 중복·역행은 드롭", async () => {
  const stomp = makeStomp();
  const { ds } = newDs(
    { "/api/v1/ticker": () => jsonRes(200, { schema_version: "1.0", data: [restTicker(9)], pagination: { next_cursor: null, has_more: false } }) },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.ticker("KRW-BTC"), (m) => received.push(m));
  await settle();
  const base = received.length; // 초기 스냅샷 1건

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(10));
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(11));
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(11)); // 중복
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(9)); // 역행
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(12));
  await settle();

  const seqs = received.slice(base).map((m) => m.seq);
  assert.deepEqual(seqs, [10, 11, 12]);
});

test("seq gap 감지 시 REST 재동기화 후 스냅샷 기준으로 재개", async () => {
  const stomp = makeStomp();
  let restSeq = 9;
  const { ds, count } = newDs(
    {
      "/api/v1/ticker": () =>
        jsonRes(200, { schema_version: "1.0", data: [restTicker(restSeq)], pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.ticker("KRW-BTC"), (m) => received.push(m));
  await settle();
  const fetchesBefore = count("/api/v1/ticker");

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(10)); // 기준점
  restSeq = 14; // 재동기화 시점의 서버 상태
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(13)); // gap (11·12 누락)
  await settle();

  assert.equal(count("/api/v1/ticker"), fetchesBefore + 1, "gap이 REST 재동기화를 1회 호출");
  const last = received[received.length - 1] as TickerMessage;
  assert.equal(last.seq, 14, "스냅샷(seq 14)이 최종 상태");

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(15)); // 스냅샷 기준 연속
  await settle();
  assert.equal(received[received.length - 1].seq, 15);
});

test("같은 콜백 이중 구독: 한쪽 해제해도 STOMP 구독·실시간 전달이 살아있다(refcount)", async () => {
  const stomp = makeStomp();
  const { ds } = newDs(
    {
      "/api/v1/ticker": () =>
        jsonRes(200, { schema_version: "1.0", data: [restTicker(9)], pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  const cb = (m: WsMessage) => received.push(m); // 같은 콜백 참조(예: store.apply 공유)
  const off1 = ds.subscribe(dest.ticker("KRW-BTC"), cb);
  ds.subscribe(dest.ticker("KRW-BTC"), cb); // 이중 구독
  await settle();

  off1(); // 첫 구독 해제. 생존 구독이 남아 STOMP 구독은 유지돼야 한다
  assert.ok(stomp.state.subs.has(dest.ticker("KRW-BTC")), "생존 구독이 있어 STOMP 구독 유지");
  const before = received.length;
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(10));
  await settle();
  assert.equal(received.length, before + 1, "생존 구독으로 실시간 1건 전달(사일런트 프리즈 없음)");
});

test("epoch 변경 감지 시 전 채널 강제 재동기화", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs(
    {
      "/api/v1/ticker": () =>
        jsonRes(200, { schema_version: "1.0", data: [restTicker(3)], pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  ds.subscribe(dest.ticker("KRW-BTC"), () => {});
  await settle();
  const fetchesBefore = count("/api/v1/ticker");

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(100, 1)); // epoch 1 기준점
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(2, 2)); // 리더 교체, seq 도메인 재설정
  await settle();

  assert.equal(count("/api/v1/ticker"), fetchesBefore + 1, "epoch 변경이 재동기화를 호출");
});

test("trade: event_seq 비연속 증가(1000·1003·1007)는 전부 적용, 재동기화 없음", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs({ "/api/v1/trades": () => jsonRes(200, emptyList) }, stomp.factory);
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.trade("KRW-BTC"), (m) => received.push(m));
  await settle();

  stomp.push(dest.trade("KRW-BTC"), wsTrade(1000)); // 기준점
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1003)); // event_seq 비연속 증가
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1007));
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1005)); // 되돌이(<=lastSeq) 드롭
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1007)); // 중복 드롭
  await settle();

  assert.deepEqual(received.map((m) => m.seq), [1000, 1003, 1007]);
  assert.equal(count("/api/v1/trades"), 0, "비연속 증가는 재동기화를 유발하지 않는다");
});

test("candle: event_seq 비연속 증가는 전부 적용, 되돌이·중복은 드롭, 재동기화 없음", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs({ "/api/v1/candles/1m": () => jsonRes(200, emptyList) }, stomp.factory);
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.candle("1m", "KRW-BTC"), (m) => received.push(m));
  await settle();

  stomp.push(dest.candle("1m", "KRW-BTC"), wsCandle(1000));
  stomp.push(dest.candle("1m", "KRW-BTC"), wsCandle(1003));
  stomp.push(dest.candle("1m", "KRW-BTC"), wsCandle(1007));
  stomp.push(dest.candle("1m", "KRW-BTC"), wsCandle(1007)); // 중복 드롭
  stomp.push(dest.candle("1m", "KRW-BTC"), wsCandle(1002)); // 되돌이 드롭
  await settle();

  assert.deepEqual(received.map((m) => m.seq), [1000, 1003, 1007]);
  assert.equal(count("/api/v1/candles"), 0, "비연속 증가는 재동기화를 유발하지 않는다");
});

test("ticker: +1 조밀 도메인은 비연속 증가를 gap으로 보고 재동기화(trade·candle과 대비)", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs(
    { "/api/v1/ticker": () => jsonRes(200, { schema_version: "1.0", data: [restTicker(1007)], pagination: { next_cursor: null, has_more: false } }) },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  ds.subscribe(dest.ticker("KRW-BTC"), () => {});
  await settle();
  const before = count("/api/v1/ticker");

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(1000)); // 기준점
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(1003)); // 비연속 증가 → gap
  await settle();

  assert.equal(count("/api/v1/ticker"), before + 1, "ticker는 비연속 증가에서 재동기화 유지");
});

test("orderbook: 구독 즉시 snapshot이 연속성 기준점(§5-2)", async () => {
  const stomp = makeStomp();
  const { ds } = newDs(
    {
      "/api/v1/orderbook": () =>
        jsonRes(200, {
          schema_version: "1.0",
          market: "KRW-BTC",
          timestamp: "2026-07-21T09:00:00.123Z",
          epoch: 1,
          seq: 99,
          total_ask_size: "1",
          total_bid_size: "1",
          orderbook_units: [],
        }),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.orderbook("KRW-BTC"), (m) => received.push(m));
  await settle();
  const base = received.length;

  const ob = (seq: number, stream: "snapshot" | "realtime") => ({
    schema_version: "1.0",
    type: "orderbook",
    market: "KRW-BTC",
    epoch: 1,
    seq,
    stream,
    timestamp: "2026-07-21T09:00:00.200Z",
    total_ask_size: "1",
    total_bid_size: "1",
    orderbook_units: [],
  });
  stomp.push(dest.orderbook("KRW-BTC"), ob(100, "snapshot")); // 서버 구독 즉시 1건
  stomp.push(dest.orderbook("KRW-BTC"), ob(101, "realtime"));
  stomp.push(dest.orderbook("KRW-BTC"), ob(100, "realtime")); // 역행 드롭
  await settle();

  const seqs = received.slice(base).map((m) => m.seq);
  assert.deepEqual(seqs, [100, 101]);
});

test("orderbook: 늦게 도착한 스냅샷은 기준점을 되돌리지 않는다", async () => {
  const stomp = makeStomp();
  const { ds } = newDs({}, stomp.factory);
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.orderbook("KRW-BTC"), (m) => received.push(m));
  await settle();

  const ob = (seq: number, stream: "snapshot" | "realtime") => ({
    schema_version: "1.0",
    type: "orderbook",
    market: "KRW-BTC",
    epoch: 1,
    seq,
    stream,
    timestamp: "2026-07-21T09:00:00.200Z",
    total_ask_size: "1",
    total_bid_size: "1",
    orderbook_units: [],
  });
  stomp.push(dest.orderbook("KRW-BTC"), ob(100, "snapshot")); // 기준점
  stomp.push(dest.orderbook("KRW-BTC"), ob(101, "realtime"));
  stomp.push(dest.orderbook("KRW-BTC"), ob(90, "snapshot")); // 늦은 스냅샷. 역행 드롭
  stomp.push(dest.orderbook("KRW-BTC"), ob(102, "realtime"));
  await settle();

  assert.deepEqual(
    received.map((m) => m.seq),
    [100, 101, 102],
    "늦은 스냅샷을 채택하면 이후 실시간이 기준점 역행으로 드롭된다",
  );
});

test("flush: 조밀 채널의 결번은 수용하지 않고 재동기화를 다시 건다", async () => {
  const stomp = makeStomp();
  const deferred: { resolve: ((r: Response) => void) | null } = { resolve: null };
  let tickerHits = 0;
  const body = (seq: number) =>
    jsonRes(200, { schema_version: "1.0", data: [restTicker(seq)], pagination: { next_cursor: null, has_more: false } });
  const fetchFn: typeof fetch = async (input) => {
    if (!String(input).includes("/api/v1/ticker")) {
      return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
    }
    tickerHits += 1;
    if (tickerHits === 1) return body(9); // 구독 초기 스냅샷
    if (tickerHits === 2) {
      return new Promise<Response>((resolve) => {
        deferred.resolve = resolve;
      });
    }
    return body(30); // 결번 감지 후 재동기화
  };
  const ds = new HttpStompDataSource({ apiBase: "http://api.test", wsBase: "ws://api.test", fetchFn, stompFactory: stomp.factory });
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.ticker("KRW-BTC"), (m) => received.push(m));
  await settle();
  const base = received.length;

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(10)); // 기준점
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(13)); // gap → 재동기화 시작(응답 지연)
  await settle();
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(20)); // 재동기화 중 버퍼링
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(25)); // 20과 결번
  await settle();

  assert.ok(deferred.resolve, "재동기화 fetch 발사됨");
  deferred.resolve(body(19)); // 스냅샷 기준점 19
  await settle();

  assert.equal(tickerHits, 3, "flush 중 결번이 재동기화를 다시 건다");
  assert.deepEqual(
    received.slice(base).map((m) => m.seq),
    [10, 19, 20, 30],
    "결번(25)은 수용하지 않고 새 스냅샷(30)으로 이어간다",
  );
});

test("flush: trade 채널은 비연속 증가를 그대로 수용한다(event_seq 도메인)", async () => {
  const stomp = makeStomp();
  const deferred: { resolve: ((r: Response) => void) | null } = { resolve: null };
  let hits = 0;
  const fetchFn: typeof fetch = async (input) => {
    if (!String(input).includes("/api/v1/trades")) {
      return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
    }
    hits += 1;
    return new Promise<Response>((resolve) => {
      deferred.resolve = resolve;
    });
  };
  const ds = new HttpStompDataSource({ apiBase: "http://api.test", wsBase: "ws://api.test", fetchFn, stompFactory: stomp.factory });
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.trade("KRW-BTC"), (m) => received.push(m));
  await settle();

  // 재연결이 trade 채널 재동기화를 건다(REST 응답 지연)
  stomp.state.args!.onDisconnect();
  stomp.state.args!.onConnect();
  await settle();
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1000));
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1007)); // 비연속 증가
  await settle();
  assert.ok(deferred.resolve, "재동기화 fetch 발사됨");
  deferred.resolve(jsonRes(200, emptyList));
  await settle();

  assert.deepEqual(
    received.map((m) => m.seq),
    [1000, 1007],
    "trade는 채널당 비연속이 정상이라 flush에서 결번 검사를 걸지 않는다",
  );
  assert.equal(hits, 1, "추가 재동기화 없음");
});

test("재연결 시 전 채널 재동기화, 연결 상태 통지", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs(
    {
      "/api/v1/ticker": () =>
        jsonRes(200, { schema_version: "1.0", data: [restTicker(5)], pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  const states: boolean[] = [];
  ds.onConnectionChange((c) => states.push(c));
  ds.connect();
  stomp.state.args!.onConnect();
  ds.subscribe(dest.ticker("KRW-BTC"), () => {});
  await settle();
  const fetchesBefore = count("/api/v1/ticker");

  stomp.state.args!.onDisconnect();
  stomp.state.args!.onConnect(); // 재연결
  await settle();

  assert.deepEqual(states, [false, true, false, true]);
  assert.equal(count("/api/v1/ticker"), fetchesBefore + 1, "재연결이 채널 재동기화를 호출");
});

/* ===== exchange-config 파싱 (§4-A-6) ===== */

test("exchange-config trade_flash=auto는 mode:auto·level:normal로 좁힌다", async () => {
  const { ds } = newDs({
    "/api/v1/exchange-config": () => jsonRes(200, { schema_version: "1.0", trade_flash: "auto" }),
  });
  assert.deepEqual(await ds.getEffectConfig(), { mode: "auto", level: "normal", schema_version: "1.0" });
});

test("exchange-config trade_flash 열거값은 mode:manual·해당 level로 좁힌다", async () => {
  for (const flash of ["off", "subtle", "normal", "strong"] as const) {
    const { ds } = newDs({
      "/api/v1/exchange-config": () => jsonRes(200, { schema_version: "1.0", trade_flash: flash }),
    });
    assert.deepEqual(await ds.getEffectConfig(), { mode: "manual", level: flash, schema_version: "1.0" });
  }
});

test("exchange-config 미인증 공개 조회다(Authorization 미첨부)", async () => {
  const { ds, calls } = newDs({
    "/api/v1/exchange-config": () => jsonRes(200, { schema_version: "1.0", trade_flash: "normal" }),
  });
  await ds.getEffectConfig();
  const call = calls.find((c) => c.url.includes("/exchange-config"));
  assert.equal(call?.auth, null);
});

test("exchange-config 미상값·누락은 안전 폴백(auto·normal)", async () => {
  for (const body of [{ schema_version: "1.0" }, { trade_flash: "loud" }, { trade_flash: 3 }, {}]) {
    const { ds } = newDs({ "/api/v1/exchange-config": () => jsonRes(200, body) });
    assert.deepEqual(await ds.getEffectConfig(), { mode: "auto", level: "normal", schema_version: "1.0" });
  }
});

test("exchange-config 조회 실패는 안전 폴백(비민감·비필수)", async () => {
  const { ds } = newDs({
    "/api/v1/exchange-config": () => jsonRes(500, { error: { name: "server_error", message: "일시 오류" } }),
  });
  assert.deepEqual(await ds.getEffectConfig(), { mode: "auto", level: "normal", schema_version: "1.0" });
});

/* ===== seq 도메인 없는 채널 ===== */

test("seq 도메인 없는 채널(§5-3 미분류)은 단조 seq 검사를 우회해 전달", async () => {
  const stomp = makeStomp();
  const { ds } = newDs({}, stomp.factory);
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  const misc = "/topic/config";
  ds.subscribe(misc, (m) => received.push(m));
  await settle();

  // 역행·중복 seq여도 seq 규약을 적용하지 않아 전량 전달된다
  stomp.push(misc, { type: "config", seq: 5, epoch: 1 });
  stomp.push(misc, { type: "config", seq: 3, epoch: 1 });
  stomp.push(misc, { type: "config", seq: 3, epoch: 1 });
  await settle();

  assert.equal(received.length, 3);
});

test("myOrder 재동기화는 open과 closed를 함께 복원해 종결 통지를 확정", async () => {
  const stomp = makeStomp();
  const openOrder = { id: "1b4e28ba-2fa1-11d2-883f-0016d3cca427", status: "OPEN", created_at: "2026-07-21T09:00:00.000Z" };
  const closedOrder = { id: "6ba7b810-9dad-11d1-80b4-00c04fd430c8", status: "FILLED", created_at: "2026-07-21T09:00:01.000Z" };
  const { ds, count } = newDs(
    {
      "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(15 * 60_000) }),
      "/api/v1/orders/open": () =>
        jsonRes(200, { schema_version: "1.0", data: [openOrder], pagination: { next_cursor: null, has_more: false } }),
      "/api/v1/orders/closed": () =>
        jsonRes(200, { schema_version: "1.0", data: [closedOrder], pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  const received: WsMessage[] = [];
  ds.connect();
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect();
  ds.subscribe(dest.myOrder, (m) => received.push(m));
  await settle();
  // 구독이 건 인증 승격으로 세션이 확보된 상태. 승격 연결을 성립시킨다(실 클라이언트는
  // beforeConnect가 항상 CONNECT 직전에 돈다)
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect();
  await settle();

  stomp.state.args!.onDisconnect();
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect(); // 재연결 → myOrder 재동기화
  await settle();

  assert.ok(count("/api/v1/orders/closed") >= 1, "재동기화가 종결 주문을 조회");
  const terminal = received.find((m) => m.type === "myOrder" && m.id === closedOrder.id);
  assert.ok(terminal, "종결 주문의 myOrder 통지 전달");
  assert.equal((terminal as { status: string }).status, "FILLED");
});

/* ===== 비공개 채널 인증 승격 ===== */

test("만료 토큰 재연결은 비공개 채널 인증 승격을 재발화한다", async () => {
  const stomp = makeStomp();
  const expired = makeJwt(-1000);
  const fresh = makeJwt(15 * 60_000);
  const { ds, count } = newDs(
    {
      "/api/v1/demo/session": (_c, hits) => jsonRes(200, { access_token: hits === 1 ? expired : fresh }),
      "/api/v1/orders/open": () => jsonRes(200, emptyList),
      "/api/v1/orders/closed": () => jsonRes(200, emptyList),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.connectHeaders(); // 세션 미보유 → 무인증 CONNECT
  stomp.state.args!.onConnect();
  ds.subscribe(dest.myOrder, () => {});
  await settle();
  assert.equal(stomp.state.deactivations, 1, "구독 시 승격 재연결 1회");

  // 승격으로 받은 토큰이 이미 만료라 재연결도 무인증 CONNECT가 된다
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect();
  await settle();

  assert.equal(stomp.state.deactivations, 2, "재연결 경로에서 승격이 다시 발화해야 한다");
  const headers = stomp.state.args!.connectHeaders();
  assert.equal(headers["Authorization"], `Bearer ${fresh}`, "승격 후 CONNECT는 Bearer 첨부");

  // 승격 연결에서 비공개 채널이 재구독·재동기화된다
  stomp.state.args!.onConnect();
  await settle();
  assert.ok(stomp.state.subs.has(dest.myOrder), "승격 연결에서 비공개 채널 재구독");
  assert.ok(count("/api/v1/orders/open") >= 1, "승격 연결에서 비공개 채널 재동기화");
});

test("승격 세션 발급 실패 시 채널 복원을 포기하지 않는다", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs(
    {
      "/api/v1/demo/session": () => jsonRes(503, { error: { name: "server_error", message: "일시 오류" } }),
      "/api/v1/ticker": () =>
        jsonRes(200, { schema_version: "1.0", data: [restTicker(5)], pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect();
  ds.subscribe(dest.myOrder, () => {});
  ds.subscribe(dest.ticker("KRW-BTC"), () => {});
  await settle();
  const before = count("/api/v1/ticker");

  stomp.state.args!.onDisconnect();
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect(); // 재연결. 승격 시도가 세션 발급 실패로 좌초한다
  await settle();

  assert.ok(stomp.state.subs.has(dest.ticker("KRW-BTC")), "승격 실패에도 공개 채널은 재구독");
  assert.equal(count("/api/v1/ticker"), before + 1, "승격 실패에도 재동기화가 돈다");
});

/* ===== 회원 세션 (§3-8) ===== */

function edJwt(ttlMs: number, sub = "member"): string {
  const payload = {
    typ: "access",
    sub,
    user_id: 42,
    scope: "TRADE",
    kind: "HUMAN",
    exp: Math.floor((Date.now() + ttlMs) / 1000),
  };
  return `${b64url({ alg: "EdDSA", typ: "JWT" })}.${b64url(payload)}.sig`;
}

const MEMBER_USER = {
  external_id: "9f2b7a4e",
  email: "trader@demo.local",
  display_name: "데모트레이더",
  kind: "HUMAN",
};

test("로그인 성공 시 세션을 확립하고 onMemberSessionChange로 통지", async () => {
  const { ds } = newDs({
    "/api/v1/auth/login": () =>
      jsonRes(200, { schema_version: "1.0", access_token: edJwt(900_000), token_type: "Bearer", user: MEMBER_USER }),
  });
  const seen: (string | null)[] = [];
  ds.onMemberSessionChange((s) => seen.push(s ? s.user.email : null));
  const session = await ds.login({ email: "trader@demo.local", password: "demopass1" });
  assert.equal(session.user.email, "trader@demo.local");
  assert.equal(ds.getMemberSession()?.user.display_name, "데모트레이더");
  // 등록 즉시 null 1회 + 로그인 후 이메일 1회
  assert.deepEqual(seen, [null, "trader@demo.local"]);
});

test("로그인 실패는 §3-4 에러코드로 throw(존재 여부 비노출)", async () => {
  const { ds } = newDs({
    "/api/v1/auth/login": () =>
      jsonRes(401, { error: { name: "invalid_credentials", message: "이메일 또는 비밀번호를 확인해 주세요" } }),
  });
  await assert.rejects(
    () => ds.login({ email: "x@y.z", password: "nope12345" }),
    (e: unknown) => e instanceof ApiError && e.code === "invalid_credentials",
  );
  assert.equal(ds.getMemberSession(), null);
});

test("인증 REST는 회원 세션 access를 Bearer로 첨부(데모 토큰 아님)", async () => {
  const memberToken = edJwt(900_000);
  const { ds, calls } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: memberToken, user: MEMBER_USER }),
    "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(900_000), user: { external_id: "demo" } }),
    "/api/v1/accounts": () => jsonRes(200, { schema_version: "1.0", data: [] }),
  });
  await ds.login({ email: "trader@demo.local", password: "demopass1" });
  await ds.getAccounts();
  const acct = calls.find((c) => c.url.includes("/api/v1/accounts"));
  assert.equal(acct?.auth, `Bearer ${memberToken}`);
  // 데모 세션 발급이 일어나지 않았다(회원 토큰 우선)
  assert.equal(calls.some((c) => c.url.includes("/api/v1/demo/session")), false);
});

test("인증 REST 401은 회원 refresh 후 새 토큰으로 1회 재시도", async () => {
  const firstToken = edJwt(900_000, "stale");
  const nextToken = edJwt(900_000, "fresh");
  let acctHits = 0;
  const { ds, calls } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: firstToken, user: MEMBER_USER }),
    "/api/v1/auth/refresh": () => jsonRes(200, { schema_version: "1.0", access_token: nextToken }),
    "/api/v1/accounts": () => {
      acctHits += 1;
      // 첫 호출은 만료로 401, 재시도는 성공
      return acctHits === 1
        ? jsonRes(401, { error: { name: "jwt_verification", message: "만료" } })
        : jsonRes(200, { schema_version: "1.0", data: [] });
    },
  });
  await ds.login({ email: "trader@demo.local", password: "demopass1" });
  await ds.getAccounts();
  assert.equal(acctHits, 2, "401 후 1회 재시도");
  const retried = calls.filter((c) => c.url.includes("/api/v1/accounts"));
  assert.equal(retried[1]?.auth, `Bearer ${nextToken}`, "재시도는 갱신된 회원 토큰 사용");
});

test("refresh 실패는 회원 세션을 해제하고 데모로 폴백", async () => {
  const memberToken = edJwt(900_000);
  const { ds, calls } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: memberToken, user: MEMBER_USER }),
    "/api/v1/auth/refresh": () => jsonRes(401, { error: { name: "session_expired", message: "만료" } }),
    "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(900_000), user: { external_id: "demo" } }),
    "/api/v1/accounts": () => jsonRes(200, { schema_version: "1.0", data: [] }),
  });
  const seen: (string | null)[] = [];
  ds.onMemberSessionChange((s) => seen.push(s ? s.user.email : null));
  await ds.login({ email: "trader@demo.local", password: "demopass1" });
  const restored = await ds.refresh();
  assert.equal(restored, null, "refresh 실패는 null");
  assert.equal(ds.getMemberSession(), null, "세션 해제");
  // 세션 해제 후 인증 REST는 데모 세션으로 폴백
  await ds.getAccounts();
  assert.ok(calls.some((c) => c.url.includes("/api/v1/demo/session")), "데모 세션 폴백 발급");
  assert.equal(seen[seen.length - 1], null, "마지막 통지는 세션 null");
});

test("회원 기원 쓰기는 refresh 실패 시 데모로 재집행하지 않고 session_expired", async () => {
  const memberToken = edJwt(900_000);
  let orderHits = 0;
  const { ds, calls } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: memberToken, user: MEMBER_USER }),
    "/api/v1/auth/refresh": () => jsonRes(401, { error: { name: "session_expired", message: "만료" } }),
    "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(900_000), user: { external_id: "demo" } }),
    "/api/v1/orders": () => {
      orderHits += 1;
      // 서버측 세션 폐기(타 기기 로그아웃 등)로 회원 토큰이 401
      return jsonRes(401, { error: { name: "jwt_verification", message: "만료" } });
    },
  });
  await ds.login({ email: "trader@demo.local", password: "demopass1" });
  await assert.rejects(
    () =>
      ds.placeOrder({
        market: "KRW-BTC",
        side: "bid",
        ord_type: "limit",
        price: "50000000",
        volume: "0.001",
        client_order_id: "web-order-1",
      }),
    (e: unknown) => e instanceof ApiError && e.code === "session_expired",
    "쓰기는 데모 폴백 대신 session_expired로 표면화",
  );
  assert.equal(orderHits, 1, "회원 토큰 최초 시도 1회만. 데모로 재집행 없음");
  const orderCalls = calls.filter((c) => c.url.includes("/api/v1/orders"));
  assert.equal(orderCalls[0]?.auth, `Bearer ${memberToken}`, "주문은 회원 Bearer로만 나감");
  assert.equal(ds.getMemberSession(), null, "회원 세션 해제");
});

test("로그아웃은 서버 폐기 시도 후 세션 해제", async () => {
  const memberToken = edJwt(900_000);
  const { ds, count } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: memberToken, user: MEMBER_USER }),
    "/api/v1/auth/logout": () => new Response(null, { status: 204 }),
  });
  await ds.login({ email: "trader@demo.local", password: "demopass1" });
  await ds.logout();
  assert.equal(ds.getMemberSession(), null);
  assert.ok(count("/api/v1/auth/logout") >= 1, "서버 로그아웃 호출");
});

test("비로그인 반복 refresh는 서버를 1회만 호출한다(401 루프 억제)", async () => {
  const { ds, count } = newDs({
    "/api/v1/auth/refresh": () => jsonRes(401, { error: { name: "session_expired", message: "만료" } }),
  });
  const a = await ds.refresh();
  const b = await ds.refresh();
  const c = await ds.refresh();
  assert.equal(a, null);
  assert.equal(b, null);
  assert.equal(c, null);
  assert.equal(count("/api/v1/auth/refresh"), 1, "직전 401 이후 refresh 재요청 억제");
});

test("재로그인은 refresh 억제를 해제한다", async () => {
  const memberToken = edJwt(900_000);
  const { ds, count } = newDs({
    "/api/v1/auth/refresh": () => jsonRes(401, { error: { name: "session_expired", message: "만료" } }),
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: memberToken, user: MEMBER_USER }),
  });
  await ds.refresh(); // 1회 실패 → 억제 on
  await ds.refresh(); // 억제됨(요청 없음)
  assert.equal(count("/api/v1/auth/refresh"), 1);
  await ds.login({ email: "trader@demo.local", password: "demopass1" }); // setMember → 억제 해제
  await ds.refresh(); // 재허용 → 서버 왕복(401)
  assert.equal(count("/api/v1/auth/refresh"), 2, "재로그인 후 refresh 재허용");
});

test("refresh 일시 장애(5xx)는 회원 세션을 유지하고 원 오류를 표면화한다", async () => {
  const memberToken = edJwt(900_000);
  const { ds, count } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: memberToken, user: MEMBER_USER }),
    "/api/v1/auth/refresh": () => jsonRes(503, { error: { name: "server_error", message: "일시 오류" } }),
  });
  await ds.login({ email: "trader@demo.local", password: "demopass1" });
  await assert.rejects(ds.refresh(), (e: ApiError) => e.status === 503);
  assert.ok(ds.getMemberSession(), "일시 장애로 유효 rt 쿠키 세션을 데모로 강등하지 않는다");
  // refreshBlocked 미설정: 다음 트리거가 서버를 다시 왕복한다(억제는 401·403 전용)
  await assert.rejects(ds.refresh());
  assert.equal(count("/api/v1/auth/refresh"), 2, "일시 장애는 재시도 트리거를 보존");
});

/* ===== 구독 취소·초기 스냅샷 신선도 ===== */

test("unsubscribe는 in-flight 초기 스냅샷 fetch를 실제 취소한다", async () => {
  // 클로저 대입은 TS 흐름 분석 밖이라 ref 객체로 관찰한다
  const captured: { signal: AbortSignal | null } = { signal: null };
  const fetchFn: typeof fetch = (input, init) => {
    const url = String(input);
    if (url.includes("/api/v1/ticker")) {
      captured.signal = init?.signal ?? null;
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("취소", "AbortError")));
      });
    }
    return Promise.resolve(jsonRes(404, { error: { name: "order_not_found", message: "없음" } }));
  };
  const ds = new HttpStompDataSource({
    apiBase: "http://api.test",
    wsBase: "ws://api.test",
    fetchFn,
    stompFactory: () => ({ activate: () => {}, deactivate: async () => {}, subscribe: () => () => {} }),
  });
  const received: WsMessage[] = [];
  const off = ds.subscribe(dest.ticker("KRW-BTC"), (m) => received.push(m));
  await settle();
  assert.ok(captured.signal, "초기 스냅샷 fetch에 signal 전파");
  off();
  assert.equal(captured.signal.aborted, true, "구독 해제가 in-flight fetch를 abort");
  await settle();
  assert.equal(received.length, 0, "취소된 스냅샷은 전달되지 않는다");
});

test("myAsset: realtime 기수신이면 늦은 초기 스냅샷 REST를 폐기한다(잔액 역전 방지)", async () => {
  const stomp = makeStomp();
  const deferred: { resolveAccounts: ((r: Response) => void) | null } = { resolveAccounts: null };
  const fetchFn: typeof fetch = async (input) => {
    const url = String(input);
    if (url.includes("/api/v1/demo/session")) {
      return jsonRes(200, { access_token: makeJwt(15 * 60_000) });
    }
    if (url.includes("/api/v1/accounts")) {
      return new Promise<Response>((resolve) => {
        deferred.resolveAccounts = resolve;
      });
    }
    return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
  };
  const ds = new HttpStompDataSource({ apiBase: "http://api.test", wsBase: "ws://api.test", fetchFn, stompFactory: stomp.factory });
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.myAsset, (m) => received.push(m));
  await settle();

  // REST 왕복 중 체결 후 잔액이 realtime으로 먼저 도착(기준점 확립)
  stomp.push(dest.myAsset, {
    schema_version: "1.0",
    type: "myAsset",
    epoch: 1,
    seq: 7,
    timestamp: "2026-07-21T09:00:02.000Z",
    assets: [{ currency: "KRW", balance: "90000", locked: "0" }],
  });
  await settle();
  // 체결 전 잔액을 실은 늦은 REST 응답 도착
  assert.ok(deferred.resolveAccounts, "초기 스냅샷 REST가 발사됨");
  deferred.resolveAccounts(
    jsonRes(200, {
      schema_version: "1.0",
      data: [{ currency: "KRW", balance: "100000", locked: "0", avg_buy_price: "0", unit_currency: "KRW" }],
      pagination: { next_cursor: null, has_more: false },
    }),
  );
  await settle();

  const assetMsgs = received.filter((m) => m.type === "myAsset");
  assert.equal(assetMsgs.length, 1, "늦은 REST 스냅샷이 배달되면 최신 잔액이 체결 전으로 역전된다");
  const balance = (assetMsgs[0] as { assets: { balance: string }[] }).assets[0].balance;
  assert.equal(balance, "90000", "realtime 잔액 유지");
});

/* ===== 재동기화 세대 가드 ===== */

test("플랩 재연결: 늦게 끝난 이전 재동기화가 기준점·전달을 되돌리지 않는다", async () => {
  const stomp = makeStomp();
  const deferred: { resolveFirstResync: ((r: Response) => void) | null } = { resolveFirstResync: null };
  let tickerHits = 0;
  const tickerBody = (seq: number) =>
    jsonRes(200, { schema_version: "1.0", data: [restTicker(seq)], pagination: { next_cursor: null, has_more: false } });
  const fetchFn: typeof fetch = async (input) => {
    const url = String(input);
    if (url.includes("/api/v1/ticker")) {
      tickerHits += 1;
      if (tickerHits === 1) return tickerBody(50); // 구독 초기 스냅샷
      if (tickerHits === 2) {
        // 첫 재연결의 재동기화: 응답 지연(플랩 중 미완)
        return new Promise<Response>((resolve) => {
          deferred.resolveFirstResync = resolve;
        });
      }
      return tickerBody(200); // 두 번째 재연결의 재동기화
    }
    return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
  };
  const ds = new HttpStompDataSource({ apiBase: "http://api.test", wsBase: "ws://api.test", fetchFn, stompFactory: stomp.factory });
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.ticker("KRW-BTC"), (m) => received.push(m));
  await settle();
  const base = received.length;

  stomp.state.args!.onDisconnect();
  stomp.state.args!.onConnect(); // 재동기화 1(지연 중)
  stomp.state.args!.onDisconnect();
  stomp.state.args!.onConnect(); // 재동기화 2(즉시 완료, 기준점 seq 200)
  await settle();
  // 이전 세대의 늦은 응답(오래된 seq 100)이 이제야 도착
  assert.ok(deferred.resolveFirstResync, "첫 재동기화 fetch 발사됨");
  deferred.resolveFirstResync(tickerBody(100));
  await settle();

  // stale 세대의 스냅샷은 배달되지 않고 기준점도 회귀하지 않는다
  const seqs = received.slice(base).map((m) => m.seq);
  assert.deepEqual(seqs, [200], "최신 세대 스냅샷만 배달");
  // 기준점이 200으로 유지됐다면 다음 realtime 201이 gap 재동기화 없이 그대로 전달된다
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(201));
  await settle();
  assert.deepEqual(
    received.slice(base).map((m) => m.seq),
    [200, 201],
    "기준점 회귀가 없어 realtime이 이어진다",
  );
});

/* ===== 네트워크 거부 래핑 (§2-3 한국어 메시지 계약) ===== */

test("authPost·issueSession: fetch 거부는 network_error로 래핑된다", async () => {
  const failFetch: typeof fetch = async (input) => {
    const url = String(input);
    if (url.includes("/api/v1/auth/login") || url.includes("/api/v1/demo/session")) {
      throw new TypeError("Failed to fetch");
    }
    return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
  };
  const ds = new HttpStompDataSource({
    apiBase: "http://api.test",
    wsBase: "ws://api.test",
    fetchFn: failFetch,
    stompFactory: () => ({ activate: () => {}, deactivate: async () => {}, subscribe: () => () => {} }),
  });
  await assert.rejects(ds.login({ email: "a@b.c", password: "pw" }), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.code, "network_error");
    assert.equal(e.status, 0);
    return true;
  });
  // 데모 세션 발급(issueSession) 경로. 인증 REST가 세션 확보 단계에서 같은 코드로 수렴한다
  await assert.rejects(ds.getAccounts(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.code, "network_error");
    return true;
  });
});

test("authPost: AbortError는 래핑하지 않고 원형 전파한다", async () => {
  const abortFetch: typeof fetch = async () => {
    const err = new Error("중단");
    err.name = "AbortError";
    throw err;
  };
  const ds = new HttpStompDataSource({
    apiBase: "http://api.test",
    wsBase: "ws://api.test",
    fetchFn: abortFetch,
    stompFactory: () => ({ activate: () => {}, deactivate: async () => {}, subscribe: () => () => {} }),
  });
  await assert.rejects(ds.login({ email: "a@b.c", password: "pw" }), (e: unknown) => {
    assert.equal((e as Error).name, "AbortError");
    assert.ok(!(e instanceof ApiError));
    return true;
  });
});

/* ===== §5-4 orderbook totals 폴백 ===== */

test("totals 없는 orderbook 수신 시 orderbook_units 합산으로 채운다(문면 준수 서버 대응)", async () => {
  const stomp = makeStomp();
  const { ds } = newDs({}, stomp.factory);
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.orderbook("KRW-BTC"), (m) => received.push(m));
  await settle();
  stomp.push(dest.orderbook("KRW-BTC"), {
    schema_version: "1.0",
    type: "orderbook",
    market: "KRW-BTC",
    stream: "snapshot",
    epoch: 1,
    seq: 5,
    timestamp: "2026-07-21T09:00:01.000Z",
    orderbook_units: [
      { ask_price: "50010000", bid_price: "50000000", ask_size: "0.5", bid_size: "1.25" },
      { ask_price: "50020000", bid_price: "49990000", ask_size: "0.125", bid_size: "2" },
    ],
  });
  await settle();
  const ob = received.find((m) => m.type === "orderbook") as OrderbookMessage | undefined;
  assert.ok(ob, "orderbook 메시지 수신");
  assert.equal(ob.total_ask_size, "0.625");
  assert.equal(ob.total_bid_size, "3.25");
});
