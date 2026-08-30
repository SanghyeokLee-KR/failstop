"use client";

/**
 * 관리 평면 폴링 지연과 트레이딩 WS 단절이 같은 어휘를 쓴다.
 * 패널마다 낭독하면 live 영역이 겹쳐 소음이 되므로 낭독은 announce로 분리한다.
 */

import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useExchangeStore } from "@/lib/store";
import { bannerNotice, isDismissible } from "@/lib/notices";
import styles from "./StatusSignals.module.css";

function formatElapsed(sinceMs: number, nowMs: number): string {
  const s = Math.max(0, Math.floor((nowMs - sinceMs) / 1000));
  if (s < 60) return `${s}초 전`;
  if (s < 3600) return `${Math.floor(s / 60)}분 전`;
  return `${Math.floor(s / 3600)}시간 전`;
}

export function StaleBadge({
  since,
  label = "갱신 지연",
  announce = true,
}: {
  /** 경과 표시·타이머 구동 기준 시각. 생략·null이면 경과 없이 라벨만 그린다 */
  since?: number | null;
  label?: string;
  /** live 영역 낭독 여부. 트레이딩은 패널마다 배지가 붙어 낭독이 겹치므로 배너가 대신 고지한다 */
  announce?: boolean;
}) {
  const showElapsed = since != null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    // 경과 숫자만 매초 치환(재구성 아님, 시프트 0). since 없으면 타이머를 돌리지 않는다
    if (!showElapsed) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [showElapsed]);
  return (
    <span className={styles.staleBadge} role={announce ? "status" : undefined}>
      {/* 경고 형태는 장식이라 항상 낭독 제외. 라벨이 의미를 전달한다 */}
      <span aria-hidden="true">▲</span>
      {/* 경과 숫자는 매초 바뀌므로 announce 시엔 낭독에서 빼고 sr-only로 1회만 고지한다 */}
      <span aria-hidden={announce ? "true" : undefined}>
        {label}
        {showElapsed ? ` · ${formatElapsed(since, now)}` : ""}
      </span>
      {announce && <span className="sr-only">갱신이 지연돼 마지막 값 표시 중</span>}
    </span>
  );
}

export function StaleRegion({
  stale,
  children,
  className,
}: {
  stale: boolean;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={`${styles.staleRegion} ${className ?? ""}`} data-stale={stale ? "" : undefined}>
      {children}
    </div>
  );
}

/**
 * 배너 자리는 화면당 하나이고 연결 단절이 공지보다 먼저다. 끊긴 상태에서는 어떤 조작도 성립하지 않는다.
 * 공지는 CRITICAL·WARNING만 오르며, showNotice=false는 목록이 이미 공지를 보여주는 공지 화면용이다(§4-F).
 */
export function ConnectionBanner({ showNotice = true }: { showNotice?: boolean } = {}) {
  const connected = useExchangeStore((s) => s.connected);
  const notices = useExchangeStore((s) => s.notices);
  const dismissed = useExchangeStore((s) => s.dismissedNoticeIds);
  const dismissNotice = useExchangeStore((s) => s.dismissNotice);

  if (!connected) {
    return (
      <div className={styles.connBanner} role="status">
        <span className={styles.connBannerMark} aria-hidden="true">
          ▲
        </span>
        <span>실시간 연결 끊김. 마지막 시세를 표시하며 자동 재연결 중.</span>
      </div>
    );
  }

  if (!showNotice) return null;
  const notice = bannerNotice(notices, dismissed);
  if (!notice) return null;
  const critical = notice.severity === "CRITICAL";
  return (
    /* CRITICAL은 alert(즉시 낭독)이다. 점검·중단은 사용자가 다음 조작을 하기 전에 알아야
       한다. WARNING은 status(polite)라 진행 중인 낭독을 끊지 않는다 */
    <div className={styles.noticeBanner} data-severity={notice.severity} role={critical ? "alert" : "status"}>
      <span className={styles.connBannerMark} aria-hidden="true">
        {critical ? "■" : "▲"}
      </span>
      <span className={styles.noticeBannerTitle}>{notice.title}</span>
      {notice.body && <span className={styles.noticeBannerBody}>{notice.body}</span>}
      <Link href="/notices" className={styles.noticeBannerLink}>
        공지 전체
      </Link>
      {isDismissible(notice.severity) && (
        <button
          type="button"
          className={styles.noticeBannerClose}
          onClick={() => dismissNotice(notice.id)}
          aria-label="이 공지 닫기"
        >
          {/* 곱셈 기호(U+00D7). U+2715 계열은 스타일 게이트의 기호 범위에 걸리고
              알파벳 x는 글자로 읽혀 닫기 아이콘으로 안 읽힌다 */}
          ×
        </button>
      )}
    </div>
  );
}
