"use client";

/**
 * 실시간 연결·지연 상태 표현 공용 조각. 관리 평면 폴링 지연과 트레이딩 WS 단절이 같은 어휘를 쓴다.
 * - StaleBadge: 지연 배지. 깜빡임 없이 경과만 갱신, 시프트 0. 관리 평면은 경과·낭독을, 트레이딩은
 *   경과 없는 시각 표식을 쓴다(패널마다 낭독하면 live 영역이 겹쳐 소음이 되므로 announce로 분리).
 * - StaleRegion: 지연 구간 감쇠 래퍼. 마지막 값임을 opacity로 알린다. 시프트 0(A-5).
 * - ConnectionBanner: 연결 단절 시 상단 경고 배너. 화면당 낭독 1회의 권위 고지(A-5 warning).
 */

import { useEffect, useState, type ReactNode } from "react";
import { useExchangeStore } from "@/lib/store";
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
      {announce && <span className="sr-only">갱신이 지연돼 마지막 값을 표시 중입니다.</span>}
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
 * 연결 단절 배너. store.connected를 직접 구독한다(단일 원천). sim 기본은 connected=true라
 * 렌더되지 않으므로 데모 표시는 무변경. http 모드에서 WS 단절 시에만 나타난다.
 */
export function ConnectionBanner() {
  const connected = useExchangeStore((s) => s.connected);
  if (connected) return null;
  return (
    <div className={styles.connBanner} role="status">
      <span className={styles.connBannerMark} aria-hidden="true">
        ▲
      </span>
      <span>실시간 연결이 끊겨 마지막 시세를 표시하고 있어요. 자동으로 다시 연결하고 있어요.</span>
    </div>
  );
}
