/**
 * 국면 프리셋 계약 검증(API §6-A). 카탈로그가 정본 배수표와 같은지, sim이 기준선 배수·멱등·복귀를
 * 지키는지, http가 와이어를 뷰모델로 옮기고 미반영을 삼키지 않는지 본다.
 * 서버 SimScenarioTest와 같은 값을 단정해 한쪽만 바뀌면 여기서 갈라진다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";
import { SCENARIO_CATALOG, scenarioDef } from "../lib/admin/catalog";
import type { ScenarioMarketResult, ScenarioName } from "../lib/admin/types";

const CANONICAL: Record<ScenarioName, [number, number, number, number, number, number, string, boolean, string]> = {
  // [opsMult, lambdaMult, MM, TAKER, TREND, NOISE, targetBias, autoBalance, strength]
  steady: [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, "0.00", true, "0.5"],
  volatile: [1.5, 1.5, 0.6, 1.3, 1.5, 1.2, "0.00", true, "0.15"],
  ramp: [2.0, 2.0, 1.0, 1.4, 2.0, 1.0, "0.35", true, "0.3"],
  spike: [10.0, 10.0, 1.0, 1.0, 1.0, 1.0, "0.60", false, "0.5"],
  // spike 의 방향 대칭. 부하 축은 같고 부호만 반대다
  selloff: [10.0, 10.0, 1.0, 1.0, 1.0, 1.0, "-0.60", false, "0.5"],
  "thundering-herd": [12.0, 12.0, 1.0, 1.0, 1.0, 1.0, "0.70", false, "0.5"],
};

async function botOps(): Promise<SimAdminApi> {
  const api = new SimAdminApi();
  await api.establishSession("op-02");
  return api;
}

// markets는 queued 경로에서 null이다(관리 평면이 마켓별 결과를 모른다). 목 어댑터는
// 항상 local이라 null이 나오면 그 자체가 회귀다. 단언으로 좁힌다.
function marketRow(markets: ScenarioMarketResult[] | null, market: string): ScenarioMarketResult {
  assert.ok(markets, "markets가 null이다. 목 어댑터는 local 경로라 값이 있어야 한다");
  const found = markets.find((m) => m.market === market);
  assert.ok(found, `마켓 행 없음: ${market}`);
  return found;
}

test("카탈로그가 정본 배수표와 일치한다", () => {
  assert.equal(SCENARIO_CATALOG.length, 6);
  for (const def of SCENARIO_CATALOG) {
    const want = CANONICAL[def.name];
    assert.ok(want, `정본에 없는 프리셋: ${def.name}`);
    assert.equal(def.opsMult, want[0], `${def.name} OPS`);
    assert.equal(def.lambdaMult, want[1], `${def.name} TAKER λ`);
    assert.equal(def.scale.MARKET_MAKER, want[2], `${def.name} MARKET_MAKER`);
    assert.equal(def.scale.TAKER, want[3], `${def.name} TAKER`);
    assert.equal(def.scale.TREND, want[4], `${def.name} TREND`);
    assert.equal(def.scale.NOISE, want[5], `${def.name} NOISE`);
    assert.equal(def.targetBias, want[6], `${def.name} target_net_bias`);
    assert.equal(def.autoBalance, want[7], `${def.name} auto_balance`);
    assert.equal(def.strength, want[8], `${def.name} strength`);
  }
});

test("부하 국면은 대수를 건드리지 않는다", () => {
  // 하네스 §2-1이 spike를 동일 플릿, thundering-herd를 전 봇으로 규정한다
  for (const name of ["spike", "thundering-herd"] as ScenarioName[]) {
    const def = scenarioDef(name);
    for (const v of Object.values(def.scale)) assert.equal(v, 1.0, `${name} 대수 배수`);
    assert.ok(def.opsMult >= 10, `${name} 크기는 전역 OPS 축이 준다`);
  }
});

test("도착률 배수는 OPS 배수를 따라간다", () => {
  // 상한과 수요를 함께 올려야 어느 한쪽이 무의미해지지 않는다
  for (const def of SCENARIO_CATALOG) {
    assert.equal(def.lambdaMult, def.opsMult, `${def.name} λ와 OPS 배수`);
  }
});

test("알 수 없는 프리셋은 거부한다", () => {
  assert.throws(() => scenarioDef("meltdown" as ScenarioName), /알 수 없는/);
});

test("대수는 시드 기준선에 배수를 곱한 값이다", async () => {
  const api = await botOps();
  const r = await api.activateScenario("volatile");
  // 시드 KRW-BTC는 MM 4 · TAKER 3. volatile은 MM 0.6 / TAKER 1.3
  const btc = marketRow(r.markets, "KRW-BTC");
  assert.equal(btc.scaleApplied, true);
  assert.equal(btc.applied.MARKET_MAKER, 2);
  assert.equal(btc.applied.TAKER, 4);
});

test("같은 프리셋 재활성화는 누적되지 않는다", async () => {
  const api = await botOps();
  const once = await api.activateScenario("volatile");
  await api.activateScenario("volatile");
  const thrice = await api.activateScenario("volatile");
  assert.deepEqual(marketRow(thrice.markets, "KRW-BTC"), marketRow(once.markets, "KRW-BTC"));
  assert.equal(thrice.targetOps, once.targetOps);
});

test("평상은 기준선 복귀 경로다", async () => {
  const api = await botOps();
  const before = await api.getSimStatus();
  await api.activateScenario("thundering-herd");
  const back = await api.activateScenario("steady");

  assert.equal(Number(back.targetOps), Number(back.baseOps));
  assert.equal(Number(back.targetOps), before.rate);
  const btc = marketRow(back.markets, "KRW-BTC");
  assert.equal(btc.applied.MARKET_MAKER, 4);
  assert.equal(btc.applied.TAKER, 3);
});

test("OPS는 기준선 배수이고 봇 대수와 무관하다", async () => {
  const api = await botOps();
  const base = (await api.getSimStatus()).rate;
  const spike = await api.activateScenario("spike");
  assert.equal(Number(spike.targetOps), base * 10);
  // spike는 대수를 건드리지 않으므로 시드 대수가 그대로다
  const btc = marketRow(spike.markets, "KRW-BTC");
  assert.equal(btc.applied.MARKET_MAKER, 4);
  assert.equal((await api.getSimStatus()).rate, base * 10);
});

test("기준선 없는 마켓은 대수를 건드리지 않는다", async () => {
  const api = await botOps();
  // 시드에 없는 마켓. setFleetScale이 상태만 만들고 기준선은 잡히지 않는다
  await api.setFleetScale("KRW-DOGE", { TAKER: 5 });
  const r = await api.activateScenario("volatile");
  const doge = marketRow(r.markets, "KRW-DOGE");
  assert.equal(doge.scaleApplied, false);
  assert.equal(doge.applied.TAKER, 5);
});

test("auto off 국면은 방향을 즉시 싣는다", async () => {
  const api = await botOps();
  const r = await api.activateScenario("spike");
  assert.equal(r.autoBalance, false);
  assert.equal(r.targetNetBias, "0.60");
  assert.equal(marketRow(r.markets, "KRW-BTC").effectiveBias, "0.60");
});

test("프리셋 적용은 감사에 남는다", async () => {
  const api = await botOps();
  await api.activateScenario("ramp");
  const audit = await api.getAudit({ limit: 20 });
  assert.ok(audit.data.some((a) => a.action === "sim.scenario" && a.target === "ramp"));
});

test("권한 없는 세션은 프리셋을 적용하지 못한다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  api.endSession();
  // auth는 다른 sim 제어와 같이 동기로 던진다. 화면은 await 안에서 받으므로 같은 경로다
  await assert.rejects(async () => api.activateScenario("steady"), /세션 없음/);
});

/* ===== http 어댑터 ===== */

function withFetch(res: unknown, run: () => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  globalThis.fetch = (async () => res) as typeof fetch;
  return run().finally(() => {
    globalThis.fetch = prev;
  });
}

function okRes(body: unknown) {
  return { ok: true, status: 200, headers: { get: () => null }, json: async () => body } as unknown as Response;
}

test("http 어댑터가 와이어를 뷰모델로 옮긴다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    okRes({
      schema_version: "1.0",
      scenario: "spike",
      target_ops: "300.0",
      base_ops: "30.0",
      auto_balance: false,
      target_net_bias: "0.60",
      markets: [{ market: "KRW-BTC", scale_applied: true, applied: { TAKER: 30 }, effective_bias: "0.60" }],
      clamped: false,
      fleet_present: true,
    }),
    async () => {
      const r = await api.activateScenario("spike");
      assert.equal(r.scenario, "spike");
      assert.equal(r.targetOps, "300.0");
      assert.equal(r.baseOps, "30.0");
      assert.equal(r.autoBalance, false);
      assert.equal(r.applyPath, "local");
      assert.ok(r.markets, "local 경로는 마켓 결과를 낸다");
      assert.equal(r.markets[0].market, "KRW-BTC");
      assert.equal(r.markets[0].scaleApplied, true);
      assert.equal(r.markets[0].applied.TAKER, 30);
      assert.equal(r.fleetPresent, true);
    },
  );
});

test("플릿 부재 응답은 성공으로 삼키지 않는다", async () => {
  const api = new HttpAdminApi("");
  // apply_path가 없는 응답은 큐 경로가 없던 구 서버다. 그때는 fleet_present:false가
  // 곧 미적용이라 예외가 맞다.
  await withFetch(okRes({ scenario: "steady", fleet_present: false }), async () => {
    await assert.rejects(
      api.activateScenario("steady"),
      (e: unknown) => e instanceof AdminError && e.code === "control_not_applied",
    );
  });
});

test("queued 응답은 실패가 아니다", async () => {
  const api = new HttpAdminApi("");
  // 관리 평면과 봇 JVM이 물리 분리된 배치의 정상 응답이다. 서버가 sim_commands에 의도를 적재하고
  // 봇이 1초 안에 집어 적용한다(API §6-A). fleet_present:false만 보고 예외를 던지면 적용된 조작이 오류로 보인다.
  await withFetch(
    okRes({
      schema_version: "1.1",
      scenario: "spike",
      apply_path: "queued",
      queued_command_id: 7,
      auto_balance: false,
      target_net_bias: "0.60",
      fleet_present: false,
    }),
    async () => {
      const r = await api.activateScenario("spike");
      assert.equal(r.applyPath, "queued");
      assert.equal(r.queuedCommandId, 7);
      // 서버가 안 낸 값을 0이나 빈 배열로 채우지 않는다. 채우면 화면이 거짓을 단정한다.
      assert.equal(r.targetOps, null);
      assert.equal(r.baseOps, null);
      assert.equal(r.markets, null);
      assert.equal(r.clamped, null);
      // 프리셋 정의값은 거버너 없이도 정확하다.
      assert.equal(r.autoBalance, false);
      assert.equal(r.targetNetBias, "0.60");
    },
  );
});
