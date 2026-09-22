/**
 * 관리 평면 도메인 타입. 고객 평면(lib/types.ts)과 자격·세션·색 도메인을 공유하지 않는다.
 * 금액·수수료·수량·파라미터는 문자열이다(NUMERIC(40,18) 정밀도 보존, float 금지).
 */

export const ADMIN_SCHEMA_VERSION = "1.0" as const;

/**
 * 커서 페이지 한 쪽(API 설계 §2-6). 고객 평면 Page<T>와 필드명이 다르다.
 * 그쪽은 와이어 이름을 그대로 쓰고 여기는 관리 도메인 관례(카멜)를 따른다.
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

/* ===== 승인 티켓: 발급형 (§3-6, §6-B 완결 열거) ===== */

/**
 * approval_token 필수 파괴 액션(§3-6 완결 열거).
 * 콘솔 의미 액션이라 백엔드 바인딩 문자열과 별도다.
 */
export type DestructiveAction =
  | "sim.bots.params"
  | "config.market.halt"
  | "config.market.resume"
  | "config.market.fees"
  | "operators.create"
  | "operators.status"
  | "operators.roles"
  | "members.close";

/**
 * admin_approvals.state 정본 어휘(V5__admin.sql CHECK, §6-B). 원장 최초 상태가 ISSUED이고
 * 그 시점에 approverId가 항상 존재한다. 발급 전 "요청"은 서버 상태가 아니라 브라우저 로컬 의도다.
 */
export type ApprovalState = "ISSUED" | "CONSUMED" | "EXPIRED" | "REJECTED";

export type ApprovalPayload =
  | { action: "sim.bots.params"; botId: string; params: Record<string, string> }
  | { action: "config.market.halt"; symbol: string }
  | { action: "config.market.resume"; symbol: string }
  | { action: "config.market.fees"; symbol: string; makerFee: string; takerFee: string }
  | { action: "operators.create"; operatorId: string; displayName: string }
  | { action: "operators.status"; operatorId: string; status: OperatorStatus }
  | { action: "operators.roles"; operatorId: string; grant: RoleGrant; role: AdminRole }
  | { action: "members.close"; externalId: string; email: string };

/**
 * 승인 원장 프로젝션(GET /admin/v1/approvals). token과 payload는 응답에 없어 여기에도 없다.
 * 소각에 필요한 값은 발급 응답에만 실리고, requesterId <> approverId는 서버가 인증서 CN으로 강제한다(§3-6).
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
 * token은 이 응답 단일 경로로만 노출되므로 데모는 store 보관소로 발급자에서 요청자 전달을 모사한다.
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

/**
 * 시퀀서 큐·주문 지연 p99·원장 불균형·Hikari 대기는 싣지 않는다.
 * `/admin/v1/ha/status`가 이 넷을 방출하지 않아 http 어댑터가 리터럴 0을 채우고 있었고,
 * 그 결과 관제 화면이 어떤 부하에서도 경보를 내지 못하면서 "대기 0 · 지연 0ms"를
 * 측정값처럼 띄웠다. 없는 값을 0으로 채우지 않는다는 이 파일의 규율을 그 넷만 깨고 있었다.
 * 이 축들의 추이는 Grafana 02(트래픽·Spring)와 04(DB 관제)가 실측으로 그린다.
 */
export interface HaStatus {
  epoch: number;
  leaderNodeId: string;
  nodes: HaNode[];
  tradesTotal: number;
  generatedAt: string;
}

/* ===== 용량 콘솔(ADR 0015 수동 스케일링) =====
 *
 * 계약면과 관측면을 한 객체에 나란히 담는다. "몇 대가 있어야 하는가" 와 "몇 대가 있는가" 를
 * 갈라 두어야 어긋남이 보인다. 계약 3 대에 관측 2 대면 추가가 아직 안 끝난 것이고, 계약 2 대에
 * 관측 3 대면 줄이기가 안 끝나 비용이 새는 중이다. 관측만 보면 둘 다 안 보인다. */

/** 노드 한 대. expected 까지가 계약이고 reachable 부터가 관측이다. */
export interface CapacityNode {
  id: string;
  az: string;
  instanceType: string;
  /** 매칭엔진 보유. 켠 노드만 리더가 될 수 있다 */
  engine: boolean;
  /** 확장 대상인가. 리더는 아니다. 단일 라이터라 늘려도 주문 천장이 오르지 않는다 */
  scalable: boolean;
  /** 계약상 떠 있어야 하는가 */
  expected: boolean;
  /** 액추에이터가 응답했는가. false면 아래 값은 뜻이 없다 */
  reachable: boolean;
  /** readiness 200. 주문을 받는 노드다 */
  leader: boolean;
  /** read 그룹 200. 조회를 받을 수 있다. 팔로워는 leader=false이면서 이 값이 true다 */
  servesReads: boolean;
  /** 할당 코어 대비 사용률 0~1. 코어 수로 이미 나눈 값이다 */
  cpuRatio: number | null;
  cpuCount: number | null;
  heapUsedBytes: number | null;
  heapMaxBytes: number | null;
  /** 누적 요청 수. 초당 값은 화면이 두 표본의 차로 낸다 */
  httpRequests: number | null;
  wsClients: number | null;
}

export type CapacityAdviceLevel = "ok" | "info" | "warn" | "danger";

/** 권고. 표시까지이고 조치로 이어지지 않는다(정본 7.5 스케일링 실행 주체) */
export interface CapacityAdvice {
  id: string;
  level: CapacityAdviceLevel;
  headline: string;
  detail: string;
  /** 이 권고에 붙는 조치 버튼. null이면 표시만 한다 */
  action: "SCALE_OUT" | "SCALE_IN" | null;
}

export interface CapacityPlan {
  min: number;
  desired: number;
  max: number;
  updatedBy: string;
  updatedAt: string;
}

export interface CapacityState {
  tier: string;
  plan: CapacityPlan;
  /** 실제로 응답한 노드 수 */
  observedTotal: number;
  /** 계약과 관측이 같은가 */
  reconciled: boolean;
  /** 조회를 받는 노드의 평균 사용률 0~1. 못 읽으면 null이고 그때는 권고를 내지 않는다 */
  readAvgCpuRatio: number | null;
  nodes: CapacityNode[];
  advice: CapacityAdvice[];
}

/**
 * 불변식 요약 판정. 분모는 기대 검사 수(카탈로그)이고 서버 응답 길이가 아니다.
 * NONE(행 없음)과 PARTIAL(일부만 수집)은 통과가 아니라 미판정이다.
 */
export type InvariantState = "PASS" | "VIOLATED" | "PARTIAL" | "NONE";

export interface InvariantSummary {
  state: InvariantState;
  ok: number;
  violated: number;
  /** 기대 검사 수. INVARIANT_DEFS 길이 */
  expected: number;
  /** 최신 검사 시각. 행이 없으면 null이고 현재 시각으로 채우지 않는다 */
  checkedAt: string | null;
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
 * 주행 한 건의 판정. 집계와 달리 미판정이 없다. 표가 비었는지는 행 단위에서 물을 수 없다.
 * 이 별칭이 없으면 렌더가 UNKNOWN을 받을 수 있는 타입을 이진으로 칠한다.
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
   * 서버 verdict.overall. 표 전체(또는 since 창) 집계다.
   * 창 안 최신 1행만 보는 판정은 과거 FAIL과 최신 PASS가 함께 있을 때 통과로 보인다.
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
 * 봇 제어 조작의 반영 경로(API §6-A apply_path). queued는 실패가 아니라 지연이다.
 * 즉시 완료로 단정하면 봇이 죽어 명령이 PENDING인 상태에서도 성공을 표시한다.
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

/**
 * 플릿 상태 원천(status 1.3). local은 같은 JVM의 거버너, published는 봇 노드가 발행한
 * 스냅샷, unknown은 둘 다 없는 상태다. unknown에서 값을 0으로 채우면 정지·0대라는
 * 없는 사실을 화면이 단정한다.
 */
export type SimStatusSource = "local" | "published" | "unknown";

export interface SimStatus {
  source: SimStatusSource;
  /** published일 때 스냅샷 시각. 그 외 null */
  observedAt: string | null;
  /** 발행이 끊겨 스냅샷이 늙은 상태 */
  stale: boolean;
  running: boolean | null;
  paused: boolean | null;
  rate: number | null; // 전역 OPS 목표
  currentOps: number | null;
  botCount: number | null;
  activeBots: number | null;
  /** 서버 status가 내지 않는 축이다. 상수를 채우면 미측정이 측정값으로 보인다 */
  successRate: string | null;
  avgLatencyMs: number | null;
  duplicateFillTotal: number | null;
  bots: Bot[];
  /** 마켓별 방향·대수·측정치(status 1.1, §5-5). 미보고는 null이고 빈 배열과 다르다 */
  markets: MarketSimState[] | null;
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
export type ScenarioName = "steady" | "volatile" | "ramp" | "spike" | "selloff" | "thundering-herd";

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
  /** 주문 규칙(§2 v2.35). 호가 격자와 최소 주문 금액. NUMERIC 문자열 그대로 */
  tickSize: string;
  minNotional: string;
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
  /* 활동 요약(§6-E v2.35). 상세와 같은 정의를 목록 행이 함께 싣는다.
     정지·폐쇄 대상을 고르는 곳이 목록이고, 폐쇄는 미체결을 전량 취소하므로
     그 수는 조치 전에 보여야 한다 */
  openOrderCount: number;
  lastOrderAt: number | null;
  assetKinds: number;
}

/**
 * 회원 상세(§6-E). 활동 요약은 폐쇄 전 판단 근거다. openOrderCount가 0이 아니면
 * 폐쇄가 그 주문들을 취소한다.
 */
export interface AdminMemberDetail extends AdminMember {
  updatedAt: number;
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
 * 신고 큐 한 행(GET /admin/v1/community/reports). targetExternalId는 처리 액션이 받는 식별자다.
 * reportId·targetId는 내부 BIGINT이고 관리 평면 커서 축으로만 쓴다(§2-6 각주).
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
  invariants: InvariantSummary;
  lastRtoMs: number | null;
  lastVerdict: RecoveryVerdict | null;
  /** 봇 노드가 상태를 발행하지 않으면 미상이다. 0을 채우면 정지로 읽힌다 */
  botsActive: number | null;
  currentOps: number | null;
  pendingApprovals: number;
  suspendedMarkets: number;
  generatedAt: string;
}
