/**
 * lib/admin/httpAdmin.ts. Spring 관리 평면(/admin/v1/*) REST 어댑터.
 * 전송 계층 mTLS는 브라우저 클라이언트 인증서가 담당하고(fetch에 투명), 앱 계층은 mTLS
 * 신원에서 유래한 단기 세션 토큰을 Bearer로 싣는다(§1-3). 세션 토큰은 메모리에만 둔다.
 * 데모 기본은 SimAdminApi이며 이 어댑터는 NEXT_PUBLIC_DATASOURCE=http에서만 활성이다.
 *
 * 승인은 발급형(§3-6)이다. 승인자가 POST /admin/v1/approvals에 {requester_operator_id, action,
 * target}을 실어 approval_token을 발급하고, 요청자가 파괴 액션 엔드포인트 바디에 approval_token을
 * 실어 소각한다. 콘솔 의미 액션(chaos.inject 등)은 wireBinding으로 백엔드 바인딩 문자열
 * (chaos.kill_node/network/app 등)과 대상으로 분해한다. targetKey(콘솔 UI 키)는 바인딩 target과 다르다.
 *
 * 읽기 응답은 서버 엔벌로프(data·checks·catalog·lease 등)를 언랩하고 snake_case를 도메인 뷰모델로
 * 매핑한다. 무검사 캐스팅은 배열 기대 지점에서 TypeError(콘솔 진입 크래시)를 유발하므로 쓰지 않는다.
 * 서버 미노출 필드(HA 노드 토폴로지·큐·지연, 감사 로그 id 등)는 안전 기본값으로 좁힌다.
 */

import { AdminError, type AdminApi } from "./api";
import { approvalConsoleTarget } from "./roles";
import { CHAOS_CATALOG, INVARIANT_DEFS } from "./catalog";
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
/**
 * kill-node 대상 컨테이너 기본값. harness/docker-compose.chaos.yml의 실제 이름이다.
 * 종전 값(exchange-core-1)은 어느 컨테이너와도 매칭되지 않아 소비기가 100% "No such
 * container"로 실패했다. 실 리더가 api-2인 경우는 소비기가 아니라 실험 스크립트가
 * Get-LeaderContainer로 판별한다(콘솔은 기본 대상만 제시).
 */
const CHAOS_NODE = "chaos-api-1";

/** 유한수만 취하고 아니면 기본값(무검사 캐스팅의 NaN 렌더 방지) */

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

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * 콘솔 액션·payload -> 백엔드 승인 바인딩(action + 대상). POST /admin/v1/approvals와 소각 엔드포인트가
 * 공유하는 (action, target) 바인딩이다. chaos.inject는 실험 injectPath로 kill_node/network/app를 가른다.
 * 콘솔 targetKey와 달리 여기 target은 실제 자원(node·toxic·symbol·operatorId)이다(재사용 금지 규율).
 */
function wireBinding(payload: ApprovalPayload): { action: string; target: string } {
  switch (payload.action) {
    case "chaos.inject": {
      const def = CHAOS_CATALOG.find((d) => d.id === payload.experimentId);
      const path = def?.injectPath ?? "";
      if (path.endsWith("kill-node")) return { action: "chaos.kill_node", target: CHAOS_NODE };
      if (path.endsWith("app")) return { action: "chaos.app", target: `${payload.experimentId.toLowerCase()}-assault` };
      return { action: "chaos.network", target: `${payload.experimentId.toLowerCase()}-toxic` };
    }
    case "chaos.clear":
      return { action: "chaos.network.clear", target: payload.runId };
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

/** 원장의 바인딩 action -> 콘솔 의미 액션 역매핑. chaos 3종은 주입 하나로 수렴한다 */
function consoleAction(wireAction: string): DestructiveAction {
  switch (wireAction) {
    case "chaos.kill_node":
    case "chaos.network":
    case "chaos.app":
      return "chaos.inject";
    case "chaos.network.clear":
      return "chaos.clear";
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
      return "chaos.inject";
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
  return `관리 요청이 많습니다. ${when} 다시 시도하세요`;
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
/** GET /admin/v1/chaos/runs 감사 투영 원소(토큰 미노출). state·blast_radius 없음 */
interface WireChaosRun {
  id?: number | string;
  action?: string;
  target?: string;
  experiment_id?: string;
  operator_id?: string;
  created_at?: string;
}

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

/** 서버 판정 문자열을 좁힌다. 미지값은 null이고 그것을 통과로 읽지 않는다. */
function verdictOf(v: string | undefined): RecoveryVerdict | null {
  return v === "PASS" || v === "FAIL" || v === "UNKNOWN" ? v : null;
}

export class HttpAdminApi implements AdminApi {
  // v2.22부터 서버 control이 플릿 수명주기를 실반영한다(start 기동·pause 발행 게이트·stop 정지).
  // 반영 여부는 응답 applied와 status 1.2의 running·paused가 원천이다
  readonly controlApplied = true;

  private readonly base: string;
  private session: AdminSession | null = null;
  private listeners = new Set<(s: AdminSession | null) => void>();

  constructor(base: string) {
    this.base = base.replace(/\/+$/, "");
  }

  private async http<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers: Record<string, string> = {
      ...(init.body != null ? JSON_HEADERS : {}),
      ...(this.session ? { Authorization: `Bearer ${this.session.token}` } : {}),
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

  /* ===== 인증 ===== */

  listDemoCerts(): DemoClientCert[] {
    // 운영 mTLS는 OS·브라우저 인증서 저장소를 쓴다. 앱이 인증서를 열거하지 않는다
    return [];
  }

  async establishSession(): Promise<AdminSession> {
    // 서버 세션 응답은 snake_case. camelCase 도메인으로 명시 매핑한다. issued_at은 수신 시각으로 파생.
    const raw = await this.http<WireSession>("/admin/v1/session", { method: "POST" });
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

  /* ===== 이중 승인: 발급형(§3-6) ===== */

  async issueApproval(requesterId: string, action: DestructiveAction, payload: ApprovalPayload): Promise<IssuedApproval> {
    const approver = this.getSession();
    if (!approver) throw new AdminError("jwt_verification", "관리자 세션 없음");
    const bind = wireBinding(payload);
    const { key, label } = approvalConsoleTarget(payload);
    // 발급 주체(현재 mTLS 세션)와 요청 주체 분리는 서버가 인증서 CN으로 강제한다(반결탁)
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
      action: "chaos.inject",
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
    const operatorId = this.opId();
    const token = approval.token;
    const p = approval.payload;
    switch (p.action) {
      case "chaos.inject": {
        const def = CHAOS_CATALOG.find((d) => d.id === p.experimentId);
        const path = def?.injectPath ?? "/admin/v1/chaos/network";
        const bind = wireBinding(p);
        // 주입 파라미터는 카탈로그에서 온다. 상수로 박으면 E7(pause)이 kill로 나가고
        // network 계열이 전부 지연이 돼 화면 라벨과 실제 주입이 갈린다.
        // experiment는 소비기가 주입 경로·범위 제외(E3)를 가르는 열쇠라 반드시 싣는다.
        const common = { operator_id: operatorId, approval_token: token, experiment: p.experimentId };
        if (path.endsWith("kill-node")) {
          await this.http<void>(path, {
            method: "POST",
            body: JSON.stringify({ ...common, node: bind.target, method: def?.method ?? "docker_kill", expect_failover: true }),
          });
        } else if (path.endsWith("app")) {
          await this.http<void>(path, {
            method: "POST",
            body: JSON.stringify({ ...common, assault: bind.target }),
          });
        } else {
          await this.http<void>(path, {
            method: "POST",
            body: JSON.stringify({ ...common, name: bind.target, type: def?.toxicType ?? "latency" }),
          });
        }
        return;
      }
      case "chaos.clear":
        await this.http<void>(`/admin/v1/chaos/network/${encodeURIComponent(wireBinding(p).target)}`, {
          method: "DELETE",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token }),
        });
        return;
      case "sim.bots.params":
        await this.http<void>(`/admin/v1/sim/bots/${encodeURIComponent(p.botId)}/params`, {
          method: "PATCH",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token, params: p.params }),
        });
        return;
      case "config.market.halt":
        await this.http<void>(`/admin/v1/config/markets/${encodeURIComponent(p.symbol)}/halt`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token }),
        });
        return;
      case "config.market.resume":
        await this.http<void>(`/admin/v1/config/markets/${encodeURIComponent(p.symbol)}/resume`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token }),
        });
        return;
      case "config.market.fees":
        await this.http<void>(`/admin/v1/config/markets/${encodeURIComponent(p.symbol)}/fees`, {
          method: "PATCH",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token, maker_fee: p.makerFee, taker_fee: p.takerFee }),
        });
        return;
      case "operators.create":
        await this.http<void>("/admin/v1/operators", {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token, new_operator_id: p.operatorId, display_name: p.displayName }),
        });
        return;
      case "operators.status":
        await this.http<void>(`/admin/v1/operators/${encodeURIComponent(p.operatorId)}/status`, {
          method: "PATCH",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token, status: p.status }),
        });
        return;
      case "operators.roles":
        // 부여·회수 단건 모델(정본 API). 백엔드는 {action: GRANT|REVOKE, role}을 읽는다
        await this.http<void>(`/admin/v1/operators/${encodeURIComponent(p.operatorId)}/roles`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token, action: p.grant, role: p.role }),
        });
        return;
      case "members.close":
        await this.http<void>(`/admin/v1/members/${encodeURIComponent(p.externalId)}/close`, {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, approval_token: token }),
        });
        return;
    }
  }

  /* ===== 모니터링 ===== */

  async getOverview(): Promise<AdminOverview> {
    // 종합 요약은 개별 모니터링 엔드포인트를 합성한다(전용 엔드포인트 없음).
    const [ha, inv, sim, chaos, markets, rec, pending] = await Promise.all([
      this.getHaStatus(),
      this.getInvariants(),
      this.getSimStatus(),
      this.getChaosRuns(),
      this.getConfigMarkets(),
      this.getRecovery(),
      // 종합 상태의 대기 건수는 첫 쪽 기준이다. 500건을 넘겨 대기 중인 승인은 이미
      // 종합 카드가 아니라 승인 화면에서 다뤄야 할 상태다
      this.listApprovals("ISSUED").then((r) => r.data.length),
    ]);
    const okCount = inv.filter((i) => i.ok).length;
    const health: "success" | "warning" | "danger" = ha.nodes.some((n) => n.health === "down")
      ? "danger"
      : ha.nodes.some((n) => n.health === "degraded") || okCount < inv.length
        ? "warning"
        : "success";
    return {
      overallHealth: health,
      epoch: ha.epoch,
      leaderNodeId: ha.leaderNodeId,
      invariantsOk: okCount,
      invariantsTotal: inv.length,
      lastRtoMs: rec.lastRtoMs,
      lastVerdict: rec.lastVerdict,
      botsActive: sim.activeBots,
      currentOps: sim.currentOps,
      chaosRunning: chaos.data.filter((r) => r.state === "RUNNING").length,
      pendingApprovals: pending,
      suspendedMarkets: markets.filter((m) => m.state === "HALTED").length,
      generatedAt: ha.generatedAt,
    };
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
      sequencerQueueSize: 0,
      orderLatencyP99Ms: 0,
      // 원장 불균형 실측은 invariants(ledger_imbalance)가 담당한다. status에는 없어 보존값으로 둔다
      ledgerImbalance: "0",
      tradesTotal: trades,
      hikariPending: 0,
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
        // RPO는 recovery_runs에 컬럼이 없다. 원천은 harness.failover_runs.rpo_records다.
        // 종전에는 0을 박아 화면이 "유실 0"을 단정했다. 같은 파일 activateScenario가
        // "없는 값을 0으로 채우지 않는다"를 규율로 적었는데 여기만 어겼다. RPO=0은
        // 수용 기준의 헤드라인 주장이라 더 나쁘다.
        rpoMs: null,
        replayedFromSeq: num(r.replayed_from_seq),
        replayedToSeq: num(r.replayed_to_seq),
        // 서버가 스키마 1.2부터 낸다. 재생 0건과 실재 재생을 이 값으로 가른다
        // (fp에 verified_events가 실린다). 종전에는 빈 문자열로 덮어 구분이 사라졌다.
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
    // 판정은 서버 verdict를 쓴다. 종전에는 failover_runs 최신 1행으로 화면 판정을 만들어,
    // 과거 FAIL과 최신 PASS가 함께 있으면 서버가 FAIL인데 화면은 통과를 표시했다.
    // 배열은 limit로 자른 창이라 그것으로 결론을 내면 창 밖 위반이 안 보인다.
    const v = res.verdict;
    // 스키마 1.0 서버는 verdict가 없다. 그때만 종전 경로로 떨어진다.
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

  /* ===== 봇 플릿 ===== */

  async getSimStatus(): Promise<SimStatus> {
    // 서버 /status 1.1은 {schema_version, fleet_present, target_ops, trade_flash, markets[]}만 준다.
    // 명시 매핑 + 안전 기본값으로 좁힌다(대수는 markets scale 합, currentOps는 target_ops).
    const raw = await this.http<WireSimStatus>("/admin/v1/sim/status");
    const markets = this.mapMarkets(raw.markets);
    let activeBots = 0;
    let botCount = 0;
    for (const m of markets) {
      for (const type of Object.keys(m.scale) as (keyof MarketSimState["scale"])[]) {
        const e = m.scale[type];
        if (!e) continue;
        activeBots += e.active;
        botCount += e.target;
      }
    }
    const rate = num(raw.target_ops);
    return {
      // status 1.2의 실제 플릿 상태. fleet_present는 빈 존재 여부일 뿐 가동 여부가 아니다
      running: raw.running ?? raw.fleet_present === true,
      paused: raw.paused ?? false,
      rate,
      currentOps: rate,
      botCount,
      activeBots,
      successRate: "1.0000",
      avgLatencyMs: 0,
      duplicateFillTotal: 0,
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
    // apply_path=queued는 성공이다. 관리 평면과 봇 JVM이 물리 분리된 배치에서 서버가
    // sim_commands에 의도를 적재하고 봇이 1초 안에 집어 적용한다(API§6-A). 종전에는
    // fleet_present:false만 보고 예외를 던져 실제로 적용된 조작을 빨간 토스트로 표시했다.
    // 큐 경로가 없던 시절에는 fleet_present:false가 곧 미적용이라 그 판단이 맞았다.
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
    // 백엔드 RateBody 키는 target_ops이며 문자열로 파싱한다(NUMERIC 정밀도 계약)
    // 응답을 버리지 않는다. 종전에는 void로 읽어 봇에 닿았는지와 무관하게 화면이 완료를
    // 단정했다. 큐 경로를 넣은 지금은 그 단정이 최대 1초 이른 거짓이 된다.
    // 프리셋은 같은 부류를 반영 대기로 고쳤는데 이 경로만 빠져 있었다.
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

  /* ===== 카오스 ===== */

  getChaosCatalog(): ChaosExperimentDef[] {
    return CHAOS_CATALOG.slice();
  }

  async getChaosRuns(opts: PageOpts = {}): Promise<AdminPage<ChaosRun>> {
    // GET /admin/v1/chaos/runs 감사 투영(id·action·target·experiment_id·created_at). state·blast_radius가
    // 없어 축소 뷰모델로 좁힌다. 활성 여부는 action 스트림(inject vs clear)을 접어 재구성한다.
    // experiment_id는 서버가 UUID로 발급해 카탈로그 id(E1~E9)와 직접 조인이 불가하다. 주입 시
    // wireBinding이 발급한 target 접두(e1-toxic·e8-assault)에서 id를 파생해 조인하고,
    // 고정 노드명을 쓰는 kill-node류는 action 기반 폴백 라벨을 쓴다.
    const res = await this.http<PagedEnvelope<WireChaosRun>>(`/admin/v1/chaos/runs${pageQuery(opts)}`);
    const raw = res.data ?? [];
    const cleared = new Set<string>();
    for (const r of raw) {
      if ((r.action ?? "").includes("clear")) cleared.add(r.target ?? "");
    }
    const rows: ChaosRun[] = raw.map((r, i) => {
      const exp = r.experiment_id ?? "";
      const target = r.target ?? "";
      const prefix = (target.split("-")[0] ?? "").toUpperCase();
      const def = CHAOS_CATALOG.find((d) => d.id === prefix);
      const isKillNode = (r.action ?? "").includes("kill");
      const isClear = (r.action ?? "").includes("clear");
      return {
        id: String(r.id ?? `chx-${r.created_at ?? ""}-${i}`),
        // 해제는 감사 행 id가 아니라 주입 시 발급한 이름(target)을 지칭해야 성립한다(§3-6).
        // clear 행 target과 inject 행 target이 같은 문자열이라 cleared 접기도 이 키로 닫힌다
        clearKey: target,
        experimentId: exp,
        label: def?.label ?? (isKillNode ? `리더 노드 kill (${target})` : exp),
        state: isClear || cleared.has(target) ? "CLEARED" : "RUNNING",
        blastRadius: def?.blastRadius ?? (isKillNode ? "체결 엔진 leader" : ""),
        grafanaAnnotation: "",
        injectedBy: r.operator_id ?? "",
        startedAt: r.created_at ?? "",
        clearedAt: null,
      };
    });
    return toPage(rows, res);
  }

  /* ===== 마켓 설정 ===== */

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
    const res = await this.http<WireMember & {
      updated_at?: string; open_order_count?: number | string;
      last_order_at?: string | null; asset_kinds?: number | string;
    }>(`/admin/v1/members/${encodeURIComponent(externalId)}`);
    return {
      ...toMember(res),
      updatedAt: Date.parse(res.updated_at ?? "") || 0,
      openOrderCount: num(res.open_order_count),
      lastOrderAt: res.last_order_at ? Date.parse(res.last_order_at) || null : null,
      assetKinds: num(res.asset_kinds),
    };
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

  /* ===== 운영자·감사 ===== */

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
