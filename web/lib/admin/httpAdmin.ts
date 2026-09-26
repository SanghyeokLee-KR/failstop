/**
 * 앱 계층은 mTLS 신원에서 유래한 단기 세션 토큰을 Bearer로 싣고 메모리에만 둔다(§1-3).
 * 승인은 발급형이라 승인자가 approval_token을 발급하고 요청자가 액션 바디에 실어 소각한다(§3-6).
 * 읽기 응답은 무검사 캐스팅 없이 언랩·매핑하고, 서버 미노출 필드는 안전 기본값으로 좁힌다.
 */

import { AdminError, type AdminApi } from "./api";
import { approvalConsoleTarget } from "./roles";
import {
  OpsFeed,
  absentBoard,
  boardOf,
  chatReplyOf,
  eventOf,
  judgmentOf,
  limitWireKey,
  proposalOf,
  reportDeskOf,
  thresholdToWire,
  windowOf,
  type WireAction,
  type WireChatReply,
  type WireDecision,
  type WireIncidentEvent,
  type WireOverview,
  type WireReport as WireOpsReport,
  type WireReportsStatus,
  type WireSection,
  type WireWindow,
} from "./httpOps";
import { linkReverts, serverRecords } from "./actionLog";
import { SLA_SOURCE } from "./sla";
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
  HaLease,
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
  ControlCenter,
  AiChatReply,
  AiChatTurn,
  AiDecision,
  AiJudgment,
  IncidentEvent,
  ReportDesk,
  OpsSection,
  OnCallEntry,
  TrendRange,
  MemberStatus,
  MemberTransition,
  ModerationAction,
  ReportReason,
  ReportResolution,
  ReportStatus,
  AdminTransferStatus,
  ActionFeed,
  ChangeRequest,
  MaintenanceWindow,
  WindowInput,
  SlaMonth,
  OpsFlowTapeEntry,
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
    // 서버가 안 실어 보내면 계약 기본값으로 떨어진다. 옛 서버와 새 화면이 섞여도
    // 눈금이 사라지지 않게 한다
    thresholds: {
      scaleOutCpuRatio: num(w.thresholds?.scale_out_cpu_ratio, 0.6),
      scaleInCpuRatio: num(w.thresholds?.scale_in_cpu_ratio, 0.3),
    },
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
    case "ops.threshold.set":
      // 값까지 묶는다(§6-F). 되돌리기도 되돌린 뒤의 값(직전 값)이 대상이다. 서버 단위의 끝 0 없는 십진 표기다
      return { action: "ops.threshold.set", target: `${payload.key}:${thresholdToWire(payload.key, payload.to)}` };
    case "ops.ai.auto":
      return { action: "ops.ai.auto", target: `AUTO:${payload.hours}` };
    case "ops.ai.limits":
      return { action: "ops.ai.limits", target: `${limitWireKey(payload.key)}:${payload.to}` };
    case "ops.change.approve":
      // 서버 경로는 3라운드 계약 초안이다(대상 {id}). 변경 요청 원천이 연결 전이라 화면이 이 티켓을 만들지 않는다
      return { action: "ops.change.approve", target: payload.id };
  }
}

/**
 * 원장의 바인딩 action -> 콘솔 의미 액션 역매핑.
 * 서버에는 콘솔 화면이 없는 승인 action(chaos.* 등)도 있다. 그 행은 콘솔 액션으로 되돌릴 수
 * 없어 폴백으로 흘린다. 승인 목록은 실행 대기(ISSUED)만 읽으므로 그런 행이 목록에 오르는 것은
 * 콘솔 밖에서 발급한 경우뿐이고, 그때 목록 라벨은 폴백 액션 이름으로 보인다.
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
    case "ops.threshold.set":
    case "ops.ai.auto":
    case "ops.ai.limits":
    case "ops.change.approve":
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
  error?: { name?: string; message?: string; retry_after?: number | null; detail?: Record<string, unknown> | null };
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
  lease?: WireHaLease;
  /** 모드가 쓰는 리스 자원별 행(API 설계 §6-C). 옛 서버는 내지 않는다 */
  leases?: WireHaLease[];
  leader_protocol?: number;
  command_watermark?: number;
  event_watermark?: number;
  trades_count?: number;
}
interface WireHaLease {
  resource?: string;
  holder?: string;
  epoch?: number;
  lease_until?: string;
  leader_alive?: boolean;
  lease_remaining_ms?: number;
  markets?: string[];
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
  thresholds?: { scale_out_cpu_ratio?: number; scale_in_cpu_ratio?: number };
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
/** ETag를 기억해 둘 GET 수. 커서마다 주소가 달라 끝없이 늘지 않게 오래된 것부터 버린다 */
const ETAG_KEEP = 64;

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
  /** 떠 있는 GET. 같은 신원·주소의 요청이 겹치면 새로 보내지 않고 이 응답을 나눠 쓴다 */
  private inflight = new Map<string, Promise<unknown>>();
  /** 서버가 ETag를 준 GET의 태그와 본문. 다음 요청에 If-None-Match를 싣고 304면 이 본문을 쓴다 */
  private etags = new Map<string, { tag: string; body: unknown }>();

  constructor(base: string) {
    this.base = base.replace(/\/+$/, "");
  }

  /**
   * GET은 신원과 주소를 열쇠로 겹친 요청을 합친다. 폴러와 화면이 같은 조회를 같은 순간에 부르면 요청은
   * 한 건이고, 관리 요청 한 건이 운영자 버킷에서 10토큰을 쓰므로 합친 만큼 버킷이 남는다
   */
  private http<T>(path: string, init: RequestInit = {}, authorization?: string): Promise<T> {
    if ((init.method ?? "GET") !== "GET" || authorization !== undefined) return this.send<T>(path, init, authorization, null);
    const key = `${this.session?.operatorId ?? ""} ${path}`;
    const running = this.inflight.get(key);
    if (running) return running as Promise<T>;
    const p = this.send<T>(path, init, undefined, key).finally(() => this.inflight.delete(key));
    this.inflight.set(key, p);
    return p;
  }

  private async send<T>(path: string, init: RequestInit, authorization: string | undefined, cacheKey: string | null): Promise<T> {
    const cached = cacheKey === null ? undefined : this.etags.get(cacheKey);
    const headers: Record<string, string> = {
      ...(init.body != null ? JSON_HEADERS : {}),
      ...(this.session ? { Authorization: `Bearer ${this.session.token}` } : {}),
      ...(authorization ? { Authorization: authorization } : {}),
      // 세션 발급 후에도 매 요청에 싣는다. AdminAuthFilter 가 세션 operator 를 현재 mTLS
      // 신원과 재대조하므로(관리자 설계 §3-6 4단계) 발급 때만 실으면 다음 요청이 401 이다
      ...(this.forwardCn ? { "X-Operator-Cn": this.forwardCn } : {}),
      ...(cached ? { "If-None-Match": cached.tag } : {}),
    };
    // mTLS 클라이언트 인증은 브라우저가 TLS 핸드셰이크에서 처리한다
    const res = await fetch(`${this.base}${path}`, { ...init, headers, credentials: "include" });
    if (res.status === 304 && cached) return cached.body as T;
    if (!res.ok) {
      const rateLimited = res.status === 429;
      let code = rateLimited
        ? "too_many_requests"
        : res.status === 401
          ? "jwt_verification"
          : res.status === 403
            ? "admin_forbidden"
            : res.status === 404
              ? "not_found"
              : "server_error";
      let retryAfter: number | null = null;
      let message = rateLimited ? rateLimitMessage(null) : "관리 요청 실패";
      // 에러 바디의 detail. 회원 폐쇄 409 는 남은 수와 사유별 수를 싣는다(API 설계 v2.54 §6-E). 버리면 화면이
      // 폐쇄 확정과 남은 조치를 알릴 근거가 없다
      let detail: Record<string, unknown> | null = null;
      try {
        const body = (await res.json()) as ErrorEnvelope;
        if (body.error?.name) code = body.error.name;
        retryAfter = body.error?.retry_after ?? null;
        if (body.error?.message) message = body.error.message;
        if (body.error?.detail && typeof body.error.detail === "object") detail = body.error.detail;
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
      throw new AdminError(code, message, retryAfter, res.status, detail);
    }
    if (res.status === 204) return undefined as T;
    const body = (await res.json()) as T;
    const tag = cacheKey === null ? null : res.headers?.get("ETag") ?? null;
    if (cacheKey !== null && tag) {
      this.etags.delete(cacheKey);
      this.etags.set(cacheKey, { tag, body });
      if (this.etags.size > ETAG_KEEP) this.etags.delete(this.etags.keys().next().value as string);
    }
    return body;
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
      case "ops.threshold.set": {
        const body = { operator_id: operatorId, ...approval, reason: p.reason };
        if (p.revertOf) {
          await this.http<void>(`/admin/v1/msp/thresholds/changes/${encodeURIComponent(p.revertOf)}/revert`, {
            method: "POST",
            body: JSON.stringify(body),
          });
        } else {
          await this.http<void>(`/admin/v1/msp/thresholds/${encodeURIComponent(p.key)}`, {
            method: "POST",
            body: JSON.stringify({ ...body, value: thresholdToWire(p.key, p.to) }),
          });
        }
        this.ops.invalidate("thresholds.changes");
        return;
      }
      case "ops.ai.auto":
        await this.http<void>("/admin/v1/msp/ai/mode/auto", {
          method: "POST",
          body: JSON.stringify({ operator_id: operatorId, ...approval, hours: p.hours, reason: p.reason }),
        });
        this.ops.invalidate("ai.mode", "ai.limits");
        return;
      case "ops.ai.limits":
        // 되돌리기는 이력의 직전 값으로 같은 경로를 다시 부르는 것이다(§6-F AI 한도)
        await this.http<void>(`/admin/v1/msp/ai/limits/${encodeURIComponent(limitWireKey(p.key))}`, {
          method: "PUT",
          body: JSON.stringify({ operator_id: operatorId, ...approval, value: p.to, reason: p.reason }),
        });
        this.ops.invalidate("ai.limits", "ai.limitChanges");
        return;
      case "ops.change.approve":
        throw new AdminError("not_connected", "변경 요청 원천 연결 전. 서버 경로는 3라운드 계약 초안입니다");
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
      // 종합 카드가 아니라 승인 화면에서 다뤄야 할 상태다. 서버는 TTL이 지난 발급분도 ISSUED로
      // 돌려주고 listApprovals가 그것을 EXPIRED로 파생하므로, 실행할 수 없는 행은 세지 않는다
      this.listApprovals("ISSUED").then((r) => r.data.filter((a) => a.state === "ISSUED").length),
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
      tradesTotal: ha.tradesTotal,
      // 서버 status는 노드별 마켓을 내지 않아 리더 하나로 합성한다(getHaStatus). 0이 아니라 미보고다
      marketPartitions: null,
      leases: ha.leases ?? [],
      generatedAt: ha.generatedAt,
    };
  }

  /* ===== MSP AI 관제센터(관리자 설계 4-4, API 설계 §6-F) =====
   * 한 판(GET /admin/v1/msp/overview)을 ETag로 읽고, 세부 조회는 OpsFeed가 필요한 것만 골라 읽는다(httpOps.ts).
   * 관제 모듈이 꺼진 배포는 overview가 404이고 판의 원천 상태를 모른다(sources null). 폴러는 그때 5분마다만 다시 본다 */

  readonly opsScenarios = false;
  private readonly ops = new OpsFeed(<T,>(path: string) => this.http<T>(path));

  /** 지표 관제의 박자별 기록은 관제 시뮬레이터에만 있다. 실측 추이는 Grafana가 소유한다 */
  getFlowTape(): Promise<OpsFlowTapeEntry[] | null> {
    return Promise.resolve(null);
  }

  async getControlCenter(): Promise<ControlCenter> {
    let w: WireOverview;
    try {
      w = await this.http<WireOverview>("/admin/v1/msp/overview");
    } catch (e) {
      // 관제 모듈이 없는 배포다. 오류 본문의 이름은 배포마다 달라 상태로 가른다
      if (e instanceof AdminError && (e.status === 404 || e.code === "not_found")) return absentBoard();
      throw e;
    }
    await this.ops.update(w);
    return boardOf(w, this.ops.snapshot(w), Date.now());
  }

  wantTrends(range: TrendRange): void {
    this.ops.wantTrend(range);
  }

  async getAiJudgment(id: string): Promise<AiJudgment | null> {
    try {
      const d = await this.http<WireDecision>(`/admin/v1/msp/ai/decisions/${encodeURIComponent(id)}`);
      return judgmentOf(d, { desired: null, alertNames: new Map() });
    } catch (e) {
      if (e instanceof AdminError && e.status === 404) return null;
      throw e;
    }
  }

  async getIncidentEvents(incidentId: string): Promise<IncidentEvent[] | null> {
    const res = await this.http<{ events?: WireIncidentEvent[] }>(`/admin/v1/msp/incidents/${encodeURIComponent(incidentId)}`);
    return (res.events ?? []).map((ev) => eventOf(ev, incidentId)).filter((ev): ev is IncidentEvent => ev !== null);
  }

  private post(path: string, body: Record<string, unknown> = {}): Promise<unknown> {
    return this.http<unknown>(path, { method: "POST", body: JSON.stringify({ operator_id: this.opId(), ...body }) });
  }

  async ackAlert(alertId: string): Promise<void> {
    await this.post(`/admin/v1/msp/alerts/${encodeURIComponent(alertId)}/ack`);
    this.ops.invalidate("incidents");
  }

  /**
   * 사람 보류. 화면은 30분 이하 warning·info만 연다(BOT_OPS 이상, 티켓 없음). 서버는 사유를 요구하므로 화면이 고른
   * 길이를 사유에 적는다. critical·등급 모름과 30분 넘는 보류는 SUPER와 ops.alert.silence 티켓이 필요해 이 경로가 아니다
   */
  async silenceAlert(alertId: string, minutes: number): Promise<void> {
    await this.post(`/admin/v1/msp/alerts/${encodeURIComponent(alertId)}/silence`, { minutes, reason: `관제센터에서 ${minutes}분 보류` });
    this.ops.invalidate("incidents");
  }

  /** 사건 담당 지정. 서버 경로(POST .../incidents/{id}/assign)가 3라운드 계약 초안이라 보내지 않는다 */
  assignIncident(): Promise<void> {
    return Promise.reject(new AdminError("not_connected", "사건 담당 지정 원천 연결 전. 서버 경로는 3라운드 계약 초안입니다"));
  }

  /** 사후 분석 저장. 서버 경로(PUT .../incidents/{id}/postmortem)가 3라운드 계약 초안이라 보내지 않는다 */
  savePostmortem(): Promise<void> {
    return Promise.reject(new AdminError("not_connected", "사후 분석 원천 연결 전. 서버 경로는 3라운드 계약 초안입니다"));
  }

  /**
   * 조치 요청 결정. 승인은 실행이고, 임계값 조치는 1인 승인 티켓(ops.threshold.set, 대상은 바꿀 값)을 발급해 곧바로
   * 싣는다. 나중에는 서버 기본(15분)을 쓰며 요청의 만료를 늘리지 않는다
   */
  async decideAiProposal(proposalId: string, decision: AiDecision): Promise<void> {
    const base = `/admin/v1/msp/ai/actions/${encodeURIComponent(proposalId)}`;
    if (decision === "REJECT") await this.post(`${base}/reject`);
    else if (decision === "DEFER") await this.post(`${base}/defer`);
    else await this.post(`${base}/approve`, await this.actionTicket(proposalId, "approve"));
    this.ops.invalidate("ai.actions", "ai.mode");
  }

  async revertAiAction(proposalId: string): Promise<void> {
    await this.post(`/admin/v1/msp/ai/actions/${encodeURIComponent(proposalId)}/revert`, await this.actionTicket(proposalId, "revert"));
    this.ops.invalidate("ai.actions", "ai.mode");
  }

  /**
   * 임계값 조치의 승인·되돌리기 티켓. 승인 대상은 요청의 새 값, 되돌리기 대상은 실행 때 바꾸기 전의 값이다(§6-F).
   * 다른 종류는 티켓이 없다. 읽어 둔 요청이 없으면 티켓 없이 보내고 서버가 판정한다
   */
  private async actionTicket(proposalId: string, verb: "approve" | "revert"): Promise<Record<string, string>> {
    const a = this.ops.action(proposalId);
    if (!a || a.kind !== "THRESHOLD") return {};
    const key = String(a.params?.key ?? a.target ?? "");
    const raw = verb === "approve" ? a.params?.to ?? a.params?.value : a.result?.from;
    if (raw === undefined || raw === null) return {};
    const value = String(raw).includes(".") ? String(raw).replace(/0+$/, "").replace(/\.$/, "") : String(raw);
    const res = await this.http<WireIssue>("/admin/v1/approvals", {
      method: "POST",
      body: JSON.stringify({ requester_operator_id: this.opId(), action: "ops.threshold.set", target: `${key}:${value}` }),
    });
    return { approval_token: res.approval_token ?? "" };
  }

  /** 자동 모드 켜기. 1인 승인 티켓(ops.ai.auto, 대상 AUTO:{hours})을 발급해 같은 요청에 싣는다(ADR 0019·0035) */
  async enableAiAuto(hours: number, reason: string): Promise<void> {
    const res = await this.http<WireIssue>("/admin/v1/approvals", {
      method: "POST",
      body: JSON.stringify({ requester_operator_id: this.opId(), action: "ops.ai.auto", target: `AUTO:${hours}` }),
    });
    await this.post("/admin/v1/msp/ai/mode/auto", { approval_token: res.approval_token ?? "", hours, reason });
    this.ops.invalidate("ai.mode", "ai.limits");
  }

  async stopAiAuto(): Promise<void> {
    await this.post("/admin/v1/msp/ai/mode/manual");
    this.ops.invalidate("ai.mode");
  }

  async stopAi(): Promise<void> {
    await this.post("/admin/v1/msp/ai/stop");
    this.ops.invalidate("ai.mode", "ai.actions");
  }

  async resumeAi(reason: string): Promise<void> {
    await this.post("/admin/v1/msp/ai/resume", { reason });
    this.ops.invalidate("ai.mode");
  }

  /** 백업 즉시 실행. Databasus 연결 전이라 서버가 409 msp_source_not_connected로 거절하고 성공을 지어내지 않는다 */
  async runBackupNow(): Promise<void> {
    await this.post("/admin/v1/msp/ops/backup");
  }

  startOpsScenario(): Promise<void> {
    return Promise.reject(new AdminError("source_unavailable", "시나리오 재생은 시뮬레이터에만 있습니다"));
  }

  resetOps(): Promise<void> {
    return Promise.reject(new AdminError("source_unavailable", "시뮬레이터 초기화는 시뮬레이터에만 있습니다"));
  }

  /* ===== AI 상담·운영 보고(관리자 설계 4-6·4-7) ===== */

  readonly aiChat = "LIVE" as const;

  /** 서버 상담. 최근 대화는 요청에 실어 보내고 서버는 저장하지 않는다(§6-F AI 상담) */
  async askAi(question: string, recent: AiChatTurn[]): Promise<AiChatReply> {
    const history = recent.map((m) => ({ role: m.role === "operator" ? "user" : "assistant", content: m.text.slice(0, 1200) })).filter((m) => m.content.trim() !== "");
    const res = await this.http<WireChatReply>("/admin/v1/msp/ai/chat", {
      method: "POST",
      body: JSON.stringify({ operator_id: this.opId(), question, history }),
    });
    return chatReplyOf(res, question);
  }

  async getReports(): Promise<OpsSection<ReportDesk>> {
    try {
      const [list, status] = await Promise.all([
        this.http<WireSection<WireOpsReport[]>>("/admin/v1/msp/reports?limit=20"),
        this.http<WireReportsStatus>("/admin/v1/msp/reports/status"),
      ]);
      return reportDeskOf(list, status, "");
    } catch (e) {
      if (e instanceof AdminError && (e.status === 404 || e.code === "not_found")) {
        return { state: "NOT_CONNECTED", source: "관리 API에 관제 모듈 없음(보고 서비스 읽기 프록시 404)" };
      }
      throw e;
    }
  }

  sendTeamsTest(): Promise<void> {
    return Promise.reject(new AdminError("source_unavailable", "Teams 연결 시험은 서버 계약에 없습니다"));
  }

  getOnCall(): Promise<OpsSection<OnCallEntry[]>> {
    return Promise.resolve({ state: "NOT_CONNECTED", source: "운영자·당번 명부 · 서버 계약에 없음" });
  }

  /** SLA 월 집계는 3라운드 계약 초안이고 외부 프로브가 없어 가용성 실적의 원천부터 없다(관리자 설계 4-10-5) */
  getSlaMonth(_month: string): Promise<OpsSection<SlaMonth>> {
    return Promise.resolve({ state: "NOT_CONNECTED", source: SLA_SOURCE });
  }

  /* ===== 조치 기록(관리자 설계 4-10-4) ===== */

  /**
   * 감사 로그의 한 쪽(최신 500줄)과 조치 요청 목록, 승인 원장의 한 쪽을 읽어 조치 기록으로 옮긴다. 감사에는 관리 요청마다의
   * 줄이 함께 있어 읽기와 같은 요청의 필터 줄을 뺀다(actionLog.serverRecords). AI 조치 줄('ai-action:77')은 조치 요청의 제목과
   * 판단 기록으로 잇고, 되돌린 줄과 되돌린 기록을 서로 가리키게 한다. 이전 쪽은 cursor로 더 읽는다
   */
  async getActionRecords(cursor: string | null = null): Promise<ActionFeed> {
    const page = await this.getAudit({ cursor, limit: 500 });
    const [actions, approvals] = cursor === null
      ? await Promise.all([
          this.http<{ data?: WireAction[] }>("/admin/v1/msp/ai/actions?status=ALL&limit=100").then((r) => r.data ?? []).catch(() => [] as WireAction[]),
          this.listApprovals(undefined, { limit: 100 }).then((r) => r.data).catch(() => [] as ApprovalRequest[]),
        ])
      : [[] as WireAction[], [] as ApprovalRequest[]];
    const rows = serverRecords(page.data);
    const byId = new Map(actions.map((a) => [String(a.id ?? ""), a]));
    const refs = new Map<string, { ref: string | null; revertOfRef: string | null }>();
    for (const r of rows) {
      const m = /^ai-action:(\S+)$/.exec(r.target);
      if (!m) continue;
      const a = byId.get(m[1]);
      if (a) {
        r.title = proposalOf(a, { desired: null, alertNames: new Map() })?.title ?? null;
        r.judgmentId = a.last_decision_id == null ? null : String(a.last_decision_id);
      }
      const revert = /\.(auto_)?revert$/.test(r.action);
      const run = /\.(approve|execute)$/.test(r.action);
      refs.set(r.id, { ref: run ? `action:${m[1]}` : null, revertOfRef: revert ? `action:${m[1]}` : null });
    }
    linkReverts(rows, refs);
    return { origin: "LIVE", rows, approvals, nextCursor: page.nextCursor, partial: true };
  }

  /* ===== 변경·작업(관리자 설계 4-10-4) =====
   * 계획 작업 창은 서버 계약(§6-F)이 있어 보낸다. 변경 요청은 서버 경로가 3라운드 계약 초안이라 보내지 않고 거절한다 */

  createChange(): Promise<ChangeRequest> {
    return Promise.reject(new AdminError("not_connected", "변경 요청 원천 연결 전. 서버 경로는 3라운드 계약 초안입니다"));
  }

  startChange(): Promise<void> {
    return Promise.reject(new AdminError("not_connected", "변경 요청 원천 연결 전. 서버 경로는 3라운드 계약 초안입니다"));
  }

  finishChange(): Promise<void> {
    return Promise.reject(new AdminError("not_connected", "변경 요청 원천 연결 전. 서버 경로는 3라운드 계약 초안입니다"));
  }

  cancelChange(): Promise<void> {
    return Promise.reject(new AdminError("not_connected", "변경 요청 원천 연결 전. 서버 경로는 3라운드 계약 초안입니다"));
  }

  /** 창 등록(BOT_OPS 이상). 시작을 비우면 서버가 지금으로 둔다 */
  async createWindow(input: WindowInput): Promise<MaintenanceWindow> {
    const labels = Object.fromEntries(Object.entries(input.labels).filter(([, v]) => (v ?? "").trim() !== ""));
    const body: Record<string, unknown> = { ends_at: input.endsAt, reason: input.reason.trim() };
    if (input.startsAt) body.starts_at = input.startsAt;
    if (input.alertname && input.alertname.trim() !== "") body.alertname = input.alertname.trim();
    if (Object.keys(labels).length > 0) body.labels = labels;
    const res = await this.post("/admin/v1/msp/maintenance-windows", body);
    this.ops.invalidate("windows");
    return windowOf(res as WireWindow);
  }

  /** 창 조기 종료(VIEWER 이상). 이미 끝난 창이면 서버가 changed:false를 준다 */
  async endWindow(id: string): Promise<void> {
    const res = (await this.post(`/admin/v1/msp/maintenance-windows/${encodeURIComponent(id)}/end`)) as { changed?: boolean };
    this.ops.invalidate("windows");
    if (res?.changed === false) throw new AdminError("invalid_parameter", "이미 끝난 창");
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
    // 리스 표. 옛 서버 응답(leases 없음)이면 lease 한 행을 matching-engine 으로 만든다. 그 응답은 마켓 목록을 주지 않는다.
    const wireLeases = res.leases && res.leases.length > 0
      ? res.leases
      : [{ ...lease, resource: lease.resource ?? "matching-engine" }];
    return {
      epoch: node.epoch,
      leaderNodeId: holder,
      nodes: [node],
      tradesTotal: trades,
      generatedAt: isoNow(),
      leases: wireLeases.map(mapHaLease),
    };
  }

  async getRecovery(since?: string): Promise<HaRecovery> {
    // 서버는 결정적 재현(recovery_runs)과 장애조치 드릴(failover_runs)을 분리한다. 복구 표는
    // recovery_runs를 행으로 매핑하고, RTO 수치는 failover_runs 최신 1건을 쓴다. 판정은 다르다.
    // 서버 verdict 객체가 표 전체(또는 since 창) 집계라 그것을 읽는다(아래).
    const q = since ? `?${new URLSearchParams({ since })}` : "";
    const res = await this.http<WireRecovery>(`/admin/v1/ha/recovery${q}`);
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

/** 리스 한 행. 서버가 주지 않은 남은 시간·만료 시각은 null로 둔다 */
function mapHaLease(w: WireHaLease): HaLease {
  return {
    resource: w.resource ?? "matching-engine",
    holder: w.holder ?? "",
    epoch: num(w.epoch),
    leaseUntil: w.lease_until ?? null,
    leaderAlive: w.leader_alive === true,
    leaseRemainingMs: typeof w.lease_remaining_ms === "number" ? w.lease_remaining_ms : null,
    markets: Array.isArray(w.markets) ? w.markets.slice() : [],
  };
}
