"use client";

/**
 * 게시판 목록 보조 요소. 카운트 배지(표시 전용) + 우측 레일(안내·계정·인기 글).
 * 인기 글은 인기순 상위 소량을 1회 조회한다. 실패는 조용히 흡수해 목록 렌더를 막지 않는다.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import type { CoinMeta, MemberSession } from "@/lib/types";
import type { CommunityPost } from "@/lib/community/types";
import { getCommunityDataSource } from "@/lib/community/datasource";
import { formatCount } from "@/lib/format";
import styles from "./community.module.css";

export function CountBadge({ kind, value }: { kind: "like" | "comment"; value: number }) {
  const label = kind === "like" ? "좋아요" : "댓글";
  return (
    <span className={styles.countBadge}>
      <span className={styles.countIcon} aria-hidden="true">
        {kind === "like" ? (
          <svg width="14" height="14" viewBox="0 0 24 24">
            <path
              d="M12 20.5l-1.3-1.1C6 15.1 3.2 12.6 3.2 9.4 3.2 6.9 5.2 5 7.6 5c1.5 0 2.9.7 3.7 1.9C12.1 5.7 13.5 5 15 5c2.4 0 4.4 1.9 4.4 4.4 0 3.2-2.8 5.7-7.5 10z"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.7"
              strokeLinejoin="round"
            />
          </svg>
        ) : (
          <svg width="14" height="14" viewBox="0 0 24 24">
            <path
              d="M4 5h16v11H9l-4 3v-3H4z"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.7"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </span>
      <span className={`${styles.countValue} num`} aria-label={`${label} ${formatCount(value)}개`}>
        {formatCount(value)}
      </span>
    </span>
  );
}

interface RailProps {
  market: string;
  meta: CoinMeta;
  session: MemberSession | null;
  onWrite(): void;
  onRequireLogin(): void;
}

export function PostRail({ market, meta, session, onWrite, onRequireLogin }: RailProps) {
  const ds = getCommunityDataSource();
  const [popular, setPopular] = useState<CommunityPost[]>([]);

  useEffect(() => {
    let alive = true;
    void ds
      .listPosts(market, "popular", null, 5)
      .then((page) => {
        if (alive) setPopular(page.items);
      })
      .catch(() => {
        // 레일 인기 글 실패는 무시. 목록 본문 렌더에 영향 없음(격리)
      });
    return () => {
      alive = false;
    };
  }, [ds, market]);

  return (
    <aside className={styles.rail} aria-label="게시판 정보">
      <section className={`card ${styles.railCard}`}>
        <h2 className={styles.railTitle}>{meta.koreanName} 토론방</h2>
        <p className={styles.railText}>
          종목에 대한 의견을 자유롭게 나누는 공간이에요. 서로 존중하는 표현을 지켜주세요. 투자 권유나 허위 정보는 신고 대상이에요.
        </p>
      </section>

      <section className={`card ${styles.railCard}`}>
        {session ? (
          <>
            <p className={styles.railText}>
              <b className={styles.railName}>{session.user.display_name}</b>님으로 참여 중이에요
            </p>
            <button type="button" className="btn btn--primary btn--full" onClick={onWrite}>
              글쓰기
            </button>
          </>
        ) : (
          <>
            <p className={styles.railText}>로그인하면 글과 댓글을 남길 수 있어요</p>
            <div className={styles.railCta}>
              <button type="button" className="btn btn--primary btn--full" onClick={onRequireLogin}>
                로그인
              </button>
              <Link href="/auth/signup" className="btn btn--secondary btn--full">
                회원 가입
              </Link>
            </div>
          </>
        )}
      </section>

      {popular.length > 0 && (
        <section className={`card ${styles.railCard}`}>
          <h2 className={styles.railTitle}>인기 글</h2>
          <ol className={styles.railList}>
            {popular.map((p, i) => (
              <li key={p.external_id}>
                <Link href={`/community/${market}?post=${p.external_id}`} className={styles.railItem}>
                  <span className={`${styles.railRank} num`} aria-hidden="true">
                    {i + 1}
                  </span>
                  <span className={styles.railItemTitle}>{p.title}</span>
                </Link>
              </li>
            ))}
          </ol>
        </section>
      )}
    </aside>
  );
}
