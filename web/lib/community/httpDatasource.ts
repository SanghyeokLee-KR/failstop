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
  let name = res.status >= 500 ? "server_error" : "invalid_parameter";
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
  private readonly fetchFn: typeof fetch;

  constructor(options: HttpCommunityOptions) {
    this.apiBase = options.apiBase.replace(/\/+$/, "");
    this.identity = options.identityProvider;
    this.fetchFn = options.fetchFn ?? ((input, init) => fetch(input, init));
  }

  private async request<T>(path: string, init: RequestInit, auth: boolean): Promise<T> {
    const headers: Record<string, string> = { ...(init.body != null ? JSON_HEADERS : {}) };
    if (auth) {
      const me = this.identity();
      // 미로그인 쓰기는 서버 왕복 전에 로그인 유도로 표면화(§4 인증)
      if (!me) throw new ApiError(401, "jwt_verification", "로그인이 필요해요");
      headers.Authorization = `Bearer ${me.token}`;
    }
    const res = await this.fetchFn(`${this.apiBase}${BASE}${path}`, { ...init, headers });
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

  async listPosts(market: string, sort: PostSort, cursor?: string | null, count?: number): Promise<CursorPage<CommunityPost>> {
    const q = new URLSearchParams({ sort });
    if (cursor) q.set("cursor", cursor);
    if (count) q.set("count", String(count));
    return this.page<CommunityPost>(
      await this.request(`/boards/${encodeURIComponent(market)}/posts?${q}`, {}, false),
    );
  }

  async getPost(externalId: string): Promise<CommunityPost> {
    return this.unwrap<CommunityPost>(await this.request(`/posts/${externalId}`, {}, false));
  }

  async createPost(market: string, input: CreatePostInput): Promise<CommunityPost> {
    const headers: Record<string, string> = {};
    if (input.clientKey) headers["Idempotency-Key"] = input.clientKey;
    return this.unwrap<CommunityPost>(
      await this.request(
        `/boards/${encodeURIComponent(market)}/posts`,
        { method: "POST", headers, body: JSON.stringify({ title: input.title, body: input.body }) },
        true,
      ),
    );
  }

  async updatePost(externalId: string, input: UpdatePostInput): Promise<CommunityPost> {
    return this.unwrap<CommunityPost>(
      await this.request(`/posts/${externalId}`, { method: "PATCH", body: JSON.stringify(input) }, true),
    );
  }

  async deletePost(externalId: string): Promise<void> {
    await this.request(`/posts/${externalId}`, { method: "DELETE" }, true);
  }

  async listComments(postExternalId: string, cursor?: string | null, count?: number): Promise<CursorPage<CommunityComment>> {
    const q = new URLSearchParams();
    if (cursor) q.set("cursor", cursor);
    if (count) q.set("count", String(count));
    return this.page<CommunityComment>(
      await this.request(`/posts/${postExternalId}/comments?${q}`, {}, false),
    );
  }

  async createComment(postExternalId: string, input: CreateCommentInput): Promise<CommunityComment> {
    const headers: Record<string, string> = {};
    if (input.clientKey) headers["Idempotency-Key"] = input.clientKey;
    const body: Record<string, unknown> = { body: input.body };
    if (input.parentId) body.parent_comment_id = input.parentId;
    return this.unwrap<CommunityComment>(
      await this.request(`/posts/${postExternalId}/comments`, { method: "POST", headers, body: JSON.stringify(body) }, true),
    );
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
