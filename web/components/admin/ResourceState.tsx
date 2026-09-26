"use client";

/**
 * 관리 평면 리소스 상태 표현 조각. useAdminResource 상태를 화면에 그린다.
 * - AdminErrorState: 최초 로드 실패. empty-state role=alert + 재시도. 세션·서버 사유 구분.
 *   빈 상태(값 없음)와 모양을 가른다(§12-8). 서버 실패는 장애 면과 ■, 세션 만료는 주의 면과 ▲다.
 * - StaleBadge: 공용 지연 배지 재노출. 트레이딩·자산 표면과 같은 구현을 공유한다.
 */

import type { ErrorReason } from "./useAdminResource";
import styles from "./admin.module.css";

export { StaleBadge } from "@/components/StatusSignals";

const REASON_COPY: Record<ErrorReason, { title: string; desc: string }> = {
  session: {
    title: "세션 만료",
    desc: "다시 로그인한 뒤 이어서 진행합니다",
  },
  server: {
    title: "데이터를 불러오지 못함",
    desc: "서버에 연결하지 못했습니다. 잠시 후 다시 시도하십시오",
  },
};

/** 색 단독 금지라 사유마다 형태를 병기한다. 형태는 낭독하지 않고 제목이 뜻을 싣는다 */
const REASON_MARK: Record<ErrorReason, string> = { server: "■", session: "▲" };

export function AdminErrorState({
  reason = "server",
  title,
  desc,
  onRetry,
  retryLabel = "다시 시도",
}: {
  reason?: ErrorReason;
  /** 사유 기본 문구를 덮어쓸 때만 지정 */
  title?: string;
  desc?: string;
  onRetry?: () => void;
  retryLabel?: string;
}) {
  const copy = REASON_COPY[reason];
  return (
    <div className={`empty-state ${styles.errorState}`} data-reason={reason} role="alert">
      <span className="empty-state__title">
        <span className={styles.errorMark} aria-hidden="true">
          {REASON_MARK[reason]}
        </span>
        {title ?? copy.title}
      </span>
      <span className="empty-state__desc">{desc ?? copy.desc}</span>
      {onRetry && reason !== "session" && (
        <button type="button" className={styles.neutralBtn} onClick={onRetry}>
          {retryLabel}
        </button>
      )}
    </div>
  );
}
