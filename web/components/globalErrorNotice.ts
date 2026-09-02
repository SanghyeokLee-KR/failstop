/**
 * 전역 실패 통지 판정. GlobalErrorReporter가 소비한다.
 *
 * 순수 함수만 두어 테스트가 DOM 없이 계약을 고정한다(orderKey·fillNotice와 같은 배치).
 */

/** 동일 메시지 재통지 억제 창. 재시도 루프가 토스트를 도배하는 것을 막는다 */
export const DEDUPE_MS = 5000;

const FALLBACK_MESSAGE = "요청을 처리하지 못함. 잠시 후 다시 시도 필요";

/**
 * 전역 오류 통지 대상인가. 백프레셔는 대상이 아니다.
 *
 * 429는 서버가 설계대로 수용 상한을 건 결과이고 회복 시각까지 함께 준다. 정본이
 * "조회 429를 종결 실패로 적지 않는다"로 못박는다(API 설계 2-3). 그런데 이 판정이
 * 없으면 자체 catch 를 빠져나간 429 거부가 그대로 붉은 오류 토스트가 되어,
 * 설계된 백프레셔가 사용자에게 고장으로 보인다. 그것이 이 저장소가 없애려는 오역이다.
 *
 * 지연을 알리는 것 자체는 화면 몫이다. 잔고는 배지로, 차트는 지연 상태로 각자 알린다.
 * 전역 통지는 "어디서 났는지 모르는 실패" 전용이고 429는 언제나 발생 지점을 안다.
 *
 * 판정은 오리 타이핑으로 한다. ApiError 를 직접 참조하면 이 모듈이 어댑터에 묶이고,
 * 순수 함수만 둔다는 배치 근거가 무너진다.
 */
export function isBackpressure(reason: unknown): boolean {
  return typeof reason === "object" && reason !== null && (reason as { status?: unknown }).status === 429;
}

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
