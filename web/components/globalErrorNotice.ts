/**
 * 전역 실패 통지 판정. GlobalErrorReporter가 소비한다.
 *
 * 순수 함수만 두어 테스트가 DOM 없이 계약을 고정한다(orderKey·fillNotice와 같은 배치).
 */

/** 동일 메시지 재통지 억제 창. 재시도 루프가 토스트를 도배하는 것을 막는다 */
export const DEDUPE_MS = 5000;

const FALLBACK_MESSAGE = "요청을 처리하지 못함. 잠시 후 다시 시도 필요";

/** rejection 사유에서 사용자에게 보여줄 문장을 고른다. 비정형 사유는 기본 문구로 수렴시킨다 */
export function messageOf(reason: unknown): string {
  if (reason instanceof Error && reason.message.trim() !== "") return reason.message;
  if (typeof reason === "string" && reason.trim() !== "") return reason;
  return FALLBACK_MESSAGE;
}

/** 억제 판정. 같은 메시지가 창 안에 이미 통지됐으면 건너뛴다 */
export function shouldNotify(message: string, lastMessage: string | null, elapsedMs: number): boolean {
  if (message !== lastMessage) return true;
  return elapsedMs >= DEDUPE_MS;
}
