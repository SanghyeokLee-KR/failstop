"use client";

/**
 * 관리 평면 리소스 상태 표현 조각. useAdminResource 상태를 화면에 그린다.
 * - AdminErrorState: 최초 로드 실패. empty-state role=alert + 재시도. 세션·서버 사유 구분.
 * - StaleBadge: 폴링 실패 지연 배지. 깜빡임 없이 경과만 갱신, 시프트 0. 낭독 1회.
 */

import { useEffect, useState } from "react";
import type { ErrorReason } from "./useAdminResource";
import styles from "./admin.module.css";

const REASON_COPY: Record<ErrorReason, { title: string; desc: string }> = {
  session: {
    title: "세션이 만료됐어요",
    desc: "다시 로그인한 뒤 이어서 진행해 주세요.",
  },
  server: {
    title: "데이터를 불러오지 못했어요",
    desc: "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
  },
};

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
    <div className="empty-state" role="alert">
      <span className="empty-state__title">{title ?? copy.title}</span>
      <span className="empty-state__desc">{desc ?? copy.desc}</span>
      {onRetry && reason !== "session" && (
        <button type="button" className="btn btn--secondary" onClick={onRetry}>
          {retryLabel}
        </button>
      )}
    </div>
  );
}

function formatElapsed(sinceMs: number, nowMs: number): string {
  const s = Math.max(0, Math.floor((nowMs - sinceMs) / 1000));
  if (s < 60) return `${s}초 전`;
  if (s < 3600) return `${Math.floor(s / 60)}분 전`;
  return `${Math.floor(s / 3600)}시간 전`;
}

export function StaleBadge({ since }: { since: number | null }) {
  // 경과 표시만 매초 갱신. 재구성이 아닌 텍스트 치환이라 시프트 0
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return (
    <span className={styles.staleBadge} role="status">
      {/* 경과 숫자는 매초 바뀌므로 낭독 대상에서 뺀다. 마운트 시 aria-label로 1회만 고지 */}
      <span aria-hidden="true">
        ▲ 갱신 지연{since != null ? ` · ${formatElapsed(since, now)}` : ""}
      </span>
      <span className="sr-only">갱신이 지연돼 마지막 값을 표시 중이에요.</span>
    </span>
  );
}
