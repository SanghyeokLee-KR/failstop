/**
 * tests/httpAdmin.test.ts. HttpAdminApi 에러 매핑 단위 검증.
 * 관리 평면 429(too_many_requests) 응답을 명시 매핑하는지 확인한다(감사 rank10).
 * 실제 네트워크 없이 global fetch를 스텁한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";

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

test("관리 403: forbidden 코드로 매핑하고 429 안내를 붙이지 않는다", async () => {
  const res = fakeRes({ status: 403, body: { error: { name: "admin_forbidden", message: "권한이 없습니다" } } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStart());
    assert.equal(e.code, "admin_forbidden");
    assert.doesNotMatch(e.message, /초 후|잠시 후/);
  });
});
