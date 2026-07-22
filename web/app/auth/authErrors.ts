/**
 * app/auth/authErrors.ts. 회원 인증 에러코드 to 화면 매핑(§3-4).
 * ApiError·SimApiError 모두 name에 코드를 노출하므로 name으로 분기한다.
 * field가 있으면 해당 입력에 인라인 표시, null이면 폼 상단 토스트·배너로 표시한다.
 */

import { ApiError } from "@/lib/api/httpDatasource";

export type AuthField = "email" | "password" | "display_name" | null;

export interface MappedAuthError {
  field: AuthField;
  message: string;
}

function codeOf(e: unknown): string {
  if (e instanceof ApiError) return e.code;
  if (e instanceof Error) return e.name;
  return "unknown";
}

function retryAfterSec(e: unknown): number | null {
  return e instanceof ApiError ? e.retryAfter : null;
}

export function mapAuthError(e: unknown): MappedAuthError {
  const code = codeOf(e);
  switch (code) {
    case "weak_password":
      return { field: "password", message: "비밀번호는 8자 이상이어야 해요" };
    case "invalid_email":
      return { field: "email", message: "이메일 형식을 확인해 주세요" };
    case "email_taken":
      return { field: "email", message: "이미 가입된 이메일이에요" };
    case "invalid_credentials":
      return { field: null, message: "이메일 또는 비밀번호를 확인해 주세요" };
    case "account_locked": {
      const sec = retryAfterSec(e);
      const when = sec && sec > 0 ? `${sec}초 후` : "잠시 후";
      return { field: null, message: `로그인 시도가 많아 계정이 잠겼어요. ${when} 다시 시도해 주세요` };
    }
    case "account_suspended":
      return { field: null, message: "정지된 계정이에요. 로그인할 수 없어요" };
    case "session_expired":
    case "refresh_reuse":
      return { field: null, message: "세션이 만료됐어요. 다시 로그인해 주세요" };
    case "too_many_requests":
    case "auth_throttled":
      return { field: null, message: "요청이 많아요. 잠시 후 다시 시도해 주세요" };
    default:
      return { field: null, message: "요청에 실패했어요. 잠시 후 다시 시도해 주세요" };
  }
}
