/**
 * 회원 운영 어댑터 계약 검증(API §6-E). 핵심은 정지와 폐쇄의 부수 효과 차이다.
 * 정지는 가역이라 미체결 주문을 남기고 세션만 끊고, 폐쇄는 비가역이라 주문까지 정리한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";
import { ACTION_CAP, approvalConsoleTarget } from "../lib/admin/roles";
import { describeCloseIncomplete } from "../lib/admin/memberClose";

const TRADER = "11111111-1111-4111-8111-111111111111";

async function mod(): Promise<SimAdminApi> {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  return api;
}

test("목록은 가입 역순이고 상태로 거른다", async () => {
  const api = await mod();
  const all = await api.getMembers();
  assert.ok(all.data.length >= 3);
  for (let i = 1; i < all.data.length; i += 1) {
    assert.ok(all.data[i - 1].createdAt >= all.data[i].createdAt, "가입 역순");
  }
  const suspended = await api.getMembers("SUSPENDED");
  assert.ok(suspended.data.every((m) => m.status === "SUSPENDED"));
  assert.ok(suspended.data.length < all.data.length);
});

test("상세가 폐쇄 판단에 필요한 활동 요약을 싣는다", async () => {
  const api = await mod();
  const d = await api.getMember(TRADER);
  assert.equal(d.email, "trader@demo.local");
  // 이 값이 없으면 무엇이 함께 취소되는지 모른 채 폐쇄를 누르게 된다
  assert.ok(d.openOrderCount > 0);
  assert.ok(d.assetKinds > 0);
  assert.ok(d.updatedAt > 0);
});

test("없는 회원은 not_found다", async () => {
  const api = await mod();
  await assert.rejects(
    async () => api.getMember("00000000-0000-4000-8000-000000000000"),
    (e: unknown) => e instanceof AdminError && e.code === "not_found",
  );
});

test("정지는 세션만 끊고 미체결 주문을 남긴다", async () => {
  const api = await mod();
  const before = await api.getMember(TRADER);
  const t = await api.suspendMember(TRADER);
  assert.equal(t.status, "SUSPENDED");
  assert.ok(t.revokedSessions > 0, "세션은 끊는다");
  // 가역 조치라 주문을 건드리지 않는다. 취소하면 복구해도 원상태가 아니다
  assert.equal(t.canceledOrders, 0);
  assert.equal((await api.getMember(TRADER)).openOrderCount, before.openOrderCount);
});

test("복구는 상태만 되돌리고 세션은 되살리지 않는다", async () => {
  const api = await mod();
  await api.suspendMember(TRADER);
  const t = await api.reinstateMember(TRADER);
  assert.equal(t.status, "ACTIVE");
  assert.equal(t.revokedSessions, 0, "복구는 세션을 만들지 않는다");
  assert.equal((await api.getMember(TRADER)).status, "ACTIVE");
});

test("폐쇄는 승인 소각 경로로만 실행되고 종결 상태다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  const issued = await api.issueApproval("op-01", "members.close", {
    action: "members.close", externalId: TRADER, email: "trader@demo.local",
  });
  await api.establishSession("op-01");
  await api.consumeApproval(issued);

  const after = await api.getMember(TRADER);
  assert.equal(after.status, "CLOSED");
  // 비가역이라 남은 주문이 체결되면 폐쇄 계정에 체결이 생긴다. 전량 취소한다
  assert.equal(after.openOrderCount, 0);

  await assert.rejects(
    async () => api.reinstateMember(TRADER),
    (e: unknown) => e instanceof AdminError && e.code === "member_closed",
  );
});

/*
 * 정지 마켓 미체결이 남은 폐쇄(API 설계 v2.54 §6-E, ADR 0034 결정 15). 폐쇄는 확정되고 409 orders_cancel_incomplete 가
 * 남은 수와 사유별 수를 싣는다. 정지 마켓 포함은 AI 가 고른 방침이고 사용자 확인이 필요하다.
 */
test("정지 마켓 미체결이 남은 폐쇄는 CLOSED 로 확정하고 409 로 사유별 수를 알린다", async () => {
  const api = await mod();
  const halt = await api.issueApproval("op-01", "config.market.halt", { action: "config.market.halt", symbol: "KRW-ETH" });
  await api.consumeApproval(halt);
  const close = await api.issueApproval("op-01", "members.close", {
    action: "members.close", externalId: TRADER, email: "trader@demo.local",
  });

  let raised: AdminError | null = null;
  try {
    await api.consumeApproval(close);
  } catch (e) {
    raised = e as AdminError;
  }

  assert.ok(raised instanceof AdminError, "409 가 나지 않았다");
  assert.equal(raised.code, "orders_cancel_incomplete");
  assert.equal(raised.detail?.pending_orders, 1);
  assert.equal(raised.detail?.canceled_orders, 2);
  assert.deepEqual(raised.detail?.pending_reasons, { market_suspended: 1 });
  assert.equal(raised.detail?.approval_id, close.id);
  assert.match(raised.message, /폐쇄 확정, 미완료 1건\(정지 마켓 1건\)/);
  const after = await api.getMember(TRADER);
  assert.equal(after.status, "CLOSED", "폐쇄는 확정이다");
  assert.equal(after.openOrderCount, 1, "정지 마켓 주문은 남는다");
  // 서버처럼 토큰은 이미 소각됐다. 같은 토큰으로 다시 부를 수 없다
  await assert.rejects(async () => api.consumeApproval(close),
    (e: unknown) => e instanceof AdminError && e.code === "admin_forbidden");
});

test("미완료 문구는 사유별 수와 다음 조치를 싣는다", () => {
  assert.equal(
    describeCloseIncomplete({ pending_orders: 3, pending_reasons: { service_unavailable: 1, market_suspended: 2 } }),
    "폐쇄 확정, 미완료 3건(일시 거부 1건, 정지 마켓 2건). 정지 마켓을 재개한 뒤 새 승인으로 폐쇄 재요청 필요",
  );
  assert.equal(
    describeCloseIncomplete({ pending_orders: 1, pending_reasons: { service_unavailable: 1 } }),
    "폐쇄 확정, 미완료 1건(일시 거부 1건). 새 승인으로 폐쇄 재요청 필요",
  );
  // 마켓 접수 경합(API 설계 v2.55 §2-5-b)은 한글 이름으로 적고 서버와 같은 순서에 둔다. 원시 코드가 보이면 안 된다
  assert.equal(
    describeCloseIncomplete({
      pending_orders: 4,
      pending_reasons: { market_suspended: 1, too_many_requests: 2, service_unavailable: 1 },
    }),
    "폐쇄 확정, 미완료 4건(일시 거부 1건, 접수 혼잡 2건, 정지 마켓 1건). 정지 마켓을 재개한 뒤 새 승인으로 폐쇄 재요청 필요",
  );
  assert.equal(
    describeCloseIncomplete({ pending_orders: 2, pending_reasons: { too_many_requests: 2 } }),
    "폐쇄 확정, 미완료 2건(접수 혼잡 2건). 새 승인으로 폐쇄 재요청 필요",
  );
  // 사유가 없거나 0 이면 합만 보인다
  assert.equal(describeCloseIncomplete({ pending_orders: 2, pending_reasons: { market_suspended: 0 } }),
    "폐쇄 확정, 미완료 2건. 새 승인으로 폐쇄 재요청 필요");
  assert.equal(describeCloseIncomplete(null), "폐쇄 확정, 미완료 0건. 새 승인으로 폐쇄 재요청 필요");
});

test("http 어댑터는 폐쇄 409 바디의 detail 을 AdminError 에 싣는다", async () => {
  const api = new HttpAdminApi("http://admin.test");
  const prev = globalThis.fetch;
  const detail = {
    status: "CLOSED", canceled_orders: 2, pending_orders: 1,
    pending_reasons: { market_suspended: 1 }, approval_id: 77,
  };
  globalThis.fetch = (async (url: unknown) => {
    if (String(url).endsWith("/admin/v1/session")) {
      return okRes({
        operator_id: "op-mod", display_name: "운영", roles: ["SUPER"], token: "sess",
        expires_at: new Date(Date.now() + 3_600_000).toISOString(),
      });
    }
    return {
      ok: false, status: 409, headers: { get: () => null },
      json: async () => ({ error: { name: "orders_cancel_incomplete", message: "폐쇄는 확정됐으나", detail,
                                    trace_id: null, request_id: "r-1", retry_after: null } }),
    } as unknown as Response;
  }) as typeof fetch;
  try {
    await api.establishSession();
    await assert.rejects(
      async () => api.consumeApproval({
        id: "77", action: "members.close", targetKey: "", targetLabel: "", requesterId: "op-mod",
        requesterName: "", approverId: "op-mod", approverName: "", state: "ISSUED", issuedAt: "", expiresAt: "",
        consumedAt: null,
        payload: { action: "members.close", externalId: "e-1", email: "e@x" }, token: "apv-1",
      }),
      (e: unknown) => e instanceof AdminError && e.code === "orders_cancel_incomplete"
        && (e.detail?.pending_reasons as Record<string, number>).market_suspended === 1
        && e.detail?.pending_orders === 1,
    );
  } finally {
    globalThis.fetch = prev;
  }
});

test("폐쇄 승인은 요청자와 발급자가 같아도 발급된다(1인 승인)", async () => {
  const api = await mod();
  const issued = await api.issueApproval("op-01", "members.close", {
    action: "members.close", externalId: TRADER, email: "trader@demo.local",
  });
  // ADR 0019. 서버 ApprovalService.issue에도 동일성 가드가 없어 sim이 같은 계약을 따른다
  assert.equal(issued.requesterId, "op-01");
  assert.equal(issued.approverId, "op-01");
});

test("폐쇄는 MEMBER_MOD를 요구하고 승인 키는 회원별로 갈린다", () => {
  assert.equal(ACTION_CAP["members.close"], "MEMBER_MOD");
  const a = approvalConsoleTarget({ action: "members.close", externalId: "aaa", email: "a@x" });
  const b = approvalConsoleTarget({ action: "members.close", externalId: "bbb", email: "b@x" });
  assert.notEqual(a.key, b.key, "회원이 다르면 승인 키도 달라야 한다");
  // UUID는 눈으로 못 읽는다. 운영자가 보는 라벨은 이메일이다
  assert.ok(a.label.includes("a@x"));
});

test("권한 없는 세션은 회원을 전이하지 못한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  api.endSession();
  await assert.rejects(async () => api.suspendMember(TRADER), /세션 없음/);
});

/* ===== http 어댑터 ===== */

function okRes(body: unknown) {
  return { ok: true, status: 200, headers: { get: () => null }, json: async () => body } as unknown as Response;
}

test("http 어댑터가 회원 행과 상세를 옮긴다", async () => {
  const api = new HttpAdminApi("");
  const prev = globalThis.fetch;
  globalThis.fetch = (async (url: unknown) =>
    String(url).includes("/members/")
      ? okRes({
          external_id: "e-1", email: "a@demo.local", display_name: "표시명", status: "SUSPENDED",
          created_at: "2026-08-01T00:00:00Z", updated_at: "2026-08-20T00:00:00Z",
          open_order_count: 2, last_order_at: "2026-08-19T00:00:00Z", asset_kinds: 3,
        })
      : okRes({
          data: [{ external_id: "e-1", email: "a@demo.local", display_name: null,
                   status: "훼손됨", created_at: "2026-08-01T00:00:00Z" }],
          pagination: { next_cursor: null, has_more: false },
        })) as typeof fetch;
  try {
    const list = await api.getMembers();
    // CHECK 밖 어휘는 화면이 깨지지 않게 좁힌다
    assert.equal(list.data[0].status, "ACTIVE");
    assert.equal(list.data[0].displayName, null);

    const d = await api.getMember("e-1");
    assert.equal(d.status, "SUSPENDED");
    assert.equal(d.openOrderCount, 2);
    assert.equal(d.assetKinds, 3);
    assert.ok(d.lastOrderAt && d.lastOrderAt > 0);
  } finally {
    globalThis.fetch = prev;
  }
});

test("전이 경로가 동사로 갈리고 폐쇄는 승인 경로를 탄다", async () => {
  const api = new HttpAdminApi("");
  const seen: string[] = [];
  const prev = globalThis.fetch;
  globalThis.fetch = (async (url: unknown) => {
    seen.push(String(url));
    return okRes({ external_id: "e-1", status: "SUSPENDED", canceled_orders: 0, revoked_sessions: 1 });
  }) as typeof fetch;
  try {
    await api.suspendMember("e-1");
    await api.reinstateMember("e-1");
  } finally {
    globalThis.fetch = prev;
  }
  assert.ok(seen[0].endsWith("/members/e-1/suspend"), seen[0]);
  assert.ok(seen[1].endsWith("/members/e-1/reinstate"), seen[1]);
  // 폐쇄는 별도 메서드가 없다. consumeApproval이 토큰과 함께 부른다
  assert.equal(typeof (api as unknown as Record<string, unknown>).closeMember, "undefined");
});
