/**
 * lib/admin/types.ts. 관리 평면 도메인 타입 (docs/관리자_설계.md v1.1 + 백엔드 admin 계약 미러).
 * 고객 평면(lib/types.ts)과 자격·세션·색 도메인을 공유하지 않는다.
 * 금액·수수료·수량·파라미터는 문자열(NUMERIC(40,18) 정밀도 보존), float 금지.
 */

export const ADMIN_SCHEMA_VERSION = "1.0" as const;

/**
 * 커서 페이지 한 쪽(API 설계 §2-6).
 *
 * 고객 평면 Page<T>와 필드명이 다르다. 그쪽은 와이어 이름을 그대로 쓰고 여기는 관리
 * 도메인 타입 관례(카멜)를 따른다. 두 평면은 타입을 공유하지 않는다(이 파일 머리말).
 */
/** 쪽 요청. cursor 없으면 첫 쪽이다 */
export interface PageOpts {
  cursor?: string | null;
  limit?: number;
}

export interface AdminPage<T> {
  data: T[];
  nextCursor: string | null;
  hasMore: boolean;
}

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
  | "operators.roles"
  | "members.close";

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
  | { action: "operators.create"; operatorId: string; displayName: string }
  | { action: "operators.status"; operatorId: string; status: OperatorStatus }
  | { action: "operators.roles"; operatorId: string; grant: RoleGrant; role: AdminRole }
  | { action: "members.close"; externalId: string; email: string };

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

/** invariant_checks 원소. 항목 수는 서버가 정한다(실측 26종) */
export interface InvariantCheck {
  name: string;
  label: string;
  ok: boolean;
  violatedRows: number;
  detail: string;
  checkedAt: string;
}

/**
 * 복구 판정. UNKNOWN은 통과가 아니다. 표가 비었거나 계측값이 NULL이면 판정 대상이
 * 없다는 뜻이고, 그것을 통과로 표시하면 공허 통과가 화면에 통과로 뜬다(API§6-C).
 */
export type RecoveryVerdict = "PASS" | "FAIL" | "UNKNOWN";

/**
 * 주행 한 건의 판정. 집계와 달리 미판정이 없다. 재생은 예외 없이 끝났거나 아니거나
 * 둘 중 하나이고, 표가 비었는지는 행 단위에서 물을 수 없는 질문이기 때문이다.
 * 이 별칭이 없으면 렌더가 UNKNOWN을 받을 수 있는 타입인데 이진으로 칠한다.
 */
export type RunVerdict = "PASS" | "FAIL";

/** recovery_runs 원소(재생 창·재생 소요·재현 판정). RTO·RPO는 이 표에 없다 */
export interface RecoveryRun {
  id: string;
  startedAt: string;
  /** 재생 소요. 페일오버 RTO가 아니다(원천 harness.failover_runs.rto_seconds) */
  durationMs: number;
  /** RPO는 recovery_runs에 없다. 원천은 harness.failover_runs라 이 행에서는 미측정이다 */
  rpoMs: number | null;
  replayedFromSeq: number;
  replayedToSeq: number;
  fpReplay: string;
  fpLive: string;
  matched: boolean;
  verdict: RunVerdict;
}

export interface HaRecovery {
  runs: RecoveryRun[];
  lastRtoMs: number | null;
  /**
   * 서버 verdict.overall. 표 전체(또는 since 창) 집계다. 종전에는 failover_runs 최신
   * 1행으로 화면 판정을 만들어, 과거 FAIL과 최신 PASS가 함께 있으면 서버는 FAIL인데
   * 화면은 통과를 표시했다. 서버가 없앤 "창 안만 보는 판정"이 화면에 남아 있었다.
   */
  lastVerdict: RecoveryVerdict | null;
  /** 판정 범위. 창을 주지 않으면 all이다 */
  verdictScope: "all" | "since";
  /** 재생 축 판정. overall과 갈릴 수 있어 복구 이력 패널 헤더에 따로 띄운다 */
  replayVerdict: RecoveryVerdict | null;
  /** 페일오버 축 판정 */
  failoverVerdict: RecoveryVerdict | null;
}

/* ===== 봇 플릿 (§2-1, /admin/v1/sim/*) ===== */

/**
 * 봇 제어 조작의 반영 경로(API§6-A `apply_path`). `queued`는 실패가 아니라 지연이다.
 * 관리 평면과 봇 JVM이 물리 분리된 배치에서 서버가 sim_commands에 의도를 적재하고
 * 봇이 폴링해 적용한다. 화면이 이것을 즉시 완료로 단정하면 봇이 죽어 명령이 PENDING으로
 * 남은 상태에서도 성공을 표시한다.
 */
export interface ControlApply {
  applyPath: "local" | "queued";
  queuedCommandId: number;
}

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

/** 시나리오 프리셋 이름(§6-A v2.31). 정본 표기는 하이픈이다 */
export type ScenarioName = "steady" | "volatile" | "ramp" | "spike" | "thundering-herd";

/** POST /admin/v1/sim/scenarios/{name}/activate 응답의 markets[] 원소 */
export interface ScenarioMarketResult {
  market: string;
  /** 기준선 미등록 마켓은 대수를 건드리지 않고 bias만 받는다 */
  scaleApplied: boolean;
  applied: FleetScaleTargets;
  effectiveBias: string;
}

/**
 * 프리셋 활성화 응답 미러(§6-A v2.31). targetOps·baseOps는 문자열이다(서버가 문자열로 준다).
 * targetOps는 설정된 상한이고 달성 처리량이 아니다. 실제 발행량은 min(봇 수요, targetOps)다.
 */
export interface ScenarioResult {
  scenario: ScenarioName;
  /** 봇 거버너가 이 노드에 있으면 즉시 적용, 없으면 명령 큐를 거친다(API§6-A apply_path) */
  applyPath: "local" | "queued";
  /** queued일 때 sim_commands 행 id. local이면 0 */
  queuedCommandId: number;
  /** queued 경로에서는 관리 평면이 모르는 값이라 서버가 내지 않는다 */
  targetOps: string | null;
  baseOps: string | null;
  autoBalance: boolean;
  targetNetBias: string;
  markets: ScenarioMarketResult[] | null;
  clamped: boolean | null;
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
  /**
   * 폭발 반경 등급. 경로(injectPath)에서 유추하지 않는다. 같은 network 주입이라도 응답
   * 지연(E1)과 노드 간 분할(E5)은 멈추는 범위가 다르다. 위험은 실험의 성질이다.
   * high: 체결 경로가 멈춘다 / medium: 의존 계층이 끊긴다 / low: 지연이 낀다
   */
  severity: "high" | "medium" | "low";
  /**
   * kill-node 계열의 주입 원시연산. E7은 pause라 kill과 파괴 강도가 다르다.
   * 콘솔이 상수로 박으면 운영자가 고른 것보다 파괴적인 연산이 나간다.
   */
  method?: "docker_kill" | "docker_pause";
  /** network 계열 toxic 종류. 실험마다 다르고 상수로 접으면 화면 라벨과 실제가 갈린다 */
  toxicType?: "latency" | "timeout" | "reset_peer";
}

/** 진행·이력 실험 원소 */
export interface ChaosRun {
  id: string;
  /** 해제 요청 target. 정본 §3-6은 주입 시 발급한 이름(toxic 등)을 해제 target으로 재사용한다.
   * sim은 run id, http는 감사 행 target(주입 이름)이다. 화면 chaos.clear payload는 id가 아니라 이 값을 쓴다 */
  clearKey: string;
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

/* ===== 출금 승인 큐 (API 설계 §4-E) ===== */

export type AdminTransferStatus = "REQUESTED" | "PENDING" | "COMPLETED" | "FAILED" | "CANCELED";

/** GET /admin/v1/transfers 항목. 사용자 응답과 달리 요청자 이메일이 붙는다 */
export interface AdminTransfer {
  externalId: string;
  direction: "DEPOSIT" | "WITHDRAW";
  asset: string;
  amount: string;
  status: AdminTransferStatus;
  reason: string | null;
  userEmail: string;
  createdAt: number;
  updatedAt: number;
}

/* ===== 문의 답변 큐 (§4-G) ===== */

export interface AdminInquiry {
  externalId: string;
  category: "TRADE" | "TRANSFER" | "ACCOUNT" | "ETC";
  title: string;
  body: string;
  status: "OPEN" | "ANSWERED";
  answer: string | null;
  answeredAt: number | null;
  userEmail: string;
  createdAt: number;
}

/* ===== 회원 운영 (§6-E, /admin/v1/members/*) ===== */

/** users.status CHECK 어휘. CLOSED에서 나가는 전이는 없다 */
export type MemberStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";

export interface AdminMember {
  externalId: string;
  email: string;
  displayName: string | null;
  status: MemberStatus;
  createdAt: number;
}

/**
 * 회원 상세(§6-E). 활동 요약은 폐쇄 전 판단 근거다. openOrderCount가 0이 아니면
 * 폐쇄가 그 주문들을 취소한다.
 */
export interface AdminMemberDetail extends AdminMember {
  updatedAt: number;
  openOrderCount: number;
  lastOrderAt: number | null;
  assetKinds: number;
}

/** 전이 결과. 무엇이 함께 정리됐는지 운영자에게 되돌려 준다 */
export interface MemberTransition {
  externalId: string;
  status: MemberStatus;
  canceledOrders: number;
  revokedSessions: number;
}

/* ===== 커뮤니티 운영 (§6-D, /admin/v1/community/*) ===== */

/** reports.status CHECK 어휘. PENDING만 처리 대기고 나머지 셋은 종결이다 */
export type ReportStatus = "PENDING" | "REVIEWED" | "ACTIONED" | "DISMISSED";

/** 신고 처리 결과. PENDING에서만 전이한다 */
export type ReportResolution = "REVIEWED" | "ACTIONED" | "DISMISSED";

/** reports.reason CHECK 어휘 */
export type ReportReason = "SPAM" | "ABUSE" | "ILLEGAL" | "ETC";

/** posts·comments.status CHECK 어휘. DELETED는 종료 상태라 되돌리지 못한다 */
export type ContentStatus = "VISIBLE" | "HIDDEN" | "DELETED";

/** 모더레이션 액션. DELETE는 비가역이다(게시판_설계 §4.1) */
export type ModerationAction = "HIDE" | "UNHIDE" | "DELETE";

/**
 * 신고 큐 한 행(GET /admin/v1/community/reports).
 * targetExternalId는 처리 액션이 받는 식별자다. reportId·targetId는 내부 BIGINT이고
 * 관리 평면 커서 축으로만 쓴다(§2-6 각주: 내부 id를 이미 공개 식별자로 쓰는 표).
 * 대상이 조인되지 않으면 요약 필드가 null이다.
 */
export interface CommunityReport {
  reportId: number;
  targetType: "POST" | "COMMENT";
  targetId: number;
  targetExternalId: string | null;
  targetStatus: ContentStatus | null;
  targetExcerpt: string | null;
  targetAuthor: string | null;
  reporterId: number;
  reason: ReportReason;
  detail: string | null;
  status: ReportStatus;
  createdAt: number;
}

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

/** DDL CHECK (result IN ('OK','DENIED','ERROR')) 미러. ERROR는 처리 중 5xx */
export type AuditResult = "OK" | "DENIED" | "ERROR";

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
