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
  jitteredReconnectDelayMs,
  WS_RECONNECT_BASE_MS,
  WS_RECONNECT_MAX_MS,
  type StompFactory,
  type StompFactoryArgs,
} from "../lib/api/httpDatasource";
import { dest, TRADE_TAPE_LIMIT } from "../lib/types";
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

/* 429만 흡수 대상에서 뺀다. 백프레셔는 실패가 아니라 지연이고 서버가 회복 시각을 준다.
   빈 배열로 접으면 화면이 "데이터 없음"을 그려 설계된 백프레셔를 데이터 부재로 번역한다.
   실제로 체결 12만 건이 도는 마켓의 429 빈 차트를 데이터 부재로 오진한 사례가 있다 */
test("캔들 429는 흡수하지 않는다. 사유를 올려 호출자가 지연으로 다루게 한다", async () => {
  const { ds } = newDs({
    "/api/v1/candles/15m": () =>
      new Response(JSON.stringify({ error: { name: "too_many_requests", message: "동시 처리 한도 도달. 잠시 후 재시도 가능", retry_after: 3 } }),
        { status: 429, headers: { "Content-Type": "application/json" } }),
  });
  await assert.rejects(
    () => ds.getCandles("KRW-BTC", "15m"),
    (e: unknown) => e instanceof ApiError && e.status === 429 && e.retryAfter === 3,
    "429를 빈 배열로 접으면 지연과 데이터 부재가 같은 화면이 된다",
  );
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

/* 429 파싱 계약(§2-3 429 클라이언트 규약). 세 평면이 같은 규칙을 쓴다는 것을 여기서 고정한다.
   429는 설계된 백프레셔이고 서버가 회복 시각을 함께 준다. 그 정보가 어댑터에서 소실되면
   화면은 "언제 되는지"를 말할 수 없고 곧 풀릴 지연을 영구 실패로 적게 된다 */
test("429: 비 JSON 본문도 too_many_requests로 분류한다", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () => new Response("<html>gateway</html>", { status: 429 }),
  });
  await assert.rejects(ds.getMarkets(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 429);
    // invalid_parameter 로 떨어지면 authErrors 가 표시명 필드 오류로 매핑한다. 열화가 아니라 오답이다
    assert.equal(e.code, "too_many_requests");
    return true;
  });
});

test("429: name 없는 본문에서도 retry_after를 읽는다", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () => jsonRes(429, { error: { retry_after: 3 } }),
  });
  await assert.rejects(ds.getMarkets(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 429);
    assert.equal(e.code, "too_many_requests");
    // 가드 안에서 읽으면 여기서 null 이 된다. 회복 시각을 버리는 자리다
    assert.equal(e.retryAfter, 3);
    return true;
  });
});

test("429: 본문에 retry_after가 없으면 Retry-After 헤더로 폴백한다", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () =>
      new Response(JSON.stringify({ error: { name: "too_many_requests" } }), {
        status: 429,
        headers: { "Content-Type": "application/json", "Retry-After": "5" },
      }),
  });
  await assert.rejects(ds.getMarkets(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.retryAfter, 5);
    return true;
  });
});

test("429: Retry-After가 HTTP-date면 값을 만들지 않는다", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () =>
      new Response(JSON.stringify({ error: { name: "too_many_requests" } }), {
        status: 429,
        headers: { "Content-Type": "application/json", "Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT" },
      }),
  });
  await assert.rejects(ds.getMarkets(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    // 없는 값을 지어내지 않는다. 안내는 "잠시 후"로 열화한다
    assert.equal(e.retryAfter, null);
    return true;
  });
});

test("429: 위반 버킷 detail은 보존한다(표시·로깅 전용, 분기 금지)", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () =>
      jsonRes(429, {
        error: { name: "too_many_requests", detail: { bucket: "node", scope: "query" }, retry_after: 1 },
      }),
  });
  await assert.rejects(ds.getMarkets(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.deepEqual(e.detail, { bucket: "node", scope: "query" });
    return true;
  });
});

test("에러 본문이 JSON이 아니면 상태코드 기반 기본 에러", async () => {
  const { ds } = newDs({
    "/api/v1/markets": () => new Response("bad gateway", { status: 502 }),
  });
  await assert.rejects(ds.getMarkets(), (e: ApiError) => e.code === "server_error" && e.status === 502);
});

/* ===== 원가 필드 (§4-C-1 v2.21) ===== */

test("원가 필드를 내지 않는 서버 응답은 원가 미상으로 읽는다", async () => {
  const { ds } = newDs({
    "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(15 * 60_000) }),
    "/api/v1/accounts": () =>
      jsonRes(200, {
        schema_version: "1.0",
        // v2.20 이하 응답. avg_buy_price만 있고 누산기 쌍이 없다
        data: [{ currency: "BTC", balance: "0.5", locked: "0", avg_buy_price: "48000000", unit_currency: "KRW" }],
        pagination: { next_cursor: null, has_more: false },
      }),
  });
  const [btc] = await ds.getAccounts();
  // 결측을 avg_buy_price 신뢰로 되돌리면 원가 없는 보유가 전액 수익으로 오표시된다
  assert.equal(btc.cost_basis_qty, "0", "판별자가 0이어야 화면이 산출 불가로 그린다");
  assert.equal(btc.cost_basis_amount, "0");
});

test("원가 필드를 내는 서버 응답은 값을 그대로 통과시킨다", async () => {
  const { ds } = newDs({
    "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(15 * 60_000) }),
    "/api/v1/accounts": () =>
      jsonRes(200, {
        schema_version: "1.0",
        data: [
          {
            currency: "BTC",
            balance: "0.493998",
            locked: "0.01",
            avg_buy_price: "50025013",
            unit_currency: "KRW",
            cost_basis_qty: "0.003998",
            cost_basis_amount: "200000",
          },
        ],
        pagination: { next_cursor: null, has_more: false },
      }),
  });
  const [btc] = await ds.getAccounts();
  assert.equal(btc.cost_basis_qty, "0.003998");
  assert.equal(btc.cost_basis_amount, "200000");
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

test("epoch 상승 후 새 리더 seq가 끊기지 않고 계속 전달된다", async () => {
  // 서버 채널 seq는 노드 인메모리 카운터라 리더가 바뀌면 1부터 다시 센다. 재동기화 스냅샷이
  // 구 리더 기준점보다 낮다고 버려지면 신 리더 실시간이 전부 드롭돼 채널이 영구 정지한다.
  // 호출 횟수가 아니라 전달된 seq 목록으로 스트림 재개를 고정한다
  const stomp = makeStomp();
  let restSeq = 99;
  const { ds } = newDs(
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
  const base = received.length;

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(100, 1)); // 구 리더 기준점
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(101, 1));
  await settle();

  restSeq = 3; // 리더 교체. 새 노드 카운터는 낮은 seq에서 시작한다
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(2, 2)); // epoch 상승 감지 → 전 채널 재동기화
  await settle();
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(4, 2));
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(5, 2));
  await settle();

  assert.deepEqual(
    received.slice(base).map((m) => m.seq),
    [100, 101, 3, 4, 5],
    "epoch 교체 후 새 도메인 스냅샷과 실시간이 이어져야 한다",
  );
});

test("epoch 교체 후 trade 채널은 기수신 체결을 재전달하지 않는다", async () => {
  // trade·candle의 event_seq는 전역 영속 도메인이라 리더 교체로 리셋되지 않는다(§5-2).
  // 기준점을 비우면 재동기화가 기수신 체결을 새 epoch로 재전달하고, 캔들 합성이 이중계상한다
  const stomp = makeStomp();
  const restTrades = [996, 997, 998, 999, 1000].map((seq) => ({
    trade_id: `t-${seq}`,
    market: "KRW-BTC",
    price: "50005000",
    volume: "0.1",
    taker_side: "bid",
    timestamp: "2026-07-21T09:00:01.000Z",
    seq,
  }));
  const { ds } = newDs(
    {
      "/api/v1/trades": () =>
        jsonRes(200, { schema_version: "1.0", data: restTrades, pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.trade("KRW-BTC"), (m) => received.push(m));
  await settle();

  stomp.push(dest.trade("KRW-BTC"), wsTrade(1000, 1)); // 기준점
  await settle();
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1001, 2)); // 리더 교체 감지 → 전 채널 재동기화
  await settle();

  assert.deepEqual(
    received.map((m) => m.seq),
    [1000, 1001],
    "기수신 체결(996~1000)이 다시 흐르면 캔들 volume이 이중계상된다",
  );
});

test("trade 재동기화는 스토어 테이프 상한만큼 재전달을 요청한다", async () => {
  // 재전달 상한이 테이프 상한보다 작으면 테이프를 가득 유실한 재연결에서 빈 자리가 남는다.
  // 상한 두 값이 한 상수를 공유하는지 요청 쿼리로 고정한다
  const stomp = makeStomp();
  const { ds, calls } = newDs(
    {
      "/api/v1/trades": () =>
        jsonRes(200, { schema_version: "1.0", data: [], pagination: { next_cursor: null, has_more: false } }),
    },
    stomp.factory,
  );
  ds.connect();
  stomp.state.args!.onConnect();
  ds.subscribe(dest.trade("KRW-BTC"), () => {});
  await settle();

  stomp.push(dest.trade("KRW-BTC"), wsTrade(1000, 1)); // 기준점
  await settle();
  stomp.push(dest.trade("KRW-BTC"), wsTrade(1001, 2)); // 리더 교체 감지 → 재동기화
  await settle();

  const resync = calls.filter((c) => c.url.includes("/api/v1/trades"));
  assert.ok(resync.length > 0, "재동기화가 trades를 조회해야 한다");
  for (const c of resync) {
    assert.equal(new URL(c.url).searchParams.get("count"), String(TRADE_TAPE_LIMIT));
  }
});

test("epoch 교체 후 구 세대 REST 스냅샷은 기준점이 되지 않는다", async () => {
  const stomp = makeStomp();
  const { ds } = newDs(
    {
      // 교체 직후 뒤처진 노드가 응답한다. 구 세대 스냅샷을 채택하면 신 리더 실시간이 전부 드롭된다
      "/api/v1/orderbook": () =>
        jsonRes(200, {
          schema_version: "1.0",
          market: "KRW-BTC",
          timestamp: "2026-07-21T09:00:00.123Z",
          epoch: 1,
          seq: 900,
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

  const ob = (seq: number, stream: "snapshot" | "realtime", epoch: number) => ({
    schema_version: "1.0",
    type: "orderbook",
    market: "KRW-BTC",
    epoch,
    seq,
    stream,
    timestamp: "2026-07-21T09:00:00.200Z",
    total_ask_size: "1",
    total_bid_size: "1",
    orderbook_units: [],
  });
  stomp.push(dest.orderbook("KRW-BTC"), ob(100, "snapshot", 1)); // 구 리더 기준점
  await settle();
  stomp.push(dest.orderbook("KRW-BTC"), ob(3, "realtime", 2)); // 리더 교체
  await settle();
  stomp.push(dest.orderbook("KRW-BTC"), ob(4, "realtime", 2));
  await settle();

  assert.deepEqual(
    received.map((m) => m.seq),
    [100, 3, 4],
    "구 세대 스냅샷(seq 900)을 채택하면 신 리더 실시간이 영구 드롭된다",
  );
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

test("flush: 스냅샷이 결번을 못 메워도 재동기화가 순환하지 않는다", async () => {
  const stomp = makeStomp();
  let tickerHits = 0;
  const fetchFn: typeof fetch = async (input) => {
    if (!String(input).includes("/api/v1/ticker")) {
      return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
    }
    tickerHits += 1;
    // REST가 WS보다 뒤처진 노드를 읽는 상태. 스냅샷 seq가 버퍼 head를 영영 못 따라잡는다
    return jsonRes(200, { schema_version: "1.0", data: [restTicker(19)], pagination: { next_cursor: null, has_more: false } });
  };
  const ds = new HttpStompDataSource({ apiBase: "http://api.test", wsBase: "ws://api.test", fetchFn, stompFactory: stomp.factory });
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.ticker("KRW-BTC"), (m) => received.push(m));
  await settle();
  const base = received.length;

  stomp.push(dest.ticker("KRW-BTC"), wsTicker(10)); // 기준점
  stomp.push(dest.ticker("KRW-BTC"), wsTicker(25)); // gap → 재동기화(스냅샷 19)
  await settle();

  // 상한만 걸면 재기동이 아예 없는 구현(초기 1 + 재동기화 1)도 통과한다. 판정 대상인 재기동이
  // 실제로 한 번 돌았다는 것을 왕복 수로 고정한다
  assert.equal(tickerHits, 3, "초기 스냅샷 1 + 재동기화 1 + 재기동 1. 재기동 상한은 1회다");
  assert.deepEqual(
    received.slice(base).map((m) => m.seq),
    [10, 19, 25],
    "상한을 넘긴 결번(25)은 수용해 스트림을 진행시키고, 같은 스냅샷(19)을 반복 전달하지 않는다",
  );
});

test("flush: 늦은 스냅샷 재전달은 기준점을 되돌리지 않는다", async () => {
  const stomp = makeStomp();
  const deferred: { resolve: ((r: Response) => void) | null } = { resolve: null };
  let obHits = 0;
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
  const fetchFn: typeof fetch = async (input) => {
    if (!String(input).includes("/api/v1/orderbook")) {
      return jsonRes(404, { error: { name: "order_not_found", message: "없음" } });
    }
    obHits += 1;
    return new Promise<Response>((resolve) => {
      deferred.resolve = resolve;
    });
  };
  const ds = new HttpStompDataSource({ apiBase: "http://api.test", wsBase: "ws://api.test", fetchFn, stompFactory: stomp.factory });
  ds.connect();
  stomp.state.args!.onConnect();
  const received: WsMessage[] = [];
  ds.subscribe(dest.orderbook("KRW-BTC"), (m) => received.push(m));
  await settle();

  stomp.push(dest.orderbook("KRW-BTC"), ob(100, "snapshot")); // 기준점
  stomp.push(dest.orderbook("KRW-BTC"), ob(103, "realtime")); // gap → 재동기화 시작
  await settle();
  stomp.push(dest.orderbook("KRW-BTC"), ob(95, "snapshot")); // 재전달된 늦은 스냅샷(버퍼링)
  await settle();
  assert.ok(deferred.resolve, "재동기화 fetch 발사됨");
  deferred.resolve(
    jsonRes(200, {
      schema_version: "1.0",
      market: "KRW-BTC",
      timestamp: "2026-07-21T09:00:00.300Z",
      epoch: 1,
      seq: 102,
      total_ask_size: "1",
      total_bid_size: "1",
      orderbook_units: [],
    }),
  );
  await settle();

  assert.deepEqual(
    received.map((m) => m.seq),
    [100, 102, 103],
    "늦은 스냅샷(95)은 드롭하고 결번 없이 이어진다",
  );
  assert.equal(obHits, 1, "스냅샷 재전달이 추가 재동기화를 부르지 않는다");
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
  const first = makeJwt(15 * 60_000);
  let reissued = "";
  const { ds, count } = newDs(
    {
      // 두 번째 발급은 시계를 옮긴 뒤 생성해야 그 시점 기준으로 신선하다
      "/api/v1/demo/session": (_c, hits) =>
        jsonRes(200, { access_token: hits === 1 ? first : (reissued = makeJwt(15 * 60_000)) }),
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
  stomp.state.args!.connectHeaders(); // 승격 연결(Bearer first)
  stomp.state.args!.onConnect();
  await settle();

  // 토큰 만료 시점으로 시계를 옮긴다. 재연결 CONNECT는 무인증이 된다
  const realNow = Date.now;
  Date.now = () => realNow() + 20 * 60_000;
  try {
    stomp.state.args!.onDisconnect();
    stomp.state.args!.connectHeaders();
    stomp.state.args!.onConnect();
    await settle();
    assert.equal(stomp.state.deactivations, 2, "재연결 경로에서 승격이 다시 발화해야 한다");
    const headers = stomp.state.args!.connectHeaders();
    assert.equal(headers["Authorization"], `Bearer ${reissued}`, "승격 후 CONNECT는 새 Bearer 첨부");
    stomp.state.args!.onConnect();
    await settle();
  } finally {
    Date.now = realNow;
  }

  assert.ok(stomp.state.subs.has(dest.myOrder), "승격 연결에서 비공개 채널 재구독");
  assert.ok(count("/api/v1/orders/open") >= 1, "승격 연결에서 비공개 채널 재동기화");
});

test("승격이 인증 CONNECT를 만들지 못하면 재발화를 멈춘다(자기 재생 차단)", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs(
    {
      // 발급 직후에도 만료로 보이는 토큰(시계 스큐). CONNECT는 계속 무인증이 된다
      "/api/v1/demo/session": () => jsonRes(200, { access_token: makeJwt(-1000) }),
      "/api/v1/orders/open": () => jsonRes(200, emptyList),
      "/api/v1/orders/closed": () => jsonRes(200, emptyList),
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
  assert.equal(stomp.state.deactivations, 1, "승격 시도 1회");

  stomp.state.args!.connectHeaders(); // 승격 연결이지만 여전히 무인증
  stomp.state.args!.onConnect();
  await settle();
  assert.equal(stomp.state.deactivations, 1, "승격 실패 후 재발화 없음");

  const tickerBefore = count("/api/v1/ticker");
  stomp.state.args!.onDisconnect();
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect(); // 외부 요인 재연결. 그동안 세션이 재발급됐다면 1회만 재시도한다
  await settle();
  assert.equal(stomp.state.deactivations, 2, "자격증명 갱신당 재시도는 1회로 유계다");
  assert.ok(stomp.state.subs.has(dest.ticker("KRW-BTC")), "공개 채널은 정상 재구독");
  assert.equal(count("/api/v1/ticker"), tickerBefore + 1, "공개 채널 재동기화가 멈추지 않는다");

  // 그 재시도의 결과 CONNECT도 무인증이면 다시 멈춘다. 스스로 재연결을 만들지 않는다
  stomp.state.args!.connectHeaders();
  stomp.state.args!.onConnect();
  await settle();
  assert.equal(stomp.state.deactivations, 2, "승격 결과 연결이 스스로 다음 승격을 부르지 않는다");
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
  stomp.state.args!.onConnect(); // 재동기화 1
  /* 여기서 한 번 흘려보내야 재동기화 1의 요청이 실제로 발사된다. ticker 조회는 같은 tick 의
     호출을 한 왕복으로 합치므로(fetchTickerCoalesced), 두 재연결을 동기로 붙이면 두 세대가
     한 요청으로 묶여 "이전 세대의 늦은 응답"이라는 상황 자체가 만들어지지 않는다.
     실제 플랩도 두 재연결 사이에 왕복이 걸쳐 있다 */
  await settle();
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

/* 부트스트랩 복원 규약(회원_설계 §부트스트랩 복원 규약).
   access 가 메모리 전용이라 새로고침마다 refresh -> /me 로 세션을 다시 세운다.
   이 사슬에서 "미로그인"과 "복원 실패"를 가르지 못하면, 유효한 rt 쿠키를 든 회원이
   데모 세션으로 폴백해 공유 데모 계정 잔고를 보게 된다. 사용자에게는 로그인이 풀린 것으로 보인다.
   두 호출에 같은 규칙이 걸리는지를 여기서 고정한다. */

test("복원: /me 429는 미로그인이 아니다. 사유를 올려 호출자가 판단하게 한다", async () => {
  const token = edJwt(900_000, "boot");
  const { ds } = newDs({
    "/api/v1/auth/refresh": () => jsonRes(200, { schema_version: "1.0", access_token: token }),
    "/api/v1/me": () => jsonRes(429, { error: { name: "too_many_requests", retry_after: 2 } }),
  });
  await assert.rejects(ds.refresh(), (e: unknown) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 429);
    // 종전에는 !res.ok 를 null 로 접어 이 정보가 통째로 사라졌다
    assert.equal(e.retryAfter, 2);
    return true;
  });
});

test("복원: /me 5xx도 미로그인이 아니다", async () => {
  const token = edJwt(900_000, "boot");
  const { ds } = newDs({
    "/api/v1/auth/refresh": () => jsonRes(200, { schema_version: "1.0", access_token: token }),
    "/api/v1/me": () => jsonRes(503, { error: { name: "service_unavailable" } }),
  });
  await assert.rejects(ds.refresh(), (e: unknown) => e instanceof ApiError && e.status === 503);
});

test("복원: /me 401·403만 미로그인으로 확정한다", async () => {
  for (const status of [401, 403]) {
    const token = edJwt(900_000, "boot");
    const { ds } = newDs({
      "/api/v1/auth/refresh": () => jsonRes(200, { schema_version: "1.0", access_token: token }),
      "/api/v1/me": () => jsonRes(status, { error: { name: "session_expired" } }),
    });
    const s = await ds.refresh();
    assert.equal(s, null, `${status}는 사용자 없음으로 확정한다`);
  }
});

test("복원: /me 200이면 회원 세션이 선다", async () => {
  const token = edJwt(900_000, "boot");
  const { ds } = newDs({
    "/api/v1/auth/refresh": () => jsonRes(200, { schema_version: "1.0", access_token: token }),
    "/api/v1/me": () => jsonRes(200, { schema_version: "1.0", user: MEMBER_USER }),
  });
  const s = await ds.refresh();
  assert.ok(s, "복원 성공");
  assert.equal(s.user.email, MEMBER_USER.email);
});

/* 복원 사슬 안에서만 재시도한다(회원_설계 §부트스트랩 복원 규약 v2.6).
   바깥에서 복원 전체를 다시 부르면 이미 성공한 POST /auth/refresh 까지 다시 쏘게 되고
   그것이 정본이 금지한 동시 회전이다. 두 재시도의 대상과 횟수를 여기서 고정한다. */

test("복원: /me 429는 1회 재시도한다. refresh는 다시 쏘지 않는다", async () => {
  const token = edJwt(900_000, "boot");
  let refreshHits = 0;
  let meHits = 0;
  const { ds } = newDs({
    "/api/v1/auth/refresh": () => {
      refreshHits += 1;
      return jsonRes(200, { schema_version: "1.0", access_token: token });
    },
    "/api/v1/me": () => {
      meHits += 1;
      return meHits === 1
        ? jsonRes(429, { error: { name: "too_many_requests", retry_after: 1 } })
        : jsonRes(200, { schema_version: "1.0", user: MEMBER_USER });
    },
  });
  const s = await ds.refresh();
  assert.ok(s, "재시도로 복원된다");
  assert.equal(meHits, 2, "/me 는 한 번 더 부른다");
  assert.equal(refreshHits, 1, "회전 엔드포인트는 다시 쏘지 않는다. 동시 회전을 스스로 만들지 않는다");
});

test("복원: /me 429가 두 번이면 포기한다. 상한은 1회다", async () => {
  const token = edJwt(900_000, "boot");
  let meHits = 0;
  const { ds } = newDs({
    "/api/v1/auth/refresh": () => jsonRes(200, { schema_version: "1.0", access_token: token }),
    "/api/v1/me": () => {
      meHits += 1;
      return jsonRes(429, { error: { name: "too_many_requests", retry_after: 1 } });
    },
  });
  await assert.rejects(ds.refresh(), (e: unknown) => e instanceof ApiError && e.status === 429);
  assert.equal(meHits, 2, "최초 + 재시도 1회");
});

test("복원: refresh 409는 1회 재시도한다. 승자의 새 쿠키로 성립한다", async () => {
  const token = edJwt(900_000, "boot");
  let refreshHits = 0;
  const { ds } = newDs({
    "/api/v1/auth/refresh": () => {
      refreshHits += 1;
      // 첫 호출은 동시 회전 패자. 두 번째는 승자가 심은 새 쿠키로 성공한다
      return refreshHits === 1
        ? jsonRes(409, { error: { name: "refresh_in_progress", retry_after: 1 } })
        : jsonRes(200, { schema_version: "1.0", access_token: token });
    },
    "/api/v1/me": () => jsonRes(200, { schema_version: "1.0", user: MEMBER_USER }),
  });
  const s = await ds.refresh();
  assert.ok(s, "경합 뒤 세션이 선다. 재시도가 없으면 데모로 폴백한다");
  assert.equal(refreshHits, 2);
});

test("복원: refresh 429는 재시도하지 않는다. 회전이 동시 회전을 만든다", async () => {
  let refreshHits = 0;
  const { ds } = newDs({
    "/api/v1/auth/refresh": () => {
      refreshHits += 1;
      return jsonRes(429, { error: { name: "too_many_requests", retry_after: 1 } });
    },
  });
  await assert.rejects(ds.refresh(), (e: unknown) => e instanceof ApiError && e.status === 429);
  assert.equal(refreshHits, 1, "회전 엔드포인트에 자동 재발사를 걸지 않는다");
});

/* ===== ticker 스냅샷 합류 (조회 수용 상한 준수) ===== */

/* 거래 화면은 전 마켓 시세를 한 번에 구독한다. 구독마다 왕복을 쓰면 마켓 수만큼
   요청이 같은 tick 에 나가고, 서버 조회 수용 상한(6, API 설계 2-5-a)을 화면 하나가
   스스로 넘겨 자기 요청을 429 로 만든다. 이 테스트가 고정하는 것은 왕복 수다.
   실측으로 동시 24건은 429 18건 200 6건이었다. 상한이 낮은 것이 아니라 화면이
   상한을 안 지킨 것이므로, 고치는 쪽은 클라이언트다 */
test("여러 마켓 ticker 구독은 한 왕복으로 합류한다", async () => {
  const markets = ["KRW-BTC", "KRW-ETH", "KRW-SOL", "KRW-XRP", "KRW-DOGE",
    "KRW-ADA", "KRW-TRX", "KRW-LINK", "KRW-DOT", "KRW-MATIC", "KRW-AVAX", "KRW-ATOM"];
  const { ds, count, calls } = newDs({
    "/api/v1/ticker": (call) => {
      const q = new URLSearchParams(call.url.split("?")[1] ?? "");
      const asked = (q.get("markets") ?? "").split(",").filter(Boolean);
      return jsonRes(200, {
        schema_version: "1.0",
        data: asked.map((m) => ({ ...restTicker(9), market: m })),
        pagination: { next_cursor: null, has_more: false },
      });
    },
  });

  const seen = new Map<string, number>();
  for (const m of markets) {
    ds.subscribe(dest.ticker(m), (msg) => {
      assert.equal(msg.type, "ticker");
      const mk = (msg as TickerMessage).market;
      seen.set(mk, (seen.get(mk) ?? 0) + 1);
    });
  }
  await settle();

  assert.equal(count("/api/v1/ticker"), 1, "12개 구독이 12왕복이 되면 상한 6을 넘긴다");
  const asked = new URLSearchParams(calls.at(-1)!.url.split("?")[1]).get("markets")!.split(",");
  assert.deepEqual(asked.sort(), [...markets].sort(), "합류가 마켓을 흘리지 않는다");
  // 구독당 스냅샷 1건이라는 성질은 유지된다. 합친 것은 왕복이지 전달이 아니다
  assert.equal(seen.size, markets.length);
  for (const m of markets) assert.equal(seen.get(m), 1, `${m} 스냅샷 1건`);
});

test("합류 중 한 구독이 풀려도 나머지 스냅샷은 도착한다", async () => {
  const { ds, count } = newDs({
    "/api/v1/ticker": (call) => {
      const q = new URLSearchParams(call.url.split("?")[1] ?? "");
      const asked = (q.get("markets") ?? "").split(",").filter(Boolean);
      return jsonRes(200, {
        schema_version: "1.0",
        data: asked.map((m) => ({ ...restTicker(9), market: m })),
        pagination: { next_cursor: null, has_more: false },
      });
    },
  });
  const got: string[] = [];
  const push = (m: WsMessage): void => { got.push((m as TickerMessage).market); };
  const off = ds.subscribe(dest.ticker("KRW-BTC"), push);
  ds.subscribe(dest.ticker("KRW-ETH"), push);
  off(); // 합류 요청이 나가기 전에 한쪽만 해제
  await settle();

  assert.equal(count("/api/v1/ticker"), 1);
  assert.deepEqual(got, ["KRW-ETH"], "풀린 구독에는 넘기지 않고 남은 구독은 받는다");
});

/* 팬아웃 지점이 둘이다. 구독 직후 초기 스냅샷과 재동기화. 재동기화는 리더 교체·재연결에서
   전 채널에 한꺼번에 걸리므로 초기 로드보다 폭이 크다. 한쪽만 합치면 다른 쪽이 상한을 넘긴다 */
test("재동기화도 같은 합류를 쓴다", async () => {
  const stomp = makeStomp();
  const { ds, count } = newDs({
    "/api/v1/ticker": (call) => {
      const q = new URLSearchParams(call.url.split("?")[1] ?? "");
      const asked = (q.get("markets") ?? "").split(",").filter(Boolean);
      return jsonRes(200, {
        schema_version: "1.0",
        data: asked.map((m) => ({ ...restTicker(50), market: m })),
        pagination: { next_cursor: null, has_more: false },
      });
    },
  }, stomp.factory);
  ds.connect();
  stomp.state.args!.onConnect();
  const markets = ["KRW-BTC", "KRW-ETH", "KRW-SOL", "KRW-XRP"];
  for (const m of markets) ds.subscribe(dest.ticker(m), () => {});
  await settle();
  const afterMount = count("/api/v1/ticker");

  // 리더 교체. 전 채널이 한꺼번에 재동기화한다
  stomp.state.args!.onConnect();
  await settle();

  const resyncCalls = count("/api/v1/ticker") - afterMount;
  // 상한만 걸면 재동기화가 아예 안 도는 상태(0 왕복)도 통과한다. 4채널이 한 왕복으로 합류한다는
  // 것이 계약이므로 왕복 수를 정확히 못박는다
  assert.equal(resyncCalls, 1, `재동기화가 ${resyncCalls} 왕복을 썼다. 채널마다 쏘면 상한을 넘기고 0이면 복원이 멈춘 것이다`);
});

/* 종전에는 구독 해제가 그 구독의 in-flight fetch 를 실제로 취소해 슬롯을 즉시 반환했다.
   합류 뒤에도 그 성질을 지켜야 하지만, 한 구독이 풀렸다고 남은 구독의 스냅샷까지
   끊으면 안 된다. 그래서 전원이 풀린 때만 끊는다. 계약이 둘이라 따로 판정한다.
   둘 다 요청이 실제로 발사된 것을 먼저 세운다. 안 나간 요청은 취소된 것이 아니다 */
test("합류 요청이 나간 뒤 한 구독만 풀려도 남은 구독의 스냅샷은 도착한다", async () => {
  // 클로저 대입은 TS 흐름 분석 밖이라 ref 객체로 관찰한다
  const captured: { signal: AbortSignal | null } = { signal: null };
  const deferred: { send: (() => void) | null } = { send: null };
  let tickerHits = 0;
  const fetchFn: typeof fetch = (input, init) => {
    const url = String(input);
    if (!url.includes("/api/v1/ticker")) {
      return Promise.resolve(jsonRes(404, { error: { name: "order_not_found", message: "없음" } }));
    }
    tickerHits += 1;
    captured.signal = init?.signal ?? null;
    const asked = (new URLSearchParams(url.split("?")[1] ?? "").get("markets") ?? "").split(",").filter(Boolean);
    return new Promise<Response>((resolve, reject) => {
      deferred.send = () =>
        resolve(
          jsonRes(200, {
            schema_version: "1.0",
            data: asked.map((m) => ({ ...restTicker(9), market: m })),
            pagination: { next_cursor: null, has_more: false },
          }),
        );
      init?.signal?.addEventListener("abort", () => reject(new DOMException("취소", "AbortError")));
    });
  };
  const ds = new HttpStompDataSource({
    apiBase: "http://api.test",
    wsBase: "ws://api.test",
    fetchFn,
    stompFactory: () => ({ activate: () => {}, deactivate: async () => {}, subscribe: () => () => {} }),
  });
  const got: string[] = [];
  const push = (m: WsMessage): void => { got.push((m as TickerMessage).market); };
  const offBtc = ds.subscribe(dest.ticker("KRW-BTC"), push);
  ds.subscribe(dest.ticker("KRW-ETH"), push);
  await settle();

  assert.equal(tickerHits, 1, "두 구독이 한 왕복으로 합류해 실제로 발사됐다");
  assert.ok(captured.signal, "합류 요청에 취소 signal이 걸려 있다");
  offBtc();
  assert.equal(captured.signal.aborted, false, "한쪽이 빠졌다고 남은 구독의 요청을 끊지 않는다");

  assert.ok(deferred.send, "응답 대기 중인 합류 요청이 있다");
  deferred.send();
  await settle();
  assert.deepEqual(got, ["KRW-ETH"], "풀린 구독에는 넘기지 않고 남은 구독은 받는다");
});

test("합류 구독 전원이 풀리면 in-flight 요청을 실제로 취소한다", async () => {
  const captured: { signal: AbortSignal | null } = { signal: null };
  let tickerHits = 0;
  const fetchFn: typeof fetch = (input, init) => {
    if (!String(input).includes("/api/v1/ticker")) {
      return Promise.resolve(jsonRes(404, { error: { name: "order_not_found", message: "없음" } }));
    }
    tickerHits += 1;
    captured.signal = init?.signal ?? null;
    // 응답을 주지 않는다. 취소만이 이 요청을 끝낼 수 있다
    return new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("취소", "AbortError")));
    });
  };
  const ds = new HttpStompDataSource({
    apiBase: "http://api.test",
    wsBase: "ws://api.test",
    fetchFn,
    stompFactory: () => ({ activate: () => {}, deactivate: async () => {}, subscribe: () => () => {} }),
  });
  const got: string[] = [];
  const push = (m: WsMessage): void => { got.push((m as TickerMessage).market); };
  const offs = [
    ds.subscribe(dest.ticker("KRW-BTC"), push),
    ds.subscribe(dest.ticker("KRW-ETH"), push),
  ];
  await settle();

  assert.equal(tickerHits, 1, "취소를 판정하려면 요청이 먼저 나가 있어야 한다");
  assert.ok(captured.signal, "합류 요청에 취소 signal이 걸려 있다");
  offs[0]();
  assert.equal(captured.signal.aborted, false, "일부만 빠진 동안은 요청을 유지한다");
  offs[1]();
  assert.equal(captured.signal.aborted, true, "전원이 빠지면 요청을 끊어 조회 슬롯을 즉시 반환한다");
  await settle();
  assert.deepEqual(got, [], "취소된 합류는 어느 구독에도 스냅샷을 넘기지 않는다");
});

/* ===== 재접속 지터 (증거 ws-storm-20260907) ===== */

test("재접속 지연은 기준의 0.5배에서 1.5배 사이다", () => {
  assert.equal(jitteredReconnectDelayMs(() => 0), WS_RECONNECT_BASE_MS * 0.5);
  assert.equal(jitteredReconnectDelayMs(() => 0.5), WS_RECONNECT_BASE_MS);
  assert.equal(jitteredReconnectDelayMs(() => 1), WS_RECONNECT_BASE_MS * 1.5);
});

test("서로 다른 난수는 서로 다른 지연을 낸다", () => {
  /* 무리를 흩는 것이 목적이라 값이 갈리는 것 자체가 계약이다. 고정 2초였을 때 거절당한
     전원이 같은 순간에 되돌아왔다(실측 초당 785건). */
  const draws = new Set<number>();
  for (let i = 0; i < 100; i++) {
    draws.add(jitteredReconnectDelayMs(() => i / 100));
  }
  assert.ok(draws.size > 50, `출발점이 충분히 갈려야 한다. 실제 ${draws.size}종`);
});

test("지연은 상한 아래에서 시작한다", () => {
  /* 출발점이 상한을 넘으면 지수 백오프가 첫 시도부터 상한에 붙어 백오프가 무의미해진다. */
  assert.ok(jitteredReconnectDelayMs(() => 1) < WS_RECONNECT_MAX_MS);
});
