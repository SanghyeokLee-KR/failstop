/**
 * 앱 계층은 mTLS 신원에서 유래한 단기 세션 토큰을 Bearer로 싣고 메모리에만 둔다(§1-3).
 * 승인은 발급형이라 승인자가 approval_token을 발급하고 요청자가 액션 바디에 실어 소각한다(§3-6).
 * 읽기 응답은 무검사 캐스팅 없이 언랩·매핑하고, 서버 미노출 필드는 안전 기본값으로 좁힌다.
 */

import { AdminError, type AdminApi } from "./api";
import { approvalConsoleTarget } from "./roles";
import { INVARIANT_DEFS } from "./catalog";
import { summarizeInvariants } from "./invariants";
import { isoNow } from "../format";
import { COIN_META } from "../sim/engine";
import type {
  AdminPage,
  PageOpts,
  AdminOperator,
  AdminOverview,
  AdminRole,
  AdminSession,
  ApprovalPayload,
  ApprovalRequest,
  ApprovalState,
  AuditEntry,
  BotType,
  CapacityAdvice,
  CapacityAdviceLevel,
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
  HaRecovery,
  HaStatus,
  InvariantCheck,
  IssuedApproval,
  MarketSimState,
  MarketState,
  Notice,
  NoticePatch,
  NoticeSeverity,
  ControlApply,
  RecoveryRun,
  RecoveryVerdict,
  ScenarioName,
  ScenarioResult,
  SimStatus,
  SimStatusSource,
  TradeFlash,
  AdminInquiry,
  AdminTransfer,
  AdminMember,
  AdminMemberDetail,
  CommunityReport,
  ContentStatus,
  MemberStatus,
  MemberTransition,
  ModerationAction,
  ReportReason,
  ReportResolution,
  ReportStatus,
  AdminTransferStatus,
} from "./types";

/** 마켓 심볼 -> 한글명. 서버 config/markets는 한글명을 싣지 않아 표시용으로만 조인한다 */
const COIN_NAME = new Map(COIN_META.map((c) => [c.market, c.koreanName]));
/** 불변식 check_name -> 라벨. 서버 invariants는 라벨을 싣지 않는다 */
const INVARIANT_LABEL = new Map(INVARIANT_DEFS.map((d) => [d.name, d.label]));
/** 커서 쪽 요청 질의문자열. 빈 값은 붙이지 않아 URL이 조건마다 달라지지 않는다 */
function pageQuery(opts: PageOpts, extra: Record<string, string> = {}): string {
  const q = new URLSearchParams(extra);
  if (opts.limit) q.set("limit", String(opts.limit));
  if (opts.cursor) q.set("cursor", opts.cursor);
  const s = q.toString();
  return s ? `?${s}` : "";
}

/** 서버 스네이크 엔벌로프를 관리 도메인 카멜 페이지로 옮긴다 */
function toPage<T>(data: T[], res: { pagination?: { next_cursor?: string | null; has_more?: boolean } }): AdminPage<T> {
  return {
    data,
    nextCursor: res.pagination?.next_cursor ?? null,
    hasMore: !!res.pagination?.has_more,
  };
}

type PagedEnvelope<T> = WireDataEnvelope<T> & {
  pagination?: { next_cursor?: string | null; has_more?: boolean };
};

/**
 * 용량 응답 매핑. 지표가 null로 오면 null로 둔다.
 *
 * 0으로 좁히지 않는 이유가 있다. "사용률 0%"와 "사용률을 못 읽었다"는 다른 사실이고,
 * 둘을 합치면 화면이 죽은 노드를 한가한 노드로 그린다. 서버도 같은 이유로 못 읽은 값을
 * null로 낸다(NodeProbe).
 */
function mapCapacity(w: WireCapacity): CapacityState {
  const plan = w.plan ?? {};
  const nodes: CapacityNode[] = (w.nodes ?? []).map((n) => ({
    id: n.id ?? "",
    az: n.az ?? "",
    instanceType: n.instance_type ?? "",
    engine: n.engine === true,
    scalable: n.scalable === true,
    expected: n.expected === true,
    reachable: n.reachable === true,
    leader: n.leader === true,
    servesReads: n.serves_reads === true,
    cpuRatio: nullableNum(n.cpu_ratio),
    cpuCount: nullableNum(n.cpu_count),
    heapUsedBytes: nullableNum(n.heap_used_bytes),
    heapMaxBytes: nullableNum(n.heap_max_bytes),
    httpRequests: nullableNum(n.http_requests),
    wsClients: nullableNum(n.ws_clients),
  }));
  const advice: CapacityAdvice[] = (w.advice ?? []).map((a) => ({
    id: a.id ?? "",
    level: (["ok", "info", "warn", "danger"].includes(a.level ?? "")
      ? a.level
      : "info") as CapacityAdviceLevel,
    headline: a.headline ?? "",
    detail: a.detail ?? "",
    action: a.action === "SCALE_OUT" || a.action === "SCALE_IN" ? a.action : null,
  }));
  return {
    tier: w.tier ?? "app",
    plan: {
      min: num(plan.min, 2),
      desired: num(plan.desired, 2),
      max: num(plan.max, 2),
      updatedBy: plan.updated_by ?? "",
      updatedAt: plan.updated_at ?? "",
    },
    observedTotal: num(w.observed_total),
    reconciled: w.reconciled === true,
    readAvgCpuRatio: nullableNum(w.read_avg_cpu_ratio),
    nodes,
    advice,
  };
}

/** 숫자면 숫자, 아니면 null. num()과 달리 기본값으로 좁히지 않는다 */
function nullableNum(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * 콘솔 액션·payload를 백엔드 승인 바인딩(action + 대상)으로 분해한다.
 * 콘솔 targetKey와 달리 여기 target은 실제 자원(node·toxic·symbol·operatorId)이라 재사용하지 않는다.
 */
function wireBinding(payload: ApprovalPayload): { action: string; target: string } {
  switch (payload.action) {
    case "sim.bots.params":
      return { action: "sim.bots.params", target: payload.botId };
    case "config.market.halt":
      return { action: "config.market.halt", target: payload.symbol };
    case "config.market.resume":
      return { action: "config.market.resume", target: payload.symbol };
    case "config.market.fees":
      return { action: "config.market.fees", target: payload.symbol };
    case "operators.create":
      return { action: "operators.create", target: payload.operatorId };
    case "operators.status":
      // 전이 스코프까지 바인딩한다. bare operatorId면 상태 토큰이 빠져 backend 복합 target과 어긋난다
      return { action: "operators.status", target: `${payload.operatorId}:${payload.status.toUpperCase()}` };
    case "operators.roles":
      // 신원+방향+역할까지 바인딩해 같은 토큰의 다른 역할 변경 재사용을 차단한다
      return {
        action: "operators.roles",
        target: `${payload.operatorId}:${payload.grant.toUpperCase()}:${payload.role.toUpperCase()}`,
      };
    case "members.close":
      // 자원 스코프다. target은 대상 회원의 external_id(§6-E 바인딩)
      return { action: "members.close", target: payload.externalId };
  }
}

/**
 * 원장의 바인딩 action -> 콘솔 의미 액션 역매핑.
 * 감사 원장은 append-only라 폐기한 카오스 행이 과거 기록으로 남는다. 그 행은 콘솔 액션으로
 * 되돌릴 수 없으므로 폴백으로 흘리고, 화면은 용어집(glossary)이 원문 코드를 풀어 보여 준다.
 */
function consoleAction(wireAction: string): DestructiveAction {
  switch (wireAction) {
    case "sim.bots.params":
    case "config.market.halt":
    case "config.market.resume":
    case "config.market.fees":
    case "operators.create":
    case "operators.status":
    case "operators.roles":
    case "members.close":
      return wireAction;
    default:
      return "sim.bots.params";
  }
}

const APPROVAL_STATES: ApprovalState[] = ["ISSUED", "CONSUMED", "EXPIRED", "REJECTED"];
function normApprovalState(v: unknown): ApprovalState {
  return APPROVAL_STATES.includes(v as ApprovalState) ? (v as ApprovalState) : "ISSUED";
}

function normMarketState(v: unknown): MarketState {
  if (v === "ACTIVE") return "ACTIVE";
  if (v === "DELISTED") return "DELISTED";
  return "HALTED";
}

// 서버 와이어는 대문자(INFO·WARNING·CRITICAL), 프론트 타입은 소문자다. 수신은 소문자 정규화,
// 송신은 toUpperCase로 맞춘다. 미정규화 시 등록이 400으로 반려되고 조회 위계가 전부 info로 열화된다
const NOTICE_SEVS: NoticeSeverity[] = ["info", "warning", "critical"];
function normSeverity(v: unknown): NoticeSeverity {
  const s = String(v ?? "").toLowerCase() as NoticeSeverity;
  return NOTICE_SEVS.includes(s) ? s : "info";
}

/** GET /admin/v1/members data 원소 */
interface WireMember {
  external_id?: string;
  email?: string;
  display_name?: string | null;
  status?: string;
  created_at?: string;
  /* 활동 요약(§6-E v2.35). 목록·상세가 같은 필드로 낸다 */
  open_order_count?: number | string;
  last_order_at?: string | null;
  asset_kinds?: number | string;
}

const MEMBER_STATUSES: MemberStatus[] = ["ACTIVE", "SUSPENDED", "CLOSED"];

/** CHECK 밖 값은 화면이 깨지지 않게 ACTIVE로 좁힌다(전이 버튼은 상태로 갈린다) */
function normMemberStatus(v: unknown): MemberStatus {
  return MEMBER_STATUSES.includes(v as MemberStatus) ? (v as MemberStatus) : "ACTIVE";
}

function toMember(w: WireMember): AdminMember {
  return {
    externalId: w.external_id ?? "",
    email: w.email ?? "",
    displayName: w.display_name ?? null,
    status: normMemberStatus(w.status),
    createdAt: Date.parse(w.created_at ?? "") || 0,
    // 활동 요약은 목록·상세가 같은 필드명으로 온다(§6-E v2.35). 한 자리에서 옮긴다
    openOrderCount: num(w.open_order_count),
    lastOrderAt: w.last_order_at ? Date.parse(w.last_order_at) || null : null,
    assetKinds: num(w.asset_kinds),
  };
}

/** GET /admin/v1/community/reports data 원소. 대상 요약은 조인 실패 시 null이다 */
interface WireReport {
  report_id?: number | string;
  target_type?: string;
  target_id?: number | string;
  target_external_id?: string | null;
  target_status?: string | null;
  target_excerpt?: string | null;
  target_author?: string | null;
  reporter_id?: number | string;
  reason?: string;
  detail?: string | null;
  status?: string;
  created_at?: string;
}

const REPORT_STATUSES: ReportStatus[] = ["PENDING", "REVIEWED", "ACTIONED", "DISMISSED"];
const REPORT_REASONS: ReportReason[] = ["SPAM", "ABUSE", "ILLEGAL", "ETC"];
const CONTENT_STATUSES: ContentStatus[] = ["VISIBLE", "HIDDEN", "DELETED"];

/** 미지 어휘는 정본 CHECK 밖이라 좁히지 않고 마지막 값으로 떨어뜨린다(ETC·DISMISSED) */
function toCommunityReport(w: WireReport): CommunityReport {
  const status = REPORT_STATUSES.includes(w.status as ReportStatus)
    ? (w.status as ReportStatus) : "PENDING";
  const reason = REPORT_REASONS.includes(w.reason as ReportReason)
    ? (w.reason as ReportReason) : "ETC";
  const targetStatus = CONTENT_STATUSES.includes(w.target_status as ContentStatus)
    ? (w.target_status as ContentStatus) : null;
  return {
    reportId: num(w.report_id),
    targetType: w.target_type === "COMMENT" ? "COMMENT" : "POST",
    targetId: num(w.target_id),
    targetExternalId: w.target_external_id ?? null,
    targetStatus,
    targetExcerpt: w.target_excerpt ?? null,
    targetAuthor: w.target_author ?? null,
    reporterId: num(w.reporter_id),
    reason,
    detail: w.detail ?? null,
    status,
    createdAt: Date.parse(w.created_at ?? "") || 0,
  };
}

interface ErrorEnvelope {
  error?: { name?: string; message?: string; retry_after?: number | null };
}

/** 429 대기 초 -> 안내 문구. 초를 알면 명시, 모르면 "잠시 후"로 열화 */
function rateLimitMessage(retryAfter: number | null): string {
  const when = retryAfter != null && retryAfter > 0 ? `${retryAfter}초 후` : "잠시 후";
  return `관리 요청 한도 초과. ${when} 다시 시도 필요`;
}

/** POST /admin/v1/session 와이어 스키마(§3-6, snake_case). issued_at은 서버 미전송 */
interface WireSession {
  schema_version?: string;
  token?: string;
  operator_id?: string;
  display_name?: string;
  roles?: AdminRole[];
  issued_at?: string;
  expires_at?: string;
}

/* ===== 승인 발급형 와이어 스키마(§6-B) ===== */

/** POST /admin/v1/approvals 발급 응답. approval_token은 이 응답에만 실린다(원장 프로젝션 제외) */
interface WireIssue {
  id?: number | string;
  approval_id?: number | string;
  approval_token?: string;
  issued_at?: string;
  expires_at?: string;
}
/** GET /admin/v1/approvals data 원소. token 미포함. 원장 프로젝션은 행 id를 approval_id로 투영한다 */
interface WireApproval {
  approval_id?: number | string;
  id?: number | string;
  action?: string;
  target?: string;
  requester_operator_id?: string;
  approver_operator_id?: string;
  state?: string;
  issued_at?: string;
  expires_at?: string;
  consumed_at?: string | null;
}

/* ===== /admin/v1/sim/status 1.1 와이어 스키마(§5-5). snake_case 원본을 매핑한다 ===== */

interface WireScaleEntry {
  target?: number;
  active?: number;
}
interface WireMarketState {
  market: string;
  target_net_bias?: string;
  measured_net_bias?: string;
  effective_bias?: string;
  auto_balance?: { enabled?: boolean; strength?: string };
  scale?: Record<string, WireScaleEntry>;
  fill_ratio?: string;
  trade_flash?: TradeFlash;
}
interface WireSimStatus {
  schema_version?: string;
  fleet_present?: boolean;
  /** status 1.3(§6-A). local·published·unknown. 구 서버는 부재 */
  source?: SimStatusSource;
  observed_at?: string;
  stale?: boolean;
  /** status 1.2(v2.22)부터 실제 플릿 상태. 구 서버는 부재라 fleet_present 폴백 */
  running?: boolean;
  paused?: boolean;
  target_ops?: string;
  trade_flash?: TradeFlash;
  markets?: WireMarketState[];
}
/** POST /admin/v1/sim/bots 응답(governor set-point, §6-A) */
interface WireCreateBot {
  created?: number;
  applied_target?: number;
  fleet_total?: number;
  clamped?: boolean;
  fleet_present?: boolean;
}

/** GET·POST /admin/v1/support 와이어(§4-G, snake_case) */
interface AdminInquiryWire {
  external_id?: string;
  category?: string;
  title?: string;
  body?: string;
  status?: string;
  answer?: string | null;
  answered_at?: number | null;
  user_email?: string;
  created_at?: number;
}

function toAdminInquiry(w: AdminInquiryWire): AdminInquiry {
  const at = (v: unknown) => (typeof v === "number" ? v : Number(v ?? 0) || 0);
  return {
    externalId: w.external_id ?? "",
    category: (w.category as AdminInquiry["category"]) ?? "ETC",
    title: w.title ?? "",
    body: w.body ?? "",
    status: w.status === "ANSWERED" ? "ANSWERED" : "OPEN",
    answer: w.answer ?? null,
    answeredAt: w.answered_at == null ? null : at(w.answered_at),
    userEmail: w.user_email ?? "",
    createdAt: at(w.created_at),
  };
}

/** GET·POST /admin/v1/transfers 와이어(§4-E, snake_case) */
interface AdminTransferWire {
  external_id?: string;
  direction?: string;
  asset?: string;
  amount?: string;
  status?: string;
  reason?: string | null;
  user_email?: string;
  created_at?: number;
  updated_at?: number;
}

/** 서버 시각은 epoch ms 숫자다. 문자열로 오면 숫자로 눕혀 정렬 축을 하나로 유지한다 */
function toAdminTransfer(w: AdminTransferWire): AdminTransfer {
  const at = (v: unknown) => (typeof v === "number" ? v : Number(v ?? 0) || 0);
  return {
    externalId: w.external_id ?? "",
    direction: w.direction === "DEPOSIT" ? "DEPOSIT" : "WITHDRAW",
    asset: w.asset ?? "KRW",
    amount: w.amount ?? "0",
    status: (w.status as AdminTransfer["status"]) ?? "REQUESTED",
    reason: w.reason ?? null,
    // 사용자 응답에는 없는 필드다. 목록 밖 경로(승인·거절 응답)에서는 비어 온다
    userEmail: w.user_email ?? "",
    createdAt: at(w.created_at),
    updatedAt: at(w.updated_at),
  };
}

/* ===== 관리 읽기 엔벌로프 와이어 스키마(snake_case 원본). 언랩·매핑 대상 ===== */

interface WireDataEnvelope<T> {
  data?: T[];
}
/** GET /admin/v1/ha/status. lease·워터마크만 제공(노드 토폴로지 없음) */
interface WireHaStatus {
  lease?: { holder?: string; epoch?: number; lease_until?: string; leader_alive?: boolean };
  command_watermark?: number;
  event_watermark?: number;
  trades_count?: number;
}
/** 용량 콘솔 응답. 서버가 못 읽은 지표는 null로 오고 화면이 빈칸으로 낸다 */
interface WireCapacityNode {
  id?: string;
  az?: string;
  instance_type?: string;
  engine?: boolean;
  scalable?: boolean;
  expected?: boolean;
  reachable?: boolean;
  leader?: boolean;
  serves_reads?: boolean;
  cpu_ratio?: number | null;
  cpu_count?: number | null;
  heap_used_bytes?: number | null;
  heap_max_bytes?: number | null;
  http_requests?: number | null;
  ws_clients?: number | null;
}
interface WireCapacity {
  tier?: string;
  plan?: { min?: number; desired?: number; max?: number; updated_by?: string; updated_at?: string };
  observed_total?: number;
  reconciled?: boolean;
  read_avg_cpu_ratio?: number | null;
  nodes?: WireCapacityNode[];
  advice?: { id?: string; level?: string; headline?: string; detail?: string; action?: string | null }[];
}
interface WireRecoveryRun {
  market_id?: number;
  replayed_from_seq?: number;
  replayed_to_seq?: number;
  fp_replay?: string;
  fp_live?: string;
  matched?: boolean | string;
  duration_ms?: number | string;
  created_at?: string;
}
/** API§6-C 스키마 1.2. 판정 셋은 PASS|FAIL|UNKNOWN이다 */
interface WireVerdict {
  scope?: string;
  replay_deterministic?: string;
  failover_ok?: string;
  overall?: string;
}
interface WireFailoverRun {
  experiment_id?: string;
  rto_seconds?: string;
  downtime_seconds?: string;
  rpo_records?: number | string;
  verdict?: string;
  created_at?: string;
}
interface WireRecovery {
  verdict?: WireVerdict;
  recovery_runs?: WireRecoveryRun[];
  failover_runs?: WireFailoverRun[];
}
interface WireInvariant {
  check_name?: string;
  ok?: boolean;
  violated_rows?: number;
  checked_at?: string;
}
interface WireInvariants {
  checks?: WireInvariant[];
}
/** GET /admin/v1/config/markets data 원소. status는 ACTIVE·HALTED·DELISTED */
interface WireConfigMarket {
  market?: string;
  status?: string;
  maker_fee?: string;
  taker_fee?: string;
  /* 주문 규칙(§2 v2.35) */
  tick_size?: string;
  min_notional?: string;
}
/** GET /admin/v1/config/notices data 원소(active 포함). body·published_at·severity 포함 */
interface WireNotice {
  id?: number | string;
  title?: string;
  body?: string;
  severity?: string;
  active?: boolean;
  published_at?: string;
  published_by?: string;
}
interface WireOperator {
  operator_id?: string;
  display_name?: string;
  status?: string;
  roles?: string[];
}
interface WireAudit {
  id?: number | string;
  operator_id?: string;
  display_name?: string;
  action?: string;
  target?: string;
  result?: string;
  approval_id?: number | string;
  created_at?: string;
  request_id?: string;
}
const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

/** 서버 판정 문자열을 좁힌다. 미지값은 null이고 그것을 통과로 읽지 않는다. */
function verdictOf(v: string | undefined): RecoveryVerdict | null {
  return v === "PASS" || v === "FAIL" || v === "UNKNOWN" ? v : null;
}

/**
 * dev 전용 운영자 CN 목록. 값이 있으면 로컬 서버가 mTLS 종단 게이트웨이 토폴로지
 * (exchange.admin.mtls.trusted-forwarding=true)로 떠 있다는 뜻이고, 그 게이트웨이가 없으므로
 * 브라우저가 X-Operator-Cn 을 직접 싣는다.
 *
 * 이 값이 비면 운영 경로다. 헤더를 싣지 않고 신원은 TLS 핸드셰이크의 클라이언트 인증서에서만
 * 나온다. 즉 이 상수가 비어 있는 빌드에는 헤더 신원 경로가 존재하지 않는다.
 * 서버 쪽 짝은 두 겹으로 막혀 있다. 설정이 on-profile: dev 아래에 있고,
 * StartupSecretValidator 가 운영 기동에서 trusted-forwarding=true 를 보면 기동을 중단한다.
 */
const DEV_OPERATOR_CNS: string[] = (process.env.NEXT_PUBLIC_ADMIN_DEV_CN ?? "")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

export class HttpAdminApi implements AdminApi {
  // 반영 여부는 응답 applied와 status 1.2의 running·paused가 원천이다
  readonly controlApplied = true;

  private readonly base: string;
  private session: AdminSession | null = null;
  private listeners = new Set<(s: AdminSession | null) => void>();
  /** 헤더로 실을 운영자 CN. dev 게이트웨이 모드에서만 채워진다 */
  private forwardCn: string | null = null;

  constructor(base: string) {
    this.base = base.replace(/\/+$/, "");
  }

  private async http<T>(path: string, init: RequestInit = {}, authorization?: string): Promise<T> {
    const headers: Record<string, string> = {
      ...(init.body != null ? JSON_HEADERS : {}),
      ...(this.session ? { Authorization: `Bearer ${this.session.token}` } : {}),
      ...(authorization ? { Authorization: authorization } : {}),
      // 세션 발급 후에도 매 요청에 싣는다. AdminAuthFilter 가 세션 operator 를 현재 mTLS
      // 신원과 재대조하므로(관리자 설계 §3-6 4단계) 발급 때만 실으면 다음 요청이 401 이다
      ...(this.forwardCn ? { "X-Operator-Cn": this.forwardCn } : {}),
    };
    // mTLS 클라이언트 인증은 브라우저가 TLS 핸드셰이크에서 처리한다
    const res = await fetch(`${this.base}${path}`, { ...init, headers, credentials: "include" });
    if (!res.ok) {
      const rateLimited = res.status === 429;
      let code = rateLimited
        ? "too_many_requests"
        : res.status === 401
          ? "jwt_verification"
          : res.status === 403
            ? "admin_forbidden"
            : "server_error";
      let retryAfter: number | null = null;
      let message = rateLimited ? rateLimitMessage(null) : "관리 요청 실패";
      try {
        const body = (await res.json()) as ErrorEnvelope;
        if (body.error?.name) code = body.error.name;
        retryAfter = body.error?.retry_after ?? null;
        if (body.error?.message) message = body.error.message;
      } catch {
        // 비 JSON 응답은 상태코드 기반 기본값 유지
      }
      if (retryAfter === null) {
        // 바디 retry_after 부재 시 HTTP Retry-After 헤더를 폴백으로 읽는다. 초 단위만 취하고
        // HTTP-date 형식이면 Number가 NaN이라 null 유지(안내는 "잠시 후"로 열화)
        const header = res.headers?.get("Retry-After");
        const sec = header != null ? Number(header) : Number.NaN;
        if (Number.isFinite(sec) && sec >= 0) retryAfter = sec;
      }
      if (rateLimited && (code === "too_many_requests" || code === "rate_limited")) {
        message = rateLimitMessage(retryAfter);
      }
      throw new AdminError(code, message, retryAfter);
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  private opId(): string {
    const s = this.getSession();
    if (!s) throw new AdminError("jwt_verification", "관리자 세션 없음");
    return s.operatorId;
  }

  // sim 제어·rate는 operator_id가 감사 편의 필드이고 권위는 Bearer 세션이다(API §3-6, v2.4). 세션 부재는
  // 전송 계층에서 401 jwt_verification으로 표면화하므로 바디 구성 단계에서 선제 throw하지 않는다.
  private sessionOpId(): string {
    return this.getSession()?.operatorId ?? "";
  }

  listDemoCerts(): DemoClientCert[] {
    // 운영 mTLS는 OS·브라우저 인증서 저장소를 쓴다. 앱이 인증서를 열거하지 않는다.
    // dev 게이트웨이 모드에서만 선택지를 세운다. 신원을 하나로 고정하면 운영자별 역할 차이와
    // 다른 운영자가 발급하는 경로를 화면에서 검증할 수 없다
    return DEV_OPERATOR_CNS.map((cn) => ({
      certId: cn,
      subjectCn: `CN=${cn}`,
      issuer: "dev 전달 헤더 (미검증 신원)",
      operatorId: cn,
    }));
  }

  async establishSession(certId?: string, password?: string): Promise<AdminSession> {
    let authorization: string | undefined;
    if (password !== undefined) {
      this.endSession();
      const origin = typeof window !== "undefined" ? window.location.origin : undefined;
      const protocol = this.base ? new URL(this.base, origin).protocol : origin ? new URL(origin).protocol : "";
      if (protocol !== "https:") {
        throw new AdminError("invalid_parameter", "운영자 로그인은 HTTPS 전용");
      }
      if (!certId?.trim() || certId.includes(":") || !password) {
        throw new AdminError("invalid_parameter", "운영자 아이디 또는 비밀번호 오류");
      }
      // 비밀번호는 HTTPS 로그인 요청 한 번에만 쓰고 세션 객체에 보관하지 않는다.
      const bytes = new TextEncoder().encode(`${certId.trim()}:${password}`);
      authorization = `Basic ${btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""))}`;
    }
    // dev 게이트웨이 모드는 고른 CN을 신원으로 싣는다. 운영은 인자를 무시하고 TLS 신원만 쓴다
    this.forwardCn = password === undefined && DEV_OPERATOR_CNS.includes(certId ?? "") ? (certId ?? null) : null;
    // 서버 세션 응답은 snake_case. camelCase 도메인으로 명시 매핑한다. issued_at은 수신 시각으로 파생.
    const raw = await this.http<WireSession>("/admin/v1/session", { method: "POST" }, authorization);
    const s: AdminSession = {
      operatorId: raw.operator_id ?? "",
      displayName: raw.display_name ?? "",
      roles: raw.roles ?? [],
      token: raw.token ?? "",
      issuedAt: raw.issued_at ?? new Date().toISOString(),
      expiresAt: raw.expires_at ?? new Date().toISOString(),
    };
    this.session = s;
    this.emit();
    return s;
  }

  getSession(): AdminSession | null {
    // expiresAt 파싱 실패(NaN)면 만료 판정을 건너뛴다. 손상 값이 세션을 영구 유지시키는 것을 막고
    // 유효 값일 때만 클라 만료를 발동한다.
    if (this.session) {
      const exp = new Date(this.session.expiresAt).getTime();
      if (Number.isFinite(exp) && Date.now() > exp) {
        this.session = null;
        this.emit();
      }
    }
    return this.session;
  }

  endSession(): void {
    this.session = null;
    this.forwardCn = null;
    this.emit();
  }

  onSessionChange(cb: (s: AdminSession | null) => void): () => void {
    this.listeners.add(cb);
    cb(this.getSession());
    return () => this.listeners.delete(cb);
  }

  private emit(): void {
    for (const cb of this.listeners) cb(this.session);
  }

  /* ===== 승인 티켓: 발급형(§3-6) ===== */

  async issueApproval(requesterId: string, action: DestructiveAction, payload: ApprovalPayload): Promise<IssuedApproval> {
    const approver = this.getSession();
    if (!approver) throw new AdminError("jwt_verification", "관리자 세션 없음");
    const bind = wireBinding(payload);
    const { key, label } = approvalConsoleTarget(payload);
    // 발급 주체는 현재 mTLS 세션이다. 1인 승인이라 요청 주체와 같아도 서버가 받는다(ADR 0019)
    const res = await this.http<WireIssue>("/admin/v1/approvals", {
      method: "POST",
      body: JSON.stringify({ requester_operator_id: requesterId, action: bind.action, target: bind.target }),
    });
    return {
      id: String(res.id ?? res.approval_id ?? ""),
      action,
      targetKey: key,
      targetLabel: label,
      requesterId,
      // 원장 조회로 표시명을 채운다. 발급 응답에는 표시명이 없어 id를 잠정 표기한다
      requesterName: requesterId,
      approverId: approver.operatorId,
      approverName: approver.displayName,
      state: "ISSUED",
      issuedAt: res.issued_at ?? isoNow(),
      expiresAt: res.expires_at ?? isoNow(),
      consumedAt: null,
      payload,
      token: res.approval_token ?? "",
    };
  }

  async listApprovals(state?: ApprovalState, opts: PageOpts = {}): Promise<AdminPage<ApprovalRequest>> {
    const res = await this.http<PagedEnvelope<WireApproval>>(
      `/admin/v1/approvals${pageQuery(opts, state ? { state } : {})}`,
    );
    const rows = (res.data ?? []).map((a, i) => {
      const wa = a.action ?? "";
      const target = a.target ?? "";
      // 서버 원장은 소비 시점에만 EXPIRED로 전이하고 list에 만료 스윕이 없다. TTL 경과한
      // ISSUED를 그대로 두면 실행 대기·대기 카운트가 유지되다 실행 시점에야 403이 난다.
      // sim 스윕과 표면을 맞춰 표시 계층에서 EXPIRED로 파생한다(원장 상태는 서버 소유 유지)
      const rawState = normApprovalState(a.state);
      const expMs = a.expires_at ? Date.parse(a.expires_at) : Number.NaN;
      const derivedState =
        rawState === "ISSUED" && Number.isFinite(expMs) && expMs < Date.now() ? "EXPIRED" : rawState;
      return {
        // 원장 프로젝션 키는 approval_id다(발급 응답과 대칭). 합성 apr-N는 키 부재 시 최후 폴백
        id: String(a.approval_id ?? a.id ?? `apr-${i}`),
        action: consoleAction(wa),
        targetKey: `${wa}:${target}`,
        targetLabel: target,
        requesterId: a.requester_operator_id ?? "",
        requesterName: a.requester_operator_id ?? "",
        // 발급형이라 원장 최초 상태부터 approver가 존재한다(발급=승인)
        approverId: a.approver_operator_id ?? "",
        approverName: a.approver_operator_id ?? "",
        state: derivedState,
        issuedAt: a.issued_at ?? "",
        expiresAt: a.expires_at ?? "",
        consumedAt: a.consumed_at ?? null,
      };
    });
    return toPage(rows, res);
  }

  async rejectApproval(approvalId: string): Promise<ApprovalRequest> {
    await this.http<void>(`/admin/v1/approvals/${encodeURIComponent(approvalId)}/reject`, {
      method: "POST",
      body: JSON.stringify({ operator_id: this.opId() }),
    });
    // 서버는 200과 {approval_id, state}를 주지만 호출부가 재조회로 원장을 갱신하므로 응답을 버리고 최소 프로젝션을 반환한다
    return {
      id: approvalId,
      action: "sim.bots.params",
      targetKey: "",
      targetLabel: "",
      requesterId: "",
      requesterName: "",
      approverId: "",
      approverName: "",
      state: "REJECTED",
      issuedAt: "",
      expiresAt: "",
      consumedAt: null,
    };
  }

  async consumeApproval(approval: IssuedApproval): Promise<void> {
    // 요청 운영자(현재 세션)가 발급 토큰을 파괴 액션 바디에 실어 소각한다(§3-6)
    return this.perform(approval.payload, approval.token);
  }

  /** token 이 null 이면 approval_token 을 싣지 않는다. 그 외 바디는 승인 경로와 같다 */
  private async perform(p: ApprovalPayload, token: string | null): Promise<void> {
    const operatorId = this.opId();
    // 키 자체를 빼야 한다. approval_token: null 은 서버 파싱에서 빈 문자열과 같지 않다
    const approval: Record<string, string> = token ? { approval_token: token } : {};
    switch (p.action) {
      case "sim.bots.params":
        await this.http<void>(`/admin/v1/sim/bots/${encodeURIComponent(p.botId)}/params`, {
          method: "PATCH",
          body: JSON.stringify({ operator_id: operatorId, ...approval, params: p.params }),
        });
        return;
      case "config.market.halt":
        await this.http<void>(`/admin/v1/config/markets/${encodeURIComponent(p.symbol)}/halt`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, ...approval }),
        });
        return;
      case "config.market.resume":
        await this.http<void>(`/admin/v1/config/markets/${encodeURIComponent(p.symbol)}/resume`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, ...approval }),
        });
        return;
      case "config.market.fees":
        await this.http<void>(`/admin/v1/config/markets/${encodeURIComponent(p.symbol)}/fees`, {
          method: "PATCH",
          body: JSON.stringify({ operator_id: operatorId, ...approval, maker_fee: p.makerFee, taker_fee: p.takerFee }),
        });
        return;
      case "operators.create":
        await this.http<void>("/admin/v1/operators", {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, ...approval, new_operator_id: p.operatorId, display_name: p.displayName }),
        });
        return;
      case "operators.status":
        await this.http<void>(`/admin/v1/operators/${encodeURIComponent(p.operatorId)}/status`, {
          method: "PATCH",
          body: JSON.stringify({ operator_id: operatorId, ...approval, status: p.status }),
        });
        return;
      case "operators.roles":
        // 부여·회수 단건 모델(정본 API). 백엔드는 {action: GRANT|REVOKE, role}을 읽는다
        await this.http<void>(`/admin/v1/operators/${encodeURIComponent(p.operatorId)}/roles`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, ...approval, action: p.grant, role: p.role }),
        });
        return;
      case "members.close":
        await this.http<void>(`/admin/v1/members/${encodeURIComponent(p.externalId)}/close`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, ...approval }),
        });
        return;
    }
  }

  async getOverview(): Promise<AdminOverview> {
    // 종합 요약은 개별 모니터링 엔드포인트를 합성한다(전용 엔드포인트 없음).
    const [ha, inv, sim, markets, rec, pending] = await Promise.all([
      this.getHaStatus(),
      this.getInvariants(),
      this.getSimStatus(),
      this.getConfigMarkets(),
      this.getRecovery(),
      // 종합 상태의 대기 건수는 첫 쪽 기준이다. 500건을 넘겨 대기 중인 승인은 이미
      // 종합 카드가 아니라 승인 화면에서 다뤄야 할 상태다
      this.listApprovals("ISSUED").then((r) => r.data.length),
    ]);
    const invariants = summarizeInvariants(inv);
    // 위반은 danger, 미수집·부분수집은 warning이다. 미판정을 통과로 칠하지 않는다
    const health: "success" | "warning" | "danger" =
      ha.nodes.some((n) => n.health === "down") || invariants.state === "VIOLATED"
        ? "danger"
        : ha.nodes.some((n) => n.health === "degraded") || invariants.state !== "PASS"
          ? "warning"
          : "success";
    return {
      overallHealth: health,
      epoch: ha.epoch,
      leaderNodeId: ha.leaderNodeId,
      invariants,
      lastRtoMs: rec.lastRtoMs,
      lastVerdict: rec.lastVerdict,
      botsActive: sim.activeBots,
      currentOps: sim.currentOps,
      pendingApprovals: pending,
      suspendedMarkets: markets.filter((m) => m.state === "HALTED").length,
      generatedAt: ha.generatedAt,
    };
  }

  /* ===== 용량(ADR 0015) ===== */

  async getCapacity(): Promise<CapacityState> {
    return mapCapacity(await this.http<WireCapacity>("/admin/v1/capacity"));
  }

  async scaleOut(reason?: string): Promise<CapacityState> {
    return mapCapacity(await this.http<WireCapacity>("/admin/v1/capacity/scale-out", {
      method: "POST",
      body: JSON.stringify({ reason: reason ?? "" }),
    }));
  }

  async scaleIn(reason?: string): Promise<CapacityState> {
    return mapCapacity(await this.http<WireCapacity>("/admin/v1/capacity/scale-in", {
      method: "POST",
      body: JSON.stringify({ reason: reason ?? "" }),
    }));
  }

  async getHaStatus(): Promise<HaStatus> {
    // 서버 status는 leader_lease와 워터마크만 준다. 노드 토폴로지·시퀀서 큐·지연·Hikari는
    // 미노출이라 매칭엔진 리더를 단일 노드로 합성하고, 미노출 지표는 안전 기본값으로 좁힌다.
    const res = await this.http<WireHaStatus>("/admin/v1/ha/status");
    const lease = res.lease ?? {};
    const epoch = num(lease.epoch);
    const seq = num(res.command_watermark);
    const trades = num(res.trades_count);
    const holder = lease.holder ?? "";
    const node: HaNode = {
      id: holder || "matching-engine",
      role: "leader",
      epoch,
      seq,
      followerLagMs: 0,
      ownedMarkets: [],
      health: lease.leader_alive === true ? "healthy" : "down",
    };
    return {
      epoch: node.epoch,
      leaderNodeId: holder,
      nodes: [node],
      tradesTotal: trades,
      generatedAt: isoNow(),
    };
  }

  async getRecovery(): Promise<HaRecovery> {
    // 서버는 결정적 재현(recovery_runs)과 장애조치 드릴(failover_runs)을 분리한다. 복구 표는
    // recovery_runs를 행으로 매핑하고, RTO 수치는 failover_runs 최신 1건을 쓴다. 판정은 다르다.
    // 서버 verdict 객체가 표 전체 집계라 그것을 읽는다(아래).
    const res = await this.http<WireRecovery>("/admin/v1/ha/recovery");
    const runs: RecoveryRun[] = (res.recovery_runs ?? []).map((r, i) => {
      const matched = r.matched === true;
      return {
        id: `rec-${r.created_at ?? ""}-${i}`,
        startedAt: r.created_at ?? "",
        durationMs: num(r.duration_ms),
        // RPO 원천은 harness.failover_runs.rpo_records다. 없는 값을 0으로 채우면 화면이 유실
        // 없음을 단정한다. RPO=0은 수용 기준의 헤드라인 주장이라 더 나쁘다.
        rpoMs: null,
        replayedFromSeq: num(r.replayed_from_seq),
        replayedToSeq: num(r.replayed_to_seq),
        // 서버가 스키마 1.2부터 낸다. 재생 0건과 실재 재생을 이 값으로 가른다(fp에 verified_events가 실린다)
        fpReplay: r.fp_replay ?? "",
        fpLive: r.fp_live ?? "",
        matched,
        verdict: matched ? "PASS" : "FAIL",
      };
    });
    const drill = (res.failover_runs ?? [])[0];
    const drillRto = drill ? Number(drill.rto_seconds) : Number.NaN;
    // failover_runs가 비면 미측정이다. recovery_runs.duration_ms로 폴백하면 재생 소요가
    // "최근 복구 시간(RTO)"으로 둔갑한다. 두 축은 다른 것을 잰다.
    const lastRtoMs = drill && Number.isFinite(drillRto) ? Math.round(drillRto * 1000) : null;
    // 판정은 서버 verdict를 쓴다. 배열은 limit로 자른 창이라 그것으로 결론을 내면 창 밖 위반이 안 보인다.
    const v = res.verdict;
    // 스키마 1.0 서버는 verdict가 없다. 그때만 폴백 경로로 떨어진다.
    const legacy = drill?.verdict === "PASS" || drill?.verdict === "FAIL" ? drill.verdict : null;
    return {
      runs,
      lastRtoMs,
      lastVerdict: verdictOf(v?.overall) ?? legacy ?? runs[0]?.verdict ?? null,
      verdictScope: v?.scope === "since" ? "since" : "all",
      replayVerdict: verdictOf(v?.replay_deterministic),
      failoverVerdict: verdictOf(v?.failover_ok),
    };
  }

  async getInvariants(): Promise<InvariantCheck[]> {
    // 서버는 {checks:[...]} 엔벌로프. 라벨은 미제공이라 정적 정의(INVARIANT_DEFS)로 조인한다.
    const res = await this.http<WireInvariants>("/admin/v1/ha/invariants");
    return (res.checks ?? []).map((c) => {
      const name = c.check_name ?? "";
      const ok = c.ok === true;
      const rows = num(c.violated_rows);
      return {
        name,
        label: INVARIANT_LABEL.get(name) ?? name,
        ok,
        violatedRows: rows,
        detail: ok ? "정상" : `위반 ${rows}행`,
        checkedAt: c.checked_at ?? "",
      };
    });
  }

  async getSimStatus(): Promise<SimStatus> {
    // 없는 값을 0으로 채우지 않는다. 서버가 안 낸 이유가 모르기 때문이고, 0을 채우면
    // 정지·0대·성공률 100%가 측정값처럼 화면에 뜬다(status 1.3 source, §6-A).
    const raw = await this.http<WireSimStatus>("/admin/v1/sim/status");
    // 구 서버(1.2 이하)는 source가 없다. 거버너 부재 배포에서 running·markets를 함께
    // 내지 않으므로 그때는 unknown으로 읽는다.
    const source: SimStatusSource =
      raw.source ?? (raw.running === undefined && raw.markets === undefined ? "unknown" : "local");
    const markets = source === "unknown" || raw.markets === undefined
      ? null
      : this.mapMarkets(raw.markets);
    let activeBots: number | null = null;
    let botCount: number | null = null;
    if (markets) {
      activeBots = 0;
      botCount = 0;
      for (const m of markets) {
        for (const type of Object.keys(m.scale) as (keyof MarketSimState["scale"])[]) {
          const e = m.scale[type];
          if (!e) continue;
          activeBots += e.active;
          botCount += e.target;
        }
      }
    }
    // unknown 에서 target_ops 를 실은 구 서버 응답도 0 으로 눕히지 않는다.
    const rate =
      source === "unknown" || raw.target_ops === undefined ? null : num(raw.target_ops);
    return {
      source,
      observedAt: raw.observed_at ?? null,
      stale: raw.stale === true,
      running: raw.running ?? null,
      paused: raw.paused ?? null,
      rate,
      currentOps: rate,
      botCount,
      activeBots,
      // 서버 status가 내지 않는 세 축이다. 상수를 채우면 성공률 100%·지연 0ms·중복체결
      // 0건이 실측처럼 보인다. 원천이 생기기 전까지 미측정으로 둔다
      successRate: null,
      avgLatencyMs: null,
      duplicateFillTotal: null,
      // 서버 status에 개별 봇 목록은 없다(생성·파라미터는 별도 경로). 빈 목록으로 정직하게 둔다
      bots: [],
      markets,
      tradeFlash: raw.trade_flash ?? "normal",
    };
  }

  private mapMarkets(rows: WireMarketState[] | undefined): MarketSimState[] {
    if (!Array.isArray(rows)) return [];
    return rows.map((r) => {
      const scale: MarketSimState["scale"] = {};
      for (const [key, entry] of Object.entries(r.scale ?? {})) {
        scale[key as keyof MarketSimState["scale"]] = {
          target: num(entry.target),
          active: num(entry.active),
        };
      }
      return {
        market: r.market,
        targetNetBias: r.target_net_bias ?? "0.00",
        measuredNetBias: r.measured_net_bias ?? "0.00",
        effectiveBias: r.effective_bias ?? "0.00",
        autoBalance: { enabled: r.auto_balance?.enabled ?? false, strength: r.auto_balance?.strength ?? "0.00" },
        scale,
        fillRatio: r.fill_ratio ?? "0.00",
        tradeFlash: r.trade_flash ?? "normal",
      };
    });
  }

  async setFleetScale(market: string, targets: FleetScaleTargets): Promise<FleetScaleResult> {
    const res = await this.http<{ market: string; applied?: FleetScaleTargets; clamped?: boolean; fleet_present?: boolean }>(
      "/admin/v1/sim/scale",
      { method: "PUT", body: JSON.stringify({ market, targets }) },
    );
    return {
      market: res.market,
      applied: res.applied ?? {},
      clamped: res.clamped === true,
      fleetPresent: res.fleet_present === true,
    };
  }

  async setDirectionBias(market: string, targetNetBias: string, autoBalance: boolean, strength: string): Promise<DirectionBiasResult> {
    const res = await this.http<{
      market: string;
      target_net_bias?: string;
      auto_balance?: boolean;
      strength?: string;
      effective_bias?: string;
      fleet_present?: boolean;
    }>("/admin/v1/sim/bias", {
      method: "PUT",
      body: JSON.stringify({ market, target_net_bias: targetNetBias, auto_balance: autoBalance, strength }),
    });
    return {
      market: res.market,
      targetNetBias: res.target_net_bias ?? targetNetBias,
      autoBalance: res.auto_balance === true,
      strength: res.strength ?? strength,
      effectiveBias: res.effective_bias ?? "0.00",
      fleetPresent: res.fleet_present === true,
    };
  }

  /**
   * 프리셋 활성화(§6-A v2.31). operator_id는 감사 편의 필드이고 권위는 Bearer 세션이다.
   * fleet_present:false는 성공 응답이지만 미반영이라 control과 같은 규약으로 에러 승격한다.
   */
  async activateScenario(name: ScenarioName): Promise<ScenarioResult> {
    const res = await this.http<{
      scenario?: string;
      apply_path?: string;
      queued_command_id?: number;
      target_ops?: string;
      base_ops?: string;
      auto_balance?: boolean;
      target_net_bias?: string;
      markets?: { market: string; scale_applied?: boolean; applied?: FleetScaleTargets; effective_bias?: string }[];
      clamped?: boolean;
      fleet_present?: boolean;
    }>(`/admin/v1/sim/scenarios/${encodeURIComponent(name)}/activate`, {
      method: "POST",
      body: JSON.stringify({ operator_id: this.sessionOpId() }),
    });
    // apply_path=queued는 성공이다. 서버가 sim_commands에 의도를 적재하고 봇이 1초 안에 집어
    // 적용한다(API §6-A). fleet_present:false만 보고 예외를 던지면 적용된 조작이 오류로 보인다.
    const path = res?.apply_path === "queued" ? "queued" : "local";
    if (res && res.fleet_present === false && res.apply_path === undefined) {
      throw new AdminError("control_not_applied", "봇 플릿 부재로 반영되지 않음. 서버 프로파일 확인 필요");
    }
    return {
      scenario: (res.scenario as ScenarioName) ?? name,
      applyPath: path,
      queuedCommandId: res.queued_command_id ?? 0,
      // 없는 값을 0으로 채우지 않는다. 서버가 안 낸 이유가 모르기 때문이고,
      // 0을 채우면 화면이 실제와 다른 수를 단정한다.
      targetOps: res.target_ops ?? null,
      baseOps: res.base_ops ?? null,
      autoBalance: res.auto_balance === true,
      targetNetBias: res.target_net_bias ?? "0.00",
      markets: res.markets
        ? res.markets.map((m) => ({
            market: m.market,
            scaleApplied: m.scale_applied === true,
            applied: m.applied ?? {},
            effectiveBias: m.effective_bias ?? "0.00",
          }))
        : null,
      clamped: res.clamped ?? null,
      fleetPresent: res.fleet_present === true,
    };
  }

  async setEffectConfig(tradeFlash: TradeFlash): Promise<TradeFlash> {
    const res = await this.http<{ trade_flash?: TradeFlash }>("/admin/v1/sim/effects", {
      method: "PUT",
      body: JSON.stringify({ trade_flash: tradeFlash }),
    });
    return res.trade_flash ?? tradeFlash;
  }

  /** control 1.1의 applied:false(플릿 빈 부재 등)는 성공 응답이지만 미반영이다.
   * 삼키면 콘솔이 반영을 단정하므로 에러로 승격해 화면 에러 토스트를 태운다 */
  private async simControl(command: "start" | "stop" | "pause"): Promise<void> {
    const res = await this.http<{ applied?: boolean }>(`/admin/v1/sim/${command}`, {
      method: "POST",
      body: JSON.stringify({ operator_id: this.sessionOpId() }),
    });
    if (res && res.applied === false) {
      throw new AdminError("control_not_applied", "봇 플릿 부재로 반영되지 않음. 서버 프로파일 확인 필요");
    }
  }

  simStart(): Promise<void> {
    return this.simControl("start");
  }

  simStop(): Promise<void> {
    return this.simControl("stop");
  }

  simPause(): Promise<void> {
    return this.simControl("pause");
  }

  async simSetRate(rate: number): Promise<ControlApply> {
    // 백엔드 RateBody 키는 target_ops이며 문자열로 파싱한다(NUMERIC 정밀도 계약).
    // 응답을 버리지 않는다. 큐 경로에서는 완료 단정이 최대 1초 이른 거짓이 된다.
    const res = await this.http<{ apply_path?: string; queued_command_id?: number }>(
      "/admin/v1/sim/rate",
      {
        method: "PUT",
        body: JSON.stringify({ operator_id: this.sessionOpId(), target_ops: String(rate) }),
      },
    );
    return {
      applyPath: res?.apply_path === "queued" ? "queued" : "local",
      queuedCommandId: res?.queued_command_id ?? 0,
    };
  }

  async createBot(type: BotType, market: string, count = 1): Promise<CreateBotResult> {
    // governor set-point 상향(§6-A). 응답은 created·applied_target·fleet_total·clamped·fleet_present
    const res = await this.http<WireCreateBot>("/admin/v1/sim/bots", {
      method: "POST",
      body: JSON.stringify({ operator_id: this.opId(), type, count: Math.max(0, Math.round(count)), market }),
    });
    return {
      created: num(res.created),
      appliedTarget: num(res.applied_target),
      fleetTotal: num(res.fleet_total),
      clamped: res.clamped === true,
      fleetPresent: res.fleet_present === true,
    };
  }

  deleteBot(botId: string): Promise<void> {
    return this.http<void>(`/admin/v1/sim/bots/${encodeURIComponent(botId)}`, {
      method: "DELETE",
      body: JSON.stringify({ operator_id: this.opId() }),
    });
  }

  async getConfigMarkets(): Promise<ConfigMarket[]> {
    // 서버는 {data:[{market,status,maker_fee,taker_fee}]}. status는 ACTIVE·HALTED·DELISTED 3분.
    // 한글명은 서버 미제공이라 심볼로 조인한다.
    const res = await this.http<WireDataEnvelope<WireConfigMarket>>("/admin/v1/config/markets");
    return (res.data ?? []).map((m) => {
      const symbol = m.market ?? "";
      return {
        symbol,
        koreanName: COIN_NAME.get(symbol) ?? symbol,
        state: normMarketState(m.status),
        makerFee: m.maker_fee ?? "0",
        takerFee: m.taker_fee ?? "0",
        tickSize: m.tick_size ?? "0",
        minNotional: m.min_notional ?? "0",
      };
    });
  }

  async getNotices(opts: { cursor?: string | null; limit?: number } = {}): Promise<AdminPage<Notice>> {
    // 서버는 {data:[...], pagination:{...}} 엔벌로프(active 포함).
    const q = new URLSearchParams({ limit: String(opts.limit ?? 100) });
    if (opts.cursor) q.set("cursor", opts.cursor);
    const res = await this.http<
      WireDataEnvelope<WireNotice> & { pagination?: { next_cursor?: string | null; has_more?: boolean } }
    >(`/admin/v1/config/notices?${q}`);
    const rows = (res.data ?? []).map((n) => ({
      id: String(n.id ?? ""),
      title: n.title ?? "",
      body: n.body ?? "",
      severity: normSeverity(n.severity),
      active: n.active !== false,
      createdAt: n.published_at ?? "",
      createdBy: n.published_by ?? "",
    }));
    return {
      data: rows,
      nextCursor: res.pagination?.next_cursor ?? null,
      hasMore: !!res.pagination?.has_more,
    };
  }

  /* ===== 문의 답변 큐 (§4-G) ===== */

  async getInquiries(status: "OPEN" | "ANSWERED" = "OPEN",
                     opts: PageOpts = {}): Promise<AdminPage<AdminInquiry>> {
    const res = await this.http<PagedEnvelope<AdminInquiryWire>>(
      `/admin/v1/support${pageQuery(opts, { status })}`,
    );
    return toPage((res.data ?? []).map(toAdminInquiry), res);
  }

  async answerInquiry(externalId: string, answer: string): Promise<AdminInquiry> {
    const res = await this.http<AdminInquiryWire>(
      `/admin/v1/support/${encodeURIComponent(externalId)}/answer`,
      { method: "POST", body: JSON.stringify({ answer }) },
    );
    return toAdminInquiry(res);
  }

  /* ===== 회원 운영 (§6-E) ===== */

  async getMembers(status?: MemberStatus, opts: PageOpts = {}): Promise<AdminPage<AdminMember>> {
    const extra: Record<string, string> = status ? { status } : {};
    const res = await this.http<PagedEnvelope<WireMember>>(
      `/admin/v1/members${pageQuery(opts, extra)}`,
    );
    return toPage((res.data ?? []).map(toMember), res);
  }

  async getMember(externalId: string): Promise<AdminMemberDetail> {
    const res = await this.http<WireMember & { updated_at?: string }>(
      `/admin/v1/members/${encodeURIComponent(externalId)}`,
    );
    // 활동 요약 3필드는 toMember가 옮긴다. 상세와 목록의 계약이 같아졌다
    return { ...toMember(res), updatedAt: Date.parse(res.updated_at ?? "") || 0 };
  }

  suspendMember(externalId: string): Promise<MemberTransition> {
    return this.transitionMember(externalId, "suspend");
  }

  reinstateMember(externalId: string): Promise<MemberTransition> {
    return this.transitionMember(externalId, "reinstate");
  }

  private async transitionMember(externalId: string, verb: string): Promise<MemberTransition> {
    const res = await this.http<{
      external_id?: string; status?: string;
      canceled_orders?: number | string; revoked_sessions?: number | string;
    }>(`/admin/v1/members/${encodeURIComponent(externalId)}/${verb}`, {
      method: "POST",
      body: JSON.stringify({ operator_id: this.sessionOpId() }),
    });
    return {
      externalId: res.external_id ?? externalId,
      status: normMemberStatus(res.status),
      canceledOrders: num(res.canceled_orders),
      revokedSessions: num(res.revoked_sessions),
    };
  }

  /* ===== 커뮤니티 운영 큐 (§6-D) ===== */

  async getCommunityReports(status: ReportStatus = "PENDING",
                            opts: PageOpts = {}): Promise<AdminPage<CommunityReport>> {
    const res = await this.http<PagedEnvelope<WireReport>>(
      `/admin/v1/community/reports${pageQuery(opts, { status })}`,
    );
    return toPage((res.data ?? []).map(toCommunityReport), res);
  }

  async resolveCommunityReport(reportId: number, resolution: ReportResolution,
                               note?: string): Promise<void> {
    await this.http<unknown>(
      `/admin/v1/community/reports/${encodeURIComponent(String(reportId))}/resolve`,
      { method: "POST", body: JSON.stringify({ resolution, note: note ?? null }) },
    );
  }

  async moderateCommunityTarget(targetType: "POST" | "COMMENT", externalId: string,
                                action: ModerationAction, note?: string): Promise<void> {
    const seg = targetType === "POST" ? "posts" : "comments";
    await this.http<unknown>(
      `/admin/v1/community/${seg}/${encodeURIComponent(externalId)}/moderate`,
      { method: "POST", body: JSON.stringify({ action, note: note ?? null }) },
    );
  }

  /* ===== 출금 승인 큐 (§4-E) ===== */

  async getTransfers(status: AdminTransferStatus = "REQUESTED",
                     opts: PageOpts = {}): Promise<AdminPage<AdminTransfer>> {
    const res = await this.http<PagedEnvelope<AdminTransferWire>>(
      `/admin/v1/transfers${pageQuery(opts, { status })}`,
    );
    return toPage((res.data ?? []).map(toAdminTransfer), res);
  }

  async approveTransfer(externalId: string): Promise<AdminTransfer> {
    const res = await this.http<AdminTransferWire>(
      `/admin/v1/transfers/${encodeURIComponent(externalId)}/approve`,
      { method: "POST" },
    );
    return toAdminTransfer(res);
  }

  async rejectTransfer(externalId: string, reason: string): Promise<AdminTransfer> {
    const res = await this.http<AdminTransferWire>(
      `/admin/v1/transfers/${encodeURIComponent(externalId)}/reject`,
      { method: "POST", body: JSON.stringify({ reason }) },
    );
    return toAdminTransfer(res);
  }

  async postNotice(title: string, body: string, severity: NoticeSeverity = "info"): Promise<Notice> {
    // 서버 응답은 {notice_id}만 준다. 목록 스키마에 맞춰 요청 값과 세션·수신 시각으로 뷰모델을 채운다.
    const res = await this.http<{ notice_id?: number }>("/admin/v1/config/notices", {
      method: "POST",
      body: JSON.stringify({ operator_id: this.opId(), title, body, severity: severity.toUpperCase() }),
    });
    return {
      id: String(res.notice_id ?? ""),
      title,
      body,
      severity,
      active: true,
      createdAt: isoNow(),
      createdBy: this.session?.operatorId ?? "",
    };
  }

  async updateNotice(id: string, patch: NoticePatch): Promise<Notice> {
    // PATCH 성공만 확인한다. 최신 행은 호출부가 재조회로 반영하므로 요청 값 기준 낙관 뷰모델을 반환한다.
    await this.http<void>(`/admin/v1/config/notices/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({
        operator_id: this.opId(),
        title: patch.title,
        body: patch.body,
        severity: patch.severity?.toUpperCase(),
        active: patch.active,
      }),
    });
    return {
      id,
      title: patch.title ?? "",
      body: patch.body ?? "",
      severity: patch.severity ?? "info",
      active: patch.active ?? true,
      createdAt: "",
      createdBy: "",
    };
  }

  deleteNotice(id: string): Promise<void> {
    // 서버가 active=false 소프트 삭제로 처리한다(§6-D). 하드 삭제 아님.
    return this.http<void>(`/admin/v1/config/notices/${encodeURIComponent(id)}`, {
      method: "DELETE",
      body: JSON.stringify({ operator_id: this.opId() }),
    });
  }

  async getOperators(): Promise<AdminOperator[]> {
    // 서버는 {data:[{operator_id,display_name,status,roles}]}. roles는 이미 정본 역할 enum 이름이다.
    const res = await this.http<WireDataEnvelope<WireOperator>>("/admin/v1/operators");
    return (res.data ?? []).map((o) => ({
      operatorId: o.operator_id ?? "",
      displayName: o.display_name ?? "",
      roles: (o.roles ?? []) as AdminRole[],
      status: o.status === "DISABLED" ? "DISABLED" : "ACTIVE",
    }));
  }

  async getAudit(opts: PageOpts = {}): Promise<AdminPage<AuditEntry>> {
    // 서버 프로젝션은 id·display_name·request_id를 포함한다(AuditController §3-1). 결측 행만
    // rowKey용 합성 id·operator_id 표기로 폴백한다. result는 DDL 3분값(OK·DENIED·ERROR)을
    // 그대로 보존한다. ERROR(처리 중 5xx)를 OK로 접으면 감사 판독이 오염된다
    const res = await this.http<PagedEnvelope<WireAudit>>(`/admin/v1/audit${pageQuery(opts)}`);
    const rows: AuditEntry[] = (res.data ?? []).map((e, i) => {
      const approval = e.approval_id;
      return {
        id: e.id != null && e.id !== "" ? String(e.id) : `aud-${e.created_at ?? ""}-${i}`,
        at: e.created_at ?? "",
        operatorId: e.operator_id ?? "",
        operatorName: e.display_name || (e.operator_id ?? ""),
        action: e.action ?? "",
        target: e.target ?? "",
        approvalId: approval == null || approval === "" ? null : String(approval),
        result: e.result === "DENIED" ? "DENIED" : e.result === "ERROR" ? "ERROR" : "OK",
        requestId: e.request_id ?? "",
      };
    });
    return toPage(rows, res);
  }
}
