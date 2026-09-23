/**
 * 처리 대기 화면의 큐 건수 판정 검증.
 *
 * sim 어댑터는 큐 메서드의 권한 검사를 동기로 던진다. 이 차이는 http 어댑터(async 메서드)만
 * 보면 드러나지 않으므로 실제 SimAdminApi의 시드 운영자로 단정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { AdminError } from "../lib/admin/api";
import { QUEUE_COUNT_CAP } from "../lib/admin/paging";
import { loadQueueCounts } from "../lib/admin/queueCounts";
import type { AdminPage } from "../lib/admin/types";

const EMPTY: AdminPage<never> = { data: [], nextCursor: null, hasMore: false };

/**
 * 큐마다 결과를 정해 둔 가짜 어댑터. 성공, 비동기 거절(http 어댑터처럼), 동기 예외(sim 어댑터처럼)를 고른다
 */
type Outcome = { ok: true } | { rejects: unknown } | { throws: unknown };
const OK: Outcome = { ok: true };

function fakeApi(outcomes: { transfers: Outcome; inquiries: Outcome; reports: Outcome }) {
  const answer = (o: Outcome): Promise<AdminPage<never>> => {
    if ("throws" in o) throw o.throws;
    if ("rejects" in o) return Promise.reject(o.rejects);
    return Promise.resolve(EMPTY);
  };
  return {
    getTransfers: () => answer(outcomes.transfers),
    getInquiries: () => answer(outcomes.inquiries),
    getCommunityReports: () => answer(outcomes.reports),
  };
}

test("신고 권한이 없는 운영자도 출금·문의 건수는 본다(sim op-02)", async () => {
  const api = new SimAdminApi();
  // op-02는 VIEWER·BOT_OPS·CHAOS_OPS라 MEMBER_MOD가 없다. sim은 이 거절을 동기로 던진다
  await api.establishSession("op-02");
  assert.throws(() => api.getCommunityReports("PENDING"), (e: unknown) =>
    e instanceof AdminError && e.code === "admin_forbidden");

  const counts = await loadQueueCounts(api);
  assert.deepEqual(counts.reports, { kind: "forbidden" });

  const transfers = await api.getTransfers("REQUESTED", { limit: QUEUE_COUNT_CAP });
  const inquiries = await api.getInquiries("OPEN", { limit: QUEUE_COUNT_CAP });
  assert.deepEqual(counts.transfers, { kind: "ok", loaded: transfers.data.length, hasMore: transfers.hasMore });
  assert.deepEqual(counts.inquiries, { kind: "ok", loaded: inquiries.data.length, hasMore: inquiries.hasMore });
});

test("모든 권한이 있는 운영자는 세 큐를 모두 센다(sim op-01)", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const counts = await loadQueueCounts(api);
  assert.equal(counts.transfers.kind, "ok");
  assert.equal(counts.inquiries.kind, "ok");
  assert.equal(counts.reports.kind, "ok");
});

test("세션이 없으면 큐별 실패로 흡수하지 않고 세션 만료로 올린다", async () => {
  const api = new SimAdminApi();
  await assert.rejects(loadQueueCounts(api), (e: unknown) =>
    e instanceof AdminError && e.code === "jwt_verification");
});

test("한 큐만 세션 만료여도 화면 전체의 세션 만료로 올린다", async () => {
  const api = fakeApi({
    transfers: OK,
    inquiries: { rejects: new AdminError("jwt_verification", "만료") },
    reports: OK,
  });
  await assert.rejects(loadQueueCounts(api), (e: unknown) =>
    e instanceof AdminError && e.code === "jwt_verification");
});

test("한 큐의 읽기 실패는 그 큐만 실패로 적는다", async () => {
  const api = fakeApi({ transfers: { rejects: new Error("network") }, inquiries: OK, reports: OK });
  const counts = await loadQueueCounts(api);
  assert.deepEqual(counts.transfers, { kind: "failed" });
  assert.equal(counts.inquiries.kind, "ok");
  assert.equal(counts.reports.kind, "ok");
});

test("셋 다 권한과 무관하게 실패하면 첫 실패를 화면 전체의 실패로 올린다", async () => {
  const first = new Error("first");
  const api = fakeApi({
    transfers: { rejects: first },
    inquiries: { rejects: new Error("second") },
    reports: { throws: new Error("third") },
  });
  await assert.rejects(loadQueueCounts(api), (e: unknown) => e === first);
});

test("권한 거절이 섞인 전부 실패는 올리지 않고 큐별 사유로 나눠 적는다", async () => {
  const forbidden = new AdminError("admin_forbidden", "권한 부족");
  // 동기 예외도 그 큐 하나의 거절로 받는지 함께 본다
  const api = fakeApi({
    transfers: { rejects: new Error("network") },
    inquiries: { rejects: forbidden },
    reports: { throws: forbidden },
  });
  const counts = await loadQueueCounts(api);
  assert.deepEqual(counts, {
    transfers: { kind: "failed" },
    inquiries: { kind: "forbidden" },
    reports: { kind: "forbidden" },
  });
});
