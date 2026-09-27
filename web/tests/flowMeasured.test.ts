/**
 * 서비스 흐름·지표 관제 실측 모드(관리자 설계 4-2). 픽스처 flow.json 은 서버 단위 시험 MspFlowTest 가 스텁 Prometheus 로
 * 흐름 조회(GET /admin/v1/msp/flow)를 조립해 남긴 본문이다. 150초 창 안에서 주 DB(db-a)가 멈추고 예비 DB(db-b)가 승격되며
 * 리더가 app-1 에서 app-2 로 바뀌고, 조회 부하 뒤 희망 대수가 3이 되어 app-3 이 켜진다. 실제 스택의 응답은 아니다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { HttpAdminApi } from "../lib/admin/httpAdmin";
import { absentFlow, flowMeasuredOf, type WireFlow } from "../lib/admin/httpFlow";
import {
  appSlots,
  dbSlots,
  emptyBoard,
  measuredCardTitle,
  measuredDbTitle,
  measuredEdgeWhat,
  measuredEdges,
  measuredFlow,
  measuredNote,
  measuredRows,
  measuredViews,
  pendingBoard,
  rateText,
  rejectText,
  tierRates,
} from "../lib/admin/flowMeasured";
import { asgView } from "../lib/admin/flowSim";
import type { ControlCenter, FlowMeasured } from "../lib/admin/types";

const FIX = path.resolve(__dirname, "..", "..", "tests", "fixtures", "msp");
const wire = (JSON.parse(readFileSync(path.join(FIX, "flow.json"), "utf8")) as { body: WireFlow }).body;
const board = (): FlowMeasured => flowMeasuredOf(wire);

// MspFlowTest 의 격자 번호. 주 DB 중지, 리더 리스 상실, 예비 DB 승격, 새 리더와 거래 재개, 희망 3대, app-3 가동
const DB_DOWN = 30;
const LEASE_LOST = 33;
const PROMOTED = 38;
const RESUMED = 40;
const LOAD = 45;
const APP3_UP = 55;

/** 관제 판에서 흐름이 읽는 칸(임계값, 사건 기록)만 채운 판 */
function center(events: { at: string; kind: string; text: string }[], scaleOutPct: number | null): ControlCenter {
  return {
    thresholds: scaleOutPct === null
      ? { state: "NOT_CONNECTED", source: "임계값 레지스트리" }
      : { state: "READY", data: { items: [{ key: "read.scale_out_cpu", value: scaleOutPct }, { key: "read.scale_in_cpu", value: 10 }], history: [] } },
    incidents: { state: "READY", data: { incidents: [], events: events.map((e, i) => ({ id: `e${i}`, actor: "SYSTEM", actorName: "", incidentId: null, ...e })) } },
  } as unknown as ControlCenter;
}

test("흐름 조회 응답의 십진 문자열을 수로, 격자를 원점과 간격으로 옮긴다", () => {
  const b = board();
  assert.equal(b.environment, "LOCAL_DOCKER");
  assert.equal(b.step, 2);
  assert.equal(b.points, 76, "150초를 2초 격자로 양 끝 포함");
  assert.equal(b.start, Date.parse(wire.window?.start ?? "") / 1000);
  assert.equal(b.flow.state, "READY");
  const f = b.flow.state === "READY" ? b.flow.data : null;
  assert.ok(f);
  assert.deepEqual(f.apps.map((a) => [a.node, a.role]), [["app-1", "app"], ["app-2", "app"], ["app-3", "reader"]]);
  assert.equal(f.apps[0].cpu?.[0], 0.2);
  assert.equal(f.apps[2].up[APP3_UP - 1], false);
  assert.equal(f.apps[0].write?.[DB_DOWN], "UNREACHABLE");
  assert.equal(f.apps[0].leaseAge, null, "실패한 질의의 배열은 null 이다");
  assert.deepEqual(f.failed, ["app_lease_age"]);
  assert.equal(f.trades?.[DB_DOWN + 1], 0);
  assert.equal(f.orders?.[0], 25);
  assert.equal(f.apps[0].reads?.[LOAD], 150, "수용은 429 가 아닌 응답이다");
  assert.equal(f.apps[0].readsRejected?.[LOAD], 60);
  assert.equal(f.readRate?.[75], 400, "전체는 떠 있는 노드의 합");
  assert.equal(f.readRejectedRate?.[75], 25);
  assert.deepEqual(f.probes?.map((p) => p.service), ["alb", "cloudfront", "edge"]);
  assert.equal(b.readTier.state === "READY" ? b.readTier.data.desired?.[75] : null, 3);
  assert.equal(b.bots.state === "READY" ? b.bots.data.targetOps : null, 25);
});

test("흐름 자리는 역할 라벨로 정하고 DB 의 Primary 는 recovery 지표로 정한다", () => {
  const { state, samples, origin } = measuredFlow(board(), null);
  const m = state.measured;
  assert.ok(m);
  assert.equal(origin, board().start);
  assert.equal(samples.length, 76);
  assert.equal(samples[1].at, 2, "표본 간격은 서버 격자다");
  assert.equal(m.apps.api1.node, "app-1");
  assert.equal(m.apps.app3.node, "app-3");
  assert.equal(state.holder, "api2");
  assert.equal(state.epoch, 20);
  assert.deepEqual(state.db, { pg1: "stopped", pg2: "primary" });
  assert.equal(state.endpoint, "pg2");
  assert.equal(state.apps.app3.status, "running", "희망 3대에 떠 있는 app-3");
  assert.equal(state.desired, 3);
  assert.equal(m.suspended, false);
  assert.equal(state.replicating, false, "옛 Primary 가 멈춰 복제 상대가 없다");
  // 앱 평균 CPU 는 떠 있는 앱 노드의 평균이다(60·55·30)
  assert.ok(Math.abs((samples[75].util ?? 0) - 0.4833) < 0.001);
  assert.equal(samples[75].registered, 3);
  assert.equal(samples[APP3_UP - 1].cpu.app3, null, "뜨기 전 app-3 CPU 는 미수집");
  assert.equal(samples[75].reads?.app3, 100);
  assert.equal(samples[DB_DOWN - 1].replLagKb, 808 / 1024);
  assert.equal(samples[DB_DOWN].replLagKb, null);
});

test("창 안에서 바뀐 상태를 바뀐 격자의 사건으로 적는다", () => {
  const { state } = measuredFlow(board(), null);
  const at = (label: string) => state.events.find((e) => e.label === label)?.at;
  assert.equal(at("db-a 중지"), DB_DOWN * 2);
  assert.equal(at("거래 일시 중단"), DB_DOWN * 2);
  assert.equal(at("쓰기 리더 없음"), LEASE_LOST * 2);
  assert.equal(at("주 DB 없음"), DB_DOWN * 2);
  assert.equal(at("db-b 주 DB 승격"), PROMOTED * 2);
  assert.equal(at("리더 app-2 · epoch 20"), RESUMED * 2);
  assert.equal(at("거래 재개"), RESUMED * 2);
  assert.equal(at("app-3 가동"), APP3_UP * 2);
  assert.equal(state.events.find((e) => e.label === "거래 일시 중단")?.health, "danger");
  assert.ok(state.events.every((e, i, all) => i === 0 || all[i - 1].at <= e.at), "시각 순서다");
});

test("관제 판의 사건 기록과 증설 임계를 얹고 표지는 격자에 맞춰 내린다", () => {
  const b = board();
  const inWindow = new Date((b.start + 97) * 1000).toISOString();
  const before = new Date((b.start - 10) * 1000).toISOString();
  const c = center([
    { at: inWindow, kind: "APPROVED", text: "승인 · app-3 켜기 (앱 2 → 3대) · 김수퍼" },
    { at: before, kind: "OPENED", text: "경보 발생 · 앱 계층 CPU 높음" },
    { at: inWindow, kind: "ACKED", text: "확인" },
  ], 25);
  const { state } = measuredFlow(b, c);
  const approved = state.events.find((e) => e.label.startsWith("승인"));
  assert.deepEqual(approved, { at: 96, health: "success", label: "승인 · app-3 켜기 (앱 2 → 3대)" });
  assert.equal(state.events.some((e) => e.label.startsWith("경보 발생")), false, "창 밖의 기록은 싣지 않는다");
  assert.equal(state.events.some((e) => e.label === "확인"), false, "띠에 올리지 않는 종류다");
  assert.equal(state.measured?.scaleOut, 0.25);
  assert.equal(state.adviceOut, true, "앱 평균 48% 가 증설 임계 25% 이상이다");
  assert.equal(measuredFlow(b, center([], null)).state.measured?.scaleOut, null, "임계를 못 읽으면 지어내지 않는다");
});

test("노드 카드는 실측 값을 적고 로컬에 없는 CloudFront·ALB 는 같은 일을 하는 대상의 검사로 적는다", () => {
  const { state } = measuredFlow(board(), null);
  const v = measuredViews(state);
  assert.deepEqual([v.users.status, v.users.state], ["접속 가능", "running"]);
  assert.deepEqual([v.cdn.status, v.cdn.container], ["검사 정상", "localhost:3000 · 웹 입구 검사"]);
  assert.deepEqual([v.alb.status, v.alb.container], ["요청 450/s", "chaos-lb"], "조회 수용 400, 거절 25, 쓰기 25");
  // 구성도 카드에는 거절을 적지 않는다. ALB 는 요청 수, 앱 노드는 CPU 하나이고 거절은 팝오버와 표 비고가 적는다
  assert.ok(Object.values(v).every((x) => !Object.hasOwn(x, "reject")), "카드 값에 거절 토막이 없다");
  assert.equal(v.api2.status, "CPU 55%");
  assert.equal(Object.fromEntries(measuredRows("api2", state, 48))["거절"], "10/s · 요청 제한(429)");
  assert.equal(Object.fromEntries(measuredRows("alb", state, 48))["거절"], "25/s · 앱 요청 제한(429)");
  assert.equal(v.api1.role, "리더 대기 · 쓰기 백업 · 조회");
  assert.equal(v.api2.role, "매칭 리더 · epoch 20 · 조회");
  assert.deepEqual([v.app3.status, v.app3.state], ["CPU 30%", "running"]);
  assert.deepEqual([v.pg1.name, v.pg1.status, v.pg1.state], ["db-a", "중지 · 응답 없음", "failure"]);
  assert.deepEqual([v.pg2.name, v.pg2.status, v.pg2.role], ["db-b", "쓰기 · 연결 46", "주 DB · 쓰기"]);
  assert.equal(v.pg1.container, null, "DB 컨테이너 대응은 적지 않는다");
  assert.deepEqual([v.bot.status, v.bot.role], ["주문·취소 25/s", "주문 발행 · 목표 25 OPS"]);
  assert.deepEqual([v.load.status, v.load.state], ["조회 425/s", "running"], "load-1 이 보낸 조회는 수용과 거절의 합");
  assert.equal(v.obs.status, "일부 질의 실패");
});

test("연결선의 세기는 잰 초당 건수이고 멈춘 쪽은 끊김이나 꺼짐이다", () => {
  const { state } = measuredFlow(board(), null);
  const e = measuredEdges(state);
  assert.deepEqual(e["bot-alb"], { kind: "flow", tier: 2, rate: 25 });
  assert.equal(e["load-alb"].tier, 3);
  assert.equal(e["alb-api1"].rate, 160, "리더를 내준 app-1 은 조회(수용 150과 거절 10)만 받는다");
  assert.equal(e["alb-api2"].rate, 185);
  assert.deepEqual(e["alb-read"], { kind: "flow", tier: 2, rate: 105 });
  assert.equal(e["app-db"].rate, 380, "Primary 의 커밋 트랜잭션");
  assert.equal(e["app-db"].tier, 2, "커밋 선은 요청 선이 아니라 DB 커밋 경계로 단계를 가른다");
  assert.equal(e["read-db"].rate, 100, "거절한 조회는 DB 에 닿지 않는다");
  assert.equal(e.repl.kind, "down", "옛 Primary 가 멈춰 복제 상대가 없으면 복제선은 끊김이다(재생과 같은 말)");
  assert.equal(e["users-cdn"].kind, "idle", "사용자 쪽은 잰 건수가 없어 검사 성공이면 유휴다");
});

test("팝오버와 표는 쓰기 경로와 조회 경로를 나눠 적는다", () => {
  const { state } = measuredFlow(board(), null);
  const alb = Object.fromEntries(measuredRows("alb", state, 48));
  assert.equal(alb["쓰기 경로"], "25/s · 매칭 리더 app-2");
  assert.equal(alb["조회 경로"], "수용 400/s → app-1 · app-2 · app-3");
  assert.equal(alb["거절"], "25/s · 앱 요청 제한(429)");
  const api1 = Object.fromEntries(measuredRows("api1", state, 48));
  assert.equal(api1["리스 갱신"], "미수집");
  assert.equal(measuredNote(state, "api2"), "쓰기 25/s · 조회 150/s · 거절 10/s · 리더 epoch 20");
  assert.equal(measuredNote(state, "pg2"), "연결 46 · 커밋 380/s");
  assert.equal(measuredNote(state, "bot"), "목표 25 OPS · 체결 8.1/s");
  assert.equal(asgView(state).status, "앱 3대");
  assert.equal(rateText(0.25), "0.3");
  assert.equal(rateText(24.875), "25");
});

test("app-3 는 희망 대수로 기동 중과 내리는 중을 가르고, 흐름 조회가 없으면 연결 전이다", () => {
  const b = board();
  const f = b.flow.state === "READY" ? b.flow.data : null;
  assert.ok(f);
  const slots = appSlots(f.apps);
  assert.equal(slots.app3?.node, "app-3");
  const lowered: FlowMeasured = {
    ...b,
    readTier: b.readTier.state === "READY" ? { state: "READY", data: { ...b.readTier.data, desired: b.readTier.data.desired?.map(() => 2) ?? null } } : b.readTier,
  };
  assert.equal(measuredFlow(lowered, null).state.apps.app3.status, "draining");
  const early: FlowMeasured = { ...b, points: APP3_UP - 2 };
  const booting = measuredFlow(early, null).state;
  assert.equal(booting.apps.app3.status, "booting", "희망 3대인데 아직 뜨지 않았다");
  assert.equal(measuredViews(booting).app3.status, "기동 중");

  const none = measuredFlow(absentFlow(0), null);
  assert.equal(none.samples.length, 0);
  assert.equal(none.state.measured?.flow, "NOT_CONNECTED");
  assert.match(none.state.measured?.source ?? "", /GET \/admin\/v1\/msp\/flow/);
});

test("DB 자리는 창 안에서 응답한 노드가 먼저이고 옛 수집 대상은 자리를 차지하지 않는다", () => {
  const db = (node: string, reachable: boolean) => ({
    node,
    reachable: [reachable, reachable],
    up: null,
    inRecovery: null,
    connections: null,
    xact: null,
  });
  const slots = dbSlots([db("db-primary", false), db("db-2", true), db("db-1", true), db("db-standby", false)]);
  assert.deepEqual([slots.pg1?.node, slots.pg2?.node], ["db-1", "db-2"]);
  assert.equal(dbSlots(null).pg1, null);
});

test("값이 없으면 단위 없이 미수집으로 적는다", () => {
  const b = board();
  const { state } = measuredFlow({ ...b, points: APP3_UP - 2 }, null);
  assert.equal(measuredNote(state, "app3"), "조회 미수집");
  assert.equal(measuredRows("app3", state, 0).find(([k]) => k === "조회 경로")?.[1], "미수집");
});

test("http 어댑터는 흐름 조회를 읽고 404 면 연결 전 판을 돌려준다", async () => {
  const prev = globalThis.fetch;
  const paths: string[] = [];
  let status = 200;
  globalThis.fetch = (async (url: string) => {
    paths.push(url);
    return status === 200
      ? new Response(JSON.stringify(wire), { status: 200, headers: { "Content-Type": "application/json" } })
      : new Response(JSON.stringify({ error: { name: "not_found", message: "없음" } }), { status: 404 });
  }) as typeof fetch;
  try {
    const api = new HttpAdminApi("http://admin.test");
    const got = await api.getFlowMeasured();
    assert.equal(got?.points, 76);
    status = 404;
    const absent = await api.getFlowMeasured();
    assert.equal(absent?.flow.state, "NOT_CONNECTED");
    assert.deepEqual(paths, ["http://admin.test/admin/v1/msp/flow", "http://admin.test/admin/v1/msp/flow"]);
  } finally {
    globalThis.fetch = prev;
  }
});

test("흐름 조회가 없거나 실패해도 같은 도면을 그리고 칸마다 미수집이다", () => {
  const pending = measuredFlow(pendingBoard(0), null).state;
  const v = measuredViews(pending);
  for (const id of ["users", "cdn", "alb", "api1", "api2", "app3", "pg1", "pg2", "bot", "load"] as const) {
    assert.deepEqual([v[id].status, v[id].state], ["미수집", "absent"], id);
  }
  assert.deepEqual([v.obs.status, v.obs.state], ["확인 중", "absent"], "첫 응답 전에는 연결 전이라고 단정하지 않는다");
  assert.equal(v.pg1.role, "역할 미수집");
  assert.equal(measuredDbTitle(pending, "pg1"), "DB");
  assert.ok(Object.values(measuredEdges(pending)).every((e) => e.kind === "off"), "값이 없는 선은 끊김이 아니라 꺼짐이다");
  const asg = asgView(pending);
  assert.deepEqual([asg.state, asg.status], ["absent", "미수집"]);
  const rows = Object.fromEntries(measuredRows("asg", pending, null));
  assert.match(rows["앱 평균 CPU"], /^미수집 · /);
  assert.equal(rows["가동"], "미수집");
  assert.equal(Object.fromEntries(measuredRows("users", pending, null))["쓰기 리더"], "미수집", "리더 값이 없으면 없음이 아니라 미수집");
  assert.equal(measuredNote(pending, "asg"), "앱 목표 미수집 · 가동 미수집");

  assert.equal(measuredViews(measuredFlow(absentFlow(0), null).state).obs.status, "연결 전");
  const failed = measuredViews(measuredFlow(emptyBoard({ state: "UNAVAILABLE", source: "관리 API 흐름 조회", reason: "요청 실패 503" }, 0), null).state);
  assert.deepEqual([failed.obs.status, failed.obs.state], ["불러오기 실패", "failure"]);
  assert.deepEqual([failed.api1.status, failed.api1.state], ["미수집", "absent"]);
});

test("DB 역할은 recovery 값으로만 적고 멈췄거나 값이 없으면 이름으로 짐작하지 않는다", () => {
  const { state } = measuredFlow(board(), null);
  const v = measuredViews(state);
  assert.equal(v.pg1.role, "역할 미수집", "멈춘 옛 Primary 의 역할은 모른다");
  assert.equal(measuredDbTitle(state, "pg1"), "중지");
  assert.equal(measuredDbTitle(state, "pg2"), "주 DB", "승격 뒤 db-b 의 제목은 수집값을 따라 주 DB 다");
  assert.equal(measuredNote(state, "pg1"), "역할은 recovery 지표로 판정");

  // 살아 있는데 recovery 값이 없는 DB 는 Standby 로 적지 않는다
  const b = board();
  const f = b.flow.state === "READY" ? b.flow.data : null;
  assert.ok(f);
  const blind: FlowMeasured = { ...b, flow: { state: "READY", data: { ...f, dbs: (f.dbs ?? []).map((d) => ({ ...d, inRecovery: null })) } } };
  const s2 = measuredFlow(blind, null).state;
  const v2 = measuredViews(s2);
  assert.deepEqual([v2.pg2.role, v2.pg2.state, v2.pg2.status], ["역할 미수집", "running", "연결 46"]);
  assert.equal(measuredDbTitle(s2, "pg2"), "DB");
  assert.equal(Object.fromEntries(measuredRows("pg2", s2, 48))["역할"], "미수집");
  assert.equal(measuredNote(s2, "pg2"), "역할은 recovery 지표로 판정");
  assert.equal(measuredEdges(s2).repl.kind, "down", "Primary 를 모르면 DB 를 잰 것만으로 복제는 끊김이다");

  // DB 질의가 모두 실패한 판: DB 칸만 미수집이고 선은 끊김이 아니라 꺼짐이다
  const noDb: FlowMeasured = { ...b, flow: { state: "READY", data: { ...f, dbs: null, replLagBytes: null, failed: [...f.failed, "db_up"] } } };
  const s3 = measuredFlow(noDb, null).state;
  const v3 = measuredViews(s3);
  assert.deepEqual([v3.pg1.status, v3.pg1.state, v3.pg1.role], ["미수집", "absent", "역할 미수집"]);
  assert.equal(v3.api2.status, "CPU 55%", "앱 칸은 그대로 실측이다");
  assert.equal(v3.obs.status, "일부 질의 실패");
  const e3 = measuredEdges(s3);
  assert.deepEqual([e3["app-db"].kind, e3.repl.kind, e3["scrape-db"].kind], ["off", "off", "off"]);
});

test("조회 요청은 수용과 요청 제한 거절(429)로 나누고 app-3 합류 뒤 두 대의 거절이 준다", () => {
  const { samples, state } = measuredFlow(board(), null);
  assert.equal(samples[LOAD - 1].rejected?.api1, 0);
  assert.equal(samples[LOAD].rejected?.api1, 60, "두 대에 몰린 동안");
  assert.equal(samples[APP3_UP].rejected?.api1, 10, "app-3 합류 뒤");
  assert.equal(samples[APP3_UP].rejected?.app3, 5);
  const m = state.measured;
  assert.ok(m);
  assert.deepEqual(tierRates(m), { reads: 400, rejected: 25, writes: 25, total: 450, sent: 425 });
  assert.equal(rejectText(0), undefined, "거절이 없으면 카드에 싣지 않는다");
  assert.equal(rejectText(0.04), undefined, "0 으로 적힐 값도 싣지 않는다");
  assert.equal(rejectText(null), undefined);
  assert.equal(rejectText(2100.4), "거절 2100/s");

  // 거절을 싣지 않는 서버: 조회 합은 노드 값을 더하고 거절 토막은 없다
  const old = JSON.parse(JSON.stringify(wire)) as WireFlow;
  const data = old.flow?.data as Record<string, unknown> & { apps?: Record<string, unknown>[] };
  delete data.read_rate;
  delete data.read_rejected_rate;
  for (const a of data.apps ?? []) delete a.read_rejected_rate;
  const s0 = measuredFlow(flowMeasuredOf(old), null).state;
  const v0 = measuredViews(s0);
  assert.equal(v0.alb.status, "요청 425/s");
  assert.equal(Object.fromEntries(measuredRows("alb", s0, 48))["거절"], "미수집", "거절을 싣지 않는 서버면 거절을 모른다");
  const m0 = s0.measured;
  assert.ok(m0);
  assert.deepEqual([tierRates(m0).reads, tierRates(m0).rejected], [400, null]);
});

test("DB 복제 방식과 승격 방식은 흐름 조회의 수집값으로 적는다", () => {
  const b = board();
  const f = b.flow.state === "READY" ? b.flow.data : null;
  assert.ok(f);
  assert.equal(f.replicationMode?.[DB_DOWN - 1], "SYNC");
  assert.equal(f.promotion?.[75], "AUTO");

  // 주 DB 가 멈추기 전: 동기 Standby(db-b)가 복제를 받는다
  const before = measuredFlow({ ...b, points: DB_DOWN }, null).state;
  const v = measuredViews(before);
  assert.equal(v.pg2.role, "예비 DB · 엄격 동기");
  assert.deepEqual([measuredDbTitle(before, "pg1"), measuredDbTitle(before, "pg2")], ["주 DB", "예비 DB"]);
  assert.equal(measuredNote(before, "pg2"), "엄격 동기 · 복제 지연 1 KB");
  const rows = Object.fromEntries(measuredRows("pg1", before, 48));
  assert.equal(rows["복제 방식"], "엄격 동기 복제");
  assert.equal(rows["승격"], "자동 승격(Patroni)");
  assert.equal(measuredEdgeWhat("repl", before), "엄격 동기 복제 스트림");

  // 승격 뒤: 복제 상대가 없어 방식을 모른다. 승격 방식은 Patroni 지표 그대로 자동이다
  const { state } = measuredFlow(b, null);
  assert.equal(measuredEdgeWhat("repl", state), "복제 스트림 · 방식 미수집");
  assert.equal(Object.fromEntries(measuredRows("pg2", state, 48))["복제 방식"], "복제 방식 미수집");
  assert.equal(state.measured?.promotion, "AUTO");
  assert.equal(measuredEdgeWhat("bot-alb", state), "주문·취소 요청", "다른 선은 그대로다");
});

test("구성도 카드 제목은 호스트 이름 대신 한글 역할 이름이고 표준 이름은 그대로다", () => {
  const { state } = measuredFlow(board(), null);
  assert.deepEqual(
    (["api1", "api2", "app3", "load"] as const).map((k) => measuredCardTitle(k, state)),
    ["앱 서버 1", "앱 서버 2", "앱 서버 3", "부하 발생기"],
  );
  for (const k of ["alb", "cdn", "users", "bot", "obs", "pg1"] as const) assert.equal(measuredCardTitle(k, state), null, k);
  // 앱 서버 번호는 자리가 아니라 수집 대상 node 라벨의 번호를 따른다
  const m = state.measured;
  assert.ok(m);
  const swapped = { ...state, measured: { ...m, apps: { ...m.apps, api1: { ...m.apps.api1, node: "app-4" } } } };
  assert.equal(measuredCardTitle("api1", swapped), "앱 서버 4");
  // 시뮬레이션·재생 판은 바꾸지 않는다
  assert.equal(measuredCardTitle("api1", { ...state, measured: null }), null);
});
