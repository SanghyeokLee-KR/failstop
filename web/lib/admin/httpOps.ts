/**
 * 관제센터 서버 계약(API 설계 §6-F, /admin/v1/msp/**)을 관제 판(ControlCenter)으로 옮기는 어댑터.
 *
 * 서버는 한 판(overview)과 세부 조회를 따로 낸다. 화면은 한 판 모델 하나를 읽으므로, 여기서 한 판 응답에 세부 조회
 * 몇 건(AI 조치 요청, 판단, 한도, 임계값 이력, 사건, 1·6시간 추세, 보고 전송 상태)을 붙여 판을 만든다.
 *
 * 관리 요청 한 건이 운영자 버킷에서 10토큰을 쓰고 계약 기본 버킷은 분당 6건이다(§6-F 활성 조건). 한 판을 15초마다
 * 읽으면 그것만으로 분당 4건이라, 세부 조회는 필요할 때만 한다. 한 판에서 서명(열린 조치 수, 마지막 판단, 임계값 판,
 * 열린 사건 등)이 바뀌었거나 수명이 지난 것만 고르고, 한 번 읽을 때 한 건(서명이 바뀐 것이 있으면 두 건)까지만
 * 부른다. 첫 판만 필요한 것을 한꺼번에 읽는다. 부르지 못한 세부는 지난 값을 쓰고, 한 번도 읽지 못했으면 그 사실을
 * 판에 적는다. 값을 지어내지 않는다.
 *
 * 서버에 없는 값(거래 칸, 인증서 만료, 호스트별 마켓 묶음, AI 서버 장비 이름 등)은 원천 연결 전이다.
 */

import { isoNow } from "../format";
import { AI_LIMIT_DEFS, actionKey, isStricter } from "./aiPolicy";
import { requestedGuide } from "./aiChat";
import { DB_STATS_SOURCE } from "./dbFacts";
import { runbookForAlert } from "./runbooks";
import { THRESHOLD_META, isThresholdKey } from "./thresholdMeta";
import type {
  AiActionKind,
  AiChatEvidence,
  AiChatReply,
  AiCheckRule,
  AiCheckState,
  AiDesk,
  AiEvidence,
  AiJudgment,
  AiJudgmentTrace,
  AiLimit,
  AiLimitChange,
  AiLimitKey,
  AiProposal,
  AiProposalStatus,
  AiServer,
  AlertSeverity,
  ChangeDesk,
  ControlCenter,
  HostAsset,
  Incident,
  IncidentEvent,
  IncidentEventKind,
  IncidentLog,
  MaintenanceWindow,
  OpsActor,
  OpsAlert,
  OpsBackup,
  OpsCollect,
  OpsEnvironment,
  OpsHost,
  OpsHostRole,
  OpsJobs,
  OpsRef,
  OpsReport,
  OpsSection,
  OpsSourceName,
  OpsSourceState,
  OpsSummary,
  OpsThreshold,
  OpsTrendSeries,
  OpsTrends,
  ReadNodeState,
  ReadTier,
  Replication,
  ReportDesk,
  ReportJobStatus,
  ThresholdChange,
  ThresholdKey,
  ThresholdRegistry,
  ThresholdVia,
  TrendMetric,
  TrendRange,
} from "./types";

/* ===== 와이어 형식(API §6-F). 모든 필드를 선택으로 두고 모르는 값은 버린다 ===== */

export interface WireMeasure {
  value?: string | number | null;
  sampled_at?: string | null;
  age_seconds?: number | null;
  collect?: string | null;
}

export interface WireSection<T> {
  state?: string;
  source?: string | null;
  reason?: string | null;
  data?: T | null;
}

export interface WireSource {
  name?: string;
  state?: string;
  last_poll_at?: string | null;
  reason?: string | null;
  via?: string;
  delivery_enabled?: boolean;
  last_sent_at?: string | null;
  pending?: number;
  provider?: string;
  model?: string;
  transport?: string;
}

interface WireSummary {
  availability?: WireMeasure;
  p95_seconds?: WireMeasure;
  error_ratio?: WireMeasure;
  slo?: { availability?: string | number | null; p95_seconds?: string | number | null; error_ratio?: string | number | null };
}

interface WireHost {
  node?: string;
  role?: string;
  actual_role?: string | null;
  instance_type?: string | null;
  az?: string | null;
  up?: WireMeasure;
  cpu_ratio?: WireMeasure;
  memory_ratio?: WireMeasure;
  root_free_ratio?: WireMeasure;
  cpu_credits?: WireMeasure;
}

interface WireReadTier {
  mode?: string;
  min?: number;
  desired?: number;
  max?: number;
  observed?: number | null;
  avg_cpu_ratio?: WireMeasure;
  scaling_owner?: string;
}

interface WireReplication {
  primary?: string | null;
  standby?: string | null;
  stream?: string | null;
  lag_seconds?: WireMeasure;
  lag_bytes?: WireMeasure;
  label_mismatch?: boolean;
}

export interface WireAlert {
  fingerprint?: string;
  alertname?: string;
  severity?: string;
  node?: string | null;
  role?: string | null;
  component?: string | null;
  mountpoint?: string | null;
  summary?: string | null;
  starts_at?: string;
  state?: string;
  incident_id?: number | string | null;
  threshold_key?: string | null;
  acked_by?: string | null;
  acked_at?: string | null;
  silence?: { silence_id?: string; ends_at?: string; actor?: string; by?: string } | null;
}

interface WireOps {
  db_backup?: WireSection<{ last_success_at?: string | null; verify_ok?: boolean | null }>;
  databasus?: WireSection<unknown>;
  certificate?: WireSection<unknown>;
  daily_report?: WireSection<{ last_triggered_at?: string | null }>;
}

interface WireThreshold {
  key?: string;
  unit?: string;
  value?: string | null;
  default?: string | null;
  min?: string;
  max?: string;
  ai_max_step?: string;
  version?: number;
  updated_by?: string | null;
  updated_at?: string | null;
  in_use?: boolean;
  rule_value?: string | null;
}

interface WireAiBrief {
  state?: string;
  source?: string;
  reason?: string | null;
  mode?: string;
  stopped?: boolean;
  stop?: { by?: string; at?: string; reason?: string | null } | null;
  auto?: { since?: string; valid_until?: string; enabled_by?: string; activation?: number; auto_used?: number; auto_cap?: number } | null;
  open_actions?: number;
  last_decision?: { id?: number | string; decided_at?: string; outcome?: string } | null;
}

export interface WireAiServer {
  state?: string;
  source?: string;
  reason?: string | null;
  provider?: string;
  model?: string;
  transport?: string;
  server?: {
    version?: string | null;
    model_loaded?: boolean | null;
    model_listed?: boolean | null;
    loaded_models?: { name?: string; size_vram_bytes?: number }[];
    checked_at?: string | null;
  } | null;
  calls?: {
    window?: number;
    count?: number;
    failures?: number;
    latency_ms?: { p50?: number | null; p95?: number | null };
    last_success_at?: string | null;
    last_failure?: { at?: string; code?: string } | null;
  } | null;
}

interface WireTrendData {
  unit?: string;
  aggregate?: string;
  bucket_seconds?: number;
  points?: [string, string | number | null][];
  current?: string | number | null;
  previous?: string | number | null;
}

export interface WireTrends {
  range?: string;
  step_seconds?: number;
  series?: Partial<Record<"availability" | "p95_seconds" | "error_ratio" | "active_alerts" | "incidents", WireSection<WireTrendData>>>;
}

export interface WireOverview {
  schema_version?: string;
  environment?: string;
  generated_at?: string;
  sources?: WireSource[];
  summary?: WireSection<WireSummary>;
  hosts?: WireSection<WireHost[]>;
  read_tier?: WireSection<WireReadTier>;
  replication?: WireSection<WireReplication>;
  alerts?: WireSection<WireAlert[]> & { last_poll_at?: string | null };
  incidents?: WireSection<{ today_count?: number; today_resolved_mean_seconds?: number | null; open_ids?: (number | string)[] }>;
  ops?: WireSection<WireOps>;
  thresholds?: WireSection<{ items?: WireThreshold[]; gauge_scraped?: boolean | null }>;
  ai?: WireAiBrief;
  ai_server?: WireAiServer;
  trends?: WireTrends;
}

export interface WireAiMode {
  mode?: string;
  stopped?: boolean;
  stop?: { by?: string; at?: string; reason?: string | null } | null;
  auto?: { since?: string; valid_until?: string; enabled_by?: string; reason?: string; approval_id?: number | string } | null;
  activation?: { seq?: number; started_at?: string; auto_used?: number; auto_cap?: number; threshold_used?: Record<string, number> } | null;
  cooldowns?: Record<string, string | null>;
  observe_until?: string | null;
  llm?: { state?: string; provider?: string; model?: string; transport?: string };
}

export interface WireAction {
  id?: number | string;
  kind?: string;
  verb?: string;
  target?: string | null;
  identity_key?: string;
  params?: Record<string, unknown> | null;
  confidence?: string | number | null;
  reason?: string | null;
  status?: string;
  hold_rule?: string | null;
  hold_reason?: string | null;
  consecutive?: number;
  auto?: boolean;
  deferred_until?: string | null;
  decided_by?: string | null;
  decided_at?: string | null;
  approval_id?: number | string | null;
  executed_by?: string | null;
  executed_at?: string | null;
  result?: Record<string, unknown> | null;
  observe_until?: string | null;
  observe_outcome?: string | null;
  reverted_by?: string | null;
  reverted_at?: string | null;
  created_at?: string;
  expires_at?: string | null;
  last_decision_id?: number | string | null;
  revertible?: boolean;
}

export interface WireDecision {
  id?: number | string;
  decided_at?: string;
  mode?: string;
  outcome?: string;
  error?: string | null;
  model?: string | null;
  evidence?: Record<string, unknown> | null;
  situation?: string | null;
  reason?: string | null;
  confidence?: string | number | null;
  latency_ms?: number | null;
  incident_id?: number | string | null;
  actions?: WireAction[];
  dropped?: { verb?: string; action?: string; code?: string; reason?: string; target?: string }[];
}

export interface WireLimitItem {
  key?: string;
  value?: number;
  effective?: number;
  pending?: boolean;
  min?: number;
  max?: number;
  tighter?: string;
  unit?: string;
  version?: number;
  updated_by?: string | null;
  updated_at?: string | null;
}

export interface WireLimitChange {
  id?: number | string;
  key?: string;
  from?: number;
  to?: number;
  changed_by?: string;
  approval_id?: number | string | null;
  reason?: string | null;
  changed_at?: string;
}

export interface WireThresholdChange {
  id?: number | string;
  key?: string;
  from?: string | null;
  to?: string | null;
  via?: string;
  changed_by?: string;
  approval_id?: number | string | null;
  ai_action_id?: number | string | null;
  revert_of?: number | string | null;
  reason?: string | null;
  changed_at?: string;
}

export interface WireIncident {
  id?: number | string;
  fingerprint?: string;
  alertname?: string;
  severity?: string;
  node?: string | null;
  component?: string | null;
  summary?: string | null;
  started_at?: string;
  resolved_at?: string | null;
  status?: string;
}

export interface WireIncidentEvent {
  id?: number | string;
  kind?: string;
  actor_type?: string;
  actor_id?: string | null;
  text?: string | null;
  at?: string;
}

export interface WireChatReply {
  state?: string;
  reason?: string | null;
  preface?: string | null;
  answer?: string;
  citations?: { id?: string; label?: string; value?: string | null; collected_at?: string | null }[];
  evidence?: { collected_at?: string | null };
  model?: string | null;
}

export interface WireReport {
  id?: string | number;
  kind?: string;
  created_at?: string;
  sent_at?: string | null;
  status?: string;
  title?: string;
  severity?: string | null;
  summary?: string | null;
  alert_state?: string | null;
  targets?: unknown;
  links?: { pdf?: string | null; html?: string | null } | null;
}

export interface WireReportsStatus {
  state?: string;
  reason?: string | null;
  data?: {
    reporting?: {
      delivery_enabled?: boolean;
      counts?: Partial<Record<"pending" | "processing" | "queued" | "sending" | "sent" | "dead", number>>;
      last_sent_at?: string | null;
      last_attempt?: { at?: string; status?: string; error?: string | null } | null;
      last_daily_sent_at?: string | null;
    } | null;
    outbox?: Record<string, unknown> | null;
  };
}

/* ===== 값 옮기기 ===== */

/** 십진 문자열이나 숫자를 수로. 못 읽으면 null이고 0으로 채우지 않는다 */
export function dec(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

/** 비율을 백분율로. 화면이 쓰는 자리수로 반올림한다 */
function pct(v: unknown, digits = 0): number | null {
  const n = dec(v);
  if (n === null) return null;
  const f = 10 ** digits;
  return Math.round(n * 100 * f) / f;
}

function mval(m: WireMeasure | undefined): number | null {
  return m ? dec(m.value) : null;
}

function collectOf(m: WireMeasure | undefined): OpsCollect {
  return m?.collect === "OK" || m?.collect === "STALE" ? m.collect : "MISSING";
}

/** 비율을 소수 넷째 자리까지의 평문 십진 표기로. 서버의 티켓 대상과 같은 모양(끝 0 없음)이다 */
export function plainDecimal(n: number, digits = 4): string {
  const s = n.toFixed(digits);
  return s.includes(".") ? s.replace(/0+$/, "").replace(/\.$/, "") : s;
}

const SOURCE_WORD: Record<string, string> = {
  prometheus: "Prometheus 조회(exchange.msp.prometheus-url)",
  alertmanager: "Alertmanager(exchange.msp.alertmanager-url)",
  llm: "AI 판단 모델(exchange.msp.llm.base-url)",
  databasus: "Databasus API",
  read_capacity: "앱 계층 용량 원천(exchange.msp.read-capacity.mode)",
  certificate: "인증서 만료 원천",
  reporting: "보고 서비스(exchange.msp.reporting.url)",
  teams: "보고 서비스(exchange.msp.reporting.url)",
};

/** 서버의 실패 분류 코드를 사람이 읽는 말로. 모르는 코드는 그대로 적는다 */
export const REASON_WORD: Record<string, string> = {
  timeout: "시간 초과",
  unreachable: "연결 불가",
  http_status: "HTTP 오류",
  response_limit: "응답 크기 초과",
  query_failed: "질의 실패",
  bad_response: "응답 형식 오류",
  not_polled: "아직 읽지 않음",
  remote_address: "허용하지 않은 주소",
  invalid_output: "모델 출력 형식 오류",
  model_loading: "모델 적재 중",
};

export function reasonWord(code: string | null | undefined): string {
  if (!code) return "원인 미확인";
  return REASON_WORD[code] ?? code;
}

function sourceWord(source: string | null | undefined, fallback: string): string {
  if (!source) return fallback;
  return SOURCE_WORD[source] ?? source;
}

/** 서버 칸 하나를 화면 칸으로. READY만 값을 옮기고, 연결 전과 실패는 원천과 사유를 그대로 적는다 */
export function section<W, T>(w: WireSection<W> | undefined, fallback: string, map: (d: W) => T): OpsSection<T> {
  if (!w) return { state: "NOT_CONNECTED", source: `${fallback} · 서버 응답에 없음` };
  if (w.state === "READY" && w.data !== undefined && w.data !== null) return { state: "READY", data: map(w.data) };
  if (w.state === "UNAVAILABLE") return { state: "UNAVAILABLE", source: sourceWord(w.source, fallback), reason: reasonWord(w.reason) };
  return { state: "NOT_CONNECTED", source: `${sourceWord(w.source, fallback)} 설정 없음` };
}

const nc = (source: string): OpsSection<never> => ({ state: "NOT_CONNECTED", source });

/* ===== 원천·요약·호스트 ===== */

const SOURCE_NAMES: readonly OpsSourceName[] = ["prometheus", "alertmanager", "databasus", "llm", "teams"];
const ENVIRONMENTS: readonly OpsEnvironment[] = ["LOCAL_DOCKER", "SCHOOL_AWS", "PERSONAL_AWS", "UNKNOWN"];

/**
 * 원천 상태. 모르는 이름과 상태는 버리고 짐작하지 않는다. 확인 시각은 경보 폴러면 마지막 폴링, Teams면 마지막 전송,
 * 그 밖의 붙은 원천은 한 판을 만든 시각이다
 */
export function sourcesOf(w: WireOverview): OpsSourceState[] {
  const out: OpsSourceState[] = [];
  for (const s of w.sources ?? []) {
    const name = SOURCE_NAMES.find((n) => n === s.name);
    const state = s.state === "READY" || s.state === "NOT_CONNECTED" || s.state === "UNAVAILABLE" ? s.state : null;
    if (!name || !state) continue;
    const checkedAt = name === "alertmanager" ? s.last_poll_at ?? null : state === "READY" ? w.generated_at ?? null : null;
    out.push({ name, state, checkedAt, reason: state === "UNAVAILABLE" ? s.reason ?? null : null });
  }
  return out;
}

function summaryOf(s: WireSummary): OpsSummary {
  const p95 = mval(s.p95_seconds);
  const sloP95 = dec(s.slo?.p95_seconds);
  return {
    availabilityPct: pct(mval(s.availability), 2),
    p95Ms: p95 === null ? null : Math.round(p95 * 1000),
    errorRatePct: pct(mval(s.error_ratio), 2),
    slo: {
      availabilityPct: pct(s.slo?.availability, 2),
      p95Ms: sloP95 === null ? null : Math.round(sloP95 * 1000),
      errorRatePct: pct(s.slo?.error_ratio, 2),
    },
  };
}

/**
 * 호스트 역할. DB는 설정의 배치 역할이 아니라 recovery 지표로 정한 실제 역할을 쓴다. 실제 역할을 모르면(지표 없음)
 * 노드 이름으로 자리만 정하고 수집 상태는 서버가 준 그대로 둔다
 */
function hostRole(h: WireHost): OpsHostRole {
  if (h.role === "DB") {
    if (h.actual_role === "PRIMARY") return "DB_PRIMARY";
    if (h.actual_role === "STANDBY") return "DB_STANDBY";
    return /standby|replica/i.test(h.node ?? "") ? "DB_STANDBY" : "DB_PRIMARY";
  }
  return h.role === "BOT_OBS" ? "BOT_OBS" : "APP";
}

function hostOf(h: WireHost): OpsHost {
  // 대상이 내려가 있으면(up 0) 값이 있어도 수집 끊김으로 본다
  const down = mval(h.up) === 0;
  const free = mval(h.root_free_ratio);
  return {
    id: h.node ?? "",
    role: hostRole(h),
    instanceType: h.instance_type ?? "",
    az: h.az ?? "",
    collect: down ? "MISSING" : collectOf(h.up),
    collectedAt: h.up?.sampled_at ?? null,
    cpuPct: pct(mval(h.cpu_ratio)),
    memPct: pct(mval(h.memory_ratio)),
    diskUsedPct: free === null ? null : Math.round((1 - free) * 100),
    cpuCredits: mval(h.cpu_credits),
    // 마켓 묶음은 서버 한 판이 싣지 않는다
    partition: null,
    // 자산 칸은 서버 경로(GET /admin/v1/msp/hosts/inventory)가 3라운드 계약 초안이라 값마다 원천 연결 전이다
    asset: { ...NO_ASSET },
  };
}

/** 자산 원천이 없는 노드의 자산 칸. 값마다 null이고 화면이 칸마다 원천 이름을 적는다(관리자 설계 4-10-3) */
export const NO_ASSET: HostAsset = { os: null, kernel: null, bootedAt: null, inodeUsedPct: null, failedUnits: null, securityUpdates: null };

/**
 * 앱 계층(ADR 0030 사용자 정정). 서버의 용량 계획(capacity_plan)은 앱 계층 전체 대수이고 min 대까지가 늘 켜진 노드,
 * 그 위가 자동으로 켜고 끄는 자리다. 관측 대수로 그 자리가 켜졌는지, 켜지는 중인지, 내려가는 중인지를 가른다
 */
function readTierOf(r: WireReadTier): OpsSection<ReadTier> {
  if (r.mode === "ASG_POLICY") {
    return nc("AWS Auto Scaling이 대수를 소유합니다(대상당 요청 수). 대수 조회 원천 연결 전");
  }
  const min = dec(r.min) ?? 2;
  const max = dec(r.max) ?? min;
  const desired = dec(r.desired) ?? min;
  const observed = dec(r.observed);
  const fixed = Array.from({ length: min }, (_, i) => `app-${i + 1}`);
  const nodes = Array.from({ length: Math.max(0, max - min) }, (_, i) => {
    const n = min + i + 1;
    const want = desired >= n;
    const have = observed !== null && observed >= n;
    const state: ReadNodeState = want ? (have ? "IN_SERVICE" : "PENDING") : have ? "DRAINING" : "OFF";
    return { id: `app-${n}`, state, cpuPct: null, instanceType: "", az: "", ...(state === "OFF" ? {} : { asset: { ...NO_ASSET } }) };
  });
  return {
    state: "READY",
    data: {
      min,
      max,
      desired,
      fixed,
      serving: observed ?? 0,
      nodes,
      avgCpuPct: pct(mval(r.avg_cpu_ratio)),
      // 켜진 시간을 세는 원천이 없다(AWS 활동 기록·용량 활동 기록 연결 전)
      onSecondsToday: null,
    },
  };
}

function replicationOf(r: WireReplication): Replication {
  const stream = (r.stream ?? "").toUpperCase();
  return {
    primary: r.primary ?? "미확인",
    standby: r.standby ?? "미확인",
    stream: stream === "STREAMING" ? "STREAMING" : stream === "" ? "STOPPED" : "CATCHUP",
    lagBytes: mval(r.lag_bytes),
    lagSec: mval(r.lag_seconds),
  };
}

/* ===== 경보와 사건 ===== */

/** 등급을 모르는 경보(other)는 보류 규칙에서 critical과 같게 다루므로(§6-F) 화면도 장애로 그리고 문구에 적는다 */
function severityOf(s: string | undefined): { severity: AlertSeverity; unknown: boolean } {
  if (s === "critical" || s === "warning" || s === "info") return { severity: s, unknown: false };
  return { severity: "critical", unknown: true };
}

function alertRef(a: WireAlert, hostIds: Set<string>): OpsRef | null {
  if (a.node && hostIds.has(a.node)) return { kind: "HOST", id: a.node };
  const key = a.threshold_key ?? "";
  if (key.startsWith("read.")) return { kind: "READ_TIER" };
  if (key === "db.replication_lag_alert") return { kind: "REPLICATION" };
  if (key === "backup.delay_alert") return { kind: "BACKUP" };
  return null;
}

function alertOf(a: WireAlert, hostIds: Set<string>): OpsAlert {
  const sev = severityOf(a.severity);
  const state = a.state === "ACKED" || a.state === "SILENCED" ? a.state : "FIRING";
  const detail = [sev.unknown ? "등급 라벨 없음(장애로 다룸)" : "", a.summary ?? ""].filter(Boolean).join(" · ");
  return {
    id: a.fingerprint ?? "",
    name: a.alertname ?? "",
    severity: sev.severity,
    target: a.node ?? a.component ?? a.mountpoint ?? "",
    detail,
    since: a.starts_at ?? "",
    thresholdKey: isThresholdKey(a.threshold_key) ? a.threshold_key : null,
    ref: alertRef(a, hostIds),
    state,
    ackBy: a.acked_by ?? null,
    ackAt: a.acked_at ?? null,
    silencedUntil: a.silence?.ends_at ?? null,
    silencedBy: a.silence?.by ?? a.silence?.actor ?? null,
    // AI의 연속 보류 금지(S3)는 서버가 판정한다. 사람의 보류에는 이 제약이 없다
    silencedBefore: false,
    incidentId: a.incident_id === null || a.incident_id === undefined || a.incident_id === "" ? null : String(a.incident_id),
  };
}

const EVENT_KINDS: readonly IncidentEventKind[] = [
  "OPENED", "AI_JUDGED", "APPROVED", "REJECTED", "DEFERRED", "ACTED", "AUTO_ACTED", "AUTO_REVERTED", "REVERTED", "MODE",
  "NOTIFIED", "ACKED", "SILENCED", "UNSILENCED", "RESOLVED", "STOPPED", "RESUMED", "LIMITS", "WINDOW", "DENIED", "OBSERVED",
];

function actorOf(t: string | undefined): OpsActor {
  return t === "AI" || t === "OPERATOR" ? t : "SYSTEM";
}

export function eventOf(e: WireIncidentEvent, incidentId: string | null): IncidentEvent | null {
  const kind = EVENT_KINDS.find((k) => k === e.kind);
  if (!kind) return null;
  const actor = actorOf(e.actor_type);
  return {
    id: String(e.id ?? ""),
    at: e.at ?? "",
    kind,
    actor,
    actorName: e.actor_id ?? (actor === "SYSTEM" ? "시스템" : actor === "AI" ? "AI 운영" : ""),
    text: e.text ?? "",
    incidentId,
  };
}

export function incidentOf(i: WireIncident, eventsLoaded: boolean): Incident {
  const sev = severityOf(i.severity);
  const title = [i.alertname ?? "경보", i.node ?? i.component ?? ""].filter(Boolean).join(" · ");
  return {
    id: String(i.id ?? ""),
    title,
    severity: sev.severity,
    openedAt: i.started_at ?? "",
    resolvedAt: i.resolved_at ?? null,
    eventsLoaded,
    // 런북은 경보 규칙 이름으로 고르는 정적 표다. 담당·호출·사후 분석은 서버 경로가 계약 초안이라 싣지 않는다(원천 연결 전)
    runbookId: runbookForAlert({ name: i.alertname ?? "", target: i.node ?? i.component ?? "", detail: i.summary ?? "", thresholdKey: null }),
  };
}

/* ===== 운영 작업 ===== */

function jobsOf(o: WireOps, lastDailySentAt: string | null): OpsJobs {
  const b = o.db_backup;
  let backup: OpsBackup | null = null;
  let backupSource: string | null = null;
  if (b?.state === "READY" && b.data) {
    const verify = b.data.verify_ok;
    backup = {
      tool: "백업 지표",
      lastSuccessAt: b.data.last_success_at ?? null,
      lastSizeBytes: null,
      running: false,
      offsite: null,
      restoreCheck: { outcome: verify === true ? "SUCCESS" : verify === false ? "FAILED" : "NONE", lastSuccessAt: null, lastFailureAt: null },
      nextAt: null,
      manualRunsToday: null,
      // Databasus 연결 전이라 서버의 즉시 실행은 늘 409다(§6-F 운영 작업)
      canRun: o.databasus?.state === "READY",
    };
  } else {
    backupSource = b?.state === "UNAVAILABLE" ? `DB 백업 지표 불러오기 실패 · ${reasonWord(b.reason)}` : "DB 백업 지표(failstop_db_backup_*)와 Databasus";
  }
  const daily = o.daily_report?.state === "READY" ? o.daily_report.data ?? null : null;
  return {
    backup,
    backupSource,
    // 인증서 만료는 이 판에서 원천이 없다(§6-F 운영 작업의 certificate 칸이 연결 전이다)
    certificates: null,
    dailyReport: { hour: null, channel: "Teams", lastSentAt: lastDailySentAt, lastRunAt: daily?.last_triggered_at ?? null },
  };
}

/* ===== 임계값 ===== */

/** 서버 단위(비율·초·크레딧)의 값을 화면 단위로 */
export function thresholdToView(key: ThresholdKey, v: unknown): number | null {
  const n = dec(v);
  if (n === null) return null;
  switch (THRESHOLD_META[key].unit) {
    case "%":
      return Math.round(n * 100 * 100) / 100;
    case "시간":
      return Math.round((n / 3600) * 100) / 100;
    default:
      return n;
  }
}

/** 화면 단위의 값을 서버 단위의 평문 십진 표기로. 승인 티켓 대상({key}:{value})과 요청 본문이 같은 글자를 쓴다 */
export function thresholdToWire(key: ThresholdKey, v: number): string {
  switch (THRESHOLD_META[key].unit) {
    case "%":
      return plainDecimal(v / 100);
    case "시간":
      return plainDecimal(v * 3600, 0);
    default:
      return plainDecimal(v);
  }
}

function thresholdOf(t: WireThreshold): OpsThreshold | null {
  if (!isThresholdKey(t.key)) return null;
  const key = t.key;
  const meta = THRESHOLD_META[key];
  return {
    key,
    label: meta.label,
    appliesTo: meta.appliesTo,
    unit: meta.unit,
    value: thresholdToView(key, t.value),
    min: thresholdToView(key, t.min) ?? 0,
    max: thresholdToView(key, t.max) ?? 0,
    step: meta.step,
    aiMaxStep: thresholdToView(key, t.ai_max_step) ?? 0,
    // AI의 권고 값은 판단 기록의 조치 요청에만 있고 레지스트리에는 없다
    aiSuggested: null,
    aiReason: null,
    updatedAt: t.updated_at ?? "",
    updatedBy: t.updated_by ?? "",
    inUse: t.in_use ?? null,
    ruleValue: thresholdToView(key, t.rule_value),
  };
}

const VIAS: readonly ThresholdVia[] = ["MANUAL", "AI_APPROVED", "AI_AUTO", "REVERT", "AUTO_REVERT", "SEED", "CONFIG"];

export function thresholdChangesOf(list: WireThresholdChange[]): ThresholdChange[] {
  const reverted = new Set(list.map((c) => (c.revert_of == null ? "" : String(c.revert_of))).filter(Boolean));
  const out: ThresholdChange[] = [];
  for (const c of list) {
    // 마이그레이션 초기값(SEED)은 변경이 아니다. 화면의 이력은 사람·AI·환경 설정이 바꾼 것만 싣는다
    if (!isThresholdKey(c.key) || c.via === "SEED") continue;
    const meta = THRESHOLD_META[c.key];
    const via = VIAS.find((v) => v === c.via) ?? "MANUAL";
    const id = String(c.id ?? "");
    out.push({
      id,
      key: c.key,
      label: meta.label,
      unit: meta.unit,
      from: thresholdToView(c.key, c.from),
      to: thresholdToView(c.key, c.to),
      at: c.changed_at ?? "",
      by: c.changed_by ?? "",
      via,
      approvalId: c.approval_id == null ? null : String(c.approval_id),
      reason: c.reason ?? "",
      revertOf: c.revert_of == null ? null : String(c.revert_of),
      reverted: reverted.has(id),
    });
  }
  return out;
}

/* ===== AI ===== */

const LIMIT_WIRE: Record<AiLimitKey, string> = {
  confidencePct: "confidence_pct",
  autoCap: "auto_cap",
  thresholdPerItem: "threshold_per_item",
  botCooldownMin: "bot_cooldown_min",
  backupDiskFreePct: "backup_disk_free_pct",
};

export function limitWireKey(key: AiLimitKey): string {
  return LIMIT_WIRE[key];
}

function limitKeyOf(wire: string | undefined): AiLimitKey | null {
  const hit = (Object.keys(LIMIT_WIRE) as AiLimitKey[]).find((k) => LIMIT_WIRE[k] === wire);
  return hit ?? null;
}

/** 한도. 화면의 지금 값은 서버가 적용 중인 값(effective)이고, 다음 활성부터 적용될 값이 있으면 pending에 싣는다 */
export function limitsOf(items: WireLimitItem[]): AiLimit[] {
  const out: AiLimit[] = [];
  for (const def of AI_LIMIT_DEFS) {
    const w = items.find((i) => limitKeyOf(i.key) === def.key);
    if (!w) continue;
    const effective = dec(w.effective) ?? dec(w.value) ?? def.initial;
    out.push({
      key: def.key,
      label: def.label,
      desc: def.desc,
      unit: def.unit,
      value: effective,
      pending: w.pending ? dec(w.value) : null,
      min: dec(w.min) ?? def.min,
      max: dec(w.max) ?? def.max,
      tighterWhenHigher: w.tighter === "UP",
      updatedAt: w.updated_at ?? null,
      updatedBy: w.updated_by ?? null,
    });
  }
  return out;
}

export function limitChangesOf(list: WireLimitChange[]): AiLimitChange[] {
  const out: AiLimitChange[] = [];
  for (const c of list) {
    const key = limitKeyOf(c.key);
    // 마이그레이션이 넣은 확정값(바꾸기 전 값이 없음)은 변경이 아니다
    if (!key || c.from === null || c.from === undefined) continue;
    const def = AI_LIMIT_DEFS.find((d) => d.key === key);
    out.push({
      id: String(c.id ?? ""),
      key,
      label: def?.label ?? key,
      unit: def?.unit ?? "",
      from: dec(c.from) ?? 0,
      to: dec(c.to) ?? 0,
      at: c.changed_at ?? "",
      by: c.changed_by ?? "",
      approvalId: c.approval_id == null ? "" : String(c.approval_id),
      reason: c.reason ?? "",
      revertOf: null,
      reverted: false,
      // 이력 항목은 적용 시점을 싣지 않는다(§6-F AI 한도)
      apply: null,
    });
  }
  return out;
}

const KINDS: readonly AiActionKind[] = ["READ_NODES", "THRESHOLD", "SILENCE", "BOT_LOAD", "BACKUP_NOW"];

const STATUSES: readonly AiProposalStatus[] = [
  "PROPOSED", "APPROVED", "REJECTED", "DEFERRED", "AUTO_EXECUTED", "REVERTED", "AUTO_REVERTED", "WITHDRAWN", "EXPIRED",
  "EXECUTING", "FAILED", "UNKNOWN", "DENIED",
];

/** 조치 종류마다 되돌리기와 비용의 말. 서버 계약의 되돌리기 규칙(§6-F)과 같다 */
const KIND_TEXT: Record<AiActionKind, { revert: string; cost: string; risk: "LOW" | "MEDIUM"; impact: string }> = {
  READ_NODES: {
    impact: "앱 계층 조회 용량이 한 대만큼 바뀝니다. 매칭 리더 app-1·app-2는 그대로입니다",
    revert: "반대 방향으로 한 대. 현재 희망 대수가 조치 결과와 같을 때만 됩니다",
    cost: "app-3을 켜면 인스턴스 한 대의 사용 시간이 늘고, 끄면 줍니다",
    risk: "LOW",
  },
  THRESHOLD: {
    impact: "경보나 증설 판정이 바뀐 임계로 걸립니다",
    revert: "직전 값으로. 그 항목의 최신 변경일 때만 되고 1인 승인이 필요합니다",
    cost: "비용 변화는 없습니다",
    risk: "LOW",
  },
  SILENCE: {
    impact: "보류 동안 이 경보가 Teams로 다시 가지 않습니다",
    revert: "보류를 끝냅니다",
    cost: "비용 변화는 없습니다",
    risk: "MEDIUM",
  },
  BOT_LOAD: {
    impact: "시장 봇 발행률이 줄어 체결이 줄어듭니다",
    revert: "직전 목표 발행률로. 부하를 늘리는 방향이라 사람만 합니다",
    cost: "비용 변화는 없습니다",
    risk: "MEDIUM",
  },
  BACKUP_NOW: {
    impact: "DB 백업이 한 번 더 돕니다",
    revert: "되돌리기가 없습니다",
    cost: "백업 저장 공간이 한 벌만큼 늡니다",
    risk: "LOW",
  },
};

function str(v: unknown): string | null {
  return typeof v === "string" ? v : typeof v === "number" ? String(v) : null;
}

interface ProposalContext {
  desired: number | null;
  alertNames: Map<string, string>;
}

/** 조치의 전후 값. 실행했으면 결과, 아니면 요청 매개변수에서 읽는다 */
function changeOf(a: WireAction, kind: AiActionKind, ctx: ProposalContext): { from: number; to: number; unit: string } | null {
  const result = a.result ?? {};
  const params = a.params ?? {};
  if (kind === "READ_NODES") {
    const from = dec(result.from) ?? ctx.desired;
    const delta = dec(params.delta) ?? (a.verb === "READ_NODE_REMOVE_ONE" ? -1 : 1);
    const to = dec(result.to) ?? (from === null ? null : from + delta);
    return from === null || to === null ? null : { from, to, unit: "대" };
  }
  if (kind === "THRESHOLD") {
    const key = str(params.key) ?? a.target ?? "";
    if (!isThresholdKey(key)) return null;
    const from = thresholdToView(key, result.from ?? params.from);
    const to = thresholdToView(key, result.to ?? params.to ?? params.value);
    return from === null || to === null ? null : { from, to, unit: THRESHOLD_META[key].unit };
  }
  if (kind === "BOT_LOAD") {
    const from = dec(result.from);
    const to = dec(result.to);
    return from === null || to === null ? null : { from, to, unit: "OPS" };
  }
  return null;
}

function titleOf(a: WireAction, kind: AiActionKind, change: { from: number; to: number; unit: string } | null, ctx: ProposalContext): string {
  const params = a.params ?? {};
  switch (kind) {
    case "READ_NODES": {
      const on = a.verb !== "READ_NODE_REMOVE_ONE";
      const span = change ? ` (앱 ${change.from} → ${change.to}대)` : "";
      return `${on ? "app-3 켜기" : "app-3 끄기"}${span}`;
    }
    case "THRESHOLD": {
      const key = str(params.key) ?? a.target ?? "";
      const label = isThresholdKey(key) ? THRESHOLD_META[key].label : key;
      return change ? `${label} ${change.from} → ${change.to}${change.unit}` : `${label} 변경`;
    }
    case "SILENCE": {
      const fp = str(params.fingerprint) ?? a.target ?? "";
      const minutes = dec(params.minutes);
      return `경보 보류${minutes === null ? "" : ` ${minutes}분`} · ${ctx.alertNames.get(fp) ?? fp}`;
    }
    case "BOT_LOAD": {
      const factor = dec(params.factor);
      return change ? `시장 봇 부하 줄임 (${change.from} → ${change.to} OPS)` : `시장 봇 부하 줄임${factor === null ? "" : ` ×${factor}`}`;
    }
    case "BACKUP_NOW":
      return "백업 즉시 실행";
  }
}

function targetOf(a: WireAction, kind: AiActionKind, ctx: ProposalContext): string {
  const params = a.params ?? {};
  switch (kind) {
    case "READ_NODES":
      return "app-3(앱 계층 자동 증설 자리)";
    case "THRESHOLD": {
      const key = str(params.key) ?? a.target ?? "";
      return isThresholdKey(key) ? `${THRESHOLD_META[key].label}(${key})` : key;
    }
    case "SILENCE": {
      const fp = str(params.fingerprint) ?? a.target ?? "";
      return ctx.alertNames.get(fp) ?? fp;
    }
    case "BOT_LOAD":
      return "bot-1 시장 봇";
    case "BACKUP_NOW":
      return "Databasus";
  }
}

const OBSERVE_WORD: Record<string, string> = {
  KEPT: "관찰 창 동안 악화 없이 유지했습니다",
  REVERTED: "관찰 창에서 악화해 자동으로 되돌렸습니다",
  UNOBSERVABLE: "근거가 없어 관찰하지 못했습니다",
  REVERT_BLOCKED: "악화했지만 되돌리기가 막혀 수동으로 바꿨습니다",
  NOT_REVERTIBLE: "악화했지만 되돌리지 않는 조치라 수동으로 바꿨습니다",
};

/** 서버 조치 요청 하나를 조치 카드로. 제목·대상·전후는 서버가 준 매개변수와 결과에서만 만든다 */
export function proposalOf(a: WireAction, ctx: ProposalContext): AiProposal | null {
  const kind = KINDS.find((k) => k === a.kind);
  const status = STATUSES.find((s) => s === a.status);
  if (!kind || !status) return null;
  const change = changeOf(a, kind, ctx);
  const hold = a.hold_rule ? { rule: a.hold_rule, reason: a.hold_reason ?? "" } : null;
  const decidedAt = a.reverted_at ?? a.executed_at ?? a.decided_at ?? null;
  const decidedBy = a.reverted_by ?? a.executed_by ?? a.decided_by ?? null;
  const done = status !== "PROPOSED" && status !== "DEFERRED" && status !== "EXECUTING";
  const outcomeParts: string[] = [];
  // 실제로 바꾼 전후는 실행 결과(result)가 있을 때만 적는다. 거절·만료처럼 실행하지 않은 요청에는 바꾼 것이 없다
  const ran = a.result != null && a.result.from !== undefined && a.result.to !== undefined;
  if (done && ran && change) outcomeParts.push(`${change.from} → ${change.to}${change.unit}`);
  if (a.observe_outcome && OBSERVE_WORD[a.observe_outcome]) outcomeParts.push(OBSERVE_WORD[a.observe_outcome]);
  if ((status === "DENIED" || status === "FAILED" || status === "EXPIRED") && hold) outcomeParts.push(hold.reason);
  const t = KIND_TEXT[kind];
  // 자동 모드에서 실행할 수 있는가. 임계값은 민감한 방향만(T6), 그 밖의 막힘은 서버가 준 규칙으로 판정한다
  const autoAllowed = kind === "THRESHOLD" && change && isThresholdKey(str(a.params?.key) ?? a.target ?? "")
    ? isStricter((str(a.params?.key) ?? a.target) as ThresholdKey, change.from, change.to)
    : hold?.rule !== "T6";
  return {
    id: String(a.id ?? ""),
    kind,
    title: titleOf(a, kind, change, ctx),
    target: targetOf(a, kind, ctx),
    impact: t.impact,
    risk: t.risk,
    revertPlan: t.revert,
    confidencePct: pct(a.confidence) ?? 0,
    status,
    createdAt: a.created_at ?? "",
    decidedAt: status === "PROPOSED" ? null : decidedAt,
    decidedBy: status === "PROPOSED" ? null : decidedBy,
    revertibleUntil: a.revertible && a.observe_until ? a.observe_until : null,
    autoHold: status === "PROPOSED" || status === "DEFERRED" ? hold?.reason ?? null : null,
    outcome: outcomeParts.length > 0 ? outcomeParts.join(" · ") : null,
    change,
    cost: t.cost,
    autoAllowed,
    hold,
  };
}

const OUTCOME_WORD: Record<string, string> = {
  PROPOSED: "조치 요청",
  NO_ACTION: "이상 없음",
  INVALID_OUTPUT: "모델 출력 형식 오류",
  MODEL_ERROR: "모델 호출 실패",
  EVIDENCE_STALE: "근거가 오래돼 판단하지 않음",
};

/** 판단 목록 한 줄. 판단 과정은 상세를 열 때 읽는다 */
export function judgmentBriefOf(d: WireDecision): AiJudgment {
  const outcome = OUTCOME_WORD[d.outcome ?? ""] ?? d.outcome ?? "";
  const conf = pct(d.confidence);
  const summary = [outcome, d.model ? `모델 ${d.model}` : "", conf === null ? "" : `확신도 ${conf}%`, d.error ? reasonWord(d.error) : ""]
    .filter(Boolean)
    .join(" · ");
  return { id: String(d.id ?? ""), at: d.decided_at ?? "", summary, outcome, trace: null, lazy: true, confidencePct: conf };
}

/**
 * 판단 근거 스냅숏(msp-evidence/2)에서 칩 몇 개를 뽑는다. 경보(이름·등급), 근거 지표, 조회 계층 평균 CPU다.
 * 근거의 모양이 바뀌어도 모르는 필드는 건너뛴다
 */
export function evidenceChips(ev: Record<string, unknown> | null | undefined): AiEvidence[] {
  if (!ev) return [];
  const out: AiEvidence[] = [];
  const alerts = Array.isArray(ev.alerts) ? (ev.alerts as Record<string, unknown>[]) : [];
  for (const a of alerts.slice(0, 6)) {
    const name = str(a.alertname) ?? "형식 밖 경보명";
    const sev = severityOf(str(a.severity) ?? undefined);
    const fp = str(a.fingerprint);
    out.push({ id: `alert:${fp ?? name}`, label: name, value: sev.unknown ? "등급 모름" : sev.severity, severity: sev.severity, ref: fp ? { kind: "ALERT", id: fp } : null });
  }
  const rt = ev.read_tier as Record<string, unknown> | undefined;
  const avg = rt ? (rt.avg_cpu as WireMeasure | undefined) ?? (rt.avg_cpu_ratio as WireMeasure | undefined) : undefined;
  const avgPct = pct(typeof avg === "object" && avg ? avg.value : avg);
  if (avgPct !== null) out.push({ id: "metric:read_avg_cpu", label: "앱 계층 평균 CPU", value: `${avgPct}%`, severity: null, ref: { kind: "READ_TIER" } });
  const metrics = ev.metrics && typeof ev.metrics === "object" ? (ev.metrics as Record<string, unknown>) : {};
  for (const [name, m] of Object.entries(metrics)) {
    const w = typeof m === "object" && m !== null ? (m as WireMeasure) : { value: m as string };
    const n = dec(w.value);
    if (n === null) continue;
    const known = METRIC_WORD[name];
    const value = known ? known.fmt(n) : String(n);
    // 60초보다 오래된 표본은 그 사실을 값 옆에 적는다(근거 신선도)
    const late = w.collect === "STALE" ? " · 지연" : "";
    out.push({ id: `metric:${name}`, label: known?.label ?? name, value: `${value}${late}`, severity: null, ref: known?.ref ?? null });
  }
  return out.slice(0, 12);
}

const ratioPct = (digits: number) => (n: number) => `${pct(n, digits)}%`;

/** 판단 근거의 지표 이름(msp-evidence/2 metrics)과 화면 말. 모르는 이름은 그대로 적는다 */
const METRIC_WORD: Record<string, { label: string; fmt: (n: number) => string; ref?: OpsRef }> = {
  availability: { label: "외부 가용성", fmt: ratioPct(2), ref: { kind: "SLO", metric: "availability" } },
  http_5xx_ratio: { label: "5xx 비율", fmt: ratioPct(2), ref: { kind: "SLO", metric: "errors" } },
  http_p95_seconds: { label: "응답 p95", fmt: (n) => `${Math.round(n * 1000)}ms`, ref: { kind: "SLO", metric: "p95" } },
  host_cpu_max: { label: "호스트 CPU 최대", fmt: ratioPct(0) },
  host_memory_max: { label: "호스트 메모리 최대", fmt: ratioPct(0) },
  host_root_free_min: { label: "루트 디스크 여유 최소", fmt: ratioPct(0) },
  bot_host_cpu: { label: "bot-1 CPU", fmt: ratioPct(0), ref: { kind: "HOST", id: "bot-1" } },
  replication_lag_seconds: { label: "복제 지연", fmt: (n) => `${n}초`, ref: { kind: "REPLICATION" } },
  backup_age_seconds: { label: "마지막 백업 뒤", fmt: (n) => `${Math.round((n / 3600) * 10) / 10}시간`, ref: { kind: "BACKUP" } },
};

/** 서버 규칙 id를 검사표의 행으로. 설계 7-6절 표의 묶음을 따른다 */
function checkRuleOf(rule: string): AiCheckRule {
  if (rule === "A3") return "CONFIDENCE";
  if (rule === "A4") return "STREAK";
  if (rule === "A5" || rule === "C5") return "FRESH";
  if (rule === "A6") return "OBSERVE";
  if (rule === "A7") return "CAP";
  if (rule === "A8" || rule === "A9") return "COOLDOWN";
  if (rule === "T2") return "STEP";
  if (rule === "T4") return "PER_ITEM";
  if (rule === "K2") return "BACKUP_TODAY";
  if (rule.startsWith("S")) return "WINDOW";
  if (rule === "D4" || rule.startsWith("B")) return "BOT_GUARD";
  if (rule.startsWith("D") || rule === "T6") return "DIRECTION";
  if (rule.startsWith("M") || rule === "A1") return "MODE";
  return "ALLOWED";
}

const CHECK_LABEL: Record<AiCheckRule, string> = {
  ALLOWED: "허용 목록·형식",
  OBSERVE: "한 번에 한 조치(관찰 창)",
  CAP: "자동 조치 상한",
  COOLDOWN: "쿨다운·사람의 결정",
  FRESH: "근거 60초 이내·대상 재확인",
  CONFIDENCE: "확신도 기준",
  STREAK: "같은 판단 연속 2회",
  STEP: "임계값 1회 변경폭",
  PER_ITEM: "임계값 항목당 횟수",
  BACKUP_TODAY: "백업 하루 1회·디스크 여유",
  WINDOW: "경보 보류 조건·계획 작업 창",
  DIRECTION: "방향 전제",
  BOT_GUARD: "봇 용량 보호",
  MODE: "모드",
};

/**
 * 판단 상세를 판단 과정으로. 서버는 조치 요청마다 막은 규칙 하나(hold_rule)와 사유만 남기므로, 검사표에는 서버가
 * 기록한 것만 적는다. 막힌 규칙은 막음, 자동 실행된 요청은 모든 검사를 통과한 것으로, 수동 모드의 요청은 승인 대기로
 * 적고 서버가 남기지 않은 규칙별 통과는 지어내지 않는다
 */
export function judgmentOf(d: WireDecision, ctx: ProposalContext): AiJudgment {
  const brief = judgmentBriefOf(d);
  // 판단 상세의 조치 요청은 확신도를 따로 싣지 않는다. 그 판단의 확신도가 요청의 확신도다
  const proposals = (d.actions ?? [])
    .map((a) => proposalOf({ ...a, confidence: a.confidence ?? d.confidence ?? null, created_at: a.created_at ?? d.decided_at }, ctx))
    .filter((p): p is AiProposal => p !== null);
  const candidates = proposals.map((p) => ({ id: p.id, kind: p.kind, title: p.title, confidencePct: p.confidencePct, risk: p.risk }));
  const rows = new Map<AiCheckRule, { state: AiCheckState; note: string }[]>();
  const cell = (rule: AiCheckRule) => {
    let r = rows.get(rule);
    if (!r) {
      r = proposals.map(() => ({ state: "NA" as AiCheckState, note: "" }));
      rows.set(rule, r);
    }
    return r;
  };
  proposals.forEach((p, i) => {
    if (p.hold) {
      const r = checkRuleOf(p.hold.rule);
      cell(r)[i] = { state: p.status === "PROPOSED" || p.status === "DEFERRED" ? "HOLD" : "BLOCK", note: `${p.hold.rule} · ${p.hold.reason}` };
    } else if (p.status === "AUTO_EXECUTED" || p.status === "AUTO_REVERTED") {
      cell("ALLOWED")[i] = { state: "PASS", note: "서버 정책 엔진의 자동 실행 검사를 모두 통과했습니다" };
    } else if (p.status === "PROPOSED" && d.mode !== "AUTO") {
      cell("MODE")[i] = { state: "HOLD", note: "수동 모드라 운영자 승인을 기다립니다" };
    } else {
      cell("ALLOWED")[i] = { state: "PASS", note: "제안 때의 종류별 규칙과 방향 전제를 통과했습니다" };
    }
  });
  const checks = [...rows.entries()].map(([rule, results]) => ({ rule, label: CHECK_LABEL[rule], results }));
  const dropped = (d.dropped ?? []).map((x) => `${x.verb ?? x.action ?? "조치"} 버림(${x.code ?? x.reason ?? "형식"})`);
  const ev = d.evidence ?? null;
  const trace: AiJudgmentTrace = {
    mode: d.mode === "AUTO" ? "AUTO" : "MANUAL",
    evidence: evidenceChips(ev),
    evidenceAt: ev && typeof ev.collected_at === "string" ? ev.collected_at : null,
    hypothesis: d.reason ?? null,
    candidates,
    checks,
    result: [brief.outcome, ...proposals.map((p) => `${p.title} · ${STATUS_WORD[p.status]}`), ...dropped].filter(Boolean).join(" · "),
  };
  return { ...brief, summary: d.situation ?? brief.summary, trace, lazy: false };
}

const STATUS_WORD: Record<AiProposalStatus, string> = {
  PROPOSED: "승인 대기",
  APPROVED: "승인 실행",
  REJECTED: "거절",
  DEFERRED: "나중에",
  AUTO_EXECUTED: "자동 실행",
  REVERTED: "되돌림",
  AUTO_REVERTED: "자동 되돌림",
  WITHDRAWN: "거둠",
  EXPIRED: "만료",
  EXECUTING: "실행 중",
  FAILED: "실행 실패",
  UNKNOWN: "결과 확인 필요",
  DENIED: "정책이 막음",
};

export interface AiExtras {
  mode: WireAiMode | null;
  actions: WireAction[] | null;
  decision: WireDecision | null;
  decisions: WireDecision[] | null;
  limits: WireLimitItem[] | null;
  limitChanges: WireLimitChange[] | null;
}

const COOLDOWN_KINDS: readonly AiActionKind[] = ["READ_NODES", "THRESHOLD", "BOT_LOAD"];

/**
 * AI 칸. LLM 설정이 없어도 모드·정지·한도는 서버에 있으므로 칸을 세운다(§6-F ai). 판단이 없으면 상황 문장에
 * 그 사실을 적고, 세부 조회를 한 번도 읽지 못한 것은 비워 두되 그 사실을 적는다
 */
export function aiDeskOf(ai: WireAiBrief, x: AiExtras, ctx: ProposalContext, now: number): AiDesk {
  const stopped = ai.stopped === true || x.mode?.stopped === true;
  const mode = stopped ? "STOPPED" : ai.mode === "AUTO" ? "AUTO" : "MANUAL";
  const autoW = x.mode?.auto ?? null;
  const auto = mode === "AUTO" && ai.auto
    ? {
        since: ai.auto.since ?? autoW?.since ?? "",
        until: ai.auto.valid_until ?? autoW?.valid_until ?? "",
        reason: autoW?.reason ?? "",
        by: ai.auto.enabled_by ?? autoW?.enabled_by ?? "",
        approvalId: autoW?.approval_id == null ? "" : String(autoW.approval_id),
      }
    : null;
  const proposals = (x.actions ?? []).map((a) => proposalOf(a, ctx)).filter((p): p is AiProposal => p !== null);
  const decision = x.decision;
  const llmReady = ai.state === "READY" || ai.state === "UNAVAILABLE";
  const pendingCount = ai.open_actions ?? 0;
  let situation: string;
  if (!llmReady) situation = "AI 판단 모델이 연결 전이라 판단하지 않습니다. 모드, 즉시 끄기, AI 정지와 한도는 쓸 수 있습니다";
  else if (stopped) situation = "운영자가 AI를 정지했습니다. 새 판단과 조치안을 내지 않습니다";
  else if (decision?.situation) situation = decision.situation;
  else if (ai.last_decision) situation = `마지막 판단은 ${OUTCOME_WORD[ai.last_decision.outcome ?? ""] ?? ai.last_decision.outcome ?? "기록"}입니다`;
  else situation = "아직 판단 기록이 없습니다";
  if (ai.state === "UNAVAILABLE") situation = `${situation}. 마지막 모델 호출이 실패했습니다(${reasonWord(ai.reason)})`;
  if (x.actions === null && pendingCount > 0) situation = `${situation}. 조치 요청 ${pendingCount}건을 아직 불러오지 못했습니다`;
  const cooldowns = COOLDOWN_KINDS.flatMap((kind) => {
    const until = x.mode?.cooldowns?.[kind] ?? null;
    return until && Date.parse(until) > now ? [{ kind, until }] : [];
  });
  const observeUntil = x.mode?.observe_until ?? null;
  const observing = observeUntil && Date.parse(observeUntil) > now
    ? proposals.find((p) => p.status === "AUTO_EXECUTED" && p.revertibleUntil === observeUntil) ?? null
    : null;
  const stats: Record<string, { runs: number; reverts: number }> = {};
  let runs = 0;
  let reverts = 0;
  for (const p of proposals) {
    const ran = p.status === "APPROVED" || p.status === "AUTO_EXECUTED" || p.status === "REVERTED" || p.status === "AUTO_REVERTED";
    if (!ran) continue;
    const back = p.status === "REVERTED" || p.status === "AUTO_REVERTED";
    const k = actionKey(p.kind, p.title);
    stats[k] = { runs: (stats[k]?.runs ?? 0) + 1, reverts: (stats[k]?.reverts ?? 0) + (back ? 1 : 0) };
    if (now - Date.parse(p.decidedAt ?? p.createdAt) <= 86_400_000) {
      runs += 1;
      if (back) reverts += 1;
    }
  }
  const decisions = x.decisions ?? [];
  const history = decisions.map(judgmentBriefOf);
  const model = x.mode?.llm?.model ?? decision?.model ?? (llmReady ? "미확인" : "연결 전");
  return {
    mode,
    auto,
    // 서버는 판단 중인지를 싣지 않는다
    thinking: false,
    model,
    judgedAt: ai.last_decision?.decided_at ?? null,
    evidenceAt: decision?.evidence && typeof decision.evidence.collected_at === "string" ? decision.evidence.collected_at : null,
    situation,
    hypothesis: decision?.reason ?? null,
    confidencePct: decision ? pct(decision.confidence) : null,
    evidence: evidenceChips(decision?.evidence),
    proposals,
    history,
    autoUsed: ai.auto?.auto_used ?? x.mode?.activation?.auto_used ?? 0,
    autoCap: ai.auto?.auto_cap ?? x.mode?.activation?.auto_cap ?? (limitsOf(x.limits ?? []).find((l) => l.key === "autoCap")?.value ?? 5),
    stopped: stopped ? { at: ai.stop?.at ?? x.mode?.stop?.at ?? "", by: ai.stop?.by ?? x.mode?.stop?.by ?? "" } : null,
    limits: limitsOf(x.limits ?? []),
    limitHistory: limitChangesOf(x.limitChanges ?? []),
    cooldowns,
    observation: observing && observeUntil ? { startedAt: observing.decidedAt ?? observing.createdAt, until: observeUntil, action: observing.title } : null,
    actionStats: stats,
    // 모드 줄 알림은 서버가 내지 않는다. 사건 타임라인의 정지·재개 기록이 같은 사실을 싣는다
    notice: null,
    recent: { runs, reverts },
  };
}

/* ===== AI 서버 ===== */

export function aiServerOf(w: WireAiServer | undefined): OpsSection<AiServer> {
  if (!w || (w.state !== "READY" && w.state !== "UNAVAILABLE")) {
    return nc(`${sourceWord(w?.source ?? "llm", "AI 서버")} 설정 없음`);
  }
  const vram = (w.server?.loaded_models ?? []).reduce((a, m) => a + (dec(m.size_vram_bytes) ?? 0), 0);
  const last = w.calls?.last_success_at ?? null;
  const runtime = w.provider === "ollama-native" ? `Ollama${w.server?.version ? ` ${w.server.version}` : ""}` : `OpenAI 호환${w.server?.version ? ` ${w.server.version}` : ""}`;
  const loaded = w.server?.model_loaded;
  return {
    state: "READY",
    data: {
      id: "ai-1",
      // 장비 이름은 서버가 싣지 않는다(ADR 0038의 DGX Spark는 결정이고 관측값이 아니다)
      hardware: "장비 원천 연결 전",
      runtime,
      model: `${w.model ?? "미확인"}${loaded === false ? " · 적재 안 됨" : ""}`,
      collectedAt: w.server?.checked_at ?? null,
      gpuPct: null,
      memUsedGb: vram > 0 ? Math.round((vram / 1e9) * 10) / 10 : null,
      memTotalGb: null,
      p50Ms: dec(w.calls?.latency_ms?.p50),
      p95Ms: dec(w.calls?.latency_ms?.p95),
      lastMs: null,
      tokensPerSec: null,
      queue: null,
      link: w.state === "READY" ? "OK" : "DOWN",
      assumed: false,
      busy: false,
      // 순번은 응답 순간을 알아채는 데만 쓴다. 서버는 마지막 성공 시각만 주므로 그 초를 순번으로 쓴다
      served: last ? Math.floor(Date.parse(last) / 1000) : 0,
      lastServedAt: last,
    },
  };
}

/* ===== 추세 ===== */

const TREND_KEYS: Record<TrendMetric, "availability" | "p95_seconds" | "error_ratio" | "active_alerts" | "incidents"> = {
  availability: "availability",
  p95: "p95_seconds",
  errors: "error_ratio",
  alerts: "active_alerts",
  incidents: "incidents",
};

function trendValue(metric: TrendMetric, v: unknown): number | null {
  const n = dec(v);
  if (n === null) return null;
  if (metric === "availability" || metric === "errors") return Math.round(n * 100 * 10000) / 10000;
  if (metric === "p95") return Math.round(n * 1000);
  return n;
}

const EMPTY_SERIES: OpsTrendSeries = { points: [], current: null, previous: null };

export function trendRangeOf(w: WireTrends | null | undefined): Record<TrendMetric, OpsTrendSeries> {
  const out = {} as Record<TrendMetric, OpsTrendSeries>;
  for (const metric of Object.keys(TREND_KEYS) as TrendMetric[]) {
    const s = w?.series?.[TREND_KEYS[metric]];
    if (!s || s.state !== "READY" || !s.data) {
      out[metric] = EMPTY_SERIES;
      continue;
    }
    out[metric] = {
      points: (s.data.points ?? []).map(([, v]) => trendValue(metric, v)),
      current: trendValue(metric, s.data.current),
      previous: trendValue(metric, s.data.previous),
    };
  }
  return out;
}

/**
 * 1·6·24시간 추세. 한 판은 24시간만 싣고, 1·6시간은 세부 조회(GET .../trends?range=)를 읽었을 때만 있다. 읽지 못한
 * 범위는 빈 점열이라 화면이 '비교 기록 없음'으로 그린다. 계열이 모두 연결 전이면 칸 전체가 연결 전이다
 */
export function trendsOf(day: WireTrends | undefined, hour: WireTrends | null, six: WireTrends | null): OpsSection<OpsTrends> {
  const series = Object.values(day?.series ?? {});
  if (!day || series.length === 0 || series.every((s) => s?.state === "NOT_CONNECTED")) {
    return nc("Prometheus 기록 규칙과 사건 표 설정 없음");
  }
  const data: OpsTrends = { 1: trendRangeOf(hour), 6: trendRangeOf(six), 24: trendRangeOf(day) };
  return { state: "READY", data };
}

/* ===== 세부 조회 묶음 ===== */

export interface OpsExtrasSnapshot {
  ai: AiExtras;
  thresholdChanges: WireThresholdChange[] | null;
  incidents: WireIncident[] | null;
  incidentEvents: Map<string, WireIncidentEvent[]>;
  trends: { 1: WireTrends | null; 6: WireTrends | null };
  reportsStatus: WireReportsStatus | null;
  /** 계획 작업 창 목록. 읽지 못했으면 null이다 */
  windows: WireWindow[] | null;
}

export function emptyExtras(): OpsExtrasSnapshot {
  return {
    ai: { mode: null, actions: null, decision: null, decisions: null, limits: null, limitChanges: null },
    thresholdChanges: null,
    incidents: null,
    incidentEvents: new Map(),
    trends: { 1: null, 6: null },
    reportsStatus: null,
    windows: null,
  };
}

/* ===== 한 판 ===== */

/** 관제 모듈이 없는 배포(404)의 판. 칸마다 붙일 원천을 적고 원천 상태는 모른다(null) */
export function absentBoard(): ControlCenter {
  const why = "관리 API에 관제 모듈 없음(exchange.msp.enabled 꺼짐, overview 404)";
  return {
    origin: null,
    environment: "UNKNOWN",
    generatedAt: isoNow(),
    collectedAt: null,
    summary: nc(why),
    hosts: nc(why),
    readTier: nc(why),
    replication: nc(why),
    alerts: nc(why),
    incidents: nc(why),
    jobs: nc(why),
    thresholds: nc(why),
    ai: nc(why),
    aiServer: nc(why),
    trading: nc(why),
    trends: nc(why),
    scenario: null,
    sources: null,
    oncall: nc(why),
    database: nc(why),
    changes: nc(why),
    outlook: nc(why),
  };
}

/** 3라운드 계약 초안(관리자 설계 4-4)의 칸. 서버에 경로가 없어 원천 연결 전이다 */
const ONCALL_SOURCE = "당번 명부와 호출 정책(GET /admin/v1/msp/oncall · 3라운드 계약 초안)";
export const CHANGE_SOURCE = "변경 요청(GET·POST /admin/v1/msp/changes · 3라운드 계약 초안)";
export const OUTLOOK_SOURCE = "용량 전망과 앱 계층 활동 기록(GET /admin/v1/msp/capacity/outlook · 3라운드 계약 초안)";
const WINDOW_SOURCE = "계획 작업 창(GET /admin/v1/msp/maintenance-windows)";

/** 서버 계획 작업 창 한 건(API 설계 §6-F) */
export interface WireWindow {
  id?: number | string;
  starts_at?: string;
  ends_at?: string;
  alertname?: string | null;
  labels?: Record<string, string> | null;
  reason?: string;
  created_by?: string;
  ended_at?: string | null;
  ended_by?: string | null;
  active?: boolean;
}

/** 라벨은 서버가 받는 네 키만 옮긴다 */
function windowLabels(l: Record<string, string> | null | undefined): MaintenanceWindow["labels"] {
  const out: MaintenanceWindow["labels"] = {};
  for (const k of ["node", "role", "component", "mountpoint"] as const) if (l?.[k]) out[k] = l[k];
  return out;
}

export function windowOf(w: WireWindow): MaintenanceWindow {
  return {
    id: String(w.id ?? ""),
    startsAt: w.starts_at ?? "",
    endsAt: w.ends_at ?? "",
    alertname: w.alertname ?? null,
    labels: windowLabels(w.labels),
    reason: w.reason ?? "",
    createdBy: w.created_by ?? "",
    endedAt: w.ended_at ?? null,
    endedBy: w.ended_by ?? null,
    active: w.active === true,
    // 변경 요청 원천이 연결 전이라 창을 연 요청을 모른다
    changeId: null,
  };
}

/**
 * 변경·작업 칸. 창은 서버 목록(status=ALL, 끝 시각 역순)이고 변경 요청은 원천 연결 전이다. 창 목록을 아직 읽지 못했으면
 * '아직 읽지 않음'을 적는다(사건 목록과 같다). 한 판에 AI 칸이 없는 서버는 창 목록을 읽지 않으므로 원천 연결 전이다
 */
function changesOf(windows: WireWindow[] | null, hasAi: boolean): OpsSection<ChangeDesk> {
  if (!hasAi) return nc(`${WINDOW_SOURCE} · 서버 응답에 AI 제어 경로 없음`);
  if (windows === null) return { state: "UNAVAILABLE", source: WINDOW_SOURCE, reason: "아직 읽지 않음" };
  return { state: "READY", data: { windows: windows.map(windowOf), changes: nc(CHANGE_SOURCE) } };
}

function newest(times: (string | null | undefined)[]): string | null {
  let best: string | null = null;
  for (const t of times) if (t && (best === null || Date.parse(t) > Date.parse(best))) best = t;
  return best;
}

/** 서버 한 판과 세부 조회 묶음을 관제 판으로 만든다. 같은 입력이면 같은 판이 나온다(now는 쿨다운 판정에만 쓴다) */
export function boardOf(w: WireOverview, x: OpsExtrasSnapshot, now: number): ControlCenter {
  const hosts = section(w.hosts, "Prometheus 호스트 지표", (list: WireHost[]) => list.map(hostOf));
  const hostIds = new Set(hosts.state === "READY" ? hosts.data.map((h) => h.id) : []);
  const alertsW = w.alerts;
  const alerts = section(alertsW, "Alertmanager 경보", (list: WireAlert[]) => list.map((a) => alertOf(a, hostIds)));
  const alertNames = new Map((alertsW?.data ?? []).map((a) => [a.fingerprint ?? "", a.alertname ?? ""]));
  const readTier = w.read_tier?.state === "READY" && w.read_tier.data ? readTierOf(w.read_tier.data) : section(w.read_tier, "앱 계층 용량", () => null as never);
  const desired = readTier.state === "READY" ? readTier.data.desired : null;
  const ctx: ProposalContext = { desired, alertNames };

  const thresholdChanges = x.thresholdChanges ? thresholdChangesOf(x.thresholdChanges) : [];
  const thresholds = section(w.thresholds, "임계값 레지스트리", (d: { items?: WireThreshold[] }): ThresholdRegistry => ({
    items: (d.items ?? []).map(thresholdOf).filter((t): t is OpsThreshold => t !== null),
    history: thresholdChanges,
  }));

  const incidents: OpsSection<IncidentLog> = (() => {
    const iw = w.incidents;
    if (!iw || iw.state !== "READY" || !iw.data) return section(iw, "사건 기록", () => null as never);
    const counts = iw.data;
    if (x.incidents === null) {
      // 오늘 사건도 열린 사건도 없으면 빈 목록이 사실이다. 있는데 목록을 아직 못 읽었으면 그 사실을 적는다
      if ((counts.today_count ?? 0) === 0 && (counts.open_ids ?? []).length === 0) return { state: "READY", data: { incidents: [], events: [] } };
      return { state: "UNAVAILABLE", source: "사건 목록(GET /admin/v1/msp/incidents)", reason: "아직 읽지 않음" };
    }
    const list = x.incidents.map((i) => incidentOf(i, x.incidentEvents.has(String(i.id ?? ""))));
    const events: IncidentEvent[] = [];
    for (const [id, evs] of x.incidentEvents) {
      for (const e of evs) {
        const m = eventOf(e, id);
        if (m) events.push(m);
      }
    }
    events.sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
    list.sort((a, b) => Date.parse(b.openedAt) - Date.parse(a.openedAt));
    return { state: "READY", data: { incidents: list, events } };
  })();

  const lastDaily = x.reportsStatus?.data?.reporting?.last_daily_sent_at ?? null;
  const jobs = section(w.ops, "운영 작업", (o: WireOps) => jobsOf(o, lastDaily));
  const summary = section(w.summary, "Prometheus 기록 규칙", summaryOf);
  const ai: OpsSection<AiDesk> = w.ai ? { state: "READY", data: aiDeskOf(w.ai, x.ai, ctx, now) } : nc("AI 판단 서비스 · 서버 응답에 없음");
  const board: ControlCenter = {
    origin: null,
    environment: ENVIRONMENTS.find((e) => e === w.environment) ?? "UNKNOWN",
    generatedAt: w.generated_at ?? isoNow(),
    collectedAt: newest([
      ...(w.hosts?.data ?? []).map((h) => h.up?.sampled_at),
      w.summary?.data?.error_ratio?.sampled_at,
      w.summary?.data?.p95_seconds?.sampled_at,
      w.summary?.data?.availability?.sampled_at,
    ]),
    summary,
    hosts,
    readTier,
    replication: section(w.replication, "PostgreSQL 복제 지표", replicationOf),
    alerts,
    incidents,
    jobs,
    thresholds,
    ai,
    aiServer: aiServerOf(w.ai_server),
    trading: nc("거래 칸 · 관제 서버 계약에 없음(봇 플릿·HA 화면은 기존 관리 API로 읽습니다)"),
    trends: trendsOf(w.trends, x.trends[1], x.trends[6]),
    scenario: null,
    sources: sourcesOf(w),
    oncall: nc(ONCALL_SOURCE),
    database: nc(DB_STATS_SOURCE),
    changes: changesOf(x.windows, Boolean(w.ai)),
    outlook: nc(OUTLOOK_SOURCE),
  };
  // 실측 표식은 관측 원천(지표·경보)의 값이 있을 때만 세운다. DB의 임계값 레지스트리만 있는 판은 실측이 아니다
  const anyLive = [board.summary, board.hosts, board.alerts].some((s) => s.state === "READY");
  return { ...board, origin: anyLive ? "LIVE" : null };
}

/* ===== AI 상담 ===== */

function chatKind(id: string): AiChatEvidence["kind"] {
  if (id.startsWith("alert")) return "ALERT";
  if (id.startsWith("incident")) return "INCIDENT";
  if (id.startsWith("action")) return "PROPOSAL";
  if (id.startsWith("threshold")) return "THRESHOLD";
  if (id.startsWith("host")) return "HOST";
  return "METRIC";
}

/**
 * 서버 상담 답을 화면 답으로. 근거 id의 구분자를 화면의 것(metric:, alert:)으로 바꾸고, 실행 부탁인지는 질문을 읽는
 * 화면 규칙(requestedGuide)으로 가른다. 서버 상담 경로에는 쓰기 호출이 없어 어느 쪽이든 실행되지 않는다
 */
export function chatReplyOf(w: WireChatReply, question: string): AiChatReply {
  const guide = requestedGuide(question);
  const evidence: AiChatEvidence[] = (w.citations ?? []).map((c) => {
    const raw = c.id ?? "";
    const dot = raw.indexOf(".");
    const id = dot > 0 ? `${raw.slice(0, dot)}:${raw.slice(dot + 1)}` : `metric:${raw}`;
    return { id, kind: chatKind(raw), label: c.label ?? raw, value: c.value ?? "값 없음", severity: null };
  });
  const state = w.state === "ANSWERED" ? "OK" : w.state === "STOPPED" ? "STOPPED" : w.state === "NOT_CONNECTED" ? "NOT_CONFIGURED" : "UNAVAILABLE";
  const reason = w.reason ? ` (${reasonWord(w.reason)})` : "";
  return {
    state,
    answer: state === "OK" ? w.answer ?? "" : "",
    note: state === "OK" ? w.preface ?? null : `${w.answer ?? ""}${state === "UNAVAILABLE" ? reason : ""}`.trim() || null,
    evidence,
    collectedAt: w.evidence?.collected_at ?? null,
    model: w.model ?? null,
    declined: guide !== null,
    guide,
  };
}

/* ===== 보고서 ===== */

const JOB_STATUSES: readonly ReportJobStatus[] = ["pending", "processing", "queued", "sending", "sent", "dead"];

/** 보고 서비스의 개입 등급(intervention.py). 목록의 severity가 P1~P4다 */
const INTERVENTION: Record<string, { code: "immediate" | "scheduled" | "verify" | "monitor"; label: string }> = {
  P1: { code: "immediate", label: "즉시 대응" },
  P2: { code: "scheduled", label: "담당자 조치" },
  P3: { code: "verify", label: "담당자 확인" },
  P4: { code: "monitor", label: "관찰 유지" },
};

function reportOf(r: WireReport, customer: string, environment: string): OpsReport {
  const status = JOB_STATUSES.find((s) => s === r.status) ?? "pending";
  const state = (r.alert_state ?? "").toLowerCase();
  const level = INTERVENTION[r.severity ?? ""] ?? null;
  return {
    id: String(r.id ?? ""),
    detail: "SUMMARY",
    kind: r.kind === "daily" ? "daily" : "incident",
    createdAt: r.created_at ?? "",
    sentAt: r.sent_at ?? null,
    status,
    title: r.title ?? "",
    transition: state === "firing" || state === "resolved" || state === "mixed" ? state : "unknown",
    intervention: level
      ? { code: level.code, priority: r.severity ?? "", label: level.label, reason: "" }
      : { code: "monitor", priority: "등급", label: "원천 연결 전", reason: "" },
    customer,
    environment,
    collectedAt: r.created_at ?? "",
    windowStart: r.created_at ?? "",
    windowEnd: r.created_at ?? "",
    lead: r.summary ?? "",
    impact: "",
    currentState: "",
    events: [],
    actions: [],
    metrics: [],
    analysis: { mode: "facts-only", note: "", summary: "", model: null },
    gaps: [],
    autoAction: "",
    links: { pdf: r.links?.pdf ?? null, html: r.links?.html ?? null },
    attempts: [],
  };
}

/**
 * 보고서와 전송 상태. 보고 서비스 읽기 프록시(GET .../reports, .../reports/status)는 목록 요약과 전송 수만 준다.
 * 본문(영향·조치·후속 관측·분석)과 시도 기록, 연결 시험 기록은 원천이 없어 비워 두고 detail로 알린다
 */
export function reportDeskOf(list: WireSection<WireReport[]> & { data?: WireReport[] | null }, status: WireReportsStatus, environment: string): OpsSection<ReportDesk> {
  if (list.state !== "READY") {
    return list.state === "UNAVAILABLE"
      ? { state: "UNAVAILABLE", source: SOURCE_WORD.reporting, reason: reasonWord(list.reason) }
      : nc(`${SOURCE_WORD.reporting} 설정 없음`);
  }
  const reports = (list.data ?? []).filter((r) => r.kind !== "test").map((r) => reportOf(r, "", environment));
  const tests = (list.data ?? [])
    .filter((r) => r.kind === "test")
    .map((r, i) => ({ attempt: i + 1, at: r.sent_at ?? r.created_at ?? "", status: r.status === "sent" ? ("sent" as const) : r.status === "dead" ? ("dead" as const) : ("queued" as const), error: null }));
  const rep = status.data?.reporting ?? null;
  const counts = rep?.counts ?? {};
  return {
    state: "READY",
    data: {
      reports,
      delivery: {
        lastDailySentAt: rep?.last_daily_sent_at ?? null,
        pending: (counts.pending ?? 0) + (counts.processing ?? 0) + (counts.queued ?? 0) + (counts.sending ?? 0),
        dead: counts.dead ?? 0,
        nextDailyAt: null,
      },
      tests,
    },
  };
}

/* ===== 세부 조회 일정 ===== */

type Getter = <T>(path: string) => Promise<T>;

interface FeedEntry {
  /** 마지막으로 읽은 시각(ms). 읽은 적이 없으면 null */
  at: number | null;
  /** 읽을 때의 서명. 한 판의 서명이 바뀌면 다시 읽는다 */
  sig: string | null;
  body: unknown;
  /** 조작 뒤처럼 서명과 무관하게 다시 읽어야 하는가 */
  dirty: boolean;
}

interface FeedJob {
  key: string;
  path: string;
  sig: string;
  ttlMs: number;
  /** 작을수록 먼저 읽는다 */
  rank: number;
}

/**
 * 첫 판에서 한꺼번에 읽는 세부 조회 수. 계약 기본 버킷(분당 6건)을 한 번에 다 쓰지 않게 여섯으로 두고, 남은 세부는
 * 다음 판들에서 두 건씩 채운다(읽은 적 없는 세부는 서명이 바뀐 것으로 친다)
 */
const FIRST_BUDGET = 6;
/** 서명이 그대로일 때 세부 조회 사이의 최소 간격. 한 판(15초)과 합쳐 분당 6건 안쪽에 머문다 */
const IDLE_GAP_MS = 30_000;
/** 사건 기록을 판에 싣는 최근 사건 수 */
const EVENT_INCIDENTS = 3;

const ALL_TREND_RANGES = ["1h", "6h"] as const;

/**
 * 세부 조회의 일정과 보관. 한 판을 받을 때마다 update를 부르면, 서명이 바뀌었거나 수명이 지난 조회를 순위대로 골라
 * 정해진 수만큼 읽는다. 실패한 조회는 지난 값을 두고 다음 기회에 다시 읽는다
 */
export class OpsFeed {
  private entries = new Map<string, FeedEntry>();
  private lastFetchAt = 0;
  private loadedOnce = false;
  /** 429를 받으면 Retry-After(없으면 30초)까지 세부 조회를 쉰다. 한 판 조회와 조작의 버킷을 남기려는 것이다 */
  private pauseUntil = 0;
  private wanted = new Set<string>(["1h"]);

  constructor(private readonly get: Getter, private readonly clock: () => number = () => Date.now()) {}

  /** 화면이 고른 추세 범위. 1시간은 늘 읽고(벗어난 시간을 분 점열로 센다) 6시간은 고른 동안만 읽는다 */
  wantTrend(range: TrendRange): void {
    if (range === 6) this.wanted.add("6h");
  }

  /** 조작 뒤 다시 읽을 조회. 한 판의 서명이 바뀌지 않는 조작(한도 변경 등)이 있다 */
  invalidate(...keys: string[]): void {
    for (const k of keys) {
      for (const [key, e] of this.entries) if (key === k || key.startsWith(`${k}:`)) e.dirty = true;
    }
  }

  /** 읽어 둔 조치 요청 하나. 승인·되돌리기가 종류와 매개변수를 본다 */
  action(id: string): WireAction | null {
    const list = (this.entries.get("ai.actions")?.body as { data?: WireAction[] } | undefined)?.data ?? [];
    return list.find((a) => String(a.id) === id) ?? null;
  }

  private jobs(w: WireOverview): FeedJob[] {
    const out: FeedJob[] = [];
    const ai = w.ai;
    if (ai) {
      const brief = JSON.stringify([ai.mode, ai.stopped, ai.auto?.since, ai.auto?.valid_until, ai.open_actions, ai.last_decision?.id]);
      out.push({ key: "ai.actions", path: "/admin/v1/msp/ai/actions?status=ALL&limit=30", sig: brief, ttlMs: (ai.open_actions ?? 0) > 0 ? 60_000 : 300_000, rank: 0 });
      out.push({ key: "ai.mode", path: "/admin/v1/msp/ai/mode", sig: brief, ttlMs: 120_000, rank: 3 });
      out.push({ key: "ai.limits", path: "/admin/v1/msp/ai/limits", sig: JSON.stringify([ai.mode, ai.auto?.activation]), ttlMs: 300_000, rank: 5 });
      out.push({ key: "ai.limitChanges", path: "/admin/v1/msp/ai/limits/changes?limit=20", sig: this.limitsSig(), ttlMs: 600_000, rank: 9 });
      const last = ai.last_decision?.id;
      if (last != null) {
        out.push({ key: `ai.decision:${last}`, path: `/admin/v1/msp/ai/decisions/${encodeURIComponent(String(last))}`, sig: String(last), ttlMs: Infinity, rank: 1 });
        out.push({ key: "ai.decisions", path: "/admin/v1/msp/ai/decisions?limit=20", sig: String(last), ttlMs: 600_000, rank: 7 });
      }
    }
    const items = w.thresholds?.state === "READY" ? w.thresholds.data?.items ?? [] : [];
    if (w.thresholds?.state === "READY") {
      out.push({ key: "thresholds.changes", path: "/admin/v1/msp/thresholds/changes?limit=30", sig: items.map((i) => `${i.key}:${i.version}`).join(","), ttlMs: 600_000, rank: 8 });
    }
    const inc = w.incidents?.state === "READY" ? w.incidents.data : null;
    if (inc) {
      const alertSig = (w.alerts?.data ?? []).map((a) => `${a.fingerprint}:${a.state}`).join(",");
      const sig = JSON.stringify([inc.today_count, inc.open_ids, alertSig]);
      if ((inc.today_count ?? 0) > 0 || (inc.open_ids ?? []).length > 0 || this.entries.has("incidents")) {
        out.push({ key: "incidents", path: "/admin/v1/msp/incidents?limit=30", sig, ttlMs: 300_000, rank: 2 });
      }
      const list = (this.entries.get("incidents")?.body as { data?: WireIncident[] } | undefined)?.data ?? [];
      for (const i of list.slice(0, EVENT_INCIDENTS)) {
        const open = i.resolved_at == null;
        out.push({ key: `incident:${i.id}`, path: `/admin/v1/msp/incidents/${encodeURIComponent(String(i.id))}`, sig: `${i.resolved_at ?? ""}|${open ? sig : ""}`, ttlMs: open ? 300_000 : Infinity, rank: 6 });
      }
    }
    if (w.trends) {
      for (const r of ALL_TREND_RANGES) {
        if (!this.wanted.has(r)) continue;
        out.push({ key: `trends.${r}`, path: `/admin/v1/msp/trends?range=${r}`, sig: "", ttlMs: r === "1h" ? 60_000 : 180_000, rank: r === "1h" ? 4 : 9 });
      }
    }
    // 계획 작업 창은 AI 제어 경로(API 설계 §6-F)라 한 판에 AI 칸이 있을 때 읽는다. 모든 화면의 사이드바 배지와 관제센터 모드
    // 줄이 읽고, 등록·종료 뒤에는 invalidate로 다시 읽는다
    if (ai) out.push({ key: "windows", path: "/admin/v1/msp/maintenance-windows?status=ALL", sig: "", ttlMs: 120_000, rank: 2 });
    const teams = (w.sources ?? []).find((s) => s.name === "teams");
    if (teams && teams.state !== "NOT_CONNECTED") {
      out.push({ key: "reports.status", path: "/admin/v1/msp/reports/status", sig: teams.last_sent_at ?? "", ttlMs: 300_000, rank: 10 });
    }
    return out;
  }

  private limitsSig(): string {
    const items = (this.entries.get("ai.limits")?.body as { items?: WireLimitItem[] } | undefined)?.items ?? [];
    return items.map((i) => `${i.key}:${i.version}`).join(",");
  }

  /** 한 판을 받은 뒤 부른다. 읽을 차례인 세부 조회를 골라 읽는다 */
  async update(w: WireOverview): Promise<void> {
    const now = this.clock();
    const due = this.jobs(w)
      .map((j) => {
        const e = this.entries.get(j.key);
        const changed = !e || e.at === null || e.dirty || e.sig !== j.sig;
        const expired = !!e && e.at !== null && now - e.at >= j.ttlMs;
        return { j, changed, expired };
      })
      .filter((d) => d.changed || d.expired)
      .sort((a, b) => Number(b.changed) - Number(a.changed) || a.j.rank - b.j.rank);
    let budget: number;
    if (now < this.pauseUntil) budget = 0;
    else if (!this.loadedOnce) budget = FIRST_BUDGET;
    else if (due.some((d) => d.changed)) budget = 2;
    else budget = now - this.lastFetchAt >= IDLE_GAP_MS ? 1 : 0;
    this.loadedOnce = true;
    for (const { j } of due.slice(0, budget)) {
      this.lastFetchAt = now;
      try {
        const body = await this.get<unknown>(j.path);
        this.entries.set(j.key, { at: now, sig: j.sig, body, dirty: false });
      } catch (e) {
        // 지난 값을 두고 다음 기회에 다시 읽는다. 읽은 적이 없으면 판이 그 사실을 적는다
        const old = this.entries.get(j.key);
        if (old) old.dirty = true;
        const limited = typeof e === "object" && e !== null && (e as { status?: number }).status === 429;
        if (limited) {
          const after = (e as { retryAfter?: number | null }).retryAfter ?? 30;
          this.pauseUntil = now + Math.max(1, after) * 1000;
          break;
        }
      }
    }
    // 판에서 빠진 사건과 판단의 기록은 버린다. 보관이 끝없이 늘지 않게 한다
    const keep = new Set(this.jobs(w).map((j) => j.key));
    for (const key of [...this.entries.keys()]) {
      if ((key.startsWith("incident:") || key.startsWith("ai.decision:")) && !keep.has(key)) this.entries.delete(key);
    }
  }

  snapshot(w: WireOverview): OpsExtrasSnapshot {
    const body = <T>(key: string): T | null => (this.entries.get(key)?.body as T | undefined) ?? null;
    const last = w.ai?.last_decision?.id;
    const events = new Map<string, WireIncidentEvent[]>();
    for (const [key, e] of this.entries) {
      if (!key.startsWith("incident:")) continue;
      events.set(key.slice("incident:".length), (e.body as { events?: WireIncidentEvent[] }).events ?? []);
    }
    return {
      ai: {
        mode: body<WireAiMode>("ai.mode"),
        actions: body<{ data?: WireAction[] }>("ai.actions")?.data ?? null,
        decision: last == null ? null : body<WireDecision>(`ai.decision:${last}`),
        decisions: body<{ data?: WireDecision[] }>("ai.decisions")?.data ?? null,
        limits: body<{ items?: WireLimitItem[] }>("ai.limits")?.items ?? null,
        limitChanges: body<{ data?: WireLimitChange[] }>("ai.limitChanges")?.data ?? null,
      },
      thresholdChanges: body<{ data?: WireThresholdChange[] }>("thresholds.changes")?.data ?? null,
      incidents: body<{ data?: WireIncident[] }>("incidents")?.data ?? null,
      incidentEvents: events,
      trends: { 1: body<WireTrends>("trends.1h"), 6: body<WireTrends>("trends.6h") },
      reportsStatus: body<WireReportsStatus>("reports.status"),
      windows: body<{ data?: WireWindow[] }>("windows")?.data ?? null,
    };
  }
}