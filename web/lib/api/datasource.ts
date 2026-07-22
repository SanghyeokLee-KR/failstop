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
  Market,
  Order,
  OrderRequest,
  OrderbookMessage,
  Ticker,
  Trade,
  WsMessage,
} from "../types";
import { SimEngine } from "../sim/engine";
import { HttpStompDataSource } from "./httpDatasource";

export interface DataSource {
  /* ===== REST 미러 (§4) ===== */
  getMarkets(): Promise<Market[]>;
  getTicker(markets: string[]): Promise<Ticker[]>;
  getOrderbook(market: string): Promise<OrderbookMessage>;
  getTrades(market: string, count?: number): Promise<Trade[]>;
  getCandles(market: string, unit: CandleUnit, count?: number): Promise<Candle[]>;
  getAccounts(): Promise<Asset[]>;
  getOpenOrders(market?: string): Promise<Order[]>;
  getClosedOrders(market?: string): Promise<Order[]>;
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
}

/** 시뮬레이터 기반 구현. 런타임 외부 의존 0 */
export class SimDataSource implements DataSource {
  constructor(private engine: SimEngine) {}

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
