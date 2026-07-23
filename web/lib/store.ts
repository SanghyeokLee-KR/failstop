"use client";

/**
 * lib/store.ts. zustand 전역 스토어.
 * WS 메시지(ticker/orderbook/trade/myOrder/myAsset)를 apply()로 흡수하고,
 * 화면은 selector로 필요한 조각만 구독한다 (셀 단위 리렌더 최소화).
 */

import { useEffect, useMemo, useRef } from "react";
import { create } from "zustand";
import type {
  Asset,
  EffectConfig,
  Market,
  MemberSession,
  MyOrderMessage,
  Order,
  OrderbookMessage,
  TickerMessage,
  TradeMessage,
  WsMessage,
} from "./types";
import { DEFAULT_EFFECT_CONFIG, SCHEMA_VERSION } from "./types";
import { getDataSource } from "./api/datasource";

const MAX_TRADES = 60;

/**
 * ticker·orderbook 스토어 교체 전 (epoch, seq) 단조 검사.
 * 느린 REST 스냅샷·재전달이 이미 반영된 최신 실시간을 낮은 seq로 되돌리지 않게 한다.
 * epoch 상승은 리더 세대 교체로 seq 도메인이 리셋되므로 무조건 최신으로 본다.
 */
function isStaleUpdate(
  prev: { epoch: number; seq: number } | undefined,
  next: { epoch: number; seq: number },
): boolean {
  if (!prev) return false;
  if (next.epoch !== prev.epoch) return next.epoch < prev.epoch;
  return next.seq <= prev.seq;
}

/**
 * myOrder 메시지로부터 미보유 주문을 스토어에 채우기 위한 최소 Order 합성.
 * 재동기화·타 세션 신규 주문은 openOrders에 없어 유실되므로 즉시 표시용으로 append 한다.
 * 상세 필드(ord_type·수수료·체결금액)는 메시지에 없어 안전 기본값을 두고, 이어지는
 * loadOrders 전량 조회가 권위 있는 Order로 교체한다. amount는 문자열 규약을 지켜 산술하지 않는다.
 */
function synthOrderFromMyOrder(msg: MyOrderMessage): Order {
  return {
    schema_version: SCHEMA_VERSION,
    id: msg.id,
    client_order_id: msg.client_order_id,
    market: msg.market,
    side: msg.side,
    ord_type: "limit", // 미체결 잔존 주문은 지정가가 표준. 정확한 종류는 loadOrders가 확정
    status: msg.status,
    price: msg.price,
    volume: null,
    remaining_volume: msg.remaining_volume,
    executed_volume: msg.executed_volume,
    executed_funds: "0",
    remaining_notional: null,
    paid_fee: "0",
    trades_count: 0,
    created_at: msg.timestamp,
    epoch: msg.epoch,
    seq: msg.seq,
  };
}
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
  /** 회원 세션(§4-5). null이면 미로그인·데모 둘러보기. DataSource가 유일 갱신 주체 */
  session: MemberSession | null;

  apply(msg: WsMessage): void;
  /** 효과 강도 재조회. 폴링 훅이 호출한다(실시간 push 아님, §4-A-6) */
  loadEffectConfig(): Promise<void>;
  /** DataSource.onConnectionChange가 유일한 호출 주체 (§5.1) */
  setConnected(v: boolean): void;
  /** DataSource.onMemberSessionChange가 유일한 호출 주체(§4-5). 화면은 setter를 직접 호출하지 않는다.
   * 세션 해제도 setSession(null)로 수렴한다(별도 clearSession 없음). 신원 전환·해제 시
   * 사설 슬라이스(assets·openOrders·closedOrders)를 이 한 지점에서 소거해 평면 간 누수를 막는다 */
  setSession(s: MemberSession | null): void;
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
  session: null,

  apply(msg) {
    switch (msg.type) {
      case "ticker":
        set((s) => {
          // 낮은 (epoch, seq)는 stale. 같은 참조 반환으로 리렌더 없이 드롭한다
          if (isStaleUpdate(s.tickers[msg.market], msg)) return s;
          return { tickers: { ...s.tickers, [msg.market]: msg } };
        });
        break;
      case "orderbook":
        set((s) => {
          if (isStaleUpdate(s.orderbooks[msg.market], msg)) return s;
          return { orderbooks: { ...s.orderbooks, [msg.market]: msg } };
        });
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
            // 미보유 OPEN·PARTIAL은 재동기화·타 세션 신규 주문이다. 드롭하지 않고 합성해 append,
            // 이후 loadOrders 전량 조회가 권위 데이터로 교체한다(재연결 유실 방지)
            openOrders: inOpen
              ? openOrders.map((o) => (o.id === msg.id ? patch(o) : o))
              : [synthOrderFromMyOrder(msg), ...openOrders],
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

  setSession(s) {
    set((state) => {
      const prevId = state.session?.user.external_id ?? null;
      const nextId = s?.user.external_id ?? null;
      // 같은 신원의 토큰 재발급은 사설 슬라이스를 보존한다. 신원 전환·해제만 소거해
      // 이전 회원의 잔액·주문이 다음 신원 화면에 잔존·flash 하지 않게 한다
      if (prevId === nextId) return { session: s };
      return { session: s, assets: [], openOrders: [], closedOrders: [] };
    });
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
 * 구독 집합 전이의 차집합. useLiveChannels가 destinations 변경 시 전량 철거·재구독 대신
 * 추가·제거분만 반영하기 위한 순수 계산(회귀 테스트 대상).
 */
export function diffDestinations(
  prev: Iterable<string>,
  next: string[],
): { added: string[]; removed: string[] } {
  const prevSet = new Set(prev);
  const nextSet = new Set(next);
  return {
    added: next.filter((d) => !prevSet.has(d)),
    removed: [...prevSet].filter((d) => !nextSet.has(d)),
  };
}

/**
 * 채널 구독 훅. 마운트 시 destination들을 구독하고 스토어에 흘려 넣는다.
 * 예: useLiveChannels([dest.ticker("KRW-BTC"), dest.orderbook("KRW-BTC")])
 * destinations 변경 시 diff 기반으로 추가·제거된 채널만 구독·해제한다. 전량 철거·재구독은
 * 유지 중인 채널의 STOMP UNSUBSCRIBE/SUBSCRIBE와 채널 수 규모의 초기 스냅샷 REST를 재발사한다.
 */
export function useLiveChannels(destinations: string[]): void {
  const apply = useExchangeStore((s) => s.apply);
  const key = destinations.join(",");
  const stable = useMemo(() => destinations, [key]); // eslint-disable-line react-hooks/exhaustive-deps
  const subs = useRef<Map<string, () => void>>(new Map());
  useEffect(() => {
    const ds = getDataSource();
    const { added, removed } = diffDestinations(subs.current.keys(), stable);
    for (const d of removed) {
      subs.current.get(d)?.();
      subs.current.delete(d);
    }
    for (const d of added) subs.current.set(d, ds.subscribe(d, apply));
  }, [stable, apply]);
  // 언마운트 시 전체 해제. StrictMode 이중 마운트에서도 재실행 effect가 빈 맵에서 재구독한다
  useEffect(
    () => () => {
      for (const off of subs.current.values()) off();
      subs.current.clear();
    },
    [],
  );
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

/**
 * 회원 세션 부트스트랩. 한 곳(HeaderNav)에서만 호출한다.
 * onMemberSessionChange 구독으로 store를 갱신하고, 마운트 시 refresh로 세션 복원을 시도한다.
 */
export function useMemberSessionBootstrap(): void {
  const setSession = useExchangeStore((s) => s.setSession);
  useEffect(() => {
    const ds = getDataSource();
    const off = ds.onMemberSessionChange(setSession);
    // 새로고침 후 httpOnly refresh 쿠키로 access 재획득(§4-5). sim은 인메모리 세션을 그대로 반환
    void ds.refresh().catch(() => {});
    return off;
  }, [setSession]);
}

/** 회원 세션 셀렉터. 화면은 이 셀렉터로 로그인 상태를 구독한다 */
export function useMemberSession(): MemberSession | null {
  return useExchangeStore((s) => s.session);
}
