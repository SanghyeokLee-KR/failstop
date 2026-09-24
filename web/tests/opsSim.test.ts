/**
 * 관제센터 시뮬레이터(opsSim.ts) 규칙 검증. 시계를 직접 넘겨 시나리오 진행, 수동·자동 모드,
 * 관찰 창의 자동 되돌림, 경보 보류 규칙, 임계값 연동을 결정적으로 본다(ADR 0035, 관리자 설계 4-4).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { AI_ACTOR, OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import { AdminError } from "../lib/admin/api";
import type { AiDesk, ControlCenter, IncidentLog, OpsAlert, ThresholdRegistry } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
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

test("조회 급증은 경보와 AI 판단(노드 +1, 증설 임계 60→55)을 거쳐 수동 모드에서 운영자를 기다린다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const c = sim.snapshot(at(20));
  const names = alerts(c).map((a) => a.name);
  assert.ok(names.includes("조회 노드 CPU 높음"));
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
  assert.ok(done, "노드 +1 은 자동 실행된다");
  assert.equal(done.kind, "READ_NODES");
  assert.ok(done.revertibleUntil !== null);
  assert.ok(desk.observation !== null, "관찰 창이 열린다");
  assert.ok(desk.cooldowns.some((x) => x.kind === "READ_NODES"));
  assert.equal(desk.autoToday, 1);
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

test("즉시 끄기는 자동 모드를 바로 수동으로 바꾸고 기록을 남긴다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(8, "시험", OP, "apr-auto", at(0));
  sim.stopAuto({ id: "op-02", name: "이오퍼" }, at(5));
  const c = sim.snapshot(at(6));
  assert.equal(ai(c).mode, "MANUAL");
  assert.ok(log(c).events.some((e) => e.kind === "MODE" && e.text.includes("즉시 끄기") && e.actorName === "이오퍼"));
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
