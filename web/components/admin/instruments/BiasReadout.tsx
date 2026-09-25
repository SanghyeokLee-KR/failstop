/**
 * 마커는 전부 무채·형태 구분이라 시세색 규약과 충돌하지 않는다(§6.2).
 * 값은 NUMERIC 문자열 계약을 유지하고 Number 변환은 위치 계산에만 쓴다.
 */

import styles from "./instruments.module.css";

const TICKS = [
  { at: -1, label: "-1.0" },
  { at: -0.5, label: "-0.5" },
  { at: 0, label: "0" },
  { at: 0.5, label: "+0.5" },
  { at: 1, label: "+1.0" },
] as const;

/** -1~+1 도메인을 0~100% 위치로. 비유한 입력은 중앙(중립)으로 눌러 마커 이탈을 막는다 */
function pctOf(v: string): number {
  const n = Number(v);
  if (!Number.isFinite(n)) return 50;
  return Math.min(1, Math.max(0, (n + 1) / 2)) * 100;
}

export interface BiasReadoutProps {
  target: string;
  measured: string;
  effective: string;
}

export function BiasReadout({ target, measured, effective }: BiasReadoutProps) {
  return (
    <div
      className={styles.biasReadout}
      role="img"
      aria-label={`방향 바이어스 판독: 목표 ${target}, 실측 ${measured}, 유효 ${effective}`}
    >
      <div className={styles.biasRail}>
        {TICKS.map((t) => (
          <span
            key={t.at}
            className={styles.biasTick}
            data-center={t.at === 0 ? "" : undefined}
            style={{ left: `${((t.at + 1) / 2) * 100}%` }}
          />
        ))}
        <span className={styles.biasSlot} style={{ transform: `translateX(${pctOf(effective)}cqw)` }}>
          <span className={styles.biasMarkEffective} />
        </span>
      </div>
      <span className={styles.biasSlot} style={{ transform: `translateX(${pctOf(target)}cqw)` }} aria-hidden="true">
        {/* 글꼴 삼각(9px)은 글꼴마다 크기와 기준선이 달라 같은 격자의 SVG로 그린다 */}
        <svg className={styles.biasMarkTarget} width="9" height="8" viewBox="0 0 9 8">
          <path d="M0.8 0.8 H8.2 L4.5 7.2 Z" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
        </svg>
      </span>
      <span className={styles.biasSlot} style={{ transform: `translateX(${pctOf(measured)}cqw)` }} aria-hidden="true">
        <svg className={styles.biasMarkMeasured} width="9" height="8" viewBox="0 0 9 8">
          <path d="M4.5 0.6 L8.4 7.4 H0.6 Z" fill="currentColor" />
        </svg>
      </span>
      <div className={styles.biasScale} aria-hidden="true">
        {TICKS.map((t) => (
          <span
            key={t.at}
            className={styles.biasScaleLabel}
            data-edge={t.at === -1 ? "start" : t.at === 1 ? "end" : undefined}
            style={{ left: `${((t.at + 1) / 2) * 100}%` }}
          >
            {t.label}
          </span>
        ))}
      </div>
    </div>
  );
}
