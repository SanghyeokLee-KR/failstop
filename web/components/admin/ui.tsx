/**
 * 관리 평면 공용 UI 조각. 헬스 색 도메인(success/warning/danger)만 쓰고 시세색을 차용하지 않는다.
 * 상태 표현은 색 단독 금지: 아이콘·라벨을 병기한다(§4-3).
 */

import type { ReactNode } from "react";
import styles from "./admin.module.css";

export type Health = "success" | "warning" | "danger";

// 색약·모노 변별을 위해 심각도별 형태 분리(success 원·warning 삼각·danger 사각). 색은 보조 채널
export const HEALTH_ICON: Record<Health, string> = { success: "●", warning: "▲", danger: "■" };
// 형태·색은 aria-hidden이라 보조기술에 심각도가 안 닿는다. sr-only로 낭독할 라벨
// 라벨 어휘는 NOTICE_SEVERITY_LABEL·admin 홈 HEALTH_LABEL과 통일(warning=주의)
const HEALTH_LABEL: Record<Health, string> = { success: "정상", warning: "주의", danger: "위험" };

/** 헬스 상태 필. 항상 아이콘+라벨 병기 */
export function HealthPill({ health, label }: { health: Health; label: string }) {
  return (
    <span className="status-pill" data-health={health}>
      {HEALTH_ICON[health]} {label}
    </span>
  );
}

/**
 * 지표 타일. 헬스 톤은 값 강조에만 쓴다. hero는 화면당 단일 대표 지표에만(700 승격).
 * 색 단독 금지라 tone이 있으면 값 옆에 심각도 형태(●/▲/■)를 병기한다.
 * alarm은 위험 타일을 톤 보더 + 좌측 상태 레일로 승격해 균일 격자에서 경보를 도드라지게 한다.
 */
export function StatTile({
  label,
  value,
  sub,
  tone,
  hero,
  alarm,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  tone?: Health;
  hero?: boolean;
  alarm?: boolean;
}) {
  // 색·형태로만 전달되던 심각도를 낭독한다. alarm은 tone 미지정 시 warning 승격과 동일하게 맞춘다
  const severity = tone ?? (alarm ? "warning" : undefined);
  return (
    <div className={styles.statTile} data-alarm={alarm ? tone ?? "warning" : undefined}>
      <span className={styles.statLabel}>{label}</span>
      <span className={`${styles.statValue} num`} data-tone={tone ?? undefined} data-hero={hero ? "" : undefined}>
        {tone && (
          <span className={styles.toneIcon} aria-hidden="true">
            {HEALTH_ICON[tone]}
          </span>
        )}
        {severity && <span className="sr-only">심각도 {HEALTH_LABEL[severity]}</span>}
        {value}
      </span>
      {sub != null && <span className={styles.statSub}>{sub}</span>}
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
