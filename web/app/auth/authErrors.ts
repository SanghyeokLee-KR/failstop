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
      // 서버는 8자 미만과 128자 초과를 같은 코드로 거절한다. 하한만 안내하면 상한 위반에 정반대 안내가 된다
      return { field: "password", message: "비밀번호는 8자 이상 128자 이하" };
    case "invalid_parameter":
      // 가입 폼의 도달 가능한 invalid_parameter는 표시명 상한(32자)뿐이다(다른 입력은 폼·별도 코드가 선차단)
      return { field: "display_name", message: "표시명은 최대 32자" };
    case "invalid_email":
      return { field: "email", message: "이메일 형식 확인 필요" };
    case "email_taken":
      return { field: "email", message: "이미 가입된 이메일" };
    case "invalid_credentials":
      return { field: null, message: "이메일 또는 비밀번호 확인 필요" };
    case "account_locked": {
      const sec = retryAfterSec(e);
      const when = sec && sec > 0 ? `${sec}초 후` : "잠시 후";
      return { field: null, message: `로그인 시도가 많아 계정 잠김. ${when} 다시 시도 필요` };
    }
    case "account_suspended":
      return { field: null, message: "정지된 계정. 로그인 불가" };
    case "session_expired":
    case "refresh_reuse":
      return { field: null, message: "세션 만료. 다시 로그인 필요" };
    case "too_many_requests":
    case "auth_throttled":
      return { field: null, message: "요청 과다. 잠시 후 다시 시도 필요" };
    default:
      return { field: null, message: "요청 실패. 잠시 후 다시 시도 필요" };
  }
}
