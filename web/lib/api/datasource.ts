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
  PublicNotice,
  SupportInput,
  SupportInquiry,
  TransferInput,
  TransferRequest,
  WsMessage,
} from "../types";
import { SimEngine } from "../sim/engine";
import { emailError, passwordError } from "../auth/memberInputs";
import { compareDecimal } from "../decimal";
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

  /** 공지 목록(§4-F). 무인증 읽기. active인 공지만, 최신순 */
  getNotices(): Promise<PublicNotice[]>;

  /* ===== 문의 (§4-G). 회원 세션 전용 ===== */

  /** 문의 등록 */
  createInquiry(input: SupportInput): Promise<SupportInquiry>;
  /** 내 문의 목록. 최신순 */
  listInquiries(limit?: number): Promise<SupportInquiry[]>;

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

  /* ===== 입출금 (§4-E) =====
   * 회원 세션 전용이다. 데모 세션은 쓰기 자체가 막혀 있다(§3-7 읽기 전용). */

  /** 입출금 요청. idemKey는 재시도 시 같은 값을 재사용한다(주문 client_order_id 동형) */
  requestTransfer(input: TransferInput, idemKey: string): Promise<TransferRequest>;
  /** 내 입출금 내역. 최신순 */
  listTransfers(limit?: number): Promise<TransferRequest[]>;
  /** 출금 요청 취소. REQUESTED 상태만 가능하고 잠금이 풀린다 */
  cancelTransfer(externalId: string): Promise<TransferRequest>;
}

/**
 * 데모 회원 로컬 흐름용 검증.
 *
 * 자체 정규식을 두지 않고 lib/auth/memberInputs를 경유한다. 그 파일은 서버
 * MemberInputs.java의 미러다. sim이 느슨한 자체 규칙을 들면 sim에서 가입되는 계정이
 * http에서는 거절돼 데이터 소스만 바꿔도 동작이 갈린다. 여기 있던
 * /^[^\s@]+@[^\s@]+\.[^\s@]+$/는 실제로 a..b@x.com과 .a@x.com을 통과시켰다.
 */
const MAX_DISPLAY_NAME_LEN = 32;

function validateCredentials(email: string, password: string): void {
  const emailFail = emailError(email);
  if (emailFail) throw new ApiError(400, "invalid_email", emailFail);
  const passwordFail = passwordError(password);
  if (passwordFail) throw new ApiError(400, "weak_password", passwordFail);
}

/** sim 전용 입출금 행. __idem은 멱등 재생용이라 응답에서 뺀다 */
interface SimTransfer extends TransferRequest {
  __idem: string;
}

function stripIdem(row: SimTransfer): TransferRequest {
  const { __idem, ...rest } = row;
  return rest;
}

/**
 * sim 금액 검증. 서버 TransferInputs와 같은 규칙이다.
 * 여기가 느슨하면 sim에서 되는 입금이 http에서 400으로 막힌다.
 */
function normalizeTransferAmount(raw: string): string {
  const text = String(raw ?? "").trim();
  if (!text || !/^\d+(\.0+)?$/.test(text)) {
    throw new ApiError(400, "invalid_transfer_amount", "원화는 소수점 불가");
  }
  const whole = text.split(".")[0].replace(/^0+(?=\d)/, "");
  if (compareDecimal(whole, "0") <= 0) {
    throw new ApiError(400, "invalid_transfer_amount", "금액은 0보다 커야 함");
  }
  if (compareDecimal(whole, MAX_TRANSFER_KRW) > 0) {
    throw new ApiError(400, "invalid_transfer_amount", `1회 한도 ${MAX_TRANSFER_KRW}원 초과`);
  }
  return whole;
}

/** 서버 TransferInputs.MAX_TRANSFER_KRW 미러 */
export const MAX_TRANSFER_KRW = "100000000";

/** 시뮬레이터 기반 구현. 런타임 외부 의존 0 */
export class SimDataSource implements DataSource {
  constructor(private engine: SimEngine) {}

  /* ===== 회원 세션(데모 로컬 흐름) =====
   * 인메모리 레지스트리. 비밀번호는 저장소·로그로 나가지 않고 이 datasource 싱글턴 수명 동안만
   * 메모리에 둔다(로그아웃해도 members는 잔존, 재로그인 검증용). 새로고침 시 싱글턴이 재생성돼
   * 레지스트리·세션이 함께 소멸한다(데모 한계, §4-5). */
  private members = new Map<string, { password: string; user: MemberUser }>();
  private memberSession: MemberSession | null = null;
  /** 입출금 요청 로컬 저장소. 새로고침하면 사라진다(sim은 메모리 전용) */
  private transfers: SimTransfer[] = [];
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

  /* ===== 공지 (§4-F) ===== */

  /** 데모 공지 3종. 심각도별 전달 채널을 화면에서 확인할 수 있게 각 단계를 하나씩 둔다 */
  async getNotices(): Promise<PublicNotice[]> {
    const now = Date.now();
    return [
      {
        id: "sim-n1",
        title: "정기 점검 안내",
        body: "08-28 02:00~04:00 주문 접수 중단. 조회는 정상 동작",
        severity: "CRITICAL",
        published_at: now - 1000 * 60 * 40,
      },
      {
        id: "sim-n2",
        title: "체결 지연 가능성",
        body: "일부 구간에서 체결 반영이 수 초 지연될 수 있음. 주문은 정상 접수",
        severity: "WARNING",
        published_at: now - 1000 * 60 * 60 * 6,
      },
      {
        id: "sim-n3",
        title: "차트 지표 추가",
        body: "볼린저 밴드와 스토캐스틱을 차트 지표 메뉴에 추가",
        severity: "INFO",
        published_at: now - 1000 * 60 * 60 * 30,
      },
    ];
  }

  /* ===== 문의 (§4-G) ===== */

  /** 로컬 저장소. sim에는 운영자가 없어 답변은 관리 어댑터가 따로 든다(경계를 흐리지 않는다) */
  private inquiries: SupportInquiry[] = [];

  async createInquiry(input: SupportInput): Promise<SupportInquiry> {
    if (!this.memberSession) {
      throw new ApiError(403, "out_of_scope", "회원 세션 전용");
    }
    const title = String(input.title ?? "").replace(/s+/g, " ").trim();
    const body = String(input.body ?? "").trim();
    if (!title) throw new ApiError(400, "invalid_support_input", "제목 필요");
    if (body.length < 5) throw new ApiError(400, "invalid_support_input", "내용은 최소 5자");
    const row: SupportInquiry = {
      external_id: `sim-q-${Date.now().toString(36)}-${this.inquiries.length}`,
      category: input.category,
      title,
      body,
      status: "OPEN",
      answer: null,
      answered_at: null,
      created_at: Date.now(),
    };
    this.inquiries.unshift(row);
    return { ...row };
  }

  async listInquiries(limit = 50): Promise<SupportInquiry[]> {
    return this.inquiries.slice(0, limit).map((r) => ({ ...r }));
  }

  /* ===== 입출금 (§4-E) ===== */

  /**
   * 서버 상태 기계를 로컬에서 그대로 재현한다. 입금은 즉시 COMPLETED, 출금은 잠근 뒤
   * REQUESTED로 남는다. sim에는 운영자가 없으므로 출금 승인은 화면에서 확인할 수 없고,
   * 요청·잠금·취소까지가 재현 범위다. 그 경계를 흐리지 않으려고 자동 승인은 넣지 않는다.
   */
  async requestTransfer(input: TransferInput, idemKey: string): Promise<TransferRequest> {
    const key = String(idemKey ?? "").trim();
    if (!key) throw new ApiError(400, "invalid_parameter", "Idempotency-Key 필요");
    const replay = this.transfers.find((t) => t.__idem === key);
    if (replay) return stripIdem(replay);

    if (input.asset && input.asset !== "KRW") {
      throw new ApiError(400, "invalid_transfer_asset", "원화 입출금만 지원");
    }
    const amount = normalizeTransferAmount(input.amount);
    const now = Date.now();
    if (input.direction === "DEPOSIT") {
      this.engine.moveKrw(amount, "0");
    } else {
      if (compareDecimal(this.engine.availableKrw(), amount) < 0) {
        throw new ApiError(422, "insufficient_funds", "주문 가능 금액 초과");
      }
      // 가용에서 빼서 잠금으로. 서버 reserve_funds와 같은 이동이다
      this.engine.moveKrw(`-${amount}`, amount);
    }
    const row: SimTransfer = {
      __idem: key,
      external_id: `sim-${now.toString(36)}-${this.transfers.length}`,
      direction: input.direction,
      asset: "KRW",
      amount,
      status: input.direction === "DEPOSIT" ? "COMPLETED" : "REQUESTED",
      reason: null,
      created_at: now,
      updated_at: now,
    };
    this.transfers.unshift(row);
    return stripIdem(row);
  }

  async listTransfers(limit = 50): Promise<TransferRequest[]> {
    return this.transfers.slice(0, limit).map(stripIdem);
  }

  async cancelTransfer(externalId: string): Promise<TransferRequest> {
    const row = this.transfers.find((t) => t.external_id === externalId);
    if (!row) throw new ApiError(404, "not_found", "입출금 요청 없음");
    if (row.status !== "REQUESTED") {
      throw new ApiError(409, "transfer_not_pending", `이미 처리된 요청: ${row.status}`);
    }
    // 잠금에서 가용으로. 서버 release_funds와 같은 이동이다
    this.engine.moveKrw(row.amount, `-${row.amount}`);
    row.status = "CANCELED";
    row.reason = "사용자 취소";
    row.updated_at = Date.now();
    return stripIdem(row);
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
