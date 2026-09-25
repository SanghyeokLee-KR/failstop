/**
 * 계측 레일(§6.2). 스칼라 값을 눈금 트랙 위 fill 폭으로 보조 표기한다.
 * 정보의 주 채널은 항상 인접 숫자 텍스트고 레일은 위치 보조 채널이다.
 * fill 기본은 무채. tone은 헬스 도메인(success/warning/danger)만 받는다.
 */

import styles from "./instruments.module.css";

export type MeterTone = "success" | "warning" | "danger";

export interface MeterMark {
  /** value와 같은 단위의 위치값 */
  at: number;
  label?: string;
  /** 마커 2종 병기 시 의도(드래프트) 쪽을 굵기·명도로 구분한다. 색 추가 없음 */
  emphasis?: boolean;
}

export interface MeterRailProps {
  value: number;
  max: number;
  min?: number;
  /** 판독 기준선. 초과 판정 자체는 소비처가 tone으로 내린다 */
  threshold?: number;
  tone?: MeterTone;
  marks?: MeterMark[];
  /** 미지정 시 aria-hidden. 인접 텍스트가 같은 값을 낭독하는 소비처의 중복을 막는다 */
  ariaLabel?: string;
}

const NUM = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 2 });

/** 0~1 클램프 위치. 역구간·영구간·비유한 입력은 0으로 눌러 NaN 폭주를 막는다 */
function ratioOf(v: number, min: number, max: number): number {
  const span = max - min;
  if (!Number.isFinite(span) || span <= 0) return 0;
  const r = (v - min) / span;
  if (!Number.isFinite(r)) return 0;
  return Math.min(1, Math.max(0, r));
}

export function MeterRail({ value, max, min = 0, threshold, tone, marks, ariaLabel }: MeterRailProps) {
  const fillPct = ratioOf(value, min, max) * 100;
  const aria =
    ariaLabel !== undefined
      ? { role: "img", "aria-label": `${ariaLabel} ${NUM.format(value)} / ${NUM.format(max)}` }
      : { "aria-hidden": true as const };
  return (
    <div className={styles.rail} {...aria}>
      <div className={styles.track}>
        <span className={styles.fill} data-tone={tone} style={{ transform: `scaleX(${fillPct / 100})` }} />
      </div>
      {marks?.map((m, i) => (
        <span
          key={i}
          className={styles.mark}
          data-emphasis={m.emphasis || undefined}
          style={{ left: `${ratioOf(m.at, min, max) * 100}%` }}
          title={m.label}
        />
      ))}
      {threshold !== undefined && (
        <span className={styles.threshold} style={{ left: `${ratioOf(threshold, min, max) * 100}%` }} />
      )}
    </div>
  );
}
