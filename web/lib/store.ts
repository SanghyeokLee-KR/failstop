"use client";

/**
 * zustand 전역 스토어. WS 메시지를 apply()로 흡수하고, 화면은 selector로 필요한 조각만 구독한다.
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
  OrderStatus,
  OrderbookMessage,
  PublicNotice,
  TickerMessage,
  TradeMessage,
  WsMessage,
} from "./types";
import { DEFAULT_EFFECT_CONFIG, SCHEMA_VERSION, TRADE_TAPE_LIMIT } from "./types";
import { compareDecimal } from "./decimal";
import { getDataSource } from "./api/datasource";
import { ApiError } from "./api/httpDatasource";

/**
 * ticker·orderbook 스토어 교체 전 (epoch, seq) 단조 검사. 느린 REST 스냅샷·재전달이
 * 이미 반영된 최신 실시간을 되돌리지 않게 한다. epoch 상승은 seq 도메인 리셋이라 최신으로 본다.
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
 * myOrder 메시지로 미보유 주문을 즉시 표시용으로 합성한다. 재동기화·타 세션 신규 주문은
 * openOrders에 없어 유실된다. 상세 필드는 메시지에 없어 안전 기본값을 두고 loadOrders가 교체한다.
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

/**
 * 주문 상태 등급. 수명이 OPEN에서 PARTIAL, 종결로 단조라 더 낮은 등급은 되돌이(stale)다.
 * 주문 seq는 REST가 command_journal 도메인, WS 합성이 채널 도메인이라 비교할 수 없어 등급으로 잡는다.
 */
const STATUS_RANK: Record<OrderStatus, number> = {
  OPEN: 0,
  PARTIAL: 1,
  FILLED: 2,
  CANCELED: 2,
  REJECTED: 2,
};

const TERMINAL_RANK = 2;

function isTerminalOrder(status: OrderStatus): boolean {
  return STATUS_RANK[status] >= TERMINAL_RANK;
}

/**
 * 로컬 주문과 도착 주문 중 더 진행된 쪽을 고른다.
 * 등급이 같으면 도착분을 채택해 권위 필드를 채우되, 로컬 체결 수량이 더 크면 로컬을 유지한다.
 */
function pickOrder(local: Order | undefined, incoming: Order): Order {
  if (!local) return incoming;
  const diff = STATUS_RANK[incoming.status] - STATUS_RANK[local.status];
  if (diff < 0) return local;
  if (diff > 0) return incoming;
  return compareDecimal(local.executed_volume, incoming.executed_volume) > 0 ? local : incoming;
}

export interface OrderMergeOptions {
  /** 조회 발사 시점에 스토어가 알던 주문 id. 응답에 없는 이 집합의 주문만 제거한다.
   * 왕복 중 접수·수신된 주문은 스냅샷에 있을 수 없어 부재를 소멸로 해석하면 안 된다 */
  known?: ReadonlySet<string>;
  /** 마켓 한정 조회면 그 마켓만 제거 대상 */
  market?: string;
}

/** 목록 순서를 보존하며 id 중복 없이 누적 */
function collect(into: Map<string, Order>, ids: string[], orders: Order[]): void {
  for (const o of orders) {
    if (into.has(o.id)) continue;
    into.set(o.id, o);
    ids.push(o.id);
  }
}

/**
 * REST 주문 스냅샷 병합. 전량 교체는 왕복 중 확정된 WS 전이를 되돌려 종결 주문을 미체결 탭에 부활시킨다.
 * id 기준 upsert + 상태 단조 가드로 진행된 로컬 상태를 지키고, 분류는 병합 결과의 상태로 결정한다.
 */
export function mergeOrderSnapshot(
  prev: { openOrders: Order[]; closedOrders: Order[] },
  fetched: Order[],
  options: OrderMergeOptions = {},
): { openOrders: Order[]; closedOrders: Order[] } {
  const merged = new Map<string, Order>();
  const ids: string[] = [];
  collect(merged, ids, prev.openOrders);
  collect(merged, ids, prev.closedOrders);

  const fetchedIds = new Set<string>();
  for (const f of fetched) {
    fetchedIds.add(f.id);
    const local = merged.get(f.id);
    if (local === undefined) ids.push(f.id);
    merged.set(f.id, pickOrder(local, f));
  }

  const openOrders: Order[] = [];
  const closedOrders: Order[] = [];
  for (const id of ids) {
    const o = merged.get(id);
    if (!o) continue;
    // 조회 범위 안에서 서버가 더는 알지 못하는 주문만 제거한다(타 마켓·신규 접수분 보존)
    const scoped = options.market === undefined || o.market === options.market;
    if (!fetchedIds.has(id) && scoped && options.known?.has(id)) continue;
    if (isTerminalOrder(o.status)) closedOrders.push(o);
    else openOrders.push(o);
  }
  return { openOrders, closedOrders };
}

function sameOrders(a: Order[], b: Order[]): boolean {
  return a.length === b.length && a.every((o, i) => o === b[i]);
}

/** 내용이 그대로면 이전 배열 참조를 유지한다. 무의미한 리렌더를 만들지 않기 위함 */
function nextOrderLists(
  prev: { openOrders: Order[]; closedOrders: Order[] },
  next: { openOrders: Order[]; closedOrders: Order[] },
): { openOrders: Order[]; closedOrders: Order[] } {
  return {
    openOrders: sameOrders(prev.openOrders, next.openOrders) ? prev.openOrders : next.openOrders,
    closedOrders: sameOrders(prev.closedOrders, next.closedOrders) ? prev.closedOrders : next.closedOrders,
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
  /* 잔고 신선도(§2-3 429 클라이언트 규약). 이진 실패 플래그를 대신한다.
     플래그가 UI 로컬에 있던 동안에는 myAsset 이 잔고를 되살려도 그 성공을 관측할 수 없어
     데이터는 살아나고 표시만 실패로 남는 갈라짐이 있었다. 소유자를 데이터 쪽으로 옮긴다 */
  accountsHealth: AccountsHealth;
  /** 조회 왕복 단일화 가드. 연타·동시 마운트가 요청을 늘리지 않게 한다 */
  accountsInFlight: boolean;
  /** 진행 중 잔고 조회의 신원. in-flight 가드를 신원별로 만든다 */
  accountsInFlightId: string | null;
  /* 공지 첫 쪽(§4-F). 배너와 진입점 미읽음 표식이 쓴다.
     목록 화면은 여기를 보지 않고 자기 커서로 아카이브를 따라간다 */
  notices: PublicNotice[];
  /* 빈 배열과 미로딩을 구분한다. 하나로 두면 첫 프레임에 "등록된 공지 없음"이 스친 뒤
     목록이 나타난다. 사용자는 그 깜빡임을 오류로 읽는다 */
  noticesLoaded: boolean;
  /** 체결 목록 첫 쪽이 잘렸는지. 요약이 부분 집계를 총계로 말하지 않게 한다 */
  closedOrdersTruncated: boolean;
  /* 빈 배열과 미로딩을 구분한다. noticesLoaded와 같은 이유이며 대상만 다르다.
     구분이 없으면 요약 화면이 첫 프레임에 "0원 0종목 0건"을 사실로 표시하고 값이 튄다.
     0은 빈 상태가 아니라 아직 모르는 값이다 */
  accountsLoaded: boolean;
  ordersLoaded: boolean;
  /** 사용자가 닫은 공지 id. CRITICAL은 이 목록을 무시한다(lib/notices) */
  dismissedNoticeIds: string[];
  /** 마지막으로 공지 목록을 본 시각. 진입점 표식 판정용(lib/notices.hasUnread) */
  lastSeenNoticeAt: number | null;
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
  loadNotices(): Promise<void>;
  dismissNotice(id: string): void;
  markNoticesSeen(): void;
  loadOrders(market?: string): Promise<void>;
  /** REST 주문 응답 1건 반영(POST /orders 201, DELETE /orders 200).
   * 재조회 왕복을 기다리지 않고 목록을 확정한다. 상태 단조 가드로 역행 응답은 드롭한다 */
  upsertOrder(order: Order): void;
}

/**
 * 잔고 신선도(§2-3 429 클라이언트 규약).
 *
 * idle   아직 한 번도 읽지 않음
 * ok     마지막 조회 성공. 값이 최신이다
 * stale  조회는 실패했지만 이전 성공값이 있다. 값을 지우지 않고 지연을 표시한다
 * failed 값이 없는 채로 실패했다. 표시할 잔고 자체가 없다
 *
 * stale 과 failed 를 가르는 것이 이 모델의 요점이다. 종전에는 한 플래그였고, 그래서 조회
 * 한 번 실패에 이미 받아둔 잔고까지 화면에서 사라졌다. 서버가 설계대로 백프레셔를 걸수록
 * 화면은 그것을 고장으로 번역했다.
 */
export type BalanceFreshness = "idle" | "ok" | "stale" | "failed";

export interface AccountsHealth {
  state: BalanceFreshness;
  /** 마지막 성공 시각(epoch ms). 실패해도 보존한다. 값의 나이를 말하는 근거다 */
  lastUpdatedAt: number | null;
  /** 429 대기 초. 표시와 자동 재조회가 함께 쓴다. 429가 아닌 실패면 null */
  retryAfterSec: number | null;
}

/* 서버가 준 대기 초를 표시·타이머에 쓰기 전에 유계화한다. 계약상 작은 값이지만 상한이
   없으면 잘못된 헤더 하나가 화면을 몇 분 잠그고, 하한이 없으면 0초 재조회가 폭주가 된다 */
const RETRY_SEC_MIN = 1;
const RETRY_SEC_MAX = 30;

export function clampRetrySec(sec: number | null): number {
  if (sec == null || !Number.isFinite(sec)) return RETRY_SEC_MIN;
  return Math.min(RETRY_SEC_MAX, Math.max(RETRY_SEC_MIN, Math.ceil(sec)));
}

/* 사설 조회 왕복 상한. 응답하지 않는 요청 하나가 in-flight 가드와 로딩 플래그를 영구 고착시켜
   재조회 자체를 봉인한다. 봉인이 걸리는 구간은 응답이 느린 구간, 곧 백프레셔 구간이다.
   never-settle REST를 끊는다는 사유가 어댑터의 재동기화 상한(RESYNC_TIMEOUT_MS)과 같아 값도 맞춘다 */
export const PRIVATE_QUERY_TIMEOUT_MS = 8_000;

/** 상한을 넘긴 조회를 실패로 확정한다. signal은 어댑터가 in-flight fetch를 끊는 데 쓴다 */
function withQueryTimeout<T>(run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), PRIVATE_QUERY_TIMEOUT_MS);
  return run(ctl.signal)
    .catch((e: unknown) => {
      /* 상한으로 끊은 것은 화면의 흐름 제어가 아니라 실패다. 어댑터가 원형 보존하는 취소 예외를
         그대로 흘리면 브라우저 원문이 전역 토스트에 오른다(§2-3 한국어 메시지 계약).
         429가 아니므로 신선도는 값 유무에 따라 stale·failed로 수렴한다 */
      throw ctl.signal.aborted
        ? new ApiError(0, "query_timeout", "응답 지연으로 조회를 중단함. 잠시 후 다시 시도 필요")
        : e;
    })
    .finally(() => clearTimeout(timer));
}

// loadMarkets 동시 호출 병합. 여러 화면이 같은 프레임에 마운트해도 GET /markets는 1회다
let marketsInFlight: Promise<void> | null = null;

/**
 * 전역이 드는 공지 수. 배너는 이 안에서 최신 경보 한 건을 고른다.
 * 활성 경보보다 새 공지가 이 수를 넘게 쌓이면 배너가 그 경보를 놓치는데, 그 상황은 공지 운영이 먼저 깨진 상태다.
 */
const NOTICE_HEAD = 50;

export const useExchangeStore = create<ExchangeState>((set, get) => ({
  markets: [],
  tickers: {},
  orderbooks: {},
  trades: {},
  assets: [],
  accountsHealth: { state: "idle", lastUpdatedAt: null, retryAfterSec: null },
  accountsInFlight: false,
  accountsInFlightId: null,
  notices: [],
  noticesLoaded: false,
  closedOrdersTruncated: false,
  accountsLoaded: false,
  ordersLoaded: false,
  dismissedNoticeIds: [],
  lastSeenNoticeAt: null,
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
          // 재연결 재동기화가 기수신 체결을 재전달한다(datasource 기준점 소거). 중복이 테이프
          // 상한을 소모해 고유 체결 표시 수를 줄이지 않도록 trade_id로 드롭한다
          if (prev.some((t) => t.trade_id === msg.trade_id)) return s;
          // seq 내림차순 삽입. 무조건 prepend하면 WS보다 뒤처진 복제본을 읽은 재동기화 꼬리가
          // 과거 체결을 목록 맨 위에 올려 시각 정렬이 뒤집힌다. trade는 전역 영속 event_seq라
          // 세대가 바뀌어도 seq 대소가 시간 순서를 그대로 나타낸다(§5-2)
          const next = prev.slice();
          let at = 0;
          while (at < next.length && next[at].seq > msg.seq) at++;
          next.splice(at, 0, msg);
          return { trades: { ...s.trades, [msg.market]: next.slice(0, TRADE_TAPE_LIMIT) } };
        });
        break;
      case "myAsset":
        set((s) => {
          // publishMyAsset은 접촉 마켓의 base·quote만 보내는 델타(§5-2/§5-5). msg.assets로 전체
          // 교체하면 미접촉 보유코인이 소실돼 자산 화면 행·총평가·가용잔고가 어긋난다. currency
          // 기준 upsert 병합으로 미접촉분을 보존한다.
          const next = s.assets.slice();
          for (const a of msg.assets) {
            const i = next.findIndex((p) => p.currency === a.currency);
            // 원가는 델타가 권위다(v2.21 K5). 직전 값을 보존하면 매수 체결마다 낡은 평단가가
            // GET /accounts 재적재 전까지 남는다. 미노출 서버는 결측을 원가 미상으로 읽는다
            const merged: Asset = {
              currency: a.currency,
              balance: a.balance,
              locked: a.locked,
              avg_buy_price: a.avg_buy_price ?? "0",
              unit_currency: "KRW",
              cost_basis_qty: a.cost_basis_qty ?? "0",
              cost_basis_amount: a.cost_basis_amount ?? "0",
            };
            if (i >= 0) next[i] = merged;
            else next.push(merged);
          }
          /* 잔고 델타가 도착했으면 값이 살아난 것이다. 신선도도 함께 회복시킨다.
             이 한 줄이 없어서 종전에는 데이터는 최신인데 화면만 실패로 남았다.
             lastUpdatedAt 도 갱신한다. 값의 나이가 조회 시각이 아니라 이 시각이기 때문이다 */
          return {
            assets: next,
            accountsHealth: { state: "ok" as const, lastUpdatedAt: Date.now(), retryAfterSec: null },
          };
        });
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
          // 재동기화가 재전달한 미체결 스냅샷이 이미 종결된 주문을 미체결로 되돌리지 않게 막는다.
          // 종결은 흡수 상태라 종결→OPEN 전이는 계약상 존재하지 않는다
          if (closedOrders.some((o) => o.id === msg.id)) break;
          set({
            // 미보유 OPEN·PARTIAL은 재동기화·타 세션 신규 주문이다. 드롭하지 않고 합성해 append,
            // 이후 loadOrders 전량 조회가 권위 데이터로 교체한다(재연결 유실 방지)
            openOrders: inOpen
              ? openOrders.map((o) => (o.id === msg.id ? patch(o) : o))
              : [synthOrderFromMyOrder(msg), ...openOrders],
          });
        } else {
          const inClosed = closedOrders.find((o) => o.id === msg.id);
          // 종결 주문이 openOrders에 없어도(재동기화 재전달·타 세션 종결) closedOrders에서 드롭하지
          // 않는다. 보유분이 있으면 patch, 없으면 합성해 id upsert 한다(OPEN 분기와 대칭). 재전달이
          // 중복 행을 만들지 않게 이미 있으면 갱신한다. 이후 loadOrders가 권위 Order로 교체한다
          const settled = patch(inOpen ?? inClosed ?? synthOrderFromMyOrder(msg));
          set({
            openOrders: openOrders.filter((o) => o.id !== msg.id),
            closedOrders: inClosed
              ? closedOrders.map((o) => (o.id === msg.id ? settled : o))
              : [settled, ...closedOrders],
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
      /* 신선도도 함께 되돌린다. assets 만 비우면 stale 상태가 값 없이 남고, 그 조합은
         "이전 성공값이 있다"는 stale 정의를 깬다. decideBalance 는 stale 을 값 있음으로
         읽어 CTA 까지 열므로, 로그아웃 직후 거래 화면이 빈 잔고로 주문을 허용하게 된다 */
      return {
        session: s,
        assets: [],
        openOrders: [],
        closedOrders: [],
        accountsHealth: { state: "idle" as const, lastUpdatedAt: null, retryAfterSec: null },
      };
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

  // async를 쓰지 않는다. 래핑 프라미스가 새로 생기면 호출자가 공유 여부를 확인할 수 없다
  loadMarkets() {
    // 여러 화면이 동시에 마운트해도 조회는 1회. 진행 중이면 같은 프라미스를 공유한다
    if (marketsInFlight) return marketsInFlight;
    marketsInFlight = getDataSource()
      .getMarkets()
      .then((markets) => {
        set({ markets });
      })
      .finally(() => {
        marketsInFlight = null;
      });
    return marketsInFlight;
  },

  /* 실패해도 조용히 넘긴다. 공지는 보조 정보라 부재 자체가 사용자에게 전달할 사실이 아니다.
     전역이 드는 것은 첫 쪽뿐이다. 아카이브 전체를 들면 공지가 쌓일수록 모든 화면의 첫 요청이 무거워진다 */
  async loadNotices() {
    try {
      const page = await getDataSource().getNotices({ limit: NOTICE_HEAD });
      set({ notices: page.data, noticesLoaded: true });
    } catch {
      /* 받아 둔 공지는 유지하되 loaded는 세운다. 실패를 미로딩으로 두면 스켈레톤이 영원히
         돌고, 그것은 지켜지지 않는 약속이다 */
      set({ noticesLoaded: true });
    }
  },

  /* 목록을 본 시점을 기록한다. 가장 최근 공지의 발행 시각을 기준으로 두는 이유는
     now()를 쓰면 조회 직후 발행된 공지가 읽은 것으로 잘못 표시되기 때문이다 */
  markNoticesSeen() {
    const latest = get().notices.reduce((mx, n) => Math.max(mx, n.published_at ?? 0), 0);
    if (latest > 0) set({ lastSeenNoticeAt: latest });
  },

  dismissNotice(id) {
    set((state) =>
      state.dismissedNoticeIds.includes(id)
        ? state
        : { dismissedNoticeIds: [...state.dismissedNoticeIds, id] },
    );
  },

  async loadAccounts() {
    /* 단일 in-flight. 여러 화면이 같은 프레임에 마운트하거나 사용자가 재시도를 연타해도
       왕복은 하나다. 백프레셔로 거절당한 구간에 요청을 더 얹지 않는 것이 이 가드의 목적이다.

       가드는 신원별이다. 전역 부울 하나로 두면 이전 신원의 조회가 도는 동안 새 신원의
       조회가 통째로 삼켜지고, 새 신원 화면이 값 없이 남는다. 신원이 다르면 통과시킨다.
       늦게 도착한 이전 신원 응답은 아래 stillMine 이 버린다. 두 장치는 역할이 다르다.
       in-flight 는 중복 발사를 막고, stillMine 은 남의 응답 채택을 막는다.
       가드는 왕복 상한이 있어야 성립한다. 상한이 없으면 미해소 요청 하나가 같은 신원의
       재조회를 영구 봉인해, 새로고침도 429 자동 재조회도 반응 없이 삼켜진다 */
    const fired = get().session?.user.external_id ?? null;
    const cur = get();
    if (cur.accountsInFlight && cur.accountsInFlightId === fired) return;
    set({ accountsInFlight: true, accountsInFlightId: fired });
    /* 발사 시점 신원과 현재 신원이 다르면 이 응답은 남의 것이다. 채택하지 않는다 */
    const stillMine = () => (get().session?.user.external_id ?? null) === fired;
    try {
      const assets = await withQueryTimeout((signal) => getDataSource().getAccounts(signal));
      if (!stillMine()) return;
      set({ assets, accountsHealth: { state: "ok", lastUpdatedAt: Date.now(), retryAfterSec: null } });
    } catch (e) {
      if (!stillMine()) return;
      /* 값이 있으면 stale, 없으면 failed다. 이 구분이 이 상태 모델의 요점이다.
         종전에는 둘을 한 플래그로 묶어, 한 번 실패하면 이미 받아둔 잔고까지 화면에서 사라졌다.
         429는 장애가 아니라 설계된 백프레셔라 회복 시각을 함께 든다(§2-3 429 클라이언트 규약) */
      const limited = e instanceof ApiError && e.status === 429;
      set((s) => ({
        accountsHealth: {
          state: s.assets.length > 0 ? "stale" : "failed",
          lastUpdatedAt: s.accountsHealth.lastUpdatedAt,
          retryAfterSec: limited ? clampRetrySec((e as ApiError).retryAfter) : null,
        },
      }));
      throw e;
    } finally {
      // 실패해도 세운다. 미로딩으로 두면 스켈레톤이 영원히 돈다(loadNotices와 같은 규약)
      // 내 비행일 때만 내린다. 신원이 바뀌어 새 조회가 떴으면 그 비행의 깃발이다
      set((s) => (s.accountsInFlightId === fired
        ? { accountsLoaded: true, accountsInFlight: false }
        : { accountsLoaded: true }));
    }
  },

  async loadOrders(market) {
    const ds = getDataSource();
    // 발사 시점 id 집합을 고정한다. 왕복 중 접수·확정된 주문은 이 스냅샷에 있을 수 없어
    // 응답 부재를 소멸로 해석하면 방금 낸 주문이 목록에서 사라진다
    const before = get();
    const known = new Set([...before.openOrders, ...before.closedOrders].map((o) => o.id));
    /* 주문도 사설 슬라이스다. 잔고와 같은 규율로 신원을 찍고 착지 때 대조한다.
       없으면 로그아웃 왕복 중 착지한 이전 신원 주문이 새 신원 목록에 그대로 병합된다 */
    const fired = before.session?.user.external_id ?? null;
    const stillMine = () => (get().session?.user.external_id ?? null) === fired;
    try {
      /* 전역이 드는 체결 목록은 첫 쪽뿐이다. 여기를 소비하는 것은 체결 통지 중복 판정
         (OrderPanel.shouldAnnounceFill)과 마이페이지 요약이고 둘 다 최근 것만 본다.
         아카이브 전체는 거래내역 화면이 자기 커서로 따라간다 */
      // 여기에는 in-flight 가드가 없어 봉인은 없지만, 미해소 요청이면 아래 finally 가
      // 실행되지 않아 ordersLoaded 가 서지 않고 스켈레톤이 영원히 돈다. 같은 상한을 건다
      const [open, closed] = await withQueryTimeout((signal) =>
        Promise.all([ds.getOpenOrders(market, signal), ds.getClosedOrders({ market }, signal)]),
      );
      if (!stillMine()) return;
      set((s) => nextOrderLists(s, mergeOrderSnapshot(s, [...open, ...closed.data], { known, market })));
      set({ closedOrdersTruncated: closed.has_more });
    } finally {
      // 실패해도 세운다. 미로딩으로 두면 스켈레톤이 영원히 돈다(loadNotices와 같은 규약)
      set({ ordersLoaded: true });
    }
  },

  upsertOrder(order) {
    set((s) => nextOrderLists(s, mergeOrderSnapshot(s, [order])));
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
    // added는 nextSet 기준으로 유일화한다. next 배열의 중복 원소가 added에 두 번 실리면
    // 훅의 Map.set이 첫 구독 핸들을 덮어써 refcount가 소리 없이 샌다
    added: [...nextSet].filter((d) => !prevSet.has(d)),
    removed: [...prevSet].filter((d) => !nextSet.has(d)),
  };
}

/**
 * 채널 구독 훅. destinations 변경 시 diff로 추가·제거된 채널만 구독·해제한다.
 * 전량 철거·재구독은 유지 중인 채널의 UNSUBSCRIBE/SUBSCRIBE와 채널 수 규모의 스냅샷 REST를 재발사한다.
 */
export function useLiveChannels(destinations: string[]): void {
  const apply = useExchangeStore((s) => s.apply);
  const key = destinations.join(",");
  // 중복 destination은 진입 시 제거한다. Map 키 충돌로 구독 핸들이 덮여 해제 시 참조가 새는 것을 막는다
  const stable = useMemo(() => Array.from(new Set(destinations)), [key]); // eslint-disable-line react-hooks/exhaustive-deps
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
    /* 새로고침 후 httpOnly refresh 쿠키로 access 재획득(§4-5). sim은 인메모리 세션을 그대로 반환.
       재시도는 여기서 하지 않는다. 복원 전체를 다시 부르면 이미 성공한 POST /auth/refresh 까지
       다시 쏘게 되고, 그것이 회원_설계가 금지한 동시 회전이다. 어댑터가 사슬 안에서
       /me 429 와 refresh 409 만 각각 1회 재시도한다 */
    void ds.refresh().catch(() => {});
    return off;
  }, [setSession]);
}

/**
 * 마켓 메타 보장 훅. tick_size·qty_scale은 표시 자릿수의 유일 근거라 미로드 상태로 그리면
 * 폴백 자릿수가 실제와 달라 가격·수량 표기가 위조된다. 동시 호출은 loadMarkets가 병합한다.
 */
export function useMarketSpecs(): Market[] {
  const markets = useExchangeStore((s) => s.markets);
  const connected = useExchangeStore((s) => s.connected);
  const loadMarkets = useExchangeStore((s) => s.loadMarkets);
  useEffect(() => {
    if (markets.length > 0) return;
    // 조회 실패는 폴백 자릿수로 열화한다. 화면을 막지 않고 연결 전이마다 다시 시도한다.
    // 재시도 트리거가 없으면 첫 조회 실패가 그 마운트 내내 위조된 자릿수를 고정한다
    void loadMarkets().catch(() => {});
  }, [markets.length, connected, loadMarkets]);
  return markets;
}

/** 회원 세션 셀렉터. 화면은 이 셀렉터로 로그인 상태를 구독한다 */
export function useMemberSession(): MemberSession | null {
  return useExchangeStore((s) => s.session);
}
