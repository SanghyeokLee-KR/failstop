"use client";

/**
 * 최종 실측 시각과 새로고침. 관리 화면 머리에 상설한다.
 *
 * 갱신을 manual로 내린 화면이 많아졌다. 자동으로 돌지 않는 값은 언제 잰 것인지가 반드시
 * 보여야 한다. 시각 없이 숫자만 있으면 운영자가 그것을 현재로 읽고, 그 오해는 장애 대응
 * 중에 가장 비싸다. 폴링 실패로 값이 굳은 구간은 지연 배지가 따로 말한다.
 */

import { StaleBadge } from "@/components/StatusSignals";
import { formatTimeHMS } from "@/lib/format";
import styles from "./admin.module.css";

export default function ResourceStamp({
  lastUpdatedAt,
  stale,
  onReload,
}: {
  lastUpdatedAt: number | null;
  stale: boolean;
  onReload: () => void;
}) {
  return (
    <span className={styles.stamp}>
      {stale && <StaleBadge since={lastUpdatedAt} />}
      <span className={styles.stampTime}>
        {lastUpdatedAt === null ? "실측 없음" : `최종 실측 ${formatTimeHMS(new Date(lastUpdatedAt).toISOString())}`}
      </span>
      <button type="button" className={styles.stampBtn} onClick={onReload}>
        새로고침
      </button>
    </span>
  );
}
