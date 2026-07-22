/**
 * lib/format.ts. KRW/수량/등락률 포맷터.
 * 규약: 숫자는 tabular-nums + Intl.NumberFormat('ko-KR'), 방향은 색 단독 금지(부호+화살표 병기).
 */

import type { ChangeState, Direction } from "./types";

const nfKRW = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const nfDec = new Map<number, Intl.NumberFormat>();

function decFmt(digits: number): Intl.NumberFormat {
  let f = nfDec.get(digits);
  if (!f) {
    f = new Intl.NumberFormat("ko-KR", {
      minimumFractionDigits: 0,
      maximumFractionDigits: digits,
    });
    nfDec.set(digits, f);
  }
  return f;
}

/** 문자열 NUMERIC → number (표시 전용 변환. 계약상 원본은 항상 string) */
export function toNum(v: string | number | null | undefined): number {
  if (v == null) return 0;
  const n = typeof v === "number" ? v : parseFloat(v);
  return Number.isFinite(n) ? n : 0;
}

/** KRW 정수 포맷: 84320000 → "84,320,000" */
export function formatKRW(value: string | number): string {
  return nfKRW.format(Math.round(toNum(value)));
}

/** 가격 포맷. 1,000원 미만 코인은 소수 표시 허용 */
export function formatPrice(value: string | number): string {
  const n = toNum(value);
  if (Math.abs(n) >= 1000) return nfKRW.format(Math.round(n));
  if (Math.abs(n) >= 100) return decFmt(1).format(n);
  return decFmt(2).format(n);
}

/** 수량 포맷: 0.00412 → "0.00412" (최대 scale 자리, 후행 0 제거) */
export function formatQty(value: string | number, scale = 8): string {
  return decFmt(scale).format(toNum(value));
}

/** 등락률(절대값 rate 문자열 "0.0234" + change) → "+2.34%" / "−1.08%" / "0.00%" */
export function formatChangeRate(changeRate: string | number, change: ChangeState): string {
  const pct = (toNum(changeRate) * 100).toFixed(2);
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

/** 등락 금액 병기: ▲ +1,930,000 */
export function formatDirectionalAmount(amount: string | number, change: ChangeState): string {
  const n = Math.abs(toNum(amount));
  if (change === "RISE") return `▲ +${nfKRW.format(Math.round(n))}`;
  if (change === "FALL") return `▼ −${nfKRW.format(Math.round(n))}`;
  return nfKRW.format(Math.round(n));
}

/** 거래대금 축약: 345,000,000,000 → "3,450억", 64,000,000,000 → "640억", 그 미만 → "5,200만" */
export function formatCompactKRW(value: string | number): string {
  const n = toNum(value);
  if (n >= 1e8) return `${nfKRW.format(Math.round(n / 1e8))}억`;
  if (n >= 1e4) return `${nfKRW.format(Math.round(n / 1e4))}만`;
  return nfKRW.format(Math.round(n));
}

/** ChangeState → UI Direction */
export function dirOf(change: ChangeState): Direction {
  return change === "RISE" ? "up" : change === "FALL" ? "down" : "flat";
}

/** 두 가격 비교 → Direction (틱 플래시 판정) */
export function dirOfDelta(delta: number): Direction {
  return delta > 0 ? "up" : delta < 0 ? "down" : "flat";
}

/** 접근성 라벨: "상승 2.34퍼센트" (A-6 계약) */
export function ariaChangeLabel(changeRate: string | number, change: ChangeState): string {
  const pct = (toNum(changeRate) * 100).toFixed(2);
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
