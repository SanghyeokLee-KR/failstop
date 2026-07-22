-- ============================================================================
-- V7__community_board.sql
-- M3 종목 토론 게시판(게시판_설계.md §3). 코어(매칭·저널·정산·원장)와 격리한다.
-- 데모 동거 구성(단일 PG17)이라 커뮤니티 테이블을 코어 라인의 다음 가용 버전 V7에 얹는다.
-- 게시판 테이블은 원장·저널과 어떤 FK·트리거로도 무관하다. 작성자만 users(id)로 논리 연결한다.
-- 물리 DB 분리 구성으로 승격하면 이 라인은 커뮤니티 전용 Flyway 이력으로 분리한다(설계 §2.3).
-- ============================================================================
SET timezone = 'UTC';

-- 글: 코인(마켓)별 스레드. market_symbol은 markets.symbol 값 참조이나 물리 FK를 두지 않는다
-- (격리 §2.2). 유효 마켓 검증은 앱의 MarketDirectory가 수행하고 미존재는 404로 거른다.
CREATE TABLE posts (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id   UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    market_symbol VARCHAR(24)  NOT NULL,
    author_id     BIGINT       NOT NULL REFERENCES users(id),   -- 회원(M2) 신원. 동거 구성 물리 FK
    author_handle VARCHAR(32)  NOT NULL,                        -- 작성 시점 표시명 denormalize(보장 렌더)
    title         VARCHAR(120) NOT NULL,
    body          VARCHAR(4000) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'VISIBLE'
                  CHECK (status IN ('VISIBLE','HIDDEN','DELETED')),
    like_count    INTEGER      NOT NULL DEFAULT 0 CHECK (like_count >= 0),
    comment_count INTEGER      NOT NULL DEFAULT 0 CHECK (comment_count >= 0),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);
-- 보드별 최신순 커서 페이징. 정렬키(created_at,id)가 불변이라 커서가 안정적이다(설계 §1.2).
CREATE INDEX idx_posts_board_latest ON posts (market_symbol, created_at DESC, id DESC)
    WHERE status = 'VISIBLE';
CREATE INDEX idx_posts_author       ON posts (author_id, created_at DESC);

-- 댓글: 1뎁스 대댓글까지. 깊이 제한은 앱 가드(parent가 대댓글이면 거부).
CREATE TABLE comments (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id       UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    post_id           BIGINT       NOT NULL REFERENCES posts(id),   -- 커뮤니티 내부 FK만 허용
    parent_comment_id BIGINT       REFERENCES comments(id),
    author_id         BIGINT       NOT NULL REFERENCES users(id),
    author_handle     VARCHAR(32)  NOT NULL,
    body              VARCHAR(1000) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'VISIBLE'
                      CHECK (status IN ('VISIBLE','HIDDEN','DELETED')),
    like_count        INTEGER      NOT NULL DEFAULT 0 CHECK (like_count >= 0),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ
);
-- 목록·집계 동시 제외(설계 §1.3)라 조회 술어와 인덱스 술어를 status = 'VISIBLE'로 일치시킨다.
CREATE INDEX idx_comments_post ON comments (post_id, created_at, id) WHERE status = 'VISIBLE';

-- 좋아요: 회원·대상 1:1 토글. PK 유니크로 중복을 물리 차단해 자연 멱등.
CREATE TABLE post_likes (
    post_id    BIGINT      NOT NULL REFERENCES posts(id),
    member_id  BIGINT      NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, member_id)
);
CREATE TABLE comment_likes (
    comment_id BIGINT      NOT NULL REFERENCES comments(id),
    member_id  BIGINT      NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, member_id)
);

-- 인기순 페이징용 순위 스냅샷: 가변 like_count를 커서 축에서 분리(설계 §1.2). 세대 단위 불변.
CREATE TABLE post_rank_snapshot (
    market_symbol VARCHAR(24) NOT NULL,
    post_id       BIGINT      NOT NULL REFERENCES posts(id),
    rank_score    INTEGER     NOT NULL,
    generation    BIGINT      NOT NULL,
    snapshot_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (market_symbol, post_id)
);
CREATE INDEX idx_rank_snapshot_board
    ON post_rank_snapshot (market_symbol, generation, rank_score DESC, post_id DESC);

-- 신고: 대상별 1인 1회. target_id는 타입별 posts.id 또는 comments.id 논리 참조.
CREATE TABLE reports (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type VARCHAR(8)  NOT NULL CHECK (target_type IN ('POST','COMMENT')),
    target_id   BIGINT      NOT NULL,
    reporter_id BIGINT      NOT NULL REFERENCES users(id),
    reason      VARCHAR(32) NOT NULL CHECK (reason IN ('SPAM','ABUSE','ILLEGAL','ETC')),
    detail      VARCHAR(500),
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING','REVIEWED','ACTIONED','DISMISSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (target_type, target_id, reporter_id)
);
CREATE INDEX idx_reports_pending ON reports (status, created_at) WHERE status = 'PENDING';

-- 모더레이션 로그: 관리 평면(MEMBER_MOD) 숨김·복구·삭제·신고 처리 이력. operator_id는
-- admin_operators.operator_id 값 참조(관리 평면 소관, 커뮤니티 물리 FK 없음).
CREATE TABLE community_moderation_log (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type VARCHAR(8)  NOT NULL CHECK (target_type IN ('POST','COMMENT')),
    target_id   BIGINT      NOT NULL,
    action      VARCHAR(16) NOT NULL CHECK (action IN ('HIDE','UNHIDE','DELETE')),
    operator_id VARCHAR(64) NOT NULL,
    report_id   BIGINT      REFERENCES reports(id),
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_moderation_target ON community_moderation_log (target_type, target_id, created_at);

-- 권한 경계: app_role은 게시판 테이블에 SELECT·INSERT·UPDATE를 갖는다. 좋아요 토글은 물리 삭제라
-- post_likes·comment_likes에만 DELETE를 더한다. 글·댓글은 soft delete뿐이라 DELETE를 주지 않는다.
-- 원장·잔고 직접 DML 경계(V1 §7)는 그대로다. 빈 PG17 단독 적용성을 위해 롤 존재 검사로 감싼다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT, INSERT, UPDATE
            ON posts, comments, post_likes, comment_likes,
               post_rank_snapshot, reports, community_moderation_log TO app_role;
        GRANT DELETE ON post_likes, comment_likes TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT
            ON posts, comments, post_likes, comment_likes,
               post_rank_snapshot, reports, community_moderation_log TO harness_role;
    END IF;
END $$;
