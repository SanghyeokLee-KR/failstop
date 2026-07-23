/**
 * 주문 멱등키(client_order_id) 수명주기 (API §2-4, §7-6).
 * 계약: 재시도는 동일 키 재사용, 내용 변경만 새 키, 확정 성공 후 다음 주문은 새 키.
 * 제출 실패(5XX·네트워크·타임아웃)는 키를 보존한다. 상태가 UNKNOWN이므로 동일 키
 * 재전송(또는 by-client 재조회)이 유일한 안전 경로이고, 새 키 발급은 이중접수를 부르는 우회로다.
 * 발급 시점을 arm(1차 확인)이 아니라 제출 확정으로 두어, 실패 후 재확인이 새 키를
 * 발급하던 결함을 차단한다.
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
 * 종결성 거부 판정(API §2-4). 4xx 거부는 게이트웨이 선차단(키 미소모)이거나 저널 REJECTED
 * 박제(키 영구 소모)다. 어느 쪽이든 같은 키 재시도는 무의미하거나 멱등 replay 거짓 성공을
 * 만들므로 다음 제출은 새 키가 안전하다. 키를 보존해야 하는 예외는 상태 UNKNOWN(5xx·
 * 타임아웃·네트워크 0)과 진행 중(409 order_in_progress)·레이트리밋(429)뿐이다(§7-6).
 */
export function isFinalRejectStatus(status: number): boolean {
  return status >= 400 && status < 500 && status !== 409 && status !== 429;
}
