/**
 * tests/myAssetVersion.test.ts. myAsset 잔고 판(version) 적용의 스토어 경로 검증(ADR 0034 S6, 설계 4-9·4-13).
 *
 * 게시 노드가 둘이면 같은 사용자의 KRW 잔고를 두 노드가 각자 읽어 보내고, 두 메시지가 서로 다른 경로로 와서 순서가
 * 뒤집힐 수 있다. seq 는 노드마다 달라 순서를 말하지 못하므로 스토어가 잔고 판으로 판정한다.
 * 스토어는 싱글턴 어댑터를 직접 부른다. 주입 지점이 없어 인스턴스 메서드를 덮고 복원한다(storeQueryTimeout 과 같은 방식).
 */

import { mock, test } from "node:test";
import assert from "node:assert/strict";
import { getDataSource } from "../lib/api/datasource";
import { useExchangeStore } from "../lib/store";
import type { Asset, WsMessage } from "../lib/types";

const ds = getDataSource();

interface Item {
  currency: string;
  balance: string;
  version?: number;
  avg?: string;
}

function asset(a: Item): Asset {
  return {
    currency: a.currency,
    balance: a.balance,
    locked: "0",
    avg_buy_price: a.avg ?? "0",
    unit_currency: "KRW",
    cost_basis_qty: "0",
    cost_basis_amount: "0",
    ...(a.version === undefined ? {} : { version: a.version }),
  } as Asset;
}

function myAsset(items: Item[]): WsMessage {
  return {
    schema_version: "1.0",
    type: "myAsset",
    epoch: 1,
    seq: 1,
    timestamp: "2026-09-25T00:00:00.000Z",
    market: "KRW-BTC",
    assets: items.map((a) => {
      const { unit_currency: _u, ...rest } = asset(a);
      return rest;
    }),
  } as unknown as WsMessage;
}

function reset(assets: Asset[]): void {
  useExchangeStore.setState({
    session: null,
    assets,
    accountsInFlight: false,
    accountsInFlightId: null,
    accountsHealth: { state: "ok", lastUpdatedAt: 1, retryAfterSec: null },
  });
}

async function flush(): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise((r) => setImmediate(r));
}

function krw(): Asset {
  return useExchangeStore.getState().assets.find((a) => a.currency === "KRW")!;
}

test("작은 판의 myAsset 은 적용하지 않고 계좌 재조회는 2초에 한 번으로 묶는다", async () => {
  reset([asset({ currency: "KRW", balance: "1000", version: 10 })]);
  let calls = 0;
  ds.getAccounts = () => {
    calls += 1;
    return Promise.resolve([asset({ currency: "KRW", balance: "1000", version: 10 })]);
  };
  mock.timers.enable({ apis: ["setTimeout", "Date"], now: 1_000_000 });
  try {
    const { apply } = useExchangeStore.getState();
    apply(myAsset([{ currency: "KRW", balance: "900", version: 8 }]));
    await flush();
    apply(myAsset([{ currency: "KRW", balance: "800", version: 7 }]));
    mock.timers.tick(1_000);
    apply(myAsset([{ currency: "KRW", balance: "700", version: 9 }]));
    await flush();
    assert.equal(krw().balance, "1000", "작은 판은 잔고를 되돌리지 않는다");
    assert.equal((krw() as { version?: number }).version, 10);
    assert.equal(calls, 1, "2초 안의 재조회는 한 번이다");

    // 창이 지나면 묶어 둔 요청이 한 번 더 나간다. 마지막 신호를 버리지 않는다
    mock.timers.tick(1_000);
    await flush();
    assert.equal(calls, 2);
  } finally {
    mock.timers.reset();
    Reflect.deleteProperty(ds, "getAccounts");
  }
});

test("같은 판의 myAsset 은 잔고·잠금을 그대로 두고 원가 필드를 새 값으로 바꾼다", async () => {
  reset([asset({ currency: "KRW", balance: "1000", version: 10, avg: "0" }), asset({ currency: "BTC", balance: "1", version: 4, avg: "50000000" })]);
  let calls = 0;
  ds.getAccounts = () => {
    calls += 1;
    return Promise.resolve([]);
  };
  try {
    // 원가 재구축(rebuild_position_cost)은 balances 를 건드리지 않아 판이 그대로다(설계 4-9)
    useExchangeStore.getState().apply(myAsset([{ currency: "BTC", balance: "1", version: 4, avg: "51000000" }]));
    await flush();
    const btc = useExchangeStore.getState().assets.find((a) => a.currency === "BTC")!;
    assert.equal(btc.balance, "1");
    assert.equal(btc.avg_buy_price, "51000000", "같은 판이면 원가 필드는 새 값이다");
    assert.equal(calls, 0, "같은 판은 재조회 신호가 아니다");
  } finally {
    Reflect.deleteProperty(ds, "getAccounts");
  }
});

test("판이 없는 myAsset 은 판이 없는 값을 종전처럼 덮어쓴다", async () => {
  reset([asset({ currency: "KRW", balance: "1000" })]);
  let calls = 0;
  ds.getAccounts = () => {
    calls += 1;
    return Promise.resolve([]);
  };
  try {
    useExchangeStore.getState().apply(myAsset([{ currency: "KRW", balance: "900" }]));
    await flush();
    assert.equal(krw().balance, "900");
    assert.equal(calls, 0);
  } finally {
    Reflect.deleteProperty(ds, "getAccounts");
  }
});

test("계좌 조회 왕복 중 더 새 판이 오면 그 통화는 지금 값을 둔다", async () => {
  reset([asset({ currency: "KRW", balance: "1000", version: 10 }), asset({ currency: "BTC", balance: "1", version: 4 })]);
  const pending: { resolve: ((a: Asset[]) => void) | null } = { resolve: null };
  ds.getAccounts = () =>
    new Promise<Asset[]>((resolve) => {
      pending.resolve = resolve;
    });
  try {
    const load = useExchangeStore.getState().loadAccounts();
    // 요청 뒤 체결이 KRW 판을 12 로 올렸다
    useExchangeStore.getState().apply(myAsset([{ currency: "KRW", balance: "700", version: 12 }]));
    assert.ok(pending.resolve);
    // 응답은 요청 때(10)보다 새 11 이지만 지금 값(12)보다 오래됐다. BTC 는 응답이 지금 값보다 새롭다
    pending.resolve([
      asset({ currency: "KRW", balance: "800", version: 11 }),
      asset({ currency: "BTC", balance: "2", version: 5 }),
    ]);
    await load;
    const s = useExchangeStore.getState();
    assert.equal(krw().balance, "700", "요청 뒤 온 더 새 실시간을 늦은 응답이 되돌리지 않는다");
    assert.equal(s.assets.find((a) => a.currency === "BTC")!.balance, "2");
  } finally {
    Reflect.deleteProperty(ds, "getAccounts");
  }
});
