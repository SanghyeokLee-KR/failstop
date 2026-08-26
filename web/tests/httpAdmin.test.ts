/**
 * tests/httpAdmin.test.ts. HttpAdminApi 에러 매핑 단위 검증.
 * 관리 평면 429(too_many_requests) 응답을 명시 매핑하는지 확인한다(감사 rank10).
 * 실제 네트워크 없이 global fetch를 스텁한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";
import type { ApprovalPayload, IssuedApproval } from "../lib/admin/types";

interface FakeResInit {
  status: number;
  body?: unknown;
  retryAfterHeader?: string | null;
}

function fakeRes(init: FakeResInit): Response {
  const headers = { get: (name: string) => (name === "Retry-After" ? init.retryAfterHeader ?? null : null) };
  return {
    ok: init.status >= 200 && init.status < 300,
    status: init.status,
    headers,
    json: async () => {
      if (init.body === undefined) throw new SyntaxError("non-json");
      return init.body;
    },
  } as unknown as Response;
}

function withFetch(res: Response, run: () => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  globalThis.fetch = (async () => res) as typeof fetch;
  return run().finally(() => {
    globalThis.fetch = prev;
  });
}

async function expectAdminError(run: () => Promise<unknown>): Promise<AdminError> {
  try {
    await run();
  } catch (e) {
    assert.ok(e instanceof AdminError, "AdminError로 표면화");
    return e;
  }
  throw new assert.AssertionError({ message: "AdminError가 던져지지 않았다" });
}

test("관리 429: 바디 retry_after를 too_many_requests로 매핑하고 대기 초를 안내한다", async () => {
  const res = fakeRes({ status: 429, body: { error: { name: "too_many_requests", retry_after: 7 } } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStart());
    assert.equal(e.code, "too_many_requests");
    assert.match(e.message, /7초 후/);
  });
});

test("관리 429: 바디 retry_after 부재 시 Retry-After 헤더 초를 안내한다", async () => {
  const res = fakeRes({ status: 429, body: { error: { name: "too_many_requests" } }, retryAfterHeader: "5" });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStop());
    assert.equal(e.code, "too_many_requests");
    assert.match(e.message, /5초 후/);
  });
});

test("관리 429: 대기 초 미상이면 잠시 후로 열화한다", async () => {
  const res = fakeRes({ status: 429 });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simPause());
    assert.equal(e.code, "too_many_requests");
    assert.match(e.message, /잠시 후/);
  });
});

test("control 1.1: applied false는 미반영 에러로 승격한다(성공 응답 삼킴 방지)", async () => {
  const res = fakeRes({ status: 200, body: { schema_version: "1.1", command: "start", applied: false } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStart());
    assert.equal(e.code, "control_not_applied");
  });
});

test("control 1.0: applied 부재는 구 서버 하위호환으로 통과한다", async () => {
  const res = fakeRes({ status: 200, body: { schema_version: "1.0", command: "start" } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    await api.simStart();
  });
});

test("관리 403: forbidden 코드로 매핑하고 429 안내를 붙이지 않는다", async () => {
  const res = fakeRes({ status: 403, body: { error: { name: "admin_forbidden", message: "권한이 없습니다" } } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStart());
    assert.equal(e.code, "admin_forbidden");
    assert.doesNotMatch(e.message, /초 후|잠시 후/);
  });
});

/* ===== 승인 바인딩 정합(정본 API §3-6 확정표 v2.13) ===== */
// wireBinding은 모듈 사설이라 공개 표면(발급·소각)의 요청 바디를 fetch 스텁으로 캡처해 대조한다.
// 발급 바디 target으로 wireBinding target을, 소각 바디로 파괴 액션 바디 필드를 검증한다.

interface CapturedCall {
  url: string;
  method: string;
  body: Record<string, unknown> | undefined;
}

function withCapture(run: (calls: CapturedCall[], api: HttpAdminApi) => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  const calls: CapturedCall[] = [];
  globalThis.fetch = (async (input: unknown, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({
      url,
      method: init.method ?? "GET",
      body: init.body != null ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined,
    });
    // 세션·발급 응답만 최소 필드를 채우고 나머지는 빈 객체로 언랩 통과시킨다
    let payload: unknown = {};
    if (url.endsWith("/admin/v1/session")) {
      payload = {
        operator_id: "op-approver",
        display_name: "승인자",
        roles: ["SUPER"],
        token: "sess",
        expires_at: new Date(Date.now() + 3_600_000).toISOString(),
      };
    } else if (url.endsWith("/admin/v1/approvals")) {
      payload = { approval_id: 42, approval_token: "apv-issued", expires_at: new Date(Date.now() + 300_000).toISOString() };
    }
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => payload,
    } as unknown as Response;
  }) as typeof fetch;
  const api = new HttpAdminApi("http://admin.test");
  return run(calls, api).finally(() => {
    globalThis.fetch = prev;
  });
}

function issued(payload: ApprovalPayload, token = "apv-burn"): IssuedApproval {
  return {
    id: "1",
    action: payload.action,
    targetKey: "",
    targetLabel: "",
    requesterId: "",
    requesterName: "",
    approverId: "",
    approverName: "",
    state: "ISSUED",
    issuedAt: "",
    expiresAt: "",
    consumedAt: null,
    payload,
    token,
  };
}

test("발급 바인딩: 자원·전이 스코프 target을 v2.13 표대로 approvals 바디에 싣는다", async () => {
  const cases: { payload: ApprovalPayload; action: string; target: string }[] = [
    { payload: { action: "chaos.inject", experimentId: "E4" }, action: "chaos.kill_node", target: "exchange-core-1" },
    { payload: { action: "chaos.inject", experimentId: "E1" }, action: "chaos.network", target: "e1-toxic" },
    { payload: { action: "chaos.inject", experimentId: "E8" }, action: "chaos.app", target: "e8-assault" },
    { payload: { action: "chaos.clear", runId: "e1-toxic" }, action: "chaos.network.clear", target: "e1-toxic" },
    { payload: { action: "config.market.halt", symbol: "KRW-BTC" }, action: "config.market.halt", target: "KRW-BTC" },
    { payload: { action: "config.market.fees", symbol: "KRW-BTC", makerFee: "0.0005", takerFee: "0.0005" }, action: "config.market.fees", target: "KRW-BTC" },
    { payload: { action: "sim.bots.params", botId: "bot-17", params: { spread: "0.01" } }, action: "sim.bots.params", target: "bot-17" },
    { payload: { action: "operators.create", operatorId: "op-07", displayName: "신규" }, action: "operators.create", target: "op-07" },
    { payload: { action: "operators.status", operatorId: "op-07", status: "DISABLED" }, action: "operators.status", target: "op-07:DISABLED" },
    { payload: { action: "operators.roles", operatorId: "op-07", grant: "GRANT", role: "SUPER" }, action: "operators.roles", target: "op-07:GRANT:SUPER" },
  ];
  for (const c of cases) {
    await withCapture(async (calls, api) => {
      await api.establishSession();
      await api.issueApproval("req-op", c.payload.action, c.payload);
      const call = calls[calls.length - 1];
      assert.ok(call.url.endsWith("/admin/v1/approvals"), `발급 경로: ${call.url}`);
      assert.equal(call.method, "POST");
      assert.equal(call.body?.action, c.action, `${c.payload.action} 바인딩 action`);
      assert.equal(call.body?.target, c.target, `${c.payload.action} 바인딩 target`);
      assert.equal(call.body?.requester_operator_id, "req-op");
    });
  }
});

test("소각 바디: 파괴 액션별 바디 필드를 v2.13 표대로 싣는다", async () => {
  const cases: { payload: ApprovalPayload; method: string; path: string; fields: Record<string, unknown> }[] = [
    { payload: { action: "chaos.inject", experimentId: "E4" }, method: "POST", path: "/admin/v1/chaos/kill-node", fields: { node: "exchange-core-1", method: "docker_kill", expect_failover: true } },
    { payload: { action: "chaos.inject", experimentId: "E1" }, method: "POST", path: "/admin/v1/chaos/network", fields: { name: "e1-toxic", type: "latency" } },
    { payload: { action: "chaos.inject", experimentId: "E8" }, method: "POST", path: "/admin/v1/chaos/app", fields: { assault: "e8-assault" } },
    { payload: { action: "chaos.clear", runId: "e1-toxic" }, method: "DELETE", path: "/admin/v1/chaos/network/e1-toxic", fields: {} },
    { payload: { action: "operators.create", operatorId: "op-07", displayName: "신규" }, method: "POST", path: "/admin/v1/operators", fields: { new_operator_id: "op-07", display_name: "신규" } },
    { payload: { action: "operators.status", operatorId: "op-07", status: "DISABLED" }, method: "PATCH", path: "/admin/v1/operators/op-07/status", fields: { status: "DISABLED" } },
    { payload: { action: "operators.roles", operatorId: "op-07", grant: "GRANT", role: "SUPER" }, method: "POST", path: "/admin/v1/operators/op-07/roles", fields: { action: "GRANT", role: "SUPER" } },
  ];
  for (const c of cases) {
    await withCapture(async (calls, api) => {
      await api.establishSession();
      await api.consumeApproval(issued(c.payload, "apv-burn"));
      const call = calls[calls.length - 1];
      assert.ok(call.url.endsWith(c.path), `소각 경로: ${call.url} != ${c.path}`);
      assert.equal(call.method, c.method, `${c.payload.action} 메서드`);
      // 공통 필드: 수행자 operator_id(세션 유래)와 approval_token
      assert.equal(call.body?.operator_id, "op-approver");
      assert.equal(call.body?.approval_token, "apv-burn");
      // 표 고유 필드
      for (const [k, v] of Object.entries(c.fields)) {
        assert.deepEqual(call.body?.[k], v, `${c.payload.action} 바디 ${k}`);
      }
    });
  }
});
