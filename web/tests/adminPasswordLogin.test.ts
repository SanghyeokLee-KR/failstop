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
    // 이름에 localhost가 들어가도 루프백이 아니면 막는다
    await assert.rejects(new HttpAdminApi("http://localhost.example.test").establishSession("operator", "secret"));
    await assert.rejects(new HttpAdminApi("http://127.0.0.1.example.test").establishSession("operator", "secret"));
    await assert.rejects(new HttpAdminApi("https://admin.example.test").establishSession("bad:name", "secret"));
    assert.equal(calls, 0);
  } finally {
    globalThis.fetch = original;
  }
});

test("이 PC의 루프백 관문은 http여도 비밀번호 로그인을 받는다", async () => {
  const original = globalThis.fetch;
  const auth: (string | null)[] = [];
  globalThis.fetch = async (_url, init) => {
    auth.push(new Headers(init?.headers).get("Authorization"));
    return new Response(JSON.stringify({
      operator_id: "op-01", display_name: "운영자", roles: ["SUPER"],
      token: "short-session", expires_at: new Date(Date.now() + 60000).toISOString(),
    }), { status: 200 });
  };
  try {
    for (const base of ["http://localhost:3000", "http://127.0.0.1:3000", "http://[::1]:3000"]) {
      const api = new HttpAdminApi(base);
      await api.establishSession("op-01", "test-password");
      assert.equal(api.getSession()?.operatorId, "op-01", base);
    }
    assert.deepEqual(auth, Array(3).fill(`Basic ${btoa("op-01:test-password")}`));
  } finally {
    globalThis.fetch = original;
  }
});
