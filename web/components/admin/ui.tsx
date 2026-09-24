"use client";

/**
 * 관리 평면 공용 UI 조각. 헬스 색 도메인(success/warning/danger)만 쓰고 시세색을 차용하지 않는다.
 * 상태 표현은 색 단독 금지: 아이콘·라벨을 병기한다(§4-3).
 */

import type { ReactNode } from "react";
import styles from "./admin.module.css";
import { MeterRail, type MeterMark } from "./instruments/MeterRail";
import { useHealthTransition } from "./useHealthTransition";

export type Health = "success" | "warning" | "danger";

// 색약·모노 변별을 위해 심각도별 형태 분리(success 원·warning 삼각·danger 사각). 색은 보조 채널
export const HEALTH_ICON: Record<Health, string> = { success: "●", warning: "▲", danger: "■" };
// 형태·색은 aria-hidden이라 보조기술에 심각도가 안 닿는다. sr-only로 낭독할 라벨
// 라벨 어휘는 NOTICE_SEVERITY_LABEL·admin 홈 HEALTH_LABEL과 통일(warning=주의)
const HEALTH_LABEL: Record<Health, string> = { success: "정상", warning: "주의", danger: "장애" };

/**
 * 헬스 상태 표시. 정상은 필을 세우지 않고 점만 초록, 글자는 잉크로 적는다(§12-3).
 * 이 콘솔은 대부분 정상이라, 정상까지 면을 칠하면 초록으로 덮인 화면에서 빨강 하나를 찾아야 한다.
 */
export function HealthPill({
  health,
  label,
  flashOnChange,
}: {
  health: Health;
  label: string;
  flashOnChange?: boolean;
}) {
  // 정지가 기본값이라 플래시는 옵트인. 시각 규칙은 globals.css [data-transition]이 소유한다
  const flash = useHealthTransition(flashOnChange ? health : null);
  if (health === "success") {
    return (
      <span className={styles.healthInk}>
        <span className={styles.healthDot} aria-hidden="true">
          {HEALTH_ICON[health]}
        </span>{" "}
        {label}
      </span>
    );
  }
  return (
    <span className="status-pill" data-health={health} data-transition={flash ?? undefined}>
      {HEALTH_ICON[health]} {label}
    </span>
  );
}

/** StatTile 보조 게이지. tone은 별도 필드 없이 타일 tone을 상속한다 */
export interface StatTileMeter {
  value: number;
  max: number;
  min?: number;
  threshold?: number;
  marks?: MeterMark[];
}

/**
 * 색 단독 금지라 tone이 있으면 값 옆에 심각도 형태(●/▲/■)를 병기한다.
 * hero는 화면당 단일 대표 지표에만 쓰고, meter는 고정 높이 프리미티브라 갱신 시프트 0이다.
 */
export function StatTile({
  label,
  value,
  sub,
  tone,
  hero,
  alarm,
  unit,
  meter,
  compact,
  title,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  tone?: Health;
  hero?: boolean;
  alarm?: boolean;
  unit?: string;
  meter?: StatTileMeter;
  compact?: boolean;
  /** 라벨 원어 gloss. 평이한 한국어 라벨 뒤로 강등된 기술 용어를 싣는다 */
  title?: string;
}) {
  // 색·형태로만 전달되던 심각도를 낭독한다. alarm은 tone 미지정 시 warning 승격과 동일하게 맞춘다
  const severity = tone ?? (alarm ? "warning" : undefined);
  return (
    <div
      className={styles.statTile}
      data-alarm={alarm ? tone ?? "warning" : undefined}
      data-compact={compact ? "" : undefined}
      title={title}
    >
      <span className={styles.statLabel}>{label}</span>
      <span className={`${styles.statValue} num`} data-tone={tone ?? undefined} data-hero={hero ? "" : undefined}>
        {tone && (
          <span className={styles.toneIcon} aria-hidden="true">
            {HEALTH_ICON[tone]}
          </span>
        )}
        {severity && <span className="sr-only">심각도 {HEALTH_LABEL[severity]}</span>}
        {value}
        {unit && <span className="unit-suffix">{unit}</span>}
      </span>
      {sub != null && (
        <span className={styles.statAux}>
          <span className={styles.statSub}>{sub}</span>
        </span>
      )}
      {meter && (
        // 값·라벨 텍스트가 이미 낭독되므로 레일은 aria-hidden(ariaLabel 미지정)
        <MeterRail
          value={meter.value}
          max={meter.max}
          min={meter.min}
          threshold={meter.threshold}
          marks={meter.marks}
          tone={tone}
        />
      )}
    </div>
  );
}

/** 화면 헤더. 제목 + 설명 + 우측 액션 슬롯 */
export function PageHeader({ title, desc, actions }: { title: string; desc?: string; actions?: ReactNode }) {
  return (
    <header className={styles.pageHead}>
      <div>
        <h1 className={styles.pageTitle}>{title}</h1>
        {desc && <p className={styles.pageDesc}>{desc}</p>}
      </div>
      {actions != null && <div className={styles.pageActions}>{actions}</div>}
    </header>
  );
}

/** 권한 부족 안내(읽기는 허용, 변경 버튼만 잠길 때 섹션 상단에 병기) */
export function PermissionNote({ children }: { children: ReactNode }) {
  return (
    <p className={styles.permNote} role="note">
      {children}
    </p>
  );
}
