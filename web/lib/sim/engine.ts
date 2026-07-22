/**
 * lib/sim/engine.ts. 클라이언트 시뮬레이터 (런타임 외부 의존 0).
 * GBM(기하 브라운 운동) 틱 엔진 + 호가 10단계 + 체결 스트림 + 내 주문/자산 시뮬레이션.
 * 발행 메시지는 API 계약(§5-4/§5-5)의 WS 스키마를 그대로 미러한다.
 * seq는 채널(destination) 단위 독립 단조 증가, epoch는 리더 세대(§5-2).
 */

import type {
  Asset,
  Candle,
  CandleUnit,
  CoinMeta,
  EffectConfig,
  EffectLevel,
  EffectMode,
  Market,
  MyOrderMessage,
  MyAssetMessage,
  Order,
  OrderRequest,
  OrderbookMessage,
  OrderbookUnit,
  Side,
  Ticker,
  TickerMessage,
  Trade,
  TradeMessage,
  WsMessage,
} from "../types";
import { DEFAULT_EFFECT_CONFIG, SCHEMA_VERSION, dest } from "../types";
import { isoNow } from "../format";

/* ===== 코인 시드 (목업과 동일 시드가) =====
 * sim(프론트) 전용 로스터. 백엔드 미러가 아니라 데모 시세 생성원이다.
 * http 모드에서는 백엔드 markets만 노출되므로 이 목록과 무관하다. */

interface SimMarketSeed {
  market: string;
  koreanName: string;
  symbol: string;
  brandColor: string;
  brandTextDark?: boolean;
  seedPrice: number;      // 목업 표기 현재가
  seedChangeRate: number; // 목업 표기 전일대비 (부호 포함)
  sigma: number;          // 틱당 변동성 (GBM)
  qtyScale: number;
  baseVolume: number;     // 체결 1건 기준 수량 스케일
}

const SEEDS: SimMarketSeed[] = [
  { market: "KRW-BTC",  koreanName: "비트코인", symbol: "BTC",  brandColor: "#F7931A", seedPrice: 84_320_000, seedChangeRate: +0.0234, sigma: 0.00045, qtyScale: 8, baseVolume: 0.02 },
  { market: "KRW-ETH",  koreanName: "이더리움", symbol: "ETH",  brandColor: "#627EEA", seedPrice: 4_512_000,  seedChangeRate: -0.0108, sigma: 0.00055, qtyScale: 8, baseVolume: 0.4 },
  { market: "KRW-SOL",  koreanName: "솔라나",   symbol: "SOL",  brandColor: "#66F9A1", brandTextDark: true, seedPrice: 246_800, seedChangeRate: 0, sigma: 0.0007, qtyScale: 6, baseVolume: 6 },
  { market: "KRW-XRP",  koreanName: "리플",     symbol: "XRP",  brandColor: "#23292F", seedPrice: 742,  seedChangeRate: +0.0512, sigma: 0.0008, qtyScale: 2, baseVolume: 1800 },
  { market: "KRW-DOGE", koreanName: "도지코인", symbol: "DOGE", brandColor: "#C2A633", seedPrice: 182,  seedChangeRate: -0.0055, sigma: 0.0009, qtyScale: 2, baseVolume: 7000 },
  { market: "KRW-ADA",  koreanName: "에이다",   symbol: "ADA",  brandColor: "#0033AD", seedPrice: 1_084, seedChangeRate: +0.0087, sigma: 0.0007, qtyScale: 2, baseVolume: 1200 },
  { market: "KRW-TRX",  koreanName: "트론",     symbol: "TRX",  brandColor: "#EB0029", seedPrice: 385,     seedChangeRate: +0.0321, sigma: 0.0008, qtyScale: 2, baseVolume: 5200 },
  { market: "KRW-LINK", koreanName: "체인링크", symbol: "LINK", brandColor: "#2A5ADA", seedPrice: 28_540,  seedChangeRate: +0.0142, sigma: 0.0006, qtyScale: 4, baseVolume: 62 },
  { market: "KRW-DOT",  koreanName: "폴카닷",   symbol: "DOT",  brandColor: "#E6007A", seedPrice: 9_820,   seedChangeRate: -0.0231, sigma: 0.0007, qtyScale: 4, baseVolume: 210 },
  { market: "KRW-MATIC",koreanName: "폴리곤",   symbol: "MATIC",brandColor: "#8247E5", seedPrice: 724,     seedChangeRate: -0.0176, sigma: 0.0009, qtyScale: 2, baseVolume: 2600 },
  { market: "KRW-AVAX", koreanName: "아발란체", symbol: "AVAX", brandColor: "#E84142", seedPrice: 52_430,  seedChangeRate: +0.0405, sigma: 0.0008, qtyScale: 4, baseVolume: 38 },
  { market: "KRW-ATOM", koreanName: "코스모스", symbol: "ATOM", brandColor: "#2E3148", seedPrice: 9_640,   seedChangeRate: +0.0067, sigma: 0.0006, qtyScale: 4, baseVolume: 150 },
];

export const COIN_META: CoinMeta[] = SEEDS.map((s) => ({
  market: s.market,
  symbol: s.symbol,
  koreanName: s.koreanName,
  brandColor: s.brandColor,
  brandTextDark: s.brandTextDark,
}));

/** 호가 가격 단위 (Upbit 관례 축약). 저가 구간은 소수 틱으로 격자를 유지한다(sub-1 KRW 마켓 0 강제 방지) */
export function priceStep(price: number): number {
  if (price >= 2_000_000) return 1000;
  if (price >= 1_000_000) return 500;
  if (price >= 500_000) return 100;
  if (price >= 100_000) return 50;
  if (price >= 10_000) return 10;
  if (price >= 100) return 1;
  if (price >= 10) return 0.1;
  if (price >= 1) return 0.01;
  if (price >= 0.1) return 0.001;
  if (price >= 0.01) return 0.0001;
  return 0.00001;
}

function roundToStep(price: number): number {
  const step = priceStep(price);
  return Math.round(price / step) * step;
}

/** 시드 가능한 PRNG (mulberry32). 캔들 백필 재현성 확보 */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 표준정규 난수 (Box-Muller) */
function gauss(rand: () => number): number {
  const u = Math.max(rand(), 1e-12);
  const v = rand();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

export const CANDLE_UNIT_MS: Record<CandleUnit, number> = {
  "1s": 1_000, "1m": 60_000, "3m": 180_000, "5m": 300_000, "15m": 900_000,
  "30m": 1_800_000, "60m": 3_600_000, "240m": 14_400_000, "1d": 86_400_000, "1w": 604_800_000,
};

const FEE_RATE = 0.0005; // 마켓 고정 수수료 (§4-A-1 권위 소스)
const TICK_INTERVAL_MS = 700;
const EPOCH = 1;

interface MarketState {
  seed: SimMarketSeed;
  price: number;
  prevClose: number;
  high24h: number;
  low24h: number;
  accVolume24h: number;
  accPrice24h: number;
}

interface SimOrder extends Order {
  /** 내부 체결 스케줄 타이머 */
  _timer?: ReturnType<typeof setTimeout>;
}

type Listener = (msg: WsMessage) => void;

const EFFECT_STORAGE_KEY = "exchange.effects";
const EFFECT_LEVELS: EffectLevel[] = ["off", "subtle", "normal", "strong"];
const EFFECT_MODES: EffectMode[] = ["auto", "manual"];

/**
 * 데모 공유 저장소(localStorage)에서 효과 강도를 읽는다.
 * 관리·고객 sim 싱글턴 분리를 넘어 즉시 반영이 필요할 때의 읽기 소스(§4-2). 미설정·손상 시 안전 폴백.
 * 런타임 외부 의존이 아니다(브라우저 로컬 저장소).
 */
function readEffectConfig(): EffectConfig {
  if (typeof window === "undefined") return DEFAULT_EFFECT_CONFIG;
  try {
    const raw = window.localStorage.getItem(EFFECT_STORAGE_KEY);
    if (!raw) return DEFAULT_EFFECT_CONFIG;
    const parsed = JSON.parse(raw) as { mode?: unknown; level?: unknown };
    const mode = EFFECT_MODES.includes(parsed.mode as EffectMode) ? (parsed.mode as EffectMode) : DEFAULT_EFFECT_CONFIG.mode;
    const level = EFFECT_LEVELS.includes(parsed.level as EffectLevel) ? (parsed.level as EffectLevel) : DEFAULT_EFFECT_CONFIG.level;
    return { mode, level, schema_version: SCHEMA_VERSION };
  } catch {
    return DEFAULT_EFFECT_CONFIG;
  }
}

let seqCounter = 0;
function ulid(): string {
  // 계약상 외부 노출 id는 UUID(§7-1 v2.7 정정). 시뮬레이터는 고유성만 필요해 형태만 미러한 유사 id를 쓴다.
  const t = Date.now().toString(36).toUpperCase().padStart(9, "0");
  const r = Math.random().toString(36).slice(2, 10).toUpperCase();
  return `01${t}${r}${(seqCounter++ % 36).toString(36).toUpperCase()}`.slice(0, 26).padEnd(26, "0");
}

export class SimEngine {
  private states = new Map<string, MarketState>();
  private listeners = new Map<string, Set<Listener>>();
  private seqs = new Map<string, number>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private rand = mulberry32(20260721);
  private candleCache = new Map<string, Candle[]>();

  /* 내 계좌/주문 (시뮬레이션 시드 자산) */
  private balances = new Map<string, { balance: number; locked: number; avgBuyPrice: number }>();
  private orders = new Map<string, SimOrder>();
  private ordersByClientId = new Map<string, string>();

  constructor() {
    for (const seed of SEEDS) {
      const prevClose = roundToStep(seed.seedPrice / (1 + seed.seedChangeRate));
      this.states.set(seed.market, {
        seed,
        price: seed.seedPrice,
        prevClose,
        high24h: Math.max(seed.seedPrice, prevClose) * 1.008,
        low24h: Math.min(seed.seedPrice, prevClose) * 0.985,
        accVolume24h: seed.baseVolume * 2400,
        accPrice24h: seed.seedPrice * seed.baseVolume * 2400,
      });
    }
    // 시드 자산: 현금 1,000만원 + BTC/ETH 일부 보유 (자산 화면 데모용)
    this.balances.set("KRW", { balance: 10_000_000, locked: 0, avgBuyPrice: 0 });
    this.balances.set("BTC", { balance: 0.0918, locked: 0, avgBuyPrice: 70_000_000 });
    this.balances.set("ETH", { balance: 0.69, locked: 0, avgBuyPrice: 4_660_000 });
  }

  /* ===== 수명주기 ===== */

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), TICK_INTERVAL_MS);
  }

  stop(): void {
    if (this.timer) { clearInterval(this.timer); this.timer = null; }
  }

  get running(): boolean { return this.timer !== null; }

  /* ===== 구독 (STOMP destination 미러, §5-3) ===== */

  subscribe(destination: string, cb: Listener): () => void {
    let set = this.listeners.get(destination);
    if (!set) { set = new Set(); this.listeners.set(destination, set); }
    set.add(cb);
    // 구독 즉시 계약(§5-2): snapshot 1건 전송
    const snapshot = this.snapshotFor(destination);
    if (snapshot) queueMicrotask(() => { if (set!.has(cb)) cb(snapshot); });
    return () => { set!.delete(cb); };
  }

  private publish(destination: string, msg: WsMessage): void {
    const set = this.listeners.get(destination);
    if (!set) return;
    for (const cb of set) cb(msg);
  }

  private nextSeq(destination: string): number {
    const n = (this.seqs.get(destination) ?? 0) + 1;
    this.seqs.set(destination, n);
    return n;
  }

  private snapshotFor(destination: string): WsMessage | null {
    const m = /^\/topic\/(ticker|orderbook|trade)\.(.+)$/.exec(destination);
    if (m) {
      const [, type, market] = m;
      if (type === "ticker") return this.tickerMessage(market, "snapshot");
      if (type === "orderbook") return this.orderbookMessage(market, "snapshot");
      return null; // trade는 스냅샷 없음 (REST getTrades로 초기화)
    }
    if (destination === dest.myAsset) return this.myAssetMessage();
    return null;
  }

  /* ===== 틱 루프 (GBM) ===== */

  private tick(): void {
    // 매 틱 1~3개 마켓 무작위 갱신 → 현실적 비동기 갱신 감각
    const markets = [...this.states.keys()];
    const n = 1 + Math.floor(this.rand() * 3);
    for (let i = 0; i < n; i++) {
      const market = markets[Math.floor(this.rand() * markets.length)];
      this.tickMarket(market);
    }
  }

  private tickMarket(market: string): void {
    const st = this.states.get(market);
    if (!st) return;
    const { sigma } = st.seed;
    // GBM: S ← S·exp((−σ²/2)·dt + σ·√dt·z), dt=1 tick
    const z = gauss(this.rand);
    const next = roundToStep(st.price * Math.exp((-sigma * sigma) / 2 + sigma * z));
    const step = priceStep(st.price);
    const price = Math.max(step, Math.abs(next - st.price) < step ? st.price + Math.sign(z || 1) * step : next);
    const volume = st.seed.baseVolume * (0.05 + this.rand() * 0.9);

    st.price = price;
    st.high24h = Math.max(st.high24h, price);
    st.low24h = Math.min(st.low24h, price);
    st.accVolume24h += volume;
    st.accPrice24h += volume * price;

    // trade → ticker → orderbook 순서로 발행 (실거래소 유사)
    const tradeMsg: TradeMessage = {
      schema_version: SCHEMA_VERSION,
      type: "trade",
      market,
      epoch: EPOCH,
      seq: this.nextSeq(dest.trade(market)),
      trade_id: ulid(),
      price: String(price),
      volume: volume.toFixed(st.seed.qtyScale),
      taker_side: z >= 0 ? "bid" : "ask",
      timestamp: isoNow(),
    };
    this.publish(dest.trade(market), tradeMsg);
    this.publish(dest.ticker(market), this.tickerMessage(market, "realtime"));
    this.publish(dest.orderbook(market), this.orderbookMessage(market, "realtime"));

    // 미체결 지정가 주문 체결 판정
    this.matchOpenOrders(market, price);
  }

  /* ===== 메시지 빌더 ===== */

  private tickerOf(market: string): Ticker {
    const st = this.states.get(market)!;
    const diff = st.price - st.prevClose;
    const change = diff > 0 ? "RISE" : diff < 0 ? "FALL" : "EVEN";
    return {
      market,
      trade_price: String(st.price),
      change,
      change_rate: Math.abs(diff / st.prevClose).toFixed(6),
      change_price: String(Math.abs(Math.round(diff))),
      high_24h: String(roundToStep(st.high24h)),
      low_24h: String(roundToStep(st.low24h)),
      acc_trade_volume_24h: st.accVolume24h.toFixed(4),
      acc_trade_price_24h: String(Math.round(st.accPrice24h)),
      best_bid: String(st.price - priceStep(st.price)),
      best_ask: String(st.price + priceStep(st.price)),
      seq: this.seqs.get(dest.ticker(market)) ?? 0,
      timestamp: isoNow(),
    };
  }

  private tickerMessage(market: string, stream: "snapshot" | "realtime"): TickerMessage {
    const t = this.tickerOf(market);
    return {
      schema_version: SCHEMA_VERSION,
      type: "ticker",
      stream,
      epoch: EPOCH,
      seq: this.nextSeq(dest.ticker(market)),
      timestamp: t.timestamp,
      market: t.market,
      trade_price: t.trade_price,
      change: t.change,
      change_rate: t.change_rate,
      change_price: t.change_price,
      high_24h: t.high_24h,
      low_24h: t.low_24h,
      acc_trade_volume_24h: t.acc_trade_volume_24h,
      acc_trade_price_24h: t.acc_trade_price_24h,
      best_bid: t.best_bid,
      best_ask: t.best_ask,
    };
  }

  /** 호가 10단계 생성 */
  private orderbookUnits(market: string): { units: OrderbookUnit[]; totalAsk: number; totalBid: number } {
    const st = this.states.get(market)!;
    const step = priceStep(st.price);
    const units: OrderbookUnit[] = [];
    let totalAsk = 0, totalBid = 0;
    for (let i = 1; i <= 10; i++) {
      // 현재가에서 멀수록 잔량이 커지는 경향 + 노이즈
      const askSize = st.seed.baseVolume * (0.3 + this.rand() * 1.6) * (1 + i * 0.12);
      const bidSize = st.seed.baseVolume * (0.3 + this.rand() * 1.6) * (1 + i * 0.12);
      totalAsk += askSize; totalBid += bidSize;
      units.push({
        ask_price: String(st.price + step * i),
        ask_size: askSize.toFixed(st.seed.qtyScale),
        bid_price: String(st.price - step * i),
        bid_size: bidSize.toFixed(st.seed.qtyScale),
      });
    }
    return { units, totalAsk, totalBid };
  }

  private orderbookMessage(market: string, stream: "snapshot" | "realtime"): OrderbookMessage {
    const { units, totalAsk, totalBid } = this.orderbookUnits(market);
    const st = this.states.get(market)!;
    return {
      schema_version: SCHEMA_VERSION,
      type: "orderbook",
      market,
      epoch: EPOCH,
      seq: this.nextSeq(dest.orderbook(market)),
      stream,
      timestamp: isoNow(),
      total_ask_size: totalAsk.toFixed(st.seed.qtyScale),
      total_bid_size: totalBid.toFixed(st.seed.qtyScale),
      orderbook_units: units,
    };
  }

  private myAssetMessage(): MyAssetMessage {
    return {
      schema_version: SCHEMA_VERSION,
      type: "myAsset",
      epoch: EPOCH,
      seq: this.nextSeq(dest.myAsset),
      timestamp: isoNow(),
      assets: [...this.balances.entries()].map(([currency, b]) => ({
        currency,
        balance: b.balance.toFixed(currency === "KRW" ? 2 : 8),
        locked: b.locked.toFixed(currency === "KRW" ? 2 : 8),
      })),
    };
  }

  /* ===== REST 미러 (DataSource가 소비) ===== */

  getMarkets(): Market[] {
    return SEEDS.map((s) => ({
      market: s.market,
      korean_name: s.koreanName,
      base: s.symbol,
      quote: "KRW",
      state: "ACTIVE",
      tick_size: String(priceStep(s.seedPrice)),
      min_order_amount: "5000",
      max_order_amount: "1000000000",
      qty_scale: s.qtyScale,
      price_scale: 0,
      maker_fee: String(FEE_RATE),
      taker_fee: String(FEE_RATE),
    }));
  }

  getTicker(markets: string[]): Ticker[] {
    return markets.filter((m) => this.states.has(m)).map((m) => this.tickerOf(m));
  }

  /** sim은 config를 항상 제공한다(§4-2). 관리 쓰기는 범위 밖이라 저장소·기본값만 읽는다 */
  getEffectConfig(): EffectConfig {
    return readEffectConfig();
  }

  getOrderbook(market: string): OrderbookMessage {
    return this.orderbookMessage(market, "snapshot");
  }

  getTrades(market: string, count = 30): Trade[] {
    // 과거 체결 백필 (현재가 주변 랜덤워크)
    const st = this.states.get(market);
    if (!st) return [];
    const out: Trade[] = [];
    let p = st.price;
    const now = Date.now();
    for (let i = 0; i < count; i++) {
      const step = priceStep(p);
      const drift = Math.round(gauss(this.rand)) * step;
      p = Math.max(step, p - drift);
      out.push({
        trade_id: ulid(),
        market,
        price: String(roundToStep(p)),
        volume: (st.seed.baseVolume * (0.05 + this.rand() * 0.9)).toFixed(st.seed.qtyScale),
        taker_side: this.rand() < 0.5 ? "bid" : "ask",
        timestamp: isoNow(now - i * 1400),
        seq: 0,
      });
    }
    return out; // 시각 내림차순 (§2-6)
  }

  getCandles(market: string, unit: CandleUnit, count = 120): Candle[] {
    const st = this.states.get(market);
    if (!st) return [];
    const key = `${market}|${unit}`;
    let cached = this.candleCache.get(key);
    const unitMs = CANDLE_UNIT_MS[unit];
    const nowBucket = Math.floor(Date.now() / unitMs) * unitMs;
    if (!cached || cached.length < count) {
      // 현재가에서 역방향 GBM 백필. 시드 PRNG로 재현성 확보
      const rand = mulberry32(20260721 ^ market.length * 7919 ^ unitMs % 104729);
      const sigmaU = st.seed.sigma * Math.sqrt(unitMs / TICK_INTERVAL_MS) * 0.35;
      const candles: Candle[] = [];
      let close = st.price;
      for (let i = 0; i < Math.max(count, 200); i++) {
        const t = nowBucket - i * unitMs;
        const open = roundToStep(close * Math.exp(-((-sigmaU * sigmaU) / 2 + sigmaU * gauss(rand))));
        const hi = Math.max(open, close) * (1 + Math.abs(gauss(rand)) * sigmaU * 0.5);
        const lo = Math.min(open, close) * (1 - Math.abs(gauss(rand)) * sigmaU * 0.5);
        candles.push({
          market,
          candle_time: isoNow(t),
          open: String(open),
          high: String(roundToStep(hi)),
          low: String(roundToStep(lo)),
          close: String(close),
          volume: (st.seed.baseVolume * (10 + rand() * 90)).toFixed(4),
          trade_count: Math.floor(50 + rand() * 400),
          seq: 0,
        });
        close = open;
      }
      cached = candles; // 시각 내림차순
      this.candleCache.set(key, cached);
    }
    return cached.slice(0, count);
  }

  getAccounts(): Asset[] {
    return [...this.balances.entries()].map(([currency, b]) => ({
      currency,
      balance: b.balance.toFixed(currency === "KRW" ? 2 : 8),
      locked: b.locked.toFixed(currency === "KRW" ? 2 : 8),
      avg_buy_price: String(b.avgBuyPrice),
      unit_currency: "KRW",
    }));
  }

  getOrders(open: boolean, market?: string): Order[] {
    return [...this.orders.values()]
      .filter((o) => (market ? o.market === market : true))
      .filter((o) => (open ? o.status === "OPEN" || o.status === "PARTIAL"
                          : o.status === "FILLED" || o.status === "CANCELED" || o.status === "REJECTED"))
      .sort((a, b) => (a.created_at < b.created_at ? 1 : -1))
      .map(({ _timer, ...o }) => o);
  }

  /* ===== 주문 시뮬레이션 (멱등키 client_order_id, §2-4) ===== */

  placeOrder(req: OrderRequest): Order {
    if (!req.client_order_id) throw new SimApiError("client_order_id_required", "client_order_id가 필요해요.");
    // 멱등 replay: 동일 키 → 기존 주문 반환
    const existingId = this.ordersByClientId.get(req.client_order_id);
    if (existingId) {
      const { _timer, ...o } = this.orders.get(existingId)!;
      return o;
    }
    const st = this.states.get(req.market);
    if (!st) throw new SimApiError("invalid_parameter", "존재하지 않는 마켓이에요.");

    const isMarketBuy = req.ord_type === "market" && req.side === "bid";
    const price = req.price != null ? parseFloat(req.price) : null;
    const volume = req.volume != null ? parseFloat(req.volume) : null;
    const notional = isMarketBuy ? price! : (price ?? st.price) * (volume ?? 0);
    if (notional < 5000) throw new SimApiError("under_min_order", "최소 주문 금액은 5,000원이에요.");

    // 잔고 락 (bid=KRW 락(대금+수수료), ask=코인 락)
    const base = req.market.split("-")[1];
    if (req.side === "bid") {
      const need = notional * (1 + FEE_RATE);
      const krw = this.balances.get("KRW")!;
      if (krw.balance < need) throw new SimApiError("insufficient_funds", "주문 가능 금액이 부족해요.");
      krw.balance -= need; krw.locked += need;
    } else {
      const coin = this.balances.get(base) ?? { balance: 0, locked: 0, avgBuyPrice: 0 };
      if (coin.balance < (volume ?? 0)) throw new SimApiError("insufficient_funds", "보유 수량이 부족해요.");
      coin.balance -= volume!; coin.locked += volume!;
      this.balances.set(base, coin);
    }

    const order: SimOrder = {
      schema_version: SCHEMA_VERSION,
      id: ulid(),
      client_order_id: req.client_order_id,
      market: req.market,
      side: req.side,
      ord_type: req.ord_type,
      status: "OPEN",
      price: req.price ?? null,
      volume: req.volume ?? null,
      remaining_volume: req.volume ?? "0",
      executed_volume: "0",
      executed_funds: "0",
      remaining_notional: isMarketBuy ? req.price! : null,
      paid_fee: "0",
      trades_count: 0,
      created_at: isoNow(),
      epoch: EPOCH,
      seq: this.nextSeq("journal"),
    };
    this.orders.set(order.id, order);
    this.ordersByClientId.set(order.client_order_id, order.id);

    this.emitMyOrder(order, null);
    this.emitMyAsset();

    // 시장가: 즉시 체결. 지정가: 현재가와 교차 시 지연 체결, 아니면 OPEN 유지 → 틱마다 재판정
    if (req.ord_type === "market") {
      order._timer = setTimeout(() => this.fillOrder(order.id, st.price), 250 + Math.random() * 400);
    } else if (price != null && this.crosses(req.side, price, st.price)) {
      order._timer = setTimeout(() => this.fillOrder(order.id, price), 300 + Math.random() * 900);
    }
    const { _timer, ...pub } = order;
    return pub;
  }

  cancelOrder(idOrClientId: string): Order {
    const id = this.orders.has(idOrClientId) ? idOrClientId : this.ordersByClientId.get(idOrClientId);
    const order = id ? this.orders.get(id) : undefined;
    if (!order) throw new SimApiError("order_not_found", "주문을 찾을 수 없어요.");
    if (order.status === "CANCELED") { const { _timer, ...o } = order; return o; } // 취소 멱등 (§2-4)
    if (order.status === "FILLED") throw new SimApiError("invalid_parameter", "이미 체결 완료된 주문이에요.");

    if (order._timer) clearTimeout(order._timer);
    // 락 해제 (잔여분)
    const base = order.market.split("-")[1];
    const remaining = parseFloat(order.remaining_volume);
    if (order.side === "bid") {
      const krw = this.balances.get("KRW")!;
      // 시장가 매수는 수량이 아니라 잔여 대금(remaining_notional)으로 락을 잡는다.
      // 지정가 경로처럼 price*remaining으로 계산하면 remaining_volume=0이라 환급이 0이 되어 락이 영구 잔존한다.
      const refund =
        order.ord_type === "market"
          ? parseFloat(order.remaining_notional ?? "0") * (1 + FEE_RATE)
          : (order.price ? parseFloat(order.price) : 0) * remaining * (1 + FEE_RATE);
      const back = Math.min(krw.locked, refund);
      krw.locked -= back; krw.balance += back;
    } else {
      const coin = this.balances.get(base)!;
      const back = Math.min(coin.locked, remaining);
      coin.locked -= back; coin.balance += back;
    }
    order.status = "CANCELED";
    order.canceled_at = isoNow();
    this.emitMyOrder(order, null);
    this.emitMyAsset();
    const { _timer, ...o } = order;
    return o;
  }

  private crosses(side: Side, limitPrice: number, marketPrice: number): boolean {
    return side === "bid" ? limitPrice >= marketPrice : limitPrice <= marketPrice;
  }

  private matchOpenOrders(market: string, price: number): void {
    for (const order of this.orders.values()) {
      if (order.market !== market || order.ord_type !== "limit") continue;
      if (order.status !== "OPEN" && order.status !== "PARTIAL") continue;
      if (order._timer) continue;
      const limit = order.price ? parseFloat(order.price) : NaN;
      if (Number.isFinite(limit) && this.crosses(order.side, limit, price)) {
        order._timer = setTimeout(() => this.fillOrder(order.id, limit), 200 + Math.random() * 700);
      }
    }
  }

  /** 전량 체결 처리 (지정가=주문가, 시장가=현재가 기준) */
  private fillOrder(id: string, fillPrice: number): void {
    const order = this.orders.get(id);
    if (!order || (order.status !== "OPEN" && order.status !== "PARTIAL")) return;
    order._timer = undefined;

    const st = this.states.get(order.market)!;
    const base = order.market.split("-")[1];
    const isMarketBuy = order.ord_type === "market" && order.side === "bid";
    const volume = isMarketBuy
      ? parseFloat(order.remaining_notional ?? "0") / fillPrice
      : parseFloat(order.remaining_volume || order.volume || "0");
    const funds = volume * fillPrice;
    const fee = funds * FEE_RATE;

    if (order.side === "bid") {
      const krw = this.balances.get("KRW")!;
      const locked = funds * (1 + FEE_RATE);
      krw.locked = Math.max(0, krw.locked - locked);
      const coin = this.balances.get(base) ?? { balance: 0, locked: 0, avgBuyPrice: 0 };
      const prevQty = coin.balance + coin.locked;
      coin.avgBuyPrice = prevQty + volume > 0
        ? (coin.avgBuyPrice * prevQty + fillPrice * volume) / (prevQty + volume)
        : fillPrice;
      coin.balance += volume;
      this.balances.set(base, coin);
    } else {
      const coin = this.balances.get(base)!;
      coin.locked = Math.max(0, coin.locked - volume);
      const krw = this.balances.get("KRW")!;
      krw.balance += funds - fee;
    }

    order.status = "FILLED";
    order.executed_volume = volume.toFixed(st.seed.qtyScale);
    order.remaining_volume = "0";
    order.remaining_notional = isMarketBuy ? "0" : order.remaining_notional;
    order.executed_funds = funds.toFixed(2);
    order.paid_fee = fee.toFixed(2);
    order.trades_count += 1;

    this.emitMyOrder(order, {
      trade_id: ulid(),
      price: String(roundToStep(fillPrice)),
      volume: order.executed_volume,
      liquidity_flag: order.ord_type === "market" ? "TAKER" : "MAKER",
      fee: fee.toFixed(2),
    });
    this.emitMyAsset();
  }

  private emitMyOrder(order: SimOrder, trade: MyOrderMessage["trade"]): void {
    const msg: MyOrderMessage = {
      schema_version: SCHEMA_VERSION,
      type: "myOrder",
      epoch: EPOCH,
      seq: this.nextSeq(dest.myOrder),
      timestamp: isoNow(),
      id: order.id,
      client_order_id: order.client_order_id,
      market: order.market,
      side: order.side,
      status: order.status,
      price: order.price,
      executed_volume: order.executed_volume,
      remaining_volume: order.remaining_volume,
      reject_reason: null,
      trade: trade ?? null,
    };
    this.publish(dest.myOrder, msg);
  }

  private emitMyAsset(): void {
    this.publish(dest.myAsset, this.myAssetMessage());
  }
}

/** API 계약 §2-3 표준 에러 name을 미러하는 시뮬레이터 에러 */
export class SimApiError extends Error {
  constructor(public name2: string, message: string) {
    super(message);
    this.name = name2;
  }
}
