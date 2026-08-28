"use client";

/**
 * 종목 토론 게시판 클라이언트 루트(게시판_설계 §5).
 * market은 로컬 마켓 캐시(COIN_META)로 검증하고 미존재는 안내로 거른다(§1.1 404 대응).
 * 상세는 ?post 쿼리로 분기한다. 게시판 데이터는 CommunityDataSource로만 접근한다(거래 스토어와 격리).
 */

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import SymbolChip from "@/components/SymbolChip";
import { COIN_META } from "@/lib/sim/engine";
import { useMemberSession } from "@/lib/store";
import BoardListView from "./BoardListView";
import PostDetailView from "./PostDetailView";
import styles from "./community.module.css";

export default function CommunityClient() {
  const params = useParams<{ market: string }>();
  const search = useSearchParams();
  const session = useMemberSession();
  const market = typeof params.market === "string" ? params.market : "";
  const meta = COIN_META.find((c) => c.market === market);
  const postId = search.get("post");

  if (!meta) {
    return (
      <main className={styles.page}>
        <div className={styles.notFound}>
          <span className="empty-state__mark" aria-hidden="true" />
          <h1 className={styles.notFoundTitle}>존재하지 않는 종목 게시판</h1>
          <Link href="/exchange" className="btn btn--secondary">
            거래소로 이동
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <nav className={styles.crumb} aria-label="이동 경로">
        <Link href={`/exchange/${market}`} className={`hit-44-y ${styles.crumbLink}`}>
          {meta.koreanName} 거래
        </Link>
        <span className={styles.crumbSep} aria-hidden="true">
          /
        </span>
        <span className={styles.crumbCurrent}>토론</span>
      </nav>

      <header className={styles.boardHead}>
        <SymbolChip meta={meta} size="sm" />
        <div className={styles.boardHeadText}>
          <h1 className={styles.boardTitle}>{meta.koreanName} 토론</h1>
          <span className={`${styles.boardSymbol} num`}>{market}</span>
        </div>
      </header>

      {postId ? (
        <PostDetailView market={market} meta={meta} postId={postId} session={session} />
      ) : (
        <BoardListView market={market} meta={meta} session={session} />
      )}
    </main>
  );
}
