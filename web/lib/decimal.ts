/**
 * lib/decimal.ts. 문자열 NUMERIC(40,18) 십진 연산.
 * 계약: 금액·수량은 문자열로 다루고 float(parseFloat·JS number 산술)을 쓰지 않는다.
 * 곱·비교·스케일 변환을 BigInt로 처리해 2^53 초과 거래대금·근접 가격의 순서 역전과 정밀 손실을 막는다.
 * format.ts(표시), orderMath.ts(주문 격자)가 이 단일 출처를 공유한다.
 */

/** 지수표기를 고정 소수 문자열로 환산. 십진 연산은 지수표기를 입력으로 받지 않는다 */
export function normalizeDecimal(v: string | number): string {
  const s = typeof v === "number" ? String(v) : v.trim();
  if (s === "") return "0";
  if (!/[eE]/.test(s)) return s;
  const n = Number(s);
  if (!Number.isFinite(n)) return "0";
  return n.toFixed(20).replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
}

/** 십진 문자열의 소수 자릿수 */
export function decimalsOf(v: string | number): number {
  const s = normalizeDecimal(v);
  const i = s.indexOf(".");
  return i < 0 ? 0 : s.length - i - 1;
}

/** 십진 문자열 → 10^scale 스케일드 BigInt. 초과 자릿수 절사(버림) */
export function scaledIntTrunc(v: string | number, scale: number): bigint {
  const raw = normalizeDecimal(v);
  const neg = raw.startsWith("-");
  const s = (neg ? raw.slice(1) : raw) || "0";
  const [intPart, fracPart = ""] = s.split(".");
  const frac = (fracPart + "0".repeat(scale)).slice(0, scale);
  const n = BigInt((intPart || "0") + frac);
  return neg ? -n : n;
}

/** 십진 문자열 → 10^scale 스케일드 BigInt. scale 자리에서 반올림(half-up, 절댓값 기준) */
export function scaledIntRound(v: string | number, scale: number): bigint {
  const raw = normalizeDecimal(v);
  const neg = raw.startsWith("-");
  const s = (neg ? raw.slice(1) : raw) || "0";
  const [intPart, fracPart = ""] = s.split(".");
  const padded = (fracPart + "0".repeat(scale + 1)).slice(0, scale + 1);
  const keep = padded.slice(0, scale);
  const guard = padded.charCodeAt(scale) - 48;
  let n = BigInt((intPart || "0") + keep);
  if (guard >= 5) n += 1n;
  return neg ? -n : n;
}

/** 10^scale 스케일드 BigInt → 십진 문자열. 후행 0 제거 */
export function fromScaledInt(n: bigint, scale: number): string {
  const neg = n < 0n;
  let s = (neg ? -n : n).toString();
  if (scale <= 0) return (neg ? "-" : "") + s;
  s = s.padStart(scale + 1, "0");
  const intPart = s.slice(0, s.length - scale);
  const frac = s.slice(s.length - scale).replace(/0+$/, "");
  return (neg ? "-" : "") + intPart + (frac ? "." + frac : "");
}

/**
 * 두 십진 문자열의 정확 합. 공통 스케일 BigInt로 더해 float 오차와 2^53 상한을 피한다.
 * 잔고·평가금액 합산의 단일 출처다(화면마다 지역 구현을 두지 않는다).
 */
export function addDecimal(a: string | number, b: string | number): string {
  const s = Math.max(decimalsOf(a), decimalsOf(b));
  return fromScaledInt(scaledIntTrunc(a, s) + scaledIntTrunc(b, s), s);
}

/** 두 십진 문자열의 정확 차. 손익 계산이 음수를 그대로 보존한다 */
export function subDecimal(a: string | number, b: string | number): string {
  const s = Math.max(decimalsOf(a), decimalsOf(b));
  return fromScaledInt(scaledIntTrunc(a, s) - scaledIntTrunc(b, s), s);
}

/** 두 십진 문자열의 정확 곱. 결과 소수 자릿수는 두 입력의 합(반올림 없음) */
export function mulDecimal(a: string | number, b: string | number): string {
  const na = normalizeDecimal(a);
  const nb = normalizeDecimal(b);
  const da = decimalsOf(na);
  const db = decimalsOf(nb);
  const prod = scaledIntTrunc(na, da) * scaledIntTrunc(nb, db);
  return fromScaledInt(prod, da + db);
}

/**
 * 두 십진 문자열 비교. a<b → -1, a===b → 0, a>b → 1.
 * float 왕복 없이 공통 스케일 BigInt로 비교해 정렬 키의 순서 역전을 막는다.
 */
export function compareDecimal(a: string | number, b: string | number): -1 | 0 | 1 {
  const na = normalizeDecimal(a);
  const nb = normalizeDecimal(b);
  const scale = Math.max(decimalsOf(na), decimalsOf(nb));
  const ia = scaledIntTrunc(na, scale);
  const ib = scaledIntTrunc(nb, scale);
  return ia < ib ? -1 : ia > ib ? 1 : 0;
}

/** 절댓값 십진 문자열(부호만 제거, 값 보존) */
export function absDecimal(v: string | number): string {
  const s = normalizeDecimal(v);
  return s.startsWith("-") ? s.slice(1) : s;
}

/**
 * 십진 문자열 → 10^scale 스케일드 BigInt. scale 자리에서 0 반대 방향 올림(Java RoundingMode.UP).
 * 락 산식(§4-B-5 v2.7)이 UP 양자화를 요구한다. 내림·반올림으로 잡으면 락이 실제 대금보다
 * 작아져 체결 시점에 모자란다.
 */
export function scaledIntCeil(v: string | number, scale: number): bigint {
  const raw = normalizeDecimal(v);
  const neg = raw.startsWith("-");
  const s = (neg ? raw.slice(1) : raw) || "0";
  const [intPart, fracPart = ""] = s.split(".");
  const keep = (fracPart + "0".repeat(scale)).slice(0, scale);
  const rest = fracPart.slice(scale);
  let n = BigInt((intPart || "0") + keep);
  if (/[1-9]/.test(rest)) n += 1n;
  return neg ? -n : n;
}

/** scale 자리 UP 양자화(0 반대 방향 올림) 십진 문자열 */
export function ceilToScale(v: string | number, scale: number): string {
  const s = Math.min(Math.max(scale, 0), 30);
  return fromScaledInt(scaledIntCeil(v, s), s);
}

/** scale 자리 HALF_UP 반올림 십진 문자열 */
export function roundToScale(v: string | number, scale: number): string {
  const s = Math.min(Math.max(scale, 0), 30);
  return fromScaledInt(scaledIntRound(v, s), s);
}

/** scale 자리 절사(0 방향) 십진 문자열 */
export function truncToScale(v: string | number, scale: number): string {
  const s = Math.min(Math.max(scale, 0), 30);
  return fromScaledInt(scaledIntTrunc(v, s), s);
}

/**
 * 십진 나눗셈. scale 자리에서 HALF_UP 또는 절사. 제수가 0 이하면 null(호출자가 판정한다).
 * 평단가 산출(§4-C-1)과 원가 처분 안분이 같은 나눗셈을 쓰도록 단일 출처로 둔다.
 */
export function divDecimal(
  a: string | number,
  b: string | number,
  scale: number,
  mode: "half-up" | "trunc" = "half-up",
): string | null {
  const s = Math.min(Math.max(scale, 0), 30);
  const na = normalizeDecimal(a);
  const nb = normalizeDecimal(b);
  const da = decimalsOf(na);
  const db = decimalsOf(nb);
  const den = scaledIntTrunc(nb, db);
  if (den <= 0n) return null;
  const neg = na.startsWith("-");
  let num = scaledIntTrunc(na, da);
  if (num < 0n) num = -num;
  // guard 자리를 하나 더 계산해 HALF_UP 판정을 정수 나눗셈만으로 끝낸다
  const guardExp = mode === "half-up" ? 1 : 0;
  const scaled = (num * 10n ** BigInt(db + s + guardExp)) / (den * 10n ** BigInt(da));
  let out = mode === "half-up" ? scaled / 10n + (scaled % 10n >= 5n ? 1n : 0n) : scaled;
  if (neg) out = -out;
  return fromScaledInt(out, s);
}
