/**
 * 재시도는 동일 키 재사용, 내용 변경만 새 키다(API §2-4, §7-6).
 * 제출 실패는 상태 UNKNOWN이라 키를 보존하고, 발급 시점은 arm이 아니라 제출 확정이다.
 */

export interface OrderKeyState {
  readonly key: string;
  /** 다음 제출이 새 주문이라 새 키가 필요한지. false면 진행 중 주문과 동일 키 재사용 */
  readonly dirty: boolean;
}

export type KeyGen = () => string;

export function initOrderKey(gen: KeyGen): OrderKeyState {
  return { key: gen(), dirty: true };
}

/** 입력·방향·유형 변경. 확정 대상이 달라졌으므로 다음 제출은 새 키 */
export function onInputChanged(state: OrderKeyState): OrderKeyState {
  return state.dirty ? state : { key: state.key, dirty: true };
}

/** 제출 직전 키 확정. dirty면 새 키를 발급하고, 아니면 기존 키를 재사용(동일 내용 재시도) */
export function resolveKeyForSubmit(state: OrderKeyState, gen: KeyGen): OrderKeyState {
  return state.dirty ? { key: gen(), dirty: false } : state;
}

/** 제출 확정 성공. 다음 주문은 새 키 */
export function onSubmitAccepted(state: OrderKeyState): OrderKeyState {
  return state.dirty ? state : { key: state.key, dirty: true };
}

/**
 * 제출 실패. 키·dirty를 모두 보존해 동일 키 재전송 경로를 유지한다.
 * 상태 전이가 없다는 것이 계약이다. 항등을 함수로 고정해, 회귀로 실패 경로에서 키가
 * 바뀌는 변경이 들어오면 테스트가 잡게 한다.
 */
export function onSubmitFailed(state: OrderKeyState): OrderKeyState {
  return state;
}

/**
 * 제출 중에는 보류하되 서명을 갱신하지 않아, 제출 종료 뒤 그 변경이 정확히 한 번 반영된다.
 * 서명이 같으면 반영하지 않는다. 입력이 그대로인 5XX 재시도가 새 키를 얻으면 이중접수다.
 */
export function shouldApplyInputChange(sig: string, lastSig: string | null, submitting: boolean): boolean {
  if (submitting) return false;
  return sig !== lastSig;
}

/** 같은 키가 다른 내용으로 이미 소모됐다는 확정 응답(§2-4). 재시도해도 영구 409다 */
const DUPLICATE_KEY_CODE = "duplicate_client_order_id";

/**
 * 4xx 거부는 게이트웨이 선차단이거나 저널 REJECTED 박제라 다음 제출은 새 키가 안전하다(§2-4).
 * 키를 보존하는 예외는 상태 UNKNOWN(5xx·타임아웃·네트워크 0), 409 order_in_progress·requery_deferred, 429뿐이다(§7-6).
 * 코드 미상 409는 보존 쪽으로 판단한다. 미확정 주문에 새 키를 발급하면 이중접수가 된다.
 */
export function isFinalRejectStatus(status: number, code?: string): boolean {
  if (status < 400 || status >= 500) return false;
  if (status === 429) return false;
  if (status === 409) return code === DUPLICATE_KEY_CODE;
  return true;
}
