package kr.exchange.api.community;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.ClientIp;
import kr.exchange.api.common.SlidingWindowThrottle;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 게시판 공개·회원 API(게시판_설계.md §4.1). 읽기는 무인증, 쓰기는 회원 세션(aud=community)이다.
 * 거래 평면과 경계 분리되어 ApiAuthFilter를 통과하지 않고 CommunityAuthenticator로 자체 인증한다.
 */
@RestController
@RequestMapping("/api/v1/community")
public class CommunityController {

    private static final int IDEMPOTENCY_KEY_MAX = 36;

    private final CommunityService community;
    private final CommunityAuthenticator auth;
    // 게시판 전용 버킷(설계 §4.3). 코어 주문 버킷과 별개라 게시판 폭주가 주문 예산을 갉지 않는다.
    private final SlidingWindowThrottle readThrottle = new SlidingWindowThrottle(300, 10_000, "community_read");
    private final SlidingWindowThrottle writeThrottle = new SlidingWindowThrottle(60, 60_000, "community_write");

    public CommunityController(CommunityService community, CommunityAuthenticator auth) {
        this.community = community;
        this.auth = auth;
    }

    public record PostBody(String title, String body) {
    }

    public record CommentBody(String body, String parent_comment_id) {
    }

    public record ReportBody(String target_type, String target_id, String reason, String detail) {
    }

    // ------------------------------------------------------------------
    // 글
    // ------------------------------------------------------------------

    @GetMapping("/boards/{market}/posts")
    public Map<String, Object> listPosts(@PathVariable String market,
                                         @RequestParam(value = "sort", required = false) String sort,
                                         @RequestParam(value = "cursor", required = false) String cursor,
                                         @RequestParam(value = "count", required = false) Integer count,
                                         HttpServletRequest request) {
        readThrottle.check(ClientIp.of(request));
        CommunityService.Page page = community.listPosts(market, sort, cursor, count, viewer(request));
        return envelope(page);
    }

    @PostMapping("/boards/{market}/posts")
    public ResponseEntity<Map<String, Object>> createPost(
            @PathVariable String market,
            @RequestBody(required = false) PostBody body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        PostBody b = body == null ? new PostBody(null, null) : body;
        CommunityService.Created created = community.createPost(
                userId, market, b.title(), b.body(), validKey(idempotencyKey));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", ApiFormats.SCHEMA_VERSION);
        payload.put("post_id", created.externalId());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(
                created.replay() ? HttpStatus.OK : HttpStatus.CREATED);
        if (created.replay()) {
            builder.header("X-Idempotent-Replay", "true");
        }
        return builder.body(payload);
    }

    @GetMapping("/posts/{externalId}")
    public Map<String, Object> getPost(@PathVariable String externalId, HttpServletRequest request) {
        readThrottle.check(ClientIp.of(request));
        return community.getPost(externalId, viewer(request));
    }

    @PatchMapping("/posts/{externalId}")
    public Map<String, Object> updatePost(@PathVariable String externalId,
                                          @RequestBody(required = false) PostBody body,
                                          HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        PostBody b = body == null ? new PostBody(null, null) : body;
        return community.updatePost(userId, externalId, b.title(), b.body());
    }

    @DeleteMapping("/posts/{externalId}")
    public Map<String, Object> deletePost(@PathVariable String externalId, HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        community.deletePost(userId, externalId);
        return ok();
    }

    // ------------------------------------------------------------------
    // 댓글
    // ------------------------------------------------------------------

    @GetMapping("/posts/{externalId}/comments")
    public Map<String, Object> listComments(@PathVariable String externalId,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "count", required = false) Integer count,
                                            HttpServletRequest request) {
        readThrottle.check(ClientIp.of(request));
        return envelope(community.listComments(externalId, cursor, count, viewer(request)));
    }

    @PostMapping("/posts/{externalId}/comments")
    public ResponseEntity<Map<String, Object>> createComment(
            @PathVariable String externalId,
            @RequestBody(required = false) CommentBody body,
            HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        CommentBody b = body == null ? new CommentBody(null, null) : body;
        CommunityService.Created created = community.createComment(
                userId, externalId, b.body(), b.parent_comment_id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", ApiFormats.SCHEMA_VERSION);
        payload.put("comment_id", created.externalId());
        return ResponseEntity.status(HttpStatus.CREATED).body(payload);
    }

    @DeleteMapping("/comments/{externalId}")
    public Map<String, Object> deleteComment(@PathVariable String externalId,
                                             HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        community.deleteComment(userId, externalId);
        return ok();
    }

    // ------------------------------------------------------------------
    // 좋아요 토글
    // ------------------------------------------------------------------

    @PostMapping("/posts/{externalId}/like")
    public Map<String, Object> likePost(@PathVariable String externalId, HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        return community.togglePostLike(userId, externalId, true);
    }

    @DeleteMapping("/posts/{externalId}/like")
    public Map<String, Object> unlikePost(@PathVariable String externalId, HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        return community.togglePostLike(userId, externalId, false);
    }

    @PostMapping("/comments/{externalId}/like")
    public Map<String, Object> likeComment(@PathVariable String externalId, HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        return community.toggleCommentLike(userId, externalId, true);
    }

    @DeleteMapping("/comments/{externalId}/like")
    public Map<String, Object> unlikeComment(@PathVariable String externalId, HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        return community.toggleCommentLike(userId, externalId, false);
    }

    // ------------------------------------------------------------------
    // 신고
    // ------------------------------------------------------------------

    @PostMapping("/reports")
    public Map<String, Object> report(@RequestBody(required = false) ReportBody body,
                                      HttpServletRequest request) {
        long userId = auth.requireMember(request);
        writeThrottle.check("member:" + userId);
        if (body == null || body.target_id() == null) {
            throw new ApiException(400, "invalid_parameter", "target_type, target_id, reason이 필요합니다.");
        }
        return community.report(userId, body.target_type(), body.target_id(),
                body.reason(), body.detail());
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    // 읽기 경로의 선택 인증 신원. 비로그인·무효 토큰은 null(서버가 mine=false로 산출).
    private Long viewer(HttpServletRequest request) {
        java.util.OptionalLong id = auth.optionalMember(request);
        return id.isPresent() ? id.getAsLong() : null;
    }

    private static String validKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() > IDEMPOTENCY_KEY_MAX || !trimmed.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(400, "invalid_parameter",
                    "Idempotency-Key는 1~36자의 [A-Za-z0-9_-]입니다.");
        }
        return trimmed;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("ok", true);
        return body;
    }

    private static Map<String, Object> envelope(CommunityService.Page page) {
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("next_cursor", page.nextCursor());
        pagination.put("has_more", page.nextCursor() != null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("data", page.rows());
        body.put("pagination", pagination);
        return body;
    }
}
