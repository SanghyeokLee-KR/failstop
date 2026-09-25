/**
 * 관제센터 시뮬레이터(opsSim.ts) 규칙 검증. 시계를 직접 넘겨 시나리오 진행, 수동·자동 모드,
 * 관찰 창의 자동 되돌림, 경보 보류 규칙, 임계값 연동을 결정적으로 본다(ADR 0035, 관리자 설계 4-4).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { AI_ACTOR, OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import { AdminError } from "../lib/admin/api";
import type { AiDesk, AiServer, ControlCenter, IncidentLog, OpsAlert, OpsHost, OpsTrends, ThresholdRegistry } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const VIEWER: OpsActorRef = { id: "op-02", name: "이오퍼" };
// 로컬 정오. 하루 경계와 새벽 백업 시각에 걸리지 않게 한다
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}
const ai = (c: ControlCenter) => ready<AiDesk>(c.ai);
const alerts = (c: ControlCenter) => ready<OpsAlert[]>(c.alerts);
const log = (c: ControlCenter) => ready<IncidentLog>(c.incidents);
const reg = (c: ControlCenter) => ready<ThresholdRegistry>(c.thresholds);

function threshold(c: ControlCenter, key: string): number {
  const t = reg(c).items.find((x) => x.key === key);
  assert.ok(t, key);
  return t.value;
}

/** 따라잡기 상한(900틱)에 걸리지 않게 10분씩 나눠 시계를 넘긴다 */
function run(sim: OpsSim, toSec: number): void {
  for (let s = 600; s < toSec; s += 600) sim.advance(at(s));
  sim.advance(at(toSec));
}

const open0 = (c: ControlCenter) => ready<AiDesk>(c.ai).proposals.filter((p) => p.status === "PROPOSED");

async function rejectsWith(fn: () => unknown, code: string): Promise<void> {
  await assert.rejects(async () => fn(), (e: unknown) => e instanceof AdminError && e.code === code);
}

test("평시 판은 경보와 조치안 없이 이상 없음이고 무엇을 보고 판단했는지 근거를 싣는다", () => {
  const sim = new OpsSim(T0);
  const c = sim.snapshot(at(6));
  assert.equal(c.origin, "SIM");
  assert.equal(alerts(c).length, 0);
  assert.equal(ai(c).proposals.filter((p) => p.status === "PROPOSED").length, 0);
  assert.match(ai(c).situation, /모든 지표가 임계 안/);
  assert.ok(ai(c).evidence.length >= 3, "이상 없음에도 근거가 있어야 공허 판정이 아니다");
  assert.equal(ai(c).mode, "MANUAL");
});

test("조회 급증은 경보와 AI 판단(app-3 켜기, 증설 임계 60→55)을 거쳐 수동 모드에서 운영자를 기다린다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const c = sim.snapshot(at(20));
  const names = alerts(c).map((a) => a.name);
  assert.ok(names.includes("앱 계층 CPU 높음"));
  assert.ok(open0(c).some((p) => p.title === "app-3 켜기 (앱 2 → 3대)"), "조치안은 app-3 켜기로 적는다");
  const open = ai(c).proposals.filter((p) => p.status === "PROPOSED");
  assert.deepEqual(open.map((p) => p.kind).sort(), ["READ_NODES", "THRESHOLD"]);
  assert.equal(open[0].kind, "READ_NODES", "서비스에 가까운 조치가 앞에 선다");
  assert.ok(open.some((p) => p.title.includes("60% → 55%")));
  assert.equal(ready<{ desired: number }>(c.readTier).desired, 2, "수동 모드는 스스로 실행하지 않는다");
  assert.equal(reg(c).items.find((t) => t.key === "read.scale_out_cpu")?.aiSuggested, 55);
});

test("운영자가 승인하면 노드가 늘고 경보가 풀려 사건이 해제되며 누가 언제 했는지 남는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const p = ai(sim.snapshot(at(20))).proposals.find((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.ok(p);
  sim.decide(p.id, "APPROVE", OP, at(20));
  const c = sim.snapshot(at(45));
  assert.equal(ready<{ desired: number }>(c.readTier).desired, 3);
  assert.equal(alerts(c).length, 0);
  const l = log(c);
  const inc = l.incidents[0];
  assert.ok(inc.resolvedAt !== null, "경보가 모두 풀리면 사건이 닫힌다");
  const kinds = l.events.filter((e) => e.incidentId === inc.id).map((e) => e.kind);
  for (const k of ["OPENED", "AI_JUDGED", "APPROVED", "ACTED", "RESOLVED"] as const) assert.ok(kinds.includes(k), k);
  assert.ok(l.events.some((e) => e.kind === "APPROVED" && e.actorName === "김수퍼"));
});

test("임계값 조치안은 승인 티켓 없이 실행하지 않는다", async () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const p = ai(sim.snapshot(at(20))).proposals.find((x) => x.kind === "THRESHOLD");
  assert.ok(p);
  await rejectsWith(() => sim.decide(p.id, "APPROVE", OP, at(20)), "admin_forbidden");
  sim.decide(p.id, "APPROVE", OP, at(20), "apr-test");
  const c = sim.snapshot(at(21));
  assert.equal(threshold(c, "read.scale_out_cpu"), 55);
  const change = reg(c).history[0];
  assert.equal(change.via, "AI_APPROVED");
  assert.equal(change.approvalId, "apr-test");
});

test("거절한 조치안은 10분 동안 다시 내지 않는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const p = ai(sim.snapshot(at(20))).proposals.find((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.ok(p);
  sim.decide(p.id, "REJECT", OP, at(20));
  const later = ai(sim.snapshot(at(60))).proposals.filter((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.equal(later.length, 0);
});

test("자동 모드는 같은 판단 두 번 뒤 허용 조치 하나만 실행하고 관찰 창과 쿨다운을 건다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  const c = sim.snapshot(at(24));
  const desk = ai(c);
  const done = desk.proposals.find((p) => p.status === "AUTO_EXECUTED");
  assert.ok(done, "app-3 켜기는 자동 실행된다");
  assert.equal(done.kind, "READ_NODES");
  assert.ok(done.revertibleUntil !== null);
  assert.ok(desk.observation !== null, "관찰 창이 열린다");
  assert.ok(desk.cooldowns.some((x) => x.kind === "READ_NODES"));
  assert.equal(desk.autoUsed, 1);
  const held = desk.proposals.find((p) => p.status === "PROPOSED" && p.kind === "THRESHOLD");
  assert.ok(held, "임계값 제안은 카드로 남는다");
  assert.match(held.autoHold ?? "", /한 번에 한 조치|확신도/);
  assert.ok(log(c).events.some((e) => e.kind === "AUTO_ACTED" && e.actor === "AI"));
  assert.ok(log(c).events.some((e) => e.kind === "NOTIFIED"), "자동 조치는 Teams 알림을 남긴다");
});

test("야간 축소 뒤 조회가 몰리면 관찰 창 안에서 자동으로 되돌리고 수동으로 바꾼다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "야간", OP, "apr-auto", at(0));
  sim.startScenario("NIGHT_SCALE_IN", at(0));
  const mid = sim.snapshot(at(18));
  assert.equal(ready<{ desired: number }>(mid.readTier).desired, 2, "낮은 부하에서 한 대를 자동으로 줄인다");
  const c = sim.snapshot(at(40));
  const desk = ai(c);
  assert.equal(desk.mode, "MANUAL", "악화하면 수동으로 바뀐다");
  assert.ok(desk.proposals.some((p) => p.status === "AUTO_REVERTED"));
  assert.equal(ready<{ desired: number }>(c.readTier).desired, 3, "축소를 되돌린다");
  const kinds = log(c).events.map((e) => e.kind);
  assert.ok(kinds.includes("AUTO_REVERTED"));
  assert.ok(log(c).events.some((e) => e.kind === "MODE" && e.text.includes("수동")));
  const inc = log(c).incidents[0];
  assert.match(inc.title, /자동 조치 뒤 악화/);
  const own = log(c).events.filter((e) => e.incidentId === inc.id).map((e) => e.kind);
  assert.ok(own.includes("AUTO_ACTED") && own.includes("AUTO_REVERTED"), "원인이 된 자동 실행과 되돌림이 한 사건에 묶인다");
  const reverted = desk.proposals.find((p) => p.status === "AUTO_REVERTED");
  assert.ok(reverted && reverted.decidedAt !== null && Date.parse(reverted.decidedAt) > T0 + 20_000, "카드에는 되돌린 시각이 온다");
});

test("자동 모드 켜기는 유효 시간·사유를 검사하고 유효 시간이 지나면 수동으로 돌아간다", async () => {
  const sim = new OpsSim(T0);
  await rejectsWith(() => sim.checkAuto(3, "사유", at(0)), "invalid_parameter");
  await rejectsWith(() => sim.checkAuto(2, " ", at(0)), "invalid_parameter");
  sim.enableAuto(1, "시험", OP, "apr-auto", at(0));
  await rejectsWith(() => sim.checkAuto(1, "다시", at(1)), "invalid_parameter");
  run(sim, 3660);
  assert.equal(ai(sim.snapshot(at(3662))).mode, "MANUAL");
});

test("자동 끄기는 자동 모드를 바로 수동으로 바꾸고 기록을 남긴다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(8, "시험", OP, "apr-auto", at(0));
  sim.stopAuto(VIEWER, at(5));
  const c = sim.snapshot(at(6));
  assert.equal(ai(c).mode, "MANUAL");
  assert.ok(log(c).events.some((e) => e.kind === "MODE" && e.text.includes("자동 끄기") && e.actorName === "이오퍼"));
});

test("AI 즉시 정지는 수동 모드에서도 되고, 대기 조치안을 만료시키며 새 판단을 멈춘다", async () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const before = ai(sim.snapshot(at(20))).proposals.filter((p) => p.status === "PROPOSED");
  assert.ok(before.length > 0);
  sim.stopAi(VIEWER, at(20));
  const c = sim.snapshot(at(21));
  const desk = ai(c);
  assert.equal(desk.mode, "STOPPED");
  assert.equal(desk.stopped?.by, "이오퍼");
  assert.equal(desk.proposals.filter((p) => p.status === "PROPOSED").length, 0);
  const expired = desk.proposals.filter((p) => p.status === "EXPIRED");
  assert.equal(expired.length, before.length, "대기 중이던 조치안이 모두 정지로 만료된다");
  assert.ok(expired.every((p) => p.outcome === "AI 정지로 만료"));
  await rejectsWith(() => sim.decide(expired[0].id, "APPROVE", OP, at(21), "apr-x"), "invalid_parameter");
  assert.ok(reg(c).items.every((t) => t.aiSuggested === null), "만료된 임계값 제안의 AI 표식도 걷힌다");
  assert.ok(log(c).events.some((e) => e.kind === "MODE" && e.text.startsWith("모드 · AI 즉시 정지") && e.actorName === "이오퍼"));

  const later = ai(sim.snapshot(at(60)));
  assert.equal(later.proposals.filter((p) => p.status === "PROPOSED").length, 0, "정지 중에는 새 조치안이 없다");
  assert.equal(later.thinking, false);
  assert.equal(later.evidence.length, 0);
  assert.match(later.situation, /AI 정지 중/);
  await rejectsWith(() => sim.checkAuto(2, "켜기", at(61)), "invalid_parameter");
  await rejectsWith(() => sim.stopAi(OP, at(61)), "invalid_parameter");
});

test("자동 모드에서 정지하면 자동이 풀리고, 진행 중 관찰 창은 끝까지 보며 악화하면 되돌린다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "야간", OP, "apr-auto", at(0));
  sim.startScenario("NIGHT_SCALE_IN", at(0));
  let sec = 0;
  for (let s = 1; s <= 30 && sec === 0; s += 1) {
    if (ai(sim.snapshot(at(s))).proposals.some((p) => p.status === "AUTO_EXECUTED")) sec = s;
  }
  assert.ok(sec > 0);
  sim.stopAi(VIEWER, at(sec + 1));
  const stopped = ai(sim.snapshot(at(sec + 2)));
  assert.equal(stopped.mode, "STOPPED");
  assert.equal(stopped.auto, null, "자동 모드가 곧바로 풀린다");
  assert.ok(stopped.observation !== null, "관찰 창은 남는다");
  const c = sim.snapshot(at(60));
  assert.equal(ai(c).mode, "STOPPED", "되돌려도 정지 상태를 유지한다");
  assert.ok(ai(c).proposals.some((p) => p.status === "AUTO_REVERTED"), "정지 중에도 악화하면 되돌린다");
  assert.equal(ready<{ desired: number }>(c.readTier).desired, 3);
  const note = log(c).events.find((e) => e.kind === "NOTIFIED" && e.text.includes("자동 되돌림"));
  assert.ok(note && !note.text.includes("수동 전환"), "정지 중 되돌림은 수동 전환이라고 적지 않는다");
});

test("정지 중 관찰 창이 악화 없이 끝나면 정지 문장에서 관찰 중이 사라진다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  assert.ok(ai(sim.snapshot(at(24))).observation !== null);
  sim.stopAi(OP, at(25));
  assert.match(ai(sim.snapshot(at(26))).situation, /관찰 중/);
  run(sim, 360);
  const desk = ai(sim.snapshot(at(361)));
  assert.equal(desk.observation, null);
  assert.equal(desk.mode, "STOPPED");
  assert.doesNotMatch(desk.situation, /관찰 중/);
});

test("AI 재개는 사유를 받아 수동으로만 돌리고, 자동은 다시 켜야 한다", async () => {
  const sim = new OpsSim(T0);
  await rejectsWith(() => sim.resumeAi("재개", OP, at(1)), "invalid_parameter");
  sim.stopAi(OP, at(1));
  await rejectsWith(() => sim.resumeAi(" ", OP, at(2)), "invalid_parameter");
  sim.resumeAi("원인 확인 끝", OP, at(3));
  const c = sim.snapshot(at(4));
  assert.equal(ai(c).mode, "MANUAL");
  assert.equal(ai(c).stopped, null);
  assert.ok(log(c).events.some((e) => e.kind === "MODE" && e.text.includes("AI 재개(수동)") && e.text.includes("원인 확인 끝")));
  sim.enableAuto(1, "다시", OP, "apr-auto", at(5));
  assert.equal(ai(sim.snapshot(at(6))).mode, "AUTO");
});

test("자동 조치 수는 자동을 켤 때마다 0부터 세고 쿨다운은 그대로 남는다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  assert.equal(ai(sim.snapshot(at(24))).autoUsed, 1);
  sim.stopAuto(OP, at(25));
  sim.enableAuto(2, "다시", OP, "apr-auto-2", at(26));
  const desk = ai(sim.snapshot(at(27)));
  assert.equal(desk.autoUsed, 0, "새 활성은 0부터 센다");
  assert.ok(desk.cooldowns.some((x) => x.kind === "READ_NODES"), "쿨다운은 모드와 무관하게 남는다");
});

test("이번 자동 모드의 상한에 닿으면 수동으로 바꾸고 Teams로 알린다", () => {
  const sim = new OpsSim(T0);
  sim.setLimit("autoCap", 5, 1, OP, "apr-l", "시험 상한", null, at(0));
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  const c = sim.snapshot(at(24));
  assert.equal(ai(c).autoUsed, 1);
  assert.equal(ai(c).mode, "MANUAL");
  assert.ok(log(c).events.some((e) => e.kind === "MODE" && e.text.includes("상한 1회 도달")));
  assert.ok(log(c).events.some((e) => e.kind === "NOTIFIED" && e.text.includes("상한 도달")));
});

test("AI 한도는 하드 범위 밖, 사유 없음, 승인 없음, AI 계정의 변경을 거절한다", async () => {
  const sim = new OpsSim(T0);
  await rejectsWith(() => sim.setLimit("confidencePct", 80, 96, OP, "a", "시험", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("confidencePct", 80, 69, OP, "a", "시험", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("autoCap", 5, 11, OP, "a", "시험", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("thresholdPerItem", 2, 4, OP, "a", "시험", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("botCooldownMin", 10, 4, OP, "a", "시험", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("backupDiskFreePct", 30, 51, OP, "a", "시험", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("confidencePct", 75, 85, OP, "a", "시험", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("confidencePct", 80, 85, OP, "a", " ", null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.setLimit("confidencePct", 80, 85, OP, "", "시험", null, at(1)), "admin_forbidden");
  await rejectsWith(() => sim.setLimit("confidencePct", 80, 70, AI_ACTOR, "a", "AI 스스로", null, at(1)), "admin_forbidden");
  assert.equal(ai(sim.snapshot(at(2))).limits.find((l) => l.key === "confidencePct")?.value, 80);
});

test("자동 모드 중 한도를 좁히면 곧바로, 넓히면 이번 자동 모드가 끝난 뒤 적용한다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.setLimit("confidencePct", 80, 85, OP, "a1", "좁힘", null, at(1));
  sim.setLimit("autoCap", 5, 8, OP, "a2", "넓힘", null, at(2));
  sim.setLimit("botCooldownMin", 10, 5, OP, "a3", "넓힘", null, at(3));
  sim.setLimit("backupDiskFreePct", 30, 40, OP, "a4", "좁힘", null, at(4));
  const limit = (c: ControlCenter, key: string) => ai(c).limits.find((l) => l.key === key);
  let c = sim.snapshot(at(5));
  assert.deepEqual([limit(c, "confidencePct")?.value, limit(c, "confidencePct")?.pending], [85, null]);
  assert.deepEqual([limit(c, "autoCap")?.value, limit(c, "autoCap")?.pending], [5, 8]);
  assert.deepEqual([limit(c, "botCooldownMin")?.value, limit(c, "botCooldownMin")?.pending], [10, 5]);
  assert.deepEqual([limit(c, "backupDiskFreePct")?.value, limit(c, "backupDiskFreePct")?.pending], [40, null]);
  assert.equal(ai(c).autoCap, 5, "넓힌 상한은 이번 활성에 쓰지 않는다");
  assert.deepEqual(ai(c).limitHistory.map((h) => h.apply), ["NOW", "NEXT_AUTO", "NEXT_AUTO", "NOW"]);
  sim.stopAuto(OP, at(6));
  c = sim.snapshot(at(7));
  assert.deepEqual([limit(c, "autoCap")?.value, limit(c, "autoCap")?.pending], [8, null]);
  assert.equal(limit(c, "botCooldownMin")?.value, 5);
  assert.ok(log(c).events.some((e) => e.kind === "ACTED" && e.text.startsWith("AI 한도 적용")));
  sim.setLimit("confidencePct", 85, 75, OP, "a5", "수동에서 넓힘", null, at(8));
  assert.equal(limit(sim.snapshot(at(9)), "confidencePct")?.value, 75, "자동 모드가 꺼져 있으면 곧바로 적용한다");
});

test("자동 모드 중 상한을 이미 쓴 횟수까지 낮추면 곧바로 수동으로 바뀐다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  assert.equal(ai(sim.snapshot(at(24))).autoUsed, 1);
  sim.setLimit("autoCap", 5, 1, OP, "a", "좁힘", null, at(25));
  assert.equal(ai(sim.snapshot(at(26))).mode, "MANUAL");
});

test("한도 변경은 이력에서 되돌리고 사건 기록에 남는다", () => {
  const sim = new OpsSim(T0);
  sim.setLimit("thresholdPerItem", 2, 1, OP, "a1", "좁힘", null, at(1));
  const change = ai(sim.snapshot(at(2))).limitHistory[0];
  sim.setLimit("thresholdPerItem", 1, 2, OP, "a2", "이력에서 되돌리기", change.id, at(3));
  const c = sim.snapshot(at(4));
  assert.equal(ai(c).limits.find((l) => l.key === "thresholdPerItem")?.value, 2);
  assert.equal(ai(c).limitHistory.find((h) => h.id === change.id)?.reverted, true);
  assert.ok(log(c).events.some((e) => e.kind === "ACTED" && e.text.startsWith("AI 한도 되돌리기")));
});

test("임계값을 바꾸면 경보 판정이 새 값을 쓰고, 허용 조치가 없으면 AI는 조치안을 내지 않는다", () => {
  const sim = new OpsSim(T0);
  sim.applyThreshold("host.mem_alert", 90, 70, OP, "apr-1", "시험", null, null, at(1));
  const c = sim.snapshot(at(12));
  assert.equal(threshold(c, "host.mem_alert"), 70);
  const mem = alerts(c).filter((a) => a.name === "메모리 부족");
  assert.ok(mem.length >= 1, "새 임계로 메모리 경보가 선다");
  assert.equal(ai(c).proposals.filter((p) => p.status === "PROPOSED").length, 0, "재시작 같은 금지 조치를 제안하지 않는다");
  assert.match(ai(c).situation, /운영자 확인/);
});

test("임계값 변경은 범위와 증설·축소 간격을 지키고 요청 뒤 바뀐 값은 거절한다", async () => {
  const sim = new OpsSim(T0);
  await rejectsWith(() => sim.applyThreshold("read.scale_out_cpu", 60, 99, OP, "a", "시험", null, null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.applyThreshold("read.scale_out_cpu", 60, 40, OP, "a", "시험", null, null, at(1)), "invalid_parameter");
  await rejectsWith(() => sim.applyThreshold("read.scale_out_cpu", 55, 50, OP, "a", "시험", null, null, at(1)), "invalid_parameter");
  sim.applyThreshold("read.scale_out_cpu", 60, 65, OP, "a", "시험", null, null, at(1));
  const c = sim.snapshot(at(2));
  const change = reg(c).history[0];
  sim.applyThreshold("read.scale_out_cpu", 65, 60, OP, "b", "되돌리기", change.id, null, at(3));
  const after = reg(sim.snapshot(at(4)));
  assert.equal(after.items.find((t) => t.key === "read.scale_out_cpu")?.value, 60);
  assert.equal(after.history[0].via, "REVERT");
  assert.equal(after.history.find((h) => h.id === change.id)?.reverted, true);
});

test("경보 보류는 최대 30분이고 같은 경보를 잇달아 보류하지 못한다", async () => {
  const sim = new OpsSim(T0);
  // 크레딧 경보 임계를 올려 bot-1 의 정보 등급 경보를 세운다
  sim.applyThreshold("host.cpu_credit_alert", 50, 200, OP, "apr-1", "시험", null, null, at(1));
  const a = alerts(sim.snapshot(at(8))).find((x) => x.name === "CPU 크레딧 잔량 낮음");
  assert.ok(a);
  assert.equal(a.severity, "info");
  await rejectsWith(() => sim.silenceAlert(a.id, 31, OP, at(9)), "invalid_parameter");
  sim.silenceAlert(a.id, 30, OP, at(9));
  const silenced = alerts(sim.snapshot(at(10))).find((x) => x.id === a.id);
  assert.equal(silenced?.state, "SILENCED");
  run(sim, 9 + 30 * 60 + 2);
  const back = alerts(sim.snapshot(at(9 + 30 * 60 + 3))).find((x) => x.id === a.id);
  assert.ok(back && back.state !== "SILENCED", "보류가 끝나면 다시 울린다");
  await rejectsWith(() => sim.silenceAlert(a.id, 10, OP, at(9 + 30 * 60 + 4)), "invalid_parameter");
});

test("확인은 경보를 끄지 않고 누가 확인했는지만 남긴다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const a = alerts(sim.snapshot(at(16)))[0];
  sim.ackAlert(a.id, OP, at(16));
  const after = alerts(sim.snapshot(at(17))).find((x) => x.id === a.id);
  assert.equal(after?.state, "ACKED");
  assert.equal(after?.ackBy, "김수퍼");
});

test("AI 조치의 행위자는 AI 전용 운영 계정이다", () => {
  assert.equal(AI_ACTOR.id, "ai-ops");
});

test("매칭 노드 두 대는 마켓 묶음을 나눠 맡고 다른 호스트에는 묶음이 없다", () => {
  const hosts = ready<OpsHost[]>(new OpsSim(T0).snapshot(at(3)).hosts);
  assert.deepEqual(hosts.map((h) => h.id), ["app-1", "app-2", "db-primary", "db-standby", "bot-1"]);
  const apps = hosts.filter((h) => h.role === "APP");
  assert.deepEqual(apps.map((h) => h.partition?.id), ["p0", "p1"]);
  const markets = apps.flatMap((h) => h.partition?.markets ?? []);
  assert.equal(new Set(markets).size, markets.length, "한 마켓은 한 노드만 맡는다");
  assert.ok(hosts.filter((h) => h.role !== "APP").every((h) => h.partition === null));
});

test("AI 판단 요청이 걸리는 동안 ai-1의 대기 요청과 GPU가 오르고 판단이 나면 응답으로 센다", () => {
  const sim = new OpsSim(T0);
  const idle = ready<AiServer>(sim.snapshot(at(3)).aiServer);
  assert.equal(idle.busy, false);
  assert.equal(idle.queue, 0);
  assert.equal(idle.model, "평가 중", "sim은 실제 모델 이름을 적지 않는다");
  sim.startScenario("READ_SURGE", at(0));
  let busy: AiServer | null = null;
  for (let s = 4; s <= 30 && busy === null; s += 1) {
    const srv = ready<AiServer>(sim.snapshot(at(s)).aiServer);
    if (srv.busy) busy = srv;
  }
  assert.ok(busy, "경보가 선 뒤 판단 중에는 요청이 걸린다");
  assert.ok((busy.queue ?? 0) >= 1);
  assert.ok((busy.gpuPct ?? 0) > (idle.gpuPct ?? 0) + 40);
  const later = ready<AiServer>(sim.snapshot(at(40)).aiServer);
  assert.ok(later.served > busy.served, "판단이 나면 응답 수가 는다");
  assert.ok(later.p50Ms !== null && later.p95Ms !== null && later.p95Ms >= later.p50Ms);
});

test("AI 정지 중에는 ai-1에 판단 요청을 보내지 않는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  sim.stopAi(OP, at(12));
  for (let s = 13; s <= 40; s += 1) assert.equal(ready<AiServer>(sim.snapshot(at(s)).aiServer).busy, false, `${s}초`);
});

test("추세는 범위마다 정한 점 수를 싣고 사건 기록에 있는 사건만 센다", () => {
  const sim = new OpsSim(T0);
  const t = ready<OpsTrends>(sim.snapshot(at(5)).trends);
  assert.deepEqual([t[1].p95.points.length, t[6].p95.points.length, t[24].p95.points.length], [60, 72, 96]);
  const today = log(sim.snapshot(at(5))).incidents;
  const inWindow = (hours: number) => today.filter((i) => Date.parse(i.openedAt) > at(5) - hours * 3_600_000).length;
  for (const h of [1, 6, 24] as const) assert.equal(t[h].incidents.current, inWindow(h), `${h}시간 사건 수`);
  assert.ok(t[24].incidents.previous !== null && t[24].incidents.previous > 0, "앞 24시간 구간도 기록에서 센다");
  for (const v of t[24].availability.points) assert.ok(v !== null && v <= 100 && v > 99.8);
});

test("조회 급증의 p95와 경보가 1시간 추세의 마지막 점에 잡힌다", () => {
  const sim = new OpsSim(T0);
  const before = ready<OpsTrends>(sim.snapshot(at(5)).trends)[1];
  sim.startScenario("READ_SURGE", at(5));
  run(sim, 95);
  const after = ready<OpsTrends>(sim.snapshot(at(95)).trends)[1];
  const last = (s: { points: (number | null)[] }) => s.points[s.points.length - 1] ?? 0;
  assert.ok(last(after.p95) > last(before.p95) + 50, "급증 구간 p95가 오른다");
  assert.ok(last(after.alerts) >= 1, "선 경보가 점에 잡힌다");
  assert.ok((after.alerts.current ?? 0) >= (before.alerts.current ?? 0) + 1, "구간 안 경보 발생 수가 는다");
});
