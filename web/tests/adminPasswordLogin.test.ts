import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpAdminApi } from "../lib/admin/httpAdmin";

test("비밀번호는 HTTPS 세션 발급 한 번에만 전송한다", async () => {
  const original = globalThis.fetch;
  const calls: RequestInit[] = [];
  globalThis.fetch = async (_url, init) => {
    calls.push(init ?? {});
    const body = calls.length === 1 ? {
      operator_id: "operator", display_name: "운영자", roles: ["VIEWER", "BOT_OPS"],
      token: "short-session", expires_at: new Date(Date.now() + 60000).toISOString(),
    } : [];
    return new Response(JSON.stringify(body), { status: 200 });
  };
  try {
    const api = new HttpAdminApi("https://admin.example.test");
    await api.establishSession("operator", "test-password");
    await api.getOperators();
    assert.equal(new Headers(calls[0].headers).get("Authorization"),
      `Basic ${btoa("operator:test-password")}`);
    assert.equal(new Headers(calls[1].headers).get("Authorization"), "Bearer short-session");
    assert.ok(!JSON.stringify(api.getSession()).includes("test-password"));
    api.endSession();
    assert.equal(api.getSession(), null);
  } finally {
    globalThis.fetch = original;
  }
});

test("HTTP와 잘못된 아이디는 비밀번호를 전송하지 않는다", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls += 1; return new Response("{}", { status: 401 }); };
  try {
    await assert.rejects(new HttpAdminApi("http://admin.example.test").establishSession("operator", "secret"));
    await assert.rejects(new HttpAdminApi("https://admin.example.test").establishSession("bad:name", "secret"));
    assert.equal(calls, 0);
  } finally {
    globalThis.fetch = original;
  }
});
