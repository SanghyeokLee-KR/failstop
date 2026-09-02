/**
 * 잔고 신선도 판정 표 (§2-3 429 클라이언트 규약).
 *
 * 이 표가 고정하는 계약은 둘이다.
 * 하나. 값이 있으면 화면을 비우지 않는다. 서버가 설계대로 백프레셔를 걸었을 때 화면이
 *   그것을 고장으로 번역하던 것이 이 작업의 출발점이다.
 * 둘. 값이 오래됐으면 확정 행위는 막는다. 낡은 잔고로 주문을 허용하면 429 한 번을 흡수한
 *   대가로 주문 거부 한 번을 만든다.
 *
 * failed 와 stale 을 가르는 것은 값의 유무이지 오류의 종류가 아니다. 오류 종류는 문구만 가른다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { decideBalance } from "../components/trade/derive";
import { clampRetrySec } from "../lib/store";

test("idle: 첫 조회 전에는 스켈레톤이고 주문은 막힌다", () => {
  const d = decideBalance({ state: "idle", hasTicker: true, retryAfterSec: null });
  assert.equal(d.view, "loading");
  assert.equal(d.ready, false);
  assert.equal(d.notice, "none");
});

test("ok: 시세까지 있으면 값과 주문을 연다", () => {
  const d = decideBalance({ state: "ok", hasTicker: true, retryAfterSec: null });
  assert.deepEqual(d, { view: "value", stale: false, ready: true, canRetry: false, notice: "none" });
});

test("ok: 시세가 없으면 아직 그릴 값이 없다(총액·수수료 파생 미성립)", () => {
  const d = decideBalance({ state: "ok", hasTicker: false, retryAfterSec: null });
  assert.equal(d.view, "loading");
  assert.equal(d.ready, false);
});

test("stale: 값을 지우지 않는다. 배지를 붙이고 확정만 막는다", () => {
  const d = decideBalance({ state: "stale", hasTicker: true, retryAfterSec: 1 });
  assert.equal(d.view, "value", "값이 사라지면 이 작업의 목적이 무너진다");
  assert.equal(d.stale, true);
  assert.equal(d.ready, false, "낡은 잔고로 주문을 확정하지 않는다");
  assert.equal(d.notice, "backpressure");
  assert.equal(d.canRetry, true, "자동 재조회 1회가 실패하면 사람이 판단한다. 자리가 있어야 한다");
});

test("canRetry: 값을 못 읽은 두 상태에서만 참이다", () => {
  const states = ["idle", "ok", "stale", "failed"] as const;
  const on: string[] = [];
  for (const state of states) {
    if (decideBalance({ state, hasTicker: true, retryAfterSec: null }).canRetry) on.push(state);
  }
  assert.deepEqual(on, ["stale", "failed"]);
});

test("stale: 429가 아닌 실패도 값은 유지한다. 문구만 갈린다", () => {
  const d = decideBalance({ state: "stale", hasTicker: true, retryAfterSec: null });
  assert.equal(d.view, "value");
  assert.equal(d.stale, true);
  assert.equal(d.notice, "error");
});

test("failed: 값이 없으면 재시도 자리를 내준다", () => {
  const d = decideBalance({ state: "failed", hasTicker: true, retryAfterSec: null });
  assert.equal(d.view, "retry");
  assert.equal(d.stale, false, "표시할 값이 없으므로 지연 배지도 없다");
  assert.equal(d.ready, false);
  assert.equal(d.notice, "error");
});

test("failed + 429: 종결 실패가 아니라 백프레셔 문구다", () => {
  const d = decideBalance({ state: "failed", hasTicker: true, retryAfterSec: 3 });
  assert.equal(d.view, "retry");
  assert.equal(d.notice, "backpressure", "429를 종결 실패로 적지 않는다");
});

test("어느 상태에서도 안내 행은 최대 1개다", () => {
  const states = ["idle", "ok", "stale", "failed"] as const;
  for (const state of states) {
    for (const sec of [null, 1]) {
      for (const hasTicker of [true, false]) {
        const d = decideBalance({ state, hasTicker, retryAfterSec: sec });
        assert.ok(["none", "backpressure", "error"].includes(d.notice));
        if (d.view === "loading") {
          assert.equal(d.notice, "none", "스켈레톤 옆에 경고를 겹치지 않는다");
        }
      }
    }
  }
});

test("ready 는 ok + 시세 조합에서만 참이다", () => {
  const states = ["idle", "ok", "stale", "failed"] as const;
  const ready: string[] = [];
  for (const state of states) {
    for (const hasTicker of [true, false]) {
      if (decideBalance({ state, hasTicker, retryAfterSec: null }).ready) {
        ready.push(`${state}/${hasTicker}`);
      }
    }
  }
  assert.deepEqual(ready, ["ok/true"]);
});

/* 대기 초 유계화. 상한이 없으면 잘못된 헤더 하나가 화면을 몇 분 잠그고,
   하한이 없으면 0초 재조회가 폭주가 된다 */
test("clampRetrySec: null·비유한값은 하한으로 접는다", () => {
  assert.equal(clampRetrySec(null), 1);
  assert.equal(clampRetrySec(Number.NaN), 1);
  assert.equal(clampRetrySec(Number.POSITIVE_INFINITY), 1);
});

test("clampRetrySec: 0과 음수는 하한, 과대값은 상한", () => {
  assert.equal(clampRetrySec(0), 1);
  assert.equal(clampRetrySec(-5), 1);
  assert.equal(clampRetrySec(3600), 30);
});

test("clampRetrySec: 소수는 올린다. 이르게 재조회하면 다시 429다", () => {
  assert.equal(clampRetrySec(1.2), 2);
  assert.equal(clampRetrySec(2), 2);
});
