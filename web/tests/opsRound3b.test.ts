/**
 * 3라운드 구현 묶음 B(관리자 설계 4-10-3·4-10-4)의 원천 규칙과 화면 사이 일치. 계획 작업 창의 입력 규칙, 변경 요청의 다섯 단계,
 * AI 경보 보류의 창 판정(S6·S7), 조치 기록의 옮김·거르기·CSV, CPU 크레딧 식, 용량 전망, app-3 가동 시간, 인증서 판정,
 * 그리고 http 원천의 원천 연결 전을 결정적으로 본다. 관제 시뮬레이터의 시계는 직접 넘긴다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { AdminError } from "../lib/admin/api";
import {
  actionsCsv,
  aiRecentOf,
  applyFilter,
  classify,
  csvFileName,
  DEFAULT_FILTER,
  isAction,
  linkReverts,
  serverRecords,
} from "../lib/admin/actionLog";
import { certDaysLeft, certTone, SCHOOL_CERTIFICATES, soonestCert } from "../lib/admin/certs";
import {
  actionsIn,
  aiMaySilenceKind,
  changeInputError,
  changeSpan,
  changeStages,
  defaultSpan,
  kstFields,
  kstIso,
  openPlanItems,
  openWindows,
  planDurationWord,
  planItems,
  planSpanWord,
  simAlertLabels,
  windowCovers,
  windowInputError,
  windowRemainWord,
} from "../lib/admin/changes";
import { creditNetPerHour, hoursToEmpty, T3_SMALL } from "../lib/admin/credits";
import { onSecondsFromActivity, tookWord } from "../lib/admin/tierActivity";
import { kstDay } from "../lib/admin/timeText";
import { boardOf, emptyExtras, type WireOverview, type WireWindow } from "../lib/admin/httpOps";
import { badgeOf } from "../lib/admin/navBadges";
import type { AuditEntry, CapacityOutlook, ChangeDesk, ChangeInput, ControlCenter, OpsAlert, OpsJobs, ReadTier, ThresholdRegistry } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
// 로컬 정오. 하루 경계와 새벽 백업 시각에 걸리지 않게 한다
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;
const iso = (ms: number) => new Date(ms).toISOString();

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}

/** 따라잡기 상한(900틱)에 걸리지 않게 10분씩 나눠 시계를 넘긴다 */
function run(sim: OpsSim, fromSec: number, toSec: number): ControlCenter {
  for (let s = fromSec + 600; s < toSec; s += 600) sim.advance(at(s));
  return sim.snapshot(at(toSec));
}

const INPUT: ChangeInput = {
  title: "앱 계층 CPU 경보 시험",
  target: "앱 계층",
  plan: "조회 부하를 올려 경보가 서는지 봅니다",
  rollback: "부하를 멈춥니다",
  risk: "LOW",
  startsAt: iso(at(60)),
  endsAt: iso(at(3600)),
  alertname: null,
  labels: { role: "app" },
};

test("계획 작업 창 입력은 서버 계약의 규칙(24시간, 대상, 라벨 키, 사유)을 따른다", () => {
  const now = at(0);
  const ok = { startsAt: null, endsAt: iso(at(600)), alertname: null, labels: { node: "bot-1" }, reason: "봇 설정 교체" };
  assert.equal(windowInputError(ok, now), null);
  assert.match(windowInputError({ ...ok, endsAt: iso(at(-1)) }, now) ?? "", /이미 지났/);
  assert.match(windowInputError({ ...ok, startsAt: iso(at(700)) }, now) ?? "", /시작보다 뒤/);
  assert.match(windowInputError({ ...ok, endsAt: iso(at(24 * 3600 + 1)) }, now) ?? "", /24시간/);
  assert.equal(windowInputError({ ...ok, endsAt: iso(at(24 * 3600)) }, now), null, "24시간 정각은 된다");
  assert.match(windowInputError({ ...ok, labels: {} }, now) ?? "", /하나는 있어야/);
  assert.equal(windowInputError({ ...ok, labels: {}, alertname: "메모리 부족" }, now), null, "경보명만으로도 된다");
  assert.match(windowInputError({ ...ok, labels: { instance: "x" } as never }, now) ?? "", /라벨 키/);
  assert.match(windowInputError({ ...ok, reason: "  " }, now) ?? "", /사유/);
  assert.match(windowInputError({ ...ok, reason: "가".repeat(201) }, now) ?? "", /200자/);
  assert.match(changeInputError({ ...INPUT, title: "x" }, now) ?? "", /두 글자/);
  assert.match(changeInputError({ ...INPUT, rollback: "" }, now) ?? "", /되돌리기/);
  assert.equal(changeInputError(INPUT, now), null);

  // 입력칸은 브라우저 시간대와 무관하게 KST다
  const k = kstFields(Date.parse("2026-09-24T18:05:00Z"));
  assert.deepEqual(k, { date: "2026-09-25", time: "03:05" });
  assert.equal(kstIso(k.date, k.time), "2026-09-24T18:05:00.000Z");
  assert.equal(kstIso("2026-9-25", "03:05"), null);
  const span = defaultSpan(Date.parse("2026-09-25T00:03:10Z"));
  assert.equal(iso(span.start), "2026-09-25T01:10:00.000Z", "10분 단위로 올리고 한 시간 뒤");
  assert.equal(span.end - span.start, 3_600_000);
  assert.equal(planSpanWord("2026-09-25T18:00:00Z", "2026-09-25T19:00:00Z"), "9.26 03:00 → 04:00 · 1시간");
  assert.equal(planSpanWord("2026-09-25T14:30:00Z", "2026-09-25T15:30:00Z"), "9.25 23:30 → 9.26 00:30 · 1시간", "날이 바뀌면 끝에도 날짜");
  assert.equal(planDurationWord(29 * 60_000 + 40_000), "30분", "계획 길이는 분 단위");
  assert.equal(planDurationWord(65 * 60_000), "1시간 5분");
  assert.equal(planDurationWord(20_000), "1분 미만");
  assert.equal(windowRemainWord(3_600_000 + 60_000), "1시간 1분");
  assert.equal(windowRemainWord(59 * 60_000 + 5_000), "59:05", "한 시간 아래는 mm:ss");
});

test("변경 요청은 등록, 승인, 진행(창 열림), 종료(창 닫힘) 순서로만 흐르고 취소는 진행 전만 된다", () => {
  const sim = new OpsSim(T0);
  assert.throws(() => sim.createChange({ ...INPUT, title: "x" }, OP, at(10)), (e: unknown) => e instanceof AdminError && /두 글자/.test(e.message));
  const c = sim.createChange(INPUT, OP, at(10));
  assert.equal(c.state, "REQUESTED");
  assert.equal(c.requestedBy, OP.name);
  assert.throws(() => sim.startChange(c.id, OP, at(20)), /승인 전/);
  sim.approveChange(c.id, OP, "apr-test", at(30));
  assert.throws(() => sim.checkChangeApproval(c.id, at(31)), /승인할 수 없는 상태/);
  const w = sim.startChange(c.id, OP, at(40));
  assert.equal(w.changeId, c.id);
  assert.deepEqual(w.labels, { role: "app" });
  assert.equal(w.endsAt, c.endsAt, "창은 지금부터 계획한 끝까지");
  assert.throws(() => sim.cancelChange(c.id, OP, at(41)), /진행 전/);

  let desk = ready<ChangeDesk>(sim.snapshot(at(50)).changes);
  let req = ready(desk.changes).find((x) => x.id === c.id);
  assert.ok(req);
  assert.equal(req.state, "IN_PROGRESS");
  assert.equal(req.windowId, w.id);
  assert.deepEqual(changeStages(req, null).map((m) => m.state), ["done", "done", "done", "current", "todo"]);
  assert.deepEqual(openWindows(desk.windows, at(50)).map((x) => x.id), [w.id]);

  sim.finishChange(c.id, "ROLLED_BACK", "경보가 서지 않아 되돌림", OP, at(300));
  desk = ready<ChangeDesk>(sim.snapshot(at(301)).changes);
  req = ready(desk.changes).find((x) => x.id === c.id);
  assert.ok(req);
  assert.equal(req.state, "ROLLED_BACK");
  assert.equal(req.result, "롤백 · 경보가 서지 않아 되돌림");
  assert.deepEqual(openWindows(desk.windows, at(301)), [], "종료하면 요청의 창도 닫힌다");
  const closed = desk.windows.find((x) => x.id === w.id);
  assert.equal(closed?.endedBy, OP.name);
  assert.deepEqual(changeSpan(req, desk.windows), { start: iso(at(40)), end: iso(at(300)) }, "변경이 차지한 시간은 창을 연 때부터 닫은 때까지");
  assert.deepEqual(changeStages(req, 2).map((m) => [m.state, m.note]), [
    ["done", null], ["done", null], ["done", null], ["done", "롤백"], ["done", "조치 2건"],
  ]);

  const d = sim.createChange({ ...INPUT, title: "두 번째 요청" }, OP, at(400));
  sim.cancelChange(d.id, OP, at(401));
  const canceled = ready(ready<ChangeDesk>(sim.snapshot(at(402)).changes).changes).find((x) => x.id === d.id);
  assert.equal(canceled?.state, "CANCELED");
  assert.deepEqual(changeStages(canceled!, null).slice(1).map((m) => [m.state, m.note]), [
    ["skipped", "취소"], ["skipped", "취소"], ["skipped", "취소"], ["skipped", "취소"],
  ]);
  assert.equal(sim.endWindow(w.id, OP, at(403)), false, "이미 닫힌 창은 바꾸지 않는다");
});

test("AI의 경보 보류는 계획 작업 창 안의 주의 경보만이고 보류 끝은 창의 끝을 넘지 않으며, 창이 닫히면 실행하지 못한다(S6·S7)", () => {
  const silences = (c: ControlCenter) => ready(c.ai).proposals.filter((p) => p.kind === "SILENCE" && p.status === "PROPOSED");

  // 창이 없으면 주의 경보가 울려도 AI는 보류를 내지 않는다
  const bare = new OpsSim(T0);
  bare.startScenario("READ_SURGE", at(2));
  const bareBoard = run(bare, 2, 120);
  assert.ok(ready<OpsAlert[]>(bareBoard.alerts).some((a) => a.name === "앱 계층 CPU 높음" && a.severity === "warning"), "주의 경보가 울려야 공허 통과가 아니다");
  assert.deepEqual(silences(bareBoard), []);

  // 앱 계층 창(10분)이 열리면 그 창의 대상 경보만, 창이 끝나기 전까지만 보류를 낸다
  const sim = new OpsSim(T0);
  const w = sim.createWindow({ startsAt: null, endsAt: iso(at(600)), alertname: null, labels: { role: "app" }, reason: "조회 증설 시험" }, OP, at(1));
  sim.startScenario("READ_SURGE", at(2));
  const board = run(sim, 2, 120);
  const props = silences(board);
  assert.equal(props.length, 1, "창 대상(role=app)인 앱 계층 CPU 경보 하나");
  assert.match(props[0].title, /앱 계층 CPU 높음/);
  const minutes = Number(/보류 (\d+)분/.exec(props[0].title)?.[1] ?? NaN);
  assert.ok(minutes >= 1 && minutes <= 9, `창 끝(600초)까지 남은 시간을 넘지 않는다 · ${props[0].title}`);
  const judged = ready(board.ai).history.find((h) => h.trace?.candidates.some((x) => x.kind === "SILENCE"));
  const idx = judged?.trace?.candidates.findIndex((x) => x.kind === "SILENCE") ?? -1;
  const windowRow = judged?.trace?.checks.find((r) => r.rule === "WINDOW");
  assert.equal(windowRow?.results[idx]?.state, "PASS", "판단 과정의 창 규칙 행이 보류 후보를 통과시킨다");
  sim.decide(props[0].id, "APPROVE", OP, at(130));
  const silenced = ready<OpsAlert[]>(sim.snapshot(at(131)).alerts).find((a) => a.name === "앱 계층 CPU 높음");
  assert.equal(silenced?.state, "SILENCED");
  assert.ok(Date.parse(silenced?.silencedUntil ?? "") <= Date.parse(w.endsAt), "보류 끝은 창 끝을 넘지 않는다");

  // 창을 일찍 닫으면 남은 보류 조치안은 실행하지 못한다
  const sim2 = new OpsSim(T0);
  const w2 = sim2.createWindow({ startsAt: null, endsAt: iso(at(3600)), alertname: "앱 계층 CPU 높음", labels: {}, reason: "경보명으로 고른 창" }, OP, at(1));
  sim2.startScenario("READ_SURGE", at(2));
  const p2 = silences(run(sim2, 2, 120))[0];
  assert.ok(p2, "경보명으로 고른 창도 대상이 된다");
  assert.equal(sim2.endWindow(w2.id, OP, at(125)), true);
  assert.throws(() => sim2.decide(p2.id, "APPROVE", OP, at(126)), /창/);

  // 창 안이어도 복제·DB·백업·디스크 계열은 보류하지 않는다(S7)
  const disk = { thresholdKey: "host.disk_free_alert" as const, name: "디스크 여유 부족", target: "bot-1" };
  assert.equal(aiMaySilenceKind(disk, simAlertLabels(disk)), false);
  const backup = { thresholdKey: "backup.delay_alert" as const, name: "백업 지연", target: "db-primary" };
  assert.equal(aiMaySilenceKind(backup, simAlertLabels(backup)), false);
  const mem = { thresholdKey: "host.mem_alert" as const, name: "메모리 부족", target: "bot-1" };
  assert.equal(aiMaySilenceKind(mem, simAlertLabels(mem)), true);
  assert.equal(windowCovers({ ...w, labels: { node: "bot-1" } }, "메모리 부족", simAlertLabels(mem)), true);
  assert.equal(windowCovers({ ...w, labels: { node: "app-1" } }, "메모리 부족", simAlertLabels(mem)), false);
});

test("변경·작업 화면, 관제센터 모드 줄, 사이드바 배지가 같은 진행 중인 창을 본다", () => {
  const sim = new OpsSim(T0);
  const base = sim.snapshot(at(1));
  assert.equal(badgeOf("changes", base), null, "진행 중인 창이 없으면 배지가 없다");
  const c = sim.createChange(INPUT, OP, at(10));
  sim.approveChange(c.id, OP, "apr-test", at(20));
  const w = sim.startChange(c.id, OP, at(30));
  const solo = sim.createWindow({ startsAt: null, endsAt: iso(at(1200)), alertname: null, labels: { node: "bot-1" }, reason: "봇 설정 교체" }, OP, at(40));
  const board = sim.snapshot(at(50));
  const now = Date.parse(board.generatedAt);
  const desk = ready<ChangeDesk>(board.changes);
  // 변경·작업: 달력 항목의 진행 중(창만 등록한 창은 그 창, 요청이 연 창은 요청과 한 항목)
  const items = planItems(desk.windows, ready(desk.changes), now);
  const open = openPlanItems(items, now);
  assert.deepEqual(open.map((it) => it.window?.id), [solo.id, w.id], "끝이 먼저 오는 순서");
  assert.equal(open.find((it) => it.window?.id === w.id)?.change?.id, c.id);
  // 모드 줄: 같은 판정으로 연 창 수와 가장 먼저 끝나는 창
  const mode = openWindows(desk.windows, now);
  assert.deepEqual(mode.map((x) => x.id), open.map((it) => it.window?.id));
  // 사이드바 배지: 무채 '진행'과 개수
  const b = badgeOf("changes", board);
  assert.deepEqual(b && [b.tone, b.label, b.count, b.shape], ["info", "진행", 2, "play"]);
  // 창을 닫으면 세 자리가 함께 줄어든다
  sim.endWindow(solo.id, OP, at(60));
  const after = sim.snapshot(at(61));
  const afterDesk = ready<ChangeDesk>(after.changes);
  assert.deepEqual(openWindows(afterDesk.windows, Date.parse(after.generatedAt)).map((x) => x.id), [w.id]);
  const b2 = badgeOf("changes", after);
  assert.deepEqual(b2 && [b2.label, b2.count], ["진행", null], "한 개면 수를 적지 않는다");
});

test("인증서 남은 날은 관제센터 운영 작업 줄, 네트워크·보안, 사이드바 배지가 같은 판정이다", () => {
  // 오늘(9월)은 모두 30일 밖이라 배지가 없다
  const today = new OpsSim(T0).snapshot(at(1));
  const certs = ready<OpsJobs>(today.jobs).certificates ?? [];
  assert.equal(certs.length, SCHOOL_CERTIFICATES.length);
  assert.deepEqual(certs.map((c) => c.expiresOn).sort(), ["2026-11-19", "2026-12-18", "2026-12-18"], "학교 발급 기록의 만료일");
  assert.equal(badgeOf("security", today), null);

  // 11-20은 내부 mTLS가 이미 만료(■)이고 나머지 둘은 30일 이내(▲)다. 배지는 가장 무거운 판정과 그 수다
  for (const [day, tone, count] of [["2026-10-25", "warning", 1], ["2026-11-15", "danger", 1], ["2026-11-20", "danger", 1]] as const) {
    const t = Date.parse(`${day}T12:00:00+09:00`);
    const board = new OpsSim(t).snapshot(t + 1000);
    const list = ready<OpsJobs>(board.jobs).certificates ?? [];
    const now = Date.parse(board.generatedAt);
    // 관제센터 운영 작업 줄: 가장 먼저 만료되는 인증서의 판정
    const soon = soonestCert(list);
    assert.ok(soon);
    // 네트워크·보안: 인증서마다의 판정
    const tones = list.map((c) => certTone(certDaysLeft(c, now)));
    const b = badgeOf("security", board);
    assert.ok(b, `${day}: 배지가 선다`);
    assert.equal(b.tone, tone, `${day}: 배지 판정`);
    assert.equal(b.count, count, `${day}: 배지 수`);
    assert.equal(tones.filter((x) => x === tone).length, count, `${day}: 네트워크·보안의 판정 수`);
    assert.equal(certTone(certDaysLeft(soon, now)), tone, `${day}: 운영 작업 줄과 배지가 같은 판정`);
  }
  // 날짜 경계는 KST다. 만료 당일은 D-0, 지나면 음수다
  assert.equal(certDaysLeft({ expiresOn: "2026-11-19" }, Date.parse("2026-11-19T00:30:00+09:00")), 0);
  assert.equal(certDaysLeft({ expiresOn: "2026-11-19" }, Date.parse("2026-11-18T23:30:00+09:00")), 1);
  assert.equal(certDaysLeft({ expiresOn: "2026-11-19" }, Date.parse("2026-11-20T09:00:00+09:00")), -1);
});

test("app-3 가동 시간은 활동 기록에서 센 값과 관제 판의 값이 같다(기동·드레이닝, 끄는 중 다시 켜기 포함)", () => {
  const sim = new OpsSim(T0);
  sim.scaleReadNodes(1, OP, at(10), "시험 켜기");
  sim.advance(at(200));
  sim.scaleReadNodes(-1, OP, at(300), "시험 끄기");
  sim.advance(at(302));
  // 드레이닝(4초) 중에 다시 켠다. 켜진 구간은 끊기지 않는다
  sim.scaleReadNodes(1, OP, at(302), "다시 켜기");
  sim.advance(at(900));
  sim.scaleReadNodes(-1, OP, at(1000), "끄기");
  const board = run(sim, 1000, 1500);
  const tier = ready<ReadTier>(board.readTier);
  const outlook = ready<CapacityOutlook>(board.outlook);
  const now = Date.parse(board.generatedAt);
  assert.ok(tier.onSecondsToday !== null && tier.onSecondsToday > 900, "켜져 있던 시간이 쌓였다");
  assert.ok(Math.abs(onSecondsFromActivity(outlook.activity, now) - (tier.onSecondsToday ?? 0)) <= 2, `활동 기록 ${onSecondsFromActivity(outlook.activity, now)}초 · 판 ${tier.onSecondsToday}초`);
  const words = outlook.activity.slice(0, 4).map(tookWord);
  assert.match(words[0], /^드레이닝 \d+초$/);
  assert.match(words[1], /^기동 \d+초$/);
  assert.equal(words[2], "드레이닝 중 다시 켬");
  assert.match(words[3], /^기동 \d+초$/);
  assert.deepEqual(outlook.activity.slice(0, 4).map((a) => [a.action, a.via]), [["OFF", "OPERATOR"], ["ON", "OPERATOR"], ["OFF", "OPERATOR"], ["ON", "OPERATOR"]]);
  // 어제 자정 앞의 구간은 오늘로 세지 않는다
  const midnight = Date.parse(`${kstDay(now)}T00:00:00+09:00`);
  assert.equal(onSecondsFromActivity([{ id: "x", at: iso(midnight - 7200_000), action: "ON", actor: "OPERATOR", actorName: "", via: "OPERATOR", reason: "", tookSec: 60, interrupted: false, incidentId: null }, { id: "y", at: iso(midnight - 3600_000), action: "OFF", actor: "OPERATOR", actorName: "", via: "OPERATOR", reason: "", tookSec: 60, interrupted: false, incidentId: null }], now), 0);
});

test("CPU 크레딧 증감은 t3.small의 한 식이고 기준선 20%에서 0이다. 관제 판의 잔량도 이 식을 따른다", () => {
  assert.deepEqual([T3_SMALL.vcpu, T3_SMALL.earnPerHour, T3_SMALL.maxCredits, T3_SMALL.baselinePct], [2, 24, 576, 20]);
  assert.equal(creditNetPerHour(20), 0, "기준선이면 쌓는 양과 쓰는 양이 같다");
  assert.equal(creditNetPerHour(0), 24);
  assert.equal(creditNetPerHour(50), -36);
  assert.equal(hoursToEmpty(288, 50), 8);
  assert.equal(hoursToEmpty(288, 10), null, "쌓는 중이면 0이 되지 않는다");
  // CPU가 기준선보다 낮은 db-standby는 쌓고(최대 576), 봇 부하를 받는 bot-1은 기준선 위라 줄어든다
  const sim = new OpsSim(T0);
  const before = ready(sim.snapshot(at(1)).hosts);
  const after = ready(run(sim, 1, 3600).hosts);
  const credit = (list: typeof before, id: string) => list.find((h) => h.id === id)?.cpuCredits ?? NaN;
  const cpu = (id: string) => after.find((h) => h.id === id)?.cpuPct ?? NaN;
  assert.ok(cpu("db-standby") < T3_SMALL.baselinePct && credit(after, "db-standby") > credit(before, "db-standby"));
  assert.ok(cpu("bot-1") > T3_SMALL.baselinePct && credit(after, "bot-1") < credit(before, "bot-1"));
  const drop = credit(before, "bot-1") - credit(after, "bot-1");
  assert.ok(Math.abs(drop + creditNetPerHour(cpu("bot-1"))) < 8, `한 시간 줄어든 ${drop}개가 식의 값(${-creditNetPerHour(cpu("bot-1"))})과 가깝다`);
});

test("용량 전망은 근거 구간이 24시간 이상일 때 추세를 이어 예상 시각을 내고, 경보 기준은 임계값 관리의 디스크 여유 경보다", () => {
  const board = new OpsSim(T0).snapshot(at(1));
  const o = ready<CapacityOutlook>(board.outlook);
  assert.ok(o.basisHours >= 24);
  const free = ready<ThresholdRegistry>(board.thresholds).items.find((t) => t.key === "host.disk_free_alert")?.value ?? NaN;
  const now = Date.parse(board.generatedAt);
  for (const d of o.disks) {
    assert.ok(d.growthPctPerDay !== null && d.growthPctPerDay > 0, d.host);
    const days = (100 - free - d.usedPct) / d.growthPctPerDay;
    assert.ok(Math.abs(Date.parse(d.alertAt ?? "") - (now + days * 86_400_000)) < 2000, `${d.host}: 경보 기준 도달`);
    assert.ok(Date.parse(d.fullAt ?? "") > Date.parse(d.alertAt ?? ""), `${d.host}: 가득 참은 경보 뒤`);
  }
  assert.equal(o.dbGrowthBytesPerDay, 38_000_000, "데이터베이스 화면의 DB 크기 증가와 같은 값");
});

test("조치 기록은 감사 코드를 종류와 주해로 옮기고, 서버 감사의 읽기와 같은 요청의 필터 줄을 빼며 되돌림을 잇는다", () => {
  assert.deepEqual(classify("msp.alert.ack"), classify("ops.alert.ack"), "서버와 sim의 다른 코드를 같은 종류로 묶는다");
  assert.equal(classify("msp.alert.ack").category, "ALERT");
  assert.equal(classify("ai.auto_revert:READ_NODES").gloss, "AI 자동 되돌림");
  assert.equal(classify("execute:ops.change.approve").category, "CHANGE");
  assert.equal(classify("msp.window.end").gloss, "계획 작업 창 조기 종료");
  assert.equal(classify("POST /admin/v1/msp/unknown").gloss, "관리 요청 POST");
  assert.equal(isAction("GET /admin/v1/msp/overview"), false);
  assert.equal(isAction("ops.ai.chat"), false);

  const e = (id: string, action: string, target: string, requestId: string, result: AuditEntry["result"] = "OK", operatorId = "op-01"): AuditEntry => ({
    id, at: `2026-09-25T01:0${id}:00Z`, operatorId, operatorName: operatorId === "op-01" ? "김수퍼" : operatorId, action, target, approvalId: null, result, requestId,
  });
  const audit: AuditEntry[] = [
    e("6", "ops.ai.action.revert", "ai-action:77", "r6"),
    e("5", "POST /admin/v1/msp/ai/actions/77/revert", "/admin/v1/msp/ai/actions/77/revert", "r6"),
    e("4", "GET /admin/v1/msp/overview", "/admin/v1/msp/overview", "r4"),
    e("3", "POST /admin/v1/capacity/scale-out", "/admin/v1/capacity/scale-out", "r3", "DENIED", "op-02"),
    e("2", "ops.ai.action.approve", "ai-action:77", "r2"),
    e("1", "msp.alert.ack", "HostHighCpu", "r1"),
  ];
  const rows = serverRecords(audit);
  assert.deepEqual(rows.map((r) => r.id), ["6", "3", "2", "1"], "읽기와 같은 요청의 필터 줄을 뺀다. 도메인 줄이 없는 거부 요청은 남긴다");
  assert.equal(rows[1].category, "CAPACITY");
  linkReverts(rows, new Map([["6", { ref: null, revertOfRef: "action:77" }], ["2", { ref: "action:77", revertOfRef: null }]]));
  assert.equal(rows[0].revertOf, "2");
  assert.equal(rows[2].revertedBy, "6");
  assert.equal(rows[2].result, "REVERTED");
  const recent = aiRecentOf(rows, Date.parse("2026-09-25T02:00:00Z"));
  assert.deepEqual(recent, { runs: 1, reverts: 1 }, "되돌린 실행도 실행으로 센다");

  // 거르기: 행위자, 결과, 기간, 검색
  const now = Date.parse("2026-09-25T02:00:00Z");
  assert.deepEqual(applyFilter(rows, { ...DEFAULT_FILTER, result: "DENIED" }, now, kstDay).map((r) => r.id), ["3"]);
  assert.deepEqual(applyFilter(rows, { ...DEFAULT_FILTER, query: "hosthighcpu" }, now, kstDay).map((r) => r.id), ["1"]);
  assert.deepEqual(applyFilter(rows, { ...DEFAULT_FILTER, category: "AI_ACTION", result: "REVERTED" }, now, kstDay).map((r) => r.id), ["2"]);
  assert.equal(applyFilter(rows, { ...DEFAULT_FILTER, period: "today" }, Date.parse("2026-09-27T02:00:00Z"), kstDay).length, 0);
});

test("CSV는 거른 행만, 보이는 열로, BOM과 KST 시각으로 만들고 파일 이름에 원천과 KST 시각을 넣는다", () => {
  const rows = serverRecords([
    { id: "2", at: "2026-09-25T03:04:05Z", operatorId: "op-01", operatorName: "김수퍼", action: "msp.window.create", target: "W-3 · role=app, node=\"app-1\"", approvalId: null, result: "OK", requestId: "r2" },
    { id: "1", at: "2026-09-24T15:00:00Z", operatorId: "ai-ops", operatorName: "AI 운영", action: "ai.auto:READ_NODES", target: "app-3 켜기", approvalId: "apr-1", result: "OK", requestId: "r1" },
  ]);
  const csv = actionsCsv(rows);
  assert.ok(csv.startsWith("﻿"), "엑셀이 한글을 읽게 BOM");
  const lines = csv.slice(1).split("\r\n");
  assert.equal(lines[0], "시각(KST),행위자 구분,행위자,인증 주체,종류,주해,원문 코드,대상,결과,승인 티켓,사건");
  assert.equal(lines[1], '2026-09-25 12:04:05,운영자,김수퍼,op-01,계획 작업 창,계획 작업 창 등록,msp.window.create,"W-3 · role=app, node=""app-1""",성공,,');
  assert.equal(lines[2], "2026-09-25 00:00:00,AI,AI 운영,ai-ops,AI 조치,AI 자동 실행,ai.auto:READ_NODES,app-3 켜기,성공,apr-1,", "날짜 경계는 KST");
  assert.equal(lines[3], "", "끝 줄바꿈");
  assert.equal(csvFileName("SIM", Date.parse("2026-09-25T03:04:05Z")), "ops-actions_sim_20260925-120405KST.csv");
  assert.equal(csvFileName("LIVE", Date.parse("2026-09-24T15:00:00Z")), "ops-actions_live_20260925-000000KST.csv");
});

test("sim 조치 기록은 변경 요청의 승인 티켓, 창, 사건과 되돌림을 잇고 AI 판단 화면과 같은 수를 센다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const now = Date.now();
  const c = await api.createChange({
    ...INPUT,
    startsAt: iso(now + 60_000),
    endsAt: iso(now + 3_600_000),
  });
  const issued = await api.issueApproval("op-01", "ops.change.approve", { action: "ops.change.approve", id: c.id, title: c.title });
  await api.consumeApproval(issued);
  await api.startChange(c.id);
  await api.finishChange(c.id, "DONE", "확인 끝");

  const board = await api.getControlCenter();
  const req = ready(ready<ChangeDesk>(board.changes).changes).find((x) => x.id === c.id);
  assert.equal(req?.state, "DONE");
  assert.equal(req?.approvalId, issued.id, "승인 티켓 id가 요청에 남는다");

  const feed = await api.getActionRecords();
  assert.equal(feed.origin, "SIM");
  assert.equal(feed.partial, false);
  const mine = feed.rows.filter((r) => r.target.startsWith(c.id) || r.target.includes(`${c.id} ·`));
  const codes = mine.map((r) => r.action);
  for (const code of ["msp.change.create", "msp.change.start", "msp.change.finish"]) assert.ok(codes.includes(code), code);
  const approveRow = feed.rows.find((r) => r.approvalId === issued.id && r.category === "CHANGE");
  assert.ok(approveRow, "승인 실행 줄");
  const ticket = feed.approvals.find((a) => a.id === issued.id);
  assert.equal(ticket?.state, "CONSUMED");
  assert.ok(ticket?.consumedAt, "소각 시각");
  assert.ok(feed.rows.some((r) => r.category === "WINDOW" && r.action === "msp.window.create" && r.target.startsWith(req?.windowId ?? "?")));

  // 창 안의 조치는 창 여닫기와 요청 자체의 줄을 뺀다
  const span = changeSpan(req!, ready<ChangeDesk>(board.changes).windows);
  assert.ok(actionsIn(feed.rows, span).every((r) => r.category !== "WINDOW" && r.category !== "CHANGE"));

  // 봇 발행률 시험은 봇 용량 보호 사건의 AI 조치 승인으로 되돌렸다. 승인 줄이 그 사건을 잇는다
  const botApprove = feed.rows.find((r) => r.action === "ai.approve:BOT_LOAD");
  assert.ok(botApprove?.incidentId, "사건을 잇는다");
  // AI 판단 화면의 '지난 24시간 AI 조치 n회 · 되돌림 m회'와 같은 수
  assert.deepEqual(aiRecentOf(feed.rows, Date.parse(board.generatedAt)), ready(board.ai).recent);
  // 세션·승인 종류(관리 접근 기록)와 운영자의 권한 거부가 있다
  assert.ok(feed.rows.some((r) => r.category === "SESSION"));
  assert.ok(feed.rows.some((r) => r.result === "DENIED" && r.actor === "OPERATOR"));
});

test("http 원천: 계획 작업 창은 서버에서 읽고, 변경 요청·용량 전망·활동 기록·인증서·가동 시간은 원천 연결 전이다", () => {
  const FIX = path.resolve(__dirname, "..", "..", "tests", "fixtures", "msp");
  const read = <T>(name: string) => (JSON.parse(readFileSync(path.join(FIX, `${name}.json`), "utf8")) as { body: T }).body;
  const w = read<WireOverview>("overview-full");
  const now = Date.parse(w.generated_at ?? "");
  const bare = boardOf(w, emptyExtras(), now);
  assert.equal(bare.changes.state, "UNAVAILABLE", "창 목록을 아직 읽지 않았다");
  assert.equal(boardOf({ ...w, ai: undefined }, emptyExtras(), now).changes.state, "NOT_CONNECTED", "AI 제어 경로가 없는 서버는 창을 읽지 않는다");
  assert.equal(bare.outlook.state, "NOT_CONNECTED");
  assert.equal(ready<OpsJobs>(bare.jobs).certificates, null);
  assert.equal(ready<ReadTier>(bare.readTier).onSecondsToday, null);
  assert.equal(badgeOf("security", bare), null);

  const extras = emptyExtras();
  extras.windows = read<{ data: WireWindow[] }>("maintenance-windows").data;
  const c = boardOf(w, extras, now);
  const desk = ready<ChangeDesk>(c.changes);
  assert.equal(desk.changes.state, "NOT_CONNECTED", "변경 요청은 3라운드 계약 초안");
  assert.deepEqual(desk.windows.map((x) => [x.id, x.alertname, x.labels, x.reason, x.createdBy]), [["1", "ReadTierCpuHigh", { component: "app" }, "app-3 교체 작업", "snap-bot"]]);
  const items = planItems(desk.windows, null, Date.parse(desk.windows[0].startsAt) + 60_000);
  assert.deepEqual(items.map((it) => [it.kind, it.id, it.state]), [["window", "1", "IN_PROGRESS"]], "변경 요청 없이 창만 달력에 선다");
  const inWindow = boardOf(w, extras, Date.parse(desk.windows[0].startsAt) + 60_000);
  assert.deepEqual(badgeOf("changes", { ...inWindow, generatedAt: iso(Date.parse(desk.windows[0].startsAt) + 60_000) })?.label, "진행");
});
