"use client";

/**
 * 관리 평면 폴링 지연과 트레이딩 WS 단절이 같은 어휘를 쓴다.
 * 패널마다 낭독하면 live 영역이 겹쳐 소음이 되므로 낭독은 announce로 분리한다.
 */

import { useEffect, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { useExchangeStore } from "@/lib/store";
import { bannerNotice, isDismissible } from "@/lib/notices";
import {
  bannerKind,
  isSuspended,
  pollIntervalMs,
  RESUMED_TOAST,
  sinceLabel,
  suspensionCopy,
} from "@/lib/tradingStatus";
import type { TradingStatus } from "@/lib/types";
import { toast } from "@/components/Toast";
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
 * 거래 상태 폴링(API 설계 §4-A-7 클라이언트 규약). 화면을 열 때, poll_after_seconds마다, WebSocket이 다시 붙었을 때
 * 읽는다. 쓰기가 423을 받으면 주문 패널이 곧바로 한 번 더 읽는다. 결과를 모르는 주문의 재조회도 이 주기에 태운다.
 * 배너가 부르는 훅이라 배너를 그리는 화면마다 한 번 돈다.
 */
export function useTradingStatusPolling(): void {
  const connected = useExchangeStore((s) => s.connected);
  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      const before = useExchangeStore.getState().tradingStatus;
      const next = await useExchangeStore.getState().loadTradingStatus();
      if (stopped) return;
      // 재개는 알린다. 자동 재전송은 하지 않으므로 사용자가 다음 행동을 스스로 정해야 한다(Patroni 설계 6-2)
      if (isSuspended(before) && next !== null && !isSuspended(next)) toast(RESUMED_TOAST, "success");
      void useExchangeStore.getState().resolvePendingOrders();
      timer = setTimeout(() => void tick(), pollIntervalMs(next));
    };
    void tick();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, []);
  // 끊긴 동안 중단이 시작되거나 풀렸을 수 있다. 다시 붙는 순간 한 번 더 읽는다
  const wasConnected = useRef(connected);
  useEffect(() => {
    if (connected && !wasConnected.current) void useExchangeStore.getState().loadTradingStatus();
    wasConnected.current = connected;
  }, [connected]);
}

/* 닫을 수 없다. 중단은 사용자가 지울 수 있는 안내가 아니라 지금 주문이 안 된다는 사실이다.
   공지 CRITICAL과 같은 danger 띠를 쓴다. 거래가 멈추는 사건이라 연결 경고와 톤을 가른다 */
function TradingSuspendedBanner({ status }: { status: TradingStatus }) {
  const copy = suspensionCopy(status.reason);
  const since = sinceLabel(status.since);
  return (
    <div className={styles.noticeBanner} data-severity="CRITICAL" role="alert">
      <span className={styles.connBannerMark} aria-hidden="true">
        ■
      </span>
      <span className={styles.noticeBannerTitle} aria-hidden="true">
        {copy.title}
      </span>
      {since && (
        <span className={`${styles.suspendSince} num`} aria-hidden="true">
          {since}
        </span>
      )}
      <span className={styles.noticeBannerBody} aria-hidden="true">
        {copy.body}
      </span>
      <span className="sr-only">{copy.sr}</span>
    </div>
  );
}

/**
 * 거래 일시 중단 배너만 그린다. 실시간 연결을 쓰지 않는 쓰기 화면(게시판)용이다. 그 화면에는 연결 끊김과 공지를
 * 띄울 이유가 없지만 쓰기가 막힌다는 사실은 알려야 한다.
 */
export function TradingStatusBanner() {
  useTradingStatusPolling();
  const tradingStatus = useExchangeStore((s) => s.tradingStatus);
  if (!isSuspended(tradingStatus) || !tradingStatus) return null;
  return <TradingSuspendedBanner status={tradingStatus} />;
}

/**
 * 배너 자리는 화면당 하나다. 연결 단절, 거래 일시 중단, 공지 순이다(§4-F). 끊긴 상태에서는 어떤 조작도 성립하지 않고,
 * 중단은 공지보다 먼저 알아야 주문을 헛되이 내지 않는다. 공지는 CRITICAL·WARNING만 오르며, showNotice=false는 목록이
 * 이미 공지를 보여주는 공지 화면용이다. 그 화면에서도 중단 배너는 뜬다.
 */
export function ConnectionBanner({ showNotice = true }: { showNotice?: boolean } = {}) {
  useTradingStatusPolling();
  const connected = useExchangeStore((s) => s.connected);
  const tradingStatus = useExchangeStore((s) => s.tradingStatus);
  const notices = useExchangeStore((s) => s.notices);
  const dismissed = useExchangeStore((s) => s.dismissedNoticeIds);
  const dismissNotice = useExchangeStore((s) => s.dismissNotice);
  const kind = bannerKind(connected, tradingStatus, showNotice ? bannerNotice(notices, dismissed) : null);

  if (kind === "connection") {
    return (
      <div className={styles.connBanner} role="status">
        <span className={styles.connBannerMark} aria-hidden="true">
          ▲
        </span>
        <span>실시간 연결 끊김. 마지막 시세를 표시하며 자동 재연결 중.</span>
      </div>
    );
  }

  if (kind === "suspended" && tradingStatus) return <TradingSuspendedBanner status={tradingStatus} />;
  if (kind !== "notice") return null;
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
