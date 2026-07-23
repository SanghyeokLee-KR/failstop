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
  RecoveryRun,
  SimStatus,
  TradeFlash,
} from "./types";

/** 마켓 심볼 -> 한글명. 서버 config/markets는 한글명을 싣지 않아 표시용으로만 조인한다 */
const COIN_NAME = new Map(COIN_META.map((c) => [c.market, c.koreanName]));
/** 불변식 check_name -> 라벨. 서버 invariants는 라벨을 싣지 않는다 */
const INVARIANT_LABEL = new Map(INVARIANT_DEFS.map((d) => [d.name, d.label]));
/** kill-node 대상 노드 데모 기본값(단일 리더 토폴로지) */
const CHAOS_NODE = "exchange-core-1";

/** 유한수만 취하고 아니면 기본값(무검사 캐스팅의 NaN 렌더 방지) */
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
  matched?: boolean | string;
  duration_ms?: number | string;
  created_at?: string;
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
  operator_id?: string;
  action?: string;
  target?: string;
  result?: string;
  approval_id?: number | string;
  created_at?: string;
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

export class HttpAdminApi implements AdminApi {
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
      let message = rateLimited ? rateLimitMessage(null) : "관리 요청에 실패했습니다";
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
    if (!s) throw new AdminError("jwt_verification", "관리자 세션이 없습니다");
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
    if (!approver) throw new AdminError("jwt_verification", "관리자 세션이 없습니다");
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

  async listApprovals(state?: ApprovalState): Promise<ApprovalRequest[]> {
    const q = state ? `?state=${encodeURIComponent(state)}` : "";
    const res = await this.http<WireDataEnvelope<WireApproval>>(`/admin/v1/approvals${q}`);
    return (res.data ?? []).map((a, i) => {
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
        if (path.endsWith("kill-node")) {
          await this.http<void>(path, {
            method: "POST",
            body: JSON.stringify({ operator_id: operatorId, approval_token: token, node: bind.target, method: "docker_kill", expect_failover: true }),
          });
        } else if (path.endsWith("app")) {
          await this.http<void>(path, {
            method: "POST",
            body: JSON.stringify({ operator_id: operatorId, approval_token: token, assault: bind.target }),
          });
        } else {
          await this.http<void>(path, {
            method: "POST",
            body: JSON.stringify({ operator_id: operatorId, approval_token: token, name: bind.target, type: "latency" }),
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
      this.listApprovals("ISSUED").then((r) => r.length),
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
      chaosRunning: chaos.filter((r) => r.state === "RUNNING").length,
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
    // recovery_runs를 행으로 매핑하고, 대시보드 RTO·판정은 권위값 failover_runs 최신을 우선한다.
    const res = await this.http<WireRecovery>("/admin/v1/ha/recovery");
    const runs: RecoveryRun[] = (res.recovery_runs ?? []).map((r, i) => {
      const matched = r.matched === true;
      return {
        id: `rec-${r.created_at ?? ""}-${i}`,
        startedAt: r.created_at ?? "",
        durationMs: num(r.duration_ms),
        // recovery_runs는 결정적 재현이라 RPO 0. 서버 프로젝션에 fp_replay·fp_live는 없다
        rpoMs: 0,
        replayedFromSeq: num(r.replayed_from_seq),
        replayedToSeq: num(r.replayed_to_seq),
        fpReplay: "",
        fpLive: "",
        matched,
        verdict: matched ? "PASS" : "FAIL",
      };
    });
    const drill = (res.failover_runs ?? [])[0];
    const drillRto = drill ? Number(drill.rto_seconds) : Number.NaN;
    const lastRtoMs = drill && Number.isFinite(drillRto) ? Math.round(drillRto * 1000) : runs[0]?.durationMs ?? null;
    const drillVerdict = drill?.verdict === "PASS" || drill?.verdict === "FAIL" ? drill.verdict : null;
    const lastVerdict = drillVerdict ?? runs[0]?.verdict ?? null;
    return { runs, lastRtoMs, lastVerdict };
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
      running: raw.fleet_present === true,
      paused: false,
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

  async setEffectConfig(tradeFlash: TradeFlash): Promise<TradeFlash> {
    const res = await this.http<{ trade_flash?: TradeFlash }>("/admin/v1/sim/effects", {
      method: "PUT",
      body: JSON.stringify({ trade_flash: tradeFlash }),
    });
    return res.trade_flash ?? tradeFlash;
  }

  simStart(): Promise<void> {
    return this.http<void>("/admin/v1/sim/start", { method: "POST", body: JSON.stringify({ operator_id: this.sessionOpId() }) });
  }

  simStop(): Promise<void> {
    return this.http<void>("/admin/v1/sim/stop", { method: "POST", body: JSON.stringify({ operator_id: this.sessionOpId() }) });
  }

  simPause(): Promise<void> {
    return this.http<void>("/admin/v1/sim/pause", { method: "POST", body: JSON.stringify({ operator_id: this.sessionOpId() }) });
  }

  simSetRate(rate: number): Promise<void> {
    // 백엔드 RateBody 키는 target_ops이며 문자열로 파싱한다(NUMERIC 정밀도 계약)
    return this.http<void>("/admin/v1/sim/rate", {
      method: "PUT",
      body: JSON.stringify({ operator_id: this.sessionOpId(), target_ops: String(rate) }),
    });
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

  async getChaosRuns(): Promise<ChaosRun[]> {
    // GET /admin/v1/chaos/runs 감사 투영(id·action·target·experiment_id·created_at). state·blast_radius가
    // 없어 축소 뷰모델로 좁힌다. 활성 여부는 action 스트림(inject vs clear)을 접어 재구성한다.
    // experiment_id는 서버가 UUID로 발급해 카탈로그 id(E1~E9)와 직접 조인이 불가하다. 주입 시
    // wireBinding이 발급한 target 접두(e1-toxic·e8-assault)에서 id를 파생해 조인하고,
    // 고정 노드명을 쓰는 kill-node류는 action 기반 폴백 라벨을 쓴다.
    const res = await this.http<WireDataEnvelope<WireChaosRun>>("/admin/v1/chaos/runs");
    const rows = res.data ?? [];
    const cleared = new Set<string>();
    for (const r of rows) {
      if ((r.action ?? "").includes("clear")) cleared.add(r.target ?? "");
    }
    return rows.map((r, i) => {
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

  async getNotices(): Promise<Notice[]> {
    // 서버는 {data:[...]} 엔벌로프(active 포함). body·published_at·severity·active를 매핑한다.
    const res = await this.http<WireDataEnvelope<WireNotice>>("/admin/v1/config/notices");
    return (res.data ?? []).map((n) => ({
      id: String(n.id ?? ""),
      title: n.title ?? "",
      body: n.body ?? "",
      severity: normSeverity(n.severity),
      active: n.active !== false,
      createdAt: n.published_at ?? "",
      createdBy: n.published_by ?? "",
    }));
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

  async getAudit(): Promise<AuditEntry[]> {
    // 서버는 {data:[...]} 엔벌로프이고 로그 id·표시명·request_id는 프로젝션에 없다. rowKey용
    // 합성 id를 부여하고 표시명은 operator_id로 대체한다. approval_id는 없으면 null.
    const res = await this.http<WireDataEnvelope<WireAudit>>("/admin/v1/audit");
    return (res.data ?? []).map((e, i) => {
      const approval = e.approval_id;
      return {
        id: `aud-${e.created_at ?? ""}-${i}`,
        at: e.created_at ?? "",
        operatorId: e.operator_id ?? "",
        operatorName: e.operator_id ?? "",
        action: e.action ?? "",
        target: e.target ?? "",
        approvalId: approval == null || approval === "" ? null : String(approval),
        result: e.result === "DENIED" ? "DENIED" : "OK",
        requestId: "",
      };
    });
  }
}
