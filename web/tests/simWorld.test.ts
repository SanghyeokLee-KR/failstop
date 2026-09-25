/**
 * 관제 시뮬레이터 한 판에서 화면별 파생값이 같은지 본다(관리자 설계 4-4 sim 단일 원천). 2026-09-25 디자인 평가가
 * 같은 시각의 화면끼리 어긋난다고 짚은 자리(봇 발행률, 조회 CPU, 증설 기준, 대수, 매칭 리더, 복제, Teams, ai-1,
 * 수집 대상, 남은 시간, 증감)를 시나리오의 여러 순간마다 한꺼번에 견준다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { OpsSim, type OpsActorRef } from "../lib/admin/opsSim";
import { capacityOf, SimAdminApi } from "../lib/admin/simAdmin";
import { asgView, flowFromCenter, isLeader, nodeViews, type AppId, type EngineId } from "../lib/admin/flowSim";
import { collectTargets, infraCounts, overallOf } from "../lib/admin/opsHealth";
import { integrationsOf, teamsDelivery } from "../lib/admin/integrations";
import { simReportDesk } from "../lib/admin/reportSim";
import { kstDay, lagWord, remainWord } from "../lib/admin/timeText";
import { breachMinutes, trendDelta } from "../lib/admin/opsTrends";
import type {
  AiDesk,
  ControlCenter,
  IncidentLog,
  OpsHost,
  OpsSection,
  OpsTrading,
  ReadTier,
  Replication,
  ReportDesk,
  ThresholdRegistry,
} from "../lib/admin/types";

const OP: OpsActorRef = { id: "op-01", name: "김수퍼" };
const T0 = new Date(2026, 8, 24, 12, 0, 0).getTime();
const at = (sec: number) => T0 + sec * 1000;
const ENGINE_OF: Record<string, EngineId> = { "app-1": "api1", "app-2": "api2" };
/** 앱 계층의 자동 증설 노드. read-N 은 없다(ADR 0030 사용자 정정 2026-09-25) */
const READ_OF: Record<string, AppId> = { "app-3": "app3" };

function ready<T>(s: { state: string; data?: T }): T {
  assert.equal(s.state, "READY");
  return (s as { data: T }).data;
}

/** 한 판에서 관제센터, 서비스 흐름·지표 관제, 용량·부하, 봇 플릿, 서버·노드, 설정이 그릴 값을 모두 파생해 견준다 */
function checkWorld(c: ControlCenter, sim: OpsSim, now: number, where: string): void {
  const tier = ready<ReadTier>(c.readTier);
  const hosts = ready<OpsHost[]>(c.hosts);
  const trading = ready<OpsTrading>(c.trading);
  const repl = ready<Replication>(c.replication);
  const reg = ready<ThresholdRegistry>(c.thresholds);
  const scaleOut = reg.items.find((t) => t.key === "read.scale_out_cpu")?.value;

  const flow = flowFromCenter(c, 42, []);
  assert.ok(flow, `${where}: 관제 판을 흐름으로 옮길 수 있어야 한다`);
  const { state, sample } = flow;
  const views = nodeViews(state, sample.cpu);
  const cap = capacityOf(c, () => 0);
  const load = sim.botLoad(now);

  // 봇 발행률: 거래 요약·봇 플릿·서비스 흐름·지표 관제
  assert.equal(trading.botOps, load.current, `${where}: 거래 칸과 봇 플릿의 지금 발행률`);
  assert.equal(trading.botTargetOps, load.target, `${where}: 거래 칸과 봇 플릿의 목표 발행률`);
  assert.equal(sample.botOps, trading.botOps, `${where}: 지표 관제의 봇 발행률`);
  assert.equal(state.live?.botTarget, trading.botTargetOps, `${where}: 서비스 흐름 팝오버의 목표 발행률`);
  assert.ok(views.bot.status.startsWith(`${trading.botOps} OPS`), `${where}: 시장 봇 카드 '${views.bot.status}'`);
  assert.equal(sample.trades, trading.tradesPerSec, `${where}: 초당 체결`);

  // 앱 계층 CPU: 관제센터 앱 계층·서비스 흐름·지표 관제·용량
  assert.deepEqual(tier.nodes.map((n) => n.id), ["app-3"], `${where}: 자동 증설 노드는 app-3 하나`);
  assert.deepEqual(tier.fixed, ["app-1", "app-2"], `${where}: 고정 앱 노드`);
  assert.equal(Math.round(sample.util * 100), tier.avgCpuPct ?? 0, `${where}: 앱 평균 사용률`);
  assert.equal(cap.readAvgCpuRatio === null ? null : Math.round(cap.readAvgCpuRatio * 100), tier.avgCpuPct, `${where}: 용량 화면 조회 평균`);
  for (const n of tier.nodes) {
    const id = READ_OF[n.id];
    assert.equal(sample.cpu[id], n.state === "OFF" ? null : n.cpuPct, `${where}: ${n.id} CPU`);
    const capNode = cap.nodes.find((x) => x.id === n.id);
    assert.ok(capNode, `${where}: 용량 화면에 ${n.id}`);
    if (capNode.cpuRatio !== null) assert.equal(Math.round(capNode.cpuRatio * 100), n.cpuPct, `${where}: 용량 화면 ${n.id} CPU`);
  }
  for (const h of hosts.filter((x) => x.role === "APP")) {
    assert.equal(sample.cpu[ENGINE_OF[h.id]], h.cpuPct, `${where}: 흐름의 ${h.id} CPU`);
    const capNode = cap.nodes.find((x) => x.id === h.id);
    assert.equal(capNode?.cpuRatio === null || capNode === undefined ? null : Math.round(capNode.cpuRatio * 100), h.cpuPct, `${where}: 용량 화면 ${h.id} CPU`);
  }

  // DB·봇 호스트 CPU: 서버·노드 카드와 지표 관제 인스턴스 표
  const cpuOf = (role: string) => hosts.find((h) => h.role === role)?.cpuPct ?? null;
  assert.deepEqual(state.live?.hostCpu, { pg1: cpuOf("DB_PRIMARY"), pg2: cpuOf("DB_STANDBY"), bot: cpuOf("BOT_OBS") }, `${where}: 인스턴스 표의 호스트 CPU`);

  // 증설 기준: 임계값 관리·앱 계층 눈금·서비스 흐름·지표 관제·용량
  assert.equal(Math.round((state.live?.scaleOut ?? 0) * 100), scaleOut, `${where}: 흐름의 증설 임계`);
  assert.equal(Math.round(cap.thresholds.scaleOutCpuRatio * 100), scaleOut, `${where}: 용량 화면의 증설 임계`);

  // 대수: 관제센터 '가동/희망'·서비스 흐름 ASG·지표 관제 '목표·등록'·용량 계약. 앱 계층 2~3대로 센다
  const serving = hosts.filter((h) => h.role === "APP" && h.collect !== "MISSING").length + tier.nodes.filter((n) => n.state === "IN_SERVICE").length;
  assert.equal(tier.serving, serving, `${where}: 앱 계층 가동 대수`);
  assert.ok(tier.desired >= 2 && tier.desired <= 3, `${where}: 앱 계층 희망 대수 2~3`);
  assert.equal(state.desired, tier.desired, `${where}: 흐름의 희망 대수`);
  assert.equal(cap.plan.desired, tier.desired, `${where}: 용량 화면의 희망 대수`);
  assert.equal(sample.registered, serving, `${where}: 지표 관제의 등록 대수`);
  assert.equal(asgView(state).registered, serving, `${where}: 흐름 ASG 머리의 등록 대수`);
  assert.equal(cap.nodes.filter((n) => n.servesReads).length, serving, `${where}: 용량 화면의 조회 받는 노드`);
  for (const n of tier.nodes) {
    const app = state.apps[READ_OF[n.id]];
    const want = n.state === "IN_SERVICE" ? "running" : n.state === "PENDING" ? "booting" : n.state === "DRAINING" ? "draining" : "stopped";
    assert.equal(app.status, want, `${where}: 흐름의 ${n.id} 상태`);
  }

  // 매칭 리더: 거래 요약·인프라 카드·서비스 흐름(ADR 0034 파티션 리더)
  assert.deepEqual(trading.leaders.map((l) => [l.partition, l.node]), [["p0", "app-1"], ["p1", "app-2"]], `${where}: 파티션 리더`);
  for (const l of trading.leaders) {
    assert.equal(hosts.find((h) => h.id === l.node)?.partition?.id, l.partition, `${where}: ${l.node} 카드의 파티션`);
    assert.ok(isLeader(state, ENGINE_OF[l.node]), `${where}: 흐름에서 ${l.node}가 리더`);
    assert.ok(state.live?.partitions.some((p) => p.id === l.partition && p.epoch === l.epoch), `${where}: 흐름의 ${l.partition} 세대`);
  }
  assert.notEqual(trading.leaders[0].epoch, trading.leaders[1].epoch, `${where}: 세대는 파티션마다 따로다`);

  // DB 복제: 관제센터 DB 묶음·지표 관제·서비스 흐름 Standby
  assert.equal(Math.round(sample.replLagKb ?? -1), Math.round((repl.lagBytes ?? 0) / 1024), `${where}: 복제 지연 KB`);
  assert.equal(views.pg2.status, repl.stream === "STOPPED" ? "복제 끊김" : "복제 수신 중", `${where}: Standby 상태`);

  // 수집 대상: 설정의 Prometheus 행과 서버·노드 표
  const targets = collectTargets(c);
  assert.equal(targets.targets, hosts.length + tier.nodes.filter((n) => n.state !== "OFF").length, `${where}: 수집 대상 수`);
  assert.equal(targets.rows.filter((r) => r.readState !== null).length, tier.nodes.length, `${where}: 수집 대상 표에 app-3`);
  const desk: OpsSection<ReportDesk> = { state: "READY", data: simReportDesk(now) };
  const rows = Object.fromEntries(integrationsOf(c, desk, { grafana: false, databasus: false }).map((r) => [r.key, r]));
  assert.ok(rows.prometheus.detail.startsWith(`수집 대상 ${targets.targets} · 응답 ${targets.ok}/${targets.targets}`), `${where}: '${rows.prometheus.detail}'`);

  // Teams: 설정의 연동 행과 서비스 흐름의 보고 경로 자리는 같은 판정을 쓴다
  assert.equal(rows.teams.state, teamsDelivery(desk).state, `${where}: Teams 판정`);

  // ai-1: 구축 전이라 연결은 가정이고, 연결됨으로 세지 않으며 인프라 집계에 들지 않는다
  const ai = ready<{ assumed: boolean }>(c.aiServer);
  assert.equal(ai.assumed, true, `${where}: sim의 ai-1은 가정`);
  assert.equal(rows.ai1.state, "ASSUMED", `${where}: 설정의 ai-1 행`);
  assert.doesNotMatch(rows.ai1.detail, /연결됨/, `${where}: ai-1 행에 '연결됨'을 적지 않는다`);
  const counts = infraCounts(c);
  assert.ok(counts);
  assert.equal(counts.ok + counts.warn + counts.down, hosts.length + tier.nodes.filter((n) => n.state !== "OFF").length, `${where}: 인프라 집계에서 ai-1 제외`);
  // 운영자 바의 '노드 n', 인프라 칸 머리의 주의·장애 수는 같은 노드를 센다(기동·드레이닝 중인 app-3 포함)
  assert.equal(overallOf(c).nodes, counts.warn + counts.down, `${where}: 운영자 바와 인프라 칸의 주의 노드 수`);
}

test("평시 한 판에서 모든 화면의 봇 발행률, 조회 CPU, 증설 기준, 대수, 리더, 복제, 연동 값이 같다", () => {
  const sim = new OpsSim(T0);
  checkWorld(sim.snapshot(at(5)), sim, at(5), "평시");
  const trading = ready<OpsTrading>(sim.snapshot(at(6)).trading);
  assert.equal(trading.botTargetOps, 30, "기준 발행률은 시드 기록('60 → 30 OPS') 뒤의 30 OPS다");
});

test("조회 급증에서 대기, 승인, 기동, 해제의 매 순간 화면별 값이 같다", () => {
  const sim = new OpsSim(T0);
  sim.startScenario("READ_SURGE", at(0));
  for (let sec = 1; sec <= 20; sec += 1) checkWorld(sim.snapshot(at(sec)), sim, at(sec), `급증 ${sec}초`);
  const pending = ready<AiDesk>(sim.snapshot(at(20)).ai).proposals.find((p) => p.kind === "READ_NODES" && p.status === "PROPOSED");
  assert.ok(pending, "20초에 app-3 켜기 조치안이 대기한다");
  assert.equal(pending.title, "app-3 켜기 (앱 2 → 3대)");
  sim.decide(pending.id, "APPROVE", OP, at(21));
  for (let sec = 21; sec <= 60; sec += 1) checkWorld(sim.snapshot(at(sec)), sim, at(sec), `승인 뒤 ${sec}초`);
  const tier = ready<ReadTier>(sim.snapshot(at(60)).readTier);
  assert.equal(tier.desired, 3);

  // AI 즉시 정지 뒤에도 판은 같은 원천에서 나온다. 정지는 AI 칸만 바꾸고 지표와 대수는 그대로 흐른다
  sim.stopAi(OP, at(61));
  for (let sec = 62; sec <= 70; sec += 1) checkWorld(sim.snapshot(at(sec)), sim, at(sec), `정지 뒤 ${sec}초`);
  assert.equal(ready<AiDesk>(sim.snapshot(at(70)).ai).mode, "STOPPED");
});

test("야간 축소를 자동 모드가 실행하고 되돌리는 동안에도 화면별 값이 같다", () => {
  const sim = new OpsSim(T0);
  sim.enableAuto(2, "야간 대기", OP, "apr-1", at(0));
  sim.startScenario("NIGHT_SCALE_IN", at(1));
  let draining = false;
  for (let sec = 2; sec <= 120; sec += 1) {
    const c = sim.snapshot(at(sec));
    if (ready<ReadTier>(c.readTier).nodes.some((n) => n.state === "DRAINING")) draining = true;
    checkWorld(c, sim, at(sec), `야간 ${sec}초`);
  }
  assert.ok(draining, "야간 시나리오에서 드레이닝 순간을 지난다");
});

test("봇 플릿에서 발행률을 바꾸면 관제 판, 흐름, 봇 플릿이 같은 값을 보고 사건 기록에 운영자 조치로 남는다", () => {
  const sim = new OpsSim(T0);
  sim.setBotTarget(60, OP, "봇 플릿 화면", at(3));
  const c = sim.snapshot(at(4));
  checkWorld(c, sim, at(4), "발행률 60");
  assert.equal(ready<OpsTrading>(c.trading).botTargetOps, 60);
  const log = ready<IncidentLog>(c.incidents);
  assert.ok(log.events.some((e) => e.actor === "OPERATOR" && e.text === "봇 발행 30 → 60 OPS · 봇 플릿 화면"));

  sim.setFleet(0, false);
  const stopped = sim.snapshot(at(5));
  checkWorld(stopped, sim, at(5), "봇 정지");
  assert.equal(ready<OpsTrading>(stopped.trading).botOps, 0, "정지한 플릿은 발행률 0이다");
});

test("관리 어댑터의 종합 조회와 봇 상태는 관제 판의 거래 칸과 같은 리더와 발행률을 말한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const c = await api.getControlCenter();
  const trading = ready<OpsTrading>(c.trading);
  const ov = await api.getOverview();
  assert.equal(ov.leaderNodeId, trading.leaders.map((l) => l.node).join("·"));
  assert.equal(ov.currentOps, trading.botOps);
  assert.equal(ov.marketPartitions, trading.leaders.reduce((a, l) => a + l.markets.length, 0));
  const status = await api.getSimStatus();
  assert.equal(status.rate, trading.botTargetOps);
  assert.equal(status.currentOps, trading.botOps);
  const ha = await api.getHaStatus();
  assert.deepEqual(ha.nodes.map((n) => n.id), ["app-1", "app-2"], "옛 engine-01~03은 없다");

  await api.simSetRate(45);
  const after = ready<OpsTrading>((await api.getControlCenter()).trading);
  assert.equal(after.botTargetOps, 45);
  assert.equal((await api.getSimStatus()).rate, 45);
});

test("날짜 경계와 초 단위 지연은 브라우저 시간대와 무관하게 같은 꼴이다", () => {
  // UTC 15:30은 KST로 다음 날 00:30이다. 콘솔의 '오늘'은 KST 자정으로 가른다
  assert.equal(kstDay(Date.UTC(2026, 8, 24, 15, 30)), "2026-09-25");
  assert.equal(kstDay(Date.UTC(2026, 8, 24, 14, 59)), "2026-09-24");
  assert.equal(lagWord(1), "1.0초");
  assert.equal(lagWord(0.25), "0.3초");
});

test("남은 시간은 사이드바와 모드 줄이 같은 꼴로 적는다", () => {
  assert.equal(remainWord((1 * 3600 + 59 * 60 + 49) * 1000), "1시간 59분");
  assert.equal(remainWord(2 * 3600 * 1000), "2시간 0분");
  assert.equal(remainWord(59 * 60 * 1000 + 30_000), "59분");
  assert.equal(remainWord(42_000), "42초");
});

test("증감은 지금 값과 앞 시점 값의 차이라 큰 수치와 같은 쪽을 가리키고, 변화가 없으면 ±0이다", () => {
  const flat = { points: Array.from({ length: 60 }, () => 170), current: 170, previous: 170 };
  const up = trendDelta("p95", flat, 1, 507);
  assert.ok(up);
  assert.equal(up.dir, "up");
  assert.equal(up.good, false);
  assert.equal(up.text, "337ms");
  assert.deepEqual(trendDelta("p95", flat, 1, 170), { diff: 0, text: "±0", dir: "flat", good: null });
  const av = { points: Array.from({ length: 60 }, () => 99.98), current: 99.98, previous: 99.98 };
  assert.equal(trendDelta("availability", av, 1, 99.7)?.text, "0.28%p");
  const alerts = { points: Array.from({ length: 60 }, (_, i) => (i === 49 ? 1 : 0)), current: 1, previous: 0 };
  assert.equal(trendDelta("alerts", alerts, 1, 0)?.text, "1건", "10분 전 1건에서 지금 0건");
  assert.equal(trendDelta("alerts", alerts, 1, 0)?.good, true);
  const incidents = { points: Array.from({ length: 60 }, (_, i) => (i === 55 ? 1 : 0)), current: 1, previous: 0 };
  assert.equal(trendDelta("incidents", incidents, 1, 3)?.text, "1건", "최근 10분 안에 연 사건 수");
  assert.equal(breachMinutes([170, 420, 430, 440], (v) => v > 400), 3, "지금 분을 포함해 목표를 넘은 분");
  assert.equal(breachMinutes([170, 180, 190, 440], (v) => v > 400), 1);
});
