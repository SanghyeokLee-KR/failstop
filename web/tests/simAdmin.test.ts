/**
 * SimAdminApi 헬스·복구 시드 검증. 타이머(tick)는 브라우저 전용이라 노드 환경에서는
 * 생성 직후의 동기 상태만 단정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { SimAdminApi } from "../lib/admin/simAdmin";
import { INVARIANT_DEFS } from "../lib/admin/catalog";
import type { OpsTrading } from "../lib/admin/types";

test("복구 이력 시드는 스파크 게이트(n>=3)를 채우고 최신순이다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const rec = await api.getRecovery();
  assert.ok(rec.runs.length >= 3);
  for (let i = 1; i < rec.runs.length; i += 1) {
    assert.ok(rec.runs[i - 1].startedAt >= rec.runs[i].startedAt);
  }
  // lastRtoMs는 페일오버 RTO다. 목에 그 주행 표가 없으므로 null이어야 한다.
  // 재생 소요(recovery_runs.duration_ms)를 여기 실으면 축 혼동을 계약으로 굳힌다.
  assert.equal(rec.lastRtoMs, null, "없는 축을 지어내지 않는다");
  assert.ok(rec.runs[0].durationMs > 0, "재생 소요는 그 자리에 남는다");
  assert.equal(rec.failoverVerdict, null, "재생 축 결론을 페일오버 축에 복사하지 않는다");
});

test("시드 상태의 종합은 정상이고 불변식은 카탈로그 전부가 통과로 잡힌다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const ov = await api.getOverview();
  assert.equal(ov.overallHealth, "success");
  assert.equal(ov.invariants.state, "PASS");
  // 목은 권위 run 전용 검사까지 전부 싣는다. 통과 수가 분모를 넘지 않아야 한다
  assert.equal(ov.invariants.ok, INVARIANT_DEFS.length);
  assert.equal(ov.invariants.expected, INVARIANT_DEFS.length);
  assert.equal(ov.lastRtoMs, null);
});

test("세션 없이는 종합을 읽지 못한다", async () => {
  const api = new SimAdminApi();
  // 목은 권한 검사를 동기로 던진다. async로 감싸 거절로 받는다
  await assert.rejects(async () => api.getOverview());
});

test("HA 상태의 리스 표는 관제 판의 파티션 리더와 같은 matching-p0·matching-p1 행이다", async () => {
  const api = new SimAdminApi();
  await api.establishSession("op-01");
  const ha = await api.getHaStatus();
  const section = (await api.getControlCenter()).trading;
  assert.equal(section.state, "READY");
  const trading = (section as { data: OpsTrading }).data;
  assert.deepEqual(ha.leases?.map((l) => l.resource), trading.leaders.map((l) => `matching-${l.partition}`));
  assert.deepEqual(ha.leases?.map((l) => l.resource), ["matching-p0", "matching-p1"]);
  for (const [i, lease] of ha.leases!.entries()) {
    const leader = trading.leaders[i];
    assert.equal(lease.holder, leader.node);
    assert.equal(lease.epoch, leader.epoch);
    assert.deepEqual(lease.markets, ha.nodes[i].ownedMarkets);
    assert.ok(lease.markets.length > 0, "리더가 덮는 마켓이 비었다");
    // 목은 리스 만료 시각을 추적하지 않는다. 지어내지 않는다
    assert.equal(lease.leaseRemainingMs, null);
    assert.equal(lease.leaseUntil, null);
  }
  assert.equal(ha.epoch, Math.max(...ha.leases!.map((l) => l.epoch)));
  assert.deepEqual((await api.getOverview()).leases, ha.leases, "거래 요약 띠가 읽는 종합의 리스 행도 같다");
});
