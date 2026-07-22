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

/* ===== 이중 승인 (§1-4, §3-2) ===== */

export type DestructiveAction =
  | "chaos.inject"
  | "chaos.clear"
  | "sim.bots.params"
  | "config.market.halt"
  | "config.market.resume"
  | "operators.roles";

/**
 * admin_approvals.state 정본 어휘(V5__admin.sql CHECK). 비정본 PENDING·APPROVED를 폐기하고
 * ISSUED로 수렴한다(관리자 설계 §7-7). "승인 대기"와 "실행 대기"는 별도 상태가 아니라
 * ISSUED + approverId 유무로 파생한다(ApprovalList phaseOf).
 */
export type ApprovalState = "ISSUED" | "CONSUMED" | "EXPIRED" | "REJECTED";

export type ApprovalPayload =
  | { action: "chaos.inject"; experimentId: string }
  | { action: "chaos.clear"; runId: string }
  | { action: "sim.bots.params"; botId: string; params: Record<string, string> }
  | { action: "config.market.halt"; symbol: string }
  | { action: "config.market.resume"; symbol: string }
  | { action: "operators.roles"; operatorId: string; roles: AdminRole[] };

/**
 * 승인 토큰 원장(admin_approvals). 단회 소각, TTL 5분, action+대상 바인딩.
 * requesterId <> approverId를 앱·서버 이중 강제(반결탁, §1-3 (a)~(c)).
 */
export interface ApprovalRequest {
  id: string;
  action: DestructiveAction;
  targetKey: string; // action+대상 바인딩 키
  targetLabel: string;
  payload: ApprovalPayload;
  requesterId: string;
  requesterName: string;
  approverId: string | null;
  approverName: string | null;
  state: ApprovalState;
  issuedAt: string;
  expiresAt: string;
  consumedAt: string | null;
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

export interface Bot {
  id: string;
  type: BotType;
  market: string;
  active: boolean;
  ops: number;
  params: Record<string, string>; // 문자열 표기(정밀도 보존)
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

export interface ConfigMarket {
  symbol: string;
  koreanName: string;
  state: "ACTIVE" | "SUSPENDED";
  makerFee: string; // 문자열 수수료율
  takerFee: string;
}

export interface Notice {
  id: string;
  title: string;
  body: string;
  createdAt: string;
  createdBy: string;
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
