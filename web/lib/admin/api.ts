/**
 * lib/admin/api.ts. 관리 평면 데이터 접근 추상화.
 * 고객 평면 DataSource(lib/api/datasource.ts)와 분리된 인터페이스·싱글턴이다.
 * 화면은 이 인터페이스만 소비하고 시뮬레이터나 fetch를 직접 부르지 않는다.
 * 구현: SimAdminApi(데모, 기본)와 HttpAdminApi(Spring /admin/v1/* 어댑터).
 */

import { SimAdminApi } from "./simAdmin";
import { HttpAdminApi } from "./httpAdmin";
import type {
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
  SimStatus,
  TradeFlash,
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
  listApprovals(state?: ApprovalState): Promise<ApprovalRequest[]>;
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
  /** start·stop·pause가 플릿 상태에 즉시 반영되는지. http 서버는 해당 명령을 감사 적재만
   * 하고 governor를 건드리지 않아 false다. 화면은 이 값으로 성공 토스트 문구를 가른다
   * (반영을 단정하는 문구가 운영자 오인을 만든다) */
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

  /* ===== 카오스(§2-2) ===== */
  getChaosCatalog(): ChaosExperimentDef[];
  getChaosRuns(): Promise<ChaosRun[]>;

  /* ===== 마켓 설정(§2, config) ===== */
  getConfigMarkets(): Promise<ConfigMarket[]>;
  getNotices(): Promise<Notice[]>;
  postNotice(title: string, body: string, severity?: NoticeSeverity): Promise<Notice>;
  updateNotice(id: string, patch: NoticePatch): Promise<Notice>;
  /** 소프트 삭제(active=false). notices DELETE 권한이 없어 하드 삭제하지 않는다(§6-D) */
  deleteNotice(id: string): Promise<void>;

  /* ===== 운영자·감사(§3-1) ===== */
  getOperators(): Promise<AdminOperator[]>;
  getAudit(): Promise<AuditEntry[]>;
}

/** 화면이 소비할 때 세션이 필요함을 알린다(요청 요구 권한과 별개) */
export function requireSession(api: AdminApi): AdminSession {
  const s = api.getSession();
  if (!s) throw new AdminError("jwt_verification", "관리자 세션이 없습니다. mTLS 인증서로 접속하세요");
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
