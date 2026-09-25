/**
 * 3라운드 구현 묶음 C(관리자 설계 4-10-5·4-10-6·4-10-7·4-10-9). 시나리오 재생 세계의 결정성과 계약의 기대, SLA 월 집계와 관제 판의
 * 일치, 비용 계획의 정수 셈, 역할·권한 표와 화면·어댑터의 요구 역할, 알림 채널의 판정, http 원천의 원천 연결 전을 본다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { AdminError } from "../lib/admin/api";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { OpsSim } from "../lib/admin/opsSim";
import { SCENARIOS, scenarioById } from "../lib/admin/scenarios";
import { nextPlaySec, QUIET_LEAD_SEC, QUIET_SEC, quietSpans, ReplayWorld, runTimeline, stageAt, teamsNoticeOf, type ReplayTimeline } from "../lib/admin/scenarioWorld";
import { budgetMinutes, daysIn, monthOf, prevMonth, recommendationsOf, SLA_SOURCE, SLA_TARGET_PCT } from "../lib/admin/sla";
import { app3Cost, COST_PLAN, costView, exhaustsEarly, md, toUnits, usd } from "../lib/admin/cost";
import { ACTION_CAP, AI_ACTION_CAP, can, OPS_PERMISSIONS, roleAllows } from "../lib/admin/roles";
import { channelsOf, integrationsOf } from "../lib/admin/integrations";
import { writeStateOf } from "../lib/admin/opsHealth";
import { todayIncidents, todayMeans } from "../lib/admin/opsDetail";
import { certDaysLeft } from "../lib/admin/certs";
import { badgeOf } from "../lib/admin/navBadges";
import { kstDay } from "../lib/admin/timeText";
import { simReportDesk } from "../lib/admin/reportSim";
import type { AiDesk, DestructiveAction, IncidentLog, OpsAlert, OpsJobs, OpsSection, ReportDesk, SlaIncident, SlaMonth } from "../lib/admin/types";

// 로컬 정오. 하루 경계와 새벽 백업 시각에 걸리지 않게 한다
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}

async function rejectsWith(run: () => Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(async () => run(), (e: unknown) => e instanceof AdminError && e.code === code);
}

/* ===== 시나리오 재생(4-10-6·4-10-9) ===== */

const timelines = new Map<string, ReplayTimeline>();
function timeline(id: string, mode: "MANUAL" | "AUTO"): ReplayTimeline {
  const key = `${id}:${mode}`;
  let tl = timelines.get(key);
  if (!tl) {
    const spec = scenarioById(id);
    assert.ok(spec, id);
    tl = runTimeline(spec, mode, T0);
    timelines.set(key, tl);
  }
  return tl;
}

test("시나리오 라이브러리는 계약의 아홉이고 앞의 둘만 관제 판에 건다", () => {
  assert.deepEqual(SCENARIOS.map((s) => s.id), [
    "READ_SURGE", "NIGHT_SCALE_IN", "DB_PRIMARY_DOWN", "SYNC_STANDBY_LOST", "DISK_LOW", "BOT_RUNAWAY", "AI_SERVER_DOWN", "PROMPT_INJECTION", "CERT_EXPIRY",
  ]);
  assert.deepEqual(SCENARIOS.filter((s) => s.live !== null).map((s) => s.id), ["READ_SURGE", "NIGHT_SCALE_IN"]);
  for (const s of SCENARIOS) {
    assert.equal(s.stages[0].on.kind, "START", `${s.id}: 첫 단계는 재생 시작`);
    assert.ok(s.cues.every((c) => c.atSec > 0 && c.atSec < s.durationSec), `${s.id}: 대본은 재생 안`);
  }
  assert.equal(scenarioById("SYNC_STANDBY_LOST")?.assumption, "PATRONI_APPLIED");
  assert.equal(scenarioById("AI_SERVER_DOWN")?.assumption, "AWS_ASG_POLICY");
});

test("모든 시나리오는 두 모드 모두 단계를 차례로 밟고 재생 안에서 해제된다", () => {
  for (const s of SCENARIOS) {
    for (const mode of ["MANUAL", "AUTO"] as const) {
      const tl = timeline(s.id, mode);
      assert.equal(tl.samples.length, s.durationSec + 1, `${s.id} ${mode}: 초마다 판독`);
      tl.stageSecs.forEach((sec, i) => assert.notEqual(sec, null, `${s.id} ${mode}: ${s.stages[i].title} 단계가 서야 한다`));
      const secs = tl.stageSecs as number[];
      for (let i = 1; i < secs.length; i += 1) assert.ok(secs[i] >= secs[i - 1], `${s.id} ${mode}: 단계 순서`);
      assert.equal(stageAt(tl.stageSecs, s.durationSec), s.stages.length - 1, `${s.id} ${mode}: 끝에서 마지막 단계`);
      assert.notEqual(tl.result.openedSec, null, `${s.id} ${mode}: 사건이 선다`);
      assert.notEqual(tl.result.resolvedSec, null, `${s.id} ${mode}: 재생 안에서 해제된다`);
      // 자동 모드의 통지 등급은 시나리오가 적은 등급이고, 수동 모드는 등급 통지가 없거나 같은 등급이다
      if (mode === "AUTO") assert.equal(tl.result.notices.top, s.notice.priority, `${s.id}: 통지 등급`);
      else assert.ok(tl.result.notices.top === null || tl.result.notices.top === s.notice.priority, `${s.id} 수동: 통지 등급`);
    }
  }
});

test("재생은 결정적이다. 같은 시각은 늘 같은 판이고 되감아 다시 밟아도 같다", () => {
  const spec = scenarioById("DB_PRIMARY_DOWN");
  assert.ok(spec);
  const again = runTimeline(spec, "MANUAL", T0);
  assert.deepEqual(again.result, timeline("DB_PRIMARY_DOWN", "MANUAL").result);
  assert.deepEqual(again.stageSecs, timeline("DB_PRIMARY_DOWN", "MANUAL").stageSecs);
  const w = new ReplayWorld(spec, "MANUAL", T0);
  w.seek(200);
  const forward = w.board();
  w.seek(40);
  w.seek(200);
  assert.deepEqual(w.board(), forward, "되감기는 처음부터 다시 밟아 같은 판을 만든다");
});

test("AI가 조치할 수 없는 장애는 두 모드가 같고, AI가 조치하는 장애는 자동 모드만 자동 실행한다", () => {
  for (const id of ["DB_PRIMARY_DOWN", "SYNC_STANDBY_LOST", "DISK_LOW", "AI_SERVER_DOWN", "PROMPT_INJECTION", "CERT_EXPIRY"]) {
    assert.deepEqual(timeline(id, "AUTO").result, timeline(id, "MANUAL").result, `${id}: 비교 보기의 두 줄이 같다`);
    assert.equal(timeline(id, "AUTO").result.autoRuns, 0, `${id}: 자동 실행 없음`);
  }
  for (const id of ["READ_SURGE", "BOT_RUNAWAY"]) {
    assert.equal(timeline(id, "AUTO").result.autoRuns, 1, `${id}: 자동 모드 자동 실행`);
    assert.equal(timeline(id, "AUTO").result.decisions, 0, `${id}: 자동 모드는 운영자 결정 없음`);
    assert.equal(timeline(id, "MANUAL").result.autoRuns, 0, `${id}: 수동 모드는 자동 실행 없음`);
    assert.ok(timeline(id, "MANUAL").result.decisions >= 1, `${id}: 수동 모드는 운영자 승인`);
  }
  assert.equal(timeline("READ_SURGE", "AUTO").result.lastTier, 3, "조회 급증 뒤 앱 3대");
  assert.equal(timeline("AI_SERVER_DOWN", "MANUAL").result.lastTier, 3, "AI 없이 지표 정책이 app-3을 켠다");
  const night = timeline("NIGHT_SCALE_IN", "AUTO").result;
  assert.equal(night.reverts, 1, "관찰 창 안의 악화로 자동 되돌림");
  assert.equal(night.notices.top, "P2");
});

test("쓰기 상태는 DB Primary 장애에서 쓰기 실패, 엄격 동기 Standby 상실에서 거래 일시 중단이고 거래 요약 띠와 같은 판정이다", () => {
  const db = scenarioById("DB_PRIMARY_DOWN");
  const sync = scenarioById("SYNC_STANDBY_LOST");
  assert.ok(db && sync);
  const w = new ReplayWorld(db, "MANUAL", T0);
  w.seek(100);
  assert.equal(writeStateOf(w.board().trading)?.word, "쓰기 실패");
  w.seek(205);
  assert.equal(writeStateOf(w.board().trading), null, "주소 전환 뒤 쓰기가 열린다");
  const s = new ReplayWorld(sync, "AUTO", T0);
  s.seek(60);
  assert.equal(writeStateOf(s.board().trading)?.word, "거래 일시 중단");
  s.seek(160);
  assert.equal(writeStateOf(s.board().trading), null, "Standby가 돌아오면 쓰기가 자동으로 재개된다");
  assert.ok(timeline("DB_PRIMARY_DOWN", "MANUAL").result.writesDownSec > 0);
  // 관제 판(관제센터)은 쓰기가 열려 있어 띠에 쓰기 칸이 서지 않는다
  assert.equal(writeStateOf(new OpsSim(T0).snapshot(T0 + 5000).trading), null);
});

test("디스크 부족은 봇을 멈추고 운영자가 다시 켤 때까지 멈춰 있으며, 인증서 갱신은 만료일을 90일 뒤로 바꾼다", () => {
  const disk = timeline("DISK_LOW", "MANUAL");
  const guard = disk.stageSecs[1] as number;
  assert.equal(disk.samples[guard + 1].botOps, 0, "봇 용량 보호가 봇을 멈춘다");
  assert.equal(disk.samples[225].botOps, 0, "해제 뒤에도 봇은 멈춰 있다");
  assert.ok(disk.samples[disk.durationSec].botOps > 0, "봇 재개 뒤 발행");
  const spec = scenarioById("CERT_EXPIRY");
  assert.ok(spec);
  const w = new ReplayWorld(spec, "MANUAL", T0);
  const www = () => ready<OpsJobs>(w.board().jobs).certificates?.find((c) => c.domain === "www.sang.it.kr");
  const left = () => {
    const c = www();
    assert.ok(c);
    return certDaysLeft(c, Date.parse(w.board().generatedAt));
  };
  assert.equal(left(), 13);
  w.seek(spec.durationSec);
  assert.equal(left(), 90);
});

test("프롬프트 주입 경보는 모델 근거에서 문장을 버리고, 명령을 따른 조치안이 나와도 정책 엔진이 D4와 S8로 막는다", () => {
  const spec = scenarioById("PROMPT_INJECTION");
  assert.ok(spec);
  const w = new ReplayWorld(spec, "AUTO", T0);
  w.seek(30);
  const hyp = w.hypothetical();
  assert.ok(hyp);
  const blocked = (rule: string, i: number) => hyp.checks.find((c) => c.rule === rule)?.results[i].state;
  assert.equal(blocked("BOT_GUARD", 0), "BLOCK", "봇 부하 줄임은 bot-1 CPU가 기준 아래라 막힌다(D4)");
  assert.equal(blocked("WINDOW", 1), "BLOCK", "형식 밖 문장이 든 경보의 보류는 막힌다(S8)");
  const ai = ready<AiDesk>(w.board().ai);
  const trace = ai.history.find((h) => h.trace?.rejected && h.trace.rejected.length > 0)?.trace;
  assert.ok(trace, "판단 근거에서 버린 필드가 기록된다");
  assert.ok(ai.proposals.every((p) => p.status !== "AUTO_EXECUTED"), "조치하지 않는다");
});

test("Teams 카드 미리보기는 통지 글에서 종류와 등급을 가른다", () => {
  const notices = timeline("DB_PRIMARY_DOWN", "MANUAL").events.filter((x) => x.e.kind === "NOTIFIED").map((x) => teamsNoticeOf(x.e.text));
  assert.ok(notices.some((n) => n.kind === "REPORT" && n.priority === "P1"), "사건 보고 P1");
  assert.ok(notices.some((n) => n.kind === "ALERT" && n.priority === null), "경보 카드는 등급이 없다");
});

test("재생 길이는 두 모드 가운데 늦은 쪽의 마지막 기록 뒤 12초를 5초 단위로 올린 값이라 긴 빈 꼬리가 없다", () => {
  for (const s of SCENARIOS) {
    const last = Math.max(...(["MANUAL", "AUTO"] as const).flatMap((m) => timeline(s.id, m).events.map((x) => x.sec)));
    assert.equal(s.durationSec, Math.ceil((last + 12) / 5) * 5, `${s.id}: 마지막 기록 ${last}초`);
  }
});

test("재생은 빈 구간의 앞 기록을 보인 뒤 다음 기록 3초 앞으로 넘고, 건너뛰기를 끄면 배속대로 간다", () => {
  const db = scenarioById("DB_PRIMARY_DOWN");
  assert.ok(db);
  const spans = quietSpans(db, [timeline(db.id, "MANUAL")]);
  // 옛 Primary 재편입(660초) 앞의 새 베이스백업 구간이 가장 길다
  assert.deepEqual(spans, [{ from: 50, to: 127 }, { from: 168, to: 196 }, { from: 230, to: 657 }]);
  const end = db.durationSec;
  assert.equal(nextPlaySec(229, 1, end, spans), 230, "앞 기록까지는 배속대로 간다");
  assert.equal(nextPlaySec(230, 1, end, spans), 657, "앞 기록을 보인 다음 박자에 넘는다");
  assert.equal(nextPlaySec(657, 1, end, spans), 658);
  assert.equal(nextPlaySec(228, 4, end, spans), 232, "박자가 기록을 건너 구간에 들면 기록 직후의 판을 먼저 보인다");
  assert.equal(nextPlaySec(232, 4, end, spans), 657);
  assert.equal(nextPlaySec(674, 4, end, spans), end, "재생 끝을 넘지 않는다");
  assert.equal(nextPlaySec(230, 1, end, []), 231, "건너뛰기를 끄면 배속대로 간다");
});

test("빈 구간 안에는 두 모드의 기록·대본이 없고, 1배로 끝까지 재생해도 기록 없이 30박자 넘게 흐르지 않는다", () => {
  let spanCount = 0;
  for (const s of SCENARIOS) {
    const views: ReplayTimeline[][] = [[timeline(s.id, "MANUAL")], [timeline(s.id, "AUTO")], [timeline(s.id, "MANUAL"), timeline(s.id, "AUTO")]];
    for (const tls of views) {
      const q = quietSpans(s, tls);
      spanCount += q.length;
      const points = new Set<number>([s.durationSec]);
      for (const tl of tls) {
        for (const x of tl.events) points.add(x.sec);
        for (const c of s.cues) if (c.mode === "ANY" || c.mode === tl.mode) points.add(c.atSec);
      }
      for (const span of q) {
        assert.ok([...points].every((p) => p <= span.from || p >= span.to + QUIET_LEAD_SEC), `${s.id}: ${span.from}~${span.to} 안에 기록이 없다`);
        assert.ok(points.has(span.to + QUIET_LEAD_SEC), `${s.id}: 빈 구간은 다음 기록 ${QUIET_LEAD_SEC}초 앞에서 끝난다`);
      }
      let sec = 0;
      let idle = 0;
      while (sec < s.durationSec) {
        const next = nextPlaySec(sec, 1, s.durationSec, q);
        idle = [...points].some((p) => p > sec && p <= next) ? 0 : idle + 1;
        assert.ok(idle <= QUIET_SEC, `${s.id}: ${sec}초에서 기록 없이 ${idle}박자`);
        sec = next;
      }
    }
  }
  assert.ok(spanCount > 0, "빈 구간이 있는 시나리오가 있어야 공허 통과가 아니다");
});

/* ===== SLA 월간 보고(4-10-5) ===== */

test("오류 예산은 그 달 전체 분의 0.5%다", () => {
  assert.equal(SLA_TARGET_PCT, 99.5);
  assert.equal(daysIn("2026-09"), 30);
  assert.equal(daysIn("2026-02"), 28);
  assert.equal(budgetMinutes("2026-09"), 216);
  assert.equal(budgetMinutes("2026-10"), 223.2);
  assert.equal(prevMonth("2026-01"), "2025-12");
  assert.equal(monthOf(Date.UTC(2026, 8, 30, 15, 30)), "2026-10", "KST 자정으로 달을 가른다");
});

test("sim SLA의 오늘 줄은 경보·사건 화면의 오늘 사건 수·평균 확인·해제 시간과 같고, 지난 24시간 AI 조치는 AI 판단 화면과 같다", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: T0 });
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  await api.startOpsScenario("READ_SURGE");
  t.mock.timers.tick(25_000);
  const pending = ready<AiDesk>((await api.getControlCenter()).ai).proposals.find((p) => p.kind === "READ_NODES" && p.status === "PROPOSED");
  assert.ok(pending);
  await api.decideAiProposal(pending.id, "APPROVE");
  t.mock.timers.tick(90_000);
  const now = Date.now();
  const board = await api.getControlCenter();
  const month = ready<SlaMonth>(await api.getSlaMonth(monthOf(now)));
  const log = ready<IncidentLog>(board.incidents);
  const today = month.days.find((d) => d.day === kstDay(now));
  assert.ok(today);
  assert.equal(today.source, "BOARD");
  assert.equal(today.incidents, todayIncidents(log, now).count, "오늘 사건 수");
  const means = todayMeans(log, now);
  assert.equal(today.mttaMs, means.mttaMs, "오늘 평균 확인 시간");
  assert.equal(today.mttrMs, means.mttrMs, "오늘 평균 해제 시간");
  assert.ok(today.incidents >= 1);
  assert.deepEqual(month.ai.recent, ready<AiDesk>(board.ai).recent, "지난 24시간 AI 조치와 되돌림");
  assert.equal(month.throughDay, kstDay(now));
  assert.equal(month.days.length, Number(kstDay(now).slice(8, 10)));
  assert.ok(month.days.filter((d) => d.source === "BOARD").length <= 3, "관제 판이 닿는 날은 지난 48시간이 걸친 날");
  assert.equal(month.budget.allowedMin, budgetMinutes(month.month));
  assert.equal(month.incidents.total, month.incidentRows.length);

  const prev = ready<SlaMonth>(await api.getSlaMonth(prevMonth(month.month)));
  assert.equal(prev.days.length, daysIn(prev.month));
  assert.equal(prev.ai.recent, null, "지난 달은 지난 24시간 칸을 싣지 않는다");
  assert.deepEqual(ready<SlaMonth>(await api.getSlaMonth(prev.month)), prev, "예시 날은 날짜에서 정해진다");
  await rejectsWith(() => api.getSlaMonth("2026-9"), "invalid_parameter");
});

let slaSeq = 0;
const inc = (alert: string, pagedLevel: 0 | 1 | 2 | 3): SlaIncident => ({
  id: `i-${alert}-${pagedLevel}-${(slaSeq += 1)}`, title: alert, alert, severity: "warning", openedAt: "2026-09-10T00:00:00Z", resolvedAt: "2026-09-10T00:10:00Z",
  ttaMs: 60_000, ttrMs: 600_000, pagedLevel, postmortem: "NONE", source: "SEED",
});

test("다음 달 권고는 규칙에 걸린 수에서만 나오고 근거 수를 붙인다", () => {
  const quiet = recommendationsOf({ budget: { allowedMin: 216, usedMin: 20 }, incidentRows: [inc("복제 지연", 0)], changeRows: [], autoReverts: 0 });
  assert.deepEqual(quiet, [], "걸린 규칙이 없으면 권고도 없다");
  const busy = recommendationsOf({
    budget: { allowedMin: 216, usedMin: 190 },
    incidentRows: [inc("복제 지연", 0), inc("복제 지연", 2), inc("복제 지연", 0)],
    changeRows: [{ id: "CHG-0914", title: "시험", state: "ROLLED_BACK", closedAt: "2026-09-14T06:00:00Z", result: "되돌림", source: "SEED" }],
    autoReverts: 1,
  });
  assert.deepEqual(busy.map((r) => r.rule), ["BUDGET", "REPEAT", "AUTO_REVERT", "PAGED", "ROLLED_BACK"]);
  assert.equal(busy[0].basis, "오류 예산 88% 사용(190.0분 / 216분)");
  assert.equal(busy[1].basis, "복제 지연 경보로 연 사건 3건");
});

test("다음 달 권고의 문턱은 오류 예산 80%, 같은 경보 3건, 확인 전 2차 호출, 되돌린 변경 1건이고 바로 아래에서는 권고가 없다", () => {
  const below = recommendationsOf({
    budget: { allowedMin: 100, usedMin: 79.9 },
    incidentRows: [inc("복제 지연", 1), inc("복제 지연", 1), inc("백업 지연", 0)],
    changeRows: [{ id: "CHG-0911", title: "시험", state: "DONE", closedAt: "2026-09-11T06:00:00Z", result: "완료", source: "SEED" }],
    autoReverts: 0,
  });
  assert.deepEqual(below, [], "예산 79.9%, 같은 경보 2건, 1차 호출, 완료한 변경");
  const edge = recommendationsOf({
    budget: { allowedMin: 100, usedMin: 80 },
    incidentRows: [inc("복제 지연", 1), inc("복제 지연", 0), inc("복제 지연", 2)],
    changeRows: [],
    autoReverts: 0,
  });
  assert.deepEqual(edge.map((r) => r.rule), ["BUDGET", "REPEAT", "PAGED"]);
  assert.equal(edge[0].basis, "오류 예산 80% 사용(80.0분 / 100분)");
  assert.equal(edge[2].basis, "확인 없이 2차 호출까지 간 사건 1건");
});

test("http SLA는 요청 없이 원천 연결 전이다", async () => {
  const prev = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = (async () => {
    calls += 1;
    return new Response("{}", { status: 200 });
  }) as typeof fetch;
  try {
    const sla = await new HttpAdminApi("http://admin.test").getSlaMonth("2026-09");
    assert.deepEqual(sla, { state: "NOT_CONNECTED", source: SLA_SOURCE });
    assert.equal(calls, 0);
  } finally {
    globalThis.fetch = prev;
  }
});

/* ===== 비용(4-10-5) ===== */

test("비용 계획은 십진 입력을 정수 셈 단위로 옮겨 셈한다", () => {
  assert.equal(toUnits("163.62"), 1_636_200);
  assert.equal(toUnits("6.73"), 67_300);
  assert.equal(toUnits("0.0335"), 335);
  assert.throws(() => toUnits("1.23456"));
  assert.throws(() => toUnits("-1"));
  const v = costView(COST_PLAN, Date.UTC(2026, 8, 25, 3));
  assert.equal(v.phase, "BEFORE");
  assert.equal(v.days, 15, "10.2~10.16 양 끝 포함");
  assert.equal(usd(v.planTotal), "$100.95");
  assert.equal(usd(v.planWithApp3), "$112.95");
  assert.equal(usd(v.remain), "$62.67");
  assert.equal(usd(v.remainWithApp3), "$50.67");
  assert.equal(v.exhaustDay, "2026-10-26", "24일을 쓰고 25일째에 넘는다");
  assert.equal(v.exhaustDayWithApp3, "2026-10-23");
  assert.equal(v.spentToDate, 0);
  const mid = costView(COST_PLAN, Date.parse("2026-10-05T12:00:00+09:00"));
  assert.equal(mid.phase, "RUNNING");
  assert.equal(mid.spentToDate, 4 * 67_300);
  assert.equal(exhaustsEarly(), false);
  assert.equal(md("2026-10-26"), "10.26");
  assert.equal(usd(-12_345), "-$1.23");
  assert.equal(app3Cost(3600), 335, "한 시간은 단가 그대로");
  assert.equal(app3Cost(0), 0);
});

test("비용 배지는 계획 소진 예상일이 운영 종료일과 같거나 이를 때만 주의 ▲로 선다", () => {
  const plan = (creditUsd: string) => ({ ...COST_PLAN, creditUsd });
  // 하루 $6.73, 10.2 착수, 10.16 종료. 소진일은 착수일부터 '크레딧 / 하루'의 몫만큼 뒤다
  assert.equal(costView(plan("100"), T0).exhaustDay, "2026-10-16");
  assert.equal(exhaustsEarly(plan("100")), true, "종료일과 같은 날");
  assert.equal(costView(plan("90"), T0).exhaustDay, "2026-10-15");
  assert.equal(exhaustsEarly(plan("90")), true, "종료일보다 하루 이른 날");
  assert.equal(costView(plan("101"), T0).exhaustDay, "2026-10-17");
  assert.equal(exhaustsEarly(plan("101")), false, "종료일 다음 날");
  assert.equal(exhaustsEarly(plan("100.95")), false, "크레딧이 15일을 꼭 채우면 넘지 않는다");
  // 사이드바 배지는 계획표 하나를 읽는다. 계획표를 잠시 줄여 배지 모양을 보고 되돌린다
  const board = new OpsSim(T0).snapshot(T0 + 5000);
  const saved = COST_PLAN.creditUsd;
  try {
    COST_PLAN.creditUsd = "100";
    const b = badgeOf("cost", board);
    assert.deepEqual(b && [b.tone, b.shape, b.count], ["warning", "triangle", null]);
  } finally {
    COST_PLAN.creditUsd = saved;
  }
  assert.equal(badgeOf("cost", board), null, "지금 계획(10.27 소진)에서는 서지 않는다");
});

test("지금 계획에서는 비용 배지가 서지 않고, 비용의 app-3 가동 시간은 관제 판의 값이다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", T0);
  sim.decide(ready<AiDesk>(sim.snapshot(T0 + 20_000).ai).proposals.find((p) => p.kind === "READ_NODES")?.id ?? "", "APPROVE", { id: "op-01", name: "김수퍼" }, T0 + 21_000);
  const c = sim.snapshot(T0 + 120_000);
  assert.equal(badgeOf("cost", c), null);
  const tier = ready<{ onSecondsToday: number | null }>(c.readTier);
  assert.ok((tier.onSecondsToday ?? 0) > 0, "app-3이 켜진 시간");
  assert.equal(app3Cost(tier.onSecondsToday ?? 0), Math.round(((tier.onSecondsToday ?? 0) * 335) / 3600));
});

/* ===== 역할·권한(4-10-7) ===== */

test("역할·권한 표의 요구 역할은 승인 티켓의 요구 역할, 조치 종류별 역할과 같다", () => {
  const capOf = (r: string) => (r === "VIEWER" ? "VIEW" : r);
  for (const p of OPS_PERMISSIONS) {
    if (p.ticket && p.ticket in ACTION_CAP) assert.equal(ACTION_CAP[p.ticket as DestructiveAction], capOf(p.role), `${p.action}: 티켓 ${p.ticket}`);
  }
  const row = (text: string) => OPS_PERMISSIONS.find((p) => p.action.startsWith(text));
  assert.equal(row("조치안 승인·되돌리기(봇 부하")?.role, AI_ACTION_CAP.BOT_LOAD);
  assert.equal(AI_ACTION_CAP.SILENCE, "BOT_OPS");
  assert.equal(row("조치안 승인·되돌리기(app-3")?.role, AI_ACTION_CAP.READ_NODES);
  assert.equal(AI_ACTION_CAP.BACKUP_NOW, "SUPER");
  assert.equal(row("임계값 변경")?.ticket, "ops.threshold.set");
  assert.equal(AI_ACTION_CAP.THRESHOLD, "SUPER");
  // 행렬의 칸. 총괄은 모두, 실험 운영은 봇 운영을 품고, 회원 운영과 조회는 권한을 줄이는 조작과 조회만 한다
  for (const p of OPS_PERMISSIONS) {
    assert.equal(roleAllows("SUPER", p.role), true, `${p.action}: 총괄`);
    assert.equal(roleAllows("CHAOS_OPS", p.role), roleAllows("BOT_OPS", p.role), `${p.action}: 실험 운영은 봇 운영과 같다`);
    assert.equal(roleAllows("MEMBER_MOD", p.role), p.role === "VIEWER", `${p.action}: 회원 운영`);
    assert.equal(roleAllows("VIEWER", p.role), p.role === "VIEWER", `${p.action}: 조회`);
  }
  assert.equal(can(["VIEWER"], "BOT_OPS"), false);
});

test("sim 조치안 승인은 조치 종류별 역할이다. 봇 운영은 app-3 켜기를 승인하지 못하고 거절은 한다", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: T0 });
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  await api.startOpsScenario("READ_SURGE");
  t.mock.timers.tick(25_000);
  const desk = ready<AiDesk>((await api.getControlCenter()).ai);
  const readNodes = desk.proposals.find((p) => p.kind === "READ_NODES" && p.status === "PROPOSED");
  const threshold = desk.proposals.find((p) => p.kind === "THRESHOLD" && p.status === "PROPOSED");
  assert.ok(readNodes && threshold);
  await rejectsWith(() => api.decideAiProposal(readNodes.id, "APPROVE"), "admin_forbidden");
  await rejectsWith(() => api.decideAiProposal(threshold.id, "APPROVE"), "admin_forbidden");
  await api.decideAiProposal(threshold.id, "REJECT");
  await api.establishSession("op-01");
  await api.decideAiProposal(readNodes.id, "APPROVE");
  const after = ready<AiDesk>((await api.getControlCenter()).ai);
  assert.equal(after.proposals.find((p) => p.id === readNodes.id)?.status, "APPROVED");
});

/** 봇 발행률을 올려 bot-1 CPU가 용량 보호 기준을 넘게 하고, 운영자 변경 뒤의 봇 부하 쿨다운(10분)이 지난 판을 읽는다 */
async function botLoadBoard(api: SimAdminApi, tick: (ms: number) => void, rate: number): Promise<AiDesk> {
  await api.simSetRate(rate);
  tick(400_000);
  await api.getControlCenter();
  tick(325_000);
  return ready<AiDesk>((await api.getControlCenter()).ai);
}

test("sim 조치안 승인·되돌리기의 조치 종류별 역할: 봇 운영은 봇 부하 줄임을 승인하고 자동 실행한 봇 부하 줄임을 되돌린다", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: T0 });
  const tick = (ms: number) => t.mock.timers.tick(ms);
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  const manual = (await botLoadBoard(api, tick, 140)).proposals.find((p) => p.kind === "BOT_LOAD" && p.status === "PROPOSED");
  assert.ok(manual, "봇 부하 줄임 조치안이 서야 공허 통과가 아니다");
  await api.decideAiProposal(manual.id, "APPROVE");
  assert.equal(ready<AiDesk>((await api.getControlCenter()).ai).proposals.find((p) => p.id === manual.id)?.status, "APPROVED");

  // 자동 모드는 총괄이 켠다. 그 뒤 AI가 스스로 실행한 봇 부하 줄임을 봇 운영이 되돌린다
  await api.establishSession("op-01");
  await api.enableAiAuto(2, "야간 대기");
  await api.establishSession("op-02");
  const auto = (await botLoadBoard(api, tick, 160)).proposals.find((p) => p.kind === "BOT_LOAD" && p.status === "AUTO_EXECUTED");
  assert.ok(auto, "자동 실행한 봇 부하 줄임이 서야 공허 통과가 아니다");
  await api.revertAiAction(auto.id);
  assert.equal(ready<AiDesk>((await api.getControlCenter()).ai).proposals.find((p) => p.id === auto.id)?.status, "REVERTED");
});

test("sim 조치안 승인의 조치 종류별 역할: 봇 운영은 계획 작업 창 안의 주의 경보 보류안을 승인한다", async (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: T0 });
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  await api.createWindow({ startsAt: null, endsAt: new Date(T0 + 600_000).toISOString(), alertname: null, labels: { role: "app" }, reason: "조회 증설 시험" });
  await api.startOpsScenario("READ_SURGE");
  t.mock.timers.tick(120_000);
  const silence = ready<AiDesk>((await api.getControlCenter()).ai).proposals.find((p) => p.kind === "SILENCE" && p.status === "PROPOSED");
  assert.ok(silence, "창 안의 경보 보류안이 서야 공허 통과가 아니다");
  await api.decideAiProposal(silence.id, "APPROVE");
  const alert = ready<OpsAlert[]>((await api.getControlCenter()).alerts).find((a) => a.name === "앱 계층 CPU 높음");
  assert.equal(alert?.state, "SILENCED");
});

/* ===== 알림 채널·설정(4-10-7) ===== */

test("알림 채널의 상태는 연동 표의 같은 행에서 옮기고, 당번 호출은 원천 연결 전, 이메일은 경로가 없다", () => {
  const sim = new OpsSim(T0);
  const c = sim.snapshot(T0 + 5000);
  const desk: OpsSection<ReportDesk> = { state: "READY", data: simReportDesk(T0 + 5000) };
  const rows = integrationsOf(c, desk, { grafana: false, databasus: false });
  const byKey = Object.fromEntries(channelsOf(rows, true).map((r) => [r.key, r]));
  const row = (k: string) => rows.find((r) => r.key === k);
  assert.equal(byKey.teamsReport.state, row("teams")?.state);
  assert.equal(byKey.teamsAlert.state, row("alertmanager")?.state);
  assert.equal(byKey.reporterAlert.state, row("alertmanager")?.state);
  assert.equal(byKey.page.state, "NOT_CONNECTED");
  assert.equal(byKey.email.state, null);
  assert.match(byKey.teamsReport.uses, /09:00 KST/);
  // http 판이 없으면(관제 모듈 404) 경보 두 갈래는 연결 전이다
  const none = Object.fromEntries(channelsOf(integrationsOf(null, null, { grafana: false, databasus: false }), false).map((r) => [r.key, r]));
  assert.equal(none.teamsAlert.state, "NOT_CONNECTED");
  assert.equal(none.teamsReport.state, "NOT_CONNECTED");
});
