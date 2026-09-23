/**
 * tests/httpAdmin.test.ts. HttpAdminApi 에러 매핑 단위 검증.
 * 관리 평면 429(too_many_requests) 응답을 명시 매핑하는지 확인한다(감사 rank10).
 * 실제 네트워크 없이 global fetch를 스텁한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { AdminError } from "../lib/admin/api";
import { aggregateVerdict } from "../lib/admin/simAdmin";
import { SCHEDULED_INVARIANTS } from "../lib/admin/invariants";
import type { ApprovalPayload, IssuedApproval, RecoveryRun } from "../lib/admin/types";

interface FakeResInit {
  status: number;
  body?: unknown;
  retryAfterHeader?: string | null;
}

function fakeRes(init: FakeResInit): Response {
  const headers = { get: (name: string) => (name === "Retry-After" ? init.retryAfterHeader ?? null : null) };
  return {
    ok: init.status >= 200 && init.status < 300,
    status: init.status,
    headers,
    json: async () => {
      if (init.body === undefined) throw new SyntaxError("non-json");
      return init.body;
    },
  } as unknown as Response;
}

function withFetch(res: Response, run: () => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  globalThis.fetch = (async () => res) as typeof fetch;
  return run().finally(() => {
    globalThis.fetch = prev;
  });
}

async function expectAdminError(run: () => Promise<unknown>): Promise<AdminError> {
  try {
    await run();
  } catch (e) {
    assert.ok(e instanceof AdminError, "AdminError로 표면화");
    return e;
  }
  throw new assert.AssertionError({ message: "AdminError가 던져지지 않았다" });
}

test("관리 429: 바디 retry_after를 too_many_requests로 매핑하고 대기 초를 안내한다", async () => {
  const res = fakeRes({ status: 429, body: { error: { name: "too_many_requests", retry_after: 7 } } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStart());
    assert.equal(e.code, "too_many_requests");
    assert.match(e.message, /7초 후/);
  });
});

test("관리 429: 바디 retry_after 부재 시 Retry-After 헤더 초를 안내한다", async () => {
  const res = fakeRes({ status: 429, body: { error: { name: "too_many_requests" } }, retryAfterHeader: "5" });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStop());
    assert.equal(e.code, "too_many_requests");
    assert.match(e.message, /5초 후/);
  });
});

test("관리 429: 대기 초 미상이면 잠시 후로 열화한다", async () => {
  const res = fakeRes({ status: 429 });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simPause());
    assert.equal(e.code, "too_many_requests");
    assert.match(e.message, /잠시 후/);
  });
});

test("control 1.1: applied false는 미반영 에러로 승격한다(성공 응답 삼킴 방지)", async () => {
  const res = fakeRes({ status: 200, body: { schema_version: "1.1", command: "start", applied: false } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStart());
    assert.equal(e.code, "control_not_applied");
  });
});

test("control 1.0: applied 부재는 구 서버 하위호환으로 통과한다", async () => {
  const res = fakeRes({ status: 200, body: { schema_version: "1.0", command: "start" } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    await api.simStart();
  });
});

test("관리 403: forbidden 코드로 매핑하고 429 안내를 붙이지 않는다", async () => {
  const res = fakeRes({ status: 403, body: { error: { name: "admin_forbidden", message: "권한이 없습니다" } } });
  await withFetch(res, async () => {
    const api = new HttpAdminApi("http://admin.test");
    const e = await expectAdminError(() => api.simStart());
    assert.equal(e.code, "admin_forbidden");
    assert.doesNotMatch(e.message, /초 후|잠시 후/);
  });
});

/* ===== 승인 바인딩 정합(정본 API §3-6 확정표 v2.13) ===== */
// wireBinding은 모듈 사설이라 공개 표면(발급·소각)의 요청 바디를 fetch 스텁으로 캡처해 대조한다.
// 발급 바디 target으로 wireBinding target을, 소각 바디로 파괴 액션 바디 필드를 검증한다.

interface CapturedCall {
  url: string;
  method: string;
  body: Record<string, unknown> | undefined;
}

function withCapture(run: (calls: CapturedCall[], api: HttpAdminApi) => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  const calls: CapturedCall[] = [];
  globalThis.fetch = (async (input: unknown, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({
      url,
      method: init.method ?? "GET",
      body: init.body != null ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined,
    });
    // 세션·발급 응답만 최소 필드를 채우고 나머지는 빈 객체로 언랩 통과시킨다
    let payload: unknown = {};
    if (url.endsWith("/admin/v1/session")) {
      payload = {
        operator_id: "op-approver",
        display_name: "승인자",
        roles: ["SUPER"],
        token: "sess",
        expires_at: new Date(Date.now() + 3_600_000).toISOString(),
      };
    } else if (url.endsWith("/admin/v1/approvals")) {
      payload = { approval_id: 42, approval_token: "apv-issued", expires_at: new Date(Date.now() + 300_000).toISOString() };
    }
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => payload,
    } as unknown as Response;
  }) as typeof fetch;
  const api = new HttpAdminApi("http://admin.test");
  return run(calls, api).finally(() => {
    globalThis.fetch = prev;
  });
}

function issued(payload: ApprovalPayload, token = "apv-burn"): IssuedApproval {
  return {
    id: "1",
    action: payload.action,
    targetKey: "",
    targetLabel: "",
    requesterId: "",
    requesterName: "",
    approverId: "",
    approverName: "",
    state: "ISSUED",
    issuedAt: "",
    expiresAt: "",
    consumedAt: null,
    payload,
    token,
  };
}

test("발급 바인딩: 자원·전이 스코프 target을 v2.13 표대로 approvals 바디에 싣는다", async () => {
  const cases: { payload: ApprovalPayload; action: string; target: string }[] = [
    { payload: { action: "config.market.halt", symbol: "KRW-BTC" }, action: "config.market.halt", target: "KRW-BTC" },
    { payload: { action: "config.market.fees", symbol: "KRW-BTC", makerFee: "0.0005", takerFee: "0.0005" }, action: "config.market.fees", target: "KRW-BTC" },
    { payload: { action: "sim.bots.params", botId: "bot-17", params: { spread: "0.01" } }, action: "sim.bots.params", target: "bot-17" },
    { payload: { action: "operators.create", operatorId: "op-07", displayName: "신규" }, action: "operators.create", target: "op-07" },
    { payload: { action: "operators.status", operatorId: "op-07", status: "DISABLED" }, action: "operators.status", target: "op-07:DISABLED" },
    { payload: { action: "operators.roles", operatorId: "op-07", grant: "GRANT", role: "SUPER" }, action: "operators.roles", target: "op-07:GRANT:SUPER" },
  ];
  for (const c of cases) {
    await withCapture(async (calls, api) => {
      await api.establishSession();
      await api.issueApproval("req-op", c.payload.action, c.payload);
      const call = calls[calls.length - 1];
      assert.ok(call.url.endsWith("/admin/v1/approvals"), `발급 경로: ${call.url}`);
      assert.equal(call.method, "POST");
      assert.equal(call.body?.action, c.action, `${c.payload.action} 바인딩 action`);
      assert.equal(call.body?.target, c.target, `${c.payload.action} 바인딩 target`);
      assert.equal(call.body?.requester_operator_id, "req-op");
    });
  }
});

test("소각 바디: 파괴 액션별 바디 필드를 v2.13 표대로 싣는다", async () => {
  const cases: { payload: ApprovalPayload; method: string; path: string; fields: Record<string, unknown> }[] = [
    { payload: { action: "operators.create", operatorId: "op-07", displayName: "신규" }, method: "POST", path: "/admin/v1/operators", fields: { new_operator_id: "op-07", display_name: "신규" } },
    { payload: { action: "operators.status", operatorId: "op-07", status: "DISABLED" }, method: "PATCH", path: "/admin/v1/operators/op-07/status", fields: { status: "DISABLED" } },
    { payload: { action: "operators.roles", operatorId: "op-07", grant: "GRANT", role: "SUPER" }, method: "POST", path: "/admin/v1/operators/op-07/roles", fields: { action: "GRANT", role: "SUPER" } },
  ];
  for (const c of cases) {
    await withCapture(async (calls, api) => {
      await api.establishSession();
      await api.consumeApproval(issued(c.payload, "apv-burn"));
      const call = calls[calls.length - 1];
      assert.ok(call.url.endsWith(c.path), `소각 경로: ${call.url} != ${c.path}`);
      assert.equal(call.method, c.method, `${c.payload.action} 메서드`);
      // 공통 필드: 수행자 operator_id(세션 유래)와 approval_token
      assert.equal(call.body?.operator_id, "op-approver");
      assert.equal(call.body?.approval_token, "apv-burn");
      // 표 고유 필드
      for (const [k, v] of Object.entries(c.fields)) {
        assert.deepEqual(call.body?.[k], v, `${c.payload.action} 바디 ${k}`);
      }
    });
  }
});

/**
 * 복구 판정은 서버 verdict 객체가 정본이다(API §6-C 스키마 1.2).
 * 배열은 limit로 자른 창이라, 창 안 최신 1행으로 판정하면 서버가 FAIL인데 화면이 통과를 표시한다.
 */
test("복구 판정은 배열이 아니라 서버 verdict를 따른다", async () => {
  const api = new HttpAdminApi("");
  const original = globalThis.fetch;
  globalThis.fetch = (async () =>
    fakeRes({
      status: 200,
      body: {
        schema_version: "1.2",
        verdict: {
          scope: "all",
          replay_deterministic: "FAIL",
          failover_ok: "PASS",
          overall: "FAIL",
        },
        // 배열에는 통과 행만 보인다. 위반은 창 밖에 있다.
        recovery_runs: [
          {
            replayed_from_seq: 1,
            replayed_to_seq: 2,
            fp_replay: "seq:1-2:ev:5",
            fp_live: "seq:1-2:ev:5",
            matched: true,
            duration_ms: 12,
            created_at: "2026-08-30T00:00:00.000Z",
          },
        ],
        failover_runs: [{ rto_seconds: "3.200", verdict: "PASS", created_at: "2026-08-30T00:00:00.000Z" }],
      },
    })) as typeof fetch;
  try {
    const r = await api.getRecovery();
    assert.equal(r.lastVerdict, "FAIL", "창 밖 위반을 화면이 놓치면 안 된다");
    assert.equal(r.replayVerdict, "FAIL");
    assert.equal(r.failoverVerdict, "PASS");
    assert.equal(r.verdictScope, "all");
    // RTO는 failover_runs가 원천이다. 이 픽스처는 두 배열이 다 차 있으므로 우선순위
    // 역전을 잡는다. 진짜 폴백은 아래 별 케이스가 건다.
    assert.equal(r.lastRtoMs, 3200, "RTO 원천은 failover_runs.rto_seconds");
    // fp를 빈 문자열로 덮으면 재생 0건과 실재 재생을 화면에서 가를 수 없다.
    assert.equal(r.runs[0].fpReplay, "seq:1-2:ev:5");
  } finally {
    globalThis.fetch = original;
  }
});

test("UNKNOWN을 통과로 옮기지 않는다", async () => {
  const api = new HttpAdminApi("");
  const original = globalThis.fetch;
  globalThis.fetch = (async () =>
    fakeRes({
      status: 200,
      body: {
        schema_version: "1.2",
        // 표가 비면 판정 대상이 없다. 0행 대조로 통과를 내면 그것이 공허 통과다.
        verdict: { scope: "since", replay_deterministic: "UNKNOWN", failover_ok: "UNKNOWN", overall: "UNKNOWN" },
        recovery_runs: [],
        failover_runs: [],
      },
    })) as typeof fetch;
  try {
    const r = await api.getRecovery();
    assert.equal(r.lastVerdict, "UNKNOWN");
    assert.equal(r.verdictScope, "since");
    assert.equal(r.runs.length, 0);
  } finally {
    globalThis.fetch = original;
  }
});

/**
 * 목 어댑터의 복구 판정 규칙. 시드가 전건 PASS라 어댑터를 통째로 부르면 FAIL·UNKNOWN
 * 분기가 실행되지 않는다. 규칙을 순수 함수로 뽑아 직접 때린다.
 */
test("복구 판정 집계는 전체를 보고 UNKNOWN을 통과로 치지 않는다", () => {
  const run = (verdict: "PASS" | "FAIL"): RecoveryRun => ({
    id: `r-${verdict}`,
    startedAt: "2026-08-30T00:00:00.000Z",
    durationMs: 1,
    // recovery_runs에 RPO 컬럼이 없다. 이 축은 이 행에서 미측정이다.
    rpoMs: null,
    replayedFromSeq: 1,
    replayedToSeq: 2,
    fpReplay: "",
    fpLive: "",
    matched: verdict === "PASS",
    verdict,
  });
  assert.equal(aggregateVerdict([]), "UNKNOWN", "표가 비면 미판정이다");
  assert.equal(aggregateVerdict([run("PASS"), run("PASS")]), "PASS");
  // 최신 1행만 보면 과거 실패가 사라진다. 순서를 바꿔도 결론이 같아야 한다.
  assert.equal(aggregateVerdict([run("PASS"), run("FAIL")]), "FAIL");
  assert.equal(aggregateVerdict([run("FAIL"), run("PASS")]), "FAIL");
});

/**
 * failover_runs가 비고 recovery_runs만 있는 상태.
 * runs[0].durationMs로 폴백하면 재생 소요가 "최근 복구 시간(RTO)"으로 둔갑한다. 두 축은 다른 것을 잰다.
 */
test("failover 표가 비면 RTO는 미측정이다", async () => {
  const api = new HttpAdminApi("");
  const original = globalThis.fetch;
  globalThis.fetch = (async () =>
    fakeRes({
      status: 200,
      body: {
        schema_version: "1.2",
        verdict: { scope: "all", replay_deterministic: "PASS", failover_ok: "UNKNOWN", overall: "UNKNOWN" },
        recovery_runs: [
          {
            replayed_from_seq: 1,
            replayed_to_seq: 2,
            fp_replay: "seq:1-2:ev:5",
            fp_live: "seq:1-2:ev:5",
            matched: true,
            duration_ms: 12,
            created_at: "2026-08-30T00:00:00.000Z",
          },
        ],
        failover_runs: [],
      },
    })) as typeof fetch;
  try {
    const r = await api.getRecovery();
    assert.equal(r.lastRtoMs, null, "재생 소요를 RTO로 싣지 않는다");
    assert.equal(r.runs[0].durationMs, 12, "재생 소요는 그 자리에 남는다");
    assert.equal(r.failoverVerdict, "UNKNOWN");
  } finally {
    globalThis.fetch = original;
  }
});

/* ===== 플릿 상태 원천 매핑(status 1.3, §6-A) ===== */

test("플릿 상태 미보고를 정지·0대로 눕히지 않는다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    fakeRes({
      status: 200,
      // 관리 평면에 거버너가 없고 봇 노드 발행분도 없는 상태. 서버가 네 필드를 싣지 않는다
      body: { schema_version: "1.3", fleet_present: false, source: "unknown", trade_flash: "normal" },
    }),
    async () => {
      const s = await api.getSimStatus();
      assert.equal(s.source, "unknown");
      assert.equal(s.running, null, "미보고를 정지로 단정하지 않는다");
      assert.equal(s.paused, null);
      assert.equal(s.rate, null);
      assert.equal(s.currentOps, null);
      assert.equal(s.activeBots, null, "0대로 단정하지 않는다");
      assert.equal(s.botCount, null);
      assert.equal(s.markets, null, "빈 배열은 마켓 0개라는 뜻이라 미보고와 다르다");
    },
  );
});

test("봇 노드 발행분은 값으로 읽고 원천과 신선도를 남긴다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    fakeRes({
      status: 200,
      body: {
        schema_version: "1.3",
        fleet_present: false,
        source: "published",
        observed_at: "2026-09-06T13:40:00Z",
        stale: false,
        running: true,
        paused: false,
        target_ops: "10.0",
        trade_flash: "normal",
        markets: [
          {
            market: "KRW-BTC",
            target_net_bias: "0.00",
            measured_net_bias: "0.12",
            effective_bias: "0.00",
            auto_balance: { enabled: true, strength: "0.50" },
            scale: { MARKET_MAKER: { target: 4, active: 4 }, TAKER: { target: 2, active: 2 } },
            fill_ratio: "0.31",
          },
        ],
      },
    }),
    async () => {
      const s = await api.getSimStatus();
      assert.equal(s.source, "published");
      assert.equal(s.observedAt, "2026-09-06T13:40:00Z");
      assert.equal(s.stale, false);
      assert.equal(s.running, true);
      assert.equal(s.rate, 10);
      assert.equal(s.activeBots, 6);
      assert.equal(s.botCount, 6);
      assert.equal(s.markets?.length, 1);
    },
  );
});

test("서버가 내지 않는 성공률·지연·중복체결은 상수로 채우지 않는다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    fakeRes({
      status: 200,
      body: {
        schema_version: "1.3", fleet_present: true, source: "local",
        running: true, paused: false, target_ops: "20.0", trade_flash: "normal", markets: [],
      },
    }),
    async () => {
      const s = await api.getSimStatus();
      assert.equal(s.source, "local");
      // 종전에는 "1.0000"·0·0 을 박아 성공률 100%·지연 0ms 가 실측처럼 보였다
      assert.equal(s.successRate, null);
      assert.equal(s.avgLatencyMs, null);
      assert.equal(s.duplicateFillTotal, null);
      // markets 는 실제 빈 배열이므로 0대다. 미보고와 구분된다
      assert.deepEqual(s.markets, []);
      assert.equal(s.activeBots, 0);
    },
  );
});

test("source 없는 구 서버 응답도 미보고로 읽는다", async () => {
  const api = new HttpAdminApi("");
  await withFetch(
    fakeRes({ status: 200, body: { schema_version: "1.2", fleet_present: false, target_ops: "0" } }),
    async () => {
      const s = await api.getSimStatus();
      assert.equal(s.source, "unknown");
      assert.equal(s.running, null);
      assert.equal(s.markets, null);
    },
  );
});

/* ===== 종합 요약 합성(getOverview) ===== */

interface OverviewFixture {
  leaderAlive?: boolean;
  checks?: { check_name: string; ok: boolean }[];
  approvals?: { approval_id: number; expires_at: string }[];
}

/** getOverview가 부르는 여섯 경로를 주소로 가려 응답한다. 순서나 동시성에 기대지 않는다 */
function withOverview(fx: OverviewFixture, run: (api: HttpAdminApi, paths: string[]) => Promise<void>): Promise<void> {
  const prev = globalThis.fetch;
  const paths: string[] = [];
  const body = (url: string): unknown => {
    const path = new URL(url).pathname;
    paths.push(path);
    switch (path) {
      case "/admin/v1/ha/status":
        return { lease: { holder: "app-1", epoch: 7, leader_alive: fx.leaderAlive ?? true }, command_watermark: 10, trades_count: 3 };
      case "/admin/v1/ha/invariants":
        return {
          schema_version: "1.0",
          checks: (fx.checks ?? SCHEDULED_INVARIANTS.map((n) => ({ check_name: n, ok: true }))).map((c) => ({
            ...c,
            violated_rows: c.ok ? 0 : 2,
            checked_at: "2026-09-23T00:00:00.000Z",
          })),
        };
      case "/admin/v1/sim/status":
        return { schema_version: "1.3", fleet_present: false, source: "unknown", trade_flash: "normal" };
      case "/admin/v1/config/markets":
        return { data: [{ market: "KRW-BTC", status: "HALTED" }, { market: "KRW-ETH", status: "ACTIVE" }] };
      case "/admin/v1/ha/recovery":
        return {
          schema_version: "1.2",
          verdict: { scope: "all", replay_deterministic: "PASS", failover_ok: "UNKNOWN", overall: "UNKNOWN" },
          recovery_runs: [],
          failover_runs: [],
        };
      case "/admin/v1/approvals":
        return {
          data: (fx.approvals ?? []).map((a) => ({
            ...a,
            action: "config.market.halt",
            target: "KRW-BTC",
            requester_operator_id: "op-01",
            approver_operator_id: "op-01",
            state: "ISSUED",
            issued_at: "2026-09-23T00:00:00.000Z",
          })),
          pagination: { has_more: false },
        };
      default:
        throw new Error(`예상 밖 경로 ${path}`);
    }
  };
  globalThis.fetch = (async (input: unknown) => fakeRes({ status: 200, body: body(String(input)) })) as typeof fetch;
  return run(new HttpAdminApi("http://admin.test"), paths).finally(() => {
    globalThis.fetch = prev;
  });
}

test("종합: 리더 생존과 정기 검사 전부 통과면 정상이고 합성 여섯 경로를 한 번씩 읽는다", async () => {
  await withOverview({}, async (api, paths) => {
    const ov = await api.getOverview();
    assert.equal(ov.overallHealth, "success");
    assert.equal(ov.invariants.state, "PASS");
    assert.equal(ov.invariants.ok, SCHEDULED_INVARIANTS.length);
    assert.equal(ov.leaderNodeId, "app-1");
    assert.equal(ov.epoch, 7);
    assert.equal(ov.suspendedMarkets, 1);
    // 봇 노드 미보고를 0대로 눕히지 않는다
    assert.equal(ov.botsActive, null);
    assert.equal(ov.lastVerdict, "UNKNOWN");
    assert.equal(paths.length, 6);
    assert.equal(new Set(paths).size, 6);
  });
});

test("종합: 리더 리스가 죽었으면 장애다", async () => {
  await withOverview({ leaderAlive: false }, async (api) => {
    assert.equal((await api.getOverview()).overallHealth, "danger");
  });
});

test("종합: 검사 위반은 장애이고 정기 검사가 빠졌으면 주의다", async () => {
  const violated = SCHEDULED_INVARIANTS.map((n, i) => ({ check_name: n, ok: i !== 0 }));
  await withOverview({ checks: violated }, async (api) => {
    const ov = await api.getOverview();
    assert.equal(ov.invariants.state, "VIOLATED");
    assert.equal(ov.overallHealth, "danger");
  });
  const partial = SCHEDULED_INVARIANTS.slice(1).map((n) => ({ check_name: n, ok: true }));
  await withOverview({ checks: partial }, async (api) => {
    const ov = await api.getOverview();
    assert.equal(ov.invariants.state, "PARTIAL");
    assert.equal(ov.overallHealth, "warning");
  });
  await withOverview({ checks: [] }, async (api) => {
    const ov = await api.getOverview();
    assert.equal(ov.invariants.state, "NONE", "빈 표는 통과가 아니다");
    assert.equal(ov.overallHealth, "warning");
  });
});

test("종합: TTL이 지난 발급분은 대기 승인으로 세지 않는다", async () => {
  const future = new Date(Date.now() + 300_000).toISOString();
  const past = new Date(Date.now() - 1_000).toISOString();
  await withOverview(
    { approvals: [{ approval_id: 1, expires_at: future }, { approval_id: 2, expires_at: past }] },
    async (api) => {
      assert.equal((await api.getOverview()).pendingApprovals, 1);
    },
  );
});
