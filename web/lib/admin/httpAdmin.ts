/**
 * lib/admin/httpAdmin.ts. Spring 관리 평면(/admin/v1/*) REST 어댑터.
 * 전송 계층 mTLS는 브라우저 클라이언트 인증서가 담당하고(fetch에 투명), 앱 계층은 mTLS
 * 신원에서 유래한 단기 세션 토큰을 Bearer로 싣는다(§1-3). 세션 토큰은 메모리에만 둔다.
 * 데모 기본은 SimAdminApi이며 이 어댑터는 NEXT_PUBLIC_DATASOURCE=http에서만 활성이다.
 *
 * 완성 범위: 세션·읽기 전용 모니터링(ha·sim status·chaos·config·operators·audit)과
 * sim 제어(start·stop·pause·rate)는 백엔드 경로·바디와 일치한다.
 *
 * 미완 범위: 2인 승인 라이프사이클과 봇 생성은 백엔드 계약과 정합하지 않아 미구현이다.
 *   - 백엔드 승인은 발급형(§3-6)이다. 승인자가 POST /admin/v1/approvals에
 *     {requester_operator_id, action, target}를 실어 approval_token을 발급하고, 요청자가
 *     파괴 액션 엔드포인트 바디에 approval_token을 실어 소각한다. AdminApi 인터페이스의
 *     requestApproval·approve·reject·execute·listApprovals는 SimAdminApi의 인메모리
 *     라이프사이클을 전제한 형태라 이 발급형 모델에 그대로 대응하지 않는다(요청자·승인자
 *     역할이 역전되고 approve·reject·execute·list 엔드포인트가 백엔드에 없다). 인터페이스를
 *     발급형으로 재설계하기 전까지 이 메서드들은 not_implemented로 명시 실패한다.
 *   - 봇 생성 엔드포인트는 백엔드에 없다(SimAdminController는 status·rate·{command}·
 *     bots/{id}/params만 제공). createBot은 not_implemented로 실패한다.
 */

import { AdminError, type AdminApi } from "./api";
import { CHAOS_CATALOG } from "./catalog";
import type {
  AdminOperator,
  AdminOverview,
  AdminSession,
  ApprovalPayload,
  ApprovalRequest,
  AuditEntry,
  Bot,
  BotType,
  ChaosExperimentDef,
  ChaosRun,
  ConfigMarket,
  DemoClientCert,
  DestructiveAction,
  HaRecovery,
  HaStatus,
  InvariantCheck,
  Notice,
  SimStatus,
} from "./types";

interface ErrorEnvelope {
  error?: { name?: string; message?: string };
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
      let code = res.status === 401 ? "jwt_verification" : res.status === 403 ? "admin_forbidden" : "server_error";
      let message = "관리 요청에 실패했습니다";
      try {
        const body = (await res.json()) as ErrorEnvelope;
        if (body.error?.name) code = body.error.name;
        if (body.error?.message) message = body.error.message;
      } catch {
        // 비 JSON 응답은 상태코드 기반 기본값 유지
      }
      throw new AdminError(code, message);
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  /* ===== 인증 ===== */

  listDemoCerts(): DemoClientCert[] {
    // 운영 mTLS는 OS·브라우저 인증서 저장소를 쓴다. 앱이 인증서를 열거하지 않는다
    return [];
  }

  async establishSession(): Promise<AdminSession> {
    const s = await this.http<AdminSession>("/admin/v1/session", { method: "POST" });
    this.session = s;
    this.emit();
    return s;
  }

  getSession(): AdminSession | null {
    if (this.session && Date.now() > new Date(this.session.expiresAt).getTime()) {
      this.session = null;
      this.emit();
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

  /* ===== 이중 승인 ===== */
  // 백엔드는 발급형 승인이라 아래 라이프사이클 메서드는 인터페이스 재설계 전까지 미구현이다.
  // 파일 헤더 미완 범위 참고. 조용한 400·404 대신 명시 실패로 오인 사용을 차단한다.

  private static notReconciled(): never {
    throw new AdminError(
      "not_implemented",
      "HTTP 어댑터의 2인 승인 라이프사이클은 백엔드 발급형 계약과 미정합이다. SimAdminApi로 시연하거나 발급형으로 재설계 후 사용한다.",
    );
  }

  requestApproval(_action: DestructiveAction, _payload: ApprovalPayload): Promise<ApprovalRequest> {
    return HttpAdminApi.notReconciled();
  }

  approve(_approvalId: string): Promise<ApprovalRequest> {
    return HttpAdminApi.notReconciled();
  }

  reject(_approvalId: string): Promise<ApprovalRequest> {
    return HttpAdminApi.notReconciled();
  }

  execute(_approvalId: string): Promise<void> {
    return HttpAdminApi.notReconciled();
  }

  listApprovals(): Promise<ApprovalRequest[]> {
    return HttpAdminApi.notReconciled();
  }

  /* ===== 모니터링 ===== */

  async getOverview(): Promise<AdminOverview> {
    // 종합 요약은 개별 모니터링 엔드포인트를 합성한다(전용 엔드포인트 없음).
    // 승인 목록 엔드포인트가 없어(발급형 계약) pendingApprovals는 HTTP로 집계하지 않는다.
    const [ha, inv, sim, chaos, markets, rec] = await Promise.all([
      this.getHaStatus(),
      this.getInvariants(),
      this.getSimStatus(),
      this.getChaosRuns(),
      this.getConfigMarkets(),
      this.getRecovery(),
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
      pendingApprovals: 0, // 발급형 승인에는 목록 엔드포인트가 없어 HTTP 집계 불가
      suspendedMarkets: markets.filter((m) => m.state === "SUSPENDED").length,
      generatedAt: ha.generatedAt,
    };
  }

  getHaStatus(): Promise<HaStatus> {
    return this.http<HaStatus>("/admin/v1/ha/status");
  }

  getRecovery(): Promise<HaRecovery> {
    return this.http<HaRecovery>("/admin/v1/ha/recovery");
  }

  getInvariants(): Promise<InvariantCheck[]> {
    return this.http<InvariantCheck[]>("/admin/v1/ha/invariants");
  }

  /* ===== 봇 플릿 ===== */

  getSimStatus(): Promise<SimStatus> {
    return this.http<SimStatus>("/admin/v1/sim/status");
  }

  simStart(): Promise<void> {
    return this.http<void>("/admin/v1/sim/start", { method: "POST" });
  }

  simStop(): Promise<void> {
    return this.http<void>("/admin/v1/sim/stop", { method: "POST" });
  }

  simPause(): Promise<void> {
    return this.http<void>("/admin/v1/sim/pause", { method: "POST" });
  }

  simSetRate(rate: number): Promise<void> {
    // 백엔드 RateBody 키는 target_ops이며 문자열로 파싱한다(NUMERIC 정밀도 계약)
    return this.http<void>("/admin/v1/sim/rate", {
      method: "PUT",
      body: JSON.stringify({ target_ops: String(rate) }),
    });
  }

  createBot(_type: BotType, _market: string): Promise<Bot> {
    // 백엔드에 봇 생성 엔드포인트가 없다(SimAdminController 열거 참고). 미구현으로 명시 실패한다
    throw new AdminError(
      "not_implemented",
      "HTTP 어댑터는 봇 생성을 지원하지 않는다. 백엔드에 해당 엔드포인트가 없다.",
    );
  }

  /* ===== 카오스 ===== */

  getChaosCatalog(): ChaosExperimentDef[] {
    return CHAOS_CATALOG.slice();
  }

  getChaosRuns(): Promise<ChaosRun[]> {
    return this.http<ChaosRun[]>("/admin/v1/chaos/experiments");
  }

  /* ===== 마켓 설정 ===== */

  getConfigMarkets(): Promise<ConfigMarket[]> {
    return this.http<ConfigMarket[]>("/admin/v1/config/markets");
  }

  getNotices(): Promise<Notice[]> {
    return this.http<Notice[]>("/admin/v1/config/notices");
  }

  postNotice(title: string, body: string): Promise<Notice> {
    return this.http<Notice>("/admin/v1/config/notices", { method: "POST", body: JSON.stringify({ title, body }) });
  }

  /* ===== 운영자·감사 ===== */

  getOperators(): Promise<AdminOperator[]> {
    return this.http<AdminOperator[]>("/admin/v1/operators");
  }

  getAudit(): Promise<AuditEntry[]> {
    return this.http<AuditEntry[]>("/admin/v1/audit");
  }
}
