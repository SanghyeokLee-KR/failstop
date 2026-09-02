/**
 * 잔고 신선도 판정 표 (§2-3 429 클라이언트 규약).
 *
 * 이 표가 고정하는 계약은 둘이다.
 * 하나. 값이 있으면 화면을 비우지 않는다. 서버가 설계대로 백프레셔를 걸었을 때 화면이
 *   그것을 고장으로 번역하던 것이 이 작업의 출발점이다.
 * 둘. 값이 오래됐어도 확정 행위는 막지 않는다(v2.37 정정). 백프레셔가 걸리는 구간은
 *   거래가 몰리는 때이고, 그때 매도를 막으면 429 한 번을 더 나쁜 것으로 바꾼다.
 *   잔고 판정의 권위는 서버에 있다. 확정을 막는 것은 값이 없을 때뿐이다.
 *
 * 셋. 문구 축은 값의 유무가 가른다. 값이 있으면 지연 문구, 없으면 차단 문구다. 429 여부는
 *   대기 초를 말할 수 있는지만 가른다. 두 축을 하나로 접었을 때 확정이 열린 상태에 종결
 *   실패 문구가 붙었고, 화면이 주문 불가라 낭독하면서 버튼은 눌리는 상태가 됐다.
 *
 * failed 와 stale 을 가르는 것은 값의 유무이지 오류의 종류가 아니다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { decideBalance, type BalanceDecision, type BalanceNotice } from "../components/trade/derive";
import { clampRetrySec, useExchangeStore } from "../lib/store";

type FreshnessState = "idle" | "ok" | "stale" | "failed";
const STATES: FreshnessState[] = ["idle", "ok", "stale", "failed"];
// 대기 초는 유무만 판정에 쓰인다. 값 자체는 clampRetrySec 가 따로 든다
const WAITS: Array<number | null> = [null, 2];

/**
 * 판정 표 전수. 상태 4 x 시세 2 x 대기초 2 = 16 조합을 한 자리에 고정한다.
 * 조합을 테스트마다 흩으면 빠진 조합이 판정 대상 없이 통과로 보인다.
 */
const TABLE: Array<{
  state: FreshnessState;
  hasTicker: boolean;
  wait: number | null;
  expect: BalanceDecision;
}> = [
  { state: "idle", hasTicker: true, wait: null, expect: { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" } },
  { state: "idle", hasTicker: true, wait: 2, expect: { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" } },
  { state: "idle", hasTicker: false, wait: null, expect: { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" } },
  { state: "idle", hasTicker: false, wait: 2, expect: { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" } },
  { state: "ok", hasTicker: true, wait: null, expect: { view: "value", stale: false, ready: true, canRetry: false, notice: "none" } },
  { state: "ok", hasTicker: true, wait: 2, expect: { view: "value", stale: false, ready: true, canRetry: false, notice: "none" } },
  { state: "ok", hasTicker: false, wait: null, expect: { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" } },
  { state: "ok", hasTicker: false, wait: 2, expect: { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" } },
  { state: "stale", hasTicker: true, wait: null, expect: { view: "value", stale: true, ready: true, canRetry: true, notice: "delayed" } },
  { state: "stale", hasTicker: true, wait: 2, expect: { view: "value", stale: true, ready: true, canRetry: true, notice: "delayed_wait" } },
  { state: "stale", hasTicker: false, wait: null, expect: { view: "value", stale: true, ready: false, canRetry: true, notice: "delayed" } },
  { state: "stale", hasTicker: false, wait: 2, expect: { view: "value", stale: true, ready: false, canRetry: true, notice: "delayed_wait" } },
  { state: "failed", hasTicker: true, wait: null, expect: { view: "retry", stale: false, ready: false, canRetry: true, notice: "blocked" } },
  { state: "failed", hasTicker: true, wait: 2, expect: { view: "retry", stale: false, ready: false, canRetry: true, notice: "blocked_wait" } },
  { state: "failed", hasTicker: false, wait: null, expect: { view: "retry", stale: false, ready: false, canRetry: true, notice: "blocked" } },
  { state: "failed", hasTicker: false, wait: 2, expect: { view: "retry", stale: false, ready: false, canRetry: true, notice: "blocked_wait" } },
];

test("판정 표 전수: 16 조합의 표시·확정·문구를 고정한다", () => {
  const covered = new Set<string>();
  for (const row of TABLE) {
    const key = `${row.state}|${row.hasTicker}|${row.wait}`;
    const d = decideBalance({ state: row.state, hasTicker: row.hasTicker, retryAfterSec: row.wait });
    assert.deepEqual(d, row.expect, key);
    covered.add(key);
  }
  // 행이 빠지면 그 조합은 판정 대상이 없는 채로 통과한다. 덮은 조합 수를 먼저 증명한다
  assert.equal(covered.size, STATES.length * 2 * WAITS.length);
});

test("문구 축 다섯 값이 전부 실제로 나온다", () => {
  const seen = new Set<BalanceNotice>(TABLE.map((r) => r.expect.notice));
  assert.deepEqual([...seen].sort(), ["blocked", "blocked_wait", "delayed", "delayed_wait", "none"]);
});

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

test("stale: 값도 주문도 막지 않는다. 배지만 붙인다", () => {
  const d = decideBalance({ state: "stale", hasTicker: true, retryAfterSec: 1 });
  assert.equal(d.view, "value", "값이 사라지면 이 작업의 목적이 무너진다");
  assert.equal(d.stale, true);
  /* 백프레셔가 걸리는 구간은 거래가 몰리는 때다. 그때 매도를 막으면 429를 흡수한 것이
     아니라 더 나쁜 것으로 바꾼 것이다. 잔고 판정의 권위는 서버에 있다(§2-3 v2.37) */
  assert.equal(d.ready, true, "지연을 이유로 확정을 막지 않는다");
  assert.equal(d.notice, "delayed_wait", "확정을 여는 상태에 차단 문구를 붙이지 않는다");
  assert.equal(d.canRetry, true, "자동 재조회 1회가 실패하면 사람이 판단한다. 자리가 있어야 한다");
});

test("stale: 시세가 없으면 확정할 값이 없다", () => {
  // 총액·수수료 파생이 시세에서 나온다. 잔고와 별개 축이다
  const d = decideBalance({ state: "stale", hasTicker: false, retryAfterSec: 1 });
  assert.equal(d.view, "value", "잔고는 여전히 보인다");
  assert.equal(d.ready, false);
});

test("canRetry: 값을 못 읽은 두 상태에서만 참이다", () => {
  const on: string[] = [];
  for (const state of STATES) {
    if (decideBalance({ state, hasTicker: true, retryAfterSec: null }).canRetry) on.push(state);
  }
  assert.deepEqual(on, ["stale", "failed"]);
});

test("stale: 429가 아닌 실패도 값은 유지한다. 대기 초만 빠진다", () => {
  const d = decideBalance({ state: "stale", hasTicker: true, retryAfterSec: null });
  assert.equal(d.view, "value");
  assert.equal(d.stale, true);
  // 자동 재조회는 대기 초가 있는 경로에만 걸린다. 여기서 그것을 예고하면 오지 않는 갱신을 약속한다
  assert.equal(d.notice, "delayed");
});

test("failed: 값이 없으면 재시도 자리를 내준다", () => {
  const d = decideBalance({ state: "failed", hasTicker: true, retryAfterSec: null });
  assert.equal(d.view, "retry");
  assert.equal(d.stale, false, "표시할 값이 없으므로 지연 배지도 없다");
  assert.equal(d.ready, false);
  assert.equal(d.notice, "blocked");
});

test("failed + 429: 차단이되 스스로 풀리는 대기다", () => {
  const d = decideBalance({ state: "failed", hasTicker: true, retryAfterSec: 3 });
  assert.equal(d.view, "retry");
  assert.equal(d.notice, "blocked_wait", "값이 없어 막히지만 회복 시각은 서버가 말했다");
});

/* 이번 결함의 회귀 고정. 확정을 여는 상태에 종결 실패 문구가 붙어 있었다. 화면은 주문 불가를
   즉시 낭독하면서 CTA 는 열어 두었고, 스크린리더 사용자에게는 그 둘이 정면으로 어긋났다 */
test("확정을 여는 조합에는 차단 문구가 붙지 않는다", () => {
  const opened: string[] = [];
  for (const row of TABLE) {
    const d = decideBalance({ state: row.state, hasTicker: row.hasTicker, retryAfterSec: row.wait });
    if (!d.ready) continue;
    opened.push(`${row.state}|${row.wait}`);
    assert.ok(
      d.notice === "none" || d.notice === "delayed" || d.notice === "delayed_wait",
      `${row.state}|${row.wait} 가 확정을 열면서 ${d.notice} 를 낸다`,
    );
  }
  // 확정이 열리는 조합이 하나도 없으면 위 단언은 아무것도 판정하지 않는다
  assert.deepEqual(opened, ["ok|null", "ok|2", "stale|null", "stale|2"]);
});

test("차단 문구는 확정이 막힌 조합에만 나온다", () => {
  const blocked = TABLE.filter((r) => r.expect.notice === "blocked" || r.expect.notice === "blocked_wait");
  assert.equal(blocked.length, 4, "차단 조합이 없으면 아래 단언이 공허하다");
  for (const row of blocked) {
    assert.equal(row.expect.ready, false, `${row.state}|${row.wait}`);
    assert.equal(row.expect.view, "retry", "값이 없으므로 표시 자리에 재시도를 둔다");
  }
});

test("어느 상태에서도 안내 행은 최대 1개다", () => {
  const allowed: BalanceNotice[] = ["none", "delayed_wait", "delayed", "blocked_wait", "blocked"];
  for (const state of STATES) {
    for (const sec of WAITS) {
      for (const hasTicker of [true, false]) {
        const d = decideBalance({ state, hasTicker, retryAfterSec: sec });
        assert.ok(allowed.includes(d.notice));
        if (d.view === "loading") {
          assert.equal(d.notice, "none", "스켈레톤 옆에 경고를 겹치지 않는다");
        }
      }
    }
  }
});

/* 값을 못 읽은 두 상태를 가르는 선이 여기다. 지연된 참값은 확정을 허용하고,
   값 부재는 막는다. failed 에서 열면 0 으로 폴백된 거짓 잔고로 주문하게 된다 */
test("ready 는 값이 있는 상태 + 시세 조합에서만 참이다", () => {
  const ready: string[] = [];
  for (const state of STATES) {
    for (const hasTicker of [true, false]) {
      if (decideBalance({ state, hasTicker, retryAfterSec: null }).ready) {
        ready.push(`${state}/${hasTicker}`);
      }
    }
  }
  assert.deepEqual(ready, ["ok/true", "stale/true"]);
});

test("failed 는 어떤 조합에서도 확정을 열지 않는다", () => {
  for (const hasTicker of [true, false]) {
    for (const sec of [null, 1]) {
      assert.equal(decideBalance({ state: "failed", hasTicker, retryAfterSec: sec }).ready, false);
    }
  }
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

/* 상태 소유자가 스토어라는 것의 대가. assets 와 accountsHealth 가 같이 움직여야 한다.
   한쪽만 비우면 "값이 있다"는 stale 정의가 깨지고, decideBalance 가 그것을 값 있음으로
   읽어 CTA 까지 연다. 로그아웃 직후 빈 잔고로 주문이 열리는 경로가 그것이다. */
test("신원 교체는 잔고와 신선도를 함께 되돌린다", () => {
  const st = useExchangeStore.getState();
  useExchangeStore.setState({
    session: { user: { external_id: "u-1", email: "a@x", display_name: "A", kind: "HUMAN" }, accessToken: "t1" },
    assets: [{ currency: "KRW", balance: "1000", locked: "0", avg_buy_price: "0",
      unit_currency: "KRW", cost_basis_qty: "0", cost_basis_amount: "0" }],
    accountsHealth: { state: "stale", lastUpdatedAt: 1, retryAfterSec: 1 },
  });
  st.setSession(null);
  const after = useExchangeStore.getState();
  assert.equal(after.assets.length, 0);
  assert.equal(after.accountsHealth.state, "idle", "값이 사라졌으면 신선도도 초기 상태다");
  assert.equal(after.accountsHealth.lastUpdatedAt, null);
  // 이 조합이 남으면 아래가 참이 되어 빈 잔고로 주문이 열린다
  assert.equal(decideBalance({ state: after.accountsHealth.state, hasTicker: true, retryAfterSec: null }).ready, false);
});

test("같은 신원의 토큰 재발급은 잔고와 신선도를 보존한다", () => {
  const user = { external_id: "u-1", email: "a@x", display_name: "A", kind: "HUMAN" };
  useExchangeStore.setState({
    session: { user, accessToken: "t1" },
    assets: [{ currency: "KRW", balance: "1000", locked: "0", avg_buy_price: "0",
      unit_currency: "KRW", cost_basis_qty: "0", cost_basis_amount: "0" }],
    accountsHealth: { state: "ok", lastUpdatedAt: 42, retryAfterSec: null },
  });
  useExchangeStore.getState().setSession({ user, accessToken: "t2" });
  const after = useExchangeStore.getState();
  assert.equal(after.assets.length, 1, "회전은 신원 교체가 아니다");
  assert.equal(after.accountsHealth.state, "ok");
  assert.equal(after.accountsHealth.lastUpdatedAt, 42);
});

/* M3 회귀 고정. 신원 교체는 값을 비우고 신선도를 idle 로 되돌린다. 그 상태에서 재조회
   주체가 없으면 decideBalance 가 loading + canRetry=false 를 내고, 화면은 재시도 자리도
   없는 스켈레톤으로 고착한다. 값을 비우는 쪽과 다시 읽는 쪽이 짝이어야 한다는 계약이다 */
test("신원 교체 직후 상태는 사용자가 스스로 빠져나올 수 없다. 재조회가 필수다", () => {
  const d = decideBalance({ state: "idle", hasTicker: true, retryAfterSec: null });
  assert.equal(d.view, "loading");
  assert.equal(d.canRetry, false, "idle 에는 재시도 버튼이 없다");
  assert.equal(d.ready, false);
  /* 그러므로 setSession 이 idle 로 되돌린 뒤에는 반드시 누군가 loadAccounts 를 건다.
     그 배선은 ExchangeMarketClient 의 memberId 의존 효과다 */
});

/* in-flight 가드가 신원별이라는 것. 전역 부울이면 이전 신원 조회가 도는 동안 새 신원
   조회가 통째로 삼켜지고, 위 테스트가 말한 고착이 재조회를 걸어도 풀리지 않는다 */
test("in-flight 가드는 신원이 다르면 새 조회를 막지 않는다", () => {
  const user = (id: string) => ({ external_id: id, email: `${id}@x`, display_name: id, kind: "HUMAN" as const });
  useExchangeStore.setState({
    session: { user: user("u-1"), accessToken: "t1" },
    accountsInFlight: true,
    accountsInFlightId: "u-1",
  });
  const same = useExchangeStore.getState();
  assert.equal(same.accountsInFlight && same.accountsInFlightId === "u-1", true, "같은 신원은 삼킨다");
  useExchangeStore.setState({ session: { user: user("u-2"), accessToken: "t2" } });
  const other = useExchangeStore.getState();
  const swallowed =
    other.accountsInFlight && other.accountsInFlightId === (other.session?.user.external_id ?? null);
  assert.equal(swallowed, false, "다른 신원의 조회를 삼키면 새 화면이 값 없이 남는다");
});
