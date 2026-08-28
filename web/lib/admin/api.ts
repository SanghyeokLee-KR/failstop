/**
 * lib/admin/api.ts. 관리 평면 데이터 접근 추상화.
 * 고객 평면 DataSource(lib/api/datasource.ts)와 분리된 인터페이스·싱글턴이다.
 * 화면은 이 인터페이스만 소비하고 시뮬레이터나 fetch를 직접 부르지 않는다.
 * 구현: SimAdminApi(데모, 기본)와 HttpAdminApi(Spring /admin/v1/* 어댑터).
 */

import { SimAdminApi } from "./simAdmin";
import { HttpAdminApi } from "./httpAdmin";
import type {
  AdminPage,
  PageOpts,
  AdminOverview,
  AdminSession,
  ApprovalRequest,
  ApprovalState,
  BotType,
  ChaosExperimentDef,
  ChaosRun,
  ConfigMarket,
  CreateBotResult,
  DemoClientCert,
  DestructiveAction,
  ApprovalPayload,
  AdminOperator,
  AdminRole,
  AuditEntry,
  DirectionBiasResult,
  FleetScaleResult,
  FleetScaleTargets,
  HaRecovery,
  HaStatus,
  InvariantCheck,
  IssuedApproval,
  Notice,
  NoticePatch,
  NoticeSeverity,
  ScenarioName,
  ScenarioResult,
  SimStatus,
  TradeFlash,
  AdminInquiry,
  AdminTransfer,
  CommunityReport,
  ModerationAction,
  ReportResolution,
  ReportStatus,
  AdminTransferStatus,
} from "./types";

/** 표준 관리 에러. name에 API §2-3 코드를 노출(jwt_verification·admin_forbidden 등) */
export class AdminError extends Error {
  readonly code: string;
  /** 429 응답의 Retry-After(초). 폴링 백오프 근거. 없으면 null */
  readonly retryAfter: number | null;
  constructor(code: string, message: string, retryAfter: number | null = null) {
    super(message);
    this.name = code;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

export interface AdminApi {
  /* ===== 인증(§1-3) ===== */
  /** 브라우저 인증서 저장소 설치분(데모 mTLS 인증서 목록) */
  listDemoCerts(): DemoClientCert[];
  /** mTLS 인증서 제시로 신원 유래 단기 세션 확립. certId=CN=operatorId */
  establishSession(certId: string): Promise<AdminSession>;
  getSession(): AdminSession | null;
  endSession(): void;
  /** 세션 변화 통지(로그인·전환·만료). 등록 즉시 현재 상태 1회 통지 */
  onSessionChange(cb: (s: AdminSession | null) => void): () => void;

  /* ===== 이중 승인: 발급형(§3-6) ===== */
  /**
   * 승인 발급. 발급 주체(현재 세션)가 요청 운영자(requesterId)와 다를 때만 성립(반결탁).
   * 액션+대상에 바인딩된 단회 토큰을 반환한다. 토큰은 이 응답에만 실린다(원장 프로젝션 제외).
   */
  issueApproval(requesterId: string, action: DestructiveAction, payload: ApprovalPayload): Promise<IssuedApproval>;
  /** 승인 원장 조회(state 필터). token은 반환하지 않는다 */
  /** 승인 이력 한 쪽. 소비·만료 뒤에도 남아 누적된다 */
  listApprovals(state?: ApprovalState, opts?: PageOpts): Promise<AdminPage<ApprovalRequest>>;
  /** ISSUED 승인 거부(ISSUED->REJECTED). 발급 주체 또는 요청 주체만 가능(소유·반결탁) */
  rejectApproval(approvalId: string): Promise<ApprovalRequest>;
  /** 요청 운영자 본인이 발급 토큰을 파괴 액션 바디에 실어 소각(실효 액션 실행) */
  consumeApproval(approval: IssuedApproval): Promise<void>;

  /* ===== 모니터링(§2-3, 읽기 전용) ===== */
  getOverview(): Promise<AdminOverview>;
  getHaStatus(): Promise<HaStatus>;
  getRecovery(): Promise<HaRecovery>;
  getInvariants(): Promise<InvariantCheck[]>;

  /* ===== 봇 플릿(§2-1) ===== */
  /** start·stop·pause가 플릿 상태에 즉시 반영되는지. sim·http(v2.22 실반영) 모두 true다.
   * 반영 불가 케이스(플릿 빈 부재)는 어댑터가 응답 applied:false를 에러로 승격해 전달한다.
   * false를 반환하는 어댑터는 화면이 기록 사실만 알리는 문구로 강등한다 */
  readonly controlApplied: boolean;
  getSimStatus(): Promise<SimStatus>;
  simStart(): Promise<void>;
  simStop(): Promise<void>;
  simPause(): Promise<void>;
  simSetRate(rate: number): Promise<void>;
  /** 봇 생성(governor set-point 상향). count만큼 유형별 목표 대수를 올린다 */
  createBot(type: BotType, market: string, count?: number): Promise<CreateBotResult>;
  /** 봇 제거(DELETE /admin/v1/sim/bots/{id}). 승인 불요 */
  deleteBot(botId: string): Promise<void>;

  /* ===== 시장 생동감(§3-2~3-5). approval 불요, 전량 감사 ===== */
  /** 타입별 목표 대수 set-point. 서버가 현재·목표 차이를 수렴한다 */
  setFleetScale(market: string, targets: FleetScaleTargets): Promise<FleetScaleResult>;
  /** 마켓 방향 바이어스·자동균형. net bias·strength는 문자열(float 금지) */
  setDirectionBias(market: string, targetNetBias: string, autoBalance: boolean, strength: string): Promise<DirectionBiasResult>;
  /** 체결 시각효과 강도 저장(system_config). 트레이딩 UI가 공개 조회로 읽는다 */
  setEffectConfig(tradeFlash: TradeFlash): Promise<TradeFlash>;
  /**
   * 시장 국면 프리셋 활성화(§6-A v2.31). 전역 OPS·유형별 대수·bias를 한 번에 얹는다.
   * rate·scale·bias 세 경로로 도달 가능한 상태만 만들므로 승인 불요다.
   * 값은 기준선 대비 배수라 같은 프리셋을 두 번 눌러도 결과가 같다. steady가 복귀 경로다.
   */
  activateScenario(name: ScenarioName): Promise<ScenarioResult>;

  /* ===== 카오스(§2-2) ===== */
  getChaosCatalog(): ChaosExperimentDef[];
  /** 실험 이력 한 쪽. 감사 로그에 영구 누적된다 */
  getChaosRuns(opts?: PageOpts): Promise<AdminPage<ChaosRun>>;

  /* ===== 마켓 설정(§2, config) ===== */
  getConfigMarkets(): Promise<ConfigMarket[]>;
  /**
   * 공지 목록 한 쪽. 철회분 포함. 커서는 §2-6 opaque.
   *
   * 공지는 DELETE 권한이 없어(V5 §7) 철회만 되고 행은 계속 쌓인다. 고정 상한으로
   * 자르면 오래된 공지가 운영자에게도 안 보인다.
   */
  getNotices(opts?: { cursor?: string | null; limit?: number }): Promise<AdminPage<Notice>>;
  postNotice(title: string, body: string, severity?: NoticeSeverity): Promise<Notice>;
  updateNotice(id: string, patch: NoticePatch): Promise<Notice>;
  /** 소프트 삭제(active=false). notices DELETE 권한이 없어 하드 삭제하지 않는다(§6-D) */
  deleteNotice(id: string): Promise<void>;

  /* ===== 운영자·감사(§3-1) ===== */
  /* 출금 승인 큐(§4-E). 목록은 VIEWER, 처리는 MEMBER_MOD */
  /** 출금 큐 한 쪽. COMPLETED·CANCELED는 처리 뒤에도 남는다 */
  getTransfers(status?: AdminTransferStatus, opts?: PageOpts): Promise<AdminPage<AdminTransfer>>;
  approveTransfer(externalId: string): Promise<AdminTransfer>;
  rejectTransfer(externalId: string, reason: string): Promise<AdminTransfer>;

  /* 문의 답변 큐(§4-G). 목록은 VIEWER, 답변은 MEMBER_MOD */
  /** 문의 큐 한 쪽. ANSWERED는 답변 뒤에도 남는다 */
  getInquiries(status?: "OPEN" | "ANSWERED", opts?: PageOpts): Promise<AdminPage<AdminInquiry>>;
  answerInquiry(externalId: string, answer: string): Promise<AdminInquiry>;

  /* 커뮤니티 운영 큐(§6-D). 목록·처리 전부 MEMBER_MOD */
  /** 신고 큐 한 쪽. 기본 PENDING. 처리 뒤에도 종결 상태로 남아 누적된다 */
  getCommunityReports(status?: ReportStatus, opts?: PageOpts): Promise<AdminPage<CommunityReport>>;
  /** 신고 종결. PENDING에서만 전이하고 대상 자체는 건드리지 않는다 */
  resolveCommunityReport(reportId: number, resolution: ReportResolution, note?: string): Promise<void>;
  /**
   * 게시글·댓글 상태 전이. externalId를 받는다(내부 id 비공개 규약).
   * DELETE는 종료 상태라 UNHIDE로 되돌아오지 않는다.
   */
  moderateCommunityTarget(
    targetType: "POST" | "COMMENT",
    externalId: string,
    action: ModerationAction,
    note?: string,
  ): Promise<void>;

  getOperators(): Promise<AdminOperator[]>;
  /** 감사 로그 한 쪽. append-only라 영구 누적이다 */
  getAudit(opts?: PageOpts): Promise<AdminPage<AuditEntry>>;
}

/** 화면이 소비할 때 세션이 필요함을 알린다(요청 요구 권한과 별개) */
export function requireSession(api: AdminApi): AdminSession {
  const s = api.getSession();
  if (!s) throw new AdminError("jwt_verification", "관리자 세션 없음. mTLS 인증서로 접속 필요");
  return s;
}

/* ===== 싱글턴 ===== */

let singleton: AdminApi | null = null;

function createAdminApi(): AdminApi {
  if (process.env.NEXT_PUBLIC_DATASOURCE === "http") {
    return new HttpAdminApi(process.env.NEXT_PUBLIC_ADMIN_BASE ?? "");
  }
  return new SimAdminApi();
}

export function getAdminApi(): AdminApi {
  if (!singleton) singleton = createAdminApi();
  return singleton;
}

export type { AdminRole };
