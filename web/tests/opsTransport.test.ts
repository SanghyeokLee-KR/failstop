/**
 * 관제 판의 전송 규칙(관리자 설계 4-4). http는 한 판 조회(GET /admin/v1/msp/overview)를 읽고, 같은 조회가
 * 겹치면 한 건만 보내며, ETag가 같으면 304로 받은 본문을 다시 쓴다. 관제 모듈이 없는 배포(404)는 칸마다
 * '원천 연결 전'이다. 폴러의 백오프, 판의 구조 공유, 추세선 점 줄이기도 여기서 본다. 서버 응답을 판으로 옮기는
 * 규칙은 opsHttpAdapter.test.ts가 서버 스냅숏으로 본다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError, type AdminApi } from "../lib/admin/api";
import { backoffMs, shareEqual } from "../lib/admin/opsCenter";
import { downsample, SPARK_MAX_POINTS } from "../lib/admin/series";

type Stub = (url: string, init: RequestInit) => Response | Promise<Response>;

/** fetch를 바꿔 끼우고 부른 주소와 헤더를 모은다 */
async function withFetch(stub: Stub, run: (calls: { url: string; headers: Record<string, string> }[]) => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  const calls: { url: string; headers: Record<string, string> }[] = [];
  globalThis.fetch = (async (url: string, init: RequestInit = {}) => {
    calls.push({ url, headers: { ...(init.headers as Record<string, string>) } });
    return stub(url, init);
  }) as typeof fetch;
  try {
    await run(calls);
  } finally {
    globalThis.fetch = prev;
  }
}

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });

const SECTIONS = ["summary", "hosts", "readTier", "replication", "alerts", "incidents", "jobs", "thresholds", "ai", "logs", "aiServer", "trends"] as const;

test("http: 관제 모듈이 없는 배포(404)는 칸마다 '원천 연결 전'과 원천 이름이고 원천 상태는 모르는 채로 둔다", async () => {
  // 오류 본문의 이름은 배포마다 다르다. 이름이 아니라 상태 404로 가른다
  await withFetch(() => json({ error: { name: "no_handler", message: "없음" } }, 404), async (calls) => {
    const api: AdminApi = new HttpAdminApi("http://admin.test");
    const c = await api.getControlCenter();
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "http://admin.test/admin/v1/msp/overview");
    assert.equal(c.origin, null);
    assert.equal(c.environment, "UNKNOWN");
    assert.equal(c.sources, null, "원천 상태를 지어내지 않는다");
    for (const key of SECTIONS) {
      const s = c[key];
      assert.equal(s.state, "NOT_CONNECTED", key);
      assert.ok(s.state === "NOT_CONNECTED" && s.source.length > 0, `${key} 원천 이름`);
    }
    // 조작은 서버 경로로 보내고, 관제 모듈이 없는 배포라 서버의 404를 그대로 올린다. 조작을 지어내 성공시키지 않는다
    (api as unknown as { session: unknown }).session = { operatorId: "op-1", displayName: "op", roles: ["SUPER"], token: "t", issuedAt: "", expiresAt: "2999-01-01T00:00:00.000Z" };
    for (const run of [() => api.ackAlert("a"), () => api.decideAiProposal("p", "REJECT"), () => api.stopAi(), () => api.runBackupNow()]) {
      await assert.rejects(run(), (e: unknown) => e instanceof AdminError && e.status === 404);
    }
    assert.deepEqual(calls.slice(1).map((c) => c.url), [
      "http://admin.test/admin/v1/msp/alerts/a/ack",
      "http://admin.test/admin/v1/msp/ai/actions/p/reject",
      "http://admin.test/admin/v1/msp/ai/stop",
      "http://admin.test/admin/v1/msp/ops/backup",
    ]);
  });
});

test("http: 칸이 빠진 한 판은 환경과 원천 상태만 옮기고 칸은 연결 전이다. 모르는 원천 이름과 상태는 버린다", async () => {
  const body = {
    environment: "SCHOOL_AWS",
    generated_at: "2026-09-25T01:00:00.000Z",
    sources: [
      { name: "prometheus", state: "READY" },
      { name: "alertmanager", state: "READY", last_poll_at: "2026-09-25T00:59:50.000Z" },
      { name: "llm", state: "UNAVAILABLE", reason: "연결 시간 초과" },
      { name: "databasus", state: "NOT_CONNECTED" },
      { name: "grafana", state: "READY" },
      { name: "teams", state: "SOMETIMES" },
    ],
  };
  await withFetch(() => json(body), async () => {
    const c = await new HttpAdminApi("http://admin.test").getControlCenter();
    assert.equal(c.environment, "SCHOOL_AWS");
    assert.deepEqual(c.sources, [
      { name: "prometheus", state: "READY", checkedAt: "2026-09-25T01:00:00.000Z", reason: null },
      { name: "alertmanager", state: "READY", checkedAt: "2026-09-25T00:59:50.000Z", reason: null },
      { name: "llm", state: "UNAVAILABLE", checkedAt: null, reason: "연결 시간 초과" },
      { name: "databasus", state: "NOT_CONNECTED", checkedAt: null, reason: null },
    ]);
    // 서버가 싣지 않은 칸은 값을 지어내지 않는다
    for (const key of SECTIONS) assert.equal(c[key].state, "NOT_CONNECTED", key);
  });
});

test("http: 같은 조회가 겹치면 요청은 한 건이고, ETag가 같으면 304로 받은 본문을 다시 쓴다", async () => {
  let release: () => void = () => {};
  const gate = new Promise<void>((r) => {
    release = r;
  });
  let n = 0;
  await withFetch(
    async (_url, init) => {
      n += 1;
      const headers = init.headers as Record<string, string>;
      if (headers["If-None-Match"] === '"v1"') return new Response(null, { status: 304 });
      await gate;
      return json({ environment: "LOCAL_DOCKER", generated_at: "2026-09-25T01:00:00.000Z", sources: [] }, 200, { ETag: '"v1"' });
    },
    async (calls) => {
      const api = new HttpAdminApi("http://admin.test");
      const a = api.getControlCenter();
      const b = api.getControlCenter();
      release();
      const [ca, cb] = await Promise.all([a, b]);
      assert.equal(n, 1, "겹친 조회는 한 건이다");
      assert.equal(ca.environment, "LOCAL_DOCKER");
      assert.equal(cb.environment, "LOCAL_DOCKER");
      assert.equal(calls[0].headers["If-None-Match"], undefined, "처음에는 태그가 없다");

      const again = await api.getControlCenter();
      assert.equal(n, 2);
      assert.equal(calls[1].headers["If-None-Match"], '"v1"', "받은 태그를 싣는다");
      assert.equal(again.environment, "LOCAL_DOCKER", "304면 전에 받은 본문을 쓴다");
      assert.deepEqual(again.sources, []);
    },
  );
});

test("http: 조작(POST)은 합치지 않는다", async () => {
  await withFetch(() => json({ applied: true }), async (calls) => {
    const api = new HttpAdminApi("http://admin.test") as unknown as { http: (p: string, i: RequestInit) => Promise<unknown> };
    await Promise.all([api.http("/admin/v1/x", { method: "POST", body: "{}" }), api.http("/admin/v1/x", { method: "POST", body: "{}" })]);
    assert.equal(calls.length, 2);
  });
});

test("폴러 백오프: 실패마다 주기를 두 배로 늘려 5분에서 멈추고, Retry-After가 더 길면 그것을 따른다", () => {
  assert.equal(backoffMs(15_000, 1, null), 30_000);
  assert.equal(backoffMs(15_000, 3, null), 120_000);
  assert.equal(backoffMs(15_000, 8, null), 300_000);
  assert.equal(backoffMs(1_000, 1, 60), 60_000);
  assert.equal(backoffMs(300_000, 1, null), 300_000, "모듈이 없는 배포의 5분 주기도 상한을 넘지 않는다");
});

test("판의 구조 공유: 같은 가지는 이전 참조를 지키고 바뀐 가지만 새 참조가 된다", () => {
  const prev = { a: { x: 1, list: [1, 2] }, b: { y: "p" }, at: "t1" };
  const same = shareEqual(prev, { a: { x: 1, list: [1, 2] }, b: { y: "p" }, at: "t1" });
  assert.equal(same, prev, "값이 모두 같으면 판 전체가 이전 객체다");
  const next = shareEqual(prev, { a: { x: 1, list: [1, 2] }, b: { y: "q" }, at: "t2" });
  assert.notEqual(next, prev);
  assert.equal(next.a, prev.a, "바뀌지 않은 칸은 이전 참조다");
  assert.notEqual(next.b, prev.b);
  const grown = shareEqual(prev, { ...prev, a: { x: 1, list: [1, 2, 3] } });
  assert.notEqual(grown.a.list, prev.a.list);
  const dropped = shareEqual({ k: 1, gone: 2 } as Record<string, number>, { k: 1 } as Record<string, number>);
  assert.deepEqual(dropped, { k: 1 }, "빠진 열쇠도 바뀐 것이다");
});

test("추세선 점 줄이기: 선은 치솟음을 남기고 막대는 묶음의 합을 남기며, 마지막 점은 제자리다", () => {
  const flat = Array.from({ length: 240 }, () => 100);
  flat[37] = 900;
  flat[239] = 120;
  const line = downsample(flat);
  assert.ok(line.points.length <= SPARK_MAX_POINTS);
  assert.equal(line.factor, 4);
  assert.ok(line.points.includes(900), "순간 치솟음을 지우지 않는다");
  assert.equal(line.points[line.points.length - 1], 120, "마지막 묶음의 값이 지금 값이다");

  const counts = Array.from({ length: 240 }, (_, i) => (i % 8 === 0 ? 1 : 0));
  const bars = downsample(counts, SPARK_MAX_POINTS, "sum");
  assert.equal(bars.points.reduce<number>((a, v) => a + (v ?? 0), 0), 30, "묶어도 건수의 합은 같다");

  const gaps: (number | null)[] = Array.from({ length: 120 }, (_, i) => (i < 60 ? null : 5));
  const g = downsample(gaps);
  assert.equal(g.points[0], null, "모두 빈 묶음은 빈 점이다");
  assert.deepEqual(downsample([1, 2, 3]), { points: [1, 2, 3], factor: 1 }, "상한 안이면 그대로다");
});
