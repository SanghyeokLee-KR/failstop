/**
 * 회원 폐쇄의 미완료 알림(API 설계 v2.55 §6-E, ADR 0034 S3·S4). 폐쇄는 상태 전이·세션 폐기·취소를 먼저 커밋한 뒤, 취소하지 못한
 * 주문이 남으면 409 orders_cancel_incomplete 로 알린다. 폐쇄는 이미 확정이고 승인 토큰도 소각됐으므로 화면은 이것을 실패가
 * 아니라 경고로 보이고, 남은 수와 사유별 수, 다음 조치를 안내한다. 가짜 어댑터와 HTTP 어댑터 화면이 같은 문구를 쓰도록 순수
 * 함수로 둔다(web/tests/memberAdmin.test.ts 가 고정한다).
 *
 * 정지 마켓 미체결을 미완료로 세는 것은 2026-09-24 작업을 맡은 AI 가 고른 방침이고 사용자 확인이 필요하다(ADR 0034 결정 15).
 * 파티션 인계의 세 사유(partition_not_started, partition_pending, server_error)도 미완료다(ADR 0034 S5, 사용자 확인 대상).
 */

export const CLOSE_INCOMPLETE = "orders_cancel_incomplete";

/** 사유 코드 -> 화면 이름. 서버 detail.pending_reasons 의 키다 */
const REASON_LABEL: Record<string, string> = {
  service_unavailable: "일시 거부",
  // 마켓 접수 경합으로 저널 전에 끝난 취소(API 설계 §2-5-b). 곧바로 다시 요청하면 풀린다
  too_many_requests: "접수 혼잡",
  market_suspended: "정지 마켓",
  // 파티션 인계(API 설계 v2.57 §4-B-3·§6-E). 소유 노드가 없을 때와 다른 인계를 처리하느라 바쁠 때 모두 나므로 원인을
  // 단정하지 않는다. 이 요청은 그 주문에 아무것도 하지 않았다
  partition_not_started: "소유 노드 미착수",
  // 소유 노드가 시작했으나 기다림 안에 끝내지 않은 몫. 폐쇄 응답 뒤에 취소될 수 있다
  partition_pending: "소유 노드 처리 중",
  // 소유 노드의 예기치 않은 오류로 끝난 주문
  server_error: "서버 오류",
};

/** 사유 표시 순서. 서버 detail 과 같은 순서로 둔다(MemberAdminService) */
const REASON_ORDER = [
  "service_unavailable",
  "too_many_requests",
  "market_suspended",
  "partition_not_started",
  "partition_pending",
  "server_error",
];

function count(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.trunc(value) : 0;
}

/** 에러 detail 의 사유별 수. 0 이거나 모르는 값은 뺀다 */
export function pendingReasons(detail: Record<string, unknown> | null | undefined): Record<string, number> {
  const raw = detail?.pending_reasons;
  const out: Record<string, number> = {};
  if (raw && typeof raw === "object") {
    for (const [reason, n] of Object.entries(raw as Record<string, unknown>)) {
      const c = count(n);
      if (c > 0) out[reason] = c;
    }
  }
  return out;
}

/**
 * 경고 문구. 예: "폐쇄 확정, 미완료 3건(일시 거부 1건, 정지 마켓 2건). 정지 마켓을 재개한 뒤 새 승인으로 폐쇄 재요청 필요".
 * 사유별 수가 없으면 합만 보인다. 정지 마켓 몫이 있으면 재개가 먼저라는 안내를 붙인다. 소유 노드가 처리 중인 몫
 * (partition_pending)이 있으면 곧 취소될 수 있으므로 잠시 뒤 재요청하라는 안내를, 둘 다 없으면 곧바로 재요청하라는 안내를
 * 붙인다.
 */
export function describeCloseIncomplete(detail: Record<string, unknown> | null | undefined): string {
  const reasons = pendingReasons(detail);
  const summed = Object.values(reasons).reduce((a, b) => a + b, 0);
  const pending = count(detail?.pending_orders) || summed;
  const known = REASON_ORDER.filter((r) => reasons[r]);
  const other = Object.keys(reasons).filter((r) => !REASON_ORDER.includes(r));
  const parts = [...known, ...other].map((r) => `${REASON_LABEL[r] ?? r} ${reasons[r]}건`);
  const head = `폐쇄 확정, 미완료 ${pending}건${parts.length > 0 ? `(${parts.join(", ")})` : ""}`;
  const next = reasons.market_suspended
    ? "정지 마켓을 재개한 뒤 새 승인으로 폐쇄 재요청 필요"
    : reasons.partition_pending
      ? "잠시 뒤 새 승인으로 폐쇄 재요청 필요"
      : "새 승인으로 폐쇄 재요청 필요";
  return `${head}. ${next}`;
}
