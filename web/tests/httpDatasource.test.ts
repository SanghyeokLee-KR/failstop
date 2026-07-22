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
import type { Ticker, TickerMessage, WsMessage } from "../lib/types";

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
  stomp.state.args!.onConnect();
  ds.subscribe(dest.myOrder, (m) => received.push(m));
  await settle();

  stomp.state.args!.onDisconnect();
  stomp.state.args!.onConnect(); // 재연결 → myOrder 재동기화
  await settle();

  assert.ok(count("/api/v1/orders/closed") >= 1, "재동기화가 종결 주문을 조회");
  const terminal = received.find((m) => m.type === "myOrder" && m.id === closedOrder.id);
  assert.ok(terminal, "종결 주문의 myOrder 통지 전달");
  assert.equal((terminal as { status: string }).status, "FILLED");
});
