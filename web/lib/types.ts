/**
 * lib/types.ts. API 계약(docs/백엔드_API_설계.md v2.3)의 스키마 미러.
 * 금액/수량은 계약대로 "문자열 NUMERIC(40,18)" (JSON float 금지, §2-1).
 * WS 메시지는 §5-4/§5-5의 채널 스키마를 그대로 따른다 (ticker/orderbook/trade/candle/myOrder/myAsset).
 * 추후 실제 Spring REST/STOMP 어댑터로 교체 시 이 타입이 그대로 유지된다.
 * 계약 미러 외에, 스토어와 어댑터가 함께 지켜야 하는 클라이언트 공용 정책 상수도 여기 둔다.
 */

export const SCHEMA_VERSION = "1.0" as const;

/* ===== 공통 ===== */

export type Side = "bid" | "ask"; // bid=매수, ask=매도
export type OrdType = "limit" | "market" | "best";
export type TimeInForce = "GTC" | "IOC" | "FOK" | "POST_ONLY";
export type OrderStatus = "OPEN" | "PARTIAL" | "FILLED" | "CANCELED" | "REJECTED";
export type ChangeState = "RISE" | "FALL" | "EVEN";
export type MarketState = "ACTIVE" | "SUSPENDED";
export type CandleUnit =
  | "1s" | "1m" | "3m" | "5m" | "15m" | "30m" | "60m" | "240m" | "1d" | "1w";

/** UI 방향 도메인 (상승=Red / 하락=Blue / 보합=무채색) */
export type Direction = "up" | "down" | "flat";

/* ===== 체결 시각효과 강도 (체결_시각효과 설계 §4-3) ===== */

/** 효과 강도 4단계. off=표식만, strong=최대 */
export type EffectLevel = "off" | "subtle" | "normal" | "strong";
/** auto=체결률 거버너가 선택 단계를 상한으로 하향, manual=선택 단계 고정 */
export type EffectMode = "auto" | "manual";

/**
 * 프론트 내부 효과 강도 표현. REST §4-A-6 trade_flash(auto|off|subtle|normal|strong)를
 * 어댑터가 이 형태로 좁힌다(auto→mode:auto, 그 외→mode:manual). WS 채널 아님, 폴링으로 갱신.
 */
export interface EffectConfig {
  mode: EffectMode;
  level: EffectLevel;
  schema_version: typeof SCHEMA_VERSION;
}

/** config 조회 실패 시 안전 폴백(§4-A-6). 화면은 이 값으로 정상 렌더 */
export const DEFAULT_EFFECT_CONFIG: EffectConfig = {
  mode: "auto",
  level: "normal",
  schema_version: SCHEMA_VERSION,
};

/**
 * 체결 테이프 보관 상한. 스토어가 마켓별로 이만큼만 들고, 재동기화 재전달도 같은 값을 쓴다.
 * 재전달 상한이 이보다 작으면 테이프를 가득 유실한 재연결에서 빈 자리가 메워지지 않는다.
 */
export const TRADE_TAPE_LIMIT = 60;

/* ===== 시장 데이터 (§4-A) ===== */

/** GET /api/v1/markets 원소 */
export interface Market {
  market: string;          // "KRW-BTC" (quote-base)
  korean_name: string;     // "비트코인"
  base: string;            // "BTC"
  quote: string;           // "KRW"
  state: MarketState;
  tick_size: string;       // "1000"
  min_order_amount: string;// "5000"
  max_order_amount: string;// "1000000000"
  qty_scale: number;       // 8
  price_scale: number;     // 0
  maker_fee: string;       // "0.0005"
  taker_fee: string;       // "0.0005"
}

/** 호가 단위 (§4-A-2 orderbook_units 원소) */
export interface OrderbookUnit {
  ask_price: string;
  ask_size: string;
  bid_price: string;
  bid_size: string;
}

/** GET /api/v1/orderbook 단건 객체 (WS orderbook 메시지의 상위집합) */
export interface OrderbookSnapshot {
  schema_version: typeof SCHEMA_VERSION;
  market: string;
  timestamp: string;       // UTC ISO, 밀리초 3자리 고정
  epoch: number;           // 리더 세대. 변경 시 강제 재동기화
  seq: number;             // 채널(market×type) 단위 단조 증가
  total_ask_size: string;
  total_bid_size: string;
  orderbook_units: OrderbookUnit[];
}

/**
 * 티커 (REST GET /ticker 원소 + WS ticker 공통 필드).
 * null 경계(§4-A-4): 체결 이력이 전혀 없는 마켓은 trade_price가 null, 24h 윈도 무체결이면
 * high_24h·low_24h가 null, 해당 사이드 호가가 비면 best_bid·best_ask가 null이다.
 * change 계열·acc_trade_* 는 서버가 EVEN·"0"으로 폴백해 항상 non-null이다.
 */
export interface Ticker {
  market: string;
  trade_price: string | null;
  change: ChangeState;
  change_rate: string;     // 절대값 문자열 "0.0123". 부호는 change로 판정. 무체결 시 "0"
  change_price: string;    // 전일대비 절대 금액. 무체결 시 "0"
  high_24h: string | null;
  low_24h: string | null;
  acc_trade_volume_24h: string;
  acc_trade_price_24h: string; // 거래대금(KRW)
  best_bid: string | null;
  best_ask: string | null;
  seq: number;
  timestamp: string;
}

/** 체결 (REST GET /trades 원소) */
export interface Trade {
  trade_id: string;
  market: string;
  price: string;
  volume: string;
  taker_side: Side;
  timestamp: string;
  seq: number;
}

/** 캔들 (GET /candles/{unit} 원소) */
export interface Candle {
  market: string;
  candle_time: string;     // 캔들 시작 시각 UTC
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
  trade_count: number;
  seq: number;
}

/* ===== 주문/계좌 (§4-B, §4-C) ===== */

/** POST /api/v1/orders 요청 (client_order_id 필수 멱등키, §2-4) */
export interface OrderRequest {
  market: string;
  side: Side;
  ord_type: OrdType;
  price?: string;          // limit·시장가매수(notional) 필수
  volume?: string;         // limit·시장가매도 필수
  time_in_force?: TimeInForce;
  client_order_id: string; // 1~36자 [A-Za-z0-9_-]
}

/** 주문 객체 (POST /orders 201 / GET /orders/{id} 스키마) */
export interface Order {
  schema_version: typeof SCHEMA_VERSION;
  id: string;              // UUID (§7-1 v2.7 정정)
  client_order_id: string;
  market: string;
  side: Side;
  ord_type: OrdType;
  status: OrderStatus;
  price: string | null;
  volume: string | null;
  remaining_volume: string;
  executed_volume: string;
  executed_funds: string;
  remaining_notional: string | null; // 시장가 매수(notional)만 채움
  paid_fee: string;
  trades_count: number;
  created_at: string;
  canceled_at?: string;
  epoch: number;
  seq: number;             // command_journal seq (WS 채널 seq와 별개 도메인)
}

/** GET /api/v1/accounts 원소 */
export interface Asset {
  currency: string;        // "KRW" | "BTC" | ...
  balance: string;         // available
  locked: string;
  /** 원가 확보분의 평단가(§4-C-1 v2.21). "0"은 평단가 0원이 아니라 산출 불가다 */
  avg_buy_price: string;
  unit_currency: string;   // "KRW"
  /** 원가 확보 수량. 0이면 avg_buy_price "0"이 산출 불가라는 판별자다 (v2.21 additive) */
  cost_basis_qty: string;
  /** 원가 확보 금액. 총매수는 이 값이며 수량×평단가로 재계산하지 않는다 (v2.21 additive) */
  cost_basis_amount: string;
}

/* ===== WS(STOMP) 메시지 (§5-4, §5-5) ===== */

interface WsBase {
  schema_version: typeof SCHEMA_VERSION;
  epoch: number;
  seq: number;             // 채널(destination) 단위 독립 단조 증가 (§5-2)
  timestamp: string;
}

/** /topic/ticker.{market} */
export interface TickerMessage extends WsBase, Omit<Ticker, "seq" | "timestamp"> {
  type: "ticker";
  stream?: "snapshot" | "realtime";
}

/** /topic/orderbook.{market} */
export interface OrderbookMessage extends WsBase {
  type: "orderbook";
  market: string;
  stream: "snapshot" | "realtime";
  total_ask_size: string;
  total_bid_size: string;
  orderbook_units: OrderbookUnit[];
}

/** /topic/trade.{market} */
export interface TradeMessage extends WsBase {
  type: "trade";
  market: string;
  trade_id: string;
  price: string;
  volume: string;
  taker_side: Side;
}

/** /topic/candle.{unit}.{market}. seq 도메인은 market×type×unit 독립 (§5-2) */
export interface CandleMessage extends WsBase {
  type: "candle";
  unit: CandleUnit;
  market: string;
  candle_time: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
}

/** 체결 상세 (myOrder.trade) */
export interface MyOrderTrade {
  trade_id: string;
  price: string;
  volume: string;
  liquidity_flag: "MAKER" | "TAKER";
  fee: string;
}

/** /user/queue/myOrder. seq 도메인은 user×type 독립 (§5-2) */
export interface MyOrderMessage extends WsBase {
  type: "myOrder";
  id: string;
  client_order_id: string;
  market: string;
  side: Side;
  status: OrderStatus;
  price: string | null;
  executed_volume: string;
  remaining_volume: string;
  reject_reason: string | null;
  trade?: MyOrderTrade | null;
}

/** /user/queue/myAsset */
/** /user/queue/myAsset. 접촉 자산만 싣는 델타 (§5-5) */
export interface MyAssetMessage extends WsBase {
  type: "myAsset";
  /** 원가 필드는 부가가 아니라 계약이다(v2.21 K5). 없으면 매수마다 낡은 평단가가 화면에 남는다 */
  assets: Array<
    Pick<Asset, "currency" | "balance" | "locked" | "avg_buy_price" | "cost_basis_qty" | "cost_basis_amount">
  >;
}

export type WsMessage =
  | TickerMessage
  | OrderbookMessage
  | TradeMessage
  | CandleMessage
  | MyOrderMessage
  | MyAssetMessage;

/* ===== STOMP destination 헬퍼 (§5-3 경로 미러) ===== */

export const dest = {
  ticker: (market: string) => `/topic/ticker.${market}`,
  orderbook: (market: string) => `/topic/orderbook.${market}`,
  trade: (market: string) => `/topic/trade.${market}`,
  candle: (unit: CandleUnit, market: string) => `/topic/candle.${unit}.${market}`,
  myOrder: "/user/queue/myOrder" as const,
  myAsset: "/user/queue/myAsset" as const,
};

/* ===== 회원 세션 (§3-8 미러) ===== */

/** GET /me·가입·로그인 응답의 user 객체 */
export interface MemberUser {
  external_id: string;
  email: string;
  display_name: string;
  kind: string; // "HUMAN"
}

/** 회원 세션. access는 메모리 보관, refresh는 httpOnly 쿠키(JS 접근 불가) */
export interface MemberSession {
  user: MemberUser;
  accessToken: string;
}

/** POST /api/v1/auth/signup 요청 */
export interface SignupInput {
  email: string;
  password: string;
  display_name: string;
}

/** POST /api/v1/auth/login 요청 */
export interface LoginInput {
  email: string;
  password: string;
}

/* ===== UI 보조 (데이터성 색상. 코인 브랜드색은 토큰 예외 허용) ===== */

export interface CoinMeta {
  market: string;
  symbol: string;
  koreanName: string;
  /** 코인 브랜드 로고색 (하드코딩 hex 예외 허용 대상) */
  brandColor: string;
  /** 어두운 배경색 위 텍스트가 어두워야 하는 경우 */
  brandTextDark?: boolean;
}
