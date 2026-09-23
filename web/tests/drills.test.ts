/**
 * 시연 대본. 이 파일이 지키는 것은 하나다. 화면이 일어나지 않은 일을 일어났다고 말하지 않는다.
 *
 * 네 축으로 고정한다.
 *
 * 1. 값을 못 읽었으면 어떤 단계도 충족이 아니다. 종전 관제 화면이 빈 표를 통과로 칠한
 *    전례가 있어 같은 경로를 여기서 미리 닫는다.
 * 2. needs 선언과 실제 사용이 일치한다. 선언에서 빠진 축은 화면이 부르지 않으므로,
 *    그 축을 읽는 단계는 영원히 미수집으로 굳는다. 빌드도 타입검사도 잡지 못한다.
 * 3. 제목이 명사형이다. 디자인시스템이 관리 평면을 예외로 두지 않는다.
 * 4. 주행 기록은 게이트를 지난 단계에만, 주행을 켠 뒤의 관측에서만, 앞 단계보다 늦게 생긴다.
 *    뒤 단계가 먼저 참이던 순간을 진행으로 적으면 일어나지 않은 전환이 완료로 뜬다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  DRILLS,
  evaluateDrill,
  type DrillDef,
  type DrillRun,
  type DrillSeen,
  type DrillSignalKey,
  type DrillSignals,
  type DrillStep,
} from "../lib/admin/drills";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import type { CapacityNode, CapacityState, HaRecovery, HaStatus, SimStatus } from "../lib/admin/types";

const EMPTY: DrillSignals = { cap: null, sim: null, ha: null, rec: null };

const cap: CapacityState = {
  tier: "app",
  plan: { min: 2, desired: 3, max: 3, updatedBy: "op-01", updatedAt: "2026-09-23T00:00:00Z" },
  observedTotal: 3,
  reconciled: true,
  readAvgCpuRatio: 0.71,
  thresholds: { scaleOutCpuRatio: 0.6, scaleInCpuRatio: 0.3 },
  nodes: [
    {
      id: "app-1",
      az: "a",
      instanceType: "1vCPU",
      engine: true,
      scalable: false,
      expected: true,
      reachable: true,
      leader: true,
      servesReads: true,
      cpuRatio: 0.7,
      cpuCount: 1,
      heapUsedBytes: 100,
      heapMaxBytes: 1000,
      httpRequests: 10,
      wsClients: 0,
    },
  ],
  advice: [{ id: "scale_out", level: "warn", headline: "증설 권고", detail: "", action: "SCALE_OUT" }],
};

const sim: SimStatus = {
  source: "published",
  observedAt: "2026-09-23T00:00:00Z",
  stale: false,
  running: true,
  paused: false,
  rate: 400,
  currentOps: 380,
  botCount: 10,
  activeBots: 10,
  successRate: null,
  avgLatencyMs: null,
  duplicateFillTotal: null,
  bots: [],
  markets: null,
  tradeFlash: "normal",
};

const ha: HaStatus = {
  epoch: 7,
  leaderNodeId: "app-1",
  nodes: [],
  tradesTotal: 100,
  generatedAt: "2026-09-23T00:00:00Z",
};

const rec: HaRecovery = {
  runs: [],
  lastRtoMs: 3200,
  lastVerdict: "PASS",
  verdictScope: "since",
  replayVerdict: "PASS",
  failoverVerdict: "PASS",
};

const FULL: DrillSignals = { cap, sim, ha, rec };

/** 선언한 축만 채운 신호. 선언 누락이면 이 신호에서 판정이 달라진다 */
function onlyDeclared(needs: DrillSignalKey[]): DrillSignals {
  const pick = <K extends DrillSignalKey>(k: K) => (needs.includes(k) ? FULL[k] : null);
  return { cap: pick("cap"), sim: pick("sim"), ha: pick("ha"), rec: pick("rec") };
}

/** 모든 앞 단계가 같은 값을 보고 지난 것으로 친 관측. 뒤 단계가 대조하는 값까지 채운다 */
function seenAll(def: DrillDef, s: DrillSignals): DrillSeen {
  return Object.fromEntries(def.steps.map((x) => [x.id, s]));
}

const T = (m: number) => `2026-09-23T00:${String(m).padStart(2, "0")}:00.000Z`;

function drill(id: string): DrillDef {
  const d = DRILLS.find((x) => x.id === id);
  assert.ok(d, `${id} 대본 없음`);
  return d;
}

function step(def: DrillDef, id: string): DrillStep {
  const s = def.steps.find((x) => x.id === id);
  assert.ok(s, `${def.id}/${id} 단계 없음`);
  return s;
}

const node = (id: string, over: Partial<CapacityNode> = {}): CapacityNode => ({ ...cap.nodes[0], id, ...over });

test("값을 못 읽으면 어떤 단계도 충족이 아니다", () => {
  for (const def of DRILLS) {
    for (const seen of [{}, seenAll(def, FULL)]) {
      for (const x of def.steps) {
        const c = x.check(EMPTY, seen);
        assert.notEqual(c.state, "MET", `${def.id}/${x.id} 가 빈 신호에서 충족을 냈다`);
      }
    }
  }
});

test("needs 선언과 실제 사용이 일치한다", () => {
  for (const def of DRILLS) {
    const partial = onlyDeclared(def.needs);
    def.steps.forEach((step, i) => {
      const a = step.check(partial, seenAll(def, partial));
      const b = step.check(FULL, seenAll(def, FULL));
      assert.deepEqual(
        a,
        b,
        `${def.id}/${step.id}(${i}) 가 선언에 없는 축을 읽는다. needs=[${def.needs.join(",")}]`,
      );
    });
  }
});

test("원천 없는 단계는 진행 분모에서 빠진다", () => {
  for (const def of DRILLS) {
    const ev = evaluateDrill(def, FULL);
    const noSource = ev.raw.filter((c) => c.state === "NO_SOURCE").length;
    assert.equal(ev.total, def.steps.length);
    assert.equal(ev.judgeable, def.steps.length - noSource);
  }
});

test("판정 불가만 남으면 현재 단계가 없다", () => {
  const db = DRILLS.find((d) => d.id === "db");
  assert.ok(db);
  const ev = evaluateDrill(db, FULL);
  assert.equal(ev.currentIndex, null);
  assert.equal(ev.judgeable, 0);
  // 원천이 없는 축이 진행을 막지 않는다. 막으면 대본이 첫 칸에서 멈춘다
  assert.ok(ev.checks.every((c) => c.state === "NO_SOURCE"));
});

test("앞 단계가 끝나기 전에는 뒤 단계를 판정하지 않는다", () => {
  const scale = DRILLS.find((d) => d.id === "scale");
  assert.ok(scale);
  // 기준 대수만 맞고 부하는 아직인 상태. 뒤쪽의 "사용률 하락"과 "축소 완료"는
  // 조건만 보면 참이지만 시연의 진행은 아니다
  const idle: DrillSignals = {
    cap: { ...cap, plan: { ...cap.plan, desired: 2 }, observedTotal: 2, readAvgCpuRatio: 0.1, advice: [] },
    sim: { ...sim, rate: 10, currentOps: 10 },
    ha: null,
    rec: null,
  };
  const ev = evaluateDrill(scale, idle);
  assert.equal(ev.currentIndex, 1, "발행률 상향이 현재 단계여야 한다");
  assert.equal(ev.position, 2);
  for (let i = 2; i < ev.checks.length; i += 1) {
    if (ev.raw[i].state === "NO_SOURCE") continue;
    assert.equal(ev.checks[i].state, "BLOCKED", `${scale.steps[i].id} 가 앞질러 판정됐다`);
  }
  // 라이브 판정 자체는 남겨 둔다. 화면은 안 쓰지만 진단에서 필요하다
  assert.equal(ev.raw[scale.steps.findIndex((s) => s.id === "restore")].state, "MET");
});

test("이번 주행 기록이 있으면 되돌아간 단계가 진행을 되감지 않는다", () => {
  const scale = drill("scale");
  const restored: DrillSignals = {
    cap: { ...cap, plan: { ...cap.plan, desired: 2 }, observedTotal: 2, readAvgCpuRatio: 0.1, advice: [] },
    sim: { ...sim, rate: 10, currentOps: 10 },
    ha: null,
    rec: null,
  };
  // 사용률 하락까지 부하가 있는 동안 기록됐다. 그 뒤 부하를 되돌려 앞 단계들은 지금 미충족이다
  const run: DrillRun = {
    since: T(0),
    steps: Object.fromEntries(scale.steps.slice(0, 7).map((s, i) => [s.id, { at: T(i + 1), seen: FULL }])),
  };
  const ev = evaluateDrill(scale, restored, run, T(9));
  assert.equal(ev.currentIndex, null, "기록이 있으면 마지막 단계까지 열린다");
  assert.equal(ev.position, scale.steps.length);
});

/* ===== 주행 기록 규칙 ===== */

/** 증설 대본의 유휴 상태. 기준 대수만 맞고 부하는 아직이다. 축소 완료 조건은 이미 참이다 */
const IDLE: DrillSignals = {
  cap: { ...cap, plan: { ...cap.plan, desired: 2 }, observedTotal: 2, readAvgCpuRatio: 0.1, advice: [] },
  sim: { ...sim, rate: 10, currentOps: 10 },
  ha: null,
  rec: null,
};

test("주행 기록은 게이트를 지난 단계에만 생긴다", () => {
  const scale = drill("scale");
  const ev = evaluateDrill(scale, IDLE, { since: T(0), steps: {} }, T(1));
  // 축소 완료는 지금 참이지만 앞 단계가 막혀 있어 기록하지 않는다
  assert.equal(ev.raw[scale.steps.findIndex((s) => s.id === "restore")].state, "MET");
  assert.deepEqual(Object.keys(ev.fresh), ["baseline"]);
  assert.equal(ev.fresh.baseline.at, T(1));
});

test("주행 시작 전에 요청한 관측은 기록하지 않는다", () => {
  const scale = drill("scale");
  const ev = evaluateDrill(scale, IDLE, { since: T(5), steps: {} }, T(4));
  assert.deepEqual(ev.fresh, {});
  assert.deepEqual(ev.metAt, {});
  // 기록은 안 해도 지금 값의 판정은 그대로 보인다
  assert.equal(ev.checks[0].state, "MET");
});

test("주행 밖에서는 아무것도 기록하지 않는다", () => {
  const ev = evaluateDrill(drill("scale"), IDLE, null, T(1));
  assert.deepEqual(ev.fresh, {});
});

test("주행 시작보다 앞선 기록은 지남으로 치지 않는다", () => {
  const scale = drill("scale");
  const run: DrillRun = { since: T(5), steps: { baseline: { at: T(1), seen: IDLE }, load: { at: T(2), seen: FULL } } };
  const ev = evaluateDrill(scale, IDLE, run, T(6));
  assert.equal(ev.currentIndex, 1, "옛 주행의 발행률 기록으로 앞 단계를 건너뛰면 안 된다");
  assert.deepEqual(Object.keys(ev.fresh), ["baseline"]);
});

test("앞 단계 기록보다 먼저 찍힌 기록은 지남으로 치지 않는다", () => {
  const scale = drill("scale");
  // 축소 기록이 주행 첫 표본에 찍혔고 나머지는 차례로 지났다. 지금은 아직 3대다
  const order = ["baseline", "load", "readload", "breach", "desired", "joined", "spread"];
  const steps: DrillRun["steps"] = Object.fromEntries(order.map((id, i) => [id, { at: T(i + 1), seen: FULL }]));
  steps.spread = { at: T(7), seen: { ...FULL, cap: { ...cap, readAvgCpuRatio: 0.4 } } };
  steps.restore = { at: T(1), seen: IDLE };
  const now: DrillSignals = { cap: { ...cap, advice: [] }, sim, ha: null, rec: null };
  const ev = evaluateDrill(scale, now, { since: T(0), steps }, T(9));
  assert.equal(ev.currentIndex, scale.steps.findIndex((s) => s.id === "restore"));
  assert.equal(ev.metAt.restore, undefined, "순서를 어긴 기록은 도장도 찍지 않는다");
});

test("단계를 지난 표본의 값이 뒤 단계의 기준으로 함께 기록된다", () => {
  const leader = drill("leader");
  const ev = evaluateDrill(leader, FULL, { since: T(0), steps: {} }, T(1));
  assert.deepEqual(Object.keys(ev.fresh), ["before"]);
  assert.equal(ev.fresh.before.seen, FULL);
  assert.equal(ev.currentIndex, 1);
});

/* ===== 증설 대본 ===== */

/** 증설 전 조회 노드 두 대. 둘 다 조회를 받고 사용률이 읽힌다 */
const TWO_READERS: CapacityNode[] = [node("chaos-api-1", { leader: false }), node("chaos-api-2", { leader: true })];
/** 증설 노드가 붙어 조회를 받는 세 대 */
const THREE_READERS: CapacityNode[] = [
  ...TWO_READERS,
  node("chaos-api-3", { leader: false, engine: false, scalable: true }),
];

/** 증설 권고가 처음 뜬 표본. 두 대가 평균 58%다 */
const BREACH: DrillSignals = {
  ...FULL,
  cap: { ...cap, plan: { ...cap.plan, desired: 2 }, observedTotal: 2, nodes: TWO_READERS, readAvgCpuRatio: 0.58 },
};

const AT_MAX: CapacityState = {
  ...cap,
  nodes: THREE_READERS,
  advice: [{ id: "at_max", level: "warn", headline: "조회 부하 높음. 증설 불가", detail: "", action: null }],
};

test("최대 대수에서 사용률이 증설 전보다 내려가지 않으면 분산이 아니다", () => {
  const spread = step(drill("scale"), "spread");
  const seen = { breach: BREACH };
  const now: DrillSignals = { ...FULL, cap: { ...AT_MAX, readAvgCpuRatio: 0.72 } };
  // 권고가 at_max로 바뀌어 증설 권고가 사라졌어도 사용률은 올랐다
  assert.equal(spread.check(now, seen).state, "PENDING");
  assert.equal(spread.check({ ...now, cap: { ...AT_MAX, readAvgCpuRatio: 0.58 } }, seen).state, "PENDING");
  const dropped = spread.check({ ...now, cap: { ...AT_MAX, readAvgCpuRatio: 0.49 } }, seen);
  assert.equal(dropped.state, "MET");
  assert.equal(dropped.readout, "기준 58% → 평균 49% · 조회 노드 2대 → 3대");
});

test("부하가 끝나 내려간 사용률은 분산이 아니다", () => {
  const spread = step(drill("scale"), "spread");
  const seen = { breach: BREACH };
  const scaleIn = [{ id: "scale_in", level: "info" as const, headline: "조회 부하 낮음", detail: "", action: "SCALE_IN" as const }];
  // 조회 부하 주입이 끝났다. 세 대가 조회를 받지만 사용률은 축소 임계 아래다
  const ended = spread.check({ ...FULL, cap: { ...cap, nodes: THREE_READERS, readAvgCpuRatio: 0.05, advice: scaleIn } }, seen);
  assert.equal(ended.state, "PENDING");
  assert.equal(ended.readout, "기준 58% → 평균 5% · 유휴");
  // 검토에서 재현한 표본. 증설 노드가 아직 조회를 받지 않는데 부하도 끝났다
  const notJoined = [...TWO_READERS, node("chaos-api-3", { leader: false, servesReads: false })];
  assert.equal(
    spread.check({ ...FULL, cap: { ...cap, nodes: notJoined, readAvgCpuRatio: 0.05, advice: scaleIn } }, seen).state,
    "PENDING",
  );
  // 축소 임계와 같은 값도 부하로 치지 않는다. 조회 부하 단계와 같은 선이다
  assert.equal(spread.check({ ...FULL, cap: { ...cap, nodes: THREE_READERS, readAvgCpuRatio: 0.3 } }, seen).state, "PENDING");
});

test("조회 노드가 늘지 않은 하락은 분산이 아니다", () => {
  const spread = step(drill("scale"), "spread");
  const seen = { breach: BREACH };
  // 같은 두 대의 사용률만 내려갔다. 재기동 직후 워밍이 끝나면 부하가 같아도 이렇게 내려간다
  const warmed = spread.check({ ...FULL, cap: { ...cap, nodes: TWO_READERS, readAvgCpuRatio: 0.4 } }, seen);
  assert.equal(warmed.state, "PENDING");
  assert.equal(warmed.readout, "기준 58% → 평균 40% · 조회 노드 2대 → 2대");
  // 증설 노드가 떴어도 조회를 받지 않거나 사용률이 안 읽히면 나눠 받은 것이 아니다
  for (const over of [{ servesReads: false }, { cpuRatio: null }, { reachable: false }]) {
    const nodes = [...TWO_READERS, node("chaos-api-3", { leader: false, ...over })];
    assert.equal(spread.check({ ...FULL, cap: { ...cap, nodes, readAvgCpuRatio: 0.4 } }, seen).state, "PENDING");
  }
  // 한 대가 빠지고 한 대가 들어온 것은 교체이지 분산이 아니다
  const swapped = [TWO_READERS[0], THREE_READERS[2]];
  assert.equal(spread.check({ ...FULL, cap: { ...cap, nodes: swapped, readAvgCpuRatio: 0.4 } }, seen).state, "PENDING");
  // 늘린 노드가 조회를 받고 부하가 남은 채 내려가야 충족이다
  assert.equal(spread.check({ ...FULL, cap: { ...cap, nodes: THREE_READERS, readAvgCpuRatio: 0.4 } }, seen).state, "MET");
});

test("증설 전 기준이 없으면 사용률 하락을 판정하지 않는다", () => {
  const spread = step(drill("scale"), "spread");
  assert.equal(spread.check({ ...FULL, cap: { ...AT_MAX, readAvgCpuRatio: 0.1 } }, {}).state, "PENDING");
});

test("증설 대본은 조절기 정지로 끝나고 그 단계는 판정하지 않는다", () => {
  const scale = drill("scale");
  const last = scale.steps[scale.steps.length - 1];
  assert.equal(last.id, "reconciler");
  assert.match(last.title, /조절기/);
  // 콘솔은 조절기 프로세스를 읽지 않는다. 지어낸 충족을 내지 않고 진행도 막지 않는다
  assert.equal(last.check(FULL, seenAll(scale, FULL)).state, "NO_SOURCE");
  // 리더 전환 대본도 조절기가 멈춰 있어야 한다는 전제를 든다
  assert.ok(drill("leader").prep?.some((p) => p.text.includes("조절기")));
});

/* ===== 리더 전환 대본 ===== */

/** 리더 chaos-api-2, 팔로워 chaos-api-1, 세대 7 */
const BEFORE: DrillSignals = {
  cap: {
    ...cap,
    plan: { ...cap.plan, desired: 2 },
    observedTotal: 2,
    nodes: [node("chaos-api-1", { leader: false }), node("chaos-api-2", { leader: true })],
  },
  sim: null,
  ha: { ...ha, epoch: 7 },
  rec,
};

function leaderAfter(nodes: CapacityNode[], epoch: number): DrillSignals {
  return { ...BEFORE, cap: { ...BEFORE.cap!, nodes }, ha: { ...ha, epoch } };
}

test("팔로워만 멈추면 리더 중단이 아니다", () => {
  const kill = step(drill("leader"), "kill");
  const followerDown = leaderAfter(
    [node("chaos-api-1", { leader: false, reachable: false }), node("chaos-api-2", { leader: true })],
    7,
  );
  assert.equal(kill.check(followerDown, { before: BEFORE }).state, "PENDING");
  const leaderDown = leaderAfter(
    [node("chaos-api-1", { leader: false }), node("chaos-api-2", { leader: false, reachable: false })],
    7,
  );
  assert.deepEqual(kill.check(leaderDown, { before: BEFORE }), { state: "MET", readout: "chaos-api-2 미응답" });
});

test("세대가 오르지 않으면 새 리더 선출이 아니다", () => {
  const promote = step(drill("leader"), "promote");
  const nodes = [node("chaos-api-1", { leader: true }), node("chaos-api-2", { leader: false, reachable: false })];
  assert.equal(promote.check(leaderAfter(nodes, 7), { before: BEFORE }).state, "PENDING");
  assert.deepEqual(promote.check(leaderAfter(nodes, 8), { before: BEFORE }), {
    state: "MET",
    readout: "리더 chaos-api-1 · 세대 7 → 8",
  });
  // 기준 세대가 없으면 오른 것인지 알 수 없다
  assert.equal(promote.check(leaderAfter(nodes, 8), {}).state, "PENDING");
});

test("복구 판정은 주행 창으로 좁힌 판정만 받는다", () => {
  const verdict = step(drill("leader"), "verdict");
  assert.equal(verdict.check({ ...FULL, rec: { ...rec, verdictScope: "all" } }, {}).state, "PENDING");
  assert.equal(verdict.check({ ...FULL, rec: { ...rec, verdictScope: "since" } }, {}).state, "MET");
});

test("옛 리더 복귀 명령은 첫 단계에서 관측한 리더 이름으로 만든다", () => {
  const leader = drill("leader");
  const rejoin = step(leader, "rejoin");
  assert.equal(typeof rejoin.command, "function");
  const ev = evaluateDrill(leader, BEFORE, { since: T(0), steps: {} }, T(1));
  assert.equal(ev.commands[leader.steps.indexOf(rejoin)], "docker start chaos-api-2");
  // 리더를 관측하지 못했으면 이름을 지어내지 않는다
  const unknown = evaluateDrill(leader, { ...BEFORE, cap: null }, null, null);
  assert.equal(unknown.commands[leader.steps.indexOf(rejoin)], null);
});

test("옛 리더가 팔로워로 돌아오고 세대가 그대로여야 복귀다", () => {
  const rejoin = step(drill("leader"), "rejoin");
  const promoted = leaderAfter(
    [node("chaos-api-1", { leader: true }), node("chaos-api-2", { leader: false, reachable: false })],
    8,
  );
  const seen = { before: BEFORE, promote: promoted };
  const back = leaderAfter([node("chaos-api-1", { leader: true }), node("chaos-api-2", { leader: false })], 8);
  assert.equal(rejoin.check(back, seen).state, "MET");
  const retook = leaderAfter([node("chaos-api-1", { leader: false }), node("chaos-api-2", { leader: true })], 9);
  assert.equal(rejoin.check(retook, seen).state, "PENDING", "옛 리더가 리스를 되찾으면 복귀가 아니다");
  const reelected = leaderAfter([node("chaos-api-1", { leader: true }), node("chaos-api-2", { leader: false })], 9);
  assert.equal(rejoin.check(reelected, seen).state, "PENDING", "세대가 또 오르면 선출이 두 번 일어났다");
});

test("앞 단계 기록보다 앞선 관측은 기록하지 않아 기록 갱신이 끝난다", () => {
  const leader = drill("leader");
  // 현재 리더 확인은 T5에 기록됐다. 벽시계가 뒤로 조정돼 T4에 요청한 관측이 리더 중단을 보였다
  const leaderDown: DrillSignals = {
    ...BEFORE,
    cap: {
      ...BEFORE.cap!,
      nodes: [node("chaos-api-1", { leader: false }), node("chaos-api-2", { leader: false, reachable: false })],
    },
  };
  let run: DrillRun = { since: T(0), steps: { before: { at: T(5), seen: BEFORE } } };
  const first = evaluateDrill(leader, leaderDown, run, T(4));
  assert.deepEqual(first.fresh, {}, "앞 단계보다 이른 시각으로 적으면 다음 판정이 그 기록을 버린다");
  assert.equal(first.metAt.kill, undefined);
  // 라이브 판정은 그대로 보이고 진행도 막지 않는다
  assert.equal(first.checks[leader.steps.findIndex((s) => s.id === "kill")].state, "MET");
  // 화면처럼 새 기록을 병합하며 되풀이해도 기록이 멈춘다
  let rounds = 0;
  for (; rounds < 5; rounds += 1) {
    const ev = evaluateDrill(leader, leaderDown, run, T(4));
    if (Object.keys(ev.fresh).length === 0) break;
    run = { ...run, steps: { ...run.steps, ...ev.fresh } };
  }
  assert.equal(rounds, 0);
  // 앞 단계 기록 뒤의 관측이면 기록한다
  const later = evaluateDrill(leader, leaderDown, run, T(6));
  assert.deepEqual(Object.keys(later.fresh), ["kill"]);
  assert.equal(later.fresh.kill.at, T(6));
});

test("대본은 복구 판정을 주행 시작 이후로 좁혀 읽는다", async () => {
  const original = globalThis.fetch;
  const urls: string[] = [];
  globalThis.fetch = (async (url: string) => {
    urls.push(String(url));
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => ({ schema_version: "1.2", verdict: { scope: "since" }, recovery_runs: [], failover_runs: [] }),
    } as unknown as Response;
  }) as typeof fetch;
  try {
    const api = new HttpAdminApi("");
    const r = await api.getRecovery(T(3));
    assert.equal(r.verdictScope, "since");
    await api.getRecovery();
  } finally {
    globalThis.fetch = original;
  }
  assert.equal(urls[0], `/admin/v1/ha/recovery?since=${encodeURIComponent(T(3))}`);
  assert.equal(urls[1], "/admin/v1/ha/recovery");
});

/* ===== 디스크·메모리 대본 ===== */

test("디스크·메모리 단계는 환경과 무관하게 원천 없음이다", () => {
  const pressure = drill("pressure");
  for (const id of ["disk", "memory"]) {
    const c = step(pressure, id).check(FULL, seenAll(pressure, FULL));
    assert.equal(c.state, "NO_SOURCE");
    assert.doesNotMatch(c.readout, /로컬/, "로컬만의 사정처럼 적으면 다른 환경에서는 판정되는 것으로 읽힌다");
  }
});

test("단계 id와 대본 id가 유일하다", () => {
  const ids = DRILLS.map((d) => d.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const def of DRILLS) {
    const stepIds = def.steps.map((s) => s.id);
    assert.equal(new Set(stepIds).size, stepIds.length, `${def.id} 단계 id 중복`);
  }
});

test("제목과 한 줄 설명이 명사형이다", () => {
  // 디자인시스템 §9. 관리 평면도 예외가 아니다. 여러 문장으로 이유를 적는 detail·claim은
  // 대상이 아니다. 그쪽까지 접으면 이유를 적을 자리가 사라진다
  const verbEnding = /(다|요|니다|세요)[.]?$/;
  for (const def of DRILLS) {
    assert.ok(!verbEnding.test(def.title), `${def.id} 대본 제목이 서술형이다`);
    assert.ok(!verbEnding.test(def.lead), `${def.id} 대본 한 줄 설명이 서술형이다`);
    for (const p of def.prep ?? []) {
      assert.ok(!verbEnding.test(p.text), `${def.id} 사전 조건이 서술형이다: ${p.text}`);
    }
    for (const step of def.steps) {
      assert.ok(!verbEnding.test(step.title), `${def.id}/${step.id} 단계 제목이 서술형이다`);
    }
  }
});

test("사전 조건의 명령은 콘솔이 실행하지 않는 것만 둔다", () => {
  // 조작은 버튼으로, 호스트 일은 명령으로 가른다. 한 줄이 둘을 겸하면 무엇이 실행되는지 갈린다
  for (const def of DRILLS) {
    for (const p of def.prep ?? []) {
      if (p.command === undefined) continue;
      assert.ok(p.command.trim().length > 0, `${def.id} 사전 조건 명령이 비었다`);
      assert.ok(!/^\s/.test(p.command), `${def.id} 사전 조건 명령에 앞 공백이 있다`);
    }
  }
});

test("콘솔 조작은 관리 API에 있는 것만 둔다", () => {
  const allowed = new Set(["OPS", "SCALE_OUT", "SCALE_IN"]);
  for (const def of DRILLS) {
    for (const step of def.steps) {
      if (!step.act) continue;
      assert.ok(allowed.has(step.act.kind), `${def.id}/${step.id} 가 없는 조작을 건다`);
      // 조작 단계를 명령과 함께 두면 어느 쪽이 실제로 실행되는지 화면에서 갈리지 않는다
      assert.equal(step.command, undefined, `${def.id}/${step.id} 가 조작과 명령을 함께 든다`);
    }
  }
});
