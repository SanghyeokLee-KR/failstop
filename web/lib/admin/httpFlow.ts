/**
 * 서비스 흐름 실측 조회(GET /admin/v1/msp/flow, API 설계 §6-F '흐름')의 응답을 화면 형식으로 옮긴다. 십진 문자열은 수로 읽고,
 * 읽지 못한 값(null)은 null 그대로 둔다. 금액이 아니라 표시에만 쓰는 지표라 수로 옮겨도 된다.
 */

import type {
  FlowMeasured,
  FlowMeasuredApp,
  FlowMeasuredBots,
  FlowMeasuredDb,
  FlowMeasuredSeries,
  FlowMeasuredTier,
  FlowWriteState,
  OpsEnvironment,
  OpsSection,
} from "./types";

/** 격자 배열. 값이 없는 점은 null, 질의가 돌지 않았거나 실패하면 배열 전체가 null 이다 */
type Wire<T> = (T | null)[] | null | undefined;

interface WireSection<T> {
  state?: string;
  data?: T;
  source?: string;
  reason?: string;
}

interface WireApp {
  node?: string;
  role?: string | null;
  up?: Wire<boolean>;
  cpu_ratio?: Wire<string>;
  read_rate?: Wire<string>;
  read_rejected_rate?: Wire<string>;
  write_rate?: Wire<string>;
  leader?: Wire<boolean>;
  epoch?: Wire<number>;
  write_state?: Wire<string>;
  lease_age_seconds?: Wire<string>;
}

interface WireDb {
  node?: string;
  reachable?: Wire<boolean>;
  up?: Wire<boolean>;
  in_recovery?: Wire<boolean>;
  connections?: Wire<number>;
  xact_rate?: Wire<string>;
}

interface WireFlowData {
  apps?: WireApp[];
  dbs?: WireDb[] | null;
  trades_per_second?: Wire<string>;
  order_rate?: Wire<string>;
  read_rate?: Wire<string>;
  read_rejected_rate?: Wire<string>;
  replication_lag_bytes?: Wire<string>;
  replication_mode?: Wire<string>;
  promotion?: Wire<string>;
  probes?: { service?: string; success?: Wire<boolean> }[] | null;
  failed?: string[];
}

interface WireTier {
  mode?: string;
  min?: number;
  max?: number;
  desired?: Wire<number>;
}

interface WireBots {
  source?: string;
  target_ops?: string | null;
  sampled_at?: string | null;
}

export interface WireFlow {
  environment?: string;
  generated_at?: string;
  window?: { start?: string; end?: string; step_seconds?: number };
  flow?: WireSection<WireFlowData>;
  read_tier?: WireSection<WireTier>;
  bots?: WireSection<WireBots>;
}

const ENVIRONMENTS: OpsEnvironment[] = ["LOCAL_DOCKER", "SCHOOL_AWS", "PERSONAL_AWS", "UNKNOWN"];
const WRITE_STATES: FlowWriteState[] = ["ACTIVE", "SUSPENDED", "UNREACHABLE"];
/** 원천 이름을 화면 말로. 칸이 연결 전·실패일 때 무엇을 붙이면 되는지 적는다 */
const SOURCE_WORD: Record<string, string> = {
  prometheus: "Prometheus",
  read_capacity: "앱 계층 용량 계획",
  bot_fleet: "봇 플릿 상태",
};

/** 점 수를 격자에 맞춘다. 서버가 보낸 배열 길이가 다르면 모자란 점은 null, 넘는 점은 버린다 */
function fit<T, U>(values: Wire<T>, points: number, read: (v: T) => U | null): (U | null)[] | null {
  if (!Array.isArray(values)) return null;
  const out: (U | null)[] = [];
  for (let i = 0; i < points; i += 1) {
    const v = values[i];
    out.push(v === null || v === undefined ? null : read(v));
  }
  return out;
}

function decimal(v: string): number | null {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

const flag = (v: boolean) => v;
const int = (v: number) => (Number.isFinite(v) ? v : null);

function sectionOf<W, T>(w: WireSection<W> | undefined, what: string, read: (d: W) => T): OpsSection<T> {
  if (w?.state === "READY" && w.data !== undefined) return { state: "READY", data: read(w.data) };
  const source = SOURCE_WORD[w?.source ?? ""] ?? w?.source ?? what;
  if (w?.state === "UNAVAILABLE") return { state: "UNAVAILABLE", source, reason: w.reason ?? "사유 없음" };
  return { state: "NOT_CONNECTED", source };
}

function appOf(a: WireApp, points: number): FlowMeasuredApp {
  return {
    node: a.node ?? "",
    role: a.role ?? null,
    up: fit(a.up, points, flag) ?? new Array<boolean | null>(points).fill(null),
    cpu: fit(a.cpu_ratio, points, decimal),
    reads: fit(a.read_rate, points, decimal),
    readsRejected: fit(a.read_rejected_rate, points, decimal),
    writes: fit(a.write_rate, points, decimal),
    leader: fit(a.leader, points, flag),
    epoch: fit(a.epoch, points, int),
    write: fit(a.write_state, points, (s: string) => (WRITE_STATES as string[]).includes(s) ? (s as FlowWriteState) : null),
    leaseAge: fit(a.lease_age_seconds, points, decimal),
  };
}

function dbOf(d: WireDb, points: number): FlowMeasuredDb {
  return {
    node: d.node ?? "",
    reachable: fit(d.reachable, points, flag) ?? new Array<boolean | null>(points).fill(null),
    up: fit(d.up, points, flag),
    inRecovery: fit(d.in_recovery, points, flag),
    connections: fit(d.connections, points, int),
    xact: fit(d.xact_rate, points, decimal),
  };
}

function seriesOf(d: WireFlowData, points: number): FlowMeasuredSeries {
  return {
    apps: (d.apps ?? []).filter((a) => a.node).map((a) => appOf(a, points)),
    dbs: d.dbs == null ? null : d.dbs.filter((x) => x.node).map((x) => dbOf(x, points)),
    trades: fit(d.trades_per_second, points, decimal),
    orders: fit(d.order_rate, points, decimal),
    readRate: fit(d.read_rate, points, decimal),
    readRejectedRate: fit(d.read_rejected_rate, points, decimal),
    replLagBytes: fit(d.replication_lag_bytes, points, decimal),
    replicationMode: fit(d.replication_mode, points, (v: string) => (v === "SYNC" || v === "ASYNC" ? v : null)),
    promotion: fit(d.promotion, points, (v: string) => (v === "AUTO" || v === "MANUAL" ? v : null)),
    probes: d.probes == null
      ? null
      : d.probes.filter((p) => p.service).map((p) => ({ service: p.service ?? "", success: fit(p.success, points, flag) ?? [] })),
    failed: d.failed ?? [],
  };
}

function tierOf(t: WireTier, points: number): FlowMeasuredTier {
  return {
    mode: t.mode === "ASG_POLICY" ? "ASG_POLICY" : "CAPACITY_PLAN",
    min: typeof t.min === "number" ? t.min : null,
    max: typeof t.max === "number" ? t.max : null,
    desired: fit(t.desired, points, int),
  };
}

function botsOf(b: WireBots): FlowMeasuredBots {
  return {
    source: b.source ?? "unknown",
    targetOps: b.target_ops == null ? null : decimal(b.target_ops),
    sampledAt: b.sampled_at ?? null,
  };
}

export function flowMeasuredOf(w: WireFlow): FlowMeasured {
  const step = w.window?.step_seconds && w.window.step_seconds > 0 ? w.window.step_seconds : 2;
  const startMs = Date.parse(w.window?.start ?? "");
  const endMs = Date.parse(w.window?.end ?? "");
  const start = Number.isFinite(startMs) ? Math.floor(startMs / 1000) : Math.floor(Date.now() / 1000);
  const points = Number.isFinite(startMs) && Number.isFinite(endMs) && endMs >= startMs
    ? Math.floor((endMs - startMs) / 1000 / step) + 1
    : 0;
  return {
    environment: ENVIRONMENTS.find((e) => e === w.environment) ?? "UNKNOWN",
    generatedAt: w.generated_at ?? new Date().toISOString(),
    start,
    step,
    points,
    flow: sectionOf(w.flow, "Prometheus", (d) => seriesOf(d, points)),
    readTier: sectionOf(w.read_tier, "앱 계층 용량 계획", (d) => tierOf(d, points)),
    bots: sectionOf(w.bots, "봇 플릿 상태", botsOf),
  };
}

/** 관리 API에 흐름 조회가 없는 배포(404). 원천 상태를 모르는 채로 모든 칸을 연결 전으로 둔다 */
export function absentFlow(now = Date.now()): FlowMeasured {
  const source = "관리 API 흐름 조회(GET /admin/v1/msp/flow)";
  return {
    environment: "UNKNOWN",
    generatedAt: new Date(now).toISOString(),
    start: Math.floor(now / 1000),
    step: 2,
    points: 0,
    flow: { state: "NOT_CONNECTED", source },
    readTier: { state: "NOT_CONNECTED", source },
    bots: { state: "NOT_CONNECTED", source },
  };
}
