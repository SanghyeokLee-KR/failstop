/**
 * 운영 보고와 연동 설정(관리자 설계 4-7·4-8). 보고서 화면의 'Teams로 보낸 카드'는 보고 서비스의 카드와 같은 순서로
 * 만들고, sim은 세 가지 보고 모양을 예시로 든다. 설정 화면의 연동 상태는 이미 받은 관제 판과 보고 서비스의 전송
 * 기록에서 가리며, 밖으로 보내는 Teams 연결 시험은 sim에서만 모의로 동작한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { simReportDesk } from "../lib/admin/reportSim";
import { cardTone, teamsCard, type CardBlock } from "../lib/admin/reports";
import { integrationsOf } from "../lib/admin/integrations";
import { OpsSim } from "../lib/admin/opsSim";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";
import type { ControlCenter, OpsSection, ReportDesk } from "../lib/admin/types";

const NOW = new Date(2026, 8, 25, 10, 30, 0).getTime();

const texts = (blocks: CardBlock[]) => blocks.flatMap((b) => (b.type === "text" ? [b.text] : b.facts.map((f) => `${f.title}=${f.value}`)));

async function rejectsWith(run: () => Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(async () => run(), (e: unknown) => e instanceof AdminError && e.code === code);
}

test("sim 보고 예시는 일일 정상, 사건 보고(자동 되돌림), 일일 주의(백업 확인 필요)를 최근 순서로 든다", () => {
  const d = simReportDesk(NOW);
  assert.deepEqual(
    d.reports.map((r) => [r.kind, r.intervention.priority, r.transition]),
    [
      ["daily", "P4", "unknown"],
      ["incident", "P3", "resolved"],
      ["daily", "P3", "unknown"],
    ],
  );
  const [normal, incident, backup] = d.reports;
  assert.equal(normal.analysis.mode, "gemini");
  assert.ok(normal.analysis.model, "AI 요약에는 모델을 적는다");
  assert.equal(backup.analysis.mode, "facts-only");
  assert.equal(backup.analysis.model, null, "AI 분석이 없으면 모델도 없다");
  assert.equal(backup.gaps.length, 2);
  assert.match(incident.autoAction, /자동 되돌림/);
  assert.equal(d.delivery.lastDailySentAt, normal.sentAt);
  assert.ok(Date.parse(d.delivery.nextDailyAt ?? "") > NOW, "다음 일일 보고는 앞으로다");
});

test("Teams 카드는 보고 서비스와 같은 순서로 머리, 영향, 담당자 조치, 후속 관측, 참고 분석, 기록을 싣는다", () => {
  const [normal, incident, backup] = simReportDesk(NOW).reports;

  const c = teamsCard(incident);
  const t = texts(c.blocks);
  assert.equal(t[0], "페일스톱 운영 관제 · exchange / AWS");
  assert.equal(t[1], "응답 지연 증가 해제");
  assert.equal(t[2], "P3 · 담당자 확인 필요");
  assert.ok(t.some((x) => x.startsWith("대상=")) && t.some((x) => x.startsWith("해제=")), "사건 사실에 대상과 해제 시각이 있다");
  const order = ["영향  |", "담당자 조치", "후속 관측 ·", "참고 분석", "미확인 자료 1항목", "기록 "];
  const idx = order.map((head) => t.findIndex((x) => x.startsWith(head)));
  assert.ok(idx.every((i) => i >= 0), `모든 블록이 있다: ${idx}`);
  assert.deepEqual([...idx].sort((a, b) => a - b), idx, "순서가 보고 서비스와 같다");
  assert.equal(c.actions.length, 0, "보고 파일이 없으면 단추를 붙이지 않는다");

  const f = texts(teamsCard(backup).blocks);
  assert.ok(!f.includes("참고 분석"), "AI 분석이 없으면 참고 분석 블록이 없다");
  assert.ok(f.includes("미확인 자료 2항목 · 상세 보고의 수집 범위 참조"));
  assert.ok(f.some((x) => x.startsWith("보고 기준=")), "사건이 없는 일일 보고는 보고 기준 시각을 사실로 싣는다");

  assert.deepEqual(cardTone(incident), { tone: "good", shape: "●" }, "해제는 초록이다");
  assert.deepEqual(cardTone(backup), { tone: "warning", shape: "▲" });
  assert.deepEqual(cardTone(normal), { tone: "good", shape: "○" });
  const withFiles = { ...normal, links: { pdf: "https://r.test/a.pdf", html: "https://r.test/a.html" } };
  assert.deepEqual(teamsCard(withFiles).actions.map((a) => a.title), ["PDF 보고서", "HTML 보고서"]);
});

test("연동 상태: sim은 판에서 가리고, http는 원천 상태에서 가리며, 모듈이 없으면 모두 연결 전이다", () => {
  const sim = new OpsSim(NOW);
  const board = sim.snapshot(NOW + 6000);
  const desk: OpsSection<ReportDesk> = { state: "READY", data: simReportDesk(NOW) };
  const rows = integrationsOf(board, desk, { grafana: false, databasus: false });
  assert.deepEqual(rows.map((r) => r.key), ["prometheus", "alertmanager", "grafana", "databasus", "teams", "ai1"]);
  const byKey = Object.fromEntries(rows.map((r) => [r.key, r]));
  assert.equal(byKey.prometheus.state, "CONNECTED");
  assert.equal(byKey.prometheus.sim, true, "시뮬레이터가 흉내 낸 값이라고 적는다");
  assert.equal(byKey.grafana.state, "NOT_CONNECTED", "딥링크 주소가 없으면 연결 전이다");
  assert.equal(byKey.teams.state, "CONNECTED");
  assert.equal(byKey.ai1.state, "CONNECTED");

  const absent = { ...board, origin: null, sources: null } as ControlCenter;
  const none = integrationsOf(absent, { state: "NOT_CONNECTED", source: "보고 서비스" }, { grafana: true, databasus: true });
  const nb = Object.fromEntries(none.map((r) => [r.key, r]));
  assert.equal(nb.prometheus.state, "NOT_CONNECTED");
  assert.equal(nb.grafana.state, "CONNECTED", "Grafana는 주소가 있는지만 적는다");
  assert.equal(nb.teams.state, "NOT_CONNECTED");

  const failing = {
    ...absent,
    aiServer: { state: "NOT_CONNECTED", source: "구축 전" },
    sources: [
      { name: "prometheus", state: "READY", checkedAt: "2026-09-25T01:00:00.000Z", reason: null },
      { name: "llm", state: "UNAVAILABLE", checkedAt: null, reason: "연결 시간 초과" },
    ],
  } as ControlCenter;
  const fb = Object.fromEntries(integrationsOf(failing, null, { grafana: false, databasus: false }).map((r) => [r.key, r]));
  assert.equal(fb.prometheus.state, "CONNECTED");
  assert.equal(fb.prometheus.checkedAt, "2026-09-25T01:00:00.000Z");
  assert.equal(fb.alertmanager.state, "NOT_CONNECTED", "서버가 모르는 원천은 연결 전이다");
  assert.equal(fb.ai1.state, "ERROR");
  assert.match(fb.ai1.detail, /연결 시간 초과/);
});

test("sim 보고서·연락 체계는 예시를 돌려주고, Teams 연결 시험은 총괄만 모의로 보내 감사에 남긴다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  const desk = await api.getReports();
  assert.equal(desk.state, "READY");
  assert.equal(desk.state === "READY" && desk.data.reports.length, 3);
  const onCall = await api.getOnCall();
  assert.equal(onCall.state === "READY" && onCall.data.length, 3);
  await rejectsWith(() => api.sendTeamsTest(), "admin_forbidden");

  await api.establishSession("op-01");
  await api.sendTeamsTest();
  const after = await api.getReports();
  assert.equal(after.state === "READY" && after.data.tests[0].status, "sent");
  const audit = (await api.getAudit({ limit: 20 })).data;
  assert.ok(audit.some((e) => e.action === "ops.report.teams_test" && e.result === "OK"));
});

test("http 보고서·연락 체계는 서버 계약 전이라 원천 이름과 함께 연결 전이고, 연결 시험은 보내지 않는다", async () => {
  const prev = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = (async () => {
    calls += 1;
    throw new Error("보고 엔드포인트를 부르면 안 된다");
  }) as typeof fetch;
  try {
    const api = new HttpAdminApi("http://admin.test");
    const desk = await api.getReports();
    assert.equal(desk.state, "NOT_CONNECTED");
    assert.ok(desk.state === "NOT_CONNECTED" && desk.source.includes("/admin/v1/msp/reports"));
    const onCall = await api.getOnCall();
    assert.equal(onCall.state, "NOT_CONNECTED");
    await rejectsWith(() => api.sendTeamsTest(), "source_unavailable");
    assert.equal(calls, 0);
  } finally {
    globalThis.fetch = prev;
  }
});
