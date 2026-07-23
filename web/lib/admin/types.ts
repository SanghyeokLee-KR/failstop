/**
 * lib/admin/types.ts. 관리 평면 도메인 타입 (docs/관리자_설계.md v1.1 + 백엔드 admin 계약 미러).
 * 고객 평면(lib/types.ts)과 자격·세션·색 도메인을 공유하지 않는다.
 * 금액·수수료·수량·파라미터는 문자열(NUMERIC(40,18) 정밀도 보존), float 금지.
 */

export const ADMIN_SCHEMA_VERSION = "1.0" as const;

/* ===== 인증·운영자 (§1-2, §1-3) ===== */

/** 정본 5역할. 임의 축약 금지 */
export type AdminRole = "VIEWER" | "BOT_OPS" | "CHAOS_OPS" | "MEMBER_MOD" | "SUPER";

export const ADMIN_ROLES: AdminRole[] = ["VIEWER", "BOT_OPS", "CHAOS_OPS", "MEMBER_MOD", "SUPER"];

export type OperatorStatus = "ACTIVE" | "DISABLED";

/** 역할 변경 방향. POST /operators/{id}/roles는 부여·회수 단건 모델이다(정본 API §운영자) */
export type RoleGrant = "GRANT" | "REVOKE";

/** 운영자 레지스트리 원소(V5 admin_operators). mTLS 신원 기반, password_hash 없음 */
export interface AdminOperator {
  operatorId: string; // 인증서 CN 유래(자가신고 금지)
  displayName: string;
  roles: AdminRole[];
  status: OperatorStatus;
}

/**
 * 데모 mTLS 클라이언트 인증서(§1-6). 브라우저 인증서 저장소 설치분을 모사한다.
 * certId=CN=operatorId. 사설키는 각 운영자 워크스테이션 전제이며 데모는 인증서 선택으로 갈음한다.
 */
export interface DemoClientCert {
  certId: string;
  operatorId: string;
  subjectCn: string;
  issuer: string; // 데모 CA
}

/** mTLS 신원에서 유래한 단기 관리자 세션(§1-3). 관리 평면 전용 */
export interface AdminSession {
  operatorId: string;
  displayName: string;
  roles: AdminRole[];
  token: string;
  issuedAt: string;
  expiresAt: string;
}

/* ===== 이중 승인: 발급형 (§3-6, §6-B 완결 열거) ===== */

/**
 * approval_token 필수 파괴 액션(§3-6 완결 열거). 콘솔 의미 액션이며 백엔드 엔드포인트·바인딩
 * 문자열과는 별도다(httpAdmin.wireBinding이 chaos.inject를 kill_node/network/app로 분해). 신규
 * 추가: config.market.fees, operators.create, operators.status(v2.12 확장).
 */
export type DestructiveAction =
  | "chaos.inject"
  | "chaos.clear"
  | "sim.bots.params"
  | "config.market.halt"
  | "config.market.resume"
  | "config.market.fees"
  | "operators.create"
  | "operators.status"
  | "operators.roles";

/**
 * admin_approvals.state 정본 어휘(V5__admin.sql CHECK, §6-B). 발급형이라 원장 최초 상태가
 * ISSUED이고 그 시점에 approverId(발급 주체)가 항상 존재한다. 발급 전 "요청"은 서버 상태가
 * 아니라 요청 운영자 브라우저의 로컬 의도다(store 보관소). ISSUED -> CONSUMED|EXPIRED|REJECTED.
 */
export type ApprovalState = "ISSUED" | "CONSUMED" | "EXPIRED" | "REJECTED";

export type ApprovalPayload =
  | { action: "chaos.inject"; experimentId: string }
  | { action: "chaos.clear"; runId: string }
  | { action: "sim.bots.params"; botId: string; params: Record<string, string> }
  | { action: "config.market.halt"; symbol: string }
  | { action: "config.market.resume"; symbol: string }
  | { action: "config.market.fees"; symbol: string; makerFee: string; takerFee: string }
  | { action: "operators.create"; operatorId: string; displayName: string; roles: AdminRole[] }
  | { action: "operators.status"; operatorId: string; status: OperatorStatus }
  | { action: "operators.roles"; operatorId: string; grant: RoleGrant; role: AdminRole };

/**
 * 승인 원장 프로젝션(GET /admin/v1/approvals). approval_token은 응답에서 제외되므로 여기에 없다.
 * 발급형이라 approverId(발급 주체)는 항상 존재한다. payload도 원장 프로젝션에 없어(action+target만)
 * 여기에 두지 않는다. 소각에 필요한 payload·token은 발급 응답(IssuedApproval)에만 실린다.
 * requesterId <> approverId를 서버가 인증서 CN으로 강제(반결탁, §3-6).
 */
export interface ApprovalRequest {
  id: string;
  action: DestructiveAction;
  targetKey: string; // 콘솔 UI 중복 방지 키(action+대상). 바인딩 target과 다르다
  targetLabel: string;
  requesterId: string;
  requesterName: string;
  approverId: string;
  approverName: string;
  state: ApprovalState;
  issuedAt: string;
  expiresAt: string;
  consumedAt: string | null;
}

/**
 * 발급 응답(POST /admin/v1/approvals). 원장에 없는 payload와 단회 bearer 시크릿 token을 싣는다.
 * 요청 운영자가 파괴 액션 바디에 token을 실어 소각한다. token은 발급 응답 단일 경로로만 노출되므로
 * 데모(단일 브라우저 운영자 전환)는 store 보관소로 발급자->요청자 전달을 모사한다.
 */
export interface IssuedApproval extends ApprovalRequest {
  payload: ApprovalPayload;
  token: string;
}

/* ===== HA 모니터링 (§2-3, /admin/v1/ha/*) ===== */

export type NodeRole = "leader" | "follower";
export type NodeHealth = "healthy" | "degraded" | "down";

export interface HaNode {
  id: string;
  role: NodeRole;
  epoch: number;
  seq: number;
  followerLagMs: number;
  ownedMarkets: string[];
  health: NodeHealth;
}

export interface HaStatus {
  epoch: number;
  leaderNodeId: string;
  nodes: HaNode[];
  sequencerQueueSize: number;
  orderLatencyP99Ms: number;
  /** exchange_ledger_imbalance 총합. 자산 보존이면 "0" */
  ledgerImbalance: string;
  tradesTotal: number;
  hikariPending: number;
  generatedAt: string;
}

/** invariant_checks 원소(불변식 7종) */
export interface InvariantCheck {
  name: string;
  label: string;
  ok: boolean;
  violatedRows: number;
  detail: string;
  checkedAt: string;
}

export type RecoveryVerdict = "PASS" | "FAIL";

/** recovery_runs 원소(RTO·RPO·failover·재현 판정) */
export interface RecoveryRun {
  id: string;
  startedAt: string;
  durationMs: number; // RTO
  rpoMs: number;
  replayedFromSeq: number;
  replayedToSeq: number;
  fpReplay: string;
  fpLive: string;
  matched: boolean;
  verdict: RecoveryVerdict;
}

export interface HaRecovery {
  runs: RecoveryRun[];
  lastRtoMs: number | null;
  lastVerdict: RecoveryVerdict | null;
}

/* ===== 봇 플릿 (§2-1, /admin/v1/sim/*) ===== */

export type BotType = "MARKET_MAKER" | "TAKER" | "TREND" | "NOISE";

/** BotType 표기 순서·한글 라벨. 화면 공용 단일 출처(페이지·섹션 중복 정의 방지) */
export const BOT_TYPES: BotType[] = ["MARKET_MAKER", "TAKER", "TREND", "NOISE"];
export const BOT_TYPE_LABEL: Record<BotType, string> = {
  MARKET_MAKER: "마켓메이커",
  TAKER: "테이커",
  TREND: "추세",
  NOISE: "노이즈",
};

export interface Bot {
  id: string;
  type: BotType;
  market: string;
  active: boolean;
  ops: number;
  params: Record<string, string>; // 문자열 표기(정밀도 보존)
}

/**
 * POST /admin/v1/sim/bots 응답 미러(§6-A, governor set-point 모델). 개별 인스턴스 id를 발급하지
 * 않고 유형별 목표 대수를 count만큼 상향한다. created=클램프 후 순증분, appliedTarget=클램프 후
 * 해당 유형 목표, fleetTotal=전 마켓 목표 합, clamped=캡 도달, fleetPresent=governor 주입 여부.
 */
export interface CreateBotResult {
  created: number;
  appliedTarget: number;
  fleetTotal: number;
  clamped: boolean;
  fleetPresent: boolean;
}

export interface SimStatus {
  running: boolean;
  paused: boolean;
  rate: number; // 전역 OPS 목표
  currentOps: number;
  botCount: number;
  activeBots: number;
  successRate: string; // "0.9987" 문자열
  avgLatencyMs: number;
  duplicateFillTotal: number;
  bots: Bot[];
  /** 마켓별 방향·대수·측정치(status 1.1, §5-5). 데모·HTTP 공통 */
  markets: MarketSimState[];
  /** 체결 시각효과 강도 전역 저장값(system_config, §5-3). auto 포함 */
  tradeFlash: TradeFlash;
}

/* ===== 시장 생동감: 대수·방향 바이어스·효과 강도 (봇_방향성_거버넌스 §5, 체결_시각효과 §3) ===== */

/** 효과 강도 저장값. auto는 트레이딩 UI 거버너가 관측 강도로 상한 하향(§3-5) */
export type TradeFlash = "off" | "subtle" | "normal" | "strong" | "auto";

export const TRADE_FLASH_VALUES: TradeFlash[] = ["off", "subtle", "normal", "strong", "auto"];

/** 타입별 목표 대수 set-point(§5-1). 서버가 현재 대수와 차이를 생성·제거로 수렴한다 */
export type FleetScaleTargets = Partial<Record<BotType, number>>;

// 프론트 검증 상한. 서버 BotFleetGovernor.defaults()(typeCap 30·marketCap 80)와 정렬한다.
// 서버가 권위적으로 클램프하고 clamped를 반환하므로 프론트 캡은 사전 안내·과대 표시 방지용이다.
/** 타입별 대수 상한 */
export const FLEET_TYPE_CAP = 30;
/** 마켓 합계 상한 */
export const FLEET_MARKET_CAP = 80;

/** PUT /admin/v1/sim/scale 응답 미러(§5-1) */
export interface FleetScaleResult {
  market: string;
  applied: FleetScaleTargets;
  clamped: boolean;
  fleetPresent: boolean;
}

/**
 * PUT /admin/v1/sim/bias 응답 미러(§5-2). net bias·strength는 문자열이다
 * (분수 파라미터 표기·전송은 문자열, float 금지 계약 상속).
 */
export interface DirectionBiasResult {
  market: string;
  targetNetBias: string;
  autoBalance: boolean;
  strength: string;
  effectiveBias: string;
  fleetPresent: boolean;
}

/** SimStatus.markets[] 원소(§5-5). 방향 측정치와 타입별 target/active 대수 */
export interface MarketSimState {
  market: string;
  targetNetBias: string;
  measuredNetBias: string;
  effectiveBias: string;
  autoBalance: { enabled: boolean; strength: string };
  scale: Partial<Record<BotType, { target: number; active: number }>>;
  fillRatio: string;
  tradeFlash: TradeFlash;
}

/* ===== 카오스 (§2-2, /admin/v1/chaos/*) ===== */

export type ChaosState = "RUNNING" | "CLEARED";

/** 정적 실험 카탈로그 원소(E1~E9) */
export interface ChaosExperimentDef {
  id: string;
  label: string;
  injectPath: string;
  blastRadius: string;
  description: string;
}

/** 진행·이력 실험 원소 */
export interface ChaosRun {
  id: string;
  experimentId: string;
  label: string;
  state: ChaosState;
  blastRadius: string;
  grafanaAnnotation: string;
  injectedBy: string;
  startedAt: string;
  clearedAt: string | null;
}

/* ===== 마켓 설정 (§2, /admin/v1/config/*) ===== */

/**
 * 마켓 상태 3분(§6-D). markets.status 정본. DELISTED는 종국 상태라 halt/resume 대상이 아니며
 * (409 market_delisted) 재개 버튼을 노출하지 않는다.
 */
export type MarketState = "ACTIVE" | "HALTED" | "DELISTED";

export interface ConfigMarket {
  symbol: string;
  koreanName: string;
  state: MarketState;
  makerFee: string; // 문자열 수수료율
  takerFee: string;
}

/** 공지 심각도. 배너 위계 구분(§6-D 공지 목록 active 포함) */
export type NoticeSeverity = "info" | "warning" | "critical";

export const NOTICE_SEVERITIES: NoticeSeverity[] = ["info", "warning", "critical"];
export const NOTICE_SEVERITY_LABEL: Record<NoticeSeverity, string> = {
  info: "안내",
  warning: "주의",
  critical: "중요",
};

export interface Notice {
  id: string;
  title: string;
  body: string;
  severity: NoticeSeverity;
  active: boolean;
  createdAt: string; // published_at 매핑
  createdBy: string; // published_by 매핑
}

/** PATCH /admin/v1/config/notices/{id} 부분 갱신. active 토글·소프트 삭제 공용 */
export interface NoticePatch {
  title?: string;
  body?: string;
  severity?: NoticeSeverity;
  active?: boolean;
}

/* ===== 감사 로그 (§3-1, /admin/v1/audit) ===== */

export type AuditResult = "OK" | "DENIED";

export interface AuditEntry {
  id: string;
  at: string;
  operatorId: string;
  operatorName: string;
  action: string;
  target: string;
  approvalId: string | null;
  result: AuditResult;
  requestId: string;
}

/* ===== 종합 상태 요약(/admin 대시보드) ===== */

export interface AdminOverview {
  overallHealth: "success" | "warning" | "danger";
  epoch: number;
  leaderNodeId: string;
  invariantsOk: number;
  invariantsTotal: number;
  lastRtoMs: number | null;
  lastVerdict: RecoveryVerdict | null;
  botsActive: number;
  currentOps: number;
  chaosRunning: number;
  pendingApprovals: number;
  suspendedMarkets: number;
  generatedAt: string;
}
