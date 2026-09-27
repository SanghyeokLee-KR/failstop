/**
 * 서비스 흐름·지표 관제의 실측 모드(관리자 설계 4-2, http 원천). 관리 API 흐름 조회(GET /admin/v1/msp/flow)의 150초 창을
 * 흐름 상태와 표본으로 옮기고, 창 안에서 바뀐 상태를 사건으로 적는다. 원천에 없는 값은 미수집으로 두고 지어내지 않는다.
 * 로컬 Docker 에 없는 AWS 구성 요소(CloudFront·ALB)는 같은 일을 하는 웹 입구·로드밸런서의 검사 결과로 적는다.
 *
 * 흐름 자리는 수집 대상의 라벨로 정한다. reader 는 조회 전용 app-3 자리, 나머지 앱 노드는 node 라벨 순으로 app-1·app-2
 * 자리다. DB 두 자리는 창 안에서 응답한 노드가 먼저이고, Primary·Standby 는 자리나 이름이 아니라 recovery 지표로 정한다.
 */

import {
  APP_CONTAINER,
  APP_IDS,
  APP_NAME,
  DB_IDS,
  EDGE_IDS,
  ENGINE_IDS,
  LIVE_EVENT_HEALTH,
  asgView,
  createFlow,
  dbEdgeTier,
  edgeTier,
  liveEventLabel,
  view,
  type AppId,
  type AppNode,
  type DbId,
  type DbStatus,
  type EdgeFlow,
  type EdgeId,
  type EngineId,
  type FlowEvent,
  type FlowSample,
  type FlowState,
  type MeasuredApp,
  type MeasuredDb,
  type MeasuredFacts,
  type NodeId,
  type NodeState,
  type NodeView,
} from "./flowSim";
import { promotionWord, replicationWord, standbyWord } from "./replicationWords";
import type {
  ControlCenter,
  FlowMeasured,
  FlowMeasuredApp,
  FlowMeasuredDb,
  FlowMeasuredSeries,
  FlowWriteState,
  OpsEnvironment,
  OpsSection,
} from "./types";

/** 차트 창(초). 서버가 읽는 창과 같다 */
export const MEASURED_WINDOW_SEC = 150;
/** 흐름 조회를 다시 읽는 주기. 서버의 격자 간격과 같다 */
export const MEASURED_POLL_MS = 2_000;
/** 사건 표식 상한. 창이 150초라 넘을 일이 드물지만 상태가 흔들리는 원천에서 띠가 넘치지 않게 둔다 */
const MAX_EVENTS = 40;

/**
 * 값이 하나도 없는 판. 첫 응답을 받기 전이나 흐름 조회가 없거나 실패했을 때 같은 도면을 미수집으로 그리는 데 쓴다.
 * 흐름 칸의 상태와 원천만 다르고 모든 칸이 비었다
 */
export function emptyBoard(flow: Exclude<OpsSection<FlowMeasuredSeries>, { state: "READY" }>, now = Date.now()): FlowMeasured {
  const none = { state: "NOT_CONNECTED" as const, source: flow.source };
  return {
    environment: "UNKNOWN",
    generatedAt: new Date(now).toISOString(),
    start: Math.floor(now / 1000),
    step: 2,
    points: 0,
    flow,
    readTier: none,
    bots: none,
  };
}

/**
 * 첫 응답을 기다리는 동안의 판. 도면은 그대로 두고 모든 칸이 미수집이다. 원천 이름이 비어 있어 관측 카드가 연결 전이 아니라
 * 확인 중이라고 적는다
 */
export function pendingBoard(now = Date.now()): FlowMeasured {
  return emptyBoard({ state: "NOT_CONNECTED", source: "" }, now);
}

const byNode = (a: { node: string }, b: { node: string }) => a.node.localeCompare(b.node);

/** 흐름 자리에 앱 노드를 앉힌다. reader 는 app-3, 나머지는 node 라벨 순으로 app-1·app-2 자리다 */
export function appSlots(apps: FlowMeasuredApp[]): Record<AppId, FlowMeasuredApp | null> {
  const readers = apps.filter((a) => a.role === "reader").sort(byNode);
  const engines = apps.filter((a) => a.role !== "reader").sort(byNode);
  return { api1: engines[0] ?? null, api2: engines[1] ?? null, app3: readers[0] ?? null };
}

/**
 * DB 두 자리. 창 안에서 한 번이라도 수집기가 응답한 노드를 먼저 앉힌다. 구성을 바꾼 뒤 남은 옛 수집 대상(창 내내 응답 없음)이
 * 자리를 차지하지 않게 하려는 순서이고, 그다음은 node 라벨 순이다
 */
export function dbSlots(dbs: FlowMeasuredDb[] | null): Record<DbId, FlowMeasuredDb | null> {
  const seen = (d: FlowMeasuredDb) => (d.reachable.some((v) => v === true) ? 0 : 1);
  const sorted = [...(dbs ?? [])].sort((a, b) => seen(a) - seen(b) || byNode(a, b));
  return { pg1: sorted[0] ?? null, pg2: sorted[1] ?? null };
}

const pick = <T>(arr: (T | null)[] | null | undefined, i: number): T | null => (arr ? arr[i] ?? null : null);

function appAt(a: FlowMeasuredApp | null, i: number): MeasuredApp {
  if (a === null) {
    return { node: null, up: null, cpu: null, reads: null, rejected: null, writes: null, leader: null, epoch: null, write: null, leaseAge: null };
  }
  const cpu = pick(a.cpu, i);
  return {
    node: a.node,
    up: pick(a.up, i),
    cpu: cpu === null ? null : cpu * 100,
    reads: pick(a.reads, i),
    rejected: pick(a.readsRejected, i),
    writes: pick(a.writes, i),
    leader: pick(a.leader, i),
    epoch: pick(a.epoch, i),
    write: pick(a.write, i),
    leaseAge: pick(a.leaseAge, i),
  };
}

function dbAt(d: FlowMeasuredDb | null, i: number): MeasuredDb {
  if (d === null) return { node: null, reachable: null, up: null, primary: null, connections: null, xact: null };
  const recovery = pick(d.inRecovery, i);
  return {
    node: d.node,
    reachable: pick(d.reachable, i),
    up: pick(d.up, i),
    primary: recovery === null ? null : !recovery,
    connections: pick(d.connections, i),
    xact: pick(d.xact, i),
  };
}

/** 매칭 리더 자리. 리더 값을 낸 노드가 하나도 없으면 모르므로 undefined 다 */
function leaderOf(apps: Record<AppId, MeasuredApp>): EngineId | null | undefined {
  const known = ENGINE_IDS.filter((id) => apps[id].leader !== null);
  if (known.length === 0) return undefined;
  return known.find((id) => apps[id].leader === true) ?? null;
}

/**
 * 거래가 멈췄는가. 리더가 있으면 리더의 쓰기 가능 상태, 없으면 떠 있는 노드 가운데 ACTIVE 가 하나도 없는지로 본다. 쓰기 가능
 * 상태가 ACTIVE 가 아니면 공개 상태 API 가 거래 일시 중단을 알린다(API 설계 §2-5-e)
 */
function suspendedOf(apps: Record<AppId, MeasuredApp>): boolean | null {
  const alive = APP_IDS.map((id) => apps[id]).filter((a) => a.up === true && a.write !== null);
  if (alive.length === 0) return null;
  const leader = alive.find((a) => a.leader === true);
  if (leader) return leader.write !== "ACTIVE";
  return !alive.some((a) => a.write === "ACTIVE");
}

const dbAlive = (d: MeasuredDb) => (d.reachable === true && d.up !== null ? d.up : d.reachable === false ? false : null);

/** Primary 자리. 역할을 아는 DB 가 하나도 없으면 undefined 다 */
function primaryOf(dbs: Record<DbId, MeasuredDb>): DbId | null | undefined {
  const known = DB_IDS.filter((id) => dbAlive(dbs[id]) !== null);
  if (known.length === 0) return undefined;
  return known.find((id) => dbAlive(dbs[id]) === true && dbs[id].primary === true) ?? null;
}

const appName = (id: AppId, a: MeasuredApp) => a.node ?? APP_NAME[id];
const dbName = (id: DbId, d: MeasuredDb) => d.node ?? (id === "pg1" ? "DB 1" : "DB 2");

interface Frame {
  apps: Record<AppId, MeasuredApp>;
  dbs: Record<DbId, MeasuredDb>;
}

function frameAt(apps: Record<AppId, FlowMeasuredApp | null>, dbs: Record<DbId, FlowMeasuredDb | null>, i: number): Frame {
  return {
    apps: { api1: appAt(apps.api1, i), api2: appAt(apps.api2, i), app3: appAt(apps.app3, i) },
    dbs: { pg1: dbAt(dbs.pg1, i), pg2: dbAt(dbs.pg2, i) },
  };
}

/**
 * 창 안에서 바뀐 상태를 사건으로 적는다. 사건은 바뀐 격자에 선다. 값을 모르는 점(null)을 사이에 둔 변화는 적지 않는다.
 * app-3 는 수집 대상이 사라져도(꺼짐) 계열이 끊기므로 null 을 꺼짐으로 본다
 */
function measuredEvents(frames: Frame[], step: number): FlowEvent[] {
  const events: FlowEvent[] = [];
  for (let i = 1; i < frames.length; i += 1) {
    const prev = frames[i - 1];
    const cur = frames[i];
    const at = i * step;
    const push = (health: FlowEvent["health"], label: string) => events.push({ at, health, label });
    for (const id of ENGINE_IDS) {
      const a = prev.apps[id].up;
      const b = cur.apps[id].up;
      if (a === null || b === null || a === b || cur.apps[id].node === null) continue;
      if (b) push("success", `${appName(id, cur.apps[id])} 복구`);
      else push("danger", `${appName(id, cur.apps[id])} 중단`);
    }
    const r0 = prev.apps.app3.up === true;
    const r1 = cur.apps.app3.up === true;
    if (r0 !== r1) push(r1 ? "success" : "warning", `${appName("app3", cur.apps.app3)} ${r1 ? "가동" : "꺼짐"}`);

    const l0 = leaderOf(prev.apps);
    const l1 = leaderOf(cur.apps);
    if (l0 !== undefined && l1 !== undefined && l0 !== l1) {
      if (l1 === null) push("danger", "쓰기 리더 없음");
      else {
        const epoch = cur.apps[l1].epoch;
        push("success", `리더 ${appName(l1, cur.apps[l1])}${epoch !== null ? ` · epoch ${epoch}` : ""}`);
      }
    }
    const s0 = suspendedOf(prev.apps);
    const s1 = suspendedOf(cur.apps);
    if (s0 !== null && s1 !== null && s0 !== s1) push(s1 ? "danger" : "success", s1 ? "거래 일시 중단" : "거래 재개");

    for (const id of DB_IDS) {
      const a = dbAlive(prev.dbs[id]);
      const b = dbAlive(cur.dbs[id]);
      if (a === null || b === null || a === b || cur.dbs[id].node === null) continue;
      push(b ? "success" : "danger", `${dbName(id, cur.dbs[id])} ${b ? "가동" : "중지"}`);
    }
    const p0 = primaryOf(prev.dbs);
    const p1 = primaryOf(cur.dbs);
    if (p0 !== undefined && p1 !== undefined && p0 !== p1) {
      if (p1 === null) push("danger", "주 DB 없음");
      else push("success", `${dbName(p1, cur.dbs[p1])} 주 DB 승격`);
    }
  }
  return events;
}

/**
 * 관제 판의 사건 기록 가운데 창 안의 것(경보 발생, 승인, 조치, 해제). 표지는 기록이 난 초를 격자에 맞춰 내린 자리에 선다.
 * 지표 관제의 시간축은 격자 점에만 좌표가 있다
 */
function centerEvents(center: ControlCenter | null, origin: number, step: number, last: number): FlowEvent[] {
  if (center === null || center.incidents.state !== "READY") return [];
  const out: FlowEvent[] = [];
  for (const e of center.incidents.data.events) {
    const health = LIVE_EVENT_HEALTH[e.kind];
    const sec = Math.floor(Date.parse(e.at) / 1000) - origin;
    if (!health || !Number.isFinite(sec) || sec < 0 || sec > last) continue;
    out.push({ at: Math.floor(sec / step) * step, health, label: liveEventLabel(e.text) });
  }
  return out;
}

/** 임계값 관리의 증설·축소 임계(0..1). http 관제 판의 항목 값은 백분율이다 */
function thresholdsOf(center: ControlCenter | null): { scaleOut: number | null; scaleIn: number | null } {
  if (center === null || center.thresholds.state !== "READY") return { scaleOut: null, scaleIn: null };
  const value = (key: string) => {
    const v = center.thresholds.state === "READY" ? center.thresholds.data.items.find((t) => t.key === key)?.value : null;
    return typeof v === "number" ? v / 100 : null;
  };
  return { scaleOut: value("read.scale_out_cpu"), scaleIn: value("read.scale_in_cpu") };
}

const sumOrNull = (values: (number | null)[]) =>
  values.every((v) => v === null) ? null : values.reduce<number>((a, v) => a + (v ?? 0), 0);

function appNode(id: AppId, a: MeasuredApp, desired: number | null): AppNode {
  if (a.up === true) {
    // 희망 대수가 3 아래로 내려갔는데 app-3 이 아직 떠 있으면 조정기가 로드밸런서에서 빼고 내리는 중이다
    if (id === "app3" && desired !== null && desired < 3) return { status: "draining", inRead: false };
    return { status: "running", inRead: true };
  }
  if (id === "app3") return { status: desired !== null && desired >= 3 ? "booting" : "stopped", inRead: false };
  return { status: a.up === false ? "killed" : "stopped", inRead: false };
}

/**
 * 흐름 상태의 DB 자리. 살아 있는데 recovery 값이 없는 DB 는 흐름 상태의 자리만 standby 로 둔다. 화면 말(제목, 역할, 비고,
 * 복제 선)은 이 자리가 아니라 recovery 값(MeasuredDb.primary)을 직접 읽고, 모르면 미수집이라고 적는다
 */
function dbStatus(d: MeasuredDb): DbStatus {
  if (d.node === null || dbAlive(d) !== true) return "stopped";
  return d.primary === true ? "primary" : "standby";
}

/** 흐름 조회가 환경을 모르면(없는 배포) 같은 관리 JVM 의 한 판이 싣는 환경을 쓴다 */
function envOf(board: FlowMeasured, center: ControlCenter | null): OpsEnvironment {
  return board.environment === "UNKNOWN" && center ? center.environment : board.environment;
}

function emptyFacts(board: FlowMeasured, center: ControlCenter | null): MeasuredFacts {
  const f = frameAt({ api1: null, api2: null, app3: null }, { pg1: null, pg2: null }, 0);
  const source = board.flow.state === "READY" ? null : board.flow.state === "UNAVAILABLE"
    ? `${board.flow.reason} · ${board.flow.source}`
    : board.flow.source;
  return {
    env: envOf(board, center),
    at: new Date((board.start + Math.max(0, board.points - 1) * board.step) * 1000).toISOString(),
    flow: board.flow.state,
    source,
    apps: f.apps,
    dbs: f.dbs,
    probes: { cloudfront: null, alb: null, edge: null },
    trades: null,
    orders: null,
    tierReads: null,
    tierRejected: null,
    targetOps: board.bots.state === "READY" ? board.bots.data.targetOps : null,
    botSource: board.bots.state === "READY" ? board.bots.data.source : null,
    lagBytes: null,
    replMode: null,
    promotion: null,
    tier: { mode: null, min: null, max: null, desired: null },
    ...thresholdsOf(center),
    suspended: null,
    failed: [],
  };
}

/**
 * 흐름 조회 한 판을 흐름 상태, 표본, 원점으로 옮긴다. 표본의 at 은 창 첫 격자로부터의 초이고 원점이 그 격자의 벽시계 초다.
 * 관제 판(center)은 임계값과 사건 기록을 보탠다. 없으면 그 둘만 비운다
 */
export function measuredFlow(board: FlowMeasured, center: ControlCenter | null): { state: FlowState; samples: FlowSample[]; origin: number } {
  const origin = board.start;
  const s = createFlow();
  if (board.flow.state !== "READY" || board.points === 0) {
    // 값이 없는 판은 재생 세계의 첫 상태(리더 app-1, Primary DB 1, 가동 2대)를 싣지 않는다. 리더 표지나 DB 역할을 짐작하지 않는다
    s.holder = null;
    for (const id of APP_IDS) {
      s.apps[id] = { status: "stopped", inRead: false };
      s.cpuEff[id] = 0;
    }
    for (const id of DB_IDS) s.db[id] = "stopped";
    s.replicating = false;
    s.botOps = 0;
    s.opsEff = 0;
    s.util = 0;
    s.utilTarget = 0;
    s.measured = emptyFacts(board, center);
    return { state: s, samples: [], origin };
  }
  const series = board.flow.data;
  const apps = appSlots(series.apps);
  const dbs = dbSlots(series.dbs);
  const tier = board.readTier.state === "READY" ? board.readTier.data : null;
  const step = board.step;
  const frames: Frame[] = [];
  const samples: FlowSample[] = [];
  for (let i = 0; i < board.points; i += 1) {
    const f = frameAt(apps, dbs, i);
    frames.push(f);
    const up = APP_IDS.map((id) => f.apps[id].up);
    const cpus = APP_IDS.filter((id) => f.apps[id].up === true).map((id) => f.apps[id].cpu).filter((v): v is number => v !== null);
    const lag = pick(series.replLagBytes, i);
    samples.push({
      at: i * step,
      trades: pick(series.trades, i),
      botOps: pick(series.orders, i),
      lease: null,
      cpu: { api1: f.apps.api1.cpu, api2: f.apps.api2.cpu, app3: f.apps.app3.cpu },
      util: cpus.length === 0 ? null : cpus.reduce((a, v) => a + v, 0) / cpus.length / 100,
      desired: pick(tier?.desired, i),
      registered: up.every((v) => v === null) ? null : up.filter((v) => v === true).length,
      replLagKb: lag === null ? null : lag / 1024,
      reads: { api1: f.apps.api1.reads, api2: f.apps.api2.reads, app3: f.apps.app3.reads },
      rejected: { api1: f.apps.api1.rejected, api2: f.apps.api2.rejected, app3: f.apps.app3.rejected },
    });
  }

  const lastIndex = board.points - 1;
  const now = frames[lastIndex];
  const nowSample = samples[lastIndex];
  const desired = nowSample.desired;
  const thresholds = thresholdsOf(center);
  const probe = (service: string) => {
    const p = series.probes?.find((x) => x.service === service);
    return p ? p.success[lastIndex] ?? null : null;
  };
  // 전환 공백에도 SQL 선이 옛 Primary 를 가리키게 창 안에서 마지막으로 Primary 였던 자리를 쓴다
  let endpoint: DbId = "pg1";
  for (let i = lastIndex; i >= 0; i -= 1) {
    const p = primaryOf(frames[i].dbs);
    if (p) {
      endpoint = p;
      break;
    }
  }
  const holder = leaderOf(now.apps) ?? null;
  const events = [...measuredEvents(frames, step), ...centerEvents(center, origin, step, lastIndex * step)].sort((a, b) => a.at - b.at);

  s.tick = lastIndex * step;
  for (const id of APP_IDS) s.apps[id] = appNode(id, now.apps[id], desired);
  s.holder = holder;
  s.epoch = (holder ? now.apps[holder].epoch : null) ?? 1;
  s.lease = 0;
  s.desired = (desired ?? nowSample.registered ?? 2) >= 3 ? 3 : 2;
  for (const id of DB_IDS) s.db[id] = dbStatus(now.dbs[id]);
  s.endpoint = endpoint;
  s.replicating = nowSample.replLagKb !== null;
  s.botOps = nowSample.botOps ?? 0;
  s.opsEff = s.botOps;
  s.readVu = 0;
  s.util = nowSample.util ?? 0;
  s.utilTarget = s.util;
  for (const id of APP_IDS) s.cpuEff[id] = now.apps[id].cpu ?? 0;
  s.adviceOut = thresholds.scaleOut !== null && nowSample.util !== null && nowSample.util >= thresholds.scaleOut;
  s.events = events.length > MAX_EVENTS ? events.slice(events.length - MAX_EVENTS) : events;
  s.measured = {
    env: envOf(board, center),
    at: new Date((origin + lastIndex * step) * 1000).toISOString(),
    flow: "READY",
    source: null,
    apps: now.apps,
    dbs: now.dbs,
    probes: { cloudfront: probe("cloudfront"), alb: probe("alb"), edge: probe("edge") },
    trades: nowSample.trades,
    orders: nowSample.botOps,
    tierReads: pick(series.readRate, lastIndex),
    tierRejected: pick(series.readRejectedRate, lastIndex),
    targetOps: board.bots.state === "READY" ? board.bots.data.targetOps : null,
    botSource: board.bots.state === "READY" ? board.bots.data.source : null,
    lagBytes: pick(series.replLagBytes, lastIndex),
    replMode: pick(series.replicationMode, lastIndex),
    promotion: pick(series.promotion, lastIndex),
    tier: { mode: tier?.mode ?? null, min: tier?.min ?? null, max: tier?.max ?? null, desired },
    ...thresholds,
    suspended: suspendedOf(now.apps),
    failed: series.failed,
  };
  return { state: s, samples, origin };
}

/* ===== 화면 말 ===== */

/** 초당 건수. 10 이상은 정수, 그 아래는 첫째 자리까지 적는다 */
export function rateText(v: number | null): string {
  if (v === null) return "미수집";
  return v >= 10 ? String(Math.round(v)) : String(Math.round(v * 10) / 10);
}

/** 단위를 붙인 초당 건수. 값이 없으면 단위 없이 미수집이다 */
const perSec = (v: number | null) => (v === null ? "미수집" : `${rateText(v)}/s`);

const WRITE_WORD: Record<FlowWriteState, string> = {
  ACTIVE: "쓰기 가능",
  SUSPENDED: "쓰기 보류 · 동기 복제 대기",
  UNREACHABLE: "DB 접속 불가",
};

const probeWord = (ok: boolean | null) => (ok === null ? "미수집" : ok ? "정상" : "실패");

/**
 * 로컬에서 load-1 자리를 맡는 조회 부하. 실측 시연은 k6(grafana/k6 컨테이너, 초당 요청 고정)로 건다(2026-09-27). 카드 값은 부하
 * 발생기가 아니라 앱 계층이 받은 조회 경로 요청의 합계라 발생기가 바뀌어도 값은 그대로다
 */
const LOAD_CONTAINER = "grafana/k6 · 컨테이너";

/** 앱 계층이 받은 초당 요청 */
export interface TierRates {
  /** 조회 경로 수용(429가 아닌 응답) */
  reads: number | null;
  /** 조회 경로 요청 제한 거절(429) */
  rejected: number | null;
  /** 쓰기 경로(주문·취소·계좌·호가) */
  writes: number | null;
  /** 셋의 합. 로드밸런서가 앱 계층에 넘긴 요청 전체다 */
  total: number | null;
  /** 조회 경로 전체(수용과 거절). load-1 이 보낸 몫이다 */
  sent: number | null;
}

/** 떠 있는 앱 노드가 받은 요청의 합. 조회 합은 서버가 낸 전체 값을 쓰고, 없는 서버면 노드 값을 더한다 */
export function tierRates(m: MeasuredFacts): TierRates {
  const up = APP_IDS.map((id) => m.apps[id]).filter((a) => a.up === true);
  const reads = m.tierReads ?? sumOrNull(up.map((a) => a.reads));
  const rejected = m.tierRejected ?? sumOrNull(up.map((a) => a.rejected));
  const writes = sumOrNull(up.map((a) => a.writes));
  return { reads, rejected, writes, total: sumOrNull([reads, rejected, writes]), sent: sumOrNull([reads, rejected]) };
}

/** 노드가 받은 요청 전체(조회 수용과 거절, 쓰기 경로)/초 */
export function nodeTotal(a: MeasuredApp): number | null {
  return sumOrNull([a.reads, a.rejected, a.writes]);
}

/**
 * 인스턴스 표 비고에 붙는 요청 제한 거절 토막. 0 으로 적힐 만큼 작거나(0.05/s 아래) 값이 없으면 싣지 않는다. 구성도 카드에는
 * 거절을 적지 않는다(2026-09-27 사용자 요청)
 */
export function rejectText(v: number | null): string | undefined {
  return v === null || v < 0.05 ? undefined : `거절 ${perSec(v)}`;
}

function appStatus(id: AppId, s: FlowState, a: MeasuredApp): [string, NodeState] {
  const n = s.apps[id];
  // 수집 대상이 없는 app-3 는 꺼진 것이다(조정기가 멈추면 수집 대상에서 빠진다). 매칭 자리가 비면 값이 없는 것이다
  if (a.node === null) return id === "app3" ? (n.status === "booting" ? ["기동 중", "transition"] : ["꺼짐", "stopped"]) : ["미수집", "absent"];
  switch (n.status) {
    case "killed":
      return ["중단 · 응답 없음", "failure"];
    case "booting":
      return ["기동 중", "transition"];
    case "draining":
      return ["내리는 중", "transition"];
    case "stopped":
      return id === "app3" ? ["꺼짐", "stopped"] : ["미수집", "absent"];
    default:
      if (a.write === "UNREACHABLE") return ["DB 접속 불가", "failure"];
      if (a.write === "SUSPENDED") return ["쓰기 보류", "transition"];
      return [a.cpu === null ? "CPU 미수집" : `CPU ${Math.round(a.cpu)}%`, "running"];
  }
}

function dbView(id: DbId, s: FlowState): NodeView {
  const m = s.measured as MeasuredFacts;
  const d = m.dbs[id];
  const name = dbName(id, d);
  const alive = dbAlive(d);
  // 역할은 recovery 지표로만 적는다. 멈췄거나 지표가 없으면 노드 이름으로 짐작하지 않고 미수집이다. 이름으로 짐작하면
  // db-1·db-2 처럼 역할이 이름에 없는 배치에서 전환 중에 두 대가 같은 역할로 잡힌다
  const role = alive === true && d.primary !== null ? (d.primary ? "주 DB · 쓰기" : standbyWord(m.replMode)) : "역할 미수집";
  // DB 컨테이너 구성은 바뀌는 중이라 대응을 적지 않고 수집 대상의 node 라벨을 이름으로 쓴다
  const v = (status: string, state: NodeState) => view(name, null, role, status, state);
  if (d.node === null) return v("미수집", "absent");
  if (d.reachable === false) return v("수집 끊김", "failure");
  if (d.up === false) return v("중지 · 응답 없음", "failure");
  if (alive === null) return v("미수집", "absent");
  if (d.primary === null) return v(`연결 ${d.connections ?? "미수집"}`, "running");
  if (d.primary) return v(`쓰기 · 연결 ${d.connections ?? "미수집"}`, "running");
  return m.lagBytes !== null ? v("복제 수신 중", "running") : v("복제 끊김", "failure");
}

/**
 * 흐름 조회가 없거나 실패한 동안의 노드 카드. 도면은 그대로 두고 값만 미수집(점선 윤곽)이다. 관측 카드만 원천의 상태
 * (연결 전, 불러오기 실패)를 적는다
 */
function unknownViews(m: MeasuredFacts, local: boolean): Record<NodeId, NodeView> {
  const none = (name: string, container: string | null, role: string) => view(name, container, role, "미수집", "absent");
  const failed = m.flow === "UNAVAILABLE";
  // 첫 응답을 기다리는 판(pendingBoard)은 원천 이름이 비어 있다
  const pending = m.flow === "NOT_CONNECTED" && !m.source;
  return {
    users: none("사용자", local ? "localhost:3000 · 웹 입구" : null, "웹 · 봇 주문"),
    cdn: none("CloudFront", local ? "localhost:3000 · 웹 입구 검사" : null, "엣지 · 정적 배포 · 캐시"),
    alb: none("ALB", local ? "chaos-lb" : null, "쓰기 → 매칭 리더 · 조회 → 앱 계층"),
    api1: none(APP_NAME.api1, local ? APP_CONTAINER.api1 : null, "매칭 리더 · 조회"),
    api2: none(APP_NAME.api2, local ? APP_CONTAINER.api2 : null, "매칭 리더 · 조회"),
    app3: none(APP_NAME.app3, local ? APP_CONTAINER.app3 : null, "조회 전용 · Auto Scaling"),
    pg1: none("DB 1", null, "역할 미수집"),
    pg2: none("DB 2", null, "역할 미수집"),
    bot: none("bot-1 · 시장 봇", local ? "chaos-bots" : null, "주문 발행"),
    obs: view("bot-1 · 관측", local ? "chaos-prometheus · chaos-grafana" : null, "Prometheus 수집 · Grafana",
      failed ? "불러오기 실패" : pending ? "확인 중" : "연결 전", failed ? "failure" : "absent"),
    load: none("load-1", local ? LOAD_CONTAINER : null, "조회 경로 요청 합계"),
  };
}

/**
 * 실측 모드 구성도의 카드 제목. 호스트 이름(app-1, load-1) 대신 한글 역할 이름이다(2026-09-27 사용자 요청: 보는 사람이 호스트
 * 이름을 모른다). 앱 서버 번호는 수집 대상 node 라벨(app-N)의 번호를 따르고, 라벨이 그 모양이 아니면 자리 번호다. 호스트 이름은
 * 팝오버 제목과 인스턴스 표가 적는다. 표준 이름(ALB, CloudFront, Primary, Standby, 시장 봇, 관측 등)은 바꾸지 않아 null 이다
 */
export function measuredCardTitle(key: NodeId, s: FlowState): string | null {
  const m = s.measured;
  if (m === null) return null;
  if (key === "load") return "부하 발생기";
  if (key === "api1" || key === "api2" || key === "app3") {
    const slot = { api1: "1", api2: "2", app3: "3" }[key];
    return `앱 서버 ${/^app-(\d+)$/.exec(m.apps[key].node ?? "")?.[1] ?? slot}`;
  }
  return null;
}

/** 실측 모드 구성도의 온프레미스 AI 서버 카드와 bot-1 묶음 이름표(한글 역할 이름) */
export const MEASURED_AI_TITLE = "AI 서버";
export const MEASURED_BOT_FRAME = "봇 서버";

/**
 * 실측 모드의 DB 카드 제목. 역할은 recovery 지표로 읽은 것만 적고(주 DB·예비 DB, 2026-09-27 사용자 요청으로 한글 역할 이름),
 * 멈춘 DB 는 중지, 모르면 DB 다. 장애 뒤 역할이 바뀌면 제목도 수집값을 따라 바뀐다
 */
export function measuredDbTitle(s: FlowState, id: DbId): string {
  const d = (s.measured as MeasuredFacts).dbs[id];
  const alive = dbAlive(d);
  if (d.node === null || alive === null) return "DB";
  if (!alive) return "중지";
  return d.primary === null ? "DB" : d.primary ? "주 DB" : "예비 DB";
}

/**
 * 실측 모드의 노드 카드. 상태색 규칙(실행 초록, 중지 회색, 장애 빨강, 전환 주황, 원천 없음 점선)은 시뮬레이션과 같다.
 * 원천에 없는 값은 미수집으로 적는다
 */
export function measuredViews(s: FlowState): Record<NodeId, NodeView> {
  const m = s.measured as MeasuredFacts;
  const local = m.env === "LOCAL_DOCKER";
  if (m.flow !== "READY") return unknownViews(m, local);
  const rates = tierRates(m);
  const app = (id: AppId): NodeView => {
    const a = m.apps[id];
    const role = id === "app3"
      ? "조회 전용 · Auto Scaling"
      : a.leader
        ? `매칭 리더${a.epoch !== null ? ` · epoch ${a.epoch}` : ""} · 조회`
        : a.up
          ? "리더 대기 · 쓰기 백업 · 조회"
          : "리더 경쟁 제외";
    const [status, state] = appStatus(id, s, a);
    // 앱 노드 카드의 값 줄은 CPU 하나다(2026-09-27 사용자 요청). 좁은 카드에 거절을 함께 적으면 CPU 가 잘렸다
    return view(appName(id, a), local ? APP_CONTAINER[id] : null, role, status, state);
  };
  const users: [string, NodeState] = m.suspended === true
    ? ["쓰기 불가", "failure"]
    : m.probes.edge === null ? ["미수집", "absent"] : m.probes.edge ? ["접속 가능", "running"] : ["웹 입구 응답 없음", "failure"];
  const cdn: [string, NodeState] = m.probes.cloudfront === null
    ? ["미수집", "absent"]
    : m.probes.cloudfront ? ["검사 정상", "running"] : ["검사 실패", "failure"];
  const alb: [string, NodeState] = m.probes.alb === false
    ? ["검사 실패", "failure"]
    : rates.total !== null ? [`요청 ${perSec(rates.total)}`, "running"] : m.probes.alb ? ["검사 정상", "running"] : ["미수집", "absent"];
  const bot: [string, NodeState] = m.orders === null
    ? ["미수집", "absent"]
    : m.suspended === true ? [`주문 ${perSec(m.orders)} · 중단`, "failure"] : [`주문·취소 ${perSec(m.orders)}`, "running"];
  const obs: [string, NodeState] = m.flow !== "READY"
    ? [m.flow === "UNAVAILABLE" ? "불러오기 실패" : "연결 전", m.flow === "UNAVAILABLE" ? "failure" : "absent"]
    : m.failed.length > 0 ? ["일부 질의 실패", "transition"] : ["수집 중", "running"];
  // load-1 이 보낸 조회는 앱 계층이 받은 조회 경로 전체(수용과 거절)다
  const load: [string, NodeState] = rates.sent === null
    ? ["미수집", "absent"]
    : [`조회 ${perSec(rates.sent)}`, rates.sent >= 1 ? "running" : "stopped"];
  return {
    users: view("사용자", local ? "localhost:3000 · 웹 입구" : null, "웹 · 봇 주문", users[0], users[1]),
    cdn: view("CloudFront", local ? "localhost:3000 · 웹 입구 검사" : null, "엣지 · 정적 배포 · 캐시", cdn[0], cdn[1]),
    // 구성도 카드에는 요청 제한 거절을 적지 않는다(2026-09-27 사용자 요청). ALB 는 요청 수, 앱 노드는 CPU 하나다. 거절은
    // 팝오버의 '거절' 줄, 인스턴스 표 비고, 지표 관제의 노드별 거절 차트가 적는다
    alb: view("ALB", local ? "chaos-lb" : null, "쓰기 → 매칭 리더 · 조회 → 앱 계층", alb[0], alb[1]),
    api1: app("api1"),
    api2: app("api2"),
    app3: app("app3"),
    pg1: dbView("pg1", s),
    pg2: dbView("pg2", s),
    bot: view("bot-1 · 시장 봇", local ? "chaos-bots" : null, m.targetOps !== null ? `주문 발행 · 목표 ${rateText(m.targetOps)} OPS` : "주문 발행", bot[0], bot[1]),
    obs: view("bot-1 · 관측", local ? "chaos-prometheus · chaos-grafana" : null, "Prometheus 수집 · Grafana", obs[0], obs[1]),
    load: view("load-1", local ? LOAD_CONTAINER : null, "조회 경로 요청 합계", load[0], load[1]),
  };
}

const flowOf = (rate: number | null, tierOf: (rate: number) => EdgeFlow["tier"] = edgeTier): EdgeFlow =>
  rate === null ? { kind: "off", tier: 0, rate: 0 } : rate > 0 ? { kind: "flow", tier: tierOf(rate), rate } : { kind: "idle", tier: 0, rate: 0 };
/** 주 DB 커밋을 싣는 선. 요청 선과 단위가 달라 DB 커밋 경계로 단계를 가른다(dbEdgeTier) */
const commitFlowOf = (rate: number | null): EdgeFlow => flowOf(rate, dbEdgeTier);
const fixed = (kind: Exclude<EdgeFlow["kind"], "flow">): EdgeFlow => ({ kind, tier: 0, rate: 0 });
const checked = (ok: boolean | null): EdgeFlow => (ok === null ? fixed("off") : ok ? fixed("idle") : fixed("down"));

/**
 * 실측 모드의 연결선. 선의 세기는 잰 초당 건수다. 앱 노드로 가는 선은 그 노드가 받은 요청(조회 경로와 쓰기 경로), Primary 로
 * 가는 매칭 칸 선은 Primary 의 커밋 트랜잭션, app-3 의 조회 SQL 선은 app-3 이 받은 조회 경로 요청(모두 DB 에서 끝나는 조회)이다.
 * 커밋을 싣는 두 선(매칭 칸 → Primary, 복제)은 요청 선과 단위가 달라 DB 커밋 경계로 단계를 가른다.
 * 사용자 쪽 두 선은 잰 건수가 없어 진입 검사가 성공이면 유휴, 실패면 끊김이다
 */
export function measuredEdges(s: FlowState): Record<EdgeId, EdgeFlow> {
  const m = s.measured as MeasuredFacts;
  if (m.flow !== "READY") return Object.fromEntries(EDGE_IDS.map((id) => [id, fixed("off")])) as Record<EdgeId, EdgeFlow>;
  const rates = tierRates(m);
  const primary = DB_IDS.find((id) => s.db[id] === "primary") ?? null;
  // Primary 가 없을 때 DB 를 하나라도 잰 것이면 끊김, DB 값이 하나도 없으면 모르는 것이라 꺼짐으로 둔다
  const lost = primary === null && DB_IDS.some((id) => dbAlive(m.dbs[id]) !== null) ? fixed("down") : fixed("off");
  const tx = primary === null ? null : m.dbs[primary].xact;
  const albTo = (id: EngineId): EdgeFlow => {
    const st = s.apps[id].status;
    if (st === "killed") return fixed("down");
    if (st !== "running") return fixed("off");
    // 앱 노드로 가는 선은 그 노드가 받은 요청 전체다. 요청 제한 거절도 로드밸런서를 지나 노드까지 간다
    return flowOf(nodeTotal(m.apps[id]));
  };
  const app3 = s.apps.app3.status;
  const albRead = app3 === "running" ? flowOf(nodeTotal(m.apps.app3)) : app3 === "booting" || app3 === "draining" ? fixed("transition") : fixed("off");
  const other = primary === null ? null : primary === "pg1" ? "pg2" : "pg1";
  // 복제 스트림이 없을 때 상대 DB 를 쟀으면(살아 있든 멈췄든) 끊김, 상대를 모르면 꺼짐이다
  const repl = primary === null
    ? lost
    : m.lagBytes !== null
      ? commitFlowOf(tx ?? 1)
      : other !== null && dbAlive(m.dbs[other]) !== null ? fixed("down") : fixed("off");
  const enginesUp = ENGINE_IDS.some((id) => s.apps[id].status === "running");
  return {
    "users-cdn": checked(m.probes.edge ?? m.probes.cloudfront),
    "cdn-alb": checked(m.probes.alb),
    "bot-alb": flowOf(m.orders),
    "load-alb": flowOf(rates.sent),
    "alb-api1": albTo("api1"),
    "alb-api2": albTo("api2"),
    "alb-read": albRead,
    "app-db": primary === null ? lost : enginesUp ? commitFlowOf(tx) : fixed("off"),
    "read-db": primary === null && app3 === "running" ? lost : albRead.kind === "flow" ? flowOf(m.apps.app3.reads) : albRead,
    repl,
    "scrape-app": m.flow === "READY" ? flowOf(1) : fixed("down"),
    // DB 수집 대상이 응답하지 않으면 끊김, DB 질의가 실패해 대상 상태를 모르면 꺼짐이다
    "scrape-db": DB_IDS.some((id) => m.dbs[id].reachable === true)
      ? flowOf(1)
      : DB_IDS.some((id) => m.dbs[id].reachable === false) ? fixed("down") : fixed("off"),
  };
}

/** 연결 목록의 한 줄에 싣는 것. 양끝 이름은 화면이 붙인다 */
export const MEASURED_EDGE_WHAT: Record<EdgeId, string> = {
  "users-cdn": "웹 입구 검사",
  "cdn-alb": "로드밸런서 검사",
  "bot-alb": "주문·취소 요청",
  "load-alb": "조회 경로 요청 합계",
  "alb-api1": "쓰기 경로와 조회 경로 요청",
  "alb-api2": "쓰기 경로와 조회 경로 요청",
  "alb-read": "조회 경로 요청",
  "app-db": "주 DB 커밋 트랜잭션",
  "read-db": "app-3 조회 요청",
  repl: "복제 스트림",
  "scrape-app": "지표 수집",
  "scrape-db": "지표 수집",
};

/** 연결 목록 한 줄이 싣는 것. 복제 선은 수집한 복제 방식을 붙이고, 모르면 방식 미수집이라 적는다 */
export function measuredEdgeWhat(id: EdgeId, s: FlowState): string {
  if (id !== "repl") return MEASURED_EDGE_WHAT[id];
  const mode = (s.measured as MeasuredFacts).replMode;
  return mode === null ? "복제 스트림 · 방식 미수집" : `${replicationWord(mode)} 스트림`;
}

/** 노드 팝오버의 판독 줄. 값은 모두 마지막 격자의 실측이다 */
export function measuredRows(key: NodeId | "asg", s: FlowState, util: number | null): [string, string][] {
  const m = s.measured as MeasuredFacts;
  const local = m.env === "LOCAL_DOCKER";
  const rates = tierRates(m);
  const leader = s.holder === null ? null : m.apps[s.holder];
  // 리더 값을 낸 노드가 하나도 없으면 리더가 없는 것이 아니라 모르는 것이다
  const leaderName = s.holder !== null ? appName(s.holder, m.apps[s.holder]) : leaderOf(m.apps) === undefined ? "미수집" : "없음";
  const readers = APP_IDS.filter((id) => s.apps[id].status === "running").map((id) => appName(id, m.apps[id]));
  const pct = (v: number | null) => (v === null ? "미수집" : `${Math.round(v * 100)}%`);
  switch (key) {
    case "asg": {
      const asg = asgView(s);
      return [
        ["그룹", "app-3 한 대 · 0~1대 · 조회 전용"],
        ["앱 계층", `app-1 · app-2 고정과 app-3 · min ${asg.min} · max ${asg.max}`],
        ["앱 평균 CPU", `${util === null ? "미수집" : `${util}%`} · 증설 임계 ${pct(m.scaleOut)} · 축소 임계 ${pct(m.scaleIn)}`],
        ["목표", m.tier.desired === null ? "미수집" : `앱 ${m.tier.desired}대 · 용량 계획`],
        ["가동", asg.state === "absent" ? "미수집" : `앱 ${asg.registered}대 · Prometheus up`],
        ["조정", m.tier.mode === "ASG_POLICY" ? "AWS ASG 지표 정책이 대수를 정합니다" : "희망 대수를 바꾸면 조정기가 app-3 을 켜고 끕니다"],
      ];
    }
    case "users":
      return [
        ["진입", `웹 입구 검사 ${probeWord(m.probes.edge)}`],
        ["거래", m.suspended === null ? "미수집" : m.suspended ? "일시 중단 · 쓰기 가능 상태 기준" : "진행 중"],
        ["쓰기 리더", leaderName],
      ];
    case "cdn":
      return [
        ["검사", probeWord(m.probes.cloudfront)],
        ...(local ? [["로컬", "CloudFront 없음 · 같은 일을 하는 웹 입구(localhost:3000)의 정적 파일 검사"] as [string, string]] : []),
        ["AWS", "정적 웹은 S3, API·WebSocket 은 ALB 로"],
      ];
    case "alb":
      return [
        ["검사", probeWord(m.probes.alb)],
        ...(local ? [["로컬", "chaos-lb · nginx"] as [string, string]] : []),
        ["쓰기 경로", `${perSec(rates.writes)} · 매칭 리더 ${leaderName}`],
        ["조회 경로", `${rates.reads === null ? "미수집" : `수용 ${perSec(rates.reads)}`} → ${readers.join(" · ") || "받는 노드 없음"}`],
        ["거절", rates.rejected === null ? "미수집" : `${perSec(rates.rejected)} · 앱 요청 제한(429)`],
        ["조회 분산", "least_conn · 연결이 적은 노드로"],
      ];
    case "api1":
    case "api2":
    case "app3": {
      const a = m.apps[key];
      const rows: [string, string][] = [
        ["노드", `${appName(key, a)}${local ? ` · ${APP_CONTAINER[key]}` : ""}`],
        ["CPU", a.cpu === null ? "미수집" : `${Math.round(a.cpu)}%`],
        ["조회 경로", a.reads === null ? "미수집" : `수용 ${perSec(a.reads)}`],
        ["거절", a.rejected === null ? "미수집" : `${perSec(a.rejected)} · 요청 제한(429)`],
      ];
      if (key !== "app3") {
        rows.push(["쓰기 경로", perSec(a.writes)]);
        rows.push(["리더", a.leader === null ? "미수집" : a.leader ? `보유${a.epoch !== null ? ` · epoch ${a.epoch}` : ""}` : "대기"]);
        rows.push(["리스 갱신", a.leaseAge === null ? "미수집" : `${a.leaseAge.toFixed(1)}초 전`]);
      } else {
        rows.push(["목표", m.tier.desired === null ? "미수집" : `앱 ${m.tier.desired}대`]);
      }
      rows.push(["쓰기 가능", a.write === null ? "미수집" : WRITE_WORD[a.write]]);
      return rows;
    }
    case "pg1":
    case "pg2": {
      const d = m.dbs[key];
      return [
        ["노드", dbName(key, d)],
        ["역할", d.primary === null ? "미수집" : d.primary ? "주 DB · recovery 아님" : "예비 DB · recovery 중"],
        ["연결", d.connections === null ? "미수집" : `${d.connections}`],
        ["커밋", perSec(d.xact)],
        ["복제 지연", m.lagBytes === null ? "스트림 없음" : `${Math.round(m.lagBytes / 1024)} KB`],
        ["복제 방식", replicationWord(m.replMode)],
        ["승격", promotionWord(m.promotion)],
        ["전환", "역할은 recovery 지표로 판정 · 콘솔은 전환을 실행하지 않음"],
      ];
    }
    case "bot":
      return [
        ["주문·취소", `${perSec(m.orders)} · 앱 계층이 받은 요청`],
        ["목표", m.targetOps === null ? "미수집" : `${rateText(m.targetOps)} OPS · ${m.botSource === "published" ? "봇 노드 발행" : "관리 JVM"}`],
        ["체결", perSec(m.trades)],
        ["쓰기 리더", `${leaderName}${leader?.write ? ` · ${WRITE_WORD[leader.write]}` : ""}`],
      ];
    case "obs":
      return [
        ["수집", "Prometheus · 2초 격자 · 최근 150초"],
        ["실패한 질의", m.failed.length === 0 ? "없음" : m.failed.join(", ")],
        ...(local ? [["로컬", "chaos-prometheus · chaos-grafana"] as [string, string]] : []),
      ];
    case "load":
      return [
        ["조회 경로", perSec(rates.sent)],
        ["수용 · 거절", `${perSec(rates.reads)} · ${perSec(rates.rejected)}`],
        ["값", "load-1 자체 지표는 없음 · 앱 계층이 받은 조회 경로 요청의 합계"],
        ...(local ? [["로컬", "grafana/k6 컨테이너 · 초당 요청 고정 조회 부하"] as [string, string]] : []),
      ];
  }
}

/** 인스턴스 표의 비고. 다음에 볼 실측 값 한 줄이다 */
export function measuredNote(s: FlowState, id: Exclude<NodeId, "users"> | "asg"): string {
  const m = s.measured as MeasuredFacts;
  const rates = tierRates(m);
  switch (id) {
    case "cdn":
      return `검사 ${probeWord(m.probes.cloudfront)}${m.env === "LOCAL_DOCKER" ? " · 로컬은 웹 입구" : ""}`;
    case "alb": {
      const reject = rejectText(rates.rejected);
      return `쓰기 ${perSec(rates.writes)} · 조회 ${perSec(rates.reads)}${reject ? ` · ${reject}` : ""}`;
    }
    case "api1":
    case "api2":
    case "app3": {
      const a = m.apps[id];
      const lead = a.leader ? ` · 리더${a.epoch !== null ? ` epoch ${a.epoch}` : ""}` : "";
      const reject = rejectText(a.rejected);
      const rej = reject ? ` · ${reject}` : "";
      return id === "app3" ? `조회 ${perSec(a.reads)}${rej}` : `쓰기 ${perSec(a.writes)} · 조회 ${perSec(a.reads)}${rej}${lead}`;
    }
    case "pg1":
    case "pg2": {
      const d = m.dbs[id];
      // 역할은 recovery 값으로만 가른다. 멈췄거나 값이 없으면 판정 근거만 적는다
      if (dbAlive(d) !== true || d.primary === null) return "역할은 recovery 지표로 판정";
      if (d.primary) return `연결 ${d.connections ?? "미수집"} · 커밋 ${perSec(d.xact)}`;
      const mode = m.replMode === "SYNC" ? "엄격 동기 · " : m.replMode === "ASYNC" ? "비동기 · " : "";
      return m.lagBytes === null ? "복제 끊김" : `${mode}복제 지연 ${Math.round(m.lagBytes / 1024)} KB`;
    }
    case "bot":
      return `목표 ${m.targetOps === null ? "미수집" : `${rateText(m.targetOps)} OPS`} · 체결 ${perSec(m.trades)}`;
    case "obs":
      return m.failed.length === 0 ? "Prometheus · 2초 격자" : `실패한 질의 ${m.failed.length}`;
    case "load":
      return "앱 계층이 받은 조회 경로 합계";
    case "asg": {
      const asg = asgView(s);
      return `앱 목표 ${m.tier.desired ?? "미수집"} · 가동 ${asg.state === "absent" ? "미수집" : asg.registered}`;
    }
  }
}
