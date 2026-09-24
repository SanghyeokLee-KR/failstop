/**
 * 관제 시뮬레이터의 상태 정합. 같은 순간에 AI 패널 문장, 조치 카드, 조회 ASG, 사건 기록이 서로 다른
 * 상태를 말하지 않는지 시나리오의 매 초마다 본다(관리자 설계 4-4).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import type { AiDesk, AiProposal, ControlCenter, IncidentLog, ReadTier, ThresholdRegistry } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}
const ai = (c: ControlCenter) => ready<AiDesk>(c.ai);
const tier = (c: ControlCenter) => ready<ReadTier>(c.readTier);
const log = (c: ControlCenter) => ready<IncidentLog>(c.incidents);
const reg = (c: ControlCenter) => ready<ThresholdRegistry>(c.thresholds);
const open = (c: ControlCenter) => ai(c).proposals.filter((p) => p.status === "PROPOSED");

/** 조회 노드 조치안 제목의 '(a → b대)'. 조치안은 지금 대수에서 출발해야 한다 */
function nodeStep(p: AiProposal): { from: number; to: number } {
  const m = /\((\d+) → (\d+)대\)/.exec(p.title);
  assert.ok(m, p.title);
  return { from: Number(m[1]), to: Number(m[2]) };
}

/** 어느 순간에도 성립해야 하는 관계. 어긋나면 화면의 칸끼리 서로 다른 상태를 말한다 */
function checkInvariants(c: ControlCenter, sec: number): void {
  const where = `${sec}초`;
  const t = tier(c);
  const live = t.nodes.filter((n) => n.state === "IN_SERVICE" || n.state === "PENDING").length;
  assert.equal(live, t.desired, `${where}: 희망 대수와 가동·기동 중 노드 칩 수`);

  const desk = ai(c);
  for (const p of open(c)) {
    if (p.kind === "READ_NODES") assert.equal(nodeStep(p).from, t.desired, `${where}: 대기 조치안 '${p.title}'는 지금 대수에서 출발`);
    if (p.kind === "THRESHOLD") {
      const now = reg(c).items.find((x) => x.key === "read.scale_out_cpu")?.value;
      assert.ok(p.title.startsWith(`증설 CPU 임계 ${now}%`), `${where}: 대기 임계값 조치안 '${p.title}'는 현재값 ${now}%에서 출발`);
    }
  }
  const done = desk.proposals.filter((p) => p.status === "APPROVED" || p.status === "AUTO_EXECUTED" || p.status === "AUTO_REVERTED" || p.status === "REVERTED");
  for (const d of done) {
    assert.ok(!open(c).some((p) => p.title === d.title), `${where}: 처리한 조치 '${d.title}'가 대기 카드에 다시 있다`);
  }

  const pending = t.nodes.some((n) => n.state === "PENDING");
  const draining = t.nodes.some((n) => n.state === "DRAINING");
  if (!pending) assert.doesNotMatch(desk.situation, /준비 중/, `${where}: 기동 중 노드가 없는데 '준비 중'`);
  if (!draining) assert.doesNotMatch(desk.situation, /줄이는 중/, `${where}: 드레이닝 노드가 없는데 '줄이는 중'`);
}

function approveNodes(sim: OpsSim, sec: number): void {
  const p = open(sim.snapshot(at(sec))).find((x) => x.kind === "READ_NODES");
  assert.ok(p, `${sec}초에 조회 노드 조치안이 있어야 한다`);
  sim.decide(p.id, "APPROVE", OP, at(sec));
}

test("조회 급증(수동)의 전·중·확인·조치·후 단계마다 칸들이 같은 상태를 말한다", () => {
  const sim = new OpsSim(T0);
  for (let s = 1; s <= 6; s += 1) checkInvariants(sim.snapshot(at(s)), s);
  const before = sim.snapshot(at(6));
  assert.match(ai(before).situation, /모든 지표가 임계 안/, "전: 평시 문장");

  sim.startScenario("READ_SURGE", at(6));
  for (let s = 7; s <= 26; s += 1) checkInvariants(sim.snapshot(at(s)), s);
  const during = sim.snapshot(at(26));
  assert.match(ai(during).situation, /평소의 .*배/, "중: 급증 문장");
  assert.ok(open(during).some((p) => p.kind === "READ_NODES"), "중: 노드 +1 조치안 대기");
  assert.equal(tier(during).desired, 2);

  // 확인 대화상자가 떠 있는 동안에도 판은 그대로다. 승인 직전의 조치안이 지금 대수에서 출발하는지 본다
  const confirm = sim.snapshot(at(27));
  checkInvariants(confirm, 27);
  approveNodes(sim, 27);

  const acting = sim.snapshot(at(28));
  checkInvariants(acting, 28);
  assert.equal(tier(acting).desired, 3);
  assert.match(ai(acting).situation, /준비 중/, "조치: 새 노드 준비 문장");
  assert.ok(!open(acting).some((p) => p.kind === "READ_NODES"), "조치: 실행한 +1은 대기 카드에서 빠진다");

  for (let s = 29; s <= 120; s += 1) checkInvariants(sim.snapshot(at(s)), s);
  const after = sim.snapshot(at(120));
  assert.equal(ready<unknown[]>(after.alerts).length, 0, "후: 경보 해제");
  assert.ok(log(after).incidents[0].resolvedAt !== null, "후: 사건 종료");
});

test("조회 급증(자동)도 매 초 칸들이 같은 상태를 말한다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  for (let s = 1; s <= 240; s += 1) checkInvariants(sim.snapshot(at(s)), s);
});

test("야간 축소 뒤 악화의 자동 되돌림 순간에 타임라인·AI 문장·ASG·카드가 같은 방향을 말한다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "야간", OP, "apr-auto", at(0));
  sim.startScenario("NIGHT_SCALE_IN", at(0));
  let reverted: ControlCenter | null = null;
  let sec = 0;
  for (let s = 1; s <= 90 && reverted === null; s += 1) {
    const c = sim.snapshot(at(s));
    checkInvariants(c, s);
    if (ai(c).proposals.some((p) => p.status === "AUTO_REVERTED")) {
      reverted = c;
      sec = s;
    }
  }
  assert.ok(reverted, "관찰 창 안에서 자동 되돌림이 일어나야 한다");
  const ev = log(reverted).events.find((e) => e.kind === "AUTO_REVERTED");
  assert.ok(ev);
  // 되돌림은 축소(−1)를 무르는 증설(+1)이다. 기록 첫머리가 무른 조치가 아니라 실제로 한 일을 말해야 한다
  assert.match(ev.text, /^자동 되돌림 · 조회 노드 \+1 \(2 → 3대\)/, ev.text);
  assert.equal(tier(reverted).desired, 3);
  assert.ok(tier(reverted).nodes.some((n) => n.state === "PENDING"), "되돌린 대수만큼 노드가 기동 중이다");
  assert.match(ai(reverted).situation, /자동 축소를 되돌렸습니다/, `되돌림 순간의 AI 문장: ${ai(reverted).situation}`);
  const card = ai(reverted).proposals.find((p) => p.status === "AUTO_REVERTED");
  assert.match(card?.outcome ?? "", /^되돌림 · 조회 노드 \+1 \(2 → 3대\)/, "처리된 카드는 되돌린 방향을 결과로 적는다");
  assert.ok(!open(reverted).some((p) => p.kind === "READ_NODES"), "되돌린 축소를 다시 권하지 않는다");
  for (let s = sec + 1; s <= sec + 60; s += 1) {
    const c = sim.snapshot(at(s));
    checkInvariants(c, s);
    assert.ok(!open(c).some((p) => p.kind === "READ_NODES" && p.title.includes("−1")), `${s}초: 되돌린 축소를 곧바로 다시 권한다`);
  }
});

test("드레이닝 중에 축소를 되돌리면 그 노드가 다시 올라와 희망 대수와 노드 칩 수가 맞는다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "야간", OP, "apr-auto", at(0));
  sim.startScenario("NIGHT_SCALE_IN", at(0));
  let done: AiProposal | undefined;
  let sec = 0;
  for (let s = 1; s <= 30 && !done; s += 1) {
    done = ai(sim.snapshot(at(s))).proposals.find((p) => p.status === "AUTO_EXECUTED");
    sec = s;
  }
  assert.ok(done, "야간 축소가 자동 실행되어야 한다");
  assert.ok(tier(sim.snapshot(at(sec))).nodes.some((n) => n.state === "DRAINING"), "실행 직후에는 한 대가 드레이닝 중이다");
  sim.revert(done.id, OP, at(sec));
  for (let s = sec; s <= sec + 20; s += 1) checkInvariants(sim.snapshot(at(s)), s);
  assert.equal(tier(sim.snapshot(at(sec + 20))).nodes.filter((n) => n.state === "IN_SERVICE").length, 3);
});
