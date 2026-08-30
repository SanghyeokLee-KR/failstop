"use client";

/**
 * 커서 목록이라 총 쪽수를 모른다. 위치 표시를 지어내지 않고 현재 쪽만 말한다(§2-6).
 * 갈 곳이 없으면 렌더하지 않는다.
 */

import styles from "./Pager.module.css";

interface Props {
  /** 0부터 센다. 표시는 +1 */
  page: number;
  hasMore: boolean;
  onMove: (target: number) => void;
  /** 스크린리더가 어느 목록의 페이저인지 알 수 있게 한다 */
  ariaLabel: string;
  /** 이동 중 연타 방지. 소비처가 in-flight를 알면 넘긴다 */
  busy?: boolean;
}

export default function Pager({ page, hasMore, onMove, ariaLabel, busy }: Props) {
  if (page === 0 && !hasMore) return null;
  return (
    <nav className={styles.pager} aria-label={ariaLabel}>
      <button
        type="button"
        className="btn btn--secondary"
        onClick={() => onMove(page - 1)}
        disabled={page === 0 || busy}
      >
        이전
      </button>
      <span className={`${styles.at} num`} aria-live="polite">
        {page + 1}쪽
      </span>
      <button
        type="button"
        className="btn btn--secondary"
        onClick={() => onMove(page + 1)}
        disabled={!hasMore || busy}
      >
        다음
      </button>
    </nav>
  );
}
