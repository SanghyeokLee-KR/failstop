/**
 * 커뮤니티 신고 큐 어댑터 계약 검증(API §6-D). sim은 상태 전이·권한·종결 규율을, http는 와이어 매핑을 본다.
 * 대상 요약 4필드는 운영자가 처리 액션을 부르는 유일한 통로라, 매핑이 끊기면 화면이 종결만 가능해진다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";

async function mod(): Promise<SimAdminApi> {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  return api;
}

test("신고 큐 기본은 대기이고 오래된 것부터다", async () => {
  const api = await mod();
  const r = await api.getCommunityReports();
  assert.ok(r.data.length >= 2);
  assert.ok(r.data.every((x) => x.status === "PENDING"));
  for (let i = 1; i < r.data.length; i += 1) {
    assert.ok(r.data[i - 1].createdAt <= r.data[i].createdAt, "큐는 오름차순이다");
  }
});

test("행이 처리에 필요한 대상 식별자와 요약을 싣는다", async () => {
  const api = await mod();
  const row = (await api.getCommunityReports()).data[0];
  // targetId(내부 BIGINT)로는 모더레이션을 못 부른다. external이 유일한 통로다
  assert.ok(row.targetExternalId, "대상 external_id");
  assert.ok(row.targetExcerpt, "대상 원문 요약");
  assert.ok(row.targetStatus, "대상 상태");
});

test("종결은 대기 상태에서만 전이한다", async () => {
  const api = await mod();
  const row = (await api.getCommunityReports()).data[0];
  await api.resolveCommunityReport(row.reportId, "DISMISSED");

  const pending = await api.getCommunityReports("PENDING");
  assert.ok(!pending.data.some((x) => x.reportId === row.reportId), "대기에서 빠진다");
  const dismissed = await api.getCommunityReports("DISMISSED");
  assert.ok(dismissed.data.some((x) => x.reportId === row.reportId), "기각으로 옮겨간다");

  await assert.rejects(
    async () => api.resolveCommunityReport(row.reportId, "ACTIONED"),
    /처리 대기 중인 신고가 아닙니다/,
  );
});

test("숨김은 대상 상태를 바꾸고 재시도가 안전하다", async () => {
  const api = await mod();
  const row = (await api.getCommunityReports()).data.find((r) => r.targetStatus === "VISIBLE");
  assert.ok(row?.targetExternalId);
  await api.moderateCommunityTarget(row.targetType, row.targetExternalId, "HIDE");
  // 같은 상태로 다시 써도 통과해야 부분 실패 뒤 재시도가 성립한다
  await api.moderateCommunityTarget(row.targetType, row.targetExternalId, "HIDE");
  const again = (await api.getCommunityReports()).data.find((r) => r.reportId === row.reportId);
  assert.equal(again?.targetStatus, "HIDDEN");
});

test("삭제는 종료 상태라 되돌아오지 않는다", async () => {
  const api = await mod();
  const row = (await api.getCommunityReports()).data.find((r) => r.targetStatus === "VISIBLE");
  assert.ok(row?.targetExternalId);
  await api.moderateCommunityTarget(row.targetType, row.targetExternalId, "DELETE");
  await assert.rejects(
    async () => api.moderateCommunityTarget(row.targetType, row.targetExternalId!, "UNHIDE"),
    /이미 삭제된 대상/,
  );
});

test("권한 없는 세션은 신고를 처리하지 못한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  api.endSession();
  await assert.rejects(async () => api.getCommunityReports(), /세션 없음/);
});

test("처리는 감사에 남는다", async () => {
  const api = await mod();
  const row = (await api.getCommunityReports()).data[0];
  await api.resolveCommunityReport(row.reportId, "REVIEWED");
  const audit = await api.getAudit({ limit: 20 });
  assert.ok(audit.data.some((a) => a.action === "community.report.resolve"));
});

/* ===== http 어댑터 ===== */

function withFetch(res: unknown, run: () => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  globalThis.fetch = (async () => res) as typeof fetch;
  return run().finally(() => {
    globalThis.fetch = prev;
  });
}

function okRes(body: unknown) {
  return { ok: true, status: 200, headers: { get: () => null }, json: async () => body } as unknown as Response;
}

test("http 어댑터가 신고 행을 뷰모델로 옮긴다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    okRes({
      schema_version: "1.0",
      data: [{
        report_id: 41, target_type: "POST", target_id: 1207,
        target_external_id: "5c1b-uuid", target_status: "HIDDEN",
        target_excerpt: "요약", target_author: "글쓴이",
        reporter_id: 88, reason: "SPAM", detail: "사유", status: "PENDING",
        created_at: "2026-08-29T00:10:00Z",
      }],
      pagination: { next_cursor: "c2", has_more: true },
    }),
    async () => {
      const r = await api.getCommunityReports();
      const row = r.data[0];
      assert.equal(row.reportId, 41);
      assert.equal(row.targetExternalId, "5c1b-uuid");
      assert.equal(row.targetStatus, "HIDDEN");
      assert.equal(row.targetExcerpt, "요약");
      assert.equal(row.reason, "SPAM");
      assert.equal(r.nextCursor, "c2");
      assert.equal(r.hasMore, true);
    },
  );
});

test("대상 조인 실패는 null로 흘리고 어휘 밖 값은 좁힌다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    okRes({
      data: [{
        report_id: 9, target_type: "COMMENT", target_id: 1,
        target_external_id: null, target_status: null, target_excerpt: null, target_author: null,
        reporter_id: 2, reason: "훼손됨", status: "훼손됨", created_at: "",
      }],
    }),
    async () => {
      const row = (await api.getCommunityReports()).data[0];
      assert.equal(row.targetExternalId, null);
      assert.equal(row.targetStatus, null);
      // CHECK 밖 어휘는 화면이 깨지지 않게 좁힌다
      assert.equal(row.reason, "ETC");
      assert.equal(row.status, "PENDING");
      assert.equal(row.createdAt, 0);
    },
  );
});

test("모더레이션 경로가 대상 유형으로 갈린다", async () => {
  const api = new HttpAdminApi("");
  const seen: string[] = [];
  const prev = globalThis.fetch;
  globalThis.fetch = (async (url: unknown) => {
    seen.push(String(url));
    return okRes({});
  }) as typeof fetch;
  try {
    await api.moderateCommunityTarget("POST", "p-1", "HIDE");
    await api.moderateCommunityTarget("COMMENT", "c-1", "DELETE");
    await api.resolveCommunityReport(7, "ACTIONED");
  } finally {
    globalThis.fetch = prev;
  }
  assert.ok(seen[0].includes("/community/posts/p-1/moderate"), seen[0]);
  assert.ok(seen[1].includes("/community/comments/c-1/moderate"), seen[1]);
  assert.ok(seen[2].includes("/community/reports/7/resolve"), seen[2]);
});

test("세션 없는 http 어댑터도 AdminError로 실패한다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    { ok: false, status: 403, headers: { get: () => null }, json: async () => ({ error: { name: "admin_forbidden", message: "권한 부족" } }) } as unknown as Response,
    async () => {
      await assert.rejects(
        api.getCommunityReports(),
        (e: unknown) => e instanceof AdminError && e.code === "admin_forbidden",
      );
    },
  );
});
