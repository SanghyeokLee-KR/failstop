/**
 * lib/community/errors.ts. 커뮤니티 에러코드 to 화면 매핑(게시판_설계 §4.2, API §2-3 재사용).
 * ApiError는 거래 어댑터와 공유한다(name에 코드 노출). 게시판 전용 문구·필드로 좁힌다.
 */

import { ApiError } from "../api/httpDatasource";

export type CommunityField = "title" | "body" | "detail" | null;

export interface MappedCommunityError {
  field: CommunityField;
  message: string;
  /** 429 등 재시도 대기 초. 안내 문구 보조 */
  retryAfter: number | null;
  /** 미인증(작성·수정·삭제·좋아요·신고). 화면이 로그인 유도로 분기 */
  needsLogin: boolean;
}

function codeOf(e: unknown): string {
  if (e instanceof ApiError) return e.code;
  if (e instanceof Error) return e.name;
  return "unknown";
}

function retryAfterSec(e: unknown): number | null {
  return e instanceof ApiError ? e.retryAfter : null;
}

export function mapCommunityError(e: unknown): MappedCommunityError {
  const code = codeOf(e);
  const retryAfter = retryAfterSec(e);
  switch (code) {
    case "jwt_verification":
    case "session_expired":
    case "refresh_reuse":
      return { field: null, message: "로그인 필요", retryAfter, needsLogin: true };
    case "out_of_scope":
      return { field: null, message: "본인 글·댓글만 수정·삭제 가능", retryAfter, needsLogin: false };
    case "not_found":
      return { field: null, message: "이미 삭제됐거나 없는 대상", retryAfter, needsLogin: false };
    case "post_not_found":
      return { field: null, message: "이미 삭제됐거나 없는 글", retryAfter, needsLogin: false };
    case "comment_not_found":
      return { field: null, message: "이미 삭제됐거나 없는 댓글", retryAfter, needsLogin: false };
    case "board_not_found":
      return { field: null, message: "존재하지 않는 종목 게시판", retryAfter, needsLogin: false };
    case "duplicate_report":
    case "conflict":
      return { field: null, message: "이미 신고한 대상", retryAfter, needsLogin: false };
    case "title_too_long":
      return { field: "title", message: "제목 길이 초과", retryAfter, needsLogin: false };
    case "body_too_long":
      return { field: "body", message: "본문 길이 초과", retryAfter, needsLogin: false };
    case "invalid_parameter": {
      // 길이·형식 위반은 백엔드 세부 코드가 없으면 본문 필드로 귀속(작성 폼 최다 케이스)
      return { field: "body", message: "입력 내용 확인 필요", retryAfter, needsLogin: false };
    }
    case "too_many_requests":
    case "rate_limited": {
      const when = retryAfter && retryAfter > 0 ? `${retryAfter}초 후` : "잠시 후";
      return { field: null, message: `요청 과다. ${when} 다시 시도 필요`, retryAfter, needsLogin: false };
    }
    default:
      return { field: null, message: "요청 실패. 잠시 후 다시 시도 필요", retryAfter, needsLogin: false };
  }
}
