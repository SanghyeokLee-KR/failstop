/**
 * tests/assetVersions.test.ts. myAsset 잔고 판 판정 규칙(ADR 0034 S6, 설계 4-13 표)과 계좌 조회 병합 검증.
 *
 * version 은 그 사용자·자산의 AVAILABLE·LOCKED 잔고 행 version 의 합이다. DB 타임라인 안에서 줄지 않고, 같으면 잔고와
 * 잠금이 같다. 수동 승격이나 복원 뒤에는 줄 수 있으므로 작은 판은 버리지 않고 재조회로 맞춘다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { judgeAssetUpdate, mergeAccounts } from "../lib/api/assetVersions";
import type { Asset } from "../lib/types";

function asset(currency: string, balance: string, version?: number): Asset {
  return {
    currency,
    balance,
    locked: "0",
    avg_buy_price: "0",
    unit_currency: "KRW",
    cost_basis_qty: "0",
    cost_basis_amount: "0",
    ...(version === undefined ? {} : { version }),
  };
}

test("판정 표 일곱 줄", () => {
  const rows: Array<{ name: string; incoming: number | undefined; current: Asset | undefined; apply: boolean; refetch: boolean }> = [
    { name: "둘 다 판 없음(종전 서버)", incoming: undefined, current: asset("KRW", "1"), apply: true, refetch: false },
    { name: "판 있음, 그 통화 없음", incoming: 3, current: undefined, apply: true, refetch: false },
    { name: "판 있음, 가진 값에 판 없음", incoming: 3, current: asset("KRW", "1"), apply: true, refetch: true },
    { name: "판 없음, 가진 값에 판 있음", incoming: undefined, current: asset("KRW", "1", 3), apply: false, refetch: true },
    { name: "더 큼", incoming: 4, current: asset("KRW", "1", 3), apply: true, refetch: false },
    { name: "같음", incoming: 3, current: asset("KRW", "1", 3), apply: true, refetch: false },
    { name: "더 작음", incoming: 2, current: asset("KRW", "1", 3), apply: false, refetch: true },
  ];
  for (const r of rows) {
    assert.deepEqual(judgeAssetUpdate(r.incoming, r.current), { apply: r.apply, refetch: r.refetch }, r.name);
  }
  assert.equal(rows.length, 7);
  // 표 밖의 조합: 판 없는 항목이 처음 보는 통화면 종전처럼 더한다
  assert.deepEqual(judgeAssetUpdate(undefined, undefined), { apply: true, refetch: false });
});

test("계좌 조회 병합: 응답이 지금 값 이상이면 적용한다", () => {
  const now = [asset("KRW", "1000", 10)];
  const merged = mergeAccounts(now, [asset("KRW", "900", 11)], new Map([["KRW", 10]]));
  assert.deepEqual(merged, [asset("KRW", "900", 11)]);
  const same = mergeAccounts(now, [asset("KRW", "1000", 10)], new Map([["KRW", 10]]));
  assert.deepEqual(same, [asset("KRW", "1000", 10)]);
});

test("계좌 조회 병합: 요청 뒤 더 새 실시간이 온 통화는 지금 값을 둔다", () => {
  // 요청 때 10, 왕복 중 실시간이 12 를 적용했다. 응답 11 은 요청 뒤의 값이지만 지금 값보다 오래됐다
  const now = [asset("KRW", "700", 12), asset("BTC", "1", 4)];
  const merged = mergeAccounts(now, [asset("KRW", "800", 11), asset("BTC", "2", 5)], new Map([
    ["KRW", 10],
    ["BTC", 4],
  ]));
  assert.deepEqual(merged, [asset("KRW", "700", 12), asset("BTC", "2", 5)]);
});

test("계좌 조회 병합: 요청 때 값보다도 작은 응답은 DB 가 되돌아간 것이라 적용한다", () => {
  const now = [asset("KRW", "700", 12)];
  const merged = mergeAccounts(now, [asset("KRW", "500", 7)], new Map([["KRW", 10]]));
  assert.deepEqual(merged, [asset("KRW", "500", 7)]);
});

test("계좌 조회 병합: 판 없는 응답은 종전처럼 통째로 바꾼다", () => {
  const now = [asset("KRW", "700", 12), asset("BTC", "1", 4)];
  const fetched = [asset("KRW", "500")];
  assert.deepEqual(mergeAccounts(now, fetched, new Map([["KRW", 10]])), fetched);
});

test("계좌 조회 병합: 요청 뒤 실시간으로 생긴 통화는 응답에 없어도 남긴다", () => {
  // 첫 매수 체결이 새 통화를 만들었는데 응답은 그 전에 읽혔다
  const now = [asset("KRW", "700", 12), asset("ETH", "3", 2)];
  const merged = mergeAccounts(now, [asset("KRW", "700", 12)], new Map([["KRW", 12]]));
  assert.deepEqual(merged, [asset("KRW", "700", 12), asset("ETH", "3", 2)]);
  // 요청 때부터 있던 통화가 응답에 없으면 응답이 권위다
  const gone = mergeAccounts(now, [asset("KRW", "700", 12)], new Map([
    ["KRW", 12],
    ["ETH", 2],
  ]));
  assert.deepEqual(gone, [asset("KRW", "700", 12)]);
});
