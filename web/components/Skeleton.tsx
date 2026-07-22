/** A-15. Skeleton. text·circle·card·listRow·chart, shimmer(reduced-motion 시 static), CLS 0 (§7) */

import styles from "./Skeleton.module.css";

interface Props {
  variant?: "text" | "circle" | "card" | "listRow" | "chart";
  width?: number | string;
  height?: number | string;
  className?: string;
}

export default function Skeleton({ variant = "text", width, height, className }: Props) {
  if (variant === "listRow") {
    return (
      <div className={`${styles.listRow} ${className ?? ""}`} aria-hidden="true">
        <span className={`${styles.base} ${styles.circle}`} style={{ width: 40, height: 40 }} />
        <span className={styles.listText}>
          <span className={`${styles.base} ${styles.text}`} style={{ width: "40%" }} />
          <span className={`${styles.base} ${styles.text}`} style={{ width: "24%" }} />
        </span>
        <span className={styles.listText} style={{ alignItems: "flex-end" }}>
          <span className={`${styles.base} ${styles.text}`} style={{ width: 88 }} />
          <span className={`${styles.base} ${styles.text}`} style={{ width: 56 }} />
        </span>
      </div>
    );
  }

  const style: React.CSSProperties = { width, height };
  const shape =
    variant === "circle" ? styles.circle : variant === "card" ? styles.card : variant === "chart" ? styles.chart : styles.text;
  return <span className={`${styles.base} ${shape} ${className ?? ""}`} style={style} aria-hidden="true" />;
}
