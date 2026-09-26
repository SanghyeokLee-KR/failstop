/**
 * tests/tradingStatus.test.ts. 거래 일시 중단 표시와 결과를 모르는 주문의 규칙(API 설계 §2-5-e·§4-A-7·§4-F,
 * Patroni 설계 6-2).
 *
 * 회귀 방지 대상: 중단을 ACTIVE 로 오독해 버튼이 열리는 것, 배너 우선순위가 뒤집히는 것, 423 을 받은 키를 버리고 새 키로
 * 다시 내는 것, 중단 중에 423 키를 재조회해 거짓 미접수를 확정하는 것.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ACTIVE_TRADING,
  bannerKind,
  coerceTradingStatus,
  dueForRequery,
  isSuspended,
  pendingCauseOf,
  pollIntervalMs,
  requeryVerdict,
  sinceLabel,
  suspensionCopy,
  trackPending,
  type PendingOrder,
} from "../lib/tradingStatus";
import { isFinalRejectStatus } from "../components/trade/orderKey";
import { getDataSource } from "../lib/api/datasource";
import { ApiError } from "../lib/api/httpDatasource";
import { useExchangeStore } from "../lib/store";
import type { Order, PublicNotice, TradingStatus } from "../lib/types";

const suspended = (reason: TradingStatus["reason"] = "replication_unavailable"): TradingStatus => ({
  trading_suspended: true,
  trading_state: "SUSPENDED",
  reason,
  since: 1_790_000_000_000,
  checked_at: 1_790_000_001_234,
  poll_after_seconds: 5,
});

const notice: PublicNotice = { id: "n1", title: "점검", body: "", severity: "CRITICAL", published_at: 1 };

test("응답 모양이 틀리면 null 이라 직전 상태를 유지한다", () => {
  assert.equal(coerceTradingStatus(null), null);
  assert.equal(coerceTradingStatus({ trading_state: "SUSPENDED" }), null, "불리언이 없으면 판정하지 않는다");
  assert.deepEqual(coerceTradingStatus({ ...suspended(), schema_version: "1.0" }), suspended());
});

test("모르는 이유가 와도 중단 사실은 버리지 않는다", () => {
  const s = coerceTradingStatus({ ...suspended(), reason: "future_reason" });
  assert.equal(s?.trading_suspended, true, "중단을 ACTIVE 로 오독하면 버튼이 열린다");
  assert.equal(s?.reason, null);
});

test("ACTIVE 면 이유와 중단 시각을 싣지 않는다", () => {
  const s = coerceTradingStatus({ ...ACTIVE_TRADING, reason: "replication_unavailable", since: 5 });
  assert.equal(s?.reason, null);
  assert.equal(s?.since, null);
  assert.equal(isSuspended(s), false);
  assert.equal(isSuspended(null), false, "못 읽은 상태는 막지 않는다");
});

test("배너는 연결 끊김, 거래 일시 중단, 공지 순이다", () => {
  assert.equal(bannerKind(false, suspended(), notice), "connection");
  assert.equal(bannerKind(true, suspended(), notice), "suspended", "중단은 공지보다 먼저다");
  assert.equal(bannerKind(true, ACTIVE_TRADING, notice), "notice");
  assert.equal(bannerKind(true, null, null), null);
  assert.equal(bannerKind(true, suspended(), null), "suspended", "공지 화면에서도 중단 배너는 뜬다");
});

test("이유마다 문구가 다르고 보이는 문구는 명사형이다", () => {
  const replication = suspensionCopy("replication_unavailable");
  const database = suspensionCopy("database_unavailable");
  assert.equal(replication.title, "거래 일시 중단");
  assert.match(replication.body, /복제 서버 복구 대기/);
  assert.match(database.body, /DB 전환 진행 중/);
  assert.deepEqual(suspensionCopy(null), replication, "이유를 모르면 복제 대기 문구다");
  for (const text of [replication.body, replication.sr, database.body, database.sr]) {
    assert.doesNotMatch(text, /(습니다|니다\.|하세요|십시오)/, `합쇼체 종결: ${text}`);
  }
});

test("중단 시각은 브라우저 시각의 시:분:초다", () => {
  const at = new Date(2026, 8, 25, 14, 3, 7).getTime();
  assert.equal(sinceLabel(at), "중단 시각 14:03:07");
  assert.equal(sinceLabel(null), null);
});

test("폴링 간격은 서버 권장값을 1~30초로 유계화한다", () => {
  assert.equal(pollIntervalMs(null), 5_000);
  assert.equal(pollIntervalMs({ ...suspended(), poll_after_seconds: 0 }), 1_000);
  assert.equal(pollIntervalMs({ ...suspended(), poll_after_seconds: 3600 }), 30_000);
});

test("423 을 받은 주문 키는 버리지 않는다", () => {
  // 423 은 이 시도만 쓰지 않았다는 뜻이다. 앞선 시도가 재개 뒤 커밋될 수 있어 새 키로 다시 내면 모르는 주문이 체결된다
  assert.equal(isFinalRejectStatus(423, "trading_suspended"), false);
  assert.equal(pendingCauseOf(423, "trading_suspended"), "suspended");
  assert.equal(pendingCauseOf(409, "order_in_progress"), "unknown");
  assert.equal(pendingCauseOf(503, "service_unavailable"), "unknown");
  assert.equal(pendingCauseOf(0, "network_error"), "unknown");
  assert.equal(pendingCauseOf(429, "too_many_requests"), null, "429 는 확정 미접수다");
  assert.equal(pendingCauseOf(409, "duplicate_client_order_id"), null);
  assert.equal(pendingCauseOf(422, "domain_rejected"), null);
});

test("423 키는 재개를 확인한 뒤에만 재조회한다", () => {
  const list: PendingOrder[] = [
    { clientOrderId: "a", market: "KRW-BTC", cause: "suspended", since: 1 },
    { clientOrderId: "b", market: "KRW-BTC", cause: "unknown", since: 2 },
  ];
  assert.deepEqual(dueForRequery(list, suspended()).map((p) => p.clientOrderId), ["b"],
    "중단 중의 재조회는 409 라 판정이 안 되고, 423 키는 앞선 시도가 아직 커밋을 기다릴 수 있다");
  assert.deepEqual(dueForRequery(list, null).map((p) => p.clientOrderId), ["b"], "재개를 확인하지 못했다");
  assert.deepEqual(dueForRequery(list, ACTIVE_TRADING).map((p) => p.clientOrderId), ["a", "b"]);
});

test("같은 키는 하나만 추적한다", () => {
  let list: PendingOrder[] = [];
  list = trackPending(list, { clientOrderId: "a", market: "KRW-BTC", cause: "unknown", since: 1 });
  list = trackPending(list, { clientOrderId: "a", market: "KRW-BTC", cause: "suspended", since: 2 });
  assert.equal(list.length, 1);
  assert.equal(list[0].cause, "suspended");
});

test("재조회 판정은 200 접수, 404 미접수 확정, 그 밖은 다시 본다", () => {
  assert.equal(requeryVerdict(200), "accepted");
  assert.equal(requeryVerdict(404), "not_accepted");
  for (const status of [409, 429, 503, 0]) assert.equal(requeryVerdict(status), "retry");
});

/* ===== 스토어 ===== */

const ds = getDataSource();

function restore(...keys: ("getTradingStatus" | "getOrderByClientId")[]): void {
  for (const k of keys) Reflect.deleteProperty(ds, k);
}

function order(clientOrderId: string): Order {
  return {
    schema_version: "1.0",
    id: `id-${clientOrderId}`,
    client_order_id: clientOrderId,
    market: "KRW-BTC",
    side: "bid",
    ord_type: "limit",
    status: "OPEN",
    price: "50000000",
    volume: "0.01",
    remaining_volume: "0.01",
    executed_volume: "0",
    executed_funds: "0",
    remaining_notional: null,
    paid_fee: "0",
    trades_count: 0,
    created_at: "2026-09-25T00:00:00.000Z",
    epoch: 1,
    seq: 1,
  };
}

test("상태 조회가 실패하면 직전 상태를 유지한다", async () => {
  useExchangeStore.setState({ tradingStatus: suspended() });
  Object.assign(ds, { getTradingStatus: async () => { throw new ApiError(503, "service_unavailable", "x"); } });
  try {
    const kept = await useExchangeStore.getState().loadTradingStatus();
    assert.equal(kept?.trading_suspended, true, "한 번 못 읽었다고 중단 중인 화면의 버튼을 열면 안 된다");
    assert.equal(useExchangeStore.getState().tradingStatus?.trading_suspended, true);
  } finally {
    restore("getTradingStatus");
  }
});

test("재개 뒤 재조회가 결과를 정하고 판정 보류는 남긴다", async () => {
  const asked: string[] = [];
  Object.assign(ds, {
    getOrderByClientId: async (coid: string) => {
      asked.push(coid);
      if (coid === "accepted") return order(coid);
      if (coid === "absent") throw new ApiError(404, "order_not_found", "접수된 주문 없음");
      throw new ApiError(409, "order_in_progress", "주문 결과 확인 중. 재조회 필요", 1);
    },
  });
  useExchangeStore.setState({
    session: null,
    openOrders: [],
    closedOrders: [],
    tradingStatus: suspended(),
    pendingOrders: [
      { clientOrderId: "accepted", market: "KRW-BTC", cause: "suspended", since: 1 },
      { clientOrderId: "absent", market: "KRW-BTC", cause: "suspended", since: 1 },
      { clientOrderId: "pending", market: "KRW-BTC", cause: "unknown", since: 1 },
    ],
  });
  try {
    await useExchangeStore.getState().resolvePendingOrders();
    assert.deepEqual(asked, ["pending"], "중단 중에는 423 키를 묻지 않는다");

    useExchangeStore.setState({ tradingStatus: { ...ACTIVE_TRADING } });
    await useExchangeStore.getState().resolvePendingOrders();
    const left = useExchangeStore.getState().pendingOrders.map((p) => p.clientOrderId);
    assert.deepEqual(left, ["pending"], "409 는 판정이 아니라 다시 본다");
    assert.ok(useExchangeStore.getState().openOrders.some((o) => o.client_order_id === "accepted"),
      "접수된 주문은 목록에 반영한다");
  } finally {
    restore("getOrderByClientId");
    useExchangeStore.setState({ pendingOrders: [], tradingStatus: null, openOrders: [] });
  }
});
