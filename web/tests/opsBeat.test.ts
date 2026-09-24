/**
 * 콘솔 공용 박자와 관제 판 폴러(관리자 설계 4-4). 박자는 구독자가 있고 탭이 보일 때만 돌고, 탭이 숨으면
 * 멈췄다가 다시 보이는 순간 한 번 뛴다. 폴러는 그 박자를 타서 숨은 탭에서는 읽지 않고, 돌아오면 밀린 갱신을
 * 곧바로 한다.
 */

import { setHidden } from "./support/fakeVisibility";
import { test } from "node:test";
import assert from "node:assert/strict";
import { beatRunning, onBeat } from "../lib/admin/ticker";
import { opsCenterStore } from "../lib/admin/opsCenter";
import { getAdminApi } from "../lib/admin/api";
import type { ControlCenter } from "../lib/admin/types";

const settle = () => new Promise((r) => setImmediate(r));

test("박자는 초 경계 바로 뒤에 뛰고, 구독이 모두 풀리거나 탭이 숨으면 멈춘다", (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 10_500 });
  const seen: number[] = [];
  const off = onBeat((now) => seen.push(now));
  assert.equal(beatRunning(), true);
  assert.deepEqual(seen, [10_500], "첫 구독은 곧바로 한 번 뛴다");
  t.mock.timers.tick(504);
  t.mock.timers.tick(1000);
  assert.deepEqual(seen, [10_500, 11_004, 12_004], "초 경계 바로 뒤에 뛴다");

  setHidden(true);
  assert.equal(beatRunning(), false, "숨은 탭에서는 멈춘다");
  t.mock.timers.tick(5000);
  assert.equal(seen.length, 3);
  setHidden(false);
  assert.equal(seen.length, 4, "다시 보이면 곧바로 한 번 뛴다");
  assert.equal(beatRunning(), true);

  off();
  assert.equal(beatRunning(), false, "구독이 모두 풀리면 멈춘다");
  t.mock.timers.tick(5000);
  assert.equal(seen.length, 4);
});

test("관제 판 폴러는 숨은 탭에서 읽지 않고, 다시 보이면 곧바로 읽는다", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 50_200 });
  const api = getAdminApi() as unknown as { getControlCenter: () => Promise<ControlCenter> };
  const prev = api.getControlCenter;
  let calls = 0;
  api.getControlCenter = async () => {
    calls += 1;
    return { origin: "SIM", generatedAt: String(Date.now()) } as unknown as ControlCenter;
  };
  opsCenterStore.setState({ data: null, lastAt: null, loading: true, failed: false, subscribers: 0, failures: 0, nextAt: 0 });
  const store = opsCenterStore.getState();
  try {
    store.subscribe();
    store.subscribe();
    await settle();
    assert.equal(calls, 1, "구독자가 여럿이어도 진입 조회는 한 번이다");

    // sim 주기는 1초다. 다음 박자 두 번에 한 번 읽는다
    t.mock.timers.tick(804);
    await settle();
    t.mock.timers.tick(1000);
    await settle();
    assert.equal(calls, 2);

    setHidden(true);
    t.mock.timers.tick(30_000);
    await settle();
    assert.equal(calls, 2, "숨은 탭에서는 읽지 않는다");

    setHidden(false);
    await settle();
    assert.equal(calls, 3, "돌아오면 박자를 기다리지 않고 읽는다");

    store.unsubscribe();
    assert.equal(beatRunning(), true, "남은 구독자가 있으면 계속 돈다");
    store.unsubscribe();
    assert.equal(beatRunning(), false);
    t.mock.timers.tick(10_000);
    await settle();
    assert.equal(calls, 3);
  } finally {
    while (opsCenterStore.getState().subscribers > 0) opsCenterStore.getState().unsubscribe();
    api.getControlCenter = prev;
  }
});

test("조작 직후의 강제 읽기는 떠 있는 요청 뒤에 한 번만 더 읽는다", async () => {
  const api = getAdminApi() as unknown as { getControlCenter: () => Promise<ControlCenter> };
  const prev = api.getControlCenter;
  let calls = 0;
  let open: () => void = () => {};
  api.getControlCenter = () => {
    calls += 1;
    return new Promise((r) => {
      open = () => r({ origin: "SIM", generatedAt: String(calls) } as unknown as ControlCenter);
    });
  };
  opsCenterStore.setState({ data: null, lastAt: null, loading: true, failed: false, subscribers: 0, failures: 0, nextAt: 0 });
  try {
    const s = opsCenterStore.getState();
    const first = s.refresh();
    const a = s.refresh(true);
    const b = s.refresh(true);
    assert.equal(calls, 1);
    open();
    await first;
    await settle();
    assert.equal(calls, 2, "겹친 강제 읽기는 한 번으로 모인다");
    open();
    await Promise.all([a, b]);
    assert.equal(opsCenterStore.getState().data?.generatedAt, "2");
  } finally {
    api.getControlCenter = prev;
  }
});
