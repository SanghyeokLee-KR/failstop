"use client";

/**
 * 커서 페이지네이션 페이저(API 설계 §2-6).
 *
 * 총 쪽수를 모르는 커서 목록 전용이다. 서버는 다음 쪽의 존재 여부만 알려주고 전체 건수는
 * 세지 않는다(페이지마다 전 범위 COUNT를 도는 비용을 피한다). 그래서 "3 / 12" 같은 위치
 * 표시를 할 수 없고, 지어내지도 않는다. 지금 몇 쪽인지만 말한다.
 *
 * 갈 곳이 없으면 렌더하지 않는다. 첫 쪽뿐인 사용자에게 비활성 버튼 두 개를 보여줄 이유가
 * 없다. 소비처 카드는 높이가 다른 요소에 잠겨 있어야 나타나고 사라져도 흔들리지 않는다.
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
