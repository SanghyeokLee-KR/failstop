/**
 * lib/types.ts. API 계약(docs/백엔드_API_설계.md v2.3)의 스키마 미러.
 * 금액/수량은 계약대로 "문자열 NUMERIC(36,18)" (JSON float 금지, §2-1).
 * WS 메시지는 §5-4/§5-5의 채널 스키마를 그대로 따른다 (ticker/orderbook/trade/candle/myOrder/myAsset).
 * 추후 실제 Spring REST/STOMP 어댑터로 교체 시 이 타입이 그대로 유지된다.
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

/** 티커 (REST GET /ticker 원소 + WS ticker 공통 필드) */
export interface Ticker {
  market: string;
  trade_price: string;
  change: ChangeState;
  change_rate: string;     // 절대값 문자열 "0.0123". 부호는 change로 판정
  change_price: string;    // 전일대비 절대 금액
  high_24h: string;
  low_24h: string;
  acc_trade_volume_24h: string;
  acc_trade_price_24h: string; // 거래대금(KRW)
  best_bid: string;
  best_ask: string;
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
  avg_buy_price: string;
  unit_currency: string;   // "KRW"
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
export interface MyAssetMessage extends WsBase {
  type: "myAsset";
  assets: Array<Pick<Asset, "currency" | "balance" | "locked">>;
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
