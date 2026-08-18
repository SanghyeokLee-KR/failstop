"use client";

/**
 * OrderPanel: 인라인 주문 폼 (§2.5). OrderClient의 검증·제출 로직 이식.
 * 확인 단계는 인라인 2단계. 1차 클릭 시 CTA가 확인 라벨로 전환, 3초 내 재클릭 확정,
 * Esc·외부 클릭·3초 경과·입력 변경으로 원복. 모달·바텀시트 없음.
 * 프리필: /order 리다이렉트 쿼리(side+price) 승계 + 호가 클릭(가격만, side 유지).
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import PanelCard from "@/components/PanelCard";
import SegmentedTab from "@/components/SegmentedTab";
import Skeleton from "@/components/Skeleton";
import { toast } from "@/components/Toast";
import { useExchangeStore, useLiveChannels } from "@/lib/store";
import { dest, type OrderRequest, type Side } from "@/lib/types";
import { getDataSource } from "@/lib/api/datasource";
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
  type OrderKeyState,
} from "./orderKey";
import { shouldAnnounceFill } from "./fillNotice";
import OrderConfirmDialog from "./OrderConfirmDialog";
import {
  autoFillPrice,
  committedRatioOf,
  formatPriceField,
  isLargeOrder as computeLargeOrder,
  pctOfDecimal,
  qtyFromTotal,
  resnapAutoPrice,
  roundToTick,
  stepAutoPrice,
} from "./orderMath";
import styles from "./OrderPanel.module.css";

// 수수료·최소주문의 권위 소스는 GET /markets 마켓 메타다(§4-A-1, 관리자 런타임 변경 가능).
// 아래 상수는 메타 미로딩 구간의 표시·검증 폴백일 뿐이다.
const FALLBACK_FEE_RATE_STR = "0.0005";
const FALLBACK_MIN_ORDER_KRW = "5000";
const CONFIRM_TIMEOUT_MS = 3000;

type OrdType = "limit" | "market";

const SIDE_ITEMS = [
  { value: "bid" as Side, label: "매수", tone: "buy" as const },
  { value: "ask" as Side, label: "매도", tone: "sell" as const },
];

export interface PricePrefill {
  price: string;
  /** 동일 가격 재클릭도 재주입되도록 하는 시퀀스 */
  seq: number;
}

interface Props {
  market: string;
  initialSide: Side;
  /** 호가 클릭·쿼리 승계 프리필. 가격만 주입, side 유지 (§2.5) */
  prefill: PricePrefill | null;
  /** 계좌 로드 실패 여부. 실패면 잔고 미확정으로 CTA를 막고 재시도를 노출한다 */
  accountsFailed?: boolean;
  /** 계좌 재조회 트리거 (부모 소유) */
  onRetryAccounts?: () => void;
}

function newClientOrderId(): string {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export default function OrderPanel({ market, initialSide, prefill, accountsFailed = false, onRetryAccounts }: Props) {
  const base = market.split("-")[1] ?? "";

  const ticker = useExchangeStore((s) => s.tickers[market]);
  const assets = useExchangeStore((s) => s.assets);
  const markets = useExchangeStore((s) => s.markets);
  const loadOrders = useExchangeStore((s) => s.loadOrders);
  const upsertOrder = useExchangeStore((s) => s.upsertOrder);
  const apply = useExchangeStore((s) => s.apply);

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
      if (announce) toast("주문이 체결됐어요", "success");
    });
  }, [apply]);

  const marketInfo = markets.find((m) => m.market === market);
  const qtyScale = marketInfo?.qty_scale ?? 8;
  const curPrice = ticker ? toNum(ticker.trade_price) : 0;

  // 가격·퍼센트칩·틱 계산용 number(격자 선택·역산 보조). 총액·수수료·검증·제출은 아래 십진 문자열이 단일 기준.
  const price = ordType === "market" ? curPrice : toNum(priceRaw);
  const isMarketBuy = ordType === "market" && side === "bid";

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
  }, [prefill, fillPrice]);

  /* 프리필 없이 현재가 수신 시 1회 가격 채움. 무체결 마켓(trade_price null)은 채울 값이 없어 대기 */
  const prefilled = useRef(prefill != null);
  useEffect(() => {
    if (!prefilled.current && ticker?.trade_price != null) {
      prefilled.current = true;
      fillPrice(ticker.trade_price);
    }
  }, [ticker, fillPrice]);

  /* 마켓 스펙은 자동 채움보다 늦게 도착할 수 있다(외부 딥링크 + 콜드 세션). 폴백 격자로 채운
     값을 그대로 두면 표시가는 폴백 격자, 제출 기준가(priceBasisStr)는 권위 격자로 갈린다.
     트리거를 priceTick이 아니라 스펙 틱으로 좁힌다. 폴백 priceStep은 입력 자릿수를 따라 흔들려
     타이핑마다 재스냅이 걸린다. specTick은 한 번 확정되면 다시 바뀌지 않아, in-flight로 건너뛰면
     트리거가 소실된다. submitting을 deps에 넣어 제출 종료 시 재시도한다 */
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

  /* 입력·방향·유형 변경 시 확인 상태 원복 + 다음 제출은 새 멱등키. 확정 대상이 달라졌기 때문.
     단 제출 중(in-flight)에는 dirty 전이를 동결한다. 5XX 재시도가 새 키를 발급하면 이중접수 위험(§7-6) */
  useEffect(() => {
    if (submittingRef.current) return;
    disarm();
    keyStateRef.current = onInputChanged(keyState());
  }, [side, ordType, priceRaw, qtyRaw, amountRaw, market, disarm]);

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
  // 진행 중과 실패를 분리한다. 실패면 영구 로딩 대신 재시도를 노출하고, 잔고 미확정이므로 CTA는 막는다
  const loadingBalance = !accountsFailed && (assets.length === 0 || !ticker);

  /* ===== 검증 (OrderClient 규약, 문자열 십진 정확 비교) =====
     최소·최대는 반올림 총액이 아니라 정확 비교로 판정한다. 서버(게이트웨이 정적 검증 §4-B-1)가
     NUMERIC 정확 비교라 클라이언트만 반올림하면 경계 구간 주문이 인라인 검증을 통과 후 거부된다 */
  const totalPositive = compareDecimal(totalStr, "0") > 0;
  let error: string | null = null;
  if (totalPositive && compareDecimal(totalStr, minOrderStr) < 0)
    error = `최소 주문 금액은 ${formatKRW(minOrderStr)}원이에요`;
  else if (totalPositive && maxOrderStr !== null && compareDecimal(totalStr, maxOrderStr) > 0)
    error = `최대 주문 금액은 ${formatKRW(maxOrderStr)}원이에요`;
  else if (side === "bid" && totalPositive && compareDecimal(bidLockStr, availKRWStr) > 0)
    error = "주문 가능 금액을 초과했어요";
  else if (side === "ask" && compareDecimal(qtyStr, availCoinStr) > 0) error = "보유 수량을 초과했어요";
  const ready = totalPositive && error === null && !loadingBalance && !accountsFailed;

  // 가용 대비 큰 비중이면 인라인 확인 대신 다이얼로그로 오발주를 막는다(임계 판정은 number로 충분).
  // 분자는 실제로 묶이는 금액(락)이다. 수수료를 더하면 v2.7 예약 모델과 어긋난다
  const committedRatio = committedRatioOf(side, toNum(bidLockStr), availKRW, availCoin, toNum(qtyStr));
  const isLargeOrder = computeLargeOrder(ready, committedRatio);

  /* ===== 입력 핸들러 ===== */
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
    const t = toNum(v.replace(/[^0-9]/g, "").slice(0, 14));
    // 역산 기준가는 검증·제출과 같은 틱 반올림가(priceBasisStr). 원시 입력가 기준이면
    // 격자 밖 가격에서 역산 수량의 반올림가 총액이 입력 총액과 어긋난다
    const p = toNum(priceBasisStr);
    const next = qtyFromTotal(t, p, qtyScale);
    if (next !== null) setQtyRaw(next);
    setActivePct(null);
  };

  const stepPrice = (sign: 1 | -1) => {
    const ref = price > 0 ? price : curPrice;
    setSnappedPrice(stepAutoPrice(ref, specTick, priceStep, sign));
    setActivePct(null);
  };

  /* 퍼센트 프리셋. 전 경로가 문자열 십진이다. float 왕복은 2^53 초과 잔고에서 수량을 위조한다.
     v2.7 예약 모델에서 락은 대금뿐이라 (1+fee) 역산이 없다. 예산을 price_scale로 먼저 절사해
     두면 대금 <= 예산(정수)이고 UP 양자화한 락도 그 정수를 넘지 않아, 100% 프리셋이 스스로
     채운 수량으로 잔고 초과 에러를 만들지 않는다 */
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

  /* ===== 제출 (인라인 2단계 확인) ===== */
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
      toast("주문을 접수했어요", "info");
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
      toast(e instanceof Error ? e.message : "주문에 실패했어요. 잠시 후 다시 시도해 주세요", "error");
    } finally {
      // dirty 동결 해제는 제출 종료 시점. 재전송이 끝날 때까지 확정 키를 고정했다
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  const onCta = () => {
    if (!ready || submitting) return;
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
    if (submitting) return;
    void submit().finally(() => setConfirmOpen(false));
  };

  const sideLabel = side === "bid" ? "매수" : "매도";
  const btnClass = side === "bid" ? "btn--buy" : "btn--sell";

  // 다이얼로그 표시값. 시장가 매수는 체결 수량이 확정 전이라 현재가 기준 추정 수량을 보인다.
  // 총액은 반올림값이 아니라 패널·제출과 같은 정확 총액이다(표시가와 접수가가 갈리면 안 된다)
  const confirmQtyStr = recvQtyStr;
  const confirmPriceStr = ordType === "limit" ? priceBasisStr : undefined;
  const confirmTotalStr = totalStr;
  const ratioPct = Math.round(committedRatio * 100);

  return (
    <PanelCard className={styles.card}>
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
          {(["limit", "market"] as OrdType[]).map((t) => (
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
            <div className={styles.inp}>
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
            <div className={styles.inp}>
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
            <div className={styles.inp} data-error={error === "보유 수량을 초과했어요" || undefined}>
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
            <div className={styles.inp}>
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
          <span>주문가능</span>
          {loadingBalance ? (
            <Skeleton variant="text" width={110} height={16} />
          ) : accountsFailed ? (
            <button type="button" className={styles.retry} onClick={() => onRetryAccounts?.()}>
              불러오기 실패, 다시 시도
            </button>
          ) : (
            <span className="num">
              {/* 표시도 원본 문자열로 넘긴다. number 왕복은 2^53 초과 잔고 표기를 뭉갠다 */}
              {side === "bid" ? formatKRW(availKRWStr) : formatQty(availCoinStr, qtyScale)}{" "}
              <span className="unit-suffix">{side === "bid" ? "원" : base}</span>
            </span>
          )}
        </div>

        {accountsFailed && (
          <p className={styles.error} role="alert">
            잔고를 불러오지 못해 주문할 수 없어요. 다시 시도해 주세요
          </p>
        )}

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}

        {/* 인라인 2단계 확인 CTA. armed 시 확인 라벨, 재클릭 확정 (§2.5) */}
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
              : `${sideLabel}하기`}
        </button>
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
