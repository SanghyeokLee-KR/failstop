/**
 * 거래 화면 파생 계산 (§2.5).
 * orderbook·trade 메시지에는 전일종가가 없어 ticker에서 역산해 조인한다.
 * 역산·등락률은 문자열 십진 연산으로 처리한다. float 왕복은 근접 가격의 방향·비율 오차를 만든다.
 */

import type { ChangeState, TickerMessage } from "../../lib/types";
import { toNum } from "../../lib/format";
import {
  absDecimal,
  addDecimal,
  compareDecimal,
  decimalsOf,
  fromScaledInt,
  normalizeDecimal,
  scaledIntTrunc,
  subDecimal,
} from "../../lib/decimal";

// 등락률 소수부 자리수. 백분율 2자리 표시에 충분한 여유
const RATE_SCALE = 8;
// |a| / b 를 고정 자리수 비율 문자열로. b<=0이면 "0"(0 나눗셈 보호)
function ratioStr(a: string, b: string): string {
  const da = decimalsOf(a);
  const db = decimalsOf(b);
  let n = scaledIntTrunc(a, da);
  if (n < 0n) n = -n;
  const d = scaledIntTrunc(b, db);
  if (d <= 0n) return "0";
  const scaled = (n * 10n ** BigInt(db + RATE_SCALE)) / (d * 10n ** BigInt(da));
  return fromScaledInt(scaled, RATE_SCALE);
}

/** 전일종가 = 현재가 ∓ 등락액 (RISE는 차감, FALL은 가산). 문자열 십진으로 정밀 보존 */
export function prevCloseOf(ticker: TickerMessage | undefined): string {
  // 첫 체결 전 마켓은 trade_price가 null이다(§4-A-4). 기준가가 없으므로 역산 없이 0으로 조기 반환
  if (!ticker || ticker.trade_price == null) return "0";
  const cur = normalizeDecimal(ticker.trade_price);
  const chg = normalizeDecimal(ticker.change_price);
  if (ticker.change === "RISE") return subDecimal(cur, chg);
  if (ticker.change === "FALL") return addDecimal(cur, chg);
  return cur;
}

export interface DerivedRate {
  /** 절대값 등락률 문자열 (formatChangeRate 입력 규격) */
  rate: string;
  state: ChangeState;
}

/**
 * 호가 레벨 실재 여부. 얇은 호가에서 서버가 0으로 채운 빈 슬롯을 걸러낸다.
 * 가격 0을 기준으로 등락률을 역산하면 -100%가 나오는 오표시를 막는다.
 */
export function isPresentLevel(price: string, size: string): boolean {
  return toNum(price) > 0 && toNum(size) > 0;
}

/** 임의 가격의 전일종가 대비 등락률. float 왕복 없이 문자열 비율로 산출한다 */
export function rateVsPrevClose(price: string | number, prevClose: string): DerivedRate {
  if (compareDecimal(prevClose, "0") <= 0) return { rate: "0", state: "EVEN" };
  const p = normalizeDecimal(price);
  const cmp = compareDecimal(p, prevClose);
  if (cmp === 0) return { rate: "0", state: "EVEN" };
  const rate = ratioStr(absDecimal(subDecimal(p, prevClose)), prevClose);
  return { rate, state: cmp > 0 ? "RISE" : "FALL" };
}

/* ===== 잔고 신선도 판정 (§2-3 429 클라이언트 규약) ===== */

/** 주문 패널이 잔고 영역에 무엇을 그릴지. 순수 함수라 표로 고정한다 */
export type BalanceView = "loading" | "value" | "retry";

/**
 * 안내 문구 축. 화면이 오류 코드를 직접 분기하지 않도록 문구 선택까지 여기서 끝낸다.
 *
 * 앞부분(delayed·blocked)은 값의 유무가 가른다. 값이 있으면 지연이고 없으면 차단이다.
 * 뒷부분(_wait)은 서버가 회복 시각을 줬는지만 가른다. 대기 초를 말할 수 있는지를 정할 뿐
 * 종결 실패인지는 정하지 않는다(§2-3). 두 축을 하나로 접으면 값이 있고 확정도 열린 상태에
 * 종결 실패 문구가 붙어, 화면이 주문 불가라 말하면서 CTA 는 여는 자기모순이 된다.
 */
export type BalanceNotice = "none" | "delayed_wait" | "delayed" | "blocked_wait" | "blocked";

export interface BalanceDecision {
  /** 잔고 영역 표시 종류 */
  view: BalanceView;
  /** 값 옆에 지연 배지를 붙이는가. stale 에서만 참이다 */
  stale: boolean;
  /** 주문 CTA 를 허용하는가 */
  ready: boolean;
  /** 안내 문구 종류. none 이면 행을 만들지 않는다 */
  notice: BalanceNotice;
  /**
   * 사용자가 직접 다시 읽을 수단을 내주는가.
   *
   * stale 에도 내준다. 자동 재조회는 실패 한 건당 1회가 상한이라(§2-3 유계 조건) 그 한 번이
   * 실패하면 더 시도하지 않는다. 그때 stale 은 값이 있어 재시도 버튼 자리가 없으므로,
   * 수단을 안 주면 사용자가 낡은 값에 갇힌다. 재요청 판단을 사람에게 넘기는 것이 규약이고
   * 넘기려면 넘길 자리가 있어야 한다.
   */
  canRetry: boolean;
}

/**
 * 잔고 상태 판정. 화면이 오류 코드를 직접 분기하지 않게 한 곳으로 모은다.
 *
 * stale 에서 CTA 를 연다(§2-3 v2.37 정정). 종전 판은 여기서 막았고 그 근거는 낡은 잔고로
 * 주문하면 서버가 insufficient_balance 로 거부한다는 것이었다. 그 근거가 약하다.
 * 클라이언트 잔고 검사는 편의이지 안전장치가 아니고, 판정의 권위는 서버에 있으며 그 게이트는
 * 화면이 무엇을 하든 그대로 작동한다. 막아서 아끼는 것은 요청 한 건이고, 잃는 것은
 * 백프레셔가 걸린 구간에서 주문할 수단이다. 그 구간은 거래가 몰리는 때, 곧 사용자가 가장
 * 확정해야 하는 때다. 급락에 매도를 막는 화면은 429를 흡수한 것이 아니라 더 나쁜 것으로
 * 바꾼 것이다. 실물 거래소도 잔고 지연으로 주문 버튼을 잠그지 않는다.
 *
 * failed 는 계속 막는다. 값이 아예 없으면 잔고 표시가 0 으로 폴백되고 비율 버튼도 0 을
 * 기준으로 계산한다. 거짓 숫자를 보이고 그것으로 확정하게 두는 것과, 지연된 참값으로
 * 확정하게 두는 것은 다른 문제다. 이 경계가 유일한 차단 근거다.
 *
 * failed 와 stale 을 가르는 것은 값의 유무이지 오류의 종류가 아니다. 429든 5xx든 값이
 * 있으면 stale 이고 없으면 failed 다. 429 여부는 대기 초를 말할 수 있는지만 가른다.
 */
export function decideBalance(input: {
  state: "idle" | "ok" | "stale" | "failed";
  hasTicker: boolean;
  retryAfterSec: number | null;
}): BalanceDecision {
  // 회복 시각을 서버가 준 경우에만 대기 초를 말할 수 있다. 비429 실패는 그 근거가 없다
  const canNameWait = input.retryAfterSec !== null;
  if (input.state === "failed") {
    // 값이 없다. 재시도 자리를 내주고 CTA 는 막는다
    return {
      view: "retry", stale: false, ready: false, canRetry: true,
      notice: canNameWait ? "blocked_wait" : "blocked",
    };
  }
  if (input.state === "stale") {
    /* 값은 있다. 지우지 않고 나이를 표시하되 확정은 막지 않는다.
       시세는 여전히 필요하다. 없으면 총액·수수료 파생이 서지 않아 확정할 값이 없다 */
    return {
      view: "value", stale: true, ready: input.hasTicker, canRetry: true,
      notice: canNameWait ? "delayed_wait" : "delayed",
    };
  }
  if (input.state === "ok") {
    // 시세가 없으면 총액·수수료 파생이 서지 않아 아직 그릴 값이 없다
    return input.hasTicker
      ? { view: "value", stale: false, ready: true, canRetry: false, notice: "none" }
      : { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" };
  }
  // idle. 첫 조회 전이다
  return { view: "loading", stale: false, ready: false, canRetry: false, notice: "none" };
}
