/**
 * 주문 프리필 병합 규칙 회귀.
 * /order 리다이렉트 쿼리 승계는 정적 export에서 Suspense 경계 안으로 격리됐다(주문 슬롯만 CSR).
 * 경계를 옮기면서 두 출처(쿼리·호가 클릭)의 우선순위가 뒤집히면 딥링크 진입 화면에서
 * 호가 클릭이 먹지 않는다. 그 계약을 여기서 고정한다.
 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  effectivePrefill,
  nextClickPrefill,
  prefillSideOf,
  queryPrefillOf,
  type PricePrefill,
} from "../components/trade/prefill";

test("쿼리 side는 ask만 매도로 읽는다", () => {
  assert.equal(prefillSideOf("ask"), "ask");
  assert.equal(prefillSideOf("bid"), "bid");
  assert.equal(prefillSideOf(null), "bid");
  assert.equal(prefillSideOf(""), "bid");
  assert.equal(prefillSideOf("ASK"), "bid");
  assert.equal(prefillSideOf("sell"), "bid");
});

test("쿼리 가격은 문자열 그대로 seq 0으로 승계한다", () => {
  assert.deepEqual(queryPrefillOf("84338000"), { price: "84338000", seq: 0 });
  // 소수 틱 마켓의 자릿수가 number 왕복으로 뭉개지지 않아야 한다
  assert.deepEqual(queryPrefillOf("0.000398"), { price: "0.000398", seq: 0 });
});

test("가격 없는 쿼리는 승계할 프리필이 없다", () => {
  assert.equal(queryPrefillOf(null), null);
  assert.equal(queryPrefillOf(""), null);
});

test("호가 클릭 프리필이 쿼리 승계분을 이긴다", () => {
  const fromQuery = queryPrefillOf("84338000");
  const clicked: PricePrefill = { price: "84400000", seq: 1 };
  assert.deepEqual(effectivePrefill(clicked, fromQuery), clicked);
});

test("클릭 전에는 쿼리 승계분이 쓰인다", () => {
  const fromQuery = queryPrefillOf("84338000");
  assert.deepEqual(effectivePrefill(null, fromQuery), fromQuery);
});

test("양쪽 다 없으면 프리필 없음(현재가 자동 채움 경로)", () => {
  assert.equal(effectivePrefill(null, null), null);
});

test("마켓 전환으로 양쪽이 파기되면 프리필도 사라진다", () => {
  // 전환 시 부모는 클릭분을, 슬롯은 쿼리 승계분을 각각 null로 되돌린다
  assert.equal(effectivePrefill(null, null), null);
});

test("같은 가격 재클릭도 seq가 올라 재주입된다", () => {
  const first = nextClickPrefill(null, "84338000");
  assert.deepEqual(first, { price: "84338000", seq: 1 });
  const again = nextClickPrefill(first, "84338000");
  assert.deepEqual(again, { price: "84338000", seq: 2 });
  assert.notDeepEqual(first, again);
});

test("클릭 seq는 쿼리 승계분(0) 위에서 시작한다", () => {
  const fromQuery = queryPrefillOf("84338000");
  const clicked = nextClickPrefill(null, "84400000");
  assert.equal(fromQuery?.seq, 0);
  assert.equal(clicked.seq, 1);
  // 승계분과 클릭분이 같은 가격이어도 seq가 달라 주입 이펙트가 다시 돈다
  const samePrice = nextClickPrefill(null, "84338000");
  assert.notDeepEqual(samePrice, fromQuery);
});
