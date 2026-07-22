"use client";

/** A-4. ListRow. 3영역(에셋/텍스트/값·액션), 높이 64~72px, pressed 상태 (§7) */

import Link from "next/link";
import type { ReactNode } from "react";
import styles from "./ListRow.module.css";

interface Props {
  /** 좌측 에셋 영역 (심볼칩·별 등) */
  left?: ReactNode;
  title: ReactNode;
  sub?: ReactNode;
  /** 우측 값·액션 영역 */
  right?: ReactNode;
  href?: string;
  onClick?: () => void;
  ariaLabel?: string;
}

export default function ListRow({ left, title, sub, right, href, onClick, ariaLabel }: Props) {
  const body = (
    <>
      {left != null && <div className={styles.left}>{left}</div>}
      <div className={styles.text}>
        <div className={styles.title}>{title}</div>
        {sub != null && <div className={styles.sub}>{sub}</div>}
      </div>
      {right != null && <div className={styles.right}>{right}</div>}
    </>
  );

  if (href) {
    return (
      <Link href={href} className={styles.row} data-interactive aria-label={ariaLabel}>
        {body}
      </Link>
    );
  }
  if (onClick) {
    return (
      <button type="button" className={styles.row} data-interactive onClick={onClick} aria-label={ariaLabel}>
        {body}
      </button>
    );
  }
  return <div className={styles.row}>{body}</div>;
}
