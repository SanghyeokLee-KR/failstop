"use client";

/**
 * 댓글 영역. 1뎁스 트리(부모 + 대댓글). 목록·작성·삭제·좋아요·신고를 자체 관리한다.
 * 본문은 React 텍스트로만 렌더해 이스케이프한다(XSS 방지, dangerouslySetInnerHTML 금지).
 * 작성·좋아요·삭제는 낙관 UI로 반영하고 실패 시 롤백한다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import type { MemberSession } from "@/lib/types";
import type { CommunityComment, ReportReason } from "@/lib/community/types";
import { COMMENT_BODY_MAX } from "@/lib/community/types";
import { getCommunityDataSource } from "@/lib/community/datasource";
import { mapCommunityError } from "@/lib/community/errors";
import { formatCount } from "@/lib/format";
import { formatRelativeTime, formatAbsoluteTime } from "@/lib/community/time";
import { toast } from "@/components/Toast";
import Skeleton from "@/components/Skeleton";
import LikeButton from "./LikeButton";
import ReportDialog from "./ReportDialog";
import styles from "./CommentSection.module.css";

interface Props {
  postExternalId: string;
  session: MemberSession | null;
  /** 글의 전체 댓글 수(서버 집계). 헤딩은 로드된 페이지가 아니라 이 총계를 반영한다 */
  commentCount: number;
  onRequireLogin(): void;
  onCountChange?(delta: number): void;
}

export default function CommentSection({ postExternalId, session, commentCount, onRequireLogin, onCountChange }: Props) {
  const ds = getCommunityDataSource();
  const [comments, setComments] = useState<CommunityComment[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [phase, setPhase] = useState<"loading" | "ready" | "error">("loading");
  const [reportTarget, setReportTarget] = useState<string | null>(null);
  const [replyTo, setReplyTo] = useState<string | null>(null);

  const load = useCallback(
    async (reset: boolean) => {
      try {
        if (reset) setPhase("loading");
        const page = await ds.listComments(postExternalId, reset ? null : cursor, 20);
        setComments((prev) => (reset ? page.items : [...prev, ...page.items]));
        setCursor(page.next_cursor);
        setHasMore(page.has_more);
        setPhase("ready");
      } catch {
        setPhase("error");
      }
    },
    [ds, postExternalId, cursor],
  );

  useEffect(() => {
    void load(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [postExternalId]);

  const patch = (id: string, next: Partial<CommunityComment>) =>
    setComments((prev) => prev.map((c) => (c.external_id === id ? { ...c, ...next } : c)));

  const toggleLike = async (c: CommunityComment) => {
    if (!session) return onRequireLogin();
    const nextLiked = !c.liked;
    patch(c.external_id, { liked: nextLiked, like_count: c.like_count + (nextLiked ? 1 : -1) });
    try {
      const r = await ds.toggleCommentLike(c.external_id, nextLiked);
      patch(c.external_id, { liked: r.liked, like_count: r.like_count });
    } catch (e) {
      patch(c.external_id, { liked: c.liked, like_count: c.like_count });
      const m = mapCommunityError(e);
      if (m.needsLogin) onRequireLogin();
      else toast(m.message, "error");
    }
  };

  const remove = async (c: CommunityComment) => {
    if (!session) return onRequireLogin();
    const snapshot = comments;
    patch(c.external_id, { status: "DELETED", body: "삭제된 댓글입니다" });
    onCountChange?.(-1);
    try {
      await ds.deleteComment(c.external_id);
      toast("댓글을 삭제했어요", "info");
    } catch (e) {
      setComments(snapshot);
      onCountChange?.(1);
      toast(mapCommunityError(e).message, "error");
    }
  };

  const submitReport = async (targetId: string, reason: ReportReason, detail: string) => {
    try {
      await ds.report({ targetType: "COMMENT", targetId, reason, detail });
      toast("신고를 접수했어요", "success");
      setReportTarget(null);
    } catch (e) {
      const m = mapCommunityError(e);
      if (m.needsLogin) {
        setReportTarget(null);
        onRequireLogin();
      } else {
        toast(m.message, "error");
      }
    }
  };

  const created = (comment: CommunityComment, parentId: string | null) => {
    // 부모 답글은 부모 바로 뒤, 최상위는 목록 끝에 붙인다(작성순 유지)
    setComments((prev) => {
      if (!parentId) return [...prev, comment];
      const idx = prev.findIndex((c) => c.external_id === parentId);
      if (idx < 0) return [...prev, comment];
      let insert = idx + 1;
      while (insert < prev.length && prev[insert].parent_id === parentId) insert++;
      return [...prev.slice(0, insert), comment, ...prev.slice(insert)];
    });
    onCountChange?.(1);
    setReplyTo(null);
  };

  const roots = comments.filter((c) => !c.parent_id);
  const childrenOf = (id: string) => comments.filter((c) => c.parent_id === id);

  return (
    <section className={styles.root} aria-label="댓글">
      <h2 className={styles.heading}>
        댓글 <span className="num">{formatCount(commentCount)}</span>
      </h2>

      {session ? (
        <CommentComposer
          postExternalId={postExternalId}
          parentId={null}
          onCreated={(c) => created(c, null)}
          onRequireLogin={onRequireLogin}
        />
      ) : (
        <button type="button" className={styles.loginPrompt} onClick={onRequireLogin}>
          로그인하고 댓글을 남겨보세요
        </button>
      )}

      {phase === "loading" && (
        <div className={styles.list}>
          <Skeleton variant="listRow" />
          <Skeleton variant="listRow" />
        </div>
      )}

      {phase === "error" && (
        <div className={styles.inlineError} role="alert">
          <span>댓글을 불러오지 못했어요</span>
          <button type="button" className={styles.retry} onClick={() => void load(true)}>
            다시 시도
          </button>
        </div>
      )}

      {phase === "ready" && roots.length === 0 && (
        <div className={styles.empty}>
          <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
          <p>아직 댓글이 없어요. 첫 댓글을 남겨보세요</p>
        </div>
      )}

      {phase === "ready" && roots.length > 0 && (
        <ul className={styles.list}>
          {roots.map((c) => (
            <li key={c.external_id}>
              <CommentItem
                comment={c}
                onToggleLike={() => void toggleLike(c)}
                onDelete={() => void remove(c)}
                onReport={() => (session ? setReportTarget(c.external_id) : onRequireLogin())}
                onReply={() => (session ? setReplyTo(replyTo === c.external_id ? null : c.external_id) : onRequireLogin())}
                replying={replyTo === c.external_id}
              />
              {replyTo === c.external_id && session && (
                <div className={styles.replyBox}>
                  <CommentComposer
                    postExternalId={postExternalId}
                    parentId={c.external_id}
                    onCreated={(nc) => created(nc, c.external_id)}
                    onRequireLogin={onRequireLogin}
                    compact
                  />
                </div>
              )}
              {childrenOf(c.external_id).length > 0 && (
                <ul className={styles.replies}>
                  {childrenOf(c.external_id).map((rc) => (
                    <li key={rc.external_id}>
                      <CommentItem
                        comment={rc}
                        reply
                        onToggleLike={() => void toggleLike(rc)}
                        onDelete={() => void remove(rc)}
                        onReport={() => (session ? setReportTarget(rc.external_id) : onRequireLogin())}
                      />
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      )}

      {phase === "ready" && hasMore && (
        <button type="button" className={styles.more} onClick={() => void load(false)}>
          댓글 더 보기
        </button>
      )}

      {reportTarget && (
        <ReportDialog
          targetType="COMMENT"
          onClose={() => setReportTarget(null)}
          onSubmit={(reason, detail) => submitReport(reportTarget, reason, detail)}
        />
      )}
    </section>
  );
}

interface ItemProps {
  comment: CommunityComment;
  reply?: boolean;
  replying?: boolean;
  onToggleLike(): void;
  onDelete(): void;
  onReport(): void;
  onReply?(): void;
}

function CommentItem({ comment, reply, replying, onToggleLike, onDelete, onReport, onReply }: ItemProps) {
  const deleted = comment.status === "DELETED";
  return (
    <article className={styles.item} data-reply={reply || undefined}>
      <div className={styles.itemHead}>
        <span className={styles.author}>{comment.author_handle}</span>
        <time className={styles.time} dateTime={comment.created_at} title={formatAbsoluteTime(comment.created_at)}>
          {formatRelativeTime(comment.created_at)}
        </time>
      </div>
      <p className={styles.body} data-deleted={deleted || undefined}>
        {comment.body}
      </p>
      {!deleted && (
        <div className={styles.itemActions}>
          <LikeButton liked={comment.liked} count={comment.like_count} onToggle={onToggleLike} size="sm" />
          {onReply && (
            <button type="button" className={styles.textAction} onClick={onReply} aria-pressed={replying}>
              답글
            </button>
          )}
          {comment.mine ? (
            <button type="button" className={styles.textAction} onClick={onDelete}>
              삭제
            </button>
          ) : (
            <button type="button" className={styles.textAction} onClick={onReport}>
              신고
            </button>
          )}
        </div>
      )}
    </article>
  );
}

interface ComposerProps {
  postExternalId: string;
  parentId: string | null;
  onCreated(comment: CommunityComment): void;
  onRequireLogin(): void;
  compact?: boolean;
}

function CommentComposer({ postExternalId, parentId, onCreated, onRequireLogin, compact }: ComposerProps) {
  const ds = getCommunityDataSource();
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const keyRef = useRef<string>("");

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const text = body.trim();
    if (!text || busy) return;
    setBusy(true);
    if (!keyRef.current) keyRef.current = crypto.randomUUID();
    try {
      const c = await ds.createComment(postExternalId, { body: text, parentId, clientKey: keyRef.current });
      setBody("");
      keyRef.current = "";
      onCreated(c);
    } catch (err) {
      const m = mapCommunityError(err);
      if (m.needsLogin) onRequireLogin();
      else toast(m.message, "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className={styles.composer} onSubmit={submit} data-compact={compact || undefined}>
      <textarea
        className={styles.composerInput}
        value={body}
        rows={compact ? 2 : 3}
        maxLength={COMMENT_BODY_MAX}
        disabled={busy}
        placeholder={parentId ? "답글을 입력해 주세요" : "댓글을 입력해 주세요"}
        aria-label={parentId ? "답글 입력" : "댓글 입력"}
        onChange={(e) => setBody(e.target.value)}
      />
      <div className={styles.composerFoot}>
        <button type="submit" className="btn btn--primary" disabled={!body.trim() || busy} aria-busy={busy}>
          {busy ? "등록 중" : "등록"}
        </button>
      </div>
    </form>
  );
}
