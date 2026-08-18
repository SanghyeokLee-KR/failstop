/**
 * tests/marketSpecs.test.ts. 마켓 스펙 로드 계약 고정.
 * 회귀 방지 대상: 홈·자산·거래내역이 markets를 로드하지 않아 표시 자릿수를 폴백으로 유도하던 결함.
 * 스펙 부재가 실제로 표기를 바꾼다는 전제와, 스토어 로드가 그 근거를 채운다는 결과를 함께 고정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { useExchangeStore } from "../lib/store";
import { formatPrice, formatQty } from "../lib/format";

test("스펙 미로드 시 표시 자릿수가 위조된다(가격·수량)", () => {
  // tick_size 1000 마켓의 저가 구간. 스펙이 없으면 가격대 폴백이 소수를 붙인다
  assert.equal(formatPrice("125.5", "1000"), "126");
  assert.notEqual(formatPrice("125.5"), formatPrice("125.5", "1000"));
  // qty_scale 4 마켓. 스펙이 없으면 기본 8자리로 없는 정밀도를 그린다
  assert.equal(formatQty("1.5", 4), "1.5000");
  assert.notEqual(formatQty("1.5"), formatQty("1.5", 4));
});

test("loadMarkets는 표시 자릿수의 근거를 스토어에 채운다", async () => {
  useExchangeStore.setState({ markets: [] });
  await useExchangeStore.getState().loadMarkets();
  const markets = useExchangeStore.getState().markets;
  assert.ok(markets.length > 0, "마켓 메타 로드");
  const btc = markets.find((m) => m.market === "KRW-BTC");
  assert.ok(btc, "KRW-BTC 스펙 존재");
  assert.equal(typeof btc.tick_size, "string");
  assert.equal(typeof btc.qty_scale, "number");
  // 로드된 스펙으로 렌더하면 tick_size 격자대로 소수 없이 그려진다
  assert.equal(formatPrice("64000000", btc.tick_size), "64,000,000");
});

test("loadMarkets 동시 호출은 같은 조회를 공유한다", async () => {
  useExchangeStore.setState({ markets: [] });
  const { loadMarkets } = useExchangeStore.getState();
  const a = loadMarkets();
  const b = loadMarkets();
  assert.equal(a, b, "진행 중 조회가 있으면 같은 프라미스를 돌려준다");
  await Promise.all([a, b]);
  assert.ok(useExchangeStore.getState().markets.length > 0);
});
