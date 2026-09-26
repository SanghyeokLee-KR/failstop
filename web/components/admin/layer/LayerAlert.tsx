"use client";

/**
 * 층 안의 알림 줄(디자인 시스템 §12-12 토스트). 층이 열린 동안 난 오류는 오른쪽 아래 토스트가 아니라 맨 위 층의
 * 이 줄에 적는다. 덮개 아래 토스트는 층의 가장자리에 가려 읽히지 않는다. 모양은 불러오기 실패 면과 같다(§12-8)
 */

import { useLayerError } from "@/components/Toast";
import StatusShape from "../StatusShape";
import styles from "./layer.module.css";

export default function LayerAlert({ depth }: { depth: number }) {
  const err = useLayerError(depth);
  if (err === null) return null;
  return (
    <p key={err.id} className={styles.alert} role="alert">
      <StatusShape kind="square" className={styles.alertShape} />
      <span>{err.message}</span>
    </p>
  );
}
