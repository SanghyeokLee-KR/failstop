/**
 * 3라운드 관제 묶음 A(관리자 설계 4-10-2·4-10-3)의 원천 규칙. 사건 담당 자동 배정과 담당 지정, 단계별 호출(5·15·30분,
 * 확인·해제로 멈춤), 사후 분석, 사건 생애 다섯 단계, 런북 고르기, DB 통계, 자산 목록, AI 모드·정지 이력, http 원천의
 * 원천 연결 전을 결정적으로 본다. 시계는 직접 넘긴다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { OpsSim, SYSTEM_ACTOR, type OpsActorRef } from "../lib/admin/opsSim";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { AdminError } from "../lib/admin/api";
import { aiModeHistory } from "../lib/admin/aiHistory";
import { assetRows } from "../lib/admin/assets";
import { bytesWord, lsnBytes, lsnOf, walGap } from "../lib/admin/dbFacts";
import { MODEL_EVAL_METRICS, MODEL_EVALUATION } from "../lib/admin/modelEval";
import { ESCALATION_POLICY, ONCALL_ROSTER, oncallDesk, onDutyAt } from "../lib/admin/oncall";
import { escalationOf, incidentSections, incidentsIn, incidentTimes, isOperatorTouch, lifecycleOf, nodeList } from "../lib/admin/opsDetail";
import { collectTargets } from "../lib/admin/opsHealth";
import { RUNBOOKS, runbookForAlert } from "../lib/admin/runbooks";
import { spanWord, weekStartKst } from "../lib/admin/timeText";
import { boardOf, emptyExtras, type WireOverview } from "../lib/admin/httpOps";
import type { AuditEntry, ControlCenter, DbStats, Incident, IncidentLog, OnCallDesk, OpsAlert, OpsHost, ReadTier, Replication } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const OP2: OpsActorRef = { id: "op-02", name: "이오퍼" };
// 로컬 정오. 하루 경계와 새벽 백업 시각에 걸리지 않게 한다
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}
const log = (c: ControlCenter) => ready<IncidentLog>(c.incidents);

/** 따라잡기 상한(900틱)에 걸리지 않게 10분씩 나눠 시계를 넘긴다 */
function run(sim: OpsSim, toSec: number): ControlCenter {
  for (let s = 600; s < toSec; s += 600) sim.advance(at(s));
  return sim.snapshot(at(toSec));
}

test("당번은 월요일 09:00 KST에 바뀌고, 새 사건은 연 순간의 당번이 담당으로 자동 배정된다", () => {
  const monday = weekStartKst(T0);
  assert.equal(new Date(monday + 9 * 3_600_000).getUTCDay(), 1, "교대 경계는 KST 월요일");
  const a = onDutyAt(monday + 1000);
  const b = onDutyAt(monday + 7 * 86_400_000 + 1000);
  assert.notEqual(a.person, b.person, "주마다 다른 사람이다");
  assert.equal(onDutyAt(monday - 1000).person, b.person, "두 사람이 번갈아 맡는다");
  const desk: OnCallDesk = oncallDesk(T0);
  assert.equal(desk.onDuty?.person, onDutyAt(T0).person);
  assert.equal(desk.rotation.length, 2);
  assert.deepEqual(desk.policy.map((p) => p.afterMin), [5, 15, 30]);

  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const inc = log(sim.snapshot(at(20))).incidents.find((i) => i.resolvedAt === null);
  assert.ok(inc && inc.owner);
  assert.equal(inc.owner.name, onDutyAt(Date.parse(inc.openedAt)).person);
  assert.equal(inc.owner.assignedBy, "시스템");
  assert.equal(inc.owner.role, "서비스 운영 담당");
  assert.deepEqual(inc.pages, [], "5분 전에는 호출하지 않는다");
});

test("확인·결정이 없으면 5·15·30분에 1차·2차·책임자를 호출하고 사건 기록에 남긴다", () => {
  const sim = new OpsSim(T0);
  sim.setBotTarget(200, OP, "시험", at(1));
  const first = log(sim.snapshot(at(10))).incidents.find((i) => i.resolvedAt === null);
  assert.ok(first, "봇 용량 보호로 사건이 열린다");
  const opened = Date.parse(first.openedAt);
  const c = run(sim, Math.ceil((opened - T0) / 1000) + 31 * 60);
  const inc = log(c).incidents.find((i) => i.id === first.id);
  assert.ok(inc && inc.pages);
  assert.deepEqual(inc.pages.map((p) => p.level), [1, 2, 3]);
  for (const [i, p] of inc.pages.entries()) {
    assert.equal(Date.parse(p.at) - opened, ESCALATION_POLICY[i].afterMin * 60_000, `${p.level}단계는 정책 시각에 나간다`);
    assert.equal(p.state, "SENT");
  }
  assert.equal(inc.pages[0].person, onDutyAt(Date.parse(inc.pages[0].at)).person, "1차는 그때의 당번");
  assert.equal(inc.pages[1].person, "이오퍼");
  assert.equal(inc.pages[2].person, "김수퍼");
  const notified = log(c).events.filter((e) => e.incidentId === inc.id && e.kind === "NOTIFIED" && e.text.startsWith("호출 "));
  assert.equal(notified.length, 3);
  const view = escalationOf(inc, ESCALATION_POLICY, log(c).events);
  assert.ok(view);
  assert.equal(view.level, 3);
  assert.equal(view.paging, true);
  assert.equal(view.next, null, "다 보냈으면 다음 호출이 없다");
});

test("운영자가 확인하면 보낸 호출이 확인으로 바뀌고 다음 단계로 올리지 않는다. 해제되면 남은 호출은 닫힌다", () => {
  const sim = new OpsSim(T0);
  sim.setBotTarget(200, OP, "시험", at(1));
  const first = log(sim.snapshot(at(10))).incidents.find((i) => i.resolvedAt === null);
  assert.ok(first);
  const opened = Date.parse(first.openedAt);
  const base = Math.ceil((opened - T0) / 1000);
  let c = run(sim, base + 6 * 60);
  let inc = log(c).incidents.find((i) => i.id === first.id) as Incident;
  assert.deepEqual(inc.pages?.map((p) => [p.level, p.state]), [[1, "SENT"]]);
  // 담당 지정은 확인이 아니라 호출을 멈추지 않는다. 자동 배정된 당번이 아닌 사람에게 넘긴다
  const other = inc.owner?.operatorId === OP.id ? OP2 : OP;
  sim.assignIncident(inc.id, other, OP, at(base + 6 * 60 + 5));
  c = sim.snapshot(at(base + 6 * 60 + 6));
  inc = log(c).incidents.find((i) => i.id === first.id) as Incident;
  assert.equal(inc.owner?.name, other.name);
  assert.equal(inc.owner?.assignedBy, "김수퍼");
  assert.ok(log(c).events.some((e) => e.incidentId === inc.id && e.kind === "ASSIGNED" && e.actor === "OPERATOR"));
  assert.equal(isOperatorTouch({ actor: "OPERATOR", kind: "ASSIGNED" }), false);
  const alert = ready<OpsAlert[]>(c.alerts).find((a) => a.incidentId === inc.id);
  assert.ok(alert, "경보는 속한 사건을 싣는다");
  sim.ackAlert(alert.id, OP, at(base + 7 * 60));
  c = run(sim, base + 20 * 60);
  inc = log(c).incidents.find((i) => i.id === first.id) as Incident;
  assert.deepEqual(inc.pages?.map((p) => [p.level, p.state]), [[1, "ACKED"]], "확인 뒤 2차를 부르지 않는다");
  const view = escalationOf(inc, ESCALATION_POLICY, log(c).events);
  assert.equal(view?.steps.find((s) => s.key === "l2")?.state, "skipped");
  assert.equal(view?.steps.find((s) => s.key === "l2")?.note, "확인으로 멈춤");
  assert.throws(() => sim.assignIncident(inc.id, other, OP, at(base + 20 * 60 + 1)), (e: unknown) => e instanceof AdminError && e.code === "invalid_parameter");
});

test("호출 단계의 첫 알림은 사건의 첫 Teams 경보 알림이고, 그 기록이 없으면 보냈다고 적지 않는다", () => {
  const sim = new OpsSim(T0);
  const l = log(sim.snapshot(at(1)));
  const night = l.incidents.find((i) => i.title === "복제 지연 · db-standby" && i.pages?.length === 2) as Incident;
  assert.ok(night, "호출이 두 번 나간 새벽 사건");
  assert.equal(new Date(Date.parse(night.openedAt) + 9 * 3_600_000).toISOString().slice(11, 16), "03:20", "새벽 사건은 03:20 KST에 연다");
  assert.ok(T0 - Date.parse(night.openedAt) > 86_400_000, "오늘 목록이 아니라 7일 목록에 선다");
  const view = escalationOf(night, ESCALATION_POLICY, l.events);
  assert.equal(view?.steps[0].state, "done");
  assert.equal(view?.steps[0].at, night.openedAt, "경보 발생과 같은 시각의 경보 알림");
  assert.equal(incidentSections(night, l.events).teams.length, 1, "Teams 전송 절에 경보 알림 한 줄");
  const bare = escalationOf(night, ESCALATION_POLICY, l.events.filter((e) => !e.text.startsWith("Teams 경보 알림")));
  assert.equal(bare?.steps[0].state, "skipped");
  assert.equal(bare?.steps[0].note, "기록 없음");

  // 지금 서는 경보도 처음 설 때 Teams 경보 알림 한 줄을 남긴다
  sim.setBotTarget(200, OP, "시험", at(2));
  const c = sim.snapshot(at(12));
  const open = log(c).incidents.find((i) => i.resolvedAt === null) as Incident;
  assert.ok(open);
  const lines = log(c).events.filter((e) => e.incidentId === open.id);
  const fired = lines.filter((e) => e.kind === "OPENED").length;
  assert.ok(fired >= 1);
  assert.equal(lines.filter((e) => e.kind === "NOTIFIED" && e.text.startsWith("Teams 경보 알림")).length, fired);
  assert.equal(escalationOf(open, ESCALATION_POLICY, log(c).events)?.steps[0].state, "done");
});

test("한 사건에서 같은 경보가 묶음 간격 5분 안에 다시 서면 Teams 경보 알림을 다시 남기지 않는다", () => {
  const sim = new OpsSim(T0);
  // 200 OPS면 bot-1 CPU가 호스트 CPU 경보(85%)와 봇 용량 보호(70%)를 모두 넘는다
  sim.setBotTarget(200, OP, "시험", at(1));
  let c = sim.snapshot(at(12));
  const inc = log(c).incidents.find((i) => i.resolvedAt === null) as Incident;
  assert.ok(inc);
  const cards = (cc: ControlCenter) => log(cc).events.filter((e) => e.incidentId === inc.id && e.text === "Teams 경보 알림 · 호스트 CPU 높음 · bot-1").length;
  const hostCpu = (cc: ControlCenter) => ready<OpsAlert[]>(cc.alerts).some((a) => a.name === "호스트 CPU 높음" && a.target === "bot-1");
  assert.ok(hostCpu(c));
  assert.equal(cards(c), 1);
  // 100 OPS면 CPU 73% 안팎이라 호스트 CPU 경보만 풀리고 봇 용량 보호로 사건은 열려 있다
  sim.setBotTarget(100, OP, "시험", at(13));
  c = sim.snapshot(at(30));
  assert.equal(hostCpu(c), false);
  assert.equal(log(c).incidents.find((i) => i.id === inc.id)?.resolvedAt, null);
  sim.setBotTarget(200, OP, "시험", at(31));
  c = sim.snapshot(at(45));
  assert.ok(hostCpu(c), "다시 섰다");
  assert.equal(cards(c), 1, "5분 안이라 새 카드가 없다");
  // 5분이 지난 뒤 다시 서면 새 카드다
  sim.setBotTarget(100, OP, "시험", at(46));
  c = run(sim, 400);
  assert.equal(hostCpu(c), false);
  sim.setBotTarget(200, OP, "시험", at(401));
  c = sim.snapshot(at(415));
  assert.ok(hostCpu(c));
  assert.equal(cards(c), 2);
});

test("사건 생애는 발생·확인·대응·해제·사후 분석이고, 확인 없이 자동 조치로 풀리거나 조치 없이 풀린 단계는 건넌다", () => {
  const sim = new OpsSim(T0);
  const c = sim.snapshot(at(1));
  const l = log(c);
  const bySeed = (title: string, minutes: number) =>
    l.incidents.find((i) => i.title === title && i.resolvedAt !== null && Date.parse(i.resolvedAt) - Date.parse(i.openedAt) === minutes * 60_000) as Incident;
  // 새벽 복제 지연: 1·2차 호출 뒤 16분에 확인, 조치 없이 해제, 사후 분석 전
  const night = bySeed("복제 지연 · db-standby", 17);
  assert.ok(night);
  const s1 = lifecycleOf(night, l.events);
  assert.deepEqual(s1.map((s) => s.state), ["done", "done", "skipped", "done", "current"]);
  assert.equal(s1[2].note, "조치 없이 해제");
  assert.equal(s1[4].note, "작성 전");
  assert.equal(incidentTimes(night, l.events).ttaMs, 16 * 60_000);
  assert.deepEqual(night.pages?.map((p) => [p.level, p.state]), [[1, "ACKED"], [2, "ACKED"]]);
  // 디스크 여유 부족: 확인 2분, 런북 조치 4분, 사후 분석 완료
  const disk = bySeed("디스크 여유 부족 · bot-1", 9);
  const s2 = lifecycleOf(disk, l.events);
  assert.deepEqual(s2.map((s) => s.state), ["done", "done", "done", "done", "done"]);
  assert.equal(disk.runbookId, "DISK_CLEANUP");
  assert.equal(disk.postmortem?.status, "DONE");
  assert.ok(s2[2].marks.some((m) => m.label === "AI 판단"), "대응 칸 아래에 AI 판단 표지");
  // 응답 지연: 승인 1분(확인과 대응), 사후 분석 초안
  const lat = bySeed("응답 지연 증가 · 외부 응답", 5);
  const s3 = lifecycleOf(lat, l.events);
  assert.equal(s3[4].state, "current");
  assert.equal(s3[4].note, "초안");
  assert.ok(s3[2].marks.some((m) => m.label === "승인"));
  // 진행 중인 사건은 오지 않은 첫 단계가 지금 단계다
  sim.startScenario("READ_SURGE", at(2));
  const open = log(sim.snapshot(at(20))).incidents.find((i) => i.resolvedAt === null) as Incident;
  const s4 = lifecycleOf(open, log(sim.snapshot(at(20))).events);
  assert.equal(s4[1].state, "current", "확인 전이면 확인이 지금 단계");
  assert.equal(s4[4].state, "todo", "해제 전에는 사후 분석을 쓸 때가 아니다");
});

test("사후 분석은 해제된 사건에만 쓰고, 완료하면 생애의 다섯째 단계가 서며 고칠 수 없다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const open = log(sim.snapshot(at(20))).incidents.find((i) => i.resolvedAt === null) as Incident;
  const draft = { cause: "조회 급증", impact: "p95 목표 초과", timeline: [{ at: open.openedAt, text: "경보 발생" }], actions: [{ task: "임계 검토", owner: "김수퍼", due: "2026-09-30", done: false }] };
  assert.throws(() => sim.savePostmortem(open.id, draft, false, OP, at(21)), /해제 전/);
  const p = ready<{ proposals: { id: string; kind: string; status: string }[] }>(sim.snapshot(at(21)).ai).proposals.find((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.ok(p);
  sim.decide(p.id, "APPROVE", OP, at(22));
  let c = sim.snapshot(at(22));
  for (let s = 30; s <= 600 && (log(c).incidents.find((i) => i.id === open.id) as Incident).resolvedAt === null; s += 10) c = sim.snapshot(at(s));
  const done = log(c).incidents.find((i) => i.id === open.id) as Incident;
  assert.ok(done.resolvedAt);
  const t = Date.parse(done.resolvedAt) + 5000;
  assert.throws(() => sim.savePostmortem(open.id, { ...draft, cause: " " }, true, OP, t), /원인과 영향/);
  sim.savePostmortem(open.id, draft, false, OP, t);
  let pm = (log(sim.snapshot(t + 1000)).incidents.find((i) => i.id === open.id) as Incident).postmortem;
  assert.equal(pm?.status, "DRAFT");
  sim.savePostmortem(open.id, draft, true, OP2, t + 2000);
  c = sim.snapshot(t + 3000);
  const inc = log(c).incidents.find((i) => i.id === open.id) as Incident;
  pm = inc.postmortem;
  assert.equal(pm?.status, "DONE");
  assert.equal(pm?.updatedBy, "이오퍼");
  assert.equal(lifecycleOf(inc, log(c).events)[4].state, "done");
  assert.ok(log(c).events.some((e) => e.incidentId === inc.id && e.kind === "POSTMORTEM"));
  assert.equal(incidentTimes(inc, log(c).events).ttaMs, at(22) - Date.parse(inc.openedAt), "사후 분석은 확인까지(MTTA)를 바꾸지 않는다");
  assert.throws(() => sim.savePostmortem(open.id, draft, false, OP, t + 4000), /완료한 사후 분석/);
});

test("사건의 런북은 경보 이름과 임계값 키로 고르고, 복제 지연처럼 맞는 절차가 없으면 없다", () => {
  const a = (name: string, thresholdKey: OpsAlert["thresholdKey"] = null, target = "", detail = "") => runbookForAlert({ name, thresholdKey, target, detail });
  assert.equal(a("디스크 여유 부족", "host.disk_free_alert"), "DISK_CLEANUP");
  assert.equal(a("LinuxDiskSpaceLow"), "DISK_CLEANUP");
  assert.equal(a("봇 용량 보호", "bot.capacity_guard"), "BOT_STOP");
  assert.equal(a("백업 지연", "backup.delay_alert"), "BACKUP_RESTORE");
  assert.equal(a("DatabaseConnectionFailed"), "DB_PROMOTE");
  assert.equal(a("LinuxServiceFailed", null, "app-1", "exchange-app.service failed"), "APP_RESTART");
  assert.equal(a("LinuxServiceFailed", null, "app-1", "certbot-renew.service failed"), "CERT_RENEW");
  assert.equal(a("LinuxServiceFailed", null, "app-1", ""), null, "서비스를 모르면 짓지 않는다");
  assert.equal(a("복제 지연", "db.replication_lag_alert"), null);
  assert.equal(a("앱 계층 CPU 높음", "read.scale_out_cpu"), null);
  // 런북은 여섯이고 모두 사람이 SSM에서 하는 절차다. 명령이 있는 단계와 없는 단계를 모두 싣는다
  assert.deepEqual(RUNBOOKS.map((r) => r.id), ["DB_PROMOTE", "APP_RESTART", "DISK_CLEANUP", "CERT_RENEW", "BOT_STOP", "BACKUP_RESTORE"]);
  assert.equal(RUNBOOKS[0].steps.length, 7, "DB 수동 승격은 일곱 단계(관리자 설계 4-10-9)");
  for (const r of RUNBOOKS) {
    assert.ok(r.steps.length > 0 && r.sources.length > 0 && r.notForAi.length > 0, r.id);
  }
  const sim = new OpsSim(T0);
  sim.setBotTarget(200, OP, "시험", at(1));
  const inc = log(sim.snapshot(at(10))).incidents.find((i) => i.resolvedAt === null) as Incident;
  assert.equal(inc.runbookId, "BOT_STOP");
});

test("DB 통계의 Standby 재생 위치는 Primary WAL에서 복제 지연 바이트만큼 뒤이고 WAL은 앞으로만 간다", () => {
  const sim = new OpsSim(T0);
  let prev = 0;
  for (const sec of [3, 30, 90]) {
    const c = sim.snapshot(at(sec));
    const db = ready<DbStats>(c.database);
    const repl = ready<Replication>(c.replication);
    assert.equal(walGap(db), repl.lagBytes, `${sec}초: WAL 차이는 복제 지연 바이트`);
    assert.equal(db.mode, "ASYNC");
    assert.equal(db.failover, "PATRONI_BUILDING");
    assert.equal(db.slowQueries.state, "NOT_CONNECTED", "느린 질의는 sim에서도 원천 연결 전");
    assert.ok(db.connections && db.connections.used <= db.connections.max);
    const p = lsnBytes(db.wal?.primaryLsn ?? "") as number;
    assert.ok(p > prev, "WAL은 앞으로만 간다");
    prev = p;
  }
  assert.equal(lsnOf(lsnBytes("3/1C4A8F20") as number), "3/1C4A8F20");
  assert.equal(bytesWord(12_288), "12KB");
});

test("자산 목록은 노드 상세의 이웃 순서이고 수집기 상태는 설정의 수집 대상과 같은 함수로 센다", () => {
  const sim = new OpsSim(T0);
  let c = sim.snapshot(at(5));
  let rows = assetRows(c);
  assert.deepEqual(rows.map((r) => r.id), nodeList(c).map((n) => n.id));
  assert.deepEqual(rows.map((r) => r.id), ["app-1", "app-2", "app-3", "db-primary", "db-standby", "bot-1", "ai-1"]);
  const ai = rows.find((r) => r.id === "ai-1");
  assert.ok(ai && ai.assumed && ai.asset === null, "구축 전 ai-1은 가정 행이고 OS·가동 칸을 비운다");
  const off = rows.find((r) => r.id === "app-3");
  assert.equal(off?.collect, "OFF");
  assert.equal(off?.asset, null, "꺼진 app-3는 인스턴스가 없다");
  const hosts = ready<OpsHost[]>(c.hosts);
  for (const h of hosts) {
    assert.ok(h.asset && h.asset.os && h.asset.bootedAt, h.id);
    assert.deepEqual(h.asset.failedUnits, [], "수집되는 노드는 서비스 탭처럼 모두 실행 중");
  }
  assert.equal(spanWord(147 * 3_600_000), "6일 3시간", "app-1 예시는 시뮬레이터를 연 시각보다 147시간 앞에 부팅했다");
  // app-3를 켜면 새 인스턴스라 가동 시간이 켠 시각부터고 보안 업데이트 대기가 없다
  sim.scaleReadNodes(1, OP, at(6));
  c = sim.snapshot(at(20));
  rows = assetRows(c);
  const app3 = rows.find((r) => r.id === "app-3");
  assert.ok(app3 && app3.asset);
  assert.equal(Date.parse(app3.asset.bootedAt as string), at(6));
  assert.equal(app3.asset.securityUpdates, 0);
  const targets = collectTargets(c);
  assert.equal(rows.filter((r) => r.responding).length, targets.ok, "응답 행 수가 수집 대상 응답 수와 같다");
  assert.equal(ready<ReadTier>(c.readTier).nodes[0].state, "IN_SERVICE");
});

test("자산 예시의 OS와 커널 계열은 학교 첫 앱 노드의 첫 셸 점검 기록(증거본 070~071, 073)과 같다", () => {
  const readme = readFileSync(path.resolve(__dirname, "..", "..", "..", "증거본", "2026-09-19_학교AWS", "README.md"), "utf8");
  const os = /\| 070~071 \| `cat \/etc\/os-release` \| (Amazon Linux [\d.]+)\./.exec(readme)?.[1];
  const kernel = /\| 073 \| `uname -rm` \| (\d+\.\d+)\.\d+-/.exec(readme)?.[1];
  assert.ok(os && kernel, "증거본에서 OS와 커널 기록을 읽어야 공허 통과가 아니다");
  const sim = new OpsSim(T0);
  const assets = ready<OpsHost[]>(sim.snapshot(at(5)).hosts).map((h) => h.asset);
  sim.scaleReadNodes(1, OP, at(6));
  assets.push(assetRows(sim.snapshot(at(20))).find((r) => r.id === "app-3")?.asset ?? undefined);
  assert.equal(assets.length, 6, "고정 다섯 대와 켠 app-3");
  for (const a of assets) {
    assert.equal(a?.os, os);
    assert.equal(a?.kernel, `${kernel} 계열`);
  }
});

test("모드·정지 이력은 감사의 ops.ai.* 행에서 전환 전후와 사유·유효 시간·승인 티켓을 옮긴다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  await api.enableAiAuto(2, "야간 대기");
  await api.stopAiAuto();
  await api.stopAi();
  await api.resumeAi("점검 끝");
  const rows = aiModeHistory((await api.getAudit({ limit: 200 })).data);
  assert.deepEqual(rows.map((r) => r.kind), ["RESUME", "STOP", "AUTO_OFF", "AUTO_ON"], "최신이 앞");
  const on = rows[3];
  assert.equal(on.hours, 2);
  assert.equal(on.reason, "야간 대기");
  assert.ok(on.approvalId, "자동 켜기는 승인 티켓을 싣는다");
  assert.deepEqual([on.from, on.to], ["MANUAL", "AUTO"]);
  assert.deepEqual([rows[1].from, rows[1].to], ["MANUAL", "STOPPED"]);
  assert.equal(rows[0].reason, "점검 끝");
  // 서버 감사 목록은 사유를 싣지 않는다. 시스템 전환은 system:msp가 한다
  const server: AuditEntry[] = [
    { id: "9", at: "2026-09-25T01:00:00Z", operatorId: "op-01", operatorName: "김수퍼", action: "ops.ai.auto", target: "AUTO:4", approvalId: "31", result: "OK", requestId: "r" },
    { id: "10", at: "2026-09-25T01:30:00Z", operatorId: "system:msp", operatorName: "system:msp", action: "ops.ai.mode.expire", target: "MANUAL", approvalId: null, result: "OK", requestId: "r" },
    { id: "11", at: "2026-09-25T01:40:00Z", operatorId: "op-01", operatorName: "김수퍼", action: "ops.ai.stop", target: "AI", approvalId: null, result: "DENIED", requestId: "r" },
  ];
  const srv = aiModeHistory(server);
  assert.deepEqual(srv.map((r) => [r.kind, r.hours, r.reason, r.actor]), [["EXPIRE", null, null, "SYSTEM"], ["AUTO_ON", 4, null, "OPERATOR"]], "거부된 행은 옮기지 않는다");
  assert.equal(SYSTEM_ACTOR.id, "system:msp");
});

test("모델 평가는 측정 문서의 재평가 최종 회차 값이고 잘못 실행될 조치가 첫 행이다", () => {
  assert.equal(MODEL_EVAL_METRICS[0].key, "wrongExec");
  const [gpt, qwen] = MODEL_EVALUATION.rows;
  assert.deepEqual([gpt.model, gpt.wrongExec, gpt.correct, gpt.latency], ["gpt-oss:20b", 2, "98/111", "5.9초 / 8.5초"]);
  assert.deepEqual([qwen.model, qwen.wrongExec, qwen.correct, qwen.latency], ["qwen3.6:35b-a3b", 0, "90/111", "2.2초 / 3.2초"]);
  assert.equal(MODEL_EVALUATION.promptVersion, "msp-ai/3");
  // 측정 문서에 같은 값이 있어야 한다. 문서가 바뀌면 이 기록도 함께 고친다
  const doc = readFileSync(path.resolve(__dirname, "..", "..", "..", "docs", "measurements", "20260925.md"), "utf8");
  assert.match(doc, /\| 모델 정답 \| 98 \| 98 \| 87 \| 90 \|/);
  assert.match(doc, /\| 지연 p50 \/ p95 \| 5\.5초 \/ 8\.3초 \| 5\.9초 \/ 8\.5초 \| 2\.3초 \/ 3\.6초 \| 2\.2초 \/ 3\.2초 \|/);
  assert.match(doc, /\| 카탈로그 밖 조치 \| 1 \| 1 \| 12 \| 9 \|/);
});

test("http 원천: 경보는 속한 사건을, 사건은 런북을 싣고 당번·호출·사후 분석·DB 통계·자산은 원천 연결 전이다", () => {
  const FIX = path.resolve(__dirname, "..", "..", "tests", "fixtures", "msp");
  const w = (JSON.parse(readFileSync(path.join(FIX, "overview-full.json"), "utf8")) as { body: WireOverview }).body;
  const c = boardOf(w, emptyExtras(), Date.parse(w.generated_at ?? ""));
  assert.equal(c.oncall.state, "NOT_CONNECTED");
  assert.equal(c.database.state, "NOT_CONNECTED");
  const hosts = ready<OpsHost[]>(c.hosts);
  for (const h of hosts) assert.deepEqual(h.asset, { os: null, kernel: null, bootedAt: null, inodeUsedPct: null, failedUnits: null, securityUpdates: null }, h.id);
  const alerts = ready<OpsAlert[]>(c.alerts);
  const wired = (w.alerts?.data ?? []).filter((a) => a.incident_id !== null && a.incident_id !== undefined);
  assert.ok(wired.length > 0, "픽스처에 사건이 있는 경보가 있어야 공허 통과가 아니다");
  for (const a of wired) assert.equal(alerts.find((x) => x.id === a.fingerprint)?.incidentId, String(a.incident_id));
  const extras = emptyExtras();
  extras.incidents = (JSON.parse(readFileSync(path.join(FIX, "incidents.json"), "utf8")) as { body: { data: NonNullable<typeof extras.incidents> } }).body.data;
  const withLog = boardOf(w, extras, Date.parse(w.generated_at ?? ""));
  const incs = ready<IncidentLog>(withLog.incidents).incidents;
  assert.ok(incs.length > 0);
  for (const i of incs) {
    assert.equal(i.owner, undefined, "담당 원천 연결 전");
    assert.equal(i.pages, undefined, "호출 원천 연결 전");
    assert.equal(escalationOf(i, null, []), null);
    assert.equal(lifecycleOf(i, [])[4].note, i.resolvedAt ? "원천 연결 전" : null);
  }
  assert.ok(incidentsIn(ready<IncidentLog>(withLog.incidents), "week", Date.parse(w.generated_at ?? "")).length > 0);
  // 명부는 sim의 연락 체계 조회와 관제 판 당번 칸이 같은 한 벌이다
  assert.equal(ONCALL_ROSTER.length, 3);
});

test("DB 통계 원천이 없으면 WAL 차이를 짓지 않는다", () => {
  const empty: Pick<DbStats, "wal"> = { wal: null };
  assert.equal(walGap(empty), null);
  assert.equal(walGap({ wal: { primaryLsn: "잘못", standbyReplayLsn: "0/1" } }), null);
});
