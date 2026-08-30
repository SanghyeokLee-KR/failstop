/**
 * KRW·가격·수량·등락 표시 포맷터. 금액·수량은 문자열로 다루고 표시 반올림·자리구분을 BigInt 십진 경로로 처리한다.
 * 표시 소수 자릿수는 마켓 스펙(tick_size·price_scale·qty_scale)에서 유도하며, 스펙이 있으면 반드시 넘긴다.
 * 스펙 미전달 시 가격대별 안전 폴백으로 sub-1 KRW가 "0"으로 뭉개지지 않게 한다.
 */

import type { ChangeState, Direction } from "./types";
import {
  absDecimal,
  compareDecimal,
  decimalsOf,
  mulDecimal,
  normalizeDecimal,
  scaledIntRound,
  scaledIntTrunc,
} from "./decimal";

/** 가격 스펙: tick_size 문자열("1000"·"0.0001") 또는 price_scale 숫자(소수 자릿수) */
export type PriceSpec = string | number;

const nfInt = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });

const MAX_DISPLAY_DECIMALS = 18; // NUMERIC(40,18) 소수부 상한

function clampDecimals(d: number): number {
  if (!Number.isFinite(d) || d <= 0) return 0;
  return d > MAX_DISPLAY_DECIMALS ? MAX_DISPLAY_DECIMALS : Math.floor(d);
}

// 표시 계층 최후 방어. 티커 null 계약(§4-A-4)은 Ticker 타입(string | null)이 소비처에서
// 컴파일 타임에 강제하는 것이 원칙이고, 여기는 계약 밖 결측(어댑터 이상 등)이 렌더 크래시로
// 번지는 것만 0 강등으로 막는다. 금액 산술(decimal.ts)은 엄격 유지해 계산 경로의 null은 드러낸다.
function displayValue(v: string | number | null | undefined): string | number {
  return v == null || v === "" ? 0 : v;
}

/**
 * 십진 문자열을 고정 소수 자릿수로 반올림해 자리구분 표기. 음수는 U+2212로 적는다.
 * 정수부는 BigInt로 2^53 초과를 보존하고, 소수부는 후행 0을 유지해 tabular 정렬을 지킨다.
 */
function formatFixed(value: string | number, decimals: number): string {
  const d = clampDecimals(decimals);
  const scaled = scaledIntRound(displayValue(value), d);
  const neg = scaled < 0n;
  const digits = (neg ? -scaled : scaled).toString().padStart(d + 1, "0");
  const cut = digits.length - d;
  const intStr = nfInt.format(BigInt(digits.slice(0, cut)));
  const body = d > 0 ? `${intStr}.${digits.slice(cut)}` : intStr;
  return neg ? `−${body}` : body;
}

/** 스펙 미전달 시 가격대별 소수 자릿수. sub-1 KRW·sub-100 KRW가 "0"으로 붕괴하지 않게 넉넉히 잡는다 */
function fallbackPriceDecimals(value: string | number): number {
  const n = Math.abs(toNum(value));
  if (n >= 1000) return 0;
  if (n >= 1) return 2;
  if (n >= 0.01) return 4;
  if (n >= 0.0001) return 6;
  if (n > 0) return 8;
  return 0;
}

/** 가격 스펙 → 고정 소수 자릿수. 숫자는 price_scale, 문자열은 tick_size에서 유도 */
function priceDecimalsOf(spec: PriceSpec | undefined, value: string | number): number {
  if (typeof spec === "number") return clampDecimals(spec);
  if (typeof spec === "string" && spec.trim() !== "") return clampDecimals(decimalsOf(spec));
  return fallbackPriceDecimals(value);
}

/** 절대 비율(예: "0.0234")을 백분율 소수 2자리 문자열("2.34")로. 자리구분 불필요한 소수 비율 */
function formatPct(rate: string | number): string {
  const pct = mulDecimal(normalizeDecimal(displayValue(rate)), "100");
  const d = 2;
  const scaled = scaledIntRound(absDecimal(pct), d);
  const digits = scaled.toString().padStart(d + 1, "0");
  const cut = digits.length - d;
  return `${digits.slice(0, cut)}.${digits.slice(cut)}`;
}

/** 문자열 NUMERIC → number. 표시 폴백 대역 판정·비산술 용도의 경계 변환 전용. 금액 산술에 쓰지 않는다 */
export function toNum(v: string | number | null | undefined): number {
  if (v == null) return 0;
  const n = typeof v === "number" ? v : parseFloat(v);
  return Number.isFinite(n) ? n : 0;
}

/** KRW 정수 포맷: "84320000" → "84,320,000" (won 아래 절사 없이 반올림) */
export function formatKRW(value: string | number): string {
  return formatFixed(value, 0);
}

/** 정수 카운트 포맷(대수·OPS 등 통화 아닌 개수): 1200 → "1,200" */
export function formatCount(value: string | number): string {
  return formatFixed(value, 0);
}

/**
 * 가격 포맷. 소수 자릿수를 마켓 스펙(tick_size 문자열 또는 price_scale 숫자)에서 유도한다.
 * 스펙 미전달 시 가격대별 폴백. sub-1 KRW(최저 0.000398)가 "0"·동일 문자열로 붕괴하지 않는다.
 */
export function formatPrice(value: string | number, spec?: PriceSpec): string {
  return formatFixed(value, priceDecimalsOf(spec, value));
}

/** 수량 포맷: qty_scale에서 고정 소수 자릿수 유도. 0.00412(scale 8) → "0.00412000" (절단·정수강제 없음) */
export function formatQty(value: string | number, qtyScale = 8): string {
  return formatFixed(value, qtyScale);
}

/** 등락률(절대 rate 문자열 + change) → "+2.34%" / "−1.08%" / "0.00%" */
export function formatChangeRate(changeRate: string | number, change: ChangeState): string {
  const pct = formatPct(changeRate);
  if (change === "RISE") return `+${pct}%`;
  if (change === "FALL") return `−${pct}%`; // U+2212 MINUS SIGN
  return "0.00%";
}

/** 방향 병기 라벨: 색 단독 금지 → 화살표+부호 병기 (▲ +2.34% / ▼ −1.08% / 0.00%) */
export function formatDirectional(changeRate: string | number, change: ChangeState): string {
  const body = formatChangeRate(changeRate, change);
  if (change === "RISE") return `▲ ${body}`;
  if (change === "FALL") return `▼ ${body}`;
  return body; // 보합: 화살표 없음
}

/**
 * 등락 금액 병기: ▲ +1,930,000 / ▼ −0.000398.
 * 소수 자릿수를 가격 스펙에서 유도해 sub-1 KRW change_price가 "+0"으로 뭉개지지 않게 한다.
 */
export function formatDirectionalAmount(
  amount: string | number,
  change: ChangeState,
  spec?: PriceSpec,
): string {
  const d = priceDecimalsOf(spec, amount);
  const body = formatFixed(absDecimal(displayValue(amount)), d);
  if (change === "RISE") return `▲ +${body}`;
  if (change === "FALL") return `▼ −${body}`;
  return body;
}

/** KRW 거래대금 축약: "345000000000" → "3,450억", "64000000000" → "640억", 그 미만 → "5,200만" */
export function formatCompactKRW(value: string | number): string {
  const won = scaledIntTrunc(displayValue(value), 0); // won 아래 절사. 억·만 축약에서 sub-won은 무의미
  const neg = won < 0n;
  const a = neg ? -won : won;
  const roundDiv = (n: bigint, unit: bigint) => nfInt.format((n + unit / 2n) / unit);
  let out: string;
  if (a >= 100_000_000n) out = `${roundDiv(a, 100_000_000n)}억`;
  else if (a >= 10_000n) out = `${roundDiv(a, 10_000n)}만`;
  else out = nfInt.format(a);
  return neg ? `−${out}` : out;
}

/**
 * 코인 수량 축약. 1 이상은 소수 2자리로 압축하고,
 * 1 미만은 qty_scale까지 보존해 세션 초반 소액 누적분이 "0"으로 사라지지 않게 한다.
 */
export function formatCompactQty(value: string | number, qtyScale = 8): string {
  const v = displayValue(value);
  if (compareDecimal(absDecimal(v), "1") >= 0) return formatFixed(v, 2);
  return formatFixed(v, qtyScale);
}

/** ChangeState → UI Direction */
export function dirOf(change: ChangeState): Direction {
  return change === "RISE" ? "up" : change === "FALL" ? "down" : "flat";
}

/** 숫자 delta → Direction (틱 플래시 판정. 근접 가격은 dirOfPrices 사용) */
export function dirOfDelta(delta: number): Direction {
  return delta > 0 ? "up" : delta < 0 ? "down" : "flat";
}

/** 두 가격 문자열 비교 → Direction. float 왕복 없이 근접 가격의 방향 오판을 막는다 */
export function dirOfPrices(prev: string | number, next: string | number): Direction {
  const c = compareDecimal(next, prev);
  return c > 0 ? "up" : c < 0 ? "down" : "flat";
}

/**
 * net bias 표기. 색 단독 금지라 방향어·화살표·부호·수치를 함께 낸다.
 * 입력은 분수 문자열이고 Number 변환은 방향 판정과 자리 맞춤에만 쓴다.
 */
export function formatNetBias(v: string | number): { text: string; dir: Direction } {
  const n = Number(v);
  if (!Number.isFinite(n)) return { text: "중립 0.00", dir: "flat" };
  const mag = (Math.round(Math.abs(n) * 100) / 100).toFixed(2);
  if (n > 0.0001) return { text: `양전 ▲ +${mag}`, dir: "up" };
  if (n < -0.0001) return { text: `음전 ▼ −${mag}`, dir: "down" };
  return { text: `중립 ${mag}`, dir: "flat" };
}

/** 접근성 라벨: "상승 2.34퍼센트" (A-6 계약) */
export function ariaChangeLabel(changeRate: string | number, change: ChangeState): string {
  const pct = formatPct(changeRate);
  if (change === "RISE") return `상승 ${pct}퍼센트`;
  if (change === "FALL") return `하락 ${pct}퍼센트`;
  return "보합";
}

/** UTC ISO(밀리초 3자리) 타임스탬프. API 계약 §2-1 형식 */
export function isoNow(t: number = Date.now()): string {
  return new Date(t).toISOString(); // toISOString은 항상 .SSS'Z' 형식
}

/** "14:23:01" 로컬 시각 (체결 목록용) */
export function formatTimeHMS(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 거래내역 시각 표기. 오늘 주문은 시:분:초, 그 외 날짜는 MM.DD 시:분을 병기한다.
 * 날짜 없는 HH:MM:SS만으로는 어제 이전 주문이 언제인지 구분되지 않는다.
 */
export function formatHistoryTime(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
