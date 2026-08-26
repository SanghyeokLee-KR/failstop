"use client";

/**
 * 글 상세 뷰. 본문(이스케이프 렌더) + 좋아요 토글 + 본인 수정·삭제 + 신고 + 댓글.
 * 본문은 React 텍스트로만 렌더한다(XSS 방지, dangerouslySetInnerHTML 금지, 줄바꿈은 pre-wrap).
 * 좋아요·삭제는 낙관 UI로 반영하고 실패 시 롤백한다.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import type { CoinMeta, MemberSession } from "@/lib/types";
import type { CommunityPost, ReportReason } from "@/lib/community/types";
import { ApiError } from "@/lib/api/httpDatasource";
import { getCommunityDataSource } from "@/lib/community/datasource";
import { mapCommunityError } from "@/lib/community/errors";
import { formatRelativeTime, formatAbsoluteTime } from "@/lib/community/time";
import Skeleton from "@/components/Skeleton";
import { toast } from "@/components/Toast";
import PostComposer from "@/components/community/PostComposer";
import LikeButton from "@/components/community/LikeButton";
import ReportDialog from "@/components/community/ReportDialog";
import CommentSection from "@/components/community/CommentSection";
import styles from "./community.module.css";

interface Props {
  market: string;
  meta: CoinMeta;
  postId: string;
  session: MemberSession | null;
}

export default function PostDetailView({ market, meta, postId, session }: Props) {
  const ds = getCommunityDataSource();
  const router = useRouter();
  const [post, setPost] = useState<CommunityPost | null>(null);
  const [phase, setPhase] = useState<"loading" | "ready" | "notfound" | "error">("loading");
  const [editing, setEditing] = useState(false);
  const [titleError, setTitleError] = useState<string | null>(null);
  const [bodyError, setBodyError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [reporting, setReporting] = useState(false);

  useEffect(() => {
    let alive = true;
    setPhase("loading");
    void ds
      .getPost(postId)
      .then((p) => {
        if (!alive) return;
        setPost(p);
        setPhase(p.status === "DELETED" ? "notfound" : "ready");
      })
      .catch((e) => {
        if (!alive) return;
        setPhase(e instanceof ApiError && e.status === 404 ? "notfound" : "error");
      });
    return () => {
      alive = false;
    };
  }, [ds, postId]);

  const requireLogin = () => {
    toast("로그인 필요", "info");
    router.push("/auth/login");
  };

  const backHref = `/community/${market}`;

  const toggleLike = async () => {
    if (!post) return;
    if (!session) return requireLogin();
    const nextLiked = !post.liked;
    const before = post;
    setPost({ ...post, liked: nextLiked, like_count: post.like_count + (nextLiked ? 1 : -1) });
    try {
      const r = await ds.togglePostLike(post.external_id, nextLiked);
      setPost((p) => (p ? { ...p, liked: r.liked, like_count: r.like_count } : p));
    } catch (e) {
      setPost(before);
      const m = mapCommunityError(e);
      if (m.needsLogin) requireLogin();
      else toast(m.message, "error");
    }
  };

  const submitEdit = async (title: string, body: string) => {
    if (!post) return;
    setTitleError(null);
    setBodyError(null);
    try {
      const updated = await ds.updatePost(post.external_id, { title, body });
      setPost(updated);
      setEditing(false);
      toast("글 수정 완료", "success");
    } catch (e) {
      const m = mapCommunityError(e);
      if (m.needsLogin) requireLogin();
      else if (m.field === "title") setTitleError(m.message);
      else if (m.field === "body") setBodyError(m.message);
      else toast(m.message, "error");
    }
  };

  const remove = async () => {
    if (!post) return;
    try {
      await ds.deletePost(post.external_id);
      toast("글 삭제 완료", "info");
      router.push(backHref);
    } catch (e) {
      setConfirming(false);
      const m = mapCommunityError(e);
      if (m.needsLogin) requireLogin();
      else toast(m.message, "error");
    }
  };

  const submitReport = async (reason: ReportReason, detail: string) => {
    if (!post) return;
    try {
      await ds.report({ targetType: "POST", targetId: post.external_id, reason, detail });
      toast("신고 접수 완료", "success");
      setReporting(false);
    } catch (e) {
      const m = mapCommunityError(e);
      if (m.needsLogin) {
        setReporting(false);
        requireLogin();
      } else {
        toast(m.message, "error");
      }
    }
  };

  return (
    <div className={styles.layout}>
      <div className={styles.main}>
        <Link href={backHref} className={styles.backLink}>
          <span aria-hidden="true">←</span> 목록으로
        </Link>

        {phase === "loading" && (
          <section className="card">
            <Skeleton variant="text" width="60%" height={28} />
            <div style={{ height: 12 }} />
            <Skeleton variant="text" width="40%" />
            <div style={{ height: 20 }} />
            <Skeleton variant="text" width="100%" />
            <div style={{ height: 8 }} />
            <Skeleton variant="text" width="90%" />
          </section>
        )}

        {phase === "notfound" && (
          <div className="empty-state">
            <p className="empty-state__title">이미 삭제됐거나 없는 글</p>
            <Link href={backHref} className="btn btn--secondary">
              목록으로
            </Link>
          </div>
        )}

        {phase === "error" && (
          <div className="empty-state" role="alert">
            <p className="empty-state__title">글을 불러오지 못함</p>
            <Link href={backHref} className="btn btn--secondary">
              목록으로
            </Link>
          </div>
        )}

        {phase === "ready" && post && editing && (
          <section className="card">
            <h2 className={styles.sectionTitle}>글 수정</h2>
            <PostComposer
              initialTitle={post.title}
              initialBody={post.body}
              submitLabel="수정 완료"
              busyLabel="수정 중"
              onSubmit={submitEdit}
              onCancel={() => setEditing(false)}
              titleError={titleError}
              bodyError={bodyError}
            />
          </section>
        )}

        {phase === "ready" && post && !editing && (
          <>
            <article className={`card ${styles.postCard}`}>
              <h1 className={styles.postTitle}>{post.title}</h1>
              <div className={styles.postMeta}>
                <span className={styles.postAuthor}>{post.author_handle}</span>
                <time
                  className={styles.postTime}
                  dateTime={post.created_at}
                  title={formatAbsoluteTime(post.created_at)}
                >
                  {formatRelativeTime(post.created_at)}
                </time>
                {post.updated_at !== post.created_at && <span className={styles.edited}>(수정됨)</span>}
              </div>

              <p className={styles.postBody}>{post.body}</p>

              <div className={styles.postActions}>
                <LikeButton liked={post.liked} count={post.like_count} onToggle={() => void toggleLike()} />
                <div className={styles.postActionsRight}>
                  {post.mine ? (
                    <>
                      <button type="button" className={styles.textAction} onClick={() => setEditing(true)}>
                        수정
                      </button>
                      {confirming ? (
                        <span className={styles.confirm}>
                          <span className={styles.confirmText}>삭제할까요?</span>
                          <button type="button" className={styles.dangerAction} onClick={() => void remove()}>
                            삭제
                          </button>
                          <button type="button" className={styles.textAction} onClick={() => setConfirming(false)}>
                            취소
                          </button>
                        </span>
                      ) : (
                        <button type="button" className={styles.textAction} onClick={() => setConfirming(true)}>
                          삭제
                        </button>
                      )}
                    </>
                  ) : (
                    <button
                      type="button"
                      className={styles.textAction}
                      onClick={() => (session ? setReporting(true) : requireLogin())}
                    >
                      신고
                    </button>
                  )}
                </div>
              </div>
            </article>

            <section className={`card ${styles.commentsCard}`}>
              <CommentSection
                postExternalId={post.external_id}
                session={session}
                commentCount={post.comment_count}
                onRequireLogin={requireLogin}
                onCountChange={(delta) =>
                  setPost((p) => (p ? { ...p, comment_count: Math.max(0, p.comment_count + delta) } : p))
                }
              />
            </section>
          </>
        )}
      </div>

      <aside className={styles.rail} aria-label="게시판 정보">
        <section className={`card ${styles.railCard}`}>
          <h2 className={styles.railTitle}>{meta.koreanName} 토론방</h2>
          <p className={styles.railText}>
            서로 존중하는 표현 유지, 투자 권유·허위 정보는 신고 대상.
          </p>
          <Link href={backHref} className="btn btn--secondary btn--full">
            목록 전체 보기
          </Link>
        </section>
        <section className={`card ${styles.railCard}`}>
          <Link href={`/exchange/${market}`} className="btn btn--secondary btn--full">
            {meta.koreanName} 거래 화면
          </Link>
        </section>
      </aside>

      {reporting && post && (
        <ReportDialog
          targetType="POST"
          onClose={() => setReporting(false)}
          onSubmit={(reason, detail) => submitReport(reason, detail)}
        />
      )}
    </div>
  );
}
