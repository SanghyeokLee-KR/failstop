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
  Bot,
  BotType,
  ChaosExperimentDef,
  ChaosRun,
  ConfigMarket,
  DemoClientCert,
  DestructiveAction,
  ApprovalPayload,
  AdminOperator,
  AdminRole,
  AuditEntry,
  HaRecovery,
  HaStatus,
  InvariantCheck,
  Notice,
  SimStatus,
} from "./types";

/** 표준 관리 에러. name에 API §2-3 코드를 노출(jwt_verification·admin_forbidden 등) */
export class AdminError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = code;
    this.code = code;
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

  /* ===== 이중 승인(§1-4) ===== */
  requestApproval(action: DestructiveAction, payload: ApprovalPayload): Promise<ApprovalRequest>;
  approve(approvalId: string): Promise<ApprovalRequest>;
  reject(approvalId: string): Promise<ApprovalRequest>;
  /** 요청자 본인이 승인된 토큰을 소각하며 실효 액션 실행 */
  execute(approvalId: string): Promise<void>;
  listApprovals(): Promise<ApprovalRequest[]>;

  /* ===== 모니터링(§2-3, 읽기 전용) ===== */
  getOverview(): Promise<AdminOverview>;
  getHaStatus(): Promise<HaStatus>;
  getRecovery(): Promise<HaRecovery>;
  getInvariants(): Promise<InvariantCheck[]>;

  /* ===== 봇 플릿(§2-1) ===== */
  getSimStatus(): Promise<SimStatus>;
  simStart(): Promise<void>;
  simStop(): Promise<void>;
  simPause(): Promise<void>;
  simSetRate(rate: number): Promise<void>;
  createBot(type: BotType, market: string): Promise<Bot>;

  /* ===== 카오스(§2-2) ===== */
  getChaosCatalog(): ChaosExperimentDef[];
  getChaosRuns(): Promise<ChaosRun[]>;

  /* ===== 마켓 설정(§2, config) ===== */
  getConfigMarkets(): Promise<ConfigMarket[]>;
  getNotices(): Promise<Notice[]>;
  postNotice(title: string, body: string): Promise<Notice>;

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
