/**
 * lib/community/datasource.ts. 게시판 데이터 소스 추상화(게시판_설계 §5 CommunityDataSource).
 * 거래용 DataSource·zustand 스토어와 독립이다. 게시판 요청 실패가 시세 스토어·WS에 전파되지 않는다.
 * 구현은 SimCommunityDataSource(데모 인메모리)와 HttpCommunityDataSource(실 API) 둘.
 * 회원 신원은 단방향 소비만 한다(§0). identityProvider로 주입받아 작성자를 확정하고 Bearer를 첨부한다.
 */

import { ApiError } from "../api/httpDatasource";
import { getDataSource } from "../api/datasource";
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
import {
  COMMENT_BODY_MAX,
  POST_BODY_MAX,
  POST_TITLE_MAX,
  REPORT_DETAIL_MAX,
} from "./types";
import { HttpCommunityDataSource } from "./httpDatasource";

export type IdentityProvider = () => MemberIdentity | null;

export interface CommunityDataSource {
  listPosts(market: string, sort: PostSort, cursor?: string | null, count?: number): Promise<CursorPage<CommunityPost>>;
  getPost(externalId: string): Promise<CommunityPost>;
  createPost(market: string, input: CreatePostInput): Promise<CommunityPost>;
  updatePost(externalId: string, input: UpdatePostInput): Promise<CommunityPost>;
  deletePost(externalId: string): Promise<void>;

  listComments(postExternalId: string, cursor?: string | null, count?: number): Promise<CursorPage<CommunityComment>>;
  createComment(postExternalId: string, input: CreateCommentInput): Promise<CommunityComment>;
  deleteComment(externalId: string): Promise<void>;

  togglePostLike(externalId: string, like: boolean): Promise<LikeResult>;
  toggleCommentLike(externalId: string, like: boolean): Promise<LikeResult>;

  report(input: ReportInput): Promise<void>;
}

const DEFAULT_COUNT = 20;
const MAX_COUNT = 50;
const DELETED_POST_BODY = "삭제된 글입니다";
const DELETED_COMMENT_BODY = "삭제된 댓글입니다";

function clampCount(count?: number): number {
  if (!count || count < 1) return DEFAULT_COUNT;
  return Math.min(count, MAX_COUNT);
}

function encodeCursor(payload: unknown): string {
  return btoa(unescape(encodeURIComponent(JSON.stringify(payload))));
}

function decodeCursor<T>(cursor: string): T | null {
  try {
    return JSON.parse(decodeURIComponent(escape(atob(cursor)))) as T;
  } catch {
    return null;
  }
}

/* ===== 인메모리 레코드(sim 전용) ===== */

interface PostRecord {
  n: number;
  external_id: string;
  market: string;
  author_id: string;
  author_handle: string;
  title: string;
  body: string;
  deleted: boolean;
  likers: Set<string>;
  created_at: string;
  updated_at: string;
}

interface CommentRecord {
  n: number;
  external_id: string;
  post_n: number;
  parent_id: string | null;
  author_id: string;
  author_handle: string;
  body: string;
  deleted: boolean;
  likers: Set<string>;
  created_at: string;
}

interface LatestCursor {
  s: "latest";
  n: number;
}
interface PopularCursor {
  s: "popular";
  g: number;
  i: number;
}

/**
 * 시뮬레이터 기반 구현. 런타임 외부 의존 0.
 * 새로고침 시 싱글턴 재생성으로 게시판 상태가 소멸한다(데모 한계). 인기순은 세대 스냅샷으로
 * 커서 축을 가변 like_count에서 분리한다(§1.2).
 */
export class SimCommunityDataSource implements CommunityDataSource {
  private posts = new Map<string, PostRecord>();
  private comments = new Map<string, CommentRecord>();
  private reports = new Set<string>();
  private idempotency = new Map<string, string>();
  private popularSnapshots = new Map<string, string[]>();
  private seq = 0;
  private gen = 0;

  constructor(private identity: IdentityProvider) {
    seedDemoBoard(this);
  }

  /** 데모 시드 주입 경로(생성자 전용). 합성 작성자로 초기 글을 심는다 */
  _seedPost(market: string, authorId: string, handle: string, title: string, body: string, ageMs: number, likers: string[]): PostRecord {
    const now = Date.now() - ageMs;
    const rec: PostRecord = {
      n: ++this.seq,
      external_id: crypto.randomUUID(),
      market,
      author_id: authorId,
      author_handle: handle,
      title,
      body,
      deleted: false,
      likers: new Set(likers),
      created_at: new Date(now).toISOString(),
      updated_at: new Date(now).toISOString(),
    };
    this.posts.set(rec.external_id, rec);
    return rec;
  }

  _seedComment(post: PostRecord, authorId: string, handle: string, body: string, ageMs: number): void {
    const now = Date.now() - ageMs;
    const rec: CommentRecord = {
      n: ++this.seq,
      external_id: crypto.randomUUID(),
      post_n: post.n,
      parent_id: null,
      author_id: authorId,
      author_handle: handle,
      body,
      deleted: false,
      likers: new Set(),
      created_at: new Date(now).toISOString(),
    };
    this.comments.set(rec.external_id, rec);
  }

  private me(): MemberIdentity | null {
    return this.identity();
  }

  private requireMe(): MemberIdentity {
    const id = this.me();
    if (!id) throw new ApiError(401, "jwt_verification", "로그인 필요");
    return id;
  }

  private toPost(rec: PostRecord): CommunityPost {
    const me = this.me();
    const commentCount = this.visibleCommentCount(rec.n);
    return {
      external_id: rec.external_id,
      market: rec.market,
      author_id: rec.author_id,
      author_handle: rec.author_handle,
      title: rec.deleted ? "삭제된 글" : rec.title,
      body: rec.deleted ? DELETED_POST_BODY : rec.body,
      status: rec.deleted ? "DELETED" : "VISIBLE",
      like_count: rec.likers.size,
      comment_count: commentCount,
      liked: me ? rec.likers.has(me.externalId) : false,
      mine: me ? rec.author_id === me.externalId : false,
      created_at: rec.created_at,
      updated_at: rec.updated_at,
    };
  }

  private toComment(rec: CommentRecord): CommunityComment {
    const me = this.me();
    const post = [...this.posts.values()].find((p) => p.n === rec.post_n);
    return {
      external_id: rec.external_id,
      post_id: post?.external_id ?? "",
      parent_id: rec.parent_id,
      author_id: rec.author_id,
      author_handle: rec.author_handle,
      body: rec.deleted ? DELETED_COMMENT_BODY : rec.body,
      status: rec.deleted ? "DELETED" : "VISIBLE",
      like_count: rec.likers.size,
      liked: me ? rec.likers.has(me.externalId) : false,
      mine: me ? rec.author_id === me.externalId : false,
      created_at: rec.created_at,
    };
  }

  private visibleCommentCount(postN: number): number {
    let c = 0;
    for (const cm of this.comments.values()) if (cm.post_n === postN && !cm.deleted) c++;
    return c;
  }

  async listPosts(market: string, sort: PostSort, cursor?: string | null, count?: number): Promise<CursorPage<CommunityPost>> {
    const size = clampCount(count);
    const visible = [...this.posts.values()].filter((p) => p.market === market && !p.deleted);

    if (sort === "popular") {
      return this.listPopular(market, visible, cursor, size);
    }

    // 최신순: created_at DESC, n DESC. n이 삽입 단조라 정렬 안정성 대리(§1.2)
    const sorted = visible.sort((a, b) => b.n - a.n);
    let start = 0;
    if (cursor) {
      const c = decodeCursor<LatestCursor>(cursor);
      if (c && c.s === "latest") start = sorted.findIndex((p) => p.n < c.n);
      if (start < 0) start = sorted.length;
    }
    const page = sorted.slice(start, start + size);
    const hasMore = start + size < sorted.length;
    const last = page[page.length - 1];
    return {
      items: page.map((p) => this.toPost(p)),
      next_cursor: hasMore && last ? encodeCursor({ s: "latest", n: last.n } satisfies LatestCursor) : null,
      has_more: hasMore,
    };
  }

  private listPopular(market: string, visible: PostRecord[], cursor: string | null | undefined, size: number): CursorPage<CommunityPost> {
    let gen: number;
    let order: string[];
    let index: number;
    if (cursor) {
      const c = decodeCursor<PopularCursor>(cursor);
      if (c && c.s === "popular" && this.popularSnapshots.has(`${market}#${c.g}`)) {
        gen = c.g;
        order = this.popularSnapshots.get(`${market}#${c.g}`)!;
        index = c.i;
      } else {
        ({ gen, order } = this.snapshotPopular(market, visible));
        index = 0;
      }
    } else {
      ({ gen, order } = this.snapshotPopular(market, visible));
      index = 0;
    }
    // 커서 축은 불변 order 인덱스다(§1.2 커서 축 고정). 삭제 필터를 거친 가변 배열을 축으로
    // 쓰면 이미 노출된 구간의 삭제가 배열을 왼쪽으로 당겨 미노출 글이 건너뛰어진다.
    // 페이지 조립 시 order[i..]를 순회하며 삭제 글만 건너뛰어 size만큼 채운다
    const page: PostRecord[] = [];
    let i = index;
    for (; i < order.length && page.length < size; i += 1) {
      const p = this.posts.get(order[i]);
      if (p && !p.deleted) page.push(p);
    }
    let hasMore = false;
    for (let j = i; j < order.length; j += 1) {
      const p = this.posts.get(order[j]);
      if (p && !p.deleted) {
        hasMore = true;
        break;
      }
    }
    return {
      items: page.map((p) => this.toPost(p)),
      next_cursor: hasMore ? encodeCursor({ s: "popular", g: gen, i } satisfies PopularCursor) : null,
      has_more: hasMore,
    };
  }

  private snapshotPopular(market: string, visible: PostRecord[]): { gen: number; order: string[] } {
    const gen = ++this.gen;
    const order = [...visible]
      .sort((a, b) => b.likers.size - a.likers.size || b.n - a.n)
      .map((p) => p.external_id);
    this.popularSnapshots.set(`${market}#${gen}`, order);
    return { gen, order };
  }

  async getPost(externalId: string): Promise<CommunityPost> {
    const rec = this.posts.get(externalId);
    if (!rec) throw new ApiError(404, "post_not_found", "이미 삭제됐거나 없는 글");
    return this.toPost(rec);
  }

  async createPost(market: string, input: CreatePostInput): Promise<CommunityPost> {
    const me = this.requireMe();
    if (input.clientKey) {
      const existing = this.idempotency.get(input.clientKey);
      if (existing && this.posts.has(existing)) return this.toPost(this.posts.get(existing)!);
    }
    const title = input.title.trim();
    const body = input.body.trim();
    assertLen(title, 1, POST_TITLE_MAX, "title", "제목");
    assertLen(body, 1, POST_BODY_MAX, "body", "본문");
    const iso = new Date().toISOString();
    const rec: PostRecord = {
      n: ++this.seq,
      external_id: crypto.randomUUID(),
      market,
      author_id: me.externalId,
      author_handle: me.handle,
      title,
      body,
      deleted: false,
      likers: new Set(),
      created_at: iso,
      updated_at: iso,
    };
    this.posts.set(rec.external_id, rec);
    if (input.clientKey) this.idempotency.set(input.clientKey, rec.external_id);
    return this.toPost(rec);
  }

  async updatePost(externalId: string, input: UpdatePostInput): Promise<CommunityPost> {
    const me = this.requireMe();
    const rec = this.posts.get(externalId);
    if (!rec || rec.deleted) throw new ApiError(404, "post_not_found", "이미 삭제됐거나 없는 글");
    if (rec.author_id !== me.externalId) throw new ApiError(403, "out_of_scope", "본인 글만 수정 가능");
    const title = input.title.trim();
    const body = input.body.trim();
    assertLen(title, 1, POST_TITLE_MAX, "title", "제목");
    assertLen(body, 1, POST_BODY_MAX, "body", "본문");
    rec.title = title;
    rec.body = body;
    rec.updated_at = new Date().toISOString();
    return this.toPost(rec);
  }

  async deletePost(externalId: string): Promise<void> {
    const me = this.requireMe();
    const rec = this.posts.get(externalId);
    if (!rec || rec.deleted) throw new ApiError(404, "post_not_found", "이미 삭제됐거나 없는 글");
    if (rec.author_id !== me.externalId) throw new ApiError(403, "out_of_scope", "본인 글만 삭제 가능");
    rec.deleted = true;
  }

  async listComments(postExternalId: string, cursor?: string | null, count?: number): Promise<CursorPage<CommunityComment>> {
    const post = this.posts.get(postExternalId);
    if (!post) throw new ApiError(404, "post_not_found", "이미 삭제됐거나 없는 글");
    const size = clampCount(count);
    // 삭제 댓글은 트리 유지를 위해 마스킹으로 노출한다(§1.3). created_at ASC, n ASC
    const all = [...this.comments.values()].filter((c) => c.post_n === post.n).sort((a, b) => a.n - b.n);
    let start = 0;
    if (cursor) {
      const c = decodeCursor<{ n: number }>(cursor);
      if (c) start = all.findIndex((cm) => cm.n > c.n);
      if (start < 0) start = all.length;
    }
    const page = all.slice(start, start + size);
    const hasMore = start + size < all.length;
    const last = page[page.length - 1];
    return {
      items: page.map((c) => this.toComment(c)),
      next_cursor: hasMore && last ? encodeCursor({ n: last.n }) : null,
      has_more: hasMore,
    };
  }

  async createComment(postExternalId: string, input: CreateCommentInput): Promise<CommunityComment> {
    const me = this.requireMe();
    const post = this.posts.get(postExternalId);
    if (!post || post.deleted) throw new ApiError(404, "post_not_found", "이미 삭제됐거나 없는 글");
    const body = input.body.trim();
    assertLen(body, 1, COMMENT_BODY_MAX, "body", "댓글");
    let parentId: string | null = null;
    if (input.parentId) {
      const parent = this.comments.get(input.parentId);
      if (!parent || parent.deleted) throw new ApiError(404, "comment_not_found", "이미 삭제된 댓글");
      // 1뎁스 강제: 부모가 이미 대댓글이면 그 부모로 올려붙인다(깊이 제한, §1.1)
      parentId = parent.parent_id ?? parent.external_id;
    }
    const rec: CommentRecord = {
      n: ++this.seq,
      external_id: crypto.randomUUID(),
      post_n: post.n,
      parent_id: parentId,
      author_id: me.externalId,
      author_handle: me.handle,
      body,
      deleted: false,
      likers: new Set(),
      created_at: new Date().toISOString(),
    };
    this.comments.set(rec.external_id, rec);
    return this.toComment(rec);
  }

  async deleteComment(externalId: string): Promise<void> {
    const me = this.requireMe();
    const rec = this.comments.get(externalId);
    if (!rec || rec.deleted) throw new ApiError(404, "comment_not_found", "이미 삭제된 댓글");
    if (rec.author_id !== me.externalId) throw new ApiError(403, "out_of_scope", "본인 댓글만 삭제 가능");
    rec.deleted = true;
  }

  async togglePostLike(externalId: string, like: boolean): Promise<LikeResult> {
    const me = this.requireMe();
    const rec = this.posts.get(externalId);
    if (!rec || rec.deleted) throw new ApiError(404, "post_not_found", "이미 삭제됐거나 없는 글");
    if (like) rec.likers.add(me.externalId);
    else rec.likers.delete(me.externalId);
    return { like_count: rec.likers.size, liked: rec.likers.has(me.externalId) };
  }

  async toggleCommentLike(externalId: string, like: boolean): Promise<LikeResult> {
    const me = this.requireMe();
    const rec = this.comments.get(externalId);
    if (!rec || rec.deleted) throw new ApiError(404, "comment_not_found", "이미 삭제된 댓글");
    if (like) rec.likers.add(me.externalId);
    else rec.likers.delete(me.externalId);
    return { like_count: rec.likers.size, liked: rec.likers.has(me.externalId) };
  }

  async report(input: ReportInput): Promise<void> {
    const me = this.requireMe();
    if (input.detail && input.detail.length > REPORT_DETAIL_MAX) {
      throw new ApiError(400, "invalid_parameter", "신고 사유 설명 길이 초과");
    }
    const key = `${input.targetType}:${input.targetId}:${me.externalId}`;
    if (this.reports.has(key)) throw new ApiError(409, "duplicate_report", "이미 신고한 대상");
    this.reports.add(key);
  }
}

function assertLen(v: string, min: number, max: number, field: "title" | "body", label: string): void {
  if (v.length < min) throw new ApiError(400, "invalid_parameter", `${label} 입력 필요`);
  if (v.length > max) {
    const code = field === "title" ? "title_too_long" : "body_too_long";
    throw new ApiError(400, code, `${label} 길이 초과`);
  }
}

/* ===== 데모 시드 ===== */

function seedDemoBoard(ds: SimCommunityDataSource): void {
  const MIN = 60_000;
  const HOUR = 60 * MIN;
  const DAY = 24 * HOUR;

  const btc1 = ds._seedPost(
    "KRW-BTC",
    "seed-user-1",
    "존버는승리한다",
    "지금 조정은 눌림목이라고 봅니다",
    "단기 변동성은 크지만 온체인 지표는 여전히 매집 구간을 가리키고 있어요. 분할 매수로 대응 중입니다.",
    3 * HOUR,
    ["seed-user-2", "seed-user-3", "seed-user-4", "seed-user-5"],
  );
  ds._seedComment(btc1, "seed-user-2", "코린이", "저도 분할로 담고 있어요. 멘탈 관리가 반이네요", 2 * HOUR);
  ds._seedComment(btc1, "seed-user-3", "차트보는사람", "이평선 지지 확인하고 들어가는 게 안전할 듯", 90 * MIN);

  const btc2 = ds._seedPost(
    "KRW-BTC",
    "seed-user-6",
    "반감기 이후 흐름 정리",
    "과거 사이클과 비교하면 아직 중반부라는 분석이 많습니다. 다만 과거가 미래를 보장하진 않으니 리스크 관리가 우선이에요.",
    "공급 축소 효과가 가격에 반영되는 데는 시차가 있었습니다. 지표를 매주 점검하면서 비중을 조절하려 합니다.",
    26 * HOUR,
    ["seed-user-1", "seed-user-2"],
  );
  ds._seedComment(btc2, "seed-user-7", "롱온리", "정리 감사합니다. 참고할게요", 20 * HOUR);

  ds._seedPost(
    "KRW-BTC",
    "seed-user-8",
    "메이커러",
    "수수료 아끼는 법 있을까요",
    "메이커 주문 위주로 넣으면 체결이 느린데 다들 어떻게 하시나요. 팁 공유 부탁드려요.",
    3 * DAY,
    ["seed-user-3"],
  );

  const eth1 = ds._seedPost(
    "KRW-ETH",
    "seed-user-4",
    "스테이커",
    "스테이킹 수익률 요즘 어떤가요",
    "검증자 물량이 늘면서 수익률이 조금씩 내려가는 느낌입니다. 그래도 장기 보유라면 괜찮다고 봐요.",
    5 * HOUR,
    ["seed-user-1", "seed-user-9"],
  );
  ds._seedComment(eth1, "seed-user-9", "덩어리", "레이어2 쪽도 같이 보면 좋아요", 4 * HOUR);
}

/* ===== 싱글턴(클라이언트 전용) ===== */

let singleton: CommunityDataSource | null = null;

/** 회원 신원은 거래 DataSource 세션에서 단방향으로만 읽는다(§0). 데이터 경로는 독립 */
function currentIdentity(): MemberIdentity | null {
  const s = getDataSource().getMemberSession();
  if (!s) return null;
  return { externalId: s.user.external_id, handle: s.user.display_name, token: s.accessToken };
}

/** access 만료 시 거래 세션 refresh로 신선한 신원을 확보한다. 게시판·거래 세션 수명을 대칭으로 맞춘다 */
async function refreshIdentity(): Promise<MemberIdentity | null> {
  const s = await getDataSource().refresh();
  if (!s) return null;
  return { externalId: s.user.external_id, handle: s.user.display_name, token: s.accessToken };
}

function createCommunityDataSource(): CommunityDataSource {
  if (process.env.NEXT_PUBLIC_DATASOURCE === "http") {
    return new HttpCommunityDataSource({
      apiBase: process.env.NEXT_PUBLIC_API_BASE ?? "",
      identityProvider: currentIdentity,
      refreshIdentity,
    });
  }
  return new SimCommunityDataSource(currentIdentity);
}

export function getCommunityDataSource(): CommunityDataSource {
  if (!singleton) singleton = createCommunityDataSource();
  return singleton;
}
