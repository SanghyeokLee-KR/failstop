"use client";

/**
 * 관제 화면 머리. 제목 옆에 값의 출처를 한 번 적는다(sim은 '시뮬레이션' 표식, 실측은 환경 배지와 출처 칩, §12-9). 관제센터는 오른쪽에 추세 범위와 수집 상태를
 * 더 싣는다. 머리 높이는 판형의 예약 높이를 그대로 써서 다른 관리 화면과 제목 자리가 같다(레이아웃 §7).
 */

import type { ReactNode } from "react";
import type { ControlCenter, TrendRange } from "@/lib/admin/types";
import adminStyles from "../admin.module.css";
import StatusShape from "../StatusShape";
import { ago, LIVE_STALE_MS, OpsSource, useClock } from "./parts";
import styles from "./control.module.css";

const RANGES: TrendRange[] = [1, 6, 24];

/** 수집 상태. 판을 받지 못했거나 오래됐으면 문구와 모양을 바꾼다. 'n초 전'이 걸린 조각이라 시계를 직접 탄다 */
export function CollectState({ center, failed }: { center: ControlCenter | null; failed: boolean }) {
  const now = useClock();
  let kind: "live" | "stale" | "none";
  let text: string;
  if (center === null) {
    kind = "none";
    text = "수집 확인 중";
  } else if (center.origin === null) {
    kind = "none";
    text = "수집 원천 연결 전";
  } else if (failed) {
    kind = "stale";
    text = center.collectedAt === null ? "수집 지연" : `수집 지연 · 마지막 ${ago(center.collectedAt, now)}`;
  } else if (center.origin === "LIVE" && (center.collectedAt === null || now - Date.parse(center.collectedAt) > LIVE_STALE_MS)) {
    kind = "stale";
    text = center.collectedAt === null ? "수집 지연" : `수집 지연 · ${ago(center.collectedAt, now)}`;
  } else {
    kind = "live";
    text = "실시간 수집 중";
  }
  return (
    <span className={styles.collect} data-kind={kind} role="status">
      <StatusShape kind={kind === "live" ? "circle" : kind === "stale" ? "triangle" : "dashed"} className={styles.collectShape} />
      {text}
    </span>
  );
}

export function RangeSwitch({ range, onRange }: { range: TrendRange; onRange: (r: TrendRange) => void }) {
  return (
    <span className={styles.rangeWrap}>
      {/* 보이는 이름은 짧게 두고 무엇과 비교하는지는 묶음 이름과 칸마다의 증감 설명이 싣는다 */}
      <span className={styles.rangeLabel} aria-hidden="true">
        추세
      </span>
      <span className={styles.switch} role="group" aria-label="추세 범위 · 이전 같은 길이 구간 대비">
        {RANGES.map((r) => (
          <button key={r} type="button" className={styles.switchBtn} data-size="sm" aria-pressed={range === r} onClick={() => onRange(r)}>
            {r}시간
          </button>
        ))}
      </span>
    </span>
  );
}

export default function OpsHead({ title, desc, center, actions, aside, mark }: {
  title: string;
  /** 설명 줄. 3라운드 화면은 두지 않는다(관리자 설계 4-10). 머리 높이는 예약 높이가 지킨다 */
  desc?: ReactNode;
  center: ControlCenter | null;
  actions?: ReactNode;
  /** 조작 앞에 두는 판독(추세 범위, 수집 상태). 관제센터만 넘긴다 */
  aside?: ReactNode;
  /** 값의 출처 표식을 바꾼다. 관제 판과 다른 세계(시나리오 재생)가 쓴다 */
  mark?: ReactNode;
}) {
  return (
    <header className={adminStyles.pageHead}>
      <div>
        <div className={styles.titleRow}>
          <h1 className={adminStyles.pageTitle}>{title}</h1>
          {mark ?? <OpsSource center={center} />}
        </div>
        {desc != null && <p className={styles.headDesc}>{desc}</p>}
      </div>
      {(aside != null || actions != null) && (
        <div className={styles.headActions}>
          {aside}
          {aside != null && actions != null && <span className={styles.headSep} aria-hidden="true" />}
          {actions}
        </div>
      )}
    </header>
  );
}
