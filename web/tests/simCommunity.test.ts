/**
 * tests/simCommunity.test.ts. 게시판 sim 데이터 소스 계약 검증.
 * 커서 페이징, 회원 세션 쓰기 게이트, 좋아요 멱등, 본인만 수정·삭제(403), 신고 중복(409),
 * 댓글 카운트 정합을 회귀 방지 대상으로 고정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimCommunityDataSource } from "../lib/community/datasource";
import type { MemberIdentity } from "../lib/community/types";
import { ApiError } from "../lib/api/httpDatasource";

function makeDs(initial: MemberIdentity | null = null) {
  let identity = initial;
  const ds = new SimCommunityDataSource(() => identity);
  return {
    ds,
    login: (id: MemberIdentity) => {
      identity = id;
    },
    logout: () => {
      identity = null;
    },
  };
}

const alice: MemberIdentity = { externalId: "u-alice", handle: "앨리스", token: "t-alice" };
const bob: MemberIdentity = { externalId: "u-bob", handle: "밥", token: "t-bob" };

async function codeOf(fn: () => Promise<unknown>): Promise<string> {
  try {
    await fn();
    return "no_error";
  } catch (e) {
    return e instanceof ApiError ? e.code : "unknown";
  }
}

test("최신순 커서 페이징은 경계 중복 없이 이어진다", async () => {
  const { ds } = makeDs();
  const page1 = await ds.listPosts("KRW-BTC", "latest", null, 2);
  assert.equal(page1.items.length, 2);
  assert.equal(page1.has_more, true);
  assert.ok(page1.next_cursor);

  const page2 = await ds.listPosts("KRW-BTC", "latest", page1.next_cursor, 2);
  const ids = new Set(page1.items.map((p) => p.external_id));
  for (const p of page2.items) assert.ok(!ids.has(p.external_id), "페이지 경계 중복 금지");
  assert.equal(page2.has_more, false);
  assert.equal(page2.next_cursor, null);
});

test("미로그인 작성은 jwt_verification으로 거부한다", async () => {
  const { ds } = makeDs();
  const code = await codeOf(() => ds.createPost("KRW-BTC", { title: "제목", body: "본문" }));
  assert.equal(code, "jwt_verification");
});

test("로그인 작성은 최신순 최상단에 노출되고 mine 표식이 붙는다", async () => {
  const { ds, login } = makeDs();
  login(alice);
  const created = await ds.createPost("KRW-XRP", { title: "리플 토론", body: "의견 남깁니다" });
  assert.equal(created.mine, true);
  assert.equal(created.author_handle, "앨리스");

  const page = await ds.listPosts("KRW-XRP", "latest", null, 20);
  assert.equal(page.items[0].external_id, created.external_id);
});

test("좋아요 토글은 멱등이다(중복 좋아요는 1로 수렴)", async () => {
  const { ds, login } = makeDs();
  login(alice);
  const post = await ds.createPost("KRW-ADA", { title: "t", body: "b" });
  const base = post.like_count;

  const r1 = await ds.togglePostLike(post.external_id, true);
  const r2 = await ds.togglePostLike(post.external_id, true);
  assert.equal(r1.like_count, base + 1);
  assert.equal(r2.like_count, base + 1, "같은 회원 중복 좋아요는 증가하지 않는다");
  assert.equal(r2.liked, true);

  const r3 = await ds.togglePostLike(post.external_id, false);
  assert.equal(r3.like_count, base);
  assert.equal(r3.liked, false);
});

test("타인 글 수정·삭제는 out_of_scope로 거부한다", async () => {
  const { ds, login } = makeDs();
  login(alice);
  const post = await ds.createPost("KRW-SOL", { title: "t", body: "b" });

  login(bob);
  assert.equal(await codeOf(() => ds.updatePost(post.external_id, { title: "x", body: "y" })), "out_of_scope");
  assert.equal(await codeOf(() => ds.deletePost(post.external_id)), "out_of_scope");
});

test("본인 글 삭제는 목록·상세에서 제외·마스킹된다", async () => {
  const { ds, login } = makeDs();
  login(alice);
  const post = await ds.createPost("KRW-DOT", { title: "지워질글", body: "b" });
  await ds.deletePost(post.external_id);

  const page = await ds.listPosts("KRW-DOT", "latest", null, 20);
  assert.ok(!page.items.some((p) => p.external_id === post.external_id), "삭제글은 목록에서 제외");

  const detail = await ds.getPost(post.external_id);
  assert.equal(detail.status, "DELETED");
  assert.equal(detail.body, "삭제된 글");
});

test("동일 대상 중복 신고는 duplicate_report로 거부한다", async () => {
  const { ds, login } = makeDs();
  login(alice);
  const post = await ds.createPost("KRW-AVAX", { title: "t", body: "b" });

  login(bob);
  await ds.report({ targetType: "POST", targetId: post.external_id, reason: "SPAM" });
  const code = await codeOf(() => ds.report({ targetType: "POST", targetId: post.external_id, reason: "ABUSE" }));
  assert.equal(code, "duplicate_report");
});

test("댓글 작성·삭제가 comment_count 정합을 유지한다", async () => {
  const { ds, login } = makeDs();
  login(alice);
  const post = await ds.createPost("KRW-LINK", { title: "t", body: "b" });
  assert.equal(post.comment_count, 0);

  const c1 = await ds.createComment(post.external_id, { body: "첫 댓글" });
  await ds.createComment(post.external_id, { body: "둘째 댓글" });
  let detail = await ds.getPost(post.external_id);
  assert.equal(detail.comment_count, 2);

  await ds.deleteComment(c1.external_id);
  detail = await ds.getPost(post.external_id);
  assert.equal(detail.comment_count, 1, "삭제 댓글은 집계에서 제외");

  const comments = await ds.listComments(post.external_id, null, 20);
  const masked = comments.items.find((c) => c.external_id === c1.external_id);
  assert.ok(masked, "삭제 댓글도 트리 유지를 위해 목록에는 남는다");
  assert.equal(masked!.status, "DELETED");
});

test("인기순은 좋아요 많은 글을 앞세운다", async () => {
  const { ds, login } = makeDs();
  login(alice);
  const low = await ds.createPost("KRW-ATOM", { title: "낮은", body: "b" });
  const high = await ds.createPost("KRW-ATOM", { title: "높은", body: "b" });
  await ds.togglePostLike(high.external_id, true);

  const page = await ds.listPosts("KRW-ATOM", "popular", null, 20);
  const hi = page.items.findIndex((p) => p.external_id === high.external_id);
  const lo = page.items.findIndex((p) => p.external_id === low.external_id);
  assert.ok(hi < lo, "좋아요 많은 글이 앞에 온다");
});
