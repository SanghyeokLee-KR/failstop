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
  | "members.close"
  // 관제센터 세 액션은 API §3-6에 아직 없다. 서버 계약 초안(관리자 설계 4-4)이고 sim만 실행한다
  | "ops.threshold.set"
  | "ops.ai.auto"
  | "ops.ai.limits";

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
  | { action: "members.close"; externalId: string; email: string }
  | {
      action: "ops.threshold.set";
      key: ThresholdKey;
      label: string;
      unit: ThresholdUnit;
      from: number;
      to: number;
      reason: string;
      /** 되돌리기면 되돌리는 변경의 id. 이력이 어떤 변경을 무른 것인지 잇는다 */
      revertOf: string | null;
      /** AI 조치안을 승인한 변경이면 그 조치안 id */
      proposalId: string | null;
    }
  | { action: "ops.ai.auto"; hours: number; reason: string }
  | {
      action: "ops.ai.limits";
      key: AiLimitKey;
      label: string;
      unit: string;
      from: number;
      to: number;
      reason: string;
      /** 되돌리기면 되돌리는 변경의 id */
      revertOf: string | null;
    };

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

/**
 * 권고 임계. 서버가 소유한다.
 *
 * 화면이 0.60을 복제하고 있었다. 서버가 환경에 맞춰 값을 조이면 게이지 눈금만 옛 값에
 * 남아 "임계 미만인데 증설 권고"처럼 읽힌다. 판정과 눈금이 같은 값을 쓰게 서버에서 받는다.
 */
export interface CapacityThresholds {
  scaleOutCpuRatio: number;
  scaleInCpuRatio: number;
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
  /** 증설·축소 권고 임계. 화면의 게이지 눈금도 이 값을 쓴다 */
  thresholds: CapacityThresholds;
  nodes: CapacityNode[];
  advice: CapacityAdvice[];
}

/**
 * 불변식 요약 판정. 분모는 기대 검사 수이고 서버 응답 길이가 아니다.
 * NONE(행 없음)과 PARTIAL(정기 검사 일부만 수집)은 통과가 아니라 미판정이다.
 */
export type InvariantState = "PASS" | "VIOLATED" | "PARTIAL" | "NONE";

export interface InvariantSummary {
  state: InvariantState;
  ok: number;
  violated: number;
  /** 기대 검사 수. 정기 검사 전부에 응답에 실린 권위 run 전용 검사 수를 더한다 */
  expected: number;
  /** 최신 검사 시각. 행이 없으면 null이고 현재 시각으로 채우지 않는다 */
  checkedAt: string | null;
}

/** invariant_checks 원소. 항목 수는 서버 InvariantCheck enum이 정한다(정기 22종, 권위 run 전용 7종) */
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
  /** 누적 체결 수. 초당 체결은 화면이 두 표본의 차로 낸다 */
  tradesTotal: number;
  /** 리더가 맡은 마켓 파티션 수. 서버 status가 노드별 마켓을 내지 않으면 미보고(null)다 */
  marketPartitions: number | null;
  generatedAt: string;
}

/* ===== MSP AI 관제센터(관리자 설계 4-4) =====
 *
 * 관제 데이터는 칸마다 원천이 다르고 원천이 붙는 시점도 다르다. 그래서 칸마다 준비됨과
 * 원천 연결 전을 갈라 싣는다. 연결 전을 빈 배열이나 0으로 채우면 화면이 정상을 단정한다(ADR 0027). */

/** 값의 출처. SIM은 브라우저 시뮬레이터, LIVE는 관측 원천의 실측이다 */
export type OpsOrigin = "SIM" | "LIVE";

/** 배포 환경. 서버 설정이 원천이다(2단계). 모르면 UNKNOWN이고 화면이 짐작하지 않는다 */
export type OpsEnvironment = "SIMULATOR" | "LOCAL_DOCKER" | "SCHOOL_AWS" | "UNKNOWN";

/** 칸 하나. 원천을 붙이기 전이면 값 대신 붙일 원천의 이름을 싣는다 */
export type OpsSection<T> = { state: "READY"; data: T } | { state: "NOT_CONNECTED"; source: string };

/** 서비스 목표. 상태 요약 칸이 벗어났는지를 이 값과 견준다 */
export interface OpsSlo {
  availabilityPct: number;
  p95Ms: number;
  errorRatePct: number;
}

export interface OpsSummary {
  /** 외부 프로브 성공률(최근 5분) */
  availabilityPct: number | null;
  p95Ms: number | null;
  errorRatePct: number | null;
  slo: OpsSlo;
}

export type OpsHostRole = "APP" | "DB_PRIMARY" | "DB_STANDBY" | "BOT_OBS";
/** 수집 상태. 마지막 수집이 폴링 두 주기를 넘기면 STALE, 받은 적이 없으면 MISSING */
export type OpsCollect = "OK" | "STALE" | "MISSING";

/** 매칭 노드가 맡은 마켓 묶음(ADR 0034 마켓별 리더). 노드가 멈추면 다른 매칭 노드가 넘겨받는다 */
export interface OpsPartition {
  id: string;
  markets: string[];
}

export interface OpsHost {
  id: string;
  role: OpsHostRole;
  instanceType: string;
  az: string;
  collect: OpsCollect;
  collectedAt: string | null;
  cpuPct: number | null;
  memPct: number | null;
  diskUsedPct: number | null;
  /** t3 버스트 크레딧 잔량 */
  cpuCredits: number | null;
  /** 매칭 노드만 있다 */
  partition: OpsPartition | null;
}

/**
 * 온프레미스 AI 추론 서버(ADR 0038). 구축 전이라 sim 값이거나 원천 연결 전이다.
 * EC2 호스트와 재는 것이 달라 호스트 목록에 넣지 않는다.
 */
export interface AiServer {
  id: string;
  hardware: string;
  runtime: string;
  /** 적재 모델. 모델은 평가로 정하므로 sim은 실제 모델 이름을 적지 않는다 */
  model: string;
  collectedAt: string | null;
  gpuPct: number | null;
  memUsedGb: number | null;
  memTotalGb: number;
  /** 최근 판단 요청의 추론 지연. 요청이 없었으면 null */
  p50Ms: number | null;
  p95Ms: number | null;
  tokensPerSec: number | null;
  /** 대기 중인 판단 요청 수 */
  queue: number | null;
  /** 관제 서버와의 연결 */
  link: "OK" | "DOWN";
  /** 지금 판단 요청을 처리하는 중인가 */
  busy: boolean;
  /** 마지막으로 끝낸 판단 요청의 순번. 화면이 요청·응답이 오간 순간을 알아챈다 */
  served: number;
}

/** 상태 요약 추세선의 범위(시간) */
export type TrendRange = 1 | 6 | 24;
export type TrendMetric = "availability" | "p95" | "errors" | "alerts" | "incidents";

/**
 * 상태 요약 칸 하나의 추세. points는 오래된 것부터 같은 간격이고 값이 없으면 null이다.
 * current와 previous는 지금 구간과 바로 앞 같은 길이 구간의 대표값이다. 비율·지연은 평균,
 * 경보는 발생 수, 사건은 연 수다.
 */
export interface OpsTrendSeries {
  points: (number | null)[];
  current: number | null;
  previous: number | null;
}

export type OpsTrends = Record<TrendRange, Record<TrendMetric, OpsTrendSeries>>;

export type ReadNodeState = "IN_SERVICE" | "PENDING" | "DRAINING" | "OFF";

export interface ReadNode {
  id: string;
  state: ReadNodeState;
  cpuPct: number | null;
}

/** 조회 노드 ASG. 매칭 리더와 DB는 여기 들지 않는다 */
export interface ReadTier {
  min: number;
  max: number;
  desired: number;
  nodes: ReadNode[];
  /** 조회를 받는 노드의 평균 CPU. 받는 노드가 없으면 null */
  avgCpuPct: number | null;
}

export type ReplicationStream = "STREAMING" | "CATCHUP" | "STOPPED";

/** DB 복제. 비동기라 지연 동안 쓰인 기록은 Primary를 잃으면 함께 잃을 수 있다(ADR 0024) */
export interface Replication {
  primary: string;
  standby: string;
  stream: ReplicationStream;
  lagBytes: number | null;
  lagSec: number | null;
}

export type AlertSeverity = "critical" | "warning" | "info";
export type AlertState = "FIRING" | "ACKED" | "SILENCED";

/** 화면에서 강조할 자리. 근거 칩과 경보가 무엇을 가리키는지 적는다 */
export type OpsRef =
  | { kind: "HOST"; id: string }
  | { kind: "READ_TIER" }
  | { kind: "REPLICATION" }
  | { kind: "BACKUP" }
  | { kind: "SLO"; metric: "availability" | "p95" | "errors" }
  | { kind: "ALERT"; id: string };

export interface OpsAlert {
  id: string;
  name: string;
  severity: AlertSeverity;
  target: string;
  detail: string;
  since: string;
  thresholdKey: ThresholdKey | null;
  ref: OpsRef | null;
  state: AlertState;
  ackBy: string | null;
  ackAt: string | null;
  silencedUntil: string | null;
  silencedBy: string | null;
  /** 이미 한 번 보류했는가. 같은 경보를 잇달아 보류하지 못한다(ADR 0035) */
  silencedBefore: boolean;
}

export type IncidentEventKind =
  | "OPENED"
  | "AI_JUDGED"
  | "APPROVED"
  | "REJECTED"
  | "DEFERRED"
  | "ACTED"
  | "AUTO_ACTED"
  | "AUTO_REVERTED"
  | "REVERTED"
  | "MODE"
  | "NOTIFIED"
  | "ACKED"
  | "SILENCED"
  | "RESOLVED";

export type OpsActor = "SYSTEM" | "AI" | "OPERATOR";

/** 기록 한 줄. 사건에 속하지 않는 조치(모드 전환, 경보 없는 축소 등)는 incidentId가 null이다 */
export interface IncidentEvent {
  id: string;
  at: string;
  kind: IncidentEventKind;
  actor: OpsActor;
  actorName: string;
  text: string;
  incidentId: string | null;
}

export interface Incident {
  id: string;
  title: string;
  severity: AlertSeverity;
  openedAt: string;
  resolvedAt: string | null;
}

/** 최신이 앞이다 */
export interface IncidentLog {
  incidents: Incident[];
  events: IncidentEvent[];
}

/** Databasus 백업 결과. 관제센터는 판독만 하고 백업 설정을 바꾸지 않는다 */
export type JobOutcome = "SUCCESS" | "FAILED" | "NONE";

export interface OpsBackup {
  tool: "Databasus";
  lastSuccessAt: string | null;
  lastSizeBytes: number | null;
  running: boolean;
  offsite: { target: "SFTP" | "S3"; outcome: JobOutcome; at: string | null };
  restoreCheck: { outcome: JobOutcome; lastSuccessAt: string | null; lastFailureAt: string | null };
  nextAt: string | null;
  /** 오늘 운영자·AI가 즉시 실행한 횟수. 자동 즉시 실행은 하루 1회다(ADR 0035) */
  manualRunsToday: number;
}

export interface OpsJobs {
  backup: OpsBackup;
  certificate: { domain: string; expiresAt: string };
  dailyReport: { hour: number; channel: "Teams"; lastSentAt: string | null };
}

export type ThresholdKey =
  | "read.scale_out_cpu"
  | "read.scale_in_cpu"
  | "host.cpu_alert"
  | "host.mem_alert"
  | "host.disk_free_alert"
  | "db.replication_lag_alert"
  | "backup.delay_alert"
  | "bot.capacity_guard"
  | "host.cpu_credit_alert";

export type ThresholdUnit = "%" | "초" | "시간" | "크레딧";

export interface OpsThreshold {
  key: ThresholdKey;
  label: string;
  appliesTo: string;
  unit: ThresholdUnit;
  value: number;
  min: number;
  max: number;
  step: number;
  /** AI가 한 번에 바꿀 수 있는 폭. 운영자 변경에는 걸리지 않는다 */
  aiMaxStep: number;
  aiSuggested: number | null;
  aiReason: string | null;
  updatedAt: string;
  updatedBy: string;
}

export type ThresholdVia = "MANUAL" | "AI_APPROVED" | "AI_AUTO" | "REVERT";

export interface ThresholdChange {
  id: string;
  key: ThresholdKey;
  label: string;
  unit: ThresholdUnit;
  from: number;
  to: number;
  at: string;
  by: string;
  via: ThresholdVia;
  approvalId: string | null;
  reason: string;
  revertOf: string | null;
  /** 이 변경을 이미 되돌렸는가 */
  reverted: boolean;
}

export interface ThresholdRegistry {
  items: OpsThreshold[];
  history: ThresholdChange[];
}

/** AI 운영 모드(ADR 0035). 수동이 기본이다. STOPPED는 운영자가 AI 즉시 정지를 누른 상태이고 재개하면 수동이다 */
export type AiMode = "MANUAL" | "AUTO" | "STOPPED";

export interface AiStop {
  at: string;
  by: string;
}

/** 운영자가 1인 승인으로 바꾸는 AI 한도(ADR 0035). AI는 바꿀 수 없다 */
export type AiLimitKey = "confidencePct" | "autoCap" | "thresholdPerItem" | "botCooldownMin" | "backupDiskFreePct";

export interface AiLimit {
  key: AiLimitKey;
  label: string;
  desc: string;
  unit: string;
  value: number;
  /** 자동 모드 중에 넓히는 쪽으로 바꾼 값. 이번 자동 모드가 끝나면 적용된다 */
  pending: number | null;
  /** 코드에 고정한 하드 범위. 이 밖의 값은 입력 단계에서 막는다 */
  min: number;
  max: number;
  /** 값을 올리면 AI가 할 수 있는 일이 줄어드는가 */
  tighterWhenHigher: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** NOW는 곧바로, NEXT_AUTO는 이번 자동 모드가 끝난 뒤(다음 활성부터) 적용한 변경이다 */
export type AiLimitApply = "NOW" | "NEXT_AUTO";

export interface AiLimitChange {
  id: string;
  key: AiLimitKey;
  label: string;
  unit: string;
  from: number;
  to: number;
  at: string;
  by: string;
  approvalId: string;
  reason: string;
  revertOf: string | null;
  reverted: boolean;
  apply: AiLimitApply;
}

export interface AiAutoGrant {
  since: string;
  until: string;
  reason: string;
  by: string;
  approvalId: string;
}

/** 허용 목록 조치 다섯 종. 금지 목록 조치는 이 어휘에 없어 카드가 될 수 없다 */
export type AiActionKind = "READ_NODES" | "THRESHOLD" | "SILENCE" | "BOT_LOAD" | "BACKUP_NOW";
export type AiRisk = "LOW" | "MEDIUM";
export type AiProposalStatus =
  | "PROPOSED"
  | "APPROVED"
  | "REJECTED"
  | "DEFERRED"
  | "AUTO_EXECUTED"
  | "REVERTED"
  | "AUTO_REVERTED"
  | "WITHDRAWN"
  /** AI 즉시 정지로 만료된 조치안. 승인할 수 없다 */
  | "EXPIRED";

export interface AiEvidence {
  id: string;
  label: string;
  value: string;
  /** 경보 근거면 그 경보의 심각도. 지표 근거는 null */
  severity: AlertSeverity | null;
  ref: OpsRef | null;
}

export interface AiProposal {
  id: string;
  kind: AiActionKind;
  title: string;
  target: string;
  impact: string;
  risk: AiRisk;
  revertPlan: string;
  confidencePct: number;
  status: AiProposalStatus;
  createdAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
  /** 자동 실행 뒤 되돌릴 수 있는 마감. 관찰 창과 같다 */
  revertibleUntil: string | null;
  /** 자동 모드에서 실행하지 않고 기다리는 이유 */
  autoHold: string | null;
  /** 처리된 조치안이 실제로 바꾼 것. 되돌린 조치는 되돌린 방향과 이유다. 대기 중이면 null */
  outcome: string | null;
}

export interface AiJudgment {
  id: string;
  at: string;
  summary: string;
  outcome: string;
}

export interface AiCooldown {
  kind: AiActionKind;
  until: string;
}

/** 자동 조치 뒤 관찰 창. 창 안에서 악화하면 자동으로 되돌리고 수동으로 바꾼다 */
export interface AiObservation {
  startedAt: string;
  until: string;
  action: string;
}

export interface AiDesk {
  mode: AiMode;
  auto: AiAutoGrant | null;
  thinking: boolean;
  model: string;
  judgedAt: string | null;
  evidenceAt: string | null;
  situation: string;
  hypothesis: string | null;
  confidencePct: number | null;
  evidence: AiEvidence[];
  proposals: AiProposal[];
  history: AiJudgment[];
  /** 이번 자동 모드에서 실행한 조치 수와 상한. 자동을 새로 켜면 0부터 센다 */
  autoUsed: number;
  autoCap: number;
  stopped: AiStop | null;
  limits: AiLimit[];
  limitHistory: AiLimitChange[];
  cooldowns: AiCooldown[];
  observation: AiObservation | null;
  /**
   * 같은 조치를 지난날 몇 번 실행했고 몇 번 되돌렸는가. 감사 기록에서 센 값이고 열쇠는
   * aiPolicy.actionKey다. 기록이 없는 조치는 열쇠가 없다
   */
  actionStats: Record<string, { runs: number; reverts: number }>;
}

export type OpsScenarioId = "READ_SURGE" | "NIGHT_SCALE_IN";

export interface OpsScenarioView {
  id: OpsScenarioId;
  title: string;
  startedAt: string;
  elapsedSec: number;
}

export interface ControlCenter {
  /** 칸이 모두 연결 전이면 null이다 */
  origin: OpsOrigin | null;
  environment: OpsEnvironment;
  generatedAt: string;
  /** 근거 지표를 마지막으로 모은 시각 */
  collectedAt: string | null;
  summary: OpsSection<OpsSummary>;
  hosts: OpsSection<OpsHost[]>;
  readTier: OpsSection<ReadTier>;
  replication: OpsSection<Replication>;
  alerts: OpsSection<OpsAlert[]>;
  incidents: OpsSection<IncidentLog>;
  jobs: OpsSection<OpsJobs>;
  thresholds: OpsSection<ThresholdRegistry>;
  ai: OpsSection<AiDesk>;
  aiServer: OpsSection<AiServer>;
  trends: OpsSection<OpsTrends>;
  /** sim 시나리오 재생. 재생 중이 아니거나 http면 null */
  scenario: OpsScenarioView | null;
}

export type AiDecision = "APPROVE" | "REJECT" | "DEFER";
