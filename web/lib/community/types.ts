/**
 * lib/community/types.ts. 종목 토론 게시판 도메인 타입(게시판_설계 §1, §3, §4 미러).
 * 거래 도메인(lib/types.ts)과 분리한다. 게시판은 코어 스키마를 참조하지 않는다(§2 격리).
 * 수치는 카운트(정수)뿐이다. NUMERIC 문자열 규약은 게시판에 해당 없다(§0).
 */

export const COMMUNITY_SCHEMA_VERSION = "1.0" as const;

/** 정렬 축(§1.2). latest=최신, popular=인기 순위 스냅샷 */
export type PostSort = "latest" | "popular";

/** 신고 사유(§3 reports.reason) */
export type ReportReason = "SPAM" | "ABUSE" | "ILLEGAL" | "ETC";

/** 콘텐츠 상태(§1.3). DELETED·HIDDEN은 목록·집계 제외, 본문 마스킹 */
export type ContentStatus = "VISIBLE" | "HIDDEN" | "DELETED";

/** 신고 대상 종류 */
export type ReportTargetType = "POST" | "COMMENT";

/** 길이 가드(§4.3). 앱·백엔드 공통 상한 */
export const POST_TITLE_MAX = 120;
export const POST_BODY_MAX = 4000;
export const COMMENT_BODY_MAX = 1000;
export const REPORT_DETAIL_MAX = 500;

/** 글(§3 posts). liked·mine은 요청자 기준 파생값(로그인 시 유효) */
export interface CommunityPost {
  external_id: string;
  market: string;
  author_id: string;
  author_handle: string;
  title: string;
  body: string;
  status: ContentStatus;
  like_count: number;
  comment_count: number;
  liked: boolean;
  mine: boolean;
  created_at: string;
  updated_at: string;
}

/** 댓글(§3 comments). 1뎁스 대댓글은 parent_id로 표현 */
export interface CommunityComment {
  external_id: string;
  post_id: string;
  parent_id: string | null;
  author_id: string;
  author_handle: string;
  body: string;
  status: ContentStatus;
  like_count: number;
  liked: boolean;
  mine: boolean;
  created_at: string;
}

/** 커서 페이지(§1.2 엔벌로프의 프론트 표현) */
export interface CursorPage<T> {
  items: T[];
  next_cursor: string | null;
  has_more: boolean;
}

/** 좋아요 토글 결과. 낙관 UI 롤백 기준값 */
export interface LikeResult {
  like_count: number;
  liked: boolean;
}

export interface CreatePostInput {
  title: string;
  body: string;
  /** Idempotency-Key(§4.2). 재시도 중복 작성 차단. 미제공도 허용 */
  clientKey?: string;
}

export interface UpdatePostInput {
  title: string;
  body: string;
}

export interface CreateCommentInput {
  body: string;
  /** 1뎁스 대댓글 대상 댓글 external_id */
  parentId?: string | null;
  clientKey?: string;
}

export interface ReportInput {
  targetType: ReportTargetType;
  targetId: string;
  reason: ReportReason;
  detail?: string;
}

/** 회원 신원(단방향 소비, §0). 게시판은 이 값으로만 작성자를 확정한다 */
export interface MemberIdentity {
  externalId: string;
  handle: string;
  token: string;
}
