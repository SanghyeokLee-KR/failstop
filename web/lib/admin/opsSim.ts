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
import { AI_INITIAL, AI_RULES, AUTO_HOURS, cooldownSec } from "./aiPolicy";
import type {
  AiActionKind,
  AiDecision,
  AiDesk,
  AiEvidence,
  AiJudgment,
  AiMode,
  AiProposal,
  AiProposalStatus,
  AiRisk,
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
  OpsRef,
  OpsScenarioId,
  OpsSlo,
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
  { key: "host.cpu_alert", label: "호스트 CPU 경보", appliesTo: "네 호스트 CPU", unit: "%", value: 85, min: 60, max: 95, step: 1, aiMaxStep: 5 },
  { key: "host.mem_alert", label: "메모리 경보", appliesTo: "네 호스트 메모리", unit: "%", value: 90, min: 70, max: 95, step: 1, aiMaxStep: 5 },
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
}

const HOST_DEFS: HostDef[] = [
  { id: "app-1", role: "APP", az: "ap-northeast-2a", mem: 64, disk: 41, credits: 288 },
  { id: "db-primary", role: "DB_PRIMARY", az: "ap-northeast-2c", mem: 72, disk: 58, credits: 412 },
  { id: "db-standby", role: "DB_STANDBY", az: "ap-northeast-2a", mem: 55, disk: 57, credits: 540 },
  { id: "bot-1", role: "BOT_OBS", az: "ap-northeast-2c", mem: 78, disk: 67, credits: 176 },
];

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

function sameLocalDay(a: number, b: number): boolean {
  const x = new Date(a);
  const y = new Date(b);
  return x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate();
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
  private autoLog: number[] = [];
  private aiThresholdLog: { key: ThresholdKey; at: number }[] = [];
  private cooldowns = new Map<AiActionKind, number>();
  private observation: Observation | null = null;

  private backup: OpsBackup;
  private backupRunTick: number | null = null;
  private readonly certExpires: number;
  private reportSent: number | null;

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

  /** 오늘 앞서 있었던 사건 둘. 평균 복구 시간 칸이 첫 화면부터 판독되게 한다 */
  private seedHistory(now: number): void {
    const add = (openedAgoMin: number, minutes: number, title: string, severity: AlertSeverity, steps: [number, IncidentEventKind, OpsActor, string, string][]) => {
      const opened = now - openedAgoMin * 60_000;
      const inc: Incident = { id: uid("inc"), title, severity, openedAt: isoNow(opened), resolvedAt: isoNow(opened + minutes * 60_000) };
      this.incidents.unshift(inc);
      for (const [offMin, kind, actor, name, text] of steps) {
        this.events.unshift({ id: uid("evt"), at: isoNow(opened + offMin * 60_000), kind, actor, actorName: name, text, incidentId: inc.id });
      }
    };
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
    this.progressNodes();
    this.progressBackup(now);
    this.measure();
    this.evaluateAlerts(now);
    this.settleIncident(now);
    this.expireSilences(now);
    this.expireAuto(now);
    this.watchObservation(now);
    const phase = this.tick % JUDGE_EVERY;
    // 이상이 있을 때만 판단 직전 두 틱을 '판단 중'으로 둔다. 평시에 늘 도는 표시는 소음이다
    this.thinking = (phase === JUDGE_EVERY - 2 || phase === JUDGE_EVERY - 1) && this.hasConcern();
    if (phase === 0) this.judge(now);
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
    this.hostCpu.set("app-1", Math.round(24 + this.botOps * 0.3 + wobble(this.tick, 3, 3)));
    this.hostCpu.set("db-primary", Math.round(16 + this.demand * 0.08 + wobble(this.tick, 4, 2)));
    this.hostCpu.set("db-standby", Math.round(8 + wobble(this.tick, 5, 1.5)));
    this.hostCpu.set("bot-1", Math.round(28 + this.botOps * 0.45 + wobble(this.tick, 6, 3)));
    this.lagSec = round1(0.2 + Math.abs(wobble(this.tick, 7, 0.2)));
    this.lagBytes = Math.round(12_288 + wobble(this.tick, 8, 3000));
    const bot = this.credits.get("bot-1") ?? 0;
    this.credits.set("bot-1", Math.max(0, bot - 0.02));
  }

  private progressNodes(): void {
    for (const n of this.nodes) {
      if (n.state === "PENDING" && this.tick - n.since >= NODE_BOOT_TICKS) {
        n.state = "IN_SERVICE";
        n.since = this.tick;
      } else if (n.state === "DRAINING" && this.tick - n.since >= NODE_DRAIN_TICKS) {
        n.state = "OFF";
        n.since = this.tick;
      }
    }
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
        impact: `평균 CPU ${Math.round(avg)}% → 약 ${est}% 예상. t3.small 1대 비용이 시간 단위로 는다`,
        risk: "LOW", revertPlan: `조회 노드 −1. 쿨다운 ${AI_RULES.readNodesCooldownSec / 60}분 뒤 가능`,
        confidencePct: Math.round(60 + Math.min(24, (avg - scaleOut) * 1.6) + (this.p95 > SLO.p95Ms ? 4 : 0)),
      });
    }
    if (avg !== null && avg <= scaleIn && this.desired > READ_MIN && this.lowTicks >= 4 && !pending) {
      const est = Math.round(this.demand / Math.max(1, serving - 1));
      out.push({
        sig: "READ_NODES:-1", params: { kind: "READ_NODES", delta: -1 },
        title: `조회 노드 −1 (${this.desired} → ${this.desired - 1}대)`, target: "조회 ASG",
        impact: `평균 CPU ${Math.round(avg)}% → 약 ${est}% 예상. 한 대 비용이 준다. 진행 중인 요청을 흘려보낸 뒤 내린다`,
        risk: "LOW", revertPlan: `조회 노드 +1. 관찰 창 안에서 악화하면 자동으로 되돌린다`,
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
          impact: "다음 급증에서 증설이 약 1분 먼저 시작된다. 평시 증설이 잦아질 수 있다",
          risk: "LOW", revertPlan: "임계값 이력에서 되돌리기. 같은 승인 흐름을 거친다",
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
        impact: `bot-1 CPU ${botCpu}% → 약 ${Math.round(28 + toOps * 0.45)}% 예상. 관측 수집 여유를 지킨다`,
        risk: "LOW", revertPlan: `봇 발행 ${this.botOps} OPS로 되돌리기`,
        confidencePct: 86,
      });
    }
    for (const a of this.alerts) {
      if (a.severity !== "info" || a.state === "SILENCED" || a.silencedBefore) continue;
      out.push({
        sig: `SILENCE:${a.id}`, params: { kind: "SILENCE", alertId: a.id, minutes: AI_RULES.silenceMaxMin },
        title: `경보 보류 ${AI_RULES.silenceMaxMin}분 · ${a.name}`, target: `${a.target} 경보`,
        impact: "정보 등급이라 서비스 영향이 없다. 보류 동안 다른 경보는 그대로 울린다",
        risk: "LOW", revertPlan: "보류 해제. 30분 뒤에는 저절로 풀린다",
        confidencePct: 83,
      });
    }
    const backupLate = this.alerts.some((a) => a.id === "backup_delay");
    const botDiskFree = 100 - (HOST_DEFS.find((h) => h.id === "bot-1")?.disk ?? 100);
    if (backupLate && !this.backup.running && this.backup.manualRunsToday < 1 && botDiskFree >= AI_INITIAL.backupDiskFreePct) {
      out.push({
        sig: "BACKUP_NOW", params: { kind: "BACKUP_NOW" },
        title: "백업 즉시 실행 (Databasus)", target: "exchange DB",
        impact: "읽기 전용 계정으로 전체 백업과 외부 보관, 복원 검증을 한 번 돌린다. 운영 DB 쓰기에는 영향이 없다",
        risk: "LOW", revertPlan: "되돌릴 것 없음. 백업 한 벌이 늘 뿐이다",
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
        this.proposals.unshift({ id: uid("prp"), ...f, streak: 1, status: "PROPOSED", createdAt: now, decidedAt: null, decidedBy: null, revertibleUntil: null, autoHold: null });
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
      t.aiReason = p ? "급증 때 증설 전에 응답이 목표를 넘었다" : null;
    }
  }

  private describe(now: number, found: Finding[]): void {
    const avg = this.readAvg === null ? null : Math.round(this.readAvg);
    const open = this.proposals.filter((p) => p.status === "PROPOSED");
    const pending = this.nodes.some((n) => n.state === "PENDING");
    const draining = this.nodes.some((n) => n.state === "DRAINING");
    const firing = this.alerts;
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
    if (pending) {
      this.situation = `조회 노드를 ${this.desired}대로 늘렸고 새 노드가 준비 중이다. 준비되면 평균 CPU가 약 ${Math.round(this.demand / this.desired)}%로 내려갈 것으로 본다.`;
      this.hypothesis = "조회 트래픽 급증으로 조회 계층 용량이 모자랐다. 조치 효과를 관찰한다.";
      outcome = "조치 효과 관찰";
    } else if (draining) {
      this.situation = `조회 노드를 ${this.desired}대로 줄이는 중이다. 진행 중인 요청을 흘려보낸 뒤 내린다.`;
      this.hypothesis = "야간 조회 감소. 한 대를 줄여도 여유가 남는다.";
      outcome = "조치 효과 관찰";
    } else if (avg !== null && avg >= scaleOut) {
      this.situation = `조회 요청이 평소의 ${(this.demand / BASE_DEMAND).toFixed(1)}배로 늘어 조회 노드 평균 CPU가 ${avg}%다. 응답 p95는 ${this.p95}ms로 목표 ${SLO.p95Ms}ms${this.p95 > SLO.p95Ms ? "를 넘었다" : " 안이다"}. 주문 경로와 DB는 여유가 있다.`;
      this.hypothesis = this.desired >= READ_MAX
        ? "조회 계층이 최대 대수에서 포화됐다. 대수를 더 늘리는 것은 금지 목록(비용 자원 생성)이라 운영자 판단이 필요하다."
        : "조회 트래픽 급증으로 조회 계층 용량이 모자라다. 매칭 엔진과 DB는 원인이 아니다.";
      outcome = `조치안 ${open.length}건`;
    } else if (found.some((f) => f.sig === "READ_NODES:-1")) {
      this.situation = `조회 요청이 줄어 조회 노드 ${this.inService().length}대의 평균 CPU가 ${avg}%다. 축소 임계 ${this.value("read.scale_in_cpu")}% 아래가 이어진다.`;
      this.hypothesis = "야간 조회 감소. 한 대를 줄여도 여유가 남는다.";
      outcome = `조치안 ${open.length}건`;
    } else if (firing.length > 0) {
      const names = firing.map((a) => `${a.target} ${a.name}`).join(", ");
      const allowed = open.length > 0;
      this.situation = `활성 경보 ${firing.length}건(${names}). ${allowed ? `허용 목록 안의 조치안 ${open.length}건을 냈다.` : "허용 목록에 맞는 조치가 없어 운영자 확인이 필요하다."}`;
      this.hypothesis = allowed ? "경보 원인에 맞는 허용 조치가 있다." : "DB·디스크·재시작 같은 조치는 금지 목록이라 AI가 다루지 않는다.";
      outcome = allowed ? `조치안 ${open.length}건` : "운영자 확인 필요";
    } else {
      this.situation = this.normalSituation();
      this.hypothesis = open.length > 0 ? "직전 사건에서 나온 조정 제안이 남아 있다." : null;
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

  private normalSituation(): string {
    const avg = this.readAvg === null ? "미수집" : `${Math.round(this.readAvg)}%`;
    return `모든 지표가 임계 안이다. 조회 평균 CPU ${avg}, 응답 p95 ${this.p95}ms, 복제 지연 ${this.lagSec}초.`;
  }

  /* ===== 자동 실행 ===== */

  private autoToday(now: number): number {
    return this.autoLog.filter((at) => sameLocalDay(at, now)).length;
  }

  /** 자동으로 실행하지 않는 이유. 없으면 null이다. 순서는 화면에 한 줄로 적을 우선순위다 */
  private holdReason(p: ProposalRec, now: number): string | null {
    if (this.observation !== null) return "한 번에 한 조치 · 관찰 창이 끝난 뒤";
    if (this.autoToday(now) >= AI_INITIAL.autoDailyCap) return `하루 자동 조치 상한 ${AI_INITIAL.autoDailyCap}회 도달`;
    const cd = this.cooldowns.get(p.params.kind);
    if (cd !== undefined && now < cd) return `쿨다운 ${mmss((cd - now) / 1000)} 남음`;
    if (this.judgedAt === null || now - this.judgedAt > AI_RULES.evidenceMaxAgeSec * 1000) return "근거가 60초보다 오래됨";
    if (p.confidencePct < AI_INITIAL.confidencePct) return `확신도 ${p.confidencePct}% · 기준 ${AI_INITIAL.confidencePct}% 미만`;
    if (p.streak < AI_RULES.sameJudgmentStreak) return `같은 판단 ${p.streak}/${AI_RULES.sameJudgmentStreak}회`;
    const q = p.params;
    if (q.kind === "THRESHOLD") {
      const t = this.thresholds.get(q.key);
      if (!t || Math.abs(q.to - q.from) > t.aiMaxStep) return "1회 변경폭 초과";
      const used = this.aiThresholdLog.filter((x) => x.key === q.key && sameLocalDay(x.at, now)).length;
      if (used >= AI_INITIAL.thresholdDailyPerItem) return "이 항목의 오늘 자동 변경 횟수 초과";
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
    if (this.autoToday(now) >= AI_INITIAL.autoDailyCap) {
      this.setManual(now, `하루 자동 조치 상한 ${AI_INITIAL.autoDailyCap}회 도달 · 수동 전환`, "SYSTEM", "시스템");
      this.log(this.openIncident, "NOTIFIED", "SYSTEM", "Teams", "Teams 알림 · 자동 조치 상한 도달");
    }
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
        if (how === "AUTO") this.aiThresholdLog.push({ key: q.key, at: now });
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
    const cd = cooldownSec(q.kind);
    if (cd > 0) this.cooldowns.set(q.kind, now + cd * 1000);
    p.decidedAt = now;
    p.decidedBy = actor.name;
    p.autoHold = null;
    if (how === "AUTO") {
      p.status = "AUTO_EXECUTED";
      p.revertibleUntil = now + AI_RULES.observeSec * 1000;
      this.autoLog.push(now);
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

  /** 조치를 무른다. 백업은 되돌릴 것이 없어 호출하지 않는다 */
  private undo(p: ProposalRec, actor: OpsActorRef, now: number): string {
    const q = p.params;
    switch (q.kind) {
      case "READ_NODES":
        this.setDesired(this.desired - q.delta);
        return `희망 대수 ${this.desired + q.delta} → ${this.desired}대`;
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
        return "보류 해제";
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
    const why = saturated ? `조회 평균 CPU ${Math.round(this.readAvg ?? 0)}% ≥ ${scaleOut}%` : `응답 p95 ${this.p95}ms 악화`;
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
    const did = this.undo(p, AI_ACTOR, now);
    p.status = "AUTO_REVERTED";
    p.revertibleUntil = null;
    // 카드는 결정 시각을 보여 준다. 되돌린 뒤에는 되돌린 시각이 그 자리에 와야 한다
    p.decidedAt = now;
    p.decidedBy = AI_ACTOR.name;
    this.observation = null;
    this.log(this.openIncident, "AUTO_REVERTED", "AI", AI_ACTOR.name, `자동 되돌림 · ${p.title} · ${why} · ${did}`);
    this.audit(AI_ACTOR, `ai.auto_revert:${q.kind}`, p.title, null);
    if (this.mode === "AUTO") this.setManual(now, "악화로 자동 되돌림 · 수동 전환", "AI", AI_ACTOR.name);
    this.log(this.openIncident, "NOTIFIED", "SYSTEM", "Teams", "Teams 알림 · 자동 되돌림과 수동 전환");
  }

  private expireAuto(now: number): void {
    if (this.auto !== null && now >= this.auto.until) this.setManual(now, "유효 시간 만료 · 수동 복귀", "SYSTEM", "시스템");
  }

  private setManual(now: number, text: string, actor: OpsActor, name: string): void {
    this.mode = "MANUAL";
    this.auto = null;
    for (const p of this.proposals) p.autoHold = null;
    this.log(this.openIncident, "MODE", actor, name, `모드 · ${text}`);
  }

  /* ===== 효과 ===== */

  private setDesired(next: number): void {
    if (next < READ_MIN || next > READ_MAX) {
      throw new AdminError("CAPACITY_OUT_OF_BOUNDS", next < READ_MIN ? `최소 ${READ_MIN}대 아래로 축소 불가` : `최대 ${READ_MAX}대 위로 증설 불가`);
    }
    while (this.desired < next) {
      const off = this.nodes.find((n) => n.state === "OFF");
      if (off) {
        off.state = "PENDING";
        off.since = this.tick;
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
    const did = this.undo(p, actor, now);
    p.status = "REVERTED";
    p.revertibleUntil = null;
    p.decidedAt = now;
    p.decidedBy = actor.name;
    if (this.observation?.proposalId === p.id) this.observation = null;
    this.log(this.openIncident, "REVERTED", "OPERATOR", actor.name, `되돌리기 · ${p.title} · ${did}`);
    this.audit(actor, `ai.revert:${p.params.kind}`, p.title, null);
  }

  /** 자동 모드를 켤 수 있는지 본다. 승인 티켓을 발급하기 전에 부른다 */
  checkAuto(hours: number, reason: string, now: number): void {
    this.advance(now);
    if (!(AUTO_HOURS as readonly number[]).includes(hours) || hours > AI_RULES.autoMaxHours) {
      throw new AdminError("invalid_parameter", `유효 시간은 ${AUTO_HOURS.join("·")}시간 중 하나`);
    }
    if (reason.trim().length < 2) throw new AdminError("invalid_parameter", "사유 입력 필요");
    if (this.mode === "AUTO") throw new AdminError("invalid_parameter", "이미 자동 모드");
    if (this.autoToday(now) >= AI_INITIAL.autoDailyCap) throw new AdminError("invalid_parameter", "오늘 자동 조치 상한 도달");
  }

  enableAuto(hours: number, reason: string, actor: OpsActorRef, approvalId: string, now: number): void {
    this.checkAuto(hours, reason, now);
    this.mode = "AUTO";
    this.auto = { since: now, until: now + hours * 3_600_000, reason: reason.trim(), by: actor.name, approvalId };
    this.log(this.openIncident, "MODE", "OPERATOR", actor.name, `모드 · 자동 켜기 ${hours}시간 · 사유 "${reason.trim()}"`);
    for (const p of this.proposals) if (p.status === "PROPOSED") p.autoHold = this.holdReason(p, now);
  }

  stopAuto(actor: OpsActorRef, now: number): void {
    this.advance(now);
    if (this.mode !== "AUTO") throw new AdminError("invalid_parameter", "자동 모드가 아님");
    this.setManual(now, "즉시 끄기", "OPERATOR", actor.name);
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
    }));
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
      revertibleUntil: p.revertibleUntil === null ? null : isoNow(p.revertibleUntil), autoHold: p.autoHold,
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
      autoToday: this.autoToday(now),
      autoDailyCap: AI_INITIAL.autoDailyCap,
      cooldowns: [...this.cooldowns].filter(([, until]) => until > now).map(([kind, until]) => ({ kind, until: isoNow(until) })),
      observation: this.observation === null ? null : { startedAt: isoNow(this.observation.startedAt), until: isoNow(this.observation.until), action: this.observation.action },
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
      scenario: this.scenario === null ? null : {
        id: this.scenario.id, title: OPS_SCENARIO_TITLE[this.scenario.id], startedAt: isoNow(this.scenario.startedAt),
        elapsedSec: Math.max(0, Math.floor((this.t - this.scenario.startedAt) / 1000)),
      },
    };
  }
}
