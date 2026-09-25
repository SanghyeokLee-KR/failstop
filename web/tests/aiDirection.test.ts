/**
 * 자동 모드의 방향 규칙(ADR 0035, 사용자 확정 2026-09-25). 임계값은 더 민감하게 하는 방향만 자동으로 바꾸고 완화는 승인으로만
 * 하며, 시장 봇 부하 줄임은 bot-1 CPU가 봇 용량 보호 기준 이상일 때만 자동이다. 경보 문장 속 명령문을 모델이 따라 임계값을
 * 완화한 평가 사례가 이 규칙의 근거다. 정책 정의와 시뮬레이터 검사표·카드 배지가 같은 판정을 쓰는지 본다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { AI_LIMITS_DEFAULT, canAutoRun, isStricter, STRICTER } from "../lib/admin/aiPolicy";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import type { AiDesk, ControlCenter, ThresholdRegistry } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}
const ai = (c: ControlCenter) => ready<AiDesk>(c.ai);

test("임계값의 민감한 방향은 항목마다 정해져 있고 아홉 항목을 모두 덮는다", () => {
  const sim = new OpsSim(T0);
  const keys = ready<ThresholdRegistry>(sim.snapshot(at(1)).thresholds).items.map((t) => t.key).sort();
  assert.deepEqual(Object.keys(STRICTER).sort(), keys, "레지스트리에 항목이 늘면 방향도 정해야 한다");
  assert.equal(isStricter("read.scale_out_cpu", 60, 55), true, "증설 임계를 내리면 증설이 먼저 걸린다");
  assert.equal(isStricter("read.scale_out_cpu", 60, 65), false, "올리면 완화다");
  assert.equal(isStricter("host.disk_free_alert", 20, 25), true, "여유 미만 경보는 올릴수록 먼저 울린다");
  assert.equal(isStricter("host.disk_free_alert", 20, 15), false);
  assert.equal(isStricter("host.cpu_credit_alert", 50, 60), true);
  assert.equal(isStricter("bot.capacity_guard", 70, 65), true, "봇 보호는 내릴수록 먼저 줄인다");
  assert.equal(isStricter("db.replication_lag_alert", 30, 30), false, "같은 값은 바뀌는 것이 없다");
});

test("카드의 '자동 실행 가능'은 자동 모드·자동 가능 조치·확신도 기준을 모두 채울 때만이다", () => {
  const limits = AI_LIMITS_DEFAULT;
  const p = { autoAllowed: true, confidencePct: limits.confidencePct };
  assert.equal(canAutoRun(p, "AUTO", limits), true);
  assert.equal(canAutoRun(p, "MANUAL", limits), false);
  assert.equal(canAutoRun(p, "STOPPED", limits), false);
  assert.equal(canAutoRun({ ...p, autoAllowed: false }, "AUTO", limits), false, "완화 방향은 자동 모드에서도 승인 필요");
  assert.equal(canAutoRun({ ...p, confidencePct: limits.confidencePct - 1 }, "AUTO", limits), false);
});

test("조회 급증의 증설 임계 제안(60→55%)은 민감한 방향이라 자동 가능이고 검사표의 방향 행이 통과다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  const desk = ai(sim.snapshot(at(24)));
  const th = desk.proposals.find((p) => p.kind === "THRESHOLD");
  assert.ok(th);
  assert.equal(th.autoAllowed, true);
  const traced = desk.history.find((h) => h.trace?.candidates.some((c) => c.id === th.id));
  assert.ok(traced && traced.trace);
  const col = traced.trace.candidates.findIndex((c) => c.id === th.id);
  const dir = traced.trace.checks.find((r) => r.rule === "DIRECTION");
  assert.ok(dir);
  assert.equal(dir.results[col].state, "PASS");
  const guard = traced.trace.checks.find((r) => r.rule === "BOT_GUARD");
  assert.ok(guard);
  assert.equal(guard.results[col].state, "NA", "봇 보호 행은 봇 부하 줄임에만 걸린다");
});

test("봇 부하 줄임은 bot-1 CPU가 용량 보호 기준 이상일 때 나오고 그때만 자동 가능이다", () => {
  const sim = new OpsSim(T0);
  sim.setBotTarget(140, OP, "시험", at(1));
  // 운영자가 발행률을 바꾸면 봇 부하 쿨다운(10분)이 걸린다. 쿨다운이 지난 뒤의 판단을 본다
  for (let s = 60; s <= 720; s += 60) sim.advance(at(s));
  const desk = ai(sim.snapshot(at(725)));
  const bot = desk.proposals.find((p) => p.kind === "BOT_LOAD" && p.status === "PROPOSED");
  assert.ok(bot, "bot-1 CPU가 기준을 넘으면 봇 부하 줄임을 낸다");
  assert.equal(bot.autoAllowed, true);
  const traced = desk.history.find((h) => h.trace?.candidates.some((c) => c.id === bot.id));
  assert.ok(traced && traced.trace);
  const col = traced.trace.candidates.findIndex((c) => c.id === bot.id);
  const guard = traced.trace.checks.find((r) => r.rule === "BOT_GUARD");
  assert.ok(guard);
  assert.equal(guard.results[col].state, "PASS");
  assert.match(guard.results[col].note, /^bot-1 CPU \d+% ≥ \d+%$/);
});
