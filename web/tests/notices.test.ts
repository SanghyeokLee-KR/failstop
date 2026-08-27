/**
 * tests/notices.test.ts. 공지 전달 규칙 검증(§4-F).
 *
 * 이 규칙이 틀리면 두 방향으로 조용히 잘못된다. 점검 공지가 배너에 안 뜨거나,
 * 안내 공지가 화면 위를 차지한다. 둘 다 화면을 봐야만 알 수 있고, 배너는 조건이
 * 맞아야 뜨므로 우연히 보게 될 확률도 낮다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { bannerNotice, hasUnread, isDismissible, sortNotices } from "../lib/notices";
import type { NoticeSeverity, PublicNotice } from "../lib/types";

const n = (id: string, severity: NoticeSeverity, at: number): PublicNotice => ({
  id,
  title: id,
  body: "",
  severity,
  published_at: at,
});

test("INFO는 배너에 오르지 않는다", () => {
  // 안내가 배너를 차지하면 다음 CRITICAL이 왔을 때 사용자는 이미 배너를 무시하고 있다
  assert.equal(bannerNotice([n("a", "INFO", 100)]), null);
  assert.equal(bannerNotice([n("a", "INFO", 100), n("b", "INFO", 200)]), null);
});

test("CRITICAL이 최신 WARNING을 이긴다", () => {
  // 시간이 아니라 등급이 먼저다. 새 경고가 오래된 점검 공지를 덮으면 안 된다
  const picked = bannerNotice([n("warn", "WARNING", 900), n("crit", "CRITICAL", 100)]);
  assert.equal(picked?.id, "crit");
});

test("같은 등급이면 최신이 이긴다", () => {
  assert.equal(bannerNotice([n("old", "WARNING", 100), n("new", "WARNING", 900)])?.id, "new");
  assert.equal(bannerNotice([n("old", "CRITICAL", 100), n("new", "CRITICAL", 900)])?.id, "new");
});

test("닫은 WARNING은 다시 뜨지 않는다", () => {
  const list = [n("w1", "WARNING", 100), n("w2", "WARNING", 200)];
  assert.equal(bannerNotice(list, ["w2"])?.id, "w1");
  assert.equal(bannerNotice(list, ["w1", "w2"]), null);
});

test("CRITICAL은 닫아도 다시 뜬다", () => {
  // 닫기 버튼이 없으므로 목록에 들어갈 일이 없지만, 저장된 id가 남아 있어도
  // 무시해야 한다. 여기서 뚫리면 한 번 닫힌 점검 공지가 영구히 사라진다
  const list = [n("c1", "CRITICAL", 100)];
  assert.equal(bannerNotice(list, ["c1"])?.id, "c1");
});

test("닫기 가능 여부는 등급이 정한다", () => {
  assert.equal(isDismissible("CRITICAL"), false);
  assert.equal(isDismissible("WARNING"), true);
  assert.equal(isDismissible("INFO"), true);
});

test("빈 목록은 배너 없음", () => {
  assert.equal(bannerNotice([]), null);
});

test("발행 시각이 없어도 순서가 깨지지 않는다", () => {
  // published_at은 서버가 항상 채우지만 null 허용 타입이다. 정렬에서 NaN이 나오면
  // 비교가 무너져 목록 순서가 실행마다 달라진다
  const missing: PublicNotice = { id: "x", title: "x", body: "", severity: "WARNING", published_at: null };
  const picked = bannerNotice([missing, n("y", "WARNING", 100)]);
  assert.equal(picked?.id, "y");
  assert.deepEqual(sortNotices([missing, n("y", "WARNING", 100)]).map((v) => v.id), ["y", "x"]);
});

test("안 읽은 공지 판정", () => {
  const list = [n("a", "INFO", 500)];
  // 한 번도 안 봤으면 표식을 띄운다
  assert.equal(hasUnread(list, null), true);
  // 마지막으로 본 뒤에 발행됐으면 표식
  assert.equal(hasUnread(list, 400), true);
  // 발행 시각과 같으면 이미 본 것이다
  assert.equal(hasUnread(list, 500), false);
  assert.equal(hasUnread(list, 600), false);
  // 공지가 없으면 표식도 없다
  assert.equal(hasUnread([], null), false);
});

test("목록은 최신순", () => {
  const sorted = sortNotices([n("a", "INFO", 100), n("b", "CRITICAL", 300), n("c", "WARNING", 200)]);
  assert.deepEqual(sorted.map((v) => v.id), ["b", "c", "a"]);
});
