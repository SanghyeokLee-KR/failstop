/**
 * 관제센터 상태 요약의 추세(관리자 설계 4-4). 관제 시뮬레이터가 초마다 잰 값을 분 단위로 모아 두고,
 * 화면이 고른 범위(1·6·24시간)의 점열과 앞 구간 대비 값을 만든다.
 *
 * 시뮬레이터는 화면을 연 순간에 시작하므로 그 앞의 연속 지표(가용성, p95, 5xx)는 분 번호에서 정해지는
 * 기준선으로 채운다. 값은 모두 지어낸 것이라 화면의 출처 칩이 시뮬레이션을 알린다. 경보와 사건은
 * 기준선을 만들지 않고 사건 기록만 센다. 기록에 없는 사건을 추세에만 그리면 두 칸이 서로 다른 말을 한다.
 */

import type { Incident, OpsTrends, OpsTrendSeries, TrendMetric, TrendRange } from "./types";

const MINUTE = 60_000;
/** 범위별 점 개수. 1시간은 1분, 6시간은 5분, 24시간은 15분 간격이다 */
const POINTS: Record<TrendRange, number> = { 1: 60, 6: 72, 24: 96 };
export const TREND_RANGES: TrendRange[] = [1, 6, 24];
/** 앞 구간까지 보려면 가장 긴 범위의 두 배가 필요하다 */
const SPAN_MIN = 24 * 60 * 2;

interface MinuteRec {
  p95: number;
  av: number;
  err: number;
  n: number;
  alertsMax: number;
  fired: number;
}

/** 분 번호에서 정해지는 -1..1 흔들림. 같은 분이면 늘 같은 값이다 */
function jitter(m: number, ch: number): number {
  const x = Math.sin(m * 12.9898 + ch * 78.233) * 43758.5453;
  return (x - Math.floor(x)) * 2 - 1;
}

/** 하루 주기(분 번호 기준 -1..1). 거래량이 낮 시간에 오르는 모양을 흉내 낸다 */
const daily = (m: number, phase: number) => Math.sin((2 * Math.PI * m) / 1440 + phase);

/**
 * 매끈한 흔들림(-1..1). period 분마다 정한 값 사이를 코사인으로 잇는다. 사인파를 더하면 일정한 물결이
 * 되어 지어낸 값이 티가 나고, 분마다 흔들면 지진계처럼 된다
 */
function drift(m: number, ch: number, period: number): number {
  const k = Math.floor(m / period);
  const t = (m - k * period) / period;
  const s = (1 - Math.cos(Math.PI * t)) / 2;
  const a = jitter(k, ch);
  return a + (jitter(k + 1, ch) - a) * s;
}

/**
 * 시뮬레이터가 시작되기 전 구간의 기준선. 시뮬레이터 평시 값(p95 약 170ms, 가용성 99.98%, 5xx 0.02%) 둘레다.
 * 하루 주기가 큰 모양을 만들고 짧은 흔들림은 작게 둔다. 흔들림이 크면 추세선이 지진계처럼 보여 읽을 것이 없다
 */
function baseline(m: number): { p95: number; av: number; err: number } {
  return {
    p95: 166 + 12 * daily(m, -1.2) + 5 * drift(m, 1, 47) + 0.8 * jitter(m, 1),
    av: Math.min(100, 99.979 + 0.007 * daily(m, 1.9) + 0.004 * drift(m, 2, 83)),
    err: Math.max(0, 0.021 + 0.006 * daily(m, -1.0) + 0.004 * drift(m, 3, 59) + 0.0008 * jitter(m, 3)),
  };
}

const sum = (xs: ArrayLike<number>, from: number, to: number) => {
  let s = 0;
  for (let i = from; i < to; i += 1) s += xs[i];
  return s;
};
const max = (xs: ArrayLike<number>, from: number, to: number) => {
  let v = 0;
  for (let i = from; i < to; i += 1) v = Math.max(v, xs[i]);
  return v;
};

export class TrendBook {
  private readonly minutes = new Map<number, MinuteRec>();

  /** 한 틱의 판독값을 그 분에 더한다. fired는 이번 틱에 새로 선 경보 수다 */
  record(now: number, p95: number, av: number, err: number, alerts: number, fired: number): void {
    const m = Math.floor(now / MINUTE);
    const rec = this.minutes.get(m) ?? { p95: 0, av: 0, err: 0, n: 0, alertsMax: 0, fired: 0 };
    rec.p95 += p95;
    rec.av += av;
    rec.err += err;
    rec.n += 1;
    rec.alertsMax = Math.max(rec.alertsMax, alerts);
    rec.fired += fired;
    this.minutes.set(m, rec);
    for (const k of this.minutes.keys()) if (k <= m - SPAN_MIN) this.minutes.delete(k);
  }

  build(now: number, incidents: Incident[]): OpsTrends {
    // 지난 48시간을 분 칸 하나씩 한 번만 채운다. 범위마다 이 배열을 잘라 쓴다
    const nowMin = Math.floor(now / MINUTE);
    const first = nowMin - SPAN_MIN + 1;
    const av = new Float64Array(SPAN_MIN);
    const p95 = new Float64Array(SPAN_MIN);
    const err = new Float64Array(SPAN_MIN);
    const alerts = new Float64Array(SPAN_MIN);
    const fired = new Float64Array(SPAN_MIN);
    const opened = new Float64Array(SPAN_MIN);
    const recorded = new Uint8Array(SPAN_MIN);
    for (let k = 0; k < SPAN_MIN; k += 1) {
      const m = first + k;
      const rec = this.minutes.get(m);
      if (rec && rec.n > 0) {
        av[k] = rec.av / rec.n;
        p95[k] = rec.p95 / rec.n;
        err[k] = rec.err / rec.n;
        alerts[k] = rec.alertsMax;
        fired[k] = rec.fired;
        recorded[k] = 1;
      } else {
        const b = baseline(m);
        av[k] = b.av;
        p95[k] = b.p95;
        err[k] = b.err;
      }
    }
    // 시뮬레이터가 잰 분은 그 분의 경보 수를 쓰고, 그 앞은 사건 하나를 경보 하나로 센다
    for (const inc of incidents) {
      const from = Math.floor(Date.parse(inc.openedAt) / MINUTE) - first;
      const to = (inc.resolvedAt === null ? nowMin : Math.floor(Date.parse(inc.resolvedAt) / MINUTE)) - first;
      if (from >= 0 && from < SPAN_MIN) {
        opened[from] += 1;
        if (!recorded[from]) fired[from] += 1;
      }
      for (let k = Math.max(0, from); k <= Math.min(SPAN_MIN - 1, to); k += 1) {
        if (!recorded[k]) alerts[k] += 1;
      }
    }

    const series = (hours: TrendRange, metric: TrendMetric): OpsTrendSeries => {
      const len = hours * 60;
      const step = len / POINTS[hours];
      const start = SPAN_MIN - len;
      const prev = start - len;
      const pick = (from: number, to: number): number => {
        switch (metric) {
          case "availability":
            return sum(av, from, to) / (to - from);
          case "p95":
            return sum(p95, from, to) / (to - from);
          case "errors":
            return sum(err, from, to) / (to - from);
          case "alerts":
            return sum(fired, from, to);
          case "incidents":
            return sum(opened, from, to);
        }
      };
      const points = Array.from({ length: POINTS[hours] }, (_, i) => {
        const a = start + i * step;
        return metric === "alerts" ? max(alerts, a, a + step) : pick(a, a + step);
      });
      return { points, current: pick(start, SPAN_MIN), previous: pick(prev, start) };
    };

    const out = {} as OpsTrends;
    for (const h of TREND_RANGES) {
      out[h] = {
        availability: series(h, "availability"),
        p95: series(h, "p95"),
        errors: series(h, "errors"),
        alerts: series(h, "alerts"),
        incidents: series(h, "incidents"),
      };
    }
    return out;
  }
}

/** 증감을 견줄 앞 시점(분). 고른 범위의 6분의 1 앞이다 */
export const DELTA_BACK_MIN: Record<TrendRange, number> = { 1: 10, 6: 60, 24: 240 };

export interface TrendDelta {
  /** 지표 단위의 차이. 사건은 그 사이에 연 수다 */
  diff: number;
  text: string;
  dir: "up" | "down" | "flat";
  /** 좋아졌는가. 변화가 없으면 null */
  good: boolean | null;
}

/**
 * 상태 요약 칸의 증감(디자인 시스템 §12-9). 지금 값과 범위의 6분의 1 앞 시점 값의 차이다. 한 구간 평균끼리 견주면
 * 방금 오른 507ms 옆에 '0%'가 서서 칸의 큰 수치와 증감이 서로 다른 말을 한다. 앞 시점 값은 추세 점열에서 그
 * 시점을 덮는 점이고, 사건은 그 사이에 새로 연 수를 센다. 값이 없으면 비교하지 않는다
 */
export function trendDelta(metric: TrendMetric, s: OpsTrendSeries, range: TrendRange, current: number | null): TrendDelta | null {
  const n = s.points.length;
  const back = Math.round((DELTA_BACK_MIN[range] / (range * 60)) * POINTS[range]);
  if (n <= back) return null;
  let diff: number;
  if (metric === "incidents") {
    diff = s.points.slice(n - back).reduce<number>((a, v) => a + (v ?? 0), 0);
  } else {
    const ref = s.points[n - 1 - back];
    if (current === null || ref === null) return null;
    diff = current - ref;
  }
  const better = metric === "availability" ? "up" : "down";
  let text: string;
  let flat: boolean;
  if (metric === "availability" || metric === "errors") {
    text = `${Math.abs(diff).toFixed(2)}%p`;
    flat = Math.abs(diff) < 0.005;
  } else if (metric === "p95") {
    text = `${Math.round(Math.abs(diff))}ms`;
    flat = Math.round(Math.abs(diff)) === 0;
  } else {
    text = `${Math.round(Math.abs(diff))}건`;
    flat = Math.round(diff) === 0;
  }
  if (flat) return { diff: 0, text: "±0", dir: "flat", good: null };
  const dir = diff > 0 ? "up" : "down";
  return { diff, text, dir, good: dir === better };
}

/**
 * 목표를 벗어난 채 이어진 분. 1시간 범위의 분 단위 점열 끝에서 거꾸로 세고 지금 분을 더한다. 칸이 목표를 벗어난 동안
 * 증감 대신 '목표 초과 n분째'로 적는다
 */
export function breachMinutes(points: (number | null)[], over: (v: number) => boolean): number {
  let m = 1;
  for (let i = points.length - 2; i >= 0; i -= 1) {
    const v = points[i];
    if (v === null || !over(v)) break;
    m += 1;
  }
  return m;
}
