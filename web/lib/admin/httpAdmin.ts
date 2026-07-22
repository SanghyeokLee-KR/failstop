/**
 * lib/admin/httpAdmin.ts. Spring 관리 평면(/admin/v1/*) REST 어댑터.
 * 전송 계층 mTLS는 브라우저 클라이언트 인증서가 담당하고(fetch에 투명), 앱 계층은 mTLS
 * 신원에서 유래한 단기 세션 토큰을 Bearer로 싣는다(§1-3). 세션 토큰은 메모리에만 둔다.
 * 데모 기본은 SimAdminApi이며 이 어댑터는 NEXT_PUBLIC_DATASOURCE=http에서만 활성이다.
 *
 * 완성 범위: 세션·읽기 전용 모니터링(ha·sim status·chaos·config·operators·audit)과
 * sim 제어(start·stop·pause·rate). 읽기 응답은 서버 엔벌로프(data·checks·catalog·lease 등)를
 * 언랩하고 snake_case를 도메인 뷰모델로 매핑한다. 무검사 캐스팅은 배열 기대 지점에서
 * TypeError(관리 콘솔 진입 크래시)를 유발하므로 쓰지 않는다.
 *
 * 서버 미노출 항목은 안전 기본값으로 좁힌다. HA status는 노드 토폴로지·큐·지연을 싣지 않아
 * 매칭엔진 리더를 단일 노드로 합성한다. 카오스 이력·공지 본문·감사 로그 id는 서버 조회
 * 엔드포인트·프로젝션에 없어 각각 빈 목록·빈 값으로 둔다.
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
  HaNode,
  HaRecovery,
  HaStatus,
  InvariantCheck,
  MarketSimState,
  Notice,
  RecoveryRun,
  SimStatus,
  TradeFlash,
} from "./types";

/** 마켓 심볼 -> 한글명. 서버 config/markets는 한글명을 싣지 않아 표시용으로만 조인한다 */
const COIN_NAME = new Map(COIN_META.map((c) => [c.market, c.koreanName]));
/** 불변식 check_name -> 라벨. 서버 invariants는 라벨을 싣지 않는다 */
const INVARIANT_LABEL = new Map(INVARIANT_DEFS.map((d) => [d.name, d.label]));

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

/* ===== 관리 읽기 엔벌로프 와이어 스키마(snake_case 원본). 언랩·매핑 대상 ===== */

/** {schema_version, data:[...]} 공통 목록 엔벌로프(operators·audit·config) */
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
/** GET /admin/v1/ha/recovery recovery_runs 원소(결정적 재현). fp·rpo 미포함 */
interface WireRecoveryRun {
  market_id?: number;
  replayed_from_seq?: number;
  replayed_to_seq?: number;
  matched?: boolean | string;
  duration_ms?: number | string;
  created_at?: string;
}
/** GET /admin/v1/ha/recovery failover_runs 원소(장애조치 드릴 RTO·RPO·판정) */
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
/** GET /admin/v1/ha/invariants checks 원소 */
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
/** GET /admin/v1/config/notices data 원소. body·발행시각 미포함 */
interface WireNotice {
  id?: number;
  title?: string;
  severity?: string;
  active?: boolean;
  published_by?: string;
}
/** GET /admin/v1/operators data 원소 */
interface WireOperator {
  operator_id?: string;
  display_name?: string;
  status?: string;
  roles?: string[];
}
/** GET /admin/v1/audit data 원소. 로그 id·표시명·request_id 미포함 */
interface WireAudit {
  operator_id?: string;
  action?: string;
  target?: string;
  result?: string;
  approval_id?: number | string;
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
      // 평면 429는 대기 초를 힌트로 노출한다. 서버 message는 초를 담지 않을 수 있어 힌트를 우선한다
      if (rateLimited && (code === "too_many_requests" || code === "rate_limited")) {
        message = rateLimitMessage(retryAfter);
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

  async getHaStatus(): Promise<HaStatus> {
    // 서버 status는 leader_lease와 워터마크만 준다. 노드 토폴로지·시퀀서 큐·지연·Hikari는
    // 미노출이라 매칭엔진 리더를 단일 노드로 합성하고, 미노출 지표는 안전 기본값으로 좁힌다.
    // 무검사 캐스팅은 nodes를 undefined로 남겨 getOverview의 ha.nodes.some에서 크래시한다.
    const res = await this.http<WireHaStatus>("/admin/v1/ha/status");
    const lease = res.lease ?? {};
    const epoch = Number(lease.epoch ?? 0);
    const seq = Number(res.command_watermark ?? 0);
    const trades = Number(res.trades_count ?? 0);
    const holder = lease.holder ?? "";
    const node: HaNode = {
      id: holder || "matching-engine",
      role: "leader",
      epoch: Number.isFinite(epoch) ? epoch : 0,
      seq: Number.isFinite(seq) ? seq : 0,
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
      tradesTotal: Number.isFinite(trades) ? trades : 0,
      hikariPending: 0,
      generatedAt: isoNow(),
    };
  }

  async getRecovery(): Promise<HaRecovery> {
    // 서버는 결정적 재현(recovery_runs)과 장애조치 드릴(failover_runs)을 분리한다. 복구 표는
    // 재현 seq·지문 컬럼을 쓰므로 recovery_runs를 행으로 매핑하고, 대시보드 RTO·판정은
    // 권위값인 failover_runs 최신을 우선한다. 무검사 캐스팅은 runs를 undefined로 남긴다.
    const res = await this.http<WireRecovery>("/admin/v1/ha/recovery");
    const runs: RecoveryRun[] = (res.recovery_runs ?? []).map((r, i) => {
      const matched = r.matched === true;
      const duration = Number(r.duration_ms ?? 0);
      return {
        id: `rec-${r.created_at ?? ""}-${i}`,
        startedAt: r.created_at ?? "",
        durationMs: Number.isFinite(duration) ? duration : 0,
        // recovery_runs는 결정적 재현이라 RPO 0. 서버 프로젝션에 fp_replay·fp_live는 없다
        rpoMs: 0,
        replayedFromSeq: Number(r.replayed_from_seq ?? 0),
        replayedToSeq: Number(r.replayed_to_seq ?? 0),
        fpReplay: "",
        fpLive: "",
        matched,
        verdict: matched ? "PASS" : "FAIL",
      };
    });
    const drill = (res.failover_runs ?? [])[0];
    const drillRto = drill ? Number(drill.rto_seconds) : Number.NaN;
    const lastRtoMs =
      drill && Number.isFinite(drillRto) ? Math.round(drillRto * 1000) : runs[0]?.durationMs ?? null;
    const drillVerdict = drill?.verdict === "PASS" || drill?.verdict === "FAIL" ? drill.verdict : null;
    const lastVerdict = drillVerdict ?? runs[0]?.verdict ?? null;
    return { runs, lastRtoMs, lastVerdict };
  }

  async getInvariants(): Promise<InvariantCheck[]> {
    // 서버는 {checks:[...]} 엔벌로프. 무검사 배열 캐스팅은 getOverview의 inv.filter에서 크래시한다.
    // 라벨은 서버 미제공이라 정적 정의(INVARIANT_DEFS)로 조인한다.
    const res = await this.http<WireInvariants>("/admin/v1/ha/invariants");
    return (res.checks ?? []).map((c) => {
      const name = c.check_name ?? "";
      const violated = Number(c.violated_rows ?? 0);
      const ok = c.ok === true;
      const rows = Number.isFinite(violated) ? violated : 0;
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
    // 백엔드 /chaos/experiments는 정적 카탈로그(정의, {catalog:[...]})만 반환하고 실험 이력(run)
    // 조회 엔드포인트가 없다(ChaosAdminController는 catalog·주입·해제만 제공). 카탈로그는
    // getChaosCatalog가 소비하므로 이력은 빈 목록으로 정직하게 둔다. 카탈로그를 run으로
    // 오매핑하면 state 없는 객체가 getOverview의 chaos.filter를 통과해 의미가 왜곡된다.
    return Promise.resolve([]);
  }

  /* ===== 마켓 설정 ===== */

  async getConfigMarkets(): Promise<ConfigMarket[]> {
    // 서버는 {data:[{market,status,maker_fee,taker_fee}]}. 무검사 배열 캐스팅은 getOverview의
    // markets.filter와 설정 페이지 .map에서 크래시한다. 한글명은 서버 미제공이라 심볼로 조인한다.
    const res = await this.http<WireDataEnvelope<WireConfigMarket>>("/admin/v1/config/markets");
    return (res.data ?? []).map((m) => {
      const symbol = m.market ?? "";
      return {
        symbol,
        koreanName: COIN_NAME.get(symbol) ?? symbol,
        // 서버 status는 ACTIVE·HALTED·DELISTED. 프론트 이분 도메인(ACTIVE·SUSPENDED)으로 좁힌다
        state: m.status === "ACTIVE" ? "ACTIVE" : "SUSPENDED",
        makerFee: m.maker_fee ?? "0",
        takerFee: m.taker_fee ?? "0",
      };
    });
  }

  async getNotices(): Promise<Notice[]> {
    // 서버는 {data:[...]} 엔벌로프. 목록 프로젝션은 body·발행시각을 싣지 않아 가용 필드만 매핑한다.
    const res = await this.http<WireDataEnvelope<WireNotice>>("/admin/v1/config/notices");
    return (res.data ?? []).map((n) => ({
      id: String(n.id ?? ""),
      title: n.title ?? "",
      body: "",
      createdAt: "",
      createdBy: n.published_by ?? "",
    }));
  }

  async postNotice(title: string, body: string): Promise<Notice> {
    // 서버 응답은 {notice_id}만 준다. 목록 스키마에 맞춰 요청 값과 세션·수신 시각으로 뷰모델을 채운다.
    const res = await this.http<{ notice_id?: number }>("/admin/v1/config/notices", {
      method: "POST",
      body: JSON.stringify({ title, body }),
    });
    return {
      id: String(res.notice_id ?? ""),
      title,
      body,
      createdAt: isoNow(),
      createdBy: this.session?.operatorId ?? "",
    };
  }

  /* ===== 운영자·감사 ===== */

  async getOperators(): Promise<AdminOperator[]> {
    // 서버는 {data:[{operator_id,display_name,status,roles}]}. 무검사 배열 캐스팅은 운영자
    // 페이지 .map에서 크래시한다. roles는 서버가 이미 정본 역할 enum 이름으로 준다.
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
