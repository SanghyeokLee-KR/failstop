/**
 * 시연 대본. 이 파일이 지키는 것은 하나다. 화면이 일어나지 않은 일을 일어났다고 말하지 않는다.
 *
 * 세 축으로 고정한다.
 *
 * 1. 값을 못 읽었으면 어떤 단계도 충족이 아니다. 종전 관제 화면이 빈 표를 통과로 칠한
 *    전례가 있어 같은 경로를 여기서 미리 닫는다.
 * 2. needs 선언과 실제 사용이 일치한다. 선언에서 빠진 축은 화면이 부르지 않으므로,
 *    그 축을 읽는 단계는 영원히 미수집으로 굳는다. 빌드도 타입검사도 잡지 못한다.
 * 3. 제목이 명사형이다. 디자인시스템이 관리 평면을 예외로 두지 않는다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { DRILLS, evaluateDrill, type DrillSignalKey, type DrillSignals } from "../lib/admin/drills";
import type { CapacityState, HaRecovery, HaStatus, SimStatus } from "../lib/admin/types";

const EMPTY: DrillSignals = { cap: null, sim: null, ha: null, rec: null };

const cap: CapacityState = {
  tier: "app",
  plan: { min: 2, desired: 3, max: 3, updatedBy: "op-01", updatedAt: "2026-09-23T00:00:00Z" },
  observedTotal: 3,
  reconciled: true,
  readAvgCpuRatio: 0.71,
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
  verdictScope: "all",
  replayVerdict: "PASS",
  failoverVerdict: "PASS",
};

const FULL: DrillSignals = { cap, sim, ha, rec };

/** 선언한 축만 채운 신호. 선언 누락이면 이 신호에서 판정이 달라진다 */
function onlyDeclared(needs: DrillSignalKey[]): DrillSignals {
  const pick = <K extends DrillSignalKey>(k: K) => (needs.includes(k) ? FULL[k] : null);
  return { cap: pick("cap"), sim: pick("sim"), ha: pick("ha"), rec: pick("rec") };
}

test("값을 못 읽으면 어떤 단계도 충족이 아니다", () => {
  for (const def of DRILLS) {
    for (const step of def.steps) {
      const c = step.check(EMPTY);
      assert.notEqual(c.state, "MET", `${def.id}/${step.id} 가 빈 신호에서 충족을 냈다`);
    }
  }
});

test("needs 선언과 실제 사용이 일치한다", () => {
  for (const def of DRILLS) {
    const partial = onlyDeclared(def.needs);
    def.steps.forEach((step, i) => {
      const a = step.check(partial);
      const b = step.check(FULL);
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
    assert.equal(ev.checks[i].state, "BLOCKED", `${scale.steps[i].id} 가 앞질러 판정됐다`);
  }
  // 라이브 판정 자체는 남겨 둔다. 화면은 안 쓰지만 진단에서 필요하다
  assert.notEqual(ev.raw[ev.raw.length - 1].state, "BLOCKED");
});

test("이번 주행 기록이 있으면 되돌아간 단계가 진행을 되감지 않는다", () => {
  const scale = DRILLS.find((d) => d.id === "scale");
  assert.ok(scale);
  const restored: DrillSignals = {
    cap: { ...cap, plan: { ...cap.plan, desired: 2 }, observedTotal: 2, readAvgCpuRatio: 0.1, advice: [] },
    sim: { ...sim, rate: 10, currentOps: 10 },
    ha: null,
    rec: null,
  };
  const recorded = Object.fromEntries(
    scale.steps.slice(0, 6).map((s) => [s.id, "2026-09-23T00:00:00Z"]),
  );
  const ev = evaluateDrill(scale, restored, recorded);
  assert.equal(ev.currentIndex, null, "기록이 있으면 마지막 단계까지 열린다");
  assert.equal(ev.position, scale.steps.length);
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
      assert.ok(!verbEnding.test(p), `${def.id} 사전 조건이 서술형이다: ${p}`);
    }
    for (const step of def.steps) {
      assert.ok(!verbEnding.test(step.title), `${def.id}/${step.id} 단계 제목이 서술형이다`);
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
