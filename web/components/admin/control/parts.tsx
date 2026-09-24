"use client";

/**
 * 관제센터 화면들이 함께 쓰는 작은 조각. 상태 어휘는 디자인 시스템 §12-3, 관제센터 규칙은 §12-9를 따른다.
 */

import type { ReactNode } from "react";
import { useBeat } from "@/lib/admin/ticker";
import { ago, mmss } from "@/lib/admin/timeText";
import type { AlertSeverity, ControlCenter, OpsEnvironment, OpsSection } from "@/lib/admin/types";
import { AdminIcon } from "../icons";
import { MeterRail } from "../instruments/MeterRail";
import styles from "./control.module.css";

/**
 * 콘솔 공용 박자에 맞춰 다시 그리는 시각. 남은 시간과 'N초 전'처럼 벽시계로 읽는 글자만 쓴다.
 * 넓은 판 위에서 받아 아래로 내리지 말고, 그 글자를 그리는 작은 조각 안에서 부른다
 */
export function useClock(intervalMs = 1000): number {
  return useBeat(intervalMs);
}

/** 'n초 전'. 이 조각만 매초 다시 그린다 */
export function Ago({ at, prefix = "" }: { at: string; prefix?: string }) {
  const now = useClock();
  return (
    <>
      {prefix}
      {ago(at, now)}
    </>
  );
}

/** 마감까지 남은 mm:ss. 마감이 지나면 fallback을 그린다 */
export function Remain({ until, fallback = null }: { until: string; fallback?: ReactNode }) {
  const now = useClock();
  const left = Date.parse(until) - now;
  return <>{left > 0 ? mmss(left) : fallback}</>;
}

// 시각 글자는 lib에 있다. AI 상담의 답(lib)과 화면 조각이 같은 말을 쓰게 한곳에 두고 여기서 다시 내보낸다
export { ago, durationWord, hm, hmmss, hms, mmss } from "@/lib/admin/timeText";

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
  PERSONAL_AWS: "개인 AWS",
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

/** 폴링 두 주기를 넘기면 지연이다. 관제 판은 sim 1초, 실측은 15초 주기(opsCenter)라 실측 기준으로 30초다 */
export const LIVE_STALE_MS = 30_000;

/** 값의 출처와 신선도(§12-9). 원천이 없으면 연결 전을, sim이면 시뮬레이션을 먼저 말한다 */
export function OpsSourceChip({ center }: { center: ControlCenter | null }) {
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
  return <LiveChip at={center.collectedAt} />;
}

/** 실측 칩. 'N초 전'이 매초 바뀌므로 시계는 이 조각만 탄다 */
function LiveChip({ at }: { at: string | null }) {
  const now = useClock();
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
 * 게이지 한 줄. 라벨, 막대, 값 순서이고 값은 오른쪽 정렬이다. 막대의 기준선이 임계값 표의 현재값이다(§12-9).
 * 값이 임계를 넘으면 채움이 주의색이고 값 앞에 ▲를 둔다.
 */
export function GaugeRow({ label, value, unit = "%", threshold, max = 100, danger, name, suffix }: {
  label: string;
  value: number | null;
  unit?: string;
  threshold: number | null;
  max?: number;
  /** 장애 기준. 넘으면 ■와 장애색이다 */
  danger?: number;
  /** 낭독 이름. 라벨이 약어(CPU·MEM)라 풀어 쓴다 */
  name: string;
  /** 값 뒤 단위 앞에 붙는 분모(예: '/128') */
  suffix?: string;
}) {
  const tone = value === null || threshold === null ? undefined
    : danger !== undefined && value >= danger ? "danger" : value >= threshold ? "warning" : undefined;
  return (
    <div className={styles.gauge} data-tone={tone}>
      <span className={styles.gaugeLabel} aria-hidden="true">
        {label}
      </span>
      <span className={styles.gaugeRail} aria-hidden="true">
        <MeterRail value={value ?? 0} max={max} threshold={threshold ?? undefined} tone={tone} />
      </span>
      <span className={`${styles.gaugeValue} num`} data-tone={tone} aria-hidden="true">
        {tone && <span>{tone === "danger" ? "■ " : "▲ "}</span>}
        {value === null ? <span className={styles.missing}>◌</span> : value}
        {value !== null && (
          <span className={styles.gaugeUnit}>
            {suffix}
            {unit}
          </span>
        )}
      </span>
      <span className="sr-only">
        {name} {value === null ? "미수집" : `${value}${suffix ?? ""}${unit}`}
        {threshold !== null && `, 임계 ${threshold}${unit}`}
        {tone === "warning" && ", 주의"}
        {tone === "danger" && ", 장애"}
      </span>
    </div>
  );
}

/** AI 표지. 관리 아이콘 한 벌의 연산 칩이다. 반짝임 장식을 쓰지 않는다(§12-10) */
export function AiMark({ size = 16 }: { size?: number }) {
  return <AdminIcon name="ai" size={size} />;
}
