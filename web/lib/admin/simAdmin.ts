/**
 * 관리 평면 데모 시뮬레이터. 런타임 외부 의존 0.
 * 데모 인증서 2종(op-01, op-02)으로 운영자 전환을 시연한다. 1인 승인이라 요청자와 발급자가
 * 같아도 발급된다(ADR 0019). 다른 운영자가 발급하는 경로도 그대로 통한다.
 */

import { isoNow } from "../format";
import { COIN_META } from "../sim/engine";
import type { AdminApi } from "./api";
import { AdminError } from "./api";
import { ACTION_CAP, approvalConsoleTarget, can, type Capability } from "./roles";
import { feeRateError } from "./fees";
import { INVARIANT_DEFS, scenarioDef } from "./catalog";
import { summarizeInvariants } from "./invariants";
import { actionKey } from "./aiPolicy";
import { ONCALL_ROSTER } from "./oncall";
import { BASE_BOT_OPS, OpsSim, type OpsActorRef } from "./opsSim";
import { onBeat } from "./ticker";
import type {
  AdminPage,
  PageOpts,
  AdminOperator,
  AdminOverview,
  AdminSession,
  ApprovalPayload,
  ApprovalRequest,
  ApprovalState,
  AuditEntry,
  Bot,
  BotType,
  CapacityAdvice,
  CapacityNode,
  CapacityState,
  ConfigMarket,
  CreateBotResult,
  DemoClientCert,
  DestructiveAction,
  DirectionBiasResult,
  FleetScaleResult,
  FleetScaleTargets,
  ControlApply,
  HaRecovery,
  HaStatus,
  InvariantCheck,
  IssuedApproval,
  MarketSimState,
  Notice,
  NoticePatch,
  NoticeSeverity,
  OperatorStatus,
  RecoveryRun,
  RecoveryVerdict,
  SimStatus,
  TradeFlash,
  AdminInquiry,
  AdminTransfer,
  AdminTransferStatus,
  AdminMember,
  AdminMemberDetail,
  CommunityReport,
  ContentStatus,
  MemberStatus,
  MemberTransition,
  ModerationAction,
  ReportResolution,
  ReportStatus,
  ScenarioName,
  ScenarioResult,
  ScenarioMarketResult,
  AiActionKind,
  AiChatReply,
  AiChatTurn,
  AiJudgment,
  IncidentEvent,
  AiDecision,
  ControlCenter,
  OnCallEntry,
  OpsScenarioId,
  OpsSection,
  PostmortemDraft,
  ReportDesk,
} from "./types";
import { CHAT_MAX_CHARS, FLEET_TYPE_CAP } from "./types";

const SESSION_TTL_MS = 30 * 60_000; // 단기 관리자 세션
const APPROVAL_TTL_MS = 5 * 60_000; // §1-4 TTL 5분
const DEMO_CA = "demo-ca (Exchange Admin Demo Root)";
/* JVM 힙 상한. t3.small(2GiB)에 앱 하나를 올린 로컬 실측 규모를 따른다 */
const SIM_HEAP_MAX = 742_000_000;

let seq = 0;
function uid(prefix: string): string {
  seq += 1;
  return `${prefix}-${Date.now().toString(36)}-${seq.toString(36)}`;
}
function rand(lo: number, hi: number): number {
  return lo + Math.random() * (hi - lo);
}

function actorOf(s: { operatorId: string; displayName: string }): OpsActorRef {
  return { id: s.operatorId, name: s.displayName };
}

/**
 * 용량 권고. 종류와 순서는 서버(CapacityAdminController)를 따른다. 매칭 노드 응답 없음과 계약·관측 어긋남이 부하보다
 * 먼저다. 서버와 한 가지가 다르다. 어긋난 창(기동·내림 중)에는 부하 권고를 내지 않는다. 방금 누른 증설이 준비되는
 * 동안 '조회 부하 높음 · 증설 불가'나 같은 증설 단추를 다시 보이면 이미 한 조치를 또 하라는 말이 된다
 */
export function capacityAdvice(
  plan: { min: number; desired: number; max: number },
  up: number,
  avg: number | null,
  th: { scaleOut: number; scaleIn: number },
  leadersDown: string[],
): CapacityAdvice[] {
  const out: CapacityAdvice[] = [];
  if (leadersDown.length > 0) {
    out.push({
      id: "leader_down", level: "danger", headline: "주문 접수 중단",
      detail: `주문을 처리하는 매칭 노드 ${leadersDown.join("·")}가 응답하지 않습니다. 그 노드가 맡은 마켓의 주문이 멈추고 조회는 남은 앱 노드가 계속 받습니다. app-3를 켜도 주문은 재개되지 않습니다.`,
      action: null,
    });
  }
  if (up < plan.desired) {
    out.push({
      id: "under_provisioned", level: "warn", headline: "노드 부족",
      detail: `있어야 할 앱 노드가 ${plan.desired}대인데 ${up}대만 응답합니다. 켠 app-3가 준비 중이거나 한 대가 멈춘 상태입니다.`,
      action: null,
    });
  }
  if (up > plan.desired) {
    out.push({
      id: "over_provisioned", level: "warn", headline: "노드 초과",
      detail: `있어야 할 앱 노드가 ${plan.desired}대인데 ${up}대가 응답합니다. app-3 끄기가 끝나지 않았고 그동안 비용이 더 나갑니다.`,
      action: null,
    });
  }
  if (avg === null) {
    out.push({
      id: "no_metrics", level: "warn", headline: "상태 읽기 실패",
      detail: "조회를 받는 앱 노드에서 사용률을 가져오지 못했습니다. 판단 근거가 없어 권고를 내지 않습니다.",
      action: null,
    });
    return out;
  }
  if (up !== plan.desired) return out;
  const pct = Math.round(avg * 100);
  if (avg >= th.scaleOut && plan.desired < plan.max) {
    out.push({
      id: "scale_out", level: "warn", headline: "조회 부하 높음",
      detail: `조회를 받는 앱 노드의 평균 사용률이 ${pct}%입니다. app-3를 켜면 조회가 세 노드로 나뉘고 서비스는 멈추지 않습니다.`,
      action: "SCALE_OUT",
    });
  } else if (avg >= th.scaleOut) {
    out.push({
      id: "at_max", level: "warn", headline: "조회 부하 높음 · 증설 불가",
      detail: `평균 사용률이 ${pct}%인데 이미 앱 계층 최대 ${plan.max}대입니다. 최대 대수를 올리려면 비용 판단이 먼저입니다.`,
      action: null,
    });
  } else if (avg <= th.scaleIn && plan.desired > plan.min) {
    out.push({
      id: "scale_in", level: "info", headline: "조회 부하 낮음",
      detail: `조회를 받는 앱 노드의 평균 사용률이 ${pct}%입니다. app-3를 끄면 비용이 줄고, 끄기는 진행 중인 요청을 먼저 흘려보낸 뒤 내립니다.`,
      action: "SCALE_IN",
    });
  } else {
    out.push({
      id: "steady", level: "ok", headline: "안정",
      detail: `조회를 받는 앱 노드의 평균 사용률이 ${pct}%입니다. 할 조치가 없습니다.`,
      action: null,
    });
  }
  return out;
}

/**
 * 용량 판을 관제 시뮬레이터의 한 판에서 만든다. 관제센터와 서버·노드 화면이 쓰는 노드 이름(app-1·app-2·app-3),
 * 대수, 임계(임계값 레지스트리의 증설·축소 CPU)를 그대로 쓰므로 세 화면이 한 모델로 움직인다.
 *
 * 계약(최소·희망·최대)은 앱 계층 2~3대다(ADR 0030, 사용자 정정 2026-09-25). app-1·app-2는 매칭 리더이면서 조회도 받는
 * 고정 노드라 늘리고 줄이는 대상이 아니지만 대수와 평균에는 든다. 조회 전용 app-3만 켜고 끈다. app-3가 기동 중이거나
 * 내리는 중이면 계약과 관측이 어긋난 창이라 reconciled가 false다.
 */
export function capacityOf(c: ControlCenter, counted: (id: string, live: boolean, perSec: number) => number | null): CapacityState {
  const tier = c.readTier.state === "READY" ? c.readTier.data : null;
  const hosts = c.hosts.state === "READY" ? c.hosts.data : [];
  const items = c.thresholds.state === "READY" ? c.thresholds.data.items : [];
  const ratio = (key: string, fallback: number) => (items.find((t) => t.key === key)?.value ?? fallback) / 100;
  const apps: CapacityNode[] = hosts
    .filter((h) => h.role === "APP")
    .map((h, i) => {
      const up = h.collect !== "MISSING";
      const cpu = h.collect === "OK" && h.cpuPct !== null ? h.cpuPct / 100 : null;
      return {
        id: h.id,
        az: h.az,
        instanceType: h.instanceType,
        engine: true,
        scalable: false,
        expected: true,
        reachable: up,
        // 마켓별 리더(ADR 0034) 배치라 두 대가 각자 맡은 마켓의 리더이고, 조회도 함께 받는다
        leader: up,
        servesReads: up,
        cpuRatio: cpu,
        cpuCount: up ? 2 : null,
        heapUsedBytes: up ? 560_000_000 + i * 24_000_000 : null,
        heapMaxBytes: up ? SIM_HEAP_MAX : null,
        httpRequests: counted(h.id, up, 40 + (cpu ?? 0) * 120),
        wsClients: up ? 0 : null,
        lifecycle: null,
      };
    });
  const reads: CapacityNode[] = (tier?.nodes ?? []).map((n, i) => {
    const live = n.state === "IN_SERVICE" || n.state === "DRAINING";
    const cpu = live && n.cpuPct !== null ? n.cpuPct / 100 : null;
    return {
      id: n.id,
      az: n.az,
      instanceType: n.instanceType,
      engine: false,
      scalable: true,
      expected: n.state === "IN_SERVICE" || n.state === "PENDING",
      reachable: live,
      leader: false,
      servesReads: n.state === "IN_SERVICE",
      cpuRatio: cpu,
      cpuCount: live ? 2 : null,
      heapUsedBytes: live ? 610_000_000 + i * 20_000_000 : null,
      heapMaxBytes: live ? SIM_HEAP_MAX : null,
      httpRequests: counted(n.id, live, (cpu ?? 0) * 520),
      wsClients: live ? (n.state === "DRAINING" ? 24 : 360 + i * 40) : null,
      lifecycle: n.state === "PENDING" || n.state === "DRAINING" ? n.state : null,
    };
  });

  const plan = { min: tier?.min ?? 2, desired: tier?.desired ?? 2, max: tier?.max ?? 3 };
  // 앱 계층에서 응답하는 노드 수. 고정 두 대와 켜져 있는(내리는 중 포함) app-3다
  const up = apps.filter((n) => n.reachable).length + reads.filter((n) => n.reachable).length;
  const avg = tier === null || tier.avgCpuPct === null ? null : tier.avgCpuPct / 100;
  const scaleOut = ratio("read.scale_out_cpu", 60);
  const scaleIn = ratio("read.scale_in_cpu", 30);
  const down = apps.filter((n) => !n.reachable).map((n) => n.id);
  return {
    tier: "read",
    plan: { ...plan, updatedBy: "sim", updatedAt: c.generatedAt },
    observedTotal: up,
    reconciled: up === plan.desired,
    readAvgCpuRatio: avg,
    thresholds: { scaleOutCpuRatio: scaleOut, scaleInCpuRatio: scaleIn },
    nodes: [...apps, ...reads],
    advice: capacityAdvice(plan, up, avg, { scaleOut, scaleIn }, down),
  };
}

const BOT_TYPES: BotType[] = ["MARKET_MAKER", "TAKER", "TREND", "NOISE"];

/** 방향 net bias·strength 표기(문자열, 2자리). float 표기 금지 계약 준수 */
function biasStr(v: number): string {
  return (Math.round(v * 100) / 100).toFixed(2);
}
/** net bias 정의역 [-1, 1] */
function clampBias(v: number): number {
  return Math.max(-1, Math.min(1, v));
}
/** effective bias 하드캡 [-0.7, 0.7] (봇_방향성 §1-3) */
function clampEffective(v: number): number {
  return Math.max(-0.7, Math.min(0.7, v));
}
/** strength 정의역 [0, 1] */
function clampUnit(v: number): number {
  return Math.max(0, Math.min(1, v));
}

interface BiasState {
  target: number;
  measured: number;
  effective: number;
  auto: boolean;
  strength: number;
}

/** sessionStorage 로 나르는 플릿 상태. Map 은 직렬화가 안 되므로 엔트리 배열로 눕힌다 */
interface FleetSnapshot {
  running: boolean;
  paused: boolean;
  rate: number;
  bots: Bot[];
  flash: TradeFlash;
  scale: [string, FleetScaleTargets][];
  bias: [string, BiasState][];
}

/**
 * sim 커서는 마지막으로 본 항목의 id다. 없는 커서를 조용히 첫 쪽으로 되돌리지 않는다.
 * 서버는 400을 주므로, 여기서 관대하면 sim에서만 통과하는 화면 코드가 생긴다.
 */
function simPage<T, R>(rows: T[], opts: PageOpts, idOf: (row: T) => string,
                       project: (row: T) => R): AdminPage<R> {
  const limit = Math.max(1, Math.min(opts.limit ?? 100, 200));
  let start = 0;
  if (opts.cursor) {
    const at = rows.findIndex((r) => idOf(r) === opts.cursor);
    if (at < 0) throw new Error("커서 형식 오류");
    start = at + 1;
  }
  const slice = rows.slice(start, start + limit);
  const hasMore = rows.length > start + limit;
  return {
    data: slice.map(project),
    nextCursor: hasMore && slice.length > 0 ? idOf(slice[slice.length - 1]) : null,
    hasMore,
  };
}

/**
 * 복구 이력에서 전체 판정을 낸다. 서버 verdict와 같은 규칙이다(API §6-C).
 * 표가 비면 UNKNOWN이다. 판정 대상이 없는데 통과를 내면 공허 통과다.
 * 하나라도 비PASS면 FAIL이다. 창을 좁혀 최신 1행만 보면 과거 실패가 화면에서 사라진다.
 */
export function aggregateVerdict(runs: readonly RecoveryRun[]): RecoveryVerdict {
  if (runs.length === 0) {
    return "UNKNOWN";
  }
  return runs.some((r) => r.verdict !== "PASS") ? "FAIL" : "PASS";
}

export class SimAdminApi implements AdminApi {
  // sim은 start·stop·pause가 인메모리 플릿 상태에 즉시 반영된다
  readonly controlApplied = true;

  private certs: DemoClientCert[] = [
    { certId: "op-01", operatorId: "op-01", subjectCn: "CN=op-01", issuer: DEMO_CA },
    { certId: "op-02", operatorId: "op-02", subjectCn: "CN=op-02", issuer: DEMO_CA },
  ];

  private operators: AdminOperator[] = [
    { operatorId: "op-01", displayName: "김수퍼", roles: ["SUPER"], status: "ACTIVE" },
    { operatorId: "op-02", displayName: "이오퍼", roles: ["VIEWER", "BOT_OPS", "CHAOS_OPS"], status: "ACTIVE" },
  ];

  private session: AdminSession | null = null;
  private sessionListeners = new Set<(s: AdminSession | null) => void>();

  // 발급형 원장. token·payload는 내부 보관하고 listApprovals 프로젝션에서 제외한다(§6-B)
  private approvals: IssuedApproval[] = [];
  private notices: Notice[] = [
    {
      id: uid("not"),
      title: "정기 점검 안내",
      body: "매주 화요일 03:00~03:30 무중단 점검을 진행합니다.",
      severity: "info",
      active: true,
      createdAt: isoNow(Date.now() - 86_400_000),
      createdBy: "op-01",
    },
  ];
  private audit: AuditEntry[] = [];

  private markets: ConfigMarket[] = COIN_META.map((c, i) => ({
    symbol: c.market,
    koreanName: c.koreanName,
    // 마지막 마켓 1종을 DELISTED로 시드해 3분 상태(ACTIVE/HALTED/DELISTED)를 데모에서 노출한다
    state: i === COIN_META.length - 1 ? "DELISTED" : "ACTIVE",
    makerFee: "0.0005",
    takerFee: "0.0005",
    // 주문 규칙(§2 v2.35). 마켓별 격자는 sim이 들지 않아 대표값 한 벌을 둔다
    tickSize: "1000",
    minNotional: "5000",
  }));

  /**
   * 용량 판의 노드별 누적 요청 수. 화면이 두 조회의 차로 초당 요청을 내므로 누적값이 시간에 따라 늘어야 한다.
   * 대수와 사용률은 관제 시뮬레이터(this.ops)가 정본이고 여기에는 누적 카운터만 둔다
   */
  private capacityCounters = new Map<string, { at: number; total: number }>();
  // 재생 소요 실측 분포(2026-08-30, n=34: min 40 · p50 73 · max 159ms)를 미러한 시드 6건.
  // 초 단위 페일오버 RTO 대역은 다른 축이다. 단건 시드는 RtoSpark가 접힌다
  private recovery: RecoveryRun[] = [
    { hoursAgo: 1, durationMs: 159, from: 148_001, to: 148_120 },
    { hoursAgo: 5, durationMs: 73, from: 147_410, to: 147_512 },
    { hoursAgo: 11, durationMs: 121, from: 146_770, to: 146_931 },
    { hoursAgo: 19, durationMs: 40, from: 146_102, to: 146_188 },
    { hoursAgo: 27, durationMs: 88, from: 145_488, to: 145_601 },
    { hoursAgo: 38, durationMs: 147, from: 144_820, to: 144_997 },
  ].map((r) => ({
    id: uid("rec"),
    startedAt: isoNow(Date.now() - r.hoursAgo * 3_600_000),
    durationMs: r.durationMs,
    rpoMs: null,
    replayedFromSeq: r.from,
    replayedToSeq: r.to,
    // 서버 RecoveryRunRecorder가 만드는 포맷과 같게 둔다. 가짜 해시를 두면 열이 밝힌
    // 목적(재생 0건과 실재 재생을 가른다)을 데모 데이터가 배반한다.
    fpReplay: `seq:${r.from}-${r.to}:ev:${r.to - r.from}`,
    fpLive: `seq:${r.from}-${r.to}:ev:${r.to - r.from}`,
    matched: true,
    verdict: "PASS" as const,
  }));
  private invariants: InvariantCheck[] = INVARIANT_DEFS.map((d) => ({
    name: d.name,
    label: d.label,
    ok: true,
    violatedRows: 0,
    detail: "정상",
    checkedAt: isoNow(),
  }));
  /** 누적 체결. 관제 시뮬레이터의 초당 체결을 종합 조회 사이의 시간만큼 쌓는다 */
  private tradesTotal = 4_182_557;
  private tradesAt = Date.now();
  private simRunning = true;
  private simPaused = false;
  private duplicateFillTotal = 0;
  private bots: Bot[] = this.seedBots();

  /**
   * 프리셋 배수 기준선(§6-A v2.31). 시드 플릿과 기준 발행률을 한 번만 잡는다. 활성화 시점
   * 현재값을 곱하면 재활성화가 누적돼 spike 3회가 기준선 OPS의 1000배가 된다.
   */
  private readonly baseRate = BASE_BOT_OPS;
  private readonly scenarioBaseline = this.captureBaseline();

  // 시장 생동감 상태(인메모리, 봇_방향성 §7-1 volatile 규율과 동형). 외부 의존 0
  private scaleTargets = new Map<string, FleetScaleTargets>();
  private biasStates = new Map<string, BiasState>();
  private tradeFlash: TradeFlash = "normal";

  private stopTick: (() => void) | null = null;

  /** 관제센터 시뮬레이션(관리자 설계 4-4). AI가 한 일도 감사 로그에 AI 운영 계정으로 남긴다 */
  private readonly opsAudit = (actor: OpsActorRef, action: string, target: string, approvalId: string | null) =>
    this.log({ operatorId: actor.id, displayName: actor.name }, action, target, approvalId, "OK");
  private ops = new OpsSim(Date.now(), this.opsAudit);

  constructor() {
    // 관제 시뮬레이터가 시드한 지난 사건의 AI 조치도 감사 로그에 있어야 두 기록이 같은 말을 한다.
    // 초기화(resetOps)는 사건만 새로 시드하므로 여기서 한 번만 붙인다
    for (const e of this.ops.seededAudit) {
      this.audit.push({
        id: uid("aud"), at: e.at, operatorId: e.actor.id, operatorName: e.actor.name, action: e.action,
        target: e.target, approvalId: null, result: "OK", requestId: uid("req"),
      });
    }
    if (typeof window !== "undefined") {
      this.restore();
      this.start();
    }
    if (this.restoredRate !== null) this.ops.seedBotTarget(this.restoredRate);
    this.syncFleet();
  }

  /**
   * 봇 목록이 바뀔 때마다 관제 시뮬레이터에 알린다. 발행률의 정본은 관제 시뮬레이터라 봇 플릿, 종합 조회,
   * 관제 판이 같은 값을 읽는다(관리자 설계 4-4 sim 단일 원천)
   */
  private syncFleet(): void {
    const sum = this.bots.filter((b) => b.active).reduce((a, b) => a + b.ops, 0);
    this.ops.setFleet(sum, this.simRunning && !this.simPaused);
  }

  private botTarget(): number {
    return this.ops.botLoad(Date.now()).target;
  }

  /**
   * 운영자가 바꾼 플릿 상태만 새로고침 너머로 잇는다.
   *
   * 왜 필요한가. sim 어댑터는 전부 모듈 메모리라 F5 한 번에 시드값으로 돌아갔다. 정지를
   * 눌러 0 OPS를 만든 다음 새로고침하면 다시 가동 중이 떠서, 버튼이 듣지 않는 것으로 읽힌다.
   * 데모에서 그 장면은 제어가 고장난 것과 구분되지 않는다.
   *
   * 왜 이 목록만인가. 여기 담는 것은 전부 운영자가 직접 정한 값이다. 노드 헬스·epoch·
   * 불변식 검사 시각처럼 시뮬레이터가 스스로 굴리는 값은 담지 않는다. 그것들은 재기동 때
   * 새로 도는 것이 맞고, 얼려 두면 멈춘 시계를 살아 있는 관측으로 보여주게 된다.
   *
   * 세션은 담지 않는다. 실제 HttpAdminApi 도 세션을 인메모리로만 들고 F5 뒤 재확립을
   * 요구한다(mTLS 핸드셰이크가 연결 단위이므로). 여기만 살려 두면 두 어댑터의 인증 수명이
   * 갈리고, sim 에서만 통과하는 화면 코드가 생긴다.
   *
   * sessionStorage 인 이유. 탭을 닫으면 사라지는 수명이 단기 세션 규율과 어긋나지 않는다.
   * localStorage 로 두면 어제 눌러 둔 정지가 오늘 처음 연 화면에 남는다.
   */
  private static readonly STORE_KEY = "admin.sim.fleet.v1";
  /** 새로고침 전에 운영자가 정한 발행률. 관제 시뮬레이터를 세운 뒤 기록 없이 되살린다 */
  private restoredRate: number | null = null;

  private restore(): void {
    try {
      const raw = window.sessionStorage.getItem(SimAdminApi.STORE_KEY);
      if (!raw) return;
      const p = JSON.parse(raw) as Partial<FleetSnapshot>;
      if (typeof p.running === "boolean") this.simRunning = p.running;
      if (typeof p.paused === "boolean") this.simPaused = p.paused;
      if (typeof p.rate === "number" && p.rate > 0) this.restoredRate = p.rate;
      if (Array.isArray(p.bots) && p.bots.length > 0) this.bots = p.bots;
      if (p.flash) this.tradeFlash = p.flash;
      if (p.scale) this.scaleTargets = new Map(p.scale);
      if (p.bias) this.biasStates = new Map(p.bias);
    } catch {
      // 저장분이 깨졌으면 시드로 시작한다. 복원 실패로 화면을 막지 않는다
    }
  }

  /** 운영자 액션 뒤에 부른다. tick 의 난수 요동은 저장하지 않는다(매초 쓰기가 된다) */
  private persist(): void {
    if (typeof window === "undefined") return;
    const snap: FleetSnapshot = {
      running: this.simRunning,
      paused: this.simPaused,
      rate: this.botTarget(),
      bots: this.bots,
      flash: this.tradeFlash,
      scale: [...this.scaleTargets],
      bias: [...this.biasStates],
    };
    try {
      window.sessionStorage.setItem(SimAdminApi.STORE_KEY, JSON.stringify(snap));
    } catch {
      // 용량 초과·프라이빗 모드. 저장 실패가 제어 실패는 아니므로 삼킨다
    }
  }

  private seedBots(): Bot[] {
    const out: Bot[] = [];
    const spec: { type: BotType; market: string; count: number }[] = [
      { type: "MARKET_MAKER", market: "KRW-BTC", count: 4 },
      { type: "MARKET_MAKER", market: "KRW-ETH", count: 3 },
      { type: "TAKER", market: "KRW-BTC", count: 3 },
      { type: "TREND", market: "KRW-SOL", count: 2 },
      { type: "NOISE", market: "KRW-XRP", count: 2 },
    ];
    for (const s of spec) {
      for (let i = 0; i < s.count; i += 1) {
        out.push({
          id: uid("bot"),
          type: s.type,
          market: s.market,
          active: true,
          ops: Math.round(rand(4, 14)),
          params: { spread: "0.0008", size: "0.02", ttl_ms: "1500" },
        });
      }
    }
    return out;
  }

  /** 콘솔 공용 박자(ticker.ts)를 탄다. 탭이 숨으면 박자와 함께 멈추고 다시 보이면 이어 간다 */
  private start(): void {
    if (this.stopTick) return;
    this.stopTick = onBeat(() => this.tick());
  }

  /**
   * 매칭 리더와 체결은 관제 시뮬레이터가 소유한다(관리자 설계 4-4 sim 단일 원천). 여기서는 불변식 검사 시각과
   * 방향 편향의 측정값만 굴린다. 리더를 무작위로 바꾸던 옛 HA 시뮬레이션은 관제 판과 다른 리더를 말해 지웠다
   */
  private tick(): void {
    for (const inv of this.invariants) inv.checkedAt = isoNow();

    // 측정 net bias가 effective로 느슨하게 수렴(관측 프록시). 자동균형 on이면 강도만큼 빠르게 당긴다
    if (!this.simPaused) {
      for (const st of this.biasStates.values()) {
        const pull = st.auto ? 0.04 + st.strength * 0.16 : 0.03;
        st.measured = clampBias(st.measured + (st.effective - st.measured) * pull + rand(-0.01, 0.01));
      }
    }
  }

  listDemoCerts(): DemoClientCert[] {
    return this.certs.slice();
  }

  establishSession(certId: string): Promise<AdminSession> {
    const cert = this.certs.find((c) => c.certId === certId);
    if (!cert) return Promise.reject(new AdminError("jwt_verification", "제시한 클라이언트 인증서를 신뢰 앵커로 검증 실패"));
    const op = this.operators.find((o) => o.operatorId === cert.operatorId);
    if (!op || op.status !== "ACTIVE") {
      return Promise.reject(new AdminError("admin_forbidden", "비활성 운영자이거나 레지스트리에 없음"));
    }
    const now = Date.now();
    // 세션 신원은 인증서 CN에서 유래한다(자가신고 금지)
    this.session = {
      operatorId: op.operatorId,
      displayName: op.displayName,
      roles: op.roles.slice(),
      token: uid("sess"),
      issuedAt: isoNow(now),
      expiresAt: isoNow(now + SESSION_TTL_MS),
    };
    this.log(op, "session.establish", `mTLS ${cert.subjectCn}`, null, "OK");
    this.emitSession();
    return Promise.resolve(this.session);
  }

  getSession(): AdminSession | null {
    if (this.session && Date.now() > new Date(this.session.expiresAt).getTime()) {
      this.session = null;
      this.emitSession();
    }
    return this.session;
  }

  endSession(): void {
    this.session = null;
    this.emitSession();
  }

  onSessionChange(cb: (s: AdminSession | null) => void): () => void {
    this.sessionListeners.add(cb);
    cb(this.getSession());
    return () => this.sessionListeners.delete(cb);
  }

  private emitSession(): void {
    for (const cb of this.sessionListeners) cb(this.session);
  }

  /** 세션 존재만 요구하는 경로용. 역할 요구는 auth()가 담당한다 */
  private requireSession(): AdminSession {
    const s = this.getSession();
    if (!s) throw new AdminError("jwt_verification", "관리자 세션 없음");
    return s;
  }

  private auth(cap: Capability = "VIEW"): AdminSession {
    const s = this.requireSession();
    if (!can(s.roles, cap)) throw new AdminError("admin_forbidden", "권한 부족");
    return s;
  }

  private log(op: { operatorId: string; displayName: string }, action: string, target: string, approvalId: string | null, result: "OK" | "DENIED"): void {
    this.audit.unshift({
      id: uid("aud"),
      at: isoNow(),
      operatorId: op.operatorId,
      operatorName: op.displayName,
      action,
      target,
      approvalId,
      result,
      requestId: uid("req"),
    });
    this.audit = this.audit.slice(0, 200);
    /* 저장 지점을 여기 하나로 둔다. 운영자가 한 일은 예외 없이 감사 로그를 남기므로,
       로그가 남는 순간이 곧 저장할 순간이다. 제어 메서드마다 persist 를 흩어 놓으면
       나중에 추가되는 액션 하나가 조용히 저장에서 빠진다 */
    this.persist();
  }

  /** 원장 프로젝션. token·payload를 제외한 공개 뷰(§6-B) */
  private projectApproval(a: IssuedApproval): ApprovalRequest {
    return {
      id: a.id,
      action: a.action,
      targetKey: a.targetKey,
      targetLabel: a.targetLabel,
      requesterId: a.requesterId,
      requesterName: a.requesterName,
      approverId: a.approverId,
      approverName: a.approverName,
      state: a.state,
      issuedAt: a.issuedAt,
      expiresAt: a.expiresAt,
      consumedAt: a.consumedAt,
    };
  }

  issueApproval(requesterId: string, action: DestructiveAction, payload: ApprovalPayload): Promise<IssuedApproval> {
    // 발급 주체 요건은 활성 mTLS 신원뿐이다(ADR 0019, 관리자_설계 v1.4). 요청 운영자와 같아도 된다.
    // 서버 ApprovalController.issue와 동일하게 역할도 요구하지 않는다. 소각 권한은 소각 시점에 본다
    const approver = this.requireSession();
    const requester = this.operators.find((o) => o.operatorId === requesterId);
    if (!requester || requester.status !== "ACTIVE") {
      return Promise.reject(new AdminError("invalid_parameter", "요청 운영자를 레지스트리에서 찾을 수 없음"));
    }
    // 발급 주체와 요청 주체가 같아도 통과시킨다. 1인 승인이다(ADR 0019). V34가 DB의
    // chk_approval_two_person을 떼었고 서버 ApprovalService.issue에도 동일성 가드가 없다.
    // 종전 반결탁 반려는 야간 단독 운영 시간대의 정지 명령을 봉쇄해 철회했다
    const { key, label } = approvalConsoleTarget(payload);
    const now = Date.now();
    const issued: IssuedApproval = {
      id: uid("apr"),
      action,
      targetKey: key,
      targetLabel: label,
      requesterId,
      requesterName: requester.displayName,
      approverId: approver.operatorId,
      approverName: approver.displayName,
      state: "ISSUED",
      issuedAt: isoNow(now),
      expiresAt: isoNow(now + APPROVAL_TTL_MS),
      consumedAt: null,
      payload,
      token: uid("tok"),
    };
    this.approvals.unshift(issued);
    this.log(approver, `approval.issue:${action}`, label, issued.id, "OK");
    return Promise.resolve(issued);
  }

  private sweep(): void {
    const now = Date.now();
    for (const a of this.approvals) {
      if (a.state === "ISSUED" && now > new Date(a.expiresAt).getTime()) {
        a.state = "EXPIRED";
      }
    }
  }

  rejectApproval(approvalId: string): Promise<ApprovalRequest> {
    const s = this.auth("VIEW");
    this.sweep();
    const a = this.approvals.find((x) => x.id === approvalId);
    if (!a) return Promise.reject(new AdminError("admin_forbidden", "대상 승인 없음"));
    if (a.state !== "ISSUED") return Promise.reject(new AdminError("admin_forbidden", `거부 불가 상태(${a.state})`));
    // 소유 검증: 발급 주체 또는 요청 주체만 거부(de-escalation, §6-B)
    if (s.operatorId !== a.approverId && s.operatorId !== a.requesterId) {
      this.log(s, "approval.reject.denied", a.targetLabel, a.id, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "발급자 또는 요청자만 거부 가능"));
    }
    a.state = "REJECTED";
    this.log(s, `approval.reject:${a.action}`, a.targetLabel, a.id, "OK");
    return Promise.resolve(this.projectApproval(a));
  }

  consumeApproval(approval: IssuedApproval): Promise<void> {
    const s = this.auth("VIEW");
    this.sweep();
    const a = this.approvals.find((x) => x.id === approval.id);
    if (!a) return Promise.reject(new AdminError("admin_forbidden", "소각 대상 승인 없음"));
    if (a.state !== "ISSUED") return Promise.reject(new AdminError("admin_forbidden", `소각 불가 상태(${a.state})`));
    // 단회 토큰 대조(발급 응답 bearer 시크릿)
    if (a.token !== approval.token) {
      this.log(s, "execute.denied", a.targetLabel, a.id, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "승인 토큰 불일치"));
    }
    // 소각 주체는 토큰이 바인딩된 요청 운영자여야 한다(§3-6)
    if (a.requesterId !== s.operatorId) {
      this.log(s, "execute.denied", a.targetLabel, a.id, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "승인 토큰은 요청 운영자만 소각 가능"));
    }
    // 소각 시점 실행 권한 재확인(발급 이후 역할 회수 대비)
    if (!can(s.roles, ACTION_CAP[a.action])) {
      return Promise.reject(new AdminError("admin_forbidden", "소각 시점 권한 부족"));
    }
    this.applyEffect(a.payload, s, a.id);
    a.state = "CONSUMED";
    a.consumedAt = isoNow();
    this.log(s, `execute:${a.action}`, a.targetLabel, a.id, "OK");
    return Promise.resolve();
  }

  private applyEffect(payload: ApprovalPayload, by: AdminSession, approvalId: string): void {
    switch (payload.action) {
      case "sim.bots.params": {
        const bot = this.bots.find((b) => b.id === payload.botId);
        if (bot) bot.params = { ...bot.params, ...payload.params };
        break;
      }
      case "config.market.halt": {
        const m = this.markets.find((x) => x.symbol === payload.symbol);
        // DELISTED는 종국 상태라 halt 대상이 아니다(§6-D)
        if (m && m.state !== "DELISTED") m.state = "HALTED";
        break;
      }
      case "config.market.resume": {
        const m = this.markets.find((x) => x.symbol === payload.symbol);
        if (m && m.state === "HALTED") m.state = "ACTIVE";
        break;
      }
      case "config.market.fees": {
        // 서버 ConfigAdminController와 동일 계약: 검증 실패는 consume 전 반려로 토큰이 보존된다.
        // 여기서 던지면 consumeApproval의 CONSUMED 전이 전이라 승인 상태기계가 서버와 정렬된다
        const maker = payload.makerFee.trim();
        const taker = payload.takerFee.trim();
        if (maker === "" && taker === "") {
          throw new AdminError("invalid_parameter", "maker_fee 또는 taker_fee 중 하나 필요");
        }
        const err = feeRateError(maker, "maker_fee") ?? feeRateError(taker, "taker_fee");
        if (err) throw new AdminError("invalid_parameter", err);
        const m = this.markets.find((x) => x.symbol === payload.symbol);
        if (m) {
          // 빈 값은 부분 갱신(서버 parseFee null 대응). 기존 값 유지
          if (maker !== "") m.makerFee = maker;
          if (taker !== "") m.takerFee = taker;
        }
        break;
      }
      case "operators.create": {
        // 생성은 신원만 등록한다. 역할은 operators.roles 별도 승인 경로로만 부여한다(§3-6).
        // 중복 operatorId는 생성하지 않는다(§6-D 409 market 대응)
        if (!this.operators.some((o) => o.operatorId === payload.operatorId)) {
          this.operators.push({
            operatorId: payload.operatorId,
            displayName: payload.displayName,
            roles: [],
            status: "ACTIVE",
          });
        }
        break;
      }
      case "operators.status": {
        const op = this.operators.find((o) => o.operatorId === payload.operatorId);
        if (op) op.status = payload.status;
        break;
      }
      case "operators.roles": {
        // 부여·회수 단건 모델(정본 API). 전체 치환이 아니라 단일 역할만 더하거나 뺀다
        const op = this.operators.find((o) => o.operatorId === payload.operatorId);
        if (op) {
          if (payload.grant === "GRANT") {
            if (!op.roles.includes(payload.role)) op.roles = [...op.roles, payload.role];
          } else {
            op.roles = op.roles.filter((r) => r !== payload.role);
          }
        }
        // 대상이 현재 세션이면 세션 역할도 갱신
        if (this.session && this.session.operatorId === payload.operatorId && op) {
          this.session = { ...this.session, roles: op.roles.slice() };
          this.emitSession();
        }
        break;
      }
      case "members.close":
        this.closeMember(payload.externalId);
        break;
      case "ops.threshold.set":
        this.ops.applyThreshold(payload.key, payload.from ?? payload.to, payload.to, actorOf(by), approvalId, payload.reason,
          payload.revertOf, payload.proposalId, Date.now());
        break;
      case "ops.ai.auto":
        this.ops.enableAuto(payload.hours, payload.reason, actorOf(by), approvalId, Date.now());
        break;
      case "ops.ai.limits":
        this.ops.setLimit(payload.key, payload.from, payload.to, actorOf(by), approvalId, payload.reason, payload.revertOf, Date.now());
        break;
    }
  }

  listApprovals(state?: ApprovalState, opts: PageOpts = {}): Promise<AdminPage<ApprovalRequest>> {
    this.auth("VIEW");
    this.sweep();
    const rows = state ? this.approvals.filter((a) => a.state === state) : this.approvals;
    return Promise.resolve(simPage(rows, opts, (a) => a.id, (a) => this.projectApproval(a)));
  }

  private overallHealth(): "success" | "warning" | "danger" {
    const inv = summarizeInvariants(this.invariants);
    if (inv.state === "VIOLATED") return "danger";
    // 미수집·부분수집은 통과가 아니다. http 어댑터와 같은 판정식을 쓴다
    if (inv.state !== "PASS") return "warning";
    return "success";
  }

  /**
   * 종합 조회. 매칭 리더와 발행률, 체결은 관제 시뮬레이터에서 읽는다. 마켓별 리더(ADR 0034)라 리더가 둘이어서
   * 리더 칸에는 두 노드를, 세대에는 가장 최근 세대를 적는다. 관제센터 거래 요약 띠는 sim에서 이 조회가 아니라
   * 관제 판의 거래 칸을 읽는다
   */
  getOverview(): Promise<AdminOverview> {
    this.auth("VIEW");
    this.sweep();
    const now = Date.now();
    const load = this.ops.botLoad(now);
    this.tradesTotal += Math.round(load.tradesPerSec * Math.max(0, (now - this.tradesAt) / 1000));
    this.tradesAt = now;
    const leaders = this.ops.leaders();
    return Promise.resolve({
      overallHealth: this.overallHealth(),
      epoch: Math.max(...leaders.map((l) => l.epoch)),
      leaderNodeId: leaders.map((l) => l.node).join("·"),
      invariants: summarizeInvariants(this.invariants),
      // 목에 페일오버 주행 표가 없다. 재생 소요를 RTO로 실으면 없는 축을 지어낸다.
      lastRtoMs: null,
      // getRecovery와 같은 함수다. 두 화면이 다른 결론을 내면 안 된다.
      lastVerdict: aggregateVerdict(this.recovery),
      botsActive: this.bots.filter((b) => b.active).length,
      currentOps: this.currentOps(),
      // 발급형: ISSUED는 소각 대기분(발급=승인). 발급 전 요청은 서버 상태가 아니라 카운트하지 않는다
      pendingApprovals: this.approvals.filter((a) => a.state === "ISSUED").length,
      suspendedMarkets: this.markets.filter((m) => m.state === "HALTED").length,
      tradesTotal: this.tradesTotal,
      marketPartitions: leaders.reduce((a, l) => a + l.markets.length, 0),
      generatedAt: isoNow(),
    });
  }

  /* ===== MSP AI 관제센터(관리자 설계 4-4) ===== */

  readonly opsScenarios = true;

  getControlCenter(): Promise<ControlCenter> {
    this.auth("VIEW");
    const now = Date.now();
    const c = this.ops.snapshot(now);
    if (c.ai.state === "READY") {
      c.ai.data.actionStats = this.aiActionStats();
      c.ai.data.recent = this.aiRecent(now);
    }
    return Promise.resolve(c);
  }

  /** 시뮬레이터 판은 판단 과정을 늘 싣는다. 판의 기록을 그대로 돌려준다 */
  getAiJudgment(id: string): Promise<AiJudgment | null> {
    this.auth("VIEW");
    const c = this.ops.snapshot(Date.now());
    return Promise.resolve(c.ai.state === "READY" ? c.ai.data.history.find((h) => h.id === id) ?? null : null);
  }

  /** 시뮬레이터 판은 모든 사건의 기록을 싣는다. 따로 읽을 것이 없다 */
  getIncidentEvents(): Promise<IncidentEvent[] | null> {
    return Promise.resolve(null);
  }

  /** 시뮬레이터 판은 세 범위의 추세를 늘 싣는다 */
  wantTrends(): void {}

  /** 지난 24시간에 승인·자동으로 실행한 AI 조치 수와 되돌린 수. 평상 AI 패널의 한 줄이 쓴다(디자인 시스템 §12-9) */
  private aiRecent(now: number): { runs: number; reverts: number } {
    const out = { runs: 0, reverts: 0 };
    for (const e of this.audit) {
      if (e.result !== "OK" || now - Date.parse(e.at) > 86_400_000) continue;
      const m = /^ai\.(approve|auto|auto_revert|revert):/.exec(e.action);
      if (!m) continue;
      if (m[1] === "approve" || m[1] === "auto") out.runs += 1;
      else out.reverts += 1;
    }
    return out;
  }

  /** 같은 AI 조치를 몇 번 실행했고 몇 번 되돌렸는지 감사 로그에서 센다. AI 판단 요약의 '되돌리기' 칸이 쓴다 */
  private aiActionStats(): Record<string, { runs: number; reverts: number }> {
    const out: Record<string, { runs: number; reverts: number }> = {};
    for (const e of this.audit) {
      const m = /^ai\.(approve|auto|auto_revert|revert):([A-Z_]+)$/.exec(e.action);
      if (!m || e.result !== "OK") continue;
      const key = actionKey(m[2] as AiActionKind, e.target);
      const s = (out[key] ??= { runs: 0, reverts: 0 });
      if (m[1] === "approve" || m[1] === "auto") s.runs += 1;
      else s.reverts += 1;
    }
    return out;
  }

  ackAlert(alertId: string): Promise<void> {
    const s = this.auth("VIEW");
    this.ops.ackAlert(alertId, actorOf(s), Date.now());
    this.log(s, "ops.alert.ack", alertId, null, "OK");
    return Promise.resolve();
  }

  silenceAlert(alertId: string, minutes: number): Promise<void> {
    const s = this.auth("SUPER");
    this.ops.silenceAlert(alertId, minutes, actorOf(s), Date.now());
    this.log(s, "ops.alert.silence", `${alertId} ${minutes}m`, null, "OK");
    return Promise.resolve();
  }

  /** 사건 담당 지정. 서버 계약 초안과 같이 BOT_OPS 이상이고 감사 코드는 msp.incident.assign이다 */
  assignIncident(incidentId: string, operatorId: string): Promise<void> {
    const s = this.auth("BOT_OPS");
    const op = this.operators.find((o) => o.operatorId === operatorId && o.status === "ACTIVE");
    if (!op) return Promise.reject(new AdminError("invalid_parameter", "담당으로 지정할 운영자 없음"));
    this.ops.assignIncident(incidentId, { id: op.operatorId, name: op.displayName }, actorOf(s), Date.now());
    this.log(s, "msp.incident.assign", `${incidentId} → ${op.displayName}`, null, "OK");
    return Promise.resolve();
  }

  /** 사후 분석 저장. BOT_OPS 이상이고 감사 코드는 msp.incident.postmortem이다 */
  savePostmortem(incidentId: string, draft: PostmortemDraft, done: boolean): Promise<void> {
    const s = this.auth("BOT_OPS");
    this.ops.savePostmortem(incidentId, draft, done, actorOf(s), Date.now());
    this.log(s, "msp.incident.postmortem", `${incidentId} · ${done ? "완료" : "초안 저장"}`, null, "OK");
    return Promise.resolve();
  }

  async decideAiProposal(proposalId: string, decision: AiDecision): Promise<void> {
    // 승인은 인프라 조치라 SUPER다. 거절과 나중에는 조치를 줄이는 쪽이라 조회 권한으로 된다
    const s = this.auth(decision === "APPROVE" ? "SUPER" : "VIEW");
    const now = Date.now();
    this.ops.checkDecision(proposalId, decision, now);
    const t = decision === "APPROVE" ? this.ops.thresholdProposal(proposalId) : null;
    if (t) {
      // 임계값 변경은 승인 티켓 필수다. 막힐 이유를 먼저 본 뒤 발급해 쓸모없는 티켓을 남기지 않는다
      const issued = await this.issueApproval(s.operatorId, "ops.threshold.set", {
        action: "ops.threshold.set", key: t.key, label: t.label, unit: t.unit, from: t.from, to: t.to,
        reason: "AI 조치안 승인", revertOf: null, proposalId,
      });
      await this.consumeApproval(issued);
      return;
    }
    this.ops.decide(proposalId, decision, actorOf(s), now);
  }

  revertAiAction(proposalId: string): Promise<void> {
    const s = this.auth("SUPER");
    this.ops.revert(proposalId, actorOf(s), Date.now());
    return Promise.resolve();
  }

  async enableAiAuto(hours: number, reason: string): Promise<void> {
    const s = this.auth("SUPER");
    this.ops.checkAuto(hours, reason, Date.now());
    const issued = await this.issueApproval(s.operatorId, "ops.ai.auto", { action: "ops.ai.auto", hours, reason: reason.trim() });
    await this.consumeApproval(issued);
  }

  stopAiAuto(): Promise<void> {
    // 자동 끄기와 AI 정지는 누구든 언제든 된다. 조치를 멈추는 쪽이라 승인과 역할을 요구하지 않는다(ADR 0035)
    const s = this.auth("VIEW");
    this.ops.stopAuto(actorOf(s), Date.now());
    this.log(s, "ops.ai.manual", "AI 자동 모드", null, "OK");
    return Promise.resolve();
  }

  stopAi(): Promise<void> {
    const s = this.auth("VIEW");
    this.ops.stopAi(actorOf(s), Date.now());
    this.log(s, "ops.ai.stop", "AI 운영", null, "OK");
    return Promise.resolve();
  }

  resumeAi(reason: string): Promise<void> {
    // 재개는 AI가 다시 조치안을 내게 하는 쪽이라 조치안 승인과 같은 SUPER로 둔다
    const s = this.auth("SUPER");
    this.ops.resumeAi(reason, actorOf(s), Date.now());
    this.log(s, "ops.ai.resume", `AI 운영 · 사유 ${reason.trim()}`, null, "OK");
    return Promise.resolve();
  }

  runBackupNow(): Promise<void> {
    const s = this.auth("SUPER");
    this.ops.runBackup(actorOf(s), Date.now());
    return Promise.resolve();
  }

  startOpsScenario(id: OpsScenarioId): Promise<void> {
    const s = this.auth("VIEW");
    this.ops.startScenario(id, Date.now());
    this.log(s, "ops.scenario.start", id, null, "OK");
    return Promise.resolve();
  }

  resetOps(): Promise<void> {
    const s = this.auth("VIEW");
    // 관제 세계 전체를 처음으로 돌린다. 봇 발행률도 기준값으로 돌아가고 봇 목록은 그대로다
    this.ops = new OpsSim(Date.now(), this.opsAudit);
    this.syncFleet();
    this.log(s, "ops.scenario.reset", "관제 시뮬레이션", null, "OK");
    return Promise.resolve();
  }

  /* ===== AI 상담(관리자 설계 4-6) ===== */

  readonly aiChat = "SIM" as const;

  /**
   * 관제 시뮬레이터의 지금 판으로 규칙 답을 낸다. 규칙(aiChat.ts)은 처음 물을 때 불러온다. 상담을 열지 않는
   * 화면의 첫 번들에 답 규칙과 문장이 실리지 않게 한다
   */
  async askAi(question: string, _recent: AiChatTurn[]): Promise<AiChatReply> {
    const s = this.auth("VIEW");
    const text = question.trim();
    if (text.length === 0 || text.length > CHAT_MAX_CHARS) {
      throw new AdminError("invalid_parameter", `질문은 1~${CHAT_MAX_CHARS}자`);
    }
    const { answerFromCenter } = await import("./aiChat");
    const now = Date.now();
    const reply = answerFromCenter(text, this.ops.snapshot(now), now);
    // 서버 계약 초안처럼 상담도 감사에 남긴다(ops.ai.chat). 무엇을 물었는지가 기록의 목적이라 질문 앞머리를 대상에 적는다
    this.log(s, "ops.ai.chat", text.length > 60 ? `${text.slice(0, 60)}…` : text, null, "OK");
    return reply;
  }

  /* ===== 운영 보고(관리자 설계 4-7) ===== */

  private opsReports: ReportDesk | null = null;

  /** 보고 예시(reportSim.ts)는 보고서·설정 화면이 처음 읽을 때 불러온다. 다른 화면의 첫 번들에 싣지 않는다 */
  private async reportDesk(): Promise<ReportDesk> {
    if (this.opsReports === null) {
      const { simReportDesk } = await import("./reportSim");
      this.opsReports ??= simReportDesk(Date.now());
    }
    return this.opsReports;
  }

  async getReports(): Promise<OpsSection<ReportDesk>> {
    this.auth("VIEW");
    const d = await this.reportDesk();
    return { state: "READY", data: { ...d, reports: [...d.reports], tests: [...d.tests] } };
  }

  getOnCall(): Promise<OpsSection<OnCallEntry[]>> {
    this.auth("VIEW");
    // 예시 명부다. 관제 판의 당번 칸(사건 담당 자동 배정과 호출)과 같은 한 벌(oncall.ts)을 읽는다
    return Promise.resolve({ state: "READY", data: ONCALL_ROSTER.map((r) => ({ ...r })) });
  }

  /** 모의 전송이다. 밖으로 보내는 자리라 총괄 권한과 감사를 요구한다 */
  async sendTeamsTest(): Promise<void> {
    const s = this.auth("SUPER");
    const d = await this.reportDesk();
    d.tests = [{ attempt: 1, at: isoNow(), status: "sent" as const, error: null }, ...d.tests].slice(0, 10);
    this.log(s, "ops.report.teams_test", "Teams 연결 시험(모의)", null, "OK");
  }

  /* ===== 용량(앱 계층 희망 대수 · app-3 켜기·끄기) ===== */

  getCapacity(): Promise<CapacityState> {
    this.auth("VIEW");
    return Promise.resolve(this.capacitySnapshot(Date.now()));
  }

  scaleOut(reason?: string): Promise<CapacityState> {
    return Promise.resolve(this.capacityChange(1, "capacity.scale_out", reason));
  }

  scaleIn(reason?: string): Promise<CapacityState> {
    return Promise.resolve(this.capacityChange(-1, "capacity.scale_in", reason));
  }

  /**
   * 희망 대수를 바꾼다(app-3 켜기·끄기). 관제 시뮬레이터의 앱 계층을 그대로 움직이므로 관제센터의 앱 계층 칸과 AI 판단이 같은
   * 대수를 본다. 범위를 넘으면 시뮬레이터가 CAPACITY_OUT_OF_BOUNDS를 던지고 여기서 거부로 감사한다
   */
  private capacityChange(delta: 1 | -1, action: string, reason?: string): CapacityState {
    // 용량 변경은 비용을 쓰는 인프라 조치라 SUPER 를 요구한다(서버와 같은 판정).
    const session = this.auth("SUPER");
    const now = Date.now();
    try {
      this.ops.scaleReadNodes(delta, actorOf(session), now);
    } catch (e) {
      this.log(session, action, "앱 계층 · app-3", null, "DENIED");
      throw e;
    }
    this.log(session, action, "앱 계층 · app-3", null, "OK");
    void reason;
    return this.capacitySnapshot(now);
  }

  /** 용량 판. 누적 요청 카운터만 이 어댑터가 들고 나머지는 관제 시뮬레이터의 한 판에서 만든다(capacityOf) */
  private capacitySnapshot(now: number): CapacityState {
    const c = this.ops.snapshot(now);
    const at = c.collectedAt === null ? now : Date.parse(c.collectedAt);
    // 누적 요청은 초당 값을 적분해 늘린다. 내려간 노드는 카운터를 지워 다시 뜨면 0부터 센다(화면은 재기동으로 읽는다)
    return capacityOf(c, (id, live, perSec) => {
      if (!live) {
        this.capacityCounters.delete(id);
        return null;
      }
      const prev = this.capacityCounters.get(id) ?? { at, total: 0 };
      const total = Math.round(prev.total + Math.max(0, (at - prev.at) / 1000) * perSec);
      this.capacityCounters.set(id, { at, total });
      return total;
    });
  }

  /** 매칭 노드 두 대가 각자 맡은 파티션의 리더다(ADR 0034). 관제 시뮬레이터의 배치를 그대로 옮긴다 */
  getHaStatus(): Promise<HaStatus> {
    this.auth("VIEW");
    const leaders = this.ops.leaders();
    return Promise.resolve({
      epoch: Math.max(...leaders.map((l) => l.epoch)),
      leaderNodeId: leaders.map((l) => l.node).join("·"),
      nodes: leaders.map((l) => ({
        id: l.node, role: "leader" as const, epoch: l.epoch, seq: 0, followerLagMs: 0, ownedMarkets: l.markets.map((m) => `KRW-${m}`), health: "healthy" as const,
      })),
      tradesTotal: this.tradesTotal,
      generatedAt: isoNow(),
    });
  }

  getRecovery(): Promise<HaRecovery> {
    this.auth("VIEW");
    const overall = aggregateVerdict(this.recovery);
    return Promise.resolve({
      runs: this.recovery.slice(),
      // 목에 페일오버 주행 표가 없다. 재생 소요를 RTO로 실으면 없는 축을 지어낸다.
      lastRtoMs: null,
      lastVerdict: overall,
      verdictScope: "all" as const,
      replayVerdict: overall,
      // 목 어댑터에 페일오버 주행 표가 없다. 재생 축 결론을 복사하면 없는 축을 지어낸다.
      // 미추적 필드 날조 회피는 서버 응답과 같은 규율이다.
      failoverVerdict: null,
    });
  }

  getInvariants(): Promise<InvariantCheck[]> {
    this.auth("VIEW");
    return Promise.resolve(this.invariants.map((i) => ({ ...i })));
  }

  private currentOps(): number {
    return this.ops.botLoad(Date.now()).current;
  }

  getSimStatus(): Promise<SimStatus> {
    this.auth("VIEW");
    const active = this.bots.filter((b) => b.active).length;
    return Promise.resolve({
      // 모의 어댑터는 상태를 자기 안에 들고 있어 미상이 구조적으로 없다.
      source: "local" as const,
      observedAt: null,
      stale: false,
      running: this.simRunning,
      paused: this.simPaused,
      rate: this.botTarget(),
      currentOps: this.currentOps(),
      botCount: this.bots.length,
      activeBots: active,
      successRate: (0.997 + Math.random() * 0.0025).toFixed(4),
      avgLatencyMs: Math.round(rand(8, 22)),
      duplicateFillTotal: this.duplicateFillTotal,
      bots: this.bots.map((b) => ({ ...b, params: { ...b.params } })),
      markets: this.marketStates(),
      tradeFlash: this.tradeFlash,
    });
  }

  /** 봇·바이어스가 걸린 마켓 합집합의 방향·대수 스냅샷(§5-5) */
  private marketStates(): MarketSimState[] {
    const universe = new Set<string>();
    for (const b of this.bots) universe.add(b.market);
    for (const m of this.biasStates.keys()) universe.add(m);
    for (const m of this.scaleTargets.keys()) universe.add(m);
    const out: MarketSimState[] = [];
    for (const market of universe) {
      const bias = this.biasStates.get(market);
      const targets = this.scaleTargets.get(market);
      const scale: MarketSimState["scale"] = {};
      for (const type of BOT_TYPES) {
        const present = this.bots.filter((b) => b.market === market && b.type === type);
        const active = present.filter((b) => b.active).length;
        const target = targets?.[type] ?? present.length;
        // 대수·바이어스 어느 쪽도 없는 타입은 스냅샷에서 생략(빈 행 방지)
        if (target === 0 && present.length === 0) continue;
        scale[type] = { target, active };
      }
      const measured = bias?.measured ?? 0;
      const effective = bias?.effective ?? 0;
      out.push({
        market,
        targetNetBias: biasStr(bias?.target ?? 0),
        measuredNetBias: biasStr(measured),
        effectiveBias: biasStr(effective),
        autoBalance: { enabled: bias?.auto ?? false, strength: biasStr(bias?.strength ?? 0) },
        scale,
        fillRatio: biasStr(clampUnit(0.5 + effective * 0.25)),
        tradeFlash: this.tradeFlash,
      });
    }
    return out.sort((a, b) => a.market.localeCompare(b.market));
  }

  simStart(): Promise<void> {
    const s = this.auth("BOT_OPS");
    this.simRunning = true;
    this.simPaused = false;
    for (const b of this.bots) b.active = true;
    this.syncFleet();
    this.log(s, "sim.start", "전체 봇", null, "OK");
    return Promise.resolve();
  }

  simStop(): Promise<void> {
    const s = this.auth("BOT_OPS");
    this.simRunning = false;
    for (const b of this.bots) b.active = false;
    this.syncFleet();
    this.log(s, "sim.stop", "전체 봇", null, "OK");
    return Promise.resolve();
  }

  simPause(): Promise<void> {
    const s = this.auth("BOT_OPS");
    this.simPaused = !this.simPaused;
    this.syncFleet();
    this.log(s, "sim.pause", `paused=${this.simPaused}`, null, "OK");
    return Promise.resolve();
  }

  simSetRate(rate: number): Promise<ControlApply> {
    const s = this.auth("BOT_OPS");
    const next = Math.round(rate);
    // 서버 SimAdminController.parsePositive와 동일 계약. 0·음수는 정지 버튼 소관이라 반려한다
    if (next <= 0) {
      return Promise.reject(new AdminError("invalid_parameter", "target_ops는 양수여야 합니다."));
    }
    this.ops.setBotTarget(next, actorOf(s), "봇 플릿 화면", Date.now());
    this.log(s, "sim.rate", `${next} OPS`, null, "OK");
    // 목 어댑터는 거버너와 한 프로세스라 항상 즉시 적용이다.
    return Promise.resolve({ applyPath: "local" as const, queuedCommandId: 0 });
  }

  createBot(type: BotType, market: string, count = 1): Promise<CreateBotResult> {
    const s = this.auth("BOT_OPS");
    // governor set-point 모델: count만큼 유형별 목표 대수를 상향하고 캡에서 클램프한다(§6-A)
    const req = Math.max(0, Math.round(count));
    const present = this.bots.filter((b) => b.market === market && b.type === type).length;
    const target = Math.min(FLEET_TYPE_CAP, present + req);
    const created = Math.max(0, target - present);
    const clamped = created < req;
    for (let i = 0; i < created; i += 1) {
      this.bots.push({
        id: uid("bot"),
        type,
        market,
        active: this.simRunning && !this.simPaused,
        ops: Math.round(rand(4, 12)),
        params: { spread: "0.0008", size: "0.02", ttl_ms: "1500" },
      });
    }
    this.scaleTargets.set(market, { ...(this.scaleTargets.get(market) ?? {}), [type]: target });
    this.syncFleet();
    this.log(s, "sim.bots.create", `${type} ${market} +${created}`, null, "OK");
    return Promise.resolve({ created, appliedTarget: target, fleetTotal: this.bots.length, clamped, fleetPresent: true });
  }

  deleteBot(botId: string): Promise<void> {
    const s = this.auth("BOT_OPS");
    const before = this.bots.length;
    this.bots = this.bots.filter((b) => b.id !== botId);
    if (this.bots.length === before) return Promise.reject(new AdminError("invalid_parameter", "대상 봇 없음"));
    this.syncFleet();
    this.log(s, "sim.bots.delete", botId, null, "OK");
    return Promise.resolve();
  }

  setFleetScale(market: string, targets: FleetScaleTargets): Promise<FleetScaleResult> {
    const s = this.auth("BOT_OPS");
    const applied: FleetScaleTargets = {};
    let clamped = false;
    for (const type of BOT_TYPES) {
      const raw = targets[type];
      if (raw == null) continue;
      const t = Math.max(0, Math.min(FLEET_TYPE_CAP, Math.round(raw)));
      if (t !== raw) clamped = true;
      applied[type] = t;
      this.convergeScale(market, type, t);
    }
    this.scaleTargets.set(market, { ...(this.scaleTargets.get(market) ?? {}), ...applied });
    this.syncFleet();
    this.log(s, "sim.scale", market, null, "OK");
    return Promise.resolve({ market, applied, clamped, fleetPresent: true });
  }

  /** 목표 대수에 맞춰 인메모리 봇을 생성·제거로 수렴(파킹 대신 데모는 실제 증감) */
  private convergeScale(market: string, type: BotType, target: number): void {
    const present = this.bots.filter((b) => b.market === market && b.type === type);
    if (present.length < target) {
      for (let i = present.length; i < target; i += 1) {
        this.bots.push({
          id: uid("bot"),
          type,
          market,
          active: this.simRunning && !this.simPaused,
          ops: Math.round(rand(4, 12)),
          params: { spread: "0.0008", size: "0.02", ttl_ms: "1500" },
        });
      }
    } else if (present.length > target) {
      const remove = new Set(present.slice(target).map((b) => b.id));
      this.bots = this.bots.filter((b) => !remove.has(b.id));
    }
  }

  setDirectionBias(market: string, targetNetBias: string, autoBalance: boolean, strength: string): Promise<DirectionBiasResult> {
    const s = this.auth("BOT_OPS");
    // 확률 편향 스칼라(자금 경로 아님, §2-2). 표기·전송은 문자열, 내부만 수치
    const target = clampBias(Number(targetNetBias));
    const st = clampUnit(Number(strength));
    if (!Number.isFinite(target) || !Number.isFinite(st)) {
      return Promise.reject(new AdminError("invalid_parameter", "바이어스·강도 값 오류"));
    }
    const effective = clampEffective(target);
    const prev = this.biasStates.get(market);
    this.biasStates.set(market, { target, measured: prev?.measured ?? 0, effective, auto: autoBalance, strength: st });
    this.log(s, "sim.bias", market, null, "OK");
    return Promise.resolve({
      market,
      targetNetBias: biasStr(target),
      autoBalance,
      strength: biasStr(st),
      effectiveBias: biasStr(effective),
      fleetPresent: true,
    });
  }

  private captureBaseline(): Map<string, FleetScaleTargets> {
    const out = new Map<string, FleetScaleTargets>();
    for (const b of this.bots) {
      const cur = out.get(b.market) ?? {};
      cur[b.type] = (cur[b.type] ?? 0) + 1;
      out.set(b.market, cur);
    }
    return out;
  }

  /**
   * 국면 프리셋 활성화(§6-A v2.31). 기준선 대비 배수로 OPS·대수·방향을 한 번에 얹는다.
   * 기준선이 고정이라 같은 프리셋 재활성화가 누적되지 않고 steady가 복귀 경로가 된다.
   */
  activateScenario(name: ScenarioName): Promise<ScenarioResult> {
    const s = this.auth("BOT_OPS");
    const def = scenarioDef(name);
    const rate = Math.max(1, Math.round(this.baseRate * def.opsMult));
    this.ops.setBotTarget(rate, actorOf(s), `봇 국면 프리셋 ${name}`, Date.now());

    const universe = new Set<string>([
      ...this.scenarioBaseline.keys(),
      ...this.biasStates.keys(),
      ...this.scaleTargets.keys(),
    ]);
    const markets: ScenarioMarketResult[] = [];
    let clamped = false;
    for (const market of [...universe].sort((a, b) => a.localeCompare(b))) {
      const base = this.scenarioBaseline.get(market);
      const applied: FleetScaleTargets = {};
      if (base) {
        for (const type of BOT_TYPES) {
          const seeded = base[type];
          if (seeded == null) continue;
          const raw = Math.round(seeded * def.scale[type]);
          const t = Math.max(0, Math.min(FLEET_TYPE_CAP, raw));
          if (t !== raw) clamped = true;
          applied[type] = t;
          this.convergeScale(market, type, t);
        }
        this.scaleTargets.set(market, { ...(this.scaleTargets.get(market) ?? {}), ...applied });
      } else {
        // 기준선 없는 마켓은 대수를 건드리지 않는다. 0을 곱해 봇을 조용히 없애는 경로를 막는다
        Object.assign(applied, this.scaleTargets.get(market) ?? {});
      }

      // sim의 균형 루프는 measured가 effective를 좇는다(서버는 effective가 target을 좇는다).
      // 여기서는 effective가 구동값이라 auto 여부와 무관하게 목표를 싣는다. 안 그러면
      // steady를 눌러도 방향이 되돌아오지 않는다.
      const target = clampBias(Number(def.targetBias));
      const effective = clampEffective(target);
      const prev = this.biasStates.get(market);
      this.biasStates.set(market, {
        target,
        measured: prev?.measured ?? 0,
        effective,
        auto: def.autoBalance,
        strength: clampUnit(Number(def.strength)),
      });
      markets.push({ market, scaleApplied: base != null, applied, effectiveBias: biasStr(effective) });
    }

    this.syncFleet();
    this.log(s, "sim.scenario", name, null, "OK");
    return Promise.resolve({
      scenario: name,
      // 목 어댑터는 거버너와 한 프로세스라 항상 즉시 적용이다. 큐 경로는 서버 배치의
      // 물리 분리에서만 나오므로 여기서 재현할 대상이 아니다.
      applyPath: "local" as const,
      queuedCommandId: 0,
      targetOps: String(rate),
      baseOps: String(this.baseRate),
      autoBalance: def.autoBalance,
      targetNetBias: def.targetBias,
      markets,
      clamped,
      fleetPresent: true,
    });
  }

  setEffectConfig(tradeFlash: TradeFlash): Promise<TradeFlash> {
    const s = this.auth("BOT_OPS");
    this.tradeFlash = tradeFlash;
    this.log(s, "sim.effects", `trade_flash=${tradeFlash}`, null, "OK");
    return Promise.resolve(tradeFlash);
  }

  getConfigMarkets(): Promise<ConfigMarket[]> {
    this.auth("VIEW");
    return Promise.resolve(this.markets.map((m) => ({ ...m })));
  }

  /* ===== 문의 답변 큐 (§4-G) =====
   * 회원 어댑터(SimDataSource)와 다른 객체라 실제 문의를 공유하지 못한다. 답변 흐름의
   * 화면·권한·상태 전이를 확인할 수 있도록 씨앗 행을 둔다. */
  private inquiries: AdminInquiry[] = [
    {
      externalId: "sim-q-1",
      category: "TRADE",
      title: "지정가 주문이 체결되지 않습니다",
      // 줄바꿈이 든 문의로 둔다. 관리 화면이 여러 줄 본문을 제대로 그리는지 확인 대상이다
      body: ["84,000,000원에 매수 주문을 넣었는데 30분째 미체결입니다.", "현재가는 83,900,000원입니다."].join("\n"),
      status: "OPEN",
      answer: null,
      answeredAt: null,
      userEmail: "trader@demo.local",
      createdAt: Date.now() - 1000 * 60 * 25,
    },
    {
      externalId: "sim-q-2",
      category: "TRANSFER",
      title: "출금 승인이 언제 되나요",
      body: "어제 출금 요청했는데 아직 대기 중입니다.",
      status: "OPEN",
      answer: null,
      answeredAt: null,
      userEmail: "whale@demo.local",
      createdAt: Date.now() - 1000 * 60 * 5,
    },
  ];

  getInquiries(status: "OPEN" | "ANSWERED" = "OPEN", opts: PageOpts = {}): Promise<AdminPage<AdminInquiry>> {
    this.auth("VIEW");
    const rows = this.inquiries.filter((q) => q.status === status);
    return Promise.resolve(simPage(rows, opts, (q) => q.externalId, (q) => ({ ...q })));
  }

  answerInquiry(externalId: string, answer: string): Promise<AdminInquiry> {
    const s = this.auth("MEMBER_MOD");
    const text = String(answer ?? "").trim();
    if (!text) return Promise.reject(new AdminError("invalid_support_input", "답변 내용 필요"));
    const row = this.inquiries.find((q) => q.externalId === externalId);
    if (!row) return Promise.reject(new AdminError("not_found", "문의 없음"));
    if (row.status !== "OPEN") {
      return Promise.reject(new AdminError("support_already_answered", "이미 답변된 문의"));
    }
    row.status = "ANSWERED";
    row.answer = text;
    row.answeredAt = Date.now();
    this.log(s, "support.answer", externalId, null, "OK");
    return Promise.resolve({ ...row });
  }

  /* ===== 회원 운영 (§6-E) =====
   * 회원 도메인은 고객 어댑터 소관이라 관리 어댑터가 실제 계정을 들고 있지 않다. 전이의
   * 화면·권한·부수 효과 표기를 확인할 수 있도록 씨앗 행을 둔다. 자금은 여기서 안 움직인다. */
  private members: (AdminMemberDetail & { sessions: number })[] = [
    {
      externalId: "11111111-1111-4111-8111-111111111111",
      email: "trader@demo.local", displayName: "김트레이더", status: "ACTIVE",
      createdAt: Date.now() - 1000 * 60 * 60 * 24 * 30,
      updatedAt: Date.now() - 1000 * 60 * 60 * 24 * 30,
      // 폐쇄가 무엇을 취소하는지 화면에서 보이게 미체결을 들고 있는 계정을 하나 둔다
      openOrderCount: 3, lastOrderAt: Date.now() - 1000 * 60 * 8, assetKinds: 4, sessions: 2,
    },
    {
      externalId: "22222222-2222-4222-8222-222222222222",
      email: "quiet@demo.local", displayName: "이조용", status: "ACTIVE",
      createdAt: Date.now() - 1000 * 60 * 60 * 24 * 12,
      updatedAt: Date.now() - 1000 * 60 * 60 * 24 * 12,
      openOrderCount: 0, lastOrderAt: null, assetKinds: 1, sessions: 1,
    },
    {
      externalId: "33333333-3333-4333-8333-333333333333",
      email: "spammer@demo.local", displayName: "정지된계정", status: "SUSPENDED",
      createdAt: Date.now() - 1000 * 60 * 60 * 24 * 3,
      updatedAt: Date.now() - 1000 * 60 * 60 * 2,
      openOrderCount: 1, lastOrderAt: Date.now() - 1000 * 60 * 60 * 5, assetKinds: 2, sessions: 0,
    },
  ];

  getMembers(status?: MemberStatus, opts: PageOpts = {}): Promise<AdminPage<AdminMember>> {
    this.auth("VIEW");
    // 가입 역순. 최근 가입이 위다
    const rows = this.members
      .filter((m) => !status || m.status === status)
      .sort((a, b) => b.createdAt - a.createdAt);
    // 활동 요약도 목록이 함께 낸다(§6-E v2.35). http 어댑터와 같은 계약이라야
    // 화면이 한쪽에서만 도는 코드를 갖지 않는다
    return Promise.resolve(simPage(rows, opts, (m) => m.externalId, (m) => ({
      externalId: m.externalId, email: m.email, displayName: m.displayName,
      status: m.status, createdAt: m.createdAt,
      openOrderCount: m.openOrderCount, lastOrderAt: m.lastOrderAt, assetKinds: m.assetKinds,
    })));
  }

  getMember(externalId: string): Promise<AdminMemberDetail> {
    this.auth("VIEW");
    const m = this.members.find((x) => x.externalId === externalId);
    if (!m) return Promise.reject(new AdminError("not_found", "회원을 찾을 수 없습니다."));
    const { sessions: _sessions, ...detail } = m;
    return Promise.resolve({ ...detail });
  }

  suspendMember(externalId: string): Promise<MemberTransition> {
    return this.transitionMember(externalId, "SUSPENDED", "members.suspend", false);
  }

  reinstateMember(externalId: string): Promise<MemberTransition> {
    return this.transitionMember(externalId, "ACTIVE", "members.reinstate", false);
  }

  /** 폐쇄는 승인 소각 경로(consumeApproval)가 부른다. 직접 호출 경로는 없다 */
  private closeMember(externalId: string): MemberTransition {
    return this.transitionMember0(externalId, "CLOSED", "members.close", true);
  }

  private transitionMember(externalId: string, target: MemberStatus, action: string,
                           cancelOrders: boolean): Promise<MemberTransition> {
    try {
      return Promise.resolve(this.transitionMember0(externalId, target, action, cancelOrders));
    } catch (e) {
      return Promise.reject(e);
    }
  }

  private transitionMember0(externalId: string, target: MemberStatus, action: string,
                            cancelOrders: boolean): MemberTransition {
    const s = this.auth("MEMBER_MOD");
    const m = this.members.find((x) => x.externalId === externalId);
    if (!m) throw new AdminError("not_found", "회원을 찾을 수 없습니다.");
    if (m.status === "CLOSED" && target !== "CLOSED") {
      throw new AdminError("member_closed", "폐쇄된 계정은 되돌릴 수 없습니다.");
    }
    // 정지는 가역이라 주문을 남긴다. 폐쇄만 정리한다(서버 MemberAdminService와 같은 규율)
    const canceled = cancelOrders ? m.openOrderCount : 0;
    if (cancelOrders) m.openOrderCount = 0;
    const revoked = target === "ACTIVE" ? 0 : m.sessions;
    if (target !== "ACTIVE") m.sessions = 0;
    m.status = target;
    m.updatedAt = Date.now();
    this.log(s, action, externalId, null, "OK");
    return { externalId, status: target, canceledOrders: canceled, revokedSessions: revoked };
  }

  /* ===== 커뮤니티 운영 큐 (§6-D) =====
   * 게시판 도메인은 회원 어댑터 소관이라 관리 어댑터가 실제 글을 들고 있지 않다. 신고 큐의
   * 화면·권한·상태 전이를 확인할 수 있도록 씨앗 행과 대상 상태만 따로 둔다. */
  private reports: CommunityReport[] = [
    {
      reportId: 41,
      targetType: "POST",
      targetId: 1207,
      targetExternalId: "sim-post-1207",
      targetStatus: "VISIBLE",
      targetExcerpt: "이 코인 지금 안 사면 평생 후회합니다. 링크 타고 오시면 수익 인증",
      targetAuthor: "코인왕",
      reporterId: 88,
      reason: "SPAM",
      detail: "외부 링크 유도 반복 게시",
      status: "PENDING",
      createdAt: Date.now() - 1000 * 60 * 12,
    },
    {
      reportId: 42,
      targetType: "COMMENT",
      targetId: 3391,
      targetExternalId: "sim-comment-3391",
      // 임계 초과 자동 숨김을 이미 받은 대상. 운영자는 확정만 하면 된다
      targetStatus: "HIDDEN",
      targetExcerpt: "작성자 신상 털어서 올린다",
      targetAuthor: "익명이용자",
      reporterId: 91,
      reason: "ABUSE",
      detail: null,
      status: "PENDING",
      createdAt: Date.now() - 1000 * 60 * 40,
    },
    {
      reportId: 39,
      targetType: "POST",
      targetId: 1180,
      targetExternalId: "sim-post-1180",
      targetStatus: "VISIBLE",
      targetExcerpt: "오늘 장 정리해봤습니다. 각자 판단하시길",
      targetAuthor: "차트분석가",
      reporterId: 77,
      reason: "ETC",
      detail: "그냥 마음에 안 듦",
      status: "DISMISSED",
      createdAt: Date.now() - 1000 * 60 * 60 * 3,
    },
  ];

  getCommunityReports(status: ReportStatus = "PENDING",
                      opts: PageOpts = {}): Promise<AdminPage<CommunityReport>> {
    this.auth("MEMBER_MOD");
    // 큐는 오래된 것부터다. 처리 순서가 신고 순서여야 밀린 건이 뒤로 밀리지 않는다
    const rows = this.reports
      .filter((r) => r.status === status)
      .sort((a, b) => a.createdAt - b.createdAt);
    return Promise.resolve(simPage(rows, opts, (r) => String(r.reportId), (r) => ({ ...r })));
  }

  resolveCommunityReport(reportId: number, resolution: ReportResolution,
                         note?: string): Promise<void> {
    const s = this.auth("MEMBER_MOD");
    const row = this.reports.find((r) => r.reportId === reportId);
    if (!row) return Promise.reject(new AdminError("not_found", "신고 없음"));
    if (row.status !== "PENDING") {
      return Promise.reject(new AdminError("invalid_parameter", "처리 대기 중인 신고가 아닙니다."));
    }
    row.status = resolution;
    this.log(s, "community.report.resolve", String(reportId), null, "OK");
    void note;
    return Promise.resolve();
  }

  moderateCommunityTarget(targetType: "POST" | "COMMENT", externalId: string,
                          action: ModerationAction, note?: string): Promise<void> {
    const s = this.auth("MEMBER_MOD");
    const rows = this.reports.filter((r) => r.targetExternalId === externalId);
    if (rows.length === 0) return Promise.reject(new AdminError("not_found", "대상 없음"));
    // DELETED는 종료 상태다. 서버 applyModeration과 같은 규율로 되살아나는 경로를 막는다
    if (rows.some((r) => r.targetStatus === "DELETED")) {
      return Promise.reject(new AdminError("invalid_parameter", "이미 삭제된 대상입니다."));
    }
    const next: ContentStatus =
      action === "HIDE" ? "HIDDEN" : action === "UNHIDE" ? "VISIBLE" : "DELETED";
    for (const r of rows) r.targetStatus = next;
    const ns = targetType === "POST" ? "community.post.moderate" : "community.comment.moderate";
    this.log(s, ns, externalId, null, "OK");
    void note;
    return Promise.resolve();
  }

  /* ===== 출금 승인 큐 (§4-E) =====
   * 관리 어댑터는 회원 어댑터와 다른 객체라 실제 요청 목록을 공유하지 못한다. 승인 흐름의
   * 화면·권한·상태 전이 확인용 씨앗 행이고, 실제 원장 이동은 http 경로에서만 일어난다. */
  private transfers: AdminTransfer[] = [
    {
      externalId: "sim-tr-1",
      direction: "WITHDRAW",
      asset: "KRW",
      amount: "3000000",
      status: "REQUESTED",
      reason: null,
      userEmail: "trader@demo.local",
      createdAt: Date.now() - 1000 * 60 * 12,
      updatedAt: Date.now() - 1000 * 60 * 12,
    },
    {
      externalId: "sim-tr-2",
      direction: "WITHDRAW",
      asset: "KRW",
      amount: "12000000",
      status: "REQUESTED",
      reason: null,
      userEmail: "whale@demo.local",
      createdAt: Date.now() - 1000 * 60 * 3,
      updatedAt: Date.now() - 1000 * 60 * 3,
    },
  ];

  getTransfers(status: AdminTransferStatus = "REQUESTED",
               opts: PageOpts = {}): Promise<AdminPage<AdminTransfer>> {
    this.auth("VIEW");
    const rows = this.transfers.filter((t) => t.status === status);
    return Promise.resolve(simPage(rows, opts, (t) => t.externalId, (t) => ({ ...t })));
  }

  approveTransfer(externalId: string): Promise<AdminTransfer> {
    const s = this.auth("MEMBER_MOD");
    const row = this.pendingTransfer(externalId);
    row.status = "COMPLETED";
    row.updatedAt = Date.now();
    this.log(s, "transfer.approve", externalId, null, "OK");
    return Promise.resolve({ ...row });
  }

  rejectTransfer(externalId: string, reason: string): Promise<AdminTransfer> {
    const s = this.auth("MEMBER_MOD");
    if (!reason || !reason.trim()) {
      return Promise.reject(new AdminError("invalid_parameter", "거절 사유 필요"));
    }
    const row = this.pendingTransfer(externalId);
    row.status = "CANCELED";
    row.reason = reason.trim();
    row.updatedAt = Date.now();
    this.log(s, "transfer.reject", externalId, null, "OK");
    return Promise.resolve({ ...row });
  }

  private pendingTransfer(externalId: string): AdminTransfer {
    const row = this.transfers.find((t) => t.externalId === externalId);
    if (!row) throw new AdminError("not_found", "대상 요청 없음");
    if (row.status !== "REQUESTED") {
      throw new AdminError("transfer_not_pending", `이미 처리된 요청: ${row.status}`);
    }
    return row;
  }

  getNotices(opts: { cursor?: string | null; limit?: number } = {}): Promise<AdminPage<Notice>> {
    this.auth("VIEW");
    const limit = Math.max(1, Math.min(opts.limit ?? 100, 200));
    let start = 0;
    if (opts.cursor) {
      const at = this.notices.findIndex((n) => n.id === opts.cursor);
      // 서버 규칙(없는 커서=400)을 sim에서도 지킨다. 조용히 첫 쪽으로 되돌리면
      // sim에서만 통과하는 화면 코드가 생긴다
      if (at < 0) return Promise.reject(new Error("커서 형식 오류"));
      start = at + 1;
    }
    const slice = this.notices.slice(start, start + limit);
    const hasMore = this.notices.length > start + limit;
    return Promise.resolve({
      data: slice.map((n) => ({ ...n })),
      nextCursor: hasMore && slice.length > 0 ? slice[slice.length - 1].id : null,
      hasMore,
    });
  }

  postNotice(title: string, body: string, severity: NoticeSeverity = "info"): Promise<Notice> {
    const s = this.auth("SUPER");
    const notice: Notice = { id: uid("not"), title, body, severity, active: true, createdAt: isoNow(), createdBy: s.operatorId };
    this.notices.unshift(notice);
    this.log(s, "config.notice.post", title, null, "OK");
    return Promise.resolve({ ...notice });
  }

  updateNotice(id: string, patch: NoticePatch): Promise<Notice> {
    const s = this.auth("SUPER");
    const n = this.notices.find((x) => x.id === id);
    if (!n) return Promise.reject(new AdminError("invalid_parameter", "대상 공지 없음"));
    if (patch.title !== undefined) n.title = patch.title;
    if (patch.body !== undefined) n.body = patch.body;
    if (patch.severity !== undefined) n.severity = patch.severity;
    if (patch.active !== undefined) n.active = patch.active;
    this.log(s, "config.notice.update", n.title, null, "OK");
    return Promise.resolve({ ...n });
  }

  deleteNotice(id: string): Promise<void> {
    const s = this.auth("SUPER");
    const n = this.notices.find((x) => x.id === id);
    // 소프트 삭제(active=false). notices DELETE 권한이 없어 하드 삭제하지 않는다(§6-D). 없는 id는 멱등 통과
    if (n) n.active = false;
    this.log(s, "config.notice.delete", id, null, "OK");
    return Promise.resolve();
  }

  getOperators(): Promise<AdminOperator[]> {
    this.auth("VIEW");
    return Promise.resolve(this.operators.map((o) => ({ ...o, roles: o.roles.slice() })));
  }

  getAudit(opts: PageOpts = {}): Promise<AdminPage<AuditEntry>> {
    this.auth("VIEW");
    return Promise.resolve(simPage(this.audit, opts, (e) => e.id, (e) => ({ ...e })));
  }
}
