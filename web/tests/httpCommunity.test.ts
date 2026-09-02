/**
 * HttpCommunityDataSource 소유권(mine) 소비 계약 검증. 공개 GET은 optional-auth다(게시판_설계 §4-D).
 * 서버 산출 mine을 직접 소비하고 handle 대조 파생을 쓰지 않는다(동명이인 오노출·표시명 변경 소멸 방지).
 * 서버 mine=true/false/미제공 3분기와 optional-auth 헤더 규약을 고정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpCommunityDataSource } from "../lib/community/httpDatasource";
import { ApiError } from "../lib/api/httpDatasource";
import type { MemberIdentity } from "../lib/community/types";

interface RecordedCall {
  url: string;
  method: string;
  auth: string | null;
  idem: string | null;
}

function jsonRes(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

type Route = (call: RecordedCall) => Response;

function makeFetch(routes: Record<string, Route>) {
  const calls: RecordedCall[] = [];
  const fetchFn: typeof fetch = async (input, init) => {
    const url = String(input);
    const path = url.replace(/^https?:\/\/[^/]+/, "").split("?")[0];
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const call: RecordedCall = {
      url,
      method: init?.method ?? "GET",
      auth: headers["Authorization"] ?? null,
      idem: headers["Idempotency-Key"] ?? null,
    };
    calls.push(call);
    const route = routes[path];
    if (!route) return jsonRes(404, { error: { name: "post_not_found", message: "없음" } });
    return route(call);
  };
  return { fetchFn, calls };
}

const me: MemberIdentity = { externalId: "u-alice", handle: "앨리스", token: "tok-alice" };

function newDs(
  routes: Record<string, Route>,
  identity: MemberIdentity | null = null,
  refresh?: () => Promise<MemberIdentity | null>,
) {
  const { fetchFn, calls } = makeFetch(routes);
  // refresh는 신선한 신원으로 교체하고 반환한다. 재시도 요청이 identityProvider로 새 토큰을 읽게 한다
  let current = identity;
  const ds = new HttpCommunityDataSource({
    apiBase: "http://api.test",
    identityProvider: () => current,
    refreshIdentity: refresh
      ? async () => {
          current = await refresh();
          return current;
        }
      : undefined,
    fetchFn,
  });
  return { ds, calls };
}

// 백엔드 GET 와이어 형태: 식별자는 post_id, author_id·liked는 공개 GET 미포함
function wirePost(over: Record<string, unknown> = {}) {
  return {
    post_id: "p-1",
    market: "KRW-BTC",
    author_handle: "밥",
    title: "제목",
    body: "본문",
    status: "VISIBLE",
    like_count: 3,
    comment_count: 1,
    created_at: "2026-07-20T00:00:00.000Z",
    updated_at: "2026-07-20T00:00:00.000Z",
    ...over,
  };
}

function wireComment(over: Record<string, unknown> = {}) {
  return {
    comment_id: "c-1",
    author_handle: "밥",
    body: "댓글",
    status: "VISIBLE",
    like_count: 0,
    created_at: "2026-07-20T00:00:00.000Z",
    ...over,
  };
}

/* ===== mine 3분기: true / false / 미제공 ===== */

test("글 단건: 서버 mine=true를 그대로 소비한다", async () => {
  const { ds } = newDs({ "/api/v1/community/posts/p-1": () => jsonRes(200, { data: wirePost({ mine: true }) }) }, me);
  const post = await ds.getPost("p-1");
  assert.equal(post.mine, true);
  assert.equal(post.external_id, "p-1");
});

test("글 단건: 서버 mine=false면 false", async () => {
  const { ds } = newDs({ "/api/v1/community/posts/p-1": () => jsonRes(200, { data: wirePost({ mine: false }) }) }, me);
  const post = await ds.getPost("p-1");
  assert.equal(post.mine, false);
});

test("글 단건: mine 미제공(비로그인·구서버)은 false로 강등", async () => {
  const { ds } = newDs({ "/api/v1/community/posts/p-1": () => jsonRes(200, { data: wirePost() }) }, null);
  const post = await ds.getPost("p-1");
  assert.equal(post.mine, false);
});

/* ===== handle 파생 제거 검증: 동명이인·표시명 변경 ===== */

test("동명이인: author_handle이 세션 handle과 같아도 서버 mine=false면 소유 아님(오노출 해소)", async () => {
  const { ds } = newDs(
    { "/api/v1/community/posts/p-1": () => jsonRes(200, { data: wirePost({ author_handle: me.handle, mine: false }) }) },
    me,
  );
  const post = await ds.getPost("p-1");
  assert.equal(post.author_handle, me.handle);
  assert.equal(post.mine, false, "handle 일치가 소유권을 만들지 않는다");
});

test("표시명 변경: author_handle이 세션 handle과 달라도 서버 mine=true면 소유 유지(소멸 해소)", async () => {
  const { ds } = newDs(
    { "/api/v1/community/posts/p-1": () => jsonRes(200, { data: wirePost({ author_handle: "옛핸들", mine: true }) }) },
    me,
  );
  const post = await ds.getPost("p-1");
  assert.notEqual(post.author_handle, me.handle);
  assert.equal(post.mine, true, "handle 불일치가 소유권을 지우지 않는다");
});

/* ===== optional-auth 헤더 규약 ===== */

test("공개 GET은 세션이 있으면 Bearer를 실어 서버 mine 산출을 유도한다", async () => {
  const { ds, calls } = newDs({ "/api/v1/community/posts/p-1": () => jsonRes(200, { data: wirePost({ mine: true }) }) }, me);
  await ds.getPost("p-1");
  const get = calls.find((c) => c.url.includes("/posts/p-1"));
  assert.equal(get?.auth, `Bearer ${me.token}`);
});

test("공개 GET은 비로그인도 조회 가능하고 Authorization을 붙이지 않는다", async () => {
  const { ds, calls } = newDs({ "/api/v1/community/posts/p-1": () => jsonRes(200, { data: wirePost() }) }, null);
  const post = await ds.getPost("p-1");
  assert.equal(post.mine, false);
  const get = calls.find((c) => c.url.includes("/posts/p-1"));
  assert.equal(get?.auth, null, "토큰 없으면 헤더 미첨부");
});

/* ===== 목록·댓글 소비 ===== */

test("글 목록: 항목별 서버 mine을 소비한다(handle 일치해도 미제공이면 false)", async () => {
  const { ds } = newDs(
    {
      "/api/v1/community/boards/KRW-BTC/posts": () =>
        jsonRes(200, {
          data: [wirePost({ post_id: "p-1", mine: true }), wirePost({ post_id: "p-2", author_handle: me.handle })],
          pagination: { next_cursor: null, has_more: false },
        }),
    },
    me,
  );
  const page = await ds.listPosts("KRW-BTC", "latest");
  assert.deepEqual(
    page.items.map((p) => p.mine),
    [true, false],
  );
});

test("댓글 목록: 서버 mine을 소비한다(true/false/미제공)", async () => {
  const { ds } = newDs(
    {
      "/api/v1/community/posts/p-1/comments": () =>
        jsonRes(200, {
          data: [
            wireComment({ comment_id: "c-1", author_handle: "밥", mine: true }),
            wireComment({ comment_id: "c-2", author_handle: me.handle, mine: false }),
            wireComment({ comment_id: "c-3", author_handle: me.handle }),
          ],
          pagination: { next_cursor: null, has_more: false },
        }),
    },
    me,
  );
  const page = await ds.listComments("p-1");
  assert.deepEqual(
    page.items.map((c) => c.mine),
    [true, false, false],
  );
  assert.equal(page.items[0].external_id, "c-1");
});

/* ===== 작성 경로: 작성자=요청자이므로 mine=true ===== */

test("글 작성: id만 반환하면 재조회하고 재조회 GET도 Bearer를 실어 서버 mine을 받는다", async () => {
  const { ds, calls } = newDs(
    {
      "/api/v1/community/boards/KRW-BTC/posts": () => jsonRes(201, { data: { post_id: "p-77" } }),
      "/api/v1/community/posts/p-77": () => jsonRes(200, { data: wirePost({ post_id: "p-77", mine: true }) }),
    },
    me,
  );
  const post = await ds.createPost("KRW-BTC", { title: "제목", body: "본문" });
  assert.equal(post.external_id, "p-77");
  assert.equal(post.mine, true);
  const get = calls.find((c) => c.method === "GET" && c.url.includes("/posts/p-77"));
  assert.equal(get?.auth, `Bearer ${me.token}`, "재조회도 optional-auth로 Bearer 첨부");
});

test("댓글 작성: id만 반환하면 요청자 신원으로 합성하고 mine=true", async () => {
  const { ds } = newDs(
    { "/api/v1/community/posts/p-1/comments": () => jsonRes(200, { data: { comment_id: "c-9" } }) },
    me,
  );
  const c = await ds.createComment("p-1", { body: "새 댓글" });
  assert.equal(c.external_id, "c-9");
  assert.equal(c.mine, true);
  assert.equal(c.author_handle, me.handle);
});

/* ===== 쓰기(required) 경로 회귀: boolean->AuthMode 전환 후에도 인증 게이트 보존 ===== */

test("쓰기(required)는 미로그인 시 서버 왕복 없이 jwt_verification으로 거부한다", async () => {
  let hits = 0;
  const { ds } = newDs(
    {
      "/api/v1/community/boards/KRW-BTC/posts": () => {
        hits += 1;
        return jsonRes(500, { error: { name: "server_error", message: "닿으면 안 됨" } });
      },
    },
    null,
  );
  await assert.rejects(
    () => ds.createPost("KRW-BTC", { title: "제목", body: "본문" }),
    (e: unknown) => e instanceof ApiError && e.code === "jwt_verification",
  );
  assert.equal(hits, 0, "미로그인 쓰기는 네트워크 왕복 전에 차단");
});

test("쓰기(required) 401은 refresh 후 새 토큰으로 1회 재시도하고 멱등키를 보존한다", async () => {
  const stale: MemberIdentity = { externalId: "u-alice", handle: "앨리스", token: "tok-stale" };
  const fresh: MemberIdentity = { externalId: "u-alice", handle: "앨리스", token: "tok-fresh" };
  const { ds, calls } = newDs(
    {
      "/api/v1/community/posts/p-1/comments": (call) =>
        call.auth === `Bearer ${fresh.token}`
          ? jsonRes(200, { data: { comment_id: "c-9" } })
          : jsonRes(401, { error: { name: "jwt_verification", message: "만료" } }),
    },
    stale,
    async () => fresh,
  );
  const c = await ds.createComment("p-1", { body: "재시도 댓글", clientKey: "idem-key-1" });
  assert.equal(c.external_id, "c-9");
  assert.equal(c.mine, true);
  const posts = calls.filter((x) => x.method === "POST");
  assert.equal(posts.length, 2, "401 후 1회 재시도");
  assert.equal(posts[0].auth, `Bearer ${stale.token}`);
  assert.equal(posts[1].auth, `Bearer ${fresh.token}`, "재시도는 갱신 토큰으로");
  assert.equal(posts[0].idem, "idem-key-1");
  assert.equal(posts[1].idem, "idem-key-1", "재시도가 같은 멱등키를 재전송(중복 생성 방지)");
});

/* 429 파싱 계약(§2-3 429 클라이언트 규약). 거래 어댑터와 같은 표를 커뮤니티에도 건다.
   세 평면이 같은 규칙을 쓴다는 것이 규약의 요지이고, 한 평면만 테스트하면 나머지는 발산한다 */
test("429: 비 JSON 본문도 too_many_requests로 분류한다", async () => {
  const { ds } = newDs({
    "/api/v1/community/boards/KRW-BTC/posts": () => new Response("<html>gateway</html>", { status: 429 }),
  });
  await assert.rejects(
    () => ds.listPosts("KRW-BTC", "latest"),
    (e: unknown) => e instanceof ApiError && e.status === 429 && e.code === "too_many_requests",
  );
});

test("429: name 없는 본문에서도 retry_after를 읽는다", async () => {
  const { ds } = newDs({
    "/api/v1/community/boards/KRW-BTC/posts": () => jsonRes(429, { error: { retry_after: 3 } }),
  });
  await assert.rejects(
    () => ds.listPosts("KRW-BTC", "latest"),
    // 가드 안에서 읽으면 여기서 null 이 된다. 회복 시각을 버리는 자리다
    (e: unknown) => e instanceof ApiError && e.retryAfter === 3,
  );
});

test("429: Retry-After가 HTTP-date면 값을 만들지 않는다", async () => {
  const { ds } = newDs({
    "/api/v1/community/boards/KRW-BTC/posts": () =>
      new Response(JSON.stringify({ error: { name: "too_many_requests" } }), {
        status: 429,
        headers: { "Content-Type": "application/json", "Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT" },
      }),
  });
  await assert.rejects(
    () => ds.listPosts("KRW-BTC", "latest"),
    (e: unknown) => e instanceof ApiError && e.retryAfter === null,
  );
});
