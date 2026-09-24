/**
 * 관제센터 시뮬레이터(관리자 설계 4-4). 값은 지어낸 것이라 화면이 늘 '시뮬레이션'을 표시한다.
 *
 * 시계는 호출자가 넘기는 벽시계(ms)이고 1초 틱으로 밟는다. 같은 시각을 넘기면 같은 결과가 나오도록
 * 난수 대신 틱에서 정해지는 흔들림을 쓴다. 경보 판정은 임계값 표의 현재값을 그대로 읽으므로 표를
 * 바꾸면 게이지 눈금과 경보가 함께 움직인다.
 *
 * AI 판단과 자동 실행은 ADR 0035의 규칙(aiPolicy.ts)을 흉내 낸 것이다. 2단계의 서버 정책 엔진이
 * 아니고, 모델을 부르지 않는다. 금지 목록 조치는 조치 어휘(AiActionKind)에 없어 만들 수 없다.
 */

import { isoNow } from "../format";
import { AdminError } from "./api";
import { AI_LIMIT_DEFS, AI_LIMITS_DEFAULT, AI_RULES, AUTO_HOURS, cooldownSec, type AiLimits } from "./aiPolicy";
import { TrendBook } from "./opsTrends";
import type {
  AiActionKind,
  AiDecision,
  AiDesk,
  AiEvidence,
  AiJudgment,
  AiLimitApply,
  AiLimitChange,
  AiLimitKey,
  AiMode,
  AiProposal,
  AiProposalStatus,
  AiRisk,
  AiServer,
  AlertSeverity,
  ControlCenter,
  Incident,
  IncidentEvent,
  IncidentEventKind,
  OpsActor,
  OpsAlert,
  OpsBackup,
  OpsHost,
  OpsHostRole,
  OpsPartition,
  OpsRef,
  OpsScenarioId,
  OpsSlo,
  OpsSourceName,
  OpsThreshold,
  ReadNode,
  ReadNodeState,
  ThresholdChange,
  ThresholdKey,
  ThresholdUnit,
  ThresholdVia,
} from "./types";

export interface OpsActorRef {
  id: string;
  name: string;
}

/** AI 조치의 행위자. ADR 0035의 AI 전용 운영자 계정 자리다 */
export const AI_ACTOR: OpsActorRef = { id: "ai-ops", name: "AI 운영" };

export type OpsAudit = (actor: OpsActorRef, action: string, target: string, approvalId: string | null) => void;

const TICK_MS = 1000;
/** 한 번에 따라잡는 틱 상한. 배경 탭에서 오래 멈췄다 돌아와도 한 호출이 길어지지 않게 한다 */
const MAX_CATCHUP = 900;
const JUDGE_EVERY = 5;
/** 경보 발생·해제에 필요한 연속 틱. Prometheus 규칙의 for 를 줄인 것이다 */
const FOR_TICKS = 3;
const NODE_BOOT_TICKS = 6;
const NODE_DRAIN_TICKS = 4;
const BACKUP_RUN_TICKS = 8;
/** 조회 수요의 기준. 조회 노드 두 대가 각각 31% 안팎이 되는 값이다 */
const BASE_DEMAND = 62;
const BASE_BOT_OPS = 30;
const SLO: OpsSlo = { availabilityPct: 99.5, p95Ms: 400, errorRatePct: 1 };
const READ_MIN = 2;
const READ_MAX = 3;
/** 조회 축소 임계는 증설 임계보다 이만큼 낮아야 한다. 둘이 붙으면 늘리고 줄이기를 되풀이한다 */
const SCALE_GAP = 15;
/** 시나리오 부하 곡선이 끝나는 초. 그 뒤에는 다른 시나리오를 걸 수 있다 */
export const SCENARIO_END_SEC = 240;
/** 되돌린 조치를 같은 판단으로 다시 내지 않는 시간. 거절한 조치와 같다 */
const REVERT_SUPPRESS_MS = 10 * 60_000;
/** 되돌림을 AI 문장에 적어 두는 시간. 다음 정기 판단을 한 번 지날 때까지다 */
const REVERT_NOTE_MS = 2 * JUDGE_EVERY * 1000;
/** 자동 실행 후보가 여럿이면 서비스에 가까운 조치부터 본다 */
const KIND_PRIORITY: Record<AiActionKind, number> = { READ_NODES: 0, BOT_LOAD: 1, BACKUP_NOW: 2, SILENCE: 3, THRESHOLD: 4 };

interface ThresholdDef {
  key: ThresholdKey;
  label: string;
  appliesTo: string;
  unit: ThresholdUnit;
  value: number;
  min: number;
  max: number;
  step: number;
  aiMaxStep: number;
}

const THRESHOLD_DEFS: ThresholdDef[] = [
  { key: "read.scale_out_cpu", label: "조회 증설 CPU 임계", appliesTo: "조회 ASG 평균 CPU", unit: "%", value: 60, min: 40, max: 85, step: 1, aiMaxStep: 5 },
  { key: "read.scale_in_cpu", label: "조회 축소 CPU 임계", appliesTo: "조회 ASG 평균 CPU", unit: "%", value: 30, min: 10, max: 50, step: 1, aiMaxStep: 5 },
  { key: "host.cpu_alert", label: "호스트 CPU 경보", appliesTo: "EC2 호스트 CPU", unit: "%", value: 85, min: 60, max: 95, step: 1, aiMaxStep: 5 },
  { key: "host.mem_alert", label: "메모리 경보", appliesTo: "EC2 호스트 메모리", unit: "%", value: 90, min: 70, max: 95, step: 1, aiMaxStep: 5 },
  { key: "host.disk_free_alert", label: "디스크 여유 경보", appliesTo: "루트 디스크 여유(미만이면 경보)", unit: "%", value: 20, min: 10, max: 40, step: 1, aiMaxStep: 5 },
  { key: "db.replication_lag_alert", label: "복제 지연 경보", appliesTo: "Standby 재생 지연", unit: "초", value: 30, min: 5, max: 300, step: 5, aiMaxStep: 10 },
  { key: "backup.delay_alert", label: "백업 지연 경보", appliesTo: "Databasus 마지막 성공 뒤 경과", unit: "시간", value: 26, min: 12, max: 72, step: 1, aiMaxStep: 6 },
  { key: "bot.capacity_guard", label: "봇 용량 보호", appliesTo: "bot-1 CPU. 넘으면 봇 부하 줄임", unit: "%", value: 70, min: 50, max: 90, step: 1, aiMaxStep: 5 },
  { key: "host.cpu_credit_alert", label: "CPU 크레딧 잔량 경보", appliesTo: "t3 호스트 CPU 크레딧(미만이면 경보)", unit: "크레딧", value: 50, min: 10, max: 200, step: 10, aiMaxStep: 20 },
];

interface HostDef {
  id: string;
  role: OpsHostRole;
  az: string;
  mem: number;
  disk: number;
  credits: number;
  partition: OpsPartition | null;
}

/**
 * EC2 호스트. 매칭 노드 두 대는 마켓을 나눠 맡는 마켓별 리더(ADR 0034, 다른 브랜치에서 구현 중)의
 * 배치를 따른다. 조회 노드는 ASG라 여기 없고, 온프레미스 AI 서버는 재는 것이 달라 따로 둔다
 */
const HOST_DEFS: HostDef[] = [
  { id: "app-1", role: "APP", az: "ap-northeast-2a", mem: 64, disk: 41, credits: 288, partition: { id: "p0", markets: ["BTC", "XRP", "DOGE", "TRX", "DOT", "AVAX"] } },
  { id: "app-2", role: "APP", az: "ap-northeast-2c", mem: 61, disk: 40, credits: 301, partition: { id: "p1", markets: ["ETH", "SOL", "ADA", "LINK", "MATIC", "ATOM"] } },
  { id: "db-primary", role: "DB_PRIMARY", az: "ap-northeast-2c", mem: 72, disk: 58, credits: 412, partition: null },
  { id: "db-standby", role: "DB_STANDBY", az: "ap-northeast-2a", mem: 55, disk: 57, credits: 540, partition: null },
  { id: "bot-1", role: "BOT_OBS", az: "ap-northeast-2c", mem: 78, disk: 67, credits: 176, partition: null },
];

/** ai-1의 통합 메모리(DGX Spark 128GB)와 평시에 잡고 있는 양. 적재 모델은 평가로 정하므로 sim 값이다 */
const AI_MEM_TOTAL_GB = 128;
const AI_MEM_RESIDENT_GB = 38.4;
/** 추론 지연 표본을 몇 개까지 두고 p50·p95를 셀지 */
const AI_LATENCY_KEEP = 20;

const SOURCE_NAMES: OpsSourceName[] = ["prometheus", "alertmanager", "databasus", "llm", "teams"];

export const OPS_SCENARIO_TITLE: Record<OpsScenarioId, string> = {
  READ_SURGE: "조회 급증 대응",
  NIGHT_SCALE_IN: "야간 축소 뒤 악화",
};

type Params =
  | { kind: "READ_NODES"; delta: 1 | -1 }
  | { kind: "THRESHOLD"; key: ThresholdKey; from: number; to: number }
  | { kind: "SILENCE"; alertId: string; minutes: number }
  | { kind: "BOT_LOAD"; fromOps: number; toOps: number }
  | { kind: "BACKUP_NOW" };

interface ProposalRec {
  id: string;
  sig: string;
  params: Params;
  title: string;
  target: string;
  impact: string;
  risk: AiRisk;
  revertPlan: string;
  confidencePct: number;
  /** 연속으로 같은 조치를 낸 판단 횟수 */
  streak: number;
  status: AiProposalStatus;
  createdAt: number;
  decidedAt: number | null;
  decidedBy: string | null;
  revertibleUntil: number | null;
  autoHold: string | null;
  outcome: string | null;
}

interface Finding {
  sig: string;
  params: Params;
  title: string;
  target: string;
  impact: string;
  risk: AiRisk;
  revertPlan: string;
  confidencePct: number;
}

interface AlertRec {
  id: string;
  name: string;
  severity: AlertSeverity;
  target: string;
  detail: string;
  since: number;
  thresholdKey: ThresholdKey | null;
  ref: OpsRef | null;
  state: "FIRING" | "ACKED" | "SILENCED";
  ackBy: string | null;
  ackAt: number | null;
  silencedUntil: number | null;
  silencedBy: string | null;
  silencedBefore: boolean;
  incidentId: string | null;
}

interface Breach {
  name: string;
  severity: AlertSeverity;
  target: string;
  detail: string;
  thresholdKey: ThresholdKey | null;
  ref: OpsRef | null;
}

interface ReadNodeRec {
  id: string;
  state: ReadNodeState;
  /** 지금 상태에 들어선 틱 */
  since: number;
}

interface Observation {
  proposalId: string;
  startedAt: number;
  until: number;
  action: string;
  p95Before: number;
}

/** 방금 되돌린 조치. AI 문장이 되돌린 방향과 이유를 말하게 한다 */
interface RevertNote {
  at: number;
  kind: AiActionKind;
  /** 조회 노드 조치면 되돌린 원래 조치의 방향 */
  delta: number;
  undo: string;
  /** AI가 악화를 보고 되돌렸으면 그 악화. 운영자가 되돌렸으면 null */
  cause: string | null;
  by: string;
}

let seq = 0;
function uid(prefix: string): string {
  seq += 1;
  return `${prefix}-${seq.toString(36)}`;
}

/** 틱에서 정해지는 흔들림. 같은 틱이면 늘 같은 값이다 */
function wobble(tick: number, seed: number, amp: number): number {
  return amp * Math.sin(tick * 0.7 + seed * 1.3) * Math.cos(tick * 0.23 + seed);
}

function round1(v: number): number {
  return Math.round(v * 10) / 10;
}

/** 이 시각 이후 가장 가까운 로컬 시:분 */
function nextLocal(now: number, hour: number, minute: number): number {
  const d = new Date(now);
  d.setHours(hour, minute, 0, 0);
  if (d.getTime() <= now) d.setDate(d.getDate() + 1);
  return d.getTime();
}

function lastLocal(now: number, hour: number, minute: number): number {
  return nextLocal(now, hour, minute) - 86_400_000;
}

function limitDef(key: AiLimitKey): (typeof AI_LIMIT_DEFS)[number] {
  const d = AI_LIMIT_DEFS.find((x) => x.key === key);
  if (!d) throw new AdminError("invalid_parameter", "AI 한도 항목 없음");
  return d;
}

function mmss(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  return s < 60 ? `${s}초` : `${Math.floor(s / 60)}분 ${String(s % 60).padStart(2, "0")}초`;
}

export class OpsSim {
  private readonly audit: OpsAudit;
  private t: number;
  private tick = 0;
  private scenario: { id: OpsScenarioId; startedAt: number } | null = null;

  private readonly thresholds = new Map<ThresholdKey, OpsThreshold>();
  private history: ThresholdChange[] = [];

  private desired = READ_MIN;
  private nodes: ReadNodeRec[] = [
    { id: "read-1", state: "IN_SERVICE", since: 0 },
    { id: "read-2", state: "IN_SERVICE", since: 0 },
    { id: "read-3", state: "OFF", since: 0 },
  ];
  private botOps = BASE_BOT_OPS;
  private credits = new Map(HOST_DEFS.map((h) => [h.id, h.credits]));

  // 이번 틱의 판독값. 틱마다 다시 계산한다
  private demand = BASE_DEMAND;
  private readAvg: number | null = BASE_DEMAND / 2;
  private p95 = 170;
  private errors = 0.02;
  private availability = 99.98;
  private hostCpu = new Map<string, number>();
  private lagSec = 0.2;
  private lagBytes = 12_288;

  private alerts: AlertRec[] = [];
  private breachTicks = new Map<string, number>();
  private clearTicks = new Map<string, number>();
  private incidents: Incident[] = [];
  private events: IncidentEvent[] = [];
  private openIncident: string | null = null;
  private lowTicks = 0;

  private mode: AiMode = "MANUAL";
  private auto: { since: number; until: number; reason: string; by: string; approvalId: string } | null = null;
  private stopped: { at: number; by: string } | null = null;
  private limits: AiLimits = { ...AI_LIMITS_DEFAULT };
  /** 자동 모드 중에 넓히는 쪽으로 바꾼 한도. 이번 자동 모드가 끝날 때 적용한다 */
  private pendingLimits = new Map<AiLimitKey, number>();
  private limitMeta = new Map<AiLimitKey, { at: number; by: string }>();
  private limitHistory: AiLimitChange[] = [];
  private proposals: ProposalRec[] = [];
  private suppressed = new Map<string, number>();
  private judgments: AiJudgment[] = [];
  private judgedAt: number | null = null;
  private situation = "";
  private hypothesis: string | null = null;
  private confidence: number | null = null;
  private evidence: AiEvidence[] = [];
  private thinking = false;
  private lastOutcome = "";
  /** 이번 자동 모드에서 실행한 조치 수. 자동을 새로 켜면 0부터 센다. 쿨다운과 관찰 창은 모드와 무관하게 남는다 */
  private autoRuns = 0;
  private aiThresholdRuns = new Map<ThresholdKey, number>();
  private cooldowns = new Map<AiActionKind, number>();
  private observation: Observation | null = null;
  private lastRevert: RevertNote | null = null;

  private backup: OpsBackup;
  private backupRunTick: number | null = null;
  private readonly certExpires: number;
  private reportSent: number | null;

  /** 시드 기록의 AI 조치 감사. 어댑터가 처음 한 번 감사 로그 끝(오래된 쪽)에 붙인다 */
  seededAudit: { at: string; actor: OpsActorRef; action: string; target: string }[] = [];

  private readonly trends = new TrendBook();
  /** 이번 틱에 새로 선 경보 수. 추세의 경보 발생 수가 쓴다 */
  private firedNow = 0;
  /** ai-1에 판단 요청이 걸려 있는가. 판단 직전 '판단 중' 두 틱 동안 걸린다 */
  private aiBusy = false;
  private aiServed = 0;
  private aiServedAt: number | null = null;
  private aiLatency: number[] = [];

  constructor(now: number, audit: OpsAudit = () => {}) {
    this.audit = audit;
    this.t = now;
    for (const d of THRESHOLD_DEFS) {
      this.thresholds.set(d.key, { ...d, aiSuggested: null, aiReason: null, updatedAt: isoNow(now - 6 * 86_400_000), updatedBy: "초기값" });
    }
    const lastBackup = lastLocal(now, 3, 10);
    this.backup = {
      tool: "Databasus",
      lastSuccessAt: isoNow(lastBackup + 4 * 60_000),
      lastSizeBytes: 412_000_000,
      running: false,
      offsite: { target: "SFTP", outcome: "SUCCESS", at: isoNow(lastBackup + 6 * 60_000) },
      restoreCheck: { outcome: "SUCCESS", lastSuccessAt: isoNow(lastBackup + 21 * 60_000), lastFailureAt: isoNow(lastBackup - 3 * 86_400_000 + 21 * 60_000) },
      nextAt: isoNow(nextLocal(now, 3, 10)),
      manualRunsToday: 0,
    };
    this.certExpires = now + 62 * 86_400_000;
    const report = lastLocal(now, 9, 0);
    this.reportSent = report + 4_000;
    this.seedHistory(now);
    this.measure();
    this.lastOutcome = "";
    this.describe(now, []);
  }

  /**
   * 지난 이틀의 사건 기록. 오늘 앞서 있었던 둘은 평균 복구 시간 칸이 첫 화면부터 판독되게 하고, 그 앞의
   * 셋은 상태 요약 추세의 앞 구간을 채운다. 추세의 경보·사건은 이 기록만 센다(opsTrends.ts)
   */
  private seedHistory(now: number): void {
    const add = (openedAgoMin: number, minutes: number, title: string, severity: AlertSeverity, steps: [number, IncidentEventKind, OpsActor, string, string][]) => {
      const opened = now - openedAgoMin * 60_000;
      const inc: Incident = { id: uid("inc"), title, severity, openedAt: isoNow(opened), resolvedAt: isoNow(opened + minutes * 60_000) };
      this.incidents.unshift(inc);
      for (const [offMin, kind, actor, name, text] of steps) {
        this.events.unshift({ id: uid("evt"), at: isoNow(opened + offMin * 60_000), kind, actor, actorName: name, text, incidentId: inc.id });
      }
    };
    add(41 * 60 + 12, 9, "디스크 여유 부족 · bot-1", "warning", [
      [0, "OPENED", "SYSTEM", "시스템", "경보 발생 · 여유 17% < 20%"],
      [0.5, "AI_JUDGED", "AI", AI_ACTOR.name, "판단 · 디스크 정리는 금지 목록. 허용 목록 조치 없음"],
      [2, "ACKED", "OPERATOR", "김수퍼", "확인 · 오래된 로그 압축과 보관"],
      [9, "RESOLVED", "SYSTEM", "시스템", "해제 · 여유 34%. 복구 9분 00초"],
    ]);
    add(31 * 60 + 40, 3, "복제 지연 · db-standby", "warning", [
      [0, "OPENED", "SYSTEM", "시스템", "경보 발생 · 재생 지연 33초 ≥ 30초"],
      [0.4, "AI_JUDGED", "AI", AI_ACTOR.name, "판단 · 따라잡는 중. 허용 목록 조치 없음"],
      [3, "RESOLVED", "SYSTEM", "시스템", "해제 · 지연 0.4초. 복구 3분 00초"],
    ]);
    add(26 * 60 + 5, 5, "응답 지연 증가 · 외부 응답", "warning", [
      [0, "OPENED", "SYSTEM", "시스템", "경보 발생 · p95 431ms ≥ 목표 400ms"],
      [0.3, "AI_JUDGED", "AI", AI_ACTOR.name, "판단 · 조치안 1건(조회 노드 +1) · 확신도 84%"],
      [1, "APPROVED", "OPERATOR", "김수퍼", "승인 · 조회 노드 +1 (2 → 3대)"],
      [1, "ACTED", "SYSTEM", "시스템", "조치 · 희망 대수 2 → 3대"],
      [5, "RESOLVED", "SYSTEM", "시스템", "해제 · p95 188ms. 복구 5분 00초"],
    ]);
    add(190, 6, "복제 지연 · db-standby", "warning", [
      [0, "OPENED", "SYSTEM", "시스템", "경보 발생 · 재생 지연 41초 ≥ 30초"],
      [0.4, "AI_JUDGED", "AI", AI_ACTOR.name, "판단 · Primary 쓰기 몰림 뒤 따라잡는 중. 허용 목록 조치 없음"],
      [1.2, "ACKED", "OPERATOR", "김수퍼", "확인 · 복제 추이 관찰"],
      [6, "RESOLVED", "SYSTEM", "시스템", "해제 · 지연 0.3초. 복구 6분 00초"],
    ]);
    add(72, 4, "봇 용량 보호 · bot-1", "warning", [
      [0, "OPENED", "SYSTEM", "시스템", "경보 발생 · bot-1 CPU 78% ≥ 70%"],
      [0.3, "AI_JUDGED", "AI", AI_ACTOR.name, "판단 · 조치안 1건(봇 부하 60 → 30 OPS) · 확신도 88%"],
      [1.1, "APPROVED", "OPERATOR", "김수퍼", "승인 · 시장 봇 부하 줄임"],
      [1.1, "ACTED", "SYSTEM", "시스템", "조치 · 봇 발행 60 → 30 OPS"],
      [4, "RESOLVED", "SYSTEM", "시스템", "해제 · bot-1 CPU 43%. 복구 4분 00초"],
    ]);
    // 위 기록에서 운영자가 승인한 AI 조치. 감사 로그에도 같은 일이 남아 있어야 두 기록이 같은 말을 한다
    this.seededAudit = [
      { at: isoNow(now - (26 * 60 + 4) * 60_000), actor: { id: "op-01", name: "김수퍼" }, action: "ai.approve:READ_NODES", target: "조회 노드 +1 (2 → 3대)" },
      { at: isoNow(now - (72 - 1.1) * 60_000), actor: { id: "op-01", name: "김수퍼" }, action: "ai.approve:BOT_LOAD", target: "시장 봇 부하 줄임 (60 → 30 OPS)" },
    ];
  }

  /* ===== 시계 ===== */

  advance(now: number): void {
    const due = Math.min(Math.floor((now - this.t) / TICK_MS), MAX_CATCHUP);
    for (let i = 0; i < due; i += 1) {
      this.t += TICK_MS;
      this.step(this.t);
    }
    if (due === MAX_CATCHUP) this.t = now;
  }

  private step(now: number): void {
    this.tick += 1;
    this.firedNow = 0;
    const moved = this.progressNodes();
    this.progressBackup(now);
    this.measure();
    this.evaluateAlerts(now);
    this.settleIncident(now);
    this.expireSilences(now);
    this.expireAuto(now);
    this.watchObservation(now);
    this.trends.record(now, this.p95, this.availability, this.errors, this.alerts.length, this.firedNow);
    // 정지 중에는 판단하지 않는다. 관찰 창은 위에서 끝까지 보고, 창이 닫히거나 되돌리면 정지 문장도 따라간다
    if (this.mode === "STOPPED") {
      this.thinking = false;
      this.describeStopped();
      this.trackAiServer();
      return;
    }
    const phase = this.tick % JUDGE_EVERY;
    // 이상이 있을 때만 판단 직전 두 틱을 '판단 중'으로 둔다. 평시에 늘 도는 표시는 소음이다
    this.thinking = (phase === JUDGE_EVERY - 2 || phase === JUDGE_EVERY - 1) && this.hasConcern();
    if (phase === 0) this.judge(now);
    // 노드가 기동을 마치거나 내려가면 '준비 중'·'줄이는 중' 문장이 다음 판단까지 거짓으로 남는다
    else if (moved) this.describe(now, this.findings());
    this.trackAiServer();
  }

  /**
   * ai-1에 걸린 판단 요청. '판단 중' 두 틱 동안 요청이 걸려 있고 판단이 나는 틱에 응답이 돌아온다.
   * 정지로 끊긴 요청은 응답으로 세지 않는다. 지연은 지어낸 값이다
   */
  private trackAiServer(): void {
    if (this.aiBusy && !this.thinking && this.mode !== "STOPPED") {
      this.aiServed += 1;
      this.aiServedAt = this.t;
      this.aiLatency = [...this.aiLatency, Math.round(1400 + Math.abs(wobble(this.tick, 11, 1300)))].slice(-AI_LATENCY_KEEP);
    }
    this.aiBusy = this.thinking;
  }

  private hasConcern(): boolean {
    return this.alerts.length > 0 || this.proposals.some((p) => p.status === "PROPOSED") || this.findings().length > 0;
  }

  /* ===== 부하와 판독 ===== */

  private demandAt(now: number): number {
    const sc = this.scenario;
    let d = BASE_DEMAND;
    if (sc) {
      const s = (now - sc.startedAt) / 1000;
      const ramp = (from: number, to: number, a: number, b: number) => from + (to - from) * Math.min(1, Math.max(0, (s - a) / (b - a)));
      if (sc.id === "READ_SURGE") {
        d = s < 200 ? ramp(BASE_DEMAND, 148, 3, 11) : ramp(148, 64, 200, 230);
      } else {
        d = s < 200 ? ramp(66, 150, 24, 30) : ramp(150, 66, 200, 230);
      }
    }
    return d + wobble(this.tick, 1, 2.5);
  }

  private inService(): ReadNodeRec[] {
    return this.nodes.filter((n) => n.state === "IN_SERVICE");
  }

  private measure(): void {
    this.demand = this.demandAt(this.t);
    const serving = this.inService().length;
    this.readAvg = serving === 0 ? null : this.demand / serving;
    const avg = this.readAvg ?? 100;
    this.p95 = Math.round(170 + Math.max(0, avg - 50) * 14 + wobble(this.tick, 2, 6));
    this.errors = Math.round((0.02 + Math.max(0, avg - 65) * 0.06) * 100) / 100;
    this.availability = Math.round((99.98 - Math.max(0, avg - 65) * 0.03) * 100) / 100;
    // 봇 주문은 마켓으로 두 매칭 노드에 나뉜다. p0 쪽 거래가 조금 더 많다
    this.hostCpu.set("app-1", Math.round(24 + this.botOps * 0.3 + wobble(this.tick, 3, 3)));
    this.hostCpu.set("app-2", Math.round(22 + this.botOps * 0.26 + wobble(this.tick, 9, 3)));
    this.hostCpu.set("db-primary", Math.round(16 + this.demand * 0.08 + wobble(this.tick, 4, 2)));
    this.hostCpu.set("db-standby", Math.round(8 + wobble(this.tick, 5, 1.5)));
    this.hostCpu.set("bot-1", Math.round(28 + this.botOps * 0.45 + wobble(this.tick, 6, 3)));
    this.lagSec = round1(0.2 + Math.abs(wobble(this.tick, 7, 0.2)));
    this.lagBytes = Math.round(12_288 + wobble(this.tick, 8, 3000));
    const bot = this.credits.get("bot-1") ?? 0;
    this.credits.set("bot-1", Math.max(0, bot - 0.02));
  }

  /** 기동·드레이닝이 끝난 노드가 있으면 true */
  private progressNodes(): boolean {
    let moved = false;
    for (const n of this.nodes) {
      if (n.state === "PENDING" && this.tick - n.since >= NODE_BOOT_TICKS) {
        n.state = "IN_SERVICE";
        n.since = this.tick;
        moved = true;
      } else if (n.state === "DRAINING" && this.tick - n.since >= NODE_DRAIN_TICKS) {
        n.state = "OFF";
        n.since = this.tick;
        moved = true;
      }
    }
    return moved;
  }

  private progressBackup(now: number): void {
    if (this.backupRunTick === null) return;
    const age = this.tick - this.backupRunTick;
    if (age === BACKUP_RUN_TICKS) {
      this.backup.running = false;
      this.backup.lastSuccessAt = isoNow(now);
      this.backup.lastSizeBytes = 412_000_000 + Math.round(this.tick * 1_000);
      this.log(null, "ACTED", "SYSTEM", "Databasus", "백업 완료 · 412MB");
    } else if (age === BACKUP_RUN_TICKS + 2) {
      this.backup.offsite = { target: "SFTP", outcome: "SUCCESS", at: isoNow(now) };
    } else if (age === BACKUP_RUN_TICKS + 6) {
      this.backup.restoreCheck = { ...this.backup.restoreCheck, outcome: "SUCCESS", lastSuccessAt: isoNow(now) };
      this.log(null, "ACTED", "SYSTEM", "Databasus", "복원 검증 통과 · 임시 DB에 복원 뒤 건수 대조");
      this.backupRunTick = null;
    }
  }

  private value(key: ThresholdKey): number {
    const t = this.thresholds.get(key);
    if (!t) throw new Error(`임계값 정의 없음: ${key}`);
    return t.value;
  }

  /* ===== 경보 ===== */

  private breaches(now: number): Map<string, Breach> {
    const out = new Map<string, Breach>();
    const scaleOut = this.value("read.scale_out_cpu");
    if (this.readAvg !== null && this.readAvg >= scaleOut) {
      out.set("read_cpu", {
        name: "조회 노드 CPU 높음", severity: "warning", target: "조회 ASG",
        detail: `평균 ${Math.round(this.readAvg)}% ≥ ${scaleOut}%`, thresholdKey: "read.scale_out_cpu", ref: { kind: "READ_TIER" },
      });
    }
    if (this.p95 >= SLO.p95Ms) {
      out.set("latency", {
        name: "응답 지연 증가", severity: "warning", target: "외부 응답",
        detail: `p95 ${this.p95}ms ≥ 목표 ${SLO.p95Ms}ms`, thresholdKey: null, ref: { kind: "SLO", metric: "p95" },
      });
    }
    if (this.availability < SLO.availabilityPct) {
      out.set("availability", {
        name: "외부 가용성 저하", severity: "critical", target: "외부 프로브",
        detail: `${this.availability}% < 목표 ${SLO.availabilityPct}%`, thresholdKey: null, ref: { kind: "SLO", metric: "availability" },
      });
    }
    const cpuAlert = this.value("host.cpu_alert");
    const memAlert = this.value("host.mem_alert");
    const diskFree = this.value("host.disk_free_alert");
    const creditAlert = this.value("host.cpu_credit_alert");
    for (const h of HOST_DEFS) {
      const cpu = this.hostCpu.get(h.id) ?? 0;
      if (cpu >= cpuAlert) {
        out.set(`cpu:${h.id}`, { name: "호스트 CPU 높음", severity: "warning", target: h.id, detail: `CPU ${cpu}% ≥ ${cpuAlert}%`, thresholdKey: "host.cpu_alert", ref: { kind: "HOST", id: h.id } });
      }
      if (h.mem >= memAlert) {
        out.set(`mem:${h.id}`, { name: "메모리 부족", severity: "warning", target: h.id, detail: `메모리 ${h.mem}% ≥ ${memAlert}%`, thresholdKey: "host.mem_alert", ref: { kind: "HOST", id: h.id } });
      }
      const free = 100 - h.disk;
      if (free < diskFree) {
        out.set(`disk:${h.id}`, {
          name: "디스크 여유 부족", severity: free < diskFree / 2 ? "critical" : "warning", target: h.id,
          detail: `여유 ${free}% < ${diskFree}%`, thresholdKey: "host.disk_free_alert", ref: { kind: "HOST", id: h.id },
        });
      }
      const credit = Math.round(this.credits.get(h.id) ?? 0);
      if (credit < creditAlert) {
        out.set(`credit:${h.id}`, { name: "CPU 크레딧 잔량 낮음", severity: "info", target: h.id, detail: `잔량 ${credit} < ${creditAlert}`, thresholdKey: "host.cpu_credit_alert", ref: { kind: "HOST", id: h.id } });
      }
    }
    const guard = this.value("bot.capacity_guard");
    const botCpu = this.hostCpu.get("bot-1") ?? 0;
    if (botCpu >= guard) {
      out.set("bot_guard", { name: "봇 용량 보호", severity: "warning", target: "bot-1", detail: `CPU ${botCpu}% ≥ ${guard}%`, thresholdKey: "bot.capacity_guard", ref: { kind: "HOST", id: "bot-1" } });
    }
    const lagAlert = this.value("db.replication_lag_alert");
    if (this.lagSec >= lagAlert) {
      out.set("repl_lag", { name: "복제 지연", severity: "warning", target: "db-standby", detail: `지연 ${this.lagSec}초 ≥ ${lagAlert}초`, thresholdKey: "db.replication_lag_alert", ref: { kind: "REPLICATION" } });
    }
    const last = this.backup.lastSuccessAt ? Date.parse(this.backup.lastSuccessAt) : null;
    const delayH = this.value("backup.delay_alert");
    if (last === null || (now - last) / 3_600_000 >= delayH) {
      const ago = last === null ? "기록 없음" : `${Math.floor((now - last) / 3_600_000)}시간 전`;
      out.set("backup_delay", { name: "백업 지연", severity: "warning", target: "Databasus", detail: `마지막 성공 ${ago} ≥ ${delayH}시간`, thresholdKey: "backup.delay_alert", ref: { kind: "BACKUP" } });
    }
    return out;
  }

  private evaluateAlerts(now: number): void {
    const breaching = this.breaches(now);
    for (const [id, b] of breaching) {
      this.clearTicks.delete(id);
      const active = this.alerts.find((a) => a.id === id);
      if (active) {
        active.detail = b.detail;
        continue;
      }
      const n = (this.breachTicks.get(id) ?? 0) + 1;
      this.breachTicks.set(id, n);
      if (n >= FOR_TICKS) this.fire(id, b, now);
    }
    for (const a of [...this.alerts]) {
      if (breaching.has(a.id)) continue;
      const n = (this.clearTicks.get(a.id) ?? 0) + 1;
      this.clearTicks.set(a.id, n);
      if (n >= FOR_TICKS) this.resolveAlert(a, now);
    }
    for (const id of [...this.breachTicks.keys()]) {
      if (!breaching.has(id)) this.breachTicks.delete(id);
    }
  }

  private fire(id: string, b: Breach, now: number): void {
    this.breachTicks.delete(id);
    this.firedNow += 1;
    if (this.openIncident === null) {
      const inc: Incident = { id: uid("inc"), title: `${b.name} · ${b.target}`, severity: b.severity, openedAt: isoNow(now), resolvedAt: null };
      this.incidents.unshift(inc);
      this.openIncident = inc.id;
    } else {
      const inc = this.incidents.find((x) => x.id === this.openIncident);
      if (inc && b.severity === "critical") inc.severity = "critical";
    }
    this.alerts.push({
      id, name: b.name, severity: b.severity, target: b.target, detail: b.detail, since: now,
      thresholdKey: b.thresholdKey, ref: b.ref, state: "FIRING", ackBy: null, ackAt: null,
      silencedUntil: null, silencedBy: null, silencedBefore: false, incidentId: this.openIncident,
    });
    this.log(this.openIncident, "OPENED", "SYSTEM", "시스템", `경보 발생 · ${b.name} · ${b.detail}`);
  }

  private resolveAlert(a: AlertRec, now: number): void {
    this.alerts = this.alerts.filter((x) => x.id !== a.id);
    this.clearTicks.delete(a.id);
    const incId = a.incidentId;
    if (incId === null || this.alerts.some((x) => x.incidentId === incId)) {
      this.log(incId, "RESOLVED", "SYSTEM", "시스템", `경보 해제 · ${a.name}`);
      return;
    }
    const inc = this.incidents.find((x) => x.id === incId);
    if (!inc) return;
    inc.resolvedAt = isoNow(now);
    const sec = (now - Date.parse(inc.openedAt)) / 1000;
    this.log(incId, "RESOLVED", "SYSTEM", "시스템", `해제 · ${a.name} 해제로 사건 종료. 복구 ${mmss(sec)}`);
    if (this.openIncident === incId) this.openIncident = null;
  }

  /** 경보 없이 연 사건(자동 되돌림)은 조치 뒤 지표가 가라앉으면 닫는다 */
  private settleIncident(now: number): void {
    const id = this.openIncident;
    if (id === null || this.alerts.some((a) => a.incidentId === id) || this.breachTicks.size > 0) return;
    if (this.nodes.some((n) => n.state === "PENDING" || n.state === "DRAINING")) return;
    const inc = this.incidents.find((x) => x.id === id);
    if (!inc || now - Date.parse(inc.openedAt) < 8_000) return;
    inc.resolvedAt = isoNow(now);
    this.log(id, "RESOLVED", "SYSTEM", "시스템", `해제 · 조치 뒤 지표 정상. 복구 ${mmss((now - Date.parse(inc.openedAt)) / 1000)}`);
    this.openIncident = null;
  }

  private expireSilences(now: number): void {
    for (const a of this.alerts) {
      if (a.state === "SILENCED" && a.silencedUntil !== null && now >= a.silencedUntil) {
        a.state = a.ackBy ? "ACKED" : "FIRING";
        a.silencedUntil = null;
        this.log(a.incidentId, "SILENCED", "SYSTEM", "시스템", `보류 만료 · ${a.name}`);
      }
    }
  }

  /* ===== AI 판단 ===== */

  private findings(): Finding[] {
    const out: Finding[] = [];
    const scaleOut = this.value("read.scale_out_cpu");
    const scaleIn = this.value("read.scale_in_cpu");
    const avg = this.readAvg;
    const serving = this.inService().length;
    const pending = this.nodes.some((n) => n.state === "PENDING");
    if (avg !== null && avg >= scaleOut && this.desired < READ_MAX && !pending) {
      const est = Math.round(this.demand / (serving + 1));
      out.push({
        sig: "READ_NODES:+1", params: { kind: "READ_NODES", delta: 1 },
        title: `조회 노드 +1 (${this.desired} → ${this.desired + 1}대)`, target: "조회 ASG",
        impact: `평균 CPU ${Math.round(avg)}% → 약 ${est}% 예상. t3.small 1대 비용이 시간 단위로 늘어납니다`,
        risk: "LOW", revertPlan: `조회 노드 −1. 쿨다운 ${AI_RULES.readNodesCooldownSec / 60}분 뒤 가능`,
        confidencePct: Math.round(60 + Math.min(24, (avg - scaleOut) * 1.6) + (this.p95 > SLO.p95Ms ? 4 : 0)),
      });
    }
    if (avg !== null && avg <= scaleIn && this.desired > READ_MIN && this.lowTicks >= 4 && !pending) {
      const est = Math.round(this.demand / Math.max(1, serving - 1));
      out.push({
        sig: "READ_NODES:-1", params: { kind: "READ_NODES", delta: -1 },
        title: `조회 노드 −1 (${this.desired} → ${this.desired - 1}대)`, target: "조회 ASG",
        impact: `평균 CPU ${Math.round(avg)}% → 약 ${est}% 예상. 한 대 비용이 줄고, 진행 중인 요청을 흘려보낸 뒤 내립니다`,
        risk: "LOW", revertPlan: `조회 노드 +1. 관찰 창 안에서 악화하면 자동으로 되돌립니다`,
        confidencePct: Math.round(70 + Math.min(20, (scaleIn - avg) * 2) + (this.lowTicks >= 4 ? 4 : 0)),
      });
    }
    // 증설이 늦었다는 근거는 임계를 넘은 채 응답이 목표를 넘은 구간이다. 한 번 나온 제안은 결정될 때까지 남는다.
    // 같은 항목을 바꾼 지 쿨다운(30분)이 안 됐으면 다시 내리자고 하지 않는다. 바꾼 효과를 보기 전이다
    const recent = this.history.some((c) => c.key === "read.scale_out_cpu" && this.t - Date.parse(c.at) < AI_RULES.thresholdCooldownSec * 1000);
    if (avg !== null && avg >= scaleOut && this.p95 > SLO.p95Ms && !recent) {
      const to = scaleOut - 5;
      const def = this.thresholds.get("read.scale_out_cpu");
      if (def && to >= def.min && to - scaleIn >= SCALE_GAP) {
        out.push({
          sig: `THRESHOLD:read.scale_out_cpu:${to}`, params: { kind: "THRESHOLD", key: "read.scale_out_cpu", from: scaleOut, to },
          title: `증설 CPU 임계 ${scaleOut}% → ${to}%`, target: "임계값 · 조회 증설",
          impact: "다음 급증에서 증설이 약 1분 먼저 시작됩니다. 평시 증설이 잦아질 수 있습니다",
          risk: "LOW", revertPlan: "임계값 이력에서 되돌립니다. 같은 승인 흐름을 거칩니다",
          confidencePct: 72,
        });
      }
    }
    const guard = this.value("bot.capacity_guard");
    const botCpu = this.hostCpu.get("bot-1") ?? 0;
    if (botCpu >= guard && this.botOps > 3) {
      const toOps = Math.max(3, Math.round(this.botOps / 2));
      out.push({
        sig: "BOT_LOAD", params: { kind: "BOT_LOAD", fromOps: this.botOps, toOps },
        title: `시장 봇 부하 줄임 (${this.botOps} → ${toOps} OPS)`, target: "bot-1 시장 봇",
        impact: `bot-1 CPU ${botCpu}% → 약 ${Math.round(28 + toOps * 0.45)}% 예상. 관측 수집 여유를 지킵니다`,
        risk: "LOW", revertPlan: `봇 발행 ${this.botOps} OPS로 되돌리기`,
        confidencePct: 86,
      });
    }
    for (const a of this.alerts) {
      if (a.severity !== "info" || a.state === "SILENCED" || a.silencedBefore) continue;
      out.push({
        sig: `SILENCE:${a.id}`, params: { kind: "SILENCE", alertId: a.id, minutes: AI_RULES.silenceMaxMin },
        title: `경보 보류 ${AI_RULES.silenceMaxMin}분 · ${a.name}`, target: `${a.target} 경보`,
        impact: "정보 등급이라 서비스 영향이 없습니다. 보류 동안 다른 경보는 그대로 울립니다",
        risk: "LOW", revertPlan: "보류를 해제합니다. 30분 뒤에는 저절로 풀립니다",
        confidencePct: 83,
      });
    }
    const backupLate = this.alerts.some((a) => a.id === "backup_delay");
    const botDiskFree = 100 - (HOST_DEFS.find((h) => h.id === "bot-1")?.disk ?? 100);
    if (backupLate && !this.backup.running && this.backup.manualRunsToday < 1 && botDiskFree >= this.limits.backupDiskFreePct) {
      out.push({
        sig: "BACKUP_NOW", params: { kind: "BACKUP_NOW" },
        title: "백업 즉시 실행 (Databasus)", target: "exchange DB",
        impact: "읽기 전용 계정으로 전체 백업과 외부 보관, 복원 검증을 한 번 돌립니다. 운영 DB 쓰기에는 영향이 없습니다",
        risk: "LOW", revertPlan: "되돌릴 것이 없습니다. 백업 한 벌이 늘 뿐입니다",
        confidencePct: 90,
      });
    }
    return out;
  }

  private judge(now: number): void {
    const avg = this.readAvg;
    const scaleIn = this.value("read.scale_in_cpu");
    this.lowTicks = avg !== null && avg <= scaleIn ? this.lowTicks + JUDGE_EVERY : 0;
    const found = this.findings();
    const seen = new Set<string>();
    for (const f of found) {
      const until = this.suppressed.get(f.sig);
      if (until !== undefined && now < until) continue;
      seen.add(f.sig);
      const cur = this.proposals.find((p) => p.sig === f.sig && p.status === "PROPOSED");
      if (cur) {
        Object.assign(cur, { title: f.title, target: f.target, impact: f.impact, confidencePct: f.confidencePct, params: f.params });
        cur.streak += 1;
      } else {
        this.proposals.unshift({ id: uid("prp"), ...f, streak: 1, status: "PROPOSED", createdAt: now, decidedAt: null, decidedBy: null, revertibleUntil: null, autoHold: null, outcome: null });
      }
    }
    for (const p of this.proposals) {
      // 임계값 제안은 사건이 남긴 교훈이라 조건이 풀려도 결정될 때까지 둔다
      if (p.status === "PROPOSED" && !seen.has(p.sig) && p.params.kind !== "THRESHOLD") {
        p.status = "WITHDRAWN";
        p.decidedAt = now;
        p.decidedBy = AI_ACTOR.name;
      }
    }
    this.proposals = this.proposals.slice(0, 24);
    this.syncSuggestions();
    this.describe(now, found);
    if (this.mode === "AUTO") this.tryAuto(now);
  }

  /** 임계값 표의 AI 제안값 표식은 열린 임계값 제안에서만 온다 */
  private syncSuggestions(): void {
    for (const t of this.thresholds.values()) {
      const p = this.proposals.find((x) => x.status === "PROPOSED" && x.params.kind === "THRESHOLD" && x.params.key === t.key);
      t.aiSuggested = p && p.params.kind === "THRESHOLD" ? p.params.to : null;
      t.aiReason = p ? "급증 때 증설 전에 응답이 목표를 넘었습니다" : null;
    }
  }

  private describe(now: number, found: Finding[]): void {
    if (this.mode === "STOPPED") {
      this.describeStopped();
      return;
    }
    const avg = this.readAvg === null ? null : Math.round(this.readAvg);
    const open = this.proposals.filter((p) => p.status === "PROPOSED");
    const pending = this.nodes.some((n) => n.state === "PENDING");
    const draining = this.nodes.some((n) => n.state === "DRAINING");
    const firing = this.alerts;
    const revert = this.revertNote(now, pending || draining);
    this.evidence = [];
    for (const a of firing.slice(0, 3)) {
      this.evidence.push({ id: `ev-${a.id}`, label: a.name, value: a.detail, severity: a.severity, ref: { kind: "ALERT", id: a.id } });
    }
    const covered = (kind: OpsRef["kind"]) => firing.some((a) => a.ref?.kind === kind);
    if (this.demand > BASE_DEMAND * 1.4) {
      this.evidence.push({ id: "ev-demand", label: "조회 요청", value: `평소의 ${(this.demand / BASE_DEMAND).toFixed(1)}배`, severity: null, ref: { kind: "READ_TIER" } });
    }
    if (firing.length > 0) {
      // 원인이 아니라고 말한 쪽의 값도 근거로 둔다. 가설이 무엇을 보고 배제했는지 남긴다
      this.evidence.push({ id: "ev-db", label: "db-primary CPU", value: `${this.hostCpu.get("db-primary") ?? 0}%`, severity: null, ref: { kind: "HOST", id: "db-primary" } });
      this.evidence.push({ id: "ev-app", label: "app-1 CPU", value: `${this.hostCpu.get("app-1") ?? 0}%`, severity: null, ref: { kind: "HOST", id: "app-1" } });
    } else {
      // 평시에도 무엇을 보고 이상 없다고 했는지 남긴다. 근거 없는 '이상 없음'은 공허 통과다
      if (avg !== null && !covered("READ_TIER")) {
        this.evidence.push({ id: "ev-read", label: "조회 평균 CPU", value: `${avg}% · ${this.inService().length}대`, severity: null, ref: { kind: "READ_TIER" } });
      }
      this.evidence.push({ id: "ev-p95", label: "응답 p95", value: `${this.p95}ms`, severity: null, ref: { kind: "SLO", metric: "p95" } });
      this.evidence.push({ id: "ev-lag", label: "복제 지연", value: `${this.lagSec}초`, severity: null, ref: { kind: "REPLICATION" } });
      const last = this.backup.lastSuccessAt ? Date.parse(this.backup.lastSuccessAt) : null;
      this.evidence.push({ id: "ev-backup", label: "백업", value: last === null ? "기록 없음" : `${Math.floor((now - last) / 3_600_000)}시간 전`, severity: null, ref: { kind: "BACKUP" } });
    }
    this.evidence = this.evidence.slice(0, 5);

    const scaleOut = this.value("read.scale_out_cpu");
    let outcome = "이상 없음";
    if (revert) {
      this.situation = this.revertSituation(revert, pending, draining);
      this.hypothesis = revert.cause === null
        ? "운영자가 자동 조치를 무른 뒤 지표를 다시 봅니다."
        : revert.kind === "READ_NODES" && revert.delta < 0
          ? "축소 뒤 조회 요청이 다시 늘어 줄인 대수로는 모자랐습니다. 축소 판단이 일렀습니다."
          : "자동 조치 뒤 지표가 나빠졌습니다. 조치가 지금 상황에 맞지 않았다고 보고 되돌렸습니다.";
      outcome = revert.cause === null ? "운영자 되돌림 · 복구 관찰" : "자동 되돌림 · 복구 관찰";
    } else if (pending) {
      this.situation = `조회 노드를 ${this.desired}대로 늘렸고 새 노드가 준비 중입니다. 준비되면 평균 CPU가 약 ${Math.round(this.demand / this.desired)}%로 내려갈 것으로 봅니다.`;
      this.hypothesis = "조회 트래픽 급증으로 조회 계층 용량이 모자랐습니다. 조치 효과를 관찰합니다.";
      outcome = "조치 효과 관찰";
    } else if (draining) {
      this.situation = `조회 노드를 ${this.desired}대로 줄이는 중입니다. 진행 중인 요청을 흘려보낸 뒤 내립니다.`;
      this.hypothesis = "야간 조회가 줄었습니다. 한 대를 줄여도 여유가 남습니다.";
      outcome = "조치 효과 관찰";
    } else if (avg !== null && avg >= scaleOut) {
      this.situation = `조회 요청이 평소의 ${(this.demand / BASE_DEMAND).toFixed(1)}배로 늘어 조회 노드 평균 CPU가 ${avg}%입니다. 응답 p95는 ${this.p95}ms로 목표 ${SLO.p95Ms}ms${this.p95 > SLO.p95Ms ? "를 넘었습니다" : " 안입니다"}. 주문 경로와 DB는 여유가 있습니다.`;
      this.hypothesis = this.desired >= READ_MAX
        ? "조회 계층이 최대 대수에서 포화됐습니다. 대수를 더 늘리는 것은 금지 목록(비용 자원 생성)이라 운영자 판단이 필요합니다."
        : "조회 트래픽 급증으로 조회 계층 용량이 모자랍니다. 매칭 엔진과 DB는 원인이 아닙니다.";
      outcome = `조치안 ${open.length}건`;
    } else if (found.some((f) => f.sig === "READ_NODES:-1")) {
      this.situation = `조회 요청이 줄어 조회 노드 ${this.inService().length}대의 평균 CPU가 ${avg}%입니다. 축소 임계 ${this.value("read.scale_in_cpu")}% 아래가 이어집니다.`;
      this.hypothesis = "야간 조회가 줄었습니다. 한 대를 줄여도 여유가 남습니다.";
      outcome = `조치안 ${open.length}건`;
    } else if (firing.length > 0) {
      const names = firing.map((a) => `${a.target} ${a.name}`).join(", ");
      const allowed = open.length > 0;
      this.situation = `활성 경보가 ${firing.length}건(${names}) 있습니다. ${allowed ? `허용 목록 안의 조치안 ${open.length}건을 냈습니다.` : "허용 목록에 맞는 조치가 없어 운영자 확인이 필요합니다."}`;
      this.hypothesis = allowed ? "경보 원인에 맞는 허용 조치가 있습니다." : "DB·디스크·재시작 같은 조치는 금지 목록이라 AI가 다루지 않습니다.";
      outcome = allowed ? `조치안 ${open.length}건` : "운영자 확인 필요";
    } else {
      this.situation = this.normalSituation();
      this.hypothesis = open.length > 0 ? "직전 사건에서 나온 조정 제안이 남아 있습니다." : null;
      outcome = open.length > 0 ? `남은 조치안 ${open.length}건` : "이상 없음";
    }
    this.confidence = open.length > 0 ? Math.max(...open.map((p) => p.confidencePct)) : null;
    this.judgedAt = now;
    const changed = outcome !== this.lastOutcome;
    if (changed) {
      this.judgments.unshift({ id: uid("jdg"), at: isoNow(now), summary: this.situation, outcome });
      this.judgments = this.judgments.slice(0, 30);
      this.lastOutcome = outcome;
    }
    // 경보는 3틱을 채워야 서므로 판단이 사건보다 먼저 날 수 있다. 사건이 열린 뒤 그 사건에 판단 기록이
    // 아직 없으면 같은 판단을 사건 쪽에 한 번 더 남겨 발생 → AI 판단 순서가 사건 기록에서 끊기지 않게 한다
    const incident = this.openIncident;
    const unjudged = incident !== null && !this.events.some((e) => e.incidentId === incident && e.kind === "AI_JUDGED");
    if (open.length > 0 && outcome.startsWith("조치안") && (changed || unjudged)) {
      this.log(incident, "AI_JUDGED", "AI", AI_ACTOR.name, `판단 · ${outcome} · 확신도 ${this.confidence ?? 0}%`);
    }
  }

  /** 방금 되돌린 조치가 아직 AI 문장에 남아야 하는가. 되돌린 노드가 기동·드레이닝 중이면 끝날 때까지 남긴다 */
  private revertNote(now: number, moving: boolean): RevertNote | null {
    const r = this.lastRevert;
    if (r === null) return null;
    if (now - r.at < REVERT_NOTE_MS || (r.kind === "READ_NODES" && moving)) return r;
    this.lastRevert = null;
    return null;
  }

  /** 되돌린 방향과 이유, 지금 노드 상태를 한 문장 흐름으로 적는다. 노드 상태는 지금 값에서만 말한다 */
  private revertSituation(r: RevertNote, pending: boolean, draining: boolean): string {
    const lead = r.cause === null ? `운영자(${r.by})가` : `관찰 창 안에서 ${r.cause}`;
    if (r.kind !== "READ_NODES") return `${lead} 자동 조치를 되돌렸습니다. ${r.undo}.`;
    const head = `${lead} 자동 ${r.delta < 0 ? "축소를" : "증설을"} 되돌렸습니다.`;
    if (pending) {
      return `${head} 조회 노드를 다시 ${this.desired}대로 늘리는 중이며 새 노드가 준비 중입니다. 준비되면 평균 CPU가 약 ${Math.round(this.demand / this.desired)}%로 내려갈 것으로 봅니다.`;
    }
    if (draining) return `${head} 조회 노드를 다시 ${this.desired}대로 줄이는 중입니다.`;
    return `${head} 조회 노드는 다시 ${this.desired}대입니다.`;
  }

  /** 정지 중에는 새 판단이 없다. 근거와 가설을 비우고 남은 일(관찰 창)만 적는다 */
  private describeStopped(): void {
    const s = this.stopped;
    const obs = this.observation;
    this.situation = "AI 정지 중입니다."
      + (s ? ` 운영자(${s.by})가 ${new Date(s.at).toTimeString().slice(0, 8)}에 멈췄습니다.` : "")
      + " 새 판단과 조치안을 내지 않으며, 경보와 ASG 정책은 AI 없이 그대로 동작합니다."
      + (obs ? ` 정지 전 자동 조치는 관찰 창 끝까지 보고 악화하면 되돌립니다. 관찰 중 · ${obs.action}.` : "")
      + (s && this.lastRevert?.cause && this.lastRevert.at >= s.at ? ` 정지 뒤 관찰 창 안에서 악화해 자동 조치를 되돌렸습니다. ${this.lastRevert.undo}.` : "");
    this.hypothesis = null;
    this.confidence = null;
    this.evidence = [];
  }

  private normalSituation(): string {
    const avg = this.readAvg === null ? "미수집" : `${Math.round(this.readAvg)}%`;
    return `모든 지표가 임계 안입니다. 조회 평균 CPU ${avg}, 응답 p95 ${this.p95}ms, 복제 지연 ${this.lagSec}초입니다.`;
  }

  /* ===== 자동 실행 ===== */

  /** 자동으로 실행하지 않는 이유. 없으면 null이다. 순서는 화면에 한 줄로 적을 우선순위다 */
  private holdReason(p: ProposalRec, now: number): string | null {
    if (this.observation !== null) return "한 번에 한 조치 · 관찰 창이 끝난 뒤";
    if (this.autoRuns >= this.limits.autoCap) return `이번 자동 모드 상한 ${this.limits.autoCap}회 도달`;
    const cd = this.cooldowns.get(p.params.kind);
    if (cd !== undefined && now < cd) return `쿨다운 ${mmss((cd - now) / 1000)} 남음`;
    if (this.judgedAt === null || now - this.judgedAt > AI_RULES.evidenceMaxAgeSec * 1000) return "근거가 60초보다 오래됨";
    if (p.confidencePct < this.limits.confidencePct) return `확신도 ${p.confidencePct}% · 기준 ${this.limits.confidencePct}% 미만`;
    if (p.streak < AI_RULES.sameJudgmentStreak) return `같은 판단 ${p.streak}/${AI_RULES.sameJudgmentStreak}회`;
    const q = p.params;
    if (q.kind === "THRESHOLD") {
      const t = this.thresholds.get(q.key);
      if (!t || Math.abs(q.to - q.from) > t.aiMaxStep) return "1회 변경폭 초과";
      if ((this.aiThresholdRuns.get(q.key) ?? 0) >= this.limits.thresholdPerItem) return "이 항목의 이번 자동 모드 변경 횟수 초과";
    }
    if (q.kind === "BACKUP_NOW" && this.backup.manualRunsToday >= 1) return "오늘 즉시 백업을 이미 실행함";
    return null;
  }

  private tryAuto(now: number): void {
    let chosen: ProposalRec | null = null;
    const open = this.proposals
      .filter((p) => p.status === "PROPOSED")
      .sort((a, b) => KIND_PRIORITY[a.params.kind] - KIND_PRIORITY[b.params.kind]);
    for (const p of open) {
      p.autoHold = this.holdReason(p, now);
      if (p.autoHold === null && chosen === null) chosen = p;
    }
    if (chosen === null || this.auto === null) return;
    chosen.autoHold = null;
    this.execute(chosen, AI_ACTOR, "AUTO", this.auto.approvalId, now);
    // 한 번에 한 조치다. 나머지 제안은 관찰 창 뒤로 미룬 이유를 다시 적는다
    for (const p of this.proposals) if (p.status === "PROPOSED") p.autoHold = this.holdReason(p, now);
    this.stopAtCap();
  }

  /** 이번 자동 모드의 상한에 닿았으면 수동으로 바꾸고 알린다 */
  private stopAtCap(): void {
    if (this.mode !== "AUTO" || this.autoRuns < this.limits.autoCap) return;
    this.setManual(`이번 자동 모드 상한 ${this.limits.autoCap}회 도달 · 수동 전환`, "SYSTEM", "시스템");
    this.log(this.openIncident, "NOTIFIED", "SYSTEM", "Teams", "Teams 알림 · 자동 조치 상한 도달");
  }

  private execute(p: ProposalRec, actor: OpsActorRef, how: "MANUAL" | "AUTO", approvalId: string | null, now: number): void {
    const q = p.params;
    let did = "";
    switch (q.kind) {
      case "READ_NODES":
        this.setDesired(this.desired + q.delta);
        did = `희망 대수 ${this.desired - q.delta} → ${this.desired}대`;
        break;
      case "THRESHOLD":
        this.writeThreshold(q.key, q.to, actor, how === "AUTO" ? "AI_AUTO" : "AI_APPROVED", approvalId, "AI 조치안", null, now, false);
        did = `${this.thresholds.get(q.key)?.label ?? q.key} ${q.from} → ${q.to}`;
        if (how === "AUTO") this.aiThresholdRuns.set(q.key, (this.aiThresholdRuns.get(q.key) ?? 0) + 1);
        break;
      case "SILENCE": {
        const a = this.alerts.find((x) => x.id === q.alertId);
        if (a) this.silence(a, q.minutes, actor.name, now);
        did = `${a?.name ?? "경보"} ${q.minutes}분 보류`;
        break;
      }
      case "BOT_LOAD":
        this.botOps = q.toOps;
        did = `봇 발행 ${q.fromOps} → ${q.toOps} OPS`;
        break;
      case "BACKUP_NOW":
        this.startBackup(now);
        did = "Databasus 백업 시작";
        break;
    }
    const cd = cooldownSec(q.kind, this.limits);
    if (cd > 0) this.cooldowns.set(q.kind, now + cd * 1000);
    p.decidedAt = now;
    p.decidedBy = actor.name;
    p.autoHold = null;
    p.outcome = did;
    this.lastRevert = null;
    if (how === "AUTO") {
      p.status = "AUTO_EXECUTED";
      p.revertibleUntil = now + AI_RULES.observeSec * 1000;
      this.autoRuns += 1;
      this.observation = { proposalId: p.id, startedAt: now, until: now + AI_RULES.observeSec * 1000, action: p.title, p95Before: this.p95 };
      this.log(this.openIncident, "AUTO_ACTED", "AI", AI_ACTOR.name, `자동 실행 · ${p.title} · ${did}`);
      this.log(this.openIncident, "NOTIFIED", "SYSTEM", "Teams", `Teams 알림 · 자동 조치와 되돌리기 링크`);
      this.audit(AI_ACTOR, `ai.auto:${q.kind}`, p.title, approvalId);
    } else {
      p.status = "APPROVED";
      this.log(this.openIncident, "APPROVED", "OPERATOR", actor.name, `승인 · ${p.title}`);
      this.log(this.openIncident, "ACTED", "SYSTEM", "시스템", `조치 · ${did}`);
      this.audit(actor, `ai.approve:${q.kind}`, p.title, approvalId);
    }
    // 조치 직후 상황 문장이 다음 판단(최대 5초)까지 조치 전 내용으로 남지 않게 바로 다시 적는다
    this.describe(now, this.findings());
  }

  /** 조치를 무르고 무른 방향을 조치 제목과 같은 말로 돌려준다. 백업은 되돌릴 것이 없어 호출하지 않는다 */
  private undo(p: ProposalRec, actor: OpsActorRef, now: number): string {
    const q = p.params;
    switch (q.kind) {
      case "READ_NODES": {
        const before = this.desired;
        this.setDesired(before - q.delta);
        return `조회 노드 ${q.delta > 0 ? "−" : "+"}1 (${before} → ${this.desired}대)`;
      }
      case "THRESHOLD": {
        const change = this.history.find((c) => c.key === q.key && c.to === q.to && !c.reverted);
        this.writeThreshold(q.key, q.from, actor, "REVERT", null, "자동 조치 되돌리기", change?.id ?? null, now, false);
        return `${this.thresholds.get(q.key)?.label ?? q.key} ${q.to} → ${q.from}`;
      }
      case "SILENCE": {
        const a = this.alerts.find((x) => x.id === q.alertId);
        if (a && a.state === "SILENCED") {
          a.state = a.ackBy ? "ACKED" : "FIRING";
          a.silencedUntil = null;
        }
        return `경보 보류 해제${a ? ` · ${a.name}` : ""}`;
      }
      case "BOT_LOAD":
        this.botOps = q.fromOps;
        return `봇 발행 ${q.toOps} → ${q.fromOps} OPS`;
      case "BACKUP_NOW":
        return "되돌릴 것 없음";
    }
  }

  private watchObservation(now: number): void {
    const obs = this.observation;
    if (obs === null) return;
    const p = this.proposals.find((x) => x.id === obs.proposalId);
    if (!p) {
      this.observation = null;
      return;
    }
    if (now >= obs.until) {
      this.observation = null;
      if (p.status === "AUTO_EXECUTED") p.revertibleUntil = null;
      this.log(this.openIncident, "ACTED", "SYSTEM", "시스템", `관찰 창 종료 · ${obs.action} 유지`);
      if (this.mode === "AUTO") for (const x of this.proposals) if (x.status === "PROPOSED") x.autoHold = this.holdReason(x, now);
      return;
    }
    const scaleOut = this.value("read.scale_out_cpu");
    const q = p.params;
    const slower = this.p95 > SLO.p95Ms && this.p95 > obs.p95Before + 20;
    const saturated = q.kind === "READ_NODES" && q.delta === -1 && this.readAvg !== null && this.readAvg >= scaleOut;
    if ((!slower && !saturated) || q.kind === "BACKUP_NOW") return;
    const avg = Math.round(this.readAvg ?? 0);
    const why = saturated ? `조회 평균 CPU ${avg}% ≥ ${scaleOut}%` : `응답 p95 ${this.p95}ms 악화`;
    const cause = saturated ? `조회 평균 CPU가 ${avg}%로 증설 임계 ${scaleOut}%를 넘어` : `응답 p95가 ${this.p95}ms로 목표 ${SLO.p95Ms}ms를 넘어`;
    if (this.openIncident === null) {
      // 경보가 서기 전에 되돌리므로 사건을 여기서 연다. 되돌림이 어떤 악화 때문인지 한 기록에 남긴다
      const inc: Incident = { id: uid("inc"), title: `자동 조치 뒤 악화 · ${p.target}`, severity: "warning", openedAt: isoNow(now), resolvedAt: null };
      this.incidents.unshift(inc);
      this.openIncident = inc.id;
      // 원인이 된 자동 실행 기록을 이 사건으로 묶는다. 무엇을 자동으로 했고 왜 되돌렸는지 한 기록에서 읽힌다
      for (const e of this.events) {
        if (e.incidentId === null && Date.parse(e.at) >= obs.startedAt && (e.kind === "AUTO_ACTED" || e.kind === "NOTIFIED")) e.incidentId = inc.id;
      }
      this.log(inc.id, "OPENED", "SYSTEM", "시스템", `악화 감지 · 관찰 창 안에서 ${why}`);
    }
    const undo = this.undo(p, AI_ACTOR, now);
    p.status = "AUTO_REVERTED";
    p.revertibleUntil = null;
    p.outcome = `되돌림 · ${undo} · ${why}`;
    // 카드는 결정 시각을 보여 준다. 되돌린 뒤에는 되돌린 시각이 그 자리에 와야 한다
    p.decidedAt = now;
    p.decidedBy = AI_ACTOR.name;
    this.observation = null;
    this.suppressed.set(p.sig, now + REVERT_SUPPRESS_MS);
    this.lastRevert = { at: now, kind: q.kind, delta: q.kind === "READ_NODES" ? q.delta : 0, undo, cause, by: AI_ACTOR.name };
    // 기록의 첫머리는 되돌리며 실제로 한 일이다. 무른 조치의 제목을 앞에 두면 그 조치를 한 것처럼 읽힌다
    this.log(this.openIncident, "AUTO_REVERTED", "AI", AI_ACTOR.name, `자동 되돌림 · ${undo} · 무른 조치 ${p.title} · ${why}`);
    this.audit(AI_ACTOR, `ai.auto_revert:${q.kind}`, p.title, null);
    const wasAuto = this.mode === "AUTO";
    if (wasAuto) this.setManual("악화로 자동 되돌림 · 수동 전환", "AI", AI_ACTOR.name);
    this.log(this.openIncident, "NOTIFIED", "SYSTEM", "Teams", wasAuto ? "Teams 알림 · 자동 되돌림과 수동 전환" : "Teams 알림 · 자동 되돌림");
    this.describe(now, this.findings());
  }

  private expireAuto(now: number): void {
    if (this.auto !== null && now >= this.auto.until) this.setManual("유효 시간 만료 · 수동 복귀", "SYSTEM", "시스템");
  }

  private setManual(text: string, actor: OpsActor, name: string): void {
    this.leaveAuto();
    this.mode = "MANUAL";
    this.log(this.openIncident, "MODE", actor, name, `모드 · ${text}`);
  }

  /** 자동 모드를 끝낸다. 자동 모드 중에 넓힌 한도는 여기서 적용되어 다음 활성부터 쓰인다 */
  private leaveAuto(): void {
    this.auto = null;
    for (const p of this.proposals) p.autoHold = null;
    for (const [key, to] of this.pendingLimits) {
      const def = limitDef(key);
      const from = this.limits[key];
      this.limits[key] = to;
      this.log(this.openIncident, "ACTED", "SYSTEM", "시스템", `AI 한도 적용 · ${def.label} ${from} → ${to}${def.unit} · 자동 모드가 끝나 다음 활성부터 씀`);
    }
    this.pendingLimits.clear();
  }

  /* ===== 효과 ===== */

  private setDesired(next: number): void {
    if (next < READ_MIN || next > READ_MAX) {
      throw new AdminError("CAPACITY_OUT_OF_BOUNDS", next < READ_MIN ? `최소 ${READ_MIN}대 아래로 축소 불가` : `최대 ${READ_MAX}대 위로 증설 불가`);
    }
    while (this.desired < next) {
      // 빈 자리가 없으면 내리는 중인 노드 자리에 새 노드를 띄운다. 희망 대수와 기동 중인 노드 수가 늘 같다
      const slot = this.nodes.find((n) => n.state === "OFF") ?? this.nodes.find((n) => n.state === "DRAINING");
      if (slot) {
        slot.state = "PENDING";
        slot.since = this.tick;
      }
      this.desired += 1;
    }
    while (this.desired > next) {
      const on = [...this.nodes].reverse().find((n) => n.state === "IN_SERVICE" || n.state === "PENDING");
      if (on) {
        on.state = "DRAINING";
        on.since = this.tick;
      }
      this.desired -= 1;
    }
  }

  private silence(a: AlertRec, minutes: number, by: string, now: number): void {
    a.state = "SILENCED";
    a.silencedUntil = now + minutes * 60_000;
    a.silencedBy = by;
    a.silencedBefore = true;
    this.log(a.incidentId, "SILENCED", by === AI_ACTOR.name ? "AI" : "OPERATOR", by, `보류 ${minutes}분 · ${a.name}`);
  }

  private startBackup(now: number): void {
    this.backup.running = true;
    this.backup.manualRunsToday += 1;
    this.backupRunTick = this.tick;
    this.log(null, "ACTED", "SYSTEM", "Databasus", `백업 시작 · ${new Date(now).getHours()}시 즉시 실행`);
  }

  /** 임계값 검증. 범위와 증설·축소 간격을 본다 */
  private checkThreshold(key: ThresholdKey, to: number): OpsThreshold {
    const t = this.thresholds.get(key);
    if (!t) throw new AdminError("invalid_parameter", "임계값 항목 없음");
    if (!Number.isFinite(to) || to < t.min || to > t.max) {
      throw new AdminError("invalid_parameter", `허용 범위 ${t.min}~${t.max}${t.unit} 밖`);
    }
    const out = key === "read.scale_out_cpu" ? to : this.value("read.scale_out_cpu");
    const inn = key === "read.scale_in_cpu" ? to : this.value("read.scale_in_cpu");
    if (out - inn < SCALE_GAP) {
      throw new AdminError("invalid_parameter", `증설 임계는 축소 임계보다 ${SCALE_GAP}%p 이상 높아야 함`);
    }
    return t;
  }

  private writeThreshold(key: ThresholdKey, to: number, actor: OpsActorRef, via: ThresholdVia, approvalId: string | null,
                         reason: string, revertOf: string | null, now: number, record = true): ThresholdChange {
    const t = this.checkThreshold(key, to);
    const change: ThresholdChange = {
      id: uid("thc"), key, label: t.label, unit: t.unit, from: t.value, to, at: isoNow(now), by: actor.name,
      via, approvalId, reason, revertOf, reverted: false,
    };
    if (revertOf) {
      const orig = this.history.find((c) => c.id === revertOf);
      if (orig) orig.reverted = true;
    }
    t.value = to;
    t.updatedAt = isoNow(now);
    t.updatedBy = actor.name;
    this.history.unshift(change);
    this.history = this.history.slice(0, 40);
    this.syncSuggestions();
    if (record) {
      this.log(this.openIncident, "ACTED", "OPERATOR", actor.name,
        `임계값 ${via === "REVERT" ? "되돌리기" : "변경"} · ${t.label} ${change.from} → ${to}${t.unit}`);
    }
    return change;
  }

  private log(incidentId: string | null, kind: IncidentEventKind, actor: OpsActor, actorName: string, text: string): void {
    this.events.unshift({ id: uid("evt"), at: isoNow(this.t), kind, actor, actorName, text, incidentId });
    this.events = this.events.slice(0, 120);
  }

  /* ===== 운영자 조작 ===== */

  ackAlert(id: string, actor: OpsActorRef, now: number): void {
    this.advance(now);
    const a = this.alerts.find((x) => x.id === id);
    if (!a) throw new AdminError("not_found", "이미 해제된 경보");
    if (a.ackBy) throw new AdminError("invalid_parameter", "이미 확인한 경보");
    a.ackBy = actor.name;
    a.ackAt = now;
    if (a.state === "FIRING") a.state = "ACKED";
    this.log(a.incidentId, "ACKED", "OPERATOR", actor.name, `확인 · ${a.name}`);
  }

  silenceAlert(id: string, minutes: number, actor: OpsActorRef, now: number): void {
    this.advance(now);
    const a = this.alerts.find((x) => x.id === id);
    if (!a) throw new AdminError("not_found", "이미 해제된 경보");
    if (a.severity === "critical") throw new AdminError("invalid_parameter", "장애 등급 경보는 보류 불가");
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > AI_RULES.silenceMaxMin) {
      throw new AdminError("invalid_parameter", `보류는 1~${AI_RULES.silenceMaxMin}분`);
    }
    if (a.state === "SILENCED") throw new AdminError("invalid_parameter", "이미 보류 중");
    if (a.silencedBefore) throw new AdminError("invalid_parameter", "같은 경보 연속 보류 불가");
    this.silence(a, minutes, actor.name, now);
  }

  /** 임계값 조치안의 목표. 승인 티켓을 발급하려는 어댑터가 읽는다 */
  thresholdProposal(id: string): { key: ThresholdKey; label: string; unit: ThresholdUnit; from: number; to: number } | null {
    const p = this.proposals.find((x) => x.id === id);
    if (!p || p.params.kind !== "THRESHOLD") return null;
    const t = this.thresholds.get(p.params.key);
    return t ? { key: t.key, label: t.label, unit: t.unit, from: p.params.from, to: p.params.to } : null;
  }

  /** 결정 전에 막힐 이유가 있는지 본다. 승인 티켓을 발급하기 전에 불러 쓸모없는 티켓이 남지 않게 한다 */
  checkDecision(id: string, decision: AiDecision, now: number): void {
    this.advance(now);
    const p = this.proposals.find((x) => x.id === id);
    if (!p) throw new AdminError("not_found", "조치안 없음");
    if (p.status !== "PROPOSED") throw new AdminError("invalid_parameter", "이미 결정된 조치안");
    if (decision !== "APPROVE") return;
    const q = p.params;
    if (q.kind === "READ_NODES" && (this.desired + q.delta < READ_MIN || this.desired + q.delta > READ_MAX)) {
      throw new AdminError("CAPACITY_OUT_OF_BOUNDS", "조회 노드 범위 밖");
    }
    if (q.kind === "THRESHOLD") {
      if (this.value(q.key) !== q.from) throw new AdminError("invalid_parameter", "현재값이 조치안 뒤에 바뀜");
      this.checkThreshold(q.key, q.to);
    }
  }

  decide(id: string, decision: AiDecision, actor: OpsActorRef, now: number, approvalId: string | null = null): void {
    this.checkDecision(id, decision, now);
    const p = this.proposals.find((x) => x.id === id);
    if (!p) return;
    if (decision === "APPROVE") {
      if (p.params.kind === "THRESHOLD" && approvalId === null) {
        throw new AdminError("admin_forbidden", "임계값 변경은 승인 티켓 필요");
      }
      this.execute(p, actor, "MANUAL", approvalId, now);
      return;
    }
    p.status = decision === "REJECT" ? "REJECTED" : "DEFERRED";
    p.decidedAt = now;
    p.decidedBy = actor.name;
    // 거절한 조치는 10분, 나중에로 미룬 조치는 5분 동안 다시 내지 않는다
    this.suppressed.set(p.sig, now + (decision === "REJECT" ? 10 : 5) * 60_000);
    this.syncSuggestions();
    this.log(this.openIncident, decision === "REJECT" ? "REJECTED" : "DEFERRED", "OPERATOR", actor.name,
      `${decision === "REJECT" ? "거절" : "나중에"} · ${p.title}`);
    this.audit(actor, `ai.${decision === "REJECT" ? "reject" : "defer"}:${p.params.kind}`, p.title, null);
  }

  /** 운영자가 자동 조치를 되돌린다. 되돌리기 창(관찰 창) 안에서만 된다 */
  revert(id: string, actor: OpsActorRef, now: number): void {
    this.advance(now);
    const p = this.proposals.find((x) => x.id === id);
    if (!p) throw new AdminError("not_found", "조치안 없음");
    if (p.status !== "AUTO_EXECUTED" || p.revertibleUntil === null || now > p.revertibleUntil) {
      throw new AdminError("invalid_parameter", "되돌리기 창이 지났거나 자동 조치가 아님");
    }
    if (p.params.kind === "BACKUP_NOW") throw new AdminError("invalid_parameter", "백업은 되돌릴 것이 없음");
    const undo = this.undo(p, actor, now);
    p.status = "REVERTED";
    p.revertibleUntil = null;
    p.outcome = `되돌림 · ${undo}`;
    p.decidedAt = now;
    p.decidedBy = actor.name;
    if (this.observation?.proposalId === p.id) this.observation = null;
    this.suppressed.set(p.sig, now + REVERT_SUPPRESS_MS);
    this.lastRevert = { at: now, kind: p.params.kind, delta: p.params.kind === "READ_NODES" ? p.params.delta : 0, undo, cause: null, by: actor.name };
    this.log(this.openIncident, "REVERTED", "OPERATOR", actor.name, `되돌리기 · ${undo} · 무른 조치 ${p.title}`);
    this.audit(actor, `ai.revert:${p.params.kind}`, p.title, null);
    this.describe(now, this.findings());
  }

  /** 자동 모드를 켤 수 있는지 본다. 승인 티켓을 발급하기 전에 부른다 */
  checkAuto(hours: number, reason: string, now: number): void {
    this.advance(now);
    if (!(AUTO_HOURS as readonly number[]).includes(hours) || hours > AI_RULES.autoMaxHours) {
      throw new AdminError("invalid_parameter", `유효 시간은 ${AUTO_HOURS.join("·")}시간 중 하나`);
    }
    if (reason.trim().length < 2) throw new AdminError("invalid_parameter", "사유 입력 필요");
    if (this.mode === "STOPPED") throw new AdminError("invalid_parameter", "AI 정지 중. 재개해 수동으로 돌린 뒤 켜기");
    if (this.mode === "AUTO") throw new AdminError("invalid_parameter", "이미 자동 모드");
  }

  enableAuto(hours: number, reason: string, actor: OpsActorRef, approvalId: string, now: number): void {
    this.checkAuto(hours, reason, now);
    this.mode = "AUTO";
    this.auto = { since: now, until: now + hours * 3_600_000, reason: reason.trim(), by: actor.name, approvalId };
    // 상한은 활성 1회당이다. 끄고 다시 켜려면 승인 티켓·사유·감사를 다시 거치므로 횟수 우회가 통제된다
    this.autoRuns = 0;
    this.aiThresholdRuns.clear();
    this.log(this.openIncident, "MODE", "OPERATOR", actor.name, `모드 · 자동 켜기 ${hours}시간 · 사유 "${reason.trim()}"`);
    for (const p of this.proposals) if (p.status === "PROPOSED") p.autoHold = this.holdReason(p, now);
  }

  /** 자동에서 수동으로 돌아간다. 승인 없이 세션만 있으면 된다. AI 판단은 계속한다 */
  stopAuto(actor: OpsActorRef, now: number): void {
    this.advance(now);
    if (this.mode !== "AUTO") throw new AdminError("invalid_parameter", "자동 모드가 아님");
    this.setManual("자동 끄기 · 수동 전환", "OPERATOR", actor.name);
  }

  /**
   * AI 즉시 정지. 두 모드 어디서든 승인 없이 누구나 누른다. 자동을 끝내고 새 판단을 멈추며 대기 중인
   * 조치안을 만료시킨다. 진행 중인 관찰 창은 끝까지 보고, 악화하면 되돌리는 것은 안전 방향이라 남긴다
   */
  stopAi(actor: OpsActorRef, now: number): void {
    this.advance(now);
    if (this.mode === "STOPPED") throw new AdminError("invalid_parameter", "이미 AI 정지 중");
    const wasAuto = this.mode === "AUTO";
    if (wasAuto) this.leaveAuto();
    // 사건 기록과 같은 시계(틱)로 적어 모드 줄의 정지 시각과 타임라인 시각이 어긋나지 않게 한다
    const at = this.t;
    this.mode = "STOPPED";
    this.stopped = { at, by: actor.name };
    this.thinking = false;
    let expired = 0;
    for (const p of this.proposals) {
      if (p.status !== "PROPOSED") continue;
      p.status = "EXPIRED";
      p.decidedAt = at;
      p.decidedBy = actor.name;
      p.autoHold = null;
      p.outcome = "AI 정지로 만료";
      expired += 1;
    }
    this.syncSuggestions();
    const parts = ["모드 · AI 즉시 정지"];
    if (wasAuto) parts.push("자동 모드 해제");
    if (expired > 0) parts.push(`대기 조치안 ${expired}건 만료`);
    if (this.observation) parts.push("관찰 창은 끝까지 봄");
    this.log(this.openIncident, "MODE", "OPERATOR", actor.name, parts.join(" · "));
    this.judgments.unshift({ id: uid("jdg"), at: isoNow(at), summary: `운영자(${actor.name})가 AI를 정지했습니다. 새 판단과 조치안을 내지 않습니다.`, outcome: "AI 정지" });
    this.lastOutcome = "AI 정지";
    this.describe(now, []);
  }

  /** AI 재개. 사유를 받아 수동으로만 돌린다. 자동은 다시 ops.ai.auto 승인을 거친다 */
  resumeAi(reason: string, actor: OpsActorRef, now: number): void {
    this.advance(now);
    if (this.mode !== "STOPPED") throw new AdminError("invalid_parameter", "AI 정지 중이 아님");
    if (reason.trim().length < 2) throw new AdminError("invalid_parameter", "사유 입력 필요");
    this.mode = "MANUAL";
    this.stopped = null;
    this.log(this.openIncident, "MODE", "OPERATOR", actor.name, `모드 · AI 재개(수동) · 사유 "${reason.trim()}"`);
    this.describe(now, this.findings());
  }

  /** 한도 변경을 적용하기 전에 막힐 이유를 본다. 하드 범위, 요청 뒤 바뀐 값, 빈 변경을 거절한다 */
  checkLimit(key: AiLimitKey, from: number, to: number, now: number): void {
    this.advance(now);
    const def = limitDef(key);
    if (!Number.isInteger(to) || to < def.min || to > def.max) {
      throw new AdminError("invalid_parameter", `허용 범위 ${def.min}~${def.max}${def.unit} 밖`);
    }
    if ((this.pendingLimits.get(key) ?? this.limits[key]) !== from) {
      throw new AdminError("invalid_parameter", "현재값이 요청 뒤 바뀜. 다시 요청 필요");
    }
    if (to === from) throw new AdminError("invalid_parameter", "바뀐 값 없음");
  }

  /**
   * AI 한도 변경(ops.ai.limits). AI 권한을 좁히는 변경은 곧바로, 넓히는 변경은 자동 모드 중이면
   * 이번 자동 모드가 끝난 뒤(다음 활성부터) 적용한다
   */
  setLimit(key: AiLimitKey, from: number, to: number, actor: OpsActorRef, approvalId: string, reason: string,
           revertOf: string | null, now: number): void {
    // AI 운영 계정은 자기 한도를 바꾸지 못한다(ADR 0035 금지 목록 8)
    if (actor.id === AI_ACTOR.id) throw new AdminError("admin_forbidden", "AI는 한도를 바꿀 수 없음");
    if (!approvalId) throw new AdminError("admin_forbidden", "한도 변경은 승인 티켓 필요");
    if (reason.trim().length < 2) throw new AdminError("invalid_parameter", "사유 입력 필요");
    this.checkLimit(key, from, to, now);
    const def = limitDef(key);
    const cur = this.limits[key];
    const tighter = to === cur || (def.tighterWhenHigher ? to > cur : to < cur);
    const apply: AiLimitApply = this.mode === "AUTO" && !tighter ? "NEXT_AUTO" : "NOW";
    if (apply === "NOW") {
      this.limits[key] = to;
      this.pendingLimits.delete(key);
    } else {
      this.pendingLimits.set(key, to);
    }
    this.limitMeta.set(key, { at: now, by: actor.name });
    if (revertOf) {
      const orig = this.limitHistory.find((c) => c.id === revertOf);
      if (orig) orig.reverted = true;
    }
    this.limitHistory.unshift({
      id: uid("alc"), key, label: def.label, unit: def.unit, from, to, at: isoNow(now), by: actor.name,
      approvalId, reason: reason.trim(), revertOf, reverted: false, apply,
    });
    this.limitHistory = this.limitHistory.slice(0, 40);
    this.log(this.openIncident, "ACTED", "OPERATOR", actor.name,
      `AI 한도 ${revertOf ? "되돌리기" : "변경"} · ${def.label} ${from} → ${to}${def.unit} · ${apply === "NOW" ? "곧바로 적용" : "다음 자동 활성부터"}`);
    if (this.mode === "AUTO") {
      for (const p of this.proposals) if (p.status === "PROPOSED") p.autoHold = this.holdReason(p, now);
      this.stopAtCap();
    }
  }

  applyThreshold(key: ThresholdKey, from: number, to: number, actor: OpsActorRef, approvalId: string, reason: string,
                 revertOf: string | null, proposalId: string | null, now: number): void {
    this.advance(now);
    if (this.value(key) !== from) throw new AdminError("invalid_parameter", "현재값이 요청 뒤 바뀜. 다시 요청 필요");
    if (proposalId !== null) {
      this.decide(proposalId, "APPROVE", actor, now, approvalId);
      return;
    }
    this.writeThreshold(key, to, actor, revertOf ? "REVERT" : "MANUAL", approvalId, reason, revertOf, now);
    // 운영자가 바꾼 항목은 같은 쿨다운을 건다. 사람이 바꾼 직후 AI가 반대로 되돌리지 않게 한다
    this.cooldowns.set("THRESHOLD", now + AI_RULES.thresholdCooldownSec * 1000);
    const open = this.proposals.find((p) => p.status === "PROPOSED" && p.params.kind === "THRESHOLD" && p.params.key === key);
    if (open) {
      open.status = "WITHDRAWN";
      open.decidedAt = now;
      open.decidedBy = actor.name;
      this.syncSuggestions();
    }
  }

  checkThresholdChange(key: ThresholdKey, from: number, to: number, now: number): void {
    this.advance(now);
    if (this.value(key) !== from) throw new AdminError("invalid_parameter", "현재값이 요청 뒤 바뀜. 다시 요청 필요");
    this.checkThreshold(key, to);
  }

  runBackup(actor: OpsActorRef, now: number): void {
    this.advance(now);
    if (this.backup.running) throw new AdminError("invalid_parameter", "백업 진행 중");
    this.startBackup(now);
    this.audit(actor, "ops.backup.run", "Databasus exchange", null);
  }

  /**
   * 운영자가 용량·부하 화면에서 조회 노드 희망 대수를 바꾼다. AI 조치와 같은 희망 대수를 바꾸므로 관제센터와
   * 용량 화면의 대수가 함께 움직인다. 사람이 이미 정한 대수를 겨냥한 열린 조치안은 거두고, AI 자동 실행이
   * 곧바로 반대로 되돌리지 않게 조회 노드 쿨다운을 건다. 감사는 어댑터가 capacity.* 로 남긴다
   */
  scaleReadNodes(delta: 1 | -1, actor: OpsActorRef, now: number): void {
    this.advance(now);
    const before = this.desired;
    this.setDesired(before + delta);
    this.cooldowns.set("READ_NODES", now + AI_RULES.readNodesCooldownSec * 1000);
    let withdrew = false;
    for (const p of this.proposals) {
      if (p.status !== "PROPOSED" || p.params.kind !== "READ_NODES") continue;
      p.status = "WITHDRAWN";
      p.decidedAt = now;
      p.decidedBy = actor.name;
      withdrew = true;
    }
    if (withdrew) this.syncSuggestions();
    this.log(this.openIncident, "ACTED", "OPERATOR", actor.name, `조회 노드 ${delta > 0 ? "+1" : "−1"} (${before} → ${this.desired}대) · 용량·부하 화면`);
    this.describe(now, this.findings());
  }

  startScenario(id: OpsScenarioId, now: number): void {
    this.advance(now);
    if (this.scenario !== null && now - this.scenario.startedAt < SCENARIO_END_SEC * 1000) {
      throw new AdminError("invalid_parameter", "시나리오 재생 중. 초기화 뒤 다시 시작");
    }
    this.scenario = { id, startedAt: now };
    if (id === "NIGHT_SCALE_IN") {
      // 야간 시나리오는 세 대로 떠 있는 데서 시작한다. 조치가 아니라 무대 준비다
      this.nodes = this.nodes.map((n) => ({ ...n, state: "IN_SERVICE", since: this.tick }));
      this.desired = READ_MAX;
    }
    this.log(null, "MODE", "SYSTEM", "시나리오", `시나리오 시작 · ${OPS_SCENARIO_TITLE[id]}`);
  }

  /* ===== 판독 ===== */

  snapshot(now: number): ControlCenter {
    this.advance(now);
    const at = isoNow(this.t);
    const hosts: OpsHost[] = HOST_DEFS.map((h) => ({
      id: h.id, role: h.role, instanceType: "t3.small", az: h.az, collect: "OK", collectedAt: at,
      cpuPct: this.hostCpu.get(h.id) ?? null, memPct: Math.round(h.mem + wobble(this.tick, h.mem, 1)),
      diskUsedPct: h.disk, cpuCredits: Math.round(this.credits.get(h.id) ?? 0),
      partition: h.partition === null ? null : { id: h.partition.id, markets: [...h.partition.markets] },
    }));
    const lat = [...this.aiLatency].sort((a, b) => a - b);
    const q = (p: number) => (lat.length === 0 ? null : lat[Math.min(lat.length - 1, Math.floor(p * lat.length))]);
    const aiServer: AiServer = {
      id: "ai-1",
      hardware: "DGX Spark",
      runtime: "Ollama",
      model: "평가 중",
      collectedAt: at,
      gpuPct: Math.round(this.aiBusy ? 74 + wobble(this.tick, 12, 9) : 4 + Math.abs(wobble(this.tick, 12, 2))),
      memUsedGb: round1(AI_MEM_RESIDENT_GB + (this.aiBusy ? 1.6 : 0) + Math.abs(wobble(this.tick, 13, 0.3))),
      memTotalGb: AI_MEM_TOTAL_GB,
      p50Ms: q(0.5),
      p95Ms: q(0.95),
      tokensPerSec: this.aiBusy ? Math.round(46 + wobble(this.tick, 14, 6)) : 0,
      queue: this.aiBusy ? 1 + (this.alerts.length > 1 ? 1 : 0) : 0,
      link: "OK",
      busy: this.aiBusy,
      served: this.aiServed,
      lastServedAt: this.aiServedAt === null ? null : isoNow(this.aiServedAt),
    };
    const serving = this.inService();
    const readNodes: ReadNode[] = this.nodes.map((n, i) => ({
      id: n.id,
      state: n.state,
      cpuPct: n.state === "IN_SERVICE" && this.readAvg !== null ? Math.round(this.readAvg + (i - 1) * 1.5) : n.state === "PENDING" ? 4 : n.state === "DRAINING" ? 12 : null,
    }));
    const alerts: OpsAlert[] = this.alerts.map((a) => ({
      id: a.id, name: a.name, severity: a.severity, target: a.target, detail: a.detail, since: isoNow(a.since),
      thresholdKey: a.thresholdKey, ref: a.ref, state: a.state, ackBy: a.ackBy, ackAt: a.ackAt === null ? null : isoNow(a.ackAt),
      silencedUntil: a.silencedUntil === null ? null : isoNow(a.silencedUntil), silencedBy: a.silencedBy, silencedBefore: a.silencedBefore,
    }));
    // 열린 조치안이 앞이고 그 안에서는 서비스에 가까운 조치부터다. 결정된 것은 최근 순서다
    const open = this.proposals.filter((p) => p.status === "PROPOSED")
      .sort((a, b) => KIND_PRIORITY[a.params.kind] - KIND_PRIORITY[b.params.kind]);
    const decided = this.proposals.filter((p) => p.status !== "PROPOSED" && p.decidedAt !== null && now - p.decidedAt < 15 * 60_000)
      .sort((a, b) => (b.decidedAt ?? 0) - (a.decidedAt ?? 0));
    const proposals: AiProposal[] = [...open, ...decided].slice(0, 8).map((p) => ({
      id: p.id, kind: p.params.kind, title: p.title, target: p.target, impact: p.impact, risk: p.risk, revertPlan: p.revertPlan,
      confidencePct: p.confidencePct, status: p.status, createdAt: isoNow(p.createdAt),
      decidedAt: p.decidedAt === null ? null : isoNow(p.decidedAt), decidedBy: p.decidedBy,
      revertibleUntil: p.revertibleUntil === null ? null : isoNow(p.revertibleUntil), autoHold: p.autoHold, outcome: p.outcome,
    }));
    const ai: AiDesk = {
      mode: this.mode,
      auto: this.auto === null ? null : { since: isoNow(this.auto.since), until: isoNow(this.auto.until), reason: this.auto.reason, by: this.auto.by, approvalId: this.auto.approvalId },
      thinking: this.thinking,
      model: "시뮬레이터 규칙 판단(모델 호출 없음)",
      judgedAt: this.judgedAt === null ? null : isoNow(this.judgedAt),
      evidenceAt: this.judgedAt === null ? null : isoNow(this.judgedAt - 1000),
      situation: this.situation,
      hypothesis: this.hypothesis,
      confidencePct: this.confidence,
      evidence: this.evidence.map((e) => ({ ...e })),
      proposals,
      history: this.judgments.slice(0, 12).map((j) => ({ ...j })),
      autoUsed: this.autoRuns,
      autoCap: this.limits.autoCap,
      stopped: this.stopped === null ? null : { at: isoNow(this.stopped.at), by: this.stopped.by },
      limits: AI_LIMIT_DEFS.map((d) => {
        const meta = this.limitMeta.get(d.key);
        return {
          key: d.key, label: d.label, desc: d.desc, unit: d.unit, value: this.limits[d.key], pending: this.pendingLimits.get(d.key) ?? null,
          min: d.min, max: d.max, tighterWhenHigher: d.tighterWhenHigher,
          updatedAt: meta ? isoNow(meta.at) : null, updatedBy: meta ? meta.by : null,
        };
      }),
      limitHistory: this.limitHistory.map((h) => ({ ...h })),
      cooldowns: [...this.cooldowns].filter(([, until]) => until > now).map(([kind, until]) => ({ kind, until: isoNow(until) })),
      observation: this.observation === null ? null : { startedAt: isoNow(this.observation.startedAt), until: isoNow(this.observation.until), action: this.observation.action },
      // 감사 로그는 어댑터가 들고 있어 거기서 센다(simAdmin)
      actionStats: {},
    };
    return {
      origin: "SIM",
      environment: "SIMULATOR",
      generatedAt: isoNow(now),
      collectedAt: at,
      summary: { state: "READY", data: { availabilityPct: this.availability, p95Ms: this.p95, errorRatePct: this.errors, slo: { ...SLO } } },
      hosts: { state: "READY", data: hosts },
      readTier: { state: "READY", data: { min: READ_MIN, max: READ_MAX, desired: this.desired, nodes: readNodes, avgCpuPct: this.readAvg === null || serving.length === 0 ? null : Math.round(this.readAvg) } },
      replication: { state: "READY", data: { primary: "db-primary", standby: "db-standby", stream: "STREAMING", lagBytes: this.lagBytes, lagSec: this.lagSec } },
      alerts: { state: "READY", data: alerts },
      incidents: { state: "READY", data: { incidents: this.incidents.map((i) => ({ ...i })), events: this.events.map((e) => ({ ...e })) } },
      jobs: {
        state: "READY",
        data: {
          backup: { ...this.backup, offsite: { ...this.backup.offsite }, restoreCheck: { ...this.backup.restoreCheck } },
          certificate: { domain: "www.sang.it.kr", expiresAt: isoNow(this.certExpires) },
          dailyReport: { hour: 9, channel: "Teams", lastSentAt: this.reportSent === null ? null : isoNow(this.reportSent) },
        },
      },
      thresholds: { state: "READY", data: { items: THRESHOLD_DEFS.map((d) => ({ ...(this.thresholds.get(d.key) as OpsThreshold) })), history: this.history.map((h) => ({ ...h })) } },
      ai: { state: "READY", data: ai },
      aiServer: { state: "READY", data: aiServer },
      trends: { state: "READY", data: this.trends.build(this.t, this.incidents) },
      scenario: this.scenario === null ? null : {
        id: this.scenario.id, title: OPS_SCENARIO_TITLE[this.scenario.id], startedAt: isoNow(this.scenario.startedAt),
        elapsedSec: Math.max(0, Math.floor((this.t - this.scenario.startedAt) / 1000)),
      },
      // 시뮬레이터가 흉내 내는 원천은 모두 붙어 있는 것으로 둔다. 확인 시각은 이 판의 시각이다
      sources: SOURCE_NAMES.map((name) => ({ name, state: "READY", checkedAt: at, reason: null })),
    };
  }
}
