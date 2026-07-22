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
  initOrderKey,
  onInputChanged,
  onSubmitAccepted,
  onSubmitFailed,
  resolveKeyForSubmit,
  type OrderKeyState,
} from "./orderKey";
import styles from "./OrderPanel.module.css";

const FEE_RATE = 0.0005;
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

/** 소수 자릿수 내림 (주문 가능 수량 초과 방지) */
function floorTo(value: number, scale: number): number {
  const p = 10 ** scale;
  return Math.floor(value * p) / p;
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
    setPriceRaw(String(Math.round(toNum(prefill.price))));
    setActivePct(null);
  }, [prefill]);

  /* 프리필 없이 현재가 수신 시 1회 가격 채움 */
  const prefilled = useRef(prefill != null);
  useEffect(() => {
    if (!prefilled.current && ticker) {
      prefilled.current = true;
      setPriceRaw(String(Math.round(toNum(ticker.trade_price))));
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

  const price = ordType === "market" ? curPrice : toNum(priceRaw);
  const qty = toNum(qtyRaw);
  const amount = toNum(amountRaw);
  const isMarketBuy = ordType === "market" && side === "bid";
  const total = isMarketBuy ? amount : price * qty;
  const fee = total * FEE_RATE;

  const availKRW = toNum(assets.find((a) => a.currency === "KRW")?.balance);
  const availCoin = toNum(assets.find((a) => a.currency === base)?.balance);
  // 진행 중과 실패를 분리한다. 실패면 영구 로딩 대신 재시도를 노출하고, 잔고 미확정이므로 CTA는 막는다
  const loadingBalance = !accountsFailed && (assets.length === 0 || !ticker);

  /* ===== 검증 (OrderClient 규약 그대로) ===== */
  let error: string | null = null;
  if (total > 0 && total < MIN_ORDER_KRW) error = "최소 주문 금액은 5,000원이에요";
  else if (side === "bid" && total > 0 && total + fee > availKRW) error = "주문 가능 금액을 초과했어요";
  else if (side === "ask" && qty > availCoin) error = "보유 수량을 초과했어요";
  const ready = total >= MIN_ORDER_KRW && error === null && !loadingBalance && !accountsFailed;

  /* ===== 입력 핸들러 ===== */
  const onPriceInput = (v: string) => {
    setPriceRaw(v.replace(/[^0-9]/g, "").slice(0, 12));
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

  const stepPrice = (sign: 1 | -1) => {
    const p = price > 0 ? price : curPrice;
    const step = priceStep(p);
    const next = Math.max(step, p + sign * step);
    setPriceRaw(String(Math.round(next)));
    setActivePct(null);
  };

  const applyPct = (pct: number) => {
    setActivePct(pct);
    if (side === "ask") {
      setQtyRaw(String(floorTo(availCoin * (pct / 100), qtyScale)));
      return;
    }
    if (isMarketBuy) {
      setAmountRaw(String(Math.floor((availKRW * (pct / 100)) / (1 + FEE_RATE))));
      return;
    }
    if (price > 0) {
      const usable = (availKRW * (pct / 100)) / (price * (1 + FEE_RATE));
      setQtyRaw(String(floorTo(usable, qtyScale)));
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
            price: String(Math.round(price)),
            volume: String(qty),
            client_order_id: cid,
          }
        : side === "bid"
          ? { market, side, ord_type: "market", price: String(Math.round(amount)), client_order_id: cid }
          : { market, side, ord_type: "market", volume: String(qty), client_order_id: cid };
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

  const sideLabel = side === "bid" ? "매수" : "매도";
  const btnClass = side === "bid" ? "btn--buy" : "btn--sell";

  return (
    <PanelCard>
      <div className={styles.form}>
        <SegmentedTab
          items={SIDE_ITEMS}
          value={side}
          onChange={setSide}
          variant="directional"
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
                inputMode="numeric"
                autoComplete="off"
                value={priceRaw ? formatKRW(priceRaw) : ""}
                placeholder={ticker ? formatPrice(curPrice) : "0"}
                onChange={(e) => onPriceInput(e.target.value)}
                aria-label="주문 가격(원)"
                disabled={submitting}
              />
              <span className={styles.unit}>원</span>
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
              <span className={styles.unit}>원</span>
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
              <span className={styles.unit}>{base}</span>
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

        <div className={styles.sumRow}>
          <span>주문총액</span>
          <b className="num">{formatKRW(total)} 원</b>
        </div>
        <div className={styles.sumRow}>
          <span>예상 수수료 (0.05%)</span>
          <b className="num">{formatKRW(fee)} 원</b>
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
              {side === "bid" ? `${formatKRW(availKRW)} 원` : `${formatQty(availCoin, qtyScale)} ${base}`}
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
              ? `${formatKRW(total)} KRW ${sideLabel} 확정`
              : `${sideLabel}하기`}
        </button>
      </div>
    </PanelCard>
  );
}
