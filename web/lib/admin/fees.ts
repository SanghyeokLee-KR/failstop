/**
 * 서버 ConfigAdminController.parseFee를 미러한다. 화면 사전 검증과 sim 적용 검증이 같은 규칙을
 * 공유해야 데이터소스별로 결과가 갈리지 않는다. 값은 문자열로만 다룬다(float 금지).
 */

import { compareDecimal, decimalsOf } from "../decimal";

export const FEE_SCALE = 8;
export const FEE_MAX = "0.1";

// 음수·부호·지수 표기는 형식 단계에서 차단한다(서버 BigDecimal 파싱보다 좁지만 안전 방향)
const FEE_RE = /^\d+(\.\d+)?$/;

export type FeeField = "maker_fee" | "taker_fee";

/** 위반 시 서버와 같은 문구를 돌려준다. 통과·빈 값이면 null(빈 값은 부분 갱신 허용) */
export function feeRateError(raw: string, field: FeeField): string | null {
  const v = raw.trim();
  if (v === "") return null;
  if (!FEE_RE.test(v)) return `${field} 형식이 올바르지 않습니다.`;
  if (decimalsOf(v) > FEE_SCALE) return `${field}는 소수 ${FEE_SCALE}자리 이하여야 합니다.`;
  if (compareDecimal(v, FEE_MAX) > 0) return `${field}는 0 이상 ${FEE_MAX} 이하여야 합니다.`;
  return null;
}
