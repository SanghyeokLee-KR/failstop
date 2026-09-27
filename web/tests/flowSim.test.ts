import { test } from "node:test";
import assert from "node:assert/strict";
import {
  BASE_OPS,
  LEASE_TTL,
  SCALE_OUT_ADVICE,
  START_EPOCH,
  SURGE_OPS,
  asgView,
  availability,
  createFlow,
  dbEdgeTier,
  describeScenario,
  edgeFlows,
  edgeTier,
  isBusy,
  linePoints,
  nodeViews,
  primaryDb,
  startScenario,
  stepFlow,
  writable,
  type FlowSample,
  type FlowState,
  type ScenarioId,
} from "../lib/admin/flowSim";

function run(s: FlowState, ticks: number): { state: FlowState; samples: FlowSample[] } {
  const samples: FlowSample[] = [];
  let cur = s;
  for (let i = 0; i < ticks; i++) {
    const r = stepFlow(cur);
    cur = r.state;
    samples.push(r.sample);
  }
  return { state: cur, samples };
}

/** 시나리오 시작 틱 기준 elapsed 구간의 표본 */
function window(samples: FlowSample[], startTick: number, from: number, to: number): FlowSample[] {
  return samples.filter((x) => x.at - startTick >= from && x.at - startTick < to);
}

const labels = (s: FlowState) => s.events.map((e) => e.label);
const CPU = { api1: 10, api2: 5, app3: null };

test("초기 상태는 compose 기본 기동과 AWS 목표 구성의 대응이다", () => {
  const s = createFlow();
  assert.equal(s.holder, "api1");
  assert.equal(s.epoch, START_EPOCH);
  // 앱 계층 2~3대(ADR 0030 사용자 정정). 매칭 리더 두 대가 조회도 받고 app-3 은 자동 증설 자리라 꺼져 있다
  assert.equal(s.apps.app3.status, "stopped");
  assert.equal(s.apps.api1.inRead, true);
  assert.equal(s.apps.api2.inRead, true);
  assert.equal(s.apps.app3.inRead, false);
  assert.equal(s.desired, 2);
  assert.equal(s.botOps, BASE_OPS);
  assert.deepEqual(s.db, { pg1: "primary", pg2: "standby" });
  assert.equal(s.endpoint, "pg1");
  assert.equal(s.replicating, true);
  assert.equal(writable(s), true);
  const v = nodeViews(s, CPU);
  // CloudFront 는 대역에 없지만 사용자가 목표 구성대로 켜진 노드로 두기로 했다. 상태 글자가 그 사실을 밝힌다
  assert.equal(v.cdn.state, "running");
  assert.equal(v.cdn.status, "목표 구성");
  assert.equal(v.cdn.container, null);
  assert.equal(v.app3.state, "stopped");
  assert.equal(v.api1.state, "running");
  assert.match(v.api1.role, /조회/);
  assert.equal(v.app3.role, "조회 전용 · Auto Scaling");
  // 이름은 관제센터와 같다. read-N 은 없다
  assert.deepEqual([v.api1.name, v.api2.name, v.app3.name, v.pg1.name, v.pg2.name], ["app-1", "app-2", "app-3", "db-primary", "db-standby"]);
  assert.equal(v.app3.container, "chaos-api-3");
  assert.equal(v.alb.container, "chaos-lb");
  // Standby 컨테이너는 아직 없으니 이름 옆에 추가 예정을 남긴다
  assert.match(v.pg2.container ?? "", /2단계 추가/);
});

test("리더 중단은 리스 만료 뒤 대기 노드를 승격하고 세대를 한 번 올린다", () => {
  const started = startScenario(run(createFlow(), 10).state, "leaderKill");
  const t0 = started.tick;
  const { state, samples } = run(started, 20);
  assert.deepEqual(labels(state), ["app-1 kill", "리스 만료 · 쓰기 리더 없음", `app-2 승격 · epoch ${START_EPOCH + 1}`]);
  assert.equal(state.holder, "api2");
  assert.equal(state.epoch, START_EPOCH + 1);
  // docker kill 은 restart 정책을 건너뛴다. 재기동 전까지 중단이 남는다
  assert.equal(state.apps.api1.status, "killed");
  assert.equal(nodeViews(state, CPU).api1.state, "failure");
  for (const x of window(samples, t0, 1, 8)) assert.equal(x.trades, 0);
  assert.ok(window(samples, t0, 9, 15).every((x) => x.trades !== null && x.trades > 0));
  assert.ok(window(samples, t0, 1, 8).some((x) => x.lease === 0));
  for (const x of window(samples, t0, 1, 20)) assert.equal(x.cpu.api1, null);
});

test("리스가 다 닳으면 승격 전까지 리더 칸을 비운다", () => {
  let cur = startScenario(run(createFlow(), 4).state, "leaderKill");
  let emptySeen = false;
  for (let i = 0; i < 8; i++) {
    cur = stepFlow(cur).state;
    if (cur.lease === 0) {
      assert.equal(cur.holder, null);
      assert.equal(nodeViews(cur, CPU).api1.role, "리더 경쟁 제외");
      emptySeen = true;
    }
  }
  assert.ok(emptySeen);
  assert.equal(cur.holder, "api2");
});

test("승격한 틱의 리스는 틱 홀짝과 상관없이 TTL 이다", () => {
  for (const warm of [3, 4]) {
    let cur = startScenario(run(createFlow(), warm).state, "leaderKill");
    for (let i = 0; i < 8; i++) cur = stepFlow(cur).state;
    assert.equal(cur.epoch, START_EPOCH + 1);
    assert.equal(cur.lease, LEASE_TTL, `warm ${warm}`);
  }
});

test("리더 대기 노드가 없으면 리더 중단을 막는다", () => {
  const killed = run(startScenario(createFlow(), "leaderKill"), 20).state;
  const a = availability(killed, "leaderKill");
  assert.equal(a.ok, false);
  assert.match(a.reason ?? "", /리더 대기 노드 없음/);
  assert.equal(startScenario(killed, "leaderKill"), killed);
});

test("재기동하면 리더 대기로 합류하고 리더는 그대로다", () => {
  const killed = run(startScenario(createFlow(), "leaderKill"), 20).state;
  assert.equal(availability(killed, "restart", "api2").ok, false);
  const { state } = run(startScenario(killed, "restart", "api1"), 17);
  assert.equal(state.apps.api1.status, "running");
  assert.equal(state.holder, "api2");
  const again = run(startScenario(state, "leaderKill"), 20).state;
  assert.equal(again.holder, "api1");
  assert.equal(again.epoch, START_EPOCH + 2);
});

test("Primary 중단은 사람이 승격하고 접속을 옮긴 뒤에야 쓰기가 돌아온다", () => {
  const started = startScenario(run(createFlow(), 6).state, "dbSwitch");
  const t0 = started.tick;
  const { state, samples } = run(started, 35);
  assert.deepEqual(labels(state), [
    "Primary db-primary 중단",
    "리스 만료 · 쓰기 리더 없음",
    "운영자 승격 결정 · 가정",
    "db-standby 승격 · pg_promote",
    "앱 DB 접속 전환 → db-standby",
    `app-1 리스 획득 · epoch ${START_EPOCH + 1}`,
  ]);
  assert.deepEqual(state.db, { pg1: "stopped", pg2: "primary" });
  assert.equal(primaryDb(state), "pg2");
  assert.equal(state.endpoint, "pg2");
  assert.equal(state.holder, "api1");
  assert.equal(state.epoch, START_EPOCH + 1);
  assert.equal(state.replicating, false);
  // 자동 페일오버가 없으니 접속 전환과 리스 재획득 전까지 체결은 실제 0 이다
  for (const x of window(samples, t0, 1, 21)) assert.equal(x.trades, 0);
  assert.ok(window(samples, t0, 22, 30).every((x) => x.trades !== null && x.trades > 0));
  // 복제 상대가 없으니 복제 지연은 미수집이고, 앱 프로세스는 살아 있어 CPU 는 끊기지 않는다
  for (const x of window(samples, t0, 1, 35)) assert.equal(x.replLagKb, null);
  for (const x of samples) assert.notEqual(x.cpu.api1, null);
});

test("DB 전환 공백에는 앱과 옛 Primary 를 장애로 그린다", () => {
  const mid = run(startScenario(createFlow(), "dbSwitch"), 10).state;
  const v = nodeViews(mid, CPU);
  assert.equal(v.api1.state, "failure");
  assert.equal(v.api1.status, "DB 접속 불가");
  // 앱이 아직 옛 Primary 를 보고 있으니 그 DB 가 장애다
  assert.equal(v.pg1.state, "failure");
  assert.equal(v.alb.state, "failure");
  // 조회를 받는 app-2 도 같은 Primary 를 보므로 전환 공백에는 조회도 멈춘다
  assert.equal(v.api2.state, "failure");
  const after = run(startScenario(createFlow(), "dbSwitch"), 35).state;
  // 접속을 옮긴 뒤의 옛 Primary 는 장애가 아니라 중지다
  assert.equal(nodeViews(after, CPU).pg1.state, "stopped");
});

test("Standby 가 없으면 DB 전환을 막고, 재편입하면 반대 방향으로 다시 된다", () => {
  const switched = run(startScenario(createFlow(), "dbSwitch"), 35).state;
  const blocked = availability(switched, "dbSwitch");
  assert.equal(blocked.ok, false);
  assert.match(blocked.reason ?? "", /Standby 없음/);
  assert.equal(availability(switched, "rejoin", "pg2").ok, false);

  const r = startScenario(switched, "rejoin", "pg1");
  const t0 = r.tick;
  const { state, samples } = run(r, 32);
  assert.deepEqual(state.db, { pg1: "standby", pg2: "primary" });
  assert.equal(state.replicating, true);
  assert.ok(window(samples, t0, 28, 32).every((x) => x.replLagKb !== null));

  const back = run(startScenario(state, "dbSwitch"), 35).state;
  assert.deepEqual(back.db, { pg1: "primary", pg2: "stopped" });
  assert.equal(back.endpoint, "pg1");
  assert.equal(back.epoch, START_EPOCH + 2);
});

test("조회 부하는 권고를 띄우고 증설·축소가 측정 순서대로 지나간다", () => {
  const started = startScenario(run(createFlow(), 3).state, "scale");
  const t0 = started.tick;
  const { state, samples } = run(started, 105);
  const l = labels(state);
  const idx = (x: string) => l.findIndex((y) => y.startsWith(x));
  assert.ok(idx("증설 권고") >= 0 && idx("증설 권고") < idx("app-3 켜기 승인"));
  assert.ok(idx("app-3 기동") < idx("app-3 ALB 등록"));
  assert.ok(idx("app-3 끄기 승인") < idx("ALB 제외"));
  assert.ok(idx("ALB 제외") < idx("app-3 중지"));
  assert.equal(state.apps.app3.status, "stopped");
  assert.equal(state.desired, 2);
  assert.ok(window(samples, t0, 0, 24).every((x) => x.registered === 2));
  assert.ok(window(samples, t0, 24, 61).every((x) => x.registered === 3));
  assert.ok(window(samples, t0, 61, 100).every((x) => x.registered === 2));
  assert.ok(window(samples, t0, 12, 24).every((x) => x.desired === 3 && x.registered === 2));
  assert.ok(window(samples, t0, 24, 31).some((x) => x.util !== null && x.util > 0.7));
  for (const x of window(samples, t0, 0, 13)) assert.equal(x.cpu.app3, null);
  assert.ok(window(samples, t0, 14, 60).every((x) => x.cpu.app3 !== null));
  // 조회는 app-1·app-2 가 함께 받으므로 조회 부하가 두 리더의 CPU 도 민다
  assert.ok(window(samples, t0, 8, 20).every((x) => (x.cpu.api1 ?? 0) > 40 && (x.cpu.api2 ?? 0) > 40));
  // app-3 이 합류해 예열이 끝나면 세 노드가 나눠 받아 사용률이 내려간다
  assert.ok(window(samples, t0, 40, 60).every((x) => x.util !== null && x.util < 0.3));
});

test("봇 급증은 쓰기 경로만 밀고 조회 권고를 띄우지 않는다", () => {
  const { state, samples } = run(startScenario(run(createFlow(), 5).state, "traffic"), 65);
  assert.ok(labels(state).includes(`봇 ${SURGE_OPS} OPS`));
  assert.equal(labels(state).some((x) => x.startsWith("증설 권고")), false);
  const peak = Math.max(...samples.map((x) => x.trades ?? 0));
  assert.ok(peak > 17 && peak < 19.5, `peak ${peak}`);
  assert.ok(samples.every((x) => x.util !== null && x.util < SCALE_OUT_ADVICE));
});

test("재생 중에는 다른 재생을 시작하지 않는다", () => {
  const s = startScenario(createFlow(), "traffic");
  assert.ok(isBusy(s));
  assert.equal(availability(s, "dbSwitch").ok, false);
  assert.equal(startScenario(s, "leaderKill"), s);
});

test("대상이 맞지 않으면 재생을 막는다", () => {
  assert.match(availability(createFlow(), "leaderKill", "api2").reason ?? "", /리더 노드에만/);
  assert.match(availability(createFlow(), "dbSwitch", "pg2").reason ?? "", /Primary 에만/);
  assert.match(availability(createFlow(), "rejoin", "pg2").reason ?? "", /중지된 DB 에만/);
});

test("같은 조작은 같은 표본을 만든다", () => {
  const a = run(startScenario(createFlow(), "dbSwitch"), 40).samples;
  const b = run(startScenario(createFlow(), "dbSwitch"), 40).samples;
  assert.deepEqual(a, b);
});

test("사건 시각은 표본 시각과 같은 축 위에 있다", () => {
  const { state, samples } = run(startScenario(run(createFlow(), 3).state, "scale"), 100);
  const ticks = new Set(samples.map((x) => x.at));
  for (const e of state.events.slice(1)) assert.ok(ticks.has(e.at), `${e.label} at ${e.at}`);
});

test("공백 직전 점만 끊어 공백을 가로지르는 선을 막는다", () => {
  const at = (n: number, v: number | null): FlowSample => ({
    at: n, trades: 0, botOps: 0, lease: 0, cpu: { api1: v, api2: 1, app3: null }, util: 0, desired: 2, registered: 2, replLagKb: null,
  });
  const pick = (s: FlowSample) => s.cpu.api1;
  assert.deepEqual(
    linePoints([at(1, 1), at(2, 2), at(3, null), at(4, null), at(5, 3), at(6, 4)], pick).map((p) => p.cut),
    [false, true, false, false, false, false],
  );
  assert.deepEqual(linePoints([at(1, 1), at(2, null)], pick).map((p) => p.cut), [true, false]);
  assert.equal(linePoints([at(1, 1), at(2, 2)], pick).some((p) => p.cut), false);
});

test("리더 중단 뒤 재기동하면 옛 리더 CPU 선이 한 번 끊겼다 이어진다", () => {
  const before = run(createFlow(), 3);
  const killed = run(startScenario(before.state, "leaderKill"), 16);
  const back = run(startScenario(killed.state, "restart", "api1"), 15);
  const samples = [...before.samples, ...killed.samples, ...back.samples];
  assert.equal(linePoints(samples, (s) => s.cpu.api1).filter((p) => p.cut).length, 1);
});

test("화면 문구는 명사형이다", () => {
  const ids: ScenarioId[] = ["traffic", "scale", "leaderKill", "restart", "dbSwitch", "rejoin"];
  const states = [
    createFlow(),
    run(startScenario(createFlow(), "leaderKill"), 3).state,
    run(startScenario(createFlow(), "dbSwitch"), 10).state,
    run(startScenario(createFlow(), "dbSwitch"), 35).state,
    run(startScenario(createFlow(), "scale"), 20).state,
  ];
  const words: string[] = [];
  for (const s of states) {
    words.push(...labels(s), asgView(s).status);
    for (const v of Object.values(nodeViews(s, CPU))) words.push(v.status, v.role, v.name);
    for (const id of ids) {
      for (const t of [undefined, "api1", "pg1"] as const) {
        const d = describeScenario(s, id, t);
        words.push(d.title, d.target, ...d.impacts, ...d.observe, ...d.evidence);
        const a = availability(s, id, t);
        if (a.reason) words.push(a.reason);
      }
    }
  }
  for (const w of new Set(words)) assert.doesNotMatch(w, /(다|요|니다|세요|십시오)[.]?$/, w);
});

test("연결선은 평시 흐르고, 급증하면 세지고, 끊기면 장애가 된다", () => {
  const base = run(createFlow(), 5).state;
  const e0 = edgeFlows(base);
  assert.equal(e0["alb-api1"].kind, "flow");
  assert.equal(e0["bot-alb"].tier, 1);
  // 리더 대기 노드도 조회를 받으므로 그 선에는 조회 몫이 흐르고, 쓰기를 받는 리더 선보다 세지 않다
  assert.equal(e0["alb-api2"].kind, "flow");
  assert.ok(e0["alb-api2"].rate < e0["alb-api1"].rate);
  // app-3 이 꺼져 있으면 Auto Scaling 그룹으로 가는 조회선과 그 조회 SQL 선은 꺼짐이다
  assert.equal(e0["alb-read"].kind, "off");
  assert.equal(e0["read-db"].kind, "off");
  assert.equal(e0["app-db"].kind, "flow");
  // 사용자 요청은 CloudFront 를 지나 ALB 로 흐른다
  assert.equal(e0["users-cdn"].kind, "flow");
  assert.equal(e0["cdn-alb"].kind, "flow");
  assert.equal(e0["load-alb"].kind, "off");
  assert.equal(e0.repl.kind, "flow");

  const surge = run(startScenario(base, "traffic"), 20).state;
  assert.equal(edgeFlows(surge)["bot-alb"].tier, 3);
  assert.ok(edgeFlows(surge)["alb-api1"].tier > e0["alb-api1"].tier);

  const killed = run(startScenario(base, "leaderKill"), 3).state;
  assert.equal(edgeFlows(killed)["alb-api1"].kind, "down");

  const gap = run(startScenario(base, "dbSwitch"), 10).state;
  const eg = edgeFlows(gap);
  assert.equal(eg["app-db"].kind, "down");
  assert.equal(eg.repl.kind, "down");
  assert.equal(eg["alb-api1"].kind, "down");
  assert.equal(eg["alb-api2"].kind, "down");
  assert.equal(eg["alb-read"].kind, "off");
  assert.equal(eg["read-db"].kind, "off");
  const after = run(startScenario(base, "dbSwitch"), 35).state;
  assert.equal(edgeFlows(after)["app-db"].kind, "flow");
  assert.equal(edgeFlows(after).repl.kind, "down");
  const rejoining = run(startScenario(after, "rejoin", "pg1"), 5).state;
  assert.equal(edgeFlows(rejoining).repl.kind, "transition");
});

test("조회 부하와 app-3 켜기 중에는 그룹이 전환을 거쳐 앱 계층이 세 대가 된다", () => {
  const started = startScenario(run(createFlow(), 3).state, "scale");
  const booting = run(started, 15).state;
  assert.equal(nodeViews(booting, CPU).app3.state, "transition");
  assert.equal(asgView(booting).state, "transition");
  assert.equal(asgView(booting).status, "앱 2 → 3대");
  assert.equal(edgeFlows(booting)["load-alb"].tier, 3);
  // 조정 중에도 조회는 app-1·app-2 로 흐르고, 그룹으로 가는 선은 전환 중이다
  assert.equal(edgeFlows(booting)["alb-api1"].tier, 3);
  assert.equal(edgeFlows(booting)["alb-api2"].tier, 3);
  assert.equal(edgeFlows(booting)["alb-read"].kind, "transition");
  const joined = run(started, 26).state;
  assert.equal(nodeViews(joined, CPU).app3.state, "running");
  assert.equal(asgView(joined).state, "running");
  assert.equal(asgView(joined).status, "앱 3대");
  // 조회가 세 노드로 나뉜다
  const ej = edgeFlows(joined);
  assert.equal(ej["alb-read"].kind, "flow");
  assert.equal(ej["read-db"].kind, "flow");
  assert.ok(ej["alb-api2"].rate < edgeFlows(booting)["alb-api2"].rate);
});

test("매칭 리더가 멈추면 그 노드의 조회도 빠지고 그룹은 조정 중으로 보이지 않는다", () => {
  const killed = run(startScenario(run(createFlow(), 5).state, "leaderKill"), 3).state;
  assert.equal(killed.apps.api1.inRead, false);
  const a = asgView(killed);
  assert.equal(a.registered, 1);
  assert.equal(a.state, "running");
  assert.equal(a.status, "앱 1대");
  const restarted = run(startScenario(run(killed, 20).state, "restart", "api1"), 15).state;
  assert.equal(restarted.apps.api1.inRead, true);
  assert.equal(asgView(restarted).registered, 2);
});

test("흐름 단계는 경계에서 갈린다", () => {
  assert.deepEqual([0, 1, 19.9, 20, 149, 150, 400].map(edgeTier), [0, 1, 1, 2, 2, 3, 3]);
  // 주 DB 커밋 선은 평소 커밋(초당 약 230)이 가장 가는 단계에 든다
  assert.deepEqual([0, 1, 230, 299, 300, 400, 999, 1000].map(dbEdgeTier), [0, 1, 1, 1, 2, 2, 2, 3]);
});
