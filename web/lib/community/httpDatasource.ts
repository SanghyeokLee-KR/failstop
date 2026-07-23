/**
 * lib/community/httpDatasource.ts. community-api 실서버 어댑터(게시판_설계 §4).
 * 경로 접두 /api/v1/community. 거래 어댑터와 별개 배포 단위 D를 호출한다(§2.1, §2.5).
 * 엔벌로프·에러·커서는 API 공통 규약 상속(§2-2/§2-3/§2-6). 쓰기는 회원 Bearer 필수.
 */

import { ApiError } from "../api/httpDatasource";
import type {
  CommunityComment,
  CommunityPost,
  CreateCommentInput,
  CreatePostInput,
  CursorPage,
  LikeResult,
  MemberIdentity,
  PostSort,
  ReportInput,
  UpdatePostInput,
} from "./types";

export interface HttpCommunityOptions {
  apiBase: string;
  identityProvider: () => MemberIdentity | null;
  /** access 만료 시 회원 세션 갱신. 거래 어댑터 refresh와 대칭. 없으면 401을 로그인 유도로 표면화 */
  refreshIdentity?: () => Promise<MemberIdentity | null>;
  fetchFn?: typeof fetch;
}

const BASE = "/api/v1/community";
const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };
const GENERIC_MESSAGE = "요청에 실패했어요. 잠시 후 다시 시도해 주세요";

interface ErrorEnvelope {
  error?: { name?: string; message?: string; retry_after?: number | null; detail?: unknown };
}

interface ListEnvelope<T> {
  data?: T[];
  pagination?: { next_cursor?: string | null; has_more?: boolean };
}

async function toApiError(res: Response): Promise<ApiError> {
  // 비 JSON 게이트웨이 응답도 상태코드로 분류한다. 429를 invalid_parameter로 오분류하지 않는다
  let name = res.status >= 500 ? "server_error" : res.status === 429 ? "too_many_requests" : "invalid_parameter";
  let message = GENERIC_MESSAGE;
  let retryAfter: number | null = null;
  try {
    const body = (await res.json()) as ErrorEnvelope;
    if (body?.error?.name) {
      name = body.error.name;
      if (body.error.message) message = body.error.message;
      retryAfter = body.error.retry_after ?? null;
    }
  } catch {
    // 비 JSON 본문은 상태코드 기반 기본값 유지
  }
  if (retryAfter === null) {
    const header = res.headers?.get("Retry-After");
    const sec = header != null ? Number(header) : Number.NaN;
    if (Number.isFinite(sec) && sec >= 0) retryAfter = sec;
  }
  return new ApiError(res.status, name, message, retryAfter);
}

export class HttpCommunityDataSource {
  private readonly apiBase: string;
  private readonly identity: () => MemberIdentity | null;
  private readonly refreshIdentity?: () => Promise<MemberIdentity | null>;
  private readonly fetchFn: typeof fetch;

  constructor(options: HttpCommunityOptions) {
    this.apiBase = options.apiBase.replace(/\/+$/, "");
    this.identity = options.identityProvider;
    this.refreshIdentity = options.refreshIdentity;
    this.fetchFn = options.fetchFn ?? ((input, init) => fetch(input, init));
  }

  private async request<T>(path: string, init: RequestInit, auth: boolean, retried = false): Promise<T> {
    // init.headers(멱등키 등)를 보존한다. JSON_HEADERS·Authorization은 이후 병합으로 확정한다
    const headers: Record<string, string> = {
      ...(init.body != null ? JSON_HEADERS : {}),
      ...(init.headers as Record<string, string> | undefined),
    };
    if (auth) {
      const me = this.identity();
      // 미로그인 쓰기는 서버 왕복 전에 로그인 유도로 표면화(§4 인증)
      if (!me) throw new ApiError(401, "jwt_verification", "로그인이 필요해요");
      headers.Authorization = `Bearer ${me.token}`;
    }
    const res = await this.fetchFn(`${this.apiBase}${BASE}${path}`, { ...init, headers });
    if (res.status === 401 && auth && !retried && this.refreshIdentity) {
      // access 만료·refresh 쿠키 유효 시 거래 어댑터와 대칭으로 1회 갱신 후 재시도.
      // init(멱등키·본문 포함)을 그대로 재전송해 재시도가 중복 생성으로 이어지지 않게 한다
      const fresh = await this.refreshIdentity();
      if (fresh) return this.request<T>(path, init, auth, true);
    }
    if (!res.ok) throw await toApiError(res);
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  private unwrap<T>(json: unknown): T {
    const env = json as { data?: T };
    return env && "data" in env && env.data !== undefined ? (env.data as T) : (json as T);
  }

  private page<T>(json: unknown): CursorPage<T> {
    const env = json as ListEnvelope<T>;
    const items = Array.isArray(env?.data) ? env.data : [];
    return {
      items,
      next_cursor: env?.pagination?.next_cursor ?? null,
      has_more: env?.pagination?.has_more ?? false,
    };
  }

  // 백엔드 와이어는 식별자를 post_id·comment_id로 싣고(게시판_설계 §3·§4) liked·mine·author_id를
  // 공개 GET에 싣지 않는다. 뷰모델(external_id 기준)로 무매핑 캐스팅하면 상세 이동·좋아요·삭제가
  // 전부 undefined id로 깨진다(sim은 뷰모델 형태라 은폐). 여기서 단일 정규화로 닫는다.
  // mine은 세션 핸들 대조로 파생한다(서버가 권위 검증하므로 UI 게이트 용도).
  private normPost(w: Partial<CommunityPost> & { post_id?: string }): CommunityPost {
    const me = this.identity();
    return {
      external_id: w.external_id ?? w.post_id ?? "",
      market: w.market ?? "",
      author_id: w.author_id ?? "",
      author_handle: w.author_handle ?? "",
      title: w.title ?? "",
      body: w.body ?? "",
      status: w.status ?? "VISIBLE",
      like_count: w.like_count ?? 0,
      comment_count: w.comment_count ?? 0,
      liked: w.liked ?? false,
      mine: w.mine ?? (me != null && w.author_handle === me.handle),
      created_at: w.created_at ?? "",
      updated_at: w.updated_at ?? "",
    };
  }

  private normComment(w: Partial<CommunityComment> & { comment_id?: string }, postExternalId: string): CommunityComment {
    const me = this.identity();
    return {
      external_id: w.external_id ?? w.comment_id ?? "",
      post_id: w.post_id ?? postExternalId,
      parent_id: w.parent_id ?? null,
      author_id: w.author_id ?? "",
      author_handle: w.author_handle ?? "",
      body: w.body ?? "",
      status: w.status ?? "VISIBLE",
      like_count: w.like_count ?? 0,
      liked: w.liked ?? false,
      mine: w.mine ?? (me != null && w.author_handle === me.handle),
      created_at: w.created_at ?? "",
    };
  }

  async listPosts(market: string, sort: PostSort, cursor?: string | null, count?: number): Promise<CursorPage<CommunityPost>> {
    const q = new URLSearchParams({ sort });
    if (cursor) q.set("cursor", cursor);
    if (count) q.set("count", String(count));
    const page = this.page<Partial<CommunityPost> & { post_id?: string }>(
      await this.request(`/boards/${encodeURIComponent(market)}/posts?${q}`, {}, false),
    );
    return { ...page, items: page.items.map((w) => this.normPost(w)) };
  }

  async getPost(externalId: string): Promise<CommunityPost> {
    return this.normPost(
      this.unwrap<Partial<CommunityPost> & { post_id?: string }>(
        await this.request(`/posts/${externalId}`, {}, false),
      ),
    );
  }

  async createPost(market: string, input: CreatePostInput): Promise<CommunityPost> {
    const headers: Record<string, string> = {};
    if (input.clientKey) headers["Idempotency-Key"] = input.clientKey;
    const created = this.unwrap<Partial<CommunityPost> & { post_id?: string }>(
      await this.request(
        `/boards/${encodeURIComponent(market)}/posts`,
        { method: "POST", headers, body: JSON.stringify({ title: input.title, body: input.body }) },
        true,
      ),
    );
    // 백엔드 생성 응답은 {schema_version, post_id}만 반환해(게시판_설계 §4.1) 상세 필드가 없다.
    // sim은 전체 객체를 돌려주므로 계약을 맞추려 생성된 id로 단건 재조회해 합성한다.
    // 백엔드가 이미 전체 객체를 반환하면(추후 계약 확정) 재조회 없이 그대로 쓴다.
    if (created.external_id && typeof created.body === "string") return created as CommunityPost;
    const id = created.external_id ?? created.post_id;
    if (!id) throw new ApiError(0, "invalid_envelope", "글 생성 응답에 식별자가 없습니다");
    return this.getPost(id);
  }

  async updatePost(externalId: string, input: UpdatePostInput): Promise<CommunityPost> {
    return this.normPost(
      this.unwrap<Partial<CommunityPost> & { post_id?: string }>(
        await this.request(`/posts/${externalId}`, { method: "PATCH", body: JSON.stringify(input) }, true),
      ),
    );
  }

  async deletePost(externalId: string): Promise<void> {
    await this.request(`/posts/${externalId}`, { method: "DELETE" }, true);
  }

  async listComments(postExternalId: string, cursor?: string | null, count?: number): Promise<CursorPage<CommunityComment>> {
    const q = new URLSearchParams();
    if (cursor) q.set("cursor", cursor);
    if (count) q.set("count", String(count));
    const page = this.page<Partial<CommunityComment> & { comment_id?: string }>(
      await this.request(`/posts/${postExternalId}/comments?${q}`, {}, false),
    );
    return { ...page, items: page.items.map((w) => this.normComment(w, postExternalId)) };
  }

  async createComment(postExternalId: string, input: CreateCommentInput): Promise<CommunityComment> {
    const headers: Record<string, string> = {};
    if (input.clientKey) headers["Idempotency-Key"] = input.clientKey;
    const body: Record<string, unknown> = { body: input.body };
    if (input.parentId) body.parent_comment_id = input.parentId;
    const created = this.unwrap<Partial<CommunityComment> & { comment_id?: string }>(
      await this.request(`/posts/${postExternalId}/comments`, { method: "POST", headers, body: JSON.stringify(body) }, true),
    );
    // 백엔드 생성 응답은 {schema_version, comment_id}만 반환하고(게시판_설계 §4.1) 단건 댓글 GET이
    // 계약에 없다(§4 표). 전체 객체를 반환하면 그대로 쓰고, 아니면 요청 본문·회원 신원으로
    // sim과 동일 형태를 합성한다. 방금 작성자가 요청자이므로 mine=true, like는 0에서 시작.
    if (created.external_id && typeof created.body === "string") return created as CommunityComment;
    const id = created.external_id ?? created.comment_id;
    const me = this.identity();
    if (!id || !me) throw new ApiError(0, "invalid_envelope", "댓글 생성 응답에 식별자가 없습니다");
    return {
      external_id: id,
      post_id: postExternalId,
      parent_id: input.parentId ?? null,
      author_id: me.externalId,
      author_handle: me.handle,
      body: input.body.trim(),
      status: "VISIBLE",
      like_count: 0,
      liked: false,
      mine: true,
      created_at: new Date().toISOString(),
    };
  }

  async deleteComment(externalId: string): Promise<void> {
    await this.request(`/comments/${externalId}`, { method: "DELETE" }, true);
  }

  async togglePostLike(externalId: string, like: boolean): Promise<LikeResult> {
    return this.unwrap<LikeResult>(
      await this.request(`/posts/${externalId}/like`, { method: like ? "POST" : "DELETE" }, true),
    );
  }

  async toggleCommentLike(externalId: string, like: boolean): Promise<LikeResult> {
    return this.unwrap<LikeResult>(
      await this.request(`/comments/${externalId}/like`, { method: like ? "POST" : "DELETE" }, true),
    );
  }

  async report(input: ReportInput): Promise<void> {
    await this.request(
      `/reports`,
      {
        method: "POST",
        body: JSON.stringify({
          target_type: input.targetType,
          target_id: input.targetId,
          reason: input.reason,
          detail: input.detail ?? null,
        }),
      },
      true,
    );
  }
}
