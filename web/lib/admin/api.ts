/**
 * 고객 평면 DataSource와 분리된 인터페이스·싱글턴이다.
 * 화면은 이 인터페이스만 소비하고 시뮬레이터나 fetch를 직접 부르지 않는다.
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
  CapacityState,
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
  ControlApply,
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
  AdminMember,
  AdminMemberDetail,
  CommunityReport,
  MemberStatus,
  MemberTransition,
  ModerationAction,
  ReportResolution,
  ReportStatus,
  AdminTransferStatus,
  AiDecision,
  AiChatReply,
  AiJudgment,
  IncidentEvent,
  TrendRange,
  AiChatTurn,
  ControlCenter,
  OnCallEntry,
  OpsScenarioId,
  OpsSection,
  ReportDesk,
} from "./types";

/** 표준 관리 에러. name에 API §2-3 코드를 노출(jwt_verification·admin_forbidden 등) */
export class AdminError extends Error {
  readonly code: string;
  /** 429 응답의 Retry-After(초). 폴링 백오프 근거. 없으면 null */
  readonly retryAfter: number | null;
  /** 서버가 답한 HTTP 상태. 본문의 오류 이름이 상태와 다를 때(404인데 다른 이름) 상태로 가른다. 서버 응답이 아니면 null */
  readonly status: number | null;
  constructor(code: string, message: string, retryAfter: number | null = null, status: number | null = null) {
    super(message);
    this.name = code;
    this.code = code;
    this.retryAfter = retryAfter;
    this.status = status;
  }
}

export interface AdminApi {
  /* ===== 인증(§1-3) ===== */
  /** 브라우저 인증서 저장소 설치분(데모 mTLS 인증서 목록) */
  listDemoCerts(): DemoClientCert[];
  /** mTLS 인증서 제시로 신원 유래 단기 세션 확립. certId=CN=operatorId */
  establishSession(certId: string, password?: string): Promise<AdminSession>;
  getSession(): AdminSession | null;
  endSession(): void;
  /** 세션 변화 통지(로그인·전환·만료). 등록 즉시 현재 상태 1회 통지 */
  onSessionChange(cb: (s: AdminSession | null) => void): () => void;

  /* ===== 승인 티켓: 발급형(§3-6) ===== */
  /**
   * 승인 발급. 1인 승인이라 발급 주체(현재 세션)가 요청 운영자(requesterId)와 같아도 성립한다(ADR 0019).
   * 액션+대상에 바인딩된 단회 토큰을 반환한다. 토큰은 이 응답에만 실린다(원장 프로젝션 제외).
   */
  issueApproval(requesterId: string, action: DestructiveAction, payload: ApprovalPayload): Promise<IssuedApproval>;
  /** 승인 원장 조회(state 필터). token은 반환하지 않는다 */
  /** 승인 이력 한 쪽. 소비·만료 뒤에도 남아 누적된다 */
  listApprovals(state?: ApprovalState, opts?: PageOpts): Promise<AdminPage<ApprovalRequest>>;
  /** ISSUED 승인 거부(ISSUED->REJECTED). 발급 주체 또는 요청 주체만 가능(소유 검사) */
  rejectApproval(approvalId: string): Promise<ApprovalRequest>;
  /** 요청 운영자 본인이 발급 토큰을 파괴 액션 바디에 실어 소각(실효 액션 실행) */
  consumeApproval(approval: IssuedApproval): Promise<void>;

  /* ===== 모니터링(§2-3, 읽기 전용) ===== */
  getOverview(): Promise<AdminOverview>;
  getHaStatus(): Promise<HaStatus>;
  /** since(ISO-8601)를 주면 판정과 행을 그 시각 이후로 좁힌다(API§6-C). 없으면 전체 이력이다 */
  getRecovery(since?: string): Promise<HaRecovery>;
  getInvariants(): Promise<InvariantCheck[]>;

  /* ===== 용량(ADR 0015 수동 스케일링) =====
   *
   * 판정은 자동이고 실행은 사람이다. getCapacity가 계약과 관측을 함께 주고 권고까지 붙인다.
   * scaleOut·scaleIn은 희망 대수만 바꾼다. 노드를 직접 띄우거나 내리지 않으므로 호출 직후에는
   * 계약과 관측이 어긋난 창이 실재하고, 화면은 그 창을 감추지 않는다. */
  getCapacity(): Promise<CapacityState>;
  scaleOut(reason?: string): Promise<CapacityState>;
  scaleIn(reason?: string): Promise<CapacityState>;

  /* ===== 봇 플릿(§2-1) ===== */
  /** start·stop·pause가 플릿 상태에 즉시 반영되는지. sim·http(v2.22 실반영) 모두 true다.
   * 반영 불가 케이스(플릿 빈 부재)는 어댑터가 응답 applied:false를 에러로 승격해 전달한다.
   * false를 반환하는 어댑터는 화면이 기록 사실만 알리는 문구로 강등한다 */
  readonly controlApplied: boolean;
  getSimStatus(): Promise<SimStatus>;
  simStart(): Promise<void>;
  simStop(): Promise<void>;
  simPause(): Promise<void>;
  simSetRate(rate: number): Promise<ControlApply>;
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
   * rate·scale·bias 세 경로로 도달 가능한 상태만 만들어 승인이 불요다(§6-A).
   * 값이 기준선 대비 배수라 같은 프리셋을 두 번 눌러도 결과가 같고, steady가 복귀 경로다.
   */
  activateScenario(name: ScenarioName): Promise<ScenarioResult>;

  /* ===== 마켓 설정(§2, config) ===== */
  getConfigMarkets(): Promise<ConfigMarket[]>;
  /**
   * 공지 목록 한 쪽. 철회분 포함이고 커서는 §2-6 opaque.
   * 공지는 DELETE 권한이 없어 철회만 되고 행이 계속 쌓이므로 고정 상한으로 자르지 않는다(V5 §7).
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

  /* 회원 운영(§6-E). 조회는 VIEWER, 전이는 MEMBER_MOD */
  /** 회원 목록 한 쪽. 가입 역순. 봇 계정은 제외된다 */
  getMembers(status?: MemberStatus, opts?: PageOpts): Promise<AdminPage<AdminMember>>;
  /** 회원 상세. 활동 요약으로 폐쇄 전 남은 주문을 본다 */
  getMember(externalId: string): Promise<AdminMemberDetail>;
  /** 정지. 세션만 끊고 미체결 주문은 남긴다(가역) */
  suspendMember(externalId: string): Promise<MemberTransition>;
  /** 복구. 상태만 되돌린다. 세션은 복구하지 않는다 */
  reinstateMember(externalId: string): Promise<MemberTransition>;
  /* 폐쇄는 승인 필수라 별도 메서드가 없다. RequestButton -> 발급 -> consumeApproval 경로다 */

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

  /* ===== MSP AI 관제센터(관리자 설계 4-4) =====
   * sim은 브라우저 시뮬레이터, http는 관리 API의 관제센터 서버(API 설계 §6-F, /admin/v1/msp/**)다. http는 한 판
   * 조회에 세부 조회 몇 건을 붙여 판을 만들고(httpOps.ts), 서버에 없는 칸은 원천 연결 전으로 둔다 */
  getControlCenter(): Promise<ControlCenter>;
  /**
   * 판단 한 건의 과정(근거 스냅숏, 후보, 정책 엔진 검사). 판이 판단 목록만 싣는 원천(http)에서 상세를 열 때 읽는다.
   * sim은 판에 과정을 늘 싣으므로 판의 기록을 돌려준다. 없으면 null이다
   */
  getAiJudgment(id: string): Promise<AiJudgment | null>;
  /** 사건 한 건의 기록 줄. 판에 기록이 없는 사건(http의 오래된 사건)을 열 때 읽는다. sim은 null(판에 다 있음)이다 */
  getIncidentEvents(incidentId: string): Promise<IncidentEvent[] | null>;
  /** 화면이 고른 추세 범위를 알린다. http는 그 범위의 추세를 세부 조회로 읽고, sim은 할 일이 없다 */
  wantTrends(range: TrendRange): void;
  /** 경보 확인. 확인 기록이고 경보를 끄지 않는다 */
  ackAlert(alertId: string): Promise<void>;
  /** 경보 보류. 최대 30분, 장애 등급 불가, 같은 경보 연속 불가(ADR 0035) */
  silenceAlert(alertId: string, minutes: number): Promise<void>;
  /** AI 조치안 결정. 승인은 실행이고, 임계값 조치안은 1인 승인 티켓(ops.threshold.set)을 발급·소각한다 */
  decideAiProposal(proposalId: string, decision: AiDecision): Promise<void>;
  /** 자동 실행된 조치를 되돌리기 창 안에서 되돌린다 */
  revertAiAction(proposalId: string): Promise<void>;
  /** 자동 모드 켜기. 사유와 유효 시간을 받아 1인 승인 티켓(ops.ai.auto)을 발급·소각한다 */
  enableAiAuto(hours: number, reason: string): Promise<void>;
  /** 자동에서 수동으로. 승인 없이 세션만 있으면 된다 */
  stopAiAuto(): Promise<void>;
  /** AI 즉시 정지. 두 모드 어디서든 승인 없이 세션만 있으면 된다 */
  stopAi(): Promise<void>;
  /** AI 재개. 사유를 받아 수동으로만 돌린다. 승인 티켓은 없고 SUPER가 한다 */
  resumeAi(reason: string): Promise<void>;
  /** Databasus 백업 즉시 실행 */
  runBackupNow(): Promise<void>;
  /** 시나리오 재생이 있는 원천인가. sim만 true다 */
  readonly opsScenarios: boolean;
  startOpsScenario(id: OpsScenarioId): Promise<void>;
  /** 관제 시뮬레이션을 처음 상태로 되돌린다 */
  resetOps(): Promise<void>;

  /* ===== AI 상담(관리자 설계 4-6) =====
   * http는 POST /admin/v1/msp/ai/chat이다. 서버가 질문 시점의 근거를 새로 모아 모델에 묻고, 상태를 바꾸는 호출이 없다 */
  /** 상담의 원천. sim은 관제 판을 읽는 규칙 답, LIVE는 서버 상담 경로, NOT_CONNECTED는 서버가 없어 보내지 않음이다 */
  readonly aiChat: "SIM" | "LIVE" | "NOT_CONNECTED";
  /** 관제 상태를 묻는다. 조회만 하고 조치를 실행하지 않는다. recent는 최근 대화(CHAT_RECENT_TURNS개까지)다 */
  askAi(question: string, recent: AiChatTurn[]): Promise<AiChatReply>;

  /* ===== 운영 보고(관리자 설계 4-7) =====
   * bot-1 보고 서비스의 보고와 Teams 전송 기록을 읽는다. http는 보고 서비스 읽기 프록시(GET /admin/v1/msp/reports,
   * .../reports/status)이고, 프록시가 목록 요약만 주므로 보고 본문은 원천 연결 전이다 */
  getReports(): Promise<OpsSection<ReportDesk>>;
  /** Teams 연결 시험. 운영 자료를 싣지 않는 모의 메시지 한 건을 밖으로 보낸다 */
  sendTeamsTest(): Promise<void>;
  /** 담당자와 연락 체계. sim은 예시, http는 운영자·당번 명부 연결 전이다 */
  getOnCall(): Promise<OpsSection<OnCallEntry[]>>;
}

/** 화면이 소비할 때 세션이 필요함을 알린다(요청 요구 권한과 별개) */
export function requireSession(api: AdminApi): AdminSession {
  const s = api.getSession();
  if (!s) throw new AdminError("jwt_verification", "관리자 세션 없음. mTLS 인증서로 접속 필요");
  return s;
}

let singleton: AdminApi | null = null;

/**
 * 관리 평면 어댑터 판정. 고객 평면과 따로 정한다.
 *
 * 왜 따로인가. 관리 평면은 고객 평면과 같은 프로세스가 아니다. 별도 배포단위이고 포트도
 * 8443이며 mTLS 클라이언트 인증서를 강제한다(관리자 설계 §0, §4-1). 즉 고객 백엔드가 떠
 * 있다는 사실이 관리 백엔드도 떠 있다는 뜻이 아니다. 그런데 종전에는 스위치가 하나뿐이라
 * 고객 평면을 실서버에 붙이는 순간 관리 화면도 8443을 찾아갔고, 그 쪽을 안 띄웠으면
 * 로그인 화면에서 더 나아갈 수 없었다. 두 평면이 물리 분리라는 사실이 설정에도 있어야 한다.
 *
 * 값을 안 주면 고객 평면 설정을 따른다. 종전 동작이 기본값이라 배포 빌드는 바뀌지 않는다.
 * NEXT_PUBLIC_*는 빌드 타임 인라인이라 두 이름 모두 리터럴로 적어야 치환된다.
 */
function adminMode(): string {
  return process.env.NEXT_PUBLIC_ADMIN_DATASOURCE || process.env.NEXT_PUBLIC_DATASOURCE || "sim";
}

/** 관리 화면이 실서버(mTLS 평면)를 보고 있는가. 로그인 화면의 인증서 열거 분기와 공유한다 */
export function isAdminHttp(): boolean {
  return adminMode() === "http";
}

function createAdminApi(): AdminApi {
  if (isAdminHttp()) {
    return new HttpAdminApi(process.env.NEXT_PUBLIC_ADMIN_BASE ?? "");
  }
  return new SimAdminApi();
}

export function getAdminApi(): AdminApi {
  if (!singleton) singleton = createAdminApi();
  return singleton;
}

export type { AdminRole };
