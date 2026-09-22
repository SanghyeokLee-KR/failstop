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
  HaNode,
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

  private nodes: HaNode[] = [
    { id: "engine-01", role: "leader", epoch: 7, seq: 148_233, followerLagMs: 0, ownedMarkets: COIN_META.map((c) => c.market), health: "healthy" },
    { id: "engine-02", role: "follower", epoch: 7, seq: 148_231, followerLagMs: 12, ownedMarkets: [], health: "healthy" },
    { id: "engine-03", role: "follower", epoch: 7, seq: 148_230, followerLagMs: 18, ownedMarkets: [], health: "healthy" },
  ];
  private epoch = 7;
  /**
   * 용량 콘솔 상태(ADR 0015). 계약면만 든다. 관측면은 부를 때마다 만든다.
   *
   * 실측을 미러한다. 노드 규격과 AZ 배치는 정본 6.5 사이징 표이고, 조회 전용 노드는
   * 매칭엔진을 끄고 뜬다. 세 번째 노드는 부하 구간에만 존재하므로 초기 desired는 2다.
   */
  private capacityDesired = 2;
  private readonly capacityNodes = [
    { id: "app-1", az: "ap-northeast-2a", instanceType: "t4g.small", engine: true, scalable: false },
    { id: "app-2", az: "ap-northeast-2c", instanceType: "t4g.small", engine: false, scalable: true },
    { id: "app-3", az: "ap-northeast-2c", instanceType: "t4g.small", engine: false, scalable: true },
  ];
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
    if (typeof window !== "undefined") {
      this.restore();
      this.start();
    }
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

  private restore(): void {
    try {
      const raw = window.sessionStorage.getItem(SimAdminApi.STORE_KEY);
      if (!raw) return;
      const p = JSON.parse(raw) as Partial<FleetSnapshot>;
      if (typeof p.running === "boolean") this.simRunning = p.running;
      if (typeof p.paused === "boolean") this.simPaused = p.paused;
      if (typeof p.rate === "number" && p.rate > 0) this.simRate = p.rate;
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
      rate: this.simRate,
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

  private start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), 1000);
  }

  private tick(): void {
    // HA seq 단조 증가 + follower lag 요동
    for (const n of this.nodes) {
      if (n.role === "leader" && n.health !== "down") n.seq += Math.round(rand(3, 9));
      if (n.role === "follower") {
        // 중단 노드는 복제가 멎는다. seq·lag를 재추첨하면 죽은 노드가 산 노드보다 빠른 역전이 생긴다
        if (n.health === "down") continue;
        n.seq = Math.min(n.seq + Math.round(rand(3, 9)), this.leader().seq);
        n.followerLagMs = Math.max(0, Math.round(rand(5, 40) + (n.health === "degraded" ? 120 : 0)));
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

  private failover(): void {
    const old = this.leader();
    const next = this.nodes.find((n) => n.role === "follower" && n.health !== "down");
    if (!next) {
      if (old.health === "degraded") old.health = "healthy";
      return;
    }
    this.epoch += 1;
    old.role = "follower";
    // 중단 노드는 승격 뒤에도 중단으로 남고, 자연 발생 degraded만 승격과 함께 회복한다
    old.health = old.health === "down" ? "down" : "healthy";
    old.ownedMarkets = [];
    old.followerLagMs = 20;
    next.role = "leader";
    next.epoch = this.epoch;
    next.ownedMarkets = this.markets.map((m) => m.symbol);
    next.followerLagMs = 0;
    for (const n of this.nodes) n.epoch = this.epoch;
    // 재생 소요다. 페일오버 RTO가 아니다(실측 40~159ms).
    const replayMs = Math.round(rand(40, 160));
    const replayFrom = next.seq - Math.round(rand(80, 180));
    this.recovery.unshift({
      id: uid("rec"),
      startedAt: isoNow(),
      durationMs: replayMs,
      rpoMs: null,
      replayedFromSeq: replayFrom,
      replayedToSeq: next.seq,
      fpReplay: `seq:${replayFrom}-${next.seq}:ev:${next.seq - replayFrom}`,
      fpLive: `seq:${replayFrom}-${next.seq}:ev:${next.seq - replayFrom}`,
      matched: true,
      verdict: "PASS",
    });
    this.recovery = this.recovery.slice(0, 20);
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
    this.applyEffect(a.payload, s);
    a.state = "CONSUMED";
    a.consumedAt = isoNow();
    this.log(s, `execute:${a.action}`, a.targetLabel, a.id, "OK");
    return Promise.resolve();
  }

  private applyEffect(payload: ApprovalPayload, by: AdminSession): void {
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
    }
  }

  listApprovals(state?: ApprovalState, opts: PageOpts = {}): Promise<AdminPage<ApprovalRequest>> {
    this.auth("VIEW");
    this.sweep();
    const rows = state ? this.approvals.filter((a) => a.state === state) : this.approvals;
    return Promise.resolve(simPage(rows, opts, (a) => a.id, (a) => this.projectApproval(a)));
  }

  private overallHealth(): "success" | "warning" | "danger" {
    if (this.nodes.some((n) => n.health === "down")) return "danger";
    if (this.nodes.some((n) => n.health === "degraded") || this.invariants.some((i) => !i.ok)) {
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
      // 목에 페일오버 주행 표가 없다. 재생 소요를 RTO로 실으면 없는 축을 지어낸다.
      lastRtoMs: null,
      // getRecovery와 같은 함수다. 두 화면이 다른 결론을 내면 안 된다.
      lastVerdict: aggregateVerdict(this.recovery),
      botsActive: this.bots.filter((b) => b.active).length,
      currentOps: this.currentOps(),
      // 발급형: ISSUED는 소각 대기분(발급=승인). 발급 전 요청은 서버 상태가 아니라 카운트하지 않는다
      pendingApprovals: this.approvals.filter((a) => a.state === "ISSUED").length,
      suspendedMarkets: this.markets.filter((m) => m.state === "HALTED").length,
      generatedAt: isoNow(),
    });
  }

  /* ===== 용량(ADR 0015 수동 스케일링) ===== */

  getCapacity(): Promise<CapacityState> {
    this.auth("VIEW");
    return Promise.resolve(this.capacitySnapshot());
  }

  scaleOut(reason?: string): Promise<CapacityState> {
    return Promise.resolve(this.capacityChange(+1, "capacity.scale_out", reason));
  }

  scaleIn(reason?: string): Promise<CapacityState> {
    return Promise.resolve(this.capacityChange(-1, "capacity.scale_in", reason));
  }

  private capacityChange(delta: number, action: string, reason?: string): CapacityState {
    // 용량 변경은 비용을 쓰는 인프라 조치라 SUPER 를 요구한다(서버와 같은 판정).
    const session = this.auth("SUPER");
    const min = 2;
    const max = 3;
    const next = this.capacityDesired + delta;
    if (next < min || next > max) {
      this.log(session, action, "app", null, "DENIED");
      throw new AdminError("CAPACITY_OUT_OF_BOUNDS",
        next < min ? `최소 ${min}대 아래로 축소 불가` : `최대 ${max}대 위로 증설 불가`);
    }
    this.capacityDesired = next;
    this.log(session, action, "app", null, "OK");
    void reason;
    return this.capacitySnapshot();
  }

  /**
   * 관측면을 만든다. 시뮬레이터라 값을 지어내지만 두 가지는 실제와 같게 둔다.
   *
   * 하나. 계약과 관측이 즉시 일치하지 않는다. 실제 조정기가 노드를 세우는 데 약 17초가
   * 걸리므로 버튼 직후에는 어긋난 창이 있다. 여기서는 desired가 오른 직후 한 번은 관측이
   * 따라가지 않은 것으로 낸다면 화면 검증이 어려우므로 즉시 맞춘다. 그 대신 화면이
   * reconciled를 실제로 소비하는지는 http 어댑터에서 확인한다.
   *
   * 둘. 리더는 하나뿐이고 조회 전용 노드는 readiness가 503이다. 이것이 설계이고
   * 화면 문구가 그것을 정상으로 읽어야 한다.
   */
  private capacitySnapshot(): CapacityState {
    const nodes: CapacityNode[] = this.capacityNodes.map((c, i) => {
      const expected = i < this.capacityDesired;
      const reachable = expected;
      const cpu = reachable ? 0.28 + i * 0.06 : null;
      return {
        id: c.id,
        az: c.az,
        instanceType: c.instanceType,
        engine: c.engine,
        scalable: c.scalable,
        expected,
        reachable,
        leader: reachable && c.engine,
        servesReads: reachable,
        cpuRatio: cpu,
        cpuCount: reachable ? 2 : null,
        heapUsedBytes: reachable ? 620_000_000 + i * 20_000_000 : null,
        heapMaxBytes: reachable ? 742_000_000 : null,
        httpRequests: reachable ? 1_200_000 + i * 90_000 : null,
        wsClients: reachable ? 380 + i * 40 : null,
      };
    });
    const serving = nodes.filter((n) => n.servesReads && n.cpuRatio !== null);
    const avg = serving.length === 0
      ? null
      : serving.reduce((a, n) => a + (n.cpuRatio ?? 0), 0) / serving.length;
    const pct = avg === null ? 0 : Math.round(avg * 100);
    const advice: CapacityAdvice[] = avg === null
      ? [{ id: "no_metrics", level: "warn", headline: "상태 읽기 실패",
          detail: "조회를 받는 서버에서 사용률을 가져오지 못했다. 판단 근거가 없어 권고를 내지 않는다.", action: null }]
      : avg >= 0.6 && this.capacityDesired < 3
        ? [{ id: "scale_out", level: "warn", headline: "조회 부하 높음",
            detail: `조회를 받는 서버의 평균 사용률 ${pct}%. 서버를 한 대 늘리면 부하가 나뉜다. 추가에 약 17초가 걸리고 서비스는 멈추지 않는다.`, action: "SCALE_OUT" }]
        : avg <= 0.3 && this.capacityDesired > 2
          ? [{ id: "scale_in", level: "info", headline: "조회 부하 낮음",
              detail: `조회를 받는 서버의 평균 사용률 ${pct}%. 서버를 한 대 줄이면 비용이 준다. 줄이기는 진행 중인 요청을 먼저 흘려보낸 뒤 내린다.`, action: "SCALE_IN" }]
          : [{ id: "steady", level: "ok", headline: "안정",
              detail: `조회를 받는 서버의 평균 사용률 ${pct}%. 조치 불요.`, action: null }];
    const up = nodes.filter((n) => n.reachable).length;
    return {
      tier: "app",
      plan: { min: 2, desired: this.capacityDesired, max: 3, updatedBy: "sim", updatedAt: isoNow() },
      observedTotal: up,
      reconciled: up === this.capacityDesired,
      readAvgCpuRatio: avg,
      nodes,
      advice,
    };
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
    if (!this.simRunning || this.simPaused) return 0;
    const active = this.bots.filter((b) => b.active);
    const raw = active.reduce((sum, b) => sum + b.ops, 0);
    return Math.min(raw, this.simRate);
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

  simSetRate(rate: number): Promise<ControlApply> {
    const s = this.auth("BOT_OPS");
    const next = Math.round(rate);
    // 서버 SimAdminController.parsePositive와 동일 계약. 0·음수는 정지 버튼 소관이라 반려한다
    if (next <= 0) {
      return Promise.reject(new AdminError("invalid_parameter", "target_ops는 양수여야 합니다."));
    }
    this.simRate = next;
    this.log(s, "sim.rate", `${this.simRate} OPS`, null, "OK");
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
      // 목 어댑터는 거버너와 한 프로세스라 항상 즉시 적용이다. 큐 경로는 서버 배치의
      // 물리 분리에서만 나오므로 여기서 재현할 대상이 아니다.
      applyPath: "local" as const,
      queuedCommandId: 0,
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
