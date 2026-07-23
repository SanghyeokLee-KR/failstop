/**
 * lib/admin/simAdmin.ts. 관리 평면 데모 시뮬레이터. 런타임 외부 의존 0.
 * mTLS 세션·2인 승인·HA/불변식/복구 모니터링·봇·카오스·설정·감사를 인메모리로 재현한다.
 * 데모 인증서 2종(op-01, op-02)으로 반결탁을 실제로 시연한다.
 * 요청 운영자와 승인 운영자가 같으면 승인이 반려된다(§1-3 (a)~(c)).
 */

import { isoNow } from "../format";
import { COIN_META } from "../sim/engine";
import type { AdminApi } from "./api";
import { AdminError } from "./api";
import { ACTION_CAP, approvalConsoleTarget, can, type Capability } from "./roles";
import { CHAOS_CATALOG, INVARIANT_DEFS } from "./catalog";
import type {
  AdminOperator,
  AdminOverview,
  AdminSession,
  ApprovalPayload,
  ApprovalRequest,
  ApprovalState,
  AuditEntry,
  Bot,
  BotType,
  ChaosExperimentDef,
  ChaosRun,
  ConfigMarket,
  CreateBotResult,
  DemoClientCert,
  DestructiveAction,
  DirectionBiasResult,
  FleetScaleResult,
  FleetScaleTargets,
  HaNode,
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
  SimStatus,
  TradeFlash,
} from "./types";
import { FLEET_TYPE_CAP } from "./types";

const SESSION_TTL_MS = 30 * 60_000; // 단기 관리자 세션
const APPROVAL_TTL_MS = 5 * 60_000; // §1-4 TTL 5분
const DEMO_CA = "demo-ca (Exchange Admin Demo Root)";

let seq = 0;
function uid(prefix: string): string {
  seq += 1;
  return `${prefix}-${Date.now().toString(36)}-${seq.toString(36)}`;
}
function rand(lo: number, hi: number): number {
  return lo + Math.random() * (hi - lo);
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

export class SimAdminApi implements AdminApi {
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
  private chaosRuns: ChaosRun[] = [];
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
  }));

  private nodes: HaNode[] = [
    { id: "engine-01", role: "leader", epoch: 7, seq: 148_233, followerLagMs: 0, ownedMarkets: COIN_META.map((c) => c.market), health: "healthy" },
    { id: "engine-02", role: "follower", epoch: 7, seq: 148_231, followerLagMs: 12, ownedMarkets: [], health: "healthy" },
    { id: "engine-03", role: "follower", epoch: 7, seq: 148_230, followerLagMs: 18, ownedMarkets: [], health: "healthy" },
  ];
  private epoch = 7;
  private recovery: RecoveryRun[] = [
    {
      id: uid("rec"),
      startedAt: isoNow(Date.now() - 3_600_000),
      durationMs: 17_400,
      rpoMs: 0,
      replayedFromSeq: 148_001,
      replayedToSeq: 148_120,
      fpReplay: "9f2c...a17",
      fpLive: "9f2c...a17",
      matched: true,
      verdict: "PASS",
    },
  ];
  private invariants: InvariantCheck[] = INVARIANT_DEFS.map((d) => ({
    name: d.name,
    label: d.label,
    ok: true,
    violatedRows: 0,
    detail: "정상",
    checkedAt: isoNow(),
  }));
  private tradesTotal = 4_182_557;
  private simRate = 120;
  private simRunning = true;
  private simPaused = false;
  private duplicateFillTotal = 0;
  private bots: Bot[] = this.seedBots();

  // 시장 생동감 상태(인메모리, 봇_방향성 §7-1 volatile 규율과 동형). 외부 의존 0
  private scaleTargets = new Map<string, FleetScaleTargets>();
  private biasStates = new Map<string, BiasState>();
  private tradeFlash: TradeFlash = "normal";

  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    if (typeof window !== "undefined") this.start();
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

  private start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), 1000);
  }

  private tick(): void {
    // HA seq 단조 증가 + follower lag 요동
    for (const n of this.nodes) {
      if (n.role === "leader" && n.health !== "down") n.seq += Math.round(rand(3, 9));
      if (n.role === "follower") {
        n.seq = Math.min(n.seq + Math.round(rand(3, 9)), this.leader().seq);
        n.followerLagMs = Math.max(0, Math.round(rand(5, 40) + (n.health === "degraded" ? 120 : 0)));
      }
    }
    this.tradesTotal += Math.round(rand(20, 90) * (this.simPaused ? 0 : 1));

    // 드물게 리더 degraded → 자동 failover → 복구 run 적재(§2-3)
    const leader = this.leader();
    if (leader.health === "healthy" && Math.random() < 0.008) {
      leader.health = "degraded";
    } else if (leader.health === "degraded" && Math.random() < 0.4) {
      this.failover();
    }

    for (const inv of this.invariants) inv.checkedAt = isoNow();

    // 측정 net bias가 effective로 느슨하게 수렴(관측 프록시). 자동균형 on이면 강도만큼 빠르게 당긴다
    if (!this.simPaused) {
      for (const st of this.biasStates.values()) {
        const pull = st.auto ? 0.04 + st.strength * 0.16 : 0.03;
        st.measured = clampBias(st.measured + (st.effective - st.measured) * pull + rand(-0.01, 0.01));
      }
    }
  }

  private leader(): HaNode {
    return this.nodes.find((n) => n.role === "leader") ?? this.nodes[0];
  }

  private failover(): void {
    const old = this.leader();
    const next = this.nodes.find((n) => n.role === "follower" && n.health !== "down");
    if (!next) {
      old.health = "healthy";
      return;
    }
    this.epoch += 1;
    old.role = "follower";
    old.health = "healthy";
    old.ownedMarkets = [];
    old.followerLagMs = 20;
    next.role = "leader";
    next.epoch = this.epoch;
    next.ownedMarkets = this.markets.map((m) => m.symbol);
    next.followerLagMs = 0;
    for (const n of this.nodes) n.epoch = this.epoch;
    const rto = Math.round(rand(9_000, 24_000));
    this.recovery.unshift({
      id: uid("rec"),
      startedAt: isoNow(),
      durationMs: rto,
      rpoMs: 0,
      replayedFromSeq: next.seq - Math.round(rand(80, 180)),
      replayedToSeq: next.seq,
      fpReplay: "9f2c...a17",
      fpLive: "9f2c...a17",
      matched: true,
      verdict: "PASS",
    });
    this.recovery = this.recovery.slice(0, 20);
  }

  /* ===== 인증 ===== */

  listDemoCerts(): DemoClientCert[] {
    return this.certs.slice();
  }

  establishSession(certId: string): Promise<AdminSession> {
    const cert = this.certs.find((c) => c.certId === certId);
    if (!cert) return Promise.reject(new AdminError("jwt_verification", "제시한 클라이언트 인증서를 신뢰 앵커로 검증하지 못했습니다"));
    const op = this.operators.find((o) => o.operatorId === cert.operatorId);
    if (!op || op.status !== "ACTIVE") {
      return Promise.reject(new AdminError("admin_forbidden", "비활성 운영자이거나 레지스트리에 없습니다"));
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

  /* ===== 권한·감사 ===== */

  private auth(cap: Capability = "VIEW"): AdminSession {
    const s = this.getSession();
    if (!s) throw new AdminError("jwt_verification", "관리자 세션이 없습니다");
    if (!can(s.roles, cap)) throw new AdminError("admin_forbidden", "권한이 부족합니다");
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
  }

  /* ===== 이중 승인 ===== */

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
    // 발급 주체(현재 세션)는 조회 권한이면 되고, 소각 권한은 요청 운영자 기준으로 소각 시점에 본다
    const approver = this.auth("VIEW");
    const requester = this.operators.find((o) => o.operatorId === requesterId);
    if (!requester || requester.status !== "ACTIVE") {
      return Promise.reject(new AdminError("invalid_parameter", "요청 운영자를 레지스트리에서 찾을 수 없습니다"));
    }
    // 반결탁: 발급 주체와 요청 주체가 같으면 발급 불가(§3-6)
    if (approver.operatorId === requesterId) {
      this.log(approver, `approval.issue.denied:${action}`, requesterId, null, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "발급 주체와 요청 주체가 같은 운영자입니다. 다른 운영자 인증서로 발급하세요"));
    }
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
    if (!a) return Promise.reject(new AdminError("admin_forbidden", "대상 승인이 없습니다"));
    if (a.state !== "ISSUED") return Promise.reject(new AdminError("admin_forbidden", `거부 불가 상태(${a.state})`));
    // 소유 검증: 발급 주체 또는 요청 주체만 거부(de-escalation, §6-B)
    if (s.operatorId !== a.approverId && s.operatorId !== a.requesterId) {
      this.log(s, "approval.reject.denied", a.targetLabel, a.id, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "발급자 또는 요청자만 거부할 수 있습니다"));
    }
    a.state = "REJECTED";
    this.log(s, `approval.reject:${a.action}`, a.targetLabel, a.id, "OK");
    return Promise.resolve(this.projectApproval(a));
  }

  consumeApproval(approval: IssuedApproval): Promise<void> {
    const s = this.auth("VIEW");
    this.sweep();
    const a = this.approvals.find((x) => x.id === approval.id);
    if (!a) return Promise.reject(new AdminError("admin_forbidden", "소각 대상 승인이 없습니다"));
    if (a.state !== "ISSUED") return Promise.reject(new AdminError("admin_forbidden", `소각 불가 상태(${a.state})`));
    // 단회 토큰 대조(발급 응답 bearer 시크릿)
    if (a.token !== approval.token) {
      this.log(s, "execute.denied", a.targetLabel, a.id, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "승인 토큰이 일치하지 않습니다"));
    }
    // 소각 주체는 토큰이 바인딩된 요청 운영자여야 한다(§3-6)
    if (a.requesterId !== s.operatorId) {
      this.log(s, "execute.denied", a.targetLabel, a.id, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "승인 토큰은 요청 운영자만 소각할 수 있습니다"));
    }
    // 소각 시점 실행 권한 재확인(발급 이후 역할 회수 대비)
    if (!can(s.roles, ACTION_CAP[a.action])) {
      return Promise.reject(new AdminError("admin_forbidden", "소각 시점 권한이 부족합니다"));
    }
    this.applyEffect(a.payload, s);
    a.state = "CONSUMED";
    a.consumedAt = isoNow();
    this.log(s, `execute:${a.action}`, a.targetLabel, a.id, "OK");
    return Promise.resolve();
  }

  private applyEffect(payload: ApprovalPayload, by: AdminSession): void {
    switch (payload.action) {
      case "chaos.inject": {
        const def = CHAOS_CATALOG.find((d) => d.id === payload.experimentId);
        if (!def) throw new AdminError("invalid_parameter", "알 수 없는 실험");
        this.chaosRuns.unshift({
          id: uid("chx"),
          experimentId: def.id,
          label: def.label,
          state: "RUNNING",
          blastRadius: def.blastRadius,
          grafanaAnnotation: `annotation-${def.id}-${Date.now().toString(36)}`,
          injectedBy: by.operatorId,
          startedAt: isoNow(),
          clearedAt: null,
        });
        this.chaosRuns = this.chaosRuns.slice(0, 40);
        break;
      }
      case "chaos.clear": {
        const run = this.chaosRuns.find((r) => r.id === payload.runId);
        if (run && run.state === "RUNNING") {
          run.state = "CLEARED";
          run.clearedAt = isoNow();
        }
        break;
      }
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
        const m = this.markets.find((x) => x.symbol === payload.symbol);
        if (m) {
          m.makerFee = payload.makerFee;
          m.takerFee = payload.takerFee;
        }
        break;
      }
      case "operators.create": {
        // 중복 operatorId는 생성하지 않는다(§6-D 409 market 대응)
        if (!this.operators.some((o) => o.operatorId === payload.operatorId)) {
          this.operators.push({
            operatorId: payload.operatorId,
            displayName: payload.displayName,
            roles: payload.roles.slice(),
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
    }
  }

  listApprovals(state?: ApprovalState): Promise<ApprovalRequest[]> {
    this.auth("VIEW");
    this.sweep();
    const rows = state ? this.approvals.filter((a) => a.state === state) : this.approvals;
    return Promise.resolve(rows.slice(0, 40).map((a) => this.projectApproval(a)));
  }

  /* ===== 모니터링 ===== */

  private overallHealth(): "success" | "warning" | "danger" {
    if (this.nodes.some((n) => n.health === "down")) return "danger";
    if (this.nodes.some((n) => n.health === "degraded") || this.invariants.some((i) => !i.ok)) return "warning";
    return "success";
  }

  getOverview(): Promise<AdminOverview> {
    this.auth("VIEW");
    this.sweep();
    const okCount = this.invariants.filter((i) => i.ok).length;
    return Promise.resolve({
      overallHealth: this.overallHealth(),
      epoch: this.epoch,
      leaderNodeId: this.leader().id,
      invariantsOk: okCount,
      invariantsTotal: this.invariants.length,
      lastRtoMs: this.recovery[0]?.durationMs ?? null,
      lastVerdict: this.recovery[0]?.verdict ?? null,
      botsActive: this.bots.filter((b) => b.active).length,
      currentOps: this.currentOps(),
      chaosRunning: this.chaosRuns.filter((r) => r.state === "RUNNING").length,
      // 발급형: ISSUED는 소각 대기분(발급=승인). 발급 전 요청은 서버 상태가 아니라 카운트하지 않는다
      pendingApprovals: this.approvals.filter((a) => a.state === "ISSUED").length,
      suspendedMarkets: this.markets.filter((m) => m.state === "HALTED").length,
      generatedAt: isoNow(),
    });
  }

  getHaStatus(): Promise<HaStatus> {
    this.auth("VIEW");
    const degraded = this.nodes.some((n) => n.health !== "healthy");
    return Promise.resolve({
      epoch: this.epoch,
      leaderNodeId: this.leader().id,
      nodes: this.nodes.map((n) => ({ ...n, ownedMarkets: n.ownedMarkets.slice() })),
      sequencerQueueSize: Math.round(rand(0, degraded ? 320 : 40)),
      orderLatencyP99Ms: Math.round(degraded ? rand(80, 160) : rand(6, 22)),
      ledgerImbalance: "0",
      tradesTotal: this.tradesTotal,
      hikariPending: Math.round(rand(0, degraded ? 12 : 2)),
      generatedAt: isoNow(),
    });
  }

  getRecovery(): Promise<HaRecovery> {
    this.auth("VIEW");
    return Promise.resolve({
      runs: this.recovery.slice(),
      lastRtoMs: this.recovery[0]?.durationMs ?? null,
      lastVerdict: this.recovery[0]?.verdict ?? null,
    });
  }

  getInvariants(): Promise<InvariantCheck[]> {
    this.auth("VIEW");
    return Promise.resolve(this.invariants.map((i) => ({ ...i })));
  }

  /* ===== 봇 플릿 ===== */

  private currentOps(): number {
    if (!this.simRunning || this.simPaused) return 0;
    const active = this.bots.filter((b) => b.active);
    const raw = active.reduce((sum, b) => sum + b.ops, 0);
    return Math.min(raw, this.simRate);
  }

  getSimStatus(): Promise<SimStatus> {
    this.auth("VIEW");
    const active = this.bots.filter((b) => b.active).length;
    return Promise.resolve({
      running: this.simRunning,
      paused: this.simPaused,
      rate: this.simRate,
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
    this.log(s, "sim.start", "전체 봇", null, "OK");
    return Promise.resolve();
  }

  simStop(): Promise<void> {
    const s = this.auth("BOT_OPS");
    this.simRunning = false;
    for (const b of this.bots) b.active = false;
    this.log(s, "sim.stop", "전체 봇", null, "OK");
    return Promise.resolve();
  }

  simPause(): Promise<void> {
    const s = this.auth("BOT_OPS");
    this.simPaused = !this.simPaused;
    this.log(s, "sim.pause", `paused=${this.simPaused}`, null, "OK");
    return Promise.resolve();
  }

  simSetRate(rate: number): Promise<void> {
    const s = this.auth("BOT_OPS");
    this.simRate = Math.max(0, Math.round(rate));
    this.log(s, "sim.rate", `${this.simRate} OPS`, null, "OK");
    return Promise.resolve();
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
    this.log(s, "sim.bots.create", `${type} ${market} +${created}`, null, "OK");
    return Promise.resolve({ created, appliedTarget: target, fleetTotal: this.bots.length, clamped, fleetPresent: true });
  }

  deleteBot(botId: string): Promise<void> {
    const s = this.auth("BOT_OPS");
    const before = this.bots.length;
    this.bots = this.bots.filter((b) => b.id !== botId);
    if (this.bots.length === before) return Promise.reject(new AdminError("invalid_parameter", "대상 봇이 없습니다"));
    this.log(s, "sim.bots.delete", botId, null, "OK");
    return Promise.resolve();
  }

  /* ===== 시장 생동감 ===== */

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
      return Promise.reject(new AdminError("invalid_parameter", "바이어스·강도 값이 올바르지 않습니다"));
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

  setEffectConfig(tradeFlash: TradeFlash): Promise<TradeFlash> {
    const s = this.auth("BOT_OPS");
    this.tradeFlash = tradeFlash;
    this.log(s, "sim.effects", `trade_flash=${tradeFlash}`, null, "OK");
    return Promise.resolve(tradeFlash);
  }

  /* ===== 카오스 ===== */

  getChaosCatalog(): ChaosExperimentDef[] {
    return CHAOS_CATALOG.slice();
  }

  getChaosRuns(): Promise<ChaosRun[]> {
    this.auth("VIEW");
    return Promise.resolve(this.chaosRuns.slice());
  }

  /* ===== 마켓 설정 ===== */

  getConfigMarkets(): Promise<ConfigMarket[]> {
    this.auth("VIEW");
    return Promise.resolve(this.markets.map((m) => ({ ...m })));
  }

  getNotices(): Promise<Notice[]> {
    this.auth("VIEW");
    return Promise.resolve(this.notices.map((n) => ({ ...n })));
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
    if (!n) return Promise.reject(new AdminError("invalid_parameter", "대상 공지가 없습니다"));
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

  /* ===== 운영자·감사 ===== */

  getOperators(): Promise<AdminOperator[]> {
    this.auth("VIEW");
    return Promise.resolve(this.operators.map((o) => ({ ...o, roles: o.roles.slice() })));
  }

  getAudit(): Promise<AuditEntry[]> {
    this.auth("VIEW");
    return Promise.resolve(this.audit.slice());
  }
}
