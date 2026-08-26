"use client";

/**
 * 게시판 목록 뷰. 최신·인기 탭 + 커서 페이징(더 보기) + 글쓰기.
 * 목록 행은 링크라 좋아요 토글을 넣지 않는다(a 내부 button 중첩 회피). 카운트는 표시 전용.
 * 좋아요 토글은 상세 뷰에서 한다. 상태: 로딩·빈·에러 설계(§5 상태 규약).
 */

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import type { CoinMeta, MemberSession } from "@/lib/types";
import type { CommunityPost, PostSort } from "@/lib/community/types";
import { getCommunityDataSource } from "@/lib/community/datasource";
import { mapCommunityError } from "@/lib/community/errors";
import { formatRelativeTime } from "@/lib/community/time";
import { formatCount } from "@/lib/format";
import SegmentedTab from "@/components/SegmentedTab";
import ListRow from "@/components/ListRow";
import Skeleton from "@/components/Skeleton";
import { toast } from "@/components/Toast";
import PostComposer from "@/components/community/PostComposer";
import { CountBadge, PostRail } from "./boardParts";
import styles from "./community.module.css";

interface Props {
  market: string;
  meta: CoinMeta;
  session: MemberSession | null;
}

const SORT_ITEMS: Array<{ value: PostSort; label: string }> = [
  { value: "latest", label: "최신" },
  { value: "popular", label: "인기" },
];

export default function BoardListView({ market, meta, session }: Props) {
  const ds = getCommunityDataSource();
  const router = useRouter();
  const [sort, setSort] = useState<PostSort>("latest");
  const [posts, setPosts] = useState<CommunityPost[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [phase, setPhase] = useState<"loading" | "ready" | "error">("loading");
  const [loadingMore, setLoadingMore] = useState(false);
  const [composing, setComposing] = useState(false);
  const [bodyError, setBodyError] = useState<string | null>(null);
  const [titleError, setTitleError] = useState<string | null>(null);
  // 멱등키는 컴포즈 세션에 고정한다. 실패 시 유지해 재시도가 같은 키로 중복 작성을 막는다
  const postKeyRef = useRef<string>("");
  // 목록 세대. 마켓·정렬 전환(reset)마다 올려, 늦게 도착한 이전 세대 응답이 다른 정렬·마켓
  // 목록 뒤에 append되거나 커서를 이전 스트림 것으로 덮는 오염을 폐기한다
  const loadGen = useRef(0);

  const load = useCallback(
    async (nextSort: PostSort, reset: boolean) => {
      const gen = reset ? ++loadGen.current : loadGen.current;
      try {
        if (reset) setPhase("loading");
        else setLoadingMore(true);
        const page = await ds.listPosts(market, nextSort, reset ? null : cursor, 20);
        if (gen !== loadGen.current) return; // stale 응답 폐기
        setPosts((prev) => (reset ? page.items : [...prev, ...page.items]));
        setCursor(page.next_cursor);
        setHasMore(page.has_more);
        setPhase("ready");
      } catch {
        if (gen !== loadGen.current) return;
        if (reset) setPhase("error");
        else toast("목록을 더 불러오지 못함", "error");
      } finally {
        if (gen === loadGen.current) setLoadingMore(false);
      }
    },
    [ds, market, cursor],
  );

  useEffect(() => {
    void load(sort, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [market, sort]);

  const requireLogin = () => {
    toast("로그인 필요", "info");
    router.push("/auth/login");
  };

  const openCompose = () => {
    if (!session) return requireLogin();
    setTitleError(null);
    setBodyError(null);
    postKeyRef.current = "";
    setComposing(true);
  };

  const submitPost = async (title: string, body: string) => {
    setTitleError(null);
    setBodyError(null);
    if (!postKeyRef.current) postKeyRef.current = crypto.randomUUID();
    try {
      const post = await ds.createPost(market, { title, body, clientKey: postKeyRef.current });
      postKeyRef.current = "";
      toast("글 등록 완료", "success");
      setComposing(false);
      router.push(`/community/${market}?post=${post.external_id}`);
    } catch (e) {
      const m = mapCommunityError(e);
      if (m.needsLogin) requireLogin();
      else if (m.field === "title") setTitleError(m.message);
      else if (m.field === "body") setBodyError(m.message);
      else toast(m.message, "error");
    }
  };

  return (
    <div className={styles.layout}>
      <div className={styles.main}>
        {composing ? (
          <section className="card">
            <h2 className={styles.sectionTitle}>새 글 작성</h2>
            <PostComposer
              submitLabel="등록"
              busyLabel="등록 중"
              onSubmit={submitPost}
              onCancel={() => setComposing(false)}
              titleError={titleError}
              bodyError={bodyError}
            />
          </section>
        ) : (
          <>
            <div className={styles.listToolbar}>
              <SegmentedTab
                items={SORT_ITEMS}
                value={sort}
                onChange={(v) => setSort(v)}
                variant="underline"
                ariaLabel="정렬 방식"
                disabled={phase === "loading" || loadingMore}
              />
              {/* 게이트 어휘를 레일과 통일한다. 활성 글쓰기를 눌러야 로그인 요구가 뜨면
                  버튼이 약속을 어긴다 */}
              <button type="button" className="btn btn--primary" onClick={openCompose}>
                {session ? "글쓰기" : "로그인하고 글쓰기"}
              </button>
            </div>

            {phase === "loading" && (
              <div className={styles.rows}>
                <Skeleton variant="listRow" />
                <Skeleton variant="listRow" />
                <Skeleton variant="listRow" />
              </div>
            )}

            {phase === "error" && (
              <div className="empty-state" role="alert">
                <span className="empty-state__mark" aria-hidden="true" />
                <p className="empty-state__title">글을 불러오지 못함</p>
                <p className="empty-state__desc">잠시 후 다시 시도 필요</p>
                <button type="button" className="btn btn--secondary" onClick={() => void load(sort, true)}>
                  다시 시도
                </button>
              </div>
            )}

            {phase === "ready" && posts.length === 0 && (
              <div className="empty-state">
                <span className="empty-state__mark" aria-hidden="true" />
                <p className="empty-state__title">아직 글 없음</p>
                <button type="button" className="btn btn--primary" onClick={openCompose}>
                  {session ? "글쓰기" : "로그인하고 글쓰기"}
                </button>
              </div>
            )}

            {phase === "ready" && posts.length > 0 && (
              <ul className={styles.rows}>
                {posts.map((p) => (
                  <li key={p.external_id}>
                    <ListRow
                      href={`/community/${market}?post=${p.external_id}`}
                      title={<span className={styles.rowTitle}>{p.title}</span>}
                      sub={
                        <span className={styles.rowSub}>
                          <span className={styles.rowAuthor}>{p.author_handle}</span>
                          <span className={styles.rowDot} aria-hidden="true">
                            ·
                          </span>
                          <span>{formatRelativeTime(p.created_at)}</span>
                        </span>
                      }
                      right={
                        <span className={styles.rowMeta}>
                          <CountBadge kind="like" value={p.like_count} />
                          <CountBadge kind="comment" value={p.comment_count} />
                        </span>
                      }
                      ariaLabel={`${p.title}, 작성자 ${p.author_handle}, 좋아요 ${formatCount(p.like_count)}개, 댓글 ${formatCount(p.comment_count)}개`}
                    />
                  </li>
                ))}
              </ul>
            )}

            {phase === "ready" && hasMore && (
              <button
                type="button"
                className={styles.moreButton}
                onClick={() => void load(sort, false)}
                disabled={loadingMore}
                aria-busy={loadingMore}
              >
                {loadingMore ? "불러오는 중" : "더 보기"}
              </button>
            )}
          </>
        )}
      </div>

      <PostRail market={market} meta={meta} session={session} onWrite={openCompose} onRequireLogin={requireLogin} />
    </div>
  );
}
