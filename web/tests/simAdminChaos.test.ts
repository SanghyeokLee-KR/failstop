/**
 * SimAdminApi 카오스·헬스 결합 검증. 주입 효과와 해제 회수가 승인 경로로 도달하는지,
 * 복구 이력 시드가 RtoSpark 게이트(n>=3)와 정합한지 본다.
 * 타이머(tick)는 브라우저 전용이라 노드 환경에서는 주입 직후의 동기 상태만 단정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimAdminApi } from "../lib/admin/simAdmin";
import type { ApprovalPayload } from "../lib/admin/types";

/** 요청(op-01) -> 발급(op-02) -> 실행(op-01). 다른 운영자가 발급하는 경로를 통과시킨다 */
async function approveAndRun(api: SimAdminApi, payload: ApprovalPayload) {
  await api.establishSession("op-02");
  const issued = await api.issueApproval("op-01", payload.action, payload);
  await api.establishSession("op-01");
  await api.consumeApproval(issued);
  return issued;
}

test("복구 이력 시드는 스파크 게이트(n>=3)를 채우고 최신순이다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const rec = await api.getRecovery();
  assert.ok(rec.runs.length >= 3);
  for (let i = 1; i < rec.runs.length; i += 1) {
    assert.ok(rec.runs[i - 1].startedAt >= rec.runs[i].startedAt);
  }
  // lastRtoMs는 페일오버 RTO다. 목에 그 주행 표가 없으므로 null이어야 한다.
  // 재생 소요(recovery_runs.duration_ms)를 여기 실으면 축 혼동을 계약으로 굳힌다.
  assert.equal(rec.lastRtoMs, null, "없는 축을 지어내지 않는다");
  assert.ok(rec.runs[0].durationMs > 0, "재생 소요는 그 자리에 남는다");
});

test("E4 주입은 리더를 중단시키고 종합 헬스를 danger로 내린다", async () => {
  const api = new SimAdminApi();
  await approveAndRun(api, { action: "chaos.inject", experimentId: "E4" });

  const ha = await api.getHaStatus();
  assert.ok(ha.nodes.some((n) => n.health === "down"));
  const ov = await api.getOverview();
  assert.equal(ov.overallHealth, "danger");
  assert.equal(ov.chaosRunning, 1);
});

test("해제 실행은 주입 효과를 회수한다", async () => {
  const api = new SimAdminApi();
  await approveAndRun(api, { action: "chaos.inject", experimentId: "E4" });
  const runs = await api.getChaosRuns();
  const run = runs.data.find((r) => r.state === "RUNNING");
  assert.ok(run);

  await approveAndRun(api, { action: "chaos.clear", runId: run.clearKey });
  const ha = await api.getHaStatus();
  assert.ok(ha.nodes.every((n) => n.health === "healthy"));
  const ov = await api.getOverview();
  assert.equal(ov.overallHealth, "success");
  assert.equal(ov.chaosRunning, 0);
});

test("노드를 건드리지 않는 실험(E1)도 진행 중에는 종합을 주의로 내린다", async () => {
  const api = new SimAdminApi();
  await approveAndRun(api, { action: "chaos.inject", experimentId: "E1" });

  const ha = await api.getHaStatus();
  assert.ok(ha.nodes.every((n) => n.health === "healthy"));
  const ov = await api.getOverview();
  assert.equal(ov.overallHealth, "warning");
});

test("주입 승인은 발급 주체와 요청 주체가 같아도 발급된다(1인 승인)", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const issued = await api.issueApproval("op-01", "chaos.inject", { action: "chaos.inject", experimentId: "E1" });
  // ADR 0019가 발급 가드를 뗐다. 역할 게이트와 확인 다이얼로그, 전량 감사는 그대로다
  assert.equal(issued.requesterId, "op-01");
  assert.equal(issued.approverId, "op-01");
});
