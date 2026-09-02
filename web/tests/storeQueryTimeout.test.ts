/**
 * tests/storeQueryTimeout.test.ts. 사설 조회 왕복 상한 검증.
 *
 * 회귀 방지 대상: 응답하지 않는 잔고 조회 하나가 in-flight 가드를 영구 고착시켜 새로고침도
 * 429 자동 재조회도 통째로 삼키던 결함. 판정은 가드 플래그가 내려갔는지가 아니라 그 뒤의
 * 재조회가 실제 왕복을 만드는지로 한다. 플래그만 보면 어댑터를 부르지 않아도 통과한다.
 */

import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { getDataSource } from "../lib/api/datasource";
import { ApiError } from "../lib/api/httpDatasource";
import { PRIVATE_QUERY_TIMEOUT_MS, useExchangeStore } from "../lib/store";
import type { Asset } from "../lib/types";

/* 스토어는 싱글턴 어댑터를 직접 부른다. 주입 지점이 없어 인스턴스 메서드를 덮고 복원한다 */
const ds = getDataSource();

type Patched = "getAccounts" | "getOpenOrders" | "getClosedOrders";

function restore(...keys: Patched[]): void {
  for (const k of keys) Reflect.deleteProperty(ds, k);
}

function krw(balance: string): Asset {
  return {
    currency: "KRW",
    balance,
    locked: "0",
    avg_buy_price: "0",
    unit_currency: "KRW",
    cost_basis_qty: "0",
    cost_basis_amount: "0",
  };
}

/** 조회를 끊은 사유. 상한이 끊었는지와 다른 실패를 구분하는 축이다 */
function cutCode(reason: unknown): string {
  return reason instanceof ApiError ? reason.code : String(reason);
}

/** 응답하지 않는 왕복. 취소 신호를 받으면 fetch와 같은 방식으로 거절한다 */
function hangUntilAbort(signal?: AbortSignal): Promise<never> {
  return new Promise((_, reject) => {
    // 핸들을 못 받으면 영영 끝나지 않는다. 테스트를 매달지 말고 그 자리에서 실패시킨다
    if (!signal) {
      reject(new Error("스토어가 취소 핸들을 넘기지 않았다"));
      return;
    }
    signal.addEventListener("abort", () => reject(new DOMException("취소", "AbortError")), { once: true });
  });
}

function resetAccounts(assets: Asset[], state: "idle" | "ok"): void {
  useExchangeStore.setState({
    session: null,
    assets,
    accountsInFlight: false,
    accountsInFlightId: null,
    accountsHealth: state === "ok"
      ? { state: "ok", lastUpdatedAt: 42, retryAfterSec: null }
      : { state: "idle", lastUpdatedAt: null, retryAfterSec: null },
  });
}

test("미해소 잔고 조회는 상한에서 끊기고 재조회가 다시 왕복을 만든다", async () => {
  resetAccounts([], "idle");
  let calls = 0;
  let handles = 0;
  ds.getAccounts = (signal) => {
    calls += 1;
    if (signal) handles += 1;
    return calls === 1 ? hangUntilAbort(signal) : Promise.resolve([krw("1000")]);
  };
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const cut = useExchangeStore.getState().loadAccounts().then(() => null, (e: unknown) => e);
    assert.equal(useExchangeStore.getState().accountsInFlight, true, "왕복이 실제로 떴다");

    // 상한 전에는 가드가 살아 있다. 이 삼킴이 없으면 아래 해제 판정이 판정할 대상을 잃는다
    await useExchangeStore.getState().loadAccounts();
    assert.equal(calls, 1, "같은 신원의 연타는 왕복을 늘리지 않는다");

    mock.timers.tick(PRIVATE_QUERY_TIMEOUT_MS);
    const cutBy = await cut;
    assert.ok(cutBy instanceof ApiError, "상한 초과는 실패로 확정된다");
    assert.equal(cutBy.code, "query_timeout");
    assert.notEqual(cutBy.status, 429, "취소를 백프레셔로 적지 않는다");
    assert.equal(useExchangeStore.getState().accountsInFlight, false, "고착이 풀린다");

    await useExchangeStore.getState().loadAccounts();
    assert.equal(calls, 2, "재조회가 삼켜지지 않고 실제 왕복을 만든다");
    assert.equal(handles, 2, "두 왕복 모두 취소 핸들을 받았다");
    assert.equal(useExchangeStore.getState().accountsHealth.state, "ok");
    assert.equal(useExchangeStore.getState().assets[0].balance, "1000");
  } finally {
    mock.timers.reset();
    restore("getAccounts");
  }
});

test("값이 없는 채로 끊기면 failed 이고 대기 초를 만들지 않는다", async () => {
  resetAccounts([], "idle");
  ds.getAccounts = (signal) => hangUntilAbort(signal);
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const cut = useExchangeStore.getState().loadAccounts().catch((e: unknown) => e);
    mock.timers.tick(PRIVATE_QUERY_TIMEOUT_MS);
    // 상한이 끊은 실패인지 먼저 확인한다. 다른 사유의 실패도 같은 신선도 표를 만든다
    assert.equal(cutCode(await cut), "query_timeout");
    const h = useExchangeStore.getState().accountsHealth;
    assert.equal(h.state, "failed", "표시할 잔고가 없다");
    assert.equal(h.retryAfterSec, null, "429가 아닌 실패에 회복 시각을 지어내지 않는다");
    assert.equal(useExchangeStore.getState().accountsLoaded, true);
  } finally {
    mock.timers.reset();
    restore("getAccounts");
  }
});

test("받아둔 값이 있으면 끊겨도 stale 이고 값을 지우지 않는다", async () => {
  resetAccounts([krw("1000")], "ok");
  ds.getAccounts = (signal) => hangUntilAbort(signal);
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const cut = useExchangeStore.getState().loadAccounts().catch((e: unknown) => e);
    mock.timers.tick(PRIVATE_QUERY_TIMEOUT_MS);
    assert.equal(cutCode(await cut), "query_timeout");
    const s = useExchangeStore.getState();
    assert.equal(s.accountsHealth.state, "stale");
    assert.equal(s.accountsHealth.retryAfterSec, null);
    assert.equal(s.accountsHealth.lastUpdatedAt, 42, "값의 나이는 보존한다");
    assert.equal(s.assets.length, 1, "실패 한 번에 잔고를 비우지 않는다");
  } finally {
    mock.timers.reset();
    restore("getAccounts");
  }
});

test("상한 전에 도착한 응답은 끊지 않는다", async () => {
  resetAccounts([], "idle");
  ds.getAccounts = () => Promise.resolve([krw("7")]);
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await useExchangeStore.getState().loadAccounts();
    // 해제되지 않은 타이머가 뒤늦게 성공을 실패로 뒤집는지 확인한다
    mock.timers.tick(PRIVATE_QUERY_TIMEOUT_MS * 2);
    const s = useExchangeStore.getState();
    assert.equal(s.accountsHealth.state, "ok");
    assert.equal(s.assets[0].balance, "7");
    assert.equal(s.accountsInFlight, false);
  } finally {
    mock.timers.reset();
    restore("getAccounts");
  }
});

test("미해소 주문 조회도 상한에서 끊겨 로딩 플래그가 선다", async () => {
  useExchangeStore.setState({ session: null, openOrders: [], closedOrders: [], ordersLoaded: false });
  let openCalls = 0;
  let closedCalls = 0;
  ds.getOpenOrders = (_market, signal) => {
    openCalls += 1;
    return hangUntilAbort(signal);
  };
  ds.getClosedOrders = (_opts, signal) => {
    closedCalls += 1;
    return hangUntilAbort(signal);
  };
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const cut = useExchangeStore.getState().loadOrders().catch((e: unknown) => e);
    assert.equal(useExchangeStore.getState().ordersLoaded, false, "왕복 중에는 아직 미확정이다");
    mock.timers.tick(PRIVATE_QUERY_TIMEOUT_MS);
    const cutBy = await cut;
    assert.ok(cutBy instanceof ApiError);
    assert.equal(cutBy.code, "query_timeout");
    assert.equal(openCalls, 1, "판정 대상 왕복이 실제로 있었다");
    assert.equal(closedCalls, 1);
    assert.equal(useExchangeStore.getState().ordersLoaded, true, "스켈레톤이 영원히 돌지 않는다");
  } finally {
    mock.timers.reset();
    restore("getOpenOrders", "getClosedOrders");
  }
});
