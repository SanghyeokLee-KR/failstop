/**
 * tests/httpTransfers.test.ts. HTTP 어댑터의 입출금 요청 헤더 검증.
 * 서버는 Idempotency-Key가 없는 입출금 요청을 400으로 거절한다(TransferInputs.idemKey).
 * fetch를 주입해 실제로 나가는 헤더를 기록하고, 스텁도 같은 규칙으로 거절한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpStompDataSource, requestHeaders } from "../lib/api/httpDatasource";

interface SentRequest {
  path: string;
  method: string;
  headers: Headers;
  body: string | null;
}

function jsonRes(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function b64url(obj: unknown): string {
  return Buffer.from(JSON.stringify(obj)).toString("base64url");
}

function memberJwt(sub: string): string {
  const payload = { typ: "access", sub, user_id: 42, scope: "TRADE", kind: "HUMAN", exp: Math.floor(Date.now() / 1000) + 900 };
  return `${b64url({ alg: "EdDSA", typ: "JWT" })}.${b64url(payload)}.sig`;
}

const MEMBER_USER = { external_id: "9f2b7a4e", email: "trader@demo.local", display_name: "데모트레이더", kind: "HUMAN" };

const TRANSFER_ROW = {
  schema_version: "1.0",
  external_id: "tr-1",
  direction: "DEPOSIT",
  asset: "KRW",
  amount: "1000000",
  status: "COMPLETED",
  reason: null,
  created_at: 1,
  updated_at: 1,
};

type Route = (req: SentRequest, hits: number) => Response;

function newDs(routes: Record<string, Route>) {
  const sent: SentRequest[] = [];
  const hits = new Map<string, number>();
  const fetchFn: typeof fetch = async (input, init) => {
    const path = String(input).replace(/^https?:\/\/[^/]+/, "").split("?")[0];
    const req: SentRequest = {
      path,
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : null,
    };
    sent.push(req);
    const n = (hits.get(path) ?? 0) + 1;
    hits.set(path, n);
    const route = routes[path];
    return route ? route(req, n) : jsonRes(404, { error: { name: "not_found", message: "없음" } });
  };
  const ds = new HttpStompDataSource({
    apiBase: "http://api.test",
    wsBase: "ws://api.test",
    fetchFn,
    stompFactory: () => ({ activate: () => {}, deactivate: async () => {}, subscribe: () => () => {} }),
  });
  return { ds, sent };
}

/** 서버 TransferController와 같은 판정. 키가 없으면 400이다 */
function transferRoute(onOk: Route = () => jsonRes(201, TRANSFER_ROW)): Route {
  return (req, hits) =>
    req.headers.get("Idempotency-Key")
      ? onOk(req, hits)
      : jsonRes(400, { error: { name: "invalid_parameter", message: "Idempotency-Key 헤더 필요" } });
}

test("입출금 요청은 호출부의 Idempotency-Key를 요청 헤더에 싣는다", async () => {
  const token = memberJwt("member");
  const { ds, sent } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: token, user: MEMBER_USER }),
    "/api/v1/transfers": transferRoute(),
  });
  await ds.login({ email: "trader@demo.local", password: "demopass1" });

  const row = await ds.requestTransfer({ direction: "DEPOSIT", asset: "KRW", amount: "1000000" }, "web-key-1");

  assert.equal(row.status, "COMPLETED");
  const post = sent.find((r) => r.path === "/api/v1/transfers");
  assert.ok(post, "입출금 요청이 나가야 한다");
  assert.equal(post.method, "POST");
  assert.equal(post.headers.get("Idempotency-Key"), "web-key-1");
  assert.equal(post.headers.get("Authorization"), `Bearer ${token}`);
  assert.equal(post.headers.get("Content-Type"), "application/json; charset=utf-8");
  assert.deepEqual(JSON.parse(post.body ?? "null"), { direction: "DEPOSIT", asset: "KRW", amount: "1000000" });
});

test("401 뒤 회원 갱신 재시도도 같은 Idempotency-Key를 싣는다", async () => {
  const stale = memberJwt("stale");
  const fresh = memberJwt("fresh");
  const { ds, sent } = newDs({
    "/api/v1/auth/login": () => jsonRes(200, { schema_version: "1.0", access_token: stale, user: MEMBER_USER }),
    "/api/v1/auth/refresh": () => jsonRes(200, { schema_version: "1.0", access_token: fresh }),
    "/api/v1/transfers": transferRoute((_req, hits) =>
      hits === 1 ? jsonRes(401, { error: { name: "jwt_verification", message: "만료" } }) : jsonRes(201, TRANSFER_ROW),
    ),
  });
  await ds.login({ email: "trader@demo.local", password: "demopass1" });

  await ds.requestTransfer({ direction: "WITHDRAW", asset: "KRW", amount: "5000" }, "web-key-2");

  const posts = sent.filter((r) => r.path === "/api/v1/transfers");
  assert.equal(posts.length, 2, "401 뒤 1회 재시도");
  assert.deepEqual(
    posts.map((r) => r.headers.get("Idempotency-Key")),
    ["web-key-2", "web-key-2"],
    "재시도가 새 키를 쓰면 서버가 같은 요청을 두 건으로 접수한다",
  );
  assert.equal(posts[1].headers.get("Authorization"), `Bearer ${fresh}`);
});

test("요청 헤더는 Headers 객체·배열·레코드를 같은 결과로 합친다", () => {
  const expected = { "idempotency-key": "k-1", "x-trace": "t-1", Authorization: "Bearer tok" };
  const forms: HeadersInit[] = [
    new Headers({ "Idempotency-Key": "k-1", "X-Trace": "t-1" }),
    [["Idempotency-Key", "k-1"], ["X-Trace", "t-1"]],
    { "Idempotency-Key": "k-1", "X-Trace": "t-1" },
  ];
  for (const form of forms) {
    assert.deepEqual(requestHeaders(form, false, "tok"), expected);
  }
});

test("호출부가 넘긴 Authorization·Content-Type은 어댑터 값으로 대체된다", () => {
  const headers = requestHeaders(
    { authorization: "Bearer other", "content-type": "text/plain", "Idempotency-Key": "k-1" },
    true,
    "tok",
  );
  assert.deepEqual(headers, {
    "idempotency-key": "k-1",
    "Content-Type": "application/json; charset=utf-8",
    Authorization: "Bearer tok",
  });
});

test("본문과 토큰이 없으면 호출부 헤더만 남는다", () => {
  assert.deepEqual(requestHeaders(undefined, false, null), {});
  assert.deepEqual(requestHeaders({ authorization: "Bearer other" }, false, null), {});
});
