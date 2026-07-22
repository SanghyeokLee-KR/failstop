"use client";

/**
 * 좋아요 토글 버튼. 아이콘+카운트+접근성 라벨 병기(색 단독 금지 규약 준수, 활성색은 브랜드).
 * 상태는 상위가 소유한다. 낙관 UI·롤백은 호출부 책임. 여기선 표시·클릭만.
 * 좋아요 활성색에 방향색(Red 상승) 차용 금지. 시세 방향과 무관해 브랜드색으로 표기.
 */

import { formatCount } from "@/lib/format";
import styles from "./LikeButton.module.css";

interface Props {
  liked: boolean;
  count: number;
  onToggle(): void;
  disabled?: boolean;
  size?: "sm" | "md";
}

export default function LikeButton({ liked, count, onToggle, disabled, size = "md" }: Props) {
  return (
    <button
      type="button"
      className={styles.root}
      data-on={liked || undefined}
      data-size={size}
      aria-pressed={liked}
      aria-label={liked ? `좋아요 취소, 현재 ${count}개` : `좋아요, 현재 ${count}개`}
      disabled={disabled}
      onClick={onToggle}
    >
      <svg className={styles.icon} width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M12 20.5l-1.3-1.1C6 15.1 3.2 12.6 3.2 9.4 3.2 6.9 5.2 5 7.6 5c1.5 0 2.9.7 3.7 1.9C12.1 5.7 13.5 5 15 5c2.4 0 4.4 1.9 4.4 4.4 0 3.2-2.8 5.7-7.5 10z"
          fill={liked ? "currentColor" : "none"}
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinejoin="round"
        />
      </svg>
      <span className={`${styles.count} num`}>{formatCount(count)}</span>
    </button>
  );
}
