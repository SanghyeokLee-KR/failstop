"use client";

/**
 * 관제센터 화면들이 함께 쓰는 작은 조각. 상태 어휘는 디자인 시스템 §12-3, 관제센터 규칙은 §12-9를 따른다.
 */

import { useEffect, useState, type ReactNode } from "react";
import type { AlertSeverity, ControlCenter, OpsEnvironment, OpsSection } from "@/lib/admin/types";
import { MeterRail } from "../instruments/MeterRail";
import styles from "./control.module.css";

/** 매초 다시 그린다. 남은 시간과 'N초 전'처럼 벽시계로 읽는 글자만 쓴다 */
export function useClock(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}

export function mmss(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function hmmss(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  return `${h}:${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function hm(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function hms(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export function ago(iso: string, now: number): string {
  const s = Math.max(0, Math.floor((now - Date.parse(iso)) / 1000));
  if (s < 60) return `${s}초 전`;
  if (s < 3600) return `${Math.floor(s / 60)}분 전`;
  return `${Math.floor(s / 3600)}시간 전`;
}

export function durationWord(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}초`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분 ${String(s % 60).padStart(2, "0")}초`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

export const SEVERITY_SHAPE: Record<AlertSeverity, string> = { critical: "■", warning: "▲", info: "●" };
export const SEVERITY_WORD: Record<AlertSeverity, string> = { critical: "장애", warning: "주의", info: "정보" };

/** 형태 기호. 색만으로 상태를 알리지 않게 늘 글자와 함께 둔다 */
export function Shape({ tone, children }: { tone: "success" | "warning" | "danger" | "info" | "off" | "ai"; children: ReactNode }) {
  return (
    <span className={styles.shape} data-tone={tone} aria-hidden="true">
      {children}
    </span>
  );
}

/** 값을 낼 원천을 아직 붙이지 않은 칸(§12-8). 없음·실패와 모양을 가른다 */
export function NotConnected({ what, source, compact }: { what: string; source: string; compact?: boolean }) {
  return (
    <div className={styles.notConnected} data-compact={compact ? "" : undefined}>
      <span className={styles.notConnectedTitle}>
        <span aria-hidden="true">◌ </span>
        {what} 원천 연결 전
      </span>
      <span className={styles.notConnectedSource}>{source}</span>
    </div>
  );
}

/** 칸 하나를 그린다. 연결 전이면 원천 이름을, 아니면 본문을 그린다 */
export function SectionBody<T>({ section, what, compact, children }: {
  section: OpsSection<T>;
  what: string;
  compact?: boolean;
  children: (data: T) => ReactNode;
}) {
  if (section.state === "NOT_CONNECTED") return <NotConnected what={what} source={section.source} compact={compact} />;
  return <>{children(section.data)}</>;
}

/** 기둥·패널 머리. 제목은 §12-2의 패널 제목 단계다 */
export function PanelHead({ title, id, meta, actions }: { title: ReactNode; id?: string; meta?: ReactNode; actions?: ReactNode }) {
  return (
    <div className={styles.panelHead}>
      <h2 id={id} className={styles.panelTitle}>
        {title}
      </h2>
      {meta != null && <span className={styles.panelMeta}>{meta}</span>}
      {actions != null && <span className={styles.panelActions}>{actions}</span>}
    </div>
  );
}

const ENV_WORD: Record<OpsEnvironment, string> = {
  SIMULATOR: "시뮬레이터",
  LOCAL_DOCKER: "로컬 Docker",
  SCHOOL_AWS: "학교 AWS",
  UNKNOWN: "환경 미확인",
};

export function EnvBadge({ env }: { env: OpsEnvironment }) {
  return (
    <span className={styles.envBadge} data-env={env}>
      <span className="sr-only">환경 </span>
      {ENV_WORD[env]}
    </span>
  );
}

/** 폴링 두 주기를 넘기면 지연이다. 관제 판은 sim 1초, 실측은 30초 주기라 실측 기준으로 60초다 */
const LIVE_STALE_MS = 60_000;

/** 값의 출처와 신선도(§12-9). 원천이 없으면 연결 전을, sim이면 시뮬레이션을 먼저 말한다 */
export function OpsSourceChip({ center, now }: { center: ControlCenter | null; now: number }) {
  if (center === null || center.origin === null) {
    return (
      <span className={styles.sourceChip} data-kind="none">
        <span aria-hidden="true">◌ </span>원천 연결 전
      </span>
    );
  }
  if (center.origin === "SIM") {
    return (
      <span className={styles.sourceChip} data-kind="sim">
        시뮬레이션 · 재생
        <span className="sr-only"> · 값은 브라우저 시뮬레이터가 지어낸 것</span>
      </span>
    );
  }
  const at = center.collectedAt;
  const stale = at === null || now - Date.parse(at) > LIVE_STALE_MS;
  return (
    <span className={styles.sourceChip} data-kind={stale ? "stale" : "live"}>
      <span aria-hidden="true">{stale ? "▲ " : "● "}</span>
      실측 · {at === null ? "수집 없음" : ago(at, now)}
      {stale && " · 지연"}
    </span>
  );
}

/**
 * 게이지. 라벨과 값 아래에 막대를 둔다. 막대의 기준선이 임계값 표의 현재값이다(§12-9).
 * 값이 임계를 넘으면 채움이 주의색이고 값 앞에 ▲를 둔다.
 */
export function GaugeRow({ label, value, unit = "%", threshold, max = 100, danger, name }: {
  label: string;
  value: number | null;
  unit?: string;
  threshold: number | null;
  max?: number;
  /** 장애 기준. 넘으면 ■와 장애색이다 */
  danger?: number;
  /** 낭독 이름. 라벨이 약어(CPU·MEM)라 풀어 쓴다 */
  name: string;
}) {
  const tone = value === null || threshold === null ? undefined
    : danger !== undefined && value >= danger ? "danger" : value >= threshold ? "warning" : undefined;
  return (
    <div className={styles.gauge} data-tone={tone}>
      <span className={styles.gaugeLine} aria-hidden="true">
        <span className={styles.gaugeLabel}>{label}</span>
        <span className={`${styles.gaugeValue} num`} data-tone={tone}>
          {tone && <span>{tone === "danger" ? "■ " : "▲ "}</span>}
          {value === null ? <span className={styles.missing}>◌</span> : value}
          {value !== null && <span className={styles.gaugeUnit}>{unit}</span>}
        </span>
      </span>
      <span className={styles.gaugeRail}>
        <MeterRail value={value ?? 0} max={max} threshold={threshold ?? undefined} tone={tone} />
      </span>
      <span className="sr-only">
        {name} {value === null ? "미수집" : `${value}${unit}`}
        {threshold !== null && `, 임계 ${threshold}${unit}`}
        {tone === "warning" && ", 주의"}
        {tone === "danger" && ", 장애"}
      </span>
    </div>
  );
}

/** AI 표지. 선과 호만 쓰는 관리 아이콘 어휘로 그린 네 갈래 반짝임이다 */
export function AiMark({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"
      strokeLinejoin="round" aria-hidden="true">
      <path d="M8 1.75 A6.25 6.25 0 0 0 14.25 8 A6.25 6.25 0 0 0 8 14.25 A6.25 6.25 0 0 0 1.75 8 A6.25 6.25 0 0 0 8 1.75 Z" />
    </svg>
  );
}
