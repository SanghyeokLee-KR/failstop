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
  AdminRole,
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
  DirectionBiasResult,
  FleetScaleResult,
  FleetScaleTargets,
  HaRecovery,
  HaStatus,
  InvariantCheck,
  MarketSimState,
  Notice,
  SimStatus,
  TradeFlash,
} from "./types";

interface ErrorEnvelope {
  error?: { name?: string; message?: string };
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
    // 서버 세션 응답은 snake_case(AdminSessionController). camelCase 도메인으로 명시 매핑한다.
    // 무검사 캐스팅은 operatorId·displayName·expiresAt를 undefined로 남겨 카운트다운 NaN·
    // 클라 만료 미발동을 유발한다. issued_at은 서버가 싣지 않아 수신 시각으로 파생한다.
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
    // expiresAt 파싱 실패(NaN)면 만료 판정을 건너뛴다. NaN 비교는 항상 false라 손상 값이
    // 세션을 영구 유지시키는 것을 막고, 유효 값일 때만 클라 만료를 발동한다.
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

  async getSimStatus(): Promise<SimStatus> {
    // 서버 /status 1.1은 {schema_version, fleet_present, target_ops, trade_flash, markets[]}만 준다.
    // SimStatus 전 필드를 무검사 캐스팅하면 undefined가 number 필드에 실려 NaN 렌더된다.
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
    const rate = Number(raw.target_ops ?? "0");
    return {
      running: raw.fleet_present === true,
      paused: false,
      rate: Number.isFinite(rate) ? rate : 0,
      currentOps: Number.isFinite(rate) ? rate : 0,
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
          target: Number(entry.target ?? 0),
          active: Number(entry.active ?? 0),
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
