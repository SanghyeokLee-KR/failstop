/**
 * lib/api/httpDatasource.ts. Spring 실서버 어댑터 (REST fetch + STOMP).
 * DataSource 인터페이스 구현. 화면 코드 무수정 원칙(웹_화면명세서 §6).
 * - REST: 엔벌로프 {data, pagination}(API §2-2), 표준 에러 {error:{name,...}}(§2-3) 파싱.
 *   금액·수량은 문자열 그대로 통과(NUMERIC(40,18), parseFloat 금지).
 * - STOMP: /ws/v1, 채널별 seq 단조 검사. gap 시 REST 재동기화, epoch 변경 시 전 채널
 *   강제 재동기화(§5-2). orderbook은 구독 즉시 서버 snapshot 1건이 연속성 기준점.
 * - 인증: 데모 세션(POST /api/v1/demo/session, §3-7) 단기 JWT를 메모리에만 보관.
 *   만료·401 시 재발급 후 1회 재시도. 비공개 채널은 CONNECT 헤더 Bearer.
 */

import { Client, type IMessage } from "@stomp/stompjs";
import type {
  Asset,
  Candle,
  CandleUnit,
  CandleMessage,
  EffectConfig,
  EffectLevel,
  Market,
  MyAssetMessage,
  MyOrderMessage,
  Order,
  OrderRequest,
  OrderbookMessage,
  OrderbookSnapshot,
  Ticker,
  TickerMessage,
  Trade,
  TradeMessage,
  WsMessage,
} from "../types";
import { DEFAULT_EFFECT_CONFIG, SCHEMA_VERSION } from "../types";
import type { DataSource } from "./datasource";

/** 효과 강도 공개 조회 경로(§4-A-6). 무인증 화이트리스트 조회 */
const EFFECT_CONFIG_PATH = "/api/v1/exchange-config";
const EFFECT_LEVELS: EffectLevel[] = ["off", "subtle", "normal", "strong"];

/**
 * §4-A-6 응답 {schema_version, trade_flash}을 EffectConfig로 좁힌다.
 * trade_flash=auto는 UI 자체 관측 파생 지시(mode:auto), 그 외 열거값은 관리자 고정 강도(mode:manual).
 * 누락·비정상 값은 안전 폴백(DEFAULT_EFFECT_CONFIG).
 */
function coerceEffectConfig(json: unknown): EffectConfig {
  const flash = (json as { trade_flash?: unknown } | null | undefined)?.trade_flash;
  if (flash === "auto") return { mode: "auto", level: "normal", schema_version: SCHEMA_VERSION };
  if (EFFECT_LEVELS.includes(flash as EffectLevel)) {
    return { mode: "manual", level: flash as EffectLevel, schema_version: SCHEMA_VERSION };
  }
  return DEFAULT_EFFECT_CONFIG;
}

/* ===== 표준 에러 (§2-3 미러. SimApiError와 동일하게 name에 코드 노출) ===== */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryAfter: number | null;
  readonly detail: unknown;

  constructor(status: number, code: string, message: string, retryAfter: number | null = null, detail: unknown = null) {
    super(message);
    this.name = code;
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
    this.detail = detail;
  }
}

const GENERIC_ERROR_MESSAGE = "요청에 실패했어요. 잠시 후 다시 시도해 주세요";

interface ErrorEnvelope {
  error?: { name?: string; message?: string; retry_after?: number | null; detail?: unknown };
}

async function toApiError(res: { status: number; json(): Promise<unknown> }): Promise<ApiError> {
  let name = res.status >= 500 ? "server_error" : "invalid_parameter";
  let message = GENERIC_ERROR_MESSAGE;
  let retryAfter: number | null = null;
  let detail: unknown = null;
  try {
    const body = (await res.json()) as ErrorEnvelope;
    if (body?.error?.name) {
      name = body.error.name;
      if (body.error.message) message = body.error.message;
      retryAfter = body.error.retry_after ?? null;
      detail = body.error.detail ?? null;
    }
  } catch {
    // 본문이 JSON이 아니면 상태코드 기반 기본값 유지
  }
  return new ApiError(res.status, name, message, retryAfter, detail);
}

/* ===== 리스트 엔벌로프 (§2-2) ===== */

interface ListEnvelope<T> {
  schema_version?: string;
  data?: T[];
}

function unwrapList<T>(json: unknown, path: string): T[] {
  const env = json as ListEnvelope<T>;
  if (!env || !Array.isArray(env.data)) {
    throw new ApiError(0, "invalid_envelope", `리스트 엔벌로프가 아닙니다: ${path}`);
  }
  return env.data;
}

/* ===== 데모 세션 (§3-7) ===== */

interface DemoSessionResponse {
  access_token?: string;
  user?: { external_id?: string; email?: string };
}

/** JWT payload의 exp(epoch s)를 ms로 추출. 파싱 실패 시 null(만료 판정 불가 → 401 재발급에 위임) */
function decodeJwtExpMs(token: string): number | null {
  try {
    const seg = token.split(".")[1] ?? "";
    const b64 = seg.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded)) as { exp?: unknown };
    return typeof payload.exp === "number" ? payload.exp * 1000 : null;
  } catch {
    return null;
  }
}

/** 만료 30초 전부터 재발급 대상으로 간주 (시계 오차·전송 지연 흡수) */
const SESSION_SKEW_MS = 30_000;

/* ===== STOMP 추상 (테스트 주입 지점) ===== */

export interface StompFactoryArgs {
  url: string;
  /** CONNECT 직전 호출. 세션 보유 시 Authorization Bearer 포함(§5-6) */
  connectHeaders: () => Record<string, string>;
  onConnect: () => void;
  onDisconnect: () => void;
}

export interface StompHandle {
  activate(): void;
  deactivate(): Promise<void>;
  subscribe(destination: string, onBody: (body: string) => void): () => void;
}

export type StompFactory = (args: StompFactoryArgs) => StompHandle;

function defaultStompFactory(args: StompFactoryArgs): StompHandle {
  const client = new Client({
    brokerURL: args.url,
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    beforeConnect: () => {
      // 재연결마다 최신 세션 토큰 반영
      client.connectHeaders = args.connectHeaders();
    },
    onConnect: () => args.onConnect(),
    onWebSocketClose: () => args.onDisconnect(),
  });
  return {
    activate: () => client.activate(),
    deactivate: () => client.deactivate(),
    subscribe: (destination, onBody) => {
      const sub = client.subscribe(destination, (frame: IMessage) => onBody(frame.body));
      return () => {
        try {
          sub.unsubscribe();
        } catch {
          // 연결 단절 이후의 해제는 무해
        }
      };
    },
  };
}

/* ===== destination 파서 (§5-3) ===== */

type ParsedDest =
  | { kind: "ticker" | "orderbook" | "trade"; market: string }
  | { kind: "candle"; unit: CandleUnit; market: string }
  | { kind: "myOrder" }
  | { kind: "myAsset" }
  | null;

function parseDest(destination: string): ParsedDest {
  if (destination === "/user/queue/myOrder") return { kind: "myOrder" };
  if (destination === "/user/queue/myAsset") return { kind: "myAsset" };
  const candle = /^\/topic\/candle\.([^.]+)\.(.+)$/.exec(destination);
  if (candle) return { kind: "candle", unit: candle[1] as CandleUnit, market: candle[2] };
  const pub = /^\/topic\/(ticker|orderbook|trade)\.(.+)$/.exec(destination);
  if (pub) return { kind: pub[1] as "ticker" | "orderbook" | "trade", market: pub[2] };
  return null;
}

// 외부 주문 id는 UUID(§7-1, §4-B-1 v2.7 정정. 이전 판 ULID 표기는 오기). 정규 라우트 판정용
const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/* ===== 채널 상태 ===== */

type Listener = (msg: WsMessage) => void;

interface ChannelState {
  subscribers: Set<Listener>;
  stompUnsub: (() => void) | null;
  /** 연속성 기준 seq. null이면 다음 realtime이 기준점(§5-2 비 orderbook 규약) */
  lastSeq: number | null;
  resyncing: boolean;
  /** 재동기화 중 도착분. 완료 후 seq 오름차순 flush */
  buffer: WsMessage[];
}

export interface HttpDataSourceOptions {
  apiBase: string;
  wsBase: string;
  fetchFn?: typeof fetch;
  stompFactory?: StompFactory;
}

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

export class HttpStompDataSource implements DataSource {
  private readonly apiBase: string;
  private readonly wsUrl: string;
  private readonly fetchFn: typeof fetch;
  private readonly stompFactory: StompFactory;

  private stomp: StompHandle | null = null;
  private stompConnected = false;
  /** 현재 STOMP 연결이 Bearer 포함 CONNECT였는지. 비공개 구독 시 재연결 판단 근거 */
  private authOnConnect = false;
  private authUpgrading = false;

  private session: { token: string; expMs: number | null } | null = null;
  private sessionPromise: Promise<string> | null = null;

  private channels = new Map<string, ChannelState>();
  private connListeners = new Set<(connected: boolean) => void>();
  /** 리더 세대(§5-2). 변경 감지 시 전 채널 강제 재동기화 */
  private lastEpoch: number | null = null;

  constructor(options: HttpDataSourceOptions) {
    this.apiBase = options.apiBase.replace(/\/+$/, "");
    const wsBase = (options.wsBase || options.apiBase.replace(/^http/, "ws")).replace(/\/+$/, "");
    this.wsUrl = wsBase.endsWith("/ws/v1") ? wsBase : `${wsBase}/ws/v1`;
    this.fetchFn = options.fetchFn ?? ((input, init) => fetch(input, init));
    this.stompFactory = options.stompFactory ?? defaultStompFactory;
  }

  /* ===== REST 코어 ===== */

  private async http<T>(path: string, init: RequestInit = {}, auth = false): Promise<T> {
    const run = async (token: string | null) => {
      const headers: Record<string, string> = {
        ...(init.body != null ? JSON_HEADERS : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      };
      return this.fetchFn(`${this.apiBase}${path}`, { ...init, headers });
    };
    let res = await run(auth ? await this.ensureSession() : null);
    if (auth && res.status === 401) {
      // 세션 만료 추정 → 재발급 후 1회 재시도
      res = await run(await this.ensureSession(true));
    }
    if (!res.ok) throw await toApiError(res);
    return (await res.json()) as T;
  }

  private sessionFresh(): boolean {
    if (!this.session) return false;
    return this.session.expMs === null || Date.now() < this.session.expMs - SESSION_SKEW_MS;
  }

  private ensureSession(force = false): Promise<string> {
    if (force) this.session = null;
    if (this.session && this.sessionFresh()) return Promise.resolve(this.session.token);
    if (!this.sessionPromise) {
      this.sessionPromise = this.issueSession().finally(() => {
        this.sessionPromise = null;
      });
    }
    return this.sessionPromise;
  }

  private async issueSession(): Promise<string> {
    const res = await this.fetchFn(`${this.apiBase}/api/v1/demo/session`, {
      method: "POST",
      headers: JSON_HEADERS,
    });
    if (!res.ok) throw await toApiError(res);
    const body = (await res.json()) as DemoSessionResponse;
    if (!body.access_token) throw new ApiError(res.status, "invalid_envelope", "데모 세션 응답에 access_token이 없습니다");
    this.session = { token: body.access_token, expMs: decodeJwtExpMs(body.access_token) };
    return body.access_token;
  }

  /* ===== REST 미러 (§4) ===== */

  async getMarkets(): Promise<Market[]> {
    return unwrapList<Market>(await this.http("/api/v1/markets"), "/markets");
  }

  async getTicker(markets: string[]): Promise<Ticker[]> {
    if (markets.length === 0) return [];
    const q = new URLSearchParams({ markets: markets.join(",") });
    return unwrapList<Ticker>(await this.http(`/api/v1/ticker?${q}`), "/ticker");
  }

  async getOrderbook(market: string): Promise<OrderbookMessage> {
    const q = new URLSearchParams({ market });
    const snap = await this.http<OrderbookSnapshot>(`/api/v1/orderbook?${q}`);
    // 단건 객체(§4-A-2) → WS 스키마 상의 snapshot 메시지로 변환
    return {
      schema_version: SCHEMA_VERSION,
      type: "orderbook",
      market: snap.market,
      stream: "snapshot",
      epoch: snap.epoch,
      seq: snap.seq,
      timestamp: snap.timestamp,
      total_ask_size: snap.total_ask_size,
      total_bid_size: snap.total_bid_size,
      orderbook_units: snap.orderbook_units,
    };
  }

  async getTrades(market: string, count = 30): Promise<Trade[]> {
    const q = new URLSearchParams({ market, count: String(count) });
    return unwrapList<Trade>(await this.http(`/api/v1/trades?${q}`), "/trades");
  }

  async getCandles(market: string, unit: CandleUnit, count = 120): Promise<Candle[]> {
    const q = new URLSearchParams({ market, count: String(count) });
    try {
      return unwrapList<Candle>(await this.http(`/api/v1/candles/${unit}?${q}`), "/candles");
    } catch (e) {
      // 캔들은 표시 전용 피드다. 미구현·조회 실패(4xx/5xx)·비정상 엔벌로프는 빈 배열로
      // 흡수해 화면이 에러 대신 빈 상태를 그리게 한다. 구현되면 정상 데이터로 전환된다.
      // 무진단 흡수는 지속 5xx·스키마 드리프트를 흔적 없이 묻으므로 흡수 지점에 진단
      // 로그를 남긴다(UX는 빈 상태 유지). invalid_envelope는 백엔드 스키마 결함이라
      // 예상 범위인 조회 실패와 분리해 error로 올린다.
      if (e instanceof ApiError) {
        const where = `candles ${unit} ${market}`;
        if (e.code === "invalid_envelope") {
          console.error(`[datasource] ${where} 엔벌로프 스키마 불일치. 빈 상태 렌더`, e);
        } else {
          console.warn(`[datasource] ${where} 조회 실패(${e.code}, status ${e.status}). 빈 상태 렌더`);
        }
        return [];
      }
      throw e;
    }
  }

  async getAccounts(): Promise<Asset[]> {
    return unwrapList<Asset>(await this.http("/api/v1/accounts", {}, true), "/accounts");
  }

  async getOpenOrders(market?: string): Promise<Order[]> {
    const q = market ? `?${new URLSearchParams({ market })}` : "";
    return unwrapList<Order>(await this.http(`/api/v1/orders/open${q}`, {}, true), "/orders/open");
  }

  async getClosedOrders(market?: string): Promise<Order[]> {
    const q = market ? `?${new URLSearchParams({ market })}` : "";
    return unwrapList<Order>(await this.http(`/api/v1/orders/closed${q}`, {}, true), "/orders/closed");
  }

  async placeOrder(req: OrderRequest): Promise<Order> {
    return this.http<Order>("/api/v1/orders", { method: "POST", body: JSON.stringify(req) }, true);
  }

  async cancelOrder(idOrClientId: string): Promise<Order> {
    // 화면은 order.id(UUID)를 넘긴다(§7-1). UUID면 정규 라우트로 취소한다.
    // by-client(§4-B-2)는 client_order_id를 명시적으로 넘기는 호출 전용이며 M1-5 백엔드
    // 미구현이라 의존하지 않는다. 정규 UUID 경로가 취소의 유일한 실동작 경로다.
    const path = UUID_RE.test(idOrClientId)
      ? `/api/v1/orders/${idOrClientId}`
      : `/api/v1/orders/by-client/${encodeURIComponent(idOrClientId)}`;
    return this.http<Order>(path, { method: "DELETE" }, true);
  }

  async getEffectConfig(): Promise<EffectConfig> {
    try {
      return coerceEffectConfig(await this.http(EFFECT_CONFIG_PATH));
    } catch {
      // config는 표시 강도라 비민감·비필수. 조회 실패는 안전 폴백으로 흡수(§4-2)
      return DEFAULT_EFFECT_CONFIG;
    }
  }

  /* ===== 구독 (§5) ===== */

  subscribe(destination: string, cb: Listener): () => void {
    let ch = this.channels.get(destination);
    if (!ch) {
      ch = { subscribers: new Set(), stompUnsub: null, lastSeq: null, resyncing: false, buffer: [] };
      this.channels.set(destination, ch);
    }
    ch.subscribers.add(cb);
    if (this.stompConnected && !ch.stompUnsub) this.attachStomp(destination, ch);
    if (destination.startsWith("/user/") && !this.authOnConnect) void this.upgradeAuth();
    // 구독 즉시 스냅샷 1건 계약(DataSource 계약). 서버 push는 orderbook 한정이라
    // ticker·myAsset은 REST로 합성해 신규 구독자에게만 전달한다
    void this.deliverInitialSnapshot(destination, cb);
    return () => {
      const c = this.channels.get(destination);
      if (!c) return;
      c.subscribers.delete(cb);
      if (c.subscribers.size === 0) {
        c.stompUnsub?.();
        this.channels.delete(destination);
      }
    };
  }

  onConnectionChange(cb: (connected: boolean) => void): () => void {
    this.connListeners.add(cb);
    cb(this.stompConnected);
    return () => {
      this.connListeners.delete(cb);
    };
  }

  connect(): void {
    if (!this.stomp) {
      this.stomp = this.stompFactory({
        url: this.wsUrl,
        connectHeaders: (): Record<string, string> => {
          const token = this.sessionFresh() ? this.session!.token : null;
          this.authOnConnect = token !== null;
          return token ? { Authorization: `Bearer ${token}` } : {};
        },
        onConnect: () => this.handleConnect(),
        onDisconnect: () => this.handleDisconnect(),
      });
    }
    this.stomp.activate();
  }

  disconnect(): void {
    this.handleDisconnect();
    void this.stomp?.deactivate();
  }

  /* ===== STOMP 내부 ===== */

  private handleConnect(): void {
    this.stompConnected = true;
    for (const cb of this.connListeners) cb(true);
    // 재연결 시 전 채널 재구독 + REST 재동기화(웹_화면명세서 §6.1-3)
    for (const [destination, ch] of this.channels) {
      ch.stompUnsub?.();
      this.attachStomp(destination, ch);
      ch.lastSeq = null;
      this.startResync(destination, ch);
    }
  }

  private handleDisconnect(): void {
    if (!this.stompConnected) return;
    this.stompConnected = false;
    for (const cb of this.connListeners) cb(false);
  }

  private attachStomp(destination: string, ch: ChannelState): void {
    if (!this.stomp) return;
    ch.stompUnsub = this.stomp.subscribe(destination, (body) => {
      let msg: WsMessage;
      try {
        msg = JSON.parse(body) as WsMessage;
      } catch {
        return;
      }
      this.handleMessage(destination, msg);
    });
  }

  /** 세션 확보 후, 무인증 CONNECT 상태면 재연결해 Bearer CONNECT로 승격 */
  private async upgradeAuth(): Promise<void> {
    if (this.authUpgrading) return;
    this.authUpgrading = true;
    try {
      await this.ensureSession();
      if (this.stomp && this.stompConnected && !this.authOnConnect) {
        await this.stomp.deactivate();
        this.stomp.activate();
      }
    } catch {
      // 세션 발급 실패 시 공개 채널은 유지. 비공개 구독은 서버가 거절(§5-6)
    } finally {
      this.authUpgrading = false;
    }
  }

  /* ===== seq/epoch 검사 (§5-2) ===== */

  private handleMessage(destination: string, msg: WsMessage): void {
    const ch = this.channels.get(destination);
    if (!ch) return;

    if (typeof msg.epoch === "number") {
      if (this.lastEpoch === null) {
        this.lastEpoch = msg.epoch;
      } else if (msg.epoch !== this.lastEpoch) {
        // 리더 세대 교체 → 전 채널 seq 도메인 재설정 가능 → 무조건 재동기화
        this.lastEpoch = msg.epoch;
        this.resyncAll();
      }
    }

    // seq 도메인이 없는 채널(§5-3 미분류, 예: config류)은 단조 seq·gap 검사를 우회하고 그대로 전달한다.
    // 이런 채널은 REST 재동기화 대상도 아니라 seq 규약을 적용하면 정상 메시지가 드롭될 수 있다.
    if (parseDest(destination) === null) {
      this.deliver(ch, msg);
      return;
    }

    if (ch.resyncing) {
      ch.buffer.push(msg);
      return;
    }

    if (msg.type === "orderbook" && msg.stream === "snapshot") {
      // 구독 즉시 snapshot이 연속성 기준점(§5-2, orderbook 한정)
      ch.lastSeq = msg.seq;
      this.deliver(ch, msg);
      return;
    }

    if (ch.lastSeq === null) {
      ch.lastSeq = msg.seq;
      this.deliver(ch, msg);
      return;
    }
    if (msg.seq <= ch.lastSeq) return; // 중복·역행 드롭
    if (msg.seq === ch.lastSeq + 1) {
      ch.lastSeq = msg.seq;
      this.deliver(ch, msg);
      return;
    }
    // gap → REST 재동기화. 현재 메시지는 buffer에 보관 후 스냅샷 뒤에 flush
    this.startResync(destination, ch, msg);
  }

  private deliver(ch: ChannelState, msg: WsMessage): void {
    for (const cb of ch.subscribers) cb(msg);
  }

  private resyncAll(): void {
    for (const [destination, ch] of this.channels) {
      if (!ch.resyncing) this.startResync(destination, ch);
    }
  }

  private startResync(destination: string, ch: ChannelState, pending?: WsMessage): void {
    ch.resyncing = true;
    ch.buffer = pending ? [pending] : [];
    void this.resyncChannel(destination, ch)
      .catch(() => {
        // 재동기화 실패 시 스트림 정지 방지: 기준점 초기화 후 realtime 재개
        ch.lastSeq = null;
      })
      .finally(() => {
        const buffered = ch.buffer.sort((a, b) => a.seq - b.seq);
        ch.buffer = [];
        ch.resyncing = false;
        for (const m of buffered) {
          if (ch.lastSeq !== null && m.seq <= ch.lastSeq) continue;
          ch.lastSeq = m.seq;
          this.deliver(ch, m);
        }
      });
  }

  /** 채널 종류별 REST 스냅샷 재동기화(§5-2 gap 규칙) */
  private async resyncChannel(destination: string, ch: ChannelState): Promise<void> {
    const parsed = parseDest(destination);
    if (!parsed) return;
    const prevSeq = ch.lastSeq;

    switch (parsed.kind) {
      case "orderbook": {
        const snap = await this.getOrderbook(parsed.market);
        ch.lastSeq = snap.seq;
        this.deliver(ch, snap);
        return;
      }
      case "ticker": {
        const [t] = await this.getTicker([parsed.market]);
        if (!t) return;
        ch.lastSeq = t.seq;
        this.deliver(ch, this.tickerToMessage(t, "snapshot"));
        return;
      }
      case "trade": {
        // 놓친 체결만 오래된 순으로 재전달 (REST seq는 WS 채널과 동일 도메인, §4-A-3)
        const trades = await this.getTrades(parsed.market, 50);
        const missed = trades
          .filter((t) => prevSeq === null || t.seq > prevSeq)
          .sort((a, b) => a.seq - b.seq);
        for (const t of missed) {
          ch.lastSeq = t.seq;
          this.deliver(ch, this.tradeToMessage(t));
        }
        return;
      }
      case "candle": {
        const [c] = await this.getCandles(parsed.market, parsed.unit, 1);
        if (!c) return;
        ch.lastSeq = c.seq;
        this.deliver(ch, this.candleToMessage(c, parsed.unit));
        return;
      }
      case "myOrder": {
        // 채널 seq 도메인(user×type)은 REST에 없음 → 상태만 복원, 기준점은 다음 realtime.
        // open만 복원하면 gap 구간에 종결된 주문의 종결 통지가 유실된다. closed도 함께
        // 재전달해 스토어가 open→closed 전이를 확정한다(§5-2 myOrder 스냅샷 부재 보완).
        const [open, closed] = await Promise.all([this.getOpenOrders(), this.getClosedOrders()]);
        ch.lastSeq = null;
        for (const o of open) this.deliver(ch, this.orderToMyOrderMessage(o));
        for (const o of closed) this.deliver(ch, this.orderToMyOrderMessage(o));
        return;
      }
      case "myAsset": {
        const assets = await this.getAccounts();
        ch.lastSeq = null;
        this.deliver(ch, this.assetsToMessage(assets));
        return;
      }
    }
  }

  /** 신규 구독자 1명에게만 초기 스냅샷 전달 (SimDataSource 동작과 동일 계약) */
  private async deliverInitialSnapshot(destination: string, cb: Listener): Promise<void> {
    const parsed = parseDest(destination);
    if (!parsed) return;
    const alive = () => this.channels.get(destination)?.subscribers.has(cb) ?? false;
    try {
      if (parsed.kind === "ticker") {
        const [t] = await this.getTicker([parsed.market]);
        if (t && alive()) cb(this.tickerToMessage(t, "snapshot"));
      } else if (parsed.kind === "orderbook") {
        const snap = await this.getOrderbook(parsed.market);
        if (alive()) cb(snap);
      } else if (parsed.kind === "myAsset") {
        const assets = await this.getAccounts();
        if (alive()) cb(this.assetsToMessage(assets));
      }
      // trade·candle·myOrder는 스냅샷 없음. 화면이 REST로 초기화(§5-2)
      // config는 WS 채널이 아니다(§5-3). 폴링으로 갱신(§4-A-6)
    } catch {
      // 초기 스냅샷 실패는 realtime 수신으로 수렴
    }
  }

  /* ===== 메시지 합성 ===== */

  private tickerToMessage(t: Ticker, stream: "snapshot" | "realtime"): TickerMessage {
    const { seq, timestamp, ...rest } = t;
    return {
      schema_version: SCHEMA_VERSION,
      type: "ticker",
      stream,
      epoch: this.lastEpoch ?? 0,
      seq,
      timestamp,
      ...rest,
    };
  }

  private tradeToMessage(t: Trade): TradeMessage {
    return {
      schema_version: SCHEMA_VERSION,
      type: "trade",
      market: t.market,
      epoch: this.lastEpoch ?? 0,
      seq: t.seq,
      timestamp: t.timestamp,
      trade_id: t.trade_id,
      price: t.price,
      volume: t.volume,
      taker_side: t.taker_side,
    };
  }

  private candleToMessage(c: Candle, unit: CandleUnit): CandleMessage {
    return {
      schema_version: SCHEMA_VERSION,
      type: "candle",
      unit,
      market: c.market,
      epoch: this.lastEpoch ?? 0,
      seq: c.seq,
      timestamp: c.candle_time,
      candle_time: c.candle_time,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
      volume: c.volume,
    };
  }

  private orderToMyOrderMessage(o: Order): MyOrderMessage {
    return {
      schema_version: SCHEMA_VERSION,
      type: "myOrder",
      epoch: o.epoch,
      seq: 0,
      timestamp: o.created_at,
      id: o.id,
      client_order_id: o.client_order_id,
      market: o.market,
      side: o.side,
      status: o.status,
      price: o.price,
      executed_volume: o.executed_volume,
      remaining_volume: o.remaining_volume,
      reject_reason: null,
      trade: null,
    };
  }

  private assetsToMessage(assets: Asset[]): MyAssetMessage {
    return {
      schema_version: SCHEMA_VERSION,
      type: "myAsset",
      epoch: this.lastEpoch ?? 0,
      seq: 0,
      timestamp: new Date().toISOString(), // UTC ISO 밀리초 3자리(§2-1)와 동일 형태
      assets: assets.map((a) => ({ currency: a.currency, balance: a.balance, locked: a.locked })),
    };
  }
}
