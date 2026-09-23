/**
 * 입출금 빠른 금액 버튼의 누적. 누른 금액을 입력칸의 원 단위 금액에 더한다.
 * 합이 그대로 요청 금액이 되므로 number를 거치지 않고 십진 문자열로 더한다. number 합은
 * 2^53을 넘으면 끝자리가 바뀌고 10^21부터는 지수 표기가 되어 숫자만 받는 금액 검증에 걸린다.
 */

import { addDecimal } from "../../lib/decimal";

export function addQuickAmount(prev: string, quick: string): string {
  return addDecimal(prev || "0", quick);
}
