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
import { feeRateError } from "./fees";
import { CHAOS_CATALOG, INVARIANT_DEFS, scenarioDef } from "./catalog";
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
  AdminInquiry,
  AdminTransfer,
  AdminTransferStatus,
  CommunityReport,
  ContentStatus,
  ModerationAction,
  ReportResolution,
  ReportStatus,
  ScenarioName,
  ScenarioResult,
  ScenarioMarketResult,
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

/**
 * sim 커서 페이지. 서버와 같은 의미를 갖되 인코딩만 단순하다. 마지막으로 본 항목의 id다.
 *
 * 없는 커서를 조용히 첫 쪽으로 되돌리지 않는다. 서버는 400을 주므로, 여기서 관대하면
 * sim에서만 통과하는 화면 코드가 생긴다.
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
  // 하네스 실측 분포(9~24s)를 미러한 시드 6건. 단건 시드는 RtoSpark가 표본 부족으로 접힌다
  private recovery: RecoveryRun[] = [
    { hoursAgo: 1, durationMs: 17_400, from: 148_001, to: 148_120 },
    { hoursAgo: 5, durationMs: 12_050, from: 147_410, to: 147_512 },
    { hoursAgo: 11, durationMs: 21_800, from: 146_770, to: 146_931 },
    { hoursAgo: 19, durationMs: 9_640, from: 146_102, to: 146_188 },
    { hoursAgo: 27, durationMs: 14_320, from: 145_488, to: 145_601 },
    { hoursAgo: 38, durationMs: 23_110, from: 144_820, to: 144_997 },
  ].map((r) => ({
    id: uid("rec"),
    startedAt: isoNow(Date.now() - r.hoursAgo * 3_600_000),
    durationMs: r.durationMs,
    rpoMs: 0,
    replayedFromSeq: r.from,
    replayedToSeq: r.to,
    fpReplay: "9f2c...a17",
    fpLive: "9f2c...a17",
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
  private tradesTotal = 4_182_557;
  private simRate = 120;
  private simRunning = true;
  private simPaused = false;
  private duplicateFillTotal = 0;
  private bots: Bot[] = this.seedBots();

  /**
   * 프리셋 배수 기준선(§6-A v2.31). 시드 플릿과 초기 OPS를 한 번만 잡는다. 활성화 시점
   * 현재값을 곱하면 재활성화가 누적돼 spike 3회가 기준선 OPS의 1000배가 된다.
   */
  private readonly baseRate = this.simRate;
  private readonly scenarioBaseline = this.captureBaseline();

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
    // HA seq 단조 증가 + follower lag 요동. 진행 중 실험은 팔로워 lag를 밀어 올린다
    const chaosLag = this.chaosActive() ? Math.round(rand(60, 160)) : 0;
    for (const n of this.nodes) {
      if (n.role === "leader" && n.health !== "down") n.seq += Math.round(rand(3, 9));
      if (n.role === "follower") {
        // 중단 노드는 복제가 멎는다. seq·lag를 재추첨하면 죽은 노드가 산 노드보다 빠른 역전이 생긴다
        if (n.health === "down") continue;
        n.seq = Math.min(n.seq + Math.round(rand(3, 9)), this.leader().seq);
        n.followerLagMs = Math.max(0, Math.round(rand(5, 40) + (n.health === "degraded" ? 120 : 0) + chaosLag));
      }
    }
    this.tradesTotal += Math.round(rand(20, 90) * (this.simPaused ? 0 : 1));

    // 리더 사망(E4·E6 주입)은 즉시, 드문 자연 degraded는 확률적으로 failover → 복구 run 적재(§2-3)
    const leader = this.leader();
    if (leader.health === "down") {
      this.failover();
    } else if (leader.health === "healthy" && Math.random() < 0.008) {
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

  private chaosActive(): boolean {
    return this.chaosRuns.some((r) => r.state === "RUNNING");
  }

  private chaosRunningIds(): Set<string> {
    return new Set(this.chaosRuns.filter((r) => r.state === "RUNNING").map((r) => r.experimentId));
  }

  /**
   * 실험이 노드 헬스에 남기는 효과(§2-2 데모 결합). 주입이 화면에 보이지 않으면
   * 카오스 콘솔이 상태 기계 없는 목록으로 전락한다.
   * E4·E6: 리더 사망 → tick이 failover를 일으키고 죽은 노드는 해제 전까지 중단.
   * E5·E7: 성능 저하(분할·일시 정지) → 팔로워 또는 리더 degraded.
   * 나머지(E1·E2·E3·E8·E9)는 노드가 아니라 지표 압력으로만 나타난다(getHaStatus).
   */
  private applyChaosNodeEffect(experimentId: string): void {
    if (experimentId === "E4" || experimentId === "E6") {
      this.leader().health = "down";
    } else if (experimentId === "E5") {
      const f = this.nodes.find((n) => n.role === "follower" && n.health === "healthy");
      if (f) f.health = "degraded";
    } else if (experimentId === "E7") {
      this.leader().health = "degraded";
    }
  }

  /** 해제 시 잔여 효과 회수. 같은 계열 실험이 아직 돌고 있으면 남긴다 */
  private clearChaosNodeEffect(): void {
    const running = this.chaosRunningIds();
    const killActive = running.has("E4") || running.has("E6");
    const degradeActive = running.has("E5") || running.has("E7");
    for (const n of this.nodes) {
      if (n.health === "down" && !killActive) n.health = "healthy";
      if (n.health === "degraded" && !degradeActive) n.health = "healthy";
    }
  }

  private failover(): void {
    const old = this.leader();
    const next = this.nodes.find((n) => n.role === "follower" && n.health !== "down");
    if (!next) {
      if (old.health === "degraded") old.health = "healthy";
      return;
    }
    this.epoch += 1;
    old.role = "follower";
    // E4로 죽은 노드는 해제 전까지 중단, E7(일시 정지)로 저하된 노드는 해제 전까지 저하를
    // 유지한다. 자연 발생 degraded만 승격과 함께 회복
    const e7Active = this.chaosRunningIds().has("E7");
    old.health = old.health === "down" ? "down" : e7Active ? "degraded" : "healthy";
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

  /* ===== 권한·감사 ===== */

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
    // 발급 주체 요건은 요청 운영자와 다른 mTLS 신원뿐(관리자_설계 §3-6). 서버 ApprovalController.issue와
    // 동일하게 역할을 요구하지 않는다(무역할 운영자도 발급 가능). 소각 권한은 소각 시점에 본다
    const approver = this.requireSession();
    const requester = this.operators.find((o) => o.operatorId === requesterId);
    if (!requester || requester.status !== "ACTIVE") {
      return Promise.reject(new AdminError("invalid_parameter", "요청 운영자를 레지스트리에서 찾을 수 없음"));
    }
    // 반결탁: 발급 주체와 요청 주체가 같으면 발급 불가(§3-6)
    if (approver.operatorId === requesterId) {
      this.log(approver, `approval.issue.denied:${action}`, requesterId, null, "DENIED");
      return Promise.reject(new AdminError("admin_forbidden", "발급 주체와 요청 주체가 같은 운영자. 다른 운영자 인증서로 발급 필요"));
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
        const runId = uid("chx");
        this.chaosRuns.unshift({
          id: runId,
          // sim은 별도 toxic 이름이 없어 run id가 곧 해제 키다(clear 매칭도 id 기준)
          clearKey: runId,
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
        this.applyChaosNodeEffect(def.id);
        break;
      }
      case "chaos.clear": {
        const run = this.chaosRuns.find((r) => r.id === payload.runId);
        if (run && run.state === "RUNNING") {
          run.state = "CLEARED";
          run.clearedAt = isoNow();
          this.clearChaosNodeEffect();
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
        // 생성은 신원만 등록한다. 역할은 operators.roles 별도 승인 경로로만 부여한다(§3-6, 반결탁).
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
    }
  }

  listApprovals(state?: ApprovalState, opts: PageOpts = {}): Promise<AdminPage<ApprovalRequest>> {
    this.auth("VIEW");
    this.sweep();
    const rows = state ? this.approvals.filter((a) => a.state === state) : this.approvals;
    return Promise.resolve(simPage(rows, opts, (a) => a.id, (a) => this.projectApproval(a)));
  }

  /* ===== 모니터링 ===== */

  private overallHealth(): "success" | "warning" | "danger" {
    if (this.nodes.some((n) => n.health === "down")) return "danger";
    // 진행 중 실험은 지표 압력을 만들므로(getHaStatus) 종합도 최소 주의로 내린다
    if (this.nodes.some((n) => n.health === "degraded") || this.invariants.some((i) => !i.ok) || this.chaosActive()) {
      return "warning";
    }
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
    // 노드 이상뿐 아니라 진행 중 실험(E1 지연·E8 예외 폭풍 등)도 지표 압력으로 나타난다
    const degraded = this.nodes.some((n) => n.health !== "healthy") || this.chaosActive();
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
    const next = Math.round(rate);
    // 서버 SimAdminController.parsePositive와 동일 계약. 0·음수는 정지 버튼 소관이라 반려한다
    if (next <= 0) {
      return Promise.reject(new AdminError("invalid_parameter", "target_ops는 양수여야 합니다."));
    }
    this.simRate = next;
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
    if (this.bots.length === before) return Promise.reject(new AdminError("invalid_parameter", "대상 봇 없음"));
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
    this.simRate = Math.max(1, Math.round(this.baseRate * def.opsMult));

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

    this.log(s, "sim.scenario", name, null, "OK");
    return Promise.resolve({
      scenario: name,
      targetOps: String(this.simRate),
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

  /* ===== 카오스 ===== */

  getChaosCatalog(): ChaosExperimentDef[] {
    return CHAOS_CATALOG.slice();
  }

  getChaosRuns(opts: PageOpts = {}): Promise<AdminPage<ChaosRun>> {
    this.auth("VIEW");
    return Promise.resolve(simPage(this.chaosRuns, opts, (r) => r.id, (r) => ({ ...r })));
  }

  /* ===== 마켓 설정 ===== */

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
   * sim 관리 어댑터는 회원 어댑터(SimDataSource)와 다른 객체라 실제 요청 목록을 공유하지
   * 못한다. 승인 흐름의 화면·권한·상태 전이를 확인할 수 있도록 씨앗 행을 들고 있는다.
   * 자금은 여기서 움직이지 않는다. 실제 원장 이동은 http 경로에서만 일어난다. */
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

  /* ===== 운영자·감사 ===== */

  getOperators(): Promise<AdminOperator[]> {
    this.auth("VIEW");
    return Promise.resolve(this.operators.map((o) => ({ ...o, roles: o.roles.slice() })));
  }

  getAudit(opts: PageOpts = {}): Promise<AdminPage<AuditEntry>> {
    this.auth("VIEW");
    return Promise.resolve(simPage(this.audit, opts, (e) => e.id, (e) => ({ ...e })));
  }
}
