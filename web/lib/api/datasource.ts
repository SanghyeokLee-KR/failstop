/**
 * lib/api/datasource.ts. 데이터 소스 추상화.
 * 구현은 SimDataSource(클라이언트 시뮬레이터)와 HttpStompDataSource(Spring REST/STOMP) 둘.
 * NEXT_PUBLIC_DATASOURCE 환경 변수로 선택하며 기본은 sim(데모 안정성).
 * 메서드·채널 시그니처는 API 계약(§4, §5)을 그대로 미러한다.
 */

import type {
  Asset,
  Candle,
  CandleUnit,
  EffectConfig,
  LoginInput,
  Market,
  MemberSession,
  MemberUser,
  Order,
  OrderRequest,
  OrderbookMessage,
  SignupInput,
  Ticker,
  Trade,
  WsMessage,
} from "../types";
import { SimEngine } from "../sim/engine";
import { ApiError, HttpStompDataSource } from "./httpDatasource";

export interface DataSource {
  /* ===== REST 미러 (§4) =====
   * 조회 signal은 선택 취소 핸들. 화면 cleanup이 in-flight 요청을 실제로 끊어(결과 폐기가 아니라)
   * 빠른 마켓 전환 반복 시 미취소 요청 적체와 stale 응답 배달을 막는다. sim은 동기 해소라 무시한다. */
  getMarkets(): Promise<Market[]>;
  getTicker(markets: string[], signal?: AbortSignal): Promise<Ticker[]>;
  getOrderbook(market: string, signal?: AbortSignal): Promise<OrderbookMessage>;
  getTrades(market: string, count?: number, signal?: AbortSignal): Promise<Trade[]>;
  getCandles(market: string, unit: CandleUnit, count?: number, signal?: AbortSignal): Promise<Candle[]>;
  getAccounts(signal?: AbortSignal): Promise<Asset[]>;
  getOpenOrders(market?: string, signal?: AbortSignal): Promise<Order[]>;
  getClosedOrders(market?: string, signal?: AbortSignal): Promise<Order[]>;
  placeOrder(req: OrderRequest): Promise<Order>;
  cancelOrder(idOrClientId: string): Promise<Order>;

  /** 효과 강도 조회(§4-A-6). WS push 아님. 갱신은 폴링(useEffectConfigPolling). 실패는 어댑터가 폴백 흡수 */
  getEffectConfig(): Promise<EffectConfig>;

  /* ===== WS(STOMP) 미러 (§5) =====
   * destination은 §5-3 경로 문자열(/topic/ticker.KRW-BTC 등, lib/types의 dest 헬퍼 사용).
   * 반환값은 unsubscribe 함수. 구독 즉시 snapshot 1건 수신(§5-2 계약). */
  subscribe(destination: string, cb: (msg: WsMessage) => void): () => void;

  /** WS 연결 상태 통지. 등록 즉시 현재 상태 1회 통지. 반환값은 해제 함수 (§5.1 경미 확장) */
  onConnectionChange(cb: (connected: boolean) => void): () => void;

  connect(): void;
  disconnect(): void;

  /* ===== 회원 세션 (§3-8) =====
   * 로그인 성공 시 이후 인증 REST·WS는 회원 access를 Bearer로 자동 첨부한다.
   * 미로그인이면 데모 세션으로 폴백해 둘러보기가 그대로 동작한다(§4-1 병존). */

  /** 가입. 성공 시 세션을 내부 확립하고 onMemberSessionChange로 통지 */
  signup(input: SignupInput): Promise<MemberSession>;
  /** 로그인. 실패는 §3-4 에러코드(invalid_credentials·account_locked 등)로 throw */
  login(input: LoginInput): Promise<MemberSession>;
  /** 로그아웃. 서버 refresh family 폐기 시도 후 세션 해제 */
  logout(): Promise<void>;
  /** refresh 쿠키로 세션 복원(앱 로드·401 재시도). 없으면 null, 조용히 실패 */
  refresh(): Promise<MemberSession | null>;
  /** 회원 세션 사용자 로드(§3-8 GET /me) */
  getMe(): Promise<MemberUser>;
  getMemberSession(): MemberSession | null;
  /** 세션 변화 통지(로그인·갱신·로그아웃). 등록 즉시 현재 상태 1회 통지 */
  onMemberSessionChange(cb: (s: MemberSession | null) => void): () => void;
}

/** 데모 회원 로컬 흐름용 이메일·비밀번호 검증. 서버 MemberAuthService와 동일 경계(8~128자, 표시명 32자) */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LEN = 8;
const MAX_PASSWORD_LEN = 128;
const MAX_DISPLAY_NAME_LEN = 32;

function validateCredentials(email: string, password: string): void {
  if (!EMAIL_RE.test(email)) throw new ApiError(400, "invalid_email", "이메일 형식 확인 필요");
  if (password.length < MIN_PASSWORD_LEN || password.length > MAX_PASSWORD_LEN) {
    throw new ApiError(400, "weak_password", "비밀번호는 8자 이상 128자 이하");
  }
}

/** 시뮬레이터 기반 구현. 런타임 외부 의존 0 */
export class SimDataSource implements DataSource {
  constructor(private engine: SimEngine) {}

  /* ===== 회원 세션(데모 로컬 흐름) =====
   * 인메모리 레지스트리. 비밀번호는 저장소·로그로 나가지 않고 이 datasource 싱글턴 수명 동안만
   * 메모리에 둔다(로그아웃해도 members는 잔존, 재로그인 검증용). 새로고침 시 싱글턴이 재생성돼
   * 레지스트리·세션이 함께 소멸한다(데모 한계, §4-5). */
  private members = new Map<string, { password: string; user: MemberUser }>();
  private memberSession: MemberSession | null = null;
  private memberListeners = new Set<(s: MemberSession | null) => void>();

  private emitMember(): void {
    for (const cb of this.memberListeners) cb(this.memberSession);
  }

  private establish(user: MemberUser): MemberSession {
    // 데모 access는 검증 대상이 아니라 존재 표식일 뿐. 실 서명은 http 경로에서만 발생
    this.memberSession = { user, accessToken: `sim.${user.external_id}` };
    this.emitMember();
    return this.memberSession;
  }

  async signup(input: SignupInput): Promise<MemberSession> {
    validateCredentials(input.email, input.password);
    // 서버(MemberAuthService)와 동일한 표시명 상한. sim에서만 성공하는 입력을 만들지 않는다
    if (input.display_name.trim().length > MAX_DISPLAY_NAME_LEN) {
      throw new ApiError(400, "invalid_parameter", "표시명은 최대 32자");
    }
    const email = input.email.toLowerCase();
    if (this.members.has(email)) throw new ApiError(409, "email_taken", "이미 가입된 이메일");
    const user: MemberUser = {
      external_id: crypto.randomUUID(),
      email,
      display_name: input.display_name.trim() || email.split("@")[0],
      kind: "HUMAN",
    };
    this.members.set(email, { password: input.password, user });
    return this.establish(user);
  }

  async login(input: LoginInput): Promise<MemberSession> {
    const email = input.email.toLowerCase();
    const found = this.members.get(email);
    // 존재 여부 비노출: 미가입·오비밀번호를 동일 코드로 처리(§3-4)
    if (!found || found.password !== input.password) {
      throw new ApiError(401, "invalid_credentials", "이메일 또는 비밀번호 확인 필요");
    }
    return this.establish(found.user);
  }

  async logout(): Promise<void> {
    this.memberSession = null;
    this.emitMember();
  }

  async refresh(): Promise<MemberSession | null> {
    return this.memberSession;
  }

  async getMe(): Promise<MemberUser> {
    if (!this.memberSession) throw new ApiError(401, "session_expired", "세션 만료. 다시 로그인 필요");
    return this.memberSession.user;
  }

  getMemberSession(): MemberSession | null {
    return this.memberSession;
  }

  onMemberSessionChange(cb: (s: MemberSession | null) => void): () => void {
    this.memberListeners.add(cb);
    cb(this.memberSession);
    return () => this.memberListeners.delete(cb);
  }

  async getMarkets(): Promise<Market[]> { return this.engine.getMarkets(); }
  async getTicker(markets: string[]): Promise<Ticker[]> { return this.engine.getTicker(markets); }
  async getOrderbook(market: string): Promise<OrderbookMessage> { return this.engine.getOrderbook(market); }
  async getTrades(market: string, count = 30): Promise<Trade[]> { return this.engine.getTrades(market, count); }
  async getCandles(market: string, unit: CandleUnit, count = 120): Promise<Candle[]> {
    return this.engine.getCandles(market, unit, count);
  }
  async getAccounts(): Promise<Asset[]> { return this.engine.getAccounts(); }
  async getOpenOrders(market?: string): Promise<Order[]> { return this.engine.getOrders(true, market); }
  async getClosedOrders(market?: string): Promise<Order[]> { return this.engine.getOrders(false, market); }
  async placeOrder(req: OrderRequest): Promise<Order> { return this.engine.placeOrder(req); }
  async cancelOrder(idOrClientId: string): Promise<Order> { return this.engine.cancelOrder(idOrClientId); }
  async getEffectConfig(): Promise<EffectConfig> { return this.engine.getEffectConfig(); }

  subscribe(destination: string, cb: (msg: WsMessage) => void): () => void {
    return this.engine.subscribe(destination, cb);
  }

  onConnectionChange(cb: (connected: boolean) => void): () => void {
    cb(true); // 시뮬레이터는 네트워크 단절이 없어 항상 연결 상태
    return () => {};
  }

  connect(): void { this.engine.start(); }
  disconnect(): void { this.engine.stop(); }
}

/* ===== 싱글턴 (클라이언트 전용) ===== */

let singleton: DataSource | null = null;

/** NEXT_PUBLIC_DATASOURCE=http면 실서버 어댑터, 그 외(기본)는 시뮬레이터 (웹_화면명세서 §6.1) */
function createDataSource(): DataSource {
  if (process.env.NEXT_PUBLIC_DATASOURCE === "http") {
    return new HttpStompDataSource({
      apiBase: process.env.NEXT_PUBLIC_API_BASE ?? "",
      wsBase: process.env.NEXT_PUBLIC_WS_BASE ?? "",
    });
  }
  return new SimDataSource(new SimEngine());
}

export function getDataSource(): DataSource {
  if (!singleton) {
    singleton = createDataSource();
    if (typeof window !== "undefined") singleton.connect();
  }
  return singleton;
}
