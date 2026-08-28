package kr.exchange.api.community;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.market.MarketDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 게시판 도메인(게시판_설계.md §1·§3·§4). 코어 원장·저널·엔진과 무관한 커뮤니티 DB 내부 연산만
 * 수행한다. 작성자 신원은 회원 user_id(FK), 표시명은 작성 시점 denormalize다. 카운트(like·comment)는
 * 같은 트랜잭션에서 갱신한다. 파라미터 바인딩만 쓰고 본문은 응답 렌더 시 TextGuard로 이스케이프한다.
 */
public class CommunityService {

    private static final int TITLE_MAX = 120;
    private static final int BODY_MAX = 4000;
    private static final int COMMENT_MAX = 1000;
    /** 신고 큐 한 줄 폭 예산. 제목 최대치(120)와 같게 둬 게시글은 잘리지 않는다 */
    private static final int EXCERPT_MAX = 120;
    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 50;
    private static final int IDEMPOTENCY_CACHE_MAX = 4096;

    private static final String DELETED_BODY = "삭제된 글입니다.";
    private static final String HIDDEN_BODY = "숨겨진 글입니다.";

    private static final List<String> REPORT_REASONS = List.of("SPAM", "ABUSE", "ILLEGAL", "ETC");

    public record Page(List<Map<String, Object>> rows, String nextCursor) {
    }

    public record Created(String externalId, boolean replay) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MarketDirectory markets;
    private final CommunityRankService ranks;
    private final int autoHideThreshold;

    // 작성 멱등(설계 §4.2 권장). 데모 단일 프로세스 범위의 best-effort 재시도 중복 차단. 물리 DB
    // 분리 승격 시 idempotency_keys 유형 테이블로 대체한다(다중 노드·재기동 지속성).
    private final ConcurrentHashMap<String, String> idempotency = new ConcurrentHashMap<>();

    public CommunityService(JdbcClient jdbc, TransactionTemplate tx, MarketDirectory markets,
                            CommunityRankService ranks, int autoHideThreshold) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.markets = markets;
        this.ranks = ranks;
        this.autoHideThreshold = autoHideThreshold;
    }

    // ------------------------------------------------------------------
    // 글 조회(공개)
    // ------------------------------------------------------------------

    public Page listPosts(String market, String sort, String cursor, Integer count, Long viewerId) {
        requireMarket(market);
        int limit = clampCount(count);
        if ("popular".equals(sort)) {
            return listPopular(market, cursor, limit, viewerId);
        }
        if (sort != null && !"latest".equals(sort)) {
            throw new ApiException(400, "invalid_parameter", "sort는 latest 또는 popular입니다.");
        }
        return listLatest(market, cursor, limit, viewerId);
    }

    private Page listLatest(String market, String cursor, int limit, Long viewerId) {
        CommunityCursors.Latest c = cursor == null ? null : CommunityCursors.decodeLatest(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, external_id::text AS external_id, market_symbol, title,
                       author_id, author_handle, like_count, comment_count, created_at, updated_at
                FROM posts
                WHERE market_symbol = ? AND status = 'VISIBLE'
                """);
        if (c != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param(market);
        if (c != null) {
            spec = spec.param(c.createdAt()).param(c.id());
        }
        List<PostRow> rows = spec.param(limit).query(CommunityService::mapPostRow).list();
        List<Map<String, Object>> data = rows.stream().map(r -> summaryView(r, viewerId)).toList();
        String next = null;
        if (rows.size() == limit) {
            PostRow last = rows.get(rows.size() - 1);
            next = CommunityCursors.encodeLatest(last.createdAt(), last.id());
        }
        return new Page(data, next);
    }

    private Page listPopular(String market, String cursor, int limit, Long viewerId) {
        Long generation;
        CommunityCursors.Popular c = null;
        if (cursor != null) {
            c = CommunityCursors.decodePopular(cursor);
            generation = c.generation();
        } else {
            generation = jdbc.sql("SELECT max(generation) FROM post_rank_snapshot WHERE market_symbol = ?")
                    .param(market).query(Long.class).optional().orElse(null);
            if (generation == null) {
                // 첫 인기순 요청은 스냅샷을 지연 산출한다. 이후 요청은 커서에 세대를 고정해 재사용한다.
                generation = ranks.rebuild(market);
            }
        }
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.external_id::text AS external_id, p.market_symbol, p.title,
                       p.author_id, p.author_handle, p.like_count, p.comment_count,
                       p.created_at, p.updated_at, s.rank_score
                FROM post_rank_snapshot s
                JOIN posts p ON p.id = s.post_id
                WHERE s.market_symbol = ? AND s.generation = ? AND p.status = 'VISIBLE'
                """);
        if (c != null) {
            sql.append(" AND (s.rank_score, s.post_id) < (?, ?)");
        }
        sql.append(" ORDER BY s.rank_score DESC, s.post_id DESC LIMIT ?");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param(market).param(generation);
        if (c != null) {
            spec = spec.param(c.rankScore()).param(c.postId());
        }
        List<PostRow> rows = spec.param(limit).query(CommunityService::mapPostRow).list();
        List<Map<String, Object>> data = rows.stream().map(r -> summaryView(r, viewerId)).toList();
        String next = null;
        if (rows.size() == limit) {
            PostRow last = rows.get(rows.size() - 1);
            next = CommunityCursors.encodePopular(generation, last.rankScore(), last.id());
        }
        return new Page(data, next);
    }

    public Map<String, Object> getPost(String externalId, Long viewerId) {
        requireUuid(externalId, "존재하지 않는 글입니다.");
        PostRow row = jdbc.sql("""
                        SELECT id, external_id::text AS external_id, market_symbol, title, body,
                               author_id, author_handle, status, like_count, comment_count,
                               created_at, updated_at
                        FROM posts WHERE external_id = ?::uuid
                        """)
                .param(externalId)
                .query(CommunityService::mapPostDetail)
                .optional()
                .orElseThrow(() -> notFound("존재하지 않는 글입니다."));
        return detailView(row, viewerId);
    }

    // ------------------------------------------------------------------
    // 글 작성·수정·삭제(회원)
    // ------------------------------------------------------------------

    public Created createPost(long userId, String market, String title, String body,
                              String idempotencyKey) {
        requireMarket(market);
        String cleanTitle = requireText(title, TITLE_MAX, "제목");
        String cleanBody = requireText(body, BODY_MAX, "본문");
        String cacheKey = idempotencyKey == null ? null : userId + ":" + idempotencyKey;
        if (cacheKey != null) {
            String existing = idempotency.get(cacheKey);
            if (existing != null) {
                return new Created(existing, true);
            }
        }
        String handle = handleOf(userId);
        String externalId = jdbc.sql("""
                        INSERT INTO posts (market_symbol, author_id, author_handle, title, body)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING external_id::text
                        """)
                .params(market, userId, handle, cleanTitle, cleanBody)
                .query(String.class)
                .single();
        rememberIdempotency(cacheKey, externalId);
        return new Created(externalId, false);
    }

    public Map<String, Object> updatePost(long userId, String externalId, String title, String body) {
        return tx.execute(status -> {
            PostRef ref = loadPostRef(externalId);
            requireOwner(ref.authorId(), userId);
            if (!"VISIBLE".equals(ref.status())) {
                throw notFound("수정할 수 없는 글입니다.");
            }
            String cleanTitle = requireText(title, TITLE_MAX, "제목");
            String cleanBody = requireText(body, BODY_MAX, "본문");
            jdbc.sql("UPDATE posts SET title = ?, body = ?, updated_at = now() WHERE id = ?")
                    .params(cleanTitle, cleanBody, ref.id())
                    .update();
            return getPost(externalId, userId);
        });
    }

    public void deletePost(long userId, String externalId) {
        tx.executeWithoutResult(status -> {
            PostRef ref = loadPostRef(externalId);
            requireOwner(ref.authorId(), userId);
            if ("DELETED".equals(ref.status())) {
                throw notFound("이미 삭제된 글입니다.");
            }
            jdbc.sql("UPDATE posts SET status = 'DELETED', deleted_at = now() WHERE id = ?")
                    .param(ref.id())
                    .update();
        });
    }

    // ------------------------------------------------------------------
    // 댓글(회원)
    // ------------------------------------------------------------------

    public Page listComments(String postExternalId, String cursor, Integer count, Long viewerId) {
        PostRef post = loadPostRef(postExternalId);
        int limit = clampCount(count);
        CommunityCursors.Latest c = cursor == null ? null : CommunityCursors.decodeLatest(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, external_id::text AS external_id, parent_comment_id, body,
                       author_id, author_handle, status, like_count, created_at
                FROM comments
                WHERE post_id = ? AND status = 'VISIBLE'
                """);
        if (c != null) {
            sql.append(" AND (created_at, id) > (?, ?)");
        }
        sql.append(" ORDER BY created_at ASC, id ASC LIMIT ?");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param(post.id());
        if (c != null) {
            spec = spec.param(c.createdAt()).param(c.id());
        }
        List<CommentRow> rows = spec.param(limit).query(CommunityService::mapCommentRow).list();
        List<Map<String, Object>> data = rows.stream().map(r -> commentView(r, viewerId)).toList();
        String next = null;
        if (rows.size() == limit) {
            CommentRow last = rows.get(rows.size() - 1);
            next = CommunityCursors.encodeLatest(last.createdAt(), last.id());
        }
        return new Page(data, next);
    }

    public Created createComment(long userId, String postExternalId, String body,
                                 String parentExternalId) {
        String cleanBody = requireText(body, COMMENT_MAX, "댓글");
        String handle = handleOf(userId);
        return tx.execute(status -> {
            PostRef post = loadPostRef(postExternalId);
            if (!"VISIBLE".equals(post.status())) {
                throw notFound("댓글을 달 수 없는 글입니다.");
            }
            Long parentId = null;
            if (parentExternalId != null && !parentExternalId.isBlank()) {
                requireUuid(parentExternalId, "존재하지 않는 부모 댓글입니다.");
                CommentRef parent = jdbc.sql("""
                                SELECT id, post_id, author_id, status, parent_comment_id
                                FROM comments WHERE external_id = ?::uuid
                                """)
                        .param(parentExternalId)
                        .query(CommunityService::mapCommentRef)
                        .optional()
                        .orElseThrow(() -> notFound("존재하지 않는 부모 댓글입니다."));
                if (parent.postId() != post.id() || "DELETED".equals(parent.status())) {
                    throw notFound("존재하지 않는 부모 댓글입니다.");
                }
                if (parent.parentCommentId() != null) {
                    // 1뎁스 제한(설계 §1.1). 대댓글에 다시 대댓글을 달 수 없다.
                    throw new ApiException(400, "invalid_parameter", "대댓글은 1단계까지만 가능합니다.");
                }
                parentId = parent.id();
            }
            String externalId = jdbc.sql("""
                            INSERT INTO comments (post_id, parent_comment_id, author_id, author_handle, body)
                            VALUES (?, ?, ?, ?, ?)
                            RETURNING external_id::text
                            """)
                    .params(post.id(), parentId, userId, handle, cleanBody)
                    .query(String.class)
                    .single();
            jdbc.sql("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?")
                    .param(post.id())
                    .update();
            return new Created(externalId, false);
        });
    }

    public void deleteComment(long userId, String externalId) {
        tx.executeWithoutResult(status -> {
            // FOR UPDATE로 행을 잠가 동시 삭제·자동숨김·모더레이션과 직렬화한다. 락 없이 읽은 stale status로
            // 감산하면 두 경로가 각자 감산해 comment_count가 이중으로 줄어든다(감사 C).
            CommentRef ref = lockCommentRef(externalId);
            requireOwner(ref.authorId(), userId);
            if ("DELETED".equals(ref.status())) {
                throw notFound("이미 삭제된 댓글입니다.");
            }
            boolean wasVisible = "VISIBLE".equals(ref.status());
            jdbc.sql("UPDATE comments SET status = 'DELETED', deleted_at = now() WHERE id = ?")
                    .param(ref.id())
                    .update();
            // VISIBLE 댓글만 집계에 잡혀 있으므로 그 전이에서만 감산한다. HIDDEN은 숨김 시점에 이미
            // 빠졌으므로 재감산하지 않는다(§1.3).
            if (wasVisible) {
                jdbc.sql("UPDATE posts SET comment_count = greatest(comment_count - 1, 0) WHERE id = ?")
                        .param(ref.postId())
                        .update();
            }
        });
    }

    // ------------------------------------------------------------------
    // 좋아요 토글(회원). PK 유니크로 자연 멱등.
    // ------------------------------------------------------------------

    public Map<String, Object> togglePostLike(long userId, String externalId, boolean on) {
        return tx.execute(status -> {
            PostRef ref = loadPostRef(externalId);
            if (!"VISIBLE".equals(ref.status())) {
                throw notFound("좋아요할 수 없는 글입니다.");
            }
            if (on) {
                int inserted = jdbc.sql("""
                                INSERT INTO post_likes (post_id, member_id) VALUES (?, ?)
                                ON CONFLICT (post_id, member_id) DO NOTHING
                                """)
                        .params(ref.id(), userId).update();
                if (inserted == 1) {
                    jdbc.sql("UPDATE posts SET like_count = like_count + 1 WHERE id = ?")
                            .param(ref.id()).update();
                }
            } else {
                int deleted = jdbc.sql("DELETE FROM post_likes WHERE post_id = ? AND member_id = ?")
                        .params(ref.id(), userId).update();
                if (deleted == 1) {
                    jdbc.sql("UPDATE posts SET like_count = greatest(like_count - 1, 0) WHERE id = ?")
                            .param(ref.id()).update();
                }
            }
            int likeCount = jdbc.sql("SELECT like_count FROM posts WHERE id = ?")
                    .param(ref.id()).query(Integer.class).single();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schema_version", ApiFormats.SCHEMA_VERSION);
            body.put("liked", on);
            body.put("like_count", likeCount);
            return body;
        });
    }

    public Map<String, Object> toggleCommentLike(long userId, String externalId, boolean on) {
        return tx.execute(status -> {
            CommentRef ref = loadCommentRef(externalId);
            if (!"VISIBLE".equals(ref.status())) {
                throw notFound("좋아요할 수 없는 댓글입니다.");
            }
            if (on) {
                int inserted = jdbc.sql("""
                                INSERT INTO comment_likes (comment_id, member_id) VALUES (?, ?)
                                ON CONFLICT (comment_id, member_id) DO NOTHING
                                """)
                        .params(ref.id(), userId).update();
                if (inserted == 1) {
                    jdbc.sql("UPDATE comments SET like_count = like_count + 1 WHERE id = ?")
                            .param(ref.id()).update();
                }
            } else {
                int deleted = jdbc.sql("DELETE FROM comment_likes WHERE comment_id = ? AND member_id = ?")
                        .params(ref.id(), userId).update();
                if (deleted == 1) {
                    jdbc.sql("UPDATE comments SET like_count = greatest(like_count - 1, 0) WHERE id = ?")
                            .param(ref.id()).update();
                }
            }
            int likeCount = jdbc.sql("SELECT like_count FROM comments WHERE id = ?")
                    .param(ref.id()).query(Integer.class).single();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schema_version", ApiFormats.SCHEMA_VERSION);
            body.put("liked", on);
            body.put("like_count", likeCount);
            return body;
        });
    }

    // ------------------------------------------------------------------
    // 신고(회원). 대상별 1인 1회. 임계 초과 시 자동 숨김.
    // ------------------------------------------------------------------

    public Map<String, Object> report(long userId, String targetType, String targetExternalId,
                                      String reason, String detail) {
        String type = targetType == null ? "" : targetType.toUpperCase();
        if (!"POST".equals(type) && !"COMMENT".equals(type)) {
            throw new ApiException(400, "invalid_parameter", "target_type은 POST 또는 COMMENT입니다.");
        }
        String reasonCode = reason == null ? "" : reason.toUpperCase();
        if (!REPORT_REASONS.contains(reasonCode)) {
            throw new ApiException(400, "invalid_parameter", "지원하지 않는 신고 사유입니다.");
        }
        String cleanDetail = detail == null ? null : clip(detail, 500);
        long targetId = resolveTargetId(type, targetExternalId);
        return tx.execute(status -> {
            try {
                jdbc.sql("""
                                INSERT INTO reports (target_type, target_id, reporter_id, reason, detail)
                                VALUES (?, ?, ?, ?, ?)
                                """)
                        .params(type, targetId, userId, reasonCode, cleanDetail)
                        .update();
            } catch (DuplicateKeyException e) {
                throw new ApiException(409, "duplicate_report", "이미 신고한 대상입니다.");
            }
            long pending = jdbc.sql("""
                            SELECT count(*) FROM reports
                            WHERE target_type = ? AND target_id = ? AND status = 'PENDING'
                            """)
                    .params(type, targetId).query(Long.class).single();
            boolean autoHidden = false;
            if (pending >= autoHideThreshold) {
                autoHidden = autoHide(type, targetId);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schema_version", ApiFormats.SCHEMA_VERSION);
            body.put("reported", true);
            body.put("auto_hidden", autoHidden);
            return body;
        });
    }

    private boolean autoHide(String type, long targetId) {
        String table = "POST".equals(type) ? "posts" : "comments";
        int updated = jdbc.sql("UPDATE " + table + " SET status = 'HIDDEN' WHERE id = ? AND status = 'VISIBLE'")
                .param(targetId).update();
        if (updated == 1) {
            if ("COMMENT".equals(type)) {
                // 자동 숨김된 댓글은 목록·집계에서 빠지므로 부모 글 comment_count를 같은 트랜잭션에서 감산한다(§1.3).
                jdbc.sql("UPDATE posts SET comment_count = greatest(comment_count - 1, 0) "
                                + "WHERE id = (SELECT post_id FROM comments WHERE id = ?)")
                        .param(targetId).update();
            }
            jdbc.sql("""
                            INSERT INTO community_moderation_log
                                (target_type, target_id, action, operator_id, note)
                            VALUES (?, ?, 'HIDE', 'system', '신고 임계 자동 숨김')
                            """)
                    .params(type, targetId).update();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 모더레이션(관리 평면 MEMBER_MOD). 관리자 조작은 게시판 도메인 밖(원장·저널)에 닿지 않는다.
    // ------------------------------------------------------------------

    public void moderatePost(String operatorId, String externalId, String action, String note) {
        tx.executeWithoutResult(status -> {
            PostRef ref = lockPostRef(externalId);
            applyModeration("POST", "posts", ref.id(), 0, ref.status(), operatorId, action, note);
        });
    }

    public void moderateComment(String operatorId, String externalId, String action, String note) {
        tx.executeWithoutResult(status -> {
            CommentRef ref = lockCommentRef(externalId);
            applyModeration("COMMENT", "comments", ref.id(), ref.postId(), ref.status(),
                    operatorId, action, note);
        });
    }

    /**
     * 대상 행을 FOR UPDATE로 잠근 caller 트랜잭션 안에서만 호출한다. currentStatus는 락 이후 재조회한 값이라
     * 회원 자가삭제·자동숨김과 교차해도 stale하지 않다(감사 C·D). 상태 판정·전이·감산·감사를 한 트랜잭션에 묶는다.
     */
    private void applyModeration(String type, String table, long targetId, long postId,
                                 String currentStatus, String operatorId, String action, String note) {
        String act = action == null ? "" : action.toUpperCase();
        String target = switch (act) {
            case "HIDE" -> "HIDDEN";
            case "UNHIDE" -> "VISIBLE";
            case "DELETE" -> "DELETED";
            default -> throw new ApiException(400, "invalid_parameter",
                    "action은 HIDE, UNHIDE, DELETE입니다.");
        };
        // DELETED는 종료 상태다. 회원 자가삭제 대상에 HIDE→UNHIDE 2단계로 본문 마스킹을 푸는 부활을 막기 위해
        // 모든 모더레이션 전이를 거부한다(감사 D).
        if ("DELETED".equals(currentStatus)) {
            throw new ApiException(400, "invalid_parameter", "이미 삭제된 대상은 모더레이션 불가");
        }
        // UNHIDE는 직전 HIDDEN만 복구한다.
        if ("UNHIDE".equals(act) && !"HIDDEN".equals(currentStatus)) {
            throw new ApiException(400, "invalid_parameter", "숨김 상태 대상만 복구 가능");
        }
        String setDeleted = "DELETE".equals(act) ? ", deleted_at = now()" : "";
        jdbc.sql("UPDATE " + table + " SET status = ?" + setDeleted + " WHERE id = ?")
                .params(target, targetId)
                .update();
        // 댓글 가시성 전이는 부모 글 comment_count를 같은 트랜잭션에서 맞춘다(§1.3 목록·집계 동시 제외).
        if ("COMMENT".equals(type)) {
            int delta = ("VISIBLE".equals(target) ? 1 : 0)
                    - ("VISIBLE".equals(currentStatus) ? 1 : 0);
            if (delta != 0) {
                jdbc.sql("UPDATE posts SET comment_count = greatest(comment_count + ?, 0) WHERE id = ?")
                        .params(delta, postId)
                        .update();
            }
        }
        jdbc.sql("""
                        INSERT INTO community_moderation_log
                            (target_type, target_id, action, operator_id, note)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(type, targetId, act, operatorId, note == null ? null : clip(note, 500))
                .update();
    }

    /**
     * 신고 큐 한 쪽. 오래된 신고부터 처리한다.
     *
     * LIMIT 200이 SQL에 박혀 있어 파라미터로도 넘길 수 없었다. PENDING만 보므로 처리하면
     * 빠지지만, 신고가 몰리면 201번째부터 운영자에게 보이지 않고 잘렸다는 표시도 없었다.
     * status=REVIEWED 등 처리된 상태 조회는 영구 누적이다.
     */
    public List<CursorPages.Row<Map<String, Object>>> listReports(String status,
                                                                  Cursors.Cursor cursor, int limit) {
        String filter = status == null ? "PENDING" : status.toUpperCase();
        // 대상 요약을 함께 싣는다. 신고 사유만으로는 무엇을 숨길지 판단할 수 없고, 처리 액션이
        // external_id를 받는데 reports.target_id는 내부 BIGINT라 운영자가 그 값으로는 아무것도
        // 못 부른다(게시판_설계 §5 "프론트는 내부 수치 id를 알지 못한다").
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.target_type, r.target_id, r.reporter_id, r.reason, r.detail,
                       r.status, r.created_at,
                       COALESCE(p.external_id::text, c.external_id::text) AS target_external_id,
                       COALESCE(p.status, c.status)                       AS target_status,
                       COALESCE(p.title, c.body)                          AS target_excerpt,
                       COALESCE(p.author_handle, c.author_handle)         AS target_author
                FROM reports r
                LEFT JOIN posts    p ON r.target_type = 'POST'    AND p.id = r.target_id
                LEFT JOIN comments c ON r.target_type = 'COMMENT' AND c.id = r.target_id
                WHERE r.status = ?
                """);
        List<Object> params = new ArrayList<>(List.of(filter));
        if (cursor != null) {
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT created_at FROM reports WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject("created_at", OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            // 큐는 오름차순이라 경계 비교도 반대 방향이다
            sql.append(" AND (r.created_at, r.id) > (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        sql.append(" ORDER BY r.created_at ASC, r.id ASC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("report_id", rs.getLong("id"));
                    row.put("target_type", rs.getString("target_type"));
                    row.put("target_id", rs.getLong("target_id"));
                    row.put("reporter_id", rs.getLong("reporter_id"));
                    row.put("reason", rs.getString("reason"));
                    row.put("detail", TextGuard.escape(rs.getString("detail")));
                    row.put("status", rs.getString("status"));
                    // 대상이 하드 삭제되는 경로는 없으나 조인 실패를 null로 정직하게 흘린다
                    row.put("target_external_id", rs.getString("target_external_id"));
                    row.put("target_status", rs.getString("target_status"));
                    row.put("target_excerpt", excerpt(rs.getString("target_excerpt")));
                    row.put("target_author", rs.getString("target_author"));
                    row.put("created_at", ApiFormats.time(rs.getObject("created_at", OffsetDateTime.class)));
                    return new CursorPages.Row<>(row,
                            rs.getObject("created_at", OffsetDateTime.class),
                            String.valueOf(rs.getLong("id")));
                })
                .list();
    }

    /** 신고 큐 한 줄에 들어갈 길이로 자른다. 본문 전체는 운영 판단에 필요 없고 행만 밀어낸다 */
    private static String excerpt(String raw) {
        if (raw == null) {
            return null;
        }
        String flat = TextGuard.escape(raw).replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_MAX ? flat : flat.substring(0, EXCERPT_MAX) + "...";
    }

    public void resolveReport(String operatorId, long reportId, String resolution, String note) {
        String res = resolution == null ? "" : resolution.toUpperCase();
        if (!List.of("REVIEWED", "ACTIONED", "DISMISSED").contains(res)) {
            throw new ApiException(400, "invalid_parameter",
                    "resolution은 REVIEWED, ACTIONED, DISMISSED입니다.");
        }
        int updated = jdbc.sql("UPDATE reports SET status = ? WHERE id = ? AND status = 'PENDING'")
                .params(res, reportId)
                .update();
        if (updated == 0) {
            throw notFound("처리 대기 중인 신고가 아닙니다.");
        }
    }

    // ------------------------------------------------------------------
    // 내부 헬퍼
    // ------------------------------------------------------------------

    private long resolveTargetId(String type, String externalId) {
        if ("POST".equals(type)) {
            return loadPostRef(externalId).id();
        }
        return loadCommentRef(externalId).id();
    }

    private PostRef loadPostRef(String externalId) {
        requireUuid(externalId, "존재하지 않는 글입니다.");
        return jdbc.sql("SELECT id, author_id, status FROM posts WHERE external_id = ?::uuid")
                .param(externalId)
                .query((rs, n) -> new PostRef(rs.getLong("id"), rs.getLong("author_id"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> notFound("존재하지 않는 글입니다."));
    }

    // 상태 전이(삭제·모더레이션) 경로 전용. 대상 행을 FOR UPDATE로 잠가 read-modify-write를 한 트랜잭션·행잠금에
    // 모아 stale status·이중 감산을 차단한다(감사 C·D). caller 트랜잭션 안에서만 호출한다.
    private PostRef lockPostRef(String externalId) {
        requireUuid(externalId, "존재하지 않는 글입니다.");
        return jdbc.sql("SELECT id, author_id, status FROM posts WHERE external_id = ?::uuid FOR UPDATE")
                .param(externalId)
                .query((rs, n) -> new PostRef(rs.getLong("id"), rs.getLong("author_id"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> notFound("존재하지 않는 글입니다."));
    }

    private CommentRef loadCommentRef(String externalId) {
        requireUuid(externalId, "존재하지 않는 댓글입니다.");
        return jdbc.sql("""
                        SELECT id, post_id, author_id, status, parent_comment_id
                        FROM comments WHERE external_id = ?::uuid
                        """)
                .param(externalId)
                .query(CommunityService::mapCommentRef)
                .optional()
                .orElseThrow(() -> notFound("존재하지 않는 댓글입니다."));
    }

    private CommentRef lockCommentRef(String externalId) {
        requireUuid(externalId, "존재하지 않는 댓글입니다.");
        return jdbc.sql("""
                        SELECT id, post_id, author_id, status, parent_comment_id
                        FROM comments WHERE external_id = ?::uuid FOR UPDATE
                        """)
                .param(externalId)
                .query(CommunityService::mapCommentRef)
                .optional()
                .orElseThrow(() -> notFound("존재하지 않는 댓글입니다."));
    }

    private String handleOf(long userId) {
        Optional<String> handle = jdbc.sql("SELECT display_name FROM user_profiles WHERE user_id = ?")
                .param(userId).query(String.class).optional();
        return handle.orElse("회원" + userId);
    }

    private void requireMarket(String market) {
        // 유효 마켓은 로컬 카탈로그(MarketDirectory)로 검증한다. 미존재는 404로 거른다(설계 §1.1).
        try {
            markets.bySymbol(market);
        } catch (ApiException e) {
            throw notFound("존재하지 않는 마켓입니다: " + market);
        }
    }

    private void rememberIdempotency(String cacheKey, String externalId) {
        if (cacheKey == null) {
            return;
        }
        if (idempotency.size() >= IDEMPOTENCY_CACHE_MAX) {
            idempotency.clear();
        }
        idempotency.putIfAbsent(cacheKey, externalId);
    }

    private static void requireOwner(long authorId, long userId) {
        if (authorId != userId) {
            throw new ApiException(403, "out_of_scope", "작성자 본인만 가능");
        }
    }

    private static String requireText(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "invalid_parameter", label + " 입력 필요");
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new ApiException(400, "invalid_parameter", label + "은(는) 최대 " + max + "자");
        }
        return trimmed;
    }

    private static int clampCount(Integer count) {
        if (count == null) {
            return DEFAULT_COUNT;
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new ApiException(400, "invalid_parameter", "count는 1~" + MAX_COUNT + "이어야 합니다.");
        }
        return count;
    }

    private static String clip(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        // UTF-16 code unit 절단이 서로게이트 쌍을 반으로 가르지 않게 경계를 앞당긴다.
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    // external_id를 ?::uuid 캐스트 전에 형식 검증한다. 비 UUID 입력이 캐치올 500·매 건 log.error로 새는
    // 것을 막고 OrderController 패턴대로 404로 정규화한다(무인증 GET 소음 제거).
    private static void requireUuid(String externalId, String message) {
        if (externalId == null) {
            throw notFound(message);
        }
        try {
            UUID.fromString(externalId);
        } catch (IllegalArgumentException e) {
            throw notFound(message);
        }
    }

    // ------------------------------------------------------------------
    // 행 매핑·뷰
    // ------------------------------------------------------------------

    private static PostRow mapPostRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int rankScore = hasColumn(rs, "rank_score") ? rs.getInt("rank_score") : 0;
        return new PostRow(rs.getLong("id"), rs.getString("external_id"), rs.getString("market_symbol"),
                rs.getString("title"), null, rs.getLong("author_id"), rs.getString("author_handle"),
                "VISIBLE", rs.getInt("like_count"), rs.getInt("comment_count"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rankScore);
    }

    private static PostRow mapPostDetail(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PostRow(rs.getLong("id"), rs.getString("external_id"), rs.getString("market_symbol"),
                rs.getString("title"), rs.getString("body"), rs.getLong("author_id"),
                rs.getString("author_handle"), rs.getString("status"), rs.getInt("like_count"),
                rs.getInt("comment_count"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), 0);
    }

    private static CommentRow mapCommentRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long parent = rs.getLong("parent_comment_id");
        boolean hasParent = !rs.wasNull();
        return new CommentRow(rs.getLong("id"), rs.getString("external_id"),
                hasParent ? parent : null, rs.getString("body"), rs.getLong("author_id"),
                rs.getString("author_handle"), rs.getString("status"), rs.getInt("like_count"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static CommentRef mapCommentRef(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long parent = rs.getLong("parent_comment_id");
        boolean hasParent = !rs.wasNull();
        return new CommentRef(rs.getLong("id"), rs.getLong("post_id"), rs.getLong("author_id"),
                rs.getString("status"), hasParent ? parent : null);
    }

    private static boolean hasColumn(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(md.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> summaryView(PostRow r, Long viewerId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("post_id", r.externalId());
        row.put("market", r.marketSymbol());
        row.put("title", TextGuard.escape(r.title()));
        row.put("author_handle", TextGuard.escape(r.authorHandle()));
        row.put("mine", isMine(r.authorId(), viewerId));
        row.put("like_count", r.likeCount());
        row.put("comment_count", r.commentCount());
        row.put("created_at", ApiFormats.time(r.createdAt()));
        row.put("updated_at", ApiFormats.time(r.updatedAt()));
        return row;
    }

    private static Map<String, Object> detailView(PostRow r, Long viewerId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema_version", ApiFormats.SCHEMA_VERSION);
        row.put("post_id", r.externalId());
        row.put("market", r.marketSymbol());
        row.put("title", TextGuard.escape(maskTitle(r.status(), r.title())));
        row.put("body", TextGuard.escape(maskBody(r.status(), r.body())));
        row.put("author_handle", TextGuard.escape(r.authorHandle()));
        row.put("mine", isMine(r.authorId(), viewerId));
        row.put("status", r.status());
        row.put("like_count", r.likeCount());
        row.put("comment_count", r.commentCount());
        row.put("created_at", ApiFormats.time(r.createdAt()));
        row.put("updated_at", ApiFormats.time(r.updatedAt()));
        return row;
    }

    private static Map<String, Object> commentView(CommentRow r, Long viewerId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("comment_id", r.externalId());
        row.put("parent_comment_id", r.parentCommentId() == null ? null : r.parentCommentId());
        row.put("body", TextGuard.escape(maskBody(r.status(), r.body())));
        row.put("author_handle", TextGuard.escape(r.authorHandle()));
        row.put("mine", isMine(r.authorId(), viewerId));
        row.put("status", r.status());
        row.put("like_count", r.likeCount());
        row.put("created_at", ApiFormats.time(r.createdAt()));
        return row;
    }

    // 소유권은 작성자 내부 id와 요청자 신원의 대조로만 판정한다(handle 무관). 비로그인·무효 토큰은 false.
    private static boolean isMine(long authorId, Long viewerId) {
        return viewerId != null && viewerId == authorId;
    }

    private static String maskBody(String status, String body) {
        if ("DELETED".equals(status)) {
            return DELETED_BODY;
        }
        if ("HIDDEN".equals(status)) {
            return HIDDEN_BODY;
        }
        return body;
    }

    private static String maskTitle(String status, String title) {
        if ("DELETED".equals(status)) {
            return DELETED_BODY;
        }
        if ("HIDDEN".equals(status)) {
            return HIDDEN_BODY;
        }
        return title;
    }

    private record PostRow(long id, String externalId, String marketSymbol, String title, String body,
                           long authorId, String authorHandle, String status, int likeCount,
                           int commentCount, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           int rankScore) {
    }

    private record CommentRow(long id, String externalId, Long parentCommentId, String body,
                              long authorId, String authorHandle, String status, int likeCount,
                              OffsetDateTime createdAt) {
    }

    private record PostRef(long id, long authorId, String status) {
    }

    private record CommentRef(long id, long postId, long authorId, String status, Long parentCommentId) {
    }
}
