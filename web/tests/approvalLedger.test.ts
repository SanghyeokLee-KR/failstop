/**
 * 승인 원장 단일 폴러. 구독자 수로 켜고 끄는지, 조작 뒤 즉시 조회가 새 원장 반영까지
 * 기다리는지, 실패를 빈 목록으로 흡수하지 않는지 본다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { approvalLedgerStore } from "../lib/admin/approvals";
import { getAdminApi } from "../lib/admin/api";
import type { AdminPage, ApprovalRequest } from "../lib/admin/types";

type List = () => Promise<AdminPage<ApprovalRequest>>;

function row(id: string): ApprovalRequest {
  return {
    id,
    action: "config.market.halt",
    targetKey: `config.market.halt:${id}`,
    targetLabel: "KRW-BTC",
    requesterId: "op-01",
    requesterName: "op-01",
    approverId: "op-01",
    approverName: "op-01",
    state: "ISSUED",
    issuedAt: "2026-09-23T00:00:00.000Z",
    expiresAt: "2026-09-23T00:05:00.000Z",
    consumedAt: null,
  };
}

const page = (...ids: string[]): AdminPage<ApprovalRequest> => ({ data: ids.map(row), nextCursor: null, hasMore: false });

/** 폴러는 부를 때마다 getAdminApi()를 읽는다. 싱글턴의 원장 조회만 바꿔 끼우고 상태를 처음으로 되돌린다 */
async function withLedger(list: List, run: () => Promise<void>): Promise<void> {
  const api = getAdminApi() as unknown as { listApprovals: List };
  const prev = api.listApprovals;
  api.listApprovals = list;
  approvalLedgerStore.setState({ rows: [], hasMore: false, lastAt: null, loading: true, failed: false, seq: 0 });
  try {
    await run();
  } finally {
    // 남은 타이머가 있으면 노드 프로세스가 끝나지 않는다
    while (approvalLedgerStore.getState().subscribers > 0) approvalLedgerStore.getState().unsubscribe();
    api.listApprovals = prev;
  }
}

const settle = () => new Promise((r) => setImmediate(r));

test("구독자가 여럿이어도 진입 조회는 한 번이고 마지막 구독이 풀리면 타이머가 멈춘다", async () => {
  let calls = 0;
  await withLedger(
    async () => {
      calls += 1;
      return page("a");
    },
    async () => {
      const ledger = approvalLedgerStore.getState();
      ledger.subscribe();
      ledger.subscribe();
      await settle();
      assert.equal(calls, 1);
      assert.notEqual(approvalLedgerStore.getState().timer, null);
      ledger.unsubscribe();
      assert.notEqual(approvalLedgerStore.getState().timer, null, "남은 구독자가 있으면 계속 돈다");
      ledger.unsubscribe();
      assert.equal(approvalLedgerStore.getState().timer, null);
      assert.equal(approvalLedgerStore.getState().subscribers, 0);
    },
  );
});

test("invalidate는 새 원장이 반영된 뒤에 풀린다", async () => {
  await withLedger(
    () => new Promise((r) => setTimeout(() => r(page("a", "b")), 20)),
    async () => {
      await approvalLedgerStore.getState().invalidate();
      // 포커스 폴백이 이 순서에 기댄다. 풀린 시점에 행이 이미 바뀌어 있어야 한다
      assert.deepEqual(
        approvalLedgerStore.getState().rows.map((r) => r.id),
        ["a", "b"],
      );
      assert.equal(approvalLedgerStore.getState().loading, false);
    },
  );
});

test("한 번도 받지 못한 채 실패하면 빈 목록이 아니라 실패로 남는다", async () => {
  await withLedger(
    async () => {
      throw new Error("down");
    },
    async () => {
      await approvalLedgerStore.getState().invalidate();
      const s = approvalLedgerStore.getState();
      assert.equal(s.loading, false);
      assert.equal(s.failed, true);
      assert.equal(s.lastAt, null, "받은 적이 없다는 사실이 남아야 화면이 '없음'을 단정하지 않는다");
    },
  );
});

test("받은 뒤의 실패는 마지막 목록을 두고 실패만 세운다", async () => {
  let fail = false;
  await withLedger(
    async () => {
      if (fail) throw new Error("down");
      return page("a");
    },
    async () => {
      await approvalLedgerStore.getState().invalidate();
      const at = approvalLedgerStore.getState().lastAt;
      fail = true;
      await approvalLedgerStore.getState().invalidate();
      const s = approvalLedgerStore.getState();
      assert.equal(s.failed, true);
      assert.deepEqual(s.rows.map((r) => r.id), ["a"]);
      assert.equal(s.lastAt, at);
    },
  );
});

test("늦게 도착한 옛 응답은 새 원장을 덮지 않는다", async () => {
  let n = 0;
  await withLedger(
    () => {
      n += 1;
      // 첫 요청이 두 번째보다 늦게 도착한다
      return n === 1
        ? new Promise((r) => setTimeout(() => r(page("old")), 30))
        : Promise.resolve(page("new"));
    },
    async () => {
      const ledger = approvalLedgerStore.getState();
      const first = ledger.invalidate();
      await ledger.invalidate();
      await first;
      assert.deepEqual(approvalLedgerStore.getState().rows.map((r) => r.id), ["new"]);
    },
  );
});
