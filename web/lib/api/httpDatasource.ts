/**
 * Spring 실서버 어댑터(REST fetch + STOMP). 금액·수량은 문자열 그대로 통과한다(parseFloat 금지).
 * 채널별 seq 단조 검사에서 gap이면 REST 재동기화, epoch 변경이면 전 채널 강제 재동기화다(§5-2).
 * 데모 세션 단기 JWT는 메모리에만 두고 만료·401 시 재발급 후 1회 재시도한다(§3-7).
 */

import { Client, TickerStrategy, type IMessage } from "@stomp/stompjs";
import type {
  Page,
  Asset,
  Candle,
  CandleUnit,
  CandleMessage,
  EffectConfig,
  EffectLevel,
  LoginInput,
  Market,
  MemberSession,
  MemberUser,
  MyAssetMessage,
  MyOrderMessage,
  Order,
  OrderRequest,
  OrderbookMessage,
  OrderbookSnapshot,
  SignupInput,
  Ticker,
  TickerMessage,
  Trade,
  TradeMessage,
  WsMessage,
  PublicNotice,
  SupportInput,
  SupportInquiry,
  TransferInput,
  TransferRequest,
} from "../types";
import { DEFAULT_EFFECT_CONFIG, SCHEMA_VERSION, TRADE_TAPE_LIMIT } from "../types";
import { decimalsOf, fromScaledInt, scaledIntTrunc } from "../decimal";
import type { DataSource } from "./datasource";

/** 효과 강도 공개 조회 경로(§4-A-6). 무인증 화이트리스트 조회 */
const EFFECT_CONFIG_PATH = "/api/v1/exchange-config";
const EFFECT_LEVELS: EffectLevel[] = ["off", "subtle", "normal", "strong"];

/**
 * trade_flash=auto는 UI 자체 관측 파생 지시이고, 그 외 열거값은 관리자 고정 강도다(§4-A-6).
 * 누락·비정상 값은 DEFAULT_EFFECT_CONFIG로 폴백한다.
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

const GENERIC_ERROR_MESSAGE = "요청 실패. 잠시 후 다시 시도 필요";

/**
 * 호출측 취소(AbortSignal)는 흐름 제어라 원형을 보존한다.
 * 그 외에는 브라우저 원문이 토스트에 노출되지 않도록 표준 에러로 래핑한다(§2-3 한국어 메시지 계약).
 */
function wrapNetworkError(e: unknown): never {
  if ((e as { name?: string } | null)?.name === "AbortError") throw e;
  throw new ApiError(0, "network_error", "네트워크 연결 실패. 잠시 후 다시 시도 필요");
}

interface ErrorEnvelope {
  error?: { name?: string; message?: string; retry_after?: number | null; detail?: unknown };
}

async function toApiError(res: {
  status: number;
  headers?: { get(name: string): string | null };
  json(): Promise<unknown>;
}): Promise<ApiError> {
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
  if (retryAfter === null) {
    // 바디 retry_after 부재 시 HTTP Retry-After 헤더를 폴백으로 읽는다(§2-3 423 account_locked).
    // 초 단위만 취하고, HTTP-date 형식이면 Number가 NaN이라 null 유지(안내는 "잠시 후"로 열화)
    const header = res.headers?.get("Retry-After");
    const sec = header != null ? Number(header) : Number.NaN;
    if (Number.isFinite(sec) && sec >= 0) retryAfter = sec;
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

/**
 * 원가 필드 미노출 서버를 만나면 원가 미상으로 읽는다(§4-C-1). 결측을 0으로 채우면 화면이
 * 손익 산출 불가로 그리는 안전한 열화지만, avg_buy_price만 믿으면 전액 수익으로 오표시된다.
 */
function normalizeCostFields(a: Asset): Asset {
  if (typeof a.cost_basis_qty === "string" && typeof a.cost_basis_amount === "string") return a;
  return { ...a, cost_basis_qty: a.cost_basis_qty ?? "0", cost_basis_amount: a.cost_basis_amount ?? "0" };
}

/* ===== 데모 세션 (§3-7) ===== */

interface DemoSessionResponse {
  access_token?: string;
  user?: { external_id?: string; email?: string };
}

/** §3-8 가입·로그인·갱신 공통 응답. refresh는 user 없이 access_token만 준다 */
interface AuthResponse {
  access_token?: string;
  token_type?: string;
  expires_in?: number;
  user?: MemberUser;
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
/** 재동기화 REST 상한. never-settle REST가 채널을 영구 정지시키지 않도록 fetch를 취소한다 */
const RESYNC_TIMEOUT_MS = 8_000;

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
    // 은닉 탭에서 메인 스레드 setInterval이 스로틀되면 발신 하트비트가 굶어 서버가 연결을
    // 닫고, 재연결마다 전 채널 REST 재동기화 버스트가 반복된다. 웹워커 타이머는 스로틀
    // 대상이 아니라 은닉 중 절단 자체를 막는다. Worker 미지원 환경은 라이브러리가 interval로 폴백
    heartbeatStrategy: TickerStrategy.Worker,
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

/**
 * 인메모리 카운터로 실현되는 채널(§5-2). seq가 +1로 조밀해 결번이 실제 유실이고,
 * 리더 교체 시 카운터가 낮은 값에서 재시작한다. trade·candle은 전역 영속 event_seq라 여기 없다.
 */
const DENSE_KINDS = new Set(["orderbook", "ticker", "myOrder", "myAsset"]);

// 외부 주문 id는 UUID(§7-1, §4-B-1 v2.7 정정. 이전 판 ULID 표기는 오기). 정규 라우트 판정용
const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** 호가 잔량 합산(문자열 십진, float 금지). 최대 소수 자릿수로 스케일을 맞춰 BigInt 누적한다 */
function sumUnitSizes(units: Array<{ ask_size?: string; bid_size?: string }>, key: "ask_size" | "bid_size"): string {
  let scale = 0;
  for (const u of units) scale = Math.max(scale, decimalsOf(u[key] ?? "0"));
  let acc = 0n;
  for (const u of units) acc += scaledIntTrunc(u[key] ?? "0", scale);
  return fromScaledInt(acc, scale);
}

/**
 * §5-4 orderbook 문면에는 total_ask_size·total_bid_size가 없다(REST §4-A-2 한정).
 * 문면 준수 서버가 생략하면 총잔량이 0으로 열화되므로 와이어 경계에서 orderbook_units 합산으로 채운다.
 */
function ensureOrderbookTotals(msg: OrderbookMessage): void {
  const m = msg as OrderbookMessage & { total_ask_size?: string; total_bid_size?: string };
  if (m.total_ask_size != null && m.total_bid_size != null) return;
  const units = m.orderbook_units ?? [];
  if (m.total_ask_size == null) m.total_ask_size = sumUnitSizes(units, "ask_size");
  if (m.total_bid_size == null) m.total_bid_size = sumUnitSizes(units, "bid_size");
}

type Listener = (msg: WsMessage) => void;

interface ChannelState {
  // 콜백 참조가 아니라 구독 호출 단위로 계수한다. 같은 destination을 같은 콜백(예: store.apply)으로
  // 이중 구독하면 Set 병합으로 refcount가 소실돼, 한 컴포넌트 언마운트가 살아있는 다른 구독의 STOMP
  // 구독을 조기 철거한다(사일런트 프리즈). Map<콜백, 참조수>로 마지막 실사용자에서만 teardown 한다.
  subscribers: Map<Listener, number>;
  stompUnsub: (() => void) | null;
  /** 연속성 기준 seq. null이면 다음 realtime이 기준점(§5-2 비 orderbook 규약) */
  lastSeq: number | null;
  resyncing: boolean;
  /** 재동기화 중 도착분. 완료 후 seq 오름차순 flush */
  buffer: WsMessage[];
  /** 재동기화 세대. 플랩 재연결로 겹친 이전 실행이 기준점·버퍼를 되돌리지 못하게 최신 세대만 반영한다 */
  resyncGen: number;
  /** 진행 중 재동기화 REST 취소 핸들. 새 세대 시작 시 이전 in-flight fetch를 끊는다 */
  resyncAbort: AbortController | null;
  /** flush 결번으로 재동기화를 다시 건 횟수. 상한을 넘으면 수용하고 진행해 순환을 끊는다 */
  gapRearms: number;
}

/** flush 결번당 재동기화 재기동 상한. 스냅샷이 결번을 못 메우는 상태의 요청 폭주를 막는다 */
const MAX_GAP_REARMS = 1;

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
  /** 승격 재연결을 발사하고 그 CONNECT를 기다리는 중. 결과 판정 근거 */
  private authUpgradePending = false;
  /** 승격 재연결이 인증 CONNECT를 만들지 못했다. 같은 자격증명으로는 재발화하지 않는다(자기 재생 차단) */
  private authUpgradeGaveUp = false;
  /** 실패한 승격에 쓴 토큰. 다른 토큰을 확보하면 조건이 달라졌다고 보고 1회 더 시도한다 */
  private authUpgradeToken: string | null = null;

  private session: { token: string; expMs: number | null } | null = null;
  private sessionPromise: Promise<string> | null = null;

  /** 회원 세션(§3-8). access는 메모리만. refresh는 httpOnly 쿠키라 JS 미접근 */
  private member: { user: MemberUser; token: string; expMs: number | null } | null = null;
  private memberListeners = new Set<(s: MemberSession | null) => void>();
  private refreshPromise: Promise<MemberSession | null> | null = null;
  /** 직전 refresh 실패 후 재로그인(setMember) 전까지 refresh 재요청을 억제한다. 비로그인 401 반복 방지 */
  private refreshBlocked = false;
  /** 재연결 직렬화 체인. setMember·clearMember 근접 호출의 이중 활성을 막는다 */
  private reconnectChain: Promise<void> = Promise.resolve();

  private channels = new Map<string, ChannelState>();
  /** getMarkets가 캐시하는 백엔드 실제 마켓 집합. 티커 요청을 이 집합으로 좁혀 미등록 마켓 400 소음을 없앤다 */
  private knownMarkets: Set<string> | null = null;
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

  private async http<T>(path: string, init: RequestInit = {}, auth = false): Promise<T> {
    const isWrite = (init.method ?? "GET").toUpperCase() !== "GET";
    const run = async (token: string | null) => {
      const headers: Record<string, string> = {
        ...(init.body != null ? JSON_HEADERS : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      };
      try {
        return await this.fetchFn(`${this.apiBase}${path}`, { ...init, headers });
      } catch (e) {
        wrapNetworkError(e);
      }
    };
    // 회원 기원 쓰기는 refresh 실패 시 데모 계정으로 재집행하지 않는다. 데모 폴백은 §4-1
    // 둘러보기(읽기) 연속성 의도라, 회원 주문을 데모 Bearer로 집행하면 계약 위반이다.
    // 토큰 확보 후 회원이 사라졌으면 데모로 강등된 것이므로 session_expired로 표면화한다.
    const authorize = async (force: boolean): Promise<string> => {
      const wasMember = this.member !== null;
      const token = await this.ensureAuthToken(force);
      if (isWrite && wasMember && !this.member) {
        throw new ApiError(401, "session_expired", "세션 만료. 다시 로그인 필요");
      }
      return token;
    };
    let res = await run(auth ? await authorize(false) : null);
    if (auth && res.status === 401) {
      // 세션 만료 추정 → 회원이면 refresh, 아니면 데모 재발급 후 1회 재시도
      res = await run(await authorize(true));
    }
    if (!res.ok) throw await toApiError(res);
    return (await res.json()) as T;
  }

  private memberFresh(): boolean {
    if (!this.member) return false;
    return this.member.expMs === null || Date.now() < this.member.expMs - SESSION_SKEW_MS;
  }

  /** 인증 REST용 Bearer 결정. 회원 세션 우선, 없으면 데모 세션 폴백(§4-1 병존) */
  private async ensureAuthToken(force = false): Promise<string> {
    if (this.member) {
      if (!force && this.memberFresh()) return this.member.token;
      const restored = await this.refresh();
      if (restored) return restored.accessToken;
      // refresh 실패 시 refresh()가 회원 세션을 해제하고 통지했다. 데모로 폴백
    }
    return this.ensureSession(force);
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
    let res: Response;
    try {
      res = await this.fetchFn(`${this.apiBase}/api/v1/demo/session`, {
        method: "POST",
        headers: JSON_HEADERS,
      });
    } catch (e) {
      // http()와 동일 계약. 세션 발급 실패도 network_error 코드로 수렴한다
      wrapNetworkError(e);
    }
    if (!res.ok) throw await toApiError(res);
    const body = (await res.json()) as DemoSessionResponse;
    if (!body.access_token) throw new ApiError(res.status, "invalid_envelope", "데모 세션 응답에 access_token 없음");
    this.session = { token: body.access_token, expMs: decodeJwtExpMs(body.access_token) };
    // 새 자격증명은 승격 조건을 바꾼다. 직전 실패에 쓴 토큰과 다르면 포기 래치를 푼다.
    // 래치는 승격 결과 판정에서 다시 걸리므로, 재시도는 자격증명 갱신당 1회로 유계다
    if (this.authUpgradeToken !== null && this.authUpgradeToken !== body.access_token) {
      this.authUpgradeGaveUp = false;
      this.authUpgradeToken = null;
    }
    return body.access_token;
  }

  /* ===== REST 미러 (§4) ===== */

  async getMarkets(): Promise<Market[]> {
    const list = unwrapList<Market>(await this.http("/api/v1/markets"), "/markets");
    // 백엔드 실제 마켓 집합을 캐시한다. COIN_META(269종)와 달리 실서버는 일부만 제공하므로,
    // 티커 요청을 이 집합으로 좁혀 미등록 마켓의 400 응답을 애초에 발생시키지 않는다
    this.knownMarkets = new Set(list.map((m) => m.market));
    return list;
  }

  async getTicker(markets: string[], signal?: AbortSignal): Promise<Ticker[]> {
    if (markets.length === 0) return [];
    // 캐시된 마켓 집합이 있으면 미등록 마켓을 제외한다(400 억제). 미캐시(부트 초기)면 그대로 요청한다
    const known = this.knownMarkets;
    const wanted = known ? markets.filter((m) => known.has(m)) : markets;
    if (wanted.length === 0) return [];
    const q = new URLSearchParams({ markets: wanted.join(",") });
    return unwrapList<Ticker>(await this.http(`/api/v1/ticker?${q}`, { signal }), "/ticker");
  }

  async getOrderbook(market: string, signal?: AbortSignal): Promise<OrderbookMessage> {
    const q = new URLSearchParams({ market });
    const snap = await this.http<OrderbookSnapshot>(`/api/v1/orderbook?${q}`, { signal });
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

  async getTrades(market: string, count = 30, signal?: AbortSignal): Promise<Trade[]> {
    const q = new URLSearchParams({ market, count: String(count) });
    return unwrapList<Trade>(await this.http(`/api/v1/trades?${q}`, { signal }), "/trades");
  }

  async getCandles(market: string, unit: CandleUnit, count = 120, signal?: AbortSignal): Promise<Candle[]> {
    const q = new URLSearchParams({ market, count: String(count) });
    try {
      return unwrapList<Candle>(await this.http(`/api/v1/candles/${unit}?${q}`, { signal }), "/candles");
    } catch (e) {
      // 캔들은 표시 전용 피드라 조회 실패를 빈 배열로 흡수해 화면이 빈 상태를 그리게 한다.
      // 무진단 흡수는 지속 5xx·스키마 드리프트를 묻으므로 흡수 지점에 진단 로그를 남기고,
      // invalid_envelope는 백엔드 스키마 결함이라 예상 범위인 조회 실패와 분리해 error로 올린다.
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

  async getAccounts(signal?: AbortSignal): Promise<Asset[]> {
    const raw = unwrapList<Asset>(await this.http("/api/v1/accounts", { signal }, true), "/accounts");
    return raw.map(normalizeCostFields);
  }

  async getOpenOrders(market?: string, signal?: AbortSignal): Promise<Order[]> {
    const q = market ? `?${new URLSearchParams({ market })}` : "";
    return unwrapList<Order>(await this.http(`/api/v1/orders/open${q}`, { signal }, true), "/orders/open");
  }

  async getClosedOrders(opts: { market?: string; cursor?: string | null; limit?: number } = {},
                        signal?: AbortSignal): Promise<Page<Order>> {
    const q = new URLSearchParams();
    if (opts.market) q.set("market", opts.market);
    if (opts.limit) q.set("count", String(opts.limit));
    if (opts.cursor) q.set("cursor", opts.cursor);
    const qs = q.toString();
    const body = await this.http<Page<Order> & { data: Order[] | null }>(
      `/api/v1/orders/closed${qs ? `?${qs}` : ""}`,
      { signal },
      true,
    );
    return { data: body.data ?? [], next_cursor: body.next_cursor ?? null, has_more: !!body.has_more };
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
      ch = {
        subscribers: new Map(),
        stompUnsub: null,
        lastSeq: null,
        resyncing: false,
        buffer: [],
        resyncGen: 0,
        resyncAbort: null,
        gapRearms: 0,
      };
      this.channels.set(destination, ch);
    }
    ch.subscribers.set(cb, (ch.subscribers.get(cb) ?? 0) + 1);
    if (this.stompConnected && !ch.stompUnsub) this.attachStomp(destination, ch);
    if (destination.startsWith("/user/") && this.canUpgradeAuth()) void this.upgradeAuth();
    // 구독 즉시 스냅샷 1건 계약. 서버 push는 orderbook 한정이라 ticker·myAsset은 REST로 합성한다.
    // 핸들별 컨트롤러로 unsubscribe가 in-flight 스냅샷 fetch를 실제로 취소한다(결과 폐기만으로는 요청이 큐를 점유한다)
    const snapshotAbort = new AbortController();
    void this.deliverInitialSnapshot(destination, cb, snapshotAbort.signal);
    // 핸들은 one-shot. done 없이 감산만 하면 같은 (destination, cb) 구독 2건 상태에서 첫 핸들의
    // 이중 호출이 살아있는 구독의 참조를 훔쳐 STOMP 구독을 조기 철거한다(사일런트 프리즈)
    let done = false;
    return () => {
      if (done) return;
      done = true;
      snapshotAbort.abort();
      const c = this.channels.get(destination);
      if (!c) return;
      const refs = c.subscribers.get(cb);
      if (refs === undefined) return; // 이미 해제된 핸들의 중복 호출은 무시
      if (refs <= 1) c.subscribers.delete(cb);
      else c.subscribers.set(cb, refs - 1);
      if (c.subscribers.size === 0) {
        c.resyncAbort?.abort();
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
          // 회원 세션이 있으면 회원 Bearer로 CONNECT해 /user 채널을 회원 스코프로 연다
          const token = this.memberFresh()
            ? this.member!.token
            : this.sessionFresh()
              ? this.session!.token
              : null;
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

  /* ===== 회원 세션 (§3-8) ===== */

  private emitMember(): void {
    const s = this.getMemberSession();
    for (const cb of this.memberListeners) cb(s);
  }

  private setMember(user: MemberUser, token: string): MemberSession {
    this.member = { user, token, expMs: decodeJwtExpMs(token) };
    // 성공 인증은 새 refresh 쿠키를 확립한다. 이후 만료 시 refresh를 다시 허용한다
    this.refreshBlocked = false;
    // 신원이 바뀌면 승격 조건도 바뀐다. 직전 승격 포기를 해제한다
    this.authUpgradeGaveUp = false;
    this.authUpgradeToken = null;
    this.emitMember();
    // /user 채널을 회원 Bearer로 다시 열기 위해 재연결(승격)
    this.reconnectWs();
    return { user, accessToken: token };
  }

  private clearMember(): void {
    if (!this.member) return;
    this.member = null;
    this.authUpgradeGaveUp = false;
    this.authUpgradeToken = null;
    this.emitMember();
    this.reconnectWs();
  }

  /** 세션 토큰이 바뀌면 CONNECT 헤더를 갱신하려 재연결한다(§5-6).
   * setMember·clearMember 근접 호출이 겹쳐 이중 활성·유휴 연결이 남지 않도록 단일 체인으로 직렬화한다 */
  private reconnectWs(): void {
    if (!this.stomp || !this.stompConnected) return;
    this.reconnectChain = this.reconnectChain
      .then(async () => {
        if (!this.stomp) return;
        await this.stomp.deactivate();
        this.stomp.activate();
      })
      .catch(() => {});
  }

  private async authPost(path: string, body?: unknown): Promise<AuthResponse> {
    let res: Response;
    try {
      res = await this.fetchFn(`${this.apiBase}${path}`, {
        method: "POST",
        headers: JSON_HEADERS,
        // refresh 쿠키 수신·송신을 위해 자격증명 포함(§4-5)
        credentials: "include",
        ...(body != null ? { body: JSON.stringify(body) } : {}),
      });
    } catch (e) {
      // http()와 동일 계약. 로그인·가입 실패 토스트에 브라우저 원문이 새지 않게 한다
      wrapNetworkError(e);
    }
    if (!res.ok) throw await toApiError(res);
    // 로그아웃은 본문 없는 204가 계약(§3-8). 빈 본문 json() 파싱이 정상 응답을 실패로 만들지 않게 한다
    if (res.status === 204) return {};
    return (await res.json()) as AuthResponse;
  }

  async signup(input: SignupInput): Promise<MemberSession> {
    const body = await this.authPost("/api/v1/auth/signup", input);
    if (!body.access_token || !body.user) {
      throw new ApiError(0, "invalid_envelope", "가입 응답에 세션 정보 없음");
    }
    return this.setMember(body.user, body.access_token);
  }

  async login(input: LoginInput): Promise<MemberSession> {
    const body = await this.authPost("/api/v1/auth/login", input);
    if (!body.access_token || !body.user) {
      throw new ApiError(0, "invalid_envelope", "로그인 응답에 세션 정보 없음");
    }
    return this.setMember(body.user, body.access_token);
  }

  async logout(): Promise<void> {
    try {
      await this.authPost("/api/v1/auth/logout");
    } catch {
      // 서버 폐기 실패라도 로컬 세션은 해제한다(재사용 토큰은 서버가 회전·탐지)
    }
    this.clearMember();
  }

  async refresh(): Promise<MemberSession | null> {
    // 동시 401·부트스트랩이 refresh를 중복 호출하지 않도록 단일 프라미스 공유
    if (this.refreshPromise) return this.refreshPromise;
    // 직전 refresh가 실패했고 재로그인이 없었으면 서버 왕복 없이 즉시 실패로 수렴한다.
    // 비로그인 상태의 부트스트랩·재구독·게시판 401이 auth/refresh를 반복 POST하던 소음을 없앤다
    if (this.refreshBlocked) return Promise.resolve(null);
    this.refreshPromise = this.doRefresh().finally(() => {
      this.refreshPromise = null;
    });
    return this.refreshPromise;
  }

  private async doRefresh(): Promise<MemberSession | null> {
    let token: string;
    try {
      const body = await this.authPost("/api/v1/auth/refresh");
      if (!body.access_token) throw new ApiError(0, "invalid_envelope", "갱신 응답에 access_token 없음");
      token = body.access_token;
    } catch (e) {
      // 401·403만 세션 종료로 수렴해 재로그인 전까지 재요청을 억제한다(§3-4).
      // 네트워크·5xx·429에서 member를 해제하면 유효 rt 쿠키를 갖고도 데모로 강등되므로 세션을 유지한다.
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        this.clearMember();
        this.refreshBlocked = true;
        return null;
      }
      throw e;
    }
    // refresh 응답은 user를 주지 않으므로 /me로 사용자 로드(§3-8)
    const user = this.member?.user ?? (await this.fetchMe(token));
    if (!user) {
      // 토큰 회전은 성공했으나 /me 일시 실패. 회전된 유효 쿠키를 버리지 않도록 억제하지 않는다.
      this.clearMember();
      return null;
    }
    return this.setMember(user, token);
  }

  private async fetchMe(token: string): Promise<MemberUser | null> {
    try {
      const res = await this.fetchFn(`${this.apiBase}/api/v1/me`, {
        headers: { Authorization: `Bearer ${token}` },
        credentials: "include",
      });
      if (!res.ok) return null;
      const body = (await res.json()) as { user?: MemberUser };
      return body.user ?? null;
    } catch {
      return null;
    }
  }

  async getMe(): Promise<MemberUser> {
    const token = await this.ensureAuthToken();
    const user = await this.fetchMe(token);
    if (!user) throw new ApiError(401, "session_expired", "세션 만료. 다시 로그인 필요");
    return user;
  }

  /** 공지(§4-F). 무인증 경로라 Bearer를 붙이지 않는다 */
  async getNotices(opts: { cursor?: string | null; limit?: number } = {}): Promise<Page<PublicNotice>> {
    const q = new URLSearchParams({ limit: String(opts.limit ?? 50) });
    if (opts.cursor) q.set("cursor", opts.cursor);
    const body = await this.http<Page<PublicNotice> & { data: PublicNotice[] | null }>(
      `/api/v1/notices?${q}`,
    );
    return { data: body.data ?? [], next_cursor: body.next_cursor ?? null, has_more: !!body.has_more };
  }

  /* ===== 문의 (§4-G) ===== */

  async createInquiry(input: SupportInput): Promise<SupportInquiry> {
    return this.http<SupportInquiry>(
      "/api/v1/support",
      { method: "POST", body: JSON.stringify(input) },
      true,
    );
  }

  async listInquiries(opts: { cursor?: string | null; limit?: number } = {}): Promise<Page<SupportInquiry>> {
    const q = new URLSearchParams({ limit: String(opts.limit ?? 50) });
    if (opts.cursor) q.set("cursor", opts.cursor);
    const body = await this.http<Page<SupportInquiry> & { data: SupportInquiry[] | null }>(
      `/api/v1/support?${q}`,
      {},
      true,
    );
    return { data: body.data ?? [], next_cursor: body.next_cursor ?? null, has_more: !!body.has_more };
  }

  /* ===== 입출금 (§4-E) ===== */

  /**
   * Idempotency-Key는 호출부가 소유한다. 재시도 시 같은 키를 재사용해야 서버
   * UNIQUE(user_id, idem_key)가 중복 요청을 하나로 접는다(주문 client_order_id와 같은 소유 규칙, §2-4).
   */
  async requestTransfer(input: TransferInput, idemKey: string): Promise<TransferRequest> {
    return this.http<TransferRequest>(
      "/api/v1/transfers",
      { method: "POST", body: JSON.stringify(input), headers: { "Idempotency-Key": idemKey } },
      true,
    );
  }

  async listTransfers(opts: { cursor?: string | null; limit?: number } = {}): Promise<Page<TransferRequest>> {
    const q = new URLSearchParams({ limit: String(opts.limit ?? 50) });
    if (opts.cursor) q.set("cursor", opts.cursor);
    const body = await this.http<Page<TransferRequest> & { data: TransferRequest[] | null }>(
      `/api/v1/transfers?${q}`,
      {},
      true,
    );
    return { data: body.data ?? [], next_cursor: body.next_cursor ?? null, has_more: !!body.has_more };
  }

  async cancelTransfer(externalId: string): Promise<TransferRequest> {
    return this.http<TransferRequest>(
      `/api/v1/transfers/${encodeURIComponent(externalId)}`,
      { method: "DELETE" },
      true,
    );
  }

  getMemberSession(): MemberSession | null {
    return this.member ? { user: this.member.user, accessToken: this.member.token } : null;
  }

  onMemberSessionChange(cb: (s: MemberSession | null) => void): () => void {
    this.memberListeners.add(cb);
    cb(this.getMemberSession());
    return () => {
      this.memberListeners.delete(cb);
    };
  }

  private handleConnect(): void {
    this.stompConnected = true;
    for (const cb of this.connListeners) cb(true);
    if (this.authUpgradePending) {
      this.authUpgradePending = false;
      // 승격 재연결인데도 여전히 무인증이면 그 조건에서는 승격이 성립하지 않는다(예: 시계 스큐로
      // 발급 직후 토큰이 만료로 보임). 재발화를 멈춰 재연결·세션 발급 폭주를 막는다.
      // 재로그인·로그아웃 등 신원 변화가 있으면 다시 허용한다
      this.authUpgradeGaveUp = !this.authOnConnect;
    }
    // 채널 복원은 연결마다 무조건 한다. 승격에 위임하면 승격이 좌초할 때 공개 채널까지
    // 무증상 정지한다(연결 통지는 이미 true라 배너도 뜨지 않는다)
    this.restoreChannels();
    // 토큰 만료 중 재연결은 무인증 CONNECT로 붙어 비공개 채널을 서버가 거절한다(§5-6).
    // 승격 트리거가 subscribe에만 있으면 재연결 경로는 영구 무인증으로 남는다
    if (this.canUpgradeAuth()) void this.upgradeAuth();
  }

  /** 비공개 채널을 구독 중인데 현재 연결이 무인증 CONNECT인지 */
  private needsAuthUpgrade(): boolean {
    if (this.authOnConnect) return false;
    for (const destination of this.channels.keys()) {
      if (destination.startsWith("/user/")) return true;
    }
    return false;
  }

  /** 승격을 지금 시도해도 되는지. 진행 중·직전 실패는 재발화하지 않는다 */
  private canUpgradeAuth(): boolean {
    return !this.authUpgrading && !this.authUpgradeGaveUp && this.needsAuthUpgrade();
  }

  /** 전 채널 재구독 + REST 재동기화(웹_화면명세서 §6.1-3). 연결 단절 구간의 유실을 메운다 */
  private restoreChannels(): void {
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
      // 회원 세션이 있으면 회원 토큰을, 없으면 데모 세션을 확보(§4-1 병존)
      const token = await this.ensureAuthToken();
      if (this.stomp && this.stompConnected && !this.authOnConnect) {
        // 다음 CONNECT가 이 승격의 결과다. 인증이 붙지 않으면 재발화를 멈춘다
        this.authUpgradePending = true;
        this.authUpgradeToken = token;
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

    // 와이어 경계 정규화. 버퍼링·flush 경로도 이 지점을 지나 한 번만 수행된다
    if (msg.type === "orderbook") ensureOrderbookTotals(msg);

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
    const parsed = parseDest(destination);
    if (parsed === null) {
      this.deliver(ch, msg);
      return;
    }

    if (ch.resyncing) {
      ch.buffer.push(msg);
      return;
    }

    if (msg.type === "orderbook" && msg.stream === "snapshot") {
      // 구독 즉시 snapshot이 연속성 기준점(§5-2, orderbook 한정).
      // 다만 기준점은 전진만 한다. 늦게 도착한 스냅샷이 기준점을 내리면 이미 반영한 실시간이
      // 낮은 seq로 되돌아 화면 호가가 과거 상태로 역행한다
      if (ch.lastSeq !== null && msg.seq <= ch.lastSeq) return;
      ch.lastSeq = msg.seq;
      this.deliver(ch, msg);
      return;
    }

    if (ch.lastSeq === null) {
      ch.lastSeq = msg.seq;
      this.deliver(ch, msg);
      return;
    }

    // trade·candle은 event_seq 도메인이라 채널 관점에서 seq가 +1로 조밀하지 않다(§4-A-3, §5-2).
    // +1 gap 게이트를 걸면 정상 증가마다 재동기화 churn이 나므로 strictly-increasing만 검사한다.
    if (parsed.kind === "trade" || parsed.kind === "candle") {
      if (msg.seq <= ch.lastSeq) return; // 중복·되돌이 드롭
      ch.lastSeq = msg.seq;
      this.deliver(ch, msg);
      return;
    }

    // orderbook·ticker는 채널당 연속(조밀 +1) 도메인이라 gap을 실제 유실로 보고 재동기화한다.
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
    // 콜백 단위로 1회씩 전달한다(refcount는 teardown 계수용, 전달 중복 아님)
    for (const cb of ch.subscribers.keys()) cb(msg);
  }

  private resyncAll(): void {
    for (const [destination, ch] of this.channels) {
      const kind = parseDest(destination)?.kind;
      // 리더 교체가 리셋하는 것은 인메모리 카운터 실현뿐이라 그 채널만 기준점을 비운다
      // (전진 가드는 동일 epoch 한정). trade·candle은 비우면 재동기화가 기수신 체결을 이중계상한다
      if (kind !== undefined && DENSE_KINDS.has(kind)) ch.lastSeq = null;
      // 진행 중 재동기화도 다시 건다. 구 리더 응답이 새 도메인의 기준점이 되면 안 된다
      this.startResync(destination, ch);
    }
  }

  private startResync(destination: string, ch: ChannelState, pending?: WsMessage | WsMessage[]): void {
    // 플랩 재연결(reconnectDelay 2s < REST 상한 8s)은 이전 재동기화가 미완인 채 다음을 시작한다.
    // 이전 in-flight를 취소하고 세대를 올려, 늦게 끝난 이전 실행이 lastSeq 회귀·버퍼 flush·
    // resyncing 해제로 최신 실행을 오염시키지 못하게 한다.
    ch.resyncAbort?.abort();
    const gen = ++ch.resyncGen;
    ch.resyncing = true;
    ch.buffer = pending === undefined ? [] : Array.isArray(pending) ? [...pending] : [pending];
    // REST가 영영 안 끝나면 finally 미실행 → resyncing 잔류 → 채널 영구 정지·버퍼 무한 증가.
    // 타임아웃으로 fetch를 취소해 프라미스를 확정 종료시킨다(catch로 수렴).
    const controller = new AbortController();
    ch.resyncAbort = controller;
    const timer = setTimeout(() => controller.abort(), RESYNC_TIMEOUT_MS);
    void this.resyncChannel(destination, ch, controller.signal, gen)
      .catch(() => {
        // 재동기화 실패·타임아웃 시 스트림 정지 방지: 기준점 초기화 후 realtime 재개
        if (ch.resyncGen === gen) ch.lastSeq = null;
      })
      .finally(() => {
        clearTimeout(timer);
        if (ch.resyncGen !== gen) return; // 세대 교체됨. flush·상태 정리는 최신 실행 소유
        ch.resyncAbort = null;
        const buffered = ch.buffer.sort((a, b) => a.seq - b.seq);
        ch.buffer = [];
        ch.resyncing = false;
        // flush도 실시간 분기와 같은 기준점 규약을 따른다. 조밀 채널에서 결번을 무검사로
        // 수용하면 재동기화가 메우려던 유실 구간이 그대로 화면에 반영된다
        const kind = parseDest(destination)?.kind;
        const dense = kind !== undefined && DENSE_KINDS.has(kind);
        for (let i = 0; i < buffered.length; i++) {
          const m = buffered[i];
          // 스냅샷은 연속성 검사 대상이 아니라 기준점 자체다(§5-2). 조밀 검사에서 제외한다
          const snapshot = m.type === "orderbook" && m.stream === "snapshot";
          if (ch.lastSeq !== null) {
            if (m.seq <= ch.lastSeq) continue; // 중복·역행 드롭
            if (!snapshot && dense && m.seq !== ch.lastSeq + 1 && ch.gapRearms < MAX_GAP_REARMS) {
              // 결번 확인. 수용을 멈추고 남은 버퍼를 물려 재동기화를 다시 건다.
              // 상한을 두는 이유는 스냅샷이 결번을 못 메우는 상태(REST가 다른 노드·캐시)에서
              // 재기동이 순환하며 요청 폭주를 만들기 때문이다. 상한 초과분은 수용하고 진행한다
              ch.gapRearms += 1;
              this.startResync(destination, ch, buffered.slice(i));
              return;
            }
          }
          ch.lastSeq = m.seq;
          this.deliver(ch, m);
        }
        ch.gapRearms = 0; // 결번 없이 소진. 다음 gap은 다시 상한만큼 시도한다
      });
  }

  /** 재동기화 실행이 여전히 최신 세대인지. await 재개 지점마다 검사해 stale 실행의 상태 변경을 차단한다 */
  private resyncFresh(ch: ChannelState, gen: number | undefined): boolean {
    return gen === undefined || ch.resyncGen === gen;
  }

  /** 채널 종류별 REST 스냅샷 재동기화(§5-2 gap 규칙). signal은 취소용, gen은 세대 검사용 */
  private async resyncChannel(destination: string, ch: ChannelState, signal?: AbortSignal, gen?: number): Promise<void> {
    const parsed = parseDest(destination);
    if (!parsed) return;
    const prevSeq = ch.lastSeq;

    switch (parsed.kind) {
      case "orderbook": {
        const snap = await this.getOrderbook(parsed.market, signal);
        if (!this.resyncFresh(ch, gen)) return;
        // 교체 직후 뒤처진 노드의 구 세대 스냅샷이 기준점이 되면 신 리더 실시간이 전부 드롭된다.
        // orderbook REST는 epoch를 싣는다(§4-A-2). ticker는 REST 계약(§4-A-4)에 epoch가 없어 같은 방어를 못 한다
        if (this.lastEpoch !== null && snap.epoch !== this.lastEpoch) return;
        // 기준점은 전진만 한다. REST가 WS보다 뒤처진 노드를 읽으면 기준점이 내려가
        // 이미 보여준 호가가 과거로 되돌고, 이어지는 실시간이 결번으로 재동기화를 부른다
        if (ch.lastSeq !== null && snap.seq <= ch.lastSeq) return;
        ch.lastSeq = snap.seq;
        this.deliver(ch, snap);
        return;
      }
      case "ticker": {
        const [t] = await this.getTicker([parsed.market], signal);
        if (!t || !this.resyncFresh(ch, gen)) return;
        if (ch.lastSeq !== null && t.seq <= ch.lastSeq) return; // 기준점 전진만
        ch.lastSeq = t.seq;
        this.deliver(ch, this.tickerToMessage(t, "snapshot"));
        return;
      }
      case "trade": {
        // trade는 REST 스냅샷과 WS 실시간이 같은 event_seq 도메인이라(§4-A-3, §5-2) prevSeq로 이미 본
        // 체결을 거르고 lastSeq를 REST seq로 올려도 단조성이 유지된다. 재전달 상한은 스토어 테이프
        // 상한과 같은 값이다. 이보다 작으면 테이프를 가득 유실한 재연결에서 빈 자리가 남는다
        const trades = await this.getTrades(parsed.market, TRADE_TAPE_LIMIT, signal);
        if (!this.resyncFresh(ch, gen)) return;
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
        // 예약 경로. 프론트는 /topic/candle.*를 구독하지 않고 candleStream이 체결로 캔들을 합성한다.
        // candle도 REST와 WS가 같은 event_seq 도메인이라(§5-2) 최신 캔들 seq를 기준점으로 삼는다.
        const [c] = await this.getCandles(parsed.market, parsed.unit, 1, signal);
        if (!c || !this.resyncFresh(ch, gen)) return;
        if (ch.lastSeq !== null && c.seq <= ch.lastSeq) return; // 기준점 전진만
        ch.lastSeq = c.seq;
        this.deliver(ch, this.candleToMessage(c, parsed.unit));
        return;
      }
      case "myOrder": {
        // myOrder 채널 seq 도메인은 REST에 없어 상태만 복원하고 기준점은 다음 realtime이 잡는다.
        // open만 복원하면 gap 구간 종결 통지가 유실되므로 closed도 재전달한다(§5-2 스냅샷 부재 보완).
        // 재동기는 끊긴 구간을 메우는 것이라 첫 쪽이면 충분하다
        const [open, closed] = await Promise.all([
          this.getOpenOrders(undefined, signal),
          this.getClosedOrders({}, signal),
        ]);
        if (!this.resyncFresh(ch, gen)) return;
        ch.lastSeq = null;
        for (const o of open) this.deliver(ch, this.orderToMyOrderMessage(o));
        for (const o of closed.data) this.deliver(ch, this.orderToMyOrderMessage(o));
        return;
      }
      case "myAsset": {
        const assets = await this.getAccounts(signal);
        if (!this.resyncFresh(ch, gen)) return;
        ch.lastSeq = null;
        this.deliver(ch, this.assetsToMessage(assets));
        return;
      }
    }
  }

  /** 신규 구독자 1명에게만 초기 스냅샷 전달 (SimDataSource 동작과 동일 계약). signal은 구독 핸들 해제 시 취소 */
  private async deliverInitialSnapshot(destination: string, cb: Listener, signal?: AbortSignal): Promise<void> {
    const parsed = parseDest(destination);
    if (!parsed) return;
    const alive = () => this.channels.get(destination)?.subscribers.has(cb) ?? false;
    try {
      if (parsed.kind === "ticker") {
        const [t] = await this.getTicker([parsed.market], signal);
        if (t && alive()) cb(this.tickerToMessage(t, "snapshot"));
      } else if (parsed.kind === "orderbook") {
        // 연결 상태면 STOMP 구독 즉시 서버 snapshot 1건이 연속성 기준점으로 도착한다(§5-2).
        // 그때 REST 스냅샷까지 보내면 느린 REST 응답이 이미 반영된 최신 실시간을 stale로 되돌린다.
        // REST 스냅샷은 미연결 시 초기 표시를 채우는 용도로만 쓴다.
        if (this.stompConnected) return;
        const snap = await this.getOrderbook(parsed.market, signal);
        if (alive()) cb(snap);
      } else if (parsed.kind === "myAsset") {
        const assets = await this.getAccounts(signal);
        // REST 왕복 중 realtime myAsset이 먼저 도착했으면(lastSeq 확립) 늦은 스냅샷이 체결 후
        // 잔액을 체결 전으로 되돌린다. store myAsset은 신선도 가드가 없어 여기서 폐기한다
        if (this.channels.get(destination)?.lastSeq != null) return;
        if (alive()) cb(this.assetsToMessage(assets));
      }
      // trade·candle·myOrder는 스냅샷 없음. 화면이 REST로 초기화(§5-2)
      // config는 WS 채널이 아니다(§5-3). 폴링으로 갱신(§4-A-6)
    } catch {
      // 초기 스냅샷 실패는 realtime 수신으로 수렴
    }
  }

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
      assets: assets.map((a) => ({
        currency: a.currency,
        balance: a.balance,
        locked: a.locked,
        avg_buy_price: a.avg_buy_price,
        cost_basis_qty: a.cost_basis_qty,
        cost_basis_amount: a.cost_basis_amount,
      })),
    };
  }
}
