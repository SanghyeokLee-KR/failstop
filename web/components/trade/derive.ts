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

export interface BalanceDecision {
  /** 잔고 영역 표시 종류 */
  view: BalanceView;
  /** 값 옆에 지연 배지를 붙이는가. stale 에서만 참이다 */
  stale: boolean;
  /** 주문 CTA 를 허용하는가 */
  ready: boolean;
  /** 안내 문구 종류. none 이면 행을 만들지 않는다 */
  notice: "none" | "backpressure" | "error";
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
 * stale 에서 CTA 를 막는 이유. 값이 오래됐는데 주문을 허용하면 사용자는 낡은 잔고로
 * 판단하고 서버는 insufficient_balance 로 거부한다. 429 한 번을 흡수한 대가로 주문 거부
 * 한 번을 만드는 교환이고, 데이터 무결성 축에서는 후자가 더 나쁘다. 값과 배지는 유지하되
 * 확정 행위만 막는다. 이것이 "화면을 비우지 않는다"와 "낡은 값으로 확정하지 않는다"를
 * 동시에 지키는 유일한 조합이다.
 *
 * failed 와 stale 을 가르는 것은 값의 유무이지 오류의 종류가 아니다. 429든 5xx든 값이
 * 있으면 stale 이고 없으면 failed 다. 오류 종류는 문구(notice)만 가른다.
 */
export function decideBalance(input: {
  state: "idle" | "ok" | "stale" | "failed";
  hasTicker: boolean;
  retryAfterSec: number | null;
}): BalanceDecision {
  const backpressure = input.retryAfterSec !== null;
  if (input.state === "failed") {
    // 값이 없다. 재시도 자리를 내주고 CTA 는 막는다
    return {
      view: "retry", stale: false, ready: false, canRetry: true,
      notice: backpressure ? "backpressure" : "error",
    };
  }
  if (input.state === "stale") {
    // 값은 있다. 지우지 않고 나이를 표시한다. 확정만 막고 다시 읽을 수단은 준다
    return {
      view: "value", stale: true, ready: false, canRetry: true,
      notice: backpressure ? "backpressure" : "error",
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
