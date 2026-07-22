"use client";

/**
 * lib/store.ts. zustand 전역 스토어.
 * WS 메시지(ticker/orderbook/trade/myOrder/myAsset)를 apply()로 흡수하고,
 * 화면은 selector로 필요한 조각만 구독한다 (셀 단위 리렌더 최소화).
 */

import { useEffect, useMemo } from "react";
import { create } from "zustand";
import type {
  Asset,
  EffectConfig,
  Market,
  Order,
  OrderbookMessage,
  TickerMessage,
  TradeMessage,
  WsMessage,
} from "./types";
import { DEFAULT_EFFECT_CONFIG } from "./types";
import { getDataSource } from "./api/datasource";

const MAX_TRADES = 60;
const FAV_STORAGE_KEY = "exchange.favorites";
const DEFAULT_FAVORITES = ["KRW-BTC", "KRW-XRP"];

function readFavorites(): string[] {
  try {
    const raw = localStorage.getItem(FAV_STORAGE_KEY);
    if (raw) {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.every((v) => typeof v === "string")) return parsed;
    }
  } catch {
    /* storage 접근 실패 시 기본값 유지 */
  }
  return DEFAULT_FAVORITES;
}

function writeFavorites(favorites: string[]): void {
  try {
    localStorage.setItem(FAV_STORAGE_KEY, JSON.stringify(favorites));
  } catch {
    /* no-op */
  }
}

interface ExchangeState {
  markets: Market[];
  tickers: Record<string, TickerMessage>;
  orderbooks: Record<string, OrderbookMessage>;
  trades: Record<string, TradeMessage[]>;
  assets: Asset[];
  openOrders: Order[];
  closedOrders: Order[];
  favorites: string[];
  connected: boolean;
  /** 체결 시각효과 강도. 폴링 전 안전 폴백(§4-A-6) */
  effectConfig: EffectConfig;

  apply(msg: WsMessage): void;
  /** 효과 강도 재조회. 폴링 훅이 호출한다(실시간 push 아님, §4-A-6) */
  loadEffectConfig(): Promise<void>;
  /** DataSource.onConnectionChange가 유일한 호출 주체 (§5.1) */
  setConnected(v: boolean): void;
  toggleFavorite(market: string): void;
  /** SSR 안전 하이드레이션: 마운트 후 localStorage 관심 목록 반영 */
  hydrateFavorites(): void;
  loadMarkets(): Promise<void>;
  loadAccounts(): Promise<void>;
  loadOrders(market?: string): Promise<void>;
}

export const useExchangeStore = create<ExchangeState>((set, get) => ({
  markets: [],
  tickers: {},
  orderbooks: {},
  trades: {},
  assets: [],
  openOrders: [],
  closedOrders: [],
  favorites: DEFAULT_FAVORITES,
  connected: true,
  effectConfig: DEFAULT_EFFECT_CONFIG,

  apply(msg) {
    switch (msg.type) {
      case "ticker":
        set((s) => ({ tickers: { ...s.tickers, [msg.market]: msg } }));
        break;
      case "orderbook":
        set((s) => ({ orderbooks: { ...s.orderbooks, [msg.market]: msg } }));
        break;
      case "trade":
        set((s) => {
          const prev = s.trades[msg.market] ?? [];
          return { trades: { ...s.trades, [msg.market]: [msg, ...prev].slice(0, MAX_TRADES) } };
        });
        break;
      case "myAsset":
        set((s) => ({
          assets: msg.assets.map((a) => {
            const prev = s.assets.find((p) => p.currency === a.currency);
            return {
              currency: a.currency,
              balance: a.balance,
              locked: a.locked,
              avg_buy_price: prev?.avg_buy_price ?? "0",
              unit_currency: "KRW",
            };
          }),
        }));
        break;
      case "myOrder": {
        // 미체결↔체결 목록 전이 (§8 화면6 실시간 규약)
        const { openOrders, closedOrders } = get();
        const patch = (o: Order): Order => ({
          ...o,
          status: msg.status,
          executed_volume: msg.executed_volume,
          remaining_volume: msg.remaining_volume,
        });
        const inOpen = openOrders.find((o) => o.id === msg.id);
        if (msg.status === "OPEN" || msg.status === "PARTIAL") {
          set({
            openOrders: inOpen
              ? openOrders.map((o) => (o.id === msg.id ? patch(o) : o))
              : openOrders, // 신규 주문은 placeOrder 응답/loadOrders로 합류
          });
        } else {
          set({
            openOrders: openOrders.filter((o) => o.id !== msg.id),
            closedOrders: inOpen ? [patch(inOpen), ...closedOrders] : closedOrders,
          });
        }
        break;
      }
      case "candle":
        break; // 캔들은 차트 컴포넌트가 로컬 구독 (스토어 미보관)
    }
  },

  async loadEffectConfig() {
    // 효과 강도는 WS push가 아니라 폴링·재조회로 반영한다(§4-A-6). 실패는 어댑터가 폴백 흡수
    const cfg = await getDataSource().getEffectConfig();
    set({ effectConfig: cfg });
  },

  setConnected(v) {
    set({ connected: v });
  },

  toggleFavorite(market) {
    set((s) => {
      const favorites = s.favorites.includes(market)
        ? s.favorites.filter((m) => m !== market)
        : [...s.favorites, market];
      writeFavorites(favorites);
      return { favorites };
    });
  },

  hydrateFavorites() {
    if (typeof window === "undefined") return;
    set({ favorites: readFavorites() });
  },

  async loadMarkets() {
    const markets = await getDataSource().getMarkets();
    set({ markets });
  },

  async loadAccounts() {
    const assets = await getDataSource().getAccounts();
    set({ assets });
  },

  async loadOrders(market) {
    const ds = getDataSource();
    const [openOrders, closedOrders] = await Promise.all([
      ds.getOpenOrders(market),
      ds.getClosedOrders(market),
    ]);
    set({ openOrders, closedOrders });
  },
}));

/**
 * 채널 구독 훅. 마운트 시 destination들을 구독하고 스토어에 흘려 넣는다.
 * 예: useLiveChannels([dest.ticker("KRW-BTC"), dest.orderbook("KRW-BTC")])
 */
export function useLiveChannels(destinations: string[]): void {
  const apply = useExchangeStore((s) => s.apply);
  const key = destinations.join(",");
  const stable = useMemo(() => destinations, [key]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    const ds = getDataSource();
    const offs = stable.map((d) => ds.subscribe(d, apply));
    return () => offs.forEach((off) => off());
  }, [stable, apply]);
}

// 효과 강도는 실시간 push가 아니라 폴링으로 반영한다(§4-A-6). cosmetic 값이라 저빈도로 충분
const EFFECT_POLL_MS = 15_000;

/** 마운트 시 효과 강도 1회 조회 후 주기 재조회. 관리자 변경을 폴링으로 흡수 */
export function useEffectConfigPolling(intervalMs = EFFECT_POLL_MS): void {
  const loadEffectConfig = useExchangeStore((s) => s.loadEffectConfig);
  useEffect(() => {
    void loadEffectConfig();
    const id = setInterval(() => void loadEffectConfig(), intervalMs);
    return () => clearInterval(id);
  }, [loadEffectConfig, intervalMs]);
}
