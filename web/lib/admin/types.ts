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
  // 관제센터 세 액션. 서버 승인 원장의 액션 이름과 같다(API §3-6, §6-F)
  | "ops.threshold.set"
  | "ops.ai.auto"
  | "ops.ai.limits"
  // 변경 요청 승인(관리자 설계 4-4 3라운드 계약 초안). 서버 경로는 아직 없다
  | "ops.change.approve";

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
      /** 바꾸기 전의 값. 서버 레지스트리의 미설정 항목이면 null이다 */
      from: number | null;
      to: number;
      reason: string;
      /** 되돌리기면 되돌리는 변경의 id. 이력이 어떤 변경을 되돌린 것인지 잇는다 */
      revertOf: string | null;
      /** AI 조치안을 승인한 변경이면 그 조치안 id */
      proposalId: string | null;
    }
  | { action: "ops.ai.auto"; hours: number; reason: string }
  | { action: "ops.change.approve"; id: string; title: string }
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
  /**
   * 모드가 쓰는 리스 자원별 한 행(API 설계 §6-C `leases[]`). P1·P2는 matching-engine 하나이고
   * 파티션 리더 모드(P3)는 matching-p0·matching-p1이다. `leases`가 없는 옛 서버 응답이면
   * 어댑터가 `lease` 한 행으로 만든다.
   */
  leases?: HaLease[];
}

/** 리스 한 행. 남은 시간·만료 시각은 서버가 주지 않았으면 null이다(0으로 채우지 않는다) */
export interface HaLease {
  resource: string;
  /** 보유 노드의 holder. 비었으면 아무도 쥐지 않았다 */
  holder: string;
  epoch: number;
  leaseUntil: string | null;
  leaderAlive: boolean;
  leaseRemainingMs: number | null;
  /** 그 자원이 덮는 마켓 심볼(마켓 id 순) */
  markets: string[];
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
  /**
   * 기동 중(PENDING)이거나 내리는 중(DRAINING)인 app-3. sim이 관제 시뮬레이터의 앱 계층에서 옮겨 온다.
   * 서버 응답에는 이 축이 없어 비어 있고, 그때 화면은 계약(expected)과 응답(reachable)만으로 읽는다
   */
  lifecycle?: "PENDING" | "DRAINING" | null;
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
  /** 리스 자원별 리더(HaStatus.leases와 같다). P1·P2는 matching-engine 한 행, 파티션 리더 모드(P3)는 파티션마다 한 행이다 */
  leases: HaLease[];
  generatedAt: string;
}

/* ===== MSP AI 관제센터(관리자 설계 4-4) =====
 *
 * 관제 데이터는 칸마다 원천이 다르고 원천이 붙는 시점도 다르다. 그래서 칸마다 준비됨과
 * 원천 연결 전을 갈라 싣는다. 연결 전을 빈 배열이나 0으로 채우면 화면이 정상을 단정한다(ADR 0027). */

/** 값의 출처. SIM은 브라우저 시뮬레이터, LIVE는 관측 원천의 실측이다 */
export type OpsOrigin = "SIM" | "LIVE";

/** 배포 환경. 서버 설정(exchange.msp.environment)이 원천이다. 모르면 UNKNOWN이고 화면이 짐작하지 않는다 */
export type OpsEnvironment = "SIMULATOR" | "LOCAL_DOCKER" | "SCHOOL_AWS" | "PERSONAL_AWS" | "UNKNOWN";

/**
 * 칸 하나. 원천을 붙이기 전이면 값 대신 붙일 원천의 이름을 싣는다. UNAVAILABLE은 원천 설정은 있으나 이번 조회가
 * 실패했다는 뜻이고(API §6-F 칸 상태) reason에 서버의 분류 코드를 싣는다. 연결 전과 실패는 모양이 다르다(§12-8)
 */
export type OpsSection<T> =
  | { state: "READY"; data: T }
  | { state: "NOT_CONNECTED"; source: string }
  | { state: "UNAVAILABLE"; source: string; reason: string };

/** 서비스 목표. 상태 요약 칸이 벗어났는지를 이 값과 견준다. 서버에 목표 설정이 없으면 null이고 견주지 않는다 */
export interface OpsSlo {
  availabilityPct: number | null;
  p95Ms: number | null;
  errorRatePct: number | null;
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
  /** 자산 칸(관리자 설계 4-10-3). 원천이 채우기 전에는 없다 */
  asset?: HostAsset;
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
  /** 장비 메모리. 원천이 알려 주지 않으면 null이다 */
  memTotalGb: number | null;
  /** 최근 판단 요청의 추론 지연. 요청이 없었으면 null */
  p50Ms: number | null;
  p95Ms: number | null;
  /** 마지막 판단 요청 한 건의 추론 지연. 서비스 흐름의 ai-1 카드가 응답 순간에 적는다. 요청이 없었으면 null */
  lastMs: number | null;
  tokensPerSec: number | null;
  /** 대기 중인 판단 요청 수 */
  queue: number | null;
  /** 관제 서버와의 연결 */
  link: "OK" | "DOWN";
  /** 구축 전이라 연결과 값이 시뮬레이터의 가정인가. 화면은 '시뮬레이션 가정'을 함께 적는다 */
  assumed: boolean;
  /** 지금 판단 요청을 처리하는 중인가 */
  busy: boolean;
  /** 마지막으로 끝낸 판단 요청의 순번. 화면이 요청·응답이 오간 순간을 알아챈다 */
  served: number;
  /** 마지막으로 판단 요청에 답한 시각. 답한 적이 없으면 null */
  lastServedAt: string | null;
}

/** 마켓 파티션 하나의 매칭 리더(ADR 0034). 세대는 파티션마다 따로 오른다 */
export interface OpsLeader {
  partition: string;
  node: string;
  epoch: number;
  markets: string[];
}

/**
 * 거래 칸. 매칭 리더와 시장 봇 발행률, 초당 체결이다. 관제센터의 거래 요약 띠, 봇 플릿, 서비스 흐름이 같은
 * 값을 읽는다. 목표 발행률은 운영자·AI가 정한 값이고 지금 발행률은 봇이 실제로 내는 값이다(정지면 0)
 */
export interface OpsTrading {
  leaders: OpsLeader[];
  botTargetOps: number;
  botOps: number;
  tradesPerSec: number;
  /** 쓰기 상태(관리자 설계 4-10-8). 원천이 채우기 전에는 없다 */
  writes?: WriteState;
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

/** 자동 증설 자리의 노드(app-3) */
export interface ReadNode {
  id: string;
  state: ReadNodeState;
  cpuPct: number | null;
  instanceType: string;
  az: string;
  /**
   * 자산 칸(관리자 설계 4-10-3). 켤 때마다 새 인스턴스라 가동 시간과 최근 재부팅이 켠 시각에서 온다. 꺼져 있으면
   * 인스턴스가 없어 없다. http는 원천이 채우기 전이라 값마다 null이다
   */
  asset?: HostAsset;
}

/**
 * 앱 계층(ADR 0030, 사용자 정정 2026-09-25). app-1·app-2는 늘 켜진 매칭 리더이면서 조회도 받는 고정 노드이고, 부하가
 * 오르면 조회 전용 app-3가 Auto Scaling으로 켜진다(0~1대). 대수는 앱 계층 전체로 센다(최소 2 · 최대 3). 매칭 리더와
 * DB는 ASG가 관리하지 않으므로 nodes에는 자동으로 켜고 끄는 app-3만 있다. 이름은 조회를 나누는 계층이라는 뜻으로 둔다
 */
export interface ReadTier {
  /** 앱 계층 최소·최대 대수(2·3) */
  min: number;
  max: number;
  /** 앱 계층 희망 대수. 3이면 app-3가 켜져 있거나 켜지는 중이다 */
  desired: number;
  /** 늘 켜진 앱 노드(app-1·app-2). ASG 밖이다 */
  fixed: string[];
  /** 지금 조회를 받는 앱 노드 수(고정 두 대와 켜진 app-3) */
  serving: number;
  /** Auto Scaling이 켜고 끄는 자리(app-3) */
  nodes: ReadNode[];
  /** 조회를 받는 앱 노드의 평균 CPU. 받는 노드가 없으면 null */
  avgCpuPct: number | null;
  /**
   * KST 오늘 app-3이 기동·운영·드레이닝이던 초(관리자 설계 4-10-5 비용). 인스턴스 요금은 켜져 있는 동안 붙으므로
   * 조회를 받기 전과 내리는 동안도 센다. 용량·부하의 활동 기록과 비용이 이 값을 읽는다. 원천이 없으면 null이다
   */
  onSecondsToday: number | null;
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
  /**
   * 이 경보가 속한 사건. 경보 행의 호출 단계 칩이 그 사건의 호출 기록을 읽는다(관리자 설계 4-10-2). 서버는 경보마다
   * incident_id를 싣고, 원천이 사건을 싣지 않으면 null이다
   */
  incidentId: string | null;
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
  | "RESOLVED"
  /* 아래는 서버 사건 타임라인에만 있는 종류다(V71·V72). sim은 내지 않는다 */
  /** 사람 보류 해제 */
  | "UNSILENCED"
  /** AI 즉시 정지와 재개 */
  | "STOPPED"
  | "RESUMED"
  /** AI 한도 변경 */
  | "LIMITS"
  /** 계획 작업 창 등록·종료. 관제 시뮬레이터도 창과 변경 요청의 기록에 쓴다(관리자 설계 4-10-4) */
  | "WINDOW"
  /** 정책 엔진이 조치 요청을 막음 */
  | "DENIED"
  /** 관찰 창의 끝 */
  | "OBSERVED"
  /** 사건 담당 지정(관리자 설계 4-10-2). 서버 경로는 계약 초안이다 */
  | "ASSIGNED"
  /** 사후 분석 완료(관리자 설계 4-10-2). 생애의 다섯째 단계다. 서버 경로는 계약 초안이다 */
  | "POSTMORTEM";

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
  /**
   * 이 사건의 기록 줄이 판에 모두 실렸는가. http 판은 최근 사건 몇 건의 기록만 싣고 나머지는 사건을 열 때
   * getIncidentEvents로 읽는다. sim은 늘 싣는다(undefined)
   */
  eventsLoaded?: boolean;
  /*
   * 아래 셋은 3라운드 칸이다(관리자 설계 4-10-2). 당번 명부·호출·사후 분석 원천이 있어야 채운다. sim은 늘 채우고
   * http는 서버 경로가 계약 초안이라 없다. 없으면 화면이 그 칸을 원천 연결 전으로 그린다
   */
  owner?: IncidentOwner | null;
  /** 보낸 호출. 오래된 것이 앞이다 */
  pages?: IncidentPage[];
  postmortem?: Postmortem | null;
  /** 사건의 런북. 경보의 이름과 임계값 키로 정적 런북 표에서 고른다(runbooks.ts). 두 원천 모두 채운다 */
  runbookId: RunbookId | null;
}

/** 최신이 앞이다 */
export interface IncidentLog {
  incidents: Incident[];
  events: IncidentEvent[];
}

/** Databasus 백업 결과. 관제센터는 판독만 하고 백업 설정을 바꾸지 않는다 */
export type JobOutcome = "SUCCESS" | "FAILED" | "NONE";

export interface OpsBackup {
  /** 값의 원천. Databasus 판독이거나, Databasus 연결 전이면 백업 스크립트가 내는 지표(failstop_db_backup_*)다 */
  tool: "Databasus" | "백업 지표";
  lastSuccessAt: string | null;
  lastSizeBytes: number | null;
  running: boolean;
  /** 외부 보관 결과. 원천이 알려 주지 않으면 null이다 */
  offsite: { target: "SFTP" | "S3"; outcome: JobOutcome; at: string | null } | null;
  restoreCheck: { outcome: JobOutcome; lastSuccessAt: string | null; lastFailureAt: string | null };
  nextAt: string | null;
  /** 오늘 운영자·AI가 즉시 실행한 횟수. 자동 즉시 실행은 하루 1회다(ADR 0035). 원천이 세지 않으면 null이다 */
  manualRunsToday: number | null;
  /** 즉시 실행을 받는 원천이 있는가. Databasus 연결 전이면 단추를 막는다 */
  canRun: boolean;
}

export interface OpsJobs {
  /** 백업 원천이 없으면 null이고 backupSource에 붙일 원천을 적는다 */
  backup: OpsBackup | null;
  backupSource: string | null;
  /**
   * 인증서 목록(관리자 설계 4-10-3). 공개 인증서, 관리 게이트웨이 내부 인증서, DB TLS 서버 인증서다. 관제센터 운영 작업 줄,
   * 네트워크·보안, 사이드바 배지가 이 한 목록을 읽는다. 원천이 없으면 null이다
   */
  certificates: CertificateInfo[] | null;
  /** hour는 보고 예약 시각(KST 시)이다. 원천이 예약 시각을 알려 주지 않으면 null이다 */
  dailyReport: {
    hour: number | null;
    channel: "Teams";
    /** Teams 전송 기록의 마지막 일일 보고 */
    lastSentAt: string | null;
    /** 보고 타이머의 마지막 실행. 실행만 알고 전달 성공은 모른다(§6-F 운영 작업). sim은 싣지 않는다 */
    lastRunAt?: string | null;
  };
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
  /** 지금 값. 서버 레지스트리의 미설정 항목은 null이고 그 항목은 판정하지 않는다 */
  value: number | null;
  min: number;
  max: number;
  step: number;
  /** AI가 한 번에 바꿀 수 있는 폭. 운영자 변경에는 걸리지 않는다 */
  aiMaxStep: number;
  aiSuggested: number | null;
  aiReason: string | null;
  updatedAt: string;
  updatedBy: string;
  /** 이 환경에서 그 항목을 쓰는 규칙이나 권고가 있는가(§6-F in_use). sim은 싣지 않는다 */
  inUse?: boolean | null;
  /** 경보 규칙이 지금 쓰는 값(게이지 마지막 값). 값과 다르면 변경이 아직 규칙에 닿지 않았다. sim은 싣지 않는다 */
  ruleValue?: number | null;
}

/** 변경 경로. 뒤의 셋은 서버에만 있다(SEED 마이그레이션 초기값, CONFIG 환경 기본값, AUTO_REVERT 관찰 창의 자동 되돌림) */
export type ThresholdVia = "MANUAL" | "AI_APPROVED" | "AI_AUTO" | "REVERT" | "AUTO_REVERT" | "SEED" | "CONFIG";

export interface ThresholdChange {
  id: string;
  key: ThresholdKey;
  label: string;
  unit: ThresholdUnit;
  /** 미설정에서 바꾸거나 미설정으로 되돌린 변경은 null이다 */
  from: number | null;
  to: number | null;
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
  /** 적용 시점. 서버 변경 이력은 싣지 않아 http는 null이다 */
  apply: AiLimitApply | null;
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
  /** AI 즉시 정지나 시간 초과로 만료된 조치안. 승인할 수 없다. 어느 쪽인지는 hold가 가른다 */
  | "EXPIRED"
  /* 아래 넷은 서버 정책 엔진에만 있는 상태다(API §6-F). sim은 내지 않는다 */
  /** 실행 중 */
  | "EXECUTING"
  /** 실행했으나 실패 */
  | "FAILED"
  /** 실행이 중간에 끊겨 결과를 원천에서 대조하지 못함 */
  | "UNKNOWN"
  /** 정책이 제안 때 막음 */
  | "DENIED";

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
  /** 바꾸는 값의 전후(앱 계층 2 → 3대, 임계 60 → 55%). 보류·백업처럼 수로 적을 전후가 없으면 null */
  change: { from: number; to: number; unit: string } | null;
  /** 비용이 무엇이 늘고 주는지. 시간당 요금 같은 수치는 적지 않는다 */
  cost: string;
  /**
   * 자동 모드에서 실행할 수 있는 조치인가(ADR 0035, 사용자 확정 2026-09-25). 임계값은 더 민감하게 하는 방향만, 시장 봇
   * 부하 줄임은 bot-1 CPU가 봇 용량 보호 기준 이상일 때만 자동이다. 아니면 자동 모드에서도 '승인 필요'다
   */
  autoAllowed: boolean;
  /**
   * 서버 정책 엔진이 막거나 기다리게 한 규칙과 사유(hold_rule, hold_reason). 규칙 id는 관제센터 2단계 설계 7-6절이다.
   * sim과 막힌 것이 없는 조치는 null이다
   */
  hold: { rule: string; reason: string } | null;
}

/** 판단 과정의 후보 조치 하나 */
export interface AiTraceCandidate {
  id: string;
  kind: AiActionKind;
  title: string;
  confidencePct: number;
  risk: AiRisk;
}

/**
 * 정책 엔진 검사 행(ADR 0035의 자동 실행 조건). PASS는 통과, BLOCK은 자동 실행을 막음, HOLD는 수동 모드라 운영자
 * 승인을 기다림, NA는 그 후보에 해당하지 않음이다
 */
export type AiCheckRule =
  | "ALLOWED"
  | "OBSERVE"
  | "CAP"
  | "COOLDOWN"
  | "FRESH"
  | "CONFIDENCE"
  | "STREAK"
  | "STEP"
  | "PER_ITEM"
  | "BACKUP_TODAY"
  /** 경보 보류는 계획 작업 창과 겹치는 주의 경보만(S1~S8, 관리자 설계 4-10-4) */
  | "WINDOW"
  | "DIRECTION"
  | "BOT_GUARD"
  | "MODE";
export type AiCheckState = "PASS" | "BLOCK" | "HOLD" | "NA";

export interface AiTraceCheck {
  rule: AiCheckRule;
  label: string;
  /** 후보 순서와 같다 */
  results: { state: AiCheckState; note: string }[];
}

/** 판단 한 번의 과정. 판단한 순간의 근거, 가설, 후보와 후보마다의 정책 엔진 검사, 결과다 */
export interface AiJudgmentTrace {
  mode: AiMode;
  evidence: AiEvidence[];
  evidenceAt: string | null;
  hypothesis: string | null;
  candidates: AiTraceCandidate[];
  checks: AiTraceCheck[];
  result: string;
  /**
   * 모델 근거에 싣지 않고 버린 경보 필드의 이름(서버 판단 근거의 rejected, 관리자 설계 4-10-9 프롬프트 주입).
   * 버린 것이 없거나 원천이 채우기 전에는 없다
   */
  rejected?: { alert: string; fields: string[] }[];
}

/**
 * 관제 시뮬레이터가 박자마다 남기는 지표 관제 값(관리자 설계 4-2 지표 관제). 지표 관제 화면에 들어오면 이 기록으로 최근 150초 창을
 * 채운다. 화면에 들어온 뒤부터만 그리면 첫 화면 대부분이 빈 격자가 된다. sim만 있고 http 원천에는 없다
 */
export interface OpsFlowTapeEntry {
  /** 박자의 시각(ms) */
  t: number;
  tradesPerSec: number;
  botOps: number;
  /** 노드 CPU. app-3가 꺼져 있으면 null이다 */
  cpu: { "app-1": number | null; "app-2": number | null; "app-3": number | null };
  /** 앱 계층 평균 CPU(%) */
  avgCpuPct: number | null;
  desired: number;
  serving: number;
  /** 복제가 끊겼으면 null이다 */
  lagBytes: number | null;
}

export interface AiJudgment {
  id: string;
  at: string;
  summary: string;
  outcome: string;
  /** 판단 과정. 판단이 아닌 기록(AI 정지 등)은 null이다 */
  trace: AiJudgmentTrace | null;
  /**
   * 판단 과정을 판에 싣지 않았는가. http 판은 판단 목록만 싣고, 상세를 열 때 getAiJudgment로 읽는다. sim은 늘 싣는다
   */
  lazy?: boolean;
  /** 그 판단의 확신도(후보 가운데 가장 높은 값). 후보가 없으면 null이다. http는 판단 목록의 confidence다 */
  confidencePct?: number | null;
  /**
   * 그 판단이 낸 조치안을 어떻게 처리했는가('승인 · 김수퍼 20:35', '자동 실행 20:35', '대기 중'). 조치안이 없으면 null이다.
   * sim은 조치안의 상태가 바뀔 때 적고, http는 판단 목록이 싣지 않아 없다(AI 판단 화면의 판단 기록 표, 관리자 설계 4-10-2)
   */
  handled?: string | null;
}

/**
 * 모드 줄 알림(디자인 시스템 §12-9). 자동 되돌림, AI 정지와 재개, 자동 상한 도달, 유효 시간 만료 뒤 30초 동안
 * 모드 줄이 그 사건을 한 줄로 적는다. until이 지나면 관제 판이 싣지 않는다
 */
export interface AiNotice {
  /** EXECUTED는 운영자가 승인해 실행한 조치다. 결과(앱 계층 2 → 3대 · app-3 기동)를 첫 화면의 모드 줄에 30초 동안 적는다 */
  kind: "AUTO_REVERTED" | "STOPPED" | "RESUMED" | "AUTO_CAP" | "AUTO_EXPIRED" | "EXECUTED";
  at: string;
  until: string;
  text: string;
  tone: "warning" | "neutral";
  /** 사건 기록에서 열 사건. 사건 밖의 일이면 null */
  incidentId: string | null;
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
  /** 모드 줄 알림. 30초가 지났거나 알릴 일이 없으면 null */
  notice: AiNotice | null;
  /** 지난 24시간에 승인·자동으로 실행한 AI 조치 수와 그중 되돌린 수. 감사 기록에서 센다 */
  recent: { runs: number; reverts: number };
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
  trading: OpsSection<OpsTrading>;
  trends: OpsSection<OpsTrends>;
  /** sim 시나리오 재생. 재생 중이 아니거나 http면 null */
  scenario: OpsScenarioView | null;
  /**
   * 관제 서버가 붙인 원천의 상태(서버 계약 초안의 overview sources). 연동 설정 화면이 읽는다.
   * 관리 API에 관제 모듈이 없어 모르면 null이다
   */
  sources: OpsSourceState[] | null;
  /*
   * 아래는 3라운드 칸이다(관리자 설계 4-10-8). 두 원천이 채운 칸은 필수다(http는 서버 경로가 계약 초안이라 원천 연결
   * 전을 싣는다). 선택 칸은 원천이 채우기 전에는 없고, 화면은 칸이 없으면 원천 연결 전으로 그린다
   */
  /** 당번 명부와 호출 정책. sim은 예시 명부, http는 원천 연결 전(GET /admin/v1/msp/oncall 초안)이다 */
  oncall: OpsSection<OnCallDesk>;
  /**
   * 계획 작업 창과 변경 요청. sim은 관제 시뮬레이터, http는 창을 서버(§6-F maintenance-windows)에서 읽고 변경 요청은
   * 원천 연결 전(3라운드 계약 초안)이다
   */
  changes: OpsSection<ChangeDesk>;
  /** DB 통계. sim은 예시 값, http는 원천 연결 전(GET /admin/v1/msp/database 초안)이다. 복제 방식은 DB_TOPOLOGY가 싣는다 */
  database: OpsSection<DbStats>;
  /** 용량 전망과 앱 계층 활동 기록. sim은 예시와 시뮬레이터 기록, http는 원천 연결 전(GET /admin/v1/msp/capacity/outlook 초안)이다 */
  outlook: OpsSection<CapacityOutlook>;
  /* SLA 달은 달을 골라 읽으므로 이 판에 싣지 않는다. 관리 어댑터의 조회(getSlaMonth)가 이 판과 감사에서 만든다(4-10-8) */
}

export type AiDecision = "APPROVE" | "REJECT" | "DEFER";

/** 담당자와 연락 체계 한 줄. 역할 이름은 보고 서비스의 권고 담당 역할과 같다(intervention.py) */
export interface OnCallEntry {
  role: string;
  person: string;
  operatorId: string | null;
  /** 연락 경로 */
  channel: string;
  /** 대응 시간 */
  hours: string;
}

/** 관제 서버가 부르는 원천. teams는 관제 서버의 통지기이고, 일일·사건 보고의 Teams 전송은 보고 서비스가 한다 */
export type OpsSourceName = "prometheus" | "alertmanager" | "databasus" | "llm" | "teams";

/** READY는 붙어서 읽힘, NOT_CONNECTED는 설정 없음, UNAVAILABLE은 설정은 있으나 마지막 확인이 실패했다는 뜻이다 */
export interface OpsSourceState {
  name: OpsSourceName;
  state: "READY" | "NOT_CONNECTED" | "UNAVAILABLE";
  /** 마지막으로 성공한 확인 시각. 모르면 null */
  checkedAt: string | null;
  /** 실패 분류 코드(timeout, unreachable 등). READY면 null */
  reason: string | null;
}

/* ===== AI 상담(관리자 설계 4-6) =====
 * 운영자가 대화로 지금 상태를 묻는 칸이다. 조회만 하고 조치를 실행하지 않는다. 서버 계약 초안은
 * POST /admin/v1/msp/ai/chat이고(요청: 질문과 최근 대화, 응답: 답·근거·수집 시각·모델·상태) 연결 전이다. */

/** 질문 길이 상한(글자). 서버 계약 초안과 같고 입력칸이 남은 글자를 보인다 */
export const CHAT_MAX_CHARS = 500;
/** 질문과 함께 보내는 최근 대화 수. 운영자·AI가 세 번 주고받은 만큼이다 */
export const CHAT_RECENT_TURNS = 6;

export type AiChatRole = "operator" | "ai";

export interface AiChatTurn {
  role: AiChatRole;
  text: string;
}

/**
 * OK는 답이 있음, STOPPED는 AI 정지 중이라 답하지 않음, NOT_CONFIGURED는 상담 모델 설정이 없음, UNAVAILABLE은 모델
 * 호출이 실패했거나 모델을 적재하는 중이라 답하지 못함이다(서버 상담 state UNAVAILABLE)
 */
export type AiChatState = "OK" | "STOPPED" | "NOT_CONFIGURED" | "UNAVAILABLE";

export type AiChatEvidenceKind = "METRIC" | "ALERT" | "INCIDENT" | "PROPOSAL" | "THRESHOLD" | "HOST";

/** 답이 근거로 든 항목. id는 관제 판의 항목을 가리키고(예: alert:{id}), 값은 답을 낸 순간의 값이다 */
export interface AiChatEvidence {
  id: string;
  kind: AiChatEvidenceKind;
  label: string;
  value: string;
  /** 경보 근거면 그 경보의 심각도 */
  severity: AlertSeverity | null;
}

export interface AiChatReply {
  state: AiChatState;
  /** 합니다체 답. state가 OK가 아니면 빈 문자열이다 */
  answer: string;
  /**
   * 답보다 먼저 보일 서버 안내(합니다체). 근거가 오래됐거나 원천이 연결 전일 때의 머리말(preface)이거나, OK가 아닐 때
   * 서버가 쓴 안내 문장이다. sim은 null이다
   */
  note: string | null;
  evidence: AiChatEvidence[];
  /** 근거를 모은 시각 */
  collectedAt: string | null;
  /** 답을 낸 모델. sim은 모델이 아니라 규칙이라 그렇게 적는다 */
  model: string | null;
  /** 실행을 요청한 질문이라 실행하지 않고 조치 카드·승인 절차로 안내한 답인가 */
  declined: boolean;
  /** 답이 가리키는 절차. 화면은 이 값으로 그 절차가 있는 화면 링크만 보이고 실행 단추는 두지 않는다 */
  guide: AiChatGuide | null;
}

/**
 * 답이 안내하는 운영 절차. app-3 켜기·끄기, 임계값 변경, AI 모드, 경보 보류, 백업 즉시 실행, 대기 조치안의
 * 승인, 금지 목록(AI가 어느 모드에서도 하지 않는 일)이다
 */
export type AiChatGuide = "READ_NODES" | "THRESHOLD" | "MODE" | "SILENCE" | "BACKUP" | "PROPOSAL" | "FORBIDDEN";

/* ===== 운영 보고(관리자 설계 4-7) =====
 * bot-1 보고 서비스(deploy/reporting)가 만든 일일·사건 보고와 Teams 전송 기록이다. 관제 콘솔은 읽기만
 * 한다. 표시 모델은 보고 서비스의 공통 표시 모델(presentation.py build_brief)과 개입 판정(intervention.py)의
 * 필드를 그대로 옮긴 것이다. 서버 계약 초안은 보고 서비스 읽기 프록시(GET /admin/v1/msp/reports)이고
 * 연결 전이다. */

export type ReportKind = "daily" | "incident";

/** 보고 서비스의 작업 상태(reporter.py jobs.status). sent는 웹훅이 접수했다는 뜻이고 채널 게시의 증거가 아니다 */
export type ReportJobStatus = "pending" | "processing" | "queued" | "sending" | "sent" | "dead";

/** 개입 판정(intervention.py). P1 즉시 대응, P2 담당자 조치, P3 담당자 확인, P4 관찰 유지 */
export type InterventionCode = "immediate" | "scheduled" | "verify" | "monitor";

export interface ReportAction {
  priority: string;
  owner: string;
  timing: string;
  task: string;
  doneWhen: string;
}

export interface ReportMetric {
  label: string;
  value: string;
  detail: string;
  /** observed 관측, attention 기준 초과, unknown 확인 필요(자료 없음·오래됨) */
  status: "observed" | "attention" | "unknown";
}

export interface ReportEvent {
  name: string;
  /** 진행, 해제, 확인 필요 */
  statusLabel: string;
  target: string;
  startedAt: string;
  endedAt: string | null;
}

/** 보고 한 건의 전송 시도(reporter.py job_attempts) */
export interface ReportAttempt {
  attempt: number;
  at: string;
  status: "sent" | "queued" | "dead";
  /** 정제한 오류 코드(http_429 등). 성공이면 null */
  error: string | null;
}

export interface OpsReport {
  id: string;
  /**
   * 본문까지 실렸는가. 보고 서비스 읽기 프록시(GET /admin/v1/msp/reports)는 목록 요약만 주므로 http는 SUMMARY이고,
   * 이때 영향·조치·후속 관측·분석 칸은 비어 있고 화면이 연결 전으로 그린다. sim은 FULL이다
   */
  detail: "FULL" | "SUMMARY";
  kind: ReportKind;
  createdAt: string;
  sentAt: string | null;
  status: ReportJobStatus;
  title: string;
  /** 사건 전환(presentation.py status.code). 일일 보고는 unknown이나 mixed일 수 있다 */
  transition: "firing" | "resolved" | "mixed" | "unknown";
  intervention: { code: InterventionCode; priority: string; label: string; reason: string };
  customer: string;
  environment: string;
  /** 보고 기준 시각(근거 수집 시각) */
  collectedAt: string;
  windowStart: string;
  windowEnd: string;
  lead: string;
  impact: string;
  currentState: string;
  events: ReportEvent[];
  actions: ReportAction[];
  /** 후속 관측. 카드는 이 가운데 확인 필요가 아닌 넷을 싣는다 */
  metrics: ReportMetric[];
  analysis: {
    /** facts-only는 AI 없이 수집 근거로만 판정, 그 밖은 AI 참고 분석이 붙은 보고다 */
    mode: "facts-only" | "mock" | "gemini" | "remote";
    note: string;
    /** AI 요약. 없으면 빈 문자열이다 */
    summary: string;
    model: string | null;
  };
  /** 미확인 자료 */
  gaps: string[];
  /** 자동 조치 여부. 보고 서비스는 조치하지 않고, AI 자동 모드가 한 일은 그 사실을 적는다 */
  autoAction: string;
  /** PDF·HTML 보고서의 서명 주소. 파일이 없거나 서명할 수 없으면 null이다 */
  links: { pdf: string | null; html: string | null };
  attempts: ReportAttempt[];
}

export interface ReportDesk {
  reports: OpsReport[];
  delivery: {
    lastDailySentAt: string | null;
    /** 아직 보내지 않은 작업 수(pending·processing·queued·sending) */
    pending: number;
    /** 다시 보내지 않는 실패 수 */
    dead: number;
    nextDailyAt: string | null;
  };
  /** Teams 연결 시험(운영 자료를 싣지 않는 모의 전송) 기록. 최신이 앞이다 */
  tests: ReportAttempt[];
}

/* ===== 3라운드 화면(관리자 설계 4-10) =====
 * 사이드바 전체를 MSP 관점으로 넓히며 더하는 모양이다. 관제 판의 칸(ControlCenter의 선택 필드)은 sim이 관제
 * 시뮬레이터 한 판에서 내고 http는 서버 계약이 있는 것만 채운다. 조치 기록은 감사 기록을 가진 관리 어댑터가,
 * 런북·시나리오 라이브러리·모델 평가·허용 경로·비용 계획은 두 원천이 같은 정적 자료가 낸다. */

/** 호출 단계. 첫 알림 뒤 운영자 확인이 없으면 1차·2차·책임자로 올린다 */
export type EscalationLevel = 1 | 2 | 3;

/** 호출 정책 한 줄(4-10-7). 발생 뒤 확인 없이 afterMin분이 지나면 role에게 channel로 알린다 */
export interface EscalationStep {
  level: EscalationLevel;
  afterMin: number;
  role: string;
  channel: string;
}

/** 당번 명부, 호출 정책, 지금 당번(4-10-7) */
export interface OnCallDesk {
  roster: OnCallEntry[];
  /** 단계 순서다 */
  policy: EscalationStep[];
  /** 지금 당번. 명부에 교대가 없으면 null */
  onDuty: { role: string; person: string; operatorId: string | null; until: string } | null;
  /** 주 단위 당번 교대. 이번 주와 다음 주다. 교대가 없으면 빈 배열이다 */
  rotation: { person: string; operatorId: string | null; from: string; until: string }[];
}

/** 사건 담당. 이 사건을 맡은 사람이고 보고서의 권고 담당 역할(4-7)과 다르다 */
export interface IncidentOwner {
  operatorId: string | null;
  name: string;
  /** 연락 체계의 담당 역할 */
  role: string;
  assignedAt: string;
  /** 지정한 운영자. 당번 자동 배정이면 시스템이다 */
  assignedBy: string;
}

/** 보낸 호출 한 건. 운영자가 확인하면 ACKED, 해제로 멈추면 CLOSED다 */
export interface IncidentPage {
  level: EscalationLevel;
  at: string;
  role: string;
  /** 명부에서 찾은 사람. 명부가 없으면 null */
  person: string | null;
  channel: string;
  state: "SENT" | "ACKED" | "CLOSED";
}

/** 사후 분석. 해제된 사건에만 쓴다 */
export interface Postmortem {
  status: "DRAFT" | "DONE";
  cause: string;
  impact: string;
  /** 사건 기록에서 고른 줄과 운영자가 더한 줄. 오래된 것이 앞이다 */
  timeline: { at: string; text: string }[];
  /** 재발 방지 조치. due는 KST 날짜(YYYY-MM-DD)다 */
  actions: { task: string; owner: string; due: string; done: boolean }[];
  updatedAt: string;
  updatedBy: string;
}

/** 운영자가 쓰는 사후 분석 본문. 상태와 누가 언제는 원천이 정한다 */
export type PostmortemDraft = Pick<Postmortem, "cause" | "impact" | "timeline" | "actions">;

/**
 * AI 모드·정지 이력 한 줄(관리자 설계 4-10-2). 감사 기록의 ops.ai.* 행에서 옮긴다(aiHistory.ts). 사유와 유효 시간은
 * 감사 대상 칸이 싣는 만큼만 적고, 서버 감사 목록처럼 사유가 없으면 null이다
 */
export interface AiModeRow {
  /** 감사 기록 id. 조치 기록의 상세가 이 행을 연다 */
  auditId: string;
  at: string;
  kind: "AUTO_ON" | "AUTO_OFF" | "STOP" | "RESUME" | "EXPIRE" | "SYSTEM";
  /** 전환 전후 모드 */
  from: AiMode;
  to: AiMode;
  actor: OpsActor;
  actorName: string;
  reason: string | null;
  /** 자동 모드 유효 시간(시간). 자동 켜기가 아니면 null */
  hours: number | null;
  approvalId: string | null;
  /** 감사 원문 코드 */
  action: string;
}

/** 계획 작업 창(API 설계 §6-F). AI의 경보 보류는 이 창과 겹치는 warning 경보뿐이다(ADR 0035) */
export interface MaintenanceWindow {
  id: string;
  startsAt: string;
  endsAt: string;
  /** 대상 경보명. 라벨로만 고른 창은 null */
  alertname: string | null;
  /** 대상 라벨. 키는 node·role·component·mountpoint뿐이다 */
  labels: Partial<Record<WindowLabelKey, string>>;
  reason: string;
  createdBy: string;
  endedAt: string | null;
  endedBy: string | null;
  active: boolean;
  /** 이 창을 연 변경 요청. 창만 따로 등록했으면 null */
  changeId: string | null;
}

/** 계획 작업 창의 라벨 키. 서버가 받는 넷뿐이다(API 설계 §6-F 계획 작업 창) */
export type WindowLabelKey = "node" | "role" | "component" | "mountpoint";

/** 계획 작업 창 등록 입력. 시작을 비우면 지금이고 끝은 시작 뒤 24시간 이내다. 경보명과 라벨 가운데 하나는 있어야 한다 */
export interface WindowInput {
  startsAt: string | null;
  endsAt: string;
  alertname: string | null;
  labels: Partial<Record<WindowLabelKey, string>>;
  reason: string;
}

export type ChangeState = "REQUESTED" | "APPROVED" | "IN_PROGRESS" | "DONE" | "ROLLED_BACK" | "CANCELED";
export type ChangeRisk = "LOW" | "MEDIUM" | "HIGH";

/** 변경 요청(4-4 3라운드 계약 초안). 등록, 1인 승인(ops.change.approve), 진행, 종료, 검토로 흐른다 */
export interface ChangeRequest {
  id: string;
  title: string;
  target: string;
  plan: string;
  rollback: string;
  risk: ChangeRisk;
  startsAt: string;
  endsAt: string;
  /** 진행하며 열 계획 작업 창의 대상. 경보명과 라벨 */
  alertname: string | null;
  labels: Partial<Record<WindowLabelKey, string>>;
  state: ChangeState;
  requestedBy: string;
  requestedAt: string;
  approvalId: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  startedAt: string | null;
  startedBy: string | null;
  /** 진행하며 연 계획 작업 창 */
  windowId: string | null;
  /** 종료 때 적은 결과. 종료 전이면 null */
  result: string | null;
  closedAt: string | null;
  closedBy: string | null;
}

/** 변경 요청 등록 입력 */
export type ChangeInput = Pick<ChangeRequest, "title" | "target" | "plan" | "rollback" | "risk" | "startsAt" | "endsAt" | "alertname" | "labels">;

export interface ChangeDesk {
  /** 끝 시각 역순. 서버 목록(status=ALL)과 같은 순서다 */
  windows: MaintenanceWindow[];
  /** 변경 요청. 서버 경로가 3라운드 계약 초안이라 http는 원천 연결 전이다. 등록 순서의 역순이다 */
  changes: OpsSection<ChangeRequest[]>;
}

/** 복제 방식. 지금은 비동기이고 Patroni 엄격 동기는 구성 중이다(ADR 0024, ADR 0036은 다른 브랜치) */
export type ReplicationMode = "ASYNC" | "SYNC_STRICT";
/** DB 전환 방식. PATRONI_BUILDING은 설계·구현이 진행 중이고 이 환경에 적용하지 않았다는 뜻이다 */
export type FailoverMode = "MANUAL" | "PATRONI_BUILDING" | "PATRONI";

export interface DbStats {
  mode: ReplicationMode;
  failover: FailoverMode;
  connections: { used: number; max: number } | null;
  tps: number | null;
  cacheHitPct: number | null;
  wal: { primaryLsn: string; standbyReplayLsn: string } | null;
  sizeBytes: number | null;
  /** 느린 질의. 학교 DB에 pg_stat_statements를 적재하지 않아 두 원천 모두 연결 전이다 */
  slowQueries: OpsSection<{ query: string; calls: number; meanMs: number }[]>;
}

/** 노드 한 대의 디스크 전망. 하루 증가는 근거 구간의 추세이고, 두 예상 시각은 그 추세를 이은 예측이다 */
export interface DiskOutlook {
  host: string;
  /** 루트 볼륨 크기(GiB). 모르면 null */
  sizeGiB: number | null;
  usedPct: number;
  /** 하루 증가(%p). 근거가 모자라면 null */
  growthPctPerDay: number | null;
  /** 디스크 여유 경보 기준(host.disk_free_alert)에 닿을 예상 시각. 줄어들거나 근거가 없으면 null */
  alertAt: string | null;
  /** 가득 찰 예상 시각. 줄어들거나 근거가 없으면 null */
  fullAt: string | null;
}

/** 앱 계층 활동 기록 한 줄(app-3 켜기·끄기). 행위자가 AI면 승인 실행인지 자동 실행인지를 via가 가른다 */
export interface TierActivity {
  id: string;
  at: string;
  action: "ON" | "OFF";
  actor: OpsActor;
  actorName: string;
  /**
   * 누가 어떤 길로 바꿨나. 운영자 직접(용량·부하 화면), AI 조치 승인, AI 자동 실행, 되돌림, 시나리오 무대 준비(sim), AWS 지표
   * 정책(시나리오 재생의 AI 서버 무응답에서 AI 대신 대수를 바꾸는 ASG 정책 흉내, 관리자 설계 4-10-9)
   */
  via: "OPERATOR" | "AI_APPROVED" | "AI_AUTO" | "REVERT" | "AUTO_REVERT" | "SCENARIO" | "POLICY";
  reason: string;
  /** 기동(켜기)이나 드레이닝(끄기)에 걸린 초. 아직 끝나지 않았거나 끝나기 전에 멈췄으면 null */
  tookSec: number | null;
  /** 끝나기 전에 반대 조치가 들어와 멈췄는가(내리는 중에 다시 켬) */
  interrupted: boolean;
  /** 이 일이 속한 사건. 사건 밖이면 null */
  incidentId: string | null;
}

/** 용량 전망과 앱 계층 활동 기록(4-10-3). 전망은 예측이라 근거 구간을 함께 싣는다 */
export interface CapacityOutlook {
  /** 근거 구간(시간). 24시간보다 짧으면 예상 시각을 내지 않는다 */
  basisHours: number;
  disks: DiskOutlook[];
  dbGrowthBytesPerDay: number | null;
  /** app-3 켜기·끄기. 최신이 앞이다 */
  activity: TierActivity[];
}

/** 호스트 자산(4-10-3). 원천이 없는 칸은 null이다 */
export interface HostAsset {
  os: string | null;
  kernel: string | null;
  bootedAt: string | null;
  inodeUsedPct: number | null;
  /** 실패한 systemd 서비스. 없으면 빈 배열이다 */
  failedUnits: string[] | null;
  /** 보안 업데이트 대기 수 */
  securityUpdates: number | null;
}

/**
 * 인증서 한 장(4-10-3). 만료는 기록이 날짜만 적은 인증서가 있어 KST 날짜(expiresOn)로 싣고, 시각까지 기록된 인증서만
 * expiresAt을 채운다. 남은 날은 KST 날짜의 차이로 센다
 */
export interface CertificateInfo {
  /** 도메인이나 인증서 이름 */
  domain: string;
  /** 쓰는 곳 */
  purpose: string;
  issuer: string;
  /** 만료 날짜(YYYY-MM-DD, KST) */
  expiresOn: string;
  /** 만료 시각. 기록에 시각이 없으면 null */
  expiresAt: string | null;
  /** 날짜가 UTC 기준으로 기록된 인증서(DB TLS). 화면이 'UTC 날짜'를 덧붙인다 */
  utcDate: boolean;
  /** 갱신 방식. 자동 갱신 타이머나 자동 갱신 없음 */
  renewal: string;
  autoRenew: boolean;
  /** 마지막 모의 갱신 성공 날짜(KST). 모르거나 모의 갱신이 없으면 null */
  lastDryRunOn: string | null;
}

/**
 * 쓰기 상태(4-10-8). FAILING은 DB에 쓰지 못해 주문이 실패하는 상태, SUSPENDED는 엄격 동기에서 Standby가 없어
 * 거래를 일시 중단하고 조회만 받는 상태다(ADR 0036 적용 뒤)
 */
export type WriteState = "OPEN" | "FAILING" | "SUSPENDED";

/**
 * SLA 한 달(4-10-5). 가용성은 외부 프로브 성공률이다. 주문 API SLO(API 설계 §7-4)와 다른 지표다. 달을 골라 읽으므로 관제 판에
 * 싣지 않고 관리 어댑터의 조회(getSlaMonth)가 낸다. sim은 관제 판의 기록이 닿는 날(BOARD)을 판과 감사에서 세고, 그 앞의 날은
 * 시뮬레이터 예시(SEED)다
 */
export interface SlaMonth {
  /** YYYY-MM(KST) */
  month: string;
  /** 센 마지막 날(KST). 이번 달이면 오늘, 지난 달이면 그 달의 끝이다 */
  throughDay: string;
  daysInMonth: number;
  targetPct: number;
  /** 센 날의 분에서 낸 가용성. 원천이 없으면 null */
  availabilityPct: number | null;
  /** 오류 예산(분). 허용은 그 달 전체의 분 x (100 - 목표)%다. 쓴 값을 모르면 null */
  budget: { allowedMin: number; usedMin: number | null };
  incidents: {
    total: number;
    bySeverity: Record<AlertSeverity, number>;
    mttaMs: number | null;
    mttrMs: number | null;
    /** 사후 분석을 마친 사건 수 */
    postmortems: number;
  };
  changes: { done: number; rolledBack: number };
  /**
   * 그 달의 AI 조치. runs는 승인·자동 실행 수, autoRuns는 그 가운데 자동 실행, reverts는 되돌림(운영자·자동), autoReverts는 그 가운데
   * 관찰 창의 자동 되돌림이다. recent는 AI 판단 화면의 '지난 24시간 AI 조치 n회 · 되돌림 m회'와 같은 감사에서 센 값이다(이번 달만)
   */
  ai: { runs: number; autoRuns: number; reverts: number; autoReverts: number; recent: { runs: number; reverts: number } | null };
  /** 날마다(센 날까지). SEED는 시뮬레이터 예시, BOARD는 관제 판에서 센 날이다 */
  days: SlaDay[];
  /** 그 달의 사건. 관제 판의 사건은 사건 드로어를 열고, 예시 사건은 열 곳이 없다. 최신이 앞이다 */
  incidentRows: SlaIncident[];
  /** 그 달에 끝난 변경 요청(완료·되돌림). 관제 판의 요청은 변경 요청 드로어를 연다. 최신이 앞이다 */
  changeRows: SlaChange[];
  /** 그 달의 수에서 나온 권고. basis는 근거 수를 적은 글이다. 규칙에 걸린 것이 없으면 빈 배열이다 */
  recommendations: { rule: SlaRule; text: string; basis: string }[];
}

export interface SlaDay {
  day: string;
  availabilityPct: number | null;
  /** 가용하지 않았던 분(외부 프로브 실패율 x 분) */
  unavailMin: number | null;
  incidents: number;
  /** 그날 연 사건의 평균 확인·해제 시간. 값이 있는 사건이 없으면 null */
  mttaMs: number | null;
  mttrMs: number | null;
  source: "SEED" | "BOARD";
}

export interface SlaIncident {
  id: string;
  title: string;
  /** 사건을 연 경보 이름. 같은 경보로 연 사건을 세는 권고가 쓴다 */
  alert: string;
  severity: AlertSeverity;
  openedAt: string;
  resolvedAt: string | null;
  ttaMs: number | null;
  ttrMs: number | null;
  /** 확인 전에 보낸 가장 높은 호출 단계. 없으면 0 */
  pagedLevel: 0 | EscalationLevel;
  postmortem: "DONE" | "DRAFT" | "NONE";
  source: "SEED" | "BOARD";
}

export interface SlaChange {
  id: string;
  title: string;
  state: "DONE" | "ROLLED_BACK";
  closedAt: string;
  result: string | null;
  source: "SEED" | "BOARD";
}

/** SLA 권고 규칙(4-10-5). 근거 수에서만 문장을 만든다 */
export type SlaRule = "BUDGET" | "REPEAT" | "AUTO_REVERT" | "PAGED" | "ROLLED_BACK";

/** 조치 기록의 종류(4-10-4). 서버와 sim의 액션 코드가 달라도 같은 종류로 묶는다 */
export type ActionCategory =
  | "ALERT"
  | "AI_ACTION"
  | "AI_MODE"
  | "THRESHOLD"
  | "AI_LIMIT"
  | "CAPACITY"
  | "BOT"
  | "BACKUP"
  | "WINDOW"
  | "CHANGE"
  | "INCIDENT"
  | "REPORT"
  | "SESSION"
  | "OTHER";

/** 조치 기록 한 줄. 관리 어댑터가 감사 기록과 사건 기록을 옮기고, 관제 판에는 싣지 않는다 */
export interface ActionRecord {
  id: string;
  at: string;
  actor: OpsActor;
  /** 인증 주체(운영자 id, ai-ops, system:msp) */
  actorId: string;
  actorName: string;
  category: ActionCategory;
  /** 감사 원문 코드. 화면은 한국어 주해와 함께 원문을 늘 보인다 */
  action: string;
  target: string;
  /** 읽기 쉬운 대상(AI 조치 요청의 제목 등). 원문 대상만 있으면 null이다 */
  title: string | null;
  /** 결과. 성공한 조치를 뒤에 되돌렸으면 REVERTED다 */
  result: AuditResult | "REVERTED";
  approvalId: string | null;
  incidentId: string | null;
  /** AI 조치를 낸 판단 기록. 없으면 null */
  judgmentId: string | null;
  /** 이 조치를 되돌린 기록 */
  revertedBy: string | null;
  /** 이 조치가 되돌린 기록 */
  revertOf: string | null;
  /** 같은 요청의 감사 줄을 잇는 요청 id. 없으면 null */
  requestId: string | null;
  /** 감사 원문. 상세 모달이 접힌 JSON으로 보인다 */
  raw: Record<string, unknown>;
}

/**
 * 조치 기록 조회 한 번의 결과. 서버 감사는 커서로 나눠 읽으므로 받아 온 범위만 있고(partial), 더 읽을 커서를 싣는다.
 * 승인 티켓의 발급·소각 시각은 승인 원장의 한 쪽에서 찾는다
 */
export interface ActionFeed {
  origin: OpsOrigin;
  rows: ActionRecord[];
  approvals: ApprovalRequest[];
  /** 더 이전 기록을 읽는 커서. 끝이면 null */
  nextCursor: string | null;
  /** 받아 온 쪽만 담았는가. sim은 기록 전체라 false다 */
  partial: boolean;
}

/** 런북(4-10-4). 운영자가 SSM 세션에서 실행하는 절차이고 콘솔에 실행 단추가 없다 */
export type RunbookId = "DB_PROMOTE" | "APP_RESTART" | "DISK_CLEANUP" | "CERT_RENEW" | "BOT_STOP" | "BACKUP_RESTORE";

export interface RunbookStep {
  title: string;
  detail: string;
  /** 다음 단계로 가기 전에 확인할 것 */
  check: string;
  /** 원문 문서에 적힌 명령만 옮긴다. 없으면 빈 배열이다 */
  commands: string[];
}

export interface Runbook {
  id: RunbookId;
  title: string;
  when: string;
  /** AI가 하지 않는 이유(ADR 0035 금지 목록 번호나 허용 목록 밖) */
  notForAi: string;
  /** 관련 경보. sim 경보 이름과 서버 경보 규칙 이름 */
  alerts: { sim: string | null; rule: string | null }[];
  steps: RunbookStep[];
  rollback: string;
  /** 원문(저장소 경로와 절 이름) */
  sources: { path: string; section: string }[];
}

/** 시나리오 라이브러리(4-10-9). live가 있는 것만 관제 판에 실시간으로 걸 수 있다 */
export type ScenarioId =
  | "READ_SURGE"
  | "NIGHT_SCALE_IN"
  | "DB_PRIMARY_DOWN"
  | "SYNC_STANDBY_LOST"
  | "DISK_LOW"
  | "BOT_RUNAWAY"
  | "AI_SERVER_DOWN"
  | "PROMPT_INJECTION"
  | "CERT_EXPIRY";

/**
 * 재생의 전제. NONE은 지금 구성, PATRONI_APPLIED는 ADR 0036(2026-09-26 main 병합, 어느 환경에도 아직 적용 전)을 적용한 뒤, AWS_ASG_POLICY는 AWS 지표
 * 정책이 앱 계층 대수를 소유하는 환경이다. NONE이 아니면 재생 판에 가정 표식을 단다
 */
export type ScenarioAssumption = "NONE" | "PATRONI_APPLIED" | "AWS_ASG_POLICY";

/**
 * 재생 세계에 거는 일(4-10-9). 대본의 장애·회복(시스템)과 사람이 SSM에서 한 런북 단계의 효과다. 관제 시뮬레이터는 이 어휘만
 * 받고, 무엇을 언제 거는지는 시나리오 대본(scenarios.ts)이 정한다. NOTE는 세계를 바꾸지 않고 사람의 확인만 기록에 남긴다
 */
export type ScenarioOp =
  | "DB_PRIMARY_LOST"
  | "DB_ISOLATE"
  | "DB_PROMOTE"
  | "DB_SWITCH"
  | "DB_REJOIN"
  | "STANDBY_LOST"
  | "STANDBY_BACK"
  | "DISK_FILL"
  | "DISK_CLEANED"
  | "BOTS_RESUMED"
  | "BOT_RUNAWAY"
  | "AI_DOWN"
  | "INJECTED_ALERT"
  | "INJECTED_CLEAR"
  | "CERT_RENEWED"
  | "NOTE";

/**
 * 시나리오 단계. 단계가 서는 때는 재생 세계의 기록에서 찾는다. START는 0초이고, EVENT는 앞 단계가 선 뒤의 첫 기록(종류, 행위자,
 * 글의 머리)이다. 같은 단계라도 수동과 자동에서 서는 때가 다르다
 */
export interface ScenarioStage {
  key: string;
  title: string;
  /** 이 단계에서 보일 것 */
  expect: string;
  on: { kind: "START" } | { kind: "EVENT"; events: IncidentEventKind[]; actor?: OpsActor; prefix?: string };
  /* prefix는 기록 글의 머리다. 같은 종류의 기록(해제와 경보 해제, 런북 단계)을 가른다 */
}

/**
 * 재생 대본 한 줄. WORLD는 대본의 장애·회복(시스템)이고 나머지는 사람 몫이다. APPROVE_FIRST와 ACK_ALERT는 콘솔 결정, RUNBOOK_STEP과
 * RESUME_BOTS는 SSM에서 한 런북 단계, CHECK는 런북이 없는 상황에서 사람이 한 확인이다
 */
export interface ScenarioCue {
  atSec: number;
  /** 이 대본이 도는 모드. ANY는 두 모드 모두다 */
  mode: "MANUAL" | "AUTO" | "ANY";
  kind: "WORLD" | "APPROVE_FIRST" | "ACK_ALERT" | "RUNBOOK_STEP" | "RESUME_BOTS" | "CHECK";
  /** 세계에 거는 일. APPROVE_FIRST와 ACK_ALERT는 콘솔 결정이라 없다 */
  op?: ScenarioOp;
  /** 런북 단계 번호(1부터). RUNBOOK_STEP만 싣는다 */
  step?: number;
  /** ACK_ALERT가 확인할 경보 이름. 없거나 서 있지 않으면 가장 무거운 경보다 */
  alert?: string;
  /** 운영자 칸과 기록에 남기는 말 */
  note: string;
}

/** 시나리오 목록의 표지. LIVE는 관제 판에 걸 수 있음, PATRONI는 Patroni 적용 가정, NO_AI는 AI 조치 없음이다 */
export type ScenarioTag = "LIVE" | "PATRONI" | "ASG_POLICY" | "NO_AI";

export interface ScenarioSpec {
  id: ScenarioId;
  title: string;
  question: string;
  /** 사실 전제(지금 구성, 적용 전 구성) */
  premise: string[];
  assumption: ScenarioAssumption;
  durationSec: number;
  stages: ScenarioStage[];
  cues: ScenarioCue[];
  runbookId: RunbookId | null;
  /** 관제 판에 실시간으로 거는 시나리오의 id. 재생 전용이면 null */
  live: OpsScenarioId | null;
  tags: ScenarioTag[];
  /** AI 조치가 없는 재생에서 정책 엔진 칸이 대조하는 금지 목록 번호(ADR 0035, aiPolicy.AI_FORBIDDEN의 1부터) */
  forbidden: number[];
  /** 통지 등급과 그 근거(보고 서비스의 개입 판정) */
  notice: { priority: "P1" | "P2" | "P3" | "P4"; basis: string };
  /** 재생 판에 적는 한계. 측정·지금 구성과 다른 점이다 */
  caveats: string[];
}

/** 모델 평가 한 행(4-10-2). 측정 문서의 값을 옮긴 정적 기록이라 글자 그대로 싣는다 */
export interface ModelEvalRow {
  model: string;
  think: string | null;
  wrongExec: number;
  approvalWrong: number;
  correct: string;
  schema: string;
  consistent: string;
  latency: string;
  honorific: string;
  outOfCatalog: number;
  injection: string;
  chat: string;
}

export interface ModelEvaluation {
  /** 측정 문서 경로 */
  source: string;
  measuredAt: string;
  where: string;
  promptVersion: string;
  rows: ModelEvalRow[];
  errorTypes: string[];
  next: string;
}

/** 보안 그룹 허용 경로의 설계 의도(4-10-3). AWS에서 읽은 값이 아니다 */
export interface AllowedPath {
  from: string;
  to: string;
  port: string;
  purpose: string;
  /** 근거(저장소 경로) */
  source: string;
  /** 학교 계정에 콘솔로 적용한 기록. 기록이 없으면 null */
  school: string | null;
}

/** 비용 계획(4-10-5). 두 원천이 같은 정적 입력이고 app-3 가동 시간만 관제 판에서 읽는다 */
/** 비용 계획의 입력. 달러는 부동소수점으로 셈하지 않도록 소수 넷째 자리까지의 십진 문자열로 둔다('6.73', '0.0335') */
export interface CostPlan {
  /** 개인 계정 크레딧(달러)과 그 값의 출처 */
  creditUsd: string;
  creditNote: string;
  /** 운영 기간(KST 날짜, 양 끝 포함) */
  startDay: string;
  endDay: string;
  /** 계획 하루 비용(달러). app-3을 늘 켠 경우를 따로 둔다 */
  dailyUsd: string;
  dailyWithApp3Usd: string;
  /** app-3 한 대의 계획 단가(달러/시간) */
  app3HourlyUsd: string;
  /** 계산에서 뺀 것 */
  excluded: string[];
  school: { monthlyKrw: number; note: string; reference: { label: string; usd: string } };
}
