/**
 * 관제센터 어댑터 검증. sim은 역할 검사와 1인 승인 원장을 거쳐 시뮬레이터를 바꾸고, http는 서버에
 * 없는 관제 엔드포인트를 부르지 않고 '원천 연결 전'을 돌려준다(관리자 설계 4-4, ADR 0035).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError, type AdminApi } from "../lib/admin/api";
import type { AiDesk, ControlCenter, ThresholdRegistry } from "../lib/admin/types";

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}

/** sim은 권한 검사를 동기로 던지기도 한다. async로 감싸 거절로 받는다 */
async function rejectsWith(run: () => Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(async () => run(), (e: unknown) => e instanceof AdminError && e.code === code);
}

test("sim: 조회 권한 운영자는 자동 모드를 켜지 못하지만 자동 끄기는 누구나 한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  await rejectsWith(() => api.enableAiAuto(2, "야간 대기"), "admin_forbidden");
  await api.establishSession("op-01");
  await api.enableAiAuto(2, "야간 대기");
  assert.equal(ready<AiDesk>((await api.getControlCenter()).ai).mode, "AUTO");
  await api.establishSession("op-02");
  await api.stopAiAuto();
  assert.equal(ready<AiDesk>((await api.getControlCenter()).ai).mode, "MANUAL");
});

test("sim: 자동 모드 켜기는 승인 티켓을 발급·소각한 기록을 남긴다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  await api.enableAiAuto(4, "점검");
  const page = await api.listApprovals();
  const auto = page.data.find((a) => a.action === "ops.ai.auto");
  assert.ok(auto, "승인 원장에 자동 모드 티켓이 있다");
  assert.equal(auto.state, "CONSUMED");
  const desk = ready<AiDesk>((await api.getControlCenter()).ai);
  assert.equal(desk.auto?.approvalId, auto.id);
  assert.equal(desk.auto?.reason, "점검");
});

test("sim: AI 즉시 정지는 조회 권한으로 두 모드에서 되고, 재개는 SUPER가 사유를 적어 수동으로 한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  await api.stopAi();
  assert.equal(ready<AiDesk>((await api.getControlCenter()).ai).mode, "STOPPED", "수동 모드에서도 정지한다");
  await rejectsWith(() => api.resumeAi("다시 봄"), "admin_forbidden");
  await api.establishSession("op-01");
  await rejectsWith(() => api.enableAiAuto(2, "정지 중 켜기"), "invalid_parameter");
  await api.resumeAi("원인 확인 끝");
  assert.equal(ready<AiDesk>((await api.getControlCenter()).ai).mode, "MANUAL");
  await api.enableAiAuto(2, "야간 대기");
  await api.establishSession("op-02");
  await api.stopAi();
  const desk = ready<AiDesk>((await api.getControlCenter()).ai);
  assert.equal(desk.mode, "STOPPED", "자동 모드에서도 정지한다");
  assert.equal(desk.auto, null);
  await api.establishSession("op-01");
  const audit = (await api.getAudit({ limit: 50 })).data.map((e) => e.action);
  assert.ok(audit.includes("ops.ai.stop") && audit.includes("ops.ai.resume"), "정지와 재개가 감사 로그에 남는다");
});

test("sim: AI 한도는 ops.ai.limits 1인 승인 티켓으로 바뀌고 조회 권한 운영자는 소각해도 바꾸지 못한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  const denied = await api.issueApproval("op-02", "ops.ai.limits", {
    action: "ops.ai.limits", key: "autoCap", label: "자동 조치 상한", unit: "회", from: 5, to: 3, reason: "좁힘", revertOf: null,
  });
  await rejectsWith(() => api.consumeApproval(denied), "admin_forbidden");
  await api.establishSession("op-01");
  const issued = await api.issueApproval("op-01", "ops.ai.limits", {
    action: "ops.ai.limits", key: "autoCap", label: "자동 조치 상한", unit: "회", from: 5, to: 3, reason: "야간 좁힘", revertOf: null,
  });
  await api.consumeApproval(issued);
  const desk = ready<AiDesk>((await api.getControlCenter()).ai);
  assert.equal(desk.limits.find((l) => l.key === "autoCap")?.value, 3);
  assert.equal(desk.autoCap, 3);
  assert.equal(desk.limitHistory[0].approvalId, issued.id);
  assert.equal(desk.limitHistory[0].reason, "야간 좁힘");
  const out = await api.issueApproval("op-01", "ops.ai.limits", {
    action: "ops.ai.limits", key: "confidencePct", label: "확신도 기준", unit: "%", from: 80, to: 99, reason: "범위 밖", revertOf: null,
  });
  await rejectsWith(() => api.consumeApproval(out), "invalid_parameter");
});

test("sim: 잘못된 유효 시간은 티켓을 발급하기 전에 거절한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  await rejectsWith(() => api.enableAiAuto(12, "길게"), "invalid_parameter");
  const page = await api.listApprovals();
  assert.equal(page.data.filter((a) => a.action === "ops.ai.auto").length, 0, "쓸모없는 티켓을 남기지 않는다");
});

test("sim: 임계값 변경은 1인 승인 티켓으로 적용되고 이력에서 같은 흐름으로 되돌린다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const issued = await api.issueApproval("op-01", "ops.threshold.set", {
    action: "ops.threshold.set", key: "db.replication_lag_alert", label: "복제 지연 경보", unit: "초",
    from: 30, to: 20, reason: "민감하게", revertOf: null, proposalId: null,
  });
  await api.consumeApproval(issued);
  let reg = ready<ThresholdRegistry>((await api.getControlCenter()).thresholds);
  assert.equal(reg.items.find((t) => t.key === "db.replication_lag_alert")?.value, 20);
  const change = reg.history[0];
  assert.equal(change.approvalId, issued.id);
  assert.equal(change.by, "김수퍼");

  const back = await api.issueApproval("op-01", "ops.threshold.set", {
    action: "ops.threshold.set", key: "db.replication_lag_alert", label: "복제 지연 경보", unit: "초",
    from: 20, to: 30, reason: "되돌리기", revertOf: change.id, proposalId: null,
  });
  await api.consumeApproval(back);
  reg = ready<ThresholdRegistry>((await api.getControlCenter()).thresholds);
  assert.equal(reg.items.find((t) => t.key === "db.replication_lag_alert")?.value, 30);
  assert.equal(reg.history[0].via, "REVERT");
  assert.equal(reg.history.find((h) => h.id === change.id)?.reverted, true);
});

test("sim: 조회 권한 운영자는 임계값 티켓을 소각해도 적용되지 않는다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  const issued = await api.issueApproval("op-02", "ops.threshold.set", {
    action: "ops.threshold.set", key: "host.cpu_alert", label: "호스트 CPU 경보", unit: "%",
    from: 85, to: 80, reason: "시험", revertOf: null, proposalId: null,
  });
  await rejectsWith(() => api.consumeApproval(issued), "admin_forbidden");
  const reg = ready<ThresholdRegistry>((await api.getControlCenter()).thresholds);
  assert.equal(reg.items.find((t) => t.key === "host.cpu_alert")?.value, 85);
});

test("sim: 경보 보류는 SUPER만, 확인은 조회 권한으로 된다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  await rejectsWith(() => api.silenceAlert("없는-경보", 10), "admin_forbidden");
  await rejectsWith(() => api.ackAlert("없는-경보"), "not_found");
});

test("http: 관제센터 판독은 네트워크를 부르지 않고 칸마다 '원천 연결 전'과 원천 이름을 돌려준다", async () => {
  const prev = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = (async () => {
    calls += 1;
    throw new Error("관제 엔드포인트를 부르면 안 된다");
  }) as typeof fetch;
  try {
    // 계약(AdminApi)으로 부른다. 구현은 인자를 읽지 않고 곧바로 거절한다
    const api: AdminApi = new HttpAdminApi("http://admin.test");
    const c: ControlCenter = await api.getControlCenter();
    assert.equal(c.origin, null);
    for (const key of ["summary", "hosts", "readTier", "replication", "alerts", "incidents", "jobs", "thresholds", "ai"] as const) {
      const s = c[key];
      assert.equal(s.state, "NOT_CONNECTED", key);
      assert.ok(s.state === "NOT_CONNECTED" && s.source.length > 0, `${key} 원천 이름`);
    }
    assert.equal(c.scenario, null);
    assert.equal(api.opsScenarios, false);
    for (const run of [
      () => api.ackAlert("a"),
      () => api.silenceAlert("a", 10),
      () => api.decideAiProposal("p", "APPROVE"),
      () => api.revertAiAction("p"),
      () => api.enableAiAuto(1, "사유"),
      () => api.stopAiAuto(),
      () => api.stopAi(),
      () => api.resumeAi("사유"),
      () => api.runBackupNow(),
      () => api.startOpsScenario("READ_SURGE"),
      () => api.resetOps(),
    ]) {
      await rejectsWith(run, "source_unavailable");
    }
    assert.equal(calls, 0);
  } finally {
    globalThis.fetch = prev;
  }
});
