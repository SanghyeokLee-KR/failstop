"use client";

/**
 * 값을 읽은 시각(KST)과 새로고침. 수동 갱신 관리 화면의 머리 오른쪽에 둔다(디자인 시스템 §12-14 v1.14).
 *
 * 관제센터 머리의 수집 상태와 같은 판독 한 줄이다. 모양 기호와 13px 글자가 상태를 말하고, 새로고침은 머리의 다른
 * 조작과 같은 중립 단추다. 자동으로 돌지 않는 값은 언제 잰 것인지가 반드시 보여야 한다. 시각 없이 숫자만 있으면
 * 운영자가 그것을 현재로 읽고, 그 오해는 장애 대응 중에 가장 비싸다. 폴링이 실패해 값이 굳은 동안에는 같은 자리가
 * 주의 모양과 '갱신 지연'으로 바뀐다. 초록 점은 쓰지 않는다. 수동으로 읽은 값은 실시간이 아니기 때문이다.
 */

import { hms } from "@/lib/admin/timeText";
import { AdminIcon } from "./icons";
import StatusShape from "./StatusShape";
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
  const at = lastUpdatedAt === null ? null : hms(new Date(lastUpdatedAt).toISOString());
  const kind = at === null ? "none" : stale ? "stale" : "read";
  return (
    <span className={styles.stamp}>
      <span className={styles.stampText} data-kind={kind} role="status">
        <StatusShape kind={kind === "stale" ? "triangle" : kind === "none" ? "dashed" : "ring"} className={styles.stampShape} />
        {at === null ? "조회 전" : stale ? `갱신 지연 · 마지막 조회 ${at}` : `마지막 조회 ${at}`}
      </span>
      <button type="button" className={`${styles.neutralBtn} ${styles.stampBtn}`} onClick={onReload}>
        <AdminIcon name="refresh" />
        새로고침
      </button>
    </span>
  );
}
