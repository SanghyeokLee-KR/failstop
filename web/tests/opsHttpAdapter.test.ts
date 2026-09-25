/**
 * 관제센터 http 어댑터(httpOps.ts)를 서버가 실제로 낸 응답으로 시험한다. 픽스처는 서버 통합 시험
 * MspContractSnapshotIntegrationTest가 Testcontainers(PostgreSQL)와 원천 스텁(Prometheus, Alertmanager, Ollama 모양 LLM,
 * 보고 서비스)으로 관리 API를 띄우고 받은 본문이다(web/tests/fixtures/msp, {status, etag?, body}). 실제 학교·로컬 스택이나
 * 실제 모델의 응답은 아니다. 시각은 스냅숏을 뜬 시각이라 시험은 시각의 값이 아니라 모양과 변환을 본다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";
import {
  OpsFeed,
  boardOf,
  chatReplyOf,
  emptyExtras,
  judgmentOf,
  plainDecimal,
  reportDeskOf,
  thresholdToView,
  thresholdToWire,
  type WireOverview,
} from "../lib/admin/httpOps";
import type { ControlCenter, OpsSection } from "../lib/admin/types";

const FIX = path.resolve(__dirname, "..", "..", "tests", "fixtures", "msp");

interface Snap {
  status: number;
  etag?: string;
  body: unknown;
}

function snap(name: string): Snap {
  return JSON.parse(readFileSync(path.join(FIX, `${name}.json`), "utf8")) as Snap;
}
const body = <T>(name: string) => snap(name).body as T;

function ready<T>(s: OpsSection<T>, what: string): T {
  assert.equal(s.state, "READY", what);
  return (s as { data: T }).data;
}

/** 경로마다 픽스처를 돌려주는 가짜 fetch. 부른 경로와 본문을 모은다 */
type Route = (url: string, init: RequestInit) => Snap | null;
async function withServer(route: Route, run: (calls: { path: string; method: string; body: unknown }[]) => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  const calls: { path: string; method: string; body: unknown }[] = [];
  globalThis.fetch = (async (url: string, init: RequestInit = {}) => {
    const p = url.replace("http://admin.test", "");
    calls.push({ path: p, method: init.method ?? "GET", body: init.body ? JSON.parse(String(init.body)) : null });
    const s = route(p, init);
    if (!s) return new Response(JSON.stringify({ error: { name: "not_found", message: "없음" } }), { status: 404 });
    if (s.status === 304) return new Response(null, { status: 304 });
    return new Response(JSON.stringify(s.body), { status: s.status, headers: { "Content-Type": "application/json", ...(s.etag ? { ETag: s.etag } : {}) } });
  }) as typeof fetch;
  try {
    await run(calls);
  } finally {
    globalThis.fetch = prev;
  }
}

/** 한 판과 세부 조회를 모두 픽스처로 답하는 서버 */
function fullServer(p: string): Snap | null {
  if (p === "/admin/v1/msp/overview") return snap("overview-full");
  if (p.startsWith("/admin/v1/msp/ai/actions?")) return snap("ai-actions-all");
  if (p === "/admin/v1/msp/ai/mode") return snap("ai-mode-manual");
  if (p === "/admin/v1/msp/ai/limits") return snap("ai-limits");
  if (p.startsWith("/admin/v1/msp/ai/limits/changes")) return snap("ai-limits-changes");
  if (p.startsWith("/admin/v1/msp/ai/decisions?")) return snap("ai-decisions");
  if (p.startsWith("/admin/v1/msp/ai/decisions/")) return snap("ai-decision-detail");
  if (p.startsWith("/admin/v1/msp/thresholds/changes")) return snap("threshold-changes");
  if (p.startsWith("/admin/v1/msp/incidents?")) return snap("incidents");
  if (p.startsWith("/admin/v1/msp/incidents/")) return snap("incident-detail");
  if (p === "/admin/v1/msp/trends?range=1h") return snap("trends-1h");
  if (p === "/admin/v1/msp/trends?range=6h") return snap("trends-6h");
  if (p === "/admin/v1/msp/reports/status") return snap("reports-status");
  if (p.startsWith("/admin/v1/msp/maintenance-windows?")) return snap("maintenance-windows");
  return null;
}

test("서버 한 판: 칸마다 단위를 화면 단위로 옮기고, 서버에 없는 칸은 원천 연결 전으로 둔다", () => {
  const w = body<WireOverview>("overview-full");
  const c = boardOf(w, emptyExtras(), Date.parse(w.generated_at ?? ""));
  assert.equal(c.origin, "LIVE");
  assert.equal(c.environment, "LOCAL_DOCKER");

  // 요약: 비율은 백분율, 초는 ms. 목표(SLO)도 같은 단위다
  const s = ready(c.summary, "summary");
  assert.equal(s.availabilityPct, 99.95);
  assert.equal(s.p95Ms, 184);
  assert.equal(s.errorRatePct, 0.12);
  assert.deepEqual(s.slo, { availabilityPct: 99.9, p95Ms: 300, errorRatePct: 1 });

  // 호스트: DB는 실제 역할(recovery 지표), 수집이 늦은 bot-1은 STALE, 디스크는 사용률로
  const hosts = ready(c.hosts, "hosts");
  assert.deepEqual(hosts.map((h) => [h.id, h.role]), [["app-1", "APP"], ["app-2", "APP"], ["db-primary", "DB_PRIMARY"], ["db-standby", "DB_STANDBY"], ["bot-1", "BOT_OBS"]]);
  const app1 = hosts[0];
  assert.equal(app1.cpuPct, 62);
  assert.equal(app1.diskUsedPct, 38);
  assert.equal(app1.cpuCredits, null, "미수집을 0으로 채우지 않는다");
  assert.equal(app1.partition, null, "마켓 묶음은 서버 한 판에 없다");
  assert.equal(hosts[4].collect, "STALE");

  // 앱 계층(ADR 0030 정정): 용량 계획 min 2·max 3, 늘 켜진 app-1·app-2와 자동 자리 app-3
  const tier = ready(c.readTier, "readTier");
  assert.deepEqual([tier.min, tier.desired, tier.max], [2, 2, 3]);
  assert.deepEqual(tier.fixed, ["app-1", "app-2"]);
  assert.deepEqual(tier.nodes.map((n) => [n.id, n.state]), [["app-3", "OFF"]]);
  assert.equal(tier.avgCpuPct, 80);

  // 경보: 등급 모름(other)은 장애로 그리고 문구에 적는다. 임계값 키와 가리키는 자리를 잇는다
  const alerts = ready(c.alerts, "alerts");
  const other = alerts.find((a) => a.name === "LinuxServiceOdd");
  assert.ok(other);
  assert.equal(other.severity, "critical");
  assert.match(other.detail, /등급 라벨 없음/);
  const cpu = alerts.find((a) => a.name === "ReadTierCpuHigh");
  assert.equal(cpu?.thresholdKey, "read.scale_out_cpu");
  assert.deepEqual(cpu?.ref, { kind: "HOST", id: "app-1" });
  assert.equal(alerts.find((a) => a.name === "LinuxInodesLow")?.state, "SILENCED");

  // 임계값: 비율은 %, 초는 시간(백업 지연), 미설정은 null. 서버만 싣는 in_use·rule_value도 옮긴다
  const reg = ready(c.thresholds, "thresholds");
  const th = (k: string) => reg.items.find((t) => t.key === k);
  assert.equal(th("read.scale_out_cpu")?.value, 60);
  assert.equal(th("host.disk_free_alert")?.value, 15);
  assert.equal(th("host.disk_free_alert")?.min, 10);
  assert.equal(th("backup.delay_alert")?.value, 26);
  assert.equal(th("backup.delay_alert")?.unit, "시간");
  assert.equal(th("host.cpu_alert")?.value, null, "미설정 항목");
  assert.equal(th("host.cpu_alert")?.inUse, false);
  assert.equal(th("read.scale_in_cpu")?.ruleValue, 30);

  // 운영 작업: Databasus·인증서는 원천 연결 전, 백업 지표는 마지막 성공과 복원 검증만
  const jobs = ready(c.jobs, "jobs");
  assert.ok(jobs.backup);
  assert.equal(jobs.backup.tool, "백업 지표");
  assert.equal(jobs.backup.restoreCheck.outcome, "SUCCESS");
  assert.equal(jobs.backup.canRun, false, "Databasus 연결 전이라 즉시 실행을 막는다");
  assert.equal(jobs.certificates, null);
  assert.equal(jobs.dailyReport.lastRunAt, "2026-09-24T22:50:04.000Z");

  // AI 서버: 서버가 잰 호출 지연과 적재 모델. 장비 메모리와 GPU는 원천이 없다
  const ai1 = ready(c.aiServer, "aiServer");
  assert.equal(ai1.link, "OK");
  assert.equal(ai1.assumed, false);
  assert.match(ai1.runtime, /^Ollama/);
  assert.equal(ai1.memTotalGb, null);
  assert.equal(ai1.gpuPct, null);
  assert.equal(ai1.p95Ms, 4);

  // 추세: 한 판은 24시간만 싣는다. 1·6시간은 세부 조회 전이라 빈 점열이다
  const tr = ready(c.trends, "trends");
  assert.equal(tr[24].availability.points.length, 120);
  assert.equal(tr[24].incidents.points.length, 24);
  assert.equal(tr[1].availability.points.length, 0);

  // 거래 칸은 관제 서버 계약에 없다
  assert.equal(c.trading.state, "NOT_CONNECTED");
  // 사건 수가 있는데 목록을 아직 읽지 않았으면 실패 모양으로 그 사실을 적는다(빈 목록으로 채우지 않는다)
  assert.equal(c.incidents.state, "UNAVAILABLE");
  // AI 칸은 모드·정지가 있으므로 세운다. 조치 요청을 아직 읽지 못했으면 상황 문장에 적는다
  const desk = ready(c.ai, "ai");
  assert.equal(desk.mode, "MANUAL");
  assert.match(desk.situation, /불러오지 못했습니다/);
});

test("관제 모듈만 켜고 원천이 없는 서버: 칸마다 원천 설정 없음이고 사건 0건은 빈 목록이 사실이다", () => {
  const w = body<WireOverview>("overview-bare");
  const c = boardOf(w, emptyExtras(), Date.now());
  for (const key of ["summary", "hosts", "alerts", "aiServer"] as const) {
    assert.notEqual(c[key].state, "READY", key);
  }
  // 임계값 레지스트리와 사건 추세는 DB에 있어 원천이 없어도 읽힌다. Prometheus 계열 추세만 빈 점열이다
  assert.equal(c.thresholds.state, "READY");
  const tr = ready(c.trends, "trends");
  assert.equal(tr[24].availability.points.length, 0);
  assert.ok(tr[24].incidents.points.length > 0);
  assert.equal(c.origin, null);
  assert.deepEqual(ready(c.incidents, "incidents"), { incidents: [], events: [] });
  // LLM이 없어도 모드와 정지는 쓸 수 있어 AI 칸을 세운다
  const desk = ready(c.ai, "ai");
  assert.match(desk.situation, /연결 전/);
  assert.ok(c.sources && c.sources.every((s) => s.state !== "READY" || s.name === "alertmanager" || s.name === "prometheus"));
});

test("한 판과 세부 조회를 잇는다: 첫 판은 여섯 건까지, 그 뒤는 못 읽은 것과 서명이 바뀐 것을 두 건씩 읽는다", async () => {
  await withServer(fullServer, async (calls) => {
    const api = new HttpAdminApi("http://admin.test");
    let c = await api.getControlCenter();
    const first = calls.map((x) => x.path);
    assert.equal(first[0], "/admin/v1/msp/overview");
    assert.equal(first.length, 7, `첫 판의 세부 조회는 여섯 건: ${first.join(",")}`);
    assert.ok(first.includes("/admin/v1/msp/ai/actions?status=ALL&limit=30"));
    assert.ok(first.includes("/admin/v1/msp/incidents?limit=30"));
    // 남은 세부는 다음 판들에서 두 건씩 채운다. 더 읽을 것이 없을 때까지 판을 몇 번 더 읽는다
    for (let i = 0; i < 8; i += 1) {
      const before = calls.length;
      c = await api.getControlCenter();
      const extra = calls.length - before - 1;
      assert.ok(extra <= 2, `한 판에 붙는 세부는 두 건까지: ${extra}`);
      if (extra === 0) break;
    }

    // 조치 카드: 서버 조치 요청을 앱 계층 2~3대 모델의 말로
    const desk = ready(c.ai, "ai");
    const titles = desk.proposals.map((p) => [p.status, p.title]);
    assert.ok(titles.some(([, t]) => /^app-3 켜기 \(앱 2 → 3대\)$/.test(t)), JSON.stringify(titles));
    const expired = desk.proposals.find((p) => p.status === "EXPIRED");
    assert.equal(expired?.hold?.rule, "M9");
    // 판단 목록은 과정을 싣지 않고 상세를 열 때 읽는다
    assert.ok(desk.history.length > 0 && desk.history.every((h) => h.lazy === true && h.trace === null));
    assert.equal(desk.limits.length, 5);
    assert.deepEqual(desk.limitHistory.map((l) => [l.key, l.from, l.to, l.apply]), [["botCooldownMin", 10, 20, null]], "마이그레이션 확정값은 변경 이력이 아니다");

    // 사건 목록과 최근 사건의 기록 줄
    const log = ready(c.incidents, "incidents");
    assert.ok(log.incidents.length >= 1);
    assert.ok(log.incidents.some((i) => i.eventsLoaded === true));
    assert.ok(log.events.some((e) => e.kind === "ACKED" && e.actor === "OPERATOR"));
    // 임계값 이력: 초기값(SEED)은 빼고, 되돌린 변경을 표시한다
    const reg = ready(c.thresholds, "thresholds");
    assert.ok(reg.history.every((h) => h.via !== "SEED"));
    const aiChange = reg.history.find((h) => h.via === "AI_APPROVED");
    assert.equal(aiChange?.reverted, true);
    assert.deepEqual([aiChange?.from, aiChange?.to], [30, 35]);

    // 두 번째 판(같은 시각): 서명이 그대로면 세부 조회를 더 부르지 않는다
    const before = calls.length;
    await api.getControlCenter();
    assert.equal(calls.length, before + 1, "한 판 한 건뿐이다");
  });
});

test("세부 조회 일정: 서명이 그대로면 30초에 한 건, 바뀌면 곧바로 두 건까지 읽는다", async () => {
  const w = body<WireOverview>("overview-full");
  let now = 1_000_000;
  const got: string[] = [];
  const feed = new OpsFeed(async <T,>(p: string) => {
    got.push(p);
    const s = fullServer(p);
    if (!s) throw new AdminError("not_found", "없음", null, 404);
    return s.body as T;
  }, () => now);
  await feed.update(w);
  assert.ok(got.length <= 6, "첫 판은 여섯 건까지");
  // 아직 못 읽은 세부를 다 읽을 때까지 판을 받는다(한 번에 두 건)
  for (let i = 0; i < 10; i += 1) {
    const before = got.length;
    now += 1_000;
    await feed.update(w);
    assert.ok(got.length - before <= 2);
    if (got.length === before) break;
  }
  got.length = 0;
  now += 5_000;
  await feed.update(w);
  assert.equal(got.length, 0, "다 읽은 뒤 30초 안에는 수명이 지나지 않은 세부를 읽지 않는다");
  // 열린 조치 수가 바뀌면 서명이 바뀐다
  const changed: WireOverview = { ...w, ai: { ...w.ai, open_actions: 2 } };
  await feed.update(changed);
  assert.ok(got.length >= 1 && got.length <= 2, `바뀐 서명의 세부만 읽는다: ${got.join(",")}`);
  assert.ok(got.some((p) => p.startsWith("/admin/v1/msp/ai/actions")));
});

test("판단 상세: 서버가 남긴 규칙만 검사표에 싣고 근거 스냅숏을 이름 있는 칩으로 옮긴다", () => {
  const d = judgmentOf(body("ai-decision-detail"), { desired: 2, alertNames: new Map() });
  assert.ok(d.trace);
  assert.equal(d.lazy, false);
  assert.equal(d.trace.candidates.length, 1);
  assert.equal(d.trace.candidates[0].title, "app-3 켜기 (앱 2 → 3대)");
  assert.equal(d.trace.candidates[0].confidencePct, 90, "판단의 확신도를 요청의 확신도로 쓴다");
  // 수동 모드 판단의 대기 요청은 승인 대기 한 행이다. 서버가 남기지 않은 규칙별 통과는 지어내지 않는다
  assert.deepEqual(d.trace.checks.map((r) => [r.rule, r.results[0].state]), [["MODE", "HOLD"]]);
  const labels = d.trace.evidence.map((e) => e.label);
  assert.ok(labels.includes("앱 계층 평균 CPU"), labels.join(","));
  assert.ok(labels.includes("5xx 비율"));
  assert.ok(d.trace.evidence.some((e) => e.label === "bot-1 CPU" && e.value.endsWith("지연")), "60초 넘은 표본은 지연을 적는다");

  const dropped = judgmentOf(body("ai-decision-dropped"), { desired: 2, alertNames: new Map() });
  assert.match(dropped.trace?.result ?? "", /DB_PROMOTE 버림\(UNKNOWN_KIND\)/);
});

test("임계값 값 옮기기: 화면 단위와 서버 단위가 왕복하고 티켓 대상은 끝 0 없는 십진 표기다", () => {
  assert.equal(thresholdToWire("host.disk_free_alert", 20), "0.2");
  assert.equal(thresholdToWire("read.scale_in_cpu", 35), "0.35");
  assert.equal(thresholdToWire("host.cpu_alert", 5.5), "0.055");
  assert.equal(thresholdToWire("backup.delay_alert", 26), "93600");
  assert.equal(thresholdToWire("db.replication_lag_alert", 30), "30");
  assert.equal(thresholdToView("backup.delay_alert", "93600"), 26);
  assert.equal(thresholdToView("read.scale_out_cpu", "0.6"), 60);
  assert.equal(plainDecimal(0.1 + 0.2), "0.3", "부동소수점 꼬리를 넷째 자리에서 자른다");
});

test("조작: 임계값 조치 승인은 ops.threshold.set 티켓을 새 값에 묶어 발급하고 같은 요청에 싣는다", async () => {
  const issued: unknown[] = [];
  await withServer((p, init) => {
    if (p === "/admin/v1/msp/overview") return snap("overview-full");
    if (p.startsWith("/admin/v1/msp/ai/actions?")) return snap("ai-actions-open");
    if (p === "/admin/v1/approvals" && init.method === "POST") {
      issued.push(JSON.parse(String(init.body)));
      return { status: 201, body: { approval_id: 9, approval_token: "APV-test", expires_at: "2026-09-25T04:00:00.000Z" } };
    }
    if (p === "/admin/v1/msp/ai/actions/1/approve") return snap("ai-action-approve-threshold");
    return fullServer(p);
  }, async (calls) => {
    const api = new HttpAdminApi("http://admin.test");
    // operator_id 는 세션에서 오므로 세션을 흉내 낸다(발급 경로는 mTLS 게이트웨이 몫이라 여기서 보지 않는다)
    (api as unknown as { session: unknown }).session = { operatorId: "op-1", displayName: "op", roles: ["SUPER"], token: "t", issuedAt: "", expiresAt: "2999-01-01T00:00:00.000Z" };
    await api.getControlCenter();
    await api.decideAiProposal("1", "APPROVE");
    assert.deepEqual(issued, [{ requester_operator_id: "op-1", action: "ops.threshold.set", target: "read.scale_in_cpu:0.35" }]);
    const approve = calls.find((x) => x.path === "/admin/v1/msp/ai/actions/1/approve");
    assert.deepEqual(approve?.body, { operator_id: "op-1", approval_token: "APV-test" });
  });
});

test("조작: 자동 모드 켜기, 즉시 끄기, 정지·재개, 거절·나중에는 서버 경로와 본문 그대로다", async () => {
  await withServer((p, init) => {
    if (p === "/admin/v1/approvals" && init.method === "POST") return { status: 201, body: { approval_id: 3, approval_token: "APV-auto" } };
    if (p === "/admin/v1/msp/ai/mode/auto") return snap("ai-mode-auto-post");
    if (p === "/admin/v1/msp/ai/mode/manual") return snap("ai-mode-manual-post");
    if (p === "/admin/v1/msp/ai/stop") return snap("ai-stop");
    if (p === "/admin/v1/msp/ai/resume") return snap("ai-resume");
    if (p.endsWith("/reject")) return snap("ai-action-reject");
    if (p.endsWith("/defer")) return snap("ai-action-defer");
    if (p.endsWith("/ack")) return snap("alert-ack");
    if (p.endsWith("/silence")) return snap("alert-silence");
    return null;
  }, async (calls) => {
    const api = new HttpAdminApi("http://admin.test");
    (api as unknown as { session: unknown }).session = { operatorId: "op-1", displayName: "op", roles: ["SUPER"], token: "t", issuedAt: "", expiresAt: "2999-01-01T00:00:00.000Z" };
    await api.enableAiAuto(4, "야간 관제");
    await api.stopAiAuto();
    await api.stopAi();
    await api.resumeAi("모델 교체 확인");
    await api.decideAiProposal("7", "REJECT");
    await api.decideAiProposal("7", "DEFER");
    await api.ackAlert("a1b2c3d4e5f60001");
    await api.silenceAlert("a1b2c3d4e5f60001", 30);
    const sent = calls.map((c) => [c.method, c.path, c.body]);
    assert.deepEqual(sent, [
      ["POST", "/admin/v1/approvals", { requester_operator_id: "op-1", action: "ops.ai.auto", target: "AUTO:4" }],
      ["POST", "/admin/v1/msp/ai/mode/auto", { operator_id: "op-1", approval_token: "APV-auto", hours: 4, reason: "야간 관제" }],
      ["POST", "/admin/v1/msp/ai/mode/manual", { operator_id: "op-1" }],
      ["POST", "/admin/v1/msp/ai/stop", { operator_id: "op-1" }],
      ["POST", "/admin/v1/msp/ai/resume", { operator_id: "op-1", reason: "모델 교체 확인" }],
      ["POST", "/admin/v1/msp/ai/actions/7/reject", { operator_id: "op-1" }],
      ["POST", "/admin/v1/msp/ai/actions/7/defer", { operator_id: "op-1" }],
      ["POST", "/admin/v1/msp/alerts/a1b2c3d4e5f60001/ack", { operator_id: "op-1" }],
      ["POST", "/admin/v1/msp/alerts/a1b2c3d4e5f60001/silence", { operator_id: "op-1", minutes: 30, reason: "관제센터에서 30분 보류" }],
    ]);
  });
});

test("조작: 임계값·한도 변경은 승인 흐름의 티켓 대상과 요청 본문이 같은 서버 단위 값을 쓴다", async () => {
  await withServer((p, init) => {
    if (p === "/admin/v1/approvals" && init.method === "POST") return { status: 201, body: { approval_id: 5, approval_token: "APV-x" } };
    if (p === "/admin/v1/msp/thresholds/host.disk_free_alert") return snap("threshold-set");
    if (p === "/admin/v1/msp/thresholds/changes/10/revert") return snap("threshold-revert");
    if (p === "/admin/v1/msp/ai/limits/bot_cooldown_min") return snap("ai-limits-put");
    return null;
  }, async (calls) => {
    const api = new HttpAdminApi("http://admin.test");
    (api as unknown as { session: unknown }).session = { operatorId: "op-1", displayName: "op", roles: ["SUPER"], token: "t", issuedAt: "", expiresAt: "2999-01-01T00:00:00.000Z" };
    const set = { action: "ops.threshold.set", key: "host.disk_free_alert", label: "디스크", unit: "%", from: 15, to: 20, reason: "증설 전 조기 경보", revertOf: null, proposalId: null } as const;
    const a = await api.issueApproval("op-1", "ops.threshold.set", set);
    await api.consumeApproval(a);
    const back = { ...set, key: "read.scale_in_cpu", from: 35, to: 30, reason: "되돌리기", revertOf: "10" } as const;
    await api.consumeApproval(await api.issueApproval("op-1", "ops.threshold.set", back));
    const lim = { action: "ops.ai.limits", key: "botCooldownMin", label: "봇 부하 쿨다운", unit: "분", from: 10, to: 20, reason: "간격 확대", revertOf: null } as const;
    await api.consumeApproval(await api.issueApproval("op-1", "ops.ai.limits", lim));
    const sent = calls.map((c) => [c.method, c.path, c.body]);
    assert.deepEqual(sent, [
      ["POST", "/admin/v1/approvals", { requester_operator_id: "op-1", action: "ops.threshold.set", target: "host.disk_free_alert:0.2" }],
      ["POST", "/admin/v1/msp/thresholds/host.disk_free_alert", { operator_id: "op-1", approval_token: "APV-x", reason: "증설 전 조기 경보", value: "0.2" }],
      ["POST", "/admin/v1/approvals", { requester_operator_id: "op-1", action: "ops.threshold.set", target: "read.scale_in_cpu:0.3" }],
      ["POST", "/admin/v1/msp/thresholds/changes/10/revert", { operator_id: "op-1", approval_token: "APV-x", reason: "되돌리기" }],
      ["POST", "/admin/v1/approvals", { requester_operator_id: "op-1", action: "ops.ai.limits", target: "bot_cooldown_min:20" }],
      ["PUT", "/admin/v1/msp/ai/limits/bot_cooldown_min", { operator_id: "op-1", approval_token: "APV-x", value: 20, reason: "간격 확대" }],
    ]);
  });
});

test("조작 거부: 서버 오류 봉투의 이름과 문장을 그대로 올린다(정책 거부 409)", async () => {
  await withServer((p) => (p.endsWith("/approve") ? snap("error-policy-denied") : null), async () => {
    const api = new HttpAdminApi("http://admin.test");
    (api as unknown as { session: unknown }).session = { operatorId: "op-1", displayName: "op", roles: ["SUPER"], token: "t", issuedAt: "", expiresAt: "2999-01-01T00:00:00.000Z" };
    await assert.rejects(api.decideAiProposal("9", "APPROVE"), (e: unknown) =>
      e instanceof AdminError && e.code === "msp_policy_denied" && e.status === 409 && /최대 3대 밖입니다/.test(e.message));
  });
});

test("AI 상담: 서버 답의 머리말을 먼저 싣고 근거 id를 화면 모양으로, 실행 부탁은 화면 규칙으로 가른다", () => {
  const ok = chatReplyOf(body("ai-chat-answered"), "app-3를 켜 주세요");
  assert.equal(ok.state, "OK");
  assert.match(ok.note ?? "", /60초보다 오래되었습니다/);
  assert.deepEqual(ok.evidence.map((e) => [e.id, e.kind]), [
    ["metric:read_tier", "METRIC"],
    ["threshold:read.scale_out_cpu", "THRESHOLD"],
    ["alert:a1b2c3d4e5f60001", "ALERT"],
  ]);
  assert.equal(ok.declined, true);
  assert.equal(ok.guide, "READ_NODES");
  assert.equal(ok.model, "test-model");

  const stopped = chatReplyOf(body("ai-chat-stopped"), "지금 상태는?");
  assert.equal(stopped.state, "STOPPED");
  assert.equal(stopped.answer, "");
  assert.match(stopped.note ?? "", /정지 중/);
  assert.equal(stopped.declined, false);
});

test("보고서: 읽기 프록시의 요약만 옮기고 본문은 SUMMARY로 알린다. 전송 상태는 보고 서비스 수를 합친다", () => {
  const desk = reportDeskOf(body("reports"), body("reports-status"), "");
  const d = ready(desk, "reports");
  assert.equal(d.reports.length, 3);
  assert.ok(d.reports.every((r) => r.detail === "SUMMARY"));
  const daily = d.reports.find((r) => r.kind === "daily");
  assert.equal(daily?.intervention.label, "관찰 유지");
  assert.ok(daily?.links.pdf?.startsWith("https://"));
  assert.equal(d.delivery.pending, 1);
  assert.equal(d.delivery.dead, 0);
  assert.equal(d.delivery.lastDailySentAt, "2026-09-25T00:50:04.000Z");
});

test("사건 기록 줄: 판에 없는 사건은 열 때 서버 사건 상세에서 읽는다", async () => {
  await withServer((p) => (p === "/admin/v1/msp/incidents/1" ? snap("incident-detail") : null), async () => {
    const api = new HttpAdminApi("http://admin.test");
    const events = await api.getIncidentEvents("1");
    assert.deepEqual(events?.map((e) => [e.kind, e.actor, e.incidentId]), [["OPENED", "SYSTEM", "1"], ["ACKED", "OPERATOR", "1"]]);
  });
});

/** 픽스처가 서버 계약의 한 판 모양인지 한 번 본다. 픽스처를 다시 뜨면 이 시험이 모양의 어긋남을 먼저 알린다 */
test("픽스처: 한 판은 ETag를 싣고, 304는 본문이 없다", () => {
  const full = snap("overview-full");
  assert.equal(full.status, 200);
  assert.match(full.etag ?? "", /^W\/"[0-9a-f]{32}"$/);
  assert.equal(snap("overview-304").status, 304);
  const c: ControlCenter = boardOf(body("overview-full"), emptyExtras(), Date.now());
  assert.ok(c.sources?.some((s) => s.name === "teams" && s.state === "READY"));
});
