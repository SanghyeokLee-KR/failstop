/**
 * 시연 화면 리더 리스 표의 행(S4 설계 4-17). 표는 이 행을 그대로 그린다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { leaseRows } from "../lib/admin/leases";

test("리스 행: 보유 노드는 holder 앞 8자이고 남은 시간은 서버 값만 적는다", () => {
  const rows = leaseRows([
    { resource: "matching-p0", holder: "0f3c2a9b-1111-2222", epoch: 21, leaseUntil: null, leaderAlive: true,
      leaseRemainingMs: 8400, markets: ["KRW-BTC", "KRW-XRP"] },
    { resource: "matching-p1", holder: "", epoch: 22, leaseUntil: null, leaderAlive: false,
      leaseRemainingMs: 0, markets: ["KRW-ETH"] },
    { resource: "matching-engine", holder: "engine-01", epoch: 7, leaseUntil: null, leaderAlive: true,
      leaseRemainingMs: null, markets: [] },
  ]);
  assert.deepEqual(rows, [
    { resource: "matching-p0", holder: "0f3c2a9b", epoch: 21, remaining: "8.4초", marketCount: 2, alive: true },
    { resource: "matching-p1", holder: "없음", epoch: 22, remaining: "만료", marketCount: 1, alive: false },
    { resource: "matching-engine", holder: "engine-0", epoch: 7, remaining: "미측정", marketCount: 0, alive: true },
  ]);
  assert.deepEqual(leaseRows(undefined), []);
});
