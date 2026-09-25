/**
 * 관제센터 3라운드 최종 보정의 규칙 검증. 판단 기록 표(결과 묶음, 확신도, 정책 판정, 처리 결과, 지난 24시간 시드), 경보를 부르는 말,
 * 정책 검사표의 세 칸, 시각 한 형식, 런북 없는 사건의 콘솔 자리, 지표 관제 기록, 승인 실행 알림을 결정적으로 본다
 * (관리자 설계 4-10, 디자인 시스템 v1.21).
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { checkView, visibleChecks } from "../lib/admin/aiPolicy";
import { filterJudgments, judgmentConfidence, judgmentGroup, judgmentMark, policyVerdict } from "../lib/admin/judgmentLog";
import { consoleRouteFor } from "../lib/admin/runbooks";
import { alertPhrase, splitNames } from "../lib/admin/text";
import { stamp } from "../lib/admin/timeText";
import { sampleFromTape } from "../lib/admin/flowSim";
import type { AiDesk, AiJudgment, ControlCenter, IncidentLog } from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}
const ai = (c: ControlCenter) => ready<AiDesk>(c.ai);

test("경보를 부르는 말은 이름이 대상으로 시작하면 이름만, 아니면 '이름 · 대상'이다", () => {
  assert.equal(alertPhrase({ name: "앱 계층 CPU 높음", target: "앱 계층" }), "앱 계층 CPU 높음");
  assert.equal(alertPhrase({ name: "응답 지연 증가", target: "외부 응답" }), "응답 지연 증가 · 외부 응답");
  assert.equal(alertPhrase({ name: "복제 지연", target: "db-standby" }), "복제 지연 · db-standby");
});

test("띄어 쓰지 않은 가운뎃점 묶음과 하이픈 이름은 한 덩어리 토막이고 띄어 쓴 가운뎃점은 나누지 않는다", () => {
  const names = (t: string) => splitNames(t).filter((_, i) => i % 2 === 1);
  assert.deepEqual(names("장애 등급, 복제·DB·백업·디스크와 관측 경로 경보 불가"), ["복제·DB·백업·디스크와"]);
  assert.deepEqual(names("쓰기 → app-1·app-2, 조회 → app-3"), ["app-1·app-2", "app-3"]);
  assert.deepEqual(names("db-primary 승격은 사람이 합니다"), ["db-primary"]);
  assert.deepEqual(names("CHG-104 · 봇 발행률 상향 시험"), ["CHG-104"]);
  assert.deepEqual(names("p0 BTC · XRP · DOGE"), []);
  assert.equal(splitNames("복제·DB 경보").join(""), "복제·DB 경보", "글자는 바꾸지 않는다");
});

test("조회 급증 뒤 판단 문장과 사건 이름에 대상과 경보 이름이 겹치지 않는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const p = ai(sim.snapshot(at(20))).proposals.find((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.ok(p);
  sim.decide(p.id, "APPROVE", OP, at(20));
  const situations = new Set<string>();
  for (let s = 20; s <= 60; s += 1) {
    const c = sim.snapshot(at(s));
    situations.add(ai(c).situation);
    for (const h of ai(c).history) situations.add(h.summary);
  }
  for (const text of situations) assert.doesNotMatch(text, /앱 계층 앱 계층|외부 응답 응답/, text);
  const inc = ready<IncidentLog>(sim.snapshot(at(60)).incidents).incidents.find((i) => i.title.startsWith("앱 계층 CPU 높음"));
  assert.ok(inc, "급증 사건");
  assert.equal(inc.title, "앱 계층 CPU 높음", "사건 이름에 '· 앱 계층'을 되풀이하지 않는다");
});

test("판단 기록은 지난 24시간의 시드 판단을 싣고 오늘 조치안을 낸 판단(확신도 88%)과 처리 결과를 찾을 수 있다", () => {
  const sim = new OpsSim(T0);
  const c = sim.snapshot(at(2));
  const history = ai(c).history;
  assert.ok(history.every((h) => at(2) - Date.parse(h.at) <= 24 * 3_600_000), "24시간 안의 기록만");
  const proposals = filterJudgments(history, "PROPOSAL", 24, at(2));
  const bot = proposals.find((h) => h.trace?.candidates.some((x) => x.kind === "BOT_LOAD"));
  assert.ok(bot, "봇 용량 보호 사건의 판단");
  assert.equal(judgmentConfidence(bot), 88);
  assert.match(bot.handled ?? "", /^승인 · 김수퍼 \d\d:\d\d$/);
  assert.equal(policyVerdict(bot)?.word, "수동 · 승인 대기");
  // 시드 판단 과정의 검사표는 수동 판단이라 자동 실행 조건(같은 판단 연속)이 자동이면 막힘이고, 해당 없는 규칙은 표 아래로 접힌다
  const tr = bot.trace;
  assert.ok(tr);
  const streak = tr.checks.find((r) => r.rule === "STREAK");
  assert.ok(streak);
  assert.equal(checkView(streak.rule, streak.results[0].state, tr.mode), "autoOnly");
  assert.deepEqual(visibleChecks(tr.checks).hidden, ["임계값 1회 변경폭", "임계값 항목당 횟수", "임계값 방향", "오늘 즉시 백업", "계획 작업 창"]);
  // 기록은 최신 순이다
  for (let i = 1; i < history.length; i += 1) assert.ok(Date.parse(history[i - 1].at) >= Date.parse(history[i].at));
});

test("조치안을 승인하면 그 조치안을 낸 판단 기록의 처리 결과가 '대기 중'에서 '승인'으로 바뀐다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const c = sim.snapshot(at(20));
  const p = ai(c).proposals.find((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.ok(p);
  const judged = ai(c).history.find((h) => h.trace?.candidates.some((x) => x.id === p.id));
  assert.ok(judged);
  assert.match(judged.handled ?? "", /대기 중/);
  sim.decide(p.id, "APPROVE", OP, at(21));
  const after = ai(sim.snapshot(at(22))).history.find((h) => h.id === judged.id);
  assert.ok(after);
  assert.match(after.handled ?? "", /승인/);
  assert.ok(Object.isFrozen(after), "판단 기록은 얼린 객체라 화면이 바꿀 수 없다");
});

test("승인한 조치의 결과를 모드 줄 알림이 30초 동안 적는다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  const p = ai(sim.snapshot(at(20))).proposals.find((x) => x.kind === "READ_NODES" && x.status === "PROPOSED");
  assert.ok(p);
  sim.decide(p.id, "APPROVE", OP, at(20));
  const n = ai(sim.snapshot(at(25))).notice;
  assert.ok(n);
  assert.equal(n.kind, "EXECUTED");
  assert.match(n.text, /앱 계층 2 → 3대/);
  assert.equal(ai(sim.snapshot(at(51))).notice, null, "30초가 지나면 평소 글로 돌아간다");
});

test("판단 기록의 결과 묶음은 sim과 서버 판단 목록의 말을 함께 가른다", () => {
  assert.equal(judgmentGroup("조치안 2건"), "PROPOSAL");
  assert.equal(judgmentGroup("남은 조치안 1건"), "PROPOSAL");
  assert.equal(judgmentGroup("조치 요청"), "PROPOSAL");
  assert.equal(judgmentGroup("자동 실행 · app-3 켜기 (앱 2 → 3대)"), "PROPOSAL");
  assert.equal(judgmentGroup("이상 없음"), "CLEAR");
  assert.equal(judgmentGroup("AI 정지"), "HALT");
  assert.equal(judgmentGroup("모델 호출 실패"), "HALT");
  assert.equal(judgmentGroup("운영자 확인 필요"), "WATCH");
  assert.equal(judgmentGroup("조치 효과 관찰"), "WATCH");
  assert.deepEqual(judgmentMark("AI 정지"), { kind: "triangle", tone: "warning" });
  assert.deepEqual(judgmentMark("모델 호출 실패"), { kind: "square", tone: "danger" });
  assert.deepEqual(judgmentMark("조치 효과 관찰"), { kind: "ring", tone: "off" });
  const brief: AiJudgment = { id: "1", at: new Date(at(0)).toISOString(), summary: "", outcome: "조치 요청", trace: null, lazy: true, confidencePct: 86 };
  assert.equal(judgmentConfidence(brief), 86, "서버 판단 목록은 확신도를 싣는다");
  assert.equal(policyVerdict(brief), null, "검사표를 싣지 않은 목록 줄은 정책 판정을 짓지 않는다");
});

test("정책 검사표 칸은 모드에 따라 막음과 자동이면 막힘으로 갈린다", () => {
  assert.equal(checkView("CONFIDENCE", "BLOCK", "MANUAL"), "autoOnly");
  assert.equal(checkView("CONFIDENCE", "BLOCK", "AUTO"), "block");
  assert.equal(checkView("WINDOW", "BLOCK", "MANUAL"), "block", "창 규칙은 수동 승인도 막는다");
  assert.equal(checkView("BACKUP_TODAY", "BLOCK", "MANUAL"), "block");
  assert.equal(checkView("MODE", "HOLD", "MANUAL"), "hold");
  assert.equal(checkView("ALLOWED", "PASS", "MANUAL"), "pass");
  assert.equal(checkView("STEP", "NA", "AUTO"), "na");
});

test("기록 시각은 오늘이면 시·분·초, 다른 날이면 날짜와 시·분이다", () => {
  const noon = Date.parse("2026-09-25T03:00:00Z");
  assert.equal(stamp("2026-09-25T01:02:03Z", noon), "10:02:03");
  assert.equal(stamp("2026-09-24T12:25:26Z", noon), "9.24 21:25");
});

test("런북이 없는 사건은 콘솔에서 할 자리로 안내한다", () => {
  assert.equal(consoleRouteFor("앱 계층 CPU 높음")?.href, "/admin/capacity");
  assert.equal(consoleRouteFor("응답 지연 증가 · 외부 응답")?.href, "/admin/capacity");
  assert.equal(consoleRouteFor("복제 지연 · db-standby")?.href, "/admin/database");
  assert.equal(consoleRouteFor("디스크 여유 부족 · bot-1"), null);
});

test("지표 관제 기록은 콘솔을 연 시각 앞 150초를 채우고 판의 값과 같은 표본이 된다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const tape = await api.getFlowTape();
  assert.ok(tape);
  assert.ok(tape.length >= 150, `기록 ${tape.length}박자`);
  for (let i = 1; i < tape.length; i += 1) assert.equal(tape[i].t - tape[i - 1].t, 1000, "박자는 1초 간격");
  const board = await api.getControlCenter();
  const last = tape[tape.length - 1];
  const s = sampleFromTape(last, 10);
  assert.equal(s.desired, ready<{ desired: number }>(board.readTier).desired);
  assert.equal(s.botOps, ready<{ botOps: number }>(board.trading).botOps);
  assert.equal(s.lease, 6 - (10 % 2));
});
