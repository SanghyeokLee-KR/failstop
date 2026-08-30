/**
 * sim 캔들·24H 통계 정합 검증. 캔들의 24h 창 극값은 ticker의 high_24h/low_24h 안에 있어야 하고,
 * 24h 전 캔들 시가는 전일 종가와 정합해야 한다.
 * 엔진 start()를 호출하지 않아 틱이 돌지 않는 정지 상태에서 단정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimEngine, CANDLE_UNIT_MS } from "../lib/sim/engine";

function num(v: string): number {
  return Number(v);
}

test("캔들 24h 창 극값이 ticker 고저(24H) 대역 안이다", () => {
  const eng = new SimEngine();
  for (const market of ["KRW-BTC", "KRW-ETH", "KRW-DOT"]) {
    const tk = eng.getTicker([market])[0];
    const hi = num(tk.high_24h!);
    const lo = num(tk.low_24h!);
    for (const unit of ["15m", "60m"] as const) {
      const within = Math.ceil(86_400_000 / CANDLE_UNIT_MS[unit]);
      const candles = eng.getCandles(market, unit, within);
      // 라운딩 1스텝 여유. 대역을 넘는 극값은 차트 주석과 통계의 모순으로 나타난다
      const tol = Math.max(hi * 1e-6, 1000);
      for (const c of candles) {
        assert.ok(num(c.high) <= hi + tol, `${market} ${unit} high ${c.high} > ${hi}`);
        assert.ok(num(c.low) >= lo - tol, `${market} ${unit} low ${c.low} < ${lo}`);
      }
    }
  }
});

test("단위가 달라도 같은 시각 구간의 종가는 같은 경로를 읽는다", () => {
  const eng = new SimEngine();
  const m15 = eng.getCandles("KRW-BTC", "15m", 8);
  const h1 = eng.getCandles("KRW-BTC", "60m", 2);
  // 한 시간 캔들의 종가는 그 시간대의 마지막 15분 캔들 종가와 같은 분 경로 값이다.
  // 두 배열 모두 시각 내림차순이고 앵커를 포함하는 버킷이 0번이라, 선택이 아니라 인덱스로 짝을 짓는다.
  // 올바른 짝이면 두 종가가 같은 경로 인덱스를 읽어 정확히 같다(300표본 최대 괴리 0.000000%)
  assert.equal(m15[0].close, h1[0].close);
  assert.ok(m15[0].candle_time >= h1[0].candle_time, "15분 버킷이 시간 버킷 안에 있다");
});

test("최신 캔들 종가는 현재가, 24h 창 시가는 전일 종가와 정합한다", () => {
  const eng = new SimEngine();
  const tk = eng.getTicker(["KRW-BTC"])[0];
  const candles = eng.getCandles("KRW-BTC", "60m", 25);
  assert.equal(num(candles[0].close), num(tk.trade_price!));
  // 전일 종가 = 현재가 / (1+등락률). 24h 전 부근 캔들에 그 값이 실제로 나타난다
  const prevClose = num(tk.trade_price!) - (tk.change === "RISE" ? num(tk.change_price!) : -num(tk.change_price!));
  const near24h = candles.slice(22, 25).map((c) => [num(c.open), num(c.close), num(c.high), num(c.low)]).flat();
  const hit = near24h.some((v) => Math.abs(v - prevClose) / prevClose < 0.005);
  assert.ok(hit, `전일 종가 ${prevClose} 부근 값이 24h 전 캔들에 없다`);
});
