/**
 * 거래 일시 중단 표시와 결과를 모르는 주문의 처리 규칙(API 설계 §2-5-d·§4-A-7·§4-F, Patroni 설계 6-2).
 * 판정을 순수 함수로 두는 이유는 notices.ts 와 같다. DOM 없이 검증하고, 틀리면 화면을 봐야만 드러난다.
 */

import type { PublicNotice, TradingStatus, TradingSuspendReason } from "./types";

/** 탐지를 끈 노드와 같은 모양. 상태를 아직 못 읽은 화면은 이것으로 막지 않는다 */
export const ACTIVE_TRADING: TradingStatus = {
  trading_suspended: false,
  trading_state: "ACTIVE",
  reason: null,
  since: null,
  checked_at: 0,
  poll_after_seconds: 5,
};

const REASONS: readonly TradingSuspendReason[] = ["replication_unavailable", "database_unavailable"];

/**
 * 응답 모양을 확인한다. 모양이 틀리면 null 이고 호출자는 직전 상태를 유지한다(§4-A-7 클라이언트 규약).
 * 모르는 reason 은 중단 사실을 버리지 않고 이유만 비운다. 중단을 ACTIVE 로 오독하면 버튼이 열린다.
 */
export function coerceTradingStatus(raw: unknown): TradingStatus | null {
  if (typeof raw !== "object" || raw === null) return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.trading_suspended !== "boolean") return null;
  const suspended = r.trading_suspended;
  const reason = REASONS.includes(r.reason as TradingSuspendReason) ? (r.reason as TradingSuspendReason) : null;
  const poll = typeof r.poll_after_seconds === "number" && Number.isFinite(r.poll_after_seconds)
    ? r.poll_after_seconds : ACTIVE_TRADING.poll_after_seconds;
  return {
    trading_suspended: suspended,
    trading_state: suspended ? "SUSPENDED" : "ACTIVE",
    reason: suspended ? reason : null,
    since: suspended && typeof r.since === "number" ? r.since : null,
    checked_at: typeof r.checked_at === "number" ? r.checked_at : 0,
    poll_after_seconds: poll,
  };
}

export function isSuspended(status: TradingStatus | null): boolean {
  return status?.trading_suspended === true;
}

/* 서버 권장 간격을 유계화한다. 잘못된 값 하나가 폴링을 멈추거나 폭주시키면 안 된다 */
const POLL_MIN_MS = 1_000;
const POLL_MAX_MS = 30_000;

export function pollIntervalMs(status: TradingStatus | null): number {
  const sec = status?.poll_after_seconds ?? ACTIVE_TRADING.poll_after_seconds;
  return Math.min(POLL_MAX_MS, Math.max(POLL_MIN_MS, Math.round(sec * 1000)));
}

/**
 * 배너 자리는 화면당 하나다. 연결 끊김, 거래 일시 중단, 공지 순이다(§4-F). 연결이 끊기면 어떤 조작도 성립하지
 * 않고, 중단은 공지보다 먼저 알아야 다음 주문을 헛되이 내지 않는다.
 */
export type BannerKind = "connection" | "suspended" | "notice";

export function bannerKind(connected: boolean, status: TradingStatus | null,
                           notice: PublicNotice | null): BannerKind | null {
  if (!connected) return "connection";
  if (isSuspended(status)) return "suspended";
  return notice ? "notice" : null;
}

export interface SuspensionCopy {
  title: string;
  body: string;
  /** 보조기술 낭독문. 보이는 문구와 같은 사실을 문장으로 준다 */
  sr: string;
}

/* 보이는 문구는 개조식 명사 종결이다(디자인 시스템 §10-1). 낭독문도 토큰 게이트의 어미 규칙을 따른다 */
export function suspensionCopy(reason: TradingSuspendReason | null): SuspensionCopy {
  if (reason === "database_unavailable") {
    return {
      title: "거래 일시 중단",
      body: "DB 전환 진행 중. 전환이 끝나면 자동 재개",
      sr: "거래 일시 중단. 데이터베이스 전환이 진행 중이고 전환이 끝나면 자동으로 재개",
    };
  }
  return {
    title: "거래 일시 중단",
    body: "DB 복제 서버 복구 대기. 시세·잔고 조회는 계속 가능. 복구되면 자동 재개",
    sr: "거래 일시 중단. 데이터베이스 복제 서버가 복구될 때까지 주문과 취소를 받지 않음. "
      + "조회는 계속 가능하고 복구되면 자동으로 재개",
  };
}

export const ORDER_BLOCKED_LABEL = "거래 일시 중단 · 주문·취소 불가";
export const ORDER_BLOCKED_SR = "거래가 일시 중단되어 주문 불가";
export const PENDING_RESULT_LABEL = "주문 결과 확인 중";
export const PENDING_RESULT_SR = "직전 주문의 결과 확인 중. 같은 주문을 새로 내지 말 것";
export const RESUMED_TOAST = "거래 재개";
export const SAVE_BLOCKED_LABEL = "일시적으로 저장 불가. 재개 후 다시 시도 필요";
export const SAVE_BLOCKED_SR = "거래 일시 중단으로 지금은 저장 불가";

/** "중단 시각 14:23:01". 이 노드가 중단을 판정한 시각을 브라우저 시각으로 보인다 */
export function sinceLabel(sinceMs: number | null): string | null {
  if (sinceMs == null || !Number.isFinite(sinceMs)) return null;
  const d = new Date(sinceMs);
  const p = (n: number) => String(n).padStart(2, "0");
  return `중단 시각 ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/* ===== 결과를 모르는 주문(§6-2, API 설계 §2-5-d) ===== */

/**
 * suspended 는 423 을 받은 키다. 423 은 '이 시도는 쓰지 않음'일 뿐이고 인그레스가 다시 보낸 시도의 답일 수 있어,
 * 앞선 시도가 재개 뒤 커밋될 수 있다. 그래서 재개를 확인한 뒤에 같은 키로 재조회한다.
 * unknown 은 409 order_in_progress·5xx·시간 초과다. 결과를 모르므로 곧바로 재조회를 이어간다.
 */
export type PendingCause = "suspended" | "unknown";

export interface PendingOrder {
  clientOrderId: string;
  market: string;
  cause: PendingCause;
  since: number;
}

/**
 * 주문 제출 실패가 결과를 모르는 주문을 남기는가. 429 는 확정 미접수(§2-5-b)라 남기지 않는다.
 * 409 가운데 duplicate_client_order_id 는 다른 내용에 이미 쓴 키라는 확정이다.
 */
export function pendingCauseOf(status: number, code?: string): PendingCause | null {
  if (status === 423) return "suspended";
  if (status === 0 || status >= 500) return "unknown";
  if (status === 409 && code !== "duplicate_client_order_id") return "unknown";
  return null;
}

/** 같은 키는 하나만 둔다. 원인이 바뀌면 나중 것을 쓴다 */
export function trackPending(list: readonly PendingOrder[], next: PendingOrder): PendingOrder[] {
  return [...list.filter((p) => p.clientOrderId !== next.clientOrderId), next];
}

/** 이번 주기에 재조회할 키. 423 원인은 상태를 읽었고 중단이 풀렸을 때만이다 */
export function dueForRequery(list: readonly PendingOrder[], status: TradingStatus | null): PendingOrder[] {
  const resumed = status !== null && !status.trading_suspended;
  return list.filter((p) => p.cause === "unknown" || resumed);
}

/**
 * by-client 재조회의 판정(§4-B-4 (a)). 200 은 접수됨(재전송 금지), 404 는 미접수 확정이라 그때부터 새 키가 안전하다.
 * 409·429·5xx·네트워크 실패는 판정이 아니므로 다시 본다. 중단 중과 재개 직후의 409 가 여기에 든다.
 */
export type RequeryVerdict = "accepted" | "not_accepted" | "retry";

export function requeryVerdict(status: number): RequeryVerdict {
  if (status === 200) return "accepted";
  if (status === 404) return "not_accepted";
  return "retry";
}
