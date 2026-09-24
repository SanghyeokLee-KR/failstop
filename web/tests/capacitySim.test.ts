/**
 * 용량·부하 화면의 sim이 관제 시뮬레이터의 조회 ASG와 한 모델로 움직이는지 본다(관리자 설계 4-2). 노드 이름,
 * 대수, 임계가 관제센터와 같고, 용량 화면의 증설·축소가 관제센터의 조회 ASG를 움직이며, 권고의 종류와 순서는
 * 서버(CapacityAdminController)를 따른다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { capacityAdvice, SimAdminApi } from "../lib/admin/simAdmin";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import { AdminError } from "../lib/admin/api";
import type { AiDesk, ControlCenter, ReadTier } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}

async function rejectsWith(run: () => Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(async () => run(), (e: unknown) => e instanceof AdminError && e.code === code);
}

test("용량 판은 관제센터와 같은 노드 이름, 대수, 임계를 쓰고 매칭 노드는 대수에 넣지 않는다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  const c = await api.getControlCenter();
  const cap = await api.getCapacity();
  const tier = ready<ReadTier>(c.readTier);
  assert.equal(cap.tier, "read");
  assert.deepEqual(cap.nodes.map((n) => n.id), ["app-1", "app-2", "read-1", "read-2", "read-3"]);
  assert.deepEqual([cap.plan.min, cap.plan.desired, cap.plan.max], [tier.min, tier.desired, tier.max]);

  const apps = cap.nodes.filter((n) => n.id.startsWith("app-"));
  assert.ok(apps.every((n) => n.engine && !n.scalable && n.leader && !n.servesReads), "매칭 노드는 늘릴 수 없는 주문 리더다");
  const reads = cap.nodes.filter((n) => n.id.startsWith("read-"));
  assert.ok(reads.every((n) => !n.engine && n.scalable && !n.leader));
  assert.equal(cap.observedTotal, tier.nodes.filter((n) => n.state === "IN_SERVICE").length);
  assert.equal(cap.reconciled, true);
  assert.equal(reads.find((n) => n.id === "read-3")?.expected, false, "세 번째 조회 노드는 계약 밖이다");

  const items = ready<{ items: { key: string; value: number }[] }>(c.thresholds).items;
  assert.equal(cap.thresholds.scaleOutCpuRatio, (items.find((t) => t.key === "read.scale_out_cpu")?.value ?? 0) / 100);
  assert.equal(cap.thresholds.scaleInCpuRatio, (items.find((t) => t.key === "read.scale_in_cpu")?.value ?? 0) / 100);
  assert.ok(cap.readAvgCpuRatio !== null && Math.abs(cap.readAvgCpuRatio * 100 - (tier.avgCpuPct ?? 0)) <= 5);
});

test("용량 화면의 증설은 관제센터의 조회 ASG를 움직이고 감사와 사건 기록에 남는다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  await rejectsWith(() => api.scaleOut(), "admin_forbidden");

  await api.establishSession("op-01");
  const cap = await api.scaleOut();
  assert.equal(cap.plan.desired, 3);
  const read3 = cap.nodes.find((n) => n.id === "read-3");
  assert.equal(read3?.lifecycle, "PENDING");
  assert.equal(read3?.expected, true);
  assert.equal(read3?.reachable, false);
  assert.equal(cap.reconciled, false);
  assert.deepEqual(cap.advice.map((a) => a.id), ["under_provisioned"], "준비되는 동안 같은 증설을 다시 권하지 않는다");

  const c: ControlCenter = await api.getControlCenter();
  const tier = ready<ReadTier>(c.readTier);
  assert.equal(tier.desired, 3);
  assert.equal(tier.nodes.find((n) => n.id === "read-3")?.state, "PENDING");
  const events = ready<{ events: { actor: string; text: string }[] }>(c.incidents).events;
  assert.ok(events.some((e) => e.actor === "OPERATOR" && e.text.includes("용량·부하 화면")), "사건 기록에 운영자 조치로 남는다");

  await rejectsWith(() => api.scaleOut(), "CAPACITY_OUT_OF_BOUNDS");
  const audit = (await api.getAudit({ limit: 20 })).data.filter((e) => e.action === "capacity.scale_out");
  assert.deepEqual(audit.map((e) => e.result).sort(), ["DENIED", "OK"]);
});

test("사람이 대수를 바꾸면 열린 조회 노드 조치안은 거두고 AI 자동 실행에 쿨다운을 건다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const before = ready<AiDesk>(sim.snapshot(at(20)).ai);
  assert.ok(before.proposals.some((p) => p.kind === "READ_NODES" && p.status === "PROPOSED"));

  sim.scaleReadNodes(1, OP, at(20));
  const c = sim.snapshot(at(21));
  const d = ready<AiDesk>(c.ai);
  assert.ok(!d.proposals.some((p) => p.kind === "READ_NODES" && p.status === "PROPOSED"));
  assert.ok(d.proposals.some((p) => p.kind === "READ_NODES" && p.status === "WITHDRAWN" && p.decidedBy === "김수퍼"));
  assert.ok(d.cooldowns.some((x) => x.kind === "READ_NODES"));
  assert.equal(ready<ReadTier>(c.readTier).desired, 3);
});

test("권고 순서는 서버와 같고, 계약과 관측이 어긋난 창에는 부하 권고를 내지 않는다", () => {
  const plan = { min: 2, desired: 2, max: 3 };
  const th = { scaleOut: 0.6, scaleIn: 0.3 };
  const ids = (up: number, avg: number | null, p = plan, down: string[] = []) => capacityAdvice(p, up, avg, th, down).map((a) => a.id);
  assert.deepEqual(capacityAdvice(plan, 2, 0.7, th, []).map((a) => [a.id, a.action]), [["scale_out", "SCALE_OUT"]]);
  assert.deepEqual(ids(3, 0.7, { ...plan, desired: 3 }), ["at_max"]);
  assert.deepEqual(ids(3, 0.2, { ...plan, desired: 3 }), ["scale_in"]);
  assert.deepEqual(ids(2, 0.2), ["steady"], "최소 대수에서는 줄이라고 하지 않는다");
  assert.deepEqual(ids(3, 0.2), ["over_provisioned"]);
  assert.deepEqual(ids(2, null, plan, ["app-2"]), ["leader_down", "no_metrics"]);
  const down = capacityAdvice(plan, 2, 0.4, th, ["app-2"])[0];
  assert.equal(down.level, "danger");
  assert.match(down.detail, /조회 서버를 늘려도 주문은 재개되지 않습니다/);
});
