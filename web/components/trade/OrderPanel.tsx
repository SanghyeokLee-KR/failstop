"use client";

/**
 * 확인은 인라인 2단계다(§2.5). 1차 클릭에 CTA가 확인 라벨로 바뀌고 3초 내 재클릭이 확정,
 * Esc·외부 클릭·3초 경과·입력 변경으로 원복한다. 모달·바텀시트는 쓰지 않는다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import PanelCard from "@/components/PanelCard";
import SegmentedTab from "@/components/SegmentedTab";
import Skeleton from "@/components/Skeleton";
import { toast } from "@/components/Toast";
import { useExchangeStore, useLiveChannels, useMemberSession } from "@/lib/store";
import type { AccountsHealth } from "@/lib/store";
import { decideBalance } from "./derive";
import { StaleBadge } from "@/components/StatusSignals";
import { dest, type OrderRequest, type Side } from "@/lib/types";
import { getDataSource } from "@/lib/api/datasource";
import { loginHref } from "@/lib/auth/nextPath";
import { ApiError } from "@/lib/api/httpDatasource";
import { priceStep, SimApiError } from "@/lib/sim/engine";
import { formatKRW, formatPrice, formatQty, toNum } from "@/lib/format";
import {
  compareDecimal,
  decimalsOf,
  fromScaledInt,
  mulDecimal,
  normalizeDecimal,
  scaledIntRound,
  truncToScale,
} from "@/lib/decimal";
import { bidLockAmount, buyFeeBase, marketBidLockAmount, sellFeeQuote } from "@/lib/orderContract";
import {
  initOrderKey,
  isFinalRejectStatus,
  onInputChanged,
  onSubmitAccepted,
  onSubmitFailed,
  resolveKeyForSubmit,
  shouldApplyInputChange,
  type OrderKeyState,
} from "./orderKey";
import { placedNotice, shouldAnnounceFill } from "./fillNotice";
import {
  isSuspended,
  ORDER_BLOCKED_LABEL,
  ORDER_BLOCKED_SR,
  PENDING_RESULT_LABEL,
  PENDING_RESULT_SR,
  pendingCauseOf,
} from "@/lib/tradingStatus";
import type { PricePrefill } from "./prefill";
import OrderConfirmDialog from "./OrderConfirmDialog";
import {
  autoFillPrice,
  committedRatioOf,
  formatPriceField,
  isLargeOrder as computeLargeOrder,
  pctOfDecimal,
  qtyFromTotal,
  qtyFromTotalInput,
  resnapAutoPrice,
  roundToTick,
  stepLimitPrice,
} from "./orderMath";
import styles from "./OrderPanel.module.css";

// 수수료·최소주문의 권위 소스는 GET /markets 마켓 메타다(§4-A-1, 관리자 런타임 변경 가능).
// 아래 상수는 메타 미로딩 구간의 표시·검증 폴백일 뿐이다.
// 프리렌더 골격(OrderPanelSkeleton)이 콜드 마운트 표기를 그대로 재현하려고 같은 상수를 읽는다.
export const FALLBACK_FEE_RATE_STR = "0.0005";
/* 콜드 마운트 CTA 라벨. 프리렌더 골격과 실제 패널의 첫 라벨을 맞춰 하이드레이션 순간
   라벨이 튀지 않게 한다. 이 시점은 지정가·가격 미입력이라 필요한 것이 가격이다 */
export const COLD_CTA_LABEL = "가격 입력";
const FALLBACK_MIN_ORDER_KRW = "5000";
const CONFIRM_TIMEOUT_MS = 3000;

type OrdType = "limit" | "market";

// 프리렌더 골격이 같은 배열로 방향 탭을 그린다. 라벨·톤이 갈리면 하이드레이션 순간 탭이 바뀐다
export const SIDE_ITEMS = [
  { value: "bid" as Side, label: "매수", tone: "buy" as const },
  { value: "ask" as Side, label: "매도", tone: "sell" as const },
];

interface Props {
  market: string;
  initialSide: Side;
  /** 호가 클릭·쿼리 승계 프리필. 가격만 주입, side 유지 (§2.5) */
  prefill: PricePrefill | null;
  /** 계좌 로드 실패 여부. 실패면 잔고 미확정으로 CTA를 막고 재시도를 노출한다 */
  /** 잔고 신선도(§2-3 429 클라이언트 규약). 판정은 derive.decideBalance 가 한다 */
  accountsHealth?: AccountsHealth;
  /** 계좌 재조회 트리거 (부모 소유) */
  onRetryAccounts?: () => void;
}

function newClientOrderId(): string {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

const IDLE_HEALTH: AccountsHealth = { state: "idle", lastUpdatedAt: null, retryAfterSec: null };

export default function OrderPanel({ market, initialSide, prefill, accountsHealth = IDLE_HEALTH, onRetryAccounts }: Props) {
  const base = market.split("-")[1] ?? "";

  const ticker = useExchangeStore((s) => s.tickers[market]);
  const assets = useExchangeStore((s) => s.assets);
  const markets = useExchangeStore((s) => s.markets);
  const loadOrders = useExchangeStore((s) => s.loadOrders);
  const upsertOrder = useExchangeStore((s) => s.upsertOrder);
  const apply = useExchangeStore((s) => s.apply);
  // 묶임 금액이 몇 건에서 나왔는지를 같이 말하려고 구독한다. 금액만 있으면 취소할 대상이
  // 하나인지 열인지 모른 채 미체결 목록을 먼저 열어봐야 한다
  const openOrders = useExchangeStore((s) => s.openOrders);

  // 게스트(데모 둘러보기) 잔고 표식용. 회원 세션이 서면 배지를 내린다
  const isGuest = useMemberSession() === null;
  /* 거래 일시 중단(§4-A-7). 서버가 확정한 상태라 '낡은 값이 확정 행위를 막지 않는다'(§2-3)의 예외다.
     입력칸은 그대로 두어 재개 뒤 바로 낼 수 있게 하고 확정 버튼만 막는다 */
  const suspended = useExchangeStore((s) => isSuspended(s.tradingStatus));
  const pendingHere = useExchangeStore((s) => s.pendingOrders.some((p) => p.market === market));
  const trackPendingOrder = useExchangeStore((s) => s.trackPendingOrder);
  const loadTradingStatus = useExchangeStore((s) => s.loadTradingStatus);

  const [side, setSide] = useState<Side>(initialSide);
  const [ordType, setOrdType] = useState<OrdType>("limit");
  const [priceRaw, setPriceRaw] = useState<string>("");
  const [qtyRaw, setQtyRaw] = useState<string>("");
  const [amountRaw, setAmountRaw] = useState<string>(""); // 시장가 매수 금액
  const [activePct, setActivePct] = useState<number | null>(null);
  const [armed, setArmed] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  // 지연 초기화: 매 렌더 키 생성(Date.now·Math.random) 방지
  const keyStateRef = useRef<OrderKeyState | null>(null);
  const keyState = (): OrderKeyState => (keyStateRef.current ??= initOrderKey(newClientOrderId));
  // 제출 중 dirty 전이 동결. state 갱신 지연에 걸리지 않도록 ref로 동기 판정
  const submittingRef = useRef(false);
  // 마지막으로 dirty에 반영한 입력 서명. 제출 종료로 재실행될 때 실제 변경만 가려내는 기준이다
  const lastInputSigRef = useRef<string | null>(null);
  const armTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const ctaRef = useRef<HTMLButtonElement>(null);

  useLiveChannels(useMemo(() => [dest.myAsset], []));

  /* myOrder 수신 시 스토어 반영 + 체결 Toast */
  useEffect(() => {
    return getDataSource().subscribe(dest.myOrder, (msg) => {
      if (msg.type !== "myOrder") {
        apply(msg);
        return;
      }
      // 판정은 apply 이전 상태로 한다. apply가 종결 목록에 넣은 뒤에는 신규·재전달을 못 가른다
      const announce = shouldAnnounceFill(msg, useExchangeStore.getState().closedOrders);
      apply(msg);
      if (announce) toast("주문 체결 완료", "success");
    });
  }, [apply]);

  const marketInfo = markets.find((m) => m.market === market);
  const qtyScale = marketInfo?.qty_scale ?? 8;
  const curPrice = ticker ? toNum(ticker.trade_price) : 0;

  // 가격·퍼센트칩·틱 계산용 number(격자 선택·역산 보조). 총액·수수료·검증·제출은 아래 십진 문자열이 단일 기준.
  const price = ordType === "market" ? curPrice : toNum(priceRaw);
  const isMarketBuy = ordType === "market" && side === "bid";

  /* 시장가 매수는 금액 기반(notional) 주문이고, 서버가 quote 자산 scale 18 마켓에서만 받는다
     (OrderGateway market_buy_quote_scale_18, DB 설계 §1.4 v2.9 앱 가드). notional 나눗셈이
     scale 18 소진액을 만들어 quote scale<18 이면 정산 양자화 재검증에서 fail-fast 하기 때문이다.
     이 저장소의 KRW 마켓은 전부 quote scale 0 이라 시장가 매수가 한 건도 통과할 수 없다.
     DB 설계 §1.4 도 "금액기반(notional) 주문은 사용하지 않는다"로 못박고 있다.
     그런데 화면은 그 탭을 계속 세워 두어, 누르면 400 invalid_parameter 만 돌아왔다.
     사용자에게는 "주문했는데 아무 일도 안 일어남"으로 보인다. 낼 수 없는 주문은 걷는다.
     시장가 매도는 수량 기반이라 이 가드를 타지 않으므로 그대로 둔다. */
  const marketBuyAllowed = (marketInfo?.price_scale ?? 0) === 18;
  const ordTypes: OrdType[] = side === "bid" && !marketBuyAllowed ? ["limit"] : ["limit", "market"];

  // 매도에서 시장가를 고른 뒤 매수로 넘어오면 낼 수 없는 상태가 남는다. 지정가로 되돌린다
  useEffect(() => {
    if (!ordTypes.includes(ordType)) setOrdType("limit");
  }, [ordTypes, ordType]);

  // 마켓 스펙 틱(권위). 입력값에 따라 흔들리지 않아 재스냅 트리거로 안전하다
  const specTick = marketInfo?.tick_size && toNum(marketInfo.tick_size) > 0 ? marketInfo.tick_size : null;
  // 가격 격자. 스펙이 없으면 가격대별 priceStep을 쓴다. 저가·소수틱 마켓 0 강제 방지
  const priceTick = specTick ?? String(priceStep(price > 0 ? price : curPrice > 0 ? curPrice : 1));

  // 자동 채움(프리필·현재가·스텝·blur 스냅)이 마지막으로 쓴 값. 사용자가 타이핑 중인 값과 구분해
  // 스펙 도착 시 재스냅 대상을 격자에 맞춰 둔 값으로만 한정한다
  const autoPriceRef = useRef("");
  // 렌더 중 쓰기는 tickRef 계열과 같은 최신값 참조 패턴이다. 이 패널이 transition·Suspense
  // 안으로 들어가면 폐기된 렌더의 값이 남을 수 있어 그때 재검토 대상이다
  const priceRawRef = useRef(priceRaw);
  priceRawRef.current = priceRaw;
  // 이펙트에서만 읽는다. 콜백 참조를 고정해 스펙 도착이 프리필 재주입을 부르지 않게 한다
  const specTickRef = useRef(specTick);
  specTickRef.current = specTick;

  /* 격자에 맞춘 가격을 필드에 쓰고 자동 채움분으로 기록한다 */
  const setSnappedPrice = useCallback((snapped: string) => {
    autoPriceRef.current = snapped;
    setPriceRaw(snapped);
  }, []);
  const fillPrice = useCallback(
    (value: string | number) => {
      setSnappedPrice(autoFillPrice(value, specTickRef.current, priceStep));
    },
    [setSnappedPrice],
  );

  /* 프리필은 가격만 주입, side 유지. 시장가 상태면 가격 입력이 보이도록 지정가 전환.
     제출 in-flight 중에는 보류한다. 동결된 확정 내용을 프리필이 바꾸면 동일 키·다른 내용이 된다 */
  useEffect(() => {
    if (!prefill) return;
    if (submittingRef.current) return;
    setOrdType((t) => (t === "market" ? "limit" : t));
    fillPrice(prefill.price);
    setActivePct(null);
    /* seq는 프리필 출처 판별자다. 0은 마운트 시 쿼리 승계라 포커스를 뺏으면 화면이 튀고,
       1 이상은 사용자가 방금 누른 호가라 수량 입력으로 이어준다(호가 첫 행에서 수량까지 27탭) */
    if (prefill.seq > 0) {
      document.getElementById("panel-ord-qty")?.focus();
    }
  }, [prefill, fillPrice]);

  /* 프리필 없이 현재가 수신 시 1회 가격 채움. 무체결 마켓(trade_price null)은 채울 값이 없어 대기 */
  const prefilled = useRef(prefill != null);
  useEffect(() => {
    if (!prefilled.current && ticker?.trade_price != null) {
      prefilled.current = true;
      fillPrice(ticker.trade_price);
    }
  }, [ticker, fillPrice]);

  /* 폴백 격자로 채운 값을 그대로 두면 표시가와 제출 기준가가 갈린다. 트리거를 스펙 틱으로
     좁히고, submitting을 deps에 넣어 in-flight로 건너뛴 재스냅을 제출 종료 시 재시도한다 */
  useEffect(() => {
    if (submitting) return;
    const next = resnapAutoPrice(priceRawRef.current, autoPriceRef.current, specTick);
    if (next !== null) setSnappedPrice(next);
  }, [specTick, submitting, setSnappedPrice]);

  // armed 라벨용 총액 스냅샷. 시장가 매도의 라이브 파생 총액이 armed 3초 창 동안 계속 바뀌면
  // aria-live 버튼이 틱마다 확정 문장을 재낭독하고 확정 직전 금액이 흔들린다. arm 시점에 동결한다
  const [armedTotal, setArmedTotal] = useState<string | null>(null);

  const disarm = useCallback(() => {
    setArmed(false);
    setArmedTotal(null);
    if (armTimer.current) {
      clearTimeout(armTimer.current);
      armTimer.current = null;
    }
  }, []);

  // 확인 대기 중에 중단되면 확인 상태를 거둔다. 재개 뒤 첫 클릭이 곧바로 확정이 되면 안 된다
  useEffect(() => {
    if (suspended) disarm();
  }, [suspended, disarm]);

  /* Esc·외부 클릭으로 확인 상태 원복 (§2.5) */
  useEffect(() => {
    if (!armed) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") disarm();
    };
    const onDown = (e: MouseEvent) => {
      if (ctaRef.current && !ctaRef.current.contains(e.target as Node)) disarm();
    };
    window.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onDown);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onDown);
    };
  }, [armed, disarm]);

  /* 제출 중에는 dirty 전이를 동결한다. 5XX 재시도가 새 키를 발급하면 이중접수다(§7-6).
     입력 서명을 비교해 실제로 달라졌을 때만, 제출 종료 뒤 정확히 한 번 반영한다 */
  useEffect(() => {
    const sig = [side, ordType, priceRaw, qtyRaw, amountRaw, market].join("|");
    if (!shouldApplyInputChange(sig, lastInputSigRef.current, submitting)) return;
    lastInputSigRef.current = sig;
    disarm();
    keyStateRef.current = onInputChanged(keyState());
  }, [side, ordType, priceRaw, qtyRaw, amountRaw, market, submitting, disarm]);

  useEffect(
    () => () => {
      if (armTimer.current) clearTimeout(armTimer.current);
    },
    [],
  );

  // 마켓 메타 소비(§4-A-1). taker_fee가 표시·잔고 검증·프리셋 역산의 단일 수수료 기준
  const feeRateStr = marketInfo?.taker_fee ?? FALLBACK_FEE_RATE_STR;
  // 라벨 % 표기는 수수료율에서 파생(0.0005 -> 0.05)
  const feePctStr = mulDecimal(feeRateStr, "100");
  const minOrderStr = marketInfo?.min_order_amount ?? FALLBACK_MIN_ORDER_KRW;
  const maxOrderStr = marketInfo?.max_order_amount ?? null;
  // quote 자산 scale. 락 UP 양자화와 매도 수수료 선양자화의 격자다
  const priceScale = marketInfo?.price_scale ?? 0;

  // 단일 십진 기준. 수량은 qty_scale 격자로 내림(잔고 안전, 반올림 아님), 가격은 틱 반올림.
  // 총액·수수료·최소주문·잔고 검증·제출 volume이 같은 문자열 값을 공유해 float 왕복과 경계 불일치를 없앤다.
  const qtyStr = truncToScale(qtyRaw || "0", qtyScale);
  const amountStr = amountRaw === "" ? "0" : normalizeDecimal(amountRaw);
  // 총액 기준가: 지정가는 틱 반올림가, 시장가 매도는 현재가 추정, 시장가 매수는 가격 미사용(총액=금액)
  const priceBasisStr = isMarketBuy
    ? "0"
    : ordType === "market"
      ? normalizeDecimal(ticker?.trade_price ?? "0")
      : roundToTick(priceRaw || "0", priceTick);
  const totalStr = isMarketBuy ? amountStr : mulDecimal(priceBasisStr, qtyStr);
  // 원 단위 반올림 총액(BigInt). 시장가 매수 notional 전용.
  // 최소주문 판정에 쓰면 [min-0.5, min) 구간이 half-up으로 통과해 서버 정확 비교와 경계가 어긋난다
  const totalWon = scaledIntRound(totalStr, 0);
  // 예약(락)은 수수료를 포함하지 않는다(§4-B-5 v2.7). 대금을 price_scale로 UP 양자화한 값이
  // 실제로 잡히는 금액이므로 매수 잔고 검증 기준도 이것이다. 구 모델(대금+수수료)로 재면
  // 서버가 통과시키는 주문을 인라인 검증이 막는다
  const bidLockStr = isMarketBuy
    ? marketBidLockAmount(amountStr, priceScale)
    : bidLockAmount(priceBasisStr, qtyStr, priceScale);
  // 수수료는 수취 자산 기준이다(§4-B-5 v2.7). 매수는 수취 base 수량 × taker(단위 base),
  // 매도는 체결대금 × taker(단위 quote). 시장가 매수의 수취 수량은 현재가 기준 추정이다
  const recvQtyStr = isMarketBuy
    ? (qtyFromTotal(amountStr, normalizeDecimal(ticker?.trade_price ?? "0"), qtyScale) ?? "0")
    : qtyStr;
  const feeStr =
    side === "bid"
      ? buyFeeBase(recvQtyStr, feeRateStr, qtyScale)
      : sellFeeQuote(totalStr, feeRateStr, priceScale);
  const feeUnit = side === "bid" ? base : "원";

  const availKRWStr = assets.find((a) => a.currency === "KRW")?.balance ?? "0";
  const availCoinStr = assets.find((a) => a.currency === base)?.balance ?? "0";
  const availKRW = toNum(availKRWStr);
  const availCoin = toNum(availCoinStr);
  /* 묶임 잔고. 미체결 지정가가 잡아둔 몫이다. 이 값을 감추면 "주문가능"이 왜 보유량보다
     적은지가 화면 어디에도 없다. 매수는 원화가, 매도는 코인이 묶이므로 지금 보는 방향의
     자산만 싣는다. 반대 방향 묶임까지 같이 세우면 어느 쪽이 내 주문가능을 깎았는지 흐려진다 */
  const lockedStr = side === "bid"
    ? (assets.find((a) => a.currency === "KRW")?.locked ?? "0")
    : (assets.find((a) => a.currency === base)?.locked ?? "0");
  const hasLocked = compareDecimal(lockedStr, "0") > 0;
  /* 묶임을 만든 건수. 방향을 맞춰 센다. 매수 화면에서 매도 미체결까지 세면 금액과 건수가
     서로 다른 것을 세게 된다. 마켓은 걸러내지 않는다. 원화 묶임은 전 마켓 매수 주문의 합이라
     이 화면 마켓만 세면 건수가 금액을 설명하지 못한다 */
  const openCount = openOrders.filter(
    (o) => o.side === side && (o.status === "OPEN" || o.status === "PARTIAL"),
  ).length;
  /* 잔고 표시·CTA 판정을 순수 함수 한 곳으로 모은다(derive.decideBalance).
     화면이 오류 코드를 직접 분기하면 상태가 늘 때마다 분기가 흩어지고, 그 분기는 테스트로
     고정할 수 없다. 여기서는 결과만 읽는다. 표는 tests/balanceFreshness.test.ts 가 든다 */
  const balance = decideBalance({
    state: accountsHealth.state,
    hasTicker: Boolean(ticker),
    retryAfterSec: accountsHealth.retryAfterSec,
  });
  const loadingBalance = balance.view === "loading";

  /* 최소·최대는 반올림 총액이 아니라 문자열 십진 정확 비교로 판정한다. 서버가 NUMERIC 정확
     비교라 클라이언트만 반올림하면 경계 구간 주문이 인라인 검증을 통과한 뒤 거부된다(§4-B-1) */
  const totalPositive = compareDecimal(totalStr, "0") > 0;
  let error: string | null = null;
  if (totalPositive && compareDecimal(totalStr, minOrderStr) < 0)
    error = `최소 주문 금액은 ${formatKRW(minOrderStr)}원`;
  else if (totalPositive && maxOrderStr !== null && compareDecimal(totalStr, maxOrderStr) > 0)
    error = `최대 주문 금액은 ${formatKRW(maxOrderStr)}원`;
  else if (side === "bid" && totalPositive && compareDecimal(bidLockStr, availKRWStr) > 0)
    error = "주문 가능 금액 초과";
  else if (side === "ask" && compareDecimal(qtyStr, availCoinStr) > 0) error = "보유 수량 초과";
  const ready = totalPositive && error === null && balance.ready;

  /* 비활성 사유를 CTA 라벨이 말한다. 별도 안내 행을 더하면 스켈레톤과의 높이 계약이 깨진다 */
  const needLabel = ordType === "limit" && !(price > 0)
    ? COLD_CTA_LABEL
    : isMarketBuy
      ? "금액 입력"
      : "수량 입력";

  // 가용 대비 큰 비중이면 인라인 확인 대신 다이얼로그로 오발주를 막는다(임계 판정은 number로 충분).
  // 분자는 실제로 묶이는 금액(락)이다. 수수료를 더하면 v2.7 예약 모델과 어긋난다
  const committedRatio = committedRatioOf(side, toNum(bidLockStr), availKRW, availCoin, toNum(qtyStr));
  const isLargeOrder = computeLargeOrder(ready, committedRatio);

  const onPriceInput = (v: string) => {
    // 소수 허용(저가·소수틱 마켓). 숫자와 소수점 1개만 남긴다
    let cleaned = v.replace(/[^0-9.]/g, "");
    const dot = cleaned.indexOf(".");
    if (dot >= 0) cleaned = cleaned.slice(0, dot + 1) + cleaned.slice(dot + 1).replace(/\./g, "");
    setPriceRaw(cleaned.slice(0, 16));
    setActivePct(null);
  };
  const onQtyInput = (v: string) => {
    if (/^\d*\.?\d*$/.test(v)) {
      setQtyRaw(v.slice(0, 14));
      setActivePct(null);
    }
  };
  const onAmountInput = (v: string) => {
    setAmountRaw(v.replace(/[^0-9]/g, "").slice(0, 12));
    setActivePct(null);
  };
  // 주문총액 입력 → 가격 기준 수량 역산(양방향). 지정가는 입력가, 시장가 매도는 현재가 기준.
  // 수량이 원본, 총액은 파생. 총액 입력은 역산해 수량에 되쓴다.
  const onTotalInput = (v: string) => {
    // 역산 기준가는 검증·제출과 같은 틱 반올림가(priceBasisStr). 원시 입력가 기준이면
    // 격자 밖 가격에서 역산 수량의 반올림가 총액이 입력 총액과 어긋난다. 입력 정리와 역산은
    // 단위 시험이 고정하는 qtyFromTotalInput 한 곳에 둔다
    const next = qtyFromTotalInput(v, priceBasisStr, qtyScale);
    if (next !== null) setQtyRaw(next);
    setActivePct(null);
  };

  const stepPrice = (sign: 1 | -1) => {
    // 출발값 선택과 이동은 stepLimitPrice 한 곳에 둔다. 출발값을 number로 바꾸면 제출 가격이 달라진다
    setSnappedPrice(stepLimitPrice(priceRaw, ticker?.trade_price, specTick, priceStep, sign));
    setActivePct(null);
  };

  /* 퍼센트 프리셋. float 왕복은 2^53 초과 잔고에서 수량을 위조한다. 예산을 price_scale로 먼저
     절사해야 UP 양자화한 락도 그 정수를 넘지 않아 100% 프리셋이 잔고 초과를 만들지 않는다(§4-B-5) */
  const applyPct = (pct: number) => {
    setActivePct(pct);
    if (side === "ask") {
      setQtyRaw(pctOfDecimal(availCoinStr, pct, qtyScale));
      return;
    }
    // 예산 자릿수는 잔고 자릿수 + 2. 10·25·50% 나눗셈이 절사 없이 떨어진다
    const budgetStr = truncToScale(pctOfDecimal(availKRWStr, pct, decimalsOf(availKRWStr) + 2), priceScale);
    if (isMarketBuy) {
      setAmountRaw(budgetStr);
      return;
    }
    // 역산 기준가는 검증·제출과 같은 틱 반올림가. 원시 입력가 기준이면 100% 프리셋이
    // 스스로 채운 수량이 반올림가 총액으로 재검증되며 잔고 초과 에러를 만든다
    const next = qtyFromTotal(budgetStr, priceBasisStr, qtyScale);
    if (next !== null) setQtyRaw(next);
  };

  const submit = async () => {
    if (submittingRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    // 제출 직전에 키를 확정한다. 내용이 바뀌었으면 새 키, 실패 후 동일 내용 재시도면 기존 키(API §2-4).
    keyStateRef.current = resolveKeyForSubmit(keyState(), newClientOrderId);
    const cid = keyStateRef.current.key;
    const req: OrderRequest =
      ordType === "limit"
        ? {
            market,
            side,
            ord_type: "limit",
            price: priceBasisStr,
            volume: qtyStr,
            client_order_id: cid,
          }
        : side === "bid"
          ? { market, side, ord_type: "market", price: fromScaledInt(totalWon, 0), client_order_id: cid }
          : { market, side, ord_type: "market", volume: qtyStr, client_order_id: cid };
    try {
      const placed = await getDataSource().placeOrder(req);
      // 201 응답이 권위 Order다. 재조회 왕복을 기다리지 않고 목록에 반영한다(§4-B-1)
      upsertOrder(placed);
      // 확정 성공. 다음 주문은 새 키
      keyStateRef.current = onSubmitAccepted(keyState());
      // 접수와 체결은 다른 사건이다. 한 문구로 알리면 미체결을 "샀다"로 읽는다
      const notice = placedNotice(placed);
      toast(notice.text, notice.kind);
      setQtyRaw("");
      setAmountRaw("");
      setActivePct(null);
      void loadOrders();
    } catch (e) {
      // 종결성 거부(4xx)는 키가 소모됐거나 선차단됐다. 같은 키 재시도는 REJECTED 멱등 replay를
      // 거짓 성공으로 받으므로 dirty를 세워 다음 제출에 새 키를 쓴다. sim 거부는 전부 키 등록 전
      // 종결이라 동일 취급. 상태 UNKNOWN(5xx·네트워크·타임아웃)·429와 미확정 409만 키를 보존한다(§7-6)
      const finalReject = e instanceof ApiError ? isFinalRejectStatus(e.status, e.code) : e instanceof SimApiError;
      keyStateRef.current = finalReject ? onInputChanged(keyState()) : onSubmitFailed(keyState());
      /* 결과를 모르는 주문은 키를 버리지 않고 같은 키로 재조회해 결과를 정한다(Patroni 설계 6-2). 423 은 이 시도만
         쓰지 않았다는 뜻이라 재개를 확인한 뒤에 재조회한다. 새 키로 다시 내면 모르는 주문이 체결될 수 있다 */
      const cause = e instanceof ApiError ? pendingCauseOf(e.status, e.code) : null;
      if (cause) trackPendingOrder({ clientOrderId: cid, market, cause, since: Date.now() });
      if (e instanceof ApiError && e.status === 423) void loadTradingStatus();
      toast(e instanceof Error ? e.message : "주문 실패. 잠시 후 다시 시도 필요", "error");
    } finally {
      // dirty 동결 해제는 제출 종료 시점. 재전송이 끝날 때까지 확정 키를 고정했다
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  const onCta = () => {
    if (!ready || submitting || suspended) return;
    // 임계 이상은 확인 다이얼로그로. 임계 미만은 기존 인라인 2단계 흐름
    if (isLargeOrder) {
      disarm();
      setConfirmOpen(true);
      return;
    }
    if (!armed) {
      setArmed(true);
      setArmedTotal(totalStr); // 확정 라벨 금액 동결(라이브 파생 총액의 armed 중 재낭독 방지)
      armTimer.current = setTimeout(() => {
        setArmed(false);
        setArmedTotal(null);
        armTimer.current = null;
      }, CONFIRM_TIMEOUT_MS);
      return;
    }
    disarm();
    void submit();
  };

  const onConfirmLarge = () => {
    if (submitting || suspended) return;
    void submit().finally(() => setConfirmOpen(false));
  };

  const sideLabel = side === "bid" ? "매수" : "매도";

  /* 로그인 후 돌아올 자리. 지금 고른 마켓·방향·가격을 쿼리로 실어 입력을 잃지 않게 한다.
     가격은 표시 문자열이 아니라 원본(priceRaw)이다. 쉼표가 낀 값은 프리필이 못 읽는다.
     window를 렌더에서 읽으면 프리렌더가 깨지므로 경로는 이미 아는 market으로 조립한다 */
  const guestReturnPath = useMemo(() => {
    const query = new URLSearchParams({ side });
    if (priceRaw) query.set("price", priceRaw);
    return `/exchange/${market}?${query.toString()}`;
  }, [market, side, priceRaw]);
  const btnClass = side === "bid" ? "btn--buy" : "btn--sell";

  // 다이얼로그 표시값. 시장가 매수는 체결 수량이 확정 전이라 현재가 기준 추정 수량을 보인다.
  // 총액은 반올림값이 아니라 패널·제출과 같은 정확 총액이다(표시가와 접수가가 갈리면 안 된다)
  const confirmQtyStr = recvQtyStr;
  const confirmPriceStr = ordType === "limit" ? priceBasisStr : undefined;
  const confirmTotalStr = totalStr;
  const ratioPct = Math.round(committedRatio * 100);

  return (
    <PanelCard srTitle="주문" className={styles.card}>
      <div className={styles.form}>
        <SegmentedTab
          items={SIDE_ITEMS}
          value={side}
          onChange={setSide}
          variant="directional"
          semantics="radio"
          ariaLabel="주문 방향"
          disabled={submitting}
        />

        <div className={styles.typRow} role="group" aria-label="주문 유형">
          {ordTypes.map((t) => (
            <button
              key={t}
              type="button"
              className={styles.typBtn}
              data-on={t === ordType || undefined}
              aria-pressed={t === ordType}
              disabled={submitting}
              onClick={() => {
                setOrdType(t);
                setActivePct(null);
              }}
            >
              {t === "limit" ? "지정가" : "시장가"}
            </button>
          ))}
        </div>

        {/* 가격 (지정가) */}
        {ordType === "limit" && (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="panel-ord-price">
              가격
            </label>
            <div className={`focus-ring-owner ${styles.inp}`}>
              <button type="button" className={styles.step} aria-label="가격 한 호가 내리기" onClick={() => stepPrice(-1)} disabled={submitting}>
                −
              </button>
              <input
                id="panel-ord-price"
                className="num"
                inputMode="decimal"
                autoComplete="off"
                value={priceRaw ? formatPriceField(priceRaw) : ""}
                placeholder={ticker ? formatPrice(curPrice, priceTick) : "0"}
                onChange={(e) => onPriceInput(e.target.value)}
                onBlur={() => {
                  // 격자 밖 입력은 제출 시 반올림가로 나간다. 표시가와 제출가가 다르지 않게 blur에 스냅.
                  // 스냅 결과를 자동 채움분으로 기록해 스펙이 늦게 와도 재스냅이 갈라짐을 닫는다.
                  // 격자 선택은 autoFillPrice 한 곳에 둔다. 같은 식을 손으로 재현하면 갈라진다
                  if (priceRaw) setSnappedPrice(autoFillPrice(priceRaw, specTick, priceStep));
                }}
                aria-label="주문 가격(원)"
                disabled={submitting}
              />
              <span className={`unit-suffix ${styles.unit}`}>원</span>
              <button type="button" className={styles.step} aria-label="가격 한 호가 올리기" onClick={() => stepPrice(1)} disabled={submitting}>
                ＋
              </button>
            </div>
          </div>
        )}

        {/* 수량 (지정가·시장가 매도) / 금액 (시장가 매수) */}
        {isMarketBuy ? (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="panel-ord-amount">
              주문 금액
            </label>
            <div className={`focus-ring-owner ${styles.inp}`}>
              <input
                id="panel-ord-amount"
                className="num"
                inputMode="numeric"
                autoComplete="off"
                value={amountRaw ? formatKRW(amountRaw) : ""}
                placeholder="0"
                onChange={(e) => onAmountInput(e.target.value)}
                aria-label="시장가 매수 금액(원)"
                disabled={submitting}
              />
              <span className={`unit-suffix ${styles.unit}`}>원</span>
            </div>
          </div>
        ) : (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="panel-ord-qty">
              수량
            </label>
            <div className={`focus-ring-owner ${styles.inp}`} data-error={error === "보유 수량 초과" || undefined}>
              <input
                id="panel-ord-qty"
                className="num"
                inputMode="decimal"
                autoComplete="off"
                value={qtyRaw}
                placeholder="0"
                onChange={(e) => onQtyInput(e.target.value)}
                aria-label={`주문 수량(${base})`}
                disabled={submitting}
              />
              <span className={`unit-suffix ${styles.unit}`}>{base}</span>
            </div>
          </div>
        )}

        {/* 퍼센트 칩 */}
        <div className={styles.pct} role="group" aria-label="가용 잔고 비율 선택">
          {[10, 25, 50, 100].map((p) => (
            <button
              key={p}
              type="button"
              className={styles.pctBtn}
              data-on={activePct === p || undefined}
              aria-pressed={activePct === p}
              onClick={() => applyPct(p)}
              disabled={submitting}
            >
              {p === 100 ? "최대" : `${p}%`}
            </button>
          ))}
        </div>

        <div className={styles.divider} />

        {/* 주문총액: 시장가 매수는 위 금액 입력이 곧 총액이라 표시만, 그 외는 입력 가능(수량 역산) */}
        {isMarketBuy ? (
          <div className={styles.sumRow}>
            <span>주문총액</span>
            <b className="num">
              {formatKRW(totalStr)} <span className="unit-suffix">원</span>
            </b>
          </div>
        ) : (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="panel-ord-total">
              주문총액
            </label>
            <div className={`focus-ring-owner ${styles.inp}`}>
              <input
                id="panel-ord-total"
                className="num"
                inputMode="numeric"
                autoComplete="off"
                value={compareDecimal(totalStr, "0") > 0 ? formatKRW(totalStr) : ""}
                placeholder="0"
                onChange={(e) => onTotalInput(e.target.value)}
                aria-label="주문총액(원)"
                disabled={submitting}
              />
              <span className={`unit-suffix ${styles.unit}`}>원</span>
            </div>
          </div>
        )}
        {/* 행동과 그 근거를 한 덩어리로 묶는다. 폼이 안에서 접힐 때 수수료와 주문가능이
            먼저 밀려나면 얼마까지 살 수 있는지 모르는 채 수량을 넣고 버튼만 누르게 된다.
            결정에 쓰는 값은 결정 지점 옆에 있어야 한다 */}
        <div className={styles.foot}>
          <div className={styles.sumRow}>
            {/* 수수료 자산은 수취 자산이다. 매수는 받는 코인에서, 매도는 받는 원화에서 빠진다.
                단위를 병기하지 않으면 매수 수수료를 원 금액으로 읽는다 */}
            <span>예상 수수료 ({feePctStr}%)</span>
            <b className="num">
              {side === "bid" ? formatQty(feeStr, qtyScale) : formatKRW(feeStr)}{" "}
              <span className="unit-suffix">{feeUnit}</span>
            </b>
          </div>
          <div className={styles.sumRow}>
            <span className={styles.availLabel}>
              주문가능
              {/* 게스트 데모 잔고 표식. 숫자가 실자산으로 읽히지 않게 한다. 화면당 1곳 */}
              {isGuest && <span className={`status-pill ${styles.demoBadge}`}>데모 자산</span>}
            </span>
            {/* 값이 오래됐음을 라벨 옆에 붙인다. 값 자체는 지우지 않는다(§2-3 429 규약).
                공용 StaleBadge 를 쓴다. 관리·자산 표면이 이미 같은 구현을 공유한다 */}
            {/* 지연 배지 옆에 다시 읽을 수단을 둔다. 자동 재조회가 실패 한 건당 1회라
                그 한 번이 실패하면 사용자가 판단한다(§2-3). 판단하려면 자리가 있어야 한다 */}
            {balance.stale && (
              <>
                <StaleBadge label="지연" announce={false} />
                <button type="button" className={styles.retry} onClick={() => onRetryAccounts?.()}>
                  새로고침
                </button>
              </>
            )}
            {balance.view === "loading" ? (
              <Skeleton variant="text" width={110} height={16} />
            ) : balance.view === "retry" ? (
              // 버튼은 행동만 맡는다. 실패 사실과 결과는 바로 아래 경고문이 이미 말하므로
              // 여기서 되풀이하면 한 화면에서 같은 사건을 두 번 듣는다
              <button type="button" className={styles.retry} onClick={() => onRetryAccounts?.()}>
                다시 시도
              </button>
            ) : (
              <span className="num">
                {/* 표시도 원본 문자열로 넘긴다. number 왕복은 2^53 초과 잔고 표기를 뭉갠다 */}
                {side === "bid" ? formatKRW(availKRWStr) : formatQty(availCoinStr, qtyScale)}{" "}
                <span className="unit-suffix">{side === "bid" ? "원" : base}</span>
              </span>
            )}
          </div>

          {/* 묶임 잔고. 주문가능 바로 아래에 붙인다. 두 값은 한 잔고를 나눈 것이라 떨어뜨리면
              합이 보이지 않고, "왜 내 돈이 줄었나"가 화면 밖 질문이 된다.
              0이면 줄을 만들지 않는다. 늘 떠 있는 0은 읽을 것이 없는데 자리만 먹는다 */}
          {balance.view === "value" && hasLocked && (
            <div className={styles.sumRow}>
              <span className={styles.availLabel}>
                주문 중
                {openCount > 0 && <span className={`status-pill ${styles.lockHint}`}>미체결 {openCount}건</span>}
              </span>
              <span className={`num ${styles.locked}`}>
                {side === "bid" ? formatKRW(lockedStr) : formatQty(lockedStr, qtyScale)}{" "}
                <span className="unit-suffix">{side === "bid" ? "원" : base}</span>
              </span>
            </div>
          )}

          {/* 429는 장애가 아니라 설계된 백프레셔라 종결 실패로 적지 않는다(§2-3 429 클라이언트
              규약). 문구 축은 decideBalance 가 정하고 여기서는 고르기만 한다.
              role 도 가른다. alert 는 즉시 낭독을 뺏으므로 값이 있어 확정이 열려 있는 지연이나
              곧 자동으로 풀리는 대기에는 쓰지 않고, 스스로 풀리지 않는 차단에만 쓴다.
              자동 재조회 예고는 대기 초가 있는 경로에만 붙는다. 비429 실패에는 그 타이머가
              걸리지 않으므로(ExchangeMarketClient 자동 재조회 조건) 예고하면 거짓말이 된다 */}
          {balance.notice === "delayed_wait" && (
            <p className={styles.hint} role="status">
              요청 몰림으로 잔고 갱신 대기. {accountsHealth.retryAfterSec}초 후 자동 재조회
            </p>
          )}
          {balance.notice === "delayed" && (
            <p className={styles.hint} role="status">
              잔고 갱신 실패로 표시 값이 오래됨. 새로고침 가능
            </p>
          )}
          {balance.notice === "blocked_wait" && (
            <p className={styles.hint} role="status">
              잔고 미확정으로 주문 불가. {accountsHealth.retryAfterSec}초 후 자동 재조회
            </p>
          )}
          {balance.notice === "blocked" && (
            <p className={styles.error} role="alert">
              잔고 조회 실패로 주문 불가. 다시 시도 필요
            </p>
          )}

          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}

          {/* 둘러보기 중에는 주문 자리가 로그인 자리다(회원_설계 §0 읽기 전용 계약).
              비활성 버튼으로 두면 "왜 안 눌리는지"만 남고 다음 행동이 없다. 입력은 그대로 두어
              값을 채워본 뒤 로그인할 수 있게 하고, 복귀 경로에 현재 쿼리까지 실어 자리를 지킨다 */}
          {pendingHere && (
            <p className={styles.hint} role="status">
              <span aria-hidden="true">{PENDING_RESULT_LABEL}</span>
              <span className="sr-only">{PENDING_RESULT_SR}</span>
            </p>
          )}

          {isGuest ? (
            <Link
              href={loginHref(guestReturnPath)}
              className={`btn ${btnClass} btn--full ${styles.cta}`}
            >
              로그인하고 {sideLabel}
            </Link>
          ) : suspended ? (
            /* 버튼 자리에 중단 표시를 둔다. 비활성 버튼만 두면 왜 안 눌리는지 알 수 없다 */
            <button type="button" className={`btn ${btnClass} btn--full ${styles.cta}`} disabled>
              <span aria-hidden="true">{ORDER_BLOCKED_LABEL}</span>
              <span className="sr-only">{ORDER_BLOCKED_SR}</span>
            </button>
          ) : (
            /* 인라인 2단계 확인 CTA. armed 시 확인 라벨, 재클릭 확정 (§2.5) */
            <button
              ref={ctaRef}
              type="button"
              className={`btn ${btnClass} btn--full ${styles.cta}`}
              disabled={!ready || submitting}
              aria-busy={submitting}
              aria-live="polite"
              onClick={onCta}
            >
              {submitting
                ? "접수 중…"
                : armed
                  ? `${formatKRW(armedTotal ?? totalStr)} KRW ${sideLabel} 확정`
                  : totalPositive
                    ? `${sideLabel}하기`
                    : needLabel}
            </button>
          )}
        </div>
      </div>

      {confirmOpen && (
        <OrderConfirmDialog
          side={side}
          market={market}
          base={base}
          ordType={ordType}
          priceStr={confirmPriceStr}
          priceSpec={priceTick}
          qtyStr={confirmQtyStr}
          qtyScale={qtyScale}
          totalStr={confirmTotalStr}
          feeStr={feeStr}
          feeUnit={feeUnit}
          feePctStr={feePctStr}
          ratioPct={ratioPct}
          busy={submitting}
          onConfirm={onConfirmLarge}
          onClose={() => {
            if (!submitting) setConfirmOpen(false);
          }}
        />
      )}
    </PanelCard>
  );
}
