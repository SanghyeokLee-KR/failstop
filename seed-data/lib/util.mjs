// 두 수집 스크립트가 공유하는 최소 유틸. 외부 의존 없이 Node 20+ 내장 API만 사용한다.

import { mkdir, writeFile } from "node:fs/promises";

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 초당 요청 상한을 지키기 위한 간격 게이트. Upbit는 초당 10회/IP이므로
// minIntervalMs를 100 이상으로 두면 안전 구간에 머문다.
export class RateGate {
  constructor(minIntervalMs) {
    this.min = minIntervalMs;
    this.last = 0;
  }
  async wait() {
    const delta = Date.now() - this.last;
    if (delta < this.min) await sleep(this.min - delta);
    this.last = Date.now();
  }
}

function isTransient(err) {
  // fetch 계층의 네트워크 오류는 code가 없고 name이 TypeError로 오는 경우가 있어 이름만으로 못 거른다.
  return err && (err.name === "AbortError" || err.name === "TypeError" || typeof err.code === "string");
}

async function withTimeout(url, headers, timeoutMs) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    return await fetch(url, { headers, signal: ctrl.signal });
  } finally {
    clearTimeout(t);
  }
}

// 429/5xx와 네트워크 오류에만 지수 백오프 재시도. 그 외 4xx는 즉시 실패로 올린다.
async function requestWithRetry(url, { headers = {}, gate, retries = 4, timeoutMs = 15000 }) {
  let attempt = 0;
  for (;;) {
    if (gate) await gate.wait();
    try {
      const res = await withTimeout(url, headers, timeoutMs);
      if (res.status === 429 || res.status >= 500) {
        if (attempt >= retries) throw new Error(`HTTP ${res.status} (재시도 ${attempt}회 초과): ${url}`);
        const retryAfter = Number(res.headers.get("Retry-After")) || 0;
        const backoff = retryAfter > 0 ? retryAfter * 1000 : Math.min(500 * 2 ** attempt, 8000);
        attempt += 1;
        await sleep(backoff);
        continue;
      }
      return res;
    } catch (err) {
      if (isTransient(err) && attempt < retries) {
        attempt += 1;
        await sleep(Math.min(500 * 2 ** attempt, 8000));
        continue;
      }
      throw err;
    }
  }
}

export async function fetchJson(url, opts = {}) {
  const res = await requestWithRetry(url, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
  const remaining = res.headers.get("Remaining-Req");
  const data = await res.json();
  return { data, remaining };
}

export async function fetchBuffer(url, opts = {}) {
  const res = await requestWithRetry(url, { timeoutMs: 20000, ...opts });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
  const contentType = res.headers.get("Content-Type") || "";
  const buf = Buffer.from(await res.arrayBuffer());
  return { buf, contentType };
}

export async function ensureDir(path) {
  await mkdir(path, { recursive: true });
}

export async function writeJson(path, obj) {
  await writeFile(path, JSON.stringify(obj, null, 2) + "\n", "utf8");
}

// 지수표기 문자열을 평문 소수로 편다. 예: "1.5e-7" -> "0.00000015".
function expandExponential(s) {
  const [mantissa, expPart] = s.split(/e/i);
  const exp = Number(expPart);
  const sign = mantissa.startsWith("-") ? "-" : "";
  const digits = mantissa.replace("-", "");
  const [intPart, fracPart = ""] = digits.split(".");
  const all = intPart + fracPart;
  const point = intPart.length + exp;
  if (point <= 0) return `${sign}0.${"0".repeat(-point)}${all}`;
  if (point >= all.length) return sign + all + "0".repeat(point - all.length);
  return `${sign}${all.slice(0, point)}.${all.slice(point)}`;
}

// 금액·수량은 문자열로만 저장한다(정본 규칙: float 금지).
// String()의 최단 왕복 표기를 써서 toFixed가 만드는 float 노이즈("5.8"->"5.7999..")를 피한다.
// 지수표기는 평문으로 펴고, 소수부는 NUMERIC(40,18) 상한에 맞춰 18자리로 자른다.
export function toPlainString(num, maxFrac = 18) {
  if (!Number.isFinite(num)) return "0";
  let s = String(num);
  if (/e/i.test(s)) s = expandExponential(s);
  if (s.includes(".")) {
    const [i, f] = s.split(".");
    if (f.length > maxFrac) s = maxFrac === 0 ? i : `${i}.${f.slice(0, maxFrac)}`;
    s = s.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
  }
  if (s === "" || s === "-0") s = "0";
  return s;
}

export function mean(arr) {
  if (arr.length === 0) return 0;
  return arr.reduce((a, b) => a + b, 0) / arr.length;
}

export function median(arr) {
  if (arr.length === 0) return 0;
  const s = [...arr].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

// 표본 표준편차(n-1). 변동성 추정에 표본 분산을 쓰는 관례를 따른다.
export function sampleStddev(arr) {
  if (arr.length < 2) return 0;
  const m = mean(arr);
  const v = arr.reduce((a, b) => a + (b - m) ** 2, 0) / (arr.length - 1);
  return Math.sqrt(v);
}
