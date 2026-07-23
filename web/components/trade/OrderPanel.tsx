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
import { priceStep } from "@/lib/sim/engine";
import { formatKRW, formatPrice, formatQty, toNum } from "@/lib/format";
import {
  compareDecimal,
  fromScaledInt,
  mulDecimal,
  normalizeDecimal,
  scaledIntRound,
  scaledIntTrunc,
} from "@/lib/decimal";
import {
  initOrderKey,
  onInputChanged,
  onSubmitAccepted,
  onSubmitFailed,
  resolveKeyForSubmit,
  type OrderKeyState,
} from "./orderKey";
import OrderConfirmDialog from "./OrderConfirmDialog";
import {
  committedRatioOf,
  floorTo,
  formatPriceField,
  isLargeOrder as computeLargeOrder,
  qtyFromTotal,
  roundToTick,
  stepToTick,
  toPlainString,
} from "./orderMath";
import styles from "./OrderPanel.module.css";

const FEE_RATE = 0.0005;
const FEE_RATE_STR = "0.0005";
// 총액+수수료 = 총액×(1+0.0005). bid 잔고 검증을 문자열 곱 한 번으로 처리한다
const GROSS_RATE_STR = "1.0005";
const MIN_ORDER_KRW = 5000;
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
      apply(msg);
      if (msg.type === "myOrder" && msg.status === "FILLED") toast("주문이 체결됐어요", "success");
    });
  }, [apply]);

  /* 프리필은 가격만 주입, side 유지. 시장가 상태면 가격 입력이 보이도록 지정가 전환.
     제출 in-flight 중에는 보류한다. 동결된 확정 내용을 프리필이 바꾸면 동일 키·다른 내용이 된다 */
  useEffect(() => {
    if (!prefill) return;
    if (submittingRef.current) return;
    setOrdType((t) => (t === "market" ? "limit" : t));
    setPriceRaw(roundToTick(prefill.price, tickRef.current));
    setActivePct(null);
  }, [prefill]);

  /* 프리필 없이 현재가 수신 시 1회 가격 채움. 무체결 마켓(trade_price null)은 채울 값이 없어 대기 */
  const prefilled = useRef(prefill != null);
  useEffect(() => {
    if (!prefilled.current && ticker?.trade_price != null) {
      prefilled.current = true;
      setPriceRaw(roundToTick(ticker.trade_price, tickRef.current));
    }
  }, [ticker]);

  const disarm = useCallback(() => {
    setArmed(false);
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

  const marketInfo = markets.find((m) => m.market === market);
  const qtyScale = marketInfo?.qty_scale ?? 8;
  const curPrice = ticker ? toNum(ticker.trade_price) : 0;

  // 가격·퍼센트칩·틱 계산용 number(격자 선택·역산 보조). 총액·수수료·검증·제출은 아래 십진 문자열이 단일 기준.
  const price = ordType === "market" ? curPrice : toNum(priceRaw);
  const isMarketBuy = ordType === "market" && side === "bid";

  // 가격 격자. 마켓 tick_size가 있으면 그것을, 없으면 가격대별 priceStep을 쓴다. 저가·소수틱 마켓 0 강제 방지.
  const priceTick =
    marketInfo?.tick_size && toNum(marketInfo.tick_size) > 0
      ? marketInfo.tick_size
      : String(priceStep(price > 0 ? price : curPrice > 0 ? curPrice : 1));
  // 프리필 이펙트는 marketInfo를 deps에 넣지 않으려 최신 틱을 ref로 참조한다(ticker 변화마다 재주입 방지)
  const tickRef = useRef(priceTick);
  tickRef.current = priceTick;

  // 단일 십진 기준. 수량은 qty_scale 격자로 내림(잔고 안전, 반올림 아님), 가격은 틱 반올림.
  // 총액·수수료·최소주문·잔고 검증·제출 volume이 같은 문자열 값을 공유해 float 왕복과 경계 불일치를 없앤다.
  const qtyStr = fromScaledInt(scaledIntTrunc(qtyRaw || "0", qtyScale), qtyScale);
  const amountStr = amountRaw === "" ? "0" : normalizeDecimal(amountRaw);
  // 총액 기준가: 지정가는 틱 반올림가, 시장가 매도는 현재가 추정, 시장가 매수는 가격 미사용(총액=금액)
  const priceBasisStr = isMarketBuy
    ? "0"
    : ordType === "market"
      ? normalizeDecimal(ticker?.trade_price ?? "0")
      : roundToTick(priceRaw || "0", priceTick);
  const totalStr = isMarketBuy ? amountStr : mulDecimal(priceBasisStr, qtyStr);
  const feeStr = mulDecimal(totalStr, FEE_RATE_STR);
  // 원 단위 반올림 총액(BigInt). 최소주문·표시가 서브원 오차 없이 정렬된다
  const totalWon = scaledIntRound(totalStr, 0);
  // 총액+수수료. bid 잔고 초과 판정 기준
  const grossStr = mulDecimal(totalStr, GROSS_RATE_STR);

  const availKRWStr = assets.find((a) => a.currency === "KRW")?.balance ?? "0";
  const availCoinStr = assets.find((a) => a.currency === base)?.balance ?? "0";
  const availKRW = toNum(availKRWStr);
  const availCoin = toNum(availCoinStr);
  // 진행 중과 실패를 분리한다. 실패면 영구 로딩 대신 재시도를 노출하고, 잔고 미확정이므로 CTA는 막는다
  const loadingBalance = !accountsFailed && (assets.length === 0 || !ticker);

  /* ===== 검증 (OrderClient 규약 그대로, 문자열 십진 기준) ===== */
  const minOrder = BigInt(MIN_ORDER_KRW);
  let error: string | null = null;
  if (totalWon > 0n && totalWon < minOrder) error = "최소 주문 금액은 5,000원이에요";
  else if (side === "bid" && totalWon > 0n && compareDecimal(grossStr, availKRWStr) > 0)
    error = "주문 가능 금액을 초과했어요";
  else if (side === "ask" && compareDecimal(qtyStr, availCoinStr) > 0) error = "보유 수량을 초과했어요";
  const ready = totalWon >= minOrder && error === null && !loadingBalance && !accountsFailed;

  // 가용 대비 큰 비중이면 인라인 확인 대신 다이얼로그로 오발주를 막는다(임계 판정은 number로 충분)
  const committedRatio = committedRatioOf(side, toNum(totalStr), toNum(feeStr), availKRW, availCoin, toNum(qtyStr));
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
    const p = ordType === "market" ? curPrice : price;
    const next = qtyFromTotal(t, p, qtyScale);
    if (next !== null) setQtyRaw(next);
    setActivePct(null);
  };

  const stepPrice = (sign: 1 | -1) => {
    const ref = price > 0 ? price : curPrice;
    setPriceRaw(stepToTick(ref, priceTick, sign));
    setActivePct(null);
  };

  const applyPct = (pct: number) => {
    setActivePct(pct);
    if (side === "ask") {
      setQtyRaw(toPlainString(floorTo(availCoin * (pct / 100), qtyScale), qtyScale));
      return;
    }
    if (isMarketBuy) {
      setAmountRaw(String(Math.floor((availKRW * (pct / 100)) / (1 + FEE_RATE))));
      return;
    }
    if (price > 0) {
      const usable = (availKRW * (pct / 100)) / (price * (1 + FEE_RATE));
      setQtyRaw(toPlainString(floorTo(usable, qtyScale), qtyScale));
    }
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
      await getDataSource().placeOrder(req);
      // 확정 성공. 다음 주문은 새 키
      keyStateRef.current = onSubmitAccepted(keyState());
      toast("주문을 접수했어요", "info");
      setQtyRaw("");
      setAmountRaw("");
      setActivePct(null);
      void loadOrders();
    } catch (e) {
      // 실패는 상태 UNKNOWN. 키를 보존해 동일 키 재전송·재조회 경로를 지킨다(§7-6)
      keyStateRef.current = onSubmitFailed(keyState());
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
      armTimer.current = setTimeout(() => {
        setArmed(false);
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

  // 다이얼로그 표시값. 시장가 매수는 체결 수량이 확정 전이라 현재가 기준 추정 수량을 보인다
  const confirmQtyStr = isMarketBuy
    ? (qtyFromTotal(amountStr, normalizeDecimal(ticker?.trade_price ?? "0"), qtyScale) ?? "0")
    : qtyStr;
  const confirmPriceStr = ordType === "limit" ? priceBasisStr : undefined;
  const confirmTotalStr = fromScaledInt(totalWon, 0);
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
          <span>예상 수수료 (0.05%)</span>
          <b className="num">
            {formatKRW(feeStr)} <span className="unit-suffix">원</span>
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
              {side === "bid" ? formatKRW(availKRW) : formatQty(availCoin, qtyScale)}{" "}
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
              ? `${formatKRW(totalStr)} KRW ${sideLabel} 확정`
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
