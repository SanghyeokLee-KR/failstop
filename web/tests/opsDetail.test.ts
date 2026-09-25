/**
 * 관제센터 상세 층의 원천(관리자 설계 4-9, 디자인 시스템 §12-9·§12-13). 판단 기록의 판단 과정과 정책 엔진 검사표,
 * 조치 카드의 대기 이유가 한 검사표에서 나오는지, 조치안의 변경 전후·비용, 모드 줄 알림의 30초, 사건의 확인·해제까지
 * 걸린 시간, 판단 기록 찾기, 상세 층 주소, 조치 확인 제목의 조사를 본다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import { actionVerb, changeParts, incidentSections, incidentTimes, judgmentAt, todayMeans } from "../lib/admin/opsDetail";
import { detailFromSearch, searchWithDetail } from "../lib/admin/detailStore";
import { directionParticle, joinDots, objectParticle } from "../lib/admin/text";
import type { AiDesk, AiJudgment, ControlCenter, IncidentLog } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}
const ai = (c: ControlCenter) => ready<AiDesk>(c.ai);
const log = (c: ControlCenter) => ready<IncidentLog>(c.incidents);

test("조치안은 변경 전후와 비용을 싣고, 조치 확인 제목은 조치를 동사로 적는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const desk = ai(sim.snapshot(at(20)));
  const node = desk.proposals.find((p) => p.kind === "READ_NODES" && p.status === "PROPOSED");
  assert.ok(node);
  assert.deepEqual(node.change, { from: 2, to: 3, unit: "대" });
  assert.ok(node.cost.length > 0, "비용 줄이 빈 글이면 확인 화면이 말할 것이 없다");
  assert.equal(actionVerb(node), "앱 계층을 3대로 늘립니다");
  assert.equal(node.title, "app-3 켜기 (앱 2 → 3대)");
  assert.deepEqual(changeParts(node.change), { from: "2대", to: "3대" });
  const th = desk.proposals.find((p) => p.kind === "THRESHOLD" && p.status === "PROPOSED");
  assert.ok(th && th.change);
  assert.equal(actionVerb(th), "증설 CPU 임계를 60%에서 55%로 낮춥니다");
});

test("판단 기록은 판단한 순간의 근거·가설·후보와 후보마다의 정책 엔진 검사표를 싣는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const desk = ai(sim.snapshot(at(20)));
  const j = desk.history.find((h) => h.trace !== null && h.trace.candidates.length > 0);
  assert.ok(j && j.trace);
  const tr = j.trace;
  assert.equal(tr.mode, "MANUAL");
  assert.ok(tr.evidence.length > 0);
  assert.ok(tr.hypothesis !== null && tr.hypothesis.length > 0);
  assert.ok(tr.checks.length > 0);
  for (const row of tr.checks) assert.equal(row.results.length, tr.candidates.length, `${row.rule} 행의 칸 수는 후보 수와 같다`);
  const allowed = tr.checks.find((r) => r.rule === "ALLOWED");
  assert.ok(allowed && allowed.results.every((c) => c.state === "PASS"), "허용 목록 밖의 후보는 조치안이 되지 않는다");
  const mode = tr.checks.find((r) => r.rule === "MODE");
  assert.ok(mode && mode.results.every((c) => c.state === "HOLD"), "수동 모드는 막음이 아니라 운영자 승인 대기다");
  assert.match(tr.result, /운영자 승인 대기/);
});

test("자동 모드에서 조치 카드의 대기 이유는 판단 과정 검사표의 첫 막힌 칸과 같은 말이다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "시험", OP, "apr-auto", at(0));
  sim.startScenario("READ_SURGE", at(0));
  const desk = ai(sim.snapshot(at(24)));
  const held = desk.proposals.find((p) => p.status === "PROPOSED" && p.autoHold !== null);
  assert.ok(held, "관찰 창 동안 남은 조치안은 대기 이유가 있다");
  const traced = desk.history.find((h) => h.trace?.candidates.some((c) => c.id === held.id));
  assert.ok(traced && traced.trace, "그 조치안을 후보로 둔 판단 기록이 있다");
  const col = traced.trace.candidates.findIndex((c) => c.id === held.id);
  const blocks = traced.trace.checks.map((r) => r.results[col]).filter((c) => c.state === "BLOCK");
  assert.ok(blocks.length > 0);
});

test("모드 줄 알림은 AI 정지 뒤 30초 동안만 실리고 사건이 열려 있으면 그 사건을 가리킨다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  sim.snapshot(at(20));
  sim.stopAi(OP, at(21));
  const n = ai(sim.snapshot(at(22))).notice;
  assert.ok(n);
  assert.equal(n.kind, "STOPPED");
  assert.equal(n.tone, "warning");
  assert.match(n.text, /^AI 즉시 정지 · 김수퍼/);
  assert.equal(n.incidentId, log(sim.snapshot(at(22))).incidents[0].id, "열린 사건을 가리켜 알림에서 그 사건을 연다");
  assert.equal(Date.parse(n.until) - Date.parse(n.at), 30_000);
  assert.equal(ai(sim.snapshot(at(52))).notice, null, "30초가 지나면 싣지 않는다");
  sim.resumeAi("점검 끝", OP, at(60));
  const r = ai(sim.snapshot(at(61))).notice;
  assert.ok(r && r.kind === "RESUMED" && r.tone === "neutral");
});

test("사건의 확인까지는 운영자의 첫 결정, 해제까지는 해제 시각까지이고 오늘 평균은 값이 있는 사건만 센다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const before = sim.snapshot(at(20));
  const inc = log(before).incidents[0];
  assert.ok(inc && inc.resolvedAt === null);
  const open = incidentTimes(inc, log(before).events);
  assert.equal(open.ttaMs, null, "승인 전이면 확인까지가 없다");
  assert.equal(open.ttrMs, null);
  const p = ai(before).proposals.find((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.ok(p);
  sim.decide(p.id, "APPROVE", OP, at(22));
  let c = sim.snapshot(at(22));
  for (let s = 30; s <= 600 && log(c).incidents[0].resolvedAt === null; s += 10) c = sim.snapshot(at(s));
  const done = log(c).incidents.find((i) => i.id === inc.id);
  assert.ok(done && done.resolvedAt !== null, "노드가 늘면 사건이 해제된다");
  const t = incidentTimes(done, log(c).events);
  assert.equal(t.ttaMs, at(22) - Date.parse(done.openedAt));
  assert.equal(t.ttrMs, Date.parse(done.resolvedAt) - Date.parse(done.openedAt));
  assert.equal(t.autoOnly, false);
  const mean = todayMeans(log(c), at(600));
  assert.ok(mean.mttaMs !== null && mean.mttrMs !== null);
  const sec = incidentSections(done, log(c).events);
  assert.ok(sec.operator.some((e) => e.kind === "APPROVED"));
  assert.ok(sec.alerts.every((e) => e.actor === "SYSTEM"));
  for (let i = 1; i < sec.ai.length; i++) assert.ok(Date.parse(sec.ai[i - 1].at) <= Date.parse(sec.ai[i].at), "절은 오래된 순서다");
});

test("사건 기록의 AI 행은 그 시각 이전 60초 안의 판단 과정 기록을 찾고, 없으면 찾지 못했다고 말한다", () => {
  const j = (id: string, sec: number, traced = true): AiJudgment => ({
    id,
    at: new Date(at(sec)).toISOString(),
    summary: "",
    outcome: "",
    trace: traced ? { mode: "MANUAL", evidence: [], evidenceAt: new Date(at(sec)).toISOString(), hypothesis: null, candidates: [], checks: [], result: "" } : null,
  });
  const history = [j("c", 50), j("b", 20), j("stop", 30, false), j("a", 5)];
  assert.equal(judgmentAt(history, new Date(at(35)).toISOString())?.id, "b", "판단 과정이 없는 기록(AI 정지)은 건너뛴다");
  assert.equal(judgmentAt(history, new Date(at(50.5)).toISOString())?.id, "c", "1초 여유 안의 기록을 찾는다");
  assert.equal(judgmentAt(history, new Date(at(200)).toISOString()), null, "60초보다 먼 기록은 그 행의 판단이 아니다");
});

test("상세 층 주소는 사건을 노드보다 먼저 읽고, 층을 바꿔도 다른 검색어는 그대로 둔다", () => {
  assert.deepEqual(detailFromSearch("?incident=inc-1&node=app-1"), { kind: "incident", id: "inc-1" });
  assert.deepEqual(detailFromSearch("?node=app-2"), { kind: "node", id: "app-2" });
  assert.equal(detailFromSearch("?id=rpt-1"), null);
  assert.equal(searchWithDetail("?id=rpt-1", { kind: "node", id: "app-3" }), "?id=rpt-1&node=app-3");
  assert.equal(searchWithDetail("?incident=inc-1", { kind: "node", id: "app-1" }), "?node=app-1");
  assert.equal(searchWithDetail("?incident=inc-1", null), "");
});

test("문장의 퍼센트 기호는 뒤 조사와 붙고 가운뎃점은 줄 머리에 서지 않는다(§12-12)", () => {
  assert.equal(joinDots("앱 평균 CPU가 74%입니다 · 응답 p95 465ms"), "앱 평균 CPU가 74%\u2060입니다\u00a0· 응답 p95 465ms");
  assert.equal(joinDots("평균 CPU 73%로 받고 있습니다"), "평균 CPU 73%\u2060로 받고 있습니다");
  assert.equal(joinDots("사용률 30% 이하"), "사용률 30% 이하", "뒤가 공백이면 그대로다");
});

test("조치 확인 제목의 조사는 받침과 단위의 읽는 소리를 따른다", () => {
  assert.equal(objectParticle("증설 CPU 임계"), "를");
  assert.equal(objectParticle("임계값"), "을");
  assert.equal(directionParticle("55%"), "로");
  assert.equal(directionParticle("20시간"), "으로");
  assert.equal(directionParticle("40크레딧"), "으로");
  assert.equal(directionParticle("10초"), "로");
  assert.equal(directionParticle("3"), "으로", "삼으로");
  assert.equal(directionParticle("1"), "로", "ㄹ 받침(일로)");
  assert.equal(
    actionVerb({ kind: "THRESHOLD", title: "백업 지연 경보 26시간 → 20시간", change: { from: 26, to: 20, unit: "시간" } }),
    "백업 지연 경보를 26시간에서 20시간으로 낮춥니다",
  );
});
